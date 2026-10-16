# YourHRM — MVP Scope Re-rating (Phase 1 / 2 / 3)

| Item | Value |
|---|---|
| Document | `docs/01-requirements/brd/mvp-scope.md` |
| Version | 0.2 — 2026-10-16 (round 2 applied) |
| Author | Lead Business Analyst |
| Status | Round 1, round 2 and the R2-02 final scope sync applied. Open points: §11.4 (compliance conflicts; R2-C1 sponsor to decide) |
| Drives | [BRD.md](BRD.md) §5, §9, §10 · [decision-log.md](../../00-project-management/decision-log.md) X-04, OD-9, D-09, D-10, R2-01 – R2-19 (§7, §7.1, §7.2) · [project-charter.md](../../00-project-management/project-charter.md) §6 |
| Snapshot | Requirement IDs and old priorities were read from the six module BRDs on 2026-10-16. The module BRDs are being edited in parallel by their owners; any ID they add later must be rated here before the backlog freezes. |

| Version | Change |
|---|---|
| 0.1 | Round 1 re-rating (X-04, OD-9, D-09, D-10): P1 = 281 BRs, 61 NFRs. |
| 0.2 | Round 2 (R2-01 – R2-19): Option B applied (27 of 29 cuts), all live external integrations → P2, BR-ATT-063 and BR-SAAS-073 → P1, baseline column added (R2-18). **P1 = 254 BRs, 58 NFRs.** Changed rows in §9 are marked "(R2, 2026-10-16)". New §11 (round 2 changes) and §12 (phase-tag sync list for the module BRDs). |
| 0.2 (final sync) | R2-02 final scope sync: 9 new BA requirements rated (7 P1, 2 P2); 5 rows marked `Removed` because ownership moved (BR-CORE-038, 039, 042, 113, BR-SAAS-027), with their P1 capabilities confirmed in SaaS rows rated P1 + baseline; `API` baseline value added (R2-C9); T&A Phase column synced to §9; phase banner added to every module BRD, which replaces the §12 tag list. **P1 = 259 BRs, 58 NFRs.** |

## 1. Purpose

The sponsor decided on 2026-10-16 to cut the MVP. This document gives every BR and NFR in the six module BRDs a delivery phase — **P1** (Phase 1 / MVP, 16-week pilot), **P2** or **P3** — and records the reason for each one. From now on, **the new phase here overrides the MoSCoW priority in the module BRDs** when deciding what goes into Phase 1. MoSCoW still ranks items *within* a phase.

Decisions applied:

| Decision | What it means for scope |
|---|---|
| X-04 | Cut the MVP. "Phase 1 only core features on each module." All mobile app features go to Phase 2. Starting point: 310 Must BRs (Recruitment excluded). Target: about 180–200. |
| OD-9 | No PWA, mobile, GPS or selfie check-in in Phase 1. Pilot attendance comes from time devices and Excel import. ESS stays in Phase 1 as a responsive web app. |
| D-09 | No Oracle migration, staging or parallel run. It is replaced by a generic **opening-balance import** from Excel templates: employee master, YTD income/PIT/SI, SI contribution history, leave balances and loans. These are needed for a mid-year go-live and for PIT year-end finalisation. |
| D-10 | Billable employee = a person active at month end, counted per legal entity. |
| Charter §6 | Recruitment, appraisal, training, expenses, advanced rostering, report designer, white-label and BI (Superset) are Phase 2. |
| Sponsor rule | Everything statutory for Vietnam payroll compliance stays in Phase 1: SI/HI/UI, PIT including finalisation, labour contracts, OT caps and leave law. |
| R2-01, R2-19 (round 2) | Option B stretch cuts approved. **No live external integration in Phase 1**: time-device connector, Zalo OA/ZNS, SMS, push, e-sign, VNeID, accounting and bank APIs, job boards, public API/webhooks and the integration hub runtime are P2. Phase 1 keeps e-mail and in-app notifications, **file exports** (bank payment file, VSS declaration file, PIT XML for eTax, GL journal in Excel), Excel import (incl. a device-export-file template for punches), manual entry and correction requests. |
| R2-03, R2-07, R2-14, R2-16, R2-17, R2-18 (round 2) | One-month payroll comparison (BR-SAAS-073) and SI benefit-claim data (BR-ATT-063, minimal) → P1. Bonus/off-cycle run stays P1; Tết/13th-month policy rules → P2. Basic concurrent employment across legal entities in P1. Payslip as password-protected PDF by e-mail in P1. Baseline (always-on) capabilities marked in §9. Details: §11. |

## 2. Method & rating rules

1. **Extraction.** Every table row whose first cell is `BR-<MOD>-NNN` or `NFR-<MOD>-NNN` in sections §4 and §9 of the six module BRDs was extracted with `awk` (§10). That gives 566 rows, plus 2 new D-09 requirements. Split priorities are counted under their leading letter, as in BRD §9. For example, BR-CORE-039 "M/S/C" counts as M, and BR-PPL-058 "S→M" counts as S.
2. **The rules were applied in this order. The first rule that matches wins.**

| # | Rule | Phase |
|---|---|---|
| R1 | Statutory obligation for a VN employer (SI/HI/UI/OA-OD/TU, PIT incl. 05/KK, 05/QTT and e-certificates, minimum wage, labour contracts and probation, notice, severance, OT caps and categories, leave law, labour book and labour reports, PDPL, e-invoice, data residency) | P1, even if the old priority was S |
| R2 | Required for a mid-year go-live without migration (D-09): import framework, opening-balance templates, reconciliation, leave and loan balances | P1 |
| R3 | Data or processing that a statutory output depends on (legal entity, org, person, employment, assignment, salary, schedule, timesheet, period close, payroll run, bank file, GL) | P1 |
| R4 | Security, isolation, audit and privacy baseline (the product cannot go live without it) | P1 |
| R5 | ESS/MSS through **responsive web**: profile, leave, OT, corrections, payslips, PIT certificates, approvals inbox | P1 |
| R6 | Any mobile app feature, PWA, GPS/Wi-Fi/selfie/web check-in, push, mobile approval, or anything that depends on them (OD-9) | P2 |
| R7 | Whole module or capability deferred by the charter: Recruitment, appraisal, training, expenses, rostering, report designer, dashboards/BI, white-label, public API/webhooks, self-service sign-up, dedicated tier | P2 (an old `C` goes to P3) |
| R8 | Convenience, automation or configurability where Phase 1 has a workaround (manual step, seeded configuration, simpler rule) | P2 |
| R9 | Anything else keeps its MoSCoW mapping: M → P1, S → P2, C → P3. A module's own phase tag (T&A Phase column) is respected when it is later than this mapping. | — |

**Round 2 overrides (applied before R1–R9).** (a) A live external integration, or a requirement whose only purpose is one (punch-rate and offline-buffer NFRs), is P2. A file export or import that the customer uploads or downloads by hand stays in its R1–R9 phase. (b) The Option B list (§4) is P2, except where a more specific round 2 decision keeps an item in P1 (BR-PAY-102 by R2-17, BR-ATT-037 by R2-01 "manual entry"). (c) An explicit round 2 decision on a single ID (R2-03, R2-07) wins over everything else.

3. **Counting.** Every count in §3 is output from the script in §10, run over the extract joined with the rating file.

## 3. Results

All counts below are output from the §10 script, run on 2026-10-16 after the round 2 final scope sync. "R1" is version 0.1 of this document (round 1 decisions). "R2" is this version, including the R2-02 sync: 9 new BA requirements rated, and 5 rows removed from their old module because ownership moved (phase `Removed`, excluded from all counts).

### 3.1 Business requirements (BR), R1 vs R2

"BR total" counts active rows only. Removed rows are shown separately. Old M/S/C is the MoSCoW value before R1; "new" means added on 2026-10-16.

| Module | BR total | Old M | Old S | Old C | New | Removed | R1 P1 | R1 P2 | R1 P3 | **R2 P1** | R2 P2 | R2 P3 | Δ P1 (R2 − R1) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Core | 75 | 59 | 10 | 5 | 1 | 4 | 52 | 21 | 5 | **45** | 25 | 5 | −7 (5 Option B; 2 removed: 038, 113) |
| People | 95 | 65 | 23 | 5 | 2 | 0 | 50 | 38 | 5 | **46** | 44 | 5 | −4 (5 Option B; +1 new BR-PPL-023) |
| Time & Attendance | 80 | 61 | 16 | 3 | 0 | 0 | 61 | 19 | 0 | **54** | 26 | 0 | −7 (7 Option B, 1 connector; +1 BR-ATT-063) |
| Payroll | 91 | 72 | 12 | 3 | 4 | 0 | 72 | 12 | 3 | **71** | 17 | 3 | −1 (5 Option B; +4 new BR-PAY-065, 085–087) |
| Recruitment | 63 | 42 | 20 | 1 | 0 | 0 | 0 | 62 | 1 | **0** | 62 | 1 | 0 (whole module P2) |
| SaaS Platform | 90 | 55 | 25 | 6 | 4 | 1 | 46 | 39 | 4 | **43** | 43 | 4 | −3 (5 Option B, 1 connector; +1 BR-SAAS-073; +2 new BR-SAAS-090/091) |
| **Total** | **494** | **354** | **106** | **23** | **11** | **5** | **281** | **191** | **18** | **259** | **217** | **18** | **−22** |
| **Total excl. Recruitment** | **431** | **312** | **86** | **22** | **11** | **5** | **281** | **129** | **17** | **259** | **155** | **17** | **−22** |

**How round 2 moves P1 from 281 to 259 (net −22):**

| Change | P1 effect |
|---|---|
| Option B cuts (29 listed, 2 kept by later specific decisions) | −27 |
| Live-integration BRs moved to P2 (BR-ATT-021, BR-SAAS-065) | −2 |
| Promoted to P1 (BR-ATT-063, BR-SAAS-073) | +2 |
| New R2-02 BRs rated P1 (BR-PPL-023, BR-PAY-065, 085, 086, 087, BR-SAAS-090, 091) | +7 |
| P1 rows removed as moved to SaaS; the capability stays P1 in BR-SAAS-038/039/043/046 (BR-CORE-038, 113) | −2 |
| **Net** | **−22** |

Two new BRs are rated P2: BR-CORE-088 and BR-PPL-039. Details per ID: §11.

Round 1 note (unchanged). The R1 Must baseline was **316**. That figure includes six X-03 close-hold Must BRs (BR-ATT-079, 087, 088 and BR-PAY-082, 083, 084), which stay P1. In R1:
- four old Should BRs were promoted to P1 (BR-ATT-005, BR-ATT-049, BR-PAY-091, BR-SAAS-087)
- two D-09 BRs were added (BR-SAAS-088, 089)
- ten T&A S/C items tagged "MVP" moved to P2.

### 3.2 Non-functional requirements (NFR), R1 vs R2

| Module | NFR total | Old M | Old S | R1 P1 | R1 P2 | **R2 P1** | R2 P2 | R2 P3 |
|---|---|---|---|---|---|---|---|---|
| Core | 15 | 12 | 3 | 13 | 2 | **13** | 2 | 0 |
| People | 12 | 10 | 2 | 10 | 2 | **10** | 2 | 0 |
| Time & Attendance | 11 | 9 | 2 | 9 | 2 | **7** | 4 | 0 |
| Payroll | 13 | 11 | 2 | 11 | 2 | **11** | 2 | 0 |
| Recruitment | 11 | 8 | 3 | 0 | 11 | **0** | 11 | 0 |
| SaaS Platform | 22 | 19 | 3 | 18 | 4 | **17** | 5 | 0 |
| **Total** | **84** | **69** | **15** | **61** | **23** | **58** | **26** | **0** |

R1: NFR-CORE-009 (import throughput) was promoted to P1 for the opening-balance load, and NFR-SAAS-019 (full tenant export) moved to P2 with tenant offboarding. R2: NFR-ATT-001 (punch ingestion rate), NFR-ATT-004 (connector offline buffer) and NFR-SAAS-004 (punch burst) move to P2 with the live connector (R2-01). Spike SP-3 and the X-05 figure are therefore no longer P1 gates.

### 3.3 What the 259 P1 BRs are made of

Buckets follow the reason column. The R2 deltas were assigned by hand from the R1 buckets, so these figures are indicative, not awk output.

| Bucket | Core | People | T&A | Payroll | SaaS | **R2 total** | R1 total |
|---|---|---|---|---|---|---|---|
| Statutory (VN law, incl. PDPL) | 9 | 33 | 21 | 36 | 11 | **110** | 107 |
| Opening-balance import, comparison & billing (D-09/D-10, R2-03) | 5 | 0 | 2 | 2 | 7 | **16** | 16 |
| Security, privacy & audit baseline | 9 | 1 | 0 | 2 | 8 | **20** | 24 |
| ESS web | 2 | 3 | 3 | 2 | 2 | **12** | 13 |
| Foundation / core processing that the statutory outputs depend on (incl. X-03 close hold, job scheduler, pay groups) | 20 | 9 | 28 | 29 | 15 | **101** | 121 |
| **Total** | 45 | 46 | 54 | 71 | 43 | **259** | 281 |

### 3.4 Baseline capabilities (R2-18)

