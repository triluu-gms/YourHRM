// People sample data: hires (approved through the real workflow), person details, contracts and their lifecycle,
// assignment / salary changes, decisions, compliance, family & PIT dependants, bank accounts, ESS links and requests,
// separations and statutory reports. Some requests are deliberately LEFT PENDING so every inbox has work in it.
import { execFileSync } from 'node:child_process';
import { IDS, USERS } from './core.mjs';
import { ApiError, TODAY, addDays, addMonths, client, section, step } from './lib.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SIGNATORY = { signatoryName: 'Nguyễn Văn Minh', signatoryTitle: 'Tổng Giám đốc' };

/** Next Monday at least `minDays` from today (temporary transfers need >= 3 working days notice). */
function nextMonday(minDays) {
  let d = addDays(TODAY, minDays);
  while (new Date(`${d}T00:00:00Z`).getUTCDay() !== 1) d = addDays(d, 1);
  return d;
}

// key, family, middle, given, gender, birth, CCCD, SI no, LE, org unit, location, job, grade, hire date, contract,
// base salary, manager key, ESS login
const PEOPLE = [
  ['hung', 'Trần', 'Văn', 'Hùng', 'M', '1982-02-14', '001082004521', '0182004521', 'HQ', 'DEV-IT', 'HN-HO', 'DEPT_HEAD', 'G5', '2025-07-01', { contractTypeCode: 'INDEFINITE' }, 45_000_000, null, 'itManager'],
  ['mai', 'Lê', 'Thị', 'Mai', 'F', '1985-09-03', '001185007733', '0185007733', 'HQ', 'DEV-SALES', 'HN-HO', 'DEPT_HEAD', 'G5', '2025-07-01', { contractTypeCode: 'INDEFINITE' }, 40_000_000, null, 'salesManager'],
  ['thu', 'Phạm', 'Thị', 'Thu', 'F', '1990-11-21', '001190011876', '0190011876', 'HQ', 'DEV-HR', 'HN-HO', 'HR_SPEC', 'G3', '2025-07-01', { contractTypeCode: 'INDEFINITE' }, 28_000_000, null, 'hrAdmin'],
  ['duc', 'Phan', 'Văn', 'Đức', 'M', '1988-06-30', '001088019244', '0188019244', 'HQ', 'DEV-IT-DEV', 'HN-HO', 'SWE', 'G4', '2025-07-01', { contractTypeCode: 'INDEFINITE' }, 35_000_000, 'hung', null],
  ['ngoc', 'Cao', 'Thị', 'Ngọc', 'F', '1987-01-09', '001187022310', '0187022310', 'HQ', 'DEV-FIN', 'HN-HO', 'ACCOUNTANT', 'G3', '2025-07-15', { contractTypeCode: 'INDEFINITE' }, 24_000_000, null, null],
  ['an', 'Nguyễn', 'Văn', 'An', 'M', '1994-04-12', '001094012345', '0194012345', 'HQ', 'DEV-IT-DEV', 'HN-HO', 'SWE', 'G2', '2025-08-01', { contractTypeCode: 'INDEFINITE' }, 22_000_000, 'hung', 'employee'],
  ['tung', 'Đặng', 'Văn', 'Tùng', 'M', '1992-12-05', '027092031455', '2792031455', 'MFG', 'MFG-PRD', 'BN-FAC', 'OPERATOR', 'G1', '2025-08-01', { contractTypeCode: 'INDEFINITE' }, 7_500_000, null, null],
  ['john', null, null, null, 'M', '1979-03-17', 'C8812345', null, 'HQ', 'DEV-IT', 'HN-HO', 'SWE', 'G4', '2025-09-01', { contractTypeCode: 'DEFINITE', endDate: '2026-12-19' }, 90_000_000, 'hung', null],
  ['hai', 'Lý', 'Văn', 'Hải', 'M', '1990-07-22', '027090040112', '2790040112', 'MFG', 'MFG-PRD', 'BN-FAC', 'WELDER', 'G2', '2025-09-01', { contractTypeCode: 'DEFINITE', endDate: '2027-08-31' }, 9_800_000, null, null],
  ['binh', 'Hoàng', 'Thị', 'Bình', 'F', '1996-08-08', '001196044890', '0196044890', 'HQ', 'DEV-SALES', 'HN-HO', 'SALES_EXEC', 'G2', '2025-09-15', { contractTypeCode: 'DEFINITE', endDate: addDays(TODAY, 19) }, 16_000_000, 'mai', 'employee2'],
  ['hanh', 'Vũ', 'Thị', 'Hạnh', 'F', '1993-05-27', '001193050067', '0193050067', 'HQ', 'DEV-FIN', 'HN-HO', 'ACCOUNTANT', 'G2', '2025-10-01', { contractTypeCode: 'INDEFINITE' }, 18_000_000, null, null],
  ['long', 'Bùi', 'Văn', 'Long', 'M', '1995-10-19', '048095061233', '4895061233', 'HQ', 'DEV-SALES-DN', 'DN-BR', 'SALES_EXEC', 'G1', '2025-11-01', { contractTypeCode: 'DEFINITE', endDate: '2026-10-31' }, 14_000_000, 'mai', null],
  ['lan', 'Ngô', 'Thị', 'Lan', 'F', '1991-03-02', '001191070456', '0191070456', 'HQ', 'DEV-WH', 'HN-BV', 'WAREHOUSE', 'G1', '2025-12-01', { contractTypeCode: 'INDEFINITE' }, 6_500_000, null, null],
  ['kien', 'Mai', 'Văn', 'Kiên', 'M', '1997-01-15', '001097081578', '0197081578', 'HQ', 'DEV-IT-QA', 'HN-HO', 'QA_ENG', 'G2', '2025-12-01', { contractTypeCode: 'DEFINITE', endDate: addDays(TODAY, 34) }, 17_000_000, 'hung', null],
  ['yen', 'Trịnh', 'Thị', 'Yến', 'F', '1994-12-12', '079194090811', '7994090811', 'SGN', 'SGN-SALES', 'HCM-HO', 'SALES_EXEC', 'G2', '2026-01-05', { contractTypeCode: 'INDEFINITE' }, 18_000_000, null, null],
  ['nga', 'Kiều', 'Thị', 'Nga', 'F', '1999-02-25', '001199100234', '0199100234', 'HQ', 'DEV-HR', 'HN-HO', 'HR_SPEC', 'G1', '2026-08-03',
    { contractTypeCode: 'PROBATION', endDate: '2026-10-01', probationLevelCode: 'COLLEGE_OR_ABOVE', probationPayPct: 85 }, 15_000_000, null, null],
  ['quan', 'Đỗ', 'Minh', 'Quân', 'M', '2000-09-09', '001200110567', '0100110567', 'HQ', 'DEV-IT-QA', 'HN-HO', 'QA_ENG', 'G1', addDays(TODAY, -30),
    { contractTypeCode: 'PROBATION', endDate: addDays(TODAY, 29), probationLevelCode: 'COLLEGE_OR_ABOVE', probationPayPct: 85 }, 16_000_000, 'hung', null],
  ['minh', 'Hồ', 'Quang', 'Minh', 'M', '1998-04-04', '001098120789', '0198120789', 'HQ', 'DEV-IT-DEV', 'HN-HO', 'SWE', 'G2', addDays(TODAY, -1), { contractTypeCode: 'INDEFINITE' }, 21_000_000, 'hung', null],
];
/** Hires left PENDING (hr.admin inbox). */
const PENDING_HIRES = new Set(['minh']);

