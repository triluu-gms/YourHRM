# ADR-0016: Integration hub and connector pattern

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** SaaS BRD §4.F; gap-analysis GAP-008, GAP-009, GAP-052, GAP-058; NFR-PAY-010, NFR-REC-007, NFR-ATT-001/004; BRD RK7; [architecture-overview](../architecture-overview.md) §7.5

## Context

YourHRM integrates with banks (files, later H2H), eTax/T-VAN, VSS/I-VAN, MISA/Fast/SAP, e-invoice providers, e-signature CAs, VNeID, time devices, Zalo ZNS/SMS/push/e-mail, job boards, calendars and CV parsers. Formats and APIs change frequently (RK7). Credentials are per tenant. Failures must be retried without data loss and be visible.

## Decision

1. The **integration hub** lives in `saas-platform/integration`, runs on the `integration` worker pool, and is the only place that talks to external providers (except Keycloak's IdP brokering).
2. **Connector = anti-corruption adapter** behind a port interface per capability (`BankPaymentFileWriter`, `TaxDeclarationSubmitter`, `ESignProvider`, `MessageChannel`, `DeviceConnector`…), versioned independently.
3. **Format templates as configuration** (bank layouts, XSDs, GL mappings) versioned and publishable without release.
4. **Outbound calls via jobs** with Resilience4j circuit breakers, timeouts, exponential retry, dead state and operator replay; inbound callbacks (e-sign, banks) via signed webhooks into the hub, translated to domain events.
5. **Credentials** per tenant in OpenBao; request/response archive with PII redaction for 90 days.
6. **Time devices:** on-prem connector agent (Java, buffers ≥ 7 days) → `hrm-ingest` (mTLS) → Kafka; T&A owns the device registry, SaaS owns transport and credentials.
7. **Residency:** only VN-hosted providers by default; non-VN processors require tenant opt-in and DPIA.

## Alternatives considered

- **iPaaS / ESB (MuleSoft, WSO2, Apache Camel K):** powerful but heavy; cloud iPaaS breaches residency. Apache Camel components may be used *inside* adapters where helpful.
- **Integrations coded inside each module:** duplicates credential handling, retries and monitoring.

## Consequences

- + Uniform resilience, monitoring and credential handling; format changes without release.
- − The hub team becomes a bottleneck if under-staffed; connectors should be developed with module BAs.
