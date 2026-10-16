# ADR-0018: Observability with OpenTelemetry and a self-hosted Grafana stack in Vietnam

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** NFR-SAAS-010 (tenant-tagged logs/metrics/traces, ≥ 12/24 months in VN), NFR-SAAS-001, GAP-069; [architecture-overview](../architecture-overview.md) §14.5

## Context

Support and SRE need logs, metrics and traces tagged with tenant and correlation id, per-tenant dashboards, SLO alerting, and business monitors (period-close mismatches, settlement deadlines, failed statutory validations). Logs must be retained ≥ 12 months (security ≥ 24) in Vietnam with personal data masked. SaaS observability vendors would move data outside Vietnam.

## Decision

1. **OpenTelemetry** everywhere (Java agent + Micrometer; Faro for frontend RUM); OTel Collector as DaemonSet + gateway with PII-scrubbing processors.
2. **Backends:** Grafana **Mimir** (metrics), **Loki** (logs), **Tempo** (traces), Grafana dashboards and Alertmanager; storage on VN object storage with tiered retention (hot 30 days, cold to 12/24 months).
3. **Tenant tagging:** `tenant_id` and `correlation_id` on logs/spans; metrics carry tenant only on bounded top-N series to control cardinality.
4. **SLOs** with multi-window burn-rate alerts: API availability, login (99.9% internal SLO), p95 latency, payroll run duration, relay lag, event delivery lag and `DEAD` deliveries (Phase 1 DB-backed bus; DLT size from Phase 2), reporting replication freshness, import throughput; punch ack from Phase 2 (R2-01).
5. **Business monitors** as scheduled checks emitting metrics.
6. Security events also forwarded to a SIEM (Wazuh or Grafana-based rules) **[spike]**.

## Alternatives considered

- **Datadog / New Relic / Grafana Cloud:** data leaves Vietnam; rejected.
- **ELK/OpenSearch for logs:** heavier to operate at 12–24 months retention than Loki on object storage.

## Consequences

- + Open standards; VN residency; one UI.
- − The stack must be operated and sized (log volume estimate in SP-0).
