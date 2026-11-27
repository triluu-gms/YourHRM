import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { CapView, MyBalance, MyDay, MyDayDetail, MyEmployment } from './api';
import { MyTimesheetPage } from './MyTimesheetPage';
import { MyLeavePage } from './MyLeavePage';
import { MyOvertimePage } from './MyOvertimePage';

const LE1 = '018f0000-0000-7000-8000-0000000000b1';
const LE2 = '018f0000-0000-7000-8000-0000000000b2';
const EMP1 = '018f0000-0000-7000-8000-0000000000b3';
const EMP2 = '018f0000-0000-7000-8000-0000000000b4';
const TYPE = '018f0000-0000-7000-8000-0000000000b5';

const lookups = [
  http.get(`${API}/core/legal-entities`, () =>
    HttpResponse.json([
      { id: LE1, code: 'LE01', status: 'ACTIVE', current: { shortName: 'Demo VN' } },
      { id: LE2, code: 'LE02', status: 'ACTIVE', current: { shortName: 'Demo HN' } },
    ]),
  ),
];

const employments: MyEmployment[] = [
  { employmentId: EMP1, legalEntityId: LE1, hireDate: '2024-01-02', terminationDate: null, status: 'ACTIVE', activeToday: true },
  { employmentId: EMP2, legalEntityId: LE2, hireDate: '2025-06-01', terminationDate: null, status: 'ACTIVE', activeToday: true },
];

const day: MyDay = {
  workDate: '2026-11-02',
  dayType: 'WORKDAY',
  dayTypeLabel: 'Working day',
  dayStatus: 'PROCESSED',
  dayStatusLabel: 'Processed',
  primaryCode: 'X',
  primaryCodeLabel: 'Present',
  codes: [{ code: 'X', label: 'Present', quantity: 1 }],
  firstInAt: '2026-11-02T01:05:00Z',
  lastOutAt: '2026-11-02T10:00:00Z',
  scheduledHours: 8,
  workedHours: 7.9,
  lateMin: 5,
  earlyMin: 0,
  absentHours: 0,
  nightHours: 0,
  dayValue: 1,
  exceptions: [{ code: 'LATE', label: 'Late arrival', blocking: false }],
  manualOverride: false,
};

