# SaaS Platform: BR / NFR index

Source: `docs/01-requirements/brd/modules/06-saas-platform.md` v0.3 (§4, §9). Phase source: `docs/01-requirements/brd/mvp-scope.md` §9.6 (v0.3, round 3).

**Count: 113 IDs = 91 BR (BR-SAAS-001 … 091, of which 027 is Removed) + 22 NFR (NFR-SAAS-001 … 022).**
Phase totals for BRs: P1 = 42, P2 = 44, P3 = 4 (042, 054, 064, 067), Removed = 1 (027). NFR: P1 = 17, P2 = 5 (004, 011, 018, 019, 022).

How to read the table:
- MoSCoW is the BRD business priority. **Phase is authoritative for scope.** Where they differ, Phase wins.
- `B` means a baseline (always-on) capability (mvp-scope §3.4, R2-18).
- `⚠` means the BRD row's own note disagrees with mvp-scope. See "Known conflicts" in `SKILL.md`.
- Each rule is a one-line English summary. Read the BRD row before implementing it.

## 4.A Tenant lifecycle & commercial

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-001 | Tenant record with lifecycle states {Trial, Pending Provisioning, Active, Suspended-ReadOnly, Suspended-Locked, Expired, Terminated-Retention, Deleted}. Every transition is logged with actor, reason and time. The state drives login, job scheduling and billing. | M | P1 |
| BR-SAAS-002 | A tenant has 1..n legal entities sharing one subscription. Core owns legal-entity master data. SaaS keeps only the billing party (buyer MST, name, address, e-invoice identity) and the plan's LE limit. Provisioning creates the first LE through the Core API. | M | P1 |
| BR-SAAS-003 | An operator provisions a fully working shared-tier tenant from the back-office in ≤ 10 min, including reference data and the current legal pack. | M | P1 |
| BR-SAAS-004 | Self-service trial sign-up with e-mail and phone OTP, MST format and duplicate check. The trial is ready in ≤ 2 min with optional sample data. | S | P2 |
| BR-SAAS-005 | Trial length, edition and employee cap are configurable per campaign. On expiry the tenant becomes read-only, then is deleted after a configurable period (default 30 d), with warnings 7 d and 1 d before. | S | P2 |
| BR-SAAS-006 | Versioned plans/editions bundle modules, features, limits and support level. Existing tenants keep their plan version until migrated. | M | P2 |
| BR-SAAS-007 | Each module (`core` mandatory) and add-on can be subscribed independently per tenant with start/end dates, subject to the always-on baseline (091). | M | P1 |
| BR-SAAS-008 | A central entitlement service answers "is feature X on for tenant T (and LE E)?". UI, API and jobs all enforce it. A feature that is not entitled is hidden in the UI and the API returns HTTP 403 `FEATURE_NOT_ENTITLED`. | M | P1 |
| BR-SAAS-009 | Feature flags with global, plan, tenant, user-group and percentage scopes, plus a kill-switch. Flag changes are audited and take effect in ≤ 60 s without a redeploy. | M | P2 |
| BR-SAAS-010 | Pricing: PEPM per module, volume tiers, minimum fee, committed seats, annual prepay discount, promo codes, contract price overrides. Prices are in VND, excluding VAT. | M | P2 |
| BR-SAAS-011 | Plan changes are prorated by day. An upgrade takes effect immediately, a downgrade at period end. A downgrade never deletes data; affected data becomes read-only. | M | P2 |
| BR-SAAS-012 | Billable employee (D-10): a person with an active employment at an LE on the **last day of the month**, counted per LE and per module. A person in two LEs counts twice. Pre-hire, terminated and suspended persons do not count. For `payroll`, a person paid in that month's regular run at that LE also counts. A quarter or year is the sum of its months. | M | P1 |
| BR-SAAS-013 | Daily usage metering per tenant: billable count, active users, storage, API calls, SMS/Zalo messages and e-sign transactions. Visible to the owner and the operator. | M | P2 |
| BR-SAAS-014 | Subscription invoices per billing cycle, issued as VN e-invoices (hóa đơn điện tử) through a certified provider, with VAT. | M | P2 (R3-01) |
| BR-SAAS-015 | Invoice corrections only through an adjusting or replacing e-invoice. An issued e-invoice is never edited or deleted. `[verify-legal]` | M | P2 (R3-01) |
| BR-SAAS-016 | Payment by bank transfer with auto-reconciliation, and at least one domestic gateway (card, QR/VietQR). | S | P2 |
| BR-SAAS-017 | Configurable dunning: reminder → warning → read-only → locked. Suspension never blocks: an employee's own payslip and PIT certificate, the owner's data export, or DSR processing. | M | P2 |
| BR-SAAS-018 | The owner can request offboarding. A full export (CSV/JSON + data dictionary + documents) is delivered within 72 h. | M | P2 |
| BR-SAAS-019 | After retention, purge all tenant data from every store (DB, replicas, reporting, search, object storage, caches, logs with PII) and issue a deletion certificate. Backups expire within backup retention. A legal hold can pause the purge. | M | P2 |
| BR-SAAS-020 | Tenant ownership transfer needs the current owner's approval, or vendor-verified proof of legal authority. | S | P2 |

