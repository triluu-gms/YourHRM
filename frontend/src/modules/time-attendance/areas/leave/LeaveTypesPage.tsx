import { useEffect, useState } from 'react';
import { Alert, Button, Col, Form, Input, InputNumber, Row, Select, Space, Switch, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { ConfirmButton, DataTable, FormDrawer, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { localizedName } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import {
  COUNTING_BASES,
  LEAVE_CATEGORIES,
  MIN_UNITS,
  PAID_BY,
  PAYROLL_CATEGORIES,
  type LeaveTypeInput,
  type LeaveTypeRow,
  type SeedResult,
} from './api';
import { useAttendanceCodes, useLeaveTypes, useSaveLeaveType, useSeedStatutory } from './queries';
import { applyFieldErrors, enumOptions, NS, useEnumLabel } from './components';

/** Leave types (BR-ATT-055/056): list, create, edit, and seeding of the VN statutory types. */
export function LeaveTypesPage() {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const label = useEnumLabel();
  const q = useLeaveTypes();
  const seed = useSeedStatutory();
  const fb = useFeedback();
  const [editing, setEditing] = useState<LeaveTypeRow | 'new' | null>(null);
  const [seeded, setSeeded] = useState<SeedResult | null>(null);

  return (
    <div>
      <PageHeader
        title={t('types.title')}
        subTitle={t('types.intro')}
        extra={
          <PermissionGate anyOf={P.LEAVE_TYPE_UPDATE}>
            <ConfirmButton
              title={t('types.seedConfirm')}
              loading={seed.isPending}
              onConfirm={async () => {
                try {
                  setSeeded(await seed.mutateAsync());
                } catch (e) {
                  fb.failed(e);
                }
              }}
            >
              {t('types.seed')}
            </ConfirmButton>
            <Button type="primary" onClick={() => setEditing('new')}>
              {tc('action.create')}
            </Button>
          </PermissionGate>
        }
      />
      {seeded ? (
        <Alert
          style={{ marginBottom: 16 }}
          type={seeded.warnings.length ? 'warning' : 'success'}
          showIcon
          closable
          onClose={() => setSeeded(null)}
          message={t('types.seedResult', { created: seeded.created.length, existing: seeded.existing.length })}
          description={
            <Space direction="vertical" size={2}>
              {seeded.created.length ? <span>{t('types.seedCreated', { codes: seeded.created.join(', ') })}</span> : null}
              {seeded.warnings.map((w, i) => (
                <Typography.Text key={i} type="warning">
                  {w}
                </Typography.Text>
              ))}
            </Space>
          }
        />
      ) : null}
      <DataTable<LeaveTypeRow>
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          { title: tc('field.code'), key: 'code', render: (_, r) => r.data.code },
          { title: tc('field.name'), key: 'name', render: (_, r) => <LocalName row={r} /> },
          { title: t('field.category'), key: 'category', render: (_, r) => label('category', r.data.category) },
          { title: t('field.paidBy'), key: 'paidBy', render: (_, r) => label('paidBy', r.data.paidBy) },
          { title: t('field.minUnit'), key: 'minUnit', render: (_, r) => label('unit', r.data.minUnit) },
          { title: t('field.countingBasis'), key: 'basis', render: (_, r) => label('basis', r.data.countingBasis) },
          { title: t('field.attendanceCode'), key: 'ac', render: (_, r) => <Tag>{r.data.attendanceCode}</Tag> },
          {
            title: t('field.flags'),
            key: 'flags',
            render: (_, r) => (
              <Space size={2} wrap>
                {r.data.statutory ? <Tag color="blue">{t('field.statutory')}</Tag> : null}
                {r.data.tracksBalance ? <Tag>{t('field.tracksBalance')}</Tag> : null}
                {r.data.requiresSiClaimData ? <Tag color="purple">{t('field.requiresSiClaimData')}</Tag> : null}
                {r.data.requiresAttachment ? <Tag>{t('field.requiresAttachment')}</Tag> : null}
              </Space>
            ),
          },
          { title: tc('field.status'), key: 'status', render: (_, r) => <StatusTag status={r.data.status} /> },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, r) => (
              <PermissionGate anyOf={P.LEAVE_TYPE_UPDATE}>
                <Button size="small" onClick={() => setEditing(r)}>
                  {tc('action.edit')}
                </Button>
              </PermissionGate>
            ),
          },
        ]}
      />
      <LeaveTypeDrawer row={editing === 'new' ? null : editing} open={editing !== null} onClose={() => setEditing(null)} />
    </div>
  );
}

