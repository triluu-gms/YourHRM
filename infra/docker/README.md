# Local development environment

Phase 1 stack for one developer laptop (tested target: Windows 11, 8 GB RAM, Docker Desktop on WSL2).

| Service | URL / port | Login (local only) | Purpose |
|---|---|---|---|
| PostgreSQL 17 | `localhost:5432`, database `yhrm` | `postgres` / `postgres_dev`; module roles `yhrm_app_core` … / `yhrm_dev` | All schemas, loaded from `database/postgresql/ddl/` on first start |
| Keycloak 26 | http://localhost:8180 | `admin` / `admin_dev` | Authentication (ADR-0005) |
| Valkey 8 | `localhost:6379` | — | Cache (ADR-0002) |
| Mailpit | UI http://localhost:8025, SMTP `localhost:1025` | — | Catches all e-mail: OTP, notifications, payslip PDFs |
| OpenBao (profile `full`) | http://localhost:8200 | token `root_dev` | Secrets / transit encryption (ADR-0017, ADR-0026) |

Not in the local stack: Kafka (Phase 2, ADR-0004 rev. 2), PgBouncer and object storage (added when the walking skeleton needs them, US-TECH backlog).

## Prerequisites (one time)
1. **JDK 25** (Temurin), **Node.js 22**, **Git**.
2. **WSL2 + Docker Desktop**, then **restart Windows**.
3. Start Docker Desktop once and accept the licence. Settings → Resources should show the WSL2 backend.
4. Optional: `C:\Users\<you>\.wslconfig` caps WSL memory (3 GB on an 8 GB laptop).

## Daily use (from the repo root)
```powershell
docker compose -f infra/docker/docker-compose.yml up -d            # core stack
docker compose -f infra/docker/docker-compose.yml --profile full up -d   # + OpenBao
docker compose -f infra/docker/docker-compose.yml ps
docker compose -f infra/docker/docker-compose.yml logs -f postgres
docker compose -f infra/docker/docker-compose.yml down             # stop (data kept)
docker compose -f infra/docker/docker-compose.yml down -v          # stop and DELETE all local data
```

## Database
- On the **first** start of an empty volume, `postgres/initdb/10-yhrm-ddl.sh` runs `000_…` → `003_…` from `database/postgresql/ddl/` into `yhrm` and sets a local password on every `yhrm_*` login role.
- After a DDL change, reload from scratch: `down -v`, then `up -d`.
- Connect as a module role to see RLS working: `psql -h localhost -U yhrm_app_hr -d yhrm`, then `SELECT set_config('app.tenant_id', '<uuid>', false);`. With no tenant set, every tenant table returns 0 rows.
- From the Flyway pipeline onwards, these DDL files become each module's `V1__baseline.sql` (DESIGN.md §12).

## Overrides
Copy `.env.example` to `.env` in this folder to change ports or passwords.
