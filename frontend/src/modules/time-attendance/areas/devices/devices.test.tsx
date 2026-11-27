import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { DeviceView, MappingView, PunchRow } from './api';
import { DevicesPage } from './DevicesPage';
import { EnrolmentMappingsPage } from './EnrolmentMappingsPage';
import { QuarantinePage } from './QuarantinePage';

const DEV = '018f0000-0000-7000-8000-0000000000d1';
const EMP = '018f0000-0000-7000-8000-0000000000e1';
const MAP = '018f0000-0000-7000-8000-0000000000a1';

const device: DeviceView = {
  id: DEV,
  code: 'HCM-01',
  name: 'Cổng chính',
  terminalCode: 'ZK001',
  vendor: 'ZKTeco',
  model: 'K40',
  serialNo: null,
  legalEntityId: null,
  locationId: null,
  timeZone: 'Asia/Ho_Chi_Minh',
  connectorType: 'FILE_IMPORT',
  fileLayoutCode: 'ZKTECO',
  lastImportAt: null,
  status: 'ACTIVE',
  version: 3,
};

const lookups = [
  http.get(`${API}/core/legal-entities`, () => HttpResponse.json([])),
  http.get(`${API}/core/locations`, () => HttpResponse.json([])),
  http.get(`${API}/core/org-units`, () => HttpResponse.json([])),
  http.get(`${API}/people/employees`, () =>
    HttpResponse.json({ items: [{ employmentId: EMP, personId: 'p1', employeeNo: 'NV0001', fullName: 'Trần Thị Bình', status: 'ACTIVE' }], total: 1, page: 0, size: 200 }),
  ),
];

describe('DevicesPage', () => {
  it('lists the time clocks and creates one', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/devices`, () => HttpResponse.json([device])),
      http.post(`${API}/attendance/devices`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...device, id: 'new', code: 'HN-02' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DevicesPage />);

    expect(await screen.findByText('HCM-01')).toBeInTheDocument();
    expect(screen.getByText('ZKTeco / K40')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Add time clock/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Clock code'), 'hn-02');
    await user.type(within(drawer).getByLabelText('Clock name'), 'Hà Nội');
    await user.type(within(drawer).getByLabelText('Terminal code in file'), 'HK9');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(body).toMatchObject({
        code: 'HN-02',
        name: 'Hà Nội',
        terminalCode: 'HK9',
        timeZone: 'Asia/Ho_Chi_Minh',
        fileLayoutCode: 'STANDARD',
        status: 'ACTIVE',
      }),
    );
    expect(body).not.toHaveProperty('version');
  }, 30_000);

  it('updates with the version read and shows translated problems', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/devices`, () => HttpResponse.json([device])),
      http.put(`${API}/attendance/devices/${DEV}`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return problem(409, 'terminal-code-taken');
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DevicesPage />);

    await user.click(await screen.findByRole('button', { name: 'Edit HCM-01' }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toMatchObject({ code: 'HCM-01', version: 3 }));
    expect(await within(drawer).findByText('The terminal code is already used by another clock.')).toBeInTheDocument();
  }, 30_000);

  it('is read-only without att.device.update', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.DEVICE_VIEW))),
      http.get(`${API}/attendance/devices`, () => HttpResponse.json([device])),
      http.get(`${API}/attendance/devices/${DEV}`, () => HttpResponse.json(device)),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DevicesPage />);

    expect(await screen.findByText('HCM-01')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add time clock/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit HCM-01' })).not.toBeInTheDocument();
    // the detail drawer still opens (GET /devices/{id})
    await user.click(screen.getByRole('button', { name: 'View HCM-01' }));
    expect(await screen.findByText('Time clock HCM-01')).toBeInTheDocument();
    expect(screen.getByText('File import')).toBeInTheDocument();
  }, 30_000);
});

