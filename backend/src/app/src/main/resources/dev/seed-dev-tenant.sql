-- =============================================================================
-- YourHRM · backend · dev/seed-dev-tenant.sql
-- LOCAL DEVELOPMENT seed: one dev tenant with a legal entity, a location, a small org tree,
-- the global Core permission catalogue, the "Tenant Owner" system role holding every core.* permission
-- and an owner user whose identity_id links to the Keycloak user.
--
-- Run with psql as the database superuser (or yhrm_owner) on a database where the DDL baseline
-- database/postgresql/ddl/000..007 is loaded. Idempotent (re-runnable).
--
--   psql -h localhost -U postgres -d yhrm -v owner_identity=<keycloak-user-id> -f seed-dev-tenant.sql
--
-- owner_identity = the `sub` of the Keycloak user who should act as Tenant Owner (defaults to a
-- placeholder that matches no real identity; update it later, see backend/README.md).
-- The access token must also carry the claim tenant_id = 01900000-0000-7000-8000-000000000001.
--
-- NEVER run against shared / production databases. The permission rows below are what the release job
-- registers in real environments (the runtime role yhrm_app_core has SELECT only on core.permission).
-- =============================================================================
\set ON_ERROR_STOP on
\if :{?owner_identity}
\else
  \set owner_identity '00000000-0000-4000-8000-0000000000aa'
\endif

BEGIN;

SELECT set_config('yhrm.owner_identity', :'owner_identity', true);

-- 1. Global permission catalogue of Core (mirror of CorePermissionCatalogue; the app verifies it at start-up)
INSERT INTO core.permission (permission_code, owner_module, function_code, action, name_vi, name_en,
                             is_sensitive, is_privileged, requires_step_up, is_active) VALUES
  ('core.legal_entity.view',      'core', 'core.legal_entity',    'VIEW',   'Xem pháp nhân', 'View legal entities', false, false, false, true),
  ('core.location.view',          'core', 'core.location',        'VIEW',   'Xem địa điểm', 'View locations', false, false, false, true),
  ('core.org_unit.view',          'core', 'core.org_unit',        'VIEW',   'Xem đơn vị tổ chức', 'View org units', false, false, false, true),
  ('core.permission.view',        'core', 'core.permission',      'VIEW',   'Xem danh mục quyền', 'View permission catalogue', false, false, false, true),
  ('core.role.create',            'core', 'core.role',            'CREATE', 'Tạo vai trò', 'Create roles', false, false, false, true),
  ('core.role.delete',            'core', 'core.role',            'DELETE', 'Xóa vai trò', 'Delete roles', false, false, false, true),
  ('core.role.update',            'core', 'core.role',            'UPDATE', 'Sửa vai trò và ma trận quyền', 'Update roles and permission matrix', false, true, false, true),
  ('core.role.view',              'core', 'core.role',            'VIEW',   'Xem vai trò', 'View roles', false, false, false, true),
  ('core.role_assignment.create', 'core', 'core.role_assignment', 'CREATE', 'Gán vai trò', 'Grant roles', false, true, false, true),
  ('core.role_assignment.delete', 'core', 'core.role_assignment', 'DELETE', 'Thu hồi vai trò', 'Revoke roles', false, true, false, true),
  ('core.role_assignment.view',   'core', 'core.role_assignment', 'VIEW',   'Xem gán vai trò', 'View role assignments', false, false, false, true),
  ('core.user.create',            'core', 'core.user',            'CREATE', 'Tạo người dùng', 'Create users', false, false, false, true),
  ('core.user.update',            'core', 'core.user',            'UPDATE', 'Sửa / kích hoạt / vô hiệu hóa người dùng', 'Update / activate / deactivate users', false, false, false, true),
  ('core.user.view',              'core', 'core.user',            'VIEW',   'Xem người dùng', 'View users', false, false, false, true)
ON CONFLICT (permission_code) DO UPDATE
   SET owner_module = excluded.owner_module, function_code = excluded.function_code, action = excluded.action,
       name_vi = excluded.name_vi, name_en = excluded.name_en, is_sensitive = excluded.is_sensitive,
       is_privileged = excluded.is_privileged, requires_step_up = excluded.requires_step_up, is_active = true;

-- 2. Tenant (control-plane row; replicated read-only into pool clusters in real environments)
INSERT INTO saas.tenant (id, slug, name, state, default_locale, time_zone, activated_at)
VALUES ('01900000-0000-7000-8000-000000000001', 'dev', 'YourHRM Dev Tenant', 'ACTIVE', 'vi-VN',
        'Asia/Ho_Chi_Minh', now())
ON CONFLICT (id) DO NOTHING;

-- The rows below are tenant-owned (RLS). Superuser / owner bypass RLS; we still set the context so
-- the audit trigger records a meaningful request id.
SELECT set_config('app.tenant_id', '01900000-0000-7000-8000-000000000001', true),
       set_config('app.request_id', 'seed-dev-tenant', true);

