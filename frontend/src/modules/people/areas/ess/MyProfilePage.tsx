import { Alert, Card, Collapse, Empty, Result, Skeleton, Space, Table, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { DescriptionList, PageHeader, ProblemAlert } from '@/shared/components';
import { formatDate, formatMoney, formatValidity } from '@/shared/format';
import {
  CodeLabel,
  CountryName,
  EmploymentStatusTag,
  GradeName,
  JobTitleName,
  LegalEntityName,
  LocationName,
  MaskedValue,
  OrgUnitName,
  type EmploymentStatus,
} from '../../common';
import type { MyAllowance, MyEmployment, MyProfile } from './api';
import { useMyEmployments, useMyProfile } from './queries';
import { DocumentsTable } from './MyDocumentsPage';
import './i18n';

/** "My HR profile" (US-PPL-072/073): own personal data (masked identifiers) and own employments. Read-only. */
export function MyProfilePage() {
  const { t: te } = useTranslation('people-ess');
  const profile = useMyProfile();
  const employments = useMyEmployments();

  if (profile.isLoading) return <Skeleton active />;
  if (profile.error || !profile.data) {
    return <Result status="warning" title={te('profile.title')} extra={<ProblemAlert error={profile.error} />} />;
  }
  const p = profile.data;

  return (
    <div>
      <PageHeader title={te('profile.title')} subTitle={p.fullName} />
      <Alert type="info" showIcon message={te('profile.correctionHint')} style={{ marginBottom: 16 }} />
      <PersonalCard profile={p} />
      <ProfileLists profile={p} />

      <Typography.Title level={4} style={{ marginTop: 24 }}>
        {te('employment.title')}
      </Typography.Title>
      {employments.error ? <ProblemAlert error={employments.error} /> : null}
      {employments.isLoading ? <Skeleton active /> : null}
      {employments.data && employments.data.length === 0 ? <Empty description={te('employment.none')} /> : null}
      {(employments.data ?? []).map((e) => (
        <EmploymentCard key={e.employment.employmentId} employment={e} />
      ))}
    </div>
  );
}

function PersonalCard({ profile: p }: { profile: MyProfile }) {
  const { t: te } = useTranslation('people-ess');
  return (
    <Card title={te('profile.personal')} style={{ marginBottom: 16 }}>
      <DescriptionList
        items={[
          { label: te('profile.fullName'), value: p.fullName },
          { label: te('profile.preferredName'), value: p.preferredName, hideEmpty: true },
          { label: te('profile.birthDate'), value: formatDate(p.birthDate) },
          { label: te('profile.gender'), value: <CodeLabel list="GENDER" code={p.genderCode} /> },
          { label: te('profile.nationality'), value: <CountryName code={p.nationalityCode} nationality /> },
          { label: te('profile.nationalId'), value: <MaskedValue value={p.nationalIdMasked} /> },
          { label: te('profile.taxCode'), value: <MaskedValue value={p.taxCodeMasked} /> },
          { label: te('profile.siNumber'), value: <MaskedValue value={p.siNumberMasked} /> },
          { label: te('profile.hiCardNo'), value: <MaskedValue value={p.hiCardNoMasked} /> },
        ]}
      />
      <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
        {te('profile.maskedHint')}
      </Typography.Text>
    </Card>
  );
}

function ProfileLists({ profile: p }: { profile: MyProfile }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const yes = (v: boolean) => (v ? <Tag color="green">{t('common.yes')}</Tag> : null);
  const validity = (from?: string | null, to?: string | null) => (from ? formatValidity(from, to, t('common.openEnded')) : '—');

  return (
    <Collapse
      style={{ marginBottom: 16 }}
      defaultActiveKey={['contacts', 'addresses']}
      items={[
        {
          key: 'identityDocuments',
          label: `${te('profile.identityDocuments')} (${p.identityDocuments.length})`,
          children: (
            <Table
              size="small"
              rowKey={(r, i) => `${r.docType}-${i}`}
              pagination={false}
              dataSource={p.identityDocuments}
              columns={[
                { title: te('profile.docType'), dataIndex: 'docType' },
                { title: te('profile.docNo'), dataIndex: 'docNoMasked', render: (v: string | null) => <MaskedValue value={v} /> },
                { title: te('profile.issueDate'), dataIndex: 'issueDate', render: (v: string | null) => formatDate(v) },
                { title: te('profile.expiryDate'), dataIndex: 'expiryDate', render: (v: string | null) => formatDate(v) },
                { title: t('field.status'), dataIndex: 'status' },
              ]}
            />
          ),
        },
        {
          key: 'addresses',
          label: `${te('profile.addresses')} (${p.addresses.length})`,
          children: (
            <Table
              size="small"
              rowKey={(r, i) => `${r.addressType}-${i}`}
              pagination={false}
              dataSource={p.addresses}
              columns={[
                { title: te('profile.addressType'), dataIndex: 'addressType' },
                { title: te('profile.address'), dataIndex: 'addressLine' },
                { title: te('profile.validity'), key: 'v', render: (_, r) => validity(r.validFrom, r.validTo) },
              ]}
            />
          ),
        },
        {
          key: 'contacts',
          label: `${te('profile.contacts')} (${p.contacts.length})`,
          children: (
            <Table
              size="small"
              rowKey={(r, i) => `${r.contactType}-${i}`}
              pagination={false}
              dataSource={p.contacts}
              columns={[
                { title: te('profile.contactType'), dataIndex: 'contactType' },
                { title: te('profile.contactValue'), dataIndex: 'contactValue' },
                { title: te('profile.primary'), dataIndex: 'primary', render: yes },
                { title: te('profile.verified'), dataIndex: 'verified', render: yes },
              ]}
            />
          ),
        },
        {
          key: 'qualifications',
          label: `${te('profile.qualifications')} (${p.qualifications.length})`,
          children: (
            <Table
              size="small"
              rowKey={(_, i) => String(i)}
              pagination={false}
              dataSource={p.qualifications}
              columns={[
                {
                  title: te('profile.qualificationLevel'),
                  dataIndex: 'qualificationLevelCode',
                  render: (c: string | null) => <CodeLabel list="QUALIFICATION_LEVEL" code={c} />,
                },
                { title: te('profile.fieldOfStudy'), dataIndex: 'fieldOfStudy' },
                { title: te('profile.institution'), dataIndex: 'institutionName' },
                { title: te('profile.graduationYear'), dataIndex: 'graduationYear' },
                { title: te('profile.highest'), dataIndex: 'highest', render: yes },
              ]}
            />
          ),
        },
        {
          key: 'taxResidency',
          label: `${te('profile.taxResidency')} (${p.taxResidency.length})`,
          children: (
            <Table
              size="small"
              rowKey={(_, i) => String(i)}
              pagination={false}
              dataSource={p.taxResidency}
              columns={[
                { title: te('profile.residencyStatus'), dataIndex: 'status' },
                { title: te('profile.basis'), dataIndex: 'basis' },
                { title: te('profile.validity'), key: 'v', render: (_, r) => validity(r.validFrom, r.validTo) },
                { title: te('profile.approvalStatus'), dataIndex: 'approvalStatus' },
              ]}
            />
          ),
        },
      ]}
    />
  );
}

function EmploymentCard({ employment: e }: { employment: MyEmployment }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const { t: tp } = useTranslation('people');
  const ref = e.employment;
  const documents = [...e.contracts, ...e.decisions];

  return (
    <Card
      style={{ marginBottom: 16 }}
      title={
        <Space wrap>
          {te('employment.employeeNo', { no: ref.employeeNo })}
          <EmploymentStatusTag status={ref.status as EmploymentStatus} />
        </Space>
      }
      extra={<LegalEntityName id={ref.legalEntityId} />}
    >
      <DescriptionList
        items={[
          { label: tp('common.hireDate'), value: formatDate(ref.hireDate) },
          { label: tp('common.endDate'), value: formatDate(ref.endDate), hideEmpty: true },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Typography.Title level={5}>{te('employment.assignments')}</Typography.Title>
      <Table
        size="small"
        rowKey={(r) => `${r.validFrom}-${r.assignmentType ?? ''}`}
        pagination={false}
        dataSource={e.assignments}
        locale={{ emptyText: tp('common.noData') }}
        style={{ marginBottom: 16 }}
        columns={[
          { title: tp('common.orgUnit'), dataIndex: 'orgUnitId', render: (id: string | null) => <OrgUnitName id={id} /> },
          { title: tp('common.jobTitle'), dataIndex: 'jobTitleId', render: (id: string | null) => <JobTitleName id={id} /> },
          { title: tp('common.grade'), dataIndex: 'gradeId', render: (id: string | null) => <GradeName id={id} /> },
          { title: tp('common.location'), dataIndex: 'locationId', render: (id: string | null) => <LocationName id={id} /> },
          { title: te('employment.action'), dataIndex: 'actionCode' },
          { title: te('profile.validity'), key: 'v', render: (_, r) => formatValidity(r.validFrom, r.validTo, t('common.openEnded')) },
        ]}
      />

      <Typography.Title level={5}>
        {te('employment.contracts')} / {te('employment.decisions')}
      </Typography.Title>
      <DocumentsTable documents={documents} />

      <Typography.Title level={5} style={{ marginTop: 16 }}>
        {te('employment.salary')}
      </Typography.Title>
      {e.salaryVisible && e.salary ? (
        <Table
          size="small"
          rowKey={(r) => r.validFrom}
          pagination={false}
          dataSource={e.salary}
          locale={{ emptyText: tp('common.noData') }}
          columns={[
            { title: te('profile.validity'), key: 'v', render: (_, r) => formatValidity(r.validFrom, r.validTo, t('common.openEnded')) },
            { title: te('employment.base'), dataIndex: 'baseSalaryAmount', align: 'right', render: (v: number | null, r) => formatMoney(v, r.currencyCode ?? 'VND') },
            { title: te('employment.net'), dataIndex: 'agreedNetAmount', align: 'right', render: (v: number | null, r) => formatMoney(v, r.currencyCode ?? 'VND') },
            { title: te('employment.si'), dataIndex: 'siSalaryAmount', align: 'right', render: (v: number | null) => formatMoney(v) },
            {
              title: te('employment.allowances'),
              dataIndex: 'allowances',
              render: (list: MyAllowance[] | null) =>
                (list ?? []).map((a) => `${a.componentCode}: ${formatMoney(a.amount)}`).join(' · ') || '—',
            },
          ]}
        />
      ) : (
        <Typography.Text type="secondary">{te('employment.salaryHidden')}</Typography.Text>
      )}
    </Card>
  );
}
