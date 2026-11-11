import { useMemo, useState } from 'react';
import { Button, Input, Modal, Select, Space, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { CorePermissions as P } from '../../permissions';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader, useFeedback } from '@/shared/components';
import { formatDateTime, localizedName } from '@/shared/format';
import { processTypesQuery, tasksQuery, useBulkApprove } from './queries';
import { SummaryCell, WorkflowStatusTag, processName } from './common';
import { TaskActionDrawer } from './TaskActionDrawer';
import { ReassignTaskDrawer } from './ReassignTaskDrawer';
import type { TaskItem } from './api';
import './i18n';

/** Unified approval inbox (US-CORE-059): the caller's pending tasks across modules, with bulk approve (US-CORE-055). */
export function TasksPage() {
  const { t, i18n } = useTranslation('core-workflow');
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [processType, setProcessType] = useState<string | undefined>();
  const [selected, setSelected] = useState<UUID[]>([]);
  const [acting, setActing] = useState<TaskItem | null>(null);
  const [reassigning, setReassigning] = useState<TaskItem | null>(null);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkComment, setBulkComment] = useState('');

  const tasks = useQuery(tasksQuery(processType));
  const processTypes = useQuery(processTypesQuery);
  const bulk = useBulkApprove();

  const processOptions = useMemo(() => {
    const fromTasks = new Map<string, { nameVi: string; nameEn?: string | null }>();
    for (const task of tasks.data ?? []) {
      fromTasks.set(task.processTypeCode, { nameVi: task.processNameVi, nameEn: task.processNameEn });
    }
    for (const pt of processTypes.data ?? []) {
      if (!fromTasks.has(pt.code)) fromTasks.set(pt.code, { nameVi: pt.nameVi, nameEn: pt.nameEn });
    }
    return [...fromTasks.entries()].map(([code, n]) => ({ value: code, label: localizedName(n, i18n.language) || code }));
  }, [tasks.data, processTypes.data, i18n.language]);

  const runBulk = async () => {
    try {
      const results = await bulk.mutateAsync({ taskIds: selected, comment: bulkComment.trim() || null });
      const approved = results.filter((r) => r.approved).length;
      fb.done(t('tasks.bulkResult', { approved, total: results.length }));
      const failed = results.filter((r) => !r.approved);
      if (failed.length > 0) {
        Modal.warning({
          title: t('tasks.bulkFailed', { errors: '' }).replace(/:\s*$/, ''),
          content: (
            <ul style={{ paddingLeft: 16 }}>
              {failed.map((r) => (
                <li key={r.taskId}>
                  <code>{r.taskId.slice(0, 8)}</code>: {r.error ?? tc('error.generic')}
                </li>
              ))}
            </ul>
          ),
        });
      }
      setSelected([]);
      setBulkOpen(false);
      setBulkComment('');
    } catch (error) {
      fb.failed(error);
    }
  };

  return (
    <div>
      <PageHeader
        title={t('tasks.title')}
        subTitle={t('tasks.subtitle')}
        extra={
          <Button type="primary" disabled={selected.length === 0} onClick={() => setBulkOpen(true)}>
            {t('tasks.bulkApprove')}
            {selected.length > 0 ? ` (${selected.length})` : ''}
          </Button>
        }
      >
        <Space wrap>
          <Select
            aria-label={t('tasks.processType')}
            allowClear
            placeholder={t('tasks.allProcessTypes')}
            style={{ minWidth: 240 }}
            value={processType}
            onChange={(v) => {
              setProcessType(v);
              setSelected([]);
            }}
            options={processOptions}
          />
          <Button onClick={() => void tasks.refetch()}>{tc('action.refresh')}</Button>
        </Space>
      </PageHeader>

      <DataTable<TaskItem>
        data={tasks.data}
        isLoading={tasks.isLoading}
        error={tasks.error}
        rowKey="taskId"
        locale={{ emptyText: t('tasks.empty') }}
        rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as UUID[]) }}
        columns={[
          {
            title: t('tasks.process'),
            key: 'process',
            render: (_, row) => (
              <div>
                <Typography.Text strong>{processName(row, i18n.language)}</Typography.Text>
                <br />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('tasks.step')} {row.stepNo}
                  {row.stepNameVi ? ` – ${row.stepNameVi}` : ''}
                </Typography.Text>
              </div>
            ),
          },
          { title: t('tasks.requester'), key: 'requester', render: (_, row) => row.requesterName ?? row.requesterUserId },
          { title: t('tasks.summary'), key: 'summary', render: (_, row) => <SummaryCell summary={row.summary} /> },
          { title: t('tasks.submittedAt'), key: 'submittedAt', render: (_, row) => formatDateTime(row.submittedAt) },
          { title: t('tasks.assignedAt'), key: 'assignedAt', render: (_, row) => formatDateTime(row.assignedAt) },
          { title: tc('field.status'), key: 'status', render: (_, row) => <WorkflowStatusTag status={row.instanceStatus} /> },
          {
            title: tc('field.actions'),
            key: 'actions',
            fixed: 'right',
            render: (_, row) => (
              <Space size="small" wrap>
                <Button type="primary" size="small" onClick={() => setActing(row)}>
                  {t('tasks.act')}
                </Button>
                <PermissionGate anyOf={[P.WORKFLOW_REASSIGN, P.WORKFLOW_ADMIN]}>
                  <Button size="small" onClick={() => setReassigning(row)}>
                    {t('tasks.reassign')}
                  </Button>
                </PermissionGate>
                <Link to="/core/workflow/instances/$instanceId" params={{ instanceId: row.instanceId }}>
                  {t('tasks.openRequest')}
                </Link>
              </Space>
            ),
          },
        ]}
      />

      <TaskActionDrawer task={acting} open={acting !== null} onClose={() => setActing(null)} onDone={() => setSelected([])} />
      <ReassignTaskDrawer task={reassigning} open={reassigning !== null} onClose={() => setReassigning(null)} />

      <Modal
        open={bulkOpen}
        title={t('tasks.bulkApproveTitle', { count: selected.length })}
        okText={tc('action.approve')}
        cancelText={tc('action.cancel')}
        confirmLoading={bulk.isPending}
        onOk={() => void runBulk()}
        onCancel={() => setBulkOpen(false)}
      >
        <Typography.Paragraph type="secondary">{t('tasks.bulkApproveDescription')}</Typography.Paragraph>
        <label htmlFor="bulk-comment" style={{ display: 'block', marginBottom: 4 }}>
          {tc('field.comment')}
        </label>
        <Input.TextArea id="bulk-comment" rows={3} maxLength={4000} value={bulkComment} onChange={(e) => setBulkComment(e.target.value)} />
      </Modal>
    </div>
  );
}
