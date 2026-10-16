# Decision Log & RAID — Phase 0 (2026-10-16)

Consolidates the open decisions raised by the BRD, the gap analysis and the architecture drafts. Each one needs an owner and a date before the MVP backlog is frozen.

**Status 2026-10-16:** rounds 1 (§1–§2) and 2 (§7) decided and **applied** to the charter, BRDs (BRD v0.3, gap analysis v1.2, mvp-scope v0.2), architecture overview v0.3, DESIGN.md v0.3 and ADRs. Phase 1 = 259 BRs + 58 NFRs. No open gap blocks the MVP. Round 3 (§8) is open. PM interpretations: §6 and §7.2.

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
| R2-01 | **MVP size.** Re-scope gives **281 P1 BRs**, not 180–200: 107 are statutory and 121 are the chain those outputs need. Option B cuts 29 more (→ 252), each with a workaround. | [mvp-scope.md](../01-requirements/brd/mvp-scope.md) | Accept 281 as scope and keep Option B as the descope reserve. **Size it in story points before committing to 16 weeks**, because 281 BRs in 16 weeks is high risk. | Remove external intergration with time devices, zalo, too down scope. |
| R2-02 | **Approve the single-owner table** (gap-analysis §5) as final. It closes most of the 29 open MVP-blocking gaps. | gap-analysis §3, §5 | Approve as a whole. | Agree |
| R2-03 | **No parallel run** with D-09. Add a one-month payroll comparison against the customer's current payroll output (BR-SAAS-073) to P1? About 1 week of work. | mvp-scope §8; BRD RK9 | Yes, P1. | agree |
| R2-04 | SSO (Google / Microsoft 365) deferred to P2. Pilot uses Keycloak username + MFA. | BR-CORE-039, BR-SAAS-040 | Accept, unless the pilot requires SSO. | No need SSO at pilot. SSO can be move to next phase |
| R2-05 | Discipline procedure deferred to P2. Pilot handles dismissals offline. | BR-PPL-080/081 | Accept. | Agree |
| R2-06 | Desktop web check-in (IP-restricted) deferred to P2 with OD-9. Staff without a device use Excel import or correction requests. | BR-ATT-024 | Accept if every pilot site has time devices; otherwise keep it in P1 (small). | Agree to P2, not all all has time devices, can use excel import. |
| R2-07 | SI benefit-claim data for sick/maternity leave (certificate, facility, child data) deferred to P2. | BR-ATT-063 | **Keep in P1 (minimal).** The employer must file sick/maternity claims to VSS, and payroll needs the same data. | Agree |
| R2-08 | Cross-entity annual OT cap stays P2 pending legal advice. | BR-ATT-047; GAP-031 | Accept; ask the labour consultant. | Agree |
| R2-09 | Multi-currency (USD contracts for expats) stays P2. | mvp-scope §8 | Accept, if the pilot has no USD contracts. Please confirm. | Agree, do not need multi currency |
| R2-10 | E-labour contract stays P2 unless a decree makes it mandatory before go-live. | People BRD | Accept. | Agree, E-labour contract can be stay P2 at this time |
| R2-11 | X-02 exception list also includes tenant override rows in `core.code_value` / `core.exchange_rate`, plus two plumbing write grants: insert into `saas.job` and append to `audit.audit_event`. | ADR-0021; DESIGN.md §2.3 | Accept as covered by X-02. | Agree |
| R2-12 | D-10 details: (a) is a `SUSPENDED` employee billable? (b) confirm a person employed by 2 legal entities is billed twice. | DESIGN.md §11; BR-SAAS-012 | (a) Not billable. (b) Your call; billing once per person per tenant is friendlier. |  (a) Not billable |
| R2-13 | Opening balances: who prepares the Excel files and who signs the reconciliation? Data must start from 1 January of the tax year. | BR-SAAS-088/089 | Customer prepares, our implementation consultant validates, customer C&B lead signs. | Yes, customer prepares |
| R2-14 | Tết / 13th-month bonus policy for the pilot (first run Feb 2027). | gap-analysis §8 | Business input needed from the pilot. | No need when run pilot, put some sample data to verify function work that enough. |
| R2-15 | DPO to record in the DPIA: plaintext salary amounts are readable by holders of the `hr`/`pay` DB credentials (consequence of X-01). | Arch. AR-13 | Accept; mitigate with secrets vault, no human access to app credentials, DB access audit. | Accept |

