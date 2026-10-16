# ADR-0004: Event backbone — transactional outbox and in-app relay; DB-backed bus in Phase 1, Apache Kafka in Phase 2

- **Status:** Proposed — **revision 2 (2026-10-16, decision log R2-01)**. Revision 1 (same day, also Proposed) put Kafka in Phase 1. No accepted decision is changed; the brief baseline B5 (outbox per module, canonical envelope, catalogue v1) is kept.
- **Date:** 2026-10-16
- **Related:** Architecture brief B5; gap-analysis §6, §9 A-2, GAP-017/019/020/063; NFR-PPL-002, NFR-CORE-004, NFR-REC-005; decision log R2-01 / §7.2; [architecture-overview](../architecture-overview.md) §7.2; ADR-0011 (job queue), ADR-0012 (reporting feed), ADR-0016, ADR-0028 (outbox/inbox tables); DESIGN.md §9.1

## Context

All six BRDs assume a transactional outbox and an event bus with at-least-once delivery and idempotent consumers. Gap-analysis §6 is the canonical catalogue v1 with a common envelope.

Revision 1 chose Kafka from day one because the same infrastructure was to serve three loads: business events, the **punch ingestion buffer** (500 punches/s platform peak, X-05) and **CDC to the reporting store**, plus replay and DR mirroring. Decision R2-01 (2026-10-16) removes every live external integration from Phase 1: no time-device connector or ingestion endpoint (punches come only from imported device-export files), no public webhooks, no external event consumers. That removes the two loads that justified Kafka in Phase 1:

| Kafka driver in rev. 1 | Phase 1 after R2-01 |
|---|---|
| Punch burst buffer (`att.raw_punch` topic, 500/s) | Gone: device-export files are imported in chunked jobs; X-05 and SP-3 move to Phase 2 |
| Public webhooks / external consumers | Gone: Phase 2 (ADR-0014) |
| CDC to reporting (Debezium → Kafka) | Replaced by native PostgreSQL logical replication (ADR-0012 amendment) |
| Business events between 5 modules | Remains, but at pilot volume: estimate ≤ 2 events per business write, a few events/s on average, short bursts of a few hundred/s during imports and payroll publication |
| Replay for new consumers | Covered by re-sync jobs from the owner API (already required, DESIGN §2.2) plus the 7-day outbox window |

What Kafka would still cost in Phase 1: Strimzi + 3 brokers across zones, Apicurio, MirrorMaker 2 to DC2, monitoring, upgrades, a DR runbook and team training — for a small team on a 16-week plan (C-7) and on a VN provider that may not offer managed Kafka (AR-5). Everything else in the design already relies on PostgreSQL: the job queue (ADR-0011) gives transactional enqueue, retries, dead state, operator replay, tenant fair share and KEDA scaling.

## Decision

1. **Outbox per module schema**, written in the business transaction (tables: ADR-0028). Unchanged.
2. **In-app polling relay** in `hrm-worker`: polls each cluster's outboxes with `FOR UPDATE SKIP LOCKED` (role `yhrm_relay`), marks rows published; published rows kept 7 days. Debezium is *not* on the business path. Unchanged.
3. **Phase 1 transport — DB-backed bus on the job queue (no Kafka):**
   - In the same transaction in which it marks an outbox row published, the relay calls `saas.enqueue_event_delivery()` (SECURITY DEFINER), which inserts **one delivery job per subscribed consumer** into the cluster-local `saas.job` (queue `events`, type `bus.Deliver`, `idempotency_key = consumer:event_id`).
   - **Ordering:** `ordering_key = consumer:aggregate_id`; `saas.claim_jobs()` runs at most one non-terminal job per ordering key, oldest first. Each consumer therefore sees the events of one aggregate in commit order, which is the per-key order Kafka would give.
   - **Payload:** the job carries a pointer (source cluster, schema, `event_id`, `created_at`), never the business payload, because `saas.job` is readable by every module role of the tenant (X-02 plumbing grant) and payloads such as `people.SalaryChanged` hold amounts (X-01). The delivery worker reads the event through the relay connection and runs the consumer handler in the consumer module's own transaction, role and inbox.
   - **Subscriptions** are declared in code (`@EventConsumer(eventType, consumer)` in each module) and compiled into a registry at startup; adding a consumer needs no infrastructure change. A new consumer back-fills by re-sync from the owner API.
   - **Failures:** exponential retry (1 s … 10 min, 10 attempts) by the job framework, then `DEAD` = the Phase-1 dead-letter state, with the operator replay dashboard (NFR-REC-005). A `DEAD` delivery must be replayed within the 7-day outbox window, otherwise the consumer re-syncs.
   - **Platform-cluster events** (`saas.LegalParameterPackPublished`, `core.MasterDataReleased`) are enqueued by the relay on every pool and dedicated cluster with the sentinel tenant.
   - **SLO:** relay lag p95 < 5 s, delivery lag p95 < 10 s; both on the operator dashboard.
