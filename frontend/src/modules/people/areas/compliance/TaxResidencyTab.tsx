import { useEffect, useState } from 'react';
import { Alert, Button, DatePicker, Form, InputNumber, Select, Space, Tag, Typography } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, FormDrawer, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { applyFieldErrors, fromIsoDate, toIsoDate, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { RESIDENCY_BASES, type PresenceView, type ResidencyStatus, type ResidencyView } from './api';
import { EvidenceCell } from './EvidenceCell';
import { usePresence, useSavePresence, useSubmitTaxResidency, useTaxResidencies } from './queries';
import './i18n';

/** Tax residency (US-PPL-013, submitted for approval) and presence-in-Vietnam periods (US-PPL-014) of the person. */
export function TaxResidencyTab({ personId }: ProfileTabProps) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation('people-compliance');
  const residencies = useTaxResidencies(personId);
  const presence = usePresence(personId);
  const [submitting, setSubmitting] = useState(false);
  const [period, setPeriod] = useState<PresenceView | null | undefined>(undefined);

  return (
    <div>
      <Space style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {tc('residency.title')}
        </Typography.Title>
        <PermissionGate anyOf={P.TAX_RESIDENCY_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setSubmitting(true)}>
            {tc('residency.submit')}
          </Button>
        </PermissionGate>
      </Space>
      <Alert type="info" showIcon message={tc('residency.intro')} style={{ marginBottom: 12 }} />
      <DataTable<ResidencyView>
        size="small"
        data={residencies.data}
        isLoading={residencies.isLoading}
        error={residencies.error}
        columns={[
          {
            title: tc('residency.status'),
            dataIndex: 'status',
            render: (s: ResidencyStatus) => <Tag color={s === 'RESIDENT' ? 'green' : 'default'}>{tc(`residency.statuses.${s}`)}</Tag>,
          },
          { title: tc('residency.basis'), dataIndex: 'basis', render: (b: string) => tc(`residency.bases.${b}`, { defaultValue: b }) },
          { title: tc('common.validFrom'), dataIndex: 'validFrom', render: (v: string) => formatDate(v) },
          { title: tc('common.validTo'), dataIndex: 'validTo', render: (v: string | null) => formatDate(v) || '—' },
          { title: tc('residency.taxYear'), dataIndex: 'taxYear', render: (v: number | null) => v ?? '—' },
          { title: tc('residency.daysInVn'), dataIndex: 'daysInVn', align: 'right', render: (v: number | null) => v ?? '—' },
          { title: tc('residency.source'), dataIndex: 'source', render: (v: string | null) => v ?? '—' },
          { title: tc('common.approval'), dataIndex: 'approvalStatus', render: (s: string | null) => <StatusTag status={s} /> },
          {
            title: tc('common.evidence'),
            key: 'evidence',
            render: (_, r) => (
              <EvidenceCell personId={personId} kind="taxResidency" rowId={r.id} documentId={r.evidenceDocumentId} permission={P.TAX_RESIDENCY_UPDATE} />
            ),
          },
        ]}
      />

      <Space style={{ display: 'flex', justifyContent: 'space-between', margin: '24px 0 12px' }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {tc('presence.title')}
        </Typography.Title>
        <PermissionGate anyOf={P.TAX_RESIDENCY_UPDATE}>
          <Button icon={<PlusOutlined />} onClick={() => setPeriod(null)}>
            {tc('presence.add')}
          </Button>
        </PermissionGate>
      </Space>
      <DataTable<PresenceView>
        size="small"
        data={presence.data}
        isLoading={presence.isLoading}
        error={presence.error}
        columns={[
          { title: tc('presence.entryDate'), dataIndex: 'entryDate', render: (v: string) => formatDate(v) },
          { title: tc('presence.exitDate'), dataIndex: 'exitDate', render: (v: string | null) => (v ? formatDate(v) : <Tag color="blue">{tc('presence.stillInVn')}</Tag>) },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, p) => (
              <PermissionGate anyOf={P.TAX_RESIDENCY_UPDATE}>
                <Button type="link" size="small" icon={<EditOutlined />} onClick={() => setPeriod(p)}>
                  {t('action.edit')}
                </Button>
              </PermissionGate>
            ),
          },
        ]}
      />

      <ResidencyDrawer personId={personId} open={submitting} onClose={() => setSubmitting(false)} />
      <PresenceDrawer personId={personId} period={period} onClose={() => setPeriod(undefined)} />
    </div>
  );
}

