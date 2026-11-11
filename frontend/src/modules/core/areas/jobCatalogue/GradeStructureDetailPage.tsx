import { useState } from 'react';
import { Alert, Button, Card, Checkbox, DatePicker, Form, Input, InputNumber, Result, Skeleton, Space, Switch } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { AsOfPicker, DataTable, FormDrawer, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatValidity, ISO_DATE, today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { Grade, GradeStructure } from './api';
import { useCreateGrade, useGrades, useGradeStructures, useUpdateGrade } from './queries';
import { GradeStructureForm } from './GradeStructuresPage';
import { applyFieldErrors, CODE_PATTERN } from './formErrors';
import { JOB_CATALOGUE_NS } from './i18n';

/**
 * One grade structure and its grades in rank order (BR-CORE-021, US-CORE-021). The backend has no GET by id for a
 * structure, so it is picked from the list. Grades carry no pay data.
 */
export function GradeStructureDetailPage({ structureId }: { structureId: string }) {
  const { t } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const [allDates, setAllDates] = useState(true);
  const [asOf, setAsOf] = useState(today);
  const [renaming, setRenaming] = useState(false);
  const [gradeForm, setGradeForm] = useState<{ grade: Grade | null } | null>(null);
  const canUpdate = usePermission(P.JOB_CATALOGUE_UPDATE);
  const structures = useGradeStructures();
  const grades = useGrades(structureId, allDates ? { allDates: true } : { asOf });

  if (structures.isLoading) return <Skeleton active />;
  const structure = structures.data?.find((s) => s.id === structureId);
  if (structures.error || !structure) {
    return (
      <Result status="404" title={tj('common.notFound')} extra={structures.error ? <ProblemAlert error={structures.error} /> : null} />
    );
  }

  return (
    <div>
      <PageHeader
        title={tj('gradeStructure.detailTitle', { code: structure.code })}
        subTitle={structure.name}
        breadcrumb={[{ label: tj('gradeStructure.title'), to: '/core/grade-structures' }, { label: structure.code }]}
        extra={
          <PermissionGate anyOf={P.JOB_CATALOGUE_UPDATE}>
            <Button icon={<EditOutlined />} onClick={() => setRenaming(true)}>
              {tj('gradeStructure.rename')}
            </Button>
          </PermissionGate>
        }
      />
      <Alert type="info" showIcon message={tj('grade.noPayNote')} style={{ marginBottom: 16 }} />

      <Card
        title={tj('grade.title')}
        extra={
          <PermissionGate anyOf={P.JOB_CATALOGUE_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setGradeForm({ grade: null })}>
              {tj('grade.create')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap style={{ marginBottom: 12 }}>
          <Checkbox checked={allDates} onChange={(e) => setAllDates(e.target.checked)}>
            {tj('grade.allPeriods')}
          </Checkbox>
          {!allDates ? <AsOfPicker value={asOf} onChange={setAsOf} /> : null}
        </Space>
        <DataTable<Grade>
          data={grades.data}
          isLoading={grades.isLoading}
          error={grades.error}
          columns={[
            { title: tj('grade.field.rankOrder'), dataIndex: 'rankOrder', align: 'right' },
            { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
            { title: t('field.name'), dataIndex: 'name' },
            {
              title: tj('grade.field.validity'),
              key: 'validity',
              render: (_, g) => formatValidity(g.validFrom, g.validTo, t('common.openEnded')),
            },
            { title: t('field.status'), dataIndex: 'active', render: (a: boolean) => <StatusTag status={a ? 'ACTIVE' : 'INACTIVE'} /> },
            ...(canUpdate
              ? [
                  {
                    title: t('field.actions'),
                    key: 'actions',
                    render: (_: unknown, g: Grade) => (
                      <Button
                        size="small"
                        icon={<EditOutlined />}
                        aria-label={`${t('action.edit')} ${g.code}`}
                        onClick={() => setGradeForm({ grade: g })}
                      />
                    ),
                  },
                ]
              : []),
          ]}
        />
      </Card>

      {renaming ? <GradeStructureForm structure={structure} onClose={() => setRenaming(false)} /> : null}
      {gradeForm ? <GradeForm structure={structure} grade={gradeForm.grade} onClose={() => setGradeForm(null)} /> : null}
    </div>
  );
}

interface GradeFormValues {
  code: string;
  name: string;
  rankOrder: number;
  validFrom?: Dayjs | null;
  validTo?: Dayjs | null;
  active: boolean;
}

/** Add a grade, or rename / re-rank / end / (de)activate one. Code and start date are immutable; no version. */
function GradeForm({ structure, grade, onClose }: { structure: GradeStructure; grade: Grade | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<GradeFormValues>();
  const create = useCreateGrade(structure.id);
  const update = useUpdateGrade();
  const mutation = grade ? update : create;

  const submit = async () => {
    let v: GradeFormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const validTo = v.validTo ? v.validTo.format(ISO_DATE) : null;
    try {
      if (grade) {
        await update.mutateAsync({
          id: grade.id,
          body: { name: v.name.trim(), rankOrder: v.rankOrder, validTo, active: v.active },
        });
        fb.saved();
      } else {
        await create.mutateAsync({
          code: v.code.trim(),
          name: v.name.trim(),
          rankOrder: v.rankOrder,
          validFrom: v.validFrom ? v.validFrom.format(ISO_DATE) : null,
          validTo,
        });
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
      title={grade ? tj('grade.edit', { code: grade.code }) : `${tj('grade.create')} · ${structure.code}`}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={480}
    >
      <Form<GradeFormValues>
        form={form}
        layout="vertical"
        initialValues={
          grade
            ? {
                code: grade.code,
                name: grade.name,
                rankOrder: grade.rankOrder,
                validFrom: dayjs(grade.validFrom),
                validTo: grade.validTo ? dayjs(grade.validTo) : null,
                active: grade.active,
              }
            : { active: true }
        }
      >
        <Form.Item
          name="code"
          label={t('field.code')}
          extra={grade ? tj('common.codeImmutable') : undefined}
          rules={[
            { required: true, whitespace: true, message: tj('common.codeRequired') },
            { pattern: CODE_PATTERN, message: tj('common.codeInvalid') },
          ]}
        >
          <Input disabled={!!grade} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="name" label={t('field.name')} rules={[{ required: true, whitespace: true, message: tj('common.nameRequired') }, { max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item
          name="rankOrder"
          label={tj('grade.field.rankOrder')}
          extra={tj('grade.rankHint')}
          rules={[{ required: true, message: tj('grade.rankRequired') }]}
        >
          <InputNumber precision={0} style={{ width: 160 }} />
        </Form.Item>
        <Form.Item name="validFrom" label={t('field.validFrom')} extra={grade ? undefined : tj('grade.validFromHint')}>
          <DatePicker format={DATE_FORMAT} disabled={!!grade} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="validTo"
          label={t('field.validTo')}
          extra={tj('grade.validToHint')}
          dependencies={['validFrom']}
          rules={[
            ({ getFieldValue }) => ({
              validator: (_, value: Dayjs | null | undefined) => {
                const from: Dayjs | null | undefined = getFieldValue('validFrom');
                const start = from ?? dayjs().startOf('day');
                return value && !value.isAfter(start, 'day')
                  ? Promise.reject(new Error(tj('grade.validToAfterFrom')))
                  : Promise.resolve();
              },
            }),
          ]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        {grade ? (
          <Form.Item name="active" label={tj('common.active')} extra={tj('grade.activeHint')} valuePropName="checked">
            <Switch />
          </Form.Item>
        ) : null}
      </Form>
    </FormDrawer>
  );
}
