# ADR-0004: Event backbone — transactional outbox, in-app relay, Apache Kafka, schema registry

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** Architecture brief B5; gap-analysis §6, §9 A-2, GAP-017/019/020/063; NFR-PPL-002, NFR-CORE-004, NFR-REC-005; [architecture-overview](../architecture-overview.md) §7

## Context

All six BRDs assume a transactional outbox and an event bus with at-least-once delivery and idempotent consumers. Event names drifted between producers and consumers; gap-analysis §6 is the canonical catalogue v1 with a common envelope. The same infrastructure should also serve the punch ingestion buffer (bursts of ~333 punches/s platform-wide) and CDC to the reporting store, and must support replay for new consumers and DR.

## Decision

1. **Outbox per module schema**, written in the business transaction (tables designed by the Data architect).
2. **In-app relay** in `hrm-worker`: polls each cluster's outbox with `FOR UPDATE SKIP LOCKED`, preserves per-aggregate order, publishes to Kafka, marks rows published; published rows are kept 7 days for re-publication after DR. Debezium is *not* used on the business path (only for reporting CDC, ADR-0012).
3. **Apache Kafka 4.x (KRaft)** on Strimzi: one topic per producer module and major version (`people.events.v1`), key `tenant_id:aggregate_id`, RF 3, `min.insync.replicas=2`, retention 14 days; dedicated `att.punch.raw` topic; DLT per consumer group.
4. **Envelope** as in gap §6, plus `causation_id` and the `initial_load` flag (set by the opening-balance import, ADR-0037; formerly `migration`); CloudEvents-compatible header names.
5. **Schemas:** JSON Schema in `shared/contracts`, registered in **Apicurio Registry** with backward-compatibility enforcement; breaking change ⇒ new topic version with dual publishing. Consumer-driven contract tests in CI for each catalogue event.
6. **Consumers:** inbox dedupe by `(consumer, event_id)`, idempotent handlers, version checks against aggregate versions, retries with backoff then DLT with an operator replay dashboard.
7. **Payload minimisation:** sensitive values are not carried except to the single authorised consumer on ACL-restricted topics (e.g. `people.SalaryChanged` → Payroll).
8. Sync in-process APIs remain the default for queries along the allowed dependency direction (ADR-0001).

## Alternatives considered

- **RabbitMQ:** simpler, but no log replay, weaker ordering semantics per key, and no natural CDC path; would need a second technology for reporting.
- **Debezium Outbox Event Router as relay:** removes polling but puts Kafka Connect on the critical business path and needs a connector per dedicated DB; reconsider if relay load becomes significant.
- **Spring Modulith in-memory events only:** insufficient for durability across pods and for external consumers.
- **PostgreSQL LISTEN/NOTIFY or queue tables only:** no replay, limited throughput for punches, no fan-out to reporting.

## Consequences

- + Durable, ordered, replayable integration fabric shared by business events, punches and CDC.
- + Business transactions never depend on Kafka availability (outbox absorbs outages).
- − Kafka must be operated (Strimzi, monitoring, upgrades); team training needed.
- − Eventual consistency between modules must be visible in UX (pending states, readiness checklists).
- − Catalogue governance: the Integration analyst owns names; architecture adds `att.AttendancePeriodHoldGranted/Released`, `core.ReorgPackageStatusChanged`, `people.OnboardingReadinessChanged` for acceptance.
