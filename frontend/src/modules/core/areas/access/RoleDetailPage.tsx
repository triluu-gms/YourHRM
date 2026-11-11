import { useState } from 'react';
import { Alert, Button, Card, Result, Skeleton, Tag } from 'antd';
import { CopyOutlined, EditOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { ConfirmButton, DescriptionList, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { localizedName } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { useDeleteRole, useRole } from './queries';
import { RoleForm, type RoleFormMode } from './RoleForm';
import { PermissionMatrix } from './PermissionMatrix';
import './i18n';

/** One role: details, rename / copy / delete and the permission matrix (US-CORE-034). System roles are read-only. */
export function RoleDetailPage({ roleId }: { roleId: string }) {
  const { t, i18n } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const navigate = useNavigate();
  const fb = useFeedback();
  const query = useRole(roleId);
  const remove = useDeleteRole();
  const canUpdate = usePermission(P.ROLE_UPDATE);
  const [form, setForm] = useState<RoleFormMode | null>(null);

  const role = query.data;
  if (query.isLoading) return <Skeleton active />;
  if (query.error || !role) {
    return <Result status="404" title={ta('roles.notFound')} extra={query.error ? <ProblemAlert error={query.error} /> : null} />;
  }

  const onDelete = async () => {
    try {
      await remove.mutateAsync(role.id);
      fb.done(ta('roles.deleted'));
      void navigate({ to: '/core/roles' });
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader
        title={ta('roles.detailTitle', { code: role.code })}
        subTitle={localizedName(role, i18n.language)}
        breadcrumb={[{ label: ta('roles.title'), to: '/core/roles' }, { label: role.code }]}
        extra={
          <>
            {!role.system ? (
              <PermissionGate anyOf={P.ROLE_UPDATE}>
                <Button icon={<EditOutlined />} onClick={() => setForm({ kind: 'edit', role })}>
                  {t('action.edit')}
                </Button>
              </PermissionGate>
            ) : null}
            <PermissionGate anyOf={P.ROLE_CREATE}>
              <Button icon={<CopyOutlined />} onClick={() => setForm({ kind: 'copy', role })}>
                {ta('roles.copy')}
              </Button>
            </PermissionGate>
            {!role.system ? (
              <PermissionGate anyOf={P.ROLE_DELETE}>
                <ConfirmButton danger title={ta('roles.confirmDelete')} loading={remove.isPending} onConfirm={onDelete}>
                  {t('action.delete')}
                </ConfirmButton>
              </PermissionGate>
            ) : null}
          </>
        }
      />

      {role.system ? <Alert type="info" showIcon message={ta('roles.systemHint')} style={{ marginBottom: 16 }} /> : null}

      <Card title={ta('roles.details')} style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: ta('roles.code'), value: <code>{role.code}</code> },
            {
              label: ta('roles.kind'),
              value: role.system ? (
                <Tag icon={<LockOutlined />} color="blue">
                  {ta('roles.system')}
                </Tag>
              ) : (
                <Tag>{ta('roles.custom')}</Tag>
              ),
            },
            { label: ta('roles.nameVi'), value: role.nameVi },
            { label: ta('roles.nameEn'), value: role.nameEn },
            { label: ta('roles.permissionCount'), value: role.permissions.length },
            { label: ta('roles.templateVersion'), value: role.templateVersion, hideEmpty: true },
            { label: t('field.version'), value: role.version },
          ]}
        />
      </Card>

      <PermissionGate anyOf={P.PERMISSION_VIEW}>
        <PermissionMatrix role={role} readOnly={role.system || !canUpdate} />
      </PermissionGate>

      <RoleForm
        mode={form}
        onClose={() => setForm(null)}
        onSaved={(r) => {
          if (r.id !== role.id) void navigate({ to: '/core/roles/$roleId', params: { roleId: r.id } });
        }}
      />
    </div>
  );
}
