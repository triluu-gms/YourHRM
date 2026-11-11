import { useMemo, useState } from 'react';
import { Col, DatePicker, Form, Input, InputNumber, Row, Select, TreeSelect, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { ISODate, UUID } from '@/shared/api';
import { usePermission } from '@/shared/auth';
import { FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE, localizedName } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { OrgUnit, OrgUnitAttributes } from './api';
import {
  useChangeOrgUnit,
  useCostCentreLookup,
  useCreateOrgUnit,
  useLocations,
  useOrgTree,
  useOrgUnitTypes,
  useManagerLookup,
} from './queries';
import { applyFieldErrors, blankToNull, CODE_PATTERN } from './formErrors';
import { buildOrgTree, orgUnitLabel, subtreeIds, type OrgTreeNode } from './orgTree';
import { LegalEntitySelect } from './widgets';
import { EMPLOYER_NS } from './i18n';

interface ParentOption {
  value: UUID;
  title: string;
  children: ParentOption[];
}

interface FormValues {
  code?: string;
  date?: Dayjs | null;
  attributes: Partial<OrgUnitAttributes>;
}

export type OrgUnitFormMode =
  | { kind: 'create'; parent?: OrgUnit | null; legalEntityId?: UUID }
  | { kind: 'change'; unit: OrgUnit };

/**
 * Create a unit, or record an effective-dated change of one (rename, re-parent = restructure, manager, location,
 * cost centre…; BR-CORE-010..012). The parent picker shows the tree in effect on the chosen date, limited to the
 * unit's legal entity and without the unit's own subtree. The backend re-validates everything.
 */
export function OrgUnitForm({
  mode,
  onClose,
  onSaved,
}: {
  mode: OrgUnitFormMode;
  onClose: () => void;
  onSaved?: (saved: OrgUnit) => void;
}) {
  const { t, i18n } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateOrgUnit();
  const change = useChangeOrgUnit(mode.kind === 'change' ? mode.unit.id : '');
  const mutation = mode.kind === 'change' ? change : create;

  const initialValues: FormValues =
    mode.kind === 'change'
      ? {
          date: dayjs(),
          attributes: {
            nameVi: mode.unit.nameVi,
            nameEn: mode.unit.nameEn,
            typeId: mode.unit.typeId,
            parentId: mode.unit.parentId,
            legalEntityId: mode.unit.legalEntityId,
            locationId: mode.unit.locationId,
            costCentreId: mode.unit.costCentreId,
            managerUserId: mode.unit.managerUserId,
            sortOrder: mode.unit.sortOrder,
          },
        }
      : {
          attributes: {
            parentId: mode.parent?.id ?? null,
            legalEntityId: mode.parent?.legalEntityId ?? mode.legalEntityId,
            locationId: mode.parent?.locationId ?? null,
            costCentreId: mode.parent?.costCentreId ?? null,
            sortOrder: 0,
          },
        };

  const date = Form.useWatch('date', form);
  const legalEntityId = Form.useWatch(['attributes', 'legalEntityId'], form) ?? initialValues.attributes.legalEntityId;
  const asOf: ISODate = (date ?? dayjs()).format(ISO_DATE);

  const types = useOrgUnitTypes(false);
  const canSeeLocations = usePermission(P.LOCATION_VIEW);
  const locations = useLocations(asOf, legalEntityId, canSeeLocations && !!legalEntityId);
  // Picker lookups need org-unit write access only (not core.cost_centre.view / core.user.view).
  const costCentres = useCostCentreLookup(legalEntityId, asOf);
  const current = mode.kind === 'change' ? mode.unit : null;
  const costCentreOptions = (costCentres.data ?? []).map((c) => ({ value: c.id, label: `${c.code} – ${c.name}` }));
  if (current?.costCentreId && !costCentreOptions.some((o) => o.value === current.costCentreId)) {
    costCentreOptions.unshift({
      value: current.costCentreId,
      label: [current.costCentreCode, current.costCentreName].filter(Boolean).join(' – ') || current.costCentreId,
    });
  }
  const tree = useOrgTree({ asOf });

  const parentTree = useMemo(() => {
    const units = (tree.data ?? []).filter((u) => u.status === 'ACTIVE' && u.legalEntityId === legalEntityId);
    const excluded = mode.kind === 'change' ? subtreeIds(tree.data ?? [], mode.unit.id) : new Set<UUID>();
    const toNode = (n: OrgTreeNode): ParentOption => ({
      value: n.unit.id,
      title: orgUnitLabel(n.unit, i18n.language),
      children: n.children.filter((c) => !excluded.has(c.unit.id)).map(toNode),
    });
    return buildOrgTree(units)
      .filter((n) => !excluded.has(n.unit.id))
      .map(toNode);
  }, [tree.data, legalEntityId, mode, i18n.language]);

  const submit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const a = blankToNull(values.attributes);
    const attributes: OrgUnitAttributes = {
      nameVi: a.nameVi as string,
      nameEn: a.nameEn ?? null,
      typeId: a.typeId as UUID,
      parentId: a.parentId ?? null,
      legalEntityId: a.legalEntityId as UUID,
      locationId: a.locationId ?? null,
      costCentreId: a.costCentreId ?? null,
      managerUserId: a.managerUserId ?? null,
      sortOrder: a.sortOrder ?? null,
    };
    const iso = values.date ? values.date.format(ISO_DATE) : null;
    try {
      const saved =
        mode.kind === 'change'
          ? await change.mutateAsync({ effectiveFrom: iso as string, attributes, version: mode.unit.version })
          : await create.mutateAsync({ code: (values.code ?? '').trim(), validFrom: iso, attributes });
      if (mode.kind === 'change') fb.saved();
      else fb.created();
      onSaved?.(saved);
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  const a = (name: keyof OrgUnitAttributes) => ['attributes', name];
  const title =
    mode.kind === 'change'
      ? te('orgUnit.newVersionTitle', { code: mode.unit.code })
      : mode.parent
        ? te('orgUnit.createChild')
        : te('orgUnit.createRoot');

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={title}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={680}
    >
      {mode.kind === 'change' ? (
        <Typography.Paragraph type="secondary">{te('orgUnit.versionHint')}</Typography.Paragraph>
      ) : null}
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onValuesChange={(changed: Partial<FormValues>) => {
          // A different legal entity invalidates the LE-bound references (event handler, not an effect).
          if (changed.attributes && 'legalEntityId' in changed.attributes) {
            form.setFieldsValue({ attributes: { parentId: null, locationId: null, costCentreId: null } });
          }
        }}
      >
        <Row gutter={16}>
          {mode.kind === 'create' ? (
            <>
              <Col xs={24} md={12}>
                <Form.Item
                  name="code"
                  label={t('field.code')}
                  extra={te('common.codeImmutable')}
                  rules={[
                    { required: true, whitespace: true, message: te('common.codeRequired') },
                    { pattern: CODE_PATTERN, message: te('common.codeInvalid') },
                  ]}
                >
                  <Input autoComplete="off" style={{ fontFamily: 'monospace' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="date" label={t('field.validFrom')} extra={te('common.validFromHint')}>
                  <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </>
          ) : (
            <Col xs={24} md={12}>
              <Form.Item
                name="date"
                label={te('common.effectiveFrom')}
                extra={te('common.effectiveFromHint')}
                rules={[{ required: true, message: te('common.effectiveFromRequired') }]}
              >
                <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          )}
          <Col xs={24} md={12}>
            <Form.Item name={a('nameVi')} label={t('field.nameVi')} rules={[{ required: true, whitespace: true, message: te('common.nameRequired') }, { max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('nameEn')} label={t('field.nameEn')} rules={[{ max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('typeId')} label={te('orgUnit.field.type')} rules={[{ required: true, message: te('orgUnit.typeRequired') }]}>
              <Select
                showSearch
                optionFilterProp="label"
                loading={types.isLoading}
                options={(types.data ?? []).map((ty) => ({ value: ty.id, label: `${ty.code} – ${localizedName(ty, i18n.language)}` }))}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('sortOrder')} label={te('orgUnit.field.sortOrder')}>
              <InputNumber precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('legalEntityId')} label={te('orgUnit.field.legalEntity')} rules={[{ required: true, message: te('common.legalEntityRequired') }]}>
              <LegalEntitySelect asOf={asOf} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('parentId')} label={te('orgUnit.field.parent')} extra={te('orgUnit.noParentHint')}>
              <TreeSelect
                allowClear
                showSearch
                treeNodeFilterProp="title"
                treeDefaultExpandAll
                loading={tree.isLoading}
                disabled={!legalEntityId}
                treeData={parentTree}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('locationId')} label={te('orgUnit.field.location')}>
              {canSeeLocations ? (
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  disabled={!legalEntityId}
                  loading={locations.isLoading}
                  options={(locations.data ?? [])
                    .filter((l) => l.status === 'ACTIVE')
                    .map((l) => ({ value: l.id, label: `${l.code} – ${l.current?.name ?? ''}` }))}
                />
              ) : (
                <UuidInput />
              )}
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('costCentreId')} label={te('orgUnit.field.costCentre')}>
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                disabled={!legalEntityId}
                loading={costCentres.isLoading}
                options={costCentreOptions}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('managerUserId')} label={te('orgUnit.field.manager')}>
              <ManagerSelect currentLabel={current?.managerName} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </FormDrawer>
  );
}

function UuidInput(props: { value?: string | null; onChange?: (v: string) => void; id?: string }) {
  const { t: te } = useTranslation(EMPLOYER_NS);
  return (
    <Input
      id={props.id}
      value={props.value ?? ''}
      onChange={(e) => props.onChange?.(e.target.value)}
      placeholder={te('common.uuidHint')}
      style={{ fontFamily: 'monospace' }}
    />
  );
}

/** Active-user search for the P1 org-unit manager (`manager_user_id`; positions are P2) via the org-unit lookup. */
function ManagerSelect({
  value,
  onChange,
  id,
  currentLabel,
}: {
  value?: UUID | null;
  onChange?: (v: UUID | null) => void;
  id?: string;
  currentLabel?: string | null;
}) {
  const { t: te } = useTranslation(EMPLOYER_NS);
  const [term, setTerm] = useState('');
  const search = useManagerLookup(term);
  const options = (search.data ?? []).map((u) => ({ value: u.id, label: u.name === u.code ? u.code : `${u.name} (${u.code})` }));
  if (value && !options.some((o) => o.value === value)) {
    options.unshift({ value, label: currentLabel || value });
  }
  return (
    <Select
      id={id}
      showSearch
      allowClear
      filterOption={false}
      value={value ?? undefined}
      onSearch={setTerm}
      onChange={(v) => onChange?.(v ?? null)}
      placeholder={te('orgUnit.userSearch')}
      loading={search.isFetching}
      options={options}
    />
  );
}
