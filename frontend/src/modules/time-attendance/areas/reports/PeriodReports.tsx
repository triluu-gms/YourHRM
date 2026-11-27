import { useState } from 'react';
import { Checkbox, DatePicker, Form, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, StatusTag } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime } from '@/shared/format';
import { EmployeeSelect, LegalEntitySelect, legalEntityLabel, useLegalEntities } from '../../common';
import { PERIOD_STATUSES, type CloseStatus, type CloseStatusParams, type RetroParams, type RetroRow, type RetroTotal } from './api';
import { usePeriodCloseStatus, usePeriodOptions, useRetroAdjustments } from './queries';
import { FilterForm, OrgUnitLabel, ReportFrame, ReportPager, qty, toIso, useLang, useWindow } from './shared';
import { REPORTS_NS } from './i18n';
import './i18n';

const { RangePicker } = DatePicker;

const range = (a: string | null | undefined, b: string | null | undefined) => (a ? `${formatDate(a)} – ${formatDate(b)}` : '—');

function LegalEntityName({ id }: { id: UUID | null | undefined }) {
  const { i18n } = useTranslation();
  const les = useLegalEntities();
  if (!id) return <>—</>;
  return <>{legalEntityLabel(les.data?.find((l) => l.id === id), i18n.language) || id.slice(0, 8)}</>;
}

// ---------------------------------------------------------------------------------- R-08 close status

interface CloseForm {
  legalEntityId?: UUID;
  status?: string;
  range?: [Dayjs, Dayjs] | null;
}

/** R-08: close status per legal entity × pay group with the outstanding checklist items. */
export function PeriodCloseStatusReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const { t: ta } = useTranslation('att');
  const lang = useLang();
  const [form] = Form.useForm<CloseForm>();
  const w = useWindow(200);
  // Runs at once: every filter is optional.
  const [filters, setFilters] = useState<Omit<CloseStatusParams, 'limit' | 'offset' | 'lang'>>({});
  const query = usePeriodCloseStatus({ ...filters, lang, limit: w.limit, offset: w.offset });

  return (
    <ReportFrame
      reportKey="period-close-status"
      filters={
        <FilterForm<CloseForm>
          form={form}
          onApply={(v) => {
            w.reset();
            setFilters({ legalEntityId: v.legalEntityId, status: v.status, from: toIso(v.range?.[0]), to: toIso(v.range?.[1]) });
          }}
        >
          <Form.Item name="legalEntityId" label={ta('common.legalEntity')}>
            <LegalEntitySelect style={{ width: 240 }} />
          </Form.Item>
          <Form.Item name="status" label={ta('common.status')}>
            <Select<string>
              allowClear
              style={{ width: 160 }}
              options={PERIOD_STATUSES.map((s) => ({ value: s, label: t(`periodStatuses.${s}`) }))}
            />
          </Form.Item>
          <Form.Item name="range" label={t('filter.range')}>
            <RangePicker format={DATE_FORMAT} />
          </Form.Item>
        </FilterForm>
      }
    >
      <DataTable<CloseStatus>
        size="small"
        rowKey={(r) => r.period.id}
        data={query.data?.items}
        isLoading={query.isFetching}
        error={query.error}
        columns={[
          { title: ta('common.legalEntity'), key: 'le', render: (_, r) => <LegalEntityName id={r.period.legalEntityId} /> },
          { title: t('period.payGroup'), key: 'pg', render: (_, r) => <code>{r.period.payGroupId.slice(0, 8)}</code> },
          { title: ta('common.period'), key: 'period', render: (_, r) => range(r.period.periodStart, r.period.periodEnd) },
          {
            title: ta('common.status'),
            key: 'status',
            render: (_, r) => (
              <Space size={4}>
                <StatusTag status={r.period.status} labels={{ [r.period.status]: r.statusLabel }} />
                {r.period.activeHold ? <Tag color="purple">{t('period.held')}</Tag> : null}
              </Space>
            ),
          },
          {
            title: t('period.deadline'),
            key: 'deadline',
            render: (_, r) => (
              <Typography.Text type={r.overdue ? 'danger' : undefined}>
                {formatDate(r.period.closeDeadline) || '—'}
                {r.overdue ? ` (${t('period.overdue')})` : ''}
              </Typography.Text>
            ),
          },
          {
            title: t('period.version'),
            key: 'version',
            render: (_, r) =>
              r.period.lockedVersion != null ? t('period.versionLocked', { current: r.period.currentVersion, locked: r.period.lockedVersion }) : `v${r.period.currentVersion}`,
          },
          {
            title: t('period.canClose'),
            dataIndex: 'canClose',
            render: (v: boolean | null) =>
              v === null || v === undefined ? '—' : <Tag color={v ? 'green' : 'red'}>{v ? t('period.ready') : t('period.notReady')}</Tag>,
          },
          {
            title: t('period.outstanding'),
            key: 'outstanding',
            render: (_, r) =>
              r.outstanding.length === 0 ? (
                '—'
              ) : (
                <Space size={[4, 4]} wrap>
                  {r.outstanding.map((o) => (
                    <Tooltip key={o.code} title={o.waivable ? t('period.waivable') : t('period.notWaivable')}>
                      <Tag color={o.waivable ? 'orange' : 'red'}>
                        {o.label}: {o.count}
                      </Tag>
                    </Tooltip>
                  ))}
                </Space>
              ),
          },
          { title: t('period.pendingRetro'), dataIndex: 'pendingRetro', align: 'right' },
        ]}
      />
      <ReportPager page={query.data} window={w} />
    </ReportFrame>
  );
}

