# ADR-0037: Opening-balance import — Excel templates, staging, validation, reconciliation, commit

- **Status:** Accepted (2026-10-16, D-09)
- **Date:** 2026-10-16
- **Supersedes:** [ADR-0033](0033-oracle-migration-staging-reconciliation.md) (Oracle legacy migration)
- **Deciders:** Data architect (target tables, validation and reconciliation checks), Solution architect (import pipeline and templates), Core lead (import engine), Payroll / T&A / People leads, pilot customer data owners
- **Related:** decision-log D-09 and §6 interpretation; architecture brief baseline 7; GAP-005 (Core import engine); BR-CORE-114; NFR-PPL-007; ADR-0021, ADR-0022, ADR-0024, ADR-0026, ADR-0027, ADR-0028; [architecture-overview](../architecture-overview.md) §9.6; [DESIGN.md](../../../database/postgresql/DESIGN.md) §13; DDL `core.import_job`, `core.import_row`, `core.import_reconciliation` (`002_core.sql`)

## Context

The sponsor decided (D-09) that there is **no Oracle migration**: YourHRM is a new system and the pilot does not move its legacy history. A mid-year go-live still needs correct starting positions:

* the **employee master** (person, employment, current assignment, current salary record, contract, dependants, bank accounts) as of go-live;
* **YTD income, PIT withheld and SI contributions** for the current tax year, because PIT year-end finalisation (05/QTT) and the 13th-month / Tết bonus rely on the whole year;
* **SI contribution history** (months and basis), which drives SI decrease declarations, sickness/maternity eligibility and severance/unemployment calculations;
* **leave balances** (entitlement, taken, carried forward) per leave type and year;
* **outstanding loans and advances**.

Customers can provide these from any previous system or spreadsheet, so the solution must be source-agnostic, repeatable for every new tenant (not a one-off project), validated before anything reaches the target tables, and reconciled against totals the customer signs.

## Decision

1. **One generic pipeline, run by the Core import engine** in a dedicated mode `OPENING_BALANCE` (`core.import_job.mode`). There is no migration database, no `stg_raw`/`stg_clean`/`mig` schema and no legacy key map.
2. **Templates (Excel, vi/en)**, versioned and owned by the module that owns the data:

   | Import type | Owner module | Target (owner schema) |
   |---|---|---|
   | `OB_EMPLOYEE_MASTER` (person, employment, current assignment, salary record, contract, dependants, bank account) | People | `hr.*` |
   | `OB_YTD_PAYROLL` (per employee × month of the current tax year: gross, taxable income, PIT withheld, EE/ER SI, net) | Payroll | `pay.ytd_balance` (`source = OPENING`) |
   | `OB_SI_HISTORY` (per employee × month: basis, scheme, EE/ER amounts; from the SI book / VSS C12) | Payroll | `pay.si_contribution` (`status = OPENING`) |
   | `OB_LEAVE_BALANCE` (per employee × leave type × year: entitlement, taken, carried, closing) | T&A | `att.leave_balance_ledger` (`txn_type = OPENING`) |
   | `OB_LOAN` (per employee × loan: principal, outstanding, instalment plan) | Payroll | `pay.loan`, `pay.loan_schedule` |

   Every template has a **control sheet** with customer-declared totals (headcount per legal entity, sum of YTD gross/taxable/PIT per month, SI months, leave closing days, loan outstanding).
3. **Pipeline:** template download → upload (malware scan, sha256) → **parse to staging** (`core.import_row`, one row per sheet row; the whole parsed row is encrypted with a per-job DEK because it may contain CCCD, tax code or bank numbers, ADR-0026; only non-sensitive preview fields are clear) → **validation** by the owner module's validators (format, referential, business rules; blocking errors vs warnings) → preview with per-row messages → **reconciliation report** (`core.import_reconciliation`: control value vs staged value per check and grain, tolerance 0 VND / 0.0 days / exact counts; mismatches must be fixed or waived with a reason) → **customer sign-off** (`signed_off_by/at`; a CHECK constraint forbids committing an opening-balance job without it) → **commit** through the owner module's `ImportHandler` in its own transactions → **post-commit check** (`committed_value` recomputed from the target tables).
4. **Module boundaries are kept:** Core stores staging and the report; it never reads `hr`/`att`/`pay`. The owner module reads the staged rows through Core's in-process import API, computes staged and committed values in its own schema (`ReconciliationProvider` SPI) and returns them. No cross-schema grant is added (ADR-0021, X-02).
5. **Ordering and dependencies:** `OB_EMPLOYEE_MASTER` must be committed first (`depends_on_job_id`); the other templates reference employees by `employee_no` and the legal entity by code. Validation fails when the referenced employee does not exist.
6. **"Opening balance as of the go-live date" (replaces the migrated-history "known date"):**
   * every opening-balance job has an `as_of_date` = go-live date **G** (CHECK constraint);
   * master data is loaded as the **version valid at G**; its `valid_from` is the real start date from the template when known, otherwise G. History before that version is not loaded;
   * bi-temporal rows get `recorded_at` = load time and `source = 'OPENING_LOAD'`. "As known on" queries for dates before the load return the opening view; this is a documented limitation, not a defect;
   * YTD and SI history are stored per month before G and are never recalculated by YourHRM; retro before G is not supported (corrections are manual adjustments in the first run);
   * leave balances are one `OPENING` ledger row per employee × type × year, dated G.
7. **Idempotency and correction:** the business key (`employee_no`, + month / leave type / loan number) makes a re-upload an upsert of the same targets. A committed job can be rolled back by its undo log until the first payroll run of the tenant is calculated; after that, corrections are normal adjustments with audit.
8. **Events and audit:** commits publish events with the envelope flag `initial_load = true`; consumers skip side effects (invitations, notifications, retro triggers) and projections are rebuilt by re-sync jobs. Audit uses `app.audit_mode = 'BATCH'` (one summary event per chunk, actor `IMPORT`).
9. **Rehearsal:** the customer runs the full pipeline in a **sandbox tenant** first; the production load repeats it with the same files and template versions.
10. **Retention:** staging rows are deleted and the job DEK destroyed 30 days after commit; the job header and the reconciliation report are kept as go-live evidence for the payroll retention period `[verify-legal]`.

## Alternatives considered

| Option | Why not |
|---|---|
| Keep the Oracle staging/mapping/parallel-run design (ADR-0033) | Sponsor decision D-09: no migration. It also only served one source system. |
| Start every tenant with zero balances | Breaks PIT year-end finalisation, SI eligibility and leave entitlements for any go-live after January. |
| Direct SQL loads into module tables | Bypasses domain validation, events and audit; projections diverge; needs cross-schema access. |
| One import screen per module without a common pipeline | Inconsistent validation, no single reconciliation report or sign-off gate, duplicated staging code. |
| Keep full pre-go-live history (all salary/assignment versions) | Little value for the pilot, large data-quality effort; the opening view as of G is enough for payroll and statutory outputs. |

## Consequences

- \+ The same templates serve every new customer; onboarding is a product feature, not a project.
- \+ Hard quality gate (validation + reconciliation + sign-off) before data reaches payroll.
- \+ No extra environment or schema to secure; staging lives in the tenant's cluster under RLS, encrypted.
- − No pre-go-live history in YourHRM (salary changes, transfers). Customers keep their old records for disputes.
- − YTD/SI amounts before go-live are trusted inputs; errors found later need manual adjustments.
- − Template versions must follow legal changes (new SI schemes, PIT fields) like statutory templates.
