# ADR-0026: PII classification, application-level envelope encryption and crypto-shredding

- **Status:** Accepted (2026-10-16, X-01)
- **Date:** 2026-10-16 (amended for X-01)
- **Deciders:** Data architect, Security lead, DPO, Solution architect; PM arbitration X-01 (sponsor-approved)
- **Related:** gap-analysis §9 A-9; NFR-SAAS-007, NFR-PPL-005, NFR-PAY-004, NFR-REC-004/009, NFR-ATT-008; PDPL 2025 / Decree 13/2023 `[verify-legal]`; ADR-0017 (OpenBao transit); [DESIGN.md](../../../database/postgresql/DESIGN.md) §9.3–9.4; DDL `saas.tenant_key`, `saas.subject_key`, `*_enc/*_bidx/*_last4` columns

## Context

The NFRs require field-level encryption with per-tenant keys for CCCD/passport numbers, personal tax codes, bank accounts, health data, and "salary amounts where feasible". The SA and DA drafts disagreed on salary amounts; the PM arbitrated (X-01): amounts are not column-encrypted. DSR erasure, the 12-month candidate retention and tenant purge must delete data verifiably, including from replicas, the reporting store, exports and backups, which cannot be edited.

## Decision

1. **Classify every column** as P0–P3 with a `COMMENT 'pii:Px'`. A CI check rejects unclassified columns and feeds the SaaS PD inventory.
2. **P3** (identity numbers, tax code, SI number, bank account numbers, religion, ethnicity, health/disability, GPS, selfie references, dependants' ID and tax codes, candidate ID documents) is **encrypted by the application** with AES-256-GCM into `<col>_enc bytea`. Equality search uses a blind index `<col>_bidx` (HMAC-SHA256 with a tenant key derived via HKDF, truncated to 16 bytes); display uses `<col>_last4`.
3. **Key hierarchy:** tenant KEK in OpenBao transit (in VN; software root of trust for the MVP, HSM-backed root before the Enterprise tier, OD-10) → **per-data-subject DEK** (`saas.subject_key.wrapped_dek`; person, candidate, casual payee, family member) → columns. The ciphertext header carries the key id and version.
4. **Crypto-shredding:**
   * subject level: `wrapped_dek := NULL` (DSR erasure, retention expiry);
   * tenant level: destroy the tenant KEK in OpenBao.

   Plaintext P2 data in backups expires with backup retention (35 days), which is documented to the DPO.
5. **P2** (salary amounts, DOB, addresses, contact data) stays plaintext in the DB, protected by RLS, module grants, storage encryption, masking views (`security_invoker`, `app.mask_level`), API field masking (ADR-0006) and audit of sensitive reads. **Salary amounts are not column-encrypted (decided X-01, 2026-10-16).** This covers the salary record and its allowances, pay-profile projections, payroll inputs, result lines, payslip amounts, YTD balances, SI/PIT amounts, bank payment lines and GL journals. NFR-PAY-004 "encrypt salary where feasible" is answered as *not feasible* and replaced by the compensating controls in point 8.
6. `pgcrypto` is used only for hashing (`digest`) and random bytes, never for encrypting business data.
7. The keyring lives in the platform cluster (ADR-0036), so pool-cluster backups never contain wrapped DEKs.
8. **Compensating controls for salary amounts (X-01), all mandatory:**
   * RLS on every table holding amounts (tenant isolation, fail closed);
   * module DB roles: only `yhrm_app_hr` (salary record) and `yhrm_app_pay` (payroll data) have grants; no other module role can read them (X-02 exception list does not include any amount table);
   * masking views (`hr.v_salary_record_masked`, payroll equivalents) that hide amounts unless `app.mask_level` is `NONE` or `PII`; an unset level hides amounts (fail closed);
   * API field masking by the user's field profile (`@Sensitive(category=SALARY)`, ADR-0006) and step-up for bulk salary views and exports;
   * audit of every sensitive read (`is_sensitive_read`) and every export containing amounts;
   * events carry amounts only on ACL-restricted Payroll topics (`people.SalaryChanged`);
   * reporting store: amounts only through the reporting service with pay permission and per-user scope (ADR-0032).
9. **Identifiers, bank accounts and special-category data remain P3** and encrypted as in point 2: CCCD/passport/CMND numbers, personal and dependants' tax codes, SI numbers, bank account numbers, religion, ethnicity, health/disability, and (Phase 2) GPS coordinates and selfie references.

## Alternatives considered

| Option | Why not |
|---|---|
| `pgcrypto` `pgp_sym_encrypt` in SQL | Keys travel in SQL text and appear in logs/`pg_stat_statements`; the DB process sees plaintext; no clean per-tenant key handling. |
| Only TDE / volume encryption | Protects against disk theft, not against DB-level access, replicas or exports. Gives no crypto-shredding. |
| Per-tenant DEK only (no subject level) | Single-subject erasure would need re-encryption or row deletion everywhere, including copies already exported to the reporting store. |
| Encrypt salary/result amounts too | Breaks SQL aggregation (GL, reports, cost allocation, reporting store), slows payroll for 10k employees, and moves all salary analytics into application memory. Rejected by X-01 (2026-10-16); compensating controls in point 8 instead. |

## Consequences

- \+ Erasure is verifiable and instant across every copy that holds ciphertext.
- \+ Database administrators and backups never see P3 plaintext.
- − No range, partial or fuzzy search on P3 fields (equality only).
- − OpenBao availability is on the critical path. The tenant KEK is cached in memory for ≤ 1 h, and a degraded mode masks P3.
- − Key rotation and lazy re-encryption jobs must be operated (≤ 90 days to complete).
- − Benchmark 10k DEK unwraps per payroll run **[spike]**. The payroll run needs only bank accounts (bank file) and tax codes (declarations), not amounts, so the load is bounded.
- − Salary amounts are readable by anyone holding the `hr`/`pay` module DB credentials or break-glass access; this is accepted (X-01) and mitigated by dynamic credentials, pgaudit on break-glass, and the masking/audit controls above. DPO to record this in the DPIA.
- − Root of trust: software root (OpenBao) for the MVP, HSM-backed root before the Enterprise tier (OD-10).
