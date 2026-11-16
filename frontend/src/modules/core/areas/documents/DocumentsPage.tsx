import { useMemo, useState } from 'react';
import { Alert, Card, Input, Select, Space, Tabs, Tag, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, PageHeader } from '@/shared/components';
import { CONFIDENTIALITIES, formatBytes, type Confidentiality, type DocumentTypeDefinition } from './api';
import { useDocumentTypes } from './queries';
import { ConfidentialityTag, DocumentsPanel } from './DocumentsPanel';
import { DOCUMENTS_NS } from './i18n';

/** Case-insensitive match on the type code, both names and the record kind it belongs to. */
export function matchesType(x: DocumentTypeDefinition, q: string): boolean {
  const needle = q.trim().toLowerCase();
  return (
    !needle ||
    x.code.toLowerCase().includes(needle) ||
    x.nameVi.toLowerCase().includes(needle) ||
    (x.nameEn ?? '').toLowerCase().includes(needle) ||
    x.ownerEntityType.toLowerCase().includes(needle)
  );
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Documents (BR-CORE-100/101, US-CORE-100..102). The document service has no tenant-wide listing - a document is
 * reached through the record it belongs to, because that record's permission and data scope decide who may see it.
 * So this page shows what the registry holds (which types exist, who may read them, how long they are kept) and
 * lets an administrator open the files of one record.
 */
export function DocumentsPage() {
  const { t } = useTranslation(DOCUMENTS_NS);
  return (
    <div>
      <PageHeader title={t('library.title')} subTitle={t('library.subtitle')} />
      <Tabs
        items={[
          { key: 'types', label: t('types.title'), children: <DocumentTypesTable /> },
          { key: 'record', label: t('library.byRecord'), children: <RecordLookup /> },
        ]}
      />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ type registry

function DocumentTypesTable() {
  const { t } = useTranslation(DOCUMENTS_NS);
  const types = useDocumentTypes();
  const [q, setQ] = useState('');
  const [module, setModule] = useState<string | undefined>();
  const [confidentiality, setConfidentiality] = useState<Confidentiality | undefined>();

  const all = useMemo(() => types.data ?? [], [types.data]);
  const modules = useMemo(() => [...new Set(all.map((x) => x.ownerModule))].sort(), [all]);
  const rows = all.filter(
    (x) =>
      matchesType(x, q) &&
      (!module || x.ownerModule === module) &&
      (!confidentiality || x.defaultConfidentiality === confidentiality),
  );

  return (
    <div>
      <Typography.Paragraph type="secondary">{t('types.subtitle')}</Typography.Paragraph>
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          allowClear
          placeholder={t('types.search')}
          aria-label={t('types.search')}
          style={{ width: 280 }}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <Select<string>
          allowClear
          aria-label={t('types.field.module')}
          placeholder={t('types.allModules')}
          style={{ width: 160 }}
          value={module}
          onChange={setModule}
          options={modules.map((m) => ({ value: m, label: m }))}
        />
        <Select<Confidentiality>
          allowClear
          aria-label={t('types.field.confidentiality')}
          placeholder={t('types.allConfidentialities')}
          style={{ width: 200 }}
          value={confidentiality}
          onChange={setConfidentiality}
          options={CONFIDENTIALITIES.map((c) => ({ value: c, label: t(`confidentiality.${c}`) }))}
        />
      </Space>

      <DataTable<DocumentTypeDefinition>
        rowKey="code"
        data={rows}
        isLoading={types.isLoading}
        error={types.error}
        columns={[
          {
            title: t('types.field.code'),
            dataIndex: 'code',
            render: (code: string, x) => (
              <Space direction="vertical" size={0}>
                <Typography.Text style={{ fontFamily: 'monospace' }}>{code}</Typography.Text>
                <Typography.Text type="secondary">{x.nameVi}</Typography.Text>
              </Space>
            ),
          },
          { title: t('types.field.module'), dataIndex: 'ownerModule' },
          {
            title: t('types.field.ownerEntityType'),
            dataIndex: 'ownerEntityType',
            render: (owner: string) => (
              <Typography.Text style={{ fontFamily: 'monospace' }}>{owner}</Typography.Text>
            ),
          },
          {
            title: t('types.field.confidentiality'),
            dataIndex: 'defaultConfidentiality',
            render: (c: Confidentiality) => <ConfidentialityTag value={c} />,
          },
          { title: t('types.field.retentionClass'), dataIndex: 'retentionClass' },
          {
            title: t('types.field.permissions'),
            key: 'permissions',
            render: (_, x) => (
              <Space direction="vertical" size={0}>
                <Typography.Text type="secondary" style={{ fontFamily: 'monospace' }}>
                  {t('types.view')}: {x.viewPermission}
                </Typography.Text>
                <Typography.Text type="secondary" style={{ fontFamily: 'monospace' }}>
                  {t('types.manage')}: {x.managePermission}
                </Typography.Text>
              </Space>
            ),
          },
          {
            title: t('types.field.limits'),
            key: 'limits',
            render: (_, x) => (
              <Space size={[4, 4]} wrap>
                {x.mediaTypes.length ? (
                  x.mediaTypes.map((m) => <Tag key={m}>{m}</Tag>)
                ) : (
                  <Tooltip title={t('types.tenantDefaultHint')}>
                    <Tag>{t('types.tenantDefault')}</Tag>
                  </Tooltip>
                )}
                {x.maxSizeBytes ? <Tag color="blue">{formatBytes(x.maxSizeBytes)}</Tag> : null}
              </Space>
            ),
          },
        ]}
      />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ record lookup

/** The files of one record, reached the way the API expects: owner record kind + its id. */
function RecordLookup() {
  const { t } = useTranslation(DOCUMENTS_NS);
  const types = useDocumentTypes();
  const [ownerEntityType, setOwnerEntityType] = useState<string | undefined>();
  const [id, setId] = useState('');

  const ownerTypes = useMemo(
    () => [...new Set((types.data ?? []).map((x) => x.ownerEntityType))].sort(),
    [types.data],
  );
  const ownerEntityId = UUID_RE.test(id.trim()) ? (id.trim() as UUID) : undefined;

  return (
    <div>
      <Alert type="info" showIcon message={t('library.byRecordHint')} style={{ marginBottom: 12 }} />
      <Space style={{ marginBottom: 12 }} wrap>
        <Select<string>
          showSearch
          style={{ width: 260 }}
          aria-label={t('library.field.ownerEntityType')}
          placeholder={t('library.field.ownerEntityType')}
          value={ownerEntityType}
          onChange={setOwnerEntityType}
          options={ownerTypes.map((o) => ({ value: o, label: o }))}
        />
        <Input
          allowClear
          style={{ width: 360 }}
          aria-label={t('library.field.recordId')}
          placeholder={t('library.field.recordId')}
          value={id}
          onChange={(e) => setId(e.target.value)}
          status={id && !ownerEntityId ? 'error' : undefined}
        />
      </Space>

      {ownerEntityType && ownerEntityId ? (
        <Card size="small">
          <DocumentsPanel ownerEntityType={ownerEntityType} ownerEntityId={ownerEntityId} />
        </Card>
      ) : (
        <Typography.Text type="secondary">{t('library.pickRecord')}</Typography.Text>
      )}
    </div>
  );
}
