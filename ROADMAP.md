---
title: "exeris-caps-service-boundary-core — Roadmap"
type: roadmap
visibility: public
owning-repo: exeris-caps-service-boundary-core
status: active
last-verified: 2026-10-15
---

# exeris-caps-service-boundary-core — Roadmap

This repository is a proof of concept of the Service Boundary substrate: the route registry with request
admission, the request context, the lifecycle hooks and the filter chain. This roadmap records what the code
does not cover today. Read it together with the code, and let the pull request that closes a gap update it.

Milestones are versions, not dates.

## Before 0.1.0

No item.

## Later

### Admission outside `handle()` enforces no route requirements

`RequestFilterChain.execute(exchange, routeMatch)` runs the filters, but it checks no `RouteRequirement` of the
matched route and never seals the admission context; only `ApiSurfaceRegistry.handle()` does both
(`internal/DefaultApiSurfaceRegistry.java`, `execute` and `handle`). A host that drives the chain directly, as
the gateway `policy-chain` host would, gets filters without the 401 and 403 answers. This waits on the rule for
that host: either `execute` returns the denial, or the requirement check moves into a shared step.

### A filter or handler that does not answer leaves the exchange open

When a filter returns `false` without responding, `handle()` returns without answering (`admit` and `handle` in
`internal/DefaultApiSurfaceRegistry.java`), and a handler that returns without responding is not answered
either. A filter that throws an `Exception` is answered 500, but an `Error` propagates out of `handle()`
unanswered. The request is never served, so nothing is admitted by mistake, but the caller waits. Whether the
kernel completes an unanswered exchange has not been verified here. A default 500 after an unanswered `false`
would make the contract independent of that.

### A route without requirements is served anonymously by default

A route built with no requirements, or with `null`, declares nothing and is served to a request that no filter
authenticated (`api/ApiSurfaceRoute.java`, constructor; README, "Route requirements are enforced"). A
composition can already make a forgotten requirement fail closed: `ServiceBoundaryCoreModule.builder()
.strictRoutes(true)` refuses such a route at registration, and `RouteRequirement.permitAll()` declares an
anonymous one. Strict mode is opt-in. Making it the default changes every consumer's routes, so it waits on a
decision before any consumer depends on the current default.

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
