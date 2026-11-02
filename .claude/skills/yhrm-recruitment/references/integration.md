# Recruitment: interfaces and hand-off

Sources:
- BRD 05 §7 (REC-Cn / REC-Pn)
- gap-analysis §6 (canonical event catalogue v1.1)
- decision-log §10 D7
- DDL `006_rec.sql` §13

The whole module and every interface below is **Phase 2**. Only the contracts that P1 modules must already expose are frozen in Phase 0: C11, C14, C16, C17 and P1 payload v1 (BRD A-04).

Use synchronous APIs for look-ups and validation at the moment of use. Use outbox events for state changes. `rec` never writes another schema.

## Consumes (REC-C)
| # | What | From | Canonical event / API | BR |
|---|---|---|---|---|
| C1 | Legal entities (tax code, address, region) | core | API; `core.LegalEntityChanged` | 001, 040 |
| C2 | Org units | core | API; `core.OrgUnitChanged` (BRD alias `OrgUnitRetired`), `core.ReorgActivated` | 001, 006 |
| C3 | Positions, job catalogue | core | API; `core.PositionChanged` (alias `PositionRetired`), `core.JobCatalogueChanged` | 001, 007, 043 |
| C4 | Grades / job levels | core | API | 010, 043 |
| C5 | Work locations + labour region (Vùng I–IV) | core | API; `core.LocationChanged` | 043 |
| C6 | Users, roles, data scopes, `compensation` permission | core | every request | 011, 032 |
| C7 | Workflow engine | core | start instance; callbacks `core.WorkflowStepCompleted` (action RETURNED) / `core.WorkflowCompleted` (GAP-063) | 004, 009, 044 |
| C8 | Notifications (email; SMS/Zalo are P2 integrations) | core | API | 030, 035 |
| C9 | Audit log | core | API | 063 |
| C10 | Numbering, tenant settings, PDF template engine | core | API | 045 |
| C11 | Actual HC/FTE snapshot per org unit × position | people | API (Phase 0 contract); `people.HeadcountSnapshotTaken` | 002, 005 |
| C12 | Lifecycle events | people | `people.EmployeeHired` (alias `EmployeeStarted`), `people.EmployeeTerminated`, `people.TerminationScheduled`/`TerminationCancelled` (alias `Separation*`), `people.AssignmentChanged` (alias `EmployeeTransferred`), `people.ProbationCompleted` PASS/FAIL (alias `ProbationPassed/Failed`) | 005, 012, 057 |
| C13 | Employee directory | people | API | 007, 039, 055 |
| C14 | Person/employee match (CCCD/passport, email, phone, name + DOB → current/former, rehire flag) | people | API (Phase 0 contract) | 023, 051 |
| C15 | Hand-off result | people | `people.HireHandoffProcessed{hire_id, status ACCEPTED/REJECTED, employee_id, reason}` (BRD alias `HireAccepted/HireRejected`; GAP-019 open), `people.HireCancelled`, `people.StartDateChanged`; also `people.OnboardingReadinessChanged` (P2) | 050, 052 |
| C16 | Salary bands per grade, pay component catalogue | payroll | API (Phase 0); `pay.CompensationCatalogueChanged` | 003, 010, 040, 041 |
| C17 | Gross↔net simulation (stateless, pre-employee) | payroll | IF-PAY-P-15, always-on API (Phase 0) | 042 |
| C18 | Legal parameters: min wage by region, probation limits and %, employer on-cost rates | saas | `getParam(key, scope, asOfDate)`; `saas.LegalParameterPackPublished` | 003, 043 |
| C19 | **Removed** (R2-02 #24): Payroll sends no `SalaryRecordCreated/Rejected` | — | — | — |
| C20 | `ReferralBonusPaid` | payroll | not yet provided (GAP-051) | 057, 058 |
| C21 | Tenant context, entitlement, quotas | saas | every request | NFR-001 |
| C22 | Career-site hosting, domain, TLS, branding | saas | gap GAP-052 open | 014 |
| C23 | Connector framework + vault (job boards, calendar, video, e-sign, SMS/Zalo, CV parser) | saas | integration hub (ADR-0016) | 016, 022, 033, 045 |
| C24 | Document service (upload, virus scan, signed URL, retention class) | core | API | 022, 048 |
| C25 | Candidate external identity (magic-link portal) | saas | GAP-052 open | 048, 060 |
| C26 | Privacy framework: consent registry, DSR, retention scheduler, processor registry, breach | saas | `hasConsent()`, `saas.ConsentChanged`, retention callbacks | 059–062 |
| C27 | Holidays (and optionally leave) for slot proposal | att | API; `att.HolidayCalendarChanged` | 033 |
| C28 | Working calendar for start-date warning | att | API (ATT-P-16) | 040 |

## Provides (REC-P)
| # | What | To | Event / API | BR |
|---|---|---|---|---|
| P1 | Hand-off package | people **only** | `rec.CandidateHired` (payload v1) + P3 pull API by `hire_id` (D7) | 050 |
| P2 | Internal transfer | people | `rec.InternalTransferAccepted` (People consumer missing, GAP-021) | 053 |
| P3 | Amend / renege after hand-off | people | `rec.HireAmended`, `rec.HireWithdrawn` | 046, 052 |
| P4 | Pre-boarding documents (Core document refs + verification status) | people | with P1 | 048, 049 |
| P5 | Consent references, re-purposed for employment | saas registry, people | with P1 | 059 |
| P6 | Open vacancies / accepted-not-started | core | API (gap-analysis suggests dropping People) | 005, 047 |
| P7 | Approved FTE budgets + proposed positions | core position API | sync; `staffing_plan_line.core_sync_status` | 004 |
| P8 | Requisition status | core | `rec.RequisitionStatusChanged` (BRD: `RequisitionApproved/Cancelled/Filled`) | 009, 019 |
| P9 | Referral bonus instruction (employee, VND amount, component code, period) | payroll | no consumer yet (GAP-051) | 057 |
| P10 | Recruitment cost actuals | payroll / finance export | no consumer yet | 058 |
| P11 | Workflow types `REC_PLAN`, `REC_REQUISITION`, `REC_OFFER` | core | install | 009, 044 |
| P12 | Notification event types + bilingual templates | core | install | 030 |
| P13 | Interview tasks / scorecards due | core task inbox (Q-08) | — | 036, 037 |
| P14 | Hiring statistics for labour reporting | people | gap-analysis: drop | C-14 |
| P15 | Candidate data inventory, DSR log, deletion log, breach list | saas | API | 060–063 |
| P16 | Usage metrics for billing/quotas | saas | daily | NFR-001 |

## `rec.CandidateHired` hand-off (BRD §7.3 + D7)
These are the groups of hand-off **content**, not the event payload.

| Group | Fields (short) | In event? |
|---|---|---|
| Keys | hire_id, tenant_id, application_id, candidate_id, offer_id + version, requisition_id, matched person/employee id | yes |
| Person | name, DOB, gender, nationality, marital status, place of birth, photo; ethnicity/religion only if collected | non-P3 parts yes. **CCCD/passport, contacts (P3 for candidates, D8), ethnicity/religion: pull API.** |
| Statutory | MST, SI book no., HI hospital, PIT dependants, foreign-worker flag + work-permit status | flags yes; **numbers and dependants' IDs: pull API** |
| Bank | bank, branch, account no., holder | **pull API**; People owns bank accounts (#5) |
| Assignment | entity, org unit, position, job, grade, level, location + region, manager, employment type, FTE, start date, contract type/term, probation days/end | yes |
| Compensation | GROSS/NET basis, base salary, probation % and amount, allowances, bonus terms, effective date, currency, out-of-band + approval ref, simulation stamp, agreed NET | yes (P2). People creates the salary record; Payroll gets `people.SalaryChanged`. |
| Documents | Core document refs (signed offer, CV, degrees, health cert, ID scans, photos, judicial record) + verification status | refs only |
| Recruitment context | source, referral + referrer, recruiter, hire date, time-to-hire, consent refs | yes |

Implementation rules:
- Write the `hire_handoff` row and the outbox row in one transaction.
- Re-sends reuse `hire_id`. `payload_sha256` and `amend_seq` track what was sent.
- The P3 pull API authenticates People's service identity. It reads `rec.candidate *_enc` and `rec.preboarding_item.value_enc`; People re-encrypts the values under the **person** DEK.
- After hire, `rec` keeps a read-only snapshot for analytics until retention ends. The candidate record is then closed.
