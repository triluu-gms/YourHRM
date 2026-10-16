# ADR-0035: Global vs tenant-overridable reference data

- **Status:** Accepted (2026-10-16, X-02 — read-only exception for global reference data, listed in ADR-0021)
- **Date:** 2026-10-16
- **Deciders:** Data architect, Core lead
- **Related:** gap-analysis §5 #28 (Core owns global master data), §9 A-1 (RLS exemptions); NFR-CORE-001; ADR-0020, ADR-0022, ADR-0025, ADR-0036; [DESIGN.md](../../../database/postgresql/DESIGN.md) §3.5; DDL `core.code_value`, `core.v_code_value_effective`, `sys.enable_tenant_or_global_rls`

## Context

Some reference data is identical for all tenants and released by the platform: countries, currencies, admin units and their 2025 mapping, banks, and statutory code lists. Some lists are extendable or overridable per tenant (leave reasons, separation reasons, employee categories). Some catalogues are seeded per tenant from templates (roles, leave types, salary components). The model must keep RLS simple and FKs tenant-safe.

## Decision

Four classes:

| Class | `tenant_id` | RLS | Examples | Write path |
|---|---|---|---|---|
| **Pure global** | none | none; `SELECT` granted to app roles (Core tables); `saas.legal_param_*` only to the SaaS role, other modules use the Legal Parameter Service API | `core.country`, `currency`, `admin_unit`, `admin_unit_mapping`, `bank`, `bank_branch`, `code_list`; `saas.legal_param_*` | Platform release job (`core.MasterDataReleased`), replicated to all clusters |
| **Global + tenant override** | `NOT NULL`; global rows use the sentinel `00000000-…-0000` | read own + sentinel; write own only (`sys.enable_tenant_or_global_rls`) | `core.code_value`, `core.exchange_rate`, `saas.retention_policy` | Tenant admin for own rows; release job for sentinel rows |
| **Seeded from template** | `NOT NULL`, tenant rows only | standard | `core.role` (system roles), `att.leave_type`, `pay.salary_component`, holiday calendar copies | Provisioning seed APIs, with `template_version` kept for upgrades |
| **Tenant-owned** | `NOT NULL` | standard | everything else | Module APIs |

* Override resolution: `DISTINCT ON (list, code) … ORDER BY (tenant_id = sentinel)`, wrapped in `core.v_code_value_effective` (security-invoker view). Tenants may add values or deactivate them for themselves. Statutory lists (`is_statutory`) cannot be extended.
* Other modules reference global data **by code**, never by surrogate id (ADR-0022).
* **Direct read by other modules (X-02, accepted 2026-10-16):** module roles may `SELECT` the Core reference tables of the first two classes (and the tenant's own `saas.tenant` row). This is the only read exception to "no grant on another module's schema"; it is read-only and enumerated in ADR-0021, and is guarded by `sys.cross_schema_grant_violations()` (DB test) plus an ArchUnit rule. Seeded and tenant-owned catalogues are **not** covered: other modules read them through the owner's API or an event-fed projection.
* **No FK may point at a global + tenant table by `id`**: FK checks bypass RLS, so a tenant could reference another tenant's row. Such references use the code (validated in the app), or the catalogue is seeded per tenant instead.

## Alternatives considered

| Option | Why not |
|---|---|
| `tenant_id NULL` for global rows | NULL cannot be part of a PK; policies with `IS NULL` branches are error-prone. |
| Copy all reference data into every tenant | 500 copies of the admin-unit table; releases (such as the 2025 admin restructure) would need 500 updates. |
| Separate `ref` schema | Ownership is Core's (gap §5 #28); a separate schema adds a grant surface without benefit. |

## Consequences

- \+ One release updates every tenant; tenants still customise where allowed.
- − Upgrading seeded templates (new system role permissions, a new statutory salary component) needs a "template upgrade" job that respects tenant changes.
- − The sentinel tenant id must never be provisioned as a real tenant (CHECK on `saas.tenant`).
