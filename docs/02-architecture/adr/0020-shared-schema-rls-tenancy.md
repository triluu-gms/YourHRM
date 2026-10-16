# ADR-0020: Shared-schema multi-tenancy with RLS and a transaction-local tenant context

- **Status:** Accepted (2026-10-16, brief baseline 4; X-07 `saas` schema approved; SP-2 still validates RLS performance)
- **Date:** 2026-10-16
- **Deciders:** Data architect (owner), Solution architect (consumes), PM
- **Related:** Architecture brief B4; SaaS BRD Q2; NFR-CORE-001, NFR-PAY-005, NFR-ATT-010, NFR-REC-001, NFR-SAAS-005; ADR-0003 (application tenancy), ADR-0021, ADR-0035, ADR-0036; [DESIGN.md](../../../database/postgresql/DESIGN.md) §3; DDL `000_extensions_roles.sql` (`sys.enable_tenant_rls`)

## Context

The PM baseline fixes a shared pool with `tenant_id` on every row, PostgreSQL RLS and an application guard, plus a dedicated-database tier. SaaS Q2 still asked whether schema-per-tenant would be better. The shared tier must hold ≥ 500 tenants and 200k employees at launch and scale 5× (NFR-SAAS-005). Every module requires isolation that is "tested automatically" and holds in background jobs and exports. Connections go through PgBouncer in transaction mode, so session state cannot be trusted.

## Decision

1. **Shared schema, shared tables.** Every tenant-owned table has `tenant_id uuid NOT NULL`, which leads its PK `(tenant_id, id)` and every secondary index.
2. **RLS is enabled and forced** on every tenant-owned table, with one standard policy applied by `sys.enable_tenant_rls()`:
   `FOR ALL TO yhrm_app USING (tenant_id = (SELECT sys.current_tenant_id())) WITH CHECK (same)`.
   The scalar subquery is evaluated once per statement (InitPlan), so the predicate stays index-friendly.
3. **The context is transaction-local.** The first statement of every transaction is `set_config('app.tenant_id', …, true)` (equivalent to `SET LOCAL`), together with `app.user_id`, `app.request_id`, `app.purpose`, `app.support_grant_id` and `app.mask_level`. `sys.current_tenant_id()` returns `NULLIF(current_setting('app.tenant_id', true), '')::uuid`.
4. **Fail closed.** With no context the predicate is NULL, so reads return no rows and writes are rejected. Every DB access runs inside a transaction, and code paths without a tenant (cron dispatchers) never touch tenant tables.
5. **RLS is for tenant isolation only.** User data scope is enforced by Core's scope predicates in the application (ADR-0006), against denormalised `legal_entity_id`/`org_unit_id` columns.
6. **Technical roles** (outbox relay, audit sealer) get explicit `USING (true)` policies for the operations they need, never `BYPASSRLS`. Only `yhrm_owner` (migrations) and the time-boxed break-glass role bypass RLS.
7. **Release-blocking test:** for every table in a module schema, a two-tenant fixture asserts zero cross-tenant visibility and rejected cross-tenant writes per app role. A catalogue check fails the build when a table has no `tenant_id` + RLS and is not allow-listed as global.

## Alternatives considered

| Option | Why not |
|---|---|
| Schema-per-tenant | 500+ tenants × ~400 tables means about 200k relations: catalogue bloat, slow migrations (every release × 500 schemas), and no cross-tenant platform queries (billing headcount, legal-pack impact). Per-tenant restore is better, but it is achieved through logical export (DESIGN §3.7). |
| Database-per-tenant for everyone | Connection and ops cost multiplies. Kept only for the Enterprise tier (ADR-0036). |
| Application-only filtering (Hibernate filter / jOOQ listener) | One forgotten predicate leaks data. It is kept as the **second** line of defence (ADR-0003), not the only one. |
| Session-level `SET app.tenant_id` | Unsafe with PgBouncer transaction pooling: the context leaks to the next client. |
| `current_user` per tenant (DB role per tenant) | Role explosion, no pooling across tenants, grants per tenant. |

## Consequences

- \+ Isolation is enforced by the database, including ad-hoc SQL by support tools and bugs in jobs.
- \+ One schema to migrate. The dedicated tier uses the same DDL.
- − Every query pays a policy predicate; SP-2 must show < 10 % p95 overhead **[spike]**.
- − Developers must always run inside `@Transactional` (read-only included) with the context set. ArchUnit rules and the `sys.assert_tenant()` check enforce this.
- − Partitions are not protected by the parent's policies when accessed directly, so app roles get grants on parent tables only.
- − Bulk cross-tenant maintenance (backfills) runs as the migration owner and must be reviewed as privileged code.
