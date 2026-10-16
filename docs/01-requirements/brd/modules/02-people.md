# YourHRM — Module BRD: People (`people`, schema `hr`)

| Item | Value |
|---|---|
| Document | `docs/01-requirements/brd/modules/02-people.md` |
| Version | Draft v0.1 — 2026-10-16 (minor edits 2026-10-16 for D-09: BR-PPL-010 note, IN-28, NFR-PPL-007, Q3, RK2) |
| Owner | BA – People |
| Requirement prefix | `BR-PPL-NNN`, `NFR-PPL-NNN` |
| Related | Project charter §4–§6; BRDs 01-core, 03-time-attendance, 04-payroll, 05-recruitment, 06-saas-platform |
| Status | For review by PM / integration analyst (gap analysis) |

> All legal values (durations, percentages, amounts, deadlines) quoted here are examples. They are stored as **effective-dated, tenant-overridable parameters**, never hard-coded, and are marked `[verify-legal]` until a Vietnam labour/tax consultant confirms them.

---

## 1. Purpose & scope

### 1.1 Purpose
The People module is the **system of record for every person who works, has worked, or is about to work** for a tenant: who they are (person), how they are engaged (employment, contract), where they sit and what they are paid at grade level (assignment, salary history), and everything that happens to them over the employment lifecycle (onboarding → changes → appraisal / training / discipline → separation). It publishes authoritative HR events that Time & Attendance, Payroll, Recruitment and Core consume.

### 1.2 In scope
1. **Person vs employee**: one Person per real human per tenant; one or more Employments (rehire, concurrent employment in several legal entities of the same tenant).
2. **Personal information**: identity (CCCD / căn cước, VNeID level-2 verification, passport for foreigners), personal tax code (MST cá nhân), SI book number (số sổ BHXH / mã số BHXH), bank accounts, addresses, family members & PIT dependants, emergency contacts, education, certificates/licences, work permits (foreigners).
3. **Effective-dated job assignments**: hire, transfer, promotion, demotion, secondment, temporary transfer (Art. 29), concurrent positions (kiêm nhiệm), reporting line changes.
4. **Salary history / grade changes** (diễn biến lương) as HR events feeding payroll.
5. **Labour contracts** per Labour Code 2019: probation, definite-term (≤36 months), indefinite-term, conversion rules, appendices, suspension, e-labour contracts.
6. **Onboarding / offboarding checklists**.
7. **Resignation & termination**: notice periods, severance / job-loss allowance eligibility, final-settlement trigger, handover.
8. **Discipline & rewards** (kỷ luật / khen thưởng) including statutory disciplinary procedure.
9. **Appraisal / performance**: KPI and OKR cycles, ratings, calibration.
10. **Training**: courses, sessions, attendance, certificates, training bonds.
11. **Expense claims & cash advances** (tạm ứng / hoàn ứng).
12. **ESS and MSS**: self-service for employees and managers (web + mobile-responsive).
13. **Documents & HR letters**: decisions (quyết định), contracts, confirmations, templated letters, e-signature.
14. **Employee timeline**: single chronological view of all HR events.
15. **VN statutory outputs**: labour management book (sổ quản lý lao động), semi-annual labour-usage report (Decree 145/2020), monthly labour-change notice to the Employment Service Centre.
16. **Personal data protection**: consent, purpose limitation, sensitive-data controls per PDPL 2025 / Decree 13/2023.

### 1.3 Out of scope (owned elsewhere)
| Topic | Owner module |
|---|---|
| Legal entities, org tree, positions, job catalogue, users/roles, workflow engine, notifications, audit store | `core` |
| Shifts, rosters, check-ins, overtime, leave types/balances, holidays | `time-attendance` |
| Pay components, pay scales (thang bảng lương) amounts, SI/HI/UI contributions & declarations (D02-LT etc.), PIT calculation & finalization, payslips, bank payment files, the money calculation of final settlement / severance | `payroll` |
| Requisitions, candidates, interviews, offers | `recruitment` |
| Tenant provisioning, plans/licensing, file storage, e-signature/e-contract provider gateway, VNeID/third-party connectors, DSAR tooling framework | `saas-platform` |
| General-ledger posting of expenses | External accounting (via payroll / export) |

---

## 2. Actors & personas

| Actor | Description | Main interactions |
|---|---|---|
| HR Administrator (C&B / HR Ops) | Maintains employee records, contracts, decisions | Hire, change, terminate, generate documents, statutory reports |
| HR Business Partner | Supports managers on changes, discipline, appraisal | Approves/initiates transfers, discipline cases, calibrations |
| HR Manager / HR Director | Owns HR policy, signs decisions (or delegate) | Approves, signs, views analytics |
| Line Manager (MSS) | Manages direct/indirect reports | Approves requests, initiates changes, appraises, approves expenses |
| Employee (ESS) | Any active employee | Views/updates own data, dependants, payslip link, appraisal, expense claims, resignation |
| Former employee | Separated, retains limited ESS access per policy | Download documents (e.g., work confirmation, PIT certificate), rehire |
| Pre-hire (new joiner) | Accepted offer, not yet started | Completes onboarding forms, uploads documents, gives consent |
| Legal representative / authorised signatory | Signs contracts and decisions on behalf of the legal entity | E-sign |
| Finance / Accountant | Pays advances/expenses | Views approved claims, settles advances |
| Trainer / L&D coordinator | Runs training | Courses, sessions, attendance, bonds |
| Trade union representative (CĐCS) | Participates in disciplinary meetings | Recorded as attendee |
| Data Protection Officer (DPO) | Oversees personal data processing | Consent register, DSAR, access logs |
| System (integrations) | Other modules / scheduled jobs | Consume/produce events |

---

## 3. Business processes

### P1. Hire (new hire, from Recruitment or direct)
1. Recruitment publishes `OfferAccepted` (or HR creates a direct hire).
2. System matches an existing Person by CCCD / passport / tax code / SI number; if found → **rehire or concurrent hire** path (P2), else creates Person.
3. HR completes Employment: legal entity, employee number, hire date, seniority date, employment type, worker category.
4. HR creates first Assignment (position, org unit, job grade, manager, work location, cost centre, working-time regime).
5. HR creates Contract (probation agreement or labour contract) and Salary record from offer terms.
6. Pre-hire receives ESS invitation: fills personal data, bank account, dependants, uploads documents, gives PDPL consents.
7. Onboarding checklist instantiated (P9).
8. On approval and when effective date reached: Employment becomes `Active`; events `EmployeeHired`, `AssignmentChanged`, `SalaryChanged`, `ContractSigned` published.
- **Exceptions**: duplicate person conflict → manual merge review; no-show before start → hire cancelled, event `HireCancelled`; missing mandatory data at start date → employment active but flagged "incomplete" and payroll warned.

### P2. Rehire / concurrent employment
1. Matching Person found.
2. If the person has an active Employment in the **same** legal entity → block (use assignment change instead).
3. If active Employment in **another** legal entity → concurrent employment; HR records primary employment flag (for SI and PIT purposes).
4. If former employee → check rehire-eligibility flag; new Employment created with new hire date; prior service optionally recognised for seniority (tenant policy).
- **Exception**: rehire within the period covered by a severance already paid → warn (service years for future severance exclude paid periods).

