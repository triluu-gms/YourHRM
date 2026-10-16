# Core Backlog: Phase 1 (v0.1, 2026-10-16)

| Item | Value |
|---|---|
| Module | `core` ([01-core.md](../brd/modules/01-core.md) v0.2) |
| Scope | Every **P1** BR-CORE and NFR-CORE of [mvp-scope.md](../brd/mvp-scope.md) §9.1 (45 BR + 13 NFR) |
| Rules | [estimation-guide.md](estimation-guide.md) v0.1 (Fibonacci scale §4, done means §5) |
| Author | BA – Core (estimator) |
| Status | Draft for estimation review |

## 0. Reading notes

**Engines built here (once).** Core owns and estimates in full: org / legal entity / location / job / grade / cost-centre master data; users, roles, permissions, data scopes and field masking (authorisation service); the **workflow & approval engine**; the **notification centre** (event codes, vendor templates, recipient resolution, in-app inbox, e-mail hand-off to the SaaS delivery channel); the **audit service** (business side: client, store, hash chain, search); the **document service** (upload/versions/access, HTML → PDF/A, DOCX templates → PDF, **password-protected PDF** used by payroll payslips); the **import engine** (registry, staging, dry-run, all-or-nothing commit, rollback, Excel templates, opening-balance mode, reconciliation report); the global master data store (34 provinces + wards, old → new mapping, banks, nationalities, …); numbering; settings; business-day calculator. Other backlogs only register with these engines (process types, permission catalogues, templates, import types, document types, merge fields) and estimate just that registration.

**Not estimated here.** Authentication, MFA, sessions, Keycloak (SaaS / TECH); job scheduler and queue (SaaS / TECH); Legal Parameter Service (SaaS runtime in TECH); object storage, SMTP relay and channel adapters (SaaS); reporting and list-export framework incl. former BR-CORE-113 (SaaS, BR-SAAS-046); RLS framework, outbox / event bus, envelope encryption, CI/CD, observability, rate limiting (TECH); opening-balance onboarding orchestration, cut-over lock and adjustment batches (SaaS, BR-SAAS-071/072/088).

**Phase-1 cuts that shape stories (mvp-scope §9.1, R2-19 Option B).** Positions, position control and reorg packages are P2, so P1 org changes are effective-dated edits and no "position holder" resolver is built. Workflow definitions are **vendor-seeded** (no designer, BR-CORE-051 P2), steps are **sequential single-approver** only (BR-CORE-053 P2), no SLA/escalation (BR-CORE-057 P2), no approval delegation (BR-CORE-055 P2), no re-resolve on reorg (BR-CORE-058 P2). Notification templates are vendor-maintained VI/EN with no tenant overrides (BR-CORE-071 P2); no user preferences or digests (BR-CORE-072/073 P2). Code lists are vendor-maintained (BR-CORE-083 P2). No custom fields (BR-CORE-086 P2). Audit retention is a fixed platform minimum (BR-CORE-094 P2). Channels: in-app + e-mail only.

**Dependency placeholders.** The TECH and SaaS backlogs are not yet numbered. Dependencies on them are written as `TECH:<enabler>` or `SAAS:<capability>` and are replaced with `US-TECH-…` / `US-SAAS-…` IDs at PM consolidation:
`TECH:skeleton` (walking skeleton, module layout, API conventions), `TECH:rls` (tenancy context + RLS framework), `TECH:outbox` (transactional outbox, DB-backed event bus), `TECH:jobq` (job queue / scheduler API, 00:00 ICT triggers), `TECH:kms` (envelope encryption), `TECH:lps` (Legal Parameter Service runtime `getParam`), `TECH:perf` (performance test harness), `TECH:i18n` (ICU bundles, `unaccent` + trigram search), `SAAS:identity` (authenticated identity C-22, session revocation), `SAAS:provision` (`TenantProvisioned`, entitlements C-01/C-02), `SAAS:storage` (object storage + ClamAV, PR-16), `SAAS:email` (e-mail delivery adapter), `SAAS:backoffice` (master-data release workflow C-04), `SAAS:supportgrant` (break-glass grant C-05), `SAAS:report` (reporting/export framework). People and T&A dependencies (`people.EmployeeHired`, assignment lookup C-10, T&A calendar C-14) are written as `PPL:` / `ATT:`.

## 1. Epics

| Epic ID | Name | Goal | Stories | SP |
|---|---|---|---|---|
| EP-CORE-01 | Company, legal entities & locations | Statutory employer master (MST, SI code, tax authority, OA-OD decision), effective-dated, with branches and minimum-wage region resolved from the legal pack | 9 | 39 |
| EP-CORE-02 | Organisation structure | Effective-dated org tree with as-of queries, events and import | 6 | 28 |
| EP-CORE-03 | Job architecture, cost centres & categories | Job titles (hazardous flag), grade catalogue, cost centres, employee categories, lookups and imports | 6 | 23 |
| EP-CORE-04 | Users & authorisation | Tenant users driven by People events; roles, data scopes, derived manager/self scopes, field masking, single server-side authorisation service, break-glass enforcement | 13 | 73 |
| EP-CORE-05 | Workflow & approval engine | Generic sequential approval engine used by every module: process types, seeded definitions, resolvers, actions, fallback, inbox, immutable history | 14 | 80 |
| EP-CORE-06 | Notification centre | Event codes, VI/EN templates, recipient resolution, in-app inbox, e-mail via SaaS channel, sensitive-content guard incl. payslip exception | 6 | 33 |
| EP-CORE-07 | Master data, settings, numbering, calendar | 34-province admin units with old→new mapping, global code lists and banks, hierarchical settings, numbering service, business-day calculator | 8 | 47 |
| EP-CORE-08 | Audit service | Single tenant audit store: data-change and security events, tamper evidence, search and export | 5 | 25 |
| EP-CORE-09 | Document service | Files with record-scope access and signed URLs, PDF/A generation, DOCX templates → PDF, password-protected PDF | 6 | 37 |
| EP-CORE-10 | Import engine | Single import engine: registry, async staging and dry-run, all-or-nothing commit, rollback, Excel templates, opening-balance mode, reconciliation report | 9 | 53 |
| EP-CORE-11 | Core non-functional hardening | RLS and isolation tests on Core, encryption of Core identifiers, effective-dating job framework, localisation | 4 | 16 |
| | **Total** | | **86** | **454** |

## 2. Stories

