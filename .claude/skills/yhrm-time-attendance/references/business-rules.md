# T&A business rules and NFR index (BR-ATT / NFR-ATT)

Source: `docs/01-requirements/brd/modules/03-time-attendance.md` v0.3 §4 and §9. Phases come from `docs/01-requirements/brd/mvp-scope.md` §9.3. MoSCoW is the BRD's business priority and is not the phase.

**Count:** 80 BR IDs (55 P1, 25 P2) and 11 NFR IDs (7 P1, 4 P2), so 91 IDs in total. The BRD footer says "Tổng số yêu cầu nghiệp vụ: 77", which is stale: §4 has 80 BR rows, and mvp-scope §3/§9.3 counts 80. Baseline column (mvp-scope §3.4): `B` = always on, whatever the tenant subscribes to. `API` = only the read API is always on.

The one-line summaries are English paraphrases. Read the BRD row before you implement a rule. Values marked `[verify-legal]` are examples and need the legal consultant's confirmation.

## 4.1 Holiday calendar (lịch ngày lễ)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-001 | Tenant holiday calendars, assigned per legal entity (optionally per location), with effective-dated entries: public holiday, substitute day, company day, day swap. Baseline `API`. | M | P1 |
| BR-ATT-002 | The platform maintains a yearly VN holiday pack, with lunar holidays (Tết, Giỗ Tổ Hùng Vương) converted to solar dates. Tenants inherit it and may override. Baseline `API`. | M | P1 |
| BR-ATT-003 | A holiday that falls on the employee's **own** weekly rest day (from their schedule) gives a substitute day (nghỉ bù) on the next working day. The rule is configurable per LE. `[verify-legal]` Art. 112(3). | M | P1 |
| BR-ATT-004 | Day swap (hoán đổi ngày): a working day moved to bridge a holiday, plus a make-up working day, per LE or employee group. | S | P2 |
| BR-ATT-005 | Extra holidays for foreign workers (1 traditional New Year + 1 national day of their country), based on nationality from People. `[verify-legal]` Art. 112(2). | S | P1 |
| BR-ATT-006 | A holiday change that affects an open period triggers recalculation. One that affects a locked period produces a retro delta (BR-ATT-071). | M | P1 |

