import { useState } from 'react';
import { AutoComplete, Button, Drawer, Form, Input, Select, Space, Typography } from 'antd';
import { EditOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, DescriptionList, FormDrawer, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import { LegalEntitySelect, LocationSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import { DEVICE_CODE_PATTERN, DEVICE_STATUSES, FILE_LAYOUTS, type DeviceRequest, type DeviceView } from './api';
import { useDevice, useDevices, useSaveDevice } from './queries';
import { applyFieldErrors, DeviceStatusTag, LegalEntityName, LocationName, NS, textOrNull } from './components';

const TIME_ZONES = ['Asia/Ho_Chi_Minh', 'Asia/Bangkok', 'Asia/Singapore', 'Asia/Tokyo', 'UTC'];
const DEFAULT_TZ = 'Asia/Ho_Chi_Minh';

/** Device registry (BR-ATT-022, US-ATT-014): list, create, edit and a detail drawer. */
export function DevicesPage() {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [status, setStatus] = useState<string | undefined>();
  const [form, setForm] = useState<{ device: DeviceView | null } | null>(null);
  const [detailId, setDetailId] = useState<UUID | null>(null);
  const canUpdate = usePermission(P.DEVICE_UPDATE);
  const q = useDevices(status);

  return (
    <div>
      <PageHeader title={t('devices.title')} subTitle={t('devices.subtitle')} />
      <Space wrap style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Select
          aria-label={t('field.status')}
          allowClear
          placeholder={t('devices.allStatuses')}
          style={{ width: 200 }}
          value={status}
          onChange={setStatus}
          options={DEVICE_STATUSES.map((s) => ({ value: s, label: t(`deviceStatus.${s}`) }))}
        />
        <PermissionGate anyOf={P.DEVICE_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ device: null })}>
            {t('devices.create')}
          </Button>
        </PermissionGate>
      </Space>
      <Typography.Paragraph type="secondary">{t('devices.importHint')}</Typography.Paragraph>

      <DataTable<DeviceView>
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        locale={{ emptyText: t('devices.empty') }}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.name'), dataIndex: 'name' },
          { title: t('field.terminalCode'), dataIndex: 'terminalCode' },
          {
            title: t('field.vendor'),
            key: 'vendor',
            render: (_, d) => [d.vendor, d.model].filter(Boolean).join(' / '),
          },
          { title: t('field.legalEntity'), dataIndex: 'legalEntityId', render: (id: UUID | null) => <LegalEntityName id={id} /> },
          { title: t('field.location'), dataIndex: 'locationId', render: (id: UUID | null) => <LocationName id={id} /> },
          {
            title: t('field.fileLayout'),
            dataIndex: 'fileLayoutCode',
            render: (c: string | null) => (c ? t(`layout.${c}`, { defaultValue: c }) : null),
          },
          { title: t('field.lastImportAt'), dataIndex: 'lastImportAt', render: (v: string | null) => formatDateTime(v) },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <DeviceStatusTag status={s} /> },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, d) => (
              <Space>
                <Button size="small" icon={<EyeOutlined />} aria-label={t('devices.viewDetail', { code: d.code })} onClick={() => setDetailId(d.id)} />
                {canUpdate ? (
                  <Button size="small" icon={<EditOutlined />} aria-label={t('devices.editAria', { code: d.code })} onClick={() => setForm({ device: d })} />
                ) : null}
              </Space>
            ),
          },
        ]}
      />

      {form ? <DeviceForm device={form.device} onClose={() => setForm(null)} /> : null}
      {detailId ? <DeviceDetail id={detailId} onClose={() => setDetailId(null)} /> : null}
    </div>
  );
}

