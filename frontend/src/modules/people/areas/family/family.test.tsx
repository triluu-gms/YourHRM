import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, server } from '@/test/server';
import { meFixture } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { EmployeeProfile } from '../../common';
import type { BankAccountView, DependantView, FamilyMemberView } from './api';
import { FamilyMembersTab } from './FamilyMembersTab';
import { DependantsTab } from './DependantsTab';
import { BankAccountsTab } from './BankAccountsTab';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';
const MEMBER = '018f0000-0000-7000-8000-0000000000a1';
const DEP = '018f0000-0000-7000-8000-0000000000a2';
const ACC = '018f0000-0000-7000-8000-0000000000a3';
const P = `${API}/people/persons/${PERSON}`;

const profile: EmployeeProfile = {
  employment: { employmentId: EMP, employeeNo: 'NV0001', status: 'ACTIVE' },
  person: { id: PERSON, fullName: 'Nguyễn Văn An', ethnicityRecorded: false, religionRecorded: false, anonymised: false, version: 1 },
  otherEmployments: [],
  primaryAssignment: null,
  tabs: {},
  asOf: '2026-11-11',
};
const props = { employmentId: EMP, personId: PERSON, profile };

const member: FamilyMemberView = {
  id: MEMBER,
  personId: PERSON,
  relationshipCode: 'CHILD',
  fullName: 'Nguyễn Minh Khôi',
  birthDate: '2018-04-02',
  nationalIdMasked: '••••4321',
  taxCodeMasked: null,
  hasIncomeAboveThreshold: false,
  disabled: false,
  deceased: false,
  masked: false,
  version: 2,
};

const lookups = [
  http.get(`${API}/core/me`, () => HttpResponse.json(meFixture)),
  http.get(`${API}/core/master-data/code-lists/RELATIONSHIP/values`, () =>
    HttpResponse.json([{ valueCode: 'CHILD', labelVi: 'Con', labelEn: 'Child', sortOrder: 1, active: true }]),
  ),
  http.get(`${API}/core/documents`, () => HttpResponse.json([])),
  http.get(`${API}/core/*`, () => HttpResponse.json([])),
];

