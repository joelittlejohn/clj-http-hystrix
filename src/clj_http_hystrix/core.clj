(ns clj-http-hystrix.core
  (:require [clj-http.client :as http]
            [clojure.set :as set]
            [clojure.tools.logging :refer [warn error]]
            [robert.hooke :as hooke])
  (:import [com.netflix.hystrix HystrixCommand HystrixThreadPoolProperties HystrixCommandProperties HystrixCommand$Setter HystrixCommandGroupKey$Factory HystrixCommandKey$Factory]
           [com.netflix.hystrix.exception HystrixBadRequestException]
           [org.slf4j MDC]
           [clojure.lang ExceptionInfo]))

(def ^:private ^:const hystrix-keys
  #{:hystrix/fallback-fn
    :hystrix/group-key
    :hystrix/command-key
    :hystrix/threads
    :hystrix/queue-size
    :hystrix/timeout-ms
    :hystrix/bad-request-pred})

(defn default-fallback [req this]
  (if-let [exception (.getFailedExecutionException this)]
    exception
    (with-meta
        {:status 503}
        {:error this})))

(defn handle-exception
  [f req]
  (let [response (try (f) (catch Exception e e))]
    (if (instance? Throwable response)
      (let [response (if (instance? HystrixBadRequestException response)
                       (.getCause response)
                       response)]
        (when (:throw-exceptions req true)
          (throw response))
        (if (instance? ExceptionInfo response)
          (:object (.getData response))
          (with-meta {:status 503
                      :body {:error (.getMessage response)}}
            {:error response})))
      response)))

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
      (error exception message)
      (error message))))

(defn status-codes
  [& status]
  (fn [v]
    (if (instance? ExceptionInfo v)
      (reduce #(or %1 (= %2 (:status (:object (.getData v))))) false status))))

(defn status-4xx?
  [v]
  (if (instance? ExceptionInfo v)
    (< 399 (:status (:object (.getData v))) 500)))

(defn wrap-hystrix
  "Wrap a clj-http client request with hystrix features (but only if a
  command-key is present in the options map)."
  [f req]
  (if (not-empty (select-keys req hystrix-keys))
    (let [bad-request-pred (req :hystrix/bad-request-pred)
          configurator (configurator req)
          logging-context (or (MDC/getCopyOfContextMap) {})
          command (proxy [HystrixCommand] [configurator]
                    (getFallback []
                      (log-error (:hystrix/command-key req) this)
                      (if (:hystrix/fallback-fn req)
                        ((:hystrix/fallback-fn req) req this)
                        (default-fallback req this)))
                    (run []
                      (MDC/setContextMap logging-context)
                      (try
                        (f (assoc req :throw-exceptions true))
                        (catch Exception e
                          (if (and bad-request-pred (bad-request-pred e))
                            (throw (HystrixBadRequestException. "Ignoring exception" e))
                            (throw e))))))]
      (handle-exception #(.execute command) req))
    (f req)))

(defn add-hook []
  "Activate clj-http-hystrix to wrap all clj-http client requests as
  hystrix commands."
  (when (not (some-> (meta http/request) :robert.hooke/hooks deref (contains? #'wrap-hystrix)))
    (hooke/add-hook #'http/request #'wrap-hystrix)))
