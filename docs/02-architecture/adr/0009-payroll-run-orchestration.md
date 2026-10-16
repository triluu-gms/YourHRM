# ADR-0009: Payroll run orchestration — input snapshot and partitioned parallel calculation

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** NFR-SAAS-002, NFR-PAY-001/002/003, NFR-SAAS-017; gap-analysis §7, GAP-031, GAP-064; ADR-0008, ADR-0010, ADR-0011, ADR-0019; snapshot/result storage in the data architecture (DESIGN.md); [architecture-overview](../architecture-overview.md) §8

## Context

A monthly run for 10,000 employees must finish in ≤ 15 min (1,000 in ≤ 2 min; one employee ≤ 5 s) while other tenants are calculating on the same pool at 3× load during days 25–5. Results must be reproducible for ≥ 10 years and stamped with the legal pack, configuration and code versions. Inputs come from People (bi-temporal), T&A (versioned Attendance Result), Payroll variable inputs and the Legal Parameter Service. Concurrent employment across legal entities requires cross-LE rules per person.

## Decision

1. **Run state machine** per gap §7.1 with technical sub-states `SNAPSHOTTING`, `CALCULATING`, `FAILED` between `DRAFT` and `CALCULATED`.
2. **Snapshot first:** at FINAL calculation the run seals an immutable snapshot (population, People data as of period end as known at the watermark, Attendance Result versions, variable inputs, LPS `resolveSet` result, compiled configuration version, engine version, calc-behaviour flags, prior results/YTD). The engine reads only the snapshot.
3. **Partitioning:** the population is split into chunks of ~250 employees, grouped by `person_id` so concurrent employments share a chunk; each chunk is a job on the `payroll` worker pool (ADR-0011, fair-share capped at 20% of slots per tenant).
4. **Chunk execution:** bulk-load the chunk's slice (one query per input type), evaluate the compiled DAG per employee in memory, write results with `COPY` to run staging; idempotent (delete-and-rewrite per chunk).
5. **Finalisation:** aggregate, validate (minimum wage, negative net, missing bank/tax code, variance threshold), move to `CALCULATED`, publish `pay.PayrollRunStatusChanged`.
6. **Determinism:** no clock/random in the kernel; stable ordering; `BigDecimal` with explicit rounding per element; result lines hashed.
7. **Versioned kernel:** `payroll/engine` is a separately versioned artifact; older kernels remain loadable for the `ReproduceRun` job; golden pack tagged per kernel version.
8. **Retro:** recompute affected approved periods with "as known now" inputs and their own period parameters; carry element deltas into the current run.
9. **Net-to-gross:** piecewise-linear analytic inversion + integer bisection (≤ 40 iterations), smallest gross whose net ≥ target.
10. **Design target** 10k employees ≤ 5 min under peak, validated in SP-1; the 15-min NFR is a hard ceiling.

## Alternatives considered

- **Calculate in SQL / stored procedures:** fast for simple sums but formulas are tenant-configured; reproducibility and testability suffer; ties logic to the DB.
- **Spring Batch:** usable for chunking, but its job repository is not tenant-fair and duplicates our scheduler; we may reuse its readers/writers selectively.
- **Distributed compute (Spark/Flink):** unnecessary at this scale; operational cost.
- **Kafka as work distribution:** no transactional enqueue with run state; rejected in favour of the PostgreSQL job queue.

## Consequences

- + Predictable performance scaling with worker slots; isolation between tenants.
- + Full auditability and reproducibility from snapshot + stamped versions.
- − Snapshot storage volume grows (10 years × all runs); storage design and compression are the Data architect's concern.
- − Single-employee recalculation must load and re-seal a partial snapshot; designed for ≤ 5 s.
