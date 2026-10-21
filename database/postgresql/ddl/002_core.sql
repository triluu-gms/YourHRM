-- =============================================================================
-- YourHRM  ·  002_core.sql   (owner module: Core; `audit` component)
-- Global master data, tenant profile, legal entity, location, cost centre,
-- org tree (as-of, ltree paths), job architecture, grades catalogue, positions,
-- users/roles/permissions/data scope, employee categories, generic import staging
-- (opening-balance import), workflow engine, notification centre, document service,
-- settings, numbering, custom fields, reorg packages, export jobs, SoD rules,
-- the audit store with hash-chain sealing, Core's outbox/inbox and the X-02
-- cross-schema grant guard.
-- DESIGN.md §2-§5, §7.2, §9, §13; ADR-0020/0021/0022/0024/0027/0028/0035/0037.
-- v0.2 (2026-10-16): import staging (ADR-0037), X-02 guard, actor IMPORT. NOT re-tested
-- after these changes (no PostgreSQL available locally); reviewed by read-through only.
--
-- v0.4 (2026-10-17, DDL review round). Phase per table is marked [P1] / [P2] below.
--   Review fixes:
--   * H7  legal_entity_version: pit_declaration_frequency (MONTHLY|QUARTERLY) and
--         oa_od_rate (sys.rate, BHTNLD-BNN rate) + decision ref/doc, next to
--         tax_authority_code (R3-10, GAP-049, BR-CORE-002).
--   * M2  location_version.min_wage_region_override REMOVED: the region and any
--         location-level override belong to the Legal Parameter Service
--         (saas.legal_param_override LOCATION scope, BR-CORE-006); admin_unit_code kept.
--   * M4  role_assignment.scope_type adds 'LOCATION' (BR-CORE-032).
--   New Core tables (BRD 01-core §4/§6, DESIGN §7.2):
--   * permission (module-registered catalogue; role_permission now FKs it), employee_category,
--     user_account.identity_id (Keycloak link, ADR-0005).
--   * Workflow engine (ADR-0007): process_type, workflow_definition, workflow_definition_version
--     (steps jsonb), workflow_instance, workflow_task, workflow_action_log (append-only),
--     workflow_callback (idempotent module callbacks), approval_delegation [P2];
--     SLA / escalation columns are nullable [P2].
--   * Notification centre: notification_template (global + tenant override),
--     notification + notification_delivery (monthly partitioned; EMAIL/IN_APP in P1).
--   * Document service (metadata only; binaries in SaaS object storage): document,
--     document_version, document_template, document_template_version (DOCX/HTML -> PDF).
--   * setting_definition / setting_value (tenant / LE / location), sequence_definition +
--     sequence_counter (gap-free numbering under a row lock, core.next_sequence_value()).
--   * custom_field_def / custom_field_value [P2], reorg_package / reorg_change /
--     reorg_mapping [P2], export_job, sod_rule [P2].
--   * Business-day / holiday calendars are NOT here: T&A owns calendars (att schema).
--   * X-02 allow-list unchanged (no new cross-schema grants).
--   Smoke test (2026-10-17): 000 -> 001 -> 002 load cleanly on PGlite 0.5.10 (PostgreSQL 18.6
--   in WASM; pg_stat_statements skipped, toast compression pglz) and 39/39 assertions pass:
--   table presence, forced RLS, cross-tenant isolation + fail-closed, workflow FK chain and
--   idempotency keys, append-only trails, gap-free numbering, M2/M3/M4/H7, X-02 guard = 0.
--   Uses only PG 15+ features (NULLS NOT DISTINCT, security_invoker views); a run on real
--   PostgreSQL 16/17 is still required in CI (Testcontainers).
--
-- v0.5 (2026-10-21, sponsor decisions on the DDL review, DESIGN §15.1 D1-D11):
--   * D9  tenant purge: new core.tg_append_only() replaces sys.tg_forbid_change() on
--         core.workflow_action_log; core.tg_workflow_version_guard() lets a non-DRAFT
--         version be deleted. In both cases only when sys.purge_bypass(OLD.tenant_id)
--         is true (member of yhrm_purger + app.purge_tenant_id = tenant, contract in
--         000). UPDATE stays blocked. audit.audit_event keeps NO bypass (retained by
--         law; comment at audit.tg_audit_guard()).
--   * D6  no masking view of core is granted to yhrm_reporting_ro (there was none);
--         reporting goes through the reporting store (DESIGN §11), not OLTP views.
--   * D4  the reporting feed (publication pub_rpt_core, yhrm_cdc grants and read
--         policies) is in 007_publications.sql; M2 (no min_wage_region_override) is
--         reflected there and in reporting/100_rpt.sql (D11).
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
-- 0. Module trigger functions
-- =============================================================================
-- D9 (2026-10-21): append-only trail for Core tables. UPDATE is always rejected;
-- DELETE is rejected EXCEPT inside the controlled tenant purge (DESIGN §3.7), i.e.
-- when sys.purge_bypass(OLD.tenant_id) is true: the session runs as a member of the
-- NOLOGIN role yhrm_purger AND app.purge_tenant_id = that tenant (contract in
-- 000_extensions_roles.sql). The purge is the ONLY path that removes these rows.
-- (Same contract as hr.tg_append_only / rec.tg_append_only. The module-local copy
-- depends only on sys.purge_bypass(); 000 v0.5 gives sys.tg_forbid_change() the same
-- bypass, so the two may be consolidated later.)
CREATE OR REPLACE FUNCTION core.tg_append_only()
RETURNS trigger LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' AND sys.purge_bypass(OLD.tenant_id) THEN
    RETURN OLD;                                             -- D9: tenant purge only
  END IF;
  RAISE EXCEPTION '%.% is append-only (% rejected)', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
    USING ERRCODE = '55000';
END $$;

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
  tax_authority_code text,                         -- managing tax authority (cơ quan thuế quản lý)
  pit_declaration_frequency text NOT NULL DEFAULT 'MONTHLY',  -- H7/R3-10: kỳ khai thuế TNCN, per tax year via versions
  oa_od_rate         sys.rate,                     -- H7/R3-10: BHTNLĐ-BNN employer rate applied (e.g. 0.005 / 0.003);
                                                   --   NULL = statutory standard from the legal pack; value must be
                                                   --   one the pack allows [verify-legal] (checked by the service)
  oa_od_decision_ref text,                         -- decision no. + issuing body for a reduced rate
  oa_od_decision_doc_id uuid,                      -- evidence file -> core.document
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
  CONSTRAINT legal_entity_version_pit_freq_ck CHECK (pit_declaration_frequency IN ('MONTHLY','QUARTERLY')),
  CONSTRAINT legal_entity_version_oa_od_ck CHECK (oa_od_rate IS NULL OR (oa_od_rate >= 0 AND oa_od_rate < 0.05)),
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
  admin_unit_code      text REFERENCES core.admin_unit (admin_unit_code),  -- drives the min-wage region lookup in the
                                                   -- Legal Parameter Service (admin unit -> region map). v0.4 M2: no region
                                                   -- override here; a permitted override is saas.legal_param_override
                                                   -- (LOCATION scope), BR-CORE-006.
  address_line         text,
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

-- Employee category [P1] (BR-CORE-026): official, probation, seasonal, intern, expat,
-- collaborator (CTV) ... Used as a condition by workflow definitions, data scope rules
-- and payroll eligibility. The behaviour flags are what other modules key on; the code
-- itself is tenant-chosen. People stores employee_category_id on the assignment (soft).
CREATE TABLE core.employee_category (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  code              text NOT NULL,
  name_vi           text NOT NULL,
  name_en           text,
  is_probation      boolean NOT NULL DEFAULT false,
  is_seasonal       boolean NOT NULL DEFAULT false,     -- thời vụ / short fixed-term
  is_intern         boolean NOT NULL DEFAULT false,
  is_expat          boolean NOT NULL DEFAULT false,     -- foreign national (work permit, PIT residency rules)
  is_collaborator   boolean NOT NULL DEFAULT false,     -- CTV / service contract, not a labour contract
  counts_in_headcount boolean NOT NULL DEFAULT true,
  attributes        jsonb NOT NULL DEFAULT '{}'::jsonb, -- further tenant flags
  sort_order        int  NOT NULL DEFAULT 0,
  is_active         boolean NOT NULL DEFAULT true,
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT employee_category_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT employee_category_code_uq UNIQUE (tenant_id, code)
);
COMMENT ON TABLE core.employee_category IS '[P1] Employee category catalogue with behaviour flags (BR-CORE-026).';

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
  identity_id    uuid,                             -- Keycloak user id (ADR-0005); credentials/MFA live in Keycloak only
  locale         text NOT NULL DEFAULT 'vi-VN',
  status         text NOT NULL DEFAULT 'ACTIVE',
  expires_at     timestamptz,
  access_revoked_at timestamptz,                   -- set with FORMER_EMPLOYEE/DEACTIVATED; SaaS revokes sessions
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
CREATE UNIQUE INDEX user_account_identity_uq ON core.user_account (tenant_id, identity_id) WHERE identity_id IS NOT NULL;
COMMENT ON COLUMN core.user_account.email  IS 'pii:P1 work e-mail (personal e-mail for former employees, OD-8: P2)';
COMMENT ON COLUMN core.user_account.mobile IS 'pii:P2';