### EP-CORE-01 Company, legal entities & locations

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-001 | EP-CORE-01 | As a Tenant Owner, I want my company group profile (name, logo, default language, timezone, currency) created at provisioning and editable so that the tenant starts with correct defaults. AC: `TenantProvisioned` creates the profile idempotently; logo stored via document service; edits audited. | BR-CORE-001 | 3 | SAAS:provision, US-CORE-100, US-CORE-090 | Seeds system roles and settings via US-CORE-033 / US-CORE-085 |
| US-CORE-002 | EP-CORE-01 | As a Tenant Owner, I want to create and maintain legal entities (legal name VI/EN, short name, MST, SI unit code, business registration, registered address province/ward, phone, e-mail, legal representative, fiscal year start) so that every statutory output has a correct employer. AC: MST is 10 digits or `NNNNNNNNNN-NNN` and unique per tenant; creation blocked above the plan's legal-entity limit with a plan-limit message; provisioning API creates the first entity. | BR-CORE-001, BR-CORE-002, BR-CORE-003 | 5 | US-CORE-001, US-CORE-080, SAAS:provision | MST format `[verify-legal]` but rule is clear (no extra step) |
| US-CORE-003 | EP-CORE-01 | As an HR Admin, I want statutory legal-entity attributes (tax code, SI code, region, address, status) to be effective-dated with history so that payroll uses the value valid in the period. AC: future-dated change creates a new version, previous version end-dated D−1; history view; as-of query returns the version for any date. | BR-CORE-004 | 5 | US-CORE-002, US-CORE-122 | Version pattern reused by locations and org units |
| US-CORE-004 | EP-CORE-01 | As a C&B Specialist, I want to record on each legal entity, effective-dated, the managing tax authority, the PIT declaration frequency per tax year and the OA-OD rate decision (rate, decision no., authority, valid from/to, evidence file) so that payroll declares and contributes correctly. AC: OA-OD rate must be a value the legal pack allows; one frequency per tax year; evidence file mandatory for a reduced rate. | BR-CORE-002 (GAP-049, R3-10) | 5 | US-CORE-003, TECH:lps, US-CORE-100 | OA-OD allowed values `[verify-legal]`, read from legal pack |
| US-CORE-005 | EP-CORE-01 | As a C&B Specialist, I want to keep the payer bank accounts of a legal entity (bank, branch, account no., name, purpose salary/SI/tax, default) and its seal and authorised-signatory images so that bank files and documents use the right payer data. AC: bank/branch from global list; one default per purpose; images stored as confidential documents. | BR-CORE-002 | 3 | US-CORE-002, US-CORE-083, US-CORE-100, US-CORE-121 | Account numbers encrypted (US-CORE-121) |
| US-CORE-006 | EP-CORE-01 | As a payroll / people developer, I want a legal-entity as-of lookup API and a `core.LegalEntityChanged` event so that other modules read period-correct employer data. AC: `GET …/legal-entities/{id}?asOf=`; event carries effective date and changed fields; contract test published. | BR-CORE-004 | 3 | US-CORE-003, TECH:outbox | Interface P-01 |
| US-CORE-007 | EP-CORE-01 | As an HR Admin, I want to maintain branches / work locations (code, name, type, legal entity, address province/ward + street, timezone, dependent 13-digit tax code, optional geo-coordinates/radius/IP list), effective-dated, so that work place, PIT branch and region are known. AC: exactly one legal entity; dependent tax code format checked; geo fields optional and hidden behind a P2 flag. | BR-CORE-005 | 5 | US-CORE-002, US-CORE-080, US-CORE-122 | Geo-fence data used only in P2 (OD-9) |
| US-CORE-008 | EP-CORE-01 | As a payroll developer, I want the minimum-wage region (I–IV) of a location / entity resolved as of a date from the legal pack's admin-unit → region map so that minimum-wage and SI floor checks use the right region. AC: reads `getParam` with a local read-only cache keyed by pack version; override allowed only where the pack flags "law permits", with reason; API returns region and source (map / override). | BR-CORE-006 | 5 | US-CORE-007, TECH:lps | Map at ward level `[verify-legal]`; Core does not master it |
| US-CORE-009 | EP-CORE-01 | As a payroll developer, I want the region recomputed when an address changes or `saas.LegalParameterPackPublished` arrives, and `core.LocationChanged` (`LocationRegionChanged`) published with the effective date, so that minimum-wage checks rerun. AC: only locations whose region actually changes emit an event; recompute job is idempotent and re-runnable; run log kept. | BR-CORE-007, NFR-CORE-015 | 5 | US-CORE-008, US-CORE-122, TECH:outbox | |

### EP-CORE-02 Organisation structure

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-010 | EP-CORE-02 | As an HR Admin, I want a catalogue of org unit types (Khối, Phòng, Ban, Bộ phận, Tổ/Nhóm…) with a level hint so that the tree uses the tenant's vocabulary. AC: VI/EN labels, sort order, active flag; seeded defaults. | BR-CORE-010 | 2 | TECH:skeleton | |
| US-CORE-011 | EP-CORE-02 | As an HR Admin, I want to create and edit org units (code, name VI/EN, type, parent, legal entity or group-level null, primary location, manager user, cost centre, status, sort order) so that the organisation is modelled. AC: code unique per tenant and immutable after first use; parent must be in the same legal entity or a group unit; no cycles; depth ≥ 12 levels. | BR-CORE-010, BR-CORE-011 | 3 | US-CORE-010, US-CORE-002, US-CORE-007 | Manager *position* is P2 (positions); P1 stores manager user |
| US-CORE-012 | EP-CORE-02 | As an HR Admin, I want every org unit attribute and its parent to be effective-dated (rename, move, change manager, change cost centre, close; past, today or future) so that history and reporting are correct. AC: new version end-dates the previous at D−1; "history of unit X"; closing is blocked while People reports active assignments in the unit; conflicting future versions are rejected. | BR-CORE-012 | 5 | US-CORE-011, US-CORE-122, PPL:assignment-lookup | Replaces reorg packages (P2) in P1 |
| US-CORE-013 | EP-CORE-02 | As an HR Admin and as a module developer, I want an "org tree as of date D" service (tree UI, ancestors / descendants / subtree API, unit manager) backed by a closure table or materialised paths so that scopes, resolvers and reports are fast. AC: 2,000 units / 10,000 employees as-of query ≤ 1 s p95; subtree predicate index-friendly for the authorisation service; tree UI with search and as-of date picker. | BR-CORE-010, BR-CORE-012, NFR-CORE-003 | 8 | US-CORE-012, TECH:perf | Closure maintenance under effective dating is the hard part |
| US-CORE-014 | EP-CORE-02 | As a people / T&A developer, I want `core.OrgUnitChanged` published with the effective date when a version becomes effective (00:00 ICT for future-dated changes) so that dependent data reacts at the right time. AC: event emitted once per version activation; activation job idempotent and re-runnable; back-dated edits emit immediately with the past effective date. | BR-CORE-012, NFR-CORE-015 | 5 | US-CORE-012, US-CORE-122, TECH:outbox | |
| US-CORE-015 | EP-CORE-02 | As an HR Admin, I want an org unit import type (template, validators, commit / undo handlers) so that the initial tree is loaded from Excel at go-live. AC: parent references resolved within the file in dependency order; external reference id kept; works in opening-balance mode. | BR-CORE-110, BR-CORE-114 | 5 | US-CORE-012, US-CORE-110, US-CORE-116 | Template listed in BR-SAAS-069 |