interface ResidencyValues {
  status: ResidencyStatus;
  basis: string;
  validFrom: Dayjs;
  validTo?: Dayjs | null;
  arrivalDate?: Dayjs | null;
  departureDate?: Dayjs | null;
  taxYear?: number | null;
  daysInVn?: number | null;
}

function ResidencyDrawer({ personId, open, onClose }: { personId: UUID; open: boolean; onClose: () => void }) {
  const { t: tc } = useTranslation('people-compliance');
  const fb = useFeedback();
  const [form] = Form.useForm<ResidencyValues>();
  const submit = useSubmitTaxResidency(personId);
  const status = Form.useWatch('status', form);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    submit.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, form]);

  const onSubmit = async () => {
    let v: ResidencyValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await submit.mutateAsync({
        status: v.status,
        basis: v.basis,
        validFrom: toIsoDate(v.validFrom) as string,
        validTo: toIsoDate(v.validTo),
        arrivalDate: toIsoDate(v.arrivalDate),
        departureDate: toIsoDate(v.departureDate),
        taxYear: v.taxYear ?? null,
        daysInVn: v.daysInVn ?? null,
      });
      fb.done(tc('residency.submitted'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={tc('residency.submit')} onSubmit={() => void onSubmit()} submitting={submit.isPending} error={submit.error}>
      <Form<ResidencyValues> form={form} layout="vertical">
        <Form.Item name="status" label={tc('residency.status')} rules={[{ required: true, message: tc('residency.statusRequired') }]}>
          <Select
            options={(['RESIDENT', 'NON_RESIDENT'] as const).map((s) => ({ value: s, label: tc(`residency.statuses.${s}`) }))}
            onChange={() => form.setFieldValue('basis', undefined)}
          />
        </Form.Item>
        <Form.Item name="basis" label={tc('residency.basis')} rules={[{ required: true, message: tc('residency.basisRequired') }]}>
          <Select disabled={!status} options={(status ? RESIDENCY_BASES[status] : []).map((b) => ({ value: b, label: tc(`residency.bases.${b}`) }))} />
        </Form.Item>
        <Space wrap>
          <Form.Item name="validFrom" label={tc('common.validFrom')} rules={[{ required: true, message: tc('residency.validFromRequired') }]}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="validTo" label={tc('common.validTo')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
        </Space>
        <Space wrap>
          <Form.Item name="arrivalDate" label={tc('residency.arrivalDate')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="departureDate" label={tc('residency.departureDate')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
        </Space>
        <Space wrap>
          <Form.Item name="taxYear" label={tc('residency.taxYear')}>
            <InputNumber min={2000} max={2100} precision={0} />
          </Form.Item>
          <Form.Item name="daysInVn" label={tc('residency.daysInVn')}>
            <InputNumber min={0} max={366} precision={0} />
          </Form.Item>
        </Space>
      </Form>
    </FormDrawer>
  );
}

function PresenceDrawer({ personId, period, onClose }: { personId: UUID; period: PresenceView | null | undefined; onClose: () => void }) {
  const { t: tc } = useTranslation('people-compliance');
  const fb = useFeedback();
  const [form] = Form.useForm<{ entryDate: Dayjs; exitDate?: Dayjs | null }>();
  const save = useSavePresence(personId);
  const open = period !== undefined;

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    save.reset();
    if (period) form.setFieldsValue({ entryDate: fromIsoDate(period.entryDate) as Dayjs, exitDate: fromIsoDate(period.exitDate) });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, period, form]);

  const onSubmit = async () => {
    let v: { entryDate: Dayjs; exitDate?: Dayjs | null };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await save.mutateAsync({ id: period?.id, version: period?.version, entryDate: toIsoDate(v.entryDate) as string, exitDate: toIsoDate(v.exitDate) });
      fb.saved();
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={period ? tc('presence.edit') : tc('presence.add')}
      onSubmit={() => void onSubmit()}
      submitting={save.isPending}
      error={save.error}
      width={420}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="entryDate" label={tc('presence.entryDate')} rules={[{ required: true, message: tc('presence.entryRequired') }]}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="exitDate" label={tc('presence.exitDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
