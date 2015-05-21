(ns clj-http-hystrix.core-test
  (:require [clj-http-hystrix.core :refer :all]
            [clj-http.client :as http]
            [rest-cljer.core :refer [rest-driven]]
            [midje.sweet :refer :all]))

(def url "http://localhost:8081/")

(add-hook)

(fact "hystrix wrapping with fallback"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 400}]
       (http/get url {:throw-exceptions false
                      :hystrix/fallback-fn (constantly "foo")})
       => "foo"))

(fact "hystrix wrapping return successful call"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200}]
       (-> (http/get url {:throw-exceptions false
                          :hystrix/fallback-fn (constantly "foo")})
           :status)
       => 200))

(fact "hystrix wrapping with exceptions off"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 400}]
       (-> (http/get url {:throw-exceptions false
                          :hystrix/command-key :foo}) :status)
       => 400))

(fact "hystrix wrapping with exceptions implicitly on"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 400}]
       (http/get url {:hystrix/command-key :foo})
       => (throws clojure.lang.ExceptionInfo "clj-http: status 400")))

(fact "hystrix wrapping with exceptions explicitly on"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 400}]
       (http/get url {:throw-exceptions true
                      :hystrix/command-key :foo})
       => (throws clojure.lang.ExceptionInfo "clj-http: status 400")))

(fact "request with no hystrix key present"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 500}]
       (http/get url {})
       => (throws clojure.lang.ExceptionInfo "clj-http: status 500")))
