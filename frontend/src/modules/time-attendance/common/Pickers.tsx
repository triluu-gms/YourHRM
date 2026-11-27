import { useMemo, useState } from 'react';
import { Select, type SelectProps } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { localizedName } from '@/shared/format';
import { legalEntityLabel, lookupApi, useLegalEntities, useLocations, useOrgUnits } from './lookups';
import { ATT_NS } from './i18n';

/** Ant `Select`s bound to Core / People lookups, usable directly as `Form.Item` children (value/onChange injected). */
type Base<V = string> = Omit<SelectProps<V>, 'options' | 'loading'>;

const common = { showSearch: true, optionFilterProp: 'label', allowClear: true } as const;

export function LegalEntitySelect(props: Base<UUID>) {
  const { i18n } = useTranslation();
  const q = useLegalEntities();
  const options = (q.data ?? []).map((le) => ({ value: le.id, label: legalEntityLabel(le, i18n.language) }));
  return <Select<UUID> {...common} loading={q.isLoading} options={options} {...props} />;
}

export function OrgUnitSelect({ legalEntityId, ...props }: Base<UUID> & { legalEntityId?: UUID | null }) {
  const { i18n } = useTranslation();
  const q = useOrgUnits();
  const options = (q.data ?? [])
    .filter((ou) => !legalEntityId || ou.legalEntityId === legalEntityId)
    .map((ou) => ({ value: ou.id, label: `${'· '.repeat(ou.depth ?? 0)}${ou.code} – ${localizedName(ou, i18n.language)}` }));
  return <Select<UUID> {...common} loading={q.isLoading} options={options} {...props} />;
}

export function LocationSelect({ legalEntityId, ...props }: Base<UUID> & { legalEntityId?: UUID | null }) {
  const q = useLocations();
  const options = (q.data ?? [])
    .filter((l) => !legalEntityId || l.legalEntityId === legalEntityId)
    .map((l) => ({ value: l.id, label: l.current?.name ? `${l.code} – ${l.current.name}` : l.code }));
  return <Select<UUID> {...common} loading={q.isLoading} options={options} {...props} />;
}

/** Searches employees (GET /people/employees, scoped by the backend) as the user types. The value is an `employmentId`. */
export function EmployeeSelect({ legalEntityId, ...props }: Base<UUID> & { legalEntityId?: UUID | null }) {
  const { t } = useTranslation(ATT_NS);
  const [q, setQ] = useState('');
  const query = useQuery({
    queryKey: ['att', 'lookup', 'employee-search', q, legalEntityId ?? ''],
    queryFn: () => lookupApi.employees({ q: q || undefined, legalEntityId: legalEntityId ?? undefined, size: 20 }),
    staleTime: 30_000,
  });
  const options = useMemo(
    () => (query.data?.items ?? []).map((e) => ({ value: e.employmentId, label: `${e.employeeNo} – ${e.fullName}` })),
    [query.data],
  );
  return (
    <Select<UUID>
      showSearch
      allowClear
      filterOption={false}
      onSearch={setQ}
      loading={query.isFetching}
      placeholder={t('common.searchEmployee')}
      notFoundContent={query.isFetching ? undefined : t('common.noEmployee')}
      options={options}
      {...props}
    />
  );
}
