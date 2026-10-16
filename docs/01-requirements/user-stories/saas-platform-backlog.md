# SaaS Platform Backlog: Phase 1 (v0.1, 2026-10-16)

| Item | Value |
|---|---|
| Owner | BA – SaaS Platform & cross-cutting |
| Scope | Every P1 `BR-SAAS` and `NFR-SAAS` in [mvp-scope.md](../brd/mvp-scope.md) §9.6 (42 BR + 17 NFR), from [06-saas-platform.md](../brd/modules/06-saas-platform.md) v0.3 |
| Method | [estimation-guide.md](estimation-guide.md) v0.1 (Fibonacci SP against the §4 reference scale; 1 SP ≈ 1 developer-day before the +40% overhead) |
| Decisions applied | D-09 (opening-balance import, no migration), D-10/R2-12 (billable = active person at month end per legal entity; suspended not billable), R2-01/R2-19 (no live integrations; feature flags, async exports, metering, break-glass cut), R2-02 (single-owner table), R2-03 (payroll comparison P1), R2-04 (no SSO), R2-18 (baseline always on), R3-01 (no e-invoice in P1), R3-03 (multi-contract insurance rule as legal parameter), R3-05 (no ESS without e-mail), R3-08 (operator access log P1), R3-09 (custom attributes mapped at validation), OD-8 (former-employee e-mail OTP) |

## 0. Split with other backlogs

This backlog holds the **product-facing platform features**: what tenants, employees, operators and the regulatory content owner see and use.

| Not in this backlog | Where it is |
|---|---|
| Keycloak deployment, realms/Organizations set-up, token/BFF plumbing; tenancy context, RLS, isolation test suite; outbox/event bus; job queue runtime and workers; Legal Parameter Service runtime (`get`/`resolveSet`, caching, events); object storage; SMTP relay hosting; CI/CD, environments, observability, backup/DR, encryption, secrets, security and performance testing | `technical-enablers-backlog.md` (`US-TECH-…`) |
| Workflow engine, notification centre, import engine (validate/commit/rollback), audit store, document service, masking policy and data-scope predicate, global master data store and admin API | `core-backlog.md` (`US-CORE-…`) |
| Content of the opening-balance templates (import types and handlers), statutory forms (D02-LT, 05/KK, 05/QTT, labour reports), bank file and GL journal layouts, payslip PDF password | Owning module backlogs (People, T&A, Payroll), per guide §4 |

Stories here only *register on* or *configure* those engines. **Depends on** names the enabler by capability (`TECH: …`, `CORE: …`) where the other backlog's story ID was not yet published when this file was written; the PM links the IDs at consolidation.

## 1. Epics

| Epic ID | Name | Goal | Stories | SP |
|---|---|---|---|---|
| EP-SAAS-01 | Tenant lifecycle & provisioning | An operator provisions a working pilot tenant in ≤ 10 min and manages its lifecycle state and settings | 6 | 27 |
| EP-SAAS-02 | Subscriptions, entitlements & billable count | Modules are sold per tenant, baseline capabilities are always on, and vendor finance gets a month-end billable count per legal entity (no e-invoice in P1) | 6 | 32 |
| EP-SAAS-03 | Operator back-office & support access | Operators work from a separate console, reach tenant data only through tenant-approved, masked, logged grants, and release global master data | 8 | 34 |
| EP-SAAS-04 | Identity & access (as users see it) | Login, password policy, MFA (TOTP / e-mail OTP), sessions, ESS activation and former-employee login | 9 | 39 |
| EP-SAAS-05 | Legal Parameter Service: business features | The content owner maintains, approves and publishes legal parameter packs; tenants see the values in force and request permitted overrides | 8 | 39 |
| EP-SAAS-06 | Privacy & compliance (PDPL) | PD inventory, consent registry, DSR handling, DPIA inputs and breach handling for the tenant as controller and the vendor as processor | 8 | 37 |
| EP-SAAS-07 | Reporting, export & statutory template framework | One report shell and export path for all modules, versioned statutory templates, and an outbound-file centre for bank/GL/eTax files | 7 | 39 |
| EP-SAAS-08 | ESS web shell, localisation & e-mail channel | Responsive ESS web shell, full vi/en, and e-mail delivery of Core notifications | 6 | 22 |
| EP-SAAS-09 | Opening-balance go-live programme | Each legal entity goes live mid-year from validated, reconciled, signed-off and locked opening balances, with an optional one-month payroll comparison | 12 | 60 |
| EP-SAAS-10 | Platform operations features | Job monitor, per-tenant calculation toggles and release rings, release notes and maintenance notices, support entry point, tenant quotas | 5 | 13 |
| **Total** | | | **75** | **342** |

## 2. Stories

### EP-SAAS-01 Tenant lifecycle & provisioning

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-001 | EP-SAAS-01 | As a platform operator, I want a tenant record with lifecycle states so that login, jobs and billing follow the tenant's contractual status. States Trial…Deleted per BR, allowed transitions only; every transition logged with actor, reason, timestamp; P1 uses Pending Provisioning, Active, Suspended-ReadOnly, Suspended-Locked (set manually; no dunning) | BR-SAAS-001 | 5 | TECH: tenancy context | State machine with history = 5. Trial/Expired/Terminated states exist but have no P1 automation (BR-SAAS-004/005/017/018 are P2) |
| US-SAAS-002 | EP-SAAS-01 | As a platform operator, I want tenant state enforced across all modules so that a suspended tenant cannot run new work but employees keep statutory access. Read-only blocks writes with code `TENANT_READ_ONLY`; Locked lets only the Tenant Owner log in; allow-list always open: own payslips/PIT certificates, Owner export, DSR; jobs of locked tenants paused | BR-SAAS-001, BR-SAAS-090 | 5 | US-SAAS-001; TECH: job queue runtime | Cross-module guard plus allow-list |
| US-SAAS-003 | EP-SAAS-01 | As a billing operator, I want a billing party per tenant linked to its Core legal entities so that invoices go to the right buyer while Core stays the legal-entity master. CRUD buyer MST, name, address, e-invoice identity; links to `core.legal_entity.id`; plan limit on legal-entity count enforced on Core create-LE | BR-SAAS-002 | 3 | US-SAAS-001; CORE: legal entity API | One CRUD entity |
| US-SAAS-004 | EP-SAAS-01 | As an onboarding consultant, I want to provision a fully working shared-tier tenant from the back-office so that a new customer can start within 10 minutes. Wizard: tenant, billing party, first legal entity (via Core API), modules, contract dates, committed seats, owner contact; automated steps: tenant record + RLS context, Keycloak organisation and owner invitation, reference data seed, binding to the current legal pack, baseline entitlements; progress shown, automatic rollback and operator alert on any failed step, end-to-end ≤ 10 min | BR-SAAS-003 | 8 | US-SAAS-001, 003, 007, 010; TECH: Keycloak, tenancy/RLS, job queue; CORE: legal entity API, master data | `[high-uncertainty]`: orchestration of several services with compensation on failure; the 10-min target depends on Keycloak and seed timings |
| US-SAAS-005 | EP-SAAS-01 | As a Tenant Owner, I want an activation link to set my password and enrol MFA so that I can take over my new tenant securely. Link single-use, expires (default 72 h), resend by operator; MFA enrolment mandatory before first access; activation logged | BR-SAAS-003, BR-SAAS-039 | 3 | US-SAAS-004, 023 | |
| US-SAAS-006 | EP-SAAS-01 | As a Tenant Admin, I want to set tenant defaults so that every user sees our language, time zone and formats. Default language vi/en, time zone (default Asia/Ho_Chi_Minh), date/number format, currency (VND), fiscal year, week start, logo; per-user language override; changes audited | BR-SAAS-025 | 3 | US-SAAS-001, 055 | One settings entity |

