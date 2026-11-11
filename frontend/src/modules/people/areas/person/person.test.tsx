import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, server } from '@/test/server';
import { meFixture } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { EmployeeProfile, PersonView } from '../../common';
import { PersonTab } from './PersonTab';
import { ContactsTab } from './ContactsTab';
import { AddressesTab } from './AddressesTab';
import { HirePage } from './HirePage';
import type { AddressView, ContactView, HireResult } from './api';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';
const LE = '018f0000-0000-7000-8000-0000000000f1';
const OU = '018f0000-0000-7000-8000-0000000000f2';

const person: PersonView = {
  id: PERSON,
  familyName: 'Nguyễn',
  middleName: 'Văn',
  givenName: 'An',
  fullName: 'Nguyễn Văn An',
  birthDate: '1990-05-17',
  genderCode: 'MALE',
  nationalityCode: 'VN',
  nationalIdType: 'CCCD',
  nationalIdMasked: '••••1234',
  taxCodeMasked: '••••5678',
  ethnicityRecorded: true,
  religionRecorded: false,
  anonymised: false,
  version: 4,
};

const profile: EmployeeProfile = {
  employment: { employmentId: EMP, employeeNo: 'NV0001', status: 'ACTIVE' },
  person,
  otherEmployments: [],
  primaryAssignment: null,
  tabs: {},
  asOf: '2026-11-11',
};
const props = { employmentId: EMP, personId: PERSON, profile };

/** Core lookups (names, pickers); one legal entity and one org unit for the hire wizard. */
const lookups = [
  http.get(`${API}/core/legal-entities`, () =>
    HttpResponse.json([{ id: LE, code: 'LE01', status: 'ACTIVE', current: { shortName: 'Demo' } }]),
  ),
  http.get(`${API}/core/org-units`, () =>
    HttpResponse.json([{ id: OU, code: 'HR', nameVi: 'Phòng Nhân sự', nameEn: 'HR Dept', legalEntityId: LE, depth: 0 }]),
  ),
  // The catch-all below would otherwise swallow /core/me.
  http.get(`${API}/core/me`, () => HttpResponse.json(meFixture)),
  http.get(`${API}/core/*`, () => HttpResponse.json([])),
  http.get(`${API}/people/employees`, () => HttpResponse.json({ items: [], total: 0, page: 0, size: 20 })),
];

