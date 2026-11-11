import { useState } from 'react';
import { Button, Card, Checkbox, Space, Tag, Tooltip, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, useFeedback } from '@/shared/components';
import { formatValidity } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { AssignmentView } from './api';
import { useRevokeRole, useRoleAssignments } from './queries';
import { AssignRoleDrawer } from './AssignRoleDrawer';
import './i18n';

/** Role assignments of one user (US-CORE-035): table + "Assign role" drawer + revoke with confirmation. */
export function RoleAssignmentsCard({ userId }: { userId: UUID }) {
  const { t } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const fb = useFeedback();
  const [includeHistory, setIncludeHistory] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const query = useRoleAssignments(userId, includeHistory);
  const revoke = useRevokeRole(userId);

  const onRevoke = async (assignmentId: UUID) => {
    try {
      await revoke.mutateAsync(assignmentId);
      fb.done(ta('assignments.revoked'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <Card
      title={ta('assignments.title')}
      extra={
        <Space wrap>
          <Checkbox checked={includeHistory} onChange={(e) => setIncludeHistory(e.target.checked)}>
            {ta('assignments.includeHistory')}
          </Checkbox>
          <PermissionGate anyOf={P.ROLE_ASSIGNMENT_CREATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setAssigning(true)}>
              {ta('assignments.assign')}
            </Button>
          </PermissionGate>
        </Space>
      }
    >
      <DataTable<AssignmentView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: ta('assignments.role'),
            dataIndex: 'roleCode',
            render: (code: string, row) => (
              <Link to="/core/roles/$roleId" params={{ roleId: row.roleId }}>
                {code}
              </Link>
            ),
          },
          {
            title: ta('assignments.scopeType'),
            dataIndex: 'scopeType',
            render: (s: AssignmentView['scopeType']) => <Tag>{ta(`assignments.scope.${s}`, { defaultValue: s })}</Tag>,
          },
          {
            title: ta('assignments.scopeIds'),
            dataIndex: 'scopeIds',
            render: (ids: UUID[]) =>
              ids.length === 0 ? (
                <Typography.Text type="secondary">—</Typography.Text>
              ) : (
                <Tooltip title={ids.join(', ')}>
                  <span>{ta('assignments.scopeCount', { count: ids.length })}</span>
                </Tooltip>
              ),
          },
          {
            title: ta('assignments.includeSubtree'),
            dataIndex: 'includeSubtree',
            render: (v: boolean) => (v ? t('common.yes') : t('common.no')),
          },
          {
            title: ta('assignments.validity'),
            render: (_, row) => formatValidity(row.validFrom, row.validTo, t('common.openEnded')),
          },
          {
            title: t('field.status'),
            dataIndex: 'activeToday',
            render: (v: boolean) =>
              v ? <Tag color="green">{ta('assignments.activeToday')}</Tag> : <Tag>{ta('assignments.inactive')}</Tag>,
          },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, row) => (
              <PermissionGate anyOf={P.ROLE_ASSIGNMENT_DELETE}>
                <ConfirmButton
                  danger
                  size="small"
                  type="link"
                  title={ta('assignments.confirmRevoke')}
                  onConfirm={() => onRevoke(row.id)}
                >
                  {t('action.delete')}
                </ConfirmButton>
              </PermissionGate>
            ),
          },
        ]}
      />
      <AssignRoleDrawer userId={userId} open={assigning} onClose={() => setAssigning(false)} />
    </Card>
  );
}
