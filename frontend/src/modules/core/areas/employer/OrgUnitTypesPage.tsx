import { useState } from 'react';
import { Button, Checkbox, Form, Input, InputNumber, Switch } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { localizedName } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { OrgUnitType } from './api';
import { useCreateOrgUnitType, useOrgUnitTypes, useUpdateOrgUnitType } from './queries';
import { applyFieldErrors } from './formErrors';
import { EMPLOYER_NS } from './i18n';

/** Mirrors `OrgUnitTypeController.CreateTypeRequest` `@Pattern`. */
const TYPE_CODE_PATTERN = /^[A-Z0-9_]{2,32}$/;

/** Org unit types (BR-CORE-010, US-CORE-010): list, create, rename / reorder / (de)activate. */
export function OrgUnitTypesPage() {
  const { t, i18n } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const [includeInactive, setIncludeInactive] = useState(false);
  const [form, setForm] = useState<{ type: OrgUnitType | null } | null>(null);
  const canUpdate = usePermission(P.ORG_UNIT_TYPE_UPDATE);
  const query = useOrgUnitTypes(includeInactive);

  return (
    <div>
      <PageHeader
        title={te('orgUnitType.title')}
        extra={
          <PermissionGate anyOf={P.ORG_UNIT_TYPE_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ type: null })}>
              {te('orgUnitType.create')}
            </Button>
          </PermissionGate>
        }
      >
        <Checkbox checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)}>
          {te('orgUnitType.includeInactive')}
        </Checkbox>
      </PageHeader>

      <DataTable<OrgUnitType>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.name'), key: 'name', render: (_, row) => localizedName(row, i18n.language) },
          { title: te('orgUnitType.field.levelHint'), dataIndex: 'levelHint', align: 'right' },
          { title: te('orgUnitType.field.sortOrder'), dataIndex: 'sortOrder', align: 'right' },
          { title: t('field.status'), dataIndex: 'active', render: (a: boolean) => <StatusTag status={a ? 'ACTIVE' : 'INACTIVE'} /> },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, row: OrgUnitType) => (
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      aria-label={`${t('action.edit')} ${row.code}`}
                      onClick={() => setForm({ type: row })}
                    />
                  ),
                },
              ]
            : []),
        ]}
      />

      {form ? <OrgUnitTypeForm type={form.type} onClose={() => setForm(null)} /> : null}
    </div>
  );
}

interface FormValues {
  code: string;
  nameVi: string;
  nameEn: string;
  levelHint?: number | null;
  sortOrder?: number | null;
  active: boolean;
}

function OrgUnitTypeForm({ type, onClose }: { type: OrgUnitType | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateOrgUnitType();
  const update = useUpdateOrgUnitType();
  const mutation = type ? update : create;

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const nameVi = v.nameVi.trim();
    const nameEn = v.nameEn.trim();
    try {
      if (type) {
        await update.mutateAsync({
          id: type.id,
          body: {
            nameVi,
            nameEn,
            levelHint: v.levelHint ?? null,
            sortOrder: v.sortOrder ?? 0,
            active: v.active,
            version: type.version,
          },
        });
        fb.saved();
      } else {
        await create.mutateAsync({ code: v.code.trim(), nameVi, nameEn, levelHint: v.levelHint ?? null, sortOrder: v.sortOrder ?? null });
        fb.created();
      }
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={type ? te('orgUnitType.edit') : te('orgUnitType.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={480}
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={
          type
            ? { code: type.code, nameVi: type.nameVi, nameEn: type.nameEn, levelHint: type.levelHint, sortOrder: type.sortOrder, active: type.active }
            : { active: true, sortOrder: 0 }
        }
      >
        <Form.Item
          name="code"
          label={t('field.code')}
          extra={type ? undefined : te('orgUnitType.codeHint')}
          rules={[
            { required: true, whitespace: true, message: te('common.codeRequired') },
            { pattern: TYPE_CODE_PATTERN, message: te('orgUnitType.codeInvalid') },
          ]}
        >
          <Input disabled={!!type} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="nameVi" label={t('field.nameVi')} rules={[{ required: true, whitespace: true, message: te('common.nameRequired') }, { max: 100 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="nameEn" label={t('field.nameEn')} rules={[{ required: true, whitespace: true, message: te('common.nameRequired') }, { max: 100 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="levelHint" label={te('orgUnitType.field.levelHint')}>
          <InputNumber min={1} precision={0} style={{ width: 140 }} />
        </Form.Item>
        <Form.Item name="sortOrder" label={te('orgUnitType.field.sortOrder')}>
          <InputNumber precision={0} style={{ width: 140 }} />
        </Form.Item>
        {type ? (
          <Form.Item name="active" label={te('orgUnitType.field.active')} valuePropName="checked">
            <Switch />
          </Form.Item>
        ) : null}
      </Form>
    </FormDrawer>
  );
}
