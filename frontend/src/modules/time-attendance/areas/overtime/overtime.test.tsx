import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { meWith } from '@/test/fixtures';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { OvertimeCapView, OvertimeRequestView, OvertimeResultView } from './api';
import { OvertimeRequestsPage } from './OvertimeRequestsPage';
import { OvertimeRequestDetailPage } from './OvertimeRequestDetailPage';
import { OvertimeCapsPage } from './OvertimeCapsPage';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const LE = '018f0000-0000-7000-8000-0000000000a1';
const R1 = '018f0000-0000-7000-8000-0000000000b1';

const request: OvertimeRequestView = {
  id: R1,
  employmentId: EMP,
  legalEntityId: LE,
  requestGroupId: null,
  workDate: '2026-11-20',
  plannedStartAt: '2026-11-20T10:00:00Z',
  plannedEndAt: '2026-11-20T12:30:00Z',
  plannedMinutes: 150,
  reason: 'Year-end inventory',
  status: 'DRAFT',
  employeeConsent: true,
  consentMethod: 'PAPER',
  consentAt: '2026-11-19T02:00:00Z',
  protectedWorker: false,
  capOverride: false,
  capOverrideReason: null,
  workflowInstanceId: null,
  decidedAt: null,
  categories: [{ category: 'OT-WD', minutes: 150, precededByDayOt: false }],
  version: 1,
};

const result: OvertimeResultView = {
  id: 'r1',
  employmentId: EMP,
  workDate: '2026-11-18',
  otCategory: 'OT-RD-N',
  actualMinutes: 120,
  approvedMinutes: 90,
  paidMinutes: 90,
  precededByDayOt: true,
  calcVersion: 3,
  legalPackId: null,
};

const lookups = [
  http.get(`${API}/core/legal-entities`, () =>
    HttpResponse.json([{ id: LE, code: 'LE01', current: { legalNameVi: 'Công ty A', legalNameEn: 'Company A' } }]),
  ),
  http.get(`${API}/people/employees`, () =>
    HttpResponse.json({ items: [{ employmentId: EMP, personId: 'p1', employeeNo: 'NV0001', fullName: 'Nguyễn Văn An', status: 'ACTIVE' }], total: 1, page: 0, size: 20 }),
  ),
];

