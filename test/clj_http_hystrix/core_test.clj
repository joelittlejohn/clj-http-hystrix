(ns clj-http-hystrix.core-test
  (:require [clj-http-hystrix.core :refer :all]
            [clj-http-hystrix.util :refer :all]
            [clj-http.client :as http]
            [clojure.java.io :refer [resource]]
            [clojure.tools.logging :refer [warn error]]
            [robert.hooke :as hooke]
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

(def ^:dynamic *bindable* :unbound)

(fact "hystrix wrapping with fallback - preserves dynamic bindings"
      (let [fallback (fn [& z] *bindable*)]
        (rest-driven
          [{:method :GET
            :url "/"}
           {:status 500}]
          (binding [*bindable* :bound]
            (make-hystrix-call {:throw-exceptions false
                                :hystrix/fallback-fn fallback}))
          => :bound)))

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
                          :hystrix/command-key command-key}) => (contains {:status 400}))
         (Thread/sleep 600) ;sleep to wait for Hystrix health snapshot
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
                          :hystrix/command-key command-key}) => (throws ExceptionInfo))
         (Thread/sleep 600) ;sleep to wait for Hystrix health snapshot
         (http/get url {:throw-exceptions false
                        :hystrix/command-key command-key}) => (contains {:status 200}))))

(fact "status-codes predicate matches only given status codes"
      (let [predicate (status-codes 100 200 300)]
        (predicate {} {:status 100}) => true
        (predicate {} {:status 200}) => true
        (predicate {} {:status 300}) => true
        (predicate {} {:status 101}) => false
        (predicate {} {:status 202}) => false
        (predicate {} {:status 299}) => false))

(defn get-hooks []
  (some-> http/request meta :robert.hooke/hooks deref))

(fact "add-hook can be safely called more than once"
      (count (get-hooks)) => 1
      (contains? (get-hooks) :clj-http-hystrix.core/wrap-hystrix) => true
      ;call add-hook a few more times and ensure only one hook is present
      (add-hook), (add-hook)
      (count (get-hooks)) => 1
      (contains? (get-hooks) :clj-http-hystrix.core/wrap-hystrix) => true)

(fact "remove-hook removes clj-http-hystrix hook"
      (count (get-hooks)) => 1
      (contains? (get-hooks) :clj-http-hystrix.core/wrap-hystrix) => true
      (remove-hook)
      (get-hooks) => nil
      ;can be called more than once
      (remove-hook), (remove-hook)
      (get-hooks) => nil
      ;restore hook for additional testing
      (add-hook))

(fact "add-hook with user-defaults will override base configuration, but not call configuration"
      (rest-driven
       [{:method :GET
         :url "/"}
        {:status 500
         :times 3}]
       (make-hystrix-call {})
       => (throws clojure.lang.ExceptionInfo "clj-http: status 500")
        ;set custom default for fallback-fn
       (remove-hook)
       (add-hook {:hystrix/fallback-fn (constantly "bar")})
       (make-hystrix-call {}) => "bar"
       (make-hystrix-call {:hystrix/fallback-fn (constantly "baz")}) => "baz")
      (remove-hook)
      (add-hook))

(fact "wrap-hystrix enables clj-http-hystrix to be incorporated as a middleware"
      (remove-hook)
      ;verify hystrix is enabled by exceeding the default timeout (1000 ms)
      (http/with-additional-middleware [wrap-hystrix]
        (rest-driven
         [{:method :GET
           :url "/"}
          {:status 200
           :after 1500}]
         (make-hystrix-call {})
         => (throws clojure.lang.ExceptionInfo "clj-http: status 503")))

      ;verify custom defaults are supported
      (http/with-additional-middleware
        [(partial wrap-hystrix {:hystrix/fallback-fn (constantly {:status 404})})]
        (rest-driven
         [{:method :GET
           :url "/"}
          {:status 500}]
         (make-hystrix-call {})
         => (throws clojure.lang.ExceptionInfo "clj-http: status 404"))))
