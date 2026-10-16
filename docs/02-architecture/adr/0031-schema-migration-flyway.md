# ADR-0031: Schema migration tooling — Flyway, forward-only expand/contract

- **Status:** Accepted (2026-10-16, Flyway Community licence)
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect, DevOps
- **Related:** NFR-SAAS-014 (zero-downtime, rollback ≤ 15 min), NFR-CORE-013; ADR-0002 (Flyway per schema), ADR-0036 (fleet of databases); [DESIGN.md](../../../database/postgresql/DESIGN.md) §12

## Context

The product is PostgreSQL-only and relies on features that ORMs and abstract changelogs model poorly: RLS policies, exclusion constraints, partitioning, generated columns, `SECURITY DEFINER` functions. Releases ship at least every two weeks with no planned downtime, to shared pool clusters and to every dedicated database. Modules own their schemas and should migrate independently.

## Decision

1. **Flyway** (Community edition, 11.x), SQL-first. One location and **one history table per schema** (`<schema>.flyway_schema_history`). Run order: `sys` → `saas` → `core`/`audit` → `hr` → `att` → `pay` → `rec`.
2. Naming: `V<yyyymmddHHMM>__<desc>.sql` (timestamped to avoid merge conflicts) and `R__<object>.sql` for views, functions and policies. The `database/postgresql/ddl/*.sql` files become each schema's `V1__baseline`.
3. Execution as `yhrm_migrator` with `initSql = SET ROLE yhrm_owner; SET lock_timeout = '3s'`. CI runs the **squawk** linter.
4. **Forward-only expand/contract:**
   * N: add nullable columns, tables, dual write;
   * a backfill job runs per tenant (fair-share, ADR-0011);
   * N+1: switch reads;
   * N+2: drop.

   Rules: indexes `CONCURRENTLY` (`executeInTransaction=false`), constraints `NOT VALID` then `VALIDATE`, no renames, no `ALTER TYPE` on large tables, `NOT NULL` through a validated `CHECK`.
5. **Fleet rollout** in rings (internal → pilot → shared pools → dedicated), driven by a migration orchestrator. App startup checks the required schema version against the history table.
6. **Tenant seeding is not a migration:** provisioning calls module seed APIs. Global master data and legal packs ship through the release job.
7. Tests: Testcontainers runs all migrations from zero and from the previous release; the RLS isolation suite and the catalogue check are release-blocking.

## Alternatives considered

| Option | Why not |
|---|---|
| Liquibase | Changelog abstraction adds little for a single-database product; PostgreSQL-specific DDL ends up in raw `sql` change sets anyway; rollback blocks are rarely correct for data changes. Valid, but no advantage here. |
| Hibernate `ddl-auto` | Not acceptable for production; no control over locks or ordering. |
| Single history table for all schemas | Couples module release trains; one module's failed migration blocks the others. |
| Down migrations | Data-destructive and untestable at scale. Rollback = previous app version on an expanded schema. |

## Consequences

- \+ Plain SQL is reviewable by DBAs and uses every PostgreSQL feature directly.
- − The Flyway Teams/Enterprise features (undo, dry-run reports) are not used. The Community edition is sufficient and was accepted on 2026-10-16 (decision-log "Flyway", D-08).
- − Expand/contract stretches a breaking change over three releases. Teams must track the contract steps (lint rule plus a backlog item).
