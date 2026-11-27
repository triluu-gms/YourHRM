import { useState } from 'react';
import { Alert, Card, Col, DatePicker, Empty, Form, Progress, Row, Skeleton, Space, Tag, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DescriptionList, PageHeader, ProblemAlert } from '@/shared/components';
import { DATE_FORMAT, formatDate, ISO_DATE } from '@/shared/format';
import { ATT_NS, EmployeeSelect, LegalEntitySelect } from '../../common';
import type { CapLevel, OvertimeCapView } from './api';
import { useOvertimeCaps, useOvertimeRegime } from './queries';
import { NS, useHours } from './components';

const LEVEL_COLOUR: Record<CapLevel, string> = { OK: 'green', WARN: 'gold', EXCEEDED: 'red' };

function CapCard({ cap }: { cap: OvertimeCapView }) {
  const { t } = useTranslation(NS);
  const hours = useHours();
  const percent = cap.capMin > 0 ? Math.round((cap.usedMin / cap.capMin) * 1000) / 10 : 0;
  const period = cap.kind === 'MONTH' ? dayjs(cap.periodStart).format('MM/YYYY') : dayjs(cap.periodStart).format('YYYY');
  const status = cap.level === 'EXCEEDED' ? 'exception' : cap.level === 'WARN' ? 'normal' : 'success';
  return (
    <Card
      size="small"
      title={t(cap.kind === 'MONTH' ? 'caps.month' : 'caps.year', { period })}
      extra={<Tag color={LEVEL_COLOUR[cap.level] ?? 'default'}>{t(`level.${cap.level}`, { defaultValue: cap.level })}</Tag>}
    >
      <Progress
        percent={Math.min(percent, 100)}
        status={status}
        strokeColor={cap.level === 'WARN' ? '#faad14' : undefined}
        format={() => `${percent}%`}
        aria-label={t(cap.kind === 'MONTH' ? 'caps.month' : 'caps.year', { period })}
      />
      <Typography.Paragraph style={{ marginTop: 8 }}>
        {t('caps.used', { used: hours(cap.usedMin), cap: hours(cap.capMin) })}
        {' · '}
        {t('caps.remaining', { value: hours(Math.max(cap.capMin - cap.usedMin, 0)) })}
      </Typography.Paragraph>
      <DescriptionList
        column={1}
        items={[
          { label: t('field.regime'), value: t(`regime.${cap.regime}`, { defaultValue: cap.regime }) },
          { label: t('field.cap'), value: hours(cap.capMin) },
          { label: t('field.opening'), value: hours(cap.openingMin) },
          { label: t('field.approved'), value: hours(cap.approvedMin) },
          { label: t('field.pending'), value: hours(cap.pendingMin) },
          { label: t('field.paid'), value: hours(cap.paidMin) },
          { label: t('field.override'), value: hours(cap.overrideMin) },
        ]}
      />
    </Card>
  );
}

/**
 * `/attendance/overtime-caps?employmentId=…&legalEntityId=…`: the employee's monthly and annual usage against the cap
 * of their legal entity (approved + pending + opening, BR-ATT-046) and the legal entity's annual regime (BR-ATT-049).
 */
export function OvertimeCapsPage({
  employmentId,
  legalEntityId,
  onChange,
}: {
  employmentId?: UUID;
  legalEntityId?: UUID;
  onChange: (next: { employmentId?: UUID; legalEntityId?: UUID }) => void;
}) {
  const { t } = useTranslation(NS);
  const { t: ta } = useTranslation(ATT_NS);
  const hours = useHours();
  const [asOf, setAsOf] = useState<Dayjs>(dayjs());
  const date = asOf.format(ISO_DATE);
  const caps = useOvertimeCaps(employmentId, date);
  const regime = useOvertimeRegime(legalEntityId, date);

  return (
    <div>
      <PageHeader title={t('caps.title')} subTitle={t('caps.subTitle')} />
      <Card size="small" style={{ marginBottom: 16 }}>
        <Form layout="inline">
          <Form.Item label={t('caps.asOf')}>
            <DatePicker format={DATE_FORMAT} allowClear={false} value={asOf} onChange={(d) => d && setAsOf(d)} />
          </Form.Item>
        </Form>
      </Card>

      <Card
        size="small"
        title={t('caps.employeeCard')}
        style={{ marginBottom: 16 }}
        extra={
          <EmployeeSelect
            style={{ minWidth: 320 }}
            aria-label={ta('common.employee')}
            value={employmentId}
            onChange={(v) => onChange({ employmentId: v ?? undefined, legalEntityId })}
          />
        }
      >
        {!employmentId ? (
          <Empty description={ta('common.selectEmployeeFirst')} />
        ) : caps.isLoading ? (
          <Skeleton active />
        ) : caps.error ? (
          <ProblemAlert error={caps.error} />
        ) : (
          <Row gutter={[16, 16]}>
            {(caps.data ?? []).map((c) => (
              <Col key={c.kind} xs={24} md={12}>
                <CapCard cap={c} />
              </Col>
            ))}
          </Row>
        )}
      </Card>

      <Card
        size="small"
        title={t('caps.regimeCard')}
        extra={
          <LegalEntitySelect
            style={{ minWidth: 280 }}
            aria-label={ta('common.legalEntity')}
            value={legalEntityId}
            onChange={(v) => onChange({ employmentId, legalEntityId: v ?? undefined })}
          />
        }
      >
        {!legalEntityId ? (
          <Empty description={t('caps.selectLegalEntity')} />
        ) : regime.isLoading ? (
          <Skeleton active />
        ) : regime.error ? (
          <ProblemAlert error={regime.error} />
        ) : regime.data ? (
          <Space direction="vertical" style={{ width: '100%' }}>
            {regime.data.noticeRequired ? <Alert type="warning" showIcon message={t('caps.noticeRequired')} /> : null}
            <DescriptionList
              items={[
                { label: t('caps.asOf'), value: formatDate(regime.data.asOf) },
                { label: t('field.regimeRequested'), value: t(`regime.${regime.data.regimeRequested}`, { defaultValue: regime.data.regimeRequested }) },
                { label: t('field.regimeApplied'), value: t(`regime.${regime.data.regimeApplied}`, { defaultValue: regime.data.regimeApplied }) },
                { label: t('field.noticeRef'), value: regime.data.noticeRef },
                { label: t('field.monthCap'), value: hours(regime.data.monthCapMin) },
                { label: t('field.yearCap'), value: hours(regime.data.yearCapMin) },
              ]}
            />
            <Typography.Text type="secondary">{t('caps.legalNote')}</Typography.Text>
          </Space>
        ) : null}
      </Card>
    </div>
  );
}
