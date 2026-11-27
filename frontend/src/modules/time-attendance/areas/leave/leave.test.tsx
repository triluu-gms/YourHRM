import { beforeEach, describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { meWith } from '@/test/fixtures';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { BalanceView, LeaveRequestRow, LeaveTypeRow, RunSummary } from './api';
import { LeaveTypesPage } from './LeaveTypesPage';
import { NewLeaveRequestDrawer } from './NewLeaveRequestDrawer';
import { LeaveBalancesPage } from './LeaveBalancesPage';
import { LeaveRequestDetailPage } from './LeaveRequestDetailPage';
import { LeaveJobsPage } from './LeaveJobsPage';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';
const T_ANNUAL = '018f0000-0000-7000-8000-0000000000a1';
const T_SICK = '018f0000-0000-7000-8000-0000000000a2';
const REQ = '018f0000-0000-7000-8000-0000000000b1';
const A = `${API}/attendance`;

const annual: LeaveTypeRow = {
  id: T_ANNUAL,
  version: 0,
  data: {
    code: 'ANNUAL',
    nameVi: 'Phép năm',
    nameEn: 'Annual leave',
    category: 'ANNUAL',
    paidBy: 'EMPLOYER',
    minUnit: 'HALF_DAY',
    countingBasis: 'WORKING_DAY',
    tracksBalance: true,
    requiresSiClaimData: false,
    requiresAttachment: false,
    countsAsService: true,
    attendanceCode: 'P',
    payrollCategory: 'PAID_LEAVE_DAYS',
    eligibility: {},
    statutory: true,
    status: 'ACTIVE',
  },
};
const sick: LeaveTypeRow = {
  id: T_SICK,
  version: 0,
  data: {
    ...annual.data,
    code: 'SICK',
    nameVi: 'Nghỉ ốm',
    nameEn: 'Sick leave',
    category: 'SICK',
    paidBy: 'SI_FUND',
    minUnit: 'DAY',
    tracksBalance: false,
    requiresSiClaimData: true,
    attendanceCode: 'Ô',
    payrollCategory: 'SI_LEAVE_DAYS',
  },
};

const request: LeaveRequestRow = {
  id: REQ,
  employmentId: EMP,
  personId: PERSON,
  legalEntityId: null,
  orgUnitId: null,
  leaveTypeId: T_SICK,
  leaveTypeCode: 'SICK',
  requestUnit: 'DAY',
  startDate: '2026-12-01',
  endDate: '2026-12-02',
  startPart: 'FULL',
  endPart: 'FULL',
  durationDays: 2,
  reason: 'Cảm cúm',
  status: 'PENDING',
  siCertificateType: 'C65',
  siCertificateNoLast4: '6789',
  siCertificateDate: '2026-12-01',
  hasSiClaimData: true,
  createdAt: '2026-11-27T02:00:00Z',
};

const baseHandlers = () => [
  http.get(`${A}/leave-types`, () => HttpResponse.json([annual, sick])),
  http.get(`${API}/core/legal-entities`, () => HttpResponse.json([])),
  http.get(`${API}/core/org-units`, () => HttpResponse.json([])),
  http.get(`${API}/people/employees`, () =>
    HttpResponse.json({
      items: [{ employmentId: EMP, personId: PERSON, employeeNo: 'NV0001', fullName: 'Nguyễn Thị Lan', status: 'ACTIVE' }],
      total: 1,
      page: 0,
      size: 20,
    }),
  ),
];

/** AntD Select: open by its label, pick the option by its title. */
async function pick(user: ReturnType<typeof userEvent.setup>, label: string, option: string) {
  await user.click(screen.getByLabelText(label));
  const opts = await screen.findAllByTitle(option);
  await user.click(opts[opts.length - 1] as HTMLElement);
}

/** AntD DatePicker: type dd/MM/yyyy and confirm. */
async function typeDate(user: ReturnType<typeof userEvent.setup>, label: string, value: string) {
  const input = screen.getByLabelText(label);
  await user.click(input);
  await user.type(input, `${value}{Enter}`);
}

beforeEach(async () => {
  await changeLanguage('en');
});

describe('LeaveTypesPage', () => {
  it('lists the leave types with translated categories and seeds the statutory types', async () => {
    let seeded = false;
    server.use(
      ...baseHandlers(),
      http.post(`${A}/leave-types/statutory`, () => {
        seeded = true;
        return HttpResponse.json({ created: ['MARRIAGE'], existing: ['ANNUAL', 'SICK'], warnings: [] });
      }),
    );
    const user = userEvent.setup();
    renderPage(<LeaveTypesPage />);
    expect(await screen.findByText('Sick leave')).toBeInTheDocument();
    expect(screen.getByText('Sick leave (SI)')).toBeInTheDocument();
    expect(screen.getAllByText('Annual leave')).toHaveLength(2);
    expect(screen.getAllByText('Statutory').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('button', { name: 'Seed statutory types' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(seeded).toBe(true));
    expect(await screen.findByText('1 leave types created; 2 already existed.')).toBeInTheDocument();
  });

  it('hides create, edit and seed without att.leave_type.update', async () => {
    server.use(...baseHandlers(), http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.LEAVE_TYPE_VIEW))));
    renderPage(<LeaveTypesPage />);
    expect(await screen.findByText('Sick leave')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Seed statutory types' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });
});

describe('NewLeaveRequestDrawer', () => {
  it('previews the counted days and balance, then submits the same body', async () => {
    const bodies: { preview?: unknown; submit?: unknown } = {};
    server.use(
      ...baseHandlers(),
      http.post(`${A}/employments/${EMP}/leave-requests/preview`, async ({ request: r }) => {
        bodies.preview = await r.json();
        return HttpResponse.json({
          leaveTypeCode: 'ANNUAL',
          requestUnit: 'DAY',
          durationDays: 1.5,
          durationHours: null,
          days: [
            { date: '2026-12-01', quantity: 1, part: 'FULL' },
            { date: '2026-12-02', quantity: 0.5, part: 'AM' },
          ],
          daysPerLeaveYear: { '2026': 1.5 },
          available: { '2026': 0.5 },
          warnings: [],
        });
      }),
      http.post(`${A}/employments/${EMP}/leave-requests`, async ({ request: r }) => {
        bodies.submit = await r.json();
        return HttpResponse.json({ ...request, id: REQ, leaveTypeId: T_ANNUAL, status: 'PENDING' }, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    let created: LeaveRequestRow | null = null;
    renderPage(<NewLeaveRequestDrawer open employmentId={EMP} onClose={() => {}} onCreated={(r) => (created = r)} />);
    await screen.findByText('New leave request');
    await pick(user, 'Leave type', 'ANNUAL – Annual leave');
    await typeDate(user, 'Start date', '01/12/2026');
    await typeDate(user, 'End date', '02/12/2026');
    await pick(user, 'End part', 'Morning');

    await user.click(screen.getByRole('button', { name: 'Preview' }));
    const preview = await screen.findByTestId('leave-preview');
    expect(within(preview).getByText('1,5 days')).toBeInTheDocument();
    expect(within(preview).getByText('2026')).toBeInTheDocument();
    expect(within(preview).getByText('0,5')).toBeInTheDocument();
    expect(bodies.preview).toMatchObject({
      leaveTypeId: T_ANNUAL,
      startDate: '2026-12-01',
      endDate: '2026-12-02',
      startPart: 'FULL',
      endPart: 'AM',
      siClaim: null,
    });

    await user.click(screen.getByRole('button', { name: 'Submit' }));
    await waitFor(() => expect(bodies.submit).toEqual(bodies.preview));
    await waitFor(() => expect(created).not.toBeNull());
  });

  it('shows a refused submit as a translated problem', async () => {
    server.use(
      ...baseHandlers(),
      http.post(`${A}/employments/${EMP}/leave-requests/preview`, () =>
        HttpResponse.json({
          leaveTypeCode: 'ANNUAL',
          requestUnit: 'DAY',
          durationDays: 1,
          days: [{ date: '2026-12-01', quantity: 1, part: 'FULL' }],
          daysPerLeaveYear: { '2026': 1 },
          available: { '2026': -1 },
          warnings: ['insufficient-balance 2026'],
        }),
      ),
      http.post(`${A}/employments/${EMP}/leave-requests`, () => problem(409, 'insufficient-balance')),
    );
    const user = userEvent.setup();
    renderPage(<NewLeaveRequestDrawer open employmentId={EMP} onClose={() => {}} />);
    await screen.findByText('New leave request');
    await pick(user, 'Leave type', 'ANNUAL – Annual leave');
    await typeDate(user, 'Start date', '01/12/2026');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await screen.findByText('Leave year 2026: not enough balance.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Submit' }));
    expect(await screen.findByText('Not enough leave balance.')).toBeInTheDocument();
  });
});

describe('LeaveBalancesPage', () => {
  const balance: BalanceView = {
    leaveTypeId: T_ANNUAL,
    leaveTypeCode: 'ANNUAL',
    leaveYear: 2026,
    opening: 2,
    accrued: 11,
    carried: 0,
    taken: 5,
    adjusted: 0,
    expired: 0,
    encashed: 0,
    balance: 8,
    pending: 1.5,
    available: 6.5,
    yearStart: '2026-01-01',
    yearEnd: '2026-12-31',
  };

  it('shows the balances and posts a manual adjustment with its reason', async () => {
    let body: unknown;
    server.use(
      ...baseHandlers(),
      http.get(`${A}/employments/${EMP}/leave-balances`, () => HttpResponse.json([balance])),
      http.post(`${A}/employments/${EMP}/leave-adjustments`, async ({ request: r }) => {
        body = await r.json();
        return HttpResponse.json({ outcome: 'APPROVAL_REQUESTED', row: null, approvalRequestId: 'x' }, { status: 202 });
      }),
    );
    const user = userEvent.setup();
    renderPage(<LeaveBalancesPage search={{ employmentId: EMP, asOf: '2026-11-27' }} onChange={() => {}} />);
    expect(await screen.findByText('6,5')).toBeInTheDocument();
    expect(screen.getByText('ANNUAL – Annual leave')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Adjust balance' }));
    await screen.findByText('Each adjustment is a ledger row (never edited or deleted) and needs a reason.');
    await pick(user, 'Leave type', 'ANNUAL – Annual leave');
    await user.type(screen.getByLabelText('Quantity'), '-1.5');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('A reason is required.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Reason'), 'Chấm công sai tháng 10');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(body).toEqual({ leaveTypeId: T_ANNUAL, leaveYear: null, quantity: -1.5, reason: 'Chấm công sai tháng 10' }),
    );
    expect(await screen.findByText('Adjustment sent for approval.')).toBeInTheDocument();
  });

  it('hides the adjustment without att.leave_balance.adjust', async () => {
    server.use(
      ...baseHandlers(),
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.LEAVE_BALANCE_VIEW, P.LEAVE_TYPE_VIEW))),
      http.get(`${A}/employments/${EMP}/leave-balances`, () => HttpResponse.json([balance])),
    );
    renderPage(<LeaveBalancesPage search={{ employmentId: EMP }} onChange={() => {}} />);
    expect(await screen.findByText('6,5')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Adjust balance' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Requests' })).not.toBeInTheDocument();
  });
});

