import { Checkbox, Col, DatePicker, Divider, Form, Input, InputNumber, Row, Select } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE } from '@/shared/format';
import { LOCATION_TYPES, type Location, type LocationAttributes } from './api';
import { useChangeLocation, useCreateLocation } from './queries';
import { applyFieldErrors, blankToNull, CODE_PATTERN, TAX_CODE_PATTERN } from './formErrors';
import { AdminUnitSelect, LegalEntitySelect } from './widgets';
import { EMPLOYER_NS } from './i18n';

interface FormValues {
  code?: string;
  legalEntityId?: UUID;
  date?: Dayjs | null;
  attributes: LocationAttributes;
}

export type LocationFormMode = { kind: 'create'; legalEntityId?: UUID } | { kind: 'change'; location: Location };

/** Create a location under one legal entity (BR-CORE-005) or record an effective-dated change of it. */
export function LocationForm({
  mode,
  onClose,
  onSaved,
}: {
  mode: LocationFormMode;
  onClose: () => void;
  onSaved?: (saved: Location) => void;
}) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateLocation();
  const change = useChangeLocation(mode.kind === 'change' ? mode.location.id : '');
  const mutation = mode.kind === 'change' ? change : create;
  const date = Form.useWatch('date', form);
  const asOf = date ? date.format(ISO_DATE) : undefined;
  const current = mode.kind === 'change' ? mode.location.current : null;

  const initialValues: FormValues =
    mode.kind === 'change' && current
      ? { date: dayjs(), attributes: { ...current } }
      : { legalEntityId: mode.kind === 'create' ? mode.legalEntityId : undefined, attributes: { name: '', locationType: 'BRANCH', remoteArea: false } };

  const submit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const a = blankToNull(values.attributes);
    const attributes: LocationAttributes = {
      name: a.name,
      locationType: a.locationType,
      adminUnitCode: a.adminUnitCode,
      addressLine: a.addressLine,
      remoteArea: !!a.remoteArea,
      timeZone: a.timeZone,
      latitude: a.latitude,
      longitude: a.longitude,
      geofenceRadiusM: a.geofenceRadiusM,
      dependentTaxCode: a.dependentTaxCode,
    };
    const iso = values.date ? values.date.format(ISO_DATE) : null;
    try {
      const saved =
        mode.kind === 'change'
          ? await change.mutateAsync({ effectiveFrom: iso as string, attributes, version: mode.location.version })
          : await create.mutateAsync({
              code: (values.code ?? '').trim(),
              legalEntityId: values.legalEntityId as UUID,
              validFrom: iso,
              attributes,
            });
      if (mode.kind === 'change') fb.saved();
      else fb.created();
      onSaved?.(saved);
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  const a = (name: keyof LocationAttributes) => ['attributes', name];

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={mode.kind === 'change' ? te('location.newVersionTitle', { code: mode.location.code }) : te('location.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={680}
    >
      <Form<FormValues> form={form} layout="vertical" initialValues={initialValues}>
        <Row gutter={16}>
          {mode.kind === 'create' ? (
            <>
              <Col xs={24} md={12}>
                <Form.Item name="legalEntityId" label={t('field.legalEntity')} rules={[{ required: true, message: te('common.legalEntityRequired') }]}>
                  <LegalEntitySelect />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item
                  name="code"
                  label={t('field.code')}
                  extra={te('common.codeImmutable')}
                  rules={[
                    { required: true, whitespace: true, message: te('common.codeRequired') },
                    { pattern: CODE_PATTERN, message: te('common.codeInvalid') },
                  ]}
                >
                  <Input autoComplete="off" style={{ fontFamily: 'monospace' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item name="date" label={t('field.validFrom')} extra={te('common.validFromHint')}>
                  <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </>
          ) : (
            <Col xs={24} md={12}>
              <Form.Item
                name="date"
                label={te('common.effectiveFrom')}
                extra={te('common.effectiveFromHint')}
                rules={[{ required: true, message: te('common.effectiveFromRequired') }]}
              >
                <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          )}
        </Row>

        <Divider orientation="left">{te('location.section.general')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name={a('name')} label={te('location.field.name')} rules={[{ required: true, whitespace: true, message: te('common.nameRequired') }, { max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('locationType')} label={te('location.field.locationType')} rules={[{ required: true, message: te('location.typeRequired') }]}>
              <Select options={LOCATION_TYPES.map((lt) => ({ value: lt, label: te(`location.type.${lt}`) }))} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name={a('dependentTaxCode')}
              label={te('location.field.dependentTaxCode')}
              extra={te('location.dependentTaxCodeHint')}
              rules={[{ pattern: TAX_CODE_PATTERN, message: te('legalEntity.taxCodeInvalid') }]}
            >
              <Input style={{ fontFamily: 'monospace' }} placeholder="0123456789-001" />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('timeZone')} label={te('location.field.timeZone')}>
              <Input placeholder="Asia/Ho_Chi_Minh" />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{te('location.section.address')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name={a('adminUnitCode')} label={te('location.field.adminUnitCode')}>
              <AdminUnitSelect asOf={asOf} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('addressLine')} label={te('location.field.addressLine')} rules={[{ max: 500 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24}>
            <Form.Item name={a('remoteArea')} valuePropName="checked">
              <Checkbox>{te('location.field.remoteArea')}</Checkbox>
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{te('location.section.attendance')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={8}>
            <Form.Item name={a('latitude')} label={te('location.field.latitude')}>
              <InputNumber min={-90} max={90} step={0.000001} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name={a('longitude')} label={te('location.field.longitude')}>
              <InputNumber min={-180} max={180} step={0.000001} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name={a('geofenceRadiusM')} label={te('location.field.geofenceRadiusM')}>
              <InputNumber min={1} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </FormDrawer>
  );
}
