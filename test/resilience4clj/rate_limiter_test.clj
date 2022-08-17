(ns resilience4clj.rate-limiter-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [resilience4clj.rate-limiter :as rate-limiter :refer [with-rate-limiter]])
  (:import [io.github.resilience4j.ratelimiter RequestNotPermitted]
           [java.time Duration]
           [java.time ZonedDateTime]))

(defn- take-with-timeout!! [ch]
  (let [timeout-chan (async/timeout 5)]
    (async/alt!!
      ch ([v] v)
      timeout-chan :timeout)))

;; -----------------------------------------------------------------------------
;; Configuration

(deftest test-build-config
  (let [build-config #'rate-limiter/build-config
        config-map {:timeout-duration 1
                    :limit-refresh-period 2
                    :limit-for-period 3}
        config (build-config config-map)]
    (is (true? (-> config .getTimeoutDuration (.equals (Duration/ofMillis 1)))))
    (is (true? (-> config .getLimitRefreshPeriod (.equals (Duration/ofNanos 2)))))
    (is (= 3 (.getLimitForPeriod config)))))

;; -----------------------------------------------------------------------------
;; Registry

(deftest test-registry--given-config-maps
  (let [reg (rate-limiter/registry {:some {:limit-for-period 5}
                                    "other" {:limit-for-period 10}})]
    (testing "the default configuration"
      (let [cfg (.getConfiguration reg "default")]
        (is (true? (.isPresent cfg)))
        (is (= 50 (.. cfg get getLimitForPeriod)))))

    (testing "the added configurations"
      (testing "the 'some' configuration"
        (let [some-cfg (.getConfiguration reg "some")]
          (is (true? (.isPresent some-cfg)))
          (is (= 5 (.. some-cfg get getLimitForPeriod)))))

      (testing "the 'other' configuration"
        (let [other-cfg (.getConfiguration reg "other")]
          (is (true? (.isPresent other-cfg)))
          (is (= 10 (.. other-cfg get getLimitForPeriod))))))))

(deftest test-registry--override-default
  (let [reg (rate-limiter/registry {:default {:limit-for-period 10}})
        cfg (.getDefaultConfig reg)]
    (is (= 10 (.getLimitForPeriod cfg)))))

