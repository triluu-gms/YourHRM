import { Card, Empty, Skeleton, Space, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { DataTable, DescriptionList, ProblemAlert } from '@/shared/components';
import { formatDate } from '@/shared/format';
import type { ProfileTabProps } from '../../common';
import type { SeparationView } from './api';
import { groundLabel, NS, SeparationStatusTag, useLabel } from './parts';
import { useEmploymentSeparations, useLiveSeparation, useSeparationReasons } from './queries';

/** Profile tab "Separation": the employment's live separation (if any) and every separation of it. */
export function SeparationTab({ employmentId, profile }: ProfileTabProps) {
  const { t, i18n } = useTranslation(NS);
  const label = useLabel();
  const live = useLiveSeparation(employmentId);
  const history = useEmploymentSeparations(employmentId);
  const reasons = useSeparationReasons(profile.employment.legalEntityId);
  const s = live.data;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" title={t('tab.current')} extra={s ? <Link to="/people/separations/$separationId" params={{ separationId: s.id }}>{t('tab.open')}</Link> : null}>
        {live.isLoading ? (
          <Skeleton active paragraph={{ rows: 2 }} />
        ) : live.error ? (
          <ProblemAlert error={live.error} />
        ) : s ? (
          <DescriptionList
            items={[
              { label: t('field.status'), value: <SeparationStatusTag status={s.status} /> },
              { label: t('field.legalGround'), value: groundLabel(reasons.data, s.legalGroundCode, i18n.language) },
              { label: t('field.separationType'), value: label('type', s.separationType) },
              { label: t('field.lastWorkingDate'), value: formatDate(s.lastWorkingDate) },
              { label: t('field.terminationDate'), value: formatDate(s.terminationDate) },
              { label: t('field.severanceType'), value: label('severanceType', s.severanceType) },
            ]}
          />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('tab.none')} />
        )}
      </Card>

      <div>
        <Typography.Title level={5}>{t('tab.history')}</Typography.Title>
        <DataTable<SeparationView>
          size="small"
          data={history.data}
          isLoading={history.isLoading}
          error={history.error}
          columns={[
            {
              title: t('field.legalGround'),
              dataIndex: 'legalGroundCode',
              render: (code: string | null, row) => (
                <Link to="/people/separations/$separationId" params={{ separationId: row.id }}>
                  {groundLabel(reasons.data, code, i18n.language) ?? label('type', row.separationType) ?? row.id}
                </Link>
              ),
            },
            { title: t('field.status'), dataIndex: 'status', render: (st: SeparationView['status']) => <SeparationStatusTag status={st} /> },
            { title: t('field.lastWorkingDate'), dataIndex: 'lastWorkingDate', render: (v: string | null) => formatDate(v) },
            { title: t('field.terminationDate'), dataIndex: 'terminationDate', render: (v: string | null) => formatDate(v) },
            { title: t('field.createdAt'), dataIndex: 'createdAt', render: (v: string | null) => formatDate(v) },
          ]}
        />
      </div>
    </Space>
  );
}
