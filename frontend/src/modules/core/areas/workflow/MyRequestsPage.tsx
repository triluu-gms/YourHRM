import { useState } from 'react';
import { Select, Space } from 'antd';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { DataTable, PageHeader } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import { requestsQuery } from './queries';
import { SubjectLabel, SummaryCell, WorkflowStatusTag, processName } from './common';
import type { RequestItem } from './api';
import './i18n';

/** Instance statuses a requester can filter by (`workflow_instance_status_ck`). */
export const REQUEST_STATUSES = [
  'IN_PROGRESS',
  'RETURNED',
  'INFO_REQUESTED',
  'APPROVED',
  'REJECTED',
  'WITHDRAWN',
  'CANCELLED',
] as const;

/** The caller's submitted requests across modules (US-CORE-056), linking to the request detail. */
export function MyRequestsPage() {
  const { t, i18n } = useTranslation('core-workflow');
  const { t: tc } = useTranslation();
  const [status, setStatus] = useState<string | undefined>();
  const requests = useQuery(requestsQuery(status));

  return (
    <div>
      <PageHeader title={t('requests.title')} subTitle={t('requests.subtitle')} />

      <Space style={{ marginBottom: 12 }} wrap>
        <Select<string>
          allowClear
          aria-label={t('requests.status')}
          placeholder={t('requests.allStatuses')}
          style={{ width: 220 }}
          value={status}
          onChange={setStatus}
          options={REQUEST_STATUSES.map((s) => ({ value: s, label: <WorkflowStatusTag status={s} /> }))}
        />
      </Space>

      <DataTable<RequestItem>
        rowKey="instanceId"
        data={requests.data}
        isLoading={requests.isLoading}
        error={requests.error}
        locale={{ emptyText: t('requests.empty') }}
        columns={[
          {
            title: t('tasks.process'),
            key: 'process',
            render: (_, row) => (
              <Link to="/core/workflow/instances/$instanceId" params={{ instanceId: row.instanceId }}>
                {processName(row, i18n.language)}
              </Link>
            ),
          },
          {
            title: t('requests.subject'),
            key: 'subject',
            render: (_, row) => (
              <SubjectLabel subjectType={row.subjectType} subjectId={row.subjectId} summary={row.summary} />
            ),
          },
          { title: t('tasks.summary'), key: 'summary', render: (_, row) => <SummaryCell summary={row.summary} /> },
          { title: tc('field.status'), key: 'status', render: (_, row) => <WorkflowStatusTag status={row.status} /> },
          {
            title: t('requests.currentStep'),
            key: 'currentStep',
            align: 'center',
            render: (_, row) => (row.completedAt ? null : (row.currentStep ?? null)),
          },
          { title: t('tasks.submittedAt'), key: 'submittedAt', render: (_, row) => formatDateTime(row.submittedAt) },
          { title: t('requests.completedAt'), key: 'completedAt', render: (_, row) => formatDateTime(row.completedAt) },
        ]}
      />
    </div>
  );
}
