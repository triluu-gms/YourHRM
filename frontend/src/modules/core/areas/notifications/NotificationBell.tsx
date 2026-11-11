import { useEffect } from 'react';
import { Badge, Button, Dropdown, Empty, List, Typography } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { http, type ISOInstant, type UUID } from '@/shared/api';
import { formatDateTime } from '@/shared/format';

/** `NotificationInboxService.InboxItem`. */
export interface InboxItem {
  id: UUID;
  createdAt: ISOInstant;
  eventCode: string;
  category: string;
  title: string;
  body?: string | null;
  deepLink?: string | null;
  readAt?: ISOInstant | null;
}

export interface Inbox {
  items: InboxItem[];
  unread: number;
  page: number;
  size: number;
}

export const notificationKeys = {
  all: ['core', 'notifications'] as const,
  unreadCount: ['core', 'notifications', 'unread-count'] as const,
  inbox: (unreadOnly: boolean, page: number, size: number) =>
    ['core', 'notifications', 'inbox', { unreadOnly, page, size }] as const,
};

export const notificationsApi = {
  inbox: (unreadOnly: boolean, page: number, size: number) =>
    http.get<Inbox>('/core/notifications', { params: { unreadOnly, page, size } }),
  unreadCount: () => http.get<{ unread: number }>('/core/notifications/unread-count'),
  markRead: (id: UUID) => http.post<void>(`/core/notifications/${id}/read`),
  markAllRead: () => http.post<{ marked: number }>('/core/notifications/read-all'),
};

/** Subscribes to the SSE stream (US-CORE-072) and refreshes the inbox queries on every event. */
export function useNotificationStream() {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (typeof EventSource === 'undefined') return;
    const source = new EventSource('/api/v1/core/notifications/stream', { withCredentials: true });
    const refresh = () => void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    source.onmessage = refresh;
    source.addEventListener('notification', refresh);
    source.onerror = () => {
      /* the browser reconnects on its own; after a 401 the next API call redirects to login */
    };
    return () => source.close();
  }, [queryClient]);
}

/** Header widget: unread badge + the five latest items. */
export function NotificationBell() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  useNotificationStream();
  const count = useQuery({ queryKey: notificationKeys.unreadCount, queryFn: notificationsApi.unreadCount, refetchInterval: 60_000 });
  const latest = useQuery({ queryKey: notificationKeys.inbox(true, 0, 5), queryFn: () => notificationsApi.inbox(true, 0, 5) });
  const markRead = useMutation({
    mutationFn: notificationsApi.markRead,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  });
  const markAll = useMutation({
    mutationFn: notificationsApi.markAllRead,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  });

  const open = (item: InboxItem) => {
    if (!item.readAt) markRead.mutate(item.id);
    if (item.deepLink) void navigate({ to: item.deepLink });
  };

  return (
    <Dropdown
      trigger={['click']}
      popupRender={() => (
        <div style={{ width: 360, background: '#fff', boxShadow: '0 6px 16px rgba(0,0,0,.12)', borderRadius: 8 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px' }}>
            <Typography.Text strong>{t('header.notifications')}</Typography.Text>
            <Button type="link" size="small" onClick={() => markAll.mutate()} disabled={!count.data?.unread}>
              {t('header.markAllRead')}
            </Button>
          </div>
          <List<InboxItem>
            size="small"
            dataSource={latest.data?.items ?? []}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('header.noNotifications')} /> }}
            renderItem={(item) => (
              <List.Item style={{ cursor: 'pointer', padding: '8px 12px' }} onClick={() => open(item)}>
                <List.Item.Meta
                  title={<Typography.Text strong={!item.readAt}>{item.title}</Typography.Text>}
                  description={
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {formatDateTime(item.createdAt)}
                    </Typography.Text>
                  }
                />
              </List.Item>
            )}
          />
          <div style={{ textAlign: 'center', padding: 8, borderTop: '1px solid #f0f0f0' }}>
            <Link to="/core/notifications">{t('header.viewAll')}</Link>
          </div>
        </div>
      )}
    >
      <Badge count={count.data?.unread ?? 0} size="small" overflowCount={99}>
        <BellOutlined style={{ fontSize: 18, cursor: 'pointer' }} aria-label={t('header.notifications')} />
      </Badge>
    </Dropdown>
  );
}
