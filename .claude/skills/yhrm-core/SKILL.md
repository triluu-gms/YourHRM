---
name: yhrm-core
description: Domain knowledge for the YourHRM Core module (BR-CORE-*, NFR-CORE-*; DB schemas `core` and `audit`; backend package `vn.yourhrm.core`, `backend/src/modules/core`). Core owns the employer structure, tenant RBAC and the shared engines. Employer structure covers legal entities (pháp nhân, MST, mã đơn vị BHXH, cơ quan thuế), locations/branches (chi nhánh, vùng lương tối thiểu via the Legal Parameter Service), the effective-dated org tree (cây tổ chức, tái cơ cấu), job titles (chức danh, nặng nhọc độc hại), the grade catalogue (ngạch bậc, no pay ranges), positions (vị trí, định biên), cost centres (trung tâm chi phí) and employee categories. Tenant RBAC covers user accounts, roles, permissions, data scope (phạm vi dữ liệu) and field masking (che giấu dữ liệu). The shared engines are workflow/approval (phê duyệt, ủy quyền, SLA), the notification centre (thông báo), the document service and templates (mẫu văn bản, ký điện tử), the import engine incl. opening balances (import số dư đầu kỳ), settings, numbering (đánh số), custom fields, SoD, the tamper-evident audit log (nhật ký kiểm toán), global master data (đơn vị hành chính 34 tỉnh, ngân hàng, dân tộc) and exchange rates (tỷ giá). Use it when writing or reviewing Core code, DDL, APIs or events, or when another module integrates with Core's authorisation, workflow, notification, document, import, audit or org/legal-entity data.
---

# YourHRM Core module

Also load **yhrm-conventions**. It covers tenancy/RLS, PII classes, outbox/inbox, no cross-schema FK, legal-parameter rules and build commands. This skill does not repeat them.

Read `references/business-rules.md` when you implement a specific BR: it indexes all 94 BR/NFR IDs with their phase. Read `references/integration.md` for every C-nn/P-nn interface and event payload.

## 1. Scope
**Core owns:**
- The employer structure: company group, legal entities, locations, org tree, job architecture, grade catalogue, positions, cost centres and employee categories.
- Tenant authorisation: users, roles, permissions, data scope and field masking.
- The shared engines: workflow, notifications, documents, import, audit, settings, numbering and custom fields.
- Global reference data.

**Out of scope. Never build these in `core`:**
| Concern | Owner |
|---|---|
| Authentication, SSO, MFA, sessions, passwords, API clients and credentials (BR-CORE-038/039/042 removed) | saas (Keycloak, ADR-0005) |
| Legal values: min-wage amounts, admin-unit→region map, BHXH rates/caps, PIT brackets, holiday templates | saas Legal Parameter Service |
| Pay scales, salary bands per grade, pay components (BR-CORE-021 has no ranges) | payroll |
| Person/employee records, assignments, contracts, actual headcount, negotiated salary (`compensation` permission is registered by people) | people (`hr`) |
| Holidays, work calendars, shifts, leave | time-attendance (`att`). Core only wraps the calendar for SLA. |
| Report and list export framework, BI, reporting store (BR-CORE-113 removed) | saas. Core supplies the scope predicate and mask policy. |
| Object storage, job scheduler/queue, channel delivery (SMTP/ZNS/SMS/push), consent registry, DSR orchestration, retention scheduler | saas |
| Requisitions, workforce plan (P2; it writes FTE budgets into Core via API) | recruitment |

## 2. Ownership (gap-analysis §5)
**Core is the single owner of:**
- #5b employer payer bank accounts
- #8 positions and FTE budget
- #9 user-account provisioning, driven by People events
- #11 custom fields
- #12 import engine (#12b: each opening-balance template is owned by its target module)
- #13 audit service and store
- #14 notification centre
- #15b document service
- #19 exchange rates
- #23 legal-entity master data (saas keeps only the billing party and the LE limit)
- #28 global master data store and API (saas back-office is only the operator UI and release flow)

**Core consumes:**
| What | From | How |
|---|---|---|
| Hires and terminations | people | `people.EmployeeHired`, `people.TerminationScheduled`, `people.EmployeeTerminated` |
| Assignment changes | people | `people.AssignmentChanged` and the C-10 lookup |
| Reorg mapping result | people | `people.ReorgMappingCompleted` |
| Authenticated identity | saas | C-22 |
| Entitlements and tenant state | saas | `saas.*` events |
| Legal parameters | saas | `getParam` and `saas.LegalParameterPackPublished` |
| Working calendar | att | C-14 |
| Legal-entity lock status | payroll | IF-PAY-P-20 |

