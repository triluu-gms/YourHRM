import { useState } from 'react';
import { Card, Col, DatePicker, Empty, Progress, Row, Skeleton, Tag } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { DescriptionList, ProblemAlert } from '@/shared/components';
import { DATE_FORMAT } from '@/shared/format';
import type { CapView } from './api';
import { useEmploymentChoice, useMyOvertimeCaps } from './queries';
import { EssFrame, hours, iso } from './shared';
import { ESS_NS } from './i18n';
import './i18n';

const LEVEL_COLOURS: Record<string, string> = { OK: 'green', WARN: 'gold', EXCEEDED: 'red' };

/** "My overtime": use of the monthly and yearly overtime caps (opening + approved + pending) on a date. */
export function MyOvertimePage() {
  const { t } = useTranslation(ESS_NS);
  const choice = useEmploymentChoice();
  const [date, setDate] = useState<Dayjs>(() => dayjs());
  const q = useMyOvertimeCaps({ employmentId: choice.employmentId, date: iso(date) }, choice.ready);

  return (
    <EssFrame
      title={t('overtime.title')}
      subTitle={t('overtime.intro')}
      controls={
        <DatePicker
          aria-label={t('overtime.date')}
          format={DATE_FORMAT}
          allowClear={false}
          value={date}
          onChange={(d) => d && setDate(d)}
        />
      }
    >
      {q.error ? <ProblemAlert error={q.error} style={{ marginBottom: 12 }} /> : null}
      {q.isLoading ? <Skeleton active /> : null}
      {q.data && q.data.length === 0 ? <Empty description={t('overtime.none')} /> : null}
      <Row gutter={[16, 16]}>
        {(q.data ?? []).map((c) => (
          <Col key={c.kind} xs={24} lg={12}>
            <CapCard cap={c} />
          </Col>
        ))}
      </Row>
    </EssFrame>
  );
}

function CapCard({ cap }: { cap: CapView }) {
  const { t } = useTranslation(ESS_NS);
  const pct = cap.capMin > 0 ? Math.round((cap.usedMin / cap.capMin) * 100) : 0;
  return (
    <Card
      title={t(`overtime.kinds.${cap.kind}`, { defaultValue: cap.kind })}
      extra={<Tag color={LEVEL_COLOURS[cap.level] ?? 'default'}>{t(`overtime.levels.${cap.level}`, { defaultValue: cap.level })}</Tag>}
    >
      <Progress
        percent={Math.min(100, pct)}
        status={cap.level === 'EXCEEDED' ? 'exception' : 'normal'}
        strokeColor={cap.level === 'WARN' ? '#faad14' : undefined}
        format={() => t('overtime.usedOfCap', { used: hours(cap.usedMin), cap: hours(cap.capMin) })}
      />
      <DescriptionList
        column={1}
        style={{ marginTop: 12 }}
        items={[
          { label: t('overtime.regime'), value: cap.regime ? t(`overtime.regimes.${cap.regime}`, { defaultValue: cap.regime }) : null },
          { label: t('overtime.opening'), value: hours(cap.openingMin) },
          { label: t('overtime.approved'), value: hours(cap.approvedMin) },
          { label: t('overtime.pending'), value: hours(cap.pendingMin) },
          { label: t('overtime.paid'), value: hours(cap.paidMin) },
          { label: t('overtime.override'), value: hours(cap.overrideMin) },
          { label: t('overtime.remaining'), value: hours(Math.max(0, cap.capMin - cap.usedMin)) },
        ]}
      />
    </Card>
  );
}
