(ns clj-http-hystrix.core-test
  (:require [clj-http-hystrix.core :refer :all]
            [clj-http-hystrix.util :refer :all]
            [clj-http.client :as http]
            [clojure.java.io :refer [resource]]
            [clojure.tools.logging :refer [warn error]]
            [rest-cljer.core :refer [rest-driven]]
            [midje.sweet :refer :all])
  (:import [java.net SocketTimeoutException]
           [java.util UUID]
           [clojure.lang ExceptionInfo]
           [org.slf4j MDC]))

(def url "http://localhost:8081/")

(add-hook)
(fake-mdc)

(defn make-hystrix-call
  [opts]
  (http/get url (merge {:hystrix/command-key (keyword (str (UUID/randomUUID)))}
                       opts)))

(fact "hystrix wrapping with fallback"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 500}]
       (make-hystrix-call {:throw-exceptions false
                           :hystrix/fallback-fn (constantly "foo")}) => "foo"))

(fact "hystrix wrapping fallback not called for client-error calls - this is due to default client-error?"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 400}]
       (-> (make-hystrix-call {:throw-exceptions false
                               :hystrix/fallback-fn (constantly "foo")})
           :status)
       => 400))

(fact "hystrix wrapping with fallback - preserves the MDC Values"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 500}]
       (MDC/put "pickles" "preserve")
       (make-hystrix-call {:throw-exceptions false
                           :hystrix/fallback-fn (fn [& z] (into {} (MDC/getCopyOfContextMap)))})
       => (contains {"pickles" "preserve"})))

(fact "hystrix wrapping return successful call"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200}]
       (-> (make-hystrix-call {:throw-exceptions false
                               :hystrix/fallback-fn (constantly "foo")})
           :status)
       => 200))

(fact "hystrix wrapping with exceptions off"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 500}]
       (-> (make-hystrix-call {:throw-exceptions false})
           :status)
       => 500))

(fact "hystrix wrapping with exceptions implicitly on"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 500}]
       (make-hystrix-call {})
       => (throws clojure.lang.ExceptionInfo "clj-http: status 500")))

(fact "hystrix wrapping with exceptions explicitly on"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 500}]
       (make-hystrix-call {:throw-exceptions true})
       => (throws clojure.lang.ExceptionInfo "clj-http: status 500")))

(fact "request with no hystrix key present"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 500}]
       (http/get url {})
       => (throws clojure.lang.ExceptionInfo "clj-http: status 500")))

(fact "hystrix wrapping with sockettimeout returns 503 status"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200
         :after 500}]
       (let [response (make-hystrix-call {:socket-timeout 100
                                          :throw-exceptions false})]
         (:status response) => 503)))

(fact "hystrix wrapping with timeout returns 503 status"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 200
         :after 1000}]
       (let [response (make-hystrix-call {:hystrix/timeout-ms 100
                                          :throw-exceptions false})]
         (:status response) => 503)))

(fact "errors will not cause circuit to break if bad-request-pred is true, with :throw-exceptions false"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 400
         :times 30}
        {:method :GET
         :url "/"}
        {:status 200}]
       (let [command-key (keyword (str (UUID/randomUUID)))]
         (dotimes [_ 30]
           (http/get url {:throw-exceptions false
                          :hystrix/command-key command-key
                          :hystrix/bad-request-pred client-error?}) => (contains {:status 400}))
         (Thread/sleep 1000) ;sleep to wait for Hystrix health snapshot
         (http/get url {:throw-exceptions false
                        :hystrix/command-key command-key}) => (contains {:status 200}))))

(fact "errors will not cause circuit to break if bad-request-pred is true, with :throw-exceptions true"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 400
         :times 30}
        {:method :GET
         :url "/"}
        {:status 200}]
       (let [command-key (keyword (str (UUID/randomUUID)))]
         (dotimes [_ 30]
           (http/get url {:throw-exceptions true
                          :hystrix/command-key command-key
                          :hystrix/bad-request-pred client-error?}) => (throws ExceptionInfo))
         (Thread/sleep 1000) ;sleep to wait for Hystrix health snapshot
         (http/get url {:throw-exceptions false
                        :hystrix/command-key command-key}) => (contains {:status 200}))))

(fact "errors will cause circuit to break if bad-request-pred is false"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 400
         :times 30}]
       (let [command-key (keyword (str (UUID/randomUUID)))]
         (dotimes [_ 30]
           (http/get url {:throw-exceptions false
                          :hystrix/command-key command-key
                          :hystrix/bad-request-pred (constantly false)}) => (contains {:status 400}))
         (Thread/sleep 1000) ;sleep to wait for Hystrix health snapshot
         (http/get url {:throw-exceptions false
                        :hystrix/command-key command-key}) => (contains {:status 503}))))