### EP-SAAS-02 Subscriptions, entitlements & billable count

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-007 | EP-SAAS-02 | As a billing operator, I want to record the module subscription of each tenant so that each module can be sold and ended independently. Subscription with items per module (`core` mandatory; people, time-attendance, payroll) with start/end dates, contract price per module and committed seats; dependency matrix validated (e.g. Payroll needs People master + attendance import); ending a module makes its data read-only, never deleted | BR-SAAS-007 | 5 | US-SAAS-001 | Effective-dated = 5. Plans/editions and price books are P2 (BR-SAAS-006/010) |
| US-SAAS-008 | EP-SAAS-02 | As a module developer, I want one entitlement resolver and check API so that every UI, API and job asks the same question "is feature X on for tenant T (and legal entity E) now?". Resolves PLAN/ADDON/OVERRIDE/BASELINE rows into `saas.entitlement`; `@Entitled` on API and job entry points returns HTTP 403 `FEATURE_NOT_ENTITLED`; `/me/capabilities`; `saas.EntitlementsChanged` invalidates the cache | BR-SAAS-008 | 8 | US-SAAS-007; TECH: event bus, walking skeleton | Platform rule engine used by every module |
| US-SAAS-009 | EP-SAAS-02 | As a tenant user, I want features we have not bought hidden so that the product shows only what we use. Menus and route guards driven by `/me/capabilities`; a direct URL to an un-entitled page shows a "not subscribed" page; data of an ended module visible read-only | BR-SAAS-008, BR-SAAS-007 | 3 | US-SAAS-008 | |
| US-SAAS-010 | EP-SAAS-02 | As the product owner, I want the baseline capabilities always on so that every tenant configuration works whatever modules it buys. `saas.baseline_capability` (Core, People basics, legal parameters, pay scales/bands + component catalogue, Excel attendance import) writes BASELINE rows for every tenant that no plan or override can disable; dependency matrix published in the plan catalogue; automated test matrix for "Payroll without T&A" and "People without Payroll" | BR-SAAS-091 | 5 | US-SAAS-008 | Release-blocking configuration tests |
| US-SAAS-011 | EP-SAAS-02 | As a billing operator, I want the platform to take the billable-employee count at each month end so that billing follows decision D-10. Count per legal entity and module of persons with an active employment on the last day of the month; pre-hire, terminated, suspended and history-only records excluded; a person in two legal entities counts twice; for payroll, a person paid in that month's regular run also counts; daily snapshots kept for information; quarterly/annual = sum of monthly counts | BR-SAAS-012 | 8 | US-SAAS-007; People: month-end active-person feed (CO-05); TECH: job queue | Complex rule with many cases. `[high-uncertainty]`: treatment of collaborators (CTV) not on payroll still open (BRD Q4) |
| US-SAAS-012 | EP-SAAS-02 | As vendor finance, I want a month-end usage report per tenant so that I can issue the invoice from our own e-invoice tool. Per tenant, legal entity and module: billable count, committed seats, max(committed, billable) × contract price; Excel export; Tenant Owner sees own billable trend | BR-SAAS-012 | 3 | US-SAAS-011, 048 | No e-invoice, payment or dunning in P1 (R3-01; BR-SAAS-013/014/015/017 are P2) |

