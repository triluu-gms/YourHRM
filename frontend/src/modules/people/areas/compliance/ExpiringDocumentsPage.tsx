import { useState } from 'react';
import { InputNumber, Space, Tabs, Tag, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { DataTable, PageHeader } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { MaskedValue } from '../../common';
import type { ExpiringIdentityDocument, ExpiringPermit } from './api';
import { PersonLink } from './PersonLink';
import { useExpiringIdentityDocuments, useExpiringWorkPermits } from './queries';
import './i18n';

function DaysLeft({ days }: { days: number }) {
  const { t: tc } = useTranslation('people-compliance');
  if (days < 0) return <Tag color="red">{tc('expiring.expired')}</Tag>;
  return <Tag color={days <= 15 ? 'red' : days <= 30 ? 'orange' : 'gold'}>{days}</Tag>;
}

/** Identity documents and work permits of in-scope employees expiring within N days (US-PPL-009/011). */
export function ExpiringDocumentsPage() {
  const { t: tc } = useTranslation('people-compliance');
  const [withinDays, setWithinDays] = useState(60);
  const identity = useExpiringIdentityDocuments(withinDays);
  const permits = useExpiringWorkPermits(withinDays);

  return (
    <div>
      <PageHeader title={tc('expiring.title')}>
        <Space>
          <Typography.Text>{tc('expiring.withinDays')}</Typography.Text>
          <InputNumber
            aria-label={tc('expiring.withinDays')}
            min={0}
            max={3660}
            precision={0}
            value={withinDays}
            onChange={(v) => setWithinDays(v ?? 60)}
          />
        </Space>
      </PageHeader>
      <Tabs
        items={[
          {
            key: 'identity',
            label: `${tc('expiring.identityTab')} (${identity.data?.length ?? 0})`,
            children: (
              <DataTable<ExpiringIdentityDocument>
                rowKey="documentId"
                data={identity.data}
                isLoading={identity.isLoading}
                error={identity.error}
                columns={[
                  { title: tc('expiring.employee'), key: 'person', render: (_, d) => <PersonLink personId={d.personId} name={d.fullName} /> },
                  { title: tc('expiring.employeeNos'), dataIndex: 'employeeNos', render: (v: string[]) => v.join(', ') },
                  { title: tc('expiring.docType'), dataIndex: 'docType' },
                  { title: tc('expiring.docNo'), dataIndex: 'docNoMasked', render: (v: string | null) => <MaskedValue value={v} /> },
                  { title: tc('expiring.expiryDate'), dataIndex: 'expiryDate', render: (v: string) => formatDate(v) },
                  { title: tc('expiring.daysLeft'), dataIndex: 'daysLeft', align: 'right', render: (d: number) => <DaysLeft days={d} /> },
                ]}
              />
            ),
          },
          {
            key: 'permits',
            label: `${tc('expiring.permitTab')} (${permits.data?.length ?? 0})`,
            children: (
              <DataTable<ExpiringPermit>
                rowKey="permitId"
                data={permits.data}
                isLoading={permits.isLoading}
                error={permits.error}
                columns={[
                  {
                    title: tc('expiring.employee'),
                    key: 'person',
                    render: (_, p) =>
                      p.employmentId ? (
                        <Link to="/people/employees/$employmentId" params={{ employmentId: p.employmentId }} search={{ tab: 'workPermits' }}>
                          {p.fullName}
                        </Link>
                      ) : (
                        <PersonLink personId={p.personId} name={p.fullName} />
                      ),
                  },
                  { title: tc('expiring.employeeNos'), dataIndex: 'employeeNo', render: (v: string | null) => v ?? '—' },
                  { title: tc('permits.type'), dataIndex: 'permitType', render: (v: string) => tc(`permits.types.${v}`, { defaultValue: v }) },
                  { title: tc('expiring.lastValidDay'), dataIndex: 'lastValidDay', render: (v: string) => formatDate(v) },
                  {
                    title: tc('expiring.daysLeft'),
                    dataIndex: 'daysLeft',
                    align: 'right',
                    render: (d: number, p) => (
                      <Space size={4}>
                        <DaysLeft days={d} />
                        {p.renewalInProgress ? <Tag color="blue">{tc('expiring.renewal')}</Tag> : null}
                      </Space>
                    ),
                  },
                ]}
              />
            ),
          },
        ]}
      />
    </div>
  );
}
