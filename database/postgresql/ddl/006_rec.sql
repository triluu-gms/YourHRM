-- =============================================================================
-- YourHRM  ·  006_rec.sql   (owner module: Recruitment)
--
-- *** PHASE 2 MODULE — created now so the schema is complete for review. ***
-- Recruitment is a Phase 2 module (BRD 05 A-04). This file exists in v0.4 so that
-- the `rec` schema, its grants, its PII classification and the hand-off contract to
-- People (rec.CandidateHired payload v1) can be reviewed together with 000-003.
-- It is loaded by the local docker initdb like every other NNN_*.sql file; nothing
-- in Phase 1 writes to it.
--
-- Staffing plan (+ lines), requisition, job opening, job posting, pipeline template
-- and stages, candidate (+ files, consent references), referral programme and
-- referral, application, stage history, interview (+ panel), scorecard, assessment,
-- offer (versions), pre-boarding checklist items, hire hand-off, masking views,
-- outbox/inbox.
-- DESIGN.md §2-§5, §6 rule 6, §7.6, §9; BRD 05-recruitment §4, §6, §7.3;
-- ADR-0022/0023/0024/0026/0028.
--
-- Cross-module references are SOFT (no FK leaves the schema):
--   legal_entity_id, org_unit_id, position_id, job_title_id, grade_id,
--   work_location_id, user_account_id, workflow_instance_id, document_id -> core.*
--   person_id, employment_id, assignment_id, contract_id                  -> hr.*
--   consent_record_id -> saas.consent_record ; sim_legal_pack_id -> saas.legal_param_pack
--   component_code -> pay.salary_component (by code)
--   Global master data is referenced by CODE (nationality_code, currency_code ...).
--
-- PII (COMMENT 'pii:Px'): every candidate is a data subject with its OWN subject
-- key (saas.subject_key, subject_type = 'CANDIDATE', subject_id = rec.candidate.id).
-- All P3 columns of a candidate (email, phone, address, identity document, parsed CV,
-- pre-boarding values such as tax code / SI number / bank account) are encrypted with
-- that DEK, so retention expiry = destroy the DEK + anonymise the plaintext columns
-- (NFR-REC-009, DESIGN §7.6). NOTE: *_bidx are HMACs under the TENANT blind-index
-- key, not the subject key, so they survive crypto-shredding: the anonymiser MUST
-- null them (candidate_anonymised_ck enforces it). Contact data (email/phone) is P2
-- for employees (DESIGN §9.3) but is raised to P3 for candidates so that a single
-- key destruction removes every way to contact or identify the person. Candidate
-- e-mail / phone / address as P3: accepted D8 (2026-10-21), DPO to confirm.
-- Offer amounts are P2 plaintext (X-01 applies equally): RLS + module grant +
-- rec.v_offer_masked (fail closed) + "compensation" permission in Core.
--
-- Consent: the registry is saas.consent_record (BR-REC-059, R2-02 §5 #27). Rec keeps
-- rec.candidate_consent_ref = soft reference + cached status; the authoritative
-- check is the saas hasConsent() API at the moment of processing.
--
-- v0.4 (2026-10-17): first version. Review fix L7 applied: offer simulation is version
-- stamped with sim_legal_pack_id + sim_override_version (DESIGN §6 rule 6 — replaces
-- the single `sim_param_version` of DESIGN §6/§7.6) and the expiry is `expires_on date`
-- (naming rule §4.2: *_at is timestamptz; a business expiry date is a date).
-- Smoke tested 2026-10-17 on PGlite 0.5.10 (PostgreSQL 18.6) and PGlite 0.2.17 (PostgreSQL 16.4)
-- after 000-003 from git HEAD: loads, RLS cross-tenant/no-context isolation, offer
-- freeze + sim-stamp checks, plan guards, anonymisation check, hire uniqueness. Not yet
-- run on a real PostgreSQL 17 server.
-- v0.5 (2026-10-21, sponsor decisions on the DDL review, DESIGN §15.1 D1-D11):
--   * D9  tenant purge: new rec.tg_append_only() replaces sys.tg_forbid_change() on
--         rec.stage_history; rec.tg_staffing_plan_guard / tg_staffing_plan_line_guard /
--         tg_offer_guard admit DELETE of frozen rows only when
--         sys.purge_bypass(OLD.tenant_id) is true (member of yhrm_purger +
--         app.purge_tenant_id = tenant, contract in 000). UPDATE rules unchanged.
--   * D7  rec.CandidateHired carries no P3 values; People pulls the P3 groups from the
--         Recruitment hand-off API by hire_id (section 13 comments aligned; the model
--         was already minimised in v0.4, no column change).
--   * D8  candidate e-mail / phone / address stay P3 under the candidate subject DEK:
--         accepted D8 (2026-10-21), DPO to confirm (column comments updated).
--   * D6  no rec masking view is granted to yhrm_reporting_ro (there was none; comment
--         added). Reporting goes through the reporting store (DESIGN §11).
--   * D4  funnel tables (requisition, job_opening, pipeline_stage, application,
--         stage_history) are published by 007_publications.sql; candidate PII is not.
-- =============================================================================

\set ON_ERROR_STOP on
SET ROLE yhrm_owner;
SET search_path = rec, sys, public;

GRANT USAGE ON SCHEMA rec TO yhrm_app_rec;

-- =============================================================================
-- 0. Module trigger functions (module-specific guards live in the module schema)
-- =============================================================================

-- D9 (2026-10-21): every guard below rejects DELETE of frozen rows EXCEPT inside the
-- controlled tenant purge (DESIGN §3.7): sys.purge_bypass(OLD.tenant_id) is true only
-- for a member of the NOLOGIN role yhrm_purger with app.purge_tenant_id = that tenant
-- (contract in 000_extensions_roles.sql). UPDATE rules are unchanged.

-- Append-only rows (rec.stage_history): UPDATE always rejected, DELETE only by purge.
CREATE OR REPLACE FUNCTION rec.tg_append_only()
RETURNS trigger LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' AND sys.purge_bypass(OLD.tenant_id) THEN
    RETURN OLD;                                             -- D9: tenant purge only
  END IF;
  RAISE EXCEPTION '%.% is append-only (% rejected)', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
    USING ERRCODE = '55000';
END $$;

-- An APPROVED / SUPERSEDED staffing plan version is read-only (BR-REC-004): the only
-- legal change is APPROVED -> SUPERSEDED when the next version is approved.
CREATE OR REPLACE FUNCTION rec.tg_staffing_plan_guard()
RETURNS trigger LANGUAGE plpgsql
AS $$
DECLARE
  allowed constant text[] := ARRAY['status','superseded_at','updated_at','updated_by','version'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' AND NOT sys.purge_bypass(OLD.tenant_id) THEN   -- D9
      RAISE EXCEPTION 'rec.staffing_plan %: only DRAFT versions may be deleted', OLD.id USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.status IN ('APPROVED','SUPERSEDED','CANCELLED') THEN
    IF (to_jsonb(NEW) - allowed) IS DISTINCT FROM (to_jsonb(OLD) - allowed) THEN
      RAISE EXCEPTION 'rec.staffing_plan % is % and read-only; create a new plan version', OLD.id, OLD.status
        USING ERRCODE = '55000';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT (OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED') THEN
      RAISE EXCEPTION 'rec.staffing_plan %: transition % -> % not allowed', OLD.id, OLD.status, NEW.status
        USING ERRCODE = '55000';
    END IF;
  END IF;
  RETURN NEW;
END $$;

-- Lines of a plan version can be edited only while the version is DRAFT/RETURNED.
-- After approval only the Core synchronisation and remap columns may change (they are
-- written by the Core position-API sync and by OrgUnit/Position retired events).
CREATE OR REPLACE FUNCTION rec.tg_staffing_plan_line_guard()
RETURNS trigger LANGUAGE plpgsql
AS $$
DECLARE
  v_status text;
  allowed constant text[] := ARRAY['core_sync_status','core_synced_at','core_sync_error',
                                   'remap_status','updated_at','updated_by','version'];
BEGIN
  IF TG_OP = 'DELETE' AND sys.purge_bypass(OLD.tenant_id) THEN
    RETURN OLD;                                             -- D9: tenant purge only
  END IF;
  SELECT p.status INTO v_status
    FROM rec.staffing_plan p
   WHERE p.tenant_id = coalesce(NEW.tenant_id, OLD.tenant_id)
     AND p.id        = coalesce(NEW.staffing_plan_id, OLD.staffing_plan_id);
  IF v_status IN ('DRAFT','RETURNED') THEN
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;
  IF TG_OP = 'UPDATE' AND (to_jsonb(NEW) - allowed) IS NOT DISTINCT FROM (to_jsonb(OLD) - allowed) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'rec.staffing_plan_line: plan version is % (read-only); % rejected', v_status, TG_OP
    USING ERRCODE = '55000';
END $$;

-- Offers are versioned (BR-REC-046): once an offer version leaves DRAFT its terms and
-- its simulation stamp are frozen; a negotiation creates offer_version + 1. Only the
-- life-cycle columns below may change. DELETE only for DRAFT.
CREATE OR REPLACE FUNCTION rec.tg_offer_guard()
RETURNS trigger LANGUAGE plpgsql
AS $$
DECLARE
  allowed constant text[] := ARRAY['status','workflow_instance_id','approved_by','approved_at',
                                   'offer_letter_document_id','signed_document_id','esign_status',
                                   'sent_at','responded_at','decline_reason_code','response_comment',
                                   'superseded_by','updated_at','updated_by','version'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' AND NOT sys.purge_bypass(OLD.tenant_id) THEN   -- D9
      RAISE EXCEPTION 'rec.offer %: only DRAFT offers may be deleted', OLD.id USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.status <> 'DRAFT' AND (to_jsonb(NEW) - allowed) IS DISTINCT FROM (to_jsonb(OLD) - allowed) THEN
    RAISE EXCEPTION 'rec.offer % (version %) is % and its terms are frozen; create offer_version + 1',
      OLD.id, OLD.offer_version, OLD.status USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;

-- =============================================================================
-- 1. STAFFING PLAN  (P-REC-01, BR-REC-001..006; versioned, read-only once approved)
--    Recruitment owns the COST budget; the approved FTE is written to the Core
--    position budget through the Core position API (R2-02 §5 #8, BRD 05 P7).
-- =============================================================================
CREATE TABLE rec.staffing_plan (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id       uuid NOT NULL,                       -- -> core.legal_entity (soft)
  plan_code             text NOT NULL,                       -- e.g. FY2027; stable across versions
  name                  text NOT NULL,
  period_start          date NOT NULL,
  period_end            date NOT NULL,                       -- INCLUSIVE business date
  plan_version          int  NOT NULL DEFAULT 1,
  previous_version_id   uuid,                                -- the version this one revises
  change_reason         text,                                -- required from version 2 (BR-REC-004)
  currency_code         sys.currency_code NOT NULL DEFAULT 'VND',
  status                text NOT NULL DEFAULT 'DRAFT',
  workflow_instance_id  uuid,                                -- -> core.workflow_instance (soft)
  submitted_at          timestamptz,
  approved_by           uuid,                                -- -> core.user_account (soft)
  approved_at           timestamptz,
  superseded_at         timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT staffing_plan_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT staffing_plan_prev_fk FOREIGN KEY (tenant_id, previous_version_id) REFERENCES rec.staffing_plan (tenant_id, id),
  CONSTRAINT staffing_plan_version_uq UNIQUE (tenant_id, legal_entity_id, plan_code, plan_version),
  CONSTRAINT staffing_plan_period_ck CHECK (period_end >= period_start),
  CONSTRAINT staffing_plan_version_ck CHECK (plan_version >= 1),
  CONSTRAINT staffing_plan_revision_ck CHECK (plan_version = 1 OR (previous_version_id IS NOT NULL AND change_reason IS NOT NULL)),
  CONSTRAINT staffing_plan_status_ck CHECK (status IN ('DRAFT','PENDING_APPROVAL','RETURNED','APPROVED',
                                                       'REJECTED','SUPERSEDED','CANCELLED')),
  CONSTRAINT staffing_plan_approved_ck CHECK (status NOT IN ('APPROVED','SUPERSEDED') OR approved_at IS NOT NULL)
);
-- exactly one approved (current) version per plan
CREATE UNIQUE INDEX staffing_plan_current_uq ON rec.staffing_plan (tenant_id, legal_entity_id, plan_code)
  WHERE status = 'APPROVED';
CREATE INDEX staffing_plan_period_ix ON rec.staffing_plan (tenant_id, legal_entity_id, period_start);

CREATE TABLE rec.staffing_plan_line (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  staffing_plan_id          uuid NOT NULL,
  org_unit_id               uuid NOT NULL,                   -- -> core.org_unit (soft)
  position_id               uuid,                            -- -> core.position (soft); NULL = proposed position
  proposed_position_ref     text,                            -- proposal key until Core creates the position (P7)
  job_title_id              uuid,                            -- -> core.job_title (soft)
  grade_id                  uuid,                            -- -> core.grade (soft)
  actual_hc_at_start        int,                             -- pre-fill from People (BR-REC-002), informative
  actual_fte_at_start       numeric(9,4),
  planned_hc                int NOT NULL DEFAULT 0,
  planned_fte               numeric(9,4) NOT NULL DEFAULT 0,
  planned_hires             int NOT NULL DEFAULT 0,
  planned_exits             int NOT NULL DEFAULT 0,
  budget_cost_month_amount  sys.money_amount NOT NULL DEFAULT 0,   -- pii:P2-adjacent (aggregate compensation)
  budget_cost_period_amount sys.money_amount NOT NULL DEFAULT 0,
  on_cost_rate              sys.rate,                        -- employer SI/HI/UI on-cost used (BR-REC-003)
  cost_basis                text NOT NULL DEFAULT 'BAND_MIDPOINT',
  core_sync_status          text NOT NULL DEFAULT 'NOT_REQUIRED',
  core_synced_at            timestamptz,
  core_sync_error           text,
  remap_status              text NOT NULL DEFAULT 'OK',      -- BR-REC-006
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT staffing_plan_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT staffing_plan_line_plan_fk FOREIGN KEY (tenant_id, staffing_plan_id) REFERENCES rec.staffing_plan (tenant_id, id),
  CONSTRAINT staffing_plan_line_uq UNIQUE NULLS NOT DISTINCT
    (tenant_id, staffing_plan_id, org_unit_id, position_id, proposed_position_ref),
  CONSTRAINT staffing_plan_line_position_ck CHECK (position_id IS NOT NULL OR proposed_position_ref IS NOT NULL),
  CONSTRAINT staffing_plan_line_qty_ck CHECK (planned_hc >= 0 AND planned_fte >= 0 AND planned_hires >= 0 AND planned_exits >= 0),
  CONSTRAINT staffing_plan_line_cost_ck CHECK (budget_cost_month_amount >= 0 AND budget_cost_period_amount >= 0),
  CONSTRAINT staffing_plan_line_basis_ck CHECK (cost_basis IN ('BAND_MIDPOINT','REFERENCE_POINT','MANUAL')),
  CONSTRAINT staffing_plan_line_sync_ck CHECK (core_sync_status IN ('NOT_REQUIRED','PENDING','SYNCED','FAILED')),
  CONSTRAINT staffing_plan_line_remap_ck CHECK (remap_status IN ('OK','ORG_UNIT_RETIRED','ORG_UNIT_MOVED',
                                                                 'POSITION_RETIRED','REMAPPED'))
);
CREATE INDEX staffing_plan_line_org_ix ON rec.staffing_plan_line (tenant_id, org_unit_id);
CREATE INDEX staffing_plan_line_position_ix ON rec.staffing_plan_line (tenant_id, position_id) WHERE position_id IS NOT NULL;
COMMENT ON COLUMN rec.staffing_plan_line.budget_cost_month_amount IS 'pii:P2 compensation budget (aggregate); visible with the compensation permission';

-- =============================================================================
-- 2. REQUISITION  (P-REC-02, BR-REC-007..012)
-- =============================================================================
CREATE TABLE rec.requisition (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                      text NOT NULL,                   -- Core numbering sequence (C10)
  legal_entity_id           uuid NOT NULL,                   -- soft core
  org_unit_id               uuid NOT NULL,                   -- soft core (data-scope key, BR-REC-011)
  position_id               uuid,                            -- soft core.position (must pre-exist in MVP, A-01)
  job_title_id              uuid,                            -- soft core.job_title
  grade_id                  uuid,                            -- soft core.grade
  staffing_plan_line_id     uuid,                            -- NULL = unplanned
  classification            text,                            -- set by the plan check (BR-REC-008)
  vacancies                 int NOT NULL DEFAULT 1,
  filled_count              int NOT NULL DEFAULT 0,          -- hires accepted by People
  reserved_count            int NOT NULL DEFAULT 0,          -- accepted offers not yet started (BR-REC-047)
  reason                    text NOT NULL,
  replaced_employment_id    uuid,                            -- -> hr.employment (soft), REPLACEMENT only
  employment_type           text NOT NULL DEFAULT 'FULL_TIME',
  contract_type_code        text,                            -- code list CONTRACT_TYPE (statutory)
  work_location_id          uuid,                            -- soft core.location
  target_start_date         date,
  salary_basis              text NOT NULL DEFAULT 'GROSS',
  salary_min_amount         sys.money_amount,                -- pii:P2-adjacent (compensation permission)
  salary_max_amount         sys.money_amount,
  currency_code             sys.currency_code NOT NULL DEFAULT 'VND',
  is_out_of_band            boolean NOT NULL DEFAULT false,  -- BR-REC-010
  out_of_band_justification text,
  jd_text                   text,
  hiring_team               jsonb NOT NULL DEFAULT '[]'::jsonb,   -- [{"user_account_id":..,"role":"HIRING_MANAGER"}]
  requested_by              uuid,                            -- soft core.user_account
  workflow_instance_id      uuid,                            -- soft core.workflow_instance
  status                    text NOT NULL DEFAULT 'DRAFT',
  approved_at               timestamptz,
  closed_at                 timestamptz,
  close_reason_code         text,
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT requisition_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT requisition_plan_line_fk FOREIGN KEY (tenant_id, staffing_plan_line_id) REFERENCES rec.staffing_plan_line (tenant_id, id),
  CONSTRAINT requisition_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT requisition_class_ck CHECK (classification IS NULL OR classification IN ('WITHIN_PLAN','OVER_PLAN','UNPLANNED')),
  CONSTRAINT requisition_class_line_ck CHECK (classification IS NULL OR (classification = 'UNPLANNED') = (staffing_plan_line_id IS NULL)),
  CONSTRAINT requisition_counts_ck CHECK (vacancies >= 1 AND filled_count >= 0 AND reserved_count >= 0
                                          AND filled_count + reserved_count <= vacancies),
  CONSTRAINT requisition_reason_ck CHECK (reason IN ('NEW','REPLACEMENT','TEMPORARY')),
  CONSTRAINT requisition_replacement_ck CHECK (reason <> 'REPLACEMENT' OR replaced_employment_id IS NOT NULL),
  CONSTRAINT requisition_emp_type_ck CHECK (employment_type IN ('FULL_TIME','PART_TIME')),
  CONSTRAINT requisition_basis_ck CHECK (salary_basis IN ('GROSS','NET')),
  CONSTRAINT requisition_range_ck CHECK (salary_min_amount IS NULL OR salary_max_amount IS NULL OR salary_min_amount <= salary_max_amount),
  CONSTRAINT requisition_oob_ck CHECK (NOT is_out_of_band OR out_of_band_justification IS NOT NULL),
  CONSTRAINT requisition_team_ck CHECK (jsonb_typeof(hiring_team) = 'array'),
  CONSTRAINT requisition_status_ck CHECK (status IN ('DRAFT','PENDING_APPROVAL','RETURNED','APPROVED','REJECTED',
                                                     'ON_HOLD','FILLED','CLOSED','CANCELLED'))
);
CREATE INDEX requisition_scope_ix ON rec.requisition (tenant_id, org_unit_id, status);
CREATE INDEX requisition_plan_line_ix ON rec.requisition (tenant_id, staffing_plan_line_id) WHERE staffing_plan_line_id IS NOT NULL;
CREATE INDEX requisition_replaced_ix ON rec.requisition (tenant_id, replaced_employment_id) WHERE replaced_employment_id IS NOT NULL;
CREATE INDEX requisition_position_ix ON rec.requisition (tenant_id, position_id) WHERE position_id IS NOT NULL;

-- =============================================================================
-- 3. PIPELINE TEMPLATE / STAGES and JOB OPENING  (BR-REC-013..019, 027)
-- =============================================================================
CREATE TABLE rec.pipeline_template (
  tenant_id     uuid NOT NULL,
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  code          text NOT NULL,
  name          text NOT NULL,
  is_default    boolean NOT NULL DEFAULT false,
  status        text NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    uuid,
  version       int NOT NULL DEFAULT 0,
  CONSTRAINT pipeline_template_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pipeline_template_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT pipeline_template_status_ck CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE UNIQUE INDEX pipeline_template_default_uq ON rec.pipeline_template (tenant_id) WHERE is_default AND status = 'ACTIVE';

CREATE TABLE rec.job_opening (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  requisition_id        uuid NOT NULL,
  code                  text NOT NULL,
  title_vi              text NOT NULL,
  title_en              text,
  jd_vi                 text,
  jd_en                 text,
  requirements          text,
  vacancies             int  NOT NULL DEFAULT 1,             -- sum over openings <= requisition.vacancies (service, BR-REC-013)
  pipeline_template_id  uuid,
  salary_display        text NOT NULL DEFAULT 'NEGOTIABLE',  -- "Thỏa thuận"
  visibility            text NOT NULL DEFAULT 'PUBLIC',
  work_location_id      uuid,                                -- soft core.location
  job_level_code        text,                                -- facet on the career site
  screening_questions   jsonb NOT NULL DEFAULT '[]'::jsonb,  -- [{"code","type","knockout_answer"}] (BR-REC-028)
  hiring_team           jsonb NOT NULL DEFAULT '[]'::jsonb,
  status                text NOT NULL DEFAULT 'DRAFT',
  opened_at             timestamptz,
  closed_at             timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT job_opening_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT job_opening_requisition_fk FOREIGN KEY (tenant_id, requisition_id) REFERENCES rec.requisition (tenant_id, id),
  CONSTRAINT job_opening_template_fk FOREIGN KEY (tenant_id, pipeline_template_id) REFERENCES rec.pipeline_template (tenant_id, id),
  CONSTRAINT job_opening_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT job_opening_vacancies_ck CHECK (vacancies >= 1),
  CONSTRAINT job_opening_salary_display_ck CHECK (salary_display IN ('SHOW_RANGE','NEGOTIABLE','HIDDEN')),
  CONSTRAINT job_opening_visibility_ck CHECK (visibility IN ('PUBLIC','INTERNAL','REFERRAL_ONLY')),
  CONSTRAINT job_opening_questions_ck CHECK (jsonb_typeof(screening_questions) = 'array'),
  CONSTRAINT job_opening_team_ck CHECK (jsonb_typeof(hiring_team) = 'array'),
  CONSTRAINT job_opening_status_ck CHECK (status IN ('DRAFT','OPEN','ON_HOLD','FILLED','CLOSED','CANCELLED'))
);
CREATE INDEX job_opening_requisition_ix ON rec.job_opening (tenant_id, requisition_id);
CREATE INDEX job_opening_status_ix ON rec.job_opening (tenant_id, status, opened_at);

-- Stages belong either to a template or to one opening (stages added per opening).
CREATE TABLE rec.pipeline_stage (
  tenant_id             uuid NOT NULL,
  id                    uuid NOT NULL DEFAULT sys.uuid_v7(),
  pipeline_template_id  uuid,
  job_opening_id        uuid,
  seq                   smallint NOT NULL,
  code                  text NOT NULL,
  name_vi               text NOT NULL,
  name_en               text,
  stage_type            text NOT NULL,
  is_active             boolean NOT NULL DEFAULT true,
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid,
  version               int NOT NULL DEFAULT 0,
  CONSTRAINT pipeline_stage_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT pipeline_stage_template_fk FOREIGN KEY (tenant_id, pipeline_template_id) REFERENCES rec.pipeline_template (tenant_id, id),
  CONSTRAINT pipeline_stage_opening_fk FOREIGN KEY (tenant_id, job_opening_id) REFERENCES rec.job_opening (tenant_id, id),
  CONSTRAINT pipeline_stage_owner_ck CHECK (num_nonnulls(pipeline_template_id, job_opening_id) = 1),
  CONSTRAINT pipeline_stage_type_ck CHECK (stage_type IN ('NEW','SCREENING','ASSESSMENT','INTERVIEW','OFFER',
                                                          'PREBOARDING','HIRED','REJECTED','WITHDRAWN')),
  CONSTRAINT pipeline_stage_code_uq UNIQUE NULLS NOT DISTINCT (tenant_id, pipeline_template_id, job_opening_id, code)
);
CREATE INDEX pipeline_stage_opening_ix ON rec.pipeline_stage (tenant_id, job_opening_id) WHERE job_opening_id IS NOT NULL;

-- =============================================================================
-- 4. JOB POSTING  (one row per opening × channel; BR-REC-016, 019)
-- =============================================================================
CREATE TABLE rec.job_posting (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  job_opening_id   uuid NOT NULL,
  channel          text NOT NULL,
  channel_detail   text,                                     -- OTHER: free name of the channel
  external_id      text,                                     -- board's posting id
  url              text,
  status           text NOT NULL DEFAULT 'DRAFT',
  publish_at       timestamptz,
  expire_at        timestamptz,
  cost_amount      sys.money_amount,                         -- BR-REC-058
  currency_code    sys.currency_code NOT NULL DEFAULT 'VND',
  attempts         int NOT NULL DEFAULT 0,
  last_error       text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT job_posting_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT job_posting_opening_fk FOREIGN KEY (tenant_id, job_opening_id) REFERENCES rec.job_opening (tenant_id, id),
  CONSTRAINT job_posting_channel_ck CHECK (channel IN ('CAREER_SITE','INTERNAL_BOARD','TOPCV','VIETNAMWORKS',
                                                       'ITVIEC','LINKEDIN','FACEBOOK','EMAIL_INBOX','AGENCY','OTHER')),
  CONSTRAINT job_posting_status_ck CHECK (status IN ('DRAFT','PUBLISHING','PUBLISHED','FAILED','UNPUBLISHED','EXPIRED')),
  CONSTRAINT job_posting_window_ck CHECK (expire_at IS NULL OR publish_at IS NULL OR expire_at > publish_at),
  CONSTRAINT job_posting_cost_ck CHECK (cost_amount IS NULL OR cost_amount >= 0)
);
-- one live posting per opening and channel (OTHER/AGENCY may repeat with a different detail)
CREATE UNIQUE INDEX job_posting_live_uq ON rec.job_posting (tenant_id, job_opening_id, channel, coalesce(channel_detail, ''))
  WHERE status IN ('DRAFT','PUBLISHING','PUBLISHED','FAILED');
CREATE INDEX job_posting_external_ix ON rec.job_posting (tenant_id, channel, external_id) WHERE external_id IS NOT NULL;

-- =============================================================================
-- 5. CANDIDATE  (one profile per person per tenant, BR-REC-020/021; no cross-tenant
--    matching; per-candidate subject key; retention + anonymisation)
-- =============================================================================
CREATE TABLE rec.candidate (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),   -- = saas.subject_key.subject_id (CANDIDATE)
  family_name             text,                                -- họ
  middle_name             text,
  given_name              text,
  full_name               text NOT NULL,                       -- 'ANONYMISED' after retention
  full_name_search        text GENERATED ALWAYS AS (sys.search_key(full_name)) STORED,
  birth_date              date,                                -- pii:P2
  gender_code             text,                                -- optional (C-01)
  nationality_code        char(2),                             -- core.country (by code)
  email_enc               bytea,                               -- pii:P3 (raised for candidates; accepted D8 (2026-10-21), DPO to confirm)
  email_bidx              bytea,                               -- duplicate check (BR-REC-023), lower-cased
  phone_enc               bytea,                               -- pii:P3 (raised for candidates; accepted D8 (2026-10-21), DPO to confirm)
  phone_bidx              bytea,                               -- normalised to +84 before HMAC
  phone_last4             text,
  address_enc             bytea,                               -- pii:P3 (raised for candidates; accepted D8 (2026-10-21), DPO to confirm)
  id_doc_type             text,                                -- CCCD | PASSPORT; collected only from offer stage
  id_doc_enc              bytea,                               -- pii:P3 identity document number
  id_doc_bidx             bytea,
  id_doc_last4            text,
  profile                 jsonb NOT NULL DEFAULT '{}'::jsonb,  -- pii:P2 education[], experience[], languages[]
  skills                  text[] NOT NULL DEFAULT '{}',        -- faceted search (BR-REC-024/025)
  expected_salary_amount  sys.money_amount,                    -- pii:P2 (compensation permission)
  expected_salary_basis   text,
  currency_code           sys.currency_code NOT NULL DEFAULT 'VND',
  notice_period_days      smallint,
  source_channel          text,                                -- first source (application keeps its own)
  employee_person_id      uuid,                                -- -> hr.person (soft): internal or former employee match
  employee_employment_id  uuid,                                -- -> hr.employment (soft)
  is_rehire_ineligible    boolean NOT NULL DEFAULT false,      -- flag copied from the People match API (C14)
  merged_into_id          uuid,                                -- set when status = MERGED (BR-REC-023, reversible 30 d)
  subject_key_version     int  NOT NULL DEFAULT 1,             -- saas.subject_key (CANDIDATE, id)
  status                  text NOT NULL DEFAULT 'ACTIVE',
  retention_until         date,                                -- set from saas.retention_policy (BR-REC-061)
  legal_hold_until        date,                                -- BR-REC-062: suspends deletion
  legal_hold_reason       text,
  anonymised_at           timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT candidate_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT candidate_merged_fk FOREIGN KEY (tenant_id, merged_into_id) REFERENCES rec.candidate (tenant_id, id),
  CONSTRAINT candidate_status_ck CHECK (status IN ('ACTIVE','MERGED','CLOSED','ANONYMISED')),
  CONSTRAINT candidate_merged_ck CHECK ((status = 'MERGED') = (merged_into_id IS NOT NULL)),
  CONSTRAINT candidate_merge_self_ck CHECK (merged_into_id IS DISTINCT FROM id),
  CONSTRAINT candidate_id_doc_type_ck CHECK (id_doc_type IS NULL OR id_doc_type IN ('CCCD','CMND','PASSPORT')),
  CONSTRAINT candidate_basis_ck CHECK (expected_salary_basis IS NULL OR expected_salary_basis IN ('GROSS','NET')),
  CONSTRAINT candidate_last4_ck CHECK ((phone_last4 IS NULL OR length(phone_last4) <= 4)
                                       AND (id_doc_last4 IS NULL OR length(id_doc_last4) <= 4)),
  CONSTRAINT candidate_profile_ck CHECK (jsonb_typeof(profile) = 'object'),
  CONSTRAINT candidate_hold_ck CHECK (legal_hold_until IS NULL OR legal_hold_reason IS NOT NULL),
  -- anonymised = no ciphertext, no blind index (tenant key!), no plaintext identifiers left
  CONSTRAINT candidate_anonymised_ck CHECK (status <> 'ANONYMISED' OR (
        anonymised_at IS NOT NULL
    AND email_enc IS NULL AND email_bidx IS NULL AND phone_enc IS NULL AND phone_bidx IS NULL
    AND phone_last4 IS NULL AND address_enc IS NULL AND id_doc_enc IS NULL AND id_doc_bidx IS NULL
    AND id_doc_last4 IS NULL AND birth_date IS NULL AND family_name IS NULL AND middle_name IS NULL
    AND given_name IS NULL AND expected_salary_amount IS NULL AND employee_person_id IS NULL
    AND employee_employment_id IS NULL))
);
CREATE INDEX candidate_email_ix  ON rec.candidate (tenant_id, email_bidx)  WHERE email_bidx IS NOT NULL;
CREATE INDEX candidate_phone_ix  ON rec.candidate (tenant_id, phone_bidx)  WHERE phone_bidx IS NOT NULL;
CREATE INDEX candidate_id_doc_ix ON rec.candidate (tenant_id, id_doc_bidx) WHERE id_doc_bidx IS NOT NULL;
CREATE INDEX candidate_name_trgm_ix ON rec.candidate USING gin (full_name_search gin_trgm_ops);   -- NFR-REC-010
CREATE INDEX candidate_skills_gix ON rec.candidate USING gin (skills);
CREATE INDEX candidate_person_ix ON rec.candidate (tenant_id, employee_person_id) WHERE employee_person_id IS NOT NULL;
-- retention handler (daily, triggered by the saas retention scheduler; NFR-REC-009 <= 24 h)
CREATE INDEX candidate_retention_ix ON rec.candidate (tenant_id, retention_until)
  WHERE status <> 'ANONYMISED' AND retention_until IS NOT NULL;
COMMENT ON TABLE rec.candidate IS
  'One profile per person per tenant. P3 columns encrypted with the candidate subject DEK (saas.subject_key CANDIDATE). '
  'Retention expiry: destroy DEK, null *_enc/*_bidx/last4 and personal plaintext, full_name := ''ANONYMISED'', status ANONYMISED.';
COMMENT ON COLUMN rec.candidate.full_name   IS 'pii:P1 (overwritten on anonymisation)';
COMMENT ON COLUMN rec.candidate.birth_date  IS 'pii:P2';
COMMENT ON COLUMN rec.candidate.email_enc   IS 'pii:P3 AES-256-GCM, candidate subject DEK (raised from P2 for crypto-shred on retention; accepted D8 (2026-10-21), DPO to confirm)';
COMMENT ON COLUMN rec.candidate.phone_enc   IS 'pii:P3 AES-256-GCM, candidate subject DEK (raised from P2 for crypto-shred on retention; accepted D8 (2026-10-21), DPO to confirm)';
COMMENT ON COLUMN rec.candidate.address_enc IS 'pii:P3 AES-256-GCM, candidate subject DEK (raised from P2 for crypto-shred on retention; accepted D8 (2026-10-21), DPO to confirm)';
COMMENT ON COLUMN rec.candidate.id_doc_enc  IS 'pii:P3 AES-256-GCM, candidate subject DEK; collected from offer stage only (BR-REC-021)';
COMMENT ON COLUMN rec.candidate.email_bidx  IS 'HMAC under the TENANT blind-index key: must be nulled on anonymisation';
COMMENT ON COLUMN rec.candidate.profile     IS 'pii:P2 education / experience / languages';
COMMENT ON COLUMN rec.candidate.expected_salary_amount IS 'pii:P2 compensation permission (BR-REC-032)';

-- Consent: soft reference to the saas consent registry + cached state (BR-REC-059).
CREATE TABLE rec.candidate_consent_ref (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  candidate_id       uuid NOT NULL,
  purpose_code       text NOT NULL,
  consent_record_id  uuid,                                    -- -> saas.consent_record (soft); NULL = legal basis
  legal_basis_code   text,                                    -- when processing relies on a recorded legal basis
  cached_status      text NOT NULL,                           -- cache only; saas hasConsent() is authoritative
  given_at           timestamptz,
  expires_at         timestamptz,                             -- talent pool: N months, max 24 [verify-legal]
  withdrawn_at       timestamptz,
  synced_at          timestamptz NOT NULL DEFAULT now(),      -- last refresh from saas events/API
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT candidate_consent_ref_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT candidate_consent_ref_candidate_fk FOREIGN KEY (tenant_id, candidate_id) REFERENCES rec.candidate (tenant_id, id),
  CONSTRAINT candidate_consent_ref_purpose_ck CHECK (purpose_code IN ('APPLICATION_PROCESSING','TALENT_POOL','GROUP_SHARING',
                                                                      'REFERRAL_CONTACT')),
  CONSTRAINT candidate_consent_ref_status_ck CHECK (cached_status IN ('PENDING','GIVEN','WITHDRAWN','EXPIRED','LEGAL_BASIS')),
  CONSTRAINT candidate_consent_ref_basis_ck CHECK (consent_record_id IS NOT NULL OR legal_basis_code IS NOT NULL
                                                   OR cached_status = 'PENDING')
);
CREATE UNIQUE INDEX candidate_consent_ref_record_uq ON rec.candidate_consent_ref (tenant_id, consent_record_id)
  WHERE consent_record_id IS NOT NULL;
CREATE INDEX candidate_consent_ref_candidate_ix ON rec.candidate_consent_ref (tenant_id, candidate_id, purpose_code);
COMMENT ON TABLE rec.candidate_consent_ref IS
  'Reference to saas.consent_record (subject_type CANDIDATE). The registry is owned by saas (R2-02 §5 #27); '
  'cached_status is a hint for list filters only, every processing decision calls hasConsent().';

-- CV and documents (files live in the Core document service; C24).
CREATE TABLE rec.candidate_file (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  candidate_id      uuid NOT NULL,
  file_type         text NOT NULL,
  document_id       uuid NOT NULL,                            -- -> core.document (soft)
  is_sensitive      boolean NOT NULL DEFAULT false,           -- health / judicial record (C-10, NFR-REC-004)
  parsed_enc        bytea,                                    -- pii:P3 parser output (contacts inside), candidate DEK
  parse_confidence  numeric(5,4),
  parse_status      text NOT NULL DEFAULT 'NONE',
  uploaded_at       timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT candidate_file_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT candidate_file_candidate_fk FOREIGN KEY (tenant_id, candidate_id) REFERENCES rec.candidate (tenant_id, id),
  CONSTRAINT candidate_file_type_ck CHECK (file_type IN ('CV','COVER_LETTER','DEGREE','CERTIFICATE','HEALTH_CERT',
                                                         'ID_SCAN','PHOTO','JUDICIAL_RECORD','OFFER_LETTER','OTHER')),
  CONSTRAINT candidate_file_sensitive_ck CHECK (file_type NOT IN ('HEALTH_CERT','JUDICIAL_RECORD','ID_SCAN') OR is_sensitive),
  CONSTRAINT candidate_file_parse_ck CHECK (parse_status IN ('NONE','PENDING','PARSED','CONFIRMED','FAILED'))
);
CREATE INDEX candidate_file_candidate_ix ON rec.candidate_file (tenant_id, candidate_id);
COMMENT ON COLUMN rec.candidate_file.parsed_enc IS 'pii:P3 AES-256-GCM, candidate subject DEK (parsed CV incl. contacts)';

-- =============================================================================
-- 6. REFERRAL PROGRAMME and REFERRAL  (P-REC-11, BR-REC-055..057)
-- =============================================================================
CREATE TABLE rec.referral_program (
  tenant_id              uuid NOT NULL,
  id                     uuid NOT NULL DEFAULT sys.uuid_v7(),
  code                   text NOT NULL,
  name                   text NOT NULL,
  bonus_amount           sys.money_amount NOT NULL,
  currency_code          sys.currency_code NOT NULL DEFAULT 'VND',
  component_code         text,                                -- -> pay.salary_component (soft, by code)
  qualifying_condition   text NOT NULL,
  qualifying_days        int,
  eligibility            jsonb NOT NULL DEFAULT '{}'::jsonb,  -- eligible openings / org units
  exclusions             jsonb NOT NULL DEFAULT '{}'::jsonb,  -- e.g. hiring managers, HR
  valid_from             date NOT NULL,
  valid_to               date,                                -- EXCLUSIVE
  status                 text NOT NULL DEFAULT 'ACTIVE',
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid,
  version                int NOT NULL DEFAULT 0,
  CONSTRAINT referral_program_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT referral_program_code_uq UNIQUE (tenant_id, code),
  CONSTRAINT referral_program_bonus_ck CHECK (bonus_amount >= 0),
  CONSTRAINT referral_program_cond_ck CHECK (qualifying_condition IN ('HIRED','PASSED_PROBATION','DAYS_AFTER_START')),
  CONSTRAINT referral_program_days_ck CHECK (qualifying_condition <> 'DAYS_AFTER_START' OR qualifying_days > 0),
  CONSTRAINT referral_program_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT referral_program_status_ck CHECK (status IN ('DRAFT','ACTIVE','INACTIVE'))
);

CREATE TABLE rec.referral (
  tenant_id                 uuid NOT NULL,
  id                        uuid NOT NULL DEFAULT sys.uuid_v7(),
  referral_program_id       uuid,
  referrer_employment_id    uuid NOT NULL,                   -- -> hr.employment (soft)
  referrer_user_account_id  uuid,                            -- -> core.user_account (soft)
  candidate_id              uuid NOT NULL,
  job_opening_id            uuid NOT NULL,
  relationship_code         text,
  status                    text NOT NULL DEFAULT 'SUBMITTED',
  submitted_at              timestamptz NOT NULL DEFAULT now(),
  qualified_at              timestamptz,
  bonus_amount              sys.money_amount,                -- copied from the programme at qualification
  currency_code             sys.currency_code NOT NULL DEFAULT 'VND',
  payroll_instruction_id    uuid,                            -- = rec.outbox_event id of the bonus instruction (P9)
  bonus_paid_at             timestamptz,                     -- pay.ReferralBonusPaid (C20)
  not_qualified_reason      text,
  created_at                timestamptz NOT NULL DEFAULT now(),
  created_by                uuid,
  updated_at                timestamptz NOT NULL DEFAULT now(),
  updated_by                uuid,
  version                   int NOT NULL DEFAULT 0,
  CONSTRAINT referral_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT referral_program_fk FOREIGN KEY (tenant_id, referral_program_id) REFERENCES rec.referral_program (tenant_id, id),
  CONSTRAINT referral_candidate_fk FOREIGN KEY (tenant_id, candidate_id) REFERENCES rec.candidate (tenant_id, id),
  CONSTRAINT referral_opening_fk FOREIGN KEY (tenant_id, job_opening_id) REFERENCES rec.job_opening (tenant_id, id),
  CONSTRAINT referral_status_ck CHECK (status IN ('SUBMITTED','CONSENT_PENDING','IN_PROCESS','HIRED','QUALIFIED',
                                                  'BONUS_INSTRUCTED','BONUS_PAID','NOT_QUALIFIED','REJECTED','WITHDRAWN')),
  CONSTRAINT referral_bonus_ck CHECK (status NOT IN ('BONUS_INSTRUCTED','BONUS_PAID') OR
                                      (bonus_amount IS NOT NULL AND payroll_instruction_id IS NOT NULL))
);
-- first live referral of a candidate for an opening wins
CREATE UNIQUE INDEX referral_live_uq ON rec.referral (tenant_id, candidate_id, job_opening_id)
  WHERE status NOT IN ('REJECTED','WITHDRAWN','NOT_QUALIFIED');
CREATE INDEX referral_referrer_ix ON rec.referral (tenant_id, referrer_employment_id);

-- =============================================================================
-- 7. APPLICATION  (candidate × opening; BR-REC-020, 026..032)
-- =============================================================================
CREATE TABLE rec.application (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  candidate_id            uuid NOT NULL,
  job_opening_id          uuid NOT NULL,
  job_posting_id          uuid,                              -- channel the application came through
  source_channel          text NOT NULL,
  referral_id             uuid,
  is_internal             boolean NOT NULL DEFAULT false,    -- internal applicant (BR-REC-015, 053)
  internal_employment_id  uuid,                              -- -> hr.employment (soft)
  current_stage_id        uuid,
  status                  text NOT NULL DEFAULT 'ACTIVE',
  knockout_flag           boolean NOT NULL DEFAULT false,    -- advisory only (BR-REC-026/028)
  screening_answers       jsonb NOT NULL DEFAULT '[]'::jsonb,
  rejection_reason_code   text,
  withdrawal_reason_code  text,
  decided_by              uuid,                              -- human decision maker (BR-REC-026)
  preboarding_status      text NOT NULL DEFAULT 'NOT_STARTED',
  applied_at              timestamptz NOT NULL DEFAULT now(),
  closed_at               timestamptz,
  hired_at                timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT application_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT application_candidate_fk FOREIGN KEY (tenant_id, candidate_id) REFERENCES rec.candidate (tenant_id, id),
  CONSTRAINT application_opening_fk FOREIGN KEY (tenant_id, job_opening_id) REFERENCES rec.job_opening (tenant_id, id),
  CONSTRAINT application_posting_fk FOREIGN KEY (tenant_id, job_posting_id) REFERENCES rec.job_posting (tenant_id, id),
  CONSTRAINT application_referral_fk FOREIGN KEY (tenant_id, referral_id) REFERENCES rec.referral (tenant_id, id),
  CONSTRAINT application_stage_fk FOREIGN KEY (tenant_id, current_stage_id) REFERENCES rec.pipeline_stage (tenant_id, id),
  CONSTRAINT application_uq UNIQUE (tenant_id, candidate_id, job_opening_id),
  -- targets of composite FKs (hand-off: application belongs to the candidate)
  CONSTRAINT application_candidate_id_uq UNIQUE (tenant_id, candidate_id, id),
  CONSTRAINT application_source_ck CHECK (source_channel IN ('CAREER_SITE','INTERNAL_BOARD','TOPCV','VIETNAMWORKS','ITVIEC',
                                                             'LINKEDIN','FACEBOOK','EMAIL_INBOX','AGENCY','REFERRAL',
                                                             'MANUAL','TALENT_POOL','OTHER')),
  CONSTRAINT application_status_ck CHECK (status IN ('ACTIVE','ON_HOLD','OFFERED','READY_TO_HIRE','HIRED',
                                                     'REJECTED','WITHDRAWN','CLOSED')),
  CONSTRAINT application_reject_ck CHECK (status <> 'REJECTED' OR (rejection_reason_code IS NOT NULL AND decided_by IS NOT NULL)),
  CONSTRAINT application_withdraw_ck CHECK (status <> 'WITHDRAWN' OR withdrawal_reason_code IS NOT NULL),
  CONSTRAINT application_hired_ck CHECK (status <> 'HIRED' OR hired_at IS NOT NULL),
  CONSTRAINT application_internal_ck CHECK (NOT is_internal OR internal_employment_id IS NOT NULL),
  CONSTRAINT application_answers_ck CHECK (jsonb_typeof(screening_answers) = 'array'),
  CONSTRAINT application_preboarding_ck CHECK (preboarding_status IN ('NOT_STARTED','IN_PROGRESS','READY','WAIVED'))
);
CREATE INDEX application_opening_ix ON rec.application (tenant_id, job_opening_id, status);
CREATE INDEX application_stage_ix ON rec.application (tenant_id, current_stage_id);
CREATE INDEX application_referral_ix ON rec.application (tenant_id, referral_id) WHERE referral_id IS NOT NULL;

-- =============================================================================
-- 8. STAGE HISTORY  (append-only; time-in-stage, time-to-hire; BR-REC-031)
--    No free text here (would need anonymisation in an append-only table).
-- =============================================================================
CREATE TABLE rec.stage_history (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  application_id   uuid NOT NULL,
  from_stage_id    uuid,
  to_stage_id      uuid,
  from_status      text,
  to_status        text NOT NULL,
  reason_code      text,
  changed_by       uuid,                                      -- soft core.user_account (NULL = system)
  changed_at       timestamptz NOT NULL DEFAULT now(),
  is_bulk          boolean NOT NULL DEFAULT false,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  CONSTRAINT stage_history_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT stage_history_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES rec.application (tenant_id, id),
  CONSTRAINT stage_history_from_fk FOREIGN KEY (tenant_id, from_stage_id) REFERENCES rec.pipeline_stage (tenant_id, id),
  CONSTRAINT stage_history_to_fk FOREIGN KEY (tenant_id, to_stage_id) REFERENCES rec.pipeline_stage (tenant_id, id),
  CONSTRAINT stage_history_change_ck CHECK (from_stage_id IS DISTINCT FROM to_stage_id OR from_status IS DISTINCT FROM to_status)
);
CREATE INDEX stage_history_application_ix ON rec.stage_history (tenant_id, application_id, changed_at);
CREATE TRIGGER trg_immutable BEFORE UPDATE OR DELETE ON rec.stage_history
  FOR EACH ROW EXECUTE FUNCTION rec.tg_append_only();           -- D9: DELETE only by tenant purge

-- =============================================================================
-- 9. INTERVIEW, PANEL, SCORECARD  (P-REC-06, BR-REC-033..037, 039)
-- =============================================================================
CREATE TABLE rec.interview (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  application_id          uuid NOT NULL,
  round_no                smallint NOT NULL DEFAULT 1,
  interview_type          text NOT NULL,
  scheduled_start_at      timestamptz,
  scheduled_end_at        timestamptz,
  tz_name                 text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  location_text           text,
  meeting_url             text,
  calendar_event_refs     jsonb NOT NULL DEFAULT '{}'::jsonb,  -- {"google": "...", "m365": "..."}
  scorecard_template_code text,
  scorecard_due_at        timestamptz,                       -- default end + 24 h (BR-REC-037)
  rescheduled_from_id     uuid,                              -- previous slot (history, BR-REC-035)
  status                  text NOT NULL DEFAULT 'PROPOSED',
  cancel_reason_code      text,
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT interview_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT interview_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES rec.application (tenant_id, id),
  CONSTRAINT interview_rescheduled_fk FOREIGN KEY (tenant_id, rescheduled_from_id) REFERENCES rec.interview (tenant_id, id),
  CONSTRAINT interview_type_ck CHECK (interview_type IN ('ONSITE','PHONE','VIDEO')),
  CONSTRAINT interview_round_ck CHECK (round_no >= 1),
  CONSTRAINT interview_slot_ck CHECK (scheduled_end_at IS NULL OR scheduled_start_at IS NULL OR scheduled_end_at > scheduled_start_at),
  CONSTRAINT interview_scheduled_ck CHECK (status IN ('PROPOSED','CANCELLED') OR scheduled_start_at IS NOT NULL),
  CONSTRAINT interview_status_ck CHECK (status IN ('PROPOSED','SCHEDULED','RESCHEDULED','COMPLETED','CANCELLED','NO_SHOW'))
);
CREATE INDEX interview_application_ix ON rec.interview (tenant_id, application_id, round_no);
CREATE INDEX interview_slot_ix ON rec.interview (tenant_id, scheduled_start_at) WHERE status = 'SCHEDULED';   -- reminders

CREATE TABLE rec.interview_panel (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  interview_id      uuid NOT NULL,
  user_account_id   uuid NOT NULL,                            -- -> core.user_account (soft, BR-REC-039)
  employment_id     uuid,                                     -- -> hr.employment (soft)
  panel_role        text NOT NULL DEFAULT 'INTERVIEWER',
  response_status   text NOT NULL DEFAULT 'PENDING',
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid,
  version           int NOT NULL DEFAULT 0,
  CONSTRAINT interview_panel_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT interview_panel_interview_fk FOREIGN KEY (tenant_id, interview_id) REFERENCES rec.interview (tenant_id, id),
  CONSTRAINT interview_panel_uq UNIQUE (tenant_id, interview_id, user_account_id),
  CONSTRAINT interview_panel_interview_id_uq UNIQUE (tenant_id, interview_id, id),   -- target for scorecard FK
  CONSTRAINT interview_panel_role_ck CHECK (panel_role IN ('LEAD','INTERVIEWER','OBSERVER')),
  CONSTRAINT interview_panel_response_ck CHECK (response_status IN ('PENDING','ACCEPTED','DECLINED','TENTATIVE'))
);
CREATE INDEX interview_panel_user_ix ON rec.interview_panel (tenant_id, user_account_id);   -- "my interviews"

CREATE TABLE rec.scorecard (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),
  interview_id            uuid NOT NULL,
  interview_panel_id      uuid NOT NULL,
  ratings                 jsonb NOT NULL DEFAULT '[]'::jsonb,  -- [{"criterion":"COMM","rating":4,"note":".."}]
  overall_recommendation  text,
  comment                 text,                              -- pii:P2 (opinion about the candidate)
  status                  text NOT NULL DEFAULT 'DRAFT',
  submitted_at            timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT scorecard_pk PRIMARY KEY (tenant_id, id),
  -- the panelist must belong to the same interview
  CONSTRAINT scorecard_panel_fk FOREIGN KEY (tenant_id, interview_id, interview_panel_id)
    REFERENCES rec.interview_panel (tenant_id, interview_id, id),
  CONSTRAINT scorecard_panel_uq UNIQUE (tenant_id, interview_panel_id),
  CONSTRAINT scorecard_ratings_ck CHECK (jsonb_typeof(ratings) = 'array'),
  CONSTRAINT scorecard_reco_ck CHECK (overall_recommendation IS NULL OR
                                      overall_recommendation IN ('STRONG_NO','NO','MIXED','YES','STRONG_YES')),
  CONSTRAINT scorecard_status_ck CHECK (status IN ('DRAFT','SUBMITTED')),
  CONSTRAINT scorecard_submitted_ck CHECK (status <> 'SUBMITTED' OR (submitted_at IS NOT NULL AND overall_recommendation IS NOT NULL))
);
CREATE INDEX scorecard_interview_ix ON rec.scorecard (tenant_id, interview_id);
COMMENT ON COLUMN rec.scorecard.comment IS 'pii:P2 opinion data about the candidate; nulled on candidate anonymisation';

-- =============================================================================
-- 10. ASSESSMENT  (P-REC-07, BR-REC-038)
-- =============================================================================
CREATE TABLE rec.assessment (
  tenant_id            uuid NOT NULL,
  id                   uuid NOT NULL DEFAULT sys.uuid_v7(),
  application_id       uuid NOT NULL,
  provider             text NOT NULL DEFAULT 'INTERNAL',      -- INTERNAL or connector code
  assessment_name      text NOT NULL,
  invite_url           text,
  external_ref         text,
  due_at               timestamptz,
  score                numeric(9,2),
  max_score            numeric(9,2),
  pass_mark            numeric(9,2),
  result               text NOT NULL DEFAULT 'PENDING',
  result_document_id   uuid,                                  -- -> core.document (soft)
  expires_on           date,                                  -- result validity (business date)
  status               text NOT NULL DEFAULT 'ASSIGNED',
  completed_at         timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int NOT NULL DEFAULT 0,
  CONSTRAINT assessment_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT assessment_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES rec.application (tenant_id, id),
  CONSTRAINT assessment_result_ck CHECK (result IN ('PENDING','PASS','FAIL','INCONCLUSIVE')),
  CONSTRAINT assessment_status_ck CHECK (status IN ('ASSIGNED','IN_PROGRESS','COMPLETED','EXPIRED','CANCELLED')),
  CONSTRAINT assessment_score_ck CHECK (score IS NULL OR max_score IS NULL OR score <= max_score),
  CONSTRAINT assessment_completed_ck CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);
CREATE INDEX assessment_application_ix ON rec.assessment (tenant_id, application_id);

-- =============================================================================
-- 11. OFFER  (versions; P-REC-08, BR-REC-040..047)
--     Review fix L7: simulation stamp = sim_legal_pack_id + sim_override_version
--     (DESIGN §6 rule 6), expiry = expires_on date (naming rule: *_at = timestamptz).
-- =============================================================================
CREATE TABLE rec.offer (
  tenant_id                     uuid NOT NULL,
  id                            uuid NOT NULL DEFAULT sys.uuid_v7(),
  application_id                uuid NOT NULL,
  offer_version                 int  NOT NULL DEFAULT 1,
  previous_offer_id             uuid,                          -- the version this one renegotiates
  superseded_by                 uuid,                          -- set on the old version when v+1 is created
  -- assignment terms (-> hand-off group "Assignment")
  legal_entity_id               uuid NOT NULL,                 -- soft core
  org_unit_id                   uuid NOT NULL,                 -- soft core
  position_id                   uuid,                          -- soft core
  job_title_id                  uuid,                          -- soft core
  grade_id                      uuid,                          -- soft core
  job_level_code                text,                          -- probation limit category (C-04)
  work_location_id              uuid,                          -- soft core.location (min-wage region, C-06)
  manager_employment_id         uuid,                          -- soft hr.employment
  employment_type               text NOT NULL DEFAULT 'FULL_TIME',
  fte                           numeric(5,4) NOT NULL DEFAULT 1.0,
  start_date                    date NOT NULL,
  contract_type_code            text NOT NULL,                 -- code list CONTRACT_TYPE
  contract_term_months          smallint,                      -- NULL = indefinite
  probation_days                smallint,
  probation_pct                 numeric(5,2),                  -- % of job salary during probation (>= legal min, C-05)
  -- compensation terms (-> hand-off group "Compensation"; People creates the salary record)
  salary_basis                  text NOT NULL DEFAULT 'GROSS',
  base_salary_amount            sys.money_amount NOT NULL,     -- pii:P2
  agreed_net_amount             sys.money_amount,              -- pii:P2 required for NET offers
  currency_code                 sys.currency_code NOT NULL DEFAULT 'VND',
  allowances                    jsonb NOT NULL DEFAULT '[]'::jsonb,  -- [{"component_code","amount","frequency"}] pii:P2
  bonus_terms                   jsonb NOT NULL DEFAULT '{}'::jsonb,  -- bonus target / 13th month
  benefits                      jsonb NOT NULL DEFAULT '[]'::jsonb,
  compa_ratio                   numeric(7,4),                  -- BR-REC-041
  is_out_of_band                boolean NOT NULL DEFAULT false,
  out_of_band_justification     text,
  -- Payroll gross<->net simulation result (C17, BR-REC-042), all pii:P2
  gross_estimate_amount         sys.money_amount,
  net_estimate_amount           sys.money_amount,
  ee_insurance_estimate_amount  sys.money_amount,              -- employee SI + HI + UI
  pit_estimate_amount           sys.money_amount,
  employer_cost_estimate_amount sys.money_amount,
  -- version stamp of the simulation (DESIGN §6 rule 6; review fix L7)
  sim_legal_pack_id             uuid,                          -- -> saas.legal_param_pack (soft)
  sim_override_version          int,                           -- saas.legal_param_override_set.override_version; 0 = none
  sim_effective_date            date,                          -- date the parameters were resolved for (= start_date normally)
  sim_engine_version            text,                          -- payroll engine release that answered C17
  simulated_at                  timestamptz,
  -- compliance flags
  is_work_permit_required       boolean NOT NULL DEFAULT false, -- BR-REC-054 / C-08
  is_minor_at_start             boolean NOT NULL DEFAULT false, -- C-07 warning recorded
  -- life cycle
  status                        text NOT NULL DEFAULT 'DRAFT',
  expires_on                    date,                          -- last day the candidate may accept (INCLUSIVE, local date)
  workflow_instance_id          uuid,                          -- soft core.workflow_instance (BR-REC-044)
  approved_by                   uuid,
  approved_at                   timestamptz,
  offer_letter_document_id      uuid,                          -- soft core.document
  signed_document_id            uuid,                          -- soft core.document
  esign_status                  text NOT NULL DEFAULT 'NONE',
  sent_at                       timestamptz,
  responded_at                  timestamptz,
  decline_reason_code           text,
  response_comment              text,                          -- pii:P2 candidate's message
  created_at                    timestamptz NOT NULL DEFAULT now(),
  created_by                    uuid,
  updated_at                    timestamptz NOT NULL DEFAULT now(),
  updated_by                    uuid,
  version                       int NOT NULL DEFAULT 0,
  CONSTRAINT offer_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT offer_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES rec.application (tenant_id, id),
  CONSTRAINT offer_previous_fk FOREIGN KEY (tenant_id, previous_offer_id) REFERENCES rec.offer (tenant_id, id),
  CONSTRAINT offer_superseded_by_fk FOREIGN KEY (tenant_id, superseded_by) REFERENCES rec.offer (tenant_id, id),
  CONSTRAINT offer_version_uq UNIQUE (tenant_id, application_id, offer_version),
  CONSTRAINT offer_application_id_uq UNIQUE (tenant_id, application_id, id),       -- target for hire_handoff FK
  CONSTRAINT offer_version_ck CHECK (offer_version >= 1 AND (offer_version = 1) = (previous_offer_id IS NULL)),
  CONSTRAINT offer_emp_type_ck CHECK (employment_type IN ('FULL_TIME','PART_TIME')),
  CONSTRAINT offer_fte_ck CHECK (fte > 0 AND fte <= 1),
  CONSTRAINT offer_basis_ck CHECK (salary_basis IN ('GROSS','NET')),
  CONSTRAINT offer_net_ck CHECK (salary_basis = 'GROSS' OR agreed_net_amount IS NOT NULL),
  CONSTRAINT offer_amounts_ck CHECK (base_salary_amount >= 0 AND coalesce(agreed_net_amount, 0) >= 0),
  CONSTRAINT offer_probation_ck CHECK ((probation_pct IS NULL OR probation_pct BETWEEN 0 AND 100)
                                       AND (probation_days IS NULL OR probation_days >= 0)),
  CONSTRAINT offer_term_ck CHECK (contract_term_months IS NULL OR contract_term_months > 0),
  CONSTRAINT offer_json_ck CHECK (jsonb_typeof(allowances) = 'array' AND jsonb_typeof(bonus_terms) = 'object'
                                  AND jsonb_typeof(benefits) = 'array'),
  CONSTRAINT offer_oob_ck CHECK (NOT is_out_of_band OR out_of_band_justification IS NOT NULL),
  -- stamp is all-or-nothing, and mandatory once the offer leaves DRAFT (BR-REC-042)
  CONSTRAINT offer_sim_stamp_ck CHECK ((sim_legal_pack_id IS NULL) = (sim_override_version IS NULL)
                                       AND (sim_legal_pack_id IS NULL) = (simulated_at IS NULL)),
  CONSTRAINT offer_sim_required_ck CHECK (status IN ('DRAFT','WITHDRAWN') OR sim_legal_pack_id IS NOT NULL),
  CONSTRAINT offer_sim_override_ck CHECK (sim_override_version IS NULL OR sim_override_version >= 0),
  CONSTRAINT offer_expiry_ck CHECK (status NOT IN ('SENT','ACCEPTED','DECLINED','EXPIRED') OR expires_on IS NOT NULL),
  CONSTRAINT offer_esign_ck CHECK (esign_status IN ('NONE','SENT','SIGNED','DECLINED','EXPIRED')),
  CONSTRAINT offer_status_ck CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','SENT','ACCEPTED',
                                               'DECLINED','CHANGE_REQUESTED','EXPIRED','WITHDRAWN','RENEGED',
                                               'SUPERSEDED')),
  CONSTRAINT offer_superseded_ck CHECK ((status = 'SUPERSEDED') = (superseded_by IS NOT NULL)),
  CONSTRAINT offer_declined_ck CHECK (status <> 'DECLINED' OR decline_reason_code IS NOT NULL)
);
-- at most one live offer version per application
CREATE UNIQUE INDEX offer_live_uq ON rec.offer (tenant_id, application_id)
  WHERE status IN ('DRAFT','PENDING_APPROVAL','APPROVED','SENT','ACCEPTED');
-- expiry job (daily at 00:00 Asia/Ho_Chi_Minh): SENT offers with expires_on < local today
CREATE INDEX offer_expiry_ix ON rec.offer (tenant_id, expires_on) WHERE status = 'SENT';
CREATE INDEX offer_scope_ix ON rec.offer (tenant_id, org_unit_id, status);
COMMENT ON TABLE rec.offer IS
  'Offer versions. Terms + simulation stamp frozen once status <> DRAFT (rec.tg_offer_guard). '
  'sim_legal_pack_id + sim_override_version replace sim_param_version (review fix L7, DESIGN §6 rule 6).';
COMMENT ON COLUMN rec.offer.base_salary_amount IS 'pii:P2 not encrypted (X-01); masked by app.mask_level = PII_AND_PAY';
COMMENT ON COLUMN rec.offer.agreed_net_amount  IS 'pii:P2 not encrypted (X-01); masked by app.mask_level = PII_AND_PAY';
COMMENT ON COLUMN rec.offer.allowances         IS 'pii:P2 amounts per pay component (by code)';
COMMENT ON COLUMN rec.offer.sim_legal_pack_id  IS '-> saas.legal_param_pack.id (soft). Pack used by the Payroll simulation (C17).';
COMMENT ON COLUMN rec.offer.sim_override_version IS 'Tenant legal override-set version used by the simulation; 0 = no override set active.';
COMMENT ON COLUMN rec.offer.expires_on IS 'Business date (local), INCLUSIVE: offer may be accepted until the end of this day.';

-- =============================================================================
-- 12. PRE-BOARDING ITEMS  (P-REC-09, BR-REC-048/049)
-- =============================================================================
CREATE TABLE rec.preboarding_item (
  tenant_id          uuid NOT NULL,
  id                 uuid NOT NULL DEFAULT sys.uuid_v7(),
  application_id     uuid NOT NULL,
  item_type_code     text NOT NULL,                           -- CCCD | TAX_CODE | SI_NUMBER | BANK_ACCOUNT | DEGREE | HEALTH_CERT ...
  is_mandatory       boolean NOT NULL DEFAULT true,
  is_sensitive       boolean NOT NULL DEFAULT false,          -- health / judicial record (C-10, C-13)
  value_enc          bytea,                                   -- pii:P3 structured value (JSON), candidate subject DEK
  value_last4        text,
  document_id        uuid,                                    -- -> core.document (soft); scans only (C-02)
  status             text NOT NULL DEFAULT 'PENDING',
  rejection_reason   text,
  verified_by        uuid,
  verified_at        timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid,
  version            int NOT NULL DEFAULT 0,
  CONSTRAINT preboarding_item_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT preboarding_item_application_fk FOREIGN KEY (tenant_id, application_id) REFERENCES rec.application (tenant_id, id),
  CONSTRAINT preboarding_item_uq UNIQUE (tenant_id, application_id, item_type_code),
  CONSTRAINT preboarding_item_status_ck CHECK (status IN ('PENDING','SUBMITTED','VERIFIED','REJECTED','WAIVED')),
  CONSTRAINT preboarding_item_reject_ck CHECK (status <> 'REJECTED' OR rejection_reason IS NOT NULL),
  CONSTRAINT preboarding_item_verified_ck CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
  CONSTRAINT preboarding_item_last4_ck CHECK (value_last4 IS NULL OR length(value_last4) <= 4)
);
COMMENT ON COLUMN rec.preboarding_item.value_enc IS
  'pii:P3 AES-256-GCM, candidate subject DEK (CCCD, MST, SI number, bank account ...). Never in the rec.CandidateHired payload (D7): People pulls it from the Recruitment API by hire_id and re-encrypts it under the PERSON DEK.';

-- =============================================================================
-- 13. HIRE HAND-OFF  (P-REC-10, BR-REC-050..054; rec.CandidateHired payload v1, BRD §7.3)
--     id = hire_id = idempotency key of the hand-off. People is the ONLY receiver
--     (R2-02 §5 #24). The event is written to rec.outbox_event in the same
--     transaction; People answers with people.HireHandoffProcessed (C15).
--     D7 (2026-10-21): the bus payload is MINIMISED (DESIGN §9.1). rec.CandidateHired
--     carries NO P3 values - only keys (hire_id, candidate_id, application_id,
--     offer_id + offer_version, requisition_id, matched_person_id) and non-P3 groups
--     (names, offer terms, org placement, start date). People pulls the P3 groups
--     (identity document / CCCD, tax code, SI number, bank account, candidate contact
--     data, i.e. rec.candidate *_enc and rec.preboarding_item.value_enc) from the
--     Recruitment hand-off API by hire_id, under its own service identity, and
--     re-encrypts them under the PERSON DEK. Outbox, bus and inbox never hold P3.
--     (BRD 05 §7.3 lists CCCD / MST / bank account in the payload: superseded by D7.)
-- =============================================================================
CREATE TABLE rec.hire_handoff (
  tenant_id               uuid NOT NULL,
  id                      uuid NOT NULL DEFAULT sys.uuid_v7(),   -- hire_id
  handoff_type            text NOT NULL DEFAULT 'NEW_HIRE',
  candidate_id            uuid NOT NULL,
  application_id          uuid NOT NULL,
  offer_id                uuid NOT NULL,
  offer_version           int  NOT NULL,                     -- denormalised into the payload keys
  requisition_id          uuid NOT NULL,
  matched_person_id       uuid,                              -- -> hr.person (soft): rehire / internal match (C14)
  payload_version         smallint NOT NULL DEFAULT 1,       -- CandidateHired payload v1 (frozen, BRD RK-03)
  last_event_type         text NOT NULL DEFAULT 'rec.CandidateHired',
  last_outbox_event_id    uuid,                              -- -> rec.outbox_event.id (partitioned; no FK)
  payload_sha256          text,                              -- of the canonical payload last sent
  amend_seq               int  NOT NULL DEFAULT 0,           -- rec.HireAmended count after the first send
  sent_at                 timestamptz,
  people_status           text NOT NULL DEFAULT 'PENDING',
  people_status_at        timestamptz,
  people_error_code       text,
  people_error_message    text,
  person_id               uuid,                              -- -> hr.person (soft), from HireAccepted
  employment_id           uuid,                              -- -> hr.employment (soft)
  assignment_id           uuid,                              -- -> hr.assignment (soft)
  contract_id             uuid,                              -- -> hr.contract (soft), draft contract
  is_work_permit_required boolean NOT NULL DEFAULT false,    -- BR-REC-054
  work_permit_status_code text,
  hr_override_reason      text,                              -- forced hire (pre-boarding not ready / no permit)
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid,
  version                 int NOT NULL DEFAULT 0,
  CONSTRAINT hire_handoff_pk PRIMARY KEY (tenant_id, id),
  -- the application belongs to the candidate, the offer belongs to the application
  CONSTRAINT hire_handoff_application_fk FOREIGN KEY (tenant_id, candidate_id, application_id)
    REFERENCES rec.application (tenant_id, candidate_id, id),
  CONSTRAINT hire_handoff_offer_fk FOREIGN KEY (tenant_id, application_id, offer_id)
    REFERENCES rec.offer (tenant_id, application_id, id),
  CONSTRAINT hire_handoff_requisition_fk FOREIGN KEY (tenant_id, requisition_id) REFERENCES rec.requisition (tenant_id, id),
  CONSTRAINT hire_handoff_type_ck CHECK (handoff_type IN ('NEW_HIRE','INTERNAL_TRANSFER')),
  CONSTRAINT hire_handoff_payload_ck CHECK (payload_version IN (1)),
  CONSTRAINT hire_handoff_event_ck CHECK (last_event_type IN ('rec.CandidateHired','rec.InternalTransferAccepted',
                                                              'rec.HireAmended','rec.HireWithdrawn')),
  CONSTRAINT hire_handoff_people_status_ck CHECK (people_status IN ('PENDING','ACCEPTED','FAILED','CANCELLED','WITHDRAWN')),
  CONSTRAINT hire_handoff_failed_ck CHECK (people_status <> 'FAILED' OR people_error_code IS NOT NULL),
  CONSTRAINT hire_handoff_accepted_ck CHECK (people_status <> 'ACCEPTED' OR (person_id IS NOT NULL AND employment_id IS NOT NULL)),
  CONSTRAINT hire_handoff_sent_ck CHECK (sent_at IS NULL OR last_outbox_event_id IS NOT NULL),
  CONSTRAINT hire_handoff_permit_ck CHECK (NOT is_work_permit_required OR work_permit_status_code IS NOT NULL
                                           OR hr_override_reason IS NOT NULL OR sent_at IS NULL)
);
-- one live hand-off per application, and a candidate is never hired twice for the same
-- requisition (BR-REC-051)
CREATE UNIQUE INDEX hire_handoff_application_live_uq ON rec.hire_handoff (tenant_id, application_id)
  WHERE people_status IN ('PENDING','ACCEPTED');
CREATE UNIQUE INDEX hire_handoff_requisition_live_uq ON rec.hire_handoff (tenant_id, requisition_id, candidate_id)
  WHERE people_status IN ('PENDING','ACCEPTED');
CREATE INDEX hire_handoff_pending_ix ON rec.hire_handoff (tenant_id, people_status, sent_at)
  WHERE people_status IN ('PENDING','FAILED');                                      -- retry dashboard (NFR-REC-005)
CREATE INDEX hire_handoff_employment_ix ON rec.hire_handoff (tenant_id, employment_id) WHERE employment_id IS NOT NULL;
COMMENT ON TABLE rec.hire_handoff IS
  'Tracking of rec.CandidateHired payload v1 (id = hire_id, idempotency key). People is the only receiver; '
  'people_status follows people.HireHandoffProcessed / HireCancelled / StartDateChanged (C15). '
  'D7: the payload carries no P3 values; People pulls the P3 groups from the Recruitment hand-off API by hire_id.';

-- =============================================================================
-- 14. MASKING VIEWS  (DESIGN §9.4).  app.mask_level: NONE | PII | PII_AND_PAY
-- =============================================================================
CREATE OR REPLACE VIEW rec.v_candidate_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT c.tenant_id, c.id, c.full_name, c.gender_code, c.nationality_code,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII') = 'NONE'
            THEN c.birth_date
            ELSE make_date(extract(year FROM c.birth_date)::int, 1, 1) END       AS birth_date,
       '••••' || coalesce(c.phone_last4, '')                                   AS phone_masked,
       c.id_doc_type,
       '••••' || coalesce(c.id_doc_last4, '')                                  AS id_doc_masked,
       c.skills, c.source_channel, c.status, c.retention_until, c.legal_hold_until
  FROM rec.candidate c;

-- Fail closed for pay: an unset app.mask_level hides amounts (same rule as hr).
CREATE OR REPLACE VIEW rec.v_offer_masked WITH (security_invoker = true, security_barrier = true) AS
SELECT o.tenant_id, o.id, o.application_id, o.offer_version, o.legal_entity_id, o.org_unit_id, o.position_id,
       o.grade_id, o.work_location_id, o.start_date, o.contract_type_code, o.probation_days, o.salary_basis,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN o.base_salary_amount END                                      AS base_salary_amount,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN o.agreed_net_amount END                                       AS agreed_net_amount,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN o.gross_estimate_amount END                                   AS gross_estimate_amount,
       CASE WHEN coalesce(current_setting('app.mask_level', true), 'PII_AND_PAY') IN ('NONE','PII')
            THEN o.employer_cost_estimate_amount END                           AS employer_cost_estimate_amount,
       o.currency_code, o.is_out_of_band, o.sim_legal_pack_id, o.sim_override_version,
       o.status, o.expires_on
  FROM rec.offer o;

-- =============================================================================
-- 15. RLS, triggers, grants  (yhrm_app_rec only, DESIGN §2.3)
-- =============================================================================
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['rec.staffing_plan','rec.staffing_plan_line','rec.requisition','rec.pipeline_template',
                           'rec.job_opening','rec.pipeline_stage','rec.job_posting','rec.candidate',
                           'rec.candidate_consent_ref','rec.candidate_file','rec.referral_program','rec.referral',
                           'rec.application','rec.stage_history','rec.interview','rec.interview_panel',
                           'rec.scorecard','rec.assessment','rec.offer','rec.preboarding_item','rec.hire_handoff']
  LOOP
    CALL sys.enable_tenant_rls(t::regclass);
    CALL audit.attach_row_audit(t::regclass);                -- BR-REC-063 (writes); reads audited by the service
  END LOOP;

  -- mutable tables (stage_history is append-only)
  FOREACH t IN ARRAY ARRAY['rec.staffing_plan','rec.staffing_plan_line','rec.requisition','rec.pipeline_template',
                           'rec.job_opening','rec.pipeline_stage','rec.job_posting','rec.candidate',
                           'rec.candidate_consent_ref','rec.candidate_file','rec.referral_program','rec.referral',
                           'rec.application','rec.interview','rec.interview_panel','rec.scorecard',
                           'rec.assessment','rec.offer','rec.preboarding_item','rec.hire_handoff']
  LOOP
    CALL sys.attach_touch_trigger(t::regclass);
  END LOOP;
END $$;

CREATE TRIGGER trg_plan_guard BEFORE UPDATE OR DELETE ON rec.staffing_plan
  FOR EACH ROW EXECUTE FUNCTION rec.tg_staffing_plan_guard();
CREATE TRIGGER trg_plan_line_guard BEFORE INSERT OR UPDATE OR DELETE ON rec.staffing_plan_line
  FOR EACH ROW EXECUTE FUNCTION rec.tg_staffing_plan_line_guard();
CREATE TRIGGER trg_offer_guard BEFORE UPDATE OR DELETE ON rec.offer
  FOR EACH ROW EXECUTE FUNCTION rec.tg_offer_guard();

GRANT SELECT, INSERT, UPDATE ON
  rec.staffing_plan, rec.staffing_plan_line, rec.requisition, rec.pipeline_template, rec.job_opening,
  rec.pipeline_stage, rec.job_posting, rec.candidate, rec.candidate_consent_ref, rec.candidate_file,
  rec.referral_program, rec.referral, rec.application, rec.interview, rec.interview_panel, rec.scorecard,
  rec.assessment, rec.offer, rec.preboarding_item, rec.hire_handoff
TO yhrm_app_rec;
GRANT SELECT, INSERT ON rec.stage_history TO yhrm_app_rec;
-- DELETE only where DRAFT / technical rows may be discarded (guards + service layer)
GRANT DELETE ON rec.staffing_plan, rec.staffing_plan_line, rec.job_posting, rec.pipeline_stage,
  rec.interview_panel, rec.candidate_file, rec.preboarding_item, rec.offer TO yhrm_app_rec;
-- D6 (2026-10-21): masking views go to the Recruitment runtime only, never to
-- yhrm_reporting_ro. Reporting goes through the reporting store (DESIGN §11:
-- pub_rpt_rec in 007_publications.sql, no candidate PII), not OLTP views.
GRANT SELECT ON rec.v_candidate_masked, rec.v_offer_masked TO yhrm_app_rec;

-- Outbox / inbox for Recruitment (rec.CandidateHired, rec.HireAmended, rec.RequisitionApproved, ...).
CALL sys.create_outbox_inbox('rec', 'yhrm_app_rec');

RESET ROLE;
