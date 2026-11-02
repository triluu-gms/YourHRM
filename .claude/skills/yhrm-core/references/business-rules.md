# Core: BR and NFR index

**94 IDs: 79 BR-CORE and 15 NFR-CORE.** Every ID in `docs/01-requirements/brd/modules/01-core.md` §4 and §9 is listed.

The 79 BRs split into 45 P1, 25 P2, 5 P3 and 4 Removed. The 15 NFRs split into 13 P1 and 2 P2.

- **MoSCoW** comes from the BRD and is business priority.
- **Phase** comes from `docs/01-requirements/brd/mvp-scope.md` §9.1. Phase is authoritative for what to build.
- "Removed" IDs are kept and never reused. Their capability moved to another module.

Read this file when you implement a specific BR, then read the full BRD row. The BRD text is Vietnamese.

## 4.1 Company, legal entity, location
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-001 | A tenant has one company-group profile and 1..n legal entities. The max count comes from the SaaS plan. | M | P1 |
| BR-CORE-002 | A legal entity stores names VI/EN, MST, BHXH unit code, business reg, address, legal rep, region, fiscal-year start, bank accounts, seal/signature, and status. Also effective-dated: tax authority, PIT declaration frequency, and the BHTNLĐ-BNN rate decision (GAP-049). | M | P1 |
| BR-CORE-003 | Validate MST as 10 digits, or 13 digits `NNNNNNNNNN-NNN` for a dependent unit. It is unique per tenant. `[verify-legal]` | M | P1 |
| BR-CORE-004 | Statutory legal-entity attributes (MST, BHXH code, region, address) are effective-dated and queryable as-of a date. | M | P1 |
| BR-CORE-005 | A location belongs to exactly one legal entity. Fields: code, type, address, region, time zone, dependent tax code. Geo-fence and Wi-Fi/IP are optional in P1 and used only in P2. | M | P1 |
| BR-CORE-006 | Resolve the min-wage region (I–IV) per LE/location as-of a date. Read it from the Legal Parameter Service admin-unit→region map. Core is not the master. | M | P1 |
| BR-CORE-007 | Recompute the region when an address or the pack map changes, then emit `core.LocationChanged` (subtype `LocationRegionChanged`). | M | P1 |
| BR-CORE-008 | Record relationships between legal entities (parent/subsidiary, shared service). | C | P3 |

## 4.2 Organisation structure
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-010 | Keep a hierarchical org tree with configurable unit types and at least 12 levels. | M | P1 |
| BR-CORE-011 | Org-unit attributes: code (unique, immutable once used), names, type, parent, LE, location, manager, cost centre, status, dates, and sort order. | M | P1 |
| BR-CORE-012 | All org-unit attributes and parentage are effective-dated. Support "tree as-of D" and "history of unit X". | M | P1 |
| BR-CORE-013 | Org changes go through a reorg package: effective date, impact analysis, approval, and scheduled atomic activation. | M | P2 |
| BR-CORE-014 | Merge, split and dissolve need position/employee mappings. Block activation while active employees are unmapped. People replies with `ReorgMappingCompleted`. | M | P2 |
| BR-CORE-015 | A retro reorg needs a special permission and notifies Payroll and T&A. It checks the LE lock status from Payroll (IF-PAY-P-20). | M | P2 |
| BR-CORE-016 | Interactive org chart, exportable to PDF/PNG. | S | P2 |
| BR-CORE-017 | Matrix (functional) reporting lines. | C | P3 |

## 4.3 Job architecture and cost centres
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-020 | Keep job families and job titles. A job title carries a JD, a default grade range, an occupation code, and a heavy/hazardous flag with category. `[verify-legal]` | M | P1 |
| BR-CORE-021 | Grade catalogue (structure, code, name, order), effective-dated. It has **no pay ranges**; Payroll owns those. | M | P1 |
| BR-CORE-022 | A position is a budgeted seat: job title, org unit, location, grades, reports-to, FTE budget, cost centre, status, and a critical flag. | M | P2 |
| BR-CORE-023 | Tenant setting `position_control` = strict / soft / off. | M | P2 |
| BR-CORE-024 | Position occupancy: budget vs filled (People) vs recruiting (Rec). | S | P2 |
| BR-CORE-025 | Cost centres (code, LE, GL segment, owner, dates), with defaults per org unit and per position. People owns the % split on assignments. | M | P1 |
| BR-CORE-026 | Employee-category code list. It is used in workflow, scope and payroll conditions. | M | P1 |

