-- =============================================================================
-- YourHRM  ·  003_hr.sql   (owner module: People)
-- Person (+ identity documents, addresses, contacts, tax residency, presence in VN,
-- qualification), employment, primary employment (PIT), insurance participation,
-- assignment (bi-temporal), salary record (bi-temporal), contract (+ appendix,
-- suspension), decision, family member + PIT dependant, emergency contact, bank
-- account, work permit, special status, separation, HR document metadata,
-- statutory-report log, labour-book views, masking views, outbox/inbox.
-- Minimal Phase-2 tables (certificate, discipline, reward, appraisal, training,
-- checklists) are marked "-- P2".
-- DESIGN.md §4, §5 (patterns A and B), §7.3, §9.3/9.4, §13; ADR-0023/0024/0026/0037.
--
-- Cross-module references are SOFT (no FK leaves the schema):
--   legal_entity_id, org_unit_id, position_id, job_title_id, grade_id,
--   location_id, cost_centre_id, document_id, workflow_instance_id -> core.*
--   component/pay-scale codes, severance result -> pay.*   |   legal_pack_id -> saas.legal_param_pack
--   Global master data is referenced by CODE (country_code, bank_code, admin_unit_code ...).
--
-- PII classes (COMMENT 'pii:Px'): P3 columns are application-encrypted with the
-- data subject's DEK (saas.subject_key); *_bidx = HMAC blind index for equality;
-- *_last4 = display hint for masked UIs. Salary amounts are P2 and NOT encrypted
-- (X-01): RLS + module grants + hr.v_salary_record_masked + audit instead.
--
-- v0.2 (2026-10-16): source value MIGRATION -> OPENING_LOAD (D-09, ADR-0037), salary
-- masking view, X-01 comments. NOT re-tested after these changes (no PostgreSQL
-- available locally); reviewed by read-through only.
-- v0.3 (2026-10-16, R2-16 concurrent employment across legal entities):
-- hr.employment.is_primary + employment_primary_ex replaced by the effective-dated
-- hr.primary_employment (per purpose PIT_FAMILY_DEDUCTION / SOCIAL_INSURANCE, at most
-- one per person per period); UNIQUE (tenant_id, person_id, id) on employment; at most
-- one live employment per person per legal entity. NOT tested (no PostgreSQL).
-- v0.4 (2026-10-17, DDL review round; R3-03, R3-07, GAP-048):
--   * H1  hr.insurance_participation (new): outcome of the per-insurance-type legal
--         parameter insurance.multi_contract_rule (R3-03) per employment, Pattern A,
--         SI | HI | UI | OA_OD, basis_rule FIRST_CONTRACT | HIGHEST_SALARY | EACH_EMPLOYER
--         as applied, exemption reason, legal pack id/version. No overlap per
--         (employment, type); at most one participating employment per person and type
--         when the rule is FIRST_CONTRACT / HIGHEST_SALARY.
--         hr.primary_employment.purpose narrowed to PIT_FAMILY_DEDUCTION (insurance is
--         now hr.insurance_participation); comments updated.
--   * H2  hr.tax_residency (new, Pattern A, BR-PPL-023): RESIDENT | NON_RESIDENT with
--         basis, arrival/departure, days in VN, evidence document, approval; plus
--         hr.vn_presence_period (entry/exit dates in VN).
--   * L3  national id stays on hr.person; hr.person_identity_doc (new) for additional
--         documents (passport, visa, temporary residence card, old CMND ...), P3.
--   * B   missing P1 tables: hr.separation (+ separation_service_period, BR-PPL-070..075),
--         hr.special_status (BR-PPL-021), hr.work_permit (BR-PPL-020), hr.person_contact,
--         hr.person_address (two-level admin units + legacy 3-level codes, BR-PPL-010),
--         hr.education_qualification (minimal, BR-PPL-019 / R3-07), hr.hr_document
--         (metadata only), hr.contract_suspension (BR-PPL-056), hr.statutory_report
--         (BR-PPL-141/142 submission log), views hr.v_labour_book (BR-PPL-140) and
--         hr.v_labour_movement (BR-PPL-141/142); hr.emergency_contact added although
--         BR-PPL-018 is P2 (mvp-scope R2-19) because it is cheap and avoids a free-text field.
--         hr.person: + health-insurance card (P3), initial KCB facility, SI book custody
--         (BR-PPL-014).
--   * B   minimal P2 tables, marked "-- P2": certificate, discipline_case, reward,
--         appraisal, training_record, checklist_template / checklist / checklist_task.
--   * C   salary_record: pay_basis GROSS|NET, agreed_net_amount and source OPENING_LOAD
--         confirmed; + net_guaranteed_scope (BR-PPL-040), + contract_appendix_id;
--         hr.v_salary_record_masked unchanged in behaviour (fail closed) and new
--         hr.v_salary_record_allowance_masked (fail closed). family_member *_enc columns
--         now carry pii:P3 comments.
--   * D   outbox/inbox via sys.create_outbox_inbox('hr', ...); every new table: RLS,
--         row audit, touch trigger, grants to yhrm_app_hr only.
--   Smoke-tested on PGlite 0.5.10 (PostgreSQL 17, WASM) together with 000-002:
--   load, exclusion constraints, composite FKs, masking views, cross-tenant RLS.
-- v0.5 (2026-10-21, sponsor decisions on the DDL review, DESIGN §15.1 D1-D11):
--   * D9  tenant purge: new hr.tg_append_only() replaces sys.tg_forbid_change() on
--         hr.salary_record_allowance; the bi-temporal tables hr.assignment and
--         hr.salary_record keep sys.tg_bitemporal_guard() for UPDATE only and get
--         trg_bitemporal_no_delete -> hr.tg_bitemporal_no_delete() for DELETE. DELETE
--         passes only when sys.purge_bypass(OLD.tenant_id) is true (member of
--         yhrm_purger + app.purge_tenant_id = tenant, contract in 000); UPDATE rules
--         are unchanged.
--   * D6  GRANT of hr.v_person_masked / v_bank_account_masked / v_salary_record_masked
--         and of USAGE ON SCHEMA hr to yhrm_reporting_ro removed. Reporting goes
--         through the reporting store (DESIGN §11), not OLTP views.
--   * D4  hr.person / hr.employment / hr.assignment are published to the reporting
--         store by 007_publications.sql (column lists, never *_enc / *_bidx).
-- =============================================================================

\set ON_ERROR_STOP on
SET ROLE yhrm_owner;
SET search_path = hr, sys, public;

GRANT USAGE ON SCHEMA hr TO yhrm_app_hr;
-- D6 (2026-10-21): yhrm_reporting_ro gets NOTHING in hr (no schema USAGE, no masking
-- view). Reporting goes through the reporting store (DESIGN §11: column-listed logical
-- replication, 007_publications.sql + reporting/100_rpt.sql), not OLTP views. The
-- security_invoker masking views below are for the People runtime only.

-- =============================================================================
-- 0. Module trigger functions  (D9, 2026-10-21)
-- =============================================================================
-- Append-only rows: UPDATE always rejected; DELETE rejected EXCEPT inside the
-- controlled tenant purge (DESIGN §3.7), i.e. when sys.purge_bypass(OLD.tenant_id)
-- is true (member of yhrm_purger + app.purge_tenant_id = that tenant; contract in
-- 000_extensions_roles.sql).
CREATE OR REPLACE FUNCTION hr.tg_append_only()
RETURNS trigger LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' AND sys.purge_bypass(OLD.tenant_id) THEN
    RETURN OLD;                                             -- D9: tenant purge only
  END IF;
  RAISE EXCEPTION '%.% is append-only (% rejected)', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
    USING ERRCODE = '55000';
END $$;

-- Bi-temporal tables (DESIGN §5.2): rows are superseded, never deleted — except by
-- the tenant purge (D9). The UPDATE rules stay in sys.tg_bitemporal_guard(), which is
-- attached BEFORE UPDATE only (section 17); this function owns the DELETE path.
CREATE OR REPLACE FUNCTION hr.tg_bitemporal_no_delete()
RETURNS trigger LANGUAGE plpgsql
AS $$
BEGIN
  IF sys.purge_bypass(OLD.tenant_id) THEN
    RETURN OLD;                                             -- D9: tenant purge only
  END IF;
  RAISE EXCEPTION '%.% is bi-temporal: rows are superseded, never deleted', TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = '55000';
END $$;

-- =============================================================================
-- 1. PERSON  (one row per real individual per tenant; no cross-tenant matching)
--    The national identifier (CCCD / passport for foreigners, BR-PPL-001) stays here;
--    further identity documents are in hr.person_identity_doc (section 1a).
-- =============================================================================
CREATE TABLE hr.person (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  family_name          text NOT NULL,                       -- họ
  middle_name          text,                                -- tên đệm
  given_name           text NOT NULL,                       -- tên
  full_name            text NOT NULL,
  full_name_search     text GENERATED ALWAYS AS (sys.search_key(full_name)) STORED,  -- accent-insensitive
  latin_name           text,                                -- passport spelling for expats
  preferred_name       text,
  birth_date           date,
  gender_code          text,                                -- code list GENDER
  nationality_code     char(2) NOT NULL DEFAULT 'VN',       -- core.country (global, by code)
  ethnicity_enc        bytea,                               -- pii:P3 (racial/ethnic origin)
  religion_enc         bytea,                               -- pii:P3 (religious belief)
  national_id_type     text,                                -- CCCD | CMND | PASSPORT
  national_id_enc      bytea,                               -- pii:P3
  national_id_bidx     bytea,                               -- equality lookup / duplicate check
  national_id_last4    text,
  tax_code_enc         bytea,                               -- pii:P3 personal tax code (MST TNCN)
  tax_code_bidx        bytea,
  tax_code_last4       text,
  si_number_enc        bytea,                               -- pii:P3 SI book number (mã số BHXH)
  si_number_bidx       bytea,
  si_number_last4      text,
  hi_card_no_enc       bytea,                               -- pii:P3 health-insurance card number (BR-PPL-014)
  hi_card_no_last4     text,
  hi_facility_code     text,                                -- initial KCB facility (nơi KCB ban đầu), code list
  si_book_custody      text,                                -- EMPLOYER | EMPLOYEE | RETURNED (BR-PPL-014)
  photo_document_id    uuid,                                -- -> core.document (soft)
  verification_status  text NOT NULL DEFAULT 'UNVERIFIED',
  subject_key_version  int  NOT NULL DEFAULT 1,             -- saas.subject_key (PERSON, id)
  is_anonymised        boolean NOT NULL DEFAULT false,
  anonymised_at        timestamptz,
  source               text NOT NULL DEFAULT 'APP',         -- APP | OPENING_LOAD (go-live import, ADR-0037) | REC_HANDOFF | IMPORT
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT person_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT person_id_type_ck CHECK (national_id_type IS NULL OR national_id_type IN ('CCCD','CMND','PASSPORT')),
  CONSTRAINT person_verification_ck CHECK (verification_status IN ('UNVERIFIED','VERIFIED','VNEID_VERIFIED','MISMATCH')),
  CONSTRAINT person_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT')),
  CONSTRAINT person_si_book_ck CHECK (si_book_custody IS NULL OR si_book_custody IN ('EMPLOYER','EMPLOYEE','RETURNED')),
  CONSTRAINT person_last4_ck CHECK (national_id_last4 IS NULL OR length(national_id_last4) <= 4)
);
CREATE UNIQUE INDEX person_national_id_uq ON hr.person (tenant_id, national_id_bidx) WHERE national_id_bidx IS NOT NULL;
CREATE INDEX person_tax_code_ix  ON hr.person (tenant_id, tax_code_bidx)  WHERE tax_code_bidx IS NOT NULL;
CREATE INDEX person_si_number_ix ON hr.person (tenant_id, si_number_bidx) WHERE si_number_bidx IS NOT NULL;
CREATE INDEX person_name_trgm_ix ON hr.person USING gin (full_name_search gin_trgm_ops);  -- NFR-PPL-009
COMMENT ON COLUMN hr.person.national_id_enc IS 'pii:P3 AES-256-GCM, subject DEK';
COMMENT ON COLUMN hr.person.tax_code_enc    IS 'pii:P3 AES-256-GCM, subject DEK';
COMMENT ON COLUMN hr.person.si_number_enc   IS 'pii:P3 AES-256-GCM, subject DEK';
COMMENT ON COLUMN hr.person.hi_card_no_enc  IS 'pii:P3 AES-256-GCM, subject DEK (health-insurance card number)';
COMMENT ON COLUMN hr.person.ethnicity_enc   IS 'pii:P3 sensitive (ethnic origin)';
COMMENT ON COLUMN hr.person.religion_enc    IS 'pii:P3 sensitive (religion)';
COMMENT ON COLUMN hr.person.birth_date      IS 'pii:P2';
COMMENT ON COLUMN hr.person.full_name       IS 'pii:P1';