### EP-SAAS-03 Operator back-office & support access

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-013 | EP-SAAS-03 | As a platform operator, I want a back-office separate from the tenant UI so that vendor work never mixes with tenant sessions. Separate operator realm with mandatory MFA and IP allow-list; operator roles (support, onboarding, billing, regulatory content owner, SRE) with menu per role; tenant portfolio list (state, modules, legal entities, billable count) | BR-SAAS-032 | 5 | TECH: Keycloak operator realm | Realm plumbing is TECH; this story is the console, role model and portfolio |
| US-SAAS-014 | EP-SAAS-03 | As a platform operator, I want to prepare a global master data release so that all tenants receive new admin units, banks or nationalities in one controlled change. Draft release by editing or Excel upload (admin units with old→new mapping, banks, nationalities); validation and diff against the current release; effective date and change notice text vi/en | BR-SAAS-032 | 5 | US-SAAS-013; CORE: global master data store and admin API | Excel import template = 5 |
| US-SAAS-015 | EP-SAAS-03 | As a platform operator, I want a release reviewed and published so that master data changes are four-eyes checked and traceable. Review by a second operator; publish loads the versioned release through the Core admin API (`core.MasterDataReleased`); release history; failed load reported, nothing partially published | BR-SAAS-032 | 3 | US-SAAS-014; CORE: master data admin API | Core owns the store and the tenant-reference report (BR-CORE-080/087) |
| US-SAAS-016 | EP-SAAS-03 | As a support agent, I want to request a support grant from the tenant so that I can diagnose an issue only with the customer's consent. Request with ticket, modules, read-only/read-write, sensitive-data flag, duration ≤ 72 h, reason; Tenant Admin/Owner approves or rejects in-app; grant auto-expires or can be revoked; session summary sent to the tenant at expiry | BR-SAAS-033 | 5 | US-SAAS-013; CORE: workflow, notifications | Approval workflow = 5 |
| US-SAAS-017 | EP-SAAS-03 | As a Tenant Admin, I want operators blocked from our data outside an active grant so that the vendor has no default access. Operator token has no tenant context unless a grant is active; grant id carried in context; persistent banner; read-only grants reject writes; every action written to the tenant audit with operator and grant | BR-SAAS-033, BR-SAAS-085 | 5 | US-SAAS-016; TECH: tenancy context; CORE: audit store | Enforces BR-CORE-041 from the platform side |
| US-SAAS-018 | EP-SAAS-03 | As a DPO, I want sensitive fields masked in support sessions so that operators do not see salary, bank or ID data without explicit consent. Salary amounts, bank account, CCCD, individual tax code, health/SI medical data and dependants masked in screens, APIs, reports and exports unless the grant includes "sensitive data"; masking tested per module | BR-SAAS-034 | 5 | US-SAAS-017, 047; CORE: masking policy | Uses the Core masking policy; this story adds the grant-driven mode |
| US-SAAS-019 | EP-SAAS-03 | As a Tenant Admin, I want to see and export the log of all operator accesses to our tenant so that we can oversee our processor as PDPL requires. Who, when, why, ticket, grant scope, what was viewed or changed; filters; Excel export | BR-SAAS-037 | 3 | US-SAAS-017, 048 | Minimal form restored by R3-08. Two-person break-glass (BR-SAAS-036) is a manual procedure in P1 |
| US-SAAS-020 | EP-SAAS-03 | As a security officer, I want platform-level operator events kept tamper-evident so that operator actions without a tenant are also accountable. `saas.audit_event` with hash chain for operator events with no tenant (logins, tenant creation, pack publication, master data releases); operator access report in the back-office | BR-SAAS-085 | 3 | US-SAAS-013 | Tenant events go to the Core audit store |

### EP-SAAS-04 Identity & access (as users see it)

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-021 | EP-SAAS-04 | As a user, I want a clear vi/en login page so that I can sign in with my employee code or e-mail. Tenant-resolved login (company code or URL slug); username = employee code or work e-mail; forgot-password by e-mail link; lockout and expired-password messages in vi/en; product-branded theme | BR-SAAS-038, BR-SAAS-045 | 3 | TECH: Keycloak | Login flow configuration and theme; Keycloak set-up is TECH |
| US-SAAS-022 | EP-SAAS-04 | As a Tenant Admin, I want to configure our password policy so that it meets our security rules. Min length (default 10, min 8), complexity, history (default 5), optional max age, lockout after N failures (default 5 / 15 min), breached-password check against an offline list; policy pushed to the identity provider; change audited | BR-SAAS-038 | 5 | US-SAAS-021; TECH: Keycloak | Breached-list check and policy sync |
| US-SAAS-023 | EP-SAAS-04 | As a user, I want to enrol and use MFA by authenticator app or e-mail code so that my account is protected. TOTP enrolment by QR, e-mail OTP as alternative; recovery codes; Tenant Admin can reset a user's MFA (audited, step-up) | BR-SAAS-039 | 5 | US-SAAS-021, 058 | SMS OTP is P2 |
| US-SAAS-024 | EP-SAAS-04 | As a Tenant Admin, I want MFA enforced by role so that privileged users cannot skip it. Mandatory for Tenant Owner, Tenant Admin, HR/C&B, finance, payroll approvers and all operators; tenant setting for ESS users; step-up (`acr ≥ 2`) on sensitive actions (payroll approval, bank-file download, payslip password) | BR-SAAS-039 | 5 | US-SAAS-023; CORE: roles | |
| US-SAAS-025 | EP-SAAS-04 | As a Tenant Admin, I want session rules and forced logout so that unattended or stolen sessions do not stay open. Idle timeout (default 30 min web), absolute timeout, concurrent session limit; user "log out all devices"; admin forced logout of a user | BR-SAAS-043 | 5 | US-SAAS-021; TECH: Keycloak, BFF | Mobile sessions are P2 |
| US-SAAS-026 | EP-SAAS-04 | As a Tenant Admin, I want login access to follow the user's status in Core so that leavers and deactivated users lose access on time. Consume `core.UserStatusChanged`: DEACTIVATED disables credentials and revokes sessions; FORMER_EMPLOYEE revokes sessions and switches login to e-mail OTP; rehire restores normal login | BR-SAAS-043, BR-SAAS-045 | 3 | US-SAAS-025; TECH: event bus; CORE: user status | OD-8 |
| US-SAAS-027 | EP-SAAS-04 | As a former employee, I want to log in with a one-time code sent to my personal e-mail so that I can download my payslips and PIT certificates after I leave. Passwordless e-mail OTP to the personal e-mail captured at offboarding; limited former-employee access only; access ends at deactivation (tenant setting, default 24 months); no e-mail → no login, HR provides documents | BR-SAAS-045 | 5 | US-SAAS-026, 058; People: personal e-mail at offboarding | OD-8 accepted; SMS OTP P3 |
| US-SAAS-028 | EP-SAAS-04 | As an HR user, I want to activate ESS accounts in bulk so that employees can sign in without shared default passwords. Activation sent only to employees with a verified work or personal e-mail; first login by e-mail OTP then own password; codes expire and can be resent; report of employees without e-mail who get printed payslips instead | BR-SAAS-045 | 5 | US-SAAS-021, 058; CORE: user accounts | R3-05: no activation codes and no ESS for employees without e-mail |
| US-SAAS-029 | EP-SAAS-04 | As a Tenant Admin, I want a security and login activity report so that I can spot attacks and track MFA adoption. Security events (login success/failure, lockout, MFA enrol/reset, password change, session revocation, policy change) written to the Core audit store; report of logins, failures, locked accounts, MFA adoption, exportable | BR-SAAS-085 | 3 | US-SAAS-022–025, 046; CORE: audit store | |

