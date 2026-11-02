---
name: yhrm-payroll
description: Domain knowledge for the YourHRM Payroll module (BR prefix BR-PAY / NFR-PAY, interfaces IF-PAY-C-nn / IF-PAY-P-nn, DB schema `pay`, backend package `vn.yourhrm.payroll`). Payroll owns pay components and their independent PIT/SI flags, the YFL formula engine, pay groups, pay calendars, the standard-day method, pay-group assignment, payroll runs (snapshot, calculation, approval, close hold with T&A), gross-to-net and net-to-gross, mandatory insurance, PIT, retro pay, termination settlement, loans, payslips, bank files, GL export, SI contribution history and payroll opening balances. Use it when writing or reviewing code, DDL, events, APIs, tests or golden scenarios that touch payroll. Trigger terms include payroll, payslip, pay run, pay group, gross-to-net, net-to-gross, gross-up, proration, overtime pay, PIT, social insurance, retro pay, final settlement, severance, bank file, GL journal, opening balance, golden test, tính lương, bảng lương, phiếu lương, kỳ lương, nhóm trả lương, ngày công chuẩn, lương GROSS/NET, BHXH, BHYT, BHTN, TNLĐ-BNN, KPCĐ, đoàn phí, thuế TNCN, giảm trừ gia cảnh, 08/CK-TNCN, 05/KK-TNCN, 05/QTT-TNCN, D02-LT, C12-TS, truy lĩnh, quyết toán nghỉ việc, trợ cấp thôi việc, tạm ứng, khoản vay and số dư đầu kỳ.
---

# YourHRM Payroll (`pay`)

Also load **yhrm-conventions**. It covers tenancy/RLS, PII classes, outbox, the no-cross-schema-FK rule, the legal-parameter rule and build commands, and this skill does not repeat them.

For a specific BR or NFR, read `references/business-rules.md`. It indexes all 91 BRs and 13 NFRs with their phases. For a specific IF or event, read `references/integration.md`.

## 1. Purpose and scope
Payroll calculates, approves, pays, posts and reports employee pay for Vietnamese legal entities. That covers BHXH/BHYT/BHTN/TNLĐ-BNN, KPCĐ and thuế TNCN. It also produces the files the customer uploads to VSS (BHXH) and GDT (eTax/HTKK) (BRD §1).

**Out of scope, with the owner of each:**
| Not Payroll | Owner |
|---|---|
| Employee master, contracts, **agreed compensation** (salary record incl. GROSS/NET basis and agreed net amount), dependants, employee bank accounts, SI book number, insurance participation, tax residency | `people` |
| Legal parameter **values**: rates, caps, PIT brackets, deductions, minimum wage + region map, national holiday template | SaaS Legal Parameter Service (LPS) |
| Holiday calendar, timesheets, leave/OT approval, night/shift classification, Attendance Result, close-hold authority | `time-attendance` |
| Legal entity, including tax authority, PIT filing frequency, TNLĐ-BNN rate decision and the payer `legal_entity_bank_account`. Also org/cost centres, workflow engine, notifications, documents/PDF, import engine, audit, RBAC, FX rates | `core` |
| ESS shell, job scheduler, tenant keys, e-signature, file storage | `saas-platform` |
| Accounting ledger (Payroll only exports) | external ERP |
| Direct VSS/GDT API submission (T-VAN), and VSS benefit claims (01B-HSB) | Payroll, but P2 |

