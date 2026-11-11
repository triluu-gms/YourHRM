import { useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, DatePicker, Form, Input, InputNumber, Radio, Select, Space, Tag, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DataTable, DescriptionList, FormDrawer, MoneyInput, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatMoney, formatValidity } from '@/shared/format';
import { applyFieldErrors, toIsoDate, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import {
  PAY_BASES,
  SALARY_ACTIONS,
  SALARY_KINDS,
  type PayBasis,
  type SalaryAction,
  type SalaryChangeResult,
  type SalaryKind,
  type SalaryView,
} from './api';
import { needsPurpose, useRequestSalaryChange, useSalaryAsOf, useSalaryHistory, useSalaryPurpose } from './queries';
import { SalaryPurposeBanner, SalaryPurposeForm } from './SalaryPurpose';
import './i18n';

const NS = 'people-employment';

/**
 * Agreed compensation (EP-PPL-05): current salary as of the profile date and the history. Reads need a purpose
 * (US-PPL-045); there is no direct edit: changes and corrections are change requests through approval (US-PPL-042).
 */
export function CompensationTab({ employmentId, profile }: ProfileTabProps) {
  const [purpose] = useSalaryPurpose();
  if (!purpose) return <SalaryPurposeForm />;
  return <CompensationContent employmentId={employmentId} asOf={profile.asOf} purpose={purpose} />;
}

function CompensationContent({
  employmentId,
  asOf,
  purpose,
}: {
  employmentId: string;
  asOf: string;
  purpose: string;
}) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(NS);
  const [, setPurpose] = useSalaryPurpose();
  const current = useSalaryAsOf(employmentId, asOf, purpose);
  const history = useSalaryHistory(employmentId, purpose, true);
  const [request, setRequest] = useState<{ corrects?: SalaryView } | null>(null);
  const [lastResult, setLastResult] = useState<SalaryChangeResult | null>(null);

  // The backend may still refuse (purpose expired on its node): ask again.
  useEffect(() => {
    if (needsPurpose(current.error) || needsPurpose(history.error)) setPurpose(undefined);
  }, [current.error, history.error, setPurpose]);

  const s = current.data;
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <SalaryPurposeBanner />
      {lastResult ? (
        <Alert
          closable
          onClose={() => setLastResult(null)}
          showIcon
          type={lastResult.warnings.length > 0 ? 'warning' : 'success'}
          message={
            <Space wrap>
              {te(`salary.requestStatus.${lastResult.status}`, { defaultValue: lastResult.status })}
              {lastResult.decisionId ? (
                <Link to="/people/decisions/$decisionId" params={{ decisionId: lastResult.decisionId }}>
                  {te('salary.decision', { no: lastResult.decisionNo ?? '' })}
                </Link>
              ) : null}
            </Space>
          }
          description={
            lastResult.warnings.length > 0 ? (
              <ul style={{ margin: 0, paddingLeft: 16 }}>
                {lastResult.warnings.map((w) => (
                  <li key={w.code}>{te(`errors.${w.code}`, { defaultValue: w.message })}</li>
                ))}
              </ul>
            ) : undefined
          }
        />
      ) : null}

      <Card
        size="small"
        title={te('salary.current', { date: formatDate(asOf) })}
        loading={current.isLoading}
        extra={
          <PermissionGate anyOf={P.COMPENSATION_UPDATE}>
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => setRequest({})}>
              {te('salary.change')}
            </Button>
          </PermissionGate>
        }
      >
        {current.error && !needsPurpose(current.error) ? <ProblemAlert error={current.error} /> : null}
        {s ? <SalaryDetails salary={s} /> : !current.isLoading && !current.error ? <Typography.Text type="secondary">{te('salary.none')}</Typography.Text> : null}
      </Card>

      <Card size="small" title={te('salary.history')}>
        <DataTable<SalaryView>
          size="small"
          data={history.data}
          isLoading={history.isLoading}
          error={needsPurpose(history.error) ? undefined : history.error}
          expandable={{ expandedRowRender: (r) => <SalaryDetails salary={r} /> }}
          columns={[
            { title: te('assignments.validity'), key: 'v', render: (_, r) => formatValidity(r.validFrom, r.validTo, t('common.openEnded')) },
            { title: te('salary.payBasis'), dataIndex: 'payBasis', render: (b: PayBasis) => <Tag color={b === 'NET' ? 'purple' : 'blue'}>{b}</Tag> },
            { title: te('salary.baseSalary'), key: 'base', align: 'right', render: (_, r) => <Amount salary={r} value={r.baseSalaryAmount} /> },
            { title: te('salary.agreedNet'), key: 'net', align: 'right', render: (_, r) => (r.payBasis === 'NET' ? <Amount salary={r} value={r.agreedNetAmount} /> : null) },
            { title: te('salary.siSalary'), key: 'si', align: 'right', render: (_, r) => <Amount salary={r} value={r.siSalaryAmount} /> },
            {
              title: te('salary.action'),
              key: 'action',
              render: (_, r) =>
                [r.actionCode ? te(`salary.actions.${r.actionCode}`, { defaultValue: r.actionCode }) : null, r.reasonCode].filter(Boolean).join(' · '),
            },
            { title: te('salary.issuedBy'), key: 'issued', render: (_, r) => <IssuedBy salary={r} /> },
            {
              title: t('field.actions'),
              key: 'actions',
              render: (_, r) =>
                !r.knownTo && !r.supersededBy ? (
                  <PermissionGate anyOf={P.COMPENSATION_UPDATE}>
                    <Button type="link" size="small" onClick={() => setRequest({ corrects: r })}>
                      {te('salary.correct')}
                    </Button>
                  </PermissionGate>
                ) : (
                  <Tag>{te('assignments.superseded')}</Tag>
                ),
            },
          ]}
        />
      </Card>

      <SalaryChangeDrawer
        employmentId={employmentId}
        open={request !== null}
        corrects={request?.corrects ?? null}
        base={s ?? null}
        onClose={() => setRequest(null)}
        onDone={setLastResult}
      />
    </Space>
  );
}

