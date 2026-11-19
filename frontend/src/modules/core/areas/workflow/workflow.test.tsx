import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, server } from '@/test/server';
import { USER_ID } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { DefinitionView, InstanceDetail, ProcessTypeView, TaskItem } from './api';
import { TasksPage } from './TasksPage';
import { MyRequestsPage } from './MyRequestsPage';
import { InstanceDetailPage } from './InstanceDetailPage';
import { subjectName } from './common';
import { ConsolePage } from './ConsolePage';
import { DefinitionsPage, versionInForce } from './DefinitionsPage';
import { DefinitionDetailPage } from './DefinitionDetailPage';
import { parseConditions, parseSteps } from './PublishDefinitionDrawer';

const INSTANCE = '018f0000-0000-7000-8000-0000000004a1';
const TASK = '018f0000-0000-7000-8000-0000000004a2';
const REQUESTER = '018f0000-0000-7000-8000-0000000004a3';
const CALLBACK = '018f0000-0000-7000-8000-0000000004b1';
const DEFINITION = '018f0000-0000-7000-8000-0000000004c1';
const VERSION_1 = '018f0000-0000-7000-8000-0000000004c2';
const VERSION_2 = '018f0000-0000-7000-8000-0000000004c3';

const processTypes: ProcessTypeView[] = [
  {
    code: 'att.LEAVE_REQUEST',
    ownerModule: 'att',
    nameVi: 'Đề nghị nghỉ phép',
    nameEn: 'Leave request',
    conditionFields: ['leaveType'],
    allowedActions: ['RETURN', 'REQUEST_INFO', 'FORWARD'],
    allowsAutoApprove: false,
    requiresStepUp: false,
    active: true,
    hasVendorDefault: true,
  },
];

const task: TaskItem = {
  taskId: TASK,
  instanceId: INSTANCE,
  processTypeCode: 'att.LEAVE_REQUEST',
  processNameVi: 'Đề nghị nghỉ phép',
  processNameEn: 'Leave request',
  stepNo: 1,
  stepKey: 'MANAGER',
  stepNameVi: 'Quản lý trực tiếp',
  requesterUserId: REQUESTER,
  requesterName: 'Trần Thị B',
  submittedAt: '2026-11-12T03:00:00Z',
  assignedAt: '2026-11-12T03:00:00Z',
  instanceStatus: 'IN_PROGRESS',
  summary: { leaveType: 'ANNUAL', from: '2026-11-20', days: 2 },
};

const detail: InstanceDetail = {
  instanceId: INSTANCE,
  processTypeCode: 'att.LEAVE_REQUEST',
  ownerModule: 'att',
  subjectType: 'att.leave_request',
  subjectId: '018f0000-0000-7000-8000-0000000004d1',
  requesterUserId: REQUESTER,
  onBehalfOfUserId: null,
  legalEntityId: null,
  orgUnitId: null,
  status: 'IN_PROGRESS',
  outcome: null,
  currentStep: 1,
  submittedAt: '2026-11-12T03:00:00Z',
  completedAt: null,
  summary: { leaveType: 'ANNUAL', days: 2 },
  approverChain: [
    { stepNo: 1, stepKey: 'MANAGER', nameVi: 'Quản lý trực tiếp', nameEn: 'Line manager', approvers: [USER_ID], via: null, reason: null, allowReturn: true },
    { stepNo: 2, stepKey: 'HR', nameVi: 'Phòng nhân sự', nameEn: 'HR', approvers: [], via: 'SKIPPED', reason: 'no approver', allowReturn: false },
  ],
  tasks: [
    {
      taskId: TASK,
      stepNo: 1,
      stepKey: 'MANAGER',
      stepType: 'SINGLE',
      assigneeUserId: USER_ID,
      originalAssigneeUserId: USER_ID,
      assignedVia: 'RESOLVER',
      status: 'PENDING',
      outcome: null,
      actedByUserId: null,
      actedAt: null,
      comment: null,
    },
  ],
  trail: [
    {
      occurredAt: '2026-11-12T03:00:00Z',
      action: 'SUBMIT',
      actorType: 'USER',
      actorUserId: REQUESTER,
      actorName: 'Trần Thị B',
      onBehalfOfUserId: null,
      stepNo: null,
      fromStatus: null,
      toStatus: 'IN_PROGRESS',
      comment: 'Xin nghỉ 2 ngày',
      attachmentIds: [],
      channel: 'UI',
      clientIp: null,
      userAgent: null,
    },
  ],
  allowedActions: ['APPROVE', 'REJECT', 'RETURN', 'REQUEST_INFO', 'FORWARD', 'COMMENT'],
  myPendingTaskId: TASK,
  userNames: { [REQUESTER]: 'Trần Thị B', [USER_ID]: 'Nguyễn Quản Lý' },
};

