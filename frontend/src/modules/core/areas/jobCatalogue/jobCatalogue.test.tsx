import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { CodeValue, Grade, GradeStructure, JobFamily, JobTitle } from './api';
import { JobTitlesPage } from './JobTitlesPage';
import { JobTitleDetailPage } from './JobTitleDetailPage';
import { GradeStructuresPage } from './GradeStructuresPage';
import { GradeStructureDetailPage } from './GradeStructureDetailPage';

const FAM_ENG = '018f0000-0000-7000-8000-0000000001a1';
const FAM_OPS = '018f0000-0000-7000-8000-0000000001a2';
const TITLE_SE = '018f0000-0000-7000-8000-0000000001b1';
const TITLE_WELDER = '018f0000-0000-7000-8000-0000000001b2';
const STRUCT = '018f0000-0000-7000-8000-0000000001c1';
const G1 = '018f0000-0000-7000-8000-0000000001d1';
const G2 = '018f0000-0000-7000-8000-0000000001d2';

const families: JobFamily[] = [
  { id: FAM_ENG, code: 'ENG', nameVi: 'Kỹ thuật', nameEn: 'Engineering', active: true },
  { id: FAM_OPS, code: 'OPS', nameVi: 'Vận hành', nameEn: 'Operations', active: true },
];

const titleSe: JobTitle = {
  id: TITLE_SE,
  code: 'SE',
  jobFamilyId: FAM_ENG,
  nameVi: 'Kỹ sư phần mềm',
  nameEn: 'Software engineer',
  description: 'Builds software',
  jdDocumentId: null,
  defaultGradeMinId: G1,
  defaultGradeMaxId: G2,
  occupationCode: '2512',
  hazardousCategory: null,
  hazardous: false,
  active: true,
  version: 3,
};

const titleWelder: JobTitle = {
  ...titleSe,
  id: TITLE_WELDER,
  code: 'WELDER',
  jobFamilyId: FAM_OPS,
  nameVi: 'Thợ hàn',
  nameEn: 'Welder',
  description: null,
  defaultGradeMinId: null,
  defaultGradeMaxId: null,
  occupationCode: '7212',
  hazardousCategory: 'IV',
  hazardous: true,
  version: 0,
};

const hazardValues: CodeValue[] = [
  { valueCode: 'IV', labelVi: 'Loại IV - Nặng nhọc, độc hại', labelEn: 'Category IV - heavy/hazardous', sortOrder: 1, active: true },
  { valueCode: 'V', labelVi: 'Loại V', labelEn: 'Category V - especially heavy/hazardous', sortOrder: 2, active: true },
];

const structure: GradeStructure = { id: STRUCT, code: 'STAFF', name: 'Staff grades' };

const grades: Grade[] = [
  { id: G1, gradeStructureId: STRUCT, code: 'G1', name: 'Junior', rankOrder: 1, active: true, validFrom: '2026-01-01', validTo: null },
  { id: G2, gradeStructureId: STRUCT, code: 'G2', name: 'Senior', rankOrder: 2, active: true, validFrom: '2026-01-01', validTo: null },
];

/** Catalogue reads every page of this area needs. */
const catalogueHandlers = () => [
  http.get(`${API}/core/job-families`, () => HttpResponse.json(families)),
  http.get(`${API}/core/master-data/code-lists/JOB_HAZARD_CATEGORY/values`, () => HttpResponse.json(hazardValues)),
  http.get(`${API}/core/grade-structures`, () => HttpResponse.json([structure])),
  http.get(`${API}/core/grade-structures/${STRUCT}/grades`, () => HttpResponse.json(grades)),
];

async function pickOption(title: string, user: ReturnType<typeof userEvent.setup>) {
  const option = await waitFor(() => {
    const el = document.querySelector(`.ant-select-item-option[title="${title}"]`);
    expect(el).not.toBeNull();
    return el as HTMLElement;
  });
  await user.click(option);
}

