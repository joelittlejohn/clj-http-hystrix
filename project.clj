(defproject clj-http-hystrix "0.1.7-SNAPSHOT"

  :description "A Clojure library to wrap clj-http requests as hystrix commands"
  :url "https://github.com/joelittlejohn/clj-http-hystrix"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[clj-http "3.8.0"]
                 [com.netflix.hystrix/hystrix-clj "1.5.12"]
                 [org.clojure/tools.logging "0.4.0"]
                 [robert/hooke "1.3.0"]
                 [slingshot "0.12.2"]]

  :env {:restdriver-port "8081"}

  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]]

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [midje "1.9.1"]
                                  [rest-cljer "0.2.2" :exclusions [clj-http]]]
                   :plugins [[lein-cloverage "1.0.10"]
                             [lein-environ "1.1.0"]
                             [lein-midje "3.2.1"]]}})
