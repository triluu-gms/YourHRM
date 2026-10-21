-- YourHRM · DDL integration check for a REAL PostgreSQL server (run after ddl/000..007).
-- Usage: psql -U postgres -d yhrm -v ON_ERROR_STOP=1 -f pg17_integration_check.sql
-- Read-only except section 9, which inserts test rows inside a transaction that is ROLLED BACK.
\set ON_ERROR_STOP on
\pset footer off
\echo '== 0. server'
SELECT version();

\echo '== 1. tables per schema (parents, no partitions)'
SELECT n.nspname AS schema, count(*) AS tables
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r','p') AND NOT c.relispartition
  AND n.nspname IN ('saas','core','audit','hr','att','pay','rec')
GROUP BY 1 ORDER BY 1;

CREATE TEMP TABLE chk (id int, name text, ok boolean, detail text);

-- 2. no FK crosses schemas
INSERT INTO chk SELECT 2, 'no cross-schema foreign key', count(*) = 0, string_agg(conrelid::regclass || ' -> ' || confrelid::regclass, ', ')
FROM pg_constraint c JOIN pg_class a ON a.oid = c.conrelid JOIN pg_class b ON b.oid = c.confrelid
WHERE c.contype = 'f' AND a.relnamespace <> b.relnamespace;

-- 3. every tenant table has ENABLE + FORCE RLS, except the documented platform-only allow-list
INSERT INTO chk SELECT 3, 'tenant tables ENABLE+FORCE RLS (allow-list: audit.chain_head, saas.platform_audit_event)', count(*) = 0, string_agg(t, ', ')
FROM (SELECT c.oid::regclass::text t FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
      WHERE c.relkind IN ('r','p') AND NOT c.relispartition AND n.nspname IN ('saas','core','audit','hr','att','pay','rec')
        AND NOT (c.relrowsecurity AND c.relforcerowsecurity)) x
WHERE t NOT IN ('audit.chain_head','saas.platform_audit_event');

-- 3b. the allow-listed tables have no grants to app/cdc roles
INSERT INTO chk SELECT 31, 'RLS-exception tables have no app/cdc grants', count(*) = 0, string_agg(table_schema||'.'||table_name||':'||grantee, ', ')
FROM information_schema.role_table_grants
WHERE table_schema||'.'||table_name IN ('audit.chain_head','saas.platform_audit_event')
  AND (grantee LIKE 'yhrm\_app%' OR grantee IN ('yhrm_cdc','yhrm_reporting_ro','PUBLIC'));

-- 4. X-02 cross-schema grant guard
INSERT INTO chk SELECT 4, 'sys.cross_schema_grant_violations() = 0', count(*) = 0, NULL FROM sys.cross_schema_grant_violations();

-- 5. module roles have no table grants on another module schema
INSERT INTO chk SELECT 5, 'no module role has grants on another module schema', count(*) = 0, string_agg(DISTINCT grantee||'->'||table_schema, ', ')
FROM information_schema.role_table_grants
WHERE grantee IN ('yhrm_app_hr','yhrm_app_att','yhrm_app_pay','yhrm_app_rec')
  AND table_schema IN ('hr','att','pay','rec') AND table_schema <> substr(grantee, 10);

-- 6. every *_enc column is classified pii:P3
INSERT INTO chk SELECT 6, 'all *_enc columns commented pii:P3', count(*) = 0, string_agg(c.oid::regclass||'.'||a.attname, ', ')
FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('saas','core','audit','hr','att','pay','rec') AND c.relkind IN ('r','p') AND NOT c.relispartition
  AND a.attname LIKE '%\_enc' AND NOT a.attisdropped
  AND coalesce(col_description(c.oid, a.attnum), '') NOT LIKE '%pii:P3%';

-- 7. publications: 5 exist, none publishes a *_enc / *_bidx column
INSERT INTO chk SELECT 7, 'five pub_rpt_* publications', count(*) = 5, string_agg(pubname, ', ') FROM pg_publication WHERE pubname LIKE 'pub\_rpt\_%';
INSERT INTO chk SELECT 71, 'no published *_enc/*_bidx column', count(*) = 0, string_agg(schemaname||'.'||tablename||'.'||col, ', ')
FROM pg_publication_tables, unnest(attnames) AS col WHERE pubname LIKE 'pub\_rpt\_%' AND (col LIKE '%\_enc' OR col LIKE '%\_bidx');

-- 8. masking views are not granted to yhrm_reporting_ro (D6)
INSERT INTO chk SELECT 8, 'no masking view granted to yhrm_reporting_ro', count(*) = 0, string_agg(table_schema||'.'||table_name, ', ')
FROM information_schema.role_table_grants WHERE grantee = 'yhrm_reporting_ro' AND table_name LIKE 'v\_%';

-- 9. behaviour on the real server (rolled back): RLS isolation, fail-closed, purge bypass
BEGIN;
SELECT set_config('app.tenant_id', '01900000-0000-7000-8000-0000000000a1', true);
SET LOCAL ROLE yhrm_app_core;
INSERT INTO core.employee_category (tenant_id, code, name_vi) VALUES ('01900000-0000-7000-8000-0000000000a1', 'ZZTEST', 'Kiểm thử');
RESET ROLE;
SELECT set_config('app.tenant_id', '01900000-0000-7000-8000-0000000000b2', true);
SET LOCAL ROLE yhrm_app_core;
CREATE TEMP TABLE r9 AS SELECT count(*) AS other_tenant_sees FROM core.employee_category WHERE code = 'ZZTEST';
RESET ROLE;
SELECT set_config('app.tenant_id', '', true);
SET LOCAL ROLE yhrm_app_core;
INSERT INTO r9 SELECT count(*) FROM core.employee_category WHERE code = 'ZZTEST';
RESET ROLE;
SELECT set_config('app.tenant_id', '01900000-0000-7000-8000-0000000000a1', true);
SET LOCAL ROLE yhrm_app_core;
INSERT INTO r9 SELECT count(*) FROM core.employee_category WHERE code = 'ZZTEST';
RESET ROLE;
-- purge bypass must be false for an app role even with the setting
SELECT set_config('app.purge_tenant_id', '01900000-0000-7000-8000-0000000000a1', true);
SET LOCAL ROLE yhrm_app_core;
INSERT INTO r9 SELECT CASE WHEN sys.purge_bypass('01900000-0000-7000-8000-0000000000a1') THEN 1 ELSE 0 END;
RESET ROLE;
SELECT array_agg(other_tenant_sees ORDER BY ctid)::text AS r9 FROM r9 \gset
ROLLBACK;   -- test rows, temp table r9 and role switches are discarded; chk (created before BEGIN) survives
INSERT INTO chk SELECT 9, 'RLS on real PG17: other tenant 0 rows, no context 0 rows, own tenant 1 row; app role cannot purge',
  :'r9' = '{0,0,1,0}', :'r9';

\echo '== results'
SELECT CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result, name, left(coalesce(detail, ''), 200) AS detail FROM chk ORDER BY id;
SELECT CASE WHEN bool_and(ok) THEN 'RESULT: ALL PASS' ELSE 'RESULT: ' || count(*) FILTER (WHERE NOT ok) || ' FAIL' END AS summary FROM chk;
