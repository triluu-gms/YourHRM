import { useState } from 'react';
import { Breadcrumb, Card, Skeleton, Space } from 'antd';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { AsOfPicker, DataTable, PageHeader, ProblemAlert, StatusTag } from '@/shared/components';
import { formatValidity, today } from '@/shared/format';
import type { OrgUnit } from './api';
import { useOrgUnit, useOrgUnitAncestors, useOrgUnitDescendants } from './queries';
import { OrgUnitActions, OrgUnitSummary } from './OrgUnitParts';
import { orgUnitLabel } from './orgTree';
import { EMPLOYER_NS } from './i18n';

/** One org unit as of a date: path from the root, attributes, sub-units, history and the unit actions. */
export function OrgUnitDetailPage({ orgUnitId }: { orgUnitId: string }) {
  const { t, i18n } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const navigate = useNavigate();
  const [asOf, setAsOf] = useState(today);
  const query = useOrgUnit(orgUnitId, asOf);
  const ancestors = useOrgUnitAncestors(orgUnitId, asOf);
  const descendants = useOrgUnitDescendants(orgUnitId, asOf);
  const unit = query.data;
  // Ancestors come root first and may include the unit itself; the parent is the one named by parentId.
  const path = (ancestors.data ?? []).filter((a) => a.id !== orgUnitId);
  const parent = unit?.parentId ? path.find((a) => a.id === unit.parentId) : null;

  return (
    <div>
      <PageHeader
        title={te('orgUnit.detailTitle', { code: unit?.code ?? '' })}
        subTitle={unit ? orgUnitLabel(unit, i18n.language) : undefined}
        breadcrumb={[{ label: te('orgUnit.title'), to: '/core/org-units' }, { label: unit?.code ?? orgUnitId }]}
        extra={unit ? <OrgUnitActions unit={unit} /> : null}
      >
        <AsOfPicker value={asOf} onChange={setAsOf} />
      </PageHeader>

      {query.isLoading ? <Skeleton active /> : null}
      {query.error ? <ProblemAlert error={query.error} style={{ marginBottom: 16 }} /> : null}

      {unit ? (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {path.length > 0 ? (
            <Card size="small" title={te('orgUnit.ancestors')}>
              <Breadcrumb
                items={path.map((a) => ({
                  title: (
                    <Link to="/core/org-units/$orgUnitId" params={{ orgUnitId: a.id }}>
                      {orgUnitLabel(a, i18n.language)}
                    </Link>
                  ),
                }))}
              />
            </Card>
          ) : null}
          <Card title={te('common.details')}>
            <OrgUnitSummary unit={unit} parent={parent} />
          </Card>
          <Card title={te('orgUnit.descendants')}>
            <DataTable<OrgUnit>
              data={(descendants.data ?? []).filter((d) => d.id !== orgUnitId)}
              isLoading={descendants.isLoading}
              error={descendants.error}
              rowKey="versionId"
              onRow={(row) => ({
                style: { cursor: 'pointer' },
                onClick: () => void navigate({ to: '/core/org-units/$orgUnitId', params: { orgUnitId: row.id } }),
              })}
              columns={[
                {
                  title: t('field.code'),
                  dataIndex: 'code',
                  render: (code: string, row) => (
                    <Link to="/core/org-units/$orgUnitId" params={{ orgUnitId: row.id }} onClick={(e) => e.stopPropagation()}>
                      {code}
                    </Link>
                  ),
                },
                { title: t('field.name'), key: 'name', render: (_, row) => (i18n.language.startsWith('en') ? row.nameEn || row.nameVi : row.nameVi) },
                { title: te('orgUnit.field.type'), dataIndex: 'typeCode' },
                { title: te('orgUnit.field.depth'), dataIndex: 'depth', align: 'right' },
                { title: te('common.validity'), key: 'validity', render: (_, row) => formatValidity(row.validFrom, row.validTo, t('common.openEnded')) },
                { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
              ]}
            />
          </Card>
        </Space>
      ) : null}
    </div>
  );
}
