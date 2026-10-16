-- =============================================================================
-- YourHRM  ·  003_hr.sql   (owner module: People)
-- Person, employment, assignment (bi-temporal), salary record (bi-temporal),
-- contract (+ appendix), decision, family member + PIT dependant, bank account,
-- masking view, outbox/inbox.
-- DESIGN.md §4, §5 (patterns A and B), §7.3, §9.3/9.4; ADR-0023/0024/0026.
--
-- Cross-module references are SOFT (no FK leaves the schema):
--   legal_entity_id, org_unit_id, position_id, job_title_id, grade_id,
--   location_id, cost_centre_id -> core.*   |   component/pay-scale codes -> pay.*
--   Global master data is referenced by CODE (country_code, bank_code, ...).
--
-- PII classes (COMMENT 'pii:Px'): P3 columns are application-encrypted with the
-- data subject's DEK (saas.subject_key); *_bidx = HMAC blind index for equality;
-- *_last4 = display hint for masked UIs. Salary amounts are P2 and NOT encrypted
-- (X-01): RLS + module grants + hr.v_salary_record_masked + audit instead.
--
-- v0.2 (2026-10-16): source value MIGRATION -> OPENING_LOAD (D-09, ADR-0037), salary
-- masking view, X-01 comments. NOT re-tested after these changes (no PostgreSQL
-- available locally); reviewed by read-through only.
-- =============================================================================

\set ON_ERROR_STOP on
SET ROLE yhrm_owner;
SET search_path = hr, sys, public;

GRANT USAGE ON SCHEMA hr TO yhrm_app_hr, yhrm_reporting_ro;

