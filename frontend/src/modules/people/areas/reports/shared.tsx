import { useState } from 'react';
import { Alert, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { StatusTag } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { useLegalEntities } from '../../common';
import type { StatutoryReportStatus, StatutoryReportType, StatutoryReportView } from './api';
import './i18n';

/** Selected legal entity, defaulting to the first one in scope once the lookup arrives (reports need one). */
export function useLegalEntityChoice(): [UUID | undefined, (id: UUID | undefined) => void] {
  const legalEntities = useLegalEntities();
  const [chosen, setChosen] = useState<UUID | undefined>();
  return [chosen ?? legalEntities.data?.[0]?.id, setChosen];
}

const STATUS_COLOURS: Record<StatutoryReportStatus, string> = {
  DRAFT: 'default',
  GENERATED: 'gold',
  SUBMITTED: 'blue',
  ACKNOWLEDGED: 'green',
  CANCELLED: 'default',
};

export function ReportStatusTag({ status }: { status: StatutoryReportStatus }) {
  const { t } = useTranslation('people-reports');
  return (
    <Tag color={STATUS_COLOURS[status] ?? 'default'}>
      {t(`statutory.statuses.${status}`, { defaultValue: status })}
    </Tag>
  );
}

/** Generic status tag with labels from this namespace (inbox statuses). */
export function LabelledStatus({ status, prefix }: { status: string; prefix: string }) {
  const { t } = useTranslation('people-reports');
  return (
    <StatusTag status={status} labels={{ [status]: t(`${prefix}.${status}`, { defaultValue: status }) }} />
  );
}

export function reportTypeLabel(t: (k: string) => string, type: StatutoryReportType) {
  return t(`statutory.types.${type}`);
}

export function periodLabel(r: Pick<StatutoryReportView, 'periodStart' | 'periodEnd'>) {
  return `${formatDate(r.periodStart)} – ${formatDate(r.periodEnd)}`;
}

/** Breakdown map (`{ code: count }`) as tags; codes are shown as the backend sends them. */
export function CountMap({ value }: { value: Record<string, number> | null | undefined }) {
  const entries = Object.entries(value ?? {});
  if (entries.length === 0) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Space size={[4, 4]} wrap>
      {entries.map(([k, n]) => (
        <Tag key={k}>
          {k}: {n}
        </Tag>
      ))}
    </Space>
  );
}

/** Notice whether identifiers on screen are masked (default) or revealed for a sensitive-data holder. */
export function IdentityNotice({ revealed }: { revealed: boolean }) {
  const { t } = useTranslation('people-reports');
  return (
    <Alert
      type={revealed ? 'warning' : 'info'}
      showIcon
      message={revealed ? t('common.revealed') : t('common.masked')}
      style={{ marginBottom: 12 }}
    />
  );
}