function Amount({ salary, value }: { salary: SalaryView; value: number | null | undefined }) {
  const { t } = useTranslation('people-employment');
  if (salary.amountsMasked) return <Typography.Text type="secondary" title={t('salary.masked')}>••••••</Typography.Text>;
  if (value === null || value === undefined) return null;
  return <>{`${formatMoney(value, salary.currencyCode)} ${salary.currencyCode}`}</>;
}

function IssuedBy({ salary: s }: { salary: SalaryView }) {
  const { t: te } = useTranslation(NS);
  return (
    <Space size={4} wrap>
      {s.decisionId ? (
        <Link to="/people/decisions/$decisionId" params={{ decisionId: s.decisionId }}>
          {te('salary.decision', { no: s.decisionNo ?? '' })}
        </Link>
      ) : null}
      {s.appendixNo ? <span>{te('salary.appendix', { no: s.appendixNo })}</span> : null}
    </Space>
  );
}

/** Read-only salary terms of one version; masked amounts are never shown (BR-CORE-034). */
export function SalaryDetails({ salary: s }: { salary: SalaryView }) {
  const { t: te } = useTranslation(NS);
  const { t } = useTranslation();
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {s.amountsMasked ? <Alert type="info" showIcon message={te('salary.masked')} /> : null}
      <DescriptionList
        items={[
          { label: te('assignments.validity'), value: formatValidity(s.validFrom, s.validTo, t('common.openEnded')) },
          { label: te('salary.payBasis'), value: te(`salary.payBases.${s.payBasis}`, { defaultValue: s.payBasis }) },
          { label: te('salary.salaryKind'), value: te(`salary.salaryKinds.${s.salaryKind}`, { defaultValue: s.salaryKind }) },
          { label: te('salary.baseSalary'), value: <Amount salary={s} value={s.baseSalaryAmount} /> },
          { label: te('salary.agreedNet'), value: <Amount salary={s} value={s.agreedNetAmount} />, hideEmpty: s.payBasis !== 'NET' },
          {
            label: te('salary.netScope'),
            value: s.netGuaranteedScope && s.netGuaranteedScope.length > 0 ? s.netGuaranteedScope.join(', ') : null,
            hideEmpty: true,
          },
          { label: te('salary.siSalary'), value: <Amount salary={s} value={s.siSalaryAmount} /> },
          {
            label: te('salary.payScale'),
            value: s.payScaleCode ? `${s.payScaleCode}${s.payScaleStepCode ? ` / ${s.payScaleStepCode}` : ''}` : null,
            hideEmpty: true,
          },
          { label: te('salary.probationPct'), value: s.probationPct !== null && s.probationPct !== undefined ? `${s.probationPct}%` : null, hideEmpty: true },
          {
            label: te('salary.allowances'),
            value:
              s.allowances.length > 0 ? (
                <ul style={{ margin: 0, paddingLeft: 16 }}>
                  {s.allowances.map((a) => (
                    <li key={a.componentCode}>
                      <code>{a.componentCode}</code>: <Amount salary={s} value={a.amount} />
                      {a.inSiSalary ? <Tag style={{ marginLeft: 8 }}>{te('salary.inSiSalary')}</Tag> : null}
                    </li>
                  ))}
                </ul>
              ) : null,
            hideEmpty: true,
          },
          { label: te('salary.action'), value: s.actionCode ? te(`salary.actions.${s.actionCode}`, { defaultValue: s.actionCode }) : null, hideEmpty: true },
          { label: te('salary.reasonCode'), value: s.reasonCode, hideEmpty: true },
          { label: te('salary.issuedBy'), value: s.decisionId || s.appendixNo ? <IssuedBy salary={s} /> : null, hideEmpty: true },
          { label: te('salary.correctionReason'), value: s.correctionReason, hideEmpty: true },
        ]}
      />
    </Space>
  );
}

