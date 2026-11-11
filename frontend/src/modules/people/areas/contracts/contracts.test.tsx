import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { EmployeeProfile } from '../../common';
import type { ContractView, DecisionView, ExpiringContract } from './api';
import { ContractsTab, DecisionsTab } from './tabs';
import { ContractDetailPage } from './ContractDetailPage';
import { DecisionDetailPage } from './DecisionDetailPage';
import { ExpiringContractsPage } from './ExpiryListPages';

const EMP = '018f0000-0000-7000-8000-0000000000e1';
const PERSON = '018f0000-0000-7000-8000-0000000000e2';
const C1 = '018f0000-0000-7000-8000-0000000000c1';
const D1 = '018f0000-0000-7000-8000-0000000000d1';
const SEP = '018f0000-0000-7000-8000-0000000000f1';

const profile: EmployeeProfile = {
  employment: { employmentId: EMP, employeeNo: 'NV0001', status: 'ACTIVE', hireDate: '2025-03-01' },
  person: { id: PERSON, fullName: 'Nguyễn Văn An', ethnicityRecorded: false, religionRecorded: false, anonymised: false, version: 1 },
  otherEmployments: [],
  primaryAssignment: null,
  tabs: {},
  asOf: '2026-11-11',
};
const props = { employmentId: EMP, personId: PERSON, profile };

const contract: ContractView = {
  id: C1,
  employmentId: EMP,
  employeeNo: 'NV0001',
  contractNo: 'HD-001',
  contractTypeCode: 'DEFINITE',
  definiteSeq: 1,
  startDate: '2025-03-01',
  endDate: '2026-02-28',
  signDate: '2025-03-01',
  status: 'ACTIVE',
  version: 2,
};

const decision: DecisionView = {
  id: D1,
  decisionNo: 'QD-007',
  decisionTypeCode: 'TRANSFER',
  employmentId: EMP,
  effectiveDate: '2026-01-01',
  status: 'APPROVED',
  version: 1,
};

const lookupHandlers = [
  ...['legal-entities', 'org-units', 'job-titles', 'locations', 'grade-structures'].map((p) =>
    http.get(`${API}/core/${p}`, () => HttpResponse.json([])),
  ),
];

describe('ContractsTab', () => {
  it('shows the contract in force, the history and suspensions', async () => {
    server.use(
      ...lookupHandlers,
      http.get(`${API}/people/employments/${EMP}/contracts/current`, () => HttpResponse.json(contract)),
      http.get(`${API}/people/employments/${EMP}/contracts`, () => HttpResponse.json([contract])),
      http.get(`${API}/people/employments/${EMP}/contract-suspensions`, () =>
        HttpResponse.json([
          { id: 's1', contractId: C1, employmentId: EMP, contractNo: 'HD-001', reasonCode: 'MILITARY_SERVICE', validFrom: '2025-06-01', status: 'ENDED', returnDate: '2025-09-01', version: 1 },
        ]),
      ),
    );
    await changeLanguage('en');
    renderPage(<ContractsTab {...props} />);
    // Current card and history table load from separate queries: wait for both, not just the first.
    await waitFor(() => expect(screen.getAllByText('HD-001').length).toBeGreaterThanOrEqual(2));
    expect(screen.getAllByText('Definite-term contract').length).toBeGreaterThan(0);
    expect(await screen.findByText('Military service')).toBeInTheDocument();
  });

  it('says so when no contract is in force (404)', async () => {
    server.use(
      ...lookupHandlers,
      http.get(`${API}/people/employments/${EMP}/contracts/current`, () => problem(404, 'not-found')),
      http.get(`${API}/people/employments/${EMP}/contracts`, () => HttpResponse.json([])),
      http.get(`${API}/people/employments/${EMP}/contract-suspensions`, () => HttpResponse.json([])),
    );
    await changeLanguage('en');
    renderPage(<ContractsTab {...props} />);
    expect(await screen.findByText('No contract in force on this date.')).toBeInTheDocument();
  });
});