### EP-CORE-03 Job architecture, cost centres & categories

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-020 | EP-CORE-03 | As an HR Admin, I want job families and job titles (code, name VI/EN, family, description / JD attachment, default grade range, occupational code, heavy/hazardous/dangerous flag with category) so that people, T&A and payroll can apply hazardous-job rules. AC: hazardous category from a vendor code list; JD via document service; change of flag emits `core.JobCatalogueChanged`. | BR-CORE-020 | 5 | US-CORE-021, US-CORE-082, US-CORE-100 | Base 3 + 1 step: hazardous category list `[verify-legal]` (Circular 11/2020 and amendments) |
| US-CORE-021 | EP-CORE-03 | As a C&B Specialist, I want the grade catalogue (grade structures, grade codes, names, ordering), effective-dated, so that payroll pay scales and bands attach to a stable grade list. AC: no salary amounts stored; ordering unique within a structure; history kept. | BR-CORE-021 | 5 | US-CORE-122 | Salary bands are payroll (IF-PAY-P-02) |
| US-CORE-022 | EP-CORE-03 | As a Finance user, I want cost centres (code, name, legal entity, GL segment code, owner, effective dates) and a default cost centre per org unit so that payroll costing and the GL journal post correctly. AC: cost centre belongs to one legal entity; org unit default must match its legal entity; inactive cost centres not selectable. | BR-CORE-025 | 3 | US-CORE-002, US-CORE-011 | Reference example "Cost centres" = 3; default per position is P2 |
| US-CORE-023 | EP-CORE-03 | As an HR Admin, I want the employee category code list (official, probation, seasonal, intern, expat, collaborator…) with flags (is_probation, is_expat…) so that workflows, scopes and payroll rules can use it as a condition. AC: seeded list; flags typed; usable as condition field by US-CORE-051 and US-CORE-035. | BR-CORE-026 | 2 | US-CORE-082 | |
| US-CORE-024 | EP-CORE-03 | As a module developer, I want lookup APIs for job titles, families, grades, cost centres and employee categories (as of date) with `core.JobCatalogueChanged` so that other modules validate and display them. AC: as-of parameter; bulk lookup by ids; contract tests. | BR-CORE-020, BR-CORE-021, BR-CORE-025, BR-CORE-026 | 3 | US-CORE-020, US-CORE-021, US-CORE-022, US-CORE-023, TECH:outbox | Interfaces P-05, P-07, P-08 |
| US-CORE-025 | EP-CORE-03 | As an HR Admin, I want import types for locations, job titles, grades and cost centres so that master data is loaded from Excel at go-live. AC: four templates registered with validators and commit / undo handlers; code-or-name lookups; opening-balance mode supported. | BR-CORE-110, BR-CORE-114 | 5 | US-CORE-007, US-CORE-020, US-CORE-021, US-CORE-022, US-CORE-110 | Four simple flat templates sized as one 5 |

### EP-CORE-04 Users & authorisation

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-030 | EP-CORE-04 | As a Tenant Owner, I want to manage tenant user accounts (login, display name, e-mail, mobile, linked employee or none, locale, status ACTIVE / FORMER_EMPLOYEE / DEACTIVATED, expiry date, SaaS identity link) so that external users (auditor, consultant) and staff can be administered. AC: one tenant per user; expiry auto-deactivates; every status change publishes `core.UserStatusChanged`. | BR-CORE-030 | 5 | SAAS:identity, TECH:outbox, US-CORE-122 | Credentials / MFA are SaaS |
| US-CORE-031 | EP-CORE-04 | As an HR Admin, I want a user created or reactivated automatically on `people.EmployeeHired` (NEW / REHIRE / CONCURRENT, preview at approval) when `auto_create_user = true`, with the default ESS role and an activation e-mail, so that new hires can log in without admin work. AC: login = work e-mail or employee code; rehire reactivates the same user; concurrent hire adds the employment link; idempotent on replayed events. | BR-CORE-030 | 5 | US-CORE-030, US-CORE-033, US-CORE-071, US-CORE-085, PPL:EmployeeHired | |
| US-CORE-032 | EP-CORE-04 | As an HR Admin, I want access downgraded automatically at `access_revocation_date` after `people.TerminationScheduled` (sessions revoked via SaaS, pending approvals reassigned, switch to FORMER_EMPLOYEE for the tenant-set period, then deactivation) so that leavers lose access on time but keep their payslips. AC: scheduled job at 00:00 ICT / 23:59 LWD per setting, idempotent; open tasks reassigned through US-CORE-056 fallback; cancelled termination cancels the schedule. | BR-CORE-030, BR-CORE-056, NFR-CORE-015 | 5 | US-CORE-030, US-CORE-056, US-CORE-122, SAAS:identity, PPL:TerminationScheduled | OD-8 former-employee login is SaaS |
| US-CORE-033 | EP-CORE-04 | As a module developer, I want to register my permission catalogue (function × action: view, create, edit, delete, approve, export, import, view-sensitive) at deploy time and have system roles seeded per tenant so that every module uses one permission model. AC: idempotent registration; removed permissions flagged, not deleted; system roles (Tenant Owner, HR Admin, ESS, LINE_MANAGER, FORMER_EMPLOYEE, Auditor) seeded on provisioning and upgraded on release. | BR-CORE-031 | 5 | TECH:skeleton, SAAS:provision | |
| US-CORE-034 | EP-CORE-04 | As a Tenant Owner, I want to clone system roles and create custom roles with a permission matrix screen so that access fits our organisation. AC: system roles read-only; matrix grouped by module / function; changes audited as security events. | BR-CORE-031 | 5 | US-CORE-033, US-CORE-091 | |
| US-CORE-035 | EP-CORE-04 | As a Tenant Owner, I want each role assignment to carry a data scope (all tenant; legal entities; locations; org units with or without subtree; self; custom rule on employee category / grade range) with effective dates so that HR officers see only their entity. AC: effective permission = union of assignments; scope values validated against master data; assignment changes audited and invalidate caches. | BR-CORE-032 | 8 | US-CORE-034, US-CORE-013, US-CORE-023 | |
| US-CORE-036 | EP-CORE-04 | As a Line Manager, I want the LINE_MANAGER (own subtree) and SELF scopes derived automatically from org manager and assignment data so that I can approve and view my team without an admin grant. AC: recomputed on `OrgUnitChanged`, `AssignmentChanged`, hire and termination; effective at the change's effective date; no manual grant possible. | BR-CORE-033 | 8 | US-CORE-035, US-CORE-014, PPL:AssignmentChanged, PPL:assignment-lookup | Eventual consistency window to be agreed with People |
| US-CORE-037 | EP-CORE-04 | As a module developer, I want a single authorisation service (`can(user, permission, record)`, a SQL scope-filter predicate for queries and reports, and a recipient-scope filter for notifications) so that data scope is enforced server-side everywhere. AC: default deny; predicate composes with RLS and is used by SaaS reporting; library for Spring + reporting service. | BR-CORE-035 | 8 | US-CORE-035, US-CORE-036, US-CORE-013 | Interface P-10 |
| US-CORE-038 | EP-CORE-04 | As a user of any list, I want scope checks to stay fast at scale so that lists and approvals are responsive. AC: ≤ 20 ms p95 added per request at 10,000 employees / 2,000 units; cached compiled predicates with event-driven invalidation; load test in CI perf suite. **[high-uncertainty]** | BR-CORE-035, NFR-CORE-002 | 5 | US-CORE-037, TECH:perf | Risk R-03; depends on SP-2 (RLS + PgBouncer) results |
| US-CORE-039 | EP-CORE-04 | As the solution architect, I want guardrails so that no module bypasses the authorisation service. AC: ArchUnit rule that every endpoint declares a permission; contract test kit modules run for scope leaks; failing build on violation. | BR-CORE-035 | 3 | US-CORE-037, TECH:skeleton | Risk R-08 |
| US-CORE-040 | EP-CORE-04 | As a DPO, I want field-level sensitivity masking (salary, bank account, ID number, health, family/dependant data, PD categories) applied per role through field profiles, with salary visible only with the `compensation` permission, so that sensitive personal data is protected. AC: masking policy API used by module APIs and reporting (fail closed); `view-sensitive` / `compensation` views audited; modules register sensitive fields. | BR-CORE-034 | 8 | US-CORE-037, US-CORE-091 | Base 5 + 1 step: PDPL sensitive categories `[verify-legal]` |
| US-CORE-041 | EP-CORE-04 | As a frontend developer, I want a current-user context API (tenant user, employee id, locale, roles, scopes, permitted menu items) built on the SaaS authenticated identity so that the SPA shows only allowed functions. AC: identity → tenant user mapping; unknown or deactivated user gets 403; context cached per session with invalidation on role change. | BR-CORE-030, BR-CORE-031 | 3 | US-CORE-030, US-CORE-034, SAAS:identity | Interface P-09 |
| US-CORE-042 | EP-CORE-04 | As a Tenant Owner, I want platform support access to our data only through a time-boxed break-glass grant we approved, with every action logged in our audit log, so that operator access is controlled under PDPL. AC: authorisation service honours the grant id and its expiry; access without grant rejected and logged; tenant screen lists support sessions and their actions. | BR-CORE-041 | 5 | US-CORE-037, US-CORE-091, SAAS:supportgrant | R3-08 minimal operator logging included |

