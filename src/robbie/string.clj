(ns robbie.string
  (:gen-class) 
  (:require [clojure.string :as str]))

(defn str-rest
  "returns string without the first character."
  [s]
  (if (= s "")
    ""
    (subs s 1)))

(defn str-butlast
  "returns every character in string but the last."
  [s]
  (if (= s "")
    ""
    (subs s 0 (dec (count s)))))

(defn trim-left
  "trims l from left side of s.
   or if it's not on the left side, returns s."
  [s l]
  (if (false? (str/starts-with? s l))
    s
    (loop [this s left l]
      (if (= left "")
        this
        (recur (str-rest this) (str-rest left))))))

(defn trim-newlines
  [s]
  (loop [this s]
    (cond
      (str/starts-with? this "\n") (recur (str-rest this))
      (str/ends-with? this "\n") (recur (str-butlast this))
      :else this)))