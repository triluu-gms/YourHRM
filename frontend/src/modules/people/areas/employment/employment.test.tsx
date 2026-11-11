import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { EmployeeProfile } from '../../common';
import type { AssignmentView, EmploymentView, ParticipationView, PrimaryView, SalaryView } from './api';
import { salaryPurposeStore } from './queries';
import { EmploymentTab } from './EmploymentTab';
import { AssignmentsTab } from './AssignmentsTab';
import { CompensationTab } from './CompensationTab';
import { ConcurrentEmploymentPage } from './ConcurrentEmploymentPage';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';
const LE = '018f0000-0000-7000-8000-0000000000e3';
const OU = '018f0000-0000-7000-8000-0000000000e4';
const A1 = '018f0000-0000-7000-8000-0000000000e5';

const profile: EmployeeProfile = {
  employment: { employmentId: EMP, employeeNo: 'NV0001', status: 'ACTIVE', hireDate: '2025-03-01', legalEntityId: LE },
  person: { id: PERSON, fullName: 'Nguyễn Văn An', ethnicityRecorded: false, religionRecorded: false, anonymised: false, version: 1 },
  otherEmployments: [],
  primaryAssignment: null,
  tabs: {},
  asOf: '2026-11-11',
};
const props = { employmentId: EMP, personId: PERSON, profile };

const employment: EmploymentView = {
  id: EMP,
  personId: PERSON,
  fullName: 'Nguyễn Văn An',
  legalEntityId: LE,
  employeeNo: 'NV0001',
  hireType: 'NEW',
  hireDate: '2025-03-01',
  status: 'ACTIVE',
  workerCategoryCode: 'EMPLOYEE',
  incomplete: true,
  missingData: ['TAX_CODE'],
  pitPrimary: false,
  version: 4,
};

/** Core lookups and the employee search used by pickers / names. */
const lookupHandlers = [
  ...['legal-entities', 'org-units', 'job-titles', 'locations', 'grade-structures', 'cost-centres', 'employee-categories'].map((p) =>
    http.get(`${API}/core/${p}`, () => HttpResponse.json([])),
  ),
  http.get(`${API}/people/employees`, () => HttpResponse.json({ items: [], total: 0, page: 0, size: 20 })),
];

describe('EmploymentTab', () => {
  it('shows facts, missing data, primary basis and insurance, and saves an edit with the version', async () => {
    let put: unknown;
    const primary: PrimaryView = { personId: PERSON, asOf: '2026-11-11', basis: 'MISSING', liveEmployments: 2, designations: [], pending: [] };
    const insurance: ParticipationView[] = [
      { id: 'p1', employmentId: EMP, insuranceType: 'SI', validFrom: '2025-03-01', participating: true, basisRule: 'FIRST_CONTRACT', manualOverride: false },
    ];
    server.use(
      ...lookupHandlers,
      http.get(`${API}/people/employments/${EMP}`, () => HttpResponse.json(employment)),
      http.get(`${API}/people/employments/${EMP}/status`, () =>
        HttpResponse.json({ employmentId: EMP, asOf: '2026-11-11', status: 'ACTIVE', statusOn: 'ACTIVE', hireDate: '2025-03-01', live: true }),
      ),
      http.get(`${API}/people/persons/${PERSON}/primary-employment`, () => HttpResponse.json(primary)),
      http.get(`${API}/people/employments/${EMP}/insurance-participation`, () => HttpResponse.json(insurance)),
      http.put(`${API}/people/employments/${EMP}`, async ({ request }) => {
        put = await request.json();
        return HttpResponse.json({ ...employment, version: 5 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmploymentTab {...props} />);

    expect(await screen.findByText('Profile incomplete: tax code')).toBeInTheDocument();
    expect(await screen.findAllByText(/two or more live employments and none designated/)).not.toHaveLength(0);
    expect(await screen.findByText('Social insurance (BHXH)')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Edit/ }));
    const drawer = await screen.findByRole('dialog');
    const regime = within(drawer).getByLabelText(/^Working regime/);
    await user.type(regime, 'STANDARD');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(put).toMatchObject({ version: 4, workerCategoryCode: 'EMPLOYEE', workingRegimeCode: 'STANDARD' }));
  });
});

describe('AssignmentsTab', () => {
  const current: AssignmentView = {
    id: A1,
    assignmentKey: A1,
    employmentId: EMP,
    assignmentType: 'PRIMARY',
    legalEntityId: LE,
    orgUnitId: OU,
    costSplit: [],
    fte: 1,
    validFrom: '2025-03-01',
    reasonCode: 'HIRE',
  };

  it('requests a change from a date and shows the approval state and warnings', async () => {
    let body: unknown;
    server.use(
      ...lookupHandlers,
      http.get(`${API}/people/employments/${EMP}/assignments`, () => HttpResponse.json([current])),
      http.get(`${API}/people/assignment-change-reasons`, () =>
        HttpResponse.json([
          { code: 'TRANSFER', nameVi: 'Điều chuyển', nameEn: 'Transfer', actionCode: 'TRANSFER', active: true, phase2: false, tenantDefined: false, problems: [] },
        ]),
      ),
      http.post(`${API}/people/employments/${EMP}/assignments/changes`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ requestId: 'r1', status: 'PENDING_APPROVAL', warnings: [{ code: 'MANAGER_CHANGE', message: 'The manager changes' }] });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<AssignmentsTab {...props} />);

    expect(await screen.findByText('PRIMARY')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Change assignment/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('combobox', { name: 'Change reason' }));
    await user.click(await screen.findByTitle('TRANSFER – Transfer'));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(body).toMatchObject({ reasonCode: 'TRANSFER', attributes: { orgUnitId: OU, fte: 1, costSplit: [] } }),
    );
    expect((await screen.findAllByText('Submitted for approval')).length).toBeGreaterThan(0);
    expect(screen.getByText('The manager changes')).toBeInTheDocument();
  }, 30_000);
});

