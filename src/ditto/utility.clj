(ns ditto.utility)

(defn third [x] (first (next (next x))))

(defn vec-prepend
  [v item]
  (into [item] v))

(defn vec-append
  [v item]
  (into v [item]))