### EP-CORE-05 Workflow & approval engine

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-050 | EP-CORE-05 | As a module developer, I want to register process types (code, request summary schema, condition fields, allowed actions, callback event, auto-approve-on-timeout flag) so that my requests use the shared engine. AC: idempotent registration at deploy; versioned schema; registry visible to support. | BR-CORE-050 | 5 | TECH:skeleton | In-house engine, no BPMN (OD-4, ADR-0007) |
| US-CORE-051 | EP-CORE-05 | As a support engineer, I want vendor-seeded workflow definitions per process type (applicability conditions on entity, org unit, location, employee category, request fields; ordered sequential steps; version and activation date) loaded from configuration, so that tenants get approval routes without a designer. AC: most specific matching definition selected; running instances keep their version; tenant-specific routes loaded by support without code change. | BR-CORE-050 | 8 | US-CORE-050, US-CORE-023 | No designer (BR-CORE-051 P2); condition evaluator is generic |
| US-CORE-052 | EP-CORE-05 | As a requester in any module, I want to submit a request that creates a workflow instance with a snapshot of the request summary and resolved approver chain, progresses step by step, and calls back the module with `WorkflowCompleted` (outcome + trail) so that the module applies the business effect. AC: submit is transactional with the module change (outbox); callback ack idempotent, failed ack raises an error task; submit ≤ 500 ms p95. **[high-uncertainty]** | BR-CORE-050, BR-CORE-054, NFR-CORE-004 | 13 | US-CORE-051, US-CORE-053, TECH:outbox, TECH:jobq | Engine core; state machine + concurrency on tasks |
| US-CORE-053 | EP-CORE-05 | As an HR Admin, I want approver resolvers: direct manager, manager N levels up, manager of a specified unit, users with role R in the requester's scope, named user, requester-selected approver from a filtered list, field from request, so that routes follow our organisation. AC: resolution as of submission date; empty result goes to fallback (US-CORE-056); each resolution logged with reason. | BR-CORE-052 | 8 | US-CORE-013, US-CORE-037, PPL:assignment-lookup | Position-holder resolver needs positions (P2); functional manager is P3 |
| US-CORE-054 | EP-CORE-05 | As an approver, I want to approve, reject (reason mandatory), return to requester or a previous step, request more info, forward (if the process type allows), comment and attach a file, and as a requester to withdraw before the final decision, so that every approval case is handled. AC: allowed actions per process type enforced; attachments via document service; concurrent action on the same task rejected cleanly. | BR-CORE-054 | 8 | US-CORE-052, US-CORE-100 | |
| US-CORE-055 | EP-CORE-05 | As a Line Manager, I want to bulk-approve selected tasks so that I can clear routine requests quickly. AC: per-task result shown (approved / failed with reason); only process types flagged bulk-allowed; each task audited individually. | BR-CORE-054 | 3 | US-CORE-054, US-CORE-059 | |
| US-CORE-056 | EP-CORE-05 | As an HR Admin, I want automatic fallback when the resolved approver is inactive, missing or equals the requester, and duplicate approvers skipped (configurable: skip, go up one level, HR of entity), so that no approval gets stuck. AC: rule per process type with tenant default; fallback logged on the instance; leaver tasks re-resolved via this rule. | BR-CORE-056 | 5 | US-CORE-053, US-CORE-085 | |
| US-CORE-057 | EP-CORE-05 | As an HR Admin, I want to reassign any open task (single or all tasks of a user) to another user with a mandatory reason so that absences are covered without approval delegation. AC: new assignee must satisfy data scope; recorded "reassigned by X from A to B"; both notified. | BR-CORE-056 | 3 | US-CORE-054, US-CORE-037 | Covers absence while delegation (BR-CORE-055) is P2 |
| US-CORE-058 | EP-CORE-05 | As a module developer, I want lifecycle events (`WorkflowSubmitted`, `StepAssigned`, `StepCompleted`, `WorkflowCompleted`, `WorkflowWithdrawn`) published at-least-once through the outbox so that modules and reporting react reliably. AC: event schema in catalogue; consumers idempotent by event id; ordering per instance. | BR-CORE-060, NFR-CORE-004 | 3 | US-CORE-052, TECH:outbox | `SlaBreached` is P2 with SLA |
| US-CORE-059 | EP-CORE-05 | As an employee or manager, I want a unified "My tasks / My requests" inbox across modules (filters, counts, generic task detail rendered from the summary schema, deep link to the module record) so that I approve everything in one place on the responsive web. AC: counts in the header; task detail shows summary, attachments, history and allowed actions; usable at 360 px. | BR-CORE-060 | 8 | US-CORE-052, US-CORE-054, US-CORE-060 | |
| US-CORE-060 | EP-CORE-05 | As an auditor, I want an immutable approval history (who, on behalf of whom, when, action, comment, IP / device) viewable on the record and printable as an approval trail so that approvals are evidence. AC: append-only table, no update / delete grant; trail rendered as PDF via document service; included in `WorkflowCompleted` payload. | BR-CORE-061 | 5 | US-CORE-052, US-CORE-103 | |
| US-CORE-061 | EP-CORE-05 | As a support engineer, I want a workflow monitoring console (instances by state, error tasks from failed callbacks, retry callback, cancel instance with reason) so that stuck requests are fixed without database access. AC: tenant-scoped; every intervention audited; filter by process type and age. | BR-CORE-056, NFR-CORE-004 | 5 | US-CORE-052, US-CORE-090 | "No stuck approvals" |
| US-CORE-062 | EP-CORE-05 | As the solution architect, I want a workflow throughput and failure-injection test so that "no lost tasks" is proven. AC: ≥ 50 submissions/s per node; submit ≤ 500 ms p95; worker / DB restarts lose no task or callback. | NFR-CORE-004 | 3 | US-CORE-052, US-CORE-058, TECH:perf | |
| US-CORE-063 | EP-CORE-05 | As an approver, I want an in-app and e-mail notification with a deep link when a task is assigned, returned or decided so that I act quickly. AC: notification events registered by the engine for all process types; e-mail body holds no request amounts; requester notified of the outcome. | BR-CORE-060, BR-CORE-070 | 3 | US-CORE-058, US-CORE-071 | |