## 2. Ownership (gap-analysis §5, decision R2-02)
**Payroll is the single owner of:**
- Pay component catalogue, pay scales (thang bảng lương) and grade bands (#7). These are always on, even without a payroll subscription (R2-18).
- Pay groups, pay calendar and the **standard-day method** (#25). T&A computes `STD_DAYS`/`STD_HOURS` with the method Payroll publishes.
- Employee → pay group assignment (BR-PAY-085). People has no pay-group field.
- 08/CK-TNCN commitments and PIT finalisation authorisations (#4). People only displays their status.
- **SI contribution history**, including pre-go-live months (#20).
- Severance/job-loss service period and amount (#29, BR-PAY-078).
- Salary advances and loans (#21b).
- Payroll opening-balance templates `OB_YTD_PAYROLL`, `OB_SI_HISTORY` and `OB_LOAN` (ADR-0037).
- Money: rates, results, payments (A1). Payroll is the system of record for money.

**Payroll consumes, never writes:**
| Fact | From | Via |
|---|---|---|
| Agreed salary per version (frozen per run) | People | `people.SalaryChanged`, giving IF-PAY-C-11 and `pay.ext_hr_salary` |
| Dependants | People | `people.DependantChanged`, giving IF-PAY-C-12 and `pay.ext_hr_dependant_count` |
| Tax residency, tax code, SI number | People | `people.PersonalDataChanged` (IF-PAY-C-13) |
| Insurance participation per type × employment | People | `people.InsuranceParticipationChanged`, giving `pay.ext_hr_insurance_participation` |
| Primary employment for `PIT_FAMILY_DEDUCTION` | People | `people.PrimaryEmploymentChanged` (IF-PAY-C-41) |
| Bank split | People | `people.BankAccountChanged`, giving `pay.ext_hr_bank_split`. The account number comes from the People API at bank-file time and is never put in an event. |
| Attendance Result versions, OT, SI leave, shifts | T&A | `att.AttendancePeriodClosed(version)`, giving `pay.ext_att_result` (IF-PAY-C-19..27) |
| Legal values | LPS | `getParam(key, scope, asOfDate)`, `saas.LegalParameterPackPublished` (IF-PAY-C-29) |
| Legal entity attributes, region of workplace | Core | `core.LegalEntityChanged`, `core.LocationChanged` (IF-PAY-C-01/02) |

## 3. Core domain model (DDL `database/postgresql/ddl/005_pay.sql`)
Everything is Phase 1 unless the DDL marks it `-- P2`.
| Area | Tables | Pattern / invariants |
|---|---|---|
| Pay group & calendar | `pay_group`, `pay_group_rule`, `pay_calendar`, `pay_period`, `pay_group_assignment` | `pay_group.std_day_method` is one of `FIXED_DAYS`, `SCHEDULE_DAYS`, `CALENDAR_DAYS`, `FIXED_HOURS`. `frequency` is `MONTHLY`; `SEMI_MONTHLY` is P2. `pay_period.status` is `OPEN → INPUT_CUTOFF → CLOSED`, and periods don't overlap per group (EXCLUDE). Assignment is Pattern A: one ACTIVE pay group per employment at any date (EXCLUDE), with `source` `RULE`/`MANUAL`/`OPENING_LOAD`. |
| Components & formulas | `salary_component`, `component_version`, `formula`, `formula_version`, `salary_structure`, `structure_line` | Versions are effective-dated (EXCLUDE). `component_version` carries the independent flags:
- PIT treatment: `pit_treatment` (`TAXABLE`/`EXEMPT`/`PARTIAL_EXEMPT`), with `pit_cap_param_key` (an LPS key) or `pit_cap_amount`, and `pit_cap_period`.
- Bases: `is_si_basis`, `is_tu_basis`, `is_13th_month_basis`, `is_leave_pay_basis`, `is_severance_basis`, `is_ot_rate_basis`.
- Proration: `is_prorated`, `proration_basis` and `non_prorated_rule`.
- Gross/net: `gross_net_scope` and `is_net_guaranteed`.
- Rounding: `rounding_mode` and `rounding_unit`. A PUBLISHED/RETIRED `formula_version` is immutable (`tg_formula_version_guard`). |
| Pay scale (baseline) | `pay_scale`, `pay_scale_step`, `grade_band` | Always-on capability (IF-PAY-P-02, `pay.CompensationCatalogueChanged`). |
| Projections | `employee_pay_profile`, `ext_hr_salary`, `ext_hr_bank_split`, `ext_hr_dependant_count`, `ext_hr_insurance_participation`, `ext_att_result` | These are read-only caches. `ext_hr_salary` is Pattern B (`recorded_at`/`superseded_at`) and is compared with `payroll_run.people_watermark` for retro detection. `ext_att_result` has scope `PERIOD`/`EMPLOYMENT` and kind `REGULAR`/`EARLY_CLOSE`/`OFF_CYCLE_RETRO`. |
| Inputs | `input_batch`, `variable_input` | Batches can be rolled back before lock. Source `API` is P2. |
| Run | `run_config_snapshot`, `payroll_run`, `run_attendance_version`, `run_input_snapshot`, `run_employee`, `result_line`, `run_unlock`, `run_result_history`, `result_archive` | See §5. `result_line` is partitioned monthly and `run_input_snapshot`/`result_archive` yearly on `period_month`. |
| Retro | `retro_item` | `cause` is SALARY, ATTENDANCE, DEPENDANT, ASSIGNMENT, PRIMARY_EMPLOYMENT or MANUAL. `status` runs `DETECTED → INCLUDED/DEFERRED → PAID`. |
| SI | `si_contribution`, `si_declaration`, `si_declaration_line` | `si_contribution` has one current row per employment × month × `insurance_type` (`SI`, `HI`, `UI`, `OA_OD`, `TU`). `source` is `RUN`, `OPENING` or `ADJUSTMENT`. `rule_applied` is `SINGLE`, `FIRST_CONTRACT`, `HIGHEST_SALARY` or `EACH_EMPLOYER`. OPENING rows are never recalculated. |
| PIT | `pit_withholding`, `ytd_balance`, `pit_declaration(_line)` (05_KK only), `pit_finalisation(_line)` (annexes 05_1/05_2/05_3), `pit_finalisation_auth`, `pit_commitment`, `pit_certificate_series`, `pit_certificate`, `casual_payee`, `casual_payment`, `residency_check` | `pit_withholding.method` is `PROGRESSIVE`, `FLAT_10`, `FLAT_20` or `NONE`. In P1 `pit_certificate.signature_status` is always `UNSIGNED`. Certificate numbering is gap-free per legal entity × symbol. |
| Loans/deductions | `loan`, `loan_schedule`, `deduction_order` | In P1 `loan.interest_method` is `NONE`. `deduction_order.order_type` is one of `STATUTORY`, `COURT_ORDER`, `DAMAGE_COMPENSATION`, `ADVANCE`, `LOAN`, `OTHER`. |
| Settlement | `final_settlement` | `severance_kind` is `SEVERANCE`/`JOB_LOSS`/`NONE`. A `FINAL_SETTLEMENT` run requires `final_settlement_id`. |
| Payslip | `payslip`, `payslip_delivery`, `payslip_delivery_preference` | There is one password row per **person**. `pdf_password_enc` is P3 and write-only. The `EMAIL_PDF` channel requires both an attachment hash and a password. |
| Pay-out & GL | `bank_file_template`, `bank_payment_file`, `bank_payment_line`, `gl_mapping`, `gl_journal`, `gl_journal_line`, `cost_allocation` (P2) | File hash and finance approval are P2 (BR-PAY-112). |
| Masking | `v_run_employee_masked`, `v_result_line_masked`, `v_ext_hr_salary_masked`, `v_si_contribution_masked`, `v_pit_withholding_masked`, `pay.amounts_visible()` | These fail closed. Amounts are P2 plaintext (X-01). |

The BRD §6 names some entities that have no table under that name (see Known conflicts): `legal_param_set`, `pit_schedule`, `payroll_result`, `input_snapshot`, `si_contribution_opening` and `payslip_password`.

## 4. Key processes (BRD §3)
**Monthly run (P2 process; gap §7.2):**
1. Publish `pay.PayCalendarPublished`. It carries the T&A close deadline (D+2 working days), the approval deadline (D+5), the pay date and the std-day method.
2. People changes flow in as effective-dated events. T&A closes the period (`CLOSED`) and publishes a versioned Attendance Result.
3. Open the run for pay group × period. During `calc_phase SNAPSHOTTING`, seal the config snapshot (deduplicated by `config_sha256`) and one input snapshot per employment × `calc_seq`. Stamp `people_watermark`, `legal_pack_id`/`version`, `override_version`, `config_version` and `code_version`.
4. During `CALCULATING`, process chunks of about 250 employees grouped by `person_id` (ADR-0009). The pipeline runs gross → proration/segments → OT → SI/HI/UI/TU → taxable income → PIT → deductions → net. NET employees are grossed up. The run then moves to `CALCULATED`.
5. Raise exceptions (BR-PAY-074). Fix and recalculate.
6. **Submit.** Call `acquireHold(period, version, runId)` for every attendance version in scope, through the T&A close-hold API (IF-PAY-C-34). If any hold is refused, the submit fails and the run stays `CALCULATED`.
7. Approve through the Core workflow. Publish `pay.PayrollRunStatusChanged(APPROVED, versions consumed)`. T&A then moves the period `CLOSED → LOCKED` (`att.AttendancePeriodLocked`), and the mirrored holds become `CONSUMED`.
8. Produce the bank file, GL journal, SI/PIT liabilities and payslips. Publish `pay.SiContributionPosted`. After the bank confirms, mark the run `PAID`, then `CLOSED`.

**Off-cycle runs:** bonus, 13th month, Tết and corrections. Only the components enabled for the run type are calculated, and PIT is month-cumulative (BR-PAY-052).

**Retro pay (truy lĩnh):**
1. Detect changes recorded after an approved run's watermark, and T&A retro deltas (`att.RetroDeltaCreated`).
2. Recalculate each origin period "as known now", using **that period's** legal parameters.
3. Write the delta into the next open run as retro lines (`retro_of_period`), adjust SI per origin month, and apply PIT per BR-PAY-064. The default is the payment month.

**Termination settlement:**
1. Trigger on `people.EmployeeTerminated` (IF-PAY-C-08).
2. Take the employment-scope `EARLY_CLOSE` or off-cycle attendance result, and hold it like a period.
3. Calculate prorated pay, untaken leave, severance or job-loss pay, notice compensation, loan/advance recovery and PIT.
4. Pay within the statutory deadline (14 working days, `[verify-legal]`). Publish `pay.FinalSettlementStatusChanged`. Generate the D02-LT decrease.

**Monthly SI cycle:** compute contributions, generate D02-LT increase/decrease/adjust lines, export for the customer's I-VAN upload, and reconcile against C12-TS.

**PIT filing:** 05/KK-TNCN monthly or quarterly per the legal entity's frequency in Core. For annual 05/QTT-TNCN with annexes 05-1/05-2/05-3 for authorised employees, recalculate annual tax and process refunds or extra withholding in a run.

**Casual income:** withhold 10% per payment at or above the threshold unless a valid 08/CK-TNCN is on file. Non-residents are withheld at 20%. Issue a certificate.

**Opening balances (D-09, ADR-0037):** use the Core import engine in `OPENING_BALANCE` mode: validate, reconcile at 0 VND tolerance, get customer sign-off, then commit. There is no Oracle migration and no parallel run.
- `OB_YTD_PAYROLL` loads into `ytd_balance` (source `OPENING`).
- `OB_SI_HISTORY` loads into `si_contribution` (source `OPENING`).
- `OB_LOAN` loads into `loan`/`loan_schedule`.
- These rows are trusted inputs. They are never recalculated and never retro'd.
- A committed load can be rolled back until the tenant's first run is calculated. After that, only adjustments are possible.
- Events from the load carry `initial_load=true`.

## 5. Critical invariants (never break)
**Run lifecycle (BR-PAY-071, gap §7.1, DDL `pay.tg_payroll_run_guard`).** Allowed transitions:
```
DRAFT→CALCULATED|CANCELLED   CALCULATED→DRAFT|SUBMITTED|CANCELLED
SUBMITTED→APPROVED|REJECTED|CALCULATED(withdraw / T&A release request → stale)
REJECTED→DRAFT|CANCELLED     APPROVED→PAID|UNLOCKED     PAID→CLOSED|REVERSED(P2)
CLOSED→REVERSED(P2)          UNLOCKED→DRAFT             CANCELLED, REVERSED: terminal
```
- `calc_phase` (`NONE`, `SNAPSHOTTING`, `CALCULATING`, `DONE`, `FAILED`) is separate from `status`. SNAPSHOTTING and CALCULATING are allowed only in DRAFT, and CALCULATED or later requires `DONE`.
- "Locked" is reserved for **T&A periods**. A Payroll run is never "locked"; an `APPROVED` run is read-only.
- **Results freeze at `SUBMITTED`** (D2). Once a run is SUBMITTED or later, `pay.tg_run_immutable` rejects writes to `run_input_snapshot`, `run_employee`, `result_line` and `pit_withholding`, and the header allows only lifecycle columns. The only ways back are withdrawal to CALCULATED or UNLOCK.
- **UNLOCK** is allowed only from APPROVED; a PAID run is reversed, not unlocked. It needs a privileged role, a reason and a second approver (`run_unlock.approved_by <> requested_by`). Copy the approved results to `run_result_history` **before** recalculating. Unlock also returns the T&A period `LOCKED → CLOSED`.
- **CANCELLED** (D3) is allowed only from DRAFT, CALCULATED or REJECTED of a run that was never approved. It needs `cancel_reason`, `cancelled_by` and `cancelled_at`, and every mirrored hold must already be released (reason `CANCELLED`).
- Only a never-approved DRAFT run may be deleted.
- `PROVISIONAL` and `COMPARISON` runs (BR-SAAS-073) can never be approved. A REGULAR run must reference at least one attendance version.

**Close hold (BR-PAY-082/083/084, ADR-0019, D1).**
- `att.attendance_result_hold` is the **only authority**. `pay.run_attendance_version` is a mirror with one row per att hold. A replaced row gets `superseded_at`; never re-point `att_hold_id`.
- To submit or approve, every current mirror row must be `ACTIVE`, and the run must have `is_stale = false` and `inputs_changed = false`.
- Release the hold on reject, withdraw, cancel, T&A reopen request or unlock. Always record a reason, and audit the release.
- Approval never rolls back, because T&A locking cannot fail.

**Calculation:**
- **Snapshot first.** The engine reads only from the snapshot (BR-PAY-072, ADR-0009). Later People edits never change an approved run; they become retro.
- **Determinism.** Use `BigDecimal` only, never float or double. Backlog US-PAY-002 adds an ArchUnit rule to enforce this. No clock or randomness in the kernel, a stable line order and explicit per-component rounding. Store `amount` (rounded) and `amount_unrounded`, and hash the lines (`result_sha256`) so the run can be replayed (NFR-PAY-002/003).
- **Legal values** come from LPS as of the period date and are frozen in `run_config_snapshot.payload`. Split mid-period changes into segments (BR-PAY-024, `result_line.segment_*`).
- **PIT and SI flags are independent** per component (BR-PAY-002). For example, a phone allowance can be PIT-exempt and still count toward SI.
- PIT across several runs in a month is computed on the **month-cumulative** assessable income: family deduction applies once, and tax already withheld is subtracted (BR-PAY-052, `pit_month_mode`).
- **Net-to-gross** takes the smallest gross whose net is at or above the target and converges within 1 VND (ADR-0009 §9: piecewise-linear inverse plus integer bisection, at most 40 iterations). The P1 net scope is **fixed**: recurring components are net, bonus and OT are gross (R2-19). The per-employee scope (BR-PAY-021) is P2.
- **Minimum regional wage** (BR-PAY-027) is raised as an exception and never auto-corrected. The region comes from the **workplace** location, not the legal entity's address.
- **Multi-employment** (BR-PAY-081, R2-16): each legal entity withholds PIT on its own payments. Family deduction applies only on the `PIT_FAMILY_DEDUCTION` primary employment. Insurance is decided per type × employment from participation records and the legal parameter `insurance.multi_contract_rule`.
- **Deductions:** damage compensation is capped at 30% of monthly net after SI and PIT (BR-PAY-092). Priority is statutory → court → advance → loan → other, and amounts not recovered carry forward (BR-PAY-093).
- **The formula engine (YFL, ADR-0008) is sandboxed.** It allows only whitelisted pure functions, with no loops, I/O or reflection, and has node and depth budgets. Components form a DAG, and cycles are rejected when the formula is saved. New functions come only from vendor releases with golden tests, never from tenants.

**Security:**
- Payroll data is scoped by legal entity and pay group, and confidential pay groups are visible only to named users (BR-PAY-120).
- Audit every change to config, inputs and run state (BR-PAY-121).
- **Payslip password (BR-PAY-102, R2-17/R3-04):**
  - The employee sets it in ESS with step-up auth. It must be at least 8 characters and not derivable from DOB, ID, tax code or employee number.
  - There is no default or derived password.
  - Never send the password by e-mail, SMS or Zalo, and never show it to HR.
  - Encrypt the PDF with AES-256.
  - Until a password is set, the e-mail is a notice plus a deep link, with no attachment.
  - Employees without e-mail get a printed PDF and have no ESS in P1 (R3-05).

**Golden tests (NFR-PAY-008):**
- Build a pack of at least 200 scenarios validated by a VN payroll consultant. Cover GROSS/NET, resident/non-resident, proration, caps, OT, retro and termination.
- Run it on every release and every legal-parameter change. Tag the pack by kernel version.
- Every `[verify-legal]` story needs consultant sign-off before its golden scenario is accepted (backlog §4.3).
- A monthly job replays 0.5% of last year's runs (ADR-0029).

## 6. VN compliance as parameters (BRD §5)
Every value below is an **example** marked `[verify-legal]`. Read each one through LPS; never hard-code it, and don't treat these values as confirmed law.
- **SI/HI/UI/OA_OD rates** (§5.1): employer 21.5% and employee 10.5% in total. BHXH and BHYT are capped at 20 × the reference level (mức tham chiếu); BHTN at 20 × the regional minimum wage. KPCĐ is 2% employer. Đoàn phí is 1% employee, max 10% of the reference level, and only for flagged members. TNLĐ-BNN is 0.5% (0.3% if approved), and the rate decision is read from the Core legal entity (BR-PAY-045).
- **SI rules:** no contribution when the employee is unpaid for at least 14 working days in a month, and maternity months are exempt (BR-PAY-043). The SI salary is the declared one, not the prorated pay (BR-PAY-042). Contracts of 1 month or more are compulsory. Foreign workers follow configured rules, and BHTN does not apply to them.
- **PIT, residents:** progressive brackets, fully parameterised (BR-PAY-051). The schedule moves from 7 brackets (5–35%) to 5 brackets (5/10/20/30/35%). Self deduction goes from 11.0 to 15.5 M VND/month and dependant deduction from 4.4 to 6.2 M VND/month from tax year 2026. **The transition date is open (Q1).**
- **PIT, non-residents:** flat 20% with no deductions (BR-PAY-053). Residency comes from People. Payroll only *recommends* changes through the 183-day check (BR-PAY-065) and never changes residency itself.
- **Casual income:** 10% for payments of at least 2,000,000 VND unless a valid 08/CK-TNCN is on file; non-residents 20% (BR-PAY-054/055).
- **OT multipliers:** at least 150%, 200% or 300%; night work +30%; night OT +20% (BR-PAY-025). The premium above the normal rate is PIT-exempt (BR-PAY-026).
- **Partially exempt allowances** (BR-PAY-003): meals 730,000/month, uniform 5,000,000/year (a YTD cap), housing taxable at most 15%.
- **Severance** (BR-PAY-077/078): 0.5 month per year of service not covered by BHTN, on a 6-month average salary base. Job-loss pay is 1 month per year, minimum 2 (§5.4).
- **Tax code:** the CCCD serves as the tax code from 1/7/2025 (BR-PAY-061).
- **Forms and versions are parameters:** D02-LT, TK1-TS, C12-TS, 05/KK-TNCN, 05/QTT-TNCN and the e-certificate per ND 70/2025. Form schemas are configurable (NFR-PAY-010). The VSS forms under Luật BHXH 2024 are open (Q3).

## 7. Interfaces (details in `references/integration.md`)
- **Consumed:** IF-PAY-C-01..41.
  - Core: C-01..05 and C-33.
  - T&A: C-06 and C-19..27, plus the close-hold API C-34.
  - People: C-07..18 and C-35..41.
  - SaaS: C-29..32.
- **Published:**
  - `pay.PayCalendarPublished` (P-05)
  - `pay.PayGroupAssigned` (P-19)
  - `pay.PayrollRunStatusChanged` (P-06)
  - `pay.SiContributionPosted` (P-08)
  - `pay.FinalSettlementStatusChanged` (P-10)
  - `pay.CompensationCatalogueChanged` (P-01/02)
- **Synchronous APIs:** the legal-entity lock state for Core (P-20), the cost-estimate API (P-15; P2), and the 183-day recommendations (P-21).
- **Outbox/inbox:** `sys.create_outbox_inbox('pay', 'yhrm_app_pay')`.

## 8. Phase 1 vs Phase 2 (mvp-scope §9.4: 71 BR P1, 17 P2, 3 P3; NFR 11 P1, 2 P2)
**Don't build these in P1:**
- BR-PAY-006: the formula IDE. P1 has syntax validation only.
- BR-PAY-011: VN templates.
- BR-PAY-013: multi-currency.
- BR-PAY-021: per-employee net scope.
- BR-PAY-048 and 049: retro SI interest and SI arrears.
- BR-PAY-063: prior-employer income.
- BR-PAY-064: retro PIT recalculation option.
- BR-PAY-073: subset, single-employee or simulation runs. P1 runs the whole pay group, and a CALCULATED run serves as the preview.
- BR-PAY-079 and 080: reversal runs and close blockers.
- BR-PAY-090: salary advance flow. In P1 an advance is paid through an advance-type run or as a variable input.
- BR-PAY-094: expense reimbursement.
- BR-PAY-111: split or cash pay.
- BR-PAY-112: file hash, finance gate and status import.
- BR-PAY-114: cost allocation. P1 exports the default cost centre in the GL.
- BR-PAY-124: the cost-estimate API.
- NFR-PAY-012 and 013.
- P3: BR-PAY-103, 115 and 125.

**Only partly P1:**
- BR-PAY-029: the variable-input API is P2.
- BR-PAY-059: P1 produces XML plus an unsigned PDF; signing is P2 (R3-02).
- BR-PAY-070 and 077: Tết/13th-month eligibility and proration are P2 (R2-14).
- BR-PAY-081: cross-entity OT caps are P2 (R2-08).
- BR-PAY-086: P1 handles only `ContractChanged(SUSPENDED/RESUMED)` and `TemporaryTransferChanged`. Reward, secondment, appraisal, advance-deduction and training-refund events come in P2 with their sources.
- BR-PAY-091: P1 loans are interest-free only.
- BR-PAY-101: the mobile app is P2.
- Frequency is monthly only (A2). Direct VSS, GDT and bank APIs are P2 (R2-01).

**Kept in P1 despite Plan B:** BR-PAY-102, the password-protected e-mailed PDF.

**Release train (R4-02):**
- R1: the first live run.
- R2: retro, off-cycle/Tết and termination edge cases.
- R3: 05/QTT and certificates, before the annual deadline.

## 9. Open questions, risks and pending decisions
- **Q1:** the transition date for the PIT 5-bracket schedule and the new casual-income threshold.
- **Q2:** the reference level (mức tham chiếu) for 2026.
- **Q3:** the VSS forms/XML under Luật BHXH 2024.
- **Q11:** KPCĐ without a grassroots union.
- **R3-02:** the unsigned-certificate approach for 2027 needs legal confirmation.
- **R3-03:** the `insurance.multi_contract_rule` default. The PM notes that BHYT may legally require `HIGHEST_SALARY`. Logic must support all three values.
- **R3-04:** the payslip password approach needs DPO sign-off.
- **Spikes:** SP-1 (10k employees in ≤15 min, target 5) and SP-4 (YFL covers ≥95% of pilot rules) have not run yet.
- The kernel image must be retained for 10 years ([spike]).
- **Risks:**
  - R1: frequent legal change.
  - R4: retro complexity across modules.
  - R7: opening-balance quality.
  - R8: net-to-gross edge cases at caps, bracket boundaries and multiple runs per month.
- **Not decided in the sources:** whether `pay.PayrollRunStatusChanged` is emitted for `CANCELLED`. IF-PAY-P-06 and the gap §6 status lists predate D3. Ask before relying on it.

## 10. Known conflicts between sources
The precedence order is decision-log → ADR → DESIGN/DDL → BRD.
1. **Legal-param snapshot table.** BRD §6/BR-PAY-010 names `pay.legal_param_set`/`legal_param_value` and `pit_schedule`/`pit_bracket`. The DDL has none of these: resolved legal values live in `pay.run_config_snapshot.payload` plus the `legal_pack_id`/`legal_pack_version`/`override_version` stamps on the run, and PIT brackets live in LPS. **Follow the DDL.**
2. **BRD entity names vs DDL:**
   - `payroll_result` is `run_employee`.
   - `input_snapshot` is `run_input_snapshot`.
   - `si_contribution_opening` is `si_contribution` with `source='OPENING'`.
   - `payslip_password` is `payslip_delivery_preference.pdf_password_enc`.
   - `pit_declaration` holds only `05_KK`; 05/QTT is in `pit_finalisation`.
3. **Freeze point.** ADR-0029 §5 says results freeze at `APPROVED`. Decision D2 and DDL v0.5 say `SUBMITTED`. **D2 wins.**
4. **CANCELLED status** (D3) is missing from BRD BR-PAY-071 and IF-PAY-P-06.
5. **Primary-employment purpose.** BRD BR-PAY-081/IF-PAY-C-41 still list `SOCIAL_INSURANCE`. DESIGN v0.4 (H1) and gap §6 v1.2 keep only `PIT_FAMILY_DEDUCTION`; insurance moved to `hr.insurance_participation` and `people.InsuranceParticipationChanged`, which the BRD §7 IF list does not have.
6. **Run types.** BR-PAY-070 lists 10 run types. The DDL `run_type` has `REGULAR`, `OFFCYCLE`, `BONUS`, `FINAL_SETTLEMENT`, `CORRECTION` and `COMPARISON`; the rest go in `run_subtype_code`. `COMPARISON` (BR-SAAS-073) is not in the BRD.
7. **BR-PAY-059.** The mvp-scope §9.4 note says it "needs a digital signature", but §11.4 R2-C3 and R3-02 resolve it as unsigned in P1.

## 11. Where to look
- **BRD:** `docs/01-requirements/brd/modules/04-payroll.md`. §3 processes, §4 BRs, §5 VN rules, §6 entities, §7 interfaces and timeline, §9 NFRs, §10 questions and risks.
- **Phase:** `docs/01-requirements/brd/mvp-scope.md` §9.4, with §5 and §11 for the Plan B and R2 changes.
- **Ownership, events and close sequence:** `docs/01-requirements/brd/gap-analysis.md` §5 (#1, 4, 7, 20, 21b, 24, 25, 29), §6 and §7.
- **Decisions:** `docs/00-project-management/decision-log.md` (§2 D-09/D-10, §7 R2-xx, §8 R3-xx, §9 R4-xx, §10 D1–D11).
- **DDL and design:** `database/postgresql/ddl/005_pay.sql` (the header lists v0.5 changes) and `database/postgresql/DESIGN.md` (§7.5 pay ER, §8 storage/immutability, §13 opening balances).
- **ADRs** in `docs/02-architecture/adr/`:
  - 0008 YFL
  - 0009 run orchestration
  - 0010/0025 LPS
  - 0019 sagas/close hold
  - 0029 result storage
  - 0030 partitioning
  - 0037 opening balances
- **Backlog:** `docs/01-requirements/user-stories/payroll-backlog.md`. It has 121 stories (US-PAY-001..), 709 SP and 14 epics EP-PAY-01..14. §3 maps BRs to stories, and §4.2 lists the high-uncertainty stories.
- **Backend:** package `vn.yourhrm.payroll` (`api` plus `internal.{domain,application,infra,web}`). Only the placeholder `backend/src/modules/payroll/engine/` exists so far; it is not yet in `settings.gradle.kts`. ADR-0008 places YFL in `backend/src/shared/expression`, which doesn't exist yet.
