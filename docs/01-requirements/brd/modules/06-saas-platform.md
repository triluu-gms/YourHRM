# BRD — SaaS Platform & Cross-cutting Capabilities (`SAAS`)

| Item | Value |
|---|---|
| Document | `docs/01-requirements/brd/modules/06-saas-platform.md` |
| Version | Draft v0.2 — 2026-10-16 |
| Change note | v0.2 (2026-10-16), following sponsor decisions D-09, D-10, OD-9 and X-04: (1) the legacy Oracle migration and parallel run are replaced by a generic **opening-balance import** (P9, BR-SAAS-069/071/072/073 changed, BR-SAAS-088/089 added); (2) billable employee = active person at month end per legal entity (BR-SAAS-012, P4); (3) no mobile app, push or check-in in Phase 1 (§1.4, BR-SAAS-066/075/076); (4) the per-requirement phase is now set in [mvp-scope.md](../mvp-scope.md), which overrides the MoSCoW → phase mapping. |
| Owner | BA – SaaS Platform & cross-cutting |
| Requirement prefixes | `BR-SAAS-NNN` (business), `NFR-SAAS-NNN` (non-functional) |
| Related | Project charter; module BRDs `01-core` … `05-recruitment`; `gap-analysis.md`; `architecture-overview.md` |
| Status | For review by PM, Solution architect, Data architect, Security lead, VN legal/tax consultant |

> Conventions (charter §4): priority is MoSCoW, `M` = needed for MVP / first paying tenant. Any legal figure, rate, deadline or decree reference is marked `[verify-legal]` until a Vietnam legal/tax consultant confirms it. Legal values are effective-dated parameters, never hard-coded.

---

## 1. Purpose & scope

### 1.1 Purpose
Define the business requirements for everything that makes YourHRM a **commercial, multi-tenant SaaS product** rather than a single-customer HR system, plus the cross-cutting capabilities (identity, reporting, integration, opening-balance import, ESS channel, compliance, product-wide NFRs) that no single HR module (`core`, `people`, `time-attendance`, `payroll`, `recruitment`) owns.

### 1.2 In scope
| # | Area | Summary |
|---|---|---|
| A | Tenant lifecycle & commercial | Sign-up / trial, provisioning, plans & editions, feature flags, module subscription, per-employee pricing, usage metering, subscription billing with VN e-invoices, dunning, suspension, offboarding (export + deletion) |
| B | Tenancy, isolation & residency | Shared vs. dedicated tiers, tenant isolation guarantees, data residency in Vietnam, tenant configuration & customisation (custom fields, forms, localisation vi/en), white-labelling, custom domain |
| C | Operator back-office | Platform admin console, support access with tenant consent, impersonation rules, operator audit |
| D | Identity & access (authentication) | Local login, password policy, MFA, SSO (SAML 2.0 / OIDC, Google Workspace, Microsoft Entra ID / M365), SCIM-style provisioning, session management |
| E | Reporting & analytics | Standard report catalogue, report designer, scheduled reports, dashboards, Excel/PDF export, HR analytics, statutory report framework, OLTP / reporting separation |
| F | Integration | Public REST API, webhooks, API keys / OAuth clients, accounting (MISA, Fast, SAP), e-signature & e-labour contract, VNeID, VSS/BHXH, eTax, banks, time devices, Zalo / email / SMS |
| G | Opening-balance import & onboarding | Import templates, generic opening-balance import for a mid-year go-live (employee master, YTD income/PIT/SI, SI contribution history, leave balances, loans), validation, reconciliation against customer control totals. No legacy data migration (changed 2026-10-16, D-09) |
| H | Mobile / ESS channel | Responsive ESS web (Phase 1); employee mobile app (iOS/Android) incl. all check-in (Phase 2) (changed 2026-10-16, OD-9) |
| I | Compliance & security | PDP Law 2025 / Decree 13/2023, consent, data subject requests, DPIA, breach notification, ISO 27001 / SOC 2 readiness, encryption, backup, audit, retention |
| J | Product-wide NFRs | Availability SLA, performance, scalability, accessibility, observability, release management, per-tenant toggles, regulatory change management |

### 1.3 Out of scope
- HR business rules themselves (payroll formulas, leave accrual, shift rules, recruitment pipeline) — owned by module BRDs `02`–`05`.
- Authorisation model inside a tenant (roles, permissions, data scopes by org unit), workflow engine, in-app notification centre, tenant-level audit log storage — owned by `core` (`01-core.md`). The platform supplies **authentication**, **tenant context** and **entitlements**; `core` supplies **authorisation**.
- Customer's own accounting / ERP processes beyond the export/integration contract.
- Marketplace of third-party apps, AI assistants, regional (non-VN) expansion — Phase 3 per charter.
- Hardware supply of time-clock devices.

### 1.4 Phasing summary
| Phase | Platform capabilities |
|---|---|
| MVP (Phase 1) | Assisted tenant onboarding (operator-provisioned), module subscription & entitlements, contract-price billing with VN e-invoice (month-end billable count per legal entity), shared tier with RLS isolation, VN data residency, vi/en, password + MFA, standard reports + Excel/PDF export, statutory reports for SI/PIT, import templates and **opening-balance import** with reconciliation, file-based bank/GL/eTax/SI outputs, one certified time-device connector + Excel punch import, responsive ESS web, e-mail notifications, PDP compliance baseline, backup/DR. **No mobile app, push or mobile/GPS/selfie/web check-in** (changed 2026-10-16, D-09/OD-9/X-04) |
| Phase 2 | Self-service sign-up & trial, plans/editions & price books, usage-metered billing automation, dunning, tenant offboarding export & deletion, dedicated tier, sandbox, OIDC/SAML SSO, custom fields, report designer & dashboards, HR analytics, separate reporting store, integration hub, public API + webhooks, accounting/bank/VSS/eTax API connectors, e-signature, native mobile app with all check-in modes and push, white-label & custom domain |
| Phase 3 | Marketplace integrations, AI assistants, region expansion |

---

## 2. Actors & personas

| Actor | Type | Description / goals |
|---|---|---|
| Prospect / Trial user | External | Evaluates YourHRM; wants a working trial with sample data in minutes |
| Tenant Owner (Chủ tài khoản) | Tenant | Signs the subscription, owns billing, can transfer ownership, approves support access and offboarding |
| Tenant Admin (Quản trị hệ thống khách hàng) | Tenant | Configures company, modules, SSO, custom fields, integrations, API clients |
| HR / C&B user | Tenant | Runs reports, imports data, consumes statutory outputs |
| Finance / Accountant | Tenant | Receives subscription invoices; receives payroll journals in MISA/Fast/SAP |
| Employee (ESS user) | Tenant | Uses responsive web ESS: payslip, leave, attendance view & corrections, profile, consent. Mobile app and check-in are Phase 2 (changed 2026-10-16, OD-9) |
| DPO / Privacy officer (Người phụ trách bảo vệ DLCN) | Tenant | Handles data subject requests, consent records, DPIA inputs, breach notices for the tenant (controller) |
| Platform Operator – Support (L1/L2) | Vendor | Diagnoses tenant issues under consent; never sees data without a grant |
| Platform Operator – Onboarding consultant | Vendor | Provisions tenants, guides the customer through the opening-balance templates, runs validation and reconciliation (changed 2026-10-16, D-09) |
| Platform Operator – Billing / Finance | Vendor | Manages plans, prices, invoices, dunning |
| Platform Administrator / SRE | Vendor | Runs infrastructure, releases, backups, incident response |
| Vendor Security & Compliance officer | Vendor | ISO 27001 / SOC 2 programme, DPIA as processor, breach notification to authorities |
| Regulatory content owner (Legal/Payroll SME) | Vendor | Maintains legal parameter packs (rates, caps, minimum wage, PIT brackets) for all tenants |
| External systems | System | IdPs, accounting software, banks, VSS, eTax (GDT), VNeID, e-signature CA, e-invoice provider, time devices, Zalo OA / SMS / email gateways |
| Integration partner / developer | External | Builds against public API and webhooks |

---

## 3. Business processes

