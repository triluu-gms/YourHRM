import { Card, Table, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useMe, type EffectivePermission, type RoleGrant } from '@/shared/auth';
import { DescriptionList, PageHeader } from '@/shared/components';
import { formatValidity, localizedName } from '@/shared/format';
import '../../i18n';

/** Read-only view of `/me` (US-CORE-041): identity, roles with scopes, effective permissions. */
export function MePage() {
  const { t, i18n } = useTranslation();
  const { t: tc } = useTranslation('core');
  const me = useMe();

  return (
    <div>
      <PageHeader title={tc('me.title')} />
      <Card style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: tc('me.login'), value: me.user.login },
            { label: tc('me.email'), value: me.user.email },
            { label: t('field.status'), value: me.user.status },
            { label: tc('me.tenant'), value: me.tenant.name ?? me.tenant.slug },
            { label: tc('me.locale'), value: me.locale },
            { label: t('field.asOf'), value: me.asOf },
          ]}
        />
      </Card>
      <Card title={tc('me.roles')} style={{ marginBottom: 16 }}>
        <Table<RoleGrant>
          size="small"
          rowKey="assignmentId"
          pagination={false}
          dataSource={me.roles}
          columns={[
            { title: t('field.code'), dataIndex: 'code' },
            { title: t('field.name'), render: (_, r) => localizedName(r, i18n.language) },
            {
              title: tc('me.scope'),
              render: (_, r) => (
                <>
                  <Tag>{tc(`me.scopeType.${r.scopeType}`, { defaultValue: r.scopeType })}</Tag>
                  {r.includeSubtree ? <Typography.Text type="secondary">{tc('me.includeSubtree')}</Typography.Text> : null}
                </>
              ),
            },
            { title: t('field.validFrom'), render: (_, r) => formatValidity(r.validFrom, r.validTo, t('common.openEnded')) },
          ]}
        />
      </Card>
      <Card title={tc('me.permissions')}>
        <Table<EffectivePermission>
          size="small"
          rowKey="code"
          pagination={{ pageSize: 25, showSizeChanger: false }}
          dataSource={me.permissions}
          columns={[
            { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
            {
              title: tc('me.scope'),
              render: (_, p) =>
                p.scopes.map((s, i) => (
                  <Tag key={i}>
                    {tc(`me.scopeType.${s.scopeType}`, { defaultValue: s.scopeType })}
                    {s.fieldMaskLevel && s.fieldMaskLevel !== 'NONE' ? ` · ${s.fieldMaskLevel}` : ''}
                  </Tag>
                )),
            },
          ]}
        />
      </Card>
    </div>
  );
}
