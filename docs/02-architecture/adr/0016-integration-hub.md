# ADR-0016: Integration hub and connector pattern — Phase 1 = file-export framework only

- **Status:** Proposed (rev. 2026-10-16, decision log R2-01: no live external integrations in Phase 1; the hub runtime moves to Phase 2)
- **Date:** 2026-10-16
- **Related:** SaaS BRD §4.F; gap-analysis GAP-008, GAP-009, GAP-052, GAP-058; decision log R2-01, R2-19, §7.2; NFR-PAY-010, NFR-REC-007, NFR-ATT-001/004; BRD RK7; [architecture-overview](../architecture-overview.md) §7.5; ADR-0004 rev. 2, ADR-0012 (statutory template framework), ADR-0015

## Context

YourHRM will integrate with banks, eTax/T-VAN, VSS/I-VAN, MISA/Fast/SAP, e-signature CAs, VNeID, time devices, Zalo ZNS/SMS/push, job boards, calendars and CV parsers. Formats and APIs change often (RK7); credentials are per tenant; failures must be retried without loss and be visible.

Decision R2-01 (2026-10-16, confirmed by R2-19 and the PM interpretation in decision log §7.2) removes **all live external integrations from Phase 1**. Phase 1 keeps only:

- **E-mail** (login, MFA, former-employee OTP, notifications, payslip PDF) and **in-app notifications** — through the SaaS channel adapter, not the hub;
- **File exports that the customer downloads and uploads** to the bank, VSS or tax portals: bank payment file, VSS insurance declaration file (D02-LT and related), PIT XML for eTax (05/KK, 05/QTT), GL journal in Excel;
- **Excel / CSV imports**, including the **time-device software export file** (punch template), through the Core import engine.

## Decision

1. **Phase 1 — file-export framework only (in `saas-platform/integration`):**
   - A **format-template registry**: bank layouts (CSV/XLSX/XML per pilot bank, no-diacritics option), VSS file layouts, eTax XSDs, GL mapping templates — versioned artifacts with effective dates, published without a release (NFR-PAY-010) and shared with the statutory template framework (ADR-0012).
   - **Port interfaces per output** (`BankPaymentFileWriter`, `SiDeclarationFileWriter`, `PitDeclarationXmlWriter`, `GlJournalWriter`): the owning module (Payroll) supplies the data, the writer renders and **validates** (XSD, checksums, control totals) before the file is released to the user.
   - Files are generated as jobs on the `general` worker pool, stored through the Core document service (tenant prefix, encrypted at rest, signed download URL ≤ 15 min, step-up for bank files), and every generation and download is audited with the template version used.
   - **No outbound network calls**, no per-tenant provider credentials, no inbound callbacks in Phase 1. The `integration` worker pool is not deployed.
   - Inbound files (device export, VSS C12 if used) are Core import types owned by the receiving module, not hub connectors.
2. **Phase 2 — integration hub runtime** (unchanged design from revision 1, activated per connector):
   - The hub is the only place that talks to external providers (except Keycloak IdP brokering), runs on the `integration` worker pool.
   - **Connector = anti-corruption adapter** behind a port per capability (`TaxDeclarationSubmitter`, `SiDeclarationSubmitter`, `BankH2H`, `ESignProvider`, `MessageChannel`, `DeviceConnector`, `AccountingPush`, `JobBoardPublisher`…), versioned independently. Phase-1 file writers are reused as the payload builders of the API connectors.
   - Outbound calls via jobs with Resilience4j circuit breakers, timeouts, exponential retry, dead state and operator replay; inbound callbacks via signed webhooks into the hub, translated to domain events.
   - Credentials per tenant in OpenBao; request/response archive with PII redaction for 90 days.
   - **Time devices:** on-prem connector agent (buffers ≥ 7 days) → `hrm-ingest` (mTLS) → Kafka (ADR-0004 rev. 2); T&A owns the device registry, SaaS the transport and credentials; capacity target X-05 (500 punches/s platform, 50/s per tenant) confirmed by SP-3.
   - Phase-2 connectors: time-device connector + offline buffer, Zalo OA/ZNS, SMS brandname (P3), e-signature / e-labour contract, VNeID, accounting APIs (MISA, Fast, SAP), bank H2H, T-VAN/I-VAN APIs, job boards, calendars, CV parser, **public webhooks** (ADR-0014).
3. **Residency:** only VN-hosted providers by default; non-VN processors need tenant opt-in and a DPIA record. Applies from Phase 2 (Phase 1 sends data only to the VN e-mail relay).

## Alternatives considered

- **Build the hub runtime in Phase 1 anyway** (job-based connectors, circuit breakers, credential vault per tenant) so Phase 2 only adds adapters. Rejected: no Phase-1 consumer, about 3–4 developer-weeks that the 16-week plan needs elsewhere; the port/adapter split of decision 1 keeps the Phase-2 step small.
- **iPaaS / ESB (MuleSoft, WSO2, Camel K):** heavy; cloud iPaaS breaches residency. Apache Camel components may still be used inside Phase-2 adapters.
- **Integrations coded inside each module:** duplicates templates, credential handling, retries and monitoring.

## Consequences

- + Phase 1 has no outbound integration surface: fewer secrets, no third-party outages on the payroll path, smaller security review.
- + The statutory outputs needed for go-live (bank, VSS, PIT, GL) are still produced and validated by the system; format changes still need no release.
- − Customers upload files to bank, VSS and eTax portals by hand in Phase 1 (manual step, documented in the runbook; the system records "file downloaded" but cannot confirm submission).
- − Attendance from time devices depends on the customer exporting files from the device software (frequency agreed with the pilot; at least once per pay period, ideally daily for exception review).
- − The hub team is a bottleneck in Phase 2 if under-staffed; connectors should be developed with module BAs.
