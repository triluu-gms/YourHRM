import { Col, DatePicker, Divider, Form, Input, InputNumber, Row, Select } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE } from '@/shared/format';
import {
  PIT_DECLARATION_FREQUENCIES,
  type LegalEntity,
  type LegalEntityAttributes,
  type LegalEntityStatus,
} from './api';
import { useChangeLegalEntity, useCreateLegalEntity } from './queries';
import { applyFieldErrors, blankToNull, CODE_PATTERN, TAX_CODE_PATTERN, UUID_PATTERN } from './formErrors';
import { AdminUnitSelect } from './widgets';
import { EMPLOYER_NS } from './i18n';

type AttributeValues = Omit<LegalEntityAttributes, 'businessRegDate' | 'oaOdRate'> & {
  businessRegDate?: Dayjs | null;
  /** Shown in percent; the API takes a fraction. */
  oaOdRatePercent?: number | null;
};

interface FormValues {
  code?: string;
  status?: LegalEntityStatus;
  date?: Dayjs | null;
  attributes: AttributeValues;
}

/** Backend field paths whose form field has another name. */
const FIELD_RENAMES = { 'attributes.oaOdRate': 'attributes.oaOdRatePercent' };

export type LegalEntityFormMode = { kind: 'create' } | { kind: 'change'; legalEntity: LegalEntity };

const toPercent = (fraction: number | null | undefined) =>
  fraction === null || fraction === undefined ? undefined : Number((fraction * 100).toFixed(4));
const toFraction = (percent: number | null | undefined) =>
  percent === null || percent === undefined ? null : Number((percent / 100).toFixed(6));

/**
 * Create a legal entity (code + first version) or record an effective-dated change of its statutory attributes
 * (BR-CORE-002..004). Mounted only while open; the change form starts from the version shown on the page.
 */
