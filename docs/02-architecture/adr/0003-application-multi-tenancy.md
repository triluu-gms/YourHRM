# ADR-0003: Application-tier multi-tenancy — resolution, context propagation, routing, quotas

- **Status:** Accepted (2026-10-16, brief baseline 4 — shared pool + RLS + app guard, dedicated tier, VN hosting; X-07 `saas` schema). Decision 5 updated 2026-10-16 to the baseline set approved in **R2-18** (accepted)
- **Date:** 2026-10-16
- **Related:** Architecture brief B4; gap-analysis GAP-016, GAP-060, §9 A-1/A-7; [architecture-overview](../architecture-overview.md) §5; data-level isolation in the data architecture (DESIGN.md, ADR 0020–0039)

## Context

The baseline is a shared pool (`tenant_id` + PostgreSQL RLS + application guard) with an Enterprise dedicated database selected by tenant routing. NFR-SAAS-005 requires ≥ 500 tenants, 200k employees, 5× growth and a noisy-tenant cap of 20% of pool capacity. Tenant context must survive HTTP, async execution, jobs, events, caches and object storage. Module subscriptions are sold separately, yet modules depend on each other's reference capabilities at runtime (GAP-060).

## Decision

1. **Resolution:** the `tid` claim of the validated access token is the only authority for user traffic; the host name (`{slug}.yourhrm.vn`, custom domain P2/P3) is a login hint and must match the claim. Device traffic (Phase 2, live connector) resolves tenant from the device credential (mTLS); in Phase 1 device data arrives only as imported files under a user session. Jobs and events carry `tenant_id` in the persisted record. Operators act on a tenant only with a time-boxed support grant.
2. **Context:** an immutable `TenantContext` bound with `ScopedValue`, propagated to virtual threads/executors with Micrometer context propagation; code without a context fails fast. Every transaction executes `SET LOCAL app.tenant_id` (and user / support grant) for RLS; a Hibernate filter / jOOQ listener adds `tenant_id` predicates as the second line of defence. Cache keys and object keys are tenant-prefixed by wrappers that refuse unprefixed tenant data.
3. **Routing:** `saas.tenant_placement` maps tenant → cluster (pool-NN or dedicated). An `AbstractRoutingDataSource` selects the cluster per transaction; a tenant never spans clusters. Pool clusters hold ~100k employees each **[spike]**. Moves between clusters use filtered logical replication outside days 25–5.
4. **Entitlements & flags:** entitlements (`saas`) gate features and UIs via an `@Entitled` aspect; OpenFeature with an in-house provider serves release, ops and calc-behaviour flags by tenant/ring.
5. **Baseline-always-on capabilities (R2-18, accepted 2026-10-16; GAP-060):** available to every tenant whatever modules it buys — **Core** (org, legal entities, users/roles, workflow, notifications, documents, import engine), **People basics** (person, employment, assignment, salary record; read APIs + import + master-record UI), **legal parameter packs** (LPS API), **pay scales/bands** (gap §5 #7; API + catalogue admin), and **Excel attendance import** producing a standard `CLOSED` Attendance Result so the period-close protocol is unchanged for Payroll-without-T&A tenants.
   - **Entitlement design:** the baseline set is the platform table `saas.baseline_capability` (feature keys `baseline.core`, `baseline.people.master`, `baseline.legal_params`, `baseline.pay.scales_bands`, `baseline.att.excel_import`), shipped by the release job and changed only by a product decision. The entitlement resolver writes a `source = BASELINE` entitlement row per tenant and key; the DB allows such a row only as an enabled tenant-wide grant, and the one-row-per-feature-per-period exclusion prevents a plan or override from disabling it (DESIGN.md §7.1). `` checks and the `/me/capabilities` response treat these keys like any other, so no code path special-cases "baseline".
   - **Runtime reference services** that other modules call are never gated by entitlements (only UIs and features are): the national holiday calendar and default schedule pattern through the T&A calendar API / Core `WorkingCalendarProvider` SPI, and the gross↔net simulator API used by People for NET salaries. They are exposed API-only to tenants without T&A or Payroll. (They are not in the R2-18 list as sellable capabilities; PM to confirm they need no separate listing.)
   - The plan catalogue publishes the dependency matrix (e.g. Payroll requires People master + attendance import).
6. **Noisy-neighbour controls:** per-tenant API token buckets (Bucket4j/Valkey), fair-share job queue with a 20% slot cap (ADR-0011), per-role statement timeouts, report concurrency limits, tenant fair share on the `events` delivery queue (Kafka keys by tenant from Phase 2, ADR-0004 rev. 2), per-tenant notification rates.

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
