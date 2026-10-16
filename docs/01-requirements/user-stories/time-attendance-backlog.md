# Time & Attendance Backlog: Phase 1 (v0.1, 2026-10-16)

| Item | Value |
|---|---|
| Owner | BA – Time & Attendance |
| Scope | Every P1 BR-ATT and NFR-ATT in [mvp-scope.md](../brd/mvp-scope.md) §9.3 (55 BR + 7 NFR = 62 IDs), detailed in [03-time-attendance.md](../brd/modules/03-time-attendance.md) v0.3 |
| Method | [estimation-guide.md](estimation-guide.md) v0.1 (Fibonacci reference scale, §4) |
| Decisions applied | R2-01 (no live device connector: attendance comes from Excel import, device-export-file import, manual entry and correction requests), OD-9 / R2-06 (no mobile or web check-in), X-03 (payroll close hold), D-09 (opening-balance import, no migration), R2-08 / R2-16 (OT caps per legal entity), R2-18 (Excel attendance import and calendar read API are baseline), R2-19 (Option B cuts), R3-06 (early close for a leaver), R2-07 (SI claim data, minimal) |

**What this backlog does not build.** Core owns the workflow engine, the notification service, the import engine (upload, dry-run, validation report, chunked commit), audit and documents. SaaS owns the Legal Parameter Service and the job scheduler. T&A stories count only the **registration and configuration** on those engines: workflow request types, import templates, notification templates, scheduled jobs and parameter keys (guide §4).

**Dependency placeholders.** The other backlogs are written in parallel and had no story IDs when this file was written. External dependencies use these placeholders. Replace them with the `US-…` IDs during PM consolidation.

| Placeholder | Meaning | Owner backlog |
|---|---|---|
| `CORE-WF` | Workflow engine: request types, approval chains, callbacks, tasks | core-backlog |
| `CORE-IMP` | Import engine: templates, dry-run, validation report, chunked commit | core-backlog |
| `CORE-NOTIF` | Notification service: e-mail and in-app, templates | core-backlog |
| `CORE-AUDIT` / `CORE-DOC` | Audit service / document (attachment) service | core-backlog |
| `CORE-RBAC` | Roles, permissions, data scopes | core-backlog |
| `PPL-EMP` | People employment, assignment, separation and protected-fact events and read APIs | people-backlog |
| `PAY-CAL` | Pay calendar, pay groups, `pay.PayGroupAssigned`, standard-day method | payroll-backlog |
| `PAY-RUN` | Payroll run state machine and `pay.PayrollRunStatusChanged` | payroll-backlog |
| `SAAS-LPS` | Legal Parameter Service (`getParam`, VN legal pack incl. national holiday template) | saas-platform-backlog |
| `TECH-BUS` | Outbox and DB-backed event bus (ADR-0004 rev. 2) | technical-enablers-backlog |
| `TECH-JOB` | Tenant-aware job scheduler / queue | technical-enablers-backlog |
| `TECH-RLS` | Tenancy and RLS foundation | technical-enablers-backlog |
| `TECH-PERF` | Performance-test harness and data generator | technical-enablers-backlog |

## 1. Epics

| Epic ID | Name | Goal | Stories | SP |
|---|---|---|---|---|
| EP-ATT-01 | Holiday calendars | Per-entity holiday calendars inherited from the VN pack (incl. lunar Tết), substitute days, foreigner holidays, always-on read API | 7 | 34 |
| EP-ATT-02 | Shifts, schedules and assignment | Shift definitions, weekly patterns with rest days, rule-based schedule assignment, standard days/hours | 5 | 31 |
| EP-ATT-03 | Attendance capture by import | Immutable raw punches from Excel and device-export files, enrolment mapping, quarantine, timesheet and Attendance Result imports | 9 | 47 |
| EP-ATT-04 | Daily timesheet processing | Work-date attribution, pairing, daily quantities, attendance codes, statutory short-time, idempotent reprocessing | 11 | 63 |
| EP-ATT-05 | Corrections and manual entry | ESS correction requests, admin manual entry and bulk edit, change history | 3 | 13 |
| EP-ATT-06 | Overtime | OT requests with consent, 6 OT categories, payable OT, legal caps per legal entity, protected employees, 300h regime | 9 | 55 |
| EP-ATT-07 | Leave configuration, entitlement and ledger | Leave types, VN seed, ledger, entitlement with seniority, service time, accrual, carry-forward, encashment on termination | 8 | 53 |
| EP-ATT-08 | Leave requests and SI claim data | Leave requests and validation, HR entry, SI claim data, maternity, business trip / WFH codes | 6 | 26 |
| EP-ATT-09 | Period close and payroll hand-off | Periods from the pay calendar, lifecycle, checklist, versioned Attendance Result, hold API, lock/unlock, retro deltas, early close for a leaver | 13 | 83 |
| EP-ATT-10 | Self-service, notifications, reports, retention | ESS/MSS web pages, notifications, statutory and operational reports, retention, permissions | 10 | 40 |
| EP-ATT-11 | Opening balances | Opening leave balances and YTD OT templates with reconciliation sign-off | 3 | 13 |
| **Total** | | | **84** | **458** |

## 2. Stories

