(ns ditto.utility)

(defn third [x] (-> x next next first))

(defn vec-prepend
  [v item]
  (into [item] v))

(defn vec-append
  [v item]
  (into v [item]))