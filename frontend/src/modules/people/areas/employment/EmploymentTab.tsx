import { useEffect, useState } from 'react';
import { Alert, Button, Card, DatePicker, Form, Input, Radio, Select, Skeleton, Space, Tag, Typography } from 'antd';
import { EditOutlined, ReloadOutlined, SwapOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, DescriptionList, FormDrawer, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime, formatValidity } from '@/shared/format';
import {
  applyFieldErrors,
  blankToNull,
  EmployeeCategorySelect,
  EmploymentStatusTag,
  fromIsoDate,
  LegalEntityName,
  toIsoDate,
  type ProfileTabProps,
} from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { INSURANCE_TYPES, type EmploymentView, type InsuranceType, type ParticipationView, type PrimaryView } from './api';
import {
  useEmployment,
  useEmploymentStatus,
  useInsurance,
  useOverrideInsurance,
  usePersonEmployments,
  usePrimaryEmployment,
  useRederiveInsurance,
  useRequestPrimaryChange,
  useUpdateEmployment,
} from './queries';
import './i18n';

const NS = 'people-employment';

/** Employment facts (US-PPL-017), PIT primary employment (US-PPL-022) and insurance participation (US-PPL-023). */
export function EmploymentTab({ employmentId, personId, profile }: ProfileTabProps) {
  const { t: te } = useTranslation(NS);
  const { t } = useTranslation();
  const query = useEmployment(employmentId);
  const status = useEmploymentStatus(employmentId, profile.asOf);
  const [editing, setEditing] = useState(false);
  const e = query.data;

  if (query.isLoading) return <Skeleton active />;
  if (query.error || !e) return <ProblemAlert error={query.error} />;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {e.incomplete && e.missingData.length > 0 ? (
        <Alert
          type="warning"
          showIcon
          message={te('employment.incomplete', {
            items: e.missingData.map((m) => te(`employment.missing.${m}`, { defaultValue: m })).join(', '),
          })}
        />
      ) : null}

      <Card
        size="small"
        title={te('employment.facts')}
        extra={
          <PermissionGate anyOf={P.EMPLOYMENT_UPDATE}>
            <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(true)}>
              {t('action.edit')}
            </Button>
          </PermissionGate>
        }
      >
        <DescriptionList
          items={[
            { label: te('employment.employeeNo'), value: e.employeeNo },
            { label: t('field.status'), value: <EmploymentStatusTag status={e.status} /> },
            {
              label: te('employment.statusOn', { date: formatDate(status.data?.asOf ?? profile.asOf) }),
              value: status.data ? <EmploymentStatusTag status={status.data.statusOn} /> : null,
            },
            { label: te('employment.hireType'), value: te(`employment.hireTypes.${e.hireType}`, { defaultValue: e.hireType }) },
            { label: te('employment.hireDate'), value: formatDate(e.hireDate) },
            { label: te('employment.originalHireDate'), value: formatDate(e.originalHireDate), hideEmpty: true },
            { label: te('employment.seniorityDate'), value: formatDate(e.seniorityDate) },
            { label: te('employment.probationEndDate'), value: formatDate(e.probationEndDate), hideEmpty: true },
            { label: te('employment.endDate'), value: formatDate(e.endDate), hideEmpty: true },
            { label: te('employment.lastWorkingDate'), value: formatDate(e.lastWorkingDate), hideEmpty: true },
            { label: te('employment.noticeState'), value: e.noticeState, hideEmpty: true },
            { label: te('employment.workerCategory'), value: e.workerCategoryCode },
            { label: te('employment.employeeCategory'), value: e.employeeCategoryCode },
            { label: te('employment.workingRegime'), value: e.workingRegimeCode },
            {
              label: te('employment.hireDecision'),
              value: e.hireDecisionId ? (
                <Link to="/people/decisions/$decisionId" params={{ decisionId: e.hireDecisionId }}>
                  {e.hireDecisionNo ?? e.hireDecisionId}
                </Link>
              ) : null,
              hideEmpty: true,
            },
            { label: te('employment.hireApproval'), value: e.hireApproval, hideEmpty: true },
            {
              label: te('employment.rehireEligible'),
              value: e.rehireEligible === null || e.rehireEligible === undefined ? null : e.rehireEligible ? t('common.yes') : t('common.no'),
              hideEmpty: true,
            },
            { label: te('employment.pitPrimary'), value: e.pitPrimary ? t('common.yes') : t('common.no') },
            { label: t('field.version'), value: e.version },
          ]}
        />
      </Card>

      <PrimaryEmploymentCard personId={personId} employmentId={employmentId} asOf={profile.asOf} />
      <InsuranceCard employmentId={employmentId} asOf={profile.asOf} />

      <EmploymentForm employment={e} open={editing} onClose={() => setEditing(false)} />
    </Space>
  );
}

