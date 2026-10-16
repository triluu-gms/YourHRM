# Architecture Brief from PM (2026-10-16)

Inputs: [BRD](../01-requirements/brd/BRD.md), [module BRDs](../01-requirements/brd/modules/), [gap analysis](../01-requirements/brd/gap-analysis.md) (§5 single-owner table, §6 event catalogue, §7 period-close sequence, §9 architecture inputs). Sponsor decisions: [decision-log](../00-project-management/decision-log.md).

## Baseline decisions (PM-fixed; architects may challenge via ADR, but design against them)
1. **Ownership:** the single-owner table in gap-analysis §5 is accepted as the working baseline. Notable: Legal Parameter Service in the platform; SaaS authenticates, Core authorises; T&A owns calendars, business trips, schedule assignment; Payroll owns pay scales/bands, pay groups, SI contribution history; People owns the salary record (incl. GROSS/NET flag) and dependants.
2. **Schemas:** add a `saas` schema for platform data (tenants, plans, subscriptions, invoices, legal parameter packs, global master data). Final set: `saas`, `core`, `hr`, `att`, `pay`, `rec` (+ reporting store, separate).
3. **Deployment shape:** start as a **modular monolith** (Spring Boot, one Gradle/Maven module per domain, enforced boundaries). Modules talk only through published APIs and domain events, never through each other's tables. Payroll calculation workers and punch ingestion must be able to scale out separately.
4. **Tenancy:** a shared pool by default (`tenant_id` on every tenant-owned row + PostgreSQL RLS + an application-level guard). An Enterprise tier gets a dedicated database with the same schema, chosen through tenant routing. All personal data hosted in Vietnam.
5. **Events:** a transactional outbox per module, a canonical event envelope, and gap-analysis §6 as catalogue v1.
6. **Legal values:** effective-dated and versioned; every payroll result stamps the parameter version it used, and results stay reproducible for 10 years.
7. **MVP (Phase 1):** core features only of Core, People, T&A, Payroll, plus platform tenancy/identity/legal params, web admin and responsive web ESS, and the opening-balance import. No Oracle migration (D-09); no mobile features (X-04, OD-9). Recruitment is P2.

## Split of responsibilities
| Topic | Solution architect | Data architect |
|---|---|---|
| Context/container/component views, module map | ✔ | |
| Eventing tech (broker), outbox relay, sagas (period close, leaver, hire) | ✔ | outbox/inbox tables |
| Identity, authN/authZ, data-scope enforcement | ✔ | RLS policies, scope-friendly keys |
| Payroll engine (formula sandbox, run orchestration, parallelism) | ✔ | snapshot/result storage |
| Reporting & analytics | tech choice, pipeline | reporting data model |
| Frontend, ESS/mobile, API gateway, integrations hub | ✔ | |
| Infra, environments, CI/CD, observability, DR | ✔ | backup/PITR, partitioning |
| Tenancy implementation, keys, effective dating, audit, PII encryption, retention/crypto-shredding | consumes | ✔ |
| Legal Parameter Service | API & caching | tables & versioning |
| Opening-balance import (replaces Oracle migration, D-09) | import pipeline & templates | target tables, validation & reconciliation checks |

ADR numbering: solution architect 0001–0019, data architect 0020–0039. Format: `docs/02-architecture/adr/NNNN-kebab-title.md` (Context, Decision, Alternatives, Consequences, Status: Proposed).
