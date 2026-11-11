import { useMemo, useState } from 'react';
import { Button, Space } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { AsOfPicker, DataTable, PageHeader, StatusTag } from '@/shared/components';
import { today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { Location } from './api';
import { useLegalEntities, useLocations } from './queries';
import { LocationForm } from './LocationForm';
import { AdminUnitName, LegalEntitySelect, legalEntityLabel } from './widgets';
import { EMPLOYER_NS } from './i18n';

/** Locations / branches as of a date (BR-CORE-005), filterable by legal entity. */
export function LocationsPage() {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const navigate = useNavigate();
  const [asOf, setAsOf] = useState(today);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [creating, setCreating] = useState(false);
  const query = useLocations(asOf, legalEntityId);
  const canSeeLegalEntities = usePermission(P.LEGAL_ENTITY_VIEW);
  const legalEntities = useLegalEntities(asOf, undefined, canSeeLegalEntities);
  const leById = useMemo(() => new Map((legalEntities.data ?? []).map((le) => [le.id, le])), [legalEntities.data]);

  return (
    <div>
      <PageHeader
        title={te('location.title')}
        extra={
          <PermissionGate anyOf={P.LOCATION_CREATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {te('location.create')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap>
          <AsOfPicker value={asOf} onChange={setAsOf} />
          {canSeeLegalEntities ? (
            <LegalEntitySelect
              allowClear
              asOf={asOf}
              placeholder={te('location.allLegalEntities')}
              value={legalEntityId}
              onChange={setLegalEntityId}
              style={{ width: 280 }}
            />
          ) : null}
        </Space>
      </PageHeader>

      <DataTable<Location>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        onRow={(row) => ({
          style: { cursor: 'pointer' },
          onClick: () => void navigate({ to: '/core/locations/$locationId', params: { locationId: row.id } }),
        })}
        columns={[
          {
            title: t('field.code'),
            dataIndex: 'code',
            render: (code: string, row) => (
              <Link to="/core/locations/$locationId" params={{ locationId: row.id }} onClick={(e) => e.stopPropagation()}>
                {code}
              </Link>
            ),
          },
          { title: te('location.field.name'), key: 'name', render: (_, row) => row.current?.name ?? te('common.noVersionAtDate') },
          {
            title: te('location.field.locationType'),
            key: 'type',
            render: (_, row) => (row.current ? te(`location.type.${row.current.locationType}`) : null),
          },
          {
            title: t('field.legalEntity'),
            dataIndex: 'legalEntityId',
            render: (id: UUID) => {
              const le = leById.get(id);
              return le ? legalEntityLabel(le) : <code>{id}</code>;
            },
          },
          { title: te('location.field.adminUnitCode'), key: 'admin', render: (_, row) => <AdminUnitName code={row.current?.adminUnitCode} /> },
          { title: te('location.field.dependentTaxCode'), key: 'dtc', render: (_, row) => <code>{row.current?.dependentTaxCode}</code> },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
        ]}
      />

      {creating ? (
        <LocationForm
          mode={{ kind: 'create', legalEntityId }}
          onClose={() => setCreating(false)}
          onSaved={(loc) => void navigate({ to: '/core/locations/$locationId', params: { locationId: loc.id } })}
        />
      ) : null}
    </div>
  );
}
