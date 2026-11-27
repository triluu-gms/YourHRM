import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { meWith } from '@/test/fixtures';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { AttendanceResult, Checklist, ImportJobView, PeriodView, ResultLine } from './api';
import { PeriodsListPage } from './PeriodsListPage';
import { PeriodDetailPage } from './PeriodDetailPage';
import { AttendanceResultPage } from './AttendanceResultPage';
import { ResultImportsPage } from './ResultImportsPage';

const LE = '018f0000-0000-7000-8000-0000000000a1';
const PG = '018f0000-0000-7000-8000-0000000000a2';
const PER = '018f0000-0000-7000-8000-0000000000c1';
const RES = '018f0000-0000-7000-8000-0000000000d1';
const EMP = '018f0000-0000-7000-8000-0000000000e1';
const JOB = '018f0000-0000-7000-8000-0000000000f1';

const period: PeriodView = {
  id: PER,
  legalEntityId: LE,
  payGroupId: PG,
  periodStart: '2026-10-01',
  periodEnd: '2026-10-31',
  otPeriodStart: null,
  otPeriodEnd: null,
  stdDayMethod: 'FIXED_DAYS',
  stdDayValue: 26,
  closeDeadline: '2026-11-03',
  status: 'IN_REVIEW',
  currentVersion: 0,
  lockedVersion: null,
  closedAt: null,
  reopenedAt: null,
  reopenReason: null,
  reopenRequestedAt: null,
  reopenRequestReason: null,
  activeHold: null,
  version: 3,
};

const checklist: Checklist = {
  periodId: PER,
  employmentId: null,
  dateFrom: '2026-10-01',
  dateTo: '2026-10-31',
  canClose: false,
  items: [
    { code: 'MISSING_PUNCH', count: 4, blocking: true, waivable: true },
    { code: 'PENDING_LEAVE', count: 1, blocking: true, waivable: true },
  ],
};

const result: AttendanceResult = {
  id: RES,
  periodId: PER,
  legalEntityId: LE,
  payGroupId: PG,
  periodStart: '2026-10-01',
  periodEnd: '2026-10-31',
  scope: 'PERIOD',
  employmentId: null,
  kind: 'REGULAR',
  version: 1,
  status: 'PUBLISHED',
  attDateFrom: '2026-10-01',
  attDateTo: '2026-10-31',
  otDateFrom: '2026-10-01',
  otDateTo: '2026-10-31',
  checksum: 'abc123',
  lineCount: 2,
  closeCause: 'CLOSE',
  ruleVersion: 'r1',
  publishedAt: '2026-11-02T03:00:00Z',
};

const lookups = [
  http.get(`${API}/core/legal-entities`, () =>
    HttpResponse.json([{ id: LE, code: 'LE01', current: { legalNameVi: 'Công ty A', legalNameEn: 'Company A' } }]),
  ),
  http.get(`${API}/people/employees`, () => HttpResponse.json({ items: [], total: 0, page: 0, size: 20 })),
];

function detailHandlers(p: PeriodView = period) {
  return [
    ...lookups,
    http.get(`${API}/attendance/attendance-periods/${PER}`, () => HttpResponse.json(p)),
    http.get(`${API}/attendance/attendance-periods/${PER}/checklist`, () => HttpResponse.json(checklist)),
    http.get(`${API}/attendance/attendance-periods/${PER}/results`, () => HttpResponse.json([result])),
    http.get(`${API}/attendance/attendance-periods/${PER}/holds`, () => HttpResponse.json([])),
    http.get(`${API}/attendance/attendance-periods/${PER}/retro-requests`, () => HttpResponse.json([])),
  ];
}

