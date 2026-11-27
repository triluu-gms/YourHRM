import { useState } from 'react';
import { Button, Checkbox, DatePicker, Select, Space, Tag, Typography } from 'antd';
import { EditOutlined, SyncOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, PageHeader, usePaging } from '@/shared/components';
import { DATE_FORMAT, formatDate, ISO_DATE } from '@/shared/format';
import { LegalEntitySelect, OrgUnitSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import { DAY_STATUSES, EXCEPTIONS, type DayRow, type EditItem } from './api';
import { useTimesheetGrid } from './queries';
import {
  CodeTags,
  DayStatusTag,
  EditDaysDrawer,
  ExceptionTags,
  fmtNum,
  formatTime,
  NS,
  RecomputeModal,
  shortId,
  useEmployeeLabels,
} from './components';

const keyOf = (d: { employmentId: UUID; workDate: string }) => `${d.employmentId}|${d.workDate}`;

/** Tenant-wide daily timesheet grid (US-ATT-032) with filters, recalculation and bulk edits (BR-ATT-037). */
export function TimesheetsPage() {
  const { t } = useTranslation(NS);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [orgUnitId, setOrgUnitId] = useState<UUID | undefined>();
  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => [dayjs().startOf('month'), dayjs()]);
  const [status, setStatus] = useState<string | undefined>();
  const [exception, setException] = useState<string | undefined>();
  const [blockingOnly, setBlockingOnly] = useState(false);
  const [selected, setSelected] = useState<EditItem[]>([]);
  const [editing, setEditing] = useState(false);
  const [recomputing, setRecomputing] = useState(false);
  const paging = usePaging(100);
  const canUpdate = usePermission(P.TIMESHEET_UPDATE);
  const employees = useEmployeeLabels();

  const from = range[0].format(ISO_DATE);
  const to = range[1].format(ISO_DATE);
  const q = useTimesheetGrid({
    legalEntityId,
    orgUnitId,
    from,
    to,
    status,
    exception,
    blockingOnly: blockingOnly || undefined,
    limit: paging.size,
    offset: paging.page * paging.size,
  });

  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    paging.reset();
    setSelected([]);
  };
  const selectedEmployments = [...new Set(selected.map((s) => s.employmentId))];

  return (
    <div>
      <PageHeader
        title={t('grid.title')}
        subTitle={t('grid.subtitle')}
        extra={
          <PermissionGate anyOf={P.TIMESHEET_UPDATE}>
            <Button icon={<SyncOutlined />} onClick={() => setRecomputing(true)}>
              {t('grid.recompute')}
            </Button>
            <Button type="primary" icon={<EditOutlined />} disabled={selected.length === 0} onClick={() => setEditing(true)}>
              {t('grid.bulkEdit')}
            </Button>
          </PermissionGate>
        }
      />
      <Space wrap style={{ marginBottom: 12 }}>
        <LegalEntitySelect
          aria-label={t('field.legalEntity')}
          placeholder={t('field.legalEntity')}
          style={{ width: 240 }}
          value={legalEntityId}
          onChange={(v) => {
            reset(setLegalEntityId)(v);
            setOrgUnitId(undefined);
          }}
        />
        <OrgUnitSelect aria-label={t('field.orgUnit')} placeholder={t('field.orgUnit')} legalEntityId={legalEntityId} style={{ width: 240 }} value={orgUnitId} onChange={reset(setOrgUnitId)} />
        <DatePicker.RangePicker
          aria-label={t('field.range')}
          format={DATE_FORMAT}
          allowClear={false}
          value={range}
          onChange={(v) => v?.[0] && v[1] && reset(setRange)([v[0], v[1]])}
        />
        <Select
          aria-label={t('field.dayStatus')}
          allowClear
          placeholder={t('grid.allStatuses')}
          style={{ width: 200 }}
          value={status}
          onChange={reset(setStatus)}
          options={DAY_STATUSES.map((s) => ({ value: s, label: t(`dayStatus.${s}`) }))}
        />
        <Select
          aria-label={t('field.exceptions')}
          allowClear
          placeholder={t('grid.allExceptions')}
          style={{ width: 220 }}
          value={exception}
          onChange={reset(setException)}
          options={EXCEPTIONS.map((e) => ({ value: e, label: t(`exception.${e}`) }))}
        />
        <Checkbox checked={blockingOnly} onChange={(e) => reset(setBlockingOnly)(e.target.checked)}>
          {t('field.blockingOnly')}
        </Checkbox>
      </Space>
      {selected.length > 0 ? (
        <Typography.Paragraph>
          <Tag color="blue">{t('grid.selected', { count: selected.length })}</Tag>
        </Typography.Paragraph>
      ) : null}

      <DataTable<DayRow>
        rowKey={keyOf}
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        locale={{ emptyText: t('grid.empty') }}
        paging={{ ...paging, onChange: (p, s) => { paging.onChange(p, s); setSelected([]); }, hasMore: (q.data?.length ?? 0) >= paging.size }}
        rowSelection={
          canUpdate
            ? {
                selectedRowKeys: selected.map(keyOf),
                onChange: (_k, rows) => setSelected(rows.map((r) => ({ employmentId: r.employmentId, workDate: r.workDate }))),
              }
            : undefined
        }
        columns={[
          {
            title: t('field.employee'),
            dataIndex: 'employmentId',
            render: (id: UUID) => (
              <Link to="/attendance/employee-timesheet" search={{ employmentId: id, from, to }} title={t('grid.openEmployee')}>
                {employees.get(id) ?? shortId(id)}
              </Link>
            ),
          },
          { title: t('field.workDate'), dataIndex: 'workDate', render: (d: string) => formatDate(d) },
          { title: t('field.dayType'), dataIndex: 'dayType', render: (d: string) => t(`dayType.${d}`, { defaultValue: d }) },
          { title: t('field.dayStatus'), dataIndex: 'dayStatus', render: (s: string) => <DayStatusTag status={s} /> },
          { title: t('field.codes'), dataIndex: 'codes', render: (c: DayRow['codes']) => <CodeTags codes={c} /> },
          { title: t('field.firstIn'), dataIndex: 'firstInAt', render: formatTime },
          { title: t('field.lastOut'), dataIndex: 'lastOutAt', render: formatTime },
          { title: t('field.workedHours'), dataIndex: 'workedHours', align: 'right', render: (v: number | null) => fmtNum(v) },
          { title: t('field.dayValue'), dataIndex: 'dayValue', align: 'right', render: (v: number | null) => fmtNum(v) },
          { title: t('field.exceptions'), dataIndex: 'exceptions', render: (e: string[]) => <ExceptionTags exceptions={e} /> },
          {
            title: t('field.manualOverride'),
            dataIndex: 'manualOverride',
            render: (b: boolean) => (b ? <Tag color="purple">{t('field.manualOverride')}</Tag> : null),
          },
        ]}
      />

      {editing ? (
        <EditDaysDrawer
          days={selected}
          onClose={(done) => {
            setEditing(false);
            if (done) setSelected([]);
          }}
        />
      ) : null}
      {recomputing ? (
        <RecomputeModal from={from} to={to} legalEntityId={legalEntityId} employmentIds={selectedEmployments} onClose={() => setRecomputing(false)} />
      ) : null}
    </div>
  );
}
