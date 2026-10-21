-- =============================================================================
-- YourHRM  ·  001_saas.sql   (owner module: SaaS platform)
-- Tenants, plans, subscriptions, entitlements, invoices, support grants,
-- keyring (tenant KEKs, per-subject DEKs), privacy registries and the
-- Legal Parameter Pack store.  DESIGN.md §3, §6, §7.1, §9.3; ADR-0020/0025/0026/0036.
--
-- Table classes in this schema (DESIGN §3.5):
--   [P]  platform-only : no tenant_id or not exposed to tenant app roles
--   [G]  pure global   : read by every module app role, written by release job
--   [T]  tenant-owned  : tenant_id + RLS (tenant_isolation policy)
-- Placement classes (DESIGN §3.1, ADR-0036). The DDL is identical in every database;
-- what differs is where a table is AUTHORITATIVE:
--   PLATFORM   : written only in the platform cluster (tenant, plans, billing + usage metering, packs, keyring,
--                privacy registries, support grants + operator access log)
--   REPLICATED : read-only logical-replication copy in pool/dedicated clusters
--                (tenant, tenant_placement, entitlement, baseline_capability, legal_param_*, retention_policy)
--   LOCAL      : cluster-local, never replicated (job queue, outbox/inbox)
--
-- v0.3 (2026-10-16, decision-log R2-01/R2-18): saas.job gets ordering_key and the
-- 'events' queue (Phase-1 DB-backed event bus, SA ADR-0004 rev. 2: the relay fans
-- each outbox event out as one delivery job per consumer; Kafka is Phase 2);
-- saas.enqueue_event_delivery(); claim_jobs() respects ordering keys;
-- entitlement source BASELINE + saas.baseline_capability (always-on capabilities).
-- NOT tested: no PostgreSQL available in this round; reviewed by read-through only.
--
-- v0.4 (2026-10-17, DDL review round):
--   * M3  legal_param_override: LOCATION scope (location_id soft ref, resolution
--         GLOBAL -> TENANT -> LEGAL_ENTITY -> LOCATION), period CHECK, exclusion now keyed
--         on (set, key, LE, location, period); trigger checks the definition's
--         override_rule / most-specific override_scope.
--   * usage_record  (monthly partitioned metering, BR-SAAS-012/013, D-10): metric x
--         module x legal entity x day/month, frozen flag for invoiced months.
--   * operator_access_log  (tenant-visible, append-only, BR-SAAS-037, R3-08 P1).
--   * Identity: confirmed NO credential / password / MFA / session tables in this
--         schema - Keycloak owns them (SA ADR-0005). DESIGN §7.1 mentions of
--         user_credential / mfa_factor are superseded by ADR-0005.
--   * X-02 allow-list unchanged: no new cross-schema grants.
--   Smoke-tested 000->001->002 on PGlite 0.5.10 (PostgreSQL 18.6 in WASM), see 002 header.
-- =============================================================================

\set ON_ERROR_STOP on
SET ROLE yhrm_owner;
SET search_path = saas, sys, public;

-- -----------------------------------------------------------------------------
-- IDENTITY: deliberately NO tables here for credentials, password hashes/history,
-- MFA factors (TOTP secrets, e-mail OTP), sessions, refresh tokens or IdP client
-- secrets. Keycloak is the identity provider and owns all of them in its own
-- database (SA ADR-0005; BR-SAAS-038/039/043). YourHRM keeps only the link
-- core.user_account.identity_id (Keycloak user id, soft) and authorisation data in
-- `core`. Per-tenant SSO configuration (identity_provider_config, BR-SAAS-041) and
-- public-API clients (api_client, BR-SAAS-056) are Phase 2 and will hold references
-- to Keycloak objects only, never secrets.
-- -----------------------------------------------------------------------------

GRANT USAGE ON SCHEMA saas TO yhrm_app, yhrm_platform;   -- modules: own tenant row + job enqueue only

-- -----------------------------------------------------------------------------
-- Tenant registry  [P, with a self-row read policy for app roles]
-- -----------------------------------------------------------------------------
CREATE TABLE saas.tenant (
  id                   uuid        NOT NULL DEFAULT sys.uuid_v7(),
  slug                 text        NOT NULL,                 -- <slug>.yourhrm.vn
  name                 text        NOT NULL,
  state                text        NOT NULL DEFAULT 'PROVISIONING',
  tier                 text        NOT NULL DEFAULT 'SHARED',
  region               text        NOT NULL DEFAULT 'VN',
  default_locale       text        NOT NULL DEFAULT 'vi-VN',
  time_zone            text        NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  is_sandbox           boolean     NOT NULL DEFAULT false,
  source_tenant_id     uuid,                                 -- sandbox copied from
  activated_at         timestamptz,
  terminated_at        timestamptz,
  purge_due_at         timestamptz,                          -- terminated_at + retention (90 d default)
  purged_at            timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid,
  version              int         NOT NULL DEFAULT 0,
  CONSTRAINT tenant_pk PRIMARY KEY (id),
  CONSTRAINT tenant_slug_uq UNIQUE (slug),
  CONSTRAINT tenant_slug_ck CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,40}[a-z0-9]$'),
  CONSTRAINT tenant_state_ck CHECK (state IN ('PROVISIONING','TRIAL','ACTIVE','SUSPENDED',
                                              'TERMINATING','TERMINATED','PURGED')),
  CONSTRAINT tenant_tier_ck CHECK (tier IN ('SHARED','DEDICATED')),
  CONSTRAINT tenant_region_ck CHECK (region = 'VN'),          -- data residency (BRD D4)
  CONSTRAINT tenant_not_global_ck CHECK (id <> sys.global_tenant_id())
);
COMMENT ON TABLE  saas.tenant IS '[P] Master tenant registry (control plane). Dedicated DBs hold a replica of their own row.';
COMMENT ON COLUMN saas.tenant.tier IS 'SHARED = pool cluster; DEDICATED = own database/cluster. Routing is saas.tenant_placement.';

-- The tenant's own row is readable by tenant app roles (locale, tz, state checks).
-- X-02 allow-listed read-only exception (see sys.cross_schema_grant_violations(), 002_core.sql).
ALTER TABLE saas.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE saas.tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_self_read ON saas.tenant FOR SELECT TO yhrm_app
  USING (id = (SELECT sys.current_tenant_id()));
CREATE POLICY platform_all ON saas.tenant FOR ALL TO yhrm_platform USING (true) WITH CHECK (true);
CREATE TRIGGER trg_touch BEFORE UPDATE ON saas.tenant
  FOR EACH ROW EXECUTE FUNCTION sys.tg_touch_platform();             -- no tenant_id column here

