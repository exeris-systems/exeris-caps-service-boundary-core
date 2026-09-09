---
title: "exeris-caps-service-boundary-core: Substrate Aggregate Capability for Service Boundary SKUs"
type: reference
visibility: public
owning-repo: exeris-caps-service-boundary-core
status: active
last-verified: 2026-09-09
---

# exeris-caps-service-boundary-core

Guardrails and operating contract for AI assistants working in this repository.

## 1. Mission and scope

`exeris-caps-service-boundary-core` is the foundational **substrate aggregate capability** (Tier 2, Layer 1) for the Service Boundary family (IDP, PIM, OMS, Headless CMS API) in the Exeris platform.

It provides three core services to higher-layer capabilities:
- `ApiSurfaceRegistry` (`eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry`): Ingress endpoint and route registration for codegen-emitted `@ExerisDomain` + `@Action` handlers.
- `ServiceLifecycleHooks` (`eu.exeris.caps.serviceboundarycore.api.ServiceLifecycleHooks`): Lifecycle dispatch and interceptors for registered service boundary handlers.
- `RequestContext` (`eu.exeris.caps.serviceboundarycore.api.RequestContext`): Request-scoped context carrying tenant identity, security principal, trace ID, and attributes via Java `ScopedValue`.

License: `commercial` (Exeris Commercial License, source-available, public visibility per ADR-023).

## 2. Operating contract & non-negotiable boundaries

- **The Cap-tier Wall (ADR-006 / ADR-024 / ADR-055):**
  - Cross-cap calls must use `@Provides` services in `eu.exeris.caps.serviceboundarycore.api.*`.
  - `eu.exeris.caps.serviceboundarycore.internal.*` is private to this capability.
  - Zero tolerance for Spring (`org.springframework.*`), Netty (`io.netty.*`), reactive streams (`reactor.*`), or servlet APIs (`jakarta.servlet.*`).
  - Package root `eu.exeris.caps.serviceboundarycore` is load-bearing; the Wall guard derives `own cap(s): [serviceboundarycore]` from the segment following `eu.exeris.caps.`.
- **JDK 25 LTS preview-clean:** Sources compile at `maven.compiler.release` 25 without `--enable-preview` on main sources (ADR-066 / ADR-069).
- **The committed manifest:** `src/main/generated/java/cap-manifest.json` is committed in git. Every change to capability declarations must keep the manifest stamped (`validated: true`) and synchronized.
- **Testing contract (ADR-058):** JUnit 5 + AssertJ only. No mocking frameworks (Mockito, EasyMock, etc.). Test doubles are written explicitly.

## 3. Architecture and documentation entry points

- [High-Level Architecture](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) — Tier 2 capability map and Service Boundary SKU family.
- [Capability Author Guide](https://github.com/exeris-systems/exeris-docs/blob/main/cap-author-guide.md) — rules for `@CapabilityModule`, `@Provides`, `@Requires`, and `@CapabilityLifecycle`.
- Governing ADRs:
  - [ADR-006](docs/adr/ADR-006.link.md) (The Wall)
  - [ADR-015](docs/adr/ADR-015.link.md) (Codegen Emission)
  - [ADR-023](docs/adr/ADR-023.link.md) (Capability Licensing Taxonomy)
  - [ADR-024](docs/adr/ADR-024.link.md) (Capability Composition Model)
  - [ADR-053](docs/adr/ADR-053.link.md) (Composition Manifest Format)
  - [ADR-055](docs/adr/ADR-055.link.md) (Cap-Tier Wall Guard)
  - [ADR-085](docs/adr/ADR-085.link.md) (Documentation and Repo-Hygiene Standards)

## 4. `.agents/` discovery

Detailed policies, skills, schemas, and vendored bundles live under [`.agents/`](.agents):
- `.agents/manifest.yaml` — composition manifest importing the `exeris-agents` bundle.
- `.agents/policies/` — non-negotiable architectural rules (The Wall, JDK baselines, testing contract).
- `.agents/vendor/` — immutable, verified vendored copy of the shared `exeris-agents` bundle.

## 5. Verification and reporting expectations

- **Build verification:**
  ```bash
  mvn compile -Dexeris.codegen.skip=true   # Pass 1: seed metadata
  mvn verify                               # Pass 2: generate manifest, validate DAG & Wall
  ```
- **Cap-tier Wall verification:**
  Must print `Cap-tier Wall clean (1 module(s), own cap(s): [serviceboundarycore])`.
- **Manifest check:**
  `git status --porcelain -- src/main/generated/` must be clean after `mvn verify`.
- **Agent bundle verification:**
  `python3 .agents/vendor/*/scripts/agents_file_check.py --root .` must pass all rules.

## 6. Provider adapters

Provider adapters (Claude, Cursor, Antigravity, Gemini) are rendered from `.agents/`. Do not edit provider adapters directly.
