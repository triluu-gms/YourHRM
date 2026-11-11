// Core sample data: company profile, legal entities, locations, org tree, catalogues, roles, users, workflow.
import { ensureKeycloakUser, section, step } from './lib.mjs';

export const IDS = {
  tenant: '01900000-0000-7000-8000-000000000001',
  leHQ: '01900000-0000-7000-8000-000000000101',
  leMFG: '01900000-0000-7000-8000-000000000102',
  locHN: '01900000-0000-7000-8000-000000000201',
  typeCompany: '01900000-0000-7000-8000-000000000301',
  typeDepartment: '01900000-0000-7000-8000-000000000302',
  ouRoot: '01900000-0000-7000-8000-000000000401',
  ouHR: '01900000-0000-7000-8000-000000000402',
  ouFIN: '01900000-0000-7000-8000-000000000403',
  roleOwner: '01900000-0000-7000-8000-000000000501',
};

/** Logins of the sample users (Keycloak realm "yourhrm"). Core user + role are created here; ESS links in people.mjs. */
export const USERS = {
  hrAdmin:   { username: 'hr.admin',    password: 'Hr@12345',  firstName: 'Thu',  lastName: 'Phạm Thị',   email: 'hr.admin@dev.yourhrm.local' },
  hrViewer:  { username: 'hr.viewer',   password: 'Hr@12345',  firstName: 'Lan',  lastName: 'Đỗ Thị',     email: 'hr.viewer@dev.yourhrm.local' },
  itManager: { username: 'manager.it',  password: 'Mgr@12345', firstName: 'Hùng', lastName: 'Trần Văn',   email: 'hung.tran@dev.yourhrm.local' },
  salesManager: { username: 'manager.sales', password: 'Mgr@12345', firstName: 'Mai', lastName: 'Lê Thị', email: 'mai.le@dev.yourhrm.local' },
  employee:  { username: 'employee',    password: 'employee_dev', firstName: 'An', lastName: 'Nguyễn Văn', email: 'employee@dev.yourhrm.local' },
  employee2: { username: 'employee2',   password: 'Emp@12345', firstName: 'Bình', lastName: 'Hoàng Thị',  email: 'binh.hoang@dev.yourhrm.local' },
};

/** Sample admin units exist from the two-level structure of 2025-07-01, so addresses are versions from that day. */
const ADDR_FROM = '2025-07-01';
async function addressVersion(api, base, attributes) {
  const versions = await api.get(`${base}/versions`);
  if (versions.some((v) => v.validFrom === ADDR_FROM)) return;
  await api.post(`${base}/versions`, { effectiveFrom: ADDR_FROM, attributes });
}

const byCode = (list, code) => (Array.isArray(list) ? list : list.items ?? []).find((x) => x.code === code);

