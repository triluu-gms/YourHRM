import { useEffect } from 'react';
import { Alert, Button, DatePicker, Form, Input, Select, Space, Typography } from 'antd';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DescriptionList, FormDrawer, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT } from '@/shared/format';
import type { DayPart, LeaveRequestBody, MyBalance } from './api';
import { usePreviewLeave, useRequestLeave } from './queries';
import { iso, qty } from './shared';
import { ESS_NS } from './i18n';
import './i18n';

interface Values {
  leaveTypeId: UUID;
  startDate: Dayjs;
  endDate?: Dayjs | null;
  startPart: DayPart;
  endPart: DayPart;
  reason?: string;
}

const PARTS: DayPart[] = ['FULL', 'AM', 'PM'];

/**
 * Request leave from ESS: preview the counted days and the remaining balance, then submit (Core workflow `att.LEAVE`).
 * Leave types offered are the ones with a balance (`/me/leave-balances`); SI claim data and attachments are added by
 * HR. Needs `att.leave_request.create` (SELF scope).
 */
export function RequestLeaveDrawer({
  open,
  onClose,
  employmentId,
  balances,
}: {
  open: boolean;
  onClose: () => void;
  employmentId: UUID | undefined;
  balances: MyBalance[];
}) {
  const { t } = useTranslation(ESS_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<Values>();
  const preview = usePreviewLeave();
  const submit = useRequestLeave();

  useEffect(() => {
    if (open) {
      preview.reset();
      submit.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const types = [...new Map(balances.map((b) => [b.leaveTypeId, b])).values()];

  const body = (v: Values): LeaveRequestBody => ({
    leaveTypeId: v.leaveTypeId,
    startDate: iso(v.startDate),
    endDate: v.endDate ? iso(v.endDate) : undefined,
    startPart: v.startPart,
    endPart: v.endPart,
    reason: v.reason?.trim() || undefined,
  });

  const runPreview = async () => {
    if (!employmentId) return;
    const v = await form.validateFields();
    preview.mutate({ employmentId, body: body(v) });
  };

  const onSubmit = async () => {
    if (!employmentId) return;
    const v = await form.validateFields();
    submit.mutate(
      { employmentId, body: body(v) },
      {
        onSuccess: () => {
          fb.done(t('request.submitted'));
          onClose();
        },
      },
    );
  };

  const p = preview.data;
  /** Warnings are codes, some with an argument (`insufficient-balance 2026`). */
  const warningText = (w: string) => {
    const [code, arg] = w.split(' ');
    return t(`request.warningCodes.${code}`, { year: arg ?? '', defaultValue: w });
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('request.title')}
      onSubmit={() => void onSubmit()}
      submitting={submit.isPending}
      submitLabel={t('request.submit')}
      error={submit.error}
      footerExtra={
        <Button onClick={() => void runPreview()} loading={preview.isPending}>
          {t('request.preview')}
        </Button>
      }
    >
      {!employmentId ? <Alert type="warning" showIcon message={t('request.noEmployment')} style={{ marginBottom: 12 }} /> : null}
      <Form<Values>
        form={form}
        layout="vertical"
        initialValues={{ startPart: 'FULL', endPart: 'FULL' }}
        onValuesChange={() => preview.reset()}
      >
        <Form.Item name="leaveTypeId" label={t('request.leaveType')} rules={[{ required: true, message: t('request.leaveTypeRequired') }]}>
          <Select<UUID>
            options={types.map((b) => ({ value: b.leaveTypeId, label: `${b.leaveTypeCode} – ${b.leaveTypeLabel}` }))}
          />
        </Form.Item>
        <Space wrap>
          <Form.Item name="startDate" label={t('request.startDate')} rules={[{ required: true, message: t('request.startDateRequired') }]}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="startPart" label={t('request.startPart')}>
            <Select<DayPart> style={{ width: 150 }} options={PARTS.map((x) => ({ value: x, label: t(`request.parts.${x}`) }))} />
          </Form.Item>
        </Space>
        <Space wrap>
          <Form.Item
            name="endDate"
            label={t('request.endDate')}
            dependencies={['startDate']}
            rules={[
              ({ getFieldValue }) => ({
                validator: (_, v: Dayjs | null | undefined) => {
                  const s = getFieldValue('startDate') as Dayjs | undefined;
                  return !v || !s || !v.isBefore(s, 'day')
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('request.endBeforeStart')));
                },
              }),
            ]}
          >
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="endPart" label={t('request.endPart')}>
            <Select<DayPart> style={{ width: 150 }} options={PARTS.map((x) => ({ value: x, label: t(`request.parts.${x}`) }))} />
          </Form.Item>
        </Space>
        <Form.Item name="reason" label={t('request.reason')}>
          <Input.TextArea rows={3} maxLength={1000} />
        </Form.Item>
      </Form>

      {preview.error ? <ProblemAlert error={preview.error} /> : null}
      {p ? (
        <div>
          <Typography.Title level={5}>{t('request.previewTitle')}</Typography.Title>
          <DescriptionList
            column={1}
            items={[
              { label: t('request.durationDays'), value: qty(p.durationDays) },
              { label: t('request.durationHours'), value: qty(p.durationHours), hideEmpty: true },
              ...Object.entries(p.available).map(([year, n]) => ({
                label: t('request.availableAfter', { year }),
                value: qty(n),
              })),
            ]}
          />
          {p.warnings.length > 0 ? (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 12 }}
              message={t('request.warnings')}
              description={
                <ul style={{ margin: 0, paddingLeft: 16 }}>
                  {p.warnings.map((w) => (
                    <li key={w}>{warningText(w)}</li>
                  ))}
                </ul>
              }
            />
          ) : null}
        </div>
      ) : null}
    </FormDrawer>
  );
}
