import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { Sequence, Setting } from './api';
import { checkPattern, formatPattern, needsDate, needsLegalEntity } from './format';
import { SettingsPage } from './SettingsPage';
import { SequencesPage } from './SequencesPage';

const LE_HQ = '018f0000-0000-7000-8000-0000000002a1';
const LOC_HCM = '018f0000-0000-7000-8000-0000000002b1';
const VAL_TENANT = '018f0000-0000-7000-8000-0000000002c1';
const VAL_LE = '018f0000-0000-7000-8000-0000000002c2';

const legalEntities = [
  { id: LE_HQ, code: 'HQ', status: 'ACTIVE', version: 0, current: { legalNameVi: 'Công ty HQ', shortName: 'Head office' } },
];
const locations = [
  { id: LOC_HCM, code: 'HCM', legalEntityId: LE_HQ, status: 'ACTIVE', version: 0, current: { name: 'Hồ Chí Minh' } },
];

const autoCreateUser: Setting = {
  key: 'core.auto_create_user',
  ownerModule: 'core',
  valueType: 'BOOL',
  allowedValues: [],
  defaultValue: false,
  allowedLevels: ['TENANT', 'LEGAL_ENTITY'],
  effectiveDated: false,
  sensitive: false,
  descriptionVi: 'Tự động tạo người dùng khi tuyển dụng',
  descriptionEn: 'Create a user account automatically on hire',
  tenantValue: true,
  values: [
    { id: VAL_TENANT, level: 'TENANT', legalEntityId: null, locationId: null, value: true, validFrom: '1900-01-01', validTo: null, version: 1 },
    { id: VAL_LE, level: 'LEGAL_ENTITY', legalEntityId: LE_HQ, locationId: null, value: false, validFrom: '1900-01-01', validTo: null, version: 0 },
  ],
};

const otCap: Setting = {
  key: 'att.ot_monthly_cap',
  ownerModule: 'att',
  valueType: 'INT',
  allowedValues: [],
  defaultValue: 40,
  allowedLevels: ['TENANT', 'LEGAL_ENTITY', 'LOCATION'],
  effectiveDated: true,
  sensitive: false,
  descriptionVi: 'Giới hạn giờ làm thêm mỗi tháng',
  descriptionEn: 'Monthly overtime cap (hours)',
  tenantValue: 40,
  values: [],
};

const apiSecret: Setting = {
  ...otCap,
  key: 'pay.bank_api_secret',
  ownerModule: 'pay',
  valueType: 'TEXT',
  allowedLevels: ['TENANT'],
  effectiveDated: false,
  sensitive: true,
  descriptionVi: 'Khóa API ngân hàng',
  descriptionEn: 'Bank API secret',
  defaultValue: '***',
  tenantValue: '***',
};

const settings = [autoCreateUser, otCap, apiSecret];

const employerHandlers = () => [
  http.get(`${API}/core/legal-entities`, () => HttpResponse.json(legalEntities)),
  http.get(`${API}/core/locations`, () => HttpResponse.json(locations)),
];

const settingHandlers = () => [
  ...employerHandlers(),
  http.get(`${API}/core/settings`, () => HttpResponse.json(settings)),
  http.get(`${API}/core/settings/:key`, ({ params }) =>
    HttpResponse.json(settings.find((s) => s.key === params.key)),
  ),
];

async function pickOption(title: string, user: ReturnType<typeof userEvent.setup>) {
  const option = await waitFor(() => {
    const el = document.querySelector(`.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${title}"]`);
    expect(el).not.toBeNull();
    return el as HTMLElement;
  });
  await user.click(option);
}

// ---------------------------------------------------------------------------------------------- pure helpers