// ------------------------------------------------------------------------------------------------ edit form

interface EmploymentFormValues {
  workerCategoryCode: string;
  employeeCategoryCode?: string;
  workingRegimeCode?: string;
  originalHireDate?: Dayjs | null;
  seniorityDate?: Dayjs | null;
  probationEndDate?: Dayjs | null;
}

function EmploymentForm({ employment, open, onClose }: { employment: EmploymentView; open: boolean; onClose: () => void }) {
  const { t: te } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm<EmploymentFormValues>();
  const update = useUpdateEmployment(employment.id);

  useEffect(() => {
    if (!open) return;
    update.reset();
    form.setFieldsValue({
      workerCategoryCode: employment.workerCategoryCode,
      employeeCategoryCode: employment.employeeCategoryCode ?? undefined,
      workingRegimeCode: employment.workingRegimeCode ?? undefined,
      originalHireDate: fromIsoDate(employment.originalHireDate),
      seniorityDate: fromIsoDate(employment.seniorityDate),
      probationEndDate: fromIsoDate(employment.probationEndDate),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, employment, form]);

  const submit = async () => {
    let v: EmploymentFormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const codes = blankToNull({
      workerCategoryCode: v.workerCategoryCode.trim(),
      employeeCategoryCode: v.employeeCategoryCode ?? null,
      workingRegimeCode: v.workingRegimeCode?.trim() ?? null,
    });
    try {
      await update.mutateAsync({
        version: employment.version,
        workerCategoryCode: codes.workerCategoryCode as string,
        employeeCategoryCode: codes.employeeCategoryCode,
        workingRegimeCode: codes.workingRegimeCode,
        originalHireDate: toIsoDate(v.originalHireDate),
        seniorityDate: toIsoDate(v.seniorityDate),
        probationEndDate: toIsoDate(v.probationEndDate),
      });
      fb.saved();
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={te('employment.edit')} onSubmit={() => void submit()} submitting={update.isPending} error={update.error}>
      <Alert type="info" showIcon message={te('employment.notEditableHint')} style={{ marginBottom: 16 }} />
      <Form<EmploymentFormValues> form={form} layout="vertical">
        <Form.Item
          name="workerCategoryCode"
          label={te('employment.workerCategory')}
          extra={te('employment.workerCategoryHint')}
          rules={[{ required: true, whitespace: true, message: te('employment.workerCategoryRequired') }, { max: 64 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="employeeCategoryCode" label={te('employment.employeeCategory')}>
          <EmployeeCategorySelect />
        </Form.Item>
        <Form.Item name="workingRegimeCode" label={te('employment.workingRegime')} extra={te('employment.workingRegimeHint')} rules={[{ max: 64 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="originalHireDate" label={te('employment.originalHireDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="seniorityDate" label={te('employment.seniorityDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="probationEndDate" label={te('employment.probationEndDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ---------------------------------------------------------------------------------------- primary employment

function PrimaryEmploymentCard({ personId, employmentId, asOf }: { personId: string; employmentId: string; asOf: string }) {
  const { t: te } = useTranslation(NS);
  const { t } = useTranslation();
  const query = usePrimaryEmployment(personId, asOf);
  const [changing, setChanging] = useState(false);
  const p = query.data;

  const employmentLabel = (id: string | null | undefined, view: PrimaryView) => {
    if (!id) return null;
    const d = view.designations.find((x) => x.employmentId === id);
    const label = d?.employeeNo ?? id;
    return id === employmentId ? (
      <Tag color="blue">{te('primary.thisEmployment')}</Tag>
    ) : (
      <Link to="/people/employees/$employmentId" params={{ employmentId: id }}>
        {label}
      </Link>
    );
  };

  return (
    <Card
      size="small"
      title={te('primary.title')}
      loading={query.isLoading}
      extra={
        <PermissionGate anyOf={P.EMPLOYMENT_UPDATE}>
          <Button size="small" icon={<SwapOutlined />} onClick={() => setChanging(true)} disabled={!p || p.liveEmployments < 2}>
            {te('primary.change')}
          </Button>
        </PermissionGate>
      }
    >
      {query.error ? <ProblemAlert error={query.error} /> : null}
      {p ? (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Text type="secondary">{te('primary.intro')}</Typography.Text>
          {p.basis === 'MISSING' ? <Alert type="error" showIcon message={te('primary.basisValue.MISSING')} /> : null}
          <DescriptionList
            items={[
              { label: te('primary.current'), value: employmentLabel(p.primaryEmploymentId, p) },
              { label: te('primary.basis'), value: te(`primary.basisValue.${p.basis}`, { defaultValue: p.basis }) },
              { label: te('primary.liveEmployments'), value: p.liveEmployments },
            ]}
          />
          {p.pending.length > 0 ? (
            <Alert
              type="info"
              showIcon
              message={te('primary.pending')}
              description={p.pending.map((r) => (
                <div key={r.requestId}>
                  {formatDate(r.validFrom)} · {employmentLabel(r.employmentId, p)} {r.reasonCode ? `· ${r.reasonCode}` : ''}
                </div>
              ))}
            />
          ) : null}
          {p.designations.length > 0 ? (
            <DataTable
              size="small"
              data={p.designations}
              columns={[
                { title: te('primary.employment'), dataIndex: 'employeeNo', render: (_: string, d) => employmentLabel(d.employmentId, p) },
                { title: te('assignments.validity'), key: 'v', render: (_, d) => formatValidity(d.validFrom, d.validTo, t('common.openEnded')) },
                { title: t('field.status'), dataIndex: 'status' },
                { title: te('primary.reasonCode'), dataIndex: 'reasonCode' },
              ]}
            />
          ) : null}
        </Space>
      ) : null}
      <PrimaryChangeDrawer personId={personId} open={changing} onClose={() => setChanging(false)} />
    </Card>
  );
}

interface PrimaryFormValues {
  employmentId: string;
  validFrom: Dayjs;
  reasonCode?: string;
  comment?: string;
}

function PrimaryChangeDrawer({ personId, open, onClose }: { personId: string; open: boolean; onClose: () => void }) {
  const { t: te } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm<PrimaryFormValues>();
  const employments = usePersonEmployments(personId);
  const change = useRequestPrimaryChange(personId);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    change.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, form]);

  const submit = async () => {
    let v: PrimaryFormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const r = await change.mutateAsync({
        employmentId: v.employmentId,
        validFrom: toIsoDate(v.validFrom) as string,
        reasonCode: v.reasonCode?.trim() || null,
        comment: v.comment?.trim() || null,
      });
      fb.done(r.status === 'APPLIED' ? te('primary.applied') : te('primary.requested'));
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={te('primary.change')} onSubmit={() => void submit()} submitting={change.isPending} error={change.error} width={480}>
      <Form<PrimaryFormValues> form={form} layout="vertical">
        <Form.Item name="employmentId" label={te('primary.employment')} rules={[{ required: true }]}>
          <Select
            loading={employments.isLoading}
            options={(employments.data ?? [])
              .filter((x) => ['PRE_HIRE', 'ACTIVE', 'SUSPENDED'].includes(x.status))
              .map((x) => ({ value: x.id, label: <EmploymentOption employment={x} /> }))}
          />
        </Form.Item>
        <Form.Item
          name="validFrom"
          label={te('primary.validFrom')}
          rules={[
            { required: true, message: te('primary.validFromMonthStart') },
            {
              validator: (_, v: Dayjs | undefined) =>
                !v || v.date() === 1 ? Promise.resolve() : Promise.reject(new Error(te('primary.validFromMonthStart'))),
            },
          ]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} disabledDate={(d) => d.date() !== 1} />
        </Form.Item>
        <Form.Item name="reasonCode" label={te('primary.reasonCode')} rules={[{ max: 64 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="comment" label={te('primary.comment')} rules={[{ max: 1000 }]}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

function EmploymentOption({ employment }: { employment: EmploymentView }) {
  return (
    <span>
      {employment.employeeNo} · <LegalEntityName id={employment.legalEntityId} />
    </span>
  );
}

// ------------------------------------------------------------------------------------- insurance participation

function InsuranceCard({ employmentId, asOf }: { employmentId: string; asOf: string }) {
  const { t: te } = useTranslation(NS);
  const { t } = useTranslation();
  const fb = useFeedback();
  const query = useInsurance(employmentId, asOf);
  const rederive = useRederiveInsurance(employmentId);
  const [overriding, setOverriding] = useState<InsuranceType | null>(null);

  const onRederive = async () => {
    try {
      await rederive.mutateAsync(null);
      fb.done(te('insurance.rederived'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <Card
      size="small"
      title={te('insurance.title')}
      extra={
        <PermissionGate anyOf={P.EMPLOYMENT_UPDATE}>
          <Space>
            <ConfirmButton size="small" icon={<ReloadOutlined />} title={te('insurance.rederiveTitle')} loading={rederive.isPending} onConfirm={onRederive}>
              {te('insurance.rederive')}
            </ConfirmButton>
            <Button size="small" onClick={() => setOverriding('SI')}>
              {te('insurance.addOverride')}
            </Button>
          </Space>
        </PermissionGate>
      }
    >
      <DataTable<ParticipationView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: te('insurance.type'), dataIndex: 'insuranceType', render: (x: InsuranceType) => te(`insurance.types.${x}`, { defaultValue: x }) },
          {
            title: t('field.status'),
            dataIndex: 'participating',
            render: (v: boolean) => (v ? <Tag color="green">{te('insurance.participating')}</Tag> : <Tag>{te('insurance.notParticipating')}</Tag>),
          },
          { title: te('assignments.validity'), key: 'v', render: (_, r) => formatValidity(r.validFrom, r.validTo, t('common.openEnded')) },
          { title: te('insurance.basisRule'), dataIndex: 'basisRule' },
          { title: te('insurance.exemption'), dataIndex: 'exemptionReasonCode' },
          {
            title: te('insurance.override'),
            key: 'override',
            render: (_, r) =>
              r.manualOverride ? (
                <Typography.Text>
                  <Tag color="orange">{te('insurance.override')}</Tag>
                  {r.overrideReason}
                </Typography.Text>
              ) : null,
          },
          {
            title: te('insurance.legalPack'),
            key: 'pack',
            render: (_, r) => (r.legalPackVersion ? `v${r.legalPackVersion} · ${formatDateTime(r.decidedAt)}` : formatDateTime(r.decidedAt)),
          },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, r) => (
              <PermissionGate anyOf={P.EMPLOYMENT_UPDATE}>
                <Button type="link" size="small" onClick={() => setOverriding(r.insuranceType)}>
                  {te('insurance.addOverride')}
                </Button>
              </PermissionGate>
            ),
          },
        ]}
      />
      <InsuranceOverrideDrawer employmentId={employmentId} type={overriding} onClose={() => setOverriding(null)} />
    </Card>
  );
}

interface OverrideValues {
  insuranceType: InsuranceType;
  validFrom: Dayjs;
  validTo?: Dayjs | null;
  participating: boolean;
  exemptionReasonCode?: string;
  overrideReason: string;
}

function InsuranceOverrideDrawer({ employmentId, type, onClose }: { employmentId: string; type: InsuranceType | null; onClose: () => void }) {
  const { t: te } = useTranslation(NS);
  const { t } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<OverrideValues>();
  const override = useOverrideInsurance(employmentId);
  const participating = Form.useWatch('participating', form);

  useEffect(() => {
    if (!type) return;
    form.resetFields();
    override.reset();
    form.setFieldsValue({ insuranceType: type, participating: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [type, form]);

  const submit = async () => {
    let v: OverrideValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await override.mutateAsync({
        insuranceType: v.insuranceType,
        validFrom: toIsoDate(v.validFrom) as string,
        validTo: toIsoDate(v.validTo),
        participating: v.participating,
        exemptionReasonCode: v.participating ? null : v.exemptionReasonCode?.trim() || null,
        overrideReason: v.overrideReason.trim(),
      });
      fb.done(te('insurance.overridden'));
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer open={type !== null} onClose={onClose} title={te('insurance.overrideTitle')} onSubmit={() => void submit()} submitting={override.isPending} error={override.error} width={480}>
      <Form<OverrideValues> form={form} layout="vertical">
        <Form.Item name="insuranceType" label={te('insurance.type')} rules={[{ required: true }]}>
          <Select options={INSURANCE_TYPES.map((x) => ({ value: x, label: te(`insurance.types.${x}`) }))} />
        </Form.Item>
        <Form.Item name="participating" label={t('field.status')} rules={[{ required: true }]}>
          <Radio.Group
            options={[
              { value: true, label: te('insurance.participating') },
              { value: false, label: te('insurance.notParticipating') },
            ]}
          />
        </Form.Item>
        <Form.Item name="validFrom" label={te('assignments.effectiveDate')} rules={[{ required: true }]}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="validTo" label={te('insurance.validTo')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        {participating === false ? (
          <Form.Item name="exemptionReasonCode" label={te('insurance.exemption')} extra={te('insurance.exemptionHint')} rules={[{ max: 64 }]}>
            <Input />
          </Form.Item>
        ) : null}
        <Form.Item
          name="overrideReason"
          label={te('insurance.overrideReason')}
          rules={[{ required: true, whitespace: true, message: te('insurance.overrideReasonRequired') }, { max: 1000 }]}
        >
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