### P1. Sign-up, trial and conversion
1. Prospect submits sign-up form (company name, tax code / MST, contact, employee band, preferred language).
2. Platform validates email (OTP link) and phone (OTP), checks MST format and duplicates.
3. Platform provisions a **trial tenant** on the shared tier with sample data and all modules of the chosen edition, valid for a configurable trial period (default 14 days).
4. Reminder emails at T-7, T-3, T-1; Tenant Owner selects plan, modules and billing cycle; accepts ToS, DPA (data processing agreement) and privacy notice.
5. On first payment (or approved invoice terms) the tenant becomes **Active**; sample data is purged on request.
- *Exceptions:* duplicate MST → route to sales (possible existing customer); trial expired without conversion → **Expired** (read-only 30 days, then deletion per P8); enterprise prospect (> 1,000 employees or dedicated tier) → assisted path P2.

### P2. Assisted provisioning (enterprise / pilot)
1. Sales closes contract; operator creates tenant in back-office: legal entities, edition, modules, tier (shared / dedicated), region (VN), contract dates, seat commitment.
2. Platform provisions infrastructure (shared: tenant record + RLS keys; dedicated: database / schema set) via automated pipeline, seeds reference data and the current legal parameter pack.
3. Tenant Owner receives activation link; sets up MFA; configures SSO (optional).
4. Opening-balance import (P9) runs in **validate-only** mode until it is clean, then it is committed to the production tenant before the first payroll run (changed 2026-10-16, D-09; sandbox tenant is Phase 2).
- *Exceptions:* provisioning step fails → automatic rollback and operator alert; dedicated tier capacity not available → status **Pending Provisioning** with SLA clock.

### P3. Subscription change (upgrade / downgrade / add-on module)
1. Tenant Owner selects change; platform shows prorated price.
2. Upgrade / add-on: entitlements effective immediately; prorated charge on next invoice.
3. Downgrade / module removal: effective at end of current billing period; platform warns about data that becomes read-only (data is never deleted by a downgrade).

### P4. Usage metering and billing cycle
1. At each month end, the platform takes a snapshot of **billable employees**, meaning the persons active at month end, per legal entity and per module (BR-SAAS-012). Daily snapshots are kept for information only (changed 2026-10-16, D-10).
2. At period close, the platform computes the charge as max(committed seats, month-end billable employees summed over legal entities) × unit price per module, plus add-ons, minus discounts (changed 2026-10-16, D-10).
3. Billing operator reviews; platform issues VN e-invoice through the e-invoice provider, sends to Tenant Owner / Finance contact, records payment status.
4. Payment received (bank transfer reconciliation / payment gateway) → invoice Paid.
- *Exceptions:* invoice error → adjustment or replacement e-invoice per Decree 123/2020 as amended by Decree 70/2025 `[verify-legal]`; non-payment → dunning P5.

### P5. Dunning, suspension and reactivation
1. Overdue D+1: reminder; D+15: warning banner to admins; D+30: **Suspended – read-only** (logins allowed, no new payroll runs, exports allowed); D+60: **Suspended – locked** (only Tenant Owner can log in to pay/export). Days are configurable per contract.
2. Payment → reactivation within 15 minutes, automatic.
- *Exceptions:* suspension never blocks: (a) employees downloading their own payslips/PIT certificates, (b) data export by Tenant Owner, (c) DSR handling.

### P6. Support access and impersonation
1. Support agent opens ticket-linked **access request** (scope: read-only / read-write; modules; duration ≤ 72h; reason).
2. Tenant Admin/Owner approves in-app (or a pre-approved standing grant for named severity-1 incidents per contract).
3. Agent accesses tenant via "support session" — banner shown, all actions audited, sensitive fields (salary, bank account, national ID, health) masked unless the grant explicitly includes them.
4. Grant expires automatically; tenant receives session summary.
- *Exceptions:* break-glass for platform-wide incident → two-person approval by vendor, tenant notified within 24h.

### P7. SSO / MFA configuration
1. Tenant Admin chooses IdP (Google, Microsoft Entra ID, generic SAML 2.0 / OIDC), uploads metadata, maps claims → user (email / employee code).
2. Test login with a named admin; enforce SSO for selected user groups; keep ≥1 break-glass local admin with MFA.

### P8. Offboarding (contract end / termination)
1. Tenant Owner (or contract expiry) triggers offboarding; platform shows checklist and final invoice.
2. Platform produces a **full data export** (P8a) — structured (CSV/JSON + data dictionary) and documents (payslips, contracts, attachments) — downloadable for 30 days.
3. Tenant enters **Terminated – retention** state: no logins except Owner export access.
4. After retention window (default 90 days, contractable) platform **deletes** tenant data from primary storage, replicas, search indexes, reporting store and object storage; backups age out within backup retention (≤ 35 days) and are crypto-shredded where per-tenant keys exist.
5. Platform issues a **certificate of deletion** to the Tenant Owner.
- *Exceptions:* legal hold requested by tenant or authority → deletion paused with reason; data the vendor must keep as its own records (billing, invoices, contract, audit of operator actions) retained per accounting law `[verify-legal]`.

### P9. Opening-balance import (mid-year go-live, any tenant) (changed 2026-10-16, D-09)
YourHRM is built as a new system, so there is no legacy migration, staging area or parallel run. Each tenant, including the pilot, loads the data it needs to go live from standard Excel templates.
1. **Cut-over plan.** The operator and the tenant agree a go-live month per legal entity. Opening balances are stated "as at the end of the month before go-live" (BR-SAAS-088).
2. **Templates.** The tenant downloads the templates (BR-SAAS-069) and fills them in, in this order:
   1. org units, positions and cost centres
   2. employee master (person, employment, assignment, contract, salary, bank, dependants)
   3. salary history needed for severance and average bases
   4. SI contribution history
   5. YTD income, insurance and PIT per month of the tax year (BR-SAAS-089)
   6. leave balances (per type, incl. carry-forward and expiry) and YTD OT hours
   7. outstanding loans and advances
3. **Validate.** Each file is uploaded in **validate-only** mode. Mandatory fields, code lists, referential integrity, duplicates (CCCD / tax code / SI number) and old→new addresses are checked, and errors are reported by row and column in vi/en. The tenant corrects and re-uploads until the file is clean.
4. **Commit.** Batches are committed in dependency order. Commits are idempotent (upsert by business key) and reversible until the opening balances are locked.
5. **Reconcile.** The reconciliation report (BR-SAAS-072) compares the imported values with **control totals supplied by the tenant**: last legacy payroll register, VSS C12-TS notice, PIT declarations already filed for the year (05/KK), and the leave report. The tenant's HR/C&B and finance owners sign off.
6. **Go-live.** The first payroll run in YourHRM uses the opening YTD. Approving it locks the opening balances for that legal entity (BR-SAAS-088). Later corrections are made only through audited adjustment batches.
- *Exceptions:*
  - Failed rows go to a downloadable error report. Partial commit is not allowed by default.
  - A missing pre-go-live month blocks go-live for the legal entity, because PIT finalisation needs the whole year.
  - Personal data in templates is uploaded only through the platform. It is never e-mailed and never stored in source control (README rule).

### P10. Regulatory change rollout
1. Regulatory content owner detects a new legal instrument (e.g., regional minimum wage, SI cap base, PIT family deduction).
2. Creates a **legal parameter pack** version with effective date, legal reference, and test cases; peer-reviewed + consultant sign-off.
3. Pack is published to staging; automated regression on reference payroll scenarios.
4. Published to all tenants with notice (in-app + email) ≥ 7 days before effective date where the law allows; tenants may preview impact (simulation on their own data).
5. Tenant-level overrides (e.g., company pays above statutory) remain intact; audit records which pack version each payroll run used.

### P11. Personal data breach handling
1. Detection (monitoring, report from tenant or employee) → incident ticket, severity triage within 1h.
2. Containment; assessment of affected tenants / data subjects / data categories.
3. Notify affected tenant controllers without undue delay (target ≤ 24h of confirmation) with facts to support their own notification.
4. Notify the competent authority (A05 – Ministry of Public Security cyber department) within 72h where the vendor is the responsible party `[verify-legal]`.
5. Post-incident review, corrective actions, evidence retained.

### P12. Data subject request (DSR)
1. Data subject (employee, candidate, former employee) submits request via ESS / public form, or tenant DPO logs it.
2. Tenant DPO verifies identity and decides (tenant is controller; platform is processor).
3. Platform tooling executes: access/export, rectification routing, consent withdrawal, restriction, deletion/anonymisation (respecting statutory retention), objection.
4. Response tracked against statutory deadline `[verify-legal]`; audit retained.

---

## 4. Business requirements

