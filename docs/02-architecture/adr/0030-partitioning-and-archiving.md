# ADR-0030: Partitioning, data lifecycle and archiving

- **Status:** Accepted (2026-10-16, D-03)
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect, DevOps
- **Related:** NFR-SAAS-004/005, NFR-ATT-001/002/007, NFR-PAY-003, BR-PAY-123, NFR-REC-009; ADR-0003 (pool clusters ~100k employees), ADR-0028, ADR-0029; [DESIGN.md](../../../database/postgresql/DESIGN.md) §10

## Context

At the sizing case (500 tenants, 1M employees across about 10 pool clusters), the large tables per year are: raw punches 1.25B rows, daily timesheets 360M, payroll result lines 864M, audit events 480M, and outbox traffic around 700M (transient). Legal retention is up to 10 years `[verify-legal]`, but queries concentrate on the last 1–3 years. Bulk deletion and vacuum must not hurt the payroll peak (days 25–5).

## Decision

1. **Partition by time, not by tenant.** Monthly range partitions for `att.raw_punch` (`work_date_hint`), `att.daily_timesheet` (`work_date`), `att.attendance_result_line`, `pay.result_line` (`period_month`), `audit.audit_event` (`occurred_at`) and `saas.usage_record`; yearly for `pay.run_input_snapshot`; daily for `*.outbox_event`; monthly for `*.inbox_message`. A tenant big enough to need its own partitions belongs in the dedicated tier or its own pool cluster.
2. **Hot windows in OLTP:** punches 25 months, timesheets 37, result lines 36 (then compacted, ADR-0029), audit 24, outbox 7 days, inbox 90 days. Attendance results, snapshots, SI/PIT and declarations stay 10 years.
3. **Archive path:** `DETACH PARTITION … CONCURRENTLY` → export to Parquet per tenant directory, encrypted with the tenant KEK, in VN object storage with object lock → `saas.archive_manifest` (URI, row count, sha256) → `DROP`. Restore = re-attach into a scratch schema on request (target ≤ 2 working days; thresholds and SLA accepted, D-03).
4. **Partition management:** pg_partman where available on CNPG images, otherwise `sys.create_range_partitions()` run by the scheduler, 3 periods ahead. No `DEFAULT` partitions on hot tables.
5. **Vacuum profile:** insert-only tables tune `autovacuum_vacuum_insert_scale_factor` and are frozen at month close. Update-heavy tables (`daily_timesheet`, `ot_cap_ledger`, `saas.job`) use `fillfactor = 80` and low scale factors.
6. **Index rules:** `tenant_id` leads every index; partial indexes for current/active rows; every FK indexed; GIN trigram for unaccented name search.

## Alternatives considered

| Option | Why not |
|---|---|
| Hash partitioning by tenant | Does not help retention (still needs `DELETE`), and query pruning only helps single-tenant queries, which the btree on `tenant_id` already serves. |
| List partition per tenant | 500+ tenants × big tables means thousands of partitions; planning overhead; provisioning DDL per tenant. |
| Keep everything online | Unbounded growth in vacuum, backups and restore time (RTO 4 h) for data that is rarely read. |
| Citus / distributed PostgreSQL | Operational complexity is not justified at about 100k employees per cluster; scale-out by pool clusters instead. |

## Consequences

- \+ Retention is a metadata operation. Backups, restore and vacuum stay bounded per cluster.
- − Archived data needs a restore step (minutes to hours); DSR "access" requests may need it.
- − Partition pre-creation is a monitored dependency.
- − Volume figures are estimates **[spike: SP-2 load test on the reference dataset]**.