### P3. Job change (transfer, promotion, demotion, secondment, temporary transfer, concurrent position)
1. HR / manager initiates change request with effective date and reason code.
2. Workflow routes to approvers (core workflow).
3. On approval, a **Decision** (quyết định) is generated; contract appendix generated when contract terms change.
4. New effective-dated Assignment row created; prior row end-dated (no overwrite).
5. Events `AssignmentChanged` (+ `SalaryChanged` if pay changes) published.
- **Exceptions**: retroactive effective date → flagged for payroll retro; temporary transfer exceeding statutory cumulative limit without consent → blocked; future-dated changes can be cancelled before effective date (`AssignmentChangeCancelled`).

### P4. Salary / grade change
1. Triggered by promotion, annual review, appraisal result, probation pass, or ad hoc.
2. Record new grade/step, base salary, SI contribution salary, allowance entitlements, effective date, reason.
3. Validate against pay scale ranges and regional minimum wage (from payroll / core parameters).
4. Approval → decision + contract appendix → `SalaryChanged` event.

### P5. Contract lifecycle
1. Probation agreement (or probation clause) → probation outcome recorded before end date → notify result.
2. Pass → labour contract (definite or indefinite) generated; fail → separation (P8) without notice obligation.
3. Expiry monitor alerts HR N days before definite contract end; HR chooses renew / convert / end.
4. Conversion rule: after 2 consecutive definite contracts, the next must be indefinite (except statutory exceptions).
5. Contract continued past expiry without new contract → system flags auto-conversion risk.
6. Appendices for changes; suspension (tạm hoãn) for statutory reasons.
7. E-contract: sent for e-signature via platform; signed PDF stored; status synced.

### P6. Appraisal cycle (KPI / OKR)
1. HR configures cycle (period, population, template, rating scale, weights).
2. Goal setting → manager approval → mid-year check-in → self-assessment → manager assessment → calibration → final rating → acknowledgement.
3. Results published (`AppraisalFinalised`) for salary review / bonus.

### P7. Discipline & rewards
1. Violation recorded with date, evidence.
2. System checks statute of limitation and protected-status rules.
3. Invitation to disciplinary meeting (employee, union rep) → minutes recorded.
4. Decision on discipline form issued; if material damage, compensation plan created.
5. Rewards: proposal → approval → decision; monetary rewards sent to payroll.

### P8. Separation (resignation, termination, contract expiry, retirement, death, dismissal, probation fail, mutual agreement)
1. Initiation: employee resignation via ESS, or HR/manager initiates termination.
2. System computes required notice period by contract type and reason; validates notice given; flags protected employees.
3. Approval workflow; termination decision generated.
4. Offboarding checklist (P9): handover, asset return, access revocation date.
5. Last working day recorded; `TerminationScheduled` event; on the date `EmployeeTerminated`.
6. Final-settlement trigger to payroll with severance / job-loss eligibility, service-year inputs, unused leave payout flag, advances outstanding.
7. Return of documents: SI book closing request, work confirmation, PIT certificate request.
- **Exceptions**: resignation withdrawn before effective date (with employer consent) → `TerminationCancelled`; termination of protected employee → blocked unless lawful exception documented.

### P9. Onboarding / offboarding checklists
1. Template per legal entity / employee category, with tasks, owners (HR, IT, manager, employee), due offsets.
2. Instantiated on hire/termination; tasks tracked; overdue reminders; completion recorded.

### P10. Expense claims & advances
1. Employee requests advance (purpose, amount, expected settle date) → approval → Finance pays.
2. Employee submits claim with receipts (e-invoice data) → links to advance → approval by manager / Finance.
3. Net payable reimbursed (via payroll or Finance); unsettled advance past due → deduction request to payroll (with employee consent).

### P11. Statutory labour reporting
1. Monthly: compile labour changes (hires/terminations) for the Employment Service Centre notice.
2. Semi-annual: compile labour-usage report (Decree 145/2020) per legal entity → HR review → export in prescribed form → submission log.

### P12. Personal data requests
1. Employee requests access/correction/withdrawal of consent via ESS.
2. DPO/HR reviews; correction via approval; withdrawal applies to optional processing only, with consequences shown.

---

## 4. Business requirements

### 4.1 Person & employment
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-001 | The system shall hold exactly one Person record per real individual per tenant, uniquely identified by national ID (CCCD 12-digit personal identification number) or, for foreigners, passport number + nationality. | M | Duplicate check on create/import; merge function with audit. |
| BR-PPL-002 | A Person may have multiple Employments over time (rehire) and concurrently in different legal entities of the same tenant; at most one active Employment per legal entity at any date. | M | |
| BR-PPL-003 | Each Employment shall have: legal entity, employee number (unique per tenant, configurable pattern per legal entity), hire date, original hire date, seniority date, employment status (Pre-hire, Active, Suspended, Notice, Terminated), worker category (Vietnamese / foreign / expatriate on assignment), employment type (full-time, part-time, seasonal, intern, collaborator-service contract). | M | Collaborators (hợp đồng dịch vụ) are not employees under labour law but need payroll PIT handling. |
| BR-PPL-004 | For concurrent employments, HR shall designate a **primary employment** used for SI participation and PIT dependant deduction; system shall prevent dependants being claimed via two employments simultaneously. | M | Payroll consumes the flag. |
| BR-PPL-005 | On rehire, the system shall show prior employment history, termination reason and rehire-eligibility flag, and allow HR to choose whether prior service counts toward seniority (policy-driven). | M | |
| BR-PPL-006 | The system shall maintain an immutable employee timeline listing all HR events (hire, assignment, salary, contract, discipline, reward, appraisal, training, leave of absence/suspension, separation) with effective date, entry date, actor and source document. | M | Read model built from events. |

### 4.2 Personal information
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-010 | Capture identity: full name (Vietnamese with diacritics + unaccented form), date of birth, gender, nationality, ethnicity, religion (optional, sensitive), place of origin, permanent & current residence addresses using the post-2025 two-level administrative units (province / commune). | M | Admin unit list from core reference data; legacy 3-level addresses kept for opening-balance imports and old documents (changed 2026-10-16, D-09). `[verify-legal]` |
| BR-PPL-011 | Capture identity documents with type (CCCD/căn cước, passport, work permit, temporary residence card), number, issue date, issue place, expiry, scanned images; alert before expiry (configurable days). | M | |
| BR-PPL-012 | Support VNeID-based identity verification (level-2 e-ID QR / NFC chip read) when the platform connector is enabled; record verification status, method and timestamp. | S | Connector from saas-platform. |
| BR-PPL-013 | Capture personal tax code; default it to the 12-digit personal identification number where law treats them as equivalent, keep legacy 10-digit MST for history. | M | Circular 86/2024/TT-BTC `[verify-legal]` |
| BR-PPL-014 | Capture SI book number / SI code (mã số BHXH), HI card number and registered initial health-care facility; record SI book custody status (held by employer / returned). | M | |
| BR-PPL-015 | Capture one or more bank accounts (bank, branch, account number, account holder name) with effective dates and payment split (amount or %); exactly one default salary account; account holder name must match employee name (warning). | M | Bank list from core reference data. |
| BR-PPL-016 | Capture family members (relationship, name, DOB, ID number, tax code, occupation, contact) and flag those registered as **PIT dependants** with deduction start month, end month and supporting document; validate per dependant-eligibility rules (e.g., child under 18, or 18+ studying/disabled without income; parents/spouse without income above threshold). | M | Threshold parameters `[verify-legal]` |
| BR-PPL-017 | Dependant registration and changes shall be approved by HR and produce a `DependantChanged` event; the system shall generate the dependant registration form/list for tax authority submission. | M | Form 07/ĐK-NPT-TNCN or current `[verify-legal]` |
| BR-PPL-018 | Capture emergency contacts (min. 1 for active employees, configurable). | M | |
| BR-PPL-019 | Capture education (level, institution, major, graduation year, degree scan), certificates & licences (type, issuer, number, issue/expiry date) with expiry alerts; occupational-safety training certificates flagged where required by job. | M | |
| BR-PPL-020 | Capture foreign-worker data: work permit / exemption certificate number, validity, job title on permit, legal entity; block assignment end-date beyond permit validity without renewal; alert 45/60 days before expiry (configurable). | M | Decree 219/2025/NĐ-CP (replacing 152/2020) `[verify-legal]` |
| BR-PPL-021 | Capture special-status attributes that drive downstream rules: pregnancy/child-under-12-months status (with dates), disability, minor (under 18), elderly worker, union membership, party/youth union membership (optional), heavy/hazardous job classification. | M | Feeds T&A (reduced hours) and protections. |
| BR-PPL-022 | Employees may request changes to their personal data via ESS; configured fields update directly, others require HR approval with evidence attachment; prior values retained effective-dated. | M | |

