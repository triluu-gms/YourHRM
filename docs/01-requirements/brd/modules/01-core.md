# YourHRM — BRD Module 01: Core (`core`)

| Item | Value |
|---|---|
| Module | `core` (DB schema `core`, backend `backend/src/modules/core` + shared `auth`, `audit`, `workflow`, `common`) |
| Requirement prefix | `BR-CORE-NNN`, `NFR-CORE-NNN` |
| Version / date | Draft v0.1, 2026-10-16 (minor edits 2026-10-16 for D-09/OD-9: BR-CORE-005, 081, 114, P-07, P-22, R-01) |
| Author | BA – Core |
| Status | Draft for review (Phase 0) |
| Related | [Project charter](../../../00-project-management/project-charter.md), 02-people, 03-time-attendance, 04-payroll, 05-recruitment, 06-saas-platform |

> Convention reminder: legal values are effective-dated parameters, never hard-coded. Every legal figure in this document is marked `[verify-legal]` until confirmed by a Vietnam labour/tax consultant.

---

## 1. Purpose & scope

### 1.1 Purpose
Core is the foundation every other module stands on inside a tenant. It owns:
- **The employer structure**: who the legal employer is (legal entity), where people work (branches / work locations), how the organisation is shaped (org units, effective-dated), and the job architecture (job titles, positions, grades, cost centres).
- **Identity & access**: users, roles, permissions, data-scope security, SSO.
- **Shared engines**: workflow & approval, notifications, import/export, document storage, audit log.
- **Shared reference data**: VN administrative units (post-2025 merger), nationalities, ethnicities, religions, banks, currencies, generic code lists, tenant settings.

### 1.2 In scope
| # | Area |
|---|---|
| S1 | Tenant company profile and **legal entities** (1..n per tenant), each with tax code, SI unit code, minimum-wage region, bank accounts, legal representative, seal/signature assets |
| S2 | Branches / work locations (address, minimum-wage region, geo-fence data for attendance, timezone) |
| S3 | Org tree: org units, org unit types, managers, effective-dated reorganisations (merge, split, move, rename, close) with history and future-dated changes |
| S4 | Job architecture: job families, job titles, positions (budgeted seats), grades / levels with salary-range reference, cost centres |
| S5 | Users, roles, permissions, data-scope rules, delegation of access, SSO (OIDC / SAML), MFA, sessions |
| S6 | Generic workflow & approval engine (multi-level, conditional routing, parallel, delegation, substitution, SLA, escalation, recall, mobile approve — mobile approve is Phase 2, changed 2026-10-16, OD-9) |
| S7 | Notification engine: templates, channels (email, in-app, mobile push, Zalo OA / ZNS, SMS optional), preferences, digest |
| S8 | Tenant settings & generic code lists; master data (provinces/wards, nationalities, ethnicities, religions, banks, currencies, ID document types) |
| S9 | Audit log (data change + security events), immutable, searchable |
| S10 | Document/file storage service (upload, virus scan, versioning, retention, access control, e-signature hand-off) |
| S11 | Import/export framework (templates, validation, staging, dry-run, partial commit, async jobs, Excel/CSV) |
| S12 | Sequence / numbering service (employee codes, document numbers) and holiday-agnostic calendar utilities used by workflow SLA |

### 1.3 Out of scope (owned elsewhere)
| Item | Owner |
|---|---|
| Tenant provisioning, subscription plans, billing, feature flags per plan, platform super-admin, cross-tenant operations, data residency of the platform | saas-platform (`06`) |
| Person / employee records, assignments, contracts, headcount actuals | people (`02`) |
| Public holidays, work calendars, shifts, leave | time-attendance (`03`) — Core provides only a business-day calculator interface that *consumes* the T&A holiday calendar |
| Salary components, SI/PIT rates, minimum-wage **amounts** | payroll (`04`) — Core holds the *region assignment* of each entity/location; payroll holds the region → amount table |
| Requisitions, staffing plans | recruitment (`05`) |
| BI / data warehouse | Reporting stream (Phase 2) |

---

## 2. Actors & personas

| Actor | Description | Typical Core activities |
|---|---|---|
| Tenant Owner / Super Admin (Quản trị hệ thống khách hàng) | Customer-side top admin created at tenant provisioning | Configure entities, SSO, roles, settings; grant admin rights |
| HR Administrator (Quản trị nhân sự) | HR ops for one or more legal entities | Maintain org tree, positions, job titles; run imports; configure workflows & templates |
| C&B Specialist | Compensation & benefits | Maintain grades, salary ranges reference, cost centres |
| Line Manager (Quản lý trực tiếp) | Manages an org subtree | Approves requests; views own subtree |
| Employee (ESS user) | Any staff with login | Approves when delegated; receives notifications; manages own preferences |
| Approver / Delegate / Substitute | Any user acting in a workflow step | Approve, reject, return, delegate, comment |
| Finance user | Accounting / payroll costing | Maintains cost-centre mapping; read-only org |
| Auditor (internal / external) | Read-only | Searches audit log, exports evidence |
| IT Administrator (customer) | Customer IT | Configures SSO, IP allowlist, API clients |
| System (scheduler / integration) | Non-human actor | Runs SLA timers, effective-dated activation, notification dispatch, import jobs |
| Platform Operator | YourHRM staff (saas-platform) | Maintains global master data (provinces, banks); support access (break-glass) |

---

## 3. Business processes

### P-01 Set up a tenant's employer structure (initial configuration)
1. saas-platform provisions the tenant and creates the Tenant Owner account (Core receives `TenantProvisioned`).
2. Tenant Owner enters company group profile (name, logo, default language, timezone `Asia/Ho_Chi_Minh`, currency VND).
3. Tenant Owner creates ≥1 **legal entity**: legal name (VN/EN), short name, enterprise tax code (MST), SI unit code (mã đơn vị BHXH), business registration number/date, registered address (province/ward), legal representative, minimum-wage region, default bank account, fiscal year start.
4. System validates MST format and uniqueness within tenant; activates entity.
5. HR Admin creates branches / work locations, each linked to exactly one legal entity, with address, region and (optional) geo-coordinates and radius.
6. HR Admin builds the org tree (root per legal entity or a group-level root), job titles, grades, positions, cost centres — manually or via import (P-09).
7. Exception: the plan limits number of legal entities (from saas-platform) → system blocks creation with a plan-limit message.

