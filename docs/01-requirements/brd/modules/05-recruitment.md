# YourHRM — Module BRD: Recruitment (`rec`)

| Item | Value |
|---|---|
| Module | `recruitment` |
| DB schema | `rec` |
| Requirement prefix | `BR-REC-NNN`, `NFR-REC-NNN` |
| Phase | Phase 2 (see charter §6). Data contracts with Core / People / Payroll are fixed in Phase 0 so MVP modules do not need rework. |
| Version | Draft v0.2, 2026-10-16 |
| Change note | v0.2 (2026-10-16): applies the approved single-owner table ([gap-analysis](../gap-analysis.md) §5, decision **R2-02**): the hire hand-off `rec.CandidateHired` goes to **People only**; People owns the agreed compensation (salary record incl. GROSS/NET and agreed net) and employee bank accounts; Payroll learns via `people.SalaryChanged` (P-REC-10, BR-REC-050, §7.3; #5, #24); REC-C19 removed; legal parameters from the SaaS Legal Parameter Service (C18; #1); positions and FTE budget owned by Core, the staffing plan owns the cost budget (A-01, P7; #8); work permits owned by People (#22); consents in the SaaS registry (#27); documents via the Core document service (C24; #15b). Edits are marked "(changed 2026-10-16, R2-02 §5 #n)". Priority columns unchanged (PM syncs). |
| Author | BA – Recruitment |
| Status | Draft for gap analysis |

> **Phase:** the delivery phase of every requirement is set only in [mvp-scope.md](../mvp-scope.md) §9; MoSCoW here is business priority. (2026-10-16)

> Legal values and legal references in this document are marked `[verify-legal]` until checked by a Vietnam labour / data-protection consultant. No legal value is hard-coded; all are effective-dated parameters (charter §4).

---

## 1. Purpose & scope

### 1.1 Purpose
Give each tenant one place to plan headcount, approve hiring, publish vacancies, manage candidates from first contact to signed offer, and hand a hired candidate over to People (onboarding, agreed compensation) without re-keying data; Payroll receives the salary from People (changed 2026-10-16, R2-02 §5 #24). Recruitment must stay within the approved headcount and salary bands, and must process candidate personal data lawfully under Vietnam's personal data protection rules.

### 1.2 In scope
1. **Staffing / headcount plans** per org unit and position, per plan period, with budgeted headcount and cost vs. actual (filled + in-pipeline).
2. **Job requisitions** (Yêu cầu tuyển dụng) with configurable approval workflow, linked to a staffing plan line or flagged as unplanned.
3. **Job openings and postings**: tenant career site, internal job board (internal mobility), multi-posting to VN job boards (TopCV, VietnamWorks, ITviec, LinkedIn, Facebook) and manual channels.
4. **Candidates and applications**: candidate profile (one per person per tenant), applications (candidate × opening), CV upload and parsing, duplicate detection and merge, talent pool and tags.
5. **Pipeline**: configurable stages per opening, screening questions, knock-out rules, bulk actions, rejection reasons, candidate communications (email / SMS / Zalo templates).
6. **Interviews**: scheduling, panels, calendar integration (Google / Microsoft 365), video-meeting links, scorecards, feedback deadlines.
7. **Assessments**: internal tests and third-party assessment links, results stored against the application.
8. **Offers**: offer creation within salary band, GROSS or NET offers, offer approval, offer letter generation and e-signature, acceptance / decline / negotiation, offer expiry.
9. **Pre-boarding**: candidate portal for document collection and personal data completion between acceptance and start date.
10. **Hire hand-off**: conversion of an accepted candidate into a People employee, assignment and salary record (incl. NET/GROSS basis and agreed net); People is the only receiver and Payroll learns the salary from `people.SalaryChanged` (changed 2026-10-16, R2-02 §5 #24).
11. **Referrals**: employee referral submission, tracking, and referral bonus trigger to Payroll.
12. **Recruitment cost, source-of-hire and funnel analytics**.
13. **Candidate privacy**: consent capture, purpose limitation, data-subject requests, retention and automatic deletion/anonymisation of candidate data.

### 1.3 Out of scope
| Item | Owner / Note |
|---|---|
| Org tree, positions, job catalogue, grades master data | `core` (consumed here) |
| Employee record, labour contract, onboarding checklist after hire | `people` |
| Salary structures, bands, PIT gross-up engine, payroll of referral bonus | `payroll` |
| Background check *vendor* operation | External; YourHRM stores only request status and result summary |
| Work permit application and tracking for foreign hires | `people` (BR-PPL-020); Recruitment only flags the need in the hand-off (changed 2026-10-16, R2-02 §5 #22) |
| Agreed compensation (salary record) and employee bank accounts after hire | `people` (changed 2026-10-16, R2-02 §5 #5, #24) |
| Legal parameters (regional minimum wage, probation limits, SI rates for on-cost) | `saas-platform` Legal Parameter Service (changed 2026-10-16, R2-02 §5 #1) |
| Consent registry, DSR orchestration, retention scheduler | `saas-platform` (changed 2026-10-16, R2-02 §5 #27) |
| Agency / RPO vendor portal | Could (Phase 3) |
| AI ranking / automated rejection | Out of scope for Phase 2; see BR-REC-026 (no fully automated decisions) |
| Payment of agency fees / ad spend | Finance system; Recruitment records cost only |

---

## 2. Actors & personas

| Actor | Description | Main goals |
|---|---|---|
| **HR Director / C&B lead** | Owns headcount plan and salary policy | Approve plan, keep hiring within budget and band |
| **Line manager / Hiring manager** (Quản lý tuyển dụng) | Owns the vacancy | Raise requisition, review shortlists, interview, decide |
| **Recruiter** (Chuyên viên tuyển dụng) | Runs the process | Post jobs, screen, schedule, move candidates, prepare offers |
| **Approver** | Department head, Finance, CEO depending on rules | Approve requisitions and offers |
| **Interviewer / panel member** | Any employee | See schedule, submit scorecard |
| **Employee (referrer)** | Any active employee via ESS | Refer candidates, track referral status |
| **Candidate / Applicant** (Ứng viên) | External or internal person | Apply, schedule, receive offer, pre-board, exercise data rights |
| **C&B / Payroll officer** | Payroll module user | Sees new-hire salary through People (`SalaryChanged`), pays referral bonus (changed 2026-10-16, R2-02 §5 #24) |
| **HR admin (People)** | People module user | Receive hire, complete contract and onboarding |
| **Tenant admin** | Configures module | Pipelines, templates, job-board credentials, retention policy |
| **DPO / Privacy officer** | Tenant's data-protection contact | Handle data-subject requests, review retention |
| **External job boards / calendar / e-sign / CV parser** | System actors | Integrations |

---

## 3. Business processes

### P-REC-01 Headcount / staffing planning
1. HR Director opens a plan period (e.g. FY2027) for the legal entity; system pre-fills each org unit × position line with **current actual headcount** and **approved FTE** from Core/People.
2. Department heads propose planned headcount (opening, closing, new positions, replacements) and budgeted monthly cost per line (default: band midpoint × FTE from Payroll, editable).
3. Plan is submitted through Core workflow; approved plan becomes read-only and versioned; revisions create a new version.
4. During the period the system shows per line: **Budget HC**, **Actual HC** (from People), **Open requisitions**, **Offers accepted not yet started**, **Variance**, and the same for cost.
- *Exceptions*: org unit restructured mid-period → lines on retired org units are flagged and must be remapped; position deleted in Core → line flagged.

### P-REC-02 Job requisition
1. Hiring manager creates a requisition: org unit, position, number of vacancies, reason (new / replacement of named employee / temporary), employment type, contract type, work location, target start date, proposed salary range (must lie within band), JD.
2. System checks availability against the staffing plan line (budget HC − actual − open requisitions − accepted offers).
3. If within plan → standard approval chain; if over plan or unplanned → extended chain (e.g. + HR Director + CEO) per tenant rule.
4. On approval the requisition becomes **Approved** and can spawn one or more job openings.
- *Exceptions*: rejected → back to requester with comment; cancelled after openings published → openings closed, candidates notified by template; replacement requisition when the replaced employee withdraws resignation → cancel.

### P-REC-03 Job opening and posting
1. Recruiter creates a job opening from an approved requisition (JD, requirements, salary display option: show range / "negotiable" (Thỏa thuận) / hide), selects pipeline template, screening questions, hiring team.
2. Recruiter selects channels: career site, internal job board, TopCV, VietnamWorks, ITviec, LinkedIn, Facebook, others (manual).
3. System validates content (no discriminatory criteria, mandatory fields per board) and publishes; each channel posting has its own status, URL, publish/expiry date, cost.
4. Applications arrive from career site / board integrations / email inbox / manual entry / referral, each tagged with **source**.
- *Exceptions*: board API failure → posting status `Failed`, retry and notify recruiter; opening filled or put on hold → unpublish all channels.

### P-REC-04 Application intake, parsing and duplicate check
1. Candidate applies (career site form or imported from board), gives consent (P-REC-12) and uploads CV.
2. CV parser extracts name, contacts, education, experience, skills; recruiter confirms/corrects.
3. Duplicate detection runs against existing candidates and **current/former employees** of the tenant; on match the recruiter merges or links.
4. Knock-out questions evaluated; application placed in the first pipeline stage; acknowledgement sent.

### P-REC-05 Screening and pipeline progression
1. Recruiter screens, moves candidates through stages (e.g. New → Screening → Phone screen → Assessment → Interview R1 → Interview R2 → Offer → Hired; terminal: Rejected, Withdrawn).
2. Hiring manager reviews shortlist and gives decision.
3. Rejection requires a reason code; rejection email optional and can be delayed.
4. Unsuccessful candidates can be added to the talent pool if their consent covers it.

### P-REC-06 Interview scheduling and evaluation
1. Recruiter creates an interview (round, type: onsite / phone / video, panel, duration).
2. System reads panel free/busy from calendar integration, proposes slots; candidate self-schedules via link or recruiter picks a slot.
3. Calendar invitations with video link sent to panel and candidate; reminders sent.
4. Each panel member submits a scorecard (criteria, rating, recommendation, comments) before seeing others' feedback (configurable).
5. Reschedule / no-show handled with status and history.

### P-REC-07 Assessment
1. Recruiter assigns an internal test or a third-party assessment link.
2. Results (score, pass/fail, report file) recorded against the application; deadline and reminder handled.

### P-REC-08 Offer
1. Recruiter creates offer: position, grade, org unit, work location, start date, contract type, probation period and probation salary %, salary basis **GROSS or NET**, base salary, allowances, bonus targets, benefits.
2. System checks: salary within band for grade (from Payroll); probation duration ≤ legal maximum for the job level; probation salary ≥ legal minimum %; salary ≥ regional minimum wage for work location; vacancy still available on requisition.
3. For NET offers system calls Payroll gross-up simulation to show estimated GROSS, employer cost, employee SI/HI/UI and PIT.
4. Offer approval through Core workflow (extra approvers if out of band or exceptions).
5. Offer letter generated from template, sent for e-signature / acceptance via candidate portal with expiry date.
6. Candidate accepts / declines (reason) / requests negotiation (new offer version).
- *Exceptions*: offer expires → status `Expired`; candidate reneges after acceptance → `Reneged`, requisition vacancy reopened.

### P-REC-09 Pre-boarding
1. On acceptance the candidate portal opens a pre-boarding checklist: personal data (CCCD/passport, tax code, SI book number, bank account, address, dependants for PIT), documents (CV legal copy, degrees, health certificate, photos, judicial record if required).
2. HR verifies documents; missing items reminded.
3. Optional background / reference check status recorded.

### P-REC-10 Hire hand-off (candidate → employee)
1. When offer is `Accepted` and pre-boarding is `Ready` (or HR forces), recruiter triggers **Hire**.
2. Recruitment publishes a `rec.CandidateHired` hand-off package (§7.3) to **People only** (changed 2026-10-16, R2-02 §5 #24).
3. People creates/reactivates person and employee, assignment, draft labour/probation contract, bank account, onboarding checklist, and the **salary record** (effective from start date, incl. GROSS/NET basis and agreed net) from offer terms; returns `employee_id` (`people.HireHandoffProcessed`).
4. Payroll receives the salary from People (`people.SalaryChanged`); Recruitment receives no acknowledgement from Payroll (changed 2026-10-16, R2-02 §5 #24).
5. Application status → `Hired`; requisition vacancy count decremented; plan "accepted not started" moves to "actual" when People reports the employee as started.
- *Exceptions*: People rejects (e.g. duplicate CCCD with active employee) → hand-off `Failed` with reason, recruiter resolves; start date postponed → offer amendment, re-sent event; no-show on day 1 → People cancels hire, Recruitment receives `HireCancelled` and reopens vacancy.

### P-REC-11 Referral
1. Employee submits a referral (candidate data, CV, opening, relationship) via ESS; candidate receives consent request.
2. Referrer sees status (masked: stage only, no interview feedback).
3. When referred candidate is hired and completes the configured qualifying period (e.g. passes probation), Recruitment sends a referral bonus instruction to Payroll.

### P-REC-12 Candidate consent and data lifecycle
1. Before any processing, candidate gives explicit consent per purpose: (a) processing for this application, (b) retention in talent pool for N months, (c) sharing with tenant's group companies (optional).
2. Board-imported candidates without proof of consent receive a consent request; processing limited until consent or until legal basis is recorded.
3. Candidate can access, correct, download, withdraw consent and request deletion via portal/link.
4. Retention job anonymises/deletes candidate data at end of retention period; hired candidates' data is transferred to People under the employment purpose and the candidate record is closed.

---

## 4. Business requirements

### 4.1 Staffing / headcount plan
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-001 | The system shall maintain staffing plans per legal entity and plan period with lines per org unit × position, holding planned headcount (HC and FTE), planned hires, planned exits and budgeted cost (monthly and period total, VND). | M | Org units / positions from Core. |
| BR-REC-002 | The system shall pre-fill a new plan with actual headcount and FTE per org unit × position as of plan start, read from People. | M | |
| BR-REC-003 | The system shall propose budgeted cost per line as FTE × salary band midpoint (or configured reference point) of the position's grade, plus configurable employer on-cost %, editable by the planner. | S | Band from Payroll; on-cost (SI/HI/UI employer share) parameter from Payroll. |
| BR-REC-004 | Staffing plans shall be approved via the Core workflow engine and versioned; an approved version is read-only and every change creates a new version with change reason. | M | |
| BR-REC-005 | The system shall show, per plan line and rolled up the org tree, Budget HC, Actual HC, Open vacancies (approved requisitions not filled), Accepted-not-started, and Variance, refreshed at least daily and on hire/termination events. | M | Actual from People events. |
| BR-REC-006 | The system shall flag plan lines whose org unit or position has been retired or moved in Core and require remapping before the next plan version is approved. | S | Consumes Core org-change events. |

### 4.2 Job requisitions
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-007 | A hiring manager shall be able to create a requisition with: legal entity, org unit, position, job title, number of vacancies, reason (new / replacement / temporary), replaced employee (if replacement), employment type, contract type, work location, target start date, proposed salary range, JD, hiring team. | M | Replaced employee from People. |
| BR-REC-008 | The system shall validate the requisition against the approved staffing plan line and classify it as *Within plan*, *Over plan* or *Unplanned*. | M | |
| BR-REC-009 | Requisition approval shall use the Core workflow engine with routing conditions on classification (BR-REC-008), org unit, grade and salary range; approvers can approve, reject, or return for change with comments. | M | |
| BR-REC-010 | The proposed salary range on a requisition shall lie within the salary band of the position's grade; values outside require an "out-of-band" justification and an extra approval step. | M | Band from Payroll. |
| BR-REC-011 | A hiring manager shall only create requisitions for org units within their Core data scope; HR can create for any unit. | M | Core RBAC data scope. |
| BR-REC-012 | When a replacement requisition's replaced employee's separation is cancelled in People, the system shall notify the recruiter and suggest cancelling the requisition. | C | |

### 4.3 Job openings & postings
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-013 | The recruiter shall create one or more job openings from an approved requisition; total vacancies across openings cannot exceed approved vacancies. | M | |
| BR-REC-014 | Each tenant shall have a branded career site (logo, colours, custom domain or `<tenant>.careers.yourhrm.vn`), in Vietnamese and English, listing published openings with search/filter by location, department, job level. | M | Branding / domain from SaaS platform. |
| BR-REC-015 | The system shall support an internal job board visible to employees via ESS; internal applicants are identified as employees and their current manager may be notified per tenant policy. | S | |
| BR-REC-016 | The system shall publish/unpublish openings to TopCV, VietnamWorks, ITviec and LinkedIn via their APIs or XML feeds where available, and support Facebook sharing (page post / job link); each posting stores channel, status, external ID, URL, publish/expiry date and cost. | S | API availability per board is an open question (Q-03). Manual channel always available. |
| BR-REC-017 | The system shall import applications from connected job boards (API, or email-inbox parsing as fallback) and tag them with source channel. | S | |
| BR-REC-018 | Before publishing, the system shall warn on discriminatory content in title/requirements (gender, age, ethnicity, religion, marital status, pregnancy, disability, HIV status, etc.) using a configurable keyword list. | S | Labour Code 2019 Art. 8; Employment Law 2025 [verify-legal]. |
| BR-REC-019 | Closing, filling or putting a requisition on hold shall unpublish all its active postings automatically. | M | |

### 4.4 Candidates, applications, CV parsing, talent pool
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-020 | The system shall keep one candidate profile per person per tenant, with any number of applications (candidate × opening), each with its own stage, source, and history. | M | |
| BR-REC-021 | The candidate profile shall hold: full name (Vietnamese diacritics), date of birth, gender (optional), nationality, phone, email, address, CCCD/passport number (only from offer stage), education, work history, skills, languages, expected salary (GROSS/NET), notice period, CV files, tags, source, consent records. | M | Data minimisation: identity docs only from offer stage. |
| BR-REC-022 | The system shall parse uploaded CVs (PDF, DOC/DOCX, images via OCR) in Vietnamese and English into structured fields with confidence indicators; the recruiter confirms before data is saved. | S | Parser may be external; see NFR-REC-006. |
| BR-REC-023 | The system shall detect potential duplicates on create/import using email, phone (normalised to +84), CCCD/passport, and fuzzy name + DOB, and shall allow merge with full audit; it shall also flag matches to current and former employees (incl. "not eligible for rehire" flag from People). | M | |
| BR-REC-024 | Recruiters shall be able to add candidates to talent pools (named lists) with tags and search them by skills, experience, location, last stage reached, only if the candidate's talent-pool consent is valid. | S | |
| BR-REC-025 | The system shall provide full-text and faceted search over candidates and CV content within the tenant. | S | |
| BR-REC-026 | The system shall not reject or rank candidates through fully automated decisions; any score or knock-out result is advisory, and a human records the rejection decision. | M | PDPL automated-processing transparency [verify-legal]. |

### 4.5 Pipeline & screening
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-027 | Tenant admin shall define pipeline templates (ordered stages with type: screening, assessment, interview, offer, hired, rejected) and assign one per opening; the recruiter may add stages per opening. | M | |
| BR-REC-028 | Openings may have screening questions (yes/no, choice, numeric, text) with optional knock-out answers that flag (not auto-reject) the application. | S | See BR-REC-026. |
| BR-REC-029 | Moving an application to *Rejected* or *Withdrawn* shall require a reason code from a configurable list; bulk move/reject/email shall be supported. | M | |
| BR-REC-030 | The system shall send candidate communications (acknowledgement, invitation, rejection, offer) from tenant-editable bilingual templates via email, and optionally SMS and Zalo OA, logging every message on the candidate timeline. | M | Email/SMS/Zalo gateway from Core notifications. |
| BR-REC-031 | Each stage change shall be time-stamped with user and stored so time-in-stage and time-to-hire can be measured. | M | |
| BR-REC-032 | Hiring managers and interviewers shall see only applications for openings they are assigned to; salary expectations and offer data visible only to roles with the "compensation" permission. | M | Core RBAC. |

### 4.6 Interviews & assessments
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-033 | The recruiter shall schedule interviews (round, type, location/room, panel, duration) and the system shall read panel members' free/busy from Google Workspace or Microsoft 365 and propose slots. | M (manual) / S (calendar) | Calendar connector from SaaS platform. |
| BR-REC-034 | Candidates shall be able to self-schedule from offered slots via a secure link; confirmed slots create calendar events with Google Meet / Teams / Zoom link for panel and candidate. | S | |
| BR-REC-035 | The system shall send reminders to candidate and panel (configurable, e.g. 24h and 1h before) and support reschedule, cancel and no-show with history. | M | |
| BR-REC-036 | Each interview shall have a scorecard template (criteria, 1–5 rating, overall recommendation: Strong no → Strong yes, comments); panel members shall submit independently and only see others' scorecards after submitting (configurable). | M | |
| BR-REC-037 | The system shall chase interviewers for overdue scorecards (configurable SLA, default 24h after interview). | S | |
| BR-REC-038 | The system shall record assessments (internal test or third-party link) with score, pass mark, result file and expiry, and display them on the application. | S | |
| BR-REC-039 | Interviewers and panel members shall be selected from active employees (People) and identified by their Core user account. | M | |

### 4.7 Offers
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-040 | The recruiter shall create an offer with: position, grade, org unit, legal entity, work location, manager, start date, contract type and term, probation duration, probation salary %, salary basis (GROSS / NET), base salary, allowances (from Payroll component catalogue), bonus/13th-month terms, benefits, and offer expiry date. | M | |
| BR-REC-041 | The system shall validate offer salary against the band of the grade (min/mid/max) and show compa-ratio; out-of-band offers require justification and additional approval. | M | Band from Payroll. |
| BR-REC-042 | For NET offers, the system shall call the Payroll gross-up simulation and display estimated GROSS, employee SI/HI/UI, PIT (with declared dependants), and employer cost; for GROSS offers the estimated NET. The simulation parameters version (effective date) shall be stored on the offer. | M | Payroll engine is the single source of calculation. |
| BR-REC-043 | The system shall validate: base salary ≥ regional minimum wage of the work location's region; probation duration ≤ legal max for the job level; probation salary ≥ legal minimum % of the job salary; no probation for contracts shorter than the legal threshold. | M | See §5 [verify-legal]. |
| BR-REC-044 | Offer approval shall use Core workflow with routing by grade, out-of-band flag and requisition classification. | M | |
| BR-REC-045 | The system shall generate the offer letter (PDF, Vietnamese/English) from tenant templates with merge fields, and send it for acceptance in the candidate portal with e-signature (internal click-to-accept, or external e-sign provider). | M (click-accept) / S (e-sign) | E-sign connector from SaaS platform. |
| BR-REC-046 | Offers shall be versioned; candidate response options: Accept, Decline (reason), Request change; expired offers move to *Expired* automatically. | M | |
| BR-REC-047 | Accepting an offer shall reserve the vacancy on the requisition and count as "Accepted-not-started" on the staffing plan. | M | |

### 4.8 Pre-boarding & hire hand-off
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-048 | After acceptance, the candidate portal shall present a configurable pre-boarding checklist collecting the personal data and documents People and Payroll need (see §7.3), with validation of CCCD (12 digits), tax code format, bank account, and SI number. | M | Checklist content per tenant. |
| BR-REC-049 | HR shall verify each pre-boarding document (Verified / Rejected with reason); the application becomes *Ready to hire* when all mandatory items are verified. | M | |
| BR-REC-050 | On *Hire*, the system shall emit one `rec.CandidateHired` hand-off package to **People only** containing the data in §7.3, idempotent by `hire_id`, and track the People status (Pending / Accepted / Failed with reason) from the People hand-off result (C15). People is the system of record for the agreed compensation and creates the salary record; Payroll is not a receiver (changed 2026-10-16, R2-02 §5 #24). | M | Core of the module. |
| BR-REC-051 | The system shall not let the same candidate be hired twice for the same requisition and shall block hire if People reports an active employment of the same person in the same legal entity (unless concurrent assignment is allowed by tenant policy). | M | |
| BR-REC-052 | The system shall process `HireCancelled` (no-show) and `StartDateChanged` from People, update the application, and reopen or keep the vacancy accordingly. | M | |
| BR-REC-053 | For internal candidates, Hire shall emit a `rec.InternalTransferAccepted` instruction to People (new assignment / transfer, new salary) instead of a new-employee hire; People applies it through its own job-change and salary-change process (changed 2026-10-16, R2-02 §5 #24). | S | |
| BR-REC-054 | If the hire is a foreign national, the system shall flag "Work permit required" and include it in the hand-off; hire cannot be completed before the work-permit status is recorded unless overridden by HR with reason. People owns the work permit record (BR-PPL-020) (changed 2026-10-16, R2-02 §5 #22). | S | Labour Code Art. 151–153 [verify-legal]; citation to be aligned with People (GAP-042). |

### 4.9 Referrals
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-055 | Employees shall submit referrals via ESS for any published (or internal-referral-only) opening; the referred person receives a consent request before their data is processed beyond the referral record. | S | |
| BR-REC-056 | Referral programmes shall define eligible openings, bonus amount (VND), qualifying condition (hired / passed probation / N days), and exclusions (e.g. hiring managers, HR). | S | |
| BR-REC-057 | When the qualifying condition is met (using People events), the system shall send a referral bonus instruction to Payroll for the referrer's next payroll period. | S | Taxable income; treatment by Payroll. |

### 4.10 Cost, analytics, privacy
| ID | Requirement | Priority | Notes |
|---|---|---|---|
| BR-REC-058 | The system shall record recruitment costs (job-board fees, agency fees, referral bonuses, assessments, events, other) per posting / opening / requisition / source, in VND. | S | |
| BR-REC-059 | The system shall capture explicit, purpose-specific candidate consent (application processing; talent pool with duration; group sharing) with timestamp, channel, notice version and IP, **recorded in the saas-platform consent registry** (subject = candidate id); Recruitment holds references and calls `hasConsent()`; no candidate data beyond the minimum identifiers shall be processed without a valid consent or recorded legal basis (changed 2026-10-16, R2-02 §5 #27). | M | PDPL 2025 / Decree 13/2023 [verify-legal]. |
| BR-REC-060 | Candidates shall be able, via the portal or a tokenised link, to view, correct, export, withdraw consent and request deletion of their data; requests are logged and must be fulfilled within the tenant's configured SLA (default the legal deadline). | M | [verify-legal] for deadlines. |
| BR-REC-061 | Tenant admin shall configure retention periods (default: unsuccessful applicants 12 months after closure; talent pool per consent, max 24 months) [verify-legal]; the saas-platform retention scheduler (single scheduler with legal floors) shall trigger Recruitment's daily anonymise/delete handler for candidate personal data and files once retention expires or consent is withdrawn, keeping only anonymised statistics (changed 2026-10-16, R2-02 §5 #27). | M | |
| BR-REC-062 | Legal hold: HR/DPO can place a candidate under legal hold (e.g. dispute), suspending deletion with a reason and review date. | S | |
| BR-REC-063 | Every view/export/download of candidate CVs and identity documents shall be logged to the Core audit log. | M | |

---

## 5. Vietnam compliance rules

| # | Rule | Legal basis | System behaviour |
|---|---|---|---|
| C-01 | Non-discrimination in recruitment (gender, ethnicity, religion, age, marital status, pregnancy, disability, HIV, union membership, etc.). | Labour Code 2019 (45/2019/QH14) Art. 3, 8; Law on Gender Equality 2006; Employment Law 2025 (74/2025/QH15) [verify-legal] | BR-REC-018 content warning; gender optional; no auto-reject (BR-REC-026). |
| C-02 | Employer must not collect deposits/fees from or retain original ID papers / diplomas of candidates/employees. | Labour Code 2019 Art. 17 [verify-legal] | Pre-boarding collects scans only; no "deposit" item allowed in checklist. |
| C-03 | Duty to provide information (job, workplace, salary, working time, OSH, SI, etc.) before contract; candidate must provide truthful info (identity, health, education). | Labour Code 2019 Art. 16 | Offer letter template mandatory fields; pre-boarding collects health certificate / degrees as configured. |
| C-04 | Probation max duration: ≤180 days (enterprise managers), ≤60 days (jobs requiring college degree or higher), ≤30 days (intermediate / technical worker), ≤6 working days (others); no probation for contracts < 1 month. [verify-legal] | Labour Code 2019 Art. 24–25 | Parameters by job level; BR-REC-043. |
| C-05 | Probation salary ≥ 85% of the job's salary. [verify-legal] | Labour Code 2019 Art. 26 | BR-REC-043. |
| C-06 | Salary ≥ regional minimum wage (Region I–IV) by work location; current monthly values per Decree 74/2024/ND-CP (Region I 4,960,000 VND … Region IV 3,450,000 VND) or successor. [verify-legal] | Labour Code 2019 Art. 90–91; Decree 74/2024/ND-CP [verify-legal] | Parameter owned by Payroll; region by work location from Core. |
| C-07 | Minimum working age 15 (exceptions for minors under strict conditions). | Labour Code 2019 Art. 143–145 [verify-legal] | Warn at offer if DOB < 18 at start date; block if < 15 unless tenant policy and legal conditions recorded. |
| C-08 | Foreign workers require work permit (or exemption certificate) and prior approval of demand for foreign labour. | Labour Code 2019 Art. 151–154; Decree 152/2020/ND-CP as amended (Decree 70/2023/ND-CP; newer decree 2025) [verify-legal] | BR-REC-054 flag. |
| C-09 | Personal data processing requires data-subject consent that is voluntary, explicit, per purpose; data subject rights (be informed, consent, access, withdraw, delete, restrict, object, data portability); processing must stop and data deleted when purpose ends. | Law on Personal Data Protection 2025 (91/2025/QH15, effective 01/01/2026) and implementing decree; Decree 13/2023/ND-CP (until replaced) [verify-legal] | BR-REC-059–063. |
| C-10 | Sensitive personal data (health, criminal record, ethnicity, religion, biometrics, etc.) needs separate notice and stronger protection. | PDPL 2025; Decree 13/2023 Art. 2(4) [verify-legal] | Health certificates, judicial records stored as sensitive class: encrypted, restricted permission, shorter retention. |
| C-11 | Personal data processing impact assessment (DPIA) dossier and, if transferring data abroad (e.g. LinkedIn, foreign CV parser, cloud outside VN), cross-border transfer impact assessment. | PDPL 2025; Decree 13/2023 Art. 24–25 [verify-legal] | Integration registry flags cross-border processors; tenant DPIA export (§8 R-12). |
| C-12 | Breach notification to the competent authority (A05, Ministry of Public Security) within 72 hours. [verify-legal] | PDPL 2025; Decree 13/2023 Art. 23 | Platform-level process (SaaS); Recruitment provides affected-candidate list. |
| C-13 | Judicial record (Phiếu lý lịch tư pháp) may only be requested where law/job allows. | Law on Judicial Records 2009 (amended) [verify-legal] | Checklist item disabled by default; enabling requires a legal-basis note. |
| C-14 | Employer reporting of labour usage / labour demand changes to the local labour authority (semi-annual / on changes). | Labour Code 2019 Art. 12; Decree 145/2020/ND-CP; Employment Law 2025 [verify-legal] | Owned by People; Recruitment provides hiring numbers via §7.2. |

---

## 6. Key business entities

| Entity (`rec.`) | Purpose | Key attributes |
|---|---|---|
| `staffing_plan` | Headcount plan for a period | tenant_id, legal_entity_id, period (from/to), version, status, approved_by/at |
| `staffing_plan_line` | Planned HC & cost per org unit × position (Recruitment owns the **cost** budget; the approved FTE is written to the Core position budget — R2-02 §5 #8) | plan_id, org_unit_id, position_id (or proposed position ref), grade_id, planned_hc, planned_fte, planned_hires, planned_exits, budget_cost_month, currency, core_sync_status |
| `requisition` | Approved request to hire | code, legal_entity_id, org_unit_id, position_id, job_title, vacancies, filled_count, reason, replaced_employee_id, employment_type, contract_type, work_location_id, target_start_date, salary_min/max, salary_basis, plan_line_id, classification, status, workflow_instance_id |
| `job_opening` | Recruitable vacancy | requisition_id, title_vi/en, jd, requirements, pipeline_template_id, salary_display, hiring_team, status, open/close dates |
| `job_posting` | Publication on one channel | opening_id, channel, external_id, url, status, publish_at, expire_at, cost |
| `candidate` | Person (one per tenant) | names, dob, gender, nationality, phones, emails, address, id_doc (masked), education[], experience[], skills[], expected_salary + basis, source, employee_id (if internal/former), status (active / anonymised) |
| `candidate_file` | CV and documents | candidate_id, type, storage_key, sensitive_flag, parsed_json, uploaded_at |
| `application` | Candidate × opening | candidate_id, opening_id, source, referral_id, current_stage_id, status, rejection_reason, applied_at, hired_at |
| `stage_history` | Stage transitions | application_id, from_stage, to_stage, user_id, at, reason |
| `pipeline_template` / `pipeline_stage` | Configurable stages | name, ordered stages, stage_type |
| `screening_answer` | Answers to questions | application_id, question_id, answer, knockout_flag |
| `interview` | Scheduled interview | application_id, round, type, start/end, location, meeting_url, calendar_event_ids, status |
| `interview_panelist` / `scorecard` | Panel and feedback | interview_id, employee_id/user_id, ratings[], recommendation, comment, submitted_at |
| `assessment` | Test result | application_id, provider, score, pass_mark, result, file_id |
| `offer` | Offer with versions | application_id, version, position_id, grade_id, org_unit_id, legal_entity_id, manager_employee_id, work_location_id, start_date, contract_type, probation_days, probation_pct, salary_basis (GROSS/NET), base_salary, allowances[], benefits, gross_estimate, net_estimate, employer_cost, sim_param_version, compa_ratio, out_of_band, status, expires_at |
| `preboarding_item` | Checklist item | application_id, item_type, value_ref/file_id, status, verified_by |
| `hire_handoff` | Hand-off tracking (People is the only receiver — R2-02 §5 #24) | hire_id, application_id, payload_version, people_status, employee_id, error, created_at |
| `referral` / `referral_program` | Referrals | referrer_employee_id, candidate_id, opening_id, relation, status, bonus_amount, qualified_at, payroll_instruction_id |
| `recruitment_cost` | Costs | scope (posting/opening/requisition), category, amount, date, vendor |
| `consent_record` → `consent_ref` | Reference to the saas-platform consent registry (changed 2026-10-16, R2-02 §5 #27) | candidate_id, purpose, registry consent id |
| `data_subject_request` | Removed — owned by saas-platform DSR orchestration (R2-02); Recruitment handles export/anonymise callbacks (changed 2026-10-16, R2-02 §5 #27) | — |
| `talent_pool` / `talent_pool_member` | Pools | name, owner, candidate_id, tags, added_at |

All tables carry `tenant_id` (row-level isolation per SaaS platform) and standard audit columns.

---

## 7. Cross-module interfaces

Interaction style: synchronous API for look-ups/validations at the moment of use; asynchronous domain events (outbox) for state changes. Recruitment never writes to another module's schema.

### 7.1 Consumes

| # | What | From module | When / trigger | Used for | Req |
|---|---|---|---|---|---|
| C1 | Legal entities (company, tax code, address, region) | core | Look-up on plan/requisition/offer; cached | Plan scope, offer letter header, minimum-wage region | BR-REC-001, 040 |
| C2 | Org tree: org units (id, code, name, parent, manager, cost centre, effective dates) | core | Look-up + `OrgUnitChanged/Retired` events | Plan lines, requisitions, data scope, roll-ups | BR-REC-001, 006 |
| C3 | Positions (id, org unit, job/job title, grade, FTE, status vacant/filled, budgeted flag) and job catalogue (job family, job level, JD template) | core | Look-up + `PositionChanged/Retired` events | Plan lines, requisition position, JD pre-fill, probation category by job level | BR-REC-001, 007, 043 |
| C4 | Grades / job levels master | core | Look-up | Band lookup key, probation rule by level | BR-REC-010, 043 |
| C5 | Work locations with labour region (Vùng I–IV) and province | core | Look-up | Minimum-wage check, posting location | BR-REC-043 |
| C6 | Users, roles, permissions, data scopes (incl. "compensation" permission) | core | Every request | Access control on requisitions, candidates, offers | BR-REC-011, 032 |
| C7 | Workflow engine (define chain, start instance, callbacks Approved/Rejected/Returned) | core | Requisition, plan, offer submit | Approvals | BR-REC-004, 009, 044 |
| C8 | Notification service (email, SMS, Zalo OA, in-app) and template rendering | core | Candidate/panel communications | Messages | BR-REC-030, 035 |
| C9 | Audit log service | core | All sensitive actions | Audit | BR-REC-063 |
| C10 | Number sequences / document numbering, tenant settings, document template engine (PDF) | core | Requisition/offer creation | Codes, offer letters | BR-REC-045 |
| C11 | Actual headcount and FTE per org unit × position (snapshot as of date) | people | Plan creation; daily refresh | Plan pre-fill and actuals | BR-REC-002, 005 |
| C12 | Employee events: `EmployeeHired/Started`, `EmployeeTerminated`, `SeparationScheduled/Cancelled`, `EmployeeTransferred`, `ProbationPassed/Failed` | people | Event | Actual HC, replacement requisitions, referral qualification | BR-REC-005, 012, 057 |
| C13 | Employee directory (id, name, user id, org unit, position, manager, status) | people | Look-up | Hiring team, panels, referrers, replaced employee | BR-REC-007, 039, 055 |
| C14 | Person/employee match API: search by CCCD/passport, email, phone, name+DOB → current/former employee, rehire-eligible flag | people | Candidate create/import; pre-hire | Duplicate detection, rehire check, internal candidate detection | BR-REC-023, 051 |
| C15 | Hand-off result: `HireAccepted` (employee_id, person_id, assignment_id, contract draft id), `HireRejected` (reason), `HireCancelled` (no-show), `StartDateChanged` | people | Event after C-hand-off | Close application, vacancy and plan update | BR-REC-050, 052 |
| C16 | Salary structures and bands per grade (min/mid/max, currency, effective date, legal entity), pay component catalogue (allowances, bonus types, taxable/insurable flags) | payroll | Look-up on plan, requisition, offer | Band validation, cost estimate, offer composition | BR-REC-003, 010, 040, 041 |
| C17 | Gross↔Net simulation API: input (basis, amount, components, dependants count, region, nationality/residency, SI participation, effective date) → GROSS, NET, employee SI/HI/UI, PIT, employer SI/HI/UI/TU, employer cost, parameter version | payroll | Offer drafting / approval | NET/GROSS offers | BR-REC-042 |
| C18 | Legal parameters: regional minimum wage by region, probation duration limits and salary min %, employer SI/HI/UI rates for on-cost — `getParam(key, scope, asOfDate)` | saas-platform (Legal Parameter Service) | Offer validation, plan costing | Validation (changed 2026-10-16, R2-02 §5 #1) | BR-REC-003, 043 |
| C19 | Removed — owned by people (R2-02). There is no `SalaryRecordCreated` / `SalaryRecordRejected` from Payroll: People creates the salary record and reports the hand-off result in C15 (changed 2026-10-16, R2-02 §5 #24) | — | — | — | BR-REC-050 |
| C20 | Referral bonus paid confirmation (`ReferralBonusPaid`, period, amount) | payroll | Event | Referral status, recruitment cost | BR-REC-057, 058 |
| C21 | Tenant context, subscription/feature entitlement (Recruitment module enabled, limits e.g. active openings, career-site quota) | saas-platform | Every request | Feature gating | NFR-REC-001 |
| C22 | Career-site hosting: tenant subdomain/custom domain, TLS, branding (logo, colours) | saas-platform | Career site render | BR-REC-014 | BR-REC-014 |
| C23 | Integration connector framework & secret vault: job boards, Google/M365 calendar, video meeting, e-signature, SMS/Zalo, CV parser; per-tenant credentials and OAuth | saas-platform | Posting, scheduling, offer signing | Job boards, calendar, e-sign connectors (e-sign orchestration is Core, R2-02 §5 #15b) | BR-REC-016, 033, 045, 022 |
| C24 | Document service (upload, virus scan, signed URLs, retention class) over saas-platform object storage | core (P-21) | CV/document upload | CV and pre-boarding documents (changed 2026-10-16, R2-02 §5 #15b) | BR-REC-022, 048 |
| C25 | External identity for candidates (passwordless/magic-link portal accounts, separate from employee identity) | saas-platform | Candidate portal | Candidate portal login | BR-REC-060, 048 |
| C26 | Privacy framework (single consent registry, DSR orchestration, retention scheduler — R2-02 §5 #27): consent notice versioning, DSR tracking, retention-job scheduler, data-processor/cross-border registry, breach process | saas-platform | Consent, DSR, retention | Consent references, DSR and retention callbacks | BR-REC-059–062, C-11, C-12 |
| C27 | Interview time constraints: holidays and (optionally) interviewer leave / shifts | time-attendance | Slot proposal | Avoid scheduling on public holidays / approved leave | BR-REC-033 (S) |
| C28 | Start-date validation: working calendar / public holidays by legal entity | time-attendance | Offer creation | Warn when start date is a holiday/non-working day | BR-REC-040 (C) |

### 7.2 Provides

| # | What | To module | When / trigger | Req |
|---|---|---|---|---|
| P1 | **`rec.CandidateHired` hand-off package** (contents §7.3) | people (only receiver) | On Hire action | BR-REC-050 (changed 2026-10-16, R2-02 §5 #24) |
| P2 | `rec.InternalTransferAccepted` (employee_id, new position/org unit/grade/location, effective date, new salary, approval ref) | people | On Hire of internal candidate | BR-REC-053 (changed 2026-10-16, R2-02 §5 #24) |
| P3 | `rec.HireAmended` (start date / position / salary changed after hand-off, before start) and `rec.HireWithdrawn` (candidate reneged) | people | Offer amendment or renege after hand-off | BR-REC-046, 052 (changed 2026-10-16, R2-02 §5 #24) |
| P4 | Pre-boarding documents (Core document references + type + verification status) transferred to the employee document store | people | With P1 | BR-REC-048, 049 |
| P5 | Consent/notice references for data collected during recruitment and pre-boarding, re-purposed under employment in the saas-platform consent registry | saas-platform (consent registry), people | With P1 | BR-REC-059 (changed 2026-10-16, R2-02 §5 #27) |
| P6 | Open vacancies and accepted-not-started per org unit × position (for org chart "vacant" display, workforce dashboards) | core, people | On change / API | BR-REC-005, 047 |
| P7 | Approved staffing plan: approved FTE budgets and **proposed positions written to Core through the Core position API** (Core C-25; the Core position FTE budget stays the authoritative seat budget); the **cost** budget stays in Recruitment for plan-vs-actual reporting | core (position API), saas-platform (reporting) | On plan approval (Phase 2) | BR-REC-004 (changed 2026-10-16, R2-02 §5 #8) |
| P8 | Requisition events `RequisitionApproved/Cancelled/Filled` (position_id, vacancies) — to mark Core positions as "in recruitment" | core | On status change | BR-REC-009, 019 |
| P9 | Referral bonus instruction (referrer employee_id, amount VND, pay component code, target period, referral id) | payroll | Qualifying condition met | BR-REC-057 |
| P10 | Recruitment cost actuals per org unit / cost centre / period | payroll (cost reporting) or finance export | Monthly | BR-REC-058 |
| P11 | Workflow definitions/types for `REC_PLAN`, `REC_REQUISITION`, `REC_OFFER` and their context fields for routing | core | Module install per tenant | BR-REC-009, 044 |
| P12 | Notification event types and default bilingual templates (candidate and internal) | core | Module install | BR-REC-030 |
| P13 | Interview events on employee calendars / ESS task list (interviews to attend, scorecards due) | people (ESS), core (in-app tasks) | On scheduling | BR-REC-036, 037 |
| P14 | Hiring statistics (hires by period, entity, gender, nationality, contract type) for labour-usage reporting | people | Report API | C-14 |
| P15 | Candidate data inventory, DSR log, retention/deletion log, list of affected candidates for breach handling | saas-platform | On request / breach | BR-REC-060–063, C-12 |
| P16 | Usage metrics (active openings, candidates stored, postings, parser calls) for billing/quotas | saas-platform | Daily | NFR-REC-001 |

### 7.3 Candidate → employee hand-off content (`CandidateHired`, payload v1)

| Group | Fields | Primary receiver | Source in `rec` |
|---|---|---|---|
| Keys | hire_id (idempotency), tenant_id, application_id, candidate_id, offer_id + version, requisition_id, existing person_id/employee_id if matched (rehire) | people | hire_handoff |
| Person | full name (VN diacritics), other names, DOB, gender, nationality, ethnicity & religion (only if tenant collects, sensitive), marital status, place of birth, CCCD/passport no., issue date/place, expiry, permanent and current address, personal email, mobile, photo | people | candidate, preboarding |
| Statutory | personal tax code (MST), SI book number (số sổ BHXH), registered hospital for HI (nơi KCB ban đầu), dependants for PIT (name, DOB, relation, ID, tax code, from-month), foreign-worker flag + work-permit status/no./expiry | people (owner); payroll (consumes from people) | preboarding |
| Bank | bank, branch, account number, account holder name | people (owner of employee bank accounts; Payroll reads them from People) (changed 2026-10-16, R2-02 §5 #5) | preboarding |
| Assignment | legal entity, org unit, position, job, grade, job level, work location (+ region), direct manager employee_id, employment type (full/part-time), FTE, start date, contract type and term, probation days, probation end date | people | offer |
| Compensation | salary basis (GROSS/NET), base salary, probation salary % and amount, allowances (component code, amount, frequency), bonus/13th-month terms, effective date, currency, out-of-band flag + approval ref, simulation parameter version, agreed NET (if NET) | people (salary record and contract; Payroll consumes `people.SalaryChanged`) (changed 2026-10-16, R2-02 §5 #24) | offer |
| Documents | Core document refs: signed offer letter, CV, degrees, health certificate, ID scans, photos, (judicial record) with verification status | people | candidate_file, preboarding |
| Recruitment context | source channel, referral_id + referrer, recruiter, hire date (decision), time-to-hire, consent references (saas-platform registry) | people (history) | application, referral, consent |

Rules (changed 2026-10-16, R2-02 §5 #5, #24): People is the system of record for person, statutory, bank, contract **and agreed compensation (salary record)** data after acceptance; Payroll owns only derived pay results and learns the salary from `people.SalaryChanged`. The referral bonus reaches Payroll separately (P9, Phase 2). Recruitment keeps a read-only snapshot for analytics until retention ends.

**Payload minimisation (changed 2026-10-21, decision D7):** the `rec.CandidateHired` event itself carries **no P3 values** (CCCD/passport number, personal tax code, SI book number, bank account number, dependants' ID numbers and tax codes, health data). The event carries the keys, the non-sensitive groups and a list of which P3 groups are available. People then pulls the P3 groups from the Recruitment hand-off API by `hire_id`, under its own service identity, and stores them encrypted with the person's key. This follows DESIGN.md §9.1 (no P3 in event payloads). The table above lists the **content of the hand-off**, not the event payload.

---

## 8. Reports & analytics

| ID | Report | Description | Audience |
|---|---|---|---|
| R-01 | Headcount plan vs. actual | Budget HC/cost vs actual, open, accepted-not-started, variance; drill org tree | HR Director, dept heads |
| R-02 | Requisition status & ageing | Open requisitions by status, days open, approval cycle time | HR, managers |
| R-03 | Recruitment funnel | Applications → screened → interviewed → offered → hired, conversion % per stage, per opening/source | Recruiter, HR |
| R-04 | Time-to-fill / time-to-hire | Requisition approval → offer accepted; application → accept; time-in-stage | HR |
| R-05 | Source-of-hire & source effectiveness | Applications, hires, quality (passed probation %), cost per hire by channel (TopCV, VietnamWorks, ITviec, LinkedIn, Facebook, referral, career site, agency) | HR, Finance |
| R-06 | Recruitment cost | Cost per hire, cost by category / org unit / period vs budget | HR, Finance |
| R-07 | Offer analytics | Offer acceptance rate, decline reasons, compa-ratio distribution, out-of-band offers, NET vs GROSS share | C&B |
| R-08 | Interviewer activity | Interviews held, scorecard SLA compliance | HR |
| R-09 | Rejection reasons | By stage and opening | HR |
| R-10 | Referral programme | Referrals, hires, bonuses due/paid | HR |
| R-11 | Diversity (anonymised, aggregated) | Gender/age band distribution through funnel, min group size 5 | HR Director |
| R-12 | Privacy compliance | Consents by purpose, expiring consents, DSRs and SLA, deletions executed, legal holds | DPO |
| R-13 | Quality of hire | Probation pass rate and 6/12-month retention of hires by source/recruiter (People data) | HR |

All reports filterable by legal entity, org unit, period; exportable (XLSX/PDF) subject to RBAC; personal data excluded from aggregate reports.

---

## 9. Non-functional requirements

| ID | Requirement | Priority |
|---|---|---|
| NFR-REC-001 | All `rec` data is tenant-isolated (tenant_id + row-level security); feature gating and quotas follow SaaS subscription. | M |
| NFR-REC-002 | Career site pages: p95 load < 2 s on 4G, mobile-first, WCAG 2.1 AA, SEO (schema.org `JobPosting`, sitemap), works without candidate login for browsing/applying. | M |
| NFR-REC-003 | Career site and apply form protected from bots (rate limiting, CAPTCHA) and file uploads virus-scanned; max file 10 MB; PDF/DOC/DOCX/JPG/PNG. | M |
| NFR-REC-004 | Candidate identity documents, health and judicial records encrypted at rest with field/file-level encryption; access only with explicit permission and logged. | M |
| NFR-REC-005 | Hand-off delivery via transactional outbox, at-least-once with idempotency key; end-to-end hand-off to People completes < 1 min in normal operation; failures visible on a retry dashboard (changed 2026-10-16, R2-02 §5 #24). | M |
| NFR-REC-006 | CV parsing: ≥ 90% field accuracy on name/email/phone for VN and EN CVs on a reference set; parse time < 10 s p95; if parser is outside Vietnam, tenant must opt in (cross-border transfer). | S |
| NFR-REC-007 | Job-board and calendar integrations fail gracefully: queued retries with backoff, recruiter notified after final failure; no data loss of applications. | S |
| NFR-REC-008 | Search over 500k candidates per tenant returns in < 2 s p95. | S |
| NFR-REC-009 | Retention job deletes/anonymises within 24 h of expiry including files and search indexes; backups expire per platform backup policy (documented to DPO). | M |
| NFR-REC-010 | UI and candidate communications bilingual (VI/EN); Vietnamese diacritics fully supported in search (accent-insensitive matching). | M |
| NFR-REC-011 | Candidate portal sessions via magic link expire after 30 min idle; links single-tenant, signed, time-limited. | M |

---

## 10. Assumptions, open questions, risks

### 10.1 Assumptions
- A-01 Core owns positions and the authoritative position FTE (seat) budget and supports "budgeted / vacant / filled" status. In MVP positions must exist before requisitions (created in Core by HR). From Phase 2 the approved staffing plan writes FTE budgets and *proposed* positions to Core through a Core API; Recruitment owns only the cost budget (changed 2026-10-16, R2-02 §5 #8).
- A-02 Payroll exposes salary bands per grade and a stateless gross↔net simulation API usable before an employee exists.
- A-03 People owns person master and accepts a hire with a not-yet-started employee status ("Pre-hire" / "Chờ nhận việc").
- A-04 Recruitment is Phase 2, but §7 contracts (C11, C14, C16, C17, P1) are agreed in Phase 0 so MVP modules expose them.
- A-05 Tenant is the data controller; YourHRM is the data processor for candidate data.

### 10.2 Open questions
| ID | Question | Owner |
|---|---|---|
| Q-01 | ~~Which module owns probation rules and regional minimum wage parameters?~~ **Closed 2026-10-16 (R2-02 §5 #1):** the saas-platform Legal Parameter Service (C18). | PM / BA-Payroll / BA-People |
| Q-02 | ~~Bank account: People or Payroll?~~ **Closed 2026-10-16 (R2-02 §5 #5):** People owns employee bank accounts; the hand-off Bank group goes to People. | BA-People / BA-Payroll |
| Q-03 | Which VN job boards offer partner APIs (TopCV, VietnamWorks, ITviec) and on what commercial terms? Facebook Jobs discontinued — limit to page posts/links? | BA-REC / Integration analyst |
| Q-04 | ~~Are new positions created in Core on requisition approval or must they pre-exist?~~ **Closed 2026-10-16 (R2-02 §5 #8):** they must pre-exist in MVP; the Phase 2 staffing plan proposes positions to Core via API. | BA-Core |
| Q-05 | Is the talent pool shared across legal entities of a tenant group (needs group-sharing consent)? | Product owner |
| Q-06 | Exact deadlines for DSR responses and permitted retention under PDPL 2025 and its implementing decree. | Legal consultant |
| Q-07 | ~~Work-permit tracking: People or a separate sub-module?~~ **Closed 2026-10-16 (R2-02 §5 #22):** People owns work permits; Recruitment flags them at hand-off. | BA-People |
| Q-08 | Does the employee calendar (ESS) show interviews, or only email/calendar invites? | BA-People / UX |

### 10.3 Risks
| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| RK-01 | PDPL 2025 implementing rules change consent/retention requirements | Rework, compliance exposure | Parameterised retention, consent notice versioning, legal review before Phase 2 build |
| RK-02 | Job-board APIs unavailable or unstable | Reduced multi-posting value | Manual posting + email-inbox import fallback |
| RK-03 | Hand-off contract mismatch with People (missing fields) | Re-keying, data errors | Freeze §7.3 payload v1 in gap analysis; contract tests |
| RK-04 | NET offer estimates differ from first payslip | Candidate dispute | Store simulation parameter version; Payroll uses agreed NET as target for gross-up |
| RK-05 | Cross-border processing (LinkedIn, foreign CV parser, calendar SaaS) | Need transfer impact assessment | Integration registry, tenant opt-in per connector |
| RK-06 | Duplicate detection false merges | Data corruption | Merge only by human confirmation, reversible within 30 days, audit |