### 4.A Tenant lifecycle & commercial

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-001 | The platform shall maintain a **tenant** record with lifecycle state ∈ {Trial, Pending Provisioning, Active, Suspended-ReadOnly, Suspended-Locked, Expired, Terminated-Retention, Deleted} and log every state transition with actor, reason and timestamp. | M | State drives login, job scheduling, billing |
| BR-SAAS-002 | A tenant shall contain 1..n **legal entities** (pháp nhân), each with its own MST, address, SI unit code, and e-invoice/eTax identity, sharing one subscription. | M | Groups with subsidiaries; ties to `core` Company |
| BR-SAAS-003 | Operators shall provision a fully working tenant (shared tier) from the back-office in ≤ 10 minutes end-to-end, including reference data and current legal parameter pack. | M | Assisted path P2 |
| BR-SAAS-004 | Prospects shall self-sign-up for a trial with email + phone OTP verification, MST format validation and duplicate detection; trial tenant ready in ≤ 2 minutes with optional sample data. | S | Phase 2 |
| BR-SAAS-005 | Trial length, trial edition and trial employee cap shall be configurable per campaign; on expiry tenant becomes read-only, then deleted after a configurable period (default 30 days) with 7-day and 1-day warnings. | S | |
| BR-SAAS-006 | The platform shall define **plans/editions** (e.g., Starter, Professional, Enterprise) as versioned bundles of modules, features, limits (employees, legal entities, storage, API calls) and support levels. Existing tenants keep their plan version until migrated. | M | Plan catalogue owned by Billing operator |
| BR-SAAS-007 | Each module (`core` mandatory; `people`, `time-attendance`, `payroll`, `recruitment`) and add-on (mobile app, advanced analytics, API, dedicated tier, white-label) shall be independently subscribable per tenant, with start/end dates. | M | |
| BR-SAAS-008 | A central **entitlement service** shall answer "is feature X enabled for tenant T (and legal entity E) now?"; every module UI, API and background job shall enforce it. Un-entitled features are hidden in UI and return HTTP 403 with code `FEATURE_NOT_ENTITLED` on API. | M | Consumed by all modules (§7) |
| BR-SAAS-009 | **Feature flags** shall support scopes: global, plan, tenant, user-group, percentage rollout; with kill-switch; flag changes audited and effective ≤ 60 s without redeploy. | M | Also used for release mgmt (NFR-SAAS-017) |
| BR-SAAS-010 | Pricing shall support per-employee-per-month (PEPM) unit price per module, volume tiers, minimum monthly fee, committed seats, annual prepay discount, promotional coupons, and contract-specific price overrides. Prices in VND, exclusive of VAT. | M | |
| BR-SAAS-011 | Plan changes shall be prorated by day; upgrades effective immediately, downgrades at period end; downgrades never delete data (affected data becomes read-only). | M | |
| BR-SAAS-012 | **Billable employee** = a person with an active employment in a legal entity on the **last day of the billing month**. The count is taken per legal entity and per module, so a person employed by two legal entities of the same tenant counts twice. People with pre-hire, terminated or suspended status are not billable on that day, and neither are records kept for history. For `payroll`, a person paid in that month's regular run of the legal entity also counts. Quarterly and annual cycles bill the sum of the monthly counts. (changed 2026-10-16, D-10) | M | Needs month-end headcount feed per legal entity from `people` (CO-05). Treatment of collaborators/CTV not on payroll: Product to confirm |
| BR-SAAS-013 | The platform shall meter and store daily usage per tenant: billable employees per module, active users, storage GB, API calls, SMS/Zalo messages sent, e-signature transactions; viewable by Tenant Owner and operator. | M | Pass-through costs (SMS, e-sign) billable |
| BR-SAAS-014 | The platform shall generate subscription invoices per billing cycle (monthly / quarterly / annual) and issue **VN electronic invoices** (hóa đơn điện tử, with tax-authority code where required) via a certified e-invoice provider, including VAT at the effective rate. | M | Decree 123/2020/ND-CP as amended by Decree 70/2025/ND-CP; Circular 78/2021/TT-BTC `[verify-legal]` |
| BR-SAAS-015 | Invoice corrections shall use adjustment or replacement e-invoices per regulation; issued e-invoices are never edited or deleted. | M | `[verify-legal]` |
| BR-SAAS-016 | Payments shall be accepted by bank transfer (with automatic reconciliation via virtual account / transfer reference) and at least one domestic payment gateway (cards, QR/VietQR). | S | |
| BR-SAAS-017 | Dunning shall follow configurable steps (reminder → warning → read-only → locked) per P5; suspension shall never block employee access to own payslips/PIT certificates, Owner data export, or DSR handling. | M | |
| BR-SAAS-018 | Tenant Owner shall be able to request offboarding; the platform shall deliver a complete export (structured data in CSV/JSON with data dictionary + all documents) within 72h of request. | M | Also contract-end automatic |
| BR-SAAS-019 | After the retention window the platform shall delete all tenant data from all stores (DB, replicas, reporting store, search, object storage, caches, logs containing personal data) and issue a certificate of deletion; backups expire within backup retention. | M | Legal hold can pause |
| BR-SAAS-020 | Tenant ownership transfer shall require approval by current Owner (or vendor verification of legal authority documents if Owner unavailable). | S | |

### 4.B Tenancy, isolation, residency & customisation

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-021 | Every business row in `core`, `hr`, `att`, `pay`, `rec` shall carry a `tenant_id`; isolation shall be enforced in the database (PostgreSQL Row-Level Security) **and** in the application layer, so that a missing filter in code cannot return another tenant's data. | M | Defence in depth; ADR by Data architect |
| BR-SAAS-022 | The platform shall support two tiers: **Shared** (pooled DB, RLS) and **Dedicated** (separate database or cluster per tenant, same code base and release train). Moving a tenant between tiers shall be an operator-run, tested procedure with ≤ 4h downtime window. | S | Dedicated = Enterprise add-on, Phase 2 |
| BR-SAAS-023 | Automated **cross-tenant isolation tests** shall run on every release and fail the release if any API, report, export, search, file URL or cache key leaks data across tenants. | M | |
| BR-SAAS-024 | All tenant data (primary, replicas, backups, reporting store, files, logs with personal data) shall be stored and processed on infrastructure located **in Vietnam**. Any service outside VN (e.g., email delivery, monitoring SaaS) must receive no personal data or be covered by a cross-border transfer impact assessment. | M | Cybersecurity Law 2018 + Decree 53/2022/ND-CP Art. 26; PDP Law 2025 cross-border rules `[verify-legal]` |
| BR-SAAS-025 | Tenant Admin shall configure tenant settings: default language (vi/en), time zone (default Asia/Ho_Chi_Minh), date/number format, currency (VND default), fiscal year, week start, logo; per-user language override. | M | |
| BR-SAAS-026 | The full UI, notifications, standard reports, payslips and system emails shall be available in **Vietnamese and English**; all user-facing strings externalised; Vietnamese diacritics correct in UI, PDF and Excel; Vietnamese-aware sorting and accent-insensitive search. | M | |
| BR-SAAS-027 | Tenant Admin shall define **custom fields** (text, number, date, list, lookup, file, boolean) on configurable entities (employee, assignment, contract, position, org unit, applicant, leave request, payroll element input) with validation, required flag, field-level permission and inclusion in reports, imports, API and exports. | M | Payroll may reference numeric custom fields in formulas |
| BR-SAAS-028 | Tenant Admin shall customise standard forms (field order, visibility, labels in vi/en, sections) and document templates (contract, decision, payslip, letters) without code; changes versioned and reversible. | S | |
| BR-SAAS-029 | **White-labelling**: tenant (Enterprise) or reseller can set logo, colour theme, product name in UI/emails/mobile, email sender domain (SPF/DKIM verified). | C | Phase 2 |
| BR-SAAS-030 | **Custom domain**: tenant can map `hr.customer.vn` to its tenant with platform-managed TLS certificate; default tenant URL `<slug>.yourhrm.vn`. | C | Phase 2 |
| BR-SAAS-031 | A tenant may request a **sandbox** tenant (copy of production, optionally with personal data masked) for testing configuration, imports and training; sandbox is excluded from billable employees. | S | Phase 2. No longer needed for go-live: opening balances use validate-only imports (changed 2026-10-16, D-09) |

