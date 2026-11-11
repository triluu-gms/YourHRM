import { useState } from 'react';
import { Button, Card, Checkbox, DatePicker, Space, Tag, Typography } from 'antd';
import { FileExcelOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, PageHeader, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatMoney, formatNumber } from '@/shared/format';
import {
  EmploymentStatusTag,
  LegalEntityName,
  LegalEntitySelect,
  toIsoDate,
  type EmploymentStatus,
} from '../../common';
import type { OpeningBalanceDiscrepancy, OpeningBalanceParams, OpeningBalanceSummary } from './api';
import { useExportOpeningBalance, useOpeningBalanceReport } from './queries';
import './i18n';

const warn = (n: number) =>
  n > 0 ? <Typography.Text type="warning">{formatNumber(n)}</Typography.Text> : formatNumber(n);

/** Reconciliation and data-quality report R20 of the opening-balance employee load (US-PPL-093), with Excel export. */
export function OpeningBalancePage() {
  const { t: tr } = useTranslation('people-reports');
  const fb = useFeedback();
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [asOf, setAsOf] = useState<Dayjs | null>(null);
  const [openingLoadOnly, setOpeningLoadOnly] = useState(true);
  const params: OpeningBalanceParams = { legalEntityId, asOf: toIsoDate(asOf) ?? undefined, openingLoadOnly };
  const query = useOpeningBalanceReport(params);
  const exporter = useExportOpeningBalance();
  const report = query.data;

  const onExport = async () => {
    try {
      await exporter.mutateAsync(params);
      fb.done(tr('common.exported'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader
        title={tr('openingBalance.title')}
        subTitle={tr('openingBalance.intro')}
        extra={
          <Button icon={<FileExcelOutlined />} loading={exporter.isPending} onClick={() => void onExport()}>
            {tr('common.export')}
          </Button>
        }
      >
        <Space wrap>
          <LegalEntitySelect
            aria-label={tr('common.legalEntity')}
            placeholder={tr('openingBalance.allLegalEntities')}
            style={{ width: 320 }}
            value={legalEntityId}
            onChange={setLegalEntityId}
          />
          <DatePicker
            aria-label={tr('common.asOf')}
            placeholder={tr('common.asOf')}
            format={DATE_FORMAT}
            value={asOf}
            onChange={setAsOf}
          />
          <Checkbox checked={openingLoadOnly} onChange={(e) => setOpeningLoadOnly(e.target.checked)}>
            {tr('openingBalance.openingLoadOnly')}
          </Checkbox>
        </Space>
      </PageHeader>

      <Card title={tr('openingBalance.summary')} style={{ marginBottom: 16 }}>
        <DataTable<OpeningBalanceSummary>
          size="small"
          rowKey="legalEntityId"
          data={report?.legalEntities}
          isLoading={query.isLoading}
          error={query.error}
          columns={[
            {
              title: tr('common.legalEntity'),
              dataIndex: 'legalEntityId',
              render: (id: UUID) => <LegalEntityName id={id} />,
            },
            {
              title: tr('openingBalance.employees'),
              dataIndex: 'employees',
              align: 'right',
              render: formatNumber,
            },
            {
              title: tr('openingBalance.activeHeadcount'),
              dataIndex: 'activeHeadcount',
              align: 'right',
              render: formatNumber,
            },
            {
              title: tr('openingBalance.preHires'),
              dataIndex: 'preHires',
              align: 'right',
              render: formatNumber,
            },
            {
              title: tr('openingBalance.salaryTotal'),
              dataIndex: 'salaryTotal',
              align: 'right',
              render: (v: number | null) =>
                report?.salaryMasked || v == null ? (
                  <Typography.Text type="secondary">{tr('openingBalance.salaryMasked')}</Typography.Text>
                ) : (
                  formatMoney(v)
                ),
            },
            {
              title: tr('openingBalance.dependantCount'),
              dataIndex: 'dependants',
              align: 'right',
              render: formatNumber,
            },
            {
              title: tr('openingBalance.missingTaxCode'),
              dataIndex: 'missingTaxCode',
              align: 'right',
              render: warn,
            },
            {
              title: tr('openingBalance.missingSiNumber'),
              dataIndex: 'missingSiNumber',
              align: 'right',
              render: warn,
            },
            {
              title: tr('openingBalance.missingBankAccount'),
              dataIndex: 'missingBankAccount',
              align: 'right',
              render: warn,
            },
          ]}
        />
      </Card>

      <Card title={tr('openingBalance.discrepancies')}>
        <DataTable<OpeningBalanceDiscrepancy>
          size="small"
          rowKey="employmentId"
          data={report?.discrepancies}
          isLoading={query.isLoading}
          locale={{ emptyText: tr('openingBalance.noIssues') }}
          columns={[
            {
              title: tr('common.employeeNo'),
              dataIndex: 'employeeNo',
              render: (no: string, d) => (
                <Link to="/people/employees/$employmentId" params={{ employmentId: d.employmentId }}>
                  {no}
                </Link>
              ),
            },
            { title: tr('common.fullName'), dataIndex: 'fullName' },
            {
              title: tr('common.legalEntity'),
              dataIndex: 'legalEntityId',
              render: (id: UUID) => <LegalEntityName id={id} />,
            },
            {
              title: tr('common.status'),
              dataIndex: 'status',
              render: (s: string) => <EmploymentStatusTag status={s as EmploymentStatus} />,
            },
            {
              title: tr('openingBalance.issues'),
              dataIndex: 'issues',
              render: (issues: string[]) => (
                <Space size={[4, 4]} wrap>
                  {issues.map((i) => (
                    <Tag key={i} color="orange">
                      {i}
                    </Tag>
                  ))}
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
