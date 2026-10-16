# ADR-0005: Keycloak as identity provider; SaaS authenticates, Core authorises

- **Status:** Accepted (2026-10-16, brief baseline 1 "SaaS authenticates, Core authorises"; OD-8 former-employee e-mail OTP; **R2-04: SSO moved to Phase 2** — Phase 1 = Keycloak username/password + MFA by TOTP or e-mail OTP)
- **Date:** 2026-10-16
- **Related:** Architecture brief B1; gap-analysis GAP-002, GAP-003, GAP-016, GAP-035, GAP-036, §9 A-5; NFR-CORE-006/007; [architecture-overview](../architecture-overview.md) §6

## Context

Authentication (credentials, MFA, SSO, sessions, API clients) is owned by the SaaS platform; authorisation (user accounts, roles, data scopes, masking) by Core. **Phase 1 (R2-04, 2026-10-16): Keycloak username + password with MFA by TOTP (authenticator app) or e-mail one-time code; no SSO at the pilot.** OIDC SSO (Google, Microsoft Entra ID) and SAML move to Phase 2; SCIM stays P3. One natural person may belong to several tenants (GAP-016). Former employees keep limited access without the tenant SSO (GAP-036). Candidates need passwordless access (P2). All identity data must stay in Vietnam; login availability SLO is 99.9% and token validation must not call the IdP per request.

## Decision

1. **Keycloak 26.x**, self-hosted in the VN clusters with its own replicated PostgreSQL database.
2. **One realm `yourhrm`** using **Keycloak Organizations**: one Organization per tenant; a Keycloak user is the global identity; Organization membership links it to tenants; per-Organization identity-provider brokering (OIDC and SAML, **Phase 2**, R2-04). Enterprise dedicated tier may get its own realm.
   - **Phase-1 authentication (R2-04):** username (work or personal e-mail) + password under a tenant-configurable password policy (Keycloak), brute-force protection, and **MFA by TOTP or by e-mail OTP** (Keycloak e-mail OTP authenticator, built-in or a vetted extension **[SP-8]**); MFA is mandatory for HR, C&B, finance, tenant admin and operator roles and tenant-configurable for ESS users. WebAuthn/passkeys are Phase 2 with SSO. Password reset and e-mail OTP use the VN-hosted e-mail relay; SMS OTP is P3.
3. **Tokens:** short-lived (5 min) JWT access tokens with `sub`, `tid`, `org`, `acr`, `amr`, `sid`; **no roles/permissions in tokens**. Resource servers validate locally with cached JWKS.
4. **Step-up** via ACR levels (`1` password/SSO, `2` MFA, `3` MFA within 5 min) requested with `acr_values`; sensitive operations demand `acr ≥ 2` or `3`.
5. **Browser** uses a BFF (ADR-0014); the mobile app (Phase 2, OD-9) uses AppAuth + PKCE with refresh-token rotation; API clients (public API, Phase 2, ADR-0014) use client credentials, created by SaaS per tenant and mapped to permissions by Core.
6. **Core** owns `user_account` (tenant-scoped) with `identity_id`; Core emits `core.UserStatusChanged`; SaaS reacts by enabling/disabling Organization membership, revoking sessions, and (P3) SCIM.
7. **Former employees:** status `FORMER_EMPLOYEE` removes the SSO link (Phase 2, when SSO exists) and enables e-mail OTP login for a tenant-set period (default 24 months); accepted as OD-8, so a personal e-mail is captured at offboarding (GAP-036). SMS OTP is P3.
8. **Candidates (P2):** passwordless magic links via signed single-use tokens or a separate external-subject realm **[spike]**.

## Alternatives considered

- **Hosted IdPs (Auth0, Entra External ID, Cognito):** identity data outside Vietnam; rejected for residency.
- **Realm per tenant:** strong separation, but 500+ realms degrade Keycloak admin performance and complicate multi-tenant users; kept only for Enterprise.
- **Zitadel / Ory:** capable and multi-tenant-native, but smaller local skill base and fewer SAML brokering references; reconsider if Organizations prove insufficient in SP-8.
- **Spring Authorization Server (custom IdP):** too much security-critical code to own.
- **Roles in tokens:** stale until expiry and bloated for users with many scopes; rejected.

## Consequences

- + Standards-based login, MFA and (Phase 2) SSO without building an IdP; VN-resident.
- − Phase 1 without SSO: users of the pilot hold one more password; mitigated by e-mail-based self-service reset and TOTP/e-mail OTP. Adding SSO in Phase 2 is configuration per Organization (identity brokering), not a model change.
- + Clear seam: SaaS never decides "what", Core never handles credentials.
- − Keycloak becomes tier-0: HA (≥ 3 replicas), its DB in DR scope, upgrade discipline.
- − Organizations feature maturity must be validated (SP-8).
- − Every request needs a Core authorisation lookup (cached; ADR-0006).
