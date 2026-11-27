import { useState } from 'react';
import { Button, Checkbox, DatePicker, Form, Input, Modal, Space, Typography } from 'antd';
import { PlusOutlined, StopOutlined, UndoOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { ConfirmButton, DataTable, FormDrawer, PageHeader, ProblemAlert, useFeedback, usePaging } from '@/shared/components';
import { DATE_FORMAT, formatValidity, ISO_DATE } from '@/shared/format';
import { EmployeeSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import type { MappingView } from './api';
import { useCreateMapping, useEndMapping, useMappings } from './queries';
import { applyFieldErrors, DeviceSelect, NS, shortId, useDeviceCodes, useEmployeeLabels } from './components';

/** Enrolment mappings (BR-ATT-022, US-ATT-015): enrol id on a clock (or every clock) → employment, effective-dated. */
export function EnrolmentMappingsPage({ initialDeviceId }: { initialDeviceId?: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [deviceId, setDeviceId] = useState<UUID | undefined>(initialDeviceId);
  const [tenantWideOnly, setTenantWideOnly] = useState(false);
  const [enrolId, setEnrolId] = useState('');
  const [employmentId, setEmploymentId] = useState<UUID | undefined>();
  const [on, setOn] = useState<string | undefined>();
  const [creating, setCreating] = useState(false);
  const [ending, setEnding] = useState<MappingView | null>(null);
  const paging = usePaging(50);
  const canUpdate = usePermission(P.DEVICE_UPDATE);
  const q = useMappings({
    deviceId: tenantWideOnly ? undefined : deviceId,
    tenantWideOnly: tenantWideOnly || undefined,
    enrolId: enrolId.trim() || undefined,
    employmentId,
    on,
    limit: paging.size,
    offset: paging.page * paging.size,
  });
  const devices = useDeviceCodes();
  const employees = useEmployeeLabels();
  const endMapping = useEndMapping();
  const fb = useFeedback();

  const reopen = async (m: MappingView) => {
    try {
      await endMapping.mutateAsync({ id: m.id, version: m.version, validTo: null });
      fb.saved();
    } catch (e) {
      fb.failed(e);
    }
  };

  const filter = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    paging.reset();
  };

  return (
    <div>
      <PageHeader title={t('mappings.title')} subTitle={t('mappings.subtitle')} />
      <Space wrap style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Space wrap>
          <DeviceSelect
            aria-label={t('field.device')}
            placeholder={t('quarantine.allDevices')}
            style={{ width: 240 }}
            disabled={tenantWideOnly}
            value={deviceId}
            onChange={filter(setDeviceId)}
          />
          <Checkbox checked={tenantWideOnly} onChange={(e) => filter(setTenantWideOnly)(e.target.checked)}>
            {t('mappings.tenantWideOnly')}
          </Checkbox>
          <Input.Search
            aria-label={t('field.enrolId')}
            placeholder={t('field.enrolId')}
            allowClear
            style={{ width: 180 }}
            onSearch={filter(setEnrolId)}
          />
          <EmployeeSelect aria-label={t('field.employee')} style={{ width: 280 }} value={employmentId} onChange={filter(setEmploymentId)} />
          <DatePicker
            aria-label={t('mappings.on')}
            placeholder={t('mappings.on')}
            format={DATE_FORMAT}
            value={on ? dayjs(on) : null}
            onChange={(d) => filter(setOn)(d ? d.format(ISO_DATE) : undefined)}
          />
        </Space>
        <PermissionGate anyOf={P.DEVICE_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            {t('mappings.create')}
          </Button>
        </PermissionGate>
      </Space>

      <DataTable<MappingView>
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        locale={{ emptyText: t('mappings.empty') }}
        paging={{ ...paging, hasMore: (q.data?.length ?? 0) >= paging.size }}
        columns={[
          { title: t('field.enrolId'), dataIndex: 'enrolId', render: (c: string) => <code>{c}</code> },
          {
            title: t('field.device'),
            dataIndex: 'deviceId',
            render: (id: UUID | null) => (id ? (devices.get(id) ?? shortId(id)) : <Typography.Text type="secondary">{t('mappings.everyDevice')}</Typography.Text>),
          },
          { title: t('field.employee'), dataIndex: 'employmentId', render: (id: UUID) => employees.get(id) ?? shortId(id) },
          {
            title: t('field.validity'),
            key: 'validity',
            render: (_, m) => formatValidity(m.validFrom, m.validTo, tc('common.openEnded')),
          },
          { title: t('field.source'), dataIndex: 'source', render: (s: string) => t(`source.${s}`, { defaultValue: s }) },
          ...(canUpdate
            ? [
                {
                  title: tc('field.actions'),
                  key: 'actions',
                  render: (_: unknown, m: MappingView) => (
                    <Space>
                      <Button size="small" icon={<StopOutlined />} aria-label={t('mappings.endAria', { enrolId: m.enrolId })} onClick={() => setEnding(m)}>
                        {t('mappings.end')}
                      </Button>
                      {m.validTo ? (
                        <ConfirmButton
                          size="small"
                          icon={<UndoOutlined />}
                          aria-label={t('mappings.reopenAria', { enrolId: m.enrolId })}
                          onConfirm={() => reopen(m)}
                        >
                          {t('mappings.reopen')}
                        </ConfirmButton>
                      ) : null}
                    </Space>
                  ),
                },
              ]
            : []),
        ]}
      />

      {creating ? <MappingForm defaultDeviceId={deviceId} onClose={() => setCreating(false)} /> : null}
      {ending ? <EndMappingModal mapping={ending} onClose={() => setEnding(null)} /> : null}
    </div>
  );
}

interface FormValues {
  deviceId?: UUID | null;
  enrolId: string;
  employmentId: UUID;
  validFrom: Dayjs;
  validTo?: Dayjs | null;
}

function MappingForm({ defaultDeviceId, onClose }: { defaultDeviceId?: UUID; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateMapping();

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await create.mutateAsync({
        deviceId: v.deviceId ?? null,
        enrolId: v.enrolId.trim(),
        employmentId: v.employmentId,
        validFrom: v.validFrom.format(ISO_DATE),
        validTo: v.validTo ? v.validTo.format(ISO_DATE) : null,
      });
      fb.created();
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open onClose={onClose} title={t('mappings.create')} onSubmit={() => void submit()} submitting={create.isPending} error={create.error}>
      <Form<FormValues> form={form} layout="vertical" initialValues={{ deviceId: defaultDeviceId, validFrom: dayjs() }}>
        <Form.Item name="deviceId" label={t('field.device')} extra={t('mappings.everyDevice')}>
          <DeviceSelect placeholder={t('mappings.everyDevice')} />
        </Form.Item>
        <Form.Item name="enrolId" label={t('field.enrolId')} rules={[{ required: true, whitespace: true, message: tc('common.required') }]}>
          <Input maxLength={64} />
        </Form.Item>
        <Form.Item name="employmentId" label={t('field.employee')} rules={[{ required: true, message: tc('common.required') }]}>
          <EmployeeSelect />
        </Form.Item>
        <Form.Item name="validFrom" label={t('field.validFrom')} rules={[{ required: true, message: tc('common.required') }]}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="validTo" label={t('field.endDate')} extra={t('mappings.endHelp')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

function EndMappingModal({ mapping, onClose }: { mapping: MappingView; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [date, setDate] = useState<Dayjs | null>(mapping.validTo ? dayjs(mapping.validTo) : dayjs());
  const end = useEndMapping();
  const submit = async () => {
    if (!date) return;
    try {
      await end.mutateAsync({ id: mapping.id, version: mapping.version, validTo: date.format(ISO_DATE) });
      fb.saved();
      onClose();
    } catch {
      // shown in the modal
    }
  };
  return (
    <Modal
      open
      title={t('mappings.endTitle', { enrolId: mapping.enrolId })}
      onCancel={onClose}
      onOk={() => void submit()}
      okText={tc('action.save')}
      cancelText={tc('action.cancel')}
      okButtonProps={{ disabled: !date, loading: end.isPending }}
      destroyOnHidden
    >
      {end.error ? <ProblemAlert error={end.error} style={{ marginBottom: 12 }} /> : null}
      <Form layout="vertical">
        <Form.Item label={t('field.endDate')} extra={t('mappings.endHelp')}>
          <DatePicker aria-label={t('field.endDate')} format={DATE_FORMAT} value={date} onChange={setDate} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
