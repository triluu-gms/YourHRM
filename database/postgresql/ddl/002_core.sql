-- =============================================================================
-- YourHRM  ·  002_core.sql   (owner module: Core; `audit` component)
-- Global master data, tenant profile, legal entity, location, cost centre,
-- org tree (as-of, ltree paths), job architecture, grades catalogue, positions,
-- users/roles/data scope (basics), generic import staging (opening-balance import),
-- the audit store with hash-chain sealing, Core's outbox/inbox and the X-02
-- cross-schema grant guard.
-- DESIGN.md §2-§5, §7.2, §9, §13; ADR-0020/0021/0022/0024/0027/0028/0035/0037.
-- v0.2 (2026-10-16): import staging (ADR-0037), X-02 guard, actor IMPORT. NOT re-tested
-- after these changes (no PostgreSQL available locally); reviewed by read-through only.
--
-- Tenancy plumbing (context getters, RLS helper procedures, outbox template)
-- is in 000_extensions_roles.sql (schema `sys`) because 001_saas.sql needs it.
--
-- Conventions recap:
--   * PK (tenant_id, id); FKs inside the schema are composite (tenant_id, x_id).
--   * No FK leaves the schema. References to other modules are soft (comment "soft").
--   * Global master data has NO tenant_id and NO RLS; app roles get SELECT only.
--   * Effective-dated attributes live in *_version tables (pattern A, DESIGN §5.1).
-- =============================================================================

\set ON_ERROR_STOP on
SET ROLE yhrm_owner;
SET search_path = core, sys, public;

GRANT USAGE ON SCHEMA core TO yhrm_app;           -- every module may read global ref tables
GRANT USAGE ON SCHEMA core TO yhrm_platform;      -- release job writes global master data via platform tooling

