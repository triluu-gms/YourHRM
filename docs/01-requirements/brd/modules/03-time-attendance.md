# YourHRM — BRD Module 03: Time & Attendance (`att`)

| Item | Value |
|---|---|
| Module | `time-attendance` — DB schema `att` — requirement prefix `BR-ATT` / `NFR-ATT` |
| Version | Draft v0.3, 2026-10-16 |
| Owner | BA – Time & Attendance |
| Related BRDs | 01-core, 02-people, 04-payroll, 05-recruitment, 06-saas-platform |
| Status | For review by PM, Payroll BA, People BA, Integration analyst |
| Change note | v0.2 (2026-10-16): period close changed to the **payroll close hold** protocol (decision X-03, ADR-0019, [gap-analysis](../gap-analysis.md) §7 v1.1) — P8, BR-ATT-073, new BR-ATT-079/087/088, §6 AttendancePeriod/AttendancePeriodHold, §7.1 ATT-C-19/21, §7.2 ATT-P-01/04, new ATT-P-17/18, §7.3 retro rules. Oracle migration replaced by the generic **opening-balance import** (decision D-09) — BR-ATT-025/068/086, Q6, R7. Edits are marked "(changed 2026-10-16, X-03/D-09)". v0.3 (2026-10-16): applies the approved single-owner table ([gap-analysis](../gap-analysis.md) §5, decision **R2-02**): the standard-day method is Payroll's (BR-ATT-016; #25); pay group comes from `pay.PayGroupAssigned` and schedule assignment is T&A-owned, not a People field (ATT-C-14, BR-ATT-015, P2; #25/#26); legal parameters from the SaaS Legal Parameter Service (ATT-C-03; #1); job scheduler from SaaS, documents and audit from Core (ATT-C-11; #13/#15b/#16); T&A is the single owner of holiday calendars and business trips (ATT-P-16, ATT-P-08; #2/#6); SI contribution years from Payroll (ATT-C-20; #20). R2-16/R2-08: OT caps per legal entity in Phase 1 for concurrent employees (BR-ATT-047 note, Q4). R2-18: the Excel attendance import is a baseline capability (BR-ATT-025). Edits are marked "(changed 2026-10-16, R2-02 §5 #n)" or with the decision ID. Priority and phase columns unchanged (PM syncs). |

> **Phase:** the delivery phase of every requirement is set only in [mvp-scope.md](../mvp-scope.md) §9; MoSCoW here is business priority. (2026-10-16)

Conventions follow the Project Charter §4: MoSCoW priority (`M` = MVP / first paying tenant), every legal figure is an effective-dated parameter and is marked `[verify-legal]` until confirmed by a Vietnam labour consultant. Phase column: `MVP`, `P2` (Phase 2), `P3`.

---

## 1. Purpose & scope

### 1.1 Purpose
Time & Attendance (Chấm công, Nghỉ phép, Làm thêm giờ) records **when each employee was expected to work, when they actually worked, and why they were absent**, applies Vietnamese labour-law rules to that data, and hands a **closed, auditable monthly attendance result** to Payroll. It is the single source of truth for working days, paid/unpaid absence, overtime hours by legal category, night hours and leave balances.

### 1.2 In scope
| # | Capability | Phase |
|---|---|---|
| S1 | Holiday calendars per tenant / legal entity: VN public holidays incl. Tết (lunar) dates, substitute (compensatory) days, government-announced day swaps, company holidays | MVP |
| S2 | Work schedules: fixed office, 5-day, 6-day and 5.5-day weeks, shifts, split shifts, night shifts, cross-midnight shifts, flexible / core-hours, rotating patterns | MVP (rotating/split: P2) |
| S3 | Shift rosters: planning, publishing, shift swap, roster-vs-actual | P2 (basic assignment of a shift pattern: MVP) |
| S4 | Check-in sources: biometric / face devices (ZKTeco, Hikvision …), mobile app with GPS geofence / Wi-Fi SSID / selfie, web check-in, file import, manual entry | MVP (devices via import/connector, web, import); mobile & push connectors: P2 |
| S5 | Raw punch processing into daily timesheets: punch pairing, late / early-leave, missing punch, absent, attendance correction requests | MVP |
| S6 | Overtime: pre-approved requests, detected OT, OT categories (weekday / weekly rest day / public holiday / night / night OT), legal caps (daily, monthly, annual 200h/300h), warnings and blocking | MVP |
| S7 | Leave: types (annual + seniority, sick (SI-paid), maternity/paternity, marriage, bereavement, unpaid, compensatory, others), requests, balances, accrual, carry-forward, expiry, encashment on termination | MVP |
| S8 | Business trips (công tác) and remote work / WFH requests and their effect on the timesheet | MVP (request + timesheet effect), P2 (geo-verified remote check-in) |
| S9 | Monthly timesheet review, closing, locking, payroll hand-off, retro corrections after lock | MVP |
| S10 | ESS / MSS self-service screens, T&A reports and dashboards | MVP |

### 1.3 Out of scope
- Money: OT pay, leave encashment amounts, allowances, deductions are **calculated by Payroll** (`pay`). T&A only provides quantities (hours, days, counts).
- SI benefit claims (sick, maternity payments by the SI fund) — Payroll/Insurance (`pay`) prepares the SI dossiers; T&A provides the absence facts.
- Business-trip expenses / per-diem claims — People (`hr`, expenses).
- Workflow engine, notifications, org tree, users/roles — Core.
- Device firmware management, access-control (door) logic, canteen systems.
- Project / task time-tracking (timesheets for billing) — out of scope; may be P3.
- Workforce demand forecasting and auto-roster optimisation — P3.

---

## 2. Actors & personas

| Actor | Description | Key goals in T&A |
|---|---|---|
| Employee (ESS) | Any employee, office or factory | Check in/out, see own timesheet, request leave / OT / correction / trip / WFH, see balances |
| Line manager (MSS) | Direct or functional manager from People assignments | Approve requests, review team timesheet, see who is absent today |
| Shift supervisor / roster planner | Factory, retail, hospitality | Build and publish rosters, handle swaps, cover gaps |
| T&A administrator (HR / C&B) | Per legal entity | Configure holidays, schedules, rules, leave policies; resolve exceptions; close period |
| Payroll officer | C&B | Consumes the closed timesheet; requests re-open / retro correction |
| Tenant administrator | Customer IT/HR lead | Configure devices, geofences, integration credentials, feature settings |
| Time-capture device / connector | ZKTeco, Hikvision, other terminals; mobile app | Push or deliver raw punches |
| System scheduler | Background jobs | Punch processing, accrual, carry-forward, auto-close reminders |
| Auditor / labour inspector (indirect) | Internal or MOLISA / Department of Home Affairs inspection | Evidence of OT caps, rest periods, leave granted |
| SaaS operator | Platform team | Maintain default VN holiday/legal parameter packs, monitor connectors |

---

## 3. Business processes

### P1 — Annual holiday calendar setup
1. Platform publishes the default **Vietnam public-holiday pack** for year N (solar and lunar dates converted to Gregorian; Tết dates; Hùng Kings day).
2. When the Government announces day swaps / Tết schedule for public-sector workers, the platform updates the pack as "announced" (informational for private sector).
3. T&A admin of each legal entity reviews, accepts or adapts (e.g., chooses the extra National Day date, adds company holidays, applies an optional day swap with a make-up working day).
4. System computes **substitute days** where a public holiday falls on the employee's weekly rest day (per work schedule, not per calendar weekday).
5. Calendar is published; changes after a period is locked generate retro corrections (P9).
- Exception: holiday added mid-year by law → new effective-dated holiday, recalculation of open periods, retro for locked ones.

