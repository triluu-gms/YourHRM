import { useMemo, useState } from 'react';
import { Alert, Badge, Button, Card, Form, Input, Modal, Result, Skeleton, Space, Table, Tag, Timeline, Tooltip, Typography } from 'antd';
import { CheckOutlined, PaperClipOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DescriptionList, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { formatDateTime, localizedName } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { instanceQuery, processTypesQuery, useCancelInstance, useInstanceAction } from './queries';
import { SubjectLabel, SummaryList, WorkflowStatusTag, useActionLabel } from './common';
import { TaskActionDrawer } from './TaskActionDrawer';
import type { InstanceDetail, ResolvedStep, TaskItem, TaskView, TrailEntry, WorkflowAction } from './api';
import './i18n';

/** Instance-level actions of the requester (`WorkflowQueryService.allowed`); everything else acts on a task. */
export const REQUESTER_ACTIONS: WorkflowAction[] = ['WITHDRAW', 'PROVIDE_INFO', 'RESUBMIT', 'COMMENT'];

/** Statuses an admin can still cancel (`WorkflowEngine.OPEN`). */
const OPEN_STATUSES: ReadonlySet<string> = new Set(['IN_PROGRESS', 'RETURNED', 'INFO_REQUESTED']);

/** Looks a user id up in `InstanceDetail.userNames`; an id the map misses shows shortened. */
export type UserName = (id: UUID | null | undefined) => string | null;
export function nameLookup(userNames: Record<UUID, string>): UserName {
  return (id) => (id ? (userNames[id] ?? id.slice(0, 8)) : null);
}

/**
 * One request: overview, module summary, approver chain, tasks and the immutable approval trail
 * (US-CORE-056/060/061). Visibility is checked by the backend (requester, participants, `core.workflow.view`).
 */
export function InstanceDetailPage({ instanceId }: { instanceId: string }) {
  const { t, i18n } = useTranslation('core-workflow');
  const query = useQuery(instanceQuery(instanceId));
  const processTypes = useQuery(processTypesQuery);
  const [acting, setActing] = useState(false);

  const detail = query.data;
  const processType = processTypes.data?.find((p) => p.code === detail?.processTypeCode);
  const processLabel = processType
    ? localizedName({ nameVi: processType.nameVi, nameEn: processType.nameEn }, i18n.language)
    : detail?.processTypeCode;

  /** The drawer acts on the caller's pending task; it only needs the row fields it displays. */
  const myTask = useMemo<TaskItem | null>(() => {
    if (!detail?.myPendingTaskId) return null;
    const task = detail.tasks.find((x) => x.taskId === detail.myPendingTaskId);
    if (!task) return null;
    return {
      taskId: task.taskId,
      instanceId: detail.instanceId,
      processTypeCode: detail.processTypeCode,
      processNameVi: processType?.nameVi ?? detail.processTypeCode,
      processNameEn: processType?.nameEn,
      stepNo: task.stepNo,
      stepKey: task.stepKey,
      stepNameVi: detail.approverChain.find((s) => s.stepNo === task.stepNo)?.nameVi,
      requesterUserId: detail.requesterUserId,
      requesterName: detail.userNames[detail.requesterUserId] ?? null,
      submittedAt: detail.submittedAt,
      assignedAt: detail.submittedAt,
      instanceStatus: detail.status,
      summary: detail.summary,
    };
  }, [detail, processType]);

  if (query.isLoading) return <Skeleton active />;
  if (query.error || !detail) {
    return (
      <Result
        status="404"
        title={t('instance.notFound')}
        extra={query.error ? <ProblemAlert error={query.error} /> : null}
      />
    );
  }

  const requesterActions = REQUESTER_ACTIONS.filter((a) => detail.allowedActions.includes(a));
  const name = nameLookup(detail.userNames);

  return (
    <div>
      <PageHeader
        title={t('instance.title', { code: processLabel })}
        subTitle={<WorkflowStatusTag status={detail.status} />}
        breadcrumb={[{ label: t('instance.breadcrumbRequests'), to: '/core/workflow/requests' }, { label: detail.instanceId.slice(0, 8) }]}
        extra={
          <>
            <RequesterActions detail={detail} actions={requesterActions} />
            <CancelButton detail={detail} />
          </>
        }
      />

      {detail.myPendingTaskId ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('instance.myPendingTask')}
          action={
            <Button type="primary" size="small" onClick={() => setActing(true)}>
              {t('instance.actOnTask')}
            </Button>
          }
        />
      ) : null}

      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card title={t('instance.overview')} size="small">
          <DescriptionList
            items={[
              {
                label: t('instance.processType'),
                // the bilingual name of `core.process_type`; the code stays in the tooltip for support
                value: (
                  <Tooltip title={detail.processTypeCode}>
                    <span data-testid="process-type">{processLabel}</span>
                  </Tooltip>
                ),
              },
              { label: t('instance.ownerModule'), value: detail.ownerModule, hideEmpty: true },
              {
                label: t('instance.subject'),
                value: detail.subjectType ? (
                  <SubjectLabel subjectType={detail.subjectType} subjectId={detail.subjectId} summary={detail.summary} />
                ) : null,
                hideEmpty: true,
              },
              { label: t('instance.requester'), value: name(detail.requesterUserId) },
              { label: t('instance.onBehalfOf'), value: name(detail.onBehalfOfUserId), hideEmpty: true },
              { label: t('instance.outcome'), value: detail.outcome ? <WorkflowStatusTag status={detail.outcome} /> : null, hideEmpty: true },
              {
                label: t('instance.currentStep'),
                value: detail.completedAt ? null : detail.currentStep,
                hideEmpty: true,
              },
              { label: t('instance.submittedAt'), value: formatDateTime(detail.submittedAt) },
              { label: t('instance.completedAt'), value: detail.completedAt ? formatDateTime(detail.completedAt) : null, hideEmpty: true },
            ]}
          />
        </Card>

        <Card title={t('instance.summary')} size="small">
          <SummaryList summary={detail.summary} />
        </Card>

        <Card title={t('instance.chain')} size="small">
          <ApproverChain chain={detail.approverChain} currentStep={detail.completedAt ? null : detail.currentStep} name={name} />
        </Card>

        <Card title={t('instance.tasks')} size="small">
          <TasksTable tasks={detail.tasks} name={name} />
        </Card>

        <Card title={t('instance.trail')} size="small" extra={<Typography.Text type="secondary">{t('instance.trailHint')}</Typography.Text>}>
          <Trail trail={detail.trail} name={name} />
        </Card>
      </Space>

      <TaskActionDrawer task={myTask} open={acting} onClose={() => setActing(false)} />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ chain + tasks

function ApproverChain({ chain, currentStep, name }: { chain: ResolvedStep[]; currentStep?: number | null; name: UserName }) {
  const { t, i18n } = useTranslation('core-workflow');
  return (
    <Table<ResolvedStep>
      size="small"
      rowKey="stepNo"
      pagination={false}
      dataSource={chain}
      columns={[
        {
          title: t('instance.chainStep'),
          key: 'step',
          render: (_, s) => (
            <Space size={6}>
              {s.stepNo === currentStep ? <Badge status="processing" /> : null}
              <span>
                {s.stepNo}. {localizedName({ nameVi: s.nameVi, nameEn: s.nameEn }, i18n.language)}
              </span>
            </Space>
          ),
        },
        {
          title: t('instance.chainApprovers'),
          key: 'approvers',
          render: (_, s) =>
            s.approvers.length > 0 ? (
              <Space size={[4, 4]} wrap>
                {s.approvers.map((a) => (
                  <Tag key={a}>{name(a)}</Tag>
                ))}
              </Space>
            ) : (
              <Typography.Text type="secondary">—</Typography.Text>
            ),
        },
        { title: t('instance.chainVia'), dataIndex: 'via', render: (v: string | null) => (v ? <WorkflowStatusTag status={v} /> : null) },
        { title: t('instance.chainReason'), dataIndex: 'reason' },
        {
          title: t('instance.chainAllowReturn'),
          dataIndex: 'allowReturn',
          align: 'center',
          render: (v: boolean) => (v ? <CheckOutlined aria-label={t('instance.chainAllowReturn')} /> : null),
        },
      ]}
    />
  );
}

function TasksTable({ tasks, name }: { tasks: TaskView[]; name: UserName }) {
  const { t } = useTranslation('core-workflow');
  const { t: tc } = useTranslation();
  return (
    <Table<TaskView>
      size="small"
      rowKey="taskId"
      pagination={false}
      dataSource={tasks}
      columns={[
        { title: t('tasks.step'), dataIndex: 'stepNo', width: 70 },
        { title: t('instance.taskAssignee'), key: 'assignee', render: (_, task) => name(task.assigneeUserId) },
        {
          title: t('instance.taskOriginalAssignee'),
          key: 'original',
          render: (_, task) =>
            task.originalAssigneeUserId && task.originalAssigneeUserId !== task.assigneeUserId
              ? name(task.originalAssigneeUserId)
              : null,
        },
        { title: t('instance.taskAssignedVia'), dataIndex: 'assignedVia', render: (v: string | null) => (v ? <WorkflowStatusTag status={v} /> : null) },
        { title: tc('field.status'), dataIndex: 'status', render: (v: string) => <WorkflowStatusTag status={v} /> },
        { title: t('instance.outcome'), dataIndex: 'outcome', render: (v: string | null) => (v ? <WorkflowStatusTag status={v} /> : null) },
        { title: t('instance.taskActedBy'), key: 'actedBy', render: (_, task) => name(task.actedByUserId) },
        { title: t('instance.taskActedAt'), dataIndex: 'actedAt', render: (v: string | null) => formatDateTime(v) },
        { title: tc('field.comment'), dataIndex: 'comment', ellipsis: true },
      ]}
    />
  );
}

// ------------------------------------------------------------------------------------------------ trail

function Trail({ trail, name }: { trail: TrailEntry[]; name: UserName }) {
  const { t } = useTranslation('core-workflow');
  const label = useActionLabel();
  if (trail.length === 0) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Timeline
      items={trail.map((e) => ({
        children: (
          <div data-testid="trail-entry">
            <Space size={8} wrap>
              <Typography.Text strong>{label(e.action)}</Typography.Text>
              <Typography.Text>{e.actorName ?? name(e.actorUserId) ?? t('instance.trailSystem')}</Typography.Text>
              {e.onBehalfOfUserId ? (
                <Typography.Text type="secondary">
                  {t('instance.trailOnBehalf', { user: name(e.onBehalfOfUserId) })}
                </Typography.Text>
              ) : null}
              {e.stepNo ? <Tag>{t('instance.trailStep', { stepNo: e.stepNo })}</Tag> : null}
              {e.fromStatus && e.toStatus ? (
                <Typography.Text type="secondary">{t('instance.trailTransition', { from: e.fromStatus, to: e.toStatus })}</Typography.Text>
              ) : null}
            </Space>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {formatDateTime(e.occurredAt)}
                {e.channel ? ` · ${e.channel}` : ''}
              </Typography.Text>
            </div>
            {e.comment ? <Typography.Paragraph style={{ margin: '4px 0 0', whiteSpace: 'pre-line' }}>{e.comment}</Typography.Paragraph> : null}
            {e.attachmentIds.length > 0 ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                <PaperClipOutlined /> {t('instance.trailAttachments', { count: e.attachmentIds.length })}
              </Typography.Text>
            ) : null}
          </div>
        ),
      }))}
    />
  );
}