## 4.B Tenancy, isolation, residency & customisation

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-021 | Every business row in `core`, `hr`, `att`, `pay`, `rec` carries `tenant_id`. Isolation is enforced by PostgreSQL RLS **and** in the application layer. | M | P1 |
| BR-SAAS-022 | Two tiers: Shared (RLS) and Dedicated (own DB/cluster, same code and release cycle). A tier move is a tested operator procedure with ≤ 4 h downtime. | S | P2 |
| BR-SAAS-023 | Automated cross-tenant isolation tests run on every release. Any leak through API, report, export, search, file URL or cache key fails the release. | M | P1 |
| BR-SAAS-024 | All tenant data (primary, replica, backup, reporting store, files, logs with PII) is stored and processed in Vietnam. A non-VN service gets no PII, or needs a cross-border transfer impact assessment. `[verify-legal]` | M | P1 |
| BR-SAAS-025 | Tenant settings: default language (vi/en), time zone (Asia/Ho_Chi_Minh), date and number formats, currency (VND), fiscal year, week start, logo. Users can override the language. | M | P1 |
| BR-SAAS-026 | UI, notifications, standard reports, payslips and system e-mails are in vi and en. All strings are externalised. Vietnamese diacritics render correctly in UI/PDF/Excel. Sorting follows Vietnamese rules and search is accent-insensitive. | M | P1 |
| BR-SAAS-027 | **Removed.** Custom fields moved to Core (BR-CORE-086, P2). The ID is kept and never reused. | M | Removed |
| BR-SAAS-028 | No-code customisation of standard forms and document templates, versioned and revertible. | S | P2 |
| BR-SAAS-029 | White-label: logo, colour theme, product name, verified sender domain (SPF/DKIM). | C | P2 |
| BR-SAAS-030 | Custom domain (`hr.customer.vn`) with a platform-managed TLS certificate. Default URL is `<slug>.yourhrm.vn`. | C | P2 |
| BR-SAAS-031 | Sandbox tenant (a copy of production, optional PII masking). It does not count toward billable employees. | S | P2 |

## 4.C Operator back-office & support access

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-032 | Separate operator back-office: own auth realm, MFA, IP allow-list. It manages tenants, plans, billing, provisioning, flags, legal packs, platform health and the **global master-data release workflow**, which loads a versioned release into Core's store through the Core admin API. Core then emits `core.MasterDataReleased`. | M | P1 |
| BR-SAAS-033 | Operators have **no default access** to tenant business data. Access needs a time-boxed (≤ 72 h) support grant approved by the Tenant Admin or Owner, scoped by module and READ or READ_WRITE. | M | P1 |
| BR-SAAS-034 | In a support session, sensitive fields (salary amounts, bank account, national ID/CCCD, personal MST, health/SI medical data, dependants) are masked unless the grant explicitly includes "sensitive data". | M | P1 |
| BR-SAAS-035 | Impersonation only inside an active grant. It shows a persistent banner and is audited under both identities. It blocks workflow approvals, password/MFA changes, payroll release/payment and document signing. | M | P2 |
| BR-SAAS-036 | Break-glass for platform-wide incidents: two vendor approvers, 4 h limit, affected tenants notified within 24 h. | M | P2 (manual procedure in P1) |
| BR-SAAS-037 | The Tenant Admin can view and export a log of every operator access: who, when, why, ticket, and what was viewed or changed. | M | P1 (minimal, R3-08) |

