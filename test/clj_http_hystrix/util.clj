(ns clj-http-hystrix.util
  (:import [org.slf4j MDC]
           [org.slf4j.spi MDCAdapter]))

(defn fake-mdc []
  (let [mdc (atom {})
        mdca (reify MDCAdapter
               (clear [_]
                 (reset! mdc {}))
               (get [_ s]
                 (@mdc s))
               (getCopyOfContextMap [_]
                 @mdc)
               (put [_ k v]
                 (swap! mdc assoc k v))
               (remove [_ k]
                 (swap! mdc dissoc k))
               (setContextMap [_ m]
                 (reset! mdc (into {} m))))]
    (doto (.getDeclaredField MDC "mdcAdapter") (.setAccessible true) (.set nil mdca))))
