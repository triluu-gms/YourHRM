---
name: yhrm-recruitment
description: Domain knowledge for the YourHRM Recruitment module (backend package vn.yourhrm.recruitment, DB schema `rec`, requirement prefix BR-REC / NFR-REC). The module owns staffing/headcount plans and their cost budget, job requisitions, job openings and postings (career site, internal job board, VN job boards), candidates and applications (PII, consent references, retention/anonymisation under VN PDPL / Decree 13), pipelines, interviews, scorecards, assessments, offers (GROSS/NET, salary band, probation checks), pre-boarding, referrals and the rec.CandidateHired hand-off to People. The whole module is Phase 2. Use this skill when reading, designing, reviewing or writing code, DDL, events or APIs that touch recruitment, or when another module integrates with it. Trigger terms: recruitment, requisition, job opening, job posting, career site, candidate, applicant, application, pipeline, interview, scorecard, offer, offer letter, pre-boarding, hire hand-off, CandidateHired, referral, talent pool, staffing plan, headcount plan; Vietnamese: tuyển dụng, yêu cầu tuyển dụng, tin tuyển dụng, trang tuyển dụng, ứng viên, hồ sơ ứng tuyển, phỏng vấn, phiếu đánh giá, thư mời nhận việc, offer, thử việc, kế hoạch nhân sự, định biên, giới thiệu ứng viên, bàn giao tuyển dụng.
---

# YourHRM Recruitment (`rec`)

Also load **yhrm-conventions**. It covers tenancy/RLS, PII classes, outbox/inbox, no cross-schema FK, the legal-parameter rule, RBAC annotations and build commands. None of that is repeated here.

> **STOP: THE WHOLE MODULE IS PHASE 2.** In mvp-scope §9.5, 62 BRs and all 11 NFRs are **P2**, BR-REC-012 is **P3**, and **zero** are P1. The BRD's MoSCoW `M` means business priority, not P1. Read §8 below before writing any `rec` code in Phase 1.

## 1. Purpose and scope
Recruitment gives one tenant one place to:
- plan headcount and its cost,
- approve hiring,
- publish vacancies,
- run candidates from first contact to a signed offer,
- hand the hire to People without re-keying.

Hiring must stay inside the approved headcount and the salary bands, and candidate data must be processed lawfully.

**In scope** (BRD §1.2): staffing plans, requisitions with approval, openings and postings (career site, internal board, TopCV/VietnamWorks/ITviec/LinkedIn/Facebook, manual), candidates and applications, CV parsing, duplicate check, talent pool, pipeline and screening, communications, interviews and scorecards, assessments, offers, pre-boarding portal, hire hand-off, referrals, cost and funnel analytics, candidate privacy.

**Out of scope, and who owns it instead** (BRD §1.3, gap-analysis §5):

