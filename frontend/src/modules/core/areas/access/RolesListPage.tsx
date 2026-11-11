import { useMemo, useState } from 'react';
import { Button, Input, Select, Space, Tag } from 'antd';
import { LockOutlined, PlusOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader } from '@/shared/components';
import { localizedName } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { RoleSummary } from './api';
import { useRoles } from './queries';
import { RoleForm, type RoleFormMode } from './RoleForm';
import './i18n';

type Kind = 'system' | 'custom';

/** Tenant roles (US-CORE-034): system roles first, then custom ones; search, kind filter, create. */
export function RolesListPage() {
  const { t, i18n } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [kind, setKind] = useState<Kind | undefined>();
  const [form, setForm] = useState<RoleFormMode | null>(null);
  const query = useRoles(q || undefined);

  const rows = useMemo(
    () => (query.data ?? []).filter((r) => !kind || (kind === 'system') === r.system),
    [query.data, kind],
  );

  return (
    <div>
      <PageHeader
        title={ta('roles.title')}
        extra={
          <PermissionGate anyOf={P.ROLE_CREATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ kind: 'create' })}>
              {ta('roles.create')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap>
          <Input.Search
            allowClear
            aria-label={t('action.search')}
            placeholder={ta('roles.searchPlaceholder')}
            style={{ width: 320 }}
            onSearch={(v) => setQ(v.trim())}
          />
          <Select<Kind>
            allowClear
            aria-label={ta('roles.kind')}
            placeholder={ta('roles.allKinds')}
            style={{ width: 180 }}
            value={kind}
            onChange={setKind}
            options={[
              { value: 'system', label: ta('roles.system') },
              { value: 'custom', label: ta('roles.custom') },
            ]}
          />
        </Space>
      </PageHeader>

      <DataTable<RoleSummary>
        data={rows}
        isLoading={query.isLoading}
        error={query.error}
        onRow={(row) => ({
          style: { cursor: 'pointer' },
          onClick: () => void navigate({ to: '/core/roles/$roleId', params: { roleId: row.id } }),
        })}
        columns={[
          {
            title: t('field.code'),
            dataIndex: 'code',
            render: (code: string, row) => (
              <Link to="/core/roles/$roleId" params={{ roleId: row.id }} onClick={(e) => e.stopPropagation()}>
                {code}
              </Link>
            ),
          },
          { title: t('field.name'), key: 'name', render: (_, row) => localizedName(row, i18n.language) },
          {
            title: ta('roles.kind'),
            dataIndex: 'system',
            render: (system: boolean) =>
              system ? (
                <Tag icon={<LockOutlined />} color="blue">
                  {ta('roles.system')}
                </Tag>
              ) : (
                <Tag>{ta('roles.custom')}</Tag>
              ),
          },
          { title: ta('roles.permissionCount'), dataIndex: 'permissionCount', align: 'right' },
        ]}
      />

      <RoleForm
        mode={form}
        onClose={() => setForm(null)}
        onSaved={(r) => void navigate({ to: '/core/roles/$roleId', params: { roleId: r.id } })}
      />
    </div>
  );
}