### EP-CORE-06 Notification centre

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-070 | EP-CORE-06 | As a module developer, I want to register notification event codes with vendor-maintained VI/EN templates per channel (in-app, e-mail) with ICU placeholders and a preview, so that messages are consistent and localised. AC: missing placeholder fails at registration; render in recipient locale with VI fallback; support can preview with sample data. | BR-CORE-070, NFR-CORE-011 | 5 | TECH:i18n, TECH:skeleton | Tenant overrides are P2 (BR-CORE-071) |
| US-CORE-071 | EP-CORE-06 | As a module developer, I want one `send(eventCode, recipients, payload, attachment?)` API (via outbox) that resolves recipients (users, roles in scope, external e-mail addresses), deduplicates, renders per recipient and queues per channel, so that modules never talk to channels directly. AC: role-in-scope recipients resolved through the authorisation service; dedup key per event; per-recipient notification record with status. | BR-CORE-070, BR-CORE-035 | 8 | US-CORE-070, US-CORE-037, TECH:outbox, TECH:jobq | Interface P-14 |
| US-CORE-072 | EP-CORE-06 | As an employee, I want an in-app inbox with unread count, list, mark read / all read and deep links so that I see what needs my attention. AC: delivered ≤ 5 s via SSE; read state per user; retention of read items fixed (e.g. 180 days). | BR-CORE-070, NFR-CORE-005 | 5 | US-CORE-071 | |
| US-CORE-073 | EP-CORE-06 | As an HR Admin, I want e-mail notifications handed to the SaaS e-mail delivery adapter and their delivery status (queued, sent, bounced, failed) shown so that I can see whether staff were reached. AC: status updated from adapter events; failed / bounced list with resend; attachment passed only for the registered exception type. | BR-CORE-070 | 5 | US-CORE-071, SAAS:email | Zalo / SMS / push are P2 |
| US-CORE-074 | EP-CORE-06 | As a DPO, I want sensitive notification content blocked from clear-text channels (deep link by default; template lint rejects sensitive placeholders in e-mail bodies; attachments allowed only for the payslip exception and only as a password-protected PDF; no attachment when the employee has no password) so that PDPL is respected. AC: send with an unencrypted attachment is refused; each send / resend with attachment audited (recipient, attachment hash); no amounts in any body. | BR-CORE-074 | 5 | US-CORE-071, US-CORE-105, US-CORE-091 | Base 3 + 1 step: PDPL review `[verify-legal]` (R2-17, R3-04) |
| US-CORE-075 | EP-CORE-06 | As a payroll officer, I want a 10,000-recipient notification batch (payslip publishing) completed ≤ 30 min with in-app ≤ 5 s and e-mail queued ≤ 1 min p95 so that publishing day works. AC: batched rendering, per-tenant fair share on the queue; load test with 10,000 recipients incl. encrypted attachments. | NFR-CORE-005 | 5 | US-CORE-071, US-CORE-073, US-CORE-105, TECH:perf | |

### EP-CORE-07 Master data, settings, numbering, calendar

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-080 | EP-CORE-07 | As an HR user, I want the administrative-unit master (34 provinces / centrally-run cities and their wards / communes / special zones from 2025-07-01, plus the historic 63 provinces, districts and wards with validity dates) with cascading province → ward pickers and a read API, so that addresses follow the two-level model. AC: global read-only tables (X-02 exception); as-of validity; accent-insensitive search. **[high-uncertainty]** | BR-CORE-080, NFR-CORE-011 | 5 | US-CORE-084, TECH:i18n | Official GSO code set and update cadence open (Q-04) `[verify-legal]` |
| US-CORE-081 | EP-CORE-07 | As an HR Admin importing old records, I want an old → new admin-unit mapping (merge / split / rename, effective date) with a resolver that converts old-model addresses and can display either form, so that old ID cards, birthplaces and opening-balance files stay valid. AC: one-to-many splits return candidates and require a choice; unmapped addresses listed in the import error file; birthplace keeps the historic unit. **[high-uncertainty]** | BR-CORE-081 | 8 | US-CORE-080 | Base 5 + 1 step: ward-level split mapping `[verify-legal]` (risk R-01, R-02) |
| US-CORE-082 | EP-CORE-07 | As any module, I want global code lists (countries / nationalities ISO 3166, 54 ethnic groups, religions, ID document types, currencies ISO 4217, languages, education levels, marital status, relationships, genders) in one generic code-list model with API and pickers so that data is standard. AC: VI/EN labels, sort, active flag, effective dates; accent-insensitive search; seeded from the operator release. | BR-CORE-082, NFR-CORE-011 | 5 | US-CORE-084, TECH:i18n | Ethnic list per GSO `[verify-legal]` (list itself is stable) |
| US-CORE-083 | EP-CORE-07 | As a payroll officer, I want the bank and bank-branch master (SBV / NAPAS codes, SWIFT) searchable so that bank accounts and bank payment files use valid codes. AC: search by code or accent-insensitive name; inactive banks hidden from new entries. | BR-CORE-082 | 3 | US-CORE-084 | Reference "bank master list with search" = 2, +branches |
| US-CORE-084 | EP-CORE-07 | As a Platform Operator, I want master-data releases published from the SaaS back-office loaded into the Core store as versioned, idempotent loads that emit `core.MasterDataReleased` so that all tenants get the same reference data. AC: load is all-or-nothing per release; re-running a release is a no-op; release history kept. | BR-CORE-080, BR-CORE-082 | 5 | SAAS:backoffice, TECH:outbox | Change notice / retired-value report is P2 (BR-CORE-087) |
| US-CORE-085 | EP-CORE-07 | As a Tenant Owner, I want hierarchical settings (tenant → legal entity → location) registered by modules with typed values, validation, effective dating where flagged and an admin screen so that behaviour (e.g. `auto_create_user`, employee code format) is configurable. AC: resolution returns the most specific value as of date; every change audited; unknown keys rejected. | BR-CORE-084 | 8 | US-CORE-002, US-CORE-007, US-CORE-090 | Interface P-17 |
| US-CORE-086 | EP-CORE-07 | As an HR Admin, I want a numbering service with sequences per tenant / entity / document type, patterns (prefix, year, entity code, zero-padded counter), a gap-less option and reset rules so that employee codes, contract and decision numbers are generated correctly. AC: gap-less numbers allocated in the business transaction with no duplicates under concurrency; yearly reset; admin screen with preview. | BR-CORE-085 | 8 | US-CORE-085, TECH:skeleton | Gap-less under concurrency needs row locks + tests |
| US-CORE-087 | EP-CORE-07 | As a module developer, I want a business-day / business-time calculator (add N working days, count working days) that wraps the T&A work calendar and falls back to the legal pack's national holiday template when T&A is not subscribed, so that due dates are computed consistently. AC: per legal entity / location / employee calendar; Tết and substitute holidays honoured; fallback path tested (R2-18). | (S12, interface P-23; no P1 BR) | 5 | ATT:calendar-api, TECH:lps | SLA (BR-CORE-057) is P2; used for due dates (e.g. 14-working-day final settlement) |

