-- =============================================================================
-- YourHRM  ·  reporting/100_rpt.sql   (owner: SaaS platform — reporting store)
--
-- *** SEPARATE REPORTING DATABASE. Do NOT load into the OLTP database. ***
-- The docker initdb script only loads database/postgresql/ddl/NNN_*.sql (top level),
-- so this file in reporting/ is never picked up by it. See reporting/README.md.
--
-- PostgreSQL 17 (DDL compatible with 16+). DESIGN.md §11, ADR-0012 (Amendment 1,
-- R2-01: Phase 1 feed = native logical replication with column lists, no Kafka),
-- ADR-0032 (model), ADR-0006 (report_scope), D-10 / R2-12 (billable person).
--
-- Layers
--   landing   : schemas named EXACTLY like the OLTP source schemas (core, hr, att,
--               pay, rec). Native logical replication maps a
--               published table to the subscriber table with the SAME schema-qualified
--               name and cannot rename, so the landing tables cannot live in a schema
--               called rpt_raw. Owned by yhrm_cdc (subscription owner = apply role),
--               no RLS, narrow: only the published columns (never P3).
--   rpt_raw   : the stable read layer for the transformation SQL models: one view per
--               landing table (rpt_raw.<schema>_<table>), owned by yhrm_cdc, readable
--               ONLY by yhrm_rpt_transform. If Phase 2 switches to Debezium -> Kafka ->
--               loader, the loader writes tables here directly and the models do not
--               change. No RLS (it is a landing area; DESIGN §11).
--   rpt       : star schema. tenant_id on every fact/dimension row (except the global
--               calendar dim_date), first column of every index, RLS with the SAME
--               transaction-local app.tenant_id context as OLTP. Facts are partitioned
--               by month.
--   rpt_sys   : local copy of the OLTP `sys` helpers that the reporting DB needs
--               (tenant context, RLS template, partition helper). Functions only.
--
-- Roles (created idempotently; in production this is its own cluster)
--   yhrm_cdc            LOGIN REPLICATION: owns the subscriptions, landing tables and
--                       rpt_raw views. Never reads rpt.
--   yhrm_rpt_transform  LOGIN: reads rpt_raw, writes rpt for ALL tenants through an
--                       explicit policy (USING true) — NOT BYPASSRLS (same pattern as
--                       yhrm_relay in OLTP).
--   yhrm_rpt_reader     NOLOGIN group targeted by the tenant RLS policies (read-only).
--   yhrm_reporting_svc  LOGIN, member of yhrm_rpt_reader: the reporting service; sets
--                       app.tenant_id / app.user_id per transaction exactly like OLTP.
--                       Superset (P2) gets its own login role in the same group.
--   yhrm_owner / yhrm_migrator: as in OLTP (owner of rpt objects / Flyway).
--
-- PII: no P3 column exists here (publications are column-listed). Names are P1;
-- amounts are P2 and only exposed through reporting-service subject areas that check
-- the pay permission (DESIGN §9.3). DSR anonymisation updates dim_employee names;
-- tenant purge deletes by tenant_id.
--
-- v0.4 (2026-10-17): first version. Review fix H6 applied: dim_employee carries
-- person_id, and fact_headcount_daily carries person_id + legal_entity_id, so the D-10
-- billable count DISTINCT (legal_entity_id, person_id) on the month-end date is
-- computable (rpt.v_billable_person_month). Smoke tested 2026-10-17 on PGlite 0.5.10
-- (PostgreSQL 18.6) and 0.2.17 (PostgreSQL 16.4), fresh instance: loads, RLS isolation,
-- report_scope view, billable view, SCD2 exclusion, partition helper. The logical
-- replication feed itself (section 10) could not be tested (single-instance PGlite).
--
-- v0.5 (2026-10-21, sponsor decisions on the DDL review, DESIGN §15.1 D4 + D11):
--   * D4  the publisher side now exists: ddl/007_publications.sql (pub_rpt_core / _hr /
--         _att / _pay / _rec, column lists without any *_enc / *_bidx, yhrm_cdc
--         column grants + read-all RLS policy). Landing tables = those column lists 1:1.
--   * D4  new landing schemas att and pay with tables mirroring the published column
--         lists: att.attendance_result, att.attendance_result_line,
--         att.leave_balance_ledger, att.daily_timesheet (minimal); pay.payroll_run,
--         pay.run_employee, pay.result_line, pay.si_contribution, pay.salary_component
--         (+ rpt_raw views att_* / pay_*). Partitioned sources arrive through their
--         root (publish_via_partition_root), so each has ONE plain landing table.
--   * D11 landing core.location_version without min_wage_region_override (M2: dropped in
--         002; dim_location.min_wage_region is derived through the Legal Parameter
--         Service by the model).
--   * D11 rpt.fact_si_contribution: insurance_type SI | HI | UI | OA_OD | TU (was OAI);
--         new column source RUN | OPENING | ADJUSTMENT (M10); contribution_status
--         CONTRIBUTE | EXEMPT | NO_CONTRIBUTION | NOT_PARTICIPATING as in
--         pay.si_contribution (OPENING is a source, no longer a status); run_sk must be
--         NULL for OPENING rows. fact_payroll_employee_month.er_oai_amount renamed
--         er_oa_od_amount (same code as pay).
--   * D11 rpt.dim_pay_run: run_type adds COMPARISON; status is now checked and includes
--         CANCELLED (D3, being added to pay.payroll_run).
--   * rpt.dim_pay_component.component_type adds ALLOWANCE (pay.salary_component type).
--   * Section 10 now points to 007_publications.sql; subscription examples for att/pay.
-- =============================================================================

\set ON_ERROR_STOP on

-- -----------------------------------------------------------------------------
-- 1. Extensions and database defaults
-- -----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- uuid equality inside EXCLUDE (SCD2 no-overlap)
CREATE EXTENSION IF NOT EXISTS ltree;        -- dim_org_unit.path (subtree roll-ups)

DO $$ BEGIN
  EXECUTE format('ALTER DATABASE %I SET timezone TO %L', current_database(), 'UTC');
END $$;

-- -----------------------------------------------------------------------------
-- 2. Roles (cluster-wide, idempotent). Passwords come from the vault, never here.
-- -----------------------------------------------------------------------------
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT * FROM (VALUES
      ('yhrm_owner',          false, true,  'Owns all objects. Used only via SET ROLE by the migrator.'),
      ('yhrm_migrator',       true,  false, 'Flyway / CI-CD only. Member of yhrm_owner.'),
      ('yhrm_cdc',            true,  false, 'Reporting feed: owns subscriptions, landing tables and rpt_raw views (REPLICATION).'),
      ('yhrm_rpt_transform',  true,  false, 'Reporting transformations: reads rpt_raw, writes rpt for all tenants via explicit policy.'),
      ('yhrm_rpt_reader',     false, false, 'Group role targeted by the rpt tenant RLS policies (read-only). NEVER BYPASSRLS.'),
      ('yhrm_reporting_svc',  true,  false, 'Reporting service runtime; member of yhrm_rpt_reader; sets app.tenant_id per transaction.')
    ) AS t(role_name, can_login, bypass, note)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.role_name) THEN
      EXECUTE format('CREATE ROLE %I %s %s NOSUPERUSER NOCREATEDB NOCREATEROLE',
                     r.role_name,
                     CASE WHEN r.can_login THEN 'LOGIN' ELSE 'NOLOGIN' END,
                     CASE WHEN r.bypass    THEN 'BYPASSRLS' ELSE 'NOBYPASSRLS' END);
    END IF;
    -- comments on shared roles are cluster-wide; keep the OLTP wording if the role exists there
    IF r.role_name NOT IN ('yhrm_owner','yhrm_migrator','yhrm_cdc') THEN
      EXECUTE format('COMMENT ON ROLE %I IS %L', r.role_name, r.note);
    END IF;
  END LOOP;
END $$;

GRANT yhrm_owner      TO yhrm_migrator;
GRANT yhrm_rpt_reader TO yhrm_reporting_svc;
ALTER ROLE yhrm_cdc WITH REPLICATION;
ALTER ROLE yhrm_reporting_svc SET statement_timeout = '30s';   -- NFR-SAAS-003: standard reports <= 3 s
ALTER ROLE yhrm_rpt_transform SET statement_timeout = '0';     -- batch models

-- PG16+: CREATE SUBSCRIPTION without superuser (also needs CREATE on the database).
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pg_create_subscription') THEN
    EXECUTE 'GRANT pg_create_subscription TO yhrm_cdc';
  END IF;
  EXECUTE format('GRANT CREATE ON DATABASE %I TO yhrm_cdc', current_database());
END $$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- -----------------------------------------------------------------------------
-- 3. Schemas
-- -----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS rpt_sys AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS rpt     AUTHORIZATION yhrm_owner;
CREATE SCHEMA IF NOT EXISTS rpt_raw AUTHORIZATION yhrm_cdc;
-- landing schemas: names must equal the publisher schemas (logical replication)
CREATE SCHEMA IF NOT EXISTS core    AUTHORIZATION yhrm_cdc;
CREATE SCHEMA IF NOT EXISTS hr      AUTHORIZATION yhrm_cdc;
CREATE SCHEMA IF NOT EXISTS att     AUTHORIZATION yhrm_cdc;
CREATE SCHEMA IF NOT EXISTS pay     AUTHORIZATION yhrm_cdc;
CREATE SCHEMA IF NOT EXISTS rec     AUTHORIZATION yhrm_cdc;