describe('OvertimeRequestsPage', () => {
  it('lists the requests and results of the selected employee in the month', async () => {
    let from: string | null = null;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/employments/${EMP}/overtime-requests`, ({ request: req }) => {
        from = new URL(req.url).searchParams.get('from');
        return HttpResponse.json([request]);
      }),
      http.get(`${API}/attendance/employments/${EMP}/overtime-results`, () => HttpResponse.json([result])),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OvertimeRequestsPage employmentId={EMP} onEmploymentChange={() => undefined} />);
    expect(await screen.findByText('Year-end inventory')).toBeInTheDocument();
    expect(screen.getByText('Draft')).toBeInTheDocument();
    expect(screen.getByText('Signed paper form')).toBeInTheDocument();
    expect(screen.getByText('17:00 – 19:30')).toBeInTheDocument();
    expect(from).toMatch(/^\d{4}-\d{2}-01$/);
    await user.click(screen.getByRole('tab', { name: 'Overtime results' }));
    expect(await screen.findByText('Night, weekly rest day')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request overtime' })).toBeInTheDocument();
  });

  it('asks for an employee first', async () => {
    server.use(...lookups);
    await changeLanguage('en');
    renderPage(<OvertimeRequestsPage onEmploymentChange={() => undefined} />);
    expect(await screen.findByText('Select an employee to see their data.')).toBeInTheDocument();
  });

  it('hides the create action without att.overtime.request', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.OVERTIME_VIEW))),
      http.get(`${API}/attendance/employments/${EMP}/overtime-requests`, () => HttpResponse.json([request])),
      http.get(`${API}/attendance/employments/${EMP}/overtime-results`, () => HttpResponse.json([])),
    );
    await changeLanguage('en');
    renderPage(<OvertimeRequestsPage employmentId={EMP} onEmploymentChange={() => undefined} />);
    expect(await screen.findByText('Year-end inventory')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request overtime' })).not.toBeInTheDocument();
  });
});

describe('OvertimeRequestDetailPage', () => {
  it('submits a draft for approval', async () => {
    let body: unknown;
    let current = request;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/overtime-requests/${R1}`, () => HttpResponse.json(current)),
      http.post(`${API}/attendance/overtime-requests/${R1}/submit`, async ({ request: req }) => {
        body = await req.json();
        current = { ...request, status: 'PENDING', version: 2 };
        return HttpResponse.json({
          request: current,
          submitted: true,
          findings: [{ code: 'ot-cap-month-warning', severity: 'WARN', message: 'Overtime reaches 90 %', data: {} }],
        });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OvertimeRequestDetailPage requestId={R1} />);
    expect(await screen.findByText('Overtime request of 20/11/2026')).toBeInTheDocument();
    // Consent already given: no consent action.
    expect(screen.queryByRole('button', { name: 'Record consent' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Submit for approval' }));
    await waitFor(() => expect(body).toEqual({ capOverrideReason: null }));
    expect(await screen.findByText('Overtime is close to the monthly cap.')).toBeInTheDocument();
    expect((await screen.findAllByText('Pending approval')).length).toBeGreaterThan(0);
  });

  it('shows blocking findings of a refused submit', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/attendance/overtime-requests/${R1}`, () => HttpResponse.json(request)),
      http.post(`${API}/attendance/overtime-requests/${R1}/submit`, () =>
        problem(422, 'ot-cap-month-exceeded', 'Overtime exceeds the monthly cap', {
          findings: [{ code: 'ot-cap-month-exceeded', severity: 'BLOCK', message: 'exceeds', data: {} }],
        }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OvertimeRequestDetailPage requestId={R1} />);
    await user.click(await screen.findByRole('button', { name: 'Submit for approval' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Submit for approval' }));
    expect(await within(dialog).findByText('The request was not submitted: resolve the blocking findings below.')).toBeInTheDocument();
    expect(within(dialog).getAllByText('Overtime exceeds the monthly cap.').length).toBeGreaterThan(0);
  });

  it('records consent on a draft without consent', async () => {
    let posted = false;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/overtime-requests/${R1}`, () =>
        HttpResponse.json({ ...request, employeeConsent: false, consentMethod: null, consentAt: null }),
      ),
      http.post(`${API}/attendance/overtime-requests/${R1}/consent`, () => {
        posted = true;
        return HttpResponse.json(request);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OvertimeRequestDetailPage requestId={R1} />);
    await user.click(await screen.findByRole('button', { name: 'Record consent' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(posted).toBe(true));
  });

  it('requires a reason to cancel an approved request', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/overtime-requests/${R1}`, () => HttpResponse.json({ ...request, status: 'APPROVED' })),
      http.post(`${API}/attendance/overtime-requests/${R1}/cancel`, async ({ request: req }) => {
        body = await req.json();
        return HttpResponse.json({ ...request, status: 'CANCELLED' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OvertimeRequestDetailPage requestId={R1} />);
    await user.click(await screen.findByRole('button', { name: 'Cancel request' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel request' }));
    expect(await within(dialog).findByText('Required')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Cancellation reason'), 'Order cancelled');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel request' }));
    await waitFor(() => expect(body).toEqual({ reason: 'Order cancelled' }));
  });

  it('hides consent, submit and cancel with view-only rights', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.OVERTIME_VIEW))),
      http.get(`${API}/attendance/overtime-requests/${R1}`, () => HttpResponse.json({ ...request, employeeConsent: false })),
    );
    await changeLanguage('en');
    renderPage(<OvertimeRequestDetailPage requestId={R1} />);
    expect(await screen.findByText('Overtime request of 20/11/2026')).toBeInTheDocument();
    // `/me` resolves after the request: wait for the gate to settle on the reduced set.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Submit for approval' })).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Record consent' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel request' })).not.toBeInTheDocument();
  });
});

describe('OvertimeCapsPage', () => {
  it('shows month / year usage against the cap and the legal entity regime', async () => {
    const caps: OvertimeCapView[] = [
      { kind: 'MONTH', periodStart: '2026-11-01', regime: 'STANDARD_200', capMin: 2400, openingMin: 0, approvedMin: 1800, pendingMin: 360, paidMin: 1500, overrideMin: 0, usedMin: 2160, level: 'WARN' },
      { kind: 'YEAR', periodStart: '2026-01-01', regime: 'STANDARD_200', capMin: 12000, openingMin: 600, approvedMin: 6000, pendingMin: 360, paidMin: 5000, overrideMin: 0, usedMin: 6960, level: 'OK' },
    ];
    server.use(
      ...lookups,
      http.get(`${API}/attendance/employments/${EMP}/overtime-caps`, () => HttpResponse.json(caps)),
      http.get(`${API}/attendance/legal-entities/${LE}/overtime-regime`, () =>
        HttpResponse.json({ legalEntityId: LE, asOf: '2026-11-27', regimeRequested: 'EXTENDED_300', regimeApplied: 'STANDARD_200', noticeRef: null, noticeRequired: true, monthCapMin: 2400, yearCapMin: 12000 }),
      ),
    );
    await changeLanguage('en');
    renderPage(<OvertimeCapsPage employmentId={EMP} legalEntityId={LE} onChange={() => undefined} />);
    expect(await screen.findByText('Month 11/2026')).toBeInTheDocument();
    expect(screen.getByText('Year 2026')).toBeInTheDocument();
    expect(screen.getByText('90%')).toBeInTheDocument();
    expect(screen.getByText('Near the cap')).toBeInTheDocument();
    expect(await screen.findByText(/no notice to the provincial labour authority/)).toBeInTheDocument();
    expect(screen.getByText('Up to 300 h / year')).toBeInTheDocument();
  });
});
