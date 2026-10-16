# People Backlog: Phase 1 (v0.1, 2026-10-16)

| Item | Value |
|---|---|
| Owner | BA – People |
| Scope | Every **P1** BR-PPL and NFR-PPL in [mvp-scope.md](../brd/mvp-scope.md) §9.2: **47 BR + 10 NFR = 57 requirements** |
| Sources | [02-people.md](../brd/modules/02-people.md) v0.2; [decision-log.md](../../00-project-management/decision-log.md) rounds 1–3; [estimation-guide.md](estimation-guide.md) v0.1 |
| Sizing | Fibonacci SP against the reference scale of the estimation guide §4 (1 SP ≈ 1 developer-day, before the +40% delivery overhead) |

**Engine ownership.** Core builds the workflow engine, notifications, document service and PDF templates, numbering, import engine, audit store and authorisation (roles, data scopes, field masking). SaaS builds the ESS/MSS shell, step-up authentication, Legal Parameter Service, consent registry, PD inventory and DSR orchestration. Technical enablers build the outbox/event bus, tenancy/RLS, effective-dating helpers in `shared/common`, field encryption key service and job queue. Stories here count **only the People work**: People entities, rules, screens, and the registration/configuration of those engines (process types, templates, permissions, import types, PD fields, event schemas).

**Decisions applied.** No mobile app (ESS = responsive web, R5/OD-9); no e-signature or e-labour contract (R2-10, BR-PPL-058 P2), so contracts and decisions are signed on paper and the scan is uploaded; no VNeID (BR-PPL-012 P2); no MSS pages (BR-PPL-121 P2): managers approve through the Core inbox; no ESS personal-data change requests (BR-PPL-022 P2): HR keys changes, except dependants and bank accounts, which BR-PPL-120 keeps as ESS requests; no mass change or reorg mapping (BR-PPL-036/039 P2); VND only (R2-09); concurrent employment across legal entities of one tenant is basic P1 (R2-16); insurance per type is the legal parameter `insurance.multi_contract_rule` with SI/UI/HI = FIRST_CONTRACT, OA-OD = EACH_EMPLOYER (R3-03, `[verify-legal]`); opening-balance import replaces migration (D-09), customer prepares the files (R2-13), custom attributes are mapped to standard fields or attachments (R3-09); minimal qualification field for the labour book (R3-07); former employees log in by e-mail OTP (OD-8); salary amounts are not column-encrypted (X-01).

**"Depends on" notation.** Core/SaaS backlogs have not yet numbered their stories, so dependencies on them cite the P1 requirement that delivers the engine (e.g. `BR-CORE-050`); technical enablers are cited as `TECH:<enabler>` (names from estimation-guide §2). Replace them with `US-CORE-…`, `US-SAAS-…`, `US-TECH-…` IDs when the PM consolidates.

Personas: **HR** = HR Administrator (C&B / HR Ops); **C&B** = HR user holding the `compensation` permission; **Employee** = ESS user; **Former employee**; **DPO**; **Payroll/T&A** = consuming module (system persona); **Implementation consultant** = our go-live consultant.

---

## 1. Epics

| Epic ID | Name | Goal | Stories | SP |
|---|---|---|---|---|
| EP-PPL-01 | Person & personal data | One person per individual per tenant with the identity, tax, SI, residency and special-status data every statutory output needs | 16 | 65 |
| EP-PPL-02 | Employment & concurrent employment | Employments per legal entity, hire/activation, concurrent employment with primary employment per purpose and insurance participation per type | 10 | 53 |
| EP-PPL-03 | Family, dependants & bank accounts | PIT dependants registered once and approved; salary accounts with split | 6 | 29 |
| EP-PPL-04 | Effective-dated assignments | Full assignment history, job changes with approval, future/retro changes, temporary transfer rule | 8 | 40 |
| EP-PPL-05 | Salary record (agreed compensation) | People as system of record for GROSS/NET agreed pay, changed only by decision/appendix, under the `compensation` permission | 6 | 29 |
| EP-PPL-06 | Labour contracts & appendices | Labour Code contract lifecycle: types, probation, two-definite rule, expiry, appendices, suspension, documents | 10 | 48 |
| EP-PPL-07 | HR decisions & People notifications | Numbered quyết định for every P1 HR change; People alert templates | 3 | 13 |
| EP-PPL-08 | Separation & final-settlement trigger | Lawful termination: reasons, notice, protection, severance eligibility, events to Payroll | 8 | 40 |
| EP-PPL-09 | Labour book & statutory labour reports | Decree 145 labour book and semi-annual report, monthly labour-fluctuation notice | 4 | 27 |
| EP-PPL-10 | ESS web pages for People | Employee self-service in the SaaS shell: profile, employment, dependants, bank, resignation | 7 | 26 |
| EP-PPL-11 | Employee events & read API | Transactional, versioned People events and the as-of employee read API for other modules | 4 | 23 |
| EP-PPL-12 | PDPL for People data | Classification, consent references, masking, encryption, access logging, DSR handlers | 6 | 32 |
| EP-PPL-13 | Employee-master opening-balance import | Excel template, validation, idempotent load and reconciliation for a mid-year go-live | 5 | 31 |
| **Total** | | | **93** | **456** |

---

## 2. Stories

