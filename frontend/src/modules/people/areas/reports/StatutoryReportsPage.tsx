import { useState } from 'react';
import {
  AutoComplete,
  Button,
  Card,
  DatePicker,
  Empty,
  InputNumber,
  Select,
  Space,
  Statistic,
  Tabs,
  Typography,
} from 'antd';
import { FileAddOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, DescriptionList, PageHeader, ProblemAlert } from '@/shared/components';
import { formatDate, formatDateTime } from '@/shared/format';
import { LegalEntitySelect } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import {
  STATUTORY_REPORT_TYPES,
  type LabourMovement,
  type StatutoryReportType,
  type StatutoryReportView,
} from './api';
import { useLabourChange, useLabourFluctuation, useStatutoryReports } from './queries';
import { GenerateReportDrawer } from './GenerateReportDrawer';
import { CountMap, IdentityNotice, periodLabel, ReportStatusTag, useLegalEntityChoice } from './shared';
import './i18n';

/**
 * Statutory report register (US-PPL-069/070/071): reports of one legal entity with status and due date, generate,
 * and on-screen review of the 6-monthly labour change report and the monthly fluctuation notice.
 */
export function StatutoryReportsPage() {
  const { t } = useTranslation('people-reports');
  const navigate = useNavigate();
  const [legalEntityId, setLegalEntityId] = useLegalEntityChoice();
  const [generating, setGenerating] = useState(false);

  return (
    <div>
      <PageHeader
        title={t('statutory.title')}
        subTitle={t('statutory.intro')}
        extra={
          <PermissionGate anyOf={P.STATUTORY_REPORT_EXECUTE}>
            <Button type="primary" icon={<FileAddOutlined />} onClick={() => setGenerating(true)}>
              {t('statutory.generate')}
            </Button>
          </PermissionGate>
        }
      >
        <LegalEntitySelect
          aria-label={t('common.legalEntity')}
          placeholder={t('common.legalEntity')}
          allowClear={false}
          style={{ width: 320 }}
          value={legalEntityId}
          onChange={setLegalEntityId}
        />
      </PageHeader>

      {!legalEntityId ? (
        <Empty description={t('common.selectLegalEntity')} />
      ) : (
        <Card styles={{ body: { paddingTop: 0 } }}>
          <Tabs
            destroyOnHidden
            items={[
              {
                key: 'register',
                label: t('statutory.tabs.register'),
                children: <Register legalEntityId={legalEntityId} />,
              },
              {
                key: 'labourChange',
                label: t('statutory.tabs.labourChange'),
                children: <LabourChangePreview legalEntityId={legalEntityId} />,
              },
              {
                key: 'fluctuation',
                label: t('statutory.tabs.fluctuation'),
                children: <FluctuationPreview legalEntityId={legalEntityId} />,
              },
            ]}
          />
        </Card>
      )}

      {generating ? (
        <GenerateReportDrawer
          open
          onClose={() => setGenerating(false)}
          legalEntityId={legalEntityId}
          onGenerated={(g) =>
            void navigate({ to: '/people/statutory-reports/$reportId', params: { reportId: g.report.id } })
          }
        />
      ) : null}
    </div>
  );
}

function Register({ legalEntityId }: { legalEntityId: UUID }) {
  const { t } = useTranslation('people-reports');
  const navigate = useNavigate();
  const [reportType, setReportType] = useState<StatutoryReportType | undefined>();
  const [year, setYear] = useState<number | null>(null);
  const query = useStatutoryReports({ legalEntityId, reportType, year: year ?? undefined });

  return (
    <>
      <Space wrap style={{ marginBottom: 12 }}>
        <Select<StatutoryReportType>
          allowClear
          aria-label={t('statutory.type')}
          placeholder={t('statutory.allTypes')}
          style={{ width: 260 }}
          value={reportType}
          onChange={setReportType}
          options={STATUTORY_REPORT_TYPES.map((x) => ({ value: x, label: t(`statutory.types.${x}`) }))}
        />
        <InputNumber
          aria-label={t('common.year')}
          placeholder={t('common.year')}
          min={2000}
          max={2100}
          value={year}
          onChange={setYear}
        />
      </Space>
      <DataTable<StatutoryReportView>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        onRow={(r) => ({
          style: { cursor: 'pointer' },
          onClick: () =>
            void navigate({ to: '/people/statutory-reports/$reportId', params: { reportId: r.id } }),
        })}
        columns={[
          {
            title: t('statutory.type'),
            dataIndex: 'reportType',
            render: (type: StatutoryReportType, r) => (
              <Link
                to="/people/statutory-reports/$reportId"
                params={{ reportId: r.id }}
                onClick={(e) => e.stopPropagation()}
              >
                {t(`statutory.types.${type}`)}
              </Link>
            ),
          },
          { title: t('statutory.periodRange'), key: 'period', render: (_, r) => periodLabel(r) },
          {
            title: t('statutory.dueDate'),
            dataIndex: 'dueDate',
            render: (v: string | null) => formatDate(v),
          },
          {
            title: t('common.status'),
            dataIndex: 'status',
            render: (s: StatutoryReportView['status']) => <ReportStatusTag status={s} />,
          },
          { title: t('statutory.rows'), dataIndex: 'rowCount', align: 'right' },
          {
            title: t('statutory.generatedAt'),
            dataIndex: 'generatedAt',
            render: (v: string | null) => formatDateTime(v),
          },
          {
            title: t('statutory.submittedAt'),
            dataIndex: 'submittedAt',
            render: (v: string | null) => formatDateTime(v),
          },
        ]}
      />
    </>
  );
}