### 4.C Operator back-office & support access

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-032 | The platform shall provide an operator back-office (separate from tenant UI, separate auth realm, MFA mandatory, IP allow-list) to manage tenants, plans, billing, provisioning, feature flags, legal parameter packs, and platform health. | M | |
| BR-SAAS-033 | Operators shall have **no default access** to tenant business data. Access requires a time-boxed (≤ 72h) **support grant** approved by Tenant Admin/Owner, scoped by module and read-only/read-write. | M | P6 |
| BR-SAAS-034 | During a support session, sensitive fields (salary amounts, bank account, national ID/CCCD, tax code of individuals, health and SI medical data, dependants) shall be masked unless the grant explicitly includes "sensitive data". | M | Sensitive personal data under PDP rules `[verify-legal]` |
| BR-SAAS-035 | **Impersonation** ("view as user") shall only be possible within an active grant, shall show a persistent banner to the operator, shall be recorded in the tenant's audit log under both identities (operator + impersonated user), and shall block: approving workflows, changing passwords/MFA, releasing payroll/payments, and signing documents. | M | |
| BR-SAAS-036 | Break-glass access for platform-wide incidents shall require two vendor approvers, be limited to 4h, and be notified to the affected tenant within 24h. | M | |
| BR-SAAS-037 | Tenant Admin shall view a log of all operator accesses to their tenant (who, when, why, ticket, what was viewed/changed) and export it. | M | |

### 4.D Identity & authentication

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-038 | Local authentication with tenant-configurable **password policy**: min length (default 10, min 8), complexity, history (default 5), max age (optional), lockout after N failed attempts (default 5 / 15 min), breached-password check. | M | |
| BR-SAAS-039 | **MFA** via TOTP authenticator app and email OTP (SMS OTP optional, billable); enforceable per tenant/role; mandatory for Tenant Owner, Tenant Admin, payroll approvers, and all operators. | M | |
| BR-SAAS-040 | **SSO** with Google Workspace and Microsoft Entra ID (M365) via OIDC. | M | MVP |
| BR-SAAS-041 | **SSO** with any SAML 2.0 or OIDC IdP, configurable per tenant, with claim mapping (email / UPN / employee code), JIT linking to existing users, and optional "SSO-only" enforcement by user group. | S | Phase 2 |
| BR-SAAS-042 | User provisioning from IdP via SCIM 2.0 (create/disable users, group → role mapping proposal; role assignment remains in `core`). | C | |
| BR-SAAS-043 | Session management: configurable idle timeout (default 30 min web, 30 days mobile with biometric unlock), absolute timeout, concurrent session limit, "log out all devices", admin-forced logout. | M | |
| BR-SAAS-044 | One person may hold user accounts in several tenants (e.g., consultant, group shared services) with a tenant switcher; identities are never merged across tenants without explicit linking by the user. | S | |
| BR-SAAS-045 | Employee ESS login shall support username = employee code / work email / mobile phone, with first-login activation via OTP; no shared default passwords. | M | |

### 4.E Reporting & analytics

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-046 | The platform shall provide a **reporting framework** used by all modules: report catalogue, parameters, row-level security inherited from `core` data scopes, field-level masking of sensitive fields, export to Excel (.xlsx), PDF and CSV. | M | Each module defines its standard reports (§8 of each BRD) |
| BR-SAAS-047 | Reporting queries over large volumes shall run on a **separate reporting store** (read replica or analytical store) so that report load cannot degrade OLTP transactions (payroll run, check-in). Data freshness of the reporting store ≤ 15 minutes; reports display "data as of" timestamp. | M | Statutory/payroll-close reports may require real-time read → run on OLTP with throttling |
| BR-SAAS-048 | **Scheduled reports**: users can schedule any report they are allowed to run (daily/weekly/monthly/at payroll close) and deliver to in-app inbox, email (password-protected attachment for sensitive reports) or SFTP. | S | |
| BR-SAAS-049 | **Report designer** (self-service): Tenant Admin/HR analyst builds tabular, pivot and chart reports from a governed semantic layer (business-named fields incl. custom fields), saves, shares by role. | S | Phase 2 |
| BR-SAAS-050 | **Dashboards**: role-based dashboards (CEO, HR manager, line manager, C&B) with KPI tiles and drill-down; tenant can create/edit dashboards from designer reports. | S | Phase 2 |
| BR-SAAS-051 | **HR analytics** package: headcount & FTE trends, hires/terminations, turnover & retention, absenteeism, overtime vs. legal caps, labour cost by org/cost centre, gender/age distribution, time-to-hire, payroll variance month-over-month. | S | Advanced analytics add-on |
| BR-SAAS-052 | **Statutory report framework**: versioned templates (layout + mapping) for government forms, updated via the regulatory change process (P10) independent of application releases; outputs in the official format (XML/Excel/PDF) required by the receiving portal. | M | Templates owned by module BRDs (SI forms in `payroll`, labour reports in `people`) |
| BR-SAAS-053 | Large exports (> 10,000 rows or > 30 s) shall run asynchronously with notification on completion; files expire after 7 days; all exports audited (who, what report, parameters, row count). | M | Data exfiltration control |
| BR-SAAS-054 | Tenants on Enterprise plan may receive a governed **data extract** (daily incremental, pseudonymised option) to their own BI tool (Power BI, etc.) via secure endpoint. | C | |

### 4.F Integration

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-055 | **Public REST API** (versioned, OpenAPI 3 documented, JSON, pagination, idempotency keys on POST, standard error model) covering at minimum: org units, positions, employees, assignments, contracts, attendance records, leave requests/balances, payroll results (read), applicants. | S | Phase 2; internal APIs follow same standards from MVP |
| BR-SAAS-056 | API clients: Tenant Admin can create **API keys** (scoped, expiring, revocable) and **OAuth 2.0 client-credentials** clients with scopes per module & read/write; secrets shown once; usage per client visible. | S | |
| BR-SAAS-057 | API rate limits per tenant and per client according to plan (e.g., requests/minute, daily quota), returning HTTP 429 with retry-after. | S | |
| BR-SAAS-058 | **Webhooks**: tenant subscribes to events (employee.created/updated/terminated, contract.signed, leave.approved, attendance.closed, payroll.approved, payroll.paid, applicant.hired…); HMAC-signed payloads, retries with exponential backoff ≥ 24h, dead-letter view, replay. | S | Event catalogue from modules (§7) |
| BR-SAAS-059 | **Accounting integration**: export payroll journal (GL account, cost centre, legal entity, debit/credit) in configurable mapping for MISA (AMIS / SME), Fast Accounting and SAP (IDoc/CSV/BAPI via middleware); file export at MVP, API push in Phase 2. | M | File export is M; API push S |
| BR-SAAS-060 | **Bank payment integration**: generate salary payment files in the formats of major VN banks (at least Vietcombank, BIDV, VietinBank, Techcombank, MB, ACB) and, where offered, host-to-host / open-banking API with payment status feedback. | M | File export M; API S |
| BR-SAAS-061 | **VSS / BHXH integration**: produce SI declaration files (increase/decrease/adjustment) in the format accepted by VSS e-transaction service providers (I-VAN) and, where available, submit via the provider API and retrieve results. | S | Social Insurance Law 2024 (41/2024/QH15) and VSS procedures `[verify-legal]` |
| BR-SAAS-062 | **eTax integration**: produce PIT withholding declarations (monthly/quarterly, annual finalisation) in HTKK/eTax XML format; issue **electronic PIT withholding certificates** (chứng từ khấu trừ thuế TNCN điện tử) to employees. | M | Decree 123/2020 as amended by Decree 70/2025; Circular 80/2021 `[verify-legal]` |
| BR-SAAS-063 | **E-signature & e-labour contract**: integrate with licensed digital-signature/CA providers (remote signing) to sign labour contracts, appendices, decisions and payslip acknowledgements; store signed PDF with signature validation evidence; support e-labour contract submission to the national e-labour-contract system when mandated. | S | Law on E-Transactions 2023; Labour Code 2019 Art. 14; implementing decree on e-labour contracts `[verify-legal]` |
| BR-SAAS-064 | **VNeID / national identity**: verify employee identity data (CCCD number, name, DOB) against VNeID/national population database through an authorised channel, with employee consent; optionally allow VNeID login for ESS when available to private apps. | C | Decree 69/2024/ND-CP on e-identification `[verify-legal]` |
| BR-SAAS-065 | **Time devices**: ingest punches from biometric/face/card devices (ZKTeco, Hikvision, Suprema at minimum) via cloud push or on-prem connector agent; buffer offline punches; device registry per tenant/site. | M | Consumer is `time-attendance`. Phase 1: one certified connector + Excel/file import are the only punch sources (changed 2026-10-16, OD-9) |
| BR-SAAS-066 | **Messaging channels**: notification delivery via email (tenant-branded), SMS brandname, Zalo OA / ZNS, and mobile push; per-tenant provider credentials or platform default; delivery status tracked; templates in vi/en. | M | Email is Phase 1. Push, SMS and Zalo are Phase 2 (changed 2026-10-16, OD-9) |
| BR-SAAS-067 | **Job boards** (recruitment): post openings and import applicants from major VN job boards (e.g., TopCV, VietnamWorks, CareerViet) and LinkedIn where APIs allow. | C | Consumer is `recruitment` |
| BR-SAAS-068 | All integrations shall share an **integration hub**: connector configuration per tenant, encrypted credential vault, execution log with payload (personal data masked), retry, alerting to Tenant Admin on failure. | M | |

