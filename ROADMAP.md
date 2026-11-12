---
title: "exeris-caps-service-boundary-core — Roadmap"
type: roadmap
visibility: public
owning-repo: exeris-caps-service-boundary-core
status: active
last-verified: 2026-11-12
---

# exeris-caps-service-boundary-core — Roadmap

This repository is a proof of concept of the Service Boundary substrate: the route registry with request
admission, the request context, the lifecycle hooks and the filter chain. This roadmap records what the code
does not cover today. Read it together with the code, and let the pull request that closes a gap update it.

Milestones are versions, not dates.

## Before 0.1.0

No item.

## Later

### The admission path allocates on every request

The organisation's rule is that a request path does not allocate, and the admission path of `handle()` does,
for a route with no filter and no path parameter as for any other. An allocation test in this repository, built
on the kernel TCK's allocation monitor, measures a steady non-zero allocation per `handle()` call for an
anonymous route, and it pins that strict mode adds none; it does not pin the total. Reading the code, the
sites on the success path are the following, in `internal/` unless stated.

- `DefaultApiSurfaceRegistry.handle`: a `MatchedHttpExchange` per request, and the `Optional` and `RouteMatch`
  returned by `matchRoute`.
- `DefaultApiSurfaceRegistry.matchRoute`: an iterator over the copy-on-write route list; for a parameterised
  route, the `LinkedHashMap`, the `Matcher` and the captured substrings of `ApiSurfaceRoute.extractPathParams`,
  then the copies made by the `RouteMatch` and `MatchedHttpExchange` constructors (`Map.copyOf`).
- `api/ApiSurfaceRoute.matchesPath` and `normalizePath`: a `Matcher` for each parameterised route scanned, and
  a new string when the path has a trailing slash or no leading one.
- `DefaultApiSurfaceRegistry.stripQueryString`: a substring when the path carries a query string.
- `DefaultApiSurfaceRegistry.admit` and `filters()`: an `ArrayList` copy of the filters, a sort, and an
  unmodifiable wrapper, on every request; the iteration over them; and, for each filter, two lambdas capturing
  the filter, the exchange and the route match.
- `DefaultAdmissionContext.open`: the admission context with its `LinkedHashMap`, and, when the host bound no
  `RequestContext`, a builder, a context and a `UUID.randomUUID().toString()` correlation id.
- `DefaultAdmissionContext.requestContext`: a new builder, `LinkedHashMap` and `DefaultRequestContext` on each
  call, which `admit` makes for each filter and `handle` makes once more before the handler.
- `DefaultAdmissionContext.callWith`, `DefaultRequestContext.bind`: a `ScopedValue` carrier per binding, two
  per filter and one for the handler.
- `DefaultApiSurfaceRegistry.runHandler`: a lambda capturing the route match and the exchange.
- `DefaultApiSurfaceRegistry.unmet`: an iterator over the route's requirements.

Those named from the code read are the call sites above; the allocation that each `ScopedValue` binding,
iterator and lambda performs after the JIT's escape analysis is not measured per site. Closing the gap needs
the route index to hand out a preallocated match and path-parameter holder, the filter order computed once at
freeze and read without a copy, a request context built once and updated in place or lent to the handler, and
a binding that captures no per-request state, each pinned by the allocation test with a total of zero.

### A composition that skips the boundary's drain leaves attached filters in place

Teardown begins in the boundary's `drain` or `terminate`. A composition that terminates a capability while the
registry is still frozen, because it skipped the boundary's drain (for instance when its drain budget is spent)
and has not called `beginTeardown()`, sees that capability's `unregisterFilter` throw; the filter stays
registered and keeps being enforced, and the boundary's own `terminate()` ends serving with 503. Closing this
needs either a pre-terminate step in the composition runtime or removal after freeze implying teardown.

### Admission outside `handle()` enforces no route requirements

`RequestFilterChain.execute(exchange, routeMatch)` runs the filters, but it checks no `RouteRequirement` of the
matched route and never seals the admission context; only `ApiSurfaceRegistry.handle()` does both
(`internal/DefaultApiSurfaceRegistry.java`, `execute` and `handle`). A host that drives the chain directly, as
the gateway `policy-chain` host would, gets filters without the 401 and 403 answers. This waits on the rule for
that host: either `execute` returns the denial, or the requirement check moves into a shared step.

### Unmatched requests skip admission

A path or method that matches no route is answered 404 or 405 before any filter runs
(`handle` in `internal/DefaultApiSurfaceRegistry.java`), so a rate-limiting filter does not see probing of
unknown paths. This is documented; counting such requests needs a phase that runs before route matching, which
is a change to the ADR-099 pipeline and not to this repository alone.

### Path matching works on the raw path string

Matching strips the query string and trims whitespace and one trailing slash, and nothing else
(`api/ApiSurfaceRoute.java`, `normalizePath` and `matchesPath`; `stripQueryString` in the registry). It does not
decode percent-encoding, resolve dot segments or collapse repeated slashes, and a `{name}` segment accepts
any run of characters except `/`. A path that does not match literally is answered 404, so this fails closed,
but it routes `%61bc` and `abc` differently. The canonical form is the kernel's to define and is not verified
here; this waits on that statement.

### Methods are matched exactly

A route serves only its own method: `HEAD` does not fall back to a `GET` route and `OPTIONS` is answered 405
(`ApiSurfaceRoute.matchesMethod`, and the 405 branch of `handle`). Preflight handling belongs to a CORS policy
capability in front of the boundary, and the `HEAD` rule is a design choice that has not been made.

### Parameterised routes match in registration order

When several parameterised routes match one path, the first registered wins, not the one with the most literal
segments (`matchRoute` in `internal/DefaultApiSurfaceRegistry.java`). Two routes of different shape, such as
`/a/{id}/x` and `/a/{name}/{rest}`, therefore resolve by registration order. Registration refuses only routes of
identical shape. The lookup is also a linear scan of all routes, which is acceptable for a proof of concept and
not for a large surface. A specificity rule and an indexed lookup wait on the first consumer with a route set of
that size.

### Lifecycle failures are not unwound by the hooks

`CompositeServiceLifecycleHooks.onServiceInitialize` and `onServiceReady` stop at the first failing hook and
terminate none of the hooks already initialised (`internal/CompositeServiceLifecycleHooks.java`). The
`BoundaryServiceHook` javadoc says a failing initialisation causes a boot unwind, which the composition
runtime would have to drive by calling `onServiceTerminate`. That call has not been verified against the
runtime and has no test here.
