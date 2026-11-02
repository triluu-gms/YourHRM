# SaaS Platform: interfaces and events

Sources:
- BRD `06-saas-platform.md` §7.1 (PR-*, what SaaS provides) and §7.2 (CO-*, what SaaS consumes).
- `gap-analysis.md` §6, event catalogue v1.1.
- ADR-0004 rev. 2, ADR-0010, ADR-0011.

The BRD names this module's interfaces `PR-nn` / `CO-nn`; it does not use `IF-SAAS-*`. Other modules cite them under their own IDs, for example Payroll `IF-PAY-C-32` → PR-17, T&A `ATT-C-11` → PR-17, and Core `C-01..C-04` → the `saas.*` events below.

## Provided (SaaS → modules), BRD §7.1

| # | What | To | Phase notes |
|---|---|---|---|
| PR-01 | Tenant context (tenant_id, LE, locale, TZ) on every request, job and event, plus DB session variables for RLS | all | P1 |
| PR-02 | Entitlement and feature-flag checks | all | Entitlement is P1. Flags (BR-SAAS-009) are P2; in P1 use config/deploy toggles |
| PR-03 | Authenticated identity (user id, auth method, MFA level, claims), session, step-up for sensitive actions | core (identity → roles), all | P1 (Keycloak, ADR-0005) |
| PR-04 | Tenant settings and localisation (vi/en bundles, formats, calendar, currency) | all | P1 |
| PR-05 | **Removed.** Custom fields are now Core P-19 / BR-CORE-086 | — | — |
| PR-06 | **Legal Parameter Service** (single owner). `getParam(key, scope, asOfDate)` returns the value plus the pack version. Emits `saas.LegalParameterPackPublished`. Overrides only where the definition allows them. Consumers have read-only access. | payroll, T&A, people, recruitment, core (region resolution) | P1 |
| PR-07 | Statutory template framework (versioned government-form layouts) | payroll, people, T&A | P1 (BR-SAAS-052) |
| PR-08 | Reporting framework, reporting store and exports (incl. list export). Receives headcount from People (OUT-34) and payroll results from Payroll (IF-PAY-P-03/16). | all | Framework and exports are P1. The separate store (BR-SAAS-047) is P2 per mvp-scope (see conflicts) |
| PR-09 | Integration hub and connectors. **P1 = e-mail and file export only**; live connectors are P2 (R2-01) | payroll, core, people, T&A, rec | P1 file export; hub P2 |
| PR-10 | Channel delivery adapter (e-mail SMTP/DKIM in P1; push/SMS/Zalo in P2). Core's notification centre composes; SaaS delivers. | core only (modules call Core) | P1 e-mail |
| PR-11 | Public API gateway and webhook delivery | all | P2 |
| PR-12 | Opening-balance onboarding on Core's import engine (BR-CORE-110–114): template catalogue, cut-over plan and lock (088), adjustment batches, reconciliation runs (072) | people, T&A, payroll, rec (P2), core | P1 |
| PR-13 | Privacy services (single owner): consent registry and check API, PD inventory, DSR orchestration, retention scheduler with statutory floors | all | Consent, inventory and DSR are P1. The retention job (082) is P2 |
| PR-14 | **Removed.** The audit store is Core P-20; SaaS writes to it (Core P-27) | — | — |
| PR-15 | Mobile/ESS shell (container, auth, push, offline sync, check-in) | people, T&A, payroll, core | P2. **P1 = responsive web ESS shell only** |
| PR-16 | Object storage infrastructure (VN residency, tenant prefix, KMS, virus scan, short signed URLs) behind Core's document service. Modules call Core P-21 only. | core | P1 |
| PR-17 | Job scheduler / queue (BR-SAAS-090) | core, people, T&A, payroll, rec | P1 |
| PR-18 | Always-on baseline entitlements (BR-SAAS-091) and the module dependency matrix | all | P1 |

## Consumed (modules → SaaS), BRD §7.2

