import { useState } from 'react';
import { Alert, Button, Checkbox, Input, InputNumber, Select, Space, Tabs, Tag, Typography } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, DescriptionList, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { formatDate, formatValidity } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import { legalEntityLabel, useLegalEntities, useLocations } from '../../common';
import type { CalendarAssignment, DateEntry, DiffItem, HolidayCalendar, HolidayDate } from './api';
import { useApplyTemplate, useCancelDate, useHolidayCalendar, useHolidayDates, useHolidayTemplates, useTemplateDiff } from './queries';
import { AssignDrawer, DateDrawer, EditCalendarDrawer, EndAssignmentModal } from './CalendarForms';
import { DayTypeTag, lunarLabel, ReasonModal, useName } from './helpers';
import { CALENDARS_NS } from './i18n';

/** One holiday calendar: dates per year, template differences (TENANT_COPY) and legal-entity assignments. */
export function HolidayCalendarDetailPage({ calendarId }: { calendarId: UUID }) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const name = useName();
  const query = useHolidayCalendar(calendarId);
  const [editing, setEditing] = useState(false);
  const c = query.data;

  return (
    <div>
      <PageHeader
        title={c ? `${c.code} – ${name(c)}` : tc('calendar.title')}
        breadcrumb={[{ label: tc('calendar.title'), to: '/attendance/holiday-calendars' }, { label: c?.code ?? '…' }]}
        extra={
          c ? (
            <PermissionGate anyOf={P.HOLIDAY_CALENDAR_UPDATE}>
              <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>
                {t('action.edit')}
              </Button>
            </PermissionGate>
          ) : null
        }
      />
      {query.error ? <ProblemAlert error={query.error} /> : null}
      {c ? (
        <>
          <DescriptionList
            style={{ marginBottom: 16 }}
            items={[
              { label: t('field.code'), value: <code>{c.code}</code> },
              { label: t('field.nameVi'), value: c.nameVi },
              { label: t('field.nameEn'), value: c.nameEn },
              { label: tc('calendar.field.source'), value: <Tag>{tc(`source.${c.source}`, { defaultValue: c.source })}</Tag> },
              { label: tc('calendar.field.templateYear'), value: c.templateYear, hideEmpty: true },
              { label: tc('calendar.field.country'), value: c.countryCode },
              { label: t('field.status'), value: <StatusTag status={c.status} /> },
            ]}
          />
          <Tabs
            items={[
              { key: 'dates', label: tc('date.tab'), children: <DatesTab calendar={c} /> },
              ...(c.source === 'TENANT_COPY'
                ? [{ key: 'template', label: tc('diff.tab'), children: <TemplateTab calendar={c} /> }]
                : []),
              { key: 'assignments', label: tc('assignment.tab'), children: <AssignmentsTab calendar={c} /> },
            ]}
          />
          {editing ? <EditCalendarDrawer calendar={c} onClose={() => setEditing(false)} /> : null}
        </>
      ) : null}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ dates

function DatesTab({ calendar }: { calendar: HolidayCalendar }) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const name = useName();
  const fb = useFeedback();
  const canUpdate = usePermission(P.HOLIDAY_CALENDAR_UPDATE);
  const [year, setYear] = useState(calendar.templateYear ?? dayjs().year());
  const [includeCancelled, setIncludeCancelled] = useState(false);
  const [editing, setEditing] = useState<HolidayDate | 'new' | null>(null);
  const [cancelling, setCancelling] = useState<HolidayDate | null>(null);
  const dates = useHolidayDates(calendar.id, year, includeCancelled);
  const cancel = useCancelDate(calendar.id);

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <Typography.Text>{tc('calendar.field.year')}</Typography.Text>
        <InputNumber
          aria-label={tc('calendar.field.year')}
          min={2000}
          max={2100}
          precision={0}
          value={year}
          onChange={(v) => v && setYear(v)}
        />
        <Checkbox checked={includeCancelled} onChange={(e) => setIncludeCancelled(e.target.checked)}>
          {tc('date.includeCancelled')}
        </Checkbox>
        <PermissionGate anyOf={P.HOLIDAY_CALENDAR_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')} disabled={calendar.status !== 'ACTIVE'}>
            {tc('date.create')}
          </Button>
        </PermissionGate>
      </Space>
      <DataTable<HolidayDate>
        data={dates.data}
        isLoading={dates.isLoading}
        error={dates.error}
        locale={{ emptyText: tc('date.empty', { year }) }}
        columns={[
          { title: tc('date.field.holidayDate'), dataIndex: 'holidayDate', render: (d: string) => formatDate(d) },
          { title: tc('date.field.weekday'), key: 'dow', render: (_, r) => dayjs(r.holidayDate).format('dddd') },
          { title: tc('date.field.dayType'), dataIndex: 'dayType', render: (d: HolidayDate['dayType']) => <DayTypeTag type={d} /> },
          { title: tc('date.field.holidayCode'), dataIndex: 'holidayCode', render: (c?: string | null) => (c ? <code>{c}</code> : null) },
          { title: t('field.name'), key: 'name', render: (_, r) => name(r) },
          { title: tc('date.field.lunar'), key: 'lunar', render: (_, r) => lunarLabel(r, tc('date.leapShort')) },
          { title: tc('date.field.substituteForDate'), dataIndex: 'substituteForDate', render: (d?: string | null) => formatDate(d) },
          { title: tc('date.field.paid'), dataIndex: 'paid', render: (p: boolean) => (p ? t('common.yes') : t('common.no')) },
          {
            title: tc('date.field.eligibility'),
            key: 'elig',
            render: (_, r) => (r.eligibility === 'FOREIGNERS' ? `${tc('eligibility.FOREIGNERS')} (${r.nationalityCode ?? ''})` : tc('eligibility.ALL')),
          },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, r: HolidayDate) =>
                    r.status === 'ACTIVE' ? (
                      <Space size={4}>
                        <Button size="small" onClick={() => setEditing(r)}>
                          {t('action.edit')}
                        </Button>
                        <Button size="small" danger onClick={() => setCancelling(r)}>
                          {tc('date.cancel')}
                        </Button>
                      </Space>
                    ) : null,
                },
              ]
            : []),
        ]}
      />
      {editing ? (
        <DateDrawer
          calendarId={calendar.id}
          date={editing === 'new' ? undefined : editing}
          defaultYear={year}
          onClose={() => setEditing(null)}
        />
      ) : null}
      <ReasonModal
        open={!!cancelling}
        title={tc('date.cancelTitle', { date: formatDate(cancelling?.holidayDate) })}
        description={tc('date.cancelHint')}
        onClose={() => setCancelling(null)}
        submitting={cancel.isPending}
        error={cancel.error}
        onSubmit={async (reason) => {
          if (!cancelling) return;
          await cancel.mutateAsync({ dateId: cancelling.id, body: { version: cancelling.version, reason } });
          fb.done(tc('date.cancelled'));
        }}
      />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ template

