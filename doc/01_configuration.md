## 1. Configuration

### Project Dependencies

resilience4clj-ratelimiter is distributed through
[Clojars](https://clojars.org) with the identifier
`tessellator/resilience4clj-ratelimiter`. You can find the version
information for the latest release at
https://clojars.org/tessellator/resilience4clj-ratelimiter.

### Configuration Options

The following table describes the options available when configuring rate
limiters as well as default values. A `config` is a map that contains any of the
keys in the table. Note that a `config` without a particular key will use the
default value (e.g., `{}` selects all default values).

| Configuration Option    | Default Value | Description                                                     |
|-------------------------|---------------|-----------------------------------------------------------------|
| `:timeout-duration`     |          5000 | The number of milliseconds to wait for permission to execute    |
| `:limit-refresh-period` |           500 | The number of nanoseconds in a given refresh period             |
| `:limit-for-period`     |            50 | The number of permissions available in one limit refresh period |

A `config` can be used to configure the global registry or a single rate
limiter when it is created.

### Global Registry

This library creates a single global `registry`. The registry may contain
`config` values as well as rate limiter instances.

`configure-registry!` overwrites the existing registry with a new registry
containing one or more config values. `configure-registry!` takes a map of
name/config value pairs. When a rate limiter is created, it may refer to one of
these names to use the associated config. Note that the name `:default` (or
`"default"`) is special in that rate limiters that are created without providing
or naming a config will use this default config.

The function `rate-limiter!` will look up or create a rate limiter in the
global registry. The function accepts a name and optionally the name of a config
or a config map.

```clojure
(ns myproject.core
  (:require [resilience4clj.rate-limiter :as rl])

;; The following creates two configs: the default config and the MoreAllowances
;; config. The default config uses only the defaults and will be used to create
;; rate limiters that do not specify a config to use.
(rl/configure-registry! {"default"    {}
                         "MoreAllowances" {:limit-for-period 100}})


;; create a rate limiter named :name using the "default" config from the
;; registry and store the result in the registry
(rl/rate-limiter! :name)

;; create a rate-limiter named :more-allowed using the "MoreAllowances" config
;; from the registry and store the result in the registry
(rl/rate-limiter! :more-allowed "MoreAllowances")

;; create a rate limiter named :custom-config using a custom config map
;; and store the result in the registry
(rl/rate-limiter! :custom-config {:timeout-duration 1000})
```

### Custom Rate Limiters

While convenient, it is not required to use the global registry. You may instead
choose to create rate limiters and manage them yourself.

In order to create a rate limiter that is not made available globally, use
the `rate-limiter` function, which accepts a name and config map.

The following code creates a new rate limiter with the default config options.

```clojure
(ns myproject.core
  (:require [resilience4clj.rate-limiter :as rl]))

(def my-limiter (rl/rate-limiter :my-limiter {}))
```