The **Baseline** column in §9 marks the capabilities that are always on, whatever modules a tenant subscribes to (gap-analysis GAP-060, decision R2-18, enforced by BR-SAAS-091). They must be built and tested in every tenant configuration, including "Payroll without T&A" and "People without Payroll". The column has two values:
- `B` means the whole requirement is baseline.
- `API` means only its read interface is an always-on service (R2-C9; the architects' point 2).

| Baseline capability (R2-18, BR-SAAS-091) | IDs marked in §9 | Count |
|---|---|---|
| Core (the whole P1 Core module) | every Core row with phase P1 | 45 BR + 13 NFR (`B`) |
| Identity and export services moved from Core to SaaS (R2-02) | BR-SAAS-038 password policy, 039 MFA, 043 sessions (former BR-CORE-038); BR-SAAS-046 reporting and list-view exports (former BR-CORE-113); BR-SAAS-091 baseline entitlement rule | 5 BR (`B`) |
| People basics (person, employment, identity, tax/SI profile incl. tax residency, bank, dependants, assignment, contract record, salary record and its validation) | BR-PPL-001–004, 010, 011, 013–017, 020, 021, 023, 030–032, 040, 041, 044, 050; NFR-PPL-001, 002, 005, 007, 008 | 21 BR + 5 NFR (`B`) |
| Legal parameters (incl. the national holiday template) | BR-PAY-010, NFR-PAY-007 (minimum-wage regions, BR-CORE-006, are already in Core) | 1 BR + 1 NFR (`B`) |
| Pay scales / bands and component catalogue (gap-analysis §5 #7, owned by Payroll) | BR-PAY-001, 002 (grades, BR-CORE-021, are in Core). The pay-scale table itself has no BR row yet, so the row must be marked `B` when it is added | 2 BR (`B`) |
| Excel attendance import (produces a standard Attendance Result) | BR-ATT-025 | 1 BR (`B`) |
| **Total `B`** | | **75 BR + 19 NFR** |
| API-level always-on services | Holiday-calendar and schedule read API: T&A **ATT-P-16** (MoSCoW S → M, R2-02), over BR-ATT-001, 002 and 011 (P1). Gross↔net simulator: Payroll **IF-PAY-P-15** over BR-PAY-124 (P2), so it becomes always-on only once built in P2 | 4 BR (`API`) |

## 4. Why P1 is 259 and not 180–200

**The target cannot be reached without breaking the compliance rule.** The 180–200 estimate in X-04 assumed savings from deferring appraisal, training, expenses, report designer and white-label. In the module BRDs, those items were already almost all `S`/`C`: only one of them (BR-PPL-094, probation appraisal) was a Must. The real reductions came from:
- Recruitment (−42 Must BRs, already outside the 316)
- mobile and check-in (OD-9)
- commercial automation
- the non-statutory People features in §5
- in round 2, the Option B cuts and the removal of live integrations.

The R2-02 single-owner sync then added 7 P1 BRs that close gaps in the chain or in statutory scope (tax residency, the 183-day check, pay groups, the job scheduler, and others).

Three things set the floor:

1. **110 P1 BRs are statutory.** SI, PIT (monthly, finalisation, e-certificates, residency), contracts, termination, OT and leave law, labour reports, PDPL and e-invoice cannot be cut under the sponsor's rule. Payroll alone has 36.
2. **About 101 BRs are the processing chain those outputs need.** This covers entity → org → person → assignment → salary → pay group → schedule → timesheet → period close (incl. the X-03 hold) → payroll run → bank/GL, and the job scheduler that runs them.
3. **D-09 adds work.** Without Oracle, the mid-year go-live depends on Excel opening balances (16 BRs, including the R2-03 comparison).

**Option B — applied in round 2 (R2-19).** These 29 BRs were listed as non-statutory, each with a Phase 1 workaround. 27 are now P2. Two stay P1 because a later, more specific round 2 decision covers them.

| Module | IDs | R2 status | Phase 1 workaround |
|---|---|---|---|
| Core (5) | BR-CORE-051, 053, 058, 071, 083 | all P2 | Vendor-seeded, sequential-only approval routes; vendor-maintained templates and code lists; no re-resolution on reorg |
| People (5) | BR-PPL-005, 018, 019, 022, 133 | all P2 | HR keys changes directly; documents as plain attachments; seniority choice by HR adjustment; keep one qualification field for the labour book (§11.4 R2-C5) |
| T&A (8) | BR-ATT-026, 034, 043, 066, 070, 075, 083 → P2; **BR-ATT-037 stays P1** | 7 P2, 1 kept | One policy per legal entity; OT by pre-approval only; trips and WFH as attendance codes. BR-ATT-037 (admin timesheet edit) is the "manual entry" that R2-01 keeps in P1 |
| Payroll (6) | BR-PAY-021, 073, 090, 112, 114 → P2; **BR-PAY-102 stays P1** | 5 P2, 1 kept | Fixed net scope; advances via the Advance run type or as variable input; cost by default cost centre. BR-PAY-102 (password-protected PDF payslip) is kept by R2-17 |
| SaaS (5) | BR-SAAS-009, 013, 036, 037, 053 | all P2 | Toggles by configuration; manual headcount report for billing; manual two-person break-glass procedure; operator-access log exported on request |

**Recommendation.** Size the 259 BRs in story points before committing to 16 weeks. Option B is now used up, so **no descope reserve is left** short of statutory scope. The remaining levers are the open conflicts in §11.4 (for example R2-C1: issuing the vendor's e-invoices outside the platform, −2 BRs) or a later go-live date.

## 5. Notable cuts (old M → P2, Recruitment excluded; R1 list, round 2 additions in §11)

| Area | Moved to P2 | Phase 1 workaround |
|---|---|---|
| Org & positions | BR-CORE-013/014/015 reorganisation packages; BR-CORE-022/023, BR-PPL-038 position control | Effective-dated org edits; assignment to job title + org unit |
| Identity | BR-CORE-039 + BR-SAAS-040 SSO (OIDC); BR-SAAS-035 impersonation | Local login + MFA; support works inside a consent grant |
| Workflow | BR-CORE-055 delegation, BR-CORE-057 SLA escalation, BR-PPL-122 | HR reassignment (BR-CORE-056) |
| HR operations | BR-PPL-060–063 checklists, BR-PPL-061 pre-hire portal, BR-PPL-076 post-separation tracking, BR-PPL-006 timeline, BR-PPL-033 concurrent positions, BR-PPL-121 MSS team views | Manual checklists; approvals through the Core inbox |
| Discipline & rewards | BR-PPL-080/081 discipline procedure, BR-PPL-085 rewards → payroll, BR-PPL-094 probation appraisal | Offline procedure with documents uploaded; rewards as payroll input; probation outcome via BR-PPL-052 |
| T&A | BR-ATT-081 MSS dashboard (BR-ATT-063 SI-claim data was here in R1; back to P1 in R2-07); plus MVP-tagged S/C: web check-in (024), TOIL (050), flexible schedules (012), day swap (004), team calendar (069), timesheet confirmations (077/078), auto-escalation (084), cross-entity OT cap (047), SI sick-day tracking (064) | Devices + Excel; per-entity OT cap |
| Payroll | BR-PAY-006 formula IDE | Syntax validation in BR-PAY-005 |
| Commercial | BR-SAAS-006 plans/editions, 010 price books, 011 proration, 017 dunning, 018/019 tenant offboarding export & deletion | Contract price per module; manual collections; offboarding built before the first tenant exit |
| Platform | BR-SAAS-027 custom fields, 047 separate reporting store, 068 integration hub, 082 retention purge jobs (073 parallel-run comparison was here in R1; P1 in R2-03) | File-based integrations; per-tenant quotas; no retention expiry in the pilot year |
| Mobile (already S) | BR-SAAS-076/077/078, BR-ATT-023, BR-CORE-059, NFR-ATT-009/011, NFR-SAAS-022 | Responsive ESS web |

## 6. Requirements changed or added because of D-09 (Oracle → opening-balance import)

| ID | Change | New phase | Where |
|---|---|---|---|
| BR-CORE-081 | Old→new admin-unit mapping now serves opening-balance imports and old ID cards, not Oracle | P1 | 01-core.md (edited) |
| BR-CORE-114 | "Oracle migration mode" → **opening-balance import mode**: bulk load, workflow bypass, external reference IDs, reconciliation report | P1 | 01-core.md (edited) |
| BR-CORE-110/111/112 | Unchanged text; now P1 because the opening-balance import relies on them | P1 | — |
| NFR-CORE-009 | Import throughput promoted S → P1 | P1 | rating only |
| NFR-PPL-007 | "Legacy (Oracle) history import" → **opening employee data import**: current person, employment, assignment, salary, contract, dependants, bank, plus the salary history needed for severance averages | P1 | 02-people.md (edited) |
| BR-ATT-068 | Unchanged; it is the ledger entry used for opening leave balances | P1 | — |
| BR-ATT-086 | Opening leave balances (per type and year, incl. carry-forward) and YTD OT hours for the caps. No historical timesheets. | P1 | 03 (changed by the T&A BA, 2026-10-16) |
| BR-PAY-091 | Employee loans promoted S → P1, interest-free instalments only, so opening loan balances can be recovered | P1 | **Owner Payroll BA** |
| BR-PAY-122 | Opening payroll balances from Excel: YTD taxable income, PIT, SI basis and contributions; SI history; 6–12 months of salary history; outstanding loans and advances | P1 | 04 (changed by the Payroll BA, 2026-10-16) |
| BR-SAAS-031 | Sandbox note: no longer required for migration | P2 | 06 (edited) |
| BR-SAAS-069 | Template list extended: YTD income/PIT/SI by month, SI contribution history, leave balances, loans | P1 | 06 (edited) |
| BR-SAAS-071 | Oracle ETL → **opening-balance import for mid-year go-live** | P1 | 06 (edited) |
| BR-SAAS-072 | Reconciliation now checks against **customer-supplied control totals** (last legacy payroll register, VSS C12-TS, leave report) | P1 | 06 (edited) |
| BR-SAAS-073 | Parallel run removed → optional comparison against a customer-supplied Excel payroll register | P2 in R1 → **P1 in R2 (R2-03)** | 06 (edited); phase per §9 (banner rule, §12) |
| **BR-SAAS-088** | **New:** go-live cut-over month per legal entity; opening balances lock after the first approved run; later corrections only by audited adjustment batches | P1 | 06 (added) |
| **BR-SAAS-089** | **New:** pre-go-live monthly income/PIT per employee for monthly cumulative PIT, 05/QTT finalisation and full-year e-certificates | P1 | 06 (added) |
| NFR-SAAS-008 | Oracle credentials clause removed | P1 | 06 (edited) |
| P9 (06 §3) | Oracle migration process → opening-balance import process | — | 06 (edited) |

Related change for D-10: BR-SAAS-012 now defines the billable employee as an active person at month end, per legal entity (06, edited).

## 7. Follow-ups for other owners (files not edited by the Lead BA)

| Owner | Item |
|---|---|
| T&A BA (03) | BR-ATT-024 web check-in → P2 (OD-9). BR-ATT-025 note "also migration tool" → "also opening-balance import". Phase column for BR-ATT-004/012/047/050/064/069/077/078/084 → P2. BR-ATT-063, BR-ATT-081 → P2. |
| Payroll BA (04) | BR-PAY-091 → P1 (minimal, interest-free instalments) so opening loan balances can be recovered; BR-PAY-006 → P2. |
| Integration analyst (gap-analysis) | Re-point gaps that cite Oracle migration, parallel run or BR-SAAS-071/073; check gaps on the deferred items in §5. |
| Solution / data architects | Drop migration staging; add the opening-balance import batch and lock (BR-SAAS-088); the reporting store (BR-SAAS-047) is no longer needed in P1. |
| All module BAs (round 2) | Done 2026-10-16: phase banner added to 01–06 and the T&A Phase column synced (§12). Phase is set only in §9. |

## 8. Items the sponsor must re-confirm (outcome of round 2)

| # | Item (R1) | Outcome (round 2, 2026-10-16) |
|---|---|---|
| 1 | P1 = 281 BRs against the 180–200 target; Option B? | Option B approved (R2-19) and live integrations removed (R2-01) → P1 = 254; after the R2-02 sync (+7 new, −2 moved) **P1 = 259**. Story-point sizing still required. |
| 2 | No parallel run: one-month comparison (BR-SAAS-073)? | **P1** (R2-03). |
| 3 | SSO deferred | Confirmed P2 (R2-04). |
| 4 | Discipline procedure deferred | Confirmed P2 (R2-05). |
| 5 | Web check-in deferred | Confirmed P2 (R2-06). Sites without devices use Excel import. |
| 6 | E-labour contract | Confirmed P2 unless a decree mandates it (R2-10). |
| 7 | Multi-currency | Confirmed P2, no USD contracts (R2-09). |
| 8 | Cross-entity annual OT cap | Confirmed P2 pending legal advice (R2-08, R2-16). |
| 9 | D-10 billing twice for two legal entities | Not answered; D-10 stands (§7.2). Suspended employees are not billable (R2-12a). |
| 10 | Loans promoted to P1 | No objection; stays P1. |
| 11 | Opening-balance data owners | Customer prepares, implementation consultant validates, customer C&B lead signs (R2-13). |
| 12 | Tết/13th-month run (Feb 2027) | Bonus/off-cycle run with correct PIT stays P1; Tết policy rules P2; test with sample data (R2-14). |

## 9. Full rating tables

### 9.1 Core — [01-core.md](modules/01-core.md)

| ID | Short title | Old priority | New phase | Reason | Baseline |
|---|---|---|---|---|---|
| BR-CORE-001 | Company group & 1..n legal entities | M | P1 | Foundation: multi-entity payroll | B |
| BR-CORE-002 | Legal entity attributes (MST, SI code, region) | M | P1 | Statutory identifiers for SI/PIT filing | B |
| BR-CORE-003 | MST format validation | M | P1 | Statutory format | B |
| BR-CORE-004 | Effective-dated legal-entity attributes | M | P1 | Statutory: period-correct declarations | B |
| BR-CORE-005 | Branches / work locations | M | P1 | Foundation: min-wage region, PIT branch (geo-fence data P2) | B |
| BR-CORE-006 | Minimum-wage region table | M | P1 | Statutory: regional minimum wage | B |
| BR-CORE-007 | Region recompute + LocationRegionChanged | M | P1 | Statutory: min-wage checks | B |
| BR-CORE-008 | Inter-entity relationships | C | P3 | Could; not pilot-critical |  |
| BR-CORE-010 | Hierarchical org tree, unit types | M | P1 | Core structure | B |
| BR-CORE-011 | Org unit attributes | M | P1 | Core structure | B |
| BR-CORE-012 | Effective-dated org (as-of) | M | P1 | Core: retro and reporting correctness | B |
| BR-CORE-013 | Reorganisation packages (impact, approval) | M | P2 | Advanced; P1 uses effective-dated edits |  |
| BR-CORE-014 | Merge/split mapping of positions/employees | M | P2 | Depends on BR-CORE-013 |  |
| BR-CORE-015 | Back-dated reorg permission & notice | M | P2 | Depends on BR-CORE-013 |  |
| BR-CORE-016 | Interactive org chart | S | P2 | Should; convenience |  |
| BR-CORE-017 | Matrix / functional reporting lines | C | P3 | Could |  |
| BR-CORE-020 | Job families & titles (hazardous flag) | M | P1 | Statutory flag drives leave/retirement | B |
| BR-CORE-021 | Grades / levels with reference range | M | P1 | Foundation for pay scales | B |
| BR-CORE-022 | Positions as budgeted seats | M | P2 | Cut: P1 assigns job title + org unit (position optional) |  |
| BR-CORE-023 | Position-control mode setting | M | P2 | Depends on BR-CORE-022 (P2) |  |
| BR-CORE-024 | Position occupancy incl. recruitment | S | P2 | Should; needs Recruitment (P2) |  |
| BR-CORE-025 | Cost centres & default per unit | M | P1 | Needed for GL export | B |
| BR-CORE-026 | Employee category code list | M | P1 | Drives payroll/SI rules | B |
| BR-CORE-030 | User management | M | P1 | Core access | B |
| BR-CORE-031 | Function permissions & roles | M | P1 | Core access | B |
| BR-CORE-032 | Role data scope | M | P1 | Core access; salary confidentiality | B |
| BR-CORE-033 | Derived LINE_MANAGER / SELF scopes | M | P1 | Core: ESS/approvals without admin work | B |
| BR-CORE-034 | Field-level sensitivity masking | M | P1 | Statutory: PDPL sensitive data | B |
| BR-CORE-035 | Server-side scope enforcement | M | P1 | Security baseline | B |
| BR-CORE-036 | Time-bound role grants with approval | S | P2 | Should |  |
| BR-CORE-037 | Segregation-of-duties rules | S | P2 | Should |  |
| BR-CORE-038 | Local login, password policy, MFA, sessions | M | Removed | Removed — owned by saas-platform: BR-SAAS-038 (password policy), 039 (MFA), 043 (sessions), all P1 + baseline (R2-02, 2026-10-16) |  |
| BR-CORE-039 | SSO OIDC/SAML/SCIM | M/S/C | Removed | Removed — owned by saas-platform: BR-SAAS-040/041/042 (SSO P2, SCIM P3) (R2-02, 2026-10-16) |  |
| BR-CORE-040 | Access delegation ("act as") | C | P3 | Could |  |
| BR-CORE-041 | Break-glass support access logged | M | P1 | Security/PDPL baseline | B |
| BR-CORE-042 | Tenant API clients | S | Removed | Removed — owned by saas-platform: BR-SAAS-056 API clients (P2) (R2-02, 2026-10-16) |  |
| BR-CORE-050 | Generic workflow engine / process types | M | P1 | Core engine for all approvals | B |
| BR-CORE-051 | No-code workflow definitions | M | P2 | Option B cut (R2-19): vendor-seeded approval routes per process type; tenant changes via support (R2, 2026-10-16) |  |
| BR-CORE-052 | Approver resolvers | M | P1 | Core engine | B |
| BR-CORE-053 | Step types (parallel branches P2) | M | P2 | Option B cut (R2-19): P1 engine is sequential single-approver steps only (R2, 2026-10-16) |  |
| BR-CORE-054 | Workflow actions | M | P1 | Core engine | B |
| BR-CORE-055 | Approval delegation by user | M | P2 | Cut: HR reassignment (056) covers absence |  |
| BR-CORE-056 | Reassignment & fallback approver | M | P1 | Core: no stuck approvals | B |
| BR-CORE-057 | SLA reminders & escalation | M | P2 | Convenience; manual chasing in pilot |  |
| BR-CORE-058 | Approver-chain snapshot / re-resolve | M | P2 | Option B cut (R2-19): no re-resolution on reorg; HR reassignment (BR-CORE-056) covers it (R2, 2026-10-16) |  |
| BR-CORE-059 | Approve from mobile app / email / Zalo links | S | P2 | Mobile & channels P2 (OD-9) |  |
| BR-CORE-060 | Workflow events + My tasks inbox | M | P1 | Core: approvals inbox (ESS web) | B |
| BR-CORE-061 | Immutable approval history | M | P1 | Audit evidence | B |
| BR-CORE-070 | Notification service (in-app + email only) | M/S/C | P1 | Core; P1 channels = in-app + e-mail only (R2-01) | B |
| BR-CORE-071 | Notification templates VI/EN | M | P2 | Option B cut (R2-19): vendor-maintained VI/EN templates, no tenant overrides (R2, 2026-10-16) |  |
| BR-CORE-072 | User channel preferences | S | P2 | Should |  |
| BR-CORE-073 | Scheduling, digests, fallback, tracking | S | P2 | Should |  |
| BR-CORE-074 | No sensitive data in clear notifications | M | P1 | PDPL; payslip password-protected PDF is the agreed exception (R2-17) | B |
| BR-CORE-075 | Internal announcements | C | P3 | Could |  |
| BR-CORE-080 | Admin units (34 provinces, wards) | M | P1 | Statutory master data | B |
| BR-CORE-081 | Old→new admin-unit mapping | M | P1 | Needed for opening-balance import & old IDs (changed D-09) | B |
| BR-CORE-082 | Global code lists (banks, ethnic, ID types) | M | P1 | Statutory master data | B |
| BR-CORE-083 | Tenant custom code lists | M | P2 | Option B cut (R2-19): vendor-maintained code lists; tenant values added by support (R2, 2026-10-16) |  |
| BR-CORE-084 | Hierarchical tenant settings | M | P1 | Core configuration | B |
| BR-CORE-085 | Numbering service | M | P1 | Contracts/decisions numbering | B |
| BR-CORE-086 | Custom fields | S | P2 | Should. MoSCoW S → M in 01-core (R2-02, PM-approved 2026-10-16); phase stays P2 (custom fields deferred) |  |
| BR-CORE-087 | Master-data distribution with change notice | M | P2 | P1 updates ship with releases |  |
| BR-CORE-088 | Exchange-rate table (master data) | new (R2-02, 2026-10-16) | P2 | Multi-currency payroll is P2 (R2-09); the table is needed only for foreign-currency pay |  |
| BR-CORE-090 | Data-change audit | M | P1 | Audit / inspection evidence | B |
| BR-CORE-091 | Security event logging | M | P1 | Security baseline | B |
| BR-CORE-092 | Tamper-evident audit log | M | P1 | Audit evidence | B |
| BR-CORE-093 | Audit search & export | M | P1 | Auditor / inspector access | B |
| BR-CORE-094 | Configurable audit retention per category | M | P2 | P1 uses fixed platform minimum |  |
| BR-CORE-100 | Document service (upload, scan, storage) | M | P1 | Core | B |
| BR-CORE-101 | File access by record scope | M | P1 | Security | B |
| BR-CORE-102 | Document templates → PDF | M | P1 | Contracts/decisions | B |
| BR-CORE-103 | E-signature adapter | S | P2 | Should (P1 if e-contract mandated) |  |
| BR-CORE-104 | Retention-class archival/deletion | S | P2 | Should |  |
| BR-CORE-110 | Import framework | M | P1 | Needed for opening-balance import (D-09) | B |
| BR-CORE-111 | Async imports, dry-run, error file | M | P1 | Needed for opening-balance import (D-09) | B |
| BR-CORE-112 | Excel template generation | M | P1 | Needed for opening-balance import (D-09) | B |
| BR-CORE-113 | Export framework | M | Removed | Removed — owned by saas-platform: BR-SAAS-046 reporting and list-view export framework (P1 + baseline); async exports BR-SAAS-053 (P2) (R2-02, 2026-10-16) |  |
| BR-CORE-114 | Opening-balance import mode (was Oracle migration mode) | M | P1 | Changed D-09 | B |
| BR-CORE-115 | Scheduled exports / outbound integrations | C | P3 | Could |  |
| NFR-CORE-001 | Tenant isolation RLS | M | P1 | Security baseline | B |
| NFR-CORE-002 | Scope check ≤20 ms | M | P1 | Performance baseline | B |
| NFR-CORE-003 | Org as-of query ≤1 s | M | P1 | Performance baseline | B |
| NFR-CORE-004 | Workflow throughput, no lost tasks | M | P1 | Integrity | B |
| NFR-CORE-005 | Notification latency | M | P1 | Payslip publishing | B |
| NFR-CORE-006 | Login availability | M | P1 | Availability | B |
| NFR-CORE-007 | Password hashing & encryption | M | P1 | Security baseline | B |
| NFR-CORE-008 | Audit write guarantee | M | P1 | Integrity | B |
| NFR-CORE-009 | Import/export throughput | S | P1 | Promoted: opening-balance import volume (D-09) | B |
| NFR-CORE-010 | File limits & signed URLs | M | P1 | Security | B |
| NFR-CORE-011 | VI/EN, accent-insensitive search | M | P1 | Localisation | B |
| NFR-CORE-012 | WCAG AA, mobile approval | S | P2 | Should; mobile P2 |  |
| NFR-CORE-013 | Config export/import between environments | S | P2 | Should |  |
| NFR-CORE-014 | OWASP ASVS L2, rate limiting | M | P1 | Security baseline | B |
| NFR-CORE-015 | Idempotent effective-date jobs | M | P1 | Integrity | B |

### 9.2 People — [02-people.md](modules/02-people.md)

| ID | Short title | Old priority | New phase | Reason | Baseline |
|---|---|---|---|---|---|
| BR-PPL-001 | One Person per individual (CCCD/passport) | M | P1 | Foundation: identity for SI/PIT | B |
| BR-PPL-002 | Multiple/concurrent employments | M | P1 | Basic concurrent employment across legal entities in P1 (R2-16) | B |
| BR-PPL-003 | Employment attributes & status | M | P1 | Foundation | B |
| BR-PPL-004 | Primary employment for SI & dependants | M | P1 | Primary employment drives PIT family deduction and single SI employer (R2-16; see §11 R2-C2) | B |
| BR-PPL-005 | Rehire history & seniority choice | M | P2 | Option B cut (R2-19): rehire seniority choice by HR leave-balance adjustment (BR-ATT-068) (R2, 2026-10-16) |  |
| BR-PPL-006 | Employee timeline view | M | P2 | Convenience; history kept in records |  |
| BR-PPL-010 | Identity & personal data | M | P1 | Statutory: labour book, SI/PIT fields | B |
| BR-PPL-011 | Identity documents & expiry | M | P1 | Statutory identifiers | B |
| BR-PPL-012 | VNeID verification | S | P2 | Should; connector P2/P3 |  |
| BR-PPL-013 | Personal tax code (= CCCD) | M | P1 | Statutory: PIT | B |
| BR-PPL-014 | SI number, HI card, facility | M | P1 | Statutory: SI/HI | B |
| BR-PPL-015 | Bank accounts & split | M | P1 | Needed for salary payment | B |
| BR-PPL-016 | Family members & PIT dependants | M | P1 | Statutory: PIT family deduction | B |
| BR-PPL-017 | Dependant approval & registration form | M | P1 | Statutory: dependant registration | B |
| BR-PPL-018 | Emergency contacts | M | P2 | Option B cut (R2-19): emergency contact kept as free-text note (R2, 2026-10-16) |  |
| BR-PPL-019 | Education & certificates | M | P2 | Option B cut (R2-19): certificates as plain attachments; labour-book qualification must stay as one field (see §11 R2-C5) (R2, 2026-10-16) |  |
| BR-PPL-020 | Foreign-worker work permit | M | P1 | Statutory: work permit validity | B |
| BR-PPL-021 | Special-status attributes | M | P1 | Statutory: drives OT/leave/termination protection | B |
| BR-PPL-022 | ESS personal data change requests | M | P2 | Option B cut (R2-19): HR keys personal-data changes directly; ESS profile view stays (BR-PPL-120) (R2, 2026-10-16) |  |
| BR-PPL-023 | Tax residency status, arrival/departure dates | new (R2-02, 2026-10-16) | P1 | Statutory: PIT resident vs non-resident (PIT Law; Circular 111/2013); People basics tax profile (BR-SAAS-091) | B |
| BR-PPL-030 | Effective-dated assignments | M | P1 | Foundation | B |
| BR-PPL-031 | Assignment attributes | M | P1 | Foundation for T&A/payroll | B |
| BR-PPL-032 | Change reasons catalogue | M | P1 | Foundation; drives decisions | B |
| BR-PPL-033 | Concurrent positions (kiêm nhiệm) | M | P2 | Not pilot-critical; allowance via payroll input |  |
| BR-PPL-034 | Secondment | S | P2 | Should |  |
| BR-PPL-035 | Temporary-transfer rule (Art. 29) | M | P1 | Statutory: Labour Code |  |
| BR-PPL-036 | Mass changes | S | P2 | Should |  |
| BR-PPL-037 | Future/retro changes with retro flag | M | P1 | Payroll retro correctness |  |
| BR-PPL-038 | Position incumbency | M | P2 | Depends on BR-CORE-022 (P2) |  |
| BR-PPL-039 | Reorg mapping execution on core.ReorgActivated | new (R2-02, 2026-10-16) | P2 | Reorganisation packages are P2 (BR-CORE-013) |  |
| BR-PPL-040 | Effective-dated salary records | M | P1 | Foundation for payroll & SI basis | B |
| BR-PPL-041 | Salary vs band & regional minimum | M | P1 | Statutory: minimum wage | B |
| BR-PPL-042 | Step progression schedules | S | P2 | Should |  |
| BR-PPL-043 | Salary review campaigns | C | P3 | Could |  |
| BR-PPL-044 | Salary visibility by role | M | P1 | Confidentiality | B |
| BR-PPL-050 | Contract types | M | P1 | Statutory: labour contracts  (R2-02: baseline, 2026-10-16) | B |
| BR-PPL-051 | Probation limits | M | P1 | Statutory: Labour Code |  |
| BR-PPL-052 | Probation outcome & contract | M | P1 | Statutory: Labour Code |  |
| BR-PPL-053 | 36-month / two-definite rule | M | P1 | Statutory: Labour Code |  |
| BR-PPL-054 | Contract expiry monitoring | M | P1 | Statutory: auto-conversion risk |  |
| BR-PPL-055 | Contract appendices validation | M | P1 | Statutory: Art. 22 |  |
| BR-PPL-056 | Contract suspension | M | P1 | Statutory; affects SI/T&A |  |
| BR-PPL-057 | Contract document generation | M | P1 | Core HR output |  |
| BR-PPL-058 | Electronic labour contracts | S→M | P2 | P2 unless a decree mandates it before go-live (R2-10 confirmed) |  |
| BR-PPL-059 | Collective labour agreement reference | C | P3 | Could |  |
| BR-PPL-060 | Checklist templates | M | P2 | Not statutory; manual in pilot |  |
| BR-PPL-061 | Pre-hire portal | M | P2 | Not pilot-critical |  |
| BR-PPL-062 | Offboarding handover/clearance | M | P2 | Not statutory; manual in pilot |  |
| BR-PPL-063 | Checklist dashboards & reminders | M | P2 | Depends on BR-PPL-060 |  |
| BR-PPL-070 | Separation reasons → legal grounds | M | P1 | Statutory: severance eligibility |  |
| BR-PPL-071 | Notice period computation | M | P1 | Statutory: Labour Code |  |
| BR-PPL-072 | ESS resignation request | M | P1 | ESS web (Phase 1) |  |
| BR-PPL-073 | Protected-status termination block | M | P1 | Statutory: Labour Code |  |
| BR-PPL-074 | Severance / job-loss eligibility | M | P1 | Statutory: final settlement |  |
| BR-PPL-075 | Termination events & settlement deadline | M | P1 | Statutory: 14/30-day settlement |  |
| BR-PPL-076 | Post-separation obligation tracking | M | P2 | Checklist (P2); SI decrease filed by Payroll |  |
| BR-PPL-077 | Mass layoff support | C | P3 | Could |  |
| BR-PPL-080 | Discipline case procedure | M | P2 | Pilot handles discipline offline + documents (R2-05 confirmed) |  |
| BR-PPL-081 | Discipline limitation & no-fine rule | M | P2 | Depends on BR-PPL-080 (R2-05 confirmed) |  |
| BR-PPL-082 | Temporary suspension from work | S | P2 | Should |  |
| BR-PPL-083 | Material liability instalments | S | P2 | Should; cap enforced by BR-PAY-092 |  |
| BR-PPL-084 | Discipline expungement | S | P2 | Should |  |
| BR-PPL-085 | Rewards → payroll | M | P2 | Cut: rewards entered as payroll variable input |  |
| BR-PPL-090 | Appraisal cycles | S | P2 | Appraisal is Phase 2 |  |
| BR-PPL-091 | KPI library / OKR | S | P2 | Appraisal is Phase 2 |  |
| BR-PPL-092 | Reviews & calibration | S | P2 | Appraisal is Phase 2 |  |
| BR-PPL-093 | AppraisalFinalised to payroll | S | P2 | Appraisal is Phase 2 |  |
| BR-PPL-094 | Probation appraisal form | M | P2 | Cut: outcome recorded via BR-PPL-052 |  |
| BR-PPL-100 | Training catalogue & sessions | S | P2 | Training is Phase 2 |  |
| BR-PPL-101 | Training bonds | S | P2 | Training is Phase 2 |  |
| BR-PPL-102 | Mandatory training tracking | S | P2 | Training is Phase 2 |  |
| BR-PPL-103 | Training time to T&A | C | P3 | Could |  |
| BR-PPL-110 | Expense categories & limits | S | P2 | Expenses are Phase 2 |  |
| BR-PPL-111 | Cash advance & settlement | S | P2 | Expenses are Phase 2 |  |
| BR-PPL-112 | Reimbursement routing | S | P2 | Expenses are Phase 2 |  |
| BR-PPL-113 | Trip request linked to expenses | S | P2 | Expenses are Phase 2 (T&A trips stay P1) |  |
| BR-PPL-120 | ESS profile, contracts, dependants, resignation | M | P1 | ESS web (Phase 1) |  |
| BR-PPL-121 | MSS team views & initiate changes | M | P2 | Cut: managers approve via Core inbox in P1 |  |
| BR-PPL-122 | Manager approval delegation | M | P2 | Depends on BR-CORE-055 (P2) |  |
| BR-PPL-123 | ESS VI/EN, responsive web | M | P1 | ESS web (Phase 1) |  |
| BR-PPL-130 | HR decision generation | M | P1 | Statutory documents (quyết định) |  |
| BR-PPL-131 | People template versioning UI | M | P2 | Cut: templates via BR-CORE-102 in P1 |  |
| BR-PPL-132 | HR letters on request | S | P2 | Should |  |
| BR-PPL-133 | Employee document repository | M | P2 | Option B cut (R2-19): documents as plain attachments via Core document service (BR-CORE-100) (R2, 2026-10-16) |  |
| BR-PPL-134 | Digital signature of decisions | S | P2 | Should |  |
| BR-PPL-140 | Labour management book | M | P1 | Statutory: Decree 145 |  |
| BR-PPL-141 | Semi-annual labour-usage report | M | P1 | Statutory: Decree 145 |  |
| BR-PPL-142 | Monthly labour-fluctuation notice | M | P1 | Statutory: Employment Law 2025 |  |
| BR-PPL-143 | Foreign-worker reports | S | P2 | Should |  |
| BR-PPL-144 | Initial labour declaration | C | P3 | Could |  |
| BR-PPL-150 | Personal-data classification | M | P1 | Statutory: PDPL |  |
| BR-PPL-151 | Consent per purpose | M | P1 | Statutory: PDPL |  |
| BR-PPL-152 | Sensitive-field masking & reveal log | M | P1 | Statutory: PDPL |  |
| BR-PPL-153 | Data-subject requests | M | P1 | Statutory: PDPL |  |
| BR-PPL-154 | Post-separation retention purge | S | P2 | Should; no purge due in pilot year |  |
| NFR-PPL-001 | As-of / as-known effective dating | M | P1 | Integrity | B |
| NFR-PPL-002 | Transactional event outbox | M | P1 | Integrity | B |
| NFR-PPL-003 | Profile/search performance | M | P1 | Performance baseline |  |
| NFR-PPL-004 | Bulk ops 5,000 in ≤10 min | S | P2 | Depends on mass change (P2) |  |
| NFR-PPL-005 | Field-level encryption | M | P1 | PDPL / security | B |
| NFR-PPL-006 | Sensitive read/write logging | M | P1 | PDPL |  |
| NFR-PPL-007 | Opening employee data import (was Oracle history import) | M | P1 | Changed D-09 | B |
| NFR-PPL-008 | No cross-tenant matching | M | P1 | Security | B |
| NFR-PPL-009 | VI/EN, diacritics search | M | P1 | Localisation |  |
| NFR-PPL-010 | PDF/A documents | M | P1 | Archiving |  |
| NFR-PPL-011 | ESS on mobile browsers ≥360 px | M | P1 | ESS responsive web (no app) |  |
| NFR-PPL-012 | Document retention & legal hold | S | P2 | Should |  |

### 9.3 Time & Attendance — [03-time-attendance.md](modules/03-time-attendance.md)

Old priority shows the MoSCoW value and, in brackets, the module's own Phase tag.

| ID | Short title | Old priority | New phase | Reason | Baseline |
|---|---|---|---|---|---|
| BR-ATT-001 | Holiday calendars per entity | M (MVP) | P1 | Statutory: public holidays ; always-on read API (R2-C9, 2026-10-16) | API |
| BR-ATT-002 | VN public-holiday pack (lunar) | M (MVP) | P1 | Statutory: public holidays ; always-on read API (R2-C9, 2026-10-16) | API |
| BR-ATT-003 | Substitute day when holiday on rest day | M (MVP) | P1 | Statutory: Art. 112(3) |  |
| BR-ATT-004 | Day swap / make-up day | S (MVP) | P2 | Optional for private employers |  |
| BR-ATT-005 | Extra holidays for foreign employees | S (MVP) | P1 | Promoted: statutory Art. 112(2) |  |
| BR-ATT-006 | Calendar change → recalculation/retro | M (MVP) | P1 | Integrity |  |
| BR-ATT-010 | Shift definitions | M (MVP) | P1 | Core T&A |  |
| BR-ATT-011 | Weekly schedule patterns (rotating P2) | M (MVP) | P1 | Core T&A ; always-on read API (R2-C9, 2026-10-16) | API |
| BR-ATT-012 | Flexible schedules | S (MVP) | P2 | Not pilot-critical |  |
| BR-ATT-013 | Split shifts | S (P2) | P2 | Module tag P2 |  |
| BR-ATT-014 | Weekly rest day per schedule | M (MVP) | P1 | Statutory: OT category, Art. 111 |  |
| BR-ATT-015 | Effective-dated schedule assignment | M (MVP) | P1 | Core T&A |  |
| BR-ATT-016 | Standard working days/hours | M (MVP) | P1 | Payroll proration input |  |
| BR-ATT-017 | Roster planning | S (P2) | P2 | Advanced rostering is Phase 2 |  |
| BR-ATT-018 | Roster validation | S (P2) | P2 | Advanced rostering is Phase 2 |  |
| BR-ATT-019 | Shift swaps | C (P2) | P2 | Advanced rostering is Phase 2 |  |
| BR-ATT-020 | Immutable raw punch store | M (MVP) | P1 | Core T&A; inspection evidence |  |
| BR-ATT-021 | Device connector (1) + file import | M (MVP) | P2 | Live connector P2 (R2-01); device-software export files (CSV/Excel) imported via BR-ATT-025 template (R2, 2026-10-16) |  |
| BR-ATT-022 | Enrolment mapping & quarantine | M (MVP) | P1 | Device user ID mapping still needed for device-export files (R2-01) |  |
| BR-ATT-023 | Mobile GPS/Wi-Fi/selfie check-in | S (P2) | P2 | Mobile is Phase 2 (OD-9) |  |
| BR-ATT-024 | Web check-in | S (MVP) | P2 | Not all sites have devices; they use Excel import (R2-06 confirmed) |  |
| BR-ATT-025 | Excel punch/timesheet import | M (MVP) | P1 | Pilot attendance source; incl. a device-export-file (CSV/Excel) template (R2-01) | B |
| BR-ATT-026 | Duplicate collapse & clock drift | M (MVP) | P2 | Option B cut (R2-19): duplicates removed in the import file / dry-run report; no live device clock (R2, 2026-10-16) |  |
| BR-ATT-027 | Raw punches never edited | M (MVP) | P1 | Inspection evidence |  |
| BR-ATT-030 | Work-date attribution | M (MVP) | P1 | Core T&A |  |
| BR-ATT-031 | Pairing rules & missing in/out | M (MVP) | P1 | Core T&A |  |
| BR-ATT-032 | Daily quantities incl. night hours | M (MVP) | P1 | Core T&A; night premium input |  |
| BR-ATT-033 | Day status & attendance codes | M (MVP) | P1 | Core T&A |  |
| BR-ATT-034 | Late/early tolerance policy | M (MVP) | P2 | Option B cut (R2-19): one fixed late/early rule per legal entity (R2, 2026-10-16) |  |
| BR-ATT-035 | Statutory paid short-time entitlements | M (MVP) | P1 | Statutory: Labour Code |  |
| BR-ATT-036 | Attendance correction requests | M (MVP) | P1 | ESS web; correction requests are a P1 attendance source (R2-01) |  |
| BR-ATT-037 | Admin bulk timesheet edit | M (MVP) | P1 | Kept P1 by R2-01 "manual entry" (overrides Option B): admin edits timesheets with reason |  |
| BR-ATT-038 | Idempotent reprocessing | M (MVP) | P1 | Integrity |  |
| BR-ATT-039 | Not-employed / suspended days | M (MVP) | P1 | Core T&A |  |
| BR-ATT-040 | OT requests with consent | M (MVP) | P1 | Statutory: employee consent |  |
| BR-ATT-041 | OT categories (day/night/rest/holiday) | M (MVP) | P1 | Statutory: OT pay rates |  |
| BR-ATT-042 | Night-OT after day-OT flag | M (MVP) | P1 | Statutory: Decree 145 Art. 57 |  |
| BR-ATT-043 | OT detection policy | M (MVP) | P2 | Option B cut (R2-19): OT by pre-approval only (BR-ATT-040) (R2, 2026-10-16) |  |
| BR-ATT-044 | Payable OT rule | M (MVP) | P1 | Core T&A |  |
| BR-ATT-045 | Daily OT cap | M (MVP) | P1 | Statutory: OT caps |  |
| BR-ATT-046 | Monthly 40h / annual 200/300h caps | M (MVP) | P1 | Statutory: OT caps |  |
| BR-ATT-047 | Cross-entity annual OT cap | S (MVP) | P2 | Per-entity cap in P1; pending legal advice (R2-08/R2-16 confirmed) |  |
| BR-ATT-048 | Protected-employee OT/night block | M (MVP) | P1 | Statutory: Labour Code |  |
| BR-ATT-049 | 300h regime authority notice | S (MVP) | P1 | Promoted: statutory Art. 107(4) |  |
| BR-ATT-050 | TOIL instead of OT pay | S (MVP) | P2 | Optional policy |  |
| BR-ATT-051 | Consecutive OT rest alert | C (P2) | P2 | Module tag P2 |  |
| BR-ATT-055 | Leave type configuration | M (MVP) | P1 | Statutory: leave law |  |
| BR-ATT-056 | Seeded VN leave types | M (MVP) | P1 | Statutory: leave law |  |
| BR-ATT-057 | Annual leave entitlement + seniority | M (MVP) | P1 | Statutory: Art. 113–114 |  |
| BR-ATT-058 | Accrual & pro-rating | M (MVP) | P1 | Statutory: Decree 145 |  |
| BR-ATT-059 | Service time counting | M (MVP) | P1 | Statutory: Decree 145 |  |
| BR-ATT-060 | Leave year & carry-forward | M (MVP) | P1 | Core leave |  |
| BR-ATT-061 | Leave requests (day/half/hour) | M (MVP) | P1 | ESS web (Phase 1) |  |
| BR-ATT-062 | Leave validation & reservation | M (MVP) | P1 | Core leave |  |
| BR-ATT-063 | SI claim data on sick/maternity leave | M (MVP) | P1 | Back to P1, minimal (R2-07): employer files sick/maternity claims to VSS; payroll needs the same data (R2, 2026-10-16) |  |
| BR-ATT-064 | SI sick-day usage tracking | S (MVP) | P2 | Information only; SI claims P2 |  |
| BR-ATT-065 | Maternity leave & early return | M (MVP) | P1 | Statutory: Art. 139 |  |
| BR-ATT-066 | Cancel/shorten leave with retro | M (MVP) | P2 | Option B cut (R2-19): HR corrects the leave via balance adjustment and timesheet re-import (R2, 2026-10-16) |  |
| BR-ATT-067 | Unused leave on termination | M (MVP) | P1 | Statutory: leave encashment |  |
| BR-ATT-068 | Balance ledger & manual adjustment | M (MVP) | P1 | Opening leave balances (D-09) |  |
| BR-ATT-069 | Team leave calendar | S (MVP) | P2 | Convenience |  |
| BR-ATT-070 | Business trips & WFH | M (MVP) | P2 | Option B cut (R2-19): trips and WFH recorded as leave-type / attendance codes (R2, 2026-10-16) |  |
| BR-ATT-071 | Retro deltas for locked periods | M (MVP) | P1 | Payroll retro contract |  |
| BR-ATT-072 | Periods aligned to pay calendar | M (MVP) | P1 | Payroll hand-off |  |
| BR-ATT-073 | Period lifecycle | M (MVP) | P1 | Payroll hand-off |  |
| BR-ATT-074 | Pre-close exception checklist | M (MVP) | P1 | Payroll hand-off quality |  |
| BR-ATT-075 | Close per entity × pay group / early close | M (MVP) | P2 | Option B cut (R2-19): leaver's final attendance entered as payroll variable input (see §11 R2-C4) (R2, 2026-10-16) |  |
| BR-ATT-076 | Versioned Attendance Result | M (MVP) | P1 | Payroll hand-off |  |
| BR-ATT-077 | Employee timesheet confirmation | C (MVP) | P2 | Optional |  |
| BR-ATT-078 | Manager timesheet sign-off | S (MVP) | P2 | Optional |  |
| BR-ATT-079 | Close hold API for Payroll (new, X-03) | M (MVP) | P1 | Payroll/T&A lock integrity (X-03) |  |
| BR-ATT-080 | ESS schedule, timesheet, balances | M (MVP) | P1 | ESS web (Phase 1) |  |
| BR-ATT-081 | MSS who's-in dashboard | M (MVP) | P2 | Convenience; approvals via Core inbox |  |
| BR-ATT-082 | T&A notifications | M (MVP) | P1 | Core T&A |  |
| BR-ATT-083 | Effective-dated policy assignment | M (MVP) | P2 | Option B cut (R2-19): one policy set per legal entity (R2, 2026-10-16) |  |
| BR-ATT-084 | Auto-escalation before close | S (MVP) | P2 | Depends on SLA escalation (P2) |  |
| BR-ATT-085 | Public punch/timesheet APIs | S (P2) | P2 | Public API is P2 |  |
| BR-ATT-086 | Opening leave balances & YTD OT import (was Oracle migration) | M (MVP) | P1 | Changed D-09 (by T&A BA) |  |
| BR-ATT-087 | Re-open of a held period (new, X-03) | M (MVP) | P1 | Payroll/T&A lock integrity (X-03) |  |
| BR-ATT-088 | Lock/unlock on payroll status (new, X-03) | M (MVP) | P1 | Payroll/T&A lock integrity (X-03) |  |
| NFR-ATT-001 | Punch ingestion throughput (500/s platform, X-05) | M | P2 | Punch-rate NFR P2 with the live connector (R2-01; SP-3 postponed) (R2, 2026-10-16) |  |
| NFR-ATT-002 | Punch-to-timesheet latency | M | P1 | P1 part: nightly reprocessing ≤ 30 min; online-source latency moot until the connector (R2-01) |  |
| NFR-ATT-003 | Period close ≤15 min | M | P1 | Payroll timeline |  |
| NFR-ATT-004 | Connector offline buffer ≥7 days | M | P2 | Connector offline buffer P2 (R2-01) (R2, 2026-10-16) |  |
| NFR-ATT-005 | UTC storage, local work dates | M | P1 | Integrity |  |
| NFR-ATT-006 | Deterministic, versioned calculations | M | P1 | Integrity |  |
| NFR-ATT-007 | Retention of T&A records | M | P1 | Statutory retention |  |
| NFR-ATT-008 | Sensitive data (biometrics on device; GPS/selfie P2) | M | P1 | Statutory: PDPL |  |
| NFR-ATT-009 | Mock-location detection | S | P2 | Mobile is Phase 2 |  |
| NFR-ATT-010 | Tenant isolation incl. devices | M | P1 | Security baseline; device endpoints only from P2 (R2-01) |  |
| NFR-ATT-011 | ESS check-in round trip | S | P2 | Web/mobile check-in is Phase 2 |  |

### 9.4 Payroll — [04-payroll.md](modules/04-payroll.md)

| ID | Short title | Old priority | New phase | Reason | Baseline |
|---|---|---|---|---|---|
| BR-PAY-001 | Salary component catalogue | M | P1 | Core payroll | B |
| BR-PAY-002 | Independent PIT/SI/TU flags per component | M | P1 | Statutory: PIT/SI treatment | B |
| BR-PAY-003 | Partly exempt allowance caps | M | P1 | Statutory: PIT exemptions |  |
| BR-PAY-004 | Effective-dated components | M | P1 | Integrity of locked runs |  |
| BR-PAY-005 | Formula engine | M | P1 | Core payroll (pilot pay rules, SP-4) |  |
| BR-PAY-006 | Formula IDE (highlighting, test mode, diff) | M | P2 | Convenience; P1 = syntax validation in BR-PAY-005 |  |
| BR-PAY-007 | Salary structures | M | P1 | Core payroll |  |
| BR-PAY-008 | Pay groups (monthly) | M | P1 | Core payroll |  |
| BR-PAY-009 | Standard working-day methods | M | P1 | Core payroll; proration |  |
| BR-PAY-010 | Effective-dated legal parameters | M | P1 | Statutory: all rates/caps/brackets | B |
| BR-PAY-011 | VN starter templates | S | P2 | Should; accelerator |  |
| BR-PAY-012 | Rounding rules | M | P1 | Core payroll |  |
| BR-PAY-013 | Multi-currency contracts | S | P2 | Pilot has no USD contracts (R2-09 confirmed) |  |
| BR-PAY-020 | GROSS and NET (gross-up) | M | P1 | Core VN payroll |  |
| BR-PAY-021 | Net-to-gross scope | M | P2 | Option B cut (R2-19): fixed net scope (all recurring components net; bonus/OT gross) (R2, 2026-10-16) |  |
| BR-PAY-022 | Proration joiners/leavers | M | P1 | Core payroll |  |
| BR-PAY-023 | Unpaid/absence reduction | M | P1 | Core payroll |  |
| BR-PAY-024 | Mid-period change segments | M | P1 | Core payroll; mid-period legal change |  |
| BR-PAY-025 | OT pay ≥ legal multipliers | M | P1 | Statutory: OT pay |  |
| BR-PAY-026 | OT rate base & PIT-exempt OT portion | M | P1 | Statutory: PIT exemption |  |
| BR-PAY-027 | Regional minimum-wage check | M | P1 | Statutory: minimum wage |  |
| BR-PAY-028 | Allowance & variable components | M | P1 | Core payroll |  |
| BR-PAY-029 | Variable input import (API P2) | M | P1 | Core payroll |  |
| BR-PAY-030 | SI-paid sick/maternity days | M | P1 | Statutory: SI |  |
| BR-PAY-031 | Paid leave/holiday pay | M | P1 | Statutory: Labour Code |  |
| BR-PAY-040 | SI/HI/UI/OA-OD contributions | M | P1 | Statutory: SI Law 2024 |  |
| BR-PAY-041 | SI salary basis & caps | M | P1 | Statutory: SI Law 2024 |  |
| BR-PAY-042 | Declared SI basis per month | M | P1 | Statutory: VSS rules |  |
| BR-PAY-043 | 14-day no-contribution rule | M | P1 | Statutory: SI Law 2024 |  |
| BR-PAY-044 | SI eligibility & exemptions | M | P1 | Statutory: SI Law 2024 |  |
| BR-PAY-045 | OA-OD rate per entity | M | P1 | Statutory |  |
| BR-PAY-046 | Trade-union fee & dues | M | P1 | Statutory: Trade Union Law |  |
| BR-PAY-047 | D02-LT / TK1-TS data, C12-TS reconciliation | M | P1 | VSS declaration file for upload via the customer's I-VAN (R2-01) |  |
| BR-PAY-048 | Retro SI adjustments with interest | S | P2 | Should; manual adjustment in pilot |  |
| BR-PAY-049 | SI arrears tracking | S | P2 | Should |  |
| BR-PAY-050 | Resident PIT progressive | M | P1 | Statutory: PIT |  |
| BR-PAY-051 | Parameter-driven PIT schedule (7→5 brackets) | M | P1 | Statutory: PIT Law 2025 |  |
| BR-PAY-052 | Monthly cumulative PIT across runs | M | P1 | Statutory: PIT |  |
| BR-PAY-053 | Non-resident 20% | M | P1 | Statutory: PIT |  |
| BR-PAY-054 | Casual income 10% withholding | M | P1 | Statutory: PIT |  |
| BR-PAY-055 | 08/CK-TNCN commitments | M | P1 | Statutory: PIT |  |
| BR-PAY-056 | 05/KK-TNCN declaration XML | M | P1 | Statutory: tax filing |  |
| BR-PAY-057 | PIT year-end finalisation | M | P1 | Statutory: 05/QTT-TNCN (uses imported YTD, D-09) |  |
| BR-PAY-058 | Finalisation eligibility & authorisation | M | P1 | Statutory: PIT finalisation |  |
| BR-PAY-059 | E-PIT withholding certificates | M | P1 | Statutory; needs employer digital signature (see §11 R2-C3) |  |
| BR-PAY-060 | Dependant deduction months | M | P1 | Statutory: PIT |  |
| BR-PAY-061 | Tax code validation | M | P1 | Statutory: PIT |  |
| BR-PAY-062 | Employer-paid PIT & benefits in kind | M | P1 | Statutory: PIT |  |
| BR-PAY-063 | Prior-employer income within group | S | P2 | Should (pre-go-live YTD comes from opening balances) |  |
| BR-PAY-064 | Retro PIT re-computation option | S | P2 | Should; default tax in month paid |  |
| BR-PAY-065 | 183-day residency check report | new (R2-02, 2026-10-16) | P1 | Statutory: PIT residency test (feeds BR-PPL-023, BR-PAY-053) |  |
| BR-PAY-070 | Run types incl. 13th month/Tết, final settlement | M | P1 | Bonus / off-cycle run with correct PIT stays P1; Tết/13th-month eligibility and proration rules P2 (R2-14) |  |
| BR-PAY-071 | Run lifecycle & lock | M | P1 | Core payroll control |  |
| BR-PAY-072 | Input snapshot & reproducibility | M | P1 | Integrity |  |
| BR-PAY-073 | Subset / single / simulation runs | M | P2 | Option B cut (R2-19): full-population runs only; a calculated, not-yet-submitted run serves as preview (R2, 2026-10-16) |  |
| BR-PAY-074 | Pre-submission exception report | M | P1 | Core payroll control |  |
| BR-PAY-075 | Approval via Core workflow | M | P1 | Core payroll control |  |
| BR-PAY-076 | Retro pay | M | P1 | Core payroll; effective-dated changes |  |
| BR-PAY-077 | Final settlement run | M | P1 | Statutory settlement P1; 13th-month pro-rata by policy P2 (R2-14) |  |
| BR-PAY-078 | Severance basis (6-month average) | M | P1 | Statutory: severance |  |
| BR-PAY-079 | Reversal run | S | P2 | Should |  |
| BR-PAY-080 | Period-close blocking rules | S | P2 | Should |  |
| BR-PAY-081 | Multi legal entity payroll | M | P1 | Each legal entity withholds PIT on its own payments (R2-16) |  |
| BR-PAY-082 | Close hold on submit (new, X-03) | M | P1 | Payroll/T&A lock integrity (X-03) |  |
| BR-PAY-083 | Hold release (new, X-03) | M | P1 | Payroll/T&A lock integrity (X-03) |  |
| BR-PAY-084 | Approval gate & lock confirmation (new, X-03) | M | P1 | Payroll/T&A lock integrity (X-03) |  |
| BR-PAY-085 | Pay group assignment (employee → pay group) | new (R2-02, 2026-10-16) | P1 | Chain: every payroll run needs the pay group link |  |
| BR-PAY-086 | People pay-impacting events | new (R2-02, 2026-10-16) | P1 | Chain, P1 only for events whose producer is P1: contract suspension/resume (BR-PPL-056) and temporary transfer (BR-PPL-035). P2 with their producers: RewardApproved (BR-PPL-085), SecondmentChanged (BR-PPL-034), AppraisalFinalised (BR-PPL-093), AdvanceDeductionRequested (BR-PPL-111), training-bond repayment (BR-PPL-101) |  |
| BR-PAY-087 | Legal-entity lock state for Core | new (R2-02, 2026-10-16) | P1 | Chain: protects locked payroll from back-dated legal-entity/org changes (Core C-16) |  |
| BR-PAY-090 | Salary advances | M | P2 | Option B cut (R2-19): advances paid via the Advance run type / entered as variable input (R2, 2026-10-16) |  |
| BR-PAY-091 | Employee loans (interest-free instalments; interest P2) | S | P1 | Promoted: opening loan balances (D-09) |  |
| BR-PAY-092 | 30% deduction cap for damages | M | P1 | Statutory: Art. 102 |  |
| BR-PAY-093 | Deduction priority & carry-forward | M | P1 | Core payroll |  |
| BR-PAY-094 | Expense reimbursement via payroll | S | P2 | Expenses are Phase 2 |  |
| BR-PAY-100 | Payslip template | M | P1 | Statutory: payslip content (Art. 95) |  |
| BR-PAY-101 | Payslips on ESS web (mobile app P2) | M | P1 | ESS web payslip (R2-17) |  |
| BR-PAY-102 | Password-protected e-mail payslip | M | P1 | Kept P1 by R2-17 (overrides Option B): password-protected PDF by e-mail |  |
| BR-PAY-103 | Payslip query from ESS | C | P3 | Could |  |
| BR-PAY-104 | ESS PIT certificates, 08/CK, authorisation | M | P1 | Statutory: PIT finalisation |  |
| BR-PAY-110 | Bank payment files | M | P1 | Pay the pilot's employees |  |
| BR-PAY-111 | Split/cash payments | S | P2 | Should |  |
| BR-PAY-112 | Payment file control & status | M | P2 | Option B cut (R2-19): approval gate BR-PAY-084 still applies; no file-hash/status import (R2, 2026-10-16) |  |
| BR-PAY-113 | GL journal export | M | P1 | Finance hand-off |  |
| BR-PAY-114 | Cost allocation (default; time-based P2) | M | P2 | Option B cut (R2-19): cost by default cost centre in the GL export (BR-PAY-113) (R2, 2026-10-16) |  |
| BR-PAY-115 | Bonus/leave accruals | C | P3 | Could |  |
| BR-PAY-120 | Payroll data scope & confidential groups | M | P1 | Confidentiality |  |
| BR-PAY-121 | Payroll audit | M | P1 | Audit evidence |  |
| BR-PAY-122 | Opening payroll balances import (was Oracle history migration) | M | P1 | Changed D-09 (by Payroll BA) |  |
| BR-PAY-123 | Retention of payroll records | M | P1 | Statutory: Accounting Law |  |
| BR-PAY-124 | Salary cost estimate API | S | P2 | Consumer (Recruitment) is Phase 2 ; always-on read API (R2-C9, 2026-10-16) | API |
| BR-PAY-125 | Gross↔net calculator | C | P3 | Could |  |
| NFR-PAY-001 | 10,000 employees ≤15 min | M | P1 | Performance baseline (SP-1) |  |
| NFR-PAY-002 | Deterministic fixed-point arithmetic | M | P1 | Integrity |  |
| NFR-PAY-003 | Reproducible ≥10 years | M | P1 | Statutory evidence |  |
| NFR-PAY-004 | Confidentiality of pay data | M | P1 | Security / PDPL |  |
| NFR-PAY-005 | Tenant isolation | M | P1 | Security baseline |  |
| NFR-PAY-006 | Immutable payroll audit | M | P1 | Audit evidence |  |
| NFR-PAY-007 | Legal agility by configuration | M | P1 | Statutory change readiness | B |
| NFR-PAY-008 | Golden test pack ≥200 scenarios | M | P1 | Go-live gate (statutory accuracy) |  |
| NFR-PAY-009 | Payroll availability 99.5% | M | P1 | Availability |  |
| NFR-PAY-010 | File compatibility HTKK/eTax/VSS/banks | M | P1 | Statutory filing |  |
| NFR-PAY-011 | VN/EN, VND formatting | M | P1 | Localisation |  |
| NFR-PAY-012 | 500-employee run <30 min hands-on | S | P2 | Should |  |
| NFR-PAY-013 | 50 entities / 10k per entity | S | P2 | Beyond pilot scale |  |

### 9.5 Recruitment — [05-recruitment.md](modules/05-recruitment.md)

The whole module is Phase 2 (charter §6). `C` items go to P3. The `CandidateHired` hand-off contract stays frozen from Phase 0.

| ID | Short title | Old priority | New phase | Reason | Baseline |
|---|---|---|---|---|---|
| BR-REC-001 | Staffing plans | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-002 | Plan pre-fill from People | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-003 | Budgeted cost per plan line | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-004 | Plan approval & versions | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-005 | Plan vs actual roll-up | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-006 | Flag retired org/positions | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-007 | Requisition creation | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-008 | Requisition vs plan classification | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-009 | Requisition approval | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-010 | Requisition salary within band | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-011 | Requisition data scope | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-012 | Replacement requisition on cancelled separation | C | P3 | Could |  |
| BR-REC-013 | Job openings from requisition | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-014 | Branded career site | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-015 | Internal job board | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-016 | Job-board publishing | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-017 | Job-board application import | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-018 | Discriminatory-content warning | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-019 | Auto-unpublish on close | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-020 | One candidate profile, many applications | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-021 | Candidate profile data | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-022 | CV parsing | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-023 | Candidate duplicate detection | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-024 | Talent pools | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-025 | Candidate search | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-026 | No automated rejection | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-027 | Pipeline templates | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-028 | Screening questions | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-029 | Reject/withdraw reasons, bulk actions | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-030 | Candidate communication templates | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-031 | Stage timestamps | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-032 | Interviewer visibility | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-033 | Interview scheduling | M/S | P2 | Recruitment is Phase 2 |  |
| BR-REC-034 | Candidate self-scheduling | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-035 | Interview reminders | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-036 | Scorecards | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-037 | Scorecard chasing | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-038 | Assessments | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-039 | Interviewers from People | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-040 | Offer creation | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-041 | Offer band check | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-042 | NET offer gross-up simulation | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-043 | Offer legal checks | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-044 | Offer approval | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-045 | Offer letter & acceptance | M/S | P2 | Recruitment is Phase 2 |  |
| BR-REC-046 | Offer versions & responses | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-047 | Vacancy reservation on accept | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-048 | Pre-boarding checklist | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-049 | Pre-boarding document verification | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-050 | CandidateHired hand-off | M | P2 | Recruitment is Phase 2 (contract frozen in Phase 0) |  |
| BR-REC-051 | Double-hire block | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-052 | HireCancelled / StartDateChanged handling | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-053 | Internal transfer hand-off | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-054 | Work-permit flag on hire | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-055 | Employee referrals | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-056 | Referral programmes | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-057 | Referral bonus to payroll | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-058 | Recruitment cost tracking | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-059 | Candidate consent | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-060 | Candidate data rights | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-061 | Candidate retention periods | M | P2 | Recruitment is Phase 2 |  |
| BR-REC-062 | Candidate legal hold | S | P2 | Recruitment is Phase 2 |  |
| BR-REC-063 | CV/ID access logging | M | P2 | Recruitment is Phase 2 |  |
| NFR-REC-001 | rec tenant isolation | M | P2 | Recruitment is Phase 2 |  |
| NFR-REC-002 | Career site performance | M | P2 | Recruitment is Phase 2 |  |
| NFR-REC-003 | Bot protection & upload scan | M | P2 | Recruitment is Phase 2 |  |
| NFR-REC-004 | Candidate document encryption | M | P2 | Recruitment is Phase 2 |  |
| NFR-REC-005 | Hand-off outbox | M | P2 | Recruitment is Phase 2 |  |
| NFR-REC-006 | CV parsing accuracy | S | P2 | Recruitment is Phase 2 |  |
| NFR-REC-007 | Integration graceful failure | S | P2 | Recruitment is Phase 2 |  |
| NFR-REC-008 | Candidate search performance | S | P2 | Recruitment is Phase 2 |  |
| NFR-REC-009 | Candidate retention job | M | P2 | Recruitment is Phase 2 |  |
| NFR-REC-010 | Recruitment VI/EN | M | P2 | Recruitment is Phase 2 |  |
| NFR-REC-011 | Candidate portal sessions | M | P2 | Recruitment is Phase 2 |  |

### 9.6 SaaS Platform — [06-saas-platform.md](modules/06-saas-platform.md)

| ID | Short title | Old priority | New phase | Reason | Baseline |
|---|---|---|---|---|---|
| BR-SAAS-001 | Tenant record & lifecycle states | M | P1 | Core SaaS |  |
| BR-SAAS-002 | Legal entities per tenant | M | P1 | Foundation: multi-entity |  |
| BR-SAAS-003 | Operator provisioning ≤10 min | M | P1 | Assisted onboarding (pilot path) |  |
| BR-SAAS-004 | Self-service trial sign-up | S | P2 | Self-service is Phase 2 |  |
| BR-SAAS-005 | Trial configuration & expiry | S | P2 | Self-service is Phase 2 |  |
| BR-SAAS-006 | Versioned plans/editions | M | P2 | Cut: P1 = per-tenant module subscription (BR-SAAS-007) |  |
| BR-SAAS-007 | Module subscription per tenant | M | P1 | Core SaaS |  |
| BR-SAAS-008 | Entitlement service | M | P1 | Core SaaS |  |
| BR-SAAS-009 | Feature flags & kill-switch | M | P2 | Option B cut (R2-19): toggles by configuration/deploy (R2, 2026-10-16) |  |
| BR-SAAS-010 | Price books, tiers, coupons | M | P2 | Cut: P1 = contract price per module |  |
| BR-SAAS-011 | Prorated plan changes | M | P2 | Billing automation is Phase 2 |  |
| BR-SAAS-012 | Billable employee = active person at month end per legal entity | M | P1 | Changed D-10 |  |
| BR-SAAS-013 | Usage metering (billable count; other metrics P2) | M | P2 | Option B cut (R2-19): manual month-end headcount report per legal entity for billing (R2, 2026-10-16) |  |
| BR-SAAS-014 | Subscription invoices + VN e-invoice | M | P1 | Statutory (vendor's own e-invoices); provider link is a live integration, see §11 R2-C1 |  |
| BR-SAAS-015 | Adjustment/replacement e-invoices | M | P1 | Statutory; see §11 R2-C1 |  |
| BR-SAAS-016 | Payment gateway & auto reconciliation | S | P2 | Should |  |
| BR-SAAS-017 | Automated dunning & suspension | M | P2 | Manual collections for pilot |  |
| BR-SAAS-018 | Offboarding full export ≤72h | M | P2 | Needed before first tenant exit, not pilot go-live |  |
| BR-SAAS-019 | Post-retention deletion & certificate | M | P2 | Needed before first tenant exit |  |
| BR-SAAS-020 | Ownership transfer | S | P2 | Should |  |
| BR-SAAS-021 | tenant_id + RLS + app filter | M | P1 | Security baseline |  |
| BR-SAAS-022 | Dedicated tier | S | P2 | Should |  |
| BR-SAAS-023 | Release-blocking isolation tests | M | P1 | Security baseline |  |
| BR-SAAS-024 | VN data residency | M | P1 | Statutory: Cybersecurity Law / PDPL |  |
| BR-SAAS-025 | Tenant settings (locale, TZ, currency) | M | P1 | Core SaaS |  |
| BR-SAAS-026 | Full VI/EN | M | P1 | Localisation |  |
| BR-SAAS-027 | Custom fields | M | Removed | Removed — owned by core: BR-CORE-086 custom-field framework (P2) (R2-02, 2026-10-16) |  |
| BR-SAAS-028 | Form & template customisation | S | P2 | Should |  |
| BR-SAAS-029 | White-labelling | C | P2 | White-label is Phase 2 (charter) |  |
| BR-SAAS-030 | Custom domain | C | P2 | White-label is Phase 2 (charter) |  |
| BR-SAAS-031 | Sandbox tenant | S | P2 | No migration rehearsal needed (D-09); validate-only import instead |  |
| BR-SAAS-032 | Operator back-office | M | P1 | Core SaaS operations |  |
| BR-SAAS-033 | Consent-based support grants | M | P1 | Security / PDPL |  |
| BR-SAAS-034 | Masking in support sessions | M | P1 | Security / PDPL |  |
| BR-SAAS-035 | Impersonation (view as user) | M | P2 | Cut: support works inside grant without impersonation |  |
| BR-SAAS-036 | Break-glass two-person | M | P2 | Option B cut (R2-19): manual two-person break-glass procedure; access still logged (BR-CORE-041) (R2, 2026-10-16) |  |
| BR-SAAS-037 | Tenant-visible operator access log | M | P2 | Option B cut (R2-19): operator-access log exported for the tenant on request (R2, 2026-10-16) |  |
| BR-SAAS-038 | Password policy | M | P1 | Security baseline  (R2-02: baseline, 2026-10-16) | B |
| BR-SAAS-039 | MFA | M | P1 | Security baseline  (R2-02: baseline, 2026-10-16) | B |
| BR-SAAS-040 | Google/Entra OIDC SSO | M | P2 | Not needed at pilot (R2-04 confirmed) |  |
| BR-SAAS-041 | Generic SAML/OIDC SSO | S | P2 | Should |  |
| BR-SAAS-042 | SCIM provisioning | C | P3 | Could |  |
| BR-SAAS-043 | Session management (web; mobile P2) | M | P1 | Security baseline  (R2-02: baseline, 2026-10-16) | B |
| BR-SAAS-044 | Multi-tenant user accounts | S | P2 | Should |  |
| BR-SAAS-045 | ESS login & OTP activation | M | P1 | ESS web; activation OTP by e-mail only in P1 (SMS P2, R2-01) |  |
| BR-SAAS-046 | Reporting framework | M | P1 | Standard reports & export  (R2-02: baseline, 2026-10-16) | B |
| BR-SAAS-047 | Separate reporting store | M | P2 | Pilot load low; per-tenant quotas protect OLTP |  |
| BR-SAAS-048 | Scheduled reports | S | P2 | Should |  |
| BR-SAAS-049 | Report designer | S | P2 | Report designer is Phase 2 |  |
| BR-SAAS-050 | Dashboards | S | P2 | Phase 2 |  |
| BR-SAAS-051 | HR analytics | S | P2 | BI is Phase 2 |  |
| BR-SAAS-052 | Statutory report framework | M | P1 | Statutory forms (SI/PIT/labour) |  |
| BR-SAAS-053 | Async audited exports | M | P2 | Option B cut (R2-19): exports run synchronously; audit via BR-CORE-090/093 (R2, 2026-10-16) |  |
| BR-SAAS-054 | BI data extract | C | P3 | Could |  |
| BR-SAAS-055 | Public REST API | S | P2 | Public API is Phase 2 |  |
| BR-SAAS-056 | API keys / OAuth clients | S | P2 | Public API is Phase 2 |  |
| BR-SAAS-057 | API rate limits | S | P2 | Public API is Phase 2 |  |
| BR-SAAS-058 | Webhooks | S | P2 | Phase 2 |  |
| BR-SAAS-059 | Accounting journal file export | M | P1 | File export only (Excel/CSV); accounting API push P2 (R2-01) |  |
| BR-SAAS-060 | Bank payment files (H2H P2) | M | P1 | File export only; host-to-host P2 (R2-01) |  |
| BR-SAAS-061 | VSS I-VAN API submission | S | P2 | P1 produces files (BR-PAY-047) |  |
| BR-SAAS-062 | eTax XML & e-PIT certificates | M | P1 | eTax XML file export; signing of e-certificates see §11 R2-C3 (R2-01) |  |
| BR-SAAS-063 | E-signature / e-labour contract | S | P2 | E-sign and e-labour contract are live integrations (R2-01, R2-10 confirmed) |  |
| BR-SAAS-064 | VNeID | C | P3 | Could; VNeID is a live integration (R2-01), stays P3 |  |
| BR-SAAS-065 | Time-device ingestion (1 certified connector) | M | P2 | Live time-device ingestion P2 (R2-01); P1 punch source = device export file + Excel import (BR-ATT-025) (R2, 2026-10-16) |  |
| BR-SAAS-066 | Messaging: e-mail only (SMS/Zalo/push P2) | M | P1 | Core notifications: e-mail only; SMS/Zalo/push P2 (R2-01) |  |
| BR-SAAS-067 | Job boards | C | P3 | Could; Recruitment is Phase 2 |  |
| BR-SAAS-068 | Integration hub | M | P2 | P1 integrations are file-based + device connector |  |
| BR-SAAS-069 | Opening-balance & master import templates | M | P1 | Changed D-09 |  |
| BR-SAAS-070 | Validate-only, idempotent, reversible imports | M | P1 | Needed for opening balances (D-09) |  |
| BR-SAAS-071 | Opening-balance import for mid-year go-live (was Oracle migration) | M | P1 | Changed D-09 |  |
| BR-SAAS-072 | Opening-balance reconciliation vs customer control totals | M | P1 | Changed D-09 |  |
| BR-SAAS-073 | Optional payroll comparison vs customer register (was parallel run) | M | P1 | One-month payroll comparison vs customer register moved to P1 (R2-03) (R2, 2026-10-16) |  |
| BR-SAAS-074 | Onboarding wizard | S | P2 | Should |  |
| BR-SAAS-075 | Responsive ESS web | M | P1 | ESS web (Phase 1) |  |
| BR-SAAS-076 | Native mobile app incl. GPS/selfie check-in | S | P2 | Mobile is Phase 2 (X-04, OD-9) |  |
| BR-SAAS-077 | Mobile payslip protection | S | P2 | Mobile is Phase 2 |  |
| BR-SAAS-078 | Offline mobile check-in | S | P2 | Mobile is Phase 2 |  |
| BR-SAAS-079 | Personal data inventory | M | P1 | Statutory: PDPL |  |
| BR-SAAS-080 | Consent management | M | P1 | Statutory: PDPL |  |
| BR-SAAS-081 | DSR tooling | M | P1 | Statutory: PDPL |  |
| BR-SAAS-082 | Configurable retention & purge jobs | M | P2 | No retention expiry in pilot year; floors documented |  |
| BR-SAAS-083 | DPIA dossier | M | P1 | Statutory: PDPL |  |
| BR-SAAS-084 | Breach notification 72h | M | P1 | Statutory: PDPL |  |
| BR-SAAS-085 | Tamper-evident security audit | M | P1 | Security baseline |  |
| BR-SAAS-086 | ISO 27001 / SOC 2 ISMS | S | P2 | Should |  |
| BR-SAAS-087 | Information-system security level classification | S | P1 | Promoted: statutory (Decree 85), non-build |  |
| BR-SAAS-088 | Go-live cut-over date & opening-balance lock (new) | — (new) | P1 | Added D-09 |  |
| BR-SAAS-089 | Pre-go-live monthly income/PIT for finalisation (new) | — (new) | P1 | Added D-09 |  |
| BR-SAAS-090 | Tenant-aware job scheduler / queue | new (R2-02, 2026-10-16) | P1 | Foundation: effective-dated activation, imports, payroll runs, payslip publishing |  |
| BR-SAAS-091 | Baseline capabilities always on (entitlements) | new (R2-02, 2026-10-16) | P1 | R2-18 baseline rule in the entitlement service | B |
| NFR-SAAS-001 | Availability 99.5% | M | P1 | Availability |  |
| NFR-SAAS-002 | Payroll performance | M | P1 | Performance baseline |  |
| NFR-SAAS-003 | Interactive performance | M | P1 | Performance baseline |  |
| NFR-SAAS-004 | Punch burst (device) | M | P2 | Punch burst P2 with the live connector (R2-01) (R2, 2026-10-16) |  |
| NFR-SAAS-005 | Scalability & noisy-tenant cap | M | P1 | Shared-tier baseline |  |
| NFR-SAAS-006 | Backup & DR in VN | M | P1 | Resilience baseline |  |
| NFR-SAAS-007 | Encryption | M | P1 | Security baseline |  |
| NFR-SAAS-008 | Secrets in vault | M | P1 | Security baseline (Oracle mention removed, D-09) |  |
| NFR-SAAS-009 | ASVS L2, scanning, pen test | M | P1 | Security baseline |  |
| NFR-SAAS-010 | Observability & log retention | M | P1 | Statutory log retention |  |
| NFR-SAAS-011 | WCAG 2.1 AA | S | P2 | Should |  |
| NFR-SAAS-012 | Browser support, 360 px (app OS P2) | M | P1 | ESS responsive web |  |
| NFR-SAAS-013 | Full localisation | M | P1 | Localisation |  |
| NFR-SAAS-014 | Zero-downtime releases | M | P1 | Payroll-window safety |  |
| NFR-SAAS-015 | Release governance & toggles | M | P1 | Calculation safety |  |
| NFR-SAAS-016 | Regulatory change SLA | M | P1 | Statutory change readiness |  |
| NFR-SAAS-017 | Calculation traceability | M | P1 | Statutory evidence |  |
| NFR-SAAS-018 | Public API quality | S | P2 | Public API is Phase 2 |  |
| NFR-SAAS-019 | Full tenant export ≤6 h | M | P2 | Tied to offboarding (P2) |  |
| NFR-SAAS-020 | Support SLA | M | P1 | Pilot support |  |
| NFR-SAAS-021 | Peak capacity (payroll close, PIT season) | M | P1 | PIT finalisation peak |  |
| NFR-SAAS-022 | Mobile app performance | S | P2 | Mobile is Phase 2 |  |


## 10. Reproducing the counts

Python is not available, so extraction and counting use `awk` (Git Bash). The rating data is the set of §9 tables in this file.

```bash
cd docs/01-requirements/brd
# 1. extract every BR/NFR row of the module BRDs: ID and current priority
awk -F'|' '/^\| *\**(BR|NFR)-[A-Z]+-[0-9]+/ { id=$2; gsub(/[ *]/,"",id); p=$4; gsub(/^ +| +$/,"",p); print id"\t"p }' \
  modules/0[1-6]-*.md > /tmp/reqs.tsv
# 2. pull the ratings (ID, old priority, new phase) out of §9 of this file
awk -F'|' '/^## 9\./{s=1} /^## 10\./{s=0} s && /^\| (BR|NFR)-/ { id=$2; o=$4; n=$5; gsub(/ /,"",id); gsub(/^ +| +$/,"",o); gsub(/ /,"",n); o=(o ~ /new/)?"new":substr(o,1,1); print id"\t"o"\t"n }' \
  mvp-scope.md > /tmp/rated.tsv
# 3. coverage: IDs in the BRDs without a rating, and ratings without a BRD row (expect none)
comm -3 <(cut -f1 /tmp/reqs.tsv | sort) <(cut -f1 /tmp/rated.tsv | sort)
# 4. counts per module and type: old priority and new phase ("new" = requirement added on 2026-10-16)
#    ("Removed" rows keep their ID in the module BRDs, so step 3 still covers them; step 4 excludes them from every count)
awk -F'\t' '{split($1,x,"-"); k=x[2]" "x[1]; if($3=="Removed"){r[k]++; t[k]+=0; next} t[k]++; o[k" "$2]++; n[k" "$3]++}
  END{for(k in t) printf "%s total=%d oldM=%d S=%d C=%d new=%d | P1=%d P2=%d P3=%d removed=%d\n",
      k,t[k],o[k" M"],o[k" S"],o[k" C"],o[k" new"],n[k" P1"],n[k" P2"],n[k" P3"],r[k]}' /tmp/rated.tsv | sort
```

Round 2 additions: the phase mismatch check (§12) and the baseline count (§3.4).

```bash
# 5. current phase tag per row in the module BRDs (T&A BR rows: Phase column; others: MoSCoW, overridden by a "→ P1/P2" or "Phase 2" note)
awk -F'|' '/^\| *\**(BR|NFR)-[A-Z]+-[0-9]+/ { f=FILENAME; sub(/.*\//,"",f); id=$2; gsub(/[ *]/,"",id); p=$4; gsub(/^ +| +$/,"",p); ph=""; nt="";
  if (f ~ /^03-/ && id ~ /^BR/) {ph=$5; gsub(/^ +| +$/,"",ph)} else {nt=$5; gsub(/^ +| +$/,"",nt)}
  if (ph!="") { tag=ph; imp=(ph ~ /^MVP/)?"P1":(ph ~ /^P2/?"P2":(ph ~ /^P3/?"P3":"?")) }
  else { l=substr(p,1,1); imp=(l=="M")?"P1":(l=="S"?"P2":"P3"); tag=p;
    if (nt ~ /→ *P2/) imp="P2"; else if (nt ~ /→ *P1/) imp="P1"; else if (nt ~ /^Phase 2/) imp="P2" }
  if ($3 ~ /Removed —/) imp="Removed"
  print f"\t"id"\t"tag"\t"imp }' modules/0[1-6]-*.md > /tmp/cur.tsv
# 6. mismatch check: rows whose module tag differs from §9. Only the T&A BR Phase column must match (expect 0 for 03-time-attendance BR rows); elsewhere MoSCoW is business priority (banner rule, §12)
join -t $'\t' -1 2 -2 1 <(sort -t $'\t' -k2,2 /tmp/cur.tsv) <(sort -k1,1 /tmp/rated.tsv) \
  | awk -F'\t' '$2 !~ /^05-/ && $4!=$6' | sort -t $'\t' -k2,2 -k1,1V
# 7. baseline rows per module (column 7 of the §9 tables)
awk -F'|' '/^## 9\./{s=1} /^## 10\./{s=0} s && /^\| (BR|NFR)-/ && $7 ~ /B/ {split($2,x,"-"); c[x[2]" "x[1]]++} END{for(k in c) print k, c[k]}' mvp-scope.md | sort
```

Checks run on 2026-10-16 after the R2-02 final scope sync: 583 rows in the module BRDs, 583 ratings (494 active BR + 84 NFR + 5 removed), no ID missing on either side (step 3 output empty), no duplicates. Steps 1–4 and 7 give the figures in §3 and §3.4. Step 6 gives 0 mismatches for the T&A BR Phase column.

**Note on the "Old priority" column.** It shows the MoSCoW value before the R1 re-rating. Re-running step 1 on the current files gives two differences: BR-SAAS-073 is now `S`, and BR-SAAS-088/089 now exist as `M`. That is why [BRD.md](BRD.md) §9 shows SaaS as M = 57 and S = 26, while §3 here shows the pre-re-rating values (M = 56, S = 25, 2 new).

## 11. Round 2 changes (2026-10-16)

Source: [decision-log.md](../../00-project-management/decision-log.md) §7, §7.1 and the PM interpretations in §7.2. Every row changed in §9 carries "(R2, 2026-10-16)" in its reason. Rows whose phase is unchanged but whose reason was updated to record a round 2 decision cite the decision ID, without the date tag.

### 11.1 Phase changes (34 rows: 31 BR, 3 NFR)

| Decision | IDs | From → to |
|---|---|---|
| R2-19 Option B | Core: BR-CORE-051, 053, 058, 071, 083 · People: BR-PPL-005, 018, 019, 022, 133 · T&A: BR-ATT-026, 034, 043, 066, 070, 075, 083 · Payroll: BR-PAY-021, 073, 090, 112, 114 · SaaS: BR-SAAS-009, 013, 036, 037, 053 | P1 → P2 (27 BR) |
| R2-19 exceptions | BR-PAY-102 (kept by R2-17), BR-ATT-037 (kept by R2-01 "manual entry") | stay P1 |
| R2-01 / §7.2 live integrations | BR-ATT-021 (device connector; its file-import part moves to BR-ATT-025), BR-SAAS-065 (time-device ingestion), NFR-ATT-001 (punch rate), NFR-ATT-004 (offline buffer), NFR-SAAS-004 (punch burst) | P1 → P2 (2 BR, 3 NFR) |
| R2-07 | BR-ATT-063 SI benefit-claim data, minimal: certificate type/number, facility, dates, child data, attachment | P2 → P1 |
| R2-03 | BR-SAAS-073 one-month payroll comparison against the customer's register | P2 → P1 |

### 11.2 Integrations: what Phase 1 keeps and what moves (R2-01, §7.2)

| Stays P1 (file or in-product) | IDs | Moves to P2 (live integration) | IDs |
|---|---|---|---|
| E-mail (login, MFA OTP, ESS activation, OD-8 former-employee OTP, notifications) and in-app notifications | BR-CORE-038, 070, BR-SAAS-039, 045, 066 | Zalo OA/ZNS, SMS, push | BR-CORE-070 (S/C parts), BR-SAAS-066 (non-e-mail parts), BR-CORE-059 |
| Bank payment file | BR-PAY-110, BR-SAAS-060 | Host-to-host / open banking | BR-SAAS-060 (API part) |
| VSS declaration file (D02-LT, TK1-TS), uploaded by the customer through its I-VAN | BR-PAY-047 | VSS I-VAN API submission | BR-SAAS-061 |
| PIT XML for eTax/HTKK (05/KK, 05/QTT); e-certificate XML/PDF | BR-PAY-056, 057, 059, BR-SAAS-062 | Digital signature / CA, VNeID | BR-CORE-103, BR-PPL-134, BR-SAAS-063, BR-PPL-012, BR-SAAS-064 (P3) |
| GL journal in Excel/CSV | BR-PAY-113, BR-SAAS-059 | Accounting API push (MISA, Fast, SAP) | BR-SAAS-059 (API part) |
| Excel import of punches and timesheets, incl. a **device-export-file (CSV/Excel) template** | BR-ATT-025, 022 (device user ID mapping), 020 | Time-device connector, offline buffer, punch-rate NFRs | BR-ATT-021, BR-SAAS-065, NFR-ATT-001, 004, NFR-SAAS-004 |
| Manual entry and correction requests | BR-ATT-036, 037 | Job boards, public API, webhooks, integration hub runtime | BR-REC-016/017, BR-SAAS-067 (P3), BR-SAAS-055–058, BR-ATT-085, BR-SAAS-068 |

### 11.3 Decisions confirmed without a phase change

| Decision | IDs | Phase |
|---|---|---|
| R2-04 SSO | BR-CORE-039, BR-SAAS-040, 041 (SCIM BR-SAAS-042: P3) | P2 |
| R2-05 Discipline | BR-PPL-080, 081 (082–084 already P2) | P2 |
| R2-06 Web check-in | BR-ATT-024 | P2 |
| R2-08 / R2-16 Cross-entity OT cap | BR-ATT-047 | P2 |
| R2-09 Multi-currency | BR-PAY-013 | P2 |
| R2-10 E-labour contract | BR-PPL-058, BR-SAAS-063 | P2 |
| R2-14 Bonus / Tết | BR-PAY-070 bonus and off-cycle run types with correct PIT: **P1**. Tết/13th-month eligibility and proration rules (gap-analysis GAP-067 "bonus plan"; the 13th-month pro-rata in BR-PAY-077): **P2**. The pilot pays Tết as a one-off amount entered as variable input; test with sample data | P1 / P2 |
| R2-16 Concurrent employment | BR-PPL-002, 004, BR-PAY-081: one employment per legal entity, primary employment for the PIT family deduction, a single SI employer, each entity withholds PIT on its own payments | P1 |
| R2-17 Payslip delivery | BR-PAY-101 (ESS web) and BR-PAY-102 (password-protected PDF by e-mail). BR-CORE-074 keeps its rule, with payslips as the agreed exception | P1 |
| R2-18 Baseline | §3.4 and the Baseline column in §9 | — |

### 11.4 Conflicts with statutory compliance, and other open points

| # | Item | Why it matters | Proposed handling | Owner |
|---|---|---|---|---|
| R2-C1 | **Sponsor to decide.** E-invoicing of YourHRM's own subscription invoices (BR-SAAS-014/015) needs a certified e-invoice provider, which is a live integration. **Kept P1 for now** as statutory. The architects treated it as P2, with vendor finance invoicing manually from the usage report under R2-01 | The obligation is the vendor's own (Decree 123/2020 as amended by Decree 70/2025), not the tenant's. Either option is compliant if invoices are issued on time | Option A: keep P1 (one provider link). Option B: P2, and vendor finance issues the invoices in its existing e-invoice software from the month-end headcount report (−2 P1 BRs). The architecture docs and this file must then agree | **Sponsor**; PM + vendor finance |
| R2-C2 | **R2-16 "single SI employer"** | For a person with several labour contracts, SI/UI follow the first contract, but HI is due on the contract with the highest salary, and OA-OD is due from **each** employer `[verify-legal]`. A strict "one SI employer" rule could under-declare HI or OA-OD in the second entity | Model SI, HI, UI and OA-OD participation separately per employment (People BR-PPL-004, Payroll BR-PAY-040/044). Legal consultant to confirm (GAP-031) | People + Payroll BA, legal consultant |
| R2-C3 | **E-PIT withholding certificates** (BR-PAY-059, BR-SAAS-062, P1) need the employer's digital signature and, under Decree 70/2025 and Circular 32/2025, possibly sending the data to the tax authority `[verify-legal]`. E-sign (BR-CORE-103, BR-SAAS-063) is P2 and no tax-authority link is in P1 | Leavers can ask for a certificate at any time; full-year certificates are due in early 2027 | P1 produces the certificate XML/PDF. The tenant signs and transmits it through its own e-document/e-invoice provider, or a minimal signing step with the tenant's own token is added to P1. Decide before go-live | Payroll BA + legal consultant |
| R2-C4 | **BR-ATT-075 cut** (early close for one employee) | Final settlement is due within 14 working days (Labour Code Art. 48) and may fall before the period closes | The leaver's final attendance is entered as variable input to the final-settlement run. The Payroll BA confirms that the X-03 hold logic allows a final-settlement run without a closed T&A period | Payroll + T&A BA |
| R2-C5 | **BR-PPL-019 cut** (education & certificates) | The labour management book (BR-PPL-140, Decree 145) includes qualification / skill level | Keep one "qualification / skill level" field in BR-PPL-010 or BR-PPL-140 | People BA |
| R2-C6 | **E-mail dependency** | Factory staff often have no work or personal e-mail. ESS activation (BR-SAAS-045), MFA OTP, OD-8 former-employee OTP and the e-mail payslip (BR-PAY-102) all rely on it now that SMS is P2. The employer must still give each employee the payslip details on every payment (Labour Code Art. 95(3)) | Printed or HR-handed PDF payslips (BR-PAY-100 PDF), HR-issued one-time activation codes; personal e-mail captured at hire | Payroll + SaaS BA |
| R2-C7 | **Password-protected PDF payslip** (R2-17) relaxes the Core deep-link rule | PDPL: the PDF password must be strong. The example rule in BR-PAY-102 ("date of birth + last 4 ID digits") is guessable | DPO acknowledgement; tenant-set or employee-set PIN; audit of sending (GAP-057) `[verify-legal]` | DPO + Payroll BA |
| R2-C8 | **BR-SAAS-036/037 cut** (two-person break-glass, tenant-visible operator log) | PDPL controller oversight of the processor | Access is still logged (BR-CORE-041, BR-SAAS-033/034); the log is exported for the tenant on request; manual two-person procedure documented | SaaS BA + DPO |
| R2-C9 | **Resolved (R2-02 sync).** Baseline scope (R2-18) did not list the holiday calendar and schedule patterns | Payroll without T&A still needs paid holidays and standard working days (BR-PAY-009, BR-PAY-031) | The holiday-calendar and schedule read API (ATT-P-16, now M) and the gross↔net simulator (IF-PAY-P-15) are marked as **API-level always-on services** (`API` in §9; §3.4). The full T&A features stay module-bound | PM (closed) |
| R2-C10 | **BR-PAY-073 cut** (subset / single / simulation runs) | G2 (first run reviewed before approval) and the R2-03 comparison need a run that is calculated but not approved | Use the normal run lifecycle (calculated, not submitted) as the preview, and a discarded comparison run for BR-SAAS-073 | Payroll BA |
| R2-C11 | **Custom fields P2** (BR-CORE-086, MoSCoW now M) | The opening-balance employee-master template may carry tenant-specific attributes that have nowhere to land without custom fields | Keep P2. The pilot maps tenant attributes to standard fields or to a document attachment; any attribute the pilot needs must be listed during template validation | PM + People BA |

No round 2 decision removes a statutory output: SI, PIT, labour-contract, OT-cap, leave and PDPL outputs remain P1. R2-C1 to R2-C3 are the points where "no live integration" and a statutory obligation meet.

### 11.5 R2-02 final scope sync (2026-10-16)

The module BAs applied the single-owner table (R2-02). This added 9 requirements and removed 5 rows from their old module. Re-running step 3 gave 583 module rows against 574 ratings. After this sync the two sides match: 583 = 583.

**New requirements**, rated by the PM and marked "new (R2-02, 2026-10-16)" in §9:

| ID | Title | Phase | Reason |
|---|---|---|---|
| BR-CORE-088 | Exchange-rate table | P2 | Multi-currency is P2 (R2-09) |
| BR-PPL-023 | Tax residency | P1, `B` | Statutory (PIT residency); part of the People basics tax profile |
| BR-PPL-039 | Reorg mapping execution | P2 | Reorganisation packages are P2 |
| BR-PAY-065 | 183-day residency check | P1 | Statutory |
| BR-PAY-085 | Pay group assignment | P1 | Chain |
| BR-PAY-086 | People pay-impacting events | P1 (partly) | Chain, for P1 producers only: contract suspension/resume (BR-PPL-056) and temporary transfer (BR-PPL-035). P2 with their producers: rewards (BR-PPL-085), secondment (034), appraisal (093), cash advance (111) and training bond (101) |
| BR-PAY-087 | Legal-entity lock state | P1 | Chain |
| BR-SAAS-090 | Job scheduler / queue | P1 | Foundation |
| BR-SAAS-091 | Baseline always-on | P1, `B` | R2-18 |

**Removed rows**: phase `Removed`, excluded from all counts. The IDs are kept and not reused.

| Removed ID | Old phase | New owner | Is the capability still covered? |
|---|---|---|---|
| BR-CORE-038 local login, password policy, MFA, sessions | P1, `B` | saas-platform | **Yes.** BR-SAAS-038 (password policy), 039 (MFA) and 043 (sessions) are all P1 and now marked `B` |
| BR-CORE-039 SSO / SCIM | P2 | saas-platform | BR-SAAS-040/041 P2, 042 P3. No P1 loss |
| BR-CORE-042 tenant API clients | P2 | saas-platform | BR-SAAS-056 P2. No P1 loss |
| BR-CORE-113 export framework | P1, `B` | saas-platform | **Yes.** BR-SAAS-046 (reporting and list-view exports) is P1 and now marked `B`. Async exports (BR-SAAS-053) remain an Option B cut (P2), so P1 exports run synchronously |
| BR-SAAS-027 custom fields | P2 | core | BR-CORE-086, P2. MoSCoW changed S → M, phase stays P2 (R2-C11). No P1 loss |

**Other PM-approved changes**:
- **ATT-P-16** (holiday-calendar and schedule read API) is now MoSCoW M. This is recorded in T&A §7 only, because it is an interface. It is an always-on API, as is the gross↔net simulator (IF-PAY-P-15, in Payroll §7). Both are marked `API` in §9; see §3.4.
- **BR-PPL-050** (contract record) is marked `B`, because BR-SAAS-091 lists "contract" under People basics.

## 12. Phase-tag sync for the module BRDs (replaced 2026-10-16)

The per-row tag list in v0.2 is withdrawn. The **phase banner rule** replaces it. Each module BRD 01–06 now carries this banner after its document-control table:

> **Phase:** the delivery phase of every requirement is set only in [mvp-scope.md](../mvp-scope.md) §9; MoSCoW here is business priority. (2026-10-16)

The banner sits after the table rather than inside it, so that the table still renders.

Consequences:
- **§9 of this file is the only source of phase.** A MoSCoW `M` in a module BRD no longer implies P1.
- **03-time-attendance.md** keeps its Phase column, and that column is now synced to §9: 9 rows were updated, with "MVP" meaning P1. They are BR-ATT-021, 026, 034, 043, 063, 066, 070, 075 and 083.
- **Check.** Re-running steps 5–6 gives **0 mismatches** for T&A BR rows. The T&A NFR rows have no Phase column, so the banner covers them.
- **Other modules.** The remaining step-6 output for 01, 02, 04 and 06 (64 rows) is MoSCoW-vs-phase difference only. It is expected under the banner rule and needs no action.
