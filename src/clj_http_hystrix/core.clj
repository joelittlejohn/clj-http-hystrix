(ns clj-http-hystrix.core
  (:require [clj-http.client :as http]
            [clojure.set :as set]
            [clojure.tools.logging :refer [warn]]
            [com.netflix.hystrix.core :as hystrix]
            [robert.hooke :as hooke]))

(def ^:private ^:const key-mapping
  {:hystrix/cache-key-fn    :cache-key-fn
   :hystrix/fallback-fn     :fallback-fn
   :hystrix/group-key       :group-key
   :hystrix/command-key     :command-key
   :hystrix/thread-pool-key :thread-pool-key
   :hystrix/init-fn         :init-fn})

(defn- hystrix-options
  [meta-map]
  (-> meta-map (select-keys (keys key-mapping)) (set/rename-keys key-mapping)))

(defn default-fallback [req]
  (let [e (.getFailedExecutionException hystrix/*command*)]
    (warn e "hystrix clj-http request failed")
    (if (-> (ex-data e) :object :status)
      (-> (ex-data e) :object)
      {:status 500})))

(defn wrap-hystrix
  "Wrap a clj-http client request with hystrix features (but only if a
  command-key is present in the options map)."
  [f req]
  (if-let [hystrix-opts (not-empty (hystrix-options req))]
    (let [command (hystrix/command (merge {:command-key :default-command
                                           :group-key :default-group
                                           :fallback-fn default-fallback
                                           :run-fn f}
                                          hystrix-opts))]
      ((http/wrap-exceptions #(hystrix/execute command (assoc % :throw-exceptions true))) req))
    (f req)))

(defn add-hook []
  "Activate clj-http-hystrix to wrap all clj-http client requests as
  hystrix commands."
  (when (not (some-> (meta http/request) :robert.hooke/hooks deref (contains? #'wrap-hystrix)))
    (hooke/add-hook #'http/request #'wrap-hystrix)))
