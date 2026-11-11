import { useState } from 'react';
import { Alert, DatePicker, Space, Tag, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, PageHeader } from '@/shared/components';
import { DATE_FORMAT } from '@/shared/format';
import { LegalEntitySelect, toIsoDate } from '../../common';
import type { ConcurrentExceptionLine } from './api';
import { useConcurrentExceptions } from './queries';
import './i18n';

/** Concurrent-employment exception report (US-PPL-025): primary employment and insurance across legal entities. */
export function ConcurrentEmploymentPage() {
  const { t: te } = useTranslation('people-employment');
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [asOf, setAsOf] = useState<Dayjs | null>(null);
  const query = useConcurrentExceptions(legalEntityId, toIsoDate(asOf) ?? undefined);

  return (
    <div>
      <PageHeader title={te('concurrent.title')} subTitle={te('concurrent.intro')}>
        <Space wrap>
          <LegalEntitySelect
            aria-label={te('salaryList.legalEntity')}
            placeholder={te('salaryList.legalEntity')}
            style={{ width: 280 }}
            value={legalEntityId}
            onChange={setLegalEntityId}
          />
          <DatePicker aria-label={te('salaryList.asOf')} placeholder={te('salaryList.asOf')} format={DATE_FORMAT} value={asOf} onChange={setAsOf} />
          {query.data ? <Typography.Text type="secondary">{te('concurrent.checked', { count: query.data.personsChecked })}</Typography.Text> : null}
        </Space>
      </PageHeader>

      {!legalEntityId ? (
        <Alert type="info" showIcon message={te('concurrent.legalEntityRequired')} />
      ) : (
        <DataTable<ConcurrentExceptionLine>
          rowKey={(r) => `${r.code}:${r.personId}:${r.employmentId ?? ''}:${r.insuranceType ?? ''}`}
          data={query.data?.exceptions}
          isLoading={query.isLoading}
          error={query.error}
          locale={{ emptyText: te('concurrent.none') }}
          columns={[
            { title: te('concurrent.code'), dataIndex: 'code', render: (c: string) => <Tag color="orange">{c}</Tag> },
            { title: te('concurrent.person'), dataIndex: 'fullName' },
            {
              title: te('concurrent.employment'),
              dataIndex: 'employeeNo',
              render: (no: string | null, r) =>
                r.employmentId ? (
                  <Link to="/people/employees/$employmentId" params={{ employmentId: r.employmentId }}>
                    {no ?? r.employmentId}
                  </Link>
                ) : null,
            },
            {
              title: te('concurrent.insuranceType'),
              dataIndex: 'insuranceType',
              render: (x: string | null) => (x ? te(`insurance.types.${x}`, { defaultValue: x }) : null),
            },
            { title: te('concurrent.detail'), dataIndex: 'detail' },
          ]}
        />
      )}
    </div>
  );
}
