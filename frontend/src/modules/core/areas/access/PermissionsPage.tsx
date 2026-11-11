import { useMemo, useState } from 'react';
import { Checkbox, Input, Select, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { DataTable, PageHeader } from '@/shared/components';
import { localizedName } from '@/shared/format';
import type { PermissionView } from './api';
import { usePermissionCatalogue } from './queries';
import { PermissionFlags } from './PermissionMatrix';
import './i18n';

/** Global permission catalogue (US-CORE-033), read-only: modules register it at deploy, tenants only compose roles. */
export function PermissionsPage() {
  const { t, i18n } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const [includeInactive, setIncludeInactive] = useState(false);
  const [module, setModule] = useState<string | undefined>();
  const [q, setQ] = useState('');
  const query = usePermissionCatalogue(includeInactive);

  const modules = useMemo(() => [...new Set((query.data ?? []).map((p) => p.module))].sort(), [query.data]);
  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (query.data ?? []).filter(
      (p) =>
        (!module || p.module === module) &&
        (!needle ||
          p.code.toLowerCase().includes(needle) ||
          p.functionCode.toLowerCase().includes(needle) ||
          localizedName(p, i18n.language).toLowerCase().includes(needle)),
    );
  }, [query.data, module, q, i18n.language]);

  return (
    <div>
      <PageHeader title={ta('permissions.title')}>
        <Space wrap>
          <Input.Search
            allowClear
            aria-label={t('action.search')}
            placeholder={ta('permissions.searchPlaceholder')}
            style={{ width: 320 }}
            onChange={(e) => setQ(e.target.value)}
          />
          <Select<string>
            allowClear
            aria-label={ta('permissions.module')}
            placeholder={ta('permissions.allModules')}
            style={{ width: 180 }}
            value={module}
            onChange={setModule}
            options={modules.map((m) => ({ value: m, label: m }))}
          />
          <Checkbox checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)}>
            {ta('permissions.includeInactive')}
          </Checkbox>
          <Typography.Text type="secondary">{ta('permissions.count', { count: rows.length })}</Typography.Text>
        </Space>
      </PageHeader>

      <DataTable<PermissionView>
        rowKey="code"
        data={rows}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.name'), key: 'name', render: (_, p) => localizedName(p, i18n.language) },
          { title: ta('permissions.module'), dataIndex: 'module' },
          { title: ta('permissions.function'), dataIndex: 'functionCode' },
          { title: ta('permissions.action'), dataIndex: 'action' },
          { title: ta('permissions.flags'), key: 'flags', render: (_, p) => <PermissionFlags permission={p} /> },
        ]}
      />
    </div>
  );
}