### EP-SAAS-05 Legal Parameter Service: business features

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-030 | EP-SAAS-05 | As the regulatory content owner, I want a catalogue of typed parameter keys so that every statutory value has one definition used by all modules. Key types SCALAR, TABLE, MAP, CALENDAR_TEMPLATE; unit, legal reference, owner module, "override permitted by law" flag; modules register keys; new keys need no schema change | BR-SAAS-032, NFR-SAAS-016 (supports BR-PAY-010, NFR-PAY-007) | 5 | US-SAAS-013; TECH: LPS runtime | |
| US-SAAS-031 | EP-SAAS-05 | As the regulatory content owner, I want to edit a draft pack version so that a new legal instrument can be entered without a release. Draft with effective date and legal reference; scalar and table editors (e.g. PIT brackets); Excel upload for MAP (admin unit → minimum-wage region) and the national holiday template; diff against the previous pack | BR-SAAS-032, NFR-SAAS-016 (supports BR-PAY-010, BR-CORE-006) | 8 | US-SAAS-030 | Several editors plus an Excel import with validation |
| US-SAAS-032 | EP-SAAS-05 | As the regulatory content owner, I want packs to pass test and two approvals before publication so that no unchecked value reaches payroll. Lifecycle DRAFT → IN_TEST (golden scenario run result attached) → APPROVED (content owner + legal consultant) → PUBLISHED (immutable) → SUPERSEDED; corrections only as new versions; publication emits `saas.LegalParameterPackPublished` | NFR-SAAS-016 (supports BR-PAY-010) | 5 | US-SAAS-031; TECH: LPS runtime, golden test harness | Approval workflow = 5 |
| US-SAAS-033 | EP-SAAS-05 | As a Tenant Admin, I want notice of new packs and their effect so that we can prepare payroll. In-app and e-mail notice to all tenants ≥ 7 days before the effective date, or flagged "short notice"; for a past effective date, the list of affected periods is handed to Payroll/T&A for retro; pack adoption report (tenants and runs per pack version) | NFR-SAAS-016, NFR-SAAS-017 | 5 | US-SAAS-032; CORE: notifications | Retro recalculation itself is Payroll/T&A |
| US-SAAS-034 | EP-SAAS-05 | As a Tenant Admin, I want to request an override of a permitted parameter so that a more favourable company rule is applied lawfully. Only keys flagged "law permits"; scope tenant or legal entity; value, validity, evidence attachment; approval through Core workflow; versioned and audited; tenant view of effective values (pack + override) by date | BR-SAAS-032 (supports BR-PAY-010, BR-CORE-006) | 5 | US-SAAS-030; CORE: workflow, documents | |
| US-SAAS-035 | EP-SAAS-05 | As the regulatory content owner, I want the multi-contract insurance rule as a parameter per insurance type so that the rule changes without code if the consultant rules differently. `insurance.multi_contract_rule` for SI, HI, UI, OA-OD with values FIRST_CONTRACT, HIGHEST_SALARY, EACH_EMPLOYER; defaults SI/UI/HI = FIRST_CONTRACT, OA-OD = EACH_EMPLOYER; change history visible | NFR-SAAS-016 (supports BR-PAY-010; decision R3-03) | 3 | US-SAAS-031 | 2 SP + 1 step for `[verify-legal]` (HI highest-salary rule in doubt, decision-log §8.1). `[high-uncertainty]`. Calculation is Payroll |
| US-SAAS-036 | EP-SAAS-05 | As the regulatory content owner, I want the initial pack loaded and signed off so that the pilot's first payroll uses validated values. 2026 (and known 2027) values: regional minimum wages and the admin unit → region map, reference level, SI/HI/UI/OA-OD rates and caps, PIT schedule and deductions, casual threshold, OT caps, night window, leave entitlements, probation/notice limits, retirement roadmap, settlement deadline, holiday template; golden scenarios; consultant sign-off | NFR-SAAS-016 (supports BR-PAY-010, NFR-PAY-007) | 5 | US-SAAS-032 | 3 SP + 1 step for `[verify-legal]` (2026 PIT/SI values not yet validated, decision-log §5). `[high-uncertainty]` |
| US-SAAS-037 | EP-SAAS-05 | As a C&B user, I want to see which parameter values a result used so that any historic payroll can be explained and reproduced. From a payroll or attendance result's stamped pack, override, configuration and release versions, show the values in force; export for audit | NFR-SAAS-017 | 3 | US-SAAS-032; Payroll/T&A: version stamps on results | Stamping is in Payroll/T&A and TECH |

### EP-SAAS-06 Privacy & compliance (PDPL)

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-038 | EP-SAAS-06 | As a DPO, I want one personal data inventory for all modules so that we know what we hold, why and where. Modules register fields: category (basic/sensitive), purpose, legal basis, retention period and legal floor, owner module, processors and their hosting location; DPO view and Excel export | BR-SAAS-079, BR-SAAS-024 | 5 | US-SAAS-013 | Sub-processor location supports the residency evidence; hosting itself is TECH |
| US-SAAS-039 | EP-SAAS-06 | As a DPO, I want the retention period and legal floor of each data category recorded so that the documented floors are visible before purge jobs exist. Retention classes per data category (Core document retention classes are keys); legal floors; read-only for tenants; no purge in P1 | BR-SAAS-079 | 2 | US-SAAS-038 | Purge/anonymisation jobs are BR-SAAS-082 (P2: no retention expiry in the pilot year) |
| US-SAAS-040 | EP-SAAS-06 | As a DPO, I want versioned consent and notice texts per purpose so that each consent refers to the exact text the person saw. Purposes (platform defaults + tenant purposes), texts vi/en, versions with effective date; a new version can require re-consent | BR-SAAS-080 | 5 | US-SAAS-038 | Versioned entity = 5 |
| US-SAAS-041 | EP-SAAS-06 | As an employee, I want to give and withdraw consent in ESS so that I control optional uses of my data. Capture with timestamp, channel and text version; withdrawal any time; `hasConsent(subject, purpose, asOf)` API for modules; consent register report for the DPO | BR-SAAS-080, BR-SAAS-075 | 5 | US-SAAS-040, 053 | People/Recruitment hold references only |
| US-SAAS-042 | EP-SAAS-06 | As a DPO, I want to log and track data subject requests so that each one is answered on time with evidence. Request from ESS or entered by DPO; types access, rectification, deletion, restriction, objection, consent withdrawal; identity verification step; due date from a legal parameter with reminders; completion evidence | BR-SAAS-081 | 5 | US-SAAS-053; CORE: workflow, notifications | Deadline `[verify-legal]` (BRD Q5); held as a parameter, so no extra step. `[high-uncertainty]` noted only for the deadline value |
| US-SAAS-043 | EP-SAAS-06 | As a DPO, I want one-click export and anonymisation across modules so that a DSR is executed completely. Export of a subject's data from every module's registered handler into one archive; anonymisation requests fanned out, statutory retention honoured with a legal-hold report; per-module status | BR-SAAS-081 | 8 | US-SAAS-042, 038; all modules: DSR handlers (CO-07) | `[high-uncertainty]`: depends on every module delivering handlers; retention floors interact with D-04/D-05 DPO statements |
| US-SAAS-044 | EP-SAAS-06 | As a tenant DPO, I want a DPIA template prefilled with platform inputs so that I can build our own DPIA dossier quickly. Generates a document with PD inventory, processors, security measures and residency statement; vendor's own processor DPIA kept as a document | BR-SAAS-083 | 2 | US-SAAS-038; CORE: documents | Dossier content is Security/DPO work, not build |
| US-SAAS-045 | EP-SAAS-06 | As the vendor security officer, I want a breach incident register with notification templates so that tenants and the authority are notified within the deadlines. Incident: severity, affected tenants, data categories, timeline; tenant notification e-mail from template (target ≤ 24 h of confirmation); 72 h authority notification checklist and countdown; evidence kept | BR-SAAS-084 | 5 | US-SAAS-013, 058 | Notification content `[verify-legal]` (BRD Q5); only template text changes, so no extra step |

