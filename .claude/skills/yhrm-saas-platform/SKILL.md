---
name: yhrm-saas-platform
description: Domain knowledge for the YourHRM SaaS Platform module (BR prefix BR-SAAS / NFR-SAAS, schema `saas`, Gradle module `saas-platform`, expected backend package `vn.yourhrm.saas`, not yet created). The module owns the tenant lifecycle (provisioning, suspension, offboarding and purge), subscriptions, entitlements and baseline capabilities, billable-employee metering and invoices, the operator back-office, support grants and the operator access log, authentication through Keycloak, the Legal Parameter Service (legal packs, overrides, getParam), the job scheduler and Phase-1 event bus, the reporting/export/statutory-template framework, the ESS web shell, privacy registries (consent, PD inventory, DSR, retention), keyring, and go-live onboarding with opening balances. Use it when building or reviewing anything in `saas.*`, `001_saas.sql`, `ddl/reporting/` or `007_publications.sql`, or when another module calls LPS, entitlement, the scheduler or the tenant context. Vietnamese trigger terms: tenant, thuê bao, gói dịch vụ, quyền sử dụng, nhân viên tính phí, hóa đơn điện tử, tạm ngưng, xóa dữ liệu tenant, back-office nhà vận hành, quyền cấp hỗ trợ, mạo danh, đăng nhập, MFA, SSO, tham số pháp lý, gói tham số pháp lý, ghi đè, bộ lập lịch job, báo cáo, xuất dữ liệu, mẫu theo luật định, ESS web, sự đồng ý, yêu cầu của chủ thể dữ liệu (DSR), danh mục dữ liệu cá nhân, lưu trữ, số dư đầu kỳ, go-live, đối chiếu, BVDLCN, Nghị định 13, Nghị định 85.
---

# YourHRM SaaS Platform (`saas`)

**Also load `yhrm-conventions`.** It covers tenancy/RLS, PII classes, the outbox, no cross-schema FKs, the legal-parameter rule and build commands. This file does not repeat them.

**State of the code:** there is **no `backend/src/modules/saas` yet**. `backend/settings.gradle.kts` includes only `shared:common`, `shared:auth`, `modules:core` and `app`. Create the module as `vn.yourhrm.saas.api` plus `internal.{domain,application,infra,web}` (yhrm-conventions). Architecture docs call the Gradle module `saas-platform`, and ADR-0010 names the API `saas-platform.api.LegalParameters`. Compile-time dependencies run `… → core → saas-platform → shared/*` (architecture-overview §3). **SaaS must not import Core.** Reach Core through events or SPIs that SaaS declares and Core implements.

Per-BR detail: read `references/business-rules.md` when you implement a specific BR. It indexes all 113 IDs with MoSCoW and phase. For the PR/CO interface tables, event payloads and bus mechanics, read `references/integration.md`.

## 1. Purpose and scope

SaaS turns YourHRM into a commercial multi-tenant product. It also hosts the cross-cutting services that no HR module owns (BRD §1.1–1.2): tenant lifecycle and commerce; tenancy, isolation and VN residency; the operator back-office; authentication; reporting, exports and statutory templates; integrations (P1 = e-mail and file export); opening-balance onboarding; the ESS shell; privacy and compliance; and the Legal Parameter Service. **SaaS authenticates; Core authorises.**