### 4.G Opening-balance import & onboarding (changed 2026-10-16, D-09)

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-069 | Downloadable **import templates** (Excel, vi/en headers, embedded code lists & instructions) for all master and opening-balance data. Templates: org units, positions, cost centres, **employee master** (person, employment, assignment, contract, salary, bank account, dependants), salary history (≥ 6 months for severance/average bases), **SI contribution history** (month, SI basis, contribution unit, for C12-TS reconciliation and severance service time), **YTD income / insurance / PIT per month** (BR-SAAS-089), **leave balances** (per type and leave year, carry-forward, expiry) and YTD OT hours, **outstanding loans and advances** (balance, instalment, next deduction month), applicants (Phase 2). (changed 2026-10-16, D-09) | M | Same templates for every tenant; no customer-specific ETL |
| BR-SAAS-070 | Imports shall run in **validate-only** mode first, report every error by row/column in vi/en, support partial commit policy (all-or-nothing default), be idempotent (upsert by business key), and be reversible (rollback of a batch within 24h if no dependent transactions). | M | |
| BR-SAAS-071 | **Opening-balance import for a mid-year go-live** (replaces the legacy Oracle migration). Any tenant shall load its opening position from the BR-SAAS-069 templates through the Core import engine (BR-CORE-114 opening-balance mode: bulk load, workflow bypass, external reference IDs kept for traceability), in the dependency order of P9, so that the first payroll run, monthly cumulative PIT, SI declarations, leave accrual and loan recovery continue correctly from the go-live month. No legacy database access, ETL pipeline or staging area. (changed 2026-10-16, D-09) | M | P9 |
| BR-SAAS-072 | **Opening-balance reconciliation** report comparing the imported values with **control totals supplied by the tenant** (last legacy payroll register, VSS C12-TS notice, 05/KK-TNCN already filed, leave report), per employee and in total, for: payroll YTD gross and net, PIT YTD taxable income, YTD PIT withheld, dependants registered, SI/HI/UI contribution salary and months contributed, leave entitlement/taken/balance (incl. carry-forward), outstanding loans/advances. Go-live requires zero unexplained differences and sign-off by the tenant's HR/C&B and finance owners. (changed 2026-10-16, D-09) | M | Critical for correct annual PIT finalisation |
| BR-SAAS-073 | *Optional* **payroll comparison**: the tenant may import its last legacy payroll register (Excel, per employee and element) and recompute that month in YourHRM from the imported inputs, with a variance report by employee and element. This replaces the mandatory parallel run. (changed 2026-10-16, D-09) | S | Phase 2 per mvp-scope.md; sponsor to re-confirm whether it is needed as a go-live safety check |
| BR-SAAS-074 | Onboarding wizard for new tenants: company & legal entities → org structure → policies (working calendar, leave, payroll cycle) → import employees → invite users → go-live checklist with progress %. | S | |
| BR-SAAS-088 | **Go-live cut-over and opening-balance lock.** Each legal entity has a go-live month, and its opening balances are dated "as at the end of the previous month". Once the first regular payroll run of that legal entity is approved, its opening balances are locked. After the lock, changes are possible only through an audited **adjustment batch** with a reason. The batch updates the YTD, SI-history, leave and loan ledgers, and is picked up as retro or adjustment input by Payroll and T&A. (new 2026-10-16, D-09) | M | Prevents silent changes to the PIT/SI history after go-live |
| BR-SAAS-089 | **Pre-go-live tax-year history for PIT.** For a go-live after January, the import shall capture, per employee, legal entity and each month of the tax year before go-live: taxable income, exempt income, compulsory insurance deducted, family deductions applied, and PIT withheld. Payroll uses these months for monthly cumulative PIT (BR-PAY-052), year-end finalisation and the 05/QTT-TNCN annexes (BR-PAY-057), and full-year e-withholding certificates (BR-PAY-059). If any month is missing, the import blocks go-live for that legal entity. (new 2026-10-16, D-09) | M | Without migration, this is the only source of the pre-go-live months for finalisation |

### 4.H Mobile / ESS channel

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-075 | **Responsive ESS web** (mobile browsers) for: view/update profile (with approval), payslip & PIT certificate, leave request/balance, attendance view & correction request, approvals inbox for managers, documents, notifications, consent management. | M | Phase 1 ESS channel. No check-in from ESS in Phase 1: pilot attendance comes from time devices and Excel import (changed 2026-10-16, OD-9) |
| BR-SAAS-076 | **Native mobile app** (iOS & Android, single multi-tenant app; tenant resolved at login via company code or email domain) providing ESS features plus GPS/Wi-Fi/QR check-in with geofence, selfie (with consent), push notifications, biometric unlock. | S | Phase 2, including **all** mobile, GPS, Wi-Fi, QR, selfie and PWA check-in (changed 2026-10-16, OD-9/X-04); white-label build optional C |
| BR-SAAS-077 | Payslips on mobile shall require re-authentication (PIN/biometric) and must not be cached unencrypted on the device; screenshots discouraged by warning (Android FLAG_SECURE optional per tenant). | S | |
| BR-SAAS-078 | Mobile app shall support offline check-in capture with trusted device time and sync later, flagged as offline for T&A rules. | S | |

### 4.I Compliance, privacy & security

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-SAAS-079 | The platform shall maintain a **personal data inventory** (data map): for each entity/field — category (basic / sensitive), purpose, legal basis, retention period, module owner, processor(s). Modules register their fields; the inventory is exportable for DPIA. | M | PDP Law 2025 (Law 91/2025/QH15) & implementing decree; Decree 13/2023/ND-CP `[verify-legal]` |
| BR-SAAS-080 | **Consent management**: versioned consent/notice texts per purpose (e.g., biometric check-in, selfie, GPS, marketing, sharing with insurers), capture of consent with timestamp/channel/version, withdrawal at any time in ESS, and APIs for modules to check consent before processing. | M | |
| BR-SAAS-081 | **DSR tooling** per P12: request log, identity verification step, deadline tracking with reminders, one-click personal-data export of a data subject across all modules, deletion/anonymisation honouring statutory retention, evidence of completion. | M | Deadlines `[verify-legal]` |
| BR-SAAS-082 | **Retention policies** configurable per data category with legal minimums enforced as floors (e.g., payroll/accounting records, SI records, labour contracts); automated purge/anonymisation jobs with report; applicants not hired purged after configurable period unless consent to keep. | M | Accounting Law 2015 retention; other periods `[verify-legal]` |
| BR-SAAS-083 | Vendor shall maintain its **DPIA dossier** (as processor) and provide each tenant a template + platform inputs for the tenant's own DPIA dossier; update upon material change. | M | `[verify-legal]` |
| BR-SAAS-084 | **Breach notification** per P11: affected tenants notified ≤ 24h after confirmation; authority notification within **72h** where required; templates and evidence kept. | M | `[verify-legal]` |
| BR-SAAS-085 | **Audit**: immutable (append-only, tamper-evident hash chain) audit trail for security events (login, MFA, SSO config, API key, permission changes), data changes on sensitive entities, exports, operator access; retained ≥ 2 years (configurable longer); searchable by Tenant Admin for own tenant. | M | Business audit stored by `core`; platform supplies tamper-evident store & security events |
| BR-SAAS-086 | The vendor shall operate an ISMS aligned with **ISO/IEC 27001:2022** and controls mappable to **SOC 2 Type II** (security, availability, confidentiality), with evidence collection automated where possible, targeting certification within 18 months of GA. | S | |
| BR-SAAS-087 | Vendor shall obtain/maintain the information-system security level classification required for the platform under VN information security regulations. | S | Decree 85/2016/ND-CP `[verify-legal]` |

