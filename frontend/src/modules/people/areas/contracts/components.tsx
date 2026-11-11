import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, DatePicker, Divider, Form, Input, InputNumber, Radio, Select, Space, Upload } from 'antd';
import { FileTextOutlined, UploadOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { ISODate, UUID } from '@/shared/api';
import { usePermission } from '@/shared/auth';
import { FormDrawer, MoneyInput, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, today } from '@/shared/format';
import {
  applyFieldErrors,
  blankToNull,
  fromIsoDate,
  JobTitleSelect,
  LocationSelect,
  OrgUnitSelect,
  toIsoDate,
} from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import {
  APPENDIX_CHANGE_KEYS,
  APPENDIX_PAY_KEYS,
  CONTRACT_TYPES,
  DECISION_TYPES,
  documentLinkApi,
  END_DATE_REQUIRED,
  PROBATION_CLAUSE_ALLOWED,
  signForm,
  SUSPENSION_REASONS,
  type AppendixChangeKey,
  type ContractTypeCode,
  type ContractView,
  type DecisionType,
  type IssueDecisionRequest,
  type ProbationResultRequest,
  type ReissueDecisionRequest,
  type SuspensionReason,
  type SuspensionRequest,
  type TermsRequest,
} from './api';
import './i18n';

export const NS = 'people-contracts';

/** Status tag with this area's labels (contracts, appendices, suspensions, decisions share one map). */
export function AreaStatusTag({ status }: { status: string | null | undefined }) {
  const { t } = useTranslation(NS);
  const labels = useMemo(
    () =>
      Object.fromEntries(
        ['DRAFT', 'PENDING_SIGNATURE', 'ACTIVE', 'SUSPENDED', 'EXPIRED', 'TERMINATED', 'SUPERSEDED', 'CANCELLED', 'SIGNED', 'APPROVED', 'ENDED', 'PENDING_APPROVAL'].map(
          (s) => [s, t(`status.${s}`)],
        ),
      ),
    [t],
  );
  return <StatusTag status={status} labels={labels} />;
}

export function ContractTypeLabel({ code }: { code: string | null | undefined }) {
  const { t } = useTranslation(NS);
  return <>{code ? t(`type.${code}`, { defaultValue: code }) : '—'}</>;
}

/** "01/01/2026 – 31/12/2026" or "01/01/2026 –" (end inclusive). */
export function formatTerm(start: ISODate | null | undefined, end: ISODate | null | undefined): string {
  return `${formatDate(start)} – ${end ? formatDate(end) : ''}`.trim();
}

/** Opens a Core document through a short-lived signed URL (never stored). */
export function DocumentLinkButton({ documentId, size = 'small' }: { documentId: UUID | null | undefined; size?: 'small' | 'middle' }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [loading, setLoading] = useState(false);
  if (!documentId) return null;
  const open = async () => {
    setLoading(true);
    try {
      const link = await documentLinkApi.downloadLink(documentId);
      window.open(link.url, '_blank', 'noopener');
    } catch (e) {
      fb.failed(e);
    } finally {
      setLoading(false);
    }
  };
  return (
    <Button size={size} icon={<FileTextOutlined />} loading={loading} onClick={() => void open()}>
      {t('contracts.open')}
    </Button>
  );
}

// ------------------------------------------------------------------------------------------------ sign

/**
 * Paper signature (R2-10: no e-sign in P1): signed scan + (optionally) the sign date, sent multipart with the record
 * version. Used for contracts and decisions (`withSignDate`) and appendices (scan only).
 */
export function SignDrawer({
  open,
  title,
  version,
  withSignDate,
  onClose,
  submit,
  pending,
  error,
  successText,
}: {
  open: boolean;
  title: string;
  version: number;
  withSignDate: boolean;
  onClose: () => void;
  submit: (form: FormData) => Promise<unknown>;
  pending: boolean;
  error: unknown;
  successText: string;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm<{ signDate?: Dayjs | null; file?: UploadFile[] }>();

  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  const onSubmit = async () => {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const file = values.file?.[0]?.originFileObj as File | undefined;
    if (!file) return;
    try {
      await submit(signForm(file, version, withSignDate ? toIsoDate(values.signDate) : null));
      fb.done(successText);
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={title} onSubmit={() => void onSubmit()} submitting={pending} error={error} width={480}>
      <Form form={form} layout="vertical" initialValues={{ signDate: fromIsoDate(today()) }}>
        {withSignDate ? (
          <Form.Item name="signDate" label={t('field.signDate')} rules={[{ required: true, message: t('field.signDate') }]}>
            <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
        ) : null}
        <Form.Item
          name="file"
          label={t('field.file')}
          valuePropName="fileList"
          getValueFromEvent={(e: { fileList: UploadFile[] }) => e.fileList.slice(-1)}
          rules={[{ required: true, type: 'array', min: 1, message: t('field.fileRequired') }]}
        >
          <Upload beforeUpload={() => false} maxCount={1} accept=".pdf,.jpg,.jpeg,.png">
            <Button icon={<UploadOutlined />}>{t('field.file')}</Button>
          </Upload>
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ terms

interface TermsValues {
  contractTypeCode?: ContractTypeCode;
  startDate?: Dayjs | null;
  endDate?: Dayjs | null;
  probationDays?: number | null;
  probationLevelCode?: string;
  probationPayPct?: number | null;
  signatoryName?: string;
  signatoryTitle?: string;
  jobTitleId?: UUID | null;
  definiteExceptionCode?: string;
  definiteExceptionNote?: string;
}

export function termsToRequest(v: TermsValues): TermsRequest {
  const { startDate, endDate, ...rest } = v;
  const type = v.contractTypeCode;
  return {
    ...blankToNull(rest as Record<string, unknown>),
    contractTypeCode: type ?? null,
    startDate: toIsoDate(startDate),
    endDate: type === 'INDEFINITE' ? null : toIsoDate(endDate),
    probationDays: type === 'PROBATION' || (type && PROBATION_CLAUSE_ALLOWED.includes(type)) ? (v.probationDays ?? null) : null,
  } as TermsRequest;
}

/**
 * The fields of a `TermsRequest`, for use inside a `Form`. `prefix` nests them (e.g. `['nextContract']` in the
 * probation drawer). Probation levels and two-definite exceptions come from the legal pack (no list API), so they are
 * free-text codes validated by the backend.
 */
export function TermsFields({ prefix = [], allowedTypes = CONTRACT_TYPES }: { prefix?: string[]; allowedTypes?: ContractTypeCode[] }) {
  const { t } = useTranslation(NS);
  const form = Form.useFormInstance();
  const type = Form.useWatch([...prefix, 'contractTypeCode'], form) as ContractTypeCode | undefined;
  const name = (n: string) => [...prefix, n];
  const needsEnd = !!type && END_DATE_REQUIRED.includes(type);
  const probation = type === 'PROBATION' || (!!type && PROBATION_CLAUSE_ALLOWED.includes(type));
  const [showException, setShowException] = useState(false);

  return (
    <>
      <Form.Item name={name('contractTypeCode')} label={t('field.contractType')} rules={[{ required: true, message: t('contracts.typeRequired') }]}>
        <Select options={allowedTypes.map((c) => ({ value: c, label: t(`type.${c}`) }))} disabled={allowedTypes.length === 1} />
      </Form.Item>
      <Space.Compact block>
        <Form.Item
          name={name('startDate')}
          label={t('field.startDate')}
          style={{ width: '50%' }}
          rules={[{ required: true, message: t('contracts.startRequired') }]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name={name('endDate')}
          label={t('field.endDate')}
          style={{ width: '50%' }}
          rules={[{ required: needsEnd, message: t('contracts.endRequired') }]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} disabled={type === 'INDEFINITE'} />
        </Form.Item>
      </Space.Compact>
      {probation ? (
        <Space.Compact block>
          <Form.Item name={name('probationDays')} label={t('field.probationDays')} style={{ width: '33%' }}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name={name('probationPayPct')} label={t('field.probationPayPct')} style={{ width: '33%' }}>
            <InputNumber min={0} max={100} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name={name('probationLevelCode')} label={t('field.probationLevel')} tooltip={t('field.probationLevelHint')} style={{ width: '34%' }}>
            <Input maxLength={64} />
          </Form.Item>
        </Space.Compact>
      ) : null}
      <Form.Item name={name('jobTitleId')} label={t('field.jobTitle')}>
        <JobTitleSelect />
      </Form.Item>
      <Space.Compact block>
        <Form.Item name={name('signatoryName')} label={t('field.signatoryName')} style={{ width: '50%' }} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name={name('signatoryTitle')} label={t('field.signatoryTitle')} style={{ width: '50%' }} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
      </Space.Compact>
      {type === 'DEFINITE' || type === 'SEASONAL' ? (
        <>
          <Checkbox checked={showException} onChange={(e) => setShowException(e.target.checked)} style={{ marginBottom: 12 }}>
            {t('field.definiteException')}
          </Checkbox>
          {showException ? (
            <>
              <Form.Item name={name('definiteExceptionCode')} label={t('field.definiteException')} rules={[{ max: 64 }]}>
                <Input />
              </Form.Item>
              <Form.Item name={name('definiteExceptionNote')} label={t('field.definiteExceptionNote')} rules={[{ max: 1000 }]}>
                <Input.TextArea rows={2} />
              </Form.Item>
            </>
          ) : null}
        </>
      ) : null}
    </>
  );
}

/** Initial terms of a follow-up contract: starts the day after `previous` ends, same job title / signatory. */
export function followUpTerms(previous: ContractView | null | undefined, type?: ContractTypeCode): TermsValues {
  const start = previous?.endDate ? fromIsoDate(previous.endDate)?.add(1, 'day') : fromIsoDate(today());
  return {
    contractTypeCode: type,
    startDate: start ?? null,
    signatoryName: previous?.signatoryName ?? undefined,
    signatoryTitle: previous?.signatoryTitle ?? undefined,
  };
}

/** Create / renew / convert drawer. */
export function TermsDrawer({
  open,
  title,
  initial,
  allowedTypes,
  onClose,
  submit,
  pending,
  error,
}: {
  open: boolean;
  title: string;
  initial?: TermsValues;
  allowedTypes?: ContractTypeCode[];
  onClose: () => void;
  submit: (body: TermsRequest) => Promise<unknown>;
  pending: boolean;
  error: unknown;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm<TermsValues>();

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    if (initial) form.setFieldsValue(initial);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer opens (`initial` is rebuilt each render)
  }, [open, form]);

  const onSubmit = async () => {
    let values: TermsValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await submit(termsToRequest(values));
      fb.done(t('contracts.saved'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={title} onSubmit={() => void onSubmit()} submitting={pending} error={error} width={640}>
      <Form<TermsValues> form={form} layout="vertical">
        <TermsFields allowedTypes={allowedTypes} />
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ probation

export function ProbationDrawer({
  open,
  contract,
  onClose,
  submit,
  pending,
  error,
}: {
  open: boolean;
  contract: ContractView;
  onClose: () => void;
  submit: (body: ProbationResultRequest) => Promise<{ decisionNo?: string | null }>;
  pending: boolean;
  error: unknown;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm();
  const result = Form.useWatch('result', form) as 'PASS' | 'FAIL' | undefined;
  const agreement = contract.contractTypeCode === 'PROBATION';

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({
      resultDate: fromIsoDate(today()),
      signatoryName: contract.signatoryName ?? undefined,
      signatoryTitle: contract.signatoryTitle ?? undefined,
      nextContract: { ...followUpTerms(contract, 'DEFINITE'), jobTitleId: undefined },
    });
  }, [open, contract, form]);

  const onSubmit = async () => {
    let v;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const recorded = await submit({
        result: v.result,
        resultDate: toIsoDate(v.resultDate),
        signatoryName: v.signatoryName || null,
        signatoryTitle: v.signatoryTitle || null,
        nextContract: agreement && v.result === 'PASS' && v.nextContract ? termsToRequest(v.nextContract) : null,
      });
      fb.done(recorded.decisionNo ? t('contracts.decisionIssued', { no: recorded.decisionNo }) : t('contracts.probationRecorded'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={t('contracts.probationTitle')} onSubmit={() => void onSubmit()} submitting={pending} error={error} width={640}>
      <Form form={form} layout="vertical">
        <Form.Item name="result" label={t('field.probationResult')} rules={[{ required: true, message: t('field.probationResult') }]}>
          <Radio.Group
            optionType="button"
            buttonStyle="solid"
            options={[
              { value: 'PASS', label: t('probationResult.PASS') },
              { value: 'FAIL', label: t('probationResult.FAIL') },
            ]}
          />
        </Form.Item>
        <Form.Item name="resultDate" label={t('field.probationResultDate')} extra={contract.probationEndDate ? `${t('field.probationEndDate')}: ${formatDate(contract.probationEndDate)}` : undefined}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Space.Compact block>
          <Form.Item name="signatoryName" label={t('field.signatoryName')} style={{ width: '50%' }}>
            <Input />
          </Form.Item>
          <Form.Item name="signatoryTitle" label={t('field.signatoryTitle')} style={{ width: '50%' }}>
            <Input />
          </Form.Item>
        </Space.Compact>
        {agreement && result === 'PASS' ? (
          <>
            <Divider orientation="left">{t('contracts.probationNextContract')}</Divider>
            <Alert type="info" showIcon message={t('contracts.probationNextHint')} style={{ marginBottom: 12 }} />
            <TermsFields prefix={['nextContract']} allowedTypes={['DEFINITE', 'INDEFINITE', 'SEASONAL']} />
          </>
        ) : null}
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ appendix

interface ChangeRow {
  key?: AppendixChangeKey;
  value?: unknown;
}

/** New appendix: effective date + one or more amended terms (never the contract term, BR-PPL-055). */
export function AppendixDrawer({
  open,
  contract,
  onClose,
  submit,
  pending,
  error,
}: {
  open: boolean;
  contract: ContractView;
  onClose: () => void;
  submit: (body: { effectiveDate: ISODate; changes: Record<string, unknown> }) => Promise<unknown>;
  pending: boolean;
  error: unknown;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const canPay = usePermission(P.COMPENSATION_UPDATE);
  const [form] = Form.useForm<{ effectiveDate?: Dayjs | null; changes?: ChangeRow[] }>();
  const rows = (Form.useWatch('changes', form) ?? []) as ChangeRow[];
  const keys = APPENDIX_CHANGE_KEYS.filter((k) => canPay || !APPENDIX_PAY_KEYS.includes(k));

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({ effectiveDate: fromIsoDate(today()), changes: [{}] });
  }, [open, form]);

  const onSubmit = async () => {
    let v;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const changes: Record<string, unknown> = {};
    for (const r of v.changes ?? []) if (r.key && r.value !== undefined && r.value !== null && r.value !== '') changes[r.key] = r.value;
    if (Object.keys(changes).length === 0) {
      form.setFields([{ name: 'changes', errors: [t('appendices.changesRequired')] }]);
      return;
    }
    try {
      await submit({ effectiveDate: toIsoDate(v.effectiveDate) as ISODate, changes });
      fb.done(t('appendices.created'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={t('appendices.create')} onSubmit={() => void onSubmit()} submitting={pending} error={error} width={640}>
      <Alert type="info" showIcon message={t('appendices.termHint')} style={{ marginBottom: 12 }} />
      <Form form={form} layout="vertical">
        <Form.Item
          name="effectiveDate"
          label={t('field.effectiveDate')}
          extra={formatTerm(contract.startDate, contract.endDate)}
          rules={[{ required: true, message: t('field.effectiveDate') }]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.List name="changes">
          {(fields, { add, remove }, { errors }) => (
            <>
              {fields.map((f, i) => {
                const k = rows[i]?.key;
                const used = rows.map((r) => r?.key).filter((x, j) => x && j !== i);
                return (
                  <Space key={f.key} align="start" style={{ display: 'flex' }} wrap>
                    <Form.Item name={[f.name, 'key']} rules={[{ required: true, message: t('field.changes') }]} style={{ width: 200 }}>
                      <Select
                        aria-label={t('field.changes')}
                        placeholder={t('field.changes')}
                        options={keys.filter((x) => !used.includes(x)).map((x) => ({ value: x, label: t(`changeKey.${x}`) }))}
                        onChange={() => form.setFieldValue(['changes', f.name, 'value'], undefined)}
                      />
                    </Form.Item>
                    <Form.Item name={[f.name, 'value']} rules={[{ required: true, message: t('field.changes') }]} style={{ width: 300 }}>
                      {changeInput(k, t(`changeKey.${k ?? 'other'}`))}
                    </Form.Item>
                    <Button type="link" danger onClick={() => remove(f.name)} disabled={fields.length === 1}>
                      ✕
                    </Button>
                  </Space>
                );
              })}
              <Form.ErrorList errors={errors} />
              <Button onClick={() => add({})} disabled={fields.length >= keys.length}>
                {t('appendices.addChange')}
              </Button>
              {!canPay ? <div style={{ marginTop: 8, color: 'rgba(0,0,0,0.45)' }}>{t('appendices.payNeedsPermission')}</div> : null}
            </>
          )}
        </Form.List>
      </Form>
    </FormDrawer>
  );
}

function changeInput(key: AppendixChangeKey | undefined, label: string) {
  switch (key) {
    case 'salary':
      return <MoneyInput aria-label={label} style={{ width: '100%' }} />;
    case 'jobTitleId':
      return <JobTitleSelect aria-label={label} />;
    case 'orgUnitId':
      return <OrgUnitSelect aria-label={label} />;
    case 'locationId':
      return <LocationSelect aria-label={label} />;
    case 'workingTime':
    case 'other':
    case 'allowances':
      return <Input.TextArea aria-label={label} rows={1} autoSize />;
    default:
      return <Input aria-label={label} />;
  }
}

/** Renders an appendix's `changes` map with this area's term labels. */
export function ChangesList({ changes, masked }: { changes: Record<string, unknown>; masked: boolean }) {
  const { t } = useTranslation(NS);
  return (
    <Space direction="vertical" size={0}>
      {Object.entries(changes).map(([k, v]) => (
        <span key={k}>
          <b>{t(`changeKey.${k}`, { defaultValue: k })}:</b> {String(v)}
        </span>
      ))}
      {masked ? <i>{t('appendices.masked')}</i> : null}
    </Space>
  );
}

// ------------------------------------------------------------------------------------------------ suspension

export function SuspensionDrawer({
  open,
  contract,
  onClose,
  submit,
  pending,
  error,
}: {
  open: boolean;
  contract: ContractView;
  onClose: () => void;
  submit: (body: SuspensionRequest) => Promise<unknown>;
  pending: boolean;
  error: unknown;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm();

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({ signatoryName: contract.signatoryName ?? undefined, signatoryTitle: contract.signatoryTitle ?? undefined });
  }, [open, contract, form]);

  const onSubmit = async () => {
    let v;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await submit({
        reasonCode: v.reasonCode as SuspensionReason,
        validFrom: toIsoDate(v.validFrom) as ISODate,
        plannedEndDate: toIsoDate(v.plannedEndDate),
        signatoryName: v.signatoryName || null,
        signatoryTitle: v.signatoryTitle || null,
      });
      fb.done(t('suspensions.created'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={t('suspensions.create')} onSubmit={() => void onSubmit()} submitting={pending} error={error} width={520}>
      <Form form={form} layout="vertical">
        <Form.Item name="reasonCode" label={t('field.reason')} rules={[{ required: true, message: t('field.reason') }]}>
          <Select options={SUSPENSION_REASONS.map((r) => ({ value: r, label: t(`suspensionReason.${r}`) }))} />
        </Form.Item>
        <Space.Compact block>
          <Form.Item name="validFrom" label={t('field.validFrom')} style={{ width: '50%' }} rules={[{ required: true, message: t('field.validFrom') }]}>
            <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="plannedEndDate" label={t('field.plannedEndDate')} style={{ width: '50%' }}>
            <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
        </Space.Compact>
        <Space.Compact block>
          <Form.Item name="signatoryName" label={t('field.signatoryName')} style={{ width: '50%' }}>
            <Input />
          </Form.Item>
          <Form.Item name="signatoryTitle" label={t('field.signatoryTitle')} style={{ width: '50%' }}>
            <Input />
          </Form.Item>
        </Space.Compact>
      </Form>
    </FormDrawer>
  );
}

/** One-date drawer (return to work, notice date…). */
export function DateDrawer({
  open,
  title,
  label,
  required = true,
  hint,
  onClose,
  submit,
  pending,
  error,
}: {
  open: boolean;
  title: string;
  label: string;
  required?: boolean;
  hint?: string;
  onClose: () => void;
  submit: (date: ISODate | null) => Promise<unknown>;
  pending: boolean;
  error: unknown;
}) {
  const [form] = Form.useForm();
  useEffect(() => {
    if (open) form.setFieldsValue({ date: fromIsoDate(today()) });
  }, [open, form]);
  const onSubmit = async () => {
    let v;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await submit(toIsoDate(v.date));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };
  return (
    <FormDrawer open={open} onClose={onClose} title={title} onSubmit={() => void onSubmit()} submitting={pending} error={error} width={420}>
      {hint ? <Alert type="info" showIcon message={hint} style={{ marginBottom: 12 }} /> : null}
      <Form form={form} layout="vertical">
        <Form.Item name="date" label={label} rules={[{ required, message: label }]}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ decisions

/** Issue (employment known) or reissue (`reissueOf`) a decision. */
export function DecisionDrawer({
  open,
  employmentId,
  reissueOf,
  onClose,
  issue,
  reissue,
  pending,
  error,
}: {
  open: boolean;
  employmentId?: UUID;
  reissueOf?: { decisionNo?: string | null; effectiveDate: ISODate; signatoryName?: string | null; signatoryTitle?: string | null; version: number } | null;
  onClose: () => void;
  issue?: (body: IssueDecisionRequest) => Promise<unknown>;
  reissue?: (body: ReissueDecisionRequest) => Promise<unknown>;
  pending: boolean;
  error: unknown;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm();

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    if (reissueOf) {
      form.setFieldsValue({
        effectiveDate: fromIsoDate(reissueOf.effectiveDate),
        signatoryName: reissueOf.signatoryName ?? undefined,
        signatoryTitle: reissueOf.signatoryTitle ?? undefined,
      });
    } else form.setFieldsValue({ effectiveDate: fromIsoDate(today()) });
  }, [open, reissueOf, form]);

  const onSubmit = async () => {
    let v;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      if (reissueOf && reissue) {
        await reissue({
          effectiveDate: toIsoDate(v.effectiveDate),
          signatoryName: v.signatoryName || null,
          signatoryTitle: v.signatoryTitle || null,
          version: reissueOf.version,
        });
        fb.done(t('decisions.reissued'));
      } else if (issue && employmentId) {
        await issue({
          employmentId,
          decisionTypeCode: v.decisionTypeCode as DecisionType,
          effectiveDate: toIsoDate(v.effectiveDate) as ISODate,
          signatoryName: v.signatoryName || null,
          signatoryTitle: v.signatoryTitle || null,
        });
        fb.done(t('decisions.issued'));
      }
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={reissueOf ? t('decisions.reissueTitle', { no: reissueOf.decisionNo ?? '' }) : t('decisions.create')}
      onSubmit={() => void onSubmit()}
      submitting={pending}
      error={error}
      width={480}
    >
      {reissueOf ? <Alert type="info" showIcon message={t('decisions.reissueHint')} style={{ marginBottom: 12 }} /> : null}
      <Form form={form} layout="vertical">
        {!reissueOf ? (
          <Form.Item name="decisionTypeCode" label={t('field.decisionType')} rules={[{ required: true, message: t('decisions.typeRequired') }]}>
            <Select showSearch optionFilterProp="label" options={DECISION_TYPES.map((d) => ({ value: d, label: t(`decisionType.${d}`) }))} />
          </Form.Item>
        ) : null}
        <Form.Item name="effectiveDate" label={t('field.effectiveDate')} rules={[{ required: !reissueOf, message: t('field.effectiveDate') }]}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="signatoryName" label={t('field.signatoryName')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="signatoryTitle" label={t('field.signatoryTitle')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
