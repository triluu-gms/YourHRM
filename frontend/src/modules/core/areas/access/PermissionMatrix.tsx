import { useMemo, useState } from 'react';
import { Alert, Button, Card, Checkbox, Empty, Input, Select, Space, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { ProblemAlert, useFeedback } from '@/shared/components';
import { localizedName } from '@/shared/format';
import { FIELD_MASK_LEVELS, type PermissionView, type RoleDetail, type RoleFieldMaskLevel } from './api';
import { usePermissionCatalogue, useReplaceRolePermissions } from './queries';
import './i18n';

type Draft = Map<string, RoleFieldMaskLevel>;

function toDraft(role: RoleDetail): Draft {
  return new Map(role.permissions.map((g) => [g.code, g.fieldMaskLevel ?? 'NONE']));
}

function sameDraft(a: Draft, b: Draft): boolean {
  if (a.size !== b.size) return false;
  for (const [code, mask] of a) if (b.get(code) !== mask) return false;
  return true;
}

interface Row {
  permission: PermissionView;
  /** rowSpan of the function cell: group size on the first row of a function, 0 on the others. */
  span: number;
  siblings: string[];
}

/**
 * Permission matrix of one role (US-CORE-034): the active catalogue grouped by module (tabs) and function, one
 * checkbox per permission plus its field mask level. Saved as a whole (PUT `/roles/{id}/permissions` with the
 * role version). Read-only for system roles and without `core.role.update`.
 */
export function PermissionMatrix({ role, readOnly }: { role: RoleDetail; readOnly: boolean }) {
  // Remount on a new role version so the draft starts from the saved matrix (no effect-based resync).
  return <MatrixEditor key={`${role.id}:${role.version}`} role={role} readOnly={readOnly} />;
}

function MatrixEditor({ role, readOnly }: { role: RoleDetail; readOnly: boolean }) {
  const { t, i18n } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const fb = useFeedback();
  const catalogue = usePermissionCatalogue(false);
  const save = useReplaceRolePermissions(role.id);
  const original = useMemo(() => toDraft(role), [role]);
  const [draft, setDraft] = useState<Draft>(original);
  const [filter, setFilter] = useState('');
  const [activeModule, setActiveModule] = useState<string | undefined>();

  const known = useMemo(() => new Set((catalogue.data ?? []).map((p) => p.code)), [catalogue.data]);
  /** Grants of permissions that are no longer in the active catalogue; the backend refuses them on save. */
  const staleGrants = useMemo(
    () => (catalogue.data ? [...draft.keys()].filter((c) => !known.has(c)).sort() : []),
    [catalogue.data, draft, known],
  );
  const dirty = !sameDraft(draft, original);

  const modules = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    const byModule = new Map<string, PermissionView[]>();
    for (const p of catalogue.data ?? []) {
      const hit =
        !needle ||
        p.code.toLowerCase().includes(needle) ||
        p.functionCode.toLowerCase().includes(needle) ||
        localizedName(p, i18n.language).toLowerCase().includes(needle);
      if (!hit) continue;
      const list = byModule.get(p.module) ?? [];
      list.push(p);
      byModule.set(p.module, list);
    }
    return [...byModule.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([module, perms]) => {
        perms.sort((a, b) => a.functionCode.localeCompare(b.functionCode) || a.code.localeCompare(b.code));
        const rows: Row[] = [];
        for (let i = 0; i < perms.length; ) {
          let j = i;
          while (j < perms.length && perms[j]!.functionCode === perms[i]!.functionCode) j++;
          const siblings = perms.slice(i, j).map((p) => p.code);
          for (let k = i; k < j; k++) rows.push({ permission: perms[k]!, span: k === i ? j - i : 0, siblings });
          i = j;
        }
        return { module, rows };
      });
  }, [catalogue.data, filter, i18n.language]);

  const toggle = (codes: string[], on: boolean) =>
    setDraft((prev) => {
      const next = new Map(prev);
      for (const c of codes) {
        if (on) {
          if (!next.has(c)) next.set(c, 'NONE');
        } else next.delete(c);
      }
      return next;
    });

  const setMask = (code: string, mask: RoleFieldMaskLevel) =>
    setDraft((prev) => new Map(prev).set(code, mask));

  const onSave = async () => {
    try {
      await save.mutateAsync({
        permissions: [...draft].filter(([code]) => known.has(code)).map(([code, fieldMaskLevel]) => ({ code, fieldMaskLevel })),
        version: role.version,
      });
      fb.saved(ta('roles.matrix.saved'));
    } catch (e) {
      fb.failed(e);
    }
  };

  const selectedIn = (rows: Row[]) => rows.filter((r) => draft.has(r.permission.code)).length;
  const current = modules.find((m) => m.module === activeModule) ?? modules[0];

  return (
    <Card
      title={ta('roles.matrix.title')}
      extra={
        <Space wrap>
          <Typography.Text type="secondary">{ta('roles.matrix.selected', { count: draft.size })}</Typography.Text>
          {dirty ? <Tag color="gold">{ta('roles.matrix.unsaved')}</Tag> : null}
          {!readOnly ? (
            <>
              <Button disabled={!dirty || save.isPending} onClick={() => setDraft(original)}>
                {ta('roles.matrix.reset')}
              </Button>
              <Button type="primary" disabled={!dirty && staleGrants.length === 0} loading={save.isPending} onClick={() => void onSave()}>
                {t('action.save')}
              </Button>
            </>
          ) : null}
        </Space>
      }
    >
      {role.system ? <Alert type="info" showIcon message={ta('roles.matrix.readOnly')} style={{ marginBottom: 12 }} /> : null}
      {!readOnly && staleGrants.length > 0 ? (
        <Alert
          type="warning"
          showIcon
          message={ta('roles.matrix.staleGrants', { codes: staleGrants.join(', ') })}
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {save.error ? <ProblemAlert error={save.error} style={{ marginBottom: 12 }} /> : null}
      {catalogue.error ? <ProblemAlert error={catalogue.error} style={{ marginBottom: 12 }} /> : null}

      <Input.Search
        allowClear
        aria-label={ta('roles.matrix.search')}
        placeholder={ta('roles.matrix.search')}
        style={{ maxWidth: 360, marginBottom: 12 }}
        onChange={(e) => setFilter(e.target.value)}
      />

      {!catalogue.isLoading && modules.length === 0 ? (
        <Empty description={ta('roles.matrix.noMatch')} />
      ) : (
        <Tabs
          aria-label={ta('roles.matrix.modules')}
          activeKey={current?.module}
          onChange={setActiveModule}
          items={modules.map(({ module, rows }) => ({
            key: module,
            label: `${module} (${selectedIn(rows)}/${rows.length})`,
          }))}
        />
      )}

      {current ? (
        <Table<Row>
          size="small"
          rowKey={(r) => r.permission.code}
          dataSource={current.rows}
          loading={catalogue.isLoading}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: ta('roles.matrix.function'),
              key: 'function',
              onCell: (r) => ({ rowSpan: r.span }),
              render: (_, r) => {
                const n = r.siblings.filter((c) => draft.has(c)).length;
                return (
                  <Checkbox
                    aria-label={ta('roles.matrix.selectAllRow', { function: r.permission.functionCode })}
                    disabled={readOnly}
                    checked={n === r.siblings.length}
                    indeterminate={n > 0 && n < r.siblings.length}
                    onChange={(e) => toggle(r.siblings, e.target.checked)}
                  >
                    <code>{r.permission.functionCode}</code>
                  </Checkbox>
                );
              },
            },
            {
              title: ta('permissions.action'),
              key: 'permission',
              render: (_, { permission: p }) => (
                <Checkbox
                  aria-label={ta('roles.matrix.toggle', { code: p.code })}
                  disabled={readOnly}
                  checked={draft.has(p.code)}
                  onChange={(e) => toggle([p.code], e.target.checked)}
                >
                  <Space direction="vertical" size={0}>
                    <Typography.Text strong>{p.action}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {localizedName(p, i18n.language)} · <code>{p.code}</code>
                    </Typography.Text>
                  </Space>
                </Checkbox>
              ),
            },
            {
              title: ta('permissions.flags'),
              key: 'flags',
              render: (_, { permission: p }) => <PermissionFlags permission={p} />,
            },
            {
              title: (
                <Tooltip title={ta('roles.matrix.maskHint')}>
                  <span>{ta('roles.matrix.mask')}</span>
                </Tooltip>
              ),
              key: 'mask',
              render: (_, { permission: p }) => {
                const mask = draft.get(p.code);
                // Masking matters for permissions that read sensitive data; keep any non-default level visible.
                if (!p.sensitive && (mask === undefined || mask === 'NONE')) {
                  return <Typography.Text type="secondary">—</Typography.Text>;
                }
                return (
                  <Select<RoleFieldMaskLevel>
                    size="small"
                    aria-label={ta('roles.matrix.maskFor', { code: p.code })}
                    style={{ width: 220 }}
                    disabled={readOnly || mask === undefined}
                    value={mask ?? 'NONE'}
                    onChange={(v) => setMask(p.code, v)}
                    options={FIELD_MASK_LEVELS.map((m) => ({ value: m, label: ta(`roles.maskLevel.${m}`) }))}
                  />
                );
              },
            },
          ]}
        />
      ) : null}
    </Card>
  );
}

/** Sensitive / privileged / step-up / inactive tags of a catalogue permission. */
export function PermissionFlags({ permission: p }: { permission: PermissionView }) {
  const { t: ta } = useTranslation('core-access');
  return (
    <Space size={4} wrap>
      {p.sensitive ? <Tag color="orange">{ta('permissions.sensitive')}</Tag> : null}
      {p.privileged ? <Tag color="red">{ta('permissions.privileged')}</Tag> : null}
      {p.requiresStepUp ? <Tag color="purple">{ta('permissions.stepUp')}</Tag> : null}
      {!p.active ? <Tag>{ta('permissions.inactive')}</Tag> : null}
    </Space>
  );
}
