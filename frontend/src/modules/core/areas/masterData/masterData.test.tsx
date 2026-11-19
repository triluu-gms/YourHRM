import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { AdminUnit, Bank, BankBranch, CodeList, CodeValue, Country, Currency, MasterDataRelease, Resolution } from './api';
import { MasterDataPage } from './MasterDataPage';

const HANOI: AdminUnit = { code: '01', level: 'PROVINCE', nameVi: 'Thành phố Hà Nội', nameEn: 'Hanoi', parentCode: null, validFrom: '2025-07-01', validTo: null };
const HCM: AdminUnit = { code: '79', level: 'PROVINCE', nameVi: 'Thành phố Hồ Chí Minh', nameEn: 'Ho Chi Minh City', parentCode: null, validFrom: '2025-07-01', validTo: null };
const BA_DINH: AdminUnit = { code: '00004', level: 'COMMUNE', nameVi: 'Phường Ba Đình', nameEn: 'Ba Dinh ward', parentCode: '01', validFrom: '2025-07-01', validTo: null };
const OLD_WARD: AdminUnit = { code: '00256', level: 'COMMUNE', nameVi: 'Phường Cống Vị', nameEn: null, parentCode: '01', validFrom: '2008-08-01', validTo: '2025-07-01' };

const codeLists: CodeList[] = [
  { listCode: 'JOB_HAZARD_CATEGORY', nameVi: 'Nhóm nghề nặng nhọc độc hại', nameEn: 'Hazard category', tenantExtensible: false, statutory: true, ownerModule: 'core' },
  { listCode: 'CONTRACT_TYPE', nameVi: 'Loại hợp đồng', nameEn: 'Contract type', tenantExtensible: true, statutory: true, ownerModule: 'hr' },
];
const hazardValues: CodeValue[] = [
  { valueCode: 'IV', labelVi: 'Loại IV', labelEn: 'Category IV', sortOrder: 40, attributes: { extraLeaveDays: 12 }, active: true, validFrom: '2020-01-01', validTo: null, tenantValue: false },
  { valueCode: 'V', labelVi: 'Loại V', labelEn: 'Category V', sortOrder: 50, attributes: {}, active: false, validFrom: '2020-01-01', validTo: '2025-01-01', tenantValue: true },
];

const banks: Bank[] = [
  { bankCode: 'VCB', bin: '970436', swiftCode: 'BFTVVNVX', shortName: 'Vietcombank', nameVi: 'Ngân hàng TMCP Ngoại thương Việt Nam', nameEn: null, active: true },
  { bankCode: 'TCB', bin: '970407', swiftCode: 'VTCBVNVX', shortName: 'Techcombank', nameVi: 'Ngân hàng TMCP Kỹ Thương Việt Nam', nameEn: null, active: true },
];
const branches: BankBranch[] = [
  { bankCode: 'VCB', branchCode: 'VCB-HN', nameVi: 'Chi nhánh Hà Nội', provinceCode: '01', active: true },
];

const countries: Country[] = [
  { code: 'VN', iso3: 'VNM', nameVi: 'Việt Nam', nameEn: 'Viet Nam', nationalityVi: 'Việt Nam', nationalityEn: 'Vietnamese' },
];
const currencies: Currency[] = [
  { code: 'VND', nameVi: 'Đồng Việt Nam', nameEn: 'Vietnamese dong', minorUnits: 0 },
  { code: 'USD', nameVi: 'Đô la Mỹ', nameEn: 'US dollar', minorUnits: 2 },
];
const releases: MasterDataRelease[] = [
  {
    releaseNo: 7,
    title: 'Sáp nhập 34 tỉnh',
    datasets: ['ADMIN_UNIT', 'BANK'],
    itemCounts: { ADMIN_UNIT: 3321, BANK: 52 },
    effectiveDate: '2025-07-01',
    publishedAt: '2025-06-20T02:00:00Z',
    loadedAt: '2025-06-25T04:30:00Z',
  },
];

const handlers = (calls: URLSearchParams[] = []) => [
  http.get(`${API}/core/master-data/admin-units`, ({ request }) => {
    const params = new URL(request.url).searchParams;
    calls.push(params);
    const parent = params.get('parent');
    return HttpResponse.json(parent === '01' ? [BA_DINH] : parent ? [] : [HANOI, HCM]);
  }),
  http.get(`${API}/core/master-data/admin-units/search`, ({ request }) => {
    const q = new URL(request.url).searchParams.get('q') ?? '';
    return HttpResponse.json([HANOI, BA_DINH, OLD_WARD].filter((u) => u.nameVi.toLowerCase().includes(q.toLowerCase())));
  }),
  http.get(`${API}/core/master-data/admin-units/:code/resolve`, ({ params }) =>
    HttpResponse.json({
      inputCode: params.code as string,
      status: 'AMBIGUOUS',
      candidates: [BA_DINH, HCM],
      note: 'Tách thành nhiều phường',
    } satisfies Resolution),
  ),
  http.get(`${API}/core/master-data/code-lists`, () => HttpResponse.json(codeLists)),
  http.get(`${API}/core/master-data/code-lists/:listCode/values`, ({ request }) => {
    const all = new URL(request.url).searchParams.get('includeInactive') === 'true';
    return HttpResponse.json(all ? hazardValues : hazardValues.filter((v) => v.active));
  }),
  http.get(`${API}/core/master-data/banks`, () => HttpResponse.json(banks)),
  http.get(`${API}/core/master-data/banks/:bankCode/branches`, () => HttpResponse.json(branches)),
  http.get(`${API}/core/master-data/countries`, () => HttpResponse.json(countries)),
  http.get(`${API}/core/master-data/currencies`, () => HttpResponse.json(currencies)),
  http.get(`${API}/core/master-data/releases`, () => HttpResponse.json(releases)),
];

