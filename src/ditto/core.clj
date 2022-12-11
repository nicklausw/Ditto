(ns ditto.core
  (:gen-class)
  (:import [java.time Instant]
           [java.io FileNotFoundException])
  (:require [cheshire.core :as cheshire]
            [chime.core :as chime]
            [clj-http.client :as client]
            [clojure.core.async :refer [chan close!]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [discljord.connections :as discord-ws]
            [discljord.events :refer [message-pump!]]
            [discljord.formatting :refer [mention-user]]
            [discljord.messaging :as discord-rest]
            [ditto.string :refer [trim-left trim-newlines]]))

; you can be in ditto mode or preset mode
; where bot name is in name.txt
; personality is in personality.txt
; and command for generation, like
; rob for /rob used in place of /gen,
; is kept in generation-command.txt.
(def mode (atom nil))
(def preset-name (atom ""))
(def preset-personality (atom ""))

(def generation-command (atom "gen"))

(def state (atom nil))

(def bot-id (atom nil))

(def changed (atom false))

(def memory
  (atom 
   (-> (try 
         (slurp "memory.json") 
         (catch FileNotFoundException _ "{}"))
       (cheshire/parse-string true)
       :guilds)))

(defn get-bot-name
  [guild-id]
  (case @mode
    :ditto (-> @memory
               ((keyword guild-id))
               :bot_name
               (or "Ditto"))
    :preset @preset-name))

(defn get-bot-personality
  [guild-id]
  (case @mode
    :ditto (-> @memory 
               ((keyword guild-id)) 
               :personality 
               (or ""))
    :preset @preset-personality))

(defn get-user-nickname
  [guild-id user-id]
  (-> @memory
      ((keyword guild-id))
      :nicknames
      ((keyword user-id))
      (or "Person")))

(defn get-messages
  [guild-id channel-id]
  (-> @memory
      ((keyword guild-id))
      ((keyword channel-id))
      :messages
      (or [])))

(defn set-bot-prompt
  [guild-id prompt] 
  (reset! memory (update-in @memory [(keyword guild-id)] assoc :personality prompt)))

(defn set-bot-name
  [guild-id name]
  (reset! memory (update-in @memory [(keyword guild-id)] assoc :bot_name name)))

(defn set-user-nickname
  [guild-id user-id nickname]
  (reset! memory (update-in @memory [(keyword guild-id) :nicknames] assoc (keyword user-id) nickname)))

(defn append-new-message
  [old-messages new-message-user new-message-bot user-nickname bot-nickname]
  (let [new-list (into old-messages [(str user-nickname ": " new-message-user) (str bot-nickname ": " new-message-bot)])]
    (if (> (count new-list) 6)
      (-> new-list (nthrest 2) vec) 
      new-list)))

(defn messages-to-prompts
  [messages]
  (loop [processed ""
         unprocessed messages]
    (if (= unprocessed [])
      processed
      (recur (str processed (first unprocessed) "\n") (rest unprocessed)))))

(defn get-openai-response
  [s messages user-nickname bot-nickname personality]
  (let [prompt (str personality "\n" (messages-to-prompts messages) user-nickname ": " s "\n" bot-nickname ": ")]
    (println "sending prompt " prompt)
    (client/post
     "https://api.openai.com/v1/completions"
     {:headers {"Authorization" (str "Bearer " (System/getenv "OPENAI_TOKEN"))}
      :content-type :json
      :form-params
      {:model "text-davinci-003"
       :prompt prompt
       :temperature 0.9
       :presence_penalty 0.5
       :frequency_penalty 0.5
       :max_tokens 1000}})))

(def error-message
  "[The OpenAI server sent back an error. Please try again in a minute; the server may be busy.]")

(defn get-response
  [s messages user-nickname bot-nickname personality]
  (try
    (let [response (get-openai-response s messages user-nickname bot-nickname personality)]
      (if (= (:status response) 200)
        (let [body (cheshire/parse-string (:body response) true)]
          (trim-newlines (:text (first (:choices body)))))
        error-message))
    (catch Exception e (pprint e) error-message)))

(defmulti handle-event (fn [type _data] type))

(defmethod handle-event :message-create
  [_ {:keys [guild-id channel-id author content] :as _data}]
  (cond
    (str/starts-with? content (str "/" @generation-command))
    (do
      (println "generating response for message...")
      (discord-rest/trigger-typing-indicator! (:rest @state) channel-id) 
      (let [trimmed-content (trim-left content (str "/" @generation-command)) 
            bot-nickname (get-bot-name guild-id) 
            personality (get-bot-personality guild-id) 
            user-nickname (get-user-nickname guild-id (:id author)) 
            messages (get-messages guild-id channel-id)
            bot-content (get-response trimmed-content messages user-nickname bot-nickname personality)
            new-messages (append-new-message messages trimmed-content bot-content user-nickname bot-nickname)] 
        (discord-rest/create-message! (:rest @state) channel-id 
                                      :content (str (mention-user author) " " bot-content))
        (reset! memory (update-in @memory [(keyword guild-id) (keyword channel-id)] assoc :messages new-messages))
        (reset! changed true)
        (println "done.")))

    (str/starts-with? content "/nickname ")
    (let [trimmed-content (trim-left content "/nickname ")]
      (println "setting nickname to" trimmed-content "...")
      (set-user-nickname guild-id (:id author) trimmed-content)
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " Set name to " trimmed-content ".")))

    (and (str/starts-with? content "/botname ") (= @mode :ditto))
    (let [trimmed-content (trim-left content "/botname ")]
      (println "setting bot nickname to" trimmed-content "...")
      (set-bot-name guild-id trimmed-content)
      (discord-rest/modify-current-user-nick! (:rest @state) guild-id trimmed-content)
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " My name is now " trimmed-content ".")))

    (and (str/starts-with? content "/personality ") (= @mode :ditto))
    (let [trimmed-content (trim-left content "/personality ")]
      (println "setting bot personality to" trimmed-content "...")
      (set-bot-prompt guild-id trimmed-content)
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " My personality prompt is now " trimmed-content ".")))

    (= content "/reset")
    (do
      (reset! memory (update-in @memory [(keyword guild-id) (keyword channel-id)] assoc :messages []))
      (println "message history reset for channel " channel-id)
      (reset! changed true))))