### P-02 Effective-dated reorganisation (tái cơ cấu tổ chức)
1. HR Admin opens a **reorganisation package** with an effective date (past, today, or future).
2. Adds changes: create unit, rename, move under new parent, merge A+B→C, split A→A1+A2, change manager, change cost centre, close unit.
3. System computes impact: affected positions, employees (via people), open requisitions (via recruitment), active workflow instances routed to affected managers, data-scope changes.
4. Package is submitted for approval through the workflow engine (process type `CORE_REORG`).
5. On approval and when the effective date is reached, the scheduler activates the package atomically: new org versions created, old versions end-dated (end date = effective date − 1).
6. Core publishes `OrgUnitChanged` / `ReorgActivated` events; people module re-maps assignments per the mapping table in the package; recruitment and workflow re-route.
7. Exceptions: (a) unit to be closed still has active employees with no mapping → block; (b) back-dated package that crosses a closed payroll period → warning and requires `CORE_REORG_BACKDATE` permission; payroll is notified for retro handling; (c) conflicting future packages on the same unit → block until resolved.

### P-03 Position management
1. HR Admin creates a position: code, title (job title), org unit, location, grade range, cost centre, reports-to position, headcount (FTE) budget, status (proposed / active / frozen / closed), effective dates.
2. Optional approval (`CORE_POSITION`).
3. People module assigns employees to the position; Core exposes occupancy = budget FTE − occupied FTE (occupied supplied by people).
4. Recruitment raises requisitions against vacant positions.
5. Exception: closing a position with an incumbent → block; freezing → blocks new requisitions.

### P-04 User lifecycle & access
1. Employee hired in people → people emits `EmployeeHired` → Core auto-creates a user (if tenant setting `auto_create_user = true`) with default ESS role, login = work email or employee code, locale.
2. Activation link sent via notification (email/Zalo/SMS). With SSO, account links on first SSO login via email / `employee_code` claim.
3. HR Admin assigns additional roles with data scopes (e.g. "HR Officer" scope = Legal Entity A).
4. Manager role is **derived** automatically: manager of an org unit gets the `LINE_MANAGER` role with scope = own subtree (no manual grant).
5. On `EmployeeTerminated` (effective last working day) → user deactivated at 23:59 of that day; pending approvals reassigned (P-07 step 8); sessions revoked.
6. Exceptions: rehire reactivates the same user; a user may exist without employee record (external auditor, consultant) with expiry date.

### P-05 Single sign-on
1. IT Admin configures an identity provider (OIDC: Microsoft Entra ID, Google Workspace; SAML 2.0 generic).
2. Maps claims to user attributes; chooses enforcement: SSO-only, or SSO + local password (for break-glass admins).
3. Tests connection with a test user; activates.
4. Exception: IdP outage → only users flagged `allow_local_login` can sign in.

### P-06 Configure a workflow
1. HR Admin selects a **process type** published by a module (e.g. `ATT_LEAVE_REQUEST`, `ATT_OT_REQUEST`, `PPL_CONTRACT_NEW`, `PAY_RUN_APPROVAL`, `REC_REQUISITION`, `CORE_REORG`).
2. Defines one or more **workflow definitions** with applicability conditions (legal entity, org unit, employee category, request attributes such as leave days > 3, OT hours, amount).
3. Defines steps: approver resolver (direct manager, manager at level N, position holder, role in scope, named user, dynamic field from request, HR of entity), step type (single, any-of, all-of, parallel branches), SLA (business hours/days), escalation rule, auto-action on timeout (escalate / auto-approve / auto-reject — auto-approve only if process type allows), skip rules (approver = requester, duplicate approver).
4. Publishes a version; running instances keep the version they started with.

