(ns ditto.core
  (:gen-class)
  (:import [java.time Instant])
  (:require [chime.core :as chime]
            [cheshire.core :as cheshire]
            [clj-http.client :as client]
            [clojure.core.async :refer [chan close!]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [discljord.connections :as discord-ws]
            [discljord.events :refer [message-pump!]]
            [discljord.formatting :refer [mention-user]]
            [discljord.messaging :as discord-rest]
            [ditto.string :refer [trim-left trim-newlines]]))


(def state (atom nil))

(def bot-id (atom nil))

(def changed (atom false))

(def slurped-memory (cheshire/parse-string (slurp "memory.json") true))

; the bot can remember past conversations
(def memory (atom (:memory slurped-memory)))

; the bot can remember nicknames
(def nicknames (atom (:nicknames slurped-memory)))

; the bot has a different personality per guild
(def personalities (atom (:personalities slurped-memory)))

(defn vec-remove [f e] (vec (remove f e)))

(defn empty-array-if-nil
  [array]
  (if (nil? array) [] array))

(defn get-personality
  "note: this returns the whole object.
   { guild-id, personality, name }"
  [guild-id]
  (let [empty-if-nil (fn [name] (if (nil? name) {:guild-id guild-id :personality "" :name "Ditto"} name))]
    (-> (some #(when (= (:guild-id %) guild-id) %) @personalities)
         empty-if-nil)))

(defn set-bot-prompt
  [guild-id prompt]
  (let [existing (get-personality guild-id)]
    (reset!
     personalities 
     (conj (vec-remove #(= (:guild-id %) guild-id) @personalities) 
           (assoc existing :personality prompt)))))

(defn set-bot-name
  [guild-id name]
  (let [existing (get-personality guild-id)]
    (reset!
     personalities
     (conj (vec-remove #(= (:guild-id %) guild-id) @personalities)
           (assoc existing :name name)))))

(defn get-user-nickname
  [guild-id user-id] 
  (let [person-if-nil (fn [name] (if (nil? name) "Person" name))]
    (->> (some #(when (and (= (:guild-id %) guild-id) (= (:user-id %) user-id)) (:nickname %)) @nicknames) 
         person-if-nil)))

(defn set-user-nickname
  [guild-id user-id nickname]
  (reset!
   nicknames
   (conj (vec-remove #(and (= guild-id (:guild-id %)) (= user-id (:user-id %))) @nicknames)
         {:guild-id guild-id :user-id user-id :nickname nickname})))

(defn append-new-message
  [old-messages new-message-user new-message-bot user-nickname bot-nickname]
  (let [new-list (into old-messages [(str user-nickname ": " new-message-user) (str bot-nickname ": " new-message-bot)])]
    (if (> (count new-list) 6)
      (-> new-list
          rest
          rest
          vec)
      new-list)))

(defn get-messages
  [guild-id channel-id] 
  (->> (some #(when (and (= (:guild-id %) guild-id) (= (:channel-id %) channel-id)) (:messages %)) @memory) 
       empty-array-if-nil))

(defn update-message-history
  [guild-id
   channel-id
   old-messages
   new-message-user
   new-message-bot
   user-nickname
   bot-nickname]
  (let [updated-messages (append-new-message old-messages new-message-user new-message-bot user-nickname bot-nickname)]
    (reset!
     memory
     (conj (vec-remove #(and (= channel-id (:channel-id %)) (= guild-id (:guild-id %))) @memory) 
           {:guild-id guild-id
            :channel-id channel-id 
            :messages updated-messages}))))

(defn delete-message-history
  [guild-id
   channel-id]
  (reset! 
   memory 
   (conj (vec-remove #(and (= channel-id (:channel-id %)) (= guild-id (:guild-id %))) @memory) 
         {:guild-id guild-id
          :channel-id channel-id
          :messages []})))

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
    (str/starts-with? content "/gen")
    (let [trimmed-content (trim-left content "/gen")
          personality-obj (get-personality guild-id)
          bot-nickname (:name personality-obj)
          personality (:personality personality-obj)
          user-nickname (get-user-nickname guild-id (:id author))]
      (println "generating response for message...")
      (discord-rest/trigger-typing-indicator! (:rest @state) channel-id)
      (let [messages (get-messages guild-id channel-id)
            bot-content (get-response trimmed-content messages user-nickname bot-nickname personality)]
        (discord-rest/create-message! (:rest @state) channel-id
                                      :content (str (mention-user author) " " bot-content))
        (update-message-history guild-id channel-id messages trimmed-content bot-content user-nickname bot-nickname)
        (reset! changed true)
        (println "done.")))

    (str/starts-with? content "/nickname ")
    (let [trimmed-content (trim-left content "/nickname ")]
      (println "setting nickname to" trimmed-content "...")
      (set-user-nickname guild-id (:id author) trimmed-content)
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " Set name to " trimmed-content ".")))

    (str/starts-with? content "/botname ")
    (let [trimmed-content (trim-left content "/botname ")]
      (println "setting bot nickname to" trimmed-content "...")
      (set-bot-name guild-id trimmed-content)
      (discord-rest/modify-current-user-nick! (:rest @state) guild-id trimmed-content)
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " My name is now " trimmed-content ".")))

    (str/starts-with? content "/personality ")
    (let [trimmed-content (trim-left content "/personality ")]
      (println "setting bot personality to" trimmed-content "...")
      (set-bot-prompt guild-id trimmed-content)
      (discord-rest/create-message! (:rest @state) channel-id
                                    :content (str (mention-user author) " My personality prompt is now " trimmed-content ".")))

    (= content "/reset")
    (do
      (delete-message-history guild-id channel-id)
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
    (spit "memory.json" (cheshire/generate-string {:memory @memory
                                                   :nicknames @nicknames
                                                   :personalities @personalities} {:pretty true}))
    (reset! changed false))
  (reset-timer))

(defn -main [& args]
  (reset! state (start-bot! (System/getenv "DITTO_TOKEN") :guild-messages))
  (reset! bot-id (:id @(discord-rest/get-current-user! (:rest @state))))
  (reset-timer)
  (try
    (message-pump! (:events @state) handle-event)
    (finally (stop-bot! @state))))
