# Core: interfaces and events

Sources:
- `docs/01-requirements/brd/modules/01-core.md` §7. Core numbers its interfaces `C-nn` (consumed) and `P-nn` (provided). Other BRDs cite theirs as `IF-<MOD>-C/P-nn`.
- `docs/01-requirements/brd/gap-analysis.md` §6, the canonical event catalogue v1.1.

Names follow the pattern `<module>.<Aggregate><PastTense>`. The envelope fields and the `initial_load=true` flag for opening-balance loads are defined in gap §6.

## Consumed (C-nn)
| # | What | From | Core uses it for |
|---|---|---|---|
| C-01 | `saas.TenantProvisioned` | saas | Create the company group, Owner user, system roles, templates and settings. The first LE is created via Core's API. |
| C-02 | `saas.EntitlementsChanged` (alias `PlanChanged`): max LEs/users, modules, channels | saas | Enforce limits (BR-CORE-001) and hide disabled features and channels |
| C-03 | `saas.TenantStateChanged` (alias Suspended/Reactivated/Terminated) | saas | Block login, stop schedulers and notifications, export or purge |
| C-04 | Master-data release from SaaS back-office | saas | Versioned load, then publish `core.MasterDataReleased` |
| C-05 | Break-glass support grant | saas | Enforce it and audit it (BR-CORE-041) |
| C-06 | Delivery adapter (email in P1), object storage, KMS, bus | saas | Notifications, documents, encryption |
| C-07 | `people.EmployeeHired` (hire_type NEW/REHIRE/CONCURRENT, preview flag) | people | Auto-create or re-activate the user (P-04) |
| C-08 | `people.TerminationScheduled` (access_revocation_date), `people.EmployeeTerminated` | people | Schedule the downgrade to FORMER_EMPLOYEE, reassign tasks, revoke sessions |
| C-09 | `people.AssignmentChanged` | people | Recompute derived SELF/LINE_MANAGER scope and invalidate the authz cache |
| C-10 | Current-assignment lookup (sync, as-of) | people | Approver resolution, scope, recipients |
| C-11 | Position occupancy (filled FTE) | people | Vacancy, org chart (P2) |
| C-12 | `people.ReorgMappingCompleted{package_id, moved, failed}` | people | Set the package to ACTIVE or ACTIVATED_WITH_ERRORS (P2) |
| C-13 | Merge-field data | people/pay/rec | Document templates |
| C-14 | Working calendar and holidays (`att` API ATT-P-16). Without T&A, use the legal-pack national template (R2-18). | att | Working-time SLA (P2), the P-23 calculator |
| C-15 | `att.AbsenceChanged` (approved leave) | att | Delegation suggestion (P2) |
| C-16 | LE lock status as-of (IF-PAY-P-20) | pay | Block or warn on retro changes (BR-CORE-015) |
| C-17 | Open requisitions per position (`rec.RequisitionStatusChanged`) | rec | Occupancy and reorg impact (P2) |
| C-18 | Registration of process types, permissions, import types, notification codes, custom-field targets and doc types | all modules | Engine configuration at deploy |
| C-19 | Workflow callback acknowledgement | all | Close the instance or create an error task |
| C-20 | Import validators and commit/undo handlers | all | Import engine |
| C-21 | Candidate contact (email/phone, consent) | rec | Notify non-users |
| C-22 | Authenticated identity (PR-03) and the session-revocation API | saas | identity → user_account → roles/scope |
| C-23 | Legal Parameter Service `getParam`; `saas.LegalParameterPackPublished` | saas | Region resolution, retention floors |
| C-24 | Job scheduler / queue | saas | Effective-date jobs, SLA timers, import jobs |
| C-25 | Approved workforce-plan FTE budgets and proposed positions | rec (P2) | Position budget |
| C-26 | Consent check, retention-policy keys, DSR orchestration | saas | Retention class, DSR export |

