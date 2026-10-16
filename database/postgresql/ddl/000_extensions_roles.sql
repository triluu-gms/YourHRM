-- =============================================================================
-- YourHRM  ·  000_extensions_roles.sql
-- PostgreSQL 16+.  Extensions, cluster roles, schemas and the shared `sys`
-- plumbing (functions/domains only, no tables) used by every module schema.
--
-- See database/postgresql/DESIGN.md §2-§4 and ADR-0020/0021/0023/0024/0034.
--
-- Run as a superuser / cloud admin ONCE per database (shared pool or dedicated).
-- Later schema changes are Flyway migrations executed by yhrm_migrator with
-- `SET ROLE yhrm_owner`.
--
-- NOTE: the tenancy helper functions (sys.current_tenant_id, sys.enable_tenant_rls…)
-- live here rather than in 002_core.sql because 001_saas.sql already needs them.
-- They are owned by the platform shared kernel; Core consumes them like everyone.
-- =============================================================================

\set ON_ERROR_STOP on

-- -----------------------------------------------------------------------------
-- 1. Extensions
-- -----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS btree_gist;          -- uuid/text equality inside EXCLUDE ... USING gist
CREATE EXTENSION IF NOT EXISTS pgcrypto;            -- digest() for audit hash chain, gen_random_bytes()
CREATE EXTENSION IF NOT EXISTS pg_trgm;             -- accent-insensitive fuzzy name search
CREATE EXTENSION IF NOT EXISTS unaccent;            -- Vietnamese diacritics folding
CREATE EXTENSION IF NOT EXISTS ltree;               -- materialised org paths (as-of trees, data scope)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;  -- requires shared_preload_libraries
-- CREATE EXTENSION IF NOT EXISTS pgaudit;          -- requires shared_preload_libraries; enabled by infra
-- CREATE EXTENSION IF NOT EXISTS pg_partman;       -- optional, see DESIGN §10.2 [spike: availability]

-- Database-level defaults (instants are UTC; business dates are explicit `date`s)
DO $$ BEGIN
  EXECUTE format('ALTER DATABASE %I SET timezone TO %L', current_database(), 'UTC');
  EXECUTE format('ALTER DATABASE %I SET default_toast_compression TO %L', current_database(), 'lz4');
END $$;

-- -----------------------------------------------------------------------------
-- 2. Roles  (cluster-wide; idempotent).  Passwords are NEVER set here:
--    login roles get credentials from the vault (rotated <= 90 days, NFR-SAAS-008).
-- -----------------------------------------------------------------------------
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT * FROM (VALUES
      -- role,               login, bypassrls, comment
      ('yhrm_owner',         false, true,  'Owns all objects. Used only via SET ROLE by the migrator.'),
      ('yhrm_migrator',      true,  false, 'Flyway / CI-CD only. Member of yhrm_owner.'),
      ('yhrm_app',           false, false, 'Group role targeted by every RLS policy. NEVER BYPASSRLS.'),
      ('yhrm_app_saas',      true,  false, 'SaaS platform module runtime.'),
      ('yhrm_app_core',      true,  false, 'Core module runtime.'),
      ('yhrm_app_hr',        true,  false, 'People module runtime.'),
      ('yhrm_app_att',       true,  false, 'Time & Attendance module runtime.'),
      ('yhrm_app_pay',       true,  false, 'Payroll module runtime (incl. calculation workers).'),
      ('yhrm_app_rec',       true,  false, 'Recruitment module runtime.'),
      ('yhrm_platform',      true,  false, 'Operator back-office. Platform tables in saas only.'),
      ('yhrm_relay',         true,  false, 'Outbox polling relay (ADR-0004). Reads/marks outbox of all tenants via dedicated policy.'),
      ('yhrm_cdc',           true,  false, 'Debezium logical decoding on a standby for the reporting store only (ADR-0012).'),
      ('yhrm_audit_sealer',  true,  false, 'Seals the audit hash chain (seal columns only).'),
      ('yhrm_reporting_ro',  true,  false, 'Real-time statutory reports on OLTP; RLS applies.'),
      ('yhrm_ops_ro',        true,  false, 'Monitoring only (pg_monitor). No table data.'),
      ('yhrm_breakglass',    false, true,  'Granted temporarily to a named DBA on two-person approval; pgaudit logs all.')
    ) AS t(role_name, can_login, bypass, note)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.role_name) THEN
      EXECUTE format('CREATE ROLE %I %s %s NOSUPERUSER NOCREATEDB NOCREATEROLE',
                     r.role_name,
                     CASE WHEN r.can_login THEN 'LOGIN' ELSE 'NOLOGIN' END,
                     CASE WHEN r.bypass    THEN 'BYPASSRLS' ELSE 'NOBYPASSRLS' END);
    END IF;
    EXECUTE format('COMMENT ON ROLE %I IS %L', r.role_name, r.note);
  END LOOP;
