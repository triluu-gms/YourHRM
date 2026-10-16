# ADR-0036: Platform cluster, pool clusters and the dedicated-database tier with an identical schema

- **Status:** Proposed
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect, PM (commercial tiers)
- **Related:** Architecture brief B4; NFR-SAAS-001/005/006/019; ADR-0003 (`saas.tenant_placement`, ~100k employees per pool cluster), ADR-0011 (job queue placement), ADR-0017, ADR-0020, ADR-0026; [DESIGN.md](../../../database/postgresql/DESIGN.md) §3.1, §3.7

## Context

The SA overview places the `saas` schema in a **platform cluster**, with pool clusters for shared tenants and a dedicated database for Enterprise tenants, selected by `saas.tenant_placement`. Enterprise customers expect physical separation, their own restore and a 99.9 % SLA. Tenants may move between clusters. ADR-0011 left the placement of job queue tables to the data architect.

## Decision

1. **One DDL everywhere.** Every database (platform, pool, dedicated) is created from the same migrations, with all schemas, `tenant_id` and RLS. A dedicated DB is just a cluster with one tenant.
2. **Placement classes** decide where each table is authoritative:
   * **PLATFORM** (platform cluster only): plans, subscriptions, invoices, support grants, keyring (`tenant_key`, `subject_key`), consent/DSR registries, legal-pack authoring, platform audit.
   * **REPLICATED** (authored in the platform cluster, read-only logical-replication copies in every pool and dedicated cluster): `saas.tenant`, `tenant_placement`, `entitlement`, published `legal_param_*`, `retention_policy`, and Core global master data.
   * **LOCAL** (each cluster): all module schemas, `audit`, every outbox/inbox, and **`saas.job`**. The job queue is per cluster, so jobs are enqueued in the business transaction; claiming goes through `saas.claim_jobs()` with a fair-share per-tenant cap.
3. **Moves** (pool → pool, pool → dedicated) use the tenant export/import format or filtered logical replication by `tenant_id`, outside days 25–5. UUIDv7 ids make this conflict-free (ADR-0023).
4. **Per-tenant restore** for shared tenants = PITR of the cluster into a scratch instance, then tenant export → sandbox import. Dedicated tenants restore natively.
5. **Reporting:** dedicated tenants get their own reporting database; pool tenants share one per pool region (ADR-0032).

## Alternatives considered

| Option | Why not |
|---|---|
| Dedicated tier with a stripped schema (no `tenant_id`/RLS) | Two code paths and two migration sets; a moved tenant would need data rewriting. |
| `saas` in every pool cluster as a full master | Multi-master registry, billing split across clusters, and keyring copies in every backup. |
| Global job queue in the platform cluster | Enqueue would not be atomic with the business change (needs a 2PC or an outbox hop); cross-cluster latency on every job. |

## Consequences

- \+ One schema, one test suite, and portable tenants.
- \+ Pool backups never contain the keyring, which strengthens crypto-shredding (ADR-0026).
- − Logical replication of REPLICATED tables must be operated and monitored (lag alarms); release-job push is the fallback **[spike]**.
- − Global code-value rows replicated into clusters must not collide with tenant rows (the sentinel tenant prevents this).
- − Job workers connect to every cluster they serve; the fair-share cap is per cluster, not platform-wide.