### EP-PPL-01 Person & personal data

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-001 | EP-PPL-01 | As HR, I want the People module foundation (schema `hr`, RLS on every table, People function permissions and data scopes registered in Core) so that every People feature is tenant-isolated and permission-checked from day one. AC: every `hr` table has `tenant_id` + RLS policy and a cross-tenant negative test; People permissions/scopes (legal entity, org unit, SELF) visible in Core role admin; no cross-tenant person lookup path exists | NFR-PPL-008 | 5 | TECH:walking-skeleton, TECH:tenancy-RLS, BR-CORE-031, BR-CORE-032, BR-CORE-033 | Registration only; scope enforcement is Core (BR-CORE-035) |
| US-PPL-002 | EP-PPL-01 | As HR, I want to create and maintain a person (full name with diacritics + generated unaccented form, DOB, gender, nationality, ethnicity, religion, place of origin) so that identity data for SI/PIT and the labour book is captured once. AC: lookups from Core reference data; religion/ethnicity flagged sensitive; changes audited | BR-PPL-010, NFR-PPL-008 | 5 | US-PPL-001, BR-CORE-090 | |
| US-PPL-003 | EP-PPL-01 | As HR, I want the system to detect an existing person by CCCD (12 digits), or passport + nationality, tax code or SI number on create so that a tenant never holds two persons for one individual. AC: hard block on same CCCD/passport+nationality; warning with candidate list on tax code/SI match; matching never crosses tenants | BR-PPL-001, NFR-PPL-008 | 3 | US-PPL-002, US-PPL-086 | Matching on encrypted IDs uses the blind index of US-PPL-086 |
| US-PPL-004 | EP-PPL-01 | As HR, I want to merge two duplicate persons so that employments, dependants, documents and bank accounts end up on one person. AC: preview of what moves; re-points child rows of every People table; merge reason and before/after written to audit; `PersonalDataChanged` + merge event published | BR-PPL-001 | 5 | US-PPL-003, US-PPL-080 | Rare but needed after opening-balance import (RK2) |
| US-PPL-005 | EP-PPL-01 | As HR, I want effective-dated permanent and current addresses on the post-2025 two-level units (province/commune), with legacy 3-level text kept, so that documents and reports print the correct address at any date. AC: no overwrite, history kept; legacy address stored read-only from import; address validated against Core admin units at the effective date | BR-PPL-010 | 5 | US-PPL-002, BR-CORE (reference data) | `[verify-legal]` admin-unit transition rules |
| US-PPL-006 | EP-PPL-01 | As HR, I want phones and e-mails (work and personal, one primary each) on the person so that notifications and former-employee OTP login reach them. AC: format validation; personal e-mail flagged for OD-8 use | BR-PPL-010 | 2 | US-PPL-002 | |
| US-PPL-007 | EP-PPL-01 | As HR, I want identity documents (CCCD/căn cước, passport, work permit, temporary residence card) with number, issue date/place, expiry and scans so that statutory identifiers are evidenced and renewed on time. AC: scans stored via Core document service; nightly job raises expiry alerts at a tenant-configured offset; expiry list report | BR-PPL-011 | 5 | US-PPL-002, BR-CORE-100, BR-CORE-101, US-PPL-059 | |
| US-PPL-008 | EP-PPL-01 | As HR, I want the personal tax code defaulted to the 12-digit personal identification number, with the legacy 10-digit MST kept as history, so that PIT filings use the right identifier. AC: default applied when the law treats them as equal; legacy MST retained with validity dates; format validation | BR-PPL-013 | 3 | US-PPL-002 | `[verify-legal]` Circular 86/2024 |
| US-PPL-009 | EP-PPL-01 | As HR, I want the SI code (mã số BHXH), HI card number, registered initial health-care facility and SI book custody status so that Payroll can declare insurance and HR can track the book. AC: 10-digit SI code validation; custody status held/returned with date; facility from reference list | BR-PPL-014 | 3 | US-PPL-002 | |
| US-PPL-010 | EP-PPL-01 | As HR, I want a minimal qualification field (education level, major) on the person so that the labour management book has its qualification column. AC: level from Core reference list; shown in profile and labour book | BR-PPL-019 | 1 | US-PPL-002 | R3-07 minimal; full education/certificates P2 |
| US-PPL-011 | EP-PPL-01 | As HR, I want work permit / exemption records (number, type, validity, job on permit, legal entity) for foreign workers so that nobody works without a valid permit. AC: block an assignment or definite contract end beyond permit validity unless a renewal is recorded; alerts at 45/60 days (configurable) | BR-PPL-020 | 5 | US-PPL-002, US-PPL-033, US-PPL-047, US-PPL-059 | `[verify-legal]` Decree 219/2025 |
| US-PPL-012 | EP-PPL-01 | As HR, I want effective-dated special statuses (pregnancy, child under 12 months, disability, minor, elderly, union membership, heavy/hazardous job) so that T&A, Payroll and termination protection apply the right regime. AC: from/to dates, evidence attachment; minor/elderly derived from DOB with legal age parameters; `SpecialStatusChanged` published | BR-PPL-021 | 5 | US-PPL-002, US-PPL-080 | Health-related statuses are sensitive (US-PPL-083) |
| US-PPL-013 | EP-PPL-01 | As HR, I want an effective-dated tax residency status (resident/non-resident) with basis, arrival and departure dates and optional days in VN per year so that Payroll applies the correct PIT method. AC: no overlapping periods; change goes through HR approval (Core workflow process type registered); approved change published in `PersonalDataChanged` | BR-PPL-023 | 5 | US-PPL-002, BR-CORE-050, US-PPL-080 | `[verify-legal]` Circular 111/2013; People does not compute the 183-day test |
| US-PPL-014 | EP-PPL-01 | As HR, I want to review Payroll's 183-day residency proposals and approve or reject them so that residency changes stay HR-approved. AC: consumes payroll proposals (IN-32) into a review list; approve creates the residency record of US-PPL-013 with source = payroll proposal; reject keeps a reason | BR-PPL-023 | 3 | US-PPL-013, US-PPL-082 | Depends on BR-PAY-065 delivery |
| US-PPL-015 | EP-PPL-01 | As HR, I want an employee list with diacritics-insensitive name search, filters (legal entity, org unit, status, category) and an as-of date so that I find anyone fast. AC: "nguyen van an" finds "Nguyễn Văn An"; Vietnamese collation sort; p95 ≤ 1 s at 10,000 employees in a load test; Excel export via SaaS list export with masking | NFR-PPL-003, NFR-PPL-009 | 5 | US-PPL-002, US-PPL-033, BR-SAAS-046 | Uses unaccented column + trigram index |
| US-PPL-016 | EP-PPL-01 | As HR, I want a tabbed employee profile (person, employment, assignment, contract, salary if permitted, dependants, bank, documents) so that I see one employee in one place. AC: p95 ≤ 2 s at 10,000 employees; sensitive fields masked by default; tabs hidden without permission | NFR-PPL-003, BR-PPL-010 | 5 | US-PPL-002, US-PPL-017, US-PPL-085 | |