### EP-ATT-01 Holiday calendars

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-001 | EP-ATT-01 | As a T&A admin, I want holiday calendars with effective-dated entries assigned to legal entities and, optionally, locations so that each entity has its own holidays. Calendar list + form with entries typed public / substitute / company; one active calendar per legal entity and date; changes audited | BR-ATT-001 | 5 | TECH-RLS, CORE-AUDIT | Effective-dated entity (guide 5). Day-swap types are P2 (BR-ATT-004) |
| US-ATT-002 | EP-ATT-01 | As a T&A admin, I want my calendar to inherit the platform VN public-holiday pack for year N and override single dates so that I do not type national holidays. Pack read from the Legal Parameter Service with its version; override/accept per date with reason; a republished pack shows a diff to accept | BR-ATT-002 | 5 | US-ATT-001, SAAS-LPS | T&A consumes the pack; the platform publishes it (ATT-C-03) |
| US-ATT-003 | EP-ATT-01 | As the SaaS operator, I want a lunar-to-Gregorian conversion tool and validated pack content for 2026–2030 (Tết 5 days, Hùng Kings 10/3 lunar, 2 Sep + adjacent day) so that the pack is correct before the pilot. Conversion checked against official announcements; Tết day distribution entered per year; output loadable into the Legal Parameter Service | BR-ATT-002 | 3 | SAAS-LPS | `[verify-legal]` Q8: new holidays from 2025–2026 NA resolutions to be confirmed |
| US-ATT-004 | EP-ATT-01 | As an employee, I want a substitute day off on my next working day when a public holiday falls on **my** schedule's rest day so that I get my statutory holiday. Computed per employee schedule (not calendar weekday); handles consecutive holidays (Tết), half-day Saturdays, 6-day weeks; rule configurable per entity | BR-ATT-003 | 8 | US-ATT-001, US-ATT-009, US-ATT-010 | `[verify-legal]` Art. 112(3). Many edge cases (guide 8) |
| US-ATT-005 | EP-ATT-01 | As a T&A admin, I want the two extra holidays for foreign employees (traditional new year + national day) per nationality so that foreigners get their statutory days. Dates per nationality and year; eligibility from People nationality; shown on their calendar and timesheet | BR-ATT-005 | 3 | US-ATT-001, PPL-EMP | `[verify-legal]` Art. 112(2) |
| US-ATT-006 | EP-ATT-01 | As a payroll officer, I want a holiday change to recalculate open periods and create retro deltas for locked ones so that holiday pay stays correct. Affected employee-days found; open days requeued; locked days produce deltas; `att.HolidayCalendarChanged` published | BR-ATT-006 | 5 | US-ATT-002, US-ATT-030, US-ATT-068, TECH-BUS | |
| US-ATT-007 | EP-ATT-01 | As Core, Payroll or People, I want an always-on holiday-calendar and schedule read API (`WorkingCalendarProvider` SPI) so that SLAs, standard days and payment dates work even for tenants without T&A. Resolved calendar per legal entity / location / employee and date range; works with no T&A entitlement (national calendar from the pack + default schedule); contract tests | BR-ATT-001, BR-ATT-002, BR-ATT-011 | 5 | US-ATT-002, US-ATT-009 | Baseline `API` (R2-18, R2-C9, ATT-P-16). Must be tested in every tenant configuration |

