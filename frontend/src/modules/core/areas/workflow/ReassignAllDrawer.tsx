import { useEffect } from 'react';
import { Form, Input, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { isProblem, type UUID } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import { useReassignAll } from './queries';
import { UserPicker } from './common';
import './i18n';

interface FormValues {
  fromUserId: UUID;
  toUserId: UUID;
  reason: string;
}

/** Move every pending task of one user to another (US-CORE-057; leave, departure). */
export function ReassignAllDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation('core-workflow');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const mutation = useReassignAll();

  useEffect(() => {
    if (open) {
      form.resetFields();
      mutation.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const submit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      const taskIds = await mutation.mutateAsync({
        fromUserId: values.fromUserId,
        toUserId: values.toUserId,
        reason: values.reason.trim(),
      });
      fb.done(t('reassign.doneAll', { count: taskIds.length }));
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
      title={t('reassign.titleAll')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      submitLabel={t('tasks.reassign')}
      width={480}
    >
      <Typography.Paragraph type="secondary">{t('reassign.description')}</Typography.Paragraph>
      <Form<FormValues> form={form} layout="vertical">
        <Form.Item name="fromUserId" label={t('reassign.fromUser')} rules={[{ required: true, message: t('reassign.fromUserRequired') }]}>
          <UserPicker />
        </Form.Item>
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