---

## 5. Vietnam compliance rules

| # | Rule | Applies to | Legal basis `[verify-legal]` |
|---|---|---|---|
| C1 | Domestic enterprises providing online services that collect user personal data in VN must store such data in Vietnam; minimum storage periods apply to certain data/logs. YourHRM hosts all tenant data in VN data centres. | BR-SAAS-024 | Cybersecurity Law 2018 (24/2018/QH14) Art. 26; Decree 53/2022/ND-CP Art. 26–27; check the new Cybersecurity Law 2025 for changes |
| C2 | Personal data processing requires a legal basis (consent or legal exception, e.g., labour contract performance, statutory obligation); sensitive data (health, biometrics, financial, location, etc.) requires stricter handling. | BR-SAAS-034, 079, 080 | Law on Personal Data Protection 2025 (91/2025/QH15, effective 01/01/2026); implementing decree; Decree 13/2023/ND-CP for transitional matters |
| C3 | Controller/processor obligations: DPA between tenant (controller) and vendor (processor); impact assessment dossiers; cross-border transfer impact assessment for any transfer abroad. | BR-SAAS-024, 083 | PDP Law 2025 |
| C4 | Personal data breach must be notified to the competent authority (MPS – A05) within 72h of detection. | BR-SAAS-084 | PDP Law 2025; Decree 13/2023 Art. 23 |
| C5 | Data subject rights (access, rectification, deletion, restriction, objection, consent withdrawal) handled within statutory deadlines. | BR-SAAS-081 | PDP Law 2025 |
| C6 | E-invoices for sale of services: content, numbering, timing of issuance, tax-authority code, correction via adjustment/replacement; retention. | BR-SAAS-014, 015 | Decree 123/2020/ND-CP; Decree 70/2025/ND-CP; Circular 78/2021/TT-BTC; Circular 32/2025/TT-BTC |
| C7 | Electronic PIT withholding certificates issued by employers to employees. | BR-SAAS-062 | Decree 123/2020 Art. 30–33 as amended by Decree 70/2025 |
| C8 | Electronic tax declaration in prescribed XML formats. | BR-SAAS-062 | Tax Administration Law 38/2019/QH14; Circular 80/2021/TT-BTC (and amendments) |
| C9 | SI/HI/UI e-declaration via VSS and licensed I-VAN providers. | BR-SAAS-061 | Social Insurance Law 2024 (41/2024/QH15, effective 01/07/2025); VSS procedural decisions |
| C10 | Electronic labour contracts have the same legal validity as written contracts; e-signature and e-contract platform requirements. | BR-SAAS-063 | Labour Code 2019 Art. 14; Law on E-Transactions 2023 (20/2023/QH15); Decree on digital signatures (23/2025/ND-CP); decree on e-labour contracts |
| C11 | Electronic identification via VNeID; use of national population database by third parties only via authorised channels. | BR-SAAS-064 | Decree 69/2024/ND-CP; Law on Identification 2023 |
| C12 | Accounting documents (incl. payroll and subscription invoices) retained per accounting law (generally ≥ 10 years for documents used to record books). | BR-SAAS-019, 082 | Accounting Law 2015 (88/2015/QH13); Decree 174/2016/ND-CP |
| C13 | Information systems processing personal data must be classified and protected according to security level. | BR-SAAS-087 | Law on Network Information Security 2015; Decree 85/2016/ND-CP |
| C14 | SMS brandname / advertising messages require registration and recipient consent; transactional HR messages must not carry advertising. | BR-SAAS-066 | Decree 91/2020/ND-CP on spam `[verify-legal]` |

---

## 6. Key business entities

> Platform entities live in a platform-level schema (proposed `saas`, see Q1) and are **not** tenant-scoped by RLS, except where marked (T).

| Entity | Purpose | Key attributes |
|---|---|---|
| Tenant | Subscribing customer organisation | tenant_id, slug, name, state, tier (shared/dedicated), region (VN), default locale, time zone, created/activated/terminated dates, data-store locator, encryption key ref |
| Legal Entity | Company/MST within a tenant | id, tenant_id, MST, legal name (vi/en), address, SI unit code, e-invoice identity, status |
| Plan / Edition (versioned) | Commercial bundle | code, version, modules, features, limits, price book ref, valid from/to |
| Price Book | Prices per module/add-on | currency, unit (PEPM/flat/usage), tiers, minimum fee, VAT category |
| Subscription | Tenant's contract | tenant_id, plan version, billing cycle, start/end, committed seats, discounts, auto-renew, status |
| Subscription Item | Module/add-on line | subscription_id, module/add-on, quantity basis, start/end, price override |
| Entitlement | Resolved feature availability | tenant_id, legal_entity_id?, feature_key, enabled, limit value, source (plan/add-on/override), effective from/to |
| Feature Flag | Release / experiment control | key, type (release/ops/permission/experiment), rules by scope, default, owner, expiry date |
| Usage Record | Metering | tenant_id, metric, dimension (module, legal entity), date, quantity, source |
| Invoice / Invoice Line | Subscription billing | number, e-invoice code & lookup code, issue date, period, lines, VAT, total VND, status, replacement/adjustment link |
| Payment | Settlement | invoice_id, method, reference, amount, date, reconciliation status |
| Support Grant | Consent-based operator access | tenant_id, operator, ticket, scope, sensitive flag, approved_by, valid from/to, status |
| Operator Session | Operator access log | grant_id, operator, impersonated user, start/end, actions ref |
| Identity Provider Config (T) | SSO set-up | protocol, metadata/issuer, client id, cert, claim mapping, enforcement groups |
| User Credential / MFA Factor (T) | Authentication | user_id, factor type, secret ref, last used, recovery codes hash |
| API Client (T) | API key / OAuth client | client_id, name, scopes, secret hash, expiry, owner, rate plan, last used |
| Webhook Subscription (T) | Event delivery | events, URL, secret, status, delivery log |
| Integration Connector Config (T) | Integration hub | connector type (MISA, bank X, VSS I-VAN…), credentials ref, mapping, schedule, status |
| Custom Field Definition (T) | Tenant extension | entity, key, type, labels vi/en, validation, required, permissions |
| Report Definition / Schedule (T) | Reporting | source (standard/designer), owner, parameters, sharing, schedule, delivery channel |
| Legal Parameter Pack | Statutory values for all tenants | version, effective date, parameters (key, value, unit), legal reference, approval, published date |
| Statutory Template | Government form layout/mapping | form code, version, effective dates, output format, mapping |
| Import Batch (T) | Data import / opening-balance import | template, file, mode (validate/commit/adjustment), counts, errors, status, rollback ref |
| Opening-Balance Cut-over (T) | Go-live point per legal entity (BR-SAAS-088) | legal entity, go-live month, as-at date, status (open/locked), locking payroll run, adjustment batches (new 2026-10-16, D-09) |
| Reconciliation Run (T) | Opening balance checks | scope, tenant-supplied control totals, imported totals, differences, sign-off (changed 2026-10-16, D-09) |
| Consent Record (T) | PDP consent | data subject, purpose, notice version, given/withdrawn at, channel |
| Data Subject Request (T) | DSR | requester, type, received, due date, verification, status, evidence |
| Retention Policy (T) | Data lifecycle | data category, retention period, legal floor, action (purge/anonymise), last run |
| Security Incident / Breach | Incident management | severity, affected tenants, data categories, timeline, notifications sent |
| Audit Event | Tamper-evident trail | tenant_id, actor (user/operator/API client), action, object, before/after hash, timestamp, IP, chain hash |

---

## 7. Cross-module interfaces

### 7.1 Provides (platform → modules)