### 7.1 Round 2 follow-up decisions (2026-10-16)
| ID | Decision needed | Source | PM recommendation | Decision |
|---|---|---|---|---|
| R2-16 | GAP-031: support one person employed by two legal entities of one tenant in Phase 1? | gap-analysis GAP-031 | Basic support: PIT family deduction applied once (primary employment), SI through one employer; cross-entity OT cap P2 (R2-08). | Agree |
| R2-17 | GAP-057: payslip delivery by e-mail. | gap-analysis GAP-057; Core deep-link rule | Secure link by default, PDF opt-in. | **Password-protected PDF** (sponsor). Core's deep-link-only rule is relaxed for payslips. |
| R2-18 | GAP-060: baseline capabilities always on, whatever modules a tenant buys. | gap-analysis GAP-060 | Core, People basics, legal parameters, pay scales/bands (§5 #7), Excel attendance import. | Agree |
| R2-19 | Option B (29 more non-statutory cuts, 281 → ~252) and all live external integrations → P2 (R2-01). | mvp-scope §6 | — | Agree (sponsor) |

### 7.2 PM interpretations of round 2 (confirm or correct)
| Item | Interpretation applied |
|---|---|
| R2-01 integrations | **P2:** live time-device connector, offline buffer and 500/s target (X-05, SP-3 postponed); Zalo OA/ZNS; SMS; e-sign; VNeID; accounting APIs; job boards; public webhooks. **P1 keeps:** e-mail (login, MFA, OD-8 OTP, notifications), in-app notifications, and **file exports** the customer uploads: bank payment file, VSS insurance declaration file, PIT XML for eTax, GL journal in Excel. **Import of time-device software export files (CSV/Excel) stays P1**: an Excel import with a device column layout, not a live integration. |
| R2-12 (b) | Not answered → D-10 stands: billed once per legal entity. |
| R2-13 | Customer prepares the opening-balance files; our implementation consultant validates; the customer's C&B lead signs the reconciliation. |
| R2-14 | No Tết policy engine in P1. The bonus / off-cycle payroll run with correct PIT stays **P1**, so a real Tết bonus can be paid as a one-off amount. Eligibility and proration rules → P2. Test with sample data. |
| R2-07 | BR-ATT-063 (SI benefit-claim data) moves back to **P1**, minimal. |
| R2-03 | BR-SAAS-073 (one-month payroll comparison) moves to **P1**. |

## 8. Round 3 — decisions needed (raised 2026-10-16 after applying round 2)
| ID | Decision needed | Source | PM recommendation | Decision |
|---|---|---|---|---|
| R3-01 | **E-invoices for YourHRM's own subscription billing** (BR-SAAS-014/015) need a certified e-invoice provider API, the only live integration left in P1. | mvp-scope §11.4 R2-C1; arch. open point 1 | **P2.** Vendor finance issues invoices from its existing e-invoice tool, using the usage report. The pilot is unlikely to be billed through the product. | _Open_ |
| R3-02 | **Electronic PIT withholding certificates** (BR-PAY-059) need the employer's digital signature and possibly sending data to the tax authority (Decree 70/2025). E-sign is P2. | R2-C3; arch. point 7 | P1 = data and PDF certificate. The customer signs and issues via the tax authority's own tool or its existing provider. **Legal consultant must confirm for 2027.** | _Open_ |
| R3-03 | **Insurance for one person with contracts in two legal entities:** SI/UI follow the first contract, HI the highest salary, OA-OD due from each employer. | R2-C2; GAP-031 | Implement per-type, per-employment participation (already designed). **Legal consultant to confirm the rules.** | _Open_ |
| R3-04 | **Payslip PDF password:** the employee sets it in ESS, with no default or derived password. Until it is set, the e-mail is a notice with no attachment. | R2-C7; BR-PAY-102; ADR-0015 | Accept. **DPO sign-off.** | _Open_ |
| R3-05 | **Employees without e-mail** can't receive OTPs or payslips, but payslip details must reach every employee (Labour Code Art. 95(3)). | R2-C6; BRD RK12 | Fallback: HR prints payslips and issues one-time activation codes for ESS. | _Open_ |
| R3-06 | **Leaver final settlement within 14 working days** without early close of one employee's attendance (BR-ATT-075 cut under Option B). | R2-C4 | Restore BR-ATT-075 to P1 (small), because the workaround (manual payroll input) is error-prone for a statutory deadline. | _Open_ |
| R3-07 | **Labour management book** still needs the qualification field (BR-PPL-019 cut under Option B). | R2-C5 | Restore a minimal qualification field to P1 (statutory). | _Open_ |
| R3-08 | **Operator / break-glass access log** cut under Option B, but PDPL requires processor oversight. | R2-C8 | Restore minimal logging of operator access to tenant data in P1. | _Open_ |
| R3-09 | **Custom fields P2:** tenant-specific attributes in the opening-balance employee file have nowhere to go. | R2-C11 | Map them to standard fields or attachments during template validation. | _Open_ |
| R3-10 | **GAP-049:** OA-OD rate and PIT declaration frequency live on the Core legal entity (PM brief), not in Payroll settings. | gap-analysis v1.2 | Accept. | _Open_ |
| R3-11 | **Phase-1 tech simplification:** no Kafka (DB-backed event bus, ADR-0004 rev. 2), no ingest role (ADR-0001 amendment), reporting fed by native logical replication (ADR-0012 amendment). Kafka is added in P2. | architecture-overview v0.3 §7.2 | Accept: it saves weeks of ops work in a 16-week plan. | _Open_ |