-- -----------------------------------------------------------------------------
-- 1a. PERSON IDENTITY DOCUMENT  (L3; BR-PPL-011)  additional documents only:
--     passport (VN or foreign), work visa, temporary/permanent residence card, the
--     old CMND / old CCCD kept for history and SI/tax matching. The CURRENT national
--     identifier stays on hr.person. Work permits are hr.work_permit (section 11).
-- -----------------------------------------------------------------------------
CREATE TABLE hr.person_identity_doc (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id            uuid NOT NULL,
  doc_type             text NOT NULL,
  doc_no_enc           bytea NOT NULL,                      -- pii:P3
  doc_no_bidx          bytea NOT NULL,                      -- duplicate check (passport no + issuing country, BR-PPL-001)
  doc_no_last4         text  NOT NULL,
  issuing_country_code char(2) NOT NULL DEFAULT 'VN',       -- core.country (global, by code)
  issue_date           date,
  issue_place          text,                                -- pii:P2
  expiry_date          date,                                -- expiry alerts (BR-PPL-011)
  visa_type_code       text,                                -- code list (visa / residence-card category)
  document_id          uuid,                                -- -> core.document (soft): scan
  status               text NOT NULL DEFAULT 'ACTIVE',
  subject_key_version  int  NOT NULL DEFAULT 1,
  source               text NOT NULL DEFAULT 'APP',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT person_identity_doc_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT person_identity_doc_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT person_identity_doc_type_ck CHECK (doc_type IN ('PASSPORT','WORK_VISA','TEMP_RESIDENCE_CARD',
                                                             'PERMANENT_RESIDENCE_CARD','OLD_CMND','OLD_CCCD','OTHER')),
  CONSTRAINT person_identity_doc_status_ck CHECK (status IN ('ACTIVE','EXPIRED','REVOKED','SUPERSEDED')),
  CONSTRAINT person_identity_doc_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT')),
  CONSTRAINT person_identity_doc_dates_ck CHECK (expiry_date IS NULL OR issue_date IS NULL OR expiry_date > issue_date),
  CONSTRAINT person_identity_doc_last4_ck CHECK (length(doc_no_last4) <= 4)
);
CREATE INDEX person_identity_doc_person_ix ON hr.person_identity_doc (tenant_id, person_id);
-- the same live document number cannot belong to two persons of the tenant
CREATE UNIQUE INDEX person_identity_doc_no_uq ON hr.person_identity_doc (tenant_id, doc_type, issuing_country_code, doc_no_bidx)
  WHERE status = 'ACTIVE';
CREATE INDEX person_identity_doc_expiry_ix ON hr.person_identity_doc (tenant_id, expiry_date) WHERE status = 'ACTIVE';
COMMENT ON COLUMN hr.person_identity_doc.doc_no_enc IS 'pii:P3 AES-256-GCM, subject DEK of the person';
COMMENT ON COLUMN hr.person_identity_doc.issue_place IS 'pii:P2';

-- -----------------------------------------------------------------------------
-- 1b. PERSON ADDRESS  (Pattern A; BR-PPL-010)
--     Two-level administrative units since the 2025 restructure: province_code +
--     commune_code -> core.admin_unit (global, by code). Addresses supplied in the
--     pre-2025 three-level format (opening-balance import, old documents) keep the
--     legacy_* codes; the importer maps them via core.admin_unit_mapping and fills
--     province_code/commune_code (mapping_status).
-- -----------------------------------------------------------------------------
CREATE TABLE hr.person_address (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id             uuid NOT NULL,
  address_type          text NOT NULL,                      -- PERMANENT (thường trú) | CURRENT (nơi ở hiện tại) | TEMPORARY (tạm trú) | HOMETOWN (quê quán) | BIRTH_PLACE
  country_code          char(2) NOT NULL DEFAULT 'VN',      -- core.country (global, by code)
  province_code         text,                               -- core.admin_unit level PROVINCE (global, by code)
  commune_code          text,                               -- core.admin_unit level COMMUNE (global, by code)
  address_line          text,                               -- street, house no., hamlet
  legacy_province_code  text,                               -- pre-2025 three-level codes (core.admin_unit, valid_to set)
  legacy_district_code  text,
  legacy_commune_code   text,
  legacy_address_text   text,                               -- address as supplied, kept for evidence
  mapping_status        text NOT NULL DEFAULT 'NOT_NEEDED', -- NOT_NEEDED | MAPPED | UNMAPPED (blocks import commit, §13.4)
  valid_from            date NOT NULL,
  valid_to              date,                               -- EXCLUSIVE; NULL = open
  valid_during          daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  source                text NOT NULL DEFAULT 'APP',
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT person_address_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT person_address_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT person_address_type_ck CHECK (address_type IN ('PERMANENT','CURRENT','TEMPORARY','HOMETOWN','BIRTH_PLACE')),
  CONSTRAINT person_address_mapping_ck CHECK (mapping_status IN ('NOT_NEEDED','MAPPED','UNMAPPED')),
  CONSTRAINT person_address_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT')),
  CONSTRAINT person_address_vn_ck CHECK (country_code <> 'VN' OR province_code IS NOT NULL OR legacy_province_code IS NOT NULL),
  CONSTRAINT person_address_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT person_address_period_ex EXCLUDE USING gist
    (tenant_id WITH =, person_id WITH =, address_type WITH =, valid_during WITH &&)
);
CREATE INDEX person_address_person_ix ON hr.person_address (tenant_id, person_id, address_type, valid_from);
CREATE INDEX person_address_unmapped_ix ON hr.person_address (tenant_id) WHERE mapping_status = 'UNMAPPED';
COMMENT ON TABLE hr.person_address IS 'pii:P2 addresses, Pattern A (valid time). Two-level admin units (2025) + legacy three-level codes.';
COMMENT ON COLUMN hr.person_address.address_line IS 'pii:P2';
COMMENT ON COLUMN hr.person_address.legacy_address_text IS 'pii:P2';

-- -----------------------------------------------------------------------------
-- 1c. PERSON CONTACT  (phones, e-mails)
-- -----------------------------------------------------------------------------
CREATE TABLE hr.person_contact (
  tenant_id       uuid NOT NULL,
  id              uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id       uuid NOT NULL,
  contact_type    text NOT NULL,
  contact_value   text NOT NULL,                            -- pii:P2 (WORK_* are P1)
  is_primary      boolean NOT NULL DEFAULT false,           -- one primary per type
  is_verified     boolean NOT NULL DEFAULT false,
  verified_at     timestamptz,
  source          text NOT NULL DEFAULT 'APP',
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid,
  version         int NOT NULL DEFAULT 0,
  CONSTRAINT person_contact_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT person_contact_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT person_contact_type_ck CHECK (contact_type IN ('MOBILE','HOME_PHONE','PERSONAL_EMAIL','WORK_EMAIL','WORK_PHONE','OTHER')),
  CONSTRAINT person_contact_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT'))
);
CREATE INDEX person_contact_person_ix ON hr.person_contact (tenant_id, person_id);
CREATE UNIQUE INDEX person_contact_primary_uq ON hr.person_contact (tenant_id, person_id, contact_type) WHERE is_primary;
CREATE INDEX person_contact_value_ix ON hr.person_contact (tenant_id, lower(contact_value));   -- duplicate check (§13.4)
COMMENT ON COLUMN hr.person_contact.contact_value IS 'pii:P2 personal phone / e-mail (WORK_EMAIL, WORK_PHONE: P1)';

