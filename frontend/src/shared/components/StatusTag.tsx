import { Tag } from 'antd';
import { useTranslation } from 'react-i18next';

const COLOURS: Record<string, string> = {
  ACTIVE: 'green',
  APPROVED: 'green',
  COMPLETED: 'green',
  PUBLISHED: 'green',
  DELIVERED: 'green',
  SENT: 'blue',
  QUEUED: 'blue',
  PENDING: 'gold',
  PENDING_APPROVAL: 'gold',
  DRAFT: 'default',
  INACTIVE: 'default',
  CLOSED: 'default',
  CANCELLED: 'default',
  SUSPENDED: 'orange',
  LOCKED: 'orange',
  EXPIRED: 'orange',
  REJECTED: 'red',
  FAILED: 'red',
};

/** Status code → coloured tag with the translated label (`status.<CODE>` in common, falls back to the code). */
export function StatusTag({ status, labels }: { status: string | null | undefined; labels?: Record<string, string> }) {
  const { t } = useTranslation();
  if (!status) return null;
  const label = labels?.[status] ?? t(`status.${status}`, { defaultValue: status });
  return <Tag color={COLOURS[status] ?? 'default'}>{label}</Tag>;
}