### P-07 Run an approval
1. Module submits a request → Core creates a workflow instance with a snapshot of the request summary and resolved approver chain.
2. Approver receives notification (in-app + email; push/Zalo in Phase 2) with deep link and approves in the web app; approval from the mobile app or email action link (one-time, signed, expiring) is Phase 2 (changed 2026-10-16, OD-9).
3. Approver: approve, reject (reason mandatory), return for change (back to requester or a previous step), request info, forward (if allowed), add comment/attachment.
4. Delegation: an absent approver's tasks route to the delegate defined for the period / process type; actions are recorded "by B on behalf of A".
5. Substitution: HR Admin may reassign an open task to another user (reason mandatory).
6. SLA timer runs on business time (tenant work calendar from T&A); reminders at configurable % of SLA; on breach → escalate to next-level manager or named role.
7. On final outcome Core calls back the owning module (`WorkflowCompleted` with outcome) — the module applies the business effect.
8. Exceptions: approver deactivated / position vacant → resolver falls back (manager's manager → HR of entity) and logs; requester withdraws before final decision; org change mid-flight → open steps re-resolved by tenant setting (`reresolve_on_reorg`).

### P-08 Send a notification
1. A module raises a notification event with template code, recipients (users, roles in scope, external email/phone), and data payload.
2. Core renders template in recipient's language (VI/EN), applies channel preferences and quiet hours, deduplicates, and queues per channel.
3. Dispatch with retry; delivery status tracked; failures fall back to the next channel per rule (e.g. Zalo ZNS fail → SMS → email).
4. In-app inbox shows unread count; items deep-link to the record.

### P-09 Import data
1. User downloads a typed template (Excel) for an import type published by a module (e.g. org units, positions, employees, opening leave balances, salary history).
2. Uploads file → Core stores it, parses into staging, runs module-provided validators.
3. User reviews errors per row (downloadable error file), fixes, re-uploads, or commits valid rows only (if import type allows partial).
4. Commit runs asynchronously; result summary + audit trail per import job; undo supported where the import type declares it.

### P-10 Audit investigation
1. Auditor filters audit log by entity type, record, user, date, action, IP.
2. Views before/after values; exports to Excel/CSV (export itself audited).

---

## 4. Business requirements

### 4.1 Company, legal entities, locations
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-001 | A tenant shall have one company group profile and **1..n legal entities**; the max number is limited by the tenant's plan (from saas-platform). | M | Group profile holds branding, default language, timezone, currency. |
| BR-CORE-002 | Each legal entity shall store: legal name VI/EN, short name, enterprise tax code (MST), SI unit code, business registration no./date/issuer, registered address (province + ward), phone, email, legal representative (name, title, ID), default minimum-wage region, fiscal year start month, default bank account(s), company seal and authorised-signatory images, effective-dated status. | M | MST and SI code are consumed by payroll for PIT/SI declarations. |
| BR-CORE-003 | MST shall be validated as 10 digits (head office) or 13 digits in the form `NNNNNNNNNN-NNN` (dependent unit/branch) and be unique per tenant. | M | Format `[verify-legal]`. |
| BR-CORE-004 | Legal-entity attributes that affect statutory calculations (tax code, SI code, region, address) shall be **effective-dated**; history must be retained and queryable "as of date". | M | Payroll needs the value valid in the payroll period. |
| BR-CORE-005 | The system shall support branches / work locations linked to exactly one legal entity, with: code, name, type (head office, branch, representative office, factory, store, site), address (province/ward + street), minimum-wage region (default inherited from the ward's region table, overridable with reason), timezone, geo-coordinates and radius (m), allowed Wi-Fi/IP list, dependent tax code (13-digit) if the branch declares PIT separately, effective dates. | M | Geo-coordinates, radius and Wi-Fi/IP list are only used by mobile/web check-in, which is Phase 2. In Phase 1 they are optional fields (changed 2026-10-16, OD-9). |
| BR-CORE-006 | The system shall hold a **minimum-wage region assignment table** mapping each administrative unit (province/ward) to region I–IV, effective-dated, maintained globally by the platform and overridable per tenant only with approval and audit. | M | Amounts per region are owned by payroll. `[verify-legal]` |
| BR-CORE-007 | When an entity/location address or the global region table changes, the system shall recompute the effective region and publish `LocationRegionChanged` with effective date. | M | Triggers payroll/people minimum-wage checks. |
| BR-CORE-008 | Inter-entity relationships (parent/subsidiary, shared-service provider) shall be recordable for reporting and cross-entity data scope. | C | |

### 4.2 Organisation structure
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-010 | The system shall maintain a hierarchical org tree of org units with configurable **unit types** (e.g. Khối, Phòng, Ban, Bộ phận, Tổ/Nhóm) and depth up to at least 12 levels. | M | |
| BR-CORE-011 | Each org unit shall store: code (unique per tenant, immutable after use), name VI/EN, type, parent, legal entity, primary location, manager position (and/or manager user), cost centre, status, effective start/end, sort order. | M | A unit belongs to one legal entity; group-level virtual units allowed with `legal_entity = null`. |
| BR-CORE-012 | All org unit attributes and the parent relationship shall be **effective-dated**; the system shall answer "org tree as of date D" and "history of unit X". | M | |
| BR-CORE-013 | Org changes shall be made through **reorganisation packages** (create, rename, move, merge, split, close, change manager, change cost centre) with an effective date, impact analysis, approval and scheduled atomic activation (P-02). | M | Simple renames may bypass a package when tenant setting allows. |
| BR-CORE-014 | Merge/split/close shall require a **mapping** of affected positions and employees to target units; activation is blocked while unmapped active employees exist. | M | Mapping executed by people module on `ReorgActivated`. |
| BR-CORE-015 | Back-dated reorganisations shall require a dedicated permission and shall notify payroll and T&A of affected periods. | M | |
| BR-CORE-016 | The system shall render an interactive org chart (units and/or positions with incumbents, photos, vacancy flags), exportable to PDF/PNG. | S | Incumbent data from people. |
| BR-CORE-017 | The system shall support matrix / secondary reporting lines (functional manager) in addition to the line hierarchy. | C | Workflow resolver can target functional manager. |

### 4.3 Job architecture & cost centres
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-020 | The system shall maintain job families and **job titles** (chức danh) with code, name VI/EN, family, description/JD attachment, default grade range, occupational code, flag "heavy/hazardous/toxic job" (nặng nhọc, độc hại, nguy hiểm) with category. | M | Hazardous flag drives retirement-age, leave days and SI rules in other modules. `[verify-legal]` |
| BR-CORE-021 | The system shall maintain **grades / levels** (ngạch, bậc, cấp bậc) per grade structure, with ordering, and an optional reference salary range (min/mid/max) per grade per effective date. | M | Salary range is reference only; actual pay owned by payroll. |
| BR-CORE-022 | The system shall maintain **positions** (vị trí) as budgeted seats: code, job title, org unit, location, grade range, reports-to position, FTE budget, cost centre, status (proposed/active/frozen/closed), effective dates, critical-position flag. | M | |
| BR-CORE-023 | Position use shall be configurable per tenant: `position_control = strict` (every assignment needs a vacant position), `soft` (warning) or `off` (job title only). | M | Avoids forcing SMEs into position control. |
| BR-CORE-024 | The system shall expose position occupancy (budget, occupied, vacant, pipeline) combining Core budget with people (occupied) and recruitment (in-progress requisitions). | S | |
| BR-CORE-025 | The system shall maintain **cost centres** (code, name, legal entity, accounting segment code, owner, effective dates) and a default cost-centre per org unit and per position; cost-centre split by percentage shall be supported at assignment level (owned by people) using Core's list. | M | Payroll costing / GL export. |
| BR-CORE-026 | The system shall maintain an **employee category** code list (e.g. official, probation, seasonal, intern, expat, collaborator/CTV) usable as a condition in workflows, data scope and payroll rules. | M | |

### 4.4 Users, roles, permissions, data scope, SSO
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-030 | The system shall manage users (login, display name, email, mobile, linked employee id or none, locale, status, expiry date, MFA status, SSO subject). A user belongs to exactly one tenant. | M | Cross-tenant identities handled by saas-platform. |
| BR-CORE-031 | Permissions shall be defined per **function** (menu/action: view, create, edit, delete, approve, export, import, view-sensitive) and grouped into **roles**; tenants can clone system roles and create custom roles. | M | Modules register their permission catalog at deploy time. |
| BR-CORE-032 | A role assignment shall carry a **data scope**: all tenant; legal entity(ies); location(s); org unit(s) with/without subtree; own subtree as manager (derived); self only; custom rule (employee category, grade range). Effective permission = union of assignments. | M | Example: HR Officer → Legal Entity A; Manager → own subtree; Payroll → entities A+B. |
| BR-CORE-033 | The `LINE_MANAGER` and `SELF` scopes shall be derived automatically from org/position data and change automatically on reorganisation or transfer without admin action. | M | |
| BR-CORE-034 | **Field-level sensitivity**: salary, bank account, ID number, health info, family/dependent data and personal-data categories shall be maskable per role (`view-sensitive` permission). | M | PDP law: sensitive personal data. `[verify-legal]` |
| BR-CORE-035 | Data-scope filtering shall be enforced server-side on every query, report, export, API and notification recipient resolution, through a single Core authorisation service. | M | |
| BR-CORE-036 | Role assignments shall support start/end dates and an approval workflow for privileged roles (`CORE_ROLE_GRANT`). | S | |
| BR-CORE-037 | Segregation-of-duties rules shall be configurable (e.g. the same user cannot both prepare and approve a payroll run); violations block assignment or action. | S | |
| BR-CORE-038 | The system shall support local login (password policy: length, complexity, history, expiry, lockout), MFA (TOTP authenticator, email OTP; SMS OTP optional), and session management (idle timeout, concurrent session limit, revoke all). | M | Policy values are tenant settings. |
| BR-CORE-039 | The system shall support SSO via OIDC (Microsoft Entra ID, Google Workspace) and SAML 2.0, with claim mapping, JIT linking to existing users, optional SCIM 2.0 provisioning. | M (OIDC), S (SAML), C (SCIM) | |
| BR-CORE-040 | The system shall support **access delegation** ("act as" for a period, limited functions) distinct from approval delegation, fully audited. | C | |
| BR-CORE-041 | Platform support staff access to tenant data shall be possible only through time-boxed, tenant-approved break-glass sessions, logged in the tenant's audit log. | M | Mechanism owned by saas-platform; Core logs and enforces. |
| BR-CORE-042 | API clients (client-credentials) for integrations shall be manageable per tenant with scopes, IP allowlist, rotation and expiry. | S | |

### 4.5 Workflow & approval engine
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-050 | Core shall provide a generic workflow engine; each module registers **process types** with: request summary schema, condition fields, allowed actions, callback endpoint/event, whether auto-approve on timeout is allowed. | M | |
| BR-CORE-051 | Workflow definitions shall be configurable without code (form-based designer): applicability conditions (entity, org unit, location, employee category, grade, any registered request field), ordered steps, versioning, activation dates. | M | |
| BR-CORE-052 | Approver resolvers shall include: direct manager; manager N levels up; manager of a specified unit; holder of a position; users with role R within the requester's scope; named user; requester-selected approver from a filtered list; field from request (e.g. project manager); functional manager. | M | |
| BR-CORE-053 | Step types: single approver; any-of (first response wins); all-of (consensus); parallel branches with join; conditional skip; notify-only (FYI) step. | M | |
| BR-CORE-054 | Actions: submit, approve, reject (reason mandatory), return to requester / previous step, request more info, withdraw (requester, before final), forward (if allowed), comment, attach file, bulk approve. | M | |
| BR-CORE-055 | **Approval delegation**: a user can delegate pending and new tasks for a date range, optionally per process type, to another user; delegate acts "on behalf of"; delegation cannot chain more than one level; delegate must satisfy data scope rules for the request. | M | Auto-suggest delegation when approver has approved leave (event from T&A). |
| BR-CORE-056 | **Substitution / reassignment**: HR Admin can reassign any open task with mandatory reason; automatic fallback when the resolved approver is inactive, missing, or equals the requester (configurable: skip, go up one level, HR of entity). | M | |
| BR-CORE-057 | **SLA & escalation**: each step has an SLA in business hours/days computed using the requester's work calendar (from T&A); reminders at configurable thresholds; on breach: escalate, reassign, auto-approve (only if allowed) or auto-reject; SLA reports. | M | |
| BR-CORE-058 | The engine shall snapshot the approver chain at submission and re-resolve open steps on org change according to tenant setting. | M | |
| BR-CORE-059 | Approval shall be possible from web, mobile app, and email/Zalo action links (signed, single-use, expiring ≤72h, step-up auth for sensitive process types). | S | |
| BR-CORE-060 | The engine shall emit lifecycle events (`WorkflowSubmitted`, `StepAssigned`, `StepCompleted`, `WorkflowCompleted`, `WorkflowWithdrawn`, `SlaBreached`) and provide a unified "My tasks / My requests" inbox across modules. | M | |
| BR-CORE-061 | Full approval history (who, on behalf of whom, when, action, comment, IP/device) shall be immutable and printable as an approval trail on documents. | M | |

### 4.6 Notifications
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-070 | Core shall provide a notification service with channels: in-app inbox, email (tenant-configurable SMTP or platform sender with custom domain/DKIM), mobile push (FCM/APNs), **Zalo OA / ZNS** (template-based, tenant's own OA), SMS (brandname, optional). | M (in-app, email), S (push, Zalo), C (SMS) | ZNS templates need Zalo approval — lead time. |
| BR-CORE-071 | Notification templates shall be managed per event code, channel and language (VI/EN) with placeholders, tenant overrides of system templates, and preview. | M | |
| BR-CORE-072 | Users shall manage channel preferences per category, except mandatory categories (security, payslip availability, workflow tasks) which can only change channel, not be disabled. | S | |
| BR-CORE-073 | The service shall support scheduled sends, quiet hours, daily digests, deduplication, channel fallback, retries and delivery status tracking (sent/delivered/read/failed). | S | |
| BR-CORE-074 | Notification content containing sensitive data (e.g. salary) shall not be sent in clear over email/Zalo/SMS; only a secure deep link. | M | |
| BR-CORE-075 | Tenants shall be able to send announcements (bản tin nội bộ) to audiences defined by data scope (entity, unit, location, category) with read receipts. | C | |

### 4.7 Settings & master data
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-080 | Core shall host **global master data** maintained by the platform and read-only to tenants: administrative units (34 provinces/centrally-run cities and their wards/communes/special zones under the two-level local government model, effective from 2025-07-01), with history/mapping from the pre-merger 63 provinces and districts. | M | Resolution 202/2025/QH15 `[verify-legal]` |
| BR-CORE-081 | The administrative-unit model shall keep old codes and an **old→new mapping table** so that old-model addresses (opening-balance imports from the customer's records, old ID cards, birthplaces) remain valid (changed 2026-10-16, D-09), and addresses can be displayed in old or new form. | M | Birthplace / hometown use historic units. |
| BR-CORE-082 | Global lists shall include: countries/nationalities (ISO 3166), 54 ethnic groups of Vietnam, religions, ID document types (CCCD, CMND legacy, passport), banks and branches (with SBV/NAPAS codes, SWIFT), currencies (ISO 4217), languages, education levels, marital status, relationships, genders. | M | Ethnic list per GSO code list `[verify-legal]` |
| BR-CORE-083 | Tenants shall be able to define **custom code lists** (e.g. reasons for termination, training types) and extend selected global lists with tenant-local values, with code, VI/EN labels, sort order, active flag and effective dates. | M | |
| BR-CORE-084 | Tenant settings shall be hierarchical (tenant → legal entity → location) with typed values, validation, effective dating where flagged, and audit of changes. | M | e.g. employee code format, `auto_create_user`, `position_control`. |
| BR-CORE-085 | Core shall provide a **numbering service**: configurable sequences per tenant/entity/document type with patterns (prefix, year, entity code, zero-padded counter), gap-less option, and reset rules. | M | Employee codes, contract numbers, decision numbers. |
| BR-CORE-086 | Core shall provide **custom fields** (user-defined fields) on registered entities of all modules with type, validation, VI/EN labels, sensitivity flag, inclusion in import/export and reports. | S | |
| BR-CORE-087 | Global master data updates (e.g. new bank, new admin units) shall be distributed to all tenants with an effective date and a change notice; tenant-specific references to retired values shall be reported. | M | |

### 4.8 Audit log
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-090 | Core shall record an audit entry for every create/update/delete on business data of all modules: tenant, entity type, record id, action, changed fields with before/after values (sensitive values masked or encrypted), user, on-behalf-of user, timestamp (UTC + local), IP, user agent, channel (UI/API/import/system), correlation id. | M | |
| BR-CORE-091 | Security events shall be logged: login success/failure, MFA, password change, role/permission changes, SSO config changes, break-glass sessions, exports and downloads of personal data, viewing of sensitive fields. | M | |
| BR-CORE-092 | The audit log shall be append-only and tamper-evident (hash chain or WORM storage); no user including tenant admin can edit or delete entries. | M | |
| BR-CORE-093 | Audit search shall support filters (date range, user, entity, record, action, module) and export; access requires `AUDIT_VIEW` and is itself audited. | M | |
| BR-CORE-094 | Audit retention shall be configurable per category with a platform minimum; purge after retention must be logged. | M | See §5. |

### 4.9 Documents & files
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-100 | Core shall provide a document service: upload (size/type limits per tenant), malware scan before availability, storage in tenant-isolated object storage, metadata (owner module, entity, record id, document type, confidentiality, retention class), versioning. | M | |
| BR-CORE-101 | Access to a file shall follow the permission and data scope of the owning record; downloads via short-lived signed URLs; access to confidential files audited. | M | |
| BR-CORE-102 | Core shall provide **document templates** (DOCX/HTML → PDF) with merge fields supplied by modules (e.g. labour contract, decision, certificate), numbering via BR-CORE-085 and entity letterhead/seal. | M | Contract generation consumed by people. |
| BR-CORE-103 | Core shall integrate with e-signature / e-contract providers (licensed CA in VN) via an adapter interface, tracking signing status and storing the signed file and certificate evidence. | S | Law on Electronic Transactions 2023 `[verify-legal]` |
| BR-CORE-104 | Retention classes shall drive automatic archival/deletion with legal-hold override. | S | |

### 4.10 Import / export
| ID | Requirement | Pri | Notes |
|---|---|---|---|
| BR-CORE-110 | Core shall provide an import framework in which modules register import types (template definition, field mapping, validators, commit handler, undo handler, partial-commit allowed). | M | |
| BR-CORE-111 | Imports shall run as asynchronous jobs with staging, row-level validation messages (VI/EN), dry-run, downloadable error file, commit, progress tracking, and a job history; ≥50,000 rows per file. | M | |
| BR-CORE-112 | Templates shall be generated as Excel with dropdowns from code lists, data-type hints and an instruction sheet; lookups shall accept code or name. | M | |
| BR-CORE-113 | Export framework: any list view exportable to Excel/CSV/PDF honouring data scope and field masking; large exports run async and are delivered via in-app link; exports of personal data are audited. | M | |
| BR-CORE-114 | Core shall provide an **opening-balance import mode** of the import framework for go-live of any tenant: bulk load, bypass of workflow, external reference ids kept for traceability, effective dating as at the go-live cut-over, and a reconciliation report. It replaces the Oracle legacy migration mode (changed 2026-10-16, D-09). | M | Used by the BR-SAAS-069/071 templates. No legacy database migration. |
| BR-CORE-115 | Scheduled exports / outbound integrations (SFTP, webhook) shall be configurable per tenant. | C | |

---

## 5. Vietnam compliance rules

| # | Rule | Legal basis | Impact on Core |
|---|---|---|---|
| VN-01 | Since 2025-07-01 Vietnam has 34 provincial-level units and a two-level local government (province → commune/ward/special zone); district level abolished. | Resolution 202/2025/QH15; Law on Organisation of Local Government 72/2025/QH15 `[verify-legal]` | Admin-unit master data (BR-CORE-080/081); addresses model province + ward only for new data; keep historic three-level data. |
| VN-02 | Regional minimum wage applies by the location where the employer operates; 4 regions mapped to administrative units; where an employer has units in several regions, each unit applies its own region. | Labour Code 2019 Art. 91; Decree 74/2024/ND-CP, as updated for the new admin units by a 2025 decree (e.g. Decree 293/2025/ND-CP effective 2026-01-01) `[verify-legal]` | Region assignment at entity and location level, effective-dated (BR-CORE-005/006/007). |
| VN-03 | Enterprise tax code = 10 digits; dependent units 13 digits; from 2025-07-01 individuals use the 12-digit personal ID number as tax code. | Law on Tax Administration 38/2019/QH14; Circular 86/2024/TT-BTC `[verify-legal]` | Validation of MST (BR-CORE-003); people/payroll use personal ID as PIT code. |
| VN-04 | Employers register with social insurance and receive an SI unit code; declarations per unit. | Law on Social Insurance 41/2024/QH15 (effective 2025-07-01) `[verify-legal]` | SI unit code per legal entity (and per branch if branch declares separately). |
| VN-05 | Personal data processing requires consent, purpose limitation, protection of sensitive data (health, financial, biometric, ethnicity, religion…), data subject rights, and impact assessment. | Law on Personal Data Protection 91/2025/QH15 (effective 2026-01-01); Decree 13/2023/ND-CP and successor guidance `[verify-legal]` | Field sensitivity & masking (BR-CORE-034), audit of access (BR-CORE-091), consent records (provided to people), retention (BR-CORE-094/104). |
| VN-06 | Certain data of VN users must be stored in Vietnam under conditions in cybersecurity regulations. | Law on Cybersecurity 2018; Decree 53/2022/ND-CP `[verify-legal]` | Hosting region is a saas-platform decision; Core storage must be region-configurable. |
| VN-07 | Electronic documents, e-signatures and e-contracts (including e-labour contracts) are legally valid when meeting conditions; e-labour contract platforms may be subject to specific rules. | Law on Electronic Transactions 20/2023/QH15; Labour Code Art. 14; related decrees `[verify-legal]` | Document service + e-sign adapter (BR-CORE-102/103). |
| VN-08 | Accounting documents (incl. payroll as accounting evidence) retained at least 10 years; labour records retention per labour regulations. | Law on Accounting 88/2015/QH13; Decree 174/2016/ND-CP `[verify-legal]` | Retention classes and audit retention minimums. |
| VN-09 | Jobs classified as heavy, hazardous or dangerous are listed by the MOLISA (now MOHA) circular; affects retirement age, leave, allowances. | Circular 11/2020/TT-BLĐTBXH (and amendments) `[verify-legal]` | Job-title flag & category (BR-CORE-020). |
| VN-10 | Official documents use Vietnamese; bilingual allowed. Dates DD/MM/YYYY; currency VND without decimals. | Decree 30/2020/ND-CP (administrative documents) `[verify-legal]` | Localisation defaults, document templates. |
| VN-11 | Ethnic group classification uses the official list of 54 ethnic groups. | Decision 121-TCTK/PPCĐ (GSO) `[verify-legal]` | Master data list (BR-CORE-082). |

---

## 6. Key business entities

| Entity | Purpose | Key attributes |
|---|---|---|
| `company_group` | Tenant-level company profile | tenant_id, name, logo, default_locale, timezone, currency |
| `legal_entity` (+ `_version`) | Legal employer / taxpayer | code, legal_name_vi/en, tax_code, si_unit_code, reg_no, address(province_id, ward_id, street), legal_rep, min_wage_region, fiscal_year_start, status, eff_from/eff_to |
| `legal_entity_bank_account` | Payer bank accounts | legal_entity_id, bank_id, branch, account_no, account_name, purpose (salary, SI, tax), default flag |
| `location` (+ `_version`) | Branch / work location | code, name, type, legal_entity_id, address, min_wage_region (derived/override), timezone, lat/long, radius_m, wifi/ip list, dependent_tax_code, eff dates |
| `admin_unit` | Global province/ward master | code, name, level, parent_id, valid_from/valid_to, replaced_by |
| `admin_unit_mapping` | Old→new admin unit mapping | old_unit_id, new_unit_id, effective_date, mapping_type (merge/split/rename) |
| `min_wage_region_map` | Admin unit → region | admin_unit_id, region (I–IV), eff_from/eff_to, source decree |
| `org_unit_type` | Unit type catalogue | code, name, level hint |
| `org_unit` (+ `_version`) | Org tree node | code, name, type, parent_id, legal_entity_id, location_id, manager_position_id, cost_centre_id, status, eff dates |
| `reorg_package` / `reorg_change` / `reorg_mapping` | Effective-dated reorganisation | effective_date, status, workflow_instance_id; change type, source/target units; position/employee mappings |
| `job_family`, `job_title` | Job architecture | code, name, family, hazardous_category, default grade range, JD doc |
| `grade_structure`, `grade` | Levels / bậc | code, order, ref_salary_min/mid/max (eff-dated) |
| `position` (+ `_version`) | Budgeted seat | code, job_title_id, org_unit_id, location_id, grade range, reports_to_position_id, fte_budget, cost_centre_id, status, critical flag |
| `cost_centre` | Costing object | code, name, legal_entity_id, gl_segment, owner, eff dates |
| `employee_category` | Category code list | code, name, flags (is_probation, is_expat…) |
| `user_account` | Login identity | login, email, mobile, employee_id (nullable), locale, status, expiry, mfa, sso_subject |
| `role`, `permission`, `role_permission` | RBAC | role code, system/custom; function code, action |
| `role_assignment` | User ↔ role with scope | user_id, role_id, scope_type, scope_values, eff dates, granted_by |
| `sod_rule` | Segregation of duties | conflicting permissions |
| `identity_provider` | SSO config | protocol, metadata, claim mapping, enforcement |
| `api_client` | Integration client | client_id, scopes, ip allowlist, expiry |
| `process_type` | Workflow type registered by module | code, module, summary schema, callback, flags |
| `workflow_definition` / `workflow_step_def` | Configured flows | conditions, version, steps (resolver, type, SLA, escalation) |
| `workflow_instance` / `workflow_task` / `workflow_action` | Runtime | request ref, snapshot, status; assignee, on_behalf_of, due_at; action, comment, ts |
| `approval_delegation` | Delegation | delegator, delegate, process types, from/to |
| `notification_template` / `notification` / `notification_delivery` | Messaging | event code, channel, locale, body; recipient; channel status |
| `user_notification_pref` | Preferences | category, channels, quiet hours |
| `code_list` / `code_value` | Generic code lists | list code, value code, labels, scope (global/tenant), eff dates |
| `country`, `ethnicity`, `religion`, `bank`, `bank_branch`, `currency`, `id_doc_type` | Global master data | ISO / official codes |
| `setting_definition` / `setting_value` | Tenant settings | key, type, level, value, eff dates |
| `sequence` | Numbering | pattern, scope, current value, reset rule |
| `custom_field_def` / `custom_field_value` | UDFs | target entity, type, validation, sensitivity |
| `audit_event` | Audit log | see BR-CORE-090 |
| `document` / `document_version` / `doc_template` | Files & templates | owner ref, type, confidentiality, retention class, storage key, hash |
| `import_type` / `import_job` / `import_row` | Import framework | template, status, counts, errors |
| `export_job` | Async exports | query, format, status, file |

---

## 7. Cross-module interfaces

Interface style: synchronous internal API for queries (read-only Core services), asynchronous domain events (outbox → message bus) for state changes. All interfaces are tenant-scoped and data-scope aware.

### 7.1 Consumes

| # | What | From module | When / trigger | Used for |
|---|---|---|---|---|
| C-01 | `TenantProvisioned` (tenant id, plan, owner contact, locale, hosting region) | saas-platform | Tenant creation | Create company group, Owner user, seed system roles, templates, settings |
| C-02 | Plan entitlements & limits (max legal entities, max users, enabled modules/features, channels allowed e.g. Zalo/SMS, storage quota) | saas-platform | On login/config, on `PlanChanged` | Enforce limits (BR-CORE-001), hide disabled features, gate channels |
| C-03 | `TenantSuspended` / `TenantReactivated` / `TenantTerminated` | saas-platform | Lifecycle change | Block logins, stop schedulers/notifications, start data export/purge |
| C-04 | Global master-data releases (admin units, region map, banks, nationalities…) | saas-platform (platform operator) | On publication | Update global lists (BR-CORE-087) |
| C-05 | Break-glass support session grant | saas-platform | On support request approved by tenant | Enforce and audit (BR-CORE-041) |
| C-06 | Platform services: email/SMS/Zalo provider accounts, object storage, KMS keys, message bus | saas-platform | Runtime | Notification dispatch, document storage, encryption |
| C-07 | `EmployeeHired` / `EmployeeRehired` (employee id, person name, work email, mobile, hire date, entity) | people | On hire effective | Auto-create/reactivate user (P-04) |
| C-08 | `EmployeeTerminated` (employee id, last working day) | people | On separation effective | Deactivate user, revoke sessions, reassign tasks & delegations |
| C-09 | `AssignmentChanged` (employee, position, org unit, location, legal entity, manager, category, grade, effective date) | people | Transfer / promotion | Recompute derived scopes (SELF/LINE_MANAGER), re-resolve open workflow steps |
| C-10 | Current assignment lookup: employee → position, org unit, manager, legal entity, location, category, grade (as of date) | people | Synchronous at workflow resolution, scope evaluation, notification audience | Approver resolvers, data scope, audiences |
| C-11 | Position occupancy (occupied FTE per position, incumbents) | people | On demand / `AssignmentChanged` | Vacancy, org chart, position-control checks |
| C-12 | Reorg mapping execution result (employees moved, failures) | people | After `ReorgActivated` | Close reorg package, report failures |
| C-13 | Merge-field data for documents (employee, contract) | people / payroll / recruitment | On document generation | Document templates (BR-CORE-102) |
| C-14 | Work calendar & public holidays per legal entity/location/employee | time-attendance | Workflow SLA computation | Business-time SLA (BR-CORE-057) |
| C-15 | Approved leave / absence of a user (period) | time-attendance | On leave approval | Suggest/auto-activate approval delegation (BR-CORE-055) |
| C-16 | Payroll period lock status per legal entity | payroll | On back-dated reorg / entity attribute change | Warn/block back-dated changes (BR-CORE-015) |
| C-17 | In-progress requisitions per position | recruitment | On demand | Position pipeline (BR-CORE-024), reorg impact analysis |
| C-18 | Process-type registrations, permission catalogs, import types, notification event codes, custom-field target entities, document types | all modules (people, time-attendance, payroll, recruitment, saas-platform) | Deploy / startup | Engines' configuration |
| C-19 | Workflow callback acknowledgements (business effect applied / failed) | all modules | After `WorkflowCompleted` | Close instance or raise error task |
| C-20 | Import validators and commit/undo handlers | all modules | During import job | Import framework |
| C-21 | Candidate identity for external notifications (email/phone, consent) | recruitment | Candidate notifications | Send to non-users |

### 7.2 Provides

| # | What | To module(s) | When | Notes |
|---|---|---|---|---|
| P-01 | Legal entity master & as-of-date lookup: tax code (MST), SI unit code, address, legal rep, bank accounts, fiscal year, status | people, payroll, recruitment, time-attendance | Sync query; event `LegalEntityChanged` | Payroll declarations, contracts, offer letters |
| P-02 | Location master: address, region, timezone, geo-fence (lat/long, radius), Wi-Fi/IP list, dependent tax code | time-attendance (check-in), payroll (region, branch PIT), people (work place on contract), recruitment (job location) | Sync query; `LocationChanged` | |
| P-03 | **Minimum-wage region** of a location/entity as of date; `LocationRegionChanged` event | payroll (min-wage & SI floor checks), people (contract salary validation) | Sync; event | Amounts owned by payroll |
| P-04 | Org tree as of date; unit details; ancestors/descendants; unit manager; `OrgUnitChanged`, `ReorgActivated` (with mapping table) | people, time-attendance, payroll, recruitment | Sync; events | People executes employee mapping |
| P-05 | Job titles (incl. hazardous flag & category), job families, grades & reference salary ranges | people, payroll, recruitment, time-attendance (extra leave for hazardous jobs) | Sync; `JobTitleChanged`, `GradeChanged` | |
| P-06 | Positions: definition, budget FTE, status, reports-to, cost centre; position-control mode; `PositionChanged` | people (assignment validation), recruitment (requisition against vacancy) | Sync; event | |
| P-07 | Cost centres & default cost centre per unit/position | payroll (costing/GL), people (assignment split) | Sync | |
| P-08 | Employee category list | people, time-attendance, payroll, recruitment | Sync | |
| P-09 | Authentication (SSO/OIDC tokens), current user context (user, employee id, tenant, locale, roles) | all modules, frontend, mobile | Every request | |
| P-10 | **Authorisation & data-scope service**: `can(user, permission, record)`, scope filter predicate for queries, field-masking policy | all modules incl. reporting | Every request / query | Single enforcement point (BR-CORE-035) |
| P-11 | User lifecycle events `UserCreated`, `UserDeactivated` | people (link user ↔ employee), saas-platform (seat counting) | On change | |
| P-12 | **Workflow engine**: submit/withdraw API, task inbox, `WorkflowCompleted` callback with outcome & approval trail, SLA events | people (contracts, changes, separation, expenses, appraisal), time-attendance (leave, OT, attendance adjustment, shift swap), payroll (payroll run, adjustments, bank payment file), recruitment (requisition, offer), core (reorg, position, role grant) | Runtime | |
| P-13 | Approval delegation lookup (who acts for whom, when) | all modules | Runtime | |
| P-14 | **Notification service**: send(event code, recipients, payload), templates, channels incl. Zalo ZNS, in-app inbox, delivery status | all modules (e.g. payslip ready, leave approved, contract expiring, interview invite) | Runtime | |
| P-15 | Global master data: admin units (34 provinces + wards, historic units, mapping), countries/nationalities, ethnicities, religions, ID doc types, banks/branches with codes, currencies, education levels, relationships | people (addresses, ID, bank accounts, dependants), payroll (bank payment files), recruitment (candidate data), saas-platform (tenant address) | Sync | |
| P-16 | Generic code lists & tenant custom lists | all modules | Sync | |
| P-17 | Tenant/entity/location settings service | all modules | Sync | |
| P-18 | Numbering service (employee code, contract no., decision no., payroll run no., requisition no.) | people, payroll, recruitment, time-attendance | Runtime | |
| P-19 | Custom field framework | all modules | Runtime | |
| P-20 | Audit service: record(event), query API; security event log | all modules; saas-platform (platform audit, break-glass) | Runtime | |
| P-21 | Document service: upload/download, virus scan, versioning, retention, signed URLs; document templates → PDF; e-signature adapter | people (contracts, decisions, certificates, employee docs), payroll (payslip PDFs, declarations), recruitment (CVs, offer letters), time-attendance (leave evidence) | Runtime | |
| P-22 | Import/export framework incl. opening-balance import mode (changed 2026-10-16, D-09) | all modules | Runtime | |
| P-23 | Business-day/time calculator (wraps T&A calendar) | all modules for due dates | Runtime | Depends on C-14 |
| P-24 | Tenant usage metrics: active users, legal entities, storage used, notifications sent per channel | saas-platform (billing, quota) | Daily | |
| P-25 | Personal-data access log & export of a user's audit trail (for data subject requests) | people (DSR handling), saas-platform | On request | VN-05 |

---

## 8. Reports & analytics

| # | Report | Audience | Content |
|---|---|---|---|
| R-01 | Org structure report / chart as of date | HR, management | Tree with unit, manager, headcount (from people), vacancies |
| R-02 | Org change history | HR, auditor | Reorg packages and unit-level changes in a period |
| R-03 | Position register & vacancy | HR, C&B, recruitment | Budget vs occupied vs pipeline per unit/entity |
| R-04 | Legal entity & location register | HR, finance | MST, SI code, region, addresses, effective history |
| R-05 | User & access review | Admin, auditor | Users, roles, scopes, last login, dormant accounts, privileged roles, SoD conflicts — supports periodic access recertification |
| R-06 | Login & security events | IT, auditor | Failed logins, MFA, lockouts, SSO errors |
| R-07 | Workflow SLA & throughput | HR, management | Requests by process type, average approval time, SLA breaches, pending by approver |
| R-08 | Pending approvals by approver / delegation in force | Managers, HR | |
| R-09 | Notification delivery | Admin | Sent/failed per channel, Zalo ZNS cost estimate |
| R-10 | Audit trail report | Auditor | Filtered changes with before/after |
| R-11 | Personal-data access report | DPO / auditor | Who viewed/exported sensitive data |
| R-12 | Import/export job log | Admin | Jobs, rows, errors |
| R-13 | Master data change impact | Admin | Records referencing retired admin units/banks |

---

## 9. Non-functional requirements

| ID | Requirement | Pri |
|---|---|---|
| NFR-CORE-001 | Tenant isolation: every Core table carries `tenant_id` (except global master data) and is protected by row-level security; no API returns cross-tenant data. Isolation tested automatically. | M |
| NFR-CORE-002 | Authorisation/data-scope check adds ≤20 ms p95 per request; scope predicates must be index-friendly (e.g. materialised org paths/closure table). | M |
| NFR-CORE-003 | Org tree "as of date" query for a 10,000-employee tenant with 2,000 units returns in ≤1 s p95. | M |
| NFR-CORE-004 | Workflow engine: submit ≤500 ms p95; ≥50 submissions/s per platform node; no lost tasks (transactional outbox, at-least-once events, idempotent callbacks). | M |
| NFR-CORE-005 | Notifications: in-app delivered ≤5 s; email/push/Zalo queued ≤1 min p95 at peak (e.g. payslip publishing for 10,000 employees completes ≤30 min). | M |
| NFR-CORE-006 | Login availability 99.9% monthly; SSO token validation does not depend on synchronous IdP calls per request. | M |
| NFR-CORE-007 | Passwords hashed with a modern adaptive algorithm (Argon2id/bcrypt); secrets and sensitive fields encrypted at rest with per-tenant keys (KMS); TLS 1.2+ in transit. | M |
| NFR-CORE-008 | Audit log write is part of the business transaction or guaranteed via outbox; audit storage tamper-evident; audit query of 1 year for one record ≤3 s. | M |
| NFR-CORE-009 | Import 50,000 rows validate ≤5 min; export 100,000 rows ≤5 min async. | S |
| NFR-CORE-010 | Files: max upload configurable (default 20 MB), malware scan ≤60 s; signed URLs expire ≤15 min. | M |
| NFR-CORE-011 | Localisation: all UI labels, templates, code list labels, validation messages in VI and EN; Vietnamese diacritics fully supported in search (accent-insensitive search) and exports (UTF-8, Excel-compatible). | M |
| NFR-CORE-012 | Accessibility WCAG 2.1 AA for Core screens; responsive for mobile approval. | S |
| NFR-CORE-013 | All Core configuration (roles, workflows, templates, settings, code lists) exportable/importable between environments of the same tenant (e.g. UAT → PROD). | S |
| NFR-CORE-014 | Security: OWASP ASVS L2; brute-force protection; rate limiting per tenant/user/API client. | M |
| NFR-CORE-015 | Effective-dated activation jobs run at 00:00 Asia/Ho_Chi_Minh and are idempotent and re-runnable. | M |

---

## 10. Assumptions, open questions, risks

### 10.1 Assumptions
| # | Assumption |
|---|---|
| A-01 | A user belongs to one tenant; consultants serving several tenants get separate accounts (multi-tenant identity is a saas-platform topic). |
| A-02 | An employee's legal employer is the legal entity of their assignment's org unit / position; people module stores it explicitly and Core validates consistency. |
| A-03 | Minimum-wage **amounts**, SI caps and PIT rates live in payroll; Core only provides the region of a location. |
| A-04 | Public holidays and work calendars are mastered by time-attendance; Core consumes them for SLA. |
| A-05 | Global master data (admin units, banks) is curated by the platform operator and pushed to all tenants. |
| A-06 | Zalo OA/ZNS is used with the tenant's own OA and ZNS account (tenant bears ZNS cost). |

### 10.2 Open questions
| # | Question | Owner |
|---|---|---|
| Q-01 | Is the workflow engine built in-house or based on an embedded BPMN engine (Flowable/Camunda)? Affects designer UX and parallel-branch support. | Solution architect |
| Q-02 | Does each legal entity always have its own SI unit code, or can branches declare SI separately (per-branch SI code)? Need consultant input; model currently allows branch-level codes. | BA Payroll / legal |
| Q-03 | Should the region table be overridable per tenant at all, or strictly platform-controlled? | PM / legal |
| Q-04 | Exact list of the post-merger wards/communes and the official code set (GSO code list) and its update cadence. | Platform operator `[verify-legal]` |
| Q-05 | Who owns "headcount plan" vs "position budget" — Core positions or recruitment staffing plans? Risk of duplication. | BA Core + BA Recruitment |
| Q-06 | E-signature/e-contract provider(s) to support in MVP? | PM |
| Q-07 | Are group-level (cross-entity) org units required for MVP (e.g. a shared HR department serving several entities)? | Pilot tenant |
| Q-08 | Data residency: must all tenant data and files stay in VN-hosted infrastructure? | saas-platform / legal |
| Q-09 | Is SCIM provisioning needed by the pilot (enterprise tenants with Entra ID)? | Pilot tenant |
| Q-10 | Should payslip notifications via Zalo include amounts (customer demand) given BR-CORE-074? | PM / DPO |

### 10.3 Risks
| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R-01 | Administrative-unit merger data incomplete/changing; addresses in customers' opening-balance files use the old 63-province/district model (changed 2026-10-16, D-09) | Wrong addresses, region assignment, declarations | Keep historic units + mapping table; import report of unmapped addresses; platform-curated updates |
| R-02 | Region mapping after merger differs from intuition (merged provinces contain multiple regions at ward level) | Underpayment vs minimum wage | Region at ward level, effective-dated, verified by consultant `[verify-legal]` |
| R-03 | Data-scope engine becomes a performance bottleneck | Slow lists/reports | Closure tables, cached scope predicates, NFR-CORE-002 load tests |
| R-04 | Workflow engine over-generalised; slow to deliver for MVP | MVP delay | MVP subset: sequential + any-of + delegation + SLA; parallel branches Phase 2 |
| R-05 | Zalo ZNS template approval lead time and cost | Channel not ready at go-live | Email/in-app as MVP baseline; Zalo as S |
| R-06 | Effective-dated reorg interplay with payroll retro and T&A | Incorrect costing/attendance | Period-lock check (C-16), back-date permission, events with effective dates |
| R-07 | PDP law (2026) compliance obligations expand (DPIA, consent logs) | Legal exposure | Sensitivity flags, access audit, DSR support (P-25); legal review |
| R-08 | Module teams bypass Core authorisation service with own checks | Data leaks across scopes | Architecture rule + automated tests on every API |