describe('FamilyMembersTab', () => {
  it('shows masked identifiers and reveals them after confirmation', async () => {
    server.use(
      ...lookups,
      http.get(`${P}/family-members`, () => HttpResponse.json([member])),
      http.get(`${P}/family-members/${MEMBER}/identifiers`, () =>
        HttpResponse.json({ id: MEMBER, nationalId: '001218004321', taxCode: null }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<FamilyMembersTab {...props} />);

    expect(await screen.findByText('Nguyễn Minh Khôi')).toBeInTheDocument();
    expect(screen.getByText('••••4321')).toBeInTheDocument();
    expect(await screen.findByText('Child')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Show identifiers/ }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('001218004321')).toBeInTheDocument();
  });

  it('creates a family member', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      ...lookups,
      http.get(`${P}/family-members`, () => HttpResponse.json([])),
      http.post(`${P}/family-members`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...member, id: 'x' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<FamilyMembersTab {...props} />);

    await user.click(await screen.findByRole('button', { name: /Add family member/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText('Relationship'));
    await user.click(await screen.findByTitle('Child'));
    await user.type(within(drawer).getByLabelText('Full name'), 'Trần Thị Bình');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toMatchObject({ relationshipCode: 'CHILD', fullName: 'Trần Thị Bình', nationalId: null, version: null }));
  });
});

describe('DependantsTab', () => {
  it('lists claims and requests the end of an approved one with its version', async () => {
    const dependant: DependantView = {
      id: DEP,
      personId: PERSON,
      familyMemberId: MEMBER,
      familyMemberName: 'Nguyễn Minh Khôi',
      relationshipCode: 'CHILD',
      fromMonth: '2025-01-01',
      toMonth: null,
      reasonCode: 'CHILD_UNDER_AGE',
      status: 'APPROVED',
      evidenceDocumentIds: [],
      version: 5,
    };
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${P}/dependants`, () => HttpResponse.json([dependant])),
      http.get(`${P}/family-members`, () => HttpResponse.json([member])),
      http.post(`${P}/dependants/${DEP}/end`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...dependant, pendingEndMonth: '2026-12-01', pendingEndRequestId: 'r' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DependantsTab {...props} />);

    expect(await screen.findByText('Child under 18')).toBeInTheDocument();
    expect(screen.getByText('01/2025')).toBeInTheDocument();
    expect(screen.getByText('Approved')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'End deduction' }));
    const drawer = await screen.findByRole('dialog');
    const month = within(drawer).getByLabelText('Last month of deduction');
    await user.click(month);
    await user.type(month, '12/2026{Enter}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toEqual({ toMonth: '2026-12', version: 5, evidenceDocumentIds: [] }));
  });

  it('shows the eligibility violations before submitting', async () => {
    server.use(
      ...lookups,
      http.get(`${P}/dependants`, () => HttpResponse.json([])),
      http.get(`${P}/family-members`, () => HttpResponse.json([member])),
      http.post(`${P}/dependants/validate`, () =>
        HttpResponse.json({ eligible: false, claimEmploymentId: EMP, violations: [{ code: 'AGE', message: 'Child is over 18', month: '2026-05' }] }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DependantsTab {...props} />);

    await user.click(await screen.findByRole('button', { name: /Register dependant/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText('Family member'));
    await user.click(await screen.findByTitle('Nguyễn Minh Khôi'));
    await user.click(within(drawer).getByLabelText('Reason'));
    await user.click(await screen.findByTitle('Child under 18'));
    const from = within(drawer).getByLabelText('Deduction from month');
    await user.click(from);
    await user.type(from, '01/2026{Enter}');
    await user.click(within(drawer).getByRole('button', { name: /Check eligibility/ }));
    expect(await within(drawer).findByText('Child is over 18 (2026-05)')).toBeInTheDocument();
  });
});

describe('BankAccountsTab', () => {
  const account: BankAccountView = {
    id: ACC,
    personId: PERSON,
    bankCode: 'VCB',
    bankName: 'Vietcombank',
    accountNoMasked: '••••6789',
    holderName: 'NGUYEN VAN AN',
    currencyCode: 'VND',
    splitType: 'REMAINDER',
    priority: 1,
    validFrom: '2025-03-01',
    validTo: null,
    status: 'ACTIVE',
    stepUpVerified: false,
    version: 3,
  };

  it('reveals the account number and closes the account', async () => {
    let closeBody: unknown;
    server.use(
      ...lookups,
      http.get(`${P}/bank-accounts`, () => HttpResponse.json([account])),
      http.get(`${P}/bank-accounts/${ACC}/account-number`, () => HttpResponse.json({ id: ACC, accountNo: '0123456789' })),
      http.post(`${P}/bank-accounts/${ACC}/close`, async ({ request }) => {
        closeBody = await request.json();
        return HttpResponse.json({ ...account, validTo: '2026-12-31' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<BankAccountsTab {...props} />);

    expect(await screen.findByText('••••6789')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Show number/ }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('0123456789')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Close account' }));
    const drawer = await screen.findByRole('dialog');
    const date = within(drawer).getByLabelText('Last valid date');
    await user.click(date);
    await user.type(date, '31/12/2026{Enter}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(closeBody).toEqual({ validTo: '2026-12-31', version: 3 }));
  });

  it('requires a bank before submitting a new account', async () => {
    server.use(
      ...lookups,
      http.get(`${P}/bank-accounts`, () => HttpResponse.json([])),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<BankAccountsTab {...props} />);

    await user.click(await screen.findByRole('button', { name: /Add bank account/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Account number'), '0123456789');
    const from = within(drawer).getByLabelText('Valid from');
    await user.click(from);
    await user.type(from, '01/12/2026{Enter}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    expect(await within(drawer).findByText('Select the bank.')).toBeInTheDocument();
  });
});
