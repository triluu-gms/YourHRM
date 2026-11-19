import { useState } from 'react';
import { Checkbox, Col, Empty, Input, Row, Space, Tag, Typography } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { DataTable, StatusTag } from '@/shared/components';
import { formatValidity, localizedName } from '@/shared/format';
import type { CodeList, CodeValue } from './api';
import { useCodeLists, useCodeValues } from './queries';
import { MASTER_DATA_NS } from './i18n';

/**
 * Code lists (BR-CORE-082): the global lists on the left, the values of the selected one on the right. A value
 * marked `tenantValue` was added or overridden by this tenant on an extensible list.
 */
export function CodeListsPanel() {
  const { t, i18n } = useTranslation();
  const { t: tm } = useTranslation(MASTER_DATA_NS);
  const [selected, setSelected] = useState<CodeList | null>(null);
  const [q, setQ] = useState('');
  const [includeInactive, setIncludeInactive] = useState(false);
  const lists = useCodeLists();
  const values = useCodeValues(selected?.listCode, q, includeInactive);

  return (
    <Row gutter={16}>
      <Col xs={24} lg={9}>
        <Typography.Title level={5} style={{ marginTop: 0 }}>
          {tm('codeList.list')}
        </Typography.Title>
        <DataTable<CodeList>
          rowKey="listCode"
          data={lists.data}
          isLoading={lists.isLoading}
          error={lists.error}
          onRow={(row) => ({ onClick: () => setSelected(row), style: { cursor: 'pointer' } })}
          rowClassName={(row) => (row.listCode === selected?.listCode ? 'ant-table-row-selected' : '')}
          columns={[
            { title: t('field.code'), dataIndex: 'listCode', render: (c: string) => <code>{c}</code> },
            { title: t('field.name'), key: 'name', render: (_, row) => localizedName(row, i18n.language) },
            { title: tm('codeList.owner'), dataIndex: 'ownerModule' },
            {
              title: tm('codeList.statutory'),
              dataIndex: 'statutory',
              align: 'center',
              render: (v: boolean) => (v ? <CheckOutlined aria-label={tm('codeList.statutory')} /> : null),
            },
            {
              title: tm('codeList.tenantExtensible'),
              dataIndex: 'tenantExtensible',
              align: 'center',
              render: (v: boolean) => (v ? <CheckOutlined aria-label={tm('codeList.tenantExtensible')} /> : null),
            },
          ]}
        />
      </Col>
      <Col xs={24} lg={15}>
        {selected ? (
          <>
            <Typography.Title level={5} style={{ marginTop: 0 }}>
              {tm('codeList.values', { list: selected.listCode })}
            </Typography.Title>
            <Space wrap style={{ marginBottom: 12 }}>
              <Input.Search
                allowClear
                aria-label={tm('codeList.searchValues')}
                placeholder={tm('codeList.searchValues')}
                style={{ width: 240 }}
                onSearch={setQ}
              />
              <Checkbox checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)}>
                {tm('codeList.includeInactive')}
              </Checkbox>
            </Space>
            <DataTable<CodeValue>
              rowKey="valueCode"
              data={values.data}
              isLoading={values.isLoading}
              error={values.error}
              locale={{ emptyText: tm('codeList.empty') }}
              columns={[
                { title: tm('codeList.value'), dataIndex: 'valueCode', render: (c: string) => <code>{c}</code> },
                {
                  title: tm('codeList.label'),
                  key: 'label',
                  render: (_, row) => localizedName({ nameVi: row.labelVi, nameEn: row.labelEn }, i18n.language),
                },
                {
                  title: tm('codeList.attributes'),
                  key: 'attributes',
                  render: (_, row) => <Attributes attributes={row.attributes} />,
                },
                {
                  title: tm('codeList.validity'),
                  key: 'validity',
                  render: (_, row) =>
                    row.validFrom ? formatValidity(row.validFrom, row.validTo, t('common.openEnded')) : null,
                },
                {
                  title: tm('codeList.tenantValue'),
                  dataIndex: 'tenantValue',
                  align: 'center',
                  render: (v: boolean) => (v ? <CheckOutlined aria-label={tm('codeList.tenantValue')} /> : null),
                },
                {
                  title: t('field.status'),
                  dataIndex: 'active',
                  render: (a: boolean) => <StatusTag status={a ? 'ACTIVE' : 'INACTIVE'} />,
                },
              ]}
            />
          </>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={tm('codeList.pickList')} />
        )}
      </Col>
    </Row>
  );
}

/** The free-form attributes a value carries (e.g. a statutory rate), as compact key: value tags. */
function Attributes({ attributes }: { attributes: Record<string, unknown> }) {
  const entries = Object.entries(attributes ?? {});
  if (entries.length === 0) return null;
  return (
    <Space size={[4, 4]} wrap>
      {entries.map(([k, v]) => (
        <Tag key={k}>
          {k}: {typeof v === 'object' ? JSON.stringify(v) : String(v)}
        </Tag>
      ))}
    </Space>
  );
}