describe('ContractDetailPage', () => {
  function handlers(c: ContractView) {
    return [
      ...lookupHandlers,
      http.get(`${API}/people/contracts/${C1}`, () => HttpResponse.json(c)),
      http.get(`${API}/people/contracts/${C1}/appendices`, () => HttpResponse.json([])),
      http.get(`${API}/people/employments/${EMP}/contract-suspensions`, () => HttpResponse.json([])),
    ];
  }

  it('cancels a draft with its version and shows translated problems', async () => {
    let body: unknown;
    server.use(
      ...handlers({ ...contract, status: 'DRAFT', contractNo: null }),
      http.post(`${API}/people/contracts/${C1}/cancel`, async ({ request }) => {
        body = await request.json();
        return problem(409, 'invalid-status-transition');
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ContractDetailPage contractId={C1} />);
    expect(await screen.findByText('Contract (draft)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record signature' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel contract' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(body).toEqual({ version: 2 }));
    expect(await screen.findByText('This action is not possible in the current status.')).toBeInTheDocument();
  });

  it('records a probation result', async () => {
    let body: { result?: string; nextContract?: unknown } | undefined;
    server.use(
      ...handlers({ ...contract, contractTypeCode: 'INDEFINITE', endDate: null, probationDays: 60, probationEndDate: '2026-12-31' }),
      http.post(`${API}/people/contracts/${C1}/probation-result`, async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json({ contract, decisionId: D1, decisionNo: 'QD-100' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ContractDetailPage contractId={C1} />);
    await user.click(await screen.findByRole('button', { name: 'Record probation result' }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByText('Passed'));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body?.result).toBe('PASS'));
    expect(body?.nextContract).toBeNull();
  });

  it('offers renew / convert / suspend on an active definite contract and no cancel', async () => {
    server.use(...handlers(contract));
    await changeLanguage('en');
    renderPage(<ContractDetailPage contractId={C1} />);
    expect(await screen.findByRole('button', { name: 'Renew' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Convert to indefinite' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Suspend contract' }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Cancel contract' })).not.toBeInTheDocument();
    expect(screen.getByText('Appendices')).toBeInTheDocument();
  });
});

describe('Decisions', () => {
  it('lists decisions and issues one', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get(`${API}/people/employments/${EMP}/decisions`, () => HttpResponse.json([decision])),
      http.post(`${API}/people/decisions`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...decision, id: 'd2', decisionNo: 'QD-008' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DecisionsTab {...props} />);
    expect(await screen.findByText('QD-007')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Issue decision/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('combobox', { name: 'Decision type' }));
    await user.click(await screen.findByTitle('Promotion'));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toMatchObject({ employmentId: EMP, decisionTypeCode: 'PROMOTION' }));
    expect(typeof body?.effectiveDate).toBe('string');
  });

  it('shows a decision with sign / cancel / reissue', async () => {
    server.use(...lookupHandlers, http.get(`${API}/people/decisions/${D1}`, () => HttpResponse.json(decision)));
    await changeLanguage('en');
    renderPage(<DecisionDetailPage decisionId={D1} />);
    expect(await screen.findByText('Decision QD-007')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record signature' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel decision' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reissue' })).toBeInTheDocument();
  });
});

describe('ExpiringContractsPage', () => {
  it('lists expiring contracts and opens a separation at expiry', async () => {
    const row: ExpiringContract = {
      contractId: C1,
      employmentId: EMP,
      employeeNo: 'NV0001',
      contractNo: 'HD-001',
      contractTypeCode: 'DEFINITE',
      definiteSeq: 2,
      endDate: '2026-12-01',
      status: 'ACTIVE',
      daysLeft: 20,
      nextMustBeIndefinite: true,
    };
    let withinDays: string | null = null;
    let posted = false;
    server.use(
      ...lookupHandlers,
      http.get(`${API}/people/contracts/expiring`, ({ request }) => {
        withinDays = new URL(request.url).searchParams.get('withinDays');
        return HttpResponse.json([row]);
      }),
      http.post(`${API}/people/contracts/${C1}/terminate-at-expiry`, () => {
        posted = true;
        return HttpResponse.json({ separation: { id: SEP, status: 'DRAFT', employmentId: EMP }, warnings: [] }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ExpiringContractsPage />);
    expect(await screen.findByText('HD-001')).toBeInTheDocument();
    expect(withinDays).toBe('60');
    expect(screen.getByText('Next contract must be indefinite')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Terminate at expiry' }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(posted).toBe(true));
  });
});
