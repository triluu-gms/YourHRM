import { useState } from 'react';
import { Alert, Checkbox, DatePicker, Form, Input, List, Select, Space, Tag, TimePicker } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { ApiProblem, type UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { FormDrawer, StatusTag } from '@/shared/components';
import { DATE_FORMAT, formatNumber, ISO_DATE } from '@/shared/format';
import { DISPLAY_TIME_ZONE } from '@/shared/i18n';
import { ATT_NS, EmployeeSelect, legalEntityLabel, useLegalEntities } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import { CONSENT_METHODS, type CreateOvertimeRequest, type Finding, type OvertimeOutcome, type OvertimeStatus } from './api';
import { OVERTIME_NS } from './i18n';

export const NS = OVERTIME_NS;

/** Minutes → "2,5 giờ" (hours with up to 2 decimals, vi-VN number format). */
export function useHours() {
  const { t } = useTranslation(ATT_NS);
  return (minutes: number | null | undefined) =>
    minutes === null || minutes === undefined ? '' : t('common.hours', { value: Math.round((minutes / 60) * 100) / 100 });
}

/** Local HH:mm of an instant (Asia/Ho_Chi_Minh). */
export function formatTime(instant: string | null | undefined): string {
  return instant ? dayjs(instant).tz(DISPLAY_TIME_ZONE).format('HH:mm') : '';
}

export function OvertimeStatusTag({ status }: { status: OvertimeStatus | string | null | undefined }) {
  const { t } = useTranslation(NS);
  if (!status) return null;
  return <StatusTag status={status} labels={{ [status]: t(`status.${status}`, { defaultValue: status }) }} />;
}

export function CategoryLabel({ code }: { code: string }) {
  const { t } = useTranslation(NS);
  return (
    <Space size={4}>
      <Tag>{code}</Tag>
      {t(`category.${code}`, { defaultValue: code })}
    </Space>
  );
}

export function LegalEntityName({ id }: { id: UUID | null | undefined }) {
  const { i18n } = useTranslation();
  const q = useLegalEntities();
  if (!id) return null;
  const le = q.data?.find((x) => x.id === id);
  return <>{le ? legalEntityLabel(le, i18n.language) : id}</>;
}

/** Findings of a create / submit (`Finding`), BLOCK first; labels by code with the backend message as fallback. */
export function FindingsList({ findings }: { findings: Finding[] }) {
  const { t } = useTranslation(NS);
  if (findings.length === 0) return <span>{t('requests.noFindings')}</span>;
  const sorted = [...findings].sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'BLOCK' ? -1 : 1));
  return (
    <List
      size="small"
      dataSource={sorted}
      renderItem={(f) => (
        <List.Item>
          <Space>
            <Tag color={f.severity === 'BLOCK' ? 'red' : 'gold'}>{t(`severity.${f.severity}`)}</Tag>
            <span>{t(`finding.${f.code}`, { defaultValue: f.message })}</span>
          </Space>
        </List.Item>
      )}
    />
  );
}

/** Findings carried by a 422 of submit / create (`problem.findings`). */
export function findingsOf(error: unknown): Finding[] {
  if (error instanceof ApiProblem && Array.isArray(error.problem.findings)) return error.problem.findings as Finding[];
  return [];
}

// ------------------------------------------------------------------------------------------------ create drawer

interface CreateForm {
  employmentIds: UUID[];
  workDate: Dayjs;
  startTime: Dayjs;
  endTime: Dayjs;
  reason?: string;
  consent?: boolean;
  consentMethod?: string;
  submit?: boolean;
  capOverrideReason?: string;
}