## 4.D Identity & authentication

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-038 | Local authentication with a per-tenant password policy: min length (default 10, floor 8), complexity, history (default 5), optional max age, lockout (default 5 attempts / 15 min), breached-password check. | M | P1 `B` |
| BR-SAAS-039 | MFA by TOTP and e-mail OTP (SMS optional and paid). It can be enforced per tenant or role, and is mandatory for Owner, Tenant Admin, payroll approvers and all operators. | M | P1 `B` |
| BR-SAAS-040 | SSO with Google Workspace and Microsoft Entra ID over OIDC. | M | P2 ⚠ (R2-04) |
| BR-SAAS-041 | Generic SAML 2.0 / OIDC SSO per tenant, with claim mapping, JIT linking and an optional "SSO-only" setting per user group. | S | P2 |
| BR-SAAS-042 | SCIM 2.0 provisioning (create/disable users, suggest group → role mapping; role assignment stays in Core). | C | P3 |
| BR-SAAS-043 | Session management: idle timeout (default 30 min web), absolute lifetime, concurrent-session cap, "log out everywhere", admin force-logout. | M | P1 `B` (web; mobile P2) |
| BR-SAAS-044 | One person can hold users in several tenants, with a tenant switcher. Identities are never merged across tenants without explicit user linking. | S | P2 |
| BR-SAAS-045 | ESS login by employee code, work e-mail or mobile number, with first-time activation by OTP and no shared default password. **P1: the activation OTP goes by e-mail only**, so an employee without e-mail gets no ESS and receives a printed PDF payslip (R3-05). | M | P1 |

## 4.E Reporting & analytics

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-046 | One reporting framework for all modules: catalogue, parameters, row-level security from Core's scope predicate, field masking, export to xlsx/PDF/CSV, and **list-screen export** in every module (formerly BR-CORE-113). | M | P1 `B` |
| BR-SAAS-047 | High-volume reports run on a separate reporting store (replica or analytics DB) with ≤ 15 min lag and an "as of" timestamp. | M | P2 ⚠ |
| BR-SAAS-048 | Scheduled reports (daily, weekly, monthly, on payroll close) delivered to the in-app inbox, e-mail (password-protected attachment if sensitive) or SFTP. | S | P2 |
| BR-SAAS-049 | Self-service report designer on a governed semantic layer. | S | P2 |
| BR-SAAS-050 | Role-based dashboards with KPI tiles and drill-down. | S | P2 |
| BR-SAAS-051 | HR analytics pack (headcount/FTE, turnover, absence, OT vs legal cap, labour cost, …). | S | P2 |
| BR-SAAS-052 | Statutory reporting framework: versioned templates (layout + mapping) updated through the P10 legal-change process, independent of app releases, in the official output format (XML/Excel/PDF). Template content is owned by the module BRDs. | M | P1 |
| BR-SAAS-053 | Large exports (> 10k rows or > 30 s) run async with a notification. Files expire after 7 d. Every export is audited. | M | P2 (P1 exports are synchronous) |
| BR-SAAS-054 | Governed incremental data extract into the customer's own BI (Enterprise). | C | P3 |

