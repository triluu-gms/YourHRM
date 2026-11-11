import { useMemo, useState } from 'react';
import { Button, Card, Result, Skeleton, Space } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DescriptionList, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { USER_STATUSES } from './api';
import { useUser, useUserActivation } from './queries';
import { UserForm } from './UserForm';
import { RoleAssignmentsCard } from './RoleAssignmentsCard';
import './i18n';

/** One user account: profile, activate/deactivate, edit drawer and the role assignments (US-CORE-030/035). */
export function UserDetailPage({ userId }: { userId: string }) {
  const { t } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const fb = useFeedback();
  const query = useUser(userId);
  const activation = useUserActivation(userId);
  const [editing, setEditing] = useState(false);
  const statusLabels = useMemo(
    () => Object.fromEntries(USER_STATUSES.map((s) => [s, ta(`users.status.${s}`)])),
    [ta],
  );

  const user = query.data;
  if (query.isLoading) return <Skeleton active />;
  if (query.error || !user) {
    return <Result status="404" title={ta('users.notFound')} extra={query.error ? <ProblemAlert error={query.error} /> : null} />;
  }

  const toggle = async (action: 'activate' | 'deactivate') => {
    try {
      await activation.mutateAsync(action);
      fb.done(action === 'activate' ? ta('users.activated') : ta('users.deactivated'));
    } catch (e) {
      fb.failed(e);
    }
  };

  const canActivate = user.status !== 'ACTIVE';
  const canDeactivate = user.status === 'ACTIVE' || user.status === 'INVITED' || user.status === 'FORMER_EMPLOYEE' || user.status === 'LOCKED';

  return (
    <div>
      <PageHeader
        title={ta('users.detailTitle', { login: user.login })}
        subTitle={user.displayName}
        breadcrumb={[{ label: ta('users.title'), to: '/core/users' }, { label: user.login }]}
        extra={
          <PermissionGate anyOf={P.USER_UPDATE}>
            <Space wrap>
              <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>
                {t('action.edit')}
              </Button>
              {canActivate ? (
                <ConfirmButton
                  type="primary"
                  title={ta('users.confirmActivate')}
                  loading={activation.isPending}
                  onConfirm={() => toggle('activate')}
                >
                  {t('action.activate')}
                </ConfirmButton>
              ) : null}
              {canDeactivate ? (
                <ConfirmButton
                  danger
                  title={ta('users.confirmDeactivate')}
                  loading={activation.isPending}
                  onConfirm={() => toggle('deactivate')}
                >
                  {t('action.deactivate')}
                </ConfirmButton>
              ) : null}
            </Space>
          </PermissionGate>
        }
      />

      <Card title={ta('users.profile')} style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: ta('users.login'), value: user.login },
            { label: ta('users.displayName'), value: user.displayName },
            { label: t('field.status'), value: <StatusTag status={user.status} labels={statusLabels} /> },
            { label: ta('users.email'), value: user.email },
            { label: ta('users.mobile'), value: user.mobile },
            { label: ta('users.locale'), value: user.locale },
            { label: ta('users.identityId'), value: user.identityId ? <code>{user.identityId}</code> : null },
            { label: ta('users.employmentId'), value: user.employmentId ? <code>{user.employmentId}</code> : null },
            { label: ta('users.personId'), value: user.personId ? <code>{user.personId}</code> : null },
            { label: ta('users.expiresAt'), value: formatDateTime(user.expiresAt) },
            { label: ta('users.accessRevokedAt'), value: formatDateTime(user.accessRevokedAt), hideEmpty: true },
            { label: t('field.createdAt'), value: formatDateTime(user.createdAt) },
            { label: t('field.updatedAt'), value: formatDateTime(user.updatedAt) },
            { label: t('field.version'), value: user.version },
          ]}
        />
      </Card>

      <PermissionGate anyOf={P.ROLE_ASSIGNMENT_VIEW}>
        <RoleAssignmentsCard userId={user.id} />
      </PermissionGate>

      <UserForm open={editing} onClose={() => setEditing(false)} user={user} />
    </div>
  );
}
