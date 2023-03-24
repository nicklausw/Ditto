(ns ditto.core
  (:gen-class)
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
            [ditto.string :refer [slice-left slice-newlines]]
            [ditto.utility :refer [third vec-append vec-prepend]])
  (:import [java.io FileNotFoundException]
           [java.time Instant]))

; you can be in ditto mode or preset mode
; where preset data is in preset-config.json
; example:
;{
;  "name": "Robbie",
;  "personality": "Robbie is a person.",
;  "command": "rob"
;}
(def mode (atom nil))
(def preset-name (atom ""))
(def preset-personality (atom ""))

; :gpt3 or :chatgpt
(def gpt-type (atom nil))

(def generation-command (atom "gen"))

(def state (atom nil))

(def bot-id (atom nil))

(def changed (atom false))

(def memory
  (atom 
   (or (some-> (try 
                 (slurp "memory.json") 
                 (catch FileNotFoundException _ nil)) 
               (cheshire/parse-string true) 
               :guilds)
       {})))

(defn get-memory
  "Gets property from memory via array of nested keywords.
   An alternative must be provided for if nothing is found.
   NOTE: this converts non-keywords in the array to keywords."
  [path-array alternative]
  (-> (get-in @memory (mapv keyword path-array))
    (or alternative)))

(defn set-memory
  "Sets property in memory and resets changed switch to true."
  [path-array new-value] 
  (reset! memory (assoc-in @memory (mapv keyword path-array) new-value)) 
  (reset! changed true))

(defn get-bot-name
  [guild-id]
  (case @mode
    :ditto (get-memory [guild-id :bot_name] "Ditto")
    :preset @preset-name))

(defn set-bot-name
  [guild-id name]
  (set-memory [guild-id :bot_name] name))

(defn get-bot-personality
  [guild-id]
  (case @mode
    :ditto (get-memory [guild-id :personality] "")
    :preset @preset-personality))

(defn set-bot-personality
  [guild-id personality]
  (set-memory [guild-id :personality] personality))

(defn get-user-nickname
  [guild-id user-id]
  (get-memory [guild-id :nicknames user-id] "Person"))

(defn set-user-nickname
  [guild-id user-id nickname]
  (set-memory [guild-id :nicknames user-id] nickname))

(defn get-messages
  [guild-id channel-id]
  (get-memory [guild-id channel-id :messages] []))