## 3. Domain model (`database/postgresql/ddl/002_core.sql`)
**Pattern A tables.** Effective-dated master data uses an anchor plus a `*_version` table: `valid_from`/`valid_to`, a generated `valid_during` daterange, and an `EXCLUDE USING gist` no-overlap constraint.
| Entity | Tables | Notes |
|---|---|---|
| Global reference data (no tenant_id, no RLS) | `country`, `currency`, `admin_unit`, `admin_unit_mapping`, `bank`, `bank_branch`, `code_list`, `code_value` (+ `v_code_value_effective`), `exchange_rate` | `code_value` and `exchange_rate` also take tenant override rows (`enable_tenant_or_global_rls`, R2-11). `admin_unit.level` is PROVINCE / DISTRICT / COMMUNE. Mapping types are MERGE / SPLIT / RENAME / RECODE. |
| Company group | `company_group` | Exactly one per tenant |
| Legal entity | `legal_entity` + `legal_entity_version` | `tax_code` CHECK `^[0-9]{10}(-[0-9]{3})?$`. `tax_authority_code`, `pit_declaration_frequency` (MONTHLY/QUARTERLY) and `oa_od_rate` (NULL = pack standard) plus decision ref/doc. Status is DRAFT/ACTIVE/DISSOLVED. |
| Payer bank account | `legal_entity_bank_account` | `account_no_enc` is P3, with `_last4`. One default per (LE, purpose). Purposes: SALARY / SI / TAX / TRADE_UNION / GENERAL. |
| Location | `location` + `location_version` | Belongs to one LE. `admin_unit_code` drives the region lookup. There is **no region override column** (DDL v0.4 M2); see §6. Also holds `dependent_tax_code`, geo fields and `is_remote_area`. |
| Cost centre | `cost_centre` | Single table, pattern A, unique per (LE, code) |
| Org tree | `org_unit_type`, `org_unit` + `org_unit_version` | `org_unit_version.path` is an ltree of ids from root to self, with `depth` generated. Use `core.org_tree_as_of(date, root)`, and `path <@ root` for subtree scope. `manager_position_id` points to a position; `reorg_package_id` is the package that created the version. |
| Job architecture | `job_family`, `job_title`, `grade_structure`, `grade` | `job_title.hazardous_category` and `occupation_code`. `grade.rank_order`. No pay data. |
| Employee category | `employee_category` | Flags: is_probation, is_seasonal, is_intern, is_expat, is_collaborator, counts_in_headcount |
| Position (P2 feature) | `position` + `position_version` | Status PROPOSED/ACTIVE/FROZEN/CLOSED, `fte_budget`, grade min/max, reports-to |
| Users and RBAC | `user_account`, `permission` (global), `role`, `role_permission`, `role_assignment` | See §5 |
| Import | `import_job`, `import_row`, `import_reconciliation` | Mode STANDARD or OPENING_BALANCE. Rows are encrypted with a per-job DEK and purged 30 days after commit. |
| Workflow | `process_type` (global), `workflow_definition` + `workflow_definition_version` (steps jsonb), `workflow_instance`, `workflow_task`, `workflow_action_log` (append-only), `workflow_callback`, `approval_delegation` (P2) | |
| Notifications | `notification_template` (+ `v_notification_template_effective`), `notification`, `notification_delivery` | The last two are partitioned monthly. Channels: IN_APP, EMAIL, PUSH, ZALO_ZNS, SMS. P1 uses IN_APP and EMAIL. |
| Documents | `document`, `document_version`, `document_template`, `document_template_version` | Binaries live in SaaS storage. `object_key` must start with `tenant_id/`. |
| Settings and numbering | `setting_definition` (global), `setting_value`, `sequence_definition`, `sequence_counter` | `core.next_sequence_value(code, le, date)` is gap-free under a row lock |
| Custom fields (P2) | `custom_field_def`, `custom_field_value` | |
| Reorg (P2) | `reorg_package`, `reorg_change`, `reorg_mapping` | |
| Export bookkeeping | `export_job` | Covers TENANT_FULL, MODULE_DATASET, AUDIT_LOG, DSR_ACCESS and LIST. Expiry is ≤7 days. Sensitive exports need step-up. |
| SoD (P2) | `sod_rule` | PERMISSION_PAIR or SAME_ACTOR; enforcement BLOCK or WARN |
| Audit | `audit.audit_event` (partitioned monthly), `audit.chain_head`, `audit.chain_anchor` | `audit.seal_batch`, `audit.verify_chain`, `audit.tg_row_audit`, `audit.attach_row_audit(regclass)` |
| Outbox/inbox | created by `sys.create_outbox_inbox('core', …)` | |

## 4. Key processes (BRD §3)
**P-01 Initial employer setup**
1. `TenantProvisioned` arrives. The first LE is created through Core's API.
2. Fill in the company profile.
3. Create the LE(s). Validate the MST and its uniqueness, and enforce the plan LE limit.
4. Create locations (one LE each).
5. Build the org tree, job titles, grades and cost centres, by hand or by import.

**P-02 Reorg (P2)**
1. Draft a package with an effective date and its changes.
2. Run the impact analysis.
3. Get approval through the workflow.
4. At 00:00 on the effective date, activate atomically: new versions get `valid_from = eff`, old versions are closed. This is DDL `[)` semantics; the BRD phrases it as "end = eff − 1".
5. Emit `core.OrgUnitChanged` and `core.ReorgActivated`.
6. People maps employees and replies `ReorgMappingCompleted`. The package becomes ACTIVE, or ACTIVATED_WITH_ERRORS if anything failed.
- Block activation while active employees in a dissolved unit are unmapped.
- A retro package that crosses a locked payroll period needs `CORE_REORG_BACKDATE`.

**P1 org changes** are direct effective-dated edits to `org_unit_version`. There are no packages in P1.

**P-04 User lifecycle.** Core only reacts to People events.
1. On `people.EmployeeHired`, if the setting `auto_create_user = true`, create the user with the default ESS role and emit `core.UserStatusChanged(ACTIVE)`. SaaS then creates the identity.
2. LINE_MANAGER scope is derived and is never granted by hand.
3. On `people.TerminationScheduled`, schedule access revocation at `access_revocation_date` (default 23:59 on the last working day). At that point:
   - reassign pending tasks
   - revoke sessions through SaaS
   - move the user to FORMER_EMPLOYEE for the tenant-set window (OD-8: OTP by e-mail), then to DEACTIVATED
4. A rehire re-activates the same user.
5. Non-employee users (auditors, consultants) carry an expiry date.

**P-06/07 Approval**
1. A module registers its process type at deploy.
2. The module calls `WorkflowService.start(processType, subjectRef, context)`, which is idempotent per `(process_type_code, idempotency_key)`.
3. Core snapshots the summary and the resolved approver chain.
4. Tasks go to the inbox. The approver acts.
5. On the final decision, Core emits `core.WorkflowCompleted` through the outbox. The owner module applies the business effect in its own transaction and acknowledges through `workflow_callback`.
6. If the resolved approver is inactive or missing, fall back per `on_resolver_failure`: SKIP, ESCALATE_UP, LE_HR_ADMIN or FAIL.

**P-08 Notify**
1. A module calls `send(eventCode, recipients, payload)`.
2. Core renders the VI/EN template, resolves recipients within scope, and de-duplicates.
3. Core writes the in-app notification and hands e-mail to the SaaS adapter, which returns the delivery status.

**P-09 Import**
1. Download the template, then upload the file (AV scan, sha256).
2. Parse into `import_row` staging.
3. The module's validators run. Use dry-run and the error file to fix and re-upload.
4. Commit asynchronously. Commits are all-or-nothing unless the import type allows partial commit. The owner module's ImportHandler writes its own schema.
5. For OPENING_BALANCE jobs: `as_of_date` (go-live G) is required, reconciliation must be signed off before commit, and dependencies are declared with `depends_on_job_id` (ADR-0037).

