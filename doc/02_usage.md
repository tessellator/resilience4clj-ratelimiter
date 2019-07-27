## 2. Usage

### Executing Code Protected by a Rate Limiter

There are two ways to execute code to be protected by the rate limiter:
`execute` and `with-rate-limiter`.

`execute` executes a single function within the context of the rate limiter
and applies any args to it. If the rate limiter has no more allowances in
the current refresh period, calls will wait up to a timeout duration for
permission to execute. If the timeout duration expires before the call receives
permission to execute, an exception is thrown instead.

```clojure
> (require '[resilience4clj.rate-limiter :as rl])
;; => nil

> (rl/execute (rl/rate-limiter! :my-limiter) map inc [1 2 3])
;; => (2 3 4) if :my-limiter has allowances; else waits
;;    OR
;;    throws an exception if permission to execute is not granted before timeout
```

`execute` is rather low-level. To make execution more convenient, this library
also includes a `with-rate-limiter` macro that executes several forms within
a context protected by the rate limiter. When you use the macro, you must
either provide a rate limiter or the name of one in the global registry. If
you provide a name and a rate limiter of that name does not already exist in
the global registry, one is created with the `:default` config.

```clojure
> (require '[resilience4clj.rate-limiter :refer [with-rate-limiter]])
;; => nil

> (with-rate-limiter :my-limiter
    (http/get "https://www.example.com")
    ;; other code here
  )
;; => some value if :my-limiter has allowances; else waits
;;    OR
;;    throws an exception if permission to execute is not granted before timeout
```

### Dynamic Configuration

In some situations, you may wish to change the configuration of a rate limiter
after it has already been created. There are two functions to change the
configuration of a given rate limiter: `change-timeout-duration!` and
`change-limit-for-period!`.

`change-timeout-duration!` changes the timeout duration for the rate limiter.
The new timeout duration will not affect calls currently waiting on permission
to execute. Only new calls will be subject to the new duration.

`change-limit-for-period!` changes the allowable number of calls during a given
limit refresh period. The new limit does not take effect until the next refresh
period. The current refresh period is not affected.