## 4.4 Users, roles, permissions, data scope
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-030 | Manage tenant user accounts: login, email, mobile, optional employee link, locale, status ACTIVE/FORMER_EMPLOYEE/DEACTIVATED, expiry, and identity link. A status change emits `core.UserStatusChanged`. | M | P1 |
| BR-CORE-031 | Permissions are functional (view/create/update/delete/approve/export/import/view-sensitive) and grouped into roles. Tenants copy system roles or create custom ones. Modules register their permissions at deploy. | M | P1 |
| BR-CORE-032 | Every role assignment carries a data scope: tenant, LE(s), location(s), org unit(s) ± subtree, own subtree as manager, self, or a custom rule. Effective scope is the union of assignments. | M | P1 |
| BR-CORE-033 | LINE_MANAGER and SELF scopes are derived automatically and follow reorgs and transfers with no admin action. | M | P1 |
| BR-CORE-034 | Field-level masking by role (`view-sensitive`). Salary needs the `compensation` permission, and every view is audited. | M | P1 |
| BR-CORE-035 | Scope filtering is enforced server-side on every query, report, export, API and notification recipient, through one Core authorisation service. | M | P1 |
| BR-CORE-036 | Time-bound role assignments, with approval (`CORE_ROLE_GRANT`) for privileged roles. | S | P2 |
| BR-CORE-037 | Configurable segregation-of-duties rules block the conflicting grant or action. | S | P2 |
| BR-CORE-038 | Removed. Local login, password policy, MFA and sessions are now BR-SAAS-038/039/043. | M | Removed |
| BR-CORE-039 | Removed. SSO OIDC/SAML and SCIM are now BR-SAAS-040/041/042. Role assignment stays in Core. | M/S/C | Removed |
| BR-CORE-040 | Access delegation ("act as"), distinct from approval delegation and fully audited. | C | P3 |
| BR-CORE-041 | Platform support reaches tenant data only through time-boxed break-glass sessions approved by the tenant and logged in the tenant audit. | M | P1 |
| BR-CORE-042 | Removed. API clients and credentials are now BR-SAAS-056. Core keeps only the API-scope→permission/scope mapping. | S | Removed |

## 4.5 Workflow and approval engine
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-050 | One generic workflow engine. Modules register process types with a summary schema, condition fields, allowed actions, a callback, and an auto-approve-on-timeout flag. | M | P1 |
| BR-CORE-051 | No-code workflow-definition designer: conditions, ordered steps, versioning, and activation date. P1 uses vendor-seeded definitions. | M | P2 |
| BR-CORE-052 | Approver resolvers: line manager, N-level manager, manager of a unit, position holder, role-in-scope, named user, requester-picked, request field, and functional manager. | M | P1 |
| BR-CORE-053 | Step types: single, any-of, all-of, parallel branches, conditional skip, FYI. P1 has sequential single-approver steps only. | M | P2 |
| BR-CORE-054 | Actions: submit, approve, reject (reason required), return, request info, withdraw, forward, comment, attach, bulk approve. | M | P1 |
| BR-CORE-055 | User-defined approval delegation by date range and process type. One level only, and the delegate must satisfy the data scope. | M | P2 |
| BR-CORE-056 | HR admin can reassign any open task, with a mandatory reason. Automatic fallback applies when the resolved approver is inactive, missing, or is the requester. | M | P1 |
| BR-CORE-057 | SLA in working time from the T&A calendar, with reminders, escalation, and auto-approve/reject. | M | P2 |
| BR-CORE-058 | Snapshot the approver chain at submit, and re-resolve open steps on org change according to the tenant setting. | M | P2 |
| BR-CORE-059 | Approve from the mobile app or from signed, single-use e-mail/Zalo links (expiry ≤72h). | S | P2 |
| BR-CORE-060 | Emit lifecycle events (`WorkflowSubmitted`, `StepAssigned`, `StepCompleted`, `WorkflowCompleted`, `WorkflowWithdrawn`, `SlaBreached`) and provide a unified "My tasks / My requests" inbox. | M | P1 |
| BR-CORE-061 | Approval history (who, on behalf of, when, action, comment, IP/device) is immutable and printable. | M | P1 |

