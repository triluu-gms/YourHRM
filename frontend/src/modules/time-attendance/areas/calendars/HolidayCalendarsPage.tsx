import { useState } from 'react';
import { Button, DatePicker, Empty, Select, Space, Tabs, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader, StatusTag } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatValidity, ISO_DATE } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import { LegalEntitySelect, useLocations } from '../../common';
import type { CalendarAssignment, HolidayCalendar, HolidayDate, ResolvedHoliday } from './api';
import {
  useHolidayCalendars,
  useHolidayTemplates,
  useLegalEntityCalendarAssignments,
  useResolvedHolidays,
  useTemplateDates,
} from './queries';
import { CreateCalendarDrawer } from './CalendarForms';
import { DayTypeTag, lunarLabel, useName } from './helpers';
import { CALENDARS_NS } from './i18n';

/** Holiday calendars of the tenant (US-ATT-001..007): list + create, by legal entity, platform templates. */
export function HolidayCalendarsPage() {
  const { t: tc } = useTranslation(CALENDARS_NS);
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  return (
    <div>
      <PageHeader
        title={tc('calendar.title')}
        subTitle={tc('calendar.subtitle')}
        extra={
          <PermissionGate anyOf={P.HOLIDAY_CALENDAR_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {tc('calendar.create')}
            </Button>
          </PermissionGate>
        }
      />
      <Tabs
        items={[
          { key: 'calendars', label: tc('calendar.tab.calendars'), children: <CalendarsTable /> },
          { key: 'legal-entity', label: tc('calendar.tab.byLegalEntity'), children: <ByLegalEntity /> },
          { key: 'templates', label: tc('calendar.tab.templates'), children: <Templates /> },
        ]}
      />
      {creating ? (
        <CreateCalendarDrawer
          onClose={() => setCreating(false)}
          onSaved={(c) => void navigate({ to: '/attendance/holiday-calendars/$calendarId', params: { calendarId: c.id } })}
        />
      ) : null}
    </div>
  );
}

function CalendarsTable() {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const name = useName();
  const navigate = useNavigate();
  const query = useHolidayCalendars();
  return (
    <DataTable<HolidayCalendar>
      data={query.data}
      isLoading={query.isLoading}
      error={query.error}
      locale={{ emptyText: tc('calendar.empty') }}
      onRow={(row) => ({
        style: { cursor: 'pointer' },
        onClick: () => void navigate({ to: '/attendance/holiday-calendars/$calendarId', params: { calendarId: row.id } }),
      })}
      columns={[
        {
          title: t('field.code'),
          dataIndex: 'code',
          render: (code: string, row) => (
            <Link to="/attendance/holiday-calendars/$calendarId" params={{ calendarId: row.id }} onClick={(e) => e.stopPropagation()}>
              {code}
            </Link>
          ),
        },
        { title: t('field.name'), key: 'name', render: (_, row) => name(row) },
        { title: tc('calendar.field.source'), dataIndex: 'source', render: (s: string) => <Tag>{tc(`source.${s}`, { defaultValue: s })}</Tag> },
        { title: tc('calendar.field.templateYear'), dataIndex: 'templateYear' },
        { title: tc('calendar.field.assignments'), key: 'assignments', render: (_, row) => row.assignments.length },
        { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
      ]}
    />
  );
}

/** Which calendar applies when (assignments of one legal entity) and its resolved holidays (ATT-P-16). */
function ByLegalEntity() {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const name = useName();
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().startOf('year'), dayjs().endOf('year')]);
  const [locationId, setLocationId] = useState<UUID | undefined>();
  const calendars = useHolidayCalendars();
  const locations = useLocations();
  const assignments = useLegalEntityCalendarAssignments(legalEntityId);
  const holidays = useResolvedHolidays(legalEntityId, {
    from: range[0].format(ISO_DATE),
    to: range[1].format(ISO_DATE),
    locationId,
  });
  const calendarOf = (id: UUID) => calendars.data?.find((c) => c.id === id);
  const locationLabel = (id?: UUID | null) => {
    if (!id) return tc('assignment.wholeLegalEntity');
    const l = locations.data?.find((x) => x.id === id);
    return l ? (l.current?.name ? `${l.code} – ${l.current.name}` : l.code) : id;
  };

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <LegalEntitySelect
          aria-label={t('field.legalEntity')}
          placeholder={tc('common.pickLegalEntity')}
          style={{ width: 320 }}
          value={legalEntityId}
          onChange={(v) => {
            setLegalEntityId(v);
            setLocationId(undefined);
          }}
        />
      </Space>
      {!legalEntityId ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={tc('common.pickLegalEntityFirst')} />
      ) : (
        <>
          <Typography.Title level={5}>{tc('calendar.leAssignments')}</Typography.Title>
          <DataTable<CalendarAssignment>
            data={assignments.data}
            isLoading={assignments.isLoading}
            error={assignments.error}
            columns={[
              {
                title: tc('calendar.title'),
                key: 'calendar',
                render: (_, row) => {
                  const c = calendarOf(row.calendarId);
                  return (
                    <Link to="/attendance/holiday-calendars/$calendarId" params={{ calendarId: row.calendarId }}>
                      {c ? `${c.code} – ${name(c)}` : row.calendarId}
                    </Link>
                  );
                },
              },
              { title: t('field.location'), key: 'loc', render: (_, row) => locationLabel(row.locationId) },
              { title: tc('common.validity'), key: 'validity', render: (_, row) => formatValidity(row.validFrom, row.validTo, t('common.openEnded')) },
            ]}
          />
          <Typography.Title level={5} style={{ marginTop: 24 }}>
            {tc('calendar.resolvedHolidays')}
          </Typography.Title>
          <Space wrap style={{ marginBottom: 12 }}>
            <DatePicker.RangePicker
              format={DATE_FORMAT}
              allowClear={false}
              value={range}
              onChange={(v) => v && v[0] && v[1] && setRange([v[0], v[1]])}
            />
            <Select<UUID>
              allowClear
              aria-label={t('field.location')}
              placeholder={tc('assignment.wholeLegalEntity')}
              style={{ width: 240 }}
              value={locationId}
              onChange={setLocationId}
              options={(locations.data ?? [])
                .filter((l) => l.legalEntityId === legalEntityId)
                .map((l) => ({ value: l.id, label: locationLabel(l.id) }))}
            />
          </Space>
          <DataTable<ResolvedHoliday>
            rowKey={(r) => `${r.date}|${r.dayType}|${r.nationalityCode ?? ''}`}
            data={holidays.data}
            isLoading={holidays.isLoading}
            error={holidays.error}
            columns={[
              { title: tc('date.field.holidayDate'), dataIndex: 'date', render: (d: string) => formatDate(d) },
              { title: tc('date.field.dayType'), dataIndex: 'dayType', render: (d: ResolvedHoliday['dayType']) => <DayTypeTag type={d} /> },
              { title: t('field.name'), key: 'name', render: (_, r) => name(r) },
              { title: tc('date.field.substituteForDate'), dataIndex: 'substituteForDate', render: (d?: string | null) => formatDate(d) },
              { title: tc('date.field.paid'), dataIndex: 'paid', render: (p: boolean) => (p ? t('common.yes') : t('common.no')) },
              {
                title: tc('date.field.eligibility'),
                key: 'elig',
                render: (_, r) => (r.foreignersOnly ? `${tc('eligibility.FOREIGNERS')} (${r.nationalityCode ?? ''})` : tc('eligibility.ALL')),
              },
            ]}
          />
        </>
      )}
    </div>
  );
}

