import { useState } from 'react';
import { Button, DatePicker, Input, Space } from 'antd';
import { UserAddOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader, usePaging } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import type { UUID } from '@/shared/api';
import {
  EmploymentStatusSelect,
  EmploymentStatusTag,
  JobTitleName,
  LegalEntityName,
  LegalEntitySelect,
  MaskedValue,
  OrgUnitName,
  OrgUnitSelect,
  toIsoDate,
  type EmployeeRow,
  type EmploymentStatus,
} from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { useEmployeeSearch } from './queries';

/** Employee directory (US-PPL-015): scoped and masked by the backend; accent-insensitive name or number search. */
export function EmployeesListPage() {
  const { t } = useTranslation();
  const { t: tp } = useTranslation('people');
  const navigate = useNavigate();
  const paging = usePaging();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<EmploymentStatus | undefined>();
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [orgUnitId, setOrgUnitId] = useState<UUID | undefined>();
  const [asOf, setAsOf] = useState<Dayjs | null>(null);

  const query = useEmployeeSearch({
    q: q || undefined,
    status,
    legalEntityId,
    orgUnitId,
    asOf: toIsoDate(asOf) ?? undefined,
    page: paging.page,
    size: paging.size,
  });

  const filterChanged = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    paging.reset();
  };

  return (
    <div>
      <PageHeader
        title={tp('directory.title')}
        extra={
          <PermissionGate anyOf={P.EMPLOYMENT_CREATE}>
            <Link to="/people/hires/new">
              <Button type="primary" icon={<UserAddOutlined />}>
                {tp('directory.hire')}
              </Button>
            </Link>
          </PermissionGate>
        }
      >
        <Space wrap>
          <Input.Search
            allowClear
            aria-label={t('action.search')}
            placeholder={tp('directory.searchPlaceholder')}
            style={{ width: 320 }}
            onSearch={(v) => filterChanged(setQ)(v.trim())}
          />
          <EmploymentStatusSelect
            aria-label={t('field.status')}
            placeholder={tp('directory.allStatuses')}
            style={{ width: 180 }}
            value={status}
            onChange={filterChanged(setStatus)}
          />
          <LegalEntitySelect
            aria-label={tp('common.legalEntity')}
            placeholder={tp('directory.allLegalEntities')}
            style={{ width: 240 }}
            value={legalEntityId}
            onChange={(v) => {
              filterChanged(setLegalEntityId)(v);
              setOrgUnitId(undefined);
            }}
          />
          <OrgUnitSelect
            aria-label={tp('common.orgUnit')}
            placeholder={tp('directory.allOrgUnits')}
            style={{ width: 260 }}
            legalEntityId={legalEntityId}
            value={orgUnitId}
            onChange={filterChanged(setOrgUnitId)}
          />
          <DatePicker
            aria-label={tp('common.asOf')}
            placeholder={tp('common.asOf')}
            format={DATE_FORMAT}
            value={asOf}
            onChange={filterChanged(setAsOf)}
          />
        </Space>
      </PageHeader>

      <DataTable<EmployeeRow>
        rowKey="employmentId"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        paging={{ page: paging.page, size: paging.size, onChange: paging.onChange }}
        onRow={(row) => ({
          style: { cursor: 'pointer' },
          onClick: () => void navigate({ to: '/people/employees/$employmentId', params: { employmentId: row.employmentId } }),
        })}
        columns={[
          {
            title: tp('common.employeeNo'),
            dataIndex: 'employeeNo',
            render: (no: string, row) => (
              <Link
                to="/people/employees/$employmentId"
                params={{ employmentId: row.employmentId }}
                onClick={(e) => e.stopPropagation()}
              >
                {no}
              </Link>
            ),
          },
          { title: tp('common.fullName'), dataIndex: 'fullName' },
          { title: tp('common.nationalId'), dataIndex: 'nationalIdMasked', render: (v: string | null) => <MaskedValue value={v} /> },
          { title: tp('common.legalEntity'), dataIndex: 'legalEntityId', render: (id: UUID | null) => <LegalEntityName id={id} /> },
          { title: tp('common.orgUnit'), dataIndex: 'orgUnitId', render: (id: UUID | null) => <OrgUnitName id={id} /> },
          { title: tp('common.jobTitle'), dataIndex: 'jobTitleId', render: (id: UUID | null) => <JobTitleName id={id} /> },
          { title: t('field.status'), dataIndex: 'status', render: (s: EmploymentStatus) => <EmploymentStatusTag status={s} /> },
          { title: tp('common.hireDate'), dataIndex: 'hireDate', render: (v: string | null) => formatDate(v) },
          { title: tp('common.endDate'), dataIndex: 'endDate', render: (v: string | null) => formatDate(v) },
        ]}
      />
    </div>
  );
}