## 4.F Integrations

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-055 | Public REST API (versioned, OpenAPI 3, paging, idempotency key on POST, standard error model) over the core HR resources. Internal APIs follow the same standard from MVP. | S | P2 |
| BR-SAAS-056 | API keys and OAuth client-credentials clients per tenant: scoped, expiring, revocable; the secret is shown once. | S | P2 |
| BR-SAAS-057 | API rate limits per tenant and client by plan, returning HTTP 429 with retry-after. | S | P2 |
| BR-SAAS-058 | Webhooks: HMAC-signed payloads, exponential retry ≥ 24 h, dead-letter screen, replay. | S | P2 |
| BR-SAAS-059 | Accounting: payroll journal export (GL account, cost centre, LE, debit/credit) with configurable mapping for MISA, Fast and SAP. **P1 is a file export**; API push is P2. | M | P1 (file only) |
| BR-SAAS-060 | Bank payment files for major VN banks (VCB, BIDV, VietinBank, TCB, MB, ACB). Host-to-host API is P2. | M | P1 (file only) |
| BR-SAAS-061 | VSS/BHXH: declaration file in an I-VAN-accepted format, and submission through the I-VAN API. P1 file generation is BR-PAY-047. `[verify-legal]` | S | P2 |
| BR-SAAS-062 | eTax: PIT withholding returns in HTKK/eTax XML, plus the e-PIT withholding certificate. **P1: certificate data + an unsigned PDF**; the customer signs and issues it (R3-02). `[verify-legal]` | M | P1 (file only) |
| BR-SAAS-063 | E-signature / e-labour-contract connector (signing orchestration is Core BR-CORE-103). `[verify-legal]` | S | P2 |
| BR-SAAS-064 | VNeID identity verification with employee consent, and optional ESS login. `[verify-legal]` | C | P3 |
| BR-SAAS-065 | Time-clock ingestion (ZKTeco, Hikvision, Suprema) by cloud push or on-prem agent, with offline buffering. The consumer is T&A. | M | P2 ⚠ (R2-01) |
| BR-SAAS-066 | Messaging channels: tenant-branded e-mail, SMS brandname, Zalo OA/ZNS, push. **P1 = e-mail only.** | M | P1 (e-mail only) |
| BR-SAAS-067 | Job-board posting and candidate import (TopCV, VietnamWorks, CareerViet, LinkedIn). The consumer is Recruitment. | C | P3 |
| BR-SAAS-068 | Single integration hub: per-tenant connector config, encrypted credential vault, execution log with PII masked, retries, failure alerts. | M | P2 |

## 4.G Opening-balance import & onboarding (also hosts 088–091)

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-069 | Downloadable import templates (Excel, vi/en headers, embedded code lists and instructions) for master data and opening balances. Each template is an import type registered on **Core's import engine** and owned by the data-owner module. SaaS publishes the template catalogue and runs the P9 onboarding. | M | P1 |
| BR-SAAS-070 | Requirements on Core's import engine: validate-only first, row/column errors in vi/en, partial-commit policy (default all-or-nothing), idempotent upsert on business key, reversible (rollback within 24 h if no dependent transactions). | M | P1 |
| BR-SAAS-071 | Mid-year go-live opening-balance load from the 069 templates via Core's OPENING_BALANCE mode (bulk, bypasses workflow, keeps external reference ids), in P9 dependency order. No legacy DB access, ETL or staging area. Tenant-specific columns must map to a standard field or an attachment during validation, and unmapped columns block commit (R3-09). | M | P1 |
| BR-SAAS-072 | Opening-balance reconciliation against tenant-supplied control totals (last legacy payroll, VSS C12-TS, filed 05/KK, leave report), per employee and in total. Go-live needs zero unexplained differences plus sign-off by the tenant's HR/C&B and finance owners. | M | P1 |
| BR-SAAS-073 | Optional one-month payroll comparison: import the last legacy payroll, recalculate that month in YourHRM, and report differences per employee and component. Replaces parallel run. | S | P1 ⚠ (R2-03) |
| BR-SAAS-074 | Onboarding wizard with a go-live checklist and % progress. | S | P2 |
| BR-SAAS-088 | Go-live cut-over and opening-balance lock. Each LE has a go-live month, and balances are "as at end of the previous month". Approval of the LE's **first regular payroll run** locks them. After the lock, changes only go through an audited **adjustment batch** with a reason, consumed by Payroll and T&A as retro/adjustment input. | M | P1 |
| BR-SAAS-089 | For go-live after January: per employee, LE and month of the tax year before go-live, import taxable income, exempt income, compulsory insurance deducted, family deduction applied and PIT withheld. A missing month blocks that LE's go-live. | M | P1 |
| BR-SAAS-090 | Tenant-aware job scheduler and queue for all modules: scheduled and on-demand jobs, per-tenant quotas (NFR-SAAS-005), pause or throttle by tenant state, retries with an idempotency key, progress/status API. | M | P1 |
| BR-SAAS-091 | Always-on baseline entitlements, whatever is subscribed: Core; People basic; legal parameters (incl. national holiday template); pay scales/bands and component catalogue; Excel attendance import producing a standard Attendance Result. These are not billed as modules. The dependency matrix is published in the plan catalogue. | M | P1 `B` |