const DIFF_COLOURS: Record<string, string> = { ADDED: 'green', CHANGED: 'gold', REMOVED: 'red', OVERRIDDEN: 'purple' };

/** Template differences of a year (US-ATT-002): ADDED / REMOVED are preselected, CHANGED / OVERRIDDEN only on request. */
function TemplateTab({ calendar }: { calendar: HolidayCalendar }) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const name = useName();
  const fb = useFeedback();
  const canUpdate = usePermission(P.HOLIDAY_CALENDAR_UPDATE);
  const templates = useHolidayTemplates();
  const template = templates.data?.find((x) => x.id === calendar.templateCalendarId);
  const [year, setYear] = useState(calendar.templateYear ?? dayjs().year());
  const [selected, setSelected] = useState<Record<number, string[]>>({});
  const [reason, setReason] = useState('');
  const diff = useTemplateDiff(calendar.id, year, true);
  const apply = useApplyTemplate(calendar.id);
  const items = diff.data ?? [];
  const defaultKeys = items.filter((d) => d.kind === 'ADDED' || d.kind === 'REMOVED').map((d) => d.key);
  const keys = selected[year] ?? defaultKeys;
  const years = Array.from(new Set([...(template?.years ?? []), year])).sort();

  const entry = (d: DiffItem): DateEntry | null | undefined => d.template ?? d.current;
  const describe = (e: DateEntry | null | undefined) =>
    e ? `${formatDate(e.holidayDate)} · ${tc(`dayType.${e.dayType}`, { defaultValue: e.dayType })} · ${name(e)}${e.paid ? '' : ` · ${tc('date.unpaid')}`}` : '—';

  const submit = async () => {
    try {
      const result = await apply.mutateAsync({ year, acceptKeys: keys, reason: reason.trim() || null });
      setSelected((s) => ({ ...s, [year]: [] }));
      setReason('');
      fb.done(tc('diff.applied', { count: result.applied.length }));
    } catch {
      // shown below
    }
  };

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <Typography.Text>{tc('calendar.field.year')}</Typography.Text>
        <Select<number>
          aria-label={tc('calendar.field.year')}
          style={{ width: 120 }}
          value={year}
          onChange={setYear}
          options={years.map((y) => ({ value: y, label: String(y) }))}
        />
      </Space>
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message={tc('diff.hint')} />
      {apply.error ? <ProblemAlert error={apply.error} style={{ marginBottom: 12 }} /> : null}
      <DataTable<DiffItem>
        rowKey="key"
        data={items}
        isLoading={diff.isLoading}
        error={diff.error}
        locale={{ emptyText: tc('diff.empty') }}
        rowSelection={
          canUpdate
            ? { selectedRowKeys: keys, onChange: (k) => setSelected((s) => ({ ...s, [year]: k.map(String) })) }
            : undefined
        }
        columns={[
          { title: tc('diff.field.kind'), dataIndex: 'kind', render: (k: string) => <Tag color={DIFF_COLOURS[k]}>{tc(`diff.kind.${k}`)}</Tag> },
          { title: tc('date.field.holidayDate'), key: 'date', render: (_, d) => formatDate(entry(d)?.holidayDate) },
          { title: tc('diff.field.template'), key: 'template', render: (_, d) => describe(d.template) },
          { title: tc('diff.field.current'), key: 'current', render: (_, d) => (d.current ? <>{describe(d.current)} <StatusTag status={d.current.status} /></> : '—') },
        ]}
      />
      <PermissionGate anyOf={P.HOLIDAY_CALENDAR_UPDATE}>
        <Space wrap style={{ marginTop: 12 }}>
          <Input
            aria-label={t('field.reason')}
            placeholder={tc('diff.reasonPlaceholder')}
            maxLength={500}
            style={{ width: 360 }}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <Button type="primary" disabled={keys.length === 0} loading={apply.isPending} onClick={() => void submit()}>
            {tc('diff.apply', { count: keys.length })}
          </Button>
        </Space>
      </PermissionGate>
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ assignments