## 4.6 Notifications
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-070 | Notification centre. Core owns the in-app inbox; email, push, Zalo and SMS go through the SaaS delivery adapter. Modules call only Core. P1 channels are in-app and e-mail. | M (in-app, email) / S (push, Zalo) / C (SMS) | P1 |
| BR-CORE-071 | Templates per event code × channel × VI/EN, with tenant override and preview. In P1 templates are vendor-maintained with no override. | M | P2 |
| BR-CORE-072 | User channel preferences per category. Mandatory categories can change channel but cannot be switched off. | S | P2 |
| BR-CORE-073 | Scheduling, quiet hours, digest, de-duplication, channel fallback, and delivery status. | S | P2 |
| BR-CORE-074 | Never send sensitive content unencrypted by email, Zalo or SMS; use a secure deep link. The only exception is the payslip PDF protected by an employee-set password (R2-17). Zalo/SMS never carry amounts. | M | P1 |
| BR-CORE-075 | Company announcements to scoped audiences, with read receipts. | C | P3 |

## 4.7 Configuration and master data
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-080 | Core owns the global master-data store and its admin API; tenants can only read it. It holds the 34-province / 2-level admin units from 2025-07-01 with history from the 63-province model, released through SaaS back-office. `[verify-legal]` | M | P1 |
| BR-CORE-081 | Keep old admin-unit codes and an old→new mapping, so legacy addresses (opening-balance import, old CMND, birthplace) stay valid. | M | P1 |
| BR-CORE-082 | Global code lists: country (ISO 3166), 54 ethnic groups, religion, ID-doc types, banks and branches (SBV/NAPAS, SWIFT), currency (ISO 4217), language, education, marital status, relationship, gender. | M | P1 |
| BR-CORE-083 | Tenant custom code lists, and tenant extensions of global lists. | M | P2 |
| BR-CORE-084 | Hierarchical typed settings (tenant → LE → location) with validation, optional effective dating, and audit. | M | P1 |
| BR-CORE-085 | Numbering service: patterns per tenant/LE/document type, an optional gap-free mode, and reset rules. | M | P1 |
| BR-CORE-086 | One custom-field framework (UDF) across modules' registered entities. SaaS has none. | M | P2 |
| BR-CORE-087 | Distribute global master-data updates with effective dates (`core.MasterDataReleased`), and report tenant references to retired values. | M | P2 |
| BR-CORE-088 | Exchange-rate table: currency pair, rate type, date, source, value. Manual entry or import, with tenant override rows. Payroll reads it (IF-PAY-C-33). | new (2026-10-16), BRD priority S | P2 |

## 4.8 Audit log
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-090 | Audit every create, update and delete of business data in every module. Record before/after values (masked), actor, on-behalf-of, time, IP, UA, channel and correlation id. | M | P1 |
| BR-CORE-091 | Log security events, including SaaS identity and operator events written into Core's store. Only platform-only events go to `saas.audit_event`. | M | P1 |
| BR-CORE-092 | The audit log is append-only and tamper-evident. Nobody, including tenant admins, may edit or delete it. | M | P1 |
| BR-CORE-093 | Audit search with filters, plus export. Access needs `AUDIT_VIEW` and is itself audited. | M | P1 |
| BR-CORE-094 | Retention per category, with legal floors: security ≥2y, payroll/accounting ≥10y `[verify-legal]`. Purges are logged. | M | P2 |

## 4.9 Documents and files
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-100 | Document-service API: upload limits, malware scan before use, SaaS object storage (tenant prefix, KMS), metadata, versions. | M | P1 |
| BR-CORE-101 | File access follows the owning record's permission and scope. Downloads use short-lived signed URLs, and access to confidential files is audited. | M | P1 |
| BR-CORE-102 | Document templates (DOCX/HTML → PDF) with module-supplied merge fields, numbering (BR-CORE-085), and LE letterhead/seal. | M | P1 |
| BR-CORE-103 | E-signature orchestration. Vendor connectors live in the SaaS integration hub. `[verify-legal]` | S | P2 |
| BR-CORE-104 | The retention class on a document is a key into the SaaS retention policy, with legal-hold override. | S | P2 |