describe('JobTitlesPage', () => {
  it('lists job titles with family and hazardous category, and creates one', async () => {
    let created: unknown;
    server.use(
      ...catalogueHandlers(),
      http.get(`${API}/core/job-titles`, () => HttpResponse.json([titleSe, titleWelder])),
      http.post(`${API}/core/job-titles`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json({ ...titleWelder, id: '018f0000-0000-7000-8000-0000000001b9', code: 'PAINTER' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<JobTitlesPage />, { path: '/core/job-titles' });

    // Titles, families and the hazard code list are three queries: wait for all of them together.
    await waitFor(() => {
      expect(screen.getByText('WELDER')).toBeInTheDocument();
      expect(screen.getByText('OPS – Operations')).toBeInTheDocument();
      expect(screen.getByText('Category IV - heavy/hazardous')).toBeInTheDocument();
    });
    expect(screen.getByText('Software engineer')).toBeInTheDocument();
    expect(screen.getByText('Not hazardous')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Create job title/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Code/), 'PAINTER');
    await user.type(within(drawer).getByLabelText(/Name \(VI\)/), 'Thợ sơn');
    await user.click(within(drawer).getByLabelText(/Heavy \/ hazardous category/));
    await pickOption('Category V - especially heavy/hazardous', user);
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(created).toBeDefined());
    expect(created).toEqual({
      code: 'PAINTER',
      attributes: {
        jobFamilyId: null,
        nameVi: 'Thợ sơn',
        nameEn: null,
        description: null,
        jdDocumentId: null,
        defaultGradeMinId: null,
        defaultGradeMaxId: null,
        occupationCode: null,
        hazardousCategory: 'V',
        active: true,
      },
    });
  }, 20_000);

  it('shows the translated problem when the code is taken', async () => {
    server.use(
      ...catalogueHandlers(),
      http.get(`${API}/core/job-titles`, () => HttpResponse.json([])),
      http.post(`${API}/core/job-titles`, () => problem(409, 'job-title-code-taken', 'A job title with code SE already exists')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<JobTitlesPage />, { path: '/core/job-titles' });

    await user.click(await screen.findByRole('button', { name: /Create job title/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Code/), 'SE');
    await user.type(within(drawer).getByLabelText(/Name \(VI\)/), 'Kỹ sư');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    expect(await within(drawer).findByText('A job title with this code already exists.')).toBeInTheDocument();
  }, 20_000);

  it('creates and edits a job family on the families tab', async () => {
    let created: unknown;
    let updated: { url: string; body: unknown } | undefined;
    server.use(
      ...catalogueHandlers(),
      http.get(`${API}/core/job-titles`, () => HttpResponse.json([])),
      http.post(`${API}/core/job-families`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json({ id: '018f0000-0000-7000-8000-0000000001a9', code: 'FIN', nameVi: 'Tài chính', nameEn: null, active: true }, { status: 201 });
      }),
      http.put(`${API}/core/job-families/:id`, async ({ request }) => {
        updated = { url: new URL(request.url).pathname, body: await request.json() };
        return HttpResponse.json({ ...families[1], active: false });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<JobTitlesPage />, { path: '/core/job-titles' });

    await user.click(await screen.findByRole('tab', { name: 'Job families' }));
    expect(await screen.findByText('Operations')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Create job family/ }));
    let drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Code/), 'FIN');
    await user.type(within(drawer).getByLabelText(/Name \(VI\)/), 'Tài chính');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(created).toEqual({ code: 'FIN', nameVi: 'Tài chính', nameEn: null }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Edit OPS' }));
    drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByLabelText(/^Code/)).toBeDisabled();
    await user.click(within(drawer).getByRole('switch'));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updated).toBeDefined());
    expect(updated).toEqual({
      url: `/api/v1/core/job-families/${FAM_OPS}`,
      body: { nameVi: 'Vận hành', nameEn: 'Operations', active: false },
    });
  }, 20_000);
});

