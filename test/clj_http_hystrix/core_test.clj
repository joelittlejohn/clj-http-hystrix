(ns clj-http-hystrix.core-test
  (:require [clj-http-hystrix.core :refer :all]
            [clj-http.client :as http]
            [midje.sweet :refer :all]))

(add-hook)

(fact "hystrix wrapping with fallback"
      (http/get "http://httpstat.us/400" {:throw-exceptions false
                                          :hystrix/fallback-fn (constantly "foo")})
      => "foo")

(fact "hystrix wrapping return successful call"
      (-> (http/get "http://httpstat.us/200" {:throw-exceptions false
                                              :hystrix/fallback-fn (constantly "foo")})
          :status)
      => 200)

(fact "hystrix wrapping with exceptions off"
      (-> (http/get "http://httpstat.us/400" {:throw-exceptions false
                                              :hystrix/command-key :foo}) :status)
      => 400)

(fact "hystrix wrapping with exceptions implicitly on"
      (http/get "http://httpstat.us/400" {:hystrix/command-key :foo})
      => (throws clojure.lang.ExceptionInfo "clj-http: status 400"))

(fact "hystrix wrapping with exceptions explicitly on"
      (http/get "http://httpstat.us/400" {:throw-exceptions true
                                          :hystrix/command-key :foo})
      => (throws clojure.lang.ExceptionInfo "clj-http: status 400"))

(fact "request with no hystrix key present"
      (http/get "http://httpstat.us/500" {})
      => (throws clojure.lang.ExceptionInfo "clj-http: status 500"))
