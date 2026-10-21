# Reporting store DDL (separate database)

`100_rpt.sql` builds the **reporting store**: a PostgreSQL 17 database that is separate from the OLTP database (DESIGN.md §11, ADR-0012 Amendment 1, ADR-0032). Do **not** load it into the OLTP database `yhrm`.

* **Not loaded by docker.** `infra/docker/postgres/initdb/10-yhrm-ddl.sh` only loads `database/postgresql/ddl/[0-9][0-9][0-9]_*.sql` at the top level of the folder. Files in `reporting/` are never picked up.
* **Manual local load** (for example into a second database on the same dev server):
  `createdb -U postgres yhrm_rpt && psql -v ON_ERROR_STOP=1 -U postgres -d yhrm_rpt -f 100_rpt.sql`
  The script creates its roles idempotently, so it also runs on a server that already holds the OLTP roles.
* **Feed (D4, v0.5).** In Phase 1 every OLTP cluster publishes column-listed tables. The publisher side is **`ddl/007_publications.sql`**, which the docker initdb loads after `006`. It creates one publication per module schema: `pub_rpt_core`, `pub_rpt_hr`, `pub_rpt_att`, `pub_rpt_pay` and `pub_rpt_rec`. The column lists never contain a `*_enc` or `*_bidx` column. The same file grants `yhrm_cdc` USAGE and column-level SELECT on exactly the published columns, and adds a read-all RLS policy (`read_all_yhrm_cdc`) on every published table. The reporting database subscribes as `yhrm_cdc`. The `CREATE SUBSCRIPTION` statements are in section 10 of `100_rpt.sql` as a commented example and are not run.

## Layers

| Schema | Content | RLS | Who reads it |
|---|---|---|---|
| `core`, `hr`, `att`, `pay`, `rec` | Landing tables of the logical-replication subscription, with the same columns as the publication column lists in `007_publications.sql`. Their names must match the publisher's schema-qualified names, because native logical replication cannot rename tables. Partitioned sources (`att.daily_timesheet`, `att.attendance_result_line`, `pay.result_line`) are published via their root and land in one plain table each. | none | `yhrm_cdc` (owner, apply worker) |
| `rpt_raw` | One view per landing table (`rpt_raw.hr_employment`, `rpt_raw.pay_result_line`, …). This is the stable input of the SQL models; a Phase 2 Debezium loader would write here directly. | none | `yhrm_rpt_transform` only |
| `rpt` | Star schema: SCD2/SCD1 dimensions, monthly-partitioned facts, `report_scope`. | `tenant_id = app.tenant_id` for `yhrm_rpt_reader`; explicit all-tenant policy for `yhrm_rpt_transform` | reporting service (`yhrm_reporting_svc`), Superset (P2) |
| `rpt_sys` | Local copy of the OLTP `sys` helpers: context, RLS template, partition helpers. Functions only. | n/a | all |

## Landing tables (= published tables)

| Schema | Tables |
|---|---|
| `core` | `legal_entity`, `legal_entity_version`, `location`, `location_version` (without `min_wage_region_override`, M2/D11), `cost_centre`, `org_unit`, `org_unit_version`, `position`, `position_version` |
| `hr` | `person` (P1 name only), `employment`, `assignment` |
| `att` | `attendance_result`, `attendance_result_line`, `leave_balance_ledger`, `daily_timesheet` (minimal: day status and hour totals) |
| `pay` | `payroll_run`, `run_employee`, `result_line`, `si_contribution`, `salary_component` |
| `rec` (Phase 2) | `requisition`, `job_opening`, `pipeline_stage`, `application`, `stage_history` (funnel only, no candidate PII) |

## Changes in v0.5 (2026-10-21, D4 and D11)

* `att` and `pay` landing tables and their `rpt_raw` views have been added.
* `core.location_version` no longer has `min_wage_region_override`.
* `rpt.fact_si_contribution` now uses the `pay.si_contribution` values:
  * `insurance_type` is `SI | HI | UI | OA_OD | TU`.
  * The new `source` column is `RUN | OPENING | ADJUSTMENT`.
  * `contribution_status` is `CONTRIBUTE | EXEMPT | NO_CONTRIBUTION | NOT_PARTICIPATING`. `OPENING` is a source, not a status.
* `rpt.fact_payroll_employee_month.er_oai_amount` has been renamed to `er_oa_od_amount`.
* `rpt.dim_pay_run`:
  * `run_type` now includes `COMPARISON`.
  * `status` is now checked and includes `CANCELLED` (D3).
* `rpt.dim_pay_component.component_type` now includes `ALLOWANCE`.

## Open points

1. **DESIGN §11 wording.** "Subscribe into `rpt_raw`" cannot work with native logical replication, which keeps the schema name. This file lands the data in source-named schemas and exposes them through `rpt_raw` views. DESIGN §11 and ADR-0032 decision 1 need to be updated to match.
2. **Dimension sources not yet published.** These dimensions have no landing source yet:
   * `dim_att_code`: needs the T&A attendance-code catalogue.
   * `dim_leave_type`: needs `att.leave_type`; `leave_type_code` is denormalised on the ledger.
   * The `dim_pay_component` attributes `is_taxable`, `is_si_basis` and `component_group`: these sit in the pay component version or config.

   Each one is added with a migration to `007_publications.sql`, a matching landing table and an `rpt_raw` view.
3. The transformation models and the load-watermark table are not part of this DDL. The models are the SCD2 merge, the snapshot build and the `report_scope` refresh.
4. Each subscription and its publication must be refreshed together. Logical replication carries no DDL.
