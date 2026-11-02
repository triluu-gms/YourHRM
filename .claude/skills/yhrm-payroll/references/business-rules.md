# Payroll BR and NFR index

**Count:** 91 BR-PAY and 13 NFR-PAY, 104 in total.
- BRs by phase: 71 P1, 17 P2 and 3 P3. This matches mvp-scope §3.1.
- NFRs by phase: 11 P1 and 2 P2.

**Sources:**
- Rules come from BRD `docs/01-requirements/brd/modules/04-payroll.md` v0.3 §4 and §9.
- MoSCoW is the BRD's business priority. `new` marks rows that were added under R2-02.
- **Phase** is authoritative from `mvp-scope.md` §9.4. A note gives the reason whenever the phase differs from the MoSCoW or applies only partly.

Values marked `[verify-legal]` are examples only. Read them through LPS and never hard-code them.

## 4.1 Configuration, parameters and components
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-001 | Keep a per-tenant component catalogue. Each component has a code, VN/EN name, type (earning, allowance, deduction, employer contribution, info), method (fixed, formula, manual, import, T&A), currency, rounding and payslip group/order. | M | P1 |
| BR-PAY-002 | Each component has **independent** flags: PIT treatment (taxable, exempt, or partially exempt with a cap), SI basis, KPCĐ basis, prorated (and its basis), GROSS/NET scope, and whether it counts toward the 13th-month, leave-pay and severance bases. | M | P1 |
| BR-PAY-003 | A partially exempt allowance references an effective-dated cap parameter, and any excess becomes taxable automatically. Examples are meals, uniform (a YTD cap), company-policy phone/fuel and housing at 15% `[verify-legal]`. | M | P1 |
| BR-PAY-004 | Components are effective-dated and versioned. A change never alters an approved run. | M | P1 |
| BR-PAY-005 | Provide a sandboxed, tenant-safe formula engine. It supports arithmetic, IF/CASE, MIN/MAX/ROUND, lookups, and references to components, employee attributes, T&A quantities, parameters and YTD values. Dependencies are resolved automatically, and a cycle is rejected when the formula is saved. | M | P1 (YFL, ADR-0008) |
| BR-PAY-006 | Formula IDE: validation, syntax highlighting, a test mode showing intermediate values, and version diff. | M | **P2** (P1 has syntax validation inside BR-PAY-005, X-04) |
| BR-PAY-007 | Salary structures bundle components with defaults and can be assigned by legal entity, grade, employee group or contract type, with a precedence rule when several match. | M | P1 |
| BR-PAY-008 | A pay group defines its legal entity, frequency, period dates (non-calendar cut-off allowed), OT cut-off, pay date, std-day method, payer bank account (Core `legal_entity_bank_account`), approval flow and payslip template. Payroll owns pay groups and the pay calendar (`pay.PayCalendarPublished`). | M | P1 (monthly only; semi-monthly is C/P2) |
| BR-PAY-009 | The standard-day method is configured per pay group: fixed days, scheduled working days, calendar days or fixed hours. Paid holidays count as paid days. Payroll is the sole owner of the method, and T&A computes `STD_DAYS`/`STD_HOURS` from it. | M | P1 |
| BR-PAY-010 | All legal parameters are stored and published by the SaaS LPS and read as of a date (`getParam`). Tenant or legal-entity overrides are allowed only where the law allows them. Payroll snapshots the pack version and the values it used for each run. | M | P1 |
| BR-PAY-011 | Ship VN configuration templates: component catalogue, office and factory structures, and GL maps under TT200/133. | S | P2 |
| BR-PAY-012 | Rounding is configurable per component and for net pay, and rounding differences are traceable. | M | P1 |
| BR-PAY-013 | Multi-currency contracts are converted to VND at a configurable FX type and date taken from Core, and the rate is stored on the result. SI and PIT are always computed in VND. | S | P2 (R2-09) |

