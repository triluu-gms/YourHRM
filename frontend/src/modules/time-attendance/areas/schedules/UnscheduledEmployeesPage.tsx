import { useState } from 'react';
import { Button, DatePicker, Empty, Space, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader } from '@/shared/components';
import { DATE_FORMAT, formatDate, ISO_DATE } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import { LegalEntitySelect, lookupApi } from '../../common';
import type { UnscheduledLine } from './api';
import { useUnscheduled } from './queries';
import { useLookupLabels } from './helpers';
import { SCHEDULES_NS } from './i18n';

/** Assignment lines valid on a date without a schedule (US-ATT-011): the exception list before processing. */
export function UnscheduledEmployeesPage() {
  const { t } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const labels = useLookupLabels();
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [date, setDate] = useState<Dayjs>(dayjs());
  const query = useUnscheduled(legalEntityId, date.format(ISO_DATE));
  // Names from People's directory (scoped by the backend); without access the employment id is shown.
  const employees = useQuery({
    queryKey: ['att', 'schedules', 'unscheduled-names', legalEntityId ?? ''],
    queryFn: () => lookupApi.employees({ legalEntityId, size: 500 }),
    enabled: !!legalEntityId && (query.data?.length ?? 0) > 0,
    staleTime: 60_000,
    retry: false,
  });
  const employee = (id: UUID) => {
    const e = employees.data?.items.find((x) => x.employmentId === id);
    return e ? `${e.employeeNo} – ${e.fullName}` : id;
  };

  return (
    <div>
      <PageHeader title={ts('unscheduled.title')} subTitle={ts('unscheduled.subtitle')}>
        <Space wrap>
          <LegalEntitySelect
            aria-label={t('field.legalEntity')}
            placeholder={ts('common.pickLegalEntity')}
            style={{ width: 320 }}
            value={legalEntityId}
            onChange={setLegalEntityId}
          />
          <Typography.Text>{ts('unscheduled.date')}</Typography.Text>
          <DatePicker aria-label={ts('unscheduled.date')} format={DATE_FORMAT} allowClear={false} value={date} onChange={(d) => d && setDate(d)} />
        </Space>
      </PageHeader>
      {!legalEntityId ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={ts('unscheduled.pickLegalEntity')} />
      ) : (
        <DataTable<UnscheduledLine>
          rowKey="assignmentKey"
          data={query.data}
          isLoading={query.isLoading}
          error={query.error}
          locale={{ emptyText: ts('unscheduled.empty') }}
          columns={[
            { title: ts('common.employee'), dataIndex: 'employmentId', render: (id: UUID) => employee(id) },
            { title: ts('unscheduled.field.assignmentType'), dataIndex: 'assignmentType', render: (v?: string | null) => (v ? ts(`assignmentType.${v}`, { defaultValue: v }) : '') },
            { title: t('field.orgUnit'), dataIndex: 'orgUnitId', render: (id?: UUID | null) => labels.orgUnit(id) },
            { title: t('field.location'), dataIndex: 'locationId', render: (id?: UUID | null) => labels.location(id) },
            { title: ts('unscheduled.field.lineValidFrom'), dataIndex: 'lineValidFrom', render: (d: string) => formatDate(d) },
            {
              title: t('field.actions'),
              key: 'actions',
              render: (_, r) => (
                <Space size={4}>
                  <Link to="/attendance/schedule-assignments" search={{ employmentId: r.employmentId }}>
                    {t('action.view')}
                  </Link>
                  <PermissionGate anyOf={P.SCHEDULE_ASSIGNMENT_UPDATE}>
                    <Link to="/attendance/schedule-assignments" search={{ employmentId: r.employmentId, assignmentKey: r.assignmentKey }}>
                      <Button size="small" type="primary">
                        {ts('unscheduled.assign')}
                      </Button>
                    </Link>
                  </PermissionGate>
                </Space>
              ),
            },
          ]}
        />
      )}
    </div>
  );
}
