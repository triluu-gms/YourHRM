# Payroll interfaces and events

Sources:
- BRD `04-payroll.md` §7: consumed interfaces IF-PAY-C-01..41 and provided interfaces IF-PAY-P-01..21.
- gap-analysis §6: the canonical event catalogue, v1.1–1.3.
- gap-analysis §7: the close-hold sequence.

Timing codes:
- **RT**: on change.
- **P-cut**: read at snapshot.
- **P-close**: after T&A `CLOSED` publishes a versioned result.
- **On-demand**: a synchronous call.

Every consumed fact must be effective-dated, because payroll recalculates past periods for retro.

Events use the gap §6 envelope: `event_id`, `event_type`, `schema_version`, `tenant_id`, `legal_entity_id`, `occurred_at`, `effective_date`, `retro` and `correlation_id`/`causation_id`. Opening-balance loads set `initial_load=true`; consumers skip side effects and retro detection for those events. P3 values never travel in events.

## Consumed
| IF | What | From | Timing | Used for |
|---|---|---|---|---|
| C-01 | Legal entity: tax code, SI unit code, tax authority, PIT filing frequency per tax year, TNLĐ-BNN rate decision, payer bank accounts (`core.LegalEntityChanged`) | core | RT, read at P-cut | Declarations, bank files, rates (BR-PAY-045/056) |
| C-02 | Workplace location → admin unit → minimum-wage **region** I–IV, effective-dated (`core.LocationChanged`) | core | RT | Minimum wage, UI cap, SI floor |
| C-03 | Org units, cost centres, projects, GL segments | core | RT | GL, cost allocation |
| C-04 | Workflow engine | core | On-demand | Run approval, unlock, loans, overrides |
| C-05 | Users, roles, data scope, audit, notifications | core | RT | BR-PAY-120/121 |
| C-06 | Holiday calendar (`att.HolidayCalendarChanged`, ATT-P-16). Without T&A, the national template from the legal pack is used. | T&A | yearly + RT | Std-day method (b), holiday pay |
| C-07 | Employee master (ID, name, DOB, nationality, ID/tax code, e-mail, status, dates) | people | RT, read at P-cut | Population, payslip e-mail. **Never** use it as the payslip password. |
| C-08 | `people.TerminationScheduled` / `people.EmployeeTerminated`: last working day, reason, notice, severance eligibility, service periods, training refund, cash advances, damage balance | people | RT | Final settlement, severance (BR-PAY-077/078), D02-LT decrease |
| C-09 | Assignments: legal entity, org, grade, worker category, location, default cost split, concurrent assignments (`people.AssignmentChanged`) | people | RT | Structure, pay-group rule, region, cost |
| C-10 | Contracts: type, dates, probation %, working-time type (`people.ContractChanged`) | people | RT | SI eligibility, PIT method, proration |
| C-11 | Salary record version: GROSS/NET basis, agreed net, base salary, allowances by component code, SI salary (`people.SalaryChanged`) | people | RT. A past effective date triggers retro. | Earnings, gross-up, SI basis. **Frozen per run.** Stored in `pay.ext_hr_salary` (bi-temporal). |
| C-12 | Dependants (`people.DependantChanged`) | people | RT | Dependant deduction, annex 05-3 → `pay.ext_hr_dependant_count` |
| C-13 | Tax residency, arrival/departure dates, tax code (`people.PersonalDataChanged`) | people | RT | PIT method, 183-day check |
| C-14 | Bank accounts (`people.BankAccountChanged`) | people | RT, at P-cut and at file time | Bank file → `pay.ext_hr_bank_split`. The number is fetched through the API. |
| C-15 | SI registration and participation per type × employment | people | RT | SI eligibility, D02-LT, TK1-TS. In the DDL this is `people.InsuranceParticipationChanged` → `pay.ext_hr_insurance_participation`. |
| C-16 | Trade-union member flag | people | RT | Đoàn phí |
| C-17 | Approved expense claims | people | daily | BR-PAY-094 (P2) |
| C-18 | Discipline and damage decisions, court-ordered deductions (`people.DisciplineDecided`) | people | RT | BR-PAY-092/093 |
| C-19 | Work schedule (scheduled days and hours) | T&A | P-close | Std-day method (b), hourly rate |
| C-20 | **Attendance Result of a closed period** (versioned): paid, unpaid, absence, suspension and holiday days, plus late minutes (`att.AttendancePeriodClosed(version)`). The T&A close deadline is at least 2 working days before payroll approval. | T&A | P-close | Proration, SI 14-day rule → `pay.ext_att_result` |
| C-21 | OT hours by type, including night OT and night hours. Only approved OT; hours converted to time off are not paid. | T&A | P-close | BR-PAY-025/026 |
| C-22 | SI-paid leave days (sick, maternity, etc.) | T&A | P-close | Exclusions, SI exemption |
| C-23 | Shift, hazard and meal counts | T&A | P-close | Allowances |
| C-24 | Leave balance to pay out | T&A | at termination and year end | BR-PAY-077 |
| C-25 | **Retro deltas** after lock (`att.RetroDeltaCreated`), fed into the next OPEN result or an off-cycle result. The T&A delta is authoritative (Q7). | T&A | RT | BR-PAY-076 |
| C-26 | Project hours | T&A | P-close | BR-PAY-114 (P2) |
| C-27 | Period events: `att.AttendancePeriodClosed`, `att.AttendancePeriodReopened`, `att.AttendancePeriodHoldGranted`, `att.AttendancePeriodHoldReleased`, `att.AttendancePeriodLocked` | T&A | RT | Stale detection, hold and lock confirmation, reconciliation |
| C-28 | Offer salary (Recruitment → People only) | — | n/a | Not consumed directly |
| C-29 | LPS `getParam(key, scope, asOfDate)`, `saas.LegalParameterPackPublished` | saas | sync during calculation | BR-PAY-010. The pack version is snapshotted per run. |
| C-30 | Subscription entitlements | saas | RT | Licence enforcement |
| C-31 | Notifications and documents/PDF (core), e-signature/HSM and tenant keys (saas) | core, saas | On-demand | Payslips, certificates |
| C-32 | Job scheduler (pool `payroll`) | saas | On-demand | NFR-PAY-001 |
| C-33 | FX rates | core | daily | BR-PAY-013 (P2) |
| C-34 | **Close-hold API** (ATT-P-17): `acquireHold(period, version, runId)`, `releaseHold(period, runId, reason)`, `getHold(period)`. Synchronous and idempotent on run id + version. | T&A | at submit (acquire); at reject, withdraw, cancel, reopen or unlock (release) | BR-PAY-082/083 |
| C-35 | `people.RewardApproved` | people | RT, applied at P-cut | Reward input (P2) |
| C-36 | `people.ContractChanged(SUSPENDED/RESUMED)` | people | RT | Stop and resume pay and SI, D02-LT (P1) |
| C-37 | `people.TemporaryTransferChanged` | people | RT | Protected-pay component (P1) |
| C-38 | `people.SecondmentChanged` | people | RT | Paying legal entity and pay group (P2) |
| C-39 | `people.AppraisalFinalised` / KPI scores | people | cycle close | KPI and bonus formula input (S, P2) |
| C-40 | `people.AdvanceDeductionRequested` | people | overdue or at termination | Deduction order (P2) |
| C-41 | `people.PrimaryEmploymentChanged` (purpose `PIT_FAMILY_DEDUCTION`) | people | RT | Family deduction. A change in a closed period triggers retro. |