export function LegalEntityForm({
  mode,
  onClose,
  onSaved,
}: {
  mode: LegalEntityFormMode;
  onClose: () => void;
  onSaved?: (saved: LegalEntity) => void;
}) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateLegalEntity();
  const change = useChangeLegalEntity(mode.kind === 'change' ? mode.legalEntity.id : '');
  const mutation = mode.kind === 'change' ? change : create;
  const date = Form.useWatch('date', form);
  const current = mode.kind === 'change' ? mode.legalEntity.current : null;

  const initialValues: FormValues =
    mode.kind === 'change' && current
      ? {
          date: dayjs(),
          attributes: {
            ...current,
            businessRegDate: current.businessRegDate ? dayjs(current.businessRegDate) : null,
            oaOdRatePercent: toPercent(current.oaOdRate),
          },
        }
      : { status: 'DRAFT', attributes: { legalNameVi: '', taxCode: '', fiscalYearStartMonth: 1, pitDeclarationFrequency: 'MONTHLY' } };

  const submit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const { businessRegDate, oaOdRatePercent, ...rest } = values.attributes;
    const attrs = blankToNull(rest);
    const attributes: LegalEntityAttributes = {
      legalNameVi: attrs.legalNameVi,
      legalNameEn: attrs.legalNameEn,
      shortName: attrs.shortName,
      taxCode: attrs.taxCode,
      siUnitCode: attrs.siUnitCode,
      businessRegNo: attrs.businessRegNo,
      businessRegDate: businessRegDate ? businessRegDate.format(ISO_DATE) : null,
      businessRegIssuer: attrs.businessRegIssuer,
      phone: attrs.phone,
      email: attrs.email,
      adminUnitCode: attrs.adminUnitCode,
      addressLine: attrs.addressLine,
      legalRepName: attrs.legalRepName,
      legalRepTitle: attrs.legalRepTitle,
      fiscalYearStartMonth: attrs.fiscalYearStartMonth,
      taxAuthorityCode: attrs.taxAuthorityCode,
      pitDeclarationFrequency: attrs.pitDeclarationFrequency,
      oaOdRate: toFraction(oaOdRatePercent),
      oaOdDecisionRef: attrs.oaOdDecisionRef,
      oaOdDecisionDocId: attrs.oaOdDecisionDocId,
    };
    const iso = values.date ? values.date.format(ISO_DATE) : null;
    try {
      const saved =
        mode.kind === 'change'
          ? await change.mutateAsync({ effectiveFrom: iso as string, attributes, version: mode.legalEntity.version })
          : await create.mutateAsync({ code: (values.code ?? '').trim(), status: values.status, validFrom: iso, attributes });
      if (mode.kind === 'change') fb.saved();
      else fb.created();
      onSaved?.(saved);
      onClose();
    } catch (error) {
      applyFieldErrors(form, error, FIELD_RENAMES);
    }
  };

  const asOf = date ? date.format(ISO_DATE) : undefined;
  const a = (name: keyof AttributeValues) => ['attributes', name];

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={mode.kind === 'change' ? te('legalEntity.newVersionTitle', { code: mode.legalEntity.code }) : te('legalEntity.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={760}
    >
      <Form<FormValues> form={form} layout="vertical" initialValues={initialValues}>
        <Row gutter={16}>
          {mode.kind === 'create' ? (
            <>
              <Col xs={24} md={8}>
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
              <Col xs={24} md={8}>
                <Form.Item name="status" label={te('legalEntity.field.initialStatus')}>
                  <Select
                    options={(['DRAFT', 'ACTIVE'] as const).map((s) => ({ value: s, label: te(`legalEntity.status.${s}`) }))}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
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

        <Divider orientation="left">{te('legalEntity.section.identity')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name={a('legalNameVi')} label={te('legalEntity.field.legalNameVi')} rules={[{ required: true, whitespace: true, message: te('common.nameRequired') }, { max: 300 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('legalNameEn')} label={te('legalEntity.field.legalNameEn')} rules={[{ max: 300 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('shortName')} label={te('legalEntity.field.shortName')} rules={[{ max: 100 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name={a('fiscalYearStartMonth')}
              label={te('legalEntity.field.fiscalYearStartMonth')}
            >
              <InputNumber min={1} max={12} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{te('legalEntity.section.tax')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name={a('taxCode')}
              label={te('legalEntity.field.taxCode')}
              rules={[
                { required: true, whitespace: true, message: te('legalEntity.taxCodeRequired') },
                { pattern: TAX_CODE_PATTERN, message: te('legalEntity.taxCodeInvalid') },
              ]}
            >
              <Input style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('siUnitCode')} label={te('legalEntity.field.siUnitCode')} rules={[{ max: 32 }]}>
              <Input style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('taxAuthorityCode')} label={te('legalEntity.field.taxAuthorityCode')} rules={[{ max: 32 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name={a('pitDeclarationFrequency')}
              label={te('legalEntity.field.pitDeclarationFrequency')}
              extra={te('legalEntity.pitFrequencyHint')}
            >
              <Select options={PIT_DECLARATION_FREQUENCIES.map((f) => ({ value: f, label: te(`legalEntity.pitFrequency.${f}`) }))} />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{te('legalEntity.section.registration')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={8}>
            <Form.Item name={a('businessRegNo')} label={te('legalEntity.field.businessRegNo')} rules={[{ max: 64 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name={a('businessRegDate')} label={te('legalEntity.field.businessRegDate')}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name={a('businessRegIssuer')} label={te('legalEntity.field.businessRegIssuer')} rules={[{ max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{te('legalEntity.section.contact')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name={a('phone')} label={te('legalEntity.field.phone')} rules={[{ max: 20 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('email')} label={te('legalEntity.field.email')} rules={[{ type: 'email' }, { max: 254 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('adminUnitCode')} label={te('legalEntity.field.adminUnitCode')}>
              <AdminUnitSelect asOf={asOf} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('addressLine')} label={te('legalEntity.field.addressLine')} rules={[{ max: 500 }]}>
              <Input />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{te('legalEntity.section.representative')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name={a('legalRepName')} label={te('legalEntity.field.legalRepName')} rules={[{ max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name={a('legalRepTitle')} label={te('legalEntity.field.legalRepTitle')} rules={[{ max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{te('legalEntity.section.oaOd')}</Divider>
        <Row gutter={16}>
          <Col xs={24} md={8}>
            <Form.Item name={a('oaOdRatePercent')} label={te('legalEntity.field.oaOdRate')} extra={te('legalEntity.oaOdRateHint')}>
              <InputNumber min={0} max={4.9999} step={0.1} addonAfter="%" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name={a('oaOdDecisionRef')} label={te('legalEntity.field.oaOdDecisionRef')} rules={[{ max: 300 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name={a('oaOdDecisionDocId')}
              label={te('legalEntity.field.oaOdDecisionDocId')}
              rules={[{ pattern: UUID_PATTERN, message: te('common.uuidInvalid') }]}
            >
              <Input placeholder={te('common.uuidHint')} style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </FormDrawer>
  );
}