-- 3. Legal entities, location
INSERT INTO core.legal_entity (tenant_id, id, code, status) VALUES
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000101', 'HQ',  'ACTIVE'),
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000102', 'MFG', 'ACTIVE')
ON CONFLICT DO NOTHING;
INSERT INTO core.legal_entity_version (tenant_id, id, legal_entity_id, valid_from, legal_name_vi, legal_name_en, tax_code)
VALUES
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000111', '01900000-0000-7000-8000-000000000101',
   '2020-01-01', 'Công ty TNHH YourHRM Dev', 'YourHRM Dev Co., Ltd.', '0101234567'),
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000112', '01900000-0000-7000-8000-000000000102',
   '2020-01-01', 'Công ty TNHH Sản xuất Dev', 'Dev Manufacturing Co., Ltd.', '0107654321')
ON CONFLICT DO NOTHING;

INSERT INTO core.location (tenant_id, id, legal_entity_id, code) VALUES
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000201', '01900000-0000-7000-8000-000000000101', 'HN-HO')
ON CONFLICT DO NOTHING;
INSERT INTO core.location_version (tenant_id, id, location_id, valid_from, name, location_type) VALUES
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000211', '01900000-0000-7000-8000-000000000201',
   '2020-01-01', 'Trụ sở Hà Nội', 'HEAD_OFFICE')
ON CONFLICT DO NOTHING;

-- 4. Org tree: Company (root) -> HR, Finance
INSERT INTO core.org_unit_type (tenant_id, id, code, name_vi, name_en, level_hint) VALUES
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000301', 'COMPANY',    'Công ty', 'Company', 1),
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000302', 'DEPARTMENT', 'Phòng',   'Department', 2)
ON CONFLICT DO NOTHING;
INSERT INTO core.org_unit (tenant_id, id, code) VALUES
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000401', 'DEV'),
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000402', 'DEV-HR'),
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000403', 'DEV-FIN')
ON CONFLICT DO NOTHING;
INSERT INTO core.org_unit_version (tenant_id, id, org_unit_id, valid_from, name_vi, name_en, org_unit_type_id,
                                   parent_id, path, legal_entity_id, location_id)
SELECT '01900000-0000-7000-8000-000000000001'::uuid, v.id::uuid, v.unit::uuid, DATE '2020-01-01', v.name_vi, v.name_en,
       v.type_id::uuid, v.parent::uuid,
       text2ltree(CASE WHEN v.parent IS NULL THEN replace(v.unit, '-', '')
                       ELSE replace(v.parent, '-', '') || '.' || replace(v.unit, '-', '') END),
       '01900000-0000-7000-8000-000000000101'::uuid, '01900000-0000-7000-8000-000000000201'::uuid
  FROM (VALUES
    ('01900000-0000-7000-8000-000000000411', '01900000-0000-7000-8000-000000000401', 'Công ty Dev', 'Dev Company',
     '01900000-0000-7000-8000-000000000301', NULL),
    ('01900000-0000-7000-8000-000000000412', '01900000-0000-7000-8000-000000000402', 'Phòng Nhân sự', 'HR Department',
     '01900000-0000-7000-8000-000000000302', '01900000-0000-7000-8000-000000000401'),
    ('01900000-0000-7000-8000-000000000413', '01900000-0000-7000-8000-000000000403', 'Phòng Tài chính', 'Finance Department',
     '01900000-0000-7000-8000-000000000302', '01900000-0000-7000-8000-000000000401')
  ) AS v(id, unit, name_vi, name_en, type_id, parent)
ON CONFLICT DO NOTHING;

-- 5. System role Tenant Owner with every core.* permission
INSERT INTO core.role (tenant_id, id, code, name_vi, name_en, is_system, template_version) VALUES
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000501', 'TENANT_OWNER',
   'Chủ sở hữu tenant', 'Tenant Owner', true, 1)
ON CONFLICT DO NOTHING;
INSERT INTO core.role_permission (tenant_id, role_id, permission_code, field_mask_level)
SELECT '01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000501', p.permission_code, 'NONE'
  FROM core.permission p WHERE p.owner_module = 'core' AND p.is_active
ON CONFLICT DO NOTHING;

-- 6. Owner user, linked to the Keycloak identity, with Tenant Owner on the whole tenant
INSERT INTO core.user_account (tenant_id, id, login, email, identity_id, locale, status) VALUES
  ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000601', 'owner@dev.yourhrm.local',
   'owner@dev.yourhrm.local', current_setting('yhrm.owner_identity')::uuid, 'vi-VN', 'ACTIVE')
ON CONFLICT DO NOTHING;
UPDATE core.user_account SET identity_id = current_setting('yhrm.owner_identity')::uuid
 WHERE tenant_id = '01900000-0000-7000-8000-000000000001' AND id = '01900000-0000-7000-8000-000000000601'
   AND identity_id IS DISTINCT FROM current_setting('yhrm.owner_identity')::uuid;

INSERT INTO core.role_assignment (tenant_id, id, user_id, role_id, scope_type, scope_ids, include_subtree, valid_from)
VALUES ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000701',
        '01900000-0000-7000-8000-000000000601', '01900000-0000-7000-8000-000000000501', 'TENANT', '{}', true, DATE '2020-01-01')
ON CONFLICT DO NOTHING;

COMMIT;
