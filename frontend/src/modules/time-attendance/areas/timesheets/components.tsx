import { useMemo, useState } from 'react';
import { Alert, Form, Input, Modal, Radio, Select, Space, Tag, TimePicker, Typography, type FormInstance, type SelectProps } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { isProblem, type ISODate, type ISOInstant, type UUID } from '@/shared/api';
import { FormDrawer, ProblemAlert, useFeedback } from '@/shared/components';
import { DISPLAY_TIME_ZONE } from '@/shared/i18n';
import { formatDate } from '@/shared/format';
import { LegalEntitySelect, lookupApi } from '../../common';
import { CORRECTION_TYPES, REQUESTABLE_KINDS, type CodeRow, type EditItem, type RecomputeSummary } from './api';
import { useAttendanceCodes, useEditDays, useRecompute, useRequestCorrection } from './queries';
import { TIMESHEETS_NS } from './i18n';

export const NS = TIMESHEETS_NS;

// ------------------------------------------------------------------------------------------------ helpers

export const shortId = (id: string | null | undefined) => (id ? id.slice(0, 8) : '');

/** An instant shown as HH:mm in Asia/Ho_Chi_Minh. */
export function formatTime(value: ISOInstant | null | undefined): string {
  return value ? dayjs(value).tz(DISPLAY_TIME_ZONE).format('HH:mm') : '';
}

/** Local work date + HH:mm (Asia/Ho_Chi_Minh) → UTC instant; `nextDay` adds a day (night shift out). */
export function toInstant(date: ISODate, time: Dayjs, nextDay = false): ISOInstant {
  const local = dayjs.tz(`${date}T${time.format('HH:mm')}:00`, DISPLAY_TIME_ZONE);
  return (nextDay ? local.add(1, 'day') : local).toISOString();
}

/** In / out pair: an out time not after the in time is the next day (same rule as the timesheet import). */
export function timesToInstants(date: ISODate, inTime?: Dayjs | null, outTime?: Dayjs | null) {
  const inAt = inTime ? toInstant(date, inTime) : null;
  const outNextDay = !!inTime && !!outTime && outTime.format('HH:mm') <= inTime.format('HH:mm');
  const outAt = outTime ? toInstant(date, outTime, outNextDay) : null;
  return { inAt, outAt };
}

export const fmtNum = (v: number | null | undefined, digits = 2) =>
  v === null || v === undefined ? '' : new Intl.NumberFormat('vi-VN', { maximumFractionDigits: digits }).format(v);

export function applyFieldErrors(form: FormInstance, error: unknown): boolean {
  if (!isProblem(error, 'validation-failed') || !error.problem.errors?.length) return false;
  form.setFields(error.problem.errors.map((e) => ({ name: e.field.split('.').pop() ?? e.field, errors: [e.message] })));
  return true;
}

/**
 * Employment id → "NV001 – Name" from one page of People's directory (no lookup by ids exists); falls back to the
 * shortened id. Shares its cache key with the devices area.
 */
export function useEmployeeLabels(): Map<UUID, string> {
  const q = useQuery({
    queryKey: ['att', 'lookup', 'employee-directory'],
    queryFn: () => lookupApi.employees({ size: 200 }),
    staleTime: 5 * 60_000,
  });
  return useMemo(
    () => new Map((q.data?.items ?? []).map((e) => [e.employmentId, `${e.employeeNo} – ${e.fullName}`])),
    [q.data],
  );
}

export function codeLabel(c: CodeRow, language: string) {
  const label = language.startsWith('en') ? c.labelEn || c.labelVi : c.labelVi || c.labelEn;
  return label ? `${c.code} – ${label}` : c.code;
}

// ------------------------------------------------------------------------------------------------ tags

const DAY_COLOURS: Record<string, string> = {
  PRESENT: 'green',
  PARTIAL: 'gold',
  ABSENT: 'red',
  INCOMPLETE: 'orange',
  ON_LEAVE: 'blue',
  HOLIDAY: 'purple',
  REST: 'default',
  SCHEDULED: 'default',
  NOT_EMPLOYED: 'default',
  SUSPENDED: 'default',
};

export function DayStatusTag({ status }: { status: string }) {
  const { t } = useTranslation(NS);
  return <Tag color={DAY_COLOURS[status] ?? 'default'}>{t(`dayStatus.${status}`, { defaultValue: status })}</Tag>;
}

const BLOCKING = new Set(['MISSING_IN', 'MISSING_OUT', 'ABSENT_NO_CODE']);

export function ExceptionTags({ exceptions }: { exceptions: string[] }) {
  const { t } = useTranslation(NS);
  return (
    <Space size={[0, 4]} wrap>
      {exceptions.map((e) => (
        <Tag key={e} color={BLOCKING.has(e) ? 'red' : 'orange'}>
          {t(`exception.${e}`, { defaultValue: e })}
        </Tag>
      ))}
    </Space>
  );
}

