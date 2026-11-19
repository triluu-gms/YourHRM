import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import {
  currentVersion,
  formatBytes,
  isDownloadable,
  isScanPending,
  publishedVersion,
  type DocumentTypeDefinition,
  type DocumentView,
  type DocumentVersionView,
  type TemplateDefinition,
  type TemplateView,
  type TemplateVersionView,
} from './api';
import { DocumentTemplatesPage, matchesTemplate } from './DocumentTemplatesPage';
import { DocumentsPage, matchesType } from './DocumentsPage';
import { DocumentsPanel } from './DocumentsPanel';

const LE_HQ = '018f0000-0000-7000-8000-0000000005a1';
const TPL_CONTRACT = '018f0000-0000-7000-8000-0000000005b1';
const TPL_DECISION = '018f0000-0000-7000-8000-0000000005b2';
const V1 = '018f0000-0000-7000-8000-0000000005c1';
const V2 = '018f0000-0000-7000-8000-0000000005c2';
const V_DRAFT = '018f0000-0000-7000-8000-0000000005c3';

const version = (over: Partial<TemplateVersionView> = {}): TemplateVersionView => ({
  id: V1,
  versionNo: 1,
  sourceFormat: 'HTML',
  outputFormat: 'PDF',
  status: 'PUBLISHED',
  validFrom: '2026-01-01',
  validTo: null,
  mergeFields: ['employee.full_name', 'contract.no'],
  ...over,
});

const definitions: TemplateDefinition[] = [
  {
    code: 'HR_LABOUR_CONTRACT',
    ownerModule: 'hr',
    purpose: 'LABOUR_CONTRACT',
    nameVi: 'Hợp đồng lao động',
    nameEn: 'Labour contract',
    documentTypeCode: 'HR_LABOUR_CONTRACT',
    mergeFields: [
      {
        key: 'employee.full_name',
        labelVi: 'Họ và tên',
        labelEn: 'Full name',
        sample: 'Nguyễn Thị Ánh',
        image: false,
      },
      {
        key: 'contract.no',
        labelVi: 'Số hợp đồng',
        labelEn: 'Contract number',
        sample: '001/2026/HĐLĐ-HQ',
        image: false,
      },
    ],
  },
  {
    code: 'HR_DECISION',
    ownerModule: 'hr',
    purpose: 'DECISION',
    nameVi: 'Quyết định nhân sự',
    nameEn: 'HR decision',
    documentTypeCode: 'HR_DECISION',
    mergeFields: [
      {
        key: 'decision.no',
        labelVi: 'Số quyết định',
        labelEn: 'Decision number',
        sample: 'QD-001',
        image: false,
      },
    ],
  },
];

const templates: TemplateView[] = [
  {
    id: TPL_CONTRACT,
    code: 'HR_LABOUR_CONTRACT',
    ownerModule: 'hr',
    purpose: 'LABOUR_CONTRACT',
    nameVi: 'Hợp đồng lao động',
    nameEn: 'Labour contract',
    legalEntityId: null,
    numberingSequenceCode: null,
    status: 'ACTIVE',
    versions: [version(), version({ id: V_DRAFT, versionNo: 2, status: 'DRAFT', validFrom: '2027-01-01' })],
  },
  {
    id: TPL_DECISION,
    code: 'HR_DECISION',
    ownerModule: 'hr',
    purpose: 'DECISION',
    nameVi: 'Quyết định nhân sự',
    nameEn: 'HR decision',
    legalEntityId: LE_HQ,
    numberingSequenceCode: 'DECISION_NO',
    status: 'ACTIVE',
    versions: [],
  },
];

const handlers = (over: Parameters<typeof server.use> = []) => [
  http.get(`${API}/core/document-templates`, () => HttpResponse.json(templates)),
  http.get(`${API}/core/document-template-definitions`, () => HttpResponse.json(definitions)),
  http.get(`${API}/core/legal-entities`, () =>
    HttpResponse.json([
      {
        id: LE_HQ,
        code: 'HQ',
        status: 'ACTIVE',
        version: 1,
        current: { shortName: 'YourHRM HQ', legalNameVi: 'Công ty TNHH YourHRM' },
      },
    ]),
  ),
  http.get(`${API}/core/sequences`, () =>
    HttpResponse.json([
      { code: 'DECISION_NO', name: 'Số quyết định', scopeLevel: 'LEGAL_ENTITY', status: 'ACTIVE' },
    ]),
  ),
  ...over,
];

