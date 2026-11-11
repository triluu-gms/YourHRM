import { useState } from 'react';
import { Button, Drawer, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, PageHeader } from '@/shared/components';
import { formatDateTime, formatNumber } from '@/shared/format';
import type { SupportEventView, SupportSessionView } from './api';
import { useSupportEvents, useSupportSessions } from './queries';
import './i18n';

const shortId = (id: string | null | undefined) => (id ? `${id.slice(0, 8)}…` : '—');

/** Operator support sessions in this tenant and what was done in each (BR-CORE-041, US-CORE-042). Read-only. */
export function SupportSessionsPage() {
  const { t } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const [selected, setSelected] = useState<UUID | null>(null);
  const sessions = useSupportSessions();
  const events = useSupportEvents(selected);

  return (
    <div>
      <PageHeader title={ta('support.title')} subTitle={ta('support.intro')} />

      <DataTable<SupportSessionView>
        rowKey="supportGrantId"
        data={sessions.data}
        isLoading={sessions.isLoading}
        error={sessions.error}
        locale={{ emptyText: ta('support.empty') }}
        columns={[
          {
            title: ta('support.grantId'),
            dataIndex: 'supportGrantId',
            render: (id: string) => (
              <Typography.Text code copyable={{ text: id }}>
                {shortId(id)}
              </Typography.Text>
            ),
          },
          {
            title: ta('support.operator'),
            dataIndex: 'operatorId',
            render: (id: string | null) => (id ? <Typography.Text code copyable={{ text: id }}>{shortId(id)}</Typography.Text> : '—'),
          },
          { title: ta('support.firstSeen'), dataIndex: 'firstSeen', render: (v: string) => formatDateTime(v) },
          { title: ta('support.lastSeen'), dataIndex: 'lastSeen', render: (v: string) => formatDateTime(v) },
          { title: ta('support.requests'), dataIndex: 'requests', align: 'right', render: (n: number) => formatNumber(n) },
          {
            title: ta('support.refused'),
            dataIndex: 'refusedRequests',
            align: 'right',
            render: (n: number) => (n > 0 ? <Typography.Text type="danger">{formatNumber(n)}</Typography.Text> : formatNumber(n)),
          },
          { title: ta('support.changes'), dataIndex: 'changes', align: 'right', render: (n: number) => formatNumber(n) },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, row) => (
              <Button type="link" size="small" onClick={() => setSelected(row.supportGrantId)}>
                {ta('support.events')}
              </Button>
            ),
          },
        ]}
      />

      <Drawer
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={ta('support.eventsTitle', { id: shortId(selected) })}
        width={800}
        destroyOnHidden
      >
        <DataTable<SupportEventView>
          size="small"
          rowKey={(e) => `${e.occurredAt}:${e.action}:${e.objectId ?? ''}`}
          data={events.data}
          isLoading={events.isLoading}
          error={events.error}
          locale={{ emptyText: ta('support.noEvents') }}
          columns={[
            { title: ta('support.occurredAt'), dataIndex: 'occurredAt', render: (v: string) => formatDateTime(v) },
            { title: ta('support.action'), dataIndex: 'action', render: (a: string) => <code>{a}</code> },
            {
              title: ta('support.object'),
              key: 'object',
              render: (_, e) => (e.objectType ? `${e.objectType}${e.objectId ? ` ${shortId(e.objectId)}` : ''}` : '—'),
            },
            {
              title: ta('support.details'),
              dataIndex: 'details',
              render: (d: Record<string, string> | null) =>
                d && Object.keys(d).length > 0 ? (
                  <Typography.Text style={{ fontSize: 12 }}>
                    {Object.entries(d)
                      .map(([k, v]) => `${k}=${v}`)
                      .join(' · ')}
                  </Typography.Text>
                ) : (
                  '—'
                ),
            },
          ]}
        />
      </Drawer>
    </div>
  );
}
