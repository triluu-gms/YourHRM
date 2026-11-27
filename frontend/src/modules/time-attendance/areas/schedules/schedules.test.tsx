import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { meWith } from '@/test/fixtures';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { ScheduleAssignment, SchedulePattern, ScheduleRule, Shift } from './api';
import { ShiftsPage } from './ShiftsPage';
import { SchedulePatternsPage } from './SchedulePatternsPage';
import { ScheduleRulesPage } from './ScheduleRulesPage';
import { ScheduleAssignmentsPage } from './ScheduleAssignmentsPage';
import { UnscheduledEmployeesPage } from './UnscheduledEmployeesPage';

const SHIFT = '018f0000-0000-7000-8000-0000000000b1';
const PAT = '018f0000-0000-7000-8000-0000000000b2';
const RULE = '018f0000-0000-7000-8000-0000000000b3';
const EMP = '018f0000-0000-7000-8000-0000000000b4';
const KEY = '018f0000-0000-7000-8000-0000000000b5';
const LE = '018f0000-0000-7000-8000-0000000000b6';

const shift: Shift = {
  id: SHIFT,
  code: 'HC',
  nameVi: 'Ca hành chính',
  nameEn: 'Office hours',
  startTime: '08:00:00',
  endTime: '17:00:00',
  crossesMidnight: false,
  spanMinutes: 540,
  workingMinutes: 480,
  graceInMin: 5,
  graceOutMin: 5,
  punchWindowBeforeMin: 120,
  punchWindowAfterMin: 240,
  stdHours: 8,
  dayValue: 1,
  breaks: [{ seq: 1, start: '12:00:00', end: '13:00:00', durationMin: 60, paid: false, flexible: false }],
  status: 'ACTIVE',
  version: 4,
};

const pattern: SchedulePattern = {
  id: PAT,
  code: 'T2-T6',
  nameVi: 'Thứ Hai – thứ Sáu',
  nameEn: 'Monday to Friday',
  patternType: 'WEEKLY',
  cycleLengthDays: 7,
  anchorDate: '2026-01-05',
  weeklyRestDays: [7],
  stdHoursWeek: 40,
  workDaysWeek: 5,
  status: 'ACTIVE',
  version: 1,
  days: [0, 1, 2, 3, 4, 5, 6].map((i) => ({
    dayIndex: i,
    dayOfWeek: (['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const)[i]!,
    dayKind: i < 5 ? 'WORK' : i === 5 ? 'OFF' : 'REST',
    shiftId: i < 5 ? SHIFT : null,
    shiftCode: i < 5 ? 'HC' : null,
  })),
};

const rule: ScheduleRule = {
  id: RULE,
  code: 'VP',
  priority: 10,
  legalEntityId: null,
  patternId: PAT,
  validFrom: '2026-01-01',
  validTo: null,
  status: 'ACTIVE',
  version: 1,
};

const manual: ScheduleAssignment = {
  id: 'sa-manual',
  employmentId: EMP,
  assignmentKey: KEY,
  patternId: PAT,
  patternCode: 'T2-T6',
  validFrom: '2026-11-01',
  validTo: '2026-12-01',
  source: 'MANUAL',
  status: 'ACTIVE',
  version: 1,
};
const byRule: ScheduleAssignment = { ...manual, id: 'sa-rule', patternCode: 'RULEPAT', validFrom: '2026-01-01', validTo: '2026-11-01', source: 'RULE', ruleId: RULE };

const lookups = [
  ...['legal-entities', 'org-units', 'locations', 'job-titles', 'employee-categories'].map((p) =>
    http.get(`${API}/core/${p}`, () =>
      HttpResponse.json(p === 'legal-entities' ? [{ id: LE, code: 'LE01', current: { shortName: 'Cty A' } }] : []),
    ),
  ),
  http.get(`${API}/people/employees`, () =>
    HttpResponse.json({ items: [{ employmentId: EMP, personId: 'p1', employeeNo: 'NV001', fullName: 'Trần Thị Bình', status: 'ACTIVE' }], total: 1, page: 0, size: 20 }),
  ),
  http.get(`${API}/attendance/shifts`, () => HttpResponse.json([shift])),
  http.get(`${API}/attendance/schedule-patterns`, () => HttpResponse.json([pattern])),
];

describe('ShiftsPage', () => {
  it('lists shifts and updates one with its version, showing statutory warnings', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      ...lookups,
      http.put(`${API}/attendance/shifts/${SHIFT}`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ shift: { ...shift, nameVi: 'Ca HC', version: 5 }, warnings: ['normal-hours-above-daily-limit: 9 h > 8 h [verify-legal]'] });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ShiftsPage />);
    expect(await screen.findByText('Office hours')).toBeInTheDocument();
    expect(screen.getByText('08:00 – 17:00')).toBeInTheDocument();
    expect(screen.getByText('8:00')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const drawer = await screen.findByRole('dialog');
    const nameVi = within(drawer).getByLabelText('Name (VI)');
    await user.clear(nameVi);
    await user.type(nameVi, 'Ca HC');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toBeDefined());
    expect(body).toMatchObject({
      code: 'HC',
      nameVi: 'Ca HC',
      startTime: '08:00',
      endTime: '17:00',
      graceInMin: 5,
      breaks: [{ start: '12:00', end: '13:00', durationMin: 60, paid: false, flexible: false }],
      status: 'ACTIVE',
      version: 4,
    });
    expect(await screen.findByText('Normal hours above the daily limit')).toBeInTheDocument();
  });

  it('hides create and edit without the config update permission', async () => {
    server.use(...lookups, http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.SCHEDULE_CONFIG_VIEW))));
    await changeLanguage('en');
    renderPage(<ShiftsPage />);
    expect(await screen.findByText('Office hours')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New shift/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });
});

