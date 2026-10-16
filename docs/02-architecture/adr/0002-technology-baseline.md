# ADR-0002: Technology baseline (Java 25, Spring Boot 4, PostgreSQL 17)

- **Status:** Accepted (2026-10-16, OD-3 — Java 25 + Spring Boot 4; Java 21 + Boot 3.5 fallback decided after the first spike). **Amendment 1 (2026-10-16, R2-01) — Accepted (R3-11):** messaging (Kafka, Apicurio) moves to Phase 2; see ADR-0004 rev. 2
- **Date:** 2026-10-16
- **Related:** [architecture-overview](../architecture-overview.md) §4; ADR-0001, ADR-0017

## Context

The charter fixes Java + Spring Boot and PostgreSQL. The product must run for 10+ years (payroll reproducibility), entirely in Vietnam, with zero-downtime releases. As of October 2026, Java 25 is the current LTS (September 2025) and Spring Boot 4.0 (Spring Framework 7, Jakarta EE 11) has been GA since November 2025. PostgreSQL 17 is widely available; PostgreSQL 18 is released but not yet offered by every VN provider/operator.

## Decision

| Concern | Choice |
|---|---|
| Runtime | Java 25 LTS (Eclipse Temurin); virtual threads; `ScopedValue` for tenant context |
| Framework | Spring Boot 4.0.x, Spring Modulith 2.x, Spring Security 7 |
| Persistence | Hibernate ORM 7 for aggregates; jOOQ for read models, bulk payroll I/O and reporting; `COPY` for bulk writes; Flyway Community per schema (licence accepted 2026-10-16, ADR-0031) |
| Database | PostgreSQL 17 (18 when supported by the selected provider/operator) operated by CloudNativePG; PgBouncer transaction pooling |
| Messaging | **Phase 1:** none — transactional outbox + polling relay + delivery jobs on the PostgreSQL job queue (ADR-0004 rev. 2). **Phase 2:** Apache Kafka 4.x (KRaft) via Strimzi (or provider-managed); Apicurio Registry 3 |
| Cache | Valkey 8 + Caffeine |
| Build | Gradle (Kotlin DSL), Jib images, Testcontainers |

**Fallback (OD-3, accepted 2026-10-16):** Java 25 + Spring Boot 4 is the decision. If the first spikes — the walking skeleton (SP-0, library compatibility) and the payroll engine spike (SP-1, named in the sponsor decision) — find a blocking library incompatibility or performance problem with Boot 4/Java 25, switch to Spring Boot 3.5.x on Java 21 LTS. The switch is decided once, at the end of SP-1, and does not change any other ADR.

## Alternatives considered

- **Java 21 + Spring Boot 3.5.** Safe and mature, but Boot 3.x OSS support ends earlier and a major upgrade would be needed during the pilot phase. Kept as fallback.
- **Kotlin.** Concise, but smaller hiring pool in VN for Kotlin backend; no strong benefit for this domain.
- **Pure JPA** for all persistence. Rejected for payroll bulk paths and as-of queries, where explicit SQL is needed for performance and determinism.
- **Redis 8.** Licence terms are less predictable for cloud providers; Valkey is protocol-compatible and BSD-licensed.

## Consequences

- + Long support horizon; modern concurrency (virtual threads) suits I/O-heavy API and worker loads.
- + Two persistence tools need clear guidelines (aggregate writes → JPA; bulk/read models → jOOQ).
- − Boot 4 is younger; early adopters may hit library gaps (mitigated by SP-0 and fallback).
- − Team members need training on Modulith and jOOQ.
