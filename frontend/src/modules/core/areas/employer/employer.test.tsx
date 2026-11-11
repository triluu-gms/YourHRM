import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { CompanyGroup, LegalEntity, Location, OrgUnit, OrgUnitType } from './api';
import { CompanyGroupPage } from './CompanyGroupPage';
import { LegalEntitiesPage } from './LegalEntitiesPage';
import { LegalEntityDetailPage } from './LegalEntityDetailPage';
import { LocationDetailPage } from './LocationDetailPage';
import { OrgUnitTypesPage } from './OrgUnitTypesPage';
import { OrgUnitsPage } from './OrgUnitsPage';

const LE_ID = '018f0000-0000-7000-8000-0000000000e1';
const LOC_ID = '018f0000-0000-7000-8000-0000000000f1';
const TYPE_COMPANY = '018f0000-0000-7000-8000-0000000000d1';
const TYPE_DEPT = '018f0000-0000-7000-8000-0000000000d2';

const legalEntity: LegalEntity = {
  id: LE_ID,
  code: 'ACME_VN',
  status: 'ACTIVE',
  version: 4,
  current: {
    versionId: '018f0000-0000-7000-8000-0000000000e2',
    validFrom: '2026-01-01',
    validTo: null,
    legalNameVi: 'Công ty TNHH Acme Việt Nam',
    legalNameEn: 'Acme Vietnam LLC',
    shortName: 'Acme VN',
    taxCode: '0312345678',
    siUnitCode: 'YN0123A',
    taxAuthorityCode: '79101',
    fiscalYearStartMonth: 1,
    pitDeclarationFrequency: 'MONTHLY',
    oaOdRate: null,
  },
};

const location: Location = {
  id: LOC_ID,
  code: 'HCM_HQ',
  legalEntityId: LE_ID,
  status: 'ACTIVE',
  version: 2,
  current: {
    versionId: '018f0000-0000-7000-8000-0000000000f2',
    validFrom: '2026-01-01',
    validTo: null,
    name: 'Trụ sở Hồ Chí Minh',
    locationType: 'HEAD_OFFICE',
    remoteArea: false,
  },
};

const types: OrgUnitType[] = [
  { id: TYPE_COMPANY, code: 'COMPANY', nameVi: 'Công ty', nameEn: 'Company', levelHint: 1, sortOrder: 1, active: true, version: 0 },
  { id: TYPE_DEPT, code: 'DEPARTMENT', nameVi: 'Phòng', nameEn: 'Department', levelHint: 3, sortOrder: 3, active: true, version: 5 },
];

const unit = (id: string, code: string, nameEn: string, parentId: string | null, depth: number, extra: Partial<OrgUnit> = {}): OrgUnit => ({
  id,
  code,
  versionId: `${id.slice(0, -2)}9${id.slice(-1)}`,
  validFrom: '2026-01-01',
  validTo: null,
  nameVi: `VI ${nameEn}`,
  nameEn,
  typeId: depth === 1 ? TYPE_COMPANY : TYPE_DEPT,
  typeCode: depth === 1 ? 'COMPANY' : 'DEPARTMENT',
  typeNameVi: depth === 1 ? 'Công ty' : 'Phòng',
  typeNameEn: depth === 1 ? 'Company' : 'Department',
  parentId,
  legalEntityId: LE_ID,
  legalEntityCode: 'ACME_VN',
  legalEntityName: 'Acme VN',
  sortOrder: 0,
  status: 'ACTIVE',
  depth,
  version: 3,
  ...extra,
});

const ROOT = '018f0000-0000-7000-8000-0000000000c1';
const HR = '018f0000-0000-7000-8000-0000000000c2';
const PAYROLL = '018f0000-0000-7000-8000-0000000000c3';
const IT = '018f0000-0000-7000-8000-0000000000c4';