### EP-CORE-08 Audit service

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-090 | EP-CORE-08 | As an auditor, I want every create / update / delete of business data in all modules recorded (entity type, record id, action, changed fields before / after with sensitive values masked or encrypted, user, on-behalf-of, UTC + local time, IP, user agent, channel UI / API / import / system, correlation id) so that changes are traceable. AC: shared audit client writes in the business transaction or via outbox (no audit, no commit); modules declare audited entities; import jobs tag channel and job id. | BR-CORE-090, NFR-CORE-008 | 8 | TECH:outbox, TECH:kms | Interface P-20; R2-11 append grant on `audit.audit_event` |
| US-CORE-091 | EP-CORE-08 | As a security officer, I want security events (login success / failure, MFA, password change from SaaS; role / permission changes; break-glass sessions; exports and downloads of personal data; views of sensitive fields) written into the tenant audit store, with a write API for SaaS identity and operator events, so that one store holds all tenant security evidence. AC: event categories fixed; SaaS writes through P-27; platform events without a tenant go to `saas` audit. | BR-CORE-091 | 5 | US-CORE-090, SAAS:identity | |
| US-CORE-092 | EP-CORE-08 | As an auditor, I want the audit log append-only and tamper-evident (per-tenant hash chain, no update / delete grant for any role) with a chain verification job so that nobody, including the tenant admin, can alter evidence undetected. AC: verification report flags any break; chain survives concurrent writers; DB grants tested. **[high-uncertainty]** | BR-CORE-092, NFR-CORE-008 | 5 | US-CORE-090, TECH:jobq | Ordering of concurrent writers vs throughput is a design risk |
| US-CORE-093 | EP-CORE-08 | As an auditor with `AUDIT_VIEW`, I want to search the audit log (date range, user, entity, record, action, module, IP) and see before / after values so that I can investigate. AC: one record's 1-year history ≤ 3 s; searches themselves audited; sensitive values shown only with `view-sensitive`. | BR-CORE-093, NFR-CORE-008 | 5 | US-CORE-090, US-CORE-040 | Partitioning / indexes per DESIGN.md |
| US-CORE-094 | EP-CORE-08 | As an auditor, I want to export audit search results to Excel / CSV so that evidence can be handed to an inspector. AC: report registered in the SaaS reporting / export framework with the Core scope predicate; export audited as a personal-data export. | BR-CORE-093 | 2 | US-CORE-093, SAAS:report | Registration only; export engine is SaaS |

### EP-CORE-09 Document service

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-100 | EP-CORE-09 | As a module developer, I want a document API (upload with per-tenant size / type limits, malware scan before availability, storage in tenant-isolated SaaS object storage, metadata: owner module, entity, record id, document type, confidentiality, retention class; versioning) so that every module stores files the same way. AC: default max 20 MB; file unavailable until clean scan (≤ 60 s); infected files quarantined and owner notified; new version keeps history. | BR-CORE-100, NFR-CORE-010 | 8 | SAAS:storage, TECH:kms | Interface P-21 |
| US-CORE-101 | EP-CORE-09 | As a module developer, I want to register document types and use a generic attachments UI component (upload, list, versions, download) so that attachment screens are not rebuilt per module. AC: types registered at deploy with confidentiality default and retention class key; component handles scan-pending state. | BR-CORE-100 | 3 | US-CORE-100 | |
| US-CORE-102 | EP-CORE-09 | As a DPO, I want file access to follow the permission and data scope of the owning record, downloads through short-lived signed URLs (≤ 15 min) and confidential downloads audited so that files never leak outside scope. AC: owning module's access check called before URL issue; expired URL rejected; access log per confidential file. | BR-CORE-101, NFR-CORE-010 | 5 | US-CORE-100, US-CORE-037, US-CORE-091 | |
| US-CORE-103 | EP-CORE-09 | As a module developer, I want a system-document engine (Thymeleaf HTML → OpenHTMLtoPDF, PDF/A-2b, embedded Vietnamese fonts, merge-field registry, batch generation as jobs) so that payslips, statutory PDFs and registers are produced at volume. AC: ~20 docs/s per worker; diacritics render correctly; generated file stored via US-CORE-100. | BR-CORE-102 | 8 | US-CORE-100, TECH:jobq | ADR-0015 engine (1) |
| US-CORE-104 | EP-CORE-09 | As an HR Admin, I want tenant-editable DOCX templates (labour contract, decision, certificate) with merge fields validated against the registry, versions, preview with sample data, letterhead / seal from the legal entity and numbering via the numbering service, rendered to PDF/A, so that HR letters look like our current documents. AC: unknown merge field rejected at upload; preview before activation; generation stores PDF and keeps the template version used. **[high-uncertainty]** | BR-CORE-102 | 8 | US-CORE-103, US-CORE-086, US-CORE-005 | poi-tl + Gotenberg / LibreOffice fidelity with Vietnamese DOCX is unproven |
| US-CORE-105 | EP-CORE-09 | As a payroll developer, I want an API that produces a password-protected copy of a PDF (AES-256, PDF 2.0 rev. 6, view / print only, random discarded owner password) from a user password supplied only in memory, stored as a transient object for ≤ 24 h, so that payslips can be e-mailed as the R2-17 exception. AC: archived PDF/A stays unencrypted; password never logged or persisted by Core; attachment hash returned for audit. | BR-CORE-074 (used by BR-PAY-102) | 5 | US-CORE-103, SAAS:storage, TECH:kms | Password capture / storage in ESS is payroll (BR-PAY-102/104) |

