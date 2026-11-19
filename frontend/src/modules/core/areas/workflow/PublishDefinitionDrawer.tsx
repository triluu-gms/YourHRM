import { useEffect, useMemo } from 'react';
import { DatePicker, Form, Input, InputNumber, Select, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import type { TFunction } from 'i18next';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { isProblem } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE, localizedName } from '@/shared/format';
import { processTypesQuery, usePublishDefinition } from './queries';
import type { Conditions, DefinitionView, ResolverFailurePolicy, StepTemplate, VersionView } from './api';
import './i18n';

const FALLBACKS: ResolverFailurePolicy[] = ['SKIP', 'ESCALATE_UP', 'LE_HR_ADMIN', 'FAIL'];
const CODE_PATTERN = /^[A-Za-z0-9_.-]{1,64}$/;

type TFn = TFunction<'core-workflow'>;

/** Parses and checks the steps JSON like the backend's `@Valid List<StepTemplate>`; returns the error text or the steps. */
export function parseSteps(raw: string, t: TFn): { error: string } | { steps: StepTemplate[] } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    return { error: t('publish.stepsInvalidJson', { message: e instanceof Error ? e.message : String(e) }) };
  }
  if (!Array.isArray(parsed)) return { error: t('publish.stepsNotArray') };
  if (parsed.length === 0) return { error: t('publish.stepsRequired') };
  for (let i = 0; i < parsed.length; i++) {
    const step = parsed[i] as Partial<StepTemplate> | null;
    if (!step || typeof step !== 'object' || !step.stepKey || !step.nameVi || !step.resolver?.type) {
      return { error: t('publish.stepInvalid', { index: i + 1 }) };
    }
  }
  return { steps: parsed as StepTemplate[] };
}

