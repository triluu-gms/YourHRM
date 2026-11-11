import { Alert, Checkbox, Col, DatePicker, Descriptions, Form, Input, Row, Select, Space, Tag, Typography, type FormInstance } from 'antd';
import type { Dayjs } from 'dayjs';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { DATE_FORMAT, formatDate, localizedName } from '@/shared/format';
import { fromIsoDate, toIsoDate } from '../../common';
import type {
  LegalGround,
  NoticeAssessment,
  Protection,
  ReasonCatalogue,
  SeparationAttributes,
  SeparationStatus,
  SeparationView,
} from './api';
import './i18n';

export const NS = 'people-separation';

const STATUS_COLOURS: Record<SeparationStatus, string> = {
  DRAFT: 'default',
  SUBMITTED: 'gold',
  APPROVED: 'blue',
  SCHEDULED: 'blue',
  COMPLETED: 'green',
  WITHDRAWN: 'default',
  REJECTED: 'red',
  CANCELLED: 'default',
};

export function SeparationStatusTag({ status }: { status: SeparationStatus | null | undefined }) {
  const { t } = useTranslation(NS);
  if (!status) return null;
  return <Tag color={STATUS_COLOURS[status] ?? 'default'}>{t(`status.${status}`, { defaultValue: status })}</Tag>;
}

/** Code → translated label from a sub-dictionary (`type.*`, `severanceType.*`…), falling back to the code. */
export function useLabel() {
  const { t } = useTranslation(NS);
  return (group: string, code: string | null | undefined) => (code ? t(`${group}.${code}`, { defaultValue: code }) : null);
}

/** Legal ground label from the reasons catalogue (article + name), falling back to the code. */
export function groundLabel(catalogue: ReasonCatalogue | undefined, code: string | null | undefined, language: string): string | null {
  if (!code) return null;
  const g = catalogue?.grounds.find((x) => x.code === code);
  if (!g) return code;
  const name = localizedName(g, language) || g.code;
  return g.article ? `${name} (${g.article})` : name;
}

export function reasonLabel(catalogue: ReasonCatalogue | undefined, code: string | null | undefined, language: string): string | null {
  if (!code) return null;
  const r = catalogue?.subReasons.find((x) => x.code === code);
  return r ? localizedName(r, language) || r.code : code;
}

/** `warnings` arrive as `code: detail`; the code part is translated when known, the detail kept as-is. */
export function Warnings({ warnings }: { warnings: string[] | null | undefined }) {
  const { t } = useTranslation(NS);
  if (!warnings || warnings.length === 0) return null;
  return (
    <Space direction="vertical" style={{ width: '100%', marginBottom: 12 }}>
      {warnings.map((w, i) => {
        const [code, ...rest] = w.split(':');
        const label = t(`warning.${code?.trim()}`, { defaultValue: '' });
        return (
          <Alert
            key={i}
            type="warning"
            showIcon
            message={label || w}
            description={label && rest.length > 0 ? rest.join(':').trim() : undefined}
          />
        );
      })}
    </Space>
  );
}

function daysText(t: TFunction<typeof NS>, days: number | null | undefined, basis?: string | null) {
  if (days === null || days === undefined) return null;
  return basis ? t('daysWithBasis', { count: days, basis: t(`noticeBasis.${basis}`, { defaultValue: basis }) }) : t('days', { count: days });
}

/**
 * Notice, protection and severance figures of a preview / create / update / submit result (or of the stored
 * separation when no fresh assessment is at hand). Read-only.
 */
