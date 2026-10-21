# YourHRM backend

Modular monolith (ADR-0001) on **Java 25 + Spring Boot 4.0.8 + Spring Modulith 2.0.8 + Hibernate 7 + PostgreSQL 17**
(ADR-0002). This first increment is the walking skeleton plus **Core RBAC** (EP-CORE-04: US-CORE-030, 033, 034, 035,
037, 038, 039, 041; US-CORE-036 as an SPI stub).

## Layout

```
backend/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew(.bat)
  src/app/               Spring Boot application (wiring only) + integration / modularity / ArchUnit tests
  src/shared/common/     vn.yourhrm.shared.common  TenantContext (ScopedValue), tenant-aware transaction manager,
                         UUIDv7 ids, ProblemDetail errors, business clock
  src/shared/auth/       vn.yourhrm.shared.auth    JWT resource server, @RequiresPermission / @PublicEndpoint,
                         AuthorizationService API, ScopePredicate, SPIs (ReportingLineProvider, UserAccountResolver)
  src/modules/core/      vn.yourhrm.core           api/ (permission codes, events), internal/{domain,application,infra,web}
```

## Prerequisites

* JDK 25 (Temurin). The Gradle wrapper downloads Gradle 9.8.1.
* Docker (tests start a throw-away `postgres:17.11` with Testcontainers; nothing else is touched).
* If your environment sets `JAVA_TOOL_OPTIONS` (e.g. `-Djava.vendor=...`), clear it for the build shell only:
  PowerShell `$env:JAVA_TOOL_OPTIONS=$null`, Git Bash `export JAVA_TOOL_OPTIONS=`.

## Build and test

```bash
cd backend
./gradlew build            # Windows: gradlew.bat build
```

`build` compiles, runs unit tests, the Spring Modulith `verify()`, the ArchUnit guardrails and the integration tests.
The integration tests load `database/postgresql/ddl/000..007` with `psql` **inside** the Testcontainers PostgreSQL,
set a test-only password on `yhrm_app_core`, run `dev/seed-dev-tenant.sql`, then start the application connected
as `yhrm_app_core` (RLS applies exactly as in production). Memory: Gradle daemon 1 GB, one test JVM (768 MB).

## Run locally

1. Start the local stack (`infra/docker`, see its README): PostgreSQL with the DDL baseline on `localhost:5432`,
   Keycloak on `localhost:8180`.
2. Seed a dev tenant (as superuser, **never** on a shared database):
   ```bash
   psql -h localhost -U postgres -d yhrm -v owner_identity=<keycloak-user-id> \
        -f src/app/src/main/resources/dev/seed-dev-tenant.sql
   ```
   It creates tenant `01900000-0000-7000-8000-000000000001`, two legal entities, a location, a small org tree, the
   global `core.*` permission catalogue, the system role `TENANT_OWNER` (all core permissions) and the owner user
   `owner@dev.yourhrm.local` linked to `owner_identity` with a TENANT-wide assignment. Re-runnable.
3. Run:
   ```bash
   export YHRM_DB_PASSWORD=<password of yhrm_app_core>        # infra/docker default: yhrm_dev
   export YHRM_JWT_ISSUER_URI=http://localhost:8180/realms/yourhrm
   SPRING_PROFILES_ACTIVE=dev ./gradlew :app:bootRun
   ```

### Calling the API

The resource server validates Keycloak access tokens locally (JWKS of the issuer). Claims used:

| Claim | Use |
|---|---|
| `sub` | Keycloak user id, mapped to `core.user_account.identity_id` of the tenant |
| `tenant_id` | tenant id (falls back to `tid`, the name used in ADR-0003/0005) |

No role or permission is read from the token. Add a `tenant_id` claim in Keycloak with a *hardcoded claim* or *user
attribute* mapper on the client (realm `yourhrm`), then:

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=<client> -d username=<user> -d password=<pw> \
  http://localhost:8180/realms/yourhrm/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/core/me