### EP-PPL-02 Employment & concurrent employment

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-017 | EP-PPL-02 | As HR, I want an employment per legal entity (employee number from a per-legal-entity pattern, hire, original hire and seniority dates, status, worker category, employment type) so that every engagement is recorded as the law and Payroll need. AC: employee number unique per tenant via Core numbering; status machine Pre-hire → Active → Notice/Suspended → Terminated with guarded transitions; collaborator/service type allowed | BR-PPL-003 | 5 | US-PPL-002, BR-CORE-085 | Q7 (collaborator as employment) assumed yes |
| US-PPL-018 | EP-PPL-02 | As HR, I want a direct-hire wizard (match person → employment → first assignment → contract → salary record) so that a new hire is complete in one guided flow. AC: person match per US-PPL-003 offers new / rehire-as-new-employment / concurrent path; whole hire saved in one transaction; licence limit checked (IN-23); missing mandatory data flags employment "incomplete" | BR-PPL-001, BR-PPL-002, BR-PPL-003 | 8 | US-PPL-003, US-PPL-017, US-PPL-033, US-PPL-041, US-PPL-047 | Rehire seniority choice is P2 (BR-PPL-005); recruitment hand-off is P2 with Recruitment |
| US-PPL-019 | EP-PPL-02 | As HR, I want hire approval configured as a Core workflow process type and the employment activated on the hire date so that `EmployeeHired` is published only for approved, effective hires. AC: process type + default approval chain registered; daily activation job sets Active and publishes `EmployeeHired` (preview flag at approval for future dates) | BR-PPL-003 | 3 | US-PPL-018, BR-CORE-050, BR-CORE-054, US-PPL-080 | Registration/config only |
| US-PPL-020 | EP-PPL-02 | As HR, I want to cancel a pre-hire before the start date so that no-shows never reach T&A and Payroll. AC: only Pre-hire status; reason mandatory; `HireCancelled` published; child records closed | BR-PPL-003 | 2 | US-PPL-019 | |
| US-PPL-021 | EP-PPL-02 | As HR, I want to hire an existing employee into another legal entity of the tenant as a concurrent employment so that one person can work for two entities (R2-16). AC: at most one live employment per legal entity at any date (DB exclusion constraint); hire_type CONCURRENT in `EmployeeHired`; same-entity attempt redirected to assignment change | BR-PPL-002 | 5 | US-PPL-018 | |
| US-PPL-022 | EP-PPL-02 | As HR, I want to designate the effective-dated primary employment per purpose (PIT_FAMILY_DEDUCTION, SOCIAL_INSURANCE) so that family deduction and SI are applied through exactly one employment. AC: exactly one primary per person per purpose at any date (auto-set for single employment, required choice for concurrent); purposes may differ; change approved and `PrimaryEmploymentChanged` published; termination of the primary forces a re-designation | BR-PPL-004 | 8 | US-PPL-021, BR-CORE-050, US-PPL-080 | Invariant enforced in DB and service; RK7 |
| US-PPL-023 | EP-PPL-02 | As Payroll, I want insurance participation per type (SI, HI, UI, OA-OD) per employment, effective-dated and derived from the legal parameter `insurance.multi_contract_rule` (FIRST_CONTRACT / HIGHEST_SALARY / EACH_EMPLOYER) so that each legal entity declares only the insurance it owes. AC: participation recomputed on hire, termination, contract and salary change and on a new parameter pack; HR can record an exemption with reason; result travels with `PrimaryEmploymentChanged`; changing the HI rule to HIGHEST_SALARY needs only the parameter | BR-PPL-004 | 13 | US-PPL-022, US-PPL-041, TECH:legal-parameter-service | **[high-uncertainty]** `[verify-legal]` base 8 +1 step: sponsor (R3-03) and PM disagree on HI; HIGHEST_SALARY needs cross-entity salary comparison and tie rules |
| US-PPL-024 | EP-PPL-02 | As HR, I want the system to stop a dependant or the personal deduction being claimed through two employments so that family deduction is applied once. AC: dependant claiming employment must be the PIT primary at every month of the claim; when the primary changes, open dependants move to the new primary with `DependantChanged` (transferred) | BR-PPL-004, BR-PPL-016 | 5 | US-PPL-022, US-PPL-028 | |
| US-PPL-025 | EP-PPL-02 | As HR, I want a concurrent-employment exception report (no primary, overlapping primaries, participation gaps or doubles) so that errors are fixed before payroll. AC: per legal entity and date; links to the fix screen | BR-PPL-004 | 2 | US-PPL-022, US-PPL-023 | |
| US-PPL-026 | EP-PPL-02 | As the SaaS platform, I want a daily active-employee count per legal entity so that billing and plan limits use People data (D-10). AC: counts Active at month end per legal entity, excludes Suspended (R2-12); published to SaaS (OUT-32) | BR-PPL-003 | 2 | US-PPL-017, US-PPL-079 | |

### EP-PPL-03 Family, dependants & bank accounts

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-027 | EP-PPL-03 | As HR, I want family members (relationship, name, DOB, ID number, tax code, occupation, income status, contact) so that dependants and the labour book can be built from them. AC: CRUD with validation; ID numbers encrypted and masked | BR-PPL-016 | 3 | US-PPL-002, US-PPL-086 | |
| US-PPL-028 | EP-PPL-03 | As HR, I want to register a family member as a PIT dependant with deduction start/end month and supporting document, checked against eligibility rules, so that only eligible dependants reduce PIT. AC: rules for child < 18, child 18+ studying or disabled without income, spouse/parents without income above the threshold, using legal parameters at the month; one taxpayer per dependant inside the tenant; ineligible = block with reason | BR-PPL-016 | 8 | US-PPL-027, US-PPL-022, TECH:legal-parameter-service | `[verify-legal]` thresholds are parameters; rule set from Circular 111 |
| US-PPL-029 | EP-PPL-03 | As HR, I want dependant registrations and changes approved through a Core workflow so that every change is checked and Payroll is told. AC: process type registered; approval publishes `DependantChanged` (registered/ended/transferred) with claiming employment and from/to month | BR-PPL-017 | 3 | US-PPL-028, BR-CORE-050, US-PPL-080 | |
| US-PPL-030 | EP-PPL-03 | As HR, I want to generate the dependant registration form/list (form 07/ĐK-NPT-TNCN or current) per legal entity and period so that I can submit it to the tax authority. AC: lists new/ended dependants in the period; Excel + PDF via Core templates; Vietnamese fonts correct | BR-PPL-017 | 8 | US-PPL-029, BR-CORE-102 | **[high-uncertainty]** `[verify-legal]` base 5 +1 step: current form version and layout unconfirmed |
| US-PPL-031 | EP-PPL-03 | As HR, I want one or more effective-dated bank accounts with payment split (amount or %) and exactly one default salary account so that salary is paid correctly. AC: split totals 100% / remainder to default; holder name vs employee name mismatch warning (unaccented compare); account number encrypted and masked; bank from Core list | BR-PPL-015, NFR-PPL-005 | 5 | US-PPL-002, US-PPL-086 | |
| US-PPL-032 | EP-PPL-03 | As C&B, I want bank account changes approved and published so that Payroll pays to the verified account. AC: approval process type registered; `BankAccountChanged` without the full account number (payroll reads via API) | BR-PPL-015 | 2 | US-PPL-031, BR-CORE-050, US-PPL-080 | |

### EP-PPL-04 Effective-dated assignments

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-033 | EP-PPL-04 | As HR, I want effective-dated assignments (legal entity, org unit, position, job, grade, line manager, work location, contractual working-time regime, FTE, assignment type) with full history so that every module knows where an employee sat on any date. AC: new row end-dates the prior one, never overwrites; no gaps or overlaps per employment; no pay group or schedule fields | BR-PPL-030, BR-PPL-031 | 5 | US-PPL-017, BR-CORE (org, positions, jobs, grades, locations) | Reference example "job assignment history" |
| US-PPL-034 | EP-PPL-04 | As HR, I want cost-centre % split and line manager either named or derived from the manager position so that cost allocation and approval routing are right. AC: split totals 100%; position-based manager resolves to the incumbent at the date; vacant manager position falls back to the next level | BR-PPL-031 | 3 | US-PPL-033 | |
| US-PPL-035 | EP-PPL-04 | As HR, I want a change-reasons catalogue (transfer, promotion, demotion, temporary transfer, title, manager, location, cost-centre change) where each reason sets required approvals and the document (decision/appendix) so that changes follow policy. AC: seeded catalogue, tenant can deactivate/add; reason → process type + document type mapping | BR-PPL-032 | 3 | US-PPL-001 | Secondment, concurrent position and reorg reasons stay inactive (P2) |
| US-PPL-036 | EP-PPL-04 | As HR, I want to request a job change with an effective date and reason that goes through approval and then creates the new assignment and its decision so that changes are controlled and documented. AC: Core workflow process types registered per reason group; on approval the assignment row, decision (US-PPL-057) and, when terms change, appendix (US-PPL-053) are created; `AssignmentChanged` published | BR-PPL-030, BR-PPL-032 | 5 | US-PPL-033, US-PPL-035, US-PPL-057, BR-CORE-050 | |
| US-PPL-037 | EP-PPL-04 | As Payroll, I want assignments, salary records and contracts queryable "as of date X" and "as known on date Y", with corrections recorded separately from changes, so that history can be reproduced. AC: correction keeps the superseded version with reason and known-from/known-to; as-known query returns what the system knew at Y; applied to assignment, salary, contract | NFR-PPL-001, BR-PPL-030 | 8 | US-PPL-033, US-PPL-041, US-PPL-047, TECH:walking-skeleton (effective-dating helpers) | Bitemporal on three aggregates |
| US-PPL-038 | EP-PPL-04 | As HR, I want future-dated changes held until their date and cancellable before it so that planned changes take effect on time. AC: daily job applies due changes and publishes events; future change cancellable with `AssignmentChangeCancelled`; overlapping future changes resolved in order | BR-PPL-037 | 5 | US-PPL-036, TECH:job-queue | |
| US-PPL-039 | EP-PPL-04 | As Payroll, I want a retro flag on events whose effective date falls in a closed payroll period so that I can run retro correction. AC: uses Payroll's closed-period status per legal entity; flag + earliest affected period in `AssignmentChanged`, `SalaryChanged`, contract and termination events | BR-PPL-037 | 3 | US-PPL-080, US-PPL-082 | |
| US-PPL-040 | EP-PPL-04 | As HR, I want temporary transfers (Art. 29) checked for cumulative working days per calendar year, notice and salary protection so that transfers stay lawful. AC: beyond 60 working days/year requires an employee consent attachment; notice ≥ 3 working days checked; salary-protection flag (≥85%, ≥ regional minimum, old salary for 30 working days) sent in `TemporaryTransferStarted/Ended` | BR-PPL-035 | 8 | US-PPL-036, TECH:legal-parameter-service | `[verify-legal]` values are parameters; working days from the Core/T&A holiday calendar |

