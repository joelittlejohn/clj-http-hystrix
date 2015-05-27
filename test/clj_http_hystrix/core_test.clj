(ns clj-http-hystrix.core-test
  (:require [clj-http-hystrix.core :refer :all]
            [clj-http.client :as http]
            [rest-cljer.core :refer [rest-driven]]
            [midje.sweet :refer :all])
  (:import [java.net SocketTimeoutException]
           [java.util UUID]
           [clojure.lang ExceptionInfo]))

(def url "http://localhost:8081/")

(add-hook)

(defn instance-of [class]
  (fn [object]
    (instance? class object)))

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

(fact "hystrix wrapping with sockettimeout returns 503 status - original error is put in meta response"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200
         :after 500}]
       (let [response (http/get url {:socket-timeout 100
                                     :throw-exceptions false
                                     :hystrix/command-key :default})]
         (:status response) => 503
         (:error (meta response)) => (instance-of SocketTimeoutException))))

(fact "hystrix wrapping with timeout returns 503 status - original error is put in meta response"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200
         :after 1000}]
       (let [response (http/get url {:hystrix/timeout-ms 100
                                     :throw-exceptions false
                                     :hystrix/command-key :socket})]
         (:status response) => 503)))


(fact "hystrix wrapping do not cause the switch to go off if predicate is true - testing lower bound of status-4xx?"
      :long
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200}
        {:method :GET
         :url "/"}
        {:status 400
         :times 30}]
       (let [command-key (keyword (str (UUID/randomUUID)))]
         (http/get url {:throw-exceptions false
                        :hystrix/command-key command-key
                        :hystrix/bad-request-pred status-4xx?})
         (Thread/sleep 10000)
         (doseq [x (range 29)]
           (http/get url {:throw-exceptions false
                          :hystrix/command-key command-key
                          :hystrix/bad-request-pred status-4xx?}))
         (-> (http/get url {:throw-exceptions false
                            :hystrix/command-key command-key
                            :hystrix/bad-request-pred status-4xx?})
             :status) => 400)))

(fact "hystrix wrapping do not cause the switch to go off if predicate is true, but still throw exception - testing upper bound of status-4xx?"
      :long
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200}
        {:method :GET
         :url "/"}
        {:status 499
         :times 30}]
       (let [command-key (keyword (str (UUID/randomUUID)))]
         (try (http/get url {:throw-exceptions true
                             :hystrix/command-key command-key
                             :hystrix/bad-request-pred status-4xx?})
              (catch Exception e))
         (Thread/sleep 10000)
         (doseq [x (range 29)]
           (try (http/get url {:throw-exceptions true
                               :hystrix/command-key command-key
                               :hystrix/bad-request-pred status-4xx?})
                (catch Exception e)))
         (http/get url {:throw-exceptions true
                        :hystrix/command-key command-key
                        :hystrix/bad-request-pred status-4xx?})
         => (throws ExceptionInfo))))

(fact "hystrix wrapping do not cause the switch to go off if predicate is true - test for specific status code"
      :long
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200}
        {:method :GET
         :url "/"}
        {:status 404
         :times 30}]
       (let [command-key (keyword (str (UUID/randomUUID)))]
         (http/get url {:throw-exceptions false
                        :hystrix/command-key command-key
                        :hystrix/bad-request-pred (status-codes 404)})
         (Thread/sleep 10000)
         (doseq [x (range 29)]
           (http/get url {:throw-exceptions false
                          :hystrix/command-key command-key
                          :hystrix/bad-request-pred (status-codes 404)}))
         (-> (http/get url {:throw-exceptions false
                            :hystrix/command-key command-key
                            :hystrix/bad-request-pred (status-codes 404)})
             :status) => 404)))