Other events Payroll consumes (gap §6):
- `core.OrgUnitChanged`
- `core.ReorgPackageStatusChanged`, after which Payroll re-evaluates pay-group and cost rules.
- `core.JobCatalogueChanged`
- `people.EmployeeHired`, which runs the pay-group rule.
- `people.HireCancelled`
- `people.StartDateChanged`
- `people.ProbationCompleted`
- `people.TerminationCancelled`
- `people.SpecialStatusChanged`
- `att.AbsenceChanged`
- `att.LeaverAttendanceClosed`

## Provided
| IF | What | To | Event / timing |
|---|---|---|---|
| P-01 | Component catalogue: the codes People salary records must use | people, recruitment | `pay.CompensationCatalogueChanged`, RT |
| P-02 | Pay scales (thang bảng lương), grade bands, structures, default allowances. **Always on** (R2-18). | people, recruitment | `pay.CompensationCatalogueChanged`, RT |
| P-03 | Per-employee period result summary: gross, net, SI basis, employer cost | people, reporting store | on approval |
| P-04 | 6-month average salary and salary history | people | On-demand |
| P-05 | Pay calendar: cut-offs, **T&A close deadline**, approval deadline, pay date, std-day method | T&A, core | `pay.PayCalendarPublished`, when the period is created |
| P-06 | Run status with the **attendance versions consumed** and the People watermark | T&A, people, core, saas | `pay.PayrollRunStatusChanged` on every transition. On APPROVED, T&A locks the held versions. On UNLOCKED, T&A moves `LOCKED → CLOSED`. |
| P-07 | Hourly and daily rate per employee | T&A | On-demand (optional) |
| P-08 | SI status per month, SI-basis history including pre-go-live months, D02-LT status, years-of-contribution API | people, T&A, saas | `pay.SiContributionPosted` on approval |
| P-09 | Loan and advance balance | people | On-demand, at termination |
| P-10 | Final settlement sheet and status: amounts, service and basis, deadline, paid date. Payroll owns the deadline clock. | people | `pay.FinalSettlementStatusChanged` |
| P-11 | Payroll ESS pages and APIs: payslip, password, PIT certificate, annual income, loan statement, 08/CK, authorisation | saas ESS shell | at publication |
| P-12 | Approval tasks | core workflow | RT |
| P-13 | Audit events | core audit | RT |
| P-14 | Notifications: payslip published, approval pending, exceptions, 08/CK expiry | core | RT |
| P-15 | Cost-estimate API (gross↔net, employer cost) | recruitment, people | On-demand. BR-PAY-124 makes it **P2**; it becomes always-on once built. |
| P-16 | Actual labour cost by cost centre, project and period | reporting store, recruitment (P2) | on approval |
| P-17 | Usage metrics. Informational only: billing counts from People per legal entity (D-10). | saas | monthly |
| P-18 | Bank, GL, VSS and PIT XML files | external, via the saas file service | at generation. Manual upload in P1; APIs are P2. |
| P-19 | Pay-group assignment (employment, pay group, valid_from, source) | T&A, people | `pay.PayGroupAssigned`, RT |
| P-20 | Legal-entity lock state at a date | core | synchronous query (BR-PAY-087) |
| P-21 | 183-day residency recommendations | people (IN-32) | monthly or on demand |

