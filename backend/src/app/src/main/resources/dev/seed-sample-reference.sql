-- =============================================================================
-- YourHRM · backend · dev/seed-sample-reference.sql
-- LOCAL DEVELOPMENT reference data for the sample data set (scripts/sample-data):
--   * global master data that a real master-data release (MasterDataReleaseLoader) would provide:
--     countries, currencies, a SAMPLE of administrative units (2-level structure from 2025-07-01),
--     banks, code lists and their global values;
--   * the dev tenant's number series (EMPLOYEE_NO, CONTRACT_NO, DECISION_NO).
-- (The module catalogues and the TENANT_OWNER / HR_ADMIN / ESS ... matrices come from the dev profile's
--  DevReleaseJob and DevTenantProvisioner when the backend starts.)
--
-- Run as the database superuser AFTER dev/seed-dev-tenant.sql (see scripts/dev-reset.ps1). Idempotent.
-- NEVER run on shared / production databases.
-- Admin-unit codes are illustrative samples, not an authoritative GSO extract. The minimum-wage regions of these
-- units live in dev/legal-parameters.json (DevLegalParameters).
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

-- 1. Countries / currencies ---------------------------------------------------------------------------------------
INSERT INTO core.country (country_code, iso3, name_vi, name_en, nationality_vi, nationality_en) VALUES
  ('VN', 'VNM', 'Việt Nam', 'Viet Nam', 'Việt Nam', 'Vietnamese'),
  ('JP', 'JPN', 'Nhật Bản', 'Japan', 'Nhật Bản', 'Japanese'),
  ('KR', 'KOR', 'Hàn Quốc', 'Korea, Republic of', 'Hàn Quốc', 'Korean'),
  ('CN', 'CHN', 'Trung Quốc', 'China', 'Trung Quốc', 'Chinese'),
  ('US', 'USA', 'Hoa Kỳ', 'United States', 'Hoa Kỳ', 'American'),
  ('SG', 'SGP', 'Singapore', 'Singapore', 'Singapore', 'Singaporean'),
  ('FR', 'FRA', 'Pháp', 'France', 'Pháp', 'French')
ON CONFLICT DO NOTHING;
INSERT INTO core.currency (currency_code, name_vi, name_en, minor_units) VALUES
  ('VND', 'Đồng Việt Nam', 'Vietnamese dong', 0),
  ('USD', 'Đô la Mỹ', 'US dollar', 2),
  ('JPY', 'Yên Nhật', 'Japanese yen', 0),
  ('EUR', 'Euro', 'Euro', 2)
ON CONFLICT DO NOTHING;

-- 2. Administrative units (sample; provinces + communes valid from 2025-07-01) -------------------------------------
INSERT INTO core.admin_unit (admin_unit_code, level, name_vi, name_en, parent_code, valid_from) VALUES
  ('01', 'PROVINCE', 'Thành phố Hà Nội', 'Ha Noi', NULL, '2025-07-01'),
  ('79', 'PROVINCE', 'Thành phố Hồ Chí Minh', 'Ho Chi Minh City', NULL, '2025-07-01'),
  ('48', 'PROVINCE', 'Thành phố Đà Nẵng', 'Da Nang', NULL, '2025-07-01'),
  ('31', 'PROVINCE', 'Thành phố Hải Phòng', 'Hai Phong', NULL, '2025-07-01'),
  ('24', 'PROVINCE', 'Tỉnh Bắc Ninh', 'Bac Ninh', NULL, '2025-07-01'),
  ('92', 'PROVINCE', 'Thành phố Cần Thơ', 'Can Tho', NULL, '2025-07-01')
ON CONFLICT DO NOTHING;
INSERT INTO core.admin_unit (admin_unit_code, level, name_vi, name_en, parent_code, valid_from) VALUES
  ('00004', 'COMMUNE', 'Phường Ba Đình', 'Ba Dinh Ward', '01', '2025-07-01'),
  ('00008', 'COMMUNE', 'Phường Ngọc Hà', 'Ngoc Ha Ward', '01', '2025-07-01'),
  ('00070', 'COMMUNE', 'Phường Hoàn Kiếm', 'Hoan Kiem Ward', '01', '2025-07-01'),
  ('00091', 'COMMUNE', 'Phường Cửa Nam', 'Cua Nam Ward', '01', '2025-07-01'),
  ('09619', 'COMMUNE', 'Xã Ba Vì', 'Ba Vi Commune', '01', '2025-07-01'),
  ('26734', 'COMMUNE', 'Phường Sài Gòn', 'Sai Gon Ward', '79', '2025-07-01'),
  ('26740', 'COMMUNE', 'Phường Tân Định', 'Tan Dinh Ward', '79', '2025-07-01'),
  ('26743', 'COMMUNE', 'Phường Bến Thành', 'Ben Thanh Ward', '79', '2025-07-01'),
  ('20194', 'COMMUNE', 'Phường Hải Châu', 'Hai Chau Ward', '48', '2025-07-01'),
  ('11311', 'COMMUNE', 'Phường Hồng Bàng', 'Hong Bang Ward', '31', '2025-07-01'),
  ('07210', 'COMMUNE', 'Phường Kinh Bắc', 'Kinh Bac Ward', '24', '2025-07-01'),
  ('31117', 'COMMUNE', 'Phường Ninh Kiều', 'Ninh Kieu Ward', '92', '2025-07-01')
