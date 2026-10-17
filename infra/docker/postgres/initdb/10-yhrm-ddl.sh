#!/usr/bin/env bash
# Runs once, on the first start of an empty data volume.
# Loads the design DDL (database/postgresql/ddl/*.sql) in file order into the `yhrm`
# database, then gives the login roles a LOCAL-ONLY password so developers and
# Testcontainers-like tooling can connect as the real module roles (RLS applies).
set -euo pipefail

echo "[yourhrm] loading DDL from /yhrm-ddl"
for f in /yhrm-ddl/[0-9][0-9][0-9]_*.sql; do
  echo "[yourhrm]   -> $(basename "$f")"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname yhrm -f "$f"
done

echo "[yourhrm] setting local-only passwords for login roles"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname yhrm \
     -v pw="$YHRM_APP_PASSWORD" <<'SQL'
SELECT set_config('yhrm.pw', :'pw', false);   -- psql variables are not visible inside DO blocks
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT rolname FROM pg_roles WHERE rolname LIKE 'yhrm\_%' AND rolcanlogin LOOP
    EXECUTE format('ALTER ROLE %I PASSWORD %L', r.rolname, current_setting('yhrm.pw'));
  END LOOP;
END $$;
SQL
echo "[yourhrm] DDL loaded"