| # | What | To module(s) | When / mechanism |
|---|---|---|---|
| PR-01 | **Tenant context** (tenant_id, legal entity, locale, time zone) on every request, job and event; DB session variable for RLS | core, people, time-attendance, payroll, recruitment | Every request / job (synchronous) |
| PR-02 | **Entitlement & feature-flag checks** (module, feature, limits) | all modules | Every UI render, API call, scheduled job |
| PR-03 | **Authenticated identity** (user id, auth method, MFA level, SSO claims) and session; step-up auth on demand for sensitive actions (payroll approval, bank file release) | core (maps identity → roles/permissions), all modules | Login / per request |
| PR-04 | **Tenant settings & localisation** (vi/en resource bundles, formats, calendar, currency) | all modules | Runtime |
| PR-05 | **Custom field framework** (definitions, storage, validation, API exposure) | core, people, time-attendance, payroll, recruitment | Configuration time / runtime |
| PR-06 | **Legal parameter packs** (effective-dated statutory values: minimum wage regions, SI/HI/UI rates & caps, PIT brackets & deductions, OT caps, public holidays template) | payroll, time-attendance, people | On publication + query by effective date |
| PR-07 | **Statutory template framework** (versioned government form layouts/output formats) | payroll (SI, PIT), people (labour reports), time-attendance (OT reports) | On publication |
| PR-08 | **Reporting framework & reporting store** (catalogue, export Excel/PDF/CSV, scheduling, async export, masking) | all modules | Module registers reports; runtime |
| PR-09 | **Integration hub & connectors**: accounting journal export, bank payment files/API, VSS I-VAN, eTax/PIT e-certificates, e-signature, VNeID, time devices, job boards | payroll (accounting, bank, eTax, VSS), people (e-signature, VNeID), time-attendance (devices), recruitment (job boards) | On module trigger / schedule |
| PR-10 | **Messaging delivery** (email, push, SMS, Zalo) with delivery status | core (notification centre composes; platform delivers), all modules via core | Event-driven |
| PR-11 | **Public API gateway & webhook delivery** (auth, rate-limit, versioning, event fan-out) | all modules expose resources/events through it | Runtime |
| PR-12 | **Import & opening-balance framework** (templates, validate/commit, error report, rollback, cut-over lock, adjustment batches, reconciliation runs) | people (employees, contracts), time-attendance (leave balances, YTD OT), payroll (monthly YTD income/PIT, SI history, loans), recruitment (applicants, P2), core (org, positions, users) | Onboarding / go-live / ad-hoc (changed 2026-10-16, D-09) |
| PR-13 | **Privacy services**: consent check API, DSR orchestration (export/anonymise requests to each module), retention scheduler | all modules | DSR / scheduled |
| PR-14 | **Tamper-evident audit store** & security events | core (business audit writer), all modules | Event-driven |
| PR-15 | **Mobile/ESS shell** (app container, auth, push, offline sync, check-in capture) | people, time-attendance, payroll, core (approvals) | Runtime. Phase 2; Phase 1 provides responsive web ESS only (changed 2026-10-16, OD-9) |
| PR-16 | **Encrypted file/document storage** (per-tenant prefixes, signed short-lived URLs, AV scan) | all modules | Runtime |

### 7.2 Consumes (modules → platform)

| # | What | From module | When / mechanism |
|---|---|---|---|
| CO-01 | Users, roles, permission & data-scope model (to enforce authorisation in reports, API scopes, support grant masking) | core | Runtime |
| CO-02 | Company / org tree / cost centres (for legal entity linkage, report filters, accounting mapping) | core | On change (event) |
| CO-03 | Business audit events and notification requests (to store tamper-evident and deliver via channels) | core | Event-driven |
| CO-04 | Workflow/approval decisions for platform processes (support grant approval, DSR approval, import commit approval) | core | Event-driven |
| CO-05 | **Active persons per legal entity at month end** (billable employee count, BR-SAAS-012) | people | Month-end snapshot (daily for information) (changed 2026-10-16, D-10) |
| CO-06 | Employee lifecycle events (hired, changed, terminated) for SCIM de-provisioning, webhooks, user deactivation | people | Event-driven |
| CO-07 | Personal-data field registrations and per-subject export/anonymise handlers | people, time-attendance, payroll, recruitment, core | Design time + DSR runtime |
| CO-08 | Contracts/decisions for e-signature; signature result callback consumer | people | On demand |
| CO-09 | Attendance/device mapping (employee ↔ device enrolment id), punch acceptance results | time-attendance | On device sync |
| CO-10 | Approved payroll results: payroll journal (GL lines), bank payment instructions, PIT withholding data, SI declaration data | payroll | On payroll approval / payment release |
| CO-11 | Payroll run volume (employees processed) for performance monitoring and metering of payroll module | payroll | Per run |
| CO-12 | Job openings to post, applicant records & candidate consent | recruitment | On publish / on apply |
| CO-13 | Module-defined standard reports, statutory template mappings, webhook event schemas and import templates | all modules | Design time (registration per release) |
| CO-14 | Module retention rules (legal floors per data category) | people, payroll, time-attendance, recruitment | Design time |

---

## 8. Reports & analytics

### 8.1 Platform/operator reports (back-office)
| Report | Audience | Content |
|---|---|---|
| Tenant portfolio | Vendor mgmt | Tenants by state, plan, tier, MRR/ARR, billable employees, churn, trial conversion rate |
| Usage & metering | Billing | Per tenant per metric per day; over-limit warnings |
| Billing & AR ageing | Billing | Invoices issued, paid, overdue buckets, dunning stage |
| Operator access report | Security | Grants, sessions, impersonations, break-glass events |
| Platform SLA report | Vendor mgmt, tenants (Enterprise) | Monthly availability, incidents, RPO/RTO test results, performance percentiles |
| Regulatory pack adoption | Legal SME | Pack versions published, tenants on each, payroll runs per pack |
| Integration health | Support | Connector failures, webhook dead letters, API error rates by tenant |

### 8.2 Tenant-facing platform reports
| Report | Audience | Content |
|---|---|---|
| Subscription & invoices | Tenant Owner / Finance | Current plan, modules, billable employees trend, invoices & e-invoice links |
| Security & login activity | Tenant Admin | Logins, failures, MFA adoption, SSO usage, locked accounts |
| Operator access log | Tenant Admin | BR-SAAS-037 |
| Export & data access log | Tenant Admin / DPO | Exports, large report runs, API client usage |
| Privacy register | DPO | Consents by purpose, DSRs and SLA status, retention job results |
| Opening-balance reconciliation | HR / Finance / consultant | BR-SAAS-072 outputs with sign-off (changed 2026-10-16, D-09) |

### 8.3 Framework capabilities (used by all module reports)
Catalogue, parameters, RLS & masking, Excel/PDF/CSV export with VN fonts, scheduling, async exports, designer, dashboards, HR analytics (BR-SAAS-046 … 054).

---

## 9. Non-functional requirements (product-wide)