## 5. Rules you must never break
- **One authorisation point (BR-CORE-035, P-10, ADR-0006).** Every query, report, export, API call and notification recipient is filtered through Core's `AuthorizationService.scopeFor(permission, asOf)` / `can(...)`. Effective scope is the **union** of role assignments (BR-CORE-032). Scope types are TENANT, LEGAL_ENTITY, LOCATION, ORG_UNIT (± `include_subtree`), SELF, DIRECT_REPORTS and ALL_REPORTS. `scope_ids` is required exactly for LE, LOCATION and ORG_UNIT.
- **Derived scopes (BR-CORE-033).** LINE_MANAGER and SELF follow org and assignment changes with no admin action. Invalidate the authz cache on `core.RoleAssignmentChanged`, `people.AssignmentChanged`, `core.OrgUnitChanged` and `core.ReorgActivated`.
- **Field masking (BR-CORE-034).** Use `role_permission.field_mask_level` = NONE / PII / PII_AND_PAY. Salary needs `compensation`, and every sensitive read is audited. Masked values never leave the server.
- **Permission catalogue.** It is global and registered by modules at deploy. Tenants compose roles from it but never add permissions. Deprecated permissions are deactivated, never deleted. System roles are seeded per tenant (`is_system`) and cannot be deleted.
- **Legal entities and locations.**
  - Statutory attributes are effective-dated (BR-CORE-004).
  - MST is 10 digits, or 13 as `NNNNNNNNNN-NNN`, unique per tenant (BR-CORE-003).
  - A location belongs to exactly one LE (BR-CORE-005).
  - Core never stores region amounts or the region map. It resolves the region through `getParam` (BR-CORE-006), recomputes on `saas.LegalParameterPackPublished`, and emits `LocationRegionChanged` (BR-CORE-007).
- **Org units.** The code is unique per tenant and immutable once used. There is no overlap per unit. A unit's parent cannot be itself. The ltree `path` must end with the unit's own id, and descendants are recomputed when a unit moves.
- **Grades carry no pay data (BR-CORE-021).** People and Recruitment validate salary against Payroll (IF-PAY-P-02), not against Core.
- **Workflow.**
  - Running instances keep the definition version they started with.
  - At most one open instance per (process type, subject).
  - Reject needs a reason (DB CHECK).
  - `workflow_action_log` is append-only (BR-CORE-061).
  - Core never touches subject tables; `subject_id` is a soft reference.
  - Callbacks are idempotent (NFR-CORE-004).
  - Long system sagas do not belong in the engine (ADR-0019).
- **Notifications (BR-CORE-074).** Never put sensitive data in clear in email, Zalo or SMS; send a deep link. The one exception is the payslip PDF with an employee-set password: there is no default or derived password, and the password is never sent. Zalo/SMS never contain amounts.
- **Audit (BR-CORE-090..093, ADR-0027).**
  - `audit.audit_event` is INSERT-only for app roles, and only the sealer updates the seal columns.
  - There is no DELETE, and no purge bypass either (unlike the D9 bypass on `workflow_action_log`).
  - Diffs are masked; encrypted columns are written as `<encrypted:changed>`.
  - Core is the only tenant audit store. SaaS identity and operator events are written here (P-27).
- **Documents (BR-CORE-100/101).**
  - Scan for malware before use.
  - Access follows the owning record's permission and scope.
  - Signed URLs live ≤15 min (NFR-CORE-010).
  - A legal hold blocks purge.
- **Import (BR-CORE-110).** All-or-nothing by default. Core stores staging and never reads `hr`/`att`/`pay`; the owner module validates and commits through its handler. Commit events carry `initial_load=true`.
- **Effective-date jobs (NFR-CORE-015).** They run at 00:00 Asia/Ho_Chi_Minh and must be idempotent and re-runnable.

## 6. VN compliance (BRD §5; all `[verify-legal]`)
- **VN-01 Admin units.** From 2025-07-01 Vietnam has 34 provinces and a 2-level model (Nghị quyết 202/2025/QH15). New addresses use province + ward only. Keep the 3-level history and the old→new mapping (BR-CORE-080/081).
- **VN-02 Regional minimum wage.** It applies per employer site, by admin unit (BLLĐ 2019 Điều 91; NĐ 74/2024; a 2025 decree, e.g. NĐ 293/2025, is an *example*). Region and amounts come from the legal pack. A tenant override is allowed only where the pack permits it, and lives in `saas.legal_param_override` (LOCATION scope).
- **VN-03 MST format.** 10 digits, or 13 for a dependent unit. Individuals use the 12-digit personal ID as their PIT code from 2025-07-01 (people/pay).
- **VN-04 BHXH unit code.** One per LE. A branch-level code is still an open question (Q-02).
- **VN-05 Personal data (PDPL 91/2025/QH15, NĐ 13/2023).** Sensitive-data masking, access audit, DSR support (P-25).
- **VN-06 Data localisation (Luật An ninh mạng, NĐ 53/2022).** Storage must be configurable per region.
- **VN-07 E-transactions and e-contracts (Luật GDĐT 20/2023).** E-signature is P2.
- **VN-08 Retention.** Accounting records (including payroll) ≥10 years. Floors come from the platform/legal pack; configurable retention is P2.
- **VN-09 Heavy/hazardous job list (TT 11/2020/TT-BLĐTBXH).** Drives `job_title.hazardous_category`.
- **VN-10 Formats.** Official documents in Vietnamese; dates DD/MM/YYYY; VND has no decimals.
- **VN-11 Ethnic groups.** The official list of 54.
- **BHTNLĐ-BNN rate on the LE.** The value must be one the legal pack allows. The DDL CHECK `< 0.05` is only a sanity bound.

## 7. Events and interfaces
- **Publishes:**
  - `core.MasterDataReleased`, `core.LegalEntityChanged`
  - `core.LocationChanged` (subtype `LocationRegionChanged`)
  - `core.OrgUnitChanged`, `core.ReorgActivated` (P2), `core.ReorgPackageStatusChanged` (P2)
  - `core.PositionChanged` (P2), `core.JobCatalogueChanged`, `core.UserStatusChanged`
  - `core.WorkflowStepCompleted`, `core.WorkflowCompleted`
