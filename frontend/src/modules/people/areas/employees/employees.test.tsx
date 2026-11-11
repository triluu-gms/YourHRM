import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { EmployeeProfile, EmployeeRow } from '../../common';
import { EmployeesListPage } from './EmployeesListPage';
import { EmployeeProfilePage } from './EmployeeProfilePage';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';

const row: EmployeeRow = {
  employmentId: EMP,
  personId: PERSON,
  employeeNo: 'NV0001',
  fullName: 'Nguyễn Văn An',
  nationalIdMasked: '••••••••1234',
  status: 'ACTIVE',
  hireDate: '2025-03-01',
};

const profile: EmployeeProfile = {
  employment: { employmentId: EMP, employeeNo: 'NV0001', status: 'ACTIVE', hireDate: '2025-03-01' },
  person: { id: PERSON, fullName: 'Nguyễn Văn An', ethnicityRecorded: false, religionRecorded: false, anonymised: false, version: 1 },
  otherEmployments: [],
  primaryAssignment: null,
  tabs: { person: `/api/v1/people/persons/${PERSON}`, contracts: `/api/v1/people/employments/${EMP}/contracts` },
  asOf: '2026-11-11',
};

/** Core lookups the name components call; empty is enough for these tests. */
const lookupHandlers = ['legal-entities', 'org-units', 'job-titles', 'locations', 'grade-structures'].map((p) =>
  http.get(`${API}/core/${p}`, () => HttpResponse.json([])),
);

describe('EmployeesListPage', () => {
  it('lists employees with the masked national id and searches', async () => {
    let q: string | null = null;
    server.use(
      ...lookupHandlers,
      http.get(`${API}/people/employees`, ({ request }) => {
        q = new URL(request.url).searchParams.get('q');
        return HttpResponse.json({ items: [row], total: 1, page: 0, size: 20 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmployeesListPage />, { path: '/people/employees' });

    expect(await screen.findByText('Nguyễn Văn An')).toBeInTheDocument();
    expect(screen.getByText('••••••••1234')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();

    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'nguyen van{Enter}');
    await waitFor(() => expect(q).toBe('nguyen van'));
  });
});

describe('EmployeeProfilePage', () => {
  it('shows only the tabs the backend allows, employment first', async () => {
    server.use(...lookupHandlers, http.get(`${API}/people/employees/${EMP}`, () => HttpResponse.json(profile)));
    await changeLanguage('en');
    renderPage(<EmployeeProfilePage employmentId={EMP} onChange={() => {}} />);

    expect(await screen.findByText('Nguyễn Văn An')).toBeInTheDocument();
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent);
    expect(tabs).toEqual(['Employment', 'Personal details', 'Contracts']);
  });
});
