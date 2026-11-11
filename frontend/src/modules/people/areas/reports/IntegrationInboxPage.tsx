import { useState } from 'react';
import { Button, Input, InputNumber, Select, Space, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { DataTable, PageHeader } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import { INBOX_STATUSES, type InboxMessage, type InboxStatus } from './api';
import { useIntegrationInbox } from './queries';
import { LabelledStatus } from './shared';
import './i18n';

/** Operations view of People's inbox (US-PPL-082): events from other modules, processing status, dead letters. */
export function IntegrationInboxPage() {
  const { t } = useTranslation();
  const { t: tr } = useTranslation('people-reports');
  const [status, setStatus] = useState<InboxStatus | undefined>();
  const [consumer, setConsumer] = useState('');
  const [limit, setLimit] = useState(100);
  const query = useIntegrationInbox({ status, consumer: consumer || undefined, limit });

  return (
    <div>
      <PageHeader
        title={tr('inbox.title')}
        subTitle={tr('inbox.intro')}
        extra={
          <Button icon={<ReloadOutlined />} loading={query.isFetching} onClick={() => void query.refetch()}>
            {t('action.refresh')}
          </Button>
        }
      >
        <Space wrap>
          <Select<InboxStatus>
            allowClear
            aria-label={tr('common.status')}
            placeholder={tr('inbox.allStatuses')}
            style={{ width: 180 }}
            value={status}
            onChange={setStatus}
            options={INBOX_STATUSES.map((s) => ({ value: s, label: tr(`inbox.statuses.${s}`) }))}
          />
          <Input.Search
            allowClear
            aria-label={tr('inbox.consumer')}
            placeholder={tr('inbox.consumerPlaceholder')}
            style={{ width: 260 }}
            onSearch={(v) => setConsumer(v.trim())}
          />
          <InputNumber
            aria-label={tr('inbox.limit')}
            addonBefore={tr('inbox.limit')}
            min={1}
            max={1000}
            value={limit}
            onChange={(v) => setLimit(v ?? 100)}
          />
        </Space>
      </PageHeader>

      <DataTable<InboxMessage>
        size="small"
        rowKey={(m) => `${m.consumer}:${m.eventId}`}
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: tr('inbox.receivedAt'),
            dataIndex: 'receivedAt',
            render: (v: string) => formatDateTime(v),
          },
          { title: tr('inbox.eventType'), dataIndex: 'eventType', render: (v: string) => <code>{v}</code> },
          { title: tr('inbox.consumer'), dataIndex: 'consumer' },
          {
            title: tr('common.status'),
            dataIndex: 'status',
            render: (s: InboxStatus) => <LabelledStatus status={s} prefix="inbox.statuses" />,
          },
          { title: tr('inbox.attempts'), dataIndex: 'attempts', align: 'right' },
          {
            title: tr('inbox.lastError'),
            dataIndex: 'lastError',
            render: (v: string | null) =>
              v ? (
                <Typography.Text type="danger" style={{ fontSize: 12 }} ellipsis={{ tooltip: v }}>
                  {v}
                </Typography.Text>
              ) : (
                '—'
              ),
          },
          {
            title: tr('inbox.processedAt'),
            dataIndex: 'processedAt',
            render: (v: string | null) => formatDateTime(v),
          },
          {
            title: tr('inbox.eventId'),
            dataIndex: 'eventId',
            render: (id: string) => (
              <Typography.Text code copyable={{ text: id }}>
                {id.slice(0, 8)}…
              </Typography.Text>
            ),
          },
        ]}
      />
    </div>
  );
}