| # | What | From | Mechanism |
|---|---|---|---|
| CO-01 | Users, roles, permissions, data scopes (for report security, API scopes, masking under support grants) | core | runtime |
| CO-02 | Legal entities (`core.LegalEntityChanged`; create-LE API used at provisioning), org tree, cost centres | core | event + API |
| CO-03 | Notification send requests (SaaS delivers and returns status); Core audit write API (identity/operator events) | core | event / API |
| CO-04 | Workflow decisions for platform processes (support grant approval, DSR approval, import commit approval) | core | event |
| CO-05 | **Persons actively employed per LE at month end** (billable count, BR-SAAS-012); daily snapshot for reference | people | `people.HeadcountSnapshotTaken` |
| CO-06 | `core.UserStatusChanged` (ACTIVE / FORMER_EMPLOYEE / DEACTIVATED) → credentials, session revocation, SCIM; People lifecycle events only for webhooks | core; people | event |
| CO-07 | PD field registration plus per-subject export/anonymise handlers | all modules | design time + DSR runtime |
| CO-08 | Contracts/decisions to e-sign; signing callback | people | P2 |
| CO-09 | Device ↔ employee mapping, punch acceptance results | T&A | P2 (live device) |
| CO-10 | Approved payroll outputs: GL journal, bank payment orders, PIT data, SI declaration data | payroll | on approval / payment release |
| CO-11 | Payroll run volume (employees processed) for performance monitoring and payroll metering | payroll | per run |
| CO-12 | Job posts, candidate profiles and consents | recruitment | P2 |
| CO-13 | Module-defined standard reports, statutory template mappings, webhook event schemas, import templates | all | design time, registered per release |
| CO-14 | Module retention rules (statutory floor per data category) | all | design time |
| CO-15 | Global master-data admin API (load a versioned release; Core emits `core.MasterDataReleased`) | core | on operator publish |
| CO-16 | Tenant user accounts and API-scope → permission mapping | core | runtime |

## Events (gap-analysis §6, catalogue v1.1)

**Published by SaaS.** Written to `saas.outbox_event`, created by `sys.create_outbox_inbox('saas', …)` in `001_saas.sql`.

| Event | Consumers | Payload (key fields) | Trigger |
|---|---|---|---|
| `saas.TenantProvisioned` | Core | tenant, plan, owner contact, locale, region, initial LE | tenant created |
| `saas.TenantStateChanged` | Core, all (via entitlement) | old/new state ∈ BR-SAAS-001 states, reason | lifecycle transition |
| `saas.EntitlementsChanged` | Core, all | modules, features, limits, effective dates | plan/add-on change |
| `saas.LegalParameterPackPublished` | Payroll, T&A, People, Core, Rec | pack version, effective date, changed keys (and dates, per ADR-0025 §5) | P10 publish |
| `saas.ConsentChanged` | People, Rec, T&A | subject, purpose, status, notice version | consent given or withdrawn |
| `saas.DataSubjectRequestOpened` | all | subject id, request type, due date | DSR approved by the DPO |

Platform-cluster events (`saas.LegalParameterPackPublished`) are fanned out to every pool and dedicated cluster with the sentinel tenant (ADR-0004 rev. 2 §3).

**Consumed by SaaS** (catalogue rows listing SaaS as consumer):
- `core.UserStatusChanged`: toggle the Keycloak Organization membership and revoke sessions. FORMER_EMPLOYEE switches login to e-mail OTP (OD-8).
- `core.LegalEntityChanged`: link the billing party and refresh report filters.
- `people.HeadcountSnapshotTaken`: billable count (D-10). The month-end snapshot is the billing quantity.
- `people.EmployeeHired`, `people.EmployeeTerminated`: SaaS is listed as a consumer (metering, webhooks P2).
- `pay.PayrollRunStatusChanged`: SaaS is listed as a consumer. Use APPROVED of the first regular run per LE for the BR-SAAS-088 lock (US-SAAS-067), and per-run volume (CO-11).

Envelope fields, outbox/inbox dedupe, `initial_load` and payload minimisation are covered by **yhrm-conventions**.

## Phase-1 event bus and job queue mechanics (ADR-0004 rev. 2, ADR-0011, `001_saas.sql`)

- The relay (`yhrm_relay`) calls `saas.enqueue_event_delivery()` in the same transaction that sets `outbox_event.published_at`. It inserts one `bus.Deliver` job per subscribed consumer into `saas.job` (queue `events`), with `idempotency_key = consumer:event_id` and `ordering_key = consumer:aggregate_id`.
- The job payload is a **pointer** (cluster, schema, event_id, created_at), never the business payload, because every module role of the tenant can read `saas.job` (X-02 plumbing grant).
- `saas.claim_jobs(queue, worker, limit, tenant_cap)` is SECURITY DEFINER and granted to `yhrm_app_saas` only. It applies fair share (least-served tenants first, per-tenant cap) and ordered lanes (at most one non-terminal job per `ordering_key`).
- Job states: READY, RUNNING, SUCCEEDED, FAILED, DEAD, CANCELLED, PAUSED. DEAD is the Phase-1 dead letter; replay must happen within the 7-day outbox window.
- Queues: `payroll`, `general`, `integration` (P2), `reporting`, `events`. Priority runs from 1 (interactive) to 9 (batch).
- Module roles (`yhrm_app`) may `SELECT, INSERT` on `saas.job` (enqueue in their own business transaction). Only `yhrm_app_saas` may update state and progress columns.
- Phase 2 swaps the transport for Kafka (`EventTransport` port: `JobQueueTransport` → `KafkaTransport`). Consumer code does not change.