/** Code tags coloured with the catalogue colour (when the caller may read the catalogue). */
export function CodeTags({ codes }: { codes: { code: string; qty?: number | null }[] }) {
  const catalogue = useAttendanceCodes();
  const colours = useMemo(() => new Map((catalogue.data ?? []).map((c) => [c.code, c.colorHex ?? undefined])), [catalogue.data]);
  return (
    <Space size={[0, 4]} wrap>
      {codes.map((c, i) => (
        <Tag key={`${c.code}-${i}`} color={colours.get(c.code)}>
          {c.code}
          {c.qty !== null && c.qty !== undefined && c.qty !== 1 ? ` ${fmtNum(c.qty)}` : ''}
        </Tag>
      ))}
    </Space>
  );
}

/** Active attendance codes, optionally limited to some kinds (value = code). */
export function CodeSelect({ kinds, ...props }: Omit<SelectProps<string>, 'options' | 'loading'> & { kinds?: string[] }) {
  const { i18n } = useTranslation();
  const q = useAttendanceCodes();
  const options = (q.data ?? [])
    .filter((c) => c.status === 'ACTIVE' && (!kinds || kinds.includes(c.kind)))
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((c) => ({ value: c.code, label: codeLabel(c, i18n.language) }));
  return <Select<string> showSearch optionFilterProp="label" allowClear loading={q.isLoading} options={options} {...props} />;
}

// ------------------------------------------------------------------------------------------------ admin / bulk edit

interface EditValues {
  code?: string | null;
  inTime?: Dayjs | null;
  outTime?: Dayjs | null;
  reason: string;
}

