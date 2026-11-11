import { useMemo, useState } from 'react';
import { Button, Input, Select, Space } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader, StatusTag, usePaging } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { USER_STATUSES, type UserStatus, type UserView } from './api';
import { useUserSearch } from './queries';
import { UserForm } from './UserForm';
import './i18n';

/** Tenant user accounts (US-CORE-030): search, status filter, create, open detail. */
export function UsersListPage() {
  const { t } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const navigate = useNavigate();
  const paging = usePaging();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<UserStatus | undefined>();
  const [creating, setCreating] = useState(false);

  const query = useUserSearch({ q: q || undefined, status, page: paging.page, size: paging.size });
  const statusLabels = useMemo(
    () => Object.fromEntries(USER_STATUSES.map((s) => [s, ta(`users.status.${s}`)])),
    [ta],
  );

  return (
    <div>
      <PageHeader
        title={ta('users.title')}
        extra={
          <PermissionGate anyOf={P.USER_CREATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {ta('users.create')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap>
          <Input.Search
            allowClear
            aria-label={t('action.search')}
            placeholder={ta('users.searchPlaceholder')}
            style={{ width: 320 }}
            onSearch={(v) => {
              setQ(v.trim());
              paging.reset();
            }}
          />
          <Select<UserStatus>
            allowClear
            aria-label={t('field.status')}
            placeholder={ta('users.allStatuses')}
            style={{ width: 200 }}
            value={status}
            onChange={(v) => {
              setStatus(v);
              paging.reset();
            }}
            options={USER_STATUSES.map((s) => ({ value: s, label: statusLabels[s] }))}
          />
        </Space>
      </PageHeader>

      <DataTable<UserView>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        paging={{ page: paging.page, size: paging.size, onChange: paging.onChange }}
        onRow={(row) => ({
          style: { cursor: 'pointer' },
          onClick: () => void navigate({ to: '/core/users/$userId', params: { userId: row.id } }),
        })}
        columns={[
          {
            title: ta('users.login'),
            dataIndex: 'login',
            render: (login: string, row) => (
              <Link to="/core/users/$userId" params={{ userId: row.id }} onClick={(e) => e.stopPropagation()}>
                {login}
              </Link>
            ),
          },
          { title: ta('users.displayName'), dataIndex: 'displayName' },
          { title: ta('users.email'), dataIndex: 'email' },
          { title: t('field.status'), dataIndex: 'status', render: (s: UserStatus) => <StatusTag status={s} labels={statusLabels} /> },
          { title: ta('users.expiresAt'), dataIndex: 'expiresAt', render: (v: string | null) => formatDateTime(v) },
          { title: t('field.updatedAt'), dataIndex: 'updatedAt', render: (v: string | null) => formatDateTime(v) },
        ]}
      />

      <UserForm
        open={creating}
        onClose={() => setCreating(false)}
        onSaved={(u) => void navigate({ to: '/core/users/$userId', params: { userId: u.id } })}
      />
    </div>
  );
}