function LocalName({ row }: { row: LeaveTypeRow }) {
  const { i18n } = useTranslation();
  return <>{localizedName(row.data, i18n.language)}</>;
}

interface TypeForm {
  code: string;
  nameVi: string;
  nameEn?: string;
  category: string;
  paidBy: string;
  minUnit: string;
  countingBasis: string;
  attendanceCode: string;
  payrollCategory: string;
  tracksBalance: boolean;
  requiresSiClaimData: boolean;
  requiresAttachment: boolean;
  countsAsService: boolean;
  maxDaysPerRequest?: number | null;
  maxDaysPerYear?: number | null;
  gender?: 'M' | 'F' | null;
  minServiceMonths?: number | null;
  allowProbation?: 'YES' | 'NO' | null;
  employeeCategories?: string[];
  status: string;
}

function toForm(r: LeaveTypeRow | null): Partial<TypeForm> {
  if (!r) {
    return {
      minUnit: 'DAY',
      countingBasis: 'WORKING_DAY',
      tracksBalance: false,
      requiresSiClaimData: false,
      requiresAttachment: false,
      countsAsService: true,
      status: 'ACTIVE',
    };
  }
  const d = r.data;
  const e = d.eligibility ?? {};
  return {
    ...d,
    nameEn: d.nameEn ?? undefined,
    gender: e.gender ?? null,
    minServiceMonths: e.min_service_months ?? null,
    allowProbation: e.allow_probation === undefined ? null : e.allow_probation ? 'YES' : 'NO',
    employeeCategories: e.employee_categories ?? [],
  };
}

function toInput(v: TypeForm, version?: number): LeaveTypeInput {
  const eligibility: LeaveTypeInput['eligibility'] = {};
  if (v.gender) eligibility.gender = v.gender;
  if (v.minServiceMonths !== null && v.minServiceMonths !== undefined) eligibility.min_service_months = v.minServiceMonths;
  if (v.allowProbation) eligibility.allow_probation = v.allowProbation === 'YES';
  if (v.employeeCategories?.length) eligibility.employee_categories = v.employeeCategories;
  return {
    code: v.code,
    nameVi: v.nameVi,
    nameEn: v.nameEn || null,
    category: v.category,
    paidBy: v.paidBy,
    minUnit: v.minUnit,
    countingBasis: v.countingBasis,
    tracksBalance: v.tracksBalance,
    requiresSiClaimData: v.requiresSiClaimData,
    requiresAttachment: v.requiresAttachment,
    maxDaysPerRequest: v.maxDaysPerRequest ?? null,
    maxDaysPerYear: v.maxDaysPerYear ?? null,
    countsAsService: v.countsAsService,
    attendanceCode: v.attendanceCode,
    payrollCategory: v.payrollCategory,
    eligibility,
    status: v.status,
    version: version ?? null,
  };
}

