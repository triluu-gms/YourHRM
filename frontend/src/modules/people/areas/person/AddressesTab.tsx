import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, DatePicker, Form, Input, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DataTable, FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatValidity } from '@/shared/format';
import {
  AdminUnitSelect,
  applyFieldErrors,
  blankToNull,
  CountryName,
  CountrySelect,
  toIsoDate,
  useProvinces,
  type ProfileTabProps,
} from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { ADDRESS_TYPES, type AddressData, type AddressType, type AddressView } from './api';
import { useAddAddress, useAddresses, useCorrectAddress, useEndAddress } from './queries';
import './i18n';

type Mode = { kind: 'add' } | { kind: 'correct'; address: AddressView } | { kind: 'end'; address: AddressView } | null;

/** Addresses of the person (US-PPL-005): 2-level model (province + ward); legacy 3-level rows are read-only. */
export function AddressesTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation('people-person');
  const { t: tc } = useTranslation();
  const [history, setHistory] = useState(false);
  const [mode, setMode] = useState<Mode>(null);
  const query = useAddresses(personId, history ? undefined : profile.asOf);
  const readOnly = profile.person.anonymised;

  return (
    <div>
      <Space wrap style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
        <Checkbox checked={history} onChange={(e) => setHistory(e.target.checked)}>
          {t('addresses.showHistory')}
        </Checkbox>
        {!readOnly ? (
          <PermissionGate anyOf={P.PERSON_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setMode({ kind: 'add' })}>
              {t('addresses.add')}
            </Button>
          </PermissionGate>
        ) : null}
      </Space>
      <DataTable<AddressView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: t('addresses.type'), dataIndex: 'addressType', render: (v: AddressType) => t(`addresses.types.${v}`, { defaultValue: v }) },
          { title: t('addresses.addressLine'), key: 'address', render: (_, a) => <AddressText address={a} /> },
          { title: t('addresses.country'), dataIndex: 'countryCode', render: (c: string | null) => <CountryName code={c} /> },
          { title: t('addresses.validFrom'), key: 'validity', render: (_, a) => formatValidity(a.validFrom, a.validTo, tc('common.openEnded')) },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, a) =>
              readOnly || a.readOnly ? null : (
                <PermissionGate anyOf={P.PERSON_UPDATE}>
                  <Space size={0}>
                    <Button type="link" size="small" onClick={() => setMode({ kind: 'correct', address: a })}>
                      {tc('action.edit')}
                    </Button>
                    {!a.validTo ? (
                      <Button type="link" size="small" onClick={() => setMode({ kind: 'end', address: a })}>
                        {t('addresses.end')}
                      </Button>
                    ) : null}
                  </Space>
                </PermissionGate>
              ),
          },
        ]}
      />
      <AddressDrawer personId={personId} mode={mode?.kind === 'end' ? null : mode} onClose={() => setMode(null)} />
      <EndAddressDrawer personId={personId} address={mode?.kind === 'end' ? mode.address : null} onClose={() => setMode(null)} />
    </div>
  );
}

/** Address line + province / ward names; legacy rows show their stored text and a tag. */
function AddressText({ address: a }: { address: AddressView }) {
  const { t, i18n } = useTranslation('people-person');
  const provinces = useProvinces();
  const province = provinces.data?.find((p) => p.code === a.provinceCode);
  const provinceName = province ? (i18n.language.startsWith('en') ? province.nameEn : province.nameVi) || province.code : a.provinceCode;
  const text = [a.addressLine, a.communeCode, provinceName].filter(Boolean).join(', ');
  return (
    <Space direction="vertical" size={0}>
      <span>{text || a.legacyAddressText || '—'}</span>
      {a.readOnly ? (
        <Tooltip title={a.legacyAddressText}>
          <Tag>{t('addresses.legacy')}</Tag>
        </Tooltip>
      ) : null}
      {a.mappingStatus ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('addresses.mapping')}: {a.mappingStatus}
        </Typography.Text>
      ) : null}
    </Space>
  );
}

interface AddressValues {
  addressType: AddressType;
  validFrom?: Dayjs | null;
  countryCode?: string;
  provinceCode?: string;
  communeCode?: string;
  addressLine?: string;
}

