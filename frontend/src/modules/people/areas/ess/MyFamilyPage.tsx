import { useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, DatePicker, Form, Input, Select, Space, Tag, Typography, Upload } from 'antd';
import { CheckCircleOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs, { type Dayjs } from 'dayjs';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, FormDrawer, MoneyInput, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { applyFieldErrors, blankToNull, CodeLabel, CodeValueSelect, MaskedValue, toIsoDate } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import {
  DEPENDANT_REASONS,
  type DependantReason,
  type DependantView,
  type EligibilityResult,
  type FamilyMemberView,
  type RegistrationRequest,
} from './api';
import {
  useAddFamilyMember,
  useEndDependant,
  useMyDependants,
  useMyFamilyMembers,
  useRegisterDependant,
  useUploadEvidence,
  useValidateDependant,
} from './queries';
import './i18n';

const RELATIONSHIP_LIST = 'RELATIONSHIP';
const MONTH_FORMAT = 'MM/YYYY';
const DEPENDANT_STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'ENDED', 'CANCELLED', 'END_REJECTED'];

const toYearMonth = (d: Dayjs | null | undefined) => (d ? d.format('YYYY-MM') : null);
/** Month stored as the first day (`2026-01-01`) → `01/2026`. */
const formatMonth = (d: string | null | undefined) => (d ? dayjs(d).format(MONTH_FORMAT) : '');

/**
 * "My family and dependants" (US-PPL-074, BR-PPL-120): own family members and PIT dependant claims. Adding a member,
 * uploading evidence, registering or ending a dependant are requests that HR approves (`hr.DEPENDANT_CHANGE`).
 */
export function MyFamilyPage() {
  const { t: te } = useTranslation('people-ess');
  return (
    <div>
      <PageHeader title={te('family.title')} />
      <Alert type="info" showIcon message={te('requestNote')} style={{ marginBottom: 16 }} />
      <FamilyMembersCard />
      <DependantsCard />
    </div>
  );
}

// ------------------------------------------------------------------------------------------- family members

function FamilyMembersCard() {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const query = useMyFamilyMembers();
  const [adding, setAdding] = useState(false);

  return (
    <Card
      title={te('family.members')}
      style={{ marginBottom: 16 }}
      extra={
        <PermissionGate anyOf={P.ESS_REQUEST}>
          <Button icon={<PlusOutlined />} onClick={() => setAdding(true)}>
            {te('family.add')}
          </Button>
        </PermissionGate>
      }
    >
      <DataTable<FamilyMemberView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: te('family.fullName'), dataIndex: 'fullName' },
          {
            title: te('family.relationship'),
            dataIndex: 'relationshipCode',
            render: (c: string) => <CodeLabel list={RELATIONSHIP_LIST} code={c} />,
          },
          {
            title: te('family.birthDate'),
            key: 'birth',
            render: (_, m) => (m.birthDate ? formatDate(m.birthDate) : (m.birthYear ?? '—')),
          },
          { title: te('family.nationalId'), dataIndex: 'nationalIdMasked', render: (v: string | null) => <MaskedValue value={v} /> },
          { title: te('family.taxCode'), dataIndex: 'taxCodeMasked', render: (v: string | null) => <MaskedValue value={v} /> },
          {
            title: '',
            key: 'flags',
            render: (_, m) => (
              <Space size={4} wrap>
                {m.disabled ? <Tag>{te('family.disabled')}</Tag> : null}
                {m.deceased ? <Tag>{te('family.deceased')}</Tag> : null}
              </Space>
            ),
          },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, m) => (
              <PermissionGate anyOf={P.ESS_REQUEST}>
                <EvidenceUploadButton familyMemberId={m.id} />
              </PermissionGate>
            ),
          },
        ]}
      />
      <AddFamilyMemberDrawer open={adding} onClose={() => setAdding(false)} />
    </Card>
  );
}

