(ns resilience4clj.rate-limiter
  "Functions to create and execute rate limiters."
  (:refer-clojure :exclude [find name reset!])
  (:require [clojure.core.async :as async]
            [clojure.string :as str])
  (:import [io.github.resilience4j.core
            EventConsumer
            Registry$EventPublisher]
           [io.github.resilience4j.core.registry
            EntryAddedEvent
            EntryRemovedEvent
            EntryReplacedEvent]
           [io.github.resilience4j.ratelimiter
            RateLimiter
            RateLimiter$EventPublisher
            RateLimiterConfig
            RateLimiterRegistry]
           [io.github.resilience4j.ratelimiter.event
            AbstractRateLimiterEvent]
           [java.time Duration]
           [java.util Map Optional]))

(set! *warn-on-reflection* true)

(defn- optional-value [^Optional optional]
  (when (.isPresent optional)
    (.get optional)))

(defn- name? [val]
  (or (string? val)
      (keyword? val)))

(defn- keywordize-enum-value [^Object enum-value]
  (-> (.toString enum-value)
      (str/lower-case)
      (str/replace #"_" "-")
      (keyword)))

;; -----------------------------------------------------------------------------
;; configuration

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

(def default-registry
  "The global ratelimiter and config registry."
  (RateLimiterRegistry/ofDefaults))

(defn- build-configs-map [configs-map]
  (into {} (map (fn [[k v]] [(clojure.core/name k) (build-config v)]) configs-map)))

(defn registry
  "Creates a registry with default values or a map of name/config-map pairs."
  ([]
   (RateLimiterRegistry/ofDefaults))
  ([configs-map]
   (let [^Map configs (build-configs-map configs-map)]
     (RateLimiterRegistry/of configs))))

(defn all-rate-limiters
  "Gets all the rate limiters in `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([]
   (all-rate-limiters default-registry))
  ([^RateLimiterRegistry registry]
   (set (.getAllRateLimiters registry))))

(defn add-configuration!
  "Adds `config` to the `registry` under the `name`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name config]
   (add-configuration! default-registry name config))
  ([^RateLimiterRegistry registry name config]
   (.addConfiguration registry (clojure.core/name name) (build-config config))))

(defn find
  "Finds the rate limiter identified by `name` in `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (find default-registry name))
  ([^RateLimiterRegistry registry name]
   (optional-value (.find registry (clojure.core/name name)))))

(defn remove!
  "Removes the rate limiter identified by `name` from `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (remove! default-registry name))
  ([^RateLimiterRegistry registry name]
   (optional-value (.remove registry (clojure.core/name name)))))

(defn replace!
  "Replaces the rate limiter identified by `name` in `registry` with the specified `rate-limiter`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name ^RateLimiter rate-limiter]
   (replace! default-registry name rate-limiter))
  ([^RateLimiterRegistry registry name ^RateLimiter rate-limiter]
   (optional-value (.replace registry (clojure.core/name name) rate-limiter))))

;; -----------------------------------------------------------------------------
;; registry events

(defn- entry-added-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryAddedEvent e event]
        (async/offer! out-chan
                      {:event-type (keywordize-enum-value (.getEventType e))
                       :added-entry ^RateLimiter (.getAddedEntry e)})))))

(defn- entry-removed-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryRemovedEvent e event]
        (async/offer! out-chan
                      {:event-type (keywordize-enum-value (.getEventType e))
                       :removed-entry ^RateLimiter (.getRemovedEntry e)})))))

(defn- entry-replaced-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryReplacedEvent e event]
        (async/offer! out-chan
                      {:event-type (keywordize-enum-value (.getEventType e))
                       :old-entry ^RateLimiter (.getOldEntry e)
                       :new-entry ^RateLimiter (.getNewEntry e)})))))

(def registry-event-types
  "The event types that can be raised by a registry."
  #{:added
    :removed
    :replaced})