/** Create one request per employment (single or a manager's group request, BR-ATT-040); optional submit at once. */
export function CreateOvertimeDrawer({
  open,
  employmentId,
  onClose,
  submit,
  pending,
  error,
}: {
  open: boolean;
  employmentId?: UUID;
  onClose: () => void;
  submit: (body: CreateOvertimeRequest) => Promise<OvertimeOutcome[]>;
  pending?: boolean;
  error?: unknown;
}) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [form] = Form.useForm<CreateForm>();
  const consent = Form.useWatch('consent', form);

  const onSubmit = async () => {
    const v = await form.validateFields().catch(() => null);
    if (!v) return;
    try {
      await submit({
        employmentIds: v.employmentIds,
        workDate: v.workDate.format(ISO_DATE),
        startTime: v.startTime.format('HH:mm'),
        endTime: v.endTime.format('HH:mm'),
        reason: v.reason || null,
        consent: !!v.consent,
        consentMethod: v.consent ? ((v.consentMethod as CreateOvertimeRequest['consentMethod']) ?? null) : null,
        submit: !!v.submit,
        capOverrideReason: v.capOverrideReason || null,
      });
      onClose();
    } catch {
      // shown through `error`
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={t('requests.createTitle')} onSubmit={() => void onSubmit()} submitting={pending} error={error}>
      <Form<CreateForm>
        form={form}
        layout="vertical"
        preserve={false}
        initialValues={{ employmentIds: employmentId ? [employmentId] : [], workDate: dayjs(), consent: false, submit: false }}
      >
        <Form.Item name="employmentIds" label={t('field.employees')} rules={[{ required: true, message: tc('common.required') }]}>
          <EmployeeSelect mode="multiple" aria-label={t('field.employees')} />
        </Form.Item>
        <Form.Item name="workDate" label={t('field.workDate')} rules={[{ required: true, message: tc('common.required') }]}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} aria-label={t('field.workDate')} />
        </Form.Item>
        <Space style={{ display: 'flex' }} align="start">
          <Form.Item name="startTime" label={t('field.startTime')} rules={[{ required: true, message: tc('common.required') }]}>
            <TimePicker format="HH:mm" minuteStep={5} aria-label={t('field.startTime')} />
          </Form.Item>
          <Form.Item name="endTime" label={t('field.endTime')} rules={[{ required: true, message: tc('common.required') }]}>
            <TimePicker format="HH:mm" minuteStep={5} aria-label={t('field.endTime')} />
          </Form.Item>
        </Space>
        <Form.Item name="reason" label={t('field.reason')}>
          <Input.TextArea rows={2} maxLength={500} />
        </Form.Item>
        <Form.Item name="consent" valuePropName="checked">
          <Checkbox>{t('field.consent')}</Checkbox>
        </Form.Item>
        {consent ? (
          <Form.Item name="consentMethod" label={t('field.consentMethod')} rules={[{ required: true, message: tc('common.required') }]}>
            <Select aria-label={t('field.consentMethod')} options={CONSENT_METHODS.map((m) => ({ value: m, label: t(`consentMethod.${m}`) }))} />
          </Form.Item>
        ) : null}
        <Form.Item name="submit" valuePropName="checked">
          <Checkbox>{t('field.submitNow')}</Checkbox>
        </Form.Item>
        <PermissionGate anyOf={P.OVERTIME_CAP_OVERRIDE}>
          <Form.Item name="capOverrideReason" label={t('field.capOverrideReason')} extra={t('field.capOverrideHint')}>
            <Input.TextArea rows={2} maxLength={500} />
          </Form.Item>
        </PermissionGate>
        {findingsOf(error).length > 0 ? (
          <Alert type="warning" showIcon message={t('detail.findings')} description={<FindingsList findings={findingsOf(error)} />} />
        ) : null}
      </Form>
    </FormDrawer>
  );
}

/** Controlled date range of a list page; default = current month. */
export function useMonthRange() {
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().startOf('month'), dayjs().endOf('month')]);
  return {
    range,
    setRange,
    params: { from: range[0].format(ISO_DATE), to: range[1].format(ISO_DATE) },
  };
}

export const hoursNumber = (minutes: number) => formatNumber(Math.round((minutes / 60) * 100) / 100);
