import { useState } from 'react';
import { Button, Checkbox, Form, Input, Space, Switch } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, StatusTag, useFeedback } from '@/shared/components';
import { CorePermissions as P } from '../../permissions';
import type { JobFamily } from './api';
import { useCreateJobFamily, useJobFamilies, useUpdateJobFamily } from './queries';
import { applyFieldErrors, CODE_PATTERN, textOrNull } from './formErrors';
import { JOB_CATALOGUE_NS } from './i18n';

/** Job families (BR-CORE-020): list, create, rename / (de)activate. Shown as a tab of the job titles page. */
export function JobFamiliesPanel() {
  const { t } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const [includeInactive, setIncludeInactive] = useState(false);
  const [form, setForm] = useState<{ family: JobFamily | null } | null>(null);
  const canUpdate = usePermission(P.JOB_CATALOGUE_UPDATE);
  const query = useJobFamilies(includeInactive);

  return (
    <div>
      <Space wrap style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Checkbox checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)}>
          {tj('common.includeInactive')}
        </Checkbox>
        <PermissionGate anyOf={P.JOB_CATALOGUE_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ family: null })}>
            {tj('jobFamily.create')}
          </Button>
        </PermissionGate>
      </Space>

      <DataTable<JobFamily>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.nameVi'), dataIndex: 'nameVi' },
          { title: t('field.nameEn'), dataIndex: 'nameEn' },
          { title: t('field.status'), dataIndex: 'active', render: (a: boolean) => <StatusTag status={a ? 'ACTIVE' : 'INACTIVE'} /> },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, row: JobFamily) => (
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      aria-label={`${t('action.edit')} ${row.code}`}
                      onClick={() => setForm({ family: row })}
                    />
                  ),
                },
              ]
            : []),
        ]}
      />

      {form ? <JobFamilyForm family={form.family} onClose={() => setForm(null)} /> : null}
    </div>
  );
}

interface FormValues {
  code: string;
  nameVi: string;
  nameEn?: string | null;
  active: boolean;
}

function JobFamilyForm({ family, onClose }: { family: JobFamily | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateJobFamily();
  const update = useUpdateJobFamily();
  const mutation = family ? update : create;

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const nameVi = v.nameVi.trim();
    const nameEn = textOrNull(v.nameEn);
    try {
      if (family) {
        await update.mutateAsync({ id: family.id, body: { nameVi, nameEn, active: v.active } });
        fb.saved();
      } else {
        await create.mutateAsync({ code: v.code.trim(), nameVi, nameEn });
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
      title={family ? tj('jobFamily.edit') : tj('jobFamily.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={480}
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={family ? { code: family.code, nameVi: family.nameVi, nameEn: family.nameEn, active: family.active } : { active: true }}
      >
        <Form.Item
          name="code"
          label={t('field.code')}
          extra={family ? tj('common.codeImmutable') : undefined}
          rules={[
            { required: true, whitespace: true, message: tj('common.codeRequired') },
            { pattern: CODE_PATTERN, message: tj('common.codeInvalid') },
          ]}
        >
          <Input disabled={!!family} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="nameVi" label={t('field.nameVi')} rules={[{ required: true, whitespace: true, message: tj('common.nameRequired') }, { max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="nameEn" label={t('field.nameEn')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        {family ? (
          <Form.Item name="active" label={tj('common.active')} valuePropName="checked">
            <Switch />
          </Form.Item>
        ) : null}
      </Form>
    </FormDrawer>
  );
}
