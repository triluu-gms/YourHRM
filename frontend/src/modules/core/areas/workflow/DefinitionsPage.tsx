import { useMemo, useState } from 'react';
import { Button, Select, Space, Tabs, Tag, Typography } from 'antd';
import { CheckOutlined, PlusOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader } from '@/shared/components';
import { localizedName, today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { definitionsQuery, processTypesQuery } from './queries';
import { WorkflowStatusTag, useActionLabel } from './common';
import { PublishDefinitionDrawer } from './PublishDefinitionDrawer';
import type { DefinitionView, ProcessTypeView, VersionView } from './api';
import './i18n';

/** The published version in force on `date`, if any. */
export function versionInForce(definition: DefinitionView, date: string): VersionView | undefined {
  return definition.versions.find(
    (v) => v.status === 'PUBLISHED' && v.validFrom <= date && (!v.validTo || date < v.validTo),
  );
}

/** Workflow definitions and the registered process types (US-CORE-050/051; `core.workflow_definition.view`). */
export function DefinitionsPage() {
  const { t, i18n } = useTranslation('core-workflow');
  const [processType, setProcessType] = useState<string | undefined>();
  const [publishing, setPublishing] = useState(false);
  const definitions = useQuery(definitionsQuery(processType));
  const processTypes = useQuery(processTypesQuery);

  const processOptions = useMemo(
    () =>
      (processTypes.data ?? []).map((pt) => ({
        value: pt.code,
        label: localizedName({ nameVi: pt.nameVi, nameEn: pt.nameEn }, i18n.language) || pt.code,
      })),
    [processTypes.data, i18n.language],
  );

  return (
    <div>
      <PageHeader
        title={t('definitions.title')}
        subTitle={t('definitions.subtitle')}
        extra={
          <PermissionGate anyOf={P.WORKFLOW_DEFINITION_MANAGE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setPublishing(true)}>
              {t('definitions.new')}
            </Button>
          </PermissionGate>
        }
      />

      <Tabs
        items={[
          {
            key: 'definitions',
            label: t('definitions.title'),
            children: (
              <div>
                <Space style={{ marginBottom: 12 }} wrap>
                  <Select<string>
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    aria-label={t('definitions.processType')}
                    placeholder={t('definitions.allProcessTypes')}
                    style={{ minWidth: 260 }}
                    value={processType}
                    onChange={setProcessType}
                    options={processOptions}
                  />
                </Space>
                <DefinitionsTable data={definitions.data} isLoading={definitions.isLoading} error={definitions.error} />
              </div>
            ),
          },
          {
            key: 'process-types',
            label: t('definitions.processTypes'),
            children: (
              <ProcessTypesTable data={processTypes.data} isLoading={processTypes.isLoading} error={processTypes.error} />
            ),
          },
        ]}
      />

      <PublishDefinitionDrawer open={publishing} onClose={() => setPublishing(false)} />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ definitions

function DefinitionsTable({ data, isLoading, error }: { data: DefinitionView[] | undefined; isLoading: boolean; error: unknown }) {
  const { t, i18n } = useTranslation('core-workflow');
  const current = today();
  return (
    <DataTable<DefinitionView>
      rowKey="id"
      data={data}
      isLoading={isLoading}
      error={error}
      locale={{ emptyText: t('definitions.empty') }}
      columns={[
        {
          title: t('definitions.code'),
          key: 'code',
          render: (_, d) => (
            <Link to="/core/workflow-definitions/$definitionId" params={{ definitionId: d.id }}>
              <Typography.Text code>{d.code}</Typography.Text>
            </Link>
          ),
        },
        { title: t('definitions.name'), key: 'name', render: (_, d) => localizedName({ nameVi: d.nameVi, nameEn: d.nameEn }, i18n.language) },
        { title: t('definitions.processType'), dataIndex: 'processTypeCode', render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
        { title: t('definitions.matchPriority'), dataIndex: 'matchPriority', align: 'right' },
        { title: t('definitions.status'), dataIndex: 'status', render: (s: string) => <WorkflowStatusTag status={s} /> },
        { title: t('definitions.versions'), key: 'versions', align: 'right', render: (_, d) => d.versions.length },
        {
          title: t('definitions.currentVersion'),
          key: 'current',
          render: (_, d) => {
            const v = versionInForce(d, current);
            return v ? `${t('definition.version', { versionNo: v.versionNo })} · ${t('definitions.stepsCount', { count: v.steps.length })}` : null;
          },
        },
      ]}
    />
  );
}

// ------------------------------------------------------------------------------------------------ process types

function ProcessTypesTable({ data, isLoading, error }: { data: ProcessTypeView[] | undefined; isLoading: boolean; error: unknown }) {
  const { t, i18n } = useTranslation('core-workflow');
  const label = useActionLabel();
  const flag = (value: boolean, name: string) => (value ? <CheckOutlined aria-label={name} /> : null);
  return (
    <DataTable<ProcessTypeView>
      rowKey="code"
      data={data}
      isLoading={isLoading}
      error={error}
      columns={[
        { title: t('definitions.code'), dataIndex: 'code', render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
        { title: t('definitions.name'), key: 'name', render: (_, pt) => localizedName({ nameVi: pt.nameVi, nameEn: pt.nameEn }, i18n.language) },
        { title: t('definitions.ownerModule'), dataIndex: 'ownerModule' },
        {
          title: t('definitions.allowedActions'),
          key: 'actions',
          render: (_, pt) => (
            <Space size={[4, 4]} wrap>
              {pt.allowedActions.map((a) => (
                <Tag key={a}>{label(a)}</Tag>
              ))}
            </Space>
          ),
        },
        {
          title: t('definitions.conditionFields'),
          key: 'fields',
          render: (_, pt) =>
            pt.conditionFields.length > 0 ? (
              <Space size={[4, 4]} wrap>
                {pt.conditionFields.map((f) => (
                  <Tag key={f}>{f}</Tag>
                ))}
              </Space>
            ) : null,
        },
        { title: t('definitions.allowsAutoApprove'), key: 'auto', align: 'center', render: (_, pt) => flag(pt.allowsAutoApprove, t('definitions.allowsAutoApprove')) },
        { title: t('definitions.requiresStepUp'), key: 'stepUp', align: 'center', render: (_, pt) => flag(pt.requiresStepUp, t('definitions.requiresStepUp')) },
        { title: t('definitions.active'), key: 'active', align: 'center', render: (_, pt) => flag(pt.active, t('definitions.active')) },
        { title: t('definitions.hasVendorDefault'), key: 'vendor', align: 'center', render: (_, pt) => flag(pt.hasVendorDefault, t('definitions.hasVendorDefault')) },
      ]}
    />
  );
}
