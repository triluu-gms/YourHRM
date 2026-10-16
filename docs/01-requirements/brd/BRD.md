# YourHRM — Business Requirements Document (Master)

> **How to read this document.** This is the consolidated, product-level BRD. It states *why* YourHRM exists, *what* it must achieve, *what* is in and out of scope by phase, and *how* the modules work together end to end. Detailed requirements live in the six module BRDs and are only referenced here — never restated.

## 1. Document control

| Item | Value |
|---|---|
| Document | `docs/01-requirements/brd/BRD.md` — Master Business Requirements Document |
| Version | 0.2 (draft) |
| Date | 2026-10-16 |
| Status | Draft for review — Phase 0 |
| Author | Lead Business Analyst |
| Reviewers | Executive sponsor — TBD · Pilot customer HR/C&B lead — TBD · PM — TBD · Solution architect — TBD · VN labour/tax consultant — TBD |
| Approval | Pending |
| Source documents | [Project charter](../../00-project-management/project-charter.md) · [Decision log](../../00-project-management/decision-log.md) · [MVP scope re-rating](mvp-scope.md) · [01 Core](modules/01-core.md) · [02 People](modules/02-people.md) · [03 Time & Attendance](modules/03-time-attendance.md) · [04 Payroll](modules/04-payroll.md) · [05 Recruitment](modules/05-recruitment.md) · [06 SaaS Platform](modules/06-saas-platform.md) · [Gap analysis](gap-analysis.md) |

**Conventions** (charter §4): requirement IDs `BR-<MOD>-NNN` / `NFR-<MOD>-NNN` with `MOD ∈ CORE, PPL, ATT, PAY, REC, SAAS`; priority is MoSCoW, where `M` = needed for MVP / first paying tenant. Every legal value is an **effective-dated parameter**, never hard-coded, and is marked `[verify-legal]` until confirmed by a Vietnam labour/tax consultant.

| Version | Date | Change |
|---|---|---|
| 0.1 | 2026-10-16 | First consolidation of module BRDs v0.1 |
| 0.2 | 2026-10-16 | Applies the sponsor decisions X-04, OD-9, D-09 and D-10 ([decision log](../../00-project-management/decision-log.md)). (1) MVP cut to "core features of each module". Every BR/NFR is re-rated P1/P2/P3 in [mvp-scope.md](mvp-scope.md); that phase overrides MoSCoW for scope. (2) No mobile features in Phase 1: no native app, PWA, GPS/selfie or web check-in, and no push. Pilot attendance comes from time devices and Excel import. ESS stays as a responsive web app. (3) No Oracle migration or parallel run. A generic opening-balance import (Excel templates) is used instead, and the go-live criteria now rely on opening-balance reconciliation. (4) Billable employee = active person at month end per legal entity. Updated §2–§6, §9–§11. |

---

## 2. Executive summary

**Problem.** Vietnamese employers run HR and payroll on legacy on-premise systems (such as the Oracle HR database of our pilot customer) or on spreadsheets. Vietnamese payroll is difficult to get right. It combines compulsory insurance (BHXH/BHYT/BHTN) with caps and exemptions, progressive PIT with family deductions, overtime and night-work premiums, regional minimum wages, and frequent legal change. In 2025–2026 alone this includes the SI Law 2024, PIT Law 2025 with new deductions and brackets, the Employment Law 2025, the PDP Law 2025, new regional minimum wages and the merger of provinces. Each change means spreadsheet rework, compliance risk and slow month-end close. Groups with several legal entities duplicate effort per entity.

**Product.** YourHRM is a multi-tenant HRM SaaS built on PostgreSQL and Java Spring Boot. It covers the employer structure and access (Core), the employee lifecycle (People), Time & Attendance, Vietnamese Payroll with SI and PIT, and Recruitment. A commercial SaaS platform layer adds tenant lifecycle, billing, identity, reporting, integration, opening-balance import and a responsive web ESS. A native mobile app follows in Phase 2.

**Target customers.** Vietnamese organisations with roughly 20 to 10,000 employees per tenant. This covers SMEs, mid-market companies and multi-entity groups, including labour-intensive sectors (manufacturing, garment, retail) that need shifts, device check-in and the 300-hour overtime regime. All tenant data is hosted in Vietnam.

**Differentiators.**

| # | Differentiator | What it means for the customer |
|---|---|---|
| D1 | **Vietnam compliance by default** | Statutory SI/HI/UI/OA-OD, trade union fee, PIT (resident, non-resident, casual 10%), OT/night rules, leave entitlements, labour-contract rules and statutory outputs (D02-LT, 05/KK-TNCN, 05/QTT-TNCN, e-withholding certificates, labour-usage report) are delivered pre-configured. |
| D2 | **Effective-dated legal parameters** | The vendor centrally maintains and versions all rates, caps, brackets, deductions, minimum wages and holidays. Each period is calculated with the parameter set in force at that time, and retro pay is recomputed correctly. Each result records the parameter version used (NFR-SAAS-016/017, BR-PAY-010). |
| D3 | **Multi-entity by design** | One tenant can hold 1..n legal entities (pháp nhân), each with its own MST, SI unit code and minimum-wage region. The tenant can have concurrent employment across entities and group-level reporting. |
| D4 | **True SaaS** | Shared and dedicated tiers with DB-enforced isolation, VN data residency, zero-downtime releases, per-tenant feature toggles, VN e-invoicing of subscriptions, and standard Excel opening-balance import, so that any customer can go live mid-year without a migration project. |

---

## 3. Business objectives & success metrics

Targets are proposed for sponsor approval. Baselines will be measured at the pilot customer during discovery.

