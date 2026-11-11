import { Button, Form, InputNumber, Space, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import {
  CostCentreSelect,
  EmployeeSelect,
  GradeSelect,
  JobTitleSelect,
  LocationSelect,
  OrgUnitSelect,
} from '../../common';
import type { AssignmentAttributes, AssignmentView } from './api';
import './i18n';

/** Form shape of `AssignmentAttributes` (nested under `attributes`). */
export interface AttributesFormValues {
  orgUnitId: UUID;
  positionId?: UUID | null;
  jobTitleId?: UUID | null;
  gradeId?: UUID | null;
  managerEmploymentId?: UUID | null;
  locationId?: UUID | null;
  costCentreId?: UUID | null;
  costSplit?: { costCentreId: UUID; pct: number }[];
  fte?: number | null;
}

export function attributesFrom(a: AssignmentView | null | undefined): Partial<AttributesFormValues> {
  if (!a) return { fte: 1 };
  return {
    orgUnitId: a.orgUnitId,
    positionId: a.positionId ?? null,
    jobTitleId: a.jobTitleId ?? undefined,
    gradeId: a.gradeId ?? undefined,
    managerEmploymentId: a.managerEmploymentId ?? undefined,
    locationId: a.locationId ?? undefined,
    costCentreId: a.costCentreId ?? undefined,
    costSplit: a.costSplit.map((s) => ({ costCentreId: s.costCentreId, pct: Number(s.pct) })),
    fte: a.fte ?? 1,
  };
}

export function toAttributes(v: AttributesFormValues): AssignmentAttributes {
  return {
    orgUnitId: v.orgUnitId,
    positionId: v.positionId ?? null,
    jobTitleId: v.jobTitleId ?? null,
    gradeId: v.gradeId ?? null,
    managerEmploymentId: v.managerEmploymentId ?? null,
    locationId: v.locationId ?? null,
    costCentreId: v.costCentreId ?? null,
    costSplit: (v.costSplit ?? []).filter((s) => s?.costCentreId),
    fte: v.fte ?? null,
  };
}

/**
 * Assignment attribute fields under `attributes.*` (BR-PPL-031: no pay group, no work schedule). Org unit,
 * location and cost centres are limited to the employment's legal entity; the backend re-checks.
 */
export function AssignmentFields({ legalEntityId, employmentId }: { legalEntityId: UUID; employmentId: UUID }) {
  const { t: tp } = useTranslation('people');
  const { t: te } = useTranslation('people-employment');
  return (
    <>
      <Form.Item name={['attributes', 'orgUnitId']} label={tp('common.orgUnit')} rules={[{ required: true, message: te('assignments.orgUnitRequired') }]}>
        <OrgUnitSelect legalEntityId={legalEntityId} />
      </Form.Item>
      <Form.Item name={['attributes', 'jobTitleId']} label={tp('common.jobTitle')}>
        <JobTitleSelect />
      </Form.Item>
      <Form.Item name={['attributes', 'gradeId']} label={tp('common.grade')}>
        <GradeSelect />
      </Form.Item>
      <Form.Item name={['attributes', 'locationId']} label={tp('common.location')}>
        <LocationSelect legalEntityId={legalEntityId} />
      </Form.Item>
      <Form.Item
        name={['attributes', 'managerEmploymentId']}
        label={tp('common.manager')}
        rules={[
          {
            validator: (_, v) => (v && v === employmentId ? Promise.reject(new Error(te('errors.invalid-manager'))) : Promise.resolve()),
          },
        ]}
      >
        <EmployeeSelect />
      </Form.Item>
      <Form.Item
        name={['attributes', 'fte']}
        label={tp('common.fte')}
        rules={[
          {
            validator: (_, v: number | null | undefined) =>
              v === null || v === undefined || (v > 0 && v <= 1) ? Promise.resolve() : Promise.reject(new Error(te('assignments.fteInvalid'))),
          },
        ]}
      >
        <InputNumber min={0.01} max={1} step={0.1} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name={['attributes', 'costCentreId']} label={te('assignments.costCentre')}>
        <CostCentreSelect legalEntityId={legalEntityId} />
      </Form.Item>
      <Typography.Text strong>{te('assignments.costSplit')}</Typography.Text>
      <Form.List
        name={['attributes', 'costSplit']}
        rules={[
          {
            validator: async (_, shares: { pct?: number }[] | undefined) => {
              const list = (shares ?? []).filter(Boolean);
              if (list.length === 0) return;
              const total = list.reduce((s, x) => s + (Number(x.pct) || 0), 0);
              if (Math.abs(total - 100) > 0.001) throw new Error(te('assignments.pctTotal', { total }));
            },
          },
        ]}
      >
        {(fields, { add, remove }, { errors }) => (
          <div style={{ marginTop: 8, marginBottom: 16 }}>
            {fields.map((f) => (
              <Space key={f.key} align="baseline" style={{ display: 'flex' }}>
                <Form.Item name={[f.name, 'costCentreId']} rules={[{ required: true }]} style={{ width: 280 }}>
                  <CostCentreSelect legalEntityId={legalEntityId} aria-label={te('assignments.costCentre')} />
                </Form.Item>
                <Form.Item name={[f.name, 'pct']} rules={[{ required: true }]}>
                  <InputNumber min={0.01} max={100} addonAfter={te('assignments.pct')} aria-label={te('assignments.pct')} />
                </Form.Item>
                <Button type="text" icon={<DeleteOutlined />} onClick={() => remove(f.name)} aria-label="remove" />
              </Space>
            ))}
            <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>
              {te('assignments.addShare')}
            </Button>
            <Form.ErrorList errors={errors} />
          </div>
        )}
      </Form.List>
    </>
  );
}