## 4.2 Gross/net and earnings
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-020 | Support GROSS and NET agreements, read from the People salary record (`pay_basis`, `agreed_net_amount`). For NET, gross up so that the computed net equals the agreed net after employee SI, union fee and PIT. | M | P1 |
| BR-PAY-021 | Net-to-gross scope is configurable: which components are net-guaranteed, set per employee from People, and whether the employer bears SI on the gross-up. The result converges within 1 VND. | M | **P2** (Plan B, R2-19). P1 uses a fixed net scope: recurring components are net, bonus and OT are gross. |
| BR-PAY-022 | Prorate joiners and leavers as paid days ÷ standard days for prorated components. Non-prorated components follow their rule: full, none or a threshold. | M | P1 |
| BR-PAY-023 | Unpaid leave, unauthorised absence and suspension days from T&A reduce prorated components, by day or by hour. | M | P1 |
| BR-PAY-024 | A mid-period change of salary, an allowance or a legal parameter splits the period into effective-dated segments. Each segment is prorated, and the segments are shown on the payslip. | M | P1 |
| BR-PAY-025 | OT pay is computed from T&A hours by type, using configurable multipliers that are never below the statutory minimums: 150%, 200%, 300%, night +30% and night OT +20% `[verify-legal]`. | M | P1 |
| BR-PAY-026 | The OT rate basis is configurable. Compute the PIT-exempt part of OT and night pay, which is the premium above the normal rate. | M | P1 |
| BR-PAY-027 | Check full-month normal pay against the regional minimum wage for the workplace, and the hourly minimum for hourly staff. A violation is an exception and is never auto-corrected. | M | P1 |
| BR-PAY-028 | Support shift, attendance, hazard, responsibility, seniority and KPI/commission pay as formula or manual components. | M | P1 |
| BR-PAY-029 | Variable inputs can be imported through Excel/CSV with validation, and through an API. Imports are versioned and can be rolled back before lock. | M | P1 (the API part is P2) |
| BR-PAY-030 | The employer does not pay sick or maternity days that BHXH pays, unless tenant policy tops them up. These days are excluded from paid days and feed the SI status logic. | M | P1 |
| BR-PAY-031 | Paid leave and holidays are paid on the configured basis, per the Labour Code. | M | P1 |

## 4.3 Mandatory insurance and trade union
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-040 | Compute employee and employer BHXH (retirement and death, sickness and maternity), BHYT, BHTN and TNLĐ-BNN at effective-dated rates. | M | P1 |
| BR-PAY-041 | The SI salary comes from People data: contract salary plus allowances flagged as SI basis, or a separately declared SI salary. It must be at least the regional minimum wage. It is capped at 20 × the reference level for SI/HI and 20 × the regional minimum for UI `[verify-legal]`. | M | P1 |
| BR-PAY-042 | The monthly SI salary is the amount declared to VSS, not the prorated pay, unless it changed during the month. | M | P1 |
| BR-PAY-043 | Determine each employee's monthly SI obligation. Unpaid for at least 14 working days means no SI/HI/UI. Maternity months are exempt. Joiner and leaver rules follow VSS `[verify-legal]`. | M | P1 |
| BR-PAY-044 | SI is compulsory for contracts of at least 1 month, with configurable exemptions such as pensioners, intra-company transferees or retirement age. Foreign-worker rates are configurable. Eligibility comes from the People SI registration. | M | P1 |
| BR-PAY-045 | The TNLĐ-BNN rate is read as of a date from the Core legal-entity rate decision and validated against the legal pack. With no decision, the standard rate applies. | M | P1 |
| BR-PAY-046 | Employer KPCĐ is 2% of the SI payroll per legal entity where the law requires it. Employee đoàn phí applies only to flagged members, at 1% with a cap of 10% of the reference level `[verify-legal]`. | M | P1 |
| BR-PAY-047 | Produce VSS data: D02-LT increase/decrease/adjust, TK1-TS, and C12-TS reconciliation. Export to Excel/XML in the configured form version. | M | P1 (file only; the customer uploads it) |
| BR-PAY-048 | Retro SI adjustments produce lines per origin month with an estimate of late-payment interest. | S | P2 |
| BR-PAY-049 | Track SI arrears when net pay can't cover the employee share, and recover them in later runs. | S | P2 |

