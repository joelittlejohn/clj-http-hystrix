# clj-http-hystrix [![Build Status](https://travis-ci.org/joelittlejohn/clj-http-hystrix.svg?branch=master)](https://travis-ci.org/joelittlejohn/clj-http-hystrix) [![Coverage Status](https://coveralls.io/repos/joelittlejohn/clj-http-hystrix/badge.svg?branch=master)](https://coveralls.io/r/joelittlejohn/clj-http-hystrix?branch=master)

![latest version](https://clojars.org/clj-http-hystrix/latest-version.svg)

A Clojure library to wrap clj-http requests as [hystrix](https://github.com/Netflix/Hystrix) commands whenever a request options map includes `:hystrix/...` keys.

## Usage

When you start your app, add:

```clj
(clj-http-hystrix.core/add-hook)
```

Whenever you make an http request, add one or more of the hystrix-clj options to your options map:

```clj
(http/get "http://www.google.com" {:hystrix/command-key             :default
                                   :hystrix/fallback-fn             default-fallback
                                   :hystrix/group-key               :default
                                   :hystrix/threads                 10
                                   :hystrix/queue-size              5
                                   :hystrix/timeout-ms              1000
                                   :hystrix/breaker-request-volume  20
                                   :hystrix/breaker-error-percent   50
                                   :hystrix/breaker-sleep-window-ms 5000
                                   :hystrix/bad-request-pred client-error?}}
```
Any values not supplied will be set to their default values as above. Requests with no Hystrix-related keys won't use Hystrix.

## Bad requests

Hystrix allows some failures to be marked as bad requests, that is, requests that have failed because of a badly formed request rather than an error in the downstream service<sup>[1](https://github.com/Netflix/Hystrix/wiki/How-To-Use#error-propagation)</sup>. clj-http-hystrix allows a predicate to be supplied under the `:hystrix/bad-request-pred` key, and if this predicate returns `true` for a given request & response, then the failure will be considered a 'bad request' (and not counted towards the failure metrics for a command).

By default, all client errors (4xx family of response codes) are considered Hystrix bad requests and are not counted towards the failure metrics for a command. There are some useful predicates and predicate generators provided<sup>[2](https://github.com/joelittlejohn/clj-http-hystrix/blob/18a4f8f9636e531558a57557681c5d5861b27e42/src/clj_http_hystrix/core.clj#L67)</sup>.

## Cached vs dynamic configuration

Hystrix caches configuration for a command and hence there are limits to how this library can react to configuration options that vary dynamically. For a given command-key, the `:hystrix/timeout-ms` will be fixed on first use. This means it's a bad idea to reuse the `:hystrix/command-key` value in many parts of your app. When you want a new configuration, you should use a new `:hystrix/command-key` value.

The same is true for thread pools - configuration is cached per `:hystrix/group-key`, so if you need to use a different value for `:hystrix/queue-size` or `:hystrix/threads` then you should use a new `:hystrix/group-key` value.

## License

Copyright © 2014 Joe Littlejohn, Mark Tinsley

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
