import { useEffect, useState } from 'react';
import { DatePicker, Space, Tag } from 'antd';
import { Link } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, PageHeader, usePaging } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatMoney } from '@/shared/format';
import { LegalEntityName, LegalEntitySelect, toIsoDate } from '../../common';
import type { SalaryListItem } from './api';
import { needsPurpose, useSalaryList, useSalaryPurpose } from './queries';
import { SalaryPurposeBanner, SalaryPurposeForm } from './SalaryPurpose';
import './i18n';

/** Current salary of every employee in scope (GET /people/salary-records); every view is audited with its purpose. */
export function SalaryRecordsPage() {
  const { t: te } = useTranslation('people-employment');
  const [purpose] = useSalaryPurpose();
  return (
    <div>
      <PageHeader title={te('salaryList.title')} />
      {purpose ? <SalaryList purpose={purpose} /> : <SalaryPurposeForm />}
    </div>
  );
}

function SalaryList({ purpose }: { purpose: string }) {
  const { t: te } = useTranslation('people-employment');
  const { t: tp } = useTranslation('people');
  const [, setPurpose] = useSalaryPurpose();
  const paging = usePaging(50);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [asOf, setAsOf] = useState<Dayjs | null>(null);
  const query = useSalaryList({
    purpose,
    legalEntityId,
    asOf: toIsoDate(asOf) ?? undefined,
    limit: paging.size,
    offset: paging.page * paging.size,
  });

  useEffect(() => {
    if (needsPurpose(query.error)) setPurpose(undefined);
  }, [query.error, setPurpose]);

  const items = query.data ?? [];
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <SalaryPurposeBanner />
      <Space wrap>
        <LegalEntitySelect
          aria-label={te('salaryList.legalEntity')}
          placeholder={te('salaryList.allLegalEntities')}
          style={{ width: 280 }}
          value={legalEntityId}
          onChange={(v) => {
            setLegalEntityId(v);
            paging.reset();
          }}
        />
        <DatePicker
          aria-label={te('salaryList.asOf')}
          placeholder={te('salaryList.asOf')}
          format={DATE_FORMAT}
          value={asOf}
          onChange={(d) => {
            setAsOf(d);
            paging.reset();
          }}
        />
      </Space>
      <DataTable<SalaryListItem>
        rowKey="employmentId"
        data={items}
        isLoading={query.isLoading}
        error={query.error}
        paging={{ page: paging.page, size: paging.size, onChange: paging.onChange, hasMore: items.length === paging.size }}
        columns={[
          {
            title: tp('common.employeeNo'),
            dataIndex: 'employeeNo',
            render: (no: string, r) => (
              <Link to="/people/employees/$employmentId" params={{ employmentId: r.employmentId }} search={{ tab: 'compensation' }}>
                {no}
              </Link>
            ),
          },
          { title: tp('common.fullName'), dataIndex: 'fullName' },
          { title: tp('common.legalEntity'), dataIndex: 'legalEntityId', render: (id: UUID) => <LegalEntityName id={id} /> },
          { title: te('salary.payBasis'), key: 'basis', render: (_, r) => <Tag color={r.salary.payBasis === 'NET' ? 'purple' : 'blue'}>{r.salary.payBasis}</Tag> },
          {
            title: te('salary.baseSalary'),
            key: 'base',
            align: 'right',
            render: (_, r) =>
              r.salary.amountsMasked ? '••••••' : `${formatMoney(r.salary.baseSalaryAmount, r.salary.currencyCode)} ${r.salary.currencyCode}`,
          },
          {
            title: te('salary.agreedNet'),
            key: 'net',
            align: 'right',
            render: (_, r) =>
              r.salary.payBasis !== 'NET'
                ? null
                : r.salary.amountsMasked
                  ? '••••••'
                  : `${formatMoney(r.salary.agreedNetAmount, r.salary.currencyCode)} ${r.salary.currencyCode}`,
          },
          {
            title: te('salary.siSalary'),
            key: 'si',
            align: 'right',
            render: (_, r) => (r.salary.amountsMasked ? '••••••' : formatMoney(r.salary.siSalaryAmount, r.salary.currencyCode)),
          },
          { title: te('salaryList.validFrom'), key: 'from', render: (_, r) => formatDate(r.salary.validFrom) },
        ]}
      />
    </Space>
  );
}