function AddressDrawer({
  personId,
  mode,
  onClose,
}: {
  personId: string;
  mode: { kind: 'add' } | { kind: 'correct'; address: AddressView } | null;
  onClose: () => void;
}) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const [form] = Form.useForm<AddressValues>();
  const add = useAddAddress(personId);
  const correct = useCorrectAddress(personId);
  const mutation = mode?.kind === 'correct' ? correct : add;
  const country = Form.useWatch('countryCode', form);
  const province = Form.useWatch('provinceCode', form);
  const vn = !country || country === 'VN';

  useEffect(() => {
    if (!mode) return;
    form.resetFields();
    add.reset();
    correct.reset();
    if (mode.kind === 'correct') {
      const a = mode.address;
      form.setFieldsValue({
        addressType: a.addressType,
        countryCode: a.countryCode ?? 'VN',
        provinceCode: a.provinceCode ?? undefined,
        communeCode: a.communeCode ?? undefined,
        addressLine: a.addressLine ?? undefined,
      });
    } else {
      form.setFieldsValue({ countryCode: 'VN' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [mode, form]);

  const submit = async () => {
    let v: AddressValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const address = blankToNull({
      countryCode: v.countryCode,
      provinceCode: vn ? v.provinceCode : null,
      communeCode: vn ? v.communeCode : null,
      addressLine: v.addressLine,
    }) as AddressData;
    try {
      if (mode?.kind === 'correct') {
        await correct.mutateAsync({ addressId: mode.address.id, version: mode.address.version, address });
      } else {
        await add.mutateAsync({ addressType: v.addressType, validFrom: toIsoDate(v.validFrom) as string, address });
      }
      fb.saved(t('addresses.saved'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={mode !== null}
      onClose={onClose}
      title={mode?.kind === 'correct' ? t('addresses.correct') : t('addresses.add')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={520}
    >
      {mode?.kind === 'add' ? <Alert type="info" showIcon message={t('addresses.addHint')} style={{ marginBottom: 12 }} /> : null}
      <Form<AddressValues> form={form} layout="vertical">
        <Form.Item name="addressType" label={t('addresses.type')} rules={[{ required: true, message: t('addresses.typeRequired') }]}>
          <Select disabled={mode?.kind === 'correct'} options={ADDRESS_TYPES.map((v) => ({ value: v, label: t(`addresses.types.${v}`) }))} />
        </Form.Item>
        {mode?.kind === 'add' ? (
          <Form.Item name="validFrom" label={t('addresses.validFrom')} rules={[{ required: true, message: t('addresses.validFromRequired') }]}>
            <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
        ) : null}
        <Form.Item name="countryCode" label={t('addresses.country')}>
          <CountrySelect
            onChange={() => form.setFieldsValue({ provinceCode: undefined, communeCode: undefined })}
          />
        </Form.Item>
        {vn ? (
          <>
            <Form.Item name="provinceCode" label={t('addresses.province')}>
              <AdminUnitSelect onChange={() => form.setFieldValue('communeCode', undefined)} />
            </Form.Item>
            <Form.Item name="communeCode" label={t('addresses.commune')}>
              <AdminUnitSelect parent={province ?? null} />
            </Form.Item>
          </>
        ) : null}
        <Form.Item name="addressLine" label={t('addresses.addressLine')} rules={[{ max: 500 }]}>
          <Input />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

function EndAddressDrawer({ personId, address, onClose }: { personId: string; address: AddressView | null; onClose: () => void }) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const [form] = Form.useForm<{ validTo: Dayjs }>();
  const end = useEndAddress(personId);

  useEffect(() => {
    if (address) {
      form.resetFields();
      end.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [address, form]);

  const submit = async () => {
    if (!address) return;
    let v: { validTo: Dayjs };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await end.mutateAsync({ addressId: address.id, version: address.version, validTo: toIsoDate(v.validTo) as string });
      fb.done(t('addresses.ended'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={address !== null} onClose={onClose} title={t('addresses.endTitle')} onSubmit={() => void submit()} submitting={end.isPending} error={end.error} width={420}>
      <Form form={form} layout="vertical">
        <Form.Item name="validTo" label={t('addresses.validTo')} rules={[{ required: true, message: t('addresses.validToRequired') }]}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
