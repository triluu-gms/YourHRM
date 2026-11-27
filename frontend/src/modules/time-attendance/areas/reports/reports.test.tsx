import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { GridReport, InboxMessage, NightlySummary } from './api';
import { TimesheetGridReportPage } from './TimesheetReports';
import { ReportsIndexPage } from './ReportsIndexPage';
import { IntegrationInboxPage } from './IntegrationInboxPage';
import { JobsPage } from './JobsPage';

const LE = '018f0000-0000-7000-8000-0000000000a1';
const EMP = '018f0000-0000-7000-8000-0000000000a2';

const lookups = [
  http.get(`${API}/core/legal-entities`, () =>
    HttpResponse.json([{ id: LE, code: 'LE01', status: 'ACTIVE', current: { shortName: 'Demo VN' } }]),
  ),
  http.get(`${API}/core/org-units`, () => HttpResponse.json([])),
  http.get(`${API}/core/locations`, () => HttpResponse.json([])),
  http.get(`${API}/attendance/attendance-periods`, () => HttpResponse.json([])),
  http.get(`${API}/people/employees`, () => HttpResponse.json({ items: [], total: 0, page: 0, size: 20 })),
];

const grid: GridReport = {
  legalEntityId: null,
  orgUnitId: null,
  periodId: null,
  from: '2026-11-02',
  to: '2026-11-03',
  dates: [
    { date: '2026-11-02', dayOfMonth: 2, weekday: 'T2' },
    { date: '2026-11-03', dayOfMonth: 3, weekday: 'T3' },
  ],
  categories: [{ code: 'WORKED_DAYS', label: 'Worked days' }],
  codes: [
    { code: 'X', label: 'Present', payrollCategory: 'WORKED_DAYS' },
    { code: 'P', label: 'Annual leave', payrollCategory: 'PAID_LEAVE_DAYS' },
  ],
  page: {
    items: [
      {
        employmentId: EMP,
        employeeNo: 'NV0001',
        fullName: 'Nguyễn Văn An',
        orgUnitId: null,
        cells: ['X', 'P'],
        codeTotals: { X: 1, P: 1 },
        categoryTotals: { WORKED_DAYS: 1 },
        workedHours: 8,
        overtimeHours: 0,
        nightHours: 0,
        lateMin: 5,
        earlyMin: 0,
        exceptionDays: 1,
        missingDays: 0,
      },
    ],
    limit: 200,
    offset: 0,
    hasMore: false,
  },
  pageTotals: { WORKED_DAYS: 1 },
};

describe('TimesheetGridReportPage', () => {
  it('runs the grid with the applied filters and shows the employee × day codes', async () => {
    let query: URLSearchParams | null = null;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/reports/timesheet-grid`, ({ request }) => {
        query = new URL(request.url).searchParams;
        return HttpResponse.json(grid);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<TimesheetGridReportPage />);

    expect(await screen.findByText('Choose the filters and press Search to run the report.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Export Excel/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /Search/ }));

    expect(await screen.findByText('Nguyễn Văn An')).toBeInTheDocument();
    expect(screen.getByText('NV0001')).toBeInTheDocument();
    expect(screen.getByText('Annual leave')).toBeInTheDocument(); // legend
    const q = query as unknown as URLSearchParams;
    expect(q.get('from')).toMatch(/^\d{4}-\d{2}-01$/);
    expect(q.get('to')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(q.get('lang')).toBe('en');
    expect(q.get('limit')).toBe('200');
    expect(screen.getByRole('button', { name: /Export Excel/ })).toBeEnabled();
  });

  it('hides the Excel export without att.report.export', async () => {
    server.use(...lookups, http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.TIMESHEET_VIEW))));
    await changeLanguage('en');
    renderPage(<TimesheetGridReportPage />);
    expect(await screen.findByRole('button', { name: /Search/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Export Excel/ })).not.toBeInTheDocument();
  });
});

describe('ReportsIndexPage', () => {
  it('lists only the reports the user may run', async () => {
    server.use(http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.OVERTIME_VIEW))));
    await changeLanguage('en');
    renderPage(<ReportsIndexPage />);
    expect(await screen.findByText('Overtime summary')).toBeInTheDocument();
    expect(screen.getByText('Overtime cap overrides')).toBeInTheDocument();
    expect(screen.queryByText('Timesheet grid')).not.toBeInTheDocument();
    expect(screen.queryByText('Excel export available')).not.toBeInTheDocument();
  });
});

describe('IntegrationInboxPage', () => {
  const failed: InboxMessage = {
    consumer: 'att.assignment',
    eventId: '018f0000-0000-7000-8000-0000000000a9',
    eventType: 'people.AssignmentChanged',
    status: 'FAILED',
    attempts: 3,
    lastError: 'boom',
    receivedAt: '2026-11-20T01:00:00Z',
    processedAt: null,
  };

  it('resyncs the locations after confirmation and shows the summary', async () => {
    let resynced = false;
    server.use(
      http.get(`${API}/attendance/integration/inbox`, () => HttpResponse.json([failed])),
      http.post(`${API}/attendance/integration/locations/resync`, () => {
        resynced = true;
        return HttpResponse.json({ locations: 4, versions: 6 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<IntegrationInboxPage />);
    expect(await screen.findByText('people.AssignmentChanged')).toBeInTheDocument();
    expect(screen.getByText('Failed')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Resync locations/ }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('4 locations resynced (6 versions).')).toBeInTheDocument();
    expect(resynced).toBe(true);
  });

  it('hides the resync without att.integration.resync', async () => {
    server.use(
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.INTEGRATION_VIEW))),
      http.get(`${API}/attendance/integration/inbox`, () => HttpResponse.json([failed])),
    );
    await changeLanguage('en');
    renderPage(<IntegrationInboxPage />);
    expect(await screen.findByText('people.AssignmentChanged')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Resync locations/ })).not.toBeInTheDocument();
  });
});

describe('JobsPage', () => {
  it('runs the nightly job after the confirm dialog and lists the steps', async () => {
    let body: unknown;
    const summary: NightlySummary = {
      day: '2026-11-27',
      steps: [
        { step: 'PERIOD_REVIEW', count: 2, failures: [] },
        { step: 'RECALCULATION', count: 10, failures: ['emp 1: boom'] },
      ],
    };
    server.use(
      http.post(`${API}/attendance/jobs/nightly/run`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(summary);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<JobsPage />);
    await user.click(await screen.findByRole('button', { name: /Run nightly job/ }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Nightly job ran for 27/11/2026; some steps failed.')).toBeInTheDocument();
    expect(screen.getByText('Recalculate timesheets')).toBeInTheDocument();
    expect(screen.getByText('emp 1: boom')).toBeInTheDocument();
    await waitFor(() => expect(body).toEqual({}));
  });

  it('shows only the reminders card without att.job.run', async () => {
    server.use(http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.REMINDER_RUN))));
    await changeLanguage('en');
    renderPage(<JobsPage />);
    expect(await screen.findByRole('button', { name: /Send reminders/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Run nightly job/ })).not.toBeInTheDocument();
  });
});