### EP-SAAS-07 Reporting, export & statutory template framework

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-046 | EP-SAAS-07 | As an HR user, I want one report catalogue for all modules so that I find and run every standard report the same way. Modules register reports (code, name vi/en, parameters, permission, data source); catalogue by module and role; parameter form, preview and run; "data as of" timestamp | BR-SAAS-046 | 8 | TECH: walking skeleton; CORE: permissions | Shared framework; module reports are counted in module backlogs |
| US-SAAS-047 | EP-SAAS-07 | As a DPO, I want reports and exports to respect data scope and masking so that nobody exports data they cannot see on screen. Core scope predicate applied to every report and export; field-level masking of sensitive fields; every export audited (who, report, parameters, row count) | BR-SAAS-046 | 5 | US-SAAS-046; CORE: scope predicate, masking, audit store | |
| US-SAAS-048 | EP-SAAS-07 | As an HR user, I want reports in Excel, PDF and CSV with correct Vietnamese text so that I can file and share them. Streaming .xlsx, PDF with embedded Vietnamese fonts, CSV UTF-8; synchronous with a row limit and a clear message above it; diacritics correct in all three | BR-SAAS-046, BR-SAAS-026, NFR-SAAS-013 | 5 | US-SAAS-046 | Async exports are BR-SAAS-053 (P2) |
| US-SAAS-049 | EP-SAAS-07 | As a user, I want to export any list view so that I can work with the data in Excel. Export button on every module grid with current filters and visible columns, Excel/CSV, scope and masking applied | BR-SAAS-046 | 3 | US-SAAS-047, 048 | Former BR-CORE-113 |
| US-SAAS-050 | EP-SAAS-07 | As the regulatory content owner, I want versioned statutory templates so that a new government form version is published without an application release. Template: form code, version, effective from/to, output format (XML/Excel/PDF), field mapping, XSD; published through the pack approval process; the version is chosen by the reporting period | BR-SAAS-052 | 8 | US-SAAS-032; TECH: document generation | Forms themselves (D02-LT, 05/KK, 05/QTT, labour reports) are counted in Payroll/People |
| US-SAAS-051 | EP-SAAS-07 | As a C&B user, I want statutory files validated before download so that the portal accepts them first time. XML validated against the template's XSD; Excel/PDF filled from mapping; error report by field; file stored as a document with checksum; regeneration keeps history | BR-SAAS-052, BR-SAAS-062 | 5 | US-SAAS-050; CORE: documents | |
| US-SAAS-052 | EP-SAAS-07 | As a C&B or finance user, I want an outbound-file centre so that I can download bank payment files, GL journals and PIT XML files and track what I uploaded. Lists files generated by Payroll (bank payment files, GL journal Excel/CSV, 05/KK and 05/QTT XML, unsigned e-PIT certificate PDF); download audited, step-up for bank files; mark "uploaded to bank/eTax/accounting" with date | BR-SAAS-059, BR-SAAS-060, BR-SAAS-062 | 5 | US-SAAS-024, 051; Payroll: file generation (BR-PAY-056/059/110/113) | File layouts and content are Payroll's; live APIs P2 (R2-01) |

### EP-SAAS-08 ESS web shell, localisation & e-mail channel

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-053 | EP-SAAS-08 | As an employee, I want a responsive ESS web portal so that I can use it from my phone's browser. Layout from 360 px; menu and route registry where modules plug their pages (profile, payslip/PIT certificate, leave, attendance and corrections, approvals, documents, notifications, consent); entries gated by entitlements and permissions; no check-in | BR-SAAS-075, NFR-SAAS-012 | 5 | US-SAAS-009, 055 | Module ESS pages are counted in module backlogs |
| US-SAAS-054 | EP-SAAS-08 | As an employee, I want an account page so that I manage my own security settings. Change password, MFA methods, language, active sessions with "log out all devices" | BR-SAAS-075, BR-SAAS-039, BR-SAAS-043 | 3 | US-SAAS-023, 025, 053 | Payslip PDF password page is Payroll (R3-04) |
| US-SAAS-055 | EP-SAAS-08 | As a user, I want the whole product in Vietnamese or English so that I never see mixed languages. All strings externalised (ICU messages) with a build check for missing keys; locale formats (VND with "." thousand separator, dates); Vietnamese-aware sorting and accent-insensitive search helpers for all modules | BR-SAAS-026, NFR-SAAS-013 | 5 | TECH: walking skeleton | |
| US-SAAS-056 | EP-SAAS-08 | As a BA, I want to export and import translation bundles so that vi/en texts are reviewed outside the code. Bundle export/import per module; system e-mail templates vi/en | BR-SAAS-026, NFR-SAAS-013 | 2 | US-SAAS-055 | |
| US-SAAS-057 | EP-SAAS-08 | As a user on an old browser, I want to be told it is unsupported so that I know why a page misbehaves. Supported list: latest 2 versions of Chrome, Edge, Safari, Firefox; notice for others; responsive breakpoints in the component library | NFR-SAAS-012 | 2 | US-SAAS-053 | Cross-browser test runs are TECH/QA |
| US-SAAS-058 | EP-SAAS-08 | As a Tenant Admin, I want system e-mails delivered reliably under our name so that employees trust and receive them. Core notification requests delivered by e-mail with tenant sender name; delivery, bounce and failure status returned to Core; per-tenant rate limit; attachment allowed only for the payslip PDF exception | BR-SAAS-066 | 5 | TECH: SMTP relay (DKIM/SPF/DMARC), job queue; CORE: notification centre | SMS, Zalo and push are P2 (R2-01) |

