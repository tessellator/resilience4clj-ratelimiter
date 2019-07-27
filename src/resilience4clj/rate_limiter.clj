(ns resilience4clj.rate-limiter
  "Functions to create and execute rate limiters."
  (:refer-clojure :exclude [name])
  (:require [clojure.spec.alpha :as s])
  (:import [io.github.resilience4j.ratelimiter
            RateLimiter
            RateLimiterConfig
            RateLimiterRegistry]
           [java.time Duration]))

;; -----------------------------------------------------------------------------
;; configuration

(s/def ::timeout-duration nat-int?)
(s/def ::limit-refresh-period nat-int?)
(s/def ::limit-for-period nat-int?)

(s/def ::config
  (s/keys :opt-un [::timeout-duration
                   ::limit-refresh-period
                   ::limit-for-period]))

(s/def ::name
  (s/or :string (s/and string? not-empty)
        :keyword keyword?))

(defn- build-config [config]
  (let [{:keys [timeout-duration
                limit-refresh-period
                limit-for-period]} config]
   (cond-> (RateLimiterConfig/custom)

     timeout-duration
     (.timeoutDuration (Duration/ofMillis timeout-duration))

     limit-refresh-period
     (.limitRefreshPeriod (Duration/ofNanos limit-refresh-period))

     limit-for-period
     (.limitForPeriod limit-for-period)

     :always
     (.build))))

;; -----------------------------------------------------------------------------
;; registry

(def registry
  "The global rate limiter and config registry."
  (RateLimiterRegistry/ofDefaults))

(defn- build-configs-map [configs-map]
  (into {} (map (fn [[k v]] [(clojure.core/name k) (build-config v)]) configs-map)))

(defn configure-registry!
  "Overwrites the global registry with one that contains the configs-map.

  configs-map is a map whose keys are names and vals are configs. When a rate
  limiter is created, you may specify one of the names in this map to use as the
  config for that rate limiter.

  :default is a special name. It will be used as the config for rate limiters
  that do not specify a config to use."
  [configs-map]
  (alter-var-root (var registry)
                  (fn [_]
                    (RateLimiterRegistry/of (build-configs-map configs-map)))))

(defn rate-limiter!
  "Creates or fetches a rate limiter with the specified name and config and
  stores it in the global registry.

  The config value can be either a config map or the name of a config map stored
  in the global registry.

  If the rate limiter already exists in the global registry, the config value is
  ignored."
  ([name]
   {:pre [(s/valid? ::name name)]}
   (.rateLimiter registry (clojure.core/name name)))
  ([name config]
   {:pre [(s/valid? ::name name)
          (s/valid? (s/or :name ::name :config ::config) config)]}
   (if (s/valid? ::name config)
     (.rateLimiter registry (clojure.core/name name) (clojure.core/name config))
     (.rateLimiter registry (clojure.core/name name) (build-config config)))))

(defn rate-limiter
  "Creates a rate limiter with the specified name and config."
  [name config]
  (RateLimiter/of (clojure.core/name name) (build-config config)))

;; -----------------------------------------------------------------------------
;; execution

(defn execute
  "Apply args to f within a context protected by the rate limiter.

  The function will wait up to the configured timeout duration if the rate limit
  has been exceeded. If the function is not allowed to execute before the
  timeout duration expires, this function will throw an exception."
  [^RateLimiter rate-limiter f & args]
  (.executeCallable rate-limiter #(apply f args)))

(defmacro with-rate-limiter
  "Executes body within a context protected by the rate limiter.

  `rate-limiter` is either a rate limiter or the name of one in the global
  registry. If you provide a name and a rate limiter of that name does not
  already exist in the global registry, one will be created with the `:default`
  config.

  The code in `body` will wait up to the configured timeout duration if the rate
  limit has been exceeded. If the function is not allowed to execute before the
  timeout duration expires, an exception will be thrown."
  [rate-limiter & body]
  `(let [rl# (if (s/valid? ::name ~rate-limiter)
               (rate-limiter! (clojure.core/name ~rate-limiter))
               ~rate-limiter)]
     (execute rl# (fn [] ~@body))))

;; -----------------------------------------------------------------------------
;; management

(defn change-timeout-duration!
  "Changes the timeout duration milliseconds for the rate limiter.

  The new timeout duration will not affect calls currently waiting for
  permission to execute."
  [^RateLimiter rate-limiter timeout-duration]
  (.changeTimeoutDuration rate-limiter (Duration/ofMillis timeout-duration)))

(defn change-limit-for-period!
  "Changes the number of allowable calls during a refresh period.

  The new limit will take effect in the next refresh period. The current period
  is not affected."
  [^RateLimiter rate-limiter limit-for-period]
  (.changeLimitForPeriod rate-limiter limit-for-period))

;; -----------------------------------------------------------------------------
;; properties

(defn name
  "Gets the name of the rate limiter."
  [^RateLimiter rate-limiter]
  (.getName rate-limiter))