## 4.2 Schedules and shifts (lịch làm việc, ca)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-010 | Shift definition covers start/end, cross-midnight flag, paid/unpaid breaks (fixed or flexible), core hours, grace in/out, rounding, minimum minutes for a half or full day, and standard hours credited. | M | P1 |
| BR-ATT-011 | Schedule patterns: weekly (5, 6 and 5.5 days, alternating Saturdays), and N-day rotating cycles with an anchor date. Rotation is P2. Baseline `API`. | M | P1 |
| BR-ATT-012 | Flexible schedules: required hours per day or week with optional core hours. Lateness is measured only against core hours. | S | P2 |
| BR-ATT-013 | Split shifts and overnight shifts. Every punch is attached to the shift's work date. | S | P2 |
| BR-ATT-014 | Each pattern defines the weekly rest day(s), which may differ from Sunday. They drive the OT category and holiday substitution. Art. 111. | M | P1 |
| BR-ATT-015 | T&A **owns schedule assignment**: effective-dated per employee assignment, by rule (org unit, location, job, employee group, People's contractual regime) or manually. People supplies no default schedule. | M | P1 |
| BR-ATT-016 | Compute STD_DAYS and STD_HOURS per employee per period from the schedule, holidays and contractual days, using the **pay group's method** from `pay.PayCalendarPublished.std_day_method` (ATT-C-18). T&A has no method configuration of its own; Payroll owns it (BR-PAY-009). | M | P1 |
| BR-ATT-017 | Rostering: create, copy, validate and publish rosters per org unit/location for a period. Notify on publish and on change. | S | P2 |
| BR-ATT-018 | Roster validation flags: less than 12h between shifts, no 24h weekly rest (or an average below 4 days/month), night shifts for protected workers, and hours above statutory normal hours. `[verify-legal]` | S | P2 |
| BR-ATT-019 | Shift swap with a colleague (same skill group), approved by both parties and the shift leader. | C | P2 |

## 4.3 Punch sources and raw punches (dữ liệu chấm công thô)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-020 | Store every raw punch immutably. Fields: tenant, enrol id, resolved employee, UTC + local time + device TZ, source, device/app id, direction, verify method, location/Wi-Fi, photo ref, received time, validation status. | M | P1 |
| BR-ATT-021 | Device integration (ZKTeco and Hikvision first) through an on-prem connector agent (pull/push over HTTPS) and export-file import. The live connector is P2 (R2-01); file import goes through BR-ATT-025. | M | P2 |
| BR-ATT-022 | Effective-dated enrolment mapping (device user id ↔ employee). Punches with an unknown or expired mapping are quarantined for admin resolution. | M | P1 |
| BR-ATT-023 | Mobile check-in with geofence and/or Wi-Fi SSID/BSSID, optional selfie + liveness. Out-of-zone punches go to "needs review" or are rejected. | S | P2 |
| BR-ATT-024 | Web check-in, per employee group, optionally restricted by IP range. | S | P2 |
| BR-ATT-025 | Excel import of punches or of whole daily timesheets, with validation report and dry-run. It is also the Attendance Result import for tenants with Payroll but no T&A, an **always-on baseline** capability (R2-18) that produces a standard `CLOSED` result version. Device export files (CSV/Excel) use the same import. Baseline `B`. | M | P1 |
| BR-ATT-026 | Merge duplicate punches within a configurable window (default 1 min), and alert on device clock drift above N min. | M | P2 |
| BR-ATT-027 | Users never edit or delete raw punches. Corrections are separate records linked to the daily timesheet. | M | P1 |

## 4.4 Daily timesheet processing (bảng công ngày)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-030 | Attach punches to a work date using the planned shift window with configurable before/after tolerance, including cross-midnight shifts. | M | P1 |
| BR-ATT-031 | Configurable pairing (first in/last out, all pairs, or by direction). Unpaired punches produce `Missing in`/`Missing out` exceptions. | M | P1 |
| BR-ATT-032 | Per employee-day, compute: scheduled hours, worked hours, paid-break hours, late and early minutes, absence hours, night hours (window parameter 22:00–06:00 `[verify-legal]`), OT-candidate minutes, and day value (0 / 0.5 / 1 / decimal). | M | P1 |
| BR-ATT-033 | Each day has a status and one or more attendance codes (ký hiệu chấm công: X, P, Ô, TS, L, CT, WFH, KL, V…). Codes are tenant-configurable and map to payroll categories. | M | P1 |
| BR-ATT-034 | Late/early tolerance and treatment by policy (grace, per minute, 15/30-min blocks, half-day absence). T&A outputs minutes and counts, never money. | M | P2 |
| BR-ATT-035 | Statutory paid short breaks count as working time: menstruation 30 min/day for 3 days/month, child under 12 months 60 min/day, pregnancy from month 7 (month 6 in remote areas) 1h less per day. `[verify-legal]` | M | P1 |
| BR-ATT-036 | Employee correction request (giải trình) with reason and attachment, approved through the Core workflow. A limit of N requests per month is configurable. | M | P1 |
| BR-ATT-037 | Admin bulk edit of daily timesheets with a mandatory reason. Before/after values are kept in history. | M | P1 |
| BR-ATT-038 | Processing is re-runnable and idempotent for any open date range or employee set. It is triggered by punches, approvals, and schedule, holiday or assignment changes. | M | P1 |
| BR-ATT-039 | Days outside employment (before hire, after termination, contract suspension) are `Not employed`/`Suspended`, which is distinct from absence. | M | P1 |

## 4.5 Overtime (làm thêm giờ)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-040 | OT request by the employee, or by a manager for one or more employees: date, planned start/end, reason, and **employee consent** flag. Core workflow with configurable levels. Art. 107(2). | M | P1 |
| BR-ATT-041 | Classify every OT minute into exactly one of OT-WD, OT-RD, OT-PH, OT-WD-N, OT-RD-N, OT-PH-N. Record NW (night work in normal hours, not OT) separately. Rates live in Payroll. | M | P1 |
| BR-ATT-042 | For night OT, flag whether day OT preceded it the same day, because the premium base differs. `[verify-legal]` ND145 Art. 57(3). | M | P1 |
| BR-ATT-043 | OT detection policy per employee group: (a) pre-approval only, (b) detected and confirmed by the manager, (c) auto-approved up to N min. Minimum OT block and rounding. | M | P2 |
| BR-ATT-044 | Paid OT per day = min(approved, actual from punches) by default. Policy may allow approved = paid for punch-exempt staff. | M | P1 |
| BR-ATT-045 | Daily cap: normal + OT ≤ 12h on a working day, OT ≤ 12h on rest days and holidays. Warn or block, configurable. `[verify-legal]` | M | P1 |
| BR-ATT-046 | Monthly cap (40h) and annual cap (200h default; 300h for flagged eligible LEs or groups), counting approved + pending. Warn at thresholds (e.g. 80%) and block at 100% unless an authorised override with reason is given. `[verify-legal]` | M | P1 |
| BR-ATT-047 | Annual cap measured across all employments and LEs of a person in the tenant and/or per LE, configurable. **P1 measures caps per LE only** (R2-08/R2-16). | S | P2 |
| BR-ATT-048 | Block OT and night shifts for protected workers (minors except a permitted list, pregnant from month 7 / month 6 in remote areas, child under 12 months) unless recorded consent exists where the law allows it. `[verify-legal]` | M | P1 |
| BR-ATT-049 | When the 300h regime applies, prompt the admin to notify the provincial labour authority and record the notice reference. `[verify-legal]` Art. 107(4). | S | P1 |
| BR-ATT-050 | TOIL (nghỉ bù) instead of OT pay, per request, when policy allows. Credited 1:1 (configurable) and excluded from paid-OT output. | S | P2 |
| BR-ATT-051 | Warn when an employee works OT on consecutive days without the legally required compensatory rest. | C | P2 |

## 4.6 Leave (nghỉ phép)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-055 | Leave type configuration: payer (employer / SI fund / unpaid), unit, counting basis (working or calendar day), balance tracking, eligibility, required documents, max per request/year, counts-as-service flag, payroll category mapping. | M | P1 |
| BR-ATT-056 | Seeded VN leave types: annual; paid personal leave (own marriage 3, child's marriage 1, death of parent/parent-in-law/spouse/child 3); unpaid personal leave (death of grandparent/sibling, marriage of parent/sibling 1); sick, child-sick, maternity, paternity, prenatal, miscarriage (SI-paid); work accident; agreed unpaid leave; TOIL; trip/WFH as non-leave codes. `[verify-legal]` | M | P1 |
| BR-ATT-057 | Annual leave: 12 / 14 / 16 working days by worker category, plus 1 day per 5 full years of service. The tenant may grant more, never less. `[verify-legal]` | M | P1 |
| BR-ATT-058 | Accrual: monthly, annual front-load, or pro-rata. Proration for joiners and leavers per ND145: a month counts if at least half its working days were worked. `[verify-legal]` | M | P1 |
| BR-ATT-059 | Service time for seniority and proration includes the periods the law counts (e.g. sick leave up to the limit, maternity, agreed unpaid leave up to 1 month/year) and excludes others, by effective-dated parameters. `[verify-legal]` | M | P1 |
| BR-ATT-060 | Configurable leave year (default: calendar year). Carry-over has a maximum and an expiry date. Expired balance is forfeited or flagged for payout per policy. | M | P1 |
| BR-ATT-061 | Leave by day, half-day (AM/PM) or hour where the type allows. Duration comes from the schedule: rest days and holidays are excluded for working-day types and included for calendar-day types (e.g. maternity). | M | P1 |
| BR-ATT-062 | On submit, check balance (optionally negative down to N days), overlap with other requests and trips, notice period, eligibility and mandatory attachments. Balance is reserved while the request is pending. | M | P1 |
| BR-ATT-063 | Sick, child-sick and maternity requests capture SI claim data: certificate type/no., issuing facility, diagnosis group, child DOB/id, confinement date, number of children, caesarean flag, attachments. Minimal in P1 (R2-07). `[verify-legal]` | M | P1 |
| BR-ATT-064 | Track SI sick days used in the calendar year against the entitlement (e.g. 30/40/60 by contribution years) as information or warning, not a hard block. Contribution years come from Payroll. `[verify-legal]` | S | P2 |
| BR-ATT-065 | Maternity leave (default 6 months, +1 month per extra child), including early return after the statutory minimum, with notification and timesheet impact. `[verify-legal]` | M | P1 |
| BR-ATT-066 | Approved leave can be cancelled or shortened by the employee (before start) or by HR (any time) with reason. The balance is refunded, with a retro delta if the period is locked. | M | P2 |
| BR-ATT-067 | On termination, compute unused annual leave (accrued − taken, incl. unexpired carry-over, prorated to the last working day) and any negative balance. Publish to Payroll and People. `[verify-legal]` | M | P1 |
| BR-ATT-068 | Manual balance adjustment with reason. Opening balances come through the opening-balance import (D-09). Every balance movement is a ledger transaction (accrue, take, cancel, carry, expire, encash, adjust). | M | P1 |
| BR-ATT-069 | Team leave calendar (respecting data scope) shows approved and pending absences and highlights under-minimum staffing per org unit. | S | P2 |

## 4.7 Business trip and remote work (công tác, WFH)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-070 | Business-trip and WFH requests, approved through Core workflow, count as worked without punches. T&A is the sole owner of trips and emits `att.BusinessTripApproved` (ATT-P-08). **In P1, trips and WFH are attendance codes (CT, WFH)**, per R2-19. | M | P2 |

## 4.8 Period close, payroll handover, retro (chốt công, khóa kỳ, hồi tố)
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-071 | A change touching a `LOCKED` period never alters the locked snapshot. It produces retro delta lines (original period, employee, segment, category, ± quantity, reason, source document) attached to the next open period's Attendance Result. | M | P1 |
| BR-ATT-072 | Attendance periods align with Payroll's pay calendar per pay group (calendar month or custom cut-off such as 26th → 25th). A separate OT cut-off is supported. | M | P1 |
| BR-ATT-073 | Lifecycle `OPEN → IN_REVIEW → CLOSED → LOCKED`. Reopening `CLOSED → OPEN` needs a T&A admin and a reason, is allowed only with no active hold, and emits `att.AttendancePeriodReopened`. `LOCKED → CLOSED` happens only through payroll unlock. | M | P1 |
| BR-ATT-074 | Pre-close exception checklist (missing punches, absences without a code, pending requests, quarantined punches, cap breaches). Close only when blocking exceptions are resolved or explicitly waived. | M | P1 |
| BR-ATT-075 | Close per legal entity × pay group, plus early close for a single employee (a leaver's final pay before period end). Restored to P1 by R3-06. | M | P1 |
| BR-ATT-076 | The published Attendance Result is per employee × assignment segment × period and carries the §7.3 quantities. It is versioned: every re-close creates a new version, and Payroll is told which version is current. | M | P1 |
| BR-ATT-077 | Optional employee confirmation of the monthly timesheet in ESS. A dispute creates a correction request. | C | P2 |
| BR-ATT-078 | Optional manager sign-off of the team timesheet before HR closes the period. | S | P2 |
| BR-ATT-079 | **Close hold**: synchronous, idempotent API for Payroll to acquire and release a hold on a specific result version. Key: tenant, LE, pay group, period, result version, run id. Acquire succeeds only if the period is `CLOSED` and the version is current. While a hold is active, T&A refuses reopen and any in-place change. A hold never expires; only Payroll actions release it. Emits `att.AttendancePeriodHoldGranted` and `att.AttendancePeriodHoldReleased`. | M | P1 |
| BR-ATT-087 | Reopening a **held** period is never done directly. Show the holding payroll run, create a release-request task for the payroll admin (Core workflow/notification), and reopen automatically once the hold is released. | M | P1 |
| BR-ATT-088 | On `pay.PayrollRunStatusChanged(APPROVED)`, lock the held version (`CLOSED → LOCKED`, idempotent, emit `att.AttendancePeriodLocked`). A version mismatch is a reconciliation incident, not a refusal. On `UNLOCKED`, move `LOCKED → CLOSED` with the same version and release the hold. A payroll reversal does not touch T&A. A nightly reconciliation job compares run and period states. | M | P1 |

## 4.9 Self-service, notifications, configuration
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-ATT-080 | ESS shows the employee's schedule, punches, daily timesheet, exceptions, balances (with ledger) and request status, in VI and EN. | M | P1 |
| BR-ATT-081 | MSS team view: who is in, late, absent or on leave today; pending approvals; team OT against caps. | M | P2 |
| BR-ATT-082 | Notifications through Core: request submitted/approved/rejected, missing-punch reminder (next morning), OT cap thresholds, roster publish, balance expiring soon, close reminder. | M | P1 |
| BR-ATT-083 | All T&A policies (grace, rounding, OT, leave) are effective-dated and assignable by LE, employee group, org unit or individual with a defined precedence. **P1: one policy set per LE** (R2-19). | M | P2 |
| BR-ATT-084 | Approval delegation and absence are handled by Core workflow. T&A auto-escalates requests pending more than N days before close. | S | P2 |
| BR-ATT-085 | Public API for external systems to push punches and read timesheets and balances, with tenant-scoped credentials. | S | P2 |
| BR-ATT-086 | Opening-balance import: leave balances (per type and leave year, incl. carry-over and expiry) and YTD OT hours (for the caps) per employee, from generic Excel templates on Core's import engine, with validation, dry-run and reconciliation sign-off. No Oracle conversion and no historical timesheets (D-09). | M | P1 |

## 9. NFRs
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| NFR-ATT-001 | Punch ingestion of at least 200 punches/s per region at peak, with no punch loss, at-least-once delivery and idempotent dedup on (device, enrol_id, timestamp). X-05 replaced the target with 500/s platform-wide and 50/s per tenant (SP-3). | M | P2 |
| NFR-ATT-002 | Punch-to-timesheet latency ≤ 5 min for online sources; nightly full reprocess of a 10k-employee tenant ≤ 30 min. Only the nightly part applies in P1. | M | P1 |
| NFR-ATT-003 | Period close (final processing + snapshot) for 10k employees ≤ 15 min. | M | P1 |
| NFR-ATT-004 | The on-prem connector buffers punches for at least 7 days offline and handles device TZ and clock drift. | M | P2 |
| NFR-ATT-005 | Store timestamps in UTC with the tenant/location TZ. The timesheet date is the local work date. | M | P1 |
| NFR-ATT-006 | Calculation is deterministic and reproducible. Each result records the rule and parameter version used. | M | P1 |
| NFR-ATT-007 | Retention of punches, timesheets, OT and leave records for at least the legal or tenant period (default 10 years after termination `[verify-legal]`). Locked snapshots are immutable. | M | P1 |
| NFR-ATT-008 | GPS, selfie and biometrics are sensitive personal data (ND13/2023, PDPL 2025): explicit consent, purpose limitation, encryption at rest, photo retention 90 days by default, and **no biometric templates stored**. `[verify-legal]` | M | P1 |
| NFR-ATT-009 | Mobile check-in detects mock location and rooted devices and flags those punches. | S | P2 |
| NFR-ATT-010 | Tenant isolation for all `att` data and device endpoints. A device credential writes only to its own tenant. | M | P1 |
| NFR-ATT-011 | ESS check-in response ≤ 2 s at p95. | S | P2 |
