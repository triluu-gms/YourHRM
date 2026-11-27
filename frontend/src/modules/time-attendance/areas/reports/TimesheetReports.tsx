import { useState } from 'react';
import { DatePicker, Form, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { usePermission } from '@/shared/auth';
import { DataTable, ProblemAlert, StatusTag } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime } from '@/shared/format';
import { EmployeeSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import {
  EXCEPTION_TYPES,
  PUNCH_STATUSES,
  type ExceptionLine,
  type ExceptionParams,
  type ExceptionType,
  type GridParams,
  type GridReport,
  type GridRow,
  type PunchLine,
  type PunchLogParams,
} from './api';
import { useDailyExceptions, useDeviceOptions, usePeriodOptions, usePunchLog, useTimesheetGrid } from './queries';
import {
  ExportButton,
  FilterForm,
  ReportFrame,
  ReportPager,
  ScopeFields,
  monthToDate,
  qty,
  toIso,
  useLang,
  useWindow,
  type ScopeValues,
} from './shared';
import { REPORTS_NS } from './i18n';
import './i18n';

const { RangePicker } = DatePicker;

// ------------------------------------------------------------------------------------------- R-01 grid

interface GridForm extends ScopeValues {
  periodId?: UUID;
  range?: [Dayjs, Dayjs] | null;
}

/** R-01 bảng chấm công: employee × day matrix of attendance codes with totals per payroll category. */
export function TimesheetGridReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const { t: ta } = useTranslation('att');
  const lang = useLang();
  const [form] = Form.useForm<GridForm>();
  const canPeriods = usePermission(P.PERIOD_VIEW);
  const legalEntityId = Form.useWatch('legalEntityId', form);
  const periods = usePeriodOptions(legalEntityId, canPeriods);
  const w = useWindow(200);
  const [filters, setFilters] = useState<Omit<GridParams, 'limit' | 'offset' | 'lang'> | null>(null);
  const params: GridParams | null = filters ? { ...filters, lang, limit: w.limit, offset: w.offset } : null;
  const query = useTimesheetGrid(params);

  return (
    <ReportFrame
      reportKey="timesheet-grid"
      extra={
        <ExportButton
          kind="timesheet-grid"
          params={params ? { ...params } : null}
          legalEntityId={filters?.legalEntityId}
          orgUnitId={filters?.orgUnitId}
        />
      }
      filters={
        <FilterForm<GridForm>
          form={form}
          initialValues={{ range: monthToDate() }}
          onApply={(v) => {
            w.reset();
            setFilters({
              legalEntityId: v.legalEntityId,
              orgUnitId: v.orgUnitId,
              periodId: v.periodId,
              from: v.periodId ? undefined : toIso(v.range?.[0]),
              to: v.periodId ? undefined : toIso(v.range?.[1]),
            });
          }}
        >
          <ScopeFields />
          {canPeriods ? (
            <Form.Item name="periodId" label={ta('common.period')}>
              <Select<UUID>
                allowClear
                style={{ width: 220 }}
                loading={periods.isLoading}
                placeholder={t('filter.periodOrRange')}
                options={(periods.data ?? []).map((p) => ({
                  value: p.id,
                  label: `${formatDate(p.periodStart)} – ${formatDate(p.periodEnd)}`,
                }))}
              />
            </Form.Item>
          ) : null}
          <Form.Item
            name="range"
            label={t('filter.range')}
            dependencies={['periodId']}
            rules={[
              ({ getFieldValue }) => ({
                validator: (_, v) =>
                  getFieldValue('periodId') || (v && v[0] && v[1])
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('filter.periodOrRangeRequired'))),
              }),
            ]}
          >
            <RangePicker format={DATE_FORMAT} />
          </Form.Item>
        </FilterForm>
      }
    >
      {query.error ? <ProblemAlert error={query.error} style={{ marginBottom: 12 }} /> : null}
      {!params ? (
        <Typography.Text type="secondary">{t('common.applyFilters')}</Typography.Text>
      ) : (
        <>
          <TimesheetGridTable report={query.data} loading={query.isFetching} />
          <ReportPager page={query.data?.page} window={w} />
        </>
      )}
    </ReportFrame>
  );
}

