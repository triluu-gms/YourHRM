import { useState } from 'react';
import { Button, Checkbox, Input, Space, Tabs } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, PageHeader, StatusTag } from '@/shared/components';
import { localizedName } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { JobTitle } from './api';
import { useJobFamilies, useJobTitles } from './queries';
import { JobTitleForm } from './JobTitleForm';
import { JobFamiliesPanel } from './JobFamiliesPanel';
import { familyLabel, HazardTag, JobFamilySelect } from './widgets';
import { JOB_CATALOGUE_NS } from './i18n';

/** Job titles and job families (BR-CORE-020, US-CORE-020): two tabs of one page. */
export function JobTitlesPage() {
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const [tab, setTab] = useState('titles');
  return (
    <div>
      <PageHeader title={tj('jobTitle.title')} subTitle={tj('jobTitle.subtitle')} />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'titles', label: tj('jobTitle.tab'), children: <JobTitlesPanel /> },
          { key: 'families', label: tj('jobFamily.tab'), children: <JobFamiliesPanel /> },
        ]}
      />
    </div>
  );
}

function JobTitlesPanel() {
  const { t, i18n } = useTranslation();
  const { t: tj } = useTranslation(JOB_CATALOGUE_NS);
  const [q, setQ] = useState('');
  const [familyId, setFamilyId] = useState<UUID | undefined>();
  const [includeInactive, setIncludeInactive] = useState(false);
  const [form, setForm] = useState<{ title: JobTitle | null } | null>(null);
  const canUpdate = usePermission(P.JOB_CATALOGUE_UPDATE);
  const query = useJobTitles({ q: q.trim() || undefined, jobFamilyId: familyId, includeInactive });
  const families = useJobFamilies(true);
  const familyById = new Map((families.data ?? []).map((f) => [f.id, f]));

  return (
    <div>
      <Space wrap style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Space wrap>
          <Input.Search
            allowClear
            aria-label={tj('jobTitle.search')}
            placeholder={tj('jobTitle.search')}
            style={{ width: 240 }}
            onSearch={setQ}
          />
          <JobFamilySelect
            value={familyId}
            onChange={(id) => setFamilyId(id ?? undefined)}
            placeholder={tj('jobTitle.allFamilies')}
            style={{ width: 240 }}
          />
          <Checkbox checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)}>
            {tj('common.includeInactive')}
          </Checkbox>
        </Space>
        <PermissionGate anyOf={P.JOB_CATALOGUE_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ title: null })}>
            {tj('jobTitle.create')}
          </Button>
        </PermissionGate>
      </Space>

      <DataTable<JobTitle>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: t('field.code'),
            dataIndex: 'code',
            render: (code: string, row) => (
              <Link to="/core/job-titles/$titleId" params={{ titleId: row.id }}>
                {code}
              </Link>
            ),
          },
          { title: t('field.name'), key: 'name', render: (_, row) => localizedName(row, i18n.language) },
          {
            title: tj('jobTitle.field.family'),
            key: 'family',
            render: (_, row) => {
              const f = row.jobFamilyId ? familyById.get(row.jobFamilyId) : undefined;
              return f ? familyLabel(f, i18n.language) : null;
            },
          },
          { title: tj('jobTitle.field.hazardous'), key: 'hazard', render: (_, row) => <HazardTag category={row.hazardousCategory} /> },
          { title: tj('jobTitle.field.occupationCode'), dataIndex: 'occupationCode' },
          { title: t('field.status'), dataIndex: 'active', render: (a: boolean) => <StatusTag status={a ? 'ACTIVE' : 'INACTIVE'} /> },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, row: JobTitle) => (
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      aria-label={`${t('action.edit')} ${row.code}`}
                      onClick={() => setForm({ title: row })}
                    />
                  ),
                },
              ]
            : []),
        ]}
      />

      {form ? <JobTitleForm title={form.title} onClose={() => setForm(null)} /> : null}
    </div>
  );
}
