(defproject ala_program "0.1.0-SNAPSHOT"
  :main ala_program.core
  :description "Web service to randomly generate library conference session names"
  :url "http://github.com/brinkmwj/ala_program"
  :license {:name "GNU General Public License"
            :url "https://github.com/brinkmwj/ala_program/blob/master/LICENSE"}
  :dependencies [[org.clojure/clojure "1.6.0"] 
                 [ring/ring-core "1.3.2"]
                 [ring/ring-jetty-adapter "1.3.2"]
                 [compojure "1.3.4"]
                 [ring/ring-json "0.3.1"]
                 [cheshire "5.5.0"]
                 [clj-time "0.9.0"]
                 [twitter-api "0.7.8"] 
                 [jarohen/chime "0.1.6"]]
  :plugins [[lein-ring "0.9.4"]]
  :ring {:handler ala-program.core/app
         :destroy ala-program.core/cleanup-app})