## Provided (P-nn)
| # | What | To |
|---|---|---|
| P-01 | Legal-entity master as-of: MST, BHXH code, address, rep, payer bank accounts, FY, tax authority, PIT frequency, BHTNLĐ-BNN decision, status. `core.LegalEntityChanged`. | people, pay, rec, att, saas |
| P-02 | Location master: address, region, TZ, geo-fence, Wi-Fi/IP, dependent tax code. `core.LocationChanged`. | att, pay, people, rec |
| P-03 | Min-wage region as-of. `core.LocationChanged` (subtype `LocationRegionChanged`). | pay, people |
| P-04 | Org tree as-of, unit detail, ancestors and descendants, unit manager. `core.OrgUnitChanged`, `core.ReorgActivated`. | people, att, pay, rec |
| P-05 | Job titles (hazardous flag), families, grade catalogue. `core.JobCatalogueChanged`. | people, pay, rec, att |
| P-06 | Positions, FTE budget, status, position-control mode. `core.PositionChanged`. | people, rec |
| P-07 | Cost centres and defaults per unit/position | pay, people |
| P-08 | Employee-category list | all |
| P-09 | Current user context (user, employee, tenant, locale, roles, scope) | all, frontend |
| P-10 | Authorisation service: `can(...)`, scope predicate, field-mask policy | all, incl. reporting |
| P-11 | `core.UserStatusChanged` (ACTIVE / FORMER_EMPLOYEE / DEACTIVATED). Replaces UserCreated/UserDeactivated. | saas, people |
| P-12 | Workflow engine: submit/withdraw, task inbox, `core.WorkflowCompleted` callback, SLA events | all |
| P-13 | Approval-delegation lookup | all |
| P-14 | Notification centre `send(eventCode, recipients, payload, encrypted attachment?)`, inbox, delivery status | all |
| P-15 | Global master data (admin units and mapping, countries, ethnicity, religion, ID types, banks, currencies…) | people, pay, rec, saas |
| P-16 | Code lists, including tenant custom lists | all |
| P-17 | Settings service (tenant/LE/location) | all |
| P-18 | Numbering service | people, pay, rec, att |
| P-19 | Custom-field framework (P2) | all |
| P-20 | Audit service: `record(event)`, query, security log | all, saas |
| P-21 | Document API: upload/download, AV, versions, retention class, signed URL, template → PDF, e-sign orchestration | people, pay, rec, att |
| P-22 | Import engine, including OPENING_BALANCE mode | all, saas |
| P-23 | Working-day/hour calculator (wraps the T&A calendar) | all |
| P-24 | Tenant usage metrics | saas |
| P-25 | Personal-data access log and per-user audit export (DSR) | saas, people |
| P-26 | Exchange rate as-of (BR-CORE-088, P2) | pay (BR-PAY-013) |
| P-27 | Audit write API for SaaS identity and operator events | saas |

## Core-published events (gap §6 v1.1)
| Event | Consumers | Key payload |
|---|---|---|
| `core.MasterDataReleased` | all | list, version, effective date, retired codes |
| `core.LegalEntityChanged` | people, pay, att, rec, saas | LE id, changed attrs, effective date |
| `core.LocationChanged` | att, pay, people, rec | location, region, coords, TZ, remote-area flag, effective date; subtype `LocationRegionChanged` |
| `core.OrgUnitChanged` | people, att, pay, rec | unit, change_type (CREATE/RENAME/MOVE/CLOSE…), effective date |
| `core.ReorgActivated` | people, rec, core-workflow | package id, effective date, mapping table (P2) |
| `core.ReorgPackageStatusChanged` | people, att, pay, rec | PENDING_MAPPING / ACTIVE / ACTIVATED_WITH_ERRORS, counts (P2) |
| `core.PositionChanged` | people, rec | PROPOSED/ACTIVE/FROZEN/CLOSED, FTE (P2) |
| `core.JobCatalogueChanged` | people, att, pay, rec | title/grade, hazardous category |
| `core.UserStatusChanged` | saas, people | user, employee link, status |
| `core.WorkflowStepCompleted` | owner module | instance, step, action (incl. RETURNED), actor / on-behalf-of |
| `core.WorkflowCompleted` | owner module | instance, outcome, approval-trail ref |

BR-CORE-060 also lists `WorkflowSubmitted`, `StepAssigned`, `WorkflowWithdrawn` and `SlaBreached`. These are not in the gap §6 catalogue.

The current code in `vn.yourhrm.core.api.CoreEvents` also emits `core.RoleAssignmentChanged` and `core.RoleChanged` in-process. ADR-0006 uses them for authz-cache invalidation. Neither is in the gap §6 catalogue.

## Other events Core consumes
`saas.TenantProvisioned`, `saas.TenantStateChanged`, `saas.EntitlementsChanged`, `saas.LegalParameterPackPublished`, `people.EmployeeHired`, `people.HireCancelled`, `people.StartDateChanged`, `people.AssignmentChanged`, `people.SecondmentChanged`/`TemporaryTransferChanged`, `people.ContractChanged`, `people.TerminationScheduled`, `people.TerminationCancelled`, `people.EmployeeTerminated`, `people.PersonalDataChanged`, `people.OnboardingReadinessChanged`, `people.ReorgMappingCompleted`, `att.AbsenceChanged`, `att.HolidayCalendarChanged`, `att.AttendancePeriodHoldGranted`/`Released`, `att.AttendancePeriodLocked`, `pay.PayCalendarPublished`, `pay.PayrollRunStatusChanged`, `rec.RequisitionStatusChanged`.
