# Decision Log & RAID — Phase 0 (2026-10-16)

Consolidates the open decisions raised by the BRD, the gap analysis and the architecture drafts. Each one needs an owner and a date before the MVP backlog is frozen.

**Status 2026-10-16:** all items in §1–§2 decided by the sponsor (see Decision column). Applied to the charter, architecture brief, BRDs and ADRs on 2026-10-16. PM interpretations are noted in §6.

## 1. PM-level conflicts found during cross-review
| ID | Conflict | Sources | PM recommendation | Decide by | Decision |
|---|---|---|---|---|---|
| X-01 | **Salary amounts encrypted?** The solution architect lists them as encrypted; the data architect recommends against it, because it breaks SQL totals, GL, reports and payroll speed. | architecture-overview.md; DESIGN.md §15 D-02/D-11; ADR-0026 | Follow the data architect: no column encryption for amounts. Protect them with RLS, module roles, masking views and access audit. Encrypt identifiers and bank accounts only. | Architects + DPO | Agree |
| X-02 | **Module roles reading Core global reference tables** breaks the solution architect's "no grants on other schemas" rule. | DESIGN.md D-12; ADR-0021/0035; ADR-0001 | Accept a read-only exception for global reference data only, listed in ArchUnit/DB tests. | Architects | accept |
| X-03 | **Period close:** the solution architect changes gap-analysis §7 to add a payroll *hold* on the attendance version. | ADR-0019; gap-analysis §7, GAP-027 | Accept it, because it removes the lock deadlock. The T&A and Payroll BAs update their BRDs. | T&A + Payroll BA | Accept |
| X-04 | **MVP scope:** 310 must-have BRs (excluding Recruitment) against a 16-week pilot go-live. | BRD §9–10 | High risk. Re-rate the MVP to "pilot-critical" only. Expect to cut it to about 180–200 BRs, deferring appraisal, training, expense, report designer and white-label. | Sponsor + PM | Yes, cut off features and put all mobile app features to next phase. Phase 1 only core features on each module. |
| X-05 | **Punch ingestion capacity:** T&A wants 200/s per region, SaaS ~333/s, and the solution architect set 500/s across the platform and 50/s per tenant. | GAP-058; architecture-overview | Use the solution architect's figure, to be confirmed in spike SP-3. | T&A BA | accept |
| X-06 | New events not in catalogue v1: `att.AttendancePeriodHoldGranted/Released`, `core.ReorgPackageStatusChanged`, `people.OnboardingReadinessChanged`. | architecture-overview | Add them to gap-analysis §6 (catalogue v1.1). | Integration analyst | Agree add to gap-analysis §6 (catalogue v1.1) |
| X-07 | **Charter amendment:** add a `saas` schema. | GAP-014 | Approve. | Sponsor | Approve |

## 2. Sponsor / business decisions
| ID | Decision needed | Source | Decision |
|---|---|---|---|
| OD-1 | VN cloud provider for production and DR (Viettel IDC / FPT / VNG / CMC). Must support the PG extensions, logical replication and KMS (also D-07). | Solution arch. §17.2, Data arch. D-07 | Run the 2 weeks  evaluation (SP-7), then decide. |
| OD-2 | Frontend framework: React + TS + Ant Design, or Angular. Depends on team skills. | ADR-0013 | React + TS + Ant Design |
| OD-3 | Java 25 + Spring Boot 4, or the Java 21 + Boot 3.5 fallback. Decide after spike SP-1. | ADR-0002 | Java 25, with 21 as the fallback after the first spike |
| OD-4 | Accept an in-house workflow engine with no BPMN designer. | ADR-0007 | Accept |
| OD-5 | BI for Phase 2: Superset, or a commercial tool. | ADR-0012 | Superset |
| OD-6 | Infrastructure cost target per employee per month, and an Enterprise dedicated-tier price floor. | Solution arch. | calcuate later, not need right now. |
| OD-8 | Former-employee login by e-mail OTP, which means capturing a personal e-mail at offboarding (GAP-036). | ADR-0005 | Accept |
| OD-9 | PWA web check-in for the pilot; native app in Phase 2. | Solution arch. | Do this on phase 2. |
| OD-10 | Software key root for MVP; HSM before the Enterprise tier. | Solution arch. | Accept |
| D-01 | Accept eventual integrity between modules (no cross-schema foreign keys). | ADR-0022 | Accept |
| D-03 | Archive thresholds: punches 25 months, timesheets 37, payroll lines 36. Restore within about 2 working days. | ADR-0030 | accept |
| D-09 | Migrated Oracle history shows the cut-over date as its "known" date. | ADR-0033 | No need migration, we will build a new system. |
| D-10 | Definition of a billable employee. | DESIGN.md §15 | count active person at month end, per legal entity. |
| Flyway | Licence terms for the Community edition. | ADR-0031 | Yes, I think Community licence that enough |

## 3. Legal / DPO validation (blocking for payroll go-live)
- Every `[verify-legal]` figure across the BRDs: SI Law 2024 rates and caps, regional minimum wages for 2026, the PIT schedule and deductions for 2026, OT caps and rates.
- D-04: retention periods, including "attendance kept 10 years after employment ends".
- D-05: backups keep erased persons' data for up to 35 days. Needs a DPO statement under PDPL 2025.
- PDPL 2025 / Decree 13/2023: DPIA for biometrics, GPS and selfies (T&A) and for candidate data (Recruitment).