-- -----------------------------------------------------------------------------
-- Tenant placement  [P, REPLICATED]  routing source (SA ADR-0003): tenant -> cluster.
-- History is kept so moves between clusters are traceable.
-- -----------------------------------------------------------------------------
CREATE TABLE saas.tenant_placement (
  tenant_id         uuid        NOT NULL REFERENCES saas.tenant (id),
  id                uuid        NOT NULL DEFAULT sys.uuid_v7(),
  cluster_id        text        NOT NULL,                     -- e.g. pool-hcm-01, ded-acme
  database_name     text        NOT NULL DEFAULT 'yourhrm',
  tier              text        NOT NULL,
  read_replica_ids  text[]      NOT NULL DEFAULT '{}',
  state             text        NOT NULL DEFAULT 'ACTIVE',
  placed_at         timestamptz NOT NULL DEFAULT now(),
  retired_at        timestamptz,
  CONSTRAINT tenant_placement_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT tenant_placement_tier_ck CHECK (tier IN ('SHARED','DEDICATED')),
  CONSTRAINT tenant_placement_state_ck CHECK (state IN ('PROVISIONING','ACTIVE','MOVING','RETIRED'))
);
CREATE UNIQUE INDEX tenant_placement_current_ix ON saas.tenant_placement (tenant_id) WHERE state IN ('PROVISIONING','ACTIVE','MOVING');
ALTER TABLE saas.tenant_placement ENABLE ROW LEVEL SECURITY;
ALTER TABLE saas.tenant_placement FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_all ON saas.tenant_placement FOR ALL TO yhrm_platform USING (true) WITH CHECK (true);
-- The router reads placements of ALL tenants before any tenant context exists.
CREATE POLICY router_read ON saas.tenant_placement FOR SELECT TO yhrm_app_saas USING (true);