## 4.10 Import / export
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-CORE-110 | One import engine. Modules register types (template, mapping, validators, commit/undo handlers, partial-commit flag). Commits are all-or-nothing by default, with rollback within 24 h if nothing depends on the data. | M | P1 |
| BR-CORE-111 | Imports run as async jobs: staging, per-row VI/EN messages, dry-run, error file, commit, progress, history. At least 50,000 rows per file. | M | P1 |
| BR-CORE-112 | Generate Excel templates with code-list dropdowns, type hints and an instructions sheet. Lookups accept a code or a name. | M | P1 |
| BR-CORE-113 | Removed. List/report export is now SaaS BR-SAAS-046/053. Core provides the scope predicates and masking policy. | M | Removed |
| BR-CORE-114 | Opening-balance import mode for go-live: bulk load, bypass workflow, keep external ref ids, effective at cut-over, and produce a reconciliation report (D-09, replaces Oracle conversion). | M | P1 |
| BR-CORE-115 | Scheduled exports and outbound integrations (SFTP, webhook). | C | P3 |

## 9. Non-functional requirements
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| NFR-CORE-001 | Tenant isolation: every Core table except global master data has `tenant_id` and RLS, with automated isolation tests. | M | P1 |
| NFR-CORE-002 | The authz/scope check adds ≤20 ms p95. Use an index-friendly predicate (materialised path / closure). | M | P1 |
| NFR-CORE-003 | The as-of org tree for 10k employees and 2k units returns in ≤1 s p95. | M | P1 |
| NFR-CORE-004 | Workflow submit ≤500 ms p95, ≥50 submits/s per node, no lost tasks (outbox, at-least-once, idempotent callbacks). | M | P1 |
| NFR-CORE-005 | In-app notification ≤5 s. Email etc. queued in ≤1 min p95. Payslip release for 10k employees in ≤30 min. | M | P1 |
| NFR-CORE-006 | Login availability 99.9%/month. No synchronous IdP call per request. | M | P1 |
| NFR-CORE-007 | Adaptive password hashing (SaaS-held), per-tenant KMS encryption at rest, TLS 1.2+. | M | P1 |
| NFR-CORE-008 | Audit writes happen in the business transaction or are guaranteed via the outbox. The store is tamper-evident. A 1-year audit query for one record takes ≤3 s. | M | P1 |
| NFR-CORE-009 | Validating a 50k-row import takes ≤5 min; a 100k-row async export takes ≤5 min. | S | P1 |
| NFR-CORE-010 | Upload limit is configurable (default 20 MB). AV scan ≤60 s. Signed URL expires in ≤15 min. | M | P1 |
| NFR-CORE-011 | Everything in VI and EN. Accent-insensitive Vietnamese search. UTF-8 Excel-compatible exports. | M | P1 |
| NFR-CORE-012 | WCAG 2.1 AA, and responsive mobile approval. | S | P2 |
| NFR-CORE-013 | Export/import Core configuration between environments of one tenant. | S | P2 |
| NFR-CORE-014 | OWASP ASVS L2, anti-brute-force, and rate limits per tenant, user and API client. | M | P1 |
| NFR-CORE-015 | Effective-date activation jobs run at 00:00 Asia/Ho_Chi_Minh, are idempotent, and can be re-run. | M | P1 |

## MoSCoW notes
- **BR-CORE-086:** the BRD says M (changed S→M on 2026-10-16, R2-02). mvp-scope §9.1 still prints "S" in its old-priority column. The phase is P2 in both.
- **BR-CORE-070:** the priority is split. The BRD gives M for in-app and e-mail, S for push and Zalo, and C for SMS. mvp-scope §9.1 prints "M/S/C".
- **BR-CORE-088:** the BRD gives S. mvp-scope §9.1 marks it "new".