END $$;

GRANT yhrm_owner TO yhrm_migrator;                       -- SET ROLE yhrm_owner in Flyway initSql
GRANT yhrm_app   TO yhrm_app_saas, yhrm_app_core, yhrm_app_hr,
                    yhrm_app_att,  yhrm_app_pay,  yhrm_app_rec;
GRANT pg_monitor TO yhrm_ops_ro;
ALTER ROLE yhrm_cdc WITH REPLICATION;                    -- reporting CDC only; the business event path is the polling relay

-- Safe defaults for every application session (also enforced by the pool config).
ALTER ROLE yhrm_app_saas SET statement_timeout = '30s';
ALTER ROLE yhrm_app_core SET statement_timeout = '30s';
ALTER ROLE yhrm_app_hr   SET statement_timeout = '30s';
ALTER ROLE yhrm_app_att  SET statement_timeout = '30s';
ALTER ROLE yhrm_app_pay  SET statement_timeout = '300s';  -- payroll batch statements
ALTER ROLE yhrm_app_rec  SET statement_timeout = '30s';
ALTER ROLE yhrm_migrator SET lock_timeout = '3s';

-- Nobody may create objects in public (PG15+ default, made explicit). USAGE stays,
-- because the extensions above (ltree, btree_gist, pg_trgm, pgcrypto, unaccent)
-- live in public and every role needs their types/operators/functions.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- -----------------------------------------------------------------------------
-- 3. Schemas (owned by yhrm_owner). Module grants are given in each module file.
-- -----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS sys   AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS saas  AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS core  AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS audit AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS hr    AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS att   AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS pay   AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS rec   AUTHORIZATION yhrm_owner;

COMMENT ON SCHEMA sys   IS 'Shared kernel: functions, domains, trigger functions. No tables.';
COMMENT ON SCHEMA saas  IS 'SaaS platform: tenants, plans, billing, identity, legal parameter packs, privacy registries, keyring.';
COMMENT ON SCHEMA core  IS 'Core: global master data, legal entity, org, positions, users/roles, workflow, notifications, documents.';
COMMENT ON SCHEMA audit IS 'Core audit store: append-only, hash-chained, partitioned.';
COMMENT ON SCHEMA hr    IS 'People: person, employment, assignment, salary record, contract, dependants, bank accounts.';
COMMENT ON SCHEMA att   IS 'Time & Attendance.';
COMMENT ON SCHEMA pay   IS 'Payroll.';
COMMENT ON SCHEMA rec   IS 'Recruitment (Phase 2).';

GRANT USAGE ON SCHEMA sys TO yhrm_app, yhrm_platform, yhrm_relay, yhrm_audit_sealer, yhrm_reporting_ro;

SET ROLE yhrm_owner;

-- -----------------------------------------------------------------------------
-- 4. Domains (DESIGN §4.6)
-- -----------------------------------------------------------------------------
DO $$ BEGIN
  CREATE DOMAIN sys.money_amount AS numeric(19,4);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  CREATE DOMAIN sys.rate AS numeric(12,8);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  CREATE DOMAIN sys.quantity AS numeric(12,4);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  CREATE DOMAIN sys.currency_code AS char(3) CHECK (VALUE ~ '^[A-Z]{3}$');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  -- first day of a month, used for year-month periods
  CREATE DOMAIN sys.month_date AS date CHECK (extract(day FROM VALUE) = 1);   -- immutable, tz-independent
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- -----------------------------------------------------------------------------
-- 5. Identifiers
-- -----------------------------------------------------------------------------