### 4.3 Assignments & organisation placement
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-030 | Assignments shall be effective-dated (from/to) with full history; no in-place overwrite of historical rows; corrections recorded as corrections with reason. | M | |
| BR-PPL-031 | Each assignment holds: legal entity, org unit, position (from core), job, job grade/level, line manager (employee or position-based), work location, cost centre (with optional % split), working-time regime/standard hours, FTE %, assignment type (primary, concurrent, secondment, temporary transfer). | M | |
| BR-PPL-032 | Support change reasons catalogue: transfer, promotion, demotion, secondment, temporary transfer, reorganisation, position title change, manager change, location change, cost-centre change; reason drives required approvals and documents. | M | |
| BR-PPL-033 | Support **concurrent positions** (kiêm nhiệm) within the same employment, each with its own manager, FTE and optional concurrent allowance; one assignment flagged primary. | M | |
| BR-PPL-034 | Support **secondment** to another legal entity (home vs host entity, paying entity, cost recharge flag, start/end), without terminating home employment. | S | |
| BR-PPL-035 | Enforce temporary-transfer rule (Art. 29): cumulative days in a calendar year beyond the statutory limit require employee written consent; notice of transfer at least N working days before; salary protection rule flagged to payroll. | M | 60 working days/year, 3 working days notice, ≥85% and ≥ regional minimum wage, 30 working days old-salary protection `[verify-legal]` |
| BR-PPL-036 | Support mass changes (reorganisation): select employees, apply same change with one effective date, generate individual decisions in bulk. | S | |
| BR-PPL-037 | Future-dated and retroactive changes allowed; retroactive changes affecting a closed payroll period produce a retro flag on the published event. | M | |
| BR-PPL-038 | Position incumbency: the system shall maintain which employee occupies which position and expose vacancy/over-fill against position headcount from core. | M | |

### 4.4 Salary history
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-040 | Maintain effective-dated salary records per employment: pay scale / grade / step (bậc), base (contract) salary, SI contribution salary, salary allowances agreed in the contract, currency, pay frequency, probation salary %, reason, linked decision/appendix. | M | Pay scale definitions owned by payroll. |
| BR-PPL-041 | Validate salary against the pay scale min/max for the grade and against the applicable regional minimum wage (by work location region) at the effective date; block below minimum, warn outside range. | M | Regional minimum wage parameter `[verify-legal]` |
| BR-PPL-042 | Support step progression (nâng bậc) schedules: compute next eligible step date from grade rules and disciplinary deferrals; produce a due list for HR. | S | Discipline "extension of salary increase" defers date. |
| BR-PPL-043 | Support salary review campaigns: budget per org unit, proposals by managers using appraisal results, approval, bulk publication of `SalaryChanged`. | C | Phase 2. |
| BR-PPL-044 | Salary data visibility restricted by role and data scope; managers see only if granted. | M | |