ON CONFLICT DO NOTHING;

-- 3. Banks ---------------------------------------------------------------------------------------------------------
INSERT INTO core.bank (bank_code, bin, swift_code, short_name, name_vi, name_en) VALUES
  ('VCB',  '970436', 'BFTVVNVX', 'Vietcombank', 'Ngân hàng TMCP Ngoại thương Việt Nam', 'Joint Stock Commercial Bank for Foreign Trade of Vietnam'),
  ('BIDV', '970418', 'BIDVVNVX', 'BIDV', 'Ngân hàng TMCP Đầu tư và Phát triển Việt Nam', 'Bank for Investment and Development of Vietnam'),
  ('CTG',  '970415', 'ICBVVNVX', 'VietinBank', 'Ngân hàng TMCP Công thương Việt Nam', 'Vietnam Joint Stock Commercial Bank for Industry and Trade'),
  ('TCB',  '970407', 'VTCBVNVX', 'Techcombank', 'Ngân hàng TMCP Kỹ thương Việt Nam', 'Vietnam Technological and Commercial Joint Stock Bank'),
  ('ACB',  '970416', 'ASCBVNVX', 'ACB', 'Ngân hàng TMCP Á Châu', 'Asia Commercial Joint Stock Bank'),
  ('MB',   '970422', 'MSCBVNVX', 'MB', 'Ngân hàng TMCP Quân đội', 'Military Commercial Joint Stock Bank')
ON CONFLICT DO NOTHING;
INSERT INTO core.bank_branch (bank_code, branch_code, name_vi, province_code) VALUES
  ('VCB', 'VCB-HN', 'Vietcombank Chi nhánh Hà Nội', '01'),
  ('VCB', 'VCB-HCM', 'Vietcombank Chi nhánh TP Hồ Chí Minh', '79'),
  ('TCB', 'TCB-HN', 'Techcombank Hội sở', '01'),
  ('BIDV', 'BIDV-HCM', 'BIDV Chi nhánh Sài Gòn', '79')
ON CONFLICT DO NOTHING;

-- 4. Code lists and global values ----------------------------------------------------------------------------------
INSERT INTO core.code_list (list_code, name_vi, name_en, is_tenant_extensible, is_statutory, owner_module) VALUES
  ('GENDER', 'Giới tính', 'Gender', false, true, 'hr'),
  ('ETHNICITY', 'Dân tộc', 'Ethnicity', false, true, 'hr'),
  ('RELIGION', 'Tôn giáo', 'Religion', false, true, 'hr'),
  ('HI_FACILITY', 'Nơi đăng ký KCB ban đầu', 'Initial health-care facility', true, false, 'hr'),
  ('QUALIFICATION_LEVEL', 'Trình độ chuyên môn', 'Qualification level', true, false, 'hr'),
  ('VOCATIONAL_SKILL_LEVEL', 'Bậc kỹ năng nghề', 'Vocational skill level', true, false, 'hr'),
  ('GENERAL_EDUCATION', 'Trình độ phổ thông', 'General education', false, false, 'hr'),
  ('VISA_TYPE', 'Loại thị thực', 'Visa type', false, true, 'hr'),
  ('RELATIONSHIP', 'Quan hệ gia đình', 'Family relationship', false, false, 'hr'),
  ('JOB_HAZARD_CATEGORY', 'Nghề nặng nhọc, độc hại', 'Hazardous job category', false, true, 'core')
ON CONFLICT DO NOTHING;