function DeviceDetail({ id, onClose }: { id: UUID; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const q = useDevice(id);
  const d = q.data;
  return (
    <Drawer open onClose={onClose} width={640} title={d ? t('devices.detail', { code: d.code }) : ''} destroyOnHidden>
      {q.error ? <ProblemAlert error={q.error} /> : null}
      {d ? (
        <>
          <DescriptionList
            column={1}
            items={[
              { label: t('field.code'), value: <code>{d.code}</code> },
              { label: t('field.name'), value: d.name },
              { label: t('field.terminalCode'), value: d.terminalCode },
              { label: t('field.vendor'), value: d.vendor },
              { label: t('field.model'), value: d.model },
              { label: t('field.serialNo'), value: d.serialNo },
              { label: t('field.legalEntity'), value: <LegalEntityName id={d.legalEntityId} /> },
              { label: t('field.location'), value: d.locationId ? <LocationName id={d.locationId} /> : null },
              { label: t('field.timeZone'), value: d.timeZone },
              { label: t('field.connector'), value: t(`connector.${d.connectorType}`, { defaultValue: d.connectorType }) },
              { label: t('field.fileLayout'), value: d.fileLayoutCode ? t(`layout.${d.fileLayoutCode}`, { defaultValue: d.fileLayoutCode }) : null },
              { label: t('field.lastImportAt'), value: formatDateTime(d.lastImportAt) },
              { label: t('field.status'), value: <DeviceStatusTag status={d.status} /> },
            ]}
          />
          <div style={{ marginTop: 16 }}>
            <Link to="/attendance/enrolment-mappings" search={{ deviceId: d.id }}>
              {t('devices.viewMappings')}
            </Link>
          </div>
        </>
      ) : null}
    </Drawer>
  );
}

interface FormValues {
  code: string;
  name: string;
  terminalCode: string;
  vendor?: string;
  model?: string;
  serialNo?: string;
  legalEntityId?: UUID | null;
  locationId?: UUID | null;
  timeZone?: string;
  fileLayoutCode?: string | null;
  status: string;
}

function DeviceForm({ device, onClose }: { device: DeviceView | null; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const save = useSaveDevice();
  const legalEntityId = Form.useWatch('legalEntityId', form);

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const body: DeviceRequest = {
      code: v.code.trim(),
      name: v.name.trim(),
      terminalCode: v.terminalCode.trim(),
      vendor: textOrNull(v.vendor),
      model: textOrNull(v.model),
      serialNo: textOrNull(v.serialNo),
      legalEntityId: v.legalEntityId ?? null,
      locationId: v.locationId ?? null,
      timeZone: textOrNull(v.timeZone),
      fileLayoutCode: v.fileLayoutCode ?? null,
      status: v.status,
      ...(device ? { version: device.version } : {}),
    };
    try {
      await save.mutateAsync({ id: device?.id, body });
      if (device) fb.saved();
      else fb.created();
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={device ? t('devices.edit', { code: device.code }) : t('devices.create')}
      onSubmit={() => void submit()}
      submitting={save.isPending}
      error={save.error}
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={
          device
            ? {
                ...device,
                vendor: device.vendor ?? undefined,
                model: device.model ?? undefined,
                serialNo: device.serialNo ?? undefined,
                timeZone: device.timeZone ?? undefined,
              }
            : { status: 'ACTIVE', timeZone: DEFAULT_TZ, fileLayoutCode: 'STANDARD' }
        }
      >
        <Form.Item
          name="code"
          label={t('field.code')}
          normalize={(v: string) => v?.toUpperCase()}
          rules={[
            { required: true, message: tc('common.required') },
            { pattern: DEVICE_CODE_PATTERN, message: t('errors.invalid-device-code') },
          ]}
        >
          <Input maxLength={40} />
        </Form.Item>
        <Form.Item name="name" label={t('field.name')} rules={[{ required: true, whitespace: true, message: tc('common.required') }]}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item
          name="terminalCode"
          label={t('field.terminalCode')}
          rules={[{ required: true, whitespace: true, message: tc('common.required') }]}
        >
          <Input maxLength={100} />
        </Form.Item>
        <Space.Compact block>
          <Form.Item name="vendor" label={t('field.vendor')} style={{ flex: 1 }}>
            <Input />
          </Form.Item>
          <Form.Item name="model" label={t('field.model')} style={{ flex: 1 }}>
            <Input />
          </Form.Item>
        </Space.Compact>
        <Form.Item name="serialNo" label={t('field.serialNo')}>
          <Input />
        </Form.Item>
        <Form.Item name="legalEntityId" label={t('field.legalEntity')}>
          <LegalEntitySelect onChange={() => form.setFieldValue('locationId', null)} />
        </Form.Item>
        <Form.Item name="locationId" label={t('field.location')}>
          <LocationSelect legalEntityId={legalEntityId} />
        </Form.Item>
        <Form.Item name="timeZone" label={t('field.timeZone')}>
          <AutoComplete options={TIME_ZONES.map((z) => ({ value: z }))} />
        </Form.Item>
        <Form.Item name="fileLayoutCode" label={t('field.fileLayout')}>
          <Select allowClear options={FILE_LAYOUTS.map((l) => ({ value: l, label: t(`layout.${l}`) }))} />
        </Form.Item>
        <Form.Item name="status" label={t('field.status')} rules={[{ required: true, message: tc('common.required') }]}>
          <Select options={DEVICE_STATUSES.map((s) => ({ value: s, label: t(`deviceStatus.${s}`) }))} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
