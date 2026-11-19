import { useState } from 'react';
import { Button, Descriptions, Drawer, Input, Space, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, SwapOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { AsOfPicker, DataTable, ProblemAlert } from '@/shared/components';
import { formatValidity, localizedName, today } from '@/shared/format';
import type { AdminUnit } from './api';
import { useAdminUnits, useAdminUnitSearch, useResolveAdminUnit } from './queries';
import { MASTER_DATA_NS } from './i18n';

/**
 * Administrative units (BR-CORE-080): the provinces of the 34-province structure, their wards, and the old → new
 * resolver (BR-CORE-081). Everything is read-only; a new release replaces the data.
 */
export function AdminUnitsPanel() {
  const { t } = useTranslation();
  const { t: tm } = useTranslation(MASTER_DATA_NS);
  const [asOf, setAsOf] = useState(today);
  const [q, setQ] = useState('');
  const [parent, setParent] = useState<AdminUnit | null>(null);
  const [resolving, setResolving] = useState<string | null>(null);

  const search = useAdminUnitSearch(q, asOf);
  const browse = useAdminUnits(parent?.code, parent ? undefined : 'PROVINCE', asOf);
  const searching = q.trim().length > 0;
  const query = searching ? search : browse;

  const title = searching
    ? tm('adminUnit.searchResults')
    : parent
      ? tm('adminUnit.children', { name: parent.nameVi })
      : tm('adminUnit.provinces');

  return (
    <div>
      <Space wrap style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Space wrap>
          <Input.Search
            allowClear
            aria-label={tm('adminUnit.search')}
            placeholder={tm('adminUnit.search')}
            style={{ width: 280 }}
            onSearch={setQ}
          />
          <AsOfPicker value={asOf} onChange={setAsOf} />
        </Space>
        {!searching && parent ? (
          <Button icon={<ArrowLeftOutlined />} onClick={() => setParent(null)}>
            {tm('adminUnit.back')}
          </Button>
        ) : null}
      </Space>

      <Typography.Title level={5} style={{ marginTop: 0 }}>
        {title}
      </Typography.Title>

      <DataTable<AdminUnit>
        rowKey="code"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: tm('adminUnit.empty') }}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.name'), key: 'name', render: (_, u) => <AdminUnitName unit={u} /> },
          {
            title: tm('adminUnit.level'),
            dataIndex: 'level',
            render: (level: string) => <Tag>{tm(`adminUnit.levelValue.${level}`, { defaultValue: level })}</Tag>,
          },
          {
            title: tm('adminUnit.validity'),
            key: 'validity',
            render: (_, u) => (u.validFrom ? formatValidity(u.validFrom, u.validTo, t('common.openEnded')) : null),
          },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, u) => (
              <Space size={4}>
                {u.level === 'PROVINCE' ? (
                  <Button
                    size="small"
                    aria-label={`${tm('adminUnit.open')} ${u.code}`}
                    onClick={() => {
                      setQ('');
                      setParent(u);
                    }}
                  >
                    {tm('adminUnit.open')}
                  </Button>
                ) : null}
                <Button
                  size="small"
                  icon={<SwapOutlined />}
                  aria-label={`${tm('adminUnit.resolve')} ${u.code}`}
                  onClick={() => setResolving(u.code)}
                />
              </Space>
            ),
          },
        ]}
      />

      <ResolveDrawer code={resolving} asOf={asOf} onClose={() => setResolving(null)} />
    </div>
  );
}

function AdminUnitName({ unit }: { unit: AdminUnit }) {
  const { i18n } = useTranslation();
  return <>{localizedName(unit, i18n.language)}</>;
}

/** Where a recorded code leads on `asOf`: current, replaced, split into candidates, or unknown. */
function ResolveDrawer({ code, asOf, onClose }: { code: string | null; asOf: string; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: tm } = useTranslation(MASTER_DATA_NS);
  const query = useResolveAdminUnit(code ?? undefined, asOf);
  const resolution = query.data;

  return (
    <Drawer
      open={!!code}
      onClose={onClose}
      width={520}
      title={code ? tm('adminUnit.resolveTitle', { code }) : null}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">{tm('adminUnit.resolveHint')}</Typography.Paragraph>
      {query.error ? <ProblemAlert error={query.error} style={{ marginBottom: 12 }} /> : null}
      {resolution ? (
        <>
          <Descriptions column={1} size="small" bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label={t('field.asOf')}>{asOf}</Descriptions.Item>
            <Descriptions.Item label={t('field.status')}>
              <Tag color={resolution.status === 'AMBIGUOUS' || resolution.status === 'UNKNOWN' ? 'orange' : 'green'}>
                {tm(`adminUnit.status.${resolution.status}`, { defaultValue: resolution.status })}
              </Tag>
            </Descriptions.Item>
            {resolution.note ? (
              <Descriptions.Item label={tm('adminUnit.note')}>{resolution.note}</Descriptions.Item>
            ) : null}
          </Descriptions>
          <Typography.Title level={5}>{tm('adminUnit.candidates')}</Typography.Title>
          <DataTable<AdminUnit>
            rowKey="code"
            data={resolution.candidates}
            isLoading={query.isLoading}
            locale={{ emptyText: tm('adminUnit.empty') }}
            columns={[
              { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
              { title: t('field.name'), key: 'name', render: (_, u) => <AdminUnitName unit={u} /> },
              {
                title: tm('adminUnit.level'),
                dataIndex: 'level',
                render: (level: string) => tm(`adminUnit.levelValue.${level}`, { defaultValue: level }),
              },
            ]}
          />
        </>
      ) : null}
    </Drawer>
  );
}
