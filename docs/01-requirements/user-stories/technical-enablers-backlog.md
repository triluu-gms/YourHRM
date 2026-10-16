# Technical Enablers Backlog: Phase 1 (v0.1, 2026-10-16)

| Item | Value |
|---|---|
| File | `docs/01-requirements/user-stories/technical-enablers-backlog.md` |
| Owner | Solution architect |
| Method | [estimation-guide.md](estimation-guide.md) v0.1: Fibonacci SP, 1 SP ≈ 1 developer-day, no story > 13 |
| Inputs | [architecture-overview.md](../../02-architecture/architecture-overview.md) v0.3 (§5 tenancy, §6 identity, §7 eventing, §9.5 jobs, §10 reporting, §11 frontend, §13 security, §14 operations, §17.3 spikes) · [DESIGN.md](../../../database/postgresql/DESIGN.md) v0.3 · DDL `database/postgresql/ddl/000–003` · [mvp-scope.md](../brd/mvp-scope.md) §9 · [decision-log.md](../../00-project-management/decision-log.md) (R2-01, R2-04, R2-15, R2-17, R3-08, R3-11) |
| ID prefixes | Epics `EP-TECH-NN`, stories `US-TECH-NNN` |

## 0. Scope and estimating rules for this file

**In scope.** Non-user-facing work that no module backlog owns: repository, build and module boundaries; the SP-0 walking skeleton; tenant context, RLS plumbing and the cross-tenant isolation suites; database roles, the Flyway pipeline and DDL hardening (including testing the existing DDL 000–003); outbox/inbox and the DB-backed event bus; the generic job queue runtime (`saas.job`) and workers; Keycloak, the BFF, sessions and the edge gateway; authorisation enforcement plumbing used by Core; Valkey caching; PII envelope encryption, OpenBao, key rotation and crypto-shredding plumbing; object storage and virus scan; the reporting-store feed; the frontend app shell, design system and i18n framework; CI/CD; environments, IaC/GitOps and DR; observability; security hardening and pen-test support; the performance test harness; and spikes SP-0, SP-1 (harness only), SP-2, SP-4 (support), SP-5, SP-6, SP-7, SP-8, SP-9.

**Out of scope (owned elsewhere, only used here).** These engines are owned by other backlogs:
- Legal Parameter Service runtime: SaaS backlog.
- YFL formula engine and payroll engine: Payroll backlog. Estimation guide §2 lists "Legal Parameter Service runtime, formula engine" under this file, but the architect's brief assigns them to the owner modules. The PM must confirm that each is estimated exactly once.
- Workflow, notification centre, document service, import engine and audit store: Core backlog.
- Entitlements, support-grant lifecycle, e-mail channel adapter, file-export framework, consent/DSR and reporting framework: SaaS backlog.
- Module screens: module backlogs.

**Sizing convention for infrastructure work.** The guide's SP definition excludes DevOps because it sits in the +40% overhead. In this file, infrastructure and platform work (IaC, clusters, CI/CD, DR, observability) *is* the deliverable, so it is estimated in SP at the same reference scale: 1 SP = 1 day of a mid-level engineer. **PM: when you consolidate, take the DevOps share out of the +40% overhead for these epics, so the effort is not counted twice.**

**Waves (Notes column).** These show when each story is needed for the 16-week plan (12 build weeks = 6 two-week sprints, then 4 weeks of SIT/UAT/pilot):
- `S0-1`: critical path, done by the end of sprint 1.
- `S2-3`: sprints 2–3.
- `S4-6`: sprints 4–6.
- `SIT`: weeks 13–16.
- `GA-def`: can move after pilot go-live but must be done before general availability, with the reason stated.

**Uncertainty.** `[high-uncertainty]` marks stories whose estimate depends on a spike outcome or on the VN provider (OD-1).

**De-duplication with the SaaS backlog.** BR-SAAS-021, 023, 024, 090 and the technical parts of BR-SAAS-038, 039 and 043 are delivered here. `saas-platform-backlog.md` should reference the US-TECH IDs for these and estimate only the screens and configuration it owns.

---

## 1. Epics

| Epic | Name | Goal | Stories | SP |
|---|---|---|---|---|
| EP-TECH-01 | Repository, build and module boundaries | Gradle multi-project, Spring Modulith + ArchUnit guards, shared kernel, API and event-contract conventions, dev environment | 9 | 42 |
| EP-TECH-02 | Walking skeleton (SP-0) | Prove Java 25 + Boot 4 + Modulith + Keycloak + BFF + CNPG + RLS + DB-backed bus end to end, deployed by Argo CD | 4 | 18 |
| EP-TECH-03 | Tenancy plumbing and isolation suites | Tenant context everywhere, RLS + app guard, routing, noisy-neighbour limits, release-blocking isolation tests | 10 | 52 |
| EP-TECH-04 | Database roles, Flyway pipeline, DDL hardening | Tested DDL 000–003, per-schema Flyway, roles as code, expand/contract, partitions, pgaudit | 10 | 44 |
| EP-TECH-05 | Outbox/inbox and DB-backed event bus | Transactional outbox, relay, ordered delivery jobs, inbox dedupe, DEAD/replay, process-manager base | 8 | 35 |
| EP-TECH-06 | Job queue runtime and workers | `saas.job` tenant-fair queue, worker pools, cron, idempotent effective-date jobs, progress, autoscaling | 8 | 39 |
| EP-TECH-07 | Identity, BFF and edge gateway | Keycloak HA with Organizations, MFA, step-up, BFF session handling, Envoy Gateway + WAF | 10 | 45 |
| EP-TECH-08 | Authorisation plumbing and caching | Permission SPI, snapshot cache, SQL scope predicates, field masking, Valkey | 5 | 28 |
| EP-TECH-09 | PII encryption and secrets | OpenBao, dynamic DB credentials, envelope encryption, blind index, rotation, crypto-shredding, log masking | 9 | 48 |
| EP-TECH-10 | Object storage and file safety | Tenant-prefixed storage, upload scan, signed URLs, replication/WORM, render services | 5 | 21 |
| EP-TECH-11 | Reporting-store feed *(conditional, see §5 R-1)* | Native logical replication with column lists to a PostgreSQL reporting store, transformation framework | 6 | 28 |
| EP-TECH-12 | Frontend foundation | React/AntD workspace, generated client, BFF session in the SPA, shells and registry, design system, i18n | 11 | 54 |
| EP-TECH-13 | CI/CD and supply chain | VN-hosted pipeline, quality and security gates, signed images, release rings, zero-downtime deploys | 10 | 41 |
| EP-TECH-14 | Environments, IaC, GitOps, backup and DR | VN cloud foundation, K8s, CNPG clusters, backups/PITR, DC2 DR site and drill | 12 | 65 |
| EP-TECH-15 | Observability and operations | OTel + Grafana stack in VN, tenant-tagged telemetry, SLOs, security alerts, runbooks | 7 | 33 |
| EP-TECH-16 | Security hardening and pen-test support | ASVS L2 baseline, DAST, threat model, pen test and remediation, admin access hardening | 8 | 28 |
| EP-TECH-17 | Performance engineering | Perf harness, reference load, payroll/batch/noisy-neighbour tests, peak pre-scaling, tuning | 7 | 34 |
| EP-TECH-18 | Spikes SP-1, 2, 4, 5, 6, 7, 8, 9 | Answer the open architecture questions before they block delivery | 10 | 49 |
| **Total** | | | **149** | **704** |

---

## 2. Stories

### EP-TECH-01 Repository, build and module boundaries

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-001 | EP-TECH-01 | As a developer, I want a Gradle (Kotlin DSL) multi-project build with `src/app`, `src/modules/*` and `src/shared/*`, a Java 25 toolchain, the Spring Boot 4 BOM and a version catalogue so that every module builds the same way; `./gradlew build` green on a clean checkout; role profiles `api` and `worker` start the same jar; Spotless, Checkstyle and Renovate configured | — | 5 | — | S0-1 |
| US-TECH-002 | EP-TECH-01 | As an architect, I want Spring Modulith module declarations with `ApplicationModules.verify()` and generated module documentation in CI so that module boundary violations fail the build; a deliberate `internal` import from another module fails CI; module canvas published as a build artifact | — | 3 | US-TECH-001 | S0-1; AR-3 |
| US-TECH-003 | EP-TECH-01 | As an architect, I want an ArchUnit rule set so that the dependency rules of arch. §3.3 are machine-checked; rules: only the allowed module directions, `api`-only access, `shared/*` depends on nothing, no `double`/`float` in money/engine/expression packages, DB access only inside `@Transactional`, no `LocalDateTime.now()`/system clock outside `Clock`; each rule has a failing fixture test | NFR-PAY-002 (part), NFR-ATT-005 (part) | 5 | US-TECH-002 | S0-1 |
| US-TECH-004 | EP-TECH-01 | As a module developer, I want a `shared/common` kernel (TenantContext record, UUIDv7/ULID ids, `Money`/VND on `BigDecimal` with explicit rounding modes, UTC `Instant` plus local work-date types, RFC 9457 error model, injectable `Clock`) so that modules share one set of conventions; 100% unit-tested value types; VND formatting has no decimals | NFR-PAY-002 (part), NFR-ATT-005 (part), NFR-PAY-011 (part) | 5 | US-TECH-001 | S0-1 |
| US-TECH-005 | EP-TECH-01 | As a People/Payroll developer, I want the `temporal-kit` library (Pattern A valid-time and Pattern B bi-temporal repository helpers, `asOf`/`knownAt` parameter binding, guard against overlapping periods) so that effective dating is implemented once; property-based tests for split/close/correct; as-of and as-known queries proven on `hr` tables from DDL 003 | NFR-PPL-001 (part) | 8 | US-TECH-004, US-TECH-024 | S0-1 (needed by the first effective-dated People story) |
| US-TECH-006 | EP-TECH-01 | As a frontend or API consumer, I want common REST conventions (springdoc OpenAPI 3.1 per module, cursor pagination, `ETag`/`If-Match`, an `Idempotency-Key` filter, problem details, `asOf`/`knownAt`) so that every module API behaves the same; OpenAPI published per build; idempotent POST replays return the original response | NFR-SAAS-003 (part) | 5 | US-TECH-004 | S0-1 |
| US-TECH-007 | EP-TECH-01 | As an integration analyst, I want `shared/contracts` with a JSON Schema per catalogue event, generated Java records and TS types, a CI backward-compatibility check and consumer-driven contract test scaffolding so that event changes cannot break consumers; a breaking change to a v1 schema fails CI; one example Pact/SCC test per producer | NFR-PPL-002 (part) | 5 | US-TECH-001 | S0-1 |
| US-TECH-008 | EP-TECH-01 | As a developer, I want a one-command local environment (PostgreSQL 17, Keycloak, Valkey, S3-compatible store, ClamAV, Gotenberg, mail catcher) with two seeded demo tenants so that onboarding takes less than half a day; `docker compose up` plus the seed script give two working logins | — | 3 | US-TECH-001 | S0-1 |
| US-TECH-009 | EP-TECH-01 | As a developer, I want test foundations (Testcontainers base classes, `@ApplicationModuleTest` conventions, tenant/user fixture builders) so that integration tests are cheap to write; reusable containers keep the module test suite under 10 min | — | 3 | US-TECH-001 | S0-1 |

