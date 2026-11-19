import { useState } from 'react';
import { Alert, Button, DatePicker, Form, Input, InputNumber, Modal, Select, Space, Switch, Typography } from 'antd';
import { EditOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE, formatDate, today } from '@/shared/format';
import { isProblem } from '@/shared/api';
import { CorePermissions as P } from '../../permissions';
import { LegalEntitySelect } from '../employer/widgets';
import { useLegalEntities } from '../employer/queries';
import {
  RESET_RULES,
  SEQUENCE_SCOPES,
  SEQUENCE_STATUSES,
  type ResetRule,
  type Sequence,
  type SequenceScope,
  type SequenceStatus,
} from './api';
import {
  PATTERN_TOKENS,
  SEQUENCE_CODE_PATTERN,
  checkPattern,
  formatPattern,
  needsDate,
  needsLegalEntity,
} from './format';
import { useCreateSequence, useSequencePreview, useSequences, useUpdateSequence } from './queries';
import { CONFIGURATION_NS } from './i18n';

/**
 * Number series (BR-CORE-085, US-CORE-086): list, create, edit and preview the next number. Numbers themselves are
 * taken by the modules inside their business transaction (`core.api.Numbering`).
 */
export function SequencesPage() {
  const { t } = useTranslation(CONFIGURATION_NS);
  const { t: tc } = useTranslation();
  const query = useSequences();
  const canUpdate = usePermission(P.SEQUENCE_UPDATE);
  const [form, setForm] = useState<{ sequence: Sequence | null } | null>(null);
  const [preview, setPreview] = useState<Sequence | null>(null);

  return (
    <div>
      <PageHeader
        title={t('sequences.title')}
        subTitle={t('sequences.subtitle')}
        extra={
          <PermissionGate anyOf={P.SEQUENCE_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ sequence: null })}>
              {t('sequences.create')}
            </Button>
          </PermissionGate>
        }
      />

      <DataTable<Sequence>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: tc('field.code'),
            dataIndex: 'code',
            render: (code: string) => <Typography.Text style={{ fontFamily: 'monospace' }}>{code}</Typography.Text>,
          },
          { title: tc('field.name'), dataIndex: 'name' },
          {
            title: t('sequences.field.pattern'),
            dataIndex: 'pattern',
            render: (pattern: string) => <Typography.Text code>{pattern}</Typography.Text>,
          },
          {
            title: t('sequences.field.scopeLevel'),
            dataIndex: 'scopeLevel',
            render: (scope: SequenceScope) => t(`sequences.scope.${scope}`),
          },
          {
            title: t('sequences.field.resetRule'),
            dataIndex: 'resetRule',
            render: (rule: ResetRule) => t(`sequences.reset.${rule}`),
          },
          {
            title: t('sequences.field.gapFree'),
            dataIndex: 'gapFree',
            render: (gapFree: boolean) => tc(gapFree ? 'common.yes' : 'common.no'),
          },
          { title: tc('field.status'), dataIndex: 'status', render: (s: SequenceStatus) => <StatusTag status={s} /> },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_: unknown, row) => (
              <Space size={4}>
                <Button
                  size="small"
                  icon={<EyeOutlined />}
                  disabled={row.status !== 'ACTIVE'}
                  aria-label={`${t('sequences.preview')} ${row.code}`}
                  title={t('sequences.preview')}
                  onClick={() => setPreview(row)}
                />
                {canUpdate ? (
                  <Button
                    size="small"
                    icon={<EditOutlined />}
                    aria-label={`${tc('action.edit')} ${row.code}`}
                    onClick={() => setForm({ sequence: row })}
                  />
                ) : null}
              </Space>
            ),
          },
        ]}
      />

      {form ? <SequenceForm sequence={form.sequence} onClose={() => setForm(null)} /> : null}
      {preview ? <PreviewModal sequence={preview} onClose={() => setPreview(null)} /> : null}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ create / edit

interface FormValues {
  code: string;
  name: string;
  scopeLevel: SequenceScope;
  pattern: string;
  resetRule: ResetRule;
  gapFree: boolean;
  startValue: number;
  incrementBy: number;
  status: SequenceStatus;
}