- **Consumes:**
  - `saas.TenantProvisioned`, `saas.TenantStateChanged`, `saas.EntitlementsChanged`, `saas.LegalParameterPackPublished`
  - `people.EmployeeHired`, `people.TerminationScheduled`, `people.TerminationCancelled`, `people.EmployeeTerminated`, `people.AssignmentChanged`, `people.ReorgMappingCompleted`
  - `att.AbsenceChanged`, `att.HolidayCalendarChanged`
  - `rec.RequisitionStatusChanged`, …
- **Interfaces.** Core numbers its own interfaces C-01..C-26 (consumed) and P-01..P-27 (provided). Other BRDs cite `IF-PAY-C-01`, `IF-PAY-P-20`, `IF-PAY-C-33`, etc. The full tables are in `references/integration.md`.

## 8. Phase 1 vs Phase 2 (mvp-scope §9.1; R2-19 Plan B)
- **P1 (45 BR + 13 NFR):** BR-CORE-001..007, 010..012, 020, 021, 025, 026, 030..035, 041, 050, 052, 054, 056, 060, 061, 070 (in-app and e-mail only), 074, 080..082, 084, 085, 090..093, 100..102, 110..112, 114. The P2 NFRs are NFR-CORE-012 and 013.
- **P2 — do NOT build in P1:**
  - Org and positions: reorg packages (013–015), org chart (016), positions and position control and occupancy (022–024; no "position holder" resolver)
  - Roles and SoD: time-bound/approved role grants (036), SoD (037)
  - Workflow: workflow designer (051; P1 uses vendor-seeded definitions), step types beyond sequential single approver (053), user approval delegation (055; HR reassign covers absence), SLA/escalation (057), approver re-resolution on reorg (058), mobile/e-mail/Zalo approval (059)
  - Notifications: tenant template override (071), preferences (072), digest/fallback (073)
  - Master data and fields: tenant custom code lists (083), custom fields (086), master-data change distribution (087), exchange rates (088)
  - Audit and documents: configurable audit retention (094), e-signature (103), retention classes (104)
- **P3:** 008, 017, 040, 075, 115.
- **Removed:** 038, 039, 042, 113.
- **Schema already exists for some P2 features:** `approval_delegation`, SLA columns, `custom_field_*`, `reorg_*`, `position*` and `sod_rule`. The tables are there; the features are still P2.

## 9. Open items affecting implementation
- **Q-01 / R4-05 workflow engine.** OD-4 accepted the in-house engine (ADR-0007), but R4-05 (*open*) proposes a 3-day spike comparing it with Flowable.
- **Q-02 BHXH unit code per branch.** The model allows a branch-level code. Pending a consultant's opinion.
- **Q-04** Official post-merger ward list and codes. `[verify-legal]`
- **Q-06** E-sign vendor (P2).
- **Q-07** Group-level (cross-LE) org units in the MVP. See conflict (c).
- **Q-08** Mandatory VN hosting of all data.
- **Risks:** R-03 the scope engine as a bottleneck (closure/path plus cache, load test NFR-CORE-002); R-04 engine over-generalisation; R-08 modules bypassing the authz service (ArchUnit plus API tests).
- **R4-01..R4-07 (schedule, team and spike decisions)** are still open in the decision log §9.

## 10. Known conflicts in the sources (report; do not silently pick)
- **(a) Location region override.** BR-CORE-005 and BRD §6 allow a location region override "with reason" and a `min_wage_region_map` cache table. DDL v0.4 M2 removed the override column and has no `min_wage_region_map`; the override lives in `saas.legal_param_override`.
- **(b) `export_job`.** BRD §6 marks it removed (moved to saas, BR-CORE-113). DDL keeps `core.export_job` [P1] for tenant/audit/DSR/list export bookkeeping.
- **(c) Group-level org units.** BR-CORE-011 allows `legal_entity = null` for a virtual group-level unit. `org_unit_version.legal_entity_id` is NOT NULL ("one root per legal entity").
- **(d) Grade effective dating.** BR-CORE-021 says grades are effective-dated. `core.grade` has only `is_active`.
- **(e) User status values.** The BRD lists ACTIVE/FORMER_EMPLOYEE/DEACTIVATED. The DDL and `UserStatus` also have INVITED and LOCKED.
- **(f) Code formats.** The BRD writes permission and process-type codes as `AUDIT_VIEW`, `CORE_REORG`, `CORE_REORG_BACKDATE`, `CORE_ROLE_GRANT`, `ATT_LEAVE_REQUEST`. The DDL requires `^[a-z]+(\.[a-z0-9_]+)+$` for permissions (e.g. `core.role.view`) and `^[a-z]+\.[A-Z][A-Z0-9_]*$` for process types (e.g. `att.LEAVE_REQUEST`). Follow the DDL.
- **(g) P1 step types.** The DDL comment says P1 supports SINGLE/ANY_OF/ALL_OF/FYI. mvp-scope (BR-CORE-053 → P2) and the backlog say P1 is sequential single-approver only.
- **(h) Import rollback window.** BR-CORE-110 says rollback within 24 h. ADR-0037 §7 lets an opening-balance job roll back until the tenant's first payroll run is calculated.
- **(i) Workflow table names.** ADR-0007 says `workflow_action`. The DDL table is `workflow_action_log`. The BRD's `workflow_step_def`, `doc_template`, `sequence`, `import_type`, `user_notification_pref` and `api_scope_mapping` do not exist in the DDL: steps are jsonb in `workflow_definition_version`, and the others map to `document_template*`, `sequence_definition`/`sequence_counter`, or are absent.
- **(j) Events outside the catalogue.** `core.RoleAssignmentChanged` and `core.RoleChanged` (code, ADR-0006) and the BR-CORE-060 lifecycle events `WorkflowSubmitted`/`StepAssigned`/`WorkflowWithdrawn`/`SlaBreached` are not in the gap §6 catalogue v1.1.
- **(k) Stale mvp-scope labels.** The BR-CORE-021 row still says "with reference ranges", and BR-CORE-086 still shows MoSCoW S. The BRD is newer on both.

