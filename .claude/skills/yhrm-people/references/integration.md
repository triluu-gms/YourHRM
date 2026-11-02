# People integration contracts (IN-xx consumed, OUT-xx published)

Sources:
- BRD `02-people.md` §7. It uses IN-/OUT- IDs; there are no IF-PPL IDs.
- Canonical event catalogue: `gap-analysis.md` §6, v1.1 + v1.2/v1.3 rows. **Use the catalogue name when it differs from the BRD.** Its rule is `<module>.<Aggregate><PastTenseVerb>`, and variants go into a `subtype` / `status` / `outcome` payload field.

Envelope (gap §6) on every event: `event_id`, `event_type`, `schema_version`, `tenant_id`, `legal_entity_id`, `occurred_at`, `effective_date`, `producer`, `correlation_id`, `causation_id`, `retro`. Add `person_id` / `employment_id` where relevant. Opening-balance import events carry `initial_load=true`, so consumers suppress side effects.

Payload rule (D7, conventions): never put P3 in a payload. For example, `BankAccountChanged` carries no full account number, and Payroll reads it via API (US-PPL-032). Salary amounts are P2 and do travel in `people.SalaryChanged`. That is why P1 delivery jobs carry only a pointer to the event (architecture-overview §7).

## Published (OUT)
| OUT | Canonical event (gap §6) | BRD name if different | Consumers | Phase note |
|---|---|---|---|---|
| OUT-01 | `people.EmployeeHired` (hire_type NEW/REHIRE/CONCURRENT, preview flag) | — | Core, T&A, Payroll, Rec, SaaS | P1. Preview on approval, final on hire date. Core provisions the user from it. |
| OUT-02 | `people.HireCancelled` | — | Core, T&A, Payroll, Rec | P1 |
| OUT-03 | Read API: person/employment/assignment/contract summary/salary summary as of a date and as known | — | all | P1 (US-PPL-081). Salary requires `compensation`. |
| OUT-04 | `people.AssignmentChanged` (carries the reporting line / manager) | — | Core, T&A, Payroll, Rec | P1. Invalidates Core's authz cache. |
| OUT-05 | `people.SecondmentChanged` | `SecondmentStarted/Ended` | Payroll, T&A, Core | P2 (BR-PPL-034) |
| OUT-06 | `people.TemporaryTransferChanged` | `TemporaryTransferStarted/Ended` | Payroll, T&A | P1 (BR-PPL-035) |
| OUT-07 | `people.SalaryChanged` (record id + version, GROSS/NET, agreed net, net scope, base, SI salary, allowances, probation %, retro, decision/appendix no.) | — | Payroll | P1. Payroll freezes the version per run (BR-PAY-072). |
| OUT-08, OUT-11 | `people.ContractChanged` subtype SIGNED/RENEWED/CONVERTED/APPENDIX/SUSPENDED/RESUMED | `ContractSigned/Renewed/Converted/AppendixSigned`, `ContractSuspended/Resumed` | Payroll, T&A, Core | P1. The appendix part is P1 (BR-PPL-055). |
| OUT-09 | `people.ProbationCompleted` (PASS/FAIL) | also `ProbationStarted` | Payroll, T&A, Rec | P1 (BR-PPL-052) |
| OUT-10 | `ContractExpiring` (not in the catalogue) | — | Core notifications, Rec | P1 (BR-PPL-054) |
| OUT-12 | `people.TerminationScheduled` (LWD, termination date, legal ground, notice shortfall, protected flag, `access_revocation_date`) | — | Core, T&A, Payroll, Rec | P1. Starts Payroll's `FinalSettlementProcess` (ADR-0019). |
| OUT-13 | `people.EmployeeTerminated` (+ final-settlement trigger, SI/UI code, severance eligibility, service and paid periods, deductions, assets outstanding) | — | Core, T&A, Payroll, Rec, SaaS | P1. Emitted on termination date / LWD+1. |
| OUT-14 | `people.TerminationCancelled` | — | Core, T&A, Payroll, Rec | P1 |
| OUT-15 | `people.PersonalDataChanged` (minimised changed fields: tax code, SI no., nationality, **tax residency** with valid_from/to and basis, entry/exit dates, address) | — | Payroll, Core, T&A | P1 |
| OUT-16 | `people.BankAccountChanged` | — | Payroll | P1. Needs step-up approval. |
| OUT-17 | `people.DependantChanged` (registered / ended / transferred, claiming employment, from/to month) | — | Payroll | P1 |
| OUT-18 | — removed (08/CK-TNCN and PIT authorisation belong to Payroll). Do not reuse the ID. | | | |
| OUT-19 | `people.SpecialStatusChanged` | — | T&A, Payroll | P1 |
| OUT-20 | Seniority / service data (seniority date, recognised prior service, excluded periods) | — | T&A, Payroll | GAP-056 open |
| OUT-21 | — removed (Core provisions users from OUT-01 / OUT-12). Do not reuse the ID. | | | |
| OUT-22 | Position incumbency / vacancy | — | Core, Rec | P2 (BR-PPL-038) |
| OUT-23 | Internal candidate profile | — | Rec | P2 |
| OUT-24 | Rehire-eligibility lookup by ID number | — | Rec | Rec is P2 |
| OUT-25..27 | `people.RewardApproved` / `people.DisciplineDecided` / `people.AppraisalFinalised` | — | Payroll | P2 |
| OUT-28, OUT-29 | `ExpenseReimbursementApproved`, `AdvanceDeductionRequested` | — | Payroll | P2 |
| OUT-30 | — removed (business trip belongs to T&A) | | | |
| OUT-31 | `TrainingAttendanceRecorded` | — | T&A | P2/P3 |
| OUT-32 | `people.HeadcountSnapshotTaken` (active per LE; month-end = billable, a 2-LE person counts twice, SUSPENDED not billed) | — | SaaS, Rec | P1 (US-PPL-026; D-10, R2-12) |
| OUT-33 | PD inventory, DSAR export package, anonymisation confirmation | — | SaaS | P1 (BR-PPL-153) |
| OUT-34 | Headcount and turnover facts for reporting | — | SaaS reporting store | via `007_publications.sql` |
| OUT-35 | `people.ReorgMappingCompleted` {package_id, moved, failed, fix list} | — | Core | P2 (BR-PPL-039) |
| OUT-36 | `people.PrimaryEmploymentChanged` {person, employment, purpose `PIT_FAMILY_DEDUCTION`, valid_from/to} | — | Payroll (IF-PAY-C-41), T&A (ATT-C-25) | P1 |
| OUT-37 | `people.InsuranceParticipationChanged` {person, employment, insurance_type SI/HI/UI/OA_OD, is_participating, basis_rule, legal_pack_id, valid_from/to} | — | Payroll | P1. The DDL comment still says "proposed". The catalogue v1.3 lists it. |
| — | `people.OnboardingReadinessChanged` (READY/BLOCKED + items: user account, pay group, schedule, signed contract, bank, tax code) | mentioned in IN-29 only | Core, Rec (P2) | catalogue v1.1, ADR-0019 `HireProcess` |
| — | `people.HireHandoffProcessed`, `people.StartDateChanged` | not in BRD §7.2 | Rec (+T&A, Payroll, Core for StartDateChanged) | GAP-019 **open**. Rec handoff is P2. |
| — | `AssignmentChangeCancelled` | P3 exception text only | — | US-PPL-038. Not in the catalogue. |