## 4. Spikes before architecture sign-off
| Spike | Goal | Owner |
|---|---|---|
| SP-1 | Payroll engine: 10k employees ≤ 15 min (target 5) | Solution arch. |
| SP-2 | RLS + PgBouncer transaction mode performance at 100k employees per cluster | Data arch. |
| SP-3 | Punch ingestion at 500/s, with offline device buffer | Solution arch. |
| SP-4 | Formula coverage of the pilot pay policies (from its pay-rule documents, not Oracle) by the formula language | Payroll BA + Solution arch. |
| SP-7 | VN cloud evaluation (2 weeks) | DevOps |
| — | Key-unwrap cost for 10k persons per payroll run | Data arch. |

## 5. Top risks
1. MVP scope vs the 16-week timeline (X-04).
2. Legal parameter accuracy: 2026 PIT and SI changes are not yet validated.
3. Opening balances: a mid-year go-live needs correct YTD payroll/PIT, SI history and leave balances loaded from Excel templates (no Oracle migration, D-09).
4. The pilot's first Tết bonus run (Feb 2027) needs the Tết/13th-month policy, a scope hole in gap-analysis §8.
5. Building in-house engines (workflow, formula language, scheduler) puts load on the team's capacity.

## 6. PM interpretations of decisions (2026-10-16)
| Decision | Interpretation applied | Confirm? |
|---|---|---|
| D-09 No migration | No Oracle migration programme, staging or parallel run. Replaced by a generic **opening-balance import** (Excel templates): employee master, YTD income/PIT/SI, SI contribution history, leave balances, loans — needed for a mid-year go-live and PIT year-end finalisation. | Sponsor |
| X-04 + OD-9 | Phase 1 = core features of each module, delivered as a responsive web app (admin + ESS web). All mobile features (native app, PWA/GPS/selfie check-in) move to Phase 2. Pilot attendance comes from time devices and Excel import. | — |
| D-10 | Billable unit = active person at month end **per legal entity**; a person employed by two legal entities of one tenant is counted twice. | Sponsor |

## 7. Round 2 — decisions needed (raised 2026-10-16 after applying round 1)
| ID | Decision needed | Source | PM recommendation | Decision |
|---|---|---|---|---|
| R2-01 | **MVP size.** Re-scope gives **281 P1 BRs**, not 180–200: 107 are statutory and 121 are the chain those outputs need. Option B cuts 29 more (→ 252), each with a workaround. | [mvp-scope.md](../01-requirements/brd/mvp-scope.md) | Accept 281 as scope and keep Option B as the descope reserve. **Size it in story points before committing to 16 weeks**, because 281 BRs in 16 weeks is high risk. | _Open_ |
| R2-02 | **Approve the single-owner table** (gap-analysis §5) as final. It closes most of the 29 open MVP-blocking gaps. | gap-analysis §3, §5 | Approve as a whole. | _Open_ |
| R2-03 | **No parallel run** with D-09. Add a one-month payroll comparison against the customer's current payroll output (BR-SAAS-073) to P1? About 1 week of work. | mvp-scope §8; BRD RK9 | Yes, P1. | _Open_ |
| R2-04 | SSO (Google / Microsoft 365) deferred to P2. Pilot uses Keycloak username + MFA. | BR-CORE-039, BR-SAAS-040 | Accept, unless the pilot requires SSO. | _Open_ |
| R2-05 | Discipline procedure deferred to P2. Pilot handles dismissals offline. | BR-PPL-080/081 | Accept. | _Open_ |
| R2-06 | Desktop web check-in (IP-restricted) deferred to P2 with OD-9. Staff without a device use Excel import or correction requests. | BR-ATT-024 | Accept if every pilot site has time devices; otherwise keep it in P1 (small). | _Open_ |
| R2-07 | SI benefit-claim data for sick/maternity leave (certificate, facility, child data) deferred to P2. | BR-ATT-063 | **Keep in P1 (minimal).** The employer must file sick/maternity claims to VSS, and payroll needs the same data. | _Open_ |
| R2-08 | Cross-entity annual OT cap stays P2 pending legal advice. | BR-ATT-047; GAP-031 | Accept; ask the labour consultant. | _Open_ |
| R2-09 | Multi-currency (USD contracts for expats) stays P2. | mvp-scope §8 | Accept, if the pilot has no USD contracts. Please confirm. | _Open_ |
| R2-10 | E-labour contract stays P2 unless a decree makes it mandatory before go-live. | People BRD | Accept. | _Open_ |
| R2-11 | X-02 exception list also includes tenant override rows in `core.code_value` / `core.exchange_rate`, plus two plumbing write grants: insert into `saas.job` and append to `audit.audit_event`. | ADR-0021; DESIGN.md §2.3 | Accept as covered by X-02. | _Open_ |
| R2-12 | D-10 details: (a) is a `SUSPENDED` employee billable? (b) confirm a person employed by 2 legal entities is billed twice. | DESIGN.md §11; BR-SAAS-012 | (a) Not billable. (b) Your call; billing once per person per tenant is friendlier. | _Open_ |
| R2-13 | Opening balances: who prepares the Excel files and who signs the reconciliation? Data must start from 1 January of the tax year. | BR-SAAS-088/089 | Customer prepares, our implementation consultant validates, customer C&B lead signs. | _Open_ |
| R2-14 | Tết / 13th-month bonus policy for the pilot (first run Feb 2027). | gap-analysis §8 | Business input needed from the pilot. | _Open_ |
| R2-15 | DPO to record in the DPIA: plaintext salary amounts are readable by holders of the `hr`/`pay` DB credentials (consequence of X-01). | Arch. AR-13 | Accept; mitigate with secrets vault, no human access to app credentials, DB access audit. | _Open_ |
