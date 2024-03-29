(defproject ditto "1.0"
  :description "GPT-3/ChatGPT Discord bot."
  :url "http://github.com/nicklausw/ditto"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.suskalo/discljord "1.1.1"]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 [jarohen/chime "0.3.3"]]
  :repl-options {:init-ns ditto.core}
  :main ditto.core
  :profiles {:uberjar {:aot :all}})