describe('EnrolmentMappingsPage', () => {
  const mapping: MappingView = {
    id: MAP,
    deviceId: DEV,
    enrolId: '1001',
    employmentId: EMP,
    validFrom: '2026-01-01',
    validTo: null,
    source: 'MANUAL',
    version: 1,
  };

  it('lists mappings with names and ends one with its version', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/devices`, () => HttpResponse.json([device])),
      http.get(`${API}/attendance/enrolment-mappings`, () => HttpResponse.json([mapping])),
      http.post(`${API}/attendance/enrolment-mappings/${MAP}/end`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...mapping, validTo: '2026-12-01', version: 2 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EnrolmentMappingsPage />);

    expect(await screen.findByText('1001')).toBeInTheDocument();
    expect(await screen.findByText('NV0001 – Trần Thị Bình')).toBeInTheDocument();
    expect(await screen.findByText('HCM-01')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'End enrolment ID 1001' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toMatchObject({ version: 1, validTo: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) }));
  }, 30_000);

  it('hides changes without att.device.update', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.DEVICE_VIEW))),
      http.get(`${API}/attendance/devices`, () => HttpResponse.json([device])),
      http.get(`${API}/attendance/enrolment-mappings`, () => HttpResponse.json([mapping])),
    );
    await changeLanguage('en');
    renderPage(<EnrolmentMappingsPage />);
    expect(await screen.findByText('1001')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Map enrolment ID/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'End enrolment ID 1001' })).not.toBeInTheDocument();
  }, 20_000);
});

describe('QuarantinePage', () => {
  const punch = (id: string, enrolId: string): PunchRow => ({
    id,
    workDateHint: '2026-11-20',
    punchAt: '2026-11-20T01:02:00Z',
    localTs: '2026-11-20T08:02:00',
    tzName: 'Asia/Ho_Chi_Minh',
    source: 'IMPORT',
    deviceId: DEV,
    terminalCode: 'ZK001',
    enrolId,
    direction: 'IN',
    validationStatus: 'QUARANTINED_UNMAPPED',
  });

  function handlers() {
    return [
      ...lookups,
      http.get(`${API}/attendance/devices`, () => HttpResponse.json([device])),
      http.get(`${API}/attendance/punches/quarantine`, () => HttpResponse.json([punch('p1', '7001'), punch('p2', '7002')])),
      http.get(`${API}/attendance/punches/quarantine/counts`, () => HttpResponse.json({ QUARANTINED_UNMAPPED: 2 })),
    ];
  }

  it('shows counts and ignores the selected punches with a reason', async () => {
    let body: unknown;
    server.use(
      ...handlers(),
      http.post(`${API}/attendance/punches/quarantine/ignore`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ignored: 1 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<QuarantinePage />);

    expect(await screen.findByText('7001')).toBeInTheDocument();
    expect(screen.getAllByText('No enrolment ID').length).toBeGreaterThan(0);

    const row = screen.getByText('7001').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /Ignore 1 selected/ }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('Reason'), 'Test punches');
    await user.click(within(dialog).getByRole('button', { name: 'Ignore' }));
    await waitFor(() =>
      expect(body).toEqual({ punches: [{ id: 'p1', workDateHint: '2026-11-20' }], reason: 'Test punches' }),
    );
  }, 30_000);

  it('re-matches the range and reports the result', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      ...handlers(),
      http.post(`${API}/attendance/punches/quarantine/rematch`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ examined: 2, matched: 1, stillQuarantined: 1 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<QuarantinePage />);
    await screen.findByText('7001');
    await user.click(screen.getByRole('button', { name: /Re-match/ }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Re-match' }));
    expect(await within(dialog).findByText('Examined 2: matched 1, 1 still unmatched.')).toBeInTheDocument();
    expect(body).toMatchObject({ deviceId: null, enrolId: null });
  }, 30_000);

  it('offers no selection or resolve actions without att.punch.resolve', async () => {
    server.use(...handlers(), http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.PUNCH_VIEW, P.DEVICE_VIEW))));
    await changeLanguage('en');
    renderPage(<QuarantinePage />);
    expect(await screen.findByText('7001')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Re-match/ })).not.toBeInTheDocument();
  }, 20_000);
});
