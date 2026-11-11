import { useMemo, useState } from 'react';
import { Select, type SelectProps } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { localizedName } from '@/shared/format';
import {
  codeLabel,
  legalEntityLabel,
  lookupApi,
  useAdminUnitChildren,
  useAllGrades,
  useBanks,
  useCodeValues,
  useCostCentres,
  useCountries,
  useEmployeeCategories,
  useJobTitles,
  useLegalEntities,
  useLocations,
  useOrgUnits,
  useProvinces,
} from './lookups';
import { EMPLOYMENT_STATUSES, type EmploymentStatus } from './types';
import './i18n';

/**
 * Ant `Select`s bound to Core lookups, usable directly as `Form.Item` children (value/onChange injected).
 * All are searchable by label; pass `filter` props to narrow (e.g. org units of one legal entity).
 */
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
    .map((ou) => ({
      value: ou.id,
      label: `${'· '.repeat(ou.depth ?? 0)}${ou.code} – ${localizedName(ou, i18n.language)}`,
    }));
  return <Select<UUID> {...common} loading={q.isLoading} options={options} {...props} />;
}

export function LocationSelect({ legalEntityId, ...props }: Base<UUID> & { legalEntityId?: UUID | null }) {
  const q = useLocations();
  const options = (q.data ?? [])
    .filter((l) => !legalEntityId || l.legalEntityId === legalEntityId)
    .map((l) => ({ value: l.id, label: `${l.code} – ${l.current?.name ?? ''}` }));
  return <Select<UUID> {...common} loading={q.isLoading} options={options} {...props} />;
}

export function JobTitleSelect(props: Base<UUID>) {
  const { i18n } = useTranslation();
  const q = useJobTitles();
  const options = (q.data ?? [])
    .filter((j) => j.active !== false || j.id === props.value)
    .map((j) => ({ value: j.id, label: `${j.code} – ${localizedName(j, i18n.language)}` }));
  return <Select<UUID> {...common} loading={q.isLoading} options={options} {...props} />;
}

export function GradeSelect(props: Base<UUID>) {
  const { structures, grades, isLoading } = useAllGrades();
  const options = structures.map((s) => ({
    label: `${s.code} – ${s.name}`,
    options: grades
      .filter((g) => g.gradeStructureId === s.id && (g.active || g.id === props.value))
      .sort((a, b) => a.rankOrder - b.rankOrder)
      .map((g) => ({ value: g.id, label: `${g.code} – ${g.name}` })),
  }));
  return <Select<UUID> {...common} loading={isLoading} options={options} {...props} />;
}

export function CostCentreSelect({ legalEntityId, ...props }: Base<UUID> & { legalEntityId?: UUID | null }) {
  const q = useCostCentres();
  const options = (q.data ?? [])
    .filter((c) => !legalEntityId || c.legalEntityId === legalEntityId)
    .map((c) => ({ value: c.id, label: `${c.code} – ${c.name}` }));
  return <Select<UUID> {...common} loading={q.isLoading} options={options} {...props} />;
}

/** Employee category by **code** (People stores `employee_category_code`). */
export function EmployeeCategorySelect(props: Base<string>) {
  const { i18n } = useTranslation();
  const q = useEmployeeCategories();
  const options = (q.data ?? [])
    .filter((c) => c.active !== false || c.code === props.value)
    .map((c) => ({ value: c.code, label: `${c.code} – ${localizedName(c, i18n.language)}` }));
  return <Select<string> {...common} loading={q.isLoading} options={options} {...props} />;
}

/** Values of one Core code list; inactive values are hidden unless currently selected. */
export function CodeValueSelect({ list, ...props }: Base<string> & { list: string }) {
  const { i18n } = useTranslation();
  const q = useCodeValues(list);
  const selected = Array.isArray(props.value) ? (props.value as string[]) : [props.value];
  const options = (q.data ?? [])
    .filter((v) => v.active || selected.includes(v.valueCode))
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((v) => ({ value: v.valueCode, label: codeLabel(v, v.valueCode, i18n.language) }));
  return <Select<string> {...common} loading={q.isLoading} options={options} {...props} />;
}

export function CountrySelect({ nationality = false, ...props }: Base<string> & { nationality?: boolean }) {
  const { i18n } = useTranslation();
  const q = useCountries();
  const en = i18n.language.startsWith('en');
  const options = (q.data ?? []).map((c) => ({
    value: c.code,
    label: `${(nationality ? (en ? c.nationalityEn : c.nationalityVi) : en ? c.nameEn : c.nameVi) || c.nameVi || c.code} (${c.code})`,
  }));
  return <Select<string> {...common} loading={q.isLoading} options={options} {...props} />;
}

export function BankSelect(props: Base<string>) {
  const { i18n } = useTranslation();
  const q = useBanks();
  const options = (q.data ?? [])
    .filter((b) => b.active || b.bankCode === props.value)
    .map((b) => ({
      value: b.bankCode,
      label: `${b.shortName ?? b.bankCode} – ${(i18n.language.startsWith('en') ? b.nameEn : b.nameVi) ?? ''}`,
    }));
  return <Select<string> {...common} loading={q.isLoading} options={options} {...props} />;
}

/** Province (new 2-level model) or, with `parent`, its wards/communes. Values are admin-unit codes. */
export function AdminUnitSelect({ parent, ...props }: Base<string> & { parent?: string | null }) {
  const { i18n } = useTranslation();
  const provinces = useProvinces();
  const children = useAdminUnitChildren(parent);
  const q = parent ? children : provinces;
  const options = (parent ? (q.data ?? []) : (provinces.data ?? [])).map((u) => ({
    value: u.code,
    label: localizedName(u, i18n.language) || u.code,
  }));
  return <Select<string> {...common} loading={q.isLoading} disabled={parent === null} options={options} {...props} />;
}

export function EmploymentStatusSelect(props: Base<EmploymentStatus>) {
  const { t } = useTranslation('people');
  return (
    <Select<EmploymentStatus>
      allowClear
      options={EMPLOYMENT_STATUSES.map((s) => ({ value: s, label: t(`employmentStatus.${s}`) }))}
      {...props}
    />
  );
}

/**
 * Searches employees (GET /people/employees, scoped by the backend) as the user types. The value is an
 * `employmentId`; `valueField="personId"` returns the person instead.
 */
export function EmployeeSelect({
  valueField = 'employmentId',
  legalEntityId,
  ...props
}: Base<UUID> & { valueField?: 'employmentId' | 'personId'; legalEntityId?: UUID | null }) {
  const { t } = useTranslation('people');
  const [q, setQ] = useState('');
  const query = useQuery({
    queryKey: ['people', 'lookup', 'employee-search', q, legalEntityId ?? ''],
    queryFn: () => lookupApi.employees({ q: q || undefined, legalEntityId: legalEntityId ?? undefined, size: 20 }),
    staleTime: 30_000,
  });
  const options = useMemo(
    () =>
      (query.data?.items ?? []).map((e) => ({
        value: e[valueField],
        label: `${e.employeeNo} – ${e.fullName}`,
      })),
    [query.data, valueField],
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
