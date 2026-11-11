import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { InboxMessage, LabourBook, StatutoryReportView } from './api';
import { StatutoryReportsPage } from './StatutoryReportsPage';
import { StatutoryReportDetailPage } from './StatutoryReportDetailPage';
import { LabourBookPage } from './LabourBookPage';
import { IntegrationInboxPage } from './IntegrationInboxPage';

const LE = '018f0000-0000-7000-8000-0000000000f1';
const REPORT = '018f0000-0000-7000-8000-0000000000f2';
const EMP = '018f0000-0000-7000-8000-0000000000f3';

const lookups = [
  http.get(`${API}/core/legal-entities`, () =>
    HttpResponse.json([{ id: LE, code: 'LE01', status: 'ACTIVE', current: { shortName: 'Demo VN' } }]),
  ),
  ...['org-units', 'job-titles', 'locations', 'grade-structures'].map((p) =>
    http.get(`${API}/core/${p}`, () => HttpResponse.json([])),
  ),
];

const report: StatutoryReportView = {
  id: REPORT,
  legalEntityId: LE,
  reportType: 'LABOUR_CHANGE_6M',
  periodStart: '2026-01-01',
  periodEnd: '2026-06-30',
  dueDate: '2026-07-05',
  generatedDocumentId: '018f0000-0000-7000-8000-0000000000f4',
  generatedAt: '2026-07-01T02:00:00Z',
  rowCount: 12,
  status: 'GENERATED',
  version: 2,
  versions: [
    {
      versionNo: 1,
      fileName: 'bc-6t.xlsx',
      mediaType: 'application/xlsx',
      sizeBytes: 2048,
      createdAt: '2026-07-01T02:00:00Z',
    },
  ],
};

describe('StatutoryReportsPage', () => {
  it('lists the register of the default legal entity', async () => {
    let le: string | null = null;
    server.use(
      ...lookups,
      http.get(`${API}/people/statutory-reports`, ({ request }) => {
        le = new URL(request.url).searchParams.get('legalEntityId');
        return HttpResponse.json([report]);
      }),
    );
    await changeLanguage('en');
    renderPage(<StatutoryReportsPage />);
    await waitFor(() => {
      expect(screen.getByText('01/01/2026 – 30/06/2026')).toBeInTheDocument();
      expect(screen.getByText('Generated')).toBeInTheDocument();
    }, { timeout: 10_000 });
    expect(le).toBe(LE);
  });
});

describe('StatutoryReportDetailPage', () => {
  it('marks a generated report submitted with its version', async () => {
    let body: unknown;
    server.use(
      ...lookups,
      http.get(`${API}/people/statutory-reports/${REPORT}`, () => HttpResponse.json(report)),
      http.post(`${API}/people/statutory-reports/${REPORT}/submit`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...report, status: 'SUBMITTED', version: 3 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<StatutoryReportDetailPage reportId={REPORT} />);

    await user.click(await screen.findByRole('button', { name: /Mark as submitted/ }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Submission reference/), 'REF-9');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(body).toMatchObject({ version: 2, channel: 'PORTAL', submissionRef: 'REF-9' }),
    );
    expect((body as { submittedOn: string }).submittedOn).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('shows the translated problem when cancelling fails', async () => {
    server.use(
      ...lookups,
      http.get(`${API}/people/statutory-reports/${REPORT}`, () => HttpResponse.json(report)),
      http.post(`${API}/people/statutory-reports/${REPORT}/cancel`, () =>
        problem(422, 'invalid-status-transition', 'nope'),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<StatutoryReportDetailPage reportId={REPORT} />);
    await user.click(await screen.findByRole('button', { name: /Cancel report/ }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText(/not possible in the current status/)).toBeInTheDocument();
  });
});

describe('LabourBookPage', () => {
  it('shows masked entries with the masking notice', async () => {
    const book: LabourBook = {
      legalEntityId: LE,
      asOf: '2026-11-11',
      identityRevealed: false,
      salaryColumns: 1,
      entries: [
        {
          employmentId: EMP,
          employeeNo: 'NV0001',
          fullName: 'Nguyễn Văn An',
          nationalId: '••••1234',
          siParticipating: true,
        },
      ],
    };
    server.use(
      ...lookups,
      http.get(`${API}/people/reports/labour-book`, () => HttpResponse.json(book)),
    );
    await changeLanguage('en');
    renderPage(<LabourBookPage />);
    expect(await screen.findByText('Nguyễn Văn An')).toBeInTheDocument();
    expect(screen.getByText('••••1234')).toBeInTheDocument();
    expect(screen.getByText(/Identifiers are masked/)).toBeInTheDocument();
  });
});

describe('IntegrationInboxPage', () => {
  it('filters by status', async () => {
    const seen: (string | null)[] = [];
    const msg: InboxMessage = {
      consumer: 'people.core-user',
      eventId: '018f0000-0000-7000-8000-0000000000f5',
      eventType: 'core.OrgUnitChanged',
      status: 'FAILED',
      attempts: 5,
      lastError: 'boom',
      receivedAt: '2026-11-10T02:00:00Z',
    };
    server.use(
      http.get(`${API}/people/integration/inbox`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('status'));
        return HttpResponse.json([msg]);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<IntegrationInboxPage />);
    expect(await screen.findByText('core.OrgUnitChanged')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByTitle('Failed'));
    await waitFor(() => expect(seen).toContain('FAILED'));
  });
});