function TimesheetGridTable({ report, loading }: { report: GridReport | undefined; loading: boolean }) {
  const { t } = useTranslation(REPORTS_NS);
  const dates = report?.dates ?? [];
  const categories = report?.categories ?? [];
  const legend = new Map((report?.codes ?? []).map((c) => [c.code, c.label]));
  const weekend = (iso: string) => [0, 6].includes(dayjs(iso).day());
  const cell = { padding: '2px 4px', textAlign: 'center' as const, minWidth: 30 };

  return (
    <>
      <Table<GridRow>
        size="small"
        bordered
        rowKey="employmentId"
        loading={loading}
        dataSource={report?.page.items ?? []}
        pagination={false}
        scroll={{ x: 'max-content', y: 560 }}
        locale={{ emptyText: t('common.noRows') }}
        columns={[
          { title: t('col.employeeNo'), dataIndex: 'employeeNo', fixed: 'left', width: 100 },
          { title: t('col.fullName'), dataIndex: 'fullName', fixed: 'left', width: 180, ellipsis: true },
          ...dates.map((d, i) => ({
            key: d.date,
            title: (
              <div style={{ textAlign: 'center', lineHeight: 1.1 }}>
                <div>{d.dayOfMonth}</div>
                <Typography.Text type="secondary" style={{ fontSize: 10 }}>
                  {d.weekday}
                </Typography.Text>
              </div>
            ),
            width: 34,
            onCell: () => ({ style: { ...cell, background: weekend(d.date) ? 'rgba(0,0,0,0.04)' : undefined } }),
            onHeaderCell: () => ({ style: { ...cell } }),
            render: (_: unknown, r: GridRow) => {
              const code = r.cells[i];
              return code ? (
                <Tooltip title={legend.get(code) ?? code}>
                  <span>{code}</span>
                </Tooltip>
              ) : (
                ''
              );
            },
          })),
          ...categories.map((c) => ({
            key: `cat-${c.code}`,
            title: <Tooltip title={c.code}>{c.label}</Tooltip>,
            align: 'right' as const,
            render: (_: unknown, r: GridRow) => qty(r.categoryTotals[c.code]),
          })),
          { title: t('col.workedHours'), dataIndex: 'workedHours', align: 'right', render: qty },
          { title: t('col.overtimeHours'), dataIndex: 'overtimeHours', align: 'right', render: qty },
          { title: t('col.nightHours'), dataIndex: 'nightHours', align: 'right', render: qty },
          { title: t('col.lateMin'), dataIndex: 'lateMin', align: 'right' },
          { title: t('col.earlyMin'), dataIndex: 'earlyMin', align: 'right' },
          { title: t('col.exceptionDays'), dataIndex: 'exceptionDays', align: 'right' },
          { title: t('col.missingDays'), dataIndex: 'missingDays', align: 'right' },
        ]}
        summary={() =>
          report && Object.keys(report.pageTotals).length > 0 ? (
            <Table.Summary fixed>
              <Table.Summary.Row>
                <Table.Summary.Cell index={0} colSpan={2 + dates.length}>
                  <b>{t('grid.pageTotals')}</b>
                </Table.Summary.Cell>
                {categories.map((c, i) => (
                  <Table.Summary.Cell key={c.code} index={2 + dates.length + i} align="right">
                    <b>{qty(report.pageTotals[c.code])}</b>
                  </Table.Summary.Cell>
                ))}
              </Table.Summary.Row>
            </Table.Summary>
          ) : null
        }
      />
      {report && report.codes.length > 0 ? (
        <div style={{ marginTop: 12 }}>
          <Typography.Text type="secondary">{t('grid.legend')}: </Typography.Text>
          <Space size={[4, 4]} wrap>
            {report.codes.map((c) => (
              <Tag key={c.code}>
                <b>{c.code}</b> {c.label}
              </Tag>
            ))}
          </Space>
        </div>
      ) : null}
    </>
  );
}

// ------------------------------------------------------------------------------------- R-02 exceptions

interface ExceptionForm extends ScopeValues {
  employmentId?: UUID;
  range: [Dayjs, Dayjs];
  type?: ExceptionType;
}

