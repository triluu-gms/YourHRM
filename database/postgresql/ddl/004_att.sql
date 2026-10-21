-- =============================================================================
-- YourHRM  ·  004_att.sql   (owner module: Time & Attendance)
-- Holiday calendars (global template + tenant copies), shifts, schedule patterns,
-- schedule assignment (T&A owns it, R2-02 §5 #26), device registry + enrolment
-- mapping (file import only in Phase 1), raw punches, attendance codes, daily
-- timesheet, corrections, overtime (request, result, cap ledger), leave (type,
-- policy, request, balance ledger), attendance period + immutable Attendance Result
-- versions + close hold (ADR-0019), event-fed projections, outbox/inbox.
-- DESIGN.md §2–§5, §7.4, §9, §10, §13; ADR-0019/0022/0024/0028/0030/0037;
-- BRD 03-time-attendance §4, §6, §7.3; gap-analysis §7.
--
-- Cross-module references are SOFT (no FK leaves the schema), comment `(soft)`:
--   employment_id, person_id, assignment_key -> hr.*   |   legal_entity_id,
--   org_unit_id, location_id, cost_centre_id -> core.*   |   pay_group_id,
--   payroll_run_id -> pay.*   |   legal_pack_id -> saas.legal_param_pack
--   import_job_id -> core.import_job   |   workflow_instance_id, *_document_id -> core.*
--
-- Phase scope: tables/values marked "-- P2" exist so Phase 2 needs no remodelling
-- but are not used by Phase 1 services. Phase-1-only CHECK value lists (e.g.
-- raw_punch.source = 'IMPORT', OD-9/R2-01) are widened by expand migrations in P2.
--
-- Data scope (DESIGN §5.3, review M9): employee-linked high-volume rows carry the
-- denormalised legal_entity_id + org_unit_id (soft, as of the row's date) with a
-- (tenant_id, org_unit_id, <date>) index.
--
-- PII (COMMENT 'pii:Px'): P3 = application-encrypted with the subject DEK of the
-- PERSON (saas.subject_key(PERSON, person_id)); health data of BR-ATT-063 is P3.
--
-- v0.4 (2026-10-17): first version of the att baseline. Applies review fixes
--   H3 (per-employment Attendance Result scope for early close of a leaver, R3-06,
--   BR-ATT-075; hold uniqueness per period x employment), M9 (denormalised
--   legal_entity_id/org_unit_id), L4 (leave tables reference leave_type_id),
--   L5 (attendance_result_line carries period_start = partition key).
--   Smoke-tested 2026-10-17 on PGlite 0.5.10 (= PostgreSQL 18.6; no PG17 build was
--   available) together with 000 and 001-003 at HEAD: loads cleanly; partitions + per-
--   partition storage options, punch dedup, hold uniqueness (PERIOD and EMPLOYMENT
--   scope), locked-result immutability, seal, reopen guard, leave overlap, cross-tenant
--   RLS, global holiday template and sys.cross_schema_grant_violations() = 0 verified.
--   Uses UNIQUE NULLS NOT DISTINCT (PG15+), so it stays within the PG16+ target.
--
-- v0.5 (2026-10-21, sponsor decisions on the DDL review):
--   D1/D10 att.attendance_result_hold is the ONLY authority for close-hold state;
--       pay.run_attendance_version is a mirror keyed by att_hold_id (005). Comments only.
--   D5  new projections att.ext_hr_employment (hire / seniority / probation / last
--       working / termination dates, status, worker category) and
--       att.ext_hr_special_status (effective-dated protected-worker flags without
--       any detail) + helper att.protected_flags(employment_id, date) (section 10).
--   D9  tenant purge bypass: every append-only / immutability trigger of this file
--       (raw punch, period, result, result line, hold, leave ledger) allows DELETE
--       when sys.purge_bypass(OLD.tenant_id) is true; UPDATE stays blocked.
--   Smoke-tested 2026-10-21 on PGlite 0.5.10 (= PostgreSQL 18.6) with 000-003 + 005.
-- =============================================================================

\set ON_ERROR_STOP on
SET ROLE yhrm_owner;
SET search_path = att, sys, public;

GRANT USAGE ON SCHEMA att TO yhrm_app_att;

-- =============================================================================
-- 1. HOLIDAY CALENDAR  (BR-ATT-001..006; "global + tenant-overridable", DESIGN §3.5)
--    Global template rows (national VN holiday pack per year, lunar dates already
--    converted, BR-ATT-002) belong to the sentinel tenant sys.global_tenant_id()
--    and are written only by the platform release job (yhrm_owner). Tenants read
--    them and copy them into their own calendars at provisioning (DESIGN §12);
--    tenants never modify global rows (policy tenant_write).
--    T&A is the sole owner of holiday calendars (ATT-P-16, att.HolidayCalendarChanged).
-- =============================================================================
CREATE TABLE att.holiday_calendar (
  tenant_id             uuid NOT NULL,                     -- sentinel = global template
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                  text NOT NULL,
  name_vi               text NOT NULL,
  name_en               text,
  country_code          char(2) NOT NULL DEFAULT 'VN',     -- core.country (global, by code)
  source                text NOT NULL DEFAULT 'CUSTOM',
  template_calendar_id  uuid,                              -- global template copied from (same table, sentinel tenant; no FK across tenants)
  template_year         smallint,                          -- last template year applied
  status                text NOT NULL DEFAULT 'ACTIVE',
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT holiday_calendar_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT holiday_calendar_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT holiday_calendar_source_ck CHECK (source IN ('PLATFORM_TEMPLATE','TENANT_COPY','CUSTOM')),
  -- only the sentinel tenant holds templates, and the sentinel holds nothing else
  CONSTRAINT holiday_calendar_global_ck CHECK (
    (tenant_id = '00000000-0000-0000-0000-000000000000'::uuid) = (source = 'PLATFORM_TEMPLATE')),
  CONSTRAINT holiday_calendar_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);
COMMENT ON TABLE att.holiday_calendar IS
  'BR-ATT-001/002. Global template rows use the sentinel tenant (DESIGN §3.5); tenant calendars are copies or custom.';

-- Which calendar applies to a legal entity (optionally a location), effective-dated (Pattern A).
CREATE TABLE att.holiday_calendar_assignment (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  calendar_id      uuid NOT NULL,
  legal_entity_id  uuid NOT NULL,                          -- -> core.legal_entity (soft)
  location_id      uuid,                                   -- -> core.location (soft); NULL = whole legal entity
  scope_key        uuid GENERATED ALWAYS AS (coalesce(location_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
  valid_from       date NOT NULL,
  valid_to         date,                                   -- EXCLUSIVE; NULL = open
  valid_during     daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT holiday_calendar_assignment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT holiday_calendar_assignment_calendar_fk FOREIGN KEY (tenant_id, calendar_id)
    REFERENCES att.holiday_calendar (tenant_id, id),
  CONSTRAINT holiday_calendar_assignment_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  -- one calendar per legal entity (or per location override) at any date
  CONSTRAINT holiday_calendar_assignment_ex EXCLUDE USING gist
    (tenant_id WITH =, legal_entity_id WITH =, scope_key WITH =, valid_during WITH &&)
);

CREATE TABLE att.holiday_date (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  calendar_id               uuid NOT NULL,
  holiday_date              date NOT NULL,                 -- solar (dương lịch) date
  day_type                  text NOT NULL,
  holiday_code              text,                          -- NEW_YEAR | TET_AL | HUNG_KINGS | REUNIFICATION | LABOUR | NATIONAL_DAY | FOREIGN_* ...
  name_vi                   text NOT NULL,
  name_en                   text,
  lunar_month               smallint,                      -- lunar reference, e.g. Tết = 1/1 âm lịch, Giỗ Tổ = 10/3 âm lịch
  lunar_day                 smallint,
  is_lunar_leap_month       boolean NOT NULL DEFAULT false,
  substitute_for_date       date,                          -- SUBSTITUTE: the holiday that fell on a rest day (BLLĐ Điều 112(3), BR-ATT-003)
  swap_pair_date            date,                          -- P2 (BR-ATT-004): SWAP_OFF <-> SWAP_WORK partner day
  is_paid                   boolean NOT NULL DEFAULT true,
  eligibility               text NOT NULL DEFAULT 'ALL',
  nationality_code          char(2),                       -- FOREIGNERS: national day of this nationality (BR-ATT-005)
  legal_ref                 text,                          -- legal basis / resolution reference
  status                    text NOT NULL DEFAULT 'ACTIVE',
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT holiday_date_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT holiday_date_calendar_fk FOREIGN KEY (tenant_id, calendar_id) REFERENCES att.holiday_calendar (tenant_id, id),
  CONSTRAINT holiday_date_type_ck CHECK (day_type IN ('PUBLIC','SUBSTITUTE','COMPANY','SWAP_OFF','SWAP_WORK')),
  CONSTRAINT holiday_date_eligibility_ck CHECK (eligibility IN ('ALL','FOREIGNERS')),
  CONSTRAINT holiday_date_nationality_ck CHECK (nationality_code IS NULL OR eligibility = 'FOREIGNERS'),
  CONSTRAINT holiday_date_lunar_ck CHECK (
    (lunar_month IS NULL AND lunar_day IS NULL)
    OR (lunar_month BETWEEN 1 AND 12 AND lunar_day BETWEEN 1 AND 30)),
  CONSTRAINT holiday_date_substitute_ck CHECK ((day_type = 'SUBSTITUTE') = (substitute_for_date IS NOT NULL)),
  CONSTRAINT holiday_date_status_ck CHECK (status IN ('ACTIVE','CANCELLED')),
  CONSTRAINT holiday_date_uq UNIQUE NULLS NOT DISTINCT (tenant_id, calendar_id, holiday_date, day_type, eligibility, nationality_code)
);
CREATE INDEX holiday_date_lookup_ix ON att.holiday_date (tenant_id, calendar_id, holiday_date) WHERE status = 'ACTIVE';
COMMENT ON COLUMN att.holiday_date.substitute_for_date IS
  'Calendar-level substitute day. Per-employee substitution against the employee''s own weekly rest days (BR-ATT-003) is derived in daily processing, not stored here.';

-- =============================================================================
-- 2. SHIFTS, SCHEDULE PATTERNS, SCHEDULE ASSIGNMENT  (BR-ATT-010/011/014/015)
--    Shift times are local `time` + crosses_midnight (DESIGN §4.7). The work date
--    of a shift is the date it starts (BRD §7.3).
-- =============================================================================
CREATE TABLE att.shift_definition (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                    text NOT NULL,
  name_vi                 text NOT NULL,
  name_en                 text,
  start_time              time NOT NULL,
  end_time                time NOT NULL,
  crosses_midnight        boolean NOT NULL DEFAULT false,
  core_start_time         time,                            -- P2 flex core hours (BR-ATT-012)
  core_end_time           time,
  grace_in_min            smallint NOT NULL DEFAULT 0,
  grace_out_min           smallint NOT NULL DEFAULT 0,
  rounding_rule_code      text,                            -- tenant code list (e.g. ROUND_DOWN_15)
  punch_window_before_min smallint NOT NULL DEFAULT 120,   -- BR-ATT-030 tolerance to attach punches
  punch_window_after_min  smallint NOT NULL DEFAULT 240,
  min_minutes_half_day    smallint,
  min_minutes_full_day    smallint,
  std_hours               sys.quantity NOT NULL,           -- standard hours credited
  day_value               numeric(4,2) NOT NULL DEFAULT 1, -- 1 | 0.5 ...
  night_window_start      time,                            -- override of legal 22:00-06:00 (VN-02), NULL = legal pack
  night_window_end        time,
  shift_group_code        text,                            -- groups for SHIFT_COUNT.<group> (BRD §7.3 #12)
  status                  text NOT NULL DEFAULT 'ACTIVE',
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT shift_definition_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT shift_definition_code_uq UNIQUE (tenant_id, code),
  -- end <= start means the shift ends on the next calendar day (24h shift when equal)
  CONSTRAINT shift_definition_midnight_ck CHECK (crosses_midnight = (end_time <= start_time)),
  CONSTRAINT shift_definition_core_ck CHECK ((core_start_time IS NULL) = (core_end_time IS NULL)),
  CONSTRAINT shift_definition_night_ck CHECK ((night_window_start IS NULL) = (night_window_end IS NULL)),
  CONSTRAINT shift_definition_values_ck CHECK (std_hours >= 0 AND std_hours <= 24 AND day_value >= 0 AND day_value <= 1
                                               AND grace_in_min >= 0 AND grace_out_min >= 0),
  CONSTRAINT shift_definition_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- Breaks of a shift (VN-03: paid break >= 30/45 min counts as working time).
CREATE TABLE att.shift_break (
  tenant_id       uuid NOT NULL,
  shift_id        uuid NOT NULL,
  seq             smallint NOT NULL,
  break_start     time,                                    -- NULL for a flexible break (duration only)
  break_end       time,
  duration_min    smallint NOT NULL,
  is_paid         boolean NOT NULL DEFAULT false,
  is_flexible     boolean NOT NULL DEFAULT false,
  CONSTRAINT shift_break_pk PRIMARY KEY (tenant_id, shift_id, seq),
  CONSTRAINT shift_break_shift_fk FOREIGN KEY (tenant_id, shift_id) REFERENCES att.shift_definition (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT shift_break_times_ck CHECK ((break_start IS NULL) = (break_end IS NULL)),
  CONSTRAINT shift_break_flex_ck CHECK (is_flexible OR break_start IS NOT NULL),
  CONSTRAINT shift_break_duration_ck CHECK (duration_min > 0)
);

CREATE TABLE att.schedule_pattern (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  code               text NOT NULL,
  name_vi            text NOT NULL,
  name_en            text,
  pattern_type       text NOT NULL DEFAULT 'WEEKLY',
  cycle_length_days  smallint NOT NULL DEFAULT 7,          -- 7 = weekly; 14 = alternating Saturdays
  anchor_date        date NOT NULL,                        -- day_index 0 of the cycle (a Monday for WEEKLY)
  weekly_rest_days   smallint[] NOT NULL,                  -- ISO dow 1=Mon..7=Sun; BR-ATT-014 (may differ from Sunday)
  std_hours_week     sys.quantity,
  status             text NOT NULL DEFAULT 'ACTIVE',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT schedule_pattern_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT schedule_pattern_code_uq UNIQUE (tenant_id, code),
  -- P1: weekly incl. 5 / 5.5 / 6 days and alternating Saturdays (BR-ATT-011).
  -- 'ROTATING' (N-day cycles) and 'FLEX' (BR-ATT-012) are P2 -> expand migration.
  CONSTRAINT schedule_pattern_type_ck CHECK (pattern_type IN ('WEEKLY')),
  CONSTRAINT schedule_pattern_cycle_ck CHECK (cycle_length_days BETWEEN 1 AND 62),
  CONSTRAINT schedule_pattern_rest_ck CHECK (weekly_rest_days <@ ARRAY[1,2,3,4,5,6,7]::smallint[]),
  CONSTRAINT schedule_pattern_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE att.schedule_pattern_day (
  tenant_id    uuid NOT NULL,
  pattern_id   uuid NOT NULL,
  day_index    smallint NOT NULL,                          -- 0 .. cycle_length_days-1 from anchor_date
  day_kind     text NOT NULL,
  shift_id     uuid,
  CONSTRAINT schedule_pattern_day_pk PRIMARY KEY (tenant_id, pattern_id, day_index),
  CONSTRAINT schedule_pattern_day_pattern_fk FOREIGN KEY (tenant_id, pattern_id) REFERENCES att.schedule_pattern (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT schedule_pattern_day_shift_fk FOREIGN KEY (tenant_id, shift_id) REFERENCES att.shift_definition (tenant_id, id),
  CONSTRAINT schedule_pattern_day_kind_ck CHECK (day_kind IN ('WORK','REST','OFF')),   -- REST = weekly rest day; OFF = non-working, not rest
  CONSTRAINT schedule_pattern_day_shift_ck CHECK ((day_kind = 'WORK') = (shift_id IS NOT NULL)),
  CONSTRAINT schedule_pattern_day_index_ck CHECK (day_index >= 0)
);
CREATE INDEX schedule_pattern_day_shift_ix ON att.schedule_pattern_day (tenant_id, shift_id);

-- Rule-based assignment (BR-ATT-015): first matching rule by priority generates
-- schedule_assignment rows (source = RULE); manual rows override.
CREATE TABLE att.schedule_assignment_rule (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                    text NOT NULL,
  priority                smallint NOT NULL,               -- lower = evaluated first
  legal_entity_id         uuid,                            -- -> core.legal_entity (soft); criteria, NULL = any
  org_unit_id             uuid,                            -- -> core.org_unit (soft), includes sub-tree
  location_id             uuid,                            -- -> core.location (soft)
  job_title_id            uuid,                            -- -> core.job_title (soft)
  employee_category_code  text,
  working_regime_code     text,                            -- People contractual regime
  pattern_id              uuid NOT NULL,
  valid_from              date NOT NULL,
  valid_to                date,
  valid_during            daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  status                  text NOT NULL DEFAULT 'ACTIVE',
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT schedule_assignment_rule_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT schedule_assignment_rule_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT schedule_assignment_rule_pattern_fk FOREIGN KEY (tenant_id, pattern_id) REFERENCES att.schedule_pattern (tenant_id, id),
  CONSTRAINT schedule_assignment_rule_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT schedule_assignment_rule_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- Schedule assignment: Pattern A (DESIGN §5.1), owned by T&A (BR-ATT-015, R2-02 §5 #26).
CREATE TABLE att.schedule_assignment (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id    uuid NOT NULL,                          -- -> hr.employment (soft)
  assignment_key   uuid NOT NULL,                          -- -> hr.assignment.assignment_key (soft; stable across bi-temporal versions)
  pattern_id       uuid NOT NULL,
  rule_id          uuid,                                   -- set when source = RULE
  valid_from       date NOT NULL,
  valid_to         date,                                   -- EXCLUSIVE; NULL = open
  valid_during     daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  source           text NOT NULL DEFAULT 'MANUAL',
  status           text NOT NULL DEFAULT 'ACTIVE',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT schedule_assignment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT schedule_assignment_pattern_fk FOREIGN KEY (tenant_id, pattern_id) REFERENCES att.schedule_pattern (tenant_id, id),
  CONSTRAINT schedule_assignment_rule_fk FOREIGN KEY (tenant_id, rule_id) REFERENCES att.schedule_assignment_rule (tenant_id, id),
  CONSTRAINT schedule_assignment_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT schedule_assignment_source_ck CHECK (source IN ('RULE','MANUAL','IMPORT','OPENING_LOAD')),
  CONSTRAINT schedule_assignment_rule_src_ck CHECK ((source = 'RULE') = (rule_id IS NOT NULL)),
  CONSTRAINT schedule_assignment_status_ck CHECK (status IN ('ACTIVE','CANCELLED')),
  -- one pattern per assignment line at any date
  CONSTRAINT schedule_assignment_period_ex EXCLUDE USING gist
    (tenant_id WITH =, assignment_key WITH =, valid_during WITH &&) WHERE (status = 'ACTIVE')
);
CREATE INDEX schedule_assignment_employment_ix ON att.schedule_assignment (tenant_id, employment_id, valid_from) WHERE status = 'ACTIVE';
CREATE INDEX schedule_assignment_pattern_ix ON att.schedule_assignment (tenant_id, pattern_id);

-- -----------------------------------------------------------------------------
-- 2a. ROSTER  -- P2 (BR-ATT-017/018/019). Planned shift per employee per day; when
--     a published roster line exists it overrides the schedule pattern for that day.
-- -----------------------------------------------------------------------------
CREATE TABLE att.roster (                                  -- P2
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  org_unit_id      uuid NOT NULL,                          -- -> core.org_unit (soft)
  location_id      uuid,                                   -- -> core.location (soft)
  period_start     date NOT NULL,
  period_end       date NOT NULL,                          -- INCLUSIVE
  status           text NOT NULL DEFAULT 'DRAFT',
  published_at     timestamptz,
  published_by     uuid,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT roster_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT roster_dates_ck CHECK (period_end >= period_start),
  CONSTRAINT roster_status_ck CHECK (status IN ('DRAFT','PUBLISHED','CANCELLED'))
);

CREATE TABLE att.roster_line (                             -- P2
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  roster_id        uuid NOT NULL,
  employment_id    uuid NOT NULL,                          -- -> hr.employment (soft)
  work_date        date NOT NULL,
  shift_id         uuid,                                   -- NULL = rest day
  day_kind         text NOT NULL DEFAULT 'WORK',
  line_version     int NOT NULL DEFAULT 1,                 -- bumped on change after publish (notification)
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT roster_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT roster_line_roster_fk FOREIGN KEY (tenant_id, roster_id) REFERENCES att.roster (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT roster_line_shift_fk FOREIGN KEY (tenant_id, shift_id) REFERENCES att.shift_definition (tenant_id, id),
  CONSTRAINT roster_line_kind_ck CHECK (day_kind IN ('WORK','REST','OFF')),
  CONSTRAINT roster_line_shift_ck CHECK ((day_kind = 'WORK') = (shift_id IS NOT NULL)),
  CONSTRAINT roster_line_uq UNIQUE (tenant_id, roster_id, employment_id, work_date)
);
CREATE INDEX roster_line_employment_ix ON att.roster_line (tenant_id, employment_id, work_date);

-- =============================================================================
-- 3. DEVICE REGISTRY and ENROLMENT MAPPING  (BR-ATT-020/022/025; OD-9, R2-01)
--    Phase 1 has NO live device connector: a device row is a registry entry that
--    maps the terminal id appearing in the customer's device-export file to a
--    location/time zone; enrolment mapping resolves enrol ids during file import.
--    att.geofence / wifi zone (BR-ATT-023) -- P2, not created in the baseline.
-- =============================================================================
CREATE TABLE att.device (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  code               text NOT NULL,
  name               text NOT NULL,
  terminal_code      text NOT NULL,                        -- terminal / machine id as written in the export file
  vendor             text,                                 -- ZKTECO | HIKVISION | OTHER ...
  model              text,
  serial_no          text,
  legal_entity_id    uuid NOT NULL,                        -- -> core.legal_entity (soft)
  location_id        uuid,                                 -- -> core.location (soft)
  time_zone          text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',   -- IANA; device clock zone (NFR-ATT-005)
  connector_type     text NOT NULL DEFAULT 'FILE_IMPORT',
  file_layout_code   text,                                 -- vendor column layout of the export template
  last_import_at     timestamptz,
  last_sync_at       timestamptz,                          -- P2 (live connector)
  status             text NOT NULL DEFAULT 'ACTIVE',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT device_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT device_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT device_terminal_uq UNIQUE (tenant_id, terminal_code),
  -- P1: file import only (R2-01). 'AGENT_PULL','AGENT_PUSH' (BR-ATT-021) are P2.
  CONSTRAINT device_connector_ck CHECK (connector_type IN ('FILE_IMPORT')),
  CONSTRAINT device_status_ck CHECK (status IN ('ACTIVE','INACTIVE','RETIRED'))
);

-- Enrol id on a device (or tenant-wide when the same id is used on every terminal)
-- -> employment, effective-dated (BR-ATT-022). Unknown/expired mappings quarantine punches.
CREATE TABLE att.enrolment_mapping (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  device_id        uuid,                                   -- NULL = valid on every device of the tenant
  scope_key        uuid GENERATED ALWAYS AS (coalesce(device_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
  enrol_id         text NOT NULL,
  employment_id    uuid NOT NULL,                          -- -> hr.employment (soft)
  valid_from       date NOT NULL,
  valid_to         date,                                   -- EXCLUSIVE; NULL = open
  valid_during     daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  source           text NOT NULL DEFAULT 'APP',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT enrolment_mapping_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT enrolment_mapping_device_fk FOREIGN KEY (tenant_id, device_id) REFERENCES att.device (tenant_id, id),
  CONSTRAINT enrolment_mapping_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT enrolment_mapping_source_ck CHECK (source IN ('APP','IMPORT','OPENING_LOAD')),
  -- one employment per enrol id per device scope at any date
  CONSTRAINT enrolment_mapping_ex EXCLUDE USING gist
    (tenant_id WITH =, scope_key WITH =, enrol_id WITH =, valid_during WITH &&)
);
CREATE INDEX enrolment_mapping_employment_ix ON att.enrolment_mapping (tenant_id, employment_id);

-- =============================================================================
-- 4. RAW PUNCH  (BR-ATT-020/022/027; NFR-ATT-001/005/007; DESIGN §7.4, §10)
--    Append-only evidence. Monthly range partitions on work_date_hint (no DEFAULT
--    partition: an out-of-range row must fail loudly, DESIGN §10.2).
--    Ingestion = batched INSERT ... ON CONFLICT (tenant_id, dedup_key, work_date_hint)
--    DO NOTHING, one batch per import-job chunk (Phase 1: device-export files only).
--    work_date_hint MUST be deterministic for a given punch = the LOCAL calendar
--    date of local_ts (not the shift-assigned work date), otherwise a re-import could
--    land in another partition and escape dedup. Daily processing reads hint D and
--    D+1 for night shifts and assigns the real work date (BR-ATT-030).
--    Only the matching columns may change, once, when a quarantined punch is
--    resolved (att.tg_raw_punch_guard); evidence columns never change (BR-ATT-027).
-- =============================================================================
CREATE TABLE att.raw_punch (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  work_date_hint       date NOT NULL,                      -- partition key = local date of local_ts
  punch_at             timestamptz NOT NULL,               -- normalised to UTC
  local_ts             timestamp NOT NULL,                 -- device wall-clock as received
  tz_name              text NOT NULL,                      -- IANA zone used for normalisation
  source               text NOT NULL DEFAULT 'IMPORT',
  device_id            uuid,                               -- NULL when the terminal is not registered
  terminal_code        text NOT NULL,                      -- terminal id as in the file (part of dedup_key)
  enrol_id             text NOT NULL,
  direction            text NOT NULL DEFAULT 'UNKNOWN',
  verify_method        text,                               -- FINGERPRINT | FACE | CARD | PASSWORD | UNKNOWN (no biometric templates stored, NFR-ATT-008)
  dedup_key            text NOT NULL,                      -- hex sha256(terminal_code|enrol_id|punch_at), NFR-ATT-001
  import_job_id        uuid,                               -- -> core.import_job (soft); required for source IMPORT
  import_row_no        int,
  received_at          timestamptz NOT NULL DEFAULT now(),
  -- matching (nullable until matched; M9 data-scope columns)
  employment_id        uuid,                               -- -> hr.employment (soft)
  legal_entity_id      uuid,                               -- -> core.legal_entity (soft)
  org_unit_id          uuid,                               -- -> core.org_unit (soft)
  enrolment_mapping_id uuid,                               -- same schema; no FK (partitioned evidence table, see §10.2 archiving)
  validation_status    text NOT NULL DEFAULT 'PENDING',
  matched_at           timestamptz,
  matched_by           uuid,
  -- P2 only (OD-9): mobile/web capture
  geo_enc              bytea,                              -- P2: GPS lat/long, pii:P3
  photo_ref_enc        bytea,                              -- P2: selfie object reference, pii:P3
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  CONSTRAINT raw_punch_pk PRIMARY KEY (tenant_id, id, work_date_hint),
  CONSTRAINT raw_punch_dedup_uq UNIQUE (tenant_id, dedup_key, work_date_hint),
  -- P1: only device-export file import (OD-9, R2-01). 'DEVICE','MOBILE','WEB','API' are P2.
  CONSTRAINT raw_punch_source_ck CHECK (source IN ('IMPORT')),
  CONSTRAINT raw_punch_import_ck CHECK (source <> 'IMPORT' OR import_job_id IS NOT NULL),
  CONSTRAINT raw_punch_direction_ck CHECK (direction IN ('IN','OUT','UNKNOWN')),
  CONSTRAINT raw_punch_validation_ck CHECK (validation_status IN
    ('PENDING','VALID','QUARANTINED_UNMAPPED','QUARANTINED_EXPIRED','QUARANTINED_UNKNOWN_DEVICE','IGNORED')),
  CONSTRAINT raw_punch_match_ck CHECK (employment_id IS NOT NULL OR (legal_entity_id IS NULL AND org_unit_id IS NULL)),
  CONSTRAINT raw_punch_valid_ck CHECK (validation_status <> 'VALID' OR employment_id IS NOT NULL),
  CONSTRAINT raw_punch_hint_ck CHECK (work_date_hint = local_ts::date),
  CONSTRAINT raw_punch_p2_ck CHECK (geo_enc IS NULL AND photo_ref_enc IS NULL)          -- P2 columns stay empty in P1
) PARTITION BY RANGE (work_date_hint);
CREATE INDEX raw_punch_employment_ix ON att.raw_punch (tenant_id, employment_id, work_date_hint, punch_at);
CREATE INDEX raw_punch_quarantine_ix ON att.raw_punch (tenant_id, work_date_hint)
  WHERE validation_status LIKE 'QUARANTINED%' OR validation_status = 'PENDING';
CREATE INDEX raw_punch_scope_ix ON att.raw_punch (tenant_id, org_unit_id, work_date_hint);
CREATE INDEX raw_punch_import_ix ON att.raw_punch (tenant_id, import_job_id);
COMMENT ON TABLE att.raw_punch IS
  'Append-only punch evidence (BR-ATT-020/027). P1 rows come only from imported device-export files (OD-9, R2-01).';
COMMENT ON COLUMN att.raw_punch.enrol_id  IS 'pii:P1 device user id';
COMMENT ON COLUMN att.raw_punch.geo_enc   IS 'pii:P3 P2 only (OD-9): GPS coordinates, subject DEK';
COMMENT ON COLUMN att.raw_punch.photo_ref_enc IS 'pii:P3 P2 only (OD-9): selfie reference, retention 90 days (NFR-ATT-008)';
COMMENT ON COLUMN att.raw_punch.punch_at  IS 'pii:P2 presence data';

CREATE OR REPLACE FUNCTION att.tg_raw_punch_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  allowed constant text[] := ARRAY['employment_id','legal_entity_id','org_unit_id','enrolment_mapping_id',
                                   'validation_status','matched_at','matched_by'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF sys.purge_bypass(OLD.tenant_id) THEN
      RETURN OLD;                                          -- D9 tenant purge (DESIGN §3.7)
    END IF;
    RAISE EXCEPTION 'att.raw_punch is append-only (BR-ATT-027); purge only by partition detach/archive'
      USING ERRCODE = '55000';
  END IF;
  IF (to_jsonb(NEW) - allowed) IS DISTINCT FROM (to_jsonb(OLD) - allowed) THEN
    RAISE EXCEPTION 'att.raw_punch evidence columns are immutable (BR-ATT-027); only matching columns may change'
      USING ERRCODE = '55000';
  END IF;
  IF OLD.validation_status IN ('VALID','IGNORED') THEN
    RAISE EXCEPTION 'att.raw_punch % is already resolved (%)', OLD.id, OLD.validation_status USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_raw_punch_guard BEFORE UPDATE OR DELETE ON att.raw_punch
  FOR EACH ROW EXECUTE FUNCTION att.tg_raw_punch_guard();

-- =============================================================================
-- 5. ATTENDANCE CODE  (BR-ATT-033; global + tenant-overridable, DESIGN §3.5/§4.5)
--    Platform default symbols (X, P, Ô, TS, L, CT, WFH, KL, V ...) are sentinel rows;
--    tenants add or shadow codes. In P1 business trips (CT) and WFH are attendance
--    codes entered via corrections/admin edit (BR-ATT-070 is P2).
-- =============================================================================
CREATE TABLE att.attendance_code (
  tenant_id          uuid NOT NULL,                        -- sentinel = platform default
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  code               text NOT NULL,                        -- e.g. X, P, Ô, TS, L, CT, WFH, KL, V
  label_vi           text NOT NULL,
  label_en           text,
  code_kind          text NOT NULL,
  payroll_category   text NOT NULL,                        -- Attendance Result category (BRD §7.3), e.g. WORKED_DAYS, PAID_LEAVE_DAYS
  payroll_sub_code   text,                                 -- e.g. ANNUAL for PAID_LEAVE_DAYS.ANNUAL
  counts_as_worked   boolean NOT NULL DEFAULT false,
  paid_by            text NOT NULL,
  counts_si_nonwork  boolean NOT NULL DEFAULT false,       -- feeds SI_NONWORK_DAYS (VN-18)
  display_order      smallint NOT NULL DEFAULT 100,
  color_hex          text,
  status             text NOT NULL DEFAULT 'ACTIVE',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT attendance_code_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT attendance_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT attendance_code_kind_ck CHECK (code_kind IN
    ('WORK','LEAVE','HOLIDAY','TRIP','WFH','ABSENCE','REST','NOT_EMPLOYED','SUSPENDED')),
  CONSTRAINT attendance_code_paid_by_ck CHECK (paid_by IN ('EMPLOYER','SI_FUND','UNPAID','NONE')),
  CONSTRAINT attendance_code_category_ck CHECK (payroll_category ~ '^[A-Z][A-Z_]*$'),
  CONSTRAINT attendance_code_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- =============================================================================
-- 6. DAILY TIMESHEET  (BR-ATT-030..039; DESIGN §7.4, §10.2)
--    Mutable until its period is LOCKED (enforced by the service: recalculation and
--    corrections check the period status). Recomputed, not edited: calc_version + 1
--    per recalculation. Monthly partitions on work_date, fillfactor 80 for HOT
--    updates (set per partition by att.create_partitions; partitioned parents
--    cannot carry storage parameters).
--    One row per employment per work date (work date = start date of the shift).
-- =============================================================================
CREATE TABLE att.daily_timesheet (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id          uuid NOT NULL,                    -- -> hr.employment (soft)
  work_date              date NOT NULL,                    -- partition key
  assignment_key         uuid,                             -- -> hr.assignment.assignment_key (soft); segment of the day
  legal_entity_id        uuid NOT NULL,                    -- -> core.legal_entity (soft), M9
  org_unit_id            uuid,                             -- -> core.org_unit (soft), M9; NULL on NOT_EMPLOYED days
  location_id            uuid,                             -- -> core.location (soft)
  pay_group_id           uuid,                             -- -> pay.pay_group (soft, from ext_pay_pay_group_assignment)
  period_id              uuid,                             -- attendance period the day belongs to
  shift_id               uuid,                             -- planned shift (pattern or roster)
  day_type               text NOT NULL,
  day_status             text NOT NULL,
  primary_code           text,                             -- attendance_code.code (code may be a global row: no FK)
  codes                  jsonb NOT NULL DEFAULT '[]'::jsonb, -- [{"code":"P","qty":0.5},{"code":"X","qty":0.5}]
  first_in_at            timestamptz,
  last_out_at            timestamptz,
  scheduled_hours        sys.quantity NOT NULL DEFAULT 0,
  worked_hours           sys.quantity NOT NULL DEFAULT 0,
  paid_break_hours       sys.quantity NOT NULL DEFAULT 0,
  protected_break_min    smallint NOT NULL DEFAULT 0,      -- statutory paid short breaks (BR-ATT-035)
  late_min               int NOT NULL DEFAULT 0,
  early_min              int NOT NULL DEFAULT 0,
  absent_hours           sys.quantity NOT NULL DEFAULT 0,
  night_hours            sys.quantity NOT NULL DEFAULT 0,  -- NW: night work in normal hours (VN-02/VN-10)
  ot_candidate_min       int NOT NULL DEFAULT 0,
  day_value              numeric(5,2) NOT NULL DEFAULT 0,  -- 0 / 0.5 / 1 or decimal
  exceptions             text[] NOT NULL DEFAULT '{}',     -- MISSING_IN | MISSING_OUT | LATE | EARLY | ABSENT_NO_CODE | QUARANTINED_PUNCH | OT_CAP
  has_blocking_exception boolean NOT NULL DEFAULT false,   -- close checklist (BR-ATT-074)
  is_manual_override     boolean NOT NULL DEFAULT false,   -- admin edit applied (BR-ATT-037, see timesheet_correction)
  calc_version           int NOT NULL DEFAULT 1,
  calc_at                timestamptz NOT NULL DEFAULT now(),
  calc_inputs_hash       text,                             -- determinism check (NFR-ATT-006)
  legal_pack_id          uuid,                             -- -> saas.legal_param_pack (soft), NFR-ATT-006
  rule_version           text,                             -- T&A rule/config version used
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT daily_timesheet_pk PRIMARY KEY (tenant_id, id, work_date),
  CONSTRAINT daily_timesheet_day_uq UNIQUE (tenant_id, employment_id, work_date),
  CONSTRAINT daily_timesheet_shift_fk FOREIGN KEY (tenant_id, shift_id) REFERENCES att.shift_definition (tenant_id, id),
  CONSTRAINT daily_timesheet_day_type_ck CHECK (day_type IN
    ('WORKDAY','REST_DAY','PUBLIC_HOLIDAY','SUBSTITUTE_HOLIDAY','COMPANY_HOLIDAY')),
  CONSTRAINT daily_timesheet_status_ck CHECK (day_status IN
    ('SCHEDULED','PRESENT','PARTIAL','ABSENT','ON_LEAVE','HOLIDAY','REST','INCOMPLETE','NOT_EMPLOYED','SUSPENDED')),  -- BR-ATT-039
  CONSTRAINT daily_timesheet_codes_ck CHECK (jsonb_typeof(codes) = 'array'),
  CONSTRAINT daily_timesheet_values_ck CHECK (
    scheduled_hours >= 0 AND worked_hours >= 0 AND paid_break_hours >= 0 AND absent_hours >= 0
    AND night_hours >= 0 AND late_min >= 0 AND early_min >= 0 AND ot_candidate_min >= 0
    AND protected_break_min >= 0 AND day_value >= 0 AND calc_version >= 1),
  CONSTRAINT daily_timesheet_inout_ck CHECK (last_out_at IS NULL OR first_in_at IS NULL OR last_out_at >= first_in_at)
) PARTITION BY RANGE (work_date);
CREATE INDEX daily_timesheet_scope_ix  ON att.daily_timesheet (tenant_id, org_unit_id, work_date);
CREATE INDEX daily_timesheet_period_ix ON att.daily_timesheet (tenant_id, period_id, employment_id);
CREATE INDEX daily_timesheet_exception_ix ON att.daily_timesheet (tenant_id, period_id) WHERE has_blocking_exception;

-- Correction request (employee, BR-ATT-036) or admin / bulk edit (BR-ATT-037) of one day.
-- Logical key (employment_id, work_date). NO FK to the partitioned daily_timesheet,
-- so that timesheet partitions can be detached/archived independently (DESIGN §10.2).
CREATE TABLE att.timesheet_correction (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id          uuid NOT NULL,                    -- -> hr.employment (soft)
  work_date              date NOT NULL,
  daily_timesheet_id     uuid,                             -- same schema, informational (no FK, see above)
  legal_entity_id        uuid NOT NULL,                    -- -> core.legal_entity (soft)
  org_unit_id            uuid,                             -- -> core.org_unit (soft)
  correction_type        text NOT NULL,
  requested_in_at        timestamptz,
  requested_out_at       timestamptz,
  requested_code         text,                             -- e.g. CT / WFH in P1 (trips/WFH are codes)
  field_changes          jsonb NOT NULL DEFAULT '[]'::jsonb, -- [{"field":"worked_hours","old":7.5,"new":8}] before/after (BR-ATT-037)
  reason_code            text,
  reason_text            text,
  attachment_document_ids uuid[] NOT NULL DEFAULT '{}',    -- -> core.document (soft)
  source                 text NOT NULL DEFAULT 'ESS',
  bulk_batch_id          uuid,                             -- groups a bulk admin edit
  status                 text NOT NULL DEFAULT 'DRAFT',
  workflow_instance_id   uuid,                             -- -> core.workflow_instance (soft)
  decided_at             timestamptz,
  decided_by             uuid,
  applied_calc_version   int,                              -- daily_timesheet.calc_version that applied it
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT timesheet_correction_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT timesheet_correction_type_ck CHECK (correction_type IN
    ('MISSING_PUNCH','WRONG_TIME','FORGOT_CHECKIN','CLIENT_SITE','CODE_CHANGE','ADMIN_EDIT')),
  CONSTRAINT timesheet_correction_source_ck CHECK (source IN ('ESS','MSS','ADMIN','BULK','IMPORT')),
  CONSTRAINT timesheet_correction_status_ck CHECK (status IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELLED','APPLIED')),
  CONSTRAINT timesheet_correction_changes_ck CHECK (jsonb_typeof(field_changes) = 'array'),
  CONSTRAINT timesheet_correction_admin_reason_ck CHECK (source NOT IN ('ADMIN','BULK') OR reason_text IS NOT NULL),  -- BR-ATT-037
  CONSTRAINT timesheet_correction_times_ck CHECK (requested_out_at IS NULL OR requested_in_at IS NULL OR requested_out_at > requested_in_at)
);
CREATE INDEX timesheet_correction_day_ix ON att.timesheet_correction (tenant_id, employment_id, work_date);
CREATE INDEX timesheet_correction_pending_ix ON att.timesheet_correction (tenant_id, org_unit_id, work_date) WHERE status = 'PENDING';

-- =============================================================================
-- 7. OVERTIME  (BR-ATT-040..049; VN-07..VN-11)
-- =============================================================================
CREATE TABLE att.overtime_request (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id          uuid NOT NULL,                    -- -> hr.employment (soft)
  legal_entity_id        uuid NOT NULL,                    -- -> core.legal_entity (soft); cap scope in P1
  org_unit_id            uuid,                             -- -> core.org_unit (soft)
  request_group_id       uuid,                             -- manager request for several employees (BR-ATT-040)
  work_date              date NOT NULL,
  planned_start_at       timestamptz NOT NULL,
  planned_end_at         timestamptz NOT NULL,
  planned_minutes        int NOT NULL,
  reason                 text,
  source                 text NOT NULL DEFAULT 'PRE_APPROVAL',
  comp_choice            text NOT NULL DEFAULT 'PAY',
  employee_consent       boolean NOT NULL DEFAULT false,   -- BLLĐ Điều 107(2): consent required
  consent_at             timestamptz,
  consent_method         text,
  protected_worker_flag  boolean NOT NULL DEFAULT false,   -- snapshot at submit (BR-ATT-048)
  cap_override           boolean NOT NULL DEFAULT false,   -- authorised override above 100% cap (BR-ATT-046)
  cap_override_reason    text,
  cap_override_by        uuid,
  status                 text NOT NULL DEFAULT 'DRAFT',
  workflow_instance_id   uuid,                             -- -> core.workflow_instance (soft)
  decided_at             timestamptz,
  decided_by             uuid,
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT overtime_request_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT overtime_request_source_ck CHECK (source IN ('PRE_APPROVAL','DETECTED')),
  CONSTRAINT overtime_request_comp_ck CHECK (comp_choice IN ('PAY')),         -- 'TOIL' is P2 (BR-ATT-050)
  CONSTRAINT overtime_request_status_ck CHECK (status IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELLED')),
  CONSTRAINT overtime_request_time_ck CHECK (planned_end_at > planned_start_at AND planned_minutes > 0),
  CONSTRAINT overtime_request_consent_ck CHECK (status NOT IN ('PENDING','APPROVED') OR employee_consent),
  CONSTRAINT overtime_request_consent_at_ck CHECK (NOT employee_consent OR consent_at IS NOT NULL),
  CONSTRAINT overtime_request_consent_method_ck CHECK (consent_method IS NULL OR consent_method IN ('ESS','PAPER','MANAGER_RECORDED')),
  CONSTRAINT overtime_request_override_ck CHECK (NOT cap_override OR (cap_override_reason IS NOT NULL AND cap_override_by IS NOT NULL))
);
CREATE INDEX overtime_request_day_ix ON att.overtime_request (tenant_id, employment_id, work_date);
CREATE INDEX overtime_request_pending_ix ON att.overtime_request (tenant_id, org_unit_id, work_date) WHERE status = 'PENDING';

-- Paid overtime per day per category (BR-ATT-041/042/044). Recomputed with the day.
-- Logical link to the day is (employment_id, work_date); no FK to the partitioned timesheet.
CREATE TABLE att.overtime_result (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id          uuid NOT NULL,                    -- -> hr.employment (soft)
  legal_entity_id        uuid NOT NULL,                    -- -> core.legal_entity (soft)
  org_unit_id            uuid,                             -- -> core.org_unit (soft)
  work_date              date NOT NULL,
  ot_category            text NOT NULL,
  actual_minutes         int NOT NULL DEFAULT 0,           -- from punches
  approved_minutes       int NOT NULL DEFAULT 0,           -- from approved requests
  paid_minutes           int NOT NULL,                     -- min(approved, actual) by default (BR-ATT-044)
  preceded_by_day_ot     boolean NOT NULL DEFAULT false,   -- night OT after day OT the same day (BR-ATT-042, VN-11)
  overtime_request_id    uuid,
  calc_version           int NOT NULL DEFAULT 1,
  legal_pack_id          uuid,                             -- -> saas.legal_param_pack (soft)
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT overtime_result_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT overtime_result_request_fk FOREIGN KEY (tenant_id, overtime_request_id) REFERENCES att.overtime_request (tenant_id, id),
  -- six OT categories (BR-ATT-041); NW (night work in normal hours) is not OT -> daily_timesheet.night_hours
  CONSTRAINT overtime_result_category_ck CHECK (ot_category IN ('OT-WD','OT-RD','OT-PH','OT-WD-N','OT-RD-N','OT-PH-N')),
  CONSTRAINT overtime_result_night_flag_ck CHECK (NOT preceded_by_day_ot OR ot_category LIKE '%-N'),
  CONSTRAINT overtime_result_minutes_ck CHECK (actual_minutes >= 0 AND approved_minutes >= 0 AND paid_minutes >= 0),
  CONSTRAINT overtime_result_uq UNIQUE (tenant_id, employment_id, work_date, ot_category)
);
CREATE INDEX overtime_result_scope_ix ON att.overtime_result (tenant_id, org_unit_id, work_date);
CREATE INDEX overtime_result_request_ix ON att.overtime_result (tenant_id, overtime_request_id);

-- OT cap ledger (BR-ATT-045..047/049, VN-07): per employment x month and x year.
-- P1 measures caps PER LEGAL ENTITY (R2-08, R2-16; an employment belongs to one LE).
-- Updated in the OT-approval transaction with SELECT ... FOR UPDATE on the row,
-- which serialises cap checks per employee (DESIGN §7.4). Opening YTD minutes from
-- the opening-balance import (BR-ATT-086, ADR-0037) go into opening_min.
CREATE TABLE att.ot_cap_ledger (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id      uuid NOT NULL,                        -- -> hr.employment (soft)
  legal_entity_id    uuid NOT NULL,                        -- -> core.legal_entity (soft)
  person_id          uuid,                                 -- -> hr.person (soft); P2 cross-LE cap (BR-ATT-047)
  cap_scope          text NOT NULL DEFAULT 'LEGAL_ENTITY',
  period_kind        text NOT NULL,
  period_start       date NOT NULL,                        -- 1st of month (MONTH) or 1 Jan (YEAR)
  regime_code        text NOT NULL DEFAULT 'STANDARD_200',
  cap_min            int NOT NULL,                         -- snapshot from legal pack (40h/month, 200h or 300h/year)
  opening_min        int NOT NULL DEFAULT 0,
  approved_min       int NOT NULL DEFAULT 0,
  pending_min        int NOT NULL DEFAULT 0,
  paid_min           int NOT NULL DEFAULT 0,               -- from overtime_result (actual paid)
  override_min       int NOT NULL DEFAULT 0,               -- minutes approved above cap by override
  legal_pack_id      uuid,                                 -- -> saas.legal_param_pack (soft)
  regime_notice_ref  text,                                 -- 300h notification to provincial labour authority (BR-ATT-049)
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT ot_cap_ledger_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ot_cap_ledger_uq UNIQUE (tenant_id, employment_id, period_kind, period_start),
  CONSTRAINT ot_cap_ledger_scope_ck CHECK (cap_scope IN ('LEGAL_ENTITY')),   -- 'PERSON' is P2 (BR-ATT-047, R2-08)
  CONSTRAINT ot_cap_ledger_kind_ck CHECK (period_kind IN ('MONTH','YEAR')),
  CONSTRAINT ot_cap_ledger_start_ck CHECK (
    (period_kind = 'MONTH' AND extract(day FROM period_start) = 1)
    OR (period_kind = 'YEAR' AND extract(day FROM period_start) = 1 AND extract(month FROM period_start) = 1)),
  CONSTRAINT ot_cap_ledger_regime_ck CHECK (regime_code IN ('STANDARD_200','EXTENDED_300')),
  CONSTRAINT ot_cap_ledger_values_ck CHECK (cap_min > 0 AND opening_min >= 0 AND approved_min >= 0
                                            AND pending_min >= 0 AND paid_min >= 0 AND override_min >= 0)
) WITH (fillfactor = 80, autovacuum_vacuum_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.01);
CREATE INDEX ot_cap_ledger_le_ix ON att.ot_cap_ledger (tenant_id, legal_entity_id, period_kind, period_start);

-- =============================================================================
-- 8. LEAVE  (BR-ATT-055..068; VN-12..VN-16)
--    Leave types are seeded per tenant at provisioning (DESIGN §12), not global.
-- =============================================================================
CREATE TABLE att.leave_type (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                      text NOT NULL,                 -- ANNUAL, MARRIAGE, SICK, CHILD_SICK, MATERNITY ...
  name_vi                   text NOT NULL,
  name_en                   text,
  leave_category            text NOT NULL,
  paid_by                   text NOT NULL,
  min_unit                  text NOT NULL DEFAULT 'DAY',
  counting_basis            text NOT NULL DEFAULT 'WORKING_DAY',   -- CALENDAR_DAY e.g. maternity (BR-ATT-061)
  tracks_balance            boolean NOT NULL DEFAULT false,
  requires_si_claim_data    boolean NOT NULL DEFAULT false,         -- BR-ATT-063
  requires_attachment       boolean NOT NULL DEFAULT false,
  max_days_per_request      sys.quantity,
  max_days_per_year         sys.quantity,
  counts_as_service         boolean NOT NULL DEFAULT true,          -- counts as working time for annual-leave service (BR-ATT-059)
  attendance_code           text NOT NULL,                          -- attendance_code.code (may be a global row: no FK)
  payroll_category          text NOT NULL,                          -- PAID_LEAVE_DAYS | SI_LEAVE_DAYS | UNPAID_DAYS
  eligibility               jsonb NOT NULL DEFAULT '{}'::jsonb,     -- {"gender":"F","min_service_months":0,"allow_probation":true,"employee_categories":[...]}
  is_statutory              boolean NOT NULL DEFAULT false,         -- seeded VN type (BR-ATT-056)
  status                    text NOT NULL DEFAULT 'ACTIVE',
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT leave_type_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT leave_type_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT leave_type_category_ck CHECK (leave_category IN
    ('ANNUAL','PERSONAL_PAID','PERSONAL_UNPAID','SICK','CHILD_SICK','MATERNITY','PATERNITY','PRENATAL',
     'MISCARRIAGE','WORK_ACCIDENT','UNPAID_AGREED','TOIL','COMPANY_PAID','OTHER')),
  CONSTRAINT leave_type_paid_by_ck CHECK (paid_by IN ('EMPLOYER','SI_FUND','UNPAID')),
  CONSTRAINT leave_type_unit_ck CHECK (min_unit IN ('DAY','HALF_DAY','HOUR')),
  CONSTRAINT leave_type_basis_ck CHECK (counting_basis IN ('WORKING_DAY','CALENDAR_DAY')),
  CONSTRAINT leave_type_category_map_ck CHECK (payroll_category IN ('PAID_LEAVE_DAYS','SI_LEAVE_DAYS','UNPAID_DAYS')),
  CONSTRAINT leave_type_eligibility_ck CHECK (jsonb_typeof(eligibility) = 'object'),
  CONSTRAINT leave_type_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- Entitlement / accrual / carry-over rules of a leave type, effective-dated (Pattern A).
-- Statutory floors (12/14/16 days, +1 per 5 years, ND145 proration) come from the
-- legal pack; the policy may grant more, never less (BR-ATT-057, validated by service).
CREATE TABLE att.leave_policy (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  leave_type_id             uuid NOT NULL,
  code                      text NOT NULL,
  legal_entity_id           uuid,                          -- -> core.legal_entity (soft); NULL = tenant-wide
  employee_category_code    text,                          -- NULL = all categories
  scope_key                 text GENERATED ALWAYS AS
                              (coalesce(legal_entity_id::text, '*') || '|' || coalesce(employee_category_code, '*')) STORED,
  accrual_method            text NOT NULL DEFAULT 'MONTHLY',
  base_entitlement_days     sys.quantity,                  -- NULL = legal pack floor by job flags
  seniority_step_years      smallint DEFAULT 5,
  seniority_step_days       sys.quantity DEFAULT 1,
  proration_rule            text NOT NULL DEFAULT 'ND145_HALF_MONTH',   -- BR-ATT-058
  leave_year_start_month    smallint NOT NULL DEFAULT 1,               -- BR-ATT-060
  carry_over_max_days       sys.quantity,
  carry_over_expiry_months  smallint,
  expiry_action             text NOT NULL DEFAULT 'FORFEIT',
  allow_negative_days       sys.quantity NOT NULL DEFAULT 0,           -- BR-ATT-062
  notice_days               smallint NOT NULL DEFAULT 0,
  valid_from                date NOT NULL,
  valid_to                  date,
  valid_during              daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  status                    text NOT NULL DEFAULT 'ACTIVE',
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT leave_policy_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT leave_policy_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT leave_policy_type_fk FOREIGN KEY (tenant_id, leave_type_id) REFERENCES att.leave_type (tenant_id, id),
  CONSTRAINT leave_policy_accrual_ck CHECK (accrual_method IN ('MONTHLY','ANNUAL_FRONT','PRORATA','NONE')),
  CONSTRAINT leave_policy_proration_ck CHECK (proration_rule IN ('ND145_HALF_MONTH','CALENDAR_DAYS','NONE')),
  CONSTRAINT leave_policy_year_ck CHECK (leave_year_start_month BETWEEN 1 AND 12),
  CONSTRAINT leave_policy_expiry_ck CHECK (expiry_action IN ('FORFEIT','ENCASH','FLAG')),
  CONSTRAINT leave_policy_values_ck CHECK (allow_negative_days >= 0 AND coalesce(base_entitlement_days, 0) >= 0),
  CONSTRAINT leave_policy_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT leave_policy_status_ck CHECK (status IN ('ACTIVE','INACTIVE')),
  CONSTRAINT leave_policy_ex EXCLUDE USING gist
    (tenant_id WITH =, leave_type_id WITH =, scope_key WITH =, valid_during WITH &&) WHERE (status = 'ACTIVE')
);

CREATE TABLE att.leave_request (
  tenant_id                   uuid NOT NULL,
  id                          uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id               uuid NOT NULL,               -- -> hr.employment (soft)
  person_id                   uuid NOT NULL,               -- -> hr.person (soft); subject DEK for the *_enc columns
  legal_entity_id             uuid NOT NULL,               -- -> core.legal_entity (soft), M9
  org_unit_id                 uuid,                        -- -> core.org_unit (soft), M9
  leave_type_id               uuid NOT NULL,               -- L4
  request_unit                text NOT NULL DEFAULT 'DAY',
  start_date                  date NOT NULL,
  end_date                    date NOT NULL,               -- INCLUSIVE
  start_part                  text NOT NULL DEFAULT 'FULL',
  end_part                    text NOT NULL DEFAULT 'FULL',
  start_time                  time,                        -- HOUR requests
  end_time                    time,
  -- half-day slot range for overlap checks: 2 slots per day (AM, PM), half-open
  slot_range                  int4range GENERATED ALWAYS AS (int4range(
                                (start_date - DATE '2000-01-01') * 2 + CASE start_part WHEN 'PM' THEN 1 ELSE 0 END,
                                (end_date   - DATE '2000-01-01') * 2 + CASE end_part   WHEN 'AM' THEN 1 ELSE 2 END)) STORED,
  duration_days               sys.quantity NOT NULL,       -- from schedule + holidays (BR-ATT-061)
  duration_hours              sys.quantity,
  reason                      text,
  status                      text NOT NULL DEFAULT 'DRAFT',
  workflow_instance_id        uuid,                        -- -> core.workflow_instance (soft)
  decided_at                  timestamptz,
  decided_by                  uuid,
  cancelled_at                timestamptz,
  cancelled_by                uuid,
  cancel_reason               text,
  actual_return_date          date,                        -- maternity early return (BR-ATT-065)
  attachment_document_ids     uuid[] NOT NULL DEFAULT '{}',-- -> core.document (soft)
  -- SI benefit-claim data (BR-ATT-063, minimal in P1 per R2-07; ATT-P-05). Health data = P3.
  si_certificate_type         text,                        -- code list SI_CERTIFICATE_TYPE (sick-leave certificate, discharge paper, birth certificate ...)
  si_certificate_no_enc       bytea,                       -- pii:P3
  si_certificate_no_last4     text,
  si_certificate_date         date,
  si_issuing_facility_enc     bytea,                       -- pii:P3 medical facility (code + name) reveals health information
  si_diagnosis_category_enc   bytea,                       -- pii:P3 health (diagnosis category / ICD group)
  si_is_long_term_illness     boolean,                     -- pii:P3-derived flag (health); needed for 180-day entitlement
  si_treatment_type           text,                        -- OUTPATIENT | INPATIENT (pii:P3-derived, minimal)
  child_birth_date            date,                        -- pii:P2 child sick / maternity
  child_id_enc                bytea,                       -- pii:P3 child personal id (mã định danh)
  child_id_last4              text,
  confinement_date            date,                        -- pii:P2 date of giving birth (ngày sinh con)
  children_born_count         smallint,                    -- pii:P2
  is_caesarean                boolean,                     -- pii:P3-derived flag (health)
  subject_key_version         int NOT NULL DEFAULT 1,      -- saas.subject_key (PERSON, person_id)
  created_at                  timestamptz NOT NULL DEFAULT now(),
  created_by                  uuid,
  updated_at                  timestamptz NOT NULL DEFAULT now(),
  updated_by                  uuid,
  version                     int NOT NULL DEFAULT 0,
  CONSTRAINT leave_request_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT leave_request_type_fk FOREIGN KEY (tenant_id, leave_type_id) REFERENCES att.leave_type (tenant_id, id),
  CONSTRAINT leave_request_unit_ck CHECK (request_unit IN ('DAY','HALF_DAY','HOUR')),
  CONSTRAINT leave_request_parts_ck CHECK (start_part IN ('FULL','AM','PM') AND end_part IN ('FULL','AM','PM')),
  CONSTRAINT leave_request_dates_ck CHECK (end_date >= start_date),
  CONSTRAINT leave_request_same_day_ck CHECK (start_date <> end_date OR NOT (start_part = 'PM' AND end_part = 'AM')),
  CONSTRAINT leave_request_hours_ck CHECK ((request_unit = 'HOUR') = (start_time IS NOT NULL AND end_time IS NOT NULL)),
  CONSTRAINT leave_request_hour_day_ck CHECK (request_unit <> 'HOUR' OR (start_date = end_date AND end_time > start_time)),
  CONSTRAINT leave_request_duration_ck CHECK (duration_days >= 0 AND coalesce(duration_hours, 0) >= 0),
  CONSTRAINT leave_request_status_ck CHECK (status IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELLED','WITHDRAWN')),
  CONSTRAINT leave_request_treatment_ck CHECK (si_treatment_type IS NULL OR si_treatment_type IN ('OUTPATIENT','INPATIENT')),
  CONSTRAINT leave_request_last4_ck CHECK (coalesce(length(si_certificate_no_last4), 0) <= 4 AND coalesce(length(child_id_last4), 0) <= 4),
  -- no overlapping day/half-day leave of one employment (BR-ATT-062); hourly leave is checked by the service
  CONSTRAINT leave_request_overlap_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, slot_range WITH &&)
    WHERE (status IN ('PENDING','APPROVED') AND request_unit <> 'HOUR')
);
CREATE INDEX leave_request_employment_ix ON att.leave_request (tenant_id, employment_id, start_date);
CREATE INDEX leave_request_scope_ix ON att.leave_request (tenant_id, org_unit_id, start_date);
CREATE INDEX leave_request_type_ix ON att.leave_request (tenant_id, leave_type_id);
COMMENT ON COLUMN att.leave_request.si_certificate_no_enc     IS 'pii:P3 AES-256-GCM, subject DEK (BR-ATT-063)';
COMMENT ON COLUMN att.leave_request.si_issuing_facility_enc   IS 'pii:P3 health-revealing (BR-ATT-063)';
COMMENT ON COLUMN att.leave_request.si_diagnosis_category_enc IS 'pii:P3 health data (BR-ATT-063, ND13/2023 sensitive)';
COMMENT ON COLUMN att.leave_request.si_is_long_term_illness   IS 'pii:P3-derived health flag, minimal';
COMMENT ON COLUMN att.leave_request.is_caesarean              IS 'pii:P3-derived health flag, minimal';
COMMENT ON COLUMN att.leave_request.child_id_enc              IS 'pii:P3 AES-256-GCM, subject DEK';
COMMENT ON COLUMN att.leave_request.child_birth_date          IS 'pii:P2';
COMMENT ON COLUMN att.leave_request.confinement_date          IS 'pii:P2';

-- Every balance movement is a ledger row (BR-ATT-068). Append-only. Not partitioned
-- (opening balances land here, DESIGN §10.2). Balance = sum(quantity) per
-- employment x leave type x leave year; amounts reserved by PENDING requests are
-- derived from leave_request, not stored (BR-ATT-062).
CREATE TABLE att.leave_balance_ledger (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id      uuid NOT NULL,                        -- -> hr.employment (soft)
  legal_entity_id    uuid NOT NULL,                        -- -> core.legal_entity (soft), M9
  org_unit_id        uuid,                                 -- -> core.org_unit (soft), M9
  leave_type_id      uuid NOT NULL,                        -- L4
  leave_type_code    text NOT NULL,                        -- denormalised for import reconciliation / reporting
  leave_year         smallint NOT NULL,
  txn_type           text NOT NULL,
  quantity           sys.quantity NOT NULL,                -- signed
  unit               text NOT NULL DEFAULT 'DAY',
  txn_date           date NOT NULL,                        -- OPENING: go-live date G (ADR-0037)
  accrual_month      sys.month_date,                       -- ACCRUE idempotency
  expires_on         date,                                 -- carried days expiry (BR-ATT-060)
  leave_request_id   uuid,
  ref_type           text,                                 -- LEAVE_REQUEST | ACCRUAL_RUN | CARRY_RUN | TERMINATION | IMPORT | ADMIN
  ref_id             uuid,
  reason             text,
  import_job_id      uuid,                                 -- -> core.import_job (soft); OB_LEAVE_BALANCE
  source             text NOT NULL DEFAULT 'SYSTEM',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  CONSTRAINT leave_balance_ledger_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT leave_balance_ledger_type_fk FOREIGN KEY (tenant_id, leave_type_id) REFERENCES att.leave_type (tenant_id, id),
  CONSTRAINT leave_balance_ledger_request_fk FOREIGN KEY (tenant_id, leave_request_id) REFERENCES att.leave_request (tenant_id, id),
  CONSTRAINT leave_balance_ledger_txn_ck CHECK (txn_type IN
    ('OPENING','ACCRUE','TAKE','CANCEL','CARRY','EXPIRE','ENCASH','ADJUST')),
  CONSTRAINT leave_balance_ledger_sign_ck CHECK (
       (txn_type = 'ACCRUE' AND quantity >= 0)
    OR (txn_type = 'TAKE'   AND quantity < 0)
    OR (txn_type = 'CANCEL' AND quantity > 0)
    OR (txn_type IN ('EXPIRE','ENCASH') AND quantity < 0)
    OR  txn_type IN ('OPENING','CARRY','ADJUST')),            -- CARRY: -n out of year N, +n into N+1
  CONSTRAINT leave_balance_ledger_unit_ck CHECK (unit IN ('DAY','HOUR')),
  CONSTRAINT leave_balance_ledger_year_ck CHECK (leave_year BETWEEN 2000 AND 2100),
  CONSTRAINT leave_balance_ledger_source_ck CHECK (source IN ('SYSTEM','APP','IMPORT','OPENING_LOAD')),
  CONSTRAINT leave_balance_ledger_opening_ck CHECK (txn_type <> 'OPENING' OR source = 'OPENING_LOAD'),
  CONSTRAINT leave_balance_ledger_adjust_ck CHECK (txn_type <> 'ADJUST' OR reason IS NOT NULL),       -- BR-ATT-068
  CONSTRAINT leave_balance_ledger_request_ck CHECK (txn_type NOT IN ('TAKE','CANCEL') OR leave_request_id IS NOT NULL)
);
CREATE INDEX leave_balance_ledger_balance_ix ON att.leave_balance_ledger (tenant_id, employment_id, leave_type_id, leave_year);
CREATE INDEX leave_balance_ledger_scope_ix ON att.leave_balance_ledger (tenant_id, org_unit_id, leave_year);
CREATE INDEX leave_balance_ledger_request_ix ON att.leave_balance_ledger (tenant_id, leave_request_id) WHERE leave_request_id IS NOT NULL;
-- one opening row per employment x leave type x leave year (DESIGN §7.4, OB_LEAVE_BALANCE)
CREATE UNIQUE INDEX leave_balance_ledger_opening_uq ON att.leave_balance_ledger (tenant_id, employment_id, leave_type_id, leave_year)
  WHERE txn_type = 'OPENING';
-- accrual runs are idempotent per month
CREATE UNIQUE INDEX leave_balance_ledger_accrue_uq ON att.leave_balance_ledger (tenant_id, employment_id, leave_type_id, accrual_month)
  WHERE txn_type = 'ACCRUE';
CREATE TRIGGER trg_immutable BEFORE UPDATE OR DELETE ON att.leave_balance_ledger
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();

-- -----------------------------------------------------------------------------
-- 8a. BUSINESS TRIP and REMOTE WORK  -- P2 (BR-ATT-070 is P2 per R2-19).
--     In P1 trips and WFH are attendance codes (CT, WFH) on the daily timesheet.
-- -----------------------------------------------------------------------------
CREATE TABLE att.business_trip (                           -- P2
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id          uuid NOT NULL,                    -- -> hr.employment (soft)
  legal_entity_id        uuid NOT NULL,                    -- -> core.legal_entity (soft)
  org_unit_id            uuid,                             -- -> core.org_unit (soft)
  start_date             date NOT NULL,
  end_date               date NOT NULL,                    -- INCLUSIVE
  start_part             text NOT NULL DEFAULT 'FULL',
  end_part               text NOT NULL DEFAULT 'FULL',
  destination            text NOT NULL,
  is_overseas            boolean NOT NULL DEFAULT false,
  country_code           char(2),
  purpose                text,
  cost_centre_id         uuid,                             -- -> core.cost_centre (soft)
  project_code           text,
  status                 text NOT NULL DEFAULT 'DRAFT',
  workflow_instance_id   uuid,                             -- -> core.workflow_instance (soft)
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT business_trip_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT business_trip_dates_ck CHECK (end_date >= start_date),
  CONSTRAINT business_trip_parts_ck CHECK (start_part IN ('FULL','AM','PM') AND end_part IN ('FULL','AM','PM')),
  CONSTRAINT business_trip_status_ck CHECK (status IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELLED'))
);
CREATE INDEX business_trip_employment_ix ON att.business_trip (tenant_id, employment_id, start_date);

CREATE TABLE att.remote_work_request (                     -- P2
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id          uuid NOT NULL,                    -- -> hr.employment (soft)
  legal_entity_id        uuid NOT NULL,                    -- -> core.legal_entity (soft)
  org_unit_id            uuid,                             -- -> core.org_unit (soft)
  start_date             date NOT NULL,
  end_date               date NOT NULL,                    -- INCLUSIVE
  start_part             text NOT NULL DEFAULT 'FULL',
  end_part               text NOT NULL DEFAULT 'FULL',
  reason                 text,
  status                 text NOT NULL DEFAULT 'DRAFT',
  workflow_instance_id   uuid,                             -- -> core.workflow_instance (soft)
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT remote_work_request_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT remote_work_request_dates_ck CHECK (end_date >= start_date),
  CONSTRAINT remote_work_request_parts_ck CHECK (start_part IN ('FULL','AM','PM') AND end_part IN ('FULL','AM','PM')),
  CONSTRAINT remote_work_request_status_ck CHECK (status IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELLED'))
);
CREATE INDEX remote_work_request_employment_ix ON att.remote_work_request (tenant_id, employment_id, start_date);

-- =============================================================================
-- 9. ATTENDANCE PERIOD, RESULT, HOLD  (BR-ATT-071..079, 087, 088; gap §7; ADR-0019)
--
--  Period (per legal entity x pay group, from pay.PayCalendarPublished, BR-ATT-072):
--    OPEN -> IN_REVIEW -> CLOSED -> LOCKED;  CLOSED -> OPEN (reopen, only without an
--    ACTIVE PERIOD-scope hold);  LOCKED -> CLOSED (only via payroll unlock).
--    Enforced by att.tg_attendance_period_guard (state machine + reopen guard).
--
--  Result versions are immutable once committed (DESIGN principle 4; NFR-ATT-007):
--    scope PERIOD     : the period result; result_version = attendance_period.current_version
--    scope EMPLOYMENT : early close of ONE employment (leaver, BR-ATT-075, R3-06,
--                       gap §7.3 step 2) or off-cycle retro result for a leaver
--                       without open period (BRD §7.3 rule c). Review fix H3.
--    Lines are inserted in the same transaction as the header; at COMMIT a deferred
--    trigger stamps sealed_at + line_count, after which no line can be added.
--    status: PUBLISHED -> WITHDRAWN (reopen / re-close) | PUBLISHED -> LOCKED (run
--    APPROVED) | LOCKED -> PUBLISHED (payroll unlock only, unlocked_* stamped).
--    Content columns never change (att.tg_attendance_result_guard).
--
--  Hold (close hold, BR-ATT-079): at most ONE ACTIVE hold per period x scope
--    (PERIOD, or each EMPLOYMENT); never expires; released/consumed only by Payroll
--    actions. Acquire = INSERT, guarded by att.tg_attendance_result_hold_guard
--    (period CLOSED + current version for PERIOD scope; result PUBLISHED).
--    D1/D10 (2026-10-21): this table is the ONLY authority for hold state. Payroll
--    keeps a mirror (pay.run_attendance_version, one row per att hold id) updated
--    from the synchronous T&A API responses and events; the mirror never enforces
--    hold uniqueness itself. A payroll run CANCELLED (D3) releases its ACTIVE holds
--    with release_reason CANCELLED through the T&A API before the run status changes.
-- =============================================================================
CREATE TABLE att.attendance_period (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id      uuid NOT NULL,                      -- -> core.legal_entity (soft)
  pay_group_id         uuid NOT NULL,                      -- -> pay.pay_group (soft)
  pay_period_id        uuid,                               -- -> pay.pay_period (soft), calendar source
  period_start         date NOT NULL,
  period_end           date NOT NULL,                      -- INCLUSIVE (e.g. 26th -> 25th)
  period_range         daterange GENERATED ALWAYS AS (daterange(period_start, period_end, '[]')) STORED,
  ot_period_start      date,                               -- separate OT cut-off (BR-ATT-072, BRD §7.3)
  ot_period_end        date,
  std_day_method       text,                               -- copied from pay calendar (BR-ATT-016, ATT-C-18)
  close_deadline       date,                               -- T&A close deadline (D + 2 working days)
  status               text NOT NULL DEFAULT 'OPEN',
  current_version      int NOT NULL DEFAULT 0,             -- latest PERIOD-scope result version (0 = none yet)
  locked_version       int,                                -- version consumed by the APPROVED run
  closed_at            timestamptz,
  closed_by            uuid,
  reopened_at          timestamptz,
  reopened_by          uuid,
  reopen_reason        text,
  locked_at            timestamptz,
  locked_by_run_id     uuid,                               -- -> pay.payroll_run (soft)
  source_event_id      uuid,                               -- pay.PayCalendarPublished event id
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT attendance_period_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT attendance_period_start_uq UNIQUE (tenant_id, id, period_start),        -- FK target carrying period_start
  CONSTRAINT attendance_period_uq UNIQUE (tenant_id, legal_entity_id, pay_group_id, period_start),
  CONSTRAINT attendance_period_dates_ck CHECK (period_end >= period_start),
  CONSTRAINT attendance_period_ot_ck CHECK ((ot_period_start IS NULL) = (ot_period_end IS NULL)
                                            AND (ot_period_end IS NULL OR ot_period_end >= ot_period_start)),
  CONSTRAINT attendance_period_status_ck CHECK (status IN ('OPEN','IN_REVIEW','CLOSED','LOCKED')),
  CONSTRAINT attendance_period_version_ck CHECK (current_version >= 0
                                                 AND (locked_version IS NULL OR locked_version BETWEEN 1 AND current_version)),
  CONSTRAINT attendance_period_closed_ck CHECK (status NOT IN ('CLOSED','LOCKED') OR current_version >= 1),
  CONSTRAINT attendance_period_locked_ck CHECK (status <> 'LOCKED' OR locked_version = current_version),
  CONSTRAINT attendance_period_ex EXCLUDE USING gist
    (tenant_id WITH =, legal_entity_id WITH =, pay_group_id WITH =, period_range WITH &&)
);
CREATE INDEX attendance_period_status_ix ON att.attendance_period (tenant_id, status, period_start);

CREATE TABLE att.attendance_result (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  period_id            uuid NOT NULL,
  period_start         date NOT NULL,                      -- = attendance_period.period_start (enforced by FK)
  scope                text NOT NULL DEFAULT 'PERIOD',     -- H3
  employment_id        uuid,                               -- -> hr.employment (soft); EMPLOYMENT scope only
  scope_key            uuid GENERATED ALWAYS AS (coalesce(employment_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
  result_kind          text NOT NULL DEFAULT 'REGULAR',
  result_version       int NOT NULL,                       -- 1, 2, ... per (period, scope_key)
  source               text NOT NULL DEFAULT 'CALCULATION',
  import_job_id        uuid,                               -- -> core.import_job (soft); Excel attendance import (BR-ATT-025, R2-18)
  status               text NOT NULL DEFAULT 'PUBLISHED',
  att_date_from        date NOT NULL,
  att_date_to          date NOT NULL,
  ot_date_from         date,
  ot_date_to           date,
  checksum_sha256      text NOT NULL,                      -- over the canonical line set (att.AttendancePeriodClosed)
  legal_pack_id        uuid,                               -- -> saas.legal_param_pack (soft), NFR-ATT-006
  rule_version         text,
  override_version     int NOT NULL DEFAULT 0,             -- number of manual overrides folded into this version
  supersedes_result_id uuid,                               -- previous version of the same scope
  close_cause          text NOT NULL DEFAULT 'CLOSE',      -- CLOSE | RECLOSE | EARLY_CLOSE | IMPORT | RETRO
  line_count           int,                                -- stamped at commit (seal)
  sealed_at            timestamptz,                        -- stamped at commit; no line insert afterwards
  published_at         timestamptz NOT NULL DEFAULT now(),
  published_by         uuid,
  withdrawn_at         timestamptz,
  withdrawn_by         uuid,
  withdraw_reason      text,
  locked_at            timestamptz,
  locked_by_run_id     uuid,                               -- -> pay.payroll_run (soft)
  unlocked_at          timestamptz,
  unlocked_by_run_id   uuid,                               -- -> pay.payroll_run (soft)
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT attendance_result_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT attendance_result_line_target_uq UNIQUE (tenant_id, id, period_start),     -- FK target for lines (L5)
  CONSTRAINT attendance_result_hold_target_uq UNIQUE (tenant_id, id, period_id, scope_key),  -- FK target for holds (H3)
  CONSTRAINT attendance_result_period_fk FOREIGN KEY (tenant_id, period_id, period_start)
    REFERENCES att.attendance_period (tenant_id, id, period_start),
  CONSTRAINT attendance_result_supersedes_fk FOREIGN KEY (tenant_id, supersedes_result_id)
    REFERENCES att.attendance_result (tenant_id, id),
  CONSTRAINT attendance_result_version_uq UNIQUE (tenant_id, period_id, scope_key, result_version),
  CONSTRAINT attendance_result_scope_ck CHECK (scope IN ('PERIOD','EMPLOYMENT')),
  CONSTRAINT attendance_result_scope_emp_ck CHECK ((scope = 'EMPLOYMENT') = (employment_id IS NOT NULL)),
  CONSTRAINT attendance_result_kind_ck CHECK (result_kind IN ('REGULAR','EARLY_CLOSE','OFF_CYCLE_RETRO')),
  CONSTRAINT attendance_result_kind_scope_ck CHECK ((result_kind = 'REGULAR') = (scope = 'PERIOD')),
  CONSTRAINT attendance_result_source_ck CHECK (source IN ('CALCULATION','IMPORT')),
  CONSTRAINT attendance_result_import_ck CHECK (source <> 'IMPORT' OR import_job_id IS NOT NULL),
  CONSTRAINT attendance_result_status_ck CHECK (status IN ('PUBLISHED','WITHDRAWN','LOCKED')),
  CONSTRAINT attendance_result_cause_ck CHECK (close_cause IN ('CLOSE','RECLOSE','EARLY_CLOSE','IMPORT','RETRO','UNLOCK')),
  CONSTRAINT attendance_result_version_ck CHECK (result_version >= 1 AND override_version >= 0),
  CONSTRAINT attendance_result_dates_ck CHECK (att_date_to >= att_date_from
                                               AND (ot_date_from IS NULL) = (ot_date_to IS NULL)),
  CONSTRAINT attendance_result_checksum_ck CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT attendance_result_withdrawn_ck CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL)),
  CONSTRAINT attendance_result_locked_ck CHECK (status <> 'LOCKED' OR (locked_at IS NOT NULL AND locked_by_run_id IS NOT NULL))
);
-- one live (PUBLISHED or LOCKED) regular / early-close version per period x scope;
-- off-cycle retro results for a leaver may accumulate (one per retro round)
CREATE UNIQUE INDEX attendance_result_live_uq ON att.attendance_result (tenant_id, period_id, scope_key)
  WHERE status IN ('PUBLISHED','LOCKED') AND result_kind <> 'OFF_CYCLE_RETRO';
CREATE INDEX attendance_result_employment_ix ON att.attendance_result (tenant_id, employment_id) WHERE employment_id IS NOT NULL;
COMMENT ON TABLE att.attendance_result IS
  'Attendance Result header (BR-ATT-076, ATT-P-01). Immutable version; scope PERIOD or EMPLOYMENT (early close of a leaver, H3/R3-06).';

-- Result lines (BRD §7.3). Append-only; monthly partitions on period_start (L5,
-- DESIGN §10.1: 10 years, narrow rows). period_start is a real column so that the
-- partition key is part of the FK to the header.
CREATE TABLE att.attendance_result_line (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  result_id              uuid NOT NULL,
  period_start           date NOT NULL,                    -- partition key (L5) = header period_start
  employment_id          uuid NOT NULL,                    -- -> hr.employment (soft)
  assignment_key         uuid,                             -- -> hr.assignment.assignment_key (soft); segment
  segment_from           date NOT NULL,                    -- assignment segment inside the period (BRD §7.3)
  segment_to             date NOT NULL,                    -- INCLUSIVE
  legal_entity_id        uuid NOT NULL,                    -- -> core.legal_entity (soft), M9
  org_unit_id            uuid,                             -- -> core.org_unit (soft), M9
  pay_group_id           uuid,                             -- -> pay.pay_group (soft)
  cost_centre_id         uuid,                             -- -> core.cost_centre (soft)
  category_code          text NOT NULL,                    -- STD_DAYS | WORKED_DAYS | PAID_LEAVE_DAYS.ANNUAL | OT_HOURS.OT-WD-N | RETRO.* ...
  quantity               numeric(12,2) NOT NULL,           -- 2 decimals (BRD §7.3); signed for retro deltas
  unit                   text NOT NULL,
  period_quantity        numeric(12,2),                    -- full-period value for proration (STD_DAYS / STD_HOURS)
  preceded_by_day_ot     boolean NOT NULL DEFAULT false,   -- OT_HOURS.*-N sub-flag (VN-11, BR-ATT-042)
  is_retro               boolean NOT NULL DEFAULT false,
  original_period_id     uuid,                             -- retro: period the delta belongs to (BR-ATT-071)
  original_result_id     uuid,                             -- retro: locked version the delta corrects
  original_segment_from  date,                             -- retro: original segment (BRD §7.3 rule d)
  retro_reason           text,
  source_ref             jsonb,                            -- source documents (leave request id, correction id ...)
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  CONSTRAINT attendance_result_line_pk PRIMARY KEY (tenant_id, id, period_start),
  CONSTRAINT attendance_result_line_result_fk FOREIGN KEY (tenant_id, result_id, period_start)
    REFERENCES att.attendance_result (tenant_id, id, period_start),
  CONSTRAINT attendance_result_line_orig_period_fk FOREIGN KEY (tenant_id, original_period_id)
    REFERENCES att.attendance_period (tenant_id, id),
  CONSTRAINT attendance_result_line_orig_result_fk FOREIGN KEY (tenant_id, original_result_id)
    REFERENCES att.attendance_result (tenant_id, id),
  CONSTRAINT attendance_result_line_category_ck CHECK (category_code ~ '^[A-Z][A-Z_]*(\.[A-Za-z0-9_-]+)*$'),
  CONSTRAINT attendance_result_line_unit_ck CHECK (unit IN ('DAY','HOUR','MINUTE','COUNT')),
  CONSTRAINT attendance_result_line_segment_ck CHECK (segment_to >= segment_from),
  CONSTRAINT attendance_result_line_retro_ck CHECK (is_retro = (original_period_id IS NOT NULL)),
  CONSTRAINT attendance_result_line_retro_reason_ck CHECK (NOT is_retro OR retro_reason IS NOT NULL),
  CONSTRAINT attendance_result_line_sign_ck CHECK (is_retro OR quantity >= 0),
  CONSTRAINT attendance_result_line_night_ck CHECK (NOT preceded_by_day_ot OR category_code LIKE 'OT_HOURS.%-N'),
  CONSTRAINT attendance_result_line_uq UNIQUE NULLS NOT DISTINCT
    (tenant_id, result_id, employment_id, segment_from, category_code, preceded_by_day_ot,
     original_period_id, original_segment_from, period_start)
) PARTITION BY RANGE (period_start);
CREATE INDEX attendance_result_line_result_ix ON att.attendance_result_line (tenant_id, result_id);
CREATE INDEX attendance_result_line_employment_ix ON att.attendance_result_line (tenant_id, employment_id, period_start);
CREATE INDEX attendance_result_line_scope_ix ON att.attendance_result_line (tenant_id, org_unit_id, period_start);
CREATE INDEX attendance_result_line_retro_ix ON att.attendance_result_line (tenant_id, original_period_id) WHERE is_retro;

CREATE TABLE att.attendance_result_hold (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  period_id            uuid NOT NULL,
  result_id            uuid NOT NULL,
  result_version       int NOT NULL,
  scope                text NOT NULL DEFAULT 'PERIOD',     -- H3: must match the held result
  employment_id        uuid,                               -- -> hr.employment (soft); EMPLOYMENT scope only
  scope_key            uuid GENERATED ALWAYS AS (coalesce(employment_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
  payroll_run_id       uuid NOT NULL,                      -- -> pay.payroll_run (soft); idempotency with result_version
  status               text NOT NULL DEFAULT 'ACTIVE',
  held_at              timestamptz NOT NULL DEFAULT now(),
  released_at          timestamptz,
  released_by          uuid,
  release_reason       text,
  consumed_at          timestamptz,
  correlation_id       text,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT attendance_result_hold_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT attendance_result_hold_period_fk FOREIGN KEY (tenant_id, period_id) REFERENCES att.attendance_period (tenant_id, id),
  -- the hold's period and scope must be those of the held result
  CONSTRAINT attendance_result_hold_result_fk FOREIGN KEY (tenant_id, result_id, period_id, scope_key)
    REFERENCES att.attendance_result (tenant_id, id, period_id, scope_key),
  CONSTRAINT attendance_result_hold_scope_ck CHECK (scope IN ('PERIOD','EMPLOYMENT')),
  CONSTRAINT attendance_result_hold_scope_emp_ck CHECK ((scope = 'EMPLOYMENT') = (employment_id IS NOT NULL)),
  CONSTRAINT attendance_result_hold_status_ck CHECK (status IN ('ACTIVE','RELEASED','CONSUMED')),
  CONSTRAINT attendance_result_hold_reason_ck CHECK (
    (status = 'RELEASED') = (release_reason IS NOT NULL)
    AND (release_reason IS NULL OR release_reason IN ('REJECTED','WITHDRAWN','CANCELLED','REOPEN_REQUESTED','UNLOCKED'))),
  CONSTRAINT attendance_result_hold_released_ck CHECK ((status = 'RELEASED') = (released_at IS NOT NULL)),
  CONSTRAINT attendance_result_hold_consumed_ck CHECK (
    (consumed_at IS NOT NULL) = (status = 'CONSUMED' OR coalesce(release_reason, '') = 'UNLOCKED'))
);
-- H3: at most one ACTIVE hold per period x scope (PERIOD, or per EMPLOYMENT for early close)
CREATE UNIQUE INDEX attendance_result_hold_active_uq ON att.attendance_result_hold
  (tenant_id, period_id, (coalesce(employment_id, '00000000-0000-0000-0000-000000000000'::uuid)))
  WHERE status = 'ACTIVE';
CREATE INDEX attendance_result_hold_run_ix ON att.attendance_result_hold (tenant_id, payroll_run_id);
CREATE INDEX attendance_result_hold_result_ix ON att.attendance_result_hold (tenant_id, result_id);
COMMENT ON TABLE att.attendance_result_hold IS
  'Close hold (BR-ATT-079, ADR-0019, gap §7.1). Never expires; released (REJECTED/WITHDRAWN/CANCELLED/REOPEN_REQUESTED/UNLOCKED) '
  'or consumed (run APPROVED -> LOCKED) only by Payroll actions. UNLOCKED releases a hold that had been consumed.';

-- -----------------------------------------------------------------------------
-- 9a. Guards
-- -----------------------------------------------------------------------------

-- Period state machine + reopen guard (BR-ATT-073/079/087/088, gap §7.1 invariant 3).
-- The UPDATE holds the period row lock, and hold acquisition takes FOR SHARE on the
-- same row (att.tg_attendance_result_hold_guard), so reopen and acquire serialise.
CREATE OR REPLACE FUNCTION att.tg_attendance_period_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF sys.purge_bypass(OLD.tenant_id) THEN
      RETURN OLD;                                          -- D9 tenant purge
    END IF;
    IF OLD.status <> 'OPEN' OR OLD.current_version <> 0 THEN
      RAISE EXCEPTION 'attendance period % (%): only an OPEN period without results may be deleted', OLD.id, OLD.status
        USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;

  IF NEW.period_start IS DISTINCT FROM OLD.period_start OR NEW.period_end IS DISTINCT FROM OLD.period_end
     OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id OR NEW.pay_group_id IS DISTINCT FROM OLD.pay_group_id THEN
    IF OLD.status <> 'OPEN' OR OLD.current_version <> 0 THEN
      RAISE EXCEPTION 'attendance period %: boundaries are immutable once a result exists', OLD.id USING ERRCODE = '55000';
    END IF;
  END IF;

  -- current_version moves only when entering CLOSED (close / re-close), never backwards
  IF NEW.current_version IS DISTINCT FROM OLD.current_version THEN
    IF NOT (NEW.status = 'CLOSED' AND OLD.status IN ('OPEN','IN_REVIEW') AND NEW.current_version > OLD.current_version) THEN
      RAISE EXCEPTION 'attendance period %: current_version may only increase on close (% -> %)', OLD.id, OLD.status, NEW.status
        USING ERRCODE = '55000';
    END IF;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT ((OLD.status, NEW.status) IN (('OPEN','IN_REVIEW'), ('IN_REVIEW','OPEN'), ('OPEN','CLOSED'), ('IN_REVIEW','CLOSED'),
                                         ('CLOSED','OPEN'), ('CLOSED','LOCKED'), ('LOCKED','CLOSED'))) THEN
      RAISE EXCEPTION 'attendance period %: illegal transition % -> % (BR-ATT-073)', OLD.id, OLD.status, NEW.status
        USING ERRCODE = '55000';
    END IF;
    -- reopen guard: a held period never reopens (BR-ATT-079/087); Payroll must release first
    IF OLD.status = 'CLOSED' AND NEW.status = 'OPEN' THEN
      IF EXISTS (SELECT 1 FROM att.attendance_result_hold h
                  WHERE h.tenant_id = OLD.tenant_id AND h.period_id = OLD.id
                    AND h.status = 'ACTIVE' AND h.scope = 'PERIOD') THEN
        RAISE EXCEPTION 'attendance period % is held by payroll (ACTIVE close hold): reopen refused, request a release (BR-ATT-087)', OLD.id
          USING ERRCODE = '55006';
      END IF;
      IF NEW.reopen_reason IS NULL THEN
        RAISE EXCEPTION 'attendance period %: reopen requires a reason (BR-ATT-073)', OLD.id USING ERRCODE = '23514';
      END IF;
    END IF;
  ELSIF OLD.status = 'LOCKED' AND NEW.locked_version IS DISTINCT FROM OLD.locked_version THEN
    RAISE EXCEPTION 'attendance period %: locked_version is frozen while LOCKED', OLD.id USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_period_guard BEFORE UPDATE OR DELETE ON att.attendance_period
  FOR EACH ROW EXECUTE FUNCTION att.tg_attendance_period_guard();

-- Result immutability (DESIGN principle 4, §7.4; NFR-ATT-006/007; BR-ATT-071/076/088).
CREATE OR REPLACE FUNCTION att.tg_attendance_result_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  -- lifecycle columns; everything else is content and immutable. scope_key is a
  -- STORED GENERATED column (NULL in NEW inside BEFORE triggers) derived from employment_id.
  lifecycle constant text[] := ARRAY['status','withdrawn_at','withdrawn_by','withdraw_reason',
                                     'locked_at','locked_by_run_id','unlocked_at','unlocked_by_run_id',
                                     'line_count','sealed_at','updated_at','scope_key'];
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'PUBLISHED' OR NEW.sealed_at IS NOT NULL OR NEW.line_count IS NOT NULL THEN
      RAISE EXCEPTION 'att.attendance_result is created PUBLISHED and unsealed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  IF TG_OP = 'DELETE' THEN
    IF sys.purge_bypass(OLD.tenant_id) THEN
      RETURN OLD;                                          -- D9 tenant purge
    END IF;
    RAISE EXCEPTION 'att.attendance_result is immutable: versions are withdrawn, never deleted' USING ERRCODE = '55000';
  END IF;
  -- the commit-time seal (att.tg_attendance_result_seal) only stamps sealed_at + line_count, once
  IF OLD.sealed_at IS NULL
     AND (to_jsonb(NEW) - 'sealed_at' - 'line_count' - 'updated_at' - 'scope_key')
         IS NOT DISTINCT FROM (to_jsonb(OLD) - 'sealed_at' - 'line_count' - 'updated_at' - 'scope_key') THEN
    NEW.updated_at := now();
    RETURN NEW;
  END IF;
  IF (to_jsonb(NEW) - lifecycle) IS DISTINCT FROM (to_jsonb(OLD) - lifecycle) THEN
    RAISE EXCEPTION 'att.attendance_result % (v%): content is immutable; publish a new version instead', OLD.id, OLD.result_version
      USING ERRCODE = '55000';
  END IF;
  -- seal stamp: once, from NULL (deferred trigger at commit)
  IF NEW.sealed_at IS DISTINCT FROM OLD.sealed_at OR NEW.line_count IS DISTINCT FROM OLD.line_count THEN
    IF OLD.sealed_at IS NOT NULL THEN
      RAISE EXCEPTION 'att.attendance_result %: already sealed', OLD.id USING ERRCODE = '55000';
    END IF;
  END IF;
  IF OLD.status = 'WITHDRAWN' AND (to_jsonb(NEW) - 'updated_at' - 'scope_key') IS DISTINCT FROM (to_jsonb(OLD) - 'updated_at' - 'scope_key') THEN
    RAISE EXCEPTION 'att.attendance_result % is WITHDRAWN (terminal)', OLD.id USING ERRCODE = '55000';
  END IF;
  IF OLD.status = 'LOCKED' THEN
    -- the only change to a LOCKED version is payroll unlock (BR-ATT-088): LOCKED -> PUBLISHED, same version
    IF NOT (NEW.status = 'PUBLISHED' AND NEW.unlocked_at IS NOT NULL AND NEW.unlocked_by_run_id IS NOT NULL
            AND NEW.unlocked_at IS DISTINCT FROM OLD.unlocked_at
            AND NEW.locked_at IS NOT DISTINCT FROM OLD.locked_at
            AND NEW.locked_by_run_id IS NOT DISTINCT FROM OLD.locked_by_run_id) THEN
      RAISE EXCEPTION 'att.attendance_result % (v%) is LOCKED: immutable except payroll unlock (BR-ATT-071/088)', OLD.id, OLD.result_version
        USING ERRCODE = '55000';
    END IF;
  ELSIF NEW.status IS DISTINCT FROM OLD.status
        AND NOT ((OLD.status, NEW.status) IN (('PUBLISHED','WITHDRAWN'), ('PUBLISHED','LOCKED'))) THEN
    RAISE EXCEPTION 'att.attendance_result %: illegal transition % -> %', OLD.id, OLD.status, NEW.status USING ERRCODE = '55000';
  END IF;
  -- a held version cannot be withdrawn (BR-ATT-079: no in-place change while held)
  IF OLD.status = 'PUBLISHED' AND NEW.status = 'WITHDRAWN'
     AND EXISTS (SELECT 1 FROM att.attendance_result_hold h
                  WHERE h.tenant_id = OLD.tenant_id AND h.result_id = OLD.id AND h.status = 'ACTIVE') THEN
    RAISE EXCEPTION 'att.attendance_result % is held by payroll: withdraw refused (BR-ATT-079)', OLD.id USING ERRCODE = '55006';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER trg_result_guard BEFORE INSERT OR UPDATE OR DELETE ON att.attendance_result
  FOR EACH ROW EXECUTE FUNCTION att.tg_attendance_result_guard();

-- Seal at COMMIT: stamps line_count + sealed_at; afterwards lines are rejected.
CREATE OR REPLACE FUNCTION att.tg_attendance_result_seal()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE att.attendance_result r
     SET sealed_at  = clock_timestamp(),
         line_count = (SELECT count(*) FROM att.attendance_result_line l
                        WHERE l.tenant_id = NEW.tenant_id AND l.result_id = NEW.id AND l.period_start = NEW.period_start)
   WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.id AND r.sealed_at IS NULL;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_result_seal AFTER INSERT ON att.attendance_result
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION att.tg_attendance_result_seal();

-- Lines may only be added to an unsealed PUBLISHED header (same transaction), and an
-- EMPLOYMENT-scope result only carries lines of its employment.
CREATE OR REPLACE FUNCTION att.tg_attendance_result_line_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;
BEGIN
  SELECT status, sealed_at, scope, employment_id INTO r
    FROM att.attendance_result WHERE tenant_id = NEW.tenant_id AND id = NEW.result_id;
  IF NOT FOUND THEN
    RETURN NEW;                                            -- the FK reports it
  END IF;
  IF r.sealed_at IS NOT NULL OR r.status <> 'PUBLISHED' THEN
    RAISE EXCEPTION 'att.attendance_result % is sealed (%): lines are immutable once committed', NEW.result_id, r.status
      USING ERRCODE = '55000';
  END IF;
  IF r.scope = 'EMPLOYMENT' AND NEW.employment_id IS DISTINCT FROM r.employment_id THEN
    RAISE EXCEPTION 'EMPLOYMENT-scope result % only carries lines of employment %', NEW.result_id, r.employment_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_result_line_insert BEFORE INSERT ON att.attendance_result_line
  FOR EACH ROW EXECUTE FUNCTION att.tg_attendance_result_line_insert();
CREATE TRIGGER trg_immutable BEFORE UPDATE OR DELETE ON att.attendance_result_line
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();

-- Hold acquire/release rules (BR-ATT-079, gap §7.1 invariants 1-3):
--  INSERT: PERIOD scope -> period CLOSED and result = current version, PUBLISHED;
--          EMPLOYMENT scope (leaver early close) -> result PUBLISHED, period not LOCKED.
--  UPDATE: ACTIVE -> RELEASED | CONSUMED; CONSUMED -> RELEASED only with UNLOCKED; identity immutable.
CREATE OR REPLACE FUNCTION att.tg_attendance_result_hold_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  p record; r record;
  lifecycle constant text[] := ARRAY['status','released_at','released_by','release_reason','consumed_at',
                                     'updated_at','updated_by','version','scope_key'];
BEGIN
  IF TG_OP = 'INSERT' THEN
    SELECT status, current_version INTO p FROM att.attendance_period
     WHERE tenant_id = NEW.tenant_id AND id = NEW.period_id
       FOR SHARE;                                          -- serialises with reopen (period UPDATE)
    SELECT status, result_version, scope INTO r FROM att.attendance_result
     WHERE tenant_id = NEW.tenant_id AND id = NEW.result_id;
    IF NOT FOUND OR p IS NULL THEN
      RETURN NEW;                                          -- the FKs report it
    END IF;
    IF NEW.status <> 'ACTIVE' THEN
      RAISE EXCEPTION 'a close hold is created ACTIVE' USING ERRCODE = '23514';
    END IF;
    IF r.scope IS DISTINCT FROM NEW.scope OR r.result_version <> NEW.result_version THEN
      RAISE EXCEPTION 'hold scope/version (%/v%) does not match result % (%/v%)', NEW.scope, NEW.result_version,
        NEW.result_id, r.scope, r.result_version USING ERRCODE = '23514';
    END IF;
    IF r.status <> 'PUBLISHED' THEN
      RAISE EXCEPTION 'acquireHold refused: result % is %', NEW.result_id, r.status USING ERRCODE = '55000';
    END IF;
    IF NEW.scope = 'PERIOD' AND (p.status <> 'CLOSED' OR p.current_version <> NEW.result_version) THEN
      RAISE EXCEPTION 'acquireHold refused: period % is % at v% (requested v%) (BR-ATT-079)', NEW.period_id, p.status,
        p.current_version, NEW.result_version USING ERRCODE = '55000';
    END IF;
    IF NEW.scope = 'EMPLOYMENT' AND p.status = 'LOCKED' THEN
      RAISE EXCEPTION 'acquireHold refused: period % is LOCKED', NEW.period_id USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
  END IF;

  IF TG_OP = 'DELETE' THEN
    IF sys.purge_bypass(OLD.tenant_id) THEN
      RETURN OLD;                                          -- D9 tenant purge
    END IF;
    RAISE EXCEPTION 'att.attendance_result_hold rows are released, never deleted' USING ERRCODE = '55000';
  END IF;
  IF (to_jsonb(NEW) - lifecycle) IS DISTINCT FROM (to_jsonb(OLD) - lifecycle) THEN
    RAISE EXCEPTION 'att.attendance_result_hold %: identity columns are immutable', OLD.id USING ERRCODE = '55000';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status
     AND NOT ((OLD.status, NEW.status) IN (('ACTIVE','RELEASED'), ('ACTIVE','CONSUMED'))
              OR (OLD.status = 'CONSUMED' AND NEW.status = 'RELEASED' AND NEW.release_reason = 'UNLOCKED')) THEN
    RAISE EXCEPTION 'att.attendance_result_hold %: illegal transition % -> %', OLD.id, OLD.status, NEW.status USING ERRCODE = '55000';
  END IF;
  IF OLD.status = 'RELEASED' AND (to_jsonb(NEW) - 'updated_at' - 'scope_key') IS DISTINCT FROM (to_jsonb(OLD) - 'updated_at' - 'scope_key') THEN
    RAISE EXCEPTION 'att.attendance_result_hold % is RELEASED (terminal)', OLD.id USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_hold_guard BEFORE INSERT OR UPDATE OR DELETE ON att.attendance_result_hold
  FOR EACH ROW EXECUTE FUNCTION att.tg_attendance_result_hold_guard();

-- Convenience check for the reopen UI / BR-ATT-087 flow (the trigger above is the guard).
CREATE OR REPLACE FUNCTION att.active_hold_for(p_period_id uuid, p_employment_id uuid DEFAULT NULL)
RETURNS att.attendance_result_hold
LANGUAGE sql STABLE AS $$
  SELECT h.* FROM att.attendance_result_hold h
   WHERE h.tenant_id = sys.current_tenant_id() AND h.period_id = p_period_id AND h.status = 'ACTIVE'
     AND h.scope_key = coalesce(p_employment_id, '00000000-0000-0000-0000-000000000000'::uuid)
$$;
COMMENT ON FUNCTION att.active_hold_for(uuid, uuid) IS
  'Returns the ACTIVE close hold of a period (PERIOD scope) or of one employment (EMPLOYMENT scope), or NULL. '
  'Used by the reopen flow to show the holding payroll run and raise a release request (BR-ATT-087).';

-- =============================================================================
-- 10. PROJECTIONS  (event-fed read models of other modules, DESIGN §2.2)
--     id = the owner's row id (version row). Rebuildable via the owner API (re-sync).
-- =============================================================================
CREATE TABLE att.ext_core_location (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL,                        -- = core.location_version.id (soft)
  location_id        uuid NOT NULL,                        -- -> core.location (soft)
  legal_entity_id    uuid NOT NULL,                        -- -> core.legal_entity (soft)
  code               text NOT NULL,
  name               text NOT NULL,
  time_zone          text NOT NULL,
  admin_unit_code    text,
  is_remote_area     boolean NOT NULL DEFAULT false,       -- 6th-month rule for protected workers (VN-16)
  latitude           numeric(9,6),                         -- P2 geofence
  longitude          numeric(9,6),
  geofence_radius_m  int,
  status             text NOT NULL,
  valid_from         date NOT NULL,
  valid_to           date,
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  source_event_id    uuid NOT NULL,
  source_version     bigint NOT NULL,
  synced_at          timestamptz NOT NULL DEFAULT now(),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_core_location_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_core_location_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT ext_core_location_ex EXCLUDE USING gist (tenant_id WITH =, location_id WITH =, valid_during WITH &&)
);

CREATE TABLE att.ext_hr_assignment (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL,                    -- = hr.assignment.id of the current-knowledge version (soft)
  assignment_key         uuid NOT NULL,
  employment_id          uuid NOT NULL,
  person_id              uuid NOT NULL,
  assignment_type        text NOT NULL,
  legal_entity_id        uuid NOT NULL,
  org_unit_id            uuid NOT NULL,
  position_id            uuid,
  job_title_id           uuid,
  location_id            uuid,
  cost_centre_id         uuid,
  manager_employment_id  uuid,
  employee_category_code text,
  working_regime_code    text,
  fte                    numeric(5,4) NOT NULL,
  valid_from             date NOT NULL,
  valid_to               date,
  valid_during           daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  source_event_id        uuid NOT NULL,
  source_version         bigint NOT NULL,
  source_recorded_at     timestamptz NOT NULL,             -- hr.assignment.recorded_at (retro detection)
  synced_at              timestamptz NOT NULL DEFAULT now(),
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_hr_assignment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_hr_assignment_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT ext_hr_assignment_ex EXCLUDE USING gist (tenant_id WITH =, assignment_key WITH =, valid_during WITH &&)
);
CREATE INDEX ext_hr_assignment_employment_ix ON att.ext_hr_assignment (tenant_id, employment_id, valid_from);
CREATE INDEX ext_hr_assignment_scope_ix ON att.ext_hr_assignment (tenant_id, org_unit_id, valid_from);
COMMENT ON COLUMN att.ext_hr_assignment.person_id IS 'pii:P1';

CREATE TABLE att.ext_pay_pay_group_assignment (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL,                        -- = pay pay-group assignment row id (soft)
  employment_id      uuid NOT NULL,
  pay_group_id       uuid NOT NULL,
  legal_entity_id    uuid NOT NULL,
  valid_from         date NOT NULL,
  valid_to           date,
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  source_event_id    uuid NOT NULL,                        -- pay.PayGroupAssigned (ATT-C-24)
  source_version     bigint NOT NULL,
  synced_at          timestamptz NOT NULL DEFAULT now(),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_pay_pay_group_assignment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_pay_pay_group_assignment_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT ext_pay_pay_group_assignment_ex EXCLUDE USING gist (tenant_id WITH =, employment_id WITH =, valid_during WITH &&)
);
CREATE INDEX ext_pay_pay_group_assignment_group_ix ON att.ext_pay_pay_group_assignment (tenant_id, pay_group_id, valid_from);

-- D5: employment facts T&A needs for NOT_EMPLOYED days, leaver early close (BR-ATT-075),
-- annual-leave proration and seniority (BR-ATT-058/059), probation eligibility of leave
-- types and termination settlement of leave (ENCASH). One row per hr.employment, the
-- latest known state (dates are business dates, not effective-dated versions: People
-- re-publishes the whole row on hire, change, termination and cancellation).
-- Fed by people.EmploymentCreated / EmploymentChanged / EmploymentTerminated /
-- EmploymentCancelled; rebuildable through the People API. No P3 data.
CREATE TABLE att.ext_hr_employment (
  tenant_id              uuid NOT NULL,
  employment_id          uuid NOT NULL,                    -- = hr.employment.id (soft)
  person_id              uuid NOT NULL,                    -- -> hr.person (soft)
  legal_entity_id        uuid NOT NULL,                    -- -> core.legal_entity (soft)
  hire_date              date NOT NULL,
  seniority_date         date,                             -- service start for leave seniority (+1 day per 5 years)
  probation_end_date     date,
  last_working_date      date,
  termination_date       date,                             -- = hr.employment.end_date, INCLUSIVE
  employment_period      daterange GENERATED ALWAYS AS (daterange(hire_date, termination_date, '[]')) STORED,
  status                 text NOT NULL,
  worker_category_code   text NOT NULL,                    -- = hr.employment.worker_category_code (EMPLOYEE | EXPAT | APPRENTICE ...)
  employee_category_code text,
  source_event_id        uuid NOT NULL,
  source_version         bigint NOT NULL,                  -- hr.employment.version; older events are ignored
  synced_at              timestamptz NOT NULL DEFAULT now(),
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_hr_employment_pk PRIMARY KEY (tenant_id, employment_id),
  CONSTRAINT ext_hr_employment_status_ck CHECK (status IN ('PRE_HIRE','ACTIVE','SUSPENDED','TERMINATED','CANCELLED')),
  CONSTRAINT ext_hr_employment_dates_ck CHECK (
        (termination_date  IS NULL OR termination_date  >= hire_date)
    AND (last_working_date IS NULL OR last_working_date >= hire_date)
    AND (probation_end_date IS NULL OR probation_end_date >= hire_date)),
  CONSTRAINT ext_hr_employment_terminated_ck CHECK (status <> 'TERMINATED' OR termination_date IS NOT NULL)
);
CREATE INDEX ext_hr_employment_person_ix ON att.ext_hr_employment (tenant_id, person_id);
CREATE INDEX ext_hr_employment_le_ix ON att.ext_hr_employment (tenant_id, legal_entity_id, termination_date);
COMMENT ON TABLE att.ext_hr_employment IS
  'D5 projection of hr.employment (latest state, one row per employment). Dates drive NOT_EMPLOYED days, leaver early close '
  '(BR-ATT-075), leave proration / seniority / probation eligibility.';
COMMENT ON COLUMN att.ext_hr_employment.person_id IS 'pii:P1';
COMMENT ON COLUMN att.ext_hr_employment.hire_date IS 'pii:P1';

-- D5: protected-worker statuses T&A needs (BR-ATT-048 OT ban / consent, night-work and
-- hazardous-work restrictions, BLLĐ Điều 137/146/160; VN-16 maternity 6th-month rule).
-- DESIGN CHOICE: a separate effective-dated projection, one row per hr.special_status
-- row, instead of boolean columns on att.ext_hr_employment, because
--   (1) the statuses are time-bounded (pregnancy, child under 12 months, minor) and
--       daily processing / OT approval / retro recalculation need the value AS OF the
--       work date, not the current value;
--   (2) most of them belong to the PERSON (all employments of the person), only
--       HAZARDOUS_JOB to one employment, exactly as in hr.special_status;
--   (3) the source's exclusion constraint can be mirrored and a re-sync is a plain
--       per-row upsert/delete keyed by source_record_id.
-- Booleans are derived on read by att.protected_flags(employment_id, date) below.
-- DATA MINIMISATION: only the types T&A rules use are projected (no UNION_MEMBER /
-- PARTY_MEMBER / YOUTH_UNION_MEMBER), and no detail (classification, detail_enc,
-- evidence document, override reason) leaves People. The derived type + validity is
-- classified P2 (sensitive-derived for PREGNANCY / DISABILITY); only yhrm_app_att may
-- read it, and services show it as a "protected worker" marker, never the type, to
-- managers without the sensitive-data permission.
CREATE TABLE att.ext_hr_special_status (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  source_record_id       uuid NOT NULL,                    -- = hr.special_status.id (soft)
  person_id              uuid NOT NULL,                    -- -> hr.person (soft)
  employment_id          uuid,                             -- -> hr.employment (soft); HAZARDOUS_JOB only
  scope_id               uuid GENERATED ALWAYS AS (coalesce(employment_id, person_id)) STORED,
  protection_type        text NOT NULL,
  valid_from             date NOT NULL,
  valid_to               date,                             -- EXCLUSIVE; NULL = open
  valid_during           daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  source_event_id        uuid NOT NULL,                    -- people.SpecialStatusChanged
  source_version         bigint NOT NULL,
  synced_at              timestamptz NOT NULL DEFAULT now(),
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ext_hr_special_status_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT ext_hr_special_status_src_uq UNIQUE (tenant_id, source_record_id),
  CONSTRAINT ext_hr_special_status_type_ck CHECK (protection_type IN
    ('PREGNANCY','CHILD_UNDER_12M','DISABILITY','MINOR','ELDERLY_WORKER','HAZARDOUS_JOB')),
  CONSTRAINT ext_hr_special_status_scope_ck CHECK ((protection_type = 'HAZARDOUS_JOB') = (employment_id IS NOT NULL)),
  CONSTRAINT ext_hr_special_status_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  -- cancelled source rows are DELETED from the projection, so every row is active
  CONSTRAINT ext_hr_special_status_ex EXCLUDE USING gist
    (tenant_id WITH =, scope_id WITH =, protection_type WITH =, valid_during WITH &&)
);
CREATE INDEX ext_hr_special_status_person_ix ON att.ext_hr_special_status (tenant_id, person_id, valid_from);
COMMENT ON TABLE att.ext_hr_special_status IS
  'D5 projection of hr.special_status (protected-worker types only, no detail). Effective-dated; booleans via att.protected_flags().';
COMMENT ON COLUMN att.ext_hr_special_status.protection_type IS
  'pii:P2 sensitive-derived (PREGNANCY, DISABILITY reveal health): type + validity only, detail stays in hr.special_status';

-- Protected-worker booleans of one employment on one date (person-level statuses of
-- the employment's person + HAZARDOUS_JOB of that employment). SECURITY INVOKER: RLS of
-- the caller applies; returns all-false when the employment is unknown.
CREATE OR REPLACE FUNCTION att.protected_flags(p_employment_id uuid, p_on date)
RETURNS TABLE (is_pregnant boolean, has_child_under_12m boolean, is_disabled boolean,
               is_minor boolean, is_elderly_worker boolean, hazardous_job boolean)
LANGUAGE sql STABLE SECURITY INVOKER AS $$
  SELECT coalesce(bool_or(s.protection_type = 'PREGNANCY'), false),
         coalesce(bool_or(s.protection_type = 'CHILD_UNDER_12M'), false),
         coalesce(bool_or(s.protection_type = 'DISABILITY'), false),
         coalesce(bool_or(s.protection_type = 'MINOR'), false),
         coalesce(bool_or(s.protection_type = 'ELDERLY_WORKER'), false),
         coalesce(bool_or(s.protection_type = 'HAZARDOUS_JOB'), false)
    FROM att.ext_hr_employment e
    JOIN att.ext_hr_special_status s
      ON s.tenant_id = e.tenant_id AND s.person_id = e.person_id
     AND (s.employment_id IS NULL OR s.employment_id = e.employment_id)
     AND s.valid_during @> p_on
   WHERE e.tenant_id = sys.current_tenant_id() AND e.employment_id = p_employment_id
$$;
COMMENT ON FUNCTION att.protected_flags(uuid, date) IS
  'D5: protected-worker flags of an employment as of a date (BR-ATT-048, night / hazardous work limits).';

-- =============================================================================
-- 11. PARTITIONS  (DESIGN §10.2). Wrapper over sys.create_range_partitions that also
--     applies per-partition storage parameters (a partitioned parent cannot hold
--     them): daily_timesheet -> fillfactor 80 + aggressive autovacuum (HOT updates);
--     raw_punch / attendance_result_line -> insert-driven autovacuum (append-only).
--     The SaaS scheduler calls it monthly to keep 3 periods ahead.
-- =============================================================================
CREATE OR REPLACE PROCEDURE att.create_partitions(p_parent regclass, p_from date, p_count int)
LANGUAGE plpgsql AS $$
DECLARE
  v_opts text;
  c record;
BEGIN
  CALL sys.create_range_partitions(p_parent, 'month', p_from, p_count);
  v_opts := CASE p_parent
              WHEN 'att.daily_timesheet'::regclass THEN
                'fillfactor = 80, autovacuum_vacuum_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.01'
              WHEN 'att.raw_punch'::regclass THEN 'autovacuum_vacuum_insert_scale_factor = 0.05'
              WHEN 'att.attendance_result_line'::regclass THEN 'autovacuum_vacuum_insert_scale_factor = 0.05'
            END;
  IF v_opts IS NULL THEN
    RETURN;
  END IF;
  FOR c IN SELECT i.inhrelid::regclass AS part FROM pg_inherits i WHERE i.inhparent = p_parent LOOP
    EXECUTE format('ALTER TABLE %s SET (%s)', c.part, v_opts);
  END LOOP;
END $$;
REVOKE EXECUTE ON PROCEDURE att.create_partitions(regclass, date, int) FROM PUBLIC;

-- previous month (late device-export files), current month and 2 ahead
CALL att.create_partitions('att.raw_punch',              (date_trunc('month', current_date) - interval '1 month')::date, 4);
CALL att.create_partitions('att.daily_timesheet',        (date_trunc('month', current_date) - interval '1 month')::date, 4);
CALL att.create_partitions('att.attendance_result_line', (date_trunc('month', current_date) - interval '1 month')::date, 4);

-- =============================================================================
-- 12. RLS, triggers, grants
-- =============================================================================
DO $$
DECLARE t text;
BEGIN
  -- tenant-owned
  FOREACH t IN ARRAY ARRAY[
    'att.holiday_calendar_assignment','att.shift_definition','att.shift_break',
    'att.schedule_pattern','att.schedule_pattern_day','att.schedule_assignment_rule','att.schedule_assignment',
    'att.roster','att.roster_line','att.device','att.enrolment_mapping','att.raw_punch',
    'att.daily_timesheet','att.timesheet_correction','att.overtime_request','att.overtime_result',
    'att.ot_cap_ledger','att.leave_type','att.leave_policy','att.leave_request','att.leave_balance_ledger',
    'att.business_trip','att.remote_work_request','att.attendance_period','att.attendance_result',
    'att.attendance_result_line','att.attendance_result_hold',
    'att.ext_core_location','att.ext_hr_assignment','att.ext_pay_pay_group_assignment',
    'att.ext_hr_employment','att.ext_hr_special_status']
  LOOP
    CALL sys.enable_tenant_rls(t::regclass);
  END LOOP;

  -- global + tenant-overridable (DESIGN §3.5): national holiday template, default attendance codes.
  -- NB: holiday_date rows of a global template calendar also use the sentinel tenant, so
  -- holiday_date gets the tenant-or-global read policy too.
  CALL sys.enable_tenant_or_global_rls('att.holiday_calendar');
  CALL sys.enable_tenant_or_global_rls('att.holiday_date');
  CALL sys.enable_tenant_or_global_rls('att.attendance_code');

  -- mutable tables: updated_at + tenant_id/id immutability
  FOREACH t IN ARRAY ARRAY[
    'att.holiday_calendar','att.holiday_calendar_assignment','att.holiday_date','att.shift_definition',
    'att.schedule_pattern','att.schedule_assignment_rule','att.schedule_assignment','att.roster','att.roster_line',
    'att.device','att.enrolment_mapping','att.attendance_code','att.daily_timesheet','att.timesheet_correction',
    'att.overtime_request','att.overtime_result','att.ot_cap_ledger','att.leave_type','att.leave_policy',
    'att.leave_request','att.business_trip','att.remote_work_request','att.attendance_period',
    'att.attendance_result_hold','att.ext_core_location','att.ext_hr_assignment','att.ext_pay_pay_group_assignment',
    'att.ext_hr_special_status']
  LOOP
    CALL sys.attach_touch_trigger(t::regclass);
  END LOOP;

  -- row audit on configuration, requests and the close protocol (NFR-CORE-008, VN-20).
  -- NOT on raw_punch / daily_timesheet / result lines (volume; they are evidence or
  -- recomputed; admin edits are audited through timesheet_correction).
  FOREACH t IN ARRAY ARRAY[
    'att.holiday_calendar','att.holiday_calendar_assignment','att.holiday_date','att.shift_definition',
    'att.schedule_pattern','att.schedule_assignment_rule','att.schedule_assignment','att.device',
    'att.enrolment_mapping','att.attendance_code','att.timesheet_correction','att.overtime_request',
    'att.leave_type','att.leave_policy','att.leave_request','att.attendance_period','att.attendance_result',
    'att.attendance_result_hold']
  LOOP
    CALL audit.attach_row_audit(t::regclass);
  END LOOP;
END $$;

-- Grants: Time & Attendance runtime only (DESIGN §2.3). Parents only, never partitions
-- (RLS policies on a partitioned parent do not apply to direct partition access).
GRANT SELECT, INSERT, UPDATE ON
  att.holiday_calendar, att.holiday_calendar_assignment, att.holiday_date, att.shift_definition, att.shift_break,
  att.schedule_pattern, att.schedule_pattern_day, att.schedule_assignment_rule, att.schedule_assignment,
  att.roster, att.roster_line, att.device, att.enrolment_mapping, att.attendance_code,
  att.daily_timesheet, att.timesheet_correction, att.overtime_request, att.overtime_result, att.ot_cap_ledger,
  att.leave_type, att.leave_policy, att.leave_request, att.business_trip, att.remote_work_request,
  att.attendance_period, att.attendance_result, att.attendance_result_hold,
  att.ext_core_location, att.ext_hr_assignment, att.ext_pay_pay_group_assignment,
  att.ext_hr_employment, att.ext_hr_special_status
TO yhrm_app_att;
-- att.ext_hr_employment has no trg_touch (key is employment_id, there is no id column):
-- the projection handler sets updated_at/synced_at; tenant_id is pinned by RLS WITH CHECK.
-- append-only
GRANT SELECT, INSERT ON att.raw_punch, att.leave_balance_ledger, att.attendance_result_line TO yhrm_app_att;
-- quarantine resolution: matching columns only (att.tg_raw_punch_guard)
GRANT UPDATE (employment_id, legal_entity_id, org_unit_id, enrolment_mapping_id, validation_status, matched_at, matched_by)
  ON att.raw_punch TO yhrm_app_att;
-- DELETE: draft requests, configuration children, projection re-sync, recalculated day OT
GRANT DELETE ON
  att.holiday_date, att.shift_break, att.schedule_pattern_day, att.roster_line, att.timesheet_correction,
  att.overtime_request, att.overtime_result, att.leave_request, att.business_trip, att.remote_work_request,
  att.attendance_period, att.ext_core_location, att.ext_hr_assignment, att.ext_pay_pay_group_assignment,
  att.ext_hr_employment, att.ext_hr_special_status
TO yhrm_app_att;
REVOKE EXECUTE ON FUNCTION att.active_hold_for(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION att.active_hold_for(uuid, uuid) TO yhrm_app_att;
REVOKE EXECUTE ON FUNCTION att.protected_flags(uuid, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION att.protected_flags(uuid, date) TO yhrm_app_att;

-- Outbox / inbox for T&A (att.AttendancePeriodClosed, att.AttendancePeriodHoldGranted/Released,
-- att.AttendancePeriodLocked, att.AttendancePeriodReopened, att.LeaverAttendanceClosed,
-- att.RetroDeltaCreated, att.HolidayCalendarChanged, ...).
CALL sys.create_outbox_inbox('att', 'yhrm_app_att');

RESET ROLE;
