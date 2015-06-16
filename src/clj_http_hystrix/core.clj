(ns clj-http-hystrix.core
  (:require [clj-http.client :as http]
            [clojure.set :as set]
            [clojure.tools.logging :refer [warn error]]
            [robert.hooke :as hooke]
            [slingshot.slingshot :refer [get-thrown-object]]
            [slingshot.support :refer [wrap stack-trace]])
  (:import [com.netflix.hystrix HystrixCommand HystrixThreadPoolProperties HystrixCommandProperties HystrixCommand$Setter HystrixCommandGroupKey$Factory HystrixCommandKey$Factory]
           [com.netflix.hystrix.exception HystrixBadRequestException]
           [org.slf4j MDC]))

(def ^:private ^:const hystrix-keys
  #{:hystrix/fallback-fn
    :hystrix/group-key
    :hystrix/command-key
    :hystrix/threads
    :hystrix/queue-size
    :hystrix/timeout-ms
    :hystrix/bad-request-pred})

(defn default-fallback [req resp]
  (if (:status resp)
    resp
    {:status 503}))

(defn ^:private handle-exception
  [f req]
  (let [raw-response (try (f) (catch Exception e e))
        resp (if (instance? HystrixBadRequestException raw-response)
               (get-thrown-object (.getCause raw-response))
               raw-response)]
    (when (:status resp)
      ((http/wrap-exceptions (constantly resp)) req))
    resp))

(defn ^:private group-key [s]
  (HystrixCommandGroupKey$Factory/asKey (name s)))

(defn ^:private command-key [s]
  (HystrixCommandKey$Factory/asKey (name s)))

(defn ^:private configurator
  "Create a configurator that can configure the hystrix according to the
  declarative config (or some sensible defaults)"
  [config]
  (let [timeout (:hystrix/timeout-ms config 600)
        group   (:hystrix/group-key config :default)
        threads (:hystrix/threads config 10)
        command-configurator (doto (HystrixCommandProperties/Setter)
                               (.withExecutionIsolationThreadTimeoutInMilliseconds timeout)
                               (.withMetricsRollingPercentileEnabled false))
        thread-pool-configurator (doto (HystrixThreadPoolProperties/Setter)
                                   (.withCoreSize threads)
                                   (.withMaxQueueSize (:hystrix/queue-size config 5))
                                   (.withQueueSizeRejectionThreshold (:hystrix/queue-size config 5)))]
    (-> (HystrixCommand$Setter/withGroupKey (group-key group))
        (.andCommandKey (command-key (:hystrix/command-key config :default)))
        (.andCommandPropertiesDefaults command-configurator)
        (.andThreadPoolPropertiesDefaults thread-pool-configurator))))

(defn ^:private log-error [command-name context]
  (let [message (format "Failed to complete %s %s" command-name (.getExecutionEvents context))]
    (if-let [exception (.getFailedExecutionException context)]
      (warn exception message)
      (warn message))))

(defn status-codes
  "Create a predicate that returns true whenever one of the given 
  status codes is present"
  [& status]
  (fn [req resp]
    (reduce #(or %1 (= %2 (:status resp))) false status)))

(defn client-error?
  "Returns true when the response has one of the 4xx family of status 
  codes"
  [req resp]
  (http/client-error? resp))

(defn wrap-hystrix
  "Wrap a clj-http client request with hystrix features (but only if a
  command-key is present in the options map)."
  [f req]
  (if (not-empty (select-keys req hystrix-keys))
    (let [bad-request-pred (:hystrix/bad-request-pred req client-error?)
          fallback (:hystrix/fallback-fn req default-fallback)
          wrap-bad-request (fn [resp] (if (bad-request-pred req resp)
                                       (throw (HystrixBadRequestException. "Ignored bad request"
                                                                           (wrap {:object resp
                                                                                  :message "Bad request pred"
                                                                                  :stack-trace (stack-trace)})))
                                           resp))
          wrap-exception-reponse (fn [resp] ((http/wrap-exceptions (constantly resp)) (assoc req :throw-exceptions true)))
          configurator (configurator req)
          logging-context (or (MDC/getCopyOfContextMap) {})
          command (proxy [HystrixCommand] [configurator]
                    (getFallback []
                      (MDC/setContextMap logging-context)
                      (log-error (:hystrix/command-key req) this)
                      (let [exception (.getFailedExecutionException this)
                            response (when exception (get-thrown-object exception))]
                        (fallback req response)))
                    (run []
                      (MDC/setContextMap logging-context)
                      (-> req
                          (assoc :throw-exceptions false)
                          f
                          wrap-bad-request
                          wrap-exception-reponse)))]
      (handle-exception #(.execute command) req))
    (f req)))

(defn add-hook []
  "Activate clj-http-hystrix to wrap all clj-http client requests as
  hystrix commands."
  (when (not (some-> (meta http/request) :robert.hooke/hooks deref (contains? #'wrap-hystrix)))
    (hooke/add-hook #'http/request #'wrap-hystrix)))
