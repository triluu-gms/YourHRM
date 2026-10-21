-- =============================================================================
-- YourHRM  ·  007_publications.sql   (owner: SaaS platform — reporting feed, D4)
--
-- Publisher side of the Phase-1 reporting feed (DESIGN §11, ADR-0012 Amendment 1,
-- ADR-0032; D-15 / R2-01): native logical replication from each OLTP cluster
-- (shared pool or dedicated) into the separate reporting store
-- (reporting/100_rpt.sql). No Kafka, no Debezium in Phase 1.
--
-- One publication per module schema:
--   pub_rpt_core  pub_rpt_hr  pub_rpt_att  pub_rpt_pay  pub_rpt_rec (Phase 2 module)
-- Each lists ONLY the tables the reporting star schema needs and, per table, an
-- explicit COLUMN LIST (PG 15+) that:
--   * never contains a P3 column (*_enc ciphertext, *_bidx blind index — the blind
--     index is an HMAC under the TENANT key and must not leave OLTP either);
--   * always contains the primary key (= replica identity), otherwise UPDATE/DELETE
--     on the publisher would fail once the table is published;
--   * omits generated columns (not replicated before PG 18), free-text columns
--     (reason / comment / error texts) and engine internals the models do not need.
-- The column sets are mirrored 1:1 by the landing tables of reporting/100_rpt.sql
-- section 5 (same schema-qualified names: logical replication cannot rename).
--
-- Grants and RLS for yhrm_cdc (the subscription's replication user):
--   * USAGE on the five module schemas and column-level SELECT on exactly the
--     published columns (derived below from pg_publication_tables, so the grant can
--     never drift from the publication);
--   * a read-all RLS policy per published table (sys.grant_rls_read_all, policy
--     read_all_yhrm_cdc, FOR SELECT USING (true)). yhrm_cdc is NOT BYPASSRLS: the
--     initial table copy runs a plain SELECT on the publisher, where every tenant
--     table has ENABLE + FORCE RLS; without this policy the copy returns 0 rows.
--     The ongoing WAL decoding is not subject to RLS.
-- yhrm_cdc gets nothing else in OLTP (no write, no masking view, no saas/audit).
--
-- wal_level: CREATE PUBLICATION only WARNS when wal_level <> logical (verified on
-- PGlite 0.5.10 / PostgreSQL 18.6: WARNING "wal_level" is insufficient to publish
-- logical changes), so this file also loads on a default local server. The feed
-- itself needs wal_level = logical (docker-compose sets it), bounded
-- max_slot_wal_keep_size and slot-lag monitoring (DESIGN §11, §14).
--
-- Partitioned sources (att.daily_timesheet, att.attendance_result_line,
-- pay.result_line) are published through their root (publish_via_partition_root),
-- so the subscriber has ONE landing table per source and new monthly partitions
-- are picked up automatically.
--
-- Change management: logical replication carries no DDL. Any later change to a
-- published table or column list is a forward migration here (ALTER PUBLICATION
-- ... SET TABLE ...) plus the matching landing-table / rpt_raw migration in the
-- reporting store, applied to the subscriber FIRST (expand), then
-- ALTER SUBSCRIPTION ... REFRESH PUBLICATION. This file is the baseline only.
--
-- Loaded after 006 by the docker initdb glob (NNN_*.sql).
--
-- v0.5 (2026-10-21): first version (D4, with D11 in reporting/100_rpt.sql).
--   * core: M2 applied — core.location_version is published WITHOUT
--     min_wage_region_override (column dropped in 002 v0.4); the region is derived
--     through the Legal Parameter Service in the reporting model.
--   * att / pay: tables and columns as they exist in the 004 / 005 working tree on
--     2026-10-21. pay.payroll_run.status values (incl. CANCELLED, D3) are not part of
--     a column list, so D3 needs no change here.
-- =============================================================================

\set ON_ERROR_STOP on

-- -----------------------------------------------------------------------------
-- 0. Prerequisite: CREATE PUBLICATION needs CREATE on the database (and ownership
--    of the tables, which yhrm_owner has). Run here as the admin that ran 000; on
--    a Flyway-only environment this grant must already exist (see 000 / runbook).
-- -----------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT has_database_privilege('yhrm_owner', current_database(), 'CREATE') THEN
    EXECUTE format('GRANT CREATE ON DATABASE %I TO yhrm_owner', current_database());
  END IF;
  IF NOT has_database_privilege('yhrm_owner', current_database(), 'CREATE') THEN
    RAISE EXCEPTION 'yhrm_owner needs CREATE on database % to own the reporting publications', current_database();
  END IF;
END $$;

SET ROLE yhrm_owner;

-- -----------------------------------------------------------------------------
-- 1. Publications (baseline; idempotent for a re-run of the local DDL)
-- -----------------------------------------------------------------------------
DROP PUBLICATION IF EXISTS pub_rpt_core, pub_rpt_hr, pub_rpt_att, pub_rpt_pay, pub_rpt_rec;

-- core: org structure, legal entities, locations, cost centres, positions
--       (dim_legal_entity, dim_org_unit, dim_location, dim_cost_centre, dim_position)
CREATE PUBLICATION pub_rpt_core FOR TABLE
  core.legal_entity         (tenant_id, id, code, status),
  core.legal_entity_version (tenant_id, id, legal_entity_id, valid_from, valid_to, legal_name_vi,
                             legal_name_en, short_name, tax_code, admin_unit_code),
  core.location             (tenant_id, id, legal_entity_id, code, status),
  core.location_version     (tenant_id, id, location_id, valid_from, valid_to, name, location_type,
                             admin_unit_code),                                   -- M2: no min_wage_region_override
  core.cost_centre          (tenant_id, id, legal_entity_id, code, name, gl_segment, valid_from, valid_to),
  core.org_unit             (tenant_id, id, code),
  core.org_unit_version     (tenant_id, id, org_unit_id, valid_from, valid_to, name_vi, name_en,
                             org_unit_type_id, parent_id, path, legal_entity_id, location_id,
                             cost_centre_id, status),
  core.position             (tenant_id, id, code),
  core.position_version     (tenant_id, id, position_id, valid_from, valid_to, title_override, job_title_id,
                             org_unit_id, legal_entity_id, location_id, cost_centre_id, fte_budget, status)
  WITH (publish = 'insert, update, delete, truncate');

-- hr: person (P1 name only), employment, assignment (dim_employee, fact_headcount_daily,
--     fact_movement). NEVER published: national id / tax code / SI number / HI card /
--     ethnicity / religion / bank / addresses / contacts / family (all P3 or not needed).
CREATE PUBLICATION pub_rpt_hr FOR TABLE
  hr.person     (tenant_id, id, full_name, birth_date, gender_code, nationality_code, is_anonymised),
  hr.employment (tenant_id, id, person_id, legal_entity_id, employee_no, hire_type, hire_date, end_date,
                 status, worker_category_code, employee_category_code),
  hr.assignment (tenant_id, id, assignment_key, employment_id, assignment_type, legal_entity_id,
                 position_id, org_unit_id, job_title_id, grade_id, location_id, cost_centre_id, fte,
                 action_code, reason_code, valid_from, valid_to, recorded_at, superseded_at)
  WITH (publish = 'insert, update, delete, truncate');

-- att: closed attendance results + lines (fact_attendance_daily totals, fact_ot by
--      OT category), leave ledger (fact_leave, balances), daily timesheet (minimal:
--      day status and hour totals; no punch times, no exception lists).
CREATE PUBLICATION pub_rpt_att FOR TABLE
  att.attendance_result      (tenant_id, id, period_id, period_start, scope, employment_id, result_kind,
                              result_version, source, status, att_date_from, att_date_to, ot_date_from,
                              ot_date_to, legal_pack_id, override_version, supersedes_result_id,
                              close_cause, sealed_at, published_at, withdrawn_at, locked_at,
                              locked_by_run_id, unlocked_at),
  att.attendance_result_line (tenant_id, id, period_start, result_id, employment_id, assignment_key,
                              segment_from, segment_to, legal_entity_id, org_unit_id, pay_group_id,
                              cost_centre_id, category_code, quantity, unit, period_quantity,
                              preceded_by_day_ot, is_retro, original_period_id, original_result_id,
                              original_segment_from),
  att.leave_balance_ledger   (tenant_id, id, employment_id, legal_entity_id, org_unit_id, leave_type_id,
                              leave_type_code, leave_year, txn_type, quantity, unit, txn_date,
                              accrual_month, expires_on, leave_request_id, source),
  att.daily_timesheet        (tenant_id, id, work_date, employment_id, legal_entity_id, org_unit_id,
                              location_id, period_id, shift_id, day_type, day_status, primary_code,
                              scheduled_hours, worked_hours, absent_hours, night_hours, late_min,
                              early_min, day_value, calc_version)
  WITH (publish = 'insert, update, delete, truncate', publish_via_partition_root = true);

-- pay: runs, per-employee totals, result lines, SI contributions, component catalogue
--      (dim_pay_run, dim_pay_component, fact_payroll_line, fact_payroll_employee_month,
--      fact_si_contribution). Amounts are P2 plaintext (X-01) and are exposed in the
--      reporting store only through the pay-permission-checked subject areas.
CREATE PUBLICATION pub_rpt_pay FOR TABLE
  pay.payroll_run      (tenant_id, id, run_no, pay_group_id, pay_period_id, legal_entity_id, period_month,
                        run_type, run_subtype_code, calc_mode, status, tax_month, payment_date,
                        currency_code, legal_pack_id, legal_pack_version, override_version,
                        approved_at, paid_at, closed_at, reverses_run_id),
  pay.run_employee     (tenant_id, id, run_id, employment_id, person_id, legal_entity_id, org_unit_id,
                        cost_centre_id, period_month, status, pay_basis, gross_amount,
                        taxable_income_amount, si_ee_amount, si_er_amount, pit_amount, deduction_amount,
                        net_amount, employer_cost_amount, currency_code),
  pay.result_line      (tenant_id, id, period_month, run_id, run_employee_id, employment_id, component_id,
                        component_code, segment_key, segment_from, segment_to, qty, rate, amount,
                        taxable_amount, exempt_amount, si_basis_amount, tu_basis_amount, retro_of_period),
  pay.si_contribution  (tenant_id, id, employment_id, person_id, legal_entity_id, contribution_month,
                        insurance_type, source, status, exemption_reason_code, basis_amount, ee_rate,
                        er_rate, ee_amount, er_amount, currency_code, run_id, adjusts_id, superseded_at),
  pay.salary_component (tenant_id, id, code, name, name_en, component_type, is_system, status)
  WITH (publish = 'insert, update, delete, truncate', publish_via_partition_root = true);

-- rec (Phase 2): recruitment funnel only (fact_recruitment_funnel). No candidate,
--      candidate file, pre-boarding, offer or hand-off data: no candidate PII leaves OLTP.
CREATE PUBLICATION pub_rpt_rec FOR TABLE
  rec.requisition    (tenant_id, id, code, legal_entity_id, org_unit_id, position_id, vacancies,
                      classification, status),
  rec.job_opening    (tenant_id, id, requisition_id, code, title_vi, status, opened_at, closed_at),
  rec.pipeline_stage (tenant_id, id, seq, code, stage_type),
  rec.application    (tenant_id, id, candidate_id, job_opening_id, source_channel, current_stage_id,
                      status, applied_at, closed_at, hired_at),
  rec.stage_history  (tenant_id, id, application_id, from_stage_id, to_stage_id, from_status, to_status,
                      reason_code, changed_at)
  WITH (publish = 'insert, update, delete, truncate');

COMMENT ON PUBLICATION pub_rpt_core IS 'Reporting feed (DESIGN §11, D4): core org/LE/location/cost centre/position; column-listed, no P3.';
COMMENT ON PUBLICATION pub_rpt_hr   IS 'Reporting feed (DESIGN §11, D4): hr person (P1 name only)/employment/assignment; column-listed, no *_enc/*_bidx.';
COMMENT ON PUBLICATION pub_rpt_att  IS 'Reporting feed (DESIGN §11, D4): att results, result lines, leave ledger, daily timesheet (minimal); via partition root.';
COMMENT ON PUBLICATION pub_rpt_pay  IS 'Reporting feed (DESIGN §11, D4): pay runs, run employees, result lines, SI contributions, components; via partition root.';
COMMENT ON PUBLICATION pub_rpt_rec  IS 'Reporting feed (DESIGN §11, D4; Phase 2): recruitment funnel only, no candidate PII.';

-- -----------------------------------------------------------------------------
-- 2. yhrm_cdc: schema USAGE, column-level SELECT and RLS read policy, derived from
--    the publications above (single source of truth), plus a hard guard that no
--    P3 column is ever published.
-- -----------------------------------------------------------------------------
GRANT USAGE ON SCHEMA core, hr, att, pay, rec TO yhrm_cdc;

DO $$
DECLARE r record;
BEGIN
  -- guard: a *_enc / *_bidx column in any reporting publication is a release blocker
  PERFORM 1
    FROM pg_publication_tables pt, unnest(pt.attnames) AS a(col)
   WHERE pt.pubname LIKE 'pub\_rpt\_%' AND (a.col LIKE '%\_enc' OR a.col LIKE '%\_bidx');
  IF FOUND THEN
    RAISE EXCEPTION 'P3 column (*_enc / *_bidx) found in a pub_rpt_* publication column list';
  END IF;

  FOR r IN
    SELECT pt.schemaname, pt.tablename, pt.attnames
      FROM pg_publication_tables pt
     WHERE pt.pubname LIKE 'pub\_rpt\_%'
     ORDER BY 1, 2
  LOOP
    -- exactly the published columns, nothing more
    EXECUTE format('REVOKE ALL ON %I.%I FROM yhrm_cdc', r.schemaname, r.tablename);
    EXECUTE format('GRANT SELECT (%s) ON %I.%I TO yhrm_cdc',
                   (SELECT string_agg(quote_ident(c), ', ') FROM unnest(r.attnames) AS c),
                   r.schemaname, r.tablename);
    -- initial copy under FORCE RLS: explicit read-all policy (yhrm_cdc is NOT BYPASSRLS)
    CALL sys.grant_rls_read_all(format('%I.%I', r.schemaname, r.tablename)::regclass, 'yhrm_cdc');
  END LOOP;
END $$;

RESET ROLE;

-- -----------------------------------------------------------------------------
-- 3. Subscriber side (reference only; runs in the reporting store as yhrm_cdc,
--    see reporting/100_rpt.sql section 10):
--      CREATE SUBSCRIPTION sub_rpt_<cluster>_<schema>
--        CONNECTION '... user=yhrm_cdc ...' PUBLICATION pub_rpt_<schema>
--        WITH (slot_name = 'rpt_<cluster>_<schema>', copy_data = true, binary = true,
--              streaming = parallel, disable_on_error = true, failover = true, origin = none);
-- -----------------------------------------------------------------------------