export function AssessmentView({
  notice,
  protection,
  severanceType,
  severanceEligibility,
  statutoryCode,
  terminationDate,
  accessRevocationDate,
  warnings,
}: {
  notice?: NoticeAssessment | null;
  protection?: Protection | null;
  severanceType?: string | null;
  severanceEligibility?: string | null;
  statutoryCode?: string | null;
  terminationDate?: string | null;
  accessRevocationDate?: string | null;
  warnings?: string[] | null;
}) {
  const { t } = useTranslation(NS);
  const label = useLabel();
  const noNotice = !notice || notice.side === 'NONE' || notice.requiredDays === null || notice.requiredDays === undefined;
  return (
    <div>
      <Warnings warnings={warnings} />
      <Descriptions size="small" bordered column={1}>
        {notice?.side ? <Descriptions.Item label={t('field.initiatedBy')}>{label('noticeSide', notice.side)}</Descriptions.Item> : null}
        <Descriptions.Item label={t('field.requiredNotice')}>
          {noNotice ? t('assessment.noNotice') : daysText(t, notice?.requiredDays, notice?.basis)}
        </Descriptions.Item>
        {!noNotice ? (
          <Descriptions.Item label={t('field.givenNotice')}>{daysText(t, notice?.givenDays ?? 0, notice?.basis)}</Descriptions.Item>
        ) : null}
        {!noNotice ? (
          <Descriptions.Item label={t('field.shortfall')}>
            {notice && notice.shortfallDays > 0 ? (
              <Typography.Text type="danger">{daysText(t, notice.shortfallDays, notice.basis)}</Typography.Text>
            ) : (
              t('assessment.noShortfall')
            )}
          </Descriptions.Item>
        ) : null}
        {notice?.contractNo ? <Descriptions.Item label={t('field.contract')}>{notice.contractNo}</Descriptions.Item> : null}
        {notice && notice.consequences.length > 0 ? (
          <Descriptions.Item label={t('field.consequences')}>
            <Space wrap>
              {notice.consequences.map((c) => (
                <Tag key={c} color="red">
                  {c}
                </Tag>
              ))}
            </Space>
          </Descriptions.Item>
        ) : null}
        {severanceType ? <Descriptions.Item label={t('field.severanceType')}>{label('severanceType', severanceType)}</Descriptions.Item> : null}
        {severanceEligibility ? (
          <Descriptions.Item label={t('field.severanceEligibility')}>{label('eligibility', severanceEligibility)}</Descriptions.Item>
        ) : null}
        {terminationDate ? <Descriptions.Item label={t('field.terminationDate')}>{formatDate(terminationDate)}</Descriptions.Item> : null}
        {accessRevocationDate ? (
          <Descriptions.Item label={t('field.accessRevocationDate')}>{formatDate(accessRevocationDate)}</Descriptions.Item>
        ) : null}
        {statutoryCode ? <Descriptions.Item label={t('field.statutoryCode')}>{statutoryCode}</Descriptions.Item> : null}
        {protection ? (
          <Descriptions.Item label={t('field.protected')}>
            <ProtectionSummary protection={protection} />
          </Descriptions.Item>
        ) : null}
      </Descriptions>
    </div>
  );
}

export function ProtectionSummary({ protection: p }: { protection: Protection }) {
  const { t } = useTranslation(NS);
  return (
    <Space direction="vertical" size={4}>
      {p.protectedEmployee ? <Tag color="orange">{t('assessment.protected')}</Tag> : <Tag>{t('assessment.notProtected')}</Tag>}
      {p.statusReasons.length > 0 ? (
        <span>
          {t('assessment.statusReasons')}: {p.statusReasons.join(', ')}
        </span>
      ) : null}
      {p.absences.length > 0 ? (
        <span>
          {t('assessment.absences')}:{' '}
          {p.absences.map((a) => `${a.absenceTypeCode} ${formatDate(a.from)}–${formatDate(a.to)}`).join('; ')}
        </span>
      ) : null}
      {p.exceptionRequired && !p.exceptionDocumented ? <Typography.Text type="danger">{t('assessment.exceptionRequired')}</Typography.Text> : null}
      {p.exceptionDocumented ? <Tag color="green">{t('assessment.exceptionDocumented')}</Tag> : null}
      {!p.leaveDataAvailable ? <Typography.Text type="warning">{t('assessment.leaveDataUnavailable')}</Typography.Text> : null}
    </Space>
  );
}

// -------------------------------------------------------------------------------------------- attribute form

/** Ant form values of `SeparationAttributes` (dates as Dayjs). */
export interface AttributeValues {
  legalGroundCode?: string;
  reasonCode?: string | null;
  requestDate?: Dayjs | null;
  noticeDate?: Dayjs | null;
  lastWorkingDate?: Dayjs | null;
  terminationDate?: Dayjs | null;
  accessRevocationDate?: Dayjs | null;
  specialTradeCode?: string | null;
  noticeExemptReasonCode?: string | null;
  protectedOverrideReason?: string | null;
  rehireEligible?: boolean;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
}

const blank = (v: string | null | undefined) => (v && v.trim() ? v.trim() : null);

export function toAttributes(v: AttributeValues): SeparationAttributes {
  return {
    legalGroundCode: v.legalGroundCode ?? '',
    reasonCode: v.reasonCode ?? null,
    requestDate: toIsoDate(v.requestDate),
    noticeDate: toIsoDate(v.noticeDate),
    lastWorkingDate: toIsoDate(v.lastWorkingDate) ?? '',
    terminationDate: toIsoDate(v.terminationDate),
    accessRevocationDate: toIsoDate(v.accessRevocationDate),
    specialTradeCode: blank(v.specialTradeCode),
    noticeExemptReasonCode: blank(v.noticeExemptReasonCode),
    protectedOverrideReason: blank(v.protectedOverrideReason),
    rehireEligible: v.rehireEligible ?? null,
    signatoryName: blank(v.signatoryName),
    signatoryTitle: blank(v.signatoryTitle),
  };
}