function LeaveTypeDrawer({ row, open, onClose }: { row: LeaveTypeRow | null; open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const label = useEnumLabel();
  const fb = useFeedback();
  const [form] = Form.useForm<TypeForm>();
  const save = useSaveLeaveType();
  const canSeeCodes = usePermission(P.ATTENDANCE_CODE_VIEW);
  const codes = useAttendanceCodes(open && canSeeCodes);

  useEffect(() => {
    if (open) {
      form.resetFields();
      form.setFieldsValue(toForm(row) as TypeForm);
    }
  }, [open, row, form]);

  const submit = async () => {
    let v: TypeForm;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await save.mutateAsync({ id: row?.id, body: toInput(v, row?.version) });
      fb.saved();
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const required = { required: true, message: tc('common.required') };
  const leaveCodes = (codes.data ?? [])
    .filter((c) => c.kind === 'LEAVE' && c.status === 'ACTIVE')
    .map((c) => ({ value: c.code, label: `${c.code} – ${i18n.language.startsWith('en') ? c.labelEn || c.labelVi : c.labelVi}` }));

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={row ? t('types.edit', { code: row.data.code }) : t('types.create')}
      onSubmit={() => void submit()}
      submitting={save.isPending}
      error={save.error}
      width={720}
    >
      <Form<TypeForm> form={form} layout="vertical">
        <Row gutter={12}>
          <Col span={8}>
            <Form.Item name="code" label={tc('field.code')} rules={[required, { pattern: /^[A-Za-z0-9_]{1,30}$/, message: t('types.codeRule') }]}>
              <Input disabled={!!row} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="nameVi" label={tc('field.nameVi')} rules={[required]}>
              <Input />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="nameEn" label={tc('field.nameEn')}>
              <Input />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="category" label={t('field.category')} rules={[required]}>
              <Select options={enumOptions(label, 'category', LEAVE_CATEGORIES)} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="paidBy" label={t('field.paidBy')} rules={[required]}>
              <Select options={enumOptions(label, 'paidBy', PAID_BY)} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="minUnit" label={t('field.minUnit')}>
              <Select options={enumOptions(label, 'unit', MIN_UNITS)} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="countingBasis" label={t('field.countingBasis')}>
              <Select options={enumOptions(label, 'basis', COUNTING_BASES)} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="attendanceCode" label={t('field.attendanceCode')} rules={[required]} extra={t('types.attendanceCodeHint')}>
              {canSeeCodes ? <Select showSearch optionFilterProp="label" loading={codes.isLoading} options={leaveCodes} /> : <Input />}
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="payrollCategory" label={t('field.payrollCategory')} rules={[required]}>
              <Select options={enumOptions(label, 'payrollCategory', PAYROLL_CATEGORIES)} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="maxDaysPerRequest" label={t('field.maxDaysPerRequest')}>
              <InputNumber min={0.5} step={0.5} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="maxDaysPerYear" label={t('field.maxDaysPerYear')}>
              <InputNumber min={0.5} step={0.5} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          {(['tracksBalance', 'requiresSiClaimData', 'requiresAttachment', 'countsAsService'] as const).map((k) => (
            <Col span={6} key={k}>
              <Form.Item name={k} label={t(`field.${k}`)} valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
          ))}
        </Row>
        <Typography.Title level={5}>{t('types.eligibility')}</Typography.Title>
        <Row gutter={12}>
          <Col span={8}>
            <Form.Item name="gender" label={t('field.gender')}>
              <Select allowClear options={[{ value: 'F', label: t('enum.gender.F') }, { value: 'M', label: t('enum.gender.M') }]} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="minServiceMonths" label={t('field.minServiceMonths')}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="allowProbation" label={t('field.allowProbation')}>
              <Select
                allowClear
                options={[
                  { value: 'YES', label: tc('common.yes') },
                  { value: 'NO', label: tc('common.no') },
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={16}>
            <Form.Item name="employeeCategories" label={t('field.employeeCategories')} extra={t('types.categoriesHint')}>
              <Select mode="tags" tokenSeparators={[',']} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="status" label={tc('field.status')}>
              <Select options={['ACTIVE', 'INACTIVE'].map((s) => ({ value: s, label: tc(`status.${s}`) }))} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </FormDrawer>
  );
}
