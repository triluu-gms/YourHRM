import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { meWith } from '@/test/fixtures';
import { changeLanguage } from '@/shared/i18n';
import { AttendancePermissions as P } from '../../permissions';
import type { DiffItem, HolidayCalendar, HolidayDate } from './api';
import { HolidayCalendarsPage } from './HolidayCalendarsPage';
import { HolidayCalendarDetailPage } from './HolidayCalendarDetailPage';

const CAL = '018f0000-0000-7000-8000-0000000000a1';
const TPL = '018f0000-0000-7000-8000-0000000000a2';
const LE = '018f0000-0000-7000-8000-0000000000a3';
const D1 = '018f0000-0000-7000-8000-0000000000a4';

const calendar: HolidayCalendar = {
  id: CAL,
  code: 'VN-2026',
  nameVi: 'Lịch nghỉ lễ Việt Nam',
  nameEn: 'Vietnam holidays',
  countryCode: 'VN',
  source: 'TENANT_COPY',
  templateCalendarId: TPL,
  templateYear: 2026,
  status: 'ACTIVE',
  version: 3,
  assignments: [{ id: 'as1', calendarId: CAL, legalEntityId: LE, locationId: null, validFrom: '2026-01-01', validTo: null, version: 1 }],
};

const date: HolidayDate = {
  id: D1,
  calendarId: CAL,
  holidayDate: '2026-09-02',
  dayType: 'PUBLIC',
  holidayCode: 'QUOC_KHANH',
  nameVi: 'Quốc khánh',
  nameEn: 'National Day',
  lunarLeapMonth: false,
  paid: true,
  eligibility: 'ALL',
  status: 'ACTIVE',
  version: 2,
};

const lookups = [
  http.get(`${API}/core/legal-entities`, () => HttpResponse.json([{ id: LE, code: 'LE01', current: { legalNameVi: 'Công ty A', shortName: 'Cty A' } }])),
  http.get(`${API}/core/locations`, () => HttpResponse.json([])),
  http.get(`${API}/attendance/holiday-calendar-templates`, () =>
    HttpResponse.json([{ id: TPL, code: 'VN', nameVi: 'Mẫu Việt Nam', nameEn: 'Vietnam template', countryCode: 'VN', years: [2026, 2027] }]),
  ),
];

describe('HolidayCalendarsPage', () => {
  it('lists the calendars and creates a custom one', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      ...lookups,
      http.get(`${API}/attendance/holiday-calendars`, () => HttpResponse.json([calendar])),
      http.post(`${API}/attendance/holiday-calendars`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...calendar, id: 'new', code: 'CTY', source: 'CUSTOM', assignments: [] }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<HolidayCalendarsPage />);
    expect(await screen.findByText('VN-2026')).toBeInTheDocument();
    expect(screen.getByText('Vietnam holidays')).toBeInTheDocument();
    expect(screen.getByText('Copy of a template')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /New holiday calendar/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText('Custom calendar'));
    await user.type(within(drawer).getByLabelText('Code'), 'cty');
    await user.type(within(drawer).getByLabelText('Name (VI)'), 'Lịch công ty');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toEqual({ code: 'CTY', nameVi: 'Lịch công ty', nameEn: null, templateId: null, year: null }));
  });

  it('hides the create action without the update permission', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.HOLIDAY_CALENDAR_VIEW))),
      http.get(`${API}/attendance/holiday-calendars`, () => HttpResponse.json([calendar])),
    );
    await changeLanguage('en');
    renderPage(<HolidayCalendarsPage />);
    expect(await screen.findByText('VN-2026')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New holiday calendar/ })).not.toBeInTheDocument();
  });
});

describe('HolidayCalendarDetailPage', () => {
  function handlers(diff: DiffItem[] = []) {
    return [
      ...lookups,
      http.get(`${API}/attendance/holiday-calendars/${CAL}`, () => HttpResponse.json(calendar)),
      http.get(`${API}/attendance/holiday-calendars/${CAL}/dates`, () => HttpResponse.json([date])),
      http.get(`${API}/attendance/holiday-calendars/${CAL}/template-diff`, () => HttpResponse.json(diff)),
    ];
  }

  it('shows the dates and cancels one with a reason', async () => {
    let body: unknown;
    server.use(
      ...handlers(),
      http.post(`${API}/attendance/holiday-calendars/${CAL}/dates/${D1}/cancel`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...date, status: 'CANCELLED', version: 3 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<HolidayCalendarDetailPage calendarId={CAL} />);
    expect(await screen.findByText('National Day')).toBeInTheDocument();
    expect(screen.getByText('02/09/2026')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel date' }));
    const modal = await screen.findByRole('dialog');
    await user.type(within(modal).getByLabelText('Reason'), 'Đổi lịch');
    await user.click(within(modal).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(body).toEqual({ version: 2, reason: 'Đổi lịch' }));
  });

  it('applies the preselected template differences', async () => {
    let body: unknown;
    const added: DiffItem = {
      key: '2026-04-30|PUBLIC|ALL|',
      kind: 'ADDED',
      template: { holidayDate: '2026-04-30', dayType: 'PUBLIC', nameVi: 'Giải phóng miền Nam', nameEn: 'Reunification Day', lunarLeapMonth: false, paid: true, eligibility: 'ALL' },
      current: null,
    };
    const changed: DiffItem = { key: '2026-09-02|PUBLIC|ALL|', kind: 'CHANGED', template: { ...date, nameEn: 'National Day (new)' }, current: date };
    server.use(
      ...handlers([added, changed]),
      http.post(`${API}/attendance/holiday-calendars/${CAL}/template-apply`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ year: 2026, applied: [added] });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<HolidayCalendarDetailPage calendarId={CAL} />);
    await user.click(await screen.findByRole('tab', { name: 'Template sync' }));
    expect(await screen.findByText('New in template')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Apply 1 change' }));
    await waitFor(() => expect(body).toEqual({ year: 2026, acceptKeys: ['2026-04-30|PUBLIC|ALL|'], reason: null }));
  });

  it('lists the legal-entity assignments and shows translated problems when ending one fails', async () => {
    server.use(
      ...handlers(),
      http.put(`${API}/attendance/holiday-calendars/${CAL}/assignments/as1`, () => problem(409, 'stale-version')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<HolidayCalendarDetailPage calendarId={CAL} />);
    await user.click(await screen.findByRole('tab', { name: 'Legal entities' }));
    expect(await screen.findByText('LE01 – Cty A')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Set end date' }));
    const modal = await screen.findByRole('dialog');
    await user.click(within(modal).getByRole('button', { name: 'Save' }));
    expect(await within(modal).findByText(/changed by someone else|người khác/)).toBeInTheDocument();
  });

  it('hides date and assignment actions for a read-only user', async () => {
    server.use(...handlers(), http.get(`${API}/core/me`, () => HttpResponse.json(meWith(P.HOLIDAY_CALENDAR_VIEW))));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<HolidayCalendarDetailPage calendarId={CAL} />);
    expect(await screen.findByText('National Day')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add date' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel date' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Legal entities' }));
    expect(screen.queryByRole('button', { name: 'Set end date' })).not.toBeInTheDocument();
  });
});
