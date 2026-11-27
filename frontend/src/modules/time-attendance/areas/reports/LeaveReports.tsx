import { useState } from 'react';
import { Alert, DatePicker, Form, Select, Tag, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { usePermission } from '@/shared/auth';
import { DataTable } from '@/shared/components';
import { DATE_FORMAT, formatDate, localizedName } from '@/shared/format';
import { EmployeeSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import type { BalanceParams, BalanceRow, LeaveRangeParams, SiLine, TakenLine } from './api';
import { useLeaveBalancesReport, useLeaveTaken, useLeaveTypeOptions, useSiLeaveRegister } from './queries';
import {
  ExportButton,
  FilterForm,
  ReportFrame,
  ReportPager,
  OrgUnitLabel,
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

/** Leave type filter; hidden without `att.leave_type.view` (the backend then simply gets no type). */
function LeaveTypeField() {
  const { t, i18n } = useTranslation(REPORTS_NS);
  const allowed = usePermission(P.LEAVE_TYPE_VIEW);
  const types = useLeaveTypeOptions(allowed);
  if (!allowed) return null;
  return (
    <Form.Item name="leaveTypeId" label={t('filter.leaveType')}>
      <Select<UUID>
        allowClear
        showSearch
        optionFilterProp="label"
        style={{ width: 220 }}
        loading={types.isLoading}
        options={(types.data ?? []).map((lt) => ({
          value: lt.id,
          label: `${lt.data.code} – ${localizedName(lt.data, i18n.language)}`,
        }))}
      />
    </Form.Item>
  );
}

const yesNo = (t: (k: string) => string, v: boolean | null | undefined) =>
  v === null || v === undefined ? '—' : v ? t('common.yes') : t('common.no');

// ---------------------------------------------------------------------------------- R-06 balances

interface BalanceForm extends ScopeValues {
  leaveTypeId?: UUID;
  employmentId?: UUID;
  asOf?: Dayjs | null;
}

/** R-06: leave balances as of a date from the ledger (movements of the leave year). */
export function LeaveBalancesReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const { t: ta } = useTranslation('att');
  const lang = useLang();
  const [form] = Form.useForm<BalanceForm>();
  const w = useWindow(200);
  const [filters, setFilters] = useState<Omit<BalanceParams, 'limit' | 'offset' | 'lang'> | null>(null);
  const params = filters ? { ...filters, lang, limit: w.limit, offset: w.offset } : null;
  const query = useLeaveBalancesReport(params);
  // The export has no employment filter.
  const exportParams = params ? { ...params, employmentId: undefined } : null;

  return (
    <ReportFrame
      reportKey="leave-balances"
      extra={
        <ExportButton
          kind="leave-balances"
          params={exportParams}
          legalEntityId={filters?.legalEntityId}
          orgUnitId={filters?.orgUnitId}
        />
      }
      filters={
        <FilterForm<BalanceForm>
          form={form}
          initialValues={{ asOf: dayjs() }}
          onApply={(v) => {
            w.reset();
            setFilters({
              legalEntityId: v.legalEntityId,
              orgUnitId: v.orgUnitId,
              leaveTypeId: v.leaveTypeId,
              employmentId: v.employmentId,
              asOf: toIso(v.asOf),
            });
          }}
        >
          <ScopeFields />
          <LeaveTypeField />
          <Form.Item name="employmentId" label={ta('common.employee')}>
            <EmployeeSelect style={{ width: 240 }} />
          </Form.Item>
          <Form.Item name="asOf" label={t('filter.asOf')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
        </FilterForm>
      }
    >
      {query.data ? (
        <Typography.Paragraph type="secondary">{t('leave.asOf', { date: formatDate(query.data.asOf) })}</Typography.Paragraph>
      ) : null}
      {!params ? (
        <Typography.Text type="secondary">{t('common.applyFilters')}</Typography.Text>
      ) : (
        <>
          <DataTable<BalanceRow>
            size="small"
            rowKey={(r) => `${r.employmentId}:${r.leaveTypeId}:${r.leaveYear}`}
            data={query.data?.page.items}
            isLoading={query.isFetching}
            error={query.error}
            columns={[
              { title: t('col.employeeNo'), dataIndex: 'employeeNo', fixed: 'left' },
              { title: t('col.fullName'), dataIndex: 'fullName' },
              { title: t('leave.type'), key: 'type', render: (_, r) => `${r.leaveTypeCode} – ${r.leaveTypeName}` },
              { title: t('leave.year'), dataIndex: 'leaveYear' },
              ...(['opening', 'carriedIn', 'accrued', 'taken', 'adjusted', 'carriedOut', 'expired', 'encashed'] as const).map(
                (k) => ({
                  key: k,
                  title: t(`leave.movements.${k}`),
                  align: 'right' as const,
                  render: (_: unknown, r: BalanceRow) => qty(r.balance[k]),
                }),
              ),
              {
                key: 'closing',
                title: t('leave.movements.closing'),
                align: 'right',
                render: (_, r) => <b>{qty(r.balance.closing)}</b>,
              },
            ]}
          />
          <ReportPager page={query.data?.page} window={w} />
        </>
      )}
    </ReportFrame>
  );
}

// ---------------------------------------------------------------------------------- R-07 taken / SI

interface RangeForm extends ScopeValues {
  leaveTypeId?: UUID;
  range: [Dayjs, Dayjs];
}

function useRangeReport() {
  const lang = useLang();
  const [form] = Form.useForm<RangeForm>();
  const w = useWindow(200);
  const [filters, setFilters] = useState<Omit<LeaveRangeParams, 'limit' | 'offset' | 'lang'> | null>(null);
  const params = filters ? { ...filters, lang, limit: w.limit, offset: w.offset } : null;
  const apply = (v: RangeForm) => {
    w.reset();
    setFilters({
      legalEntityId: v.legalEntityId,
      orgUnitId: v.orgUnitId,
      leaveTypeId: v.leaveTypeId,
      from: toIso(v.range[0]) as string,
      to: toIso(v.range[1]) as string,
    });
  };
  return { form, w, params, apply };
}

function RangeFilters({ form, apply }: Pick<ReturnType<typeof useRangeReport>, 'form' | 'apply'>) {
  const { t } = useTranslation(REPORTS_NS);
  return (
    <FilterForm<RangeForm> form={form} initialValues={{ range: [dayjs().startOf('year'), dayjs()] }} onApply={apply}>
      <ScopeFields />
      <LeaveTypeField />
      <Form.Item name="range" label={t('filter.range')} rules={[{ required: true, message: t('filter.rangeRequired') }]}>
        <RangePicker format={DATE_FORMAT} />
      </Form.Item>
    </FilterForm>
  );
}

/** R-07: approved leave per month × org unit × type. */
export function LeaveTakenReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const r = useRangeReport();
  const query = useLeaveTaken(r.params);
  return (
    <ReportFrame reportKey="leave-taken" filters={<RangeFilters form={r.form} apply={r.apply} />}>
      {!r.params ? (
        <Typography.Text type="secondary">{t('common.applyFilters')}</Typography.Text>
      ) : (
        <>
          <DataTable<TakenLine>
            size="small"
            rowKey={(x) => `${x.month}:${x.orgUnitId ?? ''}:${x.leaveTypeId}`}
            data={query.data?.items}
            isLoading={query.isFetching}
            error={query.error}
            columns={[
              { title: t('filter.month'), dataIndex: 'month', render: (v: string) => dayjs(v).format('MM/YYYY') },
              { title: t('col.orgUnit'), dataIndex: 'orgUnitId', render: (v: string | null) => (v ? <OrgUnitLabel id={v} /> : '—') },
              { title: t('leave.type'), key: 'type', render: (_, x) => `${x.leaveTypeCode} – ${x.leaveTypeName}` },
              { title: t('leave.category'), dataIndex: 'leaveCategory' },
              { title: t('leave.paidBy'), dataIndex: 'paidBy' },
              { title: t('leave.requests'), dataIndex: 'requests', align: 'right' },
              { title: t('leave.employees'), dataIndex: 'employees', align: 'right' },
              { title: t('leave.days'), dataIndex: 'days', align: 'right', render: qty },
              { title: t('leave.hours'), dataIndex: 'hours', align: 'right', render: qty },
            ]}
          />
          <ReportPager page={query.data} window={r.w} />
        </>
      )}
    </ReportFrame>
  );
}

/** R-07: register of SI-paid leave with masked claim data (audited). */
export function SiLeaveRegisterReportPage() {
  const { t } = useTranslation(REPORTS_NS);
  const { t: tc } = useTranslation();
  const r = useRangeReport();
  const query = useSiLeaveRegister(r.params);
  return (
    <ReportFrame reportKey="si-leave-register" filters={<RangeFilters form={r.form} apply={r.apply} />}>
      <Alert type="info" showIcon message={t('leave.siMaskedNotice')} style={{ marginBottom: 12 }} />
      {!r.params ? (
        <Typography.Text type="secondary">{t('common.applyFilters')}</Typography.Text>
      ) : (
        <>
          <DataTable<SiLine>
            size="small"
            rowKey="requestId"
            data={query.data?.items}
            isLoading={query.isFetching}
            error={query.error}
            columns={[
              { title: t('col.employeeNo'), dataIndex: 'employeeNo', fixed: 'left' },
              { title: t('col.fullName'), dataIndex: 'fullName' },
              { title: t('leave.type'), key: 'type', render: (_, x) => `${x.leaveTypeCode} – ${x.leaveTypeName}` },
              { title: t('leave.period'), key: 'period', render: (_, x) => `${formatDate(x.startDate)} – ${formatDate(x.endDate)}` },
              { title: t('leave.actualReturn'), dataIndex: 'actualReturnDate', render: formatDate },
              { title: t('leave.days'), dataIndex: 'durationDays', align: 'right', render: qty },
              { title: t('leave.certificateType'), dataIndex: 'certificateType' },
              { title: t('leave.certificateNo'), dataIndex: 'certificateNoMasked', render: (v: string | null) => (v ? <code>{v}</code> : '—') },
              { title: t('leave.certificateDate'), dataIndex: 'certificateDate', render: formatDate },
              { title: t('leave.treatmentType'), dataIndex: 'treatmentType' },
              { title: t('leave.longTermIllness'), dataIndex: 'longTermIllness', render: (v: boolean | null) => yesNo(tc, v) },
              { title: t('leave.childBirthDate'), dataIndex: 'childBirthDate', render: formatDate },
              { title: t('leave.childId'), dataIndex: 'childIdMasked', render: (v: string | null) => (v ? <code>{v}</code> : '—') },
              { title: t('leave.confinementDate'), dataIndex: 'confinementDate', render: formatDate },
              { title: t('leave.childrenBorn'), dataIndex: 'childrenBornCount', align: 'right' },
              { title: t('leave.caesarean'), dataIndex: 'caesarean', render: (v: boolean | null) => yesNo(tc, v) },
              {
                title: t('leave.claimData'),
                dataIndex: 'claimDataRecorded',
                render: (v: boolean) => <Tag color={v ? 'green' : 'orange'}>{v ? t('leave.claimRecorded') : t('leave.claimMissing')}</Tag>,
              },
            ]}
          />
          <ReportPager page={query.data} window={r.w} />
        </>
      )}
    </ReportFrame>
  );
}