const definition: DefinitionView = {
  id: DEFINITION,
  processTypeCode: 'att.LEAVE_REQUEST',
  code: 'LEAVE_DEFAULT',
  nameVi: 'Phê duyệt nghỉ phép',
  nameEn: 'Leave approval',
  matchPriority: 100,
  status: 'ACTIVE',
  versions: [
    {
      id: VERSION_1,
      versionNo: 1,
      status: 'PUBLISHED',
      validFrom: '2026-01-01',
      validTo: '2026-06-01',
      conditions: { legalEntityIds: [], orgUnitIds: [], locationIds: [], employeeCategoryIds: [], fields: {} },
      steps: [
        { stepKey: 'MANAGER', nameVi: 'Quản lý trực tiếp', nameEn: 'Line manager', resolver: { type: 'LINE_MANAGER', params: {} }, allowReturn: true },
      ],
      onResolverFailure: 'ESCALATE_UP',
    },
    {
      id: VERSION_2,
      versionNo: 2,
      status: 'PUBLISHED',
      validFrom: '2026-06-01',
      validTo: null,
      conditions: { legalEntityIds: [], orgUnitIds: [], locationIds: [], employeeCategoryIds: [], fields: { leaveType: ['ANNUAL'] } },
      steps: [
        { stepKey: 'MANAGER', nameVi: 'Quản lý trực tiếp', nameEn: 'Line manager', resolver: { type: 'LINE_MANAGER', params: {} }, allowReturn: true },
        { stepKey: 'HR', nameVi: 'Phòng nhân sự', nameEn: 'HR', resolver: { type: 'ROLE_IN_SCOPE', params: { role: 'HR_ADMIN' } }, allowReturn: false },
      ],
      onResolverFailure: 'LE_HR_ADMIN',
    },
  ],
};

const baseHandlers = () => [
  http.get(`${API}/core/process-types`, () => HttpResponse.json(processTypes)),
  http.get(`${API}/core/workflow/instances/${INSTANCE}`, () => HttpResponse.json(detail)),
];

/** Clicks an option in the OPEN select dropdown; earlier dropdowns stay in the DOM (and mid-close), so take the last. */
async function pickOption(title: string, user: ReturnType<typeof userEvent.setup>) {
  const option = await waitFor(() => {
    const els = document.querySelectorAll(
      `.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${title}"]`,
    );
    expect(els.length).toBeGreaterThan(0);
    return els[els.length - 1] as HTMLElement;
  });
  await user.click(option);
}

// ---------------------------------------------------------------------------------------------- pure helpers

const asT = ((key: string) => key) as unknown as Parameters<typeof parseSteps>[1];

describe('parseSteps / parseConditions (mirror the backend validation)', () => {
  it('accepts valid steps and rejects malformed ones', () => {
    const good = parseSteps(JSON.stringify(definition.versions[1]!.steps), asT);
    expect('steps' in good && good.steps).toHaveLength(2);
    expect(parseSteps('not json', asT)).toHaveProperty('error');
    expect(parseSteps('{}', asT)).toEqual({ error: 'publish.stepsNotArray' });
    expect(parseSteps('[]', asT)).toEqual({ error: 'publish.stepsRequired' });
    expect(parseSteps('[{"stepKey":"A","nameVi":"A"}]', asT)).toEqual({ error: 'publish.stepInvalid' });
  });

  it('treats empty conditions as none and rejects non-objects', () => {
    expect(parseConditions(undefined, asT)).toEqual({ conditions: null });
    expect(parseConditions('  ', asT)).toEqual({ conditions: null });
    expect(parseConditions('{"fields":{"leaveType":["ANNUAL"]}}', asT)).toEqual({
      conditions: { fields: { leaveType: ['ANNUAL'] } },
    });
    expect(parseConditions('[1]', asT)).toEqual({ error: 'publish.conditionsNotObject' });
    expect(parseConditions('{bad', asT)).toHaveProperty('error');
  });
});

