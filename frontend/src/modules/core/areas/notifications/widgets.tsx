import { Tag } from 'antd';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { StatusTag } from '@/shared/components';
import { NOTIFICATIONS_NS } from './i18n';

const CATEGORY_COLOURS: Record<string, string> = {
  SECURITY: 'red',
  WORKFLOW: 'blue',
  PAYSLIP: 'green',
  REMINDER: 'gold',
  ANNOUNCEMENT: 'purple',
};

/** Template category (SECURITY, WORKFLOW, PAYSLIP …); unknown categories show their code. */
export function CategoryTag({ category }: { category: string }) {
  const { t } = useTranslation(NOTIFICATIONS_NS);
  return <Tag color={CATEGORY_COLOURS[category] ?? 'default'}>{t(`category.${category}`, { defaultValue: category })}</Tag>;
}

export function channelLabel(t: TFunction<typeof NOTIFICATIONS_NS>, channel: string) {
  return t(`channel.${channel}`, { defaultValue: channel });
}

/** Delivery status with this namespace's labels (BOUNCED, OPENED … are not in the common status list). */
export function DeliveryStatusTag({ status }: { status: string }) {
  const { t } = useTranslation(NOTIFICATIONS_NS);
  const label = t(`status.${status}`, { defaultValue: status });
  if (status === 'BOUNCED') return <Tag color="red">{label}</Tag>;
  if (status === 'OPENED') return <Tag color="green">{label}</Tag>;
  if (status === 'SUPPRESSED') return <Tag color="orange">{label}</Tag>;
  return <StatusTag status={status} labels={{ [status]: label }} />;
}
