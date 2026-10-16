# YourHRM — Module BRD 04: Payroll (`payroll` / schema `pay`)

| Item | Value |
|---|---|
| Document | Business Requirements — Payroll module |
| Version | Draft v0.3 (2026-10-16) |
| Owner | BA – Payroll |
| Requirement prefix | `BR-PAY-NNN`, `NFR-PAY-NNN`; interfaces `IF-PAY-C-NN` (consumes) / `IF-PAY-P-NN` (provides) |
| Related | Charter §4–§5; BRDs 01-core, 02-people, 03-time-attendance, 05-recruitment, 06-saas-platform |
| Status | For review by PM, Integration analyst, VN labour/tax consultant |
| Change note | v0.2 (2026-10-16): period close changed to the **close hold** protocol (decision X-03, ADR-0019, [gap-analysis](../gap-analysis.md) §7 v1.1) — P2, BR-PAY-071/074/112, new BR-PAY-082/083/084, §6 `pay_period`/`payroll_run`, §7 timing legend, IF-PAY-C-19..27, new IF-PAY-C-34, IF-PAY-P-05/06, §7.3. "Locked" is now reserved for T&A periods; an approved run is read-only. Oracle migration replaced by the generic **opening-balance import** (decision D-09) — P1, BR-PAY-122, A5, R7. Mobile payslip access moved to Phase 2 (OD-9/X-04) — BR-PAY-101, IF-PAY-P-11. Billable unit per D-10 noted on IF-PAY-P-17. Edits are marked "(changed 2026-10-16, X-03/D-09)". v0.3 (2026-10-16): applies the approved single-owner table ([gap-analysis](../gap-analysis.md) §5, decision **R2-02**): legal parameters stored in the SaaS Legal Parameter Service, `pay.legal_param_set` = per-run snapshot of the pack version (BR-PAY-010; #1); holiday calendar from T&A (IF-PAY-C-06; #2); agreed compensation incl. GROSS/NET and agreed net consumed from People, salary frozen per run (BR-PAY-020/072, IF-PAY-C-10/11; #24, GAP-047); Payroll owns 08/CK-TNCN and finalisation authorisation (#4), pay group assignment and std-day method (new BR-PAY-085, IF-PAY-P-19; #25), SI contribution history incl. pre-go-live (#20), severance working time (BR-PAY-078; #29); exchange rates from Core (#19); ESS pages in the SaaS shell (#17); reports to the SaaS reporting store (#18). GAP-048: residency consumed from People + 183-day check report (new BR-PAY-065). GAP-049: tax authority, declaration frequency and OA-OD rate decision read from the Core legal entity (BR-PAY-045/056); legal-entity lock state provided to Core (new BR-PAY-087, IF-PAY-P-20). GAP-050: consumers for the People pay-impacting events (new BR-PAY-086, IF-PAY-C-35..40). R2-16: concurrent employment (BR-PAY-081). R2-17: password-protected payslip PDF with employee-set password (BR-PAY-102). R2-18: pay scales/bands always on (IF-PAY-P-02). Edits are marked "(changed 2026-10-16, R2-02 §5 #n)" or with the decision/gap ID. Priority columns unchanged (PM syncs). |

> **Phase:** the delivery phase of every requirement is set only in [mvp-scope.md](../mvp-scope.md) §9; MoSCoW here is business priority. (2026-10-16)

> **Legal values.** Every rate, cap, bracket, deduction, threshold, minimum wage and form code quoted below is an
> example marked `[verify-legal]`. None may be hard-coded: all are **effective-dated, versioned parameters**
> (BR-PAY-010). The system must compute any period with the parameter set that was in force for that period.

---

## 1. Purpose & scope

### 1.1 Purpose
Calculate, approve, pay, account for and report employee remuneration for Vietnamese legal entities, including
compulsory insurance (SI/HI/UI/OA-OD), trade union fee, and personal income tax (PIT), so that a tenant can run a
fully compliant monthly payroll without spreadsheets, and file the related declarations to the Vietnam Social
Security (VSS / BHXH) and the General Department of Taxation (GDT / Cục Thuế).

### 1.2 In scope
| # | Area |
|---|---|
| 1 | Salary component catalogue: earnings, allowances (taxable / partly / non-taxable, SI-liable / not), deductions, employer costs, informational items |
| 2 | Salary structures and a configurable formula engine per legal entity / grade / employee group |
| 3 | GROSS and NET contracts, net-to-gross gross-up |
| 4 | Proration for joiners, leavers, salary/allowance changes, unpaid leave; standard working-day methods |
| 5 | Overtime and night-work pay from T&A results |
| 6 | Compulsory SI / HI / UI / OA-OD (employee + employer), trade union fee (2%) and union member dues |
| 7 | PIT: residents (progressive), non-residents (20% flat), 10% withholding on casual income, commitment form 08/CK-TNCN, monthly/quarterly declaration, year-end finalisation (05/QTT-TNCN), e-withholding certificates |
| 8 | VSS declarations (D02-LT and related) and monthly contribution reconciliation |
| 9 | Payroll runs: monthly, off-cycle, bonus/13th month/Tết, retro pay, final settlement |
| 10 | Loans, salary advances and instalment deductions |
| 11 | Payslips (ESS, password-protected PDF, e-mail) |
| 12 | Bank payment files (VCB, BIDV, Techcombank, VietinBank, ACB, MB, …) |
| 13 | GL journal export, cost allocation by cost centre / project |
| 14 | Locking, approval workflow, audit, multi legal entity |

### 1.3 Out of scope (or owned by another module)
| Item | Owner |
|---|---|
| Employee master, contracts, salary *agreement* (the agreed compensation incl. GROSS/NET basis and agreed net — People is the system of record), dependants register, bank accounts, SI book number, tax residency status | `people` (changed 2026-10-16, R2-02 §5 #3, #5, #24; GAP-048) |
| Legal parameter values (rates, caps, brackets, deductions, minimum wages, region map, holiday template) | `saas-platform` Legal Parameter Service — Payroll reads and snapshots them per run (changed 2026-10-16, R2-02 §5 #1) |
| Holiday calendars | `time-attendance` (changed 2026-10-16, R2-02 §5 #2) |
| Attendance capture, timesheet, leave and OT approval, shift / night-hour classification | `time-attendance` |
| Legal entities, org tree, cost centres, regions, workflow engine, notifications, audit store, RBAC | `core` |
| Tenant provisioning, subscription, encryption keys, e-mail/SMS gateway, file storage, e-signature provider | `saas-platform` |
| Offer salary modelling (payroll only provides a cost estimate service) | `recruitment` |
| Posting to the accounting ledger (YourHRM exports; it is not an accounting system) | external ERP |
| Direct API submission to VSS / GDT (Phase 2 via T-VAN; MVP produces files for upload to VssID/I-VAN and eTax/HTKK) | payroll, Phase 2 |
| Processing SI benefit claims (sickness/maternity allowance paid by VSS, form 01B-HSB) | payroll, Phase 2 (`S`) |
| Expense claim approval | `people` (payroll only pays approved reimbursements) |

---

## 2. Actors & personas

| Actor | Description | Key permissions |
|---|---|---|
| Payroll Administrator (C&B Specialist) | Configures components, runs payroll, resolves exceptions | Configure, calculate, submit for approval, generate files |
| Payroll Manager / C&B Manager | Reviews variances, approves runs, unlocks with justification | Approve/reject, unlock, view all |
| Chief Accountant / Finance | Approves payment release, consumes GL export, reconciles SI/PIT payables | Approve payment, export GL, view summaries |
| Legal Entity Director (Legal representative) | Final approval where tenant policy requires; signs declarations | Approve, e-sign |
| HR Administrator | Maintains People data that feeds payroll | Read payroll results for own scope (configurable) |
| Line Manager | Views team cost reports (no individual salaries unless granted) | Aggregated reports |
| Employee (ESS) | Views payslips, PIT certificates, loan balances; submits 08/CK-TNCN, PIT finalisation authorisation | Own data only |
| Casual worker / contractor (non-employee payee) | Receives service fees, gets 10% withholding and certificate | Own certificate (portal or e-mail) |
| Auditor (internal/external) | Read-only audit of runs and changes | Read-only, audit log |
| Tenant Admin | Assigns payroll roles and data scopes per legal entity | RBAC (via `core`) |
| Platform Legal-Content Admin (SaaS operator) | Publishes national legal parameter packs (rates, caps, brackets) | Maintain global parameter sets (via `saas-platform`) |
| External systems | Banks, ERP/accounting, VSS portal, GDT eTax / HTKK, T-VAN | File / API exchange |

---

## 3. Business processes

### P1 — Payroll setup (one-off, then on change)
1. Platform publishes national legal parameter pack (minimum wages, reference level / base salary, SI/HI/UI/OA-OD rates, caps, PIT schedule, deductions, exempt caps) with effective dates.
2. Tenant payroll admin creates pay groups per legal entity (pay frequency, cut-off, pay date, standard-days method, currency, payer bank account from Core `legal_entity_bank_account`) and the default rule assigning employees to pay groups (legal entity + employee category) (changed 2026-10-16, R2-02 §5 #5b, #25).
3. Admin configures salary components and formulas, salary structures per grade / employee group, GL mapping, payslip template.
4. Admin loads opening balances (YTD income/PIT/SI, SI contribution history, 6-month salary history, loan balances) through the opening-balance import (BR-PAY-122), then runs a test payroll and compares it with the tenant's last payroll register. No Oracle migration or parallel run (changed 2026-10-16, D-09).
- *Exception:* a tenant needs a non-standard rate (e.g. OA-OD 0.3% approved for the entity) → the OA-OD rate decision is recorded on the Core legal entity (rate, decision number, validity, evidence — BR-CORE-002) and Payroll applies it if the legal pack allows that value; other permitted overrides are entity overrides in the Legal Parameter Service, effective-dated, with approval and evidence attachment (changed 2026-10-16, GAP-049; R2-02 §5 #1).

### P2 — Monthly payroll run (happy path)
1. **T-n:** People changes for the period are effective (hires, terminations, salary changes, dependants). T&A closes the attendance period (`CLOSED`) and publishes the versioned Attendance Result (IF-PAY-C-20) (changed 2026-10-16, X-03).
2. Admin opens the run for a pay group + period; the system snapshots inputs (employees in scope, contracts, compensation — the People salary record versions, frozen for the run — T&A results, variable inputs, loans, legal pack version) (changed 2026-10-16, R2-02 §5 #1, #24).
3. Admin imports/enters variable inputs (bonuses, one-time allowances, deductions) via UI or Excel template.
4. System calculates: gross earnings → proration → OT → SI/HI/UI/TU → taxable income → PIT → deductions → net; NET employees are grossed-up.
5. System produces exception list (negative net, net below threshold, missing bank account, missing tax code, variance > x% vs. previous period, salary below regional minimum, missing T&A).
6. Admin fixes and recalculates (individual or full); **submits** for approval. On submit the system acquires a **close hold** on every Attendance Result version used by the run (BR-PAY-082); if any hold is refused the submit fails and the run stays `Calculated`.
7. Payroll Manager → Finance (→ Director) approve via `core` workflow. Run status becomes **Approved** (read-only); T&A locks the held versions (`CLOSED → LOCKED`).
8. System generates bank file, GL journal, SI/TU/PIT payables, payslips; payslips released to ESS on publication date.
9. After bank confirmation the run is marked **Paid**; period closed.
- *Exceptions (changed 2026-10-16, X-03):* T&A period not `CLOSED` → run can be calculated in *provisional* mode only and cannot be submitted; T&A asks to re-open a held period → Payroll releases the hold, the run returns to `Calculated` with a stale flag and must be recalculated on the new version; employee data changed after snapshot → system flags "input changed since calculation" and blocks approval until recalculated or explicitly deferred to retro; rejection returns the run to Draft with comments and releases the hold.

### P3 — Off-cycle run (bonus, 13th month, Tết, correction, advance)
1. Admin creates an off-cycle run with a run type and selected population (filter or list import).
2. Only components enabled for the run type are calculated; PIT is computed per BR-PAY-052 (cumulative within the month).
3. Approval and payment as P2.

### P4 — Retroactive pay
1. A back-dated change arrives (salary increase effective in a closed period, late T&A correction, late dependant registration).
2. System detects affected closed periods and computes delta per component per original period.
3. Deltas are paid in the next open run as retro components, with SI adjustment for original months and PIT according to BR-PAY-064.
4. Retro detail is shown on the payslip and in a retro report.

### P5 — Final settlement on termination
1. People records termination (last working day, reason, notice compliance) → IF-PAY-C-08.
2. T&A provides final attendance and untaken annual leave balance to be paid.
3. Payroll calculates: prorated salary, leave encashment, severance / job-loss working time and allowance (Labour Code Art. 46–47; Payroll owns the computation, People supplies periods and eligibility), notice-period compensation/penalty, outstanding loans/salary advances, outstanding **cash advances** and **training-bond repayment** from People, asset recovery deductions, 13th-month pro-rata (if policy), PIT (changed 2026-10-16, R2-02 §5 #29; GAP-050).
4. Settlement is approved and paid within the legal deadline (14 working days, extendable to 30 days in specific cases) `[verify-legal]`; a settlement statement is issued to the employee.
5. SI decrease is declared (D02-LT) and the SI book closing is supported (data export).

### P6 — Compulsory insurance monthly cycle
1. Payroll computes contributions and the SI salary basis per employee for the month.
2. System generates increase / decrease / adjustment list (D02-LT) from People and T&A events (hire, termination, unpaid leave ≥ 14 working days, maternity, salary basis change).
3. Admin exports file / XML for VSS portal; reconciles VSS monthly notice (C12-TS) with payroll payable; differences listed.
4. Payment of contributions by the legal deadline (by the last day of the month for monthly mode) `[verify-legal]`.

### P7 — PIT monthly/quarterly declaration
1. On run close, PIT withheld is aggregated by legal entity (tax code) and by tax period.
2. System produces declaration 05/KK-TNCN data (XML compatible with HTKK/eTax) monthly or quarterly per the **PIT declaration frequency and tax authority of the Core legal entity** (changed 2026-10-16, GAP-049).

### P8 — PIT year-end finalisation (quyết toán)
1. Employees submit authorisation (08/UQ-QTT-TNCN or the current form) via ESS where eligible; ineligible employees are flagged.
2. System recomputes annual tax for authorised employees (annual income, annual deductions, annual schedule) and determines over/under withholding.
3. Admin reviews; adjustments are processed in a run (refund or additional withholding) per tenant policy.
4. System generates 05/QTT-TNCN and annexes 05-1/BK-QTT-TNCN, 05-2/BK-QTT-TNCN, 05-3/BK-QTT-TNCN.
- *Exception:* employee joined mid-year from another employer → not eligible unless conditions met; system shows eligibility reasons.

### P9 — Casual income (non-labour-contract or contract < 3 months)
1. Admin records payee (person, ID number, tax code), service, gross amount.
2. If gross per payment ≥ threshold and no valid 08/CK-TNCN → withhold 10%; non-resident → 20%.
3. Payment via bank file; withholding certificate issued; included in 05/KK and 05-2/BK.

### P10 — Loans and advances
1. Request (ESS or admin) → approval via workflow → disbursement (bank file or off-cycle run).
2. Repayment schedule generated; instalments deducted in runs; early settlement and suspension supported; remaining balance recovered in final settlement.

### P11 — Payslip & certificate distribution
1. On publication, payslips appear in ESS; optionally e-mailed as a **password-protected PDF** whose password the employee has set in ESS and which is never sent in the same e-mail (BR-PAY-102; R2-17) (changed 2026-10-16, R2-17).
2. PIT withholding certificates (e-certificates) are issued on request or at year end; delivered to ESS/e-mail.

---

## 4. Business requirements

### 4.1 Configuration, parameters & components
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-001 | The system shall maintain a salary component catalogue per tenant, each component having: code, VN/EN name, type (earning, allowance, deduction, employer contribution, information), calculation method (fixed, formula, input, imported, from T&A), currency, rounding rule, payslip display group and order. | M | |
| BR-PAY-002 | Each component shall carry independent flags: PIT treatment (taxable / exempt / partly exempt with cap reference), SI-basis inclusion (yes/no), TU-basis inclusion, proratable (yes/no and proration basis), GROSS/NET applicability, include in 13th month base, include in leave-encashment base, include in severance base. | M | Tax and SI treatments are independent (e.g. phone allowance may be PIT-exempt but SI-liable). |
| BR-PAY-003 | Partly exempt allowances shall reference an effective-dated cap parameter; the excess over the cap is automatically taxable. Pre-seeded: mid-shift meal (cap e.g. 730,000 VND/month when paid in cash `[verify-legal]`), uniform in cash (cap e.g. 5,000,000 VND/year `[verify-legal]`), phone/fuel/travel lump sum per company regulation (exempt up to company-regulated level), housing (taxable portion limited to 15% of total taxable income `[verify-legal]`). | M | Yearly caps (uniform) require year-to-date tracking. |
| BR-PAY-004 | Components shall be effective-dated and versioned; changes never alter results of approved runs. | M | |
| BR-PAY-005 | The system shall provide a tenant-safe formula engine supporting arithmetic, conditions (IF/CASE), MIN/MAX/ROUND, lookups (grade tables, rate tables), references to other components, employee attributes, T&A quantities, parameters and year-to-date values; dependency order shall be resolved automatically and circular references rejected at save time. | M | No arbitrary code execution; sandboxed expression language. |
| BR-PAY-006 | Formula authoring shall include validation, syntax highlighting, a test mode against a selected employee/period showing intermediate values, and version history with diff. | M | M → P2 (changed 2026-10-16, X-04) |
| BR-PAY-007 | Salary structures shall bundle components with default values/formulas and be assignable per legal entity, grade/level, employee group (e.g. office, factory, sales, expat), and contract type, with priority resolution when several match. | M | |
| BR-PAY-008 | Pay groups shall define legal entity, frequency (monthly in MVP; semi-monthly `C`), period start/end (supports non-calendar cut-off e.g. 26th–25th), optional OT cut-off, pay date, standard-days method, payment bank account (a Core `legal_entity_bank_account` of the entity), approval workflow, and payslip template. Payroll is the single owner of pay groups and the pay calendar (`pay.PayCalendarPublished`) (changed 2026-10-16, R2-02 §5 #5b, #25). | M | |
| BR-PAY-009 | Standard working-day methods shall be configurable per pay group: (a) fixed number (e.g. 26, 24, 22); (b) actual scheduled working days of the period from the employee's work schedule; (c) calendar days; (d) fixed hours (hourly paid). Paid public holidays count as paid days. Payroll is the **single owner** of the method; it is published in the pay calendar and T&A computes `STD_DAYS`/`STD_HOURS` with it (BR-ATT-016) (changed 2026-10-16, R2-02 §5 #25). | M | Work schedule from T&A. |
| BR-PAY-010 | All legal parameters (regional minimum wages and the admin-unit → region map, reference level / base salary, SI/HI/UI/OA-OD/TU rates, contribution caps, PIT schedule, personal and dependant deductions, casual withholding threshold and rate, non-resident rate, exempt caps, OT multiplier floors, settlement deadline) are **stored and published by the saas-platform Legal Parameter Service** and read by Payroll by effective date (`getParam(key, scope, asOfDate)`); tenant/entity overrides only where the pack flags "law permits", with audit and approval in that service. Payroll does not master legal values: `pay.legal_param_set` holds the **per-run snapshot of the pack version** (and the values used), so each result is reproducible (changed 2026-10-16, R2-02 §5 #1). | M | See §5. Changes mid-period follow BR-PAY-024. |
| BR-PAY-011 | The system shall ship with pre-configured Vietnam templates (component catalogue, structures for office/factory, GL mapping per Circular 200/133 successor charts) that a tenant can copy and adapt. | S | Accelerates onboarding. |
| BR-PAY-012 | Rounding rules shall be configurable per component and for net pay (e.g. round to 1 VND, 1,000 VND); rounding differences must be traceable. | M | |
| BR-PAY-013 | Multi-currency: contracts in USD (or other) shall be converted to VND at a configurable rate type/date (e.g. bank buying rate on pay date) read from the **Core exchange-rate table** (BR-CORE-088, IF-PAY-C-33), with the rate stored on the result; SI basis and PIT always in VND (changed 2026-10-16, R2-02 §5 #19). | S | Common for expats. Phase 2 (R2-09). |

### 4.2 Gross / net and earnings calculation
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-020 | The system shall support GROSS and NET salary agreements per employee, taken from the **People salary record** (`pay_basis`, `agreed_net_amount`, `people.SalaryChanged`), not from the contract. For NET, the system shall gross-up iteratively or analytically so that computed net equals the agreed net after employee SI/HI/UI, TU dues (if configured) and PIT (changed 2026-10-16, R2-02 §5 #24; GAP-047). | M | |
| BR-PAY-021 | Net-to-gross shall support configurable scope: which components are guaranteed net (e.g. base + fixed allowances) vs. gross (e.g. bonus, OT) — the per-employee scope comes from the People salary record (`net_guaranteed_components`), defaulting to the pay group rule — and whether employer bears SI on grossed-up amounts. Result must converge within 1 VND (changed 2026-10-16, GAP-047). | M | |
| BR-PAY-022 | Proration for mid-period joiners/leavers shall use paid days ÷ standard days (per BR-PAY-009) for proratable components; non-proratable components follow configured rule (full / none / threshold, e.g. full if ≥ 15 days). | M | |
| BR-PAY-023 | Unpaid leave, unauthorised absence and suspension days from T&A shall reduce proratable components by the configured unit (day/hour). | M | |
| BR-PAY-024 | When salary or allowances change within a period, the system shall split the period into segments by effective date and prorate each segment, showing segments on the payslip detail. | M | Applies also to mid-period legal parameter change. |
| BR-PAY-025 | OT pay shall be computed from T&A OT hours by category with configurable multipliers per category, not lower than legal minimums: weekday 150%, weekly rest day 200%, public holiday/paid leave day 300% (plus holiday pay), night work +30%, night OT additional +20% of day-rate `[verify-legal]`. | M | Labour Code 2019 Art. 98. |
| BR-PAY-026 | The OT hourly rate base shall be configurable (base salary only vs. base + specified allowances) and the system shall compute the PIT-exempt portion of OT/night pay (the amount exceeding normal-hour pay). | M | Circular 111/2013 Art. 3 `[verify-legal]`. |
| BR-PAY-027 | The system shall validate that the monthly salary for normal hours (full month) is not lower than the regional minimum wage applicable to the employee's work location; hourly minimum wage also checked for hourly paid employees. Violations are exceptions (not auto-corrected). | M | Decree on regional minimum wage. |
| BR-PAY-028 | Shift allowances, attendance bonus, hazardous/toxic allowance, responsibility allowance, seniority, KPI/commission and other inputs shall be supported as formula/input components. | M | |
| BR-PAY-029 | Variable inputs shall be importable via Excel/CSV template with validation (employee, component, period, amount) and via API; imports are versioned and reversible before lock. | M | |
| BR-PAY-030 | Salary during SI-paid sick/maternity leave shall not be paid by employer unless tenant policy tops-up; the days shall be excluded from paid days and included in SI-status logic (BR-PAY-043). | M | |
| BR-PAY-031 | Paid leave days (annual leave, paid personal leave, public holidays) shall be paid at the configured rate base (salary in contract) per Labour Code. | M | |

### 4.3 Compulsory insurance & trade union
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-040 | The system shall compute SI (retirement & survivorship, sickness & maternity), HI, UI and OA-OD contributions for employee and employer using effective-dated rates (§5.1). | M | |
| BR-PAY-041 | The SI salary basis shall be determined from People data per configurable rule: contract salary + allowances + regular supplements flagged SI-liable (BR-PAY-002), or an explicitly declared SI salary; it shall be ≥ the applicable regional minimum wage and capped: SI and HI at 20 × reference level (base salary), UI at 20 × regional minimum wage `[verify-legal]`. | M | SI Law 2024 Art. 31; Employment Law. |
| BR-PAY-042 | The SI salary basis for the month shall be the basis declared to VSS (not the prorated paid salary), unless the employee changes basis during the month, per VSS rules. | M | |
| BR-PAY-043 | The system shall determine monthly SI liability per employee: no SI/HI/UI contribution when the employee does not work and does not receive salary for ≥ 14 working days in the month (HI handled per VSS rule), exemption for maternity leave months, rules for joiners/leavers within month per VSS rule `[verify-legal]`. | M | Uses T&A unpaid/SI-paid day counts. |
| BR-PAY-044 | Eligibility: compulsory SI for employees with labour contracts ≥ 1 month (incl. part-time whose salary ≥ lowest SI basis), with exceptions configurable (e.g. retirees receiving pension, foreigners with exemption conditions such as intra-corporate transferees, employees aged ≥ retirement age); foreign workers' rates/regimes configurable `[verify-legal]`. | M | Eligibility from People SI enrolment record. |
| BR-PAY-045 | The OA-OD rate per legal entity (standard 0.5%, reduced 0.3% for eligible entities upon approval) `[verify-legal]` shall be read, effective-dated, from the **OA-OD rate decision on the Core legal entity** (BR-CORE-002, IF-PAY-C-01) and validated against the values the legal pack allows; a missing decision means the standard rate (changed 2026-10-16, GAP-049). | M | |
| BR-PAY-046 | Trade union fee (employer, 2% of SI salary basis) shall be computed per legal entity regardless of union establishment if law requires; union member dues (employee, e.g. 1% of SI salary basis capped at 10% of reference level) shall be deducted only for members flagged in People `[verify-legal]`. | M | Trade Union Law 2024. |
| BR-PAY-047 | The system shall produce VSS declaration data: increase/decrease/adjustment list D02-LT, registration data for new participants (TK1-TS data where applicable), and contribution reconciliation against VSS notice C12-TS; output as Excel/XML matching the current VSS template version (configurable template versions) `[verify-legal]`. | M | Decision 595/QĐ-BHXH and successors. |
| BR-PAY-048 | Retroactive SI adjustments (back-dated salary basis changes, late joiner reporting) shall generate adjustment lines per original month, including late-payment interest estimate. | S | |
| BR-PAY-049 | The system shall track SI arrears where an employee's net is insufficient and the employee share could not be deducted, and recover it in subsequent runs. | S | |

### 4.4 Personal income tax
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-050 | Residents: PIT on employment income = progressive schedule applied to monthly taxable income after exempt income, employee compulsory insurance, personal deduction, dependant deductions (registered and effective in People) and allowed deductions (voluntary pension within cap, charity) `[verify-legal]`. | M | |
| BR-PAY-051 | The PIT schedule (number of brackets, thresholds, rates) shall be fully parameter-driven; the system shall support at least: 7-bracket schedule 5–35% (until tax period 2025) and the reformed 5-bracket schedule 5/10/20/30/35% with new thresholds; personal deduction 11.0M → 15.5M VND/month and dependant 4.4M → 6.2M VND/month from tax period 2026 `[verify-legal]`. | M | Resolution 954/2020; Resolution 110/2025/UBTVQH15; PIT Law 2025. |
| BR-PAY-052 | When several payments occur in the same month (monthly run + bonus/off-cycle), PIT shall be computed on the cumulative taxable income of the month with deductions applied once, crediting PIT already withheld. Tenant may alternatively choose "tax in month of payment" vs. "tax in month of entitlement" per run type. | M | |
| BR-PAY-053 | Non-residents (from the effective-dated People residency status, BR-PPL-023 / IF-PAY-C-13) shall be taxed at a flat 20% on gross taxable employment income with no deductions; residency changes mid-year shall be handled with a re-computation report (changed 2026-10-16, GAP-048). | M | |
| BR-PAY-054 | Casual income (service contracts, labour contracts < 3 months, non-employees): withhold 10% on each payment ≥ threshold (e.g. 2,000,000 VND `[verify-legal]`) unless a valid 08/CK-TNCN commitment (tax-year, income estimate below threshold, has tax code) is on file; non-resident payees 20%. | M | Circular 111/2013 Art. 25. |
| BR-PAY-055 | The system shall be the **owner** of 08/CK-TNCN commitments (employees and casual payees outside People): capture them on Payroll ESS pages or by admin entry, store and track them per payee per year, warn when cumulative payments exceed the committed threshold, and auto-switch to withholding (changed 2026-10-16, R2-02 §5 #4). | M | People only displays the status. |
| BR-PAY-056 | The system shall generate monthly or quarterly PIT declaration data 05/KK-TNCN per legal entity tax code and **managing tax authority**, in HTKK/eTax XML format, using the **PIT declaration frequency** held per tax year on the Core legal entity (BR-CORE-002) `[verify-legal]` (changed 2026-10-16, GAP-049). | M | |
| BR-PAY-057 | PIT finalisation: the system shall recompute annual PIT per employee for the tax year (annual taxable income, 12 × monthly deductions or per actual dependant months, annual schedule), determine refund/additional amounts, and generate 05/QTT-TNCN with annexes 05-1/BK-QTT-TNCN (residents, progressive), 05-2/BK-QTT-TNCN (10%/20% withholding), 05-3/BK-QTT-TNCN (dependants) `[verify-legal]`. | M | Deadline: last day of 3rd month after year end `[verify-legal]`. |
| BR-PAY-058 | The system shall evaluate each employee's eligibility for authorised finalisation (labour contract ≥ 3 months, still employed at finalisation time, income-source conditions) and collect e-authorisation via ESS. | M | |
| BR-PAY-059 | The system shall issue electronic PIT withholding certificates (chứng từ khấu trừ thuế TNCN điện tử) with sequential numbering per entity series, XML + PDF rendering, and transmission to tax authority as required `[verify-legal]`. **Phase 1 (changed 2026-10-16, R3-02):** e-signature is P2, so the system produces the certificate **data** (XML in the tax authority format) and an **unsigned PDF**; the customer signs and issues the certificates through the tax authority's own tool or its existing e-invoice/e-document provider. Digital signature via the platform e-signature service and direct transmission follow in Phase 2. The Phase 1 route must be confirmed by the legal consultant for 2027 `[verify-legal]`. | M | Decree 123/2020, Decree 70/2025, Circular 32/2025. |
| BR-PAY-060 | Dependant deductions shall only apply from the month the dependant is registered as effective (and has tax code where required) and until end month; late registrations shall be considered at finalisation. | M | Dependant data from People. |
| BR-PAY-061 | Tax code (MST = CCCD number for individuals from 1/7/2025) validation; missing/invalid tax code shall be an exception and blocks e-certificate issuance `[verify-legal]`. | M | |
| BR-PAY-062 | Employer-paid PIT (for NET contracts) and employer-paid benefits in kind (housing, school fees, insurance premiums) shall be included in taxable income per configured treatment. | M | |
| BR-PAY-063 | Taxable income from previous employer within the year (for transferred staff in the same group) shall be capturable for finalisation. | S | |
| BR-PAY-064 | Retro pay PIT: configurable — tax in month of payment (default) or re-compute original months with adjustment declaration. | S | |
| BR-PAY-065 | **183-day residency check report.** Monthly and on request, the system shall count each foreign or expatriate employee's days present in Vietnam in the calendar year and in the 12 months from first arrival (from the People arrival/departure dates and, where available, T&A presence), flag employees whose computed status differs from the People residency status, and send a **proposal** to HR (People IN-32). Payroll never changes the residency itself; HR approves the change in People (BR-PPL-023), which triggers the BR-PAY-053 re-computation report (new 2026-10-16, GAP-048). | M | PIT Law; Circular 111/2013 `[verify-legal]` |

### 4.5 Payroll runs, lifecycle & control
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-070 | Run types: Regular, Off-cycle, Bonus, 13th month, Tết bonus, Correction, Final settlement, Advance, Casual income, PIT finalisation adjustment. Each run type defines enabled components, PIT method and approval route. | M | |
| BR-PAY-071 | Run lifecycle: `DRAFT` → `CALCULATED` → `SUBMITTED` → `APPROVED` → `PAID` → `CLOSED`; side states `REJECTED` (→ `DRAFT`), `UNLOCKED` (→ `DRAFT`), `REVERSED`. `APPROVED` replaces the former separate "Locked" state: approved runs are read-only. Unlocking an approved run requires a privileged role, reason and second approval, is fully audited, and returns the T&A period `LOCKED → CLOSED`. (changed 2026-10-16, X-03) | M | Unified states, gap-analysis §7.1 |
| BR-PAY-072 | The run shall snapshot all inputs (People incl. the **salary record version used per employee**, T&A, legal pack version, formulas version) at calculation, so results are reproducible; recalculation shall show what changed. The salary used by an approved run is frozen and never changed by later People edits, which flow as retro (BR-PAY-076) (changed 2026-10-16, R2-02 §5 #24). | M | |
| BR-PAY-073 | Calculation shall be possible for full population, filtered subset, or single employee, and in "simulation" mode (no persistence of approval state). | M | |
| BR-PAY-074 | Exception & validation report before submission: negative/zero net, net below configured %, missing bank account, missing tax code, missing SI number, salary below regional minimum, period-over-period variance > threshold, employees in People but not in run (and vice-versa), attendance period not `CLOSED` or stale (changed 2026-10-16, X-03), terminated employees still paid. Blocking vs. warning configurable. | M | |
| BR-PAY-075 | Approval shall use the `core` workflow engine with configurable multi-level routes per legal entity and run type; approvers see summary, variance, and drill-down within their data scope. | M | |
| BR-PAY-076 | Retro pay: back-dated changes affecting locked periods (salary, allowances, T&A corrections, dependants) shall be detected automatically, deltas computed per period and component, and paid in the next open run with retro components and retro report. | M | |
| BR-PAY-077 | Final settlement run shall compute: prorated salary, untaken annual leave encashment, severance allowance (0.5 month/year of service not covered by UI) or job-loss allowance, notice compensation/penalty, loan / salary-advance recovery, **cash-advance deduction** (`AdvanceDeductionRequested`, IF-PAY-C-40) and **training-bond repayment** (`EmployeeTerminated`, IF-PAY-C-08), material-compensation balance, 13th month pro-rata (policy), PIT; and produce a settlement statement `[verify-legal]` (changed 2026-10-16, GAP-050). | M | Labour Code Art. 46–48, 62, 113. |
| BR-PAY-078 | **Payroll computes the severance / job-loss working time and amount** (single owner): basis = average salary of 6 consecutive months before termination per contract; working time = the employment periods supplied by People minus the periods already compensated (People) minus the UI-contributed months from Payroll's own SI contribution history (incl. pre-go-live months from the opening-balance import). The result (working time, excluded months, base, amount) is published to People for display (IF-PAY-P-10) (changed 2026-10-16, R2-02 §5 #29). | M | |
| BR-PAY-079 | Reversal of a paid run (e.g. wrong bank payment) shall create a reversing run with full audit, not deletion. | S | |
| BR-PAY-080 | Payroll period close shall be blocked if bank payment, GL export, or SI/PIT payable generation is not completed (configurable). | S | |
| BR-PAY-081 | The system shall support multi legal entity: each pay group belongs to one legal entity. **Concurrent employment (Phase 1, basic — R2-16):** an employee with employments in two legal entities of the same tenant is paid in each entity's pay group; each entity withholds PIT on its own payments; the primary employment for purpose `PIT_FAMILY_DEDUCTION` (People, BR-PPL-004, `people.PrimaryEmploymentChanged`, effective-dated) applies the personal and dependant deductions. Insurance is computed **per insurance type per employment** from the People participation records, not from a single flag `[verify-legal]`; the participation records apply the legal parameter `insurance.multi_contract_rule` per insurance type (SI, HI, UI, OA-OD), with values `FIRST_CONTRACT` or `HIGHEST_SALARY` or `EACH_EMPLOYER` (Legal Parameter Service, 06 PR-06). Defaults: SI, UI and HI = `FIRST_CONTRACT` (sponsor decision R3-03), OA-OD = `EACH_EMPLOYER`; all `[verify-legal]` pending the labour consultant (changed 2026-10-16, R3-03) (primary for purpose `SOCIAL_INSURANCE` = first contract); D02-LT lines are produced per employer accordingly. The other employments withhold PIT without family deductions (progressive schedule, or 10% where the contract rules require it) `[verify-legal]`. The exception report flags a person with zero or two primary employments for a purpose in a month, and an employment with no participation decision for an insurance type. The cross-entity OT cap is Phase 2 (R2-08) (changed 2026-10-16, R2-16). | M | SI rule for several contracts per SI Law 2024 `[verify-legal]`. |
| BR-PAY-082 | **Close hold on submit.** Submitting a run shall acquire, through the T&A close-hold API (IF-PAY-C-34), a hold on each Attendance Result version recorded in the run snapshot (idempotent on run id + version). Submit is refused if any period is not `CLOSED`, the version is not current, or T&A is unavailable. Final-settlement and correction runs hold the individual / off-cycle result version the same way. (new 2026-10-16, X-03) | M | ADR-0019 |
| BR-PAY-083 | **Hold release.** The system shall release the hold whenever the run leaves `SUBMITTED` other than by approval: rejection (→ `DRAFT`), withdrawal by the admin (→ `CALCULATED`), cancellation, a T&A release request (→ `CALCULATED`, stale flag set, approval blocked until recalculated on the new version), and unlock (→ `UNLOCKED`). Re-submit re-acquires the hold. Every release states its reason and is audited. (new 2026-10-16, X-03) | M | |
| BR-PAY-084 | **Approval gate and lock confirmation.** Approval shall be allowed only when every in-scope attendance period is `CLOSED` with an active hold of this run on the current version and no stale or unresolved "input changed" flag. On approval the system publishes `pay.PayrollRunStatusChanged(APPROVED, versions consumed)`; T&A's lock cannot fail, so an approval is never rolled back. A nightly reconciliation report lists runs and periods whose states disagree (e.g. `APPROVED` run with a `CLOSED` period). (new 2026-10-16, X-03) | M | Replaces approve-then-rollback (GAP-027) |
| BR-PAY-085 | **Pay group assignment.** Payroll shall own the effective-dated employee → pay group link: a default rule per tenant (legal entity + employee category, optionally location) assigns each employment on `people.EmployeeHired` / `AssignmentChanged`, with manual override and reason; each assignment publishes `pay.PayGroupAssigned` (IF-PAY-P-19) for T&A and People. An active employment without a pay group is an exception. (new 2026-10-16, R2-02 §5 #25) | M | People has no pay group field. |
| BR-PAY-086 | **People pay-impacting events.** The system shall consume and apply: `people.RewardApproved` → variable input of the reward component in the target period; `people.ContractChanged(SUSPENDED/RESUMED)` → stop/resume pay and SI contributions for the suspension period; `people.TemporaryTransferChanged` → protected-salary component (old salary kept for the statutory period, new salary ≥ 85% and ≥ regional minimum wage) `[verify-legal]`; `people.SecondmentChanged` → paying legal entity / pay group for the secondment period; `people.AppraisalFinalised` / KPI score → input for KPI or bonus formulas (S); `people.AdvanceDeductionRequested` → deduction order (cash advance) in the next run or final settlement; training-bond repayment from `people.EmployeeTerminated` → final-settlement item. Each applied event is traceable on the result line. (new 2026-10-16, GAP-050) | M | IF-PAY-C-35..40 |
| BR-PAY-087 | **Legal-entity lock state.** The system shall derive and expose, per legal entity and as of a date, the latest pay period for which any pay group of the entity has an `APPROVED`, `PAID` or `CLOSED` run (and whether an unlock is in progress), so Core can warn or block back-dated reorganisations and legal-entity changes (IF-PAY-P-20). (new 2026-10-16, GAP-049) | M | Core C-16, BR-CORE-015 |

### 4.6 Loans, advances & deductions
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-090 | Salary advance (tạm ứng lương): request via ESS or admin, limit rule (e.g. ≤ x% of earned salary to date), approval via workflow, payout in advance run or bank file, automatic deduction in the next regular run. | M | Labour Code Art. 101. |
| BR-PAY-091 | Employee loans: principal, interest (optional, flat/reducing), schedule, instalment deduction component, suspension, early repayment, balance statements on payslip and ESS. | S | S → P1, interest-free only (changed 2026-10-16, D-09) |
| BR-PAY-092 | Deductions for damages/compensation shall respect the legal cap per month (≤ 30% of monthly net salary after insurance and PIT) `[verify-legal]`. | M | Labour Code Art. 102. |
| BR-PAY-093 | Deduction priority order when net is insufficient (statutory → court orders → advances → loans → other), with carry-forward of unrecovered amounts. | M | |
| BR-PAY-094 | Approved expense reimbursements from People shall be paid via payroll as non-taxable components (or separate payment file) with reference to claim ID. | S | |

### 4.7 Payslips & ESS
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-100 | Payslip template configurable per pay group (VN/EN bilingual), showing earnings, allowances, OT detail, SI/HI/UI, TU, PIT, deductions, net, paid days, leave balance, YTD totals, retro detail, loan balance. | M | Labour Code Art. 95.3 requires notifying salary statement each payment. |
| BR-PAY-101 | Payslips published to ESS (responsive web in Phase 1; mobile app in Phase 2 — changed 2026-10-16, OD-9/X-04) at configured publish date/time after approval; employees can view history and download PDF. | M | |
| BR-PAY-102 | E-mail distribution of payslips as a **password-protected PDF** (sponsor decision R2-17; exception to BR-CORE-074), with delivery status tracking and resend. **Password rule:** (a) the employee sets a payslip password in ESS (step-up authentication), minimum 8 characters, not derivable from date of birth, ID/tax number or employee code; (b) the password is **never sent** by e-mail, SMS or Zalo, in the same or any other message; HR cannot see it; (c) the PDF is encrypted with AES-256; (d) there is **no default, initial or derived password** (DOB + ID digits is rejected as too weak under PDPL); until the employee has set one, the e-mail carries only a notice with a secure ESS deep link and **no attachment**; (e) reset only in ESS with step-up; (f) every send, resend and password change is audited. Tenant may switch e-mail attachments off (deep link only) (changed 2026-10-16, R2-17). **Employees without an e-mail address** (changed 2026-10-16, R3-05): HR prints the PDF payslip (BR-PAY-100 template, unencrypted print run per pay group, audited) and hands it to the employee on every payment (Labour Code Art. 95(3)); no activation codes are issued, and these employees have no ESS access in Phase 1. The run shows a "print" list of employees without e-mail. | M | E-mail via the Core notification centre (P-14) and saas-platform delivery adapters (R2-02 §5 #14). PDPL `[verify-legal]`. |
| BR-PAY-103 | Employees can raise a payslip query from ESS, routed to payroll admin with SLA tracking. | C | |
| BR-PAY-104 | Payroll ESS pages (in the saas-platform ESS shell) shall show payslips, PIT certificates, annual income summary, dependant deductions applied, set/reset the payslip password (BR-PAY-102), and allow submission of 08/CK-TNCN and finalisation authorisation, which Payroll owns (changed 2026-10-16, R2-02 §5 #4, #17; R2-17). | M | |

### 4.8 Payment, accounting & cost allocation
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-110 | Bank payment files shall be generated per paying bank account in bank-specific formats (VCB, BIDV, Techcombank, VietinBank, ACB, MB, VPBank, Sacombank) via configurable file templates (CSV/XLSX/fixed-width/XML), including same-bank and inter-bank (Napas/Citad) splits. | M | Formats per bank; template designer reduces dev per bank. |
| BR-PAY-111 | Support split payment of net salary to up to 2 accounts and cash payment (with cash list), and payments to casual payees. | S | |
| BR-PAY-112 | Payment file generation requires an `APPROVED` run (changed 2026-10-16, X-03) and Finance approval; file hash is stored; regeneration is audited. Payment status (sent, paid, rejected per line) can be imported back. | M | |
| BR-PAY-113 | GL journal export: configurable mapping of each component to debit/credit accounts (e.g. 622/627/641/642 vs. 334, 3383, 3384, 3386, 3382, 3335, 141) with segments (legal entity, cost centre, project, department), in CSV/Excel and API format for ERP (MISA, Fast, SAP B1, Oracle) `[verify-legal]` (chart of accounts per Circular 200/2014 or its successor, Circular 133/2016). | M | |
| BR-PAY-114 | Cost allocation: employee cost (gross + employer contributions + TU fee) shall be allocated to cost centres/projects by default assignment from People/Core or by % split/time-based split from T&A project timesheets. | M | Project hours from T&A (`S`). |
| BR-PAY-115 | Accruals: monthly accrual of 13th month / bonus provisions and unpaid leave liability. | C | |

### 4.9 Security, audit & data
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PAY-120 | Data scope security: payroll data visible only to users with payroll roles for the specific legal entity/pay group; "confidential" pay groups (executives) restricted to named users. | M | RBAC from `core`. |
| BR-PAY-121 | Every change to components, formulas, parameters, employee payroll inputs and run status shall be audited (who, when, before/after, reason). | M | Audit store in `core`. |
| BR-PAY-122 | Opening-balance import (generic Excel templates on the Core import engine, no legacy Oracle migration): per employee and legal entity, current tax-year YTD taxable income, PIT withheld, SI/HI/UI basis and contributions; SI contribution history before go-live (months, basis, unit) into `si_contribution_opening` — Payroll owns all SI history (R2-02 §5 #20); 6–12 months of salary history for severance and 6-month averages; outstanding loans/advances. Validation report, dry-run and a reconciliation report against the tenant's last payroll register, signed off by the tenant. (changed 2026-10-16, D-09) | M | Needed for a mid-year go-live and PIT year-end finalisation. |
| BR-PAY-123 | Retention of payroll records and declarations for the legal period (e.g. ≥ 10 years for accounting documents) `[verify-legal]`. | M | Law on Accounting. |
| BR-PAY-124 | Salary cost estimate API for recruitment offers and headcount planning (input: gross/net amount, region, residency, dependants; output: gross, net, employer cost). | S | |
| BR-PAY-125 | Payroll calculator for HR/ESS (gross↔net simulation) using current parameters. | C | |

---

## 5. Vietnam compliance rules

All values `[verify-legal]`; all modelled as effective-dated parameters (BR-PAY-010).

### 5.1 Compulsory insurance contribution rates (Vietnamese employees)
| Fund | Employer | Employee | Basis / cap | Legal basis |
|---|---|---|---|---|
| SI – Retirement & survivorship | 14% | 8% | SI salary basis, cap 20 × reference level | SI Law 2024 (Law 41/2024/QH15, eff. 1/7/2025) |
| SI – Sickness & maternity | 3% | – | same | SI Law 2024 |
| SI – Occupational accident & disease | 0.5% (0.3% if approved) | – | same | Law on OSH 2015, Decree 58/2020 |
| Health insurance (HI) | 3% | 1.5% | cap 20 × base salary / reference level | HI Law (amended 2024) |
| Unemployment insurance (UI) | 1% | 1% | cap 20 × regional minimum wage | Employment Law 2025 (eff. 1/1/2026) / 2013 |
| **Total** | **21.5%** | **10.5%** | | |
| Trade union fee | 2% | – | SI salary basis | Trade Union Law 2024 |
| Union member dues | – | 1% (cap 10% of reference level) | SI salary basis | Trade union charter |

Foreign employees: SI (retirement, sickness/maternity, OA-OD) and HI apply to eligible foreigners with work permits and contracts ≥ 12 months (exemptions: intra-corporate transferees, retirement age); UI does not apply. `[verify-legal]`

### 5.2 Reference values
| Parameter | Example value | Effective | Legal basis |
|---|---|---|---|
| Base salary / reference level (lương cơ sở / mức tham chiếu) | 2,340,000 VND | 1/7/2024 | Decree 73/2024; SI Law 2024 Art. 7 |
| Regional min wage – Region I / II / III / IV | 4,960,000 / 4,410,000 / 3,860,000 / 3,450,000 VND/month | 1/7/2024 | Decree 74/2024 |
| Regional min wage – Region I / II / III / IV | 5,310,000 / 4,730,000 / 4,140,000 / 3,700,000 VND/month | 1/1/2026 | Decree 293/2025 (region list re-mapped after 2025 provincial merger) |
| Max SI/HI basis | 20 × reference level (e.g. 46,800,000 VND) | | |
| Max UI basis | 20 × regional min wage (e.g. Region I 106,200,000 VND from 1/1/2026) | | |

Region is determined by the employee's **work location** (administrative unit) from Core/People, not the legal entity's registered address.

### 5.3 PIT
| Rule | Example | Legal basis |
|---|---|---|
| Residency | ≥ 183 days in VN in calendar year / 12 consecutive months, or permanent residence | PIT Law; Circular 111/2013 |
| Personal deduction | 11,000,000 VND/month (to 2025) → 15,500,000 VND/month (from tax period 2026) | Res. 954/2020; Res. 110/2025/UBTVQH15 |
| Dependant deduction | 4,400,000 → 6,200,000 VND/month/dependant | same |
| Resident schedule (old, 7 brackets) | ≤5M 5%; 5–10M 10%; 10–18M 15%; 18–32M 20%; 32–52M 25%; 52–80M 30%; >80M 35% | PIT Law 2007 (amended) |
| Resident schedule (new, 5 brackets) | ≤10M 5%; 10–30M 10%; 30–60M 20%; 60–100M 30%; >100M 35% — applicable period per transitional provisions | PIT Law 2025 |
| Non-resident | 20% flat on gross employment income | PIT Law |
| Casual / short contract | 10% on payments ≥ 2,000,000 VND (unless 08/CK-TNCN); threshold may change under PIT Law 2025 | Circular 111/2013 Art. 25 |
| Exempt items | OT/night premium portion; mid-shift meal within cap; uniform within cap; phone/stationery per regulation; one-off relocation for expats; SI-paid benefits | Circular 111/2013 Art. 2–3; Circular 92/2015 |
| Housing benefit | Taxable up to 15% of total taxable income (excl. housing) | Circular 111/2013 |
| Declaration | 05/KK-TNCN monthly (deadline 20th of following month) or quarterly (last day of first month of next quarter) | Law on Tax Administration; Decree 126/2020 |
| Finalisation | 05/QTT-TNCN + 05-1, 05-2, 05-3/BK-QTT-TNCN; deadline last day of 3rd month after year end | Circular 80/2021 |
| E-certificates | Electronic withholding certificates, format per Decree 123/2020 as amended by Decree 70/2025 | Decree 70/2025; Circular 32/2025 |
| Personal tax code | Citizen ID (CCCD) number used as tax code from 1/7/2025 | Circular 86/2024 |

### 5.4 Labour Code rules relevant to payroll
| Rule | Summary | Legal basis |
|---|---|---|
| OT rates | ≥150% normal days, ≥200% weekly off, ≥300% holidays (excl. holiday pay); night +30%; night OT +20% of day rate | Labour Code 2019 Art. 98; Decree 145/2020 Art. 55–57 |
| Salary payment | Pay on time, in cash or bank transfer; employer bears transfer fees; late payment ≥ 15 days → compensation interest | Art. 94–97 |
| Salary statement | Employer shall notify salary statement (payslip) each payment | Art. 95.3 |
| Deductions | Only for damages, ≤ 30% of monthly salary after compulsory insurance and PIT | Art. 102 |
| Advance | Advance per agreement; 30 days of salary during military-service absence etc. | Art. 101 |
| Final settlement | Within 14 working days after termination (max 30 days in listed cases) | Art. 48 |
| Severance / job loss | Severance 0.5 month/year; job loss 1 month/year (min 2 months); basis = average of last 6 months salary | Art. 46–47 |
| Annual leave encashment | Untaken leave paid on termination or unused | Art. 113 |
| Records | Payroll registers kept, salary data provided to unions on request | Decree 145/2020 |

### 5.5 SI administration
| Rule | Summary | Legal basis |
|---|---|---|
| Contribution month exemption | No SI contribution when employee does not work & receives no salary ≥ 14 working days in month; maternity months exempt (still counted) | SI Law 2024; VSS guidance |
| Registration deadline | Register within 30 days of contract start | SI Law 2024 |
| Contribution deadline | Monthly: by last day of the month; late payment interest applies | SI Law 2024 Art. 34, 40 |
| Forms | D02-LT (increase/decrease/adjustment list), TK1-TS (participant registration), C12-TS (VSS monthly notice); template versions issued by VSS | Decision 595/QĐ-BHXH, Decision 490/QĐ-BHXH; successors under SI Law 2024 |
| Short contracts | Contracts from 1 month → compulsory SI | SI Law 2024 Art. 2 |

### 5.6 Data protection
| Rule | Summary | Legal basis |
|---|---|---|
| Salary, tax, bank, SI data are personal data (financial ⇒ sensitive) | Consent / legal basis, access logging, breach notification | Decree 13/2023; Personal Data Protection Law 2025 (eff. 1/1/2026) |

---

## 6. Key business entities (`pay` schema)

| Entity | Purpose | Key attributes |
|---|---|---|
| `legal_param_set` / `legal_param_value` | **Per-run snapshot** of the saas-platform legal pack version and the values used (master in the Legal Parameter Service) (changed 2026-10-16, R2-02 §5 #1) | run_id, pack version, scope (global/tenant/entity override), keys and values used, snapshot at |
| `pit_schedule` / `pit_bracket` | Progressive PIT schedule versions | schedule_id, effective period, lower/upper, rate, quick-calc deduction |
| `salary_component` | Catalogue of components | code, names, type, method, flags (PIT, SI, TU, prorate, gross/net), cap param ref, GL mapping ref, version |
| `formula` / `formula_version` | Expression definitions | expression, dependencies, status, author, version, effective dates |
| `salary_structure` / `structure_line` | Bundle of components per grade/group | structure_id, legal entity, grade, group, components, defaults, priority |
| `pay_group` | Payroll population & calendar | legal entity, frequency, cut-off, OT cut-off, pay date, std-days method, payer bank account (Core `legal_entity_bank_account` ref), approval route, default assignment rule (changed 2026-10-16, R2-02 §5 #5b, #25) |
| `pay_group_assignment` | Effective-dated employee → pay group link (new 2026-10-16, R2-02 §5 #25) | employment_id, pay_group_id, valid_from/to, source (rule/manual), reason |
| `pay_period` | Period per pay group | start, end, pay date, T&A period status (CLOSED/LOCKED) and current result version, status (OPEN/INPUT_CUTOFF/CLOSED) (changed 2026-10-16, X-03) |
| `employee_pay_profile` | Payroll-specific employee settings; agreed compensation, residency, TU flag, bank and tax code are cached read copies of People data (People is the owner) (changed 2026-10-16, R2-02 §5 #24; GAP-048) | employee, salary structure, cached primary employment per purpose and insurance participation per type (R2-16), cached: gross/net basis and agreed net, salary basis SI, residency, TU member, bank account ref, tax code |
| `variable_input` / `input_batch` | Per-period inputs and imports | employee, component, period, amount/qty, source, batch, status |
| `payroll_run` | Run header | type, pay group, period, status, snapshot ref, approval instance, totals, attendance holds (period, result version, hold status), stale flag, unlock info (changed 2026-10-16, X-03) |
| `payroll_result` / `result_line` | Per-employee results and per-component lines | run, employee, segment, component, qty, rate, amount, taxable, SI-basis, retro_of_period |
| `input_snapshot` | Frozen inputs for reproducibility | run, employee, payload hash, source versions |
| `si_contribution` | Monthly SI/HI/UI/OA-OD/TU per employee | period, basis, rates, EE/ER amounts, status (contribute/exempt), declaration ref |
| `si_contribution_opening` | Pre-go-live SI contribution history (new 2026-10-16, R2-02 §5 #20) | employee, month, basis, SI unit, fund flags (SI/UI), source (opening-balance import / SI book / VSS statement), import batch |
| `residency_check` | 183-day check results and proposals to HR (new 2026-10-16, GAP-048) | person, tax year, days in VN, 12-month window, computed status, People status, proposal status |
| `si_declaration` / `si_declaration_line` | D02-LT and other VSS files | entity, period, type (increase/decrease/adjust), lines, file, status, VSS reconciliation |
| `pit_withholding` | PIT per employee per month/run | income, exempt, deductions, taxable, tax, method (progressive/10%/20%) |
| `pit_declaration` | 05/KK and 05/QTT outputs | entity tax code, period, type, XML, status |
| `pit_commitment` | 08/CK-TNCN | payee, year, estimated income, valid, attachments |
| `pit_finalisation_auth` | Authorisation for finalisation | employee, year, eligible, reasons, signed on |
| `pit_certificate` | E-withholding certificates | series, number, payee, period, amounts, XML, signature, status |
| `casual_payee` / `casual_payment` | Non-employee payees and payments | ID, tax code, residency, amount, withholding, bank |
| `loan` / `loan_schedule` | Loans and advances | type, principal, interest, schedule, balance, status |
| `deduction_order` | Recurring deductions, court orders, damages | type, amount/%, cap, priority, period |
| `retro_item` | Detected retro deltas | employee, original period, component, delta, target run |
| `final_settlement` | Termination settlement | employee, termination ref, items, statement, deadline, status |
| `payslip` / `payslip_delivery` | Published payslip and delivery log | run, employee, PDF ref, publish at, e-mail status (attachment / link only), viewed at |
| `payslip_password` | Employee-set payslip PDF password (R2-17) | employee, password secret (encrypted, never displayed), set at, reset count; changes audited (new 2026-10-16, R2-17) |
| `bank_file_template` / `bank_payment_file` | Bank formats and generated files | bank, format, fields; run, file hash, lines, statuses |
| `gl_mapping` / `gl_journal` | Accounting mapping and export batches | component → accounts, segments; journal lines, export status |
| `cost_allocation` | Allocation rules and results | employee, cost centre/project, %, source, period |

---

## 7. Cross-module interfaces

Timing legend: **RT** = real-time/event at change; **D** = daily sync; **P-cut** = at payroll cut-off (snapshot on calculation); **P-close** = when the T&A period is `CLOSED` and its Attendance Result version is published (replaces the former "P-lock"; the version is held on run submit and locked by T&A on run approval — changed 2026-10-16, X-03); **On-demand** = API call by payroll.
All consumed data must be **effective-dated** (payroll calculates past periods for retro) and **as-of queryable** (value at date X as known at time Y is desirable for audit).

### 7.1 Consumes
| ID | What | From module | When | Used for / notes |
|---|---|---|---|---|
| IF-PAY-C-01 | Legal entities: name, tax code (MST), registered address, SI unit code (mã đơn vị BHXH), tax authority, PIT declaration frequency (monthly/quarterly, per tax year), OA-OD rate decision (rate, decision ref, validity), payer bank accounts | core | RT on change (`core.LegalEntityChanged`); read at P-cut | Declarations, bank files, rates. Fields are held on the Core legal entity (BR-CORE-002) (changed 2026-10-16, GAP-049; R2-02 §5 #5b). |
| IF-PAY-C-02 | Work locations with administrative unit → minimum wage **region** (I–IV), effective-dated (post-2025 provincial merger mapping) | core | RT; read at P-cut | Regional minimum wage check, UI cap, SI floor. |
| IF-PAY-C-03 | Org units, cost centres, projects, GL segment codes, effective-dated | core | RT | Cost allocation, GL export, reporting. |
| IF-PAY-C-04 | Workflow engine: approval routes, instances, decisions, delegation | core | On-demand | Run approval, unlock, loans/advances, parameter overrides. |
| IF-PAY-C-05 | Users, roles, data scopes (legal entity / pay group / confidential group), audit log service, notifications (in-app) | core | RT | Security BR-PAY-120/121, payslip notifications. |
| IF-PAY-C-06 | Public holiday calendar per legal entity / location (`att` calendar API ATT-P-16, `att.HolidayCalendarChanged`) — T&A is the single owner; Payroll-without-T&A tenants use the legal pack's national holiday template (R2-18) | time-attendance | Yearly + RT | Standard days method (b), holiday pay (changed 2026-10-16, R2-02 §5 #2; R2-18). |
| IF-PAY-C-07 | Employee master: employee ID, full name, DOB, gender, nationality, CCCD/passport (tax code), personal e-mail, employee status, hire date, seniority date | people | RT/D; read at P-cut | Population, payslip e-mail address, PIT, SI. DOB/ID are **not** used for the payslip password (R2-17). |
| IF-PAY-C-08 | Separation (`people.TerminationScheduled`, `people.EmployeeTerminated`): termination date, last working day, reason (resignation, dismissal, redundancy, contract end), legal ground, notice compliance, severance / job-loss eligibility, employment periods and already-compensated periods, training-bond repayment, outstanding cash advances, material-compensation balance, asset return status | people | RT at approval of separation; termination date | Final settlement (P5), severance/job-loss working time computed by Payroll (BR-PAY-078), D02-LT decrease. Needed ≥ 3 working days before final settlement deadline (changed 2026-10-16, R2-02 §5 #29; GAP-050). |
| IF-PAY-C-09 | Assignments: legal entity, department, position, grade/level, employee group/category, work location, cost centre/project default split, concurrent assignments, primary-employment flag, effective dates. The pay group is **not** a People field: Payroll assigns it (BR-PAY-085) | people | RT; read at P-cut | Structure resolution, pay group rule, region, cost allocation, multi-entity (changed 2026-10-16, R2-02 §5 #25; R2-16). |
| IF-PAY-C-10 | Contracts: type (indefinite, fixed-term, seasonal < 1 month, probation, service/collaborator, intern), start/end, probation salary %, working-time type (full/part-time, hourly) | people | RT | SI eligibility, PIT method (progressive vs 10%), proration. The GROSS/NET basis moved to the salary record (IF-PAY-C-11) (changed 2026-10-16, GAP-047). |
| IF-PAY-C-11 | Agreed compensation (`people.SalaryChanged`; People is the system of record): salary record id + version, **pay basis GROSS/NET, agreed net amount, net-guaranteed component scope**, base salary, currency, salary grade/step, fixed allowances (component code, amount, effective dates), declared SI salary basis (if declared separately), decision / appendix ref, salary change history with reasons | people | RT; changes with past effective date trigger retro | Earnings, net-to-gross (BR-PAY-020/021), SI basis, retro (BR-PAY-076); the version used is frozen per run (BR-PAY-072). Component codes must reference payroll catalogue (IF-PAY-P-01) (changed 2026-10-16, R2-02 §5 #24; GAP-047). |
| IF-PAY-C-12 | Dependants for PIT: name, relationship, DOB, ID/tax code, registration effective from/to, registration status with tax authority | people | RT; read at P-cut | Dependant deduction (BR-PAY-060), annex 05-3/BK-QTT. |
| IF-PAY-C-13 | Tax profile (`people.PersonalDataChanged`): effective-dated tax residency status (resident/non-resident) and basis, arrival and departure dates in VN, personal tax code | people | RT | PIT method (BR-PAY-053), 183-day check report (BR-PAY-065). 08/CK-TNCN is **not** consumed: Payroll owns it (BR-PAY-055) (changed 2026-10-16, GAP-048; R2-02 §5 #4). |
| IF-PAY-C-14 | Bank accounts: bank, branch, account number, holder name, primary/secondary split, effective date, verification status | people | RT; read at P-cut and at bank file generation | Bank file; missing → exception. |
| IF-PAY-C-15 | SI enrolment: SI number (mã số BHXH), enrolment status, exemption reason (pensioner, foreign exemption), HI registered hospital (KCB ban đầu), start date, SI book custody, and the effective-dated **participation per insurance type (SI / HI / UI / OA-OD) per employment** (BR-PPL-004, R2-16) `[verify-legal]` | people | RT | Eligibility BR-PAY-044, D02-LT/TK1-TS. SI contribution history (incl. previous employers / pre-go-live) is Payroll's own, loaded by the opening-balance import (changed 2026-10-16, R2-02 §5 #20). |
| IF-PAY-C-16 | Trade union membership flag and effective dates | people | RT | Member dues BR-PAY-046. |
| IF-PAY-C-17 | Approved expense claims to reimburse (claim ID, amount, tax treatment, cost centre) | people | Daily until P-cut | BR-PAY-094. |
| IF-PAY-C-18 | Disciplinary / damage compensation decisions with amount to deduct, and court-ordered deductions | people | RT | BR-PAY-092/093. |
| IF-PAY-C-19 | Employee work schedule / standard working pattern per day (scheduled working days & hours in period) | time-attendance | P-close (and on-demand for simulation) | Standard days method (b), hourly rate. |
| IF-PAY-C-20 | **Closed period Attendance Result** (versioned) per employee per period: scheduled days, actual worked days, paid days, paid leave days by leave type, public holidays paid, unpaid leave days, unauthorised absence days, suspension days, late/early minutes (if penalised) | time-attendance | **P-close** (`att.AttendancePeriodClosed(version)`), required for `FINAL` calculation and held on submit; the T&A **close** deadline must be ≥ 2 working days before payroll approval (changed 2026-10-16, X-03) | Proration, paid days, SI 14-day rule. |
| IF-PAY-C-21 | OT hours by category: weekday, weekly rest day, public holiday, **night OT** per each, plus normal-hour night work hours; with approval status (only approved OT) and compensatory-leave conversion (hours converted to time-off are not paid) | time-attendance | P-close | OT pay BR-PAY-025/026, PIT-exempt portion. Also monthly/yearly OT totals for legal cap reporting. |
| IF-PAY-C-22 | SI-paid leave days: sickness, child sickness, maternity, pregnancy check-up, miscarriage, OA-OD treatment, convalescence, with date ranges and working-day counts | time-attendance | P-close | Exclude from employer pay, SI contribution exemption (≥ 14 working days), maternity exemption, SI benefit claim data (Phase 2). |
| IF-PAY-C-23 | Shift-based quantities: shift counts by shift type, hazardous-work days, on-call hours, meal counts (if meal allowance per worked day) | time-attendance | P-close | Shift / meal / hazard allowances. |
| IF-PAY-C-24 | Leave balance: annual leave balance to encash at termination or year end (days, and reference daily rate basis if T&A computes) | time-attendance | On termination (P5) and year-end | Leave encashment BR-PAY-077. |
| IF-PAY-C-25 | **Retro corrections** after lock: per employee per `LOCKED` period, **delta lines** (authoritative) with reason and approver, carried in the next `OPEN` period's Attendance Result, or in an **off-cycle Attendance Result** for leavers with no open period; `att.RetroDeltaCreated` RT for visibility (changed 2026-10-16, X-03; GAP-028) | time-attendance | RT event + next Attendance Result / off-cycle result | Retro pay BR-PAY-076; off-cycle result feeds final-settlement / correction runs. T&A keeps restated versions for audit. |
| IF-PAY-C-26 | Project hours by employee per period (for time-based cost allocation) | time-attendance | P-close | BR-PAY-114 (`S`). |
| IF-PAY-C-27 | Attendance period status events: `att.AttendancePeriodClosed(version)`, `att.AttendancePeriodReopened`, `att.AttendancePeriodHoldGranted`, `att.AttendancePeriodHoldReleased`, `att.AttendancePeriodLocked` (changed 2026-10-16, X-03) | time-attendance | RT | Stale detection (re-open → run stale), hold confirmation, lock confirmation, reconciliation (BR-PAY-084). |
| IF-PAY-C-28 | New hire salary from accepted offer (only if People not yet created) — normally via People | recruitment → people | n/a (indirect) | Payroll does not consume from Recruitment directly. |
| IF-PAY-C-29 | Legal Parameter Service: `getParam(key, scope, asOfDate)` → value + pack version (rates, caps, minimum wages and region map, PIT schedules, deductions, casual threshold, settlement deadline, form template versions); `saas.LegalParameterPackPublished` | saas-platform | On publication (effective-dated); sync read at calculation | BR-PAY-010; each run snapshots the pack version (changed 2026-10-16, R2-02 §5 #1). |
| IF-PAY-C-30 | Tenant/legal-entity subscription entitlements (payroll module enabled, employee count limits) | saas-platform | RT | Licence enforcement. |
| IF-PAY-C-31 | Notification centre (e-mail incl. encrypted payslip attachment) and document service (PDF storage, generation) from **core** (P-14, P-21); digital signature / HSM for e-certificates & declarations and per-tenant encryption keys from **saas-platform** | core; saas-platform | On-demand | Payslips, e-certificates, encryption (changed 2026-10-16, R2-02 §5 #14, #15b). |
| IF-PAY-C-32 | Background job scheduler / queue with progress tracking for large runs | saas-platform | On-demand | NFR-PAY-001. |
| IF-PAY-C-33 | Exchange rates (currency pair, rate type, rate date, source) | core (P-26, BR-CORE-088) | Daily / at rate date | BR-PAY-013 (changed 2026-10-16, R2-02 §5 #19). |
| IF-PAY-C-34 | **Close-hold API** (T&A ATT-P-17): `acquireHold(period, version, runId)`, `releaseHold(period, runId, reason)`, `getHold(period)`; synchronous, idempotent on run id + version (new 2026-10-16, X-03) | time-attendance | On run submit (acquire); on rejection, withdrawal, cancellation, T&A release request, unlock (release) | BR-PAY-082/083. Refused acquire → submit fails. |
| IF-PAY-C-41 | `people.PrimaryEmploymentChanged` (person_id, employment_id, purpose PIT_FAMILY_DEDUCTION / SOCIAL_INSURANCE, valid_from/to) | people | RT; read at P-cut | Family deduction and SI/UI employer per period (BR-PAY-081); a change in a closed period triggers retro (new 2026-10-16, R2-16) |
| IF-PAY-C-35 | `people.RewardApproved` (amount, target pay period, taxable flag, component code) | people | RT; applied at P-cut | Reward variable input (BR-PAY-086) (new 2026-10-16, GAP-050). |
| IF-PAY-C-36 | `people.ContractChanged(SUSPENDED / RESUMED)` (reason, from/to) | people | RT | Stop/resume pay and SI for the suspension period; D02-LT (BR-PAY-086) (new 2026-10-16, GAP-050). |
| IF-PAY-C-37 | `people.TemporaryTransferChanged` (days used in year, protected salary until date) | people | RT | Protected-salary component (BR-PAY-086) (new 2026-10-16, GAP-050). |
| IF-PAY-C-38 | `people.SecondmentChanged` (home / host / paying legal entity, recharge flag, from/to) | people | RT | Paying legal entity and pay group for the period (BR-PAY-085/086) (new 2026-10-16, GAP-050). |
| IF-PAY-C-39 | `people.AppraisalFinalised` / periodic KPI score (period, score, rating) | people | Cycle close / monthly | KPI and bonus formula input (BR-PAY-086; S) (new 2026-10-16, GAP-050). |
| IF-PAY-C-40 | `people.AdvanceDeductionRequested` (cash advance; amount, period, consent ref) | people | Overdue advance / at termination | Deduction order in the next run or final settlement (BR-PAY-077/086); replaces the orphan People OUT-29 (new 2026-10-16, GAP-050; R2-02 §5 #21). |

### 7.2 Provides
| ID | What | To module | When | Notes |
|---|---|---|---|---|
| IF-PAY-P-01 | Salary component catalogue (codes, names, types, which are "contract allowances" enterable in People) | people, recruitment | RT | People compensation must reference these codes; recruitment offer templates use them. |
| IF-PAY-P-02 | Pay scales (thang bảng lương: grade × step amount/coefficient, step interval), grade salary bands (min-mid-max), salary structures, default allowances — `pay.CompensationCatalogueChanged`. Payroll is the single owner; Core holds only the grade catalogue. **Baseline capability, always on** whatever modules the tenant buys (R2-18) | people, recruitment | RT | Default compensation on hire/promotion; salary and offer validation (changed 2026-10-16, R2-02 §5 #7; R2-18). |
| IF-PAY-P-03 | Payroll results summary per employee per period (gross, net, SI basis, employer cost) | people (employee 360 view, appraisal/merit), saas-platform (reporting store) | On run approval | Restricted by data scope (changed 2026-10-16, R2-02 §5 #18). |
| IF-PAY-P-04 | Average salary of last 6 months and payroll history | people (separation display) | On-demand | T&A removed as consumer (T&A computes no money) (changed 2026-10-16, R2-02 §5 #29). |
| IF-PAY-P-05 | Payroll period calendar `pay.PayCalendarPublished` (attendance cut-off, optional OT cut-off, **T&A close deadline**, approval deadline, pay date, std-day method) per pay group (changed 2026-10-16, X-03) | time-attendance, core (notifications) | On period creation | T&A uses it to schedule the close; reminders. |
| IF-PAY-P-06 | Payroll run status events `pay.PayrollRunStatusChanged` (CALCULATED, SUBMITTED, APPROVED, REJECTED, PAID, CLOSED, UNLOCKED, REVERSED) with **attendance versions consumed** and People watermark (changed 2026-10-16, X-03) | time-attendance, people, core | RT | On APPROVED T&A locks the held version; on UNLOCKED T&A returns `LOCKED → CLOSED`; changes to a `LOCKED` period become retro deltas (IF-PAY-C-25). People warned when editing back-dated compensation in locked periods. |
| IF-PAY-P-07 | Hourly/daily rate per employee (for OT valuation previews, leave encashment) | time-attendance | On-demand | Optional; payroll remains the system of record for money. |
| IF-PAY-P-08 | SI contribution status per employee per month, SI salary basis history incl. pre-go-live months, D02-LT line status, and a **contribution-years API** (`pay.SiContributionPosted`) | people (SI book/profile), time-attendance (sick-leave entitlement, ATT-C-20), saas-platform (reports) | On run approval; on request | Payroll owns all SI contribution history; People shows it; supports SI book closing at termination (changed 2026-10-16, R2-02 §5 #20). |
| IF-PAY-P-09 | Loan/advance balances outstanding | people (separation checklist) | On-demand / on separation | Final settlement clearance. |
| IF-PAY-P-10 | Final settlement statement and status (`pay.FinalSettlementStatusChanged`: amounts, severance working time and base, deadline, paid date) | people | On approval / payment | Closes separation process; deadline monitoring (Payroll owns the deadline clock) (changed 2026-10-16, R2-02 §5 #29). |
| IF-PAY-P-11 | Payroll ESS pages: payslips, payslip password, PIT certificates, annual income summary, loan statements, 08/CK-TNCN and finalisation authorisation | saas-platform (ESS/MSS shell; responsive web in Phase 1, mobile app Phase 2 — OD-9/X-04) | On publish | SaaS owns the shell; Payroll owns these pages and APIs (changed 2026-10-16, R2-02 §5 #4, #17). |
| IF-PAY-P-12 | Approval tasks (run, unlock, advance, loan, parameter override) | core (workflow inbox) | RT | |
| IF-PAY-P-13 | Audit events | core (audit) | RT | |
| IF-PAY-P-14 | Notifications (payslip published, approval pending, exception alerts, 08/CK expiry) | core (notification service) | RT | |
| IF-PAY-P-15 | Salary cost estimate API (gross↔net, employer cost) | recruitment (offers), people (compensation change simulation, NET validation) | On-demand | BR-PAY-124. Core removed as consumer (Core holds no cost budget) (changed 2026-10-16, R2-02 §5 #8). **Always-on, API-level baseline service** once built, whatever modules the tenant buys (R2-18, R2-C9); its phase follows BR-PAY-124 in mvp-scope.md §9 (P2). |
| IF-PAY-P-16 | Personnel cost actuals by cost centre/project/period | saas-platform (reporting store), recruitment (Phase 2 staffing plan cost budget vs. actual) | On run approval | Core removed as consumer (changed 2026-10-16, R2-02 §5 #8, #18). |
| IF-PAY-P-17 | Usage metrics (payslips processed, employees paid per month) | saas-platform (billing) | Monthly | Informational. The billable unit is the active person at month end **per legal entity** (decision D-10), counted from People; payroll counts are used for reconciliation only (changed 2026-10-16, D-10). |
| IF-PAY-P-18 | Bank payment files, GL journals, VSS files, PIT XMLs | external (bank, ERP, VSS, GDT) via saas-platform integration/file service | On generation | Phase 1: file exports uploaded by the customer; live APIs are Phase 2 (R2-01). |
| IF-PAY-P-19 | `pay.PayGroupAssigned` (employment, pay group, effective date, source) | time-attendance, people (onboarding readiness) | RT on rule or manual assignment | BR-PAY-085 (new 2026-10-16, R2-02 §5 #25). |
| IF-PAY-P-20 | Legal-entity lock state as of date (latest locked period across all pay groups of the entity, unlock in progress) | core | Sync query | BR-PAY-087; Core C-16, BR-CORE-015 (new 2026-10-16, GAP-049). |
| IF-PAY-P-21 | 183-day residency check proposals (person, year, days in VN, proposed status) | people | Monthly / on request | BR-PAY-065; People IN-32 (new 2026-10-16, GAP-048). |

### 7.3 Timing summary (monthly cycle, example cut-off 25th, pay date 5th)
| Day | Event | Owner |
|---|---|---|
| Period end (25th) | Cut-off for People changes affecting the period; variable inputs deadline | people / payroll |
| +2 working days | T&A close deadline: period `CLOSED`, Attendance Result version published, IF-PAY-C-20..27 available (changed 2026-10-16, X-03) | time-attendance |
| +3 | Input cut-off, `FINAL` calculation, exceptions resolved (run `CALCULATED`) | payroll |
| +3 … +4 | Run submitted → close hold granted on the result version(s) | payroll / time-attendance |
| +5 | Approvals complete (run `APPROVED`) → T&A period `LOCKED`; bank file sent | payroll / core workflow / time-attendance |
| Pay date | Salary paid, payslips published | payroll |
| By 20th of following month | 05/KK-TNCN (monthly filers) | payroll |
| By last day of month | SI/HI/UI contribution paid | payroll / finance |

---

## 8. Reports & analytics

| Report | Description | Pri |
|---|---|---|
| Payroll register (bảng lương) | Per run, per employee, all components; Excel export with configurable columns; signature block | M |
| Payroll summary by legal entity / department / cost centre / project | Totals by component, headcount | M |
| Variance report | Period-over-period per employee/component with threshold highlighting | M |
| Exception report | Validation results (BR-PAY-074) | M |
| SI/HI/UI/TU contribution report | Per employee basis, rates, EE/ER, exempt reason; reconciliation vs. VSS C12-TS | M |
| D02-LT and related VSS outputs | Declaration files and listings | M |
| PIT monthly report & 05/KK-TNCN | Per employee taxable income, deductions, PIT; XML | M |
| PIT finalisation package | 05/QTT-TNCN + annexes; per-employee annual reconciliation (refund/payable) | M |
| PIT certificate register | Issued/cancelled certificates by series | M |
| Casual income & withholding register | 10%/20% payees, 08/CK status | M |
| Bank payment summary / reconciliation | Per bank file, statuses | M |
| GL journal report | Debit/credit by account and segment | M |
| Labour cost report | Gross + employer contributions + TU by cost centre/project, trend | M |
| Retro report | Deltas by original period/component | M |
| Loan/advance ledger | Balances and movements | S |
| Final settlement statement | Per employee, Vietnamese/English | M |
| Minimum wage compliance report | Employees near/below regional minimum | S |
| OT cost report | OT cost by category, department, vs. legal caps (hours from T&A) | S |
| Year-to-date earnings statement | Per employee | M |
| Analytics dashboard | Payroll cost trend, headcount vs cost, average salary by grade, gender pay gap indicator | C |

All reports respect data scopes (BR-PAY-120), are exportable to Excel/PDF, and are run against approved results unless "provisional" is explicitly selected.

---

## 9. Non-functional requirements

| ID | Requirement | Pri |
|---|---|---|
| NFR-PAY-001 | Calculation performance: a regular run of 10,000 employees shall complete in ≤ 15 minutes; single-employee recalculation ≤ 5 seconds; runs execute asynchronously with progress indicator and do not degrade other tenants (per-tenant job quotas). | M |
| NFR-PAY-002 | Accuracy & determinism: identical inputs + parameter/formula versions produce identical results to the VND; monetary arithmetic uses fixed-point decimal (no floating point). | M |
| NFR-PAY-003 | Reproducibility: any approved run can be recomputed from its snapshot and yields the same results for ≥ 10 years. | M |
| NFR-PAY-004 | Confidentiality (changed 2026-10-16, X-01): all data encrypted at rest (storage level) and in transit; bank accounts and tax/SI identifiers column-encrypted with tenant keys; salary amounts are NOT column-encrypted but protected by RLS, module DB roles, masking views and access audit; payslip PDFs password-protected; masked display of bank accounts. | M |
| NFR-PAY-005 | Tenant isolation: payroll data never crosses tenant boundaries (row-level security / schema checks) including in background jobs and file exports. | M |
| NFR-PAY-006 | Auditability: immutable audit log of configuration, inputs, status changes, file generation, unlocks; retained per BR-PAY-123. | M |
| NFR-PAY-007 | Legal agility: a new legal parameter set (e.g. minimum wage change) shall be deployable by configuration without code release; a new PIT schedule structure shall need no schema change. | M |
| NFR-PAY-008 | Regression test pack: automated golden-file test suite of ≥ 200 payroll scenarios (GROSS/NET, residents/non-residents, proration, caps, OT, retro, termination) verified by a VN payroll consultant, run on every release and every parameter change. | M |
| NFR-PAY-009 | Availability: payroll calculation and payslip ESS available 99.5% monthly; peak capacity planned for days 1–10 of each month and Tết period. | M |
| NFR-PAY-010 | File compatibility: generated XML/Excel files validated against current HTKK/eTax/VSS and bank templates; template versions configurable without release. | M |
| NFR-PAY-011 | Localisation: VN/EN UI and outputs; VND formatting (thousand separator ".", no decimals) per user locale; Vietnamese diacritics in bank files per bank rule (some banks require no diacritics). | M |
| NFR-PAY-012 | Usability: payroll admin can complete monthly run for 500 employees (no exceptions) in < 30 minutes of hands-on time. | S |
| NFR-PAY-013 | Scalability: support tenants up to 10,000 employees per legal entity and 50 legal entities per tenant. | S |

---

## 10. Assumptions, open questions, risks

### 10.1 Assumptions
| # | Assumption |
|---|---|
| A1 | Payroll is the system of record for money (rates, results, payments); People is the system of record for the agreed compensation and personal/tax/SI master data; T&A for time quantities. |
| A2 | MVP supports monthly frequency only; semi-monthly/weekly are Phase 2+. |
| A3 | MVP produces files for manual upload to VSS (VssID/I-VAN) and eTax/HTKK; direct T-VAN API in Phase 2. |
| A4 | The platform maintains national legal parameter packs centrally in the saas-platform Legal Parameter Service; tenants cannot alter national values except permitted overrides; Payroll snapshots the pack version per run (changed 2026-10-16, R2-02 §5 #1). |
| A7 | Baseline capabilities always on (R2-18): pay scales/bands and the component catalogue are available to People and Recruitment even when the payroll module is not subscribed; a tenant with Payroll but without T&A feeds attendance through the Excel attendance import, which produces a standard Attendance Result (changed 2026-10-16, R2-18). |
| A5 | The pilot tenant can supply opening balances in the Excel templates (current tax-year YTD income/PIT/SI, SI contribution history, at least 6 months of salary history, loan balances); there is no Oracle migration (changed 2026-10-16, D-09). |
| A6 | Bank host-to-host integration is out of MVP; file upload to internet banking is in scope. |

### 10.2 Open questions
| # | Question | To |
|---|---|---|
| Q1 | Exact transitional application of PIT Law 2025 for employment income (5-bracket schedule from 1/1/2026 or 1/7/2026?) and new casual withholding threshold. | Tax consultant |
| Q2 | Reference level (mức tham chiếu) value and its effective date replacing base salary for SI/HI caps and union dues cap in 2026. | Legal consultant |
| Q3 | Updated VSS form set and XML schemas issued under SI Law 2024 (successor to Decision 595/490). | Legal consultant / VSS |
| Q4 | ~~Who owns the public holiday calendar?~~ **Closed 2026-10-16 (R2-02 §5 #2):** T&A; Payroll reads the `att` calendar API (IF-PAY-C-06). | Integration analyst |
| Q5 | ~~Who owns 08/CK-TNCN and dependant registration capture?~~ **Closed 2026-10-16 (R2-02 §5 #3, #4):** People owns dependants (registration, approval, `DependantChanged`); Payroll owns 08/CK-TNCN and finalisation authorisation (Payroll ESS pages). | Integration analyst |
| Q6 | ~~Who hosts ESS?~~ **Closed 2026-10-16 (R2-02 §5 #17):** saas-platform owns the ESS/MSS shell; Payroll owns its payslip/PIT pages and APIs. | PM / Architect |
| Q7 | Does T&A provide versioned period summaries with deltas for retro (IF-PAY-C-25) or must payroll compute deltas by comparing restated summaries? **Closed 2026-10-16 (X-03, GAP-028):** T&A deltas are authoritative; restated versions are kept for audit only. | T&A BA |
| Q8 | ~~Do we need concurrent assignments across legal entities in MVP?~~ **Closed 2026-10-16 (R2-16):** yes, basic support — primary employment carries the PIT family deduction and SI; each entity withholds PIT on its own payments (BR-PAY-081). The SI/HI/UI/OA-OD rule for several contracts is the legal parameter `insurance.multi_contract_rule` (defaults FIRST_CONTRACT for SI/UI/HI, EACH_EMPLOYER for OA-OD; changed 2026-10-16, R3-03) and remains `[verify-legal]`. | PM / People BA |
| Q9 | Which banks are mandatory for the pilot tenant's bank files? | Pilot customer |
| Q10 | Is project-hour cost allocation required in MVP (IF-PAY-C-26)? | PM |
| Q11 | Should union fee apply to entities without a grassroots union under Trade Union Law 2024 rules, and at what basis? | Legal consultant |

### 10.3 Risks
| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Frequent legal changes in 2025–2026 (SI Law 2024, PIT Law 2025, new deductions, minimum wages, provincial merger) | Wrong calculations, compliance penalties | Effective-dated parameters (BR-PAY-010), legal content admin role, golden tests (NFR-PAY-008), consultant sign-off |
| R2 | Late T&A close, or a re-open request on a held period (changed 2026-10-16, X-03) | Delayed payroll | Payroll calendar (IF-PAY-P-05), close deadline alerts, provisional runs, hold release with stale flag (BR-PAY-083) |
| R3 | Formula engine misuse by tenants creates wrong results | Mis-payments | Test mode, versioning, templates, approval of formula changes |
| R4 | Retro complexity across modules (People back-dated changes, T&A corrections) | Inconsistent results | Effective-dated, versioned inputs in all modules; retro detection service; clear ownership in gap register |
| R5 | Bank/VSS/GDT format changes | Rejected files | Configurable templates (NFR-PAY-010) |
| R6 | Data breach of salary data | Legal and reputational damage | NFR-PAY-004/005, data-scope RBAC, audit |
| R7 | Opening-balance quality (YTD, SI history, salary history) (changed 2026-10-16, D-09) | Wrong PIT finalisation, severance | Template validation, reconciliation report against the tenant's last payroll register with tenant sign-off (BR-PAY-122); first live run compared with the tenant's previous payslips |
| R8 | Net-to-gross edge cases (caps, bracket boundaries, multiple runs per month) | Net deviation | Convergence tests, documented method |