### EP-PPL-05 Salary record (agreed compensation)

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-041 | EP-PPL-05 | As C&B, I want effective-dated, versioned salary records per employment (pay scale/grade/step, pay basis GROSS/NET, base salary, agreed net mandatory for NET, net-guaranteed component scope, SI contribution salary, contract allowances by payroll component code, VND, pay frequency, probation %) so that People is the system of record for agreed pay. AC: component codes and scales looked up from Payroll catalogue (IN-17); NET without agreed net rejected; every version keeps its decision/appendix link | BR-PPL-040 | 8 | US-PPL-017, BR-PAY (pay scales & component catalogue) | Effective-dated entity (5) + GROSS/NET scope rules |
| US-PPL-042 | EP-PPL-05 | As C&B, I want a salary record created or changed only through an approved decision or contract appendix so that no salary changes without a legal document. AC: no direct edit API or screen; salary-change process type registered; approval creates the version + decision/appendix and publishes `SalaryChanged` (amounts, read only by the Payroll handler) | BR-PPL-040 | 5 | US-PPL-041, US-PPL-053, US-PPL-057, BR-CORE-050, US-PPL-080 | Opening-balance import is the only other writer (US-PPL-091) |
| US-PPL-043 | EP-PPL-05 | As C&B, I want each salary validated against the grade's band min/max and the regional minimum wage of the work location at the effective date so that no one is paid below the legal minimum. AC: below minimum = block, outside band = warning with reason; probation salary checked too; NET records validated on the grossed-up amount from the Payroll simulator API | BR-PPL-041 | 8 | US-PPL-041, US-PPL-034, TECH:legal-parameter-service, BR-PAY (gross-up simulator) | **[high-uncertainty]** `[verify-legal]` base 5 +1 step: 2026 regional minima unconfirmed; gross-up simulator API not yet specified by Payroll |
| US-PPL-044 | EP-PPL-05 | As C&B, I want viewing or changing salary to require the `compensation` permission so that pay stays confidential. AC: permission registered in the Core catalogue; enforced on API, screens, list exports, read API and ESS; managers see salary only if granted | BR-PPL-044 | 3 | US-PPL-041, BR-CORE-031, BR-CORE-034 | |
| US-PPL-045 | EP-PPL-05 | As the DPO, I want every view and every change of a salary record written to the Core audit log (who, when, which record, purpose) so that compensation access is traceable. AC: list and detail views each logged; purpose captured on first view per session; audit searchable by employee | BR-PPL-044, NFR-PPL-006 | 3 | US-PPL-044, BR-CORE-090, BR-CORE-093 | |
| US-PPL-046 | EP-PPL-05 | As C&B, I want a salary history view (diễn biến lương) with linked documents so that I can explain every change. AC: chronological versions incl. corrections; links to decision/appendix PDF; permission-gated | BR-PPL-040 | 2 | US-PPL-042 | |

### EP-PPL-06 Labour contracts & appendices

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-047 | EP-PPL-06 | As HR, I want contracts of every statutory type (probation agreement, definite ≤ 36 months, indefinite, seasonal < 1 month, apprenticeship, internship, service/collaborator) with number, sign date, start/end, signatory and sequence so that labour contracts are recorded as the Labour Code defines. AC: definite end mandatory and ≤ 36 months; nth-definite counter per employment; contract number from Core numbering per legal entity | BR-PPL-050 | 5 | US-PPL-017, BR-CORE-085 | `[verify-legal]` Art. 20, 24 |
| US-PPL-048 | EP-PPL-06 | As HR, I want probation validated (maximum duration by job level, once per job, none for contracts < 1 month, probation salary ≥ 85%) so that probation is lawful. AC: limits from legal parameters by job level; second probation for the same job blocked; probation % checked against the salary record | BR-PPL-051 | 5 | US-PPL-047, US-PPL-041, TECH:legal-parameter-service | `[verify-legal]` Art. 24–27 |
| US-PPL-049 | EP-PPL-06 | As HR, I want the probation outcome recorded and notified before probation end, with alerts N days ahead, so that the outcome is lawful and pass leads to a labour contract. AC: alert to HR and manager; pass → labour contract draft (or continuation for a probation clause); fail → separation case with reason probation fail; `ProbationStarted/Completed` | BR-PPL-052 | 5 | US-PPL-047, US-PPL-059, US-PPL-061 | Probation appraisal form P2 |
| US-PPL-050 | EP-PPL-06 | As HR, I want the system to require an indefinite contract after two consecutive definite contracts, except statutory exceptions, so that we never breach Art. 20. AC: counts consecutive definite contracts incl. the imported prior count; third definite blocked unless an exception applies (elderly, foreign worker, state-enterprise manager, union official term, others configured); exception reason stored | BR-PPL-053 | 8 | US-PPL-047, US-PPL-011, US-PPL-012 | **[high-uncertainty]** `[verify-legal]` base 5 +1 step: exception list open ("etc.") and interaction with work-permit term |
| US-PPL-051 | EP-PPL-06 | As HR, I want contract expiry monitoring with alerts at configurable offsets and an auto-conversion flag so that no contract silently becomes indefinite. AC: alerts at e.g. 45/30/15 days to HR and manager; `ContractExpiring`; working > 30 days after expiry without a new contract flags auto-conversion; expiry and probation-ending list | BR-PPL-054 | 5 | US-PPL-047, US-PPL-059, TECH:job-queue | `[verify-legal]` Art. 20.2 |
| US-PPL-052 | EP-PPL-06 | As HR, I want to renew, convert or end an expiring contract from the expiry list so that the next step is one action. AC: renew creates the next contract with the two-definite check; convert creates indefinite; end opens a separation case (contract expiry); `ContractRenewed/Converted` | BR-PPL-053, BR-PPL-054 | 3 | US-PPL-050, US-PPL-051, US-PPL-061 | |
| US-PPL-053 | EP-PPL-06 | As HR, I want contract appendices for salary, position, workplace and working-time changes with an effective date so that term changes are documented. AC: appendix cannot change contract term/duration (validation); approved appendix creates the linked salary/assignment version; `ContractAppendixSigned` | BR-PPL-055 | 5 | US-PPL-047, US-PPL-055 | `[verify-legal]` Art. 22 |
| US-PPL-054 | EP-PPL-06 | As HR, I want to suspend and resume a contract for statutory reasons so that pay, SI and attendance stop correctly. AC: reasons catalogue, from/to; status Suspended; resumption-within-15-days alert; `ContractSuspended/Resumed` | BR-PPL-056 | 5 | US-PPL-047, US-PPL-017, US-PPL-080 | `[verify-legal]` Art. 30–31 |
| US-PPL-055 | EP-PPL-06 | As HR, I want contract and appendix documents generated from bilingual VN/EN tenant templates, numbered per legal entity and signed by the authorised signatory, so that paper contracts come out right. AC: People merge-field data provider for contracts/appendices; seeded templates registered with Core; signatory chosen from the legal entity delegation list; PDF/A with Vietnamese fonts | BR-PPL-057, NFR-PPL-010 | 5 | US-PPL-047, BR-CORE-102, BR-CORE-085 | Template versioning UI is Core (BR-PPL-131 P2) |
| US-PPL-056 | EP-PPL-06 | As HR, I want to record a paper signature (signed date, upload signed scan) so that the contract becomes effective without e-sign. AC: status Draft → Signed; signed scan via Core document service; `ContractSigned` published | BR-PPL-057 | 2 | US-PPL-055, BR-CORE-100 | E-sign P2 (R2-10) |