describe('MasterDataPage', () => {
  it('browses provinces, drills into their wards and searches across levels', async () => {
    const calls: URLSearchParams[] = [];
    server.use(...handlers(calls));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MasterDataPage />, { path: '/core/master-data' });

    expect(await screen.findByText('Hanoi')).toBeInTheDocument();
    expect(screen.getByText('Ho Chi Minh City')).toBeInTheDocument();
    expect(calls[0]!.get('level')).toBe('PROVINCE');

    await user.click(screen.getByRole('button', { name: 'Open 01' }));
    expect(await screen.findByText('Wards of Thành phố Hà Nội')).toBeInTheDocument();
    expect(screen.getByText('Ba Dinh ward')).toBeInTheDocument();
    await waitFor(() => expect(calls.at(-1)!.get('parent')).toBe('01'));

    await user.type(screen.getByLabelText('Search a province or ward…'), 'Cống Vị{Enter}');
    expect(await screen.findByText('Search results')).toBeInTheDocument();
    // an ended unit shows its last valid day (the exclusive end date minus one)
    const old = (await screen.findByText('00256')).closest('tr')!;
    expect(within(old).getByText('01/08/2008 – 30/06/2025')).toBeInTheDocument();
  }, 30_000);

  it('resolves an old code and shows the split candidates', async () => {
    server.use(...handlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MasterDataPage />, { path: '/core/master-data' });

    await screen.findByText('Hanoi');
    await user.click(screen.getByRole('button', { name: 'Resolve old → new 01' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('Split: choose a successor')).toBeInTheDocument();
    expect(within(drawer).getByText('Tách thành nhiều phường')).toBeInTheDocument();
    expect(within(drawer).getByText('Ba Dinh ward')).toBeInTheDocument();
  }, 30_000);

  it('shows the values of the selected code list, with the inactive ones on request', async () => {
    server.use(...handlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MasterDataPage />, { path: '/core/master-data' });

    await user.click(await screen.findByRole('tab', { name: 'Code lists' }));
    expect(await screen.findByText('Pick a code list to see its values')).toBeInTheDocument();

    await user.click(await screen.findByText('Hazard category'));
    expect(await screen.findByText('Values of JOB_HAZARD_CATEGORY')).toBeInTheDocument();
    expect(screen.getByText('Category IV')).toBeInTheDocument();
    expect(screen.getByText('extraLeaveDays: 12')).toBeInTheDocument();
    expect(screen.queryByText('Category V')).not.toBeInTheDocument();

    await user.click(screen.getByText('Show inactive'));
    expect(await screen.findByText('Category V')).toBeInTheDocument();
  }, 30_000);

  it('shows a bank with its BIN and its branches', async () => {
    server.use(...handlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MasterDataPage />, { path: '/core/master-data' });

    await user.click(await screen.findByRole('tab', { name: 'Banks' }));
    expect(await screen.findByText('Pick a bank to see its branches')).toBeInTheDocument();
    const row = (await screen.findByText('Vietcombank')).closest('tr')!;
    expect(within(row).getByText('970436')).toBeInTheDocument();

    await user.click(screen.getByText('Vietcombank'));
    expect(await screen.findByText('Branches of Vietcombank')).toBeInTheDocument();
    expect(screen.getByText('Chi nhánh Hà Nội')).toBeInTheDocument();
  }, 30_000);

  it('lists countries, currencies and the loaded releases', async () => {
    server.use(...handlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MasterDataPage />, { path: '/core/master-data' });

    await user.click(await screen.findByRole('tab', { name: 'Countries' }));
    expect(await screen.findByText('Viet Nam')).toBeInTheDocument();
    expect(screen.getByText('Vietnamese')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Currencies' }));
    expect(await screen.findByText('Vietnamese dong')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Releases' }));
    const release = (await screen.findByText('Sáp nhập 34 tỉnh')).closest('tr')!;
    expect(within(release).getByText('ADMIN_UNIT: 3.321')).toBeInTheDocument();
    expect(within(release).getByText('01/07/2025')).toBeInTheDocument();
  }, 30_000);
});
