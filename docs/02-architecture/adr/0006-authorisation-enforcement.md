# ADR-0006: Authorisation enforcement — cached snapshot, scope predicates, field masking

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** gap-analysis §9 A-5, GAP-010; NFR-CORE-002 (≤ 20 ms p95), NFR-PPL-006; [architecture-overview](../architecture-overview.md) §6.3; scope-friendly keys and closure tables in the data architecture (DESIGN.md)

## Context

Core owns RBAC with data scopes (self, direct/all reports, org subtree, legal entity, location, custom) and field masking (salary, bank, CCCD, health). Every module, every report and every export must apply the same rules. The overhead target is ≤ 20 ms p95 per request. Tenants have up to 10,000 employees and 2,000 org units.

## Decision

1. Core computes an **effective authorisation snapshot** per user account: permission set, list of scope grants (per permission group), field profile. Cached in Caffeine (60 s) and Valkey; invalidated by `core.RoleAssignmentChanged`, `people.AssignmentChanged`, `core.OrgUnitChanged`, `core.ReorgActivated`.
2. **Coarse checks** with `@RequiresPermission("pay.run.approve")` on application services (Spring method security, evaluated against the snapshot).
3. **Row-level data scope** via a `ScopePredicate` SPI in `shared/auth`: Core translates scope grants into index-friendly predicates (materialised org path / closure keys, employment id sets for small scopes). Module repositories must apply the predicate for every list/query of person-related data; ArchUnit rules flag repository methods on scoped aggregates without a predicate parameter.
4. **Field masking** at serialisation: `@Sensitive(category)` on DTO fields; a Jackson module masks values not allowed by the field profile; masked values never leave the server. Exports and reports use the same profile.
5. **Reporting store** receives a materialised `report_scope(user_account_id, employment_id)` table maintained from snapshots (ADR-0012).
6. **Support access:** the support grant in `TenantContext` applies a "mask all sensitive" profile unless the tenant grant allows a category.
7. Sensitive reads and denials are audited asynchronously via the audit outbox.

## Alternatives considered

- **Push data scope into PostgreSQL RLS:** would need per-user session settings with large id lists; makes plans unstable; RLS is reserved for tenant isolation.
- **External policy engine (OPA/Cedar) per request:** flexible but adds network hops and a second policy language; scope still needs SQL translation. Reconsider for P3 ABAC rules.
- **Masking in the frontend:** rejected; data would still leave the server.

## Consequences

- + Cache hit cost < 1 ms; cold compute budget ≤ 20 ms (measured in SP-2).
- + One rule set for screens, APIs, exports and BI.
- − Developers must use the predicate SPI consistently; enforced by tests (scope test suite per endpoint).
- − Cache invalidation must be reliable; a 60 s TTL bounds staleness.
