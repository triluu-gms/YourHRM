import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, problem, server } from '@/test/server';
import { meWith } from '@/test/fixtures';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { PermissionView, RoleDetail, RoleSummary, UserView } from './api';
import { UsersListPage } from './UsersListPage';
import { RolesListPage } from './RolesListPage';
import { RoleDetailPage } from './RoleDetailPage';
import { PermissionsPage } from './PermissionsPage';
import { SupportSessionsPage } from './SupportSessionsPage';

const ROLE_ID = '018f0000-0000-7000-8000-0000000000a1';
const SYSTEM_ROLE_ID = '018f0000-0000-7000-8000-0000000000a2';

const perm = (code: string, extra: Partial<PermissionView> = {}): PermissionView => {
  const [module = '', functionCode = '', action = ''] = code.split('.');
  return {
    code,
    module,
    functionCode,
    action,
    nameVi: `VI ${code}`,
    nameEn: `EN ${code}`,
    sensitive: false,
    privileged: false,
    requiresStepUp: false,
    active: true,
    ...extra,
  };
};

const catalogue: PermissionView[] = [
  perm('core.user.view'),
  perm('core.user.create'),
  perm('core.role.view'),
  perm('hr.person.view', { sensitive: true }),
  perm('hr.compensation.view', { sensitive: true, privileged: true }),
];

const customRole: RoleDetail = {
  id: ROLE_ID,
  code: 'HR_VIEWER',
  nameVi: 'Xem nhân sự',
  nameEn: 'HR viewer',
  system: false,
  templateVersion: null,
  version: 3,
  permissions: [{ code: 'core.user.view', fieldMaskLevel: 'NONE' }],
};

const systemRole: RoleDetail = { ...customRole, id: SYSTEM_ROLE_ID, code: 'HR_ADMIN', system: true, templateVersion: 1 };

function roleHandlers(role: RoleDetail) {
  return [
    http.get(`${API}/core/roles/${role.id}`, () => HttpResponse.json(role)),
    http.get(`${API}/core/permissions`, () => HttpResponse.json(catalogue)),
  ];
}

describe('RoleDetailPage permission matrix', () => {
  it('saves the edited matrix with the role version', async () => {
    let body: unknown;
    server.use(
      ...roleHandlers(customRole),
      http.put(`${API}/core/roles/${ROLE_ID}/permissions`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...customRole, version: 4 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<RoleDetailPage roleId={ROLE_ID} />);

    expect(await screen.findByText('Role HR_VIEWER')).toBeInTheDocument();
    const selectAllUser = await screen.findByRole('checkbox', { name: /Select every permission of user/ });
    await user.click(selectAllUser); // adds core.user.create (view already granted)
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: /hr/ }));
    await user.click(await screen.findByRole('checkbox', { name: 'Permission hr.person.view' }));

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).toBeDefined());
    expect(body).toEqual({
      version: 3,
      permissions: expect.arrayContaining([
        { code: 'core.user.view', fieldMaskLevel: 'NONE' },
        { code: 'core.user.create', fieldMaskLevel: 'NONE' },
        { code: 'hr.person.view', fieldMaskLevel: 'NONE' },
      ]),
    });
    expect((body as { permissions: unknown[] }).permissions).toHaveLength(3);
  });

  it('is read-only for a system role and offers copy but not edit/delete', async () => {
    server.use(...roleHandlers(systemRole));
    await changeLanguage('en');
    renderPage(<RoleDetailPage roleId={SYSTEM_ROLE_ID} />);

    expect(await screen.findByText('System role: read-only.')).toBeInTheDocument();
    const box = await screen.findByRole('checkbox', { name: 'Permission core.user.view' });
    expect(box).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Copy role/ })).toBeInTheDocument();
  });

  it('flags grants of deactivated permissions that a save will drop', async () => {
    server.use(
      ...roleHandlers({ ...customRole, permissions: [...customRole.permissions, { code: 'core.legacy.view', fieldMaskLevel: 'NONE' }] }),
    );
    await changeLanguage('en');
    renderPage(<RoleDetailPage roleId={ROLE_ID} />);
    expect(await screen.findByText(/no longer active and will be removed when you save: core.legacy.view/)).toBeInTheDocument();
  });

  it('shows the translated problem when the role is still assigned', async () => {
    server.use(
      ...roleHandlers(customRole),
      http.delete(`${API}/core/roles/${ROLE_ID}`, () => problem(409, 'role-in-use', 'Role HR_VIEWER has assignments')),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<RoleDetailPage roleId={ROLE_ID} />);
    await user.click(await screen.findByRole('button', { name: /Delete/ }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText(/has \(or had\) assignments and cannot be deleted/)).toBeInTheDocument();
  });

  it('hides the matrix controls without core.role.update', async () => {
    server.use(
      ...roleHandlers(customRole),
      http.get(`${API}/core/me`, () => HttpResponse.json(meWith('core.role.view', 'core.permission.view'))),
    );
    await changeLanguage('en');
    renderPage(<RoleDetailPage roleId={ROLE_ID} />);
    expect(await screen.findByRole('checkbox', { name: 'Permission core.user.view' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });
});

describe('RolesListPage', () => {
  it('lists roles and filters by kind', async () => {
    const roles: RoleSummary[] = [
      { id: SYSTEM_ROLE_ID, code: 'HR_ADMIN', nameVi: 'Quản trị NS', nameEn: 'HR admin', system: true, permissionCount: 40, version: 1 },
      { id: ROLE_ID, code: 'HR_VIEWER', nameVi: 'Xem NS', nameEn: 'HR viewer', system: false, permissionCount: 1, version: 3 },
    ];
    server.use(http.get(`${API}/core/roles`, () => HttpResponse.json(roles)));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<RolesListPage />, { path: '/core/roles' });

    expect(await screen.findByText('HR_ADMIN')).toBeInTheDocument();
    expect(screen.getByText('HR_VIEWER')).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'Kind' }));
    await user.click(await screen.findByTitle('Custom'));
    await waitFor(() => expect(screen.queryByText('HR_ADMIN')).not.toBeInTheDocument());
    expect(screen.getByText('HR_VIEWER')).toBeInTheDocument();
  });
});