-- Permission catalogue [P1]  (pure global, no tenant_id; BR-CORE-031).
-- Each module REGISTERS its permissions at deploy time (release job / repeatable
-- migration of the owning module), so codes are identical in every tenant and every
-- database. Tenants never add permissions; they compose them into roles.
-- Only Core reads it (role administration UI, authorisation service): no X-02 entry.
CREATE TABLE core.permission (
  permission_code  text NOT NULL,                  -- <module>.<function>.<action>, e.g. pay.run.approve
  owner_module     text NOT NULL,
  function_code    text NOT NULL,                  -- menu / resource, e.g. pay.run
  action           text NOT NULL,
  name_vi          text NOT NULL,
  name_en          text NOT NULL,
  is_sensitive     boolean NOT NULL DEFAULT false, -- view-sensitive / compensation: every use audited (BR-CORE-034)
  is_privileged    boolean NOT NULL DEFAULT false, -- granting needs CORE_ROLE_GRANT approval (BR-CORE-036)
  requires_step_up boolean NOT NULL DEFAULT false, -- MFA step-up at use (ADR-0005)
  registered_release text,                         -- platform release that registered / last changed it
  is_active        boolean NOT NULL DEFAULT true,  -- deprecated permissions stay for history, never deleted
  CONSTRAINT permission_pk PRIMARY KEY (permission_code),
  CONSTRAINT permission_code_ck CHECK (permission_code ~ '^[a-z]+(\.[a-z0-9_]+)+$'),
  CONSTRAINT permission_module_ck CHECK (owner_module IN ('saas','core','hr','att','pay','rec')),
  CONSTRAINT permission_action_ck CHECK (action IN ('VIEW','CREATE','UPDATE','DELETE','APPROVE','EXPORT','IMPORT',
                                                    'VIEW_SENSITIVE','EXECUTE','ADMIN'))
);
COMMENT ON TABLE core.permission IS '[P1] Global permission catalogue registered by modules at deploy (BR-CORE-031).';

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
  permission_code  text NOT NULL,                  -- -> core.permission (global catalogue, same schema)
  field_mask_level text NOT NULL DEFAULT 'NONE',   -- NONE | PII | PII_AND_PAY  (masking views, DESIGN §9.4)
  CONSTRAINT role_permission_pk PRIMARY KEY (tenant_id, role_id, permission_code),
  CONSTRAINT role_permission_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES core.role (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT role_permission_permission_fk FOREIGN KEY (permission_code) REFERENCES core.permission (permission_code),
  CONSTRAINT role_permission_mask_ck CHECK (field_mask_level IN ('NONE','PII','PII_AND_PAY'))
);

