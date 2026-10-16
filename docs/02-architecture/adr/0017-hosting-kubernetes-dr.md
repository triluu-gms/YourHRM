# ADR-0017: Hosting in Vietnam on Kubernetes; secrets and key management; disaster recovery

- **Status:** Proposed (rev. 2026-10-16, R2-01: Strimzi/Kafka and MirrorMaker 2 move to Phase 2; R2-15: no human access to application DB credentials)
- **Date:** 2026-10-16
- **Related:** BRD A4, C-2, DP3; NFR-SAAS-001/006/007/008/014; backup/PITR and encryption key hierarchy in the data architecture (DESIGN.md); [architecture-overview](../architecture-overview.md) §13, §14

## Context

All personal data — including logs, backups, search indexes and support tooling — must stay in Vietnam. The platform needs ≥ 99.5% availability (99.9% Enterprise), RPO ≤ 15 min, RTO ≤ 4 h (≤ 2 h Enterprise) with a second VN data centre, zero-downtime releases, secrets rotated ≤ 90 days, and field-level encryption with per-tenant keys in KMS/HSM. Candidate providers: Viettel IDC/Viettel Cloud, FPT Cloud, VNG Cloud, CMC Cloud; hyperscalers only with a VN-located region and VN-resident sub-processors.

## Decision

1. **Kubernetes** (provider-managed where mature, CNCF-conformant) in DC1 (primary) and DC2 (DR), separate prod and non-prod clusters; node pools for app, workers, data, platform.
2. **Operators for portability:** CloudNativePG (PostgreSQL), Strimzi (Kafka, **Phase 2**, ADR-0004 rev. 2), Valkey operator/Helm, Argo CD (GitOps), cert-manager, KEDA, Kyverno, Cilium (network policy + transparent encryption).
3. **Secrets & keys: OpenBao** (Vault-compatible) with Kubernetes auth, dynamic DB credentials, transit engine for envelope encryption (per-tenant KEKs; DEKs wrapped); auto-unseal with provider KMS/HSM where available; software root of trust for the MVP and HSM-backed root before the Enterprise tier (OD-10, accepted 2026-10-16); provider choice after the 2-week SP-7 evaluation (OD-1). **No human access to application credentials (R2-15, accepted):** module DB login roles (`yhrm_app_*`), the relay, sealer and CDC roles get dynamic credentials only through OpenBao Kubernetes auth bound to their service accounts; no OpenBao policy lets an operator or developer read them; humans reach the database only through `yhrm_breakglass` (two-person approval, ≤ 4 h, pgaudit on every statement, session recording), and any login of a module role from outside the app pods raises an alert.
4. **DR:** CNPG replica clusters in DC2 + WAL archive to DC2 object storage (`archive_timeout` 5 min) + daily base backups (35 days); Kafka MirrorMaker 2 plus outbox re-publication (Phase 2; in Phase 1 the outbox and event-delivery jobs are database rows and fail over with the database); object storage cross-site replication; Argo CD in DC2; scripted DNS switch; quarterly drill. Enterprise: hot standby with pre-scaled apps.
5. **Provider selection** through SP-7 (scored: managed K8s maturity, two DCs ≥ 50 km apart, S3 replication, KMS/HSM, network, compliance certificates, price, support); same provider for DC1/DC2 in MVP; IaC in OpenTofu to keep a second provider viable.

## Alternatives considered

- **VMs without Kubernetes:** simpler for a small team, but zero-downtime rollouts, autoscaling for peaks and per-pool workers would be hand-built.
- **Provider-managed PostgreSQL/Kafka:** acceptable if they offer PITR, logical decoding on standby, cross-DC replicas and VN residency; operators kept as default for portability.
- **HashiCorp Vault:** BSL licence; OpenBao is the open fork with compatible APIs.
- **Hyperscaler outside VN:** violates residency; rejected.

## Consequences

- + Portable across VN providers; GitOps makes DR rebuild repeatable.
- − Operating data operators requires SRE skills; a platform engineer is needed from sprint 1.
- − Cost of a warm DR site; mitigated by scaling DC2 apps to near zero.