**Out of scope (owner):**
- HR business rules such as pay formulas, leave accrual and shifts: modules 02–05.
- Roles, permissions, data scopes, masking policy, tenant `user_account` and its provisioning: **Core** (§5 #9).
- Workflow engine, notification centre (SaaS only delivers), document service (SaaS only provides object storage), **import engine** (`core.import_*`), **audit store** (`audit.audit_event`), custom fields (BR-CORE-086, P2), **legal-entity master** (`core.legal_entity`), global master-data store and admin API: **Core** (§5 #11–15b, #23, #28).
- Statutory form content, bank/GL file layouts and opening-balance template handlers: the owning module (People, T&A, Payroll).
- Marketplace, AI and non-VN regions: Phase 3. Customer ERP processes and device hardware are excluded.

## 2. Ownership (gap-analysis §5, R2-02)

**Single owner of:**
- #1 legal parameters (LPS)
- #10 authentication, SSO, MFA, sessions and API clients (credentials held in Keycloak)
- #15 object storage
- #16 job scheduler/queue
- #17 ESS/MSS portal shell
- #18 reporting framework, reporting store, exports and statutory-template framework
- #27 consent registry, PD inventory, DSR orchestration and the retention scheduler

It also owns tenants, plans, subscriptions, entitlements, usage metering, invoices, support grants, the operator access log, the keyring (`tenant_key`, `subject_key`) and the platform audit.

**Consumes, never writes:**

| Fact | Owner | Via |
|---|---|---|
| Legal entity master (MST, SI unit code, tax office, e-invoice identity) | Core | `core.LegalEntityChanged`, Core create-LE API (CO-02) |
| Tenant user status | Core | `core.UserStatusChanged` (CO-06) |
| Roles, scopes, masking | Core | runtime API/SPI (CO-01, CO-16) |
| Month-end active persons per LE | People | `people.HeadcountSnapshotTaken` (CO-05) |
| First regular run approved per LE (opening-balance lock) | Payroll | `pay.PayrollRunStatusChanged` |
| Opening-balance targets (`hr.*`, `pay.ytd_balance`, `pay.si_contribution`, `att.leave_balance_ledger`, `pay.loan*`) | People / Payroll / T&A | owner `ImportHandler` on `core.import_job` (ADR-0037) |

## 3. Core domain model (DDL `database/postgresql/ddl/001_saas.sql`, DESIGN §6, §7.1)

Placement classes (ADR-0036, DESIGN §3.1):
- **PLATFORM** tables are written only in the platform cluster.
- **REPLICATED** tables are read-only copies in pool and dedicated clusters: `tenant`, `tenant_placement`, `entitlement`, `baseline_capability`, published `legal_param_*`, `retention_policy`.
- **LOCAL** tables live per cluster: `job` and the outbox/inbox tables.

| Entity | Table(s) | Notes / invariants |
|---|---|---|
| Tenant | `saas.tenant` [P] | `state` ∈ PROVISIONING, TRIAL, ACTIVE, SUSPENDED, TERMINATING, TERMINATED, PURGED. `tier` ∈ SHARED, DEDICATED. `region = 'VN'` (CHECK). `slug` is unique. App roles can read only their own row (X-02). |
| Placement / routing | `saas.tenant_placement` | tenant → cluster. At most one current row (PROVISIONING/ACTIVE/MOVING). |
| Plan / edition | `saas.plan`, `saas.plan_version` | A plan version is immutable. `modules`, `features` and `limits` are jsonb. |
| Subscription | `saas.subscription`, `saas.subscription_item` | `billing_cycle` ∈ MONTHLY, QUARTERLY, ANNUAL. Items have `quantity_basis` ∈ PEPM, FLAT, USAGE and an optional price override in VND. |
| Entitlement | `saas.entitlement` | Pattern A (`valid_from`/`valid_to`, exclusion constraint per tenant × feature × LE × period). `source` ∈ BASELINE, PLAN, ADDON, OVERRIDE, TRIAL. A BASELINE row must be enabled and tenant-wide. |
| Baseline capability | `saas.baseline_capability` | Keys: `baseline.core`, `baseline.people.master`, `baseline.legal_params`, `baseline.pay.scales_bands`, `baseline.att.excel_import`. The release job ships this list; operators and tenants never edit it. |
| Usage / billable count | `saas.usage_record` | Monthly partitions. `BILLABLE_PERSON` requires `module_code` and `legal_entity_id`. The billable quantity is the row `measured_on` the last day of the month. Frozen rows are immutable (trigger); corrections are `MANUAL_ADJUSTMENT` rows. `detail` holds counts only and never person ids. |
| Invoice | `saas.invoice`, `saas.invoice_line` | `total = subtotal + vat` (CHECK). `invoice_no` is unique platform-wide. In-product invoicing is **P2** (R3-01). |
| Support grant | `saas.support_grant` | `valid_to ≤ valid_from + 72 h` (CHECK). `access_mode` ∈ READ, READ_WRITE. `includes_sensitive` unmasks P3. |
| Operator access log | `saas.operator_access_log` | Append-only (`sys.tg_forbid_change`). `support_grant_id` is NULL **iff** `access_type = BREAK_GLASS`. IMPERSONATION requires `impersonated_user_id`. `detail` is a masked summary only. |
| Keyring | `saas.tenant_key`, `saas.subject_key` | KMS references only; DEKs are stored wrapped. `status = DESTROYED` ⇔ `wrapped_dek IS NULL` (crypto-shred). |
| Consent | `saas.consent_record` | `subject_type` PERSON/CANDIDATE (soft ref). `status` ∈ GIVEN, WITHDRAWN, EXPIRED. |
| DSR | `saas.data_subject_request` | Types: ACCESS, RECTIFY, ERASE, RESTRICT, OBJECT, PORTABILITY, WITHDRAW_CONSENT. `fanout_status` tracks each module handler. |
| Retention | `saas.retention_policy` | Global rows use the sentinel tenant, with tenant overrides. `retention_period ≥ legal_floor` (CHECK). |
| Archive manifest | `saas.archive_manifest` | Object URI, row count, sha256, key version. |
| Legal packs [G] | `saas.legal_param_definition`, `legal_param_pack`, `legal_param_value`, `legal_param_table_row` | Pure global (no tenant_id). Pack lifecycle: DRAFT → IN_TEST → APPROVED → PUBLISHED → SUPERSEDED (+ WITHDRAWN). Content is immutable after IN_TEST (trigger). Exactly one value column is set. Values have no overlap per pack × key. `param_key` must match `^[A-Z0-9_]+(\.[A-Z0-9_]+)*$`. |
| Overrides [T] | `saas.legal_param_override_set`, `saas.legal_param_override` | `override_version` is monotonic per tenant, with one ACTIVE set per tenant. Override scope is TENANT (both ids NULL), LEGAL_ENTITY, or LOCATION (also requires `legal_entity_id`). A trigger enforces the definition's `override_rule` ≠ NONE and the maximum `override_scope`. |
| Job queue | `saas.job` | LOCAL. Has `idempotency_key` (unique per tenant × job_type) and `ordering_key` lanes. Workers claim through `saas.claim_jobs()` only. |
| Platform audit | `saas.platform_audit_event` | Hash-chained. Holds only operator/platform events that are not tied to a tenant; the purge certificate goes here. |
| Outbox/inbox | `saas.outbox_event`, `saas.inbox_message` | Created by `sys.create_outbox_inbox('saas', 'yhrm_app_saas')`. |

**Deliberately absent:** no credential, password, MFA, session or refresh-token tables, because Keycloak owns them (ADR-0005). The link is `core.user_account.identity_id`.

**Not yet in DDL** (DESIGN §7.1): `price_book`, `feature_flag`, `payment`, `operator_session`, `security_incident`, `billing_party`, and the P2 tables `identity_provider_config`, `api_client`, `webhook_subscription`. The billing party's e-invoice identity currently sits in `core.legal_entity_version.einvoice_identity`.

**No table exists** for several P1 needs: the per-LE go-live cut-over plan and lock (BR-SAAS-088), the report catalogue, statutory templates (052), the PD inventory (079) and versioned consent notices/purposes (080). Raise a DDL change; don't improvise across schemas.

Reporting store (separate DB, `ddl/reporting/100_rpt.sql`): landing schemas are named like the sources, `rpt_raw` holds views, and `rpt` is the star schema with RLS (`fact_headcount_daily.is_billable`, `report_scope`). The feed is `ddl/007_publications.sql`: `pub_rpt_*` with column lists that never contain P3 columns, replicated through `yhrm_cdc`.

## 4. Key processes (BRD §3)

- **P2 Assisted provisioning (P1 path, ≤ 10 min, BR-SAAS-003):**
  1. The operator creates the tenant, billing party, modules, contract dates and committed seats.
  2. Automated steps: tenant row and placement, Keycloak Organization plus owner invite, first LE via the Core API, reference-data seed, current legal pack, BASELINE entitlements.
  3. Any failure triggers automatic rollback and an operator alert.
  4. Emit `saas.TenantProvisioned`.
- **P4 Billable count (P1):**
  1. A month-end job writes `usage_record` `BILLABLE_PERSON` per LE × module from `people.HeadcountSnapshotTaken`.
  2. Fee = max(committed seats, Σ billable) × module unit price.
  3. P1 output is only a usage report for vendor finance; the e-invoice is issued outside the product (R3-01).
- **P5 Suspension:**
  - Dunning steps run D+1 → D+15 → D+30 read-only → D+60 locked (contract-configurable; automation is P2).
  - The allow-list is never blocked: own payslip and PIT certificate, owner data export, DSR.
  - Jobs of a locked tenant pause except allow-listed ones (ADR-0011 §6).
  - Payment triggers reactivation within 15 min.
- **P6 Support access (P1):**
  1. Request with ticket, modules, mode, sensitive flag and duration ≤ 72 h.
  2. Tenant approval through a Core workflow.
  3. The operator token gets tenant context only under an active grant, with `app.support_grant_id` set and `mask_level` defaulting to full masking.
  4. Banner shown; every action goes to `audit.audit_event` and `operator_access_log`.
  5. Auto-expiry plus a session summary.
- **P8 Offboarding / purge (P2, D9):**
  1. Export.
  2. State becomes TERMINATING: login blocked, read-only for 90 days.
  3. Crypto-shred: schedule KEK destruction.
  4. Batched `DELETE` per schema run by the purge job as `yhrm_owner`. Ops grant `yhrm_purger` for the window only (two-person). The job sets `app.purge_tenant_id`, and `sys.purge_bypass(tenant)` then lets append-only triggers allow DELETE (never UPDATE).
  5. Purge the reporting store and object prefix.
  6. Move the tenant to PURGED and write the certificate with row counts to `platform_audit_event`.
  7. `audit.audit_event` is **never** purged. Never run the purge as superuser.
- **P9 Opening-balance go-live (P1, ADR-0037, DESIGN §13):**
  1. Cut-over plan per LE: go-live month, balances as at end of the previous month.
  2. Load templates in order: org units/positions/cost centres → `OB_EMPLOYEE_MASTER` → salary history → `OB_SI_HISTORY` → `OB_YTD_PAYROLL` (every tax-year month before go-live, BR-SAAS-089) → `OB_LEAVE_BALANCE` → `OB_LOAN`.
  3. Validate-only until clean.
  4. Reconcile against customer control totals (`core.import_reconciliation`; tolerance 0 VND / 0.0 days).
  5. Customer sign-off; the DB CHECK blocks commit without it.
  6. Commit through the owner handlers. Events carry `initial_load = true`.
  7. The first regular run is approved, which locks the balances; adjustment batches only after that (088).
- **P10 Legal change:**
  1. Draft pack with legal references.
  2. IN_TEST with the golden payroll suite.
  3. APPROVED with consultant sign-off.
  4. PUBLISHED: immutable, the previous pack becomes SUPERSEDED, `saas.LegalParameterPackPublished` lists the changed keys and dates.
  5. Payroll/T&A create retro work items for affected approved periods.
  6. Notify tenants ≥ 7 days ahead where the law allows (NFR-SAAS-016).
- **P12 DSR:**
  1. Request via ESS or the DPO.
  2. The DPO verifies and approves; `saas.DataSubjectRequestOpened` fans out to module handlers (CO-07).
  3. Track `fanout_status` and the deadline `[verify-legal]`; keep evidence.

## 5. Critical rules and invariants (never break)

1. **Isolation (BR-SAAS-021/023).** RLS plus an application guard on every tenant table, and isolation tests block the release. Never use a BYPASSRLS role in app or job code. Cross-tenant workers go only through `saas.claim_jobs()` and then set the tenant context per transaction.
2. **No credentials in `saas`** (ADR-0005, BR-SAAS-038/039/043). Passwords, TOTP, OTP and sessions live in Keycloak. Tokens carry `tid`, `acr` and `amr`, and **no roles**. Step-up uses ACR 2/3.
3. **Operators have zero default access (033).** No grant means no tenant context. Mask P3 unless `includes_sensitive` (034). Every operator access is written to `operator_access_log` (037, append-only) **and** to Core's audit store (085).
4. **Entitlement is authoritative in the backend (008).** Use `@Entitled`, return 403 `FEATURE_NOT_ENTITLED`, expose `/me/capabilities`. BASELINE capabilities can never be disabled by a plan or override (091). Runtime reference services that other modules call (holiday calendar, gross↔net simulator) are not entitlement-gated (ADR-0003 §5).
5. **Billable unit = D-10.** Count active employment on the **last day of the month, per LE and per module**. A person in two LEs counts twice; SUSPENDED and pre-hire persons do not count (R2-12a). Never read `hr.*` directly; use the People event/API. Frozen usage months are immutable.
6. **Downgrade or module end never deletes data**; the data becomes read-only (011, US-SAAS-007).
7. **Suspension allow-list** (017, ADR-0011): own payslip and PIT certificate, owner export and DSR always work.
8. **Legal packs are immutable once published.** Fix one by publishing a new version. Consumers never copy parameters into their own tables, except the stamped run/result snapshot. Every answer carries `packVersion` + `overrideVersion` (ADR-0010 §3), and every result stamps them (NFR-SAAS-017).
9. **Overrides only where the definition allows** (`override_rule` ∈ ANY, FLOOR, CAP, at most `override_scope`). Resolution is GLOBAL pack → TENANT → LEGAL_ENTITY → LOCATION, and the most specific wins. FLOOR/CAP are checked in the service at activation and again on every pack publish. An activated set is immutable, so a change means a new `override_version`, approved through a Core workflow.
10. **Job payloads in the `events` queue are pointers only**; amounts never land in `saas.job`. Handlers are idempotent (at-least-once delivery).
11. **Data residency (024).** No PII leaves VN infrastructure (e-mail relay, monitoring and error tracking included) without a transfer impact assessment.
12. **Opening balances.** Commit needs reconciliation sign-off. A missing pre-go-live month blocks the LE's go-live (089). After the lock, change only through audited adjustment batches (088). Customer template files are never sent by e-mail or committed to git (NFR-SAAS-008).
13. **Purge (D9).** DELETE only, only for a TERMINATING tenant, only with `yhrm_purger` membership plus `app.purge_tenant_id`. `audit.audit_event` is never purged.
14. **ESS without e-mail (045, R3-05).** No activation code is issued and no ESS access is granted in P1; HR hands out a printed PDF payslip.

## 6. VN compliance (BRD §5; every legal reference is `[verify-legal]` until the VN legal/tax consultant confirms it)

| # | Rule | BR | Basis `[verify-legal]` |
|---|---|---|---|
| C1 | Store tenant data in VN; minimum retention for some data/logs | 024 | Cybersecurity Law 2018 Art. 26; Decree 53/2022 Art. 26–27; check the new 2025 Cybersecurity Law |
| C2 | Legal basis for processing; stricter handling of sensitive data (health, biometrics, financial, location) | 034, 079, 080 | PDPL 2025 (91/2025/QH15, effective 2026-01-01); **Decree 13/2023** for transition |
| C3 | DPA tenant (controller) ↔ vendor (processor); impact assessment; cross-border transfer assessment | 024, 083 | PDPL 2025 |
| C4 | Breach notice to A05 (Ministry of Public Security) within 72 h | 084 | PDPL 2025; Decree 13/2023 Art. 23 |
| C5 | Data subject rights handled within statutory deadlines | 081 | PDPL 2025 (BRD Q5: deadlines may change) |
| C6 | E-invoice content, numbering, tax code; corrections by adjusting/replacing invoice | 014, 015 (P2) | Decree 123/2020 amended by Decree 70/2025; Circular 78/2021, 32/2025 |
| C7 | E-PIT withholding certificate | 062 | Decree 123/2020 Art. 30–33 as amended by Decree 70/2025 |
| C8 | E-tax filing in the prescribed XML | 062 | Tax Administration Law 38/2019; Circular 80/2021 |
| C9 | Electronic SI/HI/UI declaration via licensed I-VAN | 061 (P2) | SI Law 2024 (41/2024/QH15) |
| C10 | E-labour contracts and e-signatures | 063 (P2) | Labour Code 2019 Art. 14; e-Transactions Law 2023; Decree 23/2025 |
| C11 | VNeID only through authorised channels | 064 (P3) | Decree 69/2024; Identification Law 2023 |
| C12 | Accounting records (payroll, subscription invoices) kept ≥ 10 years | 019, 082 | Accounting Law 2015; Decree 174/2016 |
| C13 | IS security-level classification (**Decree 85/2016**) | 087 (P1, process only) | Network Information Security Law 2015; Decree 85/2016 |
| C14 | SMS brandname registration; no ads in transactional HR SMS | 066 | Decree 91/2020 |

Parameters to model, never hard-code: retention floors (`retention_policy.legal_floor`), DSR deadlines, the breach window, and log retention (NFR-SAAS-010).

LPS keys include minimum wage plus admin-unit → region map, SI/HI/UI/OA-OD rates and caps, the reference level, PIT brackets and deductions, the 10 % casual-income threshold, OT limits, the night window, leave entitlement, probation/notice limits, the retirement-age roadmap, the settlement deadline and the national holiday template. Also `insurance.multi_contract_rule` per insurance type (FIRST_CONTRACT | HIGHEST_SALARY | EACH_EMPLOYER), default SI/UI/HI = FIRST_CONTRACT and OA-OD = EACH_EMPLOYER, **all `[verify-legal]`** (R3-03; the PM flags that HI may require HIGHEST_SALARY).

## 7. Interfaces and events

Details are in `references/integration.md`.
- **Provides:** PR-01 to PR-18. Key ones are PR-06 LPS, PR-17 scheduler, PR-02/18 entitlement, PR-08 reporting, PR-12 onboarding and PR-13 privacy.
- **Consumes:** CO-01 to CO-16.
- **Publishes:** `saas.TenantProvisioned`, `saas.TenantStateChanged`, `saas.EntitlementsChanged`, `saas.LegalParameterPackPublished`, `saas.ConsentChanged`, `saas.DataSubjectRequestOpened`.
- **Consumes:** `core.UserStatusChanged`, `core.LegalEntityChanged`, `people.HeadcountSnapshotTaken`, `people.EmployeeHired`, `people.EmployeeTerminated`, `pay.PayrollRunStatusChanged`.
- **LPS API** (ADR-0010):
  - `get(key, scope, asOf)`
  - `resolveSet(keys, scope, periodStart, periodEnd)`, with segments for mid-period changes
  - `currentPack(asOf)`
  - `changesSince(version)`
  - Caching: Caffeine per pack version, invalidated on the pack-published or override event via Valkey pub/sub.

## 8. Phase 1 vs Phase 2 (mvp-scope §9.6; don't build P2 by accident)

**P1 (42 BR + 17 NFR):**
- BR-SAAS 001–003, 007, 008, 012, 021, 023–026, 032–034, 037 (minimal), 038, 039, 043, 045, 046, 052, 059/060/062 (file export only), 066 (e-mail only), 069–073, 075, 079–081, 083–085, 087–091.
- All NFRs except 004, 011, 018, 019, 022.

**P2:**
- Self-service trial (004/005), plans/editions and price book (006/010), proration (011), feature flags (009; P1 uses config/deploy toggles), metering beyond the billable count (013), in-product e-invoice/payment/dunning (014–017).
- Offboarding export and purge (018/019, NFR-019), ownership transfer (020), dedicated tier (022), form customisation (028), white-label and custom domain (029/030), sandbox (031).
- Impersonation (035), break-glass automation (036; manual in P1, US-TECH-033), SSO (040/041), multi-tenant users (044).
- Separate reporting store (047), scheduled reports, designer, dashboards, analytics (048–051), async exports (053; P1 is synchronous).
- Public API and webhooks (055–058), live connectors (061, 063, 065, 068), onboarding wizard (074), mobile (076–078), retention job (082), ISO/SOC 2 (086).

**P3:** 042 SCIM, 054 BI extract, 064 VNeID, 067 job boards. **Removed:** 027.

## 9. Open questions, risks and pending decisions

- BRD Q2/Q3 are mostly settled by ADR-0020 (shared + RLS) and ADR-0012/0032 (PG reporting store). Q4 (CTV/collaborators not on payroll: billable or not) is **open**; US-SAAS-011 is `[high-uncertainty]`. Q5 (PDPL decree vs Decree 13 deadlines), Q6, Q7, Q8, Q10 (90-day retention acceptance) and Q11 are open.
- R2-12(b) was not answered, so the D-10 per-LE double count stays. R4-01..R4-07 are open: replanning, and R4-04 proposes deferring the reporting store.
- Decision-log §3 legal/DPO review: D-04 retention periods, D-05 the 35-day backup statement, 2026 SI/PIT values (US-SAAS-036 initial pack).
- Spikes: SP-2 (RLS + PgBouncer), SP-8 (Keycloak e-mail OTP), replication of the REPLICATED tables (DESIGN §3.1).

## Known conflicts in the sources (report them; don't silently pick one)

1. **Tenant states.** BR-SAAS-001 and US-SAAS-001 use Trial / Pending Provisioning / Active / Suspended-ReadOnly / Suspended-Locked / Expired / Terminated-Retention / Deleted. DDL `tenant_state_ck` has PROVISIONING, TRIAL, ACTIVE, SUSPENDED, TERMINATING, TERMINATED, PURGED: a single SUSPENDED, and no EXPIRED. ADR-0011 uses `SUSPENDED_LOCKED`. The meaning of TERMINATING vs TERMINATED is unclear (DESIGN §3.7).
2. **Opening-balance lock.** BRD 088 and US-SAAS-067 lock **per LE on approval of the first regular run**, with adjustment batches afterwards. ADR-0037 §7 and DESIGN §13.3 allow rollback **until the tenant's first run is calculated**, then ordinary manual adjustments in a run. No DDL table holds the cut-over plan or lock.
3. **Sandbox rehearsal.** ADR-0037 §9 and DESIGN §13.3 rehearse in a sandbox tenant, but BR-SAAS-031 (sandbox) is P2 and BRD P2 step 4 says validate-only replaces it.
4. **Reporting store.** BR-SAAS-047 is P2 (mvp-scope), but ADR-0012 Amendment 1, D4 and `007_publications.sql` build the "Phase-1 reporting feed", and D6 routes reports through the store. R4-04 (open) proposes deferring it. Landing layer: ADR-0032 says `rpt_raw`; `100_rpt.sql` uses source-named landing schemas with `rpt_raw` as views.
5. **Platform audit table name.** BRD §6, BR-SAAS-085 and US-SAAS-020 say `saas.audit_event`; the DDL is `saas.platform_audit_event`.
6. **Operator log retention.** BRD P8 keeps operator-action audit as vendor records; DESIGN §3.7 purges `saas.operator_access_log` with the tenant.
7. **Billing party.** BR-SAAS-002 and US-SAAS-003 want a SaaS billing-party record; there is no `saas.billing_party` in DDL, and the identity is in `core.legal_entity_version.einvoice_identity`.
8. **LPS key format.** `insurance.multi_contract_rule` (BRD PR-06, R3-03) is lowercase, but `legal_param_definition_key_ck` requires uppercase (`SI.EE.RATE` style).
9. **Stale BRD phase notes.** BR-SAAS-040 says "MVP" (now P2, R2-04). 065 says P1 has one certified connector (now P2, R2-01). 073 says "Phase 2" (now P1, R2-03). §1.4's P1 list still includes e-invoicing and a device connector (R3-01, R2-01). NFR-SAAS-015 (P1) relies on BR-SAAS-009 (P2).
10. **Override scope.** US-SAAS-034 offers tenant or LE scope only; DDL and DESIGN add LOCATION (M3).
11. **Backlog gap.** `saas-platform-backlog.md` §0 puts the LPS runtime (`get`/`resolveSet`, caching, events) in technical-enablers, and `technical-enablers-backlog.md` §0 puts it in the SaaS backlog. Neither estimates it. The enablers file also lists EP-TECH-06 (job queue runtime) with no US-TECH stories (the file stops at US-TECH-040).

## 10. Where to look

- BRD `docs/01-requirements/brd/modules/06-saas-platform.md`:
  - §3 processes P1–P12
  - §4.A–4.I BRs
  - §5 compliance
  - §6 entities
  - §7 PR/CO interfaces
  - §9 NFR
  - §10 open questions and risks
- Phase: `docs/01-requirements/brd/mvp-scope.md` §9.6, §3.4 (baseline), §11.
- Ownership and events: `docs/01-requirements/brd/gap-analysis.md` §5, §6.
- Decisions: `docs/00-project-management/decision-log.md` (X-07, D-09, D-10, R2-01..19, R3-01/03/05/08/09/11, D9 in §10).
- DDL:
  - `database/postgresql/ddl/001_saas.sql`
  - `000_extensions_roles.sql` §6a (`sys.purge_bypass`)
  - `002_core.sql` §9b (`core.import_job`)
  - `007_publications.sql`
  - `reporting/100_rpt.sql`
- DESIGN: `database/postgresql/DESIGN.md` §3 (tenancy, roles, purge), §6 (legal packs), §7.1 (saas), §11 (reporting), §13 (opening balances).
- ADRs in `docs/02-architecture/adr/`:
  - 0003 tenancy, entitlement, baseline
  - 0004 bus
  - 0005 Keycloak
  - 0010 LPS API
  - 0011 scheduler
  - 0012 / 0032 reporting
  - 0020 / 0021 RLS and roles
  - 0025 pack model
  - 0026 keyring
  - 0036 placement and dedicated tier
  - 0037 opening balances
- Architecture: `docs/02-architecture/architecture-overview.md` §3 (dependency rules, module table).
- Backlog:
  - `docs/01-requirements/user-stories/saas-platform-backlog.md` (US-SAAS-001..075, EP-SAAS-01..10)
  - `technical-enablers-backlog.md` (US-TECH-010..040: tenancy, Keycloak, bus, isolation tests, support-grant context US-TECH-023, break-glass US-TECH-033)
- Backend: `backend/src/modules/saas`, package `vn.yourhrm.saas`, **not yet created**. Use `backend/src/modules/core` as the layout reference.
