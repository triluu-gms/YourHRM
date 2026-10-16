# ADR-0019: Saga protocols — period close with close hold, leaver, hire, reorganisation

- **Status:** Accepted (2026-10-16, X-03 — period-close payroll hold; X-06 new events)
- **Date:** 2026-10-16
- **Related:** gap-analysis §7, GAP-017/019/027/028/035/036/040/053/070, §9 A-4; ADR-0004, ADR-0009, ADR-0011; [architecture-overview](../architecture-overview.md) §7.3–7.4

## Context

Several business outcomes span modules with separate transactions: the T&A ↔ Payroll period close (versioned Attendance Results, CLOSED/LOCKED handshake, stale detection, retro, unlock), the leaver flow (T&A individual close, People termination, Payroll final settlement within a legal deadline), the hire (Recruitment/People → Core/T&A/Payroll readiness), and reorganisation (Core package → People mass change → Core activation). Gap-analysis §7.2 step 9 proposes that if T&A rejects the lock on approval, Payroll's approval is rolled back — an asynchronous rollback of a human approval.

## Decision

1. **Style:** each saga is orchestrated by the module that owns the outcome, through a persisted **process manager** (state table, idempotent steps, timeouts via the scheduler), built on a small base in `shared/common`. Reactions in other modules are plain idempotent event handlers.
2. **Period close — close hold:** when a payroll run is *submitted*, Payroll acquires a hold on the exact Attendance Result version via a synchronous, idempotent T&A API. While held, T&A refuses re-open unless Payroll releases the hold (run → `CALCULATED`, stale). On approval, T&A's `CLOSED → LOCKED` cannot fail because the version is guaranteed. Unlock (privileged, second approval) is the only `LOCKED → CLOSED` path; reversal leaves T&A untouched. New events: `att.AttendancePeriodHoldGranted/Released`. A nightly reconciliation job checks run/period consistency.
3. **Leaver:** Payroll's `FinalSettlementProcess` starts on `people.TerminationScheduled` (deadline clock from termination date, legal pack key), waits for `att.LeaverAttendanceClosed` and `people.EmployeeTerminated` (or an HR override with reason), runs the off-cycle settlement, and publishes `pay.FinalSettlementStatusChanged`; alerts at 50/80/100% of deadline; cancelled by `people.TerminationCancelled`.
4. **Hire:** People's `HireProcess` emits `people.EmployeeHired(preview)`, tracks readiness (user account, pay group, schedule, contract, bank, tax code) from downstream events, publishes `people.OnboardingReadinessChanged`, and emits the final `EmployeeHired` on the start date; `HireCancelled` compensates.
5. **Reorg:** Core's `ReorgPackage` goes `PENDING_MAPPING → ACTIVE | ACTIVATED_WITH_ERRORS` based on `people.ReorgMappingCompleted`; publishes `core.ReorgPackageStatusChanged`.
6. All saga messages carry correlation ids and business versions; all steps are idempotent; process state is visible in admin UIs.

## Alternatives considered

- **Approve-then-rollback (gap §7.2 step 9 as written):** works, but reverses a human approval asynchronously and confuses approvers; kept as fallback if BAs reject the hold.
- **Pure choreography:** nobody owns overall status or deadlines; poor for the legal settlement deadline.
- **Workflow engine / Temporal for sagas:** extra infrastructure; the in-house approval engine is for human approvals only (ADR-0007).
- **Distributed transactions (XA) across schemas:** violates module autonomy and blocks extraction.

## Consequences

- + No approval rollbacks; explicit deadlines; observable process state.
- − The T&A and Payroll BAs update their BRDs (gap §7) for the hold, accepted as X-03 on 2026-10-16; the three new events are added to catalogue v1.1 (X-06).
- − Process-manager code per saga to build and test (contract + scenario tests).