/** Create a series, or edit one (code, counter scope and start value are fixed once created). */
export function SequenceForm({ sequence, onClose }: { sequence: Sequence | null; onClose: () => void }) {
  const { t } = useTranslation(CONFIGURATION_NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateSequence();
  const update = useUpdateSequence();
  const mutation = sequence ? update : create;
  const editing = sequence !== null;

  const pattern = Form.useWatch('pattern', form) ?? sequence?.pattern ?? '';
  const scopeLevel = Form.useWatch('scopeLevel', form) ?? sequence?.scopeLevel ?? 'TENANT';
  const startValue = Form.useWatch('startValue', form) ?? sequence?.startValue ?? 1;
  const sampleLe = useLegalEntities(undefined, undefined, needsLegalEntity(scopeLevel, pattern)).data?.[0]?.code ?? 'LE';
  const patternOk = pattern.trim() !== '' && checkPattern(pattern) === null;

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      if (sequence) {
        await update.mutateAsync({
          code: sequence.code,
          body: {
            name: v.name.trim(),
            pattern: v.pattern.trim(),
            resetRule: v.resetRule,
            gapFree: v.gapFree,
            incrementBy: v.incrementBy,
            status: v.status,
            version: sequence.version,
          },
        });
        fb.saved();
      } else {
        await create.mutateAsync({
          code: v.code.trim(),
          name: v.name.trim(),
          scopeLevel: v.scopeLevel,
          pattern: v.pattern.trim(),
          resetRule: v.resetRule,
          gapFree: v.gapFree,
          startValue: v.startValue,
          incrementBy: v.incrementBy,
        });
        fb.created();
      }
      onClose();
    } catch (error) {
      if (isProblem(error, 'validation-failed') && error.problem.errors?.length) {
        form.setFields(error.problem.errors.map((e) => ({ name: e.field as keyof FormValues, errors: [e.message] })));
      }
    }
  };

  const patternRule = {
    validator: (_: unknown, value: string | undefined) => {
      if (!value || !value.trim()) return Promise.resolve();
      const problem = checkPattern(value);
      return problem
        ? Promise.reject(new Error(t(`sequences.patternProblem.${problem.problem}`, { token: problem.token })))
        : Promise.resolve();
    },
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={sequence ? t('sequences.edit', { code: sequence.code }) : t('sequences.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={
          sequence ?? { scopeLevel: 'TENANT', resetRule: 'NEVER', gapFree: true, startValue: 1, incrementBy: 1, status: 'ACTIVE' }
        }
      >
        <Form.Item
          name="code"
          label={tc('field.code')}
          extra={editing ? t('sequences.fixedAfterCreate') : t('sequences.codeHint')}
          normalize={(v: string) => v?.toUpperCase()}
          rules={[
            { required: true, whitespace: true, message: t('sequences.codeRequired') },
            { pattern: SEQUENCE_CODE_PATTERN, message: t('sequences.codeInvalid') },
          ]}
        >
          <Input disabled={editing} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item
          name="name"
          label={tc('field.name')}
          rules={[{ required: true, whitespace: true, message: t('sequences.nameRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          name="scopeLevel"
          label={t('sequences.field.scopeLevel')}
          extra={editing ? t('sequences.fixedAfterCreate') : undefined}
        >
          <Select disabled={editing} options={SEQUENCE_SCOPES.map((s) => ({ value: s, label: t(`sequences.scope.${s}`) }))} />
        </Form.Item>
        <Form.Item
          name="pattern"
          label={t('sequences.field.pattern')}
          extra={t('sequences.patternHint', { tokens: PATTERN_TOKENS.join(', ') })}
          rules={[{ required: true, whitespace: true, message: t('sequences.patternRequired') }, { max: 100 }, patternRule]}
        >
          <Input autoComplete="off" style={{ fontFamily: 'monospace' }} placeholder="NV{SEQ:5}" />
        </Form.Item>
        {patternOk ? (
          <Alert
            type="info"
            style={{ marginBottom: 16 }}
            data-testid="pattern-sample"
            message={
              <Space direction="vertical" size={0}>
                <Typography.Text strong style={{ fontFamily: 'monospace' }}>
                  {formatPattern(pattern.trim(), startValue, today(), sampleLe)}
                </Typography.Text>
                <Typography.Text type="secondary">
                  {t('sequences.sampleHint', {
                    n: startValue,
                    date: formatDate(today()),
                    le: needsLegalEntity(scopeLevel, pattern) ? sampleLe : 'none',
                  })}
                </Typography.Text>
              </Space>
            }
          />
        ) : null}
        <Form.Item name="resetRule" label={t('sequences.field.resetRule')}>
          <Select options={RESET_RULES.map((r) => ({ value: r, label: t(`sequences.reset.${r}`) }))} />
        </Form.Item>
        <Form.Item name="gapFree" label={t('sequences.field.gapFree')} valuePropName="checked" extra={t('sequences.gapFreeHint')}>
          <Switch />
        </Form.Item>
        <Space size={16} wrap>
          <Form.Item
            name="startValue"
            label={t('sequences.field.startValue')}
            extra={editing ? t('sequences.fixedAfterCreate') : undefined}
            rules={[{ required: true, type: 'number', min: 0, message: t('sequences.nonNegative') }]}
          >
            <InputNumber disabled={editing} precision={0} min={0} style={{ width: 180 }} />
          </Form.Item>
          <Form.Item
            name="incrementBy"
            label={t('sequences.field.incrementBy')}
            rules={[{ required: true, type: 'number', min: 1, message: t('sequences.positive') }]}
          >
            <InputNumber precision={0} min={1} style={{ width: 180 }} />
          </Form.Item>
        </Space>
        {editing ? (
          <Form.Item name="status" label={tc('field.status')}>
            <Select options={SEQUENCE_STATUSES.map((s) => ({ value: s, label: tc(`status.${s}`) }))} />
          </Form.Item>
        ) : null}
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ preview

/** Asks the server for the next number on a date (and legal entity), without consuming it. */
function PreviewModal({ sequence, onClose }: { sequence: Sequence; onClose: () => void }) {
  const { t } = useTranslation(CONFIGURATION_NS);
  const { t: tc } = useTranslation();
  const preview = useSequencePreview(sequence.code);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [onDate, setOnDate] = useState<Dayjs | null>(dayjs());
  const leNeeded = needsLegalEntity(sequence.scopeLevel, sequence.pattern);
  const dateUsed = needsDate(sequence.resetRule, sequence.pattern);

  const run = () =>
    preview.mutate({ legalEntityId, onDate: onDate ? onDate.format(ISO_DATE) : undefined });

  return (
    <Modal
      open
      onCancel={onClose}
      title={t('sequences.previewTitle', { code: sequence.code })}
      footer={<Button onClick={onClose}>{tc('action.close')}</Button>}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">{t('sequences.previewHint')}</Typography.Paragraph>
      <Space wrap align="start">
        {leNeeded ? (
          <LegalEntitySelect style={{ width: 260 }} value={legalEntityId} onChange={setLegalEntityId} />
        ) : null}
        {dateUsed ? (
          <DatePicker
            format={DATE_FORMAT}
            value={onDate}
            onChange={setOnDate}
            aria-label={t('sequences.field.onDate')}
            placeholder={t('sequences.field.onDate')}
          />
        ) : null}
        <Button type="primary" onClick={run} loading={preview.isPending} disabled={leNeeded && !legalEntityId}>
          {t('sequences.previewRun')}
        </Button>
      </Space>
      {preview.error ? <ProblemAlert error={preview.error} style={{ marginTop: 12 }} /> : null}
      {preview.data ? (
        <div style={{ marginTop: 16 }}>
          <Typography.Text type="secondary">{t('sequences.previewNext')}</Typography.Text>
          <Typography.Title level={3} style={{ margin: 0, fontFamily: 'monospace' }} data-testid="preview-next">
            {preview.data.next}
          </Typography.Title>
        </div>
      ) : null}
    </Modal>
  );
}