// ------------------------------------------------------------------------------------------------ requester actions

function RequesterActions({ detail, actions }: { detail: InstanceDetail; actions: WorkflowAction[] }) {
  const { t } = useTranslation('core-workflow');
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const label = useActionLabel();
  const [action, setAction] = useState<WorkflowAction | null>(null);
  const [form] = Form.useForm<{ comment?: string }>();
  const mutation = useInstanceAction();

  const run = async () => {
    if (!action) return;
    let values: { comment?: string };
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await mutation.mutateAsync({
        instanceId: detail.instanceId,
        body: { action, comment: values.comment?.trim() || null },
      });
      fb.done(t('instance.actionDone'));
      setAction(null);
      form.resetFields();
    } catch (e) {
      fb.failed(e);
    }
  };

  if (actions.length === 0) return null;
  return (
    <>
      {actions.map((a) => (
        <Button
          key={a}
          onClick={() => {
            form.resetFields();
            mutation.reset();
            setAction(a);
          }}
        >
          {label(a)}
        </Button>
      ))}
      <Modal
        open={action !== null}
        title={action ? t('instance.actionTitle', { action: label(action) }) : null}
        okText={action ? label(action) : undefined}
        cancelText={tc('action.cancel')}
        confirmLoading={mutation.isPending}
        onOk={() => void run()}
        onCancel={() => setAction(null)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="comment"
            label={tc('field.comment')}
            rules={action === 'COMMENT' ? [{ required: true, whitespace: true, message: t('action.commentRequired') }] : []}
          >
            <Input.TextArea rows={4} maxLength={4000} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

// ------------------------------------------------------------------------------------------------ admin cancel

function CancelButton({ detail }: { detail: InstanceDetail }) {
  const { t } = useTranslation('core-workflow');
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ reason: string }>();
  const mutation = useCancelInstance();

  if (!OPEN_STATUSES.has(detail.status)) return null;

  const run = async () => {
    let values: { reason: string };
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await mutation.mutateAsync({ instanceId: detail.instanceId, body: { reason: values.reason.trim() } });
      fb.done(t('instance.cancelled'));
      setOpen(false);
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <PermissionGate anyOf={P.WORKFLOW_ADMIN}>
      <Button
        danger
        onClick={() => {
          form.resetFields();
          mutation.reset();
          setOpen(true);
        }}
      >
        {t('instance.cancel')}
      </Button>
      <Modal
        open={open}
        title={t('instance.cancelTitle')}
        okText={t('instance.cancelTitle')}
        okButtonProps={{ danger: true }}
        cancelText={tc('action.cancel')}
        confirmLoading={mutation.isPending}
        onOk={() => void run()}
        onCancel={() => setOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="reason"
            label={t('instance.cancelReason')}
            rules={[{ required: true, whitespace: true, message: t('instance.cancelReasonRequired') }]}
          >
            <Input.TextArea rows={3} maxLength={1000} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </PermissionGate>
  );
}
