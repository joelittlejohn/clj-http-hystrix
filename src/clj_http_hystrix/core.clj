(ns clj-http-hystrix.core
  (:require [clj-http.client :as http]
            [clojure.set :as set]
            [clojure.tools.logging :refer [warn error]]
            [com.netflix.hystrix.core :as hystrix]
            [robert.hooke :as hooke])
  (:import [com.netflix.hystrix HystrixCommand HystrixThreadPoolProperties HystrixCommandProperties HystrixCommand$Setter HystrixCommandGroupKey$Factory HystrixCommandKey$Factory]
           [org.slf4j MDC]))

(def ^:private ^:const hystrix-keys
  #{:hystrix/fallback-fn
    :hystrix/group-key
    :hystrix/command-key
    :hystrix/threads
    :hystrix/queue-size
    :hystrix/timeout-ms})

(defn default-fallback [req]
  (let [e (.getFailedExecutionException hystrix/*command*)]
    (warn e "hystrix clj-http request failed")
    (if (-> (ex-data e) :object :status)
      (-> (ex-data e) :object)
      {:status 500})))

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

(defn wrap-hystrix
  "Wrap a clj-http client request with hystrix features (but only if a
  command-key is present in the options map)."
  [f req]
  (if (not-empty (select-keys req hystrix-keys))
    (let [configurator (configurator req)
          logging-context (or (MDC/getCopyOfContextMap) {})
          command (proxy [HystrixCommand] [configurator]
                    (getFallback []
                      (log-error (:hystrix/command-key req) this)
                      (if (:hystrix/fallback-fn req)
                        ((:hystrix/fallback-fn req) req)
                        (default-fallback req)))
                    (run []
                      (MDC/setContextMap logging-context)
                      (f req)))]
      (.execute command))
    (f req)))

(defn add-hook []
  "Activate clj-http-hystrix to wrap all clj-http client requests as
  hystrix commands."
  (when (not (some-> (meta http/request) :robert.hooke/hooks deref (contains? #'wrap-hystrix)))
    (hooke/add-hook #'http/request #'wrap-hystrix)))
