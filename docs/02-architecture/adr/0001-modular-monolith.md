# ADR-0001: Modular monolith with Spring Modulith and separately scaled runtime roles

- **Status:** Accepted (2026-10-16, brief baseline 3; X-02 exception for read-only global reference data). **Amendment 1 (2026-10-16, R2-01) — Proposed:** the `ingest` runtime role is deferred to Phase 2 (decision 5).
- **Date:** 2026-10-16
- **Deciders:** Solution architect (proposer), PM, tech leads
- **Related:** Architecture brief B2/B3; [architecture-overview](../architecture-overview.md) §3; ADR-0004, ADR-0011

## Context

YourHRM has six business modules (`saas-platform`, `core`, `people`, `time-attendance`, `payroll`, `recruitment`) aligned with six PostgreSQL schemas. The PM baseline fixes a modular monolith with enforced boundaries, where modules talk only through published APIs and domain events, and where payroll calculation and punch ingestion can scale independently. The team is small to mid-sized and must deliver the core features of four MVP modules plus the platform and the opening-balance import (no Oracle migration, D-09) within a tight timeline. Effective-dated, cross-module flows (hire, period close, leaver) are frequent and benefit from in-process calls with strong typing.

## Decision

1. Build **one Spring Boot application** organised as a Gradle multi-project: one subproject per module (`backend/src/modules/*`) and per shared kernel library (`backend/src/shared/*`).
2. Use **Spring Modulith 2.x** to declare modules, verify dependencies (`ApplicationModules.verify()`), run module-scoped integration tests and generate module documentation; add **ArchUnit** rules (no `internal` access across modules, no `double` in payroll, no cross-schema SQL).
3. Allowed compile-time direction: `recruitment → payroll → time-attendance → people → core → saas-platform → shared/*`; feature modules may call `saas-platform` directly. Reverse flows use events (schemas in `shared/contracts`) or SPIs declared by the consumer.
4. Each module owns its schema; its database role has no grants on other schemas. **Single exception (X-02, accepted 2026-10-16):** read-only `SELECT` on the Core/SaaS global reference tables enumerated in ADR-0021 (country, currency, admin units, banks, code lists, exchange rates, own tenant row), guarded by a DB test (`sys.cross_schema_grant_violations()`) and an ArchUnit rule. Job enqueue and audit append are platform plumbing listed in the same table.
5. Deploy the same image in **runtime roles** that scale independently: `api` (stateless REST) and `worker` (jobs, payroll partitions, outbox relay, event-delivery jobs, imports and file exports; several Deployments per worker pool). A thin separate **`ingest` application for live punches is a Phase-2 role (Amendment 1, R2-01)**: Phase 1 has no live device connector, and device-export files are imported by the `general` worker pool through the Core import engine. The structural provision of baseline B3 (punch ingestion scales separately) is kept: `src/ingest/` and the `hrm-ingest` Helm chart are created when the Phase-2 connector starts, without changing modules.

## Alternatives considered

- **Microservices per module from day one.** Rejected: distributed transactions and operational overhead (6+ deployables, per-service DBs, network contracts) for a team that has not yet stabilised domain boundaries; slows MVP.
- **Unstructured monolith.** Rejected: boundaries erode, contradicting the single-owner baseline and making later extraction impossible.
- **JPMS modules only.** Rejected as the sole mechanism: poor Spring integration; does not cover event contracts or test slicing.

## Consequences

- + Simple deployment, in-process calls, one transaction per module use case, fast refactoring.
- + Workers (and, in Phase 2, ingestion) scale separately without a separate codebase.
- + A module can later be extracted (APIs are DTO-based; events already go through the outbox and a transport-neutral bus, Kafka from Phase 2, ADR-0004 rev. 2).
- − A defect or memory leak in one module affects the whole `api` process; mitigated by bulkheads (separate worker pools, timeouts) and good observability.
- − Requires CI discipline: Modulith verification and ArchUnit are build-breaking.
- − All modules share a release train; per-tenant flags (ADR-0003) decouple feature exposure from deployment.
