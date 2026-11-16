import { describe, expect, it } from 'vitest';
import { API, HttpResponse, mswHttp as http, server } from '@/test/server';
import { renderPage, screen, userEvent, waitFor, within } from '@/test/render';
import { changeLanguage } from '@/shared/i18n';
import type { DeliveryItem, InboxItem, TemplateItem } from './api';
import { InboxPage } from './InboxPage';
import { resolveDeepLink } from './deepLink';
import { NotificationBell } from './NotificationBell';
import { DeliveriesPage, resendBlocker } from './DeliveriesPage';
import { TemplatesPage, matchesTemplate } from './TemplatesPage';

const N_UNREAD = '018f0000-0000-7000-8000-0000000003a1';
const N_READ = '018f0000-0000-7000-8000-0000000003a2';
const D_FAILED = '018f0000-0000-7000-8000-0000000003b1';
const D_DELIVERED = '018f0000-0000-7000-8000-0000000003b2';
const D_IN_APP = '018f0000-0000-7000-8000-0000000003b3';
const D_ATTACHMENT = '018f0000-0000-7000-8000-0000000003b4';
const RECIPIENT = '018f0000-0000-7000-8000-0000000003c1';

const inboxItems: InboxItem[] = [
  {
    id: N_UNREAD,
    createdAt: '2026-11-10T08:00:00Z',
    eventCode: 'core.workflow.task_assigned',
    category: 'WORKFLOW',
    title: 'Bạn có yêu cầu chờ duyệt',
    body: 'Đề nghị nghỉ phép đang chờ bạn duyệt.',
    deepLink: '/core/workflow/tasks',
    readAt: null,
  },
  {
    id: N_READ,
    createdAt: '2026-11-09T03:00:00Z',
    eventCode: 'core.security.password_changed',
    category: 'SECURITY',
    title: 'Mật khẩu đã được đổi',
    body: null,
    deepLink: null,
    readAt: '2026-11-09T04:00:00Z',
  },
];

const delivery = (over: Partial<DeliveryItem>): DeliveryItem => ({
  deliveryId: D_FAILED,
  notificationId: N_UNREAD,
  eventCode: 'core.workflow.task_assigned',
  recipientUserId: RECIPIENT,
  channel: 'EMAIL',
  destinationMasked: 'a***@example.test',
  status: 'FAILED',
  attempts: 2,
  lastError: 'SMTP 550 mailbox unavailable',
  hasAttachment: false,
  createdAt: '2026-11-10T08:00:00Z',
  sentAt: null,
  deliveredAt: null,
  failedAt: '2026-11-10T08:01:00Z',
  ...over,
});

const deliveries: DeliveryItem[] = [
  delivery({}),
  delivery({ deliveryId: D_DELIVERED, status: 'DELIVERED', lastError: null, failedAt: null, deliveredAt: '2026-11-10T08:02:00Z', destinationMasked: 'b***@example.test' }),
  delivery({ deliveryId: D_IN_APP, channel: 'IN_APP', status: 'DELIVERED', lastError: null, failedAt: null, destinationMasked: null }),
  delivery({ deliveryId: D_ATTACHMENT, hasAttachment: true, destinationMasked: 'c***@example.test' }),
];

const templates: TemplateItem[] = [
  {
    eventCode: 'core.workflow.task_assigned',
    category: 'WORKFLOW',
    mandatory: true,
    channel: 'IN_APP',
    locale: 'vi-VN',
    subject: 'Bạn có yêu cầu chờ duyệt',
    body: 'Yêu cầu {{requestTitle}} đang chờ bạn xử lý.',
    bodyFormat: 'TEXT',
    placeholders: [{ name: 'requestTitle', kind: 'TEXT' }],
    attachmentAllowed: false,
  },
  {
    eventCode: 'pay.payslip_published',
    category: 'PAYSLIP',
    mandatory: false,
    channel: 'EMAIL',
    locale: 'en-US',
    subject: 'Your payslip is ready',
    body: '<p>Your payslip for {{period}} is attached.</p>',
    bodyFormat: 'HTML',
    placeholders: [{ name: 'period', kind: 'TEXT' }],
    attachmentAllowed: true,
  },
];