// ---------------------------------------------------------------------------------------------- pure helpers

describe('matchesTemplate', () => {
  it('matches the code and both names, case-insensitively', () => {
    const [contract] = templates;
    expect(matchesTemplate(contract!, '')).toBe(true);
    expect(matchesTemplate(contract!, 'labour_contract')).toBe(true);
    expect(matchesTemplate(contract!, 'hợp đồng')).toBe(true);
    expect(matchesTemplate(contract!, 'Labour')).toBe(true);
    expect(matchesTemplate(contract!, 'payslip')).toBe(false);
  });
});

describe('publishedVersion (mirrors the valid_during lookup of DocumentTemplateService.generate)', () => {
  const closed = version({ validFrom: '2026-01-01', validTo: '2026-07-01' });
  const next = version({ id: V2, versionNo: 2, validFrom: '2026-07-01' });
  const t = {
    ...templates[0]!,
    versions: [
      closed,
      next,
      version({ id: V_DRAFT, versionNo: 3, status: 'DRAFT', validFrom: '2026-01-01' }),
    ],
  };

  it('picks the version whose range covers the date, end exclusive', () => {
    expect(publishedVersion(t, '2026-06-30')?.id).toBe(V1);
    expect(publishedVersion(t, '2026-07-01')?.id).toBe(V2);
    expect(publishedVersion(t, '2027-01-01')?.id).toBe(V2);
  });

  it('ignores drafts and dates before the first version', () => {
    expect(publishedVersion(t, '2025-12-31')).toBeUndefined();
    expect(
      publishedVersion({ ...templates[0]!, versions: [version({ status: 'DRAFT' })] }, '2026-06-01'),
    ).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------------------------- page

describe('DocumentTemplatesPage', () => {
  it('lists the templates with their scope and the version in force, and warns when none is published', async () => {
    server.use(...handlers());
    await changeLanguage('en');
    renderPage(<DocumentTemplatesPage />, { path: '/core/document-templates' });

    const contractRow = (await screen.findByText('HR_LABOUR_CONTRACT')).closest('tr')!;
    expect(within(contractRow).getByText('Labour contract')).toBeInTheDocument();
    expect(within(contractRow).getByText('All legal entities')).toBeInTheDocument();
    expect(within(contractRow).getByText('No number series')).toBeInTheDocument();
    expect(within(contractRow).getByText('v1 since 01/01/2026')).toBeInTheDocument();

    const decisionRow = screen.getByText('HR_DECISION').closest('tr')!;
    expect(within(decisionRow).getByText('YourHRM HQ')).toBeInTheDocument();
    expect(within(decisionRow).getByText('DECISION_NO')).toBeInTheDocument();
    expect(within(decisionRow).getByText('Not published')).toBeInTheDocument();
  }, 20_000);

  it('opens a template and publishes its draft version', async () => {
    const published: string[] = [];
    server.use(
      ...handlers([
        http.post(`${API}/core/document-templates/:id/versions/:versionId/publish`, ({ params }) => {
          published.push(params.versionId as string);
          return HttpResponse.json(templates[0]);
        }),
      ]),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DocumentTemplatesPage />, { path: '/core/document-templates' });

    await user.click(await screen.findByRole('button', { name: 'Open template HR_LABOUR_CONTRACT' }));

    // the definition's merge fields and Core's common ones are both offered to the template author
    expect(await screen.findByText('Placeholders you can use')).toBeInTheDocument();
    expect(screen.getByText('{{employee.full_name}}')).toBeInTheDocument();
    expect(screen.getByText('{{le.tax_code}}')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Publish version 2' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(published).toEqual([V_DRAFT]));
  }, 20_000);

  it('names the offending placeholders when an upload is refused', async () => {
    server.use(
      ...handlers([
        http.post(`${API}/core/document-templates/:id/versions`, () =>
          problem(422, 'unknown-merge-fields', 'The template uses fields that are not registered', {
            fields: ['employee.salary', 'nope'],
          }),
        ),
      ]),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DocumentTemplatesPage />, { path: '/core/document-templates' });

    await user.click(await screen.findByRole('button', { name: 'Open template HR_LABOUR_CONTRACT' }));
    await user.click(await screen.findByRole('button', { name: 'Upload version' }));

    const file = new File(['<html>{{nope}}</html>'], 'contract.html', { type: 'text/html' });
    await user.upload(screen.getByLabelText('Template file', { selector: 'input[type="file"]' }), file);
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(
      await screen.findByText('These placeholders are not merge fields of this template:'),
    ).toBeInTheDocument();
    expect(screen.getByText('employee.salary')).toBeInTheDocument();
    expect(screen.getByText('nope')).toBeInTheDocument();
  }, 20_000);

  it('hides creating, uploading and publishing from a user who may only view templates', async () => {
    server.use(
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.document_template.view'))),
      ...handlers(),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DocumentTemplatesPage />, { path: '/core/document-templates' });

    expect(await screen.findByText('HR_LABOUR_CONTRACT')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New template' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Open template HR_LABOUR_CONTRACT' }));
    expect(await screen.findByText('Versions')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Upload version' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Publish version 2' })).not.toBeInTheDocument();
    // viewing a version is still allowed
    expect(screen.getByRole('button', { name: 'Preview version 1' })).toBeInTheDocument();
  }, 20_000);
});

// ---------------------------------------------------------------------------------- document service: fixtures

const CONTRACT_ID = '018f0000-0000-7000-8000-0000000006a1';
const DOC_CLEAN = '018f0000-0000-7000-8000-0000000006b1';
const DOC_PENDING = '018f0000-0000-7000-8000-0000000006b2';

const documentTypes: DocumentTypeDefinition[] = [
  {
    code: 'HR_LABOUR_CONTRACT',
    ownerModule: 'hr',
    ownerEntityType: 'hr.contract',
    nameVi: 'Hợp đồng lao động',
    nameEn: 'Labour contract',
    defaultConfidentiality: 'CONFIDENTIAL',
    retentionClass: 'EMPLOYEE_FILE',
    viewPermission: 'hr.contract.view',
    managePermission: 'hr.contract.update',
    mediaTypes: [],
    maxSizeBytes: null,
  },
  {
    code: 'LE_SEAL',
    ownerModule: 'core',
    ownerEntityType: 'core.legal_entity',
    nameVi: 'Con dấu',
    nameEn: 'Seal',
    defaultConfidentiality: 'INTERNAL',
    retentionClass: 'COMPANY',
    viewPermission: 'core.legal_entity.view',
    managePermission: 'core.legal_entity.update',
    mediaTypes: ['image/png'],
    maxSizeBytes: 1048576,
  },
];

const docVersion = (over: Partial<DocumentVersionView> = {}): DocumentVersionView => ({
  versionNo: 1,
  fileName: 'hd-001.pdf',
  mediaType: 'application/pdf',
  sizeBytes: 204800,
  sha256: 'abc',
  source: 'UPLOAD',
  scanStatus: 'CLEAN',
  createdAt: '2026-11-10T08:00:00Z',
  uploadedBy: null,
  ...over,
});

const documents: DocumentView[] = [
  {
    id: DOC_CLEAN,
    documentTypeCode: 'HR_LABOUR_CONTRACT',
    ownerModule: 'hr',
    ownerEntityType: 'hr.contract',
    ownerEntityId: CONTRACT_ID,
    legalEntityId: null,
    title: 'HĐLĐ 001/2026',
    confidentiality: 'CONFIDENTIAL',
    retentionClass: 'EMPLOYEE_FILE',
    status: 'ACTIVE',
    currentVersionNo: 2,
    versions: [docVersion(), docVersion({ versionNo: 2, fileName: 'hd-001-v2.pdf' })],
  },
  {
    id: DOC_PENDING,
    documentTypeCode: 'HR_LABOUR_CONTRACT',
    ownerModule: 'hr',
    ownerEntityType: 'hr.contract',
    ownerEntityId: CONTRACT_ID,
    legalEntityId: null,
    title: 'Phụ lục đang quét',
    confidentiality: 'CONFIDENTIAL',
    retentionClass: 'EMPLOYEE_FILE',
    status: 'PENDING_SCAN',
    currentVersionNo: 0,
    versions: [docVersion({ scanStatus: 'PENDING', fileName: 'pl-001.pdf' })],
  },
];

const documentHandlers = () => [
  http.get(`${API}/core/document-types`, () => HttpResponse.json(documentTypes)),
  http.get(`${API}/core/documents`, () => HttpResponse.json(documents)),
];

// ---------------------------------------------------------------------------------- document service: helpers

describe('document helpers (mirror DocumentService)', () => {
  it('allows downloading only a clean or platform-generated version', () => {
    expect(isDownloadable(docVersion({ scanStatus: 'CLEAN' }))).toBe(true);
    expect(isDownloadable(docVersion({ scanStatus: 'SKIPPED' }))).toBe(true);
    expect(isDownloadable(docVersion({ scanStatus: 'PENDING' }))).toBe(false);
    expect(isDownloadable(docVersion({ scanStatus: 'INFECTED' }))).toBe(false);
    expect(isDownloadable(docVersion({ scanStatus: 'ERROR' }))).toBe(false);
  });

  it('shows the current version, falling back to the newest while the first scan runs', () => {
    expect(currentVersion(documents[0]!)?.fileName).toBe('hd-001-v2.pdf');
    expect(currentVersion(documents[1]!)?.fileName).toBe('pl-001.pdf');
  });

  it('knows when a scan may still land', () => {
    expect(isScanPending(documents)).toBe(true);
    expect(isScanPending([documents[0]!])).toBe(false);
  });

  it('formats sizes the way an uploader reads them', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(204800)).toBe('200 KB');
    expect(formatBytes(20 * 1024 * 1024)).toBe('20.0 MB');
    expect(formatBytes(null)).toBe('');
  });
});

