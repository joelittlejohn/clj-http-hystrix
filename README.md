# clj-http-hystrix [![Build Status](https://travis-ci.org/joelittlejohn/clj-http-hystrix.svg?branch=master)](https://travis-ci.org/joelittlejohn/clj-http-hystrix)

![latest version](https://clojars.org/clj-http-hystrix/latest-version.svg)

A Clojure library to wrap clj-http requests as [hystrix](https://github.com/Netflix/Hystrix) commands whenever a request options map includes `:hystrix/...` keys.

## Usage

When you start your app, add:

```clj
(clj-http-hystrix.core/add-hook)
```

Whenever you make an http request, add one or more of the hystrix-clj options to your options map:

```clj
(http/get "http://www.google.com" {:hystrix/command-key      :default
                                   :hystrix/fallback-fn      default-fallback
                                   :hystrix/group-key        :default
                                   :hystrix/threads          10
                                   :hystrix/queue-size       5
                                   :hystrix/timeout-ms       600
                                   :hystrix/bad-request-pred client-error?}}
```
Any values not supplied will be set to their default values as above. Requests with no Hystrix-related keys won't use Hystrix.

## Bad requests

Hystrix allows some failures to be marked as bad requests, that is, requests that have failed because of a badly formed request rather than an error in the downstream service<sup>[1](https://github.com/Netflix/Hystrix/wiki/How-To-Use#error-propagation)</sup>. clj-http-hystrix allows a predicate to be supplied under the `:hystrix/bad-request-pred` key, and if this predicate returns `true` when given a response exception, then the failure will be considered a 'bad request' (and not counted towards the failure metrics for a command).

By default, all failures are counted towards the failure metrics for a command. There are some useful predicates and predicate generators provided<sup>[2](https://github.com/joelittlejohn/clj-http-hystrix/blob/2811a183b93d53cb18c464197e31757cd9e4dcae/src/clj_http_hystrix/core.clj#L74)</sup><sup>[3](https://github.com/joelittlejohn/clj-http-hystrix/blob/2811a183b93d53cb18c464197e31757cd9e4dcae/src/clj_http_hystrix/core.clj#L80)</sup>, the default is to treat the 4xx family of response codes (client errors) as Hystrix bad requests.

## License

Copyright © 2014 Joe Littlejohn

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
