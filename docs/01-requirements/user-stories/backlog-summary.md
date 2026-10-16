# Phase 1 Backlog: Estimate Summary & Capacity Check (v0.1, 2026-10-16)

Written for: sponsor and PM. Method: [estimation-guide.md](estimation-guide.md). Scope: the 260 BRs + 58 NFRs rated P1 in [mvp-scope.md](../brd/mvp-scope.md) v0.3.

## 1. Headline
- **Phase 1 = 608 user stories, ≈ 3,130 story points (SP) ≈ 4,300 person-days ≈ 195 person-months (~16 person-years).**
- **The 16-week plan is not feasible for the full Phase 1.** Building it in 12 weeks would take about 75 developers, which no team can absorb.
- **Realistic plan:** with about 16 developers (≈ 22 FTE in total), Phase 1 is complete in **about 13–14 months**. A **first live payroll release is possible in about 9–10 months** if scope is staged by the statutory calendar (§5).
- These figures rest on a planning assumption (1 SP ≈ 1 developer-day, 7 SP per developer per sprint). **Recalibrate after sprints 1–2.**

## 2. Backlog totals (recomputed from the story tables)
| Backlog | Stories | SP | 13-SP stories | Biggest epics |
|---|---|---|---|---|
| [Core](core-backlog.md) | 86 | 454 | 1 | Workflow engine 80, Users & authorisation 73, Import engine 53 |
| [People](people-backlog.md) | 93 | 456 | 1 | Person & personal data 65, Employment 53, Contracts 48 |
| [Time & Attendance](time-attendance-backlog.md) | 84 | 458 | 0 | Period close & payroll hand-off 83, Daily processing 63, OT 55 |
| [Payroll](payroll-backlog.md) | 121 | 709 | 9 | PIT 107, Run orchestration 105, Gross-to-net 77, Insurance 71 |
| [SaaS platform](saas-platform-backlog.md) | 75 | 342 | 0 | Opening-balance go-live 60, Identity 39, Legal parameters 39 |
| [Technical enablers](technical-enablers-backlog.md) | 149 | 704 | 0 | Environments/DR 65, Frontend foundation 54, Tenancy/RLS 52 |
| **Total** | **608** | **3,123** | 11 | |
| Gap found in consolidation: Legal Parameter Service as-of read API with cache and override resolution (to add to the SaaS backlog) | +1 | +8 | | |
| **Adjusted total** | **609** | **3,131** | | |

- **Coverage:** every P1 BR and NFR maps to at least one story. Exceptions:
  - BR-SAAS-087 (security-level classification under Decree 85) is a compliance dossier, not build work, so it has no story.
  - Retention purge jobs are P2.
- **No story is above 13 SP.** Point mix across all backlogs: 1 SP ×3, 2 ×36, 3 ×136, 5 ×293, 8 ×129, 13 ×11.
- **About 60 stories are `[high-uncertainty]`,** mostly because a legal rule isn't confirmed yet or a spike result isn't in.

## 3. Effort with delivery overhead
| Part | SP (dev-days) | Overhead | Person-days |
|---|---|---|---|
| Module backlogs (incl. +8 gap) | 2,427 | +40% (QA, BA, UX, DevOps, PM) | 3,398 |
| Technical enablers | 704 | +25% (DevOps work is already in the SP) | 880 |
| **Total** | **3,131** | | **≈ 4,280 (≈ 195 person-months)** |

Not included:
- **4–6 consultant-weeks** from a Vietnam payroll/tax consultant to write and verify the ≥200 golden payroll scenarios and confirm the `[verify-legal]` rules.
- DPO and legal review.
- The pilot customer's own effort: opening-balance files, UAT.

## 4. Capacity scenarios
Assumes 7 SP per developer per 2-week sprint (focus factor 0.7), plus 4–6 weeks for system test, UAT and cut-over after the build.

