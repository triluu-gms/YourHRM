import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { BankAccountView, DependantView, FamilyMemberView, MyDocument, MyEmployment, MyProfile, SeparationView } from './api';
import { MyProfilePage } from './MyProfilePage';
import { MyFamilyPage } from './MyFamilyPage';
import { MyBankAccountsPage } from './MyBankAccountsPage';
import { MyDocumentsPage } from './MyDocumentsPage';
import { MyResignationPage } from './MyResignationPage';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';
const MEMBER = '018f0000-0000-7000-8000-0000000000d1';
const DEP = '018f0000-0000-7000-8000-0000000000d2';
const DOC = '018f0000-0000-7000-8000-0000000000d3';
const SEP = '018f0000-0000-7000-8000-0000000000d4';

/** Core lookups used by name components and pickers; empty is enough here. */
const lookups = [
  ...['legal-entities', 'org-units', 'job-titles', 'locations', 'grade-structures'].map((p) =>
    http.get(`${API}/core/${p}`, () => HttpResponse.json([])),
  ),
  http.get(`${API}/core/master-data/countries`, () => HttpResponse.json([])),
  http.get(`${API}/core/master-data/banks`, () =>
    HttpResponse.json([{ bankCode: 'VCB', shortName: 'Vietcombank', nameVi: 'Ngoại thương', nameEn: 'Foreign Trade', active: true }]),
  ),
  http.get(`${API}/core/master-data/code-lists/:list/values`, ({ params }) =>
    HttpResponse.json(
      params.list === 'RELATIONSHIP'
        ? [{ valueCode: 'CHILD', labelVi: 'Con', labelEn: 'Child', sortOrder: 1, active: true }]
        : [],
    ),
  ),
];

const ref = { employmentId: EMP, employeeNo: 'NV0001', status: 'ACTIVE', hireDate: '2025-03-01' };

const myProfile: MyProfile = {
  personId: PERSON,
  fullName: 'Nguyễn Văn An',
  birthDate: '1990-05-01',
  nationalIdMasked: '••••••••1234',
  identityDocuments: [],
  addresses: [],
  contacts: [{ contactType: 'EMAIL', contactValue: 'an@example.test', primary: true, verified: true }],
  qualifications: [],
  taxResidency: [],
  employments: [ref],
};

const contract: MyDocument = { kind: 'CONTRACT', recordId: DEP, number: 'HD-001', status: 'ACTIVE', documentId: DOC, effectiveDate: '2025-03-01' };

const employment: MyEmployment = {
  employment: ref,
  assignments: [],
  contracts: [contract],
  decisions: [],
  salaryVisible: false,
  salary: null,
};

const member: FamilyMemberView = {
  id: MEMBER,
  personId: PERSON,
  relationshipCode: 'CHILD',
  fullName: 'Nguyễn Văn Bình',
  birthDate: '2015-01-01',
  nationalIdMasked: '••••5678',
  deceased: false,
  masked: true,
  version: 0,
};

const dependant: DependantView = {
  id: DEP,
  personId: PERSON,
  familyMemberId: MEMBER,
  familyMemberName: 'Nguyễn Văn Bình',
  relationshipCode: 'CHILD',
  fromMonth: '2025-03-01',
  reasonCode: 'CHILD_UNDER_AGE',
  status: 'APPROVED',
  evidenceDocumentIds: [],
  version: 2,
};

