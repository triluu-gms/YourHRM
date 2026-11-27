import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { CodeRow, DayDetail, DayRow } from './api';
import { AttendanceCodesPage } from './AttendanceCodesPage';
import { TimesheetsPage } from './TimesheetsPage';
import { EmployeeTimesheetPage } from './EmployeeTimesheetPage';
import { timesToInstants } from './components';
import dayjs from 'dayjs';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const EMP2 = '018f0000-0000-7000-8000-0000000000e2';

const code = (over: Partial<CodeRow>): CodeRow => ({
  id: over.code ?? 'X',
  global: false,
  code: 'X',
  labelVi: 'Đi làm',
  labelEn: 'Work',
  kind: 'WORK',
  payrollCategory: 'WORKED_DAYS',
  payrollSubCode: null,
  countsAsWorked: true,
  paidBy: 'EMPLOYER',
  siNonwork: false,
  displayOrder: 1,
  colorHex: null,
  status: 'ACTIVE',
  version: 0,
  ...over,
});
const codes = [code({ id: 'c-x', code: 'X', global: true }), code({ id: 'c-ct', code: 'CT', labelVi: 'Công tác', labelEn: 'Trip (domestic)', kind: 'TRIP', displayOrder: 5, version: 2 })];

const day = (employmentId: string, workDate: string, over: Partial<DayRow> = {}): DayRow => ({
  id: `${employmentId}-${workDate}`,
  employmentId,
  workDate,
  dayType: 'WORKDAY',
  dayStatus: 'INCOMPLETE',
  primaryCode: 'X',
  codes: [{ code: 'X', qty: 1 }],
  firstInAt: '2026-11-20T01:00:00Z',
  lastOutAt: null,
  scheduledHours: 8,
  workedHours: 0,
  paidBreakHours: 0,
  protectedBreakMin: 0,
  lateMin: 0,
  earlyMin: 0,
  absentHours: 0,
  nightHours: 0,
  otCandidateMin: 0,
  dayValue: 0,
  exceptions: ['MISSING_OUT'],
  hasBlockingException: true,
  manualOverride: false,
  calcVersion: 2,
  ...over,
});

const lookups = [
  http.get(`${API}/core/legal-entities`, () => HttpResponse.json([])),
  http.get(`${API}/core/org-units`, () => HttpResponse.json([])),
  http.get(`${API}/core/locations`, () => HttpResponse.json([])),
  http.get(`${API}/people/employees`, () =>
    HttpResponse.json({
      items: [
        { employmentId: EMP, personId: 'p1', employeeNo: 'NV0001', fullName: 'Trần Thị Bình', status: 'ACTIVE' },
        { employmentId: EMP2, personId: 'p2', employeeNo: 'NV0002', fullName: 'Lê Văn Cường', status: 'ACTIVE' },
      ],
      total: 2,
      page: 0,
      size: 200,
    }),
  ),
  http.get(`${API}/attendance/attendance-codes`, () => HttpResponse.json(codes)),
];

describe('timesToInstants', () => {
  it('reads times in Asia/Ho_Chi_Minh and moves an out before the in to the next day', () => {
    const r = timesToInstants('2026-11-20', dayjs('2026-01-01T22:00:00'), dayjs('2026-01-01T06:00:00'));
    expect(r).toEqual({ inAt: '2026-11-20T15:00:00.000Z', outAt: '2026-11-20T23:00:00.000Z' });
  });
});

