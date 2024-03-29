(ns ditto.string
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

(defn slice-left
  "slices l from left side of s.
   or if it's not on the left side, returns s."
  [s l]
  (if-not (str/starts-with? s l)
    s
    (subs s (count l))))

(defn slice-newlines
  [s]
  (loop [this s]
    (cond
      (str/starts-with? this "\n") (recur (str-rest this))
      (str/ends-with? this "\n") (recur (str-butlast this))
      :else this)))