describe('MyProfilePage', () => {
  it('shows own masked profile and employment documents', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/people/me/profile`, () => HttpResponse.json(myProfile)),
      http.get(`${API}/people/me/employments`, () => HttpResponse.json([employment])),
    );
    await changeLanguage('en');
    renderPage(<MyProfilePage />);
    // Two independent queries: wait for both pieces together.
    await waitFor(() => {
      expect(screen.getByText('••••••••1234')).toBeInTheDocument();
      expect(screen.getByText('HD-001')).toBeInTheDocument();
    });
    expect(screen.getByText('Your employer does not show salary details here.')).toBeInTheDocument();
  });
});

describe('MyDocumentsPage', () => {
  it('fetches a fresh signed link and opens it', async () => {
    let opened: string | undefined;
    const original = window.open;
    window.open = ((url: string) => {
      opened = url;
      return null;
    }) as typeof window.open;
    server.use(
      http.get(`${API}/people/me/documents`, () => HttpResponse.json([contract, { ...contract, recordId: SEP, number: 'QD-9', kind: 'DECISION', documentId: null }])),
      http.get(`${API}/people/me/documents/${DOC}/download-link`, () =>
        HttpResponse.json({ url: 'https://files.example.test/signed', expiresAt: '2026-11-11T10:00:00Z', fileName: 'hd.pdf' }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MyDocumentsPage />);
    expect(await screen.findByText('No file yet')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Download/ }));
    await waitFor(() => expect(opened).toBe('https://files.example.test/signed'));
    window.open = original;
  });
});

describe('MyFamilyPage', () => {
  function familyHandlers(dependants: DependantView[] = [dependant]) {
    return [
      ...lookups,
      http.get(`${API}/people/me/family-members`, () => HttpResponse.json([member])),
      http.get(`${API}/people/me/dependants`, () => HttpResponse.json(dependants)),
    ];
  }

  it('lists members and dependants and requests an end with the claim version', async () => {
    let body: unknown;
    server.use(
      ...familyHandlers(),
      http.post(`${API}/people/me/dependants/${DEP}/end`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...dependant, pendingEndMonth: '2026-12-01' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MyFamilyPage />);
    await waitFor(() => {
      expect(screen.getByText('••••5678')).toBeInTheDocument();
      expect(screen.getByText('Child under 18')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: 'Request end' }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText('Last month'));
    await user.click(await screen.findByTitle('2026-12'));
    await user.click(within(drawer).getByRole('button', { name: 'Submit request' }));
    await waitFor(() => expect(body).toEqual({ toMonth: '2026-12', version: 2, evidenceDocumentIds: [] }));
  });

  it('shows eligibility violations from the check', async () => {
    server.use(
      ...familyHandlers([]),
      http.post(`${API}/people/me/dependants/validate`, () =>
        HttpResponse.json({ eligible: false, claimEmploymentId: EMP, violations: [{ code: 'income-above-threshold', message: 'x' }] }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MyFamilyPage />);
    await screen.findByText('Nguyễn Văn Bình');

    await user.click(screen.getByRole('button', { name: /Register dependant/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText('Family member'));
    await user.click(await screen.findByTitle('Nguyễn Văn Bình'));
    await user.click(within(drawer).getByLabelText('Reason'));
    await user.click(await screen.findByTitle('Child under 18'));
    await user.click(within(drawer).getByLabelText('From month'));
    await user.click(await screen.findByTitle('2026-12'));
    await user.click(within(drawer).getByRole('button', { name: /Check eligibility/ }));
    expect(await within(drawer).findByText("The person's income is above the threshold.")).toBeInTheDocument();
  });
});

describe('MyBankAccountsPage', () => {
  const account: BankAccountView = {
    id: DEP,
    personId: PERSON,
    bankCode: 'VCB',
    bankName: 'Vietcombank',
    accountNoMasked: '••••4321',
    holderName: 'NGUYEN VAN AN',
    splitType: 'REMAINDER',
    priority: 1,
    validFrom: '2025-03-01',
    status: 'PENDING',
    stepUpVerified: true,
    version: 0,
  };

  it('flags a pending change and explains a step-up refusal', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/people/me/bank-accounts`, () => HttpResponse.json([account])),
      http.post(`${API}/people/me/bank-accounts`, () => problem(403, 'step-up-required', 'No step-up')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MyBankAccountsPage />);
    expect(await screen.findByText('••••4321')).toBeInTheDocument();
    expect(screen.getAllByText('Pending approval').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: /Request change/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText('Bank'));
    await user.click(await screen.findByTitle(/Vietcombank/));
    await user.type(within(drawer).getByLabelText('Account number'), '0123456789');
    await user.type(within(drawer).getByLabelText('Account holder'), 'NGUYEN VAN AN');
    await user.click(within(drawer).getByRole('button', { name: 'Submit request' }));
    expect(await within(drawer).findByText(/sign in again \(step-up authentication\)/)).toBeInTheDocument();
  });
});

describe('MyResignationPage', () => {
  const submitted: SeparationView = {
    id: SEP,
    employmentId: EMP,
    employeeNo: 'NV0001',
    status: 'SUBMITTED',
    requestDate: '2026-11-11',
    lastWorkingDate: '2026-12-31',
    version: 1,
  };

  it('shows the form when there is no resignation', async () => {
    server.use(
      http.get(`${API}/people/me/resignation`, () => new HttpResponse(null, { status: 204 })),
      http.get(`${API}/people/me/employments`, () => HttpResponse.json([employment])),
    );
    await changeLanguage('en');
    renderPage(<MyResignationPage />);
    expect(await screen.findByText('You have no resignation in progress.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit resignation' })).toBeInTheDocument();
  });

  it('withdraws a pending resignation', async () => {
    let withdrawn = false;
    server.use(
      http.get(`${API}/people/me/resignation`, () => HttpResponse.json(submitted)),
      http.get(`${API}/people/me/employments`, () => HttpResponse.json([employment])),
      http.post(`${API}/people/me/resignation/${SEP}/withdraw`, () => {
        withdrawn = true;
        return HttpResponse.json({ outcome: 'WITHDRAWN', separation: { ...submitted, status: 'WITHDRAWN' } });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<MyResignationPage />);
    expect(await screen.findByText('Awaiting approval')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Withdraw' }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: 'Withdraw' }));
    await waitFor(() => expect(withdrawn).toBe(true));
  });
});