### EP-CORE-10 Import engine

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-110 | EP-CORE-10 | As a module developer, I want to register import types (template definition, field mapping, validators, commit handler, undo handler, partial-commit-allowed flag, dependency on other import types) through one SPI so that every module imports through one engine. AC: registration validated at startup; contract test kit for handlers; registry visible to support. | BR-CORE-110 | 5 | TECH:skeleton | Requirement BR-SAAS-070 is on this engine |
| US-CORE-111 | EP-CORE-10 | As an HR Admin, I want to upload an Excel / CSV file that is parsed into staging and validated row by row (VI/EN messages) as an asynchronous job with progress, in dry-run first, so that I see every error before anything is written. AC: streaming parse of ≥ 50,000 rows; validate-only mode is the default first step; progress via SSE. | BR-CORE-111 | 8 | US-CORE-110, US-CORE-100, TECH:jobq | |
| US-CORE-112 | EP-CORE-10 | As an HR Admin, I want to commit a validated import all-or-nothing by default (valid-rows-only only if the type allows it), idempotent by business key, with a result summary and audit per job, so that a bad file never leaves half-loaded data. AC: any handler failure rolls back the whole batch; re-commit of the same file does not duplicate; audit entries carry channel `import` and job id. | BR-CORE-110, BR-CORE-111 | 8 | US-CORE-111, US-CORE-090 | Large batches: chunked commit with batch-level compensation |
| US-CORE-113 | EP-CORE-10 | As an HR Admin, I want to roll back a committed batch within 24 h if no dependent transactions exist so that a mistaken load can be undone. AC: undo handler per type; dependency check asks each owning module; rollback itself audited; blocked rollback explains which records depend. **[high-uncertainty]** | BR-CORE-110 | 5 | US-CORE-112 | Dependency detection across modules (no cross-schema FK, D-01) |
| US-CORE-114 | EP-CORE-10 | As an HR Admin, I want to download an error file (my original file with error columns per row / cell) and see a job history (who, when, type, mode, counts, status) so that I can fix and re-upload. AC: error file keeps my columns and order; history filterable; files expire per retention class. | BR-CORE-111 | 3 | US-CORE-111 | Report R-12 |
| US-CORE-115 | EP-CORE-10 | As an HR Admin, I want Excel templates generated from the import type definition (VI/EN headers, dropdowns from code lists incl. long lists on hidden sheets, data-type hints, instruction sheet) and lookups that accept code or name so that files are filled correctly first time. AC: generated per tenant with tenant code lists; name lookups accent-insensitive with ambiguity reported; template version stamped and checked on upload. | BR-CORE-112, NFR-CORE-011 | 8 | US-CORE-110, US-CORE-082 | |
| US-CORE-116 | EP-CORE-10 | As an implementation consultant, I want an opening-balance import mode (bulk load, workflow bypass, external reference ids kept, effective dating as at the cut-over date, enforced load order across dependent import types) so that a tenant can go live mid-year without migration. AC: mode allowed only before the entity's opening-balance lock (read from SaaS); workflow bypass audited; out-of-order loads rejected. **[high-uncertainty]** | BR-CORE-114 | 8 | US-CORE-112, US-CORE-110 | D-09; lock / adjustment batches are SaaS (BR-SAAS-088) |
| US-CORE-117 | EP-CORE-10 | As a customer C&B lead, I want a reconciliation report per opening-balance batch (row counts and control totals per template, as registered by the owning module, compared with the control totals I enter) exportable for sign-off so that I can certify the opening position. AC: modules register control-total queries; variances highlighted; PDF / Excel output with sign-off fields. | BR-CORE-114 | 5 | US-CORE-116, US-CORE-103 | Engine only; SaaS runs reconciliation workflow (BR-SAAS-072) |
| US-CORE-118 | EP-CORE-10 | As the solution architect, I want the import engine to validate 50,000 rows in ≤ 5 min so that opening-balance files load in one sitting. AC: perf test with the employee-master template shape; per-tenant fair share on the queue. | NFR-CORE-009 | 3 | US-CORE-111, US-CORE-112, TECH:perf | Export half of NFR-CORE-009 is the SaaS export framework |

### EP-CORE-11 Core non-functional hardening

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-CORE-120 | EP-CORE-11 | As a security officer, I want RLS policies on every tenant table in schema `core` (global master data exempt, read-only per X-02) and an automated isolation test suite on every Core API so that no cross-tenant data can leak. AC: policy present for every table, checked by a DB test; forged-id requests return 0 rows and raise a security event. | NFR-CORE-001 | 5 | TECH:rls | RLS framework itself is TECH |
| US-CORE-121 | EP-CORE-11 | As a DPO, I want Core's sensitive identifiers (legal representative ID number, payer bank account numbers) encrypted at rest with per-tenant keys so that a database leak exposes no identifiers. AC: envelope encryption via TECH library; search by blind index where needed; masking applied on read. | NFR-CORE-007 | 3 | TECH:kms | Passwords are SaaS; salary not encrypted (X-01) |
| US-CORE-122 | EP-CORE-11 | As an operator, I want one Core effective-dating job framework (00:00 Asia/Ho_Chi_Minh activation of future-dated versions of legal entities, locations, org units, grades, role assignments, user expiries / downgrades) that is idempotent and re-runnable with a run log so that missed or repeated runs never corrupt data. AC: re-run for a past date yields no duplicate events; catch-up after downtime; run log visible to support. | NFR-CORE-015 | 5 | TECH:jobq, TECH:outbox | Used by all effective-dated Core stories |
| US-CORE-123 | EP-CORE-11 | As a Vietnamese or English user, I want every Core screen, validation message and code-list label in VI and EN and accent-insensitive search on all Core lists so that "Nguyen" finds "Nguyễn". AC: no hard-coded strings (lint); search on org units, users, job titles, banks, admin units; UTF-8 Excel output. | NFR-CORE-011 | 3 | TECH:i18n | |

## 3. Coverage check (P1 only, mvp-scope §9.1)

### 3.1 Business requirements (45 P1)

| BR | Short title | Stories |
|---|---|---|
| BR-CORE-001 | Company group & 1..n legal entities | US-CORE-001, US-CORE-002 |
| BR-CORE-002 | Legal entity attributes | US-CORE-002, US-CORE-004, US-CORE-005 |
| BR-CORE-003 | MST format validation | US-CORE-002 |
| BR-CORE-004 | Effective-dated legal-entity attributes | US-CORE-003, US-CORE-006 |
| BR-CORE-005 | Branches / work locations | US-CORE-007 |
| BR-CORE-006 | Minimum-wage region (from legal pack) | US-CORE-008 |
| BR-CORE-007 | Region recompute + LocationRegionChanged | US-CORE-009 |
| BR-CORE-010 | Hierarchical org tree, unit types | US-CORE-010, US-CORE-011, US-CORE-013 |
| BR-CORE-011 | Org unit attributes | US-CORE-011 |
| BR-CORE-012 | Effective-dated org (as-of) | US-CORE-012, US-CORE-013, US-CORE-014 |
| BR-CORE-020 | Job families & titles (hazardous flag) | US-CORE-020, US-CORE-024 |
| BR-CORE-021 | Grade catalogue | US-CORE-021, US-CORE-024 |
| BR-CORE-025 | Cost centres & default per unit | US-CORE-022, US-CORE-024 |
| BR-CORE-026 | Employee category code list | US-CORE-023, US-CORE-024 |
| BR-CORE-030 | User management | US-CORE-030, US-CORE-031, US-CORE-032, US-CORE-041 |
| BR-CORE-031 | Function permissions & roles | US-CORE-033, US-CORE-034, US-CORE-041 |
| BR-CORE-032 | Role data scope | US-CORE-035 |
| BR-CORE-033 | Derived LINE_MANAGER / SELF scopes | US-CORE-036 |
| BR-CORE-034 | Field-level sensitivity masking | US-CORE-040 |
| BR-CORE-035 | Server-side scope enforcement | US-CORE-037, US-CORE-038, US-CORE-039, US-CORE-071 |
| BR-CORE-041 | Break-glass support access logged | US-CORE-042 |
| BR-CORE-050 | Generic workflow engine / process types | US-CORE-050, US-CORE-051, US-CORE-052 |
| BR-CORE-052 | Approver resolvers | US-CORE-053 |
| BR-CORE-054 | Workflow actions | US-CORE-052, US-CORE-054, US-CORE-055 |
| BR-CORE-056 | Reassignment & fallback approver | US-CORE-032, US-CORE-056, US-CORE-057, US-CORE-061 |
| BR-CORE-060 | Workflow events + My tasks inbox | US-CORE-058, US-CORE-059, US-CORE-063 |
| BR-CORE-061 | Immutable approval history | US-CORE-060 |
| BR-CORE-070 | Notification service (in-app + e-mail) | US-CORE-063, US-CORE-070, US-CORE-071, US-CORE-072, US-CORE-073 |
| BR-CORE-074 | No sensitive data in clear notifications (+ payslip PDF exception) | US-CORE-074, US-CORE-105 |
| BR-CORE-080 | Admin units (34 provinces, wards) | US-CORE-080, US-CORE-084 |
| BR-CORE-081 | Old→new admin-unit mapping | US-CORE-081 |
| BR-CORE-082 | Global code lists | US-CORE-082, US-CORE-083, US-CORE-084 |
| BR-CORE-084 | Hierarchical tenant settings | US-CORE-085 |
| BR-CORE-085 | Numbering service | US-CORE-086 |
| BR-CORE-090 | Data-change audit | US-CORE-090 |
| BR-CORE-091 | Security event logging | US-CORE-091 |
| BR-CORE-092 | Tamper-evident audit log | US-CORE-092 |
| BR-CORE-093 | Audit search & export | US-CORE-093, US-CORE-094 |
| BR-CORE-100 | Document service | US-CORE-100, US-CORE-101 |
| BR-CORE-101 | File access by record scope | US-CORE-102 |
| BR-CORE-102 | Document templates → PDF | US-CORE-103, US-CORE-104 |
| BR-CORE-110 | Import framework | US-CORE-015, US-CORE-025, US-CORE-110, US-CORE-112, US-CORE-113 |
| BR-CORE-111 | Async imports, dry-run, error file | US-CORE-111, US-CORE-112, US-CORE-114 |
| BR-CORE-112 | Excel template generation | US-CORE-115 |
| BR-CORE-114 | Opening-balance import mode | US-CORE-015, US-CORE-025, US-CORE-116, US-CORE-117 |

