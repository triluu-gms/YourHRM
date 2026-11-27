import { useState } from 'react';
import { Button, DatePicker, Select, Space } from 'antd';
import dayjs from 'dayjs';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE, formatDate, formatDateTime } from '@/shared/format';
import { LegalEntitySelect, OrgUnitSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import { REQUEST_STATUSES, type LeaveRequestRow } from './api';
import { useLeaveRequests } from './queries';
import { EmploymentRef, formatQty, LeaveTypeName, LeaveTypeSelect, NS, RequestStatusTag, enumOptions, useEnumLabel } from './components';
import { NewLeaveRequestDrawer } from './NewLeaveRequestDrawer';

const PAGE = 100;

export function leaveDateRange(r: Pick<LeaveRequestRow, 'startDate' | 'endDate' | 'startTime' | 'endTime' | 'requestUnit'>) {
  if (r.requestUnit === 'HOUR') return `${formatDate(r.startDate)} ${r.startTime?.slice(0, 5) ?? ''}–${r.endTime?.slice(0, 5) ?? ''}`;
  return r.startDate === r.endDate ? formatDate(r.startDate) : `${formatDate(r.startDate)} – ${formatDate(r.endDate)}`;
}

/** Tenant list of leave requests in the caller's data scope (BR-ATT-061), with filters and the "new request" drawer. */
export function LeaveRequestsPage() {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const label = useEnumLabel();
  const navigate = useNavigate();
  const [range, setRange] = useState<[string, string]>([
    dayjs().startOf('month').format(ISO_DATE),
    dayjs().endOf('month').format(ISO_DATE),
  ]);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [orgUnitId, setOrgUnitId] = useState<UUID | undefined>();
  const [leaveTypeId, setLeaveTypeId] = useState<UUID | undefined>();
  const [status, setStatus] = useState<string | undefined>();
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const q = useLeaveRequests({
    from: range[0],
    to: range[1],
    legalEntityId,
    orgUnitId,
    leaveTypeId,
    status,
    limit: PAGE + 1,
    offset: page * PAGE,
  });
  const rows = q.data?.slice(0, PAGE);
  const hasMore = (q.data?.length ?? 0) > PAGE;
  const filter = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };

  return (
    <div>
      <PageHeader
        title={t('requests.title')}
        subTitle={t('requests.intro')}
        extra={
          <PermissionGate anyOf={P.LEAVE_REQUEST_CREATE}>
            <Button type="primary" onClick={() => setCreating(true)}>
              {t('requests.new')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap>
          <DatePicker.RangePicker
            aria-label={t('field.period')}
            format={DATE_FORMAT}
            allowClear={false}
            value={[dayjs(range[0]), dayjs(range[1])]}
            onChange={(v) => v?.[0] && v[1] && filter(setRange)([v[0].format(ISO_DATE), v[1].format(ISO_DATE)])}
          />
          <LegalEntitySelect
            aria-label={tc('field.legalEntity')}
            placeholder={t('common.allLegalEntities')}
            style={{ width: 240 }}
            value={legalEntityId}
            onChange={filter(setLegalEntityId)}
          />
          <OrgUnitSelect
            aria-label={tc('field.orgUnit')}
            placeholder={t('common.allOrgUnits')}
            style={{ width: 240 }}
            legalEntityId={legalEntityId}
            value={orgUnitId}
            onChange={filter(setOrgUnitId)}
          />
          <LeaveTypeSelect
            aria-label={t('field.leaveType')}
            placeholder={t('common.allLeaveTypes')}
            style={{ width: 240 }}
            value={leaveTypeId}
            onChange={filter(setLeaveTypeId)}
          />
          <Select
            aria-label={tc('field.status')}
            placeholder={t('common.allStatuses')}
            allowClear
            style={{ width: 180 }}
            value={status}
            onChange={filter(setStatus)}
            options={enumOptions(label, 'requestStatus', REQUEST_STATUSES)}
          />
        </Space>
      </PageHeader>
      <DataTable<LeaveRequestRow>
        data={rows}
        isLoading={q.isLoading}
        error={q.error}
        paging={{ page, size: PAGE, hasMore, onChange: (p) => setPage(p) }}
        onRow={(r) => ({
          onDoubleClick: () => void navigate({ to: '/attendance/leave-requests/$requestId', params: { requestId: r.id } }),
        })}
        columns={[
          {
            title: t('field.employee'),
            dataIndex: 'employmentId',
            render: (id: UUID) => (
              <Link to="/attendance/leave-balances" search={{ employmentId: id }}>
                <EmploymentRef id={id} />
              </Link>
            ),
          },
          { title: t('field.leaveType'), key: 'type', render: (_, r) => <LeaveTypeName id={r.leaveTypeId} code={r.leaveTypeCode} /> },
          {
            title: t('field.dates'),
            key: 'dates',
            render: (_, r) => (
              <Link to="/attendance/leave-requests/$requestId" params={{ requestId: r.id }}>
                {leaveDateRange(r)}
              </Link>
            ),
          },
          {
            title: t('field.duration'),
            key: 'dur',
            align: 'right',
            render: (_, r) =>
              r.requestUnit === 'HOUR'
                ? t('requests.hoursValue', { value: formatQty(r.durationHours) })
                : t('requests.daysValue', { value: formatQty(r.durationDays) }),
          },
          { title: tc('field.status'), dataIndex: 'status', render: (s: string) => <RequestStatusTag status={s} /> },
          { title: tc('field.createdAt'), dataIndex: 'createdAt', render: (v: string) => formatDateTime(v) },
        ]}
      />
      <NewLeaveRequestDrawer
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(row) => void navigate({ to: '/attendance/leave-requests/$requestId', params: { requestId: row.id } })}
      />
    </div>
  );
}
