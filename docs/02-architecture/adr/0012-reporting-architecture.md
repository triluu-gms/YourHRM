# ADR-0012: Reporting — CDC to a PostgreSQL reporting store, Superset, statutory template framework

- **Status:** Accepted (2026-10-16, OD-5 — Superset for Phase 2). **Amendment 1 (2026-10-16, R2-01) — Accepted (R3-11):** decision 2 (feed of the reporting store) uses native PostgreSQL logical replication in Phase 1 instead of Debezium → Kafka
- **Date:** 2026-10-16
- **Related:** Architecture brief B2 (separate reporting store); gap-analysis GAP-010, §9 A-10; SaaS BRD §4.E; NFR-SAAS-003; reporting data model in the data architecture (DESIGN.md); [architecture-overview](../architecture-overview.md) §10

## Context

The SaaS platform owns the reporting framework, the separate reporting store and all exports; Core supplies the scope predicate and masking. MVP needs standard reports and statutory outputs; P2 adds a report designer, dashboards and HR analytics. Reporting must not degrade OLTP during payroll peaks, must isolate tenants including in BI, must respect data scopes and masking, and must follow deletions (DSR/retention). Data must stay in Vietnam.

## Decision

1. **Three families:** operational lists from OLTP read replicas through module APIs; **statutory outputs** from the owning module on approved, versioned data; **analytics** from the reporting store.
2. **Pipeline (Amendment 1, Proposed):** **Phase 1** — native PostgreSQL logical replication: one publication per module schema per OLTP cluster with **column lists** that exclude every P3 column, subscribed by the reporting DB into a landing schema `rpt_raw` (slot on a standby where PG 16+ standby decoding is available, otherwise on the primary with a bounded `max_slot_wal_keep_size`) → SQL transformation jobs (worker pool `reporting`) → **PostgreSQL 17 reporting store** `rpt` (star schema, SCD2, `tenant_id` + RLS on all tables). No Kafka or Kafka Connect in Phase 1 (ADR-0004 rev. 2). **Phase 2 option** — Debezium on a standby → Kafka `cdc.*` → loader, if streaming transformations or a columnar engine (SP-6) need it; the star schema does not change. Freshness target ≤ 15 min (normally ≤ 2 min).
3. **Scope in reporting:** a `report_scope(user_account_id, employment_id)` table maintained from Core authorisation snapshots; every report query and BI dataset joins it.
4. **BI (P2): Apache Superset**, self-hosted, never exposed directly; embedded with per-user guest tokens carrying RLS clauses; DB connection with RLS and per-query tenant setting.
5. **Sensitive data** stays encrypted/tokenised in the store except approved aggregate subject areas protected by permission and audit.
6. **Statutory template framework:** versioned form artifacts (XSD/XLSX template, mapping, validation, effective dates) published like legal packs; modules implement `StatutoryDataProvider` per form.
7. **Exports:** async, tenant-quota-limited, 7-day expiry, scope and masking applied.

## Alternatives considered

- **Query OLTP directly for analytics:** competes with payroll peaks; cross-schema joins violate module boundaries.
- **ClickHouse as the store:** superior for large aggregations; adds a second SQL dialect and weaker RLS model. Kept as P2 option behind SP-6.
- **Commercial BI (Power BI, Tableau, Looker):** cloud services outside VN or costly on-prem licences; embedding RLS harder to control.
- **Metabase OSS:** simpler but weaker RLS/embedding in the open-source edition.

## Consequences

- + OLTP protected; one SQL dialect; isolation enforced in the database even for BI.
- + Deletions propagate via CDC.
- − Logical replication does not carry DDL: every migration of a published table has an `rpt_raw` step (CI check), and a replication slot must be monitored (lag, retained WAL). Debezium/Kafka Connect only if the Phase-2 option is taken.
- − Reporting data is eventually consistent; screens that need current data use OLTP.