### EP-PPL-07 HR decisions & People notifications

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-057 | EP-PPL-07 | As HR, I want a decision record (quyết định) with number per legal entity per year, date, signatory, effective date, legal basis text and subject employment, linked to the change it enacts, so that every HR change has its statutory document. AC: number from Core numbering (per LE/year); one decision per change; void/re-issue keeps the number trail; signed scan upload | BR-PPL-130 | 5 | US-PPL-017, BR-CORE-085, BR-CORE-100 | |
| US-PPL-058 | EP-PPL-07 | As HR, I want seeded bilingual decision templates for the P1 decision types (hire/appointment, probation result, transfer, promotion/demotion, salary change, suspension, termination, retirement) so that decisions are generated, not typed. AC: one merge-field provider per type; PDF/A renders Vietnamese correctly; templates registered with Core | BR-PPL-130, NFR-PPL-010 | 5 | US-PPL-057, BR-CORE-102 | Reward, discipline, secondment, concurrent-position decisions follow their P2 features |
| US-PPL-059 | EP-PPL-07 | As HR, I want People alert templates and recipient rules (ID/permit expiry, probation end, contract expiry, settlement deadline, statutory report deadlines) registered with Core notifications so that alerts are consistent and bilingual. AC: VI/EN in-app + e-mail templates; no sensitive values in the message body; offsets configurable per tenant | BR-PPL-011, BR-PPL-020, BR-PPL-052, BR-PPL-054, BR-PPL-075, BR-PPL-141, BR-PPL-142 | 3 | BR-CORE-070, BR-CORE-074 | Triggers live in the owning stories |

### EP-PPL-08 Separation & final-settlement trigger

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-060 | EP-PPL-08 | As HR, I want separation reasons mapped to legal grounds, each with its SI/UI statutory code, so that the legal ground drives notice, severance and reports. AC: seeded reasons (expiry, mutual, employee resignation, Art. 36, Art. 42–43, dismissal, probation fail, retirement, death, missing, closure); tenant may add sub-reasons, not legal grounds | BR-PPL-070 | 3 | US-PPL-001 | `[verify-legal]` codes |
| US-PPL-061 | EP-PPL-08 | As HR, I want a separation case (reason, legal ground, notice date, LWD, termination date, access-revocation date) with approval and a termination decision so that terminations are controlled. AC: process type registered; employment status Notice on approval; access_revocation_date defaults to LWD 23:59; termination decision generated | BR-PPL-070, BR-PPL-075 | 5 | US-PPL-060, US-PPL-058, BR-CORE-050 | |
| US-PPL-062 | EP-PPL-08 | As HR, I want the required notice computed by contract type and reason, incl. special occupations and no-notice cases, showing any shortfall and its compensation consequence so that we and the employee respect the law. AC: employee and employer sides (Art. 35, 36); 45 / 30 / 3-working-day defaults from legal parameters; shortfall days carried to `TerminationScheduled` | BR-PPL-071 | 8 | US-PPL-061, TECH:legal-parameter-service | `[verify-legal]` Decree 145 special occupations |
| US-PPL-063 | EP-PPL-08 | As HR, I want employer termination or dismissal blocked for protected employees (pregnant, on maternity leave, child < 12 months, sick leave under treatment) unless a lawful exception is documented so that we avoid unlawful termination. AC: uses special status (US-PPL-012) and T&A leave periods (IN-14); exception needs ground + attachment and HR Manager approval | BR-PPL-073 | 5 | US-PPL-012, US-PPL-061, US-PPL-082 | `[verify-legal]` Art. 37, 122, 137 |
| US-PPL-064 | EP-PPL-08 | As Payroll, I want severance or job-loss eligibility, the employment periods (incl. recognised prior service) and the already-compensated periods supplied with the legal ground so that I can compute the amount. AC: eligibility derived from legal ground; periods incl. imported history (US-PPL-092); Payroll's result (IN-19) shown read-only on the case, never recomputed | BR-PPL-074 | 8 | US-PPL-061, US-PPL-082, US-PPL-092 | `[verify-legal]` Art. 46–47 |
| US-PPL-065 | EP-PPL-08 | As an employee, I want to submit my resignation in ESS (proposed LWD, reason, comment) and withdraw it so that resigning is simple and recorded. AC: ESS page in SaaS shell; manager then HR approve via Core inbox; withdraw before approval freely, after approval only with HR consent → `TerminationCancelled` | BR-PPL-072 | 5 | US-PPL-061, US-PPL-062, BR-SAAS-075, BR-CORE-060 | |
| US-PPL-066 | EP-PPL-08 | As Payroll, I want `TerminationScheduled` on approval and `EmployeeTerminated` with the final-settlement trigger on LWD + 1 so that final settlement starts on time. AC: daily job sets Terminated; trigger carries LWD, legal ground, SI/UI code, eligibility, periods, notice shortfall; settlement deadline from Payroll's clock (`pay.FinalSettlementStatusChanged`) shown with alerts | BR-PPL-075 | 5 | US-PPL-061, US-PPL-064, US-PPL-080, US-PPL-082 | `[verify-legal]` Art. 48 |
| US-PPL-067 | EP-PPL-08 | As HR, I want to capture the leaver's personal e-mail on the separation case so that the former employee can log in by OTP (OD-8). AC: required unless HR records "no e-mail"; handed to Core for the FORMER_EMPLOYEE role | BR-PPL-075 | 1 | US-PPL-061, US-PPL-006 | |

