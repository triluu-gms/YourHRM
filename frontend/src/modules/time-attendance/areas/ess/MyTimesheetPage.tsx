import { useState } from 'react';
import { Button, Card, Col, Drawer, Row, Skeleton, Statistic, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { ISODate } from '@/shared/api';
import { DataTable, DescriptionList, ProblemAlert, StatusTag } from '@/shared/components';
import { formatDate, formatDateTime } from '@/shared/format';
import type { MyCorrection, MyDay, MyPunch } from './api';
import { useEmploymentChoice, useMyDay, useMyExceptions, useMyPunches, useMyTimesheets } from './queries';
import { EssFrame, ExceptionTags, MonthPicker, monthRange, qty, thisMonth, useLang } from './shared';
import { ESS_NS } from './i18n';
import './i18n';

const time = (v: string | null | undefined) => (v ? formatDateTime(v).slice(11) : '');

/** "My timesheet": the days of a month with codes and hours, the exceptions, the raw punches and a day's detail. */
export function MyTimesheetPage() {
  const { t } = useTranslation(ESS_NS);
  const lang = useLang();
  const choice = useEmploymentChoice();
  const [month, setMonth] = useState(thisMonth);
  const [openDay, setOpenDay] = useState<ISODate | null>(null);
  const params = { employmentId: choice.employmentId, lang, ...monthRange(month) };
  const days = useMyTimesheets(params, choice.ready);
  const exceptions = useMyExceptions(params, choice.ready);
  const punches = useMyPunches({ employmentId: choice.employmentId, ...monthRange(month) }, choice.ready);

  const list = days.data ?? [];
  const worked = list.reduce((s, d) => s + (d.workedHours ?? 0), 0);
  const dayValue = list.reduce((s, d) => s + (d.dayValue ?? 0), 0);
  const late = list.reduce((s, d) => s + d.lateMin + d.earlyMin, 0);

  const dayColumns = [
    {
      title: t('timesheet.date'),
      dataIndex: 'workDate',
      render: (v: string) => (
        <Button type="link" size="small" style={{ padding: 0 }} onClick={() => setOpenDay(v)}>
          {formatDate(v)} <Typography.Text type="secondary">({dayjs(v).format('dd')})</Typography.Text>
        </Button>
      ),
    },
    { title: t('timesheet.dayType'), key: 'type', render: (_: unknown, d: MyDay) => d.dayTypeLabel ?? d.dayType ?? '—' },
    {
      title: t('timesheet.code'),
      key: 'code',
      render: (_: unknown, d: MyDay) =>
        d.primaryCode ? (
          <Tooltip title={d.primaryCodeLabel}>
            <Tag>{d.primaryCode}</Tag>
          </Tooltip>
        ) : (
          '—'
        ),
    },
    { title: t('timesheet.status'), key: 'status', render: (_: unknown, d: MyDay) => d.dayStatusLabel || d.dayStatus },
    { title: t('timesheet.firstIn'), dataIndex: 'firstInAt', render: time },
    { title: t('timesheet.lastOut'), dataIndex: 'lastOutAt', render: time },
    { title: t('timesheet.scheduledHours'), dataIndex: 'scheduledHours', align: 'right' as const, render: qty },
    { title: t('timesheet.workedHours'), dataIndex: 'workedHours', align: 'right' as const, render: qty },
    { title: t('timesheet.lateEarly'), key: 'le', align: 'right' as const, render: (_: unknown, d: MyDay) => (d.lateMin || d.earlyMin ? `${d.lateMin} / ${d.earlyMin}` : '') },
    { title: t('timesheet.dayValue'), dataIndex: 'dayValue', align: 'right' as const, render: qty },
    {
      title: t('timesheet.exceptions'),
      key: 'ex',
      render: (_: unknown, d: MyDay) => (
        <>
          <ExceptionTags items={d.exceptions} />
          {d.manualOverride ? <Tag color="blue">{t('timesheet.manual')}</Tag> : null}
        </>
      ),
    },
  ];

  return (
    <EssFrame
      title={t('timesheet.title')}
      subTitle={t('timesheet.intro')}
      controls={<MonthPicker value={month} onChange={setMonth} />}
    >
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic title={t('timesheet.totalDayValue')} value={qty(dayValue) || '0'} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic title={t('timesheet.totalWorked')} value={qty(worked) || '0'} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic title={t('timesheet.totalLateEarly')} value={late} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic title={t('timesheet.exceptionDays')} value={exceptions.data?.length ?? 0} />
          </Card>
        </Col>
      </Row>

      <Tabs
        items={[
          {
            key: 'days',
            label: t('timesheet.tabs.days'),
            children: (
              <DataTable<MyDay>
                size="small"
                rowKey="workDate"
                data={list}
                isLoading={days.isFetching}
                error={days.error}
                columns={dayColumns}
              />
            ),
          },
          {
            key: 'exceptions',
            label: t('timesheet.tabs.exceptions', { count: exceptions.data?.length ?? 0 }),
            children: (
              <DataTable<MyDay>
                size="small"
                rowKey="workDate"
                data={exceptions.data}
                isLoading={exceptions.isFetching}
                error={exceptions.error}
                columns={dayColumns}
              />
            ),
          },
          {
            key: 'punches',
            label: t('timesheet.tabs.punches'),
            children: <PunchTable punches={punches.data} loading={punches.isFetching} error={punches.error} />,
          },
        ]}
      />

      <DayDrawer date={openDay} onClose={() => setOpenDay(null)} />
    </EssFrame>
  );
}

function PunchTable({ punches, loading, error }: { punches: MyPunch[] | undefined; loading?: boolean; error?: unknown }) {
  const { t } = useTranslation(ESS_NS);
  return (
    <DataTable<MyPunch>
      size="small"
      rowKey={(p) => `${p.punchAt}:${p.terminalCode ?? ''}`}
      data={punches}
      isLoading={loading}
      error={error}
      columns={[
        { title: t('punch.at'), dataIndex: 'punchAt', render: formatDateTime },
        { title: t('punch.localTime'), dataIndex: 'localTime', render: (v: string) => dayjs(v).format('DD/MM/YYYY HH:mm') },
        { title: t('punch.direction'), dataIndex: 'direction', render: (v: string | null) => (v ? t(`punch.directions.${v}`, { defaultValue: v }) : '—') },
        { title: t('punch.terminal'), dataIndex: 'terminalCode' },
        { title: t('punch.status'), dataIndex: 'status', render: (v: string) => t(`punch.statuses.${v}`, { defaultValue: v }) },
      ]}
    />
  );
}

function DayDrawer({ date, onClose }: { date: ISODate | null; onClose: () => void }) {
  const { t } = useTranslation(ESS_NS);
  const lang = useLang();
  const choice = useEmploymentChoice();
  const q = useMyDay(date, { employmentId: choice.employmentId, lang }, choice.ready);
  const d = q.data?.day;

  return (
    <Drawer open={date !== null} onClose={onClose} width={640} title={t('day.title', { date: formatDate(date) })} destroyOnHidden>
      {q.isLoading ? <Skeleton active /> : null}
      {q.error ? <ProblemAlert error={q.error} /> : null}
      {d ? (
        <>
          <DescriptionList
            column={2}
            items={[
              { label: t('timesheet.dayType'), value: d.dayTypeLabel ?? d.dayType },
              { label: t('timesheet.status'), value: d.dayStatusLabel || d.dayStatus },
              { label: t('timesheet.code'), value: d.primaryCode ? `${d.primaryCode} – ${d.primaryCodeLabel ?? ''}` : null },
              { label: t('timesheet.dayValue'), value: qty(d.dayValue) },
              { label: t('timesheet.firstIn'), value: formatDateTime(d.firstInAt) },
              { label: t('timesheet.lastOut'), value: formatDateTime(d.lastOutAt) },
              { label: t('timesheet.scheduledHours'), value: qty(d.scheduledHours) },
              { label: t('timesheet.workedHours'), value: qty(d.workedHours) },
              { label: t('day.lateMin'), value: d.lateMin },
              { label: t('day.earlyMin'), value: d.earlyMin },
              { label: t('day.absentHours'), value: qty(d.absentHours) },
              { label: t('day.nightHours'), value: qty(d.nightHours) },
              { label: t('timesheet.exceptions'), value: <ExceptionTags items={d.exceptions} />, span: 2 },
            ]}
          />
          {d.codes.length > 0 ? (
            <>
              <Typography.Title level={5} style={{ marginTop: 16 }}>
                {t('day.codes')}
              </Typography.Title>
              <Table
                size="small"
                pagination={false}
                rowKey="code"
                dataSource={d.codes}
                columns={[
                  { title: t('timesheet.code'), dataIndex: 'code' },
                  { title: t('day.label'), dataIndex: 'label' },
                  { title: t('day.quantity'), dataIndex: 'quantity', align: 'right', render: qty },
                ]}
              />
            </>
          ) : null}
          <Typography.Title level={5} style={{ marginTop: 16 }}>
            {t('day.punches')}
          </Typography.Title>
          <PunchTable punches={q.data?.punches} />
          <Typography.Title level={5} style={{ marginTop: 16 }}>
            {t('day.corrections')}
          </Typography.Title>
          <DataTable<MyCorrection>
            size="small"
            data={q.data?.corrections}
            columns={[
              { title: t('day.correctionType'), dataIndex: 'correctionType', render: (v: string) => t(`day.correctionTypes.${v}`, { defaultValue: v }) },
              { title: t('day.requestedIn'), dataIndex: 'requestedInAt', render: formatDateTime },
              { title: t('day.requestedOut'), dataIndex: 'requestedOutAt', render: formatDateTime },
              { title: t('day.requestedCode'), dataIndex: 'requestedCode' },
              { title: t('timesheet.status'), key: 'st', render: (_, c) => <StatusTag status={c.status} labels={{ [c.status]: c.statusLabel }} /> },
              { title: t('day.createdAt'), dataIndex: 'createdAt', render: formatDateTime },
            ]}
          />
        </>
      ) : null}
    </Drawer>
  );
}
