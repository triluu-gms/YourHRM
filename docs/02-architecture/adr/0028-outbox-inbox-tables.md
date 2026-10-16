# ADR-0028: Transactional outbox and inbox tables per module

- **Status:** Proposed
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect (ADR-0004 owns the relay and Kafka)
- **Related:** Architecture brief B5; gap-analysis §6 (envelope); NFR-PPL-002, NFR-CORE-004, NFR-REC-005; ADR-0004, ADR-0019; [DESIGN.md](../../../database/postgresql/DESIGN.md) §9.1; DDL `sys.create_outbox_inbox()`

## Context

Every committed business change must produce its event (no dual writes), delivered at least once and consumed idempotently. ADR-0004 chose an in-app polling relay to Kafka that keeps published rows for 7 days. Outbox tables are among the hottest in the system: about two rows per business write, inserted, updated once, then discarded.

## Decision

1. Every module schema has `<schema>.outbox_event` and `<schema>.inbox_message`, created by one template procedure so they are identical.
2. **Outbox columns** carry the gap §6 envelope: `id` (= `event_id`, UUIDv7), `tenant_id`, `legal_entity_id`, `aggregate_type`, `aggregate_id`, `event_type` (checked against `<module>.<Aggregate><Verb>`), `schema_version`, `occurred_at`, `effective_date`, `correlation_id`, `causation_id`, `is_retro`, `person_id`, `employment_id`, `payload jsonb`, `headers jsonb` (including the `initial_load` flag, ADR-0037), `created_at` and `published_at`.
3. **Daily range partitions** on `created_at`; partitions older than 7 days are **dropped**. There is no `DELETE` and no vacuum churn. A partial index on unpublished rows serves the relay's `FOR UPDATE SKIP LOCKED` scan.
4. **Inbox:** `(consumer, event_id)` dedupe with status, attempts and last error. Monthly partitions kept 90 days. Because a partitioned PK must contain the partition key, the consumer template takes `pg_advisory_xact_lock(hash(consumer, event_id))` and checks for existence before applying.
5. RLS applies to app roles. The relay has a dedicated read policy and `UPDATE (published_at)` only.
6. Payload minimisation: ids and changed field names; sensitive values only to a single authorised consumer on ACL-restricted topics (ADR-0004 §7). Never P3 values.

## Alternatives considered

| Option | Why not |
|---|---|
| One shared outbox table for all modules | Cross-module write grants; one module's backlog blocks the others; breaks schema ownership. |
| Debezium outbox router on the business path | ADR-0004 keeps CDC for reporting only. The table design would still work with it. |
| Delete published rows | Heavy vacuum load at peaks; partition drop is free. |
| Inbox without partitions | Table grows to hundreds of millions of rows; purging by `DELETE` costs as much as above. |

## Consequences

- \+ Atomic state change plus event; cheap cleanup; identical structure everywhere.
- − Inbox uniqueness relies on the consumer template (advisory lock plus check) rather than a pure PK.
- − Partition pre-creation is an operational dependency (pg_partman or `sys.create_range_partitions` via the scheduler); a missing partition fails the business transaction loudly, which is intended.
