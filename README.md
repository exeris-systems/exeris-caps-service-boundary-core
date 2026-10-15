# exeris-caps-service-boundary-core

Substrate aggregate capability for the Service Boundary family (IDP, PIM, OMS, Headless CMS API). Provides the foundational API surface registry, service lifecycle hooks, and request context execution pipeline on top of the Exeris kernel SPIs. Runs kernel-direct with zero Spring, Netty, or servlet dependencies.

Status: proof of concept, built against `exeris-kernel` 0.13.0-SNAPSHOT. The API is not stable and no artefact has been published.

## Where it sits

- **Layer:** Tier 2 — Layer 1 Substrate Aggregate per `high-level-architecture.md` §3.2.
- **Substrate dependency:** Sits directly on `exeris-kernel-spi` (HTTP, Security, Context).
- **Consuming capabilities:** specified as the base for the Service Boundary platform capabilities (Layer 4) and domain primitives (Layer 5). Of the caps that exist today, `exeris-caps-audit-trail`, `exeris-caps-federated-login` and `exeris-caps-token-issuer` depend on it.
- **Wall boundary (ADR-006 / ADR-055):** Crosses no Spring, Netty, or servlet boundaries; exposes only `eu.exeris.caps.serviceboundarycore.api.*` across capabilities.

## Request admission

`ApiSurfaceRegistry` runs the registered `AdmissionFilter`s between route match and handler, ordered by
`AdmissionPhase` (pre-admission, rate limiting, authentication, authorisation, post-admission) and then by
name (ADR-099). Filter names are unique and non-blank; registering a duplicate is refused. Each filter
either passes the request on or answers it. A filter that verifies the caller records the principal
through `AdmissionContext.current().authenticate(...)`, once per request, and the session it authenticated
with through `authenticate(principal, sessionId)`. The handler runs inside the resulting `RequestContext`,
so `RequestContext.current().principal()` is the identity a filter verified and the only one a route uses;
the handler has no `AdmissionContext` and cannot change it.

- **Principal, tenant and session are admission-only.** `RequestContext.builder()` builds contexts without a
  principal or a session id, and `runWith`/`callWith` refuse to replace the principal, tenant or session id
  of a context that is already bound. `RequestContext.sessionId()` is the one place a session-bound
  capability reads the caller's session from.
- **No implicit tenant.** `RequestContext.tenantId()` is empty until the host binds a tenant or an
  authenticated principal carries one.
- **Route requirements are enforced.** After the filters, the registry answers 401 when a route requires an
  authenticated principal and none was recorded, and 403 when the principal lacks a required scope. A route
  with no requirement, or `RouteRequirement.permitAll()`, is served anonymously.
- **Unmatched requests skip admission.** A path or method that matches no route is answered 404 or 405
  before any filter runs, so rate limiting does not see them.

## Build & test

Requires JDK 25 LTS and Maven 3.9+.

```bash
# Two-pass build on clean checkout:
mvn compile -Dexeris.codegen.skip=true   # pass 1: seed processor metadata
mvn verify                               # pass 2: generate manifest, validate DAG and Wall
```

`exeris-sdk` 0.12.0 and `exeris-tooling` 0.10.0 resolve from Maven Central, and `exeris-kernel` 0.13.0-SNAPSHOT is resolved from GitHub Packages, which needs a token with `read:packages`. [`.github/workflows/build.yml`](.github/workflows/build.yml) is the exact sequence, and [`.github/maven-settings.xml`](.github/maven-settings.xml) the repository configuration it uses.

Single module test execution:
```bash
mvn test
```

## Documentation

- [Capability Author Guide](https://github.com/exeris-systems/exeris-docs/blob/main/cap-author-guide.md) — capability development lifecycle and rules.
- [High-Level Architecture (HLA)](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) — Tier 2 capability map and Service Boundary family.
- [B2B Technical Whitepaper](https://github.com/exeris-systems/exeris-docs/blob/main/b2b-technical-whitepaper.md) — SKU composition model.

- [Roadmap](ROADMAP.md) — what the proof of concept does not cover yet.

Precedence: HLA §3.2 & ADR-024 outrank repository README; code comments reflect current contract.

## Governing ADRs

- [ADR-006](docs/adr/ADR-006.link.md) — Spring-Free Kernel Boundary (The Wall).
- [ADR-015](docs/adr/ADR-015.link.md) — Codegen Emission Strategy.
- [ADR-023](docs/adr/ADR-023.link.md) — Capability Licensing Taxonomy (`commercial`, source-available).
- [ADR-024](docs/adr/ADR-024.link.md) — Capability Composition Model (`@Provides` / `@Requires` / Lifecycle).
- [ADR-053](docs/adr/ADR-053.link.md) — SKU Composition Manifest Format.
- [ADR-055](docs/adr/ADR-055.link.md) — Cap-Tier Wall Guard.
- [ADR-085](docs/adr/ADR-085.link.md) — Federated Documentation Architecture and Repo-Hygiene Standards.

## License

Exeris Commercial License 1.1 — see [`LICENSE`](LICENSE). `exeris-caps-service-boundary-core` is a `commercial` capability under [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md): the source is available to read, build, test and evaluate, and production use requires a commercial licence.

The cap enforces that at boot. It registers a kernel `EntitlementRequirement` whose capability id is `exeris-caps-service-boundary-core`, and in a production-class environment the kernel's contract gate refuses to start unless a signed licence manifest lists that id among its entitlements ([ADR-088](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-088-cryptographic-license-manifest-and-offline-verification.md), [ADR-089](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-089-capability-entitlement-enforcement-and-runtime-contract.md)).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Contributions require compliance with Exeris Standards and the [Code of Conduct](https://github.com/exeris-systems/.github/blob/main/CODE_OF_CONDUCT.md).