4. **Phase 2 — Apache Kafka 4.x (KRaft) on Strimzi (or provider-managed) with Apicurio Registry**, introduced when the first of these triggers is met: (a) the live time-device connector / `hrm-ingest` (X-05 500 punches/s), (b) public webhooks or any external event consumer, (c) sustained relay or delivery lag above the SLO, or `events` queue volume above ~2 M jobs/day per cluster **[measured in SP-2]**, (d) a consumer that needs replay beyond 7 days. Kafka design as in rev. 1: one topic per producer module and major version (`people.events.v1`), key `tenant_id:aggregate_id`, RF 3, `min.insync.replicas=2`, retention 14 days, `att.punch.raw`, DLT per consumer group, MirrorMaker 2 for DR. The switch replaces only the relay's transport step (`EventTransport` port: `JobQueueTransport` → `KafkaTransport`); outbox, envelope, inbox and consumer handlers do not change, and both transports can run side by side per event type during the cut-over.
5. **Envelope** as in gap §6, plus `causation_id` and the `initial_load` flag (opening-balance import, ADR-0037); CloudEvents-compatible names (Kafka headers in Phase 2, `headers jsonb` in the outbox).
6. **Schemas:** JSON Schema per event in `shared/contracts`; backward compatibility and consumer-driven contract tests are enforced **in CI** in Phase 1. Apicurio Registry arrives with Kafka in Phase 2 (same schemas, registered from the repository).
7. **Consumers:** inbox dedupe by `(consumer, event_id)`, idempotent handlers, version checks against aggregate versions. Consumers must not assume arrival order equals effective-date order (overview §7.2).
8. **Payload minimisation:** sensitive values are not carried except to the single authorised consumer (e.g. `people.SalaryChanged` → Payroll); in Phase 1 the payload never leaves the producer's outbox row except into that consumer's handler; in Phase 2 the topic is ACL-restricted.
9. Sync in-process APIs remain the default for queries along the allowed dependency direction (ADR-0001).

## Alternatives considered

- **Keep Kafka in Phase 1 (rev. 1).** Removes a later migration step, but pays the full operating cost for a load the pilot does not have; the migration step is small because the outbox already decouples producers from the transport. Rejected for Phase 1.
- **Spring Modulith event publication registry (JPA) with in-process listeners.** Close in spirit, but its registry is one table per application, not per module schema, and it has no tenant fair share, ordered lanes or operator replay; we would rebuild those. Rejected; Modulith is still used for boundary verification.
- **PostgreSQL LISTEN/NOTIFY as the bus.** Not durable, does not work through PgBouncer transaction mode; used at most as a wake-up hint for the workers.
- **RabbitMQ in Phase 1.** Another broker to operate, no replay, no CDC story; no gain over the job queue at pilot volume.
- **Debezium Outbox Event Router.** Puts Kafka Connect on the business path; not before Phase 2.

## Consequences

- + Phase 1 has no message broker to operate, secure, mirror or upgrade; DR is the database's DR (outbox and job rows replicate with the cluster).
- + Delivery, retries, dead letters, fairness and monitoring reuse the job framework (ADR-0011) that is built anyway.
- + Consumer code is transport-neutral, so Kafka in Phase 2 is a relay change plus infrastructure.
- − The `events` queue adds write load and bloat to `saas.job` (one row per consumer per event): mitigated by batch claims, partial indexes, `fillfactor 80` and daily purge of finished jobs; measured in SP-2.
- − Replay window is 7 days (outbox retention) instead of Kafka's 14; older gaps are closed by re-sync jobs.
- − Phase-2 work item: Kafka introduction (infrastructure, `KafkaTransport`, Apicurio, DR mirroring, runbooks) ≈ 3–4 developer-weeks plus ops.
- − Eventual consistency between modules must still be visible in UX (pending states, readiness checklists).
- − Catalogue governance unchanged: the Integration analyst owns names; v1.1 adds `att.AttendancePeriodHoldGranted/Released`, `core.ReorgPackageStatusChanged`, `people.OnboardingReadinessChanged` (X-06); `people.PrimaryEmploymentChanged` is proposed for R2-16 (DESIGN.md §7.3).