describe('AttendanceCodesPage', () => {
  it('lists codes, keeps platform codes read-only and creates a code', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      http.post(`${API}/attendance/attendance-codes`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(code({ id: 'n', code: 'WFH2' }), { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<AttendanceCodesPage />);

    expect(await screen.findByText('Trip (domestic)')).toBeInTheDocument();
    expect(screen.getByText('Platform default')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit code X' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Edit code CT' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: /Add attendance code/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Code'), 'wfh2');
    await user.type(within(drawer).getByLabelText('Label (VI)'), 'Làm tại nhà');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(body).toMatchObject({ code: 'WFH2', labelVi: 'Làm tại nhà', kind: 'WORK', paidBy: 'EMPLOYER', countsAsWorked: true, status: 'ACTIVE' }),
    );
  }, 30_000);

  it('updates a tenant code with its version and shows the problem', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      ...lookups,
      http.put(`${API}/attendance/attendance-codes/c-ct`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return problem(409, 'stale-version');
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<AttendanceCodesPage />);
    await user.click(await screen.findByRole('button', { name: 'Edit code CT' }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toMatchObject({ code: 'CT', kind: 'TRIP', version: 2 }));
  }, 30_000);

  it('hides changes without att.attendance_code.update', async () => {
    server.use(...lookups, http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.ATTENDANCE_CODE_VIEW))));
    await changeLanguage('en');
    renderPage(<AttendanceCodesPage />);
    expect(await screen.findByText('Trip (domestic)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add attendance code/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit code CT' })).not.toBeInTheDocument();
  }, 20_000);
});

