import { Badge, Button, Dropdown, Empty, List, Typography } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { InboxItem } from './api';
import { resolveDeepLink } from './deepLink';
import { useInbox, useMarkAllRead, useMarkRead, useNotificationStream, useUnreadCount } from './queries';
import { formatDateTime } from '@/shared/format';

/** Header widget: unread badge + the five latest items. */
export function NotificationBell() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  useNotificationStream();
  const count = useUnreadCount();
  const latest = useInbox(true, 0, 5);
  const markRead = useMarkRead();
  const markAll = useMarkAllRead();

  const open = (item: InboxItem) => {
    if (!item.readAt) markRead.mutate(item.id);
    const to = resolveDeepLink(item.deepLink);
    if (to) void navigate({ to });
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
