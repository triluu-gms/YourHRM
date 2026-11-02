---
name: yhrm-time-attendance
description: Domain knowledge for the YourHRM Time & Attendance module (`time-attendance`, DB schema `att`, requirement prefix BR-ATT / NFR-ATT, backend module `backend/src/modules/time-attendance`, package root `vn.yourhrm.<module>`). T&A owns holiday calendars, shifts and schedule assignment, device/Excel punch import, daily timesheet processing, overtime with legal caps, leave balances (accrual, carry-over, settlement), attendance periods, the versioned Attendance Result handed to Payroll and the close-hold protocol. Use it when reading, writing or reviewing code, DDL, events, APIs or stories that touch any of these, including the terms chấm công, bảng công, ký hiệu chấm công, ca làm việc, lịch làm việc, ngày lễ, nghỉ bù, máy chấm công, import Excel chấm công, làm thêm giờ (OT), giới hạn 40h/200h/300h, nghỉ phép, phép năm, phép thâm niên, chuyển phép, nghỉ ốm/thai sản BHXH, chốt công, khóa kỳ, close hold, Attendance Result, STD_DAYS/ngày công chuẩn, hồi tố, công tác, WFH.
---

# YourHRM Time & Attendance (`att`)

**Also load `yhrm-conventions`.** It covers tenancy/RLS, PII classes, outbox/inbox, no cross-schema FK, the legal-parameter rule and build/test commands. None of that is repeated here.

Supporting references:
- `references/business-rules.md`: all 80 BR and 11 NFR IDs with MoSCoW and phase. Read it when you implement or test a specific BR.
- `references/integration.md`: every ATT-C/ATT-P interface, event payload and the Attendance Result category contract. Read it when you touch events, APIs or Payroll handover.

## 1. Purpose and scope
T&A records three things for each employee: what they **must** work (schedule, holidays), what they **did** work (punches → daily timesheet), and **why** they were absent (leave, trips). It applies VN labour rules and hands a **closed, versioned, auditable Attendance Result** to Payroll. It is the single source of truth for working days, paid and unpaid absence, OT hours per legal category, night hours and leave balances.

**Out of scope, with the owner:**
- **Money** is computed by Payroll (`pay`): OT pay, leave payout amounts, allowances, deductions, rates (150/200/300%, +30%, +20%). T&A outputs only quantities (hours, days, counts). Never compute money in `att`.
- SI benefit claim filing → Payroll/Insurance. T&A supplies the absence facts and certificate data.
- Per diem and trip expenses → People.
- Workflow engine, notifications, org tree, users/roles, import engine, documents, audit → Core.
- Legal parameter pack and job scheduler → SaaS.
- Project timesheets and auto-rostering optimisation → P3. Device firmware, door access and canteen systems are not built at all.