## Close-hold protocol invariants (gap §7, ADR-0019)
- Every message carries `(tenant, legal_entity, pay_group, period, result_version, run_id)`. Hold, release, lock and unlock are idempotent on `run_id + result_version`.
- A run can be SUBMITTED only if it holds the current version of every period in scope. If T&A is down or refuses, the submit fails, so no orphan hold is left.
- A held period is never reopened and never changed in place. Locking on APPROVED therefore cannot fail, and approval never rolls back.
- T&A period states are `OPEN → IN_REVIEW → CLOSED → LOCKED`. Two transitions go backwards: `CLOSED → OPEN` (only with no active hold) and `LOCKED → CLOSED` (only on a payroll unlock).
- Hold states are `ACTIVE → CONSUMED | RELEASED`. Release reasons are `REJECTED`, `WITHDRAWN`, `CANCELLED`, `REOPEN_REQUESTED` and `UNLOCKED`. A hold never expires on a timer.

## Typical timeline (BRD §7.3; cut-off 25, pay day 5)
| When | What |
|---|---|
| D (25th) | People cut-off and the variable-input deadline |
| D+2 working days | T&A closes the period and publishes the result version |
| D+3 | Input cut-off, FINAL calculation, exceptions cleared (`CALCULATED`) |
| D+3…4 | Submit and acquire the hold |
| D+5 | Approval, then `LOCKED`, then the bank file |
| Pay day | Pay out and publish payslips |
| By the 20th of the next month | 05/KK-TNCN for monthly filers |
| By the last day of the month | SI payment |
