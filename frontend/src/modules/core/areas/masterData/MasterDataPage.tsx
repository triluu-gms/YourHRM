import { useState } from 'react';
import { Input, Space, Tabs, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { DataTable, PageHeader } from '@/shared/components';
import { formatDate, formatDateTime, formatNumber, localizedName } from '@/shared/format';
import type { Country, Currency, MasterDataRelease } from './api';
import { useCountries, useCurrencies, useMasterDataReleases } from './queries';
import { AdminUnitsPanel } from './AdminUnitsPanel';
import { CodeListsPanel } from './CodeListsPanel';
import { BanksPanel } from './BanksPanel';
import { MASTER_DATA_NS } from './i18n';

/**
 * Global master data browser (BR-CORE-080..082, US-CORE-080/081/082): administrative units with the old → new
 * resolver, code lists, banks, countries, currencies and the loaded releases. Read-only — a tenant never writes
 * reference data, the operator loads releases.
 */
export function MasterDataPage() {
  const { t: tm } = useTranslation(MASTER_DATA_NS);
  const [tab, setTab] = useState('admin-units');
  return (
    <div>
      <PageHeader title={tm('page.title')} subTitle={tm('page.subtitle')} />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        destroyOnHidden
        items={[
          { key: 'admin-units', label: tm('tab.adminUnits'), children: <AdminUnitsPanel /> },
          { key: 'code-lists', label: tm('tab.codeLists'), children: <CodeListsPanel /> },
          { key: 'banks', label: tm('tab.banks'), children: <BanksPanel /> },
          { key: 'countries', label: tm('tab.countries'), children: <CountriesPanel /> },
          { key: 'currencies', label: tm('tab.currencies'), children: <CurrenciesPanel /> },
          { key: 'releases', label: tm('tab.releases'), children: <ReleasesPanel /> },
        ]}
      />
    </div>
  );
}

function CountriesPanel() {
  const { t, i18n } = useTranslation();
  const { t: tm } = useTranslation(MASTER_DATA_NS);
  const [q, setQ] = useState('');
  const query = useCountries(q);
  return (
    <div>
      <Input.Search
        allowClear
        aria-label={tm('country.search')}
        placeholder={tm('country.search')}
        style={{ width: 240, marginBottom: 12 }}
        onSearch={setQ}
      />
      <DataTable<Country>
        rowKey="code"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: tm('country.empty') }}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: tm('country.iso3'), dataIndex: 'iso3' },
          { title: t('field.name'), key: 'name', render: (_, row) => localizedName(row, i18n.language) },
          {
            title: tm('country.nationality'),
            key: 'nationality',
            render: (_, row) =>
              localizedName({ nameVi: row.nationalityVi, nameEn: row.nationalityEn }, i18n.language),
          },
        ]}
      />
    </div>
  );
}

function CurrenciesPanel() {
  const { t, i18n } = useTranslation();
  const { t: tm } = useTranslation(MASTER_DATA_NS);
  const query = useCurrencies();
  return (
    <DataTable<Currency>
      rowKey="code"
      data={query.data}
      isLoading={query.isLoading}
      error={query.error}
      locale={{ emptyText: tm('currency.empty') }}
      columns={[
        { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
        { title: t('field.name'), key: 'name', render: (_, row) => localizedName(row, i18n.language) },
        { title: tm('currency.minorUnits'), dataIndex: 'minorUnits', align: 'right' },
      ]}
    />
  );
}

/** What the operator has loaded: which datasets, how many items and when (US-CORE-082). */
function ReleasesPanel() {
  const { t: tm } = useTranslation(MASTER_DATA_NS);
  const query = useMasterDataReleases();
  return (
    <DataTable<MasterDataRelease>
      rowKey="releaseNo"
      data={query.data}
      isLoading={query.isLoading}
      error={query.error}
      locale={{ emptyText: tm('release.empty') }}
      columns={[
        { title: tm('release.releaseNo'), dataIndex: 'releaseNo', align: 'right' },
        { title: tm('release.title'), dataIndex: 'title' },
        {
          title: tm('release.datasets'),
          key: 'datasets',
          render: (_, row) => (
            <Space size={[4, 4]} wrap>
              {row.datasets.map((d) => (
                <Tag key={d}>{d}</Tag>
              ))}
            </Space>
          ),
        },
        {
          title: tm('release.itemCounts'),
          key: 'itemCounts',
          render: (_, row) => (
            <Space size={[4, 4]} wrap>
              {Object.entries(row.itemCounts ?? {}).map(([k, v]) => (
                <Tag key={k}>
                  {k}: {typeof v === 'number' ? formatNumber(v) : String(v)}
                </Tag>
              ))}
            </Space>
          ),
        },
        { title: tm('release.effectiveDate'), key: 'effective', render: (_, row) => formatDate(row.effectiveDate) },
        { title: tm('release.publishedAt'), key: 'published', render: (_, row) => formatDateTime(row.publishedAt) },
        { title: tm('release.loadedAt'), key: 'loaded', render: (_, row) => formatDateTime(row.loadedAt) },
      ]}
    />
  );
}
