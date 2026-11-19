import { useState } from 'react';
import { Button, Card, Collapse, Result, Skeleton, Space, Table, Tag, Typography } from 'antd';
import { CheckOutlined, PlusOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DescriptionList, PageHeader, ProblemAlert } from '@/shared/components';
import { formatValidity, localizedName } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { definitionQuery } from './queries';
import { WorkflowStatusTag } from './common';
import { PublishDefinitionDrawer } from './PublishDefinitionDrawer';
import type { Conditions, StepTemplate, VersionView } from './api';
import './i18n';

/** One workflow definition with every version: applicability, fallback policy and steps (US-CORE-050/051). */
export function DefinitionDetailPage({ definitionId }: { definitionId: string }) {
  const { t, i18n } = useTranslation('core-workflow');
  const query = useQuery(definitionQuery(definitionId));
  const [publishing, setPublishing] = useState(false);

  const definition = query.data;
  if (query.isLoading) return <Skeleton active />;
  if (query.error || !definition) {
    return <Result status="404" title={t('instance.notFound')} extra={query.error ? <ProblemAlert error={query.error} /> : null} />;
  }

  const versions = [...definition.versions].sort((a, b) => b.versionNo - a.versionNo);

  return (
    <div>
      <PageHeader
        title={t('definition.title', { code: definition.code })}
        subTitle={localizedName({ nameVi: definition.nameVi, nameEn: definition.nameEn }, i18n.language)}
        breadcrumb={[{ label: t('definition.breadcrumb'), to: '/core/workflow-definitions' }, { label: definition.code }]}
        extra={
          <PermissionGate anyOf={P.WORKFLOW_DEFINITION_MANAGE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setPublishing(true)}>
              {t('definition.newVersion')}
            </Button>
          </PermissionGate>
        }
      />

      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card title={t('definition.overview')} size="small">
          <DescriptionList
            items={[
              { label: t('definitions.code'), value: <Typography.Text code>{definition.code}</Typography.Text> },
              { label: t('definitions.processType'), value: <Typography.Text code>{definition.processTypeCode}</Typography.Text> },
              { label: t('definitions.matchPriority'), value: definition.matchPriority },
              { label: t('definitions.status'), value: <WorkflowStatusTag status={definition.status} /> },
              { label: t('definitions.versions'), value: definition.versions.length },
            ]}
          />
        </Card>

        <Collapse
          defaultActiveKey={versions.length > 0 ? [versions[0]!.id] : []}
          items={versions.map((v) => ({
            key: v.id,
            label: (
              <Space size={8} wrap>
                <Typography.Text strong>{t('definition.version', { versionNo: v.versionNo })}</Typography.Text>
                <WorkflowStatusTag status={v.status} />
                <Typography.Text type="secondary">{formatValidity(v.validFrom, v.validTo, '…')}</Typography.Text>
              </Space>
            ),
            children: <VersionPanel version={v} />,
          }))}
        />
      </Space>

      <PublishDefinitionDrawer open={publishing} onClose={() => setPublishing(false)} definition={definition} />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ version

function VersionPanel({ version }: { version: VersionView }) {
  const { t } = useTranslation('core-workflow');
  const [showRaw, setShowRaw] = useState(false);
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <DescriptionList
        size="small"
        items={[
          { label: t('definition.versionNo'), value: version.versionNo },
          { label: t('definition.validity'), value: formatValidity(version.validFrom, version.validTo, '…') },
          {
            label: t('definition.onResolverFailure'),
            value: t(`definition.fallback.${version.onResolverFailure}`, { defaultValue: version.onResolverFailure }),
          },
        ]}
      />

      <Card type="inner" size="small" title={t('definition.conditions')}>
        <ConditionsView conditions={version.conditions} />
      </Card>

      <Card
        type="inner"
        size="small"
        title={t('definition.steps')}
        extra={
          <Button type="link" size="small" onClick={() => setShowRaw((s) => !s)}>
            {showRaw ? t('definition.hideRaw') : t('definition.showRaw')}
          </Button>
        }
      >
        <StepsTable steps={version.steps} />
        {showRaw ? (
          <Typography.Paragraph code style={{ whiteSpace: 'pre-wrap', marginTop: 12 }} data-testid="definition-raw">
            {JSON.stringify({ conditions: version.conditions, steps: version.steps, onResolverFailure: version.onResolverFailure }, null, 2)}
          </Typography.Paragraph>
        ) : null}
      </Card>
    </Space>
  );
}

function ConditionsView({ conditions }: { conditions: Conditions }) {
  const { t } = useTranslation('core-workflow');
  const groups: { key: string; label: string; values: string[] }[] = [
    { key: 'le', label: t('definition.legalEntityIds'), values: conditions.legalEntityIds },
    { key: 'ou', label: t('definition.orgUnitIds'), values: conditions.orgUnitIds },
    { key: 'loc', label: t('definition.locationIds'), values: conditions.locationIds },
    { key: 'cat', label: t('definition.employeeCategoryIds'), values: conditions.employeeCategoryIds },
    ...Object.entries(conditions.fields).map(([field, values]) => ({
      key: `f:${field}`,
      label: `${t('definition.fields')} · ${field}`,
      values,
    })),
  ].filter((g) => g.values.length > 0);

  if (groups.length === 0) return <Typography.Text type="secondary">{t('definition.conditionsNone')}</Typography.Text>;
  return (
    <Space direction="vertical" size={4}>
      {groups.map((g) => (
        <div key={g.key}>
          <Typography.Text type="secondary">{g.label}: </Typography.Text>
          <Space size={[4, 4]} wrap>
            {g.values.map((v) => (
              <Tag key={v}>{v}</Tag>
            ))}
          </Space>
        </div>
      ))}
    </Space>
  );
}

function StepsTable({ steps }: { steps: StepTemplate[] }) {
  const { t, i18n } = useTranslation('core-workflow');
  return (
    <Table<StepTemplate>
      size="small"
      rowKey="stepKey"
      pagination={false}
      dataSource={steps}
      columns={[
        { title: t('definition.stepNo'), key: 'no', width: 50, render: (_, _step, index) => index + 1 },
        { title: t('definition.stepKey'), dataIndex: 'stepKey', render: (k: string) => <Typography.Text code>{k}</Typography.Text> },
        { title: t('definition.stepName'), key: 'name', render: (_, step) => localizedName({ nameVi: step.nameVi, nameEn: step.nameEn }, i18n.language) },
        {
          title: t('definition.resolver'),
          key: 'resolver',
          render: (_, step) => t(`definition.resolverType.${step.resolver.type}`, { defaultValue: step.resolver.type }),
        },
        {
          title: t('definition.resolverParams'),
          key: 'params',
          render: (_, step) =>
            Object.keys(step.resolver.params ?? {}).length > 0 ? (
              <Space size={[4, 4]} wrap>
                {Object.entries(step.resolver.params).map(([k, v]) => (
                  <Tag key={k}>
                    {k}={v}
                  </Tag>
                ))}
              </Space>
            ) : null,
        },
        {
          title: t('definition.allowReturn'),
          dataIndex: 'allowReturn',
          align: 'center',
          render: (v: boolean) => (v ? <CheckOutlined aria-label={t('definition.allowReturn')} /> : null),
        },
      ]}
    />
  );
}
