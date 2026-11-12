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

The organisation's rule is that a request path does not allocate, and the admission path of `handle()` still
does. An allocation test in this repository, built on the kernel TCK's allocation monitor, measures a steady
non-zero allocation per `handle()` call for an anonymous route, and it pins that strict mode adds none; it does
not pin the total.

A literal route pays nothing only when it is registered before the parameterised routes. The match walks the routes
in registration order, and every parameterised route of the same HTTP method scanned before the match runs its
pattern, so a literal route registered after parameterised routes of its method pays for scanning each of them; one registered before them stops the
walk first.

Removed already, for a route with no path parameter: the per-request copy and sort of the filters (the registry
keeps one pre-sorted array and reads it once per request), the `RouteMatch` and `Optional` (the match is built
at registration), the attribute map of the admission context (allocated on the first write), the request
context rebuilt on every `requestContext()` call (built once per change of admission state, and without a
builder), the seed context and the correlation id generated for every request (the id is generated when
something first reads it, and is still `UUID.randomUUID()`), the iterator over a route's requirements, and the
substring made for a query string or a trailing slash.

What a request for such a route still allocates, in `internal/`:

- `DefaultAdmissionContext`, one per matched request, and a `DefaultRequestContext` for each change of admission
  state: at least one, because the handler runs in it, and another for each filter that runs after an earlier filter
  changed the state. Closing this needs a request context built once and updated in place or lent to
  the handler, which changes what a filter may keep of it.
- `MatchedHttpExchange`, one per matched request, which carries the path parameters and the answered state.
- The `ScopedValue` binding of each call: a carrier and a snapshot allocated by the JDK for every
  `ScopedValue.where(...).call(...)`. `admit` makes two bindings per filter (the admission context, then the
  request context) and `runHandler` makes one. The JDK allocates these and this repository cannot remove them
  while it binds; it can bind less, by binding once around the whole filter chain, which needs the filters to
  read the current context from something other than a per-filter binding.
- The two lambdas that `admit` creates per filter, and the one in `runHandler`. The JIT removes them in some
  compiled shapes and keeps them in others, so their cost varies from one JVM to the next.
- A correlation id costs a `SecureRandom` draw, a `UUID` and its string at the first read, by design: the id
  stays unpredictable.

A route with path parameters allocates more. `ApiSurfaceRoute.matchesPath` and `extractPathParams` run a regular
expression and make a `Matcher` for each parameterised route scanned, the `LinkedHashMap` and the captured
substrings of the extraction, and the copies of that map made by the `RouteMatch` and `MatchedHttpExchange`
constructors; each parameterised route of the same HTTP method scanned before the match costs again, so the cost grows with the number
of routes. Closing this needs matching that does not use a regular expression, an index from the path to the
route, and a path-parameter holder that is lent rather than copied. The 404 and 405 answers allocate too: both
build the set of allowed methods, and may cut a substring (when the request path has a query string) and run the
pattern of each parameterised route of any method, because the walk tests every route, literal ones included; a 405 also builds the `Allow` value and the header list. Which of these
the JIT removes varies, so this page does not count them.

None of the remaining items has an allocation figure in this page; the allocation test is to be extended to pin
a total per scenario once the remaining sites are decided, with the `ScopedValue` bindings counted as the
floor the JDK imposes.

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
segments (`findMatch`, behind `matchRoute`, in `internal/DefaultApiSurfaceRegistry.java`). Two routes of different shape, such as
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
