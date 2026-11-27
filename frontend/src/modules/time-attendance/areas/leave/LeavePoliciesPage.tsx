import { useEffect, useState } from 'react';
import { Button, Col, DatePicker, Form, Input, InputNumber, Modal, Row, Select } from 'antd';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatValidity } from '@/shared/format';
import { LegalEntitySelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import { ACCRUAL_METHODS, EXPIRY_ACTIONS, PRORATION_RULES, type LeavePolicyInput, type LeavePolicyRow } from './api';
import { useCreatePolicy, useEndPolicy, useLeavePolicies } from './queries';
import {
  applyFieldErrors,
  enumOptions,
  formatQty,
  lastDayToValidTo,
  LeaveTypeName,
  LeaveTypeSelect,
  LegalEntityName,
  NS,
  toIso,
  useEnumLabel,
} from './components';

/** Leave policies (BR-ATT-057/058/060): entitlement, accrual, carry-over per type × legal entity × category (Pattern A). */
export function LeavePoliciesPage() {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const label = useEnumLabel();
  const [leaveTypeId, setLeaveTypeId] = useState<UUID | undefined>();
  const q = useLeavePolicies(leaveTypeId);
  const [creating, setCreating] = useState(false);
  const [ending, setEnding] = useState<LeavePolicyRow | null>(null);

  return (
    <div>
      <PageHeader
        title={t('policies.title')}
        subTitle={t('policies.intro')}
        extra={
          <PermissionGate anyOf={P.LEAVE_TYPE_UPDATE}>
            <Button type="primary" onClick={() => setCreating(true)}>
              {tc('action.create')}
            </Button>
          </PermissionGate>
        }
      >
        <LeaveTypeSelect
          aria-label={t('field.leaveType')}
          placeholder={t('common.allLeaveTypes')}
          style={{ width: 320 }}
          value={leaveTypeId}
          onChange={setLeaveTypeId}
        />
      </PageHeader>
      <DataTable<LeavePolicyRow>
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          { title: tc('field.code'), key: 'code', render: (_, r) => r.data.code },
          { title: t('field.leaveType'), key: 'type', render: (_, r) => <LeaveTypeName id={r.data.leaveTypeId} /> },
          { title: tc('field.legalEntity'), key: 'le', render: (_, r) => <LegalEntityName id={r.data.legalEntityId} /> },
          { title: t('field.employeeCategory'), key: 'cat', render: (_, r) => r.data.employeeCategoryCode ?? t('common.allCategories') },
          { title: t('field.accrualMethod'), key: 'acc', render: (_, r) => label('accrual', r.data.accrualMethod) },
          { title: t('field.baseEntitlementDays'), key: 'base', align: 'right', render: (_, r) => formatQty(r.data.baseEntitlementDays) },
          {
            title: t('field.seniority'),
            key: 'sen',
            render: (_, r) =>
              r.data.seniorityStepYears
                ? t('policies.seniorityValue', { days: formatQty(r.data.seniorityStepDays ?? 1), years: r.data.seniorityStepYears })
                : '',
          },
          {
            title: t('field.carryOver'),
            key: 'carry',
            render: (_, r) =>
              r.data.carryOverMaxDays !== null && r.data.carryOverMaxDays !== undefined
                ? t('policies.carryValue', { days: formatQty(r.data.carryOverMaxDays), months: r.data.carryOverExpiryMonths ?? '—' })
                : '',
          },
          { title: t('field.expiryAction'), key: 'exp', render: (_, r) => label('expiryAction', r.data.expiryAction) },
          {
            title: t('field.validity'),
            key: 'valid',
            render: (_, r) => formatValidity(r.data.validFrom, r.data.validTo, tc('common.openEnded')),
          },
          { title: tc('field.status'), key: 'status', render: (_, r) => <StatusTag status={r.data.status} /> },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, r) =>
              r.data.validTo ? null : (
                <PermissionGate anyOf={P.LEAVE_TYPE_UPDATE}>
                  <Button size="small" onClick={() => setEnding(r)}>
                    {t('policies.end')}
                  </Button>
                </PermissionGate>
              ),
          },
        ]}
      />
      <PolicyDrawer open={creating} onClose={() => setCreating(false)} defaultTypeId={leaveTypeId} />
      <EndPolicyModal key={ending?.id ?? 'none'} row={ending} onClose={() => setEnding(null)} />
    </div>
  );
}

interface PolicyForm {
  leaveTypeId: UUID;
  code: string;
  legalEntityId?: UUID | null;
  employeeCategoryCode?: string;
  accrualMethod?: string;
  baseEntitlementDays?: number | null;
  seniorityStepYears?: number | null;
  seniorityStepDays?: number | null;
  prorationRule?: string;
  leaveYearStartMonth?: number;
  carryOverMaxDays?: number | null;
  carryOverExpiryMonths?: number | null;
  expiryAction?: string;
  allowNegativeDays?: number | null;
  noticeDays?: number | null;
  validFrom: Dayjs;
  lastDay?: Dayjs | null;
}