### EP-SAAS-09 Opening-balance go-live programme

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-059 | EP-SAAS-09 | As a tenant HR user, I want one catalogue of import templates so that I know what to fill in and in which order. Catalogue of templates registered by Core/People/Payroll/T&A on the Core import engine; download vi/en with code lists and instructions; P9 order; status per template and legal entity | BR-SAAS-069 | 3 | CORE: import engine; module import types | Template content is estimated in the owning modules (guide §4) |
| US-SAAS-060 | EP-SAAS-09 | As an onboarding consultant, I want a cut-over plan per legal entity so that opening balances are dated consistently. Go-live month per legal entity; as-at date = end of the previous month; tax-year start shown; status open/locked; go-live checklist | BR-SAAS-088, BR-SAAS-071 | 5 | US-SAAS-001; CORE: legal entities | |
| US-SAAS-061 | EP-SAAS-09 | As an onboarding consultant, I want an onboarding board that enforces the load order so that no file is committed before what it depends on. Validate-only until clean, then commit after approval, in P9 dependency order; batches linked to Core import jobs; rollback allowed while the legal entity is open; external reference IDs kept | BR-SAAS-070, BR-SAAS-071 | 5 | US-SAAS-059, 060; CORE: import engine (BR-CORE-110/114) | Engine (validate/commit/rollback, error report) is Core |
| US-SAAS-062 | EP-SAAS-09 | As an onboarding consultant, I want unknown columns mapped during validation so that tenant-specific attributes are not lost without custom fields. Unknown columns detected; mapped to a standard field or to a document attachment; mapping recorded in the validation report; unmapped columns block the commit | BR-SAAS-071 | 5 | US-SAAS-061 | R3-09 |
| US-SAAS-063 | EP-SAAS-09 | As a C&B user, I want go-live blocked if any pre-go-live month is missing so that PIT finalisation has the whole tax year. Per employee and legal entity, each month from January to the month before go-live must have taxable income, exempt income, insurance deducted, family deductions and PIT withheld; missing months listed; go-live blocked for that legal entity; January go-live needs none | BR-SAAS-089 | 5 | US-SAAS-060; Payroll: YTD import type | |
| US-SAAS-064 | EP-SAAS-09 | As a tenant finance owner, I want to enter our control totals so that imported balances are checked against our own records. Excel template and screen for totals from the last legacy payroll register, VSS C12-TS, 05/KK already filed and the leave report; per legal entity and optionally per employee | BR-SAAS-072 | 5 | US-SAAS-060; CORE: import engine | Excel import template = 5 |
| US-SAAS-065 | EP-SAAS-09 | As an onboarding consultant, I want a reconciliation report of imported values against control totals so that every difference is found before go-live. Per employee and total: YTD gross and net, PIT taxable income, PIT withheld, dependants, SI/HI/UI contribution salary and months, leave entitled/taken/balance incl. carry-forward, loans/advances; each difference needs an explanation; gate = zero unexplained differences | BR-SAAS-072 | 8 | US-SAAS-061, 064; People/Payroll/T&A: imported ledgers | `[high-uncertainty]`: matching rules per metric (rounding, month boundaries, carry-forward) are not yet specified |
| US-SAAS-066 | EP-SAAS-09 | As the tenant's C&B lead, I want to sign off the reconciliation so that go-live rests on our approval. Sign-off by HR/C&B and finance owners through Core workflow after consultant validation; sign-off locked to a reconciliation run; go-live readiness per legal entity | BR-SAAS-072, BR-SAAS-088 | 3 | US-SAAS-065; CORE: workflow | R2-13 |
| US-SAAS-067 | EP-SAAS-09 | As a C&B user, I want opening balances locked when the first regular payroll is approved so that the PIT/SI history cannot change silently. Lock on approval of the legal entity's first regular run; later opening-balance imports rejected; lock shown on the cut-over plan | BR-SAAS-088 | 3 | US-SAAS-060; Payroll: run approved event | |
| US-SAAS-068 | EP-SAAS-09 | As a C&B user, I want audited adjustment batches after the lock so that real errors can be corrected traceably. Adjustment batch with reason and approval; updates YTD, SI-history, leave and loan ledgers through module handlers; event picked up by Payroll and T&A as retro/adjustment input | BR-SAAS-088 | 5 | US-SAAS-067; CORE: import engine, workflow; module adjustment handlers | Approval workflow = 5 |
| US-SAAS-069 | EP-SAAS-09 | As a C&B user, I want to import our last legacy payroll register so that YourHRM can be compared with it. Excel per employee and pay element; mapping of legacy elements to YourHRM components; validation report | BR-SAAS-073 | 5 | US-SAAS-061; CORE: import engine | R2-03 |
| US-SAAS-070 | EP-SAAS-09 | As a C&B lead, I want a one-month payroll comparison so that we trust the new system before the first live run. Comparison run for that month from the imported inputs (calculated, never approved, then discarded); variance report by employee and element with tolerance; notes per variance; Excel export | BR-SAAS-073 | 8 | US-SAAS-069; Payroll: run lifecycle (R2-C10) | `[high-uncertainty]`: needs the full payroll engine and a month of inputs; element mapping depends on the pilot's register layout |