export function fromSeparation(s: SeparationView): AttributeValues {
  return {
    legalGroundCode: s.legalGroundCode ?? undefined,
    reasonCode: s.reasonCode ?? null,
    requestDate: fromIsoDate(s.requestDate),
    noticeDate: fromIsoDate(s.noticeDate),
    lastWorkingDate: fromIsoDate(s.lastWorkingDate),
    terminationDate: fromIsoDate(s.terminationDate),
    accessRevocationDate: fromIsoDate(s.accessRevocationDate),
    specialTradeCode: s.specialTradeCode ?? null,
    noticeExemptReasonCode: s.noticeExemptReasonCode ?? null,
    protectedOverrideReason: s.protectedOverrideReason ?? null,
    rehireEligible: s.rehireEligible ?? undefined,
    signatoryName: s.signatoryName ?? null,
    signatoryTitle: s.signatoryTitle ?? null,
  };
}

/**
 * The fields of `AttributesRequest`: legal ground (catalogue from the legal pack; unconfigured grounds disabled),
 * detailed reason of that ground, the dates, notice inputs, protected-employee override and signatory.
 */
export function AttributeFields({
  form,
  catalogue,
  catalogueLoading,
}: {
  form: FormInstance<AttributeValues>;
  catalogue: ReasonCatalogue | undefined;
  catalogueLoading?: boolean;
}) {
  const { t, i18n } = useTranslation(NS);
  const ground = Form.useWatch('legalGroundCode', form);
  const grounds: LegalGround[] = catalogue?.grounds ?? [];
  const reasons = (catalogue?.subReasons ?? []).filter((r) => r.groundCode === ground && (r.active || r.code === form.getFieldValue('reasonCode')));
  const date = <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />;
  return (
    <>
      <Form.Item name="legalGroundCode" label={t('field.legalGround')} rules={[{ required: true, message: t('errors.legal-ground-required') }]}>
        <Select
          showSearch
          optionFilterProp="label"
          loading={catalogueLoading}
          onChange={() => form.setFieldValue('reasonCode', null)}
          options={grounds.map((g) => ({
            value: g.code,
            disabled: !g.configured,
            label: `${groundLabel(catalogue, g.code, i18n.language)}${g.configured ? '' : ` — ${t('start.groundNotConfigured')}`}`,
          }))}
        />
      </Form.Item>
      {reasons.length > 0 ? (
        <Form.Item name="reasonCode" label={t('field.reason')}>
          <Select allowClear options={reasons.map((r) => ({ value: r.code, label: localizedName(r, i18n.language) || r.code }))} />
        </Form.Item>
      ) : null}
      <Row gutter={12}>
        <Col xs={24} sm={12}>
          <Form.Item name="requestDate" label={t('field.requestDate')}>
            {date}
          </Form.Item>
        </Col>
        <Col xs={24} sm={12}>
          <Form.Item name="noticeDate" label={t('field.noticeDate')}>
            {date}
          </Form.Item>
        </Col>
        <Col xs={24} sm={12}>
          <Form.Item
            name="lastWorkingDate"
            label={t('field.lastWorkingDate')}
            rules={[{ required: true, message: t('errors.last-working-date-required') }]}
          >
            {date}
          </Form.Item>
        </Col>
        <Col xs={24} sm={12}>
          <Form.Item name="terminationDate" label={t('field.terminationDate')}>
            {date}
          </Form.Item>
        </Col>
        <Col xs={24} sm={12}>
          <Form.Item name="accessRevocationDate" label={t('field.accessRevocationDate')}>
            {date}
          </Form.Item>
        </Col>
      </Row>
      <Row gutter={12}>
        <Col xs={24} sm={12}>
          <Form.Item name="specialTradeCode" label={t('field.specialTradeCode')} rules={[{ max: 60 }]}>
            <Input />
          </Form.Item>
        </Col>
        <Col xs={24} sm={12}>
          <Form.Item name="noticeExemptReasonCode" label={t('field.noticeExemptReasonCode')} rules={[{ max: 60 }]}>
            <Input />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item name="protectedOverrideReason" label={t('field.protectedOverrideReason')} rules={[{ max: 2000 }]}>
        <Input.TextArea rows={2} />
      </Form.Item>
      <Row gutter={12}>
        <Col xs={24} sm={12}>
          <Form.Item name="signatoryName" label={t('field.signatoryName')} rules={[{ max: 200 }]}>
            <Input />
          </Form.Item>
        </Col>
        <Col xs={24} sm={12}>
          <Form.Item name="signatoryTitle" label={t('field.signatoryTitle')} rules={[{ max: 200 }]}>
            <Input />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item name="rehireEligible" valuePropName="checked">
        <Checkbox>{t('field.rehireEligible')}</Checkbox>
      </Form.Item>
    </>
  );
}
