# ADR-0022: Schema per module, no cross-schema foreign keys, event-fed projections

- **Status:** Accepted (2026-10-16, D-01; X-02 read-only reference-data exception per ADR-0021)
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect, PM
- **Related:** Architecture brief B1/B2/B3; gap-analysis §5 (single owner), §9 A-1; ADR-0001 (modular monolith), ADR-0004 (events); [DESIGN.md](../../../database/postgresql/DESIGN.md) §2

## Context

The schema set is fixed: `saas`, `core`, `hr`, `att`, `pay`, `rec`. Each datum has a single owner (gap §5). Payroll workers and punch ingestion must be able to scale out separately and may later move to their own databases. A dedicated tenant DB and a tenant moving between clusters must carry the same model. The question is how rows in one module reference rows in another.

## Decision

1. Each module owns exactly one schema (plus `audit`, owned by Core, and `sys`, a table-less shared kernel of functions and domains).
2. **Inside a schema**, FKs are mandatory and composite: `(tenant_id, x_id) → x(tenant_id, id)`.
3. **Across schemas there are no FKs**, including no FK to `saas.tenant`. References to another module's entity are *soft*: `<entity>_id uuid` with a comment `-> core.position (soft)`. They are validated through the owner's API at write time and checked nightly by an orphan-reference report.
4. **Global master data is referenced by stable code** (`country_code`, `bank_code`, `admin_unit_code`, `currency_code`), not by surrogate id.
5. A module that needs bulk access to another module's data keeps an **event-fed projection** named `ext_<owner>_<entity>` in its own schema. Each projection row stores `source_event_id`/`source_version`, has a re-sync job from the owner API, and has a checksum compare.
6. Denormalised scope keys (`legal_entity_id`, `org_unit_id`) are copied onto employee-scoped rows so each module can filter by data scope without joining Core.

## Alternatives considered

| Option | Why not |
|---|---|
| Cross-schema FKs to `core` only | Couples migration order and deletion order, breaks the moment a module moves to another database, and needs `REFERENCES` grants across modules. |
| FK from every table to `saas.tenant` | `saas` lives in the platform cluster (ADR-0036). Every insert would also take a `FOR KEY SHARE` lock on one hot row per tenant. |
| Shared "kernel" tables read by all modules (person, org) | Recreates the shared-database monolith that the brief forbids; ownership becomes ambiguous. |
| Database views across schemas | Hidden coupling; breaks the role model (ADR-0021). |

## Consequences

- \+ Modules can be migrated, scaled and later extracted independently. The DB enforces the module map.
- \+ Composite keys make cross-tenant references impossible inside a schema.
- − Integrity across modules is eventual. It is mitigated by owner validation at write, versioned projections, nightly orphan checks, and the period-close version handshake (gap §7, ADR-0019).
- − Some data is duplicated (projections). This is acceptable: projections are rebuildable caches, not masters.
- − Reports joining modules run on the reporting store (ADR-0032), not on OLTP.
