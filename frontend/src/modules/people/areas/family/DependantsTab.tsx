import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, DatePicker, Form, Input, Select, Space, Tag, Typography } from 'antd';
import { PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, FormDrawer, useFeedback } from '@/shared/components';
import { applyFieldErrors, CodeLabel, type EmployeeProfile, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { DEPENDANT_REASONS, type DependantStatus, type DependantView, type EligibilityResult, type RegistrationRequest } from './api';
import { RELATIONSHIP_LIST } from './FamilyMemberDrawer';
import { employmentOptions, formatMonth, MONTH_FORMAT, toYearMonth } from './months';
import { useDependants, useEndDependant, useFamilyMembers, useMemberDocuments, useRegisterDependant, useValidateDependant } from './queries';
import './i18n';

const STATUS_COLOURS: Record<DependantStatus, string> = { PENDING: 'gold', APPROVED: 'green', REJECTED: 'red', ENDED: 'default' };

/**
 * PIT dependant claims (BR-PPL-017, US-PPL-028/029): register with an eligibility pre-check, request the end of a
 * claim; both go through the `hr.DEPENDANT_CHANGE` approval and stay PENDING until decided.
 */
export function DependantsTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation();
  const { t: tf } = useTranslation('people-family');
  const query = useDependants(personId);
  const [registering, setRegistering] = useState(false);
  const [ending, setEnding] = useState<DependantView | null>(null);

  return (
    <div>
      <Space style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {tf('dependants.title')}
        </Typography.Title>
        <PermissionGate anyOf={P.DEPENDANT_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setRegistering(true)}>
            {tf('dependants.register')}
          </Button>
        </PermissionGate>
      </Space>

      <DataTable<DependantView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: tf('dependants.member'), dataIndex: 'familyMemberName' },
          { title: tf('members.relationship'), dataIndex: 'relationshipCode', render: (c: string) => <CodeLabel list={RELATIONSHIP_LIST} code={c} /> },
          { title: tf('dependants.reason'), dataIndex: 'reasonCode', render: (c: string) => tf(`dependants.reasons.${c}`, { defaultValue: c }) },
          { title: tf('dependants.fromMonth'), dataIndex: 'fromMonth', render: (v: string) => formatMonth(v) },
          { title: tf('dependants.toMonth'), dataIndex: 'toMonth', render: (v: string | null) => formatMonth(v) || '—' },
          {
            title: t('field.status'),
            dataIndex: 'status',
            render: (s: DependantStatus, d) => (
              <Space direction="vertical" size={0}>
                <Tag color={STATUS_COLOURS[s]}>{tf(`dependants.status.${s}`, { defaultValue: s })}</Tag>
                {d.pendingEndMonth ? (
                  <Typography.Text type="warning" style={{ fontSize: 12 }}>
                    {tf('dependants.pendingEnd', { month: formatMonth(d.pendingEndMonth) })}
                  </Typography.Text>
                ) : null}
              </Space>
            ),
          },
          { title: tf('dependants.taxRef'), dataIndex: 'taxRegistrationRef', render: (v: string | null) => v ?? '—' },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, d) =>
              d.status === 'APPROVED' && !d.pendingEndRequestId ? (
                <PermissionGate anyOf={P.DEPENDANT_UPDATE}>
                  <Button type="link" size="small" onClick={() => setEnding(d)}>
                    {tf('dependants.end')}
                  </Button>
                </PermissionGate>
              ) : null,
          },
        ]}
      />

      <RegisterDependantDrawer personId={personId} profile={profile} open={registering} onClose={() => setRegistering(false)} />
      <EndDependantDrawer personId={personId} dependant={ending} onClose={() => setEnding(null)} />
    </div>
  );
}

interface RegisterValues {
  familyMemberId: UUID;
  employmentId?: UUID;
  fromMonth: Dayjs;
  toMonth?: Dayjs | null;
  reasonCode: string;
  evidenceDocumentIds?: UUID[];
  taxRegistrationRef?: string;
}