export async function seedCore(api, ctx) {
  section('Core: company group and system roles');
  await step('company group profile (+ sync system roles HR_ADMIN / ESS / LINE_MANAGER)', async () => {
    const g = await api.get('/core/company-group');
    await api.put('/core/company-group', {
      name: 'Tập đoàn YourHRM Dev', defaultLocale: 'vi-VN', timeZone: 'Asia/Ho_Chi_Minh', currencyCode: 'VND',
      version: g.version,
    });
  });

  section('Core: legal entities');
  const leAttrs = {
    [IDS.leHQ]: {
      legalNameVi: 'Công ty TNHH YourHRM Dev', legalNameEn: 'YourHRM Dev Co., Ltd.', shortName: 'YourHRM HQ',
      taxCode: '0101234567', siUnitCode: 'TA0101A', businessRegNo: '0101234567', businessRegDate: '2019-05-02',
      businessRegIssuer: 'Sở KH&ĐT Hà Nội', phone: '+84 24 3936 1234', email: 'hr@hq.dev.yourhrm.local',
      adminUnitCode: '00070', addressLine: '15 Tràng Tiền', legalRepName: 'Nguyễn Văn Minh', legalRepTitle: 'Tổng Giám đốc',
      fiscalYearStartMonth: 1, taxAuthorityCode: 'CT-HN',
    },
    [IDS.leMFG]: {
      legalNameVi: 'Công ty TNHH Sản xuất Dev', legalNameEn: 'Dev Manufacturing Co., Ltd.', shortName: 'YourHRM MFG',
      taxCode: '0107654321', siUnitCode: 'TB2401B', businessRegNo: '0107654321', businessRegDate: '2020-03-15',
      businessRegIssuer: 'Sở KH&ĐT Bắc Ninh', phone: '+84 222 381 5678', email: 'hr@mfg.dev.yourhrm.local',
      adminUnitCode: '07210', addressLine: 'KCN Quế Võ, Lô B2', legalRepName: 'Phạm Quốc Bảo', legalRepTitle: 'Giám đốc',
      fiscalYearStartMonth: 1, taxAuthorityCode: 'CT-BN',
    },
  };
  for (const [id, attributes] of Object.entries(leAttrs)) {
    await step(`legal entity ${attributes.shortName}: registration + address (version from ${ADDR_FROM})`, () =>
      addressVersion(api, `/core/legal-entities/${id}`, attributes));
  }
  ctx.leSGN = await step('legal entity SGN (YourHRM Sài Gòn)', async () => {
    const found = byCode(await api.get('/core/legal-entities'), 'SGN');
    if (found) return found.id;
    const attributes = {
      legalNameVi: 'Công ty TNHH YourHRM Sài Gòn', legalNameEn: 'YourHRM Saigon Co., Ltd.', shortName: 'YourHRM SGN',
      taxCode: '0312345678', siUnitCode: 'TC7901C', businessRegNo: '0312345678', businessRegDate: '2021-11-20',
      businessRegIssuer: 'Sở KH&ĐT TP HCM', phone: '+84 28 3822 9999', email: 'hr@sgn.dev.yourhrm.local',
      adminUnitCode: '26734', addressLine: '72 Lê Thánh Tôn', legalRepName: 'Võ Thanh Tâm', legalRepTitle: 'Giám đốc',
      fiscalYearStartMonth: 1, taxAuthorityCode: 'CT-HCM',
    };
    const r = await api.post('/core/legal-entities', { code: 'SGN', validFrom: '2022-01-01',
      attributes: { ...attributes, adminUnitCode: null, addressLine: null } });
    await addressVersion(api, `/core/legal-entities/${r.id}`, attributes);
    return r.id;
  });

  section('Core: locations');
  await step(`location HN-HO: address (version from ${ADDR_FROM})`, () => addressVersion(api, `/core/locations/${IDS.locHN}`, {
    name: 'Trụ sở Hà Nội', locationType: 'HEAD_OFFICE', adminUnitCode: '00070', addressLine: '15 Tràng Tiền, Hoàn Kiếm',
    timeZone: 'Asia/Ho_Chi_Minh', latitude: 21.0245, longitude: 105.8550, geofenceRadiusM: 150 }));
  const locations = [
    ['HN-BV', IDS.leHQ, '2023-06-01', { name: 'Kho Ba Vì', locationType: 'STORE', adminUnitCode: '09619', addressLine: 'Thôn Đông Phú, Ba Vì', remoteArea: true }],
    ['DN-BR', IDS.leHQ, '2023-01-01', { name: 'Chi nhánh Đà Nẵng', locationType: 'BRANCH', adminUnitCode: '20194', addressLine: '88 Bạch Đằng, Hải Châu' }],
    ['BN-FAC', IDS.leMFG, '2020-03-15', { name: 'Nhà máy Bắc Ninh', locationType: 'FACTORY', adminUnitCode: '07210', addressLine: 'KCN Quế Võ, Lô B2' }],
    ['HCM-HO', 'SGN', '2022-01-01', { name: 'Văn phòng TP HCM', locationType: 'HEAD_OFFICE', adminUnitCode: '26734', addressLine: '72 Lê Thánh Tôn, Sài Gòn' }],
  ];
  ctx.loc = { 'HN-HO': IDS.locHN };
  const existingLocs = await api.get('/core/locations');
  for (const [code, le, validFrom, attrs] of locations) {
    ctx.loc[code] = await step(`location ${code}`, async () => {
      const found = byCode(existingLocs, code);
      if (found) return found.id;
      const legalEntityId = le === 'SGN' ? ctx.leSGN : le;
      const attributes = { timeZone: 'Asia/Ho_Chi_Minh', ...attrs };
      const { id } = await api.post('/core/locations', { code, legalEntityId, validFrom,
        attributes: { ...attributes, adminUnitCode: null, addressLine: null } });
      await addressVersion(api, `/core/locations/${id}`, attributes);
      return id;
    });
  }

  section('Core: cost centres');
  ctx.cc = {};
  const ccs = [
    ['CC-HQ-ADM', IDS.leHQ, 'Hành chính - Nhân sự', '6421'],
    ['CC-HQ-IT', IDS.leHQ, 'Công nghệ thông tin', '6422'],
    ['CC-HQ-SAL', IDS.leHQ, 'Kinh doanh', '6411'],
    ['CC-HQ-FIN', IDS.leHQ, 'Tài chính - Kế toán', '6423'],
    ['CC-MFG-PRD', IDS.leMFG, 'Sản xuất trực tiếp', '6221'],
    ['CC-MFG-QC', IDS.leMFG, 'Quản lý chất lượng', '6271'],
    ['CC-SGN-SAL', 'SGN', 'Kinh doanh miền Nam', '6411'],
  ];
  const existingCc = await api.get('/core/cost-centres');
  for (const [code, le, name, glSegment] of ccs) {
    ctx.cc[code] = await step(`cost centre ${code}`, async () => {
      const found = byCode(existingCc, code);
      if (found) return found.id;
      return (await api.post('/core/cost-centres', { legalEntityId: le === 'SGN' ? ctx.leSGN : le, code, name, glSegment,
        validFrom: '2020-01-01' })).id;
    });
  }

  section('Core: org unit types and org tree');
  ctx.type = { COMPANY: IDS.typeCompany, DEPARTMENT: IDS.typeDepartment };
  const types = await api.get('/core/org-unit-types');
  for (const [code, nameVi, nameEn, levelHint] of [['DIVISION', 'Khối', 'Division', 2], ['TEAM', 'Nhóm', 'Team', 3], ['FACTORY', 'Nhà máy', 'Factory', 2], ['WORKSHOP', 'Xưởng', 'Workshop', 3]]) {
    ctx.type[code] = await step(`org unit type ${code}`, async () => {
      const found = byCode(types, code);
      if (found) return found.id;
      return (await api.post('/core/org-unit-types', { code, nameVi, nameEn, levelHint, sortOrder: levelHint })).id;
    });
  }

  // Users first, so org units can name their managers (line-manager approvals, US-CORE-036).
  await seedUsers(api, ctx);

  ctx.ou = { DEV: IDS.ouRoot, 'DEV-HR': IDS.ouHR, 'DEV-FIN': IDS.ouFIN };
  const units = [
    // code, parent, nameVi, nameEn, type, le, location, costCentre, manager
    ['DEV-BOD', 'DEV', 'Ban Tổng Giám đốc', 'Board of Directors', 'DEPARTMENT', IDS.leHQ, 'HN-HO', 'CC-HQ-ADM', null],
    ['DEV-IT', 'DEV', 'Khối Công nghệ', 'Technology Division', 'DIVISION', IDS.leHQ, 'HN-HO', 'CC-HQ-IT', 'itManager'],
    ['DEV-IT-DEV', 'DEV-IT', 'Nhóm Phát triển phần mềm', 'Software Development Team', 'TEAM', IDS.leHQ, 'HN-HO', 'CC-HQ-IT', 'itManager'],
    ['DEV-IT-QA', 'DEV-IT', 'Nhóm Kiểm thử', 'QA Team', 'TEAM', IDS.leHQ, 'HN-HO', 'CC-HQ-IT', null],
    ['DEV-SALES', 'DEV', 'Phòng Kinh doanh', 'Sales Department', 'DEPARTMENT', IDS.leHQ, 'HN-HO', 'CC-HQ-SAL', 'salesManager'],
    ['DEV-SALES-DN', 'DEV-SALES', 'Nhóm Kinh doanh Đà Nẵng', 'Da Nang Sales Team', 'TEAM', IDS.leHQ, 'DN-BR', 'CC-HQ-SAL', null],
    ['DEV-WH', 'DEV', 'Kho vận Ba Vì', 'Ba Vi Warehouse', 'DEPARTMENT', IDS.leHQ, 'HN-BV', 'CC-HQ-ADM', null],
    ['MFG', null, 'Công ty Sản xuất Dev', 'Dev Manufacturing', 'COMPANY', IDS.leMFG, 'BN-FAC', 'CC-MFG-PRD', null],
    ['MFG-PRD', 'MFG', 'Xưởng Sản xuất', 'Production Workshop', 'WORKSHOP', IDS.leMFG, 'BN-FAC', 'CC-MFG-PRD', null],
    ['MFG-QC', 'MFG', 'Phòng Quản lý chất lượng', 'Quality Control', 'DEPARTMENT', IDS.leMFG, 'BN-FAC', 'CC-MFG-QC', null],
    ['SGN', null, 'Công ty YourHRM Sài Gòn', 'YourHRM Saigon', 'COMPANY', 'SGN', 'HCM-HO', 'CC-SGN-SAL', null],
    ['SGN-SALES', 'SGN', 'Phòng Kinh doanh miền Nam', 'Southern Sales', 'DEPARTMENT', 'SGN', 'HCM-HO', 'CC-SGN-SAL', 'salesManager'],
  ];
  const tree = await api.get('/core/org-units');
  for (const [code, parent, nameVi, nameEn, type, le, loc, cc, mgr] of units) {
    ctx.ou[code] = await step(`org unit ${code} (${nameVi})`, async () => {
      const found = byCode(tree, code);
      if (found) return found.id;
      return (await api.post('/core/org-units', { code, validFrom: le === 'SGN' ? '2022-01-01' : '2020-01-01', attributes: {
        nameVi, nameEn, typeId: ctx.type[type], parentId: parent ? ctx.ou[parent] : null,
        legalEntityId: le === 'SGN' ? ctx.leSGN : le, locationId: ctx.loc[loc], costCentreId: ctx.cc[cc],
        managerUserId: mgr ? ctx.users[mgr]?.id : null, sortOrder: 1 } })).id;
    });
  }
  await step('org units DEV-HR / DEV-FIN: cost centre and manager (version from 2024-01-01)', async () => {
    for (const [code, cc, mgr] of [['DEV-HR', 'CC-HQ-ADM', 'hrAdmin'], ['DEV-FIN', 'CC-HQ-FIN', null]]) {
      const id = ctx.ou[code];
      const versions = await api.get(`/core/org-units/${id}/versions`);
      if (versions.some((v) => v.validFrom === '2024-01-01')) continue;
      const cur = versions.at(-1);
      await api.post(`/core/org-units/${id}/versions`, { effectiveFrom: '2024-01-01', attributes: {
        nameVi: cur.nameVi, nameEn: cur.nameEn, typeId: cur.typeId, parentId: cur.parentId, legalEntityId: cur.legalEntityId,
        locationId: IDS.locHN, costCentreId: ctx.cc[cc], managerUserId: mgr ? ctx.users[mgr]?.id : null, sortOrder: 2 } });
    }
  });

  section('Core: employee categories');
  ctx.category = {};
  const f = (o) => ({ probation: false, seasonal: false, intern: false, expat: false, collaborator: false, countsInHeadcount: true, ...o });
  const cats = [
    ['OFFICIAL', 'Nhân viên chính thức', 'Permanent staff', f({})],
    ['PROBATION', 'Nhân viên thử việc', 'Probationer', f({ probation: true })],
    ['SEASONAL', 'Lao động thời vụ', 'Seasonal worker', f({ seasonal: true })],
    ['INTERN', 'Thực tập sinh', 'Intern', f({ intern: true, countsInHeadcount: false })],
    ['EXPAT', 'Người lao động nước ngoài', 'Expatriate', f({ expat: true })],
    ['COLLABORATOR', 'Cộng tác viên', 'Collaborator', f({ collaborator: true, countsInHeadcount: false })],
  ];
  const existingCats = await api.get('/core/employee-categories');
  for (const [i, [code, nameVi, nameEn, flags]] of cats.entries()) {
    ctx.category[code] = await step(`employee category ${code}`, async () => {
      const found = byCode(existingCats, code);
      if (found) return found.id;
      return (await api.post('/core/employee-categories', { code, nameVi, nameEn, flags, sortOrder: i + 1 })).id;
    });
  }

  section('Core: job catalogue');
  ctx.grade = {};
  const structure = await step('grade structure STD (Ngạch bậc chuẩn)', async () => {
    const found = byCode(await api.get('/core/grade-structures'), 'STD');
    if (found) return found.id;
    return (await api.post('/core/grade-structures', { code: 'STD', name: 'Ngạch bậc chuẩn' })).id;
  });
  if (structure) {
    const grades = await api.get(`/core/grade-structures/${structure}/grades`);
    for (const [rank, code, name] of [[1, 'G1', 'Bậc 1 - Nhân viên'], [2, 'G2', 'Bậc 2 - Chuyên viên'], [3, 'G3', 'Bậc 3 - Chuyên viên chính'],
      [4, 'G4', 'Bậc 4 - Trưởng nhóm'], [5, 'G5', 'Bậc 5 - Trưởng phòng'], [6, 'G6', 'Bậc 6 - Giám đốc']]) {
      ctx.grade[code] = await step(`grade ${code}`, async () => {
        const found = byCode(grades, code);
        if (found) return found.id;
        return (await api.post(`/core/grade-structures/${structure}/grades`, { code, name, rankOrder: rank, validFrom: '2020-01-01' })).id;
      });
    }
  }
  ctx.family = {};
  const families = await api.get('/core/job-families');
  for (const [code, nameVi, nameEn] of [['IT', 'Công nghệ thông tin', 'Information technology'], ['SALES', 'Kinh doanh', 'Sales'],
    ['HR', 'Nhân sự - Hành chính', 'HR & Admin'], ['FIN', 'Tài chính - Kế toán', 'Finance'], ['PROD', 'Sản xuất', 'Production'], ['MGMT', 'Quản lý', 'Management']]) {
    ctx.family[code] = await step(`job family ${code}`, async () => {
      const found = byCode(families, code);
      if (found) return found.id;
      return (await api.post('/core/job-families', { code, nameVi, nameEn })).id;
    });
  }
  ctx.job = {};
  const titles = await api.get('/core/job-titles');
  const jobs = [
    ['CEO', 'MGMT', 'Tổng Giám đốc', 'Chief Executive Officer', 'G6', 'G6', '1120', null],
    ['DEPT_HEAD', 'MGMT', 'Trưởng phòng', 'Head of Department', 'G5', 'G5', '1219', null],
    ['SWE', 'IT', 'Kỹ sư phần mềm', 'Software Engineer', 'G2', 'G4', '2512', null],
    ['QA_ENG', 'IT', 'Kỹ sư kiểm thử', 'QA Engineer', 'G2', 'G3', '2519', null],
    ['SALES_EXEC', 'SALES', 'Chuyên viên kinh doanh', 'Sales Executive', 'G1', 'G3', '3322', null],
    ['HR_SPEC', 'HR', 'Chuyên viên nhân sự', 'HR Specialist', 'G2', 'G3', '2423', null],
    ['ACCOUNTANT', 'FIN', 'Kế toán viên', 'Accountant', 'G2', 'G3', '2411', null],
    ['OPERATOR', 'PROD', 'Công nhân vận hành máy', 'Machine Operator', 'G1', 'G2', '8189', null],
    ['WELDER', 'PROD', 'Thợ hàn', 'Welder', 'G1', 'G3', '7212', 'IV'],
    ['QC_INSP', 'PROD', 'Nhân viên kiểm tra chất lượng', 'QC Inspector', 'G1', 'G2', '7543', null],
    ['WAREHOUSE', 'PROD', 'Thủ kho', 'Storekeeper', 'G1', 'G2', '4321', null],
  ];
  for (const [code, fam, nameVi, nameEn, gMin, gMax, occupationCode, hazardousCategory] of jobs) {
    ctx.job[code] = await step(`job title ${code}`, async () => {
      const found = byCode(titles, code);
      if (found) return found.id;
      return (await api.post('/core/job-titles', { code, attributes: { jobFamilyId: ctx.family[fam], nameVi, nameEn,
        description: `${nameVi} - mô tả công việc mẫu`, defaultGradeMinId: ctx.grade[gMin], defaultGradeMaxId: ctx.grade[gMax],
        occupationCode, hazardousCategory } })).id;
    });
  }

  section('Core: workflow definition');
  await step('workflow hr.BANK_ACCOUNT_CHANGE: line manager then HR admin', async () => {
    const defs = await api.get('/core/workflow-definitions');
    if ((defs.items ?? defs).some((d) => d.code === 'BANK_2STEP')) return;
    await api.post('/core/workflow-definitions', {
      processTypeCode: 'hr.BANK_ACCOUNT_CHANGE', code: 'BANK_2STEP', nameVi: 'Đổi tài khoản lương: QLTT + HR',
      nameEn: 'Bank account change: manager + HR', matchPriority: 10, conditions: {}, validFrom: '2020-01-01',
      steps: [
        { stepKey: 'manager', nameVi: 'Quản lý trực tiếp duyệt', nameEn: 'Line manager', resolver: { type: 'LINE_MANAGER', params: {} }, allowReturn: true },
        { stepKey: 'hr', nameVi: 'Nhân sự duyệt', nameEn: 'HR admin', resolver: { type: 'ROLE_IN_SCOPE', params: { roleCode: 'HR_ADMIN' } }, allowReturn: true },
      ],
      onResolverFailure: 'LE_HR_ADMIN',
    });
  });
}