CREATE TABLE core.role_assignment (
  tenant_id     uuid NOT NULL,
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  user_id       uuid NOT NULL,
  role_id       uuid NOT NULL,
  scope_type    text NOT NULL,
  scope_ids     uuid[] NOT NULL DEFAULT '{}',      -- LE ids, location ids or org unit ids (subtree roots), per scope_type
  include_subtree boolean NOT NULL DEFAULT true,
  valid_from    date NOT NULL,
  valid_to      date,
  granted_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid,
  CONSTRAINT role_assignment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT role_assignment_user_fk FOREIGN KEY (tenant_id, user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT role_assignment_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES core.role (tenant_id, id),
  CONSTRAINT role_assignment_scope_ck CHECK (scope_type IN ('TENANT','LEGAL_ENTITY','LOCATION','ORG_UNIT',
                                                            'SELF','DIRECT_REPORTS','ALL_REPORTS')),   -- v0.4 M4: LOCATION
  CONSTRAINT role_assignment_scope_ids_ck CHECK (
    (scope_type IN ('LEGAL_ENTITY','LOCATION','ORG_UNIT')) = (cardinality(scope_ids) > 0)),
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
-- 9c. WORKFLOW & APPROVAL ENGINE  (ADR-0007; BR-CORE-050..061)            [P1]
--     Table-driven engine. The owner module starts an instance through
--     WorkflowService.start(processType, subjectRef, context) and reacts to
--     core.WorkflowStepCompleted / core.WorkflowCompleted in its own transaction
--     (inbox dedupe). Core never touches the subject's tables: subject ids are soft.
--     P1: sequential steps, SINGLE / ANY_OF / ALL_OF / FYI, conditional skip, return,
--     withdraw, reassign, request-info. P2: parallel branches, approval delegation,
--     SLA timers / reminders / escalation (columns exist, nullable), e-mail action links.
-- =============================================================================

-- Process types are registered by modules at deploy (pure global, like core.permission).
CREATE TABLE core.process_type (
  process_type_code   text NOT NULL,               -- e.g. att.LEAVE_REQUEST, pay.RUN_APPROVAL, hr.ASSIGNMENT_CHANGE
  owner_module        text NOT NULL,
  name_vi             text NOT NULL,
  name_en             text NOT NULL,
  summary_schema      jsonb NOT NULL DEFAULT '{}'::jsonb,   -- JSON schema of instance.request_summary
  condition_fields    jsonb NOT NULL DEFAULT '[]'::jsonb,   -- fields usable in definition conditions
  allowed_actions     text[] NOT NULL DEFAULT ARRAY['APPROVE','REJECT','RETURN','REQUEST_INFO','WITHDRAW','COMMENT'],
  callback_event_type text,                        -- event the owner consumes, default core.WorkflowCompleted
  allows_auto_approve boolean NOT NULL DEFAULT false,   -- SLA breach may auto-approve (P2, BR-CORE-057)
  requires_step_up    boolean NOT NULL DEFAULT false,   -- sensitive types (payroll release ...)
  registered_release  text,
  is_active           boolean NOT NULL DEFAULT true,
  CONSTRAINT process_type_pk PRIMARY KEY (process_type_code),
  CONSTRAINT process_type_module_ck CHECK (owner_module IN ('saas','core','hr','att','pay','rec')),
  CONSTRAINT process_type_code_ck CHECK (process_type_code ~ '^[a-z]+\.[A-Z][A-Z0-9_]*$')
);
COMMENT ON TABLE core.process_type IS '[P1] Global catalogue of workflow process types registered by modules (BR-CORE-050).';

CREATE TABLE core.workflow_definition (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  process_type_code  text NOT NULL,
  code               text NOT NULL,
  name_vi            text NOT NULL,
  name_en            text,
  match_priority     int  NOT NULL DEFAULT 100,    -- lower wins when several definitions' conditions match
  status             text NOT NULL DEFAULT 'ACTIVE',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT workflow_definition_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT workflow_definition_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT workflow_definition_type_fk FOREIGN KEY (process_type_code) REFERENCES core.process_type (process_type_code),
  CONSTRAINT workflow_definition_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX workflow_definition_type_ix ON core.workflow_definition (tenant_id, process_type_code, match_priority);

-- Versioned definition. Running instances keep their version (ADR-0007). A PUBLISHED
-- version is immutable except for retiring it / closing valid_to; edit = new version.
CREATE TABLE core.workflow_definition_version (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  workflow_definition_id uuid NOT NULL,
  version_no             int  NOT NULL,
  status                 text NOT NULL DEFAULT 'DRAFT',
  conditions             jsonb NOT NULL DEFAULT '{}'::jsonb,   -- applicability: LE, org unit, location, employee category, grade, request fields
  steps                  jsonb NOT NULL,                       -- ordered steps, see comment
  on_resolver_failure    text NOT NULL DEFAULT 'ESCALATE_UP',  -- BR-CORE-056 fallback
  reresolve_on_org_change boolean NOT NULL DEFAULT false,      -- BR-CORE-058
  valid_from             date NOT NULL,
  valid_to               date,
  valid_during           daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  published_at           timestamptz,
  published_by           uuid,
  content_sha256         text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT workflow_definition_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT workflow_definition_version_def_fk FOREIGN KEY (tenant_id, workflow_definition_id)
    REFERENCES core.workflow_definition (tenant_id, id),
  CONSTRAINT workflow_definition_version_uq UNIQUE (tenant_id, workflow_definition_id, version_no),
  CONSTRAINT workflow_definition_version_status_ck CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
  CONSTRAINT workflow_definition_version_steps_ck CHECK (jsonb_typeof(steps) = 'array' AND jsonb_array_length(steps) > 0),
  CONSTRAINT workflow_definition_version_fallback_ck CHECK (on_resolver_failure IN ('SKIP','ESCALATE_UP','LE_HR_ADMIN','FAIL')),
  CONSTRAINT workflow_definition_version_pub_ck CHECK (status = 'DRAFT' OR (published_at IS NOT NULL AND content_sha256 IS NOT NULL)),
  CONSTRAINT workflow_definition_version_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT workflow_definition_version_period_ex EXCLUDE USING gist
    (tenant_id WITH =, workflow_definition_id WITH =, valid_during WITH &&) WHERE (status = 'PUBLISHED')
);
COMMENT ON COLUMN core.workflow_definition_version.steps IS
  'Ordered array: [{"step_key","name_vi","name_en","step_type":"SINGLE|ANY_OF|ALL_OF|FYI|PARALLEL(P2)",'
  '"resolver":{"type":"LINE_MANAGER|MANAGER_N_UP|ORG_UNIT_MANAGER|POSITION_HOLDER|ROLE_IN_SCOPE|USER|REQUESTER_PICK|REQUEST_FIELD|FUNCTIONAL_MANAGER",...},'
  '"skip_if":<YFL expr>,"allow_return":bool,"allow_delegate":bool,"sla_hours":int(P2),"reminders":[...](P2),"on_breach":"ESCALATE|REASSIGN|AUTO_APPROVE|AUTO_REJECT"(P2)}]';

CREATE OR REPLACE FUNCTION core.tg_workflow_version_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  -- valid_during is generated (NULL in NEW inside BEFORE triggers), see sys.tg_bitemporal_guard
  mutable constant text[] := ARRAY['status','valid_to','valid_during','updated_at','updated_by','version'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    -- D9: a published/retired version may only be removed by the tenant purge
    IF OLD.status <> 'DRAFT' AND NOT sys.purge_bypass(OLD.tenant_id) THEN
      RAISE EXCEPTION 'workflow definition version % is % and cannot be deleted', OLD.id, OLD.status USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.status = 'RETIRED'
     OR (OLD.status = 'PUBLISHED' AND (NEW.status NOT IN ('PUBLISHED','RETIRED')
                                       OR (to_jsonb(NEW) - mutable) IS DISTINCT FROM (to_jsonb(OLD) - mutable))) THEN
    RAISE EXCEPTION 'workflow definition version % is % and immutable; create a new version', OLD.id, OLD.status
      USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_version_guard BEFORE UPDATE OR DELETE ON core.workflow_definition_version
  FOR EACH ROW EXECUTE FUNCTION core.tg_workflow_version_guard();

-- Approval delegation [P2] (BR-CORE-055). Created now so tasks can reference it.
CREATE TABLE core.approval_delegation (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  delegator_user_id    uuid NOT NULL,
  delegate_user_id     uuid NOT NULL,
  process_type_codes   text[],                     -- NULL = all process types
  include_pending      boolean NOT NULL DEFAULT true,
  valid_from           date NOT NULL,
  valid_to             date,
  valid_during         daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  reason               text,
  source               text NOT NULL DEFAULT 'USER', -- USER | LEAVE_SUGGESTION (from att leave event) | ADMIN
  status               text NOT NULL DEFAULT 'ACTIVE',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT approval_delegation_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT approval_delegation_from_fk FOREIGN KEY (tenant_id, delegator_user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT approval_delegation_to_fk   FOREIGN KEY (tenant_id, delegate_user_id)  REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT approval_delegation_self_ck CHECK (delegator_user_id <> delegate_user_id),
  CONSTRAINT approval_delegation_source_ck CHECK (source IN ('USER','LEAVE_SUGGESTION','ADMIN')),
  CONSTRAINT approval_delegation_status_ck CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
  CONSTRAINT approval_delegation_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX approval_delegation_from_ix ON core.approval_delegation USING gist (tenant_id, delegator_user_id, valid_during)
  WHERE status = 'ACTIVE';
COMMENT ON TABLE core.approval_delegation IS '[P2] Approval delegation; one level only (no chaining), enforced by the engine.';

CREATE TABLE core.workflow_instance (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  definition_version_id  uuid NOT NULL,
  process_type_code      text NOT NULL,            -- denormalised for the inbox and reports
  owner_module           text NOT NULL,
  subject_type           text NOT NULL,            -- e.g. att.leave_request (soft)
  subject_id             uuid NOT NULL,            -- soft reference into the owner module
  subject_version        int,                      -- owner's optimistic version at submit (stale-callback guard)
  idempotency_key        text NOT NULL,            -- start() is idempotent per (process type, key)
  requester_user_id      uuid NOT NULL,
  on_behalf_of_user_id   uuid,                     -- HR submitting for an employee
  subject_person_id      uuid,                     -- -> hr.person (soft), for DSR / my-requests
  legal_entity_id        uuid,
  org_unit_id            uuid,
  request_summary        jsonb NOT NULL DEFAULT '{}'::jsonb,   -- per process_type.summary_schema, pii:P2
  approver_chain_snapshot jsonb,                   -- resolved chain at submit (BR-CORE-058)
  status                 text NOT NULL DEFAULT 'IN_PROGRESS',
  current_step_no        int,
  outcome                text,
  submitted_at           timestamptz NOT NULL DEFAULT now(),
  completed_at           timestamptz,
  due_at                 timestamptz,              -- P2 overall SLA
  sla_breached_at        timestamptz,              -- P2
  correlation_id         text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT workflow_instance_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT workflow_instance_def_fk FOREIGN KEY (tenant_id, definition_version_id)
    REFERENCES core.workflow_definition_version (tenant_id, id),
  CONSTRAINT workflow_instance_type_fk FOREIGN KEY (process_type_code) REFERENCES core.process_type (process_type_code),
  CONSTRAINT workflow_instance_requester_fk FOREIGN KEY (tenant_id, requester_user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT workflow_instance_obo_fk FOREIGN KEY (tenant_id, on_behalf_of_user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT workflow_instance_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT workflow_instance_org_fk FOREIGN KEY (tenant_id, org_unit_id) REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT workflow_instance_idem_uq UNIQUE (tenant_id, process_type_code, idempotency_key),
  CONSTRAINT workflow_instance_module_ck CHECK (owner_module IN ('saas','core','hr','att','pay','rec')),
  CONSTRAINT workflow_instance_status_ck CHECK (status IN ('IN_PROGRESS','RETURNED','INFO_REQUESTED','APPROVED',
                                                           'REJECTED','WITHDRAWN','CANCELLED','ERROR')),
  CONSTRAINT workflow_instance_outcome_ck CHECK (outcome IS NULL OR outcome IN ('APPROVED','REJECTED','WITHDRAWN','CANCELLED')),
  CONSTRAINT workflow_instance_done_ck CHECK ((status IN ('APPROVED','REJECTED','WITHDRAWN','CANCELLED'))
                                              = (completed_at IS NOT NULL AND outcome IS NOT NULL))
);
-- at most one open instance per subject and process type
CREATE UNIQUE INDEX workflow_instance_open_uq ON core.workflow_instance (tenant_id, process_type_code, subject_id)
  WHERE status IN ('IN_PROGRESS','RETURNED','INFO_REQUESTED');
CREATE INDEX workflow_instance_requester_ix ON core.workflow_instance (tenant_id, requester_user_id, submitted_at);
CREATE INDEX workflow_instance_subject_ix   ON core.workflow_instance (tenant_id, subject_type, subject_id);
COMMENT ON COLUMN core.workflow_instance.request_summary IS 'pii:P2 summary shown to approvers; never P3 values or salary amounts unless the process type is payroll-scoped';

CREATE TABLE core.workflow_task (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  workflow_instance_id      uuid NOT NULL,
  step_no                   int  NOT NULL,
  step_key                  text NOT NULL,
  step_type                 text NOT NULL,
  branch_key                text,                  -- P2 parallel branches
  assignee_user_id          uuid NOT NULL,
  assigned_via              text NOT NULL DEFAULT 'RESOLVER',
  original_assignee_user_id uuid,                  -- before delegation / reassignment
  delegation_id             uuid,                  -- P2
  status                    text NOT NULL DEFAULT 'PENDING',
  outcome                   text,
  acted_by_user_id          uuid,                  -- = assignee, or the delegate acting on behalf
  acted_at                  timestamptz,
  comment                   text,
  due_at                    timestamptz,           -- P2 SLA (working time from T&A WorkingCalendarProvider)
  reminded_at               timestamptz,           -- P2
  escalated_at              timestamptz,           -- P2
  escalation_level          smallint,              -- P2
  created_at                timestamptz NOT NULL DEFAULT now(),
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT workflow_task_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT workflow_task_instance_fk FOREIGN KEY (tenant_id, workflow_instance_id)
    REFERENCES core.workflow_instance (tenant_id, id),
  CONSTRAINT workflow_task_assignee_fk FOREIGN KEY (tenant_id, assignee_user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT workflow_task_orig_fk     FOREIGN KEY (tenant_id, original_assignee_user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT workflow_task_actor_fk    FOREIGN KEY (tenant_id, acted_by_user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT workflow_task_delegation_fk FOREIGN KEY (tenant_id, delegation_id) REFERENCES core.approval_delegation (tenant_id, id),
  CONSTRAINT workflow_task_type_ck CHECK (step_type IN ('SINGLE','ANY_OF','ALL_OF','FYI','PARALLEL')),
  CONSTRAINT workflow_task_via_ck CHECK (assigned_via IN ('RESOLVER','DELEGATION','REASSIGN','FALLBACK','ESCALATION')),
  CONSTRAINT workflow_task_status_ck CHECK (status IN ('PENDING','COMPLETED','SKIPPED','CANCELLED','EXPIRED')),
  CONSTRAINT workflow_task_outcome_ck CHECK (outcome IS NULL OR outcome IN ('APPROVED','REJECTED','RETURNED',
                                                                            'INFO_REQUESTED','ACKNOWLEDGED','AUTO_APPROVED','AUTO_REJECTED')),
  CONSTRAINT workflow_task_done_ck CHECK (status <> 'COMPLETED' OR (outcome IS NOT NULL AND acted_at IS NOT NULL))
);
CREATE INDEX workflow_task_inbox_ix    ON core.workflow_task (tenant_id, assignee_user_id, created_at) WHERE status = 'PENDING';
CREATE INDEX workflow_task_instance_ix ON core.workflow_task (tenant_id, workflow_instance_id, step_no);
CREATE INDEX workflow_task_due_ix      ON core.workflow_task (tenant_id, due_at) WHERE status = 'PENDING' AND due_at IS NOT NULL;

-- Immutable approval trail (BR-CORE-061): who, on behalf of whom, when, what, comment, IP/device.
CREATE TABLE core.workflow_action_log (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  workflow_instance_id uuid NOT NULL,
  workflow_task_id     uuid,
  action               text NOT NULL,
  actor_type           text NOT NULL DEFAULT 'USER',
  actor_user_id        uuid,
  on_behalf_of_user_id uuid,                       -- delegator when a delegate acts
  from_status          text,
  to_status            text,
  comment              text,
  attachment_doc_ids   uuid[] NOT NULL DEFAULT '{}',   -- -> core.document
  channel              text NOT NULL DEFAULT 'WEB',
  client_ip            inet,
  user_agent           text,
  occurred_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT workflow_action_log_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT workflow_action_log_instance_fk FOREIGN KEY (tenant_id, workflow_instance_id)
    REFERENCES core.workflow_instance (tenant_id, id),
  CONSTRAINT workflow_action_log_task_fk FOREIGN KEY (tenant_id, workflow_task_id) REFERENCES core.workflow_task (tenant_id, id),
  CONSTRAINT workflow_action_log_actor_fk FOREIGN KEY (tenant_id, actor_user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT workflow_action_log_action_ck CHECK (action IN ('SUBMIT','APPROVE','REJECT','RETURN','REQUEST_INFO','PROVIDE_INFO',
                                                             'WITHDRAW','FORWARD','COMMENT','ATTACH','REASSIGN','DELEGATE',
                                                             'SKIP','ESCALATE','REMIND','AUTO_APPROVE','AUTO_REJECT','CANCEL',
                                                             'RERESOLVE','COMPLETE')),
  CONSTRAINT workflow_action_log_actor_ck CHECK (actor_type IN ('USER','SYSTEM','OPERATOR')
                                                 AND (actor_type = 'SYSTEM' OR actor_user_id IS NOT NULL)),
  CONSTRAINT workflow_action_log_channel_ck CHECK (channel IN ('WEB','MOBILE','EMAIL_LINK','ZALO_LINK','API','SYSTEM')),
  CONSTRAINT workflow_action_log_reject_ck CHECK (action <> 'REJECT' OR comment IS NOT NULL)   -- reason mandatory (BR-CORE-054)
);
CREATE INDEX workflow_action_log_instance_ix ON core.workflow_action_log (tenant_id, workflow_instance_id, occurred_at);
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON core.workflow_action_log
  FOR EACH ROW EXECUTE FUNCTION core.tg_append_only();          -- D9: DELETE only by tenant purge
COMMENT ON COLUMN core.workflow_action_log.client_ip IS 'pii:P2';

-- Module callback bookkeeping: one row per outcome notification that the owner module
-- must apply (via core.WorkflowCompleted / core.WorkflowStepCompleted on the bus and
-- its inbox). The owner acknowledges through Core's API; redelivery is idempotent on
-- idempotency_key (= instance id : event : sequence). DEAD rows surface in ops UI.
CREATE TABLE core.workflow_callback (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  workflow_instance_id uuid NOT NULL,
  target_module        text NOT NULL,
  callback_event_type  text NOT NULL,              -- core.WorkflowCompleted | core.WorkflowStepCompleted ...
  outcome              text,
  idempotency_key      text NOT NULL,
  outbox_event_id      uuid,                       -- core.outbox_event.id (same schema; partitioned, no FK)
  status               text NOT NULL DEFAULT 'PENDING',
  attempts             int  NOT NULL DEFAULT 0,
  last_error           text,
  module_result        jsonb,                      -- e.g. {"leave_request_status":"APPROVED"}
  acked_at             timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT workflow_callback_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT workflow_callback_instance_fk FOREIGN KEY (tenant_id, workflow_instance_id)
    REFERENCES core.workflow_instance (tenant_id, id),
  CONSTRAINT workflow_callback_idem_uq UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT workflow_callback_module_ck CHECK (target_module IN ('saas','core','hr','att','pay','rec')),
  CONSTRAINT workflow_callback_status_ck CHECK (status IN ('PENDING','PUBLISHED','ACKED','FAILED','DEAD')),
  CONSTRAINT workflow_callback_ack_ck CHECK ((status = 'ACKED') = (acked_at IS NOT NULL))
);
CREATE INDEX workflow_callback_open_ix ON core.workflow_callback (tenant_id, status, created_at) WHERE status <> 'ACKED';

-- =============================================================================
-- 9d. NOTIFICATION CENTRE  (BR-CORE-070..075)                                [P1]
--     P1 channels: IN_APP (Core inbox) and EMAIL (sent by the SaaS channel delivery
--     adapter, which reports status back). PUSH / ZALO_ZNS / SMS are P2 (R2-01).
--     Sensitive content is never placed in e-mail bodies: deep links only, except
--     the password-protected payslip PDF (R2-17, BR-CORE-074).
-- =============================================================================

-- Global (sentinel tenant, shipped by the platform) + tenant override (BR-CORE-071).
CREATE TABLE core.notification_template (
  tenant_id          uuid NOT NULL,                -- sys.global_tenant_id() for system templates
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  event_code         text NOT NULL,                -- e.g. core.WorkflowTaskAssigned, pay.PayslipPublished
  channel            text NOT NULL,
  locale             text NOT NULL,                -- vi-VN | en-US
  template_version   int  NOT NULL DEFAULT 1,
  category           text NOT NULL,                -- SECURITY | WORKFLOW | PAYSLIP | REMINDER | ANNOUNCEMENT ...
  is_mandatory       boolean NOT NULL DEFAULT false,   -- user may change channel but not disable (BR-CORE-072)
  subject            text,                         -- EMAIL subject / IN_APP title
  body               text NOT NULL,                -- template with {{placeholders}}
  body_format        text NOT NULL DEFAULT 'TEXT',
  placeholders       jsonb NOT NULL DEFAULT '[]'::jsonb,
  is_active          boolean NOT NULL DEFAULT true,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT notification_template_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT notification_template_uq UNIQUE (tenant_id, event_code, channel, locale, template_version),
  CONSTRAINT notification_template_channel_ck CHECK (channel IN ('IN_APP','EMAIL','PUSH','ZALO_ZNS','SMS')),   -- P1: IN_APP, EMAIL
  CONSTRAINT notification_template_locale_ck CHECK (locale IN ('vi-VN','en-US')),
  CONSTRAINT notification_template_format_ck CHECK (body_format IN ('TEXT','HTML','MARKDOWN'))
);
CREATE UNIQUE INDEX notification_template_active_uq ON core.notification_template (tenant_id, event_code, channel, locale)
  WHERE is_active;

-- Effective template for the current tenant: a tenant row shadows the global row.
CREATE VIEW core.v_notification_template_effective WITH (security_invoker = true, security_barrier = true) AS
SELECT DISTINCT ON (t.event_code, t.channel, t.locale) t.*
  FROM core.notification_template t
 WHERE t.is_active
 ORDER BY t.event_code, t.channel, t.locale, (t.tenant_id = sys.global_tenant_id());

-- One notification per recipient (the in-app inbox item). Monthly partitions on created_at;
-- retention 13 months (drop partition; deliveries first). Dedupe (BR-CORE-073) is done by the
-- sender under pg_advisory_xact_lock on (recipient, dedupe_key) within a window, because a
-- unique index on a partitioned table must contain created_at.
CREATE TABLE core.notification (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  created_at         timestamptz NOT NULL DEFAULT now(),
  recipient_user_id  uuid NOT NULL,
  event_code         text NOT NULL,
  category           text NOT NULL,
  source_module      text NOT NULL,
  source_ref_type    text,                         -- e.g. core.workflow_task
  source_ref_id      uuid,                         -- soft
  template_id        uuid,                         -- no FK: may be a global (sentinel-tenant) template row
  template_version   int,
  title              text NOT NULL,
  body               text NOT NULL,                -- rendered; pii:P2, never salary amounts or P3 values
  deep_link          text,
  priority           smallint NOT NULL DEFAULT 5,
  dedupe_key         text,
  scheduled_at       timestamptz,                  -- P2 scheduled send / quiet hours / digest
  read_at            timestamptz,
  archived_at        timestamptz,
  correlation_id     text,
  CONSTRAINT notification_pk PRIMARY KEY (tenant_id, id, created_at),
  CONSTRAINT notification_recipient_fk FOREIGN KEY (tenant_id, recipient_user_id) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT notification_module_ck CHECK (source_module IN ('saas','core','hr','att','pay','rec'))
) PARTITION BY RANGE (created_at);
CREATE INDEX notification_inbox_ix  ON core.notification (tenant_id, recipient_user_id, created_at) WHERE archived_at IS NULL;
CREATE INDEX notification_unread_ix ON core.notification (tenant_id, recipient_user_id) WHERE read_at IS NULL;
CREATE INDEX notification_dedupe_ix ON core.notification (tenant_id, recipient_user_id, dedupe_key) WHERE dedupe_key IS NOT NULL;
COMMENT ON COLUMN core.notification.body IS 'pii:P2 rendered text; sensitive values are replaced by a deep link (BR-CORE-074)';
CALL sys.create_range_partitions('core.notification', 'month', (current_date - interval '1 month')::date, 4);

-- Per-channel delivery attempt/status (IN_APP is "delivered" on insert; EMAIL via the SaaS adapter).
CREATE TABLE core.notification_delivery (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  created_at              timestamptz NOT NULL DEFAULT now(),
  notification_id         uuid NOT NULL,
  notification_created_at timestamptz NOT NULL,    -- partition key of the parent row (composite FK)
  channel                 text NOT NULL,
  destination_masked      text,                    -- e.g. n***@acme.vn (full address resolved at send time)
  status                  text NOT NULL DEFAULT 'QUEUED',
  provider_message_id     text,
  adapter_job_id          uuid,                    -- -> saas.job (soft)
  attempts                int NOT NULL DEFAULT 0,
  last_error              text,
  has_attachment          boolean NOT NULL DEFAULT false,   -- payslip PDF (password-protected) only
  sent_at                 timestamptz,
  delivered_at            timestamptz,
  opened_at               timestamptz,
  failed_at               timestamptz,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT notification_delivery_pk PRIMARY KEY (tenant_id, id, created_at),
  CONSTRAINT notification_delivery_notification_fk FOREIGN KEY (tenant_id, notification_id, notification_created_at)
    REFERENCES core.notification (tenant_id, id, created_at),
  CONSTRAINT notification_delivery_channel_ck CHECK (channel IN ('IN_APP','EMAIL','PUSH','ZALO_ZNS','SMS')),   -- P1: IN_APP, EMAIL
  CONSTRAINT notification_delivery_status_ck CHECK (status IN ('QUEUED','SENT','DELIVERED','OPENED','FAILED','BOUNCED','SUPPRESSED'))
) PARTITION BY RANGE (created_at);
CREATE INDEX notification_delivery_notif_ix ON core.notification_delivery (tenant_id, notification_id);
CREATE INDEX notification_delivery_open_ix  ON core.notification_delivery (tenant_id, status, created_at)
  WHERE status IN ('QUEUED','SENT');
COMMENT ON COLUMN core.notification_delivery.destination_masked IS 'pii:P1 masked address hint';
CALL sys.create_range_partitions('core.notification_delivery', 'month', (current_date - interval '1 month')::date, 4);

-- =============================================================================
-- 9e. DOCUMENT SERVICE  (BR-CORE-100..104; ADR-0015)                          [P1]
--     Metadata only. Binaries live in the SaaS object storage (VN, tenant prefix,
--     KMS-encrypted, AV-scanned, short-lived signed URLs). Access follows the OWNER
--     record's permission + data scope (the owner module authorises; Core serves).
--     E-signature orchestration (BR-CORE-103) is P2 and not modelled yet.
-- =============================================================================
CREATE TABLE core.document (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  owner_module         text NOT NULL,
  owner_entity_type    text NOT NULL,              -- e.g. hr.contract, att.leave_request, core.legal_entity
  owner_entity_id      uuid,                       -- soft; NULL while an upload is not yet attached
  legal_entity_id      uuid,
  subject_person_id    uuid,                       -- -> hr.person (soft): DSR access/erasure fan-out
  document_type_code   text NOT NULL,              -- code_value list DOCUMENT_TYPE
  title                text NOT NULL,
  confidentiality      text NOT NULL DEFAULT 'INTERNAL',
  retention_class      text NOT NULL,              -- -> saas.retention_policy.data_category (soft, BR-CORE-104)
  retention_anchor_date date,                      -- e.g. employment end; NULL until known
  retention_due_at     timestamptz,                -- computed by the SaaS retention scheduler
  legal_hold           boolean NOT NULL DEFAULT false,
  legal_hold_reason    text,
  legal_hold_set_by    uuid,
  legal_hold_set_at    timestamptz,
  current_version_no   int NOT NULL DEFAULT 0,
  status               text NOT NULL DEFAULT 'ACTIVE',
  purged_at            timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT document_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT document_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT document_module_ck CHECK (owner_module IN ('saas','core','hr','att','pay','rec')),
  CONSTRAINT document_conf_ck CHECK (confidentiality IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
  CONSTRAINT document_status_ck CHECK (status IN ('PENDING_SCAN','ACTIVE','QUARANTINED','ARCHIVED','PURGED')),
  CONSTRAINT document_hold_ck CHECK (NOT legal_hold OR (legal_hold_reason IS NOT NULL AND legal_hold_set_at IS NOT NULL)),
  CONSTRAINT document_purge_ck CHECK ((status = 'PURGED') = (purged_at IS NOT NULL)),
  CONSTRAINT document_purge_hold_ck CHECK (NOT (status = 'PURGED' AND legal_hold))
);
CREATE INDEX document_owner_ix     ON core.document (tenant_id, owner_entity_type, owner_entity_id);
CREATE INDEX document_person_ix    ON core.document (tenant_id, subject_person_id) WHERE subject_person_id IS NOT NULL;
CREATE INDEX document_retention_ix ON core.document (tenant_id, retention_due_at) WHERE NOT legal_hold AND status <> 'PURGED';
COMMENT ON TABLE core.document IS '[P1] Document metadata (binary in SaaS object storage). Legal hold blocks retention purge.';

CREATE TABLE core.document_version (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  document_id          uuid NOT NULL,
  version_no           int  NOT NULL,
  object_key           text NOT NULL,              -- <tenant_id>/<module>/<yyyy>/<uuid>; bucket per environment
  file_name            text NOT NULL,
  content_type         text NOT NULL,
  size_bytes           bigint NOT NULL,
  sha256               text NOT NULL,
  key_version          int,                        -- KMS key version used by the object store
  source               text NOT NULL DEFAULT 'UPLOAD',
  generated_from_template_version_id uuid,         -- -> core.document_template_version (GENERATED)
  scan_status          text NOT NULL DEFAULT 'PENDING',
  scanned_at           timestamptz,
  uploaded_by          uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT document_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT document_version_doc_fk FOREIGN KEY (tenant_id, document_id) REFERENCES core.document (tenant_id, id),
  CONSTRAINT document_version_uq UNIQUE (tenant_id, document_id, version_no),
  CONSTRAINT document_version_key_uq UNIQUE (tenant_id, object_key),
  CONSTRAINT document_version_prefix_ck CHECK (object_key LIKE tenant_id::text || '/%'),   -- tenant-isolated prefix
  CONSTRAINT document_version_size_ck CHECK (size_bytes >= 0),
  CONSTRAINT document_version_sha_ck CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT document_version_source_ck CHECK (source IN ('UPLOAD','GENERATED','SIGNED','IMPORT')),
  CONSTRAINT document_version_scan_ck CHECK (scan_status IN ('PENDING','CLEAN','INFECTED','ERROR','SKIPPED'))
);
COMMENT ON COLUMN core.document_version.file_name IS 'pii:P1 may contain a person name';

-- Document templates (DOCX / HTML -> PDF, ADR-0015): global (sentinel) system templates
-- + tenant templates; legal_entity_id gives LE-specific letterhead/seal (BR-CORE-102).
CREATE TABLE core.document_template (
  tenant_id            uuid NOT NULL,              -- sys.global_tenant_id() for platform templates
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                 text NOT NULL,              -- e.g. LABOUR_CONTRACT_DEFINITE, DECISION_TERMINATION
  owner_module         text NOT NULL,              -- module that provides the merge data
  purpose              text NOT NULL,              -- LABOUR_CONTRACT | DECISION | CERTIFICATE | PAYSLIP | LETTER | REPORT
  name_vi              text NOT NULL,
  name_en              text,
  legal_entity_id      uuid,                       -- NULL = all LEs (always NULL on global rows)
  numbering_sequence_code text,                    -- -> core.sequence_definition.code (BR-CORE-085)
  status               text NOT NULL DEFAULT 'ACTIVE',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT document_template_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT document_template_uq UNIQUE NULLS NOT DISTINCT (tenant_id, code, legal_entity_id),
  CONSTRAINT document_template_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT document_template_module_ck CHECK (owner_module IN ('saas','core','hr','att','pay','rec')),
  CONSTRAINT document_template_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE core.document_template_version (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  document_template_id uuid NOT NULL,
  version_no           int  NOT NULL,
  source_format        text NOT NULL,              -- DOCX | HTML
  output_format        text NOT NULL DEFAULT 'PDF',
  object_key           text NOT NULL,              -- template file in object storage
  sha256               text NOT NULL,
  merge_fields         jsonb NOT NULL DEFAULT '[]'::jsonb,   -- fields the owner module must supply
  status               text NOT NULL DEFAULT 'DRAFT',
  valid_from           date NOT NULL,
  valid_to             date,
  valid_during         daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  published_at         timestamptz,
  published_by         uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  CONSTRAINT document_template_version_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT document_template_version_tpl_fk FOREIGN KEY (tenant_id, document_template_id)
    REFERENCES core.document_template (tenant_id, id),
  CONSTRAINT document_template_version_uq UNIQUE (tenant_id, document_template_id, version_no),
  CONSTRAINT document_template_version_src_ck CHECK (source_format IN ('DOCX','HTML')),
  CONSTRAINT document_template_version_out_ck CHECK (output_format IN ('PDF','DOCX')),
  CONSTRAINT document_template_version_status_ck CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
  CONSTRAINT document_template_version_pub_ck CHECK (status = 'DRAFT' OR published_at IS NOT NULL),
  CONSTRAINT document_template_version_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT document_template_version_period_ex EXCLUDE USING gist
    (tenant_id WITH =, document_template_id WITH =, valid_during WITH &&) WHERE (status = 'PUBLISHED')
);

-- Generated documents point at the template version they were rendered from. Both
-- rows are always in the same tenant (tenant templates) - global templates are copied
-- into the tenant on first use, so this composite FK never crosses tenants.
ALTER TABLE core.document_version ADD CONSTRAINT document_version_template_fk
  FOREIGN KEY (tenant_id, generated_from_template_version_id) REFERENCES core.document_template_version (tenant_id, id);

-- Hard FKs for document references declared earlier in this schema (same schema: allowed).
ALTER TABLE core.legal_entity_version ADD CONSTRAINT legal_entity_version_oa_od_doc_fk
  FOREIGN KEY (tenant_id, oa_od_decision_doc_id) REFERENCES core.document (tenant_id, id);
ALTER TABLE core.company_group ADD CONSTRAINT company_group_logo_doc_fk
  FOREIGN KEY (tenant_id, logo_doc_id) REFERENCES core.document (tenant_id, id);
ALTER TABLE core.job_title ADD CONSTRAINT job_title_jd_doc_fk
  FOREIGN KEY (tenant_id, jd_document_id) REFERENCES core.document (tenant_id, id);
ALTER TABLE core.import_job ADD CONSTRAINT import_job_file_doc_fk
  FOREIGN KEY (tenant_id, file_document_id) REFERENCES core.document (tenant_id, id);

-- =============================================================================
-- 9f. SETTINGS AND NUMBERING  (BR-CORE-084, BR-CORE-085)                      [P1]
-- =============================================================================

-- Setting keys are registered by modules (pure global).
CREATE TABLE core.setting_definition (
  setting_key        text NOT NULL,                -- e.g. core.position_control, hr.employee_no_sequence, core.auto_create_user
  owner_module       text NOT NULL,
  value_type         text NOT NULL,
  json_schema        jsonb,                        -- validation for JSON / ENUM (allowed values)
  default_value      jsonb NOT NULL,
  allowed_levels     text[] NOT NULL DEFAULT ARRAY['TENANT'],
  is_effective_dated boolean NOT NULL DEFAULT false,
  is_sensitive       boolean NOT NULL DEFAULT false,
  description_vi     text NOT NULL,
  description_en     text NOT NULL,
  registered_release text,
  is_active          boolean NOT NULL DEFAULT true,
  CONSTRAINT setting_definition_pk PRIMARY KEY (setting_key),
  CONSTRAINT setting_definition_key_ck CHECK (setting_key ~ '^[a-z]+\.[a-z0-9_.]+$'),
  CONSTRAINT setting_definition_module_ck CHECK (owner_module IN ('saas','core','hr','att','pay','rec')),
  CONSTRAINT setting_definition_type_ck CHECK (value_type IN ('BOOL','INT','DECIMAL','TEXT','ENUM','DATE','JSON')),
  CONSTRAINT setting_definition_levels_ck CHECK (allowed_levels <@ ARRAY['TENANT','LEGAL_ENTITY','LOCATION']
                                                 AND cardinality(allowed_levels) > 0)
);
COMMENT ON TABLE core.setting_definition IS '[P1] Global catalogue of typed tenant settings registered by modules.';

-- Tenant values, hierarchical: LOCATION -> LEGAL_ENTITY -> TENANT -> definition default.
-- Non-effective-dated settings use one open row (valid_from = 1900-01-01).
CREATE TABLE core.setting_value (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  setting_key        text NOT NULL,
  scope_level        text NOT NULL DEFAULT 'TENANT',
  legal_entity_id    uuid,
  location_id        uuid,
  value              jsonb NOT NULL,
  valid_from         date NOT NULL DEFAULT DATE '1900-01-01',
  valid_to           date,
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT setting_value_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT setting_value_def_fk FOREIGN KEY (setting_key) REFERENCES core.setting_definition (setting_key),
  CONSTRAINT setting_value_le_fk  FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT setting_value_loc_fk FOREIGN KEY (tenant_id, location_id) REFERENCES core.location (tenant_id, id),
  CONSTRAINT setting_value_scope_ck CHECK (
       (scope_level = 'TENANT'       AND legal_entity_id IS NULL     AND location_id IS NULL)
    OR (scope_level = 'LEGAL_ENTITY' AND legal_entity_id IS NOT NULL AND location_id IS NULL)
    OR (scope_level = 'LOCATION'     AND legal_entity_id IS NOT NULL AND location_id IS NOT NULL)),
  CONSTRAINT setting_value_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT setting_value_period_ex EXCLUDE USING gist (
    tenant_id WITH =, setting_key WITH =,
    (coalesce(legal_entity_id, sys.global_tenant_id())) WITH =,
    (coalesce(location_id, sys.global_tenant_id())) WITH =, valid_during WITH &&)
);
CREATE INDEX setting_value_lookup_ix ON core.setting_value (tenant_id, setting_key, valid_from);

-- Numbering series (employee no., contract no., decision no. ...).
CREATE TABLE core.sequence_definition (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  code               text NOT NULL,                -- EMPLOYEE_NO | CONTRACT_NO | DECISION_NO | ...
  name               text NOT NULL,
  scope_level        text NOT NULL DEFAULT 'TENANT',   -- TENANT | LEGAL_ENTITY (counter per LE)
  pattern            text NOT NULL,                -- e.g. '{LE}-{YYYY}-{SEQ:5}' (formatted by the service)
  reset_rule         text NOT NULL DEFAULT 'NEVER',
  is_gap_free        boolean NOT NULL DEFAULT true,
  start_value        bigint NOT NULL DEFAULT 1,
  increment_by       int    NOT NULL DEFAULT 1,
  status             text NOT NULL DEFAULT 'ACTIVE',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT sequence_definition_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT sequence_definition_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT sequence_definition_scope_ck CHECK (scope_level IN ('TENANT','LEGAL_ENTITY')),
  CONSTRAINT sequence_definition_reset_ck CHECK (reset_rule IN ('NEVER','YEARLY','MONTHLY')),
  CONSTRAINT sequence_definition_pattern_ck CHECK (pattern LIKE '%{SEQ%'),
  CONSTRAINT sequence_definition_incr_ck CHECK (increment_by > 0),
  CONSTRAINT sequence_definition_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- Current value per series x scope x period. bigint by design (DESIGN §4.1: gap-free
-- ordering is the purpose). Gap-free: the value is taken with INSERT .. ON CONFLICT DO
-- UPDATE inside the business transaction, so the row stays locked until commit and a
-- rollback returns the number. Concurrency on one series is therefore serialised -
-- acceptable for HR document numbering volumes.
CREATE TABLE core.sequence_counter (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  sequence_definition_id uuid NOT NULL,
  legal_entity_id        uuid,                     -- NULL for TENANT-scoped series
  period_key             text NOT NULL DEFAULT '-',-- '-' (NEVER) | 'YYYY' | 'YYYY-MM'
  current_value          bigint NOT NULL,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sequence_counter_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT sequence_counter_def_fk FOREIGN KEY (tenant_id, sequence_definition_id)
    REFERENCES core.sequence_definition (tenant_id, id),
  CONSTRAINT sequence_counter_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT sequence_counter_uq UNIQUE NULLS NOT DISTINCT (tenant_id, sequence_definition_id, legal_entity_id, period_key),
  CONSTRAINT sequence_counter_period_ck CHECK (period_key ~ '^(-|[0-9]{4}|[0-9]{4}-[0-9]{2})$')
);
ALTER TABLE core.sequence_counter SET (fillfactor = 70);   -- hot single-row updates (HOT)

-- Returns the next number of a series for the current tenant. Call inside the business
-- transaction; the counter row stays locked until commit (gap-free).
CREATE OR REPLACE FUNCTION core.next_sequence_value(p_sequence_code text, p_legal_entity_id uuid DEFAULT NULL,
                                                    p_on_date date DEFAULT NULL)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
  d core.sequence_definition;
  v_period text;
  v_le uuid;
  v bigint;
BEGIN
  SELECT * INTO d FROM core.sequence_definition
   WHERE tenant_id = sys.assert_tenant() AND code = p_sequence_code AND status = 'ACTIVE';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown or inactive sequence %', p_sequence_code USING ERRCODE = '22023';
  END IF;
  IF d.scope_level = 'LEGAL_ENTITY' AND p_legal_entity_id IS NULL THEN
    RAISE EXCEPTION 'sequence % is per legal entity', p_sequence_code USING ERRCODE = '22023';
  END IF;
  v_le := CASE WHEN d.scope_level = 'LEGAL_ENTITY' THEN p_legal_entity_id END;
  IF d.reset_rule <> 'NEVER' AND p_on_date IS NULL THEN
    RAISE EXCEPTION 'sequence % resets % and needs a business date', p_sequence_code, d.reset_rule USING ERRCODE = '22023';
  END IF;
  v_period := CASE d.reset_rule WHEN 'YEARLY'  THEN to_char(p_on_date, 'YYYY')
                                WHEN 'MONTHLY' THEN to_char(p_on_date, 'YYYY-MM')
                                ELSE '-' END;
  INSERT INTO core.sequence_counter AS c (tenant_id, sequence_definition_id, legal_entity_id, period_key, current_value)
  VALUES (d.tenant_id, d.id, v_le, v_period, d.start_value)
  ON CONFLICT (tenant_id, sequence_definition_id, legal_entity_id, period_key)
  DO UPDATE SET current_value = c.current_value + d.increment_by, updated_at = now()
  RETURNING c.current_value INTO v;
  RETURN v;
END $$;

-- =============================================================================
-- 9g. CUSTOM FIELDS  (BR-CORE-086; schema now, feature P2 per mvp-scope §9, R3-09)  [P2]
-- =============================================================================
CREATE TABLE core.custom_field_def (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  target_schema      text NOT NULL,                -- hr | core | att | pay | rec
  target_table       text NOT NULL,                -- registered entity, e.g. person, assignment, org_unit
  field_code         text NOT NULL,
  label_vi           text NOT NULL,
  label_en           text,
  data_type          text NOT NULL,
  code_list_code     text,                         -- for CODE fields -> core.code_list
  validation         jsonb NOT NULL DEFAULT '{}'::jsonb,   -- min/max/regex/max_length
  is_required        boolean NOT NULL DEFAULT false,
  pii_class          text NOT NULL DEFAULT 'P1',   -- P3 values go to value_enc
  view_permission_code text,                       -- -> core.permission (field-level permission)
  sort_order         int NOT NULL DEFAULT 0,
  status             text NOT NULL DEFAULT 'ACTIVE',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT custom_field_def_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT custom_field_def_uq UNIQUE (tenant_id, target_schema, target_table, field_code),
  CONSTRAINT custom_field_def_target_uq UNIQUE (tenant_id, id, target_schema, target_table),   -- for value FK
  CONSTRAINT custom_field_def_list_fk FOREIGN KEY (code_list_code) REFERENCES core.code_list (list_code),
  CONSTRAINT custom_field_def_perm_fk FOREIGN KEY (view_permission_code) REFERENCES core.permission (permission_code),
  CONSTRAINT custom_field_def_schema_ck CHECK (target_schema IN ('core','hr','att','pay','rec')),
  CONSTRAINT custom_field_def_type_ck CHECK (data_type IN ('TEXT','NUMBER','DATE','BOOL','CODE')),
  CONSTRAINT custom_field_def_code_ck CHECK ((data_type = 'CODE') = (code_list_code IS NOT NULL)),
  CONSTRAINT custom_field_def_pii_ck CHECK (pii_class IN ('P0','P1','P2','P3')),
  CONSTRAINT custom_field_def_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- Values keyed by (target_schema, target_table, target_id); target_id is a soft reference
-- into the owner module. The composite FK guarantees the value's target matches its def.
CREATE TABLE core.custom_field_value (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  custom_field_def_id uuid NOT NULL,
  target_schema      text NOT NULL,
  target_table       text NOT NULL,
  target_id          uuid NOT NULL,                -- soft
  value_text         text,
  value_num          numeric,
  value_date         date,
  value_bool         boolean,
  value_code         text,
  value_enc          bytea,                        -- pii:P3 when def.pii_class = P3 (tenant/subject DEK)
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT custom_field_value_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT custom_field_value_def_fk FOREIGN KEY (tenant_id, custom_field_def_id, target_schema, target_table)
    REFERENCES core.custom_field_def (tenant_id, id, target_schema, target_table),
  CONSTRAINT custom_field_value_uq UNIQUE (tenant_id, custom_field_def_id, target_id),
  CONSTRAINT custom_field_value_one_ck CHECK (num_nonnulls(value_text, value_num, value_date, value_bool, value_code, value_enc) <= 1)
);
CREATE INDEX custom_field_value_target_ix ON core.custom_field_value (tenant_id, target_schema, target_table, target_id);
COMMENT ON COLUMN core.custom_field_value.value_enc IS 'pii:P3 AES-256-GCM envelope ciphertext (subject DEK)';
COMMENT ON COLUMN core.custom_field_value.value_text IS 'pii:P2 tenant-defined content, classified per custom_field_def.pii_class';

-- =============================================================================
-- 9h. REORGANISATION PACKAGES  (BR-CORE-013..015; DESIGN §5.3)              [P2]
--     Activation creates org_unit_version rows (reorg_package_id) and recomputes
--     descendant paths; People maps positions/employees on core.ReorgActivated and
--     replies people.ReorgMappingCompleted (GAP-053).
-- =============================================================================
CREATE TABLE core.reorg_package (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                 text NOT NULL,
  name                 text NOT NULL,
  effective_date       date NOT NULL,
  is_retro             boolean NOT NULL DEFAULT false,   -- needs separate permission + lock check (BR-CORE-015)
  status               text NOT NULL DEFAULT 'DRAFT',
  workflow_instance_id uuid,
  impact_summary       jsonb,                      -- counts of units/positions/employees affected
  scheduled_at         timestamptz,
  activated_at         timestamptz,
  mapping_completed_at timestamptz,
  moved_count          int,
  failed_count         int,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT reorg_package_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT reorg_package_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT reorg_package_wf_fk FOREIGN KEY (tenant_id, workflow_instance_id) REFERENCES core.workflow_instance (tenant_id, id),
  CONSTRAINT reorg_package_status_ck CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','SCHEDULED','ACTIVATING',
                                                       'ACTIVE','ACTIVATED_WITH_ERRORS','REJECTED','CANCELLED'))
);

CREATE TABLE core.reorg_change (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  reorg_package_id     uuid NOT NULL,
  seq_no               int  NOT NULL,
  change_type          text NOT NULL,
  org_unit_id          uuid,                       -- source unit (NULL for CREATE)
  target_org_unit_id   uuid,                       -- MERGE target / unit created by CREATE / SPLIT
  new_parent_id        uuid,                       -- MOVE / CREATE
  new_attributes       jsonb NOT NULL DEFAULT '{}'::jsonb,   -- name, type, manager position, cost centre ...
  status               text NOT NULL DEFAULT 'PLANNED',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  CONSTRAINT reorg_change_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT reorg_change_pkg_fk FOREIGN KEY (tenant_id, reorg_package_id) REFERENCES core.reorg_package (tenant_id, id),
  CONSTRAINT reorg_change_unit_fk FOREIGN KEY (tenant_id, org_unit_id) REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT reorg_change_target_fk FOREIGN KEY (tenant_id, target_org_unit_id) REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT reorg_change_parent_fk FOREIGN KEY (tenant_id, new_parent_id) REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT reorg_change_uq UNIQUE (tenant_id, reorg_package_id, seq_no),
  CONSTRAINT reorg_change_type_ck CHECK (change_type IN ('CREATE','RENAME','MOVE','MERGE','SPLIT','DISSOLVE',
                                                         'CHANGE_MANAGER','CHANGE_COST_CENTRE','CHANGE_ATTRIBUTES')),
  CONSTRAINT reorg_change_source_ck CHECK (change_type = 'CREATE' OR org_unit_id IS NOT NULL),
  CONSTRAINT reorg_change_status_ck CHECK (status IN ('PLANNED','APPLIED','FAILED','CANCELLED'))
);

CREATE TABLE core.reorg_mapping (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  reorg_package_id     uuid NOT NULL,
  reorg_change_id      uuid,
  mapping_type         text NOT NULL,              -- POSITION | EMPLOYEE
  position_id          uuid,                       -- POSITION mapping (core)
  employment_id        uuid,                       -- EMPLOYEE mapping -> hr.employment (soft)
  assignment_key       uuid,                       -- -> hr.assignment.assignment_key (soft)
  from_org_unit_id     uuid,
  to_org_unit_id       uuid NOT NULL,
  to_position_id       uuid,
  status               text NOT NULL DEFAULT 'PROPOSED',
  error_message        text,
  applied_at           timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reorg_mapping_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT reorg_mapping_pkg_fk FOREIGN KEY (tenant_id, reorg_package_id) REFERENCES core.reorg_package (tenant_id, id),
  CONSTRAINT reorg_mapping_change_fk FOREIGN KEY (tenant_id, reorg_change_id) REFERENCES core.reorg_change (tenant_id, id),
  CONSTRAINT reorg_mapping_pos_fk FOREIGN KEY (tenant_id, position_id) REFERENCES core.position (tenant_id, id),
  CONSTRAINT reorg_mapping_from_fk FOREIGN KEY (tenant_id, from_org_unit_id) REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT reorg_mapping_to_fk FOREIGN KEY (tenant_id, to_org_unit_id) REFERENCES core.org_unit (tenant_id, id),
  CONSTRAINT reorg_mapping_topos_fk FOREIGN KEY (tenant_id, to_position_id) REFERENCES core.position (tenant_id, id),
  CONSTRAINT reorg_mapping_type_ck CHECK ((mapping_type = 'POSITION' AND position_id IS NOT NULL)
                                       OR (mapping_type = 'EMPLOYEE' AND employment_id IS NOT NULL)),
  CONSTRAINT reorg_mapping_status_ck CHECK (status IN ('PROPOSED','CONFIRMED','APPLIED','FAILED'))
);
CREATE INDEX reorg_mapping_pkg_ix ON core.reorg_mapping (tenant_id, reorg_package_id, status);

ALTER TABLE core.org_unit_version ADD CONSTRAINT org_unit_version_reorg_fk
  FOREIGN KEY (tenant_id, reorg_package_id) REFERENCES core.reorg_package (tenant_id, id);

-- =============================================================================
-- 9i. EXPORT JOBS  (DESIGN §3.7 data export, NFR-SAAS-019; BR-SAAS-018)          [P1]
--     Bookkeeping for asynchronous exports orchestrated by Core: full tenant export
--     (each module exports its own entities under its own role and tenant context),
--     audit-log export (BR-CORE-093) and dataset exports. The result file is a
--     core.document; expiry <= 7 days. List/report export UI belongs to the SaaS
--     reporting framework (BR-CORE-113 moved, R2-02), which may record its jobs here.
-- =============================================================================
CREATE TABLE core.export_job (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  export_type          text NOT NULL,
  requested_by         uuid NOT NULL,
  legal_entity_id      uuid,
  parameters           jsonb NOT NULL DEFAULT '{}'::jsonb,   -- filters, entity list, period
  modules              text[] NOT NULL DEFAULT '{}',
  format               text NOT NULL,
  includes_sensitive   boolean NOT NULL DEFAULT false,       -- needs step-up + VIEW_SENSITIVE (audited)
  step_up_verified_at  timestamptz,
  encryption_mode      text NOT NULL DEFAULT 'TENANT_KEY',
  status               text NOT NULL DEFAULT 'QUEUED',
  module_progress      jsonb NOT NULL DEFAULT '{}'::jsonb,   -- {"hr":"DONE","pay":"RUNNING"}
  row_count            bigint,
  result_document_id   uuid,
  job_id               uuid,                       -- -> saas.job (soft)
  started_at           timestamptz,
  finished_at          timestamptz,
  expires_at           timestamptz,
  last_error           text,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT export_job_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT export_job_user_fk FOREIGN KEY (tenant_id, requested_by) REFERENCES core.user_account (tenant_id, id),
  CONSTRAINT export_job_le_fk FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES core.legal_entity (tenant_id, id),
  CONSTRAINT export_job_doc_fk FOREIGN KEY (tenant_id, result_document_id) REFERENCES core.document (tenant_id, id),
  CONSTRAINT export_job_type_ck CHECK (export_type IN ('TENANT_FULL','MODULE_DATASET','AUDIT_LOG','DSR_ACCESS','LIST')),
  CONSTRAINT export_job_format_ck CHECK (format IN ('NDJSON','CSV','XLSX','PDF','ZIP')),
  CONSTRAINT export_job_enc_ck CHECK (encryption_mode IN ('TENANT_KEY','CUSTOMER_KEY','PASSWORD')),
  CONSTRAINT export_job_status_ck CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','EXPIRED')),
  CONSTRAINT export_job_sensitive_ck CHECK (NOT includes_sensitive OR step_up_verified_at IS NOT NULL),
  CONSTRAINT export_job_expiry_ck CHECK (expires_at IS NULL OR expires_at <= coalesce(finished_at, created_at) + interval '7 days')
);
CREATE INDEX export_job_user_ix ON core.export_job (tenant_id, requested_by, created_at);

-- =============================================================================
-- 9j. SEGREGATION OF DUTIES  (BR-CORE-037)                                    [P2]
-- =============================================================================
CREATE TABLE core.sod_rule (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                 text NOT NULL,
  name                 text NOT NULL,
  rule_type            text NOT NULL,              -- PERMISSION_PAIR: never both in one user's roles;
                                                   -- SAME_ACTOR: same user cannot do A then B on one object
  permission_code_a    text NOT NULL,
  permission_code_b    text NOT NULL,
  process_type_code    text,                       -- SAME_ACTOR rules on a workflow (maker != approver)
  enforcement          text NOT NULL DEFAULT 'BLOCK',
  is_system            boolean NOT NULL DEFAULT false,   -- seeded (e.g. pay.run.calculate vs pay.run.approve)
  status               text NOT NULL DEFAULT 'ACTIVE',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT sod_rule_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT sod_rule_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT sod_rule_a_fk FOREIGN KEY (permission_code_a) REFERENCES core.permission (permission_code),
  CONSTRAINT sod_rule_b_fk FOREIGN KEY (permission_code_b) REFERENCES core.permission (permission_code),
  CONSTRAINT sod_rule_pt_fk FOREIGN KEY (process_type_code) REFERENCES core.process_type (process_type_code),
  CONSTRAINT sod_rule_type_ck CHECK (rule_type IN ('PERMISSION_PAIR','SAME_ACTOR')),
  CONSTRAINT sod_rule_distinct_ck CHECK (rule_type = 'SAME_ACTOR' OR permission_code_a <> permission_code_b),
  CONSTRAINT sod_rule_enf_ck CHECK (enforcement IN ('BLOCK','WARN')),
  CONSTRAINT sod_rule_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);

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
-- D9 (2026-10-21): deliberately NO sys.purge_bypass() exception here. The audit trail
-- is retained by law beyond the tenant's life (DESIGN §3.7, §9.2): the tenant purge
-- does not delete audit events; they leave only by partition detach/archive at the
-- end of the retention period. Do not add a purge bypass to this guard.
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
-- v0.5 (2026-10-21, PM integration check): chain_anchor carries tenant_id and Core reads it, so it must be
-- tenant-isolated like every other tenant table. Core sees only its tenant's anchors (verify_chain runs as invoker);
-- the sealer writes/reads all tenants through its own policy. chain_head stays sealer-only (no app grant, no RLS).
CALL sys.enable_tenant_rls('audit.chain_anchor');
CREATE POLICY sealer_all ON audit.chain_anchor FOR ALL TO yhrm_audit_sealer USING (true) WITH CHECK (true);
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
    'core.role_assignment','core.import_job','core.import_row','core.import_reconciliation',
    -- v0.4
    'core.employee_category',
    'core.workflow_definition','core.workflow_definition_version','core.approval_delegation',
    'core.workflow_instance','core.workflow_task','core.workflow_action_log','core.workflow_callback',
    'core.notification','core.notification_delivery',
    'core.document','core.document_version',
    'core.setting_value','core.sequence_definition','core.sequence_counter',
    'core.custom_field_def','core.custom_field_value',
    'core.reorg_package','core.reorg_change','core.reorg_mapping',
    'core.export_job','core.sod_rule']
  LOOP
    CALL sys.enable_tenant_rls(t::regclass);
  END LOOP;
END $$;

-- Global + tenant-overridable.
CALL sys.enable_tenant_or_global_rls('core.code_value');
CALL sys.enable_tenant_or_global_rls('core.exchange_rate');
CALL sys.enable_tenant_or_global_rls('core.notification_template');        -- v0.4
CALL sys.enable_tenant_or_global_rls('core.document_template');            -- v0.4
CALL sys.enable_tenant_or_global_rls('core.document_template_version');    -- v0.4

-- Touch triggers on mutable tables.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'core.code_value','core.company_group','core.legal_entity','core.legal_entity_version',
    'core.legal_entity_bank_account','core.location','core.location_version','core.cost_centre',
    'core.org_unit_version','core.job_title','core.position_version','core.user_account','core.role',
    'core.import_job','core.import_row','core.import_reconciliation',
    -- v0.4
    'core.employee_category','core.workflow_definition','core.workflow_definition_version',
    'core.approval_delegation','core.workflow_instance','core.workflow_task','core.workflow_callback',
    'core.notification_template','core.notification_delivery','core.document','core.document_template',
    'core.setting_value','core.sequence_definition','core.custom_field_def','core.custom_field_value',
    'core.reorg_package','core.reorg_mapping','core.export_job','core.sod_rule']
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
    'core.user_account','core.code_value','core.import_job','core.import_reconciliation',
    -- v0.4 (configuration and governance data; runtime workflow rows have their own
    -- immutable trail in workflow_action_log, notifications are not audited per row)
    'core.employee_category','core.workflow_definition','core.workflow_definition_version',
    'core.approval_delegation','core.notification_template','core.document','core.document_template',
    'core.document_template_version','core.setting_value','core.sequence_definition','core.custom_field_def',
    'core.reorg_package','core.reorg_change','core.reorg_mapping','core.export_job','core.sod_rule']
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
  core.import_job, core.import_row, core.import_reconciliation,
  -- v0.4
  core.employee_category, core.workflow_definition, core.workflow_definition_version,
  core.approval_delegation, core.workflow_instance, core.workflow_task, core.workflow_callback,
  core.notification_template, core.notification, core.notification_delivery,
  core.document, core.document_version, core.document_template, core.document_template_version,
  core.setting_value, core.sequence_definition, core.sequence_counter,
  core.custom_field_def, core.custom_field_value,
  core.reorg_package, core.reorg_change, core.reorg_mapping, core.export_job, core.sod_rule
TO yhrm_app_core;
GRANT SELECT, INSERT ON core.workflow_action_log TO yhrm_app_core;            -- append-only trail (BR-CORE-061)
GRANT SELECT ON core.v_notification_template_effective TO yhrm_app_core;
-- Module-registered global catalogues: read by Core only (NOT X-02: no other module role gets them);
-- written by the release job / platform tooling like the other global master data.
GRANT SELECT ON core.permission, core.process_type, core.setting_definition TO yhrm_app_core;
GRANT SELECT, INSERT, UPDATE ON core.permission, core.process_type, core.setting_definition TO yhrm_platform;
GRANT EXECUTE ON FUNCTION core.org_tree_as_of(date, uuid) TO yhrm_app_core;
GRANT EXECUTE ON FUNCTION core.next_sequence_value(text, uuid, date) TO yhrm_app_core;

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
