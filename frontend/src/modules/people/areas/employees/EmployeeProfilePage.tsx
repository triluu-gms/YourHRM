import { useMemo } from 'react';
import { Alert, Card, DatePicker, Result, Skeleton, Space, Tabs, Tag, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { ISODate, UUID } from '@/shared/api';
import { DescriptionList, PageHeader, ProblemAlert } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import {
  EmploymentStatusTag,
  fromIsoDate,
  GradeName,
  JobTitleName,
  LegalEntityName,
  LocationName,
  MaskedValue,
  OrgUnitName,
  toIsoDate,
  type EmployeeProfile,
} from '../../common';
import { ProfileActions } from '../separation/ProfileActions';
import { useEmployeeProfile } from './queries';
import { PROFILE_TABS, type ShellTabKey } from './profileTabs';

/**
 * Employee profile (US-PPL-016): header facts for one employment as of a date, then the tabs the backend allows
 * (`profile.tabs`), each rendered by its owning area. Tab and as-of date live in the URL search params.
 */
export function EmployeeProfilePage({
  employmentId,
  tab,
  asOf,
  onChange,
}: {
  employmentId: UUID;
  tab?: string;
  asOf?: ISODate;
  onChange: (next: { tab?: string; asOf?: ISODate }) => void;
}) {
  const { t: tp } = useTranslation('people');
  const query = useEmployeeProfile(employmentId, asOf);
  const profile = query.data;

  const tabs = useMemo(
    () => (profile ? PROFILE_TABS.filter((x) => x.key === 'employment' || x.key in profile.tabs) : []),
    [profile],
  );

  if (query.isLoading) return <Skeleton active />;
  if (query.error || !profile) {
    return <Result status="404" title={tp('profile.notFound')} extra={query.error ? <ProblemAlert error={query.error} /> : null} />;
  }

  const active: ShellTabKey = (tabs.find((x) => x.key === tab)?.key ?? 'employment') as ShellTabKey;
  const props = { employmentId, personId: profile.person.id, profile };
  const { employment, person } = profile;

  return (
    <div>
      <PageHeader
        title={
          <Space wrap>
            {person.fullName}
            <EmploymentStatusTag status={employment.status} />
          </Space>
        }
        subTitle={
          <Space split="·" wrap>
            <span>{employment.employeeNo}</span>
            <LegalEntityName id={employment.legalEntityId} />
            {person.nationalIdMasked ? <MaskedValue value={person.nationalIdMasked} /> : null}
          </Space>
        }
        breadcrumb={[{ label: tp('directory.title'), to: '/people/employees' }, { label: employment.employeeNo }]}
        extra={
          <>
            <DatePicker
              aria-label={tp('common.asOf')}
              placeholder={tp('common.asOf')}
              format={DATE_FORMAT}
              value={fromIsoDate(asOf)}
              onChange={(d) => onChange({ tab, asOf: toIsoDate(d) ?? undefined })}
            />
            <ProfileActions {...props} />
          </>
        }
      />

      {person.anonymised ? <Alert type="warning" showIcon message={tp('profile.anonymised')} style={{ marginBottom: 16 }} /> : null}

      <ProfileSummary profile={profile} />

      <Card styles={{ body: { paddingTop: 0 } }}>
        <Tabs
          activeKey={active}
          onChange={(key) => onChange({ tab: key, asOf })}
          destroyOnHidden
          items={tabs.map(({ key, component: Tab }) => ({
            key,
            label: tp(`profile.tabs.${key}`),
            children: <Tab {...props} />,
          }))}
        />
      </Card>
    </div>
  );
}

function ProfileSummary({ profile }: { profile: EmployeeProfile }) {
  const { t: tp } = useTranslation('people');
  const { employment, primaryAssignment: a, otherEmployments } = profile;
  return (
    <Card size="small" style={{ marginBottom: 16 }}>
      <DescriptionList
        items={[
          { label: tp('common.orgUnit'), value: a ? <OrgUnitName id={a.orgUnitId} /> : null },
          { label: tp('common.jobTitle'), value: a ? <JobTitleName id={a.jobTitleId} /> : null },
          { label: tp('common.grade'), value: a?.gradeId ? <GradeName id={a.gradeId} /> : null, hideEmpty: true },
          { label: tp('common.location'), value: a ? <LocationName id={a.locationId} /> : null },
          {
            label: tp('common.manager'),
            value: a?.managerEmploymentId ? (
              <Link to="/people/employees/$employmentId" params={{ employmentId: a.managerEmploymentId }}>
                {a.managerName ?? a.managerEmploymentId}
              </Link>
            ) : (
              a?.managerName
            ),
          },
          { label: tp('common.hireDate'), value: formatDate(employment.hireDate) },
          { label: tp('common.seniorityDate'), value: formatDate(employment.seniorityDate), hideEmpty: true },
          { label: tp('common.endDate'), value: formatDate(employment.endDate), hideEmpty: true },
          {
            label: tp('profile.otherEmployments'),
            hideEmpty: true,
            value:
              otherEmployments.length > 0 ? (
                <Space wrap>
                  {otherEmployments.map((o) => (
                    <Link key={o.employmentId} to="/people/employees/$employmentId" params={{ employmentId: o.employmentId }}>
                      <Tag>
                        {o.employeeNo} · <LegalEntityName id={o.legalEntityId} />
                      </Tag>
                    </Link>
                  ))}
                </Space>
              ) : null,
          },
        ]}
      />
      {!a ? (
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          {tp('profile.noAssignment')}
        </Typography.Text>
      ) : null}
    </Card>
  );
}