-- -----------------------------------------------------------------------------
-- Job queue  [LOCAL, per cluster]  (SA ADR-0011; placement decided here: per cluster,
-- so a job is enqueued in the same transaction as the business change)
-- -----------------------------------------------------------------------------
CREATE TABLE saas.job (
  tenant_id        uuid        NOT NULL,
  id               uuid        NOT NULL DEFAULT sys.uuid_v7(),
  queue            text        NOT NULL,                      -- payroll | general | integration | reporting | events
  job_type         text        NOT NULL,                      -- e.g. pay.CalculateChunk, bus.Deliver
  priority         smallint    NOT NULL DEFAULT 5,            -- lane: 1 interactive .. 9 batch
  payload          jsonb       NOT NULL,
  payload_version  int         NOT NULL DEFAULT 1,
  state            text        NOT NULL DEFAULT 'READY',
  attempts         int         NOT NULL DEFAULT 0,
  not_before       timestamptz NOT NULL DEFAULT now(),
  idempotency_key  text,
  ordering_key     text,                                      -- FIFO lane: at most one non-terminal job per key runs
                                                              -- (bus deliveries: consumer || ':' || aggregate_id)
  heartbeat_at     timestamptz,
  worker_id        text,
  progress         jsonb,
  last_error       text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  finished_at      timestamptz,
  CONSTRAINT job_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT job_state_ck CHECK (state IN ('READY','RUNNING','SUCCEEDED','FAILED','DEAD','CANCELLED','PAUSED'))
);
CREATE INDEX job_ready_ix ON saas.job (queue, priority, not_before) WHERE state = 'READY';
CREATE INDEX job_running_ix ON saas.job (queue, tenant_id) WHERE state = 'RUNNING';   -- fair-share counts
CREATE UNIQUE INDEX job_idempotency_uq ON saas.job (tenant_id, job_type, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- Predecessor lookup for ordered lanes. Non-terminal states block the lane;
-- SUCCEEDED / DEAD / CANCELLED release it (a DEAD delivery = the "DLT" of Phase 1).
CREATE INDEX job_ordering_ix ON saas.job (tenant_id, ordering_key, id)
  WHERE ordering_key IS NOT NULL AND state IN ('READY','RUNNING','FAILED','PAUSED');
ALTER TABLE saas.job SET (fillfactor = 80);
-- Tenants enqueue/see their own jobs (RLS). Workers claim ACROSS tenants only through
-- this SECURITY DEFINER function (owner bypasses RLS), which applies fair share
-- (fewest running jobs first, per-tenant cap). The handler then sets the tenant context.
CALL sys.enable_tenant_rls('saas.job');
CREATE OR REPLACE FUNCTION saas.claim_jobs(p_queue text, p_worker text, p_limit int DEFAULT 1, p_tenant_cap int DEFAULT 2)
RETURNS SETOF saas.job
LANGUAGE sql SECURITY DEFINER SET search_path = saas, pg_temp
AS $$
  WITH running AS (
    SELECT tenant_id, count(*) AS n FROM saas.job
     WHERE queue = p_queue AND state = 'RUNNING' GROUP BY tenant_id),
  ranked AS (                                   -- rank READY jobs inside each tenant
    SELECT j.tenant_id, j.id, j.priority,
           coalesce(r.n, 0) AS running_n,
           row_number() OVER (PARTITION BY j.tenant_id ORDER BY j.priority, j.id) AS rn
      FROM saas.job j LEFT JOIN running r ON r.tenant_id = j.tenant_id
     WHERE j.queue = p_queue AND j.state = 'READY' AND j.not_before <= now()
       AND (j.ordering_key IS NULL OR NOT EXISTS (     -- ordered lane: an older non-terminal job goes first
             SELECT 1 FROM saas.job o
              WHERE o.tenant_id = j.tenant_id AND o.ordering_key = j.ordering_key
                AND o.id < j.id AND o.state IN ('READY','RUNNING','FAILED','PAUSED')))),
  eligible AS (                                 -- respect the per-tenant cap within this batch too
    SELECT tenant_id, id FROM ranked
     WHERE running_n + rn <= p_tenant_cap
     ORDER BY running_n + rn, priority, id      -- fair share: least-served tenants first
     LIMIT p_limit * 4),
  locked AS (
    SELECT j.tenant_id, j.id
      FROM saas.job j JOIN eligible e ON e.tenant_id = j.tenant_id AND e.id = j.id
     WHERE j.state = 'READY'
     LIMIT p_limit
     FOR UPDATE OF j SKIP LOCKED)
  UPDATE saas.job j
     SET state = 'RUNNING', attempts = j.attempts + 1, worker_id = p_worker, heartbeat_at = now()
    FROM locked c
   WHERE j.tenant_id = c.tenant_id AND j.id = c.id
  RETURNING j.*;
$$;
REVOKE EXECUTE ON FUNCTION saas.claim_jobs(text, text, int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION saas.claim_jobs(text, text, int, int) TO yhrm_app_saas;
GRANT SELECT, INSERT ON saas.job TO yhrm_app;
GRANT UPDATE (state, attempts, not_before, heartbeat_at, progress, last_error, finished_at) ON saas.job TO yhrm_app_saas;

-- Phase-1 event bus (SA ADR-0004 rev. 2, DESIGN §9.1): the outbox relay (yhrm_relay)
-- turns one outbox row into one delivery job per subscribed consumer, in the same
-- transaction in which it sets outbox_event.published_at. The job payload is a
-- POINTER (source schema, event id, created_at) and never the business payload, so
-- salary amounts in e.g. people.SalaryChanged do not land in saas.job, which every
-- module role of the tenant can read (X-01 controls stay intact). The delivery
-- worker re-reads the event through the relay connection and calls the consumer
-- handler in the consumer module's own transaction (inbox dedupe, ADR-0028).
-- Phase 2 swaps this function for a Kafka publish; consumer code does not change.
CREATE OR REPLACE FUNCTION saas.enqueue_event_delivery(
    p_tenant_id uuid, p_source_cluster text, p_source_schema text, p_event_id uuid,
    p_event_created_at timestamptz, p_event_type text, p_aggregate_id uuid, p_consumers text[])
RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = saas, pg_temp
AS $$
  WITH ins AS (
    INSERT INTO saas.job (tenant_id, queue, job_type, priority, payload, idempotency_key, ordering_key)
    SELECT p_tenant_id, 'events', 'bus.Deliver', 3,
           jsonb_build_object('source_cluster', p_source_cluster, 'source_schema', p_source_schema,
                              'event_id', p_event_id, 'event_created_at', p_event_created_at,
                              'event_type', p_event_type, 'consumer', c),
           c || ':' || p_event_id::text,
           c || ':' || p_aggregate_id::text
      FROM unnest(p_consumers) AS c
    ON CONFLICT (tenant_id, job_type, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
    RETURNING 1)
  SELECT count(*)::int FROM ins;
$$;
REVOKE EXECUTE ON FUNCTION saas.enqueue_event_delivery(uuid, text, text, uuid, timestamptz, text, uuid, text[]) FROM PUBLIC;
GRANT USAGE ON SCHEMA saas TO yhrm_relay;
GRANT EXECUTE ON FUNCTION saas.enqueue_event_delivery(uuid, text, text, uuid, timestamptz, text, uuid, text[]) TO yhrm_relay;

-- -----------------------------------------------------------------------------
-- Keyring  (DESIGN §9.3, ADR-0026).  Key MATERIAL never lives here in clear:
-- tenant KEKs stay in the KMS/HSM; subject DEKs are stored wrapped by the KEK.
-- -----------------------------------------------------------------------------
CREATE TABLE saas.tenant_key (
  tenant_id       uuid        NOT NULL,
  id              uuid        NOT NULL DEFAULT sys.uuid_v7(),
  key_purpose     text        NOT NULL,                       -- DEK_WRAP | BLIND_INDEX | EXPORT
  key_version     int         NOT NULL,
  kms_key_ref     text        NOT NULL,                       -- KMS key id/ARN (VN region)
  status          text        NOT NULL DEFAULT 'ACTIVE',
  activated_at    timestamptz NOT NULL DEFAULT now(),
  retired_at      timestamptz,
  destroy_scheduled_at timestamptz,                           -- crypto-shredding of the whole tenant
  destroyed_at    timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid,
  CONSTRAINT tenant_key_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT tenant_key_uq UNIQUE (tenant_id, key_purpose, key_version),
  CONSTRAINT tenant_key_purpose_ck CHECK (key_purpose IN ('DEK_WRAP','BLIND_INDEX','EXPORT')),
  CONSTRAINT tenant_key_status_ck CHECK (status IN ('ACTIVE','RETIRED','DESTROY_SCHEDULED','DESTROYED'))
);
CREATE UNIQUE INDEX tenant_key_one_active_ix ON saas.tenant_key (tenant_id, key_purpose) WHERE status = 'ACTIVE';
COMMENT ON TABLE saas.tenant_key IS '[T] References to per-tenant KEKs in KMS. Destroying the ACTIVE+RETIRED DEK_WRAP keys crypto-shreds the tenant.';

CREATE TABLE saas.subject_key (
  tenant_id       uuid        NOT NULL,
  id              uuid        NOT NULL DEFAULT sys.uuid_v7(),
  subject_type    text        NOT NULL,                       -- PERSON | CANDIDATE | CASUAL_PAYEE | FAMILY_MEMBER
  subject_id      uuid        NOT NULL,                       -- soft ref: hr.person / rec.candidate / pay.casual_payee
  key_version     int         NOT NULL DEFAULT 1,
  wrapping_key_version int    NOT NULL,                       -- tenant_key.key_version (DEK_WRAP) used
  wrapped_dek     bytea,                                      -- NULL after crypto-shredding
  status          text        NOT NULL DEFAULT 'ACTIVE',
  destroyed_at    timestamptz,
  destroyed_reason text,                                      -- DSR id / retention policy id
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT subject_key_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT subject_key_uq UNIQUE (tenant_id, subject_type, subject_id, key_version),
  CONSTRAINT subject_key_type_ck CHECK (subject_type IN ('PERSON','CANDIDATE','CASUAL_PAYEE','FAMILY_MEMBER')),
  CONSTRAINT subject_key_status_ck CHECK (status IN ('ACTIVE','ROTATED','DESTROYED')),
  CONSTRAINT subject_key_shred_ck CHECK ((status = 'DESTROYED') = (wrapped_dek IS NULL))
);
COMMENT ON TABLE saas.subject_key IS '[T] Per-data-subject DEKs wrapped by the tenant KEK. Shredding = wrapped_dek := NULL.';

-- -----------------------------------------------------------------------------
-- Commercial: plans, subscriptions, entitlements  (BR-SAAS-008)
-- -----------------------------------------------------------------------------
CREATE TABLE saas.plan (
  id          uuid NOT NULL DEFAULT sys.uuid_v7(),
  code        text NOT NULL,                                  -- STARTER | PROFESSIONAL | ENTERPRISE
  name_vi     text NOT NULL,
  name_en     text NOT NULL,
  is_active   boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT plan_pk PRIMARY KEY (id),
  CONSTRAINT plan_code_uq UNIQUE (code)
);
COMMENT ON TABLE saas.plan IS '[P] Commercial plan/edition.';

CREATE TABLE saas.plan_version (
  id           uuid NOT NULL DEFAULT sys.uuid_v7(),
  plan_id      uuid NOT NULL REFERENCES saas.plan (id),
  plan_version int  NOT NULL,
  modules      jsonb NOT NULL,                                -- ["core","hr","att","pay"]
  features     jsonb NOT NULL DEFAULT '{}'::jsonb,            -- feature_key -> bool
  limits       jsonb NOT NULL DEFAULT '{}'::jsonb,            -- e.g. {"legal_entities":5,"api_rpm":600}
  valid_from   date NOT NULL,
  valid_to     date,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT plan_version_pk PRIMARY KEY (id),
  CONSTRAINT plan_version_uq UNIQUE (plan_id, plan_version)
);
COMMENT ON TABLE saas.plan_version IS '[P] Immutable plan version; subscriptions point to a version.';

CREATE TABLE saas.subscription (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  plan_version_id  uuid NOT NULL,                             -- -> saas.plan_version (platform table, same schema)
  billing_cycle    text NOT NULL,
  start_date       date NOT NULL,
  end_date         date,
  committed_seats  int,
  auto_renew       boolean NOT NULL DEFAULT true,
  status           text NOT NULL DEFAULT 'ACTIVE',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid,
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT subscription_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT subscription_plan_version_fk FOREIGN KEY (plan_version_id) REFERENCES saas.plan_version (id),
  CONSTRAINT subscription_cycle_ck CHECK (billing_cycle IN ('MONTHLY','QUARTERLY','ANNUAL')),
  CONSTRAINT subscription_status_ck CHECK (status IN ('TRIAL','ACTIVE','PAST_DUE','CANCELLED','EXPIRED'))
);

CREATE TABLE saas.subscription_item (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  subscription_id  uuid NOT NULL,
  item_code        text NOT NULL,                             -- module or add-on code
  quantity_basis   text NOT NULL,                             -- PEPM | FLAT | USAGE
  start_date       date NOT NULL,
  end_date         date,
  price_override_amount sys.money_amount,
  currency_code    sys.currency_code NOT NULL DEFAULT 'VND',
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT subscription_item_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT subscription_item_subscription_fk FOREIGN KEY (tenant_id, subscription_id)
    REFERENCES saas.subscription (tenant_id, id),
  CONSTRAINT subscription_item_basis_ck CHECK (quantity_basis IN ('PEPM','FLAT','USAGE'))
);
CREATE INDEX subscription_item_subscription_ix ON saas.subscription_item (tenant_id, subscription_id);

-- Resolved feature availability; read by every module via the entitlement API/cache.
CREATE TABLE saas.entitlement (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  legal_entity_id  uuid,                                      -- -> core.legal_entity (soft); NULL = whole tenant
  feature_key      text NOT NULL,                             -- module.pay | feature.pay.net_to_gross | limit.employees
  is_enabled       boolean NOT NULL,
  limit_value      numeric,
  source           text NOT NULL,
  valid_from       date NOT NULL,
  valid_to         date,
  valid_during     daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  CONSTRAINT entitlement_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT entitlement_source_ck CHECK (source IN ('BASELINE','PLAN','ADDON','OVERRIDE','TRIAL')),
  -- R2-18: a BASELINE row can only grant. Because entitlement_period_ex allows one row per
  -- (tenant, feature, LE, period), a BASELINE row also blocks any OVERRIDE that would disable it.
  CONSTRAINT entitlement_baseline_ck CHECK (source <> 'BASELINE' OR (is_enabled AND legal_entity_id IS NULL)),
  CONSTRAINT entitlement_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT entitlement_period_ex EXCLUDE USING gist (
    tenant_id WITH =, feature_key WITH =,
    (coalesce(legal_entity_id, sys.global_tenant_id())) WITH =, valid_during WITH &&)
);

-- Baseline always-on capabilities (decision-log R2-18, GAP-060; SA ADR-0003 §5).  [P, REPLICATED]
-- The entitlement resolver writes one source = 'BASELINE' entitlement row per tenant and
-- feature_key listed here, whatever plan or modules the tenant bought. Changing this list
-- is a product decision (PM), shipped by the release job, not by a tenant or operator.
CREATE TABLE saas.baseline_capability (
  feature_key   text NOT NULL,                                -- e.g. baseline.people.master
  owner_module  text NOT NULL,                                -- module that provides it
  description   text NOT NULL,
  valid_from    date NOT NULL,
  valid_to      date,
  CONSTRAINT baseline_capability_pk PRIMARY KEY (feature_key, valid_from),
  CONSTRAINT baseline_capability_owner_ck CHECK (owner_module IN ('saas','core','hr','att','pay')),
  CONSTRAINT baseline_capability_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from)
);
COMMENT ON TABLE saas.baseline_capability IS
  '[P] R2-18 always-on capabilities: baseline.core, baseline.people.master, baseline.legal_params, '
  'baseline.pay.scales_bands, baseline.att.excel_import (seeded by the release job).';

-- -----------------------------------------------------------------------------
-- Billing  [T]  (invoices are VN e-invoices; read by tenant UI through the SaaS API)
-- -----------------------------------------------------------------------------
CREATE TABLE saas.invoice (
  tenant_id       uuid NOT NULL,
  id              uuid NOT NULL DEFAULT sys.uuid_v7(),
  invoice_no      text NOT NULL,
  einvoice_code   text,                                       -- tax authority code
  lookup_code     text,
  issue_date      date NOT NULL,
  period_from     date NOT NULL,
  period_to       date NOT NULL,
  subtotal_amount sys.money_amount NOT NULL,
  vat_amount      sys.money_amount NOT NULL,
  total_amount    sys.money_amount NOT NULL,
  currency_code   sys.currency_code NOT NULL DEFAULT 'VND',
  status          text NOT NULL DEFAULT 'DRAFT',
  replaces_invoice_id uuid,
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid,
  version         int NOT NULL DEFAULT 0,
  CONSTRAINT invoice_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT invoice_no_uq UNIQUE (invoice_no),               -- platform-wide numbering series
  CONSTRAINT invoice_status_ck CHECK (status IN ('DRAFT','ISSUED','SENT','PAID','REPLACED','ADJUSTED','CANCELLED')),
  CONSTRAINT invoice_total_ck CHECK (total_amount = subtotal_amount + vat_amount)
);

CREATE TABLE saas.invoice_line (
  tenant_id     uuid NOT NULL,
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  invoice_id    uuid NOT NULL,
  line_no       int  NOT NULL,
  item_code     text NOT NULL,
  description   text NOT NULL,
  quantity      sys.quantity NOT NULL,
  unit_price_amount sys.money_amount NOT NULL,
  line_amount   sys.money_amount NOT NULL,
  vat_rate      sys.rate NOT NULL,
  CONSTRAINT invoice_line_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT invoice_line_invoice_fk FOREIGN KEY (tenant_id, invoice_id) REFERENCES saas.invoice (tenant_id, id),
  CONSTRAINT invoice_line_uq UNIQUE (tenant_id, invoice_id, line_no)
);

-- -----------------------------------------------------------------------------
-- Usage metering  [T, PLATFORM]  (BR-SAAS-012/013, decision D-10; DESIGN §10.1)
-- One row per tenant x metric x module x legal entity x measurement day. The
-- billable quantity of a month is the row with measured_on = last day of the month
-- (D-10: persons actively employed at the legal entity on the last day, counted per
-- legal entity and per module); daily rows are informative only. Counts come from
-- the owner modules' APIs/events (People: CO-05), never from reading their tables.
-- Once a month is invoiced its rows are frozen (is_frozen) and become immutable;
-- corrections are new MANUAL_ADJUSTMENT rows in an open month.
-- Range-partitioned by usage_month (monthly), hot retention 25 months.
-- -----------------------------------------------------------------------------
CREATE TABLE saas.usage_record (
  tenant_id        uuid        NOT NULL,
  id               uuid        NOT NULL DEFAULT sys.uuid_v7(),
  usage_month      sys.month_date NOT NULL,                   -- partition key (first day of month)
  measured_on      date        NOT NULL,                      -- snapshot day inside usage_month
  metric_code      text        NOT NULL,                      -- BILLABLE_PERSON | ACTIVE_USER | STORAGE_GB | API_CALL | EMAIL_SENT | SMS_SENT | ZNS_SENT | ESIGN_TXN
  module_code      text,                                      -- core | hr | att | pay | rec; NULL = not module-specific
  legal_entity_id  uuid,                                      -- -> core.legal_entity (soft); NULL = tenant-wide metric
  quantity         numeric(19,4) NOT NULL,
  source           text        NOT NULL DEFAULT 'SNAPSHOT_JOB',
  source_ref       text,                                      -- snapshot job id / adapter batch id / adjustment ticket
  detail           jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- breakdown counts only; NEVER person identifiers
  is_frozen        boolean     NOT NULL DEFAULT false,
  frozen_at        timestamptz,
  invoice_id       uuid,                                      -- -> saas.invoice once billed (P2 billing, R3-01: P1 invoices issued outside the product)
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  CONSTRAINT usage_record_pk PRIMARY KEY (tenant_id, id, usage_month),
  CONSTRAINT usage_record_uq UNIQUE NULLS NOT DISTINCT
    (tenant_id, usage_month, metric_code, module_code, legal_entity_id, measured_on, source),
  CONSTRAINT usage_record_invoice_fk FOREIGN KEY (tenant_id, invoice_id) REFERENCES saas.invoice (tenant_id, id),
  CONSTRAINT usage_record_metric_ck CHECK (metric_code IN ('BILLABLE_PERSON','ACTIVE_USER','STORAGE_GB','API_CALL',
                                                           'EMAIL_SENT','SMS_SENT','ZNS_SENT','ESIGN_TXN')),
  CONSTRAINT usage_record_module_ck CHECK (module_code IS NULL OR module_code IN ('core','hr','att','pay','rec')),
  CONSTRAINT usage_record_source_ck CHECK (source IN ('SNAPSHOT_JOB','ADAPTER','MANUAL_ADJUSTMENT')),
  CONSTRAINT usage_record_month_ck CHECK (measured_on >= usage_month
                                          AND measured_on < (usage_month + interval '1 month')),   -- tz-independent
  CONSTRAINT usage_record_qty_ck CHECK (quantity >= 0 OR source = 'MANUAL_ADJUSTMENT'),
  CONSTRAINT usage_record_frozen_ck CHECK (is_frozen = (frozen_at IS NOT NULL)),
  CONSTRAINT usage_record_billable_ck CHECK (metric_code <> 'BILLABLE_PERSON'
                                             OR (module_code IS NOT NULL AND legal_entity_id IS NOT NULL))
) PARTITION BY RANGE (usage_month);
CREATE INDEX usage_record_month_ix ON saas.usage_record (tenant_id, usage_month, metric_code);
COMMENT ON TABLE saas.usage_record IS
  '[T] Usage metering (BR-SAAS-013). Billable persons (D-10) = BILLABLE_PERSON row measured on the last day of the month, per LE x module. Frozen rows are immutable.';
COMMENT ON COLUMN saas.usage_record.detail IS 'pii:P0 aggregate counts only (no person ids)';

-- Frozen (invoiced) months are immutable; the only legal change is freezing an open row.
CREATE OR REPLACE FUNCTION saas.tg_usage_frozen_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.is_frozen THEN
    RAISE EXCEPTION 'usage record % of % is frozen', OLD.id, OLD.usage_month USING ERRCODE = '55000';
  END IF;
  RETURN coalesce(NEW, OLD);
END $$;
CREATE TRIGGER trg_frozen_guard BEFORE UPDATE OR DELETE ON saas.usage_record
  FOR EACH ROW EXECUTE FUNCTION saas.tg_usage_frozen_guard();

CALL sys.create_range_partitions('saas.usage_record', 'month', (current_date - interval '1 month')::date, 4);

-- -----------------------------------------------------------------------------
-- Support grants  [T]  (BR-SAAS-033..037)
-- -----------------------------------------------------------------------------
CREATE TABLE saas.support_grant (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  operator_id      uuid NOT NULL,
  ticket_ref       text NOT NULL,
  modules          text[] NOT NULL,
  access_mode      text NOT NULL,                             -- READ | READ_WRITE
  includes_sensitive boolean NOT NULL DEFAULT false,          -- unmask P3 (BR-SAAS-034)
  approved_by      uuid,                                      -- tenant admin/owner (core.user_account, soft)
  valid_from       timestamptz NOT NULL,
  valid_to         timestamptz NOT NULL,
  status           text NOT NULL DEFAULT 'REQUESTED',
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT support_grant_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT support_grant_mode_ck CHECK (access_mode IN ('READ','READ_WRITE')),
  CONSTRAINT support_grant_window_ck CHECK (valid_to > valid_from AND valid_to <= valid_from + interval '72 hours'),
  CONSTRAINT support_grant_status_ck CHECK (status IN ('REQUESTED','APPROVED','REJECTED','ACTIVE','EXPIRED','REVOKED'))
);

-- Operator access log  [T, PLATFORM]  (BR-SAAS-037; decision R3-08: minimal operator
-- access logging is Phase 1).  One row per operator session start/end and per
-- significant access (unmask, change, export, impersonation) to a tenant's data.
-- Visible to the tenant's admins through the SaaS API (yhrm_app_saas, RLS) and
-- exportable. Append-only. Object-level detail of what was read/changed is ALSO in
-- the tenant's audit.audit_event (operator_id / support_grant_id columns, ADR-0027);
-- audit_event_id links the two when known. Break-glass sessions (BR-SAAS-036) have
-- no support grant and must carry a reason + incident reference.
CREATE TABLE saas.operator_access_log (
  tenant_id            uuid        NOT NULL,
  id                   uuid        NOT NULL DEFAULT sys.uuid_v7(),
  occurred_at          timestamptz NOT NULL DEFAULT now(),
  operator_id          uuid        NOT NULL,                  -- platform operator (Keycloak operator realm user, soft)
  operator_display     text        NOT NULL,                  -- name shown to the tenant (operators are named, not anonymous)
  access_type          text        NOT NULL,
  support_grant_id     uuid,                                  -- NULL only for BREAK_GLASS
  operator_session_id  uuid,                                  -- groups the rows of one session (soft)
  ticket_ref           text,
  reason               text        NOT NULL,
  action               text        NOT NULL,
  modules              text[]      NOT NULL DEFAULT '{}',
  impersonated_user_id uuid,                                  -- -> core.user_account (soft), BR-SAAS-035
  object_schema        text,
  object_type          text,
  object_id            uuid,
  audit_event_id       uuid,                                  -- -> audit.audit_event (soft, tenant cluster)
  detail               jsonb       NOT NULL DEFAULT '{}'::jsonb,   -- masked summary; never unmasked values
  client_ip            inet,
  created_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT operator_access_log_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT operator_access_log_grant_fk FOREIGN KEY (tenant_id, support_grant_id)
    REFERENCES saas.support_grant (tenant_id, id),
  CONSTRAINT operator_access_log_type_ck CHECK (access_type IN ('SUPPORT_SESSION','IMPERSONATION','BREAK_GLASS')),
  CONSTRAINT operator_access_log_action_ck CHECK (action IN ('SESSION_START','SESSION_END','VIEW','UNMASK',
                                                             'CHANGE','EXPORT','IMPERSONATE_START','IMPERSONATE_END')),
  CONSTRAINT operator_access_log_grant_ck CHECK ((access_type = 'BREAK_GLASS') = (support_grant_id IS NULL)),
  CONSTRAINT operator_access_log_imp_ck CHECK (access_type <> 'IMPERSONATION' OR impersonated_user_id IS NOT NULL)
);
CREATE INDEX operator_access_log_time_ix  ON saas.operator_access_log (tenant_id, occurred_at);
CREATE INDEX operator_access_log_grant_ix ON saas.operator_access_log (tenant_id, support_grant_id);
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON saas.operator_access_log
  FOR EACH ROW EXECUTE FUNCTION sys.tg_forbid_change();
COMMENT ON TABLE saas.operator_access_log IS
  '[T] Tenant-visible log of operator access to tenant data (BR-SAAS-037, R3-08). Append-only.';
COMMENT ON COLUMN saas.operator_access_log.operator_display IS 'pii:P1 operator name (vendor staff)';
COMMENT ON COLUMN saas.operator_access_log.client_ip IS 'pii:P2 operator client IP';

-- -----------------------------------------------------------------------------
-- Privacy registries  [T]  (gap §5 #27; PDPL 2025)
-- -----------------------------------------------------------------------------
CREATE TABLE saas.consent_record (
  tenant_id       uuid NOT NULL,
  id              uuid NOT NULL DEFAULT sys.uuid_v7(),
  subject_type    text NOT NULL,                              -- PERSON | CANDIDATE
  subject_id      uuid NOT NULL,                              -- soft ref
  purpose_code    text NOT NULL,                              -- e.g. BIOMETRIC_CHECKIN, GPS_CHECKIN, TALENT_POOL
  notice_version  text NOT NULL,
  status          text NOT NULL,
  channel         text NOT NULL,
  given_at        timestamptz,
  withdrawn_at    timestamptz,
  expires_at      timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT consent_record_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT consent_record_status_ck CHECK (status IN ('GIVEN','WITHDRAWN','EXPIRED'))
);
CREATE INDEX consent_record_subject_ix ON saas.consent_record (tenant_id, subject_type, subject_id, purpose_code);

CREATE TABLE saas.data_subject_request (
  tenant_id       uuid NOT NULL,
  id              uuid NOT NULL DEFAULT sys.uuid_v7(),
  subject_type    text NOT NULL,
  subject_id      uuid NOT NULL,
  request_type    text NOT NULL,
  received_at     timestamptz NOT NULL,
  due_at          timestamptz NOT NULL,
  status          text NOT NULL DEFAULT 'RECEIVED',
  fanout_status   jsonb NOT NULL DEFAULT '{}'::jsonb,         -- per module handler: {"hr":"DONE","pay":"RETAINED_LEGAL"}
  evidence_doc_id uuid,                                       -- -> core.document (soft)
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         int NOT NULL DEFAULT 0,
  CONSTRAINT dsr_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT dsr_type_ck CHECK (request_type IN ('ACCESS','RECTIFY','ERASE','RESTRICT','OBJECT','PORTABILITY','WITHDRAW_CONSENT')),
  CONSTRAINT dsr_status_ck CHECK (status IN ('RECEIVED','VERIFYING','APPROVED','IN_PROGRESS','COMPLETED','REJECTED'))
);

CREATE TABLE saas.retention_policy (
  tenant_id        uuid NOT NULL,                             -- global defaults use the sentinel tenant
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  data_category    text NOT NULL,                             -- PAYROLL_RESULT, RAW_PUNCH, CHECKIN_PHOTO, CANDIDATE ...
  retention_period interval NOT NULL,
  legal_floor      interval,                                  -- tenant cannot go below [verify-legal]
  anchor_event     text NOT NULL,                             -- EMPLOYMENT_END | RECORD_DATE | LAST_ACTIVITY
  action           text NOT NULL,
  last_run_at      timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT retention_policy_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT retention_policy_uq UNIQUE (tenant_id, data_category),
  CONSTRAINT retention_policy_action_ck CHECK (action IN ('PURGE','ANONYMISE','CRYPTO_SHRED','ARCHIVE')),
  CONSTRAINT retention_policy_floor_ck CHECK (legal_floor IS NULL OR retention_period >= legal_floor)
);

-- -----------------------------------------------------------------------------
-- Archive manifest  [T]  (DESIGN §10.2)
-- -----------------------------------------------------------------------------
CREATE TABLE saas.archive_manifest (
  tenant_id     uuid NOT NULL,
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  source_table  text NOT NULL,                                -- e.g. att.raw_punch
  period_from   date NOT NULL,
  period_to     date NOT NULL,
  object_uri    text NOT NULL,
  row_count     bigint NOT NULL,
  sha256        text NOT NULL,
  key_version   int NOT NULL,
  archived_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT archive_manifest_pk PRIMARY KEY (tenant_id, id)
);

-- =============================================================================
-- LEGAL PARAMETER PACKS  [G]   (DESIGN §6, ADR-0025)
-- =============================================================================
CREATE TABLE saas.legal_param_definition (
  param_key       text NOT NULL,                              -- SI.EE.RATE, MIN_WAGE.REGION_I, PIT.BRACKETS ...
  domain_code     text NOT NULL,                              -- SI | PIT | MIN_WAGE | OT | LEAVE | LABOUR | ROUNDING | HOLIDAY
  value_type      text NOT NULL,                              -- SCALAR | TABLE | MAP | CALENDAR_TEMPLATE (ADR-0010)
  scalar_type     text,                                       -- for SCALAR: NUMBER | INT | MONEY | RATE | BOOL | TEXT | DATE
  unit            text,                                       -- VND, %, days, hours, months
  json_schema     jsonb,                                      -- validates value_json for structured types
  override_rule   text NOT NULL DEFAULT 'NONE',
  override_scope  text,                                       -- most specific level allowed: TENANT | LEGAL_ENTITY | LOCATION (M3)
  description_vi  text NOT NULL,
  description_en  text NOT NULL,
  is_active       boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT legal_param_definition_pk PRIMARY KEY (param_key),
  CONSTRAINT legal_param_definition_key_ck CHECK (param_key ~ '^[A-Z0-9_]+(\.[A-Z0-9_]+)*$'),
  CONSTRAINT legal_param_definition_type_ck CHECK (value_type IN ('SCALAR','TABLE','MAP','CALENDAR_TEMPLATE')),
  CONSTRAINT legal_param_definition_scalar_ck CHECK ((value_type = 'SCALAR') = (scalar_type IS NOT NULL)
    AND (scalar_type IS NULL OR scalar_type IN ('NUMBER','INT','MONEY','RATE','BOOL','TEXT','DATE'))),
  CONSTRAINT legal_param_definition_override_ck CHECK (override_rule IN ('NONE','ANY','FLOOR','CAP')),
  CONSTRAINT legal_param_definition_scope_ck CHECK (
    (override_rule = 'NONE' AND override_scope IS NULL) OR
    (override_rule <> 'NONE' AND override_scope IN ('TENANT','LEGAL_ENTITY','LOCATION')))   -- most specific allowed level
);
COMMENT ON TABLE saas.legal_param_definition IS '[G] Typed catalogue of statutory parameters and their override governance.';

CREATE TABLE saas.legal_param_pack (
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  pack_code        text NOT NULL DEFAULT 'VN',
  pack_version     bigint NOT NULL,                           -- monotonic per pack_code
  title            text NOT NULL,                             -- "Decree 74/2024 minimum wage"
  status           text NOT NULL DEFAULT 'DRAFT',
  legal_references jsonb NOT NULL DEFAULT '[]'::jsonb,        -- [{"doc":"74/2024/ND-CP","issued":"2024-06-30"}]
  supersedes_pack_id uuid,
  test_suite_ref   text,                                      -- golden-file suite run id (NFR-PAY-008)
  reviewed_by      uuid,
  approved_by      uuid,                                      -- consultant sign-off
  published_at     timestamptz,
  content_sha256   text,                                      -- hash of canonical content at publication
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid,
  CONSTRAINT legal_param_pack_pk PRIMARY KEY (id),
  CONSTRAINT legal_param_pack_version_uq UNIQUE (pack_code, pack_version),
  CONSTRAINT legal_param_pack_status_ck CHECK (status IN ('DRAFT','IN_TEST','APPROVED','PUBLISHED','SUPERSEDED','WITHDRAWN')),
  CONSTRAINT legal_param_pack_published_ck CHECK (status NOT IN ('PUBLISHED','SUPERSEDED','WITHDRAWN') OR
                                                  (published_at IS NOT NULL AND content_sha256 IS NOT NULL))
);
COMMENT ON TABLE saas.legal_param_pack IS '[G] Cumulative, immutable snapshot of ALL statutory parameters with full effective history.';

CREATE TABLE saas.legal_param_value (
  id           uuid NOT NULL DEFAULT sys.uuid_v7(),
  pack_id      uuid NOT NULL REFERENCES saas.legal_param_pack (id),
  param_key    text NOT NULL REFERENCES saas.legal_param_definition (param_key),
  valid_from   date NOT NULL,
  valid_to     date,
  valid_during daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  value_num    numeric,
  value_text   text,
  value_json   jsonb,
  legal_ref    text NOT NULL,                                 -- article/clause, e.g. "Decree 74/2024 Art.3"
  note         text,
  CONSTRAINT legal_param_value_pk PRIMARY KEY (id),
  CONSTRAINT legal_param_value_one_ck CHECK (num_nonnulls(value_num, value_text, value_json) = 1),
  CONSTRAINT legal_param_value_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT legal_param_value_ex EXCLUDE USING gist (pack_id WITH =, param_key WITH =, valid_during WITH &&)
);
CREATE INDEX legal_param_value_lookup_ix ON saas.legal_param_value (pack_id, param_key, valid_from);
COMMENT ON TABLE saas.legal_param_value IS '[G] Value of a key over a date range inside a pack. Lookup: pack_id + key + valid_during @> date.';

CREATE TABLE saas.legal_param_table_row (
  value_id   uuid NOT NULL REFERENCES saas.legal_param_value (id),
  row_key    text NOT NULL,                                   -- e.g. admin_unit_code
  row_value  jsonb NOT NULL,                                  -- e.g. {"region":"II"}
  CONSTRAINT legal_param_table_row_pk PRIMARY KEY (value_id, row_key)
);
COMMENT ON TABLE saas.legal_param_table_row IS '[G] Large MAP values (admin unit -> minimum wage region, ~3,300 rows).';

-- Published packs are immutable (values and rows of a published pack cannot change).
CREATE OR REPLACE FUNCTION saas.tg_legal_pack_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_pack uuid; v_status text;
BEGIN
  IF TG_TABLE_NAME = 'legal_param_pack' THEN
    IF OLD.status IN ('PUBLISHED','SUPERSEDED','WITHDRAWN') THEN
      -- only status moves PUBLISHED -> SUPERSEDED | WITHDRAWN and SUPERSEDED -> WITHDRAWN;
      -- content (incl. content_sha256) never changes
      IF TG_OP = 'DELETE' OR NOT (
             ((OLD.status = 'PUBLISHED'  AND NEW.status IN ('SUPERSEDED','WITHDRAWN'))
           OR (OLD.status = 'SUPERSEDED' AND NEW.status = 'WITHDRAWN'))
           AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status')) THEN
        RAISE EXCEPTION 'legal pack % is published and immutable', OLD.id USING ERRCODE = '55000';
      END IF;
    END IF;
    RETURN coalesce(NEW, OLD);
  END IF;

  IF TG_TABLE_NAME = 'legal_param_value' THEN
    v_pack := coalesce(NEW.pack_id, OLD.pack_id);
  ELSE  -- legal_param_table_row
    SELECT pack_id INTO v_pack FROM saas.legal_param_value WHERE id = coalesce(NEW.value_id, OLD.value_id);
  END IF;
  SELECT status INTO v_status FROM saas.legal_param_pack WHERE id = v_pack;
  IF v_status NOT IN ('DRAFT','IN_TEST') THEN
    RAISE EXCEPTION 'legal pack % is % and cannot be modified', v_pack, v_status USING ERRCODE = '55000';
  END IF;
  RETURN coalesce(NEW, OLD);
END $$;

CREATE TRIGGER trg_immutable BEFORE UPDATE OR DELETE ON saas.legal_param_pack
  FOR EACH ROW EXECUTE FUNCTION saas.tg_legal_pack_immutable();
CREATE TRIGGER trg_immutable BEFORE INSERT OR UPDATE OR DELETE ON saas.legal_param_value
  FOR EACH ROW EXECUTE FUNCTION saas.tg_legal_pack_immutable();
CREATE TRIGGER trg_immutable BEFORE INSERT OR UPDATE OR DELETE ON saas.legal_param_table_row
  FOR EACH ROW EXECUTE FUNCTION saas.tg_legal_pack_immutable();

-- As-of lookup helper used by the Legal Parameter Service (and by replay tooling).
CREATE OR REPLACE FUNCTION saas.legal_param_as_of(p_pack_id uuid, p_key text, p_date date)
RETURNS saas.legal_param_value
LANGUAGE sql STABLE AS $$
  SELECT v.* FROM saas.legal_param_value v
   WHERE v.pack_id = p_pack_id AND v.param_key = p_key AND v.valid_during @> p_date
$$;

-- Tenant overrides  [T]  -------------------------------------------------------
CREATE TABLE saas.legal_param_override_set (
  tenant_id         uuid NOT NULL,
  id                uuid NOT NULL DEFAULT sys.uuid_v7(),
  override_version  int  NOT NULL,                            -- monotonic per tenant; stamped on results
  status            text NOT NULL DEFAULT 'DRAFT',
  checked_against_pack_id uuid,                               -- statutory pack used for FLOOR/CAP validation
  workflow_instance_id uuid,                                  -- -> core.workflow_instance (soft)
  activated_at      timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid,
  CONSTRAINT legal_param_override_set_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT legal_param_override_set_uq UNIQUE (tenant_id, override_version),
  CONSTRAINT legal_param_override_set_status_ck CHECK (status IN ('DRAFT','PENDING_APPROVAL','ACTIVE','SUPERSEDED','REJECTED'))
);
CREATE UNIQUE INDEX legal_param_override_set_active_ix ON saas.legal_param_override_set (tenant_id) WHERE status = 'ACTIVE';

CREATE TABLE saas.legal_param_override (
  tenant_id        uuid NOT NULL,
  id               uuid NOT NULL DEFAULT sys.uuid_v7(),
  override_set_id  uuid NOT NULL,
  legal_entity_id  uuid,                                      -- -> core.legal_entity (soft); NULL = tenant-wide
  location_id      uuid,                                      -- -> core.location (soft); v0.4 M3: LOCATION-scoped override
                                                              --    (e.g. a location-level minimum-wage region override
                                                              --    where the pack allows it; Core keeps no override, BR-CORE-006)
  param_key        text NOT NULL,                             -- -> saas.legal_param_definition (same schema, global)
  valid_from       date NOT NULL,
  valid_to         date,                                      -- exclusive; NULL = open-ended
  valid_during     daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  value_num        numeric,
  value_text       text,
  value_json       jsonb,
  justification    text NOT NULL,
  evidence_doc_id  uuid,                                      -- e.g. collective agreement (core.document, soft)
  CONSTRAINT legal_param_override_pk PRIMARY KEY (tenant_id, id),
  CONSTRAINT legal_param_override_set_fk FOREIGN KEY (tenant_id, override_set_id)
    REFERENCES saas.legal_param_override_set (tenant_id, id),
  CONSTRAINT legal_param_override_key_fk FOREIGN KEY (param_key) REFERENCES saas.legal_param_definition (param_key),
  CONSTRAINT legal_param_override_one_ck CHECK (num_nonnulls(value_num, value_text, value_json) = 1),
  CONSTRAINT legal_param_override_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
  -- a location belongs to exactly one legal entity; carrying both keeps resolution a plain lookup
  CONSTRAINT legal_param_override_loc_ck CHECK (location_id IS NULL OR legal_entity_id IS NOT NULL),
  -- one value per (set, key, scope, day): no overlapping periods at the same scope
  CONSTRAINT legal_param_override_ex EXCLUDE USING gist (
    tenant_id WITH =, override_set_id WITH =, param_key WITH =,
    (coalesce(legal_entity_id, sys.global_tenant_id())) WITH =,
    (coalesce(location_id, sys.global_tenant_id())) WITH =, valid_during WITH &&)
);
CREATE INDEX legal_param_override_lookup_ix ON saas.legal_param_override (tenant_id, override_set_id, param_key, valid_from);
COMMENT ON TABLE saas.legal_param_override IS
  '[T] Tenant override of a statutory parameter. Resolution (ADR-0010): GLOBAL pack -> TENANT -> LEGAL_ENTITY -> LOCATION, most specific wins.';

-- The override level must be allowed by the definition: override_rule <> NONE, and the
-- row is no more specific than definition.override_scope (TENANT < LEGAL_ENTITY < LOCATION).
-- FLOOR/CAP checks against the statutory value need the pack and run in the service.
CREATE OR REPLACE FUNCTION saas.tg_legal_param_override_scope()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_rule text; v_scope text; v_level int; v_max int;
BEGIN
  SELECT override_rule, override_scope INTO v_rule, v_scope
    FROM saas.legal_param_definition WHERE param_key = NEW.param_key;
  IF v_rule IS NULL OR v_rule = 'NONE' THEN
    RAISE EXCEPTION 'legal parameter % cannot be overridden', NEW.param_key USING ERRCODE = '23514';
  END IF;
  v_level := CASE WHEN NEW.location_id IS NOT NULL THEN 3 WHEN NEW.legal_entity_id IS NOT NULL THEN 2 ELSE 1 END;
  v_max   := CASE v_scope WHEN 'LOCATION' THEN 3 WHEN 'LEGAL_ENTITY' THEN 2 ELSE 1 END;
  IF v_level > v_max THEN
    RAISE EXCEPTION 'legal parameter % allows overrides down to % only', NEW.param_key, v_scope USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_scope BEFORE INSERT OR UPDATE ON saas.legal_param_override
  FOR EACH ROW EXECUTE FUNCTION saas.tg_legal_param_override_scope();

-- -----------------------------------------------------------------------------
-- Platform audit (operator/identity events with no tenant business object)  [P]
-- Same seal mechanics as audit.audit_event (002_core.sql); sentinel tenant.
-- -----------------------------------------------------------------------------
CREATE TABLE saas.platform_audit_event (
  id            uuid NOT NULL DEFAULT sys.uuid_v7(),
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  actor_type    text NOT NULL,
  actor_id      uuid,
  tenant_id     uuid,                                         -- affected tenant, if any
  action        text NOT NULL,
  detail        jsonb NOT NULL DEFAULT '{}'::jsonb,
  client_ip     inet,
  chain_seq     bigint,
  prev_hash     bytea,
  row_hash      bytea,
  sealed_at     timestamptz,
  CONSTRAINT platform_audit_event_pk PRIMARY KEY (id)
);

-- -----------------------------------------------------------------------------
-- RLS + grants
-- -----------------------------------------------------------------------------
-- Tenant-owned [T] tables: standard isolation for app roles + full access for platform role.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['saas.tenant_key','saas.subject_key','saas.subscription','saas.subscription_item',
                           'saas.entitlement','saas.invoice','saas.invoice_line','saas.support_grant',
                           'saas.consent_record','saas.data_subject_request','saas.archive_manifest',
                           'saas.legal_param_override_set','saas.legal_param_override',
                           'saas.usage_record','saas.operator_access_log']
  LOOP
    CALL sys.enable_tenant_rls(t::regclass);
    EXECUTE format('CREATE POLICY platform_all ON %s FOR ALL TO yhrm_platform USING (true) WITH CHECK (true)', t);
  END LOOP;
END $$;

-- retention_policy is global (sentinel) + tenant override.
CALL sys.enable_tenant_or_global_rls('saas.retention_policy');
CREATE POLICY platform_all ON saas.retention_policy FOR ALL TO yhrm_platform USING (true) WITH CHECK (true);

-- Mutable tables get the touch trigger.
CALL sys.attach_touch_trigger('saas.subscription');
CALL sys.attach_touch_trigger('saas.invoice');
CALL sys.attach_touch_trigger('saas.data_subject_request');

-- The SaaS module runtime (yhrm_app_saas) works on tenant rows under RLS.
GRANT SELECT ON saas.tenant TO yhrm_app;                                   -- own row only (policy)
GRANT SELECT, INSERT, UPDATE ON saas.tenant_key, saas.subject_key, saas.subscription, saas.subscription_item,
      saas.entitlement, saas.invoice, saas.invoice_line, saas.support_grant, saas.consent_record,
      saas.data_subject_request, saas.retention_policy, saas.archive_manifest,
      saas.legal_param_override_set, saas.legal_param_override, saas.usage_record TO yhrm_app_saas;
GRANT SELECT, INSERT ON saas.operator_access_log TO yhrm_app_saas;          -- append-only; tenant admins read via SaaS API
GRANT SELECT ON saas.plan, saas.plan_version, saas.baseline_capability TO yhrm_app_saas;

-- Legal packs: every module reads published packs through the Legal Parameter
-- Service (owned by SaaS); direct SELECT is limited to the SaaS runtime.
GRANT SELECT ON saas.legal_param_definition, saas.legal_param_pack, saas.legal_param_value,
      saas.legal_param_table_row TO yhrm_app_saas;
GRANT EXECUTE ON FUNCTION saas.legal_param_as_of(uuid, text, date) TO yhrm_app_saas;

-- Operator back-office: platform tables + pack authoring. No access to module schemas.
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA saas TO yhrm_platform;
REVOKE UPDATE ON saas.platform_audit_event, saas.operator_access_log FROM yhrm_platform;

-- Audit sealer seals the platform chain too.
GRANT USAGE ON SCHEMA saas TO yhrm_audit_sealer;
GRANT SELECT, UPDATE (chain_seq, prev_hash, row_hash, sealed_at) ON saas.platform_audit_event TO yhrm_audit_sealer;

-- Outbox / inbox for the SaaS module (saas.TenantProvisioned, saas.LegalParameterPackPublished ...)
CALL sys.create_outbox_inbox('saas', 'yhrm_app_saas');

RESET ROLE;
