# T&A integration reference (interfaces, events, Attendance Result contract)

Sources:
- `docs/01-requirements/brd/modules/03-time-attendance.md` §7 (IDs `ATT-C-nn` = consumed, `ATT-P-nn` = provided)
- `docs/01-requirements/brd/gap-analysis.md` §6 (event catalogue v1.1) and §7 (close sequence)
- `database/postgresql/ddl/004_att.sql` §9–§10

Event envelope, outbox/inbox and transport are covered in **yhrm-conventions**. Close events also carry `(pay_group_id, period_id, result_version, run_id)`. Events produced by an opening-balance load carry `initial_load=true`.

## 1. Consumed (ATT-C)
| ID | What | From | Lands in / used for | MoSCoW |
|---|---|---|---|---|
| ATT-C-01 | Tenant context, config, time zone, default language | saas | Isolation, local-time conversion | M |
| ATT-C-02 | Plan/feature entitlements (mobile, device connector, roster, max devices) | saas (`saas.EntitlementsChanged`) | Feature gating; baseline `baseline.att.excel_import` | M |
| ATT-C-03 | **VN legal parameter pack**: holiday template, OT caps, night window, leave entitlements, protected-worker rules, 300h industry list. Read with `getParam(key, scope, asOfDate)`. | saas LPS (`saas.LegalParameterPackPublished`) | BR-ATT-002, 045–048, 057. Every result stores `legal_pack_id`. | M |
| ATT-C-04 | Device connector gateway (endpoint/agent, credentials, rate limit) | saas | BR-ATT-021, 085 (both P2) | M |
| ATT-C-05 | Mobile app framework and push | saas | P2 | S |
| ATT-C-06 | Legal entity, org tree with managers, cost centres | core (`core.LegalEntityChanged`, `core.OrgUnitChanged`) | Schedule rules, period scope, approvers | M |
| ATT-C-07 | Locations with coordinates, TZ, remote-area flag | core (`core.LocationChanged`) | `att.ext_core_location` | M |
| ATT-C-08 | Users, roles, permissions, data scope | core | ESS/MSS/admin access | M |
| ATT-C-09 | Workflow engine: request types, chains, delegation, final-decision callback | core (`core.WorkflowCompleted`, `core.WorkflowStepCompleted`) | Leave, OT, correction, trip, WFH, swap, period reopen | M |
| ATT-C-10 | Notification service and templates | core | BR-ATT-082 | M |
| ATT-C-11 | Effective-dated config store, audit, documents (core); job scheduler/queue (saas) | core; saas | Policies, audit, attachments, nightly jobs | M |
| ATT-C-12 | Employee master: id, name, number, gender, DOB, nationality | people | Eligibility, foreigner holidays, minor rules | M |
| ATT-C-13 | Employment milestones: hire, seniority date, probation end, LWD and termination, suspensions | people | `att.ext_hr_employment`; BR-ATT-039, 057–059, 067 | M |
| ATT-C-14 | Effective-dated assignment: LE, org unit, job, manager, location, employee category, contractual regime, FTE, cost centre. **No pay group and no default schedule.** | people (`people.AssignmentChanged`) | `att.ext_hr_assignment`; segmentation, schedule rules | M |
| ATT-C-15 | Job flags: hazardous / especially hazardous; punch-exempt flag | people | 14/16-day annual leave, OT exemption | M |
| ATT-C-16 | Protected-worker facts: pregnancy, child under 12 months, disability, remote area | people (`people.SpecialStatusChanged`) | `att.ext_hr_special_status` + `att.protected_flags()`; BR-ATT-035, 048, 057 | M |
| ATT-C-17 | Termination decision with LWD | people (`people.TerminationScheduled`, `people.TerminationCancelled`, `people.EmployeeTerminated`) | Leave projection, block future leave, early close BR-ATT-075 | M |
| ATT-C-18 | **Pay calendar**: pay group, periods, cut-offs (attendance and OT), T&A close deadline, pay date, **std-day method** | payroll (`pay.PayCalendarPublished`) | Creates `att.attendance_period` (copies `std_day_method`); BR-ATT-016, 072 | M |
| ATT-C-19 | Payroll run status with attendance versions consumed | payroll (`pay.PayrollRunStatusChanged`) | `APPROVED` → lock; `UNLOCKED` → `LOCKED→CLOSED`; `REVERSED` → no change. BR-ATT-073, 088 | M |
| ATT-C-20 | SI contribution years (IF-PAY-P-08) | payroll (`pay.SiContributionPosted`) | BR-ATT-064 (P2) | S |
| ATT-C-21 | Close-hold commands `acquireHold` / `releaseHold(reason)`, called into ATT-P-17 | payroll | BR-ATT-079, 087 | M |
| ATT-C-22 | Estimated hourly cost | payroll | OT cost preview (P2) | C |
| ATT-C-23 | Accepted candidate with planned start date | recruitment (via people) | Pre-enrolment / pre-assignment of schedule | C |
| ATT-C-24 | Employee → pay group link | payroll (`pay.PayGroupAssigned`) | `att.ext_pay_pay_group_assignment`; period scope, segmentation | M |
| ATT-C-25 | Primary employment per purpose | people (`people.PrimaryEmploymentChanged`) | Informational. P1 OT caps stay per employment/LE (R2-08). | S |

