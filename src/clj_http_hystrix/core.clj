(ns clj-http-hystrix.core
  (:require [clj-http.client :as http]
            [clojure.tools.logging :refer [warn error]]
            [robert.hooke :as hooke]
            [slingshot.slingshot :refer [get-thrown-object]]
            [slingshot.support :refer [wrap stack-trace]])
  (:import [com.netflix.hystrix HystrixCommand
                                HystrixThreadPoolProperties
                                HystrixCommandProperties
                                HystrixCommand$Setter
                                HystrixCommandGroupKey$Factory
                                HystrixCommandKey$Factory]
           [com.netflix.hystrix.exception HystrixBadRequestException]
           [org.slf4j MDC]))

(defn default-fallback [req resp]
  (if (:status resp)
    resp
    {:status 503}))

(defn client-error?
  "Returns true when the response has one of the 4xx family of status
  codes"
  [req resp]
  (http/client-error? resp))

(defn ^:private handle-exception
  [f req]
  (let [^Exception raw-response (try (f) (catch Exception e e))
        resp (if (instance? HystrixBadRequestException raw-response)
               (get-thrown-object (.getCause raw-response))
               raw-response)]
    (when (:status resp)
      ((http/wrap-exceptions (constantly resp)) req))
    resp))

(def ^:private ^:const hystrix-base-configuration
  {:hystrix/command-key             :default
   :hystrix/fallback-fn             default-fallback
   :hystrix/group-key               :default
   :hystrix/threads                 10
   :hystrix/queue-size              5
   :hystrix/timeout-ms              1000
   :hystrix/breaker-request-volume  20
   :hystrix/breaker-error-percent   50
   :hystrix/breaker-sleep-window-ms 5000
   :hystrix/bad-request-pred        client-error?})

(def ^:private hystrix-keys
  (keys hystrix-base-configuration))

(defn ^:private hystrix-defaults [defaults]
  (merge hystrix-base-configuration (select-keys defaults hystrix-keys)))

(defn ^:private group-key [s]
  (HystrixCommandGroupKey$Factory/asKey (name s)))

(defn ^:private command-key [s]
  (HystrixCommandKey$Factory/asKey (name s)))

(defn ^:private configurator
  "Create a configurator that can configure the hystrix according to the
  declarative config (or some sensible defaults)"
  ^HystrixCommand$Setter [config]
  (let [{group :hystrix/group-key
         command :hystrix/command-key
         timeout :hystrix/timeout-ms
         threads :hystrix/threads
         queue-size :hystrix/queue-size
         sleep-window :hystrix/breaker-sleep-window-ms
         error-percent :hystrix/breaker-error-percent
         request-volume :hystrix/breaker-request-volume} config
        command-configurator (doto (HystrixCommandProperties/Setter)
                               (.withExecutionIsolationThreadTimeoutInMilliseconds timeout)
                               (.withCircuitBreakerRequestVolumeThreshold request-volume)
                               (.withCircuitBreakerErrorThresholdPercentage error-percent)
                               (.withCircuitBreakerSleepWindowInMilliseconds sleep-window)
                               (.withMetricsRollingPercentileEnabled false))
        thread-pool-configurator (doto (HystrixThreadPoolProperties/Setter)
                                   (.withCoreSize threads)
                                   (.withMaxQueueSize queue-size)
                                   (.withQueueSizeRejectionThreshold queue-size))]
    (doto (HystrixCommand$Setter/withGroupKey (group-key group))
      (.andCommandKey (command-key command))
      (.andCommandPropertiesDefaults command-configurator)
      (.andThreadPoolPropertiesDefaults thread-pool-configurator))))

(defn ^:private log-error [command-name ^HystrixCommand context]
  (let [message (format "Failed to complete %s %s" command-name (.getExecutionEvents context))]
    (if-let [exception (.getFailedExecutionException context)]
      (warn exception message)
      (warn message))))

(defn status-codes
  "Create a predicate that returns true whenever one of the given
  status codes is present"
  [& status-codes]
  (let [status-codes (set status-codes)]
    (fn [req resp]
      (contains? status-codes (:status resp)))))

(defn hystrix-wrapper
  "Create a function that wraps clj-http client requests with hystrix features
  (but only if a hystrix key is present in the options map).
  Accepts a possibly empty map of default hystrix values that will be used as
  fallback configuration for each hystrix request."
  [custom-defaults]
  (let [defaults (hystrix-defaults custom-defaults)]
    (fn [f req]
      (if (not-empty (select-keys req hystrix-keys))
        (let [req (merge defaults req)
              bad-request-pred (:hystrix/bad-request-pred req)
              fallback (:hystrix/fallback-fn req)
              wrap-bad-request (fn [resp]
                                 (if (bad-request-pred req resp)
                                   (throw
                                     (HystrixBadRequestException.
                                       "Ignored bad request"
                                       (wrap {:object resp
                                              :message "Bad request pred"
                                              :stack-trace (stack-trace)})))
                                   resp))
              wrap-exception-response (fn [resp]
                                        ((http/wrap-exceptions (constantly resp))
                                         (assoc req :throw-exceptions true)))
              configurator (configurator req)
              logging-context (or (MDC/getCopyOfContextMap) {})
              command (proxy [HystrixCommand] [configurator]
                        (getFallback []
                          (MDC/setContextMap logging-context)
                          (log-error (:hystrix/command-key req) this)
                          (let [exception (.getFailedExecutionException ^HystrixCommand this)
                                response (when exception (get-thrown-object exception))]
                            (fallback req response)))
                        (run []
                          (MDC/setContextMap logging-context)
                          (-> req
                              (assoc :throw-exceptions false)
                              f
                              wrap-bad-request
                              wrap-exception-response)))]
          (handle-exception #(.execute command) req))
        (f req)))))

(defn add-hook
  "Activate clj-http-hystrix to wrap all clj-http client requests as
  hystrix commands.
  Provide custom hystrix defaults by providing an optional defaults map:

    ;; use clj-http-hystrix.core default configuration
    (add-hook)

    ;; 6 second timeout fallback, if not specified in request
    (add-hook {:hystrix/timeout-ms 6000})
  "
  ([] (add-hook {}))
  ([defaults]
   (hooke/add-hook #'http/request ::wrap-hystrix (hystrix-wrapper defaults))))

(defn remove-hook
  "Deactivate clj-http-hystrix."
  []
  (hooke/remove-hook #'http/request ::wrap-hystrix))

(defn wrap-hystrix
  "Middleware for adding hystrix to a clj-http client request.

  Alternative to `add-hook`. Do not use both `wrap-hystrix` and `add-hook`.

    ;; add wrap-hystrix to the middleware chain
    (clj-http.client/with-additional-middleware [wrap-hystrix]
      (clj-http.client/get ...))

    ;; or if you want to provide your own defaults
    (clj-http.client/with-additional-middleware [(partial wrap-hystrix {:hystrix/timeout-ms 6000})]
      (clj-http.client/get ...))
  "
  ([client] (wrap-hystrix {} client))
  ([defaults client]
   (let [wrapper (hystrix-wrapper defaults)]
     (fn [req]
       (wrapper client req)))))