async function seedUsers(api, ctx) {
  section('Core: roles and users');
  const roles = await api.get('/core/roles');
  ctx.role = Object.fromEntries(roles.map((r) => [r.code, r.id]));
  const perms = (await api.get('/core/permissions')).map((p) => p.code);
  const viewOnly = perms.filter((c) => /\.(view|view_[a-z_]+)$/.test(c) && !c.includes('sensitive'));
  ctx.role.HR_VIEWER = await step(`custom role HR_VIEWER (${viewOnly.length} view permissions, fields masked)`, async () => {
    if (ctx.role.HR_VIEWER) return ctx.role.HR_VIEWER;
    return (await api.post('/core/roles', { code: 'HR_VIEWER', nameVi: 'Nhân sự - chỉ xem', nameEn: 'HR viewer (read only)',
      permissions: viewOnly.map((code) => ({ code, fieldMaskLevel: 'PII' })) })).id;
  });
  ctx.role.PAYROLL_PREP = await step('custom role PAYROLL_PREP (copy of HR_VIEWER)', async () => {
    if (ctx.role.PAYROLL_PREP) return ctx.role.PAYROLL_PREP;
    return (await api.post(`/core/roles/${ctx.role.HR_VIEWER}/copy`, { code: 'PAYROLL_PREP', nameVi: 'Chuẩn bị lương',
      nameEn: 'Payroll preparation' })).id;
  });

  ctx.users = {};
  const existing = (await api.get('/core/users', { size: 200 })).items;
  const grants = {
    hrAdmin: [['HR_ADMIN', 'TENANT', []]],
    hrViewer: [['HR_VIEWER', 'LEGAL_ENTITY', [IDS.leHQ]]],
    // ESS and LINE_MANAGER are derived from the employment / org-unit manager (code derived-role): no grant here.
    itManager: [],
    salesManager: [],
    employee: [],
    employee2: [],
  };
  for (const [key, u] of Object.entries(USERS)) {
    ctx.users[key] = await step(`user ${u.username} (${grants[key].map((g) => g[0]).join(', ') || 'derived roles only'})`, async () => {
      const identityId = await ensureKeycloakUser(u);
      let user = existing.find((x) => x.login === u.email);
      if (!user) {
        user = await api.post('/core/users', { login: u.email, displayName: `${u.lastName} ${u.firstName}`, email: u.email,
          locale: 'vi-VN', identityId });
      }
      const current = await api.get(`/core/users/${user.id}/role-assignments`);
      for (const [roleCode, scopeType, scopeIds] of grants[key]) {
        const roleId = ctx.role[roleCode];
        if (!roleId) throw new Error(`role ${roleCode} missing`);
        if ((current.items ?? current).some((a) => a.roleId === roleId)) continue;
        await api.post(`/core/users/${user.id}/role-assignments`, { roleId, scopeType, scopeIds, includeSubtree: true,
          validFrom: '2020-01-01' });
      }
      return { id: user.id, identityId, ...u };
    });
  }
}