## 11. Implemented so far (backend increment 1: walking skeleton + Core RBAC)
- **`vn.yourhrm.core.api`:**
  - `CorePermissions`: `core.permission.view`; `core.role.{view,create,update,delete}`; `core.user.{view,create,update}`; `core.role_assignment.{view,create,delete}`; `core.legal_entity.view`; `core.location.view`; `core.org_unit.view`. The `role.update` and `role_assignment.create/delete` permissions are marked privileged.
  - `CoreEvents` (records `RoleAssignmentChanged`, `RoleChanged`, `UserStatusChanged`) are **in-process only** (Spring events). The outbox is deferred to `TECH:outbox`.
- **Endpoints (`internal/web`):**
  - `GET /api/v1/core/me`, `GET /api/v1/core/me/authorisation/check`, `GET /api/v1/core/me/authorisation/scope` (`@PublicEndpoint`, caller-only)
  - `GET /api/v1/core/permissions`
  - `/api/v1/core/roles`: GET list, GET `/{id}`, POST, PUT `/{id}`, DELETE `/{id}`, POST `/{id}/copy`, PUT `/{id}/permissions`
  - `/api/v1/core/users`: GET list, GET `/{id}`, POST, PUT `/{id}`, POST `/{id}/activate`, POST `/{id}/deactivate`
  - `/api/v1/core/users/{userId}/role-assignments`: GET, POST, DELETE `/{assignmentId}`
  - All mutating endpoints use `@RequiresPermission(..., tenantWide = true)`.
- **Authz internals:**
  - `CoreAuthorizationService` implements `vn.yourhrm.shared.auth.api.AuthorizationService`.
  - `ScopeResolver` (pure, unit-tested) computes the union of grants.
  - `AuthorisationCache` caches results.
  - `PermissionCatalogueRegistrar` registers each module's `PermissionCatalogue` bean.
- **BR-CORE-001 (US-CORE-001/002, increment 2):**
  - `GET/PUT /api/v1/core/company-group` (`core.company_group.view` / `.update` tenant-wide). `CompanyGroupProvisioning` (core.api) creates the profile idempotently from `saas.tenant`; the `TenantProvisioned` consumer that calls it is not wired yet.
  - `/api/v1/core/legal-entities`: GET list / GET `/{id}` (scoped by `core.legal_entity.view`, `?asOf=`), GET `/usage`, POST (`core.legal_entity.create` tenant-wide). Create writes anchor + first version, checks MST (`TaxCode`) format and per-tenant uniqueness, admin unit validity and the plan LE limit under a per-tenant advisory lock. DRAFT + ACTIVE count; DISSOLVED does not.
  - Plan limit port: `vn.yourhrm.shared.common.entitlement.TenantLimits` (to be implemented by saas); fallback property `yhrm.entitlements.max-legal-entities` (unset = no limit).
  - DDL: `core.company_group`, `core.legal_entity` and `core.location` added to the row-audit list.
- **BR-CORE-002..005 (US-CORE-003/004/007):**
  - LE: `POST /{id}/versions` (effective-dated change, `core.legal_entity.update` on that LE), `GET /{id}/versions` (history), `PUT /{id}/status` (DRAFT→ACTIVE→DISSOLVED; dissolve needs all locations CLOSED). Attributes include business reg date/issuer, phone, e-mail (DDL columns added), tax authority, PIT frequency (changes only on 1 Jan), OA-OD rate + decision ref + evidence document (required for a reduced rate; legal-pack allowed-value check pending LPS).
  - Locations: `/api/v1/core/locations` GET list (`?legalEntityId=`, `?asOf=`), GET `/{id}`, GET `/{id}/versions`, POST (`core.location.create` on the LE), POST `/{id}/versions`, POST `/{id}/close`. Dependent tax code = LE MST + `-NNN`, unique per tenant. DDL: `location_type`, dependent tax code and geo CHECKs.
  - `infra.EffectiveDatedVersions`: shared "change from D" (correct the version starting on D, or split the covering one).
- **BR-CORE-006/007 (min-wage region):**
  - Port `vn.yourhrm.shared.common.legal.LegalParameters` (SaaS implements `getParam`; key `MIN_WAGE.REGION_MAP` [verify-legal]) and event record `LegalParameterPackPublished` in shared/common. No implementation yet: lookups return 503 `legal-parameters-unavailable`; write paths skip the region diff.
  - `core.api.MinWageRegions` (P-03): `forLocation`, `forLegalEntity`, `recomputeAfterPackPublished` (per tenant, called by the not-yet-built bus consumer). Endpoints `GET /locations/{id}/min-wage-region`, `GET /legal-entities/{id}/min-wage-region` (`?asOf=`, scoped).
  - Region is never stored; changes are diffed (old vs new admin unit, previous vs new pack) and published as `CoreEvents.LocationRegionChanged` / `LegalEntityRegionChanged` (in-process).
- **BR-CORE-008** (LE relations) deliberately skipped: P3, no DDL (user decision 2026-11-01).
- **BR-CORE-010..012 (org tree, US-CORE-010..013):**
  - `/api/v1/core/org-unit-types`: GET (`core.org_unit.view`), POST / PUT (`core.org_unit_type.update` tenant-wide). Defaults (COMPANY, DIVISION, DEPARTMENT, BOARD, SECTION, TEAM) seeded by `CompanyGroupProvisioning.provision()`. DDL: `sort_order`, `is_active` added.
  - `/api/v1/core/org-units`: GET tree as-of (`?asOf=&root=&includeClosed=&q=`, scoped, path order), GET `/{id}`, `/versions`, `/ancestors`, `/descendants`; POST (create needs `core.org_unit.create` on the parent unit, or the LE for a root), POST `/{id}/versions` (rename/move/etc. from D; moving needs update on the new parent too), POST `/{id}/close`.
  - `OrgUnitService.normalisePaths` keeps the ltree invariant after every write: re-paths from the parent's version, splits descendant versions where an ancestor's path changes. Org writes are serialised per tenant (advisory lock). `core.OrgUnitChanged` evicts the tenant's authz cache.
  - DDL: `org_unit_version.manager_user_id` (P1 manager; positions are P2) and `sort_order`. One root per LE; LE stays NOT NULL (conflict (c): no group-level units).
  - `core.api.OrgUnitAssignments` SPI (People implements; stub NONE) blocks closing a unit with active assignments.