### EP-ATT-02 Shifts, schedules and assignment

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-008 | EP-ATT-02 | As a T&A admin, I want shift definitions (start/end, cross-midnight, paid/unpaid fixed breaks, grace in/out, rounding, half/full-day minimum minutes, standard hours, night-window override) so that daily processing knows the expected working time. Validation of breaks against legal minimums; cross-midnight start/end checks; versioned when used | BR-ATT-010 | 5 | TECH-RLS | `[verify-legal]` VN-03 break minimums from parameters. Core hours / flexible are P2 (BR-ATT-012) |
| US-ATT-009 | EP-ATT-02 | As a T&A admin, I want weekly schedule patterns (5-day, 6-day, 5.5-day with half Saturday, alternating Saturdays with anchor date) that define the weekly rest day(s) so that OT categories and substitute days use the right rest day. Rest day may differ from Sunday; weekly hours checked against 48h | BR-ATT-011, BR-ATT-014 | 5 | US-ATT-008 | N-day rotating cycles are P2 |
| US-ATT-010 | EP-ATT-02 | As a T&A admin, I want effective-dated schedule assignment by rule (org unit, location, job, employee group, contractual regime) with manual override so that every employee has a schedule on every date. Rules ranked by priority; reacts to People assignment events incl. future/back-dated; publishes assignment status for onboarding readiness | BR-ATT-015 | 8 | US-ATT-009, PPL-EMP, TECH-BUS | T&A-owned (R2-02 §5 #26) |
| US-ATT-011 | EP-ATT-02 | As a T&A admin, I want to bulk-assign schedules from an Excel template and see employees without a schedule so that go-live and reorganisations are fast. Template on the Core import engine with dry-run; "no schedule" exception list | BR-ATT-015 | 5 | US-ATT-010, CORE-IMP | Import template (guide 5) |
| US-ATT-012 | EP-ATT-02 | As a payroll officer, I want STD_DAYS and STD_HOURS per employee and segment, computed with my pay group's standard-day method (fixed, scheduled days, calendar days, fixed hours), so that proration is correct. Uses schedule, holidays and employment dates; full-period value also given; method read from the pay calendar, never set in T&A | BR-ATT-016 | 8 | US-ATT-004, US-ATT-059, US-ATT-062, PAY-CAL | Critical payroll input (R2-02 §5 #25) |

### EP-ATT-03 Attendance capture by import

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-013 | EP-ATT-03 | As an auditor, I want every raw punch stored append-only with UTC + local time + time zone, source type, device/site, enrolment ID, resolved employee, direction, import batch and validation status so that punches are inspection evidence. No update/delete grant at DB level; monthly partitions; tenant-isolation tests | BR-ATT-020, BR-ATT-027, NFR-ATT-005, NFR-ATT-010 | 5 | TECH-RLS | Geo/photo columns reserved for P2 |
| US-ATT-014 | EP-ATT-03 | As a T&A admin, I want effective-dated enrolment mappings (device user ID ↔ employee, per site or device group) maintained on screen and by Excel import so that device-export files resolve to employees. Overlap validation; mapping import template | BR-ATT-022 | 5 | US-ATT-013, CORE-IMP | Needed for device-export files (R2-01) |
| US-ATT-015 | EP-ATT-03 | As a T&A admin, I want punches with unknown or expired enrolment IDs quarantined and resolvable (map then release) so that no punch is lost or misattributed. Quarantine list with filters; bulk resolve; released punches reprocessed; counts feed the pre-close checklist | BR-ATT-022 | 5 | US-ATT-014, US-ATT-030 | |
| US-ATT-016 | EP-ATT-03 | As a T&A admin, I want to import punches from the standard Excel template (employee code or enrolment ID, date, time, direction) with dry-run and validation report so that sites can load attendance without a live device. Re-upload creates 0 duplicate punches (natural key); out-of-period rows reported; affected days queued for processing | BR-ATT-025, BR-ATT-020 | 5 | US-ATT-013, CORE-IMP | QAS-5a: 2,000 employees × 1 month ≤ 10 min |
| US-ATT-017 | EP-ATT-03 | As a T&A admin, I want to import time-device software export files (CSV/Excel) through column-layout profiles (built-in ZKTeco and Hikvision layouts plus tenant-defined mappings of columns, date/time formats and device time zone) so that I upload the file the device software gives me. Profile chosen per site; unparseable rows reported per row; same dedup rules as US-ATT-016 | BR-ATT-025, BR-ATT-020 | 8 | US-ATT-016 | `[high-uncertainty]`: pilot device models and export formats not yet collected; duplicates are removed in the file / dry-run (BR-ATT-026 P2) |
| US-ATT-018 | EP-ATT-03 | As a T&A admin at a site without devices, I want to import whole daily timesheets (employee, date, attendance code or hours) so that those employees still get a timesheet. Imported days marked manual source and respected by processing; reason and batch recorded | BR-ATT-025, BR-ATT-037 | 5 | US-ATT-027, CORE-IMP | R2-06: not all sites have devices |
| US-ATT-019 | EP-ATT-03 | As a payroll officer in a tenant without the T&A module, I want to import period attendance quantities per employee and category so that Payroll receives a standard `CLOSED` Attendance Result version. Same category codes and versioning as US-ATT-063; close-hold protocol applies unchanged; available without T&A entitlement | BR-ATT-025, BR-ATT-076 | 8 | US-ATT-063, US-ATT-065, CORE-IMP | Baseline `B` (R2-18, GAP-060) |
| US-ATT-020 | EP-ATT-03 | As a T&A admin, I want to list import batches and void a wrong batch before close so that I can fix a bad upload. Voiding flags the batch (raw punches are never deleted); affected days reprocessed; not allowed on CLOSED/LOCKED days | BR-ATT-025, BR-ATT-027 | 3 | US-ATT-016, US-ATT-030 | |
| US-ATT-021 | EP-ATT-03 | As the DPO, I want T&A to refuse biometric templates and photos in imports, log access to raw punch data and prove tenant isolation of all `att` data so that PDPL and isolation rules are met. Import rejects biometric/photo columns; sensitive-read logging; cross-tenant negative tests for punches, batches, results | NFR-ATT-008, NFR-ATT-010 | 3 | US-ATT-013, TECH-RLS, CORE-AUDIT | GPS/selfie handling is P2 with mobile |

### EP-ATT-04 Daily timesheet processing

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-022 | EP-ATT-04 | As a T&A admin, I want one effective-dated T&A policy set per legal entity (pairing mode, fixed late/early rule, payable-OT mode, cap mode warn/block, correction limit, negative-leave allowance) so that rules are configurable without code. Seeded defaults; changes versioned | BR-ATT-031, BR-ATT-044, BR-ATT-045 | 3 | TECH-RLS | Option B: one policy set per LE (BR-ATT-083, BR-ATT-034 P2) |
| US-ATT-023 | EP-ATT-04 | As the system, I want to attribute each punch to a work date by the planned shift window with before/after tolerance, including cross-midnight shifts, so that night shifts belong to their start date. Local-time conversion by site time zone; DST-safe; unattributable punches flagged | BR-ATT-030, NFR-ATT-005 | 5 | US-ATT-008, US-ATT-013 | |
| US-ATT-024 | EP-ATT-04 | As the system, I want configurable pairing (first-in/last-out, all pairs, direction-based) that produces `Missing in` / `Missing out` exceptions so that worked time is computed correctly. Mode from the policy set; exceptions visible to ESS and admin | BR-ATT-031 | 5 | US-ATT-022, US-ATT-023 | |
| US-ATT-025 | EP-ATT-04 | As a payroll officer, I want each employee-day to have scheduled hours, worked hours, paid break hours, late and early minutes, absent hours and a worked-day value (0 / 0.5 / 1 / decimal) so that day quantities are reliable. Grace and rounding from shift; half-day thresholds; full test matrix | BR-ATT-032 | 8 | US-ATT-024 | Late/early: one fixed rule per LE (BR-ATT-034 P2) |
| US-ATT-026 | EP-ATT-04 | As a payroll officer, I want night hours inside normal hours (window 22:00–06:00 from parameters) and OT-candidate time segments computed per day so that night premium and OT categorisation have inputs. Window effective-dated; segments split at midnight and window edges | BR-ATT-032 | 5 | US-ATT-025, SAAS-LPS | `[verify-legal]` VN-02 |
| US-ATT-027 | EP-ATT-04 | As a T&A admin, I want a configurable attendance-code catalogue (seeded X, P, Ô, TS, L, CT, WFH, KL, V…) with payroll-category mapping, and day-status precedence when leave, holiday, trip and punches overlap, so that each day carries the right codes. Half-day = two codes; precedence rules tested; VI/EN labels | BR-ATT-033 | 8 | US-ATT-025 | Category list shared with Payroll (risk R4) |
| US-ATT-028 | EP-ATT-04 | As a female employee, I want statutory paid short-time (menstruation 30 min/day for 3 days a month, nursing 60 min/day for a child < 12 months, 1 h less per day from the 7th month of pregnancy, 6th in remote areas) treated as worked time so that I am not marked late or short. Eligibility from People protected facts; menstruation days declared in ESS or by HR; monthly limits enforced | BR-ATT-035 | 8 | US-ATT-025, PPL-EMP | `[verify-legal]` `[high-uncertainty]`: base 5 + 1 step; declaration process and data ownership (Q5) unclear |
| US-ATT-029 | EP-ATT-04 | As a payroll officer, I want days before hire, after termination and during contract suspension marked `Not employed` / `Suspended` so that they are not counted as absence. Dates from People events; back-dated changes requeue days | BR-ATT-039 | 3 | US-ATT-027, PPL-EMP | |
| US-ATT-030 | EP-ATT-04 | As the system, I want an idempotent processing engine that recalculates any open date range / employee set on import, approval, schedule, holiday, assignment or People change and stamps rule and parameter versions so that results are deterministic and reproducible. Dirty-day queue; same inputs + versions give identical output (property tests); never touches CLOSED/LOCKED days | BR-ATT-038, NFR-ATT-006 | 8 | US-ATT-025, TECH-JOB, TECH-BUS | |
| US-ATT-031 | EP-ATT-04 | As the operator, I want nightly full reprocessing of a 10,000-employee tenant to finish in ≤ 30 minutes so that timesheets are fresh every morning. Scheduled job chunked by employee; progress and failure alerts; performance test passed | NFR-ATT-002 | 5 | US-ATT-030, TECH-JOB, TECH-PERF | Online-source latency moot until the connector (R2-01) |
| US-ATT-032 | EP-ATT-04 | As a T&A admin, I want a daily timesheet grid per org unit and date range with statuses, codes and exception filters, drilling down to punches and history, so that I can review attendance. Data-scope filtered; daily exceptions view (R-02) | BR-ATT-033, BR-ATT-037 | 5 | US-ATT-027, CORE-RBAC | |

### EP-ATT-05 Corrections and manual entry

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-033 | EP-ATT-05 | As an employee, I want to submit an attendance correction request (missing punch, wrong time, forgot to punch, on-site at client) with reason and attachment so that my timesheet is fixed after manager approval. `ATT_CORRECTION` workflow type registered; monthly limit N; approved correction stored as a separate record and day recalculated; request on a locked day becomes a retro delta | BR-ATT-036, BR-ATT-027 | 5 | US-ATT-030, US-ATT-068, CORE-WF, CORE-DOC | Approval workflow (guide 5) |
| US-ATT-034 | EP-ATT-05 | As a T&A admin, I want manual entry and bulk edit of daily timesheets (codes, times, hours) with mandatory reason so that I can fix attendance without devices. Before/after values kept; bulk apply to many employee-days; blocked on CLOSED/LOCKED days | BR-ATT-037 | 5 | US-ATT-032, CORE-AUDIT | Kept P1 by R2-01 "manual entry" |
| US-ATT-035 | EP-ATT-05 | As an auditor, I want the full history of an employee-day (raw punches, corrections, admin edits, calculation versions) so that every number is explainable. Read-only timeline; exportable | BR-ATT-027, BR-ATT-037, NFR-ATT-006 | 3 | US-ATT-033, US-ATT-034 | |

### EP-ATT-06 Overtime

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-036 | EP-ATT-06 | As an employee, I want to request OT (date, planned start/end, reason) with my consent recorded so that OT is approved before I work it. `OT` workflow type with configurable levels; category derived and shown; cap check result shown before submit | BR-ATT-040 | 5 | US-ATT-038, US-ATT-042, CORE-WF | `[verify-legal]` consent Art. 107(2) |
| US-ATT-037 | EP-ATT-06 | As a manager, I want to submit OT for many employees at once, and each employee confirms consent in ESS, so that team OT is planned quickly and lawfully. One request per employee created; consent pending blocks approval; per-employee cap result | BR-ATT-040 | 5 | US-ATT-036 | |
| US-ATT-038 | EP-ATT-06 | As a payroll officer, I want every OT minute classified into exactly one of OT-WD, OT-RD, OT-PH, OT-WD-N, OT-RD-N, OT-PH-N, and NW recorded for night work in normal hours, so that OT is paid at the right rate. Uses the employee's rest days and holidays incl. paid-leave days; segments crossing midnight into a holiday split correctly | BR-ATT-041 | 8 | US-ATT-026, US-ATT-004 | Guide reference example (8) |
| US-ATT-039 | EP-ATT-06 | As a payroll officer, I want night OT flagged when the employee did day-time OT on the same day before it so that the night-OT premium base is right. Flag per OT result; cross-midnight case defined and tested | BR-ATT-042 | 5 | US-ATT-038 | `[verify-legal]` Decree 145 Art. 57(3): base 3 + 1 step ("same day" for cross-midnight unclear) |
| US-ATT-040 | EP-ATT-06 | As a payroll officer, I want payable OT per day = min(approved, actual from punches), or approved = payable for punch-exempt employees, with the separate OT cut-off applied, so that only worked and approved OT is paid. Approved windows matched to actual segments; result per category | BR-ATT-044 | 5 | US-ATT-038, US-ATT-022, PPL-EMP | OT detection policy is P2 (BR-ATT-043): pre-approval only |
| US-ATT-041 | EP-ATT-06 | As a T&A admin, I want an OT cap ledger per employee × legal entity × month and year counting approved + pending OT, with effective-dated caps (40h / 200h / 300h, temporary regimes) from parameters, so that caps are always current. Seeded from opening YTD OT; recomputed on any OT change | BR-ATT-045, BR-ATT-046 | 8 | US-ATT-040, SAAS-LPS | Per legal entity only in P1 (R2-08/R2-16; BR-ATT-047 P2) |
| US-ATT-042 | EP-ATT-06 | As HR, I want daily (normal + OT ≤ 12h; rest day/holiday OT ≤ 12h), monthly and annual caps checked on OT submit and approval, warning at a threshold (e.g. 80 %) and blocking at 100 % unless an authorised override with reason is recorded, so that we comply with OT law. Warn or block per policy set; override log | BR-ATT-045, BR-ATT-046 | 8 | US-ATT-041, US-ATT-022 | `[verify-legal]` Art. 107, Decree 145 Arts. 60–61 |
| US-ATT-043 | EP-ATT-06 | As HR, I want OT and night-shift assignment blocked for protected employees (under 18 except permitted jobs, pregnant from the 7th month or 6th in remote areas, raising a child < 12 months) unless recorded consent is allowed, so that we never break protection rules. Checked in OT requests and in schedule assignment; consent recorded with document | BR-ATT-048 | 8 | US-ATT-036, US-ATT-010, PPL-EMP | `[verify-legal]` `[high-uncertainty]`: base 5 + 1 step; consent exceptions and permitted-job list unclear |
| US-ATT-044 | EP-ATT-06 | As a T&A admin, I want to flag an entity or employee group for the 300h regime (sector list from parameters), be reminded to notify the provincial labour authority and record the notice reference, so that the 300h cap applies lawfully. 300h cap active only after the notice is recorded | BR-ATT-049 | 3 | US-ATT-041 | `[verify-legal]` Art. 107(4) |

### EP-ATT-07 Leave configuration, entitlement and ledger

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-045 | EP-ATT-07 | As a T&A admin, I want to configure leave types (paid by employer / SI / unpaid, unit, working vs calendar days, balance-tracked, eligibility, documents required, max per request/year, counts as service time, payroll category, attendance code) so that leave follows company and legal rules | BR-ATT-055 | 5 | US-ATT-027 | |
| US-ATT-046 | EP-ATT-07 | As a tenant, I want the VN leave types seeded at provisioning (annual, personal paid/unpaid by Art. 115, sick, child-sick, maternity, paternity, prenatal, miscarriage, work accident, unpaid by agreement, compensatory code, business trip and WFH as non-leave codes) so that I start compliant. VI/EN labels; tenant may change but not go below legal minimums | BR-ATT-056 | 3 | US-ATT-045, SAAS-LPS | `[verify-legal]` Art. 115, SI Law 2024 |
| US-ATT-047 | EP-ATT-07 | As a T&A admin, I want a leave balance ledger where every movement (opening, accrue, take, reserve, cancel, carry, expire, encash, adjust) is a transaction and I can adjust balances with reason, so that balances are auditable. Balance per type × leave year and as-at date; adjustments audited | BR-ATT-068 | 8 | US-ATT-045, CORE-AUDIT | |
| US-ATT-048 | EP-ATT-07 | As an employee, I want my annual entitlement computed as 12 / 14 / 16 working days by job and personal flags plus 1 day per 5 full years of service so that I get my legal leave. Tenants may grant more, never less (validated); flags from People | BR-ATT-057 | 8 | US-ATT-047, US-ATT-049, PPL-EMP, SAAS-LPS | `[verify-legal]` Arts. 113–114 |
| US-ATT-049 | EP-ATT-07 | As HR, I want service time for seniority and pro-rating to include legally counted periods (sick leave up to the cumulative limit, maternity, agreed unpaid leave up to 1 month a year) and exclude others, per effective-dated parameters, so that seniority days are right. Service start date from People; counted periods from T&A history and opening data | BR-ATT-059 | 8 | US-ATT-047, PPL-EMP, SAAS-LPS | `[verify-legal]` `[high-uncertainty]`: base 5 + 1 step; no pre-go-live absence history (D-09) |
| US-ATT-050 | EP-ATT-07 | As HR, I want a scheduled accrual job (monthly accrual, front-loaded yearly grant or pro-rated grant) with joiner/leaver pro-rating (a month counts if ≥ half its working days were worked) and the seniority bonus on anniversary or year start, so that balances grow automatically. Idempotent per employee-month; ledger entries | BR-ATT-058 | 8 | US-ATT-048, TECH-JOB | `[verify-legal]` Decree 145 Art. 66 |
| US-ATT-051 | EP-ATT-07 | As HR, I want a configurable leave year with carry-forward up to a maximum, an expiry date, and forfeit or payout flag at expiry, so that year-end runs without spreadsheets. Year-end and expiry jobs; payout-flagged days available to Payroll | BR-ATT-060 | 5 | US-ATT-047, TECH-JOB | |
| US-ATT-052 | EP-ATT-07 | As a payroll officer, I want unused annual leave (accrued − taken, incl. unexpired carry-forward, pro-rated to the last working day) and any negative balance computed when an employee leaves so that final pay includes encashment or recovery. Projection on separation event; leave beyond the last working day blocked; LEAVE_ENCASH/RECOVER days published to Payroll and balance to People clearance | BR-ATT-067 | 8 | US-ATT-050, US-ATT-070, PPL-EMP | `[verify-legal]` Art. 113(3); Payroll computes the money (Q7) |

### EP-ATT-08 Leave requests and SI claim data

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-053 | EP-ATT-08 | As an employee, I want to request leave in days, half-days (AM/PM) or hours as the type allows, with duration computed from my schedule and holidays, so that my leave is approved and my timesheet updated. `LEAVE` workflow type registered; working-day vs calendar-day counting; balance consumed on approval | BR-ATT-061 | 5 | US-ATT-045, US-ATT-047, CORE-WF | Guide reference example (5) |
| US-ATT-054 | EP-ATT-08 | As an employee, I want my leave request validated (balance with negative allowance, overlaps, notice period, eligibility, attachments) and the balance reserved while pending so that I cannot over-book. Reservation released on rejection or withdrawal | BR-ATT-062 | 5 | US-ATT-053 | |
| US-ATT-055 | EP-ATT-08 | As HR, I want to enter leave on behalf of employees (including those without ESS access) and correct approved leave via balance adjustment and timesheet edit so that every absence is recorded. On-behalf entry skips approval with reason; correction path documented | BR-ATT-061, BR-ATT-068 | 3 | US-ATT-053, US-ATT-034 | Cancel/shorten with retro is P2 (BR-ATT-066); R3-05 employees without e-mail |
| US-ATT-056 | EP-ATT-08 | As a C&B officer, I want sick, child-sick, maternity, paternity, prenatal and miscarriage requests to capture SI claim data (certificate type and number, facility, diagnosis category, child DOB/ID, birth date, number of children, C-section flag, attachments) so that I can file VSS claims and Payroll gets the same facts. Required fields per type; data in SI absence facts to Payroll | BR-ATT-063 | 5 | US-ATT-053, CORE-DOC | `[verify-legal]` `[high-uncertainty]`: minimal scope (R2-07); exact VSS field list unconfirmed (base 3 + 1 step) |
| US-ATT-057 | EP-ATT-08 | As an employee on maternity leave, I want 6 months (+1 month per additional child) in calendar days and an early return after the legal minimum with notice so that my timesheet and pay restart correctly. Start/expected/actual return dates published to People and Payroll | BR-ATT-065 | 5 | US-ATT-056 | `[verify-legal]` Art. 139 |
| US-ATT-058 | EP-ATT-08 | As an employee, I want to record business trips and WFH days through the leave request flow as non-leave codes (CT, WFH) so that those days count as worked without punches. TRIP_DAYS (domestic/overseas) and WFH_DAYS in the Attendance Result | BR-ATT-056, BR-ATT-033 | 3 | US-ATT-053, US-ATT-027 | Option B workaround: full trip request and `att.BusinessTripApproved` are P2 (BR-ATT-070) |

### EP-ATT-09 Period close and payroll hand-off

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-059 | EP-ATT-09 | As a T&A admin, I want attendance periods generated from Payroll's pay calendar per legal entity × pay group (custom cut-off such as 26th→25th, separate OT cut-off, standard-day method) and a read copy of pay-group membership so that T&A and Payroll periods match. Consumes `pay.PayCalendarPublished` and `pay.PayGroupAssigned`; changes before close re-generate periods | BR-ATT-072 | 5 | PAY-CAL, TECH-BUS | |
| US-ATT-060 | EP-ATT-09 | As a T&A admin, I want the period lifecycle `OPEN → IN_REVIEW → CLOSED → LOCKED` with re-open `CLOSED → OPEN` (reason, only without an active hold) so that payroll always works on a stable version. Timesheet edits blocked outside OPEN; `att.AttendancePeriodReopened` published; every transition audited and permissioned | BR-ATT-073 | 8 | US-ATT-059, CORE-AUDIT | ADR-0019 |
| US-ATT-061 | EP-ATT-09 | As a T&A admin, I want a pre-close exception checklist (missing punches, absent without code, pending leave/OT/correction requests, quarantined punches, cap breaches) with blocking vs warning items and waive-with-reason so that I close only clean periods. Close status per LE × pay group (R-08) | BR-ATT-074 | 5 | US-ATT-060, US-ATT-015, US-ATT-042 | |
| US-ATT-062 | EP-ATT-09 | As a payroll officer, I want each period split into assignment segments whenever legal entity, pay group, cost centre, schedule, employee group or FTE changes, or on hire/termination, so that Payroll prorates per segment. Segments rebuilt on back-dated People changes in open periods | BR-ATT-076 | 5 | US-ATT-059, US-ATT-010, PPL-EMP | |
| US-ATT-063 | EP-ATT-09 | As a payroll officer, I want an immutable, versioned Attendance Result per employee × segment × period with all §7.3 items (STD, WORKED, PAID_LEAVE.type, HOLIDAY, SI_LEAVE.type, UNPAID.type, NOT_EMPLOYED, OT_HOURS.cat with the night sub-flag, NIGHT, SI_NONWORK, late/early/missing counts, SHIFT_COUNT, MEAL_ELIGIBLE, TRIP/WFH, LEAVE_ENCASH/RECOVER) and a header with checksum so that payroll runs on a reproducible snapshot. Each re-close = new version; `att.AttendancePeriodClosed(version, checksum)` | BR-ATT-076 | 8 | US-ATT-062, US-ATT-012, US-ATT-040, US-ATT-027 | SI_NONWORK feeds Payroll's 14-day rule `[verify-legal]` |
| US-ATT-064 | EP-ATT-09 | As the Payroll module, I want a documented, paged read API for a result version and a shared category-code list with contract tests so that the hand-off cannot drift. Consumer-driven contract tests run in CI on both sides | BR-ATT-076 | 5 | US-ATT-063 | Risk R4 (category mismatch) |
| US-ATT-065 | EP-ATT-09 | As the Payroll module, I want a synchronous, idempotent close-hold API (`acquireHold`, `releaseHold(reason)`, `getHold`, keyed on tenant, LE, pay group, period, version, run id) that grants only on a current `CLOSED` version and never expires so that the version I calculated cannot change. Concurrency safe against a parallel re-open; `HoldGranted` / `HoldReleased` events | BR-ATT-079 | 8 | US-ATT-060, US-ATT-063, TECH-BUS | ATT-P-17/18 |
| US-ATT-066 | EP-ATT-09 | As a T&A admin, I want a re-open request on a held period to show the holding run, create a release task for the payroll administrator and run the re-open automatically once the hold is released so that urgent fixes follow the protocol. Re-close publishes version n+1 | BR-ATT-087 | 5 | US-ATT-065, CORE-WF, CORE-NOTIF | |
| US-ATT-067 | EP-ATT-09 | As the system, I want to lock the held version on `PayrollRunStatusChanged(APPROVED)`, return it `LOCKED → CLOSED` on `UNLOCKED`, ignore `REVERSED`, and run a nightly state reconciliation so that T&A and Payroll never disagree. Idempotent handlers; mismatch raised as an incident, not a rejection; `att.AttendancePeriodLocked` | BR-ATT-088 | 8 | US-ATT-065, PAY-RUN, TECH-JOB | |
| US-ATT-068 | EP-ATT-09 | As a payroll officer, I want any change touching a LOCKED period (late correction, late leave, holiday change, back-dated assignment or termination) to produce delta lines (original period, segment, category, ± quantity, reason, source) without touching the locked snapshot so that retro pay is exact. Deltas computed by re-running the locked days against the locked snapshot; repeat changes net correctly | BR-ATT-071, BR-ATT-006 | 8 | US-ATT-063, US-ATT-030 | `[high-uncertainty]`: delta-of-delta and segment changes across periods |
| US-ATT-069 | EP-ATT-09 | As a payroll officer, I want retro lines attached to the next open period's result, or published as an off-cycle result when a leaver has no open period, so that every delta reaches a payroll run. Lines reference the original segment for original rates | BR-ATT-071 | 5 | US-ATT-068 | Retro rule (c) |
| US-ATT-070 | EP-ATT-09 | As a T&A admin, I want to close one leaver's attendance early up to the last working day, producing an individual result version that the final-settlement run can hold, so that final pay is made within 14 working days. Main period close excludes the early-closed employee-days; later changes become retro | BR-ATT-075 | 8 | US-ATT-063, US-ATT-065, US-ATT-052 | `[high-uncertainty]`: interplay of individual and period versions with holds (R3-06) |
| US-ATT-071 | EP-ATT-09 | As a T&A admin, I want to close per LE × pay group as a background job (final processing + snapshot) that finishes in ≤ 15 minutes for 10,000 employees so that payroll starts on time. Progress shown; failure rolls back to IN_REVIEW; performance test passed | BR-ATT-075, NFR-ATT-003 | 5 | US-ATT-063, TECH-JOB, TECH-PERF | |

### EP-ATT-10 Self-service, notifications, reports, retention

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-072 | EP-ATT-10 | As an employee, I want an ESS "My attendance" page (schedule calendar, imported punches, daily timesheet, exceptions with a "request correction" action, request status) in VI/EN so that I can check my attendance. Responsive ≥ 360 px | BR-ATT-080 | 5 | US-ATT-032, US-ATT-033 | No check-in button in P1 (OD-9) |
| US-ATT-073 | EP-ATT-10 | As an employee, I want to see my leave balances with ledger and my OT month/YTD against caps so that I plan leave and OT. As-at balance incl. reserved days | BR-ATT-080 | 3 | US-ATT-047, US-ATT-041 | |
| US-ATT-074 | EP-ATT-10 | As a manager, I want an MSS team timesheet and exceptions page within my data scope so that I can follow up before close. Read-only; approvals stay in the Core inbox | BR-ATT-080, BR-ATT-036 | 3 | US-ATT-032, CORE-RBAC | Who's-in dashboard is P2 (BR-ATT-081) |
| US-ATT-075 | EP-ATT-10 | As an employee or admin, I want T&A notifications (request submitted/approved/rejected, missing-punch reminder after import, OT cap thresholds, balance expiry 30 days before, period close reminders) by e-mail and in-app so that nothing is missed. Templates VI/EN registered on Core; triggers via events | BR-ATT-082 | 5 | CORE-NOTIF, TECH-BUS | Roster publish notice is P2 |
| US-ATT-076 | EP-ATT-10 | As HR, I want the monthly timesheet grid (bảng chấm công) per org unit exported to Excel in the VN standard layout with signature blocks so that I can sign and file it for inspection. Days × codes and totals per category (R-01) | BR-ATT-033, NFR-ATT-007 | 5 | US-ATT-063 | VN-20 record keeping |
| US-ATT-077 | EP-ATT-10 | As HR or an auditor, I want an OT summary (month, YTD, % of 40/200/300h caps, by category) and a cap override log so that we can show OT compliance. R-04, R-05; Excel export | BR-ATT-046, BR-ATT-049 | 5 | US-ATT-042, US-ATT-044 | |
| US-ATT-078 | EP-ATT-10 | As HR, I want a leave balance report as at a date (opening, accrued, taken, carried, expired, closing) and an SI leave register so that I can reconcile balances and file claims. R-06, R-07 | BR-ATT-068, BR-ATT-063 | 3 | US-ATT-047, US-ATT-056 | |
| US-ATT-079 | EP-ATT-10 | As an auditor or payroll officer, I want a raw punch log (by site, employee, batch, status) and a retro adjustments report by original period so that evidence and retro pay are traceable. R-03, R-09 | BR-ATT-020, BR-ATT-071 | 3 | US-ATT-013, US-ATT-069 | |
| US-ATT-080 | EP-ATT-10 | As the DPO, I want T&A retention rules (default 10 years after employment ends, from parameters) and archiving of punches (> 25 months) and timesheets (> 37 months) with immutable locked snapshots so that records survive inspection without bloating the live DB. Archive job via the platform mechanism; restore tested | NFR-ATT-007 | 5 | TECH-JOB, US-ATT-063 | `[verify-legal]` D-04; thresholds D-03 |
| US-ATT-081 | EP-ATT-10 | As a tenant admin, I want T&A permissions (T&A admin per LE, payroll read, manager, employee) and data scopes registered with Core so that each user sees only allowed employees. Permission matrix tested incl. cross-tenant and cross-LE negative tests | NFR-ATT-010 | 3 | CORE-RBAC | |

### EP-ATT-11 Opening balances

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-ATT-082 | EP-ATT-11 | As an implementation consultant, I want an opening leave balance template (employee, leave type, leave year, balance, carry-forward days and expiry) with dry-run and validation so that go-live balances are right. Posts `OPENING` ledger transactions; re-import replaces opening entries until sign-off | BR-ATT-086, BR-ATT-068 | 5 | US-ATT-047, CORE-IMP | Customer prepares files (R2-13) |
| US-ATT-083 | EP-ATT-11 | As an implementation consultant, I want a YTD OT opening template (employee, legal entity, month, OT hours by category) so that monthly and annual caps count pre-go-live OT. Seeds the cap ledger; months from 1 January of the year | BR-ATT-086, BR-ATT-046 | 5 | US-ATT-041, CORE-IMP | Risk R7 |
| US-ATT-084 | EP-ATT-11 | As the customer's C&B lead, I want a reconciliation report of loaded opening balances and YTD OT against my control totals, and a sign-off record that freezes them, so that the opening data is accepted formally. Totals per LE and leave type; sign-off audited | BR-ATT-086 | 3 | US-ATT-082, US-ATT-083 | |

## 3. Coverage check

All 62 P1 IDs from mvp-scope.md §9.3 (55 BR + 7 NFR) are covered. Checked with awk: every P1 ID appears in the BR/NFR refs column of at least one story. Nothing is uncovered.

| P1 ID | Covered by |
|---|---|
| BR-ATT-001 | US-ATT-001, US-ATT-007 |
| BR-ATT-002 | US-ATT-002, US-ATT-003, US-ATT-007 |
| BR-ATT-003 | US-ATT-004 |
| BR-ATT-005 | US-ATT-005 |
| BR-ATT-006 | US-ATT-006, US-ATT-068 |
| BR-ATT-010 | US-ATT-008 |
| BR-ATT-011 | US-ATT-007, US-ATT-009 |
| BR-ATT-014 | US-ATT-009 |
| BR-ATT-015 | US-ATT-010, US-ATT-011 |
| BR-ATT-016 | US-ATT-012 |
| BR-ATT-020 | US-ATT-013, US-ATT-016, US-ATT-017, US-ATT-079 |
| BR-ATT-022 | US-ATT-014, US-ATT-015 |
| BR-ATT-025 | US-ATT-016, US-ATT-017, US-ATT-018, US-ATT-019, US-ATT-020 |
| BR-ATT-027 | US-ATT-013, US-ATT-020, US-ATT-033, US-ATT-035 |
| BR-ATT-030 | US-ATT-023 |
| BR-ATT-031 | US-ATT-022, US-ATT-024 |
| BR-ATT-032 | US-ATT-025, US-ATT-026 |
| BR-ATT-033 | US-ATT-027, US-ATT-032, US-ATT-058, US-ATT-076 |
| BR-ATT-035 | US-ATT-028 |
| BR-ATT-036 | US-ATT-033, US-ATT-074 |
| BR-ATT-037 | US-ATT-018, US-ATT-032, US-ATT-034, US-ATT-035 |
| BR-ATT-038 | US-ATT-030 |
| BR-ATT-039 | US-ATT-029 |
| BR-ATT-040 | US-ATT-036, US-ATT-037 |
| BR-ATT-041 | US-ATT-038 |
| BR-ATT-042 | US-ATT-039 |
| BR-ATT-044 | US-ATT-022, US-ATT-040 |
| BR-ATT-045 | US-ATT-022, US-ATT-041, US-ATT-042 |
| BR-ATT-046 | US-ATT-041, US-ATT-042, US-ATT-077, US-ATT-083 |
| BR-ATT-048 | US-ATT-043 |
| BR-ATT-049 | US-ATT-044, US-ATT-077 |
| BR-ATT-055 | US-ATT-045 |
| BR-ATT-056 | US-ATT-046, US-ATT-058 |
| BR-ATT-057 | US-ATT-048 |
| BR-ATT-058 | US-ATT-050 |
| BR-ATT-059 | US-ATT-049 |
| BR-ATT-060 | US-ATT-051 |
| BR-ATT-061 | US-ATT-053, US-ATT-055 |
| BR-ATT-062 | US-ATT-054 |
| BR-ATT-063 | US-ATT-056, US-ATT-078 |
| BR-ATT-065 | US-ATT-057 |
| BR-ATT-067 | US-ATT-052 |
| BR-ATT-068 | US-ATT-047, US-ATT-055, US-ATT-078, US-ATT-082 |
| BR-ATT-071 | US-ATT-068, US-ATT-069, US-ATT-079 |
| BR-ATT-072 | US-ATT-059 |
| BR-ATT-073 | US-ATT-060 |
| BR-ATT-074 | US-ATT-061 |
| BR-ATT-075 | US-ATT-070, US-ATT-071 |
| BR-ATT-076 | US-ATT-019, US-ATT-062, US-ATT-063, US-ATT-064 |
| BR-ATT-079 | US-ATT-065 |
| BR-ATT-080 | US-ATT-072, US-ATT-073, US-ATT-074 |
| BR-ATT-082 | US-ATT-075 |
| BR-ATT-086 | US-ATT-082, US-ATT-083, US-ATT-084 |
| BR-ATT-087 | US-ATT-066 |
| BR-ATT-088 | US-ATT-067 |
| NFR-ATT-002 | US-ATT-031 |
| NFR-ATT-003 | US-ATT-071 |
| NFR-ATT-005 | US-ATT-013, US-ATT-023 |
| NFR-ATT-006 | US-ATT-030, US-ATT-035 |
| NFR-ATT-007 | US-ATT-076, US-ATT-080 |
| NFR-ATT-008 | US-ATT-021 |
| NFR-ATT-010 | US-ATT-013, US-ATT-021, US-ATT-081 |

**P2 items with a P1 workaround built here (no P2 BR is delivered):** BR-ATT-034 (one fixed late/early rule per LE, US-ATT-022/025), BR-ATT-043 (OT by pre-approval only, US-ATT-040), BR-ATT-066 (HR correction via adjustment, US-ATT-055), BR-ATT-070 (trips and WFH as attendance codes, US-ATT-058), BR-ATT-083 (one policy set per LE, US-ATT-022), BR-ATT-026 (duplicates removed in dry-run, US-ATT-016/017).

## 4. Totals and risk notes

| Epic | Stories | SP |
|---|---|---|
| EP-ATT-01 Holiday calendars | 7 | 34 |
| EP-ATT-02 Shifts, schedules and assignment | 5 | 31 |
| EP-ATT-03 Attendance capture by import | 9 | 47 |
| EP-ATT-04 Daily timesheet processing | 11 | 63 |
| EP-ATT-05 Corrections and manual entry | 3 | 13 |
| EP-ATT-06 Overtime | 9 | 55 |
| EP-ATT-07 Leave configuration, entitlement and ledger | 8 | 53 |
| EP-ATT-08 Leave requests and SI claim data | 6 | 26 |
| EP-ATT-09 Period close and payroll hand-off | 13 | 83 |
| EP-ATT-10 Self-service, notifications, reports, retention | 10 | 40 |
| EP-ATT-11 Opening balances | 3 | 13 |
| **Total** | **84** | **458** |

**Capacity view (guide §6, indicative).** 458 SP ≈ 458 developer-days before the +40 % overhead. At 7 SP per developer per sprint over 6 build sprints (42 SP per developer), T&A alone needs about **11 developers**. This is a scope risk for the 16-week plan; the PM should compare it with the other backlogs before committing.

**Stories marked `[high-uncertainty]`** (ranked by impact):
1. **US-ATT-068 Retro delta computation (8).** Deltas against locked snapshots, repeated changes to the same locked day, and back-dated segment changes across periods. A wrong delta means wrong pay. Mitigation: build early, property tests, joint test pack with the Payroll BA.
2. **US-ATT-070 Early close for a leaver (8).** Individual result versions living next to the period version, each holdable by a different payroll run (final settlement vs monthly). The state model needs a design session with the solution architect (ADR-0019 extension).
3. **US-ATT-017 Device-export file import (8).** Pilot device models, firmware and export layouts are not yet collected. Mitigation: collect sample files from every pilot site in sprint 1; the profile mapping lets new layouts be added without code.
4. **US-ATT-028 Statutory paid short-time entitlements (8).** `[verify-legal]` Art. 137 / Decree 145 Art. 80, and the declaration process and data ownership (Q5) are open.
5. **US-ATT-049 Service time counting (8).** `[verify-legal]` Decree 145 Art. 65; no pre-go-live absence history exists (D-09), so the opening template may need a "service-time adjustment" column.

Also marked: US-ATT-043 protected-employee OT/night block (consent exceptions, permitted-job list) and US-ATT-056 SI claim data (exact VSS field list).

**Other risks.**
- `[verify-legal]` items not marked high-uncertainty (US-ATT-003, 004, 005, 026, 036, 039, 042, 044, 046, 048, 050, 052, 057, 063, 080) assume the BRD figures hold. If the labour consultant changes a rule, re-estimate that story.
- 13 SP was not used. The largest engine-level pieces (daily calculation, Attendance Result, retro) are split into 5- and 8-point stories, so their combined effort is 21–26 SP each.
- Dependency placeholders (`CORE-*`, `PAY-*`, `TECH-*`) must be replaced with real story IDs. The hold API (US-ATT-065) and the result contract (US-ATT-064) are on the payroll critical path and should land by sprint 3.