### 4.5 Labour contracts
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-050 | Support contract types: probation agreement, definite-term (≤36 months), indefinite-term, seasonal/short (<1 month, where applicable), apprenticeship, internship, service/collaborator contract (non-labour), and contract appendices. | M | Labour Code 2019 Art. 20, 24 `[verify-legal]` |
| BR-PPL-051 | Probation: maximum duration validated by job level (e.g., ≤180 days enterprise manager; ≤60 days college+; ≤30 days intermediate/technical; ≤6 working days other); only one probation per job; no probation for contracts < 1 month; probation salary ≥ 85% of the job's salary. | M | Art. 24–27 `[verify-legal]` |
| BR-PPL-052 | Probation outcome (pass/fail) must be recorded and notified to the employee before probation end; system alerts HR/manager N days before; on pass, generate the labour contract (or confirm continuation if probation clause in contract). | M | Art. 27 `[verify-legal]` |
| BR-PPL-053 | Definite-term contract duration shall not exceed 36 months; system counts consecutive definite contracts per employment and **requires indefinite type** for the next contract after two definite ones, except statutory exceptions (elderly workers, foreign workers, state-enterprise managers, union officials' term-based contracts etc.). | M | Art. 20.2, 149, 151 `[verify-legal]` |
| BR-PPL-054 | Expiry monitoring: alert HR and manager at configurable offsets (e.g., 45/30/15 days) before contract end; employer notice of non-renewal; if the employee keeps working beyond 30 days after expiry without a new contract, flag auto-conversion (definite → indefinite). | M | Art. 20.2 `[verify-legal]` |
| BR-PPL-055 | Contract appendices record term changes (salary, position, workplace, working time) with effective date; appendix must not change contract term/duration (validation). | M | Art. 22 `[verify-legal]` |
| BR-PPL-056 | Contract suspension (tạm hoãn) for statutory reasons (military service, detention, maternity beyond statutory leave by agreement, election to office, other agreed) with start/end; employment status `Suspended`; event `ContractSuspended` / `ContractResumed`. | M | Art. 30–31 `[verify-legal]` |
| BR-PPL-057 | Contract documents generated from tenant templates (VN/EN bilingual), numbered per legal entity sequence, signed by authorised signatory per delegation matrix. | M | |
| BR-PPL-058 | Electronic labour contracts: send for e-signature via platform e-sign/e-contract provider, track status (draft, sent, signed by employee, signed by employer, completed, rejected, expired), store signed file + certificate evidence; support submission/registration to the national e-labour-contract platform when required. | S→M | Labour Code Art. 14; Decree on e-labour contracts (e.g., 337/2025/NĐ-CP) `[verify-legal]` — becomes M if mandated for tenant size. |
| BR-PPL-059 | Collective labour agreement (TƯLĐTT) reference per legal entity: store document and effective period; contract templates may reference it. | C | |

### 4.6 Onboarding & offboarding
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-060 | Configurable checklist templates by legal entity, employee category, and event (onboarding, offboarding, transfer); tasks with owner role, due offset, mandatory flag, attachment requirement. | M | |
| BR-PPL-061 | Pre-hire portal: invited pre-hires complete personal data, upload documents (CCCD, degrees, health certificate, photos, SI book), sign consents before day 1. | M | |
| BR-PPL-062 | Offboarding includes handover note, asset return list, access-revocation date, exit interview (optional survey), clearance sign-off from configured departments (IT, Finance, Admin). | M | |
| BR-PPL-063 | Checklist progress and overdue tasks visible to HR and manager dashboards; reminders via core notifications. | M | |

### 4.7 Separation
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-070 | Support separation reasons mapped to legal grounds: contract expiry, mutual agreement, employee unilateral resignation, employer unilateral termination (Art. 36), redundancy due to restructuring/economic reasons (Art. 42–43), dismissal (disciplinary), probation fail, retirement, death, missing/court declaration, business closure. Each reason carries a statutory code for SI/UI and reports. | M | |
| BR-PPL-071 | Compute required notice by contract type and reason: e.g., indefinite 45 days, definite 12–36 months 30 days, definite < 12 months 3 working days; special occupations per Decree 145/2020; no-notice cases for employee (Art. 35.2) and employer (Art. 36.2). Show shortfall and the compensation consequence when notice is not respected. | M | `[verify-legal]` |
| BR-PPL-072 | ESS resignation request: employee proposes last working day, reason, comments; manager and HR approve; withdrawal allowed before approval / with consent afterwards. | M | |
| BR-PPL-073 | Protected-status check: block employer unilateral termination / dismissal for employees who are pregnant, on maternity leave, raising a child under 12 months, on sick leave under treatment within statutory period, etc., unless a lawful exception is documented. | M | Art. 37, 122.4, 137 `[verify-legal]` |
| BR-PPL-074 | Determine **severance allowance** eligibility (trợ cấp thôi việc) or **job-loss allowance** eligibility (trợ cấp mất việc làm) from reason; compute service-year inputs: total working time minus periods with UI contributions minus periods already compensated; pass inputs to payroll which computes the amount (e.g., ½ month per year severance; 1 month per year, min 2 months job-loss, on 6-month average contract salary). | M | Art. 46–47, Decree 145 Art. 8 `[verify-legal]` |
| BR-PPL-075 | On approval publish `TerminationScheduled`; on last working day+1 publish `EmployeeTerminated` with final-settlement trigger; settlement deadline (e.g., 14 working days, extendable to 30 days) tracked with alerts. | M | Art. 48 `[verify-legal]` |
| BR-PPL-076 | Post-separation obligations tracked: SI book closing & return, return of original documents, work confirmation letter, PIT withholding certificate request, rehire-eligibility flag, ESS read-only access period. | M | |
| BR-PPL-077 | Mass layoff (Art. 42–44) support: labour-use plan record, union consultation evidence, notification to provincial labour authority before termination. | C | |

### 4.8 Discipline & rewards
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-080 | Discipline case record: violation date, internal-labour-regulation clause breached, evidence, meeting invitation, attendees (incl. union rep), minutes, decision, form (reprimand, extension of salary-increase period ≤ 6 months, demotion, dismissal), effective period, expungement date. | M | Art. 122–125 `[verify-legal]` |
| BR-PPL-081 | Validate statute of limitation (e.g., 6 months; 12 months for finance/asset/confidentiality violations) and prohibit monetary fines/salary deduction as discipline form. | M | Art. 123, 127 `[verify-legal]` |
| BR-PPL-082 | Temporary suspension from work (tạm đình chỉ) pending investigation, with max days (e.g., 15, up to 90 in special cases) and salary advance (e.g., 50%) passed to payroll. | S | Art. 128 `[verify-legal]` |
| BR-PPL-083 | Material liability (bồi thường thiệt hại): compensation amount and instalment plan; monthly deduction capped (e.g., ≤ 30% of net monthly salary) sent to payroll. | S | Art. 129, 102 `[verify-legal]` |
| BR-PPL-084 | Auto-expunge discipline after the statutory period without repeat violation; keep record but mark expunged. | S | Art. 126 `[verify-legal]` |
| BR-PPL-085 | Rewards (khen thưởng): type (monetary, certificate, title), level (company, state), amount, tax-treatment flag, decision; approved monetary rewards published to payroll for the target pay period. | M | |

### 4.9 Appraisal / performance
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-090 | Configure appraisal cycles (annual, semi-annual, quarterly, probation) with population filters, template (KPI, OKR, competency, mixed), weights, rating scale, and phase deadlines. | S | Phase 2 except probation appraisal (M). |
| BR-PPL-091 | KPI library and goal cascading (company → unit → individual); OKR with key-result progress check-ins. | S | |
| BR-PPL-092 | Self, manager, optional 360/peer reviews; calibration session with distribution guidance; final rating locked after acknowledgement. | S | |
| BR-PPL-093 | Publish `AppraisalFinalised` (rating, score, period) for payroll (performance bonus / KPI-based pay component) and salary review. | S | Monthly KPI score for KPI-linked pay is M if a tenant uses it — see open question Q4. |
| BR-PPL-094 | Probation appraisal form linked to probation outcome (BR-PPL-052). | M | |

### 4.10 Training
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-100 | Course catalogue, sessions (internal/external, cost, trainer, schedule), enrolment (nomination / self-request with approval), attendance and result, certificate issuance updating BR-PPL-019. | S | |
| BR-PPL-101 | Training bond / training contract (hợp đồng đào tạo): cost, commitment period; on early separation compute pro-rata repayment and send to final settlement. | S | Art. 62 `[verify-legal]` |
| BR-PPL-102 | Mandatory training tracking (e.g., occupational safety & health groups) with expiry and compliance report. | S | Decree 44/2016 `[verify-legal]` |
| BR-PPL-103 | Training time on working days may be sent to T&A as paid training attendance. | C | |

### 4.11 Expense claims & advances
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-110 | Expense categories with policy limits (per item/day/trip/grade), required receipts, VAT e-invoice fields (invoice no., seller tax code, date). | S | |
| BR-PPL-111 | Cash advance request → approval → paid status; claim settlement against advance; balance due to/from employee. | S | |
| BR-PPL-112 | Approved reimbursements routed either to payroll (as non-taxable/taxable earning per category flag) or to finance export; unsettled overdue advances sent to payroll as deduction (with consent record). | S | |
| BR-PPL-113 | Business-trip (công tác) request linked to expenses; approved trip days published to T&A as business-trip attendance. | S | |

### 4.12 ESS / MSS
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-120 | ESS: view profile, assignments, contracts, salary history (if policy allows), documents, timeline; edit personal data (BR-PPL-022); manage dependants (request); bank account change (with step-up auth); resignation; expense claims; appraisal; training enrolment; HR letter requests. | M | Leave/attendance/payslip screens embedded from T&A / payroll. |
| BR-PPL-121 | MSS: team roster & org chart, pending approvals, initiate changes (transfer, promotion, salary proposal within scope), probation outcomes, appraisal, discipline proposal, onboarding tasks. | M | |
| BR-PPL-122 | Delegation of manager approvals during absence uses core delegation. | M | |
| BR-PPL-123 | ESS/MSS available in Vietnamese and English, mobile-responsive. | M | |

### 4.13 Documents, decisions & HR letters
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-130 | Decision (quyết định) generation for: hire/appointment, probation result, transfer, promotion/demotion, concurrent position, secondment, salary change, reward, discipline, suspension, termination, retirement; each with decision number (per legal entity, per year sequence), date, signatory, effective date, legal basis text. | M | |
| BR-PPL-131 | Template management: tenant-editable templates with merge fields, versioned, bilingual. | M | |
| BR-PPL-132 | HR letters on request: employment confirmation, income confirmation (from payroll data), visa support, work-experience certificate; request via ESS with approval and auto-generation. | S | |
| BR-PPL-133 | Employee document repository with categories, retention period per category, confidentiality level, and version history. | M | |
| BR-PPL-134 | Digital signature of decisions/letters (organisation digital certificate) via platform e-sign. | S | |

### 4.14 Statutory reporting & labour book
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-140 | Maintain the **labour management book** (sổ quản lý lao động) electronically per legal entity with the prescribed data fields, available for inspection and exportable. | M | Decree 145/2020 Art. 3 `[verify-legal]` |
| BR-PPL-141 | Generate the **semi-annual labour-usage change report** per legal entity in the prescribed form (e.g., Form 01/PLI), for periods ending June and December, with deadline alerts (e.g., before 5 June / 5 December), and log submission. | M | Decree 145/2020 Art. 4 `[verify-legal]` |
| BR-PPL-142 | Generate the **monthly labour-fluctuation notice** to the local Employment Service Centre (UI purpose) listing hires/terminations, deadline alert. | M | Law on Employment 2025 + guiding decree `[verify-legal]` |
| BR-PPL-143 | Foreign-worker reports (demand explanation, periodic usage report) as prescribed. | S | `[verify-legal]` |
| BR-PPL-144 | Initial labour declaration within 30 days of a legal entity starting operation (template). | C | `[verify-legal]` |

### 4.15 Personal data protection
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-PPL-150 | Classify every personal field as basic or **sensitive** (e.g., health, biometrics, ethnicity, religion, political affiliation, criminal record, location, financial/bank, union membership) in a data dictionary. | M | Decree 13/2023 Art. 2; PDPL 2025 `[verify-legal]` |
| BR-PPL-151 | Record consent per purpose (versioned consent text, timestamp, channel, withdrawal); processing of optional sensitive data is blocked without consent; lawful-basis processing (labour contract/legal obligation) recorded without consent where the law allows. | M | |
| BR-PPL-152 | Sensitive fields masked by default in UI and exports; reveal requires permission and is logged (who, when, which field, purpose). | M | |
| BR-PPL-153 | Data-subject requests (access, correction, export, consent withdrawal, restriction, deletion where legally permitted) tracked with SLA via ESS; fulfilment produces data export from People and triggers related modules via platform DSAR framework. | M | `[verify-legal]` for response deadlines |
| BR-PPL-154 | Retention rules: after separation, personal data retained per category retention schedule (e.g., labour-law/SI/tax retention), then anonymised/purged by scheduled job, with legal-hold override. | S | |

---

## 5. Vietnam compliance rules

| # | Rule (summary) | Legal basis | Applies to |
|---|---|---|---|
| C1 | Contract types: indefinite and definite (≤ 36 months). After 2 definite contracts the next must be indefinite (exceptions). Continuing to work 30 days after expiry without new contract → becomes indefinite (from definite). | Labour Code 2019 (45/2019/QH14) Art. 20 `[verify-legal]` | BR-PPL-053/054 |
| C2 | Probation max durations by job; once per job; probation salary ≥ 85%; result notice at end of probation; no probation for contracts < 1 month. | LC Art. 24–27 `[verify-legal]` | BR-PPL-051/052 |
| C3 | Electronic labour contracts have the same validity as paper; national platform & conditions per guiding decree. | LC Art. 14; Law on E-Transactions 2023; Decree 337/2025/NĐ-CP `[verify-legal]` | BR-PPL-058 |
| C4 | Appendices may amend terms but not the contract duration. | LC Art. 22 `[verify-legal]` | BR-PPL-055 |
| C5 | Temporary transfer to other work: max 60 working days/year without consent, 3 working days notice, salary per new job ≥ 85% of old and ≥ regional minimum, old salary kept 30 working days. | LC Art. 29 `[verify-legal]` | BR-PPL-035 |
| C6 | Contract suspension grounds and resumption within 15 days. | LC Art. 30–31 `[verify-legal]` | BR-PPL-056 |
| C7 | Employee notice: 45 days (indefinite), 30 days (12–36 months), 3 working days (< 12 months); special occupations longer (e.g., 120 days) per Decree 145. Employer notice for Art. 36 grounds similar. | LC Art. 35–36; Decree 145/2020 Art. 7 `[verify-legal]` | BR-PPL-071 |
| C8 | Protected employees (pregnant, maternity leave, child < 12 months) cannot be unilaterally terminated/disciplined except business closure etc. | LC Art. 37, 122, 137 `[verify-legal]` | BR-PPL-073 |
| C9 | Severance allowance: ½ month salary per year of service for those with ≥ 12 months, excluding UI-covered time and already-compensated time; Job-loss allowance: 1 month per year, min 2 months; salary basis = average contract salary of 6 months before termination. | LC Art. 46–47; Decree 145 Art. 8 `[verify-legal]` | BR-PPL-074 |
| C10 | Settlement of all entitlements within 14 working days (extendable to 30 days in listed cases); employer must complete SI-period confirmation and return documents. | LC Art. 48 `[verify-legal]` | BR-PPL-075/076 |
| C11 | Discipline: forms limited to reprimand, extension of salary-increase period ≤ 6 months, demotion, dismissal; statute of limitation 6/12 months; fines prohibited; procedure with union participation; suspension ≤ 15 days (≤ 90 special) with 50% advance; expungement after 3/6 months (non-dismissal) or 3 years for demotion. | LC Art. 122–128; Decree 145 Art. 69–72 `[verify-legal]` | BR-PPL-080–084 |
| C12 | Material compensation by salary deduction capped at 30% of net monthly salary. | LC Art. 102, 129 `[verify-legal]` | BR-PPL-083 |
| C13 | Labour management book within 30 days of operation; semi-annual labour usage report (before 5 June and 5 December) via the national labour portal. | Decree 145/2020 Art. 3–4 `[verify-legal]` | BR-PPL-140/141 |
| C14 | Monthly notice of labour fluctuations to Employment Service Centre (UI). | Law on Employment 2025 (74/2025/QH15) & guiding decree `[verify-legal]` | BR-PPL-142 |
| C15 | Foreign workers need work permits/exemption; definite-term contracts not exceeding permit validity; renewals. | LC Art. 151–155; Decree 219/2025/NĐ-CP `[verify-legal]` | BR-PPL-020, 053 |
| C16 | Retirement age follows the gradual roadmap (to 62 male / 60 female). Used for retirement alerts. | LC Art. 169; Decree 135/2020 `[verify-legal]` | Report R7 |
| C17 | PIT dependants: registration, eligibility (age, income threshold), one taxpayer per dependant; family deduction amounts (e.g., 15.5 M VND self / 6.2 M VND per dependant from 2026). | PIT Law & amendments; Circular 111/2013; Resolution 110/2025/UBTVQH15 `[verify-legal]` | BR-PPL-016/017 (amount used by payroll) |
| C18 | Personal identification number used as personal tax code. | Law on Tax Administration; Circular 86/2024/TT-BTC `[verify-legal]` | BR-PPL-013 |
| C19 | Personal data: consent, sensitive-data classification, impact assessment, data-subject rights, breach notification (e.g., 72h). | PDPL 2025 (91/2025/QH15, effective 1/1/2026) and guiding decree; Decree 13/2023/NĐ-CP `[verify-legal]` | BR-PPL-150–154 |
| C20 | Training contract and cost repayment if the employee leaves before the committed period. | LC Art. 61–62 `[verify-legal]` | BR-PPL-101 |
| C21 | Internal labour regulations mandatory (registered) for employers with ≥ 10 employees; discipline must cite them. | LC Art. 118–119 `[verify-legal]` | BR-PPL-080 |
| C22 | Female employees with child < 12 months: 60 min paid break per day; pregnant heavy-work employees from 7th month: reduced 1 h/day. | LC Art. 137 `[verify-legal]` | BR-PPL-021 → T&A |

---

## 6. Key business entities

| Entity | Purpose | Key attributes |
|---|---|---|
| `hr.person` | Real individual (tenant-scoped) | person_id, legal names, DOB, gender, nationality, ethnicity, religion (sensitive), national_id_no, tax_code, si_code, photo, verification status |
| `hr.person_identity_doc` | ID documents & permits | doc_type, number, issue date/place, expiry, files |
| `hr.person_address` | Addresses (effective-dated) | type, province, commune, line, from/to |
| `hr.person_contact` | Phones, emails | type, value, primary |
| `hr.emergency_contact` | Emergency contacts | name, relationship, phone |
| `hr.family_member` | Family members | relationship, name, DOB, ID no., tax code, income status |
| `hr.pit_dependant` | Dependant registration | family_member_id, employment_id (claiming), from_month, to_month, status, tax-registration ref |
| `hr.bank_account` | Salary accounts | bank, branch, account no., holder, split, default, from/to |
| `hr.education`, `hr.certificate` | Qualifications | level, institution, major, year / type, issuer, expiry |
| `hr.work_permit` | Foreign worker permit | number, type (permit/exempt), job, from/to |
| `hr.special_status` | Protection & regime flags | type (pregnancy, child<12m, disability, minor, union…), from/to |
| `hr.employment` | Engagement with a legal entity | employment_id, person_id, legal_entity_id, employee_no, hire date, seniority date, status, primary flag, worker category, rehire eligible |
| `hr.assignment` | Effective-dated placement | employment_id, type, position_id, org_unit_id, job, grade, manager, location, cost centre(s), FTE, working regime, from/to, reason, decision_id |
| `hr.salary_record` | Effective-dated pay terms | employment_id, pay scale/grade/step, base salary, SI salary, contract allowances, probation %, from/to, reason, decision_id |
| `hr.contract` | Labour contract / probation / service | type, number, sign date, start, end, sequence (nth definite), signatory, e-sign status, file |
| `hr.contract_appendix` | Amendments | contract_id, changes, effective date |
| `hr.contract_suspension` | Suspension periods | reason, from/to, resumption date |
| `hr.decision` | Quyết định | number, type, sign date, effective date, signatory, subject employment, file |
| `hr.hr_event` | Timeline / outbox | event type, employment, effective date, payload, retro flag, published status |
| `hr.separation` | Resignation/termination case | reason code, legal ground, notice date, required notice, LWD, severance type, service-year inputs, settlement deadline, status |
| `hr.checklist_template`, `hr.checklist`, `hr.checklist_task` | On/offboarding | template, owner, due, status |
| `hr.discipline_case` | Discipline | violation, evidence, meeting, form, period, expunge date, compensation |
| `hr.reward` | Rewards | type, level, amount, tax flag, decision, pay period |
| `hr.appraisal_cycle`, `hr.appraisal`, `hr.goal` | Performance | cycle, template, goals, scores, final rating |
| `hr.course`, `hr.training_session`, `hr.training_enrolment`, `hr.training_bond` | Training | course, session, attendance, result, bond cost & period |
| `hr.expense_claim`, `hr.expense_line`, `hr.cash_advance` | Expenses | category, amount, invoice data, status, route (payroll/finance) |
| `hr.business_trip` | Công tác | destination, dates, purpose, status |
| `hr.document` | Employee documents | category, file ref, confidentiality, retention |
| `hr.consent_record` | PDPL consent | purpose, text version, given/withdrawn at, channel |
| `hr.data_request` | DSAR | type, status, SLA, response |
| `hr.statutory_report` | Labour reports | type, legal entity, period, generated file, submitted at |

All tables carry `tenant_id`, audit columns, and (where relevant) `valid_from`/`valid_to`.

---

## 7. Cross-module interfaces

Event names are logical (`hr.<Event>`); transport (outbox → message bus vs. synchronous API) is decided by architecture. Every event carries: `tenant_id`, `person_id`, `employment_id`, `legal_entity_id`, `effective_date`, `event_id`, `occurred_at`, `retro` flag, `correlation_id` (decision/case).

### 7.1 Consumes

| # | What | From module | When / trigger | Used for |
|---|---|---|---|---|
| IN-01 | Legal entities (name, tax code, address, SI unit code, region for minimum wage, legal representative) | core | Reference lookup; on change | Employment, contracts, decisions, statutory reports |
| IN-02 | Org units tree (effective-dated) and cost centres | core | Lookup; `OrgUnitChanged` / reorg events | Assignments; mass reassignment on reorg |
| IN-03 | Positions (with headcount, job, grade, parent position, manager position) and job catalogue / job grades | core | Lookup; `PositionChanged` / `PositionClosed` | Assignments, incumbency, vacancy, manager derivation |
| IN-04 | Work locations (address, province, minimum-wage region) | core | Lookup | Assignment, minimum-wage validation (region) |
| IN-05 | Users, roles, permissions, data scopes (by legal entity/org unit), field-level permission for sensitive data | core | Each request | Access control, ESS/MSS, masking |
| IN-06 | Workflow engine (approval chains, delegation, SLA) | core | Change requests, separation, discipline, expenses, dependants, data changes | Approvals |
| IN-07 | Notifications (email, in-app, push) and templates | core | Alerts and reminders | Expiry alerts, checklist reminders, deadlines |
| IN-08 | Audit log service | core | Every write / sensitive reveal | Audit, PDPL access logs |
| IN-09 | Reference data: administrative units (2-level from 2025), nationalities, ethnicities, banks/branches, education levels, relationships, document numbering sequences | core | Lookup | Personal data validation |
| IN-10 | Statutory parameters: regional minimum wage by region & date; retirement-age roadmap; statutory notice/probation limits (as parameters) | core (parameter store) / payroll for wage params — **owner to confirm, see Q1** | Validation at effective date | BR-PPL-041, 051, 071 |
| IN-11 | `OfferAccepted` / hire handover: candidate personal data, documents, consents given in recruitment, requisition & position, legal entity, start date, offered salary/grade, contract type & probation terms, recruiter/hiring manager | recruitment | When candidate accepts offer | Create pre-hire (P1) |
| IN-12 | `OfferWithdrawn` / `CandidateNoShow` | recruitment | Before start date | Cancel pre-hire (`HireCancelled`) |
| IN-13 | Leave balances (annual leave remaining, as of last working day) and unpaid-leave periods | time-attendance | On `TerminationScheduled` and at LWD | Final settlement inputs (unused leave payout), offboarding clearance |
| IN-14 | Unpaid leave / long absence periods > threshold, and maternity leave periods | time-attendance | On approval | Seniority computation, contract suspension, service-year exclusion for severance, protected status |
| IN-15 | Attendance-completion status for the final period | time-attendance | Before final settlement | Offboarding clearance |
| IN-16 | Business-trip and training attendance confirmation (optional) | time-attendance | Period close | Expense validation |
| IN-17 | Pay scales / grades / steps definitions and allowance catalogue (code, taxable, SI-insurable) | payroll | Lookup | Salary records, contract allowance clauses |
| IN-18 | UI contribution periods (months with UI paid) and SI participation history for the employee | payroll | On separation | Severance service-year exclusion (BR-PPL-074) |
| IN-19 | Average contract salary of last 6 months; computed final-settlement amounts (severance, job-loss, leave payout, deductions) and status `FinalSettlementPaid` | payroll | After final settlement run | Termination decision text, separation case closure, settlement deadline tracking |
| IN-20 | Income data for HR letters (income confirmation) and payslip link for ESS | payroll | On request | BR-PPL-132, ESS |
| IN-21 | SI book closing status (chốt sổ) / SI declaration status for hire & termination | payroll | After declaration | Post-separation obligations (BR-PPL-076), onboarding checklist |
| IN-22 | Advance deduction / expense reimbursement payment status | payroll | After payroll run | Advance settlement status |
| IN-23 | Tenant context, enabled modules/features, plan limits (max active employees) | saas-platform | Each request; on hire | Feature toggles, licence check before activating employment |
| IN-24 | File/object storage with virus scanning & encryption | saas-platform | Document upload/generation | Documents |
| IN-25 | E-signature / e-labour-contract provider gateway and national e-contract platform connector; status callbacks | saas-platform | Contract/decision signing | BR-PPL-058, 134 |
| IN-26 | VNeID / identity verification connector | saas-platform | Pre-hire / data change | BR-PPL-012 |
| IN-27 | DSAR orchestration framework & retention scheduler | saas-platform | Data-subject request; daily | BR-PPL-153/154 |
| IN-28 | Data import framework incl. opening-balance templates (changed 2026-10-16, D-09) | saas-platform | Tenant onboarding / go-live | Bulk load of persons, employments, current contract and salary, dependants, and the history needed for severance |

### 7.2 Provides

| # | What (event / data) | To module | When / trigger | Key payload |
|---|---|---|---|---|
| OUT-01 | `EmployeeHired` (incl. rehire, concurrent hire) | payroll, time-attendance, core, recruitment, saas-platform | Employment activated on hire date (also future-dated preview at approval) | employee_no, legal entity, hire date, seniority date, employment type, worker category, primary-employment flag, initial assignment & salary refs, contract type |
| OUT-02 | `HireCancelled` | payroll, time-attendance, core, recruitment | Pre-hire cancelled | employment_id, reason |
| OUT-03 | Employee master read API (current & as-of-date): person, employment, assignment, contract summary, salary summary (permission-gated) | all modules | On demand | As-of date query |
| OUT-04 | `AssignmentChanged` (transfer, promotion, demotion, reorg, manager change, location change, cost-centre change, concurrent position start/end) | time-attendance, payroll, core, recruitment | Effective date (and at approval for future-dated) | old/new org unit, position, job, grade, manager, location, cost-centre split, FTE, working-time regime, assignment type, retro flag |
| OUT-05 | `SecondmentStarted` / `SecondmentEnded` | payroll, time-attendance, core | Effective date | home/host legal entity, paying entity, recharge flag |
| OUT-06 | `TemporaryTransferStarted` / `Ended` with salary-protection rule | payroll, time-attendance | Effective date | days used in year, protected salary until date |
| OUT-07 | `SalaryChanged` | payroll | Effective date (approved) | pay scale/grade/step, base salary, SI contribution salary, contract allowances (codes & amounts), probation %, currency, retro flag, decision no. |
| OUT-08 | `ContractSigned` / `ContractRenewed` / `ContractConverted` / `ContractAppendixSigned` | payroll, time-attendance, core | On signature completion | contract type, number, start/end, duration months (drives SI mandatory participation ≥ 1 month and PIT 10% flat-rate rule for < 3 months), probation flag |
| OUT-09 | `ProbationStarted` / `ProbationCompleted` (pass/fail) | payroll, time-attendance, recruitment | Start; outcome date | probation salary %, end date, outcome (quality-of-hire for recruitment) |
| OUT-10 | `ContractExpiring` | core (notifications), recruitment (backfill planning) | Configured offsets before end | end date, recommended action |
| OUT-11 | `ContractSuspended` / `ContractResumed` | payroll, time-attendance | Effective date | reason, from/to (stop pay, SI handling, no attendance expected) |
| OUT-12 | `TerminationScheduled` | payroll, time-attendance, core, recruitment | Separation approved | last working day, reason code, legal ground, notice compliance (shortfall days), protected flag |
| OUT-13 | `EmployeeTerminated` + **final-settlement trigger** | payroll, time-attendance, core, recruitment, saas-platform | Day after LWD | LWD, reason/legal ground, SI/UI termination code, severance vs job-loss eligibility, service years total / excluded (UI periods, already-compensated), notice-shortfall compensation (either direction), training-bond repayment, outstanding advances, material-compensation balance, settlement deadline |
| OUT-14 | `TerminationCancelled` (resignation withdrawn) | payroll, time-attendance, core, recruitment | On cancellation | employment_id |
| OUT-15 | `PersonalDataChanged` (name, ID number, nationality/residency, address, tax code, SI code) | payroll, core, time-attendance | On approval | changed fields (minimised), effective date — payroll needs tax code, SI code, nationality & residency for PIT resident status |
| OUT-16 | `BankAccountChanged` | payroll | On approval (step-up verified) | accounts, split, default, effective from |
| OUT-17 | `DependantChanged` (registered / ended / transferred to another employment) | payroll | On HR approval | dependant id, relationship, tax code, from/to month, claiming employment — drives PIT family deduction |
| OUT-18 | PIT commitment & authorisation flags: Form 08/CK-TNCN commitment (income below threshold, no 10% withholding), PIT finalization authorisation (ủy quyền quyết toán) | payroll | On ESS submission / year-end | year, commitment type `[verify-legal]` |
| OUT-19 | `SpecialStatusChanged` (pregnancy, child < 12 months, disability, minor, elderly, heavy/hazardous job, union membership) | time-attendance, payroll | On approval | type, from/to — T&A reduced hours/extra leave; payroll union fee, SI benefit regimes |
| OUT-20 | Seniority / service data (seniority date, recognised prior service, excluded periods) | time-attendance, payroll | On change | Annual-leave seniority increment (T&A), seniority allowance (payroll) |
| OUT-21 | Line manager / reporting relationship (effective-dated) & employee-user link; `UserProvisioningRequested` / `UserDeactivationRequested` | core | Hire, manager change, termination (access revocation date) | employee_id, user id/email, manager employee_id, revocation date |
| OUT-22 | Position incumbency & vacancy (filled/vacant/over-filled per position) | core, recruitment | On assignment change | position_id, incumbents, FTE used |
| OUT-23 | Internal candidate profile (skills, education, certificates, appraisal rating — with consent) | recruitment | On search | for internal mobility |
| OUT-24 | Rehire eligibility / former-employee lookup by ID number | recruitment | On candidate creation | eligible flag, last LWD, reason category (no sensitive detail) |
| OUT-25 | `RewardApproved` (monetary) | payroll | On decision | amount, pay period, taxable flag, component code |
| OUT-26 | `DisciplineDecided` with payroll impact: salary-increase deferral, demotion (→ also `SalaryChanged`), suspension salary advance %, material-compensation deduction schedule | payroll | On decision | type, amounts, period, cap |
| OUT-27 | `AppraisalFinalised` / periodic KPI score | payroll | Cycle close (or monthly for KPI pay) | period, score, rating — performance bonus / KPI pay |
| OUT-28 | `ExpenseReimbursementApproved` | payroll (or finance export) | On final approval | amount, category, taxable flag, pay period |
| OUT-29 | `AdvanceDeductionRequested` | payroll | Overdue unsettled advance / at termination | amount, period, consent ref |
| OUT-30 | `BusinessTripApproved` | time-attendance | On approval | dates, half-days — attendance code "công tác" |
| OUT-31 | `TrainingAttendanceRecorded` (optional) | time-attendance | Session close | dates, hours |
| OUT-32 | Active employee count per legal entity (daily snapshot) | saas-platform | Daily / on hire & termination | for billing & plan limits |
| OUT-33 | Personal-data inventory, consent status and DSAR export package for People data; anonymisation-completed confirmation | saas-platform | On DSAR / retention job | per-person data package |
| OUT-34 | Headcount & turnover facts (by org, legal entity, category) for reporting/BI | core (reporting) / saas-platform BI | Daily | snapshot facts |

---

## 8. Reports & analytics

| # | Report | Audience | Notes |
|---|---|---|---|
| R1 | Employee master list (as-of date, filters, configurable columns, masking) | HR | Export Excel |
| R2 | Headcount & FTE by legal entity / org unit / location / category, trend | HR, management | |
| R3 | Movements report: hires, transfers, promotions, terminations in period | HR | |
| R4 | Turnover & retention (voluntary/involuntary, by tenure band, regretted) | HR, management | |
| R5 | Contract expiry & probation-ending list; contracts approaching conversion rule | HR | Daily alert source |
| R6 | ID / work permit / certificate expiry list | HR | |
| R7 | Retirement forecast (next 12–24 months) per statutory roadmap | HR | |
| R8 | Labour management book (sổ quản lý lao động) | HR, inspector | BR-PPL-140 |
| R9 | Semi-annual labour-usage report (Decree 145) | HR | BR-PPL-141 |
| R10 | Monthly labour-fluctuation notice (Employment Service Centre) | HR | BR-PPL-142 |
| R11 | Dependant register (PIT) and changes per month | HR, payroll | |
| R12 | Salary change history & cost impact of changes | C&B | Permission-gated |
| R13 | Discipline & reward register | HR | |
| R14 | Appraisal distribution & calibration results | HR, management | Phase 2 |
| R15 | Training hours, cost per employee, compliance (mandatory training) | L&D | Phase 2 |
| R16 | Expense & advance aging (outstanding advances) | Finance | |
| R17 | Onboarding/offboarding checklist completion & overdue tasks | HR | |
| R18 | Demographics (age, gender, education, seniority) | HR | Sensitive fields aggregated only |
| R19 | PDPL: consent coverage, sensitive-data access log, DSAR SLA | DPO | |
| R20 | Data-quality report (missing mandatory fields: tax code, SI code, bank, emergency contact) | HR | Pre-payroll check |

---

## 9. Non-functional requirements (module-specific)

| ID | Requirement | Pri |
|---|---|---|
| NFR-PPL-001 | Effective-dating: any employee query must support "as of date X" and "as known on date Y" (correction history) for assignments, salary, contracts. | M |
| NFR-PPL-002 | Event publication is transactional (outbox) — no committed HR change without a corresponding event; at-least-once delivery with idempotent `event_id`. | M |
| NFR-PPL-003 | Employee profile page loads ≤ 2 s (p95) for tenants up to 10,000 employees; employee list search ≤ 1 s (p95). | M |
| NFR-PPL-004 | Bulk operations (mass change, document generation) handle 5,000 employees in ≤ 10 min, asynchronously, with progress and per-row error report. | S |
| NFR-PPL-005 | Sensitive fields (ID numbers, bank accounts, health, religion, ethnicity) encrypted at field level; masked in logs and non-production copies. | M |
| NFR-PPL-006 | Every read of sensitive data and every write logged with actor, purpose, before/after (writes). | M |
| NFR-PPL-007 | Opening employee data import from the standard Excel templates (BR-SAAS-069): persons, employments (incl. original hire and seniority dates), current assignment, current contract (with count of prior definite-term contracts), current salary plus the salary history needed for severance and average bases, dependants (with registration months), bank accounts. Validated with a reconciliation report; idempotent re-runs. No legacy database migration (changed 2026-10-16, D-09). | M |
| NFR-PPL-008 | Strict tenant isolation: no cross-tenant person matching. | M |
| NFR-PPL-009 | UI bilingual VI/EN; Vietnamese collation and diacritics-insensitive search on names. | M |
| NFR-PPL-010 | Generated documents in PDF/A for archiving; templates render Vietnamese fonts correctly. | M |
| NFR-PPL-011 | ESS usable on mobile browsers (≥ 360 px width) with step-up authentication for bank account changes. | M |
| NFR-PPL-012 | Document storage retention and legal hold configurable per category; deletion is verifiable. | S |

---

## 10. Assumptions, open questions, risks

### 10.1 Assumptions
- A1. Position, org unit, job, grade catalogues are mastered in `core`; People only references them.
- A2. Payroll computes every money amount (severance, job-loss, leave payout, deductions); People only provides eligibility, inputs and triggers.
- A3. SI/HI/UI declarations (hire/termination/salary change) are produced by `payroll` from People events; People holds the SI code and book custody status only.
- A4. Leave and attendance (incl. maternity leave as a leave type) are mastered in `time-attendance`; People holds only statuses that change legal regime (suspension, special status).
- A5. A tenant may contain several legal entities; concurrent employment across tenants is not supported.
- A6. E-signature and VNeID connectors are provided by `saas-platform` as shared services.

### 10.2 Open questions
| # | Question | Owner |
|---|---|---|
| Q1 | Who owns statutory parameters (regional minimum wage, retirement roadmap, notice/probation limits): core parameter store or payroll? People needs read access either way. | PM / Core BA / Payroll BA |
| Q2 | Are pay scales (thang bảng lương) and allowance catalogue mastered in payroll (assumed) or core? | Payroll BA |
| Q3 | Does `payroll` provide UI participation periods (IN-18) from its own SI history, or does People need to store pre-system SI history from the SI book? (Opening-balance import impact: the SI contribution history template, BR-SAAS-069 — changed 2026-10-16, D-09.) | Payroll BA |
| Q4 | Monthly KPI scores for KPI-linked pay: MVP need for pilot tenant? Would make BR-PPL-093 `M`. | PM |
| Q5 | Expense reimbursement route: via payroll or direct finance export — per tenant? Is an accounting integration in scope? | PM / Finance |
| Q6 | Business trips: owned by People (expenses) or T&A (attendance)? Proposed: People owns request, T&A consumes. | T&A BA |
| Q7 | Is collaborator/service contract (non-labour) handled as an Employment in People or as a separate "contractor" entity for PIT only? | Payroll BA |
| Q8 | E-labour contract: which providers and is submission to the national platform mandatory for pilot tenants in 2026? | Legal consultant |
| Q9 | Retention periods for HR data after separation (labour, SI, tax, PDPL interplay). | Legal consultant / DPO |
| Q10 | Recruitment consents: can People reuse candidate consent, or must new employee consent be collected at pre-hire? | DPO / Recruitment BA |
| Q11 | Should ESS for former employees be in MVP (PIT certificate, work confirmation)? | PM |

### 10.3 Risks
| # | Risk | Impact | Mitigation |
|---|---|---|---|
| RK1 | Frequent legal changes (PDPL 2025, Employment Law 2025, e-contract decree, admin-unit merger 2025) | Rework, non-compliance | Parameterise; `[verify-legal]` review gate; legal consultant sign-off |
| RK2 | Quality of customer-supplied opening data (duplicates, missing ID/tax codes, 3-level addresses) (changed 2026-10-16, D-09) | Bad person matching, payroll errors | Data-quality report R20, merge tool, validate-only import and opening-balance reconciliation (BR-SAAS-072) |
| RK3 | Effective-dated retro changes after payroll close | Pay errors | Retro flag on events; payroll retro process agreed in gap analysis |
| RK4 | Event contract drift between People and consumers | Integration defects | Versioned event schemas, contract tests |
| RK5 | Sensitive-data exposure (bank, ID, health) | Legal penalties, reputation | Field encryption, masking, access logging, DPO reports |
| RK6 | Scope breadth (appraisal, training, expenses) vs MVP timeline | Delay | MVP = all `M` items (incl. probation appraisal BR-PPL-094); expenses (BR-PPL-110–113) deferred unless Q5 resolved; Phase 2 for full appraisal/training |
