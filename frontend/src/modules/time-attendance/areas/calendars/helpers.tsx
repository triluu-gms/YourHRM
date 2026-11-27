import { useState, type ReactNode } from 'react';
import { Form, Input, Modal, Tag } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { ISODate } from '@/shared/api';
import { ProblemAlert } from '@/shared/components';
import { ISO_DATE, localizedName } from '@/shared/format';
import type { DayType } from './api';
import { CALENDARS_NS } from './i18n';

/** Pattern A: the UI asks for the last day (inclusive); the API takes the exclusive end. */
export function toExclusiveEnd(lastDay: Dayjs | null | undefined): ISODate | null {
  return lastDay ? lastDay.add(1, 'day').format(ISO_DATE) : null;
}

export function fromExclusiveEnd(validTo: ISODate | null | undefined): Dayjs | null {
  return validTo ? dayjs(validTo).subtract(1, 'day') : null;
}

export function iso(d: Dayjs | null | undefined): ISODate | null {
  return d ? d.format(ISO_DATE) : null;
}

/** Empty strings from Ant inputs become null; strings are trimmed. */
export function clean(v: string | null | undefined): string | null {
  if (v === undefined || v === null) return null;
  const s = v.trim();
  return s === '' ? null : s;
}

export function useName() {
  const { i18n } = useTranslation();
  return (item: { nameVi?: string | null; nameEn?: string | null } | null | undefined) => localizedName(item, i18n.language);
}

const DAY_TYPE_COLOURS: Record<string, string> = { PUBLIC: 'red', SUBSTITUTE: 'volcano', COMPANY: 'blue' };

export function DayTypeTag({ type }: { type: DayType }) {
  const { t } = useTranslation(CALENDARS_NS);
  return <Tag color={DAY_TYPE_COLOURS[type] ?? 'default'}>{t(`dayType.${type}`, { defaultValue: type })}</Tag>;
}

/** Asks for a mandatory reason, then runs `onSubmit(reason)`; errors stay in the modal. */
export function ReasonModal({
  open,
  title,
  description,
  onClose,
  onSubmit,
  submitting,
  error,
}: {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<unknown>;
  submitting?: boolean;
  error?: unknown;
}) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const [form] = Form.useForm<{ reason: string }>();
  const [failed, setFailed] = useState(false);
  const submit = async () => {
    let values: { reason: string };
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await onSubmit(values.reason.trim());
      setFailed(false);
      onClose();
    } catch {
      setFailed(true);
    }
  };
  return (
    <Modal
      open={open}
      title={title}
      onCancel={() => {
        setFailed(false);
        onClose();
      }}
      onOk={() => void submit()}
      okText={t('action.confirm')}
      cancelText={t('action.cancel')}
      confirmLoading={submitting}
      destroyOnHidden
    >
      {description ? <p>{description}</p> : null}
      {failed && error ? <ProblemAlert error={error} style={{ marginBottom: 12 }} /> : null}
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item
          name="reason"
          label={t('field.reason')}
          rules={[{ required: true, whitespace: true, message: tc('common.reasonRequired') }, { max: 500 }]}
        >
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** Lunar date as dd/MM (leap month marked). */
export function lunarLabel(r: { lunarMonth?: number | null; lunarDay?: number | null; lunarLeapMonth?: boolean }, leapLabel: string) {
  if (!r.lunarMonth || !r.lunarDay) return '';
  return `${String(r.lunarDay).padStart(2, '0')}/${String(r.lunarMonth).padStart(2, '0')}${r.lunarLeapMonth ? ` (${leapLabel})` : ''}`;
}
