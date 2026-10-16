# ADR-0011: Tenant-fair job scheduler and queue on PostgreSQL

- **Status:** Proposed (rev. 2026-10-16, R2-01: the queue also carries Phase-1 event deliveries — queue `events`, ordered lanes)
- **Date:** 2026-10-16
- **Related:** gap-analysis GAP-034, §9 A-8; NFR-SAAS-005 (≤ 20% per tenant), NFR-CORE-005/015, NFR-PAY-001; [architecture-overview](../architecture-overview.md) §9.5

## Context

The SaaS platform owns the job scheduler/queue. Jobs include payroll chunks, imports, exports, document batches, notifications, effective-dating activation at 00:00 ICT, period transitions, reminders/escalations, retention, DSR handlers and connector calls. It must be tenant-aware (fair share, quotas), suspension-aware, transactional with business changes, observable (progress), and scale workers horizontally.

## Decision

1. **Queue tables in PostgreSQL** with `tenant_id`, `queue`, `type`, `priority`, `payload` (versioned), `state`, `attempts`, `not_before`, `idempotency_key`, `progress`. Placement: **per cluster** (decided with the Data architect, DESIGN.md §3.1, `saas.job` is LOCAL) so enqueue is in the same transaction as the business change. Optional `ordering_key`: at most one non-terminal job per key is claimed, oldest first (ordered lanes, used by event delivery).
2. **Workers** claim with `FOR UPDATE SKIP LOCKED` using **fair-share selection**: among tenants with ready jobs, pick the one with the lowest running/allowed ratio; per-tenant cap = 20% of the pool's slots (floor 2; Enterprise reserved slots possible); priority lanes interactive > payroll > batch.
3. **Pools:** `payroll`, `general`, `events` (Phase-1 event bus, ADR-0004 rev. 2), `reporting`, and `integration` (Phase 2, ADR-0016) — separate Deployments of the worker image; KEDA scales on queue depth.
4. **Triggers:** db-scheduler (cluster-safe) for cron/calendar schedules; schedules create queue jobs.
5. **Semantics:** at-least-once with idempotent handlers; exponential retry; dead state with operator retry; heartbeats and visibility timeouts; cancellation; progress API (SSE).
6. **Tenant state:** jobs of `SUSPENDED_LOCKED` tenants pause, except an allow-list (payslip access, exports, DSR).

## Alternatives considered

- **Quartz:** no fairness/quotas; clustered mode is lock-heavy.
- **JobRunr:** good DX; fairness and multi-tenant quotas require Pro features or forks.
- **Kafka as job queue:** no transactional enqueue, awkward per-job retries/cancel/progress. (Conversely, the job queue is used as the Phase-1 event transport, ADR-0004 rev. 2.)
- **Spring Batch alone:** batch framework, not a scheduler with fairness.

## Consequences

- + Fairness and quotas are first-class; transactional enqueue avoids lost or phantom jobs.
- + No extra infrastructure.
- − PostgreSQL load from polling: mitigated with short `LISTEN/NOTIFY` wake-ups, batch claims and partial indexes; measured in SP-1/SP-2.
- − We own scheduler code (~3–4 developer-weeks).
