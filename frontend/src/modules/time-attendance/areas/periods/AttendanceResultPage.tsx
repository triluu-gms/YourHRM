import { useState } from 'react';
import { Button, Card, Result, Skeleton, Space, Tag, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, DescriptionList, PageHeader, ProblemAlert } from '@/shared/components';
import { formatDate, formatDateTime } from '@/shared/format';
import { EmployeeSelect } from '../../common';
import type { ResultLine } from './api';
import { useAttendanceResult, useResultLines } from './queries';
import { AreaStatusTag, LegalEntityName, NS, range, ShortId } from './components';

const quantity = (n: number | null | undefined) =>
  n === null || n === undefined ? '' : new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 4 }).format(n);

/** `/attendance/attendance-results/$resultId`: an immutable Attendance Result version (header + BRD §7.3 lines). */
export function AttendanceResultPage({ resultId }: { resultId: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [employmentId, setEmploymentId] = useState<UUID | undefined>();
  const q = useAttendanceResult(resultId);
  const lines = useResultLines(resultId, employmentId);

  const r = q.data;
  if (q.isLoading) return <Skeleton active />;
  if (q.error || !r) {
    return <Result status="404" title={t('result.notFound')} extra={q.error ? <ProblemAlert error={q.error} /> : null} />;
  }

  return (
    <div>
      <PageHeader
        title={
          <Space wrap>
            {t('result.title', { version: r.version })}
            <AreaStatusTag status={r.status} />
          </Space>
        }
        subTitle={range(r.periodStart, r.periodEnd)}
        breadcrumb={[
          { label: t('detail.back'), to: '/attendance/periods' },
          { label: range(r.periodStart, r.periodEnd), to: `/attendance/periods/${r.periodId}` },
          { label: `v${r.version}` },
        ]}
        extra={
          <Link to="/attendance/periods/$periodId" params={{ periodId: r.periodId }}>
            <Button>{t('result.openPeriod')}</Button>
          </Link>
        }
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: t('field.legalEntity'), value: <LegalEntityName id={r.legalEntityId} /> },
            { label: t('field.payGroup'), value: <ShortId id={r.payGroupId} /> },
            { label: t('field.scope'), value: t(`scope.${r.scope}`, { defaultValue: r.scope }) },
            { label: t('field.employee'), value: r.employmentId ? <ShortId id={r.employmentId} /> : null, hideEmpty: true },
            { label: t('field.kind'), value: t(`kind.${r.kind}`, { defaultValue: r.kind }) },
            { label: t('field.version'), value: r.version },
            { label: t('field.status'), value: <AreaStatusTag status={r.status} /> },
            { label: t('field.closeCause'), value: t(`cause.${r.closeCause}`, { defaultValue: r.closeCause }) },
            { label: t('field.attRange'), value: range(r.attDateFrom, r.attDateTo) },
            { label: t('field.otRange'), value: range(r.otDateFrom, r.otDateTo), hideEmpty: true },
            { label: t('field.lineCount'), value: r.lineCount ?? null },
            { label: t('field.publishedAt'), value: formatDateTime(r.publishedAt) },
            { label: t('field.ruleVersion'), value: r.ruleVersion, hideEmpty: true },
            {
              label: t('field.checksum'),
              value: r.checksum ? <Typography.Text code copyable>{r.checksum}</Typography.Text> : null,
              hideEmpty: true,
            },
          ]}
        />
      </Card>

      <Card
        size="small"
        title={t('result.lines')}
        extra={
          <EmployeeSelect
            style={{ minWidth: 300 }}
            legalEntityId={r.legalEntityId}
            placeholder={t('result.filterEmployee')}
            aria-label={t('result.filterEmployee')}
            value={employmentId}
            onChange={(v) => setEmploymentId(v ?? undefined)}
          />
        }
      >
        <DataTable<ResultLine>
          data={lines.data}
          isLoading={lines.isLoading}
          error={lines.error}
          rowKey={(l) => `${l.employmentId}|${l.segmentFrom}|${l.category}|${l.retro ? l.originalSegmentFrom ?? '' : ''}`}
          columns={[
            { title: t('field.employee'), dataIndex: 'employmentId', render: (id: UUID) => <ShortId id={id} /> },
            { title: t('field.segment'), key: 'segment', render: (_: unknown, l: ResultLine) => range(l.segmentFrom, l.segmentTo) },
            {
              title: t('field.category'),
              dataIndex: 'category',
              render: (c: string, l: ResultLine) => (
                <Space size={4}>
                  <Typography.Text code>{c}</Typography.Text>
                  {l.retro ? <Tag color="purple">{t('field.retro')}</Tag> : null}
                  {l.precededByDayOt ? <Tag>{t('field.precededByDayOt')}</Tag> : null}
                </Space>
              ),
            },
            { title: t('field.unit'), dataIndex: 'unit' },
            { title: t('field.quantity'), dataIndex: 'quantity', align: 'right' as const, render: quantity },
            { title: t('field.periodQuantity'), dataIndex: 'periodQuantity', align: 'right' as const, render: quantity },
            { title: t('field.employeeGroup'), dataIndex: 'employeeGroupCode' },
            { title: t('field.fte'), dataIndex: 'fte', render: quantity },
            {
              title: t('field.originalSegment'),
              key: 'orig',
              render: (_: unknown, l: ResultLine) =>
                l.retro ? (
                  <Space size={4}>
                    {formatDate(l.originalSegmentFrom)}
                    {l.originalResultId ? (
                      <Link to="/attendance/attendance-results/$resultId" params={{ resultId: l.originalResultId }}>
                        {tc('action.view')}
                      </Link>
                    ) : null}
                  </Space>
                ) : null,
            },
            { title: t('field.retroReason'), dataIndex: 'retroReason' },
          ]}
        />
      </Card>
    </div>
  );
}