All 45 P1 BRs covered. Removed rows (BR-CORE-038, 039, 042, 113) are owned by saas-platform and are not estimated here.

### 3.2 Non-functional requirements (13 P1)

| NFR | Short title | Stories | Note |
|---|---|---|---|
| NFR-CORE-001 | Tenant isolation RLS | US-CORE-120 | RLS framework in TECH backlog |
| NFR-CORE-002 | Scope check ≤ 20 ms | US-CORE-038 | |
| NFR-CORE-003 | Org as-of query ≤ 1 s | US-CORE-013 | |
| NFR-CORE-004 | Workflow throughput, no lost tasks | US-CORE-052, US-CORE-058, US-CORE-061, US-CORE-062 | |
| NFR-CORE-005 | Notification latency | US-CORE-072, US-CORE-075 | |
| NFR-CORE-006 | Login availability 99.9 % | **none (exception)** | Login, Keycloak and token validation are SaaS / TECH (R2-02 §5 #10); covered in saas-platform / technical-enablers backlogs. Core's only part (identity → user mapping without synchronous IdP calls) is in US-CORE-041 |
| NFR-CORE-007 | Password hashing & encryption | US-CORE-121 (Core identifiers only) | Password hashing, TLS and KMS are SaaS / TECH |
| NFR-CORE-008 | Audit write guarantee | US-CORE-090, US-CORE-092, US-CORE-093 | |
| NFR-CORE-009 | Import / export throughput | US-CORE-118 (import) | Export throughput belongs to the SaaS export framework (BR-SAAS-046) |
| NFR-CORE-010 | File limits & signed URLs | US-CORE-100, US-CORE-102 | |
| NFR-CORE-011 | VI/EN, accent-insensitive search | US-CORE-070, US-CORE-080, US-CORE-082, US-CORE-115, US-CORE-123 | |
| NFR-CORE-014 | OWASP ASVS L2, rate limiting | **none (exception)** | Brute-force protection and rate limiting per tenant / user / client are gateway and identity concerns in TECH / SaaS. Core stories meet ASVS L2 through "done means" (§5 of the guide) and US-CORE-039 guardrails |
| NFR-CORE-015 | Idempotent effective-date jobs | US-CORE-122, US-CORE-009, US-CORE-014, US-CORE-032 | |

Result: 11 of 13 P1 NFRs covered by Core stories; NFR-CORE-006 and NFR-CORE-014 are delegated to TECH / SaaS (reason above) and must appear in those backlogs. NFR-CORE-007 and NFR-CORE-009 are covered only for their Core part.

## 4. Totals and risk notes

### 4.1 SP per epic

| Epic | Stories | SP |
|---|---|---|
| EP-CORE-01 Company, legal entities & locations | 9 | 39 |
| EP-CORE-02 Organisation structure | 6 | 28 |
| EP-CORE-03 Job architecture, cost centres & categories | 6 | 23 |
| EP-CORE-04 Users & authorisation | 13 | 73 |
| EP-CORE-05 Workflow & approval engine | 14 | 80 |
| EP-CORE-06 Notification centre | 6 | 33 |
| EP-CORE-07 Master data, settings, numbering, calendar | 8 | 47 |
| EP-CORE-08 Audit service | 5 | 25 |
| EP-CORE-09 Document service | 6 | 37 |
| EP-CORE-10 Import engine | 9 | 53 |
| EP-CORE-11 Core non-functional hardening | 4 | 16 |
| **Total** | **86** | **454** |

Shared engines (EP-CORE-04 to EP-CORE-10, excluding master data / settings) = 340 SP, about 75 % of Core. At the guide's 7 SP per developer per sprint, 454 SP is about 65 developer-sprints, i.e. roughly **11 developers for 6 build sprints on Core alone** before the +40 % delivery overhead. Engines on the critical path of other modules (authorisation, workflow, notification, document, import, audit) must land in sprints 1–3.

### 4.2 Risk notes (`[high-uncertainty]`)

| Story | SP | Why uncertain |
|---|---|---|
| US-CORE-052 Workflow instance runtime | 13 | In-house engine (OD-4); transactional submit with outbox, idempotent callbacks and error tasks; every module depends on it. At the upper limit of the scale |
| US-CORE-038 Scope check ≤ 20 ms | 5 | Depends on SP-2 (RLS + PgBouncer) and the closure-table design; cache invalidation on reorg / transfer (risk R-03) |
| US-CORE-081 Old → new admin-unit mapping | 8 | Ward-level splits are one-to-many; official mapping data still open (Q-04, R-01, R-02) `[verify-legal]` |
| US-CORE-116 Opening-balance import mode | 8 | Cross-module load order, workflow bypass and cut-over effective dating must work for every module's templates; mid-year go-live is top risk 3 |
| US-CORE-104 DOCX templates → PDF | 8 | poi-tl + Gotenberg / LibreOffice rendering fidelity of Vietnamese Word templates unproven; candidate for a short spike |
| US-CORE-113 Import rollback within 24 h | 5 | Dependent-transaction detection across schemas without foreign keys (D-01) |
| US-CORE-092 Tamper-evident hash chain | 5 | Per-tenant chain ordering vs concurrent writers and audit throughput |
| US-CORE-080 Admin-unit master | 5 | Official GSO code set and update cadence open (Q-04) |

Other notes:
- `[verify-legal]` +1 step applied to US-CORE-020 (hazardous categories), US-CORE-040 (PDPL sensitive categories), US-CORE-074 (PDPL review of the payslip exception) and US-CORE-081 (ward mapping).
- US-CORE-087 (business-day calculator, 5 SP) covers no P1 BR: it is Core-owned infrastructure (S12, P-23) that other modules need for due dates. Remove it only if T&A exposes the same API.
- If capacity forces cuts, the cheapest deferrals with workarounds are US-CORE-055 bulk approve (3), US-CORE-061 workflow console (5, support uses scripts), US-CORE-104 tenant DOCX templates (8, vendor-built HTML templates via US-CORE-103 instead) and US-CORE-117 reconciliation report (5, manual Excel sign-off).