## 4.4 Personal income tax (PIT)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-050 | For residents, apply progressive brackets to monthly assessable income after exempt income, employee SI, self and dependant deductions, and other allowed deductions `[verify-legal]`. | M | P1 |
| BR-PAY-051 | The PIT schedule is fully parameterised. Support at least the 7-bracket 5–35% schedule and the 5-bracket 5/10/20/30/35% schedule. The new deductions are 15.5 M self and 6.2 M per dependant from 2026 `[verify-legal]`. | M | P1 |
| BR-PAY-052 | Several payments in one month are taxed on the month-cumulative assessable income. The deduction is applied once, and tax already withheld is subtracted. Payment-month vs accrual-month is chosen per run type. | M | P1 |
| BR-PAY-053 | Non-residents (People residency, IF-PAY-C-13) pay a flat 20% on gross with no deductions. A mid-year residency change is handled by a recalculation report. | M | P1 |
| BR-PAY-054 | Casual income (service contracts, labour contracts under 3 months, non-employees): withhold 10% on each payment at or above the threshold unless a valid 08/CK-TNCN is on file. Non-residents pay 20%. | M | P1 |
| BR-PAY-055 | Payroll **owns** 08/CK-TNCN. It records commitments through ESS or an admin, tracks them per payee per year, warns when the cumulative amount passes the threshold, and switches to withholding automatically. | M | P1 |
| BR-PAY-056 | Produce 05/KK-TNCN monthly or quarterly per legal-entity tax code and tax authority, as HTKK/eTax XML. The frequency comes from the Core legal entity. | M | P1 |
| BR-PAY-057 | Annual finalisation: recalculate each employee's annual PIT, determine the refund or extra tax, and produce 05/QTT-TNCN with annexes 05-1, 05-2 and 05-3 `[verify-legal]`. | M | P1 |
| BR-PAY-058 | Assess each employee's eligibility to authorise finalisation (contract of at least 3 months, still employed, income conditions), and collect e-authorisation through ESS. | M | P1 |
| BR-PAY-059 | Issue e-PIT withholding certificates with gap-free numbering per legal entity and symbol, as XML and PDF. P1 produces the data and an **unsigned** PDF, and the customer signs through their own tool. Signing and transmission are P2. | M | P1 (partial, R3-02) |
| BR-PAY-060 | The dependant deduction applies only from the month the dependant registration takes effect (with a tax code where required) to its end month. Late registration is handled at finalisation. | M | P1 |
| BR-PAY-061 | Validate the tax code (the CCCD serves as tax code from 1/7/2025). A missing or invalid code is an exception and blocks e-certificate issuance. | M | P1 |
| BR-PAY-062 | Employer-borne PIT (NET contracts) and benefits in kind are added to taxable income according to their configured treatment. | M | P1 |
| BR-PAY-063 | Record taxable income from a prior employer in the same group during the year, for finalisation. | S | P2 |
| BR-PAY-064 | Retro PIT is configurable: tax in the payment month (the default), or recalculate the origin months with adjusted declarations. | S | P2 (P1 uses the payment month) |
| BR-PAY-065 | Run a 183-day residency check monthly and on demand. Count days in VN for foreign and seconded staff, flag mismatches with People, and send **recommendations** to HR. Never change residency directly. | new | P1 |