-- -----------------------------------------------------------------------------
-- 1d. TAX RESIDENCY  (H2; Pattern A; BR-PPL-023, GAP-048)
--     PIT residency status per person, effective-dated. People does NOT compute the
--     183-day test: Payroll proposes changes (BR-PAY-065), HR approves them here;
--     approved changes are published in people.PersonalDataChanged (OUT-15).
--     Non-overlap applies to APPROVED rows; proposals stay DRAFT/PENDING_APPROVAL
--     until the service closes the old row and approves the new one in one transaction.
-- -----------------------------------------------------------------------------
CREATE TABLE hr.tax_residency (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id             uuid NOT NULL,
  status                text NOT NULL,                      -- RESIDENT | NON_RESIDENT
  basis                 text NOT NULL,
  valid_from            date NOT NULL,
  valid_to              date,                               -- EXCLUSIVE; NULL = open
  valid_during          daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  arrival_date          date,                               -- first entry into VN relevant to this status
  departure_date        date,                               -- (planned) final departure
  tax_year              smallint,                           -- year the 183-day count refers to (optional)
  days_in_vn            smallint,                           -- days present in tax_year (optional, informative)
  evidence_document_id  uuid,                               -- -> core.document (soft): residence card, lease, ...
  source                text NOT NULL DEFAULT 'APP',        -- APP (HR) | PAYROLL_PROPOSAL (183-day check) | OPENING_LOAD | IMPORT
  proposal_ref          text,                               -- payroll 183-day check run / proposal id
  approval_status       text NOT NULL DEFAULT 'DRAFT',
  approved_by           uuid,
  approved_at           timestamptz,
  decision_id           uuid,                               -- optional hr.decision
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT tax_residency_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT tax_residency_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT tax_residency_status_ck CHECK (status IN ('RESIDENT','NON_RESIDENT')),
  -- basis: >= 183 days in a calendar year | 12 consecutive months from arrival | permanent
  -- residence (registered/residence card) | lease >= 183 days; NON_RESIDENT: tests not met
  -- or treaty tie-breaker. [verify-legal] PIT Law, Circular 111/2013
  CONSTRAINT tax_residency_basis_ck CHECK (
       (status = 'RESIDENT'     AND basis IN ('DAYS_183','MONTHS_12','PERMANENT_RESIDENCE','LEASE_183'))
    OR (status = 'NON_RESIDENT' AND basis IN ('NOT_MET','TREATY_TIE_BREAKER'))),
  CONSTRAINT tax_residency_source_ck CHECK (source IN ('APP','PAYROLL_PROPOSAL','OPENING_LOAD','IMPORT')),
  CONSTRAINT tax_residency_approval_ck CHECK (approval_status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','CANCELLED')),
  CONSTRAINT tax_residency_approved_ck CHECK (approval_status <> 'APPROVED' OR approved_at IS NOT NULL
                                              OR source IN ('OPENING_LOAD','IMPORT')),
  CONSTRAINT tax_residency_days_ck CHECK (days_in_vn IS NULL OR days_in_vn BETWEEN 0 AND 366),
  CONSTRAINT tax_residency_travel_ck CHECK (departure_date IS NULL OR arrival_date IS NULL OR departure_date >= arrival_date),
  CONSTRAINT tax_residency_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT tax_residency_period_ex EXCLUDE USING gist
    (tenant_id WITH =, person_id WITH =, valid_during WITH &&) WHERE (approval_status = 'APPROVED')
);
CREATE INDEX tax_residency_person_ix ON hr.tax_residency (tenant_id, person_id, valid_from);
CREATE INDEX tax_residency_pending_ix ON hr.tax_residency (tenant_id, approval_status) WHERE approval_status IN ('DRAFT','PENDING_APPROVAL');
COMMENT ON TABLE hr.tax_residency IS
  'H2 / BR-PPL-023: PIT tax residency per person, Pattern A. Approved rows never overlap. A person without rows is '
  'treated by Payroll as RESIDENT when nationality = VN, else as NON_RESIDENT until HR records a status (service rule).';
COMMENT ON COLUMN hr.tax_residency.status IS 'pii:P2';

-- Entries into / exits from Vietnam (BR-PPL-023 "entry date(s) and exit date(s)").
-- Input for Payroll's 183-day report; People does not compute residency from it.
CREATE TABLE hr.vn_presence_period (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id        uuid NOT NULL,
  entry_date       date NOT NULL,
  exit_date        date,                                    -- INCLUSIVE last day in VN; NULL = still in VN
  presence_period  daterange GENERATED ALWAYS AS (daterange(entry_date, exit_date, '[]')) STORED,
  evidence_document_id uuid,                                -- -> core.document (soft): passport stamps, e-visa
  source           text NOT NULL DEFAULT 'APP',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT vn_presence_period_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT vn_presence_period_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT vn_presence_period_dates_ck CHECK (exit_date IS NULL OR exit_date >= entry_date),
  CONSTRAINT vn_presence_period_source_ck CHECK (source IN ('APP','OPENING_LOAD','IMPORT')),
  CONSTRAINT vn_presence_period_ex EXCLUDE USING gist (tenant_id WITH =, person_id WITH =, presence_period WITH &&)
);
COMMENT ON TABLE hr.vn_presence_period IS 'pii:P2 travel dates (entry/exit VN) for the 183-day test run by Payroll (BR-PAY-065).';

-- -----------------------------------------------------------------------------
-- 1e. EDUCATION QUALIFICATION  (minimal, BR-PPL-019 restored by R3-07)
--     Only what the labour management book (sổ quản lý lao động, Decree 145/2020
--     Art. 3 [verify-legal]) needs: professional/technical qualification level and
--     vocational skill level, flagged is_highest. Full education history and
--     certificates remain P2 (hr.certificate below).
-- -----------------------------------------------------------------------------
CREATE TABLE hr.education_qualification (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id                 uuid NOT NULL,
  qualification_level_code  text NOT NULL,                  -- code list QUALIFICATION_LEVEL (trình độ chuyên môn kỹ thuật, statutory)
  vocational_skill_level_code text,                         -- code list (bậc trình độ kỹ năng nghề), optional
  general_education_code    text,                           -- code list (trình độ giáo dục phổ thông, e.g. 12/12), optional
  field_of_study            text,
  institution_name          text,
  graduation_year           smallint,
  is_highest                boolean NOT NULL DEFAULT false, -- the value printed in the labour book
  document_id               uuid,                           -- -> core.document (soft): diploma scan
  source                    text NOT NULL DEFAULT 'APP',
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT education_qualification_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT education_qualification_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT education_qualification_year_ck CHECK (graduation_year IS NULL OR graduation_year BETWEEN 1900 AND 2100),
  CONSTRAINT education_qualification_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT'))
);
CREATE INDEX education_qualification_person_ix ON hr.education_qualification (tenant_id, person_id);
CREATE UNIQUE INDEX education_qualification_highest_uq ON hr.education_qualification (tenant_id, person_id) WHERE is_highest;

-- =============================================================================
-- 2. EMPLOYMENT  (engagement with one legal entity; concurrent employments allowed)
-- =============================================================================
CREATE TABLE hr.employment (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id               uuid NOT NULL,
  legal_entity_id         uuid NOT NULL,                    -- -> core.legal_entity (soft)
  employee_no             text NOT NULL,
  hire_type               text NOT NULL DEFAULT 'NEW',
  hire_date               date NOT NULL,
  original_hire_date      date,                             -- first hire in the group (rehire)
  seniority_date          date,
  probation_end_date      date,
  end_date                date,                             -- termination date, INCLUSIVE business date
  last_working_date       date,
  employment_period       daterange GENERATED ALWAYS AS (daterange(hire_date, end_date, '[]')) STORED,
  -- v0.3: the v0.2 column is_primary is replaced by the effective-dated table
  -- hr.primary_employment (section 2a), because the primary employment of a person
  -- can change while both employments continue (R2-16, GAP-031).
  status                  text NOT NULL DEFAULT 'PRE_HIRE',
  worker_category_code    text NOT NULL,                    -- code list: EMPLOYEE | EXPAT | APPRENTICE | COLLABORATOR ...
  employee_category_code  text,                             -- tenant code list
  working_regime_code     text,                             -- contractual regime only; schedule assignment is T&A
  is_rehire_eligible      boolean,
  previous_employment_id  uuid,
  rec_hire_id             uuid,                             -- -> rec.hire_handoff (soft)
  source                  text NOT NULL DEFAULT 'APP',
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT employment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT employment_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT employment_prev_fk FOREIGN KEY (tenant_id, previous_employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT employment_no_uq UNIQUE (tenant_id, employee_no),
  CONSTRAINT employment_hire_type_ck CHECK (hire_type IN ('NEW','REHIRE','CONCURRENT','TRANSFER_IN')),
  CONSTRAINT employment_status_ck CHECK (status IN ('PRE_HIRE','ACTIVE','SUSPENDED','TERMINATED','CANCELLED')),
  CONSTRAINT employment_dates_ck CHECK (end_date IS NULL OR end_date >= hire_date),
  CONSTRAINT employment_lwd_ck CHECK (last_working_date IS NULL OR last_working_date >= hire_date),
  CONSTRAINT employment_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT')),
  -- target of the composite FKs from hr.primary_employment, hr.insurance_participation,
  -- hr.work_permit, hr.special_status, hr.hr_document: the employment belongs to that person
  CONSTRAINT employment_person_id_uq UNIQUE (tenant_id, person_id, id),
  -- one person has at most one live employment per legal entity at a time
  -- (concurrent work in the same LE is a CONCURRENT assignment, not a second employment)
  CONSTRAINT employment_person_le_ex EXCLUDE USING gist
    (tenant_id WITH =, person_id WITH =, legal_entity_id WITH =, employment_period WITH &&)
    WHERE (status <> 'CANCELLED')
);
CREATE INDEX employment_le_status_ix ON hr.employment (tenant_id, legal_entity_id, status);
CREATE INDEX employment_person_ix ON hr.employment (tenant_id, person_id);

-- =============================================================================
-- 2a. PRIMARY EMPLOYMENT  (R2-16, GAP-031, BR-PPL-004; valid time, DESIGN §5.1)
-- One person may hold several employments with different legal entities of the
-- tenant. For PIT_FAMILY_DEDUCTION exactly one of them is "primary" on any day: the
-- employer that applies the family (self + dependant) deduction; dependants are
-- claimed via this employment (hr.pit_dependant).
-- v0.4 (H1): the purpose SOCIAL_INSURANCE is withdrawn. Insurance is decided per
-- insurance TYPE by the legal parameter insurance.multi_contract_rule (R3-03) and the
-- outcome is recorded per employment in hr.insurance_participation (section 2b).
-- The column purpose is kept (single value today) so that a future purpose does not
-- need a new table.
-- "At most one primary employment per person per period" = primary_employment_ex.
-- Coverage (a designation exists whenever the person has 2+ live employments) and
-- "designation lies inside the employment period" are checked by the People service
-- and by the nightly consistency job, not by a constraint (they span rows/tables).
-- =============================================================================
CREATE TABLE hr.primary_employment (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id        uuid NOT NULL,
  employment_id    uuid NOT NULL,
  purpose          text NOT NULL DEFAULT 'PIT_FAMILY_DEDUCTION',
  valid_from       date NOT NULL,
  valid_to         date,                                      -- EXCLUSIVE; NULL = open
  valid_during     daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  status           text NOT NULL DEFAULT 'ACTIVE',
  reason_code      text,                                      -- EMPLOYEE_REGISTRATION | PRIMARY_ENDED | FIRST_EMPLOYMENT ...
  decision_id      uuid,                                      -- optional hr.decision
  source           text NOT NULL DEFAULT 'APP',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT primary_employment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT primary_employment_employment_fk FOREIGN KEY (tenant_id, person_id, employment_id)
    REFERENCES hr.employment (tenant_id, person_id, id),
  CONSTRAINT primary_employment_purpose_ck CHECK (purpose IN ('PIT_FAMILY_DEDUCTION')),   -- v0.4 (H1): SOCIAL_INSURANCE withdrawn
  CONSTRAINT primary_employment_status_ck CHECK (status IN ('ACTIVE','CANCELLED')),
  CONSTRAINT primary_employment_source_ck CHECK (source IN ('APP','OPENING_LOAD','IMPORT')),
  CONSTRAINT primary_employment_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  -- at most one primary employment per person, per purpose, per period
  CONSTRAINT primary_employment_ex EXCLUDE USING gist
    (tenant_id WITH =, person_id WITH =, purpose WITH =, valid_during WITH &&)
    WHERE (status = 'ACTIVE')
);
CREATE INDEX primary_employment_employment_ix ON hr.primary_employment (tenant_id, employment_id);
COMMENT ON TABLE hr.primary_employment IS
  'R2-16: primary employment per person for the PIT family deduction, effective-dated. '
  'A person with a single employment needs no row (that employment is primary by default). '
  'Insurance participation is NOT designated here (v0.4, H1): see hr.insurance_participation.';

-- =============================================================================
-- 2b. INSURANCE PARTICIPATION  (H1; R3-03, R2-16, BR-PPL-004; Pattern A)
-- Per employment and insurance type, whether the employment participates in the
-- compulsory insurance on each day:
--   SI    = BHXH (social insurance)           HI    = BHYT (health insurance)
--   UI    = BHTN (unemployment insurance)     OA_OD = BHTNLĐ-BNN (occupational accident / disease)
-- The RULE is a legal parameter per insurance type, insurance.multi_contract_rule
-- (Legal Parameter Service; defaults SI/UI/HI = FIRST_CONTRACT (sponsor, R3-03),
-- OA_OD = EACH_EMPLOYER; all [verify-legal]). This table records the OUTCOME the People
-- service derived, with the rule value applied (basis_rule) and the pack it came from,
-- so Payroll (BR-PAY-081) and audits can see why an employment does or does not
-- contribute. Rows are re-derived when an employment starts/ends, when the salary
-- changes (HIGHEST_SALARY) or when a new legal pack changes the rule; HR may override
-- with a reason (is_manual_override).
-- One person with a single employment still gets rows (one per type), so Payroll never
-- has to infer participation.
-- =============================================================================
CREATE TABLE hr.insurance_participation (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id              uuid NOT NULL,                      -- denormalised for the per-person rule (composite FK below)
  employment_id          uuid NOT NULL,
  insurance_type         text NOT NULL,
  valid_from             date NOT NULL,
  valid_to               date,                               -- EXCLUSIVE; NULL = open
  valid_during           daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  is_participating       boolean NOT NULL,
  basis_rule             text,                               -- rule value applied (from the legal pack); NULL only when exempt
  exemption_reason_code  text,                               -- code list INSURANCE_EXEMPTION (pensioner, foreigner < 12 months,
                                                             -- contract < 1 month, ...) [verify-legal]
  legal_pack_id          uuid,                               -- -> saas.legal_param_pack (soft): pack that supplied the rule
  legal_pack_version     bigint,                             -- saas.legal_param_pack.pack_version (readable without a lookup)
  decided_at             timestamptz NOT NULL DEFAULT now(),
  decided_by             uuid,                               -- NULL = rule engine (system)
  is_manual_override     boolean NOT NULL DEFAULT false,
  override_reason        text,
  status                 text NOT NULL DEFAULT 'ACTIVE',
  source                 text NOT NULL DEFAULT 'APP',
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT insurance_participation_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT insurance_participation_employment_fk FOREIGN KEY (tenant_id, person_id, employment_id)
    REFERENCES hr.employment (tenant_id, person_id, id),
  CONSTRAINT insurance_participation_type_ck CHECK (insurance_type IN ('SI','HI','UI','OA_OD')),
  CONSTRAINT insurance_participation_rule_ck CHECK (basis_rule IS NULL OR basis_rule IN ('FIRST_CONTRACT','HIGHEST_SALARY','EACH_EMPLOYER')),
  -- every row is explained either by the applied rule or by an exemption
  CONSTRAINT insurance_participation_basis_ck CHECK (basis_rule IS NOT NULL OR exemption_reason_code IS NOT NULL),
  -- an exempt employment does not participate
  CONSTRAINT insurance_participation_exempt_ck CHECK (exemption_reason_code IS NULL OR NOT is_participating),
  CONSTRAINT insurance_participation_override_ck CHECK (NOT is_manual_override OR override_reason IS NOT NULL),
  CONSTRAINT insurance_participation_status_ck CHECK (status IN ('ACTIVE','CANCELLED')),
  CONSTRAINT insurance_participation_source_ck CHECK (source IN ('APP','OPENING_LOAD','IMPORT')),
  CONSTRAINT insurance_participation_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  -- one decision per employment, insurance type and day
  CONSTRAINT insurance_participation_period_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, insurance_type WITH =, valid_during WITH &&)
    WHERE (status = 'ACTIVE'),
  -- single-payer rules: at most one participating employment per person, type and day
  -- (EACH_EMPLOYER rows are exempt from this constraint by design)
  CONSTRAINT insurance_participation_single_ex EXCLUDE USING gist
    (tenant_id WITH =, person_id WITH =, insurance_type WITH =, valid_during WITH &&)
    WHERE (status = 'ACTIVE' AND is_participating AND basis_rule IN ('FIRST_CONTRACT','HIGHEST_SALARY'))
);
CREATE INDEX insurance_participation_employment_ix ON hr.insurance_participation (tenant_id, employment_id, insurance_type, valid_from);
CREATE INDEX insurance_participation_person_ix ON hr.insurance_participation (tenant_id, person_id, insurance_type, valid_from);
COMMENT ON TABLE hr.insurance_participation IS
  'H1 / R3-03: outcome of insurance.multi_contract_rule per employment and insurance type (SI, HI, UI, OA_OD), Pattern A. '
  'Published to Payroll (people.InsuranceParticipationChanged, proposed; BR-PAY-081).';

-- =============================================================================
-- 3. DECISION  (quyết định: hire, transfer, salary change, termination ...)
-- =============================================================================
CREATE TABLE hr.decision (
  tenant_id           uuid NOT NULL,
  id                  uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id     uuid NOT NULL,                        -- soft core
  decision_no         text NOT NULL,
  decision_type_code  text NOT NULL,
  employment_id       uuid,                                 -- subject (NULL for collective decisions)
  sign_date           date,
  effective_date      date NOT NULL,
  signatory_name      text,
  signatory_title     text,
  document_id         uuid,                                 -- -> core.document (soft)
  workflow_instance_id uuid,                                -- -> core.workflow_instance (soft)
  status              text NOT NULL DEFAULT 'DRAFT',
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid,
  version             int NOT NULL DEFAULT 0,
  CONSTRAINT decision_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT decision_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT decision_no_uq UNIQUE (tenant_id, legal_entity_id, decision_no),
  CONSTRAINT decision_status_ck CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','SIGNED','CANCELLED'))
);
CREATE INDEX decision_employment_ix ON hr.decision (tenant_id, employment_id);

-- FKs to hr.decision from tables created before it
ALTER TABLE hr.primary_employment ADD CONSTRAINT primary_employment_decision_fk
  FOREIGN KEY (tenant_id, decision_id) REFERENCES hr.decision (tenant_id, id);
ALTER TABLE hr.tax_residency ADD CONSTRAINT tax_residency_decision_fk
  FOREIGN KEY (tenant_id, decision_id) REFERENCES hr.decision (tenant_id, id);

-- =============================================================================
-- 4. ASSIGNMENT  (BI-TEMPORAL, DESIGN §5.2)
--    assignment_key = stable identity of one assignment line across versions
--    (an employment can hold one PRIMARY plus CONCURRENT/SECONDMENT lines).
-- =============================================================================
CREATE TABLE hr.assignment (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  assignment_key          uuid NOT NULL,
  employment_id           uuid NOT NULL,
  assignment_type         text NOT NULL DEFAULT 'PRIMARY',
  legal_entity_id         uuid NOT NULL,                    -- host LE (soft core); = employment LE unless seconded
  paying_legal_entity_id  uuid NOT NULL,                    -- soft core
  position_id             uuid,                             -- soft core.position
  org_unit_id             uuid NOT NULL,                    -- soft core.org_unit (data-scope key)
  job_title_id            uuid,                             -- soft core.job_title
  grade_id                uuid,                             -- soft core.grade
  manager_employment_id   uuid,                             -- line manager (same schema)
  location_id             uuid,                             -- soft core.location
  cost_centre_id          uuid,                             -- soft core.cost_centre (primary)
  cost_split              jsonb,                            -- [{"cost_centre_id":..,"pct":60},..]; NULL = 100% primary
  fte                     numeric(5,4) NOT NULL DEFAULT 1.0,
  action_code             text NOT NULL,                    -- HIRE | TRANSFER | PROMOTION | DEMOTION | SECONDMENT | DATA_CHANGE ...
  reason_code             text,
  decision_id             uuid,
  -- valid time
  valid_from              date NOT NULL,
  valid_to                date,
  valid_during            daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  -- transaction time
  recorded_at             timestamptz NOT NULL DEFAULT now(),
  superseded_at           timestamptz,
  superseded_by           uuid,
  correction_reason       text,
  source                  text NOT NULL DEFAULT 'APP',
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT assignment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT assignment_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT assignment_manager_fk FOREIGN KEY (tenant_id, manager_employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT assignment_decision_fk FOREIGN KEY (tenant_id, decision_id) REFERENCES hr.decision (tenant_id, id),
  CONSTRAINT assignment_superseded_by_fk FOREIGN KEY (tenant_id, superseded_by) REFERENCES hr.assignment (tenant_id, id),
  CONSTRAINT assignment_type_ck CHECK (assignment_type IN ('PRIMARY','CONCURRENT','SECONDMENT','TEMP_TRANSFER')),
  CONSTRAINT assignment_fte_ck CHECK (fte > 0 AND fte <= 1),
  CONSTRAINT assignment_cost_split_ck CHECK (cost_split IS NULL OR jsonb_typeof(cost_split) = 'array'),
  CONSTRAINT assignment_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT assignment_tx_ck CHECK (superseded_at IS NULL OR superseded_at >= recorded_at),
  CONSTRAINT assignment_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT','REORG')),
  -- no overlapping versions of the same assignment line (current knowledge only)
  CONSTRAINT assignment_key_period_ex EXCLUDE USING gist
    (tenant_id WITH =, assignment_key WITH =, valid_during WITH &&) WHERE (superseded_at IS NULL),
  -- exactly one PRIMARY assignment per employment at any date (current knowledge)
  CONSTRAINT assignment_primary_period_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, valid_during WITH &&)
    WHERE (assignment_type = 'PRIMARY' AND superseded_at IS NULL)
);
CREATE INDEX assignment_current_ix ON hr.assignment (tenant_id, employment_id, valid_from) WHERE superseded_at IS NULL;
CREATE INDEX assignment_history_ix ON hr.assignment (tenant_id, employment_id, recorded_at);
CREATE INDEX assignment_scope_ix   ON hr.assignment (tenant_id, org_unit_id, valid_from) WHERE superseded_at IS NULL;
CREATE INDEX assignment_position_ix ON hr.assignment (tenant_id, position_id, valid_from) WHERE superseded_at IS NULL;
CREATE INDEX assignment_manager_ix ON hr.assignment (tenant_id, manager_employment_id) WHERE superseded_at IS NULL;
CREATE INDEX assignment_recorded_ix ON hr.assignment (tenant_id, recorded_at);   -- retro detection by watermark

-- =============================================================================
-- 5. SALARY RECORD  (BI-TEMPORAL; agreed compensation incl. GROSS/NET; gap §5 #24,
--    BR-PPL-040). source OPENING_LOAD = go-live employee-master import (ADR-0037, §13).
-- =============================================================================
CREATE TABLE hr.salary_record (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id        uuid NOT NULL,
  pay_basis            text NOT NULL DEFAULT 'GROSS',        -- GROSS | NET
  salary_kind          text NOT NULL DEFAULT 'MONTHLY',      -- MONTHLY | DAILY | HOURLY | PIECE
  base_salary_amount   sys.money_amount NOT NULL,           -- pii:P2 (contract salary)
  agreed_net_amount    sys.money_amount,                    -- pii:P2 required when pay_basis = NET
  net_guaranteed_scope jsonb,                               -- NET only: component codes guaranteed net, e.g. ["BASE","ALW_FIXED"];
                                                            -- everything else (bonus, OT) is gross (BR-PPL-040)
  si_salary_amount     sys.money_amount,                    -- pii:P2 salary for compulsory insurance
  currency_code        sys.currency_code NOT NULL DEFAULT 'VND',
  pay_scale_code       text,                                -- -> pay.pay_scale (soft, by code)
  pay_scale_step_code  text,                                -- bậc
  probation_pct        numeric(5,2),                        -- % of salary during probation (legal floor in pack)
  action_code          text NOT NULL,                       -- HIRE | INCREASE | PROMOTION | ADJUSTMENT | CORRECTION
  reason_code          text,
  decision_id          uuid,
  contract_appendix_id uuid,                                -- approving appendix (BR-PPL-040: decision OR appendix; service rule)
  -- valid time
  valid_from           date NOT NULL,
  valid_to             date,
  valid_during         daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  -- transaction time
  recorded_at          timestamptz NOT NULL DEFAULT now(),
  superseded_at        timestamptz,
  superseded_by        uuid,
  correction_reason    text,
  source               text NOT NULL DEFAULT 'APP',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT salary_record_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT salary_record_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT salary_record_decision_fk FOREIGN KEY (tenant_id, decision_id) REFERENCES hr.decision (tenant_id, id),
  CONSTRAINT salary_record_superseded_by_fk FOREIGN KEY (tenant_id, superseded_by) REFERENCES hr.salary_record (tenant_id, id),
  CONSTRAINT salary_record_basis_ck CHECK (pay_basis IN ('GROSS','NET')),
  CONSTRAINT salary_record_net_ck CHECK (pay_basis = 'GROSS' OR agreed_net_amount IS NOT NULL),
  CONSTRAINT salary_record_net_scope_ck CHECK (net_guaranteed_scope IS NULL
                                               OR (pay_basis = 'NET' AND jsonb_typeof(net_guaranteed_scope) = 'array')),
  CONSTRAINT salary_record_kind_ck CHECK (salary_kind IN ('MONTHLY','DAILY','HOURLY','PIECE')),
  CONSTRAINT salary_record_amounts_ck CHECK (base_salary_amount >= 0 AND coalesce(si_salary_amount, 0) >= 0
                                             AND coalesce(agreed_net_amount, 0) >= 0),
  CONSTRAINT salary_record_probation_ck CHECK (probation_pct IS NULL OR probation_pct BETWEEN 0 AND 100),
  CONSTRAINT salary_record_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT salary_record_tx_ck CHECK (superseded_at IS NULL OR superseded_at >= recorded_at),
  CONSTRAINT salary_record_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT')),
  CONSTRAINT salary_record_period_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, valid_during WITH &&) WHERE (superseded_at IS NULL)
);
CREATE INDEX salary_record_current_ix  ON hr.salary_record (tenant_id, employment_id, valid_from) WHERE superseded_at IS NULL;
CREATE INDEX salary_record_history_ix  ON hr.salary_record (tenant_id, employment_id, recorded_at);
CREATE INDEX salary_record_recorded_ix ON hr.salary_record (tenant_id, recorded_at);
COMMENT ON TABLE hr.salary_record IS
  'Bi-temporal. Never UPDATE business columns: supersede + insert (temporal-kit). Amounts are P2 plaintext (X-01, 2026-10-16): '
  'no column encryption; protected by RLS, module grants, hr.v_salary_record_masked, field masking and sensitive-read audit.';
