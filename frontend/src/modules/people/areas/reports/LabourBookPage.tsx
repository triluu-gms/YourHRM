import { useState } from 'react';
import { Button, DatePicker, Empty, Space, Typography } from 'antd';
import { FileExcelOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatMoney } from '@/shared/format';
import { LegalEntitySelect, OrgUnitName, toIsoDate } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import type { LabourBookEntry } from './api';
import { useExportLabourBook, useLabourBook, useLabourBookEntryPdf } from './queries';
import { IdentityNotice, useLegalEntityChoice } from './shared';
import './i18n';

/** Electronic labour book of a legal entity as of a date (US-PPL-068): masked on screen, Excel export, entry PDF. */
export function LabourBookPage() {
  const { t } = useTranslation();
  const { t: tr } = useTranslation('people-reports');
  const fb = useFeedback();
  const [legalEntityId, setLegalEntityId] = useLegalEntityChoice();
  const [asOf, setAsOf] = useState<Dayjs | null>(null);
  const asOfIso = toIsoDate(asOf) ?? undefined;
  const query = useLabourBook(legalEntityId, asOfIso);
  const exporter = useExportLabourBook();
  const entryPdf = useLabourBookEntryPdf();
  const book = query.data;

  const run = async (fn: () => Promise<unknown>, done: string) => {
    try {
      await fn();
      fb.done(done);
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader
        title={tr('labourBook.title')}
        subTitle={tr('labourBook.intro')}
        extra={
          <PermissionGate anyOf={P.STATUTORY_REPORT_EXECUTE}>
            <Button
              icon={<FileExcelOutlined />}
              disabled={!legalEntityId}
              loading={exporter.isPending}
              onClick={() =>
                void run(
                  () => exporter.mutateAsync({ legalEntityId: legalEntityId as UUID, asOf: asOfIso }),
                  tr('common.exported'),
                )
              }
            >
              {tr('common.export')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap>
          <LegalEntitySelect
            aria-label={tr('common.legalEntity')}
            allowClear={false}
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
          {book ? (
            <Typography.Text type="secondary">
              {tr('labourBook.count', { count: book.entries.length })}
            </Typography.Text>
          ) : null}
        </Space>
      </PageHeader>

      {!legalEntityId ? (
        <Empty description={tr('common.selectLegalEntity')} />
      ) : (
        <>
          {book ? <IdentityNotice revealed={book.identityRevealed} /> : null}
          <DataTable<LabourBookEntry>
            rowKey="employmentId"
            size="small"
            data={book?.entries}
            isLoading={query.isLoading}
            error={query.error}
            columns={[
              {
                title: tr('common.employeeNo'),
                dataIndex: 'employeeNo',
                fixed: 'left',
                render: (no: string, e) => (
                  <Link to="/people/employees/$employmentId" params={{ employmentId: e.employmentId }}>
                    {no}
                  </Link>
                ),
              },
              { title: tr('common.fullName'), dataIndex: 'fullName' },
              { title: tr('labourBook.gender'), dataIndex: 'genderCode' },
              {
                title: tr('labourBook.birthDate'),
                dataIndex: 'birthDate',
                render: (v: string | null) => formatDate(v),
              },
              {
                title: tr('labourBook.nationalId'),
                dataIndex: 'nationalId',
                render: (v: string | null) => (v ? <code>{v}</code> : '—'),
              },
              {
                title: tr('labourBook.jobTitle'),
                key: 'job',
                render: (_, e) => e.jobTitle ?? <OrgUnitName id={e.orgUnitId} />,
              },
              {
                title: tr('labourBook.contract'),
                key: 'contract',
                render: (_, e) => [e.contractTypeCode, e.contractNo].filter(Boolean).join(' · ') || '—',
              },
              {
                title: tr('labourBook.contractPeriod'),
                key: 'contractPeriod',
                render: (_, e) =>
                  e.contractStartDate
                    ? `${formatDate(e.contractStartDate)} – ${formatDate(e.contractEndDate) || '…'}`
                    : '—',
              },
              {
                title: tr('labourBook.salary'),
                key: 'salary',
                align: 'right',
                render: (_, e) =>
                  e.salary?.baseAmount != null
                    ? formatMoney(e.salary.baseAmount, e.salary.currencyCode ?? 'VND')
                    : '—',
              },
              {
                title: tr('labourBook.si'),
                dataIndex: 'siParticipating',
                render: (v: boolean) => (v ? t('common.yes') : t('common.no')),
              },
              {
                title: tr('labourBook.hireDate'),
                dataIndex: 'hireDate',
                render: (v: string | null) => formatDate(v),
              },
              {
                title: tr('labourBook.terminationDate'),
                dataIndex: 'terminationDate',
                render: (v: string | null) => formatDate(v),
              },
              { title: tr('labourBook.separationType'), dataIndex: 'separationType' },
              {
                title: t('field.actions'),
                key: 'actions',
                render: (_, e) => (
                  <PermissionGate anyOf={P.STATUTORY_REPORT_EXECUTE}>
                    <Button
                      type="link"
                      size="small"
                      onClick={() =>
                        void run(
                          () => entryPdf.mutateAsync({ employmentId: e.employmentId, asOf: asOfIso }),
                          tr('common.pdfReady'),
                        )
                      }
                    >
                      {tr('labourBook.entryPdf')}
                    </Button>
                  </PermissionGate>
                ),
              },
            ]}
          />
          {book?.notes && book.notes.length > 0 ? (
            <ul style={{ marginTop: 12 }}>
              {book.notes.map((n, i) => (
                <li key={i}>
                  <Typography.Text type="secondary">{n}</Typography.Text>
                </li>
              ))}
            </ul>
          ) : null}
        </>
      )}
    </div>
  );
}