## 4.5 Runs, lifecycle and controls
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-070 | The run types are regular, off-cycle, bonus, 13th month, Tết bonus, correction, termination, advance, casual and PIT-finalisation adjustment. Each type defines its enabled components, PIT method and approval flow. | M | P1 (Tết/13th eligibility and proration are P2, R2-14) |
| BR-PAY-071 | The run lifecycle is DRAFT→CALCULATED→SUBMITTED→APPROVED→PAID→CLOSED, with REJECTED and UNLOCKED going back to DRAFT, and REVERSED. An APPROVED run is read-only. Unlock needs a privileged role, a reason and a second approval, and returns the T&A period from LOCKED to CLOSED. | M | P1 |
| BR-PAY-072 | Snapshot every input (People salary-record versions, T&A, legal pack, formula versions) so the run can be reproduced, and show what changed on recalculation. The salary used by an approved run is frozen, and later People edits become retro. | M | P1 |
| BR-PAY-073 | Calculate for everyone, a filtered subset or one employee, and support simulation mode. | M | **P2** (Plan B, R2-19). P1 runs the whole group and uses a CALCULATED run as the preview. |
| BR-PAY-074 | Before submit, produce an exception report. Checks: net ≤ 0 or below a set %, missing bank account, tax code or SI number, below minimum wage, period variance over a threshold, mismatches between the People population and the run, attendance not CLOSED or stale, and paid leavers. Each check can be set to block or warn. | M | P1 |
| BR-PAY-075 | Approval goes through the Core workflow, with multi-level flows per legal entity and run type. Approvers see totals and variances within their data scope. | M | P1 |
| BR-PAY-076 | Retro: detect retroactive changes to locked periods automatically, compute per-period and per-component deltas, and pay them in the next open run with a retro report. | M | P1 |
| BR-PAY-077 | A termination run computes prorated pay, untaken leave, severance (0.5 month per year not covered by UI) or job-loss pay, notice compensation, loan and advance recovery, cash-advance deduction, training refund, damage balance, prorated 13th month and PIT, and produces a settlement sheet `[verify-legal]`. | M | P1 (the prorated 13th month is P2, R2-14) |
| BR-PAY-078 | Payroll is the sole owner of severance/job-loss service and amount. The basis is the 6-month average contract salary. Service = People periods − periods already paid − UI months from Payroll's own SI history, including opening months. Publish the result to People (IF-PAY-P-10). | M | P1 |
| BR-PAY-079 | Reversing a paid run creates a fully audited reversal run and deletes nothing. | S | P2 |
| BR-PAY-080 | Block closing a pay period until bank payment, GL export and SI/PIT liabilities are done (configurable). | S | P2 |
| BR-PAY-081 | Multi-entity: each pay group belongs to one legal entity. For concurrent employments (R2-16), each legal entity withholds PIT on its own payments, and only the `PIT_FAMILY_DEDUCTION` primary employment applies family deductions. Insurance is decided per type × employment through `insurance.multi_contract_rule`: FIRST_CONTRACT, HIGHEST_SALARY or EACH_EMPLOYER `[verify-legal]`. Flag people with 0 or 2 primaries. | M | P1 (cross-entity OT caps are P2, R2-08) |
| BR-PAY-082 | On submit, take a close hold through the T&A API on every Attendance Result version in the snapshot, idempotent on run id + version. Submit fails if a period is not CLOSED, a version is not current, or T&A is unavailable. Termination and correction runs hold their own results. | M | P1 |
| BR-PAY-083 | Release the hold whenever the run leaves SUBMITTED without approval: reject, withdraw, cancel, a T&A release request (which makes the run stale) or unlock. Resubmitting takes the hold again. Every release has a reason and is audited. | M | P1 |
| BR-PAY-084 | Approve only if every period is CLOSED with an active hold by this run on the current version, and no stale or input-changed flag is open. Approval publishes `pay.PayrollRunStatusChanged(APPROVED, versions)` and is never rolled back. A nightly report reconciles run and period states. | M | P1 |
| BR-PAY-085 | Payroll owns the effective-dated employment → pay group link. A default rule assigns it on `EmployeeHired`/`AssignmentChanged`, and a manual override needs a reason. Each assignment publishes `pay.PayGroupAssigned`. An active employment without a pay group is an exception. | new | P1 |
| BR-PAY-086 | Consume the People events that affect pay: RewardApproved, ContractChanged SUSPENDED/RESUMED, TemporaryTransferChanged (protected pay), SecondmentChanged, AppraisalFinalised, AdvanceDeductionRequested and the training refund. Every applied event is traceable on the result line. | new | P1 partial: only SUSPENDED/RESUMED and TemporaryTransfer. The other events are P2 with their sources. |
| BR-PAY-087 | Provide the legal-entity lock state to Core: the latest period with an APPROVED, PAID or CLOSED run, and whether it is unlocked (IF-PAY-P-20). | new | P1 |

## 4.6 Loans, advances and deductions
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-090 | Salary advance: requested through ESS or an admin, with limit rules and workflow approval, paid in an advance run or by bank file, and deducted automatically in the next regular run. | M | **P2** (Plan B, R2-19). In P1 an advance goes through an advance-type run or a variable input. |
| BR-PAY-091 | Employee loans: principal, optional interest, schedule, instalment deduction, pause, early repayment, and a balance shown on the payslip and in ESS. | S | **P1** (raised by D-09; interest-free only, interest is P2) |
| BR-PAY-092 | Damage-compensation deductions are capped at 30% of monthly pay after SI and PIT `[verify-legal]`. | M | P1 |
| BR-PAY-093 | When net pay is insufficient, deduct in priority order: statutory → court order → advance → loan → other. Anything not recovered carries forward. | M | P1 |
| BR-PAY-094 | Approved People expense claims are paid through payroll as non-taxable components that reference the claim ID. | S | P2 |

## 4.7 Payslip and ESS
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-100 | Payslip templates are configurable per pay group, in VN and EN. They show all pay elements, SI, KPCĐ, PIT, deductions, net, paid days, leave balance, YTD, retro and loan balance. | M | P1 |
| BR-PAY-101 | Payslips are published to ESS at a configured time after approval, with history and PDF download. | M | P1 (responsive web; the mobile app is P2) |
| BR-PAY-102 | E-mail the payslip as a **password-protected PDF** (AES-256). The employee sets the password in ESS: at least 8 characters, not derived from DOB, ID or employee number. There is no default, and the password is never sent or shown to HR. With no password, send a link only. Audit every step. Employees without e-mail get a printed PDF. | M | P1 (kept despite Plan B, R2-17) |
| BR-PAY-103 | Employees can raise payslip queries from ESS, routed to the payroll admin with an SLA. | C | P3 |
| BR-PAY-104 | The Payroll ESS pages show payslips, PIT certificates, annual income, applied dependants and the payslip password. They also handle 08/CK-TNCN and finalisation authorisation. | M | P1 |

