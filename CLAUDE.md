---
title: "CLAUDE.md — exeris-caps-service-boundary-core"
type: reference
visibility: public
owning-repo: exeris-caps-service-boundary-core
status: active
last-verified: 2026-09-09
---

# CLAUDE.md — exeris-caps-service-boundary-core

This repository's agent contract is [`AGENTS.md`](AGENTS.md), and its detailed semantics live in [`.agents/`](.agents) — policies, references, skills, role profiles and workflows. Read `AGENTS.md` first; it is the portable entry point every compatible agent can discover.

This file exists only as a pointer because a Claude client looks for this filename ([`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md) rule 7). It states no rule of its own: authoring rules here would violate the single-source-of-truth invariant mandated by ADR-085 §I.
