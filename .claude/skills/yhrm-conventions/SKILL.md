---
name: yhrm-conventions
description: Cross-cutting YourHRM architecture and data conventions shared by every module (multi-tenancy + RLS, schema-per-module, no cross-schema FK, UUIDv7, effective dating, PII classes and encryption, outbox/inbox events, audit chain, legal parameter packs, RBAC permissions, money/time rules). Use whenever writing or reviewing backend code, DDL, events or APIs in any YourHRM module, together with the module skill (yhrm-core, yhrm-people, yhrm-time-attendance, yhrm-payroll, yhrm-recruitment, yhrm-saas-platform).
---

# YourHRM shared conventions

YourHRM is a multi-tenant HRM SaaS for Vietnam. It is a **modular monolith**: Spring Boot 4 + Spring Modulith 2, Java 25, PostgreSQL 17, Gradle Kotlin DSL, and package root `vn.yourhrm`. These rules apply to every module. The module skills add domain rules on top.

## Source of truth (read before deciding)
| Topic | File |
|---|---|
| Decisions (R1–R5, D1–D11) | `docs/00-project-management/decision-log.md` |
| Business requirements | `docs/01-requirements/brd/BRD.md`, `brd/modules/0N-*.md` |
| Phase of each BR (P1/P2) | `docs/01-requirements/brd/mvp-scope.md` §9 (BRD MoSCoW is *business priority*, not phase) |
| Gaps and single-owner table | `docs/01-requirements/brd/gap-analysis.md` §5 |
| Architecture | `docs/02-architecture/architecture-overview.md`, `adr/0001..0037` |
| Database | `database/postgresql/DESIGN.md`, `ddl/000..007`, `ddl/reporting/` |
| Backlog | `docs/01-requirements/user-stories/*-backlog.md` |

If the docs disagree, follow this order: decision-log, then ADR, then DESIGN.md/DDL, then BRD. Report the conflict; don't silently pick one.

## Module boundaries (ADR-0001, 0022)
- Modules and their schemas:
  - `saas` → `saas`
  - `core` → `core`, `audit`
  - `people` → `hr`
  - `time-attendance` → `att`
  - `payroll` → `pay`
  - `recruitment` → `rec`
  - Shared helpers live in `sys`.
- Each module is `vn.yourhrm.<module>.api` (public) plus `internal.{domain,application,infra,web}`. Other modules may use **only** `api`. Spring Modulith and ArchUnit tests enforce this.
- Each module connects as its own DB role `yhrm_app_<schema>`, which only has grants on its own schema.
- **No cross-schema foreign keys.** Refer to another module's data by soft reference (id plus a snapshot of its key fields), or keep a local projection table `ext_<owner>_<entity>` fed by events. The X-02 read-only exception is guarded by `sys.cross_schema_grant_violations()`.
- Each fact has **one owner module** (gap-analysis §5 single-owner table). Never write another module's data; consume its events or API.

## Multi-tenancy (ADR-0003, 0020, 0021)
- Shared pool, `tenant_id` on every tenant table, and row-level security `ENABLE` + `FORCE`.
- Primary key is `(tenant_id, id)`, with `id` a UUIDv7 (ADR-0023).
- The first statement of every transaction, read-only ones included, is `set_config('app.tenant_id'|'app.user_id'|'app.request_id'|'app.purpose'|'app.mask_level', …, true)`. In the backend, `TenantAwareJpaTransactionManager` does this.
- The tenant comes **only** from the validated JWT (`tid`/`tenant_id` claim), never from headers or the request body. `TenantContext` is a `ScopedValue`.
- With no tenant context, a query returns 0 rows. It fails closed.

## Time and history (ADR-0024, 0034)
- **Pattern A:** `valid_from`/`valid_to`, with an exclusion constraint so periods don't overlap. Use it for master data that is effective-dated.
- **Pattern B:** bi-temporal, with `recorded_at`/`superseded_at` and `sys.tg_bitemporal_guard`. Use it where "what did we know when" matters, such as pay inputs and tax residency.
- Money is `numeric(19,4)` plus a currency code, VND by default, with explicit rounding rules. Never use float.
- Store timestamps as `timestamptz` in UTC. Business dates are `date` in `Asia/Ho_Chi_Minh`.

## PII and security (ADR-0026, 0006)
- Classes:
  - **P0:** public.
  - **P1:** internal.
  - **P2:** confidential. Includes salary amounts, which stay plaintext under X-01 and are protected by fail-closed masking views.
  - **P3:** sensitive, such as national ID, tax code, bank account and health data. P3 uses envelope encryption with columns `_enc` (ciphertext), `_bidx` (blind index for search) and `_last4`, and a DEK per data subject so data can be crypto-shredded.
- Every column carries a `pii:Px` comment. P3 is never logged, never published to reporting, and never put in event payloads (D7: minimise payloads).
- Every endpoint has `@RequiresPermission("<prefix>.<resource>.<action>")` or `@PublicEndpoint`; the build fails otherwise.
  - Prefixes are `core`, `ppl`, `att`, `pay`, `rec` and `saas` (architecture-overview), e.g. `core.role.create`, `ppl.employee.read`, `pay.run.approve`. Check data scope with `AuthorizationService.scopeFor(...)`.
  - Scope types are TENANT, LEGAL_ENTITY, ORG_UNIT (with subtree), LOCATION, SELF, DIRECT_REPORTS and ALL_REPORTS.
  - Each module declares its permissions in a `<Module>Permissions` class in `api`.

## Events and audit (ADR-0004, 0019, 0027, 0028)
- Write events to an outbox table in your own schema, in the same transaction as the change. Consumers dedupe through their inbox.
  - Phase 1 runs a DB-backed bus on `saas.job`; Kafka comes later.
  - Name events `<module>.<Event>` in past tense, e.g. `people.EmploymentStarted`.
- Long cross-module flows are sagas (ADR-0019). Attendance → payroll uses the **close-hold** protocol: `att.attendance_result_hold` is the authority and `pay.run_attendance_version` mirrors it.
- Security-relevant changes write to `audit.audit_event`, a hash chain that is never purged.

## VN legal values (ADR-0010, 0025)
- **Never hard-code** a rate, cap, tax bracket, deduction, minimum wage, OT multiplier or form code. Read it from the SaaS Legal Parameter Service: `getParam(key, scope, asOfDate)`, with override scopes TENANT, LEGAL_ENTITY and LOCATION, and overrides only where the law allows.
- Values marked `[verify-legal]` in the BRD are examples only. They need confirmation from the VN legal/tax consultant.

## Before finishing a change
1. Run `backend/gradlew test` from `backend/`. Integration tests use Testcontainers PostgreSQL 17 with the real DDL.
2. Run `database/postgresql/tests/pg17_integration_check.sql` if you changed DDL.
3. If you changed a requirement, entity or event, update the BRD/DESIGN/decision-log reference too.