(defmethod handle-event :default [_ _])

(defn start-bot! [token & intents]
  (let [event-channel (chan 100)
        gateway-connection (discord-ws/connect-bot! token event-channel :intents (set intents))
        rest-connection (discord-rest/start-connection! token)]
    {:events  event-channel
     :gateway gateway-connection
     :rest    rest-connection}))

(defn stop-bot! [{:keys [rest gateway events] :as _state}]
  (discord-rest/stop-connection! rest)
  (discord-ws/disconnect-bot! gateway)
  (close! events))

(declare minute-function)
(defn reset-timer
  "resets timer for the once-per-minute function."
  [] (chime/chime-at [(.plusSeconds (Instant/now) 60)] minute-function))

(defn minute-function
  "function that runs once per minute, checking if memory.json needs to be rewritten."
  [time] 
  (when (true? @changed)
    (println (.toString time) ": Writing to memory.json")
    (spit "memory.json" (cheshire/generate-string {:guilds @memory} {:pretty true}))
    (reset! changed false))
  (reset-timer))

(defn -main [& args]
  (assert (= (count args) 2) "args: [ditto/preset] [discord token]")
  (case (first args)
    "ditto" (reset! mode :ditto)
    "preset" (do
               (reset! preset-name (slurp "name.txt"))
               (reset! preset-personality (slurp "personality.txt"))
               (reset! generation-command (slurp "generation-command.txt"))
               (reset! mode :preset))
    (do
      (println "args: [ditto/preset] [discord token]")
      (System/exit 1)))
  (reset! state (start-bot! (second args) :guild-messages))
  (reset! bot-id (:id @(discord-rest/get-current-user! (:rest @state))))
  (reset-timer)
  (try
    (message-pump! (:events @state) handle-event)
    (finally (stop-bot! @state))))
