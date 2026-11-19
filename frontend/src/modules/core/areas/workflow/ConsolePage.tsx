import { useMemo, useState } from 'react';
import { Button, InputNumber, Select, Space, Tabs, Tooltip, Typography } from 'antd';
import { RedoOutlined, SwapOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ConfirmButton, DataTable, PageHeader, useFeedback } from '@/shared/components';
import { formatDateTime, formatNumber, localizedName } from '@/shared/format';
import { REQUEST_STATUSES } from './MyRequestsPage';
import { consoleCallbacksQuery, consoleInstancesQuery, processTypesQuery, useRetryCallback } from './queries';
import { WorkflowStatusTag } from './common';
import { ReassignAllDrawer } from './ReassignAllDrawer';
import type { CallbackItem, ConsoleItem } from './api';
import './i18n';

/** Admin console (US-CORE-058/061): every request of the tenant, open callbacks and bulk reassignment. */
export function ConsolePage() {
  const { t, i18n } = useTranslation('core-workflow');
  const [reassignOpen, setReassignOpen] = useState(false);
  const processTypes = useQuery(processTypesQuery);
  /** Process type code → bilingual name (`core.process_type`), for the filter and the instance rows. */
  const processNames = useMemo(
    () =>
      new Map(
        (processTypes.data ?? []).map((pt) => [
          pt.code,
          localizedName({ nameVi: pt.nameVi, nameEn: pt.nameEn }, i18n.language) || pt.code,
        ]),
      ),
    [processTypes.data, i18n.language],
  );
  const processOptions = useMemo(
    () => [...processNames].map(([value, label]) => ({ value, label })),
    [processNames],
  );

  return (
    <div>
      <PageHeader
        title={t('console.title')}
        subTitle={t('console.subtitle')}
        extra={
          <Button icon={<SwapOutlined />} onClick={() => setReassignOpen(true)}>
            {t('console.reassignAll')}
          </Button>
        }
      />
      <Tabs
        items={[
          {
            key: 'instances',
            label: t('console.instances'),
            children: <InstancesTab processOptions={processOptions} processNames={processNames} />,
          },
          { key: 'callbacks', label: t('console.callbacks'), children: <CallbacksTab /> },
        ]}
      />
      <ReassignAllDrawer open={reassignOpen} onClose={() => setReassignOpen(false)} />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ instances

function InstancesTab({
  processOptions,
  processNames,
}: {
  processOptions: { value: string; label: string }[];
  processNames: Map<string, string>;
}) {
  const { t } = useTranslation('core-workflow');
  const [status, setStatus] = useState<string | undefined>();
  const [processType, setProcessType] = useState<string | undefined>();
  const [olderThanHours, setOlderThanHours] = useState<number | undefined>();
  const query = useQuery(consoleInstancesQuery({ status, processType, olderThanHours }));

  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select<string>
          allowClear
          aria-label={t('console.status')}
          placeholder={t('console.allStatuses')}
          style={{ width: 200 }}
          value={status}
          onChange={setStatus}
          options={REQUEST_STATUSES.map((s) => ({ value: s, label: <WorkflowStatusTag status={s} /> }))}
        />
        <Select<string>
          allowClear
          showSearch
          optionFilterProp="label"
          aria-label={t('console.processType')}
          placeholder={t('console.allProcessTypes')}
          style={{ minWidth: 240 }}
          value={processType}
          onChange={setProcessType}
          options={processOptions}
        />
        <InputNumber
          aria-label={t('console.olderThan')}
          placeholder={t('console.olderThanPlaceholder')}
          min={1}
          style={{ width: 160 }}
          value={olderThanHours}
          onChange={(v) => setOlderThanHours(v ?? undefined)}
          addonBefore={t('console.olderThan')}
        />
      </Space>

      <DataTable<ConsoleItem>
        rowKey="instanceId"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: t('console.processType'),
            key: 'process',
            render: (_, row) => (
              <Tooltip title={`${row.processTypeCode} · ${row.instanceId}`}>
                <Link to="/core/workflow/instances/$instanceId" params={{ instanceId: row.instanceId }}>
                  {processNames.get(row.processTypeCode) ?? row.processTypeCode}
                </Link>
              </Tooltip>
            ),
          },
          { title: t('console.status'), key: 'status', render: (_, row) => <WorkflowStatusTag status={row.status} /> },
          { title: t('tasks.submittedAt'), key: 'submittedAt', render: (_, row) => formatDateTime(row.submittedAt) },
          { title: t('console.age'), key: 'age', align: 'right', render: (_, row) => formatNumber(Math.round(row.ageHours)) },
          {
            title: t('console.requester'),
            key: 'requester',
            render: (_, row) => (
              <Tooltip title={row.requesterUserId}>
                <span>{row.requesterName ?? row.requesterUserId.slice(0, 8)}</span>
              </Tooltip>
            ),
          },
          {
            title: t('console.callbackStatus'),
            key: 'callback',
            render: (_, row) => (row.callbackStatus ? <WorkflowStatusTag status={row.callbackStatus} /> : null),
          },
          {
            title: t('console.lastError'),
            key: 'lastError',
            render: (_, row) =>
              row.lastError ? (
                <Typography.Text type="danger" ellipsis={{ tooltip: row.lastError }} style={{ maxWidth: 280 }}>
                  {row.lastError}
                </Typography.Text>
              ) : null,
          },
        ]}
      />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ callbacks

function CallbacksTab() {
  const { t } = useTranslation('core-workflow');
  const fb = useFeedback();
  const query = useQuery(consoleCallbacksQuery);
  const retry = useRetryCallback();

  const doRetry = async (callback: CallbackItem) => {
    try {
      await retry.mutateAsync(callback.callbackId);
      fb.done(t('console.retried'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <DataTable<CallbackItem>
      rowKey="callbackId"
      data={query.data}
      isLoading={query.isLoading}
      error={query.error}
      locale={{ emptyText: t('console.noCallbacks') }}
      columns={[
        {
          title: t('console.instances'),
          key: 'instance',
          render: (_, row) => (
            <Link to="/core/workflow/instances/$instanceId" params={{ instanceId: row.instanceId }}>
              {row.instanceId.slice(0, 8)}
            </Link>
          ),
        },
        { title: t('console.targetModule'), dataIndex: 'targetModule' },
        { title: t('console.outcome'), dataIndex: 'outcome', render: (v: string) => <WorkflowStatusTag status={v} /> },
        { title: t('console.callbackStatus'), dataIndex: 'status', render: (v: string) => <WorkflowStatusTag status={v} /> },
        { title: t('console.attempts'), dataIndex: 'attempts', align: 'right' },
        {
          title: t('console.lastError'),
          dataIndex: 'lastError',
          render: (v?: string | null) =>
            v ? (
              <Typography.Text type="danger" ellipsis={{ tooltip: v }} style={{ maxWidth: 280 }}>
                {v}
              </Typography.Text>
            ) : null,
        },
        { title: t('tasks.submittedAt'), dataIndex: 'createdAt', render: (v: string) => formatDateTime(v) },
        {
          key: 'actions',
          fixed: 'right',
          render: (_, row) => (
            <ConfirmButton
              size="small"
              icon={<RedoOutlined />}
              title={t('console.retryConfirm')}
              loading={retry.isPending && retry.variables === row.callbackId}
              aria-label={`${t('console.retry')}: ${row.callbackId.slice(0, 8)}`}
              onConfirm={() => doRetry(row)}
            >
              {t('console.retry')}
            </ConfirmButton>
          ),
        },
      ]}
    />
  );
}
