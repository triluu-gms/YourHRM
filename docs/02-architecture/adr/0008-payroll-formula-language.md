# ADR-0008: Payroll formula language (YFL) on ANTLR and BigDecimal

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** Payroll BRD §4.1 (component catalogue, formulas), NFR-PAY-002/003/007/008; [architecture-overview](../architecture-overview.md) §8.3

## Context

C&B users configure salary components with formulas referencing other elements, attendance quantities, People data, legal parameters and YTD values. Formulas run inside a multi-tenant JVM, so they must be sandboxed. Results must be exact to the VND (fixed-point decimal), deterministic, and reproducible for 10 years. 10,000 employees × ~60 elements must calculate well within 15 minutes. The same kind of expressions are needed for workflow conditions and eligibility rules.

## Decision

Define the **YourHRM Formula Language (YFL)**, an Excel-like expression language implemented in `backend/src/shared/expression`:

- Grammar in **ANTLR 4**; parse → typed AST → type check → dependency extraction → compiled evaluator tree (closures), cached by configuration version.
- Types: Decimal (`BigDecimal`, `MathContext` 34 digits), Integer, Boolean, Date, Text, Table. No floating point anywhere.
- Whitelisted pure functions only (`IF`, `AND`, `MIN`, `MAX`, `ROUND(x,n,mode)`, `BRACKET`, `PRORATE`, `PARAM`, `INPUT`, `ELEMENT`, `YTD`, date functions…); no loops, no recursion, no I/O, no reflection, node/depth budget.
- Elements of a pay group form a DAG; cycles are rejected at configuration time.
- Domain-specific function sets are registered per host: payroll, workflow conditions, eligibility rules.
- New functions are added by the vendor in releases (with golden tests), never by tenants.

## Alternatives considered

- **SpEL / MVEL / OGNL:** powerful but expose Java object graphs; sandboxing is hard and historically leaky; decimal semantics awkward.
- **Groovy (sandboxed) / GraalJS / Lua:** general-purpose languages; sandbox escapes and resource control are hard; JS numbers are floating point.
- **Google CEL (cel-java):** safe and fast, but no native decimal type and not Excel-like for C&B users.
- **Exp4j/EvalEx:** EvalEx supports `BigDecimal` but lacks typed tables, dependency graphs and extension structure; could serve as a quick start but would be outgrown.
- **Drools/DMN:** heavyweight; DMN FEEL has decimal semantics but is unfamiliar to C&B users.

## Consequences

- + Safe by construction, deterministic, readable by C&B users, reusable across modules.
- + Fast: expected < 50 µs per element evaluation (validated in SP-1).
- − We own a language: grammar, error messages (vi/en), editor support (autocomplete, validation) in the frontend.
- − Pilot formulas must be translated; SP-4 measures coverage (target ≥ 95% without new functions).
