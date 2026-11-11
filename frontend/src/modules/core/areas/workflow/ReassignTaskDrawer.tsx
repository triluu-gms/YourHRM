import { useEffect } from 'react';
import { Form, Input, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { isProblem, type UUID } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import { useReassignTask } from './queries';
import { UserPicker, processName } from './common';
import type { TaskItem } from './api';
import './i18n';

interface FormValues {
  toUserId: UUID;
  reason: string;
}

/** Reassign one task to another user (US-CORE-057; `core.workflow.reassign`). */
export function ReassignTaskDrawer({ task, open, onClose }: { task: TaskItem | null; open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation('core-workflow');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const mutation = useReassignTask();

  useEffect(() => {
    if (open) {
      form.resetFields();
      mutation.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, task?.taskId]);

  const submit = async () => {
    if (!task) return;
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await mutation.mutateAsync({ taskId: task.taskId, body: { toUserId: values.toUserId, reason: values.reason.trim() } });
      fb.done(t('reassign.done'));
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
      title={t('reassign.title')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      submitLabel={t('tasks.reassign')}
      width={480}
    >
      {task ? (
        <Typography.Paragraph>
          <Typography.Text strong>{processName(task, i18n.language)}</Typography.Text>{' · '}
          <Typography.Text type="secondary">
            {t('tasks.step')} {task.stepNo}
          </Typography.Text>
        </Typography.Paragraph>
      ) : null}
      <Form<FormValues> form={form} layout="vertical">
        <Form.Item name="toUserId" label={t('reassign.toUser')} rules={[{ required: true, message: t('reassign.toUserRequired') }]}>
          <UserPicker />
        </Form.Item>
        <Form.Item name="reason" label={t('reassign.reason')} rules={[{ required: true, whitespace: true, message: t('reassign.reasonRequired') }]}>
          <Input.TextArea rows={3} maxLength={1000} showCount />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