describe('matchesType', () => {
  it('matches the code, both names and the record kind', () => {
    const [contract] = documentTypes;
    expect(matchesType(contract!, '')).toBe(true);
    expect(matchesType(contract!, 'hr.contract')).toBe(true);
    expect(matchesType(contract!, 'hợp đồng')).toBe(true);
    expect(matchesType(contract!, 'Labour')).toBe(true);
    expect(matchesType(contract!, 'payslip')).toBe(false);
  });
});

// ---------------------------------------------------------------------------------- DocumentsPanel

describe('DocumentsPanel', () => {
  it('lists the files of a record and keeps a file that is still being scanned out of reach', async () => {
    server.use(...documentHandlers());
    await changeLanguage('en');
    renderPage(<DocumentsPanel ownerEntityType="hr.contract" ownerEntityId={CONTRACT_ID} />, {
      path: '/core/documents',
    });

    const cleanRow = (await screen.findByText('HĐLĐ 001/2026')).closest('tr')!;
    expect(within(cleanRow).getByText('Confidential')).toBeInTheDocument();
    expect(within(cleanRow).getByText('hd-001-v2.pdf')).toBeInTheDocument();
    expect(within(cleanRow).getByText(/200 KB/)).toBeInTheDocument();
    expect(within(cleanRow).getByRole('button', { name: 'Open HĐLĐ 001/2026' })).toBeEnabled();

    const pendingRow = screen.getByText('Phụ lục đang quét').closest('tr')!;
    expect(within(pendingRow).getAllByText('Scanning').length).toBeGreaterThan(0);
    expect(within(pendingRow).getByRole('button', { name: 'Open Phụ lục đang quét' })).toBeDisabled();
  }, 20_000);

  it('opens a chosen version through a short-lived signed URL', async () => {
    const asked: { id: string; versionNo: string | null }[] = [];
    server.use(
      ...documentHandlers(),
      http.post(`${API}/core/documents/:id/download-link`, ({ params, request }) => {
        asked.push({
          id: params.id as string,
          versionNo: new URL(request.url).searchParams.get('versionNo'),
        });
        return HttpResponse.json({
          url: '/api/v1/dev/storage?key=x',
          expiresAt: '2026-11-10T08:15:00Z',
          fileName: 'hd-001.pdf',
        });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DocumentsPanel ownerEntityType="hr.contract" ownerEntityId={CONTRACT_ID} />, {
      path: '/core/documents',
    });

    // the row expands to the version history, where an older version can be opened on its own
    const cleanRow = (await screen.findByText('HĐLĐ 001/2026')).closest('tr')!;
    await user.click(within(cleanRow).getByRole('button', { name: 'Expand row' }));
    await user.click(await screen.findByRole('button', { name: 'Open version 1' }));

    await waitFor(() => expect(asked).toEqual([{ id: DOC_CLEAN, versionNo: '1' }]));
  }, 20_000);

  it('offers no upload to a user who may only read the record', async () => {
    server.use(...documentHandlers());
    await changeLanguage('en');
    renderPage(<DocumentsPanel ownerEntityType="hr.contract" ownerEntityId={CONTRACT_ID} />, {
      path: '/core/documents',
    });

    expect(await screen.findByText('HĐLĐ 001/2026')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Attach a file' })).not.toBeInTheDocument();
  }, 20_000);
});

// ---------------------------------------------------------------------------------- DocumentsPage

describe('DocumentsPage', () => {
  it('lists the registered document types with the permissions and limits that apply to them', async () => {
    server.use(...documentHandlers());
    await changeLanguage('en');
    renderPage(<DocumentsPage />, { path: '/core/documents' });

    const contractRow = (await screen.findByText('HR_LABOUR_CONTRACT')).closest('tr')!;
    expect(within(contractRow).getByText('hr.contract')).toBeInTheDocument();
    expect(within(contractRow).getByText('Confidential')).toBeInTheDocument();
    expect(within(contractRow).getByText('EMPLOYEE_FILE')).toBeInTheDocument();
    expect(within(contractRow).getByText(/hr\.contract\.view/)).toBeInTheDocument();
    // no media types of its own: the company-wide list applies
    expect(within(contractRow).getByText('Company default')).toBeInTheDocument();

    const sealRow = screen.getByText('LE_SEAL').closest('tr')!;
    expect(within(sealRow).getByText('image/png')).toBeInTheDocument();
    expect(within(sealRow).getByText('1.0 MB')).toBeInTheDocument();
  }, 20_000);

  it('filters the registry by module', async () => {
    server.use(...documentHandlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DocumentsPage />, { path: '/core/documents' });

    expect(await screen.findByText('HR_LABOUR_CONTRACT')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Module' }));
    await user.click(await screen.findByTitle('core'));
    await waitFor(() => expect(screen.queryByText('HR_LABOUR_CONTRACT')).not.toBeInTheDocument());
    expect(screen.getByText('LE_SEAL')).toBeInTheDocument();
  }, 20_000);

  it('asks for a record before showing files, and refuses something that is not an id', async () => {
    server.use(...documentHandlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DocumentsPage />, { path: '/core/documents' });

    await user.click(await screen.findByRole('tab', { name: 'Files of a record' }));
    expect(
      await screen.findByText('Pick a kind of record and paste its id to see its files.'),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'Kind of record' }));
    await user.click(await screen.findByTitle('hr.contract'));
    await user.type(screen.getByRole('textbox', { name: 'Record id' }), 'not-an-id');
    expect(screen.getByText('Pick a kind of record and paste its id to see its files.')).toBeInTheDocument();

    await user.clear(screen.getByRole('textbox', { name: 'Record id' }));
    await user.paste(CONTRACT_ID);
    expect(await screen.findByText('HĐLĐ 001/2026')).toBeInTheDocument();
  }, 30_000);
});