export async function seedPeople(owner, ctx, ownCore) {
  const hr = client(USERS.hrAdmin);
  const approvers = [hr, client(USERS.itManager), client(USERS.salesManager), owner];
  const LE = { HQ: IDS.leHQ, MFG: IDS.leMFG, SGN: ctx.leSGN };
  const emp = {};

  /** The workflow instance of a submit response: a workflowInstanceId anywhere in it, else the requester's request
   *  whose subject is the created record. */
  async function instanceOf(r) {
    const seen = [];
    (function walk(o) {
      if (!o || typeof o !== 'object') return;
      if (typeof o.workflowInstanceId === 'string') seen.push(o.workflowInstanceId);
      Object.values(o).forEach(walk);
    })(r);
    if (seen.length) return seen[0];
    const ids = [];
    (function walk(o) {
      if (!o || typeof o !== 'object') return;
      if (typeof o.id === 'string') ids.push(o.id);
      Object.values(o).forEach(walk);
    })(r);
    const reqs = await owner.get('/core/workflow/requests', { size: 200 });
    return (reqs.items ?? reqs).find((q) => ids.includes(q.subjectId))?.instanceId;
  }

  /** Approves every pending task of a submitted request, step after step, with whichever approver holds it. */
  async function approveAll(submitResponse, { maxSteps = 4 } = {}) {
    const instanceId = typeof submitResponse === 'string' ? submitResponse : await instanceOf(submitResponse);
    if (!instanceId) throw new Error('no workflow instance found in ' + JSON.stringify(submitResponse).slice(0, 200));
    for (let n = 0; n < maxSteps; n++) {
      let acted = false;
      for (const a of approvers) {
        const tasks = await a.get('/core/workflow/tasks');
        const t = (tasks.items ?? tasks).find((x) => x.instanceId === instanceId);
        if (t) {
          await a.post(`/core/workflow/tasks/${t.taskId}/actions`, { action: 'APPROVE', comment: 'Đồng ý (dữ liệu mẫu)' });
          acted = true;
          break;
        }
      }
      if (!acted) return;
    }
  }
  const evidence = async (path, extra = {}) => {
    const d = await owner.upload(path, extra);
    await sleep(2500); // dev storage reports the scan CLEAN ~1.5 s after the upload
    return d;
  };

  // ------------------------------------------------------------------ hires
  section('People: hires (submitted by the owner, approved by hr.admin)');
  for (const p of PEOPLE) {
    const [key, family, middle, given, gender, birth, nationalId, si, le, ou, loc, job, grade, hireDate, contract, salary, mgr] = p;
    const done = await step(`hire ${family ?? 'SMITH'} ${given ?? 'John'} (${le} ${ou}, ${contract.contractTypeCode}, ${hireDate})`, async () => {
      const person = key === 'john'
        ? { familyName: 'Smith', givenName: 'John', latinName: 'SMITH JOHN', birthDate: birth, genderCode: gender,
            nationalityCode: 'US', nationalIdType: 'PASSPORT', nationalId, acknowledgeWarnings: true }
        : { familyName: family, middleName: middle, givenName: given, birthDate: birth, genderCode: gender, nationalityCode: 'VN',
            nationalIdType: 'CCCD', nationalId, siNumber: si, hiFacilityCode: le === 'SGN' ? '79-002' : '01-001', acknowledgeWarnings: true };
      try {
        const r = await owner.post('/people/hires', {
          person, legalEntityId: LE[le], hireDate, workerCategoryCode: 'EMPLOYEE',
          assignment: { orgUnitId: ctx.ou[ou], locationId: ctx.loc[loc], jobTitleId: ctx.job[job], gradeId: ctx.grade[grade],
            managerEmploymentId: mgr ? emp[mgr]?.employmentId : null, fte: 1 },
          contract: { ...contract, jobTitleId: ctx.job[job], ...SIGNATORY },
          salary: { payBasis: 'GROSS', baseSalaryAmount: salary, siSalaryAmount: salary,
            ...(contract.contractTypeCode === 'PROBATION' ? { probationPct: 85 } : {}),
            allowances: [{ componentCode: 'ALW_LUNCH', amount: 730_000, inSiSalary: false }] },
          ...SIGNATORY,
        });
        emp[key] = r;
        if (!PENDING_HIRES.has(key)) await approveAll(r);
        return r;
      } catch (e) {
        if (e instanceof ApiError && e.body?.code === 'person-exists') throw new SeedExists();
        throw e;
      }
    });
    if (done === undefined && key === PEOPLE[0][0]) {
      console.log('\n  People data looks present already (first hire failed). Run scripts/dev-reset.ps1 for a fresh set.');
      return;
    }
  }
  const has = (...keys) => keys.every((k) => emp[k]);

  section('People: sign hire contracts and decisions (scan upload)');
  for (const [key, , , , , , , , , , , , , hireDate] of PEOPLE) {
    if (!emp[key] || PENDING_HIRES.has(key)) continue;
    await step(`sign contract ${emp[key].employeeNo} + decision ${emp[key].decisionNo}`, async () => {
      const signDate = hireDate < TODAY ? addDays(hireDate, -3) : TODAY;
      const c = await owner.get(`/people/contracts/${emp[key].contractId}`);
      if (c.status === 'DRAFT') await owner.upload(`/people/contracts/${c.id}/sign`, { version: c.version, signDate });
      if (emp[key].decisionId) {
        const d = await owner.get(`/people/decisions/${emp[key].decisionId}`);
        if (d.status !== 'SIGNED') await owner.upload(`/people/decisions/${d.id}/sign`, { version: d.version, signDate });
      }
    });
  }

  // ------------------------------------------------------------------ concurrent employment
  section('People: concurrent employment in MFG + primary employment change');
  if (has('ngoc')) {
    await step('Cao Thị Ngọc: concurrent hire in MFG (QC, 50 %)', async () => {
      const r = await owner.post('/people/hires', {
        personId: emp.ngoc.personId, legalEntityId: IDS.leMFG, hireDate: '2026-01-05', workerCategoryCode: 'EMPLOYEE',
        assignment: { orgUnitId: ctx.ou['MFG-QC'], locationId: ctx.loc['BN-FAC'], jobTitleId: ctx.job.QC_INSP, gradeId: ctx.grade.G2, fte: 0.5 },
        contract: { contractTypeCode: 'INDEFINITE', jobTitleId: ctx.job.QC_INSP, ...SIGNATORY },
        salary: { payBasis: 'GROSS', baseSalaryAmount: 8_000_000, siSalaryAmount: 8_000_000 }, ...SIGNATORY,
      });
      emp.ngocMfg = r;
      await approveAll(r);
      const c = await owner.get(`/people/contracts/${r.contractId}`);
      await owner.upload(`/people/contracts/${c.id}/sign`, { version: c.version, signDate: '2026-01-02' });
    });
    await step('Cao Thị Ngọc: request MFG as PIT primary employment from next month (left PENDING)', async () => {
      const first = `${addMonths(TODAY, 1).slice(0, 7)}-01`;
      await owner.post(`/people/persons/${emp.ngoc.personId}/primary-employment/change-requests`,
        { employmentId: emp.ngocMfg.employmentId, validFrom: first, reasonCode: 'EMPLOYEE_REGISTRATION' });
    });
  }

  // ------------------------------------------------------------------ person details
  section('People: addresses, contacts, qualifications, identity documents');
  const addr = { HQ: ['01', '00070', 'Hoàn Kiếm'], MFG: ['24', '07210', 'Kinh Bắc'], SGN: ['79', '26743', 'Bến Thành'] };
  const streets = ['12 Hàng Bài', '45 Lý Thường Kiệt', '8 Phan Chu Trinh', '102 Bà Triệu', '7 Tràng Thi', '23 Hai Bà Trưng'];
  for (const [i, [key, family, middle, given, , , , , le]] of PEOPLE.entries()) {
    if (!emp[key]) continue;
    const pid = emp[key].personId;
    await step(`details of ${family ?? 'Smith'} ${given ?? 'John'}`, async () => {
      const [prov, commune] = addr[le];
      await owner.post(`/people/persons/${pid}/addresses`, { addressType: 'PERMANENT', validFrom: '2025-07-01',
        address: { countryCode: 'VN', provinceCode: key === 'john' ? '01' : prov, communeCode: key === 'john' ? '00004' : commune, addressLine: streets[i % streets.length] } });
      await owner.post(`/people/persons/${pid}/addresses`, { addressType: 'CURRENT', validFrom: '2025-07-01',
        address: { countryCode: 'VN', provinceCode: prov, communeCode: commune, addressLine: `Số ${10 + i}, ngõ ${i + 3} ${streets[(i + 2) % streets.length]}` } });
      await owner.post(`/people/persons/${pid}/contacts`, { contactType: 'MOBILE', value: `09${String(12345670 + i * 7919).slice(0, 8)}`, primary: true });
      const ascii = `${(given ?? 'john')}.${(family ?? 'smith')}`.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/gi, 'd').toLowerCase();
      await owner.post(`/people/persons/${pid}/contacts`, { contactType: 'PERSONAL_EMAIL', value: `${ascii}${i}@example.vn`, primary: true });
      await owner.post(`/people/persons/${pid}/qualifications`, ['tung', 'hai', 'lan'].includes(key)
        ? { qualificationLevelCode: 'INTERMEDIATE', vocationalSkillLevelCode: 'L2', generalEducationCode: '12/12', fieldOfStudy: 'Cơ khí', institutionName: 'Trường Cao đẳng nghề Bắc Ninh', graduationYear: 2012, highest: true }
        : { qualificationLevelCode: ['hung', 'mai', 'john'].includes(key) ? 'MASTER' : 'UNIVERSITY', generalEducationCode: '12/12',
            fieldOfStudy: ['an', 'duc', 'kien', 'quan', 'minh', 'john', 'hung'].includes(key) ? 'Công nghệ thông tin' : 'Quản trị kinh doanh',
            institutionName: key === 'john' ? 'University of Washington' : 'Đại học Kinh tế Quốc dân', graduationYear: 2016, highest: true });
    });
  }
  if (has('john')) {
    await step('John Smith: passport + work visa (visa expiring in 45 days)', async () => {
      const pid = emp.john.personId;
      const pass = await owner.post(`/people/persons/${pid}/identity-documents`, { docType: 'PASSPORT', docNo: 'C8812345',
        issuingCountryCode: 'US', issueDate: '2021-05-10', issuePlace: 'U.S. Department of State', expiryDate: '2031-05-09' });
      await owner.post(`/people/persons/${pid}/identity-documents`, { docType: 'WORK_VISA', docNo: 'LD2V2025091', visaTypeCode: 'LD2',
        issuingCountryCode: 'VN', issueDate: '2025-08-20', issuePlace: 'Cục Quản lý xuất nhập cảnh', expiryDate: addDays(TODAY, 45) });
      const scan = await evidence('/core/documents', { documentTypeCode: 'HR_IDENTITY_DOC_SCAN', ownerEntityId: pass.id, title: 'Hộ chiếu' });
      await owner.put(`/people/persons/${pid}/identity-documents/${pass.id}/scan`, { documentId: scan.id });
    });
  }
  if (has('thu')) {
    await step('Phạm Thị Thu: passport expiring in 40 days', () => owner.post(`/people/persons/${emp.thu.personId}/identity-documents`, {
      docType: 'PASSPORT', docNo: 'N2345678', issuingCountryCode: 'VN', issueDate: '2016-12-20', issuePlace: 'Cục Quản lý xuất nhập cảnh',
      expiryDate: addDays(TODAY, 40) }));
  }

  // ------------------------------------------------------------------ compliance
  section('People: compliance (work permit, tax residency, special statuses)');
  if (has('john')) {
    await step('John Smith: work permit valid until the contract end (expiring list)', () => owner.post(`/people/persons/${emp.john.personId}/work-permits`, {
      employmentId: emp.john.employmentId, permitType: 'WORK_PERMIT', permitNo: 'HN-2025-GP-08812', jobTitleOnPermit: 'Senior Software Engineer',
      issuingAuthority: 'Sở Nội vụ Hà Nội', issueDate: '2025-08-15', validFrom: '2025-09-01', validTo: '2026-12-20', status: 'ACTIVE' }));
    await step('John Smith: VN presence + tax residency NON_RESIDENT (approved)', async () => {
      await owner.post(`/people/persons/${emp.john.personId}/vn-presence-periods`, { entryDate: '2025-08-25', exitDate: null });
      const r = await owner.post(`/people/persons/${emp.john.personId}/tax-residencies`, { status: 'NON_RESIDENT', basis: 'NOT_MET',
        validFrom: '2025-09-01', arrivalDate: '2025-08-25' });
      await approveAll(r);
    });
    await step('John Smith: residency proposal from Payroll (RESIDENT, 183 days) - SQL, no API exists', () => sql(`
      SELECT set_config('app.tenant_id', '${IDS.tenant}', true);
      INSERT INTO hr.tax_residency (tenant_id, id, person_id, status, basis, valid_from, tax_year, days_in_vn, source, proposal_ref, approval_status)
      VALUES ('${IDS.tenant}', sys.uuid_v7(), '${emp.john.personId}', 'RESIDENT', 'DAYS_183', '2026-01-01', 2026, 214, 'PAYROLL_PROPOSAL', 'PAY-183-2026-001', 'PENDING_APPROVAL');`));
  }
  if (has('hanh')) {
    await step('Vũ Thị Hạnh: PREGNANCY (protected employee)', () => owner.post(`/people/persons/${emp.hanh.personId}/special-statuses`, {
      statusType: 'PREGNANCY', validFrom: '2026-06-01', validTo: '2027-02-01', details: { certificate: 'BV Phụ sản Hà Nội 2026/0612' } }));
  }
  if (has('tung')) {
    await step('Đặng Văn Tùng: UNION_MEMBER', () => owner.post(`/people/persons/${emp.tung.personId}/special-statuses`, {
      statusType: 'UNION_MEMBER', validFrom: '2025-10-01', details: { card: 'CĐ-BN-2025-0117' } }));
  }

  // ------------------------------------------------------------------ contract lifecycle
  section('People: contract lifecycle');
  if (has('long')) {
    await step('Bùi Văn Long: renew definite contract to 2027-10-31 and sign', async () => {
      const draft = await owner.post(`/people/contracts/${emp.long.contractId}/renew`, { endDate: '2027-10-31' });
      await owner.upload(`/people/contracts/${draft.id}/sign`, { version: draft.version, signDate: '2026-10-20' });
    });
  }
  if (has('lan')) {
    await step('Ngô Thị Lan: contract suspension by agreement (ACTIVE)', async () => {
      await owner.post(`/people/contracts/${emp.lan.contractId}/suspensions`, { reasonCode: 'AGREEMENT', validFrom: '2026-10-01',
        plannedEndDate: '2027-01-31', ...SIGNATORY });
    });
  }
  if (has('nga')) {
    await step('Kiều Thị Nga: probation PASSED -> definite contract (signed)', async () => {
      const r = await owner.post(`/people/contracts/${emp.nga.contractId}/probation-result`, { result: 'PASS', resultDate: '2026-10-01',
        ...SIGNATORY, nextContract: { contractTypeCode: 'DEFINITE', endDate: '2027-10-01' } });
      const nextId = r.nextContractId ?? r.nextContract?.id;
      if (nextId) {
        const c = await owner.get(`/people/contracts/${nextId}`);
        await owner.upload(`/people/contracts/${c.id}/sign`, { version: c.version, signDate: '2026-10-01' });
      }
    });
  }
  if (has('an')) {
    await step('Nguyễn Văn An: appendix (salary 26,000,000 from 2026-07-01), signed', async () => {
      const a = await owner.post(`/people/contracts/${emp.an.contractId}/appendices`, { effectiveDate: '2026-07-01',
        changes: { salary: { base: 26_000_000 }, other: 'Điều chỉnh lương sau đánh giá giữa năm' } });
      await owner.upload(`/people/contract-appendices/${a.id}/sign`, { version: a.version });
    });
  }
  if (has('kien')) {
    await step('Mai Văn Kiên: terminate at contract expiry (DRAFT separation)', () =>
      owner.post(`/people/contracts/${emp.kien.contractId}/terminate-at-expiry`, { noticeDate: TODAY }));
  }

  // ------------------------------------------------------------------ assignment & salary changes
  section('People: assignment and salary changes');
  const attrs = (k, over = {}) => {
    const p = PEOPLE.find((x) => x[0] === k);
    return { orgUnitId: ctx.ou[p[9]], locationId: ctx.loc[p[10]], jobTitleId: ctx.job[p[11]], gradeId: ctx.grade[p[12]],
      managerEmploymentId: p[16] ? emp[p[16]]?.employmentId : null, positionId: null, costCentreId: null, fte: 1, ...over };
  };
  if (has('an')) {
    await step('Nguyễn Văn An: PROMOTION to G3 from 2026-07-01 (approved)', async () => {
      const r = await owner.post(`/people/employments/${emp.an.employmentId}/assignments/changes`, { reasonCode: 'PROMOTION',
        effectiveDate: '2026-07-01', attributes: attrs('an', { gradeId: ctx.grade.G3 }), ...SIGNATORY });
      await approveAll(r);
    });
  }
  if (has('tung')) {
    // (An's 2026-07-01 salary comes from his signed contract appendix: a second change on that day is refused.)
    await step('Đặng Văn Tùng: salary INCREASE to 8,200,000 from 2026-10-01 (approved)', async () => {
      const r = await owner.post(`/people/employments/${emp.tung.employmentId}/salary-records/change-requests`, { actionCode: 'INCREASE',
        reasonCode: 'ANNUAL_REVIEW', effectiveDate: '2026-10-01', terms: { payBasis: 'GROSS', baseSalaryAmount: 8_200_000,
          siSalaryAmount: 8_200_000, allowances: [{ componentCode: 'ALW_LUNCH', amount: 730_000 }] }, ...SIGNATORY });
      await approveAll(r);
    });
  }
  if (has('hung')) {
    await step('Trần Văn Hùng: salary review from next month (left PENDING)', () =>
      owner.post(`/people/employments/${emp.hung.employmentId}/salary-records/change-requests`, { actionCode: 'INCREASE',
        reasonCode: 'ANNUAL_REVIEW', effectiveDate: `${addMonths(TODAY, 1).slice(0, 7)}-01`, terms: { payBasis: 'GROSS',
          baseSalaryAmount: 50_000_000, siSalaryAmount: 50_000_000 }, ...SIGNATORY }));
  }
  if (has('long')) {
    await step('Bùi Văn Long: TEMPORARY_TRANSFER to Hà Nội sales for 2 weeks (left PENDING)', () => {
      const start = nextMonday(7);
      return owner.post(`/people/employments/${emp.long.employmentId}/assignments/changes`, { reasonCode: 'TEMPORARY_TRANSFER',
        effectiveDate: start, endDate: addDays(start, 13), attributes: attrs('long', { orgUnitId: ctx.ou['DEV-SALES'], locationId: ctx.loc['HN-HO'] }), ...SIGNATORY });
    });
  }
  if (has('tung')) {
    await step('Đặng Văn Tùng: COST_CENTRE_CHANGE to QC from 2026-10-01 (no approval)', () =>
      owner.post(`/people/employments/${emp.tung.employmentId}/assignments/changes`, { reasonCode: 'COST_CENTRE_CHANGE',
        effectiveDate: '2026-10-01', attributes: attrs('tung', { costCentreId: ctx.cc['CC-MFG-QC'] }), ...SIGNATORY }));
  }
  if (has('duc')) {
    await step('Phan Văn Đức: TRANSFER to QA team from 2026-03-01 (approved)', async () => {
      const r = await owner.post(`/people/employments/${emp.duc.employmentId}/assignments/changes`, { reasonCode: 'TRANSFER',
        effectiveDate: '2026-03-01', attributes: attrs('duc', { orgUnitId: ctx.ou['DEV-IT-QA'] }), ...SIGNATORY });
      await approveAll(r);
    });
  }

  section('People: decisions');
  if (has('hai')) {
    await step('Lý Văn Hải: JOB_CHANGE decision, signed, then reissued with a new date', async () => {
      const d = await owner.post('/people/decisions', { employmentId: emp.hai.employmentId, decisionTypeCode: 'JOB_CHANGE',
        effectiveDate: '2026-09-01', ...SIGNATORY });
      const s = await owner.upload(`/people/decisions/${d.id}/sign`, { version: d.version, signDate: '2026-08-25' });
      await owner.post(`/people/decisions/${d.id}/reissue`, { effectiveDate: '2026-09-15', version: s.version });
    });
  }
  if (has('yen')) {
    await step('Trịnh Thị Yến: SALARY_CHANGE decision (unsigned)', () => owner.post('/people/decisions', {
      employmentId: emp.yen.employmentId, decisionTypeCode: 'SALARY_CHANGE', effectiveDate: '2026-07-01', ...SIGNATORY }));
  }

  // ------------------------------------------------------------------ family & dependants
  section('People: family members and PIT dependants');
  async function dependant(key, member, claim, approve = true) {
    const pid = emp[key].personId;
    const fm = await owner.post(`/people/persons/${pid}/family-members`, member);
    const doc = await evidence(`/people/persons/${pid}/family-members/${fm.id}/documents`, { title: 'Giấy khai sinh / CCCD' });
    if (!claim) return;
    const r = await owner.post(`/people/persons/${pid}/dependants`, { familyMemberId: fm.id, evidenceDocumentIds: [doc.id], ...claim });
    if (approve) await approveAll(r);
  }
  if (has('hanh')) {
    await step('Vũ Thị Hạnh: spouse (not a dependant)', async () => {
      const fm = await owner.post(`/people/persons/${emp.hanh.personId}/family-members`, { relationshipCode: 'SPOUSE', fullName: 'Trần Quốc Việt',
        birthDate: '1990-02-11', nationalId: '001090123456', averageMonthlyIncome: 25_000_000 });
      return fm;
    });
    await step('Vũ Thị Hạnh: child dependant from 2026-01 (approved)', () => dependant('hanh',
      { relationshipCode: 'CHILD', fullName: 'Trần Gia Bảo', birthDate: '2021-05-20', nationalId: '001221005566', averageMonthlyIncome: 0 },
      { fromMonth: '2026-01', toMonth: '2039-05', reasonCode: 'CHILD_UNDER_AGE' }));
  }
  if (has('an')) {
    await step('Nguyễn Văn An: father as dependant from 2026-01 (approved)', () => dependant('an',
      { relationshipCode: 'FATHER', fullName: 'Nguyễn Văn Bốn', birthDate: '1960-08-15', nationalId: '001060077889', averageMonthlyIncome: 0, hasIncomeAboveThreshold: false },
      { fromMonth: '2026-01', reasonCode: 'PARENT' }));
  }
  if (has('tung')) {
    await step('Đặng Văn Tùng: two children, one dependant left PENDING', async () => {
      await dependant('tung', { relationshipCode: 'CHILD', fullName: 'Đặng Minh Châu', birthDate: '2018-11-02', nationalId: '027318004411', averageMonthlyIncome: 0 },
        { fromMonth: '2026-01', toMonth: '2036-11', reasonCode: 'CHILD_UNDER_AGE' });
      await dependant('tung', { relationshipCode: 'CHILD', fullName: 'Đặng Minh Khang', birthDate: '2023-03-19', nationalId: '027223009922', averageMonthlyIncome: 0 },
        { fromMonth: TODAY.slice(0, 7), toMonth: '2041-03', reasonCode: 'CHILD_UNDER_AGE' }, false);
    });
  }

  // ------------------------------------------------------------------ bank accounts
  section('People: salary bank accounts (2-step approval: line manager, HR)');
  const banks = [['VCB', 'VCB-HN'], ['TCB', 'TCB-HN'], ['BIDV', null], ['ACB', null], ['MB', null], ['CTG', null]];
  for (const [i, [key, family, middle, given, , , , , , , , , , hireDate]] of PEOPLE.entries()) {
    if (!emp[key] || ['an', 'minh'].includes(key)) continue; // An adds his own via ESS; Minh is not hired yet
    const [bankCode, branchCode] = key === 'yen' ? ['BIDV', null] : banks[i % banks.length];
    await step(`bank account ${bankCode} for ${family ?? 'Smith'} ${given ?? 'John'}`, async () => {
      const holder = `${family ?? 'SMITH'} ${middle ?? ''} ${given ?? 'JOHN'}`.replace(/\s+/g, ' ').normalize('NFD')
        .replace(/[̀-ͯ]/g, '').replace(/đ/gi, 'D').toUpperCase();
      const r = await owner.post(`/people/persons/${emp[key].personId}/bank-accounts`, { bankCode, branchCode,
        accountNo: `${1903000000000 + i * 104729}`, holderName: holder, splitType: 'REMAINDER', validFrom: hireDate });
      await approveAll(r);
    });
  }

  // ------------------------------------------------------------------ ESS
  section('People: ESS users (link to employment, ESS_HR role) and self-service requests');
  const essRole = await step('role ESS_HR (hr.ess.view, hr.ess.request; scope SELF)', async () => {
    const found = (await owner.get('/core/roles')).find((r) => r.code === 'ESS_HR');
    if (found) return found.id;
    return (await owner.post('/core/roles', { code: 'ESS_HR', nameVi: 'Nhân viên tự phục vụ - Nhân sự', nameEn: 'ESS - People',
      permissions: [{ code: 'hr.ess.view' }, { code: 'hr.ess.request' }] })).id;
  });
  for (const [key, , , , , , , , , , , , , , , , , login] of PEOPLE) {
    if (!login || !emp[key] || !ctx.users?.[login]) continue;
    await step(`link ${USERS[login].username} -> ${emp[key].employeeNo}`, async () => {
      const u = await owner.get(`/core/users/${ctx.users[login].id}`);
      await owner.put(`/core/users/${u.id}`, { displayName: u.displayName, email: u.email, mobile: u.mobile, locale: u.locale,
        identityId: u.identityId, employmentId: emp[key].employmentId, personId: emp[key].personId, expiresAt: u.expiresAt, version: u.version });
      const current = await owner.get(`/core/users/${u.id}/role-assignments`);
      if (essRole && !(current.items ?? current).some((a) => a.roleId === essRole)) {
        await owner.post(`/core/users/${u.id}/role-assignments`, { roleId: essRole, scopeType: 'SELF', scopeIds: [], includeSubtree: false, validFrom: '2020-01-01' });
      }
    });
  }
  // ESS bank changes need a step-up login (acr + auth_time); Keycloak's password grant used by the dev BFF carries no
  // auth_time, so /people/me/bank-accounts answers 403 step-up-required locally. HR submits An's account instead.
  const an = client(USERS.employee);
  if (has('an')) {
    await step('Nguyễn Văn An: salary bank account submitted by HR (PENDING: manager.it, then hr.admin)', () =>
      owner.post(`/people/persons/${emp.an.personId}/bank-accounts`, { bankCode: 'VCB', branchCode: 'VCB-HN',
        accountNo: '0011002233456', holderName: 'NGUYEN VAN AN', splitType: 'REMAINDER', validFrom: '2025-08-01' }));
    await step('ESS (employee): new child + dependant registration (PENDING)', async () => {
      const fm = await an.post('/people/me/family-members', { relationshipCode: 'CHILD', fullName: 'Nguyễn Minh Khôi', birthDate: '2024-02-10',
        nationalId: '001224003311', averageMonthlyIncome: 0 });
      const doc = await an.upload(`/people/me/family-members/${fm.id}/documents`, { title: 'Giấy khai sinh' });
      await sleep(2500);
      await an.post('/people/me/dependants', { familyMemberId: fm.id, fromMonth: TODAY.slice(0, 7), toMonth: '2042-02',
        reasonCode: 'CHILD_UNDER_AGE', evidenceDocumentIds: [doc.id] });
    });
  }
  if (has('binh')) {
    await step('ESS (employee2): resignation (PENDING: manager.sales, then hr.admin)', () => client(USERS.employee2).post('/people/me/resignation', {
      proposedLastWorkingDate: addDays(TODAY, 30), note: 'Chuyển công tác về quê nhà', personalEmail: 'binh.hoang.personal@example.vn' }));
  }

  // ------------------------------------------------------------------ separations
  section('People: separations');
  if (has('duc')) {
    await step('Phan Văn Đức: MUTUAL_AGREEMENT separation, prior service, submitted and approved (SCHEDULED)', async () => {
      const s = await owner.post(`/people/employments/${emp.duc.employmentId}/separation`, { legalGroundCode: 'MUTUAL_AGREEMENT',
        noticeDate: addDays(TODAY, -10), lastWorkingDate: addDays(TODAY, 19), ...SIGNATORY });
      const sep = s.separation ?? s;
      await owner.post(`/people/separations/${sep.id}/service-periods`, { periodKind: 'RECOGNISED_PRIOR', periodFrom: '2021-01-04',
        periodTo: '2025-06-30', note: 'Thời gian làm việc tại công ty liên kết, được công nhận' });
      const cur = await owner.get(`/people/separations/${sep.id}`);
      const sub = await owner.post(`/people/separations/${sep.id}/submit`, { version: (cur.separation ?? cur).version, personalEmail: 'duc.phan@example.vn' });
      await approveAll(sub);
    });
  }

  // ------------------------------------------------------------------ statutory reports
  section('People: statutory reports');
  const lastMonth = addMonths(TODAY, -1);
  await step(`labour fluctuation ${lastMonth.slice(0, 7)} (HQ): generated, submitted, receipt uploaded`, async () => {
    const r = await owner.post('/people/statutory-reports', { legalEntityId: IDS.leHQ, reportType: 'LABOUR_FLUCTUATION_MONTHLY',
      year: Number(lastMonth.slice(0, 4)), month: Number(lastMonth.slice(5, 7)) });
    const rep = r.report ?? r;
    const sub = await owner.post(`/people/statutory-reports/${rep.id}/submit`, { version: rep.version, submittedOn: TODAY,
      channel: 'PORTAL', submissionRef: `DVC-${TODAY.replaceAll('-', '')}-001` });
    await owner.upload(`/people/statutory-reports/${rep.id}/receipt`, {}, undefined);
    return sub;
  });
  await step('labour change 6 months (JUNE 2026, HQ): generated', () => owner.post('/people/statutory-reports', {
    legalEntityId: IDS.leHQ, reportType: 'LABOUR_CHANGE_6M', year: 2026, period: 'JUNE',
    notes: { HIRES: 'Tuyển mới khối Công nghệ và Kinh doanh' } }));
}

class SeedExists extends Error {
  constructor() { super('person-exists'); }
}

/** Runs SQL as the superuser in the local Postgres container (only for data no API can create). */
function sql(text) {
  execFileSync('docker', ['exec', '-i', 'yhrm-postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-q', '-U', 'postgres', '-d', 'yhrm'],
    { input: `BEGIN;\n${text}\nCOMMIT;\n`, stdio: ['pipe', 'ignore', 'pipe'] });
}
