# ADR-0003: Application-tier multi-tenancy — resolution, context propagation, routing, quotas

- **Status:** Accepted (2026-10-16, brief baseline 4 — shared pool + RLS + app guard, dedicated tier, VN hosting; X-07 `saas` schema)
- **Date:** 2026-10-16
- **Related:** Architecture brief B4; gap-analysis GAP-016, GAP-060, §9 A-1/A-7; [architecture-overview](../architecture-overview.md) §5; data-level isolation in the data architecture (DESIGN.md, ADR 0020–0039)

## Context

The baseline is a shared pool (`tenant_id` + PostgreSQL RLS + application guard) with an Enterprise dedicated database selected by tenant routing. NFR-SAAS-005 requires ≥ 500 tenants, 200k employees, 5× growth and a noisy-tenant cap of 20% of pool capacity. Tenant context must survive HTTP, async execution, jobs, events, caches and object storage. Module subscriptions are sold separately, yet modules depend on each other's reference capabilities at runtime (GAP-060).

## Decision

1. **Resolution:** the `tid` claim of the validated access token is the only authority for user traffic; the host name (`{slug}.yourhrm.vn`, custom domain P2/P3) is a login hint and must match the claim. Device traffic resolves tenant from the device credential (mTLS). Jobs and events carry `tenant_id` in the persisted record. Operators act on a tenant only with a time-boxed support grant.
2. **Context:** an immutable `TenantContext` bound with `ScopedValue`, propagated to virtual threads/executors with Micrometer context propagation; code without a context fails fast. Every transaction executes `SET LOCAL app.tenant_id` (and user / support grant) for RLS; a Hibernate filter / jOOQ listener adds `tenant_id` predicates as the second line of defence. Cache keys and object keys are tenant-prefixed by wrappers that refuse unprefixed tenant data.
3. **Routing:** `saas.tenant_placement` maps tenant → cluster (pool-NN or dedicated). An `AbstractRoutingDataSource` selects the cluster per transaction; a tenant never spans clusters. Pool clusters hold ~100k employees each **[spike]**. Moves between clusters use filtered logical replication outside days 25–5.
4. **Entitlements & flags:** entitlements (`saas`) gate features and UIs via an `@Entitled` aspect; OpenFeature with an in-house provider serves release, ops and calc-behaviour flags by tenant/ring.
5. **Baseline-always-on capabilities:** People employee master, T&A holiday calendar and schedule patterns, Payroll component catalogue/pay scales/gross↔net simulator, legal parameter packs, and an attendance import producing a standard Attendance Result are available regardless of subscription.
6. **Noisy-neighbour controls:** per-tenant API token buckets (Bucket4j/Valkey), fair-share job queue with a 20% slot cap (ADR-0011), per-role statement timeouts, report concurrency limits, Kafka keys by tenant, per-tenant notification rates.

## Alternatives considered

- **Schema-per-tenant** in the pool: rejected — 500+ schemas × 6 modules multiplies migrations and catalog bloat; RLS gives equal isolation with shared plans.
- **Database-per-tenant for everyone:** rejected for cost and operations at SME scale; kept for Enterprise.
- **Tenant from request header/path:** rejected as the authority (spoofable); used only as a hint.
- **ThreadLocal context:** rejected in favour of `ScopedValue`, which is safe with virtual threads and cannot leak across pooled threads.

## Consequences

- + Defence in depth (token → app guard → RLS); uniform behaviour for pool and dedicated tenants.
- + Fairness is measurable and enforceable.
- − Every data access path must run inside a transaction with context; raw JDBC outside the framework is banned by ArchUnit.
- − Routing directory is critical: cached in two levels and replicated per cluster.
- − Baseline capabilities mean some module code runs for tenants who did not buy the module; pricing must reflect this (PM).