### EP-PPL-09 Labour book & statutory labour reports

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-068 | EP-PPL-09 | As HR, I want the electronic labour management book per legal entity with the prescribed fields, viewable as of any date and exportable, so that it is ready for inspection. AC: fields per Decree 145 Art. 3 (identity, qualification, contract, salary, SI, training, discipline placeholders, termination); Excel + PDF export; reflects every change by effective date | BR-PPL-140 | 8 | US-PPL-010, US-PPL-033, US-PPL-041, US-PPL-047, US-PPL-061 | `[verify-legal]` statutory export (8) |
| US-PPL-069 | EP-PPL-09 | As HR, I want the semi-annual labour-usage change report (Form 01/PLI) per legal entity for June and December periods so that I submit it before the deadline. AC: counts by category from People history; HR review and adjust notes; export in the prescribed layout; deadline alerts (e.g. before 5 June / 5 December) | BR-PPL-141 | 8 | US-PPL-068, US-PPL-071 | `[verify-legal]` statutory export |
| US-PPL-070 | EP-PPL-09 | As HR, I want the monthly labour-fluctuation notice to the Employment Service Centre listing hires and terminations so that UI obligations are met. AC: per legal entity per month; export in the prescribed form; deadline alert | BR-PPL-142 | 8 | US-PPL-066, US-PPL-071 | **[high-uncertainty]** `[verify-legal]` base 5 +1 step: Employment Law 2025 guiding decree and form not yet issued/confirmed |
| US-PPL-071 | EP-PPL-09 | As HR, I want a statutory report register (type, legal entity, period, generated file, submitted at, by whom) so that submissions are logged. AC: re-generation keeps versions; mark submitted with date and receipt upload | BR-PPL-141, BR-PPL-142 | 3 | US-PPL-001, BR-CORE-100 | |

### EP-PPL-10 ESS web pages for People

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-072 | EP-PPL-10 | As an employee, I want a "My profile" page (personal data, IDs masked, addresses, contacts, qualification, tax residency) in the ESS shell so that I can check my data. AC: SELF scope only; read-only (change requests are P2); usable at 360 px | BR-PPL-120, BR-PPL-123, NFR-PPL-011 | 5 | US-PPL-002, BR-SAAS-075, US-PPL-085 | |
| US-PPL-073 | EP-PPL-10 | As an employee, I want "My employment" (assignments, contracts, decisions to download, salary history if tenant policy allows) so that I have my documents. AC: documents via Core signed URLs; salary tab hidden unless policy flag on | BR-PPL-120 | 5 | US-PPL-033, US-PPL-047, US-PPL-057, BR-CORE-101 | |
| US-PPL-074 | EP-PPL-10 | As an employee, I want to view my family and dependants and request a new or ended dependant with documents so that my PIT deduction is up to date. AC: request feeds the HR approval of US-PPL-029; status visible; eligibility errors shown before submit | BR-PPL-120, BR-PPL-017 | 5 | US-PPL-029, BR-SAAS-075 | |
| US-PPL-075 | EP-PPL-10 | As an employee, I want to request a bank-account change with step-up authentication so that only I can redirect my salary. AC: step-up via SaaS shell before submit; HR approval (US-PPL-032); e-mail notice to the employee on change | BR-PPL-120, NFR-PPL-011 | 3 | US-PPL-032, BR-SAAS-075 | |
| US-PPL-076 | EP-PPL-10 | As a line manager or HR approver, I want People task cards (hire, job change, salary change, dependant, bank, resignation, separation) in the Core inbox so that I can approve with the facts in front of me. AC: summary + link per process type; salary values only with `compensation` permission | BR-PPL-120, BR-PPL-072, BR-PPL-017 | 3 | BR-CORE-060, US-PPL-036, US-PPL-065 | MSS pages P2 (BR-PPL-121) |
| US-PPL-077 | EP-PPL-10 | As a former employee, I want read-only ESS access to my contracts and decisions so that I can download them after leaving (OD-8). AC: FORMER_EMPLOYEE role sees only own documents for the configured period | BR-PPL-120 | 2 | US-PPL-073, US-PPL-067 | |
| US-PPL-078 | EP-PPL-10 | As an employee, I want all People ESS pages in Vietnamese and English and usable on a 360 px phone browser so that I can use them anywhere. AC: every label in VI/EN resource files; responsive check on 360/768/1280 px; names displayed and searched with diacritics | BR-PPL-123, NFR-PPL-011, NFR-PPL-009 | 3 | US-PPL-072, US-PPL-073, US-PPL-074, US-PPL-075, US-PPL-065 | |

### EP-PPL-11 Employee events & read API

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-079 | EP-PPL-11 | As Payroll/T&A, I want every People change committed with its event in the same transaction through the outbox so that no HR change is lost. AC: `hr.outbox_event` wired to the TECH relay; versioned JSON Schemas for People events checked in CI; idempotent `event_id`; test proves rollback drops both | NFR-PPL-002 | 5 | TECH:outbox-event-bus | Engine is TECH; People wiring and schemas only |
| US-PPL-080 | EP-PPL-11 | As Payroll/T&A/Core, I want People lifecycle events (EmployeeHired, HireCancelled, AssignmentChanged, AssignmentChangeCancelled, SalaryChanged, Contract*, Probation*, ContractSuspended/Resumed, TemporaryTransfer*, TerminationScheduled, EmployeeTerminated, TerminationCancelled, PersonalDataChanged, BankAccountChanged, DependantChanged, SpecialStatusChanged, PrimaryEmploymentChanged) with the canonical envelope so that consumers react to HR changes. AC: payload builders with minimised data (no CCCD, full bank number; amounts only in SalaryChanged); consumer contract tests with Payroll and T&A; retro flag field present | NFR-PPL-002, BR-PPL-004, BR-PPL-075 | 8 | US-PPL-079 | ~20 event types |
| US-PPL-081 | EP-PPL-11 | As Payroll/T&A, I want an employee master read API (current and as-of date / as-known) for person, employment, assignment, contract summary and salary summary (permission-gated) so that consumers pull sensitive values under their own rights. AC: as-of and as-known parameters; salary needs `compensation`; p95 ≤ 300 ms single employee; batch by legal entity for payroll runs | NFR-PPL-001 | 5 | US-PPL-037, US-PPL-044 | OUT-03 |
| US-PPL-082 | EP-PPL-11 | As HR, I want People to consume other modules' events (core.UserStatusChanged, pay.PayGroupAssigned, T&A schedule assignment, T&A leave/maternity periods, pay.FinalSettlementStatusChanged, payroll closed periods, 183-day proposals) idempotently so that protection checks, retro flags and onboarding readiness use current facts. AC: inbox keyed by consumer + event_id; `people.OnboardingReadinessChanged` published; dead-letter visible to ops | BR-PPL-073, BR-PPL-075 | 5 | US-PPL-079, TECH:outbox-event-bus | |