describe('CompensationTab', () => {
  const salary: SalaryView = {
    id: 's1',
    employmentId: EMP,
    payBasis: 'NET',
    salaryKind: 'MONTHLY',
    baseSalaryAmount: null,
    agreedNetAmount: null,
    siSalaryAmount: null,
    currencyCode: 'VND',
    allowances: [],
    validFrom: '2025-03-01',
    amountsMasked: true,
  };

  it('asks for the purpose first, sends it with the reads and never shows masked amounts', async () => {
    salaryPurposeStore.set(undefined);
    const purposes: (string | null)[] = [];
    server.use(
      http.get(`${API}/people/employments/${EMP}/salary-records`, ({ request }) => {
        purposes.push(new URL(request.url).searchParams.get('purpose'));
        return HttpResponse.json(salary);
      }),
      http.get(`${API}/people/employments/${EMP}/salary-records/history`, () => HttpResponse.json([salary])),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<CompensationTab {...props} />);

    expect(await screen.findByText('Purpose of viewing salary data')).toBeInTheDocument();
    expect(purposes).toHaveLength(0);
    await user.type(screen.getByLabelText('Purpose'), 'Annual review');
    await user.click(screen.getByRole('button', { name: 'View salary' }));

    expect(await screen.findByText('Viewing for: Annual review')).toBeInTheDocument();
    await waitFor(() => expect(purposes).toContain('Annual review'));
    expect((await screen.findAllByText('Amounts hidden by your field mask')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('••••••').length).toBeGreaterThan(0);
  });

  it('asks again when the backend reports purpose-required', async () => {
    salaryPurposeStore.set('Old purpose');
    server.use(
      http.get(`${API}/people/employments/${EMP}/salary-records`, () => problem(422, 'purpose-required')),
      http.get(`${API}/people/employments/${EMP}/salary-records/history`, () => problem(422, 'purpose-required')),
    );
    await changeLanguage('en');
    renderPage(<CompensationTab {...props} />);
    expect(await screen.findByText('Purpose of viewing salary data')).toBeInTheDocument();
  });
});

describe('ConcurrentEmploymentPage', () => {
  it('asks for a legal entity before running the check', async () => {
    server.use(...lookupHandlers);
    await changeLanguage('en');
    renderPage(<ConcurrentEmploymentPage />);
    expect(await screen.findByText('Choose a legal entity to run the check.')).toBeInTheDocument();
  });
});