describe('PeriodsListPage', () => {
  it('lists periods and filters by status', async () => {
    const statuses: (string | null)[] = [];
    server.use(
      ...lookups,
      http.get(`${API}/attendance/attendance-periods`, ({ request }) => {
        statuses.push(new URL(request.url).searchParams.get('status'));
        return HttpResponse.json([period]);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PeriodsListPage />);
    expect(await screen.findByText('01/10/2026 – 31/10/2026')).toBeInTheDocument();
    expect(screen.getByText('In review')).toBeInTheDocument();
    expect(await screen.findByText('LE01 – Company A')).toBeInTheDocument();
    expect(screen.getByText('Fixed days')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByTitle('Closed'));
    await waitFor(() => expect(statuses).toContain('CLOSED'));
  });
});

describe('PeriodDetailPage', () => {
  it('closes the period with waived checklist items and a reason', async () => {
    let body: unknown;
    server.use(
      ...detailHandlers(),
      http.post(`${API}/attendance/attendance-periods/${PER}/close`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...result, version: 1 }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PeriodDetailPage periodId={PER} />);
    expect(await screen.findByText('Attendance period 01/10/2026 – 31/10/2026')).toBeInTheDocument();
    expect(await screen.findByText('Missing punches')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Review / close / reopen' }));
    await user.click(await screen.findByRole('button', { name: 'Close period' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('checkbox', { name: 'Waive MISSING_PUNCH' }));
    await user.click(within(dialog).getByRole('checkbox', { name: 'Waive PENDING_LEAVE' }));
    // A waiver without a reason is refused locally.
    await user.click(within(dialog).getByRole('button', { name: 'Close period' }));
    expect(await within(dialog).findByText('Required')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Waiver reason'), 'Device outage on 12/10');
    await user.click(within(dialog).getByRole('button', { name: 'Close period' }));
    await waitFor(() =>
      expect(body).toEqual({ waive: ['MISSING_PUNCH', 'PENDING_LEAVE'], waiveReason: 'Device outage on 12/10' }),
    );
    expect(await screen.findByText('Period closed: version 1')).toBeInTheDocument();
  });

  it('shows the blocking items of a refused close', async () => {
    server.use(
      ...detailHandlers(),
      http.post(`${API}/attendance/attendance-periods/${PER}/close`, () =>
        problem(422, 'close-blocked', 'blocked', { items: [{ code: 'STD_METHOD_MISSING', count: 1, blocking: true, waivable: false }] }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PeriodDetailPage periodId={PER} />);
    await user.click(await screen.findByRole('tab', { name: 'Review / close / reopen' }));
    await user.click(await screen.findByRole('button', { name: 'Close period' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Close period' }));
    expect(await within(dialog).findByText('Checklist items block the close: resolve or waive them first.')).toBeInTheDocument();
    expect(within(dialog).getByText('No standard-day method from the pay calendar')).toBeInTheDocument();
  });

  it('reopens a closed period with a reason and reports a release request when held', async () => {
    let body: unknown;
    const closed: PeriodView = { ...period, status: 'CLOSED', currentVersion: 1 };
    server.use(
      ...detailHandlers(closed),
      http.post(`${API}/attendance/attendance-periods/${PER}/reopen`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ outcome: 'RELEASE_REQUESTED', period: closed, hold: null }, { status: 202 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PeriodDetailPage periodId={PER} />);
    await user.click(await screen.findByRole('tab', { name: 'Review / close / reopen' }));
    expect(screen.queryByRole('button', { name: 'Close period' })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Reopen period' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('Reopen reason'), 'Late leave approval');
    await user.click(within(dialog).getByRole('button', { name: 'Reopen period' }));
    await waitFor(() => expect(body).toEqual({ reason: 'Late leave approval' }));
    expect(await screen.findByText('Payroll holds the period: a release request was sent')).toBeInTheDocument();
  });

  it('lists the result versions with links', async () => {
    server.use(...detailHandlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PeriodDetailPage periodId={PER} />);
    await user.click(await screen.findByRole('tab', { name: 'Attendance results' }));
    expect(await screen.findByRole('link', { name: 'v1' })).toBeInTheDocument();
    expect(screen.getByText('Published')).toBeInTheDocument();
  });

  it('hides review, close and reopen without the close / reopen permissions', async () => {
    server.use(...detailHandlers({ ...period, status: 'OPEN' }), http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.PERIOD_VIEW))));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PeriodDetailPage periodId={PER} />);
    await user.click(await screen.findByRole('tab', { name: 'Review / close / reopen' }));
    expect(await screen.findByText('The period is open: move it to review or close it.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Close period' })).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Move to review' })).not.toBeInTheDocument();
  });
});

describe('AttendanceResultPage', () => {
  it('shows the header and the lines with retro markers', async () => {
    const lines: ResultLine[] = [
      { employmentId: EMP, segmentFrom: '2026-10-01', segmentTo: '2026-10-31', legalEntityId: LE, category: 'WORKED_DAYS', unit: 'DAYS', quantity: 21.5, periodQuantity: null, precededByDayOt: false, retro: false },
      { employmentId: EMP, segmentFrom: '2026-10-01', segmentTo: '2026-10-31', legalEntityId: LE, category: 'RETRO.UNPAID_DAYS', unit: 'DAYS', quantity: -1, precededByDayOt: false, retro: true, originalSegmentFrom: '2026-09-01', retroReason: 'RECALCULATION' },
    ];
    server.use(
      ...lookups,
      http.get(`${API}/attendance/attendance-results/${RES}`, () => HttpResponse.json(result)),
      http.get(`${API}/attendance/attendance-results/${RES}/lines`, () => HttpResponse.json(lines)),
    );
    await changeLanguage('en');
    renderPage(<AttendanceResultPage resultId={RES} />);
    expect(await screen.findByText('Attendance result version 1')).toBeInTheDocument();
    expect(await screen.findByText('WORKED_DAYS')).toBeInTheDocument();
    expect(screen.getByText('21,5')).toBeInTheDocument();
    expect(screen.getByText('RETRO.UNPAID_DAYS')).toBeInTheDocument();
    expect(screen.getByText('RECALCULATION')).toBeInTheDocument();
  });
});

describe('ResultImportsPage', () => {
  it('shows a staged job and publishes it', async () => {
    let published = false;
    const staged: ImportJobView = { importJobId: JOB, status: 'STAGED', stagedLines: 12, publishedLines: 0, periods: [] };
    server.use(
      http.get(`${API}/attendance/result-imports/${JOB}`, () => HttpResponse.json(staged)),
      http.post(`${API}/attendance/result-imports/${JOB}/publish`, () => {
        published = true;
        return HttpResponse.json({ ...staged, status: 'PUBLISHED', stagedLines: 0, publishedLines: 12, periods: [{ periodId: PER, periodStart: '2026-10-01', resultId: RES, version: 2, lines: 12 }] });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ResultImportsPage jobId={JOB} onJobChange={() => undefined} />);
    expect(await screen.findByText('Waiting to publish')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Publish' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(published).toBe(true));
    expect(await screen.findByText('Imported results published')).toBeInTheDocument();
  });

  it('refuses an invalid job id', async () => {
    let changed = false;
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ResultImportsPage onJobChange={() => (changed = true)} />);
    await user.type(await screen.findByPlaceholderText('Import job id (UUID)'), 'abc{Enter}');
    expect(await screen.findByText('Invalid import job id.')).toBeInTheDocument();
    expect(changed).toBe(false);
  });
});
