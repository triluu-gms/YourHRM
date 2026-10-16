# ADR-0010: Legal Parameter Service — API and caching

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** Architecture brief B6; gap-analysis GAP-001, GAP-023, GAP-040, §9 A-6; NFR-SAAS-016/017, NFR-PAY-007; tables and versioning in the data architecture (DESIGN.md); [architecture-overview](../architecture-overview.md) §9.1

## Context

The SaaS platform owns one Legal Parameter Service (LPS) for every statutory value: minimum wages and the admin-unit→region map, SI/HI/UI/OA-OD rates and caps, reference level, PIT brackets and deductions, OT caps, night window, leave entitlements, probation/notice limits, retirement roadmap, settlement deadline, holiday template. Payroll, T&A, People, Core and Recruitment read it synchronously by effective date, with tenant/entity overrides only where the law permits. Every result stamps the pack version. Packs must be publishable without code release or schema change.

## Decision

1. **Typed keys:** `SCALAR`, `TABLE` (e.g. brackets), `MAP` (e.g. admin unit → region), `CALENDAR_TEMPLATE`; each key declares type, unit, override permission and owner.
2. **Packs are immutable once published.** Lifecycle `DRAFT → IN_TEST → APPROVED → PUBLISHED → SUPERSEDED`; publication requires the golden payroll pack to pass and consultant approval; corrections create a new version.
3. **In-process API** (`saas-platform.api.LegalParameters`): `get(key, scope, asOf)`, `resolveSet(keys, scope, periodStart, periodEnd)` (segments for mid-period changes), `currentPack(asOf)`, `changesSince(version)`; REST mirror for consoles. Every answer carries `packVersion` and `overrideVersion`.
4. **Scope resolution:** GLOBAL → TENANT → LEGAL_ENTITY → REGION/LOCATION, only for keys flagged overridable; overrides are versioned and audited.
5. **Caching:** packs cached by version indefinitely (Caffeine); the "date → version" index and tenant overrides are invalidated on `saas.LegalParameterPackPublished` / override events via Valkey pub/sub to all pods.
6. **Retro trigger:** publication with a past effective date lists affected periods; Payroll/T&A create retro work items for tenants with approved periods in range.
7. Consumers never copy parameters into their own tables, except payroll/T&A snapshots that record resolved values with versions.

## Alternatives considered

- **Parameters stored in each module:** the five-homes problem of GAP-001; rejected.
- **Separate LPS microservice over HTTP:** unnecessary latency inside the monolith; the API is designed so it can be extracted.
- **Config server / feature-flag tool for legal values:** lacks effective dating, tables and audit.

## Consequences

- + One truth, effective-dated, auditable; sub-millisecond reads.
- + Payroll reproducibility through stamps and snapshot copies.
- − Content-owner tooling (editor, diff, test run, approval) must be built in the operator console.
- − Override governance requires legal input per key (`[verify-legal]`).