## 2. Ownership (gap-analysis §5, decision R2-02)
**T&A is the single owner of:**
- **Holiday calendars** per tenant / LE / location (#2). Core, Payroll and Recruitment read the `att` calendar API (ATT-P-16), which is an always-on baseline API (R2-18, R2-C9).
- **Business trips** (#6). In P1 they exist only as attendance codes (BR-ATT-070 is P2).
- **Schedule assignment** (#26). People keeps only the contractual regime (full/part-time, hours per week).
- Device registry, enrolment mapping and punch processing (GAP-058). Daily timesheets, OT results and caps, leave types/policies/requests/ledger, attendance periods, Attendance Result versions and close holds.
- The opening-balance template `OB_LEAVE_BALANCE` (ADR-0037).

**T&A consumes these, and never redefines them:**

| Fact | Owner | Channel → local copy |
|---|---|---|
| Pay group, pay calendar, **standard-day method** (#25) | Payroll | `pay.PayCalendarPublished` (ATT-C-18) → `att.attendance_period.std_day_method`. Employee→pay group from `pay.PayGroupAssigned` (ATT-C-24) → `att.ext_pay_pay_group_assignment` |
| Payroll run state, holds, unlock | Payroll | `pay.PayrollRunStatusChanged` (ATT-C-19), close-hold calls (ATT-C-21) |
| Legal values (holiday template, OT caps, night window, leave floors, protected-worker rules, 300h industries) (#1) | SaaS LPS | `getParam(key, scope, asOfDate)`, `saas.LegalParameterPackPublished` (ATT-C-03) |
| Employment dates (hire, seniority, probation end, LWD, termination), status | People | `att.ext_hr_employment` (D5) |
| Protected-worker statuses | People | `people.SpecialStatusChanged` → `att.ext_hr_special_status`, read through `att.protected_flags()` (D5) |
| Assignment (LE, org unit, job, location, category, regime, FTE, cost centre) | People | `people.AssignmentChanged` → `att.ext_hr_assignment` |
| Locations (TZ, remote-area flag, coordinates) | Core | `core.LocationChanged` → `att.ext_core_location` |
| SI contribution years (P2 use) | Payroll | IF-PAY-P-08 (ATT-C-20) |

## 3. Domain model (DDL `database/postgresql/ddl/004_att.sql`)
Every table below exists in the DDL. Effective dating uses Pattern A: `valid_from` / `valid_to` exclusive, a generated `valid_during`, and an exclusion constraint. Soft references go to `hr`/`core`/`pay`.

| Concept (BRD §6) | Table(s) | Notes / invariants |
|---|---|---|
| HolidayCalendar / HolidayDate | `att.holiday_calendar`, `att.holiday_calendar_assignment`, `att.holiday_date` | Global VN template rows live under the sentinel tenant (`source = PLATFORM_TEMPLATE`, tenant-or-global RLS) and are written only by the platform release job. Tenants copy them (`TENANT_COPY`) or create `CUSTOM` calendars. Assignment is Pattern A, with one calendar per LE (+ optional location) at any date. `day_type` ∈ PUBLIC/SUBSTITUTE/COMPANY/SWAP_OFF/SWAP_WORK (swap = P2). Per-employee substitution (BR-ATT-003) is derived in processing, not stored. |
| ShiftDefinition | `att.shift_definition`, `att.shift_break` | Local `time` values. `crosses_midnight = (end_time <= start_time)`. Night-window override is NULL by default, which means the legal pack value applies. |
| SchedulePattern | `att.schedule_pattern`, `att.schedule_pattern_day` | P1 CHECK allows `pattern_type = 'WEEKLY'` only (ROTATING/FLEX are P2). `weekly_rest_days` uses ISO dow and may differ from Sunday. `day_kind` WORK/REST/OFF. |
| ScheduleAssignment | `att.schedule_assignment_rule`, `att.schedule_assignment` | Pattern A keyed by `assignment_key`, one ACTIVE pattern per assignment line per date. `source` RULE/MANUAL/IMPORT/OPENING_LOAD. Rules are evaluated by `priority`. |
| Roster (P2) | `att.roster`, `att.roster_line` | Exists in the DDL; not used in P1. |
| Device, EnrolmentMapping | `att.device`, `att.enrolment_mapping` | P1: `connector_type = 'FILE_IMPORT'` only. Device = registry of the terminal codes found in export files (`file_layout_code`). Mapping is Pattern A, one employment per enrol id per device scope per date. Geofence/Wi-Fi zones are not created (P2). |
| RawPunch | `att.raw_punch` | Append-only and partitioned monthly on `work_date_hint` (= local date of `local_ts`, deterministic). Dedup through `UNIQUE (tenant_id, dedup_key, work_date_hint)` + `ON CONFLICT DO NOTHING`. P1 `source = 'IMPORT'` only. `geo_enc`/`photo_ref_enc` must stay NULL (P2). `tg_raw_punch_guard` lets only the matching columns change, once, to resolve quarantine. |
| AttendanceCode | `att.attendance_code` | Global defaults (X, P, Ô, TS, L, CT, WFH, KL, V…) plus tenant rows. Maps to `payroll_category`/`payroll_sub_code`. `counts_si_nonwork` feeds `SI_NONWORK_DAYS`. |
| DailyTimesheet | `att.daily_timesheet` | One row per employment × work date (work date = shift start date), partitioned on `work_date`. **Recomputed, never edited**: `calc_version` + 1 per recalculation, with `calc_inputs_hash`, `legal_pack_id` and `rule_version` stamped (NFR-ATT-006). `day_status` includes NOT_EMPLOYED/SUSPENDED (BR-ATT-039). `has_blocking_exception` feeds the close checklist. |
| TimesheetCorrection | `att.timesheet_correction` | Employee request (BR-ATT-036) or admin/bulk edit (BR-ATT-037). Before/after values in `field_changes`. ADMIN/BULK rows require `reason_text`. No FK to the partitioned timesheet. |
| OvertimeRequest / OvertimeResult | `att.overtime_request`, `att.overtime_result` | Consent is mandatory once PENDING or APPROVED (`overtime_request_consent_ck`). `comp_choice = 'PAY'` only (TOIL is P2). Result has 6 categories, `paid_minutes` (default min(approved, actual)), and `preceded_by_day_ot` only on `*-N`. NW is not OT: it goes to `daily_timesheet.night_hours`. |
| OtCapLedger | `att.ot_cap_ledger` | One row per employment × MONTH/YEAR. `cap_scope = 'LEGAL_ENTITY'` only in P1 ('PERSON' is P2). `regime_code` STANDARD_200/EXTENDED_300, `cap_min` snapshot from the legal pack, `opening_min` from the opening-balance import, `override_min`, `regime_notice_ref` (BR-ATT-049). Update it in the OT-approval tx with `SELECT … FOR UPDATE`. |
| LeaveType / LeavePolicy | `att.leave_type`, `att.leave_policy` | Types are seeded per tenant at provisioning (not global). Policy is Pattern A per type × (LE, employee category). Statutory floors come from the legal pack; a policy may grant more, never less (service-validated). |
| LeaveRequest | `att.leave_request` | Half-day `slot_range` + exclusion constraint: no overlapping PENDING/APPROVED day or half-day leave per employment (hourly leave is checked by the service). SI claim columns are P3 (`*_enc`, subject DEK of the person). |
| LeaveBalanceLedger | `att.leave_balance_ledger` | Append-only. Balance = Σ quantity per employment × type × leave year. Pending reservations are derived from requests, not stored. Sign rules per `txn_type`. One OPENING row per employment × type × year (`source = OPENING_LOAD`). ACCRUE is idempotent per `accrual_month`. ADJUST requires a reason. |
| BusinessTrip / RemoteWorkRequest (P2) | `att.business_trip`, `att.remote_work_request` | Exist in the DDL; P1 uses CT/WFH codes instead. |
| AttendancePeriod | `att.attendance_period` | One row per LE × pay group × period (exclusion: no overlap), created from the pay calendar. Carries `ot_period_*`, `std_day_method`, `close_deadline`, `current_version`, `locked_version`. The state machine is enforced by `tg_attendance_period_guard`. |
| AttendanceResult / Line | `att.attendance_result`, `att.attendance_result_line` | Immutable versions. `scope` PERIOD or EMPLOYMENT (leaver early close / off-cycle retro). `result_kind` REGULAR/EARLY_CLOSE/OFF_CYCLE_RETRO. `source` CALCULATION/IMPORT. Lines must be inserted in the **same transaction** as the header; the deferred seal at COMMIT stamps `line_count`/`sealed_at`, and no line is accepted after that. Status PUBLISHED → WITHDRAWN or LOCKED; LOCKED → PUBLISHED only on payroll unlock. Lines are partitioned on `period_start`. |
| AttendancePeriodHold | `att.attendance_result_hold` | **The sole authority for close-hold state (D1, ADR-0019, X-03).** At most one ACTIVE hold per period × scope (`attendance_result_hold_active_uq`). ACTIVE → RELEASED / CONSUMED; CONSUMED → RELEASED only with `UNLOCKED`. Never deleted, never expires. Helper: `att.active_hold_for(period_id, employment_id)`. |
| PayGroupMembership | `att.ext_pay_pay_group_assignment` | Read-only projection of Payroll's link. |
| Projections | `att.ext_core_location`, `att.ext_hr_assignment`, `att.ext_hr_employment`, `att.ext_hr_special_status` | Event-fed and rebuildable through the owner API. `ext_hr_employment` holds one latest-state row per employment (no `trg_touch`; the handler sets `updated_at`/`synced_at`). `ext_hr_special_status` holds type + validity only, no detail. |

`att.protected_flags(employment_id, date)` returns `is_pregnant, has_child_under_12m, is_disabled, is_minor, is_elderly_worker, hazardous_job` **as of the work date**. Always use it rather than current-state flags, for OT approval, night-work checks, statutory breaks and retro recalculation. Show managers without the sensitive-data permission only a "protected worker" marker, never the type.

Partition helper: `att.create_partitions(parent, from, count)` (scheduler keeps 3 periods ahead).

## 4. Key processes (BRD §3)
- **P1 Holidays:** the platform publishes the VN pack for year N (lunar dates converted). The T&A admin reviews it and accepts or overrides per LE, adding company days. Substitute days are computed against each employee's own rest days. A change after lock produces a retro delta.
- **P2 Schedules:** define shifts and weekly patterns. Assign patterns per assignment by rule (priority order) or manually. React to `people.AssignmentChanged`, including future-dated and back-dated changes, and to `core.ReorgPackageStatusChanged` once it is `ACTIVE`. Schedule status feeds People onboarding readiness.
- **P3 Punches (P1 = import only):** Core import job (Excel template or vendor export layout) → `raw_punch` with dedup → enrolment mapping as of that date. Unknown or expired mappings are quarantined. Daily processing then attaches punches to a work date (reading hint D and D+1 for night shifts), pairs them, computes the quantities and codes, and marks NOT_EMPLOYED/SUSPENDED days.
- **P4 Corrections:** ESS request → Core workflow → recompute the day, keeping the original in history. Admin bulk edits require a reason. A request after the cut-off becomes retro (P9).
- **P5 OT (P1 = pre-approval only):** request with consent → protected-worker check and daily/monthly/annual cap check (warn or block; override with reason) → workflow → paid = min(approved, actual) → split by time segment into the 6 categories.
- **P6 Leave:** pick type and dates → duration from the schedule → checks (balance, overlap, notice, eligibility, attachments, SI claim data) → reserve while pending → TAKE ledger row on approval → timesheet updated.
- **P7 Accrual / year end:** monthly accrual or annual grant, proration (ND145 half-month rule), +1 day per 5 years → carry-over up to the max with an expiry → forfeit, encash or flag.
- **P8 Close (gap-analysis §7.2):** `pay.PayCalendarPublished` creates an OPEN period → D+1 the period moves to IN_REVIEW → exception checklist → close per LE × pay group, which publishes result vN and emits `att.AttendancePeriodClosed`. Payroll then calculates and, on submit, calls `acquireHold`. On APPROVED, T&A locks. On reject, withdraw or cancel, Payroll releases the hold.
- **P9 Retro:** a change to a LOCKED period never touches its snapshot. Delta lines go into the next open period's result, or into an off-cycle EMPLOYMENT result for a leaver with no open period (`att.RetroDeltaCreated`).
- **P10 Hire / transfer / leaver:** days before hire are NOT_EMPLOYED. The result is segmented per assignment change. On `people.TerminationScheduled`, block leave after the LWD and project the balance. At the LWD, early-close that employment (EMPLOYMENT-scope result, `att.LeaverAttendanceClosed`, with encash/recover days).

## 5. Invariants you must never break
1. **Raw punches are evidence**: append-only, evidence columns never change, and corrections are separate rows (BR-ATT-020/027). DB triggers enforce this. Do not add UPDATE paths.
2. **Never write a held or locked result.** No reopen and no withdraw while an ACTIVE hold exists (BR-ATT-079/087). A LOCKED result changes only through payroll unlock (BR-ATT-088). Later changes become retro deltas (BR-ATT-071). Do not recompute days of a CLOSED/LOCKED period in place: reopen first, which is impossible while the period is held (see Known conflicts).
3. **Hold acquisition** succeeds only if the period is `CLOSED` and the requested version = `current_version` (EMPLOYMENT scope: result PUBLISHED and period not LOCKED). Otherwise refuse, and leave no orphan hold. Acquire, release, lock and unlock are idempotent by `run_id` + `result_version`. Emit the event in the same transaction as the hold row.
4. **Locking on APPROVED cannot fail.** A version mismatch is a reconciliation incident and is never a rejection of Payroll's approval. Payroll REVERSED → no change in T&A. A nightly reconciliation job compares run, hold and period states.
5. **Period transitions** are only the ones `tg_attendance_period_guard` allows: OPEN↔IN_REVIEW, OPEN/IN_REVIEW→CLOSED, CLOSED→OPEN (reason, no PERIOD hold), CLOSED→LOCKED, LOCKED→CLOSED. `current_version` only increases on close. Boundaries are frozen once a result exists.
6. **Result versions are immutable**: header and lines are written in one transaction, then sealed. A re-close publishes a new version and withdraws the old one. Each version stamps `legal_pack_id`, `rule_version` and the checksum (NFR-ATT-006).
7. **STD_DAYS/STD_HOURS** use the pay group's method copied from the pay calendar. Never add a T&A-side method setting (BR-ATT-016).
8. **OT:** each minute falls into exactly one category. Consent is required. Caps count approved + pending and are measured **per legal entity** in P1. Use protected-worker flags as of the work date (BR-ATT-041/045–048).
9. **Leave balance** = ledger sum only. Every movement is a ledger row. An ADJUST needs a reason. Statutory floors are minimums (BR-ATT-057/068).
10. **Not employed ≠ absent** (BR-ATT-039). Use `ext_hr_employment` dates and suspension.
11. **Holiday templates** (sentinel tenant) are never modified by tenant code.
12. Processing is **idempotent and deterministic** for the same inputs and parameter version (BR-ATT-038, NFR-ATT-006).

## 6. VN compliance (BRD §5): parameters only, all `[verify-legal]`
Read every value from the Legal Parameter Service as of the work date. The numbers below are BRD defaults, **not confirmed law**. The sources define no T&A parameter key names yet.
- VN-01 normal hours ≤ 8h/day, 48h/week (≤ 10h/day if counted per week). VN-03 breaks ≥ 30 min (≥ 45 min at night) for 6h of continuous work, counted as working time. VN-04 ≥ 12h between shifts. VN-05 weekly rest ≥ 24h, or an average of ≥ 4 days/month.
- VN-02 night window 22:00–06:00.
- VN-06 11 public holidays (Tết DL 1, Tết AL 5, Giỗ Tổ 1, 30/4, 1/5, 2/9 + 1). Foreigners +2. A holiday on a rest day → substitute on the next working day (Art. 112). Watch for new holidays by National Assembly resolution (Q8).
- VN-07 OT requires consent. Normal + OT ≤ 12h/day; OT ≤ 40h/month, ≤ 200h/year, ≤ 300h/year for listed industries. VN-08 the 300h regime requires written notice to the provincial authority.
- VN-09/10/11 OT and night premiums (150/200/300%, +30%, night OT +20%) live in **Payroll**. T&A only supplies the categories and the `preceded_by_day_ot` flag.
- VN-12 annual leave 12/14/16 days + 1 per 5 years; proration; payout of unused leave on termination. VN-13 personal leave days. VN-14 SI sick leave 30/40/60 days (40/50/70 for hazardous work), child-sick 20/15 (Luật BHXH 2024). VN-15 maternity 6 months, paternity 5/7/10/14 days, 5 prenatal visits.
- VN-16 protected workers: no OT, night work or long trips from month 7 of pregnancy (month 6 remote) or with a child under 12 months without consent; statutory short paid breaks. VN-17 minors: max 8h/day, 40h/week.
- VN-18 ≥ 14 unpaid/SI working days in a month → no SI contributions (`SI_NONWORK_DAYS`). VN-19 the OT/night premium is PIT-exempt, so Payroll needs hours by category. VN-20 retention for inspection (NFR-ATT-007; default 10 years is `[verify-legal]`, D-04).
- NFR-ATT-008: GPS, selfies and biometrics are sensitive data; never store biometric templates. A DPIA is required before P2 mobile.

## 7. Events and interfaces (details in `references/integration.md`)
- **Publishes:**
  - Close events: `att.AttendancePeriodClosed`, `att.AttendancePeriodReopened`, `att.AttendancePeriodHoldGranted`, `att.AttendancePeriodHoldReleased`, `att.AttendancePeriodLocked`.
  - Other events: `att.RetroDeltaCreated`, `att.LeaverAttendanceClosed`, `att.AbsenceChanged`, `att.HolidayCalendarChanged`, `att.BusinessTripApproved` (P2).
- **Synchronous APIs:**
  - ATT-P-17 close hold: `acquireHold(period, version, runId)`, `releaseHold(period, runId, reason)`, `getHold(period)`. Payroll calls it in-process (allowed direction payroll → time-attendance, ADR-0001).
  - ATT-P-16 read API for calendars, schedules, timesheets and balances. The calendar/schedule part is always on, even without a T&A subscription.
- **Consumes:**
  - From Payroll: `pay.PayCalendarPublished`, `pay.PayGroupAssigned`, `pay.PayrollRunStatusChanged`.
  - From People: `people.AssignmentChanged`, `people.SpecialStatusChanged`, `people.TerminationScheduled`, `people.TerminationCancelled`, `people.EmployeeTerminated`, `people.EmployeeHired`, `people.PrimaryEmploymentChanged`.
  - From Core: `core.LocationChanged`, `core.OrgUnitChanged`, `core.ReorgPackageStatusChanged`, `core.WorkflowCompleted`.
  - From SaaS: `saas.LegalParameterPackPublished`.
- **Workflow request types** registered in Core (ATT-P-11): LEAVE, OT, ATT_CORRECTION, BUSINESS_TRIP, WFH, SHIFT_SWAP, PERIOD_REOPEN, BALANCE_ADJUST.

## 8. Phase 1 vs Phase 2 (mvp-scope §9.3: 55 BR + 7 NFR in P1)
**Build in P1:**
- Holidays (001–003, 005, 006). Weekly patterns, rest days, rule-based assignment, STD_DAYS (010, 011, 014–016).
- Excel and device-export **file import** with enrolment mapping and quarantine (020, 022, 025, 027).
- Daily processing (030–033, 035–039).
- Pre-approved OT with consent, categories, caps per LE, protected workers, 300h notice (040–042, 044–046, 048, 049).
- Leave configuration, entitlement, accrual, carry-over, requests, minimal SI claim data, maternity, termination payout, ledger (055–063, 065, 067, 068).
- Close and handover: retro, lifecycle, checklist, LE × pay-group close and leaver early close, versioned result, close hold (071–076, 079, 087, 088).
- ESS, notifications, opening-balance import (080, 082, 086).
- NFR-002 (nightly ≤ 30 min), 003, 005–008, 010.

**Do NOT build yet (P2). The DDL CHECKs block several of these:**
- Live device connector / agent, buffering, 500/s ingestion (021, NFR-001/004). Mobile, GPS, selfie, web check-in (023, 024, NFR-009/011). Duplicate merge and clock drift (026). Public API (085).
- Day swaps (004), flexible schedules (012), split shifts (013), rotation patterns, rostering and shift swaps (017–019).
- Late/early policy matrix (034). P1 has one fixed rule per LE. Effective-dated policy precedence (083). P1 has one policy set per LE.
- OT detection or auto-approval (043): P1 is pre-approval only. Cross-LE caps (047). TOIL (050). Consecutive-OT warning (051).
- SI sick-day tracking (064). Leave cancel/shorten with retro (066): in P1, HR uses a balance adjustment plus re-import. Team calendar (069).
- Trip and WFH requests (070): in P1 these are the CT/WFH attendance codes. Timesheet confirmation and sign-off (077, 078). MSS dashboard (081). Auto-escalation (084).

## 9. Known conflicts and gaps in the sources (do not resolve silently)
- **BR count:** the BRD footer says 77, but §4 and mvp-scope both count **80**.
- **NFR-ATT-001:** the BRD says 200/s per region. X-05 / GAP-058 set 500/s platform-wide and 50/s per tenant. NFR-ATT-001 is P2 anyway.
- **Recompute after close:** the DDL comment on `daily_timesheet` says rows are "mutable until its period is LOCKED". Backlog US-ATT-020/030 says never touch CLOSED/LOCKED days, and BR-ATT-079 forbids in-place change while a period is held. Treat CLOSED as frozen: reopen, then re-close.
- **Projection feed events:** `ext_hr_employment` claims to be fed by `people.EmploymentCreated/Changed/Terminated/Cancelled`. Those names are not in event catalogue v1.1, which has `people.EmployeeHired`, `people.TerminationScheduled`, `people.EmployeeTerminated`, `people.HireCancelled`. Confirm with the People owner.
- **OT detection:** `overtime_request.source` allows `'DETECTED'`, but BR-ATT-043 is P2. P1 creates only `PRE_APPROVAL` rows.
- **OT YTD opening balance:** BR-ATT-086 requires YTD OT hours, and `ot_cap_ledger.opening_min` exists, but ADR-0037 lists only `OB_LEAVE_BALANCE` for T&A. No OT template is defined.
- **PII class:** `ext_hr_special_status.protection_type` is P2 in `att`, while `hr` marks it P3-derived. Awaiting DPO (DESIGN §7.4, D5).
- **Backlog:** the header claims 84 stories / 458 SP across 11 epics. The file contains only US-ATT-001…032 (EP-ATT-01…04). Stories for OT, leave, close and ESS are missing.
- **Package name:** `backend/src/modules/time-attendance` is empty and no doc fixes the Java package. `vn.yourhrm.time-attendance` is not a legal Java name. Agree the name (and add the module to `settings.gradle.kts`) before creating code.

## 10. Open questions and risks that affect implementation (BRD §10, decision-log)
- Q3: does OT use a separate cut-off, and can Payroll handle two date ranges in one run? The DDL supports `ot_period_*`.
- Q4 / R2-08: cross-LE annual OT cap is deferred to P2, pending legal advice.
- Q5: who records pregnancy and child data (People or ESS self-declaration)? This drives consent and privacy for BR-ATT-035/048. Backlog US-ATT-028 marks it `[high-uncertainty]`.
- Q7: leave payout uses a salary base (Payroll); T&A gives days only. Q8: the 2027 holiday list. Q9: `PAYABLE_HOURS` for hourly workers. Q11: TOIL expiry.
- Risks:
  - R2: late People retro changes after lock → mandatory retro (BR-ATT-071).
  - R4: category code mismatch with Payroll → shared §7.3 code list plus contract tests.
  - R7: wrong opening balances → reconciliation sign-off.
  - R8: a held period blocks urgent fixes → release-request task (BR-ATT-087).
- Decision-log §3: every `[verify-legal]` value, the 10-year retention (D-04) and the biometric/GPS DPIA. R4-01…07 (re-baselined timeline, release train) are still open.

## 11. Where to look
| Need | File |
|---|---|
| Requirements (VI) | `docs/01-requirements/brd/modules/03-time-attendance.md`: §3 processes, §4 BRs, §5 VN rules, §6 entities, §7 interfaces + §7.3 result contract, §9 NFR, §10 questions/risks |
| Phase per ID | `docs/01-requirements/brd/mvp-scope.md` §9.3, §11 (R2-19 cut list, R3-06) |
| Ownership, events, close sequence | `docs/01-requirements/brd/gap-analysis.md` §5, §6, §7 (GAP-022/023/024/027/031/043/044/058/060/070) |
| Decisions | `docs/00-project-management/decision-log.md`: X-03, X-05, OD-9, D-09, R2-01/06/07/08/16/18/19, R3-06, D1, D5, D9, D10 |
| DDL | `database/postgresql/ddl/004_att.sql`. Design notes: `database/postgresql/DESIGN.md` §7.4 (att), §7.5 (pay mirrors), §10 (partitions/retention), §12 (seeding) |
| ADRs | 0019 saga/close hold, 0010 + 0025 legal parameters, 0030 partitioning/archiving, 0035 global vs tenant reference data, 0037 opening-balance import, 0001/0022 module boundaries |
| Backlog | `docs/01-requirements/user-stories/time-attendance-backlog.md` (incomplete, see §9) |
| Code | `backend/src/modules/time-attendance/` (empty). Follow `vn.yourhrm.<module>.api` + `internal.{domain,application,infra,web}` as in `backend/src/modules/core` |
