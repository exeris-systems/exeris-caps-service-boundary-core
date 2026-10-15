# Contributing to exeris-caps-service-boundary-core

This repository is source-available under the Exeris Commercial License (`commercial` under ADR-023) and open to outside contributions under the Exeris Contributor License Agreement.

## Build & test

```bash
mvn compile -Dexeris.codegen.skip=true   # pass 1 — seed capability metadata
mvn verify                               # pass 2 — generate manifest, validate, scan the Wall
```

Requires JDK 25 LTS and Maven 3.9+.

## What a change has to preserve

- **The `api` / `internal` split:** `eu.exeris.caps.serviceboundarycore.api` is the only cross-cap surface. No `internal` type may leak into an `api` signature. The package root `eu.exeris.caps.serviceboundarycore` is load-bearing: the Wall guard (ADR-055) derives this capability's identity from it (`own cap(s): [serviceboundarycore]`).
- **Spring-Free & Netty-Free boundary:** Sits on the kernel SPI and the public (non-`internal`) API of kernel Core, never on `eu.exeris.kernel.**.internal.**`. No references to Spring, Netty, or servlet APIs.
- **The committed manifest:** `src/main/generated/java/cap-manifest.json` is tracked in git. Any edit to `ServiceBoundaryCoreModule` must commit the updated manifest in the same commit.
- **The JDK baseline:** `maven.compiler.release` is 25 (LTS preview-clean, ADR-066 / ADR-069).
- **Test contract (ADR-058):** JUnit 5 + AssertJ only, no mocking libraries.

## Code of Conduct

This repository adheres to the [Exeris Systems Code of Conduct](https://github.com/exeris-systems/.github/blob/main/CODE_OF_CONDUCT.md).
