import { useState } from 'react';
import { Button, Form, Input } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, useFeedback } from '@/shared/components';
import { CorePermissions as P } from '../../permissions';
import type { GradeStructure } from './api';
import { useCreateGradeStructure, useGradeStructures, useRenameGradeStructure } from './queries';
import { applyFieldErrors, CODE_PATTERN } from './formErrors';
import { JOB_CATALOGUE_NS } from './i18n';

/** Grade structures (ngạch, BR-CORE-021): list, create, rename. Grades are managed on the structure's page. */
export function GradeStructuresPage() {
  const { t } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const navigate = useNavigate();
  const [form, setForm] = useState<{ structure: GradeStructure | null } | null>(null);
  const canUpdate = usePermission(P.JOB_CATALOGUE_UPDATE);
  const query = useGradeStructures();

  return (
    <div>
      <PageHeader
        title={tj('gradeStructure.title')}
        subTitle={tj('gradeStructure.subtitle')}
        extra={
          <PermissionGate anyOf={P.JOB_CATALOGUE_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ structure: null })}>
              {tj('gradeStructure.create')}
            </Button>
          </PermissionGate>
        }
      />

      <DataTable<GradeStructure>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: t('field.code'),
            dataIndex: 'code',
            render: (code: string, row) => (
              <Link to="/core/grade-structures/$structureId" params={{ structureId: row.id }}>
                {code}
              </Link>
            ),
          },
          { title: t('field.name'), dataIndex: 'name' },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, row: GradeStructure) => (
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      aria-label={`${tj('gradeStructure.rename')} ${row.code}`}
                      onClick={() => setForm({ structure: row })}
                    />
                  ),
                },
              ]
            : []),
        ]}
      />

      {form ? (
        <GradeStructureForm
          structure={form.structure}
          onClose={() => setForm(null)}
          onCreated={(s) => void navigate({ to: '/core/grade-structures/$structureId', params: { structureId: s.id } })}
        />
      ) : null}
    </div>
  );
}

interface FormValues {
  code: string;
  name: string;
}

/** Create a structure, or rename one (the code is immutable; the backend keeps a single-language name). */
export function GradeStructureForm({
  structure,
  onClose,
  onCreated,
}: {
  structure: GradeStructure | null;
  onClose: () => void;
  onCreated?: (s: GradeStructure) => void;
}) {
  const { t } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateGradeStructure();
  const rename = useRenameGradeStructure();
  const mutation = structure ? rename : create;

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      if (structure) {
        await rename.mutateAsync({ id: structure.id, body: { name: v.name.trim() } });
        fb.saved();
        onClose();
      } else {
        const created = await create.mutateAsync({ code: v.code.trim(), name: v.name.trim() });
        fb.created();
        onClose();
        onCreated?.(created);
      }
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={structure ? tj('gradeStructure.renameTitle', { code: structure.code }) : tj('gradeStructure.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={480}
    >
      <Form<FormValues> form={form} layout="vertical" initialValues={structure ? { code: structure.code, name: structure.name } : {}}>
        <Form.Item
          name="code"
          label={t('field.code')}
          extra={structure ? tj('common.codeImmutable') : undefined}
          rules={[
            { required: true, whitespace: true, message: tj('common.codeRequired') },
            { pattern: CODE_PATTERN, message: tj('common.codeInvalid') },
          ]}
        >
          <Input disabled={!!structure} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="name" label={t('field.name')} rules={[{ required: true, whitespace: true, message: tj('common.nameRequired') }, { max: 200 }]}>
          <Input />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