INSERT INTO core.code_value (tenant_id, list_code, value_code, label_vi, label_en, sort_order)
SELECT sys.global_tenant_id(), v.list, v.code, v.vi, v.en, v.ord
  FROM (VALUES
    ('GENDER', 'M', 'Nam', 'Male', 1),
    ('GENDER', 'F', 'Nữ', 'Female', 2),
    ('ETHNICITY', 'KINH', 'Kinh', 'Kinh', 1),
    ('ETHNICITY', 'TAY', 'Tày', 'Tay', 2),
    ('ETHNICITY', 'THAI', 'Thái', 'Thai', 3),
    ('ETHNICITY', 'MUONG', 'Mường', 'Muong', 4),
    ('ETHNICITY', 'HOA', 'Hoa', 'Hoa', 5),
    ('ETHNICITY', 'KHMER', 'Khmer', 'Khmer', 6),
    ('RELIGION', 'NONE', 'Không', 'None', 1),
    ('RELIGION', 'BUDDHISM', 'Phật giáo', 'Buddhism', 2),
    ('RELIGION', 'CATHOLIC', 'Công giáo', 'Catholicism', 3),
    ('RELIGION', 'PROTESTANT', 'Tin lành', 'Protestantism', 4),
    ('RELIGION', 'CAODAI', 'Cao Đài', 'Cao Dai', 5),
    ('HI_FACILITY', '01-001', 'Bệnh viện Bạch Mai', 'Bach Mai Hospital', 1),
    ('HI_FACILITY', '01-002', 'Bệnh viện Hữu nghị Việt Đức', 'Viet Duc Hospital', 2),
    ('HI_FACILITY', '79-002', 'Bệnh viện Chợ Rẫy', 'Cho Ray Hospital', 3),
    ('HI_FACILITY', '48-001', 'Bệnh viện Đà Nẵng', 'Da Nang Hospital', 4),
    ('QUALIFICATION_LEVEL', 'HIGH_SCHOOL', 'Trung học phổ thông', 'High school', 1),
    ('QUALIFICATION_LEVEL', 'INTERMEDIATE', 'Trung cấp', 'Intermediate', 2),
    ('QUALIFICATION_LEVEL', 'COLLEGE', 'Cao đẳng', 'College', 3),
    ('QUALIFICATION_LEVEL', 'UNIVERSITY', 'Đại học', 'University', 4),
    ('QUALIFICATION_LEVEL', 'MASTER', 'Thạc sĩ', 'Master', 5),
    ('QUALIFICATION_LEVEL', 'DOCTOR', 'Tiến sĩ', 'Doctorate', 6),
    ('VOCATIONAL_SKILL_LEVEL', 'L1', 'Bậc 1', 'Level 1', 1),
    ('VOCATIONAL_SKILL_LEVEL', 'L2', 'Bậc 2', 'Level 2', 2),
    ('VOCATIONAL_SKILL_LEVEL', 'L3', 'Bậc 3', 'Level 3', 3),
    ('GENERAL_EDUCATION', '9/12', '9/12', '9/12', 1),
    ('GENERAL_EDUCATION', '12/12', '12/12', '12/12', 2),
    ('VISA_TYPE', 'LD1', 'LĐ1 - Lao động (miễn GPLĐ)', 'LD1 - Work (permit exempt)', 1),
    ('VISA_TYPE', 'LD2', 'LĐ2 - Lao động (có GPLĐ)', 'LD2 - Work (with permit)', 2),
    ('VISA_TYPE', 'DN1', 'DN1 - Làm việc với doanh nghiệp', 'DN1 - Business', 3),
    ('VISA_TYPE', 'DT1', 'ĐT1 - Nhà đầu tư', 'DT1 - Investor', 4),
    ('RELATIONSHIP', 'SPOUSE', 'Vợ/Chồng', 'Spouse', 1),
    ('RELATIONSHIP', 'CHILD', 'Con', 'Child', 2),
    ('RELATIONSHIP', 'FATHER', 'Bố', 'Father', 3),
    ('RELATIONSHIP', 'MOTHER', 'Mẹ', 'Mother', 4),
    ('RELATIONSHIP', 'SIBLING', 'Anh/Chị/Em', 'Sibling', 5),
    ('RELATIONSHIP', 'OTHER', 'Khác', 'Other', 9),
    ('JOB_HAZARD_CATEGORY', 'IV', 'Loại IV - Nặng nhọc, độc hại', 'Category IV - heavy/hazardous', 1),
    ('JOB_HAZARD_CATEGORY', 'V', 'Loại V - Đặc biệt nặng nhọc, độc hại', 'Category V - especially heavy/hazardous', 2),
    ('JOB_HAZARD_CATEGORY', 'VI', 'Loại VI - Đặc biệt nặng nhọc, độc hại', 'Category VI - especially heavy/hazardous', 3)
  ) AS v(list, code, vi, en, ord)
ON CONFLICT DO NOTHING;

-- 5. Dev tenant: number series (as PeopleFixtures.numberSeries in the tests) --------------------------------------
SELECT set_config('app.tenant_id', '01900000-0000-7000-8000-000000000001', true),
       set_config('app.request_id', 'seed-sample-reference', true);
INSERT INTO core.sequence_definition (tenant_id, code, name, scope_level, pattern, reset_rule) VALUES
  ('01900000-0000-7000-8000-000000000001', 'EMPLOYEE_NO', 'Mã nhân viên', 'TENANT', 'NV{SEQ:5}', 'NEVER'),
  ('01900000-0000-7000-8000-000000000001', 'CONTRACT_NO', 'Số hợp đồng', 'LEGAL_ENTITY', '{SEQ:3}/{YYYY}/HĐLĐ-{LE}', 'YEARLY'),
  ('01900000-0000-7000-8000-000000000001', 'DECISION_NO', 'Số quyết định', 'LEGAL_ENTITY', '{SEQ:3}/{YYYY}/QĐ-{LE}', 'YEARLY')
ON CONFLICT (tenant_id, code) DO NOTHING;

COMMIT;