### EP-SAAS-10 Platform operations features

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-SAAS-071 | EP-SAAS-10 | As a Tenant Admin or operator, I want a job monitor so that I can see the progress of imports, payroll runs and publishing. List of jobs (type, status, progress, attempts, error); cancel or retry where the job type allows; jobs paused by tenant state shown as such | BR-SAAS-090 | 3 | TECH: job queue runtime | Scheduler/queue runtime is TECH |
| US-SAAS-072 | EP-SAAS-10 | As a platform operator, I want calculation changes behind per-tenant toggles and release rings so that a pilot tenant opts in before everyone else. Tenants assigned to rings (pilot, early, general); calculation-behaviour toggles per tenant with opt-in or announced date, set by configuration; audited; tenant sees toggles affecting it | NFR-SAAS-015 | 3 | US-SAAS-013 | Feature-flag service (BR-SAAS-009) is P2; P1 = configuration toggles (R2-19) |
| US-SAAS-073 | EP-SAAS-10 | As a Tenant Admin, I want release notes and maintenance notices in advance so that we plan around them. Release notes vi/en published ≥ 3 working days before release; in-app maintenance banner; warning when a window falls outside 22:00–05:00 ICT or in the payroll window (days 25–5) | NFR-SAAS-015, NFR-SAAS-001 | 3 | US-SAAS-013 | |
| US-SAAS-074 | EP-SAAS-10 | As a Tenant Admin, I want to raise a support request in-app so that the vendor gets the context and the SLA clock starts. Form with severity, module, description, language vi/en; tenant context attached; ticket id reusable in support grants | NFR-SAAS-020, BR-SAAS-033 | 2 | US-SAAS-016 | SLA itself is a service process |
| US-SAAS-075 | EP-SAAS-10 | As an SRE, I want per-tenant quota settings in the back-office so that a noisy tenant can be limited without a deploy. Per-tenant limits on concurrent jobs, report runs and exports, defaulted to the 20% pool cap; changes audited | NFR-SAAS-005 | 2 | US-SAAS-013; TECH: job queue fair-share | Enforcement is TECH |

## 3. Coverage check (P1 BR-SAAS and NFR-SAAS)

42 P1 BRs + 17 P1 NFRs from mvp-scope §9.6. "TECH" means the requirement is delivered by `technical-enablers-backlog.md`; the IDs are linked at PM consolidation.

### 3.1 Business requirements

| P1 ID | Short title | Covered by |
|---|---|---|
| BR-SAAS-001 | Tenant record & lifecycle | US-SAAS-001, 002 |
| BR-SAAS-002 | Legal entities / billing party | US-SAAS-003 |
| BR-SAAS-003 | Operator provisioning ≤ 10 min | US-SAAS-004, 005 |
| BR-SAAS-007 | Module subscription | US-SAAS-007, 009 |
| BR-SAAS-008 | Entitlement service | US-SAAS-008, 009 |
| BR-SAAS-012 | Billable employee count | US-SAAS-011, 012 |
| BR-SAAS-021 | tenant_id + RLS + app filter | **TECH** (tenancy/RLS). Platform plumbing, outside this backlog |
| BR-SAAS-023 | Release-blocking isolation tests | **TECH** (isolation test suite in CI) |
| BR-SAAS-024 | VN data residency | **TECH** (VN hosting, no PII to foreign services); US-SAAS-038 records processor locations |
| BR-SAAS-025 | Tenant settings | US-SAAS-006 |
| BR-SAAS-026 | Full vi/en | US-SAAS-055, 056, 048 |
| BR-SAAS-032 | Operator back-office (incl. legal packs, master data release) | US-SAAS-013, 014, 015, 030, 031, 034 |
| BR-SAAS-033 | Support grants | US-SAAS-016, 017, 074 |
| BR-SAAS-034 | Masking in support sessions | US-SAAS-018 |
| BR-SAAS-037 | Tenant-visible operator access log | US-SAAS-019 |
| BR-SAAS-038 | Password policy | US-SAAS-021, 022 |
| BR-SAAS-039 | MFA | US-SAAS-005, 023, 024, 054 |
| BR-SAAS-043 | Session management | US-SAAS-025, 026, 054 |
| BR-SAAS-045 | ESS login & OTP activation (incl. OD-8 former employees) | US-SAAS-021, 026, 027, 028 |
| BR-SAAS-046 | Reporting framework & list exports | US-SAAS-046, 047, 048, 049 |
| BR-SAAS-052 | Statutory report framework | US-SAAS-050, 051 |
| BR-SAAS-059 | Accounting journal file export | US-SAAS-052 (content: Payroll BR-PAY-113) |
| BR-SAAS-060 | Bank payment files | US-SAAS-052 (content: Payroll BR-PAY-110) |
| BR-SAAS-062 | eTax XML & e-PIT certificates (unsigned PDF) | US-SAAS-051, 052 (content: Payroll BR-PAY-056/057/059) |
| BR-SAAS-066 | Messaging: e-mail | US-SAAS-058 (SMTP relay hosting: TECH) |
| BR-SAAS-069 | Import templates catalogue | US-SAAS-059 (templates: owning modules) |
| BR-SAAS-070 | Validate-only, idempotent, reversible imports | US-SAAS-061 (engine: Core BR-CORE-110/111) |
| BR-SAAS-071 | Opening-balance import | US-SAAS-060, 061, 062 |
| BR-SAAS-072 | Opening-balance reconciliation | US-SAAS-064, 065, 066 |
| BR-SAAS-073 | One-month payroll comparison | US-SAAS-069, 070 |
| BR-SAAS-075 | Responsive ESS web | US-SAAS-053, 054, 041 |
| BR-SAAS-079 | Personal data inventory | US-SAAS-038, 039 |
| BR-SAAS-080 | Consent management | US-SAAS-040, 041 |
| BR-SAAS-081 | DSR tooling | US-SAAS-042, 043 |
| BR-SAAS-083 | DPIA dossier | US-SAAS-044 (dossier writing is Security/DPO work) |
| BR-SAAS-084 | Breach notification | US-SAAS-045 |
| BR-SAAS-085 | Security audit (written to Core store) | US-SAAS-017, 020, 029 (store: Core BR-CORE-090–094) |
| BR-SAAS-087 | Security level classification (Decree 85) | **No story: non-build.** Rated P1 as "statutory, non-build"; it is a vendor compliance dossier for the Security lead/PM, with inputs from US-SAAS-038 and the TECH security work |
| BR-SAAS-088 | Go-live cut-over & lock | US-SAAS-060, 066, 067, 068 |
| BR-SAAS-089 | Pre-go-live PIT months | US-SAAS-063 |
| BR-SAAS-090 | Job scheduler / queue | **TECH** (runtime); US-SAAS-071 (monitor UI), US-SAAS-002 (tenant-state pausing) |
| BR-SAAS-091 | Baseline capabilities always on | US-SAAS-010 |

