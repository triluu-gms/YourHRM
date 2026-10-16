# YourHRM — Project Charter (Draft v0.1, 2026-10-16)

## 1. Vision
A multi-tenant HRM SaaS for Vietnamese organisations (SMEs to enterprises, ~20–10,000 employees per tenant) that gets Vietnam-specific payroll, overtime, social insurance and PIT right by default, while staying configurable enough for each customer's policies.

## 2. Fixed constraints (from README)
- Database: PostgreSQL. One schema per module: `core`, `hr`, `att`, `pay`, `rec`, plus `saas` for platform data (tenants, subscriptions, legal parameter packs, global master data) — *amendment approved 2026-10-16 (GAP-014, X-07)*.
- Backend: Java Spring Boot, one module per domain (`backend/src/modules/<module>`), shared `auth`, `audit`, `workflow`, `common`.
- Reporting system + modern, professional web frontend.
- Multi-tenant from day one (SaaS).
- No legacy data migration (decision D-09, 2026-10-16): YourHRM is built as a new system. Customers load opening balances (employees, YTD payroll/PIT/SI, leave balances) through Excel import templates.

## 3. Team & responsibilities
| Role | Deliverable |
|---|---|
| PM | Charter, consolidated BRD, gap register, roadmap, RAID |
| BA – Core | `docs/01-requirements/brd/modules/01-core.md` |
| BA – People | `docs/01-requirements/brd/modules/02-people.md` |
| BA – Time & Attendance | `docs/01-requirements/brd/modules/03-time-attendance.md` |
| BA – Payroll | `docs/01-requirements/brd/modules/04-payroll.md` |
| BA – Recruitment | `docs/01-requirements/brd/modules/05-recruitment.md` |
| BA – SaaS Platform & cross-cutting | `docs/01-requirements/brd/modules/06-saas-platform.md` |
| Integration analyst | `docs/01-requirements/brd/gap-analysis.md` |
| Solution architect | `docs/02-architecture/architecture-overview.md`, ADRs |
| Data architect | `database/postgresql/DESIGN.md`, data ADRs |

## 4. Documentation conventions
- Requirement IDs: `BR-<MOD>-NNN` (business), `NFR-<MOD>-NNN` (non-functional). MOD ∈ `CORE, PPL, ATT, PAY, REC, SAAS`.
- Priority: MoSCoW. `M` = needed for MVP / first paying tenant.
- Every module BRD has the same sections (see §5) so gaps can be traced mechanically.
- Legal values (rates, caps, minimum wages, tax brackets, deductions) are **never hard-coded**: they are effective-dated parameters. Any value quoted in a BRD is marked `[verify-legal]` until checked by a Vietnam labour/tax consultant.
- Language of docs: English; UI terms also given in Vietnamese where it helps.

## 5. Module BRD template
1. Purpose & scope (in / out)
2. Actors & personas
3. Business processes (numbered, happy path + key exceptions)
4. Business requirements (table: ID, requirement, priority, notes)
5. Vietnam compliance rules (with legal basis)
6. Key business entities (name, purpose, key attributes)
7. Cross-module interfaces — **Consumes** (what / from which module / when) and **Provides** (what / to which module / when)
8. Reports & analytics
9. Non-functional requirements specific to the module
10. Assumptions, open questions, risks

## 6. Phasing (proposed)
- **Phase 0** — BRD, gap analysis, architecture draft (this sprint).
- **MVP (Phase 1)** — core features only of Core + People + T&A (basic) + Payroll (monthly VN payroll, SI/HI/UI, PIT); web admin + responsive web ESS; tenant onboarding; opening-balance import. No mobile features (X-04, OD-9).
- **Phase 2** — Recruitment, appraisal, training, expenses, advanced rostering, all mobile features (native app, mobile/GPS/selfie check-in), report designer, white-label, BI (Superset).
- **Phase 3** — Marketplace integrations, AI assistants, regional expansion.
