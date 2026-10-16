# ADR-0032: Reporting store data model — CDC-fed star schema with tenant isolation

- **Status:** Proposed
- **Date:** 2026-10-16
- **Deciders:** Data architect (model), Solution architect (ADR-0012 owns technology and pipeline)
- **Related:** BR-SAAS-046/047, NFR-SAAS-003; gap-analysis §9 A-10; ADR-0006 (`report_scope`), ADR-0012, ADR-0026, ADR-0029; [DESIGN.md](../../../database/postgresql/DESIGN.md) §11

## Context

Analytics must not load OLTP during payroll and check-in peaks. Freshness must be ≤ 15 min, and RLS and data scope must be inherited. ADR-0012 chose Debezium on a standby → Kafka → loader → a PostgreSQL 17 reporting store with a star schema, SCD2, and `tenant_id` + RLS. This ADR fixes the model and its isolation rules.

## Decision

1. **Layers:**
   * `rpt_raw`: CDC landing, one table per source table, append plus a merge view;
   * `rpt`: the star schema, built by SQL models run by the scheduler (dbt-style).
2. **Dimensions:** SCD2 for `dim_employee` (keyed by `employment_id`), `dim_org_unit` (with path), `dim_position`, `dim_legal_entity`; SCD1 for `dim_pay_component`, `dim_att_code`, `dim_leave_type`, `dim_location`, `dim_cost_centre`; plus `dim_date` and `dim_pay_run`. Surrogate keys are `bigint`; natural ids are kept.
3. **Facts:** `fact_headcount_daily` (periodic snapshot, `is_billable`), `fact_movement`, `fact_payroll_line` (employee × run × component, 10 years), `fact_payroll_employee_month`, `fact_attendance_daily`, `fact_ot`, `fact_leave`, `fact_si_contribution`, and `fact_recruitment_funnel` (P2). Facts are monthly-partitioned.
4. **Isolation:** `tenant_id` on every row, leading every index; the same RLS template and transaction-local context as OLTP. Data scope comes from `report_scope(tenant_id, user_account_id, employment_id)` joined on `employment_id`. Dedicated tenants get their own reporting DB.
5. **Privacy:** no P3 in the store; names (P1) are kept; amounts (P2) are visible only through permission-checked subject areas. DSR anonymisation updates `dim_employee`; tenant purge deletes by `tenant_id`.
6. Only approved payroll runs and locked or closed attendance periods enter the payroll and attendance facts. Provisional data is flagged and excluded by default.

## Alternatives considered

| Option | Why not |
|---|---|
| Report from OLTP read replicas with materialised views | Cross-module joins are forbidden on OLTP (ADR-0022); MV refresh load lands on replicas used for failover. |
| ClickHouse as the first store | Strong for scans, but RLS and SCD2 semantics are weaker and it is another engine to operate in VN. It remains the scale-out option if PostgreSQL misses NFR-SAAS-003 **[spike]**. |
| Data vault | Over-engineered for the report catalogue in scope. |

## Consequences

- \+ One governed model for standard reports, the designer (P2) and Superset.
- \+ Isolation is the same as OLTP, with no new mechanism.
- − CDC schema evolution must follow expand/contract (ADR-0031); the loader must tolerate added columns.
- − `report_scope` can be large (users × employees in scope). It is maintained incrementally, with role-level shortcuts for tenant-wide scopes **[spike]**.
