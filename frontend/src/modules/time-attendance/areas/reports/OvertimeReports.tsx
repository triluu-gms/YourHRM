import { useState } from 'react';
import { DatePicker, Form, Progress, Select, Space, Tag, Tooltip, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, StatusTag } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime } from '@/shared/format';
import { EmployeeSelect } from '../../common';
import {
  OT_REQUEST_STATUSES,
  type CapUsage,
  type OverrideLine,
  type OverrideParams,
  type OvertimeRow,
  type OvertimeSummaryParams,
} from './api';
import { useOvertimeCapOverrides, useOvertimeSummary } from './queries';
import {
  ExportButton,
  FilterForm,
  ReportFrame,
  ReportPager,
  ScopeFields,
  qty,
  toIso,
  useLang,
  useWindow,
  type ScopeValues,
} from './shared';
import { REPORTS_NS } from './i18n';
import './i18n';

const { RangePicker } = DatePicker;

/** Used / cap hours with a bar (red once the cap is breached). */
export function CapCell({ usage }: { usage: CapUsage | null | undefined }) {
  const { t } = useTranslation(REPORTS_NS);
  if (!usage) return <>—</>;
  const pct = usage.usedPercent ?? (usage.capHours > 0 ? (usage.usedHours / usage.capHours) * 100 : 0);
  return (
    <Tooltip
      title={t('ot.capTooltip', {
        opening: qty(usage.openingHours),
        approved: qty(usage.approvedHours),
        pending: qty(usage.pendingHours),
        paid: qty(usage.paidHours),
        override: qty(usage.overrideHours),
      })}
    >
      <div style={{ minWidth: 140 }}>
        <Typography.Text type={usage.breached ? 'danger' : undefined}>
          {qty(usage.usedHours)} / {qty(usage.capHours)}
        </Typography.Text>
        <Progress
          size="small"
          percent={Math.min(100, Math.round(pct))}
          status={usage.breached ? 'exception' : pct >= 90 ? 'active' : 'normal'}
          showInfo={false}
        />
      </div>
    </Tooltip>
  );
}

interface SummaryForm extends ScopeValues {
  month: Dayjs;
}

/** R-04: overtime per employee for a month and year to date against the caps, hours per OT category. */
export function OvertimeSummaryReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const lang = useLang();
  const [form] = Form.useForm<SummaryForm>();
  const w = useWindow(200);
  const [filters, setFilters] = useState<Omit<OvertimeSummaryParams, 'limit' | 'offset' | 'lang'> | null>(null);
  const params = filters ? { ...filters, lang, limit: w.limit, offset: w.offset } : null;
  const query = useOvertimeSummary(params);
  const categories = query.data?.categories ?? [];

  return (
    <ReportFrame
      reportKey="overtime-summary"
      extra={
        <ExportButton
          kind="overtime-summary"
          params={params ? { ...params } : null}
          legalEntityId={filters?.legalEntityId}
          orgUnitId={filters?.orgUnitId}
        />
      }
      filters={
        <FilterForm<SummaryForm>
          form={form}
          initialValues={{ month: dayjs() }}
          onApply={(v) => {
            w.reset();
            setFilters({ legalEntityId: v.legalEntityId, orgUnitId: v.orgUnitId, month: v.month.format('YYYY-MM') });
          }}
        >
          <ScopeFields />
          <Form.Item name="month" label={t('filter.month')} rules={[{ required: true, message: t('filter.monthRequired') }]}>
            <DatePicker picker="month" format="MM/YYYY" />
          </Form.Item>
        </FilterForm>
      }
    >
      {!params ? (
        <Typography.Text type="secondary">{t('common.applyFilters')}</Typography.Text>
      ) : (
        <>
          <DataTable<OvertimeRow>
            size="small"
            rowKey="employmentId"
            data={query.data?.page.items}
            isLoading={query.isFetching}
            error={query.error}
            columns={[
              { title: t('col.employeeNo'), dataIndex: 'employeeNo', fixed: 'left' },
              { title: t('col.fullName'), dataIndex: 'fullName' },
              { title: t('ot.month'), key: 'month', render: (_, r) => <CapCell usage={r.month} /> },
              { title: t('ot.year'), key: 'year', render: (_, r) => <CapCell usage={r.year} /> },
              {
                title: t('ot.regime'),
                key: 'regime',
                render: (_, r) => (
                  <Space direction="vertical" size={0}>
                    <span>{r.regimeLabel ?? r.year.regimeCode ?? '—'}</span>
                    {r.regimeNoticeRef ? (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {r.regimeNoticeRef}
                      </Typography.Text>
                    ) : null}
                  </Space>
                ),
              },
              ...categories.map((c) => ({
                key: `m-${c.code}`,
                title: <Tooltip title={t('ot.monthYtd')}>{c.label}</Tooltip>,
                align: 'right' as const,
                render: (_: unknown, r: OvertimeRow) =>
                  `${qty(r.monthByCategory[c.code] ?? 0)} / ${qty(r.yearToDateByCategory[c.code] ?? 0)}`,
              })),
            ]}
          />
          <ReportPager page={query.data?.page} window={w} />
        </>
      )}
    </ReportFrame>
  );
}