describe('number pattern helpers (mirror SequenceService)', () => {
  it('formats like the backend', () => {
    expect(formatPattern('NV{SEQ:5}', 1, '2026-03-01', null)).toBe('NV00001');
    expect(formatPattern('{SEQ:4}/{YYYY}/HĐLĐ-{LE}', 3, '2026-12-31', 'HQ')).toBe('0003/2026/HĐLĐ-HQ');
    expect(formatPattern('QĐ{YY}{MM}-{SEQ:3}', 12345, '2027-01-02', null)).toBe('QĐ2701-12345');
  });

  it('validates tokens and the single {SEQ:n}', () => {
    expect(checkPattern('NV{SEQ:5}')).toBeNull();
    expect(checkPattern('NV-{YYYY}')).toEqual({ problem: 'seqCount' });
    expect(checkPattern('{SEQ:3}-{SEQ:3}')).toEqual({ problem: 'seqCount' });
    expect(checkPattern('{DAY}-{SEQ:3}')).toEqual({ problem: 'unknownToken', token: '{DAY}' });
    expect(checkPattern('{SEQ}')).toEqual({ problem: 'seqWidth' });
    expect(checkPattern('{SEQ:13}')).toEqual({ problem: 'seqWidth' });
  });

  it('knows when a legal entity or a date is needed', () => {
    expect(needsLegalEntity('TENANT', 'NV{SEQ:5}')).toBe(false);
    expect(needsLegalEntity('TENANT', '{LE}{SEQ:5}')).toBe(true);
    expect(needsLegalEntity('LEGAL_ENTITY', '{SEQ:5}')).toBe(true);
    expect(needsDate('NEVER', 'NV{SEQ:5}')).toBe(false);
    expect(needsDate('YEARLY', 'NV{SEQ:5}')).toBe(true);
    expect(needsDate('NEVER', '{YYYY}{SEQ:5}')).toBe(true);
  });
});

// ---------------------------------------------------------------------------------------------- settings