COMMENT ON SCHEMA rpt_sys IS 'Reporting-DB shared kernel: tenant context, RLS template, partition helpers. Functions only.';
COMMENT ON SCHEMA rpt     IS 'Star schema (dims + monthly-partitioned facts). tenant_id + RLS on every row.';
COMMENT ON SCHEMA rpt_raw IS 'Transformation read layer over the logical-replication landing tables. No RLS; yhrm_rpt_transform only.';
COMMENT ON SCHEMA core    IS 'LANDING (logical replication subscriber) of OLTP core: column-listed, no P3, no RLS. yhrm_cdc only.';
COMMENT ON SCHEMA hr      IS 'LANDING (logical replication subscriber) of OLTP hr: column-listed, no P3, no RLS. yhrm_cdc only.';
COMMENT ON SCHEMA att     IS 'LANDING (logical replication subscriber) of OLTP att (pub_rpt_att): column-listed, no P3, no RLS. yhrm_cdc only.';
COMMENT ON SCHEMA pay     IS 'LANDING (logical replication subscriber) of OLTP pay (pub_rpt_pay): column-listed, no P3, no RLS. yhrm_cdc only.';
COMMENT ON SCHEMA rec     IS 'LANDING (logical replication subscriber) of OLTP rec (Phase 2): column-listed, no P3. yhrm_cdc only.';

GRANT USAGE ON SCHEMA rpt_sys TO yhrm_rpt_reader, yhrm_rpt_transform;
GRANT USAGE ON SCHEMA rpt     TO yhrm_rpt_reader, yhrm_rpt_transform;
GRANT USAGE ON SCHEMA rpt_raw TO yhrm_rpt_transform;

-- =============================================================================
-- 4. rpt_sys helpers (owned by yhrm_owner)
-- =============================================================================
SET ROLE yhrm_owner;

-- Same contract as OLTP sys.current_tenant_id(): NULLIF matters for pooled sessions.
CREATE OR REPLACE FUNCTION rpt_sys.current_tenant_id()
RETURNS uuid LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

-- core.user_account id of the report user (report_scope lookups).
CREATE OR REPLACE FUNCTION rpt_sys.current_user_account_id()
RETURNS uuid LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