describe('LeaveRequestDetailPage', () => {
  const handlers = () => [
    ...baseHandlers(),
    http.get(`${A}/leave-requests/${REQ}`, () => HttpResponse.json(request)),
  ];

  it('reveals the SI claim data on demand and withdraws a pending request', async () => {
    let withdrawn: unknown;
    server.use(
      ...handlers(),
      http.get(`${A}/leave-requests/${REQ}/si-claim`, () =>
        HttpResponse.json({ certificateType: 'C65', certificateNo: 'AB-123456789', issuingFacility: 'BV Bạch Mai' }),
      ),
      http.post(`${A}/leave-requests/${REQ}/withdraw`, async ({ request: r }) => {
        withdrawn = await r.json();
        return HttpResponse.json({ ...request, status: 'WITHDRAWN' });
      }),
    );
    const user = userEvent.setup();
    renderPage(<LeaveRequestDetailPage requestId={REQ} />);
    expect(await screen.findByText('••••6789')).toBeInTheDocument();
    expect(screen.queryByText('AB-123456789')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Show SI data' }));
    expect(await screen.findByText('AB-123456789')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Withdraw' }));
    await user.type(await screen.findByLabelText('Reason'), 'Đã khỏi');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(withdrawn).toEqual({ reason: 'Đã khỏi' }));
  });

  it('hides SI data and the actions without the permissions (view only)', async () => {
    server.use(...handlers(), http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.LEAVE_REQUEST_VIEW, P.LEAVE_TYPE_VIEW))));
    renderPage(<LeaveRequestDetailPage requestId={REQ} />);
    expect(await screen.findByText('••••6789')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show SI data' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });
});

describe('LeaveJobsPage', () => {
  it('runs the year-end after confirmation and shows the summary', async () => {
    let body: unknown;
    const summary: RunSummary = {
      run: 'YEAR_END 2025',
      employments: 12,
      written: 10,
      skipped: 1,
      flagged: [`${EMP} ANNUAL 2025: 3 days flagged`],
      failures: { [EMP]: 'no policy' },
    };
    server.use(
      ...baseHandlers(),
      http.post(`${A}/leave-balances/year-end`, async ({ request: r }) => {
        body = await r.json();
        return HttpResponse.json(summary);
      }),
    );
    const user = userEvent.setup();
    renderPage(<LeaveJobsPage />);
    await screen.findByText('Year-end carry-over');
    const yearInput = screen.getByLabelText('Closing leave year');
    await user.clear(yearInput);
    await user.type(yearInput, '2025');
    await user.click(screen.getAllByRole('button', { name: 'Run' })[1] as HTMLElement);
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(body).toEqual({ legalEntityId: null, year: 2025 }));
    const result = await screen.findByTestId('run-result');
    expect(within(result).getByText('Year-end 2025: 12 employments, 10 written, 1 skipped, 1 failed.')).toBeInTheDocument();
    expect(within(result).getByText('no policy')).toBeInTheDocument();
  });
});