describe('versionInForce', () => {
  it('picks the published version covering the date', () => {
    expect(versionInForce(definition, '2026-03-01')?.versionNo).toBe(1);
    expect(versionInForce(definition, '2026-06-01')?.versionNo).toBe(2);
    expect(versionInForce(definition, '2025-12-31')).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------------------------- tasks

describe('TasksPage', () => {
  it('lists tasks and approves one through the action drawer', async () => {
    let acted: unknown;
    server.use(
      ...baseHandlers(),
      http.get(`${API}/core/workflow/tasks`, () => HttpResponse.json([task])),
      http.post(`${API}/core/workflow/tasks/${TASK}/actions`, async ({ request }) => {
        acted = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<TasksPage />, { path: '/core/workflow/tasks' });

    const row = (await screen.findByText('Leave request')).closest('tr')!;
    expect(within(row).getByText('Trần Thị B')).toBeInTheDocument();
    expect(within(row).getByText(/leaveType/)).toBeInTheDocument();

    await user.click(within(row).getByRole('button', { name: 'Act' }));
    const drawer = await screen.findByRole('dialog');
    // actions come from the instance detail's allowedActions; the button-style radio hides its input: click the label
    expect(await within(drawer).findByRole('radio', { name: 'Return' })).toBeInTheDocument();
    await user.click(within(drawer).getByRole('radio', { name: 'Approve' }).closest('label')!);
    await user.click(within(drawer).getByRole('button', { name: 'Execute' }));

    await waitFor(() => expect(acted).toBeDefined());
    expect(acted).toEqual({ action: 'APPROVE', comment: null, returnTo: null, forwardTo: null });
    expect(await screen.findByText('Task processed')).toBeInTheDocument();
  }, 30_000);

  it('requires a comment to reject', async () => {
    server.use(...baseHandlers(), http.get(`${API}/core/workflow/tasks`, () => HttpResponse.json([task])));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<TasksPage />, { path: '/core/workflow/tasks' });

    await user.click(within((await screen.findByText('Leave request')).closest('tr')!).getByRole('button', { name: 'Act' }));
    const drawer = await screen.findByRole('dialog');
    await user.click((await within(drawer).findByRole('radio', { name: 'Reject' })).closest('label')!);
    await user.click(within(drawer).getByRole('button', { name: 'Execute' }));
    expect((await within(drawer).findAllByText('Reject, return and request information need a comment')).length).toBeGreaterThan(0);
  }, 30_000);
});

// ---------------------------------------------------------------------------------------------- requests

describe('MyRequestsPage', () => {
  it('lists requests and links to the detail', async () => {
    server.use(
      http.get(`${API}/core/workflow/requests`, () =>
        HttpResponse.json([
          {
            instanceId: INSTANCE,
            processTypeCode: 'att.LEAVE_REQUEST',
            processNameVi: 'Đề nghị nghỉ phép',
            processNameEn: 'Leave request',
            subjectType: 'att.leave_request',
            subjectId: '018f0000-0000-7000-8000-0000000004d1',
            status: 'APPROVED',
            outcome: 'APPROVED',
            currentStep: null,
            submittedAt: '2026-11-12T03:00:00Z',
            completedAt: '2026-11-13T05:00:00Z',
            summary: { employeeName: 'Lê Văn C', days: 2 },
          },
        ]),
      ),
    );
    await changeLanguage('en');
    renderPage(<MyRequestsPage />, { path: '/core/workflow/requests' });

    const link = await screen.findByRole('link', { name: 'Leave request' });
    expect(link).toHaveAttribute('href', `/core/workflow/instances/${INSTANCE}`);
    expect(screen.getByText('Approved')).toBeInTheDocument();
    // the subject column reads as a name, never as the subject id
    const subject = screen.getByTestId('subject');
    expect(within(subject).getByText('Lê Văn C')).toBeInTheDocument();
    expect(within(subject).getByText('Leave request')).toBeInTheDocument();
    expect(screen.queryByText('018f0000-0000-7000-8000-0000000004d1')).not.toBeInTheDocument();
  }, 20_000);
});

// ---------------------------------------------------------------------------------------------- instance detail

describe('InstanceDetailPage', () => {
  it('shows overview, chain, trail and the pending-task alert', async () => {
    server.use(...baseHandlers());
    await changeLanguage('en');
    renderPage(<InstanceDetailPage instanceId={INSTANCE} />, { path: `/core/workflow/instances/${INSTANCE}` });

    expect(await screen.findByText('You have a pending task in this request')).toBeInTheDocument();
    // the process type reads as its bilingual name, not as the code
    expect(within(screen.getByTestId('process-type')).getByText('Leave request')).toBeInTheDocument();
    expect(screen.queryByText('att.LEAVE_REQUEST')).not.toBeInTheDocument();
    // user ids resolve through userNames: approver tag + task assignee show the display name, not the uuid
    expect(screen.getAllByText('Nguyễn Quản Lý').length).toBeGreaterThanOrEqual(2);
    // approver chain with both steps; the skipped one shows its note
    expect(screen.getByText('1. Line manager')).toBeInTheDocument();
    expect(screen.getByText('2. HR')).toBeInTheDocument();
    expect(screen.getByText('no approver')).toBeInTheDocument();
    // trail entry
    const trail = screen.getByTestId('trail-entry');
    expect(within(trail).getByText('Trần Thị B')).toBeInTheDocument();
    expect(within(trail).getByText('Xin nghỉ 2 ngày')).toBeInTheDocument();
  }, 20_000);

  it('shows the subject by name and record type, never the raw uuid', async () => {
    server.use(
      http.get(`${API}/core/process-types`, () => HttpResponse.json(processTypes)),
      http.get(`${API}/core/workflow/instances/${INSTANCE}`, () =>
        HttpResponse.json({ ...detail, summary: { employeeName: 'Lê Văn C', employeeNo: 'NV001', days: 2 } }),
      ),
    );
    await changeLanguage('en');
    renderPage(<InstanceDetailPage instanceId={INSTANCE} />, { path: `/core/workflow/instances/${INSTANCE}` });

    const subject = await screen.findByTestId('subject');
    expect(within(subject).getByText('Lê Văn C')).toBeInTheDocument();
    expect(within(subject).getByText('Leave request')).toBeInTheDocument();
    expect(screen.queryByText(detail.subjectId!)).not.toBeInTheDocument();
  }, 20_000);

  it('falls back to the record type when the summary carries no name', async () => {
    server.use(...baseHandlers());
    await changeLanguage('en');
    renderPage(<InstanceDetailPage instanceId={INSTANCE} />, { path: `/core/workflow/instances/${INSTANCE}` });

    const subject = await screen.findByTestId('subject');
    expect(within(subject).getByText('Leave request')).toBeInTheDocument();
    expect(screen.queryByText(detail.subjectId!)).not.toBeInTheDocument();
  }, 20_000);

  it('lets the requester withdraw with a comment', async () => {
    let acted: unknown;
    server.use(
      http.get(`${API}/core/process-types`, () => HttpResponse.json(processTypes)),
      http.get(`${API}/core/workflow/instances/${INSTANCE}`, () =>
        HttpResponse.json({ ...detail, allowedActions: ['WITHDRAW', 'COMMENT'], myPendingTaskId: null }),
      ),
      http.post(`${API}/core/workflow/instances/${INSTANCE}/actions`, async ({ request }) => {
        acted = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<InstanceDetailPage instanceId={INSTANCE} />, { path: `/core/workflow/instances/${INSTANCE}` });

    await user.click(await screen.findByRole('button', { name: 'Withdraw' }));
    const modal = await screen.findByRole('dialog');
    await user.type(within(modal).getByLabelText('Comment'), 'Changed my mind');
    await user.click(within(modal).getByRole('button', { name: 'Withdraw' }));

    await waitFor(() => expect(acted).toBeDefined());
    expect(acted).toEqual({ action: 'WITHDRAW', comment: 'Changed my mind' });
  }, 30_000);
});

describe('subjectName', () => {
  it('picks the first human-readable key of the summary and ignores the rest', () => {
    expect(subjectName({ employeeNo: 'NV001', employeeName: 'Lê Văn C' })).toBe('Lê Văn C');
    expect(subjectName({ employeeNo: 'NV001', leaveType: 'ANNUAL' })).toBe('NV001');
    expect(subjectName({ employeeName: '  ' , code: 'C1' })).toBe('C1');
    expect(subjectName({ leaveType: 'ANNUAL', days: 2 })).toBeNull();
    expect(subjectName(null)).toBeNull();
  });
});

// ---------------------------------------------------------------------------------------------- console

describe('ConsolePage', () => {
  it('lists instances, retries a callback and reassigns all tasks of a user', async () => {
    let retried = false;
    let reassigned: unknown;
    server.use(
      ...baseHandlers(),
      http.get(`${API}/core/workflow/console/instances`, () =>
        HttpResponse.json([
          {
            instanceId: INSTANCE,
            processTypeCode: 'att.LEAVE_REQUEST',
            status: 'IN_PROGRESS',
            submittedAt: '2026-11-10T03:00:00Z',
            ageHours: 49.5,
            requesterUserId: REQUESTER,
            requesterName: 'Trần Thị B',
            callbackStatus: 'FAILED',
            lastError: 'module down',
          },
        ]),
      ),
      http.get(`${API}/core/workflow/console/callbacks`, () =>
        HttpResponse.json([
          {
            callbackId: CALLBACK,
            instanceId: INSTANCE,
            targetModule: 'att',
            outcome: 'APPROVED',
            status: 'FAILED',
            attempts: 3,
            lastError: 'timeout',
            createdAt: '2026-11-13T05:00:00Z',
          },
        ]),
      ),
      http.post(`${API}/core/workflow/console/callbacks/${CALLBACK}/retry`, () => {
        retried = true;
        return new HttpResponse(null, { status: 204 });
      }),
      http.get(`${API}/core/users`, () =>
        HttpResponse.json({
          items: [
            { id: REQUESTER, login: 'btran', displayName: 'Trần Thị B', email: null, status: 'ACTIVE' },
            { id: USER_ID, login: 'admin', displayName: 'Admin', email: null, status: 'ACTIVE' },
          ],
          total: 2,
          page: 0,
          size: 20,
        }),
      ),
      http.post(`${API}/core/workflow/reassignments`, async ({ request }) => {
        reassigned = await request.json();
        return HttpResponse.json([TASK]);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<ConsolePage />, { path: '/core/workflow/console' });

    // instances tab: the process type and the requester read as names, not as ids
    const row = (await screen.findByText('Leave request')).closest('tr')!;
    expect(within(row).getByText('Trần Thị B')).toBeInTheDocument();
    expect(within(row).queryByText(/att\.LEAVE_REQUEST/)).not.toBeInTheDocument();
    expect(within(row).queryByText(REQUESTER.slice(0, 8))).not.toBeInTheDocument();
    expect(within(row).getByText('module down')).toBeInTheDocument();
    expect(within(row).getByText('50')).toBeInTheDocument(); // rounded age

    // callbacks tab with retry
    await user.click(screen.getByRole('tab', { name: 'Open callbacks' }));
    const cbRow = (await screen.findByText('timeout')).closest('tr')!;
    await user.click(within(cbRow).getByRole('button', { name: /Retry/ }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(retried).toBe(true));
    expect(await screen.findByText('Retry queued')).toBeInTheDocument();

    // bulk reassignment
    // the icon's aria-label joins the accessible name ("swap Reassign …"), so match by regex
    await user.click(screen.getByRole('button', { name: /Reassign a user's tasks/ }));
    const drawer = await screen.findByRole('dialog');
    const pickers = within(drawer).getAllByRole('combobox');
    await user.click(pickers[0]!);
    await user.type(pickers[0]!, 'btran');
    await pickOption('Trần Thị B (btran)', user);
    await user.click(pickers[1]!);
    await user.type(pickers[1]!, 'admin');
    await pickOption('Admin (admin)', user);
    await user.type(within(drawer).getByLabelText('Reason'), 'On leave');
    await user.click(within(drawer).getByRole('button', { name: 'Reassign' }));

    await waitFor(() => expect(reassigned).toBeDefined());
    expect(reassigned).toEqual({ fromUserId: REQUESTER, toUserId: USER_ID, reason: 'On leave' });
    expect(await screen.findByText('1 tasks reassigned')).toBeInTheDocument();
  }, 40_000);
});

// ---------------------------------------------------------------------------------------------- definitions

describe('DefinitionsPage', () => {
  it('lists definitions with the version in force and publishes a new definition', async () => {
    let published: unknown;
    server.use(
      ...baseHandlers(),
      http.get(`${API}/core/workflow-definitions`, () => HttpResponse.json([definition])),
      http.post(`${API}/core/workflow-definitions`, async ({ request }) => {
        published = await request.json();
        return HttpResponse.json(definition, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DefinitionsPage />, { path: '/core/workflow-definitions' });

    const row = (await screen.findByText('LEAVE_DEFAULT')).closest('tr')!;
    expect(within(row).getByText('Leave approval')).toBeInTheDocument();
    expect(within(row).getByText('Version 2 · 2 steps')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'LEAVE_DEFAULT' })).toHaveAttribute(
      'href',
      `/core/workflow-definitions/${DEFINITION}`,
    );

    // process types tab (the definitions tab stays mounted, so scope to the visible panel)
    await user.click(screen.getByRole('tab', { name: 'Process types' }));
    const panel = await screen.findByRole('tabpanel');
    expect(await within(panel).findByText('att.LEAVE_REQUEST')).toBeInTheDocument();
    expect(within(panel).getByText('Leave request')).toBeInTheDocument();

    // publish a new definition (steps as JSON)
    await user.click(screen.getByRole('button', { name: /New definition/ }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByLabelText('Process type'));
    await pickOption('Leave request (att.LEAVE_REQUEST)', user);
    await user.type(within(drawer).getByLabelText('Definition code'), 'LEAVE_SPECIAL');
    await user.type(within(drawer).getByLabelText('Name (VI)'), 'Nghỉ đặc biệt');
    const stepsInput = within(drawer).getByLabelText('Steps (JSON)');
    await user.click(stepsInput);
    await user.paste('[{"stepKey":"HR","nameVi":"HR","resolver":{"type":"ROLE_IN_SCOPE","params":{"role":"HR_ADMIN"}},"allowReturn":false}]');
    await user.click(within(drawer).getByRole('button', { name: 'Publish' }));

    await waitFor(() => expect(published).toBeDefined());
    expect(published).toEqual(
      expect.objectContaining({
        processTypeCode: 'att.LEAVE_REQUEST',
        code: 'LEAVE_SPECIAL',
        nameVi: 'Nghỉ đặc biệt',
        onResolverFailure: 'ESCALATE_UP',
        steps: [{ stepKey: 'HR', nameVi: 'HR', resolver: { type: 'ROLE_IN_SCOPE', params: { role: 'HR_ADMIN' } }, allowReturn: false }],
      }),
    );
    expect(await screen.findByText('Definition published')).toBeInTheDocument();
  }, 40_000);
});

describe('DefinitionDetailPage', () => {
  it('shows the versions with their steps and conditions', async () => {
    server.use(
      ...baseHandlers(),
      http.get(`${API}/core/workflow-definitions/${DEFINITION}`, () => HttpResponse.json(definition)),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DefinitionDetailPage definitionId={DEFINITION} />, { path: `/core/workflow-definitions/${DEFINITION}` });

    // the newest version is expanded by default
    expect(await screen.findByText('Version 2')).toBeInTheDocument();
    // 'Line manager' is both the step name and the resolver label of the LINE_MANAGER step
    expect(screen.getAllByText('Line manager').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Role in scope')).toBeInTheDocument();
    expect(screen.getByText('role=HR_ADMIN')).toBeInTheDocument();
    expect(screen.getByText('Request fields · leaveType:')).toBeInTheDocument();
    expect(screen.getByText("Assign to the legal entity's HR admin")).toBeInTheDocument();

    // older version expands on click and has no conditions
    await user.click(screen.getByText('Version 1'));
    expect(await screen.findByText('Applies to every request')).toBeInTheDocument();
  }, 30_000);
});