## 4.H Mobile / ESS channel

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-075 | Responsive web ESS for profile (with approval), payslips and PIT certificates, leave, attendance view and correction requests, manager approval inbox, documents, notifications and consent management. No check-in from ESS in P1. | M | P1 |
| BR-SAAS-076 | Native mobile app (iOS/Android, one multi-tenant app) with GPS/Wi-Fi/QR geofenced check-in, selfie, push and biometric unlock. | S | P2 |
| BR-SAAS-077 | Payslips on mobile require re-authentication and are never cached unencrypted. Screenshot deterrent (optional FLAG_SECURE). | S | P2 |
| BR-SAAS-078 | Offline mobile check-in with trusted device time, synced later and flagged as offline. | S | P2 |

## 4.I Compliance, privacy & security

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| BR-SAAS-079 | One personal-data inventory (data map) across modules. For each entity/field: class (basic/sensitive), purpose, legal basis, retention, owner module, processors. Modules register their fields, and it can be exported for DPIA. `[verify-legal]` | M | P1 |
| BR-SAAS-080 | One consent registry for employees, pre-hires, ex-employees and candidates (People and Rec keep references only). Versioned notice per purpose; consent recorded with time, channel and version; withdrawal any time in ESS; a check API for modules before processing. | M | P1 |
| BR-SAAS-081 | DSR tooling: request log, ID verification step, deadline tracking with reminders, one-click cross-module export, erase/anonymise subject to statutory retention, evidence of completion. Deadlines are `[verify-legal]`. | M | P1 |
| BR-SAAS-082 | Single retention scheduler: configurable per data category, with statutory minimums enforced as floors, automated purge/anonymise jobs with reports, and unhired candidates deleted after a set period unless they consented. | M | P2 |
| BR-SAAS-083 | Vendor DPIA (as processor), plus a DPIA template and platform inputs for each tenant's own DPIA. `[verify-legal]` | M | P1 |
| BR-SAAS-084 | Breach notification: affected tenants ≤ 24 h after confirmation, authority within **72 h** where required; keep templates and evidence. `[verify-legal]` | M | P1 |
| BR-SAAS-085 | SaaS **writes** its security events (login, MFA, SSO config, API keys) and operator access to **Core's audit store**. Only platform-level operator events with no tenant stay in SaaS's own platform audit table. Retention floors are set in Core. | M | P1 |
| BR-SAAS-086 | ISMS aligned with ISO/IEC 27001:2022 and SOC 2 Type II mappable controls, with certification within 18 months of GA. | S | P2 |
| BR-SAAS-087 | Achieve and maintain the VN information-system security level classification (Decree 85/2016). `[verify-legal]` | S | P1 (promoted: statutory, no build) |

## 9. NFR