describe('CompanyGroupPage', () => {
  it('updates the profile with the version it read', async () => {
    const group: CompanyGroup = {
      id: '018f0000-0000-7000-8000-0000000000b1',
      name: 'Acme Group',
      defaultLocale: 'vi-VN',
      timeZone: 'Asia/Ho_Chi_Minh',
      currencyCode: 'VND',
      version: 7,
    };
    let body: unknown;
    server.use(
      http.get(`${API}/core/company-group`, () => HttpResponse.json(group)),
      http.put(`${API}/core/company-group`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...group, name: 'Acme Holdings', version: 8 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<CompanyGroupPage />, { path: '/core/company-group' });

    expect(await screen.findByText('Acme Group')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Edit profile/ }));
    const drawer = await screen.findByRole('dialog');
    const name = within(drawer).getByLabelText(/Company \/ group name/);
    await user.clear(name);
    await user.type(name, 'Acme Holdings');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toBeDefined());
    expect(body).toMatchObject({ name: 'Acme Holdings', defaultLocale: 'vi-VN', currencyCode: 'VND', version: 7, logoDocumentId: null });
  }, 15_000);

  it('hides the edit button without core.company_group.update', async () => {
    server.use(
      http.get(`${API}/core/company-group`, () =>
        HttpResponse.json({ id: 'x', name: 'Acme Group', defaultLocale: 'vi-VN', timeZone: 'Asia/Ho_Chi_Minh', currencyCode: 'VND', version: 1 }),
      ),
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.company_group.view'))),
    );
    await changeLanguage('en');
    renderPage(<CompanyGroupPage />, { path: '/core/company-group' });
    expect(await screen.findByText('Acme Group')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit profile/ })).not.toBeInTheDocument();
  });
});