-- UUIDv7 (RFC 9562): 48-bit unix ms timestamp + version 7 + random.
-- Applications normally generate ids themselves; this is the column default/fallback.
-- On PostgreSQL 18+, replace the body with the built-in uuidv7().
CREATE OR REPLACE FUNCTION sys.uuid_v7()
RETURNS uuid
LANGUAGE sql VOLATILE PARALLEL SAFE
AS $$
  -- overlay the first 6 bytes of a v4 uuid with the ms timestamp, then flip the
  -- version nibble from 0100 (v4) to 0111 (v7) by setting bits 52 and 53.
  SELECT encode(
           set_bit(set_bit(
             overlay(uuid_send(gen_random_uuid())
                     PLACING substring(int8send((extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                     FROM 1 FOR 6),
           52, 1), 53, 1),
         'hex')::uuid;
$$;
COMMENT ON FUNCTION sys.uuid_v7() IS 'UUIDv7 generator (time-ordered). Swap for built-in uuidv7() on PG18.';

-- Sentinel tenant that owns GLOBAL rows of "global + tenant-overridable" tables (DESIGN §3.5).
CREATE OR REPLACE FUNCTION sys.global_tenant_id()
RETURNS uuid LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT '00000000-0000-0000-0000-000000000000'::uuid $$;

-- -----------------------------------------------------------------------------
-- 6. Tenant / request context (set per TRANSACTION with set_config(..., true))
-- -----------------------------------------------------------------------------
-- NULLIF(...,'') matters: after a transaction-local setting ends, a pooled session
-- still knows the placeholder and returns '' instead of NULL.
CREATE OR REPLACE FUNCTION sys.current_tenant_id()
RETURNS uuid LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE OR REPLACE FUNCTION sys.current_user_id()
RETURNS uuid LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

CREATE OR REPLACE FUNCTION sys.current_request_id()
RETURNS text LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.request_id', true), '') $$;

CREATE OR REPLACE FUNCTION sys.current_purpose()
RETURNS text LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.purpose', true), '') $$;

-- Convenience for the application transaction template:
--   SELECT sys.set_context(:tenant, :user, :request, :purpose);
CREATE OR REPLACE FUNCTION sys.set_context(p_tenant uuid, p_user uuid, p_request text, p_purpose text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql VOLATILE
AS $$
BEGIN
  IF p_tenant IS NULL THEN
    RAISE EXCEPTION 'tenant context must not be null' USING ERRCODE = '28000';
  END IF;
  PERFORM set_config('app.tenant_id',  p_tenant::text,            true);
  PERFORM set_config('app.user_id',    coalesce(p_user::text, ''), true);
  PERFORM set_config('app.request_id', coalesce(p_request, ''),    true);
  PERFORM set_config('app.purpose',    coalesce(p_purpose, ''),    true);
END $$;

-- Raise if no tenant is set (called by jobs and by the transaction template in tests).
CREATE OR REPLACE FUNCTION sys.assert_tenant()
RETURNS uuid LANGUAGE plpgsql STABLE
AS $$
DECLARE t uuid := sys.current_tenant_id();
BEGIN
  IF t IS NULL THEN
    RAISE EXCEPTION 'no tenant context in this transaction (app.tenant_id)' USING ERRCODE = '28000';
  END IF;
  RETURN t;
END $$;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA sys TO yhrm_app, yhrm_platform, yhrm_relay, yhrm_audit_sealer, yhrm_reporting_ro;

-- -----------------------------------------------------------------------------
-- 7. Text helpers
-- -----------------------------------------------------------------------------
-- unaccent() is STABLE (dictionary lookup), so it cannot be used in indexes.
-- This immutable wrapper pins the dictionary, the documented workaround.
CREATE OR REPLACE FUNCTION sys.f_unaccent(text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- Normalised search key for Vietnamese names: lower + unaccent + d/đ folding.
CREATE OR REPLACE FUNCTION sys.search_key(text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$ SELECT translate(lower(sys.f_unaccent($1)), 'đ', 'd') $$;

-- -----------------------------------------------------------------------------
-- 8. Generic trigger functions
-- -----------------------------------------------------------------------------

-- Standard mutable tables: maintain updated_at, forbid tenant_id/id changes.
CREATE OR REPLACE FUNCTION sys.tg_touch()
RETURNS trigger LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION 'tenant_id is immutable (%.%)', TG_TABLE_SCHEMA, TG_TABLE_NAME;
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id THEN
    RAISE EXCEPTION 'id is immutable (%.%)', TG_TABLE_SCHEMA, TG_TABLE_NAME;
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;

-- Platform tables without tenant_id (e.g. saas.tenant): only maintain updated_at.
CREATE OR REPLACE FUNCTION sys.tg_touch_platform()
RETURNS trigger LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id THEN
    RAISE EXCEPTION 'id is immutable (%.%)', TG_TABLE_SCHEMA, TG_TABLE_NAME;
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;

-- Append-only tables (punches, snapshots, published packs ...).
CREATE OR REPLACE FUNCTION sys.tg_forbid_change()
RETURNS trigger LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION '%.% is append-only (% rejected)', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
    USING ERRCODE = '55000';
END $$;

-- Bi-temporal tables (DESIGN §5.2): business columns are immutable; the only legal
-- update is stamping superseded_at/superseded_by (once). DELETE is forbidden.
CREATE OR REPLACE FUNCTION sys.tg_bitemporal_guard()
RETURNS trigger LANGUAGE plpgsql
AS $$
DECLARE
  -- valid_during is a STORED GENERATED column: in BEFORE triggers NEW holds NULL for
  -- it (computed after the trigger), so it must be excluded from the comparison; it is
  -- derived from valid_from/valid_to, which ARE compared. Bi-temporal tables must not
  -- carry other generated columns unless they are added here.
  allowed constant text[] := ARRAY['superseded_at','superseded_by','correction_reason',
                                   'updated_at','updated_by','version','valid_during'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '%.% is bi-temporal: rows are superseded, never deleted', TG_TABLE_SCHEMA, TG_TABLE_NAME
      USING ERRCODE = '55000';
  END IF;
  IF OLD.superseded_at IS NOT NULL THEN
    RAISE EXCEPTION '%.% row % is already superseded', TG_TABLE_SCHEMA, TG_TABLE_NAME, OLD.id
      USING ERRCODE = '55000';
  END IF;
  IF (to_jsonb(NEW) - allowed) IS DISTINCT FROM (to_jsonb(OLD) - allowed) THEN
    RAISE EXCEPTION '%.%: only superseded_at/superseded_by may change; insert a new version instead',
      TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;

-- -----------------------------------------------------------------------------
-- 9. RLS helpers (DESIGN §3.5).  Policies target the group role yhrm_app.
--    Tables get ENABLE + FORCE RLS; with no matching policy access is denied.
--    The (SELECT sys.current_tenant_id()) form becomes an InitPlan evaluated once.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sys.enable_tenant_rls(p_table regclass)
LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY',  p_table);
  EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %s', p_table);
  EXECUTE format($p$
    CREATE POLICY tenant_isolation ON %s
      AS PERMISSIVE FOR ALL TO yhrm_app
      USING      (tenant_id = (SELECT sys.current_tenant_id()))
      WITH CHECK (tenant_id = (SELECT sys.current_tenant_id()))$p$, p_table);
END $$;

-- Global + tenant-overridable reference tables: read own + global rows, write own only.
CREATE OR REPLACE PROCEDURE sys.enable_tenant_or_global_rls(p_table regclass)
LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY',  p_table);
  EXECUTE format('DROP POLICY IF EXISTS tenant_or_global_read ON %s', p_table);
  EXECUTE format('DROP POLICY IF EXISTS tenant_write ON %s', p_table);
  EXECUTE format($p$
    CREATE POLICY tenant_or_global_read ON %s
      AS PERMISSIVE FOR SELECT TO yhrm_app
      USING (tenant_id = (SELECT sys.current_tenant_id()) OR tenant_id = sys.global_tenant_id())$p$, p_table);
  EXECUTE format($p$
    CREATE POLICY tenant_write ON %s
      AS PERMISSIVE FOR ALL TO yhrm_app
      USING      (tenant_id = (SELECT sys.current_tenant_id()))
      WITH CHECK (tenant_id = (SELECT sys.current_tenant_id()))$p$, p_table);
END $$;

-- Read-all policy for technical roles (relay, sealer, reporting snapshot jobs).
CREATE OR REPLACE PROCEDURE sys.grant_rls_read_all(p_table regclass, p_role name)
LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format('DROP POLICY IF EXISTS %I ON %s', 'read_all_' || p_role, p_table);
  EXECUTE format('CREATE POLICY %I ON %s AS PERMISSIVE FOR SELECT TO %I USING (true)',
                 'read_all_' || p_role, p_table, p_role);
END $$;

-- Attach the standard triggers to a mutable tenant table.
CREATE OR REPLACE PROCEDURE sys.attach_touch_trigger(p_table regclass)
LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format('DROP TRIGGER IF EXISTS trg_touch ON %s', p_table);
  EXECUTE format('CREATE TRIGGER trg_touch BEFORE UPDATE ON %s FOR EACH ROW EXECUTE FUNCTION sys.tg_touch()', p_table);
END $$;

CREATE OR REPLACE PROCEDURE sys.attach_bitemporal_guard(p_table regclass)
LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format('DROP TRIGGER IF EXISTS trg_bitemporal_guard ON %s', p_table);
  EXECUTE format('CREATE TRIGGER trg_bitemporal_guard BEFORE UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION sys.tg_bitemporal_guard()', p_table);
END $$;

-- -----------------------------------------------------------------------------
-- 10. Partition helper (fallback when pg_partman is unavailable; DESIGN §10.2)
--     Creates range partitions <parent>_pYYYYMM[DD] for p_count periods from p_from.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sys.create_range_partitions(
  p_parent regclass, p_granularity text, p_from date, p_count int)
LANGUAGE plpgsql
AS $$
DECLARE
  v_schema text; v_table text; v_start date; v_end date; v_name text; i int;
  v_step interval;
BEGIN
  IF p_granularity NOT IN ('day','month','year') THEN
    RAISE EXCEPTION 'granularity must be day|month|year';
  END IF;
  v_step := ('1 ' || p_granularity)::interval;
  SELECT n.nspname, c.relname INTO v_schema, v_table
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = p_parent;
  v_start := date_trunc(p_granularity, p_from)::date;
  FOR i IN 0 .. p_count - 1 LOOP
    v_end  := (v_start + v_step)::date;
    v_name := v_table || '_p' || to_char(v_start, CASE p_granularity WHEN 'day' THEN 'YYYYMMDD'
                                                                       WHEN 'month' THEN 'YYYYMM'
                                                                       ELSE 'YYYY' END);
    IF to_regclass(format('%I.%I', v_schema, v_name)) IS NULL THEN
      EXECUTE format('CREATE TABLE %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                     v_schema, v_name, p_parent, v_start, v_end);
    END IF;
    v_start := v_end;
  END LOOP;
END $$;

-- -----------------------------------------------------------------------------
-- 11. Outbox / inbox template (DESIGN §9.1, ADR-0028). Called once per module schema.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sys.create_outbox_inbox(p_schema name, p_app_role name)
LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format($t$
    CREATE TABLE IF NOT EXISTS %1$I.outbox_event (
      tenant_id        uuid        NOT NULL,
      id               uuid        NOT NULL DEFAULT sys.uuid_v7(),   -- = envelope event_id
      legal_entity_id  uuid,
      aggregate_type   text        NOT NULL,
      aggregate_id     uuid        NOT NULL,                         -- bus partition key
      event_type       text        NOT NULL,                         -- e.g. people.SalaryChanged
      schema_version   int         NOT NULL DEFAULT 1,
      occurred_at      timestamptz NOT NULL DEFAULT now(),
      effective_date   date,
      correlation_id   text,
      causation_id     uuid,
      is_retro         boolean     NOT NULL DEFAULT false,
      person_id        uuid,
      employment_id    uuid,
      payload          jsonb       NOT NULL,
      headers          jsonb       NOT NULL DEFAULT '{}'::jsonb,     -- e.g. {"initial_load":true} (opening-balance import, ADR-0037)
      created_at       timestamptz NOT NULL DEFAULT now(),
      published_at     timestamptz,                                  -- polling relay only
      CONSTRAINT outbox_event_pk PRIMARY KEY (id, created_at),
      CONSTRAINT outbox_event_type_ck CHECK (event_type ~ '^[a-z]+\.[A-Z][A-Za-z]+$')
    ) PARTITION BY RANGE (created_at)$t$, p_schema);

  EXECUTE format('CREATE INDEX IF NOT EXISTS outbox_event_unpublished_ix ON %I.outbox_event (created_at) WHERE published_at IS NULL', p_schema);

  EXECUTE format($t$
    CREATE TABLE IF NOT EXISTS %1$I.inbox_message (
      consumer       text        NOT NULL,          -- handler name, e.g. pay.SalaryProjection
      event_id       uuid        NOT NULL,
      tenant_id      uuid        NOT NULL,
      event_type     text        NOT NULL,
      received_at    timestamptz NOT NULL DEFAULT now(),
      processed_at   timestamptz,
      status         text        NOT NULL DEFAULT 'RECEIVED'
                     CHECK (status IN ('RECEIVED','PROCESSED','FAILED','SKIPPED')),
      attempts       int         NOT NULL DEFAULT 0,
      last_error     text,
      CONSTRAINT inbox_message_pk PRIMARY KEY (consumer, event_id, received_at)
    ) PARTITION BY RANGE (received_at)$t$, p_schema);
  -- NB: a partitioned PK must contain the partition key, so (consumer, event_id) alone
  -- cannot be unique across partitions. The consumer template therefore does:
  --   PERFORM pg_advisory_xact_lock(hashtextextended(consumer || event_id::text, 0));
  --   skip if EXISTS (consumer, event_id) in the 90-day window; else INSERT + apply.
  -- Events older than the window are rejected by the envelope occurred_at check.
  -- RLS note: policies on a partitioned parent do NOT apply to direct partition
  -- access, so app roles are granted on the parent only, never on partitions.
  EXECUTE format('CREATE INDEX IF NOT EXISTS inbox_message_event_ix ON %I.inbox_message (consumer, event_id)', p_schema);

  CALL sys.enable_tenant_rls(format('%I.outbox_event', p_schema)::regclass);
  CALL sys.enable_tenant_rls(format('%I.inbox_message', p_schema)::regclass);
  CALL sys.grant_rls_read_all(format('%I.outbox_event', p_schema)::regclass, 'yhrm_relay');
  EXECUTE format('DROP POLICY IF EXISTS relay_mark_published ON %I.outbox_event', p_schema);
  EXECUTE format($p$CREATE POLICY relay_mark_published ON %I.outbox_event FOR UPDATE TO yhrm_relay USING (true) WITH CHECK (true)$p$, p_schema);

  EXECUTE format('GRANT SELECT, INSERT ON %I.outbox_event TO %I', p_schema, p_app_role);
  EXECUTE format('GRANT SELECT, INSERT, UPDATE ON %I.inbox_message TO %I', p_schema, p_app_role);
  EXECUTE format('GRANT USAGE ON SCHEMA %I TO yhrm_relay', p_schema);
  EXECUTE format('GRANT SELECT, UPDATE (published_at) ON %I.outbox_event TO yhrm_relay', p_schema);

  -- daily outbox partitions (7-day retention), monthly inbox partitions (90 days)
  CALL sys.create_range_partitions(format('%I.outbox_event', p_schema)::regclass, 'day', current_date - 1, 10);
  CALL sys.create_range_partitions(format('%I.inbox_message', p_schema)::regclass, 'month', current_date, 3);
END $$;

RESET ROLE;

-- Policies are evaluated with the current user's privileges; make sure the app
-- group can call the sys functions used inside policies.
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA sys TO yhrm_app, yhrm_platform, yhrm_relay, yhrm_audit_sealer, yhrm_reporting_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE yhrm_owner IN SCHEMA sys
  GRANT EXECUTE ON FUNCTIONS TO yhrm_app, yhrm_platform, yhrm_relay, yhrm_audit_sealer, yhrm_reporting_ro;
-- Procedures are only called by migrations (yhrm_owner); revoke from PUBLIC.
REVOKE EXECUTE ON PROCEDURE sys.enable_tenant_rls(regclass), sys.enable_tenant_or_global_rls(regclass),
       sys.grant_rls_read_all(regclass, name), sys.attach_touch_trigger(regclass),
       sys.attach_bitemporal_guard(regclass), sys.create_range_partitions(regclass, text, date, int),
       sys.create_outbox_inbox(name, name) FROM PUBLIC;
