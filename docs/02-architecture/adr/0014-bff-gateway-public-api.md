# ADR-0014: BFF for browsers, Envoy Gateway at the edge, public API and webhooks

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** ADR-0005; SaaS BRD §4.F (public API, webhooks P2); NFR-SAAS-018, NFR-CORE-014; [architecture-overview](../architecture-overview.md) §6.2, §12

## Context

The SPA handles highly sensitive personal and salary data. Current OAuth guidance for browser apps recommends keeping tokens out of JavaScript (BFF / token-handler pattern). The platform needs TLS termination, WAF, rate limiting and routing for browsers, the Phase-2 mobile app, device agents (mTLS) and a P2 public API with OAuth2 client credentials and webhooks. The community ingress-nginx controller has been retired, so a new edge standard is needed.

## Decision

1. **Edge: Envoy Gateway** (Kubernetes Gateway API) with OWASP CRS WAF (provider WAF or Coraza), TLS 1.2+/1.3, global rate limiting, routes for app, API, ingest (mTLS).
2. **`hrm-bff`** (Spring Cloud Gateway server WebMVC + Spring Security OAuth2 client): Authorization Code + PKCE with Keycloak; tokens stored server side (Valkey, encrypted); HttpOnly `Secure` `SameSite=Strict` cookie; CSRF protection; forwards access tokens to `hrm-api`; handles step-up redirects.
3. **API conventions:** OpenAPI 3.1 per module, RFC 9457 errors, cursor pagination, ETag/If-Match, `Idempotency-Key`, `asOf`/`knownAt` parameters.
4. **Public API (P2):** `/public/v1` curated subset; OAuth2 client credentials; scopes mapped to permissions + data scope by Core; per-client quotas; backward compatible within major; ≥ 6-month deprecation. A dedicated API-management product (APISIX/Kong) only if a developer portal with monetisation is required.
5. **Webhooks (P2):** curated, PII-minimised events; HMAC-SHA256 signatures with timestamp; retries for 24 h; delivery log and replay; auto-disable; secret rotation.

## Alternatives considered

- **SPA with tokens in memory (public client):** simpler, but tokens are exposed to XSS; refresh-token handling in the browser is weaker. Rejected for sensitive data.
- **Kong / APISIX as the main edge:** capable; more moving parts than needed for MVP.
- **GraphQL:** flexible querying, but complicates field-level masking, caching and rate limiting; REST chosen.

## Consequences

- + Tokens never reach browser JavaScript; consistent edge policy.
- − An extra deployable (`hrm-bff`) on the request path; must be HA and low-latency.
- − Webhook delivery infrastructure to build in P2 (reuses integration worker).
