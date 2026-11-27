import { useState } from 'react';
import { Alert, Button, Input, InputNumber, Select, Space, Typography } from 'antd';
import { ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import { INBOX_STATUSES, type InboxMessage, type ResyncSummary } from './api';
import { useInbox, useResyncLocations } from './queries';
import { REPORTS_NS } from './i18n';
import './i18n';

/** Operations view of T&A's inbox: events of other modules it consumed, dead letters, and the location resync. */
export function IntegrationInboxPage() {
  const { t } = useTranslation();
  const { t: tr } = useTranslation(REPORTS_NS);
  const fb = useFeedback();
  const qc = useQueryClient();
  const [status, setStatus] = useState<string | undefined>();
  const [consumer, setConsumer] = useState('');
  const [limit, setLimit] = useState(100);
  const query = useInbox({ status, consumer: consumer || undefined, limit });
  const resync = useResyncLocations();
  const [summary, setSummary] = useState<ResyncSummary | null>(null);

  return (
    <div>
      <PageHeader
        title={tr('inbox.title')}
        subTitle={tr('inbox.intro')}
        extra={
          <>
            <Button icon={<ReloadOutlined />} loading={query.isFetching} onClick={() => void query.refetch()}>
              {t('action.refresh')}
            </Button>
            <PermissionGate anyOf={P.INTEGRATION_RESYNC}>
              <ConfirmButton
                icon={<SyncOutlined />}
                loading={resync.isPending}
                title={tr('inbox.resyncConfirm')}
                description={tr('inbox.resyncHint')}
                onConfirm={() =>
                  resync
                    .mutateAsync()
                    .then((s) => {
                      setSummary(s);
                      fb.done(tr('inbox.resynced'));
                      void qc.invalidateQueries({ queryKey: ['att', 'integration'] });
                    })
                    .catch(fb.failed)
                }
              >
                {tr('inbox.resync')}
              </ConfirmButton>
            </PermissionGate>
          </>
        }
      >
        <Space wrap>
          <Select<string>
            allowClear
            aria-label={tr('inbox.status')}
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

      {summary ? (
        <Alert
          type="success"
          showIcon
          closable
          onClose={() => setSummary(null)}
          style={{ marginBottom: 12 }}
          message={tr('inbox.resyncResult', { locations: summary.locations, versions: summary.versions })}
        />
      ) : null}
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message={tr('inbox.repairHint')} />

      <DataTable<InboxMessage>
        size="small"
        rowKey={(m) => `${m.consumer}:${m.eventId}`}
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: tr('inbox.receivedAt'), dataIndex: 'receivedAt', render: (v: string) => formatDateTime(v) },
          { title: tr('inbox.eventType'), dataIndex: 'eventType', render: (v: string) => <code>{v}</code> },
          { title: tr('inbox.consumer'), dataIndex: 'consumer' },
          {
            title: tr('inbox.status'),
            dataIndex: 'status',
            render: (s: string) => (
              <StatusTag
                status={s === 'PROCESSED' ? 'COMPLETED' : s}
                labels={{ COMPLETED: tr('inbox.statuses.PROCESSED'), [s]: tr(`inbox.statuses.${s}`, { defaultValue: s }) }}
              />
            ),
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
          { title: tr('inbox.processedAt'), dataIndex: 'processedAt', render: (v: string | null) => formatDateTime(v) },
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
