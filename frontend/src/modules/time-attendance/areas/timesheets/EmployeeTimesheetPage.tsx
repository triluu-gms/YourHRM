import { useState } from 'react';
import { Alert, Button, Card, DatePicker, Drawer, Empty, Space, Tabs, Tag, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, EyeOutlined, FormOutlined, PlusOutlined, SyncOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { ISODate, UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { ConfirmButton, DataTable, DescriptionList, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime, ISO_DATE } from '@/shared/format';
import { ATT_NS, EmployeeSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import type { CorrectionRow, DayRow, MenstrualBreakDeclaration, PunchRow } from './api';
import {
  useDayDetail,
  useDeclareMenstrualBreak,
  useEmploymentDays,
  useEmploymentPunches,
  useMenstrualBreaks,
  useWithdrawMenstrualBreak,
} from './queries';
import {
  CodeTags,
  CorrectionDrawer,
  DayStatusTag,
  EditDaysDrawer,
  ExceptionTags,
  fmtNum,
  formatTime,
  NS,
  RecomputeModal,
} from './components';

export interface EmployeeTimesheetSearch {
  employmentId?: UUID;
  from?: ISODate;
  to?: ISODate;
}

const defaultFrom = () => dayjs().startOf('month').format(ISO_DATE);
const defaultTo = () => dayjs().format(ISO_DATE);
const localTime = (ts: string) => dayjs(ts).format(`${DATE_FORMAT} HH:mm:ss`);

/**
 * One employee's attendance (BR-ATT-030..039): the daily timesheet of a range with the day drawer (punches,
 * corrections, admin edit, correction request), raw punches (audited) and menstrual-break declarations (sensitive,
 * shown only with `att.menstrual_break.view`). The employee and range live in the URL.
 */
export function EmployeeTimesheetPage({ search, onChange }: { search: EmployeeTimesheetSearch; onChange: (next: EmployeeTimesheetSearch) => void }) {
  const { t } = useTranslation(NS);
  const { t: ta } = useTranslation(ATT_NS);
  const employmentId = search.employmentId;
  const from = search.from ?? defaultFrom();
  const to = search.to ?? defaultTo();
  const [recomputing, setRecomputing] = useState(false);
  const canPunches = usePermission(P.PUNCH_VIEW);
  const canMenstrual = usePermission(P.MENSTRUAL_BREAK_VIEW);

  return (
    <div>
      <PageHeader
        title={t('employee.title')}
        subTitle={t('employee.subtitle')}
        extra={
          employmentId ? (
            <PermissionGate anyOf={P.TIMESHEET_UPDATE}>
              <Button icon={<SyncOutlined />} onClick={() => setRecomputing(true)}>
                {t('recompute.title')}
              </Button>
            </PermissionGate>
          ) : null
        }
      >
        <Space wrap>
          <EmployeeSelect
            aria-label={ta('common.employee')}
            style={{ width: 320 }}
            value={employmentId}
            onChange={(id) => onChange({ ...search, employmentId: id ?? undefined })}
          />
          <DatePicker.RangePicker
            aria-label={t('field.range')}
            format={DATE_FORMAT}
            allowClear={false}
            value={[dayjs(from), dayjs(to)]}
            onChange={(v) => v?.[0] && v[1] && onChange({ ...search, from: v[0].format(ISO_DATE), to: v[1].format(ISO_DATE) })}
          />
        </Space>
      </PageHeader>

      {!employmentId ? (
        <Empty description={ta('common.selectEmployeeFirst')} />
      ) : (
        <Tabs
          items={[
            { key: 'days', label: t('employee.tabDays'), children: <DaysTab employmentId={employmentId} from={from} to={to} /> },
            ...(canPunches
              ? [{ key: 'punches', label: t('employee.tabPunches'), children: <PunchesTab employmentId={employmentId} from={from} to={to} /> }]
              : []),
            ...(canMenstrual
              ? [{ key: 'menstrual', label: t('employee.tabMenstrual'), children: <MenstrualTab employmentId={employmentId} month={dayjs(to).format('YYYY-MM')} /> }]
              : []),
          ]}
        />
      )}

      {recomputing && employmentId ? (
        <RecomputeModal from={from} to={to} employmentIds={[employmentId]} allowLegalEntity={false} onClose={() => setRecomputing(false)} />
      ) : null}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ days

function DaysTab({ employmentId, from, to }: { employmentId: UUID; from: ISODate; to: ISODate }) {
  const { t } = useTranslation(NS);
  const q = useEmploymentDays(employmentId, from, to);
  const [day, setDay] = useState<ISODate | null>(null);
  const days = q.data ?? [];
  const totalDays = days.reduce((s, d) => s + (d.dayValue ?? 0), 0);
  const totalHours = days.reduce((s, d) => s + (d.workedHours ?? 0), 0);

  return (
    <>
      {days.length ? (
        <Typography.Paragraph type="secondary">
          {t('employee.totals', { days: Math.round(totalDays * 100) / 100, hours: Math.round(totalHours * 100) / 100 })}
        </Typography.Paragraph>
      ) : null}
      <DataTable<DayRow>
        rowKey="workDate"
        data={days}
        isLoading={q.isLoading}
        error={q.error}
        locale={{ emptyText: t('grid.empty') }}
        columns={[
          { title: t('field.workDate'), dataIndex: 'workDate', render: (d: string) => formatDate(d) },
          { title: t('field.dayType'), dataIndex: 'dayType', render: (d: string) => t(`dayType.${d}`, { defaultValue: d }) },
          { title: t('field.dayStatus'), dataIndex: 'dayStatus', render: (s: string) => <DayStatusTag status={s} /> },
          { title: t('field.codes'), dataIndex: 'codes', render: (c: DayRow['codes']) => <CodeTags codes={c} /> },
          { title: t('field.firstIn'), dataIndex: 'firstInAt', render: formatTime },
          { title: t('field.lastOut'), dataIndex: 'lastOutAt', render: formatTime },
          { title: t('field.workedHours'), dataIndex: 'workedHours', align: 'right', render: (v: number | null) => fmtNum(v) },
          { title: t('field.lateMin'), dataIndex: 'lateMin', align: 'right', render: (v: number) => (v ? v : '') },
          { title: t('field.earlyMin'), dataIndex: 'earlyMin', align: 'right', render: (v: number) => (v ? v : '') },
          { title: t('field.dayValue'), dataIndex: 'dayValue', align: 'right', render: (v: number | null) => fmtNum(v) },
          { title: t('field.exceptions'), dataIndex: 'exceptions', render: (e: string[]) => <ExceptionTags exceptions={e} /> },
          {
            title: '',
            key: 'view',
            render: (_, d) => (
              <Button size="small" icon={<EyeOutlined />} aria-label={t('employee.viewDay', { date: formatDate(d.workDate) })} onClick={() => setDay(d.workDate)} />
            ),
          },
        ]}
      />
      {day ? <DayDrawer employmentId={employmentId} workDate={day} onClose={() => setDay(null)} /> : null}
    </>
  );
}

function DayDrawer({ employmentId, workDate, onClose }: { employmentId: UUID; workDate: ISODate; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const q = useDayDetail(employmentId, workDate);
  const [editing, setEditing] = useState(false);
  const [requesting, setRequesting] = useState(false);
  const d = q.data?.day;

  return (
    <Drawer
      open
      onClose={onClose}
      width={760}
      destroyOnHidden
      title={t('day.title', { date: formatDate(workDate) })}
      extra={
        <Space>
          <PermissionGate anyOf={P.TIMESHEET_CORRECTION_REQUEST}>
            <Button icon={<FormOutlined />} onClick={() => setRequesting(true)}>
              {t('correction.action')}
            </Button>
          </PermissionGate>
          <PermissionGate anyOf={P.TIMESHEET_UPDATE}>
            <Button type="primary" icon={<EditOutlined />} onClick={() => setEditing(true)}>
              {t('edit.action')}
            </Button>
          </PermissionGate>
        </Space>
      }
    >
      {q.error ? <ProblemAlert error={q.error} style={{ marginBottom: 12 }} /> : null}
      {q.data && !d ? <Alert type="info" showIcon message={t('day.notComputed')} style={{ marginBottom: 12 }} /> : null}
      {d ? (
        <DescriptionList
          column={2}
          items={[
            { label: t('field.dayType'), value: t(`dayType.${d.dayType}`, { defaultValue: d.dayType }) },
            { label: t('field.dayStatus'), value: <DayStatusTag status={d.dayStatus} /> },
            { label: t('field.codes'), value: d.codes.length ? <CodeTags codes={d.codes} /> : null },
            { label: t('field.dayValue'), value: fmtNum(d.dayValue) },
            { label: t('field.firstIn'), value: formatDateTime(d.firstInAt) },
            { label: t('field.lastOut'), value: formatDateTime(d.lastOutAt) },
            { label: t('field.scheduledHours'), value: fmtNum(d.scheduledHours) },
            { label: t('field.workedHours'), value: fmtNum(d.workedHours) },
            { label: t('field.paidBreakHours'), value: fmtNum(d.paidBreakHours) },
            { label: t('field.protectedBreakMin'), value: d.protectedBreakMin || null },
            { label: t('field.lateMin'), value: d.lateMin || null },
            { label: t('field.earlyMin'), value: d.earlyMin || null },
            { label: t('field.absentHours'), value: fmtNum(d.absentHours) },
            { label: t('field.nightHours'), value: fmtNum(d.nightHours) },
            { label: t('field.otCandidateMin'), value: d.otCandidateMin || null },
            { label: t('field.exceptions'), value: d.exceptions.length ? <ExceptionTags exceptions={d.exceptions} /> : null },
            { label: t('field.manualOverride'), value: d.manualOverride ? <Tag color="purple">{t('field.manualOverride')}</Tag> : null },
            { label: t('field.calcVersion'), value: d.calcVersion },
            { label: t('field.calcAt'), value: formatDateTime(d.calcAt) },
            { label: t('field.ruleVersion'), value: d.ruleVersion },
          ]}
        />
      ) : null}

      <Typography.Title level={5} style={{ marginTop: 16 }}>
        {t('day.punches')}
      </Typography.Title>
      <PunchTable punches={q.data?.punches ?? []} loading={q.isLoading} />

      <Typography.Title level={5} style={{ marginTop: 16 }}>
        {t('day.corrections')}
      </Typography.Title>
      <DataTable<CorrectionRow>
        data={q.data?.corrections ?? []}
        isLoading={q.isLoading}
        locale={{ emptyText: t('day.noCorrections') }}
        columns={[
          { title: t('field.createdAt'), dataIndex: 'createdAt', render: (v: string) => formatDateTime(v) },
          { title: t('field.correctionType'), dataIndex: 'correctionType', render: (c: string) => t(`correctionType.${c}`, { defaultValue: c }) },
          { title: t('field.source'), dataIndex: 'source', render: (s: string) => t(`correctionSource.${s}`, { defaultValue: s }) },
          {
            title: t('field.inTime'),
            key: 'times',
            render: (_, c) => [formatTime(c.requestedInAt), formatTime(c.requestedOutAt)].filter(Boolean).join(' – '),
          },
          { title: t('field.requestedCode'), dataIndex: 'requestedCode' },
          { title: t('field.reason'), key: 'reason', render: (_, c) => [c.reasonCode, c.reasonText].filter(Boolean).join(' · ') },
          {
            title: t('field.status'),
            dataIndex: 'status',
            render: (s: string) => <StatusTag status={s} labels={{ [s]: t(`correctionStatus.${s}`, { defaultValue: s }) }} />,
          },
        ]}
      />

      {editing ? <EditDaysDrawer days={[{ employmentId, workDate }]} onClose={() => setEditing(false)} /> : null}
      {requesting ? <CorrectionDrawer employmentId={employmentId} workDate={workDate} onClose={() => setRequesting(false)} /> : null}
    </Drawer>
  );
}

function PunchTable({ punches, loading, empty }: { punches: PunchRow[]; loading?: boolean; empty?: string }) {
  const { t } = useTranslation(NS);
  return (
    <DataTable<PunchRow>
      data={punches}
      isLoading={loading}
      locale={{ emptyText: empty ?? t('employee.noPunches') }}
      columns={[
        { title: t('field.punchTime'), dataIndex: 'localTs', render: localTime },
        { title: t('field.device'), dataIndex: 'terminalCode' },
        { title: t('field.enrolId'), dataIndex: 'enrolId', render: (v: string | null) => (v ? <code>{v}</code> : null) },
        { title: t('field.direction'), dataIndex: 'direction', render: (v: string | null) => (v ? t(`direction.${v}`, { defaultValue: v }) : null) },
        { title: t('field.verifyMethod'), dataIndex: 'verifyMethod' },
        { title: t('field.validationStatus'), dataIndex: 'validationStatus', render: (v: string) => t(`punchStatus.${v}`, { defaultValue: v }) },
      ]}
    />
  );
}

// ------------------------------------------------------------------------------------------------ punches

function PunchesTab({ employmentId, from, to }: { employmentId: UUID; from: ISODate; to: ISODate }) {
  const { t } = useTranslation(NS);
  const q = useEmploymentPunches(employmentId, from, to);
  return (
    <>
      <Typography.Paragraph type="secondary">{t('employee.punchesAudited')}</Typography.Paragraph>
      {q.error ? <ProblemAlert error={q.error} style={{ marginBottom: 12 }} /> : null}
      <PunchTable punches={q.data ?? []} loading={q.isLoading} />
    </>
  );
}

// ------------------------------------------------------------------------------------------------ menstrual break

/** Sensitive (BR-ATT-035, VN-16): only rendered for holders of `att.menstrual_break.view`; reads are audited. */
function MenstrualTab({ employmentId, month: initialMonth }: { employmentId: UUID; month: string }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [month, setMonth] = useState(initialMonth);
  const [date, setDate] = useState<Dayjs | null>(null);
  const q = useMenstrualBreaks(employmentId, month);
  const declare = useDeclareMenstrualBreak();
  const withdraw = useWithdrawMenstrualBreak();

  const onDeclare = async () => {
    if (!date) return;
    try {
      const r = await declare.mutateAsync({ employmentId, workDate: date.format(ISO_DATE) });
      fb.done(t('menstrual.declared'));
      r.warnings.forEach((w) => fb.done(t(`menstrual.warning.${w}`, { defaultValue: w })));
      setDate(null);
    } catch (e) {
      fb.failed(e);
    }
  };
  const onWithdraw = async (d: MenstrualBreakDeclaration) => {
    try {
      await withdraw.mutateAsync({ employmentId, workDate: d.workDate });
      fb.done(t('menstrual.withdrawn'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <Card size="small" title={t('menstrual.title')}>
      <Typography.Paragraph type="secondary">{t('menstrual.help')}</Typography.Paragraph>
      <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('menstrual.sensitive')} />
      <Space wrap style={{ marginBottom: 12 }}>
        <DatePicker
          picker="month"
          aria-label={t('field.month')}
          format="MM/YYYY"
          allowClear={false}
          value={dayjs(`${month}-01`)}
          onChange={(m) => m && setMonth(m.format('YYYY-MM'))}
        />
        {q.data ? (
          <Tag color="blue">
            {t('menstrual.used', { active: q.data.activeInMonth, perMonth: q.data.daysPerMonth ?? '—' })}
          </Tag>
        ) : null}
        <PermissionGate anyOf={P.MENSTRUAL_BREAK_DECLARE}>
          <DatePicker aria-label={t('menstrual.declareDate')} placeholder={t('menstrual.declareDate')} format={DATE_FORMAT} value={date} onChange={setDate} />
          <Button icon={<PlusOutlined />} disabled={!date} loading={declare.isPending} onClick={() => void onDeclare()}>
            {t('menstrual.declare')}
          </Button>
        </PermissionGate>
      </Space>
      <DataTable<MenstrualBreakDeclaration>
        data={q.data?.days ?? []}
        isLoading={q.isLoading}
        error={q.error}
        locale={{ emptyText: t('menstrual.empty') }}
        columns={[
          { title: t('field.workDate'), dataIndex: 'workDate', render: (d: string) => formatDate(d) },
          { title: t('field.source'), dataIndex: 'source', render: (s: string) => t(`menstrual.source.${s}`, { defaultValue: s }) },
          {
            title: t('field.status'),
            dataIndex: 'status',
            render: (s: string) => <StatusTag status={s === 'WITHDRAWN' ? 'CANCELLED' : s} labels={{ CANCELLED: t('menstrual.status.WITHDRAWN'), ACTIVE: t('menstrual.status.ACTIVE') }} />,
          },
          { title: t('field.createdAt'), dataIndex: 'createdAt', render: (v: string) => formatDateTime(v) },
          {
            title: '',
            key: 'actions',
            render: (_, d) =>
              d.status === 'ACTIVE' ? (
                <PermissionGate anyOf={P.MENSTRUAL_BREAK_DECLARE}>
                  <ConfirmButton
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('menstrual.withdrawAria', { date: formatDate(d.workDate) })}
                    onConfirm={() => onWithdraw(d)}
                  >
                    {t('menstrual.withdraw')}
                  </ConfirmButton>
                </PermissionGate>
              ) : null,
          },
        ]}
      />
    </Card>
  );
}