COMMENT ON COLUMN hr.salary_record.base_salary_amount IS 'pii:P2 not encrypted (X-01); masked by app.mask_level = PII_AND_PAY';
COMMENT ON COLUMN hr.salary_record.agreed_net_amount  IS 'pii:P2 not encrypted (X-01); masked by app.mask_level = PII_AND_PAY';
COMMENT ON COLUMN hr.salary_record.si_salary_amount   IS 'pii:P2 not encrypted (X-01); masked by app.mask_level = PII_AND_PAY';

-- Contractual allowances that belong to one salary_record VERSION (immutable with it).
CREATE TABLE hr.salary_record_allowance (
  tenant_id          uuid NOT NULL,
  salary_record_id   uuid NOT NULL,
  component_code     text NOT NULL,                         -- -> pay.salary_component (soft, by code)
  amount             sys.money_amount NOT NULL,             -- pii:P2
  is_in_si_salary    boolean NOT NULL DEFAULT false,        -- included in SI salary basis
  CONSTRAINT salary_record_allowance_pk PRIMARY KEY (tenant_id, salary_record_id, component_code),
  CONSTRAINT salary_record_allowance_fk FOREIGN KEY (tenant_id, salary_record_id)
    REFERENCES hr.salary_record (tenant_id, id)
);
CREATE TRIGGER trg_immutable BEFORE UPDATE OR DELETE ON hr.salary_record_allowance
  FOR EACH ROW EXECUTE FUNCTION hr.tg_append_only();            -- D9: DELETE only by tenant purge
COMMENT ON COLUMN hr.salary_record_allowance.amount IS 'pii:P2 not encrypted (X-01); masked by app.mask_level = PII_AND_PAY';

-- =============================================================================
-- 6. CONTRACT  (labour contract / probation contract / service agreement)
-- =============================================================================
CREATE TABLE hr.contract (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id        uuid NOT NULL,
  contract_no          text NOT NULL,
  contract_type_code   text NOT NULL,                       -- code list: PROBATION | DEFINITE | INDEFINITE | SERVICE | APPRENTICE
  definite_seq         smallint,                            -- nth definite-term contract (conversion rule)
  sign_date            date,
  start_date           date NOT NULL,
  end_date             date,                                -- INCLUSIVE; NULL = indefinite
  contract_period      daterange GENERATED ALWAYS AS (daterange(start_date, end_date, '[]')) STORED,
  probation_days       smallint,
  signatory_name       text,
  signatory_title      text,
  esign_status         text NOT NULL DEFAULT 'NONE',
  national_platform_ref text,                               -- e-labour-contract platform id, if mandated (SaaS Q6)
  document_id          uuid,                                -- -> core.document (soft)
  previous_contract_id uuid,
  status               text NOT NULL DEFAULT 'DRAFT',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT contract_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT contract_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT contract_prev_fk FOREIGN KEY (tenant_id, previous_contract_id) REFERENCES hr.contract (tenant_id, id),
  CONSTRAINT contract_no_uq UNIQUE (tenant_id, contract_no),
  CONSTRAINT contract_employment_id_uq UNIQUE (tenant_id, employment_id, id),   -- target of hr.contract_suspension FK
  CONSTRAINT contract_dates_ck CHECK (end_date IS NULL OR end_date >= start_date),
  CONSTRAINT contract_esign_ck CHECK (esign_status IN ('NONE','SENT','PARTIALLY_SIGNED','SIGNED','DECLINED','EXPIRED')),
  CONSTRAINT contract_status_ck CHECK (status IN ('DRAFT','PENDING_SIGNATURE','ACTIVE','SUSPENDED',
                                                  'EXPIRED','TERMINATED','SUPERSEDED','CANCELLED')),
  -- contracts of one employment never overlap (probation then labour contract, renewals)
  CONSTRAINT contract_period_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, contract_period WITH &&)
    WHERE (status NOT IN ('DRAFT','CANCELLED','SUPERSEDED'))
);
CREATE INDEX contract_employment_ix ON hr.contract (tenant_id, employment_id, start_date);
CREATE INDEX contract_expiry_ix ON hr.contract (tenant_id, end_date) WHERE status = 'ACTIVE';   -- renewal alerts