/** Platform templates (read-only, sentinel tenant): their dates per published year. */
function Templates() {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const name = useName();
  const templates = useHolidayTemplates();
  const [templateId, setTemplateId] = useState<UUID | undefined>();
  const [year, setYear] = useState<number | undefined>();
  const template = templates.data?.find((x) => x.id === templateId);
  const effectiveYear = year ?? template?.years[template.years.length - 1];
  const dates = useTemplateDates(templateId, effectiveYear);
  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <Select<UUID>
          aria-label={tc('calendar.field.template')}
          placeholder={tc('calendar.field.template')}
          style={{ width: 320 }}
          loading={templates.isLoading}
          value={templateId}
          onChange={(v) => {
            setTemplateId(v);
            setYear(undefined);
          }}
          options={(templates.data ?? []).map((x) => ({ value: x.id, label: `${x.code} – ${name(x)}` }))}
        />
        <Select<number>
          aria-label={tc('calendar.field.year')}
          placeholder={tc('calendar.field.year')}
          style={{ width: 120 }}
          value={effectiveYear}
          onChange={setYear}
          disabled={!template}
          options={(template?.years ?? []).map((y) => ({ value: y, label: String(y) }))}
        />
      </Space>
      {!templateId ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={tc('calendar.pickTemplate')} />
      ) : (
        <DataTable<HolidayDate>
          data={dates.data}
          isLoading={dates.isLoading}
          error={dates.error}
          columns={[
            { title: tc('date.field.holidayDate'), dataIndex: 'holidayDate', render: (d: string) => formatDate(d) },
            { title: tc('date.field.dayType'), dataIndex: 'dayType', render: (d: HolidayDate['dayType']) => <DayTypeTag type={d} /> },
            { title: tc('date.field.holidayCode'), dataIndex: 'holidayCode', render: (c?: string | null) => (c ? <code>{c}</code> : null) },
            { title: t('field.name'), key: 'name', render: (_, r) => name(r) },
            { title: tc('date.field.lunar'), key: 'lunar', render: (_, r) => lunarLabel(r, tc('date.leapShort')) },
            { title: tc('date.field.legalRef'), dataIndex: 'legalRef' },
          ]}
        />
      )}
    </div>
  );
}

