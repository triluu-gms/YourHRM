import { useEffect, useMemo } from 'react';
import { Form, Input, Radio, Select, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { isProblem, type UUID } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import { instanceQuery, processTypesQuery, useTaskAction } from './queries';
import { UserPicker, processName, useActionLabel } from './common';
import type { TaskItem, WorkflowAction } from './api';
import './i18n';

/** Actions an assignee can take on a task; REJECT / RETURN / REQUEST_INFO need a comment (BR-CORE-054). */
export const TASK_ACTIONS: WorkflowAction[] = ['APPROVE', 'REJECT', 'RETURN', 'REQUEST_INFO', 'FORWARD', 'COMMENT'];
export const COMMENT_REQUIRED: WorkflowAction[] = ['REJECT', 'RETURN', 'REQUEST_INFO'];

interface FormValues {
  action: WorkflowAction;
  comment?: string;
  returnTo?: string;
  forwardTo?: UUID;
}

/**
 * Per-task action drawer (US-CORE-054/055). The available actions come from the instance detail
 * (`allowedActions`) when it loads, else from the process type's allowed actions, else APPROVE/REJECT.
 */
export function TaskActionDrawer({
  task,
  open,
  onClose,
  onDone,
}: {
  task: TaskItem | null;
  open: boolean;
  onClose: () => void;
  onDone?: () => void;
}) {
  const { t, i18n } = useTranslation('core-workflow');
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const label = useActionLabel();
  const [form] = Form.useForm<FormValues>();
  const action = Form.useWatch('action', form);
  const mutation = useTaskAction();

  const detail = useQuery({ ...instanceQuery(task?.instanceId ?? ''), enabled: open && !!task });
  const processTypes = useQuery({ ...processTypesQuery, enabled: open && !!task });

  const allowed = useMemo<WorkflowAction[]>(() => {
    if (detail.data) {
      const set = new Set(detail.data.allowedActions);
      return TASK_ACTIONS.filter((a) => set.has(a));
    }
    const pt = processTypes.data?.find((p) => p.code === task?.processTypeCode);
    if (pt) {
      const set = new Set(pt.allowedActions);
      return TASK_ACTIONS.filter((a) => a === 'APPROVE' || a === 'REJECT' || set.has(a));
    }
    return ['APPROVE', 'REJECT'];
  }, [detail.data, processTypes.data, task?.processTypeCode]);

  useEffect(() => {
    if (open) {
      form.resetFields();
      mutation.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, task?.taskId]);

  const returnTargets = useMemo(() => {
    const chain = detail.data?.approverChain ?? [];
    const current = task?.stepNo ?? 0;
    return chain
      .filter((s) => s.stepNo < current && s.via !== 'SKIPPED')
      .map((s) => ({ value: s.stepKey, label: t('action.returnToStep', { stepNo: s.stepNo, name: s.nameVi }) }));
  }, [detail.data, task?.stepNo, t]);

  const submit = async () => {
    if (!task) return;
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await mutation.mutateAsync({
        taskId: task.taskId,
        body: {
          action: values.action,
          comment: values.comment?.trim() || null,
          returnTo: values.action === 'RETURN' ? values.returnTo || null : null,
          forwardTo: values.action === 'FORWARD' ? (values.forwardTo ?? null) : null,
        },
      });
      fb.done(t('action.done'));
      onDone?.();
      onClose();
    } catch (error) {
      if (isProblem(error, 'validation-failed') && error.problem.errors) {
        form.setFields(error.problem.errors.map((e) => ({ name: e.field as keyof FormValues, errors: [e.message] })));
      }
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('action.title')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      submitLabel={t('action.submit')}
      width={480}
    >
      {task ? (
        <Typography.Paragraph>
          <Typography.Text strong>{processName(task, i18n.language)}</Typography.Text>
          {' · '}
          <Typography.Text type="secondary">
            {t('tasks.step')} {task.stepNo}
            {task.stepNameVi ? ` – ${task.stepNameVi}` : ''}
          </Typography.Text>
          <br />
          <Typography.Text type="secondary">
            {t('tasks.requester')}: {task.requesterName ?? task.requesterUserId}
          </Typography.Text>
        </Typography.Paragraph>
      ) : null}
      <Form<FormValues> form={form} layout="vertical" initialValues={{ action: 'APPROVE' }} requiredMark="optional">
        <Form.Item name="action" label={t('action.type')} rules={[{ required: true }]}>
          <Radio.Group
            optionType="button"
            buttonStyle="solid"
            options={allowed.map((a) => ({ value: a, label: label(a) }))}
          />
        </Form.Item>
        {action === 'RETURN' ? (
          <Form.Item name="returnTo" label={t('action.returnTo')}>
            <Select
              allowClear
              placeholder={t('action.returnToRequester')}
              options={returnTargets}
              loading={detail.isLoading}
            />
          </Form.Item>
        ) : null}
        {action === 'FORWARD' ? (
          <Form.Item name="forwardTo" label={t('action.forwardTo')} rules={[{ required: true, message: t('action.forwardToRequired') }]}>
            <UserPicker />
          </Form.Item>
        ) : null}
        <Form.Item
          name="comment"
          label={tc('field.comment')}
          rules={[
            {
              validator: (_rule, value: string | undefined) =>
                COMMENT_REQUIRED.includes(action) && !value?.trim()
                  ? Promise.reject(new Error(t('action.commentRequired')))
                  : Promise.resolve(),
            },
          ]}
          extra={COMMENT_REQUIRED.includes(action) ? t('action.commentRequired') : undefined}
        >
          <Input.TextArea rows={4} maxLength={4000} showCount placeholder={t('action.commentPlaceholder')} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