CREATE TABLE hr.contract_appendix (
  tenant_id       uuid NOT NULL,
  id              uuid NOT NULL DEFAULT sys.uuid_v7(),
  contract_id     uuid NOT NULL,
  appendix_no     text NOT NULL,
  effective_date  date NOT NULL,
  changes         jsonb NOT NULL,                           -- structured summary of amended terms
  document_id     uuid,
  status          text NOT NULL DEFAULT 'DRAFT',
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid,
  version         int NOT NULL DEFAULT 0,
  CONSTRAINT contract_appendix_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT contract_appendix_contract_fk FOREIGN KEY (tenant_id, contract_id) REFERENCES hr.contract (tenant_id, id),
  CONSTRAINT contract_appendix_uq UNIQUE (tenant_id, contract_id, appendix_no),
  CONSTRAINT contract_appendix_status_ck CHECK (status IN ('DRAFT','PENDING_SIGNATURE','SIGNED','CANCELLED'))
);

ALTER TABLE hr.salary_record ADD CONSTRAINT salary_record_appendix_fk
  FOREIGN KEY (tenant_id, contract_appendix_id) REFERENCES hr.contract_appendix (tenant_id, id);

-- Contract suspension (tạm hoãn HĐLĐ, BR-PPL-056; Labour Code Art. 30-31 [verify-legal]).
-- While ACTIVE the employment status is SUSPENDED (service); people.ContractSuspended /
-- people.ContractResumed stop/restart pay and SI in Payroll (GAP-050).
CREATE TABLE hr.contract_suspension (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id      uuid NOT NULL,
  contract_id        uuid NOT NULL,
  reason_code        text NOT NULL,                         -- code list (statutory): MILITARY_SERVICE | DETENTION | MATERNITY_EXTENDED | ELECTED_OFFICE | AGREEMENT | OTHER
  valid_from         date NOT NULL,                         -- first suspended day
  valid_to           date,                                  -- EXCLUSIVE planned/actual end; NULL = open
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  return_date        date,                                  -- actual return to work
  decision_id        uuid,
  document_id        uuid,                                  -- -> core.document (soft)
  status             text NOT NULL DEFAULT 'DRAFT',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT contract_suspension_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT contract_suspension_contract_fk FOREIGN KEY (tenant_id, employment_id, contract_id)
    REFERENCES hr.contract (tenant_id, employment_id, id),
  CONSTRAINT contract_suspension_decision_fk FOREIGN KEY (tenant_id, decision_id) REFERENCES hr.decision (tenant_id, id),
  CONSTRAINT contract_suspension_status_ck CHECK (status IN ('DRAFT','APPROVED','ACTIVE','ENDED','CANCELLED')),
  CONSTRAINT contract_suspension_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT contract_suspension_return_ck CHECK (return_date IS NULL OR return_date >= valid_from),
  CONSTRAINT contract_suspension_period_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, valid_during WITH &&) WHERE (status IN ('APPROVED','ACTIVE','ENDED'))
);
CREATE INDEX contract_suspension_contract_ix ON hr.contract_suspension (tenant_id, contract_id);

-- =============================================================================
-- 7. FAMILY MEMBER and PIT DEPENDANT  (gap §5 #3: People owns dependants)
-- =============================================================================
CREATE TABLE hr.family_member (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id            uuid NOT NULL,                       -- the employee-person
  relationship_code    text NOT NULL,                       -- code list RELATIONSHIP (statutory)
  full_name            text NOT NULL,                       -- pii:P2
  birth_date           date,                                -- pii:P2
  national_id_enc      bytea,                               -- pii:P3
  national_id_bidx     bytea,
  national_id_last4    text,
  tax_code_enc         bytea,                               -- pii:P3 dependant tax code
  tax_code_bidx        bytea,
  tax_code_last4       text,
  has_income_above_threshold boolean,                       -- eligibility input (threshold is a legal param)
  is_disabled          boolean NOT NULL DEFAULT false,      -- pii:P3-derived flag (health); minimal
  is_deceased          boolean NOT NULL DEFAULT false,
  subject_key_version  int NOT NULL DEFAULT 1,              -- saas.subject_key (FAMILY_MEMBER, id)
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT family_member_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT family_member_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id)
);
CREATE INDEX family_member_person_ix ON hr.family_member (tenant_id, person_id);
CREATE INDEX family_member_nid_ix ON hr.family_member (tenant_id, national_id_bidx) WHERE national_id_bidx IS NOT NULL;
COMMENT ON COLUMN hr.family_member.national_id_enc IS 'pii:P3 AES-256-GCM, subject DEK of the family member';
COMMENT ON COLUMN hr.family_member.tax_code_enc    IS 'pii:P3 AES-256-GCM, subject DEK of the family member';
COMMENT ON COLUMN hr.family_member.full_name       IS 'pii:P2';
COMMENT ON COLUMN hr.family_member.birth_date      IS 'pii:P2';

CREATE TABLE hr.pit_dependant (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  family_member_id      uuid NOT NULL,
  employment_id         uuid NOT NULL,                      -- claiming employment (taxpayer side); must be the person's
                                                            -- PIT_FAMILY_DEDUCTION primary employment for those months
                                                            -- when the person has several (hr.primary_employment; service rule)
  from_month            sys.month_date NOT NULL,
  to_month              sys.month_date,                     -- INCLUSIVE last month; NULL = open
  month_range           daterange GENERATED ALWAYS AS
                          (daterange(from_month, (to_month + interval '1 month')::date, '[)')) STORED,
  dependant_reason_code text,                               -- child < 18, student, disabled, parent ...
  status                text NOT NULL DEFAULT 'DRAFT',
  tax_registration_ref  text,                               -- registration form / tax authority ref
  registered_at         date,
  approved_by           uuid,
  approved_at           timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT pit_dependant_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pit_dependant_member_fk FOREIGN KEY (tenant_id, family_member_id) REFERENCES hr.family_member (tenant_id, id),
  CONSTRAINT pit_dependant_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT pit_dependant_months_ck CHECK (to_month IS NULL OR to_month >= from_month),
  CONSTRAINT pit_dependant_status_ck CHECK (status IN ('DRAFT','PENDING','APPROVED','REJECTED','ENDED')),
  -- a dependant is claimed by only one taxpayer per month (within the tenant; cross-tenant
  -- duplicates are only detectable by the tax authority)
  CONSTRAINT pit_dependant_claim_ex EXCLUDE USING gist
    (tenant_id WITH =, family_member_id WITH =, month_range WITH &&)
    WHERE (status IN ('PENDING','APPROVED','ENDED'))
);
CREATE INDEX pit_dependant_employment_ix ON hr.pit_dependant (tenant_id, employment_id, from_month);

-- Emergency contacts (BR-PPL-018; P2 in mvp-scope R2-19, table kept because it is
-- trivial and avoids a free-text field that would need migrating later).
CREATE TABLE hr.emergency_contact (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id          uuid NOT NULL,
  family_member_id   uuid,                                  -- optional link when the contact is a family member
  full_name          text NOT NULL,                         -- pii:P2
  relationship_code  text,                                  -- code list RELATIONSHIP
  phone              text NOT NULL,                         -- pii:P2
  alt_phone          text,                                  -- pii:P2
  email              text,                                  -- pii:P2
  address_text       text,                                  -- pii:P2
  priority           smallint NOT NULL DEFAULT 1,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT emergency_contact_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT emergency_contact_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT emergency_contact_member_fk FOREIGN KEY (tenant_id, family_member_id) REFERENCES hr.family_member (tenant_id, id),
  CONSTRAINT emergency_contact_priority_uq UNIQUE (tenant_id, person_id, priority),
  CONSTRAINT emergency_contact_priority_ck CHECK (priority BETWEEN 1 AND 9)
);
COMMENT ON TABLE hr.emergency_contact IS 'pii:P2 third-party contact data (name, phones, address).';