// ---------------------------------------------------------------------------------------------- pure helpers

describe('resendBlocker (mirrors NotificationService.resend)', () => {
  it('allows only e-mails that failed, bounced or never left, without an attachment', () => {
    expect(resendBlocker({ channel: 'EMAIL', status: 'FAILED', hasAttachment: false })).toBeNull();
    expect(resendBlocker({ channel: 'EMAIL', status: 'BOUNCED', hasAttachment: false })).toBeNull();
    expect(resendBlocker({ channel: 'EMAIL', status: 'QUEUED', hasAttachment: false })).toBeNull();
    expect(resendBlocker({ channel: 'IN_APP', status: 'FAILED', hasAttachment: false })).toBe('channel');
    expect(resendBlocker({ channel: 'EMAIL', status: 'DELIVERED', hasAttachment: false })).toBe('status');
    expect(resendBlocker({ channel: 'EMAIL', status: 'FAILED', hasAttachment: true })).toBe('attachment');
  });
});

describe('resolveDeepLink', () => {
  it('prefixes the workflow links of older notifications and keeps the rest', () => {
    const id = '018f0000-0000-7000-8000-0000000004a1';
    expect(resolveDeepLink(`/workflow/requests/${id}`)).toBe(`/core/workflow/requests/${id}`);
    expect(resolveDeepLink(`/workflow/tasks/${id}`)).toBe('/core/workflow/tasks');
    expect(resolveDeepLink(`/core/workflow/requests/${id}`)).toBe(`/core/workflow/requests/${id}`);
    expect(resolveDeepLink('/core/workflow/tasks')).toBe('/core/workflow/tasks');
    expect(resolveDeepLink('/people/separations/x')).toBe('/people/separations/x');
  });

  it('has nothing to follow for an empty or relative link', () => {
    expect(resolveDeepLink(null)).toBeNull();
    expect(resolveDeepLink(undefined)).toBeNull();
    expect(resolveDeepLink('   ')).toBeNull();
    expect(resolveDeepLink('workflow/requests/x')).toBeNull();
  });
});

describe('matchesTemplate', () => {
  it('matches event code and subject, case-insensitively', () => {
    const [workflow] = templates;
    expect(matchesTemplate(workflow!, '')).toBe(true);
    expect(matchesTemplate(workflow!, 'TASK_ASSIGNED')).toBe(true);
    expect(matchesTemplate(workflow!, 'chờ duyệt')).toBe(true);
    expect(matchesTemplate(workflow!, 'payslip')).toBe(false);
  });
});

// ---------------------------------------------------------------------------------------------- inbox

const inboxHandlers = (marked: string[] = [], onMarkAll?: () => void) => [
  http.get(`${API}/core/notifications`, ({ request }) => {
    const unreadOnly = new URL(request.url).searchParams.get('unreadOnly') === 'true';
    const items = unreadOnly ? inboxItems.filter((i) => !i.readAt) : inboxItems;
    return HttpResponse.json({ items, unread: 1, page: 0, size: 20 });
  }),
  http.get(`${API}/core/notifications/unread-count`, () => HttpResponse.json({ unread: 1 })),
  http.post(`${API}/core/notifications/:id/read`, ({ params }) => {
    marked.push(params.id as string);
    return new HttpResponse(null, { status: 204 });
  }),
  http.post(`${API}/core/notifications/read-all`, () => {
    onMarkAll?.();
    return HttpResponse.json({ marked: 1 });
  }),
];