Also consumed (catalogue §6): `core.ReorgPackageStatusChanged` (re-evaluate schedule rules once `ACTIVE`), `core.JobCatalogueChanged`, `people.EmployeeHired`, `people.HireCancelled`, `people.StartDateChanged`, `people.ContractChanged` (SUSPENDED/RESUMED), `people.ProbationCompleted`, `people.SecondmentChanged`, `people.PersonalDataChanged`, `saas.ConsentChanged`.

## 2. Provided (ATT-P)
| ID | What | To | When | MoSCoW |
|---|---|---|---|---|
| ATT-P-01 | **Attendance Result**: versioned snapshot per employee × assignment segment × period (§3 below), announced by `att.AttendancePeriodClosed(version, checksum)` | payroll | Close, re-close, unlock (same version), leaver early close | M |
| ATT-P-02 | Retro delta lines for locked periods (`att.RetroDeltaCreated`) | payroll | Inside the next open period's result | M |
| ATT-P-03 | Leave settlement at termination: unused annual days (incl. carry-over), negative days, leave year (`att.LeaverAttendanceClosed`) | payroll, people | Leaver's final processing | M |
| ATT-P-04 | Stale notice `att.AttendancePeriodReopened`, only possible without an active hold | payroll | Reopen | M |
| ATT-P-05 | SI absence facts: sick, child-sick, maternity, paternity, prenatal, miscarriage, work accident, with certificate data (`att.AbsenceChanged`) | payroll (insurance) | Approval, and in the result | M |
| ATT-P-06 | Expired annual leave flagged for payout | payroll | Carry-over expiry | S |
| ATT-P-07 | Maternity start / planned and actual return; long unpaid absences (≥ 14 working days) | people, payroll | Approval / change | M |
| ATT-P-08 | Approved business trips (`att.BusinessTripApproved`). BR-ATT-070 is P2. | people (expenses/per diem) | Approve, change, cancel | M |
| ATT-P-09 | Leave balance and pending requests at LWD | people | On termination, on request | M |
| ATT-P-10 | Attendance statistics per employee | people (appraisal, probation) | On request / period end | C |
| ATT-P-11 | Workflow request types: LEAVE, OT, ATT_CORRECTION, BUSINESS_TRIP, WFH, SHIFT_SWAP, PERIOD_REOPEN, BALANCE_ADJUST | core | Deploy; per request | M |
| ATT-P-12 | Business events for notification/audit (request status, cap threshold, period status, device offline) | core | On event | M |
| ATT-P-13 | Availability/absence calendar | recruitment, core | On request | C |
| ATT-P-14 | Usage metrics (devices, connectors, punches/month, mobile users) | saas | Daily | S |
| ATT-P-15 | Connector health per device | saas | Continuous | S |
| ATT-P-16 | Read API: daily timesheet, balances, **holiday calendar**, **schedule patterns** (`att.HolidayCalendarChanged`). The holiday and schedule read API is **always-on baseline** (R2-18, R2-C9); backlog US-ATT-007 names the SPI `WorkingCalendarProvider`. | core, payroll, people, recruitment | On request | M |
| ATT-P-17 | **Close-hold API**, sync and idempotent by run id + version: `acquireHold(period, version, runId)` → granted / refused; `releaseHold(period, runId, reason)`; `getHold(period)` | payroll | Run submit / release | M |
| ATT-P-18 | Close events `att.AttendancePeriodHoldGranted`, `att.AttendancePeriodHoldReleased`, `att.AttendancePeriodLocked` | payroll, core | Grant / release / lock | M |