| ID | Rule (summary) | MoSCoW | Phase |
|---|---|---|---|
| NFR-SAAS-001 | Availability ≥ 99.5 % (Starter/Pro), ≥ 99.9 % (Enterprise/dedicated). Maintenance ≤ 4 h/month in the 22:00–05:00 ICT window, never on payroll days 25–5 without consent. | M | P1 |
| NFR-SAAS-002 | Payroll run: 10k employees (~60 components) ≤ 15 min; 1k ≤ 2 min; single recalculation ≤ 5 s. | M | P1 |
| NFR-SAAS-003 | p95 ≤ 500 ms for transactional screens/APIs; ≤ 3 s for standard reports up to 5k rows. | M | P1 |
| NFR-SAAS-004 | Check-in burst 2,000/min per tenant and 20,000/min platform-wide, p95 ack ≤ 1 s. | M | P2 |
| NFR-SAAS-005 | Shared tier ≥ 500 tenants / ≥ 200k employees at launch, 5× horizontal headroom. A noisy tenant gets ≤ 20 % of pool capacity (quotas on jobs, reports, API). | M | P1 |
| NFR-SAAS-006 | RPO ≤ 15 min (PITR), RTO ≤ 4 h shared / ≤ 2 h Enterprise. Encrypted backups kept 35 d at a second VN DC, quarterly restore test, per-tenant PITR into a sandbox. | M | P1 |
| NFR-SAAS-007 | TLS 1.2+ including internal traffic; AES-256 at rest; app-level encryption for bank account, CCCD, personal MST, SI number and health data. Salary amounts are **not** column-encrypted (X-01). Keys in KMS/HSM; per-tenant keys enable crypto-shredding. | M | P1 |
| NFR-SAAS-008 | No credentials in code or repo; vault with ≤ 90 d rotation. Customer data files (e.g. opening-balance templates) are never committed. | M | P1 |
| NFR-SAAS-009 | OWASP ASVS L2; SAST/DAST/dependency scans in CI that block on critical findings; annual pentest; fix SLA 7 d critical / 30 d high. | M | P1 |
| NFR-SAAS-010 | Centralised logs (PII masked), metrics and traces tagged with tenant_id and correlation id. Logs kept ≥ 12 months (security logs ≥ 24) in VN. `[verify-legal]` | M | P1 |
| NFR-SAAS-011 | WCAG 2.1 AA for ESS and core HR flows. | S | P2 |
| NFR-SAAS-012 | Last 2 versions of Chrome/Edge/Safari/Firefox; responsive down to 360 px (mobile OS versions are P2). | M | P1 |
| NFR-SAAS-013 | Full vi/en localisation, no mixed-language UI, correct Vietnamese fonts in PDF/Excel. | M | P1 |
| NFR-SAAS-014 | Zero-downtime releases (rolling/blue-green, expand/contract migrations), cadence ≥ every 2 weeks, rollback ≤ 15 min. | M | P1 |
| NFR-SAAS-015 | vi/en release notes ≥ 3 working days ahead. Calculation-affecting changes sit behind per-tenant toggles until opt-in or the announced date. Pilot ring first. | M | P1 ⚠ (uses BR-SAAS-009, which is P2) |
| NFR-SAAS-016 | Legal-change SLA: a published, tested pack ≥ 7 calendar days before the effective date when the law is issued ≥ 30 d ahead, otherwise within 10 working days of issue, with retro recalculation support. | M | P1 |
| NFR-SAAS-017 | Every payroll/attendance result stores the legal pack version, config version and code release version, so it is reproducible. | M | P1 |
| NFR-SAAS-018 | Public API backward compatible within a major version, ≥ 6 months deprecation notice, p95 ≤ 300 ms for a single GET. | S | P2 |
| NFR-SAAS-019 | Documented, stable full-tenant export format; a 10k-employee tenant exports in ≤ 6 h. | M | P2 |
| NFR-SAAS-020 | Sev-1 response ≤ 1 h 24×7 (Enterprise) or in business hours (others); support in vi and en. | M | P1 |
| NFR-SAAS-021 | Peak capacity at 3× average for payroll close (days 25–5) and PIT finalisation (Jan–Mar), with a load test before each peak. | M | P1 |
| NFR-SAAS-022 | Mobile app: cold start ≤ 3 s on a mid-range Android, check-in ≤ 2 s on 4G, app ≤ 60 MB. | S | P2 |
