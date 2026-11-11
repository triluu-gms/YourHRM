import { useState } from 'react';
import { Alert, Button, Card, Result, Skeleton, Space } from 'antd';
import { HistoryOutlined, PlusOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import {
  AsOfPicker,
  ConfirmButton,
  DataTable,
  DescriptionList,
  HistoryDrawer,
  PageHeader,
  ProblemAlert,
  StatusTag,
  useFeedback,
} from '@/shared/components';
import { formatDate, formatValidity, today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { LEGAL_ENTITY_STATUSES, type LegalEntityStatus, type LegalEntityVersion, type Location } from './api';
import {
  useChangeLegalEntityStatus,
  useLegalEntity,
  useLegalEntityHistory,
  useLegalEntityMinWageRegion,
  useLocations,
} from './queries';
import { LegalEntityForm } from './LegalEntityForm';
import { AdminUnitName, MinWageRegionTag } from './widgets';
import { EMPLOYER_NS } from './i18n';

/** One legal entity as of a date: statutory attributes, min-wage region, history, new version, status, locations. */
export function LegalEntityDetailPage({ legalEntityId }: { legalEntityId: string }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const [asOf, setAsOf] = useState(today);
  const [editing, setEditing] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const query = useLegalEntity(legalEntityId, asOf);
  const history = useLegalEntityHistory(legalEntityId, historyOpen);
  const region = useLegalEntityMinWageRegion(legalEntityId, asOf);
  const changeStatus = useChangeLegalEntityStatus(legalEntityId);
  const statusLabels = Object.fromEntries(LEGAL_ENTITY_STATUSES.map((s) => [s, te(`legalEntity.status.${s}`)]));

  const le = query.data;
  if (query.isLoading) return <Skeleton active />;
  if (query.error || !le) {
    return <Result status="404" title={te('common.notFound')} extra={query.error ? <ProblemAlert error={query.error} /> : null} />;
  }
  const v = le.current;

  const setStatus = async (status: LegalEntityStatus) => {
    try {
      await changeStatus.mutateAsync({ status, version: le.version });
      fb.done(te(status === 'ACTIVE' ? 'legalEntity.activated' : 'legalEntity.dissolved'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader
        title={te('legalEntity.detailTitle', { code: le.code })}
        subTitle={v?.legalNameVi}
        breadcrumb={[{ label: te('legalEntity.title'), to: '/core/legal-entities' }, { label: le.code }]}
        extra={
          <>
            <Button icon={<HistoryOutlined />} onClick={() => setHistoryOpen(true)}>
              {te('legalEntity.history')}
            </Button>
            {le.status !== 'DISSOLVED' ? (
              <PermissionGate anyOf={P.LEGAL_ENTITY_UPDATE}>
                {v ? (
                  <Button icon={<PlusOutlined />} onClick={() => setEditing(true)}>
                    {te('legalEntity.newVersion')}
                  </Button>
                ) : null}
                {le.status === 'DRAFT' ? (
                  <ConfirmButton title={te('legalEntity.activateConfirm')} loading={changeStatus.isPending} onConfirm={() => setStatus('ACTIVE')}>
                    {te('legalEntity.activate')}
                  </ConfirmButton>
                ) : null}
                <ConfirmButton danger title={te('legalEntity.dissolveConfirm')} loading={changeStatus.isPending} onConfirm={() => setStatus('DISSOLVED')}>
                  {te('legalEntity.dissolve')}
                </ConfirmButton>
              </PermissionGate>
            ) : null}
          </>
        }
      >
        <AsOfPicker value={asOf} onChange={setAsOf} />
      </PageHeader>

      {changeStatus.error ? <ProblemAlert error={changeStatus.error} style={{ marginBottom: 16 }} /> : null}
      {!v ? <Alert type="info" showIcon message={te('common.noVersionAtDate')} style={{ marginBottom: 16 }} /> : null}

      <Card title={te('common.details')} style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: t('field.code'), value: <code>{le.code}</code> },
            { label: t('field.status'), value: <StatusTag status={le.status} labels={statusLabels} /> },
            { label: te('common.validity'), value: v ? formatValidity(v.validFrom, v.validTo, t('common.openEnded')) : null },
            { label: te('minWage.region'), value: <MinWageRegionTag data={region.data} error={region.error} /> },
            { label: t('field.version'), value: le.version },
          ]}
        />
      </Card>
      {v ? <LegalEntityVersionCard version={v} /> : null}

      <PermissionGate anyOf={P.LOCATION_VIEW}>
        <LegalEntityLocations legalEntityId={le.id} asOf={asOf} />
      </PermissionGate>

      {editing && v ? <LegalEntityForm mode={{ kind: 'change', legalEntity: le }} onClose={() => setEditing(false)} /> : null}
      <HistoryDrawer<LegalEntityVersion>
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        title={te('legalEntity.history')}
        versions={history.data}
        isLoading={history.isLoading}
        error={history.error}
        renderVersion={(ver) => (
          <span>
            {ver.legalNameVi} · <code>{ver.taxCode}</code>
            {ver.siUnitCode ? ` · ${ver.siUnitCode}` : ''}
            {ver.addressLine ? ` · ${ver.addressLine}` : ''}
          </span>
        )}
      />
    </div>
  );
}

function LegalEntityVersionCard({ version: v }: { version: LegalEntityVersion }) {
  const { t: te } = useTranslation(EMPLOYER_NS);
  const f = (k: string) => te(`legalEntity.field.${k}`);
  return (
    <Space direction="vertical" size={16} style={{ width: '100%', marginBottom: 16 }}>
      <Card title={te('legalEntity.section.identity')}>
        <DescriptionList
          items={[
            { label: f('legalNameVi'), value: v.legalNameVi },
            { label: f('legalNameEn'), value: v.legalNameEn },
            { label: f('shortName'), value: v.shortName },
            { label: f('fiscalYearStartMonth'), value: v.fiscalYearStartMonth },
          ]}
        />
      </Card>
      <Card title={te('legalEntity.section.tax')}>
        <DescriptionList
          items={[
            { label: f('taxCode'), value: <code>{v.taxCode}</code> },
            { label: f('siUnitCode'), value: v.siUnitCode },
            { label: f('taxAuthorityCode'), value: v.taxAuthorityCode },
            {
              label: f('pitDeclarationFrequency'),
              value: v.pitDeclarationFrequency ? te(`legalEntity.pitFrequency.${v.pitDeclarationFrequency}`) : null,
            },
            { label: f('oaOdRate'), value: v.oaOdRate != null ? `${Number((v.oaOdRate * 100).toFixed(4))}%` : null },
            { label: f('oaOdDecisionRef'), value: v.oaOdDecisionRef },
            { label: f('oaOdDecisionDocId'), value: v.oaOdDecisionDocId ? <code>{v.oaOdDecisionDocId}</code> : null, hideEmpty: true },
          ]}
        />
      </Card>
      <Card title={te('legalEntity.section.registration')}>
        <DescriptionList
          items={[
            { label: f('businessRegNo'), value: v.businessRegNo },
            { label: f('businessRegDate'), value: formatDate(v.businessRegDate) },
            { label: f('businessRegIssuer'), value: v.businessRegIssuer },
          ]}
        />
      </Card>
      <Card title={te('legalEntity.section.contact')}>
        <DescriptionList
          items={[
            { label: f('phone'), value: v.phone },
            { label: f('email'), value: v.email },
            { label: f('adminUnitCode'), value: <AdminUnitName code={v.adminUnitCode} /> },
            { label: f('addressLine'), value: v.addressLine },
            { label: f('legalRepName'), value: v.legalRepName },
            { label: f('legalRepTitle'), value: v.legalRepTitle },
          ]}
        />
      </Card>
    </Space>
  );
}

function LegalEntityLocations({ legalEntityId, asOf }: { legalEntityId: string; asOf: string }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const query = useLocations(asOf, legalEntityId);
  return (
    <Card title={te('legalEntity.locations')}>
      <DataTable<Location>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: t('field.code'),
            dataIndex: 'code',
            render: (code: string, row) => (
              <Link to="/core/locations/$locationId" params={{ locationId: row.id }}>
                {code}
              </Link>
            ),
          },
          { title: te('location.field.name'), key: 'name', render: (_, row) => row.current?.name },
          {
            title: te('location.field.locationType'),
            key: 'type',
            render: (_, row) => (row.current ? te(`location.type.${row.current.locationType}`) : null),
          },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
        ]}
      />
    </Card>
  );
}