```

Unknown identities or deactivated/expired users get `403` (`code` = `unknown-user` / `user-inactive`).

### Endpoints (`/api/v1/core`)

| Method | Path | Permission |
|---|---|---|
| GET | `/me` | none (`@PublicEndpoint`) |
| GET | `/me/authorisation/check?permission=&targetType=TENANT\|LEGAL_ENTITY\|ORG_UNIT\|LOCATION\|EMPLOYEE&targetId=&asOf=` | none (answers for the caller only) |
| GET | `/me/authorisation/scope?permission=&asOf=` | none (caller only) |
| GET | `/permissions?module=&includeInactive=` | `core.permission.view` |
| GET | `/roles?q=` , `/roles/{id}` | `core.role.view` (TENANT scope) |
| POST | `/roles` , `/roles/{id}/copy` | `core.role.create` (TENANT) |
| PUT | `/roles/{id}` , `/roles/{id}/permissions` | `core.role.update` (TENANT) |
| DELETE | `/roles/{id}` | `core.role.delete` (TENANT) |
| GET | `/users?q=&status=&page=&size=` , `/users/{id}` | `core.user.view` (TENANT) |
| POST | `/users` | `core.user.create` (TENANT) |
| PUT | `/users/{id}` ; POST `/users/{id}/activate` , `/users/{id}/deactivate` | `core.user.update` (TENANT) |
| GET | `/users/{id}/role-assignments?includeHistory=` | `core.role_assignment.view` (TENANT) |
| POST | `/users/{id}/role-assignments` | `core.role_assignment.create` (TENANT) |
| DELETE | `/users/{id}/role-assignments/{assignmentId}` | `core.role_assignment.delete` (TENANT) |

Errors are RFC 9457 `application/problem+json` with a stable `code` property. System roles are read-only
(`409 system-role-immutable`); copy them instead.

## How tenancy works per transaction

* `TenantContextFilter` (after bearer-token authentication) takes the tenant from the JWT claim and the identity from
  `sub`, resolves the tenant user through Core, and binds an immutable `TenantContext` with `ScopedValue` for the rest
  of the request. Headers/bodies are never used for tenant or user (ArchUnit-guarded).
* `TenantAwareJpaTransactionManager` (replaces Boot's JPA transaction manager) issues, on the transaction's JDBC
  connection, right after `BEGIN` and before anything else:
  `select set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true), set_config('app.request_id', ?, true), set_config('app.purpose', ?, true)`.
  Read-only transactions included; JPA and `JdbcClient` share the connection. Without a context the transaction is
  refused (`NoTenantContextException`); platform work (permission registration) uses an explicit `PlatformContext`
  with an empty tenant.
* PostgreSQL RLS (DDL) is fail-closed: outside a transaction the setting is empty, so queries return no rows and writes
  fail the `WITH CHECK`. Queries also filter `tenant_id` explicitly (second line of defence).
* The app connects as `yhrm_app_core` (never owner/superuser). DDL audit triggers record `app.user_id` as actor;
  services stamp `created_by` / `updated_by`.

## Stubbed / deferred in this increment

* **US-CORE-036** reporting lines: `ReportingLineProvider.NONE` (no reports) until People implements the SPI;
  `DIRECT_REPORTS` / `ALL_REPORTS` resolve to an empty scope (fail closed). `SELF` uses `user_account.employment_id`.
* **US-CORE-031** event-driven user provisioning from People; user expiry job (expired users are already denied at login).
* **Outbox**: `core.UserStatusChanged`, `core.RoleAssignmentChanged`, `core.RoleChanged` are in-process Spring events only
  (cache eviction); writing them to `core.outbox_event` is TECH:outbox.
* **Flyway** disabled (`spring.flyway.enabled=false`, no Flyway dependency): the DDL baseline is loaded by scripts;
  the migration pipeline is US-TECH-024.
* **jOOQ** codegen deferred: read/scope queries use Spring `JdbcClient`.
* Cache: Caffeine L1 only (60 s TTL, evicted after commit in this instance). Valkey L2 and cross-instance
  invalidation by events are deferred.
* Permission registration: `core.permission` is writable only by the release job (`yhrm_platform`/migrator), so the
  app verifies the catalogue at start-up and logs the upsert SQL when it drifts (`yhrm.permissions.registration`).
* Not yet: field masking (`@Sensitive`, US-CORE-040), break-glass support grants (US-CORE-042), step-up (`acr`),
  SoD / approval for privileged grants, entitlements, `AbstractRoutingDataSource` per cluster, Hibernate filter as an
  extra tenant guard, per-module DB roles beyond Core, Testcontainers-based perf suite with 10k employees / 2k units.