/** Admin edit of one day or bulk edit of many (BR-ATT-037): code and / or times, mandatory reason. */
export function EditDaysDrawer({ days, onClose }: { days: EditItem[]; onClose: (done: boolean) => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<EditValues>();
  const edit = useEditDays();
  const dates = new Set(days.map((d) => d.workDate));
  const oneDate = dates.size === 1 ? days[0]?.workDate : undefined;
  const [hint, setHint] = useState(false);

  const submit = async () => {
    let v: EditValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    if (!v.code && !v.inTime && !v.outTime) {
      setHint(true);
      return;
    }
    const times = oneDate ? timesToInstants(oneDate, v.inTime, v.outTime) : { inAt: null, outAt: null };
    try {
      const r = await edit.mutateAsync({ days, code: v.code ?? null, ...times, reason: v.reason.trim() });
      fb.done(t('edit.done', { count: r.applied }));
      onClose(true);
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open
      onClose={() => onClose(false)}
      title={days.length === 1 ? `${t('edit.title')} – ${formatDate(days[0]?.workDate)}` : t('edit.titleBulk', { count: days.length })}
      onSubmit={() => void submit()}
      submitting={edit.isPending}
      error={edit.error}
      submitLabel={t('edit.action')}
    >
      <Typography.Paragraph type="secondary">{t('edit.help')}</Typography.Paragraph>
      {hint ? <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('edit.codeOrTime')} /> : null}
      <Form<EditValues> form={form} layout="vertical" onValuesChange={() => setHint(false)}>
        <Form.Item name="code" label={t('field.code')}>
          <CodeSelect />
        </Form.Item>
        {oneDate ? (
          <Space wrap>
            <Form.Item name="inTime" label={t('field.inTime')}>
              <TimePicker format="HH:mm" minuteStep={1} />
            </Form.Item>
            <Form.Item name="outTime" label={t('field.outTime')} extra={t('edit.nextDay')}>
              <TimePicker format="HH:mm" minuteStep={1} />
            </Form.Item>
          </Space>
        ) : (
          <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('edit.timesOneDay')} />
        )}
        <Form.Item name="reason" label={t('field.reason')} rules={[{ required: true, whitespace: true, message: tc('common.required') }]}>
          <Input.TextArea rows={3} maxLength={1000} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ correction request

interface CorrectionValues {
  correctionType: string;
  inTime?: Dayjs | null;
  outTime?: Dayjs | null;
  requestedCode?: string | null;
  reasonCode?: string;
  reasonText?: string;
}

/** A correction request of one day through Core's workflow (BR-ATT-036). Attachments are not offered here. */
export function CorrectionDrawer({ employmentId, workDate, onClose }: { employmentId: UUID; workDate: ISODate; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<CorrectionValues>();
  const request = useRequestCorrection();
  const type = Form.useWatch('correctionType', form);
  const isCode = type === 'CODE_CHANGE';

  const submit = async () => {
    let v: CorrectionValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const times = isCode ? { inAt: null, outAt: null } : timesToInstants(workDate, v.inTime, v.outTime);
    try {
      await request.mutateAsync({
        employmentId,
        body: {
          workDate,
          correctionType: v.correctionType,
          requestedIn: times.inAt,
          requestedOut: times.outAt,
          requestedCode: isCode ? (v.requestedCode ?? null) : null,
          reasonCode: v.reasonCode?.trim() || null,
          reasonText: v.reasonText?.trim() || null,
        },
      });
      fb.done(t('correction.done'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={t('correction.title', { date: formatDate(workDate) })}
      onSubmit={() => void submit()}
      submitting={request.isPending}
      error={request.error}
      submitLabel={t('correction.action')}
    >
      <Typography.Paragraph type="secondary">{t('correction.help')}</Typography.Paragraph>
      <Form<CorrectionValues> form={form} layout="vertical" initialValues={{ correctionType: 'MISSING_PUNCH' }}>
        <Form.Item name="correctionType" label={t('field.correctionType')} rules={[{ required: true, message: tc('common.required') }]}>
          <Select options={CORRECTION_TYPES.map((c) => ({ value: c, label: t(`correctionType.${c}`) }))} />
        </Form.Item>
        {isCode ? (
          <Form.Item name="requestedCode" label={t('field.requestedCode')} rules={[{ required: true, message: tc('common.required') }]}>
            <CodeSelect kinds={REQUESTABLE_KINDS} />
          </Form.Item>
        ) : (
          <Space wrap>
            <Form.Item name="inTime" label={t('field.inTime')}>
              <TimePicker format="HH:mm" />
            </Form.Item>
            <Form.Item name="outTime" label={t('field.outTime')} extra={t('edit.nextDay')}>
              <TimePicker format="HH:mm" />
            </Form.Item>
          </Space>
        )}
        <Form.Item name="reasonCode" label={t('field.reasonCode')}>
          <Input maxLength={40} />
        </Form.Item>
        <Form.Item name="reasonText" label={t('field.reason')}>
          <Input.TextArea rows={3} maxLength={1000} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ recompute

/**
 * Recompute a range (`POST /timesheets/recompute`): either a legal entity or a list of employments. With
 * `employmentIds` the scope can be switched to the legal entity when `allowLegalEntity`.
 */
export function RecomputeModal({
  from,
  to,
  employmentIds,
  legalEntityId: initialLe,
  allowLegalEntity = true,
  onClose,
}: {
  from: ISODate;
  to: ISODate;
  employmentIds?: UUID[];
  legalEntityId?: UUID;
  allowLegalEntity?: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const hasEmployees = !!employmentIds && employmentIds.length > 0;
  const [scope, setScope] = useState<'le' | 'emp'>(hasEmployees ? 'emp' : 'le');
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>(initialLe);
  const [summary, setSummary] = useState<RecomputeSummary | null>(null);
  const recompute = useRecompute();
  const canRun = scope === 'emp' ? hasEmployees : !!legalEntityId;

  const submit = async () => {
    try {
      setSummary(
        await recompute.mutateAsync(
          scope === 'emp' ? { employmentIds, from, to } : { legalEntityId: legalEntityId ?? null, from, to },
        ),
      );
    } catch {
      // shown in the modal
    }
  };

  return (
    <Modal
      open
      title={t('recompute.title')}
      onCancel={onClose}
      onOk={summary ? onClose : () => void submit()}
      okText={summary ? tc('action.close') : t('recompute.run')}
      cancelText={tc('action.cancel')}
      cancelButtonProps={{ style: summary ? { display: 'none' } : undefined }}
      okButtonProps={{ disabled: !summary && !canRun, loading: recompute.isPending }}
      destroyOnHidden
    >
      {recompute.error ? <ProblemAlert error={recompute.error} style={{ marginBottom: 12 }} /> : null}
      <Typography.Paragraph type="secondary">{t('recompute.help')}</Typography.Paragraph>
      <Typography.Paragraph>
        {formatDate(from)} – {formatDate(to)}
      </Typography.Paragraph>
      {hasEmployees && allowLegalEntity ? (
        <Radio.Group value={scope} onChange={(e) => setScope(e.target.value as 'le' | 'emp')} style={{ marginBottom: 12 }}>
          <Radio value="emp">{t('recompute.scopeEmployees', { count: employmentIds.length })}</Radio>
          <Radio value="le">{t('recompute.scopeLegalEntity')}</Radio>
        </Radio.Group>
      ) : hasEmployees ? (
        <Typography.Paragraph>{t('recompute.scopeEmployee')}</Typography.Paragraph>
      ) : null}
      {scope === 'le' ? (
        <LegalEntitySelect aria-label={t('field.legalEntity')} placeholder={t('field.legalEntity')} style={{ width: '100%', marginBottom: 12 }} value={legalEntityId} onChange={setLegalEntityId} />
      ) : null}
      {summary ? (
        <>
          <Alert
            type={summary.failures.length ? 'warning' : 'success'}
            showIcon
            message={t('recompute.done', { written: summary.written, unchanged: summary.unchanged, frozen: summary.frozen, skipped: summary.skipped })}
          />
          {summary.failures.length ? (
            <div style={{ marginTop: 8 }}>
              <Typography.Text strong>{t('recompute.failures')}</Typography.Text>
              <ul>
                {summary.failures.map((f, i) => (
                  <li key={i}>{f}</li>
                ))}
              </ul>
            </div>
          ) : null}
        </>
      ) : null}
    </Modal>
  );
}
