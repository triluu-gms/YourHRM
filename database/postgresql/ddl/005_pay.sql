-- =============================================================================
-- YourHRM  ·  005_pay.sql   (owner module: Payroll)
-- Pay groups, calendar and periods, pay-group assignment, component catalogue,
-- formulas, salary structures, pay scales / grade bands (baseline capability),
-- People/T&A projections, variable inputs, payroll runs (snapshot-first),
-- results (partitioned), retro, SI contribution history, PIT withholding, YTD,
-- statutory declarations (D02-LT, 05/KK, 05/QTT), 08/CK, PIT certificates,
-- casual income, loans and deduction orders, final settlement, payslips and
-- delivery, bank payment files, GL, masking views, outbox/inbox.
-- DESIGN.md §2-§5, §7.5, §8, §9, §10, §13; ADR-0008/0009/0019/0022/0024/0026/0029/0030/0037;
-- payroll BRD §4/§6/§7; gap-analysis §7 (close hold, v1.1).
--
-- Cross-module references are SOFT (no FK leaves the schema):
--   employment_id, person_id, salary_record_id, bank_account_id, ... -> hr.*
--   legal_entity_id, org_unit_id, cost_centre_id, grade_id, location_id,
--   document_id, workflow_instance_id, import_job_id, user ids     -> core.*
--   att_period_id, attendance result id, hold id                   -> att.*
--   legal_pack_id                                                  -> saas.legal_param_pack
--   Global master data is referenced by CODE (bank_code, currency_code, ...).
-- Inside the schema every FK is composite (tenant_id, x_id) -> parent (tenant_id, id).
--
-- Legal values (rates, caps, PIT brackets, deductions, insurance.multi_contract_rule)
-- are NOT stored here: they live in saas.legal_param_* and are read through the
-- Legal Parameter Service. The BRD entities legal_param_set / pit_schedule are
-- realised as the resolved legal values inside pay.run_config_snapshot.payload.
--
-- PII classes (COMMENT 'pii:Px'): payroll amounts are P2 PLAINTEXT (X-01, R2-15):
-- RLS + yhrm_app_pay-only grants + fail-closed masking views (pay.v_*_masked,
-- same template as hr.v_salary_record_masked) + sensitive-read audit. P3 columns
-- (tax code, SI number, bank account, casual payee ids, payslip PDF password) are
-- application-encrypted with the data subject's DEK (saas.subject_key).
--
-- Phase markers: everything is Phase 1 unless marked "-- P2" (table or column).
--
-- v0.4 (2026-10-17): first DDL for `pay` (DESIGN v0.3 §7.5/§8 + review fixes H4, M6,
-- M7, M8, M10; R2-17 payslip password, R3-02 unsigned PIT certificate, R3-03 per-type
-- multi-contract insurance rule, D-09 opening balances). Smoke-tested 2026-10-17 on
-- PGlite 0.5.10 (= PostgreSQL 18.6 wasm; pg_stat_statements and lz4 stubbed), after
-- 000-003 taken from git HEAD: 48/48 checks pass (partitions + routing, run state
-- machine and close-hold gate, result immutability after SUBMITTED/APPROVED, UNLOCK
-- with history, si_contribution uniqueness per insurance type, shared config snapshot,
-- fail-closed masking, cross-tenant RLS, sys.cross_schema_grant_violations() = 0).
-- Not yet run on a real PostgreSQL 16/17 server.
--
-- v0.5 (2026-10-21, sponsor decisions on the DDL review):
--   D1  att.attendance_result_hold is the ONLY authority for close-hold state.
--       pay.run_attendance_version becomes a mirror: one row per att hold
--       (att_hold_id, att_result_id, result_scope, employment_id), unique per
--       (tenant_id, payroll_run_id, att_hold_id). The cross-run "one ACTIVE hold per
--       period" unique index is removed (it contradicted per-employment holds).
--       Renamed columns: run_id -> payroll_run_id, hold_id -> att_hold_id,
--       result_id -> att_result_id; new result_scope, result_kind, employment_id,
--       scope_key, superseded_at.
--   D10 pay.ext_att_result aligned with att: result_scope PERIOD | EMPLOYMENT,
--       result_kind REGULAR | EARLY_CLOSE | OFF_CYCLE_RETRO, employment_id only for
--       EMPLOYMENT scope, result_id renamed att_result_id and unique on it (the
--       (att_period_id, result_version) unique is dropped: att numbers versions per scope).
--   D3  payroll_run.status CANCELLED: only from DRAFT, CALCULATED or REJECTED of a
--       never-approved run, terminal, requires cancel_reason + cancelled_by/at and no
--       mirrored hold still ACTIVE (the T&A API releases them with reason CANCELLED).
--   D2  results freeze at SUBMITTED: accepted D2 (2026-10-21).
--   D6  masking views are granted to yhrm_app_pay only; explicit REVOKE from
--       yhrm_reporting_ro (none was granted).
--   D9  tenant purge bypass (sys.purge_bypass) on the DELETE path of every
--       append-only / immutability trigger of this file; UPDATE stays blocked.
--   Smoke-tested 2026-10-21 on PGlite 0.5.10 (= PostgreSQL 18.6) with 000-004.
-- =============================================================================

\set ON_ERROR_STOP on
SET ROLE yhrm_owner;
SET search_path = pay, sys, public;

GRANT USAGE ON SCHEMA pay TO yhrm_app_pay;

-- =============================================================================
-- 0. HELPERS (pay-local; sys stays generic)
-- =============================================================================

-- Fail-closed visibility of payroll amounts for masking views (DESIGN §9.4):
-- only app.mask_level NONE or PII reveals amounts; unset/'' (pooled session after
-- SET LOCAL) or PII_AND_PAY hides them. Same rule as hr.v_salary_record_masked.
CREATE OR REPLACE FUNCTION pay.amounts_visible()
RETURNS boolean LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT coalesce(current_setting('app.mask_level', true), '') IN ('NONE','PII') $$;
COMMENT ON FUNCTION pay.amounts_visible() IS
  'Masking views: true only when app.mask_level is NONE or PII. Fails closed (X-01 compensating control).';