### EP-PPL-12 PDPL for People data

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-083 | EP-PPL-12 | As the DPO, I want every People field classified as basic or sensitive and registered in the SaaS personal-data inventory so that the data dictionary is complete. AC: classification for every `hr` personal column (health, ethnicity, religion, union, bank = sensitive); CI check fails on an unclassified new column | BR-PPL-150 | 3 | BR-SAAS-079 | `[verify-legal]` Decree 13/2023, PDPL 2025 |
| US-PPL-084 | EP-PPL-12 | As the DPO, I want People to check consent references (`hasConsent()`) before processing optional sensitive data and record the lawful basis otherwise so that processing is lawful. AC: religion, party/youth union and optional health fields hidden/blocked without consent; lawful basis (contract/legal obligation) recorded for mandatory fields; `saas.ConsentChanged` on withdrawal hides the optional data | BR-PPL-151 | 5 | BR-SAAS-080, US-PPL-083 | Recruitment consent re-purpose is P2 with Recruitment |
| US-PPL-085 | EP-PPL-12 | As HR, I want sensitive fields masked by default on screens and exports, with a reveal action that needs permission and a purpose and is logged, so that sensitive data is shown only when needed. AC: Core field-masking rules configured for every sensitive People field; reveal logs who/when/field/purpose; exports masked unless the exporter holds reveal permission | BR-PPL-152, NFR-PPL-006 | 5 | BR-CORE-034, BR-CORE-090, US-PPL-083 | |
| US-PPL-086 | EP-PPL-12 | As the DPO, I want ID numbers, bank accounts, health, religion and ethnicity encrypted at field level with a blind index for matching, and masked in logs and non-production copies, so that a database leak does not expose them. AC: encryption via TECH key service; blind index supports CCCD/passport duplicate check and exact search; log masking test; non-prod copy masking script; salary amounts not encrypted (X-01) | NFR-PPL-005 | 8 | TECH:security (field encryption/KMS) | **[high-uncertainty]** key-unwrap cost for 10k persons per payroll run not yet measured (decision log §4) |
| US-PPL-087 | EP-PPL-12 | As the DPO, I want every read of sensitive People data and every write logged with actor, purpose and before/after (writes) so that access is accountable. AC: writes audited for all People aggregates via Core audit; sensitive reads logged (incl. read API); audit write guaranteed in the business transaction | NFR-PPL-006 | 3 | BR-CORE-090, BR-CORE-092 | |
| US-PPL-088 | EP-PPL-12 | As the DPO, I want People export and anonymise handlers for data-subject requests so that a DSR opened in SaaS can be answered for People data. AC: export package (JSON + readable PDF) of all People data for a person; anonymise handler respects legal retention floors (keeps labour/SI/tax records, removes optional data); completion confirmation to SaaS (OUT-33) | BR-PPL-153 | 8 | BR-SAAS-081, US-PPL-083 | `[verify-legal]` retention floors and deadlines (D-04, Q9) |

### EP-PPL-13 Employee-master opening-balance import

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-PPL-089 | EP-PPL-13 | As an implementation consultant, I want the employee-master opening-balance Excel template (sheets: persons, contacts/addresses, employments with original hire and seniority dates, current assignment, current contract with prior-definite count, current salary, salary history, family/dependants with registration months, bank accounts) registered with the Core import engine so that the customer can prepare the file (R2-13). AC: template generated by Core from the People import-type definition, VI/EN headers and column dictionary; lookups listed on a reference sheet; validate-only mode available | NFR-PPL-007 | 5 | BR-CORE-110, BR-CORE-112, BR-CORE-114, BR-SAAS-069 | Reference example "Excel import template" |
| US-PPL-090 | EP-PPL-13 | As an implementation consultant, I want row and cross-sheet validation (lookups to org/positions/admin units/banks, legacy 3-level addresses, duplicate persons within file and tenant, one live employment per legal entity, contract and salary rules, primary employment for concurrent employees) so that bad data is rejected before load. AC: per-row error file; unmapped custom attributes reported for mapping to standard fields or attachments (R3-09); same business rules as the screens, warnings vs errors | NFR-PPL-007, BR-PPL-001 | 8 | US-PPL-089, US-PPL-003, BR-CORE-111 | Reuses US-PPL-028/043/048/050 validators |
| US-PPL-091 | EP-PPL-13 | As an implementation consultant, I want an idempotent load that creates persons, employments, effective-dated assignments, contracts, salary records, dependants and bank accounts with import provenance so that re-runs never duplicate. AC: upsert by natural keys (CCCD, employee no.); salary records written with source = opening balance instead of a decision; opening events published once with an `opening_balance` flag; reversible per batch | NFR-PPL-007 | 8 | US-PPL-090, US-PPL-041, US-PPL-080, BR-SAAS-070 | |
| US-PPL-092 | EP-PPL-13 | As Payroll, I want prior employment periods, recognised service, already-compensated severance periods and the salary history needed for severance and average bases imported so that leavers after go-live are settled correctly. AC: history rows stored as closed effective-dated records; no events for history; visible on the employee timeline of the separation case | NFR-PPL-007, BR-PPL-074 | 5 | US-PPL-091 | |
| US-PPL-093 | EP-PPL-13 | As the customer's C&B lead, I want a reconciliation and data-quality report (counts per legal entity, active headcount, salary totals, dependants, missing tax code/SI code/bank) against customer control totals so that I can sign off the load. AC: feeds BR-SAAS-072 reconciliation; differences listed per employee; exportable for sign-off | NFR-PPL-007 | 5 | US-PPL-091, BR-SAAS-072 | Report R20 |

---

## 3. Coverage check (57 P1 requirements)

| P1 ID | Covered by |
|---|---|
| BR-PPL-001 | US-PPL-003, US-PPL-004, US-PPL-018, US-PPL-090 |
| BR-PPL-002 | US-PPL-018, US-PPL-021 |
| BR-PPL-003 | US-PPL-017, US-PPL-018, US-PPL-019, US-PPL-020, US-PPL-026 |
| BR-PPL-004 | US-PPL-022, US-PPL-023, US-PPL-024, US-PPL-025, US-PPL-080 |
| BR-PPL-010 | US-PPL-002, US-PPL-005, US-PPL-006, US-PPL-016 |
| BR-PPL-011 | US-PPL-007, US-PPL-059 |
| BR-PPL-013 | US-PPL-008 |
| BR-PPL-014 | US-PPL-009 |
| BR-PPL-015 | US-PPL-031, US-PPL-032 |
| BR-PPL-016 | US-PPL-024, US-PPL-027, US-PPL-028 |
| BR-PPL-017 | US-PPL-029, US-PPL-030, US-PPL-074, US-PPL-076 |
| BR-PPL-019 | US-PPL-010 (minimal, R3-07) |
| BR-PPL-020 | US-PPL-011, US-PPL-059 |
| BR-PPL-021 | US-PPL-012 |
| BR-PPL-023 | US-PPL-013, US-PPL-014 |
| BR-PPL-030 | US-PPL-033, US-PPL-036, US-PPL-037 |
| BR-PPL-031 | US-PPL-033, US-PPL-034 |
| BR-PPL-032 | US-PPL-035, US-PPL-036 |
| BR-PPL-035 | US-PPL-040 |
| BR-PPL-037 | US-PPL-038, US-PPL-039 |
| BR-PPL-040 | US-PPL-041, US-PPL-042, US-PPL-046 |
| BR-PPL-041 | US-PPL-043 |
| BR-PPL-044 | US-PPL-044, US-PPL-045 |
| BR-PPL-050 | US-PPL-047 |
| BR-PPL-051 | US-PPL-048 |
| BR-PPL-052 | US-PPL-049, US-PPL-059 |
| BR-PPL-053 | US-PPL-050, US-PPL-052 |
| BR-PPL-054 | US-PPL-051, US-PPL-052, US-PPL-059 |
| BR-PPL-055 | US-PPL-053 |
| BR-PPL-056 | US-PPL-054 |
| BR-PPL-057 | US-PPL-055, US-PPL-056 |
| BR-PPL-070 | US-PPL-060, US-PPL-061 |
| BR-PPL-071 | US-PPL-062 |
| BR-PPL-072 | US-PPL-065, US-PPL-076 |
| BR-PPL-073 | US-PPL-063, US-PPL-082 |
| BR-PPL-074 | US-PPL-064, US-PPL-092 |
| BR-PPL-075 | US-PPL-059, US-PPL-061, US-PPL-066, US-PPL-067, US-PPL-080, US-PPL-082 |
| BR-PPL-120 | US-PPL-072, US-PPL-073, US-PPL-074, US-PPL-075, US-PPL-076, US-PPL-077 |
| BR-PPL-123 | US-PPL-072, US-PPL-078 |
| BR-PPL-130 | US-PPL-057, US-PPL-058 |
| BR-PPL-140 | US-PPL-068 |
| BR-PPL-141 | US-PPL-059, US-PPL-069, US-PPL-071 |
| BR-PPL-142 | US-PPL-059, US-PPL-070, US-PPL-071 |
| BR-PPL-150 | US-PPL-083 |
| BR-PPL-151 | US-PPL-084 |
| BR-PPL-152 | US-PPL-085 |
| BR-PPL-153 | US-PPL-088 |
| NFR-PPL-001 | US-PPL-037, US-PPL-081 |
| NFR-PPL-002 | US-PPL-079, US-PPL-080 |
| NFR-PPL-003 | US-PPL-015, US-PPL-016 |
| NFR-PPL-005 | US-PPL-031, US-PPL-086 |
| NFR-PPL-006 | US-PPL-045, US-PPL-085, US-PPL-087 |
| NFR-PPL-007 | US-PPL-089, US-PPL-090, US-PPL-091, US-PPL-092, US-PPL-093 |
| NFR-PPL-008 | US-PPL-001, US-PPL-002, US-PPL-003 |
| NFR-PPL-009 | US-PPL-015, US-PPL-078 |
| NFR-PPL-010 | US-PPL-055, US-PPL-058 |
| NFR-PPL-011 | US-PPL-072, US-PPL-075, US-PPL-078 |