## 4.8 Payment, accounting and cost allocation
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-110 | Generate bank files per payer account in each bank's format through configurable templates (CSV, XLSX, fixed-width, XML), with same-bank and interbank (Napas/Citad) splits. | M | P1 |
| BR-PAY-111 | Split net pay across up to 2 accounts, pay in cash with a cash list, and pay casual payees. | S | P2 |
| BR-PAY-112 | A payment file requires an APPROVED run and Finance approval. Store the file hash, audit regeneration, and import payment status back. | M | **P2** (Plan B, R2-19). The BR-PAY-084 gate still applies in P1. |
| BR-PAY-113 | GL export maps each component to debit and credit accounts with segments (legal entity, cost centre, project, department), as CSV or Excel plus an ERP API `[verify-legal]`. | M | P1 (Excel/CSV; the ERP API is P2) |
| BR-PAY-114 | Allocate employee cost (gross + employer contributions + KPCĐ) to cost centres or projects, by default, by percentage or by time. | M | **P2** (Plan B). P1 uses the default cost centre in the GL. |
| BR-PAY-115 | Accrue the 13th month, bonuses and the leave liability monthly. | C | P3 |

## 4.9 Security, audit and data
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-PAY-120 | Pay data is visible only to users holding a payroll role for that legal entity or pay group. Confidential (executive) pay groups are restricted to named users. | M | P1 |
| BR-PAY-121 | Audit every change to components, formulas, parameters, employee pay inputs and run status: who, when, before and after, and why. | M | P1 |
| BR-PAY-122 | Import opening balances through the shared Core Excel import, not an Oracle migration. Load YTD taxable income, PIT, SI basis and contributions, pre-go-live SI history (Payroll owns it), 6–12 months of salary history and outstanding loans. Then validate, dry-run, reconcile and get tenant sign-off. | M | P1 (D-09) |
| BR-PAY-123 | Retain payroll records and declarations for the statutory period, for example at least 10 years `[verify-legal]`. | M | P1 |
| BR-PAY-124 | Provide a payroll cost-estimate API (gross↔net and employer cost) for recruitment offers and headcount planning. | S | P2 (an always-on API once built) |
| BR-PAY-125 | Provide a gross↔net calculator for HR and ESS. | C | P3 |

## 9. Non-functional requirements
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| NFR-PAY-001 | A 10,000-employee run finishes in ≤ 15 min and a one-employee recalculation in ≤ 5 s. Runs are async with progress shown, and per-tenant job quotas protect other tenants. | M | P1 |
| NFR-PAY-002 | Results are deterministic to the VND for the same inputs and versions, using fixed-point decimals and never floats. | M | P1 |
| NFR-PAY-003 | Any approved run can be recalculated from its snapshot with identical results for at least 10 years. | M | P1 |
| NFR-PAY-004 | Encrypt at rest and in transit. Bank account, tax code and SI number get column encryption. Pay amounts are not column-encrypted (X-01) but are protected by RLS, roles, masking views and audit. Payslip PDFs are password-protected. | M | P1 |
| NFR-PAY-005 | Tenant isolation holds everywhere, including background jobs and exports. | M | P1 |
| NFR-PAY-006 | The audit log is immutable for config, inputs, status, file generation and unlocks. | M | P1 |
| NFR-PAY-007 | A new legal parameter set is deployable by configuration alone, and a new PIT schedule shape needs no schema change. | M | P1 |
| NFR-PAY-008 | A golden-file regression pack of at least 200 consultant-validated scenarios runs on every release and every parameter change. | M | P1 (go-live gate) |
| NFR-PAY-009 | Payroll and payslip ESS have 99.5% monthly availability, with peak capacity planned for days 1–10 and Tết. | M | P1 |
| NFR-PAY-010 | Generated XML and Excel files validate against the current HTKK, eTax, VSS and bank templates. Template versions are configurable without a release. | M | P1 |
| NFR-PAY-011 | UI and outputs are in VN and EN, with VND formatting. Bank files handle Vietnamese diacritics according to each bank's rules. | M | P1 |
| NFR-PAY-012 | An admin can complete a 500-employee monthly run in under 30 minutes of hands-on time. | S | P2 |
| NFR-PAY-013 | Scale to 10k employees per legal entity and 50 legal entities per tenant. | S | P2 |