describe('TimesheetsPage', () => {
  const grid = [day(EMP, '2026-11-20'), day(EMP2, '2026-11-21', { dayStatus: 'PRESENT', exceptions: [], hasBlockingException: false })];

  it('filters the grid and bulk-edits the selected days with a reason', async () => {
    let body: unknown;
    let lastQuery: URLSearchParams | undefined;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/timesheets`, ({ request }) => {
        lastQuery = new URL(request.url).searchParams;
        return HttpResponse.json(grid);
      }),
      http.post(`${API}/attendance/timesheets/edits`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ bulkBatchId: 'b1', correctionIds: ['c1', 'c2'], applied: 2 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<TimesheetsPage />);

    expect(await screen.findByText('NV0001 – Trần Thị Bình')).toBeInTheDocument();
    expect(screen.getByText('Missing out')).toBeInTheDocument();
    expect(lastQuery?.get('from')).toMatch(/^\d{4}-\d{2}-01$/);

    await user.click(screen.getByRole('checkbox', { name: 'Only close-blocking exceptions' }));
    await waitFor(() => expect(lastQuery?.get('blockingOnly')).toBe('true'));

    const rows = screen.getAllByRole('row').slice(1);
    for (const r of rows) await user.click(within(r).getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /Bulk edit/ }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('In / out times can only be set when all selected days share one work date.')).toBeInTheDocument();

    await user.click(within(drawer).getByLabelText('Code'));
    await user.click(await screen.findByText('CT – Trip (domestic)'));
    await user.type(within(drawer).getByLabelText('Reason'), 'Client visit');
    await user.click(within(drawer).getByRole('button', { name: 'Adjust' }));
    await waitFor(() =>
      expect(body).toEqual({
        days: [
          { employmentId: EMP, workDate: '2026-11-20' },
          { employmentId: EMP2, workDate: '2026-11-21' },
        ],
        code: 'CT',
        inAt: null,
        outAt: null,
        reason: 'Client visit',
      }),
    );
  }, 40_000);

  it('offers no selection, bulk edit or recompute without att.timesheet.update', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.TIMESHEET_VIEW))),
      http.get(`${API}/attendance/timesheets`, () => HttpResponse.json(grid)),
    );
    await changeLanguage('en');
    renderPage(<TimesheetsPage />);
    expect(await screen.findByText('NV0001 – Trần Thị Bình')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Bulk edit/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Recompute timesheets/ })).not.toBeInTheDocument();
    expect(within(screen.getAllByRole('row')[1] as HTMLElement).queryByRole('checkbox')).not.toBeInTheDocument();
  }, 20_000);
});

describe('EmployeeTimesheetPage', () => {
  const search = { employmentId: EMP, from: '2026-11-01', to: '2026-11-30' };
  const detail: DayDetail = {
    day: day(EMP, '2026-11-20'),
    punches: [
      {
        id: 'p1',
        workDateHint: '2026-11-20',
        punchAt: '2026-11-20T01:00:00Z',
        localTs: '2026-11-20T08:00:00',
        source: 'IMPORT',
        terminalCode: 'ZK001',
        enrolId: '1001',
        direction: 'IN',
        validationStatus: 'VALID',
      },
    ],
    corrections: [],
  };

  function handlers() {
    return [
      ...lookups,
      http.get(`${API}/attendance/employments/${EMP}/timesheets`, () => HttpResponse.json([detail.day])),
      http.get(`${API}/attendance/employments/${EMP}/timesheets/2026-11-20`, () => HttpResponse.json(detail)),
      http.get(`${API}/attendance/employments/${EMP}/punches`, () => HttpResponse.json(detail.punches)),
      http.get(`${API}/attendance/employments/${EMP}/menstrual-breaks`, () =>
        HttpResponse.json({ employmentId: EMP, month: '2026-11', activeInMonth: 1, daysPerMonth: 3, days: [{ id: 'm1', workDate: '2026-11-10', source: 'ADMIN', status: 'ACTIVE', createdAt: '2026-11-10T02:00:00Z' }] }),
      ),
    ];
  }

  it('asks for an employee first', async () => {
    server.use(...lookups);
    await changeLanguage('en');
    renderPage(<EmployeeTimesheetPage search={{}} onChange={() => undefined} />);
    expect(await screen.findByText('Select an employee to see their data.')).toBeInTheDocument();
  });

  it('opens a day and requests a correction through the workflow', async () => {
    let body: unknown;
    server.use(
      ...handlers(),
      http.post(`${API}/attendance/employments/${EMP}/timesheet-corrections`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ id: 'c1', status: 'PENDING' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmployeeTimesheetPage search={search} onChange={() => undefined} />);

    await user.click(await screen.findByRole('button', { name: 'View 20/11/2026' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('1001')).toBeInTheDocument();
    expect(within(drawer).getByText('Valid')).toBeInTheDocument();

    await user.click(within(drawer).getByRole('button', { name: /Request correction/ }));
    const form = (await screen.findAllByRole('dialog')).at(-1) as HTMLElement;
    await user.type(within(form).getByLabelText('Reason'), 'Forgot to punch out');
    await user.click(within(form).getByRole('button', { name: 'Request correction' }));
    await waitFor(() =>
      expect(body).toMatchObject({
        workDate: '2026-11-20',
        correctionType: 'MISSING_PUNCH',
        requestedIn: null,
        requestedOut: null,
        requestedCode: null,
        reasonText: 'Forgot to punch out',
      }),
    );
  }, 40_000);

  it('shows menstrual-break days only with att.menstrual_break.view', async () => {
    server.use(
      ...handlers(),
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.TIMESHEET_VIEW, P.PUNCH_VIEW))),
    );
    await changeLanguage('en');
    renderPage(<EmployeeTimesheetPage search={search} onChange={() => undefined} />);
    expect(await screen.findByRole('tab', { name: 'Raw punches' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Menstrual break' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Recompute timesheets/ })).not.toBeInTheDocument();
  }, 20_000);

  it('lists and withdraws a menstrual-break declaration', async () => {
    let withdrawn = false;
    server.use(
      ...handlers(),
      http.delete(`${API}/attendance/employments/${EMP}/menstrual-breaks/2026-11-10`, () => {
        withdrawn = true;
        return HttpResponse.json({ id: 'm1', workDate: '2026-11-10', source: 'ADMIN', status: 'WITHDRAWN', createdAt: '2026-11-10T02:00:00Z' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmployeeTimesheetPage search={search} onChange={() => undefined} />);
    await user.click(await screen.findByRole('tab', { name: 'Menstrual break' }));
    expect(await screen.findByText('1 / 3 days declared this month')).toBeInTheDocument();
    expect(screen.getByText('Declared by HR')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Withdraw the declaration of 10/11/2026' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(withdrawn).toBe(true));
  }, 30_000);
});