describe('UsersListPage', () => {
  const alice: UserView = {
    id: '018f0000-0000-7000-8000-0000000000b1',
    login: 'alice',
    displayName: 'Alice Nguyen',
    email: 'alice@example.test',
    status: 'ACTIVE',
    version: 0,
  };

  it('searches users and creates one', async () => {
    let searched: string | null = null;
    let created: unknown;
    server.use(
      http.get(`${API}/core/users`, ({ request }) => {
        searched = new URL(request.url).searchParams.get('q');
        return HttpResponse.json({ items: [alice], page: 0, size: 20, total: 1 });
      }),
      http.post(`${API}/core/users`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json({ ...alice, id: '018f0000-0000-7000-8000-0000000000b2', login: 'bob' }, { status: 201 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<UsersListPage />, { path: '/core/users' });

    expect(await screen.findByText('alice')).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'ali{Enter}');
    await waitFor(() => expect(searched).toBe('ali'));

    await user.click(screen.getByRole('button', { name: /Create user/ }));
    const drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByLabelText(/^Login/), 'bob');
    await user.type(within(drawer).getByLabelText(/^Email/), 'bob@example.test');
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(created).toMatchObject({ login: 'bob', email: 'bob@example.test' }));
  }, 15_000);
});

describe('PermissionsPage', () => {
  it('filters the catalogue by text and shows flags', async () => {
    server.use(http.get(`${API}/core/permissions`, () => HttpResponse.json(catalogue)));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<PermissionsPage />, { path: '/core/permissions' });

    expect(await screen.findByText('hr.compensation.view')).toBeInTheDocument();
    expect(screen.getByText('5 permissions')).toBeInTheDocument();
    expect(screen.getAllByText('Privileged')).toHaveLength(1);

    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'compensation');
    await waitFor(() => expect(screen.getByText('1 permission')).toBeInTheDocument());
    expect(screen.queryByText('core.user.view')).not.toBeInTheDocument();
  });
});

describe('SupportSessionsPage', () => {
  it('opens the events of a session', async () => {
    const grantId = '018f0000-0000-7000-8000-0000000000c1';
    server.use(
      http.get(`${API}/core/support-sessions`, () =>
        HttpResponse.json([
          { supportGrantId: grantId, operatorId: null, firstSeen: '2026-11-10T02:00:00Z', lastSeen: '2026-11-10T03:00:00Z', requests: 12, refusedRequests: 1, changes: 2 },
        ]),
      ),
      http.get(`${API}/core/support-sessions/${grantId}/events`, () =>
        HttpResponse.json([{ occurredAt: '2026-11-10T02:05:00Z', action: 'SUPPORT_ACCESS', objectType: null, objectId: null, details: { path: '/api/v1/core/users' } }]),
      ),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<SupportSessionsPage />, { path: '/core/support-sessions' });

    await user.click(await screen.findByRole('button', { name: 'Events' }));
    expect(await screen.findByText('SUPPORT_ACCESS')).toBeInTheDocument();
    expect(screen.getByText('path=/api/v1/core/users')).toBeInTheDocument();
  });
});