/** Uploads one evidence file for a family member (multipart); calls `onUploaded` with the new document id. */
function EvidenceUploadButton({ familyMemberId, onUploaded }: { familyMemberId: UUID; onUploaded?: (id: UUID) => void }) {
  const { t: te } = useTranslation('people-ess');
  const fb = useFeedback();
  const upload = useUploadEvidence();
  return (
    <Upload
      showUploadList={false}
      beforeUpload={(file) => {
        upload
          .mutateAsync({ familyMemberId, file })
          .then((doc) => {
            fb.done(te('family.evidenceUploaded'));
            onUploaded?.(doc.id);
          })
          .catch((e: unknown) => fb.failed(e));
        return false; // handled above, never Ant's own XHR
      }}
    >
      <Button size="small" icon={<UploadOutlined />} loading={upload.isPending}>
        {te('family.uploadEvidence')}
      </Button>
    </Upload>
  );
}

interface MemberValues {
  relationshipCode: string;
  fullName: string;
  birthDate?: Dayjs | null;
  nationalId?: string;
  taxCode?: string;
  hasIncomeAboveThreshold?: boolean;
  averageMonthlyIncome?: number | null;
  disabled?: boolean;
  deceased?: boolean;
}

function AddFamilyMemberDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const fb = useFeedback();
  const [form] = Form.useForm<MemberValues>();
  const add = useAddFamilyMember();

  useEffect(() => {
    if (open) {
      form.resetFields();
      add.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer opens
  }, [open, form]);

  const submit = async () => {
    let v: MemberValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await add.mutateAsync({
        ...blankToNull({ nationalId: v.nationalId ?? '', taxCode: v.taxCode ?? '' }),
        relationshipCode: v.relationshipCode,
        fullName: v.fullName.trim(),
        birthDate: toIsoDate(v.birthDate),
        hasIncomeAboveThreshold: v.hasIncomeAboveThreshold ?? false,
        averageMonthlyIncome: v.averageMonthlyIncome ?? null,
        disabled: !!v.disabled,
        deceased: !!v.deceased,
      });
      fb.done(te('family.added'));
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={te('family.add')}
      onSubmit={() => void submit()}
      submitting={add.isPending}
      error={add.error}
      width={480}
    >
      <Form<MemberValues> form={form} layout="vertical">
        <Form.Item
          name="relationshipCode"
          label={te('family.relationship')}
          rules={[{ required: true, message: te('family.relationshipRequired') }]}
        >
          <CodeValueSelect list={RELATIONSHIP_LIST} />
        </Form.Item>
        <Form.Item
          name="fullName"
          label={te('family.fullName')}
          rules={[{ required: true, whitespace: true, message: te('family.fullNameRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="birthDate" label={te('family.birthDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} disabledDate={(d) => d.isAfter(dayjs())} />
        </Form.Item>
        <Form.Item name="nationalId" label={te('family.nationalId')} rules={[{ max: 32 }]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="taxCode" label={te('family.taxCode')} rules={[{ max: 20 }]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="hasIncomeAboveThreshold" valuePropName="checked">
          <Checkbox>{te('family.incomeAbove')}</Checkbox>
        </Form.Item>
        <Form.Item name="averageMonthlyIncome" label={te('family.averageIncome')} extra={t('common.optional')}>
          <MoneyInput />
        </Form.Item>
        <Form.Item name="disabled" valuePropName="checked">
          <Checkbox>{te('family.disabled')}</Checkbox>
        </Form.Item>
        <Form.Item name="deceased" valuePropName="checked">
          <Checkbox>{te('family.deceased')}</Checkbox>
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------- dependants

function DependantsCard() {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const query = useMyDependants();
  const [registering, setRegistering] = useState(false);
  const [ending, setEnding] = useState<DependantView | null>(null);
  const statusLabels = Object.fromEntries(DEPENDANT_STATUSES.map((s) => [s, te(`dependants.status.${s}`)]));

  return (
    <Card
      title={te('dependants.title')}
      extra={
        <PermissionGate anyOf={P.ESS_REQUEST}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setRegistering(true)}>
            {te('dependants.register')}
          </Button>
        </PermissionGate>
      }
    >
      <DataTable<DependantView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: te('dependants.member'), dataIndex: 'familyMemberName' },
          {
            title: te('family.relationship'),
            dataIndex: 'relationshipCode',
            render: (c: string) => <CodeLabel list={RELATIONSHIP_LIST} code={c} />,
          },
          {
            title: te('dependants.reason'),
            dataIndex: 'reasonCode',
            render: (c: string) => te(`dependants.reasons.${c}`, { defaultValue: c }),
          },
          {
            title: te('dependants.months'),
            key: 'months',
            render: (_, d) => `${formatMonth(d.fromMonth)} – ${d.toMonth ? formatMonth(d.toMonth) : t('common.openEnded')}`,
          },
          {
            title: t('field.status'),
            key: 'status',
            render: (_, d) => (
              <Space size={4} wrap>
                <StatusTag status={d.status} labels={statusLabels} />
                {d.pendingEndMonth ? (
                  <Tag color="gold">{te('dependants.pendingEnd', { month: formatMonth(d.pendingEndMonth) })}</Tag>
                ) : null}
              </Space>
            ),
          },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, d) =>
              d.status === 'APPROVED' && !d.pendingEndMonth && !d.toMonth ? (
                <PermissionGate anyOf={P.ESS_REQUEST}>
                  <Button size="small" type="link" onClick={() => setEnding(d)}>
                    {te('dependants.end')}
                  </Button>
                </PermissionGate>
              ) : null,
          },
        ]}
      />
      {/* Mounted only while open so each opening starts with a fresh form, evidence list and mutation state. */}
      {registering ? <RegisterDependantDrawer open onClose={() => setRegistering(false)} /> : null}
      {ending ? <EndDependantDrawer key={ending.id} dependant={ending} onClose={() => setEnding(null)} /> : null}
    </Card>
  );
}

interface RegisterValues {
  familyMemberId: UUID;
  reasonCode: DependantReason;
  fromMonth: Dayjs;
  toMonth?: Dayjs | null;
  taxRegistrationRef?: string;
}

function RegisterDependantDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t: te } = useTranslation('people-ess');
  const fb = useFeedback();
  const [form] = Form.useForm<RegisterValues>();
  const members = useMyFamilyMembers();
  const validate = useValidateDependant();
  const register = useRegisterDependant();
  const [evidence, setEvidence] = useState<UUID[]>([]);
  const [result, setResult] = useState<EligibilityResult | null>(null);
  const memberId = Form.useWatch('familyMemberId', form);

  const body = async (): Promise<RegistrationRequest | null> => {
    try {
      const v = await form.validateFields();
      return {
        familyMemberId: v.familyMemberId,
        reasonCode: v.reasonCode,
        fromMonth: toYearMonth(v.fromMonth) as string,
        toMonth: toYearMonth(v.toMonth),
        evidenceDocumentIds: evidence,
        taxRegistrationRef: v.taxRegistrationRef?.trim() || null,
      };
    } catch {
      return null;
    }
  };

  const check = async () => {
    const b = await body();
    if (!b) return;
    try {
      setResult(await validate.mutateAsync(b));
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const submit = async () => {
    const b = await body();
    if (!b) return;
    try {
      await register.mutateAsync(b);
      fb.done(te('dependants.requested'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={te('dependants.register')}
      onSubmit={() => void submit()}
      submitting={register.isPending}
      error={register.error ?? validate.error}
      submitLabel={te('dependants.submit')}
      footerExtra={
        <Button icon={<CheckCircleOutlined />} loading={validate.isPending} onClick={() => void check()}>
          {te('dependants.check')}
        </Button>
      }
      width={520}
    >
      <Form<RegisterValues> form={form} layout="vertical" onValuesChange={() => setResult(null)}>
        <Form.Item name="familyMemberId" label={te('dependants.member')} rules={[{ required: true, message: te('dependants.memberRequired') }]}>
          <Select
            loading={members.isLoading}
            options={(members.data ?? []).filter((m) => !m.deceased).map((m) => ({ value: m.id, label: m.fullName }))}
            onChange={() => setEvidence([])}
          />
        </Form.Item>
        <Form.Item name="reasonCode" label={te('dependants.reason')} rules={[{ required: true, message: te('dependants.reasonRequired') }]}>
          <Select options={DEPENDANT_REASONS.map((r) => ({ value: r, label: te(`dependants.reasons.${r}`) }))} />
        </Form.Item>
        <div style={{ display: 'flex', gap: 12 }}>
          <Form.Item
            name="fromMonth"
            label={te('dependants.fromMonth')}
            rules={[{ required: true, message: te('dependants.fromMonthRequired') }]}
            style={{ flex: 1 }}
          >
            <DatePicker picker="month" format={MONTH_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="toMonth" label={te('dependants.toMonth')} style={{ flex: 1 }}>
            <DatePicker picker="month" format={MONTH_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
        </div>
        <Form.Item name="taxRegistrationRef" label={te('dependants.taxRegistrationRef')} rules={[{ max: 100 }]}>
          <Input />
        </Form.Item>
        <Form.Item label={te('dependants.evidence')} extra={te('dependants.evidenceHint')}>
          <Space wrap>
            {memberId ? (
              <EvidenceUploadButton familyMemberId={memberId} onUploaded={(id) => setEvidence((prev) => [...prev, id])} />
            ) : null}
            <Typography.Text type="secondary">{te('dependants.evidenceCount', { count: evidence.length })}</Typography.Text>
          </Space>
        </Form.Item>
      </Form>
      {result ? <EligibilityAlert result={result} /> : null}
    </FormDrawer>
  );
}

function EligibilityAlert({ result }: { result: EligibilityResult }) {
  const { t: te } = useTranslation('people-ess');
  if (result.eligible) return <Alert type="success" showIcon message={te('dependants.eligible')} />;
  return (
    <Alert
      type="error"
      showIcon
      message={te('dependants.ineligible')}
      description={
        <ul style={{ margin: 0, paddingLeft: 16 }}>
          {result.violations.map((v, i) => (
            <li key={`${v.code}-${i}`}>
              {te(`dependants.violations.${v.code}`, { defaultValue: v.message ?? v.code })}
              {v.month ? ` (${formatMonth(v.month)})` : ''}
            </li>
          ))}
        </ul>
      }
    />
  );
}

function EndDependantDrawer({ dependant, onClose }: { dependant: DependantView | null; onClose: () => void }) {
  const { t: te } = useTranslation('people-ess');
  const fb = useFeedback();
  const [form] = Form.useForm<{ toMonth: Dayjs }>();
  const end = useEndDependant();
  const [evidence, setEvidence] = useState<UUID[]>([]);

  const submit = async () => {
    if (!dependant) return;
    let v: { toMonth: Dayjs };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await end.mutateAsync({
        id: dependant.id,
        body: { toMonth: toYearMonth(v.toMonth) as string, version: dependant.version, evidenceDocumentIds: evidence },
      });
      fb.done(te('dependants.endRequested'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={dependant !== null}
      onClose={onClose}
      title={te('dependants.endTitle', { name: dependant?.familyMemberName ?? '' })}
      onSubmit={() => void submit()}
      submitting={end.isPending}
      error={end.error}
      submitLabel={te('dependants.submit')}
      width={440}
    >
      <Form<{ toMonth: Dayjs }> form={form} layout="vertical">
        <Form.Item name="toMonth" label={te('dependants.endMonth')} rules={[{ required: true, message: te('dependants.endMonthRequired') }]}>
          <DatePicker
            picker="month"
            format={MONTH_FORMAT}
            style={{ width: '100%' }}
            disabledDate={(d) => (dependant ? d.isBefore(dayjs(dependant.fromMonth), 'month') : false)}
          />
        </Form.Item>
        <Form.Item label={te('dependants.evidence')}>
          <Space wrap>
            {dependant ? (
              <EvidenceUploadButton familyMemberId={dependant.familyMemberId} onUploaded={(id) => setEvidence((p) => [...p, id])} />
            ) : null}
            <Typography.Text type="secondary">{te('dependants.evidenceCount', { count: evidence.length })}</Typography.Text>
          </Space>
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
