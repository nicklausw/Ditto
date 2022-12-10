(defproject robbie "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.suskalo/discljord "1.1.1"]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 [jarohen/chime "0.3.3"]]
  :repl-options {:init-ns robbie.core}
  :main robbie.core)
