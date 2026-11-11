import { Form, Input, Select, Switch } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import type { JobTitle, JobTitleAttributes } from './api';
import { useAllGrades, useCreateJobTitle, useUpdateJobTitle } from './queries';
import { applyFieldErrors, CODE_PATTERN, stripAttributes, textOrNull, UUID_PATTERN } from './formErrors';
import { gradeLabel, HazardCategorySelect, JobFamilySelect } from './widgets';
import { JOB_CATALOGUE_NS } from './i18n';

interface FormValues {
  code: string;
  nameVi: string;
  nameEn?: string | null;
  jobFamilyId?: UUID | null;
  hazardousCategory?: string | null;
  occupationCode?: string | null;
  description?: string | null;
  jdDocumentId?: string | null;
  /** UI only: narrows the grade pickers; not sent. */
  gradeStructureId?: UUID | null;
  defaultGradeMinId?: UUID | null;
  defaultGradeMaxId?: UUID | null;
  active: boolean;
}

/**
 * Create / edit a job title (BR-CORE-020, US-CORE-020) in a drawer. Mounted only while open; the edit sends the
 * full attribute set with the version it read (optimistic lock, `stale-version` on conflict).
 */
export function JobTitleForm({
  title,
  onClose,
  onSaved,
}: {
  title: JobTitle | null;
  onClose: () => void;
  onSaved?: (saved: JobTitle) => void;
}) {
  const { t } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateJobTitle();
  const update = useUpdateJobTitle(title?.id ?? '');
  const mutation = title ? update : create;
  const { structures, grades } = useAllGrades();
  const structureId = Form.useWatch('gradeStructureId', form);
  const minId = Form.useWatch('defaultGradeMinId', form);

  // The structure of the stored range is derived from its lowest grade (resolved once the grades have loaded).
  const storedStructure = title?.defaultGradeMinId
    ? grades.find((g) => g.id === title.defaultGradeMinId)?.gradeStructureId
    : undefined;
  const effectiveStructure = structureId === undefined ? storedStructure : structureId;
  const structureGrades = grades
    .filter((g) => g.gradeStructureId === effectiveStructure && (g.active || g.id === title?.defaultGradeMinId || g.id === title?.defaultGradeMaxId))
    .sort((a, b) => a.rankOrder - b.rankOrder || a.validFrom.localeCompare(b.validFrom));
  const gradeOptions = structureGrades.map((g) => ({ value: g.id, label: gradeLabel(g) }));
  const minRank = structureGrades.find((g) => g.id === minId)?.rankOrder;

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const attributes: JobTitleAttributes = {
      jobFamilyId: v.jobFamilyId ?? null,
      nameVi: v.nameVi.trim(),
      nameEn: textOrNull(v.nameEn),
      description: textOrNull(v.description),
      jdDocumentId: textOrNull(v.jdDocumentId),
      defaultGradeMinId: v.defaultGradeMinId ?? null,
      defaultGradeMaxId: v.defaultGradeMaxId ?? null,
      occupationCode: textOrNull(v.occupationCode),
      hazardousCategory: v.hazardousCategory ?? null,
      active: title ? v.active : true,
    };
    try {
      const saved = title
        ? await update.mutateAsync({ attributes, version: title.version })
        : await create.mutateAsync({ code: v.code.trim(), attributes });
      if (title) fb.saved();
      else fb.created();
      onSaved?.(saved);
      onClose();
    } catch (error) {
      applyFieldErrors(form, error, stripAttributes);
    }
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={title ? tj('jobTitle.edit') : tj('jobTitle.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={600}
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={
          title
            ? {
                code: title.code,
                nameVi: title.nameVi,
                nameEn: title.nameEn,
                jobFamilyId: title.jobFamilyId ?? null,
                hazardousCategory: title.hazardousCategory ?? null,
                occupationCode: title.occupationCode,
                description: title.description,
                jdDocumentId: title.jdDocumentId,
                defaultGradeMinId: title.defaultGradeMinId ?? null,
                defaultGradeMaxId: title.defaultGradeMaxId ?? null,
                active: title.active,
              }
            : { active: true }
        }
      >
        <Form.Item
          name="code"
          label={t('field.code')}
          extra={title ? tj('common.codeImmutable') : undefined}
          rules={[
            { required: true, whitespace: true, message: tj('common.codeRequired') },
            { pattern: CODE_PATTERN, message: tj('common.codeInvalid') },
          ]}
        >
          <Input disabled={!!title} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="nameVi" label={t('field.nameVi')} rules={[{ required: true, whitespace: true, message: tj('common.nameRequired') }, { max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="nameEn" label={t('field.nameEn')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="jobFamilyId" label={tj('jobTitle.field.family')}>
          <JobFamilySelect />
        </Form.Item>
        <Form.Item name="hazardousCategory" label={tj('jobTitle.field.hazardousCategory')} extra={tj('jobTitle.hazardHint')}>
          <HazardCategorySelect />
        </Form.Item>
        <Form.Item name="occupationCode" label={tj('jobTitle.field.occupationCode')} extra={tj('jobTitle.occupationHint')} rules={[{ max: 32 }]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="description" label={tj('jobTitle.field.description')} rules={[{ max: 4000 }]}>
          <Input.TextArea rows={4} />
        </Form.Item>
        <Form.Item
          name="jdDocumentId"
          label={tj('jobTitle.field.jdDocumentId')}
          extra={tj('jobTitle.jdHint')}
          rules={[{ pattern: UUID_PATTERN, message: tj('common.uuidInvalid') }]}
        >
          <Input autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>

        <Form.Item name="gradeStructureId" label={tj('jobTitle.field.gradeStructure')} extra={tj('jobTitle.gradeRangeHint')}>
          <Select
            allowClear
            placeholder={storedStructure ? structures.find((s) => s.id === storedStructure)?.code : undefined}
            options={structures.map((s) => ({ value: s.id, label: `${s.code} – ${s.name}` }))}
            onChange={() => form.setFieldsValue({ defaultGradeMinId: null, defaultGradeMaxId: null })}
          />
        </Form.Item>
        <Form.Item
          name="defaultGradeMinId"
          label={tj('jobTitle.field.defaultGradeMin')}
          dependencies={['defaultGradeMaxId']}
          rules={[
            ({ getFieldValue }) => ({
              validator: (_, value) =>
                !!value === !!getFieldValue('defaultGradeMaxId')
                  ? Promise.resolve()
                  : Promise.reject(new Error(tj('jobTitle.gradeRangeBoth'))),
            }),
          ]}
        >
          <Select allowClear options={gradeOptions} disabled={!effectiveStructure} />
        </Form.Item>
        <Form.Item
          name="defaultGradeMaxId"
          label={tj('jobTitle.field.defaultGradeMax')}
          dependencies={['defaultGradeMinId']}
          rules={[
            {
              validator: (_, value) => {
                const max = structureGrades.find((g) => g.id === value);
                return max && minRank !== undefined && max.rankOrder < minRank
                  ? Promise.reject(new Error(tj('jobTitle.gradeRangeOrder')))
                  : Promise.resolve();
              },
            },
          ]}
        >
          <Select allowClear options={gradeOptions} disabled={!effectiveStructure} />
        </Form.Item>
        {title ? (
          <Form.Item name="active" label={tj('common.active')} valuePropName="checked">
            <Switch />
          </Form.Item>
        ) : null}
      </Form>
    </FormDrawer>
  );
}