| Not ours | Owner |
|---|---|
| Org tree, positions and authoritative FTE (seat) budget, job catalogue, grades | `core` (#8) |
| Employee record, contract, onboarding after hire, **work permits** (BR-PPL-020) | `people` (#22) |
| **Agreed compensation / salary record (GROSS/NET, agreed net), employee bank accounts** | `people` (#24, #5) |
| Salary structures and bands, pay component catalogue, gross↔net engine, paying the referral bonus | `payroll` (#7) |
| Legal parameters (minimum wage, probation limits, SI rates) | `saas` Legal Parameter Service (#1) |
| Consent registry, DSR orchestration, retention scheduler | `saas` (#27) |
| Document storage, e-sign orchestration | `core` document service (#15b); `saas` hosts the connectors |
| Holiday calendar | `att` (#2) |
| Background-check vendor operations; paying agency fees and ad spend | External / finance. `rec` records status and cost only. |
| AI ranking or automated rejection | Not built (BR-REC-026) |
| Agency/RPO portal | Phase 3 |

## 2. Ownership
**`rec` is the single owner of:**
- staffing plan versions and the **cost** budget (the approved FTE goes to Core through the Core position API, P7);
- requisitions with their classification and vacancy counters;
- openings and postings;
- pipeline templates;
- candidate profiles and applications;
- stage history;
- interviews, scorecards and assessments;
- offer versions and their simulation stamp;
- pre-boarding items;
- hire hand-off tracking (`hire_id`);
- referrals and programmes;
- recruitment cost.

**`rec` consumes these facts** (details in `references/integration.md`):

| Fact | From | How |
|---|---|---|
| Legal entity, org units, positions, grades, locations + region, RBAC scope, workflow, notifications, audit, numbering, documents | core | Sync API (C1–C10, C24). Events: `core.OrgUnitChanged`, `core.PositionChanged`, `core.LocationChanged`, `core.JobCatalogueChanged`, `core.WorkflowStepCompleted` |
| Actual HC/FTE, employee directory, person-match API (CCCD / email / phone / name + DOB, rehire flag) | people | C11, C13, C14 (sync) |
| Hand-off result, no-show, start-date change | people | `people.HireHandoffProcessed`, `people.HireCancelled`, `people.StartDateChanged` (C15) |
| Lifecycle events for actual HC, replacements and referral qualification | people | `people.EmployeeHired`, `people.TerminationScheduled`/`TerminationCancelled`, `people.AssignmentChanged`, `people.ProbationCompleted`, `people.EmployeeTerminated` (C12) |
| Salary bands, component catalogue | payroll | C16 sync; `pay.CompensationCatalogueChanged` |
| Gross↔net simulation | payroll | C17 sync (IF-PAY-P-15, always-on API) |
| Legal parameters | saas | `getParam(key, scope, asOfDate)` (C18) |
| Consent state, retention triggers, candidate identity, career-site hosting, connectors | saas | `hasConsent()`, `saas.ConsentChanged` (C21–C26) |
| Holidays | att | C27/C28 |

## 3. Domain model (DDL `database/postgresql/ddl/006_rec.sql`)
All tables are in `rec` with PK `(tenant_id, id)`. References outside `rec` are soft.

| Entity | Table | Invariants worth knowing |
|---|---|---|
| Staffing plan version | `rec.staffing_plan` | Unique `(legal_entity, plan_code, plan_version)`. Exactly one `APPROVED` per plan_code. v≥2 needs `previous_version_id` + `change_reason`. `tg_staffing_plan_guard` makes APPROVED/SUPERSEDED/CANCELLED read-only; the only allowed change is APPROVED→SUPERSEDED. Only a DRAFT may be deleted. |
| Plan line | `rec.staffing_plan_line` | `position_id` or `proposed_position_ref` is required. Editable only while the plan is DRAFT/RETURNED. After that, only `core_sync_*` and `remap_status` may change (`tg_staffing_plan_line_guard`). |
| Requisition | `rec.requisition` | `classification` is WITHIN_PLAN / OVER_PLAN / UNPLANNED, and UNPLANNED ⇔ `staffing_plan_line_id IS NULL`. `filled_count + reserved_count <= vacancies`. REPLACEMENT needs `replaced_employment_id`. Out-of-band needs a justification. |
| Pipeline | `rec.pipeline_template`, `rec.pipeline_stage` | A stage belongs to exactly one template **or** one opening. `stage_type` is NEW, SCREENING, ASSESSMENT, INTERVIEW, OFFER, PREBOARDING, HIRED, REJECTED or WITHDRAWN. One default template per tenant. |
| Opening | `rec.job_opening` | Sum of opening vacancies ≤ requisition vacancies (enforced by the **service**, BR-REC-013). `screening_questions` is jsonb. Visibility is PUBLIC / INTERNAL / REFERRAL_ONLY. |
| Posting | `rec.job_posting` | One live posting per opening × channel (+detail). Status is DRAFT, PUBLISHING, PUBLISHED, FAILED, UNPUBLISHED or EXPIRED. |
| Candidate | `rec.candidate` | One per person per tenant. `email/phone/address/id_doc` are `_enc` (**P3**, subject DEK `CANDIDATE`, D8) with `_bidx`. `id_doc` is collected only from the offer stage. Status is ACTIVE, MERGED, CLOSED or ANONYMISED. `candidate_anonymised_ck` requires every `_enc`, `_bidx`, last4 and personal plaintext to be NULL. |
| Consent reference | `rec.candidate_consent_ref` | Purposes: APPLICATION_PROCESSING, TALENT_POOL, GROUP_SHARING, REFERRAL_CONTACT. `cached_status` is a hint only; **call `hasConsent()`** for every processing decision. |
| CV / files | `rec.candidate_file` | The file lives in the Core document service (`document_id`). HEALTH_CERT, JUDICIAL_RECORD and ID_SCAN force `is_sensitive`. `parsed_enc` is P3. |
| Application | `rec.application` | Unique (candidate, opening). REJECTED needs `rejection_reason_code` **and** `decided_by` (a human). WITHDRAWN needs a reason. `knockout_flag` is advisory. Screening answers are jsonb. |
| Stage history | `rec.stage_history` | **Append-only** (`rec.tg_append_only`; DELETE only by tenant purge, D9). No free text. |
| Interview | `rec.interview`, `rec.interview_panel`, `rec.scorecard` | One scorecard per panelist. The panelist must belong to the same interview (composite FK). SUBMITTED needs `overall_recommendation`. `scorecard.comment` is P2 and is nulled on anonymisation. |
| Assessment | `rec.assessment` | Result is PENDING, PASS, FAIL or INCONCLUSIVE. `expires_on` is a date. |
| Offer version | `rec.offer` | At most one live version per application. Once status ≠ DRAFT, terms and simulation stamp are **frozen** (`tg_offer_guard`); renegotiate with `offer_version + 1`. NET requires `agreed_net_amount`. The stamp `sim_legal_pack_id + sim_override_version + simulated_at` is all-or-nothing and **mandatory outside DRAFT/WITHDRAWN**. `expires_on` is an inclusive local date. |
| Pre-boarding | `rec.preboarding_item` | Unique (application, item_type). `value_enc` is P3. Upload scans only. VERIFIED needs `verified_by` + `verified_at`; REJECTED needs a reason. |
| Hand-off | `rec.hire_handoff` | `id` = **hire_id** = idempotency key. `payload_version` is 1. At most one live (PENDING/ACCEPTED) hand-off per application and per (requisition, candidate). ACCEPTED needs `person_id` + `employment_id`. When `is_work_permit_required` is set, sending needs a permit status or `hr_override_reason`. |
| Referral | `rec.referral_program`, `rec.referral` | Programme validity is `[valid_from, valid_to)`. One live referral per (candidate, opening). BONUS_INSTRUCTED needs `bonus_amount` + `payroll_instruction_id`. |
| Masking views | `rec.v_candidate_masked`, `rec.v_offer_masked` | Offer amounts fail closed when `app.mask_level` is unset. |
| Outbox/inbox | `rec.outbox_event`, inbox via `sys.create_outbox_inbox('rec', …)` | — |

**Effective dating:** the module is mostly status/version based, not Pattern A/B.
- Plans and offers are **immutable versions**.
- Referral programmes use a `valid_from/valid_to` (exclusive) window.
- Salary amounts are P2 plaintext (X-01).

## 4. Key processes (BRD §3)
- **P-REC-01 Plan:**
  1. Open a period and pre-fill actual HC/FTE from People.
  2. Propose HC and cost (band midpoint × FTE + on-cost).
  3. Approve through Core workflow (`REC_PLAN`). The approved version is read-only; a revision is a new version.
  4. Show budget vs actual, open, accepted-not-started and variance.
  5. Flag lines whose org unit or position has been retired.
- **P-REC-02 Requisition:**
  1. Create it inside your data scope.
  2. Check availability = budget HC − actual − open requisitions − accepted offers, and classify.
  3. Route the approval chain by classification, grade and band (`REC_REQUISITION`).
  4. Once APPROVED, it can spawn openings.
- **P-REC-03/04 Opening, posting and intake:**
  1. Create the opening from the approved requisition.
  2. Warn on discriminatory content.
  3. Publish per channel (each posting has its own status).
  4. Applications arrive tagged with their source.
  5. Capture consent.
  6. Parse the CV; the recruiter confirms the fields.
  7. Run the duplicate check against candidates **and current/former employees** (C14).
  8. Evaluate knock-out questions as **flags only**.
- **P-REC-05/06/07 Pipeline:**
  - Moving a stage writes `stage_history`. Reject/withdraw needs a reason code.
  - Schedule interviews (calendar free/busy, self-scheduling). Panelists submit scorecards independently. Assessments are optional.
- **P-REC-08 Offer:**
  1. Draft it.
  2. Validate: band (compa-ratio), minimum wage by location region, probation days and % by job level, and that the vacancy is still free.
  3. Run the Payroll simulation and stamp it.
  4. Approve through Core workflow (`REC_OFFER`).
  5. Generate the letter and send it with an expiry date.
  6. The candidate accepts, declines or requests a change (which creates a new version).
  - Outcomes: Expired; Reneged (reopens the vacancy).
- **P-REC-09 Pre-boarding:**
  1. On acceptance the portal checklist opens (CCCD, MST, SI number, bank, dependants, documents).
  2. HR verifies each item.
  3. When every mandatory item is verified, the application is READY_TO_HIRE.
- **P-REC-10 Hire:**
  1. Insert `hire_handoff` and write `rec.CandidateHired` to the outbox **in the same transaction**.
  2. People creates the person, employment, contract draft and **salary record**.
  3. People replies with `people.HireHandoffProcessed`.
  4. The application becomes HIRED and the requisition counters update.
  - Failure: People rejects → `FAILED` with a reason; the recruiter resolves it.
  - Changes after hand-off: `rec.HireAmended` / `rec.HireWithdrawn`.
  - People sends `HireCancelled` (no-show) → reopen the vacancy.
- **P-REC-11 Referral:**
  1. An employee refers someone via ESS.
  2. The referred person gets a consent request.
  3. The referrer sees the stage only.
  4. When the qualifying condition is met (People events), send a bonus instruction to Payroll.
- **P-REC-12 Privacy:**
  1. Take consent per purpose before processing.
  2. Candidates use DSR self-service.
  3. The saas retention scheduler triggers the daily `rec` anonymise handler.
  4. Hired candidates' data moves to People under the employment purpose.

## 5. Invariants you must never break
1. **No fully automated rejection or ranking** (BR-REC-026). Scores and knock-outs are advisory, and a human records `decided_by` (BR-REC-028/029).
2. **Consent before processing** (BR-REC-059):
   - Beyond minimal identifiers, process candidate data only with a valid consent or a recorded legal basis.
   - Ask the saas registry through `hasConsent()`. Never trust `cached_status`.
   - Talent-pool use needs talent-pool consent (BR-REC-024).
3. **Data minimisation** (BR-REC-021): collect CCCD/passport only from the offer stage. Gender is optional. The pre-boarding checklist has no "deposit" item and takes scans only, never originals (C-02).
4. **Hand-off goes to People only** (BR-REC-050, R2-02 #24):
   - It is idempotent by `hire_id` and delivered via the outbox (NFR-REC-005).
   - Never send salary to Payroll. Payroll learns it from `people.SalaryChanged`.
5. **No P3 in the `rec.CandidateHired` payload** (decision **D7**):
   - The payload carries keys, non-P3 groups and a list of the P3 groups available.
   - People **pulls** P3 (ID, MST, SI number, bank account, dependants' IDs, health, contact data) from the Recruitment hand-off API by `hire_id`, under its own service identity.
   - The outbox, bus and inbox never hold P3.
6. **Never hire twice:**
   - Never hire the same candidate twice for one requisition.
   - Block the hire if People reports an active employment of the same person in the same legal entity, unless tenant policy allows it (BR-REC-051).
7. **Budget and band:**
   - A requisition range outside the band needs a justification and an extra approval step (BR-REC-010). The same holds for offers (BR-REC-041).
   - Openings never exceed approved vacancies (BR-REC-013).
   - Acceptance reserves the vacancy (BR-REC-047).
8. **Offer legality** (BR-REC-043), with every limit read from the Legal Parameter Service at the start date:
   - salary ≥ regional minimum wage of the work location;
   - probation days ≤ the limit for the job level;
   - probation % ≥ the minimum;
   - no probation below the contract-length threshold.
9. **Offer simulation is version-stamped** (BR-REC-042). Payroll is the only calculator; never compute PIT or SI in `rec`.
10. **Versioning:**
    - Approved plans are read-only (BR-REC-004).
    - Sent offers are frozen (BR-REC-046).
    - `stage_history` is append-only (BR-REC-031).
11. **Access:**
    - Hiring managers and interviewers see only openings they are assigned to.
    - Salary expectations and offer data need the `compensation` permission (BR-REC-032).
    - Requisitions are limited to the creator's Core data scope (BR-REC-011).
    - Every view, export or download of a CV or ID document goes to the Core audit log (BR-REC-063).
12. **Retention:**
    - Anonymise within 24 h of expiry, including files and search indexes (NFR-REC-009).
    - Destroy the candidate DEK **and** null the `_bidx` columns.
    - Legal hold suspends deletion (BR-REC-062).
13. **Sensitive files:** health certificates, judicial records and ID scans are always sensitive. They are encrypted and need explicit permission (NFR-REC-004, C-10). The judicial-record item is off by default and needs a legal-basis note to enable (C-13).

## 6. VN compliance (BRD §5): all values are parameters, all `[verify-legal]`
| # | Rule | Implement as |
|---|---|---|
| C-01 | Non-discrimination (Labour Code 2019 Art. 3, 8; Employment Law 2025) | Configurable keyword warning (BR-REC-018). Gender optional. No auto-reject. |
| C-02 | No deposits; no retaining original ID papers or diplomas (Art. 17) | Scan-only checklist |
| C-03 | Information duty before the contract (Art. 16) | Mandatory offer-letter fields |
| C-04 | Probation max by job level. BRD example values: 180 / 60 / 30 / 6 working days; none for contracts < 1 month (Art. 24–25) | Legal param by job level. **Examples only.** |
| C-05 | Probation salary ≥ a minimum %. BRD example: 85% (Art. 26) | Legal param |
| C-06 | Salary ≥ regional minimum wage, Region I–IV by work location | Legal param via C18. **The BRD cites Decree 74/2024 values; see Known conflicts.** |
| C-07 | Minimum working age 15 (Art. 143–145) | Warn if under 18 at start (`offer.is_minor_at_start`). Block if under 15 unless tenant policy and the legal conditions are recorded. |
| C-08 | Foreign workers need a work permit or exemption (Art. 151–154; decree citation unsettled) | `is_work_permit_required` flag in the hand-off (BR-REC-054). People owns the permit. |
| C-09 | PDPL 2025 (91/2025/QH15, effective 2026-01-01) / Decree 13/2023: consent per purpose, data-subject rights, delete when the purpose ends | BR-REC-059..063 |
| C-10 | Sensitive personal data (health, criminal record, ethnicity, religion…) | Sensitive class, encrypted, restricted access, shorter retention |
| C-11 | DPIA; cross-border transfer assessment (LinkedIn, foreign CV parser) | Integration registry flags it; tenant opts in per connector (NFR-REC-006, RK-05) |
| C-12 | Breach notification within 72 h (A05) | Platform process; `rec` provides the list of affected candidates (P15) |
| C-13 | Judicial record only where law or the job allows | Checklist item off by default |
| C-14 | Labour-usage reporting | Owned by People; `rec` supplies hiring numbers (P14) |

Retention defaults (BR-REC-061: unsuccessful applicants 12 months, talent pool ≤ 24 months) and DSR deadlines (Q-06) are **unconfirmed**.

## 7. Events and interfaces (summary; full list in `references/integration.md`)
**Published** (write to `rec.outbox_event` in the business transaction):

| Event | To | When |
|---|---|---|
| `rec.CandidateHired` (payload v1, frozen since Phase 0; REC-P1) | people only | Hire |
| `rec.InternalTransferAccepted` (REC-P2; BR-REC-053) | people | Hire of an internal candidate |
| `rec.HireAmended` / `rec.HireWithdrawn` (REC-P3) | people | After hand-off, before start |
| `rec.RequisitionStatusChanged` (REC-P8; see Known conflicts) | core | Approved / cancelled / filled |

Interface IDs `REC-Pn` / `REC-Cn` are BRD §7 row numbers (gap-analysis prefix). They are not phases.

Other provided interfaces:
- referral bonus instruction (REC-P9) → payroll;
- open vacancies (REC-P6);
- staffing plan FTE / proposed positions → Core position API (REC-P7);
- workflow types `REC_PLAN`, `REC_REQUISITION`, `REC_OFFER` (REC-P11);
- notification templates (REC-P12);
- **hand-off P3 pull API by `hire_id`** (D7).

**Consumed:**
- People: `people.HireHandoffProcessed{hire_id, status ACCEPTED/REJECTED, employee_id, reason}`, `HireCancelled`, `StartDateChanged`, `EmployeeHired`, `TerminationScheduled`/`TerminationCancelled`, `AssignmentChanged`, `ProbationCompleted`, `EmployeeTerminated`, `OnboardingReadinessChanged`, `HeadcountSnapshotTaken`.
- Core: `core.OrgUnitChanged`, `PositionChanged`, `LocationChanged`, `LegalEntityChanged`, `JobCatalogueChanged`, `WorkflowStepCompleted`, `ReorgActivated`.
- Others: `pay.CompensationCatalogueChanged`, `saas.ConsentChanged`, `saas.LegalParameterPackPublished`, `att.HolidayCalendarChanged`.

Module dependency direction (ADR-0001): `recruitment → payroll → time-attendance → people → core → saas-platform`. `rec` may call those modules' `api` packages synchronously. They talk back to `rec` only via events.

## 8. Phase boundary (mvp-scope §9.5, authoritative)
| Phase | What |
|---|---|
| **P1** | **Nothing in `rec`.** DESIGN §7.6: "nothing in Phase 1 writes to it". `006_rec.sql` exists only so the schema can be reviewed and loaded. In P1, hires are entered directly in People (direct-hire wizard US-PPL-018). |
| P1 (other modules' obligations) | The interfaces `rec` will need are frozen in Phase 0 so the P1 modules expose them: C11 (People HC snapshot), C14 (People match API), C16 (Payroll bands), C17 (Payroll gross↔net simulator, IF-PAY-P-15, always-on API), and the `CandidateHired` payload v1 contract (A-04, BR-REC-050). Changing these is a contract change. |
| **P2** | BR-REC-001..011, 013..063 and NFR-REC-001..011. This covers the whole module, including the career site and job-board connectors (R2-01 moved all online integrations to P2) and the retention deletion jobs. |
| **P3** | BR-REC-012 (suggest cancelling a replacement requisition when the separation is cancelled). Also the agency/RPO portal (BRD §1.3). |

There is **no recruitment backlog**: `docs/01-requirements/user-stories/` covers the 260 P1 BRs only (backlog-summary). If you are asked to implement a `rec` feature in Phase 1, flag it to the PM rather than building it.

## 9. Open items affecting implementation
- **Q-03:** which VN job boards have partner APIs; Facebook is limited to page posts and links (RK-02 fallback: manual posting + email-inbox import).
- **Q-05:** is the talent pool shared across a tenant group's legal entities (needs GROUP_SHARING consent)?
- **Q-06:** PDPL DSR deadlines and retention limits (legal consultant).
- **Q-08:** do ESS calendars show interviews? Gap-analysis suggests the Core task inbox.
- **Open gaps:**
  - GAP-019: People must add `HireHandoffProcessed` / `StartDateChanged`.
  - GAP-020: event-name mapping.
  - GAP-021: no People consumer for `InternalTransferAccepted`; People should treat it as a pre-filled job-change request.
  - GAP-051: no Payroll consumer for the referral bonus or cost, and no `ReferralBonusPaid` producer; proposed: Payroll variable input.
  - GAP-052: SaaS lacks career-site hosting / custom domain (BR-SAAS-030 is C), candidate magic-link identity, and calendar / CV-parser connectors.
  - GAP-061: legal citations.
  - GAP-063: workflow "Returned" arrives as `core.WorkflowStepCompleted` action RETURNED; completion arrives as `core.WorkflowCompleted`.
- **D8:** candidate email/phone/address are P3 (accepted). **DPO confirmation is still pending.**
- **Risks:**
  - RK-01: PDPL implementing rules may change.
  - RK-03: hand-off contract mismatch; mitigate with contract tests.
  - RK-04: NET estimate ≠ first payslip; mitigate by storing the simulation stamp, and Payroll targets the agreed net.
  - RK-06: false merges; merge only on human confirmation, reversible for 30 days, audited.

## 10. Known conflicts in the sources (do not resolve silently)
1. **Hand-off content vs event payload.**
   - BRD §7.3 lists CCCD, MST, SI number and bank account as hand-off content, with the Bank group going to People.
   - The gap-analysis §6 catalogue says "payload v1 … no Bank".
   - D7 (decision-log §10, DDL §13) is decisive: **no P3 in the event at all**. People pulls P3 by `hire_id`.
2. **Requisition event name.**
   - BRD P8: `RequisitionApproved/Cancelled/Filled`.
   - DDL outbox comment: `rec.RequisitionApproved`.
   - Gap-analysis §6 canonical: `rec.RequisitionStatusChanged` with a status field.
   - Prefer the catalogue (producer-name rule, variants as payload fields) and confirm with the architect.
3. **Hand-off result.** BRD C15 names `HireAccepted` / `HireRejected`. The catalogue and DDL use `people.HireHandoffProcessed`, but GAP-019 is still **Open**, so People may not publish it yet.
4. **People lifecycle names.** BRD C12 uses Rec-local names (`SeparationScheduled`, `ProbationPassed`, `EmployeeStarted`…). The canonical People events are in §7 above (GAP-020 open).
5. **BRD §6 entities missing from the DDL:**
   - `talent_pool` / `talent_pool_member` and `recruitment_cost` have no table, so BR-REC-024 and BR-REC-058 need DDL work. `job_posting.cost_amount` covers only posting cost.
   - `screening_answer` is `application.screening_answers` (jsonb).
   - `interview_panelist` is `rec.interview_panel`.
   - `consent_record` is `rec.candidate_consent_ref`.
   - `sim_param_version` is `sim_legal_pack_id + sim_override_version`.
   - `offer.expires_at` is `expires_on`.
   - Employee references use `*_employment_id`.
6. **Minimum wage.** BRD C-06 cites Decree 74/2024 and says "Parameter owned by Payroll". GAP-061 says Decree 293/2025 applies from 2026-01-01, and the owner is the saas Legal Parameter Service (C18). Use C18 and no hard-coded values.
7. **Work permit citation.** People cites Decree 219/2025; Rec cites 152/2020 as amended by 70/2023 (GAP-042/061).
8. **Referral bonus (P9) and `pay.ReferralBonusPaid`** (DDL comment) have no agreed counterpart in Payroll yet (GAP-051).

## 11. Where to look
- **BRD:** `docs/01-requirements/brd/modules/05-recruitment.md`
  - §3 processes, §4 BRs, §5 compliance, §6 entities, §7 interfaces (§7.3 hand-off), §9 NFR, §10 open items
- **Phase:** `docs/01-requirements/brd/mvp-scope.md` §9.5 (also §3.1/§3.2 counts, §11.2 integrations → P2)
- **Ownership and events:** `docs/01-requirements/brd/gap-analysis.md`
  - §5 rows #1, #2, #5, #7, #8, #15b, #22, #24, #27
  - §6 event catalogue
  - GAP-011/017/018/019/020/021/033/042/047/051/052/061/063/068
- **Decisions:** `docs/00-project-management/decision-log.md` (R2-01, R2-02, R2-19 and R3-11 in §7–§8; D7, D8 and D9 in §10)
- **DDL:**
  - `database/postgresql/ddl/006_rec.sql`
  - `007_publications.sql` (`pub_rpt_rec`: funnel only, no candidate PII)
  - DESIGN.md §7.6 (rec), §9.1 (no P3 in payloads)
- **ADRs:**
  - 0001 module dependency order
  - 0013 career site is an SSR app (P2)
  - 0016 integration hub (job boards, calendar, CV parser are P2 connectors)
  - 0019 hire saga on the People side
  - 0022 no cross-schema FK
  - 0024 effective dating
  - 0026 candidate subject DEK and crypto-shred
  - 0028 outbox/inbox
- **Backend:** `backend/src/modules/recruitment/` (empty placeholder). Package `vn.yourhrm.recruitment` (`api` + `internal.*`); DB role `yhrm_app_rec`.
- **Per-BR detail:** read `references/business-rules.md` when implementing a specific BR.
- **Interface details:** read `references/integration.md` for every C/P interface and the hand-off groups.
