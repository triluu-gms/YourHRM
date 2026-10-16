# ADR-0021: Database roles and privilege model

- **Status:** Accepted (2026-10-16, X-02 — with the read-only reference-data exception listed below)
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect, Security lead
- **Related:** Architecture brief B3 (modules never touch each other's tables); SA overview §4 "no cross-schema SQL"; BR-SAAS-033/036; NFR-SAAS-008; ADR-0017 (OpenBao dynamic credentials); ADR-0020, ADR-0022; [DESIGN.md](../../../database/postgresql/DESIGN.md) §2.3, §3.4; DDL `000_extensions_roles.sql`

## Context

The modular monolith must not let module X read module Y's tables. Operators must have no default access to tenant data (BR-SAAS-033). Break-glass access needs two approvers and a 4-hour limit (BR-SAAS-036). Migrations need to own objects and run cross-tenant backfills. The outbox relay, audit sealer and reporting CDC need narrowly scoped cross-tenant access.

## Decision

| Role | Login | BYPASSRLS | Purpose / grants |
|---|---|---|---|
| `yhrm_owner` | no | yes | Owns every object; used via `SET ROLE` by the migrator only |
| `yhrm_migrator` | yes | no | Flyway in CI/CD; member of `yhrm_owner` |
| `yhrm_app` | no | **no** | Group role targeted by all RLS policies |
| `yhrm_app_{saas,core,hr,att,pay,rec}` | yes | no | One per module, member of `yhrm_app`. DML on **its own schema only**; `INSERT` on `audit.audit_event`; `SELECT` on Core **global** reference tables; `INSERT` on `saas.job` |
| `yhrm_platform` | yes | no | Operator back-office: platform tables in `saas`, **no grant on module schemas** |
| `yhrm_relay` | yes | no | Outbox polling relay: `SELECT`, `UPDATE (published_at)` on every `outbox_event` via `USING (true)` policies |
| `yhrm_cdc` | yes | no | `REPLICATION`; Debezium on a standby for the reporting store only |
| `yhrm_audit_sealer` | yes | no | `UPDATE` on audit seal columns only |
| `yhrm_reporting_ro` | yes | no | RLS-bound read of masking views for real-time statutory reports |
| `yhrm_ops_ro` | yes | no | `pg_monitor`; no table data |
| `yhrm_breakglass` | no | yes | Granted to a named DBA for ≤ 4 h on two-person approval; pgaudit logs all statements |

- Passwords are never in DDL. Login roles get dynamic credentials from OpenBao (ADR-0017).
- Each module uses its own Hikari pool and login role, so the DB enforces module boundaries even inside one JVM.
- `PUBLIC` loses `CREATE` on `public`; procedures used by migrations are not executable by `PUBLIC`.
- Cross-tenant work that apps need (job claiming) goes through **`SECURITY DEFINER` functions** owned by `yhrm_owner` with a fixed `search_path` (for example `saas.claim_jobs`), not through broad policies.

### Cross-schema exception list (X-02, accepted 2026-10-16)

A module app role has **no grant on another module's schema**, with exactly these exceptions. Anything not listed is a build failure.

| Schema.object | Privilege | Granted to | Why |
|---|---|---|---|
| `core.country`, `core.currency`, `core.admin_unit`, `core.admin_unit_mapping`, `core.bank`, `core.bank_branch`, `core.code_list` | `SELECT` | `yhrm_app` (all modules) | Pure global reference data (X-02), immutable between platform releases |
| `core.code_value`, `core.v_code_value_effective`, `core.exchange_rate` | `SELECT` | `yhrm_app` | Global rows + the tenant's own reference overrides (RLS: own + sentinel rows); read-only reference data (X-02) |
| `saas.tenant` | `SELECT` | `yhrm_app` | The tenant's own row only (policy `tenant_self_read`; locale, time zone, state) — SaaS global reference (X-02) |
| `saas.job` | `SELECT`, `INSERT` | `yhrm_app` | Platform plumbing, not a data read: transactional job enqueue (ADR-0011) |
| `audit.audit_event` | `INSERT` | `yhrm_app` | Platform plumbing: append-only audit in the business transaction (ADR-0027) |

Not in the list (and therefore forbidden): any `UPDATE`/`DELETE` across schemas, any read of another module's tenant data, salary or payroll amounts, and the legal-parameter tables (`saas.legal_param_*` are read by other modules through the Legal Parameter Service API only).

**Guards (both release-blocking):**
1. **DB test:** `SELECT count(*) FROM sys.cross_schema_grant_violations()` must return 0 after migrations (function in `002_core.sql`; it reads `pg_class.relacl` and compares with the allow-list above).
2. **ArchUnit / jOOQ rule:** SQL or generated jOOQ classes of module X may reference schema Y only for the objects above; the rule's allow-list is generated from the same list.

Adding an entry needs an amendment of this ADR and of ADR-0035.

## Alternatives considered

| Option | Why not |
|---|---|
| One application role for the whole monolith | The module boundary would be by convention only, and a single SQL injection would expose every schema. |
| Per-module **schemas** but a shared role | Same as above. |
| Give operators read-only DB access with RLS | Bypasses support-grant scoping and masking in the application (BR-SAAS-034); operators work through the app. |
| `BYPASSRLS` for the relay/sealer | Over-privileged; narrow policies achieve the same. |

## Consequences

- \+ Module boundaries and least privilege are enforced by grants and testable in CI.
- − There are six connection pools per app instance. PgBouncer keeps server connections bounded (`default_pool_size` per db/user pair).
- − In-process cross-module calls never share a DB transaction, which is intended (consistency via outbox and sagas, ADR-0019).
- − Exception: module roles read Core/SaaS global reference tables directly. Accepted as X-02 (2026-10-16) on the condition that the exception list above is explicit and guarded by the DB test and the ArchUnit rule.
