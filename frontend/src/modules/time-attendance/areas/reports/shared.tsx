import { useState, type ReactNode } from 'react';
import { Button, Card, Form, Select, Space, Tag, Typography, type FormInstance } from 'antd';
import { FileExcelOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { PageHeader, useFeedback } from '@/shared/components';
import { ISO_DATE, formatNumber, localizedName } from '@/shared/format';
import { LegalEntitySelect, OrgUnitSelect, legalEntityLabel, useLegalEntities, useOrgUnits } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import type { ExportKind, ReportPage } from './api';
import { useExportReport } from './queries';
import { REPORTS_NS } from './i18n';

/** One entry of the report catalogue (BRD 03 §8): its sub-path, the view permission its endpoint checks, export. */
export interface ReportDef {
  key: ReportKey;
  code: string;
  permission: string;
  exportKind?: ExportKind;
}

export type ReportKey =
  | 'timesheet-grid'
  | 'daily-exceptions'
  | 'punch-log'
  | 'overtime-summary'
  | 'overtime-cap-overrides'
  | 'leave-balances'
  | 'leave-taken'
  | 'si-leave-register'
  | 'period-close-status'
  | 'retro-adjustments';

export const REPORTS: ReportDef[] = [
  { key: 'timesheet-grid', code: 'R-01', permission: P.TIMESHEET_VIEW, exportKind: 'timesheet-grid' },
  { key: 'daily-exceptions', code: 'R-02', permission: P.TIMESHEET_VIEW },
  { key: 'punch-log', code: 'R-03', permission: P.PUNCH_VIEW },
  { key: 'overtime-summary', code: 'R-04', permission: P.OVERTIME_VIEW, exportKind: 'overtime-summary' },
  { key: 'overtime-cap-overrides', code: 'R-05', permission: P.OVERTIME_VIEW },
  { key: 'leave-balances', code: 'R-06', permission: P.LEAVE_BALANCE_VIEW, exportKind: 'leave-balances' },
  { key: 'leave-taken', code: 'R-07', permission: P.LEAVE_REQUEST_VIEW },
  { key: 'si-leave-register', code: 'R-07', permission: P.LEAVE_SI_DATA_VIEW },
  { key: 'period-close-status', code: 'R-08', permission: P.PERIOD_VIEW },
  { key: 'retro-adjustments', code: 'R-09', permission: P.PERIOD_VIEW },
];

export const REPORT_PERMISSIONS = [...new Set(REPORTS.map((r) => r.permission))];

export function reportDef(key: ReportKey): ReportDef {
  return REPORTS.find((r) => r.key === key) as ReportDef;
}

/** Server label language (`lang` parameter). */
export function useLang(): string {
  const { i18n } = useTranslation();
  return i18n.language.startsWith('en') ? 'en' : 'vi';
}

export const toIso = (d: Dayjs | null | undefined): string | undefined => (d ? d.format(ISO_DATE) : undefined);

/** Hours / days quantities (BigDecimal) for display; empty for null. */
export function qty(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : formatNumber(value);
}

/** Limit/offset window of a report, reset to the first page whenever the filters change. */
export function useWindow(initialLimit = 200) {
  const [limit, setLimit] = useState(initialLimit);
  const [offset, setOffset] = useState(0);
  return {
    limit,
    offset,
    setOffset,
    setLimit: (l: number) => {
      setLimit(l);
      setOffset(0);
    },
    reset: () => setOffset(0),
  };
}

/** Prev / next pager for `ReportPage` (no total on the wire, only `hasMore`). */
export function ReportPager({
  page,
  window: w,
}: {
  page: ReportPage<unknown> | undefined;
  window: ReturnType<typeof useWindow>;
}) {
  const { t } = useTranslation(REPORTS_NS);
  if (!page) return null;
  const first = page.items.length === 0 ? 0 : page.offset + 1;
  const last = page.offset + page.items.length;
  return (
    <Space style={{ marginTop: 12, display: 'flex', justifyContent: 'flex-end' }} wrap>
      <Typography.Text type="secondary">{t('pager.rows', { first, last })}</Typography.Text>
      <Button
        size="small"
        icon={<LeftOutlined />}
        aria-label={t('pager.previous')}
        disabled={page.offset === 0}
        onClick={() => w.setOffset(Math.max(0, page.offset - w.limit))}
      />
      <Button
        size="small"
        icon={<RightOutlined />}
        aria-label={t('pager.next')}
        disabled={!page.hasMore}
        onClick={() => w.setOffset(page.offset + w.limit)}
      />
      <Select<number>
        size="small"
        aria-label={t('pager.pageSize')}
        value={w.limit}
        onChange={w.setLimit}
        style={{ width: 120 }}
        options={[50, 200, 500, 1000].map((n) => ({ value: n, label: t('pager.perPage', { count: n }) }))}
      />
    </Space>
  );
}

/** Printed "Đơn vị" of an export: the org unit if chosen, else the legal entity. */
export function useUnitLabel(legalEntityId?: UUID, orgUnitId?: UUID): string | undefined {
  const { i18n } = useTranslation();
  const les = useLegalEntities();
  const ous = useOrgUnits();
  if (orgUnitId) {
    const ou = ous.data?.find((o) => o.id === orgUnitId);
    if (ou) return localizedName(ou, i18n.language) || ou.code;
  }
  if (legalEntityId) return legalEntityLabel(les.data?.find((l) => l.id === legalEntityId), i18n.language) || undefined;
  return undefined;
}

/** Excel export button of a report, shown only with `att.report.export`. Disabled until the filters are applied. */
export function ExportButton({
  kind,
  params,
  legalEntityId,
  orgUnitId,
}: {
  kind: ExportKind;
  params: Record<string, unknown> | null;
  legalEntityId?: UUID;
  orgUnitId?: UUID;
}) {
  const { t } = useTranslation(REPORTS_NS);
  const fb = useFeedback();
  const exporter = useExportReport();
  const unitLabel = useUnitLabel(legalEntityId, orgUnitId);
  return (
    <PermissionGate anyOf={P.REPORT_EXPORT}>
      <Button
        icon={<FileExcelOutlined />}
        disabled={!params}
        loading={exporter.isPending}
        onClick={() => {
          if (!params) return;
          const { limit: _l, offset: _o, ...filters } = params;
          exporter.mutate(
            { kind, params: { ...filters, unitLabel } },
            { onSuccess: () => fb.done(t('common.exported')), onError: fb.failed },
          );
        }}
      >
        {t('common.export')}
      </Button>
    </PermissionGate>
  );
}

/** Report page frame: breadcrumb to the catalogue, title + description, actions, filter card, then the body. */
export function ReportFrame({
  reportKey,
  extra,
  filters,
  children,
}: {
  reportKey: ReportKey;
  extra?: ReactNode;
  filters: ReactNode;
  children: ReactNode;
}) {
  const { t } = useTranslation(REPORTS_NS);
  const def = reportDef(reportKey);
  return (
    <div>
      <PageHeader
        breadcrumb={[{ label: t('index.title'), to: '/attendance/reports' }, { label: t(`catalog.${reportKey}.title`) }]}
        title={
          <Space>
            {t(`catalog.${reportKey}.title`)}
            <Tag>{def.code}</Tag>
          </Space>
        }
        subTitle={t(`catalog.${reportKey}.description`)}
        extra={extra}
      />
      <Card size="small" style={{ marginBottom: 16 }}>
        {filters}
      </Card>
      {children}
    </div>
  );
}

/** Inline filter form with Apply / Reset; `onApply` receives the raw form values. */
export function FilterForm<V extends object>({
  form,
  initialValues,
  onApply,
  children,
}: {
  form: FormInstance<V>;
  initialValues?: Partial<V>;
  onApply: (values: V) => void;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Form<V>
      form={form}
      layout="inline"
      initialValues={initialValues}
      onFinish={onApply}
      style={{ rowGap: 8 }}
    >
      {children}
      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit">
            {t('action.search')}
          </Button>
          <Button
            onClick={() => {
              form.resetFields();
            }}
          >
            {t('action.reset')}
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );
}

/** Default range of a report: the current month up to today. */
export function monthToDate(): [Dayjs, Dayjs] {
  return [dayjs().startOf('month'), dayjs()];
}

export interface ScopeValues {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
}

/** Legal entity + org unit (filtered by the entity) as two inline form items. */
export function ScopeFields({ orgUnit = true }: { orgUnit?: boolean }) {
  const { t } = useTranslation('att');
  const form = Form.useFormInstance<ScopeValues>();
  const legalEntityId = Form.useWatch('legalEntityId', form);
  return (
    <>
      <Form.Item name="legalEntityId" label={t('common.legalEntity')}>
        <LegalEntitySelect style={{ width: 240 }} onChange={() => form.setFieldValue('orgUnitId', undefined)} />
      </Form.Item>
      {orgUnit ? (
        <Form.Item name="orgUnitId" label={t('common.orgUnit')}>
          <OrgUnitSelect legalEntityId={legalEntityId} style={{ width: 240 }} />
        </Form.Item>
      ) : null}
    </>
  );
}


/** Org unit code – name from the cached Core lookup (falls back to the short id). */
export function OrgUnitLabel({ id }: { id: UUID | null | undefined }) {
  const { i18n } = useTranslation();
  const ous = useOrgUnits();
  if (!id) return <>—</>;
  const ou = ous.data?.find((o) => o.id === id);
  return <>{ou ? `${ou.code} – ${localizedName(ou, i18n.language)}` : id.slice(0, 8)}</>;
}