/** R-02: late, early, missing punch and absent days. */
export function DailyExceptionsReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const { t: ta } = useTranslation('att');
  const lang = useLang();
  const [form] = Form.useForm<ExceptionForm>();
  const w = useWindow(200);
  const [filters, setFilters] = useState<Omit<ExceptionParams, 'limit' | 'offset' | 'lang'> | null>(null);
  const params = filters ? { ...filters, lang, limit: w.limit, offset: w.offset } : null;
  const query = useDailyExceptions(params);

  return (
    <ReportFrame
      reportKey="daily-exceptions"
      filters={
        <FilterForm<ExceptionForm>
          form={form}
          initialValues={{ range: monthToDate() }}
          onApply={(v) => {
            w.reset();
            setFilters({
              legalEntityId: v.legalEntityId,
              orgUnitId: v.orgUnitId,
              employmentId: v.employmentId,
              type: v.type,
              from: toIso(v.range[0]) as string,
              to: toIso(v.range[1]) as string,
            });
          }}
        >
          <ScopeFields />
          <Form.Item name="employmentId" label={ta('common.employee')}>
            <EmployeeSelect style={{ width: 240 }} />
          </Form.Item>
          <Form.Item name="range" label={t('filter.range')} rules={[{ required: true, message: t('filter.rangeRequired') }]}>
            <RangePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="type" label={t('filter.exceptionType')}>
            <Select<ExceptionType>
              allowClear
              style={{ width: 180 }}
              options={EXCEPTION_TYPES.map((x) => ({ value: x, label: t(`exceptionTypes.${x}`) }))}
            />
          </Form.Item>
        </FilterForm>
      }
    >
      {query.data ? (
        <Space wrap style={{ marginBottom: 12 }}>
          {query.data.summary.map((c) => (
            <Tag key={c.code} color={c.count > 0 ? 'orange' : 'default'}>
              {c.label}: {c.count}
            </Tag>
          ))}
        </Space>
      ) : null}
      {!params ? (
        <Typography.Text type="secondary">{t('common.applyFilters')}</Typography.Text>
      ) : (
        <>
          <DataTable<ExceptionLine>
            size="small"
            rowKey={(r) => `${r.employmentId}:${r.workDate}`}
            data={query.data?.page.items}
            isLoading={query.isFetching}
            error={query.error}
            columns={[
              { title: ta('common.workDate'), dataIndex: 'workDate', render: formatDate },
              { title: t('col.employment'), dataIndex: 'employmentId', render: (id: string) => <code>{id.slice(0, 8)}</code> },
              { title: t('col.dayStatus'), key: 'status', render: (_, r) => r.dayStatusLabel || r.dayStatus },
              { title: t('col.code'), dataIndex: 'primaryCode' },
              { title: t('col.firstIn'), dataIndex: 'firstInAt', render: formatDateTime },
              { title: t('col.lastOut'), dataIndex: 'lastOutAt', render: formatDateTime },
              { title: t('col.scheduledHours'), dataIndex: 'scheduledHours', align: 'right', render: qty },
              { title: t('col.workedHours'), dataIndex: 'workedHours', align: 'right', render: qty },
              { title: t('col.lateMin'), dataIndex: 'lateMin', align: 'right' },
              { title: t('col.earlyMin'), dataIndex: 'earlyMin', align: 'right' },
              { title: t('col.absentHours'), dataIndex: 'absentHours', align: 'right', render: qty },
              {
                title: t('col.exceptions'),
                key: 'exceptions',
                render: (_, r) => (
                  <Space size={[4, 4]} wrap>
                    {r.exceptions.map((x) => (
                      <Tag key={x.code} color={r.blocking ? 'red' : 'orange'}>
                        {x.label}
                      </Tag>
                    ))}
                  </Space>
                ),
              },
            ]}
          />
          <ReportPager page={query.data?.page} window={w} />
        </>
      )}
    </ReportFrame>
  );
}

// ------------------------------------------------------------------------------------- R-03 punch log

interface PunchForm extends ScopeValues {
  deviceId?: UUID;
  employmentId?: UUID;
  status?: string;
  range: [Dayjs, Dayjs];
}

