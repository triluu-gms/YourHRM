import { useState } from 'react';
import { Button, DatePicker, Empty, Space, Statistic } from 'antd';
import { FileExcelOutlined, FilePdfOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader, useFeedback } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { CodeLabel, LegalEntitySelect } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import type { DependantRegistrationLine, MonthRange } from './api';
import {
  useDependantRegistrations,
  useDependantRegistrationsPdf,
  useExportDependantRegistrations,
} from './queries';
import { IdentityNotice, useLegalEntityChoice } from './shared';
import './i18n';

const monthLabel = (iso: string | null | undefined) => (iso ? dayjs(iso).format('MM/YYYY') : '—');

/** PIT dependant registration declaration per legal entity and months (US-PPL-030): masked view, Excel, PDF. */
export function DependantRegistrationsPage() {
  const { t: tr } = useTranslation('people-reports');
  const fb = useFeedback();
  const [legalEntityId, setLegalEntityId] = useLegalEntityChoice();
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().startOf('year'), dayjs()]);
  const params: MonthRange | null = legalEntityId
    ? { legalEntityId, fromMonth: range[0].format('YYYY-MM'), toMonth: range[1].format('YYYY-MM') }
    : null;
  const query = useDependantRegistrations(params);
  const exporter = useExportDependantRegistrations();
  const pdf = useDependantRegistrationsPdf();
  const report = query.data;

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
        title={tr('dependants.title')}
        subTitle={tr('dependants.intro')}
        extra={
          <PermissionGate anyOf={P.STATUTORY_REPORT_EXECUTE}>
            <Button
              icon={<FileExcelOutlined />}
              disabled={!params}
              loading={exporter.isPending}
              onClick={() =>
                void run(() => exporter.mutateAsync(params as MonthRange), tr('common.exported'))
              }
            >
              {tr('common.export')}
            </Button>
            <Button
              icon={<FilePdfOutlined />}
              disabled={!params}
              loading={pdf.isPending}
              onClick={() => void run(() => pdf.mutateAsync(params as MonthRange), tr('common.pdfReady'))}
            >
              {tr('common.pdf')}
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
            onChange={(v: UUID) => setLegalEntityId(v)}
          />
          <DatePicker.RangePicker
            picker="month"
            format="MM/YYYY"
            aria-label={tr('dependants.range')}
            allowClear={false}
            value={range}
            onChange={(v) => {
              if (v?.[0] && v[1]) setRange([v[0], v[1]]);
            }}
          />
        </Space>
      </PageHeader>

      {!params ? (
        <Empty description={tr('common.selectLegalEntity')} />
      ) : (
        <>
          {report ? (
            <>
              <Space size="large" style={{ marginBottom: 12 }}>
                <Statistic title={tr('dependants.registered')} value={report.registeredCount} />
                <Statistic title={tr('dependants.ended')} value={report.endedCount} />
              </Space>
              <IdentityNotice revealed={report.unmasked} />
            </>
          ) : null}
          <DataTable<DependantRegistrationLine>
            size="small"
            rowKey={(l) => `${l.no}:${l.dependantId}`}
            data={report?.lines}
            isLoading={query.isLoading}
            error={query.error}
            columns={[
              { title: tr('dependants.no'), dataIndex: 'no', align: 'right' },
              { title: tr('dependants.changeType'), dataIndex: 'changeType' },
              { title: tr('common.employeeNo'), dataIndex: 'employeeNo' },
              { title: tr('dependants.taxpayer'), dataIndex: 'taxpayerName' },
              {
                title: tr('dependants.taxpayerTaxCode'),
                dataIndex: 'taxpayerTaxCode',
                render: (v: string | null) => (v ? <code>{v}</code> : '—'),
              },
              { title: tr('dependants.dependant'), dataIndex: 'dependantName' },
              {
                title: tr('dependants.birthDate'),
                dataIndex: 'birthDate',
                render: (v: string | null) => formatDate(v),
              },
              {
                title: tr('dependants.dependantIdNo'),
                dataIndex: 'dependantIdNo',
                render: (v: string | null) => (v ? <code>{v}</code> : '—'),
              },
              {
                title: tr('dependants.relationship'),
                dataIndex: 'relationshipCode',
                render: (c: string | null) => <CodeLabel list="RELATIONSHIP" code={c} />,
              },
              { title: tr('dependants.reason'), dataIndex: 'reasonCode' },
              { title: tr('dependants.fromMonth'), dataIndex: 'fromMonth', render: monthLabel },
              { title: tr('dependants.toMonth'), dataIndex: 'toMonth', render: monthLabel },
              { title: tr('common.status'), dataIndex: 'status' },
            ]}
          />
        </>
      )}
    </div>
  );
}