| ID | Requirement | Pri | Notes |
|---|---|---|---|
| NFR-SAAS-001 | **Availability**: production monthly uptime ≥ 99.5% (Starter/Professional) and ≥ 99.9% (Enterprise/dedicated), excluding announced maintenance ≤ 4h/month scheduled 22:00–05:00 ICT and never in the payroll window days 25–5 without tenant consent. | M | Service credits defined in SLA |
| NFR-SAAS-002 | **Payroll performance**: a monthly payroll run for 10,000 employees (≈ 60 elements each, incl. SI/PIT) completes in ≤ 15 minutes; 1,000 employees in ≤ 2 minutes; recalculation of one employee ≤ 5 s. | M | Measured on reference data set in perf environment |
| NFR-SAAS-003 | **Interactive performance**: 95th percentile server response ≤ 500 ms for transactional screens/APIs and ≤ 3 s for standard reports up to 5,000 rows, under the reference load (NFR-SAAS-005). | M | |
| NFR-SAAS-004 | **Check-in burst**: sustain 2,000 check-in events/minute per tenant and 20,000/minute platform-wide (shift start peaks) with p95 ≤ 1 s acknowledgement. | M | Phase 1 sources: device connector and file import only (changed 2026-10-16, OD-9) |
| NFR-SAAS-005 | **Scalability**: shared tier supports ≥ 500 tenants and ≥ 200,000 employees total at launch, scaling horizontally (stateless app nodes, partitioned large tables by tenant/period) to 5× without re-architecture; a noisy tenant cannot consume > 20% of shared pool capacity (per-tenant quotas on jobs, reports, API). | M | |
| NFR-SAAS-006 | **Backup & DR**: RPO ≤ 15 minutes (PITR), RTO ≤ 4h (shared) / ≤ 2h (Enterprise); daily full + continuous WAL archive; backups encrypted, retained 35 days, stored in a second VN data centre; restore test quarterly; per-tenant point-in-time restore to sandbox possible. | M | |
| NFR-SAAS-007 | **Encryption**: TLS 1.2+ in transit (internal service traffic included); AES-256 at rest for DB, backups, files; application-level encryption for highly sensitive fields (bank account, CCCD, individual tax code, salary amounts, health data) with keys in KMS/HSM; per-tenant keys for dedicated tier and supported for shared tier (crypto-shredding). | M | |
| NFR-SAAS-008 | **Secrets**: no credentials in code or repo; secrets in vault, rotated ≤ 90 days; customer data files (e.g. opening-balance templates) never committed to the repository (changed 2026-10-16, D-09). | M | |
| NFR-SAAS-009 | **Application security**: OWASP ASVS L2 controls; SAST/DAST/dependency scanning in CI blocking critical findings; annual third-party penetration test + after major release; vulnerability remediation SLA: critical 7 days, high 30 days. | M | |
| NFR-SAAS-010 | **Observability**: centralised logs (personal data masked), metrics and distributed traces tagged with tenant_id and correlation id; per-tenant dashboards for support; alerting on SLOs; logs retained ≥ 12 months (security logs ≥ 24 months) in VN. | M | Decree 53/2022 log retention `[verify-legal]` |
| NFR-SAAS-011 | **Accessibility**: web and mobile UI conform to WCAG 2.1 AA for ESS and core HR flows; keyboard navigation; screen-reader labels in vi/en. | S | |
| NFR-SAAS-012 | **Browser/device support**: latest 2 versions of Chrome, Edge, Safari, Firefox; iOS ≥ 16, Android ≥ 10; responsive down to 360 px width. | M | |
| NFR-SAAS-013 | **Usability/i18n**: every screen fully localised vi/en, no mixed-language UI; Vietnamese fonts render correctly in PDF/Excel exports. | M | |
| NFR-SAAS-014 | **Zero-downtime releases**: rolling/blue-green deployment; backward-compatible DB migrations (expand/contract); no planned downtime for routine releases; release cadence ≥ every 2 weeks; rollback ≤ 15 min. | M | |
| NFR-SAAS-015 | **Release governance**: release notes in vi/en published ≥ 3 working days before release; feature changes affecting calculations hidden behind per-tenant toggles until tenant opt-in or announced date; pilot/early-adopter ring before general rollout. | M | Uses BR-SAAS-009 |
| NFR-SAAS-016 | **Regulatory change SLA**: a new legal instrument changing statutory values is available as a published, tested legal parameter pack ≥ 7 calendar days before its effective date when the instrument is issued ≥ 30 days in advance; otherwise within 10 working days of issuance, with retroactive recalculation support. | M | P10 |
| NFR-SAAS-017 | **Traceability of calculations**: every payroll/attendance result stores the legal parameter pack version, configuration version and code release version used, so any historic result is reproducible. | M | |
| NFR-SAAS-018 | **API quality**: public API backward compatible within a major version; deprecation notice ≥ 6 months; p95 latency ≤ 300 ms for single-resource GET. | S | |
| NFR-SAAS-019 | **Data portability**: full tenant export format documented and stable; export of 10,000-employee tenant completes ≤ 6 hours. | M | |
| NFR-SAAS-020 | **Support SLA**: severity-1 response ≤ 1h 24×7 (Enterprise) / business hours (others); Vietnamese and English support. | M | |
| NFR-SAAS-021 | **Capacity during peak**: payroll close (days 25–5) and annual PIT finalisation (Jan–Mar) capacity planned at 3× average load; load test before each peak season. | M | |
| NFR-SAAS-022 | **Mobile app performance**: cold start ≤ 3 s on mid-range Android; check-in submit ≤ 2 s on 4G; app size ≤ 60 MB. | S | |

---

## 10. Assumptions, open questions, risks

### 10.1 Assumptions
- A1. Hosting is on a Vietnam-based cloud/data-centre provider (e.g., Viettel IDC, VNPT, FPT, CMC) offering at least two availability zones/sites in VN; hyperscaler regions outside VN are not used for personal data.
- A2. The tenant is the personal-data **controller**; the vendor is **processor** under a signed DPA. The vendor is controller only for its own customer-contact and billing data.
- A3. Authorisation (roles, permissions, data scopes) lives in `core`; the platform consumes it and does not duplicate it.
- A4. The pilot customer will fill in the opening-balance templates from its own records and provide control totals and data owners for reconciliation sign-off. No Oracle access and no parallel run are planned (changed 2026-10-16, D-09).
- A5. E-invoice issuance uses a certified third-party provider (e.g., VNPT, Viettel, MISA meInvoice, BKAV) rather than an in-house e-invoice system.
- A6. VSS submission goes through a licensed I-VAN provider; direct VSS APIs for third parties are not assumed.
- A7. Legal parameter packs are maintained centrally by the vendor; tenants may override only where the law allows (e.g., more favourable benefits).

### 10.2 Open questions
| # | Question | Owner |
|---|---|---|
| Q1 | Where do platform entities live — a new `saas` schema (recommended) or inside `core`? The charter fixes five module schemas. | Data architect / PM |
| Q2 | Default isolation for shared tier: single DB with RLS on all tables, or schema-per-tenant? Impacts import tooling, reporting store and per-tenant restore. | Solution architect (ADR) |
| Q3 | Reporting store technology: PostgreSQL read replica + materialised views, or separate analytical DB (e.g., ClickHouse in VN)? | Solution architect |
| Q4 | ~~Billable employee definition~~ — **decided 2026-10-16 (D-10)**: active person at month end per legal entity (BR-SAAS-012). Still open: treatment of seasonal workers / collaborators (cộng tác viên) not on payroll. | Product / Sales |
| Q5 | Does the PDP Law 2025 implementing decree change DSR deadlines, DPIA submission and breach-notification content vs. Decree 13/2023? | VN legal consultant |
| Q6 | Is the national e-labour-contract platform mandatory for our tenants, from what date, and what integration interface is published? | VN legal consultant / Integration analyst |
| Q7 | VNeID integration: is access available to private SaaS vendors, and under what licence? | Integration analyst |
| Q8 | Which banks are mandatory for the pilot customer and do they offer host-to-host APIs? | Integration analyst |
| Q9 | Do enterprise customers require on-premise or private-cloud deployment (outside SaaS)? Currently out of scope. | PM / Sales |
| Q10 | Retention of tenant data after termination (90 days default) — acceptable to pilot customer and consistent with accounting record obligations held by the tenant? | PM / Legal |
| Q11 | Target ISO 27001 certification date and whether SOC 2 is needed for VN market at all. | Security lead |

### 10.3 Risks
| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Cross-tenant data leak due to missing tenant filter | Critical (legal, reputational) | DB-enforced RLS + app filter + automated isolation tests (BR-SAAS-021/023) |
| R2 | Legal changes issued with short notice or retroactive effect | High: wrong payroll for all tenants | Parameter packs, regulatory SLA, retroactive recalculation, consultant on retainer (P10, NFR-SAAS-016) |
| R3 | Opening YTD balances imported incorrectly or incompletely from the customer's Excel files (no migration and no parallel run, D-09) | High: wrong monthly PIT and annual finalisation, SI history disputes, wrong leave balances | Standard templates with validation; mandatory full tax-year months (BR-SAAS-089); reconciliation against tenant control totals with sign-off (BR-SAAS-072); lock and audited adjustments (BR-SAAS-088); optional payroll comparison (BR-SAAS-073) (changed 2026-10-16, D-09) |
| R4 | Data residency breach via third-party SaaS (email, monitoring, error tracking) | High: regulatory penalty | Vendor inventory, VN-hosted alternatives, PII scrubbing, transfer impact assessment |
| R5 | Report workload degrading payroll/check-in performance | Medium | Separate reporting store, per-tenant quotas (BR-SAAS-047, NFR-SAAS-005) |
| R6 | Dependence on external integrators (I-VAN, e-invoice, e-signature, banks) changing formats/APIs | Medium | Integration hub abstraction, connector versioning, monitoring |
| R7 | Operator misuse of support access | High | Consent grants, masking, impersonation restrictions, tenant-visible logs (BR-SAAS-033…037) |
| R8 | Scope creep: platform capabilities pushed into MVP | Medium: delay first paying tenant | Strict MoSCoW; Phase 2 for self-service, designer, native app, API |
| R9 | PDP Law 2025 implementing rules still evolving | Medium | Legal watch, configurable consent/retention, flagged `[verify-legal]` items reviewed before MVP |