-- Transaction-local context, identical to OLTP sys.set_context().
CREATE OR REPLACE FUNCTION rpt_sys.set_context(p_tenant uuid, p_user uuid, p_request text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql VOLATILE
AS $$
BEGIN
  IF p_tenant IS NULL THEN
    RAISE EXCEPTION 'tenant context must not be null' USING ERRCODE = '28000';
  END IF;
  PERFORM set_config('app.tenant_id',  p_tenant::text,             true);
  PERFORM set_config('app.user_id',    coalesce(p_user::text, ''), true);
  PERFORM set_config('app.request_id', coalesce(p_request, ''),    true);
END $$;

-- RLS template for rpt tables: readers see their tenant only (fail closed without
-- context); the transformation role reads/writes all tenants through an explicit
-- policy instead of BYPASSRLS.
CREATE OR REPLACE PROCEDURE rpt_sys.enable_tenant_rls(p_table regclass)
LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY',  p_table);
  EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %s', p_table);
  EXECUTE format('DROP POLICY IF EXISTS transform_all ON %s', p_table);
  EXECUTE format($p$
    CREATE POLICY tenant_isolation ON %s
      AS PERMISSIVE FOR SELECT TO yhrm_rpt_reader
      USING (tenant_id = (SELECT rpt_sys.current_tenant_id()))$p$, p_table);
  EXECUTE format($p$
    CREATE POLICY transform_all ON %s
      AS PERMISSIVE FOR ALL TO yhrm_rpt_transform
      USING (true) WITH CHECK (true)$p$, p_table);
  EXECUTE format('GRANT SELECT ON %s TO yhrm_rpt_reader', p_table);
  EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %s TO yhrm_rpt_transform', p_table);
END $$;

-- Monthly range partitions <parent>_pYYYYMM for p_count months from p_from.
-- No DEFAULT partition (out-of-range rows must fail loudly, DESIGN §10.2).
CREATE OR REPLACE PROCEDURE rpt_sys.create_month_partitions(p_parent regclass, p_from date, p_count int)
LANGUAGE plpgsql
AS $$
DECLARE v_schema text; v_table text; v_start date; v_end date; v_name text; i int;
BEGIN
  SELECT n.nspname, c.relname INTO v_schema, v_table
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = p_parent;
  v_start := date_trunc('month', p_from)::date;
  FOR i IN 0 .. p_count - 1 LOOP
    v_end  := (v_start + interval '1 month')::date;
    v_name := v_table || '_p' || to_char(v_start, 'YYYYMM');
    IF to_regclass(format('%I.%I', v_schema, v_name)) IS NULL THEN
      EXECUTE format('CREATE TABLE %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                     v_schema, v_name, p_parent, v_start, v_end);
    END IF;
    v_start := v_end;
  END LOOP;
END $$;

-- Called by the transformation job before loading a month (incl. 10-year payroll
-- backfill): creates the month partitions of EVERY partitioned rpt fact.
-- SECURITY DEFINER because only the owner may create partitions.
CREATE OR REPLACE FUNCTION rpt_sys.ensure_fact_partitions(p_from date, p_count int DEFAULT 1)
RETURNS int LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE r record; n int := 0;
BEGIN
  IF p_count NOT BETWEEN 1 AND 240 THEN
    RAISE EXCEPTION 'p_count must be between 1 and 240';
  END IF;
  FOR r IN SELECT c.oid::regclass AS t
             FROM pg_partitioned_table p
             JOIN pg_class c ON c.oid = p.partrelid
             JOIN pg_namespace s ON s.oid = c.relnamespace
            WHERE s.nspname = 'rpt' AND NOT c.relispartition
  LOOP
    CALL rpt_sys.create_month_partitions(r.t, p_from, p_count);
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;

-- =============================================================================
-- 5. LANDING tables (logical replication subscribers), owned by yhrm_cdc.
--    Column sets = the publication column lists of ddl/007_publications.sql, 1:1
--    (same names; types = the source base types, domains resolved). Each has the
--    source PK so the apply worker can locate rows for UPDATE/DELETE. Generated
--    columns are not replicated (PG < 18) and are therefore absent. Partitioned
--    sources (published via their root) land in ONE plain table.
-- =============================================================================
RESET ROLE;
SET ROLE yhrm_cdc;

-- core ------------------------------------------------------------------------
CREATE TABLE core.legal_entity (
  tenant_id uuid NOT NULL, id uuid NOT NULL, code text NOT NULL, status text NOT NULL,
  CONSTRAINT legal_entity_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE core.legal_entity_version (
  tenant_id uuid NOT NULL, id uuid NOT NULL, legal_entity_id uuid NOT NULL,
  valid_from date NOT NULL, valid_to date, legal_name_vi text NOT NULL, legal_name_en text,
  short_name text, tax_code text NOT NULL, admin_unit_code text,
  CONSTRAINT legal_entity_version_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE core.location (
  tenant_id uuid NOT NULL, id uuid NOT NULL, legal_entity_id uuid NOT NULL, code text NOT NULL, status text NOT NULL,
  CONSTRAINT location_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE core.location_version (
  tenant_id uuid NOT NULL, id uuid NOT NULL, location_id uuid NOT NULL,
  valid_from date NOT NULL, valid_to date, name text NOT NULL, location_type text NOT NULL,
  admin_unit_code text,                         -- D11/M2: no min_wage_region_override (region via LPS)
  CONSTRAINT location_version_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE core.cost_centre (
  tenant_id uuid NOT NULL, id uuid NOT NULL, legal_entity_id uuid NOT NULL, code text NOT NULL,
  name text NOT NULL, gl_segment text, valid_from date NOT NULL, valid_to date,
  CONSTRAINT cost_centre_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE core.org_unit (
  tenant_id uuid NOT NULL, id uuid NOT NULL, code text NOT NULL,
  CONSTRAINT org_unit_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE core.org_unit_version (
  tenant_id uuid NOT NULL, id uuid NOT NULL, org_unit_id uuid NOT NULL,
  valid_from date NOT NULL, valid_to date, name_vi text NOT NULL, name_en text,
  org_unit_type_id uuid NOT NULL, parent_id uuid, path ltree NOT NULL, legal_entity_id uuid NOT NULL,
  location_id uuid, cost_centre_id uuid, status text NOT NULL,
  CONSTRAINT org_unit_version_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE core.position (
  tenant_id uuid NOT NULL, id uuid NOT NULL, code text NOT NULL,
  CONSTRAINT position_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE core.position_version (
  tenant_id uuid NOT NULL, id uuid NOT NULL, position_id uuid NOT NULL,
  valid_from date NOT NULL, valid_to date, title_override text, job_title_id uuid NOT NULL,
  org_unit_id uuid NOT NULL, legal_entity_id uuid NOT NULL, location_id uuid, cost_centre_id uuid,
  fte_budget numeric(6,2) NOT NULL, status text NOT NULL,
  CONSTRAINT position_version_pk PRIMARY KEY (tenant_id, id));

-- hr (NO P3: national id, tax code, SI number, ethnicity, religion, bank are never published)
CREATE TABLE hr.person (
  tenant_id uuid NOT NULL, id uuid NOT NULL, full_name text NOT NULL, birth_date date,
  gender_code text, nationality_code char(2) NOT NULL, is_anonymised boolean NOT NULL,
  CONSTRAINT person_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE hr.employment (
  tenant_id uuid NOT NULL, id uuid NOT NULL, person_id uuid NOT NULL, legal_entity_id uuid NOT NULL,
  employee_no text NOT NULL, hire_type text NOT NULL, hire_date date NOT NULL, end_date date,
  status text NOT NULL, worker_category_code text NOT NULL, employee_category_code text,
  CONSTRAINT employment_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE hr.assignment (
  tenant_id uuid NOT NULL, id uuid NOT NULL, assignment_key uuid NOT NULL, employment_id uuid NOT NULL,
  assignment_type text NOT NULL, legal_entity_id uuid NOT NULL, position_id uuid, org_unit_id uuid NOT NULL,
  job_title_id uuid, grade_id uuid, location_id uuid, cost_centre_id uuid, fte numeric(5,4) NOT NULL,
  action_code text NOT NULL, reason_code text, valid_from date NOT NULL, valid_to date,
  recorded_at timestamptz NOT NULL, superseded_at timestamptz,
  CONSTRAINT assignment_pk PRIMARY KEY (tenant_id, id));

-- rec (Phase 2; funnel only — no candidate PII is published)
CREATE TABLE rec.requisition (
  tenant_id uuid NOT NULL, id uuid NOT NULL, code text NOT NULL, legal_entity_id uuid NOT NULL,
  org_unit_id uuid NOT NULL, position_id uuid, vacancies int NOT NULL, classification text, status text NOT NULL,
  CONSTRAINT requisition_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE rec.job_opening (
  tenant_id uuid NOT NULL, id uuid NOT NULL, requisition_id uuid NOT NULL, code text NOT NULL,
  title_vi text NOT NULL, status text NOT NULL, opened_at timestamptz, closed_at timestamptz,
  CONSTRAINT job_opening_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE rec.pipeline_stage (
  tenant_id uuid NOT NULL, id uuid NOT NULL, seq smallint NOT NULL, code text NOT NULL, stage_type text NOT NULL,
  CONSTRAINT pipeline_stage_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE rec.application (
  tenant_id uuid NOT NULL, id uuid NOT NULL, candidate_id uuid NOT NULL, job_opening_id uuid NOT NULL,
  source_channel text NOT NULL, current_stage_id uuid, status text NOT NULL,
  applied_at timestamptz NOT NULL, closed_at timestamptz, hired_at timestamptz,
  CONSTRAINT application_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE rec.stage_history (
  tenant_id uuid NOT NULL, id uuid NOT NULL, application_id uuid NOT NULL, from_stage_id uuid,
  to_stage_id uuid, from_status text, to_status text NOT NULL, reason_code text, changed_at timestamptz NOT NULL,
  CONSTRAINT stage_history_pk PRIMARY KEY (tenant_id, id));

-- att (pub_rpt_att; sources att.attendance_result_line and att.daily_timesheet are
-- partitioned in OLTP and published via the root)
CREATE TABLE att.attendance_result (
  tenant_id uuid NOT NULL, id uuid NOT NULL, period_id uuid NOT NULL, period_start date NOT NULL,
  scope text NOT NULL, employment_id uuid, result_kind text NOT NULL, result_version int NOT NULL,
  source text NOT NULL, status text NOT NULL, att_date_from date NOT NULL, att_date_to date NOT NULL,
  ot_date_from date, ot_date_to date, legal_pack_id uuid, override_version int NOT NULL,
  supersedes_result_id uuid, close_cause text NOT NULL, sealed_at timestamptz, published_at timestamptz NOT NULL,
  withdrawn_at timestamptz, locked_at timestamptz, locked_by_run_id uuid, unlocked_at timestamptz,
  CONSTRAINT attendance_result_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE att.attendance_result_line (
  tenant_id uuid NOT NULL, id uuid NOT NULL, period_start date NOT NULL, result_id uuid NOT NULL,
  employment_id uuid NOT NULL, assignment_key uuid, segment_from date NOT NULL, segment_to date NOT NULL,
  legal_entity_id uuid NOT NULL, org_unit_id uuid, pay_group_id uuid, cost_centre_id uuid,
  category_code text NOT NULL, quantity numeric(12,2) NOT NULL, unit text NOT NULL, period_quantity numeric(12,2),
  preceded_by_day_ot boolean NOT NULL, is_retro boolean NOT NULL, original_period_id uuid,
  original_result_id uuid, original_segment_from date,
  CONSTRAINT attendance_result_line_pk PRIMARY KEY (tenant_id, id, period_start));
CREATE TABLE att.leave_balance_ledger (
  tenant_id uuid NOT NULL, id uuid NOT NULL, employment_id uuid NOT NULL, legal_entity_id uuid NOT NULL,
  org_unit_id uuid, leave_type_id uuid NOT NULL, leave_type_code text NOT NULL, leave_year smallint NOT NULL,
  txn_type text NOT NULL, quantity numeric(12,4) NOT NULL, unit text NOT NULL, txn_date date NOT NULL,
  accrual_month date, expires_on date, leave_request_id uuid, source text NOT NULL,
  CONSTRAINT leave_balance_ledger_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE att.daily_timesheet (                 -- minimal: no punch times, codes or exception lists
  tenant_id uuid NOT NULL, id uuid NOT NULL, work_date date NOT NULL, employment_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL, org_unit_id uuid, location_id uuid, period_id uuid, shift_id uuid,
  day_type text NOT NULL, day_status text NOT NULL, primary_code text,
  scheduled_hours numeric(12,4) NOT NULL, worked_hours numeric(12,4) NOT NULL, absent_hours numeric(12,4) NOT NULL,
  night_hours numeric(12,4) NOT NULL, late_min int NOT NULL, early_min int NOT NULL, day_value numeric(5,2) NOT NULL,
  calc_version int NOT NULL,
  CONSTRAINT daily_timesheet_pk PRIMARY KEY (tenant_id, id, work_date));

-- pay (pub_rpt_pay; pay.result_line is partitioned in OLTP and published via the root).
-- Amounts are pii:P2 (X-01); only the pay-permission subject areas expose them.
CREATE TABLE pay.payroll_run (
  tenant_id uuid NOT NULL, id uuid NOT NULL, run_no text NOT NULL, pay_group_id uuid NOT NULL,
  pay_period_id uuid NOT NULL, legal_entity_id uuid NOT NULL, period_month date NOT NULL,
  run_type text NOT NULL, run_subtype_code text, calc_mode text NOT NULL, status text NOT NULL,
  tax_month date NOT NULL, payment_date date, currency_code char(3) NOT NULL, legal_pack_id uuid,
  legal_pack_version bigint, override_version int, approved_at timestamptz, paid_at timestamptz,
  closed_at timestamptz, reverses_run_id uuid,
  CONSTRAINT payroll_run_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE pay.run_employee (
  tenant_id uuid NOT NULL, id uuid NOT NULL, run_id uuid NOT NULL, employment_id uuid NOT NULL,
  person_id uuid NOT NULL, legal_entity_id uuid NOT NULL, org_unit_id uuid, cost_centre_id uuid,
  period_month date NOT NULL, status text NOT NULL, pay_basis text NOT NULL,
  gross_amount numeric(19,4) NOT NULL, taxable_income_amount numeric(19,4) NOT NULL,
  si_ee_amount numeric(19,4) NOT NULL, si_er_amount numeric(19,4) NOT NULL, pit_amount numeric(19,4) NOT NULL,
  deduction_amount numeric(19,4) NOT NULL, net_amount numeric(19,4) NOT NULL,
  employer_cost_amount numeric(19,4) NOT NULL, currency_code char(3) NOT NULL,
  CONSTRAINT run_employee_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE pay.result_line (
  tenant_id uuid NOT NULL, id uuid NOT NULL, period_month date NOT NULL, run_id uuid NOT NULL,
  run_employee_id uuid NOT NULL, employment_id uuid NOT NULL, component_id uuid NOT NULL,
  component_code text NOT NULL, segment_key text NOT NULL, segment_from date, segment_to date,
  qty numeric(12,4), rate numeric(24,8), amount numeric(19,4) NOT NULL, taxable_amount numeric(19,4),
  exempt_amount numeric(19,4), si_basis_amount numeric(19,4), tu_basis_amount numeric(19,4), retro_of_period date,
  CONSTRAINT result_line_pk PRIMARY KEY (tenant_id, id, period_month));
CREATE TABLE pay.si_contribution (
  tenant_id uuid NOT NULL, id uuid NOT NULL, employment_id uuid NOT NULL, person_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL, contribution_month date NOT NULL, insurance_type text NOT NULL,
  source text NOT NULL, status text NOT NULL, exemption_reason_code text, basis_amount numeric(19,4) NOT NULL,
  ee_rate numeric(12,8), er_rate numeric(12,8), ee_amount numeric(19,4) NOT NULL, er_amount numeric(19,4) NOT NULL,
  currency_code char(3) NOT NULL, run_id uuid, adjusts_id uuid, superseded_at timestamptz,
  CONSTRAINT si_contribution_pk PRIMARY KEY (tenant_id, id));
CREATE TABLE pay.salary_component (
  tenant_id uuid NOT NULL, id uuid NOT NULL, code text NOT NULL, name text NOT NULL, name_en text,
  component_type text NOT NULL, is_system boolean NOT NULL, status text NOT NULL,
  CONSTRAINT salary_component_pk PRIMARY KEY (tenant_id, id));

-- rpt_raw: the transformation read layer (views run with the owner's = yhrm_cdc rights)
CREATE VIEW rpt_raw.core_legal_entity          AS SELECT * FROM core.legal_entity;
CREATE VIEW rpt_raw.core_legal_entity_version  AS SELECT * FROM core.legal_entity_version;
CREATE VIEW rpt_raw.core_location              AS SELECT * FROM core.location;
CREATE VIEW rpt_raw.core_location_version      AS SELECT * FROM core.location_version;
CREATE VIEW rpt_raw.core_cost_centre           AS SELECT * FROM core.cost_centre;
CREATE VIEW rpt_raw.core_org_unit              AS SELECT * FROM core.org_unit;
CREATE VIEW rpt_raw.core_org_unit_version      AS SELECT * FROM core.org_unit_version;
CREATE VIEW rpt_raw.core_position              AS SELECT * FROM core.position;
CREATE VIEW rpt_raw.core_position_version      AS SELECT * FROM core.position_version;
CREATE VIEW rpt_raw.hr_person                  AS SELECT * FROM hr.person;
CREATE VIEW rpt_raw.hr_employment              AS SELECT * FROM hr.employment;
CREATE VIEW rpt_raw.hr_assignment              AS SELECT * FROM hr.assignment;
CREATE VIEW rpt_raw.att_attendance_result      AS SELECT * FROM att.attendance_result;
CREATE VIEW rpt_raw.att_attendance_result_line AS SELECT * FROM att.attendance_result_line;
CREATE VIEW rpt_raw.att_leave_balance_ledger   AS SELECT * FROM att.leave_balance_ledger;
CREATE VIEW rpt_raw.att_daily_timesheet        AS SELECT * FROM att.daily_timesheet;
CREATE VIEW rpt_raw.pay_payroll_run            AS SELECT * FROM pay.payroll_run;
CREATE VIEW rpt_raw.pay_run_employee           AS SELECT * FROM pay.run_employee;
CREATE VIEW rpt_raw.pay_result_line            AS SELECT * FROM pay.result_line;
CREATE VIEW rpt_raw.pay_si_contribution        AS SELECT * FROM pay.si_contribution;
CREATE VIEW rpt_raw.pay_salary_component       AS SELECT * FROM pay.salary_component;
CREATE VIEW rpt_raw.rec_requisition            AS SELECT * FROM rec.requisition;
CREATE VIEW rpt_raw.rec_job_opening            AS SELECT * FROM rec.job_opening;
CREATE VIEW rpt_raw.rec_pipeline_stage         AS SELECT * FROM rec.pipeline_stage;
CREATE VIEW rpt_raw.rec_application            AS SELECT * FROM rec.application;
CREATE VIEW rpt_raw.rec_stage_history          AS SELECT * FROM rec.stage_history;
GRANT SELECT ON ALL TABLES IN SCHEMA rpt_raw TO yhrm_rpt_transform;   -- views only live here
RESET ROLE;

-- =============================================================================
-- 6. DIMENSIONS  (rpt, owned by yhrm_owner).  Surrogate keys are bigint identity;
--    natural ids are kept. SCD2 ranges are half-open [scd_valid_from, scd_valid_to).
--    No FKs inside rpt: loads are idempotent bulk replaces checked by model tests.
-- =============================================================================
SET ROLE yhrm_owner;
SET search_path = rpt, rpt_sys, public;

-- Global calendar (the only rpt table without tenant_id; holidays are per tenant and
-- come from T&A as an attribute of fact_attendance_daily).
CREATE TABLE rpt.dim_date (
  date_key        int  NOT NULL,                       -- YYYYMMDD
  calendar_date   date NOT NULL,
  year            smallint NOT NULL,
  quarter         smallint NOT NULL,
  month           smallint NOT NULL,
  month_start     date NOT NULL,
  month_end       date NOT NULL,
  year_month      int  NOT NULL,                       -- YYYYMM
  day_of_month    smallint NOT NULL,
  iso_dow         smallint NOT NULL,                   -- 1 = Monday
  iso_week        smallint NOT NULL,
  is_weekend      boolean NOT NULL,                    -- Sat/Sun (weekly rest day is per schedule, not here)
  is_month_end    boolean NOT NULL,
  CONSTRAINT dim_date_pk PRIMARY KEY (date_key),
  CONSTRAINT dim_date_uq UNIQUE (calendar_date)
);
INSERT INTO rpt.dim_date
SELECT to_char(d, 'YYYYMMDD')::int, d::date, extract(year FROM d)::smallint, extract(quarter FROM d)::smallint,
       extract(month FROM d)::smallint, date_trunc('month', d)::date,
       (date_trunc('month', d) + interval '1 month - 1 day')::date, to_char(d, 'YYYYMM')::int,
       extract(day FROM d)::smallint, extract(isodow FROM d)::smallint, extract(week FROM d)::smallint,
       extract(isodow FROM d) IN (6, 7),
       d::date = (date_trunc('month', d) + interval '1 month - 1 day')::date
  FROM generate_series(DATE '2016-01-01', DATE '2040-12-31', interval '1 day') AS g(d)
ON CONFLICT DO NOTHING;
GRANT SELECT ON rpt.dim_date TO yhrm_rpt_reader, yhrm_rpt_transform;

-- Employee (SCD2, one row per employment version). H6: person_id is required for the
-- D-10 billable count DISTINCT (legal_entity_id, person_id).
CREATE TABLE rpt.dim_employee (
  employee_sk             bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id               uuid NOT NULL,
  employment_id           uuid NOT NULL,               -- natural key; report_scope joins on it
  person_id               uuid NOT NULL,               -- review fix H6
  legal_entity_id         uuid NOT NULL,               -- employing LE (billing unit, D-10)
  employee_no             text NOT NULL,
  full_name               text NOT NULL,               -- pii:P1 (DSR anonymiser overwrites)
  gender_code             text,
  birth_year              smallint,                    -- year only (minimisation)
  nationality_code        char(2),
  worker_category_code    text,
  employee_category_code  text,
  hire_date               date NOT NULL,
  end_date                date,                        -- INCLUSIVE termination date
  employment_status       text NOT NULL,
  org_unit_id             uuid,                        -- primary assignment attributes at this version
  position_id             uuid,
  job_title_id            uuid,
  grade_id                uuid,
  location_id             uuid,
  cost_centre_id          uuid,
  fte                     numeric(5,4),
  is_anonymised           boolean NOT NULL DEFAULT false,
  scd_valid_from          date NOT NULL,
  scd_valid_to            date,                        -- EXCLUSIVE; NULL = current
  is_current              boolean NOT NULL DEFAULT true,
  row_hash                bytea,                       -- change detection in the model
  loaded_at               timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_employee_pk PRIMARY KEY (tenant_id, employee_sk),
  CONSTRAINT dim_employee_status_ck CHECK (employment_status IN ('PRE_HIRE','ACTIVE','SUSPENDED','TERMINATED','CANCELLED')),
  CONSTRAINT dim_employee_period_ck CHECK (scd_valid_to IS NULL OR scd_valid_to > scd_valid_from),
  CONSTRAINT dim_employee_current_ck CHECK (is_current = (scd_valid_to IS NULL)),
  CONSTRAINT dim_employee_scd_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, daterange(scd_valid_from, scd_valid_to, '[)') WITH &&)
);
CREATE UNIQUE INDEX dim_employee_current_uq ON rpt.dim_employee (tenant_id, employment_id) WHERE is_current;
CREATE INDEX dim_employee_asof_ix  ON rpt.dim_employee (tenant_id, employment_id, scd_valid_from);
CREATE INDEX dim_employee_person_ix ON rpt.dim_employee (tenant_id, person_id, legal_entity_id);
COMMENT ON COLUMN rpt.dim_employee.person_id IS 'Review fix H6: D-10 billable unit = DISTINCT (legal_entity_id, person_id) at month end.';

CREATE TABLE rpt.dim_org_unit (
  org_unit_sk          bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id            uuid NOT NULL,
  org_unit_id          uuid NOT NULL,
  code                 text NOT NULL,
  name_vi              text NOT NULL,
  name_en              text,
  parent_org_unit_id   uuid,
  path                 ltree NOT NULL,                 -- materialised root..self (labels = ids without hyphens)
  depth                smallint GENERATED ALWAYS AS (nlevel(path)) STORED,
  legal_entity_id      uuid NOT NULL,
  location_id          uuid,
  cost_centre_id       uuid,
  status               text NOT NULL,
  scd_valid_from       date NOT NULL,
  scd_valid_to         date,
  is_current           boolean NOT NULL DEFAULT true,
  loaded_at            timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_org_unit_pk PRIMARY KEY (tenant_id, org_unit_sk),
  CONSTRAINT dim_org_unit_period_ck CHECK (scd_valid_to IS NULL OR scd_valid_to > scd_valid_from),
  CONSTRAINT dim_org_unit_current_ck CHECK (is_current = (scd_valid_to IS NULL)),
  CONSTRAINT dim_org_unit_scd_ex EXCLUDE USING gist
    (tenant_id WITH =, org_unit_id WITH =, daterange(scd_valid_from, scd_valid_to, '[)') WITH &&)
);
CREATE INDEX dim_org_unit_asof_ix ON rpt.dim_org_unit (tenant_id, org_unit_id, scd_valid_from);
CREATE INDEX dim_org_unit_path_gix ON rpt.dim_org_unit USING gist (path);   -- subtree roll-ups: path <@ :root

CREATE TABLE rpt.dim_legal_entity (
  legal_entity_sk   bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id         uuid NOT NULL,
  legal_entity_id   uuid NOT NULL,
  code              text NOT NULL,
  legal_name_vi     text NOT NULL,
  legal_name_en     text,
  short_name        text,
  tax_code          text NOT NULL,                     -- company MST (not personal data)
  admin_unit_code   text,
  status            text NOT NULL,
  scd_valid_from    date NOT NULL,
  scd_valid_to      date,
  is_current        boolean NOT NULL DEFAULT true,
  loaded_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_legal_entity_pk PRIMARY KEY (tenant_id, legal_entity_sk),
  CONSTRAINT dim_legal_entity_period_ck CHECK (scd_valid_to IS NULL OR scd_valid_to > scd_valid_from),
  CONSTRAINT dim_legal_entity_current_ck CHECK (is_current = (scd_valid_to IS NULL)),
  CONSTRAINT dim_legal_entity_scd_ex EXCLUDE USING gist
    (tenant_id WITH =, legal_entity_id WITH =, daterange(scd_valid_from, scd_valid_to, '[)') WITH &&)
);
CREATE INDEX dim_legal_entity_asof_ix ON rpt.dim_legal_entity (tenant_id, legal_entity_id, scd_valid_from);

-- Position (SCD2, Phase 2 analytics: vacancy / budget vs actual)
CREATE TABLE rpt.dim_position (
  position_sk       bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id         uuid NOT NULL,
  position_id       uuid NOT NULL,
  code              text NOT NULL,
  title             text,
  job_title_id      uuid,
  org_unit_id       uuid,
  legal_entity_id   uuid,
  location_id       uuid,
  cost_centre_id    uuid,
  fte_budget        numeric(6,2),
  status            text NOT NULL,
  scd_valid_from    date NOT NULL,
  scd_valid_to      date,
  is_current        boolean NOT NULL DEFAULT true,
  loaded_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_position_pk PRIMARY KEY (tenant_id, position_sk),
  CONSTRAINT dim_position_period_ck CHECK (scd_valid_to IS NULL OR scd_valid_to > scd_valid_from),
  CONSTRAINT dim_position_current_ck CHECK (is_current = (scd_valid_to IS NULL)),
  CONSTRAINT dim_position_scd_ex EXCLUDE USING gist
    (tenant_id WITH =, position_id WITH =, daterange(scd_valid_from, scd_valid_to, '[)') WITH &&)
);
CREATE INDEX dim_position_asof_ix ON rpt.dim_position (tenant_id, position_id, scd_valid_from);

-- SCD1 dimensions ---------------------------------------------------------------
CREATE TABLE rpt.dim_location (
  location_sk        bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id          uuid NOT NULL,
  location_id        uuid NOT NULL,
  legal_entity_id    uuid NOT NULL,
  code               text NOT NULL,
  name               text NOT NULL,
  location_type      text,
  admin_unit_code    text,
  province_code      text,                           -- roll-up from admin unit
  min_wage_region    text,                           -- I..IV resolved by the model
  status             text NOT NULL,
  loaded_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_location_pk PRIMARY KEY (tenant_id, location_sk),
  CONSTRAINT dim_location_uq UNIQUE (tenant_id, location_id),
  CONSTRAINT dim_location_region_ck CHECK (min_wage_region IS NULL OR min_wage_region IN ('I','II','III','IV'))
);

CREATE TABLE rpt.dim_cost_centre (
  cost_centre_sk     bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id          uuid NOT NULL,
  cost_centre_id     uuid NOT NULL,
  legal_entity_id    uuid NOT NULL,
  code               text NOT NULL,
  name               text NOT NULL,
  gl_segment         text,
  valid_from         date,
  valid_to           date,
  loaded_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_cost_centre_pk PRIMARY KEY (tenant_id, cost_centre_sk),
  CONSTRAINT dim_cost_centre_uq UNIQUE (tenant_id, cost_centre_id)
);

-- Pay components are materialised PER TENANT (global template rows are copied), so the
-- dimension stays pure tenant-owned under RLS.
CREATE TABLE rpt.dim_pay_component (
  component_sk       bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id          uuid NOT NULL,
  component_code     text NOT NULL,                  -- pay.salary_component code
  name_vi            text NOT NULL,
  name_en            text,
  component_type     text NOT NULL,
  component_group    text,                           -- BASIC | ALLOWANCE | OT | BONUS | SI | PIT | LOAN | OTHER
  is_taxable         boolean NOT NULL DEFAULT false,
  is_si_basis        boolean NOT NULL DEFAULT false,
  gl_category        text,
  display_order      int,
  loaded_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_pay_component_pk PRIMARY KEY (tenant_id, component_sk),
  CONSTRAINT dim_pay_component_uq UNIQUE (tenant_id, component_code),
  -- pay.salary_component types + NET (derived total line of the model)
  CONSTRAINT dim_pay_component_type_ck CHECK (component_type IN ('EARNING','ALLOWANCE','DEDUCTION','EMPLOYER_CONTRIBUTION',
                                                                 'INFORMATION','NET'))
);

-- Attendance codes = the T&A result categories (BRD 03 §7.3 shared catalogue):
-- WORK, NIGHT (NW), the six OT categories, leave/absence codes ...
CREATE TABLE rpt.dim_att_code (
  att_code_sk        bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id          uuid NOT NULL,
  category_code      text NOT NULL,                  -- e.g. WORK, NIGHT_HOURS, OT_HOURS.OT-WD, LEAVE.AL
  name_vi            text NOT NULL,
  name_en            text,
  category_group     text NOT NULL,
  ot_category        text,                           -- set for the six OT codes only
  unit               text NOT NULL,
  is_paid            boolean,
  loaded_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_att_code_pk PRIMARY KEY (tenant_id, att_code_sk),
  CONSTRAINT dim_att_code_uq UNIQUE (tenant_id, category_code),
  CONSTRAINT dim_att_code_group_ck CHECK (category_group IN ('WORKED','NIGHT','OT','LEAVE','ABSENCE','HOLIDAY',
                                                             'TRIP','REMOTE','OTHER')),
  CONSTRAINT dim_att_code_ot_ck CHECK ((category_group = 'OT') = (ot_category IS NOT NULL)),
  CONSTRAINT dim_att_code_otcat_ck CHECK (ot_category IS NULL OR
                                          ot_category IN ('OT-WD','OT-RD','OT-PH','OT-WD-N','OT-RD-N','OT-PH-N')),
  CONSTRAINT dim_att_code_unit_ck CHECK (unit IN ('HOUR','DAY','MINUTE','COUNT'))
);

CREATE TABLE rpt.dim_leave_type (
  leave_type_sk      bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id          uuid NOT NULL,
  leave_type_code    text NOT NULL,
  name_vi            text NOT NULL,
  name_en            text,
  is_paid            boolean NOT NULL,
  is_statutory       boolean NOT NULL DEFAULT false,
  is_si_benefit      boolean NOT NULL DEFAULT false, -- paid by the SI fund (sickness, maternity)
  unit               text NOT NULL DEFAULT 'DAY',
  loaded_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_leave_type_pk PRIMARY KEY (tenant_id, leave_type_sk),
  CONSTRAINT dim_leave_type_uq UNIQUE (tenant_id, leave_type_code),
  CONSTRAINT dim_leave_type_unit_ck CHECK (unit IN ('DAY','HALF_DAY','HOUR'))
);

-- Payroll runs. Only APPROVED (and later) runs feed the payroll facts by default;
-- provisional runs are flagged (ADR-0032 decision 6).
CREATE TABLE rpt.dim_pay_run (
  run_sk             bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id          uuid NOT NULL,
  payroll_run_id     uuid NOT NULL,
  pay_period_id      uuid,
  pay_group_id       uuid,
  legal_entity_id    uuid NOT NULL,
  period_month       date NOT NULL,
  run_type           text NOT NULL,
  calc_mode          text NOT NULL DEFAULT 'FINAL',
  status             text NOT NULL,
  is_provisional     boolean NOT NULL DEFAULT false,
  legal_pack_id      uuid,
  override_version   int,
  approved_at        timestamptz,
  paid_at            timestamptz,
  loaded_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dim_pay_run_pk PRIMARY KEY (tenant_id, run_sk),
  CONSTRAINT dim_pay_run_uq UNIQUE (tenant_id, payroll_run_id),
  CONSTRAINT dim_pay_run_month_ck CHECK (extract(day FROM period_month) = 1),
  -- D11: = pay.payroll_run.run_type (COMPARISON runs are never approved; excluded from
  -- payroll facts by default, kept for the parallel-run comparison reports)
  CONSTRAINT dim_pay_run_type_ck CHECK (run_type IN ('REGULAR','OFFCYCLE','FINAL_SETTLEMENT','BONUS','CORRECTION',
                                                     'COMPARISON')),
  CONSTRAINT dim_pay_run_mode_ck CHECK (calc_mode IN ('PROVISIONAL','FINAL')),
  -- D11 + D3: = pay.payroll_run.status incl. CANCELLED
  CONSTRAINT dim_pay_run_status_ck CHECK (status IN ('DRAFT','CALCULATED','SUBMITTED','APPROVED','PAID','CLOSED',
                                                     'REJECTED','UNLOCKED','REVERSED','CANCELLED'))
);
CREATE INDEX dim_pay_run_period_ix ON rpt.dim_pay_run (tenant_id, legal_entity_id, period_month);

-- =============================================================================
-- 7. FACTS  (monthly range partitions; PK includes the partition key and starts
--    with tenant_id). Grain is stated per table.
-- =============================================================================

-- Periodic snapshot: one row per employment per day (D-10 billing source).
CREATE TABLE rpt.fact_headcount_daily (
  tenant_id          uuid NOT NULL,
  snapshot_date      date NOT NULL,                    -- partition key
  date_key           int  NOT NULL,
  employment_id      uuid NOT NULL,
  person_id          uuid NOT NULL,                    -- H6 (denormalised for the billable count)
  legal_entity_id    uuid NOT NULL,                    -- H6: employing LE (billing unit)
  employee_sk        bigint NOT NULL,
  legal_entity_sk    bigint NOT NULL,
  org_unit_sk        bigint,
  position_sk        bigint,
  location_sk        bigint,
  cost_centre_sk     bigint,
  employment_status  text NOT NULL,
  fte                numeric(5,4) NOT NULL DEFAULT 1,
  headcount          smallint NOT NULL DEFAULT 1,
  is_billable        boolean NOT NULL DEFAULT false,
  CONSTRAINT fact_headcount_daily_pk PRIMARY KEY (tenant_id, snapshot_date, employment_id),
  CONSTRAINT fact_headcount_daily_status_ck CHECK (employment_status IN ('PRE_HIRE','ACTIVE','SUSPENDED','TERMINATED','CANCELLED')),
  -- D-10 + R2-12a: only ACTIVE employments are billable (SUSPENDED is not)
  CONSTRAINT fact_headcount_daily_billable_ck CHECK (NOT is_billable OR employment_status = 'ACTIVE')
) PARTITION BY RANGE (snapshot_date);
CREATE INDEX fact_headcount_daily_le_ix ON rpt.fact_headcount_daily (tenant_id, legal_entity_id, snapshot_date) WHERE is_billable;
CREATE INDEX fact_headcount_daily_org_ix ON rpt.fact_headcount_daily (tenant_id, org_unit_sk, snapshot_date);

-- Accumulating events: hire, termination, transfer, promotion ... (one row per event).
CREATE TABLE rpt.fact_movement (
  tenant_id            uuid NOT NULL,
  effective_date       date NOT NULL,                  -- partition key
  movement_id          uuid NOT NULL,                  -- source assignment version / decision id
  date_key             int  NOT NULL,
  employment_id        uuid NOT NULL,
  person_id            uuid NOT NULL,
  employee_sk          bigint NOT NULL,
  legal_entity_sk      bigint NOT NULL,
  movement_type        text NOT NULL,
  reason_code          text,
  is_voluntary         boolean,                        -- terminations
  from_org_unit_sk     bigint,
  to_org_unit_sk       bigint,
  from_position_sk     bigint,
  to_position_sk       bigint,
  from_grade_id        uuid,
  to_grade_id          uuid,
  headcount_delta      smallint NOT NULL DEFAULT 0,    -- +1 hire, -1 termination, 0 internal move
  is_retro             boolean NOT NULL DEFAULT false, -- recorded after the effective month closed
  CONSTRAINT fact_movement_pk PRIMARY KEY (tenant_id, effective_date, movement_id),
  CONSTRAINT fact_movement_type_ck CHECK (movement_type IN ('HIRE','REHIRE','TERMINATION','TRANSFER','PROMOTION',
                                                            'DEMOTION','LE_TRANSFER','SECONDMENT','SUSPENSION',
                                                            'RETURN_FROM_SUSPENSION','CONCURRENT_START','CONCURRENT_END',
                                                            'DATA_CHANGE')),
  CONSTRAINT fact_movement_delta_ck CHECK (headcount_delta BETWEEN -1 AND 1)
) PARTITION BY RANGE (effective_date);
CREATE INDEX fact_movement_type_ix ON rpt.fact_movement (tenant_id, movement_type, effective_date);
CREATE INDEX fact_movement_employment_ix ON rpt.fact_movement (tenant_id, employment_id);

-- Payroll result lines: employee × run × component × segment (10 years online here).
CREATE TABLE rpt.fact_payroll_line (
  tenant_id          uuid NOT NULL,
  period_month       date NOT NULL,                    -- partition key (first day of month)
  result_line_id     uuid NOT NULL,                    -- pay.result_line.id
  period_key         int  NOT NULL,                    -- dim_date key of period_month
  run_sk             bigint NOT NULL,
  employee_sk        bigint NOT NULL,
  employment_id      uuid NOT NULL,
  legal_entity_sk    bigint NOT NULL,
  org_unit_sk        bigint,
  cost_centre_sk     bigint,
  component_sk       bigint NOT NULL,
  component_code     text NOT NULL,
  currency_code      char(3) NOT NULL DEFAULT 'VND',
  qty                numeric(12,4),
  rate               numeric(19,4),
  amount             numeric(19,4) NOT NULL,           -- pii:P2
  taxable_amount     numeric(19,4),
  si_basis_amount    numeric(19,4),
  is_retro           boolean NOT NULL DEFAULT false,
  retro_of_period    date,                             -- original period of a retro delta
  is_provisional     boolean NOT NULL DEFAULT false,   -- excluded by default (ADR-0032 decision 6)
  CONSTRAINT fact_payroll_line_pk PRIMARY KEY (tenant_id, period_month, result_line_id),
  CONSTRAINT fact_payroll_line_month_ck CHECK (extract(day FROM period_month) = 1),
  CONSTRAINT fact_payroll_line_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
  CONSTRAINT fact_payroll_line_retro_ck CHECK (is_retro = (retro_of_period IS NOT NULL))
) PARTITION BY RANGE (period_month);
CREATE INDEX fact_payroll_line_employment_ix ON rpt.fact_payroll_line (tenant_id, employment_id, period_month);
CREATE INDEX fact_payroll_line_component_ix ON rpt.fact_payroll_line (tenant_id, component_sk, period_month);
CREATE INDEX fact_payroll_line_le_ix ON rpt.fact_payroll_line (tenant_id, legal_entity_sk, period_month);

-- Employee × month summary over all approved runs of the month.
CREATE TABLE rpt.fact_payroll_employee_month (
  tenant_id               uuid NOT NULL,
  period_month            date NOT NULL,               -- partition key
  employment_id           uuid NOT NULL,
  period_key              int  NOT NULL,
  person_id               uuid NOT NULL,
  employee_sk             bigint NOT NULL,
  legal_entity_sk         bigint NOT NULL,
  org_unit_sk             bigint,
  cost_centre_sk          bigint,
  currency_code           char(3) NOT NULL DEFAULT 'VND',
  gross_amount            numeric(19,4) NOT NULL DEFAULT 0,   -- all amounts pii:P2
  taxable_income_amount   numeric(19,4) NOT NULL DEFAULT 0,
  pit_amount              numeric(19,4) NOT NULL DEFAULT 0,
  ee_si_amount            numeric(19,4) NOT NULL DEFAULT 0,   -- BHXH
  ee_hi_amount            numeric(19,4) NOT NULL DEFAULT 0,   -- BHYT
  ee_ui_amount            numeric(19,4) NOT NULL DEFAULT 0,   -- BHTN
  er_si_amount            numeric(19,4) NOT NULL DEFAULT 0,
  er_hi_amount            numeric(19,4) NOT NULL DEFAULT 0,
  er_ui_amount            numeric(19,4) NOT NULL DEFAULT 0,
  er_oa_od_amount         numeric(19,4) NOT NULL DEFAULT 0,   -- TNLĐ-BNN (insurance_type OA_OD; was er_oai_amount, D11)
  union_fee_er_amount     numeric(19,4) NOT NULL DEFAULT 0,   -- KPCĐ 2 % (insurance_type TU)
  net_amount              numeric(19,4) NOT NULL DEFAULT 0,
  employer_cost_amount    numeric(19,4) NOT NULL DEFAULT 0,
  run_count               smallint NOT NULL DEFAULT 1,
  has_retro               boolean NOT NULL DEFAULT false,
  CONSTRAINT fact_payroll_employee_month_pk PRIMARY KEY (tenant_id, period_month, employment_id, currency_code),
  CONSTRAINT fact_payroll_employee_month_month_ck CHECK (extract(day FROM period_month) = 1)
) PARTITION BY RANGE (period_month);
CREATE INDEX fact_payroll_employee_month_le_ix ON rpt.fact_payroll_employee_month (tenant_id, legal_entity_sk, period_month);

-- One row per employment per work day (locked/closed attendance results; provisional flagged).
-- OT is stored BOTH ways: the six statutory categories as columns (fast dashboards,
-- BR-ATT-041) and, at category grain, in fact_ot via dim_att_code.
CREATE TABLE rpt.fact_attendance_daily (
  tenant_id              uuid NOT NULL,
  work_date              date NOT NULL,                -- partition key
  employment_id          uuid NOT NULL,
  date_key               int  NOT NULL,
  employee_sk            bigint NOT NULL,
  legal_entity_sk        bigint NOT NULL,
  org_unit_sk            bigint,
  location_sk            bigint,
  att_code_sk            bigint,                       -- day status code (WORK / LEAVE.AL / ABSENT ...)
  shift_code             text,
  is_holiday             boolean NOT NULL DEFAULT false,
  is_rest_day            boolean NOT NULL DEFAULT false,
  scheduled_hours        numeric(6,2),
  worked_hours           numeric(6,2) NOT NULL DEFAULT 0,
  night_hours            numeric(6,2) NOT NULL DEFAULT 0,     -- NW: night work in normal hours
  ot_wd_hours            numeric(6,2) NOT NULL DEFAULT 0,     -- OT-WD   weekday, day
  ot_rd_hours            numeric(6,2) NOT NULL DEFAULT 0,     -- OT-RD   weekly rest day, day
  ot_ph_hours            numeric(6,2) NOT NULL DEFAULT 0,     -- OT-PH   public / paid holiday, day
  ot_wd_n_hours          numeric(6,2) NOT NULL DEFAULT 0,     -- OT-WD-N weekday, night
  ot_rd_n_hours          numeric(6,2) NOT NULL DEFAULT 0,     -- OT-RD-N rest day, night
  ot_ph_n_hours          numeric(6,2) NOT NULL DEFAULT 0,     -- OT-PH-N holiday, night
  ot_hours               numeric(7,2) GENERATED ALWAYS AS
                           (ot_wd_hours + ot_rd_hours + ot_ph_hours + ot_wd_n_hours + ot_rd_n_hours + ot_ph_n_hours) STORED,
  leave_hours            numeric(6,2) NOT NULL DEFAULT 0,
  late_min               int NOT NULL DEFAULT 0,
  early_leave_min        int NOT NULL DEFAULT 0,
  day_value              numeric(6,4),                 -- paid day value (1, 0.5 ...)
  attendance_result_version int,
  is_provisional         boolean NOT NULL DEFAULT false,
  CONSTRAINT fact_attendance_daily_pk PRIMARY KEY (tenant_id, work_date, employment_id),
  CONSTRAINT fact_attendance_daily_hours_ck CHECK (worked_hours >= 0 AND night_hours >= 0 AND ot_wd_hours >= 0
    AND ot_rd_hours >= 0 AND ot_ph_hours >= 0 AND ot_wd_n_hours >= 0 AND ot_rd_n_hours >= 0 AND ot_ph_n_hours >= 0)
) PARTITION BY RANGE (work_date);
CREATE INDEX fact_attendance_daily_employment_ix ON rpt.fact_attendance_daily (tenant_id, employment_id, work_date);
CREATE INDEX fact_attendance_daily_org_ix ON rpt.fact_attendance_daily (tenant_id, org_unit_sk, work_date);

-- OT at category grain: one row per approved/paid OT result line.
CREATE TABLE rpt.fact_ot (
  tenant_id          uuid NOT NULL,
  work_date          date NOT NULL,                    -- partition key
  ot_result_id       uuid NOT NULL,                    -- att.overtime_result id
  date_key           int  NOT NULL,
  employment_id      uuid NOT NULL,
  employee_sk        bigint NOT NULL,
  legal_entity_sk    bigint NOT NULL,
  org_unit_sk        bigint,
  att_code_sk        bigint NOT NULL,                  -- the OT category code in dim_att_code
  ot_category        text NOT NULL,
  requested_hours    numeric(6,2),
  approved_hours     numeric(6,2),
  actual_hours       numeric(6,2),
  paid_hours         numeric(6,2) NOT NULL DEFAULT 0,  -- min(approved, actual) unless policy differs
  toil_hours         numeric(6,2) NOT NULL DEFAULT 0,  -- converted to time off, not paid
  has_prior_day_ot   boolean NOT NULL DEFAULT false,   -- VN-11 night-OT base (BR-ATT-042)
  is_provisional     boolean NOT NULL DEFAULT false,
  CONSTRAINT fact_ot_pk PRIMARY KEY (tenant_id, work_date, ot_result_id),
  CONSTRAINT fact_ot_category_ck CHECK (ot_category IN ('OT-WD','OT-RD','OT-PH','OT-WD-N','OT-RD-N','OT-PH-N'))
) PARTITION BY RANGE (work_date);
CREATE INDEX fact_ot_employment_ix ON rpt.fact_ot (tenant_id, employment_id, work_date);   -- 40h/month, 200/300h/year caps

-- Leave taken: one row per leave request per day.
CREATE TABLE rpt.fact_leave (
  tenant_id          uuid NOT NULL,
  leave_date         date NOT NULL,                    -- partition key
  leave_request_id   uuid NOT NULL,
  date_key           int  NOT NULL,
  employment_id      uuid NOT NULL,
  employee_sk        bigint NOT NULL,
  legal_entity_sk    bigint NOT NULL,
  org_unit_sk        bigint,
  leave_type_sk      bigint NOT NULL,
  quantity_days      numeric(6,4) NOT NULL,
  quantity_hours     numeric(6,2),
  is_paid            boolean NOT NULL,
  status             text NOT NULL,
  CONSTRAINT fact_leave_pk PRIMARY KEY (tenant_id, leave_date, leave_request_id),
  CONSTRAINT fact_leave_status_ck CHECK (status IN ('APPROVED','TAKEN','CANCELLED')),
  CONSTRAINT fact_leave_qty_ck CHECK (quantity_days >= 0)
) PARTITION BY RANGE (leave_date);
CREATE INDEX fact_leave_employment_ix ON rpt.fact_leave (tenant_id, employment_id, leave_date);
CREATE INDEX fact_leave_type_ix ON rpt.fact_leave (tenant_id, leave_type_sk, leave_date);

-- Compulsory insurance per employment × month × insurance type (from pay.si_contribution,
-- incl. source = OPENING history rows of the go-live import, ADR-0037). D11: types,
-- source and status are the pay.si_contribution values (H4, M10).
CREATE TABLE rpt.fact_si_contribution (
  tenant_id            uuid NOT NULL,
  contribution_month   date NOT NULL,                  -- partition key
  si_contribution_id   uuid NOT NULL,                  -- pay.si_contribution id
  insurance_type       text NOT NULL,
  period_key           int  NOT NULL,
  employment_id        uuid NOT NULL,
  person_id            uuid NOT NULL,
  employee_sk          bigint NOT NULL,
  legal_entity_sk      bigint NOT NULL,
  run_sk               bigint,                         -- set for source RUN; NULL for OPENING rows
  source               text NOT NULL,                  -- D11/M10: RUN | OPENING | ADJUSTMENT
  currency_code        char(3) NOT NULL DEFAULT 'VND',
  basis_amount         numeric(19,4) NOT NULL,         -- pii:P2
  ee_rate              numeric(12,8),
  er_rate              numeric(12,8),
  ee_amount            numeric(19,4) NOT NULL DEFAULT 0,
  er_amount            numeric(19,4) NOT NULL DEFAULT 0,
  contribution_status  text NOT NULL,
  is_retro             boolean NOT NULL DEFAULT false,
  CONSTRAINT fact_si_contribution_pk PRIMARY KEY (tenant_id, contribution_month, si_contribution_id, insurance_type),
  CONSTRAINT fact_si_contribution_month_ck CHECK (extract(day FROM contribution_month) = 1),
  -- SI = BHXH (pension/survivor + sickness/maternity), HI = BHYT, UI = BHTN,
  -- OA_OD = BHTNLĐ-BNN (occupational accident / disease), TU = KPCĐ (trade union fee)
  CONSTRAINT fact_si_contribution_type_ck CHECK (insurance_type IN ('SI','HI','UI','OA_OD','TU')),
  CONSTRAINT fact_si_contribution_source_ck CHECK (source IN ('RUN','OPENING','ADJUSTMENT')),
  CONSTRAINT fact_si_contribution_opening_ck CHECK (source <> 'OPENING' OR run_sk IS NULL),
  -- = pay.si_contribution.status (OPENING is a source, not a status: D11)
  CONSTRAINT fact_si_contribution_status_ck CHECK (contribution_status IN ('CONTRIBUTE','EXEMPT','NO_CONTRIBUTION',
                                                                           'NOT_PARTICIPATING'))
) PARTITION BY RANGE (contribution_month);
CREATE INDEX fact_si_contribution_employment_ix ON rpt.fact_si_contribution (tenant_id, employment_id, contribution_month);
CREATE INDEX fact_si_contribution_le_ix ON rpt.fact_si_contribution (tenant_id, legal_entity_sk, contribution_month, insurance_type);

-- Recruitment funnel (Phase 2): one row per application × stage entered. No candidate PII.
CREATE TABLE rpt.fact_recruitment_funnel (
  tenant_id          uuid NOT NULL,
  entered_date       date NOT NULL,                    -- partition key (local date of entering the stage)
  stage_history_id   uuid NOT NULL,
  date_key           int  NOT NULL,
  application_id     uuid NOT NULL,
  candidate_id       uuid NOT NULL,                    -- opaque id only (anonymised candidates keep it)
  job_opening_id     uuid NOT NULL,
  requisition_id     uuid NOT NULL,
  legal_entity_sk    bigint,
  org_unit_sk        bigint,
  position_sk        bigint,
  source_channel     text NOT NULL,
  stage_code         text,
  stage_type         text,
  stage_seq          smallint,
  entered_at         timestamptz NOT NULL,
  exited_at          timestamptz,
  hours_in_stage     numeric(10,2),
  is_current         boolean NOT NULL DEFAULT false,
  outcome            text NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fact_recruitment_funnel_pk PRIMARY KEY (tenant_id, entered_date, stage_history_id),
  CONSTRAINT fact_recruitment_funnel_outcome_ck CHECK (outcome IN ('ACTIVE','HIRED','REJECTED','WITHDRAWN','CLOSED'))
) PARTITION BY RANGE (entered_date);
CREATE INDEX fact_recruitment_funnel_opening_ix ON rpt.fact_recruitment_funnel (tenant_id, job_opening_id, entered_date);

-- =============================================================================
-- 8. DATA SCOPE (ADR-0006/0012): report_scope maintained from Core permission
--    snapshots. Every fact query joins it on employment_id. Users whose scope is the
--    whole tenant get one row in report_scope_tenant_wide instead of N rows [spike].
-- =============================================================================
CREATE TABLE rpt.report_scope (
  tenant_id         uuid NOT NULL,
  user_account_id   uuid NOT NULL,                     -- core.user_account id
  employment_id     uuid NOT NULL,
  snapshot_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT report_scope_pk PRIMARY KEY (tenant_id, user_account_id, employment_id)
);
CREATE INDEX report_scope_employment_ix ON rpt.report_scope (tenant_id, employment_id);

CREATE TABLE rpt.report_scope_tenant_wide (
  tenant_id         uuid NOT NULL,
  user_account_id   uuid NOT NULL,
  snapshot_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT report_scope_tenant_wide_pk PRIMARY KEY (tenant_id, user_account_id)
);

-- =============================================================================
-- 9. RLS, grants, partitions, views
-- =============================================================================
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['rpt.dim_employee','rpt.dim_org_unit','rpt.dim_legal_entity','rpt.dim_position',
                           'rpt.dim_location','rpt.dim_cost_centre','rpt.dim_pay_component','rpt.dim_att_code',
                           'rpt.dim_leave_type','rpt.dim_pay_run',
                           'rpt.fact_headcount_daily','rpt.fact_movement','rpt.fact_payroll_line',
                           'rpt.fact_payroll_employee_month','rpt.fact_attendance_daily','rpt.fact_ot',
                           'rpt.fact_leave','rpt.fact_si_contribution','rpt.fact_recruitment_funnel',
                           'rpt.report_scope','rpt.report_scope_tenant_wide']
  LOOP
    CALL rpt_sys.enable_tenant_rls(t::regclass);
  END LOOP;
END $$;
-- NB: policies on a partitioned parent do not apply to direct partition access; roles
-- are granted on the parents only (partitions are owned by yhrm_owner, no grants).

-- current month - 1 .. + 2 (the transformation job extends with ensure_fact_partitions)
DO $$ BEGIN PERFORM rpt_sys.ensure_fact_partitions((date_trunc('month', current_date) - interval '1 month')::date, 4); END $$;

-- Scoped access template (reporting service / Superset datasets build on this shape).
CREATE OR REPLACE VIEW rpt.v_fact_headcount_daily_scoped WITH (security_invoker = true, security_barrier = true) AS
SELECT f.*
  FROM rpt.fact_headcount_daily f
 WHERE EXISTS (SELECT 1 FROM rpt.report_scope_tenant_wide w
                WHERE w.tenant_id = f.tenant_id AND w.user_account_id = (SELECT rpt_sys.current_user_account_id()))
    OR EXISTS (SELECT 1 FROM rpt.report_scope s
                WHERE s.tenant_id = f.tenant_id AND s.user_account_id = (SELECT rpt_sys.current_user_account_id())
                  AND s.employment_id = f.employment_id);

-- D-10 billable persons (review fix H6): DISTINCT (legal_entity_id, person_id) with an
-- ACTIVE employment on the last day of the month. Read by the monthly usage job (per
-- tenant context) and written to saas.usage_record (BILLABLE_PERSON) in OLTP.
CREATE OR REPLACE VIEW rpt.v_billable_person_month WITH (security_invoker = true) AS
SELECT f.tenant_id, f.legal_entity_id, f.snapshot_date AS month_end,
       count(DISTINCT f.person_id) AS billable_persons
  FROM rpt.fact_headcount_daily f
 WHERE f.is_billable
   AND f.snapshot_date = (date_trunc('month', f.snapshot_date) + interval '1 month - 1 day')::date
 GROUP BY f.tenant_id, f.legal_entity_id, f.snapshot_date;

GRANT SELECT ON rpt.v_fact_headcount_daily_scoped, rpt.v_billable_person_month TO yhrm_rpt_reader, yhrm_rpt_transform;

RESET ROLE;

GRANT EXECUTE ON FUNCTION rpt_sys.current_tenant_id(), rpt_sys.current_user_account_id(),
                          rpt_sys.set_context(uuid, uuid, text)
  TO yhrm_rpt_reader, yhrm_rpt_transform;
REVOKE EXECUTE ON FUNCTION rpt_sys.ensure_fact_partitions(date, int) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION rpt_sys.ensure_fact_partitions(date, int) TO yhrm_rpt_transform;
REVOKE EXECUTE ON PROCEDURE rpt_sys.enable_tenant_rls(regclass),
                            rpt_sys.create_month_partitions(regclass, date, int) FROM PUBLIC;

-- =============================================================================
-- 10. FEED — logical replication
-- =============================================================================
-- (a) PUBLISHER (each OLTP cluster, shared pool or dedicated): ddl/007_publications.sql
--     (v0.5, D4) creates pub_rpt_core, pub_rpt_hr, pub_rpt_att, pub_rpt_pay and
--     pub_rpt_rec with explicit column lists (never *_enc / *_bidx; PK always
--     included), grants yhrm_cdc USAGE + column-level SELECT on exactly those columns
--     and a read-all RLS policy per published table (yhrm_cdc is not BYPASSRLS; the
--     initial copy would otherwise return 0 rows under FORCE RLS). pub_rpt_att and
--     pub_rpt_pay use publish_via_partition_root = true. The landing tables of
--     section 5 mirror those column lists 1:1. Operational prerequisites:
--     wal_level = logical; max_slot_wal_keep_size bounded; slot monitoring (lag,
--     retained WAL).
--
-- (b) SUBSCRIBER (this reporting database), as yhrm_cdc — EXAMPLE ONLY, NOT EXECUTED
--     (one subscription per OLTP cluster and publication; several pool clusters land
--     in the same tables — ids are UUIDv7 and rows carry tenant_id, so they never
--     collide):
--
--   CREATE SUBSCRIPTION sub_rpt_pool01_core
--     CONNECTION 'host=pg-pool01-ro.yhrm.internal port=5432 dbname=yhrm user=yhrm_cdc sslmode=verify-full'
--     PUBLICATION pub_rpt_core
--     WITH (slot_name = 'rpt_pool01_core',
--           copy_data = true,            -- initial sync of the landing tables
--           binary = true,
--           streaming = parallel,        -- PG16+: large transactions (payroll chunks)
--           disable_on_error = true,     -- stop and alert instead of retry-looping on a bad row
--           failover = true,             -- PG17: slot survives a CNPG failover [spike SP-6]
--           origin = none);
--   -- same options for each further publication:
--   CREATE SUBSCRIPTION sub_rpt_pool01_hr  CONNECTION '...same...' PUBLICATION pub_rpt_hr
--     WITH (slot_name = 'rpt_pool01_hr',  copy_data = true, binary = true, streaming = parallel,
--           disable_on_error = true, failover = true, origin = none);
--   CREATE SUBSCRIPTION sub_rpt_pool01_att CONNECTION '...same...' PUBLICATION pub_rpt_att
--     WITH (slot_name = 'rpt_pool01_att', copy_data = true, binary = true, streaming = parallel,
--           disable_on_error = true, failover = true, origin = none);
--   CREATE SUBSCRIPTION sub_rpt_pool01_pay CONNECTION '...same...' PUBLICATION pub_rpt_pay
--     WITH (slot_name = 'rpt_pool01_pay', copy_data = true, binary = true, streaming = parallel,
--           disable_on_error = true, failover = true, origin = none);
--   -- pub_rpt_rec (Phase 2) only once Recruitment is live.
--
--   Logical replication carries NO DDL: every migration that changes a published table
--   or a column list (ALTER PUBLICATION ... SET TABLE in OLTP) adds the matching
--   landing-table + rpt_raw view migration here FIRST (expand), then
--   `ALTER SUBSCRIPTION ... REFRESH PUBLICATION` (ADR-0012, CI check).
-- =============================================================================
