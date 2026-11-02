---
name: yhrm-people
description: Domain knowledge for the YourHRM People module (BR prefix BR-PPL / NFR-PPL, schema `hr`, backend package `vn.yourhrm.people`). People is the system of record for persons and PII (identity documents, tax code, SI number, addresses), employments (concurrent across legal entities), primary employment for the PIT family deduction, per-type insurance participation, date-effective assignments and reporting lines, agreed compensation (salary records, GROSS/NET), labour contracts, appendices and suspensions, decisions, family members and PIT dependants, bank accounts, tax residency and VN presence periods, special status, work permits for foreigners, separation, and the statutory labour book and reports. Use it whenever you design, implement, review or test anything in `hr.*`, in `vn.yourhrm.people`, in `people.*` events, or in a People backlog story (US-PPL-*). Vietnamese triggers include nhân sự, hồ sơ nhân viên, CCCD, căn cước, MST cá nhân, mã số BHXH, hợp đồng lao động (HĐLĐ), phụ lục, thử việc, tạm hoãn, quyết định, điều chuyển, kiêm nhiệm, biệt phái, lương thỏa thuận, diễn biến lương, người phụ thuộc, giảm trừ gia cảnh, cư trú thuế, giấy phép lao động, thai sản/con nhỏ, nghỉ việc, chấm dứt, trợ cấp thôi việc, sổ quản lý lao động and báo cáo biến động lao động.
---

# YourHRM People module (`people` → schema `hr`)

**Also load `yhrm-conventions`.** It covers tenancy/RLS, PII classes and encryption, the outbox, no cross-schema FKs, the legal-parameter rule, Pattern A/B and build commands. They are not repeated here.

The BRD (`docs/01-requirements/brd/modules/02-people.md`, v0.2) is in Vietnamese. This file is the working summary. For a specific BR, read **references/business-rules.md** (all 95 BR + 12 NFR with phase) and then the BRD row. For events and interfaces, read **references/integration.md**.

## 1. Scope
**In scope:**
- Person and PII.
- Employment, including rehire and concurrent employment in other LEs of the same tenant.
- Date-effective assignments, including manager and reporting line.
- Agreed compensation (salary record).
- Contracts (Labour Code 2019).
- Decisions.
- Dependants, bank accounts, tax residency, special status and work permits.
- Separation (notice, protection, severance eligibility, final-settlement trigger).
- Statutory labour reports.
- People ESS pages.
- People's side of PDPL.

R2-18: the People basics (person, employment, assignment, contract, salary, dependants, bank, tax/SI profile) are **always on**, whatever modules the tenant buys.

**Out of scope. Never build these in `hr`:**
| Topic | Owner |
|---|---|
| Legal entity, org tree, positions, job/grade catalogue, users and provisioning, roles, workflow engine, notifications, audit store, document service + e-sign orchestration, import engine, reference data | core |
| Shifts, attendance, OT, leave types and balances, holidays, **work-schedule assignment**, **business trips** | time-attendance |
| Pay scales and bands, salary components, **pay-group assignment**, SI/HI/UI contributions and declarations, **SI contribution history**, PIT calculation and finalisation, **08/CK-TNCN + PIT authorisation**, payslips, bank files, **severance service time and amounts**, final-settlement computation and clock, salary advances and loans | payroll |
| Requisitions, candidates, offers | recruitment (Phase 2) |
| Legal Parameter Service, ESS/MSS shell, consent registry, PD inventory, DSR orchestration, retention scheduler, job scheduler, object storage, VNeID / e-sign connectors | saas |