### EP-TECH-02 Walking skeleton (SP-0)

The skeleton is the first thin slice of US-TECH-015, 034, 035, 036, 042, 050, 054 and 108. Those stories are estimated net of the skeleton work, which they harden.

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-010 | EP-TECH-02 | As an architect, I want a "hello-tenant" request path SPA → BFF → `hrm-api` → an RLS-protected table on CloudNativePG, with `set_config` per transaction through PgBouncer (transaction mode), so that the tenancy model is proven on day 1; tenant A never sees tenant B's row; a request without tenant context returns 0 rows | BR-SAAS-021 (part), NFR-CORE-001 (part) | 5 | US-TECH-001, US-TECH-008 | S0-1; SP-0 |
| US-TECH-011 | EP-TECH-02 | As an architect, I want the minimal event path outbox → relay → `bus.Deliver` job → consumer with inbox so that the Phase-1 DB-backed bus (no Kafka) is proven; one event delivered exactly once to a consumer in another module's role; a duplicate is ignored by the inbox | NFR-PPL-002 (part) | 5 | US-TECH-010 | S0-1; SP-0 |
| US-TECH-012 | EP-TECH-02 | As an architect, I want login with password + TOTP through Keycloak Organizations and the BFF, with local JWT validation in `hrm-api`, so that the identity split works end to end; HttpOnly session cookie; `tid` claim drives the tenant context | NFR-CORE-006 (part) | 3 | US-TECH-010 | S0-1; SP-0 |
| US-TECH-013 | EP-TECH-02 | As the sponsor, I want the skeleton built in CI with the Modulith/ArchUnit/X-02 grant guards and deployed by Argo CD to a provisional VN cluster, plus a library-fit report (Hibernate 7, jOOQ, Modulith 2, springdoc, db-scheduler, Keycloak libs on Java 25/Boot 4), so that OD-3 has evidence; report lists blockers and the fallback trigger | — | 5 | US-TECH-011, US-TECH-012, US-TECH-002 | S0-1; SP-0 exit criterion; input to OD-3 (closed after SP-1) |

### EP-TECH-03 Tenancy plumbing and isolation suites

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-014 | EP-TECH-03 | As a platform engineer, I want `TenantContext` bound with `ScopedValue` per request and propagated (Micrometer context propagation) to virtual threads and executors so that no code runs without a tenant; work started without context fails fast; the host slug must match the `tid` claim, otherwise 403 + security event | BR-SAAS-021, NFR-CORE-001, NFR-ATT-010, NFR-PAY-005 | 5 | US-TECH-004, US-TECH-010 | S0-1 |
| US-TECH-015 | EP-TECH-03 | As a platform engineer, I want a `TenantAwareDataSource` that sets `app.tenant_id`, `user_id`, `request_id`, `purpose`, `support_grant_id` and `mask_level` at transaction start and calls `sys.assert_tenant()` so that RLS always has its context; non-transactional access to tenant schemas is rejected; context never set at session level (checked under PgBouncer) | BR-SAAS-021, NFR-CORE-001 | 3 | US-TECH-010 | S0-1 |
| US-TECH-016 | EP-TECH-03 | As a security officer, I want an application guard (Hibernate filter + jOOQ `VisitListener` adding `tenant_id = ?`) as a second line of defence so that a missing RLS policy still leaks nothing; a test with RLS disabled still returns only the tenant's rows | BR-SAAS-021, NFR-CORE-001, NFR-PAY-005 | 5 | US-TECH-015 | S2-3 |
| US-TECH-017 | EP-TECH-03 | As a platform engineer, I want tenant routing (`AbstractRoutingDataSource` by `cluster_id` from `saas.tenant_placement`, cached in Caffeine + Valkey, one Hikari pool per module role per cluster, `@ReadOnlyRoute` to replicas) so that the platform cluster and pool clusters are addressed transparently; a tenant moved in the directory is routed after cache invalidation; replica routing for reports and exports | NFR-SAAS-005 | 8 | US-TECH-015, US-TECH-064, US-TECH-109 | S2-3 |
| US-TECH-018 | EP-TECH-03 | As a platform engineer, I want REPLICATED platform tables (`saas.tenant`, `tenant_placement`, `entitlement`, published legal packs, global master data) copied read-only from the platform cluster to every pool cluster (`pub_platform_ref`), with lag monitoring, so that modules read them locally; conflict handling for global `code_value` rows decided with the Data architect | NFR-SAAS-005 | 5 | US-TECH-109, US-TECH-147 | GA-def if the pilot runs platform + pool schemas in one cluster (decide with DA); DESIGN §3.1 spike; [high-uncertainty] |
| US-TECH-019 | EP-TECH-03 | As a security officer, I want a DB-level isolation suite (two tenants; every app role × every tenant table returns 0 cross-tenant rows and rejects cross-tenant writes; a catalogue check fails when a table lacks `tenant_id` + RLS unless allow-listed) run on every migration so that RLS gaps cannot ship | BR-SAAS-023, NFR-CORE-001, NFR-PAY-005, NFR-ATT-010, NFR-PPL-008 (part) | 5 | US-TECH-024, US-TECH-026 | S2-3; release-blocking |
| US-TECH-020 | EP-TECH-03 | As a security officer, I want an API-level isolation suite that discovers every endpoint from the OpenAPI specs and calls it with a tenant-A session and forged tenant-B ids so that QAS-1 is tested automatically; expected 403/404/empty; modules register fixtures through a simple SPI; release gate in the pipeline | BR-SAAS-023, NFR-CORE-001, NFR-PAY-005, NFR-ATT-010, NFR-PPL-008 (part) | 8 | US-TECH-006, US-TECH-019, US-TECH-054 | S4-6; release-blocking |
| US-TECH-021 | EP-TECH-03 | As a security officer, I want isolation tests for the asynchronous channels (job rows, event envelopes and the context re-established before handlers, cache key prefixes, object-storage keys, file exports, reporting store) so that tenant data cannot cross outside HTTP; one negative test per channel | BR-SAAS-023, NFR-PAY-005, NFR-ATT-010 | 5 | US-TECH-036, US-TECH-042, US-TECH-064, US-TECH-074 | S4-6 |
| US-TECH-022 | EP-TECH-03 | As the platform operator, I want noisy-neighbour controls (Bucket4j token buckets in Valkey per tenant and per user, plan-based defaults; `statement_timeout` per role; concurrent report/export limit per tenant) so that one tenant cannot take more than 20% of the pool; limits return 429 problem details; brute-force limits on login endpoints | NFR-SAAS-005, NFR-CORE-014 | 5 | US-TECH-064, US-TECH-054 | S2-3 |
| US-TECH-023 | EP-TECH-03 | As the platform operator, I want support-grant context plumbing (operator token without `tid` + grant id → TenantContext; `mask_level` defaults to full masking; every action stamped with the grant id) so that the SaaS support-grant feature can rely on it; actions without a valid grant are refused | BR-SAAS-033 (part), BR-SAAS-034 (part), BR-SAAS-037 (part) | 3 | US-TECH-014, US-TECH-063 | S4-6; grant lifecycle and UI in SaaS backlog |

### EP-TECH-04 Database roles, Flyway pipeline, DDL hardening

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-024 | EP-TECH-04 | As the Data architect, I want the existing DDL `000_extensions_roles`, `001_saas`, `002_core`, `003_hr` executed from zero on PostgreSQL 17 in Testcontainers, linted (`squawk`) and covered by tests of the shipped functions (`sys.current_tenant_id`, `enable_tenant_rls`, `create_outbox_inbox`, `cross_schema_grant_violations`, masking views failing closed, `claim_jobs`, `enqueue_event_delivery`) so that defects are found before modules build on them; defect list handed to the DA; all scripts run cleanly | NFR-CORE-001 (part), NFR-PAY-004 (part) | 8 | US-TECH-009 | S0-1; DDL is 2,908 lines; `att`/`pay` DDL not yet written (DA) |
| US-TECH-025 | EP-TECH-04 | As a developer, I want the DDL baseline turned into per-schema Flyway locations (`V1__baseline.sql`, `R__` for views/functions/policies, one history table per schema, order `sys → saas → core/audit → hr → att → pay`) so that modules migrate independently; the build runs all migrations from zero and the upgrade from the previous release snapshot | NFR-SAAS-014 (part) | 5 | US-TECH-024 | S0-1 |
| US-TECH-026 | EP-TECH-04 | As a platform engineer, I want the DB roles (`yhrm_owner`, `migrator`, `app_*`, `relay`, `cdc`, `audit_sealer`, `reporting_ro`, `ops_ro`, `breakglass`) and grants defined as code (CNPG managed roles + bootstrap) so that every environment has identical privileges; X-02 guard returns 0 rows; no app role has BYPASSRLS | BR-SAAS-021 (part), NFR-PAY-004 (part) | 5 | US-TECH-024 | S0-1 |
| US-TECH-027 | EP-TECH-04 | As an SRE, I want migrations applied per cluster by a migration Job before the rollout (Argo CD pre-sync), in waves, with an app startup check that refuses to run on a schema older than it needs, so that releases never meet a half-migrated database; failed migration stops the rollout | NFR-SAAS-014 | 5 | US-TECH-025, US-TECH-108 | S2-3 |
| US-TECH-028 | EP-TECH-04 | As a developer, I want expand/contract support (CI `squawk` rules: `lock_timeout`, `CREATE INDEX CONCURRENTLY`, `NOT VALID` + `VALIDATE`, no renames or type changes on large tables) and a tenant-fair backfill job template so that schema changes need no downtime; the lint blocks unsafe DDL; the backfill sets the tenant context per batch | NFR-SAAS-014 | 5 | US-TECH-025, US-TECH-042 | S2-3 |
| US-TECH-029 | EP-TECH-04 | As an SRE, I want partition management (`pg_partman` or `sys.create_range_partitions()` on the scheduler, 3 periods ahead, daily outbox partitions dropped after 7 days, inbox 90 days, alert when a future partition is missing, no DEFAULT partitions) so that large tables never fail inserts | NFR-ATT-007 (part) | 3 | US-TECH-045 | S2-3 |
| US-TECH-030 | EP-TECH-04 | As the DPO, I want a CI check that parses `COMMENT ON COLUMN … 'pii:Px'` and fails on unclassified columns, and exports the column registry for the SaaS PD inventory, so that no personal-data column goes unregistered | BR-SAAS-079 (part), NFR-PPL-005 (part) | 3 | US-TECH-025 | S2-3 |
| US-TECH-031 | EP-TECH-04 | As a security officer, I want the extension and logging baseline (`btree_gist`, `pgcrypto`, `pg_trgm`, `unaccent`, `ltree`, `pg_stat_statements`, `pgaudit`), a JDBC `/* tenant=… */` comment tag, pgaudit on human and break-glass sessions, connection logging and an alert on any module-role login from outside the app pods (R2-15), so that DB access is evidenced | NFR-PAY-004, NFR-SAAS-010 (part) | 5 | US-TECH-109, US-TECH-118 | S2-3 |
| US-TECH-032 | EP-TECH-04 | As a module developer, I want an accent-insensitive search helper (immutable `unaccent` wrapper, trigram index template, jOOQ/JPA predicate helper, Vietnamese test corpus) so that search on names works without diacritics; "nguyen van an" finds "Nguyễn Văn An"; index used (EXPLAIN test) | NFR-CORE-011 (part), NFR-PPL-009 (part) | 3 | US-TECH-025 | S2-3 |
| US-TECH-033 | EP-TECH-04 | As the platform operator, I want the manual two-person break-glass procedure (temporary `yhrm_breakglass` grant, ≤ 4 h revocation script, pgaudit, entry in the operator access log) so that emergency DB access is controlled; the procedure has been rehearsed once | BR-SAAS-036 (manual P1 variant), BR-SAAS-037 (part), NFR-SAAS-008 (part) | 2 | US-TECH-031 | S4-6; automation is P2 (R2-19) |