// ---------------------------------------------------------------------------------- R-09 retro

interface RetroForm {
  legalEntityId?: UUID;
  originalPeriodId?: UUID;
  employmentId?: UUID;
  range?: [Dayjs, Dayjs] | null;
  includeWithdrawn?: boolean;
}

/** R-09: retro adjustment lines and totals by original period. */
export function RetroAdjustmentsReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const { t: ta } = useTranslation('att');
  const [form] = Form.useForm<RetroForm>();
  const legalEntityId = Form.useWatch('legalEntityId', form);
  const periods = usePeriodOptions(legalEntityId, true);
  const w = useWindow(200);
  const [filters, setFilters] = useState<Omit<RetroParams, 'limit' | 'offset'>>({});
  const query = useRetroAdjustments({ ...filters, limit: w.limit, offset: w.offset });

  return (
    <ReportFrame
      reportKey="retro-adjustments"
      filters={
        <FilterForm<RetroForm>
          form={form}
          onApply={(v) => {
            w.reset();
            setFilters({
              legalEntityId: v.legalEntityId,
              originalPeriodId: v.originalPeriodId,
              employmentId: v.employmentId,
              from: toIso(v.range?.[0]),
              to: toIso(v.range?.[1]),
              includeWithdrawn: v.includeWithdrawn || undefined,
            });
          }}
        >
          <Form.Item name="legalEntityId" label={ta('common.legalEntity')}>
            <LegalEntitySelect style={{ width: 240 }} onChange={() => form.setFieldValue('originalPeriodId', undefined)} />
          </Form.Item>
          <Form.Item name="originalPeriodId" label={t('retro.originalPeriod')}>
            <Select<UUID>
              allowClear
              style={{ width: 220 }}
              loading={periods.isLoading}
              options={(periods.data ?? []).map((p) => ({ value: p.id, label: range(p.periodStart, p.periodEnd) }))}
            />
          </Form.Item>
          <Form.Item name="employmentId" label={ta('common.employee')}>
            <EmployeeSelect style={{ width: 240 }} />
          </Form.Item>
          <Form.Item name="range" label={t('filter.range')}>
            <RangePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="includeWithdrawn" valuePropName="checked">
            <Checkbox>{t('retro.includeWithdrawn')}</Checkbox>
          </Form.Item>
        </FilterForm>
      }
    >
      <Typography.Title level={5}>{t('retro.totals')}</Typography.Title>
      <Table<RetroTotal>
        size="small"
        bordered
        pagination={false}
        loading={query.isFetching}
        rowKey={(x) => `${x.originalPeriodId ?? ''}:${x.categoryCode}:${x.unit}`}
        dataSource={query.data?.totals ?? []}
        locale={{ emptyText: t('common.noRows') }}
        style={{ marginBottom: 16 }}
        columns={[
          { title: t('retro.originalPeriod'), key: 'p', render: (_, x) => range(x.originalPeriodStart, x.originalPeriodEnd) },
          { title: ta('common.legalEntity'), key: 'le', render: (_, x) => <LegalEntityName id={x.legalEntityId} /> },
          { title: t('retro.category'), dataIndex: 'categoryCode', render: (v: string) => <code>{v}</code> },
          { title: t('retro.quantity'), key: 'q', align: 'right', render: (_, x) => `${qty(x.quantity)} ${x.unit}` },
          { title: t('retro.lines'), dataIndex: 'lines', align: 'right' },
          { title: t('retro.employments'), dataIndex: 'employments', align: 'right' },
        ]}
      />
      <DataTable<RetroRow>
        size="small"
        rowKey="lineId"
        data={query.data?.page.items}
        isLoading={query.isFetching}
        error={query.error}
        columns={[
          { title: t('col.employment'), dataIndex: 'employmentId', render: (v: string) => <code>{v.slice(0, 8)}</code> },
          { title: t('col.orgUnit'), dataIndex: 'orgUnitId', render: (v: string | null) => <OrgUnitLabel id={v} /> },
          { title: t('retro.category'), dataIndex: 'categoryCode', render: (v: string) => <code>{v}</code> },
          { title: t('retro.quantity'), key: 'q', align: 'right', render: (_, x) => `${qty(x.quantity)} ${x.unit}` },
          { title: t('retro.segment'), key: 'seg', render: (_, x) => range(x.segmentFrom, x.segmentTo) },
          { title: t('retro.originalPeriod'), key: 'op', render: (_, x) => range(x.originalPeriodStart, x.originalPeriodEnd) },
          {
            title: t('retro.originalVersion'),
            dataIndex: 'originalResultVersion',
            render: (v: number | null) => (v != null ? `v${v}` : '—'),
          },
          { title: t('retro.reason'), dataIndex: 'retroReason' },
          { title: t('retro.carryingPeriod'), key: 'cp', render: (_, x) => range(x.carryingPeriodStart, x.carryingPeriodEnd) },
          {
            title: t('retro.result'),
            key: 'res',
            render: (_, x) => (
              <Space size={4}>
                <span>v{x.resultVersion}</span>
                <StatusTag status={x.resultStatus} />
              </Space>
            ),
          },
          { title: t('retro.createdAt'), dataIndex: 'createdAt', render: formatDateTime },
        ]}
      />
      <ReportPager page={query.data?.page} window={w} />
    </ReportFrame>
  );
}