interface OverrideForm extends ScopeValues {
  employmentId?: UUID;
  range: [Dayjs, Dayjs];
  status?: string;
}

/** R-05: overtime above the cap with an authorised override: who, why, cap usage. */
export function OvertimeCapOverridesReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const { t: ta } = useTranslation('att');
  const lang = useLang();
  const [form] = Form.useForm<OverrideForm>();
  const w = useWindow(200);
  const [filters, setFilters] = useState<Omit<OverrideParams, 'limit' | 'offset' | 'lang'> | null>(null);
  const params = filters ? { ...filters, lang, limit: w.limit, offset: w.offset } : null;
  const query = useOvertimeCapOverrides(params);

  return (
    <ReportFrame
      reportKey="overtime-cap-overrides"
      filters={
        <FilterForm<OverrideForm>
          form={form}
          initialValues={{ range: [dayjs().startOf('year'), dayjs()] }}
          onApply={(v) => {
            w.reset();
            setFilters({
              legalEntityId: v.legalEntityId,
              orgUnitId: v.orgUnitId,
              employmentId: v.employmentId,
              status: v.status,
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
          <Form.Item name="status" label={ta('common.status')}>
            <Select<string>
              allowClear
              style={{ width: 160 }}
              options={OT_REQUEST_STATUSES.map((s) => ({ value: s, label: t(`requestStatuses.${s}`) }))}
            />
          </Form.Item>
        </FilterForm>
      }
    >
      {!params ? (
        <Typography.Text type="secondary">{t('common.applyFilters')}</Typography.Text>
      ) : (
        <>
          <DataTable<OverrideLine>
            size="small"
            rowKey={(r) => r.request.requestId}
            data={query.data?.items}
            isLoading={query.isFetching}
            error={query.error}
            columns={[
              { title: ta('common.workDate'), key: 'date', render: (_, r) => formatDate(r.request.workDate) },
              { title: t('col.employment'), key: 'emp', render: (_, r) => <code>{r.request.employmentId.slice(0, 8)}</code> },
              { title: t('ot.plannedHours'), key: 'planned', align: 'right', render: (_, r) => qty(r.request.plannedMinutes / 60) },
              {
                title: ta('common.status'),
                key: 'status',
                render: (_, r) => <StatusTag status={r.request.status} labels={{ [r.request.status]: r.statusLabel }} />,
              },
              { title: t('ot.reason'), key: 'reason', render: (_, r) => r.request.reason ?? '—' },
              { title: t('ot.overrideReason'), key: 'oreason', render: (_, r) => r.request.overrideReason ?? '—' },
              {
                title: t('ot.protected'),
                key: 'prot',
                render: (_, r) => (r.request.protectedWorker ? <Tag color="purple">{t('ot.protectedWorker')}</Tag> : null),
              },
              { title: t('ot.consent'), key: 'consent', render: (_, r) => r.request.consentMethod ?? '—' },
              { title: t('ot.month'), key: 'month', render: (_, r) => <CapCell usage={r.month} /> },
              { title: t('ot.year'), key: 'year', render: (_, r) => <CapCell usage={r.year} /> },
              { title: t('ot.decidedAt'), key: 'dec', render: (_, r) => formatDateTime(r.request.decidedAt) },
            ]}
          />
          <ReportPager page={query.data} window={w} />
        </>
      )}
    </ReportFrame>
  );
}
