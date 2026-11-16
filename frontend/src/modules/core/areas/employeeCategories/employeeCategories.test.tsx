import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { EmployeeCategory, EmployeeCategoryFlags } from './api';
import { EmployeeCategoriesPage } from './EmployeeCategoriesPage';

const PERMANENT = '018f0000-0000-7000-8000-0000000005a1';
const PROBATION = '018f0000-0000-7000-8000-0000000005a2';
const INTERN = '018f0000-0000-7000-8000-0000000005a3';

const flags = (over: Partial<EmployeeCategoryFlags> = {}): EmployeeCategoryFlags => ({
  probation: false,
  seasonal: false,
  intern: false,
  expat: false,
  collaborator: false,
  countsInHeadcount: true,
  ...over,
});

const categories: EmployeeCategory[] = [
  { id: PERMANENT, code: 'CHINH_THUC', nameVi: 'Chính thức', nameEn: 'Permanent', flags: flags(), sortOrder: 10, active: true, version: 0 },
  { id: PROBATION, code: 'THU_VIEC', nameVi: 'Thử việc', nameEn: 'Probation', flags: flags({ probation: true }), sortOrder: 20, active: true, version: 2 },
  { id: INTERN, code: 'THUC_TAP', nameVi: 'Thực tập', nameEn: 'Intern', flags: flags({ intern: true, countsInHeadcount: false }), sortOrder: 30, active: false, version: 1 },
];

/** The list handler honours `includeInactive` the way `EmployeeCategoryService.list` does. */
const listHandler = (seen: boolean[] = []) =>
  http.get(`${API}/core/employee-categories`, ({ request }) => {
    const all = new URL(request.url).searchParams.get('includeInactive') === 'true';
    seen.push(all);
    return HttpResponse.json(all ? categories : categories.filter((c) => c.active));
  });

describe('EmployeeCategoriesPage', () => {
  it('lists the active categories with their flags and adds the inactive ones on request', async () => {
    const seen: boolean[] = [];
    server.use(listHandler(seen));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmployeeCategoriesPage />, { path: '/core/employee-categories' });

    const probation = (await screen.findByText('THU_VIEC')).closest('tr')!;
    expect(within(probation).getByText('Thử việc')).toBeInTheDocument();
    // the English name and the "probation" flag tag read the same, so both cells carry the word
    expect(within(probation).getAllByText('Probation')).toHaveLength(2);
    expect(within(probation).getByLabelText('Counts in headcount')).toBeInTheDocument();
    expect(screen.queryByText('THUC_TAP')).not.toBeInTheDocument();

    await user.click(screen.getByText('Show inactive'));
    const intern = (await screen.findByText('THUC_TAP')).closest('tr')!;
    expect(within(intern).getByText('Inactive')).toBeInTheDocument();
    // an intern does not count in the headcount: no tick in that column
    expect(within(intern).queryByLabelText('Counts in headcount')).not.toBeInTheDocument();
    expect(seen).toEqual([false, true]);
  }, 20_000);

  it('creates a category with its code upper-cased and its flags', async () => {
    let created: unknown;
    server.use(
      listHandler(),
      http.post(`${API}/core/employee-categories`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json(categories[0], { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmployeeCategoriesPage />, { path: '/core/employee-categories' });

    await user.click(await screen.findByRole('button', { name: /New category/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Code'), 'thoi_vu');
    await user.type(within(drawer).getByLabelText('Name (VI)'), 'Thời vụ');
    await user.type(within(drawer).getByLabelText('Name (EN)'), 'Seasonal');
    await user.click(within(drawer).getByRole('checkbox', { name: 'Seasonal' }));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(created).toBeDefined());
    expect(created).toEqual({
      code: 'THOI_VU',
      nameVi: 'Thời vụ',
      nameEn: 'Seasonal',
      flags: { probation: false, seasonal: true, intern: false, expat: false, collaborator: false, countsInHeadcount: true },
      sortOrder: 0,
    });
  }, 30_000);

  it('refuses a code that does not match the backend pattern', async () => {
    server.use(listHandler());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmployeeCategoriesPage />, { path: '/core/employee-categories' });

    await user.click(await screen.findByRole('button', { name: /New category/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Code'), 'a');
    await user.type(within(drawer).getByLabelText('Name (VI)'), 'Một ký tự');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    expect(await within(drawer).findByText("Capital letters, digits and '_' only (2 to 32 characters).")).toBeInTheDocument();
  }, 30_000);

  it('renames and deactivates a category, sending the version it read', async () => {
    let sent: unknown;
    let updatedId: string | undefined;
    server.use(
      listHandler(),
      http.put(`${API}/core/employee-categories/:id`, async ({ request, params }) => {
        updatedId = params.id as string;
        sent = await request.json();
        return HttpResponse.json({ ...categories[1], nameEn: 'Trial period', active: false, version: 3 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmployeeCategoriesPage />, { path: '/core/employee-categories' });

    await user.click(await screen.findByRole('button', { name: 'Edit THU_VIEC' }));
    const drawer = await screen.findByRole('dialog');
    const nameEn = within(drawer).getByLabelText('Name (EN)');
    await user.clear(nameEn);
    await user.type(nameEn, 'Trial period');
    await user.click(within(drawer).getByRole('switch'));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(sent).toBeDefined());
    expect(updatedId).toBe(PROBATION);
    expect(sent).toEqual({
      nameVi: 'Thử việc',
      nameEn: 'Trial period',
      flags: { probation: true, seasonal: false, intern: false, expat: false, collaborator: false, countsInHeadcount: true },
      sortOrder: 20,
      active: false,
      version: 2,
    });
  }, 30_000);

  it('shows the problem of a duplicate code', async () => {
    server.use(
      listHandler(),
      http.post(`${API}/core/employee-categories`, () => problem(409, 'employee-category-code-taken')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<EmployeeCategoriesPage />, { path: '/core/employee-categories' });

    await user.click(await screen.findByRole('button', { name: /New category/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText('Code'), 'CHINH_THUC');
    await user.type(within(drawer).getByLabelText('Name (VI)'), 'Chính thức');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));

    expect(await within(drawer).findByText('An employee category with this code already exists.')).toBeInTheDocument();
  }, 30_000);

  it('is read-only without core.employee_category.update', async () => {
    server.use(
      listHandler(),
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.employee_category.view'))),
    );
    await changeLanguage('en');
    renderPage(<EmployeeCategoriesPage />, { path: '/core/employee-categories' });

    expect(await screen.findByText('CHINH_THUC')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New category/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit CHINH_THUC' })).not.toBeInTheDocument();
  }, 20_000);
});
