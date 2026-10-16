# ADR-0033: Oracle legacy migration — staging layers, key maps, data quality and reconciliation

- **Status:** Superseded by [ADR-0037](0037-opening-balance-import.md) (2026-10-16, D-09: no Oracle migration; YourHRM is built as a new system)
- **Date:** 2026-10-16

> **Superseded.** Kept for the record only. The sponsor decided (D-09) that the pilot's Oracle data is not migrated. The `stg_raw`/`stg_clean`/`mig` layers, key maps, legacy code maps and the parallel run below are **not built**. Go-live starting positions are loaded through the generic opening-balance import (ADR-0037). The data-quality rules and reconciliation checks in this ADR were carried over to ADR-0037 in source-agnostic form.
- **Deciders:** Data architect (mapping, staging, reconciliation design), Solution architect (process and tooling), SaaS migration lead, pilot customer data owners
- **Related:** BRD O4, A5, RK3; SaaS BRD P9, BR-SAAS-071–073, R3; NFR-PPL-007; gap-analysis §5 #12/#12b; README (Oracle credentials local only); [DESIGN.md](../../../database/postgresql/DESIGN.md) §13

## Context

The pilot migrates from an Oracle HR database within ≤ 16 weeks, with ≥ 1 (target 2) parallel payroll periods. Expected data quality problems: duplicates, missing IDs, legacy three-level addresses, unreliable YTD and SI history. Wrong opening balances corrupt the annual PIT finalisation and severance. Re-runs must be idempotent. Data and credentials stay in VN and outside the repository.

## Decision

1. A separate **migration database** in VN with `tenant_id` + RLS:
   * `stg_raw`: 1:1 text copies of the Oracle tables plus `batch_id`, `source_rowid`, `row_hash`, extracted at one consistent SCN;
   * `stg_clean`: typed and normalised (NFC Vietnamese, ID formats, names split, addresses mapped through `core.admin_unit_mapping`);
   * `mig`: `key_map`, `code_map`, `dq_rule`, `dq_issue`, `recon_run`, `recon_result`, `legacy_payroll_result`, `parallel_diff`.
2. **Idempotency:** `mig.key_map(source_system, source_table, source_key → target_id)` assigns one UUIDv7 per legacy key forever, so loads are upserts. Delta extracts use `row_hash`.
3. **Load channel:** Core import engine in migration mode → module APIs. Outbox events are flagged `migration=true` and suppressed by the relay, and projections are re-synced afterwards.
4. **History:**
   * valid-time history loads as-is;
   * bi-temporal `recorded_at` = cut-over timestamp with `source='MIGRATION'`;
   * opening balances go to `pay.ytd_balance`, `pay.si_contribution` (`MIGRATED`), `att.leave_balance_ledger` (`MIGRATED`) and `pay.loan`.
5. **Data quality rules** (blocking unless waived and signed): duplicate persons (CCCD blind index, or name + DOB + phone), invalid CCCD or tax code, overlapping ranges, below-minimum-wage salary, SI basis above the cap, dependants claimed twice, unmapped bank or admin unit, and inconsistent YTD.
6. **Reconciliation gate** before production:
   * headcount by LE/org/status/category, exact;
   * per-entity record counts and hash totals, exact;
   * YTD gross/taxable/PIT/net per employee and month, 0 VND;
   * PIT annual totals, 0 VND;
   * SI history months and amounts, exact;
   * leave balances, 0.0 days;
   * loans, 0 VND.

   The customer signs off in the SaaS `reconciliation_run`.
7. **Parallel run:** legacy results are imported monthly. YourHRM runs `PROVISIONAL` on the same inputs, and `mig.parallel_diff` compares per employee × component. Acceptance = net, PIT and SI equal to the VND, or every difference explained and signed off.

## Alternatives considered

| Option | Why not |
|---|---|
| Direct Oracle → target ETL without staging | No replay, no DQ evidence, no reconciliation trail; fails the sign-off requirements. |
| Load straight into target tables with SQL | Bypasses domain validation, events and audit; projections would diverge. |
| Deterministic UUIDv5 from legacy keys instead of a key map | Not time-ordered (index locality). Collisions between source systems need namespace discipline anyway, and the map also records lineage. |
| Reconstruct legacy transaction time | The source rarely has reliable change history; false precision. |

## Consequences

- \+ Repeatable, auditable migration with a hard quality gate. Also reusable for later customers (generic `stg`/`mig` layers).
- − The migration database is an extra environment to secure (VN, encrypted, access-logged, destroyed after go-live plus the retention agreed with the customer).
- − "As known on" queries before cut-over return the cut-over view (D-09).