describe('JobTitleDetailPage', () => {
  it('shows the default grade range and updates with the version it read', async () => {
    let body: unknown;
    server.use(
      ...catalogueHandlers(),
      http.get(`${API}/core/job-titles/${TITLE_SE}`, () => HttpResponse.json(titleSe)),
      http.put(`${API}/core/job-titles/${TITLE_SE}`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...titleSe, nameEn: 'Senior software engineer', version: 4 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<JobTitleDetailPage titleId={TITLE_SE} />);

    // The title and its grade range come from separate queries (title, structures, grades).
    await waitFor(() => {
      expect(screen.getByText('Job title SE')).toBeInTheDocument();
      expect(screen.getByText(/G1 – Junior → G2 – Senior/)).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /Edit/ }));
    const drawer = await screen.findByRole('dialog');
    const nameEn = within(drawer).getByLabelText(/Name \(EN\)/);
    await user.clear(nameEn);
    await user.type(nameEn, 'Senior software engineer');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toBeDefined());
    expect(body).toEqual({
      version: 3,
      attributes: {
        jobFamilyId: FAM_ENG,
        nameVi: 'Kỹ sư phần mềm',
        nameEn: 'Senior software engineer',
        description: 'Builds software',
        jdDocumentId: null,
        defaultGradeMinId: G1,
        defaultGradeMaxId: G2,
        occupationCode: '2512',
        hazardousCategory: null,
        active: true,
      },
    });
  }, 20_000);

  it('maps attribute field errors onto the flat form fields', async () => {
    server.use(
      ...catalogueHandlers(),
      http.get(`${API}/core/job-titles/${TITLE_SE}`, () => HttpResponse.json(titleSe)),
      http.put(`${API}/core/job-titles/${TITLE_SE}`, () =>
        problem(400, 'validation-failed', 'Invalid data', {
          errors: [{ field: 'attributes.occupationCode', message: 'size must be between 0 and 32' }],
        }),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<JobTitleDetailPage titleId={TITLE_SE} />);

    await user.click(await screen.findByRole('button', { name: /Edit/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    const field = within(drawer).getByLabelText(/Occupation code/).closest('.ant-form-item') as HTMLElement;
    expect(await within(field).findByText('size must be between 0 and 32')).toBeInTheDocument();
  }, 20_000);
});

describe('GradeStructureDetailPage', () => {
  it('lists the grades of a structure in all periods and adds a grade', async () => {
    let allDates: string | null = null;
    let created: unknown;
    server.use(
      http.get(`${API}/core/grade-structures`, () => HttpResponse.json([structure])),
      http.get(`${API}/core/grade-structures/${STRUCT}/grades`, ({ request }) => {
        allDates = new URL(request.url).searchParams.get('allDates');
        return HttpResponse.json(grades);
      }),
      http.post(`${API}/core/grade-structures/${STRUCT}/grades`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json(
          { id: '018f0000-0000-7000-8000-0000000001d3', gradeStructureId: STRUCT, code: 'G3', name: 'Lead', rankOrder: 3, active: true, validFrom: '2026-11-11', validTo: null },
          { status: 201 },
        );
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<GradeStructureDetailPage structureId={STRUCT} />);

    await waitFor(() => {
      expect(screen.getByText('Grade structure STAFF')).toBeInTheDocument();
      expect(screen.getByText('Senior')).toBeInTheDocument();
    });
    expect(screen.getByText('Junior')).toBeInTheDocument();
    expect(allDates).toBe('true');

    await user.click(screen.getByRole('button', { name: /Add grade/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Code/), 'G3');
    await user.type(within(drawer).getByLabelText(/^Name/), 'Lead');
    await user.type(within(drawer).getByLabelText(/Rank order/), '3');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(created).toEqual({ code: 'G3', name: 'Lead', rankOrder: 3, validFrom: null, validTo: null }));
  }, 20_000);

  it('shows the translated problem when the rank is taken', async () => {
    server.use(
      http.get(`${API}/core/grade-structures`, () => HttpResponse.json([structure])),
      http.get(`${API}/core/grade-structures/${STRUCT}/grades`, () => HttpResponse.json(grades)),
      http.put(`${API}/core/grades/${G2}`, () => problem(409, 'grade-rank-taken', 'Rank 1 is already used by grade G1')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<GradeStructureDetailPage structureId={STRUCT} />);

    await user.click(await screen.findByRole('button', { name: 'Edit G2' }));
    const drawer = await screen.findByRole('dialog');
    const rank = within(drawer).getByLabelText(/Rank order/);
    await user.clear(rank);
    await user.type(rank, '1');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    expect(
      await within(drawer).findByText('This rank order is already used by another grade in an overlapping period.'),
    ).toBeInTheDocument();
  }, 20_000);
});

describe('read-only user (core.job_catalogue.view only)', () => {
  it('sees no write actions on job titles, families, structures and grades', async () => {
    server.use(
      ...catalogueHandlers(),
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.job_catalogue.view'))),
      http.get(`${API}/core/job-titles`, () => HttpResponse.json([titleSe])),
      http.get(`${API}/core/job-titles/${TITLE_SE}`, () => HttpResponse.json(titleSe)),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    const titles = renderPage(<JobTitlesPage />, { path: '/core/job-titles' });
    expect(await screen.findByText('SE')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create job title/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit SE' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Job families' }));
    expect(await screen.findByText('Operations')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create job family/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit OPS' })).not.toBeInTheDocument();
    titles.unmount();

    const detail = renderPage(<JobTitleDetailPage titleId={TITLE_SE} />);
    expect(await screen.findByText('Job title SE')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
    detail.unmount();

    const list = renderPage(<GradeStructuresPage />, { path: '/core/grade-structures' });
    expect(await screen.findByText('STAFF')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create grade structure/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Rename STAFF' })).not.toBeInTheDocument();
    list.unmount();

    renderPage(<GradeStructureDetailPage structureId={STRUCT} />);
    expect(await screen.findByText('Senior')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add grade/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Rename/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit G2' })).not.toBeInTheDocument();
  }, 30_000);
});
