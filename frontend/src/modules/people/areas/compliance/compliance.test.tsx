import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meFixture } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { EmployeeProfile } from '../../common';
import type { ResidencyView, StatusView } from './api';
import { TaxResidencyTab } from './TaxResidencyTab';
import { SpecialStatusesTab } from './SpecialStatusesTab';
import { ResidencyProposalsPage } from './ResidencyProposalsPage';
import { ExpiringDocumentsPage } from './ExpiringDocumentsPage';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';
const P = `${API}/people/persons/${PERSON}`;

const profile: EmployeeProfile = {
  employment: { employmentId: EMP, employeeNo: 'NV0001', status: 'ACTIVE' },
  person: { id: PERSON, fullName: 'John Smith', ethnicityRecorded: false, religionRecorded: false, anonymised: false, version: 1 },
  otherEmployments: [],
  primaryAssignment: null,
  tabs: {},
  asOf: '2026-11-11',
};
const props = { employmentId: EMP, personId: PERSON, profile };

const residency: ResidencyView = {
  id: '018f0000-0000-7000-8000-0000000000b1',
  personId: PERSON,
  status: 'RESIDENT',
  basis: 'DAYS_183',
  validFrom: '2026-01-01',
  taxYear: 2026,
  daysInVn: 200,
  approvalStatus: 'APPROVED',
  version: 1,
};

const lookups = [
  http.get(`${API}/core/me`, () => HttpResponse.json(meFixture)),
  http.get(`${API}/core/*`, () => HttpResponse.json([])),
  http.get(`${P}`, () => HttpResponse.json({ id: PERSON, fullName: 'John Smith' })),
  http.get(`${P}/employments`, () => HttpResponse.json([{ id: EMP, employeeNo: 'NV0001', status: 'ACTIVE', pitPrimary: true }])),
];

describe('TaxResidencyTab', () => {
  it('lists tax residencies and presence periods', async () => {
    server.use(
      ...lookups,
      http.get(`${P}/tax-residencies`, () => HttpResponse.json([residency])),
      http.get(`${P}/vn-presence-periods`, () =>
        HttpResponse.json([{ id: 'p1', personId: PERSON, entryDate: '2026-01-05', exitDate: null, version: 0 }]),
      ),
    );
    await changeLanguage('en');
    renderPage(<TaxResidencyTab {...props} />);

    // two separate queries: wait for both
    await waitFor(() => {
      expect(screen.getByText('183 days or more in Vietnam')).toBeInTheDocument();
      expect(screen.getByText('Still in Vietnam')).toBeInTheDocument();
    });
    expect(screen.getByText('05/01/2026')).toBeInTheDocument();
    expect(screen.getByText('200')).toBeInTheDocument();
  });

  it('submits a residency with a basis matching the status', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${P}/tax-residencies`, () => HttpResponse.json([])),
      http.get(`${P}/vn-presence-periods`, () => HttpResponse.json([])),
      http.post(`${P}/tax-residencies`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ residency, workflowStatus: 'RUNNING' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<TaxResidencyTab {...props} />);

    await user.click(await screen.findByRole('button', { name: /Submit tax residency/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText('Status'));
    await user.click(await screen.findByTitle('Non-resident'));
    await user.click(within(drawer).getByLabelText('Basis'));
    // only the non-resident bases are offered
    expect(screen.queryByTitle('183 days or more in Vietnam')).not.toBeInTheDocument();
    await user.click(await screen.findByTitle('Conditions not met'));
    const from = within(drawer).getByLabelText('Valid from');
    await user.click(from);
    await user.type(from, '01/01/2026{Enter}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toMatchObject({ status: 'NON_RESIDENT', basis: 'NOT_MET', validFrom: '2026-01-01' }));
  });
});

describe('SpecialStatusesTab', () => {
  it('cancels a status with its version and hides actions on derived ones', async () => {
    const pregnancy: StatusView = {
      id: 's1',
      personId: PERSON,
      statusType: 'PREGNANCY',
      sensitive: true,
      validFrom: '2026-06-01',
      derived: false,
      status: 'ACTIVE',
      version: 7,
    };
    const minor: StatusView = { ...pregnancy, id: 's2', statusType: 'MINOR', sensitive: false, derived: true, version: 0 };
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${P}/special-statuses`, () => HttpResponse.json([pregnancy, minor])),
      http.post(`${P}/special-statuses/s1/cancel`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...pregnancy, status: 'CANCELLED' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SpecialStatusesTab {...props} />);

    expect(await screen.findByText('Pregnancy')).toBeInTheDocument();
    expect(screen.getByText('Protected from termination')).toBeInTheDocument();
    expect(screen.getByText('Derived')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /Cancel status/ })).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: /Cancel status/ }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(body).toEqual({ version: 7 }));
  });
});

describe('ResidencyProposalsPage', () => {
  const proposal: ResidencyView = { ...residency, id: 'prop1', proposalRef: 'PAY-2026-11', approvalStatus: 'PENDING', version: 2 };

  it('rejects a proposal with a reason', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${API}/people/persons/residency-proposals`, () => HttpResponse.json([proposal])),
      http.post(`${API}/people/persons/residency-proposals/prop1/reject`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...proposal, approvalStatus: 'REJECTED' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ResidencyProposalsPage />);

    expect(await screen.findByText('PAY-2026-11')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'John Smith' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Reject' }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Reason'), 'Days counted twice');
    await user.click(within(drawer).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(body).toEqual({ version: 2, reason: 'Days counted twice' }));
  });

  it('shows the translated problem when approval fails', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/people/persons/residency-proposals`, () => HttpResponse.json([proposal])),
      http.post(`${API}/people/persons/residency-proposals/prop1/approve`, () => problem(409, 'invalid-proposal', 'closed')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ResidencyProposalsPage />);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('The proposal is no longer open.')).toBeInTheDocument();
  });
});

describe('ExpiringDocumentsPage', () => {
  it('lists expiring identity documents and work permits with the window in days', async () => {
    const seen: string[] = [];
    server.use(
      ...lookups,
      http.get(`${API}/people/persons/expiring-identity-documents`, ({ request }) => {
        seen.push(`id:${new URL(request.url).searchParams.get('withinDays')}`);
        return HttpResponse.json([
          { documentId: 'd1', personId: PERSON, fullName: 'John Smith', employeeNos: ['NV0001'], docType: 'PASSPORT', docNoMasked: '••••987', expiryDate: '2026-12-01', daysLeft: 20 },
        ]);
      }),
      http.get(`${API}/people/persons/expiring-work-permits`, ({ request }) => {
        seen.push(`wp:${new URL(request.url).searchParams.get('withinDays')}`);
        return HttpResponse.json([
          { permitId: 'w1', personId: PERSON, employmentId: EMP, employeeNo: 'NV0001', fullName: 'John Smith', permitType: 'WORK_PERMIT', lastValidDay: '2026-11-20', daysLeft: 9, renewalInProgress: true },
        ]);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ExpiringDocumentsPage />);

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: 'Identity documents (1)' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Work permits (1)' })).toBeInTheDocument();
    });
    expect(screen.getByText('••••987')).toBeInTheDocument();
    expect(seen).toEqual(expect.arrayContaining(['id:60', 'wp:60']));

    await user.click(screen.getByRole('tab', { name: 'Work permits (1)' }));
    expect(await screen.findByText('Renewal in progress')).toBeInTheDocument();
  });
});
