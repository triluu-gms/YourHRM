# Performance tests

Automated performance tests of the Phase-1 non-functional requirements. They are JUnit tests tagged `perf` in the
backend `:app` project. The default `test` task excludes them, and the `perfTest` task runs them.

| NFR | Requirement (BRD 03 §9) | Test class | What is timed | Budget (10,000 employees) |
|---|---|---|---|---|
| NFR-ATT-002 (P1 part) | Nightly full reprocessing of a 10,000-employee tenant | `vn.yourhrm.attendance.perf.NightlyReprocessingPerfIT` | `AttendanceJobs.runNightly(today)`: period review, the RECALCULATION step (`TimesheetProcessor.recomputeAll` of every employment from the earliest open period to today), leave steps and hold reconciliation. It runs twice: first with every day new (all days written, asserted), then with unchanged inputs (the usual night, also asserted). | ≤ 30 min |
| NFR-ATT-003 | Close of one period for 10,000 employees (final processing + snapshot) | `vn.yourhrm.attendance.perf.PeriodClosePerfIT` | Final processing (`TimesheetProcessor.recomputeAll` over the period, no day computed before) plus `PeriodService.close`: checklist, segments and lines, result version 1, period CLOSED, outbox, audit. The sum is asserted. | ≤ 15 min |

The 500 punches/s ingestion target (NFR-ATT-001) and the other P2 NFRs are out of scope.

## Running

You need Docker for Testcontainers (PostgreSQL 17 with the real DDL baseline, as in every integration test) and
JDK 25.

```bash
cd backend
./gradlew :app:perfTest                              # 1,000 employees (default), budgets scaled to 1/10
./gradlew :app:perfTest -Dperf.employees=10000       # the reference size of the NFRs
./gradlew :app:perfTest -Dperf.employees=10000 --tests '*PeriodClosePerfIT'
./gradlew :app:perfTest -Dperf.employees=2000 -Dperf.enforce=false   # measure only, never fail on time
```

Each run prints `[perf] ...` lines and appends one CSV row per measurement to
`backend/src/app/build/perf/att-perf.csv`. Columns: `at, nfr, step, employees, measured_ms, budget_ms, details`.

## Parameters

The Gradle invocation passes every `-Dperf.*` system property to the test JVM.

| Property | Default | Meaning |
|---|---|---|
| `perf.employees` | 1000 | Employments in the generated tenant |
| `perf.units` | 20 | Departments under one root org unit |
| `perf.nightShare` | 0.10 | Share of employees on the night shift 22:00-06:00 Mon-Sat. The others work the office shift 08:00-17:00 Mon-Fri. |
| `perf.exceptionShare` | 0.06 | Share of planned work days with an exception, split evenly between absent (no punch), missing OUT and late arrival (+20 min) |
| `perf.leaveShare` | 0.05 | Share of office employees with 1–2 days of approved annual leave in the closed month |
| `perf.otShare` | 0.10 | Share of office employees with approved, consented overtime 17:00-19:00 every Tuesday and Thursday |
| `perf.quarantineShare` | 0.001 | Extra unmapped punches, quarantined, relative to employees × 20 days |
| `perf.enforce` | true | `false` reports the timings without asserting the budgets |
| `perf.heap` | 3g | Heap of the test JVM |
| `perf.reportFile` | `build/perf/att-perf.csv` | CSV report |

## Budgets and scaling

The NFRs state their budgets for 10,000 employees. For a smaller (or larger) N the tests scale them linearly:
`budget(N) = budget(10,000) × N / 10,000`. Examples:

- At 1,000 employees: 3 min for the nightly run and 90 s for the close.
- At 10,000 employees: 30 min and 15 min.

Linear scaling is strict for small N: fixed costs such as Spring warm-up of the first calls and the checklist
queries weigh more there. If a small run fails just over its budget, check it with `-Dperf.enforce=false` and run the
10,000-employee size before you conclude anything.

Seeding is reported separately (`seed` row) and is not part of any budget.

## The synthetic tenant

`vn.yourhrm.attendance.perf.PerfTenantGenerator` builds one tenant per test class. Configuration goes through the
real API and ports:

- the two shifts and the two weekly patterns (`POST /shifts`, `/schedule-patterns`);
- the pay calendar (`PayrollInbound.deliver(PayCalendarPublished)`): the previous month P1 and the current month P2
  as OPEN periods, FIXED_DAYS 26.

The volume is written set-based (`generate_series`) as the superuser, in the state that the projections and the
punch import would leave it in:

- `att.ext_hr_employment`: 1 % hired in the middle of P1, 1 % terminated in the middle of P1;
- `att.ext_hr_assignment`: one primary line per employment, spread over the departments;
- `att.schedule_assignment`: office or night pattern;
- `att.ext_pay_pay_group_assignment`: one pay group;
- `att.raw_punch`: VALID punches for every planned work day from the start of P1 to yesterday, with jitter and the
  exceptions above, plus a few quarantined unmapped punches;
- `att.leave_type` ANNUAL (code `P`) and APPROVED `att.leave_request` rows;
- APPROVED, consented `att.overtime_request` rows, which match OUT punches at about 19:05.

The hash of the employment id drives every per-employee choice, so the shares are stable for a given size.

Not generated, so it adds no cost to the measurement:

- holiday calendars;
- corrections;
- pending requests;
- protected-worker statuses;
- leave policies and balances;
- more than one legal entity or pay group.

Add them to the generator when the NFR test plan needs them.

## Caveats

- Testcontainers runs PostgreSQL with `fsync=off` and default memory settings, on the developer machine. The
  binding measurement for sign-off belongs on production-like hardware: PostgreSQL 17 sized as in production, and
  the application pool sizes of production instead of the test sizes (the T&A pool has 3 connections in tests).
- The nightly run processes employments one after another (one transaction per employment and chunk), as the
  production code does today. The test measures that code and does not parallelise it.
- These tests had not been run when they were written (no Docker on the authoring machine). The first run will
  calibrate the generator (shares, exception mix) and may surface fixture issues.
