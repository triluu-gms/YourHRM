# ADR-0029: Payroll snapshots, immutable results and 10-year retention

- **Status:** Proposed
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect (ADR-0009 owns run orchestration), Payroll lead
- **Related:** NFR-PAY-001/002/003/006, NFR-SAAS-017, BR-PAY-071/072/123; gap §7; ADR-0009, ADR-0019, ADR-0025, ADR-0030; [DESIGN.md](../../../database/postgresql/DESIGN.md) §7.5, §8

## Context

Any approved run must be recomputable from its snapshot with identical results for ≥ 10 years. Results are about 72M lines per month at the 1M-employee sizing case. Approved runs are read-only, except through a privileged unlock with a second approval. ADR-0009 seals a snapshot before calculation and writes results by chunk with `COPY`.

## Decision

1. **Run config snapshot** (`pay.run_config_snapshot`, de-duplicated by `config_sha256`): component and formula versions (expression text and hash), structures, rounding rules, and the **resolved legal values** with `legal_pack_id` + `override_version`.
2. **Run input snapshot** (`pay.run_input_snapshot`, one per run × employee): `payload jsonb` (lz4 TOAST) with every input and its source version (salary record ids and `recorded_at`, attendance result version and checksum, variable inputs, dependants by month, YTD, loans, bank split), plus `payload_sha256`.
3. **Run header** stamps `code_version` (engine kernel), `config_version`, `legal_pack_id`, `override_version`, the consumed attendance versions and the People watermark.
4. **Results:** `run_employee` (totals, `result_sha256`) and `result_line` (component × segment, `amount` rounded, `amount_unrounded`, taxable, SI basis, `retro_of_period`), range-partitioned **monthly by `period_month`**.
5. **Immutability:** a trigger blocks changes to snapshots and results once the run is `APPROVED`. `UNLOCKED` archives the previous results into `pay.run_result_history` before recalculation.
6. **Retention:** 36 monthly line partitions stay hot. Older months are compacted into `pay.result_archive` (one row per run × employee, lines as compressed JSON, about 8× smaller), and the line partitions are dropped. Headers, snapshots, payslips, SI/PIT and declarations stay online for 10 years. The reporting store keeps 10 years of `fact_payroll_line`.
7. **Reproduction drill:** every month, a 0.5 % sample of last year's runs is replayed through the stamped kernel version, and the hashes are compared.

## Alternatives considered

| Option | Why not |
|---|---|
| Recompute from live tables "as known on" | Depends on 10 years of perfectly kept bi-temporal history in every input module and on unchanged config tables; fragile. |
| Results only as JSON documents per employee | GL, cost allocation, declarations and reports need SQL over lines. Documents are used for the archive tier only. |
| Keep all 10 years of lines hot | About 8.6B rows; index maintenance and vacuum costs grow without query value (analytics run on the reporting store). |

## Consequences

- \+ Reproduction is independent of later changes in People, T&A, Core and legal packs.
- \+ Hot data is bounded, and partitions make archival cheap.
- − Snapshot storage is about 110 GB/year at the sizing case; compression ratios need validation **[spike]**.
- − Queries on results older than 36 months use the archive table (slower), which is acceptable for audits and disputes.
- − Engine kernels and their container images must be retained for 10 years (ADR-0009 §7).
