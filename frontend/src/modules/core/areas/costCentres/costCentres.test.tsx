import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { CostCentre } from './api';
import { CostCentresPage } from './CostCentresPage';

const LE_HQ = '018f0000-0000-7000-8000-0000000006a1';
const LE_BRANCH = '018f0000-0000-7000-8000-0000000006a2';
const CC_SALES = '018f0000-0000-7000-8000-0000000006b1';
const CC_OLD = '018f0000-0000-7000-8000-0000000006b2';
const OWNER = '018f0000-0000-7000-8000-0000000006c1';
const OTHER_USER = '018f0000-0000-7000-8000-0000000006c2';

const legalEntities = [
  { id: LE_HQ, code: 'HQ', status: 'ACTIVE', version: 0, current: { legalNameVi: 'Công ty HQ', shortName: 'Head office' } },
  { id: LE_BRANCH, code: 'CN1', status: 'ACTIVE', version: 0, current: { legalNameVi: 'Chi nhánh 1', shortName: 'Branch 1' } },
];

const users = {
  items: [
    { id: OWNER, login: 'lan.nguyen', displayName: 'Nguyễn Thị Lan', email: null, status: 'ACTIVE', version: 0 },
    { id: OTHER_USER, login: 'binh.tran', displayName: 'Trần Văn Bình', email: null, status: 'ACTIVE', version: 0 },
  ],
  total: 2,
  page: 0,
  size: 20,
};

const sales: CostCentre = {
  id: CC_SALES,
  legalEntityId: LE_HQ,
  code: 'CC-SALES',
  name: 'Kinh doanh',
  glSegment: '6421',
  ownerUserId: OWNER,
  validFrom: '2026-01-01',
  validTo: null,
  version: 3,
};
const ended: CostCentre = {
  id: CC_OLD,
  legalEntityId: LE_BRANCH,
  code: 'CC-OLD',
  name: 'Dự án cũ',
  glSegment: null,
  ownerUserId: null,
  validFrom: '2024-01-01',
  validTo: '2026-01-01',
  version: 1,
};

/** Mirrors `CostCentreService.list`: `allDates` returns every period, otherwise only the ones valid on `asOf`. */
const listHandler = (seen: URLSearchParams[] = []) =>
  http.get(`${API}/core/cost-centres`, ({ request }) => {
    const params = new URL(request.url).searchParams;
    seen.push(params);
    const all = params.get('allDates') === 'true';
    const le = params.get('legalEntityId');
    const rows = [sales, ended].filter((c) => (all || !c.validTo) && (!le || c.legalEntityId === le));
    return HttpResponse.json(rows);
  });

const baseHandlers = (seen: URLSearchParams[] = []) => [
  listHandler(seen),
  http.get(`${API}/core/legal-entities`, () => HttpResponse.json(legalEntities)),
  http.get(`${API}/core/users`, () => HttpResponse.json(users)),
];