function LabourChangePreview({ legalEntityId }: { legalEntityId: UUID }) {
  const { t } = useTranslation('people-reports');
  const [year, setYear] = useState<number>(dayjs().year());
  const [period, setPeriod] = useState<string>(dayjs().month() < 6 ? 'JUNE' : 'DECEMBER');
  const [applied, setApplied] = useState<{ year: number; period: string } | null>(null);
  const query = useLabourChange(legalEntityId, applied?.year ?? year, applied?.period);
  const r = query.data;

  return (
    <>
      <Space wrap style={{ marginBottom: 12 }}>
        <InputNumber
          aria-label={t('common.year')}
          min={2000}
          max={2100}
          value={year}
          onChange={(v) => setYear(v ?? dayjs().year())}
        />
        <AutoComplete
          aria-label={t('common.period')}
          style={{ width: 180 }}
          value={period}
          onChange={setPeriod}
          options={['JUNE', 'DECEMBER'].map((p) => ({ value: p }))}
        />
        <Button
          type="primary"
          disabled={!period.trim()}
          onClick={() => setApplied({ year, period: period.trim() })}
        >
          {t('statutory.preview.load')}
        </Button>
      </Space>
      {query.error ? <ProblemAlert error={query.error} style={{ marginBottom: 12 }} /> : null}
      {r ? (
        <>
          <Typography.Title level={5}>
            {r.titleVi} {r.formCode ? `· ${t('statutory.preview.form', { code: r.formCode })}` : ''} ·{' '}
            {periodLabel(r)}
            {r.dueDate ? ` · ${t('statutory.preview.dueDate', { date: formatDate(r.dueDate) })}` : ''}
          </Typography.Title>
          <Space size="large" wrap style={{ marginBottom: 16 }}>
            <Statistic title={t('statutory.preview.headcountStart')} value={r.headcountStart} />
            <Statistic title={t('statutory.preview.hires')} value={r.hires} />
            <Statistic title={t('statutory.preview.terminations')} value={r.terminations} />
            <Statistic title={t('statutory.preview.headcountEnd')} value={r.headcountEnd} />
            <Statistic title={t('statutory.preview.femaleEnd')} value={r.femaleEnd} />
          </Space>
          <DescriptionList
            column={1}
            items={[
              { label: t('statutory.preview.hiresByType'), value: <CountMap value={r.hiresByType} /> },
              {
                label: t('statutory.preview.terminationsByType'),
                value: <CountMap value={r.terminationsByType} />,
              },
              {
                label: t('statutory.preview.contractTypesEnd'),
                value: <CountMap value={r.contractTypesEnd} />,
              },
              {
                label: t('statutory.preview.qualificationsEnd'),
                value: <CountMap value={r.qualificationsEnd} />,
              },
            ]}
          />
        </>
      ) : null}
    </>
  );
}

function FluctuationPreview({ legalEntityId }: { legalEntityId: UUID }) {
  const { t } = useTranslation('people-reports');
  const [month, setMonth] = useState<Dayjs | null>(dayjs().subtract(1, 'month'));
  const query = useLabourFluctuation(legalEntityId, month ? month.format('YYYY-MM') : undefined);
  const r = query.data;

  return (
    <>
      <Space wrap style={{ marginBottom: 12 }}>
        <DatePicker
          picker="month"
          format="MM/YYYY"
          aria-label={t('common.month')}
          value={month}
          onChange={setMonth}
        />
      </Space>
      {query.error ? <ProblemAlert error={query.error} style={{ marginBottom: 12 }} /> : null}
      {r ? (
        <>
          <Typography.Title level={5}>
            {r.titleVi} {r.formCode ? `· ${t('statutory.preview.form', { code: r.formCode })}` : ''} ·{' '}
            {periodLabel(r)}
            {r.dueDate ? ` · ${t('statutory.preview.dueDate', { date: formatDate(r.dueDate) })}` : ''}
          </Typography.Title>
          <Space size="large" wrap style={{ marginBottom: 12 }}>
            <Statistic title={t('statutory.preview.hires')} value={r.hires} />
            <Statistic title={t('statutory.preview.terminations')} value={r.terminations} />
          </Space>
          <IdentityNotice revealed={r.identityRevealed} />
          <DataTable<LabourMovement>
            size="small"
            rowKey={(m) => `${m.employmentId}:${m.movementType}:${m.movementDate}`}
            data={r.rows}
            columns={[
              { title: t('common.employeeNo'), dataIndex: 'employeeNo' },
              { title: t('common.fullName'), dataIndex: 'fullName' },
              { title: t('statutory.preview.movementType'), dataIndex: 'movementType' },
              {
                title: t('statutory.preview.movementDate'),
                dataIndex: 'movementDate',
                render: (v: string) => formatDate(v),
              },
              {
                title: t('statutory.preview.birthDate'),
                dataIndex: 'birthDate',
                render: (v: string | null) => formatDate(v),
              },
              {
                title: t('statutory.preview.nationalId'),
                dataIndex: 'nationalId',
                render: (v: string | null) => (v ? <code>{v}</code> : '—'),
              },
              {
                title: t('statutory.preview.siNumber'),
                dataIndex: 'siNumber',
                render: (v: string | null) => (v ? <code>{v}</code> : '—'),
              },
              { title: t('statutory.preview.jobTitle'), dataIndex: 'jobTitle' },
              { title: t('statutory.preview.contractType'), dataIndex: 'contractTypeCode' },
              { title: t('statutory.preview.reason'), dataIndex: 'reasonCode' },
              { title: t('statutory.preview.legalGround'), dataIndex: 'legalGroundCode' },
            ]}
          />
        </>
      ) : null}
    </>
  );
}