// ---------------------------------------------------------------------------------------- change request

interface SalaryFormValues {
  actionCode?: SalaryAction;
  reasonCode?: string;
  effectiveDate?: Dayjs | null;
  correctionReason?: string;
  payBasis: PayBasis;
  salaryKind: SalaryKind;
  baseSalaryAmount: number;
  agreedNetAmount?: number | null;
  netGuaranteedScope?: string[];
  siSalaryAmount?: number | null;
  currencyCode: string;
  payScaleCode?: string;
  payScaleStepCode?: string;
  probationPct?: number | null;
  allowances?: { componentCode: string; amount: number; inSiSalary?: boolean }[];
  contractAppendixId?: string;
  bandOverrideReason?: string;
  signatoryName?: string;
  signatoryTitle?: string;
}

function SalaryChangeDrawer({
  employmentId,
  open,
  corrects,
  base,
  onClose,
  onDone,
}: {
  employmentId: string;
  open: boolean;
  corrects: SalaryView | null;
  base: SalaryView | null;
  onClose: () => void;
  onDone: (r: SalaryChangeResult) => void;
}) {
  const { t: te } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm<SalaryFormValues>();
  const request = useRequestSalaryChange(employmentId);
  const payBasis = Form.useWatch('payBasis', form);
  const currency = Form.useWatch('currencyCode', form) ?? 'VND';

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    request.reset();
    const from = corrects ?? base;
    form.setFieldsValue({
      effectiveDate: corrects ? null : dayjs().add(1, 'month').startOf('month'),
      payBasis: from?.payBasis ?? 'GROSS',
      salaryKind: from?.salaryKind ?? 'MONTHLY',
      currencyCode: from?.currencyCode ?? 'VND',
      // Masked amounts are null: the form then starts empty instead of leaking anything.
      baseSalaryAmount: from?.baseSalaryAmount ?? undefined,
      agreedNetAmount: from?.agreedNetAmount ?? undefined,
      netGuaranteedScope: from?.netGuaranteedScope ?? [],
      siSalaryAmount: from?.siSalaryAmount ?? undefined,
      payScaleCode: from?.payScaleCode ?? undefined,
      payScaleStepCode: from?.payScaleStepCode ?? undefined,
      probationPct: from?.probationPct ?? undefined,
      allowances: (from?.allowances ?? []).map((a) => ({ ...a })),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, corrects, form]);

  const submit = async () => {
    let v: SalaryFormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const net = v.payBasis === 'NET';
    try {
      const r = await request.mutateAsync({
        actionCode: corrects ? null : (v.actionCode ?? null),
        reasonCode: v.reasonCode?.trim() || null,
        effectiveDate: corrects ? null : toIsoDate(v.effectiveDate),
        correctsRecordId: corrects?.id ?? null,
        correctionReason: corrects ? v.correctionReason?.trim() || null : null,
        terms: {
          payBasis: v.payBasis,
          salaryKind: v.salaryKind,
          baseSalaryAmount: v.baseSalaryAmount,
          agreedNetAmount: net ? (v.agreedNetAmount ?? null) : null,
          netGuaranteedScope: net && v.netGuaranteedScope?.length ? v.netGuaranteedScope : null,
          siSalaryAmount: v.siSalaryAmount ?? null,
          currencyCode: v.currencyCode,
          payScaleCode: v.payScaleCode?.trim() || null,
          payScaleStepCode: v.payScaleStepCode?.trim() || null,
          probationPct: v.probationPct ?? null,
          allowances: (v.allowances ?? [])
            .filter((a) => a?.componentCode)
            .map((a) => ({ componentCode: a.componentCode.trim(), amount: a.amount, inSiSalary: !!a.inSiSalary })),
        },
        contractAppendixId: v.contractAppendixId?.trim() || null,
        bandOverrideReason: v.bandOverrideReason?.trim() || null,
        signatoryName: v.signatoryName?.trim() || null,
        signatoryTitle: v.signatoryTitle?.trim() || null,
      });
      fb.done(te(`salary.requestStatus.${r.status}`, { defaultValue: r.status }));
      onDone(r);
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={corrects ? te('salary.correctTitle', { date: formatDate(corrects.validFrom) }) : te('salary.changeTitle')}
      onSubmit={() => void submit()}
      submitting={request.isPending}
      error={request.error}
      width={640}
    >
      <Alert type="info" showIcon message={corrects ? te('salary.correctHint') : te('salary.approvalHint')} style={{ marginBottom: 16 }} />
      <Form<SalaryFormValues> form={form} layout="vertical">
        {corrects ? (
          <Form.Item
            name="correctionReason"
            label={te('salary.correctionReason')}
            rules={[{ required: true, whitespace: true, message: te('salary.correctionReasonRequired') }, { max: 1000 }]}
          >
            <Input.TextArea rows={2} />
          </Form.Item>
        ) : (
          <Space wrap>
            <Form.Item name="actionCode" label={te('salary.action')} style={{ minWidth: 200 }}>
              <Select allowClear options={SALARY_ACTIONS.map((a) => ({ value: a, label: te(`salary.actions.${a}`) }))} />
            </Form.Item>
            <Form.Item name="effectiveDate" label={te('salary.effectiveDate')} rules={[{ required: true, message: te('salary.effectiveDateRequired') }]}>
              <DatePicker format={DATE_FORMAT} />
            </Form.Item>
          </Space>
        )}
        <Form.Item name="reasonCode" label={te('salary.reasonCode')} rules={[{ max: 64 }]}>
          <Input />
        </Form.Item>
        <Space wrap>
          <Form.Item name="payBasis" label={te('salary.payBasis')} rules={[{ required: true }]}>
            <Radio.Group optionType="button" options={PAY_BASES.map((b) => ({ value: b, label: te(`salary.payBases.${b}`) }))} />
          </Form.Item>
          <Form.Item name="salaryKind" label={te('salary.salaryKind')} rules={[{ required: true }]} style={{ minWidth: 180 }}>
            <Select options={SALARY_KINDS.map((k) => ({ value: k, label: te(`salary.salaryKinds.${k}`) }))} />
          </Form.Item>
          <Form.Item name="currencyCode" label={te('salary.currency')} rules={[{ required: true }]} style={{ width: 120 }}>
            <Input maxLength={3} />
          </Form.Item>
        </Space>
        <Form.Item name="baseSalaryAmount" label={te('salary.baseSalary')} rules={[{ required: true, message: te('salary.baseRequired') }]}>
          <MoneyInput currency={currency} min={1} />
        </Form.Item>
        {payBasis === 'NET' ? (
          <>
            <Form.Item name="agreedNetAmount" label={te('salary.agreedNet')} rules={[{ required: true, message: te('salary.agreedNetRequired') }]}>
              <MoneyInput currency={currency} />
            </Form.Item>
            <Form.Item name="netGuaranteedScope" label={te('salary.netScope')} extra={te('salary.netScopeHint')}>
              <Select mode="tags" tokenSeparators={[',', ' ']} />
            </Form.Item>
          </>
        ) : null}
        <Form.Item name="siSalaryAmount" label={te('salary.siSalary')}>
          <MoneyInput currency={currency} />
        </Form.Item>
        <Space wrap>
          <Form.Item name="payScaleCode" label={te('salary.payScaleCode')} rules={[{ max: 64 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="payScaleStepCode" label={te('salary.payScaleStepCode')} rules={[{ max: 64 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="probationPct" label={te('salary.probationPct')}>
            <InputNumber min={0} max={100} addonAfter="%" />
          </Form.Item>
        </Space>

        <Typography.Text strong>{te('salary.allowances')}</Typography.Text>
        <Form.List name="allowances">
          {(fields, { add, remove }) => (
            <div style={{ marginTop: 8, marginBottom: 16 }}>
              {fields.map((f) => (
                <Space key={f.key} align="baseline" wrap style={{ display: 'flex' }}>
                  <Form.Item name={[f.name, 'componentCode']} rules={[{ required: true, whitespace: true }, { max: 64 }]}>
                    <Input placeholder={te('salary.component')} aria-label={te('salary.component')} style={{ width: 160 }} />
                  </Form.Item>
                  <Form.Item name={[f.name, 'amount']} rules={[{ required: true }]}>
                    <MoneyInput currency={currency} aria-label={te('salary.amount')} style={{ width: 220 }} />
                  </Form.Item>
                  <Form.Item name={[f.name, 'inSiSalary']} valuePropName="checked">
                    <Checkbox>{te('salary.inSiSalary')}</Checkbox>
                  </Form.Item>
                  <Button type="text" icon={<DeleteOutlined />} onClick={() => remove(f.name)} aria-label="remove" />
                </Space>
              ))}
              <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ inSiSalary: false })}>
                {te('salary.addAllowance')}
              </Button>
            </div>
          )}
        </Form.List>

        <Form.Item name="contractAppendixId" label={te('salary.contractAppendixId')}>
          <Input style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="bandOverrideReason" label={te('salary.bandOverrideReason')} rules={[{ max: 1000 }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
        <Space wrap>
          <Form.Item name="signatoryName" label={te('salary.signatoryName')} rules={[{ max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="signatoryTitle" label={te('salary.signatoryTitle')} rules={[{ max: 200 }]}>
            <Input />
          </Form.Item>
        </Space>
      </Form>
    </FormDrawer>
  );
}
