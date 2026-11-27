import { useState } from 'react';
import { Button, Checkbox, Form, Input, InputNumber, Select, Space, Tag, Tooltip } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { AttendancePermissions as P } from '../../permissions';
import { CATEGORY_PATTERN, CODE_KINDS, CODE_PATTERN, COLOR_PATTERN, PAID_BY, type CodeRequest, type CodeRow } from './api';
import { useAttendanceCodes, useSaveCode } from './queries';
import { applyFieldErrors, NS } from './components';

/** Attendance code catalogue (BR-ATT-031): platform defaults (read-only) plus tenant codes. */
export function AttendanceCodesPage() {
  const { t, i18n } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [form, setForm] = useState<{ code: CodeRow | null } | null>(null);
  const canUpdate = usePermission(P.ATTENDANCE_CODE_UPDATE);
  const q = useAttendanceCodes();
  const en = i18n.language.startsWith('en');
  const rows = [...(q.data ?? [])].sort((a, b) => a.displayOrder - b.displayOrder || a.code.localeCompare(b.code));

  return (
    <div>
      <PageHeader
        title={t('codes.title')}
        subTitle={t('codes.subtitle')}
        extra={
          <PermissionGate anyOf={P.ATTENDANCE_CODE_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ code: null })}>
              {t('codes.create')}
            </Button>
          </PermissionGate>
        }
      />
      <DataTable<CodeRow>
        data={rows}
        isLoading={q.isLoading}
        error={q.error}
        locale={{ emptyText: t('codes.empty') }}
        columns={[
          {
            title: t('field.code'),
            dataIndex: 'code',
            render: (c: string, r) => (
              <Space>
                <Tag color={r.colorHex ?? undefined}>{c}</Tag>
                {r.global ? <Tag>{t('codes.global')}</Tag> : null}
              </Space>
            ),
          },
          { title: t('field.label'), key: 'label', render: (_, r) => (en ? r.labelEn || r.labelVi : r.labelVi) },
          { title: t('field.kind'), dataIndex: 'kind', render: (k: string) => t(`kind.${k}`, { defaultValue: k }) },
          {
            title: t('field.payrollCategory'),
            key: 'category',
            render: (_, r) => (r.payrollCategory ? <code>{[r.payrollCategory, r.payrollSubCode].filter(Boolean).join('.')}</code> : null),
          },
          { title: t('field.countsAsWorked'), dataIndex: 'countsAsWorked', render: (b: boolean) => (b ? tc('common.yes') : tc('common.no')) },
          { title: t('field.paidBy'), dataIndex: 'paidBy', render: (p: string) => t(`paidBy.${p}`, { defaultValue: p }) },
          { title: t('field.siNonwork'), dataIndex: 'siNonwork', render: (b: boolean) => (b ? tc('common.yes') : tc('common.no')) },
          { title: t('field.displayOrder'), dataIndex: 'displayOrder', align: 'right' },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
          ...(canUpdate
            ? [
                {
                  title: tc('field.actions'),
                  key: 'actions',
                  render: (_: unknown, r: CodeRow) =>
                    r.global ? (
                      <Tooltip title={t('codes.globalHelp')}>
                        <Button size="small" icon={<EditOutlined />} disabled aria-label={t('codes.editAria', { code: r.code })} />
                      </Tooltip>
                    ) : (
                      <Button size="small" icon={<EditOutlined />} aria-label={t('codes.editAria', { code: r.code })} onClick={() => setForm({ code: r })} />
                    ),
                },
              ]
            : []),
        ]}
      />
      {form ? <CodeForm code={form.code} onClose={() => setForm(null)} /> : null}
    </div>
  );
}

interface FormValues {
  code: string;
  labelVi: string;
  labelEn?: string;
  kind: string;
  payrollCategory?: string;
  payrollSubCode?: string;
  countsAsWorked: boolean;
  paidBy: string;
  siNonwork: boolean;
  displayOrder?: number | null;
  colorHex?: string;
  status: string;
}

const textOrNull = (v: string | null | undefined) => v?.trim() || null;