describe('SchedulePatternsPage', () => {
  it('lists patterns with their rest days and creates a 5-day week', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      ...lookups,
      http.post(`${API}/attendance/schedule-patterns`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ pattern: { ...pattern, id: 'new', code: 'NEW' }, warnings: [] }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SchedulePatternsPage />);
    expect(await screen.findByText('Monday to Friday')).toBeInTheDocument();
    expect(screen.getByText('Sun')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /New pattern/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Code'), 'new');
    await user.type(within(drawer).getByLabelText('Name (VI)'), 'Tuần 5 ngày');
    // Monday–Friday need a shift: pick it for each working day
    for (let i = 0; i < 5; i++) {
      await user.click(within(drawer).getByRole('combobox', { name: `Shift ${i}` }));
      const options = await screen.findAllByTitle('HC (08:00–17:00)');
      await user.click(options[options.length - 1]!);
    }
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toBeDefined());
    expect(body).toMatchObject({ code: 'NEW', nameVi: 'Tuần 5 ngày', cycleLengthDays: 7 });
    const days = body!.days as { dayIndex: number; dayKind: string; shiftId: string | null }[];
    expect(days).toHaveLength(7);
    expect(days[0]).toEqual({ dayIndex: 0, dayKind: 'WORK', shiftId: SHIFT });
    expect(days[5]).toEqual({ dayIndex: 5, dayKind: 'OFF', shiftId: null });
    expect(days[6]).toEqual({ dayIndex: 6, dayKind: 'REST', shiftId: null });
    expect(new Date(`${body!.anchorDate as string}T00:00:00`).getDay()).toBe(1);
  });
});

