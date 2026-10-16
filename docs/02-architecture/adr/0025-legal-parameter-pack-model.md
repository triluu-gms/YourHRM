# ADR-0025: Legal parameter pack data model and version stamping

- **Status:** Proposed
- **Date:** 2026-10-16
- **Deciders:** Data architect, Solution architect (ADR-0010 owns API and caching), Regulatory content owner
- **Related:** Architecture brief B6; gap-analysis §5 #1, §9 A-6; NFR-PAY-003/007, NFR-SAAS-016/017; ADR-0010; [DESIGN.md](../../../database/postgresql/DESIGN.md) §6; DDL `001_saas.sql` (`saas.legal_param_*`)

## Context

Every statutory value (minimum wage and the admin-unit → region map, SI/HI/UI rates and caps, PIT brackets and deductions, OT caps, leave entitlements, retirement roadmap …) must be effective-dated, versioned, overridable by tenants only where the law allows, stamped on every result, and reproducible for 10 years. New law can be retroactive. A new PIT structure must not need a schema change (NFR-PAY-007). The Payroll BRD placed these tables in `pay`; the gap analysis moved ownership to SaaS.

## Decision

1. Tables live in `saas` and are **pure global** (no `tenant_id`), replicated read-only to every cluster (ADR-0036):
   * `legal_param_definition` holds the typed key: `value_type` ∈ `SCALAR/TABLE/MAP/CALENDAR_TEMPLATE` (ADR-0010), `scalar_type`, unit, JSON schema, `override_rule` ∈ `NONE/ANY/FLOOR/CAP`, and `override_scope`.
   * `legal_param_pack` has a monotonic `pack_version` and lifecycle `DRAFT → IN_TEST → APPROVED → PUBLISHED → SUPERSEDED` (+ `WITHDRAWN`), with `content_sha256`.
   * `legal_param_value` (pack, key, `[valid_from, valid_to)`, `value_num` | `value_text` | `value_json`, legal reference) has an exclusion constraint per pack and key.
   * `legal_param_table_row` holds large maps.
2. **A pack is a cumulative, immutable snapshot**: it contains every key with its full effective history. A trigger forbids any content change once the pack leaves `DRAFT/IN_TEST`. Lookup = `(pack_id, key, valid_during @> date)`.
3. **Tenant overrides** live in `legal_param_override_set` (tenant, monotonic `override_version`, workflow approval) and `legal_param_override` (optionally per legal entity or location). `FLOOR`/`CAP` are validated against the statutory value for the same dates, at activation and again on every new pack publication.
4. **Stamping:** every attendance result version, payroll run and snapshot, offer simulation and eligibility calculation stores `legal_pack_id` + `override_version`. Payroll runs also freeze the **resolved values** in the run config snapshot (ADR-0029).
5. **Retroactive law:** a later pack may change past-dated values. Approved results keep their stamp. The publication event lists changed keys and dates, so Payroll and T&A can raise retro work items (ADR-0010 §6).

## Alternatives considered

| Option | Why not |
|---|---|
| Delta packs (each pack holds only changes) | Resolution needs replaying a chain; reproduction depends on every earlier pack being intact; harder to diff and test. Full snapshots are small (hundreds of keys). |
| One generic EAV row per value without packs | No atomic publication unit, no consultant sign-off unit, no clear stamp. |
| Typed tables per parameter family (`pit_bracket`, `si_rate` …) | Every new legal structure needs a schema change and release (violates NFR-PAY-007). |
| Tenant copies of the pack | Overrides drift silently from law; no single statutory source. |

## Consequences

- \+ One stamp (pack + override version) fully determines the statutory inputs. Old packs stay queryable for 10 years.
- \+ JSON-schema-validated structured values absorb new legal shapes without DDL.
- − Typing of JSON values is enforced by the application (JSON schema), not by PostgreSQL types.
- − FLOOR/CAP checks need comparable semantics per key; keys with structured values default to `NONE` until a comparator is defined.