/** R-03: raw punches with source and validation status (audited read). */
export function PunchLogReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const { t: ta } = useTranslation('att');
  const lang = useLang();
  const [form] = Form.useForm<PunchForm>();
  const canDevices = usePermission(P.DEVICE_VIEW);
  const devices = useDeviceOptions(canDevices);
  const w = useWindow(200);
  const [filters, setFilters] = useState<Omit<PunchLogParams, 'limit' | 'offset' | 'lang'> | null>(null);
  const params = filters ? { ...filters, lang, limit: w.limit, offset: w.offset } : null;
  const query = usePunchLog(params);

  return (
    <ReportFrame
      reportKey="punch-log"
      filters={
        <FilterForm<PunchForm>
          form={form}
          initialValues={{ range: [dayjs().subtract(6, 'day'), dayjs()] }}
          onApply={(v) => {
            w.reset();
            setFilters({
              legalEntityId: v.legalEntityId,
              orgUnitId: v.orgUnitId,
              deviceId: v.deviceId,
              employmentId: v.employmentId,
              status: v.status,
              from: toIso(v.range[0]) as string,
              to: toIso(v.range[1]) as string,
            });
          }}
        >
          <ScopeFields />
          {canDevices ? (
            <Form.Item name="deviceId" label={t('filter.device')}>
              <Select<UUID>
                allowClear
                showSearch
                optionFilterProp="label"
                style={{ width: 200 }}
                loading={devices.isLoading}
                options={(devices.data ?? []).map((d) => ({ value: d.id, label: d.name ? `${d.code} – ${d.name}` : d.code }))}
              />
            </Form.Item>
          ) : null}
          <Form.Item name="employmentId" label={ta('common.employee')}>
            <EmployeeSelect style={{ width: 240 }} />
          </Form.Item>
          <Form.Item name="status" label={ta('common.status')}>
            <Select<string>
              allowClear
              style={{ width: 220 }}
              options={PUNCH_STATUSES.map((s) => ({ value: s, label: t(`punchStatuses.${s}`) }))}
            />
          </Form.Item>
          <Form.Item name="range" label={t('filter.range')} rules={[{ required: true, message: t('filter.rangeRequired') }]}>
            <RangePicker format={DATE_FORMAT} />
          </Form.Item>
        </FilterForm>
      }
    >
      {!params ? (
        <Typography.Text type="secondary">{t('common.applyFilters')}</Typography.Text>
      ) : (
        <>
          <DataTable<PunchLine>
            size="small"
            rowKey={(r) => r.punch.id}
            data={query.data?.items}
            isLoading={query.isFetching}
            error={query.error}
            columns={[
              { title: t('col.punchAt'), key: 'at', render: (_, r) => formatDateTime(r.punch.punchAt) },
              { title: ta('common.workDate'), key: 'hint', render: (_, r) => formatDate(r.punch.workDateHint) },
              { title: t('col.device'), key: 'device', render: (_, r) => r.punch.deviceCode ?? r.punch.terminalCode ?? '—' },
              { title: t('col.enrolId'), key: 'enrol', render: (_, r) => r.punch.enrolId ?? '—' },
              { title: t('col.direction'), key: 'dir', render: (_, r) => r.punch.direction ?? '—' },
              { title: t('col.source'), key: 'source', render: (_, r) => r.sourceLabel || r.punch.source },
              {
                title: ta('common.status'),
                key: 'status',
                render: (_, r) => (
                  <StatusTag
                    status={r.punch.validationStatus === 'VALID' ? 'ACTIVE' : r.punch.validationStatus.startsWith('QUARANTINED') ? 'FAILED' : r.punch.validationStatus}
                    labels={{
                      ACTIVE: r.validationStatusLabel,
                      FAILED: r.validationStatusLabel,
                      [r.punch.validationStatus]: r.validationStatusLabel,
                    }}
                  />
                ),
              },
              { title: t('col.importRow'), key: 'row', render: (_, r) => r.punch.importRowNo ?? '—' },
              { title: t('col.receivedAt'), key: 'rec', render: (_, r) => formatDateTime(r.punch.receivedAt) },
            ]}
          />
          <ReportPager page={query.data} window={w} />
        </>
      )}
    </ReportFrame>
  );
}