function RegisterDependantDrawer({
  personId,
  profile,
  open,
  onClose,
}: {
  personId: UUID;
  profile: EmployeeProfile;
  open: boolean;
  onClose: () => void;
}) {
  const { t: tf } = useTranslation('people-family');
  const fb = useFeedback();
  const [form] = Form.useForm<RegisterValues>();
  const members = useFamilyMembers(personId);
  const memberId = Form.useWatch('familyMemberId', form);
  const docs = useMemberDocuments(memberId);
  const validate = useValidateDependant(personId);
  const register = useRegisterDependant(personId);
  const [check, setCheck] = useState<EligibilityResult | null>(null);
  const close = () => {
    setCheck(null);
    onClose();
  };

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    validate.reset();
    register.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, form]);

  const toBody = (v: RegisterValues): RegistrationRequest => ({
    familyMemberId: v.familyMemberId,
    employmentId: v.employmentId ?? null,
    fromMonth: toYearMonth(v.fromMonth) as string,
    toMonth: toYearMonth(v.toMonth),
    reasonCode: v.reasonCode,
    evidenceDocumentIds: v.evidenceDocumentIds ?? [],
    taxRegistrationRef: v.taxRegistrationRef?.trim() || null,
  });

  const values = async () => {
    try {
      return await form.validateFields();
    } catch {
      return null;
    }
  };

  const onCheck = async () => {
    const v = await values();
    if (!v) return;
    try {
      setCheck(await validate.mutateAsync(toBody(v)));
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const submit = async () => {
    const v = await values();
    if (!v) return;
    try {
      await register.mutateAsync(toBody(v));
      fb.done(tf('dependants.submitted'));
      close();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={close}
      title={tf('dependants.register')}
      onSubmit={() => void submit()}
      submitting={register.isPending}
      error={register.error ?? validate.error}
      footerExtra={
        <Button icon={<SafetyCertificateOutlined />} loading={validate.isPending} onClick={() => void onCheck()}>
          {tf('dependants.check')}
        </Button>
      }
    >
      {check ? (
        check.eligible ? (
          <Alert type="success" showIcon message={tf('dependants.eligible')} style={{ marginBottom: 16 }} />
        ) : (
          <Alert
            type="error"
            showIcon
            message={tf('dependants.ineligible')}
            description={
              <ul style={{ margin: 0, paddingLeft: 16 }}>
                {check.violations.map((x, i) => (
                  <li key={i}>
                    {x.message}
                    {x.month ? ` (${x.month})` : ''}
                  </li>
                ))}
              </ul>
            }
            style={{ marginBottom: 16 }}
          />
        )
      ) : null}
      <Form<RegisterValues> form={form} layout="vertical" onValuesChange={() => setCheck(null)}>
        <Form.Item name="familyMemberId" label={tf('dependants.member')} rules={[{ required: true, message: tf('dependants.memberRequired') }]}>
          <Select
            loading={members.isLoading}
            options={(members.data ?? []).filter((m) => !m.deceased).map((m) => ({ value: m.id, label: m.fullName }))}
            onChange={() => form.setFieldValue('evidenceDocumentIds', [])}
          />
        </Form.Item>
        <Form.Item name="reasonCode" label={tf('dependants.reason')} rules={[{ required: true, message: tf('dependants.reasonRequired') }]}>
          <Select options={DEPENDANT_REASONS.map((r) => ({ value: r, label: tf(`dependants.reasons.${r}`) }))} />
        </Form.Item>
        <Space wrap style={{ width: '100%' }}>
          <Form.Item name="fromMonth" label={tf('dependants.fromMonth')} rules={[{ required: true, message: tf('dependants.fromRequired') }]}>
            <DatePicker picker="month" format={MONTH_FORMAT} />
          </Form.Item>
          <Form.Item name="toMonth" label={tf('dependants.toMonth')}>
            <DatePicker picker="month" format={MONTH_FORMAT} />
          </Form.Item>
        </Space>
        <Form.Item name="employmentId" label={tf('dependants.claimEmployment')} extra={tf('dependants.claimEmploymentHint')}>
          <Select allowClear options={employmentOptions(profile)} />
        </Form.Item>
        <Form.Item name="evidenceDocumentIds" label={tf('dependants.evidence')} extra={memberId && docs.data?.length === 0 ? tf('dependants.noEvidence') : undefined}>
          <Checkbox.Group
            options={(docs.data ?? []).map((d) => ({ value: d.id, label: d.title ?? d.documentTypeCode }))}
            style={{ display: 'flex', flexDirection: 'column' }}
          />
        </Form.Item>
        <Form.Item name="taxRegistrationRef" label={tf('dependants.taxRef')} rules={[{ max: 100 }]}>
          <Input />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

function EndDependantDrawer({ personId, dependant, onClose }: { personId: UUID; dependant: DependantView | null; onClose: () => void }) {
  const { t: tf } = useTranslation('people-family');
  const fb = useFeedback();
  const [form] = Form.useForm<{ toMonth: Dayjs; evidenceDocumentIds?: UUID[] }>();
  const end = useEndDependant(personId);
  const docs = useMemberDocuments(dependant?.familyMemberId);

  useEffect(() => {
    if (!dependant) return;
    form.resetFields();
    end.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [dependant, form]);

  const submit = async () => {
    if (!dependant) return;
    let v: { toMonth: Dayjs; evidenceDocumentIds?: UUID[] };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await end.mutateAsync({
        id: dependant.id,
        body: { toMonth: toYearMonth(v.toMonth) as string, version: dependant.version, evidenceDocumentIds: v.evidenceDocumentIds ?? [] },
      });
      fb.done(tf('dependants.endRequested'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={dependant !== null}
      onClose={onClose}
      title={tf('dependants.endTitle', { name: dependant?.familyMemberName ?? '' })}
      onSubmit={() => void submit()}
      submitting={end.isPending}
      error={end.error}
      width={440}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="toMonth" label={tf('dependants.endMonth')} rules={[{ required: true, message: tf('dependants.endRequired') }]}>
          <DatePicker picker="month" format={MONTH_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="evidenceDocumentIds" label={tf('dependants.evidence')}>
          <Checkbox.Group
            options={(docs.data ?? []).map((d) => ({ value: d.id, label: d.title ?? d.documentTypeCode }))}
            style={{ display: 'flex', flexDirection: 'column' }}
          />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