- **BR-CORE-013..017** deliberately skipped (P2/P3; user decision 2026-11-01).
- **BR-CORE-020/021/025/026 (job architecture, US-CORE-020..024):**
  - `/api/v1/core/job-families`, `/job-titles` (`core.job_catalogue.view` / `.update` tenant-wide). Job title: description, JD document, default grade range (DDL `default_grade_min_id/max_id`), occupation code, hazardous category validated against code list `JOB_HAZARD_CATEGORY` [verify-legal]; a hazard change publishes `JobCatalogueChanged(HAZARD_CHANGED)`.
  - `/api/v1/core/grade-structures`, `/grade-structures/{id}/grades` (`?asOf=`, `?allDates=`), `/grades/{id}`. Grades now effective-dated (DDL `valid_from/valid_to` + `grade_rank_ex`: rank unique per structure at any date), resolving conflict (d). No pay data.
  - `/api/v1/core/cost-centres` (`core.cost_centre.*`, LE-scoped). Ending one is refused while an active org unit version uses it later (`cost-centre-in-use`).
  - `/api/v1/core/employee-categories` (`core.employee_category.*`): six typed flags; defaults seeded by provisioning. The jsonb `attributes` is not exposed yet.
  - `core.api.JobCatalogue`: bulk lookups by id (grades and cost centres as-of) for other modules.
- **BR-CORE-022..024** (positions) deliberately skipped (P2; user decision 2026-11-01).
- **BR-CORE-030..035/041 gaps (increment 2):**
  - User `display_name` (DDL); `core.api.UserLifecycle.deactivateExpiredUsers()` for the scheduler.
  - `core.api.SystemRoles` / `SystemRoleService`: TENANT_OWNER, HR_ADMIN, ESS, LINE_MANAGER, FORMER_EMPLOYEE, AUDITOR built by rule from the catalogue (`TEMPLATE_VERSION`), synced at provisioning; system matrices are overwritten on sync.
  - Derived grants in `AuthorisationQueries.loadSnapshot` (no assignment id): LINE_MANAGER on subtrees of units with `manager_user_id` = user today; ESS / FORMER_EMPLOYEE with SELF from `employment_id` + status. Manual grants of these roles → 422 `derived-role`.
  - `shared.auth.api.SensitiveDataAccess` (impl `CoreSensitiveDataAccess`): `maskLevelFor` (least restrictive grant, fail closed PII_AND_PAY), `applyMaskLevel` (sets `app.mask_level`, needs a tx), `recordSensitiveRead` (READ_SENSITIVE audit). Sensitive-field registration by modules not built.
  - `AuthorizationService.usersWhoCan(permission, target, asOf)` recipient filter.
  - Support sessions: operator token (`operator_id` + `support_grant_id` claims) → `UserAccountResolver.resolveSupport` → SaaS SPI `SupportGrants` (fail closed without it); every request audited (`SUPPORT_ACCESS[_DENIED]`); `SupportSession` scoped value → tx sets `app.operator_id` / `app.support_grant_id` → row audit `actor_type = OPERATOR`. Grant snapshot: TENANT scope, module permissions (VIEW only for READ), never privileged or `core.support_session.*`. Tenant screen `/api/v1/core/support-sessions`.
