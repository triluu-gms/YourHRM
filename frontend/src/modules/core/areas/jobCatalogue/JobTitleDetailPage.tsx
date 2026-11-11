import { useState } from 'react';
import { Button, Card, Result, Skeleton } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DescriptionList, PageHeader, ProblemAlert, StatusTag } from '@/shared/components';
import { localizedName } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { useAllGrades, useJobFamilies, useJobTitle } from './queries';
import { JobTitleForm } from './JobTitleForm';
import { familyLabel, gradeLabel, HazardTag } from './widgets';
import { JOB_CATALOGUE_NS } from './i18n';

/** One job title: attributes, hazardous category, default grade range; edit with its version. */
export function JobTitleDetailPage({ titleId }: { titleId: string }) {
  const { t, i18n } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const [editing, setEditing] = useState(false);
  const query = useJobTitle(titleId);
  const families = useJobFamilies(true);
  const { structures, grades } = useAllGrades();

  const jt = query.data;
  if (query.isLoading) return <Skeleton active />;
  if (query.error || !jt) {
    return <Result status="404" title={tj('common.notFound')} extra={query.error ? <ProblemAlert error={query.error} /> : null} />;
  }
  const family = families.data?.find((f) => f.id === jt.jobFamilyId);
  const min = grades.find((g) => g.id === jt.defaultGradeMinId);
  const max = grades.find((g) => g.id === jt.defaultGradeMaxId);
  const structure = structures.find((s) => s.id === min?.gradeStructureId);
  const range = jt.defaultGradeMinId ? (
    <>
      {structure ? (
        <Link to="/core/grade-structures/$structureId" params={{ structureId: structure.id }}>
          {structure.code}
        </Link>
      ) : null}{' '}
      {min ? gradeLabel(min) : jt.defaultGradeMinId} → {max ? gradeLabel(max) : jt.defaultGradeMaxId}
    </>
  ) : null;

  return (
    <div>
      <PageHeader
        title={tj('jobTitle.detailTitle', { code: jt.code })}
        subTitle={localizedName(jt, i18n.language)}
        breadcrumb={[{ label: tj('jobTitle.title'), to: '/core/job-titles' }, { label: jt.code }]}
        extra={
          <PermissionGate anyOf={P.JOB_CATALOGUE_UPDATE}>
            <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>
              {t('action.edit')}
            </Button>
          </PermissionGate>
        }
      />
      <Card title={tj('common.details')}>
        <DescriptionList
          items={[
            { label: t('field.code'), value: <code>{jt.code}</code> },
            { label: t('field.status'), value: <StatusTag status={jt.active ? 'ACTIVE' : 'INACTIVE'} /> },
            { label: t('field.nameVi'), value: jt.nameVi },
            { label: t('field.nameEn'), value: jt.nameEn },
            { label: tj('jobTitle.field.family'), value: family ? familyLabel(family, i18n.language) : null },
            { label: tj('jobTitle.field.hazardousCategory'), value: <HazardTag category={jt.hazardousCategory} /> },
            { label: tj('jobTitle.field.occupationCode'), value: jt.occupationCode },
            { label: tj('jobTitle.field.defaultGradeRange'), value: range },
            { label: tj('jobTitle.field.jdDocumentId'), value: jt.jdDocumentId ? <code>{jt.jdDocumentId}</code> : null },
            { label: t('field.version'), value: jt.version },
            { label: tj('jobTitle.field.description'), value: jt.description, span: 3 },
          ]}
        />
      </Card>
      {editing ? <JobTitleForm title={jt} onClose={() => setEditing(false)} /> : null}
    </div>
  );
}