describe('MyTimesheetPage', () => {
  it('switches employment, lists the days and opens the day detail', async () => {
    const asked: (string | null)[] = [];
    const detail: MyDayDetail = {
      day,
      punches: [{ punchAt: '2026-11-02T01:05:00Z', localTime: '2026-11-02T08:05:00', direction: 'IN', terminalCode: 'T01', status: 'VALID' }],
      corrections: [],
    };
    server.use(
      ...lookups,
      http.get(`${API}/attendance/me/employments`, () => HttpResponse.json(employments)),
      http.get(`${API}/attendance/me/timesheets`, ({ request }) => {
        asked.push(new URL(request.url).searchParams.get('employmentId'));
        return HttpResponse.json([day]);
      }),
      http.get(`${API}/attendance/me/exceptions`, () => HttpResponse.json([day])),
      http.get(`${API}/attendance/me/punches`, () => HttpResponse.json([])),
      http.get(`${API}/attendance/me/timesheets/2026-11-02`, () => HttpResponse.json(detail)),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MyTimesheetPage />);

    expect(await screen.findByRole('button', { name: /02\/11\/2026/ })).toBeInTheDocument();
    expect(screen.getAllByText('Late arrival').length).toBeGreaterThan(0);
    expect(asked[asked.length - 1]).toBe(EMP1);

    // Two employments → the switcher is shown; picking the second re-queries with it.
    await user.click(screen.getByRole('combobox', { name: 'Employment' }));
    await user.click(await screen.findByText(/LE02 – Demo HN/));
    await waitFor(() => expect(asked[asked.length - 1]).toBe(EMP2));

    await user.click(screen.getByRole('button', { name: /02\/11\/2026/ }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('T01')).toBeInTheDocument();
    expect(within(drawer).getByText('In')).toBeInTheDocument();
  });
});

const balance: MyBalance = {
  leaveTypeId: TYPE,
  leaveTypeCode: 'AL',
  leaveTypeLabel: 'Annual leave',
  leaveYear: 2026,
  yearStart: '2026-01-01',
  yearEnd: '2026-12-31',
  opening: 2,
  accrued: 11,
  carried: 0,
  taken: 3,
  adjusted: 0,
  expired: 0,
  encashed: 0,
  balance: 10,
  pending: 1,
  available: 9,
};

const leaveHandlers = [
  ...lookups,
  http.get(`${API}/attendance/me/employments`, () => HttpResponse.json([employments[0]])),
  http.get(`${API}/attendance/me/leave-balances`, () => HttpResponse.json([balance])),
  http.get(`${API}/attendance/me/leave-ledger`, () =>
    HttpResponse.json([
      { id: 'l1', leaveTypeCode: 'AL', leaveTypeLabel: 'Annual leave', leaveYear: 2026, txnType: 'ACCRUE', quantity: 1, unit: 'DAY', txnDate: '2026-11-01', expiresOn: null, leaveRequestId: null, reason: null },
    ]),
  ),
];

describe('MyLeavePage', () => {
  it('shows balances and ledger, previews and submits a leave request', async () => {
    let submitted: unknown;
    server.use(
      ...leaveHandlers,
      http.post(`${API}/attendance/employments/${EMP1}/leave-requests/preview`, () =>
        HttpResponse.json({
          leaveTypeCode: 'AL',
          requestUnit: 'DAY',
          durationDays: 2,
          durationHours: null,
          days: [],
          daysPerLeaveYear: { 2026: 2 },
          available: { 2026: 7 },
          warnings: [],
        }),
      ),
      http.post(`${API}/attendance/employments/${EMP1}/leave-requests`, async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json({ id: 'r1', status: 'PENDING' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MyLeavePage />);

    expect(await screen.findAllByText('AL – Annual leave')).not.toHaveLength(0);
    expect(screen.getByText('Accrual')).toBeInTheDocument();
    // Single employment → no switcher.
    expect(screen.queryByRole('combobox', { name: 'Employment' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Request leave/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('combobox', { name: /Leave type/ }));
    await user.click(await screen.findByTitle('AL – Annual leave'));
    const from = within(drawer).getByRole('textbox', { name: /From/ });
    await user.click(from);
    await user.type(from, '02/12/2026{Enter}');
    await user.click(within(drawer).getByRole('button', { name: /Count days/ }));
    expect(await within(drawer).findByText('Left in 2026')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: /Submit/ }));
    await waitFor(() =>
      expect(submitted).toMatchObject({ leaveTypeId: TYPE, startDate: '2026-12-02', startPart: 'FULL', endPart: 'FULL' }),
    );
  });

  it('hides "Request leave" without att.leave_request.create', async () => {
    server.use(...leaveHandlers, http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.ESS_VIEW))));
    await changeLanguage('en');
    renderPage(<MyLeavePage />);
    expect(await screen.findAllByText('AL – Annual leave')).not.toHaveLength(0);
    expect(screen.queryByRole('button', { name: /Request leave/ })).not.toBeInTheDocument();
  });
});

describe('MyOvertimePage', () => {
  it('shows the monthly and yearly cap usage', async () => {
    const caps: CapView[] = [
      { kind: 'MONTH', periodStart: '2026-11-01', regime: 'STANDARD_200', capMin: 2400, openingMin: 0, approvedMin: 1800, pendingMin: 300, paidMin: 1200, overrideMin: 0, usedMin: 2100, level: 'WARN' },
      { kind: 'YEAR', periodStart: '2026-01-01', regime: 'STANDARD_200', capMin: 12000, openingMin: 600, approvedMin: 3000, pendingMin: 300, paidMin: 2400, overrideMin: 0, usedMin: 3900, level: 'OK' },
    ];
    server.use(
      ...lookups,
      http.get(`${API}/attendance/me/employments`, () => HttpResponse.json([employments[0]])),
      http.get(`${API}/attendance/me/overtime-caps`, () => HttpResponse.json(caps)),
    );
    await changeLanguage('en');
    renderPage(<MyOvertimePage />);
    expect(await screen.findByText('This month')).toBeInTheDocument();
    expect(screen.getByText('Near the cap')).toBeInTheDocument();
    expect(screen.getByText('35 / 40 h')).toBeInTheDocument();
    expect(screen.getByText('65 / 200 h')).toBeInTheDocument();
  });
});
