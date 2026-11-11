import { useState } from 'react';
import { Alert, Button, Card, Result, Skeleton, Space } from 'antd';
import { HistoryOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { isProblem } from '@/shared/api';
import {
  AsOfPicker,
  ConfirmButton,
  DescriptionList,
  HistoryDrawer,
  PageHeader,
  ProblemAlert,
  StatusTag,
  useFeedback,
} from '@/shared/components';
import { formatDate, formatValidity, today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { Location, LocationVersion } from './api';
import {
  useCloseLocation,
  useLegalEntity,
  useLocation,
  useLocationHistory,
  useLocationMinWageRegion,
  useRemoveLocationVersion,
} from './queries';
import { LocationForm } from './LocationForm';
import {
  AdminUnitName,
  CloseFromDateModal,
  isPlanned,
  legalEntityLabel,
  MinWageRegionTag,
  PlannedVersionList,
} from './widgets';
import { EMPLOYER_NS } from './i18n';

/** One location as of a date: attributes, min-wage region (via LPS), history, new version, close. */
export function LocationDetailPage({ locationId }: { locationId: string }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const [asOf, setAsOf] = useState(today);
  const [editing, setEditing] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [closing, setClosing] = useState(false);
  const canUpdate = usePermission(P.LOCATION_UPDATE);
  const query = useLocation(locationId, asOf);
  const history = useLocationHistory(locationId, historyOpen || closing);
  const region = useLocationMinWageRegion(locationId, asOf);
  const remove = useRemoveLocationVersion(locationId);

  const loc = query.data;
  if (query.isLoading) return <Skeleton active />;
  if (query.error || !loc) {
    return <Result status="404" title={te('common.notFound')} extra={query.error ? <ProblemAlert error={query.error} /> : null} />;
  }
  const v = loc.current;
  // Once a closure is recorded (closedFrom set, possibly in the future) the location takes no more writes.
  const closureRecorded = !!loc.closedFrom || loc.status === 'CLOSED';

  const removePlanned = async (ver: LocationVersion) => {
    try {
      await remove.mutateAsync({ versionId: ver.versionId, version: loc.version });
      fb.done(te('planned.removed'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader
        title={te('location.detailTitle', { code: loc.code })}
        subTitle={v?.name}
        breadcrumb={[{ label: te('location.title'), to: '/core/locations' }, { label: loc.code }]}
        extra={
          <>
            <Button icon={<HistoryOutlined />} onClick={() => setHistoryOpen(true)}>
              {te('location.history')}
            </Button>
            {!closureRecorded ? (
              <PermissionGate anyOf={P.LOCATION_UPDATE}>
                {v ? (
                  <Button icon={<PlusOutlined />} onClick={() => setEditing(true)}>
                    {te('location.newVersion')}
                  </Button>
                ) : null}
                <Button danger icon={<StopOutlined />} onClick={() => setClosing(true)}>
                  {te('location.close')}
                </Button>
              </PermissionGate>
            ) : null}
          </>
        }
      >
        <AsOfPicker value={asOf} onChange={setAsOf} />
      </PageHeader>

      {!v ? <Alert type="info" showIcon message={te('common.noVersionAtDate')} style={{ marginBottom: 16 }} /> : null}

      <Card title={te('common.details')} style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: t('field.code'), value: <code>{loc.code}</code> },
            { label: t('field.status'), value: <StatusTag status={loc.status} /> },
            { label: te('location.closedFrom'), value: formatDate(loc.closedFrom), hideEmpty: true },
            { label: t('field.legalEntity'), value: <LegalEntityLink id={loc.legalEntityId} asOf={asOf} /> },
            { label: te('common.validity'), value: v ? formatValidity(v.validFrom, v.validTo, t('common.openEnded')) : null },
            { label: te('minWage.region'), value: <MinWageRegionTag data={region.data} error={region.error} /> },
            { label: t('field.version'), value: loc.version },
          ]}
        />
      </Card>
      {v ? (
        <Card title={te('location.section.general')}>
          <DescriptionList
            items={[
              { label: te('location.field.name'), value: v.name },
              { label: te('location.field.locationType'), value: te(`location.type.${v.locationType}`) },
              { label: te('location.field.dependentTaxCode'), value: v.dependentTaxCode ? <code>{v.dependentTaxCode}</code> : null },
              { label: te('location.field.adminUnitCode'), value: <AdminUnitName code={v.adminUnitCode} /> },
              { label: te('location.field.addressLine'), value: v.addressLine },
              { label: te('location.field.remoteArea'), value: v.remoteArea ? te('common.yes') : te('common.no') },
              { label: te('location.field.timeZone'), value: v.timeZone },
              { label: te('location.field.latitude'), value: v.latitude },
              { label: te('location.field.longitude'), value: v.longitude },
              { label: te('location.field.geofenceRadiusM'), value: v.geofenceRadiusM },
            ]}
          />
        </Card>
      ) : null}

      {editing && v ? <LocationForm mode={{ kind: 'change', location: loc }} onClose={() => setEditing(false)} /> : null}
      {closing ? (
        <CloseLocationModal
          location={loc}
          versions={history.data}
          onRemove={removePlanned}
          removing={remove.isPending}
          onDone={() => setClosing(false)}
        />
      ) : null}
      <HistoryDrawer<LocationVersion>
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        title={te('location.history')}
        versions={history.data}
        isLoading={history.isLoading}
        error={history.error}
        renderVersion={(ver) => (
          <Space direction="vertical" size={4}>
            <span>
              {ver.name} · {te(`location.type.${ver.locationType}`)}
              {ver.adminUnitCode ? ` · ${ver.adminUnitCode}` : ''}
              {ver.addressLine ? ` · ${ver.addressLine}` : ''}
            </span>
            {canUpdate && !closureRecorded && isPlanned(ver.validFrom) ? (
              <ConfirmButton
                size="small"
                danger
                title={te('planned.removeConfirm', { date: formatDate(ver.validFrom) })}
                loading={remove.isPending}
                onConfirm={() => removePlanned(ver)}
              >
                {te('planned.remove')}
              </ConfirmButton>
            ) : null}
          </Space>
        )}
      />
    </div>
  );
}

function LegalEntityLink({ id, asOf }: { id: string; asOf: string }) {
  const canView = usePermission(P.LEGAL_ENTITY_VIEW);
  if (!canView) return <code>{id}</code>;
  return <LegalEntityLinkLoaded id={id} asOf={asOf} />;
}

function LegalEntityLinkLoaded({ id, asOf }: { id: string; asOf: string }) {
  const le = useLegalEntity(id, asOf);
  return (
    <Link to="/core/legal-entities/$legalEntityId" params={{ legalEntityId: id }}>
      {le.data ? legalEntityLabel(le.data) : id}
    </Link>
  );
}

/** Close from a date (with the version read); after `future-versions-exist` it lists the planned versions to remove. */
function CloseLocationModal({
  location,
  versions,
  onRemove,
  removing,
  onDone,
}: {
  location: Location;
  versions: LocationVersion[] | undefined;
  onRemove: (v: LocationVersion) => Promise<unknown>;
  removing: boolean;
  onDone: () => void;
}) {
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const close = useCloseLocation(location.id);
  return (
    <CloseFromDateModal
      title={te('location.closeTitle', { code: location.code })}
      hint={te('location.closeHint')}
      okText={te('location.close')}
      error={close.error}
      pending={close.isPending}
      onCancel={onDone}
      onSubmit={async (effectiveFrom) => {
        await close.mutateAsync({ effectiveFrom, version: location.version });
        fb.done(te('location.closed'));
        onDone();
      }}
    >
      {isProblem(close.error, 'future-versions-exist') ? (
        <div style={{ marginBottom: 12 }}>
          <PlannedVersionList
            versions={versions}
            describe={(v) => v.name}
            onRemove={async (v) => {
              await onRemove(v);
              close.reset();
            }}
            removing={removing}
            canRemove
          />
        </div>
      ) : null}
    </CloseFromDateModal>
  );
}