**Result:** 57 of 57 P1 requirements covered (47 BR + 10 NFR); none uncovered. No story delivers a P2/P3 requirement; P2 items touched at the edges (rehire seniority BR-PPL-005, ESS data-change requests BR-PPL-022, MSS BR-PPL-121, e-contract BR-PPL-058, template UI BR-PPL-131, mass change NFR-PPL-004) are explicitly excluded in the story notes.

Check (Git Bash, from this folder):
```bash
awk -F'|' '/^### 9.2/{s=1} /^### 9.3/{s=0} s && /^\| (BR|NFR)-/ && $5 ~ /P1/ {id=$2; gsub(/ /,"",id); print id}' ../brd/mvp-scope.md | sort > /tmp/p1.txt
awk -F'|' '/^\| US-PPL-/ {print $5}' people-backlog.md | grep -oE '(BR|NFR)-PPL-[0-9]+' | sort -u > /tmp/cov.txt
comm -23 /tmp/p1.txt /tmp/cov.txt   # expect empty (uncovered P1)
comm -13 /tmp/p1.txt /tmp/cov.txt   # expect empty (non-P1 refs)
```

---

## 4. Totals and risk notes

### 4.1 Story points

| Epic | Stories | SP |
|---|---|---|
| EP-PPL-01 Person & personal data | 16 | 65 |
| EP-PPL-02 Employment & concurrent employment | 10 | 53 |
| EP-PPL-03 Family, dependants & bank accounts | 6 | 29 |
| EP-PPL-04 Effective-dated assignments | 8 | 40 |
| EP-PPL-05 Salary record | 6 | 29 |
| EP-PPL-06 Labour contracts & appendices | 10 | 48 |
| EP-PPL-07 HR decisions & People notifications | 3 | 13 |
| EP-PPL-08 Separation & final-settlement trigger | 8 | 40 |
| EP-PPL-09 Labour book & statutory labour reports | 4 | 27 |
| EP-PPL-10 ESS web pages for People | 7 | 26 |
| EP-PPL-11 Employee events & read API | 4 | 23 |
| EP-PPL-12 PDPL for People data | 6 | 32 |
| EP-PPL-13 Employee-master opening-balance import | 5 | 31 |
| **Total** | **93** | **456** |

Point mix: 1 SP × 2, 2 SP × 8, 3 SP × 21, 5 SP × 42, 8 SP × 19, 13 SP × 1.

**Capacity view (guide §6, for the PM).** 456 SP ≈ 456 developer-days before overhead (≈ 640 with +40%). At 7 SP per developer per sprint over 6 build sprints (42 SP per developer), People alone needs **≈ 11 developers** for the 12-week build window, and that is before Core engines it depends on are available. This confirms decision-log risk 1 (X-04): either People gets that capacity, or scope beyond the statutory chain must be re-examined (candidates are listed in §4.3).

### 4.2 Risk notes: `[high-uncertainty]` stories

| Story | SP | Why |
|---|---|---|
| US-PPL-023 Insurance participation per type per employment | 13 | Sponsor rule (all FIRST_CONTRACT, R3-03) conflicts with the PM's reading of the HI Law (HIGHEST_SALARY); the HIGHEST_SALARY variant needs cross-entity salary comparison, tie rules and recomputation on every salary change. Legal consultant must confirm before sprint start. |
| US-PPL-043 Salary vs band and regional minimum (incl. NET gross-up) | 8 | 2026 regional minima `[verify-legal]`; NET validation depends on a Payroll gross-up simulator API not yet specified. |
| US-PPL-050 Two-definite / 36-month rule with exceptions | 8 | The statutory exception list is open-ended ("etc.") and interacts with foreign-worker permit terms and elderly workers. |
| US-PPL-070 Monthly labour-fluctuation notice | 8 | Employment Law 2025 guiding decree and the notice form are not confirmed; layout may change. |
| US-PPL-030 Dependant registration form 07/ĐK-NPT-TNCN | 8 | Current form version and whether a list or individual form is filed are unconfirmed. |
| US-PPL-086 Field-level encryption with blind index | 8 | Key-unwrap cost for 10k persons per payroll run not yet measured (decision log §4 spike); may change the read-API design. |

Other `[verify-legal]` stories (rule clear enough in the BRD, sized without the +1 step, re-estimate if the consultant changes the rule): US-PPL-005, 008, 011, 028, 040, 047, 048, 051, 053, 054, 060, 062, 063, 064, 066, 068, 069, 083, 088.

### 4.3 Dependency and scope notes
- **Core engine timing.** 41 stories depend on a Core or SaaS engine (workflow, numbering, document templates, import, masking, ESS shell). Sprint 1–2 People work should be the engines-free foundation: US-PPL-001/002/003/017/033/041/047 and the outbox wiring US-PPL-079.
- **Payroll contracts.** US-PPL-014, 039, 041, 043, 064, 066 need Payroll APIs/events (183-day proposals, closed periods, component catalogue, gross-up simulator, severance result, settlement clock); agree the schemas in sprint 1 (RK4).
- **Descope reserve if capacity is short** (non-statutory or with workaround; PM/sponsor decision): US-PPL-004 person merge (manual DB-assisted fix), US-PPL-014 (HR keys residency changes directly), US-PPL-025 (exception report), US-PPL-034 position-based manager (named manager only), US-PPL-077 former-employee ESS (HR e-mails documents). Together ≈ 15 SP.