function PolicyDrawer({ open, onClose, defaultTypeId }: { open: boolean; onClose: () => void; defaultTypeId?: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const label = useEnumLabel();
  const fb = useFeedback();
  const [form] = Form.useForm<PolicyForm>();
  const create = useCreatePolicy();

  useEffect(() => {
    if (open) {
      form.resetFields();
      form.setFieldsValue({
        leaveTypeId: defaultTypeId,
        prorationRule: 'ND145_HALF_MONTH',
        expiryAction: 'FORFEIT',
        leaveYearStartMonth: 1,
        allowNegativeDays: 0,
        noticeDays: 0,
      });
    }
  }, [open, defaultTypeId, form]);

  const submit = async () => {
    let v: PolicyForm;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const body: LeavePolicyInput = {
      leaveTypeId: v.leaveTypeId,
      code: v.code,
      legalEntityId: v.legalEntityId ?? null,
      employeeCategoryCode: v.employeeCategoryCode || null,
      accrualMethod: v.accrualMethod ?? null,
      baseEntitlementDays: v.baseEntitlementDays ?? null,
      seniorityStepYears: v.seniorityStepYears ?? null,
      seniorityStepDays: v.seniorityStepDays ?? null,
      prorationRule: v.prorationRule ?? null,
      leaveYearStartMonth: v.leaveYearStartMonth ?? null,
      carryOverMaxDays: v.carryOverMaxDays ?? null,
      carryOverExpiryMonths: v.carryOverExpiryMonths ?? null,
      expiryAction: v.expiryAction ?? null,
      allowNegativeDays: v.allowNegativeDays ?? null,
      noticeDays: v.noticeDays ?? null,
      validFrom: toIso(v.validFrom) as string,
      validTo: lastDayToValidTo(v.lastDay),
    };
    try {
      await create.mutateAsync(body);
      fb.created();
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const required = { required: true, message: tc('common.required') };
  const num = (min = 0, step = 0.5) => <InputNumber min={min} step={step} style={{ width: '100%' }} />;

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('policies.create')}
      onSubmit={() => void submit()}
      submitting={create.isPending}
      error={create.error}
      width={720}
    >
      <Form<PolicyForm> form={form} layout="vertical">
        <Row gutter={12}>
          <Col span={12}>
            <Form.Item name="leaveTypeId" label={t('field.leaveType')} rules={[required]}>
              <LeaveTypeSelect activeOnly />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="code" label={tc('field.code')} rules={[required]}>
              <Input />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="legalEntityId" label={tc('field.legalEntity')} extra={t('policies.allLegalEntitiesHint')}>
              <LegalEntitySelect />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="employeeCategoryCode" label={t('field.employeeCategory')} extra={t('policies.allCategoriesHint')}>
              <Input />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="accrualMethod" label={t('field.accrualMethod')} extra={t('policies.accrualHint')}>
              <Select allowClear options={enumOptions(label, 'accrual', ACCRUAL_METHODS)} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="baseEntitlementDays" label={t('field.baseEntitlementDays')} extra={t('policies.baseHint')}>
              {num()}
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="prorationRule" label={t('field.prorationRule')}>
              <Select options={enumOptions(label, 'proration', PRORATION_RULES)} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="seniorityStepYears" label={t('field.seniorityStepYears')}>
              {num(1, 1)}
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="seniorityStepDays" label={t('field.seniorityStepDays')}>
              {num()}
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="leaveYearStartMonth" label={t('field.leaveYearStartMonth')}>
              <InputNumber min={1} max={12} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="carryOverMaxDays" label={t('field.carryOverMaxDays')}>
              {num()}
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="carryOverExpiryMonths" label={t('field.carryOverExpiryMonths')}>
              {num(0, 1)}
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="expiryAction" label={t('field.expiryAction')}>
              <Select options={enumOptions(label, 'expiryAction', EXPIRY_ACTIONS)} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="allowNegativeDays" label={t('field.allowNegativeDays')}>
              {num()}
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="noticeDays" label={t('field.noticeDays')}>
              {num(0, 1)}
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="validFrom" label={tc('field.validFrom')} rules={[required]}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="lastDay" label={t('field.lastValidDay')} extra={tc('common.optional')}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </FormDrawer>
  );
}

function EndPolicyModal({ row, onClose }: { row: LeavePolicyRow | null; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const end = useEndPolicy();
  const [lastDay, setLastDay] = useState<Dayjs | null>(null);

  return (
    <Modal
      open={!!row}
      title={t('policies.endTitle', { code: row?.data.code ?? '' })}
      okText={tc('action.confirm')}
      cancelText={tc('action.cancel')}
      okButtonProps={{ disabled: !lastDay, loading: end.isPending }}
      onCancel={onClose}
      onOk={async () => {
        if (!row || !lastDay) return;
        try {
          await end.mutateAsync({ id: row.id, version: row.version, validTo: lastDayToValidTo(lastDay) as string });
          fb.saved();
          onClose();
        } catch {
          /* shown below */
        }
      }}
      destroyOnHidden
    >
      {end.error ? <ProblemAlert error={end.error} style={{ marginBottom: 12 }} /> : null}
      <Form layout="vertical">
        <Form.Item label={t('field.lastValidDay')} required>
          <DatePicker aria-label={t('field.lastValidDay')} format={DATE_FORMAT} style={{ width: '100%' }} value={lastDay} onChange={setLastDay} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