function AssignmentsTab({ calendar }: { calendar: HolidayCalendar }) {
  const { t, i18n } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const canUpdate = usePermission(P.HOLIDAY_CALENDAR_UPDATE);
  const legalEntities = useLegalEntities();
  const locations = useLocations();
  const [assigning, setAssigning] = useState(false);
  const [ending, setEnding] = useState<CalendarAssignment | null>(null);
  const leLabel = (id: UUID) => legalEntityLabel(legalEntities.data?.find((le) => le.id === id), i18n.language) || id;
  const locLabel = (id?: UUID | null) => {
    if (!id) return tc('assignment.wholeLegalEntity');
    const l = locations.data?.find((x) => x.id === id);
    return l ? (l.current?.name ? `${l.code} – ${l.current.name}` : l.code) : id;
  };
  const rows = [...calendar.assignments].sort((a, b) => (a.validFrom < b.validFrom ? 1 : -1));

  return (
    <div>
      <PermissionGate anyOf={P.HOLIDAY_CALENDAR_UPDATE}>
        <Space style={{ marginBottom: 12 }}>
          <Button type="primary" icon={<PlusOutlined />} disabled={calendar.status !== 'ACTIVE'} onClick={() => setAssigning(true)}>
            {tc('assignment.create')}
          </Button>
        </Space>
      </PermissionGate>
      <DataTable<CalendarAssignment>
        data={rows}
        locale={{ emptyText: tc('assignment.empty') }}
        columns={[
          { title: t('field.legalEntity'), key: 'le', render: (_, a) => leLabel(a.legalEntityId) },
          { title: t('field.location'), key: 'loc', render: (_, a) => locLabel(a.locationId) },
          { title: tc('common.validity'), key: 'validity', render: (_, a) => formatValidity(a.validFrom, a.validTo, t('common.openEnded')) },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, a: CalendarAssignment) => (
                    <Button size="small" onClick={() => setEnding(a)}>
                      {tc('assignment.end')}
                    </Button>
                  ),
                },
              ]
            : []),
        ]}
      />
      {assigning ? <AssignDrawer calendarId={calendar.id} onClose={() => setAssigning(false)} /> : null}
      {ending ? <EndAssignmentModal calendarId={calendar.id} assignment={ending} onClose={() => setEnding(null)} /> : null}
    </div>
  );
}