(deftest test-all-rate-limiters
  (let [reg (rate-limiter/registry)
        r1 (atom nil)
        r2 (atom nil)]
    (is (empty? (rate-limiter/all-rate-limiters reg)))

    (reset! r1 (rate-limiter/rate-limiter! reg :some-limiter))
    (is (= #{@r1} (rate-limiter/all-rate-limiters reg)))

    (reset! r2 (rate-limiter/rate-limiter! reg :other-limiter))
    (is (= #{@r1 @r2} (rate-limiter/all-rate-limiters reg)))))

(deftest test-all-rate-limiters--no-registry-provided
  (let [reg (rate-limiter/registry)
        r (rate-limiter/rate-limiter! reg :some-name {})]
    (with-redefs [rate-limiter/default-registry reg]
      (is (= #{r} (rate-limiter/all-rate-limiters))))))

(deftest add-configuration!
  (let [reg (rate-limiter/registry)]
    (rate-limiter/add-configuration! reg :my-config {:limit-for-period 6})

    (let [cfg-opt (.getConfiguration reg "my-config")]
      (is (true? (.isPresent cfg-opt)))
      (is (= 6 (.. cfg-opt get getLimitForPeriod))))))

(deftest add-configuration!--no-registry-provided
  (let [reg (rate-limiter/registry)]
    (with-redefs [rate-limiter/default-registry reg]
      (rate-limiter/add-configuration! :my-config {:limit-for-period 6})

      (let [cfg-opt (.getConfiguration reg "my-config")]
        (is (true? (.isPresent cfg-opt)))
        (is (= 6 (.. cfg-opt get getLimitForPeriod)))))))

(deftest test-find
  (let [reg (rate-limiter/registry)
        r (rate-limiter/rate-limiter! reg :some-name {})]
    (is (= r (rate-limiter/find reg :some-name)))))

(deftest test-find--no-matching-name
  (let [reg (rate-limiter/registry)]
    (is (nil? (rate-limiter/find reg :some-name)))))

(deftest test-find--no-registry-provided
  (let [reg (rate-limiter/registry)
        r (rate-limiter/rate-limiter! reg :some-name {})]
    (with-redefs [rate-limiter/default-registry reg]
      (is (= r (rate-limiter/find :some-name))))))

(deftest test-remove!
  (let [reg (rate-limiter/registry)
        r (rate-limiter/rate-limiter! reg :some-name {})]
    (is (= #{r} (rate-limiter/all-rate-limiters reg)) "before removal")

    (let [result (rate-limiter/remove! reg :some-name)]
      (is (= r result))
      (is (empty? (rate-limiter/all-rate-limiters reg))))))

(deftest test-remove!--no-registry-provided
  (let [reg (rate-limiter/registry)
        r (rate-limiter/rate-limiter! reg :some-name {})]
    (with-redefs [rate-limiter/default-registry reg]
      (is (= #{r} (rate-limiter/all-rate-limiters reg)) "before removal")

      (let [removed (rate-limiter/remove! :some-name)]
        (is (= r removed))
        (is (empty? (rate-limiter/all-rate-limiters reg)))))))

(deftest test-remove!--no-matching-name
  (let [reg (rate-limiter/registry)
        r (rate-limiter/rate-limiter! reg :some-name {})]
    (is (= #{r} (rate-limiter/all-rate-limiters reg)) "before removal")

    (let [result (rate-limiter/remove! reg :other-name)]
      (is (nil? result))
      (is (= #{r} (rate-limiter/all-rate-limiters reg))))))

(deftest test-replace!
  (let [reg (rate-limiter/registry)
        r (rate-limiter/rate-limiter! reg :some-name {})
        new (rate-limiter/rate-limiter! reg :some-name {})
        result (rate-limiter/replace! reg :some-name new)]
    (is (= r result))
    (is (= #{new} (rate-limiter/all-rate-limiters reg)))))

(deftest test-replace!--no-matching-name
  (let [reg (rate-limiter/registry)
        r (rate-limiter/rate-limiter :some-name {})
        result (rate-limiter/replace! reg :some-name r)]
    (is (nil? result))
    (is (empty? (rate-limiter/all-rate-limiters reg)))))

(deftest test-replace!--mismatched-name
  ;; This is an interesting case because normally the registry will have rate 
  ;; limiters with names that match the name in the registry itself. But using
  ;; replace! you can change that.
  ;;
  ;; This test demonstrates that the end result of a replace! can
  ;; be a little unexpected...
  (let [reg (rate-limiter/registry)
        orig (rate-limiter/rate-limiter! reg :some-name {})
        new (rate-limiter/rate-limiter :other-name {})
        result (rate-limiter/replace! reg :some-name new)]
    (is (= result orig))
    (is (= #{new} (rate-limiter/all-rate-limiters reg)))

    (is (= "other-name" (rate-limiter/name (rate-limiter/find reg :some-name))))
    (is (nil? (rate-limiter/find reg :other-name)))))

(deftest test-replace!--no-registry-provided
  (let [reg (rate-limiter/registry)]
    (with-redefs [rate-limiter/default-registry reg]
      (let [old (rate-limiter/rate-limiter! reg :some-name {})
            new (rate-limiter/rate-limiter :some-name {})
            replaced (rate-limiter/replace! :some-name new)]
        (is (= old replaced))
        (is (= #{new} (rate-limiter/all-rate-limiters reg)))))))

;; -----------------------------------------------------------------------------
;; Registry Events

(deftest test-emit-registry-events!
  (let [reg (rate-limiter/registry)
        event-chan (async/chan 1)
        first-limiter (atom nil)
        second-limiter (rate-limiter/rate-limiter :some-limiter {})]
    (rate-limiter/emit-registry-events! reg event-chan)

    (testing "when a rate limiter is added to the registry"
      (reset! first-limiter (rate-limiter/rate-limiter! reg :some-limiter))
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :added
                :added-entry @first-limiter}
               (dissoc event :creation-time)))))

    (testing "when a rate limiter is replaced in the registry"
      (rate-limiter/replace! reg :some-limiter second-limiter)
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :replaced
                :old-entry @first-limiter
                :new-entry second-limiter}
               event))))

    (testing "when a rate limiter is removed from the registry"
      (rate-limiter/remove! reg :some-limiter)
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :removed
                :removed-entry second-limiter}
               event))))))

(deftest test-emit-registry-events!--no-registry-provided
  (let [reg (rate-limiter/registry)
        event-chan (async/chan 1)]
    (with-redefs [rate-limiter/default-registry reg]
      (rate-limiter/emit-registry-events! event-chan)
      (rate-limiter/rate-limiter! reg :some-name))

    (let [event (take-with-timeout!! event-chan)]
      (is (= :added (:event-type event))))))

(deftest test-emit-registry-events!--with-only-filter
  (let [reg (rate-limiter/registry)
        event-chan (async/chan 1)]
    (rate-limiter/emit-registry-events! reg event-chan :only [:added])

    (testing "it raises the added event"
      (rate-limiter/rate-limiter! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :added (:event-type event)))))

    (testing "it does not raise the removed event"
      (rate-limiter/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))))

(deftest test-emit-registry-events!--with-exclude-filter
  (let [reg (rate-limiter/registry)
        event-chan (async/chan 1)]
    (rate-limiter/emit-registry-events! reg event-chan :exclude [:added])

    (testing "it does not raise the added event"
      (rate-limiter/rate-limiter! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))

    (testing "it raises the removed event"
      (rate-limiter/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :removed (:event-type event)))))))

(deftest test-emit-registry-events!-only-filter-trumps-exclude-filter
  (let [reg (rate-limiter/registry)
        event-chan (async/chan 1)]
    (rate-limiter/emit-registry-events! reg event-chan :only [:added] :exclude [:added])

    (testing "it raises the added event"
      (rate-limiter/rate-limiter! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :added (:event-type event)))))

    (testing "it does not raise the removed event"
      (rate-limiter/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))))

;; -----------------------------------------------------------------------------
;; Retry events

(defn- check-base-event [event expected-event-type expected-rate-limiter-name]
  (let [{:keys [event-type rate-limiter-name creation-time]} event]
    (is (not= :timeout event))
    (is (= expected-event-type event-type))
    (is (= expected-rate-limiter-name rate-limiter-name))
    (is (instance? ZonedDateTime creation-time))))

(deftest test-emit-events!--on-success
  (let [event-chan (async/chan 1)
        r (rate-limiter/rate-limiter :some-name {})]
    (rate-limiter/emit-events! r event-chan :only [:success])

    (with-rate-limiter r
      :done)

    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :successful-acquire "some-name"))))

(deftest test-emit-events!--on-failure
  (let [event-chan (async/chan 1)
        r (rate-limiter/rate-limiter :some-name {:limit-for-period 1
                                                 :timeout-duration 1
                                                 :limit-refresh-period 50000000})]
    (rate-limiter/emit-events! r event-chan :only [:failure])

    (with-rate-limiter r
      :done)

    (is (thrown? RequestNotPermitted (with-rate-limiter r :done)))

    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :failed-acquire "some-name"))))