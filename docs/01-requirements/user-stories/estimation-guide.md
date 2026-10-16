# Estimation Guide: Phase 1 Backlog (v0.1, 2026-10-16)

The guide every estimator follows, so that story points mean the same thing in every module.

## 1. Scope
- Input: every requirement rated **P1** in [mvp-scope.md](../brd/mvp-scope.md) §9 (260 BRs + 58 NFRs), taken from the module BRDs in [../brd/modules/](../brd/modules/).
- Read [decision-log.md](../../00-project-management/decision-log.md) for decisions affecting scope: no mobile, no live integrations, no SSO, file exports + e-mail only, Excel and device-export-file attendance import, password-protected PDF payslips, opening-balance import (no migration), multi-contract insurance as a legal parameter.
- Architecture: [architecture-overview.md](../../02-architecture/architecture-overview.md) v0.3, [DESIGN.md](../../../database/postgresql/DESIGN.md) v0.3.

## 2. Backlog files
| File | Owner | Contents |
|---|---|---|
| `core-backlog.md` | Core BA | P1 BR-CORE |
| `people-backlog.md` | People BA | P1 BR-PPL |
| `time-attendance-backlog.md` | T&A BA | P1 BR-ATT |
| `payroll-backlog.md` | Payroll BA | P1 BR-PAY |
| `saas-platform-backlog.md` | SaaS BA | P1 BR-SAAS |
| `technical-enablers-backlog.md` | Solution architect | Cross-cutting technical work and P1 NFRs: walking skeleton, tenancy/RLS, outbox/event bus, Keycloak, job queue runtime, (the formula engine and payroll run orchestration are in `payroll-backlog.md`; the Legal Parameter Service is in `saas-platform-backlog.md` — corrected 2026-10-16) CI/CD, environments, observability, security, performance tests, spikes SP-0/1/2/4/5/6/7/8/9 |

## 3. Story format
Each file has:
1. **Epic table:** Epic ID (`EP-<MOD>-NN`), name, goal, number of stories, story points (SP).
2. **Story table:** `ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes`
   - ID: `US-<MOD>-NNN` (MOD = CORE, PPL, ATT, PAY, SAAS, TECH).
   - Story: "As a <persona>, I want <capability> so that <benefit>." One line, followed by 1–3 short acceptance criteria separated by `;`.
   - BR/NFR refs: every P1 requirement it delivers. One story may cover several BRs; a large BR is split across several stories.
   - Depends on: story or enabler IDs from any backlog, including `US-TECH-…`.
3. **Coverage check:** a list of every P1 BR/NFR of the module with the story IDs covering it. Every P1 ID must appear; anything uncovered is stated with a reason.
4. **Totals:** SP per epic and in total, plus **risk notes** (stories with high uncertainty, marked `[high-uncertainty]`).

## 4. Story point scale (Fibonacci, relative)
A point covers the whole story for one developer pair of skills: backend API and logic, database migration, frontend screen, unit and integration tests, and developer-level testing. It does not cover BA, QA system testing, PM or DevOps (those are in the overhead factor, §6).

| SP | Reference | Example |
|---|---|---|
| **1** | Configuration or a field added to an existing screen and API | Add "ethnicity" to the person profile |
| **2** | Simple read-only list or lookup, small validation rule | Bank master list with search |
| **3** | One CRUD entity: list + form + API + validation + tests | Cost centres |
| **5** | Entity with effective-dated history **or** an approval workflow **or** an Excel import template | Job assignment history; leave request with approval |
| **8** | Complex business rule or calculation with many cases, or a statutory file export | OT categorisation into 6 classes; VSS D02-LT export |
| **13** | Very complex: engine-level logic, many legal edge cases | PIT monthly calculation incl. residency & concurrent employment |
| **>13** | Not allowed: split the story | |

Rules:
- Estimate against the **reference examples**, not hours.
- Shared engines (workflow, import engine, document/PDF, notifications, audit, legal parameters, formula engine) are built **once** in their owner backlog. Other modules' stories only *use* them: count just the registration and configuration work.
- Statutory logic with `[verify-legal]` gets +1 Fibonacci step if the rule is still unclear; mark it `[high-uncertainty]`.
- Opening-balance templates are estimated in the owning module (People: employee master; Payroll: YTD/SI history/loans; T&A: leave balances and YTD OT).

## 5. Done means
Code merged with tests passing, tenant isolation tested, audit and permissions applied, vi/en labels, API documented, reviewed by the BA on the test environment.

## 6. Converting points to time (PM consolidation)
- Assumed **1 SP ≈ 1 developer-day** of a mid-level full-stack developer. This is a planning assumption, to be recalibrated after sprint 2.
- Delivery overhead on top of developer effort: QA system testing, BA support, UX, DevOps, PM ≈ **+40%**.
- 16-week plan: **12 weeks of build** (6 two-week sprints) + 4 weeks of system integration test, UAT, pilot cut-over and stabilisation.
- Focus factor 0.7 (meetings, reviews, bugs): one developer delivers ≈ 0.7 × 10 = **7 SP per sprint**.