describe('CostCentresPage', () => {
  it('lists the cost centres valid today with legal entity and owner names, and adds past periods on request', async () => {
    const seen: URLSearchParams[] = [];
    server.use(...baseHandlers(seen));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<CostCentresPage />, { path: '/core/cost-centres' });

    await screen.findByText('CC-SALES');
    // the legal entity and the owner are resolved by their own queries, so wait for their names
    const entity = await screen.findByText('HQ – Head office');
    const row = entity.closest('tr')!;
    expect(within(row).getByText('CC-SALES')).toBeInTheDocument();
    expect(await within(row).findByText('Nguyễn Thị Lan (lan.nguyen)')).toBeInTheDocument();
    expect(within(row).getByText('6421')).toBeInTheDocument();
    expect(screen.queryByText('CC-OLD')).not.toBeInTheDocument();
    expect(seen[0]!.get('allDates')).toBeNull();
    expect(seen[0]!.get('asOf')).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    await user.click(screen.getByText(/All periods/));
    // the ended one shows its last valid day, the day before the exclusive end date
    const old = (await screen.findByText('CC-OLD')).closest('tr')!;
    expect(within(old).getByText('01/01/2024 – 31/12/2025')).toBeInTheDocument();
    await waitFor(() => expect(seen.at(-1)!.get('allDates')).toBe('true'));
  }, 30_000);

  it('filters by legal entity', async () => {
    const seen: URLSearchParams[] = [];
    server.use(...baseHandlers(seen));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<CostCentresPage />, { path: '/core/cost-centres' });

    await screen.findByText('CC-SALES');
    await user.click(screen.getByRole('combobox', { name: 'Select a legal entity' }));
    await user.click(await screen.findByTitle('CN1 – Branch 1'));

    await waitFor(() => expect(seen.at(-1)!.get('legalEntityId')).toBe(LE_BRANCH));
  }, 30_000);

  it('creates a cost centre with its legal entity, owner and dates', async () => {
    let created: unknown;
    server.use(
      ...baseHandlers(),
      http.post(`${API}/core/cost-centres`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json(sales, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<CostCentresPage />, { path: '/core/cost-centres' });

    await user.click(await screen.findByRole('button', { name: /New cost centre/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('combobox', { name: 'Select a legal entity' }));
    await user.click(await screen.findByTitle('HQ – Head office'));
    await user.type(within(drawer).getByLabelText('Code'), 'CC-IT');
    await user.type(within(drawer).getByLabelText('Name'), 'Công nghệ thông tin');
    await user.type(within(drawer).getByLabelText('GL segment'), '6422');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(created).toBeDefined());
    expect(created).toEqual({
      legalEntityId: LE_HQ,
      code: 'CC-IT',
      name: 'Công nghệ thông tin',
      glSegment: '6422',
      ownerUserId: null,
      validFrom: null,
      validTo: null,
    });
  }, 30_000);

  it('ends a cost centre, sending the version it read while code and legal entity stay fixed', async () => {
    let sent: unknown;
    let updatedId: string | undefined;
    server.use(
      ...baseHandlers(),
      http.put(`${API}/core/cost-centres/:id`, async ({ request, params }) => {
        updatedId = params.id as string;
        sent = await request.json();
        return HttpResponse.json({ ...sales, validTo: '2027-01-01', version: 4 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<CostCentresPage />, { path: '/core/cost-centres' });

    await user.click(await screen.findByRole('button', { name: 'Edit CC-SALES' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByLabelText('Code')).toBeDisabled();
    await user.type(within(drawer).getByLabelText('Valid to'), '01/01/2027');
    await user.keyboard('{Enter}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(sent).toBeDefined());
    expect(updatedId).toBe(CC_SALES);
    expect(sent).toEqual({
      name: 'Kinh doanh',
      glSegment: '6421',
      ownerUserId: OWNER,
      validTo: '2027-01-01',
      version: 3,
    });
  }, 30_000);

  it('refuses an end date that is not after the start date', async () => {
    server.use(...baseHandlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<CostCentresPage />, { path: '/core/cost-centres' });

    await user.click(await screen.findByRole('button', { name: 'Edit CC-SALES' }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Valid to'), '01/01/2025');
    await user.keyboard('{Enter}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    expect(await within(drawer).findByText('The end date must be after the start date.')).toBeInTheDocument();
  }, 30_000);

  it('shows why a cost centre still in use cannot be ended', async () => {
    server.use(
      ...baseHandlers(),
      http.put(`${API}/core/cost-centres/:id`, () =>
        problem(409, 'cost-centre-in-use', 'Org units still use cost centre CC-SALES', { orgUnits: ['SALES'] }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<CostCentresPage />, { path: '/core/cost-centres' });

    await user.click(await screen.findByRole('button', { name: 'Edit CC-SALES' }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Valid to'), '01/01/2027');
    await user.keyboard('{Enter}');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    expect(
      await within(drawer).findByText('Org units still use this cost centre after that date. Change their default first.'),
    ).toBeInTheDocument();
  }, 30_000);

  it('is read-only without the create and update permissions', async () => {
    server.use(
      ...baseHandlers(),
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.cost_centre.view', 'core.legal_entity.view'))),
    );
    await changeLanguage('en');
    renderPage(<CostCentresPage />, { path: '/core/cost-centres' });

    expect(await screen.findByText('CC-SALES')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New cost centre/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit CC-SALES' })).not.toBeInTheDocument();
    // without core.user.view the owner column falls back to the shortened id instead of a name
    expect(screen.getByText(OWNER.slice(0, 8))).toBeInTheDocument();
  }, 20_000);
});