describe('PersonTab', () => {
  it('shows masked identifiers and unmasks with a purpose', async () => {
    let unmaskBody: unknown;
    server.use(
      ...lookups,
      http.get(`${API}/people/persons/${PERSON}`, () => HttpResponse.json(person)),
      http.post(`${API}/people/persons/${PERSON}/unmask`, async ({ request }) => {
        unmaskBody = await request.json();
        return HttpResponse.json({ personId: PERSON, values: { NATIONAL_ID: '001090001234' }, withheld: ['TAX_CODE'] });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PersonTab {...props} />);

    expect(await screen.findByText('••••1234')).toBeInTheDocument();
    expect(screen.queryByText('001090001234')).not.toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: /Show sensitive values/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Purpose/), 'SI declaration');
    await user.click(within(drawer).getByRole('button', { name: 'Show' }));

    expect(await screen.findByText('001090001234')).toBeInTheDocument();
    expect(screen.getByText('Withheld: Tax code')).toBeInTheDocument();
    expect(unmaskBody).toEqual({ fields: ['NATIONAL_ID'], purpose: 'SI declaration' });
  });

  it('saves edited details with the person version', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${API}/people/persons/${PERSON}`, () => HttpResponse.json(person)),
      http.put(`${API}/people/persons/${PERSON}`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...person, version: 5 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PersonTab {...props} />);

    await user.click(await screen.findByRole('button', { name: /Edit details/ }));
    const drawer = await screen.findByRole('dialog');
    const preferred = within(drawer).getByLabelText(/^Preferred name/);
    await user.type(preferred, 'An Nguyen');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(body).toBeDefined());
    expect(body).toMatchObject({
      version: 4,
      person: { familyName: 'Nguyễn', givenName: 'An', preferredName: 'An Nguyen', birthDate: '1990-05-17', genderCode: 'MALE' },
    });
  });

  it('warns that a 1 January birth date may be masked', async () => {
    const masked = { ...person, birthDate: '1990-01-01' };
    server.use(...lookups, http.get(`${API}/people/persons/${PERSON}`, () => HttpResponse.json(masked)));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PersonTab {...props} profile={{ ...profile, person: masked }} />);
    await user.click(await screen.findByRole('button', { name: /Edit details/ }));
    expect(await screen.findByText(/may be masked/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Load actual date of birth' })).toBeInTheDocument();
  });
});

describe('ContactsTab', () => {
  it('validates and adds a contact', async () => {
    let body: unknown;
    const contacts: ContactView[] = [];
    server.use(
      http.get(`${API}/people/persons/${PERSON}/contacts`, () => HttpResponse.json(contacts)),
      http.post(`${API}/people/persons/${PERSON}/contacts`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ id: 'c1', ...(body as object), verified: false, usableForFormerEmployeeLogin: false, version: 0 }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ContactsTab {...props} />);

    await user.click(await screen.findByRole('button', { name: /Add contact/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText(/^Type/));
    await user.click(await screen.findByTitle('Personal e-mail'));
    await user.type(within(drawer).getByLabelText(/^Value/), 'not-an-email');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    expect(await within(drawer).findByText('Invalid e-mail address.')).toBeInTheDocument();

    await user.clear(within(drawer).getByLabelText(/^Value/));
    await user.type(within(drawer).getByLabelText(/^Value/), 'an@example.test');
    await user.click(within(drawer).getByRole('checkbox', { name: 'Primary' }));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toEqual({ contactType: 'PERSONAL_EMAIL', value: 'an@example.test', primary: true }));
  });
});

describe('AddressesTab', () => {
  it('lists current addresses as of the profile date and keeps legacy rows read-only', async () => {
    let asOf: string | null = 'unset';
    const rows: AddressView[] = [
      { id: 'a1', addressType: 'PERMANENT', countryCode: 'VN', addressLine: '12 Lê Lợi', readOnly: false, validFrom: '2020-01-01', version: 1 },
      { id: 'a2', addressType: 'HOMETOWN', legacyAddressText: 'Xã A, Huyện B, Tỉnh C', readOnly: true, validFrom: '2000-01-01', version: 0 },
    ];
    server.use(
      ...lookups,
      http.get(`${API}/people/persons/${PERSON}/addresses`, ({ request }) => {
        asOf = new URL(request.url).searchParams.get('asOf');
        return HttpResponse.json(rows);
      }),
    );
    await changeLanguage('en');
    renderPage(<AddressesTab {...props} />);

    expect(await screen.findByText('12 Lê Lợi')).toBeInTheDocument();
    expect(asOf).toBe('2026-11-11');
    expect(screen.getByText('Legacy address (3-level, read-only)')).toBeInTheDocument();
    // Only the editable row has Edit / End.
    expect(screen.getAllByRole('button', { name: 'Edit' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'End' })).toHaveLength(1);
  });
});

describe('HirePage', () => {
  const result: HireResult = {
    employmentId: EMP,
    personId: PERSON,
    personCreated: true,
    employeeNo: 'NV0002',
    hireType: 'NEW',
    status: 'PRE_HIRE',
    decisionNo: 'QD-01',
    warnings: [],
    missingData: ['bankAccount'],
  };

  async function fillIdentify(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByLabelText(/^Legal entity/));
    await user.click(await screen.findByTitle('LE01 – Demo'));
    await user.type(screen.getByLabelText(/^Hire date/), '01/12/2026{Enter}');
    await user.type(screen.getByLabelText(/^ID number/), '001090001234');
  }

  it('checks for duplicates, then submits a new hire', async () => {
    let matchBody: unknown;
    let hireBody: Record<string, unknown> | undefined;
    server.use(
      ...lookups,
      http.post(`${API}/people/hires/match`, async ({ request }) => {
        matchBody = await request.json();
        return HttpResponse.json({ blocking: [], warnings: [] });
      }),
      http.post(`${API}/people/hires`, async ({ request }) => {
        hireBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(result);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<HirePage />, { path: '/people/hires/new' });

    await fillIdentify(user);
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Check' }));
    expect(await screen.findByText(/No existing person found/)).toBeInTheDocument();
    expect(matchBody).toMatchObject({ nationalIdType: 'CCCD', nationalId: '001090001234', legalEntityId: LE, hireDate: '2026-12-01' });
    await user.click(screen.getByRole('button', { name: 'Next' }));

    // Person
    await user.type(screen.getByLabelText(/^Family name/), 'Trần');
    await user.type(screen.getByLabelText(/^Given name/), 'Bình');
    await user.click(screen.getByRole('button', { name: 'Next' }));

    // Employment: org unit is required.
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Select the org unit.')).toBeInTheDocument();
    await user.click(screen.getByLabelText(/^Org unit/));
    await user.click(await screen.findByTitle('HR – HR Dept'));
    await user.click(screen.getByRole('button', { name: 'Next' }));

    // Contract & salary
    await user.click(screen.getByLabelText(/^Contract type/));
    await user.click(await screen.findByTitle('Indefinite-term'));
    await user.type(screen.getByLabelText(/^Base salary/), '15000000');
    await user.click(screen.getByRole('button', { name: 'Next' }));

    await user.click(await screen.findByRole('button', { name: 'Submit hire' }));
    expect(await screen.findByText('Hire submitted: NV0002')).toBeInTheDocument();
    expect(screen.getByText('bankAccount')).toBeInTheDocument();
    expect(hireBody).toMatchObject({
      personId: null,
      person: { familyName: 'Trần', givenName: 'Bình', nationalIdType: 'CCCD', nationalId: '001090001234', nationalityCode: 'VN', acknowledgeWarnings: false },
      hireType: null,
      legalEntityId: LE,
      hireDate: '2026-12-01',
      workerCategoryCode: 'EMPLOYEE',
      assignment: { orgUnitId: OU },
      contract: { contractTypeCode: 'INDEFINITE' },
      salary: { payBasis: 'GROSS', salaryKind: 'MONTHLY', baseSalaryAmount: 15000000, currencyCode: 'VND', allowances: [] },
    });
  }, 30_000);

  it('continues with an existing person on a blocking match (rehire) and skips the person step', async () => {
    server.use(
      ...lookups,
      http.post(`${API}/people/hires/match`, () =>
        HttpResponse.json({
          blocking: [
            {
              candidate: { personId: PERSON, fullName: 'Nguyễn Văn An', birthYear: 1990, matchedOn: 'NATIONAL_ID', employeeNos: ['NV0001'] },
              employments: [{ employmentId: EMP, employeeNo: 'NV0001', status: 'TERMINATED', legalEntityId: LE }],
              options: ['REHIRE'],
            },
          ],
          warnings: [],
        }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<HirePage />, { path: '/people/hires/new' });

    await fillIdentify(user);
    await user.click(screen.getByRole('button', { name: 'Check' }));
    expect(await screen.findByText('This person already exists')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Rehire' }));
    expect(screen.getByText('Hiring existing person Nguyễn Văn An')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));

    // Straight to the employment step (person step skipped): steps bar shows 4 steps.
    expect(screen.queryByText('Person')).not.toBeInTheDocument();
    expect(screen.getByText('First assignment')).toBeVisible();
  }, 30_000);
});