## 2. Ownership (gap-analysis §5, approved R2-02)
**Single owner of:**
- Person and identity data.
- Employment.
- Assignment and reporting line.
- **Agreed compensation (#24).** The salary record includes GROSS/NET and the agreed net. Payroll consumes `people.SalaryChanged` and **freezes the version used per run**.
- Dependants (#3).
- Employee bank accounts (#5).
- Work permits (#22).
- Expenses and cash advances (#21, Phase 2).
- Tax-residency **status**. Payroll only proposes it (BR-PAY-065 → IN-32).
- Insurance **participation** per employment and type (`hr.insurance_participation`). Payroll computes the contributions.

**Consumes, never stores as master:**
- Org, position, grade, location and LE data from Core. People holds soft ids only.
- Pay scales and component codes (`pay.CompensationCatalogueChanged`).
- SI history (`pay.SiContributionPosted`, display only).
- The severance result (`pay.FinalSettlementStatusChanged`, display only).
- Leave and maternity periods (`att.AbsenceChanged`) and leaver attendance close.
- Pay group and schedule. These are readiness items only; People stores neither (#25, #26).
- The hazardous-job flag: Core's job-title flag is the master, and People stores only per-employee overrides with a reason (GAP-045, `hr.special_status` `HAZARDOUS_JOB`).

## 3. Domain model. All tables below exist in `database/postgresql/ddl/003_hr.sql`
Date conventions:
- Pattern A uses `valid_from`/`valid_to` with **`valid_to` EXCLUSIVE**, plus a GiST exclusion constraint on the generated `valid_during`.
- `hr.employment.end_date`, `hr.contract.end_date` and `hr.vn_presence_period.exit_date` are **INCLUSIVE**.

| Entity | Table | Pattern / key invariants |
|---|---|---|
| Person | `hr.person` | One per individual per tenant. Unique `national_id_bidx`. P3: national id, tax code, SI no., HI card, ethnicity, religion (`_enc/_bidx/_last4`). `source` APP/OPENING_LOAD/REC_HANDOFF/IMPORT. Never matched across tenants. |
| Extra ID docs | `hr.person_identity_doc` | Passport, visa, residence card, old CMND/CCCD (P3). The **current** national id stays on `hr.person`. |
| Address / contact | `hr.person_address` (A, 2-level admin units + legacy 3-level codes, `mapping_status`), `hr.person_contact` | One primary contact per type. |
| Tax residency | `hr.tax_residency` (A) | RESIDENT (DAYS_183, MONTHS_12, PERMANENT_RESIDENCE, LEASE_183) or NON_RESIDENT (NOT_MET, TREATY_TIE_BREAKER). Only `APPROVED` rows are non-overlapping. Close the old row and approve the new one in one transaction. |
| VN presence | `hr.vn_presence_period` | Entry/exit dates, non-overlapping. Input to Payroll's 183-day test. |
| Qualification | `hr.education_qualification` | Minimal (R3-07), one `is_highest`, used by the labour book. |
| Employment | `hr.employment` | Status PRE_HIRE/ACTIVE/SUSPENDED/TERMINATED/CANCELLED. `employment_person_le_ex`: at most one live employment per person **per LE**. Unique `employee_no` per tenant. hire_type NEW/REHIRE/CONCURRENT/TRANSFER_IN. |
| Primary employment | `hr.primary_employment` (A) | Purpose **only** `PIT_FAMILY_DEDUCTION`. At most one per person per day (`primary_employment_ex`). No row is needed when the person has a single employment. With 2+ employments, every day must be covered (service rule + nightly check). |
| Insurance participation | `hr.insurance_participation` (A) | Per employment × `SI`/`HI`/`UI`/`OA_OD`. Stores the **outcome** of `insurance.multi_contract_rule`, with `basis_rule` (FIRST_CONTRACT/HIGHEST_SALARY/EACH_EMPLOYER) or an exemption reason, plus `legal_pack_id/version`. `insurance_participation_single_ex`: at most one participating employment per person × type when the rule is single-payer. Re-derive on employment start/end, salary change (HIGHEST_SALARY) and a new legal pack. HR override needs `override_reason`. |
| Decision | `hr.decision` | Numbered per LE (`decision_no` unique per LE). Status DRAFT→PENDING_APPROVAL→APPROVED→SIGNED/CANCELLED. |
| Assignment | `hr.assignment` (**B, bi-temporal**) | `assignment_key` = line identity. Types PRIMARY/CONCURRENT/SECONDMENT/TEMP_TRANSFER. Exactly one PRIMARY per employment per day (current knowledge). `manager_employment_id` = reporting line. `cost_split` jsonb. **No pay group, no schedule columns.** |
| Salary record | `hr.salary_record` (**B**) + `hr.salary_record_allowance` (append-only, per version) | `pay_basis` GROSS/NET. NET ⇒ `agreed_net_amount` NOT NULL. `net_guaranteed_scope` only for NET. Amounts are P2 plaintext (X-01), read via `hr.v_salary_record_masked` (fails closed). Requires `decision_id` **or** `contract_appendix_id` (service rule). |
| Contract | `hr.contract`, `hr.contract_appendix`, `hr.contract_suspension` (A) | Contracts of one employment never overlap (`contract_period_ex`). `definite_seq` drives the two-definite rule. An active suspension ⇒ employment SUSPENDED. |
| Family / dependants | `hr.family_member` (P3 IDs, own subject DEK), `hr.pit_dependant` | A dependant is claimed by one taxpayer per month within the tenant (`pit_dependant_claim_ex`). `employment_id` must be the person's PIT primary employment for those months (service rule). |
| Emergency contact | `hr.emergency_contact` | Table exists; BR-PPL-018 is P2. |
| Bank account | `hr.bank_account` (A, belongs to **person**) | P3 account no. One ACTIVE `REMAINDER` (default) account per person per day. PENDING_VERIFICATION→ACTIVE. `step_up_auth_ref`. |
| Special status | `hr.special_status` (A) | PREGNANCY, CHILD_UNDER_12M, DISABILITY, MINOR, ELDERLY_WORKER, HAZARDOUS_JOB (employment-scoped), UNION/PARTY/YOUTH_UNION_MEMBER. Sensitive types are masked by `hr.v_special_status_masked`. Details live in `detail_enc`. |
| Work permit | `hr.work_permit` (A, per employment) | WORK_PERMIT requires `valid_to`. One ACTIVE per employment per day. |
| Separation | `hr.separation`, `hr.separation_service_period` | One live separation per employment. `separation_protected_ck`: a protected employee + EMPLOYER_UNILATERAL/DISMISSAL/REDUNDANCY requires `protected_override_reason`. `payroll_result_ref` and `settlement_due_date` are **display only**. Period kinds: WORKED, RECOGNISED_PRIOR, PAID_SEVERANCE, PAID_JOB_LOSS, EXCLUDED. |
| HR documents | `hr.hr_document` | Metadata only. The file lives in Core's document service. |
| Statutory reports | `hr.statutory_report` + views `hr.v_labour_book`, `hr.v_labour_movement` | One live report per LE × type × period. |
| P2 shells | `hr.certificate`, `hr.discipline_case`, `hr.reward`, `hr.appraisal`, `hr.training_record`, `hr.checklist_template/checklist/checklist_task` | Shape only, marked `-- P2`. Do not build features on them in P1. |
| Events | `hr.outbox_event` / `hr.inbox_message` | Created by `sys.create_outbox_inbox('hr', …)`. |

Bi-temporal tables (`hr.assignment`, `hr.salary_record`):
- Never UPDATE business columns. Supersede the old row and insert a new one; `sys.tg_bitemporal_guard` enforces this.
- DELETE is rejected except during tenant purge (D9).
- A correction keeps the old version, with `correction_reason` and `superseded_*`.
- Payroll detects retro changes by comparing `recorded_at` with its People watermark (ADR-0024).

## 4. Key processes
1. **Direct hire (P1).** Recruitment handoff is P2.
   1. Match the person by national ID, or passport + nationality: an exact match blocks. A tax-code or SI-number match only warns.
   2. If an employment in the **same LE** is still live, block and redirect to an assignment change.
   3. Create the employment (PRE_HIRE), the first assignment, the contract and the salary record in one transaction.
   4. Check the plan licence limit (IN-23).
   5. Get approval through a Core workflow process type.
   6. Emit `EmployeeHired` (preview) on approval.
   7. On the hire date, a daily job sets ACTIVE and emits `EmployeeHired`, `AssignmentChanged`, `SalaryChanged` and `ContractChanged(SIGNED)`.
   8. Missing mandatory data leaves the employment ACTIVE but flagged incomplete.
   9. A no-show emits `HireCancelled`.
   10. `HireProcess` (ADR-0019) tracks readiness → `people.OnboardingReadinessChanged`.
2. **Concurrent hire (P1, R2-16).**
   1. Create a new employment with `hire_type=CONCURRENT` in another LE.
   2. Designate the `PIT_FAMILY_DEDUCTION` primary → `PrimaryEmploymentChanged`.
   3. Derive insurance participation per type → `InsuranceParticipationChanged`.
   4. Each LE withholds PIT on its own payments.
3. **Job change** (transfer, promotion, temporary transfer):
   1. Request with an effective date and a reason code.
   2. Core workflow.
   3. A decision, plus an appendix if contract terms change.
   4. New assignment version → `AssignmentChanged` (+ `SalaryChanged` if pay changed).
   - Future-dated changes wait for a daily job and can be cancelled before they apply.
   - Retro changes into a locked period set `retro=true`.
4. **Salary change.**
   1. A user with `compensation` proposes the change.
   2. Validate against the Payroll band (warn) and the regional minimum wage for the work location at the effective date (**block**). NET is checked on the gross from Payroll's simulator.
   3. Approve → decision or appendix → new salary-record version → `SalaryChanged`.
   - There is no other write path except the opening-balance import.
5. **Contract lifecycle:**
   1. Probation, with limits by job level; the result is recorded before probation ends.
   2. Definite contract (≤36 months; after two consecutive definite contracts the next must be indefinite, with exceptions).
   3. The expiry monitor raises alerts and the auto-conversion flag.
   4. Appendices may not change the term.
   5. Suspension → SUSPENDED status → `ContractChanged(SUSPENDED/RESUMED)`.
   - Signing is on paper with an uploaded scan in P1.
6. **Separation:**
   1. Request (ESS resignation or HR initiation).
   2. Compute the required notice and the shortfall.
   3. Protected-employee check from `hr.special_status` and T&A leave.
   4. Approval → decision → `TerminationScheduled` (with `access_revocation_date`).
   5. LWD+1 → `EmployeeTerminated`, carrying severance eligibility and `separation_service_period` rows.
   6. Payroll computes the amounts and the 14/30-working-day clock; People displays them.
   7. Withdrawal → `TerminationCancelled`.
   8. Capture the personal e-mail for former-employee OTP (OD-8).
7. **Dependant registration:**
   1. Family member.
   2. `pit_dependant` with month range and eligibility rules (parameters).
   3. HR approval → `DependantChanged`.
   4. When the primary employment changes, transfer active dependants (`DependantChanged` transferred).
8. **Tax residency:** HR records the status, or approves a Payroll 183-day proposal (IN-32) → `PersonalDataChanged`.
9. **Statutory reports:** labour book (on-screen view and export); 6-monthly report (June/December); monthly fluctuation notice. Log submissions in `hr.statutory_report`.

## 5. Invariants you must never break
- One person per individual per tenant. Never look up persons across tenants (BR-PPL-001, NFR-PPL-008).
- At most one live employment per person per LE. Same-LE extra work is a CONCURRENT assignment, not a second employment (BR-PPL-002).
- Exactly one PIT primary employment per person per day when the person has 2+ live employments. A dependant or self-deduction is never claimed through two employments (BR-PPL-004, RK7).
- Insurance is **not** decided by the primary employment. Use `hr.insurance_participation` per type. The rule comes from the legal parameter, not from code (BR-PPL-004, R3-03).
- A salary record changes **only** through an approved decision or appendix. The `compensation` permission is required to view or change it, and every view **and** change is audited (BR-PPL-040/044, #24).
- Assignment, salary and contract history is never overwritten. Corrections are new versions with a reason. Queries support as-of and as-known (BR-PPL-030, NFR-PPL-001).
- An assignment never carries a pay group or a work schedule (BR-PPL-031).
- No HR change commits without its outbox event in the same transaction (NFR-PPL-002).
- Below the regional minimum wage = block; outside the band = warn (BR-PPL-041).
- People never computes the 183-day test (BR-PPL-023), severance service time or amounts (BR-PPL-074), or SI contributions.
- Employer-initiated termination of a protected employee is blocked unless a documented lawful exception exists (BR-PPL-073).
- An appendix never changes the contract term. After two consecutive definite contracts the next is indefinite unless an exception is recorded (BR-PPL-053/055).
- Foreign workers: no assignment or definite-contract end beyond the permit validity without a recorded renewal (BR-PPL-020).
- P3 values (IDs, bank account) never appear in events, logs or the reporting store. Use bidx/last4 for matching and display.

## 6. VN compliance as parameters
Read every value through `getParam(key, scope, asOfDate)` (IN-10). The BRD marks all of the following `[verify-legal]`: the numbers are **examples**, not confirmed law.

| Topic | BRD example (all [verify-legal]) | BR / C# |
|---|---|---|
| Definite contract max; two-definite rule; auto-convert after N days | 36 months; 2; 30 days | 053/054, C1 |
| Probation max by level; pay floor | 180/60/30 days, 6 working days; ≥85% | 051, C2 |
| Temporary transfer (Art. 29) | 60 working days/year without consent, 3 days notice, ≥85% and ≥ min wage, 30 days old pay | 035, C5 |
| Suspension return window | 15 days | 056, C6 |
| Employee notice | 45 days indefinite / 30 days for 12–36 months / 3 working days for <12 months; special trades longer (e.g. 120) | 071, C7 |
| Severance / job-loss allowance (computed by Payroll) | ½ month per year (≥12 months) / 1 month per year, min 2; 6-month average | 074, C9 |
| Settlement deadline (Payroll clock) | 14 working days, extendable to 30 | 075, C10 |
| Regional minimum wage by location region | parameter | 041 |
| Dependant eligibility and income threshold; deduction amounts (Payroll uses them) | e.g. 15.5M self / 6.2M per dependant from 2026 | 016/017, C17 |
| Personal ID used as PIT code | Circular 86/2024 | 013, C18 |
| Work permit (Decree 219/2025); alert offsets | 45/60 days, configurable | 020, C15 |
| Insurance multi-contract rule `insurance.multi_contract_rule` | SI/UI/HI = FIRST_CONTRACT (sponsor R3-03), OA_OD = EACH_EMPLOYER. The PM believes HI may require HIGHEST_SALARY. | 004 |
| Labour book / 6-monthly report deadlines | within 30 days of operation; by 5 June / 5 December (Decree 145/2020) | 140/141, C13 |
| Monthly fluctuation notice (Employment Law 2025) | form and decree unconfirmed | 142, C14 |
| Protected employees | pregnancy, maternity leave, child <12 months, sick leave | 073, C8 |
| Retirement-age roadmap (alerts, report R7) | to 62 for men / 60 for women | C16 |
| PDPL (Law 91/2025, Decree 13/2023) | sensitive classes, consent, DSR, breach 72 h | 150–154, C19 |

## 7. Events and interfaces
See **references/integration.md** for the full IN/OUT table. Essentials:
- Publish:
  - `people.EmployeeHired`, `HireCancelled`, `AssignmentChanged`, `SalaryChanged`, `ContractChanged`, `ProbationCompleted`, `TemporaryTransferChanged`.
  - `TerminationScheduled`, `EmployeeTerminated`, `TerminationCancelled`.
  - `PersonalDataChanged` (incl. tax residency), `BankAccountChanged`, `DependantChanged`, `SpecialStatusChanged`.
  - **`PrimaryEmploymentChanged` (OUT-36)**, **`InsuranceParticipationChanged` (OUT-37)**.
  - `HeadcountSnapshotTaken`, `OnboardingReadinessChanged`.
  - The as-of/as-known read API (OUT-03).
- Consume:
  - `core.*`: OrgUnit, Position, JobCatalogue, LegalEntity, Location, UserStatus, Workflow*, ReorgActivated.
  - `saas.LegalParameterPackPublished` (re-derive insurance participation), `saas.ConsentChanged`, `saas.DataSubjectRequestOpened`.
  - `pay.CompensationCatalogueChanged`, `pay.SiContributionPosted`, `pay.FinalSettlementStatusChanged`, `pay.PayGroupAssigned`, residency proposals.
  - `att.AbsenceChanged`, `att.LeaverAttendanceClosed`.
  - `rec.CandidateHired` / `HireAmended` / `HireWithdrawn` (P2).
- **Reporting line → Core scopes:** People implements the SPI `vn.yourhrm.shared.auth.api.spi.ReportingLineProvider`. Core's `ScopeResolver` uses it to resolve `DIRECT_REPORTS` / `ALL_REPORTS` (until then, the `NONE` stub fails closed). Back it with `hr.assignment.manager_employment_id` (current version, valid on `asOf`). `people.AssignmentChanged` also invalidates Core's authz snapshot cache.

## 8. Phase boundary (mvp-scope §9.2; P1 = 47 BR + 10 NFR)
- **P1 (build now):**
  - BR-PPL-001–004, 010, 011, 013–017, 019 (minimal qualification only), 020, 021, 023.
  - 030–032, 035, 037; 040, 041, 044; 050–057; 070–075.
  - 120, 123; 130; 140–142; 150–153.
  - NFR-PPL-001–003, 005–011.
- **P2 / P3: do not build by accident.**
  - Rehire seniority choice and history (005).
  - Timeline screen (006).
  - VNeID (012).
  - Emergency contacts (018, table exists only).
  - ESS self-service personal-data change (022: HR enters changes; ESS keeps dependant and bank requests via 120).
  - Kiêm nhiệm (033), secondment (034), mass change (036), incumbency (038), reorg mapping (039).
  - Step increases (042); salary review (043, P3).
  - E-contract / e-sign (058, 134).
  - Checklists and pre-hire portal (060–063).
  - Post-separation obligations tracking (076); mass redundancy (077, P3).
  - Discipline (080–084, R2-05) and rewards (085: rewards are Payroll variable input).
  - Appraisal incl. probation form (090–094); training (100–103); expenses (110–113).
  - MSS screens (121: approvals via Core inbox); delegation (122).
  - Template UI (131: use BR-CORE-102); letters (132); document repository (133).
  - Foreign-worker reports (143); initial declaration (144, P3); retention purge (154).
  - NFR-PPL-004, NFR-PPL-012.
- P1 decisions:
  - No e-sign: contracts and decisions are signed on paper and the scan is uploaded (R2-10).
  - VND only (R2-09).
  - Recruitment handoff is P2; P1 hires are direct.
  - The cross-LE OT cap is P2 (R2-08).

## 9. Open questions, risks, pending items
- **Q7 / GAP-059:** are collaborator/service contracts an Employment or a separate payee? The backlog assumes Employment. Payroll has `casual_payee`.
- **Q4:** monthly KPI score for KPI-linked pay in the MVP? It would make BR-PPL-093 M.
- **Q8:** is the national e-labour-contract platform mandatory in 2026? **Q9:** retention periods. **Q10:** can recruitment consent be reused?
- **R3-03 [verify-legal]:** the HI rule (FIRST_CONTRACT vs HIGHEST_SALARY). US-PPL-023 is 13 SP and `[high-uncertainty]`. Keep it a parameter.
- Open gaps touching People:
  - GAP-019 (`HireHandoffProcessed`, `StartDateChanged`).
  - GAP-020 (event-name drift).
  - GAP-021 (`rec.InternalTransferAccepted` has no People input).
  - GAP-040 (settlement deadline).
  - GAP-045 (hazardous flag).
  - GAP-046.
  - GAP-055 (overlapping category codes).
  - GAP-056 (circular seniority / service measures).
  - GAP-062 (pay-scale publication duty).
  - GAP-064 (People has no defined pay cut-off; retro-flag definition).
  - GAP-065 (asset register `hr.asset_assignment` proposed, not in the DDL).
  - GAP-066 (OSH sub-domain).
- Risks: RK2 (dirty opening data → merge tool, data-quality report R20), RK3 (retro after lock), RK5 (PII exposure), RK7 (wrong primary or insurance participation).
- Spike: the key-unwrap cost for 10k persons per payroll run (decision-log §4) may reshape the read API (US-PPL-086).

## 10. Known conflicts in the sources (report them, don't silently pick)
1. **Primary employment purpose.** Stale text says `SOCIAL_INSURANCE` is also a purpose: BRD §3 P2 step 3, A5, OUT-01 payload; mvp-scope §9.2 BR-PPL-004 title/reason; gap §3 GAP-031 resolution; backlog US-PPL-022 AC; the DDL v0.3 header. The DDL CHECK, DESIGN §7.3, BR-PPL-004 (changed 2026-10-21) and catalogue v1.2 all say **`PIT_FAMILY_DEDUCTION` only**, with insurance in `hr.insurance_participation`. Follow the DDL/DESIGN.
2. **Duplicate entity row.** BRD §6 lists `hr.insurance_participation` twice (BHXH/… vs SI/HI/UI/OA_OD labels). The DDL uses `SI`/`HI`/`UI`/`OA_OD`.
3. **Employment status.** BR-PPL-003 and US-PPL-017 have a `Notice` state. The DDL CHECK has no `NOTICE` (PRE_HIRE/ACTIVE/SUSPENDED/TERMINATED/CANCELLED). Notice is currently only derivable from `hr.separation` status APPROVED/SCHEDULED. Raise this before coding the state machine.
4. **Event names.** BRD OUT-05/06/08/09/11 use Started/Ended/Signed/… names. Catalogue §6 uses `SecondmentChanged`, `TemporaryTransferChanged`, `ContractChanged{subtype}` and `ProbationCompleted`. The catalogue is canonical (X-06); GAP-020 is still open.
5. **Hire matching.** BRD §3 P1 step 2 calls rehire/concurrent "(P2)", but R2-16 / mvp-scope put concurrent employment in P1. Only BR-PPL-005 (rehire history and seniority choice) is P2.
6. **`rec.CandidateHired` payload.** IN-11 lists personal data and bank account. D7 says the payload is minimal and P3 is fetched via the Recruitment API by `hire_id` (Phase 2). Follow D7.
7. **BRD §6 entity names that don't exist in the DDL:**
   - `hr.education` → `hr.education_qualification`.
   - `hr.document` → `hr.hr_document`.
   - `hr.hr_event` → `hr.outbox_event`.
   - `hr.consent_ref` → **no table** (BR-PPL-151 P1 says People keeps consent references; this is a gap).
   - `hr.appraisal_cycle`, `hr.goal`, `hr.course`, `hr.training_session`, `hr.training_enrolment`, `hr.training_bond`, `hr.expense_*`, `hr.cash_advance` → not created (P2; only `hr.appraisal` and `hr.training_record` shells).
8. **OUT-37 status.** The DDL comment calls `InsuranceParticipationChanged` "proposed"; the BRD and catalogue v1.3 list it.
9. **Permission prefix.** Conventions say `<module>.<resource>.<action>`, so `people.*`. The architecture-overview example uses `ppl.employee.read`. BR-PPL-044 names the permission `compensation`. Agree on the final codes in `PeoplePermissions` before shipping.

## 11. Where to look
- BRD: `docs/01-requirements/brd/modules/02-people.md`. Sections: §3 processes, §4 BRs, §5 VN compliance C1–C22, §6 entities, §7 IN/OUT, §8 reports R1–R20, §9 NFR, §10 Q/RK.
- Phase: `docs/01-requirements/brd/mvp-scope.md` §9.2, §11.4 (R2-C2, R2-C5).
- Ownership and events: `docs/01-requirements/brd/gap-analysis.md` §5 (#3, #5, #22, #24, #29), §6 catalogue, §7.3 leaver sub-sequence.
- Decisions: `docs/00-project-management/decision-log.md`: R2-02, R2-05, R2-10, R2-15/X-01, R2-16, R2-18, R2-19, R3-03, R3-07, R3-09, D-09, D-10, OD-8, D6, D7, D9.
- DDL: `database/postgresql/ddl/003_hr.sql`. Design: `database/postgresql/DESIGN.md` §5 (A/B), §7.3 `hr` (concurrent-employment rule table), §9.3/9.4 (PII, masking), §13 (opening-balance import).
- Backlog: `docs/01-requirements/user-stories/people-backlog.md` (93 stories, 456 SP; coverage table §3, high-uncertainty §4.2).
- ADRs: 0001, 0006 (authz / field profile), 0019 (`HireProcess`, `FinalSettlementProcess`), 0022, 0024 (bi-temporal), 0026 (PII crypto), 0027, 0028 (outbox), 0037 (opening-balance import).
- Backend: `backend/src/modules/people/` is empty (`.gitkeep`). Package `vn.yourhrm.people` (`api` + `internal.{domain,application,infra,web}`). Mirror `backend/src/modules/core`. SPI to implement: `backend/src/shared/auth/src/main/java/vn/yourhrm/shared/auth/api/spi/ReportingLineProvider.java`.
