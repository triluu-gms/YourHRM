import { useMemo, useState } from 'react';
import { Button, Input, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { HistoryOutlined, LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { DataTable, PageHeader } from '@/shared/components';
import type { Setting } from './api';
import { useSettings } from './queries';
import { settingDescription } from './format';
import { LevelTag, SettingValueView } from './widgets';
import { SettingDrawer } from './SettingDrawer';
import { CONFIGURATION_NS } from './i18n';

/**
 * Tenant settings (BR-CORE-084, US-CORE-085): every setting the modules registered, with the value that applies to
 * the whole company today. The drawer shows the stored values per level, sets new ones and explains resolution.
 */
export function SettingsPage() {
  const { t, i18n } = useTranslation(CONFIGURATION_NS);
  const { t: tc } = useTranslation();
  const query = useSettings();
  const [search, setSearch] = useState('');
  const [module, setModule] = useState<string | undefined>();
  const [openKey, setOpenKey] = useState<string | null>(null);

  const modules = useMemo(
    () => [...new Set((query.data ?? []).map((s) => s.ownerModule))].sort(),
    [query.data],
  );
  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (query.data ?? []).filter(
      (s) =>
        (!module || s.ownerModule === module) &&
        (!q ||
          s.key.toLowerCase().includes(q) ||
          s.descriptionVi.toLowerCase().includes(q) ||
          s.descriptionEn.toLowerCase().includes(q)),
    );
  }, [query.data, search, module]);

  const moduleLabel = (m: string) => t(`module.${m}`, { defaultValue: m });

  return (
    <div>
      <PageHeader title={t('settings.title')} subTitle={t('settings.subtitle')} />

      <Space wrap style={{ marginBottom: 12 }}>
        <Input.Search
          allowClear
          placeholder={t('common.search')}
          aria-label={t('common.search')}
          style={{ width: 300 }}
          onChange={(e) => setSearch(e.target.value)}
          onSearch={setSearch}
        />
        <Select
          allowClear
          style={{ width: 220 }}
          placeholder={t('common.allModules')}
          aria-label={t('common.module')}
          value={module}
          onChange={setModule}
          options={modules.map((m) => ({ value: m, label: moduleLabel(m) }))}
        />
      </Space>

      <DataTable<Setting>
        rowKey="key"
        data={rows}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: t('settings.field.setting'),
            key: 'setting',
            render: (_: unknown, s) => (
              <Space direction="vertical" size={0}>
                <Button type="link" style={{ padding: 0, height: 'auto', whiteSpace: 'normal', textAlign: 'left' }} onClick={() => setOpenKey(s.key)}>
                  {settingDescription(s, i18n.language)}
                </Button>
                <Typography.Text type="secondary" style={{ fontFamily: 'monospace', fontSize: 12 }}>
                  {s.key}
                </Typography.Text>
              </Space>
            ),
          },
          {
            title: t('common.module'),
            dataIndex: 'ownerModule',
            render: (m: string) => <Tag>{moduleLabel(m)}</Tag>,
          },
          {
            title: t('settings.field.type'),
            dataIndex: 'valueType',
            render: (type: Setting['valueType'], s) => (
              <Space size={4}>
                {t(`type.${type}`)}
                {s.effectiveDated ? (
                  <Tooltip title={t('settings.effectiveDatedHint')}>
                    <HistoryOutlined aria-label={t('settings.field.effectiveDated')} />
                  </Tooltip>
                ) : null}
                {s.sensitive ? (
                  <Tooltip title={t('settings.sensitiveHint')}>
                    <LockOutlined aria-label={t('common.masked')} />
                  </Tooltip>
                ) : null}
              </Space>
            ),
          },
          {
            title: t('settings.field.currentValue'),
            key: 'tenantValue',
            render: (_: unknown, s) => <SettingValueView type={s.valueType} value={s.tenantValue} />,
          },
          {
            title: t('settings.field.default'),
            key: 'default',
            render: (_: unknown, s) => <SettingValueView type={s.valueType} value={s.defaultValue} />,
          },
          {
            title: t('settings.field.levels'),
            dataIndex: 'allowedLevels',
            render: (levels: Setting['allowedLevels']) => (
              <Space size={2} wrap>
                {levels.map((l) => (
                  <LevelTag key={l} level={l} />
                ))}
              </Space>
            ),
          },
          {
            title: t('settings.field.overrides'),
            key: 'values',
            render: (_: unknown, s) => (
              <Typography.Text type={s.values.length ? undefined : 'secondary'}>
                {t('settings.overrideCount', { count: s.values.length })}
              </Typography.Text>
            ),
          },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_: unknown, s) => (
              <Button size="small" onClick={() => setOpenKey(s.key)} aria-label={`${tc('action.view')} ${s.key}`}>
                {tc('action.view')}
              </Button>
            ),
          },
        ]}
      />

      {openKey ? <SettingDrawer settingKey={openKey} onClose={() => setOpenKey(null)} /> : null}
    </div>
  );
}