| # | Objective | KPI | Target | Source / evidence |
|---|---|---|---|---|
| O1 | Faster, predictable payroll close | Elapsed time from T&A period lock to approved payroll run | ≤ 3 working days (pilot baseline TBD) | Payroll §7.3 timing; run status timestamps |
| O1 | | Hands-on admin time for a 500-employee monthly run without exceptions | < 30 min | NFR-PAY-012 |
| O1 | | Calculation time for a 10,000-employee run | ≤ 15 min | NFR-SAAS-002, NFR-PAY-001 |
| O2 | Statutory accuracy | Variance against consultant-verified golden scenarios (≥ 200 cases) | 0 VND on every release and parameter change | NFR-PAY-008 |
| O2 | | Opening-balance reconciliation against the customer's control totals (YTD income/PIT, SI history, leave, loans) | 0 unexplained differences before the first payroll run | BR-SAAS-072 |
| O2 | | SI and PIT declaration files accepted by VSS/eTax at first submission | ≥ 98% | NFR-PAY-010 |
| O3 | Legal agility | Legal parameter pack published before the effective date (when the law gives ≥ 30 days' notice) | ≥ 7 calendar days before; otherwise ≤ 10 working days after issuance | NFR-SAAS-016 |
| O4 | Fast tenant onboarding | Operator provisioning of a working tenant | ≤ 10 min | BR-SAAS-003 |
| O4 | | Pilot contract-to-go-live (opening-balance import, no parallel run) | ≤ 16 weeks | BR-SAAS-071, 072, 088 |
| O4 | | Standard SME (≤ 300 employees, template import) contract-to-first-payroll | ≤ 4 weeks (MVP assisted), ≤ 2 weeks (Phase 2 wizard) | BR-SAAS-069, 074 |
| O5 | Employee & manager self-service | Leave, OT and correction requests submitted via ESS/MSS rather than on paper | ≥ 90% by month 3 after go-live | ESS usage report |
| O5 | | Median approval cycle time for leave requests | ≤ 1 business day | Core R-07 workflow SLA report |
| O6 | Trust & security | Cross-tenant data incidents | 0; isolation tests block release | BR-SAAS-021/023, NFR-SAAS-001 |
| O6 | | Monthly availability | ≥ 99.5% (Enterprise ≥ 99.9%) | NFR-SAAS-001 |
| O7 | Commercial traction | Paying tenants live 12 months after MVP GA | Pilot + TBD (sponsor to set) | Platform usage metering (BR-SAAS-013) |

---

## 4. Stakeholders & personas

Module BRDs §2 define the detailed actors. This table consolidates them into product-level personas.

| Persona | Vietnamese term | Primary goals | Main modules |
|---|---|---|---|
| Tenant HR administrator | Quản trị nhân sự | Maintain org, positions and employee records; run contracts and decisions; produce statutory labour reports | Core, People |
| C&B / payroll officer | Chuyên viên C&B / tiền lương | Configure components; run accurate payroll on time; file SI/PIT; reconcile | Payroll, T&A |
| Payroll / C&B manager, chief accountant | Kế toán trưởng | Approve runs and payments; receive GL journals; reconcile SI/PIT payables | Payroll |
| Line manager | Quản lý trực tiếp | Approve leave, OT and changes; review team timesheets; appraise; hire | Core (workflow), T&A, People, Recruitment |
| Employee | Nhân viên (ESS) | Request leave/OT and attendance corrections (check-in from the mobile app is P2); view payslips and PIT certificates; maintain profile and dependants; give consent | T&A, Payroll, People, SaaS (ESS) |
| Recruiter / hiring manager | Chuyên viên tuyển dụng | Post jobs; manage the pipeline; make offers within band; hand over hires with no re-keying | Recruitment |
| Candidate | Ứng viên | Apply; schedule interviews; accept the offer; pre-board; exercise data rights | Recruitment |
| Tenant IT admin / tenant owner | Quản trị hệ thống khách hàng / Chủ tài khoản | Configure SSO/MFA, roles, integrations and devices; manage the subscription; approve support access | Core, SaaS |
| Platform operator (support, onboarding, billing, SRE) | Nhà cung cấp | Provision tenants; guide opening-balance imports; bill; support under consent; run the platform | SaaS |
| Regulatory content owner | Chuyên gia pháp lý / lương | Publish tested legal parameter packs and statutory templates | SaaS, Payroll, T&A |
| Accountant / finance | Kế toán | Pay advances and expenses; import payroll journals into MISA/Fast/SAP; receive subscription e-invoices | Payroll, People, SaaS |
| Auditor (internal/external), labour inspector | Kiểm toán / thanh tra lao động | Read-only evidence: audit trail, payroll registers, OT caps, leave granted | Core (audit), all modules |
| DPO / privacy officer | Người phụ trách bảo vệ DLCN | Consent register, DSRs, DPIA and breach handling | SaaS, People, Recruitment |
| Executive sponsor | Ban điều hành | Business case, KPIs, go/no-go | — |

---

## 5. Scope

### 5.1 Capability map

**Phase rule (changed in v0.2).** MVP = Phase 1 (P1). Each requirement's phase is set in **[mvp-scope.md](mvp-scope.md)**. That re-rating applies the sponsor decisions X-04, OD-9, D-09 and D-10 and **overrides** both the old MoSCoW → phase mapping (M → MVP, S → P2, C → P3) and the module phase tags. In short, Phase 1 is:
- the core features of each module, delivered as a **responsive web app** (admin + ESS)
- everything statutory for VN payroll and labour compliance
- the opening-balance import.

**No mobile features are in Phase 1.** That means no native app, PWA, push, or GPS/selfie/Wi-Fi/web check-in. Recruitment is a P2 module as a whole (charter §6), but its data contracts with Core, People and Payroll are frozen in Phase 0. The Phase column below summarises the result.

| # | Capability | Module | Phase | Detail |
|---|---|---|---|---|
| 1 | Legal entities (MST, SI unit code, region), branches/locations with geo-fence | Core | MVP (geo-fence data only used by P2 check-in) | [01 §4.1](modules/01-core.md#41-company-legal-entities-locations) |
| 2 | Effective-dated org tree, reorganisation packages, positions, grades, cost centres | Core | MVP (reorganisation packages, positions as budgeted seats, interactive org chart: P2; matrix lines: P3) | [01 §4.2–4.3](modules/01-core.md#42-organisation-structure) |
| 3 | Users, RBAC, data scopes, field masking, local login + MFA, OIDC SSO (Google/Entra) | Core / SaaS | MVP: local login + MFA (OIDC and SAML SSO: P2; SCIM: P3) | [01 §4.4](modules/01-core.md#44-users-roles-permissions-data-scope-sso), [06 §4.D](modules/06-saas-platform.md#4d-identity--authentication) |
| 4 | Workflow & approval engine (conditions, delegation, SLA, escalation) | Core | MVP (approval delegation, SLA escalation, mobile/email/Zalo approval, parallel branches: P2) | [01 §4.5](modules/01-core.md#45-workflow--approval-engine) |
| 5 | Notifications: in-app and email | Core | MVP (push, Zalo ZNS: P2; SMS: P3) | [01 §4.6](modules/01-core.md#46-notifications) |
| 6 | VN master data (34 provinces/wards with old→new mapping), code lists, settings, numbering | Core | MVP | [01 §4.7](modules/01-core.md#47-settings--master-data) |
| 7 | Tamper-evident audit log; document service and templates → PDF | Core | MVP (e-sign adapter P2) | [01 §4.8–4.9](modules/01-core.md#48-audit-log) |
| 8 | Import/export framework incl. opening-balance import mode and templates (D-09; no legacy migration) | Core / SaaS | MVP | [01 §4.10](modules/01-core.md#410-import--export), [06 §4.G](modules/06-saas-platform.md#4g-data-migration--onboarding) |
| 9 | Person vs employment (rehire, concurrent), personal data, dependants, bank, SI number | People | MVP | [02 §4.1–4.2](modules/02-people.md#41-person--employment) |
| 10 | Effective-dated assignments, transfers, promotions; salary history | People | MVP (secondment, mass change, step progression: P2) | [02 §4.3–4.4](modules/02-people.md#43-assignments--organisation-placement) |
| 11 | Labour contracts, probation, appendices, suspension, conversion rules | People | MVP (e-labour contract P2, becomes MVP if mandated) | [02 §4.5](modules/02-people.md#45-labour-contracts) |
| 12 | Onboarding/offboarding checklists; separation, notice, severance eligibility | People | Separation, notice, severance: MVP. Checklists and pre-hire portal: P2 | [02 §4.6–4.7](modules/02-people.md#46-onboarding--offboarding) |
| 13 | Discipline & rewards (statutory procedure) | People | P2 (offline procedure with uploaded documents in MVP; sponsor to re-confirm) | [02 §4.8](modules/02-people.md#48-discipline--rewards) |
| 14 | Appraisal (KPI/OKR, calibration) | People | P2 (probation outcome is recorded with the contract in MVP) | [02 §4.9](modules/02-people.md#49-appraisal--performance) |
| 15 | Training, training bonds | People | P2 | [02 §4.10](modules/02-people.md#410-training) |
| 16 | Expense claims & cash advances | People | P2 (pending People Q5) | [02 §4.11](modules/02-people.md#411-expense-claims--advances) |
| 17 | ESS/MSS, HR decisions & letters, labour book, semi-annual labour report, PDP controls | People | MVP, responsive web (MSS team views, HR letters: P2) | [02 §4.12–4.15](modules/02-people.md#412-ess--mss) |
| 18 | Holiday calendars (VN pack incl. lunar Tết, substitute days) | T&A | MVP | [03 §4.1](modules/03-time-attendance.md#41-holiday-calendars) |
| 19 | Work schedules and shifts (fixed, 5/5.5/6-day, night) | T&A | MVP (rotating, split shifts: P2) | [03 §4.2](modules/03-time-attendance.md#42-work-schedules-and-rosters) |
| 20 | Rosters, shift swaps, roster validation | T&A | P2 | [03 §4.2](modules/03-time-attendance.md#42-work-schedules-and-rosters) |
| 21 | Punch capture: device import + 1 certified connector, Excel/file import, manual correction | T&A / SaaS | MVP. **Web, PWA and mobile GPS/Wi-Fi/selfie check-in: P2 (OD-9)** | [03 §4.3](modules/03-time-attendance.md#43-check-in-sources-and-raw-punches) |
| 22 | Daily timesheet processing, corrections (giải trình) | T&A | MVP | [03 §4.4](modules/03-time-attendance.md#44-daily-timesheet-processing) |
| 23 | Overtime: requests, detection, VN categories, 40/200/300h caps | T&A | MVP | [03 §4.5](modules/03-time-attendance.md#45-overtime) |
| 24 | Leave types, balances, accrual, seniority, carry-forward, encashment days | T&A | MVP | [03 §4.6](modules/03-time-attendance.md#46-leave-management) |
| 25 | Business trips / WFH (timesheet effect) | T&A | MVP | [03 §4.7](modules/03-time-attendance.md#47-business-trips-and-remote-work) |
| 26 | Period close, Attendance Result hand-off, retro deltas | T&A | MVP | [03 §4.8, §7.3](modules/03-time-attendance.md#48-period-close-payroll-hand-off-and-retro) |
| 27 | Component catalogue, formula engine, effective-dated legal parameters | Payroll | MVP (VN starter templates, multi-currency: P2) | [04 §4.1](modules/04-payroll.md#41-configuration-parameters--components) |
| 28 | Gross/Net, gross-up, proration, OT/night pay | Payroll | MVP | [04 §4.2](modules/04-payroll.md#42-gross--net-and-earnings-calculation) |
| 29 | SI/HI/UI/OA-OD, trade union fee, D02-LT files, C12-TS reconciliation | Payroll | MVP (retro SI adjustments, SI arrears: P2; SI benefit claims: P2) | [04 §4.3](modules/04-payroll.md#43-compulsory-insurance--trade-union) |
| 30 | PIT: resident/non-resident/casual, 08/CK, 05/KK, 05/QTT, e-certificates | Payroll | MVP | [04 §4.4](modules/04-payroll.md#44-personal-income-tax) |
| 31 | Monthly and off-cycle runs (13th month, Tết), retro pay, final settlement, lock/approve | Payroll | MVP — monthly frequency only (A2) | [04 §4.5](modules/04-payroll.md#45-payroll-runs-lifecycle--control) |
| 32 | Advances and deductions | Payroll | MVP incl. interest-free employee loans for opening balances (loan interest: P2) | [04 §4.6](modules/04-payroll.md#46-loans-advances--deductions) |
| 33 | Payslips (ESS, protected PDF) | Payroll | MVP | [04 §4.7](modules/04-payroll.md#47-payslips--ess) |
| 34 | Bank payment files, GL journal export, cost allocation | Payroll / SaaS | MVP (host-to-host banking, accounting API push: P2) | [04 §4.8](modules/04-payroll.md#48-payment-accounting--cost-allocation) |
| 35 | Staffing plans, requisitions, openings, multi-posting | Recruitment | P2 | [05 §4.1–4.3](modules/05-recruitment.md#41-staffing--headcount-plan) |
| 36 | Candidates, CV parsing, pipeline, interviews, assessments | Recruitment | P2 | [05 §4.4–4.6](modules/05-recruitment.md#44-candidates-applications-cv-parsing-talent-pool) |
| 37 | Offers (band check, NET offers via gross-up), pre-boarding, hire hand-off | Recruitment | P2 (hand-off contract frozen in Phase 0) | [05 §4.7–4.8](modules/05-recruitment.md#47-offers) |
| 38 | Referrals; recruitment analytics & candidate privacy | Recruitment | P2 / P3 | [05 §4.9–4.10](modules/05-recruitment.md#49-referrals) |
| 39 | Tenant lifecycle, plans, entitlements, feature flags, PEPM billing, VN e-invoice, dunning, offboarding | SaaS | MVP: module subscription, contract price, month-end billable count per legal entity (D-10), e-invoice. Plans/price books, dunning automation, offboarding export/deletion, self-service sign-up, payment gateway: P2 | [06 §4.A](modules/06-saas-platform.md#4a-tenant-lifecycle--commercial) |
| 40 | Shared tier + RLS isolation, VN residency, vi/en, custom fields | SaaS | MVP (custom fields, dedicated tier, sandbox, white-label, custom domain: P2) | [06 §4.B](modules/06-saas-platform.md#4b-tenancy-isolation-residency--customisation) |
| 41 | Operator back-office, consent-based support access | SaaS | MVP (impersonation: P2) | [06 §4.C](modules/06-saas-platform.md#4c-operator-back-office--support-access) |
| 42 | Reporting framework, separate reporting store, statutory report framework | SaaS | MVP (separate reporting store, report designer, dashboards, HR analytics: P2) | [06 §4.E](modules/06-saas-platform.md#4e-reporting--analytics) |
| 43 | Integration hub; bank, eTax and time-device integrations | SaaS | MVP: file-based bank, GL, eTax and SI outputs + 1 device connector (integration hub, public API, webhooks, VSS I-VAN API: P2; job boards: P3) | [06 §4.F](modules/06-saas-platform.md#4f-integration) |
| 44 | Responsive ESS web | SaaS | MVP (native mobile app and **all** mobile features: P2) | [06 §4.H](modules/06-saas-platform.md#4h-mobile--ess-channel) |
| 45 | PDP compliance: data inventory, consent, DSR, retention, DPIA, breach, ISO 27001 alignment | SaaS | MVP | [06 §4.I](modules/06-saas-platform.md#4i-compliance-privacy--security) |
| 46 | Marketplace integrations, AI assistants, regional (non-VN) expansion | — | P3 | Charter §6 |

### 5.2 Out of scope (all phases unless noted)

- **Legacy data migration** (D-09, 2026-10-16). There is no Oracle or other legacy migration programme, staging area or parallel run. Customers load opening balances through the standard Excel templates (capability 8, §6.5).
- **General-ledger accounting.** YourHRM exports journals and is not an accounting system. Expense GL posting is done externally.
- **Direct API filing to VSS/GDT in MVP.** The MVP produces files for upload via I-VAN/VssID and eTax/HTKK. T-VAN/I-VAN API filing comes in P2.
- **Processing of SI benefit claims** (sickness and maternity paid by VSS, form 01B-HSB): P2.
- **Bank host-to-host payments in MVP.** The MVP provides upload files only.
- Semi-monthly or weekly pay frequencies in MVP.
- Time-device firmware, door access control and canteen systems. Supply of hardware.
- Project/task time tracking for billing, workforce demand forecasting and auto-roster optimisation (candidate P3).
- Operation of background-check vendors and agency/RPO portals (P3). Payment of agency fees and advertising spend.
- AI ranking and fully automated rejection of candidates (BR-REC-026).
- Concurrent employment across tenants. Cross-tenant identity merging.
- On-premise or private-cloud deployment outside the SaaS model (SaaS Q9).
- Data storage or processing outside Vietnam.

---

## 6. End-to-end business processes

Each step names its owning module. Module-level process detail is in each BRD §3, and the interface contracts are in each BRD §7.

### 6.1 Hire-to-retire
1. **Core** — HR builds legal entities, locations, org units, positions and cost centres. Positions carry the FTE budget.
2. **Recruitment** (P2) or **People** (direct hire) — A hire is triggered (`CandidateHired` or a manual hire).
3. **People** — The person is matched by CCCD, passport, tax code or SI number. HR creates the employment, assignment, contract (probation or labour) and salary record. Events `EmployeeHired`, `AssignmentChanged`, `SalaryChanged` and `ContractSigned` are published.
4. **Core** — A user account is created automatically with the ESS role, and the manager scope is derived.
5. **T&A** — A schedule, holiday calendar and leave entitlements are assigned, and the device enrolment is mapped.
6. **Payroll** — The employee joins a pay group. SI increase (D02-LT) and PIT dependants are prepared.
7. **People** — Changes during employment (transfer, promotion, salary change, contract renewal or conversion, discipline/reward, appraisal) are processed as effective-dated records, each producing a decision (quyết định) and appendix. They are approved through the **Core** workflow, and their events flow to T&A and Payroll.
8. **People** — Retirement is flagged from the statutory retirement-age roadmap. Exit follows §6.4.

### 6.2 Time-to-pay (monthly cycle; example cut-off 25th, pay date 5th)
1. **SaaS** — The current legal parameter pack (rates, caps, brackets, holidays) is published and active.
2. **T&A** — Punches are ingested continuously and processed into daily timesheets. Employees resolve exceptions, and leave, OT, trip and WFH requests are approved via the **Core** workflow.
3. **People** — Changes for the period (hires, terminations, salary, dependants) are effective by the cut-off.
4. **T&A** — At period end the exception dashboard is cleared, managers confirm, and the admin **closes** the period. The versioned Attendance Result (with retro deltas) is published to Payroll (+2 working days).
5. **Payroll** — The run is opened and its inputs snapshotted. Variable inputs are imported. The system calculates gross → proration → OT → SI/HI/UI/TU → PIT → deductions → net, with gross-up for NET employees (+3).
6. **Payroll** — Exceptions are resolved: below minimum wage, negative net, missing bank account or tax code, variance above threshold.
7. **Core** — Approvals run in sequence: payroll manager → finance → director. The run is **Approved/Locked**, and Payroll signals **T&A** to lock the period hard (+5).
8. **Payroll** — Bank files, the GL journal and SI/TU/PIT payables are generated. On the pay date payslips are published to ESS (**SaaS** channel) with notifications from **Core**.
9. **Payroll** — Bank confirmation marks the run as **Paid**. The SI contribution is paid by month-end, and the 05/KK-TNCN is filed by the 20th of the following month (monthly filers) `[verify-legal]`.
10. **Payroll / T&A** — Late changes to locked periods arrive as retro deltas in the next run.

### 6.3 Recruit-to-onboard (Phase 2)
1. **Recruitment** — The staffing plan is pre-filled with actual headcount from **People** and positions from **Core**, then approved via the **Core** workflow.
2. **Recruitment** — A requisition is checked against plan availability and the salary band from **Payroll**, then approved.
3. **Recruitment** — The opening is published (career site, internal board, job boards). Applications are captured with per-purpose consent (**SaaS** consent service).
4. **Recruitment** — Duplicate check runs against candidates and current/former employees in **People**. The candidate moves through screening, interviews (calendar integration) and assessments.
5. **Recruitment** — The offer passes legal checks (probation length and pay, regional minimum wage from **Core**/**Payroll**). NET offers are simulated with **Payroll** gross-up. The offer is approved, then signed via e-sign (**SaaS**) or click-accept.
6. **Recruitment** — The candidate portal handles pre-boarding (CCCD, tax code, SI number, bank, dependants, documents).
7. **Recruitment → People / Payroll** — The `CandidateHired` hand-off creates the pre-hire employee, draft contract, onboarding checklist and salary record without re-keying.
8. **People** — The onboarding checklist is executed. On the start date the employee becomes `Active` and §6.1 continues.

### 6.4 Terminate-to-settle
1. **People** — Resignation is submitted via ESS, or HR/manager initiates termination. The system computes the required notice, checks protected status and routes approval through the **Core** workflow. A termination decision is generated.
2. **People** — The offboarding checklist runs (handover, assets, access revocation date). `TerminationScheduled` is published, followed by `EmployeeTerminated` on the last working day.
3. **Core** — The user is deactivated at the end of the last day, sessions are revoked and pending approvals reassigned.
4. **T&A** — Final timesheet processing projects the leave balance and provides unused annual-leave days for encashment, plus any negative balance to recover.
5. **Payroll** — The final settlement is calculated: prorated salary, leave encashment, severance or job-loss allowance (eligibility from **People**), notice compensation, loan and advance recovery, pro-rata 13th month, and PIT. It is approved and paid within 14 working days, extendable to 30 `[verify-legal]`.
6. **Payroll** — The SI decrease (D02-LT) is filed and SI-book closing data exported. The PIT withholding certificate is issued on request.
7. **People** — Documents are returned and the work confirmation issued. The former employee keeps limited ESS access per policy, and data is retained under the retention schedule (**SaaS** retention policies).

### 6.5 Tenant sign-up-to-go-live (pilot / assisted path, MVP)
1. **SaaS** — The contract is signed. The operator creates the tenant (legal entities, edition, modules, tier, VN region) and provisions it in ≤ 10 min with reference data and the current legal parameter pack. The DPA is signed.
2. **Core** — `TenantProvisioned` creates the company group, owner account, system roles, templates and settings. The owner activates MFA (SSO is P2).
3. **Core / T&A / Payroll** — Configuration: org structure, workflows, holiday calendar, schedules, leave policies, pay groups, components and formulas, bank and GL mapping, and the time-device connector or Excel punch import.
4. **SaaS / Core** — Opening-balance import (changed in v0.2, D-09; no Oracle migration). The operator and customer agree the go-live month per legal entity (BR-SAAS-088). The customer fills in the standard Excel templates (BR-SAAS-069): employee master, salary history for severance and average bases, SI contribution history, YTD income/insurance/PIT per month of the tax year (BR-SAAS-089), leave balances and YTD OT, and outstanding loans and advances. Each file is validated in validate-only mode until clean, then committed in the **Core** opening-balance import mode (BR-CORE-114).
5. **SaaS / Payroll / T&A** — Opening balances are reconciled against the customer's control totals: the last legacy payroll register, VSS C12-TS, 05/KK already filed, and the leave report (BR-SAAS-072). This covers payroll YTD, PIT YTD, dependants, SI history, leave balances and loans. The customer's HR/C&B and finance owners sign off.
6. **Payroll** — The first live payroll is calculated in simulation, and its exception and variance reports are reviewed with the customer before approval. An optional comparison against the customer's last payroll register is P2 (BR-SAAS-073); the sponsor is to re-confirm whether it is needed (mvp-scope.md §8).
7. **SaaS** — Go-live: the go-live checklist is signed (see §10). Approving the first regular run locks the opening balances of that legal entity (BR-SAAS-088). Billing starts with VN e-invoicing, counting persons active at month end per legal entity (BR-SAAS-012, D-10).
8. *(P2)* Self-service path: sign-up with OTP and MST validation → trial in ≤ 2 min → onboarding wizard → conversion.

### 6.6 Year-end PIT finalisation (quyết toán thuế TNCN)
1. **SaaS** — The PIT parameter pack for the tax year (brackets, family deductions) is confirmed. Peak capacity is planned for January–March (NFR-SAAS-021).
2. **People** — Dependant registrations and changes for the year are finalised (ESS plus HR verification).
3. **Payroll** — The system determines each employee's eligibility to authorise the employer to finalise on their behalf, and shows the reasons for ineligible employees.
4. **Payroll / SaaS ESS** — Eligible employees submit the authorisation (08/UQ-QTT-TNCN or the current form).
5. **Payroll** — Annual tax is recomputed for authorised employees and over- or under-withholding determined. For a tenant that went live mid-year, the months before go-live come from the imported opening balances (BR-SAAS-089). The admin reviews.
6. **Payroll** — Refunds or additional withholding are processed in a run per tenant policy.
7. **Payroll / SaaS** — The 05/QTT-TNCN and annexes 05-1/05-2/05-3 BK-QTT-TNCN are generated in eTax XML and filed by the last day of the third month after year end `[verify-legal]`. E-withholding certificates are issued to employees via ESS or email.

---

## 7. Vietnam regulatory framework (summary)

This table consolidates module BRDs §5. **All entries are `[verify-legal]`** and must be confirmed by a Vietnam labour/tax/data-protection consultant before MVP build sign-off. The values themselves live only in effective-dated parameter packs.

| # | Law / instrument | Topic | Affected modules |
|---|---|---|---|
| L1 | Labour Code 2019 (45/2019/QH14) | Contracts, probation, temporary transfer, notice, protected employees, discipline, working/rest time, OT, holidays, leave, wage payment, deductions, severance, settlement | People, T&A, Payroll, Recruitment `[verify-legal]` |
| L2 | Decree 145/2020/ND-CP | Labour Code guidance: OT caps and the 300h regime, OT/night pay, annual leave, discipline, labour book, labour-usage reports | People, T&A, Payroll `[verify-legal]` |
| L3 | Decree 74/2024/ND-CP; Decree 293/2025/ND-CP (from 2026-01-01) | Regional minimum wage (regions I–IV), remapped after the provincial merger | Core, Payroll, People, Recruitment `[verify-legal]` |
| L4 | Resolution 202/2025/QH15; Law 72/2025/QH15 | 34 provinces, two-level local government; administrative-unit master data | Core, People `[verify-legal]` |
| L5 | Law on Social Insurance 2024 (41/2024/QH15, effective 2025-07-01) | SI contributions, reference level, 14-day exemption, sickness/maternity entitlements, registration and payment deadlines | Payroll, T&A, Core `[verify-legal]` |
| L6 | Law on Health Insurance (amended 2024) | HI contribution and cap | Payroll `[verify-legal]` |
| L7 | Employment Law 2025 (74/2025/QH15, effective 2026-01-01) | Unemployment insurance; labour-change notices; non-discrimination | Payroll, People, Recruitment `[verify-legal]` |
| L8 | Law on OSH 2015; Decree 58/2020/ND-CP | OA-OD insurance rate (0.5%, or 0.3% if approved) | Payroll `[verify-legal]` |
| L9 | Trade Union Law 2024 | 2% trade-union fee; member dues | Payroll `[verify-legal]` |
| L10 | Decree 73/2024/ND-CP | Base salary (lương cơ sở) / reference level for caps | Payroll `[verify-legal]` |
| L11 | PIT Law (2007, amended) and PIT Law 2025; Resolution 110/2025/UBTVQH15; Circular 111/2013/TT-BTC; Circular 92/2015/TT-BTC | Residency, brackets (7 → 5), family deductions, exemptions, casual 10% withholding, dependants | Payroll, People `[verify-legal]` |
| L12 | Law on Tax Administration 38/2019/QH14; Decree 126/2020/ND-CP; Circular 80/2021/TT-BTC; Circular 86/2024/TT-BTC | Declarations (05/KK, 05/QTT), deadlines, MST formats, CCCD as personal tax code | Payroll, Core, People `[verify-legal]` |
| L13 | Decree 123/2020/ND-CP as amended by Decree 70/2025/ND-CP; Circular 78/2021, Circular 32/2025 | E-invoices (subscription billing), electronic PIT withholding certificates | SaaS, Payroll `[verify-legal]` |
| L14 | VSS Decisions 595/QĐ-BHXH and 490/QĐ-BHXH (and successors) | SI forms D02-LT, TK1-TS, C12-TS; e-declaration via I-VAN | Payroll, SaaS `[verify-legal]` |
| L15 | Law on Personal Data Protection 2025 (91/2025/QH15, effective 2026-01-01); Decree 13/2023/ND-CP | Consent, sensitive data, DSR, DPIA, cross-border transfer, 72h breach notice | All modules, SaaS `[verify-legal]` |
| L16 | Cybersecurity Law 2018 (and 2025 update); Decree 53/2022/ND-CP | Data localisation in VN; log retention | SaaS, Core `[verify-legal]` |
| L17 | Law on Network Information Security 2015; Decree 85/2016/ND-CP | Information-system security level classification | SaaS `[verify-legal]` |
| L18 | Law on E-Transactions 2023 (20/2023/QH15); Decree 23/2025/ND-CP; e-labour-contract decree (e.g. 337/2025/NĐ-CP) | E-signature, e-documents, electronic labour contracts | Core, People, SaaS `[verify-legal]` |
| L19 | Law on Identification 2023; Decree 69/2024/ND-CP | VNeID e-identification | People, SaaS `[verify-legal]` |
| L20 | Accounting Law 2015 (88/2015/QH13); Decree 174/2016/ND-CP | ≥ 10-year retention of accounting records, including payroll | Core, Payroll, SaaS `[verify-legal]` |
| L21 | Circular 11/2020/TT-BLĐTBXH | Heavy/hazardous job list (retirement, leave, allowances) | Core, T&A, People `[verify-legal]` |
| L22 | Decree 135/2020/ND-CP | Retirement-age roadmap | People `[verify-legal]` |
| L23 | Decree 152/2020/ND-CP (as amended); Decree 219/2025/NĐ-CP | Foreign workers: work permits and demand approval | People, Recruitment, Payroll `[verify-legal]` |
| L24 | Law on Gender Equality 2006; Law on Judicial Records 2009 | Non-discrimination; limits on judicial-record requests | Recruitment `[verify-legal]` |
| L25 | Decree 30/2020/ND-CP; GSO Decision 121-TCTK/PPCĐ | Official document conventions; 54 ethnic groups | Core `[verify-legal]` |
| L26 | Decree 91/2020/ND-CP | SMS brandname and anti-spam rules | SaaS, Core `[verify-legal]` |
| L27 | Decree 12/2022/ND-CP | Administrative sanctions (labour, SI) — evidence and record keeping | T&A, People, Payroll `[verify-legal]` |

---

## 8. Product-wide non-functional requirements

The product-wide NFRs are owned by [06 SaaS Platform §9](modules/06-saas-platform.md#9-non-functional-requirements-product-wide). Module NFRs (`NFR-CORE/PPL/ATT/PAY/REC`) refine them and must not contradict them.

| Area | Product-level target (summary) | Reference |
|---|---|---|
| Availability & maintenance | ≥ 99.5% (≥ 99.9% Enterprise); no maintenance between the 25th and the 5th | NFR-SAAS-001 |
| Payroll performance | 10,000 employees ≤ 15 min; 1 employee ≤ 5 s | NFR-SAAS-002, NFR-PAY-001 |
| Interactive performance | p95 ≤ 500 ms transactional; ≤ 3 s standard reports | NFR-SAAS-003, NFR-CORE-002/003, NFR-PPL-003 |
| Check-in burst | 2,000 events/min per tenant; 20,000/min platform-wide | NFR-SAAS-004, NFR-ATT-001/002 |
| Scalability & fairness | ≥ 500 tenants and 200,000 employees at launch, 5× headroom; noisy tenant capped at 20% of pool | NFR-SAAS-005, NFR-PAY-013 |
| Backup & DR | RPO ≤ 15 min; RTO ≤ 4 h (≤ 2 h Enterprise); second VN data centre | NFR-SAAS-006 |
| Security | TLS 1.2+, AES-256, field-level encryption of sensitive data, vault secrets, OWASP ASVS L2, pen tests | NFR-SAAS-007/008/009, NFR-CORE-007/014 |
| Tenant isolation | `tenant_id` + PostgreSQL RLS + app filter; release-blocking isolation tests | BR-SAAS-021/023, NFR-CORE-001, NFR-PAY-005, NFR-ATT-010 |
| Observability | Tenant-tagged logs, metrics and traces; logs ≥ 12 months (security ≥ 24) in VN | NFR-SAAS-010 |
| Localisation & UX | Full vi/en; Vietnamese diacritics in PDF/Excel; accent-insensitive search; 360 px responsive; WCAG 2.1 AA (S) | NFR-SAAS-011/012/013, NFR-CORE-011 |
| Release management | Zero-downtime releases; rollback ≤ 15 min; calculation changes behind per-tenant toggles | NFR-SAAS-014/015 |
| Legal agility & traceability | Parameter pack ≥ 7 days before effective date; every result stores pack, config and release versions; deterministic, reproducible for ≥ 10 years | NFR-SAAS-016/017, NFR-PAY-002/003/007, NFR-ATT-006 |
| Data portability | Full tenant export ≤ 6 h for 10,000 employees | NFR-SAAS-019 |
| Support & peak capacity | Sev-1 response ≤ 1 h (Enterprise 24×7); 3× capacity for payroll close and Jan–Mar PIT | NFR-SAAS-020/021 |
| API & mobile (P2) | Backward-compatible API, ≥ 6-month deprecation; app cold start ≤ 3 s | NFR-SAAS-018/022 |

---

## 9. Requirement inventory

**Counting rules.** The counts cover the rows defined in each module BRD's §4 (BR) and §9 (NFR), counted with `awk` on 2026-10-16 after the v0.2 edits (script in [mvp-scope.md §10](mvp-scope.md#10-reproducing-the-counts)). Five rows have split priorities and are counted under their leading priority: BR-CORE-039, BR-CORE-070, BR-REC-033 and BR-REC-045 count as M, and BR-PPL-058 ("S→M if mandated") counts as S. No requirement uses `W`. Changes since v0.1:
- T&A and Payroll added six X-03 close-hold BRs: BR-ATT-079, 087, 088 and BR-PAY-082, 083, 084.
- SaaS added BR-SAAS-088 and BR-SAAS-089 (D-09).
- BR-SAAS-073 changed from M to S.

**MoSCoW** ranks requirements within a phase. **Phase** (P1/P2/P3) is the scope decision, re-rated in **[mvp-scope.md](mvp-scope.md)** for X-04/OD-9/D-09. That document has the rules, the reason for every ID and the list of D-09 changes.

| Module | BRD | BR total | BR M | BR S | BR C | **BR P1** | BR P2 | BR P3 | NFR total | **NFR P1** | NFR P2 | Total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Core | [01-core.md](modules/01-core.md) | 78 | 62 | 11 | 5 | **52** | 21 | 5 | 15 | **13** | 2 | 93 |
| People | [02-people.md](modules/02-people.md) | 93 | 65 | 23 | 5 | **50** | 38 | 5 | 12 | **10** | 2 | 105 |
| Time & Attendance | [03-time-attendance.md](modules/03-time-attendance.md) | 80 | 61 | 16 | 3 | **61** | 19 | 0 | 11 | **9** | 2 | 91 |
| Payroll | [04-payroll.md](modules/04-payroll.md) | 87 | 72 | 12 | 3 | **72** | 12 | 3 | 13 | **11** | 2 | 100 |
| Recruitment (P2 module) | [05-recruitment.md](modules/05-recruitment.md) | 63 | 42 | 20 | 1 | **0** | 62 | 1 | 11 | **0** | 11 | 74 |
| SaaS Platform | [06-saas-platform.md](modules/06-saas-platform.md) | 89 | 57 | 26 | 6 | **46** | 39 | 4 | 22 | **18** | 4 | 111 |
| **Total** | | **490** | **359** | **108** | **23** | **281** | **191** | **18** | **84** | **61** | **23** | **574** |

**Phase 1 scope: 281 BRs and 61 NFRs.** In v0.1 the MVP was 310 Must BRs (Recruitment excluded). Including the six X-03 additions, that baseline is now 316. The re-rating moves 41 of those Must BRs to P2 and pulls 4 Should BRs into P1 because they are statutory or needed for D-09. It also adds 2 new D-09 BRs. All Recruitment items are Phase 2.

The sponsor's target was about 180–200 BRs. **That target is not reached**:
- 107 P1 BRs are statutory (SI, PIT including finalisation, contracts, termination, OT and leave law, labour reports, PDPL, e-invoice).
- 121 more are the processing chain those statutory outputs depend on.

A further 29 non-statutory BRs are listed as a sponsor-decided stretch cut ("Option B", mvp-scope.md §4), which would bring P1 to 252. The recommendation is to plan in story points and keep Option B as the descope reserve.

---

## 10. MVP definition — pilot tenant go-live

**Pilot profile.** The pilot is an existing customer that moves to YourHRM as a **new system** (D-09). Its legacy Oracle HR data is not migrated. The pilot loads opening balances from its own records through the standard Excel templates and may go live mid-year. It may have one or more legal entities and pays monthly in VND. Bank list and entity count are to be confirmed (Payroll Q9, Core Q7).

**Delivery channel.** Phase 1 is delivered as a web app only: admin plus a responsive ESS/MSS web. There are **no mobile features**: no native app, PWA, push notifications, or GPS/Wi-Fi/selfie/web check-in. Attendance comes from time devices (one certified connector) and Excel/file import (X-04, OD-9).

### 10.1 Must-have capabilities
The exact list is the P1 rows of [mvp-scope.md](mvp-scope.md) §9. In summary:
1. **Platform.** Assisted provisioning, shared tier with RLS, VN hosting, vi/en, local login with MFA (SSO is P2), operator back-office with consent-based support grants, module subscription with contract price and month-end billable count per legal entity (D-10), VN e-invoice, standard and statutory report framework, e-mail notifications, backup/DR.
2. **Core.** Legal entities and locations with regions, effective-dated org (reorganisation packages and position control are P2), job titles, grades and cost centres, RBAC with data scopes and masking, workflow (sequential, any-of, all-of, HR reassignment), in-app and e-mail notifications, audit, document templates, and the import framework with **opening-balance import mode**.
3. **People.** Person and employment records (incl. rehire), personal data and dependants, assignments, salary history, labour contracts, probation and suspension, separation with notice, protection and severance eligibility, ESS web, decisions, labour book, semi-annual and monthly labour reports, PDP controls. Onboarding/offboarding checklists, discipline procedure, rewards, probation appraisal and MSS team views are P2.
4. **T&A.** VN holiday pack (incl. foreign employees' holidays), fixed weekly and night schedules, punch import plus one certified device connector, Excel punch/timesheet import, timesheets and corrections, OT with categories and legal caps (incl. 300h notice), leave with accrual, seniority and carry-forward, maternity, trips/WFH, period close with close hold (X-03), Attendance Result and retro, and opening leave balances and YTD OT.
5. **Payroll.** Components and formulas, effective-dated legal parameters, Gross/Net with gross-up, SI/HI/UI/OA-OD/TU, PIT (all regimes, monthly cumulative, finalisation using imported pre-go-live months), monthly and off-cycle runs (incl. 13th month/Tết), retro pay, final settlement and severance, advances and interest-free loans, payslips (ESS web and protected e-mail PDF), bank files for the pilot's banks, GL export, D02-LT, 05/KK and 05/QTT files, e-withholding certificates.
6. **Opening-balance import** (replaces "Migration", D-09). Standard Excel templates for the employee master, salary history, SI contribution history, YTD income/insurance/PIT per month, leave balances and YTD OT, and loans. Validate-only runs, reconciliation against the customer's control totals with sign-off, and a lock at the first approved run (BR-SAAS-069–072, 088, 089).

### 10.2 Go-live acceptance criteria
| # | Criterion |
|---|---|
| G1 | The opening-balance reconciliation report against the customer's control totals (last legacy payroll register, VSS C12-TS, 05/KK filed for the year, leave report) shows zero unexplained differences and has been signed off by the pilot's HR/C&B and finance owners (BR-SAAS-072). *(changed in v0.2, D-09)* |
| G2 | Every month of the current tax year before go-live is loaded for every employee (BR-SAAS-089). The first live payroll has been calculated in simulation, and its exception and variance reports have been reviewed and explained with the customer before approval. *(replaces the parallel-run criterion, D-09)* |
| G3 | The golden payroll test pack (≥ 200 scenarios) passes, and the VN consultant has signed off the active legal parameter pack (NFR-PAY-008). |
| G4 | D02-LT and 05/KK files and the pilot bank files have been validated against current portal and bank templates (NFR-PAY-010). |
| G5 | The isolation test suite passes, the penetration-test findings at critical/high level are closed, and the DPA and DPIA inputs are delivered. |
| G6 | UAT for processes §6.1, §6.2, §6.4 and §6.5 is signed, on the web app only. §6.6 is rehearsed on the **imported** YTD data if go-live falls before year end. |
| G7 | The cut-over month is agreed per legal entity, and the opening balances lock on approval of the first regular run (BR-SAAS-088). The go/no-go is approved by the sponsor and the pilot customer. *(changed in v0.2, D-09)* |

### 10.3 Explicitly not required for pilot go-live
- **Mobile and channels.** All mobile features (native app, PWA, push, GPS/Wi-Fi/selfie and web check-in, mobile approval), Zalo/SMS channels.
- **People and T&A features.** Recruitment, appraisal (incl. the probation appraisal form), training, expenses, rosters and shift swaps, onboarding/offboarding checklists and the pre-hire portal, discipline procedure, MSS team dashboards.
- **Platform and integration.** SSO, reorganisation packages and position control, approval delegation and SLA escalation, custom fields, report designer and dashboards, separate reporting store, integration hub, public API and webhooks, VSS/eTax API submission, host-to-host banking.
- **Commercial.** Plans/price books, dunning automation, tenant offboarding export and deletion, self-service sign-up, sandbox, dedicated tier, white-label.
- **Migration.** Any legacy data migration or parallel run.

---

## 11. Assumptions, constraints, dependencies, risks

### 11.1 Assumptions
| # | Assumption | Source |
|---|---|---|
| A1 | Data ownership is split by domain. Payroll owns money. People owns agreed compensation and personal, tax and SI master data. T&A owns time quantities. Core owns structure, access and the shared engines. | Payroll A1; People A2; T&A A2 |
| A2 | The vendor maintains legal parameter packs centrally. Tenants override them only where the law allows, for example with more favourable benefits. | SaaS A7; Payroll A4 |
| A3 | The tenant is the personal-data controller and the vendor is the processor under a signed DPA. | SaaS A2; Recruitment A-05 |
| A4 | Hosting is with a VN cloud provider that has two or more sites. No personal data is held in hyperscaler regions outside VN. | SaaS A1 |
| A5 | The pilot fills in the opening-balance templates from its own records: every month of the current tax year before go-live, at least six months of salary history, SI contribution history, leave balances and loans. It also provides control totals and the data owners who sign off the reconciliation. There is no Oracle access and no parallel run (changed in v0.2, D-09). | SaaS A4; Payroll A5 |
| A6 | E-invoice, I-VAN, e-signature and VNeID come from certified third-party providers. | SaaS A5/A6; People A6 |
| A7 | Biometric matching happens on the device. YourHRM stores no biometric templates. | T&A A5 |

### 11.2 Constraints
- PostgreSQL with one schema per module (`core`, `hr`, `att`, `pay`, `rec`) plus `saas` for platform data (charter §2, X-07), Java Spring Boot, and multi-tenant from day one.
- Phase 1 is web only (admin + responsive ESS). Mobile features are Phase 2 (X-04, OD-9).
- Legal values are never hard-coded.
- Personal data, customer data files (e.g. opening-balance templates) and credentials are never committed to the repository.
- All personal data stays in Vietnam.
- Payroll maintenance windows must avoid the 25th–5th of each month.

### 11.3 Dependencies
| # | Dependency | Needed for |
|---|---|---|
| DP1 | Retained VN labour, tax and data-protection consultant | `[verify-legal]` sign-off; golden tests; parameter packs |
| DP2 | Pilot customer: completed opening-balance templates and control totals, reconciliation sign-off owners, bank list, time-device model/firmware, UAT staff | MVP go-live |
| DP3 | VN cloud/data-centre provider (two sites) | Residency, DR |
| DP4 | E-invoice provider; I-VAN/T-VAN; banks' file formats; e-signature CA | Billing, statutory filing, payments, e-contracts |
| DP5 | One certified time-device connector (ZKTeco or Hikvision) | T&A MVP |
| DP6 | Architecture decisions: tenancy isolation model, workflow engine build vs embed, reporting store | All modules (see gap analysis) |

### 11.4 Top risks (consolidated)
| # | Risk | Impact | Mitigation | Module refs |
|---|---|---|---|---|
| RK1 | High volume of legal change in 2025–2026: SI Law 2024, PIT Law 2025, Employment Law 2025, PDP Law 2025, minimum wage, provincial merger, e-contract decree | Wrong pay; rework; penalties | Effective-dated packs; regulatory SLA; golden tests; consultant on retainer | PAY R1, SAAS R2, PPL RK1, ATT R3 |
| RK2 | Cross-tenant data leak | Critical legal and reputational damage | RLS + app filter; release-blocking isolation tests | SAAS R1, CORE R-08 |
| RK3 | Incomplete or wrong opening balances in the customer's Excel files (no migration, D-09): duplicates, missing IDs, three-level addresses, missing pre-go-live months, YTD/SI history | Wrong monthly PIT and finalisation, severance and leave; go-live delay | Standard templates with validate-only runs; old→new address mapping; full tax-year months mandatory (BR-SAAS-089); reconciliation gate against control totals (BR-SAAS-072); lock and audited adjustments (BR-SAAS-088) | SAAS R3, PAY R7, PPL RK2, CORE R-01, ATT R7 |
| RK4 | Retro and effective-dating inconsistency across People, T&A and Payroll | Pay and costing errors | Versioned events; Attendance Result retro contract; period locks; contract tests | PAY R4, ATT R2/R4, PPL RK3/RK4, CORE R-06 |
| RK5 | MVP scope does not fit 16 weeks: P1 is still 281 BRs against a target of about 180–200, because 107 BRs are statutory (X-04) | Delay to pilot go-live and to the first paying tenant | Phase rating in mvp-scope.md; story-point sizing; Option B stretch cut (−29 BRs) as descope reserve; no new P1 items without a matching swap | CORE R-04, PPL RK6, SAAS R8 |
| RK6 | Exposure of sensitive personal data (salary, bank, CCCD, health; GPS/selfie only from P2) | PDP Law penalties | Field encryption, masking, access logging, consent, DPIA | PPL RK5, PAY R6, ATT R5, SAAS R7 |
| RK7 | External format/API changes (VSS, eTax, banks, devices, job boards) | Rejected files; integration gaps | Configurable templates; integration hub; connector versioning | PAY R5, SAAS R6, ATT R1, REC RK-02 |
| RK8 | Data residency breach via third-party SaaS tools (email, monitoring, CV parser) | Regulatory penalty | Vendor inventory; VN-hosted alternatives; PII scrubbing; tenant opt-in | SAAS R4, REC RK-05 |
| RK9 | No parallel run before go-live (D-09): calculation or configuration errors first appear on live payslips | Wrong first payroll; loss of pilot confidence | Golden test pack (G3); first run in simulation with variance review (G2); optional comparison against the customer's last payroll register (BR-SAAS-073, sponsor to re-confirm as P1) | SAAS R3, PAY R7 |
| RK10 | Pilot attendance relies only on time devices and Excel (OD-9). Staff without devices, such as office, field and remote staff, need Excel or correction requests | Missing punches; heavier admin work at period close | Excel timesheet import (BR-ATT-025); ESS correction requests (BR-ATT-036); trips/WFH codes; device coverage agreed with the pilot | ATT R1 |

---

## 12. Glossary

| # | English | Vietnamese | Meaning |
|---|---|---|---|
| 1 | Social insurance (SI) | Bảo hiểm xã hội (BHXH) | Compulsory insurance covering retirement and survivorship, sickness and maternity, and OA-OD. It is funded by employer and employee contributions on the SI salary basis. |
| 2 | Health insurance (HI) | Bảo hiểm y tế (BHYT) | Compulsory health cover funded by employer and employee contributions. |
| 3 | Unemployment insurance (UI) | Bảo hiểm thất nghiệp (BHTN) | Compulsory scheme, capped at 20 × regional minimum wage. It does not apply to foreigners. |
| 4 | Occupational accident & disease insurance (OA-OD) | Bảo hiểm tai nạn lao động, bệnh nghề nghiệp | Employer-only SI component. |
| 5 | Vietnam Social Security (VSS) | Bảo hiểm xã hội Việt Nam | Authority collecting SI/HI/UI. It receives D02-LT declarations and issues C12-TS notices. |
| 6 | SI unit code / SI number | Mã đơn vị BHXH / Mã số BHXH (sổ BHXH) | Employer registration code at VSS, and an individual's lifetime SI number or book. |
| 7 | Trade union fee | Kinh phí công đoàn (KPCĐ) | 2% employer levy on the SI salary basis. It is distinct from member dues (đoàn phí). |
| 8 | Tax code | Mã số thuế (MST) | Enterprise tax code: 10 digits, or 13 for dependent units. Individuals use their 12-digit personal ID number. |
| 9 | Citizen identity card | Căn cước công dân / Căn cước (CCCD) | National ID with a 12-digit personal identification number. Also serves as the personal tax code. |
| 10 | VNeID | VNeID (định danh điện tử) | National electronic identification app and level-2 e-ID. |
| 11 | Personal income tax (PIT) | Thuế thu nhập cá nhân (TNCN) | Progressive tax for residents, 20% flat for non-residents, 10% withholding on casual income. |
| 12 | PIT year-end finalisation | Quyết toán thuế TNCN | Annual recomputation of PIT. The employer finalises for authorised employees (05/QTT-TNCN). |
| 13 | Family deduction | Giảm trừ gia cảnh | Monthly deduction from taxable income: personal deduction plus an amount per registered dependant. |
| 14 | Dependant | Người phụ thuộc | Person registered for a family deduction. Each dependant can be claimed by only one taxpayer. |
| 15 | PIT withholding certificate | Chứng từ khấu trừ thuế TNCN | Certificate of tax withheld, issued electronically to the payee. |
| 16 | Commitment form 08/CK-TNCN | Bản cam kết 08/CK-TNCN | Declaration that annual income stays below the taxable threshold, which avoids the 10% casual withholding. |
| 17 | Base salary / reference level | Lương cơ sở / Mức tham chiếu | State reference amount used for SI/HI caps (20 ×) and other benefits. Under the SI Law 2024 the reference level replaces base salary. |
| 18 | Regional minimum wage | Lương tối thiểu vùng | Statutory monthly and hourly floor for regions I–IV, determined by work location. |
| 19 | Pay scale | Thang bảng lương | Employer's grade and step salary table. |
| 20 | Allowance | Phụ cấp | Additional pay component. Each allowance carries independent PIT and SI treatment flags. |
| 21 | Standard working days | Ngày công chuẩn | Divisor for proration (actual scheduled days, fixed 26, or calendar method). |
| 22 | Actual worked days | Ngày công thực tế | Days worked, including paid entitlements, as delivered by T&A. |
| 23 | Overtime (OT) | Làm thêm giờ | Work beyond normal hours, paid at ≥ 150/200/300% plus night premiums. Capped at 40 h/month and 200 or 300 h/year. |
| 24 | Annual leave | Nghỉ phép năm | 12/14/16 working days plus 1 day per 5 years of service. Unused days are paid on termination. |
| 25 | Compensatory leave / TOIL | Nghỉ bù | Time off granted instead of OT pay, or for a holiday falling on a rest day. |
| 26 | 13th-month salary / Tết bonus | Lương tháng 13 / Thưởng Tết | Customary year-end payment, processed in an off-cycle run. |
| 27 | Labour contract (definite / indefinite) | Hợp đồng lao động (xác định / không xác định thời hạn) | Employment contract. Definite term is ≤ 36 months, and after two definite contracts the next must be indefinite. |
| 28 | Probation | Thử việc | Trial period with a statutory maximum length. Pay must be ≥ 85% of the job's salary. |
| 29 | Contract appendix | Phụ lục hợp đồng | Amendment to contract terms. It cannot change the contract duration. |
| 30 | HR decision | Quyết định | Formal signed document for hires, transfers, salary changes, discipline and termination. |
| 31 | Severance / job-loss allowance | Trợ cấp thôi việc / Trợ cấp mất việc làm | Termination allowances of 0.5 or 1 month per year of service, excluding UI-covered time. |
| 32 | Legal entity | Pháp nhân | Legal employer and taxpayer. A tenant may hold several. |
| 33 | Collaborator / service contract | Cộng tác viên (CTV) / Hợp đồng dịch vụ | Non-labour engagement. Payments are taxed as casual income, not SI-liable. |
| 34 | Labour management book | Sổ quản lý lao động | Statutory register of employees kept by the employer. |
| 35 | Attendance explanation | Giải trình chấm công | Employee correction request for a missing punch or late arrival. |
| 36 | Electronic invoice | Hóa đơn điện tử | Mandatory e-invoice format for the vendor's subscription billing. |
| 37 | Effective-dated | Hiệu lực theo thời gian | A record has from and to dates. The system answers "as of date D" and keeps history. |
| 38 | ESS / MSS | Tự phục vụ nhân viên / quản lý | Employee and manager self-service portals. |

---

## 13. Open decisions

The Integration analyst consolidates cross-module open questions, ownership conflicts and interface gaps in **[gap-analysis.md](gap-analysis.md)**. That document is being prepared in parallel and is the single register for decisions. Decisions that block the MVP must be closed before SRS sign-off. Indicative topics raised in the module BRDs:
- the owner of the legal parameter store and holiday calendar
- ESS hosting
- the platform schema and tenant-isolation model
- SI unit code per branch
- the standard-days methods and the OT cut-off
- PIT Law 2025 transition dates and the reference level
- whether e-labour contracts are mandatory
- the pilot's bank list and SCIM needs
- expense reimbursement routing
