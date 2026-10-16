# ADR-0027: Tamper-evident audit log with asynchronous hash-chain sealing

- **Status:** Proposed
- **Date:** 2026-10-16
- **Deciders:** Data architect, Security lead, Solution architect
- **Related:** gap-analysis §5 #13; NFR-CORE-008, NFR-PPL-006, NFR-PAY-006, BR-SAAS-037; SA overview §audit; [DESIGN.md](../../../database/postgresql/DESIGN.md) §9.2; DDL `002_core.sql` (`audit.*`)

## Context

Audit writes must be part of the business transaction (or guaranteed through an outbox), be tamper-evident, cover sensitive reads, and be queryable (one record over one year in ≤ 3 s). Retention runs to 10 years for payroll-related actions. A naive per-tenant hash chain computed inside each business transaction would serialise all writes of a tenant on the chain head, which is unacceptable at payroll and check-in peaks.

## Decision

1. `audit.audit_event`: a separate `audit` schema (owned by Core), monthly range partitions, PK `(tenant_id, id, occurred_at)`, RLS. It holds the actor, on-behalf-of, operator, support grant, action, object, purpose, request/correlation ids, IP and a **masked diff** (`*_enc`/`*_bidx` appear only as `"<encrypted:changed>"`).
2. **Writes** happen in the business transaction: explicitly by services for business actions and sensitive reads, and by the generic trigger `audit.tg_row_audit` on configuration and HR master tables. The table in the same database acts as its own outbox. Bulk loads (opening-balance import, ADR-0037) can switch to per-batch summary events (`app.audit_mode = 'BATCH'`, actor `IMPORT`).
3. **Sealing is asynchronous.** `audit.seal_batch(tenant)` (role `yhrm_audit_sealer`, every few seconds) locks `audit.chain_head`, takes unsealed rows older than 2 s in id order, and writes `chain_seq`, `prev_hash` and `row_hash = sha256(prev_hash ‖ canonical(row))`. The chain order is the seal order.
4. **Anchoring:** the daily chain head per tenant goes to `audit.chain_anchor` and to WORM object storage in DC2. `audit.verify_chain()` detects modification, deletion (sequence gap) and re-ordering.
5. **Immutability:** app roles have `INSERT` only; the sealer can update seal columns only (column grant plus guard trigger); nobody has `DELETE`. Partitions are detached and archived to WORM Parquet after 24 months; payroll-related categories are kept 10 years.

## Alternatives considered

| Option | Why not |
|---|---|
| Synchronous chain per transaction | Serialises writes per tenant; payroll and bulk HR changes would queue on one row lock. |
| Hash chain per row without a sequence | Cannot detect deletion of the newest rows or re-ordering. |
| External immutable ledger (e.g. QLDB-like service) | Not available in VN-hosted providers; adds a dependency on the write path. |
| Audit only in application logs | Not queryable per record, not transactional, and log retention differs. |

## Consequences

- \+ No write serialisation. Tampering inside the DB is detectable, and tampering with the anchors needs WORM storage access too.
- − A window of a few seconds holds unsealed rows (tamper evidence starts at sealing). This is acceptable and monitored by an unsealed-age alert.
- − The DDL smoke test confirmed that sealing works and that a modified row is detected at its sequence number.
- − Audit volume is large (~40 events per employee per month), so partitions, archival and the query index must be operated.