describe('SettingsPage', () => {
  it('lists settings with their company value, filters, and masks sensitive values', async () => {
    server.use(...settingHandlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SettingsPage />, { path: '/core/settings' });

    const row = (await screen.findByText('Create a user account automatically on hire')).closest('tr')!;
    expect(within(row).getByText('core.auto_create_user')).toBeInTheDocument();
    expect(within(row).getByText('On')).toBeInTheDocument(); // company value
    expect(within(row).getByText('Off')).toBeInTheDocument(); // default
    expect(within(row).getByText('2 values')).toBeInTheDocument();
    const secretRow = screen.getByText('Bank API secret').closest('tr')!;
    expect(within(secretRow).getAllByLabelText('Sensitive value, hidden').length).toBeGreaterThan(0);

    await user.type(screen.getByRole('searchbox', { name: 'Search key or description' }), 'overtime');
    await waitFor(() => expect(screen.queryByText('Bank API secret')).not.toBeInTheDocument());
    expect(screen.getByText('Monthly overtime cap (hours)')).toBeInTheDocument();
  }, 20_000);

  it('shows stored values per level and sets a value for a legal entity', async () => {
    let put: { url: string; body: unknown } | undefined;
    server.use(
      ...settingHandlers(),
      http.put(`${API}/core/settings/:key`, async ({ request }) => {
        put = { url: new URL(request.url).pathname, body: await request.json() };
        return HttpResponse.json({ key: 'core.auto_create_user', value: true, source: 'LEGAL_ENTITY', valueId: VAL_LE, validFrom: '1900-01-01' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SettingsPage />, { path: '/core/settings' });

    await user.click(await screen.findByRole('button', { name: 'Create a user account automatically on hire' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('HQ – Head office')).toBeInTheDocument(); // the legal-entity value's scope
    expect(within(drawer).getAllByText('Always')).toHaveLength(2);

    // Ant Design's button-style radio hides its input: click the label
    const setSection = within(drawer).getByRole('region', { name: 'Set a value' });
    await user.click(within(setSection).getByRole('radio', { name: 'Legal entity' }).closest('label')!);
    await user.click(await within(setSection).findByRole('combobox'));
    await pickOption('HQ – Head office', user);
    // the typed editor (a switch for BOOL) starts from the company value
    expect(within(setSection).getByRole('switch')).toBeChecked();
    await user.click(within(setSection).getByRole('button', { name: 'Set value' }));

    await waitFor(() => expect(put).toBeDefined());
    expect(put).toEqual({
      url: '/api/v1/core/settings/core.auto_create_user',
      body: { level: 'LEGAL_ENTITY', legalEntityId: LE_HQ, locationId: null, value: true, validFrom: null },
    });
    expect(await screen.findByText('Value saved. It now resolves to On (Legal entity).')).toBeInTheDocument();
  }, 30_000);

  it('removes a stored value', async () => {
    let removed: string | undefined;
    server.use(
      ...settingHandlers(),
      http.delete(`${API}/core/settings/:key/values/:id`, ({ request }) => {
        removed = new URL(request.url).pathname;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SettingsPage />, { path: '/core/settings' });

    await user.click(await screen.findByRole('button', { name: 'Create a user account automatically on hire' }));
    const drawer = await screen.findByRole('dialog');
    const buttons = await within(drawer).findAllByRole('button', { name: 'Remove value' });
    await user.click(buttons[1]!);
    await user.click(await screen.findByRole('button', { name: 'OK' }));
    await waitFor(() => expect(removed).toBe(`/api/v1/core/settings/core.auto_create_user/values/${VAL_LE}`));
    expect(await screen.findByText('Value removed')).toBeInTheDocument();
  }, 20_000);

  it('sets a dated integer value from a date and explains which value applies at a location', async () => {
    let put: unknown;
    let resolveUrl: URL | undefined;
    server.use(
      ...settingHandlers(),
      http.put(`${API}/core/settings/:key`, async ({ request }) => {
        put = await request.json();
        return HttpResponse.json({ key: otCap.key, value: 32, source: 'TENANT', valueId: VAL_TENANT, validFrom: '2026-06-01' });
      }),
      http.get(`${API}/core/settings/:key/resolve`, ({ request }) => {
        resolveUrl = new URL(request.url);
        return HttpResponse.json({ key: otCap.key, value: 32, source: 'LOCATION', valueId: VAL_TENANT, validFrom: '2026-06-01' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SettingsPage />, { path: '/core/settings' });

    await user.click(await screen.findByRole('button', { name: 'Monthly overtime cap (hours)' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('No value is stored; the default applies everywhere.')).toBeInTheDocument();

    const value = within(drawer).getByLabelText('Value');
    await user.clear(value);
    await user.type(value, '32');
    const from = within(drawer).getByLabelText('Valid from');
    await user.click(from);
    await user.type(from, '01/06/2026{Enter}');
    await user.click(within(drawer).getByRole('button', { name: 'Set value' }));
    await waitFor(() => expect(put).toEqual({ level: 'TENANT', legalEntityId: null, locationId: null, value: 32, validFrom: '2026-06-01' }));

    const resolveSection = within(drawer).getByRole('region', { name: 'Which value applies?' });
    await user.click(within(resolveSection).getByRole('combobox', { name: 'Select a location' }));
    await pickOption('HCM – Hồ Chí Minh', user);
    await user.click(within(resolveSection).getByRole('button', { name: 'Check' }));
    const resolved = await within(resolveSection).findByTestId('resolved-setting');
    expect(within(resolved).getByText('32')).toBeInTheDocument();
    expect(within(resolved).getByText('Location')).toBeInTheDocument();
    expect(within(resolved).getByText('since 01/06/2026')).toBeInTheDocument();
    expect(resolveUrl?.searchParams.get('locationId')).toBe(LOC_HCM);
    expect(resolveUrl?.searchParams.get('asOf')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  }, 30_000);

  it('shows the translated problem when the value is refused', async () => {
    server.use(
      ...settingHandlers(),
      http.put(`${API}/core/settings/:key`, () => problem(422, 'invalid-setting-value', 'att.ot_monthly_cap expects INT')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SettingsPage />, { path: '/core/settings' });

    await user.click(await screen.findByRole('button', { name: 'Monthly overtime cap (hours)' }));
    const drawer = await screen.findByRole('dialog');
    await user.click(await within(drawer).findByRole('button', { name: 'Set value' }));
    expect(await within(drawer).findByText("The value does not match the setting's type.")).toBeInTheDocument();
  }, 20_000);

  it('is read-only without core.setting.update', async () => {
    server.use(...settingHandlers(), http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.setting.view'))));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SettingsPage />, { path: '/core/settings' });

    await user.click(await screen.findByRole('button', { name: 'Create a user account automatically on hire' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('core.auto_create_user')).toBeInTheDocument();
    await waitFor(() => expect(within(drawer).getAllByText('Always')).toHaveLength(2));
    expect(within(drawer).queryByRole('button', { name: 'Set value' })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: 'Remove value' })).not.toBeInTheDocument();
  }, 20_000);
});

// ---------------------------------------------------------------------------------------------- numbering

const employeeNo: Sequence = {
  id: '018f0000-0000-7000-8000-0000000002d1',
  code: 'EMPLOYEE_NO',
  name: 'Mã nhân viên',
  scopeLevel: 'TENANT',
  pattern: 'NV{SEQ:5}',
  resetRule: 'NEVER',
  gapFree: true,
  startValue: 1,
  incrementBy: 1,
  status: 'ACTIVE',
  version: 4,
};
const contractNo: Sequence = {
  ...employeeNo,
  id: '018f0000-0000-7000-8000-0000000002d2',
  code: 'CONTRACT_NO',
  name: 'Số hợp đồng',
  scopeLevel: 'LEGAL_ENTITY',
  pattern: '{SEQ:4}/{YYYY}/HĐLĐ-{LE}',
  resetRule: 'YEARLY',
  version: 0,
};

const sequenceHandlers = () => [
  ...employerHandlers(),
  http.get(`${API}/core/sequences`, () => HttpResponse.json([contractNo, employeeNo])),
];

describe('SequencesPage', () => {
  it('lists series and creates one, checking the pattern and showing a sample', async () => {
    let created: unknown;
    server.use(
      ...sequenceHandlers(),
      http.post(`${API}/core/sequences`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json({ ...employeeNo, code: 'DECISION_NO' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SequencesPage />, { path: '/core/sequences' });

    const row = (await screen.findByText('CONTRACT_NO')).closest('tr')!;
    expect(within(row).getByText('{SEQ:4}/{YYYY}/HĐLĐ-{LE}')).toBeInTheDocument();
    expect(within(row).getByText('One counter per legal entity')).toBeInTheDocument();
    expect(within(row).getByText('Every year')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Create series/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Code/), 'decision_no');
    await user.type(within(drawer).getByLabelText(/^Name/), 'Số quyết định');
    const pattern = within(drawer).getByLabelText(/^Pattern/);
    await user.type(pattern, 'QĐ-{{DAY}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    expect(await within(drawer).findByText('Unknown token {DAY}.')).toBeInTheDocument();
    expect(created).toBeUndefined();

    await user.clear(pattern);
    await user.type(pattern, 'QĐ{{YYYY}-{{SEQ:4}');
    expect(await within(drawer).findByTestId('pattern-sample')).toHaveTextContent(new RegExp(`QĐ${new Date().getFullYear()}-0001`));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(created).toBeDefined());
    expect(created).toEqual({
      code: 'DECISION_NO',
      name: 'Số quyết định',
      scopeLevel: 'TENANT',
      pattern: 'QĐ{YYYY}-{SEQ:4}',
      resetRule: 'NEVER',
      gapFree: true,
      startValue: 1,
      incrementBy: 1,
    });
  }, 30_000);

  it('edits a series with its version; code, counter and start value are fixed', async () => {
    let updated: { url: string; body: unknown } | undefined;
    server.use(
      ...sequenceHandlers(),
      http.put(`${API}/core/sequences/:code`, async ({ request }) => {
        updated = { url: new URL(request.url).pathname, body: await request.json() };
        return HttpResponse.json({ ...employeeNo, pattern: 'NV-{SEQ:6}', version: 5 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SequencesPage />, { path: '/core/sequences' });

    await user.click(await screen.findByRole('button', { name: 'Edit EMPLOYEE_NO' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByLabelText(/^Code/)).toBeDisabled();
    expect(within(drawer).getByLabelText(/^Start value/)).toBeDisabled();
    const pattern = within(drawer).getByLabelText(/^Pattern/);
    await user.clear(pattern);
    await user.type(pattern, 'NV-{{SEQ:6}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updated).toBeDefined());
    expect(updated).toEqual({
      url: '/api/v1/core/sequences/EMPLOYEE_NO',
      body: { name: 'Mã nhân viên', pattern: 'NV-{SEQ:6}', resetRule: 'NEVER', gapFree: true, incrementBy: 1, status: 'ACTIVE', version: 4 },
    });
  }, 20_000);

  it('previews the next number of a per-legal-entity series', async () => {
    let previewUrl: URL | undefined;
    server.use(
      ...sequenceHandlers(),
      http.get(`${API}/core/sequences/:code/preview`, ({ request }) => {
        previewUrl = new URL(request.url);
        return HttpResponse.json({ next: '0003/2026/HĐLĐ-HQ' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SequencesPage />, { path: '/core/sequences' });

    await user.click(await screen.findByRole('button', { name: 'Preview next number CONTRACT_NO' }));
    const modal = await screen.findByRole('dialog');
    const run = within(modal).getByRole('button', { name: 'Preview' });
    expect(run).toBeDisabled(); // needs a legal entity first
    await user.click(within(modal).getByRole('combobox'));
    await pickOption('HQ – Head office', user);
    await user.click(run);
    expect(await within(modal).findByTestId('preview-next')).toHaveTextContent('0003/2026/HĐLĐ-HQ');
    expect(previewUrl?.pathname).toBe('/api/v1/core/sequences/CONTRACT_NO/preview');
    expect(previewUrl?.searchParams.get('legalEntityId')).toBe(LE_HQ);
  }, 20_000);

  it('hides create and edit without core.sequence.update', async () => {
    server.use(...sequenceHandlers(), http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.sequence.view'))));
    await changeLanguage('en');
    renderPage(<SequencesPage />, { path: '/core/sequences' });

    expect(await screen.findByText('EMPLOYEE_NO')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview next number EMPLOYEE_NO' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create series/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit EMPLOYEE_NO' })).not.toBeInTheDocument();
  }, 20_000);
});