/** Parses the optional conditions JSON; empty input means no conditions. */
export function parseConditions(raw: string | undefined, t: TFn): { error: string } | { conditions: Partial<Conditions> | null } {
  const trimmed = raw?.trim();
  if (!trimmed) return { conditions: null };
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch (e) {
    return { error: t('publish.conditionsInvalidJson', { message: e instanceof Error ? e.message : String(e) }) };
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { error: t('publish.conditionsNotObject') };
  return { conditions: parsed as Partial<Conditions> };
}

interface FormValues {
  processTypeCode: string;
  code: string;
  nameVi: string;
  nameEn?: string;
  matchPriority?: number;
  validFrom: Dayjs;
  onResolverFailure: ResolverFailurePolicy;
  steps: string;
  conditions?: string;
}

/** The latest version's steps / conditions, used to prefill a new version. */
function latestVersion(definition: DefinitionView | undefined): VersionView | undefined {
  return definition?.versions.reduce<VersionView | undefined>((a, b) => (!a || b.versionNo > a.versionNo ? b : a), undefined);
}

/**
 * Publishes a workflow definition version (US-CORE-051 scaffolding; support-only in P1,
 * `core.workflow_definition.manage`). Steps are edited as JSON until the designer (P2) exists.
 */
export function PublishDefinitionDrawer({
  open,
  onClose,
  definition,
}: {
  open: boolean;
  onClose: () => void;
  /** When set, the drawer publishes a new version of this definition. */
  definition?: DefinitionView;
}) {
  const { t, i18n } = useTranslation('core-workflow');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const mutation = usePublishDefinition();
  const processTypes = useQuery({ ...processTypesQuery, enabled: open });

  const initialValues = useMemo<Partial<FormValues>>(() => {
    const version = latestVersion(definition);
    return {
      processTypeCode: definition?.processTypeCode,
      code: definition?.code,
      nameVi: definition?.nameVi,
      nameEn: definition?.nameEn ?? undefined,
      matchPriority: definition?.matchPriority ?? 100,
      validFrom: dayjs(),
      onResolverFailure: (version?.onResolverFailure as ResolverFailurePolicy) ?? 'ESCALATE_UP',
      steps: version ? JSON.stringify(version.steps, null, 2) : '',
      conditions: version && !isEmptyConditions(version.conditions) ? JSON.stringify(version.conditions, null, 2) : '',
    };
  }, [definition]);

  useEffect(() => {
    if (open) {
      form.resetFields();
      form.setFieldsValue(initialValues);
      mutation.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initialValues]);

  const submit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const steps = parseSteps(values.steps, t);
    if ('error' in steps) {
      form.setFields([{ name: 'steps', errors: [steps.error] }]);
      return;
    }
    const conditions = parseConditions(values.conditions, t);
    if ('error' in conditions) {
      form.setFields([{ name: 'conditions', errors: [conditions.error] }]);
      return;
    }
    try {
      await mutation.mutateAsync({
        processTypeCode: values.processTypeCode,
        code: values.code.trim(),
        nameVi: values.nameVi.trim(),
        nameEn: values.nameEn?.trim() || null,
        matchPriority: values.matchPriority ?? null,
        conditions: conditions.conditions,
        steps: steps.steps,
        onResolverFailure: values.onResolverFailure,
        validFrom: values.validFrom.format(ISO_DATE),
      });
      fb.done(t('publish.published'));
      onClose();
    } catch (error) {
      if (isProblem(error, 'validation-failed') && error.problem.errors) {
        form.setFields(error.problem.errors.map((e) => ({ name: e.field as keyof FormValues, errors: [e.message] })));
      }
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={definition ? t('definition.newVersion') : t('publish.title')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      submitLabel={t('publish.submit')}
      width={640}
    >
      <Typography.Paragraph type="secondary">{t('publish.hint')}</Typography.Paragraph>
      <Form<FormValues> form={form} layout="vertical" requiredMark="optional">
        <Form.Item name="processTypeCode" label={t('publish.processType')} rules={[{ required: true, message: t('publish.processTypeRequired') }]}>
          <Select
            showSearch
            optionFilterProp="label"
            disabled={!!definition}
            loading={processTypes.isLoading}
            options={(processTypes.data ?? []).map((pt) => ({
              value: pt.code,
              label: `${localizedName({ nameVi: pt.nameVi, nameEn: pt.nameEn }, i18n.language) || pt.code} (${pt.code})`,
            }))}
          />
        </Form.Item>
        <Form.Item
          name="code"
          label={t('publish.code')}
          rules={[
            { required: true, whitespace: true, message: t('publish.codeRequired') },
            { pattern: CODE_PATTERN, message: t('publish.codePattern') },
          ]}
        >
          <Input maxLength={64} disabled={!!definition} />
        </Form.Item>
        <Form.Item name="nameVi" label={t('publish.nameVi')} rules={[{ required: true, whitespace: true, message: t('publish.nameViRequired') }]}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="nameEn" label={t('publish.nameEn')}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="matchPriority" label={t('publish.matchPriority')}>
          <InputNumber min={0} max={10000} style={{ width: 160 }} />
        </Form.Item>
        <Form.Item name="validFrom" label={t('publish.validFrom')} rules={[{ required: true, message: t('publish.validFromRequired') }]}>
          <DatePicker format={DATE_FORMAT} />
        </Form.Item>
        <Form.Item name="onResolverFailure" label={t('publish.onResolverFailure')}>
          <Select options={FALLBACKS.map((f) => ({ value: f, label: t(`definition.fallback.${f}`) }))} style={{ width: 320 }} />
        </Form.Item>
        <Form.Item
          name="steps"
          label={t('publish.steps')}
          extra={t('publish.stepsHint')}
          rules={[{ required: true, whitespace: true, message: t('publish.stepsRequired') }]}
        >
          <Input.TextArea rows={10} style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="conditions" label={t('publish.conditions')} extra={t('publish.conditionsHint')}>
          <Input.TextArea rows={5} style={{ fontFamily: 'monospace' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

function isEmptyConditions(c: Conditions): boolean {
  return (
    c.legalEntityIds.length === 0 &&
    c.orgUnitIds.length === 0 &&
    c.locationIds.length === 0 &&
    c.employeeCategoryIds.length === 0 &&
    Object.keys(c.fields).length === 0
  );
}