## 3. Events published by T&A (catalogue v1.1)
| Event | Consumers | Trigger | Key payload |
|---|---|---|---|
| `att.AbsenceChanged` | core, payroll, people | Leave approved or cancelled | type, dates, SI data (no P3 in payload), status |
| `att.BusinessTripApproved` | people | Trip approved/changed (P2) | dates, half-days, destination, cost centre |
| `att.HolidayCalendarChanged` | core, payroll, rec | Calendar published or changed | calendar, dates, effective |
| `att.AttendancePeriodClosed` | payroll | Close / re-close / unlock | period, pay group, LE, result_version, checksum, cause `CLOSE`/`RECLOSE`/`UNLOCK` |
| `att.AttendancePeriodReopened` | payroll | `CLOSED → OPEN` (no active hold) | period, withdrawn version, reason |
| `att.AttendancePeriodHoldGranted` | payroll, core | `acquireHold` succeeded; same tx as hold row | hold id, period, pay group, LE, version, run id |
| `att.AttendancePeriodHoldReleased` | payroll, core | `releaseHold` | hold id, period, version, run id, reason `REJECTED`/`WITHDRAWN`/`CANCELLED`/`REOPEN_REQUESTED`/`UNLOCKED` |
| `att.AttendancePeriodLocked` | payroll, core, people | `pay.PayrollRunStatusChanged(APPROVED)`; consumes hold; cannot fail | period, locked version (= held), run id |
| `att.RetroDeltaCreated` | payroll | Change touches a LOCKED period | original period, item ± qty, target period |
| `att.LeaverAttendanceClosed` | payroll, people | Individual close at LWD | employment, final result version, encash/recover days |

Outbox and inbox come from `sys.create_outbox_inbox('att', 'yhrm_app_att')` at the end of `004_att.sql`.

## 4. Attendance Result contract (BRD §7.3) → `att.attendance_result` / `att.attendance_result_line`
- **Grain:** one result per employee × assignment segment × attendance period. A new segment starts when LE, pay group, cost centre, schedule, employee group or FTE changes, and at hire and termination. Lines carry `segment_from`/`segment_to`.
- **Work date** = shift start date. Results state both the attendance date range and the OT date range (`att_date_*`, `ot_date_*`).
- **Units:** days and hours are decimals with 2 places (`numeric(12,2)`), counts are integers, minutes are used only for lateness. `unit ∈ DAY/HOUR/MINUTE/COUNT`.

| # | `category_code` | Unit | Meaning |
|---|---|---|---|
| 1 | `STD_DAYS`, `STD_HOURS` | day / hour | Standard days/hours of the segment (pay group's method). `period_quantity` = full-period value for proration. |
| 2 | `WORKED_DAYS` | day | Worked days, incl. statutory short breaks, trips, WFH |
| 3 | `PAID_LEAVE_DAYS.<type>` | day | Employer-paid leave (annual, paid personal, TOIL, company) |
| 4 | `HOLIDAY_DAYS` | day | Holidays + substitute days in the segment |
| 5 | `SI_LEAVE_DAYS.<type>` | day (calendar days for maternity) | SI-paid absences |
| 6 | `UNPAID_DAYS.<type>` | day | Unpaid personal, agreed unpaid, unauthorised absence `V`, suspension |
| 7 | `NOT_EMPLOYED_DAYS` | day | Days in the period outside employment |
| 8 | `OT_HOURS.<cat>` | hour | OT-WD … OT-PH-N. `preceded_by_day_ot` sub-flag for night OT. Excludes TOIL. |
| 9 | `NIGHT_HOURS` | hour | Night work in normal hours (NW) |
| 10 | `SI_NONWORK_DAYS` | day | Unpaid + SI-paid working days (maternity excepted). Used by the ≥ 14-day SI exemption test (VN-18). |
| 11 | `LATE_MIN`, `EARLY_MIN`, `LATE_COUNT`, `EARLY_COUNT`, `MISSING_PUNCH_COUNT` | min / count | Policy deductions, attendance bonus |
| 12 | `SHIFT_COUNT.<shift_group>` | count | Shift allowances (`shift_definition.shift_group_code`) |
| 13 | `MEAL_ELIGIBLE_DAYS` | day | Meal/attendance allowance eligibility |
| 14 | `TRIP_DAYS.<domestic/overseas>`, `WFH_DAYS` | day | Allowances (per diem itself belongs to People) |
| 15 | `LEAVE_ENCASH_DAYS`, `LEAVE_RECOVER_DAYS` | day | Leaver settlement (ATT-P-03) |
| 16 | `RETRO.<item>` | per item | Delta lines: `is_retro`, `original_period_id`, `original_result_id`, `original_segment_from`, `retro_reason`, `source_ref` |
| 17 | Header | — | period id, version, status, date ranges, generated/closed by, checksum (`checksum_sha256`) |

**Retro rules (BRD §7.3, X-03):**
- (a) A `CLOSED` period that is not held and not `LOCKED` is corrected by reopen + re-close, which creates a new version. A held period must first be released by Payroll (BR-ATT-087).
- (b) A `LOCKED` period never changes. Deltas go to the next open period.
- (c) For a leaver with no open period, publish an off-cycle result (`scope = EMPLOYMENT`, `result_kind = OFF_CYCLE_RETRO`).
- (d) Retro lines reference the original segment, so Payroll can apply the original rates.

Payroll mirrors results in `pay.ext_att_result` (scope/`result_kind` aligned with `att`, D10) and holds in `pay.run_attendance_version` (mirror keyed by `att_hold_id`, D1). Neither enforces hold uniqueness; `att` does.