(defn append-new-message
  "returns array with old messages listed. does not allow
   prompt-and-response message length to exceed 1200 characters,
   even if this means a blank message history."
  [old-messages new-message-user new-message-bot user-nickname bot-nickname]
  (let [full-list (into old-messages [{:role "user" :content (str user-nickname ": " new-message-user)} 
                                      {:role "assistant" :content (str bot-nickname ": " new-message-bot)}])]
    (loop [list full-list
           totals (mapv #(count (:content %)) full-list)
           sum (reduce + totals)]
      (if (<= sum 1200)
        (vec list)
        (recur (rest list) (rest totals) (- sum (first totals)))))))

(defn get-model-data
  [messages-or-prompt]
  (case @gpt-type
    :gpt3 {:model "text-davinci-003"
           :prompt messages-or-prompt
           :temperature 0.9
           :presence_penalty 0.5
           :frequency_penalty 0.5
           :max_tokens 1000}
    :chatgpt {:model "gpt-3.5-turbo"
              :messages messages-or-prompt}))

(defn get-openai-response
  [s old-messages user-nickname bot-nickname personality]
  (let [header {:role "system" :content (str "Your name is " bot-nickname ".\n" personality)}
        footer {:role "user" :content (str user-nickname ": " s)}
        messages (-> old-messages
                     (vec-prepend header)
                     (vec-append footer))
        processed (case @gpt-type
                    ; OpenAI's gpt3 completions API wants one big string prompt
                    :gpt3 (let [strings (mapv :content messages) 
                                with-bot-name (conj strings (str bot-nickname ": "))] 
                            (str/join "\n" with-bot-name))
                    :chatgpt messages)
        data (get-model-data processed)]
    (pprint processed)
    (println "sending prompt ^")
    (client/post
     (case @gpt-type
       :gpt3 "https://api.openai.com/v1/completions"
       :chatgpt "https://api.openai.com/v1/chat/completions")
     {:headers {"Authorization" (str "Bearer " (System/getenv "OPENAI_TOKEN"))}
      :content-type :json
      :form-params data})))

(def error-message
  "[The OpenAI server sent back an error. Please try again in a minute; the server may be busy.]")

(defn trim-name
  [s name]
  (slice-left s (str name ": ")))

(defn get-response
  [s messages user-nickname bot-nickname personality]
  (try
    (let [response (get-openai-response s messages user-nickname bot-nickname personality)]
      (if (= (:status response) 200)
        (let [body (cheshire/parse-string (:body response) true)
              text (case @gpt-type
                     :gpt3 (-> body :choices first :text)
                     :chatgpt (-> body :choices first :message :content))]
          (-> text
              slice-newlines
              str/trim
              (trim-name bot-nickname)))
        error-message))
    (catch Exception e (pprint e) error-message)))

(defmulti handle-event (fn [type _data] type))

(defmethod handle-event :message-create
  [_ {:keys [guild-id channel-id author content] :as _data}]
  (cond
    (str/starts-with? content (str "/" @generation-command))
    (do
      (println "generating response for message...")
      #_{:clj-kondo/ignore [:unresolved-var]}
      (discord-rest/trigger-typing-indicator! (:rest @state) channel-id) 
      (let [trimmed-content (str/trim (slice-left content (str "/" @generation-command)))
            bot-nickname (get-bot-name guild-id) 
            personality (get-bot-personality guild-id) 
            user-nickname (get-user-nickname guild-id (:id author)) 
            messages (get-messages guild-id channel-id)
            bot-content (get-response trimmed-content messages user-nickname bot-nickname personality)
            new-messages (append-new-message messages trimmed-content bot-content user-nickname bot-nickname)] 
        #_{:clj-kondo/ignore [:unresolved-var]}
        (discord-rest/create-message! (:rest @state) channel-id 
                                      :content (str (mention-user author) " " bot-content))
        (set-memory [guild-id channel-id :messages] new-messages)
        (println "done.")))

    (str/starts-with? content "/nickname ")
    (let [trimmed-content (str/trim (slice-left content "/nickname "))]
      (println "setting nickname to" trimmed-content "...")
      (set-user-nickname guild-id (:id author) trimmed-content)
      #_{:clj-kondo/ignore [:unresolved-var]}
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " Set name to " trimmed-content ".")))

    (and (str/starts-with? content "/botname ") (= @mode :ditto))
    (let [trimmed-content (str/trim (slice-left content "/botname "))]
      (println "setting bot nickname to" trimmed-content "...")
      (set-bot-name guild-id trimmed-content)
      #_{:clj-kondo/ignore [:unresolved-var]}
      (discord-rest/modify-current-user-nick! (:rest @state) guild-id trimmed-content)
      #_{:clj-kondo/ignore [:unresolved-var]}
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " My name is now " trimmed-content ".")))

    (and (str/starts-with? content "/personality ") (= @mode :ditto))
    (let [trimmed-content (str/trim (slice-left content "/personality "))]
      (println "setting bot personality to" trimmed-content "...")
      (set-bot-personality guild-id trimmed-content)
      #_{:clj-kondo/ignore [:unresolved-var]}
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " My personality prompt is now " trimmed-content ".")))

    (= content "/reset")
    (do
      (set-memory [guild-id channel-id :messages] [])
      (println "message history reset for channel " channel-id))))

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

(defn print-args-and-quit []
  (println "args: [ditto/preset] [gpt3/chatgpt] [discord token]")
  (System/exit 1))

(defn -main [& args]
  (when (not= (count args) 3)
    (print-args-and-quit))
  (case (first args)
    "ditto" (reset! mode :ditto)
    "preset" (let [config (cheshire/parse-string (slurp "preset-config.json") true)] 
               (reset! preset-name (:name config)) 
               (reset! preset-personality (:personality config)) 
               (reset! generation-command (:command config)) 
               (reset! mode :preset))
    (print-args-and-quit))
  (case (second args)
    "gpt3" (reset! gpt-type :gpt3)
    "chatgpt" (reset! gpt-type :chatgpt)
    (print-args-and-quit))
  (reset! state (start-bot! (third args) :guild-messages))
  (reset! bot-id (:id @#_{:clj-kondo/ignore [:unresolved-var]}
                       (discord-rest/get-current-user! (:rest @state))))
  (reset-timer)
  (try
    (message-pump! (:events @state) handle-event)
    (finally (stop-bot! @state))))