## Consumed (IN)
| IN | What | From | Use |
|---|---|---|---|
| IN-01 | Legal entity (tax code, SI unit code, min-wage region, legal rep); `core.LegalEntityChanged` | core | Employment, contract, decisions, statutory reports |
| IN-02 | Org tree + cost centres (as-of); `core.OrgUnitChanged`; `core.ReorgActivated` (+ mapping); `core.ReorgPackageStatusChanged` | core | Assignment; reorg mapping (P2) |
| IN-03 | Positions, job catalogue, grades; `core.PositionChanged`, `core.JobCatalogueChanged` | core | Assignment, incumbency, manager resolution |
| IN-04 | Work locations (province, min-wage region); `core.LocationChanged` | core | Assignment, min-wage check |
| IN-05 | Users, roles, permissions, data scopes, field profiles | core | Access control and masking |
| IN-06 | Workflow engine (`core.WorkflowStepCompleted`, `core.WorkflowCompleted`) | core | All approvals |
| IN-07 | Notifications | core | Expiry and deadline alerts |
| IN-08 | Audit service | core | Sensitive read/write log |
| IN-09 | Reference data (admin units 2-level, nationality, ethnicity, banks, relationships, numbering sequences) | core | Validation |
| IN-10 | Legal parameters `getParam(key, scope, asOfDate)`; `saas.LegalParameterPackPublished` | saas | BR-PPL-041/051/071, insurance rule |
| IN-11 | `rec.CandidateHired` (only to People) | rec | Pre-hire + salary record. D7: minimal payload, P3 via Rec API by `hire_id` (P2). |
| IN-12 | `rec.HireAmended` / `rec.HireWithdrawn` | rec | Update or cancel pre-hire |
| — | `rec.InternalTransferAccepted` | rec | GAP-021 open: no People input row yet |
| IN-13..15 | Leave balance at LWD, unpaid/maternity periods (`att.AbsenceChanged`), final attendance closed (`att.LeaverAttendanceClosed`) | att | Final settlement, seniority, protected status |
| IN-16 | `att.BusinessTripApproved` | att | Trip expenses (P2) |
| IN-17 | `pay.CompensationCatalogueChanged` (scales, bands, components) | pay | Salary record validation |
| IN-18 | `pay.SiContributionPosted` (SI history, display only) | pay | SI profile display, book closure |
| IN-19 | `pay.FinalSettlementStatusChanged` (severance result, settlement clock) | pay | Display on separation; People never recomputes |
| IN-20..22 | Income data for letters, SI declaration status, advance deduction status | pay | Letters, obligations, advances |
| IN-23 | Tenant context, entitlements, plan limits (max active employees) | saas | License check before activation |
| IN-24, IN-25 | Document service, e-sign orchestration | core (+saas connectors) | Files; e-sign is P2 |
| IN-26 | VNeID connector | saas | P2 |
| IN-27 | Consent registry `hasConsent()`, `saas.ConsentChanged`, PD inventory, `saas.DataSubjectRequestOpened`, retention scheduler | saas | BR-PPL-151/153/154 |
| IN-28 | Import engine (opening-balance mode) | core | NFR-PPL-007; People owns its import types |
| IN-29 | `core.UserStatusChanged` | core | Employee↔user link; readiness item |
| IN-30 | `pay.PayGroupAssigned`, T&A schedule-assignment status | pay, att | Readiness items only; People stores neither |
| IN-31 | ESS/MSS shell | saas | Hosts People ESS pages |
| IN-32 | 183-day residency proposals | pay (BR-PAY-065) | HR review → `hr.tax_residency` with `source = 'PAYROLL_PROPOSAL'` |

## SPI implemented by People
`vn.yourhrm.shared.auth.api.spi.ReportingLineProvider` (`directReports`, `allReports` per tenant, manager employment and as-of date). Core's `ScopeResolver` uses it for `DIRECT_REPORTS` / `ALL_REPORTS`. Until People provides it, the `NONE` stub resolves to nobody (fail closed, US-CORE-036). Implement it from `hr.assignment.manager_employment_id` (current knowledge, `superseded_at IS NULL`, valid on `asOf`; index `assignment_manager_ix`). Position-based managers are resolved through the incumbent of the manager position (US-PPL-034).