-- =============================================================================
-- 8. BANK ACCOUNT  (employee salary accounts; owned by the PERSON; gap §5 #5)
-- =============================================================================
CREATE TABLE hr.bank_account (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id          uuid NOT NULL,
  bank_code          text NOT NULL,                         -- core.bank (global, by code)
  branch_code        text,
  account_no_enc     bytea NOT NULL,                        -- pii:P3
  account_no_bidx    bytea NOT NULL,
  account_no_last4   text  NOT NULL,
  holder_name        text  NOT NULL,                        -- as registered at the bank (diacritics per bank rule)
  currency_code      sys.currency_code NOT NULL DEFAULT 'VND',
  split_type         text NOT NULL DEFAULT 'REMAINDER',     -- PERCENT | FIXED | REMAINDER
  split_value        numeric(19,4),                         -- % or amount; NULL for REMAINDER
  priority           smallint NOT NULL DEFAULT 1,
  valid_from         date NOT NULL,
  valid_to           date,
  valid_during       daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  status             text NOT NULL DEFAULT 'PENDING_VERIFICATION',
  step_up_auth_ref   text,                                  -- ESS step-up evidence (NFR-PPL-011)
  verified_by        uuid,
  verified_at        timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT bank_account_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT bank_account_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT bank_account_split_ck CHECK (
       (split_type = 'REMAINDER' AND split_value IS NULL)
    OR (split_type = 'PERCENT'   AND split_value > 0 AND split_value <= 100)
    OR (split_type = 'FIXED'     AND split_value > 0)),
  CONSTRAINT bank_account_status_ck CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','REJECTED','CLOSED')),
  CONSTRAINT bank_account_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  -- one REMAINDER (default) account per person at any date
  CONSTRAINT bank_account_remainder_ex EXCLUDE USING gist
    (tenant_id WITH =, person_id WITH =, valid_during WITH &&)
    WHERE (split_type = 'REMAINDER' AND status = 'ACTIVE')
);
CREATE INDEX bank_account_person_ix ON hr.bank_account (tenant_id, person_id, valid_from);
CREATE INDEX bank_account_bidx_ix ON hr.bank_account (tenant_id, account_no_bidx);   -- shared-account fraud check
COMMENT ON COLUMN hr.bank_account.account_no_enc IS 'pii:P3 AES-256-GCM, subject DEK of the person';

-- =============================================================================
-- 9. SPECIAL STATUS  (BR-PPL-021; Pattern A)
--    Protection / entitlement flags consumed by T&A (reduced hours, OT and night-work
--    bans), Payroll and the separation guard (BR-PPL-073).
--    Person-level types: employment_id NULL. Job-level type HAZARDOUS_JOB: employment_id
--    required (per-employee override of the Core job-title flag, GAP-045: Core's flag
--    is the master, People only stores overrides with a reason).
--    Sensitive categories (health: PREGNANCY, DISABILITY; union / party membership,
--    BR-PPL-150) are P3: the TYPE stays plaintext because downstream rules and the
--    exclusion constraint need it (same approach as family_member.is_disabled), every
--    detail (disability degree, medical certificate data, union card no.) is in
--    detail_enc, and hr.v_special_status_masked hides sensitive types unless
--    app.mask_level = 'NONE' (fail closed). PARTY_MEMBER / YOUTH_UNION_MEMBER are
--    optional and need consent (saas consent registry, BR-PPL-151).
-- =============================================================================
CREATE TABLE hr.special_status (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id            uuid NOT NULL,
  employment_id        uuid,                                -- required for HAZARDOUS_JOB only
  scope_id             uuid GENERATED ALWAYS AS (coalesce(employment_id, person_id)) STORED,
  status_type          text NOT NULL,
  is_sensitive         boolean GENERATED ALWAYS AS
                         (status_type IN ('PREGNANCY','DISABILITY','UNION_MEMBER','PARTY_MEMBER','YOUTH_UNION_MEMBER')) STORED,
  valid_from           date NOT NULL,
  valid_to             date,                                -- EXCLUSIVE; e.g. CHILD_UNDER_12M: child birth date + 12 months
  valid_during         daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  classification_code  text,                                -- e.g. hazardous-job category (statutory list), disability level band
  detail_enc           bytea,                               -- pii:P3 encrypted JSON details (subject DEK of the person)
  evidence_document_id uuid,                                -- -> core.document (soft)
  override_reason      text,                                -- HAZARDOUS_JOB override of the Core job-title flag
  status               text NOT NULL DEFAULT 'ACTIVE',
  source               text NOT NULL DEFAULT 'APP',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT special_status_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT special_status_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT special_status_employment_fk FOREIGN KEY (tenant_id, person_id, employment_id)
    REFERENCES hr.employment (tenant_id, person_id, id),
  CONSTRAINT special_status_type_ck CHECK (status_type IN ('PREGNANCY','CHILD_UNDER_12M','DISABILITY','MINOR','ELDERLY_WORKER',
                                                          'HAZARDOUS_JOB','UNION_MEMBER','PARTY_MEMBER','YOUTH_UNION_MEMBER')),
  CONSTRAINT special_status_scope_ck CHECK ((status_type = 'HAZARDOUS_JOB') = (employment_id IS NOT NULL)),
  CONSTRAINT special_status_status_ck CHECK (status IN ('ACTIVE','CANCELLED')),
  CONSTRAINT special_status_source_ck CHECK (source IN ('APP','OPENING_LOAD','IMPORT')),
  CONSTRAINT special_status_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT special_status_period_ex EXCLUDE USING gist
    (tenant_id WITH =, scope_id WITH =, status_type WITH =, valid_during WITH &&) WHERE (status = 'ACTIVE')
);
CREATE INDEX special_status_person_ix ON hr.special_status (tenant_id, person_id, status_type, valid_from);
COMMENT ON COLUMN hr.special_status.detail_enc IS 'pii:P3 sensitive details (health, union / party membership), subject DEK of the person';
COMMENT ON COLUMN hr.special_status.status_type IS 'pii:P3-derived for sensitive types (is_sensitive); masked by hr.v_special_status_masked';

-- =============================================================================
-- 10. WORK PERMIT  (foreign workers, BR-PPL-020; People owns it, GAP-042)
--     Per employment, because the permit names the employer / legal entity and the job.
--     Validity is Pattern A (valid_to EXCLUSIVE = day after expiry). The assignment
--     end-date check against permit validity and the 45/60-day alerts are service rules.
--     Decree 219/2025/ND-CP [verify-legal].
-- =============================================================================
CREATE TABLE hr.work_permit (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id            uuid NOT NULL,
  employment_id        uuid NOT NULL,
  permit_type          text NOT NULL,                       -- WORK_PERMIT | EXEMPTION_CERT | NOT_REQUIRED_CONFIRMATION
  permit_no            text,                                -- pii:P2 (printed on statutory foreign-worker reports)
  job_title_on_permit  text,
  work_location_text   text,
  issuing_authority    text,
  issue_date           date,
  valid_from           date NOT NULL,
  valid_to             date,                                -- EXCLUSIVE; mandatory for WORK_PERMIT
  valid_during         daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  renewal_of_id        uuid,                                -- previous permit (extension / reissue)
  document_id          uuid,                                -- -> core.document (soft): scan
  status               text NOT NULL DEFAULT 'DRAFT',
  source               text NOT NULL DEFAULT 'APP',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT work_permit_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT work_permit_employment_fk FOREIGN KEY (tenant_id, person_id, employment_id)
    REFERENCES hr.employment (tenant_id, person_id, id),
  CONSTRAINT work_permit_renewal_fk FOREIGN KEY (tenant_id, renewal_of_id) REFERENCES hr.work_permit (tenant_id, id),
  CONSTRAINT work_permit_type_ck CHECK (permit_type IN ('WORK_PERMIT','EXEMPTION_CERT','NOT_REQUIRED_CONFIRMATION')),
  CONSTRAINT work_permit_status_ck CHECK (status IN ('DRAFT','APPLIED','ACTIVE','EXPIRED','REVOKED','CANCELLED')),
  CONSTRAINT work_permit_source_ck CHECK (source IN ('APP','OPENING_LOAD','REC_HANDOFF','IMPORT')),
  CONSTRAINT work_permit_validity_ck CHECK (permit_type <> 'WORK_PERMIT' OR valid_to IS NOT NULL),
  CONSTRAINT work_permit_no_ck CHECK (status NOT IN ('ACTIVE','EXPIRED','REVOKED') OR permit_type = 'NOT_REQUIRED_CONFIRMATION'
                                      OR permit_no IS NOT NULL),
  CONSTRAINT work_permit_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT work_permit_period_ex EXCLUDE USING gist
    (tenant_id WITH =, employment_id WITH =, valid_during WITH &&) WHERE (status = 'ACTIVE')
);
CREATE INDEX work_permit_employment_ix ON hr.work_permit (tenant_id, employment_id, valid_from);
CREATE INDEX work_permit_expiry_ix ON hr.work_permit (tenant_id, valid_to) WHERE status = 'ACTIVE';
COMMENT ON COLUMN hr.work_permit.permit_no IS 'pii:P2';

-- =============================================================================
-- 11. SEPARATION  (BR-PPL-070..075; DESIGN §7.3)
--     One live separation per employment. People records the legal ground, notice and
--     dates and the INPUTS for severance (service periods); Payroll computes the
--     severance / job-loss allowance and the settlement deadline and People only
--     displays them (payroll_result_ref, settlement_due_date; BR-PPL-074, R2-02 §5 #29).
--     Protected employees (BR-PPL-073): employer-initiated grounds need a documented
--     legal exception (separation_protected_ck); is_protected is set by the service from
--     hr.special_status / T&A leave at submission time.
-- =============================================================================
CREATE TABLE hr.separation (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id           uuid NOT NULL,
  separation_type         text NOT NULL,
  initiated_by            text NOT NULL,                    -- EMPLOYEE | EMPLOYER | MUTUAL | AUTOMATIC (expiry, death ...)
  reason_code             text,                             -- tenant code list SEPARATION_REASON
  legal_ground_code       text,                             -- statutory code list (Labour Code article; used for SI/UI and reports)
  request_date            date,                             -- resignation letter / ESS request (BR-PPL-072)
  notice_date             date,
  required_notice_days    smallint,                         -- computed from contract type + ground (BR-PPL-071)
  notice_day_basis        text,                             -- CALENDAR | WORKING
  notice_shortfall_days   smallint,                         -- informative; compensation consequence shown in UI
  last_working_date       date,
  termination_date        date,                             -- = hr.employment.end_date once completed (INCLUSIVE)
  access_revocation_date  date,
  severance_type          text NOT NULL DEFAULT 'NONE',     -- NONE | SEVERANCE_ALLOWANCE (trợ cấp thôi việc) | JOB_LOSS_ALLOWANCE (trợ cấp mất việc làm)
  severance_eligibility   text,                             -- ELIGIBLE | NOT_ELIGIBLE | PENDING (People's determination)
  is_protected            boolean NOT NULL DEFAULT false,
  protected_override_reason text,                           -- documented legal exception (e.g. entity closure)
  protected_override_document_id uuid,                      -- -> core.document (soft)
  is_rehire_eligible      boolean,
  payroll_result_ref      uuid,                             -- -> pay final-settlement / severance result (soft), display only
  settlement_due_date     date,                             -- Payroll's settlement clock (14/30 working days), display only
  decision_id             uuid,
  workflow_instance_id    uuid,                             -- -> core.workflow_instance (soft)
  status                  text NOT NULL DEFAULT 'DRAFT',
  withdrawn_at            timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT separation_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT separation_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT separation_decision_fk FOREIGN KEY (tenant_id, decision_id) REFERENCES hr.decision (tenant_id, id),
  CONSTRAINT separation_type_ck CHECK (separation_type IN ('RESIGNATION','CONTRACT_EXPIRY','MUTUAL_AGREEMENT',
                                       'EMPLOYEE_UNILATERAL','EMPLOYER_UNILATERAL','REDUNDANCY','DISMISSAL',
                                       'PROBATION_FAILED','RETIREMENT','DEATH','MISSING_DECLARED','ENTITY_CLOSURE','OTHER')),
  CONSTRAINT separation_initiated_ck CHECK (initiated_by IN ('EMPLOYEE','EMPLOYER','MUTUAL','AUTOMATIC')),
  CONSTRAINT separation_notice_basis_ck CHECK (notice_day_basis IS NULL OR notice_day_basis IN ('CALENDAR','WORKING')),
  CONSTRAINT separation_severance_ck CHECK (severance_type IN ('NONE','SEVERANCE_ALLOWANCE','JOB_LOSS_ALLOWANCE')),
  CONSTRAINT separation_eligibility_ck CHECK (severance_eligibility IS NULL OR severance_eligibility IN ('ELIGIBLE','NOT_ELIGIBLE','PENDING')),
  CONSTRAINT separation_status_ck CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','SCHEDULED','COMPLETED',
                                                    'WITHDRAWN','REJECTED','CANCELLED')),
  CONSTRAINT separation_dates_ck CHECK (
        (last_working_date IS NULL OR termination_date IS NULL OR termination_date >= last_working_date)
    AND (notice_date IS NULL OR last_working_date IS NULL OR last_working_date >= notice_date)),
  CONSTRAINT separation_completed_ck CHECK (status NOT IN ('SCHEDULED','COMPLETED') OR termination_date IS NOT NULL),
  CONSTRAINT separation_notice_ck CHECK (required_notice_days IS NULL OR required_notice_days >= 0),
  -- BR-PPL-073: employer-initiated termination of a protected employee needs a documented exception
  CONSTRAINT separation_protected_ck CHECK (NOT is_protected
    OR separation_type NOT IN ('EMPLOYER_UNILATERAL','DISMISSAL','REDUNDANCY')
    OR protected_override_reason IS NOT NULL)
);
-- at most one live separation per employment
CREATE UNIQUE INDEX separation_live_uq ON hr.separation (tenant_id, employment_id)
  WHERE status NOT IN ('WITHDRAWN','REJECTED','CANCELLED');
CREATE INDEX separation_termination_ix ON hr.separation (tenant_id, termination_date) WHERE status IN ('APPROVED','SCHEDULED');

-- Service periods supplied to Payroll for the severance computation (BR-PPL-074, OUT-13):
-- periods worked for this employer (incl. recognised prior service) and periods for which
-- severance / job-loss allowance was already paid. Payroll subtracts UI-contribution months.
CREATE TABLE hr.separation_service_period (
  tenant_id           uuid NOT NULL,
  id                  uuid NOT NULL DEFAULT sys.uuid_v7(),
  separation_id       uuid NOT NULL,
  period_kind         text NOT NULL,
  period_from         date NOT NULL,
  period_to           date NOT NULL,                        -- INCLUSIVE
  source_employment_id uuid,                                -- earlier employment of the same person, if any
  note                text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid,
  version             int NOT NULL DEFAULT 0,
  CONSTRAINT separation_service_period_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT separation_service_period_sep_fk FOREIGN KEY (tenant_id, separation_id) REFERENCES hr.separation (tenant_id, id),
  CONSTRAINT separation_service_period_emp_fk FOREIGN KEY (tenant_id, source_employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT separation_service_period_kind_ck CHECK (period_kind IN ('WORKED','RECOGNISED_PRIOR','PAID_SEVERANCE','PAID_JOB_LOSS','EXCLUDED')),
  CONSTRAINT separation_service_period_dates_ck CHECK (period_to >= period_from)
);
CREATE INDEX separation_service_period_sep_ix ON hr.separation_service_period (tenant_id, separation_id);

-- =============================================================================
-- 12. HR DOCUMENT  (metadata only; the file lives in Core's document service, R2-02 §5 #15b)
-- =============================================================================
CREATE TABLE hr.hr_document (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id            uuid NOT NULL,
  employment_id        uuid,                                -- NULL = person-level document
  category_code        text NOT NULL,                       -- tenant code list HR_DOCUMENT_CATEGORY (drives retention)
  document_id          uuid NOT NULL,                       -- -> core.document (soft)
  title                text,
  confidentiality      text NOT NULL DEFAULT 'CONFIDENTIAL',
  retention_class_code text,                                -- saas retention category (BR-PPL-154)
  retention_until      date,
  source_entity        text,                                -- e.g. 'hr.contract', 'hr.decision', 'hr.separation'
  source_id            uuid,                                -- id in source_entity (same schema, not FK: polymorphic)
  version_no           int NOT NULL DEFAULT 1,
  superseded_by_id     uuid,
  status               text NOT NULL DEFAULT 'ACTIVE',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT hr_document_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT hr_document_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT hr_document_employment_fk FOREIGN KEY (tenant_id, person_id, employment_id)
    REFERENCES hr.employment (tenant_id, person_id, id),
  CONSTRAINT hr_document_superseded_fk FOREIGN KEY (tenant_id, superseded_by_id) REFERENCES hr.hr_document (tenant_id, id),
  CONSTRAINT hr_document_confidentiality_ck CHECK (confidentiality IN ('INTERNAL','CONFIDENTIAL','RESTRICTED')),
  CONSTRAINT hr_document_status_ck CHECK (status IN ('ACTIVE','SUPERSEDED','WITHDRAWN','PURGED')),
  CONSTRAINT hr_document_source_entity_ck CHECK (source_entity IS NULL OR source_entity ~ '^hr\.[a-z_]+$')
);
CREATE INDEX hr_document_person_ix ON hr.hr_document (tenant_id, person_id, category_code);
CREATE INDEX hr_document_source_ix ON hr.hr_document (tenant_id, source_entity, source_id) WHERE source_id IS NOT NULL;
CREATE INDEX hr_document_retention_ix ON hr.hr_document (tenant_id, retention_until) WHERE status = 'ACTIVE';

-- =============================================================================
-- 13. STATUTORY REPORT LOG  (BR-PPL-140..142: labour book export, 6-monthly labour
--     change report, monthly labour fluctuation notice; generation + submission log)
-- =============================================================================
CREATE TABLE hr.statutory_report (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id        uuid NOT NULL,                     -- -> core.legal_entity (soft)
  report_type            text NOT NULL,
  period_start           date NOT NULL,
  period_end             date NOT NULL,                     -- INCLUSIVE
  due_date               date,
  template_version       text,
  generated_document_id  uuid,                              -- -> core.document (soft)
  generated_at           timestamptz,
  generated_by           uuid,
  row_count              int,
  submitted_at           timestamptz,
  submitted_by           uuid,
  submission_channel     text,                              -- PORTAL | PAPER | EMAIL | OTHER
  submission_ref         text,
  status                 text NOT NULL DEFAULT 'DRAFT',
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT statutory_report_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT statutory_report_type_ck CHECK (report_type IN ('LABOUR_BOOK_EXPORT','LABOUR_CHANGE_6M','LABOUR_FLUCTUATION_MONTHLY',
                                                              'FOREIGN_WORKER','INITIAL_DECLARATION')),
  CONSTRAINT statutory_report_status_ck CHECK (status IN ('DRAFT','GENERATED','SUBMITTED','ACKNOWLEDGED','CANCELLED')),
  CONSTRAINT statutory_report_channel_ck CHECK (submission_channel IS NULL OR submission_channel IN ('PORTAL','PAPER','EMAIL','OTHER')),
  CONSTRAINT statutory_report_period_ck CHECK (period_end >= period_start),
  CONSTRAINT statutory_report_submitted_ck CHECK (status NOT IN ('SUBMITTED','ACKNOWLEDGED') OR submitted_at IS NOT NULL)
);
CREATE UNIQUE INDEX statutory_report_live_uq ON hr.statutory_report (tenant_id, legal_entity_id, report_type, period_start)
  WHERE status <> 'CANCELLED';

-- =============================================================================
-- 14. PHASE-2 TABLES (minimal; shape only, refined when the P2 BRs are detailed)
-- =============================================================================

-- P2: certificates & licences (BR-PPL-019 full scope; P1 keeps hr.education_qualification only)
CREATE TABLE hr.certificate (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id            uuid NOT NULL,
  certificate_type_code text NOT NULL,                      -- code list (incl. OSH training groups, BR-PPL-102)
  name                 text NOT NULL,
  issuer               text,
  certificate_no       text,
  issue_date           date,
  expiry_date          date,
  is_safety_training   boolean NOT NULL DEFAULT false,
  document_id          uuid,                                -- -> core.document (soft)
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT certificate_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT certificate_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT certificate_dates_ck CHECK (expiry_date IS NULL OR issue_date IS NULL OR expiry_date >= issue_date)
);
CREATE INDEX certificate_expiry_ix ON hr.certificate (tenant_id, expiry_date) WHERE expiry_date IS NOT NULL;

-- P2: discipline (BR-PPL-080..084; R2-05: handled offline in the pilot)
CREATE TABLE hr.discipline_case (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id        uuid NOT NULL,
  violation_date       date NOT NULL,
  rule_reference       text,                                -- internal labour rules clause
  description          text,                                -- pii:P2
  hearing_date         date,
  sanction_code        text,                                -- REPRIMAND | EXTEND_RAISE | DEMOTION | DISMISSAL
  sanction_from        date,
  sanction_to          date,
  expunge_date         date,
  damages_amount       sys.money_amount,                    -- pii:P2 material liability (BR-PPL-083)
  decision_id          uuid,
  status               text NOT NULL DEFAULT 'OPEN',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT discipline_case_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT discipline_case_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT discipline_case_decision_fk FOREIGN KEY (tenant_id, decision_id) REFERENCES hr.decision (tenant_id, id),
  CONSTRAINT discipline_case_sanction_ck CHECK (sanction_code IS NULL OR sanction_code IN ('REPRIMAND','EXTEND_RAISE','DEMOTION','DISMISSAL')),
  CONSTRAINT discipline_case_status_ck CHECK (status IN ('OPEN','HEARING','DECIDED','EXPUNGED','CLOSED','CANCELLED'))
);
CREATE INDEX discipline_case_employment_ix ON hr.discipline_case (tenant_id, employment_id);
COMMENT ON TABLE hr.discipline_case IS 'P2 (R2-05). pii:P2 discipline records.';

-- P2: rewards (BR-PPL-085; P1 enters rewards as payroll variable inputs)
CREATE TABLE hr.reward (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id        uuid NOT NULL,
  reward_type          text NOT NULL,                       -- CASH | CERTIFICATE | TITLE | IN_KIND
  reward_level         text,                                -- COMPANY | STATE | OTHER
  amount               sys.money_amount,                    -- pii:P2
  currency_code        sys.currency_code NOT NULL DEFAULT 'VND',
  is_taxable           boolean NOT NULL DEFAULT true,
  target_pay_month     sys.month_date,
  decision_id          uuid,
  status               text NOT NULL DEFAULT 'DRAFT',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT reward_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT reward_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT reward_decision_fk FOREIGN KEY (tenant_id, decision_id) REFERENCES hr.decision (tenant_id, id),
  CONSTRAINT reward_type_ck CHECK (reward_type IN ('CASH','CERTIFICATE','TITLE','IN_KIND')),
  CONSTRAINT reward_level_ck CHECK (reward_level IS NULL OR reward_level IN ('COMPANY','STATE','OTHER')),
  CONSTRAINT reward_amount_ck CHECK (reward_type <> 'CASH' OR amount IS NOT NULL),
  CONSTRAINT reward_status_ck CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','PAID','CANCELLED'))
);
CREATE INDEX reward_employment_ix ON hr.reward (tenant_id, employment_id);
COMMENT ON COLUMN hr.reward.amount IS 'pii:P2 not encrypted (X-01); masking view to be added with the P2 feature';

-- P2: appraisal (BR-PPL-090..094; one row per employee and cycle; goals/KPI library later)
CREATE TABLE hr.appraisal (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id        uuid NOT NULL,
  appraisal_type       text NOT NULL,                       -- PROBATION | ANNUAL | SEMI_ANNUAL | QUARTERLY | MONTHLY_KPI
  cycle_code           text NOT NULL,
  period_start         date NOT NULL,
  period_end           date NOT NULL,
  score                numeric(7,3),
  rating_code          text,
  reviewer_employment_id uuid,
  finalised_at         timestamptz,
  status               text NOT NULL DEFAULT 'DRAFT',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT appraisal_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT appraisal_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT appraisal_reviewer_fk FOREIGN KEY (tenant_id, reviewer_employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT appraisal_type_ck CHECK (appraisal_type IN ('PROBATION','ANNUAL','SEMI_ANNUAL','QUARTERLY','MONTHLY_KPI')),
  CONSTRAINT appraisal_status_ck CHECK (status IN ('DRAFT','SELF_REVIEW','MANAGER_REVIEW','CALIBRATION','FINALISED','CANCELLED')),
  CONSTRAINT appraisal_period_ck CHECK (period_end >= period_start),
  CONSTRAINT appraisal_uq UNIQUE (tenant_id, employment_id, cycle_code)
);
COMMENT ON TABLE hr.appraisal IS 'P2 (appraisal deferred; BR-PPL-094 probation result is captured via BR-PPL-052 in P1).';

-- P2: training record (BR-PPL-100..102; course catalogue / sessions later)
CREATE TABLE hr.training_record (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  person_id            uuid NOT NULL,
  employment_id        uuid,
  course_code          text,
  course_name          text NOT NULL,
  provider             text,
  start_date           date NOT NULL,
  end_date             date,
  hours_qty            sys.quantity,
  result_code          text,
  certificate_id       uuid,
  cost_amount          sys.money_amount,
  bond_end_date        date,                                -- training commitment (BR-PPL-101)
  bond_amount          sys.money_amount,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT training_record_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT training_record_person_fk FOREIGN KEY (tenant_id, person_id) REFERENCES hr.person (tenant_id, id),
  CONSTRAINT training_record_employment_fk FOREIGN KEY (tenant_id, person_id, employment_id)
    REFERENCES hr.employment (tenant_id, person_id, id),
  CONSTRAINT training_record_certificate_fk FOREIGN KEY (tenant_id, certificate_id) REFERENCES hr.certificate (tenant_id, id),
  CONSTRAINT training_record_dates_ck CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE INDEX training_record_person_ix ON hr.training_record (tenant_id, person_id);
COMMENT ON TABLE hr.training_record IS 'P2 (training deferred).';

-- P2: on/offboarding checklists (BR-PPL-060..063, 076; manual in the pilot)
CREATE TABLE hr.checklist_template (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id      uuid,                                -- -> core.legal_entity (soft); NULL = all
  event_type           text NOT NULL,                       -- ONBOARDING | OFFBOARDING | TRANSFER
  name                 text NOT NULL,
  task_definitions     jsonb NOT NULL DEFAULT '[]'::jsonb,  -- [{"title":..,"owner_role":..,"due_offset_days":..,"mandatory":true}]
  is_active            boolean NOT NULL DEFAULT true,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT checklist_template_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT checklist_template_event_ck CHECK (event_type IN ('ONBOARDING','OFFBOARDING','TRANSFER')),
  CONSTRAINT checklist_template_tasks_ck CHECK (jsonb_typeof(task_definitions) = 'array')
);

CREATE TABLE hr.checklist (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id        uuid NOT NULL,
  template_id          uuid,
  event_type           text NOT NULL,
  separation_id        uuid,
  status               text NOT NULL DEFAULT 'OPEN',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT checklist_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT checklist_employment_fk FOREIGN KEY (tenant_id, employment_id) REFERENCES hr.employment (tenant_id, id),
  CONSTRAINT checklist_template_fk FOREIGN KEY (tenant_id, template_id) REFERENCES hr.checklist_template (tenant_id, id),
  CONSTRAINT checklist_separation_fk FOREIGN KEY (tenant_id, separation_id) REFERENCES hr.separation (tenant_id, id),
  CONSTRAINT checklist_event_ck CHECK (event_type IN ('ONBOARDING','OFFBOARDING','TRANSFER')),
  CONSTRAINT checklist_status_ck CHECK (status IN ('OPEN','COMPLETED','CANCELLED'))
);

CREATE TABLE hr.checklist_task (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  checklist_id         uuid NOT NULL,
  seq                  smallint NOT NULL,
  title                text NOT NULL,
  owner_role_code      text,
  owner_user_id        uuid,                                -- -> core.user_account (soft)
  due_date             date,
  is_mandatory         boolean NOT NULL DEFAULT true,
  requires_attachment  boolean NOT NULL DEFAULT false,
  document_id          uuid,                                -- -> core.document (soft)
  status               text NOT NULL DEFAULT 'OPEN',
  completed_at         timestamptz,
  completed_by         uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT checklist_task_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT checklist_task_checklist_fk FOREIGN KEY (tenant_id, checklist_id) REFERENCES hr.checklist (tenant_id, id),
  CONSTRAINT checklist_task_uq UNIQUE (tenant_id, checklist_id, seq),
  CONSTRAINT checklist_task_status_ck CHECK (status IN ('OPEN','DONE','WAIVED','CANCELLED'))
);

-- =============================================================================
-- 15. MASKING VIEWS  (DESIGN §9.4).  app.mask_level: NONE | PII | PII_AND_PAY
--     security_invoker keeps RLS of the caller; ciphertext is never exposed.
-- =============================================================================
CREATE OR REPLACE VIEW hr.v_person_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT p.tenant_id, p.id, p.family_name, p.middle_name, p.given_name, p.full_name, p.preferred_name,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII') = 'NONE'
            THEN p.birth_date
            ELSE make_date(extract(year FROM p.birth_date)::int, 1, 1) END       AS birth_date,
       p.gender_code, p.nationality_code, p.national_id_type,
       '••••' || coalesce(p.national_id_last4, '')                            AS national_id_masked,
       '••••' || coalesce(p.tax_code_last4, '')                               AS tax_code_masked,
       '••••' || coalesce(p.si_number_last4, '')                              AS si_number_masked,
       '••••' || coalesce(p.hi_card_no_last4, '')                             AS hi_card_no_masked,
       p.verification_status, p.is_anonymised
  FROM hr.person p;

CREATE OR REPLACE VIEW hr.v_bank_account_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT b.tenant_id, b.id, b.person_id, b.bank_code, b.branch_code,
       '••••' || b.account_no_last4 AS account_no_masked,
       b.holder_name, b.currency_code, b.split_type, b.split_value, b.priority,
       b.valid_from, b.valid_to, b.status
  FROM hr.bank_account b;

CREATE OR REPLACE VIEW hr.v_person_identity_doc_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT d.tenant_id, d.id, d.person_id, d.doc_type, '••••' || d.doc_no_last4 AS doc_no_masked,
       d.issuing_country_code, d.issue_date, d.expiry_date, d.visa_type_code, d.status
  FROM hr.person_identity_doc d;

-- Sensitive special-status types are shown only at app.mask_level = NONE (fail closed:
-- unset or '' masks them); details are never exposed (detail_enc stays in the table).
CREATE OR REPLACE VIEW hr.v_special_status_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT s.tenant_id, s.id, s.person_id, s.employment_id,
       CASE WHEN NOT s.is_sensitive OR NULLIF(current_setting('app.mask_level', true), '') = 'NONE'
            THEN s.status_type ELSE 'RESTRICTED' END                          AS status_type,
       s.is_sensitive, s.valid_from, s.valid_to,
       CASE WHEN NOT s.is_sensitive OR NULLIF(current_setting('app.mask_level', true), '') = 'NONE'
            THEN s.classification_code END                                    AS classification_code,
       s.status
  FROM hr.special_status s;

-- Salary amounts are NOT column-encrypted (X-01); this view is one of the compensating
-- controls. Fail closed for pay: an unset (or '') app.mask_level hides amounts.
CREATE OR REPLACE VIEW hr.v_salary_record_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT s.tenant_id, s.id, s.employment_id, s.pay_basis, s.salary_kind,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN s.base_salary_amount END                                     AS base_salary_amount,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN s.agreed_net_amount END                                      AS agreed_net_amount,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN s.si_salary_amount END                                       AS si_salary_amount,
       s.net_guaranteed_scope,
       s.currency_code, s.pay_scale_code, s.pay_scale_step_code, s.action_code, s.reason_code,
       s.valid_from, s.valid_to, s.recorded_at, s.superseded_at, s.source
  FROM hr.salary_record s;

CREATE OR REPLACE VIEW hr.v_salary_record_allowance_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT a.tenant_id, a.salary_record_id, a.component_code,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN a.amount END                                                 AS amount,
       a.is_in_si_salary
  FROM hr.salary_record_allowance a;

-- =============================================================================
-- 16. LABOUR-REPORT VIEWS  (BR-PPL-140..142; read by the People report service, which
--     decrypts the national id and adds salary / leave / OT columns from their owners'
--     APIs or from hr.v_salary_record_masked. Fields per Decree 145/2020 Art. 3
--     [verify-legal]). Snapshot at current_date (UTC date of the DB session); as-of
--     exports filter the base tables with an explicit date in the report service.
-- =============================================================================
CREATE OR REPLACE VIEW hr.v_labour_book WITH (security_invoker = true, security_barrier = true) AS
SELECT e.tenant_id, e.legal_entity_id, e.id AS employment_id, e.employee_no, e.person_id,
       p.full_name, p.gender_code, p.birth_date, p.nationality_code, p.national_id_type,
       p.national_id_last4,
       addr.province_code AS residence_province_code, addr.commune_code AS residence_commune_code,
       addr.address_line  AS residence_address_line,
       edu.qualification_level_code AS highest_qualification_code, edu.vocational_skill_level_code,
       asg.position_id, asg.job_title_id, asg.org_unit_id,
       ctr.contract_type_code, ctr.contract_no, ctr.start_date AS contract_start_date, ctr.end_date AS contract_end_date,
       e.hire_date, e.status AS employment_status,
       EXISTS (SELECT 1 FROM hr.insurance_participation ip
                WHERE ip.tenant_id = e.tenant_id AND ip.employment_id = e.id AND ip.insurance_type = 'SI'
                  AND ip.status = 'ACTIVE' AND ip.is_participating AND ip.valid_during @> current_date) AS si_participating,
       sep.termination_date, sep.separation_type, sep.legal_ground_code
  FROM hr.employment e
  JOIN hr.person p ON p.tenant_id = e.tenant_id AND p.id = e.person_id
  LEFT JOIN LATERAL (SELECT a.province_code, a.commune_code, a.address_line
                       FROM hr.person_address a
                      WHERE a.tenant_id = e.tenant_id AND a.person_id = e.person_id
                        AND a.address_type IN ('PERMANENT','CURRENT') AND a.valid_during @> current_date
                      ORDER BY (a.address_type = 'PERMANENT') DESC LIMIT 1) addr ON true
  LEFT JOIN LATERAL (SELECT q.qualification_level_code, q.vocational_skill_level_code
                       FROM hr.education_qualification q
                      WHERE q.tenant_id = e.tenant_id AND q.person_id = e.person_id AND q.is_highest) edu ON true
  LEFT JOIN LATERAL (SELECT a.position_id, a.job_title_id, a.org_unit_id
                       FROM hr.assignment a
                      WHERE a.tenant_id = e.tenant_id AND a.employment_id = e.id AND a.assignment_type = 'PRIMARY'
                        AND a.superseded_at IS NULL AND a.valid_from <= current_date
                      ORDER BY a.valid_from DESC LIMIT 1) asg ON true
  LEFT JOIN LATERAL (SELECT c.contract_type_code, c.contract_no, c.start_date, c.end_date
                       FROM hr.contract c
                      WHERE c.tenant_id = e.tenant_id AND c.employment_id = e.id
                        AND c.status IN ('ACTIVE','SUSPENDED','EXPIRED','TERMINATED')
                      ORDER BY c.start_date DESC LIMIT 1) ctr ON true
  LEFT JOIN LATERAL (SELECT s.termination_date, s.separation_type, s.legal_ground_code
                       FROM hr.separation s
                      WHERE s.tenant_id = e.tenant_id AND s.employment_id = e.id
                        AND s.status NOT IN ('WITHDRAWN','REJECTED','CANCELLED')) sep ON true
 WHERE e.status <> 'CANCELLED';

-- Hires and terminations per legal entity and date: input for the 6-monthly labour change
-- report and the monthly labour fluctuation notice (BR-PPL-141/142).
CREATE OR REPLACE VIEW hr.v_labour_movement WITH (security_invoker = true, security_barrier = true) AS
SELECT e.tenant_id, e.legal_entity_id, e.id AS employment_id, e.person_id,
       'HIRE'::text AS movement_type, e.hire_date AS movement_date, e.hire_type AS reason_code, NULL::text AS legal_ground_code
  FROM hr.employment e
 WHERE e.status NOT IN ('PRE_HIRE','CANCELLED')
UNION ALL
SELECT e.tenant_id, e.legal_entity_id, e.id, e.person_id,
       'TERMINATION', e.end_date, s.separation_type, s.legal_ground_code
  FROM hr.employment e
  LEFT JOIN hr.separation s ON s.tenant_id = e.tenant_id AND s.employment_id = e.id
                           AND s.status NOT IN ('WITHDRAWN','REJECTED','CANCELLED')
 WHERE e.end_date IS NOT NULL AND e.status <> 'CANCELLED';

-- =============================================================================
-- 17. RLS, triggers, grants
-- =============================================================================
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['hr.person','hr.person_identity_doc','hr.person_address','hr.person_contact',
                           'hr.tax_residency','hr.vn_presence_period','hr.education_qualification',
                           'hr.employment','hr.primary_employment','hr.insurance_participation','hr.decision',
                           'hr.assignment','hr.salary_record','hr.salary_record_allowance',
                           'hr.contract','hr.contract_appendix','hr.contract_suspension',
                           'hr.family_member','hr.pit_dependant','hr.emergency_contact','hr.bank_account',
                           'hr.special_status','hr.work_permit','hr.separation','hr.separation_service_period',
                           'hr.hr_document','hr.statutory_report',
                           -- P2
                           'hr.certificate','hr.discipline_case','hr.reward','hr.appraisal','hr.training_record',
                           'hr.checklist_template','hr.checklist','hr.checklist_task']
  LOOP
    CALL sys.enable_tenant_rls(t::regclass);
    CALL audit.attach_row_audit(t::regclass);                -- NFR-PPL-006: every write audited
  END LOOP;

  -- mutable tables
  FOREACH t IN ARRAY ARRAY['hr.person','hr.person_identity_doc','hr.person_address','hr.person_contact',
                           'hr.tax_residency','hr.vn_presence_period','hr.education_qualification',
                           'hr.employment','hr.primary_employment','hr.insurance_participation','hr.decision',
                           'hr.contract','hr.contract_appendix','hr.contract_suspension',
                           'hr.family_member','hr.pit_dependant','hr.emergency_contact','hr.bank_account',
                           'hr.special_status','hr.work_permit','hr.separation','hr.separation_service_period',
                           'hr.hr_document','hr.statutory_report',
                           'hr.certificate','hr.discipline_case','hr.reward','hr.appraisal','hr.training_record',
                           'hr.checklist_template','hr.checklist','hr.checklist_task']
  LOOP
    CALL sys.attach_touch_trigger(t::regclass);
  END LOOP;

  -- bi-temporal tables. D9: the shared guard is attached for UPDATE only (same
  -- trigger name as sys.attach_bitemporal_guard); DELETE goes through
  -- hr.tg_bitemporal_no_delete(), which admits the tenant purge and nothing else.
  FOREACH t IN ARRAY ARRAY['hr.assignment','hr.salary_record']
  LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS trg_bitemporal_guard ON %s', t);
    EXECUTE format('CREATE TRIGGER trg_bitemporal_guard BEFORE UPDATE ON %s '
                   'FOR EACH ROW EXECUTE FUNCTION sys.tg_bitemporal_guard()', t);
    EXECUTE format('DROP TRIGGER IF EXISTS trg_bitemporal_no_delete ON %s', t);
    EXECUTE format('CREATE TRIGGER trg_bitemporal_no_delete BEFORE DELETE ON %s '
                   'FOR EACH ROW EXECUTE FUNCTION hr.tg_bitemporal_no_delete()', t);
  END LOOP;
END $$;

-- People module runtime only (DESIGN §2.3): no other module role gets a grant in hr.
GRANT SELECT, INSERT, UPDATE ON
  hr.person, hr.person_identity_doc, hr.person_address, hr.person_contact, hr.tax_residency,
  hr.vn_presence_period, hr.education_qualification,
  hr.employment, hr.primary_employment, hr.insurance_participation, hr.decision, hr.assignment, hr.salary_record,
  hr.contract, hr.contract_appendix, hr.contract_suspension,
  hr.family_member, hr.pit_dependant, hr.emergency_contact, hr.bank_account,
  hr.special_status, hr.work_permit, hr.separation, hr.separation_service_period, hr.hr_document, hr.statutory_report,
  hr.certificate, hr.discipline_case, hr.reward, hr.appraisal, hr.training_record,
  hr.checklist_template, hr.checklist, hr.checklist_task
TO yhrm_app_hr;
GRANT SELECT, INSERT ON hr.salary_record_allowance TO yhrm_app_hr;
-- DELETE only where DRAFT rows may be discarded or rows are freely editable contact data
-- (enforced further by the service layer).
GRANT DELETE ON hr.decision, hr.contract, hr.contract_appendix, hr.contract_suspension, hr.pit_dependant,
  hr.person_contact, hr.emergency_contact, hr.separation_service_period, hr.checklist_task
TO yhrm_app_hr;
-- D6 (2026-10-21): masking views are granted to the People runtime ONLY. The former
-- grant to yhrm_reporting_ro is removed: with security_invoker the role could not read
-- the base tables anyway, and reporting goes through the reporting store (DESIGN §11),
-- not OLTP views.
GRANT SELECT ON hr.v_person_masked, hr.v_bank_account_masked, hr.v_salary_record_masked,
  hr.v_person_identity_doc_masked, hr.v_special_status_masked, hr.v_salary_record_allowance_masked,
  hr.v_labour_book, hr.v_labour_movement
  TO yhrm_app_hr;

-- Outbox / inbox for People (people.EmployeeHired, people.SalaryChanged,
-- people.PrimaryEmploymentChanged, people.InsuranceParticipationChanged (proposed),
-- people.PersonalDataChanged incl. tax residency, people.TerminationScheduled, ...).
CALL sys.create_outbox_inbox('hr', 'yhrm_app_hr');

RESET ROLE;
