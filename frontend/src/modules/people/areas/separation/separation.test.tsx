import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import { meWith } from '@/test/fixtures';
import type { EmployeeProfile } from '../../common';
import type { ReasonCatalogue, SeparationView } from './api';
import { SeparationTab } from './tabs';
import { SeparationDetailPage } from './SeparationDetailPage';
import { SeparationsListPage } from './SeparationsListPage';
import { ProfileActions } from './ProfileActions';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';
const SEP = '018f0000-0000-7000-8000-0000000000f1';
const PERIOD = '018f0000-0000-7000-8000-0000000000f2';

const profile: EmployeeProfile = {
  employment: { employmentId: EMP, employeeNo: 'NV0001', status: 'ACTIVE', hireDate: '2025-03-01' },
  person: { id: PERSON, fullName: 'Nguyễn Văn An', ethnicityRecorded: false, religionRecorded: false, anonymised: false, version: 1 },
  otherEmployments: [],
  primaryAssignment: null,
  tabs: {},
  asOf: '2026-11-11',
};
const props = { employmentId: EMP, personId: PERSON, profile };

const reasons: ReasonCatalogue = {
  grounds: [
    {
      code: 'RESIGN',
      separationType: 'RESIGNATION',
      initiatedBy: 'EMPLOYEE',
      article: 'Art. 35',
      nameVi: 'Nghỉ việc',
      nameEn: 'Resignation',
      employerInitiated: false,
      noticeSide: 'EMPLOYEE',
      severanceType: 'SEVERANCE_ALLOWANCE',
      configured: true,
      problems: [],
    },
  ],
  subReasons: [],
};

const sep: SeparationView = {
  id: SEP,
  employmentId: EMP,
  personId: PERSON,
  employeeNo: 'NV0001',
  fullName: 'Nguyễn Văn An',
  status: 'DRAFT',
  separationType: 'RESIGNATION',
  legalGroundCode: 'RESIGN',
  lastWorkingDate: '2026-12-31',
  terminationDate: '2026-12-31',
  requiredNoticeDays: 30,
  noticeDayBasis: 'CALENDAR',
  noticeShortfallDays: 0,
  severanceType: 'SEVERANCE_ALLOWANCE',
  severanceEligibility: 'ELIGIBLE',
  protectedEmployee: false,
  noPersonalEmail: false,
  servicePeriods: [
    { id: PERIOD, periodKind: 'RECOGNISED_PRIOR', periodFrom: '2020-01-01', periodTo: '2020-12-31', derived: false, version: 0 },
  ],
  version: 4,
};

const lookups = ['legal-entities', 'org-units', 'job-titles', 'locations', 'grade-structures'].map((p) =>
  http.get(`${API}/core/${p}`, () => HttpResponse.json([])),
);
const reasonsHandler = http.get(`${API}/people/separations/reasons`, () => HttpResponse.json(reasons));

describe('SeparationTab', () => {
  it('shows the live separation and the history', async () => {
    server.use(
      ...lookups,
      reasonsHandler,
      http.get(`${API}/people/employments/${EMP}/separation`, () => HttpResponse.json(sep)),
      http.get(`${API}/people/employments/${EMP}/separations`, () => HttpResponse.json([sep])),
    );
    await changeLanguage('en');
    renderPage(<SeparationTab {...props} />);
    await waitFor(() => {
      expect(screen.getAllByText('Draft')).toHaveLength(2);
      expect(screen.getAllByText('Resignation (Art. 35)')).toHaveLength(2);
    });
  });

  it('says so when no separation is live (404)', async () => {
    server.use(
      ...lookups,
      reasonsHandler,
      http.get(`${API}/people/employments/${EMP}/separation`, () => problem(404, 'not-found')),
      http.get(`${API}/people/employments/${EMP}/separations`, () => HttpResponse.json([])),
    );
    await changeLanguage('en');
    renderPage(<SeparationTab {...props} />);
    expect(await screen.findByText('No separation in progress.')).toBeInTheDocument();
  });
});

describe('SeparationDetailPage', () => {
  it('submits with the version and the personal e-mail', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      reasonsHandler,
      http.get(`${API}/people/separations/${SEP}`, () => HttpResponse.json(sep)),
      http.post(`${API}/people/separations/${SEP}/submit`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ separation: { ...sep, status: 'SUBMITTED', version: 5 }, warnings: [] });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SeparationDetailPage separationId={SEP} />);

    await user.click(await screen.findByRole('button', { name: /Submit for approval/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/Personal e-mail/), 'an@example.test');
    await user.click(within(drawer).getByRole('button', { name: /Submit for approval/ }));
    await waitFor(() => expect(body).toEqual({ version: 4, personalEmail: 'an@example.test', noPersonalEmail: false, note: null }));
  });

  it('removes a manual service period and shows translated problems', async () => {
    server.use(
      ...lookups,
      reasonsHandler,
      http.get(`${API}/people/separations/${SEP}`, () => HttpResponse.json(sep)),
      http.delete(`${API}/people/separations/${SEP}/service-periods/${PERIOD}`, () =>
        problem(422, 'derived-service-period', 'derived'),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SeparationDetailPage separationId={SEP} />);

    expect(await screen.findByText('Recognised prior service')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Computed service periods cannot be removed.')).toBeInTheDocument();
  });
});

describe('SeparationsListPage', () => {
  it('filters by status', async () => {
    let status: string | null = null;
    server.use(
      ...lookups,
      http.get(`${API}/people/separations`, ({ request }) => {
        status = new URL(request.url).searchParams.get('status');
        return HttpResponse.json([sep]);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SeparationsListPage />);
    expect(await screen.findByText('NV0001')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByTitle('Submitted'));
    await waitFor(() => expect(status).toBe('SUBMITTED'));
  });
});

describe('ProfileActions', () => {
  it('renders nothing without separation or DSR permissions', async () => {
    server.use(http.get(`${API}/core/me`, () => HttpResponse.json(meWith('hr.employee.view'))));
    await changeLanguage('en');
    const { container } = renderPage(<ProfileActions {...props} />);
    await waitFor(() => expect(container.querySelector('button')).toBeNull());
  });

  it('anonymises only after the confirmation word is typed', async () => {
    let body: unknown;
    server.use(
      http.post(`${API}/people/privacy/persons/${PERSON}/anonymise`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          personId: PERSON,
          mode: 'RESTRICTED',
          retainedUntil: '2036-12-31',
          cleared: ['contacts'],
          retained: ['salary_record'],
          shreddedSubjects: 0,
          completedAt: '2026-11-11T03:00:00Z',
        });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ProfileActions {...props} />);

    await user.click(await screen.findByRole('button', { name: /More actions/ }));
    await user.click(await screen.findByText('Anonymise person'));
    const dialog = await screen.findByRole('dialog');
    const run = within(dialog).getByRole('button', { name: 'Anonymise' });
    expect(run).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Type ANONYMISE to confirm'), 'ANONYMISE');
    await user.click(run);
    await waitFor(() => expect(body).toEqual({ requestRef: null, confirm: true }));
    expect(await within(dialog).findByText(/Retained records kept until 31\/12\/2036/)).toBeInTheDocument();
  });
});