-- =============================================================================
-- 1. GLOBAL MASTER DATA  (pure global; released by the platform; gap §5 #28)
-- =============================================================================
CREATE TABLE core.country (
  country_code   char(2)  NOT NULL,                -- ISO 3166-1 alpha-2
  iso3           char(3)  NOT NULL,
  name_vi        text     NOT NULL,
  name_en        text     NOT NULL,
  nationality_vi text,
  nationality_en text,
  is_active      boolean  NOT NULL DEFAULT true,
  CONSTRAINT country_pk PRIMARY KEY (country_code)
);

CREATE TABLE core.currency (
  currency_code  char(3)  NOT NULL,                -- ISO 4217
  name_vi        text     NOT NULL,
  name_en        text     NOT NULL,
  minor_units    smallint NOT NULL,                -- VND = 0
  CONSTRAINT currency_pk PRIMARY KEY (currency_code)
);

-- Administrative units (province / commune; two-level model since the 2025 restructure,
-- three-level pre-2025 units kept with valid_to + mapping for history and for addresses
-- supplied in old format by opening-balance / employee-master imports, ADR-0037).
CREATE TABLE core.admin_unit (
  admin_unit_code  text NOT NULL,                  -- official GSO code
  level            text NOT NULL,                  -- PROVINCE | DISTRICT (legacy) | COMMUNE
  name_vi          text NOT NULL,
  name_en          text,
  parent_code      text,
  valid_from       date NOT NULL,
  valid_to         date,
  replaced_by_code text,
  source_decision  text,                           -- legal basis of creation/merger
  CONSTRAINT admin_unit_pk PRIMARY KEY (admin_unit_code),
  CONSTRAINT admin_unit_parent_fk FOREIGN KEY (parent_code) REFERENCES core.admin_unit (admin_unit_code),
  CONSTRAINT admin_unit_level_ck CHECK (level IN ('PROVINCE','DISTRICT','COMMUNE')),
  CONSTRAINT admin_unit_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX admin_unit_parent_ix ON core.admin_unit (parent_code);

CREATE TABLE core.admin_unit_mapping (
  old_code        text NOT NULL REFERENCES core.admin_unit (admin_unit_code),
  new_code        text NOT NULL REFERENCES core.admin_unit (admin_unit_code),
  effective_date  date NOT NULL,
  mapping_type    text NOT NULL,
  share_note      text,                            -- for SPLIT: which part goes where
  CONSTRAINT admin_unit_mapping_pk PRIMARY KEY (old_code, new_code, effective_date),
  CONSTRAINT admin_unit_mapping_type_ck CHECK (mapping_type IN ('MERGE','SPLIT','RENAME','RECODE'))
);
-- NB: admin unit -> minimum wage REGION is a legal parameter (saas.legal_param_table_row),
-- not Core master data (gap §5 #1).

CREATE TABLE core.bank (
  bank_code      text NOT NULL,                    -- SBV / NAPAS bank code
  bin            text,                             -- NAPAS BIN
  swift_code     text,
  short_name     text NOT NULL,
  name_vi        text NOT NULL,
  name_en        text,
  is_active      boolean NOT NULL DEFAULT true,
  CONSTRAINT bank_pk PRIMARY KEY (bank_code)
);

CREATE TABLE core.bank_branch (
  bank_code      text NOT NULL REFERENCES core.bank (bank_code),
  branch_code    text NOT NULL,
  name_vi        text NOT NULL,
  province_code  text,
  is_active      boolean NOT NULL DEFAULT true,
  CONSTRAINT bank_branch_pk PRIMARY KEY (bank_code, branch_code)
);

-- Code lists: global + tenant-overridable (DESIGN §3.5, ADR-0035).
CREATE TABLE core.code_list (
  list_code        text NOT NULL,                  -- CONTRACT_TYPE, RELATIONSHIP, ETHNICITY, SEPARATION_REASON ...
  name_vi          text NOT NULL,
  name_en          text NOT NULL,
  is_tenant_extensible boolean NOT NULL DEFAULT false,
  is_statutory     boolean NOT NULL DEFAULT false, -- statutory lists: tenants may only deactivate, never add
  owner_module     text NOT NULL,                  -- module that gives the codes behaviour
  CONSTRAINT code_list_pk PRIMARY KEY (list_code)
);

CREATE TABLE core.code_value (
  tenant_id      uuid NOT NULL,                    -- sys.global_tenant_id() for platform rows
  id             uuid NOT NULL DEFAULT sys.uuid_v7(),
  list_code      text NOT NULL REFERENCES core.code_list (list_code),
  value_code     text NOT NULL,
  label_vi       text NOT NULL,
  label_en       text NOT NULL,
  sort_order     int  NOT NULL DEFAULT 0,
  attributes     jsonb NOT NULL DEFAULT '{}'::jsonb,  -- e.g. {"si_code":"..."} for statutory mappings
  is_active      boolean NOT NULL DEFAULT true,
  valid_from     date NOT NULL DEFAULT DATE '1900-01-01',
  valid_to       date,
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     uuid,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     uuid,
  version        int NOT NULL DEFAULT 0,
  CONSTRAINT code_value_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT code_value_uq UNIQUE (tenant_id, list_code, value_code)
);
COMMENT ON TABLE core.code_value IS 'Global rows (sentinel tenant) + tenant overrides; resolve with core.v_code_value_effective.';

-- Effective code values for the current tenant: a tenant row shadows the global row.
CREATE VIEW core.v_code_value_effective WITH (security_invoker = true, security_barrier = true) AS
SELECT DISTINCT ON (cv.list_code, cv.value_code) cv.*
  FROM core.code_value cv
 ORDER BY cv.list_code, cv.value_code, (cv.tenant_id = sys.global_tenant_id());  -- false (tenant) sorts first

CREATE TABLE core.exchange_rate (
  tenant_id      uuid NOT NULL,                    -- sentinel = reference rate (SBV/VCB); tenant rows = own rates
  id             uuid NOT NULL DEFAULT sys.uuid_v7(),
  from_currency  char(3) NOT NULL REFERENCES core.currency (currency_code),
  to_currency    char(3) NOT NULL REFERENCES core.currency (currency_code),
  rate_type      text NOT NULL,                    -- SBV_CENTRAL | BANK_BUY | BANK_SELL | BANK_TRANSFER
  rate_date      date NOT NULL,
  rate           sys.rate NOT NULL,
  source         text NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT exchange_rate_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT exchange_rate_uq UNIQUE (tenant_id, from_currency, to_currency, rate_type, rate_date)
);

-- =============================================================================
-- 2. TENANT PROFILE
-- =============================================================================
CREATE TABLE core.company_group (
  tenant_id       uuid NOT NULL,
  id              uuid NOT NULL DEFAULT sys.uuid_v7(),
  name            text NOT NULL,
  logo_doc_id     uuid,                            -- -> core.document
  default_locale  text NOT NULL DEFAULT 'vi-VN',
  time_zone       text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  currency_code   sys.currency_code NOT NULL DEFAULT 'VND',
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid,
  version         int NOT NULL DEFAULT 0,
  CONSTRAINT company_group_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT company_group_one_per_tenant_uq UNIQUE (tenant_id)
);

-- =============================================================================
-- 3. LEGAL ENTITY  (anchor + effective-dated version; gap §5 #23)
-- =============================================================================
CREATE TABLE core.legal_entity (
  tenant_id     uuid NOT NULL,
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  code          text NOT NULL,                     -- tenant-chosen short code
  status        text NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    uuid,
  version       int NOT NULL DEFAULT 0,
  CONSTRAINT legal_entity_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT legal_entity_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT legal_entity_status_ck CHECK (status IN ('DRAFT','ACTIVE','DISSOLVED'))
);

CREATE TABLE core.legal_entity_version (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id    uuid NOT NULL,
  valid_from         date NOT NULL,
  valid_to           date,
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  legal_name_vi      text NOT NULL,
  legal_name_en      text,
  short_name         text,
  tax_code           text NOT NULL,                -- MST (10 digits, or 13 with branch suffix)
  si_unit_code       text,                         -- mã đơn vị BHXH
  business_reg_no    text,
  admin_unit_code    text,                         -- -> core.admin_unit (global, by code)
  address_line       text,
  legal_rep_name     text,
  legal_rep_title    text,
  tax_authority_code text,
  fiscal_year_start_month smallint NOT NULL DEFAULT 1,
  einvoice_identity  jsonb,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT legal_entity_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT legal_entity_version_le_fk FOREIGN KEY (tenant_id, legal_entity_id)
    REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT legal_entity_version_admin_unit_fk FOREIGN KEY (admin_unit_code)
    REFERENCES core.admin_unit (admin_unit_code),   -- global ref data in the SAME schema: FK allowed
  CONSTRAINT legal_entity_version_tax_code_ck CHECK (tax_code ~ '^[0-9]{10}(-[0-9]{3})?$'),
  CONSTRAINT legal_entity_version_fy_ck CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
  CONSTRAINT legal_entity_version_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT legal_entity_version_period_ex EXCLUDE USING gist
    (tenant_id WITH =, legal_entity_id WITH =, valid_during WITH &&)
);
CREATE INDEX legal_entity_version_asof_ix ON core.legal_entity_version (tenant_id, legal_entity_id, valid_from);

CREATE TABLE core.legal_entity_bank_account (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id  uuid NOT NULL,
  bank_code        text NOT NULL REFERENCES core.bank (bank_code),
  branch_code      text,
  account_no_enc   bytea NOT NULL,                 -- pii:P3 (payer account; envelope-encrypted with tenant key)
  account_no_last4 text  NOT NULL,
  account_name     text  NOT NULL,
  currency_code    sys.currency_code NOT NULL DEFAULT 'VND',
  purpose          text  NOT NULL,
  is_default       boolean NOT NULL DEFAULT false,
  status           text  NOT NULL DEFAULT 'ACTIVE',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT le_bank_account_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT le_bank_account_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT le_bank_account_purpose_ck CHECK (purpose IN ('SALARY','SI','TAX','TRADE_UNION','GENERAL')),
  CONSTRAINT le_bank_account_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE UNIQUE INDEX le_bank_account_default_ix ON core.legal_entity_bank_account (tenant_id, legal_entity_id, purpose)
  WHERE is_default AND status = 'ACTIVE';
COMMENT ON COLUMN core.legal_entity_bank_account.account_no_enc IS 'pii:P3 AES-256-GCM envelope ciphertext (tenant DEK).';

-- =============================================================================
-- 4. LOCATION  (anchor + version)
-- =============================================================================
CREATE TABLE core.location (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id  uuid NOT NULL,
  code             text NOT NULL,
  status           text NOT NULL DEFAULT 'ACTIVE',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT location_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT location_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT location_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT location_status_ck CHECK (status IN ('ACTIVE','CLOSED'))
);

CREATE TABLE core.location_version (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  location_id          uuid NOT NULL,
  valid_from           date NOT NULL,
  valid_to             date,
  valid_during         daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  name                 text NOT NULL,
  location_type        text NOT NULL,              -- HEAD_OFFICE | BRANCH | FACTORY | STORE | REMOTE
  admin_unit_code      text REFERENCES core.admin_unit (admin_unit_code),
  address_line         text,
  min_wage_region_override text,                   -- NULL = derive from legal pack map by admin_unit_code
  is_remote_area       boolean NOT NULL DEFAULT false,
  time_zone            text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  latitude             numeric(9,6),
  longitude            numeric(9,6),
  geofence_radius_m    int,
  dependent_tax_code   text,                       -- branch MST (dependent unit), if any
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT location_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT location_version_location_fk FOREIGN KEY (tenant_id, location_id) REFERENCES core.location (tenant_id, id),
  CONSTRAINT location_version_region_ck CHECK (min_wage_region_override IS NULL OR min_wage_region_override IN ('I','II','III','IV')),
  CONSTRAINT location_version_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT location_version_period_ex EXCLUDE USING gist (tenant_id WITH =, location_id WITH =, valid_during WITH &&)
);
CREATE INDEX location_version_asof_ix ON core.location_version (tenant_id, location_id, valid_from);

-- =============================================================================
-- 5. COST CENTRE  (single-table pattern A: rarely changes, eff-dated status)
-- =============================================================================
CREATE TABLE core.cost_centre (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id  uuid NOT NULL,
  code             text NOT NULL,
  name             text NOT NULL,
  gl_segment       text,
  owner_user_id    uuid,
  valid_from       date NOT NULL,
  valid_to         date,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT cost_centre_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT cost_centre_code_uq UNIQUE (tenant_id, legal_entity_id, code),
  CONSTRAINT cost_centre_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT cost_centre_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from)
);

-- =============================================================================
-- 6. ORG TREE  (anchor + version with materialised ltree path; DESIGN §5.3)
-- =============================================================================
CREATE TABLE core.org_unit_type (
  tenant_id   uuid NOT NULL,
  id          uuid NOT NULL DEFAULT sys.uuid_v7(),
  code        text NOT NULL,                       -- COMPANY | DIVISION | DEPARTMENT | TEAM | LINE ...
  name_vi     text NOT NULL,
  name_en     text NOT NULL,
  level_hint  smallint,
  CONSTRAINT org_unit_type_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT org_unit_type_code_uq UNIQUE (tenant_id, code)
);

CREATE TABLE core.org_unit (
  tenant_id     uuid NOT NULL,
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  code          text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid,
  CONSTRAINT org_unit_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT org_unit_code_uq UNIQUE (tenant_id, code)
);

CREATE TABLE core.org_unit_version (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  org_unit_id        uuid NOT NULL,
  valid_from         date NOT NULL,
  valid_to           date,
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  name_vi            text NOT NULL,
  name_en            text,
  org_unit_type_id   uuid NOT NULL,
  parent_id          uuid,                         -- NULL = root (one root per legal entity)
  path               ltree NOT NULL,               -- labels = org_unit ids without hyphens, root..self
  depth              smallint GENERATED ALWAYS AS (nlevel(path)) STORED,
  legal_entity_id    uuid NOT NULL,
  location_id        uuid,
  cost_centre_id     uuid,
  manager_position_id uuid,                        -- -> core.position
  status             text NOT NULL DEFAULT 'ACTIVE',
  reorg_package_id   uuid,                         -- the package that created this version
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT org_unit_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT org_unit_version_unit_fk   FOREIGN KEY (tenant_id, org_unit_id)      REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT org_unit_version_parent_fk FOREIGN KEY (tenant_id, parent_id)        REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT org_unit_version_type_fk   FOREIGN KEY (tenant_id, org_unit_type_id) REFERENCES core.org_unit_type (tenant_id, id),
  CONSTRAINT org_unit_version_le_fk     FOREIGN KEY (tenant_id, legal_entity_id)  REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT org_unit_version_loc_fk    FOREIGN KEY (tenant_id, location_id)      REFERENCES core.location (tenant_id, id),
  CONSTRAINT org_unit_version_cc_fk     FOREIGN KEY (tenant_id, cost_centre_id)   REFERENCES core.cost_centre (tenant_id, id),
  CONSTRAINT org_unit_version_status_ck CHECK (status IN ('ACTIVE','CLOSED')),
  CONSTRAINT org_unit_version_not_self_ck CHECK (parent_id IS DISTINCT FROM org_unit_id),
  CONSTRAINT org_unit_version_path_self_ck CHECK (subpath(path, -1)::text = replace(org_unit_id::text, '-', '')),
  CONSTRAINT org_unit_version_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT org_unit_version_period_ex EXCLUDE USING gist (tenant_id WITH =, org_unit_id WITH =, valid_during WITH &&)
);
CREATE INDEX org_unit_version_asof_ix   ON core.org_unit_version (tenant_id, org_unit_id, valid_from);
CREATE INDEX org_unit_version_parent_ix ON core.org_unit_version (tenant_id, parent_id, valid_from);
CREATE INDEX org_unit_version_path_gix  ON core.org_unit_version USING gist (path);
COMMENT ON COLUMN core.org_unit_version.path IS
  'Materialised path for subtree/data-scope queries: path <@ :root AND valid_during @> :date. Recomputed for descendants on reorg activation.';

-- As-of org tree helper (NFR-CORE-003).
CREATE OR REPLACE FUNCTION core.org_tree_as_of(p_date date, p_root uuid DEFAULT NULL)
RETURNS SETOF core.org_unit_version
LANGUAGE sql STABLE AS $$
  SELECT v.*
    FROM core.org_unit_version v
   WHERE v.tenant_id = sys.current_tenant_id()
     AND v.valid_during @> p_date
     AND (p_root IS NULL OR v.path <@ (SELECT r.path FROM core.org_unit_version r
                                         WHERE r.tenant_id = sys.current_tenant_id()
                                           AND r.org_unit_id = p_root AND r.valid_during @> p_date))
   ORDER BY v.path
$$;

-- =============================================================================
-- 7. JOB ARCHITECTURE AND GRADES CATALOGUE
--    (salary bands/pay scales are owned by Payroll, gap §5 #7)
-- =============================================================================
CREATE TABLE core.job_family (
  tenant_id  uuid NOT NULL,
  id         uuid NOT NULL DEFAULT sys.uuid_v7(),
  code       text NOT NULL,
  name_vi    text NOT NULL,
  name_en    text,
  is_active  boolean NOT NULL DEFAULT true,
  CONSTRAINT job_family_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT job_family_code_uq UNIQUE (tenant_id, code)
);

CREATE TABLE core.job_title (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  job_family_id        uuid,
  code                 text NOT NULL,
  name_vi              text NOT NULL,
  name_en              text,
  hazardous_category   text,                       -- statutory heavy/hazardous classification code (affects leave, OT, retirement)
  occupation_code      text,                       -- national occupation classification (statutory reports)
  jd_document_id       uuid,                       -- -> core.document
  is_active            boolean NOT NULL DEFAULT true,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT job_title_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT job_title_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT job_title_family_fk FOREIGN KEY (tenant_id, job_family_id) REFERENCES core.job_family (tenant_id, id)
);

CREATE TABLE core.grade_structure (
  tenant_id  uuid NOT NULL,
  id         uuid NOT NULL DEFAULT sys.uuid_v7(),
  code       text NOT NULL,
  name       text NOT NULL,
  CONSTRAINT grade_structure_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT grade_structure_code_uq UNIQUE (tenant_id, code)
);

CREATE TABLE core.grade (
  tenant_id           uuid NOT NULL,
  id                  uuid NOT NULL DEFAULT sys.uuid_v7(),
  grade_structure_id  uuid NOT NULL,
  code                text NOT NULL,
  name                text NOT NULL,
  rank_order          int  NOT NULL,
  is_active           boolean NOT NULL DEFAULT true,
  CONSTRAINT grade_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT grade_code_uq UNIQUE (tenant_id, grade_structure_id, code),
  CONSTRAINT grade_structure_fk FOREIGN KEY (tenant_id, grade_structure_id) REFERENCES core.grade_structure (tenant_id, id)
);

-- =============================================================================
-- 8. POSITION  (budgeted seat; anchor + version; gap §5 #8)
-- =============================================================================
CREATE TABLE core.position (
  tenant_id   uuid NOT NULL,
  id          uuid NOT NULL DEFAULT sys.uuid_v7(),
  code        text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid,
  CONSTRAINT position_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT position_code_uq UNIQUE (tenant_id, code)
);

CREATE TABLE core.position_version (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  position_id             uuid NOT NULL,
  valid_from              date NOT NULL,
  valid_to                date,
  valid_during            daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  title_override          text,
  job_title_id            uuid NOT NULL,
  org_unit_id             uuid NOT NULL,
  legal_entity_id         uuid NOT NULL,           -- denormalised from org unit for scope filters
  location_id             uuid,
  cost_centre_id          uuid,
  reports_to_position_id  uuid,
  grade_min_id            uuid,
  grade_max_id            uuid,
  fte_budget              numeric(6,2) NOT NULL DEFAULT 1.00,
  status                  text NOT NULL DEFAULT 'ACTIVE',
  is_critical             boolean NOT NULL DEFAULT false,
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT position_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT position_version_position_fk FOREIGN KEY (tenant_id, position_id)   REFERENCES core.position (tenant_id, id),
  CONSTRAINT position_version_job_fk      FOREIGN KEY (tenant_id, job_title_id)  REFERENCES core.job_title (tenant_id, id),
  CONSTRAINT position_version_org_fk      FOREIGN KEY (tenant_id, org_unit_id)   REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT position_version_le_fk       FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT position_version_loc_fk      FOREIGN KEY (tenant_id, location_id)   REFERENCES core.location (tenant_id, id),
  CONSTRAINT position_version_cc_fk       FOREIGN KEY (tenant_id, cost_centre_id) REFERENCES core.cost_centre (tenant_id, id),
  CONSTRAINT position_version_reports_fk  FOREIGN KEY (tenant_id, reports_to_position_id) REFERENCES core.position (tenant_id, id),
  CONSTRAINT position_version_gmin_fk     FOREIGN KEY (tenant_id, grade_min_id)  REFERENCES core.grade (tenant_id, id),
  CONSTRAINT position_version_gmax_fk     FOREIGN KEY (tenant_id, grade_max_id)  REFERENCES core.grade (tenant_id, id),
  CONSTRAINT position_version_status_ck CHECK (status IN ('PROPOSED','ACTIVE','FROZEN','CLOSED')),
  CONSTRAINT position_version_fte_ck CHECK (fte_budget >= 0),
  CONSTRAINT position_version_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT position_version_period_ex EXCLUDE USING gist (tenant_id WITH =, position_id WITH =, valid_during WITH &&)
);
CREATE INDEX position_version_asof_ix ON core.position_version (tenant_id, position_id, valid_from);
CREATE INDEX position_version_org_ix  ON core.position_version (tenant_id, org_unit_id, valid_from);

-- =============================================================================
-- 9. USERS, ROLES, DATA SCOPE  (basics; authorisation is Core, authentication SaaS)
-- =============================================================================
CREATE TABLE core.user_account (
  tenant_id      uuid NOT NULL,
  id             uuid NOT NULL DEFAULT sys.uuid_v7(),
  login          text NOT NULL,                    -- employee code / work email / phone (BR-SAAS-045)
  email          text,
  mobile         text,
  employment_id  uuid,                             -- -> hr.employment (soft); NULL for non-employee users
  person_id      uuid,                             -- -> hr.person (soft)
  locale         text NOT NULL DEFAULT 'vi-VN',
  status         text NOT NULL DEFAULT 'ACTIVE',
  expires_at     timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     uuid,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     uuid,
  version        int NOT NULL DEFAULT 0,
  CONSTRAINT user_account_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT user_account_status_ck CHECK (status IN ('INVITED','ACTIVE','FORMER_EMPLOYEE','DEACTIVATED','LOCKED'))
);
CREATE UNIQUE INDEX user_account_login_uq ON core.user_account (tenant_id, lower(login));
CREATE INDEX user_account_employment_ix ON core.user_account (tenant_id, employment_id);

-- Roles are tenant-owned. System roles are SEEDED into each tenant from a template
-- (no global role rows), so composite FKs stay tenant-safe.
CREATE TABLE core.role (
  tenant_id    uuid NOT NULL,
  id           uuid NOT NULL DEFAULT sys.uuid_v7(),
  code         text NOT NULL,
  name_vi      text NOT NULL,
  name_en      text NOT NULL,
  is_system    boolean NOT NULL DEFAULT false,     -- seeded from template, not deletable
  template_version int,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  version      int NOT NULL DEFAULT 0,
  CONSTRAINT role_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT role_code_uq UNIQUE (tenant_id, code)
);

CREATE TABLE core.role_permission (
  tenant_id        uuid NOT NULL,
  role_id          uuid NOT NULL,
  permission_code  text NOT NULL,                  -- <module>.<function>.<action>, registered by modules
  field_mask_level text NOT NULL DEFAULT 'NONE',   -- NONE | PII | PII_AND_PAY  (masking views, DESIGN §9.4)
  CONSTRAINT role_permission_pk PRIMARY KEY (tenant_id, role_id, permission_code),
  CONSTRAINT role_permission_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES core.role (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT role_permission_mask_ck CHECK (field_mask_level IN ('NONE','PII','PII_AND_PAY'))
);

CREATE TABLE core.role_assignment (
  tenant_id     uuid NOT NULL,
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  user_id       uuid NOT NULL,
  role_id       uuid NOT NULL,
  scope_type    text NOT NULL,
  scope_ids     uuid[] NOT NULL DEFAULT '{}',      -- LE ids or org unit ids (subtree roots)
  include_subtree boolean NOT NULL DEFAULT true,
  valid_from    date NOT NULL,
  valid_to      date,
  granted_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid,
  CONSTRAINT role_assignment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT role_assignment_user_fk FOREIGN KEY (tenant_id, user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT role_assignment_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES core.role (tenant_id, id),
  CONSTRAINT role_assignment_scope_ck CHECK (scope_type IN ('TENANT','LEGAL_ENTITY','ORG_UNIT','SELF','DIRECT_REPORTS','ALL_REPORTS')),
  CONSTRAINT role_assignment_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX role_assignment_user_ix ON core.role_assignment (tenant_id, user_id);

-- =============================================================================
-- 9b. IMPORT ENGINE STAGING  (generic; DESIGN §9.6/§13, ADR-0037, GAP-005)
--     Replaces the Oracle stg_raw / stg_clean / mig design (D-09: no migration).
--     Used by every import type, including the go-live OPENING_BALANCE templates:
--     employee master, YTD income/PIT/SI, SI contribution history, leave balances,
--     loans. Core owns staging; the OWNER MODULE validates, reconciles and commits
--     through its ImportHandler in its own schema (no cross-schema access).
--     Staged payloads may contain P3 values (CCCD, tax code, bank account), so the
--     whole parsed row is encrypted with a per-job DEK (wrapped by the tenant KEK);
--     only non-sensitive preview fields are stored in clear. Rows are purged and the
--     job DEK destroyed after commit + 30 days (crypto-shred).
-- =============================================================================
CREATE TABLE core.import_job (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  import_type        text NOT NULL,                  -- registered by modules, e.g. OB_EMPLOYEE_MASTER, OB_YTD_PAYROLL,
                                                     -- OB_SI_HISTORY, OB_LEAVE_BALANCE, OB_LOAN, ATT_RESULT ...
  owner_module       text NOT NULL,                  -- module whose ImportHandler validates and commits
  mode               text NOT NULL DEFAULT 'STANDARD',
  template_version   text NOT NULL,
  legal_entity_id    uuid,                           -- NULL = tenant-wide import
  as_of_date         date,                           -- go-live date G: opening balances are "as of G"
  tax_year           smallint,                       -- YTD templates
  file_document_id   uuid,                           -- -> core document service (soft)
  file_sha256        text,
  status             text NOT NULL DEFAULT 'UPLOADED',
  row_count          int NOT NULL DEFAULT 0,
  error_count        int NOT NULL DEFAULT 0,
  warning_count      int NOT NULL DEFAULT 0,
  control_totals     jsonb,                          -- customer-declared totals (template control sheet), pii:P2
  wrapped_dek        bytea,                          -- per-job DEK for import_row.payload_enc; NULL after purge
  key_version        int NOT NULL DEFAULT 1,
  depends_on_job_id  uuid,                           -- e.g. YTD import requires the committed employee master
  signed_off_by      uuid,                           -- customer sign-off of the reconciliation report
  signed_off_at      timestamptz,
  committed_at       timestamptz,
  rolled_back_at     timestamptz,
  purge_due_at       timestamptz,                    -- committed_at + 30 days (staging rows + DEK)
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT import_job_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT import_job_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT import_job_depends_fk FOREIGN KEY (tenant_id, depends_on_job_id) REFERENCES core.import_job (tenant_id, id),
  CONSTRAINT import_job_module_ck CHECK (owner_module IN ('saas','core','hr','att','pay','rec')),
  CONSTRAINT import_job_mode_ck CHECK (mode IN ('STANDARD','OPENING_BALANCE')),
  CONSTRAINT import_job_status_ck CHECK (status IN ('UPLOADED','PARSING','PARSED','VALIDATING','VALIDATED',
                                                    'RECONCILED','SIGNED_OFF','COMMITTING','COMMITTED',
                                                    'FAILED','ROLLED_BACK','CANCELLED','PURGED')),
  CONSTRAINT import_job_asof_ck CHECK (mode <> 'OPENING_BALANCE' OR as_of_date IS NOT NULL),
  -- an opening-balance job can only be committed after the customer signed off the reconciliation
  CONSTRAINT import_job_signoff_ck CHECK (mode <> 'OPENING_BALANCE'
                                          OR status NOT IN ('SIGNED_OFF','COMMITTING','COMMITTED')
                                          OR signed_off_at IS NOT NULL)
);
CREATE INDEX import_job_type_ix   ON core.import_job (tenant_id, import_type, created_at);
CREATE INDEX import_job_purge_ix  ON core.import_job (tenant_id, purge_due_at) WHERE purge_due_at IS NOT NULL;
COMMENT ON TABLE core.import_job IS
  'Generic import job (ADR-0037). OPENING_BALANCE mode = go-live load as of as_of_date; commit gated by reconciliation sign-off.';
COMMENT ON COLUMN core.import_job.control_totals IS 'pii:P2 aggregate amounts declared by the customer';

CREATE TABLE core.import_row (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  import_job_id      uuid NOT NULL,
  sheet_name         text NOT NULL DEFAULT 'DATA',
  row_no             int  NOT NULL,                  -- row number in the uploaded sheet
  business_key       text,                           -- e.g. employee_no | employee_no + month; idempotent upsert key
  payload_enc        bytea NOT NULL,                 -- pii:P3 whole parsed row, AES-256-GCM with the job DEK
  preview            jsonb,                          -- non-sensitive fields only (no P3), for the preview grid
  status             text NOT NULL DEFAULT 'PARSED',
  messages           jsonb,                          -- [{code, severity, field, message_key, args}]
  target_entity      text,                           -- after commit, e.g. hr.employment (soft)
  target_id          uuid,                           -- soft reference into the owner module
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT import_row_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT import_row_job_fk FOREIGN KEY (tenant_id, import_job_id)
    REFERENCES core.import_job (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT import_row_uq UNIQUE (tenant_id, import_job_id, sheet_name, row_no),
  CONSTRAINT import_row_status_ck CHECK (status IN ('PARSED','VALID','WARNING','ERROR','COMMITTED','SKIPPED'))
);
-- duplicates inside one file are a validation error, so the business key is NOT unique here
CREATE INDEX import_row_key_ix    ON core.import_row (tenant_id, import_job_id, business_key);
CREATE INDEX import_row_status_ix ON core.import_row (tenant_id, import_job_id, status);
COMMENT ON COLUMN core.import_row.payload_enc IS 'pii:P3 AES-256-GCM, per-import-job DEK (import_job.wrapped_dek)';

-- Reconciliation report: customer control totals vs staged rows (before commit)
-- and vs target tables (after commit). Values are computed by the OWNER module's
-- ReconciliationProvider in its own schema and handed to Core through its API.
CREATE TABLE core.import_reconciliation (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  import_job_id      uuid NOT NULL,
  check_code         text NOT NULL,                  -- HEADCOUNT | ROW_COUNT | YTD_GROSS | YTD_TAXABLE | YTD_PIT |
                                                     -- YTD_SI_EE | SI_MONTHS | SI_BASIS | LEAVE_CLOSING | LOAN_OUTSTANDING ...
  grain_key          text NOT NULL,                  -- e.g. 'LE=HN01' | 'EMP=NV0012|M=2026-09'
  control_value      numeric(24,4),                  -- from the customer's control sheet
  staged_value       numeric(24,4),                  -- from validated staging rows
  committed_value    numeric(24,4),                  -- recomputed from target tables after commit
  tolerance          numeric(24,4) NOT NULL DEFAULT 0,
  status             text NOT NULL DEFAULT 'MISMATCH',
  waiver_reason      text,
  waived_by          uuid,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  CONSTRAINT import_reconciliation_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT import_reconciliation_job_fk FOREIGN KEY (tenant_id, import_job_id)
    REFERENCES core.import_job (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT import_reconciliation_uq UNIQUE (tenant_id, import_job_id, check_code, grain_key),
  CONSTRAINT import_reconciliation_status_ck CHECK (status IN ('MATCHED','MISMATCH','WAIVED')),
  CONSTRAINT import_reconciliation_tol_ck CHECK (tolerance >= 0),
  CONSTRAINT import_reconciliation_waiver_ck CHECK (status <> 'WAIVED' OR (waiver_reason IS NOT NULL AND waived_by IS NOT NULL))
);
COMMENT ON TABLE core.import_reconciliation IS
  'pii:P2 (per-employee amounts). Kept as go-live evidence for the payroll retention period (10 y) [verify-legal].';

-- =============================================================================
-- 10. AUDIT STORE  (schema `audit`; DESIGN §9.2, ADR-0027)
-- =============================================================================
SET search_path = audit, sys, public;

CREATE TABLE audit.audit_event (
  tenant_id         uuid        NOT NULL,
  id                uuid        NOT NULL DEFAULT sys.uuid_v7(),
  occurred_at       timestamptz NOT NULL DEFAULT now(),
  actor_type        text        NOT NULL,
  actor_id          uuid,                          -- user / operator / api client / system principal
  on_behalf_of_id   uuid,                          -- impersonated user or delegator
  operator_id       uuid,                          -- set during support sessions (BR-SAAS-035)
  support_grant_id  uuid,
  action            text        NOT NULL,          -- INSERT | UPDATE | DELETE | READ_SENSITIVE | APPROVE | EXPORT | LOGIN ...
  object_schema     text,
  object_type       text,
  object_id         uuid,
  legal_entity_id   uuid,
  purpose           text,                          -- app.purpose
  request_id        text,
  correlation_id    text,
  client_ip         inet,
  user_agent        text,
  change            jsonb,                         -- masked diff; encrypted columns as "<encrypted:changed>"
  is_sensitive_read boolean     NOT NULL DEFAULT false,
  -- seal columns (written only by yhrm_audit_sealer)
  chain_seq         bigint,
  prev_hash         bytea,
  row_hash          bytea,
  sealed_at         timestamptz,
  CONSTRAINT audit_event_pk PRIMARY KEY (tenant_id, id, occurred_at),
  CONSTRAINT audit_event_actor_ck CHECK (actor_type IN ('USER','OPERATOR','API_CLIENT','SYSTEM','IMPORT'))
) PARTITION BY RANGE (occurred_at);

CREATE INDEX audit_event_object_ix   ON audit.audit_event (tenant_id, object_type, object_id, occurred_at);
CREATE INDEX audit_event_actor_ix    ON audit.audit_event (tenant_id, actor_id, occurred_at);
CREATE INDEX audit_event_unsealed_ix ON audit.audit_event (tenant_id, id) WHERE sealed_at IS NULL;
CREATE INDEX audit_event_chain_ix    ON audit.audit_event (tenant_id, chain_seq) WHERE chain_seq IS NOT NULL;

CALL sys.create_range_partitions('audit.audit_event', 'month', current_date - 31, 4);

CREATE TABLE audit.chain_head (
  tenant_id    uuid        NOT NULL,
  last_seq     bigint      NOT NULL DEFAULT 0,
  last_hash    bytea       NOT NULL DEFAULT '\x'::bytea,     -- genesis = empty
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chain_head_pk PRIMARY KEY (tenant_id)
);

CREATE TABLE audit.chain_anchor (
  tenant_id     uuid        NOT NULL,
  anchor_date   date        NOT NULL,
  last_seq      bigint      NOT NULL,
  head_hash     bytea       NOT NULL,
  exported_uri  text,                                         -- WORM object (second VN DC)
  exported_at   timestamptz,
  CONSTRAINT chain_anchor_pk PRIMARY KEY (tenant_id, anchor_date)
);

-- Canonical, deterministic serialisation of an audit row (independent of DateStyle/TimeZone).
CREATE OR REPLACE FUNCTION audit.canonical(e audit.audit_event)
RETURNS text LANGUAGE sql STABLE AS $$
  SELECT jsonb_build_array(
           e.id, e.tenant_id, extract(epoch FROM e.occurred_at), e.actor_type, e.actor_id,
           e.on_behalf_of_id, e.operator_id, e.support_grant_id, e.action, e.object_schema,
           e.object_type, e.object_id, e.legal_entity_id, e.purpose, e.request_id,
           e.correlation_id, host(e.client_ip), e.change, e.is_sensitive_read)::text
$$;

-- Sealed rows are frozen (defence in depth on top of grants).
CREATE OR REPLACE FUNCTION audit.tg_audit_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'audit events cannot be deleted (archive by partition detach)' USING ERRCODE = '55000';
  END IF;
  IF OLD.sealed_at IS NOT NULL THEN
    RAISE EXCEPTION 'audit event % is sealed', OLD.id USING ERRCODE = '55000';
  END IF;
  IF (to_jsonb(NEW) - ARRAY['chain_seq','prev_hash','row_hash','sealed_at'])
     IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['chain_seq','prev_hash','row_hash','sealed_at']) THEN
    RAISE EXCEPTION 'only seal columns may be updated on audit events' USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_audit_guard BEFORE UPDATE OR DELETE ON audit.audit_event
  FOR EACH ROW EXECUTE FUNCTION audit.tg_audit_guard();

-- Asynchronous sealing (ADR-0027). Run by yhrm_audit_sealer per tenant every few seconds.
-- Chain order = seal order; late-committing rows are simply sealed in a later batch.
CREATE OR REPLACE FUNCTION audit.seal_batch(p_tenant uuid, p_limit int DEFAULT 5000)
RETURNS int LANGUAGE plpgsql AS $$
DECLARE
  h   audit.chain_head;
  r   audit.audit_event;
  n   int := 0;
  v_hash bytea;
BEGIN
  INSERT INTO audit.chain_head (tenant_id) VALUES (p_tenant) ON CONFLICT (tenant_id) DO NOTHING;
  SELECT * INTO h FROM audit.chain_head WHERE tenant_id = p_tenant FOR UPDATE;   -- one sealer per tenant

  FOR r IN
    SELECT * FROM audit.audit_event
     WHERE tenant_id = p_tenant AND sealed_at IS NULL
       AND occurred_at < now() - interval '2 seconds'
     ORDER BY id
     LIMIT p_limit
     FOR UPDATE
  LOOP
    h.last_seq := h.last_seq + 1;
    v_hash := digest(h.last_hash || convert_to(audit.canonical(r), 'UTF8'), 'sha256');
    UPDATE audit.audit_event
       SET chain_seq = h.last_seq, prev_hash = h.last_hash, row_hash = v_hash, sealed_at = now()
     WHERE tenant_id = r.tenant_id AND id = r.id AND occurred_at = r.occurred_at;
    h.last_hash := v_hash;
    n := n + 1;
  END LOOP;

  UPDATE audit.chain_head SET last_seq = h.last_seq, last_hash = h.last_hash, updated_at = now()
   WHERE tenant_id = p_tenant;
  RETURN n;
END $$;

-- Verification: returns the first broken sequence number, or NULL when intact.
CREATE OR REPLACE FUNCTION audit.verify_chain(p_tenant uuid, p_from bigint, p_to bigint)
RETURNS bigint LANGUAGE plpgsql STABLE AS $$
DECLARE
  r audit.audit_event;
  v_prev bytea;
  v_expected_seq bigint := p_from;
BEGIN
  SELECT row_hash INTO v_prev FROM audit.audit_event WHERE tenant_id = p_tenant AND chain_seq = p_from - 1;
  v_prev := coalesce(v_prev, '\x'::bytea);
  FOR r IN SELECT * FROM audit.audit_event
            WHERE tenant_id = p_tenant AND chain_seq BETWEEN p_from AND p_to ORDER BY chain_seq
  LOOP
    IF r.chain_seq <> v_expected_seq                                  -- gap = deleted row
       OR r.prev_hash IS DISTINCT FROM v_prev
       OR r.row_hash  IS DISTINCT FROM digest(v_prev || convert_to(audit.canonical(r), 'UTF8'), 'sha256') THEN
      RETURN v_expected_seq;
    END IF;
    v_prev := r.row_hash;
    v_expected_seq := v_expected_seq + 1;
  END LOOP;
  RETURN CASE WHEN v_expected_seq = p_to + 1 THEN NULL ELSE v_expected_seq END;
END $$;

-- Generic row-change audit trigger for configuration and HR master tables.
-- Encrypted columns (*_enc, *_bidx) are never copied, only flagged as changed.
-- Bulk loads (opening-balance import, ADR-0037) may set app.audit_mode = 'BATCH' and
-- write one summary event per import chunk instead (actor_type IMPORT, still audited,
-- not per row).
CREATE OR REPLACE FUNCTION audit.tg_row_audit()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  o jsonb := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) END;
  n jsonb := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) END;
  d jsonb := '{}'::jsonb;
  k text;
  v_actor uuid := sys.current_user_id();
BEGIN
  IF current_setting('app.audit_mode', true) = 'BATCH' THEN
    RETURN NULL;
  END IF;
  FOR k IN SELECT jsonb_object_keys(coalesce(n, o)) LOOP
    CONTINUE WHEN k IN ('updated_at','updated_by','version','valid_during','created_at','created_by');
    IF (o -> k) IS DISTINCT FROM (n -> k) THEN
      IF k ~ '_(enc|bidx)$' THEN
        d := d || jsonb_build_object(k, '<encrypted:changed>');
      ELSE
        d := d || jsonb_build_object(k, jsonb_build_object('old', o -> k, 'new', n -> k));
      END IF;
    END IF;
  END LOOP;
  IF d = '{}'::jsonb THEN
    RETURN NULL;
  END IF;
  INSERT INTO audit.audit_event (tenant_id, actor_type, actor_id, action, object_schema, object_type,
                                 object_id, legal_entity_id, purpose, request_id, change)
  VALUES ((coalesce(n, o) ->> 'tenant_id')::uuid,
          CASE WHEN v_actor IS NULL THEN 'SYSTEM' ELSE 'USER' END, v_actor,
          TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME,
          (coalesce(n, o) ->> 'id')::uuid,
          (coalesce(n, o) ->> 'legal_entity_id')::uuid,
          sys.current_purpose(), sys.current_request_id(), d);
  RETURN NULL;
END $$;

CREATE OR REPLACE PROCEDURE audit.attach_row_audit(p_table regclass)
LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE format('DROP TRIGGER IF EXISTS trg_row_audit ON %s', p_table);
  EXECUTE format('CREATE TRIGGER trg_row_audit AFTER INSERT OR UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION audit.tg_row_audit()', p_table);
END $$;

-- RLS on audit: tenant isolation for app roles; sealer reads/updates all tenants.
CALL sys.enable_tenant_rls('audit.audit_event');
CREATE POLICY sealer_select ON audit.audit_event FOR SELECT TO yhrm_audit_sealer USING (true);
CREATE POLICY sealer_update ON audit.audit_event FOR UPDATE TO yhrm_audit_sealer USING (true) WITH CHECK (true);

GRANT USAGE ON SCHEMA audit TO yhrm_app, yhrm_audit_sealer;
GRANT INSERT ON audit.audit_event TO yhrm_app;                             -- every module, append-only
GRANT SELECT ON audit.audit_event TO yhrm_app_core;                        -- audit query API is Core's
GRANT SELECT, UPDATE (chain_seq, prev_hash, row_hash, sealed_at) ON audit.audit_event TO yhrm_audit_sealer;
GRANT SELECT, INSERT, UPDATE ON audit.chain_head, audit.chain_anchor TO yhrm_audit_sealer;
GRANT SELECT ON audit.chain_anchor TO yhrm_app_core;
GRANT EXECUTE ON FUNCTION audit.seal_batch(uuid, int) TO yhrm_audit_sealer;
GRANT EXECUTE ON FUNCTION audit.verify_chain(uuid, bigint, bigint), audit.canonical(audit.audit_event)
  TO yhrm_audit_sealer, yhrm_app_core;
GRANT EXECUTE ON FUNCTION audit.tg_row_audit() TO yhrm_app;
REVOKE EXECUTE ON FUNCTION audit.seal_batch(uuid, int) FROM PUBLIC;

-- =============================================================================
-- 11. RLS, triggers, grants for core
-- =============================================================================
SET search_path = core, sys, public;

-- Tenant-owned tables.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'core.company_group','core.legal_entity','core.legal_entity_version','core.legal_entity_bank_account',
    'core.location','core.location_version','core.cost_centre','core.org_unit_type','core.org_unit',
    'core.org_unit_version','core.job_family','core.job_title','core.grade_structure','core.grade',
    'core.position','core.position_version','core.user_account','core.role','core.role_permission',
    'core.role_assignment','core.import_job','core.import_row','core.import_reconciliation']
  LOOP
    CALL sys.enable_tenant_rls(t::regclass);
  END LOOP;
END $$;

-- Global + tenant-overridable.
CALL sys.enable_tenant_or_global_rls('core.code_value');
CALL sys.enable_tenant_or_global_rls('core.exchange_rate');

-- Touch triggers on mutable tables.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'core.code_value','core.company_group','core.legal_entity','core.legal_entity_version',
    'core.legal_entity_bank_account','core.location','core.location_version','core.cost_centre',
    'core.org_unit_version','core.job_title','core.position_version','core.user_account','core.role',
    'core.import_job','core.import_row','core.import_reconciliation']
  LOOP
    CALL sys.attach_touch_trigger(t::regclass);
  END LOOP;
END $$;

-- Row audit on configuration that affects calculations or access (NFR-CORE-008).
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'core.legal_entity_version','core.legal_entity_bank_account','core.location_version',
    'core.org_unit_version','core.position_version','core.role_permission','core.role_assignment',
    'core.user_account','core.code_value','core.import_job','core.import_reconciliation']
  LOOP
    CALL audit.attach_row_audit(t::regclass);
  END LOOP;
END $$;

-- Global master data: SELECT for every module; written by the release job (owner/platform tooling).
-- X-02 (accepted 2026-10-16): this is the ONLY read exception to "a module role has no grant
-- on another module's schema". It is read-only and limited to the reference tables below
-- (plus the tenant's own saas.tenant row, 001_saas.sql). The allow-list is enforced by
-- sys.cross_schema_grant_violations() (end of this file) in the CI DB test suite and by an
-- ArchUnit rule on the Java side; adding a table here requires an ADR-0021 amendment.
GRANT SELECT ON core.country, core.currency, core.admin_unit, core.admin_unit_mapping, core.bank,
               core.bank_branch, core.code_list, core.code_value, core.v_code_value_effective,
               core.exchange_rate TO yhrm_app;
GRANT SELECT, INSERT, UPDATE ON core.country, core.currency, core.admin_unit, core.admin_unit_mapping,
               core.bank, core.bank_branch, core.code_list TO yhrm_platform;

-- Core module runtime: full DML on its tenant tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON
  core.company_group, core.legal_entity, core.legal_entity_version, core.legal_entity_bank_account,
  core.location, core.location_version, core.cost_centre, core.org_unit_type, core.org_unit,
  core.org_unit_version, core.job_family, core.job_title, core.grade_structure, core.grade,
  core.position, core.position_version, core.user_account, core.role, core.role_permission,
  core.role_assignment, core.code_value, core.exchange_rate,
  core.import_job, core.import_row, core.import_reconciliation
TO yhrm_app_core;
GRANT EXECUTE ON FUNCTION core.org_tree_as_of(date, uuid) TO yhrm_app_core;

-- Outbox / inbox for Core (core.LegalEntityChanged, core.OrgUnitChanged, ...).
CALL sys.create_outbox_inbox('core', 'yhrm_app_core');

-- =============================================================================
-- 12. CROSS-SCHEMA GRANT GUARD  (X-02, ADR-0021/0035)
--     Returns every table/view privilege that a module app role (or the yhrm_app
--     group role) holds OUTSIDE its own schema and that is not on the allow-list.
--     The CI DB test asserts: SELECT count(*) FROM sys.cross_schema_grant_violations() = 0.
--     Reads pg_class.relacl directly, so the result does not depend on the caller's
--     role memberships (information_schema would hide grants of other grantors).
-- =============================================================================
CREATE OR REPLACE FUNCTION sys.cross_schema_grant_violations()
RETURNS TABLE (grantee text, table_schema text, table_name text, privilege_type text)
LANGUAGE sql STABLE AS $$
  WITH module_role(role_name, own_schema) AS (
    VALUES ('yhrm_app_saas','saas'), ('yhrm_app_core','core'), ('yhrm_app_hr','hr'),
           ('yhrm_app_att','att'),   ('yhrm_app_pay','pay'),   ('yhrm_app_rec','rec'),
           ('yhrm_app', NULL)                                  -- group role: owns no schema
  ),
  allowed(table_schema, table_name, privilege_type) AS (
    VALUES
      -- X-02: read-only global reference data owned by Core
      ('core','country','SELECT'), ('core','currency','SELECT'), ('core','admin_unit','SELECT'),
      ('core','admin_unit_mapping','SELECT'), ('core','bank','SELECT'), ('core','bank_branch','SELECT'),
      ('core','code_list','SELECT'), ('core','code_value','SELECT'),
      ('core','v_code_value_effective','SELECT'), ('core','exchange_rate','SELECT'),
      -- X-02: read-only SaaS reference (own tenant row only, enforced by RLS policy tenant_self_read)
      ('saas','tenant','SELECT'),
      -- platform plumbing (not data reads; ADR-0011 transactional job enqueue, ADR-0027 audit append)
      ('saas','job','SELECT'), ('saas','job','INSERT'),
      ('audit','audit_event','INSERT')
  ),
  granted AS (
    SELECT r.rolname::text AS grantee, n.nspname::text AS table_schema,
           c.relname::text AS table_name, a.privilege_type::text AS privilege_type
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     CROSS JOIN LATERAL aclexplode(c.relacl) a
      JOIN pg_roles r ON r.oid = a.grantee
     WHERE c.relkind IN ('r','p','v','m','f')
       AND NOT c.relispartition
       AND n.nspname IN ('saas','core','audit','hr','att','pay','rec')
  )
  SELECT g.grantee, g.table_schema, g.table_name, g.privilege_type
    FROM granted g
    JOIN module_role m ON m.role_name = g.grantee
   WHERE g.table_schema IS DISTINCT FROM m.own_schema
     AND NOT (m.role_name = 'yhrm_app_core' AND g.table_schema = 'audit')   -- Core owns the audit component
     AND NOT EXISTS (SELECT 1 FROM allowed al
                      WHERE al.table_schema = g.table_schema
                        AND al.table_name = g.table_name
                        AND al.privilege_type = g.privilege_type)
   ORDER BY 1, 2, 3, 4
$$;
COMMENT ON FUNCTION sys.cross_schema_grant_violations() IS
  'X-02 guard: module-role grants outside the own schema that are not allow-listed. Must return 0 rows in CI.';

RESET ROLE;