(defn emit-registry-events!
  "Offers registry events to `out-chan`.

  The event types are identified by [[registry-event-types]].

  This function also accepts `:only` and `:exclude` keyword params that are
  sequences of the event types that should be included or excluded,
  respectively.

  Uses [[default-registry]] if `registry` is not provided."
  ([out-chan]
   (emit-registry-events! default-registry out-chan))
  ([^RateLimiterRegistry registry out-chan & {:keys [only exclude]
                                              :or {exclude []}}]
   (let [events-to-publish (if only (set only)
                               (apply disj registry-event-types exclude))
         ^Registry$EventPublisher pub (.getEventPublisher registry)]
     (when (contains? events-to-publish :added)
       (.onEntryAdded pub (entry-added-consumer out-chan)))
     (when (contains? events-to-publish :removed)
       (.onEntryRemoved pub (entry-removed-consumer out-chan)))
     (when (contains? events-to-publish :replaced)
       (.onEntryReplaced pub (entry-replaced-consumer out-chan))))
   out-chan))

;; -----------------------------------------------------------------------------
;; creation and lookup

(defn rate-limiter!
  "Creates or fetches a rate limiter with the specified name and config and 
   stores it in `registry`.

   The config value can be either a config map or the name of a config map stored
   in the registry. If the rate limiter already exists in the registry, the 
   config value is ignored. 

   Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (rate-limiter! default-registry name))
  ([^RateLimiterRegistry registry name]
   (.rateLimiter registry (clojure.core/name name)))
  ([^RateLimiterRegistry registry name config]
   (if (name? config)
     (.rateLimiter registry (clojure.core/name name) (clojure.core/name config))
     (let [^RateLimiterConfig cfg (build-config config)]
       (.rateLimiter registry (clojure.core/name name) cfg)))))

(defn rate-limiter
  "Creates a rate limiter with the `name` and `config`."
  ([name]
   (rate-limiter name {}))
  ([name config]
   (let [^RateLimiterConfig cfg (build-config config)]
     (RateLimiter/of (clojure.core/name name) cfg))))

;; -----------------------------------------------------------------------------
;; Execution

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
  `(let [rl# (if (instance? RateLimiter ~rate-limiter)
               ~rate-limiter
               (rate-limiter! (clojure.core/name ~rate-limiter)))]
     (execute rl# (fn [] ~@body))))

;; -----------------------------------------------------------------------------
;; properties

(defn name
  "Gets the name of the rate limiter."
  [^RateLimiter rate-limiter]
  (.getName rate-limiter))

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

(defn acquire-permission!
  "Attempts to acquire the specified number of permits from `rate-limiter`.
   
   Blocks until all permits are received or the rate limiter timeout duration is
   exceeded. Returns a value indicating success.
   
   The default permit value is 1."
  ([^RateLimiter rate-limiter]
   (.acquirePermission rate-limiter))
  ([^RateLimiter rate-limiter permits]
   (.acquirePermission rate-limiter permits)))

(defn reserve-permission!
  "Reserves the specified number of permits.
   
   Returns the number of nanoseconds you must wait to use the permission. If the
   number is negative, the request to reserve permission failed. This failure
   mode may occur if the number of nanoseconds to wait exceeds the rate limiter
   timeout duration.
   
   The default permit value is 1."
  ([^RateLimiter rate-limiter]
   (.reservePermission rate-limiter))
  ([^RateLimiter rate-limiter permits]
   (.reservePermission rate-limiter permits)))

(defn drain-permissions!
  "Drains all the permits left in the current period."
  [^RateLimiter rate-limiter]
  (.drainPermissions rate-limiter))

;; -----------------------------------------------------------------------------
;; rate limiter events

(def event-types
  #{:success
    :failure})

(defn- base-event [^AbstractRateLimiterEvent event]
  {:event-type (keywordize-enum-value (.getEventType event))
   :rate-limiter-name (.getRateLimiterName event)
   :creation-time (.getCreationTime event)
   :number-of-permits (.getNumberOfPermits event)})

(defn- base-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^AbstractRateLimiterEvent e event]
        (async/offer! out-chan
                      (base-event e))))))

(defn emit-events!
  "Offers events on `rate-limiter` to `out-chan`.

  The event types are identified by [[event-types]].

  This function also accepts `:only` and `:exclude` keyword params that are
  sequences of the event types that should be included or excluded,
  respectively."
  [^RateLimiter rate-limiter out-chan & {:keys [only exclude]
                                         :or {exclude []}}]
  (let [events-to-publish (if only
                            (set only)
                            (apply disj event-types exclude))
        ^RateLimiter$EventPublisher pub (.getEventPublisher rate-limiter)]
    (when (contains? events-to-publish :success)
      (.onSuccess pub (base-consumer out-chan)))
    (when (contains? events-to-publish :failure)
      (.onFailure pub (base-consumer out-chan)))))