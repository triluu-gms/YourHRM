# ADR-0024: Effective dating and partial bi-temporality

- **Status:** Proposed
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect, People and Payroll leads
- **Related:** gap-analysis §9 A-3; NFR-PPL-001 ("as of X, as known on Y"), NFR-CORE-003 (as-of org tree), BR-PAY-072/076 (retro); ADR-0009, ADR-0025; [DESIGN.md](../../../database/postgresql/DESIGN.md) §5; DDL `sys.tg_bitemporal_guard`, `hr.assignment`, `hr.salary_record`

## Context

Almost every HR master is effective-dated. People must answer "as of date X, as known on date Y" for assignments, salary and contracts. Payroll must detect back-dated changes after a run (retro) and reproduce runs for 10 years. Core must return the org tree as of any date in ≤ 1 s. A single pattern is needed, with enforcement in the DB and a shared library in the app.

## Decision

1. **Pattern A (valid time)** for most masters: `valid_from date`, `valid_to date` (exclusive, NULL = open), a generated `valid_during daterange '[)'`, and an `EXCLUDE USING gist (tenant_id WITH =, <key> WITH =, valid_during WITH &&)` (`btree_gist`). The structure is an anchor table (identity) plus a version table (attributes). Corrections are in-place updates captured by the audit log.
2. **Pattern B (bi-temporal)** for `hr.assignment`, `hr.salary_record` and the legal parameters (packs give transaction time by version, ADR-0025). Pattern A plus `recorded_at`, `superseded_at`, `superseded_by` and `correction_reason`. The exclusion constraint applies `WHERE superseded_at IS NULL`. Rows are **never updated in place**; the trigger `sys.tg_bitemporal_guard()` allows only stamping `superseded_*` and forbids `DELETE`.
3. **Query conventions:**
   * current knowledge: `superseded_at IS NULL AND valid_during @> :d`;
   * as known on `:k`: `recorded_at <= :k AND (superseded_at IS NULL OR superseded_at > :k)`;
   * period overlap: `valid_during && daterange(:start, :end, '[]')`.

   Each has a partial B-tree index for the hot path.
4. Retro detection: Payroll compares `recorded_at` with the run's People watermark.
5. Org trees: `core.org_unit_version.path ltree` per version with a GiST index; subtree = `path <@ :root`.
6. All temporal writes go through the shared Java `temporal-kit` (supersede, split, merge, close). Hand-written temporal updates fail code review.
7. Contracts, employments and dependants with *inclusive* business end dates use generated `daterange(start, end, '[]')`, which PostgreSQL canonicalises to `[)`.

## Alternatives considered

| Option | Why not |
|---|---|
| Separate `_history` tables filled by triggers | Two shapes to query, and "as known on" requires a union. Corrections are hard to express. |
| Full bi-temporality everywhere | Doubles complexity and volume for data nobody queries "as known on" (locations, job titles); the audit log covers those. |
| SQL:2011 temporal tables / `temporal_tables` extension | Not native in PostgreSQL 16/17; the extension is unmaintained and not available on CNPG images by default. |
| `valid_to` inclusive | Contiguous ranges need `+1 day` arithmetic everywhere; off-by-one bugs. |

## Consequences

- \+ Overlaps are impossible by construction. Payroll reproduction and retro have a precise source.
- \+ One pattern, one library, one set of query idioms.
- − Bi-temporal tables grow with every correction; volume is small (salary/assignment history).
- − Stored generated columns are NULL in `NEW` inside BEFORE triggers, so the guard excludes `valid_during` (found in the DDL smoke test).
- − No pre-go-live history is loaded (D-09, no Oracle migration). Opening data is the version valid at the go-live date G, with `recorded_at` = load time and `source = 'OPENING_LOAD'`; "as known on" before the load returns the opening view (ADR-0037).
