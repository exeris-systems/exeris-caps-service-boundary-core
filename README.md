# exeris-caps-service-boundary-core

Substrate aggregate capability for the Service Boundary family (IDP, PIM, OMS, Headless CMS API). Provides the foundational API surface registry, service lifecycle hooks, and request context execution pipeline on top of the Exeris kernel SPIs. Runs kernel-direct with zero Spring, Netty, or servlet dependencies.

## Where it sits

- **Layer:** Tier 2 — Layer 1 Substrate Aggregate per `high-level-architecture.md` §3.2.
- **Substrate dependency:** Sits directly on `exeris-kernel-spi` (HTTP, Security, Context).
- **Consuming capabilities:** Foundation for 23 Service Boundary platform capabilities (Layer 4) and domain primitives (Layer 5), including `multi-tenancy`, `audit-trail`, `rbac-policy`, `rest-emission`, and `contact-graph`.
- **Wall boundary (ADR-006 / ADR-055):** Crosses no Spring, Netty, or servlet boundaries; exposes only `eu.exeris.caps.serviceboundarycore.api.*` across capabilities.

## Build & test

Requires JDK 25 LTS and Maven 3.9+.

```bash
# Two-pass build on clean checkout:
mvn compile -Dexeris.codegen.skip=true   # pass 1: seed processor metadata
mvn verify                               # pass 2: generate manifest, validate DAG and Wall
```

Single module test execution:
```bash
mvn test
```

## Documentation

- [Capability Author Guide](https://github.com/exeris-systems/exeris-docs/blob/main/cap-author-guide.md) — capability development lifecycle and rules.
- [High-Level Architecture (HLA)](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) — Tier 2 capability map and Service Boundary family.
- [B2B Technical Whitepaper](https://github.com/exeris-systems/exeris-docs/blob/main/b2b-technical-whitepaper.md) — SKU composition model.

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

Source-available under the [Exeris Commercial License](LICENSE) (BSL-style) per ADR-023. Production use requires an active Platform SKU subscription or Platform-tier license. Evaluation and audit are royalty-free.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Contributions require compliance with Exeris Standards and the [Code of Conduct](https://github.com/exeris-systems/.github/blob/main/CODE_OF_CONDUCT.md).