-- =============================================================================
-- 1. PERSON  (one row per real individual per tenant; no cross-tenant matching)
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
  CONSTRAINT person_last4_ck CHECK (national_id_last4 IS NULL OR length(national_id_last4) <= 4)
);
CREATE UNIQUE INDEX person_national_id_uq ON hr.person (tenant_id, national_id_bidx) WHERE national_id_bidx IS NOT NULL;
CREATE INDEX person_tax_code_ix  ON hr.person (tenant_id, tax_code_bidx)  WHERE tax_code_bidx IS NOT NULL;
CREATE INDEX person_si_number_ix ON hr.person (tenant_id, si_number_bidx) WHERE si_number_bidx IS NOT NULL;
CREATE INDEX person_name_trgm_ix ON hr.person USING gin (full_name_search gin_trgm_ops);  -- NFR-PPL-009
COMMENT ON COLUMN hr.person.national_id_enc IS 'pii:P3 AES-256-GCM, subject DEK';
COMMENT ON COLUMN hr.person.tax_code_enc    IS 'pii:P3 AES-256-GCM, subject DEK';
COMMENT ON COLUMN hr.person.si_number_enc   IS 'pii:P3 AES-256-GCM, subject DEK';
COMMENT ON COLUMN hr.person.ethnicity_enc   IS 'pii:P3 sensitive (ethnic origin)';
COMMENT ON COLUMN hr.person.religion_enc    IS 'pii:P3 sensitive (religion)';
COMMENT ON COLUMN hr.person.birth_date      IS 'pii:P2';
COMMENT ON COLUMN hr.person.full_name       IS 'pii:P1';

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
  is_primary              boolean NOT NULL DEFAULT true,    -- primary employment for PIT/SI purposes
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
  -- at most one PRIMARY employment per person at any time (within the tenant)
  CONSTRAINT employment_primary_ex EXCLUDE USING gist
    (tenant_id WITH =, person_id WITH =, employment_period WITH &&)
    WHERE (is_primary AND status <> 'CANCELLED')
);
CREATE INDEX employment_person_ix ON hr.employment (tenant_id, person_id);
CREATE INDEX employment_le_status_ix ON hr.employment (tenant_id, legal_entity_id, status);

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
-- 5. SALARY RECORD  (BI-TEMPORAL; agreed compensation incl. GROSS/NET; gap §5 #24)
-- =============================================================================
CREATE TABLE hr.salary_record (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  employment_id        uuid NOT NULL,
  pay_basis            text NOT NULL DEFAULT 'GROSS',
  salary_kind          text NOT NULL DEFAULT 'MONTHLY',      -- MONTHLY | DAILY | HOURLY | PIECE
  base_salary_amount   sys.money_amount NOT NULL,           -- pii:P2 (contract salary)
  agreed_net_amount    sys.money_amount,                    -- pii:P2 required when pay_basis = NET
  si_salary_amount     sys.money_amount,                    -- pii:P2 salary for compulsory insurance
  currency_code        sys.currency_code NOT NULL DEFAULT 'VND',
  pay_scale_code       text,                                -- -> pay.pay_scale (soft, by code)
  pay_scale_step_code  text,                                -- bậc
  probation_pct        numeric(5,2),                        -- % of salary during probation (legal floor in pack)
  action_code          text NOT NULL,                       -- HIRE | INCREASE | PROMOTION | ADJUSTMENT | CORRECTION
  reason_code          text,
  decision_id          uuid,
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
  CONSTRAINT salary_record_kind_ck CHECK (salary_kind IN ('MONTHLY','DAILY','HOURLY','PIECE')),
  CONSTRAINT salary_record_amounts_ck CHECK (base_salary_amount >= 0 AND coalesce(si_salary_amount, 0) >= 0),
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
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();

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

CREATE TABLE hr.pit_dependant (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  family_member_id      uuid NOT NULL,
  employment_id         uuid NOT NULL,                      -- claiming employment (taxpayer side)
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
-- 9. MASKING VIEW  (DESIGN §9.4).  app.mask_level: NONE | PII | PII_AND_PAY
--    security_invoker keeps RLS of the caller; ciphertext is never exposed.
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
       p.verification_status, p.is_anonymised
  FROM hr.person p;

CREATE OR REPLACE VIEW hr.v_bank_account_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT b.tenant_id, b.id, b.person_id, b.bank_code, b.branch_code,
       '••••' || b.account_no_last4 AS account_no_masked,
       b.holder_name, b.currency_code, b.split_type, b.split_value, b.priority,
       b.valid_from, b.valid_to, b.status
  FROM hr.bank_account b;

-- Salary amounts are NOT column-encrypted (X-01); this view is one of the compensating
-- controls. Fail closed for pay: an unset app.mask_level hides amounts.
CREATE OR REPLACE VIEW hr.v_salary_record_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT s.tenant_id, s.id, s.employment_id, s.pay_basis, s.salary_kind,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN s.base_salary_amount END                                     AS base_salary_amount,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN s.agreed_net_amount END                                      AS agreed_net_amount,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN s.si_salary_amount END                                       AS si_salary_amount,
       s.currency_code, s.pay_scale_code, s.pay_scale_step_code, s.action_code, s.reason_code,
       s.valid_from, s.valid_to, s.recorded_at, s.superseded_at
  FROM hr.salary_record s;

-- =============================================================================
-- 10. RLS, triggers, grants
-- =============================================================================
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['hr.person','hr.employment','hr.decision','hr.assignment','hr.salary_record',
                           'hr.salary_record_allowance','hr.contract','hr.contract_appendix',
                           'hr.family_member','hr.pit_dependant','hr.bank_account']
  LOOP
    CALL sys.enable_tenant_rls(t::regclass);
    CALL audit.attach_row_audit(t::regclass);                -- NFR-PPL-006: every write audited
  END LOOP;

  -- mutable tables
  FOREACH t IN ARRAY ARRAY['hr.person','hr.employment','hr.decision','hr.contract','hr.contract_appendix',
                           'hr.family_member','hr.pit_dependant','hr.bank_account']
  LOOP
    CALL sys.attach_touch_trigger(t::regclass);
  END LOOP;

  -- bi-temporal tables
  CALL sys.attach_bitemporal_guard('hr.assignment');
  CALL sys.attach_bitemporal_guard('hr.salary_record');
END $$;

GRANT SELECT, INSERT, UPDATE ON
  hr.person, hr.employment, hr.decision, hr.assignment, hr.salary_record, hr.contract,
  hr.contract_appendix, hr.family_member, hr.pit_dependant, hr.bank_account
TO yhrm_app_hr;
GRANT SELECT, INSERT ON hr.salary_record_allowance TO yhrm_app_hr;
-- DELETE only where DRAFT rows may be discarded (enforced further by the service layer).
GRANT DELETE ON hr.decision, hr.contract, hr.contract_appendix, hr.pit_dependant TO yhrm_app_hr;
GRANT SELECT ON hr.v_person_masked, hr.v_bank_account_masked, hr.v_salary_record_masked
  TO yhrm_app_hr, yhrm_reporting_ro;

-- Outbox / inbox for People (people.EmployeeHired, people.SalaryChanged, ...).
CALL sys.create_outbox_inbox('hr', 'yhrm_app_hr');

RESET ROLE;