describe('InboxPage', () => {
  it('lists notifications with their category, marks one read, and filters to unread', async () => {
    const marked: string[] = [];
    server.use(...inboxHandlers(marked));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<InboxPage />, { path: '/core/notifications' });

    const unreadRow = (await screen.findByText('Bạn có yêu cầu chờ duyệt')).closest('li')!;
    expect(within(unreadRow).getByText('Approval')).toBeInTheDocument();
    const readRow = screen.getByText('Mật khẩu đã được đổi').closest('li')!;
    expect(within(readRow).getByText('Security')).toBeInTheDocument();
    expect(within(readRow).getByText('Read')).toBeInTheDocument();

    await user.click(within(unreadRow).getByRole('button', { name: 'Mark as read: Bạn có yêu cầu chờ duyệt' }));
    await waitFor(() => expect(marked).toEqual([N_UNREAD]));

    await user.click(screen.getByText('Unread (1)'));
    await waitFor(() => expect(screen.queryByText('Mật khẩu đã được đổi')).not.toBeInTheDocument());
    expect(screen.getByText('Bạn có yêu cầu chờ duyệt')).toBeInTheDocument();
  }, 20_000);

  it('opens the request detail of a workflow notification and marks it read', async () => {
    const instance = '018f0000-0000-7000-8000-0000000004a1';
    const marked: string[] = [];
    server.use(
      // this handler wins over the one of inboxHandlers: the first match is used
      http.get(`${API}/core/notifications`, () =>
        HttpResponse.json({
          // a link written before the workflow deep links carried the /core prefix
          items: [{ ...inboxItems[0], deepLink: `/workflow/requests/${instance}` }],
          unread: 1,
          page: 0,
          size: 20,
        }),
      ),
      ...inboxHandlers(marked),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    const { router } = renderPage(<InboxPage />, { path: '/core/notifications' });

    await user.click(await screen.findByText('Bạn có yêu cầu chờ duyệt'));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/core/workflow/requests/${instance}`));
    expect(marked).toEqual([N_UNREAD]);
  }, 20_000);

  it('marks everything read and reports how many', async () => {
    let markedAll = false;
    server.use(...inboxHandlers([], () => (markedAll = true)));
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<InboxPage />, { path: '/core/notifications' });

    await screen.findByText('Bạn có yêu cầu chờ duyệt');
    await user.click(screen.getByRole('button', { name: /Mark all as read/ }));
    await waitFor(() => expect(markedAll).toBe(true));
    expect(await screen.findByText('1 notification marked as read')).toBeInTheDocument();
  }, 20_000);
});

describe('NotificationBell', () => {
  it('shows the unread badge and the latest items', async () => {
    server.use(...inboxHandlers());
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<NotificationBell />);

    await screen.findByTitle('1'); // the badge
    await user.click(screen.getByLabelText('Notifications'));
    expect(await screen.findByText('Bạn có yêu cầu chờ duyệt')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View all' })).toBeInTheDocument();
  }, 20_000);
});

// ---------------------------------------------------------------------------------------------- deliveries

describe('DeliveriesPage', () => {
  it('lists deliveries, offers resend only where the backend allows it, and resends', async () => {
    let resent: string | undefined;
    const searches: string[] = [];
    server.use(
      http.get(`${API}/core/notification-deliveries`, ({ request }) => {
        const url = new URL(request.url);
        searches.push(url.search);
        const status = url.searchParams.get('status');
        return HttpResponse.json(status ? deliveries.filter((d) => d.status === status) : deliveries);
      }),
      http.post(`${API}/core/notification-deliveries/:id/resend`, ({ params }) => {
        resent = params.id as string;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DeliveriesPage />, { path: '/core/notification-deliveries' });

    const failedRow = (await screen.findByText('a***@example.test')).closest('tr')!;
    expect(within(failedRow).getByText('Failed')).toBeInTheDocument();
    expect(within(failedRow).getByText('SMTP 550 mailbox unavailable')).toBeInTheDocument();

    // delivered e-mail and attachment e-mail: button present but disabled; in-app row: no button at all
    const deliveredRow = screen.getByText('b***@example.test').closest('tr')!;
    expect(within(deliveredRow).getByRole('button', { name: 'Resend delivery to b***@example.test' })).toBeDisabled();
    const attachmentRow = screen.getByText('c***@example.test').closest('tr')!;
    expect(within(attachmentRow).getByRole('button', { name: 'Resend delivery to c***@example.test' })).toBeDisabled();
    const inAppRow = screen.getByText('In app').closest('tr')!;
    expect(within(inAppRow).queryByRole('button')).not.toBeInTheDocument();

    await user.click(within(failedRow).getByRole('button', { name: 'Resend delivery to a***@example.test' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(resent).toBe(D_FAILED));
    expect(await screen.findByText('The e-mail has been queued again.')).toBeInTheDocument();
  }, 30_000);

  it('passes the status filter to the API', async () => {
    const searches: string[] = [];
    server.use(
      http.get(`${API}/core/notification-deliveries`, ({ request }) => {
        searches.push(new URL(request.url).search);
        return HttpResponse.json([]);
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<DeliveriesPage />, { path: '/core/notification-deliveries' });

    await waitFor(() => expect(searches.length).toBeGreaterThan(0));
    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByTitle('Failed'));
    await waitFor(() => expect(searches.at(-1)).toContain('status=FAILED'));
  }, 20_000);
});

// ---------------------------------------------------------------------------------------------- templates

describe('TemplatesPage', () => {
  it('lists templates, searches, and previews one with sample values', async () => {
    let previewBody: unknown;
    server.use(
      http.get(`${API}/core/notification-templates`, () => HttpResponse.json(templates)),
      http.post(`${API}/core/notification-templates/preview`, async ({ request }) => {
        previewBody = await request.json();
        return HttpResponse.json({ subject: 'Bạn có yêu cầu chờ duyệt', body: 'Yêu cầu Nghỉ phép đang chờ bạn xử lý.', bodyFormat: 'TEXT' });
      }),
    );
    await changeLanguage('en');
    const user = userEvent.setup();
    renderPage(<TemplatesPage />, { path: '/core/notification-templates' });

    // both rows, with the mandatory lock and the attachment marker
    const workflowRow = (await screen.findByText('core.workflow.task_assigned')).closest('tr')!;
    expect(within(workflowRow).getByLabelText('Mandatory')).toBeInTheDocument();
    const payslipRow = screen.getByText('pay.payslip_published').closest('tr')!;
    expect(within(payslipRow).getByTitle('Attachment allowed')).toBeInTheDocument();

    await user.type(screen.getByRole('searchbox', { name: 'Search event or subject' }), 'payslip');
    await waitFor(() => expect(screen.queryByText('core.workflow.task_assigned')).not.toBeInTheDocument());
    await user.clear(screen.getByRole('searchbox', { name: 'Search event or subject' }));

    await user.click(await screen.findByRole('button', { name: 'View template core.workflow.task_assigned (IN_APP, vi-VN)' }));
    const drawer = await screen.findByRole('dialog');
    // the placeholder shows twice: in the placeholders list and as the preview form label
    expect(await within(drawer).findAllByText('{{requestTitle}}')).toHaveLength(2);

    await user.clear(within(drawer).getByLabelText('{{requestTitle}}'));
    await user.type(within(drawer).getByLabelText('{{requestTitle}}'), 'Nghỉ phép');
    await user.click(within(drawer).getByRole('button', { name: /Preview/ }));
    await waitFor(() => expect(previewBody).toBeDefined());
    expect(previewBody).toEqual(
      expect.objectContaining({
        eventCode: 'core.workflow.task_assigned',
        channel: 'IN_APP',
        locale: 'vi-VN',
        sample: { requestTitle: 'Nghỉ phép' },
      }),
    );
    const result = await screen.findByTestId('template-preview');
    expect(within(result).getByText('Yêu cầu Nghỉ phép đang chờ bạn xử lý.')).toBeInTheDocument();
  }, 30_000);
});