function CodeForm({ code, onClose }: { code: CodeRow | null; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const save = useSaveCode();

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const body: CodeRequest = {
      code: v.code.trim(),
      labelVi: v.labelVi.trim(),
      labelEn: textOrNull(v.labelEn),
      kind: v.kind,
      payrollCategory: textOrNull(v.payrollCategory),
      payrollSubCode: textOrNull(v.payrollSubCode),
      countsAsWorked: v.countsAsWorked,
      paidBy: v.paidBy,
      siNonwork: v.siNonwork,
      displayOrder: v.displayOrder ?? null,
      colorHex: textOrNull(v.colorHex),
      status: v.status,
      ...(code ? { version: code.version } : {}),
    };
    try {
      await save.mutateAsync({ id: code?.id, body });
      if (code) fb.saved();
      else fb.created();
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={code ? t('codes.edit', { code: code.code }) : t('codes.create')}
      onSubmit={() => void submit()}
      submitting={save.isPending}
      error={save.error}
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={
          code
            ? {
                ...code,
                labelEn: code.labelEn ?? undefined,
                payrollCategory: code.payrollCategory ?? undefined,
                payrollSubCode: code.payrollSubCode ?? undefined,
                colorHex: code.colorHex ?? undefined,
              }
            : { kind: 'WORK', paidBy: 'EMPLOYER', countsAsWorked: true, siNonwork: false, status: 'ACTIVE', displayOrder: 100 }
        }
      >
        <Form.Item
          name="code"
          label={t('field.code')}
          normalize={(v: string) => v?.toUpperCase()}
          rules={[
            { required: true, message: tc('common.required') },
            { pattern: CODE_PATTERN, message: t('errors.invalid-code') },
          ]}
        >
          <Input maxLength={10} />
        </Form.Item>
        <Form.Item name="labelVi" label={t('field.labelVi')} rules={[{ required: true, whitespace: true, message: tc('common.required') }]}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="labelEn" label={t('field.labelEn')}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="kind" label={t('field.kind')} rules={[{ required: true, message: tc('common.required') }]}>
          <Select options={CODE_KINDS.map((k) => ({ value: k, label: t(`kind.${k}`) }))} />
        </Form.Item>
        <Space wrap>
          <Form.Item
            name="payrollCategory"
            label={t('field.payrollCategory')}
            normalize={(v: string) => v?.toUpperCase()}
            rules={[{ pattern: CATEGORY_PATTERN, message: t('errors.invalid-payroll-category') }]}
          >
            <Input style={{ width: 240 }} placeholder="WORKED_DAYS" />
          </Form.Item>
          <Form.Item name="payrollSubCode" label={t('field.payrollSubCode')}>
            <Input style={{ width: 160 }} />
          </Form.Item>
        </Space>
        <Form.Item name="paidBy" label={t('field.paidBy')} rules={[{ required: true, message: tc('common.required') }]}>
          <Select options={PAID_BY.map((p) => ({ value: p, label: t(`paidBy.${p}`) }))} />
        </Form.Item>
        <Form.Item name="countsAsWorked" valuePropName="checked">
          <Checkbox>{t('field.countsAsWorked')}</Checkbox>
        </Form.Item>
        <Form.Item name="siNonwork" valuePropName="checked">
          <Checkbox>{t('field.siNonwork')}</Checkbox>
        </Form.Item>
        <Space wrap>
          <Form.Item name="displayOrder" label={t('field.displayOrder')}>
            <InputNumber min={0} max={9999} />
          </Form.Item>
          <Form.Item name="colorHex" label={t('field.colorHex')} rules={[{ pattern: COLOR_PATTERN, message: t('errors.invalid-color') }]}>
            <Input style={{ width: 140 }} placeholder="#1677FF" maxLength={7} />
          </Form.Item>
        </Space>
        <Form.Item name="status" label={t('field.status')} rules={[{ required: true, message: tc('common.required') }]}>
          <Select options={['ACTIVE', 'INACTIVE'].map((s) => ({ value: s, label: tc(`status.${s}`) }))} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