Result: **42 / 42 P1 BRs listed**: 38 covered by stories here (BR-SAAS-024 and 090 also partly by TECH), 3 delivered by TECH only (021, 023, plus the hosting part of 024), and 1 non-build (087). No P1 BR is left out.

### 3.2 Non-functional requirements

| P1 ID | Short title | Covered by |
|---|---|---|
| NFR-SAAS-001 | Availability 99.5% | **TECH**; US-SAAS-073 (maintenance notices and window guard) |
| NFR-SAAS-002 | Payroll performance | **TECH** (performance tests, spike SP-1) with the Payroll engine |
| NFR-SAAS-003 | Interactive performance | **TECH** |
| NFR-SAAS-005 | Scalability & noisy-tenant cap | **TECH** (fair-share queue, quotas); US-SAAS-075 (quota settings) |
| NFR-SAAS-006 | Backup & DR in VN | **TECH** |
| NFR-SAAS-007 | Encryption | **TECH** |
| NFR-SAAS-008 | Secrets in vault | **TECH** |
| NFR-SAAS-009 | ASVS L2, scanning, pen test | **TECH** |
| NFR-SAAS-010 | Observability & log retention | **TECH** |
| NFR-SAAS-012 | Browser support, 360 px | US-SAAS-053, 057 (cross-browser test runs: TECH/QA) |
| NFR-SAAS-013 | Full localisation | US-SAAS-055, 056, 048 |
| NFR-SAAS-014 | Zero-downtime releases | **TECH** |
| NFR-SAAS-015 | Release governance & toggles | US-SAAS-072, 073 (release pipeline: TECH) |
| NFR-SAAS-016 | Regulatory change SLA | US-SAAS-030, 031, 032, 033, 035, 036 (LPS runtime: TECH) |
| NFR-SAAS-017 | Calculation traceability | US-SAAS-033, 037 (version stamping: Payroll/T&A and TECH) |
| NFR-SAAS-020 | Support SLA | US-SAAS-074 (the SLA itself is a service process) |
| NFR-SAAS-021 | Peak capacity | **TECH** (load test before each peak) |

Result: **17 / 17 P1 NFRs listed.** Feature-like NFRs covered here: 012, 013, 015, 016, 017, 020 (and parts of 001 and 005). **Expected in the TECH backlog:** NFR-SAAS-001, 002, 003, 005, 006, 007, 008, 009, 010, 014, 021 (and the pipeline or test parts of 012, 015, 017).

### 3.3 Other modules' requirements supported here
BR-PAY-010 and NFR-PAY-007 (legal parameters, baseline) and BR-CORE-006 (region map) get their administration and governance in EP-SAAS-05; their runtime is TECH. Payroll and Core count only key registration. BR-CORE-041 (support access) is enforced from the platform side in US-SAAS-017. BR-CORE-080/087 (global master data) get their release UI in US-SAAS-014/015.

## 4. Totals and risk notes

| Epic | Stories | SP |
|---|---|---|
| EP-SAAS-01 Tenant lifecycle & provisioning | 6 | 27 |
| EP-SAAS-02 Subscriptions, entitlements & billable count | 6 | 32 |
| EP-SAAS-03 Operator back-office & support access | 8 | 34 |
| EP-SAAS-04 Identity & access | 9 | 39 |
| EP-SAAS-05 Legal Parameter Service: business features | 8 | 39 |
| EP-SAAS-06 Privacy & compliance | 8 | 37 |
| EP-SAAS-07 Reporting, export & statutory templates | 7 | 39 |
| EP-SAAS-08 ESS shell, localisation & e-mail | 6 | 22 |
| EP-SAAS-09 Opening-balance go-live programme | 12 | 60 |
| EP-SAAS-10 Platform operations features | 5 | 13 |
| **Total** | **75** | **342** |

Planning view (guide §6): 342 SP ≈ 342 developer-days, ≈ 479 days with the +40% overhead; ≈ 49 developer-sprints at 7 SP per sprint, or about 8 developers for the 6 build sprints on this backlog alone.

### Risk notes (`[high-uncertainty]`)
| Story | SP | Why it is uncertain |
|---|---|---|
| US-SAAS-070 Payroll comparison variance report | 8 | Needs the full payroll engine and a complete month of imported inputs; element mapping depends on the pilot's register layout; may need splitting once the layout is known |
| US-SAAS-043 DSR cross-module export/anonymisation | 8 | Depends on every module delivering DSR handlers; retention floors and backup statement (D-04, D-05) not yet validated by the DPO |
| US-SAAS-065 Opening-balance reconciliation report | 8 | Matching and tolerance rules per metric (rounding, month boundaries, leave carry-forward) not yet specified; critical path for go-live |
| US-SAAS-036 Initial legal pack content | 5 | `[verify-legal]`: 2026 PIT and SI values not yet validated (decision-log §3, §5) |
| US-SAAS-004 Provisioning ≤ 10 min | 8 | Orchestrates Keycloak, tenancy, Core and seeding with rollback; timing target unmeasured |
| US-SAAS-035 Multi-contract insurance rule | 3 | `[verify-legal]`: PM doubts the HI rule chosen by the sponsor (decision-log §8.1) |
| US-SAAS-011 Billable count | 8 | Treatment of collaborators (CTV) not on payroll still open (BRD Q4) |

Other notes:
- EP-SAAS-09 (60 SP) is on the go-live critical path and depends on every module's import types; plan it from sprint 3, after the Core import engine.
- DSR deadline (US-SAAS-042) and breach-notice content (US-SAAS-045) are `[verify-legal]` but held as parameters or template text, so they got no extra step.
- Descope reserve if needed: US-SAAS-073 (notices by e-mail only), US-SAAS-056, US-SAAS-057, US-SAAS-075 and the adoption report in US-SAAS-033 (about 8 SP).