- **Still open in 030..035:** custom-rule scope (employee category / grade range, needs People attributes + DDL), People-driven user provisioning/revocation (US-CORE-031/032).
- **BR-CORE-036/037 (P2), 040 (P3)** skipped; 038/039/042 removed.
- **Workflow engine P1 (BR-CORE-050/052/054/056/060/061, US-CORE-050..061):**
  - New Gradle module `shared:workflow` (`vn.yourhrm.shared.workflow.api`): `ProcessTypeCatalogue` SPI (modules declare `ProcessTypeDefinition` incl. vendor `defaultSteps`), `WorkflowService` (`start`, `acknowledgeCallback`, `reportCallbackFailure`), `WorkflowEvents` (Submitted, StepAssigned, StepCompleted, Withdrawn, Completed with history; in-process).
  - Core: `ProcessTypeRegistrar` (like permissions; runtime role can't write `core.process_type` → release-job SQL), `WorkflowDefinitionService` (vendor default materialised per tenant on first submit; support publishes versions via `POST /api/v1/core/workflow-definitions`; most-specific match), `ApproverChainResolver` (snapshot at submit; fallback SKIP / ESCALATE_UP / LE_HR_ADMIN / FAIL; duplicate approver skipped; POSITION_HOLDER / FUNCTIONAL_MANAGER resolve to nothing), `WorkflowEngine` (instance row lock per command; approve / reject+reason / return to requester or previous / request info / forward / comment+attachments / withdraw / resubmit / bulk approve per-task tx / reassign with reason + scope check / cancel; callback ledger + retry), `WorkflowQueryService` (inbox, counts, my requests, detail with allowed actions, trail, console).
  - Endpoints under `/api/v1/core/workflow/**` (inbox/actions are `@PublicEndpoint`, authorised per task), `/process-types`, `/workflow-definitions`. Permissions `core.workflow.{view,reassign,admin}`, `core.workflow_definition.{view,manage}`.
  - P1 runs sequential steps; a multi-user resolver (role pool, HR fallback) makes ANY_OF tasks where the first to act decides.
  - `ClientInfoResolver` is the only other class allowed to read request headers (User-Agent for the trail; ArchitectureTest exemption).
- **Notification centre P1 (BR-CORE-070 in-app + e-mail, BR-CORE-074; US-CORE-063/070..074):**
  - `core.api.Notifications.send(SendRequest)`: recipients = user ids + audiences (permission or role covering a target, via the authz service); 24 h dedupe per (recipient, key); render per recipient locale (vi-VN fallback); in-app item + IN_APP delivery (DELIVERED); EMAIL delivery QUEUED and handed to `shared.common.delivery.EmailDeliveryAdapter` (SaaS SPI) **after commit**; adapter reports via `core.api.NotificationDeliveryStatus`.
  - Templates: modules declare `NotificationTemplateCatalogue` beans; `NotificationTemplateRegistry` validates at start-up (declared placeholders, no SENSITIVE / MONEY placeholders anywhere, IN_APP + vi-VN required, attachments only on EMAIL). DB effective view wins over code (release job writes global rows).
  - BR-CORE-074: attachment only for an event registered `attachmentAllowed`, PDF flagged encrypted with the recipient's password, single recipient, audited (`NOTIFICATION_ATTACHMENT_SENT` with sha256); such e-mails are resent by the source module, not by Core.
  - `WorkflowNotifier`: task assigned, request returned, final decision (`core.Workflow*` events, WORKFLOW category, mandatory).
  - Endpoints: `/api/v1/core/notifications` (+ `/unread-count`, `/{id}/read`, `/read-all`, `/stream` SSE, single-node), `/notification-deliveries` (+ `/{id}/resend`, `core.notification.admin`), `/notification-templates` (+ `/preview`, `core.notification_template.view`).
- **Notifications still open:** tenant template override (071, P2), preferences (072, P2), scheduling / quiet hours / digest / channel fallback (073, P2), announcements (075, P3), push / Zalo / SMS (P2), external e-mail recipients, multi-node SSE fan-out, outbox transport.
- **Master data, settings, numbering P1 (BR-CORE-080/081/082/084/085; US-CORE-080..086):**
  - `core.api.MasterData` / `MasterDataService`: admin units as-of (provinces or children), accent-insensitive search (`sys.search_key`), old→new resolver (CURRENT / MAPPED / AMBIGUOUS split with candidates / UNKNOWN, multi-hop, `replaced_by_code` fallback), address in OLD or NEW form; code lists via `v_code_value_effective` (tenant rows shadow global); countries, currencies, banks (inactive hidden), branches. `/api/v1/core/master-data/**` (`@PublicEndpoint`).
  - `MasterDataReleaseLoader.load(platformDataSource, Release)`: all-or-nothing per release, idempotent on `release_no` (same content no-op, different content 409), history in new global table `core.master_data_release` (SELECT for `yhrm_app_core` only), publishes `CoreEvents.MasterDataReleased`. Must run on a `yhrm_platform` connection; global `code_value` rows need a platform RLS policy (open).
  - Settings: `SettingCatalogue` SPI + `SettingRegistrar` (release-job pattern; Core's own: `core.auto_create_user`, `core.former_employee_access_days`, seeded in dev seed); `core.api.Settings` / `SettingsService` resolves LOCATION → LE → TENANT → default, typed validation, dated values split like versions; `/api/v1/core/settings` (`core.setting.{view,update}`). Typed accessors are NOT interface defaults (self-invocation would bypass the transaction and RLS would return the default).
  - Numbering: `core.api.Numbering` / `SequenceService` on `core.next_sequence_value` (gap-free in the business tx; non-gap-free in REQUIRES_NEW); tokens `{SEQ:n}` `{YYYY}` `{YY}` `{MM}` `{LE}`; `/api/v1/core/sequences` + `/preview` (`core.sequence.{view,update}`).
- **Still open (080..088):** tenant custom code lists (083, P2), custom fields (086, P2), retired-value report (087, P2), exchange rates (088, P2), official GSO data (Q-04), release job wiring.
- **Workflow still open:** outbox transport, notifications on assignment (US-CORE-063), PDF trail (document service), People-based line manager (Core uses org-unit `manager_user_id`), designer / SLA / delegation / parallel / e-mail links (P2).
- **Audit P1 (BR-CORE-090..093, US-CORE-090..093):**
  - Request origin: `shared.common.tenancy.RequestOrigin` (channel UI / API / IMPORT / SYSTEM, client IP, user agent, correlation id from `X-Correlation-Id` else request id), bound by `TenantContextFilter`, set per tx as `app.channel/client_ip/user_agent/correlation_id`. DDL: `audit_event.channel` (+ CHECK, part of `audit.canonical`), `audit.current_*()` helpers used by `tg_row_audit` and `AuditWriter`, index `(tenant_id, occurred_at)`. Unbound work = SYSTEM; every request with a user token = UI (API clients are a P2 SaaS feature).
  - `core.api.AuditLog` (P-20/P-27): `record(BusinessAction)` in the caller's tx (upper-case verb, not INSERT/UPDATE/DELETE); `recordSecurityEvent(SecurityEvent)` with the fixed `SecurityEventType` list, written in its own tx (survives a rolled-back request), callable without a tenant context.
  - `AuditSealer` (role `yhrm_audit_sealer` connection): `sealPending` per tenant via `audit.seal_batch`, `anchor(day)` into `chain_anchor`; `AuditSealerScheduling` runs it only when `yhrm.audit.sealer.url` is set (5 s + 00:05 VN daily). WORM export of anchors is SaaS storage's job (not done).
  - `AuditQueryService` / `/api/v1/core/audit/events` (`core.audit.view` tenant-wide; filters date range, user, module = `object_schema`, entity, record, action, LE, channel, IP, correlation, `security=true`); open searches ≤ 366 days, record/user/correlation searches 10 y; values of `pii:P2/P3` columns (read from the DDL column comments) masked unless `core.audit.view_sensitive`; every search audited `AUDIT_SEARCH`. `POST /audit/chain/verify` (verify_chain + anchors, audited `AUDIT_VERIFY`). Export = SaaS report framework (not built).
- **Documents P1 (BR-CORE-100..102, US-CORE-100..104):**
  - SaaS ports in shared/common: `storage.ObjectStorage` (put with/without scan, get, signed URL), `crypto.DataKeys` (DEK wrap/unwrap), `onboarding.OpeningBalanceLocks`. No implementations yet: document upload → 503 `storage-unavailable`, import parse → `key-service-unavailable`.
  - `DocumentTypeCatalogue` + `DocumentOwnerResolver` SPIs (owner module maps a record to an `AuthorizationTarget`; fallback LE, else tenant). Core types: EVIDENCE, LE_LETTERHEAD, LE_SEAL (images), IMPORT_FILE.
  - `core.api.Documents` / `DocumentService`: limits from settings `core.document.max_upload_mb` (20) and `core.document.allowed_media_types` (or the type's list), extension + magic-number check; key `<tenant>/<module>/<yyyy>/<uuid>`; PENDING_SCAN until `core.api.DocumentScanResults.report(tenant, key, CLEAN|INFECTED|ERROR)` (INFECTED → QUARANTINED, audit QUARANTINE, notify uploader `core.DocumentQuarantined`); versions become current when clean; signed URL 15 min; CONFIDENTIAL/RESTRICTED downloads audited `DOWNLOAD` (sensitive read). `/api/v1/core/documents/**`, `/document-types` (`@PublicEndpoint`, checked per type).
  - Templates: `DocumentTemplateCatalogue` (module merge fields) + Core fields `doc.number/date`, `le.*`, images `le.letterhead/le.seal` (DDL `legal_entity.letterhead_doc_id/seal_doc_id`, `PUT /legal-entities/{id}/letterhead`). `TemplateRenderer`: `{{key}}` placeholders; HTML → PDF/A-2b (OpenHTMLtoPDF, embedded Noto Sans in `resources/fonts`); DOCX merged with poi-tl (`{{@key}}` images); DOCX → PDF needs Gotenberg (not wired: 503 `pdf-converter-unavailable`, use output DOCX). `/api/v1/core/document-templates` (`core.document_template.{view,manage}`): create per LE, upload version (unknown fields 422), preview with samples, publish from a date (previous ends). `core.api.DocumentGeneration.generate` picks LE → tenant → global (copied on first use), numbers in the caller's tx, stores GENERATED (scan SKIPPED), audits GENERATE. Template sources are stored without a scan (parsed, never served).
- **Import engine P1 (BR-CORE-110..112/114, US-CORE-015/025/110..117):**
  - `core.api.ImportType` SPI: definition (columns with types / lookups / sensitive flag, business key, permission, partial commit, opening balance, dependsOn, control checks) + `validate` / `commit` / `undoBlockers` / `undo` / `stagedTotals` / `committedTotals`. Engine adds optional `external_ref` column. Lookups: `CODE_LIST:<list>`, LEGAL_ENTITY, LOCATION, ORG_UNIT, ORG_UNIT_TYPE (code, "CODE - Name" or unaccented name; ambiguity reported).
  - Flow: upload (IMPORT_FILE document, job UPLOADED) → clean scan event → worker (virtual threads; SaaS job queue later) parses XLSX by SAX / CSV into `import_row` (AES-GCM with job DEK, AAD = job id; preview of non-sensitive columns) → validate (dry-run) → VALIDATED, or RECONCILED for OPENING_BALANCE (control sheet vs staged, ROW_COUNT built in) → waive (reason) → sign-off → commit on a worker in ONE transaction (errors → refused unless partial; FAILED with the row number) → COMMITTED (+30 d purge due) → rollback ≤ 24 h unless the handler names blockers. Template stamp (`_meta`: code + version) checked; headers matched by key / VI / EN.
  - Channel IMPORT + correlation id = job id during steps; OB commit sets `app.audit_mode = BATCH` and writes `IMPORT_CHUNK` (actor IMPORT) per 1000 rows; `CoreEvents.ImportCommitted(initialLoad)` / `ImportRolledBack`. DDL: `import_job.source_columns/processed_count/failure_reason` (`processed_count` skipped by row audit), `import_row.commit_action`.
  - Excel: template (VI/EN headers, comments, dropdowns on hidden `_lists`, HUONG_DAN/INSTRUCTIONS, CONTROL for OB), error file (user columns + row, status, messages), reconciliation report with sign-off block. `/api/v1/core/imports/**` (`@PublicEndpoint`, type permission checked); history needs `core.import.view` or the type's permission.
  - Core types: `CORE_COST_CENTRE`, `CORE_ORG_UNIT` (parent by code, existing or earlier row) with `core.import.run`; both upsert (UPDATED rows block rollback).
- **Still open (090..115):** retention 094/104 (P2), e-signature 103 (P2), scheduled export 115 (P3); audit export, WORM anchor export, sealer on the SaaS scheduler; storage/KMS/LPS/Gotenberg implementations; scan timeout (≤ 60 s) monitor; staging purge scheduling (`ImportService.purgeDue` per tenant); progress SSE (polling only); chunked commit with batch compensation for very large files; location / job title / grade import types (US-CORE-025 partly).
- **Not yet built:** LE bank accounts (US-CORE-005: envelope encryption), legal rep ID document (P3), People-driven user provisioning.

## 12. Where to look
- **BRD:** `docs/01-requirements/brd/modules/01-core.md` (Vietnamese):
  - §1 scope, §3 processes, §4 BRs
  - §5 VN compliance, §6 entities, §7 interfaces
  - §9 NFR, §10 open questions/risks
- **Phase:** `docs/01-requirements/brd/mvp-scope.md` §9.1, plus §5 and §11 (Plan B, R2-19).
- **Ownership and events:** `docs/01-requirements/brd/gap-analysis.md` §5 and §6.
- **Decisions:** `docs/00-project-management/decision-log.md`:
  - R2-02 ownership, R2-11 X-02 overrides, R2-17 payslip PDF
  - R2-18 baseline, R2-19 Plan B, R3-09 custom fields
  - R3-10 GAP-049, OD-4/OD-8, D9 purge bypass
- **DDL:** `database/postgresql/ddl/002_core.sql`.
- **Database design:** `database/postgresql/DESIGN.md` §5.3 (as-of org and data scope), §7.2 (core entities), §9.2 (audit), §9.4 (masking views), §13 (opening-balance import).
- **Backlog:** `docs/01-requirements/user-stories/core-backlog.md` (EP-CORE-01..11, US-CORE-*; §3 coverage).
- **ADRs:** in `docs/02-architecture/adr/`:
  - 0005 identity (Keycloak)
  - 0006 authz enforcement
  - 0007 workflow engine
  - 0011 scheduler
  - 0015 document generation
  - 0024 effective dating
  - 0027 audit chain
  - 0035 global vs tenant reference data
  - 0037 opening-balance import
- **Code:** `backend/src/modules/core/src/main/java/vn/yourhrm/core/` (`api`, `internal/{domain,application,infra,web}`). The shared authz API is `backend/src/shared/auth/.../vn/yourhrm/shared/auth/api/`.