describe('ScheduleRulesPage', () => {
  it('lists rules and regenerates every legal entity', async () => {
    let regenerated: unknown;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/schedule-rules`, () => HttpResponse.json([rule])),
      http.post(`${API}/attendance/schedule-rules/regenerate`, async ({ request }) => {
        regenerated = await request.json();
        return HttpResponse.json({ linesChanged: 3 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ScheduleRulesPage />);
    expect(await screen.findByText('VP')).toBeInTheDocument();
    expect(screen.getByText('Everyone')).toBeInTheDocument();
    expect(await screen.findByText('T2-T6')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Regenerate/ }));
    const modal = await screen.findByRole('dialog');
    await user.click(within(modal).getByRole('button', { name: /Regenerate/ }));
    await waitFor(() => expect(regenerated).toEqual({ legalEntityId: null }));
    expect(await screen.findByText('3 assignment lines changed')).toBeInTheDocument();
  });
});

describe('ScheduleAssignmentsPage', () => {
  const assignmentHandlers = [
    ...lookups,
    http.get(`${API}/attendance/employments/${EMP}/schedule-assignments`, () => HttpResponse.json([manual, byRule])),
  ];

  it('asks for an employee first', async () => {
    server.use(...lookups);
    await changeLanguage('en');
    renderPage(<ScheduleAssignmentsPage onChange={() => {}} />);
    expect(await screen.findByText('Select an employee to see their schedules.')).toBeInTheDocument();
  });

  it('lists schedules and cancels only the manual one', async () => {
    let cancelled = false;
    server.use(
      ...assignmentHandlers,
      http.post(`${API}/attendance/employments/${EMP}/schedule-assignments/sa-manual/cancel`, () => {
        cancelled = true;
        return HttpResponse.json({ ...manual, status: 'CANCELLED' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ScheduleAssignmentsPage employmentId={EMP} onChange={() => {}} />);
    expect(await screen.findByText('RULEPAT')).toBeInTheDocument();
    expect(screen.getByText('Manual')).toBeInTheDocument();
    expect(screen.getByText('01/11/2026 – 30/11/2026')).toBeInTheDocument();
    const cancelButtons = screen.getAllByRole('button', { name: 'Cancel' });
    expect(cancelButtons).toHaveLength(1);
    await user.click(cancelButtons[0]!);
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(cancelled).toBe(true));
  });

  it('creates a manual schedule prefilled from the unscheduled list', async () => {
    let body: unknown;
    server.use(
      ...assignmentHandlers,
      http.post(`${API}/attendance/employments/${EMP}/schedule-assignments`, async ({ request }) => {
        body = await request.json();
        return problem(422, 'outside-assignment-line');
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ScheduleAssignmentsPage employmentId={EMP} assignmentKey={KEY} onChange={() => {}} />);
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('combobox', { name: 'Pattern' }));
    await user.click(await screen.findByTitle('T2-T6 – Monday to Friday'));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toMatchObject({ assignmentKey: KEY, patternId: PAT, validTo: null }));
    expect(await within(drawer).findByText('The schedule must lie within the assignment line.')).toBeInTheDocument();
  });

  it('hides the assignment actions without the update permission', async () => {
    server.use(...assignmentHandlers, http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.SCHEDULE_ASSIGNMENT_VIEW))));
    await changeLanguage('en');
    renderPage(<ScheduleAssignmentsPage employmentId={EMP} onChange={() => {}} />);
    expect(await screen.findByText('RULEPAT')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Assign manually/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });

  it('shows the work plan with holidays', async () => {
    server.use(
      ...assignmentHandlers,
      http.get(`${API}/attendance/employments/${EMP}/work-plan`, () =>
        HttpResponse.json([
          { date: '2026-09-01', scheduled: true, dayKind: 'WORK', shiftCode: 'HC', stdHours: 8, dayValue: 1, patternCode: 'T2-T6', workingDay: true },
          {
            date: '2026-09-02',
            scheduled: true,
            dayKind: 'WORK',
            shiftCode: 'HC',
            stdHours: 8,
            dayValue: 1,
            patternCode: 'T2-T6',
            workingDay: false,
            holiday: { date: '2026-09-02', dayType: 'PUBLIC', nameVi: 'Quốc khánh', nameEn: 'National Day', paid: true, foreignersOnly: false, calendarId: 'c' },
          },
        ]),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ScheduleAssignmentsPage employmentId={EMP} onChange={() => {}} />);
    await user.click(await screen.findByRole('tab', { name: 'Planned days' }));
    expect(await screen.findByText('National Day')).toBeInTheDocument();
    expect(screen.getByText('1 working day · 8 standard hours')).toBeInTheDocument();
  });
});

describe('UnscheduledEmployeesPage', () => {
  it('lists the unscheduled lines of a legal entity with names', async () => {
    let date: string | null = null;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/legal-entities/${LE}/unscheduled-employees`, ({ request }) => {
        date = new URL(request.url).searchParams.get('date');
        return HttpResponse.json([{ employmentId: EMP, assignmentKey: KEY, assignmentType: 'PRIMARY', lineValidFrom: '2026-10-01' }]);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<UnscheduledEmployeesPage />);
    expect(await screen.findByText('Select a legal entity to see the list.')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Legal entity' }));
    await user.click(await screen.findByTitle('LE01 – Cty A'));
    expect(await screen.findByText('NV001 – Trần Thị Bình')).toBeInTheDocument();
    expect(screen.getByText('Primary')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Assign schedule' })).toBeInTheDocument();
    expect(date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