-- =============================================================================
-- 1. PAY GROUP, DEFAULT ASSIGNMENT RULE, CALENDAR, PERIOD
--    Payroll is the sole owner of pay groups, the pay calendar and the standard-day
--    method (BR-PAY-008/009, gap §5 #25); T&A consumes pay.PayCalendarPublished.
-- =============================================================================
CREATE TABLE pay.pay_group (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                    text NOT NULL,
  name                    text NOT NULL,
  name_en                 text,
  legal_entity_id         uuid NOT NULL,                     -- -> core.legal_entity (soft); one LE per group (BR-PAY-081)
  frequency               text NOT NULL DEFAULT 'MONTHLY',   -- SEMI_MONTHLY is P2 (BR-PAY-008 'C')
  period_start_day        smallint NOT NULL DEFAULT 1,       -- 26 => periods 26..25 (non-calendar cut-off)
  attendance_cutoff_day   smallint,                          -- day of month; NULL = period end
  ot_cutoff_day           smallint,                          -- optional separate OT cut-off
  pay_day                 smallint NOT NULL,                 -- day of month of the pay date
  pay_day_month_offset    smallint NOT NULL DEFAULT 1,       -- 0 = same month as period end, 1 = following month
  att_close_offset_wd     smallint NOT NULL DEFAULT 2,       -- T&A close deadline = period end + N working days (X-03)
  approval_offset_wd      smallint NOT NULL DEFAULT 5,       -- approval deadline = period end + N working days
  -- standard-day method (BR-PAY-009): FIXED_DAYS (e.g. 26) | SCHEDULE_DAYS (employee work schedule, from T&A)
  -- | CALENDAR_DAYS | FIXED_HOURS (hourly pay). Copied onto each pay_period at publication.
  std_day_method          text NOT NULL DEFAULT 'FIXED_DAYS',
  std_day_value           numeric(6,2),                      -- days or hours for FIXED_*; NULL otherwise
  payer_bank_account_id   uuid,                              -- -> core.legal_entity_bank_account (soft)
  approval_flow_code      text,                              -- -> core workflow definition (soft, by code)
  payslip_template_code   text,                              -- BR-PAY-100
  is_confidential         boolean NOT NULL DEFAULT false,    -- executive group, restricted users (BR-PAY-120)
  currency_code           sys.currency_code NOT NULL DEFAULT 'VND',
  status                  text NOT NULL DEFAULT 'ACTIVE',
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT pay_group_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pay_group_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT pay_group_frequency_ck CHECK (frequency IN ('MONTHLY','SEMI_MONTHLY')),
  CONSTRAINT pay_group_days_ck CHECK (period_start_day BETWEEN 1 AND 28
                                      AND pay_day BETWEEN 1 AND 31
                                      AND (attendance_cutoff_day IS NULL OR attendance_cutoff_day BETWEEN 1 AND 31)
                                      AND (ot_cutoff_day IS NULL OR ot_cutoff_day BETWEEN 1 AND 31)
                                      AND pay_day_month_offset IN (0, 1)),
  CONSTRAINT pay_group_offsets_ck CHECK (att_close_offset_wd >= 0 AND approval_offset_wd > att_close_offset_wd),
  CONSTRAINT pay_group_std_method_ck CHECK (std_day_method IN ('FIXED_DAYS','SCHEDULE_DAYS','CALENDAR_DAYS','FIXED_HOURS')),
  CONSTRAINT pay_group_std_value_ck CHECK (
       (std_day_method IN ('FIXED_DAYS','FIXED_HOURS') AND std_day_value > 0)
    OR (std_day_method IN ('SCHEDULE_DAYS','CALENDAR_DAYS') AND std_day_value IS NULL)),
  CONSTRAINT pay_group_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX pay_group_le_ix ON pay.pay_group (tenant_id, legal_entity_id);
COMMENT ON COLUMN pay.pay_group.frequency IS 'MONTHLY in Phase 1; SEMI_MONTHLY -- P2';

-- Default assignment rule (BR-PAY-085): legal entity + worker category (+ location),
-- highest priority wins; evaluated on people.EmployeeHired / AssignmentChanged.
CREATE TABLE pay.pay_group_rule (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  pay_group_id          uuid NOT NULL,
  legal_entity_id       uuid NOT NULL,                       -- soft core
  worker_category_code  text,                                -- NULL = any
  location_id           uuid,                                -- soft core.location; NULL = any
  priority              smallint NOT NULL DEFAULT 100,       -- lower = evaluated first
  valid_from            date NOT NULL,
  valid_to              date,
  valid_during          daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  status                text NOT NULL DEFAULT 'ACTIVE',
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT pay_group_rule_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pay_group_rule_group_fk FOREIGN KEY (tenant_id, pay_group_id) REFERENCES pay.pay_group (tenant_id, id),
  CONSTRAINT pay_group_rule_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT pay_group_rule_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX pay_group_rule_le_ix ON pay.pay_group_rule (tenant_id, legal_entity_id, priority);

-- One published calendar per pay group and year (pay.PayCalendarPublished).
CREATE TABLE pay.pay_calendar (
  tenant_id       uuid NOT NULL,
  id              uuid NOT NULL DEFAULT sys.uuid_v7(),
  pay_group_id    uuid NOT NULL,
  calendar_year   smallint NOT NULL,
  status          text NOT NULL DEFAULT 'DRAFT',
  published_at    timestamptz,
  published_by    uuid,
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid,
  version         int NOT NULL DEFAULT 0,
  CONSTRAINT pay_calendar_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pay_calendar_group_fk FOREIGN KEY (tenant_id, pay_group_id) REFERENCES pay.pay_group (tenant_id, id),
  CONSTRAINT pay_calendar_uq UNIQUE (tenant_id, pay_group_id, calendar_year),
  CONSTRAINT pay_calendar_year_ck CHECK (calendar_year BETWEEN 2000 AND 2100),
  CONSTRAINT pay_calendar_status_ck CHECK (status IN ('DRAFT','PUBLISHED','SUPERSEDED')),
  CONSTRAINT pay_calendar_published_ck CHECK (status = 'DRAFT' OR published_at IS NOT NULL)
);

-- Pay period. status OPEN -> INPUT_CUTOFF -> CLOSED (gap §7.1). INPUT_CUTOFF is the
-- People / variable-input watermark; People itself has no period state (GAP-064).
CREATE TABLE pay.pay_period (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  pay_group_id         uuid NOT NULL,
  pay_calendar_id      uuid,
  period_month         sys.month_date NOT NULL,               -- payroll month label (accounting month)
  period_seq           smallint NOT NULL DEFAULT 1,           -- 2 only for SEMI_MONTHLY -- P2
  period_start         date NOT NULL,
  period_end           date NOT NULL,                         -- INCLUSIVE
  period_range         daterange GENERATED ALWAYS AS (daterange(period_start, period_end, '[]')) STORED,
  attendance_cutoff_date date,
  ot_cutoff_date       date,
  att_close_deadline   date,                                  -- T&A close deadline (X-03)
  approval_deadline    date,
  pay_date             date NOT NULL,
  std_day_method       text NOT NULL,                         -- copied from pay_group at publication (published to T&A)
  std_day_value        numeric(6,2),
  status               text NOT NULL DEFAULT 'OPEN',
  input_cutoff_at      timestamptz,
  closed_at            timestamptz,
  closed_by            uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT pay_period_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pay_period_group_fk FOREIGN KEY (tenant_id, pay_group_id) REFERENCES pay.pay_group (tenant_id, id),
  CONSTRAINT pay_period_calendar_fk FOREIGN KEY (tenant_id, pay_calendar_id) REFERENCES pay.pay_calendar (tenant_id, id),
  CONSTRAINT pay_period_uq UNIQUE (tenant_id, pay_group_id, period_month, period_seq),
  CONSTRAINT pay_period_group_id_uq UNIQUE (tenant_id, pay_group_id, id),   -- target of payroll_run composite FK
  CONSTRAINT pay_period_dates_ck CHECK (period_end >= period_start AND pay_date >= period_start),
  CONSTRAINT pay_period_seq_ck CHECK (period_seq IN (1, 2)),
  CONSTRAINT pay_period_std_method_ck CHECK (std_day_method IN ('FIXED_DAYS','SCHEDULE_DAYS','CALENDAR_DAYS','FIXED_HOURS')),
  CONSTRAINT pay_period_status_ck CHECK (status IN ('OPEN','INPUT_CUTOFF','CLOSED')),
  CONSTRAINT pay_period_cutoff_ck CHECK (status = 'OPEN' OR input_cutoff_at IS NOT NULL),
  CONSTRAINT pay_period_closed_ck CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
  -- periods of one pay group never overlap
  CONSTRAINT pay_period_range_ex EXCLUDE USING gist
    (tenant_id WITH =, pay_group_id WITH =, period_range WITH &&)
);
CREATE INDEX pay_period_month_ix ON pay.pay_period (tenant_id, period_month);

-- Employment -> pay group, effective-dated (Pattern A, DESIGN §5.1). Payroll owns it
-- (BR-PAY-085; People has no pay-group field). Emits pay.PayGroupAssigned.
CREATE TABLE pay.pay_group_assignment (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id     uuid NOT NULL,                            -- -> hr.employment (soft)
  pay_group_id      uuid NOT NULL,
  legal_entity_id   uuid NOT NULL,                            -- denormalised from the pay group (data scope)
  valid_from        date NOT NULL,
  valid_to          date,                                     -- EXCLUSIVE; NULL = open
  valid_during      daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  source            text NOT NULL DEFAULT 'RULE',             -- RULE | MANUAL | OPENING_LOAD (ADR-0037)
  pay_group_rule_id uuid,
  reason_code       text,                                     -- mandatory for MANUAL overrides
  status            text NOT NULL DEFAULT 'ACTIVE',
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT pay_group_assignment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pay_group_assignment_group_fk FOREIGN KEY (tenant_id, pay_group_id) REFERENCES pay.pay_group (tenant_id, id),
  CONSTRAINT pay_group_assignment_rule_fk FOREIGN KEY (tenant_id, pay_group_rule_id) REFERENCES pay.pay_group_rule (tenant_id, id),
  CONSTRAINT pay_group_assignment_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT pay_group_assignment_source_ck CHECK (source IN ('RULE','MANUAL','OPENING_LOAD')),
  CONSTRAINT pay_group_assignment_reason_ck CHECK (source <> 'MANUAL' OR reason_code IS NOT NULL),
  CONSTRAINT pay_group_assignment_status_ck CHECK (status IN ('ACTIVE','CANCELLED')),
  -- one pay group per employment at any date
  CONSTRAINT pay_group_assignment_period_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, valid_during WITH &&) WHERE (status = 'ACTIVE')
);
CREATE INDEX pay_group_assignment_group_ix ON pay.pay_group_assignment (tenant_id, pay_group_id, valid_from) WHERE status = 'ACTIVE';

-- =============================================================================
-- 2. COMPONENT CATALOGUE, FORMULAS, SALARY STRUCTURES
--    salary_component / component_version / formula / formula_version are
--    "global + tenant-overridable" (DESIGN §3.5): platform VN templates live under
--    sys.global_tenant_id() and are COPIED by tenants (BR-PAY-011 templates -- P2);
--    tenant rows reference their own components only.
-- =============================================================================
CREATE TABLE pay.salary_component (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                   text NOT NULL,                       -- stable; referenced by hr.salary_record_allowance (soft, by code)
  name                   text NOT NULL,
  name_en                text,
  component_type         text NOT NULL,                       -- BR-PAY-001
  is_contract_allowance  boolean NOT NULL DEFAULT false,      -- may be entered in People salary records (IF-PAY-P-01)
  is_system              boolean NOT NULL DEFAULT false,      -- engine-owned (SI_EE, PIT, NET ...): not deletable
  template_component_id  uuid,                                -- global template copied from (same table, sentinel tenant; no FK)
  status                 text NOT NULL DEFAULT 'ACTIVE',
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT salary_component_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT salary_component_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT salary_component_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  CONSTRAINT salary_component_type_ck CHECK (component_type IN
    ('EARNING','ALLOWANCE','DEDUCTION','EMPLOYER_CONTRIBUTION','INFORMATION')),
  CONSTRAINT salary_component_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- Formula anchor + immutable published versions (YFL, ADR-0008).
CREATE TABLE pay.formula (
  tenant_id     uuid NOT NULL,
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  code          text NOT NULL,
  name          text NOT NULL,
  result_type   text NOT NULL DEFAULT 'DECIMAL',
  status        text NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    uuid,
  version       int NOT NULL DEFAULT 0,
  CONSTRAINT formula_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT formula_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT formula_result_type_ck CHECK (result_type IN ('DECIMAL','INTEGER','BOOLEAN','DATE','TEXT')),
  CONSTRAINT formula_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE pay.formula_version (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  formula_id         uuid NOT NULL,
  version_no         int NOT NULL,
  expression         text NOT NULL,                           -- YFL source text
  expression_sha256  text NOT NULL,                           -- hex SHA-256 of expression (set by trigger)
  language_version   text NOT NULL,                           -- YFL grammar / function-set version
  dependencies       text[] NOT NULL DEFAULT '{}',            -- component codes, PARAM keys, INPUT names (cycle check at save)
  valid_from         date NOT NULL,
  valid_to           date,
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  status             text NOT NULL DEFAULT 'DRAFT',
  published_at       timestamptz,
  published_by       uuid,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT formula_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT formula_version_formula_fk FOREIGN KEY (tenant_id, formula_id) REFERENCES pay.formula (tenant_id, id),
  CONSTRAINT formula_version_no_uq UNIQUE (tenant_id, formula_id, version_no),
  CONSTRAINT formula_version_status_ck CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
  CONSTRAINT formula_version_published_ck CHECK (status = 'DRAFT' OR published_at IS NOT NULL),
  CONSTRAINT formula_version_sha_ck CHECK (expression_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT formula_version_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT formula_version_period_ex EXCLUDE USING gist
    (tenant_id WITH =, formula_id WITH =, valid_during WITH &&) WHERE (status = 'PUBLISHED')
);

-- Effective-dated, versioned behaviour of a component (BR-PAY-002/003/004/012).
-- Approved runs are protected by pay.run_config_snapshot, not by freezing this table.
CREATE TABLE pay.component_version (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  component_id           uuid NOT NULL,
  valid_from             date NOT NULL,
  valid_to               date,
  valid_during           daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  calc_method            text NOT NULL,                       -- FIXED | FORMULA | MANUAL | IMPORT | ATTENDANCE
  formula_id             uuid,                                -- version resolved by date from formula_version
  default_amount         sys.money_amount,                    -- pii:P2 for FIXED
  currency_code          sys.currency_code NOT NULL DEFAULT 'VND',
  -- PIT treatment (independent of SI, BR-PAY-002)
  pit_treatment          text NOT NULL DEFAULT 'TAXABLE',     -- TAXABLE | EXEMPT | PARTIAL_EXEMPT
  pit_cap_param_key      text,                                -- legal cap, e.g. PIT.EXEMPT.MEAL_MONTHLY (LPS)
  pit_cap_amount         sys.money_amount,                    -- company-policy cap (phone, fuel ...)
  pit_cap_period         text,                                -- MONTH | YEAR (uniform: YTD tracked)
  -- contribution bases
  is_si_basis            boolean NOT NULL DEFAULT false,      -- in compulsory-insurance salary
  is_tu_basis            boolean NOT NULL DEFAULT false,      -- in trade-union fund basis (KPCĐ)
  -- proration (BR-PAY-022/023)
  is_prorated            boolean NOT NULL DEFAULT false,
  proration_basis        text,                                -- STD_DAYS | STD_HOURS | CALENDAR_DAYS
  non_prorated_rule      text,                                -- FULL | NONE | THRESHOLD
  non_prorated_threshold_days numeric(5,2),
  -- gross/net and downstream bases
  gross_net_scope        text NOT NULL DEFAULT 'BOTH',        -- GROSS | NET | BOTH
  is_net_guaranteed      boolean NOT NULL DEFAULT false,      -- fixed net scope in P1 (R2-19); per-employee scope -- P2 (BR-PAY-021)
  is_13th_month_basis    boolean NOT NULL DEFAULT false,
  is_leave_pay_basis     boolean NOT NULL DEFAULT false,
  is_severance_basis     boolean NOT NULL DEFAULT false,
  is_ot_rate_basis       boolean NOT NULL DEFAULT false,      -- BR-PAY-026
  -- rounding (DESIGN §4.6): configuration, not code
  rounding_mode          text NOT NULL DEFAULT 'HALF_UP',
  rounding_unit          numeric(10,4) NOT NULL DEFAULT 1,
  -- payslip
  payslip_group          text,
  display_order          int NOT NULL DEFAULT 0,
  show_on_payslip        boolean NOT NULL DEFAULT true,
  status                 text NOT NULL DEFAULT 'DRAFT',
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT component_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT component_version_component_fk FOREIGN KEY (tenant_id, component_id) REFERENCES pay.salary_component (tenant_id, id),
  CONSTRAINT component_version_formula_fk FOREIGN KEY (tenant_id, formula_id) REFERENCES pay.formula (tenant_id, id),
  CONSTRAINT component_version_method_ck CHECK (calc_method IN ('FIXED','FORMULA','MANUAL','IMPORT','ATTENDANCE')),
  CONSTRAINT component_version_formula_ck CHECK (calc_method <> 'FORMULA' OR formula_id IS NOT NULL),
  CONSTRAINT component_version_pit_ck CHECK (pit_treatment IN ('TAXABLE','EXEMPT','PARTIAL_EXEMPT')),
  CONSTRAINT component_version_pit_cap_ck CHECK (pit_treatment <> 'PARTIAL_EXEMPT'
                                                 OR pit_cap_param_key IS NOT NULL OR pit_cap_amount IS NOT NULL),
  CONSTRAINT component_version_cap_period_ck CHECK (pit_cap_period IS NULL OR pit_cap_period IN ('MONTH','YEAR')),
  CONSTRAINT component_version_proration_ck CHECK (
       (NOT is_prorated AND proration_basis IS NULL)
    OR (is_prorated AND proration_basis IN ('STD_DAYS','STD_HOURS','CALENDAR_DAYS'))),
  CONSTRAINT component_version_nonprorated_ck CHECK (non_prorated_rule IS NULL OR non_prorated_rule IN ('FULL','NONE','THRESHOLD')),
  CONSTRAINT component_version_threshold_ck CHECK (non_prorated_rule IS DISTINCT FROM 'THRESHOLD'
                                                   OR non_prorated_threshold_days > 0),
  CONSTRAINT component_version_gn_ck CHECK (gross_net_scope IN ('GROSS','NET','BOTH')),
  CONSTRAINT component_version_rounding_ck CHECK (rounding_mode IN ('HALF_UP','DOWN','UP','HALF_EVEN')
                                                  AND rounding_unit IN (0.0001, 0.01, 1, 10, 100, 1000)),
  CONSTRAINT component_version_status_ck CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT component_version_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT component_version_period_ex EXCLUDE USING gist
    (tenant_id WITH =, component_id WITH =, valid_during WITH &&) WHERE (status = 'ACTIVE')
);
COMMENT ON COLUMN pay.component_version.default_amount IS 'pii:P2 not encrypted (X-01)';
COMMENT ON COLUMN pay.component_version.rounding_unit  IS 'VND payable/declared values use >= 1; 0.0001/0.01 for USD contracts and intermediates -- P2 (BR-PAY-013)';

-- Salary structure: component bundle matched by LE / grade / category / contract type / pay group,
-- priority resolves multiple matches (BR-PAY-007).
CREATE TABLE pay.salary_structure (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                    text NOT NULL,
  name                    text NOT NULL,
  legal_entity_id         uuid,                               -- soft core; NULL = any
  grade_id                uuid,                               -- soft core.grade; NULL = any
  employee_category_code  text,
  worker_category_code    text,
  contract_type_code      text,
  pay_group_id            uuid,
  priority                smallint NOT NULL DEFAULT 100,
  valid_from              date NOT NULL,
  valid_to                date,
  valid_during            daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  status                  text NOT NULL DEFAULT 'ACTIVE',
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT salary_structure_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT salary_structure_group_fk FOREIGN KEY (tenant_id, pay_group_id) REFERENCES pay.pay_group (tenant_id, id),
  CONSTRAINT salary_structure_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT salary_structure_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT salary_structure_status_ck CHECK (status IN ('DRAFT','ACTIVE','INACTIVE'))
);

CREATE TABLE pay.structure_line (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  structure_id     uuid NOT NULL,
  component_id     uuid NOT NULL,
  default_amount   sys.money_amount,                          -- pii:P2
  formula_id       uuid,                                      -- overrides the component formula
  calc_order       int NOT NULL DEFAULT 0,                    -- display / tie-break only; the DAG decides evaluation order
  is_mandatory     boolean NOT NULL DEFAULT true,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT structure_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT structure_line_structure_fk FOREIGN KEY (tenant_id, structure_id) REFERENCES pay.salary_structure (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT structure_line_component_fk FOREIGN KEY (tenant_id, component_id) REFERENCES pay.salary_component (tenant_id, id),
  CONSTRAINT structure_line_formula_fk FOREIGN KEY (tenant_id, formula_id) REFERENCES pay.formula (tenant_id, id),
  CONSTRAINT structure_line_uq UNIQUE (tenant_id, structure_id, component_id)
);
COMMENT ON COLUMN pay.structure_line.default_amount IS 'pii:P2 not encrypted (X-01)';

-- =============================================================================
-- 3. PAY SCALE, PAY SCALE STEP, GRADE BAND  (gap §5 #7: owned here; BASELINE
--    capability, always on regardless of purchased modules, R2-18; Core keeps only
--    the grade catalogue). Published as pay.CompensationCatalogueChanged.
-- =============================================================================
CREATE TABLE pay.pay_scale (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  code              text NOT NULL,                            -- referenced by hr.salary_record.pay_scale_code (soft)
  name              text NOT NULL,
  legal_entity_id   uuid,                                     -- soft core; NULL = tenant-wide
  scale_type        text NOT NULL DEFAULT 'AMOUNT',           -- AMOUNT | COEFFICIENT (x reference amount)
  base_param_key    text,                                     -- COEFFICIENT: legal param of the base (e.g. BASE_SALARY) or NULL = base_amount
  base_amount       sys.money_amount,                         -- COEFFICIENT with company base
  currency_code     sys.currency_code NOT NULL DEFAULT 'VND',
  registered_ref    text,                                     -- registration with the labour authority, if any
  status            text NOT NULL DEFAULT 'ACTIVE',
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT pay_scale_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pay_scale_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT pay_scale_type_ck CHECK (scale_type IN ('AMOUNT','COEFFICIENT')),
  CONSTRAINT pay_scale_status_ck CHECK (status IN ('DRAFT','ACTIVE','INACTIVE'))
);

-- grade x step (ngạch x bậc), effective-dated (Pattern A).
CREATE TABLE pay.pay_scale_step (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  pay_scale_id         uuid NOT NULL,
  grade_id             uuid,                                  -- soft core.grade; NULL = single-grade scale
  step_code            text NOT NULL,                         -- referenced by hr.salary_record.pay_scale_step_code (soft)
  step_no              smallint NOT NULL,
  amount               sys.money_amount,                      -- AMOUNT scales
  coefficient          numeric(8,4),                          -- COEFFICIENT scales
  months_to_next_step  smallint,                              -- step progression
  valid_from           date NOT NULL,
  valid_to             date,
  valid_during         daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT pay_scale_step_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pay_scale_step_scale_fk FOREIGN KEY (tenant_id, pay_scale_id) REFERENCES pay.pay_scale (tenant_id, id),
  CONSTRAINT pay_scale_step_value_ck CHECK (amount IS NOT NULL OR coefficient IS NOT NULL),
  CONSTRAINT pay_scale_step_no_ck CHECK (step_no > 0),
  CONSTRAINT pay_scale_step_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT pay_scale_step_period_ex EXCLUDE USING gist
    (tenant_id WITH =, pay_scale_id WITH =, step_code WITH =,
     (coalesce(grade_id, '00000000-0000-0000-0000-000000000000'::uuid)) WITH =, valid_during WITH &&)
);
COMMENT ON COLUMN pay.pay_scale_step.amount IS 'pii:P2 (pay scale amounts are company-confidential, not personal)';

-- Grade band min-mid-max (offer / salary validation, IF-PAY-P-02).
CREATE TABLE pay.grade_band (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  grade_id          uuid NOT NULL,                            -- soft core.grade
  legal_entity_id   uuid,                                     -- soft core; NULL = tenant-wide
  currency_code     sys.currency_code NOT NULL DEFAULT 'VND',
  min_amount        sys.money_amount NOT NULL,
  mid_amount        sys.money_amount,
  max_amount        sys.money_amount NOT NULL,
  valid_from        date NOT NULL,
  valid_to          date,
  valid_during      daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT grade_band_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT grade_band_amounts_ck CHECK (min_amount >= 0 AND max_amount >= min_amount
                                          AND (mid_amount IS NULL OR mid_amount BETWEEN min_amount AND max_amount)),
  CONSTRAINT grade_band_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT grade_band_period_ex EXCLUDE USING gist
    (tenant_id WITH =, grade_id WITH =,
     (coalesce(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
     currency_code WITH =, valid_during WITH &&)
);

-- =============================================================================
-- 4. PROJECTIONS OF OTHER MODULES  (ext_<owner>_<entity>, DESIGN §2.2)
--    Event-fed, rebuildable from the owner's API ("re-sync"); each row carries
--    source_event_id / source_version. No exclusion constraints: events may arrive
--    out of order, consistency is checked when a run snapshot is sealed.
-- =============================================================================

-- employee_pay_profile: one row per employment = cached People facts (read-only copy,
-- People owns them) + payroll-only settings (structure override, PIT method override).
CREATE TABLE pay.employee_pay_profile (
  tenant_id                uuid NOT NULL,
  id                       uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id            uuid NOT NULL,                     -- -> hr.employment (soft)
  person_id                uuid NOT NULL,                     -- -> hr.person (soft); chunking key (ADR-0009 §3)
  legal_entity_id          uuid NOT NULL,                     -- soft core
  org_unit_id              uuid,                              -- soft core (data scope)
  employee_no              text NOT NULL,
  full_name                text NOT NULL,                     -- pii:P1
  work_email               text,                              -- pii:P1 (payslip e-mail target WORK)
  personal_email           text,                              -- pii:P2 (payslip e-mail target PERSONAL)
  employment_status        text NOT NULL,                     -- mirror of hr.employment.status
  hire_date                date NOT NULL,
  end_date                 date,
  worker_category_code     text,
  contract_type_code       text,                              -- current contract (IF-PAY-C-10)
  pay_basis                text,                              -- current GROSS | NET (cache of hr.salary_record)
  tax_residency            text,                              -- RESIDENT | NON_RESIDENT (current, IF-PAY-C-13)
  tax_residency_from       date,
  is_trade_union_member    boolean NOT NULL DEFAULT false,    -- IF-PAY-C-16
  trade_union_member_from  date,
  primary_purposes         jsonb NOT NULL DEFAULT '[]'::jsonb,-- effective-dated cache of hr.primary_employment for THIS employment:
                                                              -- [{"purpose":"PIT_FAMILY_DEDUCTION","from":"2027-01-01","to":null}, ...] (R2-16)
  tax_code_enc             bytea,                             -- pii:P3 cached personal MST (05/QTT, certificates)
  tax_code_bidx            bytea,
  tax_code_last4           text,
  si_number_enc            bytea,                             -- pii:P3 cached SI book number (D02-LT)
  si_number_last4          text,
  subject_key_version      int,                               -- saas.subject_key (PERSON, person_id)
  -- payroll-only settings
  salary_structure_id      uuid,                              -- override of rule-based structure matching
  pit_method_override      text,                              -- PROGRESSIVE | FLAT_10 | FLAT_20 | NONE (exceptional, audited)
  source_event_id          uuid,
  source_version           bigint,
  synced_at                timestamptz NOT NULL DEFAULT now(),
  created_at               timestamptz NOT NULL DEFAULT now(),
  created_by               uuid,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  updated_by               uuid,
  version                  int NOT NULL DEFAULT 0,
  CONSTRAINT employee_pay_profile_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT employee_pay_profile_employment_uq UNIQUE (tenant_id, employment_id),
  CONSTRAINT employee_pay_profile_structure_fk FOREIGN KEY (tenant_id, salary_structure_id) REFERENCES pay.salary_structure (tenant_id, id),
  CONSTRAINT employee_pay_profile_status_ck CHECK (employment_status IN ('PRE_HIRE','ACTIVE','SUSPENDED','TERMINATED','CANCELLED')),
  CONSTRAINT employee_pay_profile_basis_ck CHECK (pay_basis IS NULL OR pay_basis IN ('GROSS','NET')),
  CONSTRAINT employee_pay_profile_residency_ck CHECK (tax_residency IS NULL OR tax_residency IN ('RESIDENT','NON_RESIDENT')),
  CONSTRAINT employee_pay_profile_pit_ck CHECK (pit_method_override IS NULL
                                                OR pit_method_override IN ('PROGRESSIVE','FLAT_10','FLAT_20','NONE')),
  CONSTRAINT employee_pay_profile_purposes_ck CHECK (jsonb_typeof(primary_purposes) = 'array')
) WITH (fillfactor = 80);                                     -- update-heavy (DESIGN §10.2)
CREATE INDEX employee_pay_profile_person_ix ON pay.employee_pay_profile (tenant_id, person_id);
CREATE INDEX employee_pay_profile_scope_ix  ON pay.employee_pay_profile (tenant_id, legal_entity_id, org_unit_id);
CREATE INDEX employee_pay_profile_tax_ix    ON pay.employee_pay_profile (tenant_id, tax_code_bidx) WHERE tax_code_bidx IS NOT NULL;
COMMENT ON COLUMN pay.employee_pay_profile.tax_code_enc   IS 'pii:P3 AES-256-GCM, subject DEK of the person (copy of hr.person.tax_code_enc)';
COMMENT ON COLUMN pay.employee_pay_profile.si_number_enc  IS 'pii:P3 AES-256-GCM, subject DEK of the person (copy of hr.person.si_number_enc)';
COMMENT ON COLUMN pay.employee_pay_profile.personal_email IS 'pii:P2';
COMMENT ON COLUMN pay.employee_pay_profile.full_name      IS 'pii:P1';

-- Mirror of hr.salary_record versions (bi-temporal knowledge kept: recorded_at /
-- superseded_at), fed by people.SalaryChanged (sent to the Payroll consumer only).
CREATE TABLE pay.ext_hr_salary (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  salary_record_id          uuid NOT NULL,                    -- -> hr.salary_record.id (soft); one row per version
  employment_id             uuid NOT NULL,                    -- soft hr
  pay_basis                 text NOT NULL,
  salary_kind               text NOT NULL,
  base_salary_amount        sys.money_amount NOT NULL,        -- pii:P2
  agreed_net_amount         sys.money_amount,                 -- pii:P2
  si_salary_amount          sys.money_amount,                 -- pii:P2
  currency_code             sys.currency_code NOT NULL DEFAULT 'VND',
  pay_scale_code            text,
  pay_scale_step_code       text,
  probation_pct             numeric(5,2),
  allowances                jsonb NOT NULL DEFAULT '[]'::jsonb, -- [{"component_code":..,"amount":..,"is_in_si_salary":..}]
  net_guaranteed_components text[],                           -- -- P2 (BR-PAY-021 per-employee net scope)
  valid_from                date NOT NULL,
  valid_to                  date,
  valid_during              daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  recorded_at               timestamptz NOT NULL,             -- People transaction time (retro detection vs run watermark)
  superseded_at             timestamptz,
  source_event_id           uuid,
  source_version            bigint,
  synced_at                 timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_hr_salary_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_hr_salary_record_uq UNIQUE (tenant_id, salary_record_id),
  CONSTRAINT ext_hr_salary_basis_ck CHECK (pay_basis IN ('GROSS','NET')),
  CONSTRAINT ext_hr_salary_kind_ck CHECK (salary_kind IN ('MONTHLY','DAILY','HOURLY','PIECE')),
  CONSTRAINT ext_hr_salary_allowances_ck CHECK (jsonb_typeof(allowances) = 'array'),
  CONSTRAINT ext_hr_salary_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX ext_hr_salary_current_ix  ON pay.ext_hr_salary (tenant_id, employment_id, valid_from) WHERE superseded_at IS NULL;
CREATE INDEX ext_hr_salary_recorded_ix ON pay.ext_hr_salary (tenant_id, recorded_at);
COMMENT ON TABLE pay.ext_hr_salary IS 'Projection of hr.salary_record (People owns it). Amounts P2 plaintext (X-01); read via pay.v_ext_hr_salary_masked outside the engine.';
COMMENT ON COLUMN pay.ext_hr_salary.base_salary_amount IS 'pii:P2 not encrypted (X-01)';
COMMENT ON COLUMN pay.ext_hr_salary.agreed_net_amount  IS 'pii:P2 not encrypted (X-01)';
COMMENT ON COLUMN pay.ext_hr_salary.si_salary_amount   IS 'pii:P2 not encrypted (X-01)';

-- Bank split rules of the person (IF-PAY-C-14). The account NUMBER is NOT projected:
-- P3 values never travel in events (DESIGN §9.1); the bank-file job fetches it from the
-- People API and stores it encrypted on pay.bank_payment_line.
CREATE TABLE pay.ext_hr_bank_split (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  bank_account_id    uuid NOT NULL,                           -- -> hr.bank_account (soft)
  person_id          uuid NOT NULL,                           -- soft hr
  bank_code          text NOT NULL,                           -- core.bank (global, by code)
  branch_code        text,
  account_no_last4   text NOT NULL,
  holder_name        text NOT NULL,                           -- pii:P1
  currency_code      sys.currency_code NOT NULL DEFAULT 'VND',
  split_type         text NOT NULL,                           -- REMAINDER in P1; PERCENT / FIXED (2 accounts) -- P2 (BR-PAY-111)
  split_value        numeric(19,4),
  priority           smallint NOT NULL DEFAULT 1,
  valid_from         date NOT NULL,
  valid_to           date,
  status             text NOT NULL,
  source_event_id    uuid,
  source_version     bigint,
  synced_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_hr_bank_split_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_hr_bank_split_account_uq UNIQUE (tenant_id, bank_account_id),
  CONSTRAINT ext_hr_bank_split_type_ck CHECK (split_type IN ('REMAINDER','PERCENT','FIXED')),
  CONSTRAINT ext_hr_bank_split_status_ck CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','REJECTED','CLOSED'))
);
CREATE INDEX ext_hr_bank_split_person_ix ON pay.ext_hr_bank_split (tenant_id, person_id, valid_from);

-- PIT dependant count per claiming employment and month (IF-PAY-C-12; hr.pit_dependant).
-- Dependant identities for 05-3/BK-QTT are read from the People API at finalisation.
CREATE TABLE pay.ext_hr_dependant_count (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id     uuid NOT NULL,                            -- claiming employment (soft hr)
  month_date        sys.month_date NOT NULL,
  dependant_count   smallint NOT NULL,
  source_event_id   uuid,
  source_version    bigint,
  synced_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_hr_dependant_count_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_hr_dependant_count_uq UNIQUE (tenant_id, employment_id, month_date),
  CONSTRAINT ext_hr_dependant_count_ck CHECK (dependant_count >= 0)
);

-- Insurance participation per employment and insurance type (IF-PAY-C-15, BR-PPL-004,
-- R2-16/R3-03). The engine applies insurance.multi_contract_rule per type (LPS) to these rows.
CREATE TABLE pay.ext_hr_insurance_participation (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  source_record_id       uuid NOT NULL,                       -- People participation record id (soft)
  employment_id          uuid NOT NULL,                       -- soft hr
  person_id              uuid NOT NULL,                       -- soft hr
  insurance_type         text NOT NULL,                       -- SI (BHXH) | HI (BHYT) | UI (BHTN) | OA_OD (TNLĐ-BNN)
  participation_status   text NOT NULL,                       -- PARTICIPATING | EXEMPT | NOT_ELIGIBLE
  exemption_reason_code  text,                                -- pensioner, exempt expat ... (code list)
  hi_facility_code       text,                                -- KCB ban đầu (HI only)
  valid_from             date NOT NULL,
  valid_to               date,
  source_event_id        uuid,
  source_version         bigint,
  synced_at              timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_hr_insurance_participation_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_hr_insurance_participation_src_uq UNIQUE (tenant_id, source_record_id),
  CONSTRAINT ext_hr_insurance_participation_type_ck CHECK (insurance_type IN ('SI','HI','UI','OA_OD')),
  CONSTRAINT ext_hr_insurance_participation_status_ck CHECK (participation_status IN ('PARTICIPATING','EXEMPT','NOT_ELIGIBLE')),
  CONSTRAINT ext_hr_insurance_participation_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX ext_hr_insurance_participation_emp_ix ON pay.ext_hr_insurance_participation (tenant_id, employment_id, insurance_type, valid_from);
CREATE INDEX ext_hr_insurance_participation_person_ix ON pay.ext_hr_insurance_participation (tenant_id, person_id);

-- Pointer to the attendance result versions T&A has published (att.AttendancePeriodClosed,
-- ...Reopened, ...Locked, LeaverAttendanceClosed, RetroDeltaCreated). One row per result
-- version. Payroll never reads att tables; the result lines are pulled through the T&A
-- API at snapshot time. D10: scope / kind / versioning are those of att.attendance_result
-- (versions are numbered per period x scope_key, so the key is att_result_id).
CREATE TABLE pay.ext_att_result (
  tenant_id           uuid NOT NULL,
  id                  uuid NOT NULL DEFAULT sys.uuid_v7(),
  att_period_id       uuid NOT NULL,                          -- -> att.attendance_period (soft)
  att_result_id       uuid NOT NULL,                          -- -> att.attendance_result (soft)
  result_version      int NOT NULL,                           -- per (att_period_id, scope_key)
  result_scope        text NOT NULL DEFAULT 'PERIOD',         -- PERIOD | EMPLOYMENT (= att.attendance_result.scope)
  result_kind         text NOT NULL DEFAULT 'REGULAR',        -- REGULAR | EARLY_CLOSE | OFF_CYCLE_RETRO
  employment_id       uuid,                                   -- soft hr; EMPLOYMENT scope only
  scope_key           uuid GENERATED ALWAYS AS (coalesce(employment_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
  legal_entity_id     uuid NOT NULL,
  pay_group_id        uuid,                                   -- same schema, but no FK: projection fed before group setup
  period_start        date NOT NULL,
  period_end          date NOT NULL,
  result_checksum     text,
  result_status       text NOT NULL,                          -- PUBLISHED | WITHDRAWN | LOCKED (= att result status)
  att_period_status   text NOT NULL,                          -- OPEN | IN_REVIEW | CLOSED | LOCKED (latest known)
  is_current          boolean NOT NULL DEFAULT true,          -- current version of the period x scope_key
  closed_at           timestamptz,
  locked_at           timestamptz,
  source_event_id     uuid,
  source_version      bigint,
  synced_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_att_result_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_att_result_uq UNIQUE (tenant_id, att_result_id),
  CONSTRAINT ext_att_result_scope_ck CHECK (result_scope IN ('PERIOD','EMPLOYMENT')),
  CONSTRAINT ext_att_result_emp_ck CHECK ((result_scope = 'EMPLOYMENT') = (employment_id IS NOT NULL)),
  CONSTRAINT ext_att_result_kind_ck CHECK (result_kind IN ('REGULAR','EARLY_CLOSE','OFF_CYCLE_RETRO')),
  CONSTRAINT ext_att_result_kind_scope_ck CHECK ((result_kind = 'REGULAR') = (result_scope = 'PERIOD')),
  CONSTRAINT ext_att_result_version_ck CHECK (result_version >= 1),
  CONSTRAINT ext_att_result_status_ck CHECK (result_status IN ('PUBLISHED','WITHDRAWN','LOCKED')),
  CONSTRAINT ext_att_result_period_status_ck CHECK (att_period_status IN ('OPEN','IN_REVIEW','CLOSED','LOCKED')),
  CONSTRAINT ext_att_result_dates_ck CHECK (period_end >= period_start)
);
-- current version per period x scope (PERIOD, or each EMPLOYMENT); off-cycle retro
-- results of a leaver may accumulate, as in att.attendance_result_live_uq
CREATE UNIQUE INDEX ext_att_result_current_uq ON pay.ext_att_result (tenant_id, att_period_id, scope_key)
  WHERE is_current AND result_kind <> 'OFF_CYCLE_RETRO';
CREATE INDEX ext_att_result_group_ix ON pay.ext_att_result (tenant_id, pay_group_id, period_start);
CREATE INDEX ext_att_result_employment_ix ON pay.ext_att_result (tenant_id, employment_id) WHERE employment_id IS NOT NULL;

-- Residency check (BR-PAY-065, 183-day test; recommendation to HR, People decides).
CREATE TABLE pay.residency_check (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id               uuid NOT NULL,                      -- soft hr
  employment_id           uuid,                               -- soft hr
  tax_year                smallint NOT NULL,
  days_in_calendar_year   smallint NOT NULL,
  window_start            date,                               -- 12-month window from first arrival
  days_in_window          smallint,
  computed_residency      text NOT NULL,
  people_residency        text,
  recommendation_status   text NOT NULL DEFAULT 'OPEN',
  computed_at             timestamptz NOT NULL DEFAULT now(),
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT residency_check_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT residency_check_residency_ck CHECK (computed_residency IN ('RESIDENT','NON_RESIDENT')
                                                 AND (people_residency IS NULL OR people_residency IN ('RESIDENT','NON_RESIDENT'))),
  CONSTRAINT residency_check_status_ck CHECK (recommendation_status IN ('OPEN','SENT','ACCEPTED','DISMISSED','NO_ACTION'))
);
CREATE INDEX residency_check_person_ix ON pay.residency_check (tenant_id, person_id, tax_year);

-- =============================================================================
-- 5. VARIABLE INPUTS  (BR-PAY-029; import via Core import engine, API -- P2)
-- =============================================================================
CREATE TABLE pay.input_batch (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  pay_group_id     uuid,
  pay_period_id    uuid,
  source           text NOT NULL,                             -- MANUAL | IMPORT | API (P2) | PEOPLE_EVENT | RECURRING
  import_job_id    uuid,                                      -- -> core.import_job (soft)
  file_sha256      text,
  batch_version    int NOT NULL DEFAULT 1,                    -- re-import of the same file creates version + 1
  row_count        int NOT NULL DEFAULT 0,
  error_count      int NOT NULL DEFAULT 0,
  status           text NOT NULL DEFAULT 'DRAFT',
  applied_at       timestamptz,
  rolled_back_at   timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT input_batch_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT input_batch_group_fk FOREIGN KEY (tenant_id, pay_group_id) REFERENCES pay.pay_group (tenant_id, id),
  CONSTRAINT input_batch_period_fk FOREIGN KEY (tenant_id, pay_period_id) REFERENCES pay.pay_period (tenant_id, id),
  CONSTRAINT input_batch_source_ck CHECK (source IN ('MANUAL','IMPORT','API','PEOPLE_EVENT','RECURRING')),
  CONSTRAINT input_batch_import_ck CHECK (source <> 'IMPORT' OR import_job_id IS NOT NULL),
  CONSTRAINT input_batch_status_ck CHECK (status IN ('DRAFT','VALIDATED','APPLIED','ROLLED_BACK','LOCKED'))
);
COMMENT ON COLUMN pay.input_batch.source IS 'API -- P2';

CREATE TABLE pay.variable_input (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  input_batch_id    uuid,
  pay_period_id     uuid NOT NULL,
  employment_id     uuid NOT NULL,                            -- soft hr
  component_id      uuid NOT NULL,
  qty               sys.quantity,
  rate              numeric(24,8),
  amount            sys.money_amount,                         -- pii:P2
  currency_code     sys.currency_code NOT NULL DEFAULT 'VND',
  effective_date    date,                                     -- segment placement inside the period
  target_run_type   text NOT NULL DEFAULT 'REGULAR',
  source_type       text,                                     -- REWARD | KPI | ADVANCE_DEDUCTION | EXPENSE (P2) | MANUAL ...
  source_ref_id     uuid,                                     -- e.g. People reward id (soft); traceability BR-PAY-086
  status            text NOT NULL DEFAULT 'ACTIVE',
  consumed_run_id   uuid,                                     -- set when an APPROVED run used it (same schema, soft to avoid order coupling)
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT variable_input_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT variable_input_batch_fk FOREIGN KEY (tenant_id, input_batch_id) REFERENCES pay.input_batch (tenant_id, id),
  CONSTRAINT variable_input_period_fk FOREIGN KEY (tenant_id, pay_period_id) REFERENCES pay.pay_period (tenant_id, id),
  CONSTRAINT variable_input_component_fk FOREIGN KEY (tenant_id, component_id) REFERENCES pay.salary_component (tenant_id, id),
  CONSTRAINT variable_input_value_ck CHECK (amount IS NOT NULL OR qty IS NOT NULL),
  CONSTRAINT variable_input_run_type_ck CHECK (target_run_type IN ('REGULAR','OFFCYCLE','BONUS','FINAL_SETTLEMENT','CORRECTION')),
  CONSTRAINT variable_input_status_ck CHECK (status IN ('ACTIVE','CANCELLED','CONSUMED'))
);
CREATE INDEX variable_input_period_ix ON pay.variable_input (tenant_id, pay_period_id, employment_id) WHERE status = 'ACTIVE';
CREATE INDEX variable_input_batch_ix ON pay.variable_input (tenant_id, input_batch_id);
COMMENT ON COLUMN pay.variable_input.amount IS 'pii:P2 not encrypted (X-01)';

-- =============================================================================
-- 6. PAYROLL RUN  (snapshot-first, ADR-0009; immutable results, ADR-0029)
-- =============================================================================

-- 6.1 Config snapshot, DEDUPLICATED by config_sha256 (review M6): identical resolved
-- configuration across runs is stored once; payroll_run.config_snapshot_id points to it.
-- payload = resolved component versions, formula versions (expression text + hash),
-- structure lines, rounding rules and the resolved legal value set for the period
-- (pack id + override version + the values themselves). Append-only.
CREATE TABLE pay.run_config_snapshot (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  config_sha256        text NOT NULL,                         -- hex SHA-256 of the canonical JSON (computed by the app)
  legal_pack_id        uuid NOT NULL,                         -- -> saas.legal_param_pack (soft)
  legal_pack_version   bigint NOT NULL,
  override_version     int NOT NULL DEFAULT 0,                -- saas.legal_param_override_set version (0 = none)
  config_version       text NOT NULL,                         -- human-readable config release label
  payload              jsonb NOT NULL,                        -- TOAST lz4 (database default, 000)
  payload_bytes        int,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  CONSTRAINT run_config_snapshot_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT run_config_snapshot_sha_uq UNIQUE (tenant_id, config_sha256),
  CONSTRAINT run_config_snapshot_sha_ck CHECK (config_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT run_config_snapshot_payload_ck CHECK (jsonb_typeof(payload) = 'object')
);
COMMENT ON TABLE pay.run_config_snapshot IS
  'M6: one row per distinct resolved configuration (UNIQUE tenant_id, config_sha256); many runs -> one config. '
  'Insert with ON CONFLICT (tenant_id, config_sha256) DO NOTHING and reuse the id. Append-only.';

-- 6.2 Run header.
--   status     business lifecycle (gap §7.1, BR-PAY-071): DRAFT CALCULATED SUBMITTED APPROVED
--              PAID CLOSED REJECTED UNLOCKED REVERSED CANCELLED
--              CANCELLED (D3, 2026-10-21): terminal; only from DRAFT, CALCULATED or REJECTED
--              of a run that was never approved (never after APPROVED, so not after an
--              UNLOCK either); needs cancel_reason + cancelled_by + cancelled_at. Before
--              the status change the service releases every ACTIVE mirrored close hold
--              through the T&A API (att.attendance_result_hold.release_reason = CANCELLED)
--              and updates the mirror rows (hold_status RELEASED, release_reason CANCELLED);
--              pay.tg_payroll_run_guard refuses CANCELLED while a mirrored hold is ACTIVE.
--   calc_phase technical calculation state (review M8; ADR-0009 §1): NONE SNAPSHOTTING
--              CALCULATING DONE FAILED, independent of status (SNAPSHOTTING/CALCULATING only in DRAFT)
--   Attendance versions used are rows of pay.run_attendance_version (close-hold protocol).
CREATE TABLE pay.payroll_run (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_no                 text NOT NULL,
  pay_group_id           uuid NOT NULL,
  pay_period_id          uuid NOT NULL,
  legal_entity_id        uuid NOT NULL,                       -- soft core (from the pay group)
  period_month           date NOT NULL,                       -- = pay_period.period_month; partition key of result_line
  run_type               text NOT NULL,
  run_subtype_code       text,                                -- THIRTEENTH_MONTH | TET_BONUS | ADVANCE | CASUAL | PIT_FINALISATION_ADJ ...
  calc_mode              text NOT NULL DEFAULT 'PROVISIONAL',
  status                 text NOT NULL DEFAULT 'DRAFT',
  calc_phase             text NOT NULL DEFAULT 'NONE',        -- M8
  calc_seq               int NOT NULL DEFAULT 0,              -- incremented per calculation attempt
  calc_started_at        timestamptz,
  calc_finished_at       timestamptz,
  calc_error             text,
  chunk_count            int,
  chunks_done            int NOT NULL DEFAULT 0,
  pit_month_mode         text NOT NULL DEFAULT 'PAYMENT_MONTH',-- BR-PAY-052: PAYMENT_MONTH | ACCRUAL_MONTH
  tax_month              sys.month_date NOT NULL,             -- month whose PIT the run contributes to
  payment_date           date,
  currency_code          sys.currency_code NOT NULL DEFAULT 'VND',
  population_filter      jsonb,                               -- subset / single employee -- P2 (BR-PAY-073); NULL = whole pay group
  -- reproducibility stamps (ADR-0029 §3)
  config_snapshot_id     uuid,                                -- M6: shared, deduplicated config
  legal_pack_id          uuid,                                -- soft saas
  legal_pack_version     bigint,
  override_version       int,
  config_version         text,
  code_version           text,                                -- engine release tag + formula-sandbox version
  people_watermark       timestamptz,                         -- M7: People knowledge cut (recorded_at <= watermark)
  input_cutoff_at        timestamptz,
  -- change detection (gap §7.2 steps 6/7/9)
  is_stale               boolean NOT NULL DEFAULT false,      -- attendance re-opened / hold released on request
  stale_reason           text,
  inputs_changed         boolean NOT NULL DEFAULT false,      -- People event after the watermark, unresolved
  inputs_changed_at      timestamptz,
  -- approval (Core workflow)
  workflow_instance_id   uuid,                                -- soft core
  approval_version       int NOT NULL DEFAULT 0,              -- incremented per submission
  submitted_at           timestamptz,
  submitted_by           uuid,
  approved_at            timestamptz,
  approved_by            uuid,
  rejected_at            timestamptz,
  rejected_by            uuid,
  cancelled_at           timestamptz,                         -- D3
  cancelled_by           uuid,
  cancel_reason          text,
  paid_at                timestamptz,
  closed_at              timestamptz,
  -- unlock (privileged + second approval; details in pay.run_unlock)
  unlock_seq             int NOT NULL DEFAULT 0,
  unlocked_at            timestamptz,
  -- reversal -- P2 (BR-PAY-079)
  reverses_run_id        uuid,
  reversed_at            timestamptz,
  -- links
  final_settlement_id    uuid,                                -- FINAL_SETTLEMENT runs (FK added in section 10)
  comparison_import_job_id uuid,                              -- COMPARISON: customer payroll register import (soft core.import_job)
  -- totals (pii:P2 aggregates)
  headcount              int,
  gross_total            sys.money_amount,
  net_total              sys.money_amount,
  pit_total              sys.money_amount,
  si_ee_total            sys.money_amount,
  si_er_total            sys.money_amount,
  employer_cost_total    sys.money_amount,
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT payroll_run_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT payroll_run_period_fk FOREIGN KEY (tenant_id, pay_group_id, pay_period_id)
    REFERENCES pay.pay_period (tenant_id, pay_group_id, id),
  CONSTRAINT payroll_run_config_fk FOREIGN KEY (tenant_id, config_snapshot_id) REFERENCES pay.run_config_snapshot (tenant_id, id),
  CONSTRAINT payroll_run_reverses_fk FOREIGN KEY (tenant_id, reverses_run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT payroll_run_no_uq UNIQUE (tenant_id, run_no),
  CONSTRAINT payroll_run_type_ck CHECK (run_type IN
    ('REGULAR','OFFCYCLE','BONUS','FINAL_SETTLEMENT','CORRECTION','COMPARISON')),
  CONSTRAINT payroll_run_mode_ck CHECK (calc_mode IN ('PROVISIONAL','FINAL')),
  CONSTRAINT payroll_run_status_ck CHECK (status IN
    ('DRAFT','CALCULATED','SUBMITTED','APPROVED','PAID','CLOSED','REJECTED','UNLOCKED','REVERSED','CANCELLED')),
  CONSTRAINT payroll_run_phase_ck CHECK (calc_phase IN ('NONE','SNAPSHOTTING','CALCULATING','DONE','FAILED')),
  -- D3: cancellation is stamped and justified; a cancelled run was never approved
  CONSTRAINT payroll_run_cancelled_ck CHECK (
       (status = 'CANCELLED') = (cancelled_at IS NOT NULL)
   AND (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason) <> ''
                                  AND cancelled_by IS NOT NULL AND approved_at IS NULL))),
  CONSTRAINT payroll_run_pit_mode_ck CHECK (pit_month_mode IN ('PAYMENT_MONTH','ACCRUAL_MONTH')),
  CONSTRAINT payroll_run_month_ck CHECK (extract(day FROM period_month) = 1),
  -- calculation in progress only while DRAFT; CALCULATED and later require a finished calculation
  CONSTRAINT payroll_run_phase_status_ck CHECK (
       (calc_phase NOT IN ('SNAPSHOTTING','CALCULATING') OR status = 'DRAFT')
   AND (status IN ('DRAFT','REJECTED','UNLOCKED','CANCELLED') OR calc_phase = 'DONE')),
  -- COMPARISON (BR-SAAS-073) and PROVISIONAL runs are never approvable
  CONSTRAINT payroll_run_approvable_ck CHECK (
       status IN ('DRAFT','CALCULATED','REJECTED','UNLOCKED','CANCELLED')
    OR (calc_mode = 'FINAL' AND run_type <> 'COMPARISON')),
  -- submitted and later: fully stamped
  CONSTRAINT payroll_run_stamped_ck CHECK (
       status IN ('DRAFT','CALCULATED','REJECTED','UNLOCKED','CANCELLED')
    OR (config_snapshot_id IS NOT NULL AND people_watermark IS NOT NULL AND code_version IS NOT NULL
        AND legal_pack_id IS NOT NULL AND submitted_at IS NOT NULL)),
  CONSTRAINT payroll_run_approved_ck CHECK (status NOT IN ('APPROVED','PAID','CLOSED','REVERSED') OR approved_at IS NOT NULL),
  CONSTRAINT payroll_run_paid_ck CHECK (status NOT IN ('PAID','CLOSED') OR paid_at IS NOT NULL),
  CONSTRAINT payroll_run_settlement_ck CHECK (run_type <> 'FINAL_SETTLEMENT' OR final_settlement_id IS NOT NULL),
  CONSTRAINT payroll_run_stale_ck CHECK (NOT is_stale OR stale_reason IS NOT NULL),
  CONSTRAINT payroll_run_chunks_ck CHECK (chunks_done >= 0 AND (chunk_count IS NULL OR chunks_done <= chunk_count))
);
CREATE INDEX payroll_run_period_ix ON pay.payroll_run (tenant_id, pay_period_id, run_type);
CREATE INDEX payroll_run_status_ix ON pay.payroll_run (tenant_id, status) WHERE status NOT IN ('CLOSED','REVERSED','CANCELLED');
-- BR-PAY-087 legal-entity lock state: latest approved period per LE
CREATE INDEX payroll_run_le_lock_ix ON pay.payroll_run (tenant_id, legal_entity_id, period_month)
  WHERE status IN ('APPROVED','PAID','CLOSED');
CREATE INDEX payroll_run_config_ix ON pay.payroll_run (tenant_id, config_snapshot_id);
COMMENT ON COLUMN pay.payroll_run.people_watermark IS 'M7: People data with recorded_at <= watermark is in the snapshot; later effective-dated changes -> inputs_changed / retro.';
COMMENT ON COLUMN pay.payroll_run.calc_phase IS 'M8: technical calculation phase (ADR-0009), separate from the business status.';
COMMENT ON COLUMN pay.payroll_run.run_type IS 'COMPARISON = one-month parallel comparison with the customer payroll (BR-SAAS-073, R2-03), never approvable.';

-- 6.3 Attendance versions used by the run + close-hold MIRROR (gap §7, ADR-0019,
-- BR-PAY-082..084; D1/D10 2026-10-21).
-- att.attendance_result_hold is the ONLY authority for hold state (one ACTIVE hold per
-- period x scope, enforced there). This table mirrors, per run, the holds Payroll
-- acquired synchronously through the T&A API: one row per att hold (att_hold_id), with
-- the held result (att_result_id, version, scope, kind, employment_id). A run may
-- mirror several holds of the same period (PERIOD scope and/or EMPLOYMENT scope of
-- leavers, e.g. a REGULAR run and a FINAL_SETTLEMENT run each holding their scope).
-- No cross-run uniqueness here (it would contradict per-employment holds, D1).
-- When a hold is released and a new one acquired for the same run (REJECTED ->
-- recalculation, REOPEN_REQUESTED -> new version, UNLOCK), the old row is kept with
-- superseded_at stamped and a new row is inserted; the state-machine gate looks only
-- at rows with superseded_at IS NULL. hold_status NONE = version used by a DRAFT
-- calculation, hold not yet acquired (att_hold_id NULL).
CREATE TABLE pay.run_attendance_version (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  payroll_run_id       uuid NOT NULL,
  att_period_id        uuid NOT NULL,                         -- soft att.attendance_period
  att_result_id        uuid NOT NULL,                         -- soft att.attendance_result
  result_version       int NOT NULL,
  result_scope         text NOT NULL DEFAULT 'PERIOD',        -- PERIOD | EMPLOYMENT (= att hold scope)
  result_kind          text NOT NULL DEFAULT 'REGULAR',       -- REGULAR | EARLY_CLOSE | OFF_CYCLE_RETRO
  employment_id        uuid,                                  -- soft hr; EMPLOYMENT scope only
  scope_key            uuid GENERATED ALWAYS AS (coalesce(employment_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
  result_checksum      text,
  hold_status          text NOT NULL DEFAULT 'NONE',          -- NONE | ACTIVE | RELEASED | CONSUMED (mirror)
  att_hold_id          uuid,                                  -- soft att.attendance_result_hold (authority)
  hold_acquired_at     timestamptz,
  hold_released_at     timestamptz,
  release_reason       text,                                  -- REJECTED | WITHDRAWN | CANCELLED | REOPEN_REQUESTED | UNLOCKED
  locked_confirmed_at  timestamptz,                           -- att.AttendancePeriodLocked received (hold CONSUMED)
  superseded_at        timestamptz,                           -- replaced by a later mirror row of the same run
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT run_attendance_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT run_attendance_version_run_fk FOREIGN KEY (tenant_id, payroll_run_id) REFERENCES pay.payroll_run (tenant_id, id),
  -- D1: one mirror row per att hold per run
  CONSTRAINT run_attendance_version_hold_uq UNIQUE (tenant_id, payroll_run_id, att_hold_id),
  CONSTRAINT run_attendance_version_scope_ck CHECK (result_scope IN ('PERIOD','EMPLOYMENT')),
  CONSTRAINT run_attendance_version_scope_emp_ck CHECK ((result_scope = 'EMPLOYMENT') = (employment_id IS NOT NULL)),
  CONSTRAINT run_attendance_version_kind_ck CHECK (result_kind IN ('REGULAR','EARLY_CLOSE','OFF_CYCLE_RETRO')),
  CONSTRAINT run_attendance_version_kind_scope_ck CHECK ((result_kind = 'REGULAR') = (result_scope = 'PERIOD')),
  CONSTRAINT run_attendance_version_hold_ck CHECK (hold_status IN ('NONE','ACTIVE','RELEASED','CONSUMED')),
  CONSTRAINT run_attendance_version_active_ck CHECK ((hold_status = 'NONE') = (att_hold_id IS NULL)
                                                    AND (hold_status = 'NONE' OR hold_acquired_at IS NOT NULL)),
  CONSTRAINT run_attendance_version_release_ck CHECK (hold_status <> 'RELEASED'
    OR (hold_released_at IS NOT NULL AND release_reason IN ('REJECTED','WITHDRAWN','CANCELLED','REOPEN_REQUESTED','UNLOCKED'))),
  CONSTRAINT run_attendance_version_consumed_ck CHECK (hold_status <> 'CONSUMED' OR locked_confirmed_at IS NOT NULL),
  -- only a row that no longer holds anything can be superseded
  CONSTRAINT run_attendance_version_superseded_ck CHECK (superseded_at IS NULL OR hold_status IN ('NONE','RELEASED'))
);
-- within ONE run: a result is used once, and one regular / early-close version per
-- period x scope (off-cycle retro results of a leaver may be several)
CREATE UNIQUE INDEX run_attendance_version_result_uq ON pay.run_attendance_version (tenant_id, payroll_run_id, att_result_id)
  WHERE superseded_at IS NULL;
CREATE UNIQUE INDEX run_attendance_version_scope_uq ON pay.run_attendance_version (tenant_id, payroll_run_id, att_period_id, scope_key)
  WHERE superseded_at IS NULL AND result_kind <> 'OFF_CYCLE_RETRO';
CREATE INDEX run_attendance_version_period_ix ON pay.run_attendance_version (tenant_id, att_period_id, result_version);
CREATE INDEX run_attendance_version_hold_ix ON pay.run_attendance_version (tenant_id, att_hold_id) WHERE att_hold_id IS NOT NULL;
COMMENT ON TABLE pay.run_attendance_version IS
  'D1 mirror of att.attendance_result_hold per run (authority = att). SUBMITTED/APPROVED require every current '
  '(superseded_at IS NULL) row ACTIVE; APPROVED consumes them (att.AttendancePeriodLocked -> CONSUMED); CANCELLED requires '
  'none ACTIVE (released with reason CANCELLED by the T&A API). att.AttendancePeriodReopened on a referenced period sets '
  'payroll_run.is_stale.';

-- 6.4 Input snapshot: one per run x employment x calculation attempt (calc_seq), written once
-- before calculation (ADR-0009 §2). Never updated. Snapshots referenced by approved results
-- (run_employee of an approved run, run_result_history) can never be deleted.
-- Range-partitioned by YEAR on period_month (DESIGN §10.1, ~110 GB/yr at sizing scale).
CREATE TABLE pay.run_input_snapshot (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  period_month     date NOT NULL,                             -- partition key (= payroll_run.period_month)
  run_id           uuid NOT NULL,
  employment_id    uuid NOT NULL,                             -- soft hr
  calc_seq         int NOT NULL,
  payload          jsonb NOT NULL,                            -- every input + source versions (TOAST lz4, DB default)
  payload_sha256   text NOT NULL,                             -- over canonical JSON
  source_versions  jsonb NOT NULL,                            -- {"salary_records":[{"id":..,"recorded_at":..}],"attendance":[{"result_id":..,"version":..,"checksum":..}],...}
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  CONSTRAINT run_input_snapshot_pk PRIMARY KEY (tenant_id, id, period_month),
  CONSTRAINT run_input_snapshot_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT run_input_snapshot_uq UNIQUE (tenant_id, run_id, employment_id, calc_seq, period_month),
  CONSTRAINT run_input_snapshot_month_ck CHECK (extract(day FROM period_month) = 1),
  CONSTRAINT run_input_snapshot_sha_ck CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
) PARTITION BY RANGE (period_month);
COMMENT ON COLUMN pay.run_input_snapshot.payload IS 'pii:P2 (contains salary amounts, dependant counts); P3 values are never copied in (ids/last4 only)';

-- 6.5 Run employee (result header). Written by chunk workers (COPY; delete-and-rewrite
-- per chunk while calc_phase = CALCULATING). Frozen by pay.tg_run_immutable.
CREATE TABLE pay.run_employee (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_id                  uuid NOT NULL,
  employment_id           uuid NOT NULL,                      -- soft hr
  person_id               uuid NOT NULL,                      -- soft hr (concurrent employments share a chunk)
  legal_entity_id         uuid NOT NULL,                      -- soft core
  org_unit_id             uuid,                               -- soft core (data scope)
  cost_centre_id          uuid,                               -- soft core (default allocation in the GL export)
  period_month            date NOT NULL,
  chunk_no                int NOT NULL,
  calc_seq                int NOT NULL,
  input_snapshot_id       uuid NOT NULL,
  status                  text NOT NULL DEFAULT 'OK',         -- OK | WARNING | EXCEPTION | EXCLUDED | ERROR
  pay_basis               text NOT NULL DEFAULT 'GROSS',
  gross_amount            sys.money_amount NOT NULL DEFAULT 0,
  taxable_income_amount   sys.money_amount NOT NULL DEFAULT 0,
  si_ee_amount            sys.money_amount NOT NULL DEFAULT 0,
  si_er_amount            sys.money_amount NOT NULL DEFAULT 0,
  pit_amount              sys.money_amount NOT NULL DEFAULT 0,
  deduction_amount        sys.money_amount NOT NULL DEFAULT 0,
  net_amount              sys.money_amount NOT NULL DEFAULT 0,
  employer_cost_amount    sys.money_amount NOT NULL DEFAULT 0,
  currency_code           sys.currency_code NOT NULL DEFAULT 'VND',
  fx_rate                 numeric(18,8),                      -- -- P2 (BR-PAY-013 multi-currency)
  exception_count         int NOT NULL DEFAULT 0,
  exceptions              jsonb,                              -- [{"code":"BELOW_MIN_WAGE","severity":"BLOCK"}, ...] (BR-PAY-074)
  result_sha256           text,                               -- hash of the ordered result lines (replay check)
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  CONSTRAINT run_employee_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT run_employee_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT run_employee_snapshot_fk FOREIGN KEY (tenant_id, input_snapshot_id, period_month)
    REFERENCES pay.run_input_snapshot (tenant_id, id, period_month),
  CONSTRAINT run_employee_uq UNIQUE (tenant_id, run_id, employment_id),
  CONSTRAINT run_employee_run_id_uq UNIQUE (tenant_id, run_id, id),     -- target of result_line composite FK
  CONSTRAINT run_employee_status_ck CHECK (status IN ('OK','WARNING','EXCEPTION','EXCLUDED','ERROR')),
  CONSTRAINT run_employee_basis_ck CHECK (pay_basis IN ('GROSS','NET')),
  CONSTRAINT run_employee_month_ck CHECK (extract(day FROM period_month) = 1),
  CONSTRAINT run_employee_sha_ck CHECK (result_sha256 IS NULL OR result_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX run_employee_chunk_ix ON pay.run_employee (tenant_id, run_id, chunk_no);
CREATE INDEX run_employee_employment_ix ON pay.run_employee (tenant_id, employment_id, period_month);
CREATE INDEX run_employee_scope_ix ON pay.run_employee (tenant_id, org_unit_id, period_month);
CREATE INDEX run_employee_snapshot_ix ON pay.run_employee (tenant_id, input_snapshot_id);
COMMENT ON COLUMN pay.run_employee.net_amount   IS 'pii:P2 not encrypted (X-01); read through pay.v_run_employee_masked';
COMMENT ON COLUMN pay.run_employee.gross_amount IS 'pii:P2 not encrypted (X-01)';

-- 6.6 Result line: component x segment. Range-partitioned MONTHLY on period_month;
-- PK includes the partition key. ~72M rows/month at sizing scale (DESIGN §8/§10).
-- Append-only per calculation; frozen by pay.tg_run_immutable once SUBMITTED.
CREATE TABLE pay.result_line (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  period_month         date NOT NULL,                         -- partition key (= payroll_run.period_month)
  run_id               uuid NOT NULL,
  run_employee_id      uuid NOT NULL,
  employment_id        uuid NOT NULL,                         -- soft hr (denormalised for retro lookups)
  component_id         uuid NOT NULL,
  component_code       text NOT NULL,                         -- denormalised, stable
  component_version_id uuid,
  line_seq             int NOT NULL,                          -- stable order (hashing)
  segment_key          text NOT NULL DEFAULT '-',             -- e.g. '2027-03-01..2027-03-14' (BR-PAY-024)
  segment_from         date,
  segment_to           date,
  qty                  sys.quantity,
  rate                 numeric(24,8),                         -- unit price, coefficient or percentage
  amount               sys.money_amount NOT NULL,             -- rounded canonical value
  amount_unrounded     numeric(24,8) NOT NULL,                -- for penny-level reconciliation (BR-PAY-012)
  taxable_amount       sys.money_amount,
  exempt_amount        sys.money_amount,
  si_basis_amount      sys.money_amount,
  tu_basis_amount      sys.money_amount,
  retro_of_period      date,                                  -- origin month for retro lines
  retro_item_id        uuid,                                  -- same schema; no FK (retro_item defined later, rows rewritten per calc)
  source_ref           jsonb,                                 -- traceability: {"type":"people.RewardApproved","id":..} (BR-PAY-086)
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  CONSTRAINT result_line_pk PRIMARY KEY (tenant_id, id, period_month),
  CONSTRAINT result_line_run_employee_fk FOREIGN KEY (tenant_id, run_id, run_employee_id)
    REFERENCES pay.run_employee (tenant_id, run_id, id) ON DELETE CASCADE,
  CONSTRAINT result_line_component_fk FOREIGN KEY (tenant_id, component_id) REFERENCES pay.salary_component (tenant_id, id),
  CONSTRAINT result_line_month_ck CHECK (extract(day FROM period_month) = 1),
  CONSTRAINT result_line_retro_ck CHECK (retro_of_period IS NULL OR extract(day FROM retro_of_period) = 1),
  CONSTRAINT result_line_segment_ck CHECK (segment_to IS NULL OR segment_from IS NULL OR segment_to >= segment_from)
) PARTITION BY RANGE (period_month);
CREATE INDEX result_line_run_employee_ix ON pay.result_line (tenant_id, run_employee_id);
CREATE INDEX result_line_run_component_ix ON pay.result_line (tenant_id, run_id, component_code);
COMMENT ON TABLE pay.result_line IS
  'Monthly range partitions on period_month (PK includes it). Hot 36 months, then compacted into pay.result_archive and '
  'the partition is detached and dropped (ADR-0029 §6). Autovacuum insert scale factor 0.05 is set per partition by the '
  'partition job (storage parameters cannot be set on a partitioned parent).';
COMMENT ON COLUMN pay.result_line.amount           IS 'pii:P2 not encrypted (X-01); read through pay.v_result_line_masked';
COMMENT ON COLUMN pay.result_line.amount_unrounded IS 'pii:P2 numeric(24,8) before rounding (DESIGN §4.6)';

-- 6.7 Unlock log (privileged + second approval, BR-PAY-071). Append-only.
CREATE TABLE pay.run_unlock (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_id               uuid NOT NULL,
  unlock_seq           int NOT NULL,
  prior_status         text NOT NULL,                         -- APPROVED (PAID runs are reversed, not unlocked)
  prior_approved_at    timestamptz NOT NULL,
  prior_approved_by    uuid,
  prior_totals         jsonb NOT NULL,                        -- run header totals before unlock (pii:P2)
  reason               text NOT NULL,
  requested_by         uuid NOT NULL,
  approved_by          uuid NOT NULL,                         -- second approver
  workflow_instance_id uuid,                                  -- soft core
  unlocked_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  CONSTRAINT run_unlock_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT run_unlock_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT run_unlock_seq_uq UNIQUE (tenant_id, run_id, unlock_seq),
  CONSTRAINT run_unlock_seq_ck CHECK (unlock_seq > 0),
  CONSTRAINT run_unlock_prior_ck CHECK (prior_status = 'APPROVED'),
  CONSTRAINT run_unlock_four_eyes_ck CHECK (approved_by <> requested_by)
);

-- 6.8 Result history: the approved result of each employee saved before an UNLOCK
-- recalculation (keyed by unlock_seq). Nothing approved is ever lost. Append-only.
CREATE TABLE pay.run_result_history (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_id                  uuid NOT NULL,
  unlock_seq              int NOT NULL,
  run_employee_id         uuid NOT NULL,                      -- id of the replaced run_employee row (no FK: it is deleted afterwards)
  employment_id           uuid NOT NULL,
  period_month            date NOT NULL,
  input_snapshot_id       uuid NOT NULL,
  header                  jsonb NOT NULL,                     -- the run_employee row (pii:P2)
  lines                   jsonb NOT NULL,                     -- array of result_line rows (pii:P2)
  result_sha256           text,
  archived_at             timestamptz NOT NULL DEFAULT now(),
  archived_by             uuid,
  CONSTRAINT run_result_history_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT run_result_history_unlock_fk FOREIGN KEY (tenant_id, run_id, unlock_seq)
    REFERENCES pay.run_unlock (tenant_id, run_id, unlock_seq),
  -- keeps the approved input snapshot alive for reproduction
  CONSTRAINT run_result_history_snapshot_fk FOREIGN KEY (tenant_id, input_snapshot_id, period_month)
    REFERENCES pay.run_input_snapshot (tenant_id, id, period_month),
  CONSTRAINT run_result_history_uq UNIQUE (tenant_id, run_id, unlock_seq, employment_id),
  CONSTRAINT run_result_history_lines_ck CHECK (jsonb_typeof(lines) = 'array')
);
CREATE INDEX run_result_history_snapshot_ix ON pay.run_result_history (tenant_id, input_snapshot_id);

-- 6.9 Result archive (warm tier, months 37-120): one row per run x employee with the
-- compacted lines; the result_line partition is then dropped (ADR-0029 §6). Yearly partitions.
CREATE TABLE pay.result_archive (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  period_month      date NOT NULL,                            -- partition key
  run_id            uuid NOT NULL,
  run_employee_id   uuid NOT NULL,
  employment_id     uuid NOT NULL,
  line_count        int NOT NULL,
  lines             jsonb NOT NULL,                           -- compacted result_line rows (TOAST lz4, ~8x smaller)
  lines_sha256      text NOT NULL,
  archived_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT result_archive_pk PRIMARY KEY (tenant_id, id, period_month),
  CONSTRAINT result_archive_run_employee_fk FOREIGN KEY (tenant_id, run_id, run_employee_id)
    REFERENCES pay.run_employee (tenant_id, run_id, id),
  CONSTRAINT result_archive_uq UNIQUE (tenant_id, run_employee_id, period_month),
  CONSTRAINT result_archive_month_ck CHECK (extract(day FROM period_month) = 1),
  CONSTRAINT result_archive_lines_ck CHECK (jsonb_typeof(lines) = 'array' AND line_count = jsonb_array_length(lines))
) PARTITION BY RANGE (period_month);

-- =============================================================================
-- 6.10 RUN STATE MACHINE GUARD and RESULT IMMUTABILITY (tg_run_immutable)
-- =============================================================================

-- Business status transitions (gap §7.1, BR-PAY-071/082/083/084) and freezing of the
-- header once SUBMITTED. Gate conditions that need other rows are checked here too.
CREATE OR REPLACE FUNCTION pay.tg_payroll_run_guard()
RETURNS trigger LANGUAGE plpgsql
AS $$
DECLARE
  -- columns that may still change after SUBMITTED (lifecycle bookkeeping only)
  allowed constant text[] := ARRAY['status','approved_at','approved_by','rejected_at','rejected_by',
                                   'paid_at','closed_at','unlock_seq','unlocked_at','reversed_at',
                                   'is_stale','stale_reason','inputs_changed','inputs_changed_at',
                                   'workflow_instance_id','updated_at','updated_by','version'];
  ok boolean;
  n_att int; n_not_active int; n_emp int; n_hist int; n_active int;
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF sys.purge_bypass(OLD.tenant_id) THEN
      RETURN OLD;                                          -- D9 tenant purge (DESIGN §3.7)
    END IF;
    IF OLD.status <> 'DRAFT' OR OLD.approved_at IS NOT NULL THEN
      RAISE EXCEPTION 'pay.payroll_run %: only never-approved DRAFT runs may be deleted (status %)', OLD.id, OLD.status
        USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status THEN
    ok := CASE OLD.status
      WHEN 'DRAFT'      THEN NEW.status IN ('CALCULATED','CANCELLED')
      WHEN 'CALCULATED' THEN NEW.status IN ('DRAFT','SUBMITTED','CANCELLED')
      WHEN 'SUBMITTED'  THEN NEW.status IN ('APPROVED','REJECTED','CALCULATED')   -- CALCULATED = withdrawn / hold release requested
      WHEN 'REJECTED'   THEN NEW.status IN ('DRAFT','CANCELLED')
      WHEN 'APPROVED'   THEN NEW.status IN ('PAID','UNLOCKED')
      WHEN 'PAID'       THEN NEW.status IN ('CLOSED','REVERSED')                 -- REVERSED -- P2 (BR-PAY-079)
      WHEN 'CLOSED'     THEN NEW.status IN ('REVERSED')
      WHEN 'UNLOCKED'   THEN NEW.status IN ('DRAFT')
      ELSE false END;                                                            -- CANCELLED, REVERSED: terminal
    IF NOT ok THEN
      RAISE EXCEPTION 'pay.payroll_run %: illegal status transition % -> %', OLD.id, OLD.status, NEW.status
        USING ERRCODE = '55000';
    END IF;

    -- D3: CANCELLED only for a run that was never approved, with reason and stamps,
    -- and only after every mirrored close hold has been released (T&A API, reason CANCELLED)
    IF NEW.status = 'CANCELLED' THEN
      IF OLD.approved_at IS NOT NULL OR OLD.unlock_seq > 0 THEN
        RAISE EXCEPTION 'pay.payroll_run %: a run that was approved cannot be cancelled (D3)', OLD.id
          USING ERRCODE = '55000';
      END IF;
      IF NEW.cancel_reason IS NULL OR btrim(NEW.cancel_reason) = '' OR NEW.cancelled_by IS NULL OR NEW.cancelled_at IS NULL THEN
        RAISE EXCEPTION 'pay.payroll_run %: CANCELLED requires cancel_reason, cancelled_by and cancelled_at (D3)', OLD.id
          USING ERRCODE = '23514';
      END IF;
      SELECT count(*) INTO n_active FROM pay.run_attendance_version
       WHERE tenant_id = NEW.tenant_id AND payroll_run_id = NEW.id AND hold_status = 'ACTIVE';
      IF n_active > 0 THEN
        RAISE EXCEPTION 'pay.payroll_run %: % close hold(s) still ACTIVE; release them through the T&A API (reason CANCELLED) first (D3)',
          OLD.id, n_active USING ERRCODE = '55000';
      END IF;
    END IF;

    IF NEW.status IN ('SUBMITTED','APPROVED') THEN
      -- close hold: every current attendance version in scope must be held by this run
      -- (BR-PAY-082/084). The mirror is checked; att.attendance_result_hold is the authority (D1).
      SELECT count(*), count(*) FILTER (WHERE hold_status <> 'ACTIVE')
        INTO n_att, n_not_active
        FROM pay.run_attendance_version
       WHERE tenant_id = NEW.tenant_id AND payroll_run_id = NEW.id AND superseded_at IS NULL;
      IF n_not_active > 0 THEN
        RAISE EXCEPTION 'pay.payroll_run %: % attendance version(s) without an ACTIVE close hold', NEW.id, n_not_active
          USING ERRCODE = '55000';
      END IF;
      IF NEW.run_type = 'REGULAR' AND n_att = 0 THEN
        RAISE EXCEPTION 'pay.payroll_run %: a REGULAR run must reference at least one attendance result version', NEW.id
          USING ERRCODE = '55000';
      END IF;
      IF NEW.is_stale OR NEW.inputs_changed THEN
        RAISE EXCEPTION 'pay.payroll_run %: stale or unresolved input changes block %', NEW.id, NEW.status
          USING ERRCODE = '55000';
      END IF;
    END IF;

    IF NEW.status = 'UNLOCKED' THEN
      IF NEW.unlock_seq <> OLD.unlock_seq + 1 OR NEW.unlocked_at IS NULL THEN
        RAISE EXCEPTION 'pay.payroll_run %: UNLOCK must increment unlock_seq and stamp unlocked_at', NEW.id
          USING ERRCODE = '55000';
      END IF;
      IF NOT EXISTS (SELECT 1 FROM pay.run_unlock u
                      WHERE u.tenant_id = NEW.tenant_id AND u.run_id = NEW.id AND u.unlock_seq = NEW.unlock_seq) THEN
        RAISE EXCEPTION 'pay.payroll_run %: missing pay.run_unlock row (second approval) for seq %', NEW.id, NEW.unlock_seq
          USING ERRCODE = '55000';
      END IF;
      SELECT count(*) INTO n_emp  FROM pay.run_employee
       WHERE tenant_id = NEW.tenant_id AND run_id = NEW.id;
      SELECT count(*) INTO n_hist FROM pay.run_result_history
       WHERE tenant_id = NEW.tenant_id AND run_id = NEW.id AND unlock_seq = NEW.unlock_seq;
      IF n_hist <> n_emp THEN
        RAISE EXCEPTION 'pay.payroll_run %: approved results not saved to run_result_history (% of %)', NEW.id, n_hist, n_emp
          USING ERRCODE = '55000';
      END IF;
    END IF;
  END IF;

  -- header frozen from SUBMITTED on (results, stamps, totals; accepted D2 (2026-10-21));
  -- a CANCELLED run is terminal and frozen the same way (D3)
  IF OLD.status IN ('SUBMITTED','APPROVED','PAID','CLOSED','REVERSED','CANCELLED')
     AND (to_jsonb(NEW) - allowed) IS DISTINCT FROM (to_jsonb(OLD) - allowed) THEN
    RAISE EXCEPTION 'pay.payroll_run % is % : only lifecycle columns may change', OLD.id, OLD.status
      USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;

-- Generic guard for run-owned result tables (run_input_snapshot, run_employee,
-- result_line, pit_withholding): no INSERT/UPDATE/DELETE once the run is SUBMITTED or
-- later -- results freeze at SUBMITTED, accepted D2 (2026-10-21): a submitted run is
-- under approval. The only way back is UNLOCK (results first copied to
-- run_result_history) or withdrawal to CALCULATED. A CANCELLED run (D3) is frozen too.
-- D9: DELETE is allowed for the tenant purge (sys.purge_bypass).
CREATE OR REPLACE FUNCTION pay.tg_run_immutable()
RETURNS trigger LANGUAGE plpgsql
AS $$
DECLARE
  v_tenant uuid;
  v_run    uuid;
  v_status text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF sys.purge_bypass(OLD.tenant_id) THEN
      RETURN OLD;                                          -- D9 tenant purge
    END IF;
    v_tenant := OLD.tenant_id; v_run := OLD.run_id;
  ELSE
    v_tenant := NEW.tenant_id; v_run := NEW.run_id;
    IF TG_OP = 'UPDATE' AND (OLD.run_id IS DISTINCT FROM NEW.run_id OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id) THEN
      RAISE EXCEPTION '%.%: run_id / tenant_id are immutable', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
    END IF;
  END IF;
  SELECT r.status INTO v_status FROM pay.payroll_run r WHERE r.tenant_id = v_tenant AND r.id = v_run;
  IF v_status IN ('SUBMITTED','APPROVED','PAID','CLOSED','REVERSED','CANCELLED') THEN
    RAISE EXCEPTION '%.%: payroll run % is % - results are immutable (% rejected; use UNLOCK)',
      TG_TABLE_SCHEMA, TG_TABLE_NAME, v_run, v_status, TG_OP USING ERRCODE = '55000';
  END IF;
  RETURN coalesce(NEW, OLD);
END $$;
COMMENT ON FUNCTION pay.tg_run_immutable() IS
  'ADR-0029 §5 + D2 (accepted 2026-10-21): blocks writes to run results once the run is SUBMITTED/APPROVED/PAID/CLOSED/'
  'REVERSED/CANCELLED; DELETE allowed only for the tenant purge (D9). Row-level, one PK lookup per row (COPY of 600k rows '
  'costs ~1-2 s) [spike SP-1: statement-level variant with transition tables].';

-- Attendance versions (close-hold mirror, D1): after SUBMITTED (and on a CANCELLED run)
-- only the hold bookkeeping may change, so that releases / consumption reported by
-- T&A can still be mirrored. The state-machine gate is in pay.tg_payroll_run_guard.
CREATE OR REPLACE FUNCTION pay.tg_run_attendance_version_guard()
RETURNS trigger LANGUAGE plpgsql
AS $$
DECLARE
  allowed constant text[] := ARRAY['hold_status','hold_acquired_at','hold_released_at','release_reason',
                                   'locked_confirmed_at','updated_at','updated_by','version','scope_key'];
  v_status text;
  r pay.run_attendance_version;
BEGIN
  IF TG_OP = 'DELETE' AND sys.purge_bypass(OLD.tenant_id) THEN
    RETURN OLD;                                            -- D9 tenant purge
  END IF;
  r := coalesce(NEW, OLD);
  SELECT status INTO v_status FROM pay.payroll_run WHERE tenant_id = r.tenant_id AND id = r.payroll_run_id;
  IF v_status IN ('SUBMITTED','APPROVED','PAID','CLOSED','REVERSED','CANCELLED') THEN
    IF TG_OP <> 'UPDATE' OR (to_jsonb(NEW) - allowed) IS DISTINCT FROM (to_jsonb(OLD) - allowed) THEN
      RAISE EXCEPTION 'pay.run_attendance_version: run % is %, attendance versions are frozen (% rejected)',
        r.payroll_run_id, v_status, TG_OP USING ERRCODE = '55000';
    END IF;
  END IF;
  IF TG_OP = 'UPDATE' THEN
    -- identity of a mirror row never changes (a new hold = a new row)
    IF NEW.att_hold_id IS DISTINCT FROM OLD.att_hold_id AND OLD.att_hold_id IS NOT NULL THEN
      RAISE EXCEPTION 'pay.run_attendance_version %: att_hold_id is immutable once set; insert a new row and supersede this one', OLD.id
        USING ERRCODE = '55000';
    END IF;
    IF OLD.superseded_at IS NOT NULL
       AND (to_jsonb(NEW) - 'updated_at' - 'scope_key') IS DISTINCT FROM (to_jsonb(OLD) - 'updated_at' - 'scope_key') THEN
      RAISE EXCEPTION 'pay.run_attendance_version % is superseded (terminal)', OLD.id USING ERRCODE = '55000';
    END IF;
  END IF;
  RETURN r;
END $$;

-- =============================================================================
-- 7. RETRO, SI CONTRIBUTION, PIT WITHHOLDING, YTD BALANCE
-- =============================================================================

-- Retro differences detected for closed periods (BR-PAY-076; ADR-0009 §8).
CREATE TABLE pay.retro_item (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id         uuid NOT NULL,                        -- soft hr
  legal_entity_id       uuid NOT NULL,
  origin_period_month   sys.month_date NOT NULL,
  origin_run_id         uuid NOT NULL,                        -- approved run being corrected
  component_id          uuid NOT NULL,
  component_code        text NOT NULL,
  original_amount       sys.money_amount NOT NULL,
  recalculated_amount   sys.money_amount NOT NULL,
  delta_amount          sys.money_amount NOT NULL,
  delta_taxable_amount  sys.money_amount,
  delta_si_basis_amount sys.money_amount,
  cause                 text NOT NULL,                        -- SALARY | ATTENDANCE | DEPENDANT | ASSIGNMENT | PRIMARY_EMPLOYMENT | MANUAL
  cause_ref             jsonb,                                -- {"salary_record_id":..,"recorded_at":..} / {"att_retro_delta_id":..}
  detected_at           timestamptz NOT NULL DEFAULT now(),
  target_run_id         uuid,
  status                text NOT NULL DEFAULT 'DETECTED',     -- DETECTED | INCLUDED | DEFERRED | CANCELLED | PAID
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT retro_item_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT retro_item_origin_run_fk FOREIGN KEY (tenant_id, origin_run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT retro_item_target_run_fk FOREIGN KEY (tenant_id, target_run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT retro_item_component_fk FOREIGN KEY (tenant_id, component_id) REFERENCES pay.salary_component (tenant_id, id),
  CONSTRAINT retro_item_delta_ck CHECK (delta_amount = recalculated_amount - original_amount),
  CONSTRAINT retro_item_cause_ck CHECK (cause IN ('SALARY','ATTENDANCE','DEPENDANT','ASSIGNMENT','PRIMARY_EMPLOYMENT','MANUAL')),
  CONSTRAINT retro_item_status_ck CHECK (status IN ('DETECTED','INCLUDED','DEFERRED','CANCELLED','PAID')),
  CONSTRAINT retro_item_target_ck CHECK (status NOT IN ('INCLUDED','PAID') OR target_run_id IS NOT NULL)
);
CREATE INDEX retro_item_employment_ix ON pay.retro_item (tenant_id, employment_id, origin_period_month);
CREATE INDEX retro_item_open_ix ON pay.retro_item (tenant_id, status) WHERE status IN ('DETECTED','DEFERRED');

-- SI contribution history (review H4, M10): ONE row per employment x contribution_month x
-- insurance_type (SI = BHXH, HI = BHYT, UI = BHTN, OA_OD = TNLĐ-BNN, TU = trade union:
-- ee_amount = union dues of members, er_amount = KPCĐ 2% fund). Master of the SI history
-- (gap §5 #20) incl. pre-go-live months (source OPENING, template OB_SI_HISTORY, ADR-0037).
-- Corrections never update in place: the current row is superseded and a new row
-- (source ADJUSTMENT or a re-approved RUN) is inserted; "current" = superseded_at IS NULL.
CREATE TABLE pay.si_contribution (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id        uuid NOT NULL,                         -- soft hr
  person_id            uuid NOT NULL,                         -- soft hr (years-of-contribution API, IF-PAY-P-08)
  legal_entity_id      uuid NOT NULL,                         -- soft core (contributing employer)
  si_unit_code         text,                                  -- VSS unit code (mã đơn vị); previous employer for OPENING rows
  contribution_month   sys.month_date NOT NULL,
  insurance_type       text NOT NULL,
  source               text NOT NULL,                         -- RUN | OPENING | ADJUSTMENT (M10)
  status               text NOT NULL DEFAULT 'CONTRIBUTE',    -- CONTRIBUTE | EXEMPT (maternity ...) | NO_CONTRIBUTION (>= 14 unpaid days) | NOT_PARTICIPATING
  exemption_reason_code text,
  basis_amount         sys.money_amount NOT NULL DEFAULT 0,   -- declared monthly basis after floor/cap (BR-PAY-041/042)
  cap_amount           sys.money_amount,                      -- cap applied (20 x base / 20 x regional minimum) [verify-legal]
  ee_rate              sys.rate,
  er_rate              sys.rate,
  ee_amount            sys.money_amount NOT NULL DEFAULT 0,
  er_amount            sys.money_amount NOT NULL DEFAULT 0,
  currency_code        sys.currency_code NOT NULL DEFAULT 'VND',
  -- R3-03: insurance.multi_contract_rule value applied for this type (LPS), SINGLE = one employment only
  rule_applied         text NOT NULL DEFAULT 'SINGLE',
  legal_pack_version   bigint,
  run_id               uuid,                                  -- RUN rows
  run_employee_id      uuid,                                  -- M10: nullable; same schema but no FK (rows rewritten after UNLOCK)
  import_job_id        uuid,                                  -- M10: OPENING rows -> core.import_job (soft)
  adjusts_id           uuid,                                  -- ADJUSTMENT: the row it corrects
  si_declaration_line_id uuid,                                -- D02-LT line that declared it (soft, same schema; set later)
  superseded_at        timestamptz,
  superseded_by        uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT si_contribution_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT si_contribution_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT si_contribution_adjusts_fk FOREIGN KEY (tenant_id, adjusts_id) REFERENCES pay.si_contribution (tenant_id, id),
  CONSTRAINT si_contribution_superseded_by_fk FOREIGN KEY (tenant_id, superseded_by) REFERENCES pay.si_contribution (tenant_id, id),
  CONSTRAINT si_contribution_type_ck CHECK (insurance_type IN ('SI','HI','UI','OA_OD','TU')),
  CONSTRAINT si_contribution_source_ck CHECK (source IN ('RUN','OPENING','ADJUSTMENT')),
  CONSTRAINT si_contribution_status_ck CHECK (status IN ('CONTRIBUTE','EXEMPT','NO_CONTRIBUTION','NOT_PARTICIPATING')),
  CONSTRAINT si_contribution_rule_ck CHECK (rule_applied IN ('SINGLE','FIRST_CONTRACT','HIGHEST_SALARY','EACH_EMPLOYER')),
  CONSTRAINT si_contribution_source_ref_ck CHECK (
       (source = 'RUN'        AND run_id IS NOT NULL AND import_job_id IS NULL)
    OR (source = 'OPENING'    AND run_id IS NULL AND run_employee_id IS NULL AND import_job_id IS NOT NULL)
    OR (source = 'ADJUSTMENT' AND adjusts_id IS NOT NULL)),
  CONSTRAINT si_contribution_amounts_ck CHECK (basis_amount >= 0 AND ee_amount >= 0 AND er_amount >= 0),
  CONSTRAINT si_contribution_zero_ck CHECK (status = 'CONTRIBUTE' OR (ee_amount = 0 AND er_amount = 0))
);
-- H4: one CURRENT row per employment x month x insurance type
CREATE UNIQUE INDEX si_contribution_current_uq ON pay.si_contribution
  (tenant_id, employment_id, contribution_month, insurance_type) WHERE superseded_at IS NULL;
CREATE INDEX si_contribution_le_month_ix ON pay.si_contribution (tenant_id, legal_entity_id, contribution_month) WHERE superseded_at IS NULL;
CREATE INDEX si_contribution_person_ix ON pay.si_contribution (tenant_id, person_id, insurance_type, contribution_month);
CREATE INDEX si_contribution_run_ix ON pay.si_contribution (tenant_id, run_id) WHERE run_id IS NOT NULL;
CREATE INDEX si_contribution_import_ix ON pay.si_contribution (tenant_id, import_job_id) WHERE import_job_id IS NOT NULL;
COMMENT ON TABLE pay.si_contribution IS
  'H4/M10: SI contribution history per employment x month x insurance type (SI HI UI OA_OD TU). source RUN (posted on run '
  'approval, pay.SiContributionPosted) | OPENING (OB_SI_HISTORY, ADR-0037; never recalculated) | ADJUSTMENT. '
  'DESIGN §8 "status = OPENING" is expressed as source = OPENING here. Kept online 10 years.';
COMMENT ON COLUMN pay.si_contribution.ee_amount IS 'pii:P2 not encrypted (X-01)';
COMMENT ON COLUMN pay.si_contribution.er_amount IS 'pii:P2 not encrypted (X-01)';
COMMENT ON COLUMN pay.si_contribution.basis_amount IS 'pii:P2 not encrypted (X-01)';

-- PIT withholding per run x employment (BR-PAY-050..053, 062). Frozen with the run.
CREATE TABLE pay.pit_withholding (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_id                    uuid NOT NULL,
  run_employee_id           uuid NOT NULL,
  employment_id             uuid NOT NULL,                    -- soft hr
  person_id                 uuid NOT NULL,                    -- soft hr
  legal_entity_id           uuid NOT NULL,                    -- withholding employer (BR-PAY-081)
  tax_month                 sys.month_date NOT NULL,
  residency                 text NOT NULL,                    -- RESIDENT | NON_RESIDENT
  method                    text NOT NULL,                    -- PROGRESSIVE | FLAT_10 | FLAT_20 | NONE
  is_family_deduction_applied boolean NOT NULL DEFAULT false, -- PIT_FAMILY_DEDUCTION primary employment (R2-16)
  gross_taxable_amount      sys.money_amount NOT NULL DEFAULT 0,
  exempt_amount             sys.money_amount NOT NULL DEFAULT 0,
  si_deduction_amount       sys.money_amount NOT NULL DEFAULT 0,
  self_deduction_amount     sys.money_amount NOT NULL DEFAULT 0,
  dependant_count           smallint NOT NULL DEFAULT 0,
  dependant_deduction_amount sys.money_amount NOT NULL DEFAULT 0,
  other_deduction_amount    sys.money_amount NOT NULL DEFAULT 0,
  assessable_amount         sys.money_amount NOT NULL DEFAULT 0,  -- thu nhập tính thuế (month cumulative, BR-PAY-052)
  pit_month_total           sys.money_amount NOT NULL DEFAULT 0,
  pit_already_withheld      sys.money_amount NOT NULL DEFAULT 0,  -- earlier runs of the same tax month
  pit_amount                sys.money_amount NOT NULL DEFAULT 0,  -- withheld by this run
  pit_borne_by_employer     sys.money_amount NOT NULL DEFAULT 0,  -- NET contracts (BR-PAY-062)
  legal_pack_version        bigint NOT NULL,
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  CONSTRAINT pit_withholding_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_withholding_run_employee_fk FOREIGN KEY (tenant_id, run_id, run_employee_id)
    REFERENCES pay.run_employee (tenant_id, run_id, id) ON DELETE CASCADE,
  CONSTRAINT pit_withholding_uq UNIQUE (tenant_id, run_employee_id),
  CONSTRAINT pit_withholding_residency_ck CHECK (residency IN ('RESIDENT','NON_RESIDENT')),
  CONSTRAINT pit_withholding_method_ck CHECK (method IN ('PROGRESSIVE','FLAT_10','FLAT_20','NONE')),
  CONSTRAINT pit_withholding_nonres_ck CHECK (residency = 'RESIDENT' OR NOT is_family_deduction_applied)
);
CREATE INDEX pit_withholding_month_ix ON pay.pit_withholding (tenant_id, legal_entity_id, tax_month);
CREATE INDEX pit_withholding_person_ix ON pay.pit_withholding (tenant_id, person_id, tax_month);
COMMENT ON COLUMN pay.pit_withholding.pit_amount IS 'pii:P2 not encrypted (X-01)';

-- YTD balances per employment x tax year x month x balance code (DESIGN §7.5).
-- source OPENING = pre-go-live months of the current tax year from OB_YTD_PAYROLL
-- (ADR-0037, D-09): trusted, never recalculated, never retro'd.
CREATE TABLE pay.ytd_balance (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id    uuid NOT NULL,                             -- soft hr
  legal_entity_id  uuid NOT NULL,
  tax_year         smallint NOT NULL,
  balance_month    sys.month_date NOT NULL,
  balance_code     text NOT NULL,                             -- GROSS | TAXABLE | ASSESSABLE | PIT_WITHHELD | SI_EE | HI_EE | UI_EE | SI_ER ... | FAMILY_DEDUCTION | NET | UNIFORM_EXEMPT_YTD ...
  amount           sys.money_amount NOT NULL,
  source           text NOT NULL,                             -- RUN | OPENING | ADJUSTMENT
  run_id           uuid,
  import_job_id    uuid,                                      -- soft core.import_job (OPENING)
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  CONSTRAINT ytd_balance_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ytd_balance_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT ytd_balance_uq UNIQUE NULLS NOT DISTINCT (tenant_id, employment_id, balance_month, balance_code, source, run_id),
  CONSTRAINT ytd_balance_code_ck CHECK (balance_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  CONSTRAINT ytd_balance_year_ck CHECK (tax_year = extract(year FROM balance_month)),
  CONSTRAINT ytd_balance_source_ck CHECK (
       (source = 'RUN'        AND run_id IS NOT NULL AND import_job_id IS NULL)
    OR (source = 'OPENING'    AND run_id IS NULL AND import_job_id IS NOT NULL)
    OR (source = 'ADJUSTMENT' AND import_job_id IS NULL))
);
CREATE INDEX ytd_balance_emp_year_ix ON pay.ytd_balance (tenant_id, employment_id, tax_year, balance_code);
CREATE INDEX ytd_balance_run_ix ON pay.ytd_balance (tenant_id, run_id) WHERE run_id IS NOT NULL;
COMMENT ON COLUMN pay.ytd_balance.amount IS 'pii:P2 not encrypted (X-01)';

-- =============================================================================
-- 8. CASUAL INCOME (non-employees, 10% / 20% withholding, BR-PAY-054)
-- =============================================================================
CREATE TABLE pay.casual_payee (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  full_name            text NOT NULL,                         -- pii:P1
  id_doc_type          text,                                  -- CCCD | PASSPORT
  national_id_enc      bytea,                                 -- pii:P3
  national_id_bidx     bytea,
  national_id_last4    text,
  tax_code_enc         bytea,                                 -- pii:P3
  tax_code_bidx        bytea,
  tax_code_last4       text,
  residency            text NOT NULL DEFAULT 'RESIDENT',
  address              text,                                  -- pii:P2
  email                text,                                  -- pii:P2
  bank_code            text,                                  -- core.bank (by code)
  account_no_enc       bytea,                                 -- pii:P3
  account_no_bidx      bytea,
  account_no_last4     text,
  subject_key_version  int NOT NULL DEFAULT 1,                -- saas.subject_key (CASUAL_PAYEE, id)
  hr_person_id         uuid,                                  -- soft hr.person when the payee is also an employee
  status               text NOT NULL DEFAULT 'ACTIVE',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT casual_payee_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT casual_payee_doc_ck CHECK (id_doc_type IS NULL OR id_doc_type IN ('CCCD','PASSPORT')),
  CONSTRAINT casual_payee_residency_ck CHECK (residency IN ('RESIDENT','NON_RESIDENT')),
  CONSTRAINT casual_payee_status_ck CHECK (status IN ('ACTIVE','INACTIVE','ANONYMISED'))
);
CREATE UNIQUE INDEX casual_payee_nid_uq ON pay.casual_payee (tenant_id, national_id_bidx) WHERE national_id_bidx IS NOT NULL;
CREATE INDEX casual_payee_tax_ix ON pay.casual_payee (tenant_id, tax_code_bidx) WHERE tax_code_bidx IS NOT NULL;
COMMENT ON COLUMN pay.casual_payee.national_id_enc IS 'pii:P3 AES-256-GCM, subject DEK of the casual payee';
COMMENT ON COLUMN pay.casual_payee.tax_code_enc    IS 'pii:P3 AES-256-GCM, subject DEK of the casual payee';
COMMENT ON COLUMN pay.casual_payee.account_no_enc  IS 'pii:P3 AES-256-GCM, subject DEK of the casual payee';
COMMENT ON COLUMN pay.casual_payee.address         IS 'pii:P2';
COMMENT ON COLUMN pay.casual_payee.email           IS 'pii:P2';

-- 08/CK-TNCN commitments (BR-PAY-055): Payroll owns them, for employees and casual payees.
CREATE TABLE pay.pit_commitment (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  payee_kind              text NOT NULL,                      -- EMPLOYEE | CASUAL
  employment_id           uuid,                               -- soft hr (EMPLOYEE)
  casual_payee_id         uuid,                               -- CASUAL
  tax_year                smallint NOT NULL,
  estimated_income_amount sys.money_amount NOT NULL,          -- pii:P2
  has_tax_code            boolean NOT NULL,
  status                  text NOT NULL DEFAULT 'VALID',      -- VALID | EXCEEDED | REVOKED | EXPIRED | INVALID
  submitted_via           text NOT NULL DEFAULT 'ESS',        -- ESS | ADMIN
  submitted_at            timestamptz NOT NULL DEFAULT now(),
  step_up_ref             text,                               -- ESS step-up evidence
  exceeded_at             timestamptz,                        -- cumulative payments above the committed threshold
  document_id             uuid,                               -- soft core.document (signed form)
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT pit_commitment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_commitment_payee_fk FOREIGN KEY (tenant_id, casual_payee_id) REFERENCES pay.casual_payee (tenant_id, id),
  CONSTRAINT pit_commitment_kind_ck CHECK (
       (payee_kind = 'EMPLOYEE' AND employment_id IS NOT NULL AND casual_payee_id IS NULL)
    OR (payee_kind = 'CASUAL'   AND casual_payee_id IS NOT NULL AND employment_id IS NULL)),
  CONSTRAINT pit_commitment_status_ck CHECK (status IN ('VALID','EXCEEDED','REVOKED','EXPIRED','INVALID')),
  CONSTRAINT pit_commitment_via_ck CHECK (submitted_via IN ('ESS','ADMIN'))
);
CREATE UNIQUE INDEX pit_commitment_valid_uq ON pay.pit_commitment
  (tenant_id, coalesce(employment_id, casual_payee_id), tax_year) WHERE status = 'VALID';

CREATE TABLE pay.casual_payment (
  tenant_id           uuid NOT NULL,
  id                  uuid NOT NULL DEFAULT sys.uuid_v7(),
  casual_payee_id     uuid NOT NULL,
  legal_entity_id     uuid NOT NULL,                          -- soft core
  run_id              uuid,                                   -- casual run (run_subtype CASUAL), optional
  payment_date        date NOT NULL,
  tax_month           sys.month_date NOT NULL,
  contract_ref        text,                                   -- service contract / labour contract < 3 months
  description         text,
  gross_amount        sys.money_amount NOT NULL,              -- pii:P2
  withholding_method  text NOT NULL,                          -- FLAT_10 | FLAT_20 | NONE_COMMITMENT | NONE_BELOW_THRESHOLD
  withholding_rate    sys.rate NOT NULL DEFAULT 0,
  pit_amount          sys.money_amount NOT NULL DEFAULT 0,
  net_amount          sys.money_amount NOT NULL,
  commitment_id       uuid,
  cost_centre_id      uuid,                                   -- soft core
  legal_pack_version  bigint,
  status              text NOT NULL DEFAULT 'DRAFT',
  approved_at         timestamptz,
  approved_by         uuid,
  paid_at             timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid,
  version             int NOT NULL DEFAULT 0,
  CONSTRAINT casual_payment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT casual_payment_payee_fk FOREIGN KEY (tenant_id, casual_payee_id) REFERENCES pay.casual_payee (tenant_id, id),
  CONSTRAINT casual_payment_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT casual_payment_commitment_fk FOREIGN KEY (tenant_id, commitment_id) REFERENCES pay.pit_commitment (tenant_id, id),
  CONSTRAINT casual_payment_method_ck CHECK (withholding_method IN ('FLAT_10','FLAT_20','NONE_COMMITMENT','NONE_BELOW_THRESHOLD')),
  CONSTRAINT casual_payment_commitment_ck CHECK (withholding_method <> 'NONE_COMMITMENT' OR commitment_id IS NOT NULL),
  CONSTRAINT casual_payment_net_ck CHECK (net_amount = gross_amount - pit_amount AND pit_amount >= 0),
  CONSTRAINT casual_payment_status_ck CHECK (status IN ('DRAFT','APPROVED','PAID','CANCELLED'))
);
CREATE INDEX casual_payment_payee_ix ON pay.casual_payment (tenant_id, casual_payee_id, tax_month);
CREATE INDEX casual_payment_le_month_ix ON pay.casual_payment (tenant_id, legal_entity_id, tax_month);

-- =============================================================================
-- 9. STATUTORY DECLARATIONS  (Phase 1 = files the customer uploads, R2-01)
-- =============================================================================

-- VSS declarations: D02-LT (increase / decrease / adjustment), TK1-TS data (BR-PAY-047).
CREATE TABLE pay.si_declaration (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id    uuid NOT NULL,                           -- soft core
  si_unit_code       text NOT NULL,                           -- VSS unit code of the LE
  declaration_month  sys.month_date NOT NULL,
  form_code          text NOT NULL DEFAULT 'D02_LT',          -- D02_LT | TK1_TS
  form_version       text NOT NULL,                           -- VSS template version (configurable, [verify-legal])
  seq_no             smallint NOT NULL DEFAULT 1,             -- n-th file of the month
  status             text NOT NULL DEFAULT 'DRAFT',
  line_count         int NOT NULL DEFAULT 0,
  file_document_id   uuid,                                    -- soft core.document (Excel/XML export)
  file_sha256        text,
  exported_at        timestamptz,
  exported_by        uuid,
  vss_ref            text,                                    -- receipt number from the customer's I-VAN
  c12_reconciled_at  timestamptz,                             -- C12-TS reconciliation
  c12_differences    jsonb,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT si_declaration_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT si_declaration_uq UNIQUE (tenant_id, legal_entity_id, declaration_month, form_code, seq_no),
  CONSTRAINT si_declaration_form_ck CHECK (form_code IN ('D02_LT','TK1_TS')),
  CONSTRAINT si_declaration_status_ck CHECK (status IN ('DRAFT','GENERATED','EXPORTED','SUBMITTED','ACCEPTED','REJECTED','CANCELLED'))
);

CREATE TABLE pay.si_declaration_line (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  declaration_id     uuid NOT NULL,
  line_no            int NOT NULL,
  employment_id      uuid NOT NULL,                           -- soft hr; SI number / CCCD resolved encrypted at file generation
  person_id          uuid NOT NULL,
  change_type        text NOT NULL,                           -- INCREASE | DECREASE | ADJUST_UP | ADJUST_DOWN | RETRO
  plan_code          text,                                    -- VSS phương án (TM, ON, TD, GH, DC ...) [verify-legal]
  from_month         sys.month_date NOT NULL,
  to_month           sys.month_date,
  old_basis_amount   sys.money_amount,                        -- pii:P2
  new_basis_amount   sys.money_amount,                        -- pii:P2
  insurance_types    text[] NOT NULL,                         -- subset of SI HI UI OA_OD
  reason_code        text,
  source_ref         jsonb,                                   -- {"si_contribution_ids":[..]} / termination ref
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  CONSTRAINT si_declaration_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT si_declaration_line_decl_fk FOREIGN KEY (tenant_id, declaration_id) REFERENCES pay.si_declaration (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT si_declaration_line_uq UNIQUE (tenant_id, declaration_id, line_no),
  CONSTRAINT si_declaration_line_change_ck CHECK (change_type IN ('INCREASE','DECREASE','ADJUST_UP','ADJUST_DOWN','RETRO')),
  CONSTRAINT si_declaration_line_types_ck CHECK (insurance_types <@ ARRAY['SI','HI','UI','OA_OD']::text[] AND cardinality(insurance_types) > 0),
  CONSTRAINT si_declaration_line_months_ck CHECK (to_month IS NULL OR to_month >= from_month)
);
CREATE INDEX si_declaration_line_emp_ix ON pay.si_declaration_line (tenant_id, employment_id, from_month);

-- 05/KK-TNCN per LE tax code and managing tax authority, monthly or quarterly (BR-PAY-056).
CREATE TABLE pay.pit_declaration (
  tenant_id           uuid NOT NULL,
  id                  uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id     uuid NOT NULL,                          -- soft core
  le_tax_code         text NOT NULL,                          -- employer MST (not personal data)
  tax_authority_code  text NOT NULL,
  form_code           text NOT NULL DEFAULT '05_KK',
  form_version        text NOT NULL,
  period_type         text NOT NULL,                          -- MONTH | QUARTER (LE filing frequency per tax year, Core)
  period_start_month  sys.month_date NOT NULL,
  is_supplementary    boolean NOT NULL DEFAULT false,         -- tờ khai bổ sung
  supplement_seq      smallint NOT NULL DEFAULT 0,
  status              text NOT NULL DEFAULT 'DRAFT',
  headcount           int,
  taxable_income_total sys.money_amount,
  pit_total           sys.money_amount,
  xml_document_id     uuid,                                   -- soft core.document (HTKK/eTax XML)
  xml_sha256          text,
  exported_at         timestamptz,
  exported_by         uuid,
  submission_ref      text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid,
  version             int NOT NULL DEFAULT 0,
  CONSTRAINT pit_declaration_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_declaration_uq UNIQUE (tenant_id, legal_entity_id, form_code, period_start_month, supplement_seq),
  CONSTRAINT pit_declaration_form_ck CHECK (form_code IN ('05_KK')),
  CONSTRAINT pit_declaration_period_ck CHECK (period_type IN ('MONTH','QUARTER')
    AND (period_type = 'MONTH' OR extract(month FROM period_start_month) IN (1,4,7,10))),
  CONSTRAINT pit_declaration_supp_ck CHECK (is_supplementary = (supplement_seq > 0)),
  CONSTRAINT pit_declaration_status_ck CHECK (status IN ('DRAFT','GENERATED','EXPORTED','SUBMITTED','ACCEPTED','REJECTED','CANCELLED'))
);

-- Indicator lines of the form ([21]..[36] style codes, versioned with form_version).
CREATE TABLE pay.pit_declaration_line (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  declaration_id   uuid NOT NULL,
  indicator_code   text NOT NULL,
  value_amount     sys.money_amount,
  value_count      int,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  CONSTRAINT pit_declaration_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_declaration_line_decl_fk FOREIGN KEY (tenant_id, declaration_id) REFERENCES pay.pit_declaration (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT pit_declaration_line_uq UNIQUE (tenant_id, declaration_id, indicator_code),
  CONSTRAINT pit_declaration_line_value_ck CHECK (value_amount IS NOT NULL OR value_count IS NOT NULL)
);

-- Year-end PIT finalisation, 05/QTT-TNCN data with annexes 05-1/05-2/05-3 (BR-PAY-057).
CREATE TABLE pay.pit_finalisation (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id      uuid NOT NULL,                         -- soft core
  le_tax_code          text NOT NULL,
  tax_authority_code   text NOT NULL,
  tax_year             smallint NOT NULL,
  form_code            text NOT NULL DEFAULT '05_QTT',
  form_version         text NOT NULL,
  legal_pack_version   bigint,
  status               text NOT NULL DEFAULT 'DRAFT',
  calculated_at        timestamptz,
  approved_at          timestamptz,
  approved_by          uuid,
  xml_document_id      uuid,                                  -- soft core.document
  xml_sha256           text,
  exported_at          timestamptz,
  submission_ref       text,
  is_supplementary     boolean NOT NULL DEFAULT false,
  supplement_seq       smallint NOT NULL DEFAULT 0,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT pit_finalisation_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_finalisation_uq UNIQUE (tenant_id, legal_entity_id, tax_year, supplement_seq),
  CONSTRAINT pit_finalisation_status_ck CHECK (status IN ('DRAFT','CALCULATED','APPROVED','EXPORTED','SUBMITTED','CANCELLED')),
  CONSTRAINT pit_finalisation_supp_ck CHECK (is_supplementary = (supplement_seq > 0))
);

CREATE TABLE pay.pit_finalisation_line (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  finalisation_id           uuid NOT NULL,
  annex_code                text NOT NULL,                    -- 05_1 (resident progressive) | 05_2 (10%/20%) | 05_3 (dependants)
  employment_id             uuid,                             -- soft hr
  person_id                 uuid,                             -- soft hr
  casual_payee_id           uuid,
  is_authorised             boolean NOT NULL DEFAULT false,   -- finalisation authorised to the employer (pit_finalisation_auth)
  taxable_income_amount     sys.money_amount,
  assessable_income_amount  sys.money_amount,
  family_deduction_amount   sys.money_amount,
  dependant_months          smallint,
  si_deduction_amount       sys.money_amount,
  pit_due_amount            sys.money_amount,
  pit_withheld_amount       sys.money_amount,
  pit_refund_amount         sys.money_amount,
  pit_payable_amount        sys.money_amount,
  detail                    jsonb,                            -- 05-3: dependant months per dependant (ids only)
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  CONSTRAINT pit_finalisation_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_finalisation_line_fin_fk FOREIGN KEY (tenant_id, finalisation_id) REFERENCES pay.pit_finalisation (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT pit_finalisation_line_payee_fk FOREIGN KEY (tenant_id, casual_payee_id) REFERENCES pay.casual_payee (tenant_id, id),
  CONSTRAINT pit_finalisation_line_annex_ck CHECK (annex_code IN ('05_1','05_2','05_3')),
  CONSTRAINT pit_finalisation_line_subject_ck CHECK (employment_id IS NOT NULL OR casual_payee_id IS NOT NULL)
);
CREATE INDEX pit_finalisation_line_fin_ix ON pay.pit_finalisation_line (tenant_id, finalisation_id, annex_code);

-- Finalisation authorisation (BR-PAY-058, ESS).
CREATE TABLE pay.pit_finalisation_auth (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id         uuid NOT NULL,                        -- soft hr
  person_id             uuid NOT NULL,                        -- soft hr
  legal_entity_id       uuid NOT NULL,
  tax_year              smallint NOT NULL,
  is_eligible           boolean NOT NULL,
  ineligibility_reason  text,                                 -- CONTRACT_LT_3M | LEFT_BEFORE_FINALISATION | OTHER_INCOME ...
  status                text NOT NULL DEFAULT 'PENDING',      -- PENDING | AUTHORISED | DECLINED | WITHDRAWN | NOT_ELIGIBLE
  signed_at             timestamptz,
  signed_via            text,                                 -- ESS | ADMIN
  step_up_ref           text,
  document_id           uuid,                                 -- soft core.document
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT pit_finalisation_auth_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_finalisation_auth_uq UNIQUE (tenant_id, employment_id, tax_year),
  CONSTRAINT pit_finalisation_auth_status_ck CHECK (status IN ('PENDING','AUTHORISED','DECLINED','WITHDRAWN','NOT_ELIGIBLE')),
  CONSTRAINT pit_finalisation_auth_signed_ck CHECK (status <> 'AUTHORISED' OR (is_eligible AND signed_at IS NOT NULL)),
  CONSTRAINT pit_finalisation_auth_via_ck CHECK (signed_via IS NULL OR signed_via IN ('ESS','ADMIN'))
);

-- PIT withholding certificates (BR-PAY-059). Phase 1 (R3-02): certificate DATA (tax-authority
-- XML) + UNSIGNED PDF; the customer signs/issues via the tax-authority tool or own provider.
-- Digital signature and direct transmission -- P2. Gap-free numbering per LE x symbol.
CREATE TABLE pay.pit_certificate_series (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id  uuid NOT NULL,
  template_code    text NOT NULL,                             -- mẫu số [verify-legal]
  symbol           text NOT NULL,                             -- ký hiệu
  last_no          int NOT NULL DEFAULT 0,                    -- allocated under row lock (UPDATE ... RETURNING)
  status           text NOT NULL DEFAULT 'ACTIVE',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT pit_certificate_series_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_certificate_series_uq UNIQUE (tenant_id, legal_entity_id, symbol),
  CONSTRAINT pit_certificate_series_status_ck CHECK (status IN ('ACTIVE','CLOSED')),
  CONSTRAINT pit_certificate_series_no_ck CHECK (last_no >= 0)
);

CREATE TABLE pay.pit_certificate (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  series_id            uuid NOT NULL,
  cert_no              int NOT NULL,
  legal_entity_id      uuid NOT NULL,
  issue_date           date NOT NULL,
  recipient_kind       text NOT NULL,                         -- EMPLOYEE | CASUAL
  employment_id        uuid,                                  -- soft hr
  person_id            uuid,                                  -- soft hr
  casual_payee_id      uuid,
  tax_year             smallint NOT NULL,
  from_month           sys.month_date NOT NULL,
  to_month             sys.month_date NOT NULL,
  taxable_income_amount sys.money_amount NOT NULL,            -- pii:P2
  pit_withheld_amount  sys.money_amount NOT NULL,             -- pii:P2
  xml_document_id      uuid,                                  -- soft core.document
  xml_sha256           text,
  pdf_document_id      uuid,                                  -- soft core.document (UNSIGNED in P1)
  signature_status     text NOT NULL DEFAULT 'UNSIGNED',      -- UNSIGNED (P1) | SIGNED -- P2
  status               text NOT NULL DEFAULT 'DRAFT',         -- DRAFT | ISSUED | CANCELLED | REPLACED
  replaces_id          uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT pit_certificate_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_certificate_series_fk FOREIGN KEY (tenant_id, series_id) REFERENCES pay.pit_certificate_series (tenant_id, id),
  CONSTRAINT pit_certificate_payee_fk FOREIGN KEY (tenant_id, casual_payee_id) REFERENCES pay.casual_payee (tenant_id, id),
  CONSTRAINT pit_certificate_replaces_fk FOREIGN KEY (tenant_id, replaces_id) REFERENCES pay.pit_certificate (tenant_id, id),
  CONSTRAINT pit_certificate_no_uq UNIQUE (tenant_id, series_id, cert_no),
  CONSTRAINT pit_certificate_recipient_ck CHECK (
       (recipient_kind = 'EMPLOYEE' AND employment_id IS NOT NULL AND casual_payee_id IS NULL)
    OR (recipient_kind = 'CASUAL'   AND casual_payee_id IS NOT NULL AND employment_id IS NULL)),
  CONSTRAINT pit_certificate_months_ck CHECK (to_month >= from_month),
  CONSTRAINT pit_certificate_sig_ck CHECK (signature_status IN ('UNSIGNED','SIGNED')),
  CONSTRAINT pit_certificate_status_ck CHECK (status IN ('DRAFT','ISSUED','CANCELLED','REPLACED'))
);
COMMENT ON COLUMN pay.pit_certificate.signature_status IS 'R3-02: UNSIGNED in Phase 1; SIGNED via platform e-signature -- P2 [verify-legal for 2027]';

-- =============================================================================
-- 10. LOANS, DEDUCTION ORDERS, FINAL SETTLEMENT
-- =============================================================================

-- Employee loans / advances (BR-PAY-091: interest-free instalments in P1; interest -- P2).
CREATE TABLE pay.loan (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id         uuid NOT NULL,                        -- soft hr
  legal_entity_id       uuid NOT NULL,
  loan_no               text NOT NULL,
  loan_type             text NOT NULL,                        -- LOAN | SALARY_ADVANCE | CASH_ADVANCE
  principal_amount      sys.money_amount NOT NULL,            -- pii:P2
  interest_method       text NOT NULL DEFAULT 'NONE',         -- NONE (P1) | FLAT | REDUCING -- P2
  annual_rate           sys.rate NOT NULL DEFAULT 0,
  outstanding_amount    sys.money_amount NOT NULL,            -- pii:P2
  installment_count     smallint,
  installment_amount    sys.money_amount,
  start_month           sys.month_date NOT NULL,
  status                text NOT NULL DEFAULT 'DRAFT',
  source                text NOT NULL DEFAULT 'APP',          -- APP | OPENING (OB_LOAN, ADR-0037) | PEOPLE_EVENT
  import_job_id         uuid,                                 -- soft core.import_job
  workflow_instance_id  uuid,                                 -- soft core
  disbursed_run_id      uuid,
  disbursed_at          date,
  settled_at            date,
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT loan_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT loan_run_fk FOREIGN KEY (tenant_id, disbursed_run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT loan_no_uq UNIQUE (tenant_id, loan_no),
  CONSTRAINT loan_type_ck CHECK (loan_type IN ('LOAN','SALARY_ADVANCE','CASH_ADVANCE')),
  CONSTRAINT loan_interest_ck CHECK (interest_method IN ('NONE','FLAT','REDUCING')
                                     AND (interest_method <> 'NONE' OR annual_rate = 0)),
  CONSTRAINT loan_amounts_ck CHECK (principal_amount > 0 AND outstanding_amount >= 0 AND outstanding_amount <= principal_amount),
  CONSTRAINT loan_status_ck CHECK (status IN ('DRAFT','PENDING_APPROVAL','ACTIVE','PAUSED','SETTLED','WRITTEN_OFF','CANCELLED')),
  CONSTRAINT loan_source_ck CHECK (source IN ('APP','OPENING','PEOPLE_EVENT')),
  CONSTRAINT loan_opening_ck CHECK (source <> 'OPENING' OR import_job_id IS NOT NULL)
);
CREATE INDEX loan_employment_ix ON pay.loan (tenant_id, employment_id, status);
COMMENT ON COLUMN pay.loan.interest_method IS 'NONE in Phase 1 (interest-free, BR-PAY-091); FLAT/REDUCING -- P2';

CREATE TABLE pay.loan_schedule (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  loan_id            uuid NOT NULL,
  installment_no     smallint NOT NULL,
  due_month          sys.month_date NOT NULL,
  principal_amount   sys.money_amount NOT NULL,
  interest_amount    sys.money_amount NOT NULL DEFAULT 0,     -- 0 in P1
  deducted_amount    sys.money_amount NOT NULL DEFAULT 0,
  status             text NOT NULL DEFAULT 'SCHEDULED',       -- SCHEDULED | DEDUCTED | PARTIAL | SKIPPED | CARRIED | WAIVED
  run_id             uuid,                                    -- approved run that deducted it
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT loan_schedule_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT loan_schedule_loan_fk FOREIGN KEY (tenant_id, loan_id) REFERENCES pay.loan (tenant_id, id),
  CONSTRAINT loan_schedule_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT loan_schedule_uq UNIQUE (tenant_id, loan_id, installment_no),
  CONSTRAINT loan_schedule_status_ck CHECK (status IN ('SCHEDULED','DEDUCTED','PARTIAL','SKIPPED','CARRIED','WAIVED')),
  CONSTRAINT loan_schedule_amounts_ck CHECK (principal_amount >= 0 AND interest_amount >= 0 AND deducted_amount >= 0)
);

-- Recurring / court / damage-compensation / advance deductions (BR-PAY-092/093).
-- priority implements the statutory order: STATUTORY -> COURT_ORDER -> ADVANCE -> LOAN -> OTHER.
CREATE TABLE pay.deduction_order (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id      uuid NOT NULL,                           -- soft hr
  order_type         text NOT NULL,
  component_id       uuid NOT NULL,
  amount_type        text NOT NULL,                           -- FIXED | PERCENT
  amount             sys.money_amount,
  pct                numeric(7,4),
  cap_pct            numeric(7,4),                            -- e.g. 30 % of net monthly pay for DAMAGE_COMPENSATION [verify-legal]
  total_amount       sys.money_amount,                        -- total to recover (NULL = open-ended)
  recovered_amount   sys.money_amount NOT NULL DEFAULT 0,
  priority           smallint NOT NULL,
  from_month         sys.month_date NOT NULL,
  to_month           sys.month_date,
  source_type        text,                                    -- people.DisciplinaryDecision | people.AdvanceDeductionRequested | COURT | MANUAL
  source_ref_id      uuid,                                    -- soft hr
  status             text NOT NULL DEFAULT 'ACTIVE',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT deduction_order_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT deduction_order_component_fk FOREIGN KEY (tenant_id, component_id) REFERENCES pay.salary_component (tenant_id, id),
  CONSTRAINT deduction_order_type_ck CHECK (order_type IN ('STATUTORY','COURT_ORDER','DAMAGE_COMPENSATION','ADVANCE','LOAN','OTHER')),
  CONSTRAINT deduction_order_amount_ck CHECK (
       (amount_type = 'FIXED'   AND amount > 0 AND pct IS NULL)
    OR (amount_type = 'PERCENT' AND pct > 0 AND pct <= 100 AND amount IS NULL)),
  CONSTRAINT deduction_order_cap_ck CHECK (cap_pct IS NULL OR (cap_pct > 0 AND cap_pct <= 100)),
  CONSTRAINT deduction_order_damage_cap_ck CHECK (order_type <> 'DAMAGE_COMPENSATION' OR cap_pct IS NOT NULL),
  CONSTRAINT deduction_order_months_ck CHECK (to_month IS NULL OR to_month >= from_month),
  CONSTRAINT deduction_order_status_ck CHECK (status IN ('ACTIVE','PAUSED','COMPLETED','CANCELLED'))
);
CREATE INDEX deduction_order_employment_ix ON pay.deduction_order (tenant_id, employment_id, priority) WHERE status = 'ACTIVE';

-- Final settlement (BR-PAY-077/078; Payroll owns the deadline clock, gap §7.3).
CREATE TABLE pay.final_settlement (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id             uuid NOT NULL,                    -- soft hr
  person_id                 uuid NOT NULL,                    -- soft hr
  legal_entity_id           uuid NOT NULL,
  termination_ref_id        uuid,                             -- soft hr termination case
  termination_date          date NOT NULL,
  last_working_date         date,
  reason_code               text NOT NULL,                    -- RESIGNATION | DISMISSAL | REDUNDANCY | CONTRACT_EXPIRY ...
  deadline_date             date NOT NULL,                    -- statutory payment deadline [verify-legal]
  status                    text NOT NULL DEFAULT 'OPEN',
  run_id                    uuid,
  service_months            numeric(7,2),                     -- working periods supplied by People
  paid_service_months       numeric(7,2),                     -- periods already compensated (People)
  ui_months_excluded        numeric(7,2),                     -- UI months from pay.si_contribution (incl. OPENING)
  severance_basis_amount    sys.money_amount,                 -- 6-month average contractual salary (pii:P2)
  severance_amount          sys.money_amount,                 -- pii:P2
  severance_kind            text,                             -- SEVERANCE | JOB_LOSS | NONE
  items                     jsonb NOT NULL DEFAULT '[]'::jsonb, -- leave payout, notice breach, training refund, advances ...
  statement_document_id     uuid,                             -- soft core.document
  paid_at                   date,
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT final_settlement_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT final_settlement_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT final_settlement_status_ck CHECK (status IN ('OPEN','CALCULATING','CALCULATED','APPROVED','PAID','CLOSED','CANCELLED')),
  CONSTRAINT final_settlement_kind_ck CHECK (severance_kind IS NULL OR severance_kind IN ('SEVERANCE','JOB_LOSS','NONE')),
  CONSTRAINT final_settlement_items_ck CHECK (jsonb_typeof(items) = 'array')
);
CREATE UNIQUE INDEX final_settlement_open_uq ON pay.final_settlement (tenant_id, employment_id) WHERE status <> 'CANCELLED';
CREATE INDEX final_settlement_deadline_ix ON pay.final_settlement (tenant_id, deadline_date) WHERE status NOT IN ('PAID','CLOSED','CANCELLED');

ALTER TABLE pay.payroll_run
  ADD CONSTRAINT payroll_run_settlement_fk FOREIGN KEY (tenant_id, final_settlement_id)
  REFERENCES pay.final_settlement (tenant_id, id);
CREATE INDEX payroll_run_settlement_ix ON pay.payroll_run (tenant_id, final_settlement_id) WHERE final_settlement_id IS NOT NULL;

-- =============================================================================
-- 11. PAYSLIP, DELIVERY, DELIVERY PREFERENCE (R2-17)
-- =============================================================================
CREATE TABLE pay.payslip (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_id                 uuid NOT NULL,
  run_employee_id        uuid NOT NULL,
  employment_id          uuid NOT NULL,                       -- soft hr
  person_id              uuid NOT NULL,                       -- soft hr
  period_month           date NOT NULL,
  template_code          text NOT NULL,
  document_id            uuid,                                -- soft core.document: archived PDF/A, NOT encrypted (PDF/A forbids it)
  document_sha256        text,
  publish_at             timestamptz NOT NULL,                -- configured publication time (BR-PAY-101)
  published_at           timestamptz,
  status                 text NOT NULL DEFAULT 'PENDING',     -- PENDING | PUBLISHED | WITHDRAWN | SUPERSEDED
  first_viewed_at        timestamptz,
  needs_print            boolean NOT NULL DEFAULT false,      -- employee without e-mail (R3-05): HR prints
  printed_at             timestamptz,
  supersedes_payslip_id  uuid,                                -- re-issued after UNLOCK
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT payslip_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT payslip_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT payslip_supersedes_fk FOREIGN KEY (tenant_id, supersedes_payslip_id) REFERENCES pay.payslip (tenant_id, id),
  CONSTRAINT payslip_status_ck CHECK (status IN ('PENDING','PUBLISHED','WITHDRAWN','SUPERSEDED')),
  CONSTRAINT payslip_published_ck CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
-- run_employee_id has no FK: run_employee rows are rewritten after UNLOCK, the payslip is superseded instead
CREATE UNIQUE INDEX payslip_current_uq ON pay.payslip (tenant_id, run_id, employment_id) WHERE status IN ('PENDING','PUBLISHED');
CREATE INDEX payslip_person_ix ON pay.payslip (tenant_id, person_id, period_month);

CREATE TABLE pay.payslip_delivery (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  payslip_id             uuid NOT NULL,
  channel                text NOT NULL,                       -- EMAIL_PDF (encrypted attachment) | EMAIL_LINK (no attachment) | PRINT
  attempt_no             smallint NOT NULL DEFAULT 1,
  recipient_email        text,                                -- pii:P2 (WORK = P1)
  attachment_sha256      text,                                -- SHA-256 of the transient encrypted PDF
  password_set_at_used   timestamptz,                         -- which password version encrypted it (never the value)
  status                 text NOT NULL DEFAULT 'QUEUED',      -- QUEUED | SENT | BOUNCED | FAILED | PRINTED
  sent_at                timestamptz,
  error_code             text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT payslip_delivery_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT payslip_delivery_payslip_fk FOREIGN KEY (tenant_id, payslip_id) REFERENCES pay.payslip (tenant_id, id),
  CONSTRAINT payslip_delivery_uq UNIQUE (tenant_id, payslip_id, channel, attempt_no),
  CONSTRAINT payslip_delivery_channel_ck CHECK (channel IN ('EMAIL_PDF','EMAIL_LINK','PRINT')),
  CONSTRAINT payslip_delivery_pdf_ck CHECK (channel <> 'EMAIL_PDF' OR (attachment_sha256 IS NOT NULL AND password_set_at_used IS NOT NULL)),
  CONSTRAINT payslip_delivery_status_ck CHECK (status IN ('QUEUED','SENT','BOUNCED','FAILED','PRINTED'))
);
COMMENT ON COLUMN pay.payslip_delivery.recipient_email IS 'pii:P2';

-- One row per PERSON (one password for all employments), DESIGN §7.5 / R2-17 / GAP-057.
-- pdf_password_enc is P3: encrypted with the person's subject DEK, write-only through the API
-- (never returned, logged, put in events or job payloads), decrypted in memory only by the
-- payslip e-mail job. Set by the employee in ESS with step-up (acr >= 2); HR/operators can
-- only clear it. No password -> e-mail without attachment (notice + ESS link).
CREATE TABLE pay.payslip_delivery_preference (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id            uuid NOT NULL,                         -- soft hr.person
  channel              text NOT NULL DEFAULT 'EMAIL_PDF',     -- ESS_ONLY | EMAIL_PDF
  email_target         text NOT NULL DEFAULT 'WORK',          -- WORK | PERSONAL
  pdf_password_enc     bytea,                                 -- pii:P3
  password_set_at      timestamptz,
  password_set_by      uuid,                                  -- the employee's user id (self-service only)
  subject_key_version  int,
  status               text NOT NULL DEFAULT 'ACTIVE',        -- ACTIVE | PASSWORD_CLEARED
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT payslip_delivery_preference_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT payslip_delivery_preference_person_uq UNIQUE (tenant_id, person_id),
  CONSTRAINT payslip_delivery_preference_channel_ck CHECK (channel IN ('ESS_ONLY','EMAIL_PDF')),
  CONSTRAINT payslip_delivery_preference_target_ck CHECK (email_target IN ('WORK','PERSONAL')),
  CONSTRAINT payslip_delivery_preference_status_ck CHECK (status IN ('ACTIVE','PASSWORD_CLEARED')),
  CONSTRAINT payslip_delivery_preference_pwd_ck CHECK (
    (pdf_password_enc IS NULL) = (password_set_at IS NULL)
    AND (pdf_password_enc IS NULL OR subject_key_version IS NOT NULL))
);
COMMENT ON COLUMN pay.payslip_delivery_preference.pdf_password_enc IS
  'pii:P3 AES-256-GCM, subject DEK of the person (R2-17). Write-only via API; never returned/logged/evented; crypto-shredded with the DEK.';

-- =============================================================================
-- 12. BANK PAYMENT FILES  (BR-PAY-110; finance gate + hash/status import -- P2 BR-PAY-112)
-- =============================================================================
-- Global (platform-provided per bank) + tenant templates (DESIGN §3.5).
CREATE TABLE pay.bank_file_template (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  code              text NOT NULL,
  bank_code         text NOT NULL,                            -- core.bank (by code): VCB, BIDV, TCB, CTG, ACB, MB, VPB, STB ...
  name              text NOT NULL,
  template_version  int NOT NULL DEFAULT 1,
  file_format       text NOT NULL,                            -- CSV | XLSX | FIXED_WIDTH | XML
  transfer_scope    text NOT NULL DEFAULT 'BOTH',             -- SAME_BANK | INTERBANK | BOTH
  encoding          text NOT NULL DEFAULT 'UTF-8',
  layout            jsonb NOT NULL,                           -- column/field mapping, header/footer, diacritics rule
  status            text NOT NULL DEFAULT 'ACTIVE',
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT bank_file_template_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT bank_file_template_uq UNIQUE (tenant_id, code, template_version),
  CONSTRAINT bank_file_template_format_ck CHECK (file_format IN ('CSV','XLSX','FIXED_WIDTH','XML')),
  CONSTRAINT bank_file_template_scope_ck CHECK (transfer_scope IN ('SAME_BANK','INTERBANK','BOTH')),
  CONSTRAINT bank_file_template_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE pay.bank_payment_file (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_id                 uuid,                                -- NULL for casual-payment batches
  legal_entity_id        uuid NOT NULL,
  payer_bank_account_id  uuid NOT NULL,                       -- soft core.legal_entity_bank_account
  template_id            uuid NOT NULL,                       -- same schema; may be a GLOBAL (sentinel tenant) row -> no FK
  template_code          text NOT NULL,
  template_version       int NOT NULL,
  file_seq               smallint NOT NULL DEFAULT 1,
  payment_date           date NOT NULL,
  line_count             int NOT NULL DEFAULT 0,
  total_amount           sys.money_amount NOT NULL DEFAULT 0, -- pii:P2
  status                 text NOT NULL DEFAULT 'GENERATED',   -- GENERATED | FINANCE_APPROVED (P2) | SENT | CONFIRMED (P2) | PARTIALLY_REJECTED (P2) | CANCELLED
  file_document_id       uuid,                                -- soft core.document
  file_sha256            text,                                -- -- P2 (BR-PAY-112)
  generation_count       smallint NOT NULL DEFAULT 1,         -- regeneration is audited
  finance_approved_by    uuid,                                -- -- P2
  finance_approved_at    timestamptz,                         -- -- P2
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT bank_payment_file_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT bank_payment_file_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT bank_payment_file_uq UNIQUE (tenant_id, run_id, payer_bank_account_id, file_seq),
  CONSTRAINT bank_payment_file_status_ck CHECK (status IN ('GENERATED','FINANCE_APPROVED','SENT','CONFIRMED','PARTIALLY_REJECTED','CANCELLED'))
);

CREATE TABLE pay.bank_payment_line (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  file_id              uuid NOT NULL,
  line_no              int NOT NULL,
  run_employee_id      uuid,                                  -- same schema, soft (results may be rewritten after UNLOCK; file then cancelled)
  casual_payment_id    uuid,
  employment_id        uuid,                                  -- soft hr
  payee_name           text NOT NULL,                         -- pii:P1 (as registered at the bank)
  bank_code            text NOT NULL,
  branch_code          text,
  account_no_enc       bytea NOT NULL,                        -- pii:P3 (fetched from People API at generation)
  account_no_last4     text NOT NULL,
  amount               sys.money_amount NOT NULL,             -- pii:P2
  currency_code        sys.currency_code NOT NULL DEFAULT 'VND',
  transfer_type        text NOT NULL,                         -- SAME_BANK | NAPAS | CITAD | CASH (P2)
  status               text NOT NULL DEFAULT 'PENDING',       -- PENDING | SENT | PAID (P2) | REJECTED (P2)
  reject_reason        text,                                  -- -- P2
  bank_ref             text,                                  -- -- P2
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT bank_payment_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT bank_payment_line_file_fk FOREIGN KEY (tenant_id, file_id) REFERENCES pay.bank_payment_file (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT bank_payment_line_casual_fk FOREIGN KEY (tenant_id, casual_payment_id) REFERENCES pay.casual_payment (tenant_id, id),
  CONSTRAINT bank_payment_line_uq UNIQUE (tenant_id, file_id, line_no),
  CONSTRAINT bank_payment_line_subject_ck CHECK (num_nonnulls(run_employee_id, casual_payment_id) = 1),
  CONSTRAINT bank_payment_line_amount_ck CHECK (amount > 0),
  CONSTRAINT bank_payment_line_transfer_ck CHECK (transfer_type IN ('SAME_BANK','NAPAS','CITAD','CASH')),
  CONSTRAINT bank_payment_line_status_ck CHECK (status IN ('PENDING','SENT','PAID','REJECTED'))
);
COMMENT ON COLUMN pay.bank_payment_line.account_no_enc IS 'pii:P3 AES-256-GCM, subject DEK of the payee (person or casual payee)';

-- =============================================================================
-- 13. GL MAPPING, GL JOURNAL; COST ALLOCATION (-- P2)
-- =============================================================================
CREATE TABLE pay.gl_mapping (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id    uuid,                                    -- soft core; NULL = tenant-wide
  component_id       uuid NOT NULL,
  cost_category      text NOT NULL DEFAULT 'ANY',             -- ANY | DIRECT (622) | PRODUCTION_OVERHEAD (627) | SELLING (641) | ADMIN (642)
  chart_of_accounts  text NOT NULL DEFAULT 'TT200',           -- TT200 | TT133 (or successor) [verify-legal]
  debit_account      text NOT NULL,
  credit_account     text NOT NULL,
  segment_rules      jsonb,                                   -- {"cost_centre":"EMPLOYEE","project":"NONE",...}
  valid_from         date NOT NULL,
  valid_to           date,
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT gl_mapping_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT gl_mapping_component_fk FOREIGN KEY (tenant_id, component_id) REFERENCES pay.salary_component (tenant_id, id),
  CONSTRAINT gl_mapping_coa_ck CHECK (chart_of_accounts IN ('TT200','TT133','OTHER')),
  CONSTRAINT gl_mapping_category_ck CHECK (cost_category IN ('ANY','DIRECT','PRODUCTION_OVERHEAD','SELLING','ADMIN')),
  CONSTRAINT gl_mapping_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT gl_mapping_period_ex EXCLUDE USING gist
    (tenant_id WITH =, component_id WITH =, cost_category WITH =,
     (coalesce(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid)) WITH =, valid_during WITH &&)
);

CREATE TABLE pay.gl_journal (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_id            uuid NOT NULL,
  legal_entity_id   uuid NOT NULL,
  journal_no        text NOT NULL,
  posting_date      date NOT NULL,
  period_month      sys.month_date NOT NULL,
  export_format     text NOT NULL DEFAULT 'CSV',              -- CSV | XLSX (ERP API -- P2)
  status            text NOT NULL DEFAULT 'GENERATED',        -- GENERATED | EXPORTED | CANCELLED
  total_debit       sys.money_amount NOT NULL DEFAULT 0,
  total_credit      sys.money_amount NOT NULL DEFAULT 0,
  file_document_id  uuid,                                     -- soft core.document
  file_sha256       text,
  exported_at       timestamptz,
  exported_by       uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT gl_journal_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT gl_journal_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES pay.payroll_run (tenant_id, id),
  CONSTRAINT gl_journal_no_uq UNIQUE (tenant_id, legal_entity_id, journal_no),
  CONSTRAINT gl_journal_format_ck CHECK (export_format IN ('CSV','XLSX','API')),
  CONSTRAINT gl_journal_status_ck CHECK (status IN ('GENERATED','EXPORTED','CANCELLED')),
  CONSTRAINT gl_journal_balanced_ck CHECK (total_debit = total_credit)
);

CREATE TABLE pay.gl_journal_line (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  journal_id        uuid NOT NULL,
  line_no           int NOT NULL,
  account_code      text NOT NULL,
  debit_amount      sys.money_amount NOT NULL DEFAULT 0,
  credit_amount     sys.money_amount NOT NULL DEFAULT 0,
  cost_centre_id    uuid,                                     -- soft core (default cost centre in P1)
  project_id        uuid,                                     -- soft core -- P2
  org_unit_id       uuid,                                     -- soft core
  component_code    text,
  description       text,
  CONSTRAINT gl_journal_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT gl_journal_line_journal_fk FOREIGN KEY (tenant_id, journal_id) REFERENCES pay.gl_journal (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT gl_journal_line_uq UNIQUE (tenant_id, journal_id, line_no),
  CONSTRAINT gl_journal_line_side_ck CHECK ((debit_amount = 0) <> (credit_amount = 0) AND debit_amount >= 0 AND credit_amount >= 0)
);

-- -- P2 (BR-PAY-114): allocation of employee cost to cost centres / projects (percentage or
-- T&A project time). Phase 1 uses the default cost centre on run_employee in the GL export.
CREATE TABLE pay.cost_allocation (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  run_id            uuid NOT NULL,
  run_employee_id   uuid NOT NULL,
  employment_id     uuid NOT NULL,
  period_month      sys.month_date NOT NULL,
  cost_centre_id    uuid,                                     -- soft core
  project_id        uuid,                                     -- soft core
  basis             text NOT NULL,                            -- DEFAULT_ASSIGNMENT | PERCENT | PROJECT_TIME | MANUAL
  pct               numeric(7,4) NOT NULL,
  amount            sys.money_amount NOT NULL,                -- gross + employer contributions + KPCĐ share (pii:P2)
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  CONSTRAINT cost_allocation_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT cost_allocation_run_employee_fk FOREIGN KEY (tenant_id, run_id, run_employee_id)
    REFERENCES pay.run_employee (tenant_id, run_id, id) ON DELETE CASCADE,
  CONSTRAINT cost_allocation_basis_ck CHECK (basis IN ('DEFAULT_ASSIGNMENT','PERCENT','PROJECT_TIME','MANUAL')),
  CONSTRAINT cost_allocation_pct_ck CHECK (pct > 0 AND pct <= 100),
  CONSTRAINT cost_allocation_target_ck CHECK (cost_centre_id IS NOT NULL OR project_id IS NOT NULL)
);
CREATE INDEX cost_allocation_run_ix ON pay.cost_allocation (tenant_id, run_id);

-- =============================================================================
-- 14. PARTITIONS  (DESIGN §10.2: created 3 periods ahead by the SaaS scheduler; no DEFAULT
--     partition, out-of-range rows must fail loudly)
-- =============================================================================
CALL sys.create_range_partitions('pay.result_line',        'month', (date_trunc('month', current_date) - interval '1 month')::date, 5);
CALL sys.create_range_partitions('pay.run_input_snapshot', 'year',  current_date, 2);
CALL sys.create_range_partitions('pay.result_archive',     'year',  (current_date - interval '3 years')::date, 1);

-- =============================================================================
-- 15. MASKING VIEWS  (DESIGN §9.4; fail closed for amounts, same template as
--     hr.v_salary_record_masked). security_invoker keeps the caller's RLS.
-- =============================================================================
CREATE OR REPLACE VIEW pay.v_run_employee_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT e.tenant_id, e.id, e.run_id, e.employment_id, e.person_id, e.legal_entity_id, e.org_unit_id,
       e.cost_centre_id, e.period_month, e.status, e.pay_basis,
       CASE WHEN pay.amounts_visible() THEN e.gross_amount END           AS gross_amount,
       CASE WHEN pay.amounts_visible() THEN e.taxable_income_amount END  AS taxable_income_amount,
       CASE WHEN pay.amounts_visible() THEN e.si_ee_amount END           AS si_ee_amount,
       CASE WHEN pay.amounts_visible() THEN e.si_er_amount END           AS si_er_amount,
       CASE WHEN pay.amounts_visible() THEN e.pit_amount END             AS pit_amount,
       CASE WHEN pay.amounts_visible() THEN e.deduction_amount END       AS deduction_amount,
       CASE WHEN pay.amounts_visible() THEN e.net_amount END             AS net_amount,
       CASE WHEN pay.amounts_visible() THEN e.employer_cost_amount END   AS employer_cost_amount,
       e.currency_code, e.exception_count, e.exceptions
  FROM pay.run_employee e;

CREATE OR REPLACE VIEW pay.v_result_line_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT l.tenant_id, l.id, l.period_month, l.run_id, l.run_employee_id, l.employment_id,
       l.component_id, l.component_code, l.line_seq, l.segment_key, l.segment_from, l.segment_to, l.qty,
       CASE WHEN pay.amounts_visible() THEN l.rate END                   AS rate,
       CASE WHEN pay.amounts_visible() THEN l.amount END                 AS amount,
       CASE WHEN pay.amounts_visible() THEN l.amount_unrounded END       AS amount_unrounded,
       CASE WHEN pay.amounts_visible() THEN l.taxable_amount END         AS taxable_amount,
       CASE WHEN pay.amounts_visible() THEN l.exempt_amount END          AS exempt_amount,
       CASE WHEN pay.amounts_visible() THEN l.si_basis_amount END        AS si_basis_amount,
       CASE WHEN pay.amounts_visible() THEN l.tu_basis_amount END        AS tu_basis_amount,
       l.retro_of_period
  FROM pay.result_line l;

CREATE OR REPLACE VIEW pay.v_ext_hr_salary_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT s.tenant_id, s.id, s.salary_record_id, s.employment_id, s.pay_basis, s.salary_kind,
       CASE WHEN pay.amounts_visible() THEN s.base_salary_amount END     AS base_salary_amount,
       CASE WHEN pay.amounts_visible() THEN s.agreed_net_amount END      AS agreed_net_amount,
       CASE WHEN pay.amounts_visible() THEN s.si_salary_amount END       AS si_salary_amount,
       CASE WHEN pay.amounts_visible() THEN s.allowances END             AS allowances,
       s.currency_code, s.pay_scale_code, s.pay_scale_step_code, s.valid_from, s.valid_to,
       s.recorded_at, s.superseded_at
  FROM pay.ext_hr_salary s;

CREATE OR REPLACE VIEW pay.v_si_contribution_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT c.tenant_id, c.id, c.employment_id, c.person_id, c.legal_entity_id, c.si_unit_code,
       c.contribution_month, c.insurance_type, c.source, c.status, c.rule_applied,
       CASE WHEN pay.amounts_visible() THEN c.basis_amount END           AS basis_amount,
       c.ee_rate, c.er_rate,
       CASE WHEN pay.amounts_visible() THEN c.ee_amount END              AS ee_amount,
       CASE WHEN pay.amounts_visible() THEN c.er_amount END              AS er_amount,
       c.run_id, c.import_job_id, c.superseded_at
  FROM pay.si_contribution c;

CREATE OR REPLACE VIEW pay.v_pit_withholding_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT w.tenant_id, w.id, w.run_id, w.employment_id, w.person_id, w.legal_entity_id, w.tax_month,
       w.residency, w.method, w.is_family_deduction_applied, w.dependant_count,
       CASE WHEN pay.amounts_visible() THEN w.gross_taxable_amount END   AS gross_taxable_amount,
       CASE WHEN pay.amounts_visible() THEN w.assessable_amount END      AS assessable_amount,
       CASE WHEN pay.amounts_visible() THEN w.pit_amount END             AS pit_amount
  FROM pay.pit_withholding w;

-- =============================================================================
-- 16. RLS, TRIGGERS, GRANTS
-- =============================================================================
DO $$
DECLARE t text;
BEGIN
  -- tenant-owned tables
  FOREACH t IN ARRAY ARRAY[
    'pay.pay_group','pay.pay_group_rule','pay.pay_calendar','pay.pay_period','pay.pay_group_assignment',
    'pay.salary_structure','pay.structure_line','pay.pay_scale','pay.pay_scale_step','pay.grade_band',
    'pay.employee_pay_profile','pay.ext_hr_salary','pay.ext_hr_bank_split','pay.ext_hr_dependant_count',
    'pay.ext_hr_insurance_participation','pay.ext_att_result','pay.residency_check',
    'pay.input_batch','pay.variable_input',
    'pay.run_config_snapshot','pay.payroll_run','pay.run_attendance_version','pay.run_input_snapshot',
    'pay.run_employee','pay.result_line','pay.run_unlock','pay.run_result_history','pay.result_archive',
    'pay.retro_item','pay.si_contribution','pay.pit_withholding','pay.ytd_balance',
    'pay.casual_payee','pay.pit_commitment','pay.casual_payment',
    'pay.si_declaration','pay.si_declaration_line','pay.pit_declaration','pay.pit_declaration_line',
    'pay.pit_finalisation','pay.pit_finalisation_line','pay.pit_finalisation_auth',
    'pay.pit_certificate_series','pay.pit_certificate',
    'pay.loan','pay.loan_schedule','pay.deduction_order','pay.final_settlement',
    'pay.payslip','pay.payslip_delivery','pay.payslip_delivery_preference',
    'pay.bank_payment_file','pay.bank_payment_line',
    'pay.gl_mapping','pay.gl_journal','pay.gl_journal_line','pay.cost_allocation']
  LOOP
    CALL sys.enable_tenant_rls(t::regclass);
  END LOOP;

  -- global templates + tenant rows (DESIGN §3.5)
  FOREACH t IN ARRAY ARRAY['pay.salary_component','pay.component_version','pay.formula','pay.formula_version',
                           'pay.bank_file_template']
  LOOP
    CALL sys.enable_tenant_or_global_rls(t::regclass);
  END LOOP;

  -- mutable tables: updated_at + tenant_id/id immutability
  FOREACH t IN ARRAY ARRAY[
    'pay.pay_group','pay.pay_group_rule','pay.pay_calendar','pay.pay_period','pay.pay_group_assignment',
    'pay.salary_component','pay.component_version','pay.formula','pay.formula_version',
    'pay.salary_structure','pay.structure_line','pay.pay_scale','pay.pay_scale_step','pay.grade_band',
    'pay.employee_pay_profile','pay.residency_check','pay.input_batch','pay.variable_input',
    'pay.payroll_run','pay.run_attendance_version','pay.retro_item','pay.si_contribution',
    'pay.casual_payee','pay.pit_commitment','pay.casual_payment',
    'pay.si_declaration','pay.pit_declaration','pay.pit_finalisation','pay.pit_finalisation_auth',
    'pay.pit_certificate_series','pay.pit_certificate',
    'pay.loan','pay.loan_schedule','pay.deduction_order','pay.final_settlement',
    'pay.payslip','pay.payslip_delivery','pay.payslip_delivery_preference',
    'pay.bank_file_template','pay.bank_payment_file','pay.bank_payment_line',
    'pay.gl_mapping','pay.gl_journal']
  LOOP
    CALL sys.attach_touch_trigger(t::regclass);
  END LOOP;

  -- row audit on configuration, inputs, lifecycle and statutory objects (BR-PAY-121).
  -- Not on result/snapshot tables (volume; immutable anyway) nor on projections.
  FOREACH t IN ARRAY ARRAY[
    'pay.pay_group','pay.pay_group_rule','pay.pay_calendar','pay.pay_period','pay.pay_group_assignment',
    'pay.salary_component','pay.component_version','pay.formula','pay.formula_version',
    'pay.salary_structure','pay.structure_line','pay.pay_scale','pay.pay_scale_step','pay.grade_band',
    'pay.employee_pay_profile','pay.input_batch','pay.variable_input','pay.payroll_run',
    'pay.run_attendance_version','pay.run_unlock','pay.retro_item','pay.si_contribution','pay.ytd_balance',
    'pay.casual_payee','pay.pit_commitment','pay.casual_payment',
    'pay.si_declaration','pay.pit_declaration','pay.pit_finalisation','pay.pit_finalisation_auth','pay.pit_certificate',
    'pay.loan','pay.loan_schedule','pay.deduction_order','pay.final_settlement',
    'pay.payslip_delivery_preference','pay.bank_file_template','pay.bank_payment_file','pay.gl_mapping']
  LOOP
    CALL audit.attach_row_audit(t::regclass);
  END LOOP;
END $$;

-- formula versions: expression hash maintained by the DB; PUBLISHED/RETIRED versions immutable
CREATE OR REPLACE FUNCTION pay.tg_formula_version_guard()
RETURNS trigger LANGUAGE plpgsql
AS $$
DECLARE
  allowed constant text[] := ARRAY['status','valid_to','valid_during','updated_at','updated_by','version'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' AND NOT sys.purge_bypass(OLD.tenant_id) THEN   -- D9 tenant purge
      RAISE EXCEPTION 'pay.formula_version %: % versions cannot be deleted', OLD.id, OLD.status USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.status <> 'DRAFT'
     AND ((to_jsonb(NEW) - allowed) IS DISTINCT FROM (to_jsonb(OLD) - allowed)
          OR (OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED')) THEN
    RAISE EXCEPTION 'pay.formula_version %: published versions are immutable; create version_no + 1', OLD.id
      USING ERRCODE = '55000';
  END IF;
  NEW.expression_sha256 := encode(sha256(convert_to(NEW.expression, 'UTF8')), 'hex');
  RETURN NEW;
END $$;
CREATE TRIGGER trg_formula_version_guard BEFORE INSERT OR UPDATE OR DELETE ON pay.formula_version
  FOR EACH ROW EXECUTE FUNCTION pay.tg_formula_version_guard();

-- run state machine (fires before trg_touch: trigger names sort alphabetically)
CREATE TRIGGER trg_run_guard BEFORE UPDATE OR DELETE ON pay.payroll_run
  FOR EACH ROW EXECUTE FUNCTION pay.tg_payroll_run_guard();
CREATE TRIGGER trg_run_guard BEFORE INSERT OR UPDATE OR DELETE ON pay.run_attendance_version
  FOR EACH ROW EXECUTE FUNCTION pay.tg_run_attendance_version_guard();

-- result immutability (ADR-0029 §5); row triggers on partitioned parents propagate to partitions
CREATE TRIGGER trg_run_immutable BEFORE INSERT OR UPDATE OR DELETE ON pay.run_employee
  FOR EACH ROW EXECUTE FUNCTION pay.tg_run_immutable();
CREATE TRIGGER trg_run_immutable BEFORE INSERT OR UPDATE OR DELETE ON pay.result_line
  FOR EACH ROW EXECUTE FUNCTION pay.tg_run_immutable();
CREATE TRIGGER trg_run_immutable BEFORE INSERT OR DELETE ON pay.run_input_snapshot
  FOR EACH ROW EXECUTE FUNCTION pay.tg_run_immutable();
CREATE TRIGGER trg_run_immutable BEFORE INSERT OR UPDATE OR DELETE ON pay.pit_withholding
  FOR EACH ROW EXECUTE FUNCTION pay.tg_run_immutable();

-- append-only tables (sys.tg_forbid_change allows DELETE for the tenant purge only, D9)
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON pay.run_config_snapshot
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE ON pay.run_input_snapshot
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON pay.run_unlock
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON pay.run_result_history
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE ON pay.result_archive          -- partitions are dropped by retention, never DELETEd
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();

-- Grants: yhrm_app_pay only (DESIGN §2.3; no pay table is on the X-02 list).
-- RLS note: app roles are granted on partitioned PARENTS only, never on partitions.
GRANT SELECT, INSERT, UPDATE ON
  pay.pay_group, pay.pay_group_rule, pay.pay_calendar, pay.pay_period, pay.pay_group_assignment,
  pay.salary_component, pay.component_version, pay.formula, pay.formula_version,
  pay.salary_structure, pay.structure_line, pay.pay_scale, pay.pay_scale_step, pay.grade_band,
  pay.employee_pay_profile, pay.ext_hr_salary, pay.ext_hr_bank_split, pay.ext_hr_dependant_count,
  pay.ext_hr_insurance_participation, pay.ext_att_result, pay.residency_check,
  pay.input_batch, pay.variable_input,
  pay.payroll_run, pay.run_attendance_version, pay.run_employee, pay.result_line,
  pay.retro_item, pay.si_contribution, pay.pit_withholding,
  pay.casual_payee, pay.pit_commitment, pay.casual_payment,
  pay.si_declaration, pay.pit_declaration, pay.pit_finalisation, pay.pit_finalisation_auth,
  pay.pit_certificate_series, pay.pit_certificate,
  pay.loan, pay.loan_schedule, pay.deduction_order, pay.final_settlement,
  pay.payslip, pay.payslip_delivery, pay.payslip_delivery_preference,
  pay.bank_file_template, pay.bank_payment_file, pay.bank_payment_line,
  pay.gl_mapping, pay.gl_journal, pay.cost_allocation
TO yhrm_app_pay;
-- append-only / write-once
GRANT SELECT, INSERT ON
  pay.run_config_snapshot, pay.run_input_snapshot, pay.run_unlock, pay.run_result_history, pay.result_archive,
  pay.ytd_balance, pay.si_declaration_line, pay.pit_declaration_line, pay.pit_finalisation_line, pay.gl_journal_line
TO yhrm_app_pay;
-- DELETE: DRAFT discards, chunk rewrite (run_employee cascades to result_line / pit_withholding /
-- cost_allocation), projection re-sync, regenerated declaration lines, import rollback of OPENING rows
-- (until the first run is calculated, ADR-0037). Further restricted by the triggers above.
GRANT DELETE ON
  pay.pay_group_rule, pay.pay_group_assignment, pay.structure_line, pay.variable_input, pay.input_batch,
  pay.ext_hr_salary, pay.ext_hr_bank_split, pay.ext_hr_dependant_count, pay.ext_hr_insurance_participation,
  pay.ext_att_result, pay.formula_version, pay.component_version,
  pay.payroll_run, pay.run_attendance_version, pay.run_employee, pay.result_line, pay.run_input_snapshot,
  pay.pit_withholding, pay.cost_allocation, pay.ytd_balance, pay.si_contribution,
  pay.si_declaration_line, pay.pit_declaration_line, pay.pit_finalisation_line, pay.gl_journal_line,
  pay.loan, pay.loan_schedule, pay.bank_payment_line
TO yhrm_app_pay;
GRANT SELECT ON pay.v_run_employee_masked, pay.v_result_line_masked, pay.v_ext_hr_salary_masked,
                pay.v_si_contribution_masked, pay.v_pit_withholding_masked
TO yhrm_app_pay;
-- D6 (2026-10-21): the masking views are security_invoker, so a role without grants on
-- the base tables cannot read them; they are NOT granted to yhrm_reporting_ro (real-time
-- statutory reports read through the pay API / reporting store). Explicit and idempotent.
REVOKE ALL ON pay.v_run_employee_masked, pay.v_result_line_masked, pay.v_ext_hr_salary_masked,
              pay.v_si_contribution_masked, pay.v_pit_withholding_masked
FROM yhrm_reporting_ro;
GRANT EXECUTE ON FUNCTION pay.amounts_visible() TO yhrm_app_pay;

-- Outbox / inbox for Payroll (pay.PayCalendarPublished, pay.PayGroupAssigned,
-- pay.PayrollRunStatusChanged, pay.SiContributionPosted, pay.FinalSettlementStatusChanged,
-- pay.CompensationCatalogueChanged ...).
CALL sys.create_outbox_inbox('pay', 'yhrm_app_pay');

RESET ROLE;