describe('LegalEntitiesPage', () => {
  it('lists legal entities with the plan usage and creates one', async () => {
    let created: unknown;
    let listedAsOf: string | null = null;
    server.use(
      http.get(`${API}/core/legal-entities`, ({ request }) => {
        listedAsOf = new URL(request.url).searchParams.get('asOf');
        return HttpResponse.json([legalEntity]);
      }),
      http.get(`${API}/core/legal-entities/usage`, () => HttpResponse.json({ used: 1, limit: 3 })),
      http.post(`${API}/core/legal-entities`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json({ ...legalEntity, id: '018f0000-0000-7000-8000-0000000000e9', code: 'ACME_HN' }, { status: 201 });
      }),
      http.get(`${API}/core/legal-entities/:id`, () => HttpResponse.json(legalEntity)),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<LegalEntitiesPage />, { path: '/core/legal-entities' });

    expect(await screen.findByText('ACME_VN')).toBeInTheDocument();
    expect(screen.getByText('0312345678')).toBeInTheDocument();
    expect(screen.getByText('YN0123A')).toBeInTheDocument();
    expect(await screen.findByText('1/3 legal entities in the plan')).toBeInTheDocument();
    expect(listedAsOf).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    await user.click(screen.getByRole('button', { name: /Create legal entity/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Code/), 'ACME_HN');
    await user.type(within(drawer).getByLabelText(/Legal name \(VI\)/), 'Công ty Acme Hà Nội');
    await user.type(within(drawer).getByLabelText(/Tax code \(MST\)/), '123');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    expect(await within(drawer).findByText('10 digits, or 13 as NNNNNNNNNN-NNN.')).toBeInTheDocument();
    expect(created).toBeUndefined();

    const mst = within(drawer).getByLabelText(/Tax code \(MST\)/);
    await user.clear(mst);
    await user.type(mst, '0109876543');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(created).toBeDefined());
    expect(created).toMatchObject({
      code: 'ACME_HN',
      status: 'DRAFT',
      validFrom: null,
      attributes: {
        legalNameVi: 'Công ty Acme Hà Nội',
        taxCode: '0109876543',
        legalNameEn: null,
        fiscalYearStartMonth: 1,
        pitDeclarationFrequency: 'MONTHLY',
        oaOdRate: null,
      },
    });
  }, 20_000);

  it('shows the translated problem when the tax code is taken', async () => {
    server.use(
      http.get(`${API}/core/legal-entities`, () => HttpResponse.json([])),
      http.get(`${API}/core/legal-entities/usage`, () => HttpResponse.json({ used: 0, limit: null })),
      http.post(`${API}/core/legal-entities`, () => problem(409, 'tax-code-taken', 'MST 0109876543 is used by ACME_VN')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<LegalEntitiesPage />, { path: '/core/legal-entities' });

    await user.click(await screen.findByRole('button', { name: /Create legal entity/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Code/), 'ACME_HN');
    await user.type(within(drawer).getByLabelText(/Legal name \(VI\)/), 'Acme HN');
    await user.type(within(drawer).getByLabelText(/Tax code \(MST\)/), '0109876543');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    expect(await within(drawer).findByText('Another legal entity already uses this tax code.')).toBeInTheDocument();
  }, 20_000);
});

describe('LegalEntityDetailPage', () => {
  it('shows the entity with its locations and refuses to dissolve while locations are open', async () => {
    let statusBody: unknown;
    server.use(
      http.get(`${API}/core/legal-entities/${LE_ID}`, () => HttpResponse.json(legalEntity)),
      http.get(`${API}/core/legal-entities/${LE_ID}/min-wage-region`, () =>
        problem(503, 'legal-parameters-unavailable', 'LPS is not available'),
      ),
      http.get(`${API}/core/locations`, () => HttpResponse.json([location])),
      http.put(`${API}/core/legal-entities/${LE_ID}/status`, async ({ request }) => {
        statusBody = await request.json();
        return problem(409, 'legal-entity-has-active-locations', 'Close every location of ACME_VN before dissolving it');
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<LegalEntityDetailPage legalEntityId={LE_ID} />);

    // Two independent queries (entity + its locations): wait for both.
    await waitFor(() => {
      expect(screen.getByText('Legal entity ACME_VN')).toBeInTheDocument();
      expect(screen.getByText('HCM_HQ')).toBeInTheDocument();
    });
    expect(screen.getByText('Not determined')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Dissolve' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(statusBody).toEqual({ status: 'DISSOLVED', version: 4 }));
    expect(
      (await screen.findAllByText('Close every location of this legal entity before dissolving it.')).length,
    ).toBeGreaterThan(0);
  }, 15_000);
});

describe('LegalEntityForm (new version)', () => {
  it('sends the entity version and maps an oaOdRate field error onto the percent field', async () => {
    let body: unknown;
    server.use(
      http.get(`${API}/core/legal-entities/${LE_ID}`, () => HttpResponse.json(legalEntity)),
      http.get(`${API}/core/legal-entities/${LE_ID}/min-wage-region`, () => problem(503, 'legal-parameters-unavailable')),
      http.get(`${API}/core/locations`, () => HttpResponse.json([])),
      http.post(`${API}/core/legal-entities/${LE_ID}/versions`, async ({ request }) => {
        body = await request.json();
        return problem(400, 'validation-failed', 'Invalid data', {
          errors: [{ field: 'attributes.oaOdRate', message: 'must be less than 0.05' }],
        });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<LegalEntityDetailPage legalEntityId={LE_ID} />);

    await user.click(await screen.findByRole('button', { name: /New version/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toMatchObject({ version: 4, attributes: { taxCode: '0312345678' } }));
    const rate = within(drawer).getByLabelText(/OA\/OD insurance rate/);
    const item = rate.closest('.ant-form-item') as HTMLElement;
    expect(await within(item).findByText('must be less than 0.05')).toBeInTheDocument();
  }, 20_000);
});

describe('LocationDetailPage', () => {
  it('closes the location with its version', async () => {
    let closeBody: unknown;
    server.use(
      http.get(`${API}/core/locations/${LOC_ID}`, () => HttpResponse.json(location)),
      http.get(`${API}/core/locations/${LOC_ID}/min-wage-region`, () =>
        HttpResponse.json({ locationId: LOC_ID, asOf: '2026-11-11', adminUnitCode: '79', region: 'I', packVersion: 3, overridden: false }),
      ),
      http.get(`${API}/core/legal-entities/${LE_ID}`, () => HttpResponse.json(legalEntity)),
      http.post(`${API}/core/locations/${LOC_ID}/close`, async ({ request }) => {
        closeBody = await request.json();
        return HttpResponse.json({ ...location, status: 'CLOSED', version: 3 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<LocationDetailPage locationId={LOC_ID} />);

    await waitFor(() => {
      expect(screen.getByText('Location HCM_HQ')).toBeInTheDocument();
      expect(screen.getByText('Region I')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: /Close location/ }));
    const modal = await screen.findByRole('dialog');
    const date = within(modal).getByPlaceholderText('Select date');
    await user.clear(date);
    await user.type(date, '01/12/2026{Enter}');
    await user.click(within(modal).getByRole('button', { name: /Close location/ }));
    await waitFor(() => expect(closeBody).toEqual({ effectiveFrom: '2026-12-01', version: 2 }));
  }, 15_000);

  it('lists the planned versions after a refused close and removes one with the version', async () => {
    const planned = { ...location.current!, versionId: '018f0000-0000-7000-8000-0000000000f3', validFrom: '2099-03-01', name: 'HQ 2099' };
    let removed: { url: string; version: string | null } | undefined;
    server.use(
      http.get(`${API}/core/locations/${LOC_ID}`, () => HttpResponse.json(location)),
      http.get(`${API}/core/locations/${LOC_ID}/min-wage-region`, () => problem(503, 'legal-parameters-unavailable')),
      http.get(`${API}/core/legal-entities/${LE_ID}`, () => HttpResponse.json(legalEntity)),
      http.get(`${API}/core/locations/${LOC_ID}/versions`, () =>
        HttpResponse.json([{ ...location.current, validTo: '2099-03-01' }, planned]),
      ),
      http.post(`${API}/core/locations/${LOC_ID}/close`, () =>
        problem(409, 'future-versions-exist', 'Location HCM_HQ has changes planned'),
      ),
      http.delete(`${API}/core/locations/${LOC_ID}/versions/:versionId`, ({ request }) => {
        const url = new URL(request.url);
        removed = { url: url.pathname, version: url.searchParams.get('version') };
        return HttpResponse.json(location);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<LocationDetailPage locationId={LOC_ID} />);

    await user.click(await screen.findByRole('button', { name: /Close location/ }));
    const modal = await screen.findByRole('dialog');
    await user.click(within(modal).getByRole('button', { name: /Close location/ }));
    expect(await within(modal).findByText('Changes are planned on or after this date; remove the planned versions first.')).toBeInTheDocument();
    expect(await within(modal).findByText('HQ 2099')).toBeInTheDocument();
    await user.click(within(modal).getByRole('button', { name: 'Remove planned version' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(removed).toEqual({ url: `/api/v1/core/locations/${LOC_ID}/versions/${planned.versionId}`, version: '2' }));
  }, 15_000);
});

describe('OrgUnitTypesPage', () => {
  it('edits a type', async () => {
    let body: unknown;
    server.use(
      http.get(`${API}/core/org-unit-types`, () => HttpResponse.json(types)),
      http.put(`${API}/core/org-unit-types/${TYPE_DEPT}`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...types[1], nameEn: 'Dept' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OrgUnitTypesPage />, { path: '/core/org-unit-types' });

    await user.click(await screen.findByRole('button', { name: 'Edit DEPARTMENT' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByLabelText(/^Code/)).toBeDisabled();
    const nameEn = within(drawer).getByLabelText(/Name \(EN\)/);
    await user.clear(nameEn);
    await user.type(nameEn, 'Dept');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toEqual({ nameVi: 'Phòng', nameEn: 'Dept', levelHint: 3, sortOrder: 3, active: true, version: 5 }));
  }, 15_000);
});

describe('OrgUnitsPage', () => {
  const tree: OrgUnit[] = [
    unit(ROOT, 'ACME', 'Acme Vietnam', null, 1),
    unit(HR, 'HR', 'Human resources', ROOT, 2, { sortOrder: 1 }),
    unit(PAYROLL, 'PAY', 'Payroll', HR, 3),
    unit(IT, 'IT', 'Information technology', ROOT, 2, { sortOrder: 2 }),
  ];

  it('renders the tree as of a date and shows the selected unit', async () => {
    const asOfs: (string | null)[] = [];
    server.use(
      http.get(`${API}/core/org-units`, ({ request }) => {
        const asOf = new URL(request.url).searchParams.get('asOf');
        asOfs.push(asOf);
        // Before 2026 only the root existed.
        return HttpResponse.json(asOf && asOf < '2026-01-01' ? [tree[0]] : tree);
      }),
      http.get(`${API}/core/org-unit-types`, () => HttpResponse.json(types)),
      http.get(`${API}/core/legal-entities/${LE_ID}`, () => HttpResponse.json(legalEntity)),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OrgUnitsPage />, { path: '/core/org-units' });

    // Nested nodes, all expanded.
    expect(await screen.findByText('PAY – Payroll')).toBeInTheDocument();
    expect(screen.getByText('ACME – Acme Vietnam')).toBeInTheDocument();
    expect(screen.getByText('HR – Human resources')).toBeInTheDocument();
    expect(screen.getByText('IT – Information technology')).toBeInTheDocument();
    expect(screen.getByText('4 units')).toBeInTheDocument();

    await user.click(screen.getByText('PAY – Payroll'));
    // The details card: parent name from the tree; type and legal entity names come with the view.
    await waitFor(() => {
      expect(screen.getAllByText('PAY – Payroll').length).toBeGreaterThan(1);
      expect(screen.getAllByText('HR – Human resources').length).toBeGreaterThan(1);
    });
    expect(screen.getByText('Department')).toBeInTheDocument();
    expect(screen.getByText('ACME_VN – Acme VN')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Create child unit/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New version \/ restructure/ })).toBeInTheDocument();

    // Change the as-of date: the tree is fetched again for that date.
    const picker = screen.getByPlaceholderText('Select date');
    await user.clear(picker);
    await user.type(picker, '15/06/2025{Enter}');
    await waitFor(() => expect(asOfs).toContain('2025-06-15'));
    await waitFor(() => expect(screen.queryByText('PAY – Payroll')).not.toBeInTheDocument());
    expect(screen.getByText('1 unit')).toBeInTheDocument();
  }, 20_000);

  it('closes a unit from a date and shows the translated refusal', async () => {
    let closeBody: unknown;
    server.use(
      http.get(`${API}/core/org-units`, () => HttpResponse.json(tree)),
      http.get(`${API}/core/org-unit-types`, () => HttpResponse.json(types)),
      http.get(`${API}/core/legal-entities/${LE_ID}`, () => HttpResponse.json(legalEntity)),
      http.post(`${API}/core/org-units/${HR}/close`, async ({ request }) => {
        closeBody = await request.json();
        return problem(409, 'org-unit-has-children', 'Move or close the child units of HR first');
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OrgUnitsPage />, { path: '/core/org-units' });

    await user.click(await screen.findByText('HR – Human resources'));
    await user.click(await screen.findByRole('button', { name: /Close unit/ }));
    const modal = await screen.findByRole('dialog');
    await user.click(within(modal).getByRole('button', { name: /Close unit/ }));
    await waitFor(() =>
      expect(closeBody).toEqual({ effectiveFrom: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), version: 3 }),
    );
    expect(await within(modal).findByText('Move or close the child units first.')).toBeInTheDocument();
  }, 20_000);

  it('offers to remove the planned version that blocks a close', async () => {
    const plannedMove = unit(IT, 'IT', 'IT (2099)', HR, 3, {
      versionId: '018f0000-0000-7000-8000-0000000000a9',
      validFrom: '2099-01-01',
      version: 6,
    });
    let removed: { path: string; version: string | null } | undefined;
    const it2 = { ...tree[3], version: 6 };
    server.use(
      http.get(`${API}/core/org-units`, () => HttpResponse.json([...tree.slice(0, 3), it2])),
      http.get(`${API}/core/org-units/${IT}/versions`, () => HttpResponse.json([{ ...it2, validTo: '2099-01-01' }, plannedMove])),
      http.post(`${API}/core/org-units/${IT}/close`, () => problem(409, 'future-versions-exist', 'IT has changes planned')),
      http.delete(`${API}/core/org-units/${IT}/versions/:versionId`, ({ request }) => {
        const url = new URL(request.url);
        removed = { path: url.pathname, version: url.searchParams.get('version') };
        return HttpResponse.json(it2);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OrgUnitsPage />, { path: '/core/org-units' });

    await user.click(await screen.findByText('IT – Information technology'));
    await user.click(await screen.findByRole('button', { name: /Close unit/ }));
    const modal = await screen.findByRole('dialog');
    await user.click(within(modal).getByRole('button', { name: /Close unit/ }));
    expect(await within(modal).findByText('Changes are planned on or after this date; remove the planned versions first.')).toBeInTheDocument();
    await user.click(await within(modal).findByRole('button', { name: 'Remove planned version' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(removed).toEqual({ path: `/api/v1/core/org-units/${IT}/versions/018f0000-0000-7000-8000-0000000000a9`, version: '6' }),
    );
  }, 20_000);

  it('picks a manager through the org-unit lookup', async () => {
    let searched: string | null = null;
    server.use(
      http.get(`${API}/core/org-units`, () => HttpResponse.json(tree)),
      http.get(`${API}/core/org-unit-types`, () => HttpResponse.json(types)),
      http.get(`${API}/core/legal-entities`, () => HttpResponse.json([legalEntity])),
      http.get(`${API}/core/locations`, () => HttpResponse.json([location])),
      http.get(`${API}/core/org-units/lookups/cost-centres`, () =>
        HttpResponse.json([{ id: '018f0000-0000-7000-8000-0000000000aa', code: 'CC-HR', name: 'Nhân sự' }]),
      ),
      http.get(`${API}/core/org-units/lookups/managers`, ({ request }) => {
        searched = new URL(request.url).searchParams.get('q');
        return HttpResponse.json([{ id: '018f0000-0000-7000-8000-0000000000ab', code: 'tran.b', name: 'Trần Thị B' }]);
      }),
      // Only org-unit permissions: no core.user.view / core.cost_centre.view needed for the pickers.
      http.get(`${API}/core/me`, () =>
        HttpResponse.json(meWith('core.org_unit.view', 'core.org_unit.create', 'core.org_unit.update', 'core.legal_entity.view', 'core.location.view')),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OrgUnitsPage />, { path: '/core/org-units' });

    await user.click(await screen.findByText('HR – Human resources'));
    await user.click(await screen.findByRole('button', { name: /Create child unit/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Manager/), 'tran');
    await waitFor(() => expect(searched).toBe('tran'));
    expect(await screen.findByText('Trần Thị B (tran.b)')).toBeInTheDocument();
  }, 20_000);

  it('hides the write actions for a read-only user', async () => {
    server.use(
      http.get(`${API}/core/org-units`, () => HttpResponse.json(tree)),
      http.get(`${API}/core/org-unit-types`, () => HttpResponse.json(types)),
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.org_unit.view'))),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<OrgUnitsPage />, { path: '/core/org-units' });

    await user.click(await screen.findByText('IT – Information technology'));
    expect(await screen.findByRole('button', { name: /Version history/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create root unit/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create child unit/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Close unit/ })).not.toBeInTheDocument();
  }, 15_000);
});
