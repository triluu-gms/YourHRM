import { useState } from 'react';
import { Badge, Button, Empty, List, Pagination, Segmented, Space, Typography } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import { useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PageHeader, ProblemAlert, useFeedback, usePaging } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import type { InboxItem } from './api';
import { resolveDeepLink } from './deepLink';
import { useInbox, useMarkAllRead, useMarkRead } from './queries';
import { CategoryTag } from './widgets';
import { NOTIFICATIONS_NS } from './i18n';

type View = 'all' | 'unread';

/**
 * The caller's in-app inbox (US-CORE-072). Opening an item marks it read and follows its deep link; the header bell's
 * live stream keeps this list fresh. Notification text never holds personal data or amounts (BR-CORE-074).
 */
export function InboxPage() {
  const { t } = useTranslation(NOTIFICATIONS_NS);
  const navigate = useNavigate();
  const fb = useFeedback();
  const [view, setView] = useState<View>('all');
  const paging = usePaging(20);
  const query = useInbox(view === 'unread', paging.page, paging.size);
  const markRead = useMarkRead();
  const markAll = useMarkAllRead();
  const items = query.data?.items ?? [];
  const unread = query.data?.unread ?? 0;
  const hasMore = items.length === paging.size;

  const open = (item: InboxItem) => {
    if (!item.readAt) markRead.mutate(item.id);
    const to = resolveDeepLink(item.deepLink);
    if (to) void navigate({ to });
  };

  const readAll = async () => {
    try {
      const { marked } = await markAll.mutateAsync();
      fb.done(t('common.allMarked', { count: marked }));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader
        title={t('inbox.title')}
        subTitle={t('inbox.subtitle')}
        extra={
          <Button icon={<CheckOutlined />} onClick={() => void readAll()} disabled={!unread} loading={markAll.isPending}>
            {t('common.markAllRead')}
          </Button>
        }
      />

      <Space style={{ marginBottom: 12 }} wrap>
        <Segmented<View>
          value={view}
          onChange={(v) => {
            setView(v);
            paging.reset();
          }}
          options={[
            { value: 'all', label: t('common.all') },
            { value: 'unread', label: `${t('common.unread')} (${unread})` },
          ]}
        />
        <Typography.Text type="secondary">{t('common.unreadCount', { count: unread })}</Typography.Text>
      </Space>

      {query.error ? <ProblemAlert error={query.error} style={{ marginBottom: 12 }} /> : null}

      <List<InboxItem>
        bordered
        loading={query.isLoading}
        dataSource={items}
        locale={{
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={view === 'unread' ? t('inbox.emptyUnread') : t('inbox.empty')}
            />
          ),
        }}
        renderItem={(item) => (
          <List.Item
            data-testid="inbox-item"
            style={{ background: item.readAt ? undefined : 'rgba(22, 119, 255, 0.04)' }}
            actions={
              item.readAt
                ? [<Typography.Text key="read" type="secondary">{t('common.read')}</Typography.Text>]
                : [
                    <Button
                      key="mark"
                      type="link"
                      size="small"
                      aria-label={`${t('common.markRead')}: ${item.title}`}
                      onClick={() => markRead.mutate(item.id)}
                    >
                      {t('common.markRead')}
                    </Button>,
                  ]
            }
          >
            <List.Item.Meta
              avatar={<Badge status={item.readAt ? 'default' : 'processing'} />}
              title={
                <Space size={8} wrap>
                  {item.deepLink ? (
                    <Typography.Link strong={!item.readAt} onClick={() => open(item)}>
                      {item.title}
                    </Typography.Link>
                  ) : (
                    <Typography.Text strong={!item.readAt}>{item.title}</Typography.Text>
                  )}
                  <CategoryTag category={item.category} />
                </Space>
              }
              description={
                <>
                  {item.body ? (
                    <Typography.Paragraph style={{ marginBottom: 4, whiteSpace: 'pre-line' }} ellipsis={{ rows: 2, expandable: true }}>
                      {item.body}
                    </Typography.Paragraph>
                  ) : null}
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {formatDateTime(item.createdAt)}
                  </Typography.Text>
                </>
              }
            />
          </List.Item>
        )}
      />

      {paging.page > 0 || hasMore ? (
        <Pagination
          style={{ marginTop: 12, textAlign: 'right' }}
          current={paging.page + 1}
          pageSize={paging.size}
          total={hasMore ? (paging.page + 2) * paging.size : paging.page * paging.size + items.length}
          showSizeChanger
          onChange={(p, s) => paging.onChange(p - 1, s)}
        />
      ) : null}
    </div>
  );
}
