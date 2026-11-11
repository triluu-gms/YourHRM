import { useState } from 'react';
import { Button, Space } from 'antd';
import { ApartmentOutlined, HistoryOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { isProblem } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { ConfirmButton, DescriptionList, HistoryDrawer, StatusTag, useFeedback } from '@/shared/components';
import { formatDate, formatValidity } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { OrgUnit } from './api';
import { useCloseOrgUnit, useOrgUnitHistory, useRemoveOrgUnitVersion } from './queries';
import { OrgUnitForm, type OrgUnitFormMode } from './OrgUnitForm';
import { orgUnitLabel } from './orgTree';
import { CloseFromDateModal, isPlanned, PlannedVersionList } from './widgets';
import { EMPLOYER_NS } from './i18n';

const codeAndName = (code?: string | null, name?: string | null) =>
  code ? (name ? `${code} – ${name}` : code) : (name ?? null);

/** Read-only attributes of one org unit version; names come with the view (no extra lookups). */
export function OrgUnitSummary({ unit, parent }: { unit: OrgUnit; parent?: OrgUnit | null }) {
  const { t, i18n } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const typeName = i18n.language.startsWith('en') ? unit.typeNameEn || unit.typeNameVi : unit.typeNameVi || unit.typeNameEn;
  return (
    <DescriptionList
      column={{ xs: 1, sm: 1, md: 2, lg: 2, xl: 2 }}
      items={[
        { label: t('field.code'), value: <code>{unit.code}</code> },
        { label: t('field.status'), value: <StatusTag status={unit.status} /> },
        { label: t('field.nameVi'), value: unit.nameVi },
        { label: t('field.nameEn'), value: unit.nameEn },
        { label: te('orgUnit.field.type'), value: typeName || unit.typeCode },
        { label: te('orgUnit.field.depth'), value: unit.depth },
        {
          label: te('orgUnit.field.parent'),
          value: unit.parentId ? (parent ? orgUnitLabel(parent, i18n.language) : <code>{unit.parentId}</code>) : te('orgUnit.root'),
        },
        { label: te('orgUnit.field.legalEntity'), value: codeAndName(unit.legalEntityCode, unit.legalEntityName) },
        { label: te('orgUnit.field.location'), value: codeAndName(unit.locationCode, unit.locationName) },
        { label: te('orgUnit.field.costCentre'), value: codeAndName(unit.costCentreCode, unit.costCentreName) },
        { label: te('orgUnit.field.manager'), value: unit.managerName },
        { label: te('orgUnit.field.sortOrder'), value: unit.sortOrder },
        { label: te('common.validity'), value: formatValidity(unit.validFrom, unit.validTo, t('common.openEnded')) },
      ]}
    />
  );
}

/**
 * Actions on one unit: history (with "remove planned version"), create child, new version (rename / move /
 * restructure), close from a date. Every write sends the unit's `version`. Drawers and the close dialog are
 * mounted only while open.
 */
export function OrgUnitActions({ unit, onClosed }: { unit: OrgUnit; onClosed?: () => void }) {
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const [form, setForm] = useState<OrgUnitFormMode | null>(null);
  const [closing, setClosing] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const canUpdate = usePermission(P.ORG_UNIT_UPDATE);
  const history = useOrgUnitHistory(unit.id, historyOpen || closing);
  const remove = useRemoveOrgUnitVersion(unit.id);
  const active = unit.status === 'ACTIVE';

  const removePlanned = async (v: OrgUnit) => {
    try {
      await remove.mutateAsync({ versionId: v.versionId, version: unit.version });
      fb.done(te('planned.removed'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <>
      <Button icon={<HistoryOutlined />} onClick={() => setHistoryOpen(true)}>
        {te('orgUnit.history')}
      </Button>
      {active ? (
        <>
          <PermissionGate anyOf={P.ORG_UNIT_CREATE}>
            <Button icon={<PlusOutlined />} onClick={() => setForm({ kind: 'create', parent: unit })}>
              {te('orgUnit.createChild')}
            </Button>
          </PermissionGate>
          <PermissionGate anyOf={P.ORG_UNIT_UPDATE}>
            <Button icon={<ApartmentOutlined />} onClick={() => setForm({ kind: 'change', unit })}>
              {te('orgUnit.newVersion')}
            </Button>
            <Button danger icon={<StopOutlined />} onClick={() => setClosing(true)}>
              {te('orgUnit.close')}
            </Button>
          </PermissionGate>
        </>
      ) : null}

      {form ? <OrgUnitForm mode={form} onClose={() => setForm(null)} /> : null}
      {closing ? (
        <CloseOrgUnitModal
          unit={unit}
          versions={history.data}
          onRemove={removePlanned}
          removing={remove.isPending}
          onCancel={() => setClosing(false)}
          onDone={() => {
            setClosing(false);
            onClosed?.();
          }}
        />
      ) : null}
      <HistoryDrawer<OrgUnit>
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        title={te('orgUnit.history')}
        versions={history.data}
        isLoading={history.isLoading}
        error={history.error}
        renderVersion={(v) => (
          <Space direction="vertical" size={4}>
            <span>
              {v.nameVi} · {v.typeCode} · <StatusTag status={v.status} />
            </span>
            {canUpdate && isPlanned(v.validFrom) ? (
              <ConfirmButton
                size="small"
                danger
                title={te('planned.removeConfirm', { date: formatDate(v.validFrom) })}
                loading={remove.isPending}
                onConfirm={() => removePlanned(v)}
              >
                {te('planned.remove')}
              </ConfirmButton>
            ) : null}
          </Space>
        )}
      />
    </>
  );
}

/** Close from a date; after `future-versions-exist` it lists the planned versions so they can be removed. */
function CloseOrgUnitModal({
  unit,
  versions,
  onRemove,
  removing,
  onCancel,
  onDone,
}: {
  unit: OrgUnit;
  versions: OrgUnit[] | undefined;
  onRemove: (v: OrgUnit) => Promise<unknown>;
  removing: boolean;
  onCancel: () => void;
  onDone: () => void;
}) {
  const { i18n } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const close = useCloseOrgUnit(unit.id);
  return (
    <CloseFromDateModal
      title={te('orgUnit.closeTitle', { name: orgUnitLabel(unit, i18n.language) })}
      hint={te('orgUnit.closeHint')}
      okText={te('orgUnit.close')}
      error={close.error}
      pending={close.isPending}
      onCancel={onCancel}
      onSubmit={async (effectiveFrom) => {
        await close.mutateAsync({ effectiveFrom, version: unit.version });
        fb.done(te('orgUnit.closed'));
        onDone();
      }}
    >
      {isProblem(close.error, 'future-versions-exist') ? (
        <div style={{ marginBottom: 12 }}>
          <PlannedVersionList
            versions={versions}
            describe={(v) => `${v.nameVi} · ${v.status}`}
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