### EP-TECH-05 Outbox/inbox and DB-backed event bus

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-034 | EP-TECH-05 | As a module developer, I want an outbox writer API (`publish(event)` in the caller's transaction, envelope stamping of tenant, LE, correlation/causation, producer version, `initial_load` header, schema validation in tests, guard rejecting fields marked sensitive) so that no committed change exists without its event | NFR-PPL-002, NFR-CORE-004 (part), NFR-CORE-008 (part) | 5 | US-TECH-011, US-TECH-007 | S0-1 |
| US-TECH-035 | EP-TECH-05 | As an SRE, I want the production relay (per-cluster poller on a direct connection, `SKIP LOCKED` batches of 500 over every module outbox, `enqueue_event_delivery()` in the same transaction, leader-less replicas, platform events fanned out to every cluster with the sentinel tenant) so that events flow reliably; relay lag p95 < 5 s at the SP-2 test load | NFR-PPL-002, NFR-CORE-004 (part) | 5 | US-TECH-034, US-TECH-042 | S2-3 |
| US-TECH-036 | EP-TECH-05 | As a module developer, I want `@EventConsumer` subscriptions compiled into a registry and a `bus.Deliver` worker that reads the envelope through the relay connection, re-establishes the tenant context and runs the handler in the consumer module's transaction, role and inbox, in order per `consumer:aggregate`, so that consumers are idempotent and ordered; job payload is a pointer only | NFR-PPL-002, NFR-CORE-004 (part), NFR-PAY-004 (part) | 8 | US-TECH-035 | S2-3 |
| US-TECH-037 | EP-TECH-05 | As the platform operator, I want retries with exponential backoff (1 s … 10 min, 10 attempts), the `DEAD` state and an operator replay screen/API within the 7-day outbox window so that no event is silently lost; replay re-runs one or many DEAD deliveries; re-sync hook for older ones | NFR-CORE-004 (part), NFR-PPL-002 | 5 | US-TECH-036 | S2-3 |
| US-TECH-038 | EP-TECH-05 | As an architect, I want an `EventTransport` port (`JobQueueTransport` now, `KafkaTransport` interface stub for Phase 2) so that Phase 2 changes no producer or consumer code; ArchUnit forbids handlers depending on the transport | — | 2 | US-TECH-036 | S2-3 |
| US-TECH-039 | EP-TECH-05 | As an SRE, I want bus metrics and housekeeping (relay lag, delivery lag, DEAD count, `saas.job` size and bloat, daily purge of finished jobs) with alerts so that AR-14 is visible before it hurts | NFR-SAAS-010 (part) | 3 | US-TECH-036, US-TECH-119 | S2-3 |
| US-TECH-040 | EP-TECH-05 | As a module developer, I want a `ProcessManager` base in `shared/common` (state table, timeouts via the scheduler, idempotent steps, correlation) so that the hire, leaver, period-close, reorg and DSR sagas share one mechanism; example saga with a timeout tested | NFR-CORE-004 (part) | 5 | US-TECH-036, US-TECH-045 | S2-3 |
| US-TECH-041 | EP-TECH-05 | As the Core import owner, I want the initial-load mode on the bus (flag in headers, consumer helper to skip side effects, projection re-sync job template) so that opening-balance imports do not fire notifications or invitations | NFR-PPL-007 (part) | 2 | US-TECH-036 | S2-3 |

### EP-TECH-06 Job queue runtime and workers

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-042 | EP-TECH-06 | As a module developer, I want `JobService.submit(type, payload, options)` on `saas.job`, transactional with my business change (idempotency key, priority lane, `not_before`, `ordering_key`, versioned payload) and claimed with `saas.claim_jobs()` (`SKIP LOCKED`), so that background work is reliable; duplicate submit is a no-op; the job runs with the tenant context bound | BR-SAAS-090, NFR-CORE-015 (part) | 8 | US-TECH-024, US-TECH-014 | S0-1 |
| US-TECH-043 | EP-TECH-06 | As the platform operator, I want fair-share claiming (per-tenant slot cap 20% of the pool, floor 2, weighted round-robin) and suspension awareness (jobs of `SUSPENDED_LOCKED` tenants paused, allow-list for payslip/export/DSR) so that a big run cannot starve others | BR-SAAS-090, NFR-SAAS-005, NFR-PAY-001 (part) | 5 | US-TECH-042 | S2-3 |
| US-TECH-044 | EP-TECH-06 | As an SRE, I want separate worker Deployments (`payroll`, `general`, `events`, `reporting`) of the same image, with leases, heartbeat, stuck-job recovery and graceful drain on shutdown, so that rolling releases never lose or duplicate work; a killed pod's job is re-claimed after lease expiry | BR-SAAS-090, NFR-SAAS-014 (part) | 5 | US-TECH-042 | S2-3 |
| US-TECH-045 | EP-TECH-06 | As a module developer, I want db-scheduler cron and calendar triggers (00:00 Asia/Ho_Chi_Minh), per-tenant fan-out and an idempotent run key (tenant + date + job type) with catch-up after an outage, so that effective-dated activation jobs are idempotent and re-runnable; a re-run on the same date changes nothing | NFR-CORE-015, BR-SAAS-090 | 5 | US-TECH-042 | S2-3 |
| US-TECH-046 | EP-TECH-06 | As a user of a long-running job, I want progress, cancellation and a server-sent-events stream through the BFF so that imports, runs and exports show live progress; generic progress API consumed by the `JobProgress` component | BR-SAAS-090, NFR-PAY-001 (part) | 5 | US-TECH-042, US-TECH-054 | S2-3 |
| US-TECH-047 | EP-TECH-06 | As a Payroll/Core developer, I want a chunked job template (parent/child jobs, fan-out/fan-in, idempotent rewrite of a chunk, failed-chunk retry) so that payroll partitions, imports and exports share one pattern | NFR-PAY-001 (part), NFR-CORE-009 (part), NFR-ATT-003 (part) | 5 | US-TECH-042 | S2-3 |
| US-TECH-048 | EP-TECH-06 | As an SRE, I want KEDA scaling of each worker pool on queue depth (plus a calendar minimum for peaks) so that workers scale with load; scaling verified in `perf` | NFR-SAAS-021 (part), NFR-SAAS-005 (part) | 3 | US-TECH-044, US-TECH-107 | S4-6 |
| US-TECH-049 | EP-TECH-06 | As the platform operator, I want a job operations view (filter by tenant/queue/state, retry, cancel, DEAD list) and a retention purge so that support can unblock work without SQL | BR-SAAS-090 | 3 | US-TECH-042 | S4-6; screen in the operator back-office shell |

### EP-TECH-07 Identity, BFF and edge gateway

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-050 | EP-TECH-07 | As a platform engineer, I want Keycloak 26 in HA (≥ 2 replicas, own CNPG database), realm defined as code, password policy with a modern hash, brute-force detection and the VN SMTP relay, so that login is reliable and secure; realm reproducible from Git in every environment | BR-SAAS-038, BR-SAAS-043 (part), NFR-CORE-006, NFR-CORE-007, NFR-CORE-014 | 5 | US-TECH-012 | S0-1 |
| US-TECH-051 | EP-TECH-07 | As the SaaS provisioning service, I want a Keycloak admin adapter (create Organization per tenant, memberships, login hint by host slug, `tid`/`org` claim mappers) so that a tenant gets its identity setup automatically; 500 organisations created by script in the SP-8 test | BR-SAAS-003 (part), BR-SAAS-043 (part) | 5 | US-TECH-050 | S2-3 |
| US-TECH-052 | EP-TECH-07 | As a security officer, I want MFA by TOTP and by e-mail OTP, required for HR, C&B, finance, tenant-admin and operator roles (tenant setting for ESS), and ACR levels 1/2/3 with a 5-minute max age for `acr=3`, so that sensitive actions are protected; former employees log in with e-mail OTP | BR-SAAS-039, BR-SAAS-045 (part), NFR-CORE-007 | 8 | US-TECH-050, US-TECH-148 | S2-3; [high-uncertainty] e-mail OTP authenticator (built-in vs vetted extension) per SP-8 |
| US-TECH-053 | EP-TECH-07 | As an employee, I want a vi/en login theme (Keycloakify or FreeMarker) with the tenant's name and logo so that the login page is in my language; works at 360 px | NFR-SAAS-013 (part), NFR-SAAS-012 (part) | 3 | US-TECH-050 | S2-3 |
| US-TECH-054 | EP-TECH-07 | As a security officer, I want the production BFF (Spring Cloud Gateway server WebMVC + OAuth2 client, Authorization Code + PKCE, tokens encrypted in Valkey, HttpOnly `Secure` `SameSite=Strict` cookie, CSRF token, 5-minute access-token relay, refresh, logout) so that no token reaches the browser | BR-SAAS-043, NFR-CORE-014 (part) | 5 | US-TECH-012, US-TECH-064 | S0-1 |
| US-TECH-055 | EP-TECH-07 | As an employee changing my bank account or opening a payslip PDF, I want step-up (API answers 401 with `acr_values`, SPA re-authenticates through the BFF and resumes the action) so that sensitive actions need fresh MFA; `@RequiresAcr` annotation in `shared/auth` | NFR-PPL-011 (part), NFR-PAY-004 (part) | 5 | US-TECH-052, US-TECH-087 | S2-3 |
| US-TECH-056 | EP-TECH-07 | As a platform engineer, I want resource-server configuration in `shared/auth` (local JWT validation with cached and rotated JWKS, claim mapping `sub`/`tid`/`org`/`acr`/`amr`/`sid`) so that no request calls Keycloak synchronously | NFR-CORE-006 | 3 | US-TECH-012 | S0-1 |
| US-TECH-057 | EP-TECH-07 | As a security officer, I want session revocation plumbing (on `core.UserStatusChanged` disable/log out the user in that Organization through the admin API; back-channel logout drops BFF sessions by `sid`) so that a deactivated user loses access within 5 minutes | BR-SAAS-043 (part) | 3 | US-TECH-051, US-TECH-054, US-TECH-036 | S2-3 |
| US-TECH-058 | EP-TECH-07 | As a security officer, I want Envoy Gateway (Gateway API) with TLS 1.2+/1.3 (cert-manager wildcard `*.yourhrm.vn`), WAF (OWASP CRS or provider WAF), IP rate limits, security headers (HSTS, CSP, frame-ancestors) and routes to the BFF and static SPA so that the edge is hardened; SSL Labs grade A | NFR-SAAS-007 (part), NFR-CORE-014 (part), NFR-SAAS-009 (part) | 5 | US-TECH-107 | S2-3 |
| US-TECH-059 | EP-TECH-07 | As the platform operator, I want a separate operator realm with mandatory MFA and an IP allow-list for the back-office so that operator accounts are isolated from tenant identities | BR-SAAS-032 (part), NFR-SAAS-009 (part) | 3 | US-TECH-050 | S4-6 |

### EP-TECH-08 Authorisation plumbing and caching

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-060 | EP-TECH-08 | As a module developer, I want `@RequiresPermission` method security and a `PermissionResolver` SPI in `shared/auth`, implemented by Core, with a denial hook to the audit client, so that every module enforces permissions the same way; denial on a sensitive resource writes an audit event | NFR-CORE-014 (part), NFR-PAY-004 (part) | 5 | US-TECH-056 | S0-1; Core implements the resolver |
| US-TECH-061 | EP-TECH-08 | As a platform engineer, I want the authorisation snapshot cache (Caffeine L1 60 s + Valkey L2, invalidation by `core.RoleAssignmentChanged`, `people.AssignmentChanged`, `core.OrgUnitChanged` with pub/sub fan-out) so that permission checks cost < 1 ms on hit; cold compute measured ≤ 20 ms p95 | NFR-CORE-002 | 5 | US-TECH-060, US-TECH-064, US-TECH-036 | S2-3 |
| US-TECH-062 | EP-TECH-08 | As a module developer, I want a `ScopePredicate` SPI that turns data scopes (SELF, DIRECT_REPORTS, ALL_REPORTS, ORG_SUBTREE, LEGAL_ENTITY, LOCATION) into index-friendly SQL (jOOQ `Condition`, JPA `Specification`) on `org_path`/closure keys, so that lists and exports apply the same scope as screens; EXPLAIN tests show index use; ≤ 20 ms p95 overhead in SP-2 | NFR-CORE-002 | 8 | US-TECH-060, US-TECH-141 | S2-3 |
| US-TECH-063 | EP-TECH-08 | As a security officer, I want field masking (`@Sensitive(category)` Jackson module driven by the field profile; `app.mask_level` set per transaction; masked values never serialised) so that salary, bank and ID data are hidden from users without rights; contract test per category | NFR-PAY-004, NFR-PPL-005 (part), BR-SAAS-034 (part) | 5 | US-TECH-060, US-TECH-015 | S2-3 |
| US-TECH-064 | EP-TECH-08 | As a platform engineer, I want Valkey 8 (replicated, TLS, sentinel or provider HA) and a two-level cache library (Caffeine + Valkey, a key wrapper that rejects un-prefixed tenant keys, pub/sub invalidation) so that all caches are tenant-safe; an un-prefixed key throws in tests | NFR-SAAS-003 (part), NFR-CORE-001 (part) | 5 | US-TECH-008 | S0-1 (needed by the BFF) |

### EP-TECH-09 PII encryption and secrets

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-065 | EP-TECH-09 | As a security officer, I want OpenBao in HA (3-node Raft) with Kubernetes auth, policies that give no human read access to module DB credentials, an audit device, an unseal procedure for the software root of trust and hourly Raft snapshots to DC2, so that secrets and keys live in VN under control | NFR-SAAS-008, NFR-CORE-007, NFR-SAAS-007 (part) | 8 | US-TECH-107 | S2-3; [high-uncertainty] unseal without a cloud KMS; HSM is pre-Enterprise (OD-10) |
| US-TECH-066 | EP-TECH-09 | As a security officer, I want dynamic PostgreSQL credentials per module role (database secrets engine, short TTL, Hikari credential refresh without dropping traffic), other secrets via External Secrets, rotation ≤ 90 days and gitleaks in CI, so that no credential lives in code, images or human hands (R2-15) | NFR-SAAS-008, NFR-PAY-004 | 8 | US-TECH-065, US-TECH-026 | S2-3; [high-uncertainty] Hikari + PgBouncer behaviour under credential rotation |
| US-TECH-067 | EP-TECH-09 | As a module developer, I want an envelope-encryption library (per-tenant KEK in OpenBao transit, per-subject DEK in `saas.subject_key`, AES-256-GCM with a key-id/version/nonce header, in-memory caches with TTL, JPA/jOOQ converters for `_enc` columns) so that P3 fields are encrypted transparently; 10k unwraps < 1 s | NFR-PPL-005, NFR-PAY-004, NFR-SAAS-007, NFR-CORE-007 | 8 | US-TECH-065 | S2-3 |
| US-TECH-068 | EP-TECH-09 | As a module developer, I want blind-index (HKDF tenant key, HMAC-SHA256 truncated to 16 bytes) and `_last4` helpers so that CCCD, tax code and SI number can be matched within the tenant only; no cross-tenant match possible by design | NFR-PPL-005, NFR-PPL-008 (part) | 3 | US-TECH-067 | S2-3 |
| US-TECH-069 | EP-TECH-09 | As a security officer, I want key rotation (KEK rotation re-wraps DEKs; DEK rotation re-encrypts lazily on write plus a background job finishing ≤ 90 days) so that keys can be rotated without downtime | NFR-SAAS-007, NFR-SAAS-008 | 5 | US-TECH-067, US-TECH-047 | GA-def: first rotation is due ≤ 90 days after go-live |
| US-TECH-070 | EP-TECH-09 | As the DPO, I want crypto-shredding plumbing (subject shred: `wrapped_dek` NULL + `destroyed_at`; tenant KEK destruction scheduling; import-job DEK destroyed 30 days after commit) with a verification test across primary, replica and reporting store, so that DSR erasure and retention are provable | NFR-SAAS-007, BR-SAAS-081 (part) | 5 | US-TECH-067 | S4-6; DSR saga in SaaS backlog |
| US-TECH-071 | EP-TECH-09 | As an SRE, I want a KMS degraded mode (KEK cache TTL in memory, masking fallback, alert) so that an OpenBao outage degrades to masked display rather than errors everywhere | NFR-SAAS-001 (part) | 3 | US-TECH-067 | S4-6 |
| US-TECH-072 | EP-TECH-09 | As the DPO, I want PII masking in logs (allow-list structured appender, no request bodies for sensitive endpoints, test that a CCCD never reaches Loki) so that logs hold no personal identifiers | NFR-PPL-005, NFR-SAAS-010 | 3 | US-TECH-119 | S4-6 |
| US-TECH-073 | EP-TECH-09 | As the DPO, I want a non-production masking pipeline (deterministic fake substitution, re-encryption under a sandbox key, synthetic Vietnamese names) so that copies outside production never contain real PII | NFR-PPL-005 (part) | 5 | US-TECH-067 | GA-def: BR-SAAS-031 sandbox is P2 and no production copies are made before pilot |

### EP-TECH-10 Object storage and file safety

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-074 | EP-TECH-10 | As a module developer, I want an object-storage adapter (AWS SDK v2, key layout `t/{tenantId}/{module}/{yyyy}/{uuid}`, tenant-prefix guard, server-side encryption, lifecycle rules for 24 h transient and 7-day export objects) so that files are stored per tenant in VN | BR-SAAS-024 (part), NFR-SAAS-007 (part) | 5 | US-TECH-014 | S2-3 |
| US-TECH-075 | EP-TECH-10 | As a security officer, I want an upload pipeline (configurable size limit, default 20 MB; content-type sniffing; quarantine → ClamAV scan ≤ 60 s with signature updates from a VN mirror; scan-status event) so that no malware becomes downloadable; EICAR file rejected | NFR-CORE-010 | 8 | US-TECH-074 | S2-3 |
| US-TECH-076 | EP-TECH-10 | As the Core document service, I want presigned download URLs (≤ 15 min, issued only after an authorisation check, with a download audit hook and a step-up flag) so that files are never publicly addressable | NFR-CORE-010 | 3 | US-TECH-074 | S2-3 |
| US-TECH-077 | EP-TECH-10 | As an SRE, I want cross-site bucket replication to DC2 and a WORM (object-lock) bucket for audit anchors and archives so that files meet the RPO and audit anchors cannot be altered | NFR-SAAS-006 (part), NFR-CORE-008 (part) | 3 | US-TECH-074, US-TECH-111 | S4-6 |
| US-TECH-078 | EP-TECH-10 | As the Core document team, I want Gotenberg and a render base image with embedded Vietnamese fonts (Noto Sans/Serif, Be Vietnam Pro) deployed so that PDF/A output renders diacritics correctly | NFR-PPL-010 (part), NFR-SAAS-013 (part) | 2 | US-TECH-107 | S2-3; needed by SP-5 |

### EP-TECH-11 Reporting-store feed (conditional)

BR-SAAS-047 (separate reporting store) is rated **P2**, but R3-11 accepted the logical-replication feed as the Phase-1 design. If the PM confirms P2, this epic (28 SP) and US-TECH-145 (5 SP) leave Phase 1, and pilot reports run on a read replica through US-TECH-017.

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-079 | EP-TECH-11 | As a data engineer, I want a reporting PostgreSQL cluster with `rpt_raw` (landing, no app access) and `rpt` (RLS on every table) schemas and its roles so that cross-module reports never touch OLTP | NFR-SAAS-003 (part) | 3 | US-TECH-109 | S4-6; conditional |
| US-TECH-080 | EP-TECH-11 | As a data engineer, I want publications per module schema with column lists that exclude every P3 column, subscriptions, decoding from a standby (or primary with `max_slot_wal_keep_size`) and slot-lag/WAL alerts, so that reporting data is fresh (≤ 15 min) without exposing identifiers | NFR-SAAS-003 (part), NFR-PPL-005 (part) | 8 | US-TECH-079, US-TECH-145 | S4-6; conditional; [high-uncertainty] provider support for standby decoding (SP-6) |
| US-TECH-081 | EP-TECH-11 | As a developer, I want a CI check that every migration touching a published table has its `rpt_raw` counterpart, plus a subscription-refresh step in the release pipeline, so that DDL drift cannot break replication | NFR-SAAS-014 (part) | 5 | US-TECH-080, US-TECH-027 | S4-6; conditional |
| US-TECH-082 | EP-TECH-11 | As a report developer, I want a transformation framework (scheduled SQL models on the `reporting` pool, upsert and SCD2 helpers, freshness metric) so that module report stories only write their models | NFR-SAAS-003 (part) | 5 | US-TECH-080, US-TECH-045 | S4-6; conditional |
| US-TECH-083 | EP-TECH-11 | As a security officer, I want the per-user `report_scope` table refreshed from authorisation snapshots on change so that reports apply the same data scope as screens | NFR-CORE-002 (part), NFR-PAY-004 (part) | 5 | US-TECH-061, US-TECH-082 | S4-6; conditional |
| US-TECH-084 | EP-TECH-11 | As the DPO, I want tests showing that DSR and purge deletes propagate to the reporting store and that `rpt` is in the isolation suite so that the store keeps nothing the OLTP has deleted | NFR-CORE-001 (part) | 2 | US-TECH-080, US-TECH-021 | S4-6; conditional |

### EP-TECH-12 Frontend foundation

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-085 | EP-TECH-12 | As a frontend developer, I want the workspace (Vite 7, React 19, TS strict, TanStack Router + Query, React Hook Form + Zod, eslint-plugin-boundaries per module, Vitest, `admin` and `ess` entry points, lazy module routes) so that modules start building screens in sprint 1 | — | 5 | — | S0-1 |
| US-TECH-086 | EP-TECH-12 | As a frontend developer, I want an API client generated by orval from each module's OpenAPI (TanStack Query hooks, problem-details errors, CSRF, `Idempotency-Key`, ETag) so that no one hand-writes fetch code; CI fails when the client is stale | — | 3 | US-TECH-085, US-TECH-006 | S0-1 |
| US-TECH-087 | EP-TECH-12 | As a user, I want the SPA to use the BFF session (login redirect, session-expiry handling, step-up re-authentication on 401 with `acr_values`, logout) so that sign-in and step-up feel seamless | BR-SAAS-043 (part), NFR-PPL-011 (part) | 5 | US-TECH-054, US-TECH-085 | S2-3 |
| US-TECH-088 | EP-TECH-12 | As a module team, I want the Admin and ESS shells (AdminLayout and responsive EssLayout ≥ 360 px, a route/menu registry modules plug into, menus and guards from `/me/capabilities`, 403/404/error boundaries) so that module pages only register themselves | BR-SAAS-075 (part), NFR-SAAS-012, NFR-PPL-011 (part) | 8 | US-TECH-085 | S0-1 |
| US-TECH-089 | EP-TECH-12 | As a UX designer, I want the Ant Design 5 theme with YourHRM tokens (colour, typography, density), the `vi_VN` locale and Storybook so that every screen looks consistent | NFR-SAAS-013 (part) | 3 | US-TECH-085 | S0-1 |
| US-TECH-090 | EP-TECH-12 | As a module team, I want shared components set 1 (DataTable with server paging/filter/sort and export trigger, FileUpload with scan status, MoneyInput VND, MaskedField) so that list and form screens are fast to build; each in Storybook with tests | NFR-PAY-011 (part), NFR-SAAS-003 (part) | 8 | US-TECH-089, US-TECH-086 | S2-3 |
| US-TECH-091 | EP-TECH-12 | As a module team, I want shared components set 2 (EffectiveDatedForm with an "as of" picker and history drawer, OrgTreePicker with accent-insensitive search, ApprovalTimeline, JobProgress over SSE) so that effective-dated and workflow screens behave the same everywhere | NFR-PPL-001 (part), NFR-CORE-011 (part) | 8 | US-TECH-090, US-TECH-046 | S2-3 |
| US-TECH-092 | EP-TECH-12 | As a user, I want the i18n framework (react-i18next with ICU, a namespace per module, vi default + en, CI check for missing keys and mixed language, VND/date/time-zone formatting; backend ICU message bundles for validation and problem messages by user locale) so that every label is localised; switching the language changes the whole UI | BR-SAAS-026 (part), NFR-SAAS-013, NFR-CORE-011 (part), NFR-PPL-009 (part), NFR-PAY-011 (part) | 5 | US-TECH-085 | S0-1 |
| US-TECH-093 | EP-TECH-12 | As a QA lead, I want Playwright end-to-end tests against seeded tenants, visual regression on the design system and a browser matrix (Chromium, Firefox, WebKit, latest two versions) at 360 px and desktop so that browser support is tested in CI | NFR-SAAS-012 | 5 | US-TECH-088, US-TECH-115 | S4-6 |
| US-TECH-094 | EP-TECH-12 | As an SRE, I want Grafana Faro RUM (web vitals, JS errors, tenant tag, no PII) so that frontend performance is visible | NFR-SAAS-010 (part), NFR-SAAS-003 (part) | 2 | US-TECH-118 | S4-6 |
| US-TECH-095 | EP-TECH-12 | As an SRE, I want SPA static hosting (container or VN object storage/CDN behind Envoy, immutable hashed assets, CSP nonce) so that frontend releases are independent and cacheable | BR-SAAS-024 (part) | 2 | US-TECH-058 | S4-6 |

### EP-TECH-13 CI/CD and supply chain

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-096 | EP-TECH-13 | As a developer, I want a CI platform with VN-hosted runners (self-managed GitLab CI or GitHub Actions + VN runners), Gradle build cache and Testcontainers support so that code and test data never leave VN | BR-SAAS-024 (part) | 5 | — | S0-1 |
| US-TECH-097 | EP-TECH-13 | As a developer, I want the merge-request pipeline (build → unit + Modulith + ArchUnit → contract → integration → frontend → Jib image → Harbor) under 20 minutes with trunk-based branch protection so that main is always releasable | NFR-SAAS-014 (part) | 5 | US-TECH-096, US-TECH-002 | S0-1 |
| US-TECH-098 | EP-TECH-13 | As a security officer, I want SAST (Semgrep, SpotBugs + FindSecBugs), SCA (OSV/Dependency-Check), Trivy (images, IaC) and gitleaks gates blocking critical findings, with remediation-SLA tracking (critical 7 days, high 30 days), so that vulnerabilities are caught before merge | NFR-SAAS-009, NFR-SAAS-008 (part) | 5 | US-TECH-097 | S2-3 |
| US-TECH-099 | EP-TECH-13 | As a security officer, I want a CycloneDX SBOM per build, Cosign signing, Harbor in VN with a DC2 mirror and a Kyverno policy that admits only signed images so that the supply chain is verifiable | NFR-SAAS-009 | 5 | US-TECH-097, US-TECH-107 | S2-3 |
| US-TECH-100 | EP-TECH-13 | As a release manager, I want Argo CD promotion by Git tag (dev → sit → uat → prod pilot ring → GA ring), release gates (isolation suites, golden payroll suite, DAST) and a payroll-window freeze (days 25–5) enforced by the pipeline with an approved hotfix override, so that releases are safe and regular (≥ every 2 weeks) | NFR-SAAS-014, NFR-SAAS-015, NFR-SAAS-001 (part) | 5 | US-TECH-108, US-TECH-020 | S4-6 |
| US-TECH-101 | EP-TECH-13 | As a tenant admin, I want vi/en release notes generated from labelled merge requests and published ≥ 3 working days before the release so that changes are announced on time | NFR-SAAS-015 | 2 | US-TECH-100 | S4-6 |
| US-TECH-102 | EP-TECH-13 | As an SRE, I want zero-downtime rollouts (readiness/liveness, PodDisruptionBudgets, graceful shutdown, an N/N+1 compatibility test with the old app on the new schema) and a rehearsed rollback ≤ 15 min so that routine releases cause no downtime | NFR-SAAS-014, NFR-PAY-009 (part) | 5 | US-TECH-100, US-TECH-027 | S4-6 |
| US-TECH-103 | EP-TECH-13 | As the legal content owner, I want the pipeline to run the golden payroll suite automatically when a legal pack enters `IN_TEST`, and on every release, so that a pack is never published untested; scenarios are owned by Payroll | NFR-PAY-008 (part), NFR-SAAS-016 (part), NFR-PAY-007 (part) | 3 | US-TECH-100 | S4-6 |
| US-TECH-104 | EP-TECH-13 | As a Payroll developer, I want the engine kernel built as a separately versioned artifact with frozen dependencies, kept ≥ 10 years in the registry and loadable in an isolated classloader, so that `ReproduceRun` can recompute old runs with their original kernel | NFR-PAY-003 (part), NFR-SAAS-017 (part) | 5 | US-TECH-097 | S4-6; [high-uncertainty] classloader isolation of old kernels; `ReproduceRun` job in Payroll backlog |
| US-TECH-105 | EP-TECH-13 | As a Payroll/T&A developer, I want the code release version and git SHA available at runtime (API and injectable bean) so that every result can be stamped with the code version | NFR-SAAS-017 (part), NFR-ATT-006 (part) | 1 | US-TECH-097 | S2-3 |

### EP-TECH-14 Environments, IaC, GitOps, backup and DR

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-106 | EP-TECH-14 | As a platform engineer, I want OpenTofu modules behind a provider interface (network, managed K8s, node pools, object storage, DNS), with state in VN, for the provider chosen after SP-7, so that environments are reproducible and portable | BR-SAAS-024 | 8 | US-TECH-147 | S2-3; [high-uncertainty] depends on OD-1 provider and its API maturity |
| US-TECH-107 | EP-TECH-14 | As a platform engineer, I want prod and non-prod Kubernetes clusters with node pools (app, workers, data, platform), Cilium with WireGuard node-to-node encryption, default-deny network policies with an egress allow-list, cert-manager and external-dns, so that the runtime baseline is secure | NFR-SAAS-007, BR-SAAS-024, NFR-SAAS-001 (part) | 8 | US-TECH-106 | S2-3 |
| US-TECH-108 | EP-TECH-14 | As an SRE, I want GitOps (Argo CD app-of-apps, Helm charts for `hrm-api`, worker pools and `hrm-bff`, values for dev/sit/perf/uat/prod, per-MR preview environments in dev) so that every deployment is declarative | NFR-SAAS-014 (part) | 8 | US-TECH-107, US-TECH-013 | S2-3 |
| US-TECH-109 | EP-TECH-14 | As an SRE, I want CloudNativePG clusters (platform, pool-01, Keycloak, reporting) with a synchronous standby + async replica, PgBouncer poolers (transaction mode, separate payroll pooler), TLS and the parameter/extension baseline so that the data tier is HA | NFR-SAAS-001, NFR-PAY-009, NFR-SAAS-005 (part) | 8 | US-TECH-107 | S2-3 (non-prod), S4-6 (prod) |
| US-TECH-110 | EP-TECH-14 | As an SRE, I want Barman Cloud backups (daily base + continuous WAL to DC2 storage, `archive_timeout` 5 min, encrypted, 35-day retention) with an automated weekly restore test in non-prod so that RPO ≤ 15 min is real | NFR-SAAS-006 | 5 | US-TECH-109 | S4-6 |
| US-TECH-111 | EP-TECH-14 | As an SRE, I want the DC2 DR site (CNPG replica clusters, Argo CD in DC2, image mirror, OpenBao snapshot restore, low-TTL DNS with a scripted switch) and its runbook so that RTO ≤ 4 h is achievable | NFR-SAAS-006 | 8 | US-TECH-110, US-TECH-065 | S4-6 |
| US-TECH-112 | EP-TECH-14 | As the sponsor, I want a DR drill before pilot go-live (simulated DC1 loss; RPO/RTO measured; report; quarterly schedule set) so that DR is proven, not assumed | NFR-SAAS-006 | 3 | US-TECH-111 | SIT |
| US-TECH-113 | EP-TECH-14 | As the platform operator, I want per-tenant point-in-time restore (cluster PITR into a scratch instance, then extract one tenant's rows into a non-production tenant through a scripted extract) so that one tenant's mistake can be recovered without affecting others | NFR-SAAS-006 | 5 | US-TECH-110 | SIT; the documented tenant export format is P2 (NFR-SAAS-019), so this script is minimal |
| US-TECH-114 | EP-TECH-14 | As an SRE, I want the VN SMTP relay wired (SPF, DKIM, DMARC for sending domains, bounce mailbox) for Keycloak and the app so that OTP and payslip e-mails are delivered and stay in VN | BR-SAAS-066 (part), BR-SAAS-024 (part) | 2 | US-TECH-107 | S4-6; adapter in SaaS backlog |
| US-TECH-115 | EP-TECH-14 | As a QA/perf engineer, I want a synthetic data generator (Vietnamese names, LEs, org trees, employees, salaries, attendance) for 10k/50k/200k-employee reference sets and the seeded `sit` tenants so that tests never use real PII | NFR-SAAS-002 (part), NFR-SAAS-003 (part), NFR-SAAS-008 (part) | 5 | US-TECH-025 | S2-3 |
| US-TECH-116 | EP-TECH-14 | As an SRE, I want HA chaos tests (primary DB failover ≤ 60 s with no committed transaction lost, node drain, Keycloak/Valkey/OpenBao replica loss) so that QAS-6 is evidenced | NFR-SAAS-001, NFR-CORE-006, NFR-PAY-009 | 3 | US-TECH-109 | S4-6 |
| US-TECH-117 | EP-TECH-14 | As a tenant, I want maintenance windows enforced (22:00–05:00 ICT, never days 25–5 without consent) and a VN-hosted status page so that planned work never hits payroll | NFR-SAAS-001, NFR-SAAS-020 (part) | 2 | US-TECH-100 | SIT |

### EP-TECH-15 Observability and operations

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-118 | EP-TECH-15 | As an SRE, I want the observability stack in VN (OTel Collector DaemonSet + gateway, Mimir, Loki, Tempo, Grafana, Alertmanager on object storage; logs ≥ 12 months, security logs ≥ 24 months in a separate stream) so that telemetry and its retention meet the residency rules | NFR-SAAS-010, BR-SAAS-024 (part) | 8 | US-TECH-107 | S2-3; Decree 53/2022 period `[verify-legal]` |
| US-TECH-119 | EP-TECH-15 | As an SRE, I want application instrumentation (OTel agent + Micrometer, `tenant_id` and `correlation_id` in MDC and span attributes, trace propagation BFF → api → jobs → event delivery) so that one request can be followed across async hops | NFR-SAAS-010 | 5 | US-TECH-118, US-TECH-014 | S2-3 |
| US-TECH-120 | EP-TECH-15 | As a support engineer, I want dashboards per module, for the platform, the DB (`pg_stat_statements` top-N tenants), the job queue/bus and Keycloak, plus a per-tenant support dashboard, so that I can diagnose a tenant's problem quickly | NFR-SAAS-010, NFR-SAAS-020 (part) | 5 | US-TECH-119 | S4-6 |
| US-TECH-121 | EP-TECH-15 | As an SRE, I want SLOs with multi-window burn-rate alerts (availability 99.5%, login 99.9%, p95 500 ms, relay/delivery lag, DEAD = 0, payroll run duration, import throughput, replication freshness) routed to an on-call rota so that breaches are noticed before customers | NFR-SAAS-001, NFR-CORE-006, NFR-PAY-009, NFR-SAAS-020 (part) | 5 | US-TECH-120 | S4-6 |
| US-TECH-122 | EP-TECH-15 | As a security officer, I want a security-event pipeline (cross-tenant attempts, auth failures, break-glass, module-role logins from outside the app pods → alert ≤ 1 min; SIEM rules; breach runbook with a 72 h timer) so that QAS-1 and PDPL breach notification are supported | BR-SAAS-084 (part), NFR-SAAS-009 (part), NFR-SAAS-010 (part) | 5 | US-TECH-118, US-TECH-031 | S4-6; [high-uncertainty] Wazuh vs Grafana-only SIEM |
| US-TECH-123 | EP-TECH-15 | As a module team, I want a business-monitor framework (register a check as a metric + alert rule) so that period-close mismatches, settlement deadlines, failed statutory validations and packs effective without publication alert operators; checks owned by modules | — | 2 | US-TECH-121 | S4-6 |
| US-TECH-124 | EP-TECH-15 | As the support lead, I want runbooks (DB failover, DR, Keycloak, OpenBao unseal, DEAD replay, tenant restore) and the incident severity process with vi/en support routing so that the pilot support SLA can be met | NFR-SAAS-020 | 3 | US-TECH-121 | SIT |

### EP-TECH-16 Security hardening and pen-test support

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-125 | EP-TECH-16 | As a security officer, I want the OWASP ASVS L2 control baseline mapped to platform stories and added to the Definition of Done per epic (headers, CORS, CSRF, input validation, safe deserialisation) so that every module inherits the controls | NFR-SAAS-009, NFR-CORE-014 | 3 | US-TECH-058 | S4-6 |
| US-TECH-126 | EP-TECH-16 | As a security officer, I want an authenticated OWASP ZAP scan (scripted BFF login, API scan from OpenAPI) on staging for each release candidate, with triage, so that DAST findings block releases | NFR-SAAS-009 | 3 | US-TECH-100 | S4-6 |
| US-TECH-127 | EP-TECH-16 | As an architect, I want a STRIDE threat model of tenancy, identity, file upload, payslip PDF delivery and the event bus, with a fix list, so that design flaws are found before the pen test | NFR-SAAS-009 | 3 | US-TECH-054, US-TECH-075 | S4-6 |
| US-TECH-128 | EP-TECH-16 | As a security officer, I want the third-party pen test prepared (scope, test tenants and accounts, environment, rules of engagement) so that it runs in SIT without delay | NFR-SAAS-009 | 2 | US-TECH-126 | S4-6 |
| US-TECH-129 | EP-TECH-16 | As the sponsor, I want critical and high pen-test findings fixed and retested before pilot go-live so that the pilot starts without known severe vulnerabilities | NFR-SAAS-009 | 8 | US-TECH-128 | SIT; [high-uncertainty] reserve: depends on findings |
| US-TECH-130 | EP-TECH-16 | As a security officer, I want evidence of TLS 1.2+ on every hop (edge, PostgreSQL, Valkey, SMTP; testssl.sh) and of AES-256 volume encryption on the provider, so that the encryption requirements can be shown to auditors | NFR-SAAS-007, NFR-CORE-007 | 2 | US-TECH-107, US-TECH-109 | S4-6 |
| US-TECH-131 | EP-TECH-16 | As a security officer, I want admin access hardened (kubectl via OIDC + RBAC, K8s API audit, bastion with session recording, no direct prod DB access except break-glass) so that operator access is controlled and logged | NFR-SAAS-009, NFR-SAAS-008, BR-SAAS-037 (part) | 5 | US-TECH-107, US-TECH-059 | S4-6 |
| US-TECH-132 | EP-TECH-16 | As the compliance owner, I want the technical inputs for the information-system security-level dossier (Decree 85: architecture, data flows, controls, evidence from US-TECH-130/131) so that the classification can be filed | BR-SAAS-087 | 2 | US-TECH-130 | S4-6; non-build statutory item |

### EP-TECH-17 Performance engineering

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-133 | EP-TECH-17 | As a perf engineer, I want a k6 (or Gatling) harness in `tests/` (scenario library through the BFF with session login, synthetic tenants, on-demand `perf` environment, results in Grafana, a smoke perf job on main) so that performance is measured continuously | NFR-SAAS-003, NFR-SAAS-002 (part) | 5 | US-TECH-115, US-TECH-118 | S2-3 |
| US-TECH-134 | EP-TECH-17 | As the sponsor, I want the reference-load interactive test (500 tenants / 200k employees; p95 ≤ 500 ms transactional, ≤ 3 s for 5,000-row reports; module scenarios such as profile ≤ 2 s and org as-of ≤ 1 s registered by the modules) so that NFR-SAAS-003 is verified before go-live | NFR-SAAS-003, NFR-SAAS-005, NFR-PPL-003 (part), NFR-CORE-003 (part) | 5 | US-TECH-133 | S4-6 |
| US-TECH-135 | EP-TECH-17 | As a Payroll developer, I want the SP-1 payroll performance harness (10k and 50k synthetic payroll inputs with ~60 elements, 20% NET employees, a run driver and per-phase timings for snapshot, chunks and aggregate, single-employee recalc timing) so that the engine is measured on the same data every time; the engine itself is Payroll's | NFR-PAY-001, NFR-SAAS-002 | 5 | US-TECH-115, US-TECH-047 | S2-3 |
| US-TECH-136 | EP-TECH-17 | As the sponsor, I want batch perf tests (50k-row import validate ≤ 5 min, 100k-row export ≤ 5 min, 250k-punch file ≤ 10 min, period close 10k ≤ 15 min, nightly reprocessing ≤ 30 min, 10k payslip publication ≤ 30 min) so that batch NFRs are evidenced; modules provide the operations | NFR-CORE-009, NFR-ATT-002, NFR-ATT-003, NFR-CORE-005 (part) | 5 | US-TECH-133 | S4-6 |
| US-TECH-137 | EP-TECH-17 | As the platform operator, I want the noisy-neighbour test of QAS-2 (one 10k FINAL run while 50 tenants work) so that the 20% cap and the other tenants' p95 are proven | NFR-SAAS-005, NFR-PAY-001 (part) | 3 | US-TECH-134, US-TECH-043 | S4-6 |
| US-TECH-138 | EP-TECH-17 | As an SRE, I want calendar pre-scaling (days 24–6 and Jan–Mar at 3× baseline via node-pool schedules and KEDA minimums), a capacity model and a pre-peak load-test procedure so that payroll close and PIT season are covered | NFR-SAAS-021, NFR-PAY-009 (part) | 3 | US-TECH-048, US-TECH-134 | SIT |
| US-TECH-139 | EP-TECH-17 | As the team, I want a tuning reserve to fix the findings of SP-1, SP-2 and the perf tests (RLS plans, indexes, pool sizes, N+1 queries) so that perf findings do not displace feature work | NFR-SAAS-003, NFR-SAAS-002 | 8 | US-TECH-134, US-TECH-136 | SIT; [high-uncertainty] reserve |

### EP-TECH-18 Spikes

| ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes |
|---|---|---|---|---|---|---|
| US-TECH-140 | EP-TECH-18 | As the sponsor, I want SP-1 executed on the harness (10k and 50k runs, profiling, comparison with the ≤ 5 min design target and the 15 min NFR) and a decision note that closes OD-3 (Java 25/Boot 4 kept or the 21/3.5 fallback) so that the stack is fixed by the end of sprint 3 | NFR-PAY-001, NFR-SAAS-002 | 5 | US-TECH-135; Payroll engine first slice | S2-3; engine work in Payroll backlog |
| US-TECH-141 | EP-TECH-18 | As the architect and the Data architect, I want SP-2a (RLS + `set_config` + PgBouncer overhead at 200k employees / 500 tenants; scope predicates ≤ 20 ms; prepared statements through PgBouncer 1.21+) so that AR-1 is retired; p95 overhead < 10% on the reference queries | NFR-CORE-002, NFR-SAAS-003, NFR-SAAS-005 | 8 | US-TECH-115, US-TECH-016 | S2-3; [high-uncertainty] |
| US-TECH-142 | EP-TECH-18 | As the architect, I want SP-2b (relay + ordered delivery throughput and `saas.job` bloat at 10× pilot volume: 10k payslip publication, a 50k-row import) so that the measured limit is recorded as the Kafka trigger | NFR-CORE-004, NFR-PPL-002 | 5 | US-TECH-036, US-TECH-133 | S2-3 |
| US-TECH-143 | EP-TECH-18 | As the Payroll BA, I want SP-4 technical support (a YFL sandbox environment, a loader for pilot pay rules, a report of missing functions) so that formula coverage (≥ 95%) can be assessed; spike led by Payroll | NFR-PAY-007 (part) | 2 | YFL first slice (Payroll) | S2-3 |
| US-TECH-144 | EP-TECH-18 | As the Core document team, I want SP-5 (10k payslip PDF/A with Vietnamese fonts ≤ 10 min, plus AES-256 password-protected copies tested in common VN mail clients and phone PDF viewers; DOCX → PDF fidelity of pilot templates) so that NFR-CORE-005 and R2-17 are de-risked | NFR-CORE-005, NFR-PPL-010, NFR-SAAS-013 (part) | 5 | US-TECH-078 | S2-3 |
| US-TECH-145 | EP-TECH-18 | As the Data architect, I want SP-6 Phase-1 part (native logical replication with column lists from a standby on the chosen provider: freshness, DDL handling, slot monitoring) so that ADR-0012 Amendment 1 is confirmed in practice; P2-prep part (BI RLS, ClickHouse) is out of Phase 1 | NFR-SAAS-003 (part) | 5 | US-TECH-109, US-TECH-147 | S2-3; conditional with EP-TECH-11 |
| US-TECH-146 | EP-TECH-18 | As the sponsor, I want SP-7a hands-on evaluation of two VN providers (managed K8s, S3 replication between DCs, KMS/HSM option, PG extensions incl. pgaudit and logical replication, standby decoding, two DCs, network) so that the provider choice is based on tests | BR-SAAS-024, NFR-SAAS-006 (part) | 8 | — | S0-1; [high-uncertainty] provider access and quotes may be slow |
| US-TECH-147 | EP-TECH-18 | As the sponsor, I want SP-7b (scored matrix, quotes for the §14.6 sizing, DR fit, decision pack) so that OD-1 can be decided at the end of sprint 1 | BR-SAAS-024 | 3 | US-TECH-146 | S0-1 |
| US-TECH-148 | EP-TECH-18 | As the security officer, I want SP-8 (Organizations at 500 orgs, ACR step-up, TOTP + e-mail OTP, OTP for former employees, admin API automation) so that the Keycloak design is confirmed before US-TECH-051/052 | BR-SAAS-039, BR-SAAS-043 (part) | 5 | US-TECH-050 | S0-1 |
| US-TECH-149 | EP-TECH-18 | As the T&A BA, I want SP-9 technical support (parse real export files from the pilot's device software, draft the column-layout configs, enrol-id mapping and the dedup rule, one month imported with 0 duplicates) so that the T&A punch import type is specified; import type built in the T&A backlog | NFR-ATT-002 (part) | 3 | Core import engine first slice | S2-3 |

---

## 3. Coverage check: P1 NFRs

All 58 P1 NFRs are listed below.
- **TECH**: this file delivers the requirement.
- **Shared**: this file delivers the plumbing or verification, and the named backlog delivers the feature part.
- **Module**: a feature requirement owned by the named backlog. No TECH story delivers it, but TECH stories may support it.

### 3.1 NFR-CORE (13 P1)

| NFR | Short title | Coverage | US-TECH stories | Other owner / note |
|---|---|---|---|---|
| NFR-CORE-001 | Tenant isolation RLS | TECH | 010, 014, 015, 016, 019, 020, 021, 024, 064, 084 | Core tables come from Core migrations |
| NFR-CORE-002 | Scope check ≤ 20 ms | Shared | 061, 062, 083, 141 | Core: authorisation model and resolver |
| NFR-CORE-003 | Org as-of ≤ 1 s | Module | 134 (verification) | Core: org tree / closure queries |
| NFR-CORE-004 | Workflow throughput, no lost tasks | Shared | 034–037, 040, 142 | Core: workflow engine |
| NFR-CORE-005 | Notification latency (10k payslips ≤ 30 min) | Shared | 136, 144 | Core: notification centre; SaaS: e-mail adapter |
| NFR-CORE-006 | Login availability 99.9% | TECH | 012, 050, 056, 116, 121 | — |
| NFR-CORE-007 | Password hashing & encryption | TECH | 050, 052, 065, 067, 130 | — |
| NFR-CORE-008 | Audit write guarantee | Shared | 034, 077 | Core: audit service, hash chain, sealer |
| NFR-CORE-009 | Import/export throughput | Shared | 047, 136 | Core: import engine |
| NFR-CORE-010 | File limits & signed URLs | TECH | 075, 076 | Core document service calls them |
| NFR-CORE-011 | VI/EN, accent-insensitive search | Shared | 032, 091, 092 | Modules: labels and code-list translations |
| NFR-CORE-014 | ASVS L2, rate limiting | TECH | 022, 050, 054, 058, 060, 125 | — |
| NFR-CORE-015 | Idempotent effective-date jobs | Shared | 042, 045 | Modules: job handlers |

### 3.2 NFR-PPL (10 P1)

| NFR | Short title | Coverage | US-TECH stories | Other owner / note |
|---|---|---|---|---|
| NFR-PPL-001 | As-of / as-known | Shared | 005, 091 | People: entity models |
| NFR-PPL-002 | Transactional outbox | TECH | 007, 011, 034–037, 142 | — |
| NFR-PPL-003 | Profile/search performance | Module | 134 (verification) | People |
| NFR-PPL-005 | Field-level encryption | TECH | 030, 063, 067, 068, 072, 073, 080 | People: marks its P3 columns |
| NFR-PPL-006 | Sensitive read/write logging | Module | — (060 denial hook only) | People + Core audit client |
| NFR-PPL-007 | Opening employee data import | Module | 041 (initial-load mode) | People + Core import engine |
| NFR-PPL-008 | No cross-tenant matching | Shared | 019, 020, 068 | People: matching rules |
| NFR-PPL-009 | VI/EN, diacritics search | Shared | 032, 092 | People: name search |
| NFR-PPL-010 | PDF/A documents | Shared | 078, 144 | Core: document generation |
| NFR-PPL-011 | ESS ≥ 360 px, step-up for bank change | Shared | 055, 087, 088 | People: ESS screens |

### 3.3 NFR-ATT (7 P1)

| NFR | Short title | Coverage | US-TECH stories | Other owner / note |
|---|---|---|---|---|
| NFR-ATT-002 | Nightly reprocessing ≤ 30 min | Shared | 136, 149 | T&A: processing pipeline |
| NFR-ATT-003 | Period close ≤ 15 min | Shared | 047, 136 | T&A |
| NFR-ATT-005 | UTC storage, local work dates | Shared | 003, 004 | T&A: entity models |
| NFR-ATT-006 | Deterministic, versioned calculations | Module | 105 (version stamp) | T&A |
| NFR-ATT-007 | Retention of T&A records | Module | 029 (partitions) | T&A + SaaS retention (BR-SAAS-082 is P2) |
| NFR-ATT-008 | Sensitive data (no biometrics) | Module | 067 (encryption available) | T&A: Phase 1 stores no GPS/selfie/biometrics |
| NFR-ATT-010 | Tenant isolation incl. devices | TECH | 014, 019, 020, 021 | Device endpoints are P2 |

### 3.4 NFR-PAY (11 P1)

| NFR | Short title | Coverage | US-TECH stories | Other owner / note |
|---|---|---|---|---|
| NFR-PAY-001 | 10k employees ≤ 15 min | Shared | 043, 046, 047, 135, 137, 140 | Payroll: engine and partitioner |
| NFR-PAY-002 | Fixed-point arithmetic | Shared | 003, 004 | Payroll: engine |
| NFR-PAY-003 | Reproducible ≥ 10 years | Shared | 104 | Payroll: snapshots, `ReproduceRun` |
| NFR-PAY-004 | Confidentiality of pay data | TECH | 024, 026, 031, 036, 055, 060, 063, 066, 067, 083 | Payroll: masking views on `pay` tables; payslip PDF password (Payroll/Core) |
| NFR-PAY-005 | Tenant isolation | TECH | 014, 016, 019, 020, 021 | — |
| NFR-PAY-006 | Immutable payroll audit | Module | — | Payroll + Core audit |
| NFR-PAY-007 | Legal agility by configuration | Module | 103, 143 (support) | SaaS (LPS) + Payroll |
| NFR-PAY-008 | Golden test pack ≥ 200 scenarios | Shared | 103 (pipeline gate) | Payroll: scenarios with the consultant |
| NFR-PAY-009 | Payroll availability 99.5% | TECH | 102, 109, 116, 121, 138 | — |
| NFR-PAY-010 | File compatibility HTKK/eTax/VSS/banks | Module | — | SaaS file-export framework + Payroll |
| NFR-PAY-011 | VN/EN, VND formatting | Shared | 004, 090, 092 | Payroll: bank-file diacritics rule |

### 3.5 NFR-SAAS (17 P1)

| NFR | Short title | Coverage | US-TECH stories | Other owner / note |
|---|---|---|---|---|
| NFR-SAAS-001 | Availability 99.5% | TECH | 071, 100, 107, 109, 116, 117, 121 | — |
| NFR-SAAS-002 | Payroll performance | Shared | 115, 133, 135, 139, 140 | Payroll engine |
| NFR-SAAS-003 | Interactive performance | TECH | 006, 064, 133, 134, 139, 141 | Modules write efficient queries |
| NFR-SAAS-005 | Scalability & noisy-tenant cap | TECH | 017, 022, 043, 048, 109, 134, 137, 141 | — |
| NFR-SAAS-006 | Backup & DR in VN | TECH | 077, 110, 111, 112, 113, 146 | — |
| NFR-SAAS-007 | Encryption | TECH | 058, 065, 067, 069, 070, 074, 107, 130 | **Conflict:** the NFR text lists salary amounts as application-encrypted; X-01 overrides it (amounts not column-encrypted). The BA should update the NFR text |
| NFR-SAAS-008 | Secrets in vault | TECH | 033, 065, 066, 098, 115, 131 | — |
| NFR-SAAS-009 | ASVS L2, scanning, pen test | TECH | 058, 059, 098, 099, 122, 125–129, 131 | — |
| NFR-SAAS-010 | Observability & log retention | TECH | 031, 039, 072, 094, 118–120, 122 | — |
| NFR-SAAS-012 | Browser support, 360 px | TECH | 053, 088, 093 | App OS versions are P2 |
| NFR-SAAS-013 | Full localisation | Shared | 053, 078, 089, 092, 144 | Modules: their translations |
| NFR-SAAS-014 | Zero-downtime releases | TECH | 025, 027, 028, 044, 081, 097, 100, 102, 108 | — |
| NFR-SAAS-015 | Release governance & toggles | Shared | 100, 101 | Payroll/T&A: calc-behaviour versions per tenant (BR-SAAS-009 flags are P2) |
| NFR-SAAS-016 | Regulatory change SLA | Module | 103 (pipeline gate) | SaaS LPS + content process |
| NFR-SAAS-017 | Calculation traceability | Shared | 104, 105 | Payroll/T&A: stamps on results |
| NFR-SAAS-020 | Support SLA | Shared | 117, 120, 121, 124 | SaaS / support organisation: staffing and process |
| NFR-SAAS-021 | Peak capacity | TECH | 048, 138 | — |

**Summary.** TECH-owned: 22. Shared: 25. Module-owned: 11 (NFR-CORE-003, NFR-PPL-003, 006, 007, NFR-ATT-006, 007, 008, NFR-PAY-006, 007, 010, NFR-SAAS-016; NFR-PPL-006, NFR-PAY-006 and NFR-PAY-010 have no TECH story at all). Every P1 NFR has an owner.

### 3.6 P1 BR-SAAS delivered or co-delivered here

| BR | Stories | Note |
|---|---|---|
| BR-SAAS-021 tenant_id + RLS + app filter | 010, 014, 015, 016, 026 | Full |
| BR-SAAS-023 release-blocking isolation tests | 019, 020, 021 | Full |
| BR-SAAS-024 VN data residency | 074, 095, 096, 106, 107, 114, 118, 146, 147 | Infrastructure part; DPO inventory in SaaS |
| BR-SAAS-090 tenant-aware job scheduler | 042–047, 049 | Full; SaaS backlog should only reference it |
| BR-SAAS-038 / 039 / 043 password, MFA, sessions | 050, 052, 054, 057, 087, 148 | Technical part; any tenant-admin settings screens in SaaS |
| BR-SAAS-036 break-glass (P2) → manual P1 procedure | 033 | Manual variant only |
| BR-SAAS-087 security-level classification | 132 | Technical inputs only |
| BR-SAAS-003, 026, 032, 033, 034, 037, 045, 066, 075, 079, 081, 084 | 051, 092, 059, 023, 063, 131, 052, 114, 088, 030, 070, 122 | Supporting parts; the BR is owned by the SaaS backlog |

---

## 4. Totals

| Epic | SP | Stories |
|---|---|---|
| EP-TECH-01 Repository, build and module boundaries | 42 | 9 |
| EP-TECH-02 Walking skeleton (SP-0) | 18 | 4 |
| EP-TECH-03 Tenancy plumbing and isolation suites | 52 | 10 |
| EP-TECH-04 Database roles, Flyway pipeline, DDL hardening | 44 | 10 |
| EP-TECH-05 Outbox/inbox and DB-backed event bus | 35 | 8 |
| EP-TECH-06 Job queue runtime and workers | 39 | 8 |
| EP-TECH-07 Identity, BFF and edge gateway | 45 | 10 |
| EP-TECH-08 Authorisation plumbing and caching | 28 | 5 |
| EP-TECH-09 PII encryption and secrets | 48 | 9 |
| EP-TECH-10 Object storage and file safety | 21 | 5 |
| EP-TECH-11 Reporting-store feed (conditional) | 28 | 6 |
| EP-TECH-12 Frontend foundation | 54 | 11 |
| EP-TECH-13 CI/CD and supply chain | 41 | 10 |
| EP-TECH-14 Environments, IaC, GitOps, backup and DR | 65 | 12 |
| EP-TECH-15 Observability and operations | 33 | 7 |
| EP-TECH-16 Security hardening and pen-test support | 28 | 8 |
| EP-TECH-17 Performance engineering | 34 | 7 |
| EP-TECH-18 Spikes | 49 | 10 |
| **Total** | **704** | **149** |

**By wave:**

| Wave | SP | Meaning |
|---|---|---|
| S0-1 | 172 | Critical path, done by the end of sprint 1 |
| S2-3 | 319 | Sprints 2–3 |
| S4-6 | 166 | Sprints 4–6 |
| SIT | 32 | Weeks 13–16 |
| GA-def | 15 | Can follow the pilot, due before GA (US-TECH-018, 069, 073) |
| Conditional (already inside S2-3 and S4-6) | 33 | EP-TECH-11 + US-TECH-145; removed from Phase 1 if BR-SAAS-047 stays P2 |

**Capacity reading (guide §6).** At 7 SP per developer per sprint:
- 704 SP is about 100 developer-sprints, or about 17 developers for the 6 build sprints if all of it had to fit there.
- Removing the 15 GA-def SP and the 32 SIT SP leaves about 657 SP, which is still about 94 developer-sprints (about 16 developers over the 6 build sprints). A platform team of 4–5 engineers cannot carry this alone: module developers must build the shared kernel and frontend foundation in sprints 0–1, and the PM should either accept a larger platform team or challenge scope (start with R-1 and the GA-def items).
- **The S0-1 wave alone (172 SP) needs about 12 developers for two sprints.** It is the gate for every module backlog, so the PM should plan a sprint 0 in which the whole team builds the foundation.

### 4.1 Critical path (sprint 0/1)

Module stories in sprints 1–2 cannot start without these:
1. **Decisions and evidence:**
   - SP-0 walking skeleton (US-TECH-010–013).
   - SP-7 cloud evaluation (US-TECH-146–147). OD-1 must be decided by the end of sprint 1, because IaC, prod clusters, OpenBao, DR and the reporting feed all depend on the provider.
   - SP-8 Keycloak (US-TECH-148).
2. **Build and boundaries:** US-TECH-001–004, 006–009 (Gradle, Modulith, ArchUnit, shared kernel, API and event conventions, dev environment, test base).
3. **Data foundation:**
   - Test the existing DDL 000–003 and turn it into the Flyway baseline (US-TECH-024, 025).
   - DB roles as code (US-TECH-026).
   - `temporal-kit` (US-TECH-005), because People's first stories are effective-dated.
4. **Tenancy:** TenantContext and TenantAwareDataSource (US-TECH-014, 015).
5. **Plumbing every module calls:**
   - Outbox writer (US-TECH-034).
   - Job runtime (US-TECH-042).
   - Permission SPI (US-TECH-060).
   - Valkey (US-TECH-064).
6. **Identity:** Keycloak, BFF and JWT resource server (US-TECH-050, 054, 056).
7. **Frontend:** workspace, generated client, shells and registry, theme, i18n (US-TECH-085, 086, 088, 089, 092).
8. **CI:** VN runners and the MR pipeline (US-TECH-096, 097).

---

## 5. Risk notes

### 5.1 Stories marked [high-uncertainty]

| Story | SP | Why uncertain |
|---|---|---|
| US-TECH-146 SP-7a VN cloud evaluation | 8 | Provider access, quotes and feature claims (S3 replication between DCs, standby logical decoding, pgaudit, KMS/HSM) are unknown. **It gates US-TECH-106 (IaC, also uncertain), 107, 109, 111, 065 and 080.** |
| US-TECH-106 IaC foundation | 8 | Depends on the OD-1 provider and the maturity of its OpenTofu provider/API |
| US-TECH-141 SP-2a RLS performance | 8 | RLS + `set_config` + PgBouncer transaction mode + prepared statements at 200k employees is unproven (AR-1). A bad result changes the query, index and pool design across all modules |
| US-TECH-065 / 066 OpenBao and dynamic DB credentials | 8 + 8 | Unseal without a cloud KMS; Hikari and PgBouncer behaviour while credentials rotate; R2-15 forbids the easy fallback of static credentials |
| US-TECH-052 MFA TOTP + e-mail OTP + ACR step-up | 8 | E-mail OTP in Keycloak 26 may need a vetted extension; step-up ACR mapping per Organization is fiddly (SP-8 decides) |
| US-TECH-080 logical replication from a standby | 8 | Provider support for PG 16+ standby decoding and slot behaviour on failover (SP-6); conditional on BR-SAAS-047 |
| US-TECH-104 engine kernel artifact for 10-year recompute | 5 | Loading old kernels in an isolated classloader with frozen dependencies is new ground (AR-9) |
| US-TECH-018 platform-table replication | 5 | Replication vs release-job push not decided (DESIGN §3.1 spike); may be deferred if the pilot runs a single cluster |
| US-TECH-122 security-event pipeline | 5 | Wazuh vs Grafana-only SIEM not decided |
| US-TECH-129 pen-test remediation reserve, US-TECH-139 tuning reserve | 8 + 8 | Reserves: size depends on findings |

**Top 5 by impact:**
1. SP-7 / OD-1 provider choice (US-TECH-146/147 → 106, 107, 109, 111).
2. SP-2a RLS performance (US-TECH-141).
3. OpenBao + dynamic credentials (US-TECH-065/066).
4. Keycloak e-mail OTP and step-up (US-TECH-052).
5. Logical replication from a standby on the chosen provider (US-TECH-080/145).

### 5.2 Other notes for the PM

- **R-1 Reporting store scope conflict.** BR-SAAS-047 (separate reporting store) is P2 in mvp-scope §9.6, while R3-11 accepted the logical-replication feed for Phase 1. EP-TECH-11 + US-TECH-145 (33 SP) are conditional. Recommendation: keep them in P1 only if any P1 report needs cross-module joins; otherwise serve pilot reports from a read replica (US-TECH-017) and move the 33 SP to P2.
- **R-2 Ownership gaps against estimation guide §2.** The guide puts "Legal Parameter Service runtime, formula engine" in this file. They are not estimated here: they belong to the SaaS (LPS) and Payroll (YFL) backlogs. The PM must check that both backlogs contain them.
- **R-3 Double counting.** Infrastructure SP here overlap the DevOps share of the +40% overhead (see §0). The SaaS backlog must reference, not re-estimate, BR-SAAS-021/023/090 and the technical parts of 038/039/043.
- **R-4 Data architecture still to come.** The `att`, `pay` and `audit` DDL is not written yet, so US-TECH-024 covers only 000–003. The isolation suite (US-TECH-019) re-runs automatically as each module adds migrations.
- **R-5 NFR-SAAS-007 text is out of date.** It still lists salary amounts among the encrypted fields, contradicting X-01. This backlog follows X-01.
