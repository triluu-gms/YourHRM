import { useState } from 'react';
import { Button, Card, DatePicker, Empty, Form, List, Space, Tabs, Tag } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { ATT_NS, EmployeeSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import type { OvertimeOutcome, OvertimeRequestView, OvertimeResultView } from './api';
import { useCreateOvertime, useOvertimeRequests, useOvertimeResults } from './queries';
import { CategoryLabel, CreateOvertimeDrawer, FindingsList, formatTime, NS, OvertimeStatusTag, useHours, useMonthRange } from './components';

/**
 * `/attendance/overtime-requests?employmentId=…`: an employee's overtime requests and paid results in a date range,
 * the create drawer (single or group request) and the outcome of the last create.
 */
export function OvertimeRequestsPage({
  employmentId,
  onEmploymentChange,
}: {
  employmentId?: UUID;
  onEmploymentChange: (employmentId: UUID | undefined) => void;
}) {
  const { t } = useTranslation(NS);
  const { t: ta } = useTranslation(ATT_NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const hours = useHours();
  const { range, setRange, params } = useMonthRange();
  const requests = useOvertimeRequests(employmentId, params);
  const results = useOvertimeResults(employmentId, params);
  const create = useCreateOvertime();
  const [drawer, setDrawer] = useState(false);
  const [outcomes, setOutcomes] = useState<OvertimeOutcome[] | null>(null);

  const requestColumns = [
    { title: t('field.workDate'), dataIndex: 'workDate', render: (d: string) => formatDate(d) },
    {
      title: t('field.time'),
      key: 'time',
      render: (_: unknown, r: OvertimeRequestView) => `${formatTime(r.plannedStartAt)} – ${formatTime(r.plannedEndAt)}`,
    },
    { title: t('field.plannedHours'), dataIndex: 'plannedMinutes', render: (m: number) => hours(m) },
    { title: t('field.status'), dataIndex: 'status', render: (s: string) => <OvertimeStatusTag status={s} /> },
    {
      title: t('field.consent'),
      key: 'consent',
      render: (_: unknown, r: OvertimeRequestView) =>
        r.employeeConsent ? (
          <Tag color="green">{r.consentMethod ? t(`consentMethod.${r.consentMethod}`) : tc('common.yes')}</Tag>
        ) : (
          <Tag>{tc('common.no')}</Tag>
        ),
    },
    {
      title: t('field.protectedWorker'),
      dataIndex: 'protectedWorker',
      render: (v: boolean) => (v ? <Tag color="purple">{t('field.protectedWorker')}</Tag> : null),
    },
    { title: t('field.capOverride'), dataIndex: 'capOverride', render: (v: boolean) => (v ? <Tag color="orange">{t('field.capOverride')}</Tag> : null) },
    { title: t('field.reason'), dataIndex: 'reason' },
    {
      title: tc('field.actions'),
      key: 'actions',
      render: (_: unknown, r: OvertimeRequestView) => (
        <Link to="/attendance/overtime-requests/$requestId" params={{ requestId: r.id }}>
          {t('requests.open')}
        </Link>
      ),
    },
  ];

  const resultColumns = [
    { title: t('field.workDate'), dataIndex: 'workDate', render: (d: string) => formatDate(d) },
    { title: t('field.category'), dataIndex: 'otCategory', render: (c: string) => <CategoryLabel code={c} /> },
    { title: t('field.actual'), dataIndex: 'actualMinutes', render: (m: number) => hours(m) },
    { title: t('field.approved'), dataIndex: 'approvedMinutes', render: (m: number) => hours(m) },
    { title: t('field.paid'), dataIndex: 'paidMinutes', render: (m: number) => hours(m) },
    { title: t('field.precededByDayOt'), dataIndex: 'precededByDayOt', render: (v: boolean) => (v ? tc('common.yes') : '') },
    { title: t('field.calcVersion'), dataIndex: 'calcVersion' },
  ];

  return (
    <div>
      <PageHeader
        title={t('requests.title')}
        subTitle={t('requests.subTitle')}
        extra={
          <PermissionGate anyOf={P.OVERTIME_REQUEST}>
            <Button
              type="primary"
              onClick={() => {
                create.reset();
                setDrawer(true);
              }}
            >
              {t('requests.create')}
            </Button>
          </PermissionGate>
        }
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <Form layout="inline">
          <Form.Item label={ta('common.employee')}>
            <EmployeeSelect
              style={{ minWidth: 320 }}
              aria-label={ta('common.employee')}
              value={employmentId}
              onChange={(v) => onEmploymentChange(v ?? undefined)}
            />
          </Form.Item>
          <Form.Item label={t('requests.range')}>
            <DatePicker.RangePicker
              format={DATE_FORMAT}
              allowClear={false}
              value={range}
              onChange={(v) => {
                if (v?.[0] && v[1]) setRange([v[0], v[1]]);
              }}
            />
          </Form.Item>
        </Form>
      </Card>

      {outcomes ? (
        <Card size="small" title={t('requests.outcomesTitle')} style={{ marginBottom: 16 }} extra={<Button size="small" onClick={() => setOutcomes(null)}>{tc('action.close')}</Button>}>
          <List
            dataSource={outcomes}
            renderItem={(o) => (
              <List.Item>
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Space wrap>
                    <Link to="/attendance/overtime-requests/$requestId" params={{ requestId: o.request.id }}>
                      {formatDate(o.request.workDate)} {formatTime(o.request.plannedStartAt)} – {formatTime(o.request.plannedEndAt)}
                    </Link>
                    <OvertimeStatusTag status={o.request.status} />
                    <Tag color={o.submitted ? 'green' : 'default'}>{o.submitted ? t('requests.outcomeSubmitted') : t('requests.outcomeDraft')}</Tag>
                  </Space>
                  <FindingsList findings={o.findings} />
                </Space>
              </List.Item>
            )}
          />
        </Card>
      ) : null}

      {!employmentId ? (
        <Empty description={ta('common.selectEmployeeFirst')} />
      ) : (
        <Tabs
          items={[
            {
              key: 'requests',
              label: t('requests.tabRequests'),
              children: <DataTable<OvertimeRequestView> data={requests.data} isLoading={requests.isLoading} error={requests.error} columns={requestColumns} />,
            },
            {
              key: 'results',
              label: t('requests.tabResults'),
              children: <DataTable<OvertimeResultView> data={results.data} isLoading={results.isLoading} error={results.error} columns={resultColumns} />,
            },
          ]}
        />
      )}

      <CreateOvertimeDrawer
        open={drawer}
        employmentId={employmentId}
        onClose={() => setDrawer(false)}
        pending={create.isPending}
        error={create.error}
        submit={async (body) => {
          const out = await create.mutateAsync(body);
          setOutcomes(out);
          fb.done(t('requests.created', { count: out.length }));
          return out;
        }}
      />
    </div>
  );
}
