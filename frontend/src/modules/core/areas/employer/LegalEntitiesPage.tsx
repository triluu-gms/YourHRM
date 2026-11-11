import { useState } from 'react';
import { Button, Select, Space, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { AsOfPicker, DataTable, PageHeader, StatusTag } from '@/shared/components';
import { today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { LEGAL_ENTITY_STATUSES, type LegalEntity, type LegalEntityStatus } from './api';
import { useLegalEntities, useLegalEntityUsage } from './queries';
import { LegalEntityForm } from './LegalEntityForm';
import { EMPLOYER_NS } from './i18n';

/** Legal entities as of a date (BR-CORE-002/003): code, legal name, MST, BHXH unit code, tax authority, status. */
export function LegalEntitiesPage() {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const navigate = useNavigate();
  const [asOf, setAsOf] = useState(today);
  const [status, setStatus] = useState<LegalEntityStatus | undefined>();
  const [creating, setCreating] = useState(false);
  const canCreate = usePermission(P.LEGAL_ENTITY_CREATE);
  const query = useLegalEntities(asOf, status);
  const usage = useLegalEntityUsage(canCreate);
  const statusLabels = Object.fromEntries(LEGAL_ENTITY_STATUSES.map((s) => [s, te(`legalEntity.status.${s}`)]));
  const limitReached = usage.data?.limit != null && usage.data.used >= usage.data.limit;

  return (
    <div>
      <PageHeader
        title={te('legalEntity.title')}
        extra={
          <PermissionGate anyOf={P.LEGAL_ENTITY_CREATE}>
            {usage.data ? (
              <Typography.Text type={limitReached ? 'danger' : 'secondary'}>
                {usage.data.limit != null
                  ? te('legalEntity.usage', { used: usage.data.used, limit: usage.data.limit })
                  : te('legalEntity.usageUnlimited', { used: usage.data.used })}
              </Typography.Text>
            ) : null}
            <Button type="primary" icon={<PlusOutlined />} disabled={limitReached} onClick={() => setCreating(true)}>
              {te('legalEntity.create')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap>
          <AsOfPicker value={asOf} onChange={setAsOf} />
          <Select<LegalEntityStatus>
            allowClear
            aria-label={te('common.status')}
            placeholder={te('legalEntity.allStatuses')}
            style={{ width: 200 }}
            value={status}
            onChange={setStatus}
            options={LEGAL_ENTITY_STATUSES.map((s) => ({ value: s, label: statusLabels[s] }))}
          />
        </Space>
      </PageHeader>

      <DataTable<LegalEntity>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        onRow={(row) => ({
          style: { cursor: 'pointer' },
          onClick: () => void navigate({ to: '/core/legal-entities/$legalEntityId', params: { legalEntityId: row.id } }),
        })}
        columns={[
          {
            title: t('field.code'),
            dataIndex: 'code',
            render: (code: string, row) => (
              <Link
                to="/core/legal-entities/$legalEntityId"
                params={{ legalEntityId: row.id }}
                onClick={(e) => e.stopPropagation()}
              >
                {code}
              </Link>
            ),
          },
          { title: te('legalEntity.field.legalNameVi'), key: 'name', render: (_, row) => row.current?.legalNameVi ?? te('common.noVersionAtDate') },
          { title: te('legalEntity.field.shortName'), key: 'short', render: (_, row) => row.current?.shortName },
          { title: te('legalEntity.field.taxCode'), key: 'mst', render: (_, row) => <code>{row.current?.taxCode}</code> },
          { title: te('legalEntity.field.siUnitCode'), key: 'si', render: (_, row) => row.current?.siUnitCode },
          { title: te('legalEntity.field.taxAuthorityCode'), key: 'ta', render: (_, row) => row.current?.taxAuthorityCode },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} labels={statusLabels} /> },
        ]}
      />

      {creating ? (
        <LegalEntityForm
          mode={{ kind: 'create' }}
          onClose={() => setCreating(false)}
          onSaved={(le) => void navigate({ to: '/core/legal-entities/$legalEntityId', params: { legalEntityId: le.id } })}
        />
      ) : null}
    </div>
  );
}
