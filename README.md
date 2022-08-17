# resilience4clj-ratelimiter

A small Clojure wrapper around the
[resilience4j RateLimiter module](https://resilience4j.readme.io/docs/ratelimiter).
Requires Clojure 1.5 or later for JDK 8, and Clojure 1.10 or later for JDK 9+.

[![clojars badge](https://img.shields.io/clojars/v/tessellator/resilience4clj-ratelimiter.svg)](https://clojars.org/tessellator/resilience4clj-ratelimiter)
[![cljdoc badge](https://cljdoc.org/badge/tessellator/resilience4clj-ratelimiter)](https://cljdoc.org/d/tessellator/resilience4clj-ratelimiter/CURRENT)

## Quick Start

The following code defines a function `make-remote-call` that will limit the
number of calls to an external service over a period of time using a rate
limiter named `:my-limiter` in the global registry. If the rate limiter does not
already exist in the global registry, one is created.

If the rate of calls to `make-remote-call` exceeds the rate limit, the calls
will begin to wait for permission to run during the next refresh period. If a
call does not receive permission to execute before a timeout period expires, an
exception will be thrown.

```clojure
(ns myproject.some-client
  (:require [clj-http.client :as http]
            [resilience4clj.rate-limiter :refer [with-rate-limiter]])

(defn make-remote-call []
  (with-rate-limiter :my-limiter
    (http/get "https://www.example.com")))
```

Refer to the [configuration guide](/doc/01_configuration.md) for more
information on how to configure the global registry as well as individual
rate limiters.

Refer to the [usage guide](/doc/02_usage.md) for more information on how to
use rate limiters.

## License

Copyright © 2019-2020,2022 Thomas C. Taylor and contributors.

Distributed under the Eclipse Public License version 2.0.