### P2 — Work schedule and roster assignment
1. Admin defines shift definitions (start, end, breaks, night window overlap, grace periods, rounding) and schedule patterns (weekly or N-day cycle).
2. T&A assigns the schedule pattern to each assignment by rule (org unit, location, job, employee group) or manually; People holds only the contractual working-time regime (full/part-time, hours/week), which the rule may use (changed 2026-10-16, R2-02 §5 #26).
3. For rostered populations, planner builds a roster for a period, validates it (rest rules, night-work restrictions for protected employees, 12h between shifts), publishes it; employees are notified.
4. Employees request shift swaps; supervisor approves; roster updates.
- Exception: roster not published by the cut-off → fall back to default pattern and flag.

### P3 — Punch capture and processing
1. Punches arrive from devices (pull/push connector or file), mobile (GPS/Wi-Fi/selfie), web, or import.
2. Each punch is stored immutably as raw data with source, device, timestamp (device local time and UTC), and validation result (inside geofence, recognised Wi-Fi, face match score).
3. Punch is mapped to the employee via enrolment ID → employee mapping, valid at that date.
4. Processing job (near-real-time and nightly) attributes punches to a **work date** (shift-based, handles cross-midnight), pairs in/out, computes worked time, late/early minutes, night minutes, OT candidates.
5. Daily timesheet is created/updated with status: Normal / Late / Early / Missing punch / Absent / On leave / Holiday / Rest day / Business trip / WFH.
- Exceptions: unknown enrolment ID → quarantine queue; duplicated punches within X min → collapsed; device clock drift → flagged.

### P4 — Attendance exception & correction
1. Employee sees exceptions in ESS (missing punch, late) and submits a **correction request** (giải trình) with reason and optional attachment.
2. Manager approves via Core workflow; daily timesheet recalculated; original values retained in history.
3. Admin can bulk-correct with mandatory reason.
- Exception: request after the period cut-off → routed as retro correction (P9).

### P5 — Overtime
1. **Pre-approved**: employee or manager submits OT request (date, planned hours, reason, OT type is derived). System checks legal caps and protected-employee rules → warn or block. Employee consent captured. Approval via workflow.
2. **Detected**: processing finds work beyond scheduled hours (beyond threshold). Depending on policy: (a) ignored unless pre-approved, (b) created as "OT pending confirmation" for manager, (c) auto-approved up to N minutes.
3. Approved OT is matched with actual punches: **payable OT = min(approved, actual)** unless policy says otherwise; split into categories by the time segments (day/night, weekday/rest day/holiday).
4. Employee may elect **time-off-in-lieu (TOIL)** instead of pay where policy allows → compensatory leave balance credited instead of paid OT.
- Exception: cap would be exceeded → block (hard cap) or require HR-level approval (soft cap, configurable); 300h regime only for entities flagged as eligible and registered.

### P6 — Leave request
1. Employee selects leave type, dates (full day, half day AM/PM, or hours if type allows); system shows balance, working-day count per schedule (excludes rest days/holidays unless the type counts calendar days, e.g., maternity).
2. Validation: balance, eligibility (gender, seniority, probation), notice period, overlapping requests, documents required (e.g., medical certificate for sick leave).
3. Workflow approval; balance is reserved on submit and consumed on approval; calendar and timesheet updated.
4. Cancellation / partial cancellation after approval returns balance; if in a locked period → retro.

### P7 — Leave accrual, carry-forward and year-end
1. Monthly (or yearly front-loaded) accrual job grants annual leave per policy and seniority; pro-rates for joiners/leavers.
2. Seniority bonus (+1 day per 5 years) granted on anniversary or year start per policy.
3. At leave-year end: carry forward up to configured max; set expiry date (e.g., 31 March); forfeit / pay out the remainder per policy.
4. Balances adjustable by admin with reason (audit).

### P8 — Monthly timesheet close and payroll hand-off
1. At period end, system runs final processing; exception dashboard lists unresolved items (missing punches, pending requests, unapproved OT).
2. Managers confirm team timesheets (optional step); employees may confirm own timesheet (optional, configurable).
3. T&A admin **closes** the period per pay group / legal entity → status `CLOSED`; data snapshot (**Attendance Result**) is versioned and published to Payroll (`att.AttendancePeriodClosed`, version n).
4. Payroll calculates on version n. When Payroll **submits** the run for approval it acquires a **close hold** on version n (synchronous T&A API, idempotent on run id + version) → `att.AttendancePeriodHoldGranted`. The period stays `CLOSED` but is *held*.
5. Payroll run **approved** → T&A sets the held version `CLOSED → LOCKED` (hard) → `att.AttendancePeriodLocked`. Because the version is held, the lock cannot fail.
6. Payroll run **rejected, withdrawn or cancelled** → Payroll releases the hold → `att.AttendancePeriodHoldReleased`; the period is a plain `CLOSED` period again.
7. Re-open `CLOSED → OPEN` is allowed for T&A admin with reason **only when no hold is active**. If the period is held, T&A refuses the re-open and raises a release request to the payroll administrator (Core task); Payroll releases the hold (run back to `CALCULATED`, marked stale), then the re-open proceeds and the re-close publishes version n+1.
8. `LOCKED → CLOSED` happens only on a privileged Payroll **unlock** of the approved run (second approval). (changed 2026-10-16, X-03)

### P9 — Retro correction after lock
1. Any change touching a `Locked` period (approved late correction, late leave, holiday change, cancelled leave) does **not** alter the locked snapshot.
2. System computes a **delta record** (original period, item, category, +/- quantity) and attaches it to the next open period's Attendance Result as retro lines.
3. Payroll pays/recovers the delta in the next run.

### P10 — Hire, transfer, termination within a period
1. Hire mid-period: timesheet starts at hire date; non-employed days are `Not employed` (not absence).
2. Transfer (org, legal entity, schedule, pay group): timesheet is **segmented by assignment**; each segment carries its own schedule and cost centre.
3. Termination: on notification of last working day, system projects leave balance at last day, blocks leave beyond it, and after final processing provides **unused annual leave days for encashment** and any negative balance for recovery.

### P11 — Business trip and remote work
1. Employee submits business-trip request (dates, destination, purpose, half-day granularity) or WFH request; manager approves.
2. Timesheet marks those days as worked (`Business trip` / `WFH`) without punches required (configurable: require mobile check-in for WFH).
3. Trip days and destination (domestic/overseas) are provided to People (expenses/per-diem). OT on trip only if separately approved.

---

## 4. Business requirements

### 4.1 Holiday calendars
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-001 | The system shall maintain holiday calendars per tenant with assignment per legal entity (and optionally per location), each with effective-dated entries for public holidays, substitute days, company holidays and day swaps. | M | MVP | Legal entity from Core |
| BR-ATT-002 | The system shall provide a platform-maintained default Vietnam public-holiday pack per year, including lunar-date holidays (Tết, Hùng Kings) converted to Gregorian dates, which tenants inherit and may override. | M | MVP | Pack published by SaaS platform |
| BR-ATT-003 | When a public holiday falls on an employee's weekly rest day as defined by **that employee's schedule**, the system shall generate a substitute day off on the next working day (configurable rule per entity). | M | MVP | Labour Code Art.112(3) [verify-legal] |
| BR-ATT-004 | The system shall support a day swap (move a working day to bridge a holiday) with a corresponding make-up working day, applicable per legal entity or employee group. | S | P2 (changed 2026-10-16, X-04) | |
| BR-ATT-005 | The system shall support additional holidays for foreign employees (1 traditional new-year day + 1 national day of their country) driven by nationality from People. | S | MVP | Art.112(2) [verify-legal] |
| BR-ATT-006 | Any change to a holiday calendar affecting an open period shall trigger recalculation; affecting a locked period shall trigger retro deltas (BR-ATT-071). | M | MVP | |

### 4.2 Work schedules and rosters
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-010 | Admin shall define shift definitions: start/end time, cross-midnight flag, paid/unpaid breaks (fixed or floating), core hours, grace minutes for late/early, rounding rules, minimum minutes to count as half/full day, standard hours credited. | M | MVP | |
| BR-ATT-011 | Admin shall define schedule patterns: weekly (5-day, 6-day, 5.5-day with half Saturday, alternating Saturdays) and N-day rotating cycles with an anchor date. | M | MVP (rotating: P2) | |
| BR-ATT-012 | The system shall support flexible schedules: required hours per day/week with optional core hours, where lateness is measured against core hours only. | S | P2 (changed 2026-10-16, X-04) | |
| BR-ATT-013 | The system shall support split shifts (two or more work blocks per day) and cross-midnight night shifts, attributing all punches to the shift's work date. | S | P2 | |
| BR-ATT-014 | Each schedule pattern shall define the employee's **weekly rest day(s)**, used for OT categorisation and substitute-holiday rules; rest day may differ from Sunday. | M | MVP | Art.111 |
| BR-ATT-015 | The system shall own **schedule assignment**: assign an effective-dated schedule to each employee assignment from rules (org unit, location, job, employee group, People's contractual regime) or manually; People does not supply a default schedule (changed 2026-10-16, R2-02 §5 #26). | M | MVP | Status feeds People onboarding readiness |
| BR-ATT-016 | The system shall compute **standard working days and standard hours per employee per period** (ngày công chuẩn) from the schedule, holiday calendar and employment dates, using the **method of the employee's pay group** received in the pay calendar (`pay.PayCalendarPublished.std_day_method`, ATT-C-18). T&A has no method setting of its own; the method is owned by Payroll (BR-PAY-009) (changed 2026-10-16, R2-02 §5 #25). | M | MVP | Critical payroll input |
| BR-ATT-017 | Roster planners shall create, copy, validate and publish rosters per org unit/location for a period; employees are notified on publish and on change. | S | P2 | |
| BR-ATT-018 | Roster validation shall flag: < 12h rest between shifts, no 24h weekly rest (or < 4 days/month average), night shifts for protected employees, roster hours beyond legal normal hours (8h/day or 10h/day weekly-mode, 48h/week). | S | P2 | Arts.105,110,111,137 [verify-legal] |
| BR-ATT-019 | Employees shall request shift swaps with a colleague (same qualification group); both parties and the supervisor approve. | C | P2 | |

### 4.3 Check-in sources and raw punches
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-020 | The system shall store every raw punch immutably with: tenant, enrolment ID, employee (resolved), timestamp (UTC + local + device time zone), source type, device/app ID, direction (in/out/unknown), verification method, geo/Wi-Fi data, photo reference, received-at, and validation status. | M | MVP | |
| BR-ATT-021 | The system shall integrate with biometric/face terminals (ZKTeco, Hikvision as first targets) via (a) an on-premise connector agent that pulls/pushes logs over HTTPS and (b) file export import (CSV/Excel/DAT). | M | P2 (changed 2026-10-16, R2-01: live connector P2; file import via BR-ATT-025) | Device registry per tenant |
| BR-ATT-022 | The system shall maintain enrolment mappings (device user ID ↔ employee) effective-dated, and quarantine punches with unknown or expired mappings for admin resolution. | M | MVP | |
| BR-ATT-023 | Mobile check-in shall validate location against geofences (lat/long + radius) per location and/or approved Wi-Fi SSID/BSSID, optionally with selfie + liveness; out-of-fence punches are accepted as "needs review" or rejected per policy. | S | P2 | Location coordinates from Core |
| BR-ATT-024 | Web check-in shall be allowed per employee group and optionally restricted by IP range. | S | P2 (changed 2026-10-16, X-04/OD-9) | |
| BR-ATT-025 | Admin shall import punches or whole daily timesheets from Excel templates with validation report and dry-run. | M | MVP | Also the Attendance Result import for Payroll-without-T&A tenants (GAP-060); no legacy migration (changed 2026-10-16, D-09). The **Excel attendance import** is a **baseline capability**, always on whatever modules the tenant buys (R2-18); it produces a standard `CLOSED` Attendance Result version so the close-hold protocol applies unchanged. Import of time-device software export files (CSV/Excel with a device column layout) uses the same import (R2-01) (changed 2026-10-16, R2-18) |
| BR-ATT-026 | Duplicate punches within a configurable window (default 1 min) from the same employee shall be collapsed; detection of device clock drift > N minutes shall raise an alert. | M | P2 (changed 2026-10-16, R2-19) | |
| BR-ATT-027 | Raw punches shall never be edited or deleted by users; corrections are separate records linked to the daily timesheet. | M | MVP | Audit / inspection evidence |

### 4.4 Daily timesheet processing
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-030 | The system shall attribute punches to a work date by the planned shift window (with configurable before/after tolerance), supporting cross-midnight shifts. | M | MVP | |
| BR-ATT-031 | Pairing rules shall be configurable: first-in/last-out, all pairs, or direction-based; unpaired punches yield `Missing in`/`Missing out` exceptions. | M | MVP | |
| BR-ATT-032 | For each employee-day the system shall compute: scheduled hours, worked hours, paid break hours, late minutes, early-leave minutes, absent hours, night hours (22:00–06:00 window parameter), OT candidate minutes, worked-day value (0 / 0.5 / 1 or decimal). | M | MVP | Night window [verify-legal] |
| BR-ATT-033 | Each day shall carry a day status and one or more **attendance codes** (e.g., X = worked, P = annual leave, Ô = sick, TS = maternity, L = holiday, CT = trip, WFH, KL = unpaid, V = absent without leave), configurable per tenant with a mapping to payroll categories. | M | MVP | Codes familiar to VN users |
| BR-ATT-034 | Late/early tolerances and penalties shall be policy-driven (e.g., grace 5 min, after which deduct by minute, by block of 15/30 min, or count as half-day absence); T&A outputs minutes and counts, not money. | M | P2 (changed 2026-10-16, R2-19) | |
| BR-ATT-035 | Statutory paid short-time entitlements shall be treated as worked time: female employees during menstruation (30 min/day, 3 days/month), nursing a child under 12 months (60 min/day), pregnant employee from 7th month (or 6th in remote areas) working 1h less per day. | M | MVP | Arts.137, Decree 145 Art.80 [verify-legal]; needs People data |
| BR-ATT-036 | Employees shall submit attendance correction requests (missing punch, wrong time, forgot to check in, on-site at client) with reason and attachment; approval via Core workflow; limit of N requests per month configurable. | M | MVP | |
| BR-ATT-037 | Admin shall be able to bulk-edit daily timesheets with mandatory reason, retaining before/after values in history. | M | MVP | |
| BR-ATT-038 | Processing shall be re-runnable for any open date range/employee set and be idempotent; recalculation triggers on punch arrival, request approval, schedule/holiday/assignment change. | M | MVP | |
| BR-ATT-039 | Days outside the employee's employment (before hire, after termination, during contract suspension) shall be marked `Not employed` / `Suspended`, distinct from absence. | M | MVP | Dates from People |

### 4.5 Overtime
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-040 | Employees or managers (on behalf of one or many employees) shall submit OT requests with date, planned start/end, reason and employee consent flag; approval via Core workflow with configurable levels. | M | MVP | Consent: Art.107(2) |
| BR-ATT-041 | The system shall classify every OT minute into exactly one category: **OT-WD** weekday day, **OT-RD** weekly rest day day, **OT-PH** public holiday / paid leave day day, **OT-WD-N**, **OT-RD-N**, **OT-PH-N** (night OT), and separately record **NW** night work within normal hours (non-OT). | M | MVP | Rates are Payroll parameters |
| BR-ATT-042 | For night OT, the system shall flag whether the employee performed day-time OT on the same day before the night OT, because the night-OT premium base differs. | M | MVP | Decree 145 Art.57(3) [verify-legal] |
| BR-ATT-043 | OT detection policy shall be configurable per employee group: (a) pre-approval only, (b) detected OT requires manager confirmation, (c) auto-approve up to N minutes; minimum OT block and rounding (e.g., 15/30 min) configurable. | M | P2 (changed 2026-10-16, R2-19) | |
| BR-ATT-044 | Payable OT per day = min(approved OT, actual OT from punches) by default; policy may allow "approved = payable" for employees exempt from punching. | M | MVP | |
| BR-ATT-045 | The system shall enforce the **daily cap**: normal hours + OT ≤ 12h/day (weekday); on rest days/holidays OT ≤ 12h; configurable as warn or block. | M | MVP | Decree 145 Art.60 [verify-legal] |
| BR-ATT-046 | The system shall enforce the **monthly cap** (40h) and **annual cap** (200h default; 300h for entities/employee groups flagged as eligible sectors), counting approved + pending OT; warn at configurable thresholds (e.g., 80%) and block at 100% unless an authorised override with reason is recorded. | M | MVP | Art.107(2b,3); Decree 145 Art.61 [verify-legal]; caps are effective-dated (cf. 2022 temporary 60h/month) |
| BR-ATT-047 | The annual OT cap shall be measured per calendar year across all assignments and legal entities of the same employee within the tenant **and** per legal entity, as configured. | S | P2 (changed 2026-10-16, X-04) | Q4. In Phase 1 concurrent employment across legal entities is supported (R2-16), but the monthly and annual caps are measured **per legal entity** only; the cross-entity per-person measure waits for legal advice (R2-08) (changed 2026-10-16, R2-16) |
| BR-ATT-048 | The system shall block OT and night-shift assignment for protected employees: under-18 (except permitted list), pregnant from 7th month (6th in remote areas), employees raising a child under 12 months — unless the employee has given recorded consent where law allows. | M | MVP | Arts.137, 146 [verify-legal] |
| BR-ATT-049 | Where the 300h regime is used, the system shall remind admin to notify the provincial labour authority and record the notification reference. | S | MVP | Art.107(4) [verify-legal] |
| BR-ATT-050 | Employees shall be able to choose, where policy allows, TOIL (compensatory leave) instead of OT pay per OT request; TOIL hours credit the compensatory leave balance at 1:1 (configurable) and are excluded from payable OT output. | S | P2 (changed 2026-10-16, X-04) | |
| BR-ATT-051 | The system shall alert when an employee has worked OT for many consecutive days without the compensatory rest required by law. | C | P2 | Decree 145 Art.62 [verify-legal] |

### 4.6 Leave management
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-055 | Admin shall configure leave types with: paid by (employer / SI fund / unpaid), unit (day, half-day, hour), counting basis (working days vs calendar days), balance-tracked flag, eligibility (gender, tenure, probation, employee group), documents required, max per request/year, whether it counts toward annual-leave service time, payroll category mapping. | M | MVP | |
| BR-ATT-056 | Seeded VN leave types: Annual leave; Personal paid leave — own marriage (3d), child's marriage (1d), death of parent/parent-in-law/spouse/child (3d); Personal unpaid — death of grandparent/sibling, marriage of parent/sibling (1d); Sick (SI); Child-sick care (SI); Maternity (SI); Paternity (SI); Prenatal check-up (SI); Miscarriage/abortion (SI); Work accident/occupational disease treatment; Unpaid leave by agreement; Compensatory (TOIL); Business trip and WFH (non-leave codes). | M | MVP | Art.115, SI Law [verify-legal] |
| BR-ATT-057 | Annual leave entitlement shall be 12 working days/year by default, 14 for minors / disabled / heavy-hazardous jobs, 16 for especially heavy-hazardous jobs, plus 1 day per 5 full years of service; tenants may grant more but not less. | M | MVP | Arts.113, 114 [verify-legal]; job flags from People |
| BR-ATT-058 | Accrual methods: monthly accrual, front-loaded yearly grant, or pro-rated grant; pro-rating for joiners/leavers per Decree 145 (month counts if the employee worked ≥ half of the month's working days). | M | MVP | Decree 145 Art.66 [verify-legal] |
| BR-ATT-059 | Service time for seniority days and pro-rating shall include legally counted periods (e.g., sick leave up to the legal cumulative limit, maternity leave, agreed unpaid leave up to 1 month/year) and exclude others, per effective-dated parameters. | M | MVP | Decree 145 Art.65 [verify-legal] |
| BR-ATT-060 | Leave year shall be configurable (calendar year default); carry-forward with max days and expiry date; expired balances are forfeited or flagged for payout per policy. | M | MVP | |
| BR-ATT-061 | Employees shall request leave in days, half-days (AM/PM) or hours as allowed by the type; the system computes duration from the schedule (excluding rest days and holidays for working-day types; including them for calendar-day types such as maternity). | M | MVP | |
| BR-ATT-062 | On submission the system shall validate balance (with optional negative balance up to N days), overlaps with other requests/trips, notice period, eligibility, and required attachments; balance is reserved while pending. | M | MVP | |
| BR-ATT-063 | Sick-leave, child-sick and maternity requests shall record the data needed for SI claims: certificate type and number, issuing facility, diagnosis category, child's DOB/ID, birth date, number of children born, C-section flag, and attachments. | M | MVP (changed 2026-10-16, R2-07, minimal) | Needed by Payroll/Insurance; SI Law 2024 [verify-legal] |
| BR-ATT-064 | The system shall track SI-paid sick leave days used per calendar year against the SI entitlement (e.g., 30/40/60 working days by contribution years; higher for heavy jobs) as an information/warning, not a hard block. | S | P2 (changed 2026-10-16, X-04) | Contribution years from Payroll [verify-legal] |
| BR-ATT-065 | Maternity leave (default 6 months; +1 month per additional child) shall support early return to work after the legal minimum, with notification and effect on the timesheet. | M | MVP | Art.139 [verify-legal] |
| BR-ATT-066 | Approved leave can be cancelled or shortened by the employee (before start) or by HR (anytime) with reason; balance is restored; if the day lies in a locked period a retro delta is created. | M | P2 (changed 2026-10-16, R2-19) | |
| BR-ATT-067 | On termination, the system shall compute unused annual leave (accrued − taken, incl. carried-forward not yet expired and pro-rated to last working day) and negative balance, and publish them to Payroll and People (separation clearance). | M | MVP | Art.113(3) [verify-legal] |
| BR-ATT-068 | Admin shall adjust balances manually (opening balances loaded by the opening-balance import (changed 2026-10-16, D-09), corrections, grants) with reason; every balance movement is a ledger transaction (accrual, take, cancel, carry, expire, encash, adjust). | M | MVP | |
| BR-ATT-069 | Team leave calendar for managers and employees (respecting data scope) shall show approved/pending absences and highlight minimum staffing breaches per org unit. | S | P2 (changed 2026-10-16, X-04) | |

### 4.7 Business trips and remote work
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-070 | Employees shall request business trips (dates, half-day, destination domestic/overseas, purpose, project/cost centre) and WFH days; approval via Core workflow; approved days count as worked and need no punches unless policy requires mobile check-in. T&A is the **single owner of business trips**; approved, changed and cancelled trips publish `att.BusinessTripApproved` (ATT-P-08) for People's expenses/per-diem (changed 2026-10-16, R2-02 §5 #6). | M | P2 (changed 2026-10-16, R2-19) | Expenses handled by People |

### 4.8 Period close, payroll hand-off and retro
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-071 | Changes affecting a `Locked` period shall never mutate the locked snapshot; the system shall generate retro delta lines (original period, employee, assignment segment, category, delta quantity, reason, source document) attached to the next open period's Attendance Result. | M | MVP | Core of payroll retro |
| BR-ATT-072 | Attendance periods shall align with Payroll's pay calendar per pay group (calendar month or custom cut-off, e.g., 26th → 25th); T&A shall support a separate OT cut-off if the pay group defines one. | M | MVP | Pay calendar owned by Payroll |
| BR-ATT-073 | Period lifecycle: `OPEN` → `IN_REVIEW` → `CLOSED` (versioned Attendance Result published to Payroll; may be *held* by a submitted payroll run) → `LOCKED` (held version consumed by an **APPROVED** payroll run). Re-open `CLOSED`→`OPEN` requires T&A admin + reason, is allowed only when no close hold is active (BR-ATT-079), and publishes `att.AttendancePeriodReopened`. `LOCKED`→`CLOSED` is possible only through a Payroll unlock (BR-ATT-088); otherwise `LOCKED` is final and later changes become retro deltas (BR-ATT-071). (changed 2026-10-16, X-03) | M | MVP | Unified states, gap-analysis §7.1 |
| BR-ATT-074 | Before close the system shall show an exception checklist (missing punches, absent without code, pending leave/OT/correction requests, quarantined punches, cap breaches) and allow close only when blocking exceptions are cleared or explicitly waived. | M | MVP | |
| BR-ATT-075 | Close shall be possible per legal entity × pay group, and for an individual employee early (e.g., terminations requiring final pay before period end). | M | MVP (changed 2026-10-16, R3-06) | |
| BR-ATT-076 | The published **Attendance Result** shall contain, per employee × assignment segment × period, the quantities listed in §7.3, versioned (each re-close produces a new version; Payroll is told which version is current). | M | MVP | |
| BR-ATT-077 | Optional employee confirmation of monthly timesheet (ESS) with a configurable window; disputes create correction requests. | C | P2 (changed 2026-10-16, X-04) | |
| BR-ATT-078 | Manager timesheet sign-off per team before HR close (configurable). | S | P2 (changed 2026-10-16, X-04) | |
| BR-ATT-079 | **Close hold.** T&A shall provide a synchronous, idempotent API for Payroll to *acquire* and *release* a hold on a specific Attendance Result version (key: tenant, legal entity, pay group, period, result version, run id). Acquire succeeds only if the period is `CLOSED` and the version is current; it is refused otherwise (no orphan holds). While a hold is active T&A refuses re-open and any in-place change of that version. Holds never expire by time; only Payroll actions release them (rejection, withdrawal, cancellation, release request, unlock). Each grant/release publishes `att.AttendancePeriodHoldGranted` / `att.AttendancePeriodHoldReleased`. (new 2026-10-16, X-03) | M | MVP | ADR-0019; ATT-P-17/18 |
| BR-ATT-087 | **Re-open of a held period.** A T&A admin re-open request on a held period shall not be executed directly: the system shall show the holding run, create a release request task for the payroll administrator (Core workflow / notification), and execute the re-open automatically once the hold is released. (new 2026-10-16, X-03) | M | MVP | Payroll run returns to `CALCULATED` with stale flag |
| BR-ATT-088 | **Lock and unlock.** On `pay.PayrollRunStatusChanged(APPROVED)` T&A shall lock the held version (`CLOSED → LOCKED`, idempotent, publish `att.AttendancePeriodLocked`); a version mismatch shall never occur and, if detected, is raised as a reconciliation incident, not a rejection. On `pay.PayrollRunStatusChanged(UNLOCKED)` T&A shall return the period `LOCKED → CLOSED` (same version, hold released) — the only reverse path. A payroll **reversal** leaves T&A untouched. A nightly reconciliation job compares run and period states and alerts on mismatches. (new 2026-10-16, X-03) | M | MVP | ADR-0019 |

### 4.9 Self-service, notifications and configuration
| ID | Requirement | Pri | Phase | Notes |
|---|---|---|---|---|
| BR-ATT-080 | ESS shall show the employee's schedule, punches, daily timesheet, exceptions, balances (with ledger) and request status, in Vietnamese and English. | M | MVP | |
| BR-ATT-081 | MSS shall show team "who's in / late / absent / on leave today", pending approvals, team OT vs caps. | M | P2 (changed 2026-10-16, X-04) | |
| BR-ATT-082 | Notifications via Core: request submitted/approved/rejected, missing punch reminder (next morning), OT cap thresholds, roster publish, balance expiry reminder (e.g., 30 days before), period close reminders. | M | MVP | |
| BR-ATT-083 | All T&A policies (grace, rounding, OT policy, leave policy) shall be effective-dated and assignable by legal entity, employee group, org unit or individual with defined precedence. | M | P2 (changed 2026-10-16, R2-19) | |
| BR-ATT-084 | Approval delegation and out-of-office of approvers handled by Core workflow; T&A shall auto-escalate requests pending past N days before period close. | S | P2 (changed 2026-10-16, X-04) | |
| BR-ATT-085 | Public APIs shall allow external systems to push punches and read daily timesheets/balances (tenant-scoped API credentials). | S | P2 | |
| BR-ATT-086 | Opening-balance import: load opening leave balances (per leave type and leave year, incl. carry-forward and expiry) and YTD OT hours (for the monthly/annual cap) per employee from the generic opening-balance Excel templates (Core import engine), with validation report, dry-run and reconciliation sign-off. No legacy Oracle migration and no historical timesheet load. (changed 2026-10-16, D-09) | M | MVP | YTD OT essential for cap |

Total business requirements: 77.

---

## 5. Vietnam compliance rules

All values are **effective-dated parameters** (Core settings / platform legal pack), never hard-coded. All figures `[verify-legal]`.

| # | Rule | Value (default) | Legal basis | Req. |
|---|---|---|---|---|
| VN-01 | Normal working hours | ≤ 8h/day and ≤ 48h/week; if weekly mode: ≤ 10h/day, ≤ 48h/week | Labour Code 2019 (LC) Art.105 [verify-legal] | 016, 018 |
| VN-02 | Night work window | 22:00 – 06:00 | LC Art.106 [verify-legal] | 032, 041 |
| VN-03 | Breaks within shift | ≥ 30 min (day), ≥ 45 min (night) for ≥ 6h continuous work; counted as working time | LC Art.109 [verify-legal] | 010 |
| VN-04 | Rest between shifts | ≥ 12h | LC Art.110 [verify-legal] | 018 |
| VN-05 | Weekly rest | ≥ 24 consecutive hours/week; if impossible, average ≥ 4 days/month | LC Art.111 [verify-legal] | 014, 018 |
| VN-06 | Public holidays (11 days) | 1 Jan (1d); Tết lunar (5d); Hùng Kings 10/3 lunar (1d); 30 Apr (1d); 1 May (1d); 2 Sep + 1 adjacent day (2d). Foreigners +2 days. Holiday on weekly rest day → substitute next working day | LC Art.112 [verify-legal]; watch for new holidays added by NA resolutions | 001–005 |
| VN-07 | OT consent and caps | Employee consent; normal + OT ≤ 12h/day; ≤ 40h/month; ≤ 200h/year; ≤ 300h/year for listed sectors (textiles, garments, footwear, electronics, agro-forestry-fishery processing, power, telecom, water supply, refineries…, plus urgent cases) | LC Art.107; Decree 145/2020 Arts.60–62 [verify-legal] | 040, 045–047 |
| VN-08 | 300h notification | Written notice to provincial labour authority | LC Art.107(4); Decree 145 Art.62 [verify-legal] | 049 |
| VN-09 | OT pay rates (for Payroll; T&A supplies categories) | Weekday ≥ 150%; weekly rest day ≥ 200%; public holiday / paid leave day ≥ 300% (excl. holiday pay for monthly-paid) | LC Art.98(1); Decree 145 Art.55 [verify-legal] | 041 |
| VN-10 | Night work premium | ≥ +30% of normal day wage | LC Art.98(2); Decree 145 Art.56 [verify-legal] | 041 |
| VN-11 | Night OT | OT rate + 30% + 20% × day-time wage of that day (normal-day wage if no day OT before; day-OT wage if day OT preceded) — e.g., 200%/210% weekday, 270% rest day, 390% holiday | LC Art.98(3); Decree 145 Art.57 [verify-legal] | 041, 042 |
| VN-12 | Annual leave | 12 / 14 / 16 working days; +1 day per 5 years service; pro-rata; unused paid on termination | LC Arts.113–114; Decree 145 Arts.65–67 [verify-legal] | 057–059, 067 |
| VN-13 | Personal leave | Paid: own marriage 3d, child marriage 1d, death of parent/in-law/spouse/child 3d. Unpaid: death of grandparent/sibling 1d, marriage of parent/sibling 1d | LC Art.115 [verify-legal] | 056 |
| VN-14 | Sick leave (SI-paid) | 30 / 40 / 60 working days/year by SI years (<15, 15–<30, ≥30); 40/50/70 for heavy-hazardous; long-term illness up to 180 days; child sick 20d (<3y) / 15d (3–<7y). Benefit paid by SI fund, not salary | Law on SI 2024 (Law 41/2024/QH15, eff. 1 Jul 2025) [verify-legal] | 063, 064 |
| VN-15 | Maternity / paternity | Maternity 6 months (+1 month/extra child); paternity 5/7/10/14 working days; 5 prenatal visits | LC Art.139; SI Law 2024 [verify-legal] | 056, 063, 065 |
| VN-16 | Protected employees | No OT/night work/long trips for pregnant from 7th month (6th remote) or raising child < 12 months unless consent; 1h shorter day from 7th month; 30 min/day menstruation (3 days/month), 60 min/day nursing child < 12 months, counted as paid time | LC Art.137; Decree 145 Art.80 [verify-legal] | 035, 048 |
| VN-17 | Minors (< 18) | Max 8h/day, 40h/week (15–<18); OT/night only for permitted jobs | LC Art.146 [verify-legal] | 048 |
| VN-18 | SI exemption threshold | Employee not working and not paid for ≥ 14 working days in a month → no SI/HI/UI contribution that month (except maternity rules) | Decision 595/QĐ-BHXH / SI Law 2024 [verify-legal] | §7.3 item 10 |
| VN-19 | PIT on OT | OT/night premium above normal wage is PIT-exempt → Payroll needs hours per category | PIT Law; Circular 111/2013 Art.3 [verify-legal] | §7.3 |
| VN-20 | Record keeping | Attendance and OT records retained and available for inspection | LC; Decree 12/2022 (sanctions) [verify-legal] | NFR-ATT-007 |

---

## 6. Key business entities (`att` schema)

| Entity | Purpose | Key attributes |
|---|---|---|
| HolidayCalendar | Named calendar assigned to legal entities/locations | tenant_id, code, legal_entity_ids, source (platform pack / custom), year |
| HolidayDate | One holiday/day-swap entry | calendar_id, date, type (public / substitute / company / swap_off / swap_work), holiday_code, lunar_ref, paid_flag, eligibility (all / foreigners), effective_from |
| ShiftDefinition | Time template for one working day | code, start, end, cross_midnight, breaks[], core_hours, grace_in/out, rounding, std_hours, day_value, night_window_override |
| SchedulePattern | Weekly or N-day cycle of shifts | code, type (weekly/rotating/flex), cycle_length, anchor_date, days[] (shift or rest), weekly_rest_days, std_hours_week |
| ScheduleAssignment | Employee/assignment ↔ pattern (T&A-owned, R2-02 §5 #26) | employee_id, assignment_id, pattern_id, valid_from/to, source (rule / manual) |
| Roster / RosterLine | Planned shift per employee-day | org_unit, period, status (draft/published), employee_id, date, shift_id, version |
| Device / Connector | Time-capture endpoint | tenant_id, vendor, model, serial, location_id, time_zone, connector_type, last_sync, status |
| Geofence / WifiZone | Mobile validation zones | location_id, lat, long, radius_m, ssid/bssid |
| EnrolmentMapping | Device user ↔ employee | device_or_group, enrol_id, employee_id, valid_from/to |
| RawPunch | Immutable punch record | punch_ts_utc, local_ts, source, device_id, enrol_id, employee_id, direction, verify_method, geo, photo_ref, validation_status, import_batch_id |
| DailyTimesheet | Computed employee-day | employee_id, assignment_id, work_date, shift_id, status, codes[], scheduled_h, worked_h, late_min, early_min, night_h, ot_candidate_min, day_value, locked_flag, calc_version |
| TimesheetCorrection | Approved correction / admin edit | daily_timesheet_id, field, old, new, reason, request_id |
| OvertimeRequest | Planned or detected OT | employee_id, date, planned_from/to, consent_flag, source (pre/detected), comp_choice (pay/TOIL), status, workflow_instance_id |
| OvertimeResult | Payable OT per day per category | employee_id, work_date, category (OT-WD…OT-PH-N), minutes, preceded_by_day_ot, request_id |
| OtCapLedger | Running totals for caps | employee_id, legal_entity_id, month, year, approved_min, pending_min, regime (200/300) |
| LeaveType / LeavePolicy | Leave configuration | code, paid_by, unit, counting_basis, entitlement rules, accrual method, carry max/expiry, eligibility, payroll_category |
| LeaveRequest | Leave application | employee_id, type, from/to, half-day parts, hours, duration_days, status, SI claim data, attachments |
| LeaveBalanceLedger | Every balance movement | employee_id, type, leave_year, txn_type (accrue/take/cancel/carry/expire/encash/adjust), quantity, ref |
| BusinessTrip / RemoteWorkRequest | Non-leave presence requests | employee_id, dates, half-days, destination, domestic/overseas, cost_centre, status |
| AttendancePeriod | Period per pay group | pay_group_id, legal_entity_id, period_start/end, ot_cutoff, std_day_method (copied from the pay calendar, R2-02 §5 #25), status (OPEN/IN_REVIEW/CLOSED/LOCKED), current_version, locked_version |
| PayGroupMembership | Read copy of Payroll's employee → pay group link (R2-02 §5 #25) | employment_id, pay_group_id, valid_from/to, source event id |
| AttendancePeriodHold | Payroll close hold on one result version (changed 2026-10-16, X-03) | period_id, result_version, run_id, status (ACTIVE/RELEASED/CONSUMED), granted_at, released_at, release_reason (REJECTED/WITHDRAWN/CANCELLED/REOPEN_REQUESTED/UNLOCKED) |
| AttendanceResult / AttendanceResultLine | Published snapshot to Payroll | period_id, version, employee_id, assignment_segment, category_code, quantity, unit, is_retro, original_period_id |
| AttendanceCode | Day codes and their payroll mapping | code, label_vi/en, payroll_category, counts_as_worked, paid_by |

---

## 7. Cross-module interfaces

Interface IDs (`ATT-C-nn` consumes, `ATT-P-nn` provides) are for traceability in the gap register. "When" = trigger/timing.

### 7.1 Consumes

| ID | What | From module | When | Used for | Pri |
|---|---|---|---|---|---|
| ATT-C-01 | Tenant context, tenant settings, time zone, default language | saas-platform | Tenant provisioning; every request | Data isolation, local-time conversion | M |
| ATT-C-02 | Plan/feature entitlements (mobile check-in, device connectors, rostering, max devices) | saas-platform | Provisioning, plan change | Feature gating | M |
| ATT-C-03 | Default **VN legal parameter pack**: national public-holiday template per year (incl. lunar conversions), OT caps, night window, leave entitlements, protected-employee rules, 300h sector list — read via `getParam(key, scope, asOfDate)`; `saas.LegalParameterPackPublished`; each result stores the pack version | saas-platform (Legal Parameter Service) | Yearly + on legal change | BR-ATT-002, 045–048, 057 (changed 2026-10-16, R2-02 §5 #1) | M |
| ATT-C-04 | Device connector gateway: secure endpoint/agent registration, API credentials, rate limiting | saas-platform | Device onboarding; continuous | BR-ATT-021, 085 | M |
| ATT-C-05 | Mobile app shell & push notification channel | saas-platform | P2 | Mobile check-in, reminders | S |
| ATT-C-06 | Legal entities (company, tax code), org units tree with managers, cost centres | core | On change (event) + lookup | Calendar assignment, period scope, reporting, approver resolution | M |
| ATT-C-07 | Work locations with address and **geo coordinates**, time zone | core | On change | Device location, geofence, holiday per location | M |
| ATT-C-08 | Users, roles, permissions and **data scopes** (who sees which employees) | core | Each request | ESS/MSS/admin access | M |
| ATT-C-09 | **Workflow engine**: request types, approval chains (line manager, n-level, by amount/hours), delegation, escalation, callback on final decision | core | On submit / decision | Leave, OT, correction, trip, WFH, swap, period re-open | M |
| ATT-C-10 | Notification service (email, in-app, push) and templates | core | Event-driven | BR-ATT-082 | M |
| ATT-C-11 | Effective-dated settings store, audit service, document service (attachments) from **core**; **job scheduler / queue** (tenant-aware, quotas, progress API) from **saas-platform** | core; saas-platform (scheduler) | Continuous | Policies, audit trail, attachments, nightly jobs (changed 2026-10-16, R2-02 §5 #13, #15b, #16) | M |
| ATT-C-12 | Employee master: employee ID, name, employee code, gender, DOB, nationality | people | On hire/change (event) | Eligibility, foreigner holidays, minor rules | M |
| ATT-C-13 | Employment dates: hire date, **seniority/service start date** (for leave), probation end, **last working day & termination date**, contract suspension periods | people | On hire, change, separation (event, incl. future-dated) | BR-ATT-039, 057–059, 067 | M |
| ATT-C-14 | **Assignments** (effective-dated): legal entity, org unit, position/job, line manager, location, employee group/category, contractual regime (full/part-time, hours/week) & FTE, cost centre. No pay group and no default schedule: the pay group comes from Payroll (ATT-C-24) and the schedule is assigned by T&A (BR-ATT-015) | people | On change (event; future-dated supported) | Segmenting, schedule rules, approvers (changed 2026-10-16, R2-02 §5 #25, #26) | M |
| ATT-C-15 | Job attributes: heavy/hazardous and especially heavy/hazardous flags; punch-exempt flag | people | On change | Annual leave 14/16 days, OT exemptions | M |
| ATT-C-16 | Protected-employee facts: pregnancy (expected due date / month of pregnancy), children DOB (< 12 months), disability status, work in remote/hardship area | people | On change (employee-declared, HR-verified) | BR-ATT-035, 048, 057 | M |
| ATT-C-17 | Separation event (resignation accepted / dismissal) with last working day | people | When decided (before last day) | Leave projection, block future leave, early close (BR-ATT-075) | M |
| ATT-C-18 | **Pay calendar** `pay.PayCalendarPublished`: pay groups, periods, cut-off dates (attendance and OT), T&A close deadline, payment dates, **standard-day method** | payroll | Yearly setup + changes | BR-ATT-072 period alignment; BR-ATT-016 STD_DAYS method (changed 2026-10-16, R2-02 §5 #25) | M |
| ATT-C-19 | **Payroll run status** `pay.PayrollRunStatusChanged` per pay group/period with the attendance versions consumed: `APPROVED` → lock the held version; `UNLOCKED` → `LOCKED → CLOSED`; `REJECTED`/withdrawn → hold already released by Payroll; `REVERSED` → no T&A change (changed 2026-10-16, X-03) | payroll | Each run transition (event) | BR-ATT-073, 088 | M |
| ATT-C-20 | SI contribution history: total SI contribution years per employee, incl. pre-go-live months from the opening-balance import (contribution-years API, IF-PAY-P-08; `pay.SiContributionPosted`) | payroll (insurance) | Monthly / on request | BR-ATT-064 sick-leave entitlement (changed 2026-10-16, R2-02 §5 #20) | S |
| ATT-C-21 | Payroll consumption acknowledgement = **close-hold commands** `acquireHold` / `releaseHold(reason)` on a result version (calls into ATT-P-17), plus `versions consumed` in `pay.PayrollRunStatusChanged` (changed 2026-10-16, X-03) | payroll | Acquire on run submit; release on rejection, withdrawal, cancellation, release request, unlock | BR-ATT-079, 087 — re-open guard and stale detection | M |
| ATT-C-22 | Estimated hourly cost per employee (or band) | payroll | On OT request (P2) | OT cost preview for approvers | C |
| ATT-C-23 | Accepted hire with planned start date (pre-hire) | recruitment (via people) | On offer accepted | Pre-enrol on devices / pre-assign schedule before day 1 | C |
| ATT-C-25 | `people.PrimaryEmploymentChanged` (person_id, employment_id, purpose, valid_from/to) | people | On change (event) | Information for concurrent employees; Phase 1 counts OT caps per employment / legal entity, so it does not change cap counting (R2-08) (new 2026-10-16, R2-16) | S |
| ATT-C-24 | `pay.PayGroupAssigned` (employment, pay group, effective date) — Payroll owns the employee → pay group link | payroll | On rule or manual assignment (RT) | Period scope, segmenting by pay group, close per LE × pay group (new 2026-10-16, R2-02 §5 #25) | M |

### 7.2 Provides

| ID | What | To module | When | Pri |
|---|---|---|---|---|
| ATT-P-01 | **Attendance Result** (versioned snapshot per employee × assignment segment × period; content in §7.3); announced by `att.AttendancePeriodClosed(version, checksum)` | payroll | On period close (and each re-close, and on unlock with the same version); individual early close for leavers | M |
| ATT-P-02 | **Retro delta lines** for locked periods (original period, category, ± quantity, reason) | payroll | Included in next open period's Attendance Result | M |
| ATT-P-03 | **Leave encashment / recovery on termination**: unused annual leave days (incl. carry-forward), negative balance days, leave year | payroll, people | On final processing for the leaver | M |
| ATT-P-04 | Period stale notification `att.AttendancePeriodReopened` (`CLOSED` period re-opened → current version withdrawn; only possible when no hold is active) (changed 2026-10-16, X-03) | payroll | On re-open | M |
| ATT-P-05 | **SI absence facts**: sick / child-sick / maternity / paternity / prenatal / miscarriage leave with dates, working-day counts, certificate data and attachments; work-accident leave | payroll (insurance) | On approval + in Attendance Result | M |
| ATT-P-06 | Expired annual leave flagged for payout (if policy pays out at expiry) | payroll | At carry-forward expiry | S |
| ATT-P-07 | Maternity leave start / expected and actual return-to-work dates; long unpaid leave periods (≥ 14 working days) | people, payroll | On approval / change | M |
| ATT-P-08 | Approved business-trip days (dates, half-days, destination domestic/overseas, cost centre) | people (expenses / per-diem) | On approval and on change/cancellation | M |
| ATT-P-09 | Leave balance and pending requests at last working day (for separation clearance checklist) | people | On separation event, on request | M |
| ATT-P-10 | Attendance statistics per employee (absence rate, lateness count, unpaid days, OT hours) | people (appraisal, probation review) | On request / period end | C |
| ATT-P-11 | Workflow request type definitions & business payloads: LEAVE, OT, ATT_CORRECTION, BUSINESS_TRIP, WFH, SHIFT_SWAP, PERIOD_REOPEN, BALANCE_ADJUST | core | Module deployment; per request | M |
| ATT-P-12 | Business events for notifications and audit (request status, cap thresholds, period status, device offline) | core | Event-driven | M |
| ATT-P-13 | Employee availability / absence calendar (leave, trips) | recruitment (interview scheduling), core (org dashboards) | On request | C |
| ATT-P-14 | Usage metrics per tenant: active devices, connectors, punches/month, mobile check-in users | saas-platform | Daily | S |
| ATT-P-15 | Connector health status per device (last sync, errors) | saas-platform (ops monitoring) | Continuous | S |
| ATT-P-16 | Read APIs: daily timesheet, balances, **holiday calendar** and **work-schedule patterns** (`att` calendar API — T&A is the single owner of tenant/LE/location holiday calendars; also `att.HolidayCalendarChanged`) for Core SLA, Payroll (standard days, holiday pay, payment date) and Recruitment. The holiday-calendar and schedule read APIs are an **always-on, API-level baseline service** whatever modules the tenant buys (R2-18, mvp-scope.md §11.4 R2-C9) | core, payroll, people, recruitment | On request | M (changed 2026-10-16, R2-02 §5 #2; PM-approved) |
| ATT-P-17 | **Close-hold API** (synchronous, idempotent on run id + version): `acquireHold(period, version, runId)` → granted / refused (period not `CLOSED` or version not current); `releaseHold(period, runId, reason)`; `getHold(period)` (new 2026-10-16, X-03) | payroll | Payroll run submit / release | M |
| ATT-P-18 | Period-close events `att.AttendancePeriodHoldGranted`, `att.AttendancePeriodHoldReleased`, `att.AttendancePeriodLocked` (catalogue v1.1, gap-analysis §6) (new 2026-10-16, X-03) | payroll, core (period status, notifications, audit) | On hold grant / release / lock | M |

### 7.3 Payroll hand-off data contract (Attendance Result)

**Granularity:** one result per *employee × assignment segment × attendance period*. A new segment starts whenever legal entity, pay group, cost centre, schedule, employee group, or FTE changes, or on hire/termination. Payroll prorates salary per segment.

**Period boundaries:** defined by Payroll's pay calendar (ATT-C-18). Work date = shift start date (cross-midnight shift belongs to the day it started). OT may use a separate OT cut-off (e.g., OT of 21st→20th paid with month M); the result states both the attendance and the OT date ranges.

**Units:** days as decimal (2 dp, typical values 0.5 / 1); hours as decimal hours (2 dp) after rounding rules; counts as integers; minutes only for lateness.

| # | Item / category code | Unit | Description |
|---|---|---|---|
| 1 | `STD_DAYS`, `STD_HOURS` | days / hours | Standard working days/hours of the segment (method per BR-ATT-016), with full-period value as well for proration |
| 2 | `WORKED_DAYS` | days | Days actually worked incl. paid short-time entitlements, business trip, WFH |
| 3 | `PAID_LEAVE_DAYS.<type>` | days | Employer-paid leave by type: annual, personal paid (marriage, bereavement), compensatory (TOIL), company-specific paid leave |
| 4 | `HOLIDAY_DAYS` | days | Public holidays + substitute days falling inside the segment (paid by employer) |
| 5 | `SI_LEAVE_DAYS.<type>` | days (working days; calendar days for maternity) | Sick, child-sick, maternity, paternity, prenatal, miscarriage — not paid by employer |
| 6 | `UNPAID_DAYS.<type>` | days | Unpaid personal leave, unpaid leave by agreement, absent without leave (`V`), suspension |
| 7 | `NOT_EMPLOYED_DAYS` | days | Days in period outside employment |
| 8 | `OT_HOURS.<cat>` | hours | Payable OT per category OT-WD, OT-RD, OT-PH, OT-WD-N, OT-RD-N, OT-PH-N, with sub-flag for night OT preceded by day OT (VN-11). Excludes TOIL-converted OT |
| 9 | `NIGHT_HOURS` | hours | Night work within normal hours (non-OT) |
| 10 | `SI_NONWORK_DAYS` | days | Working days without work and without salary (unpaid + SI-paid, excl. maternity per rule) — for the ≥14-day SI exemption test (VN-18) |
| 11 | `LATE_MIN`, `EARLY_MIN`, `LATE_COUNT`, `EARLY_COUNT`, `MISSING_PUNCH_COUNT` | minutes / count | For policy-based deductions or attendance bonus |
| 12 | `SHIFT_COUNT.<shift_group>` | count | Number of shifts worked per shift group (e.g., night shift, Sunday shift) for shift allowances |
| 13 | `MEAL_ELIGIBLE_DAYS` | days | Days qualifying for meal/attendance allowance per tenant rule |
| 14 | `TRIP_DAYS.<domestic/overseas>`, `WFH_DAYS` | days | For allowances (per-diem itself is People/expenses) |
| 15 | `LEAVE_ENCASH_DAYS`, `LEAVE_RECOVER_DAYS` | days | Termination settlement (ATT-P-03) |
| 16 | `RETRO.<item>` | as item | Delta lines: original_period_id, item, ± quantity, reason, source ref (ATT-P-02) |
| 17 | Header | — | period id, version, status, attendance range, OT range, generated_at, closed_by, checksum |

**Retro rules (changed 2026-10-16, X-03):** (a) a `CLOSED` period that is neither held nor `LOCKED` is corrected in place by re-open and re-close (new version, ATT-P-04); a held period must first be released by Payroll (BR-ATT-087); a final-settlement or correction run holds the individual / off-cycle result version in the same way; (b) a `Locked` period is never changed — deltas go to the next open period; (c) if the employee has terminated and no open period exists for them, deltas are published as an off-cycle Attendance Result for Payroll's off-cycle run; (d) retro lines must reference the original segment so Payroll can use the original period's rates.

---

## 8. Reports & analytics

| # | Report | Users | Pri |
|---|---|---|---|
| R-01 | Monthly timesheet grid (bảng chấm công) per org unit: days × codes, totals per category — printable/exportable Excel in VN standard layout, with signature blocks | HR, managers | M |
| R-02 | Daily attendance exceptions (late, early, missing, absent) | Managers, HR | M |
| R-03 | Raw punch log by device/employee with source and validation status | HR, auditors | M |
| R-04 | OT summary per employee: month, YTD, % of 40h / 200h / 300h caps, by category | HR, managers | M |
| R-05 | OT cap breach and override log (who approved beyond cap, reason) | HR, auditors | M |
| R-06 | Leave balance report (opening, accrued, taken, carried, expired, closing) as at date | HR, employees (own) | M |
| R-07 | Leave taken by type/org/period; SI leave register for insurance claims | HR, C&B | M |
| R-08 | Period close status by legal entity / pay group, with outstanding exceptions | HR, payroll | M |
| R-09 | Retro adjustments report (by original period) | Payroll, finance | M |
| R-10 | Roster vs actual, coverage gaps, shift swap history | Supervisors | S (P2) |
| R-11 | Device health / connector sync status | Tenant admin | S |
| R-12 | Absenteeism and lateness trends, OT cost drivers (dashboard) | Management | C (P2) |

---

## 9. Non-functional requirements (module-specific)

| ID | Requirement | Pri |
|---|---|---|
| NFR-ATT-001 | Punch ingestion: ≥ 200 punches/second per region at peak (shift change of large factories, e.g., 10,000 employees in 15 min); no punch loss; at-least-once delivery with idempotent de-duplication by (device, enrol_id, timestamp). | M |
| NFR-ATT-002 | Punch-to-timesheet latency ≤ 5 minutes for online sources; nightly full reprocessing for a 10,000-employee tenant ≤ 30 minutes. | M |
| NFR-ATT-003 | Period close (final processing + snapshot) for 10,000 employees ≤ 15 minutes. | M |
| NFR-ATT-004 | On-premise connector shall buffer punches offline for ≥ 7 days and resend on reconnect; device time-zone and clock-drift handling. | M |
| NFR-ATT-005 | All timestamps stored in UTC with tenant/location time zone; dates for timesheets are local work dates. | M |
| NFR-ATT-006 | Calculations shall be deterministic and reproducible: given the same inputs and parameter versions, re-processing yields identical results; each result records the rule/parameter versions used. | M |
| NFR-ATT-007 | Retention: raw punches, timesheets, OT and leave records ≥ the period required by law/tenant policy (default 10 years after employment ends [verify-legal]); locked snapshots immutable. | M |
| NFR-ATT-008 | Personal data: GPS coordinates, selfies and biometric templates are sensitive personal data under Decree 13/2023 (and the Personal Data Protection Law 2025) — explicit employee consent, purpose limitation, encrypted at rest, photo retention configurable (default 90 days); biometric templates are **not** stored in YourHRM. [verify-legal] | M |
| NFR-ATT-009 | Mobile check-in shall detect mock-location / rooted devices where the OS permits and flag such punches. | S |
| NFR-ATT-010 | Tenant isolation for all `att` data and device endpoints; a device credential can only write punches to its own tenant. | M |
| NFR-ATT-011 | ESS check-in (web/mobile) round trip ≤ 2 s at p95. | S |

---

## 10. Assumptions, open questions, risks

### 10.1 Assumptions
- A1. Payroll owns pay groups and the pay calendar; T&A aligns periods to it (ATT-C-18).
- A2. T&A never computes money; all rates (150/200/300%, +30%, +20%) live in Payroll as effective-dated parameters.
- A3. Business-trip requests are owned by T&A (attendance effect); per-diem and expense claims are owned by People.
- A4. Assignment changes in People are published as effective-dated events, including future-dated and back-dated changes.
- A5. Biometric matching happens on devices; YourHRM receives only punch events.
- A6. One employee may have several concurrent assignments within a tenant, including concurrent employments in several legal entities (Phase 1, R2-16); in Phase 1 OT caps are tracked per legal entity, the cross-entity per-person cap is Phase 2 (R2-08, Q4) (changed 2026-10-16, R2-16).
- A7. Payroll owns the pay group, pay calendar and standard-day method; T&A owns holiday calendars, schedule assignment and business trips (R2-02 §5 #2, #6, #25, #26).

### 10.2 Open questions
| # | Question | Owner |
|---|---|---|
| Q1 | ~~Who owns the platform-level VN legal parameter pack?~~ **Closed 2026-10-16 (R2-02 §5 #1):** the saas-platform Legal Parameter Service; update SLA per NFR-SAAS-016. | PM, SaaS BA, Core BA |
| Q2 | ~~Standard working days method: which methods?~~ **Closed 2026-10-16 (R2-02 §5 #25):** Payroll owns the method on the pay group (BR-PAY-009: fixed, scheduled days, calendar days, fixed hours); T&A computes STD_DAYS/STD_HOURS with the method from the pay calendar. | ATT BA, PAY BA |
| Q3 | Is OT paid in the same period as attendance or with a separate cut-off (common in factories)? Does Payroll support two date ranges in one run? | PAY BA |
| Q4 | Annual OT cap across multiple legal entities of one tenant (group companies) — per entity (legal view) or per person (safety view)? **Deferred 2026-10-16 (R2-08, R2-16):** Phase 1 measures per legal entity; the cross-entity measure is Phase 2 pending the labour consultant. | Legal consultant |
| Q5 | Who records pregnancy / child DOB data — People (HR record) or T&A (employee declaration)? Consent and privacy implications. | PPL BA |
| Q6 | ~~SI contribution years for sick-leave entitlement?~~ **Closed 2026-10-16 (R2-02 §5 #20):** Payroll owns all SI history; pre-go-live months come from the opening-balance import (SI contribution history template); T&A reads the contribution-years API (ATT-C-20, IF-PAY-P-08). | PAY BA |
| Q7 | Leave encashment basis: Payroll needs the salary basis (LC Art.113 / Decree 145 Art.67: average salary of 6 months preceding) — T&A only gives days; confirm. | PAY BA |
| Q8 | New public holidays introduced by recent legislation (e.g., any 2025–2026 NA resolution) — confirm list for 2027 pack. | Legal consultant |
| Q9 | Should T&A support hourly-paid / part-time employees' payable hours as a separate item (`PAYABLE_HOURS`)? | PAY BA |
| Q10 | Recruitment: needed pre-hire enrolment (ATT-C-23) or only via People once employee record created? | REC BA, PPL BA |
| Q11 | Compensatory leave (TOIL) validity and payout when expired — paid at OT rates at expiry? | HR policy / legal |

### 10.3 Risks
| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Device heterogeneity (ZKTeco/Hikvision firmware, SDK versions, push protocols) delays MVP | High | MVP = file import + one certified connector; certification list per model |
| R2 | Late back-dated assignment/termination changes from People after lock cause payroll errors | High | Mandatory retro mechanism (BR-ATT-071), stale-input notifications, reconciliation report R-09 |
| R3 | Legal changes (caps, holidays, SI Law 2024 transitions) during build | Medium | Effective-dated parameters; legal pack update process (Q1) |
| R4 | Mismatch of category definitions between T&A and Payroll (e.g., holiday OT vs paid-leave-day OT, night OT base) | High | Shared category code list in §7.3, owned jointly; contract tests between modules |
| R5 | Privacy non-compliance for GPS/selfie data | High | NFR-ATT-008, DPIA before P2 mobile launch |
| R6 | Peak punch load at factory shift changes | Medium | Queue-based ingestion, NFR-ATT-001 performance tests |
| R7 | Opening-balance import of leave balances and YTD OT is incomplete or wrong → wrong caps/balances in year 1 (changed 2026-10-16, D-09) | Medium | BR-ATT-086 with reconciliation sign-off by pilot tenant HR |
| R8 | A held period blocks an urgent T&A correction until Payroll releases the hold (changed 2026-10-16, X-03) | Low | Release request task (BR-ATT-087); correction after lock goes to retro deltas (BR-ATT-071) |