| Developers (total FTE ≈ ×1.4) | SP per sprint | Build sprints | Build weeks | **Total to Phase-1 go-live** |
|---|---|---|---|---|
| 8 (≈ 11) | 56 | 56 | 112 | ≈ 116 weeks (~27 months) |
| 12 (≈ 17) | 84 | 37 | 75 | ≈ 79 weeks (~18 months) |
| **16 (≈ 22)** | **112** | **28** | **56** | **≈ 62 weeks (~14 months)** |
| 20 (≈ 28) | 140 | 22 | 45 | ≈ 51 weeks (~12 months) |
| 75 | 525 | 6 | 12 | 16 weeks: **not realistic** |
| *Sensitivity: 16 devs at 10 SP/dev/sprint* | 160 | 20 | 39 | *≈ 45 weeks (~10.5 months)* |

**The critical path limits how far a bigger team helps.** Payroll is 709 SP and has to be built mostly in sequence (formula language → inputs → gross-to-net → insurance → PIT → files). About 6 developers can work on it effectively, so Payroll alone needs about 17 sprints (34 weeks). It also can't start in earnest until the Core, People and TECH foundations land (about sprint 3). That puts a **floor of roughly 10–11 months** on full Phase 1, however large the team.

## 5. How to bring the first payroll forward: stage by the statutory calendar
Not every P1 requirement is needed on the day of the first live payroll. Proposed release train:

| Release | Contents (examples) | Needed by |
|---|---|---|
| **R1: First live payroll** | Core masters, authorisation, workflow (basic), import engine; People records, contracts, salary record; T&A holiday calendar, leave, OT, Excel and device-file import, period close and hold; Payroll monthly gross-to-net, SI/HI/UI, monthly PIT withholding, payslips (PDF), bank file, monthly insurance declaration file; opening-balance import; single pilot tenant | Pilot go-live |
| **R2: within ~3 months of R1** | Retro across locked periods, off-cycle/bonus run (before the next Tết), final settlement and severance edge cases, loans, 183-day check, PIT 05/KK monthly/quarterly declaration polish, labour reports (semi-annual), operator back-office polish | Before the first semi-annual report and the next Tết bonus |
| **R3: before the end of Q1 of the following tax year** | PIT year-end finalisation (05/QTT and annexes), annual recompute, withholding certificates, annual leave carry-forward and encashment, self-service tenant provisioning and billing | PIT finalisation deadline |

To size R1 precisely, every story needs an R1/R2/R3 tag. That is the recommended next step: an **R1 slice estimated at ~1,700–1,900 SP** gives **≈ 9–10 months** with 16 developers. This is a PM estimate until the stories are tagged.

## 6. Effort levers (smaller savings, worth taking)
| Lever | Approx. saving | Trade-off |
|---|---|---|
| Pilot on a simplified platform: ops-provisioned single tenant, no dedicated-tier routing, DR as backup/restore instead of warm standby, static-then-rotated DB credentials | 120–180 SP | Must be completed before a second tenant onboards. Static credentials conflict with R2-15 and need DPO agreement. |
| Defer the separate reporting store (pilot reports on a read replica) | ~33 SP | Reporting load runs on the replica |
| Re-open OD-4: an open-source BPMN engine (e.g. Flowable) instead of the in-house workflow engine | ~30–40 SP | Adds a dependency, but the team builds less |
| Defer Core DOCX tenant templates, bulk approve, workflow console | ~21 SP | Vendor-maintained templates at the pilot |
| **Total** | **~200–275 SP (6–9%)** | Not enough on its own; the timeline still has to change |

## 7. Consolidation follow-ups (PM)
- **Cross-backlog dependencies** still use placeholders (`TECH:rls`, `CORE-WF`, …). They will be mapped to real story IDs when the sprint plan is built.
- **Done in consolidation:**
  - Estimation-guide §2 corrected: the formula engine and run orchestration are in Payroll, and the Legal Parameter Service is in SaaS. Each is estimated once.
  - NFR-SAAS-007 aligned with X-01: salary amounts are not column-encrypted.
- **TECH decisions to carry into the release plan:**
  - The sponsor's VN cloud choice (OD-1) is needed by the end of sprint 1.
  - Spike SP-2 (RLS performance) must finish early, because it affects every module's data access.
- **Calibrate:** measure actual velocity in sprints 1–2, then re-run §4 and fix the dates.
