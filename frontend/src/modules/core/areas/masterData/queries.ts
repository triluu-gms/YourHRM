import { useQuery } from '@tanstack/react-query';
import type { ISODate } from '@/shared/api';
import { masterDataApi, type AdminUnitLevel } from './api';

/** Reference data changes only with a back-office release, so it is cached for the session. */
const STALE = 10 * 60_000;

export const masterDataKeys = {
  all: ['core', 'master-data'] as const,
  adminUnits: (parent: string | undefined, level: string | undefined, asOf: string | undefined) =>
    ['core', 'master-data', 'admin-units', { parent: parent ?? null, level: level ?? null, asOf: asOf ?? null }] as const,
  adminUnitSearch: (q: string, asOf: string | undefined) =>
    ['core', 'master-data', 'admin-units', 'search', { q, asOf: asOf ?? null }] as const,
  resolve: (code: string, asOf: string | undefined) =>
    ['core', 'master-data', 'admin-units', code, 'resolve', { asOf: asOf ?? null }] as const,
  codeLists: ['core', 'master-data', 'code-lists'] as const,
  codeValues: (listCode: string, q: string, includeInactive: boolean) =>
    ['core', 'master-data', 'code-lists', listCode, 'values', { q, includeInactive }] as const,
  countries: (q: string) => ['core', 'master-data', 'countries', { q }] as const,
  currencies: ['core', 'master-data', 'currencies'] as const,
  banks: (q: string, includeInactive: boolean) => ['core', 'master-data', 'banks', { q, includeInactive }] as const,
  branches: (bankCode: string, q: string) => ['core', 'master-data', 'banks', bankCode, 'branches', { q }] as const,
  releases: ['core', 'master-data', 'releases'] as const,
};

export function useAdminUnits(parent: string | undefined, level: AdminUnitLevel | undefined, asOf?: ISODate) {
  return useQuery({
    queryKey: masterDataKeys.adminUnits(parent, level, asOf),
    queryFn: () => masterDataApi.adminUnits({ parent, level, asOf }),
    staleTime: STALE,
  });
}

/** Runs from the first character; the backend caps the result at 100. */
export function useAdminUnitSearch(q: string, asOf?: ISODate) {
  const term = q.trim();
  return useQuery({
    queryKey: masterDataKeys.adminUnitSearch(term, asOf),
    queryFn: () => masterDataApi.searchAdminUnits(term, { asOf, limit: 50 }),
    enabled: term.length > 0,
    staleTime: STALE,
  });
}

/** Old → new resolution of one unit (BR-CORE-081); only runs once a code is picked. */
export function useResolveAdminUnit(code: string | undefined, asOf?: ISODate) {
  return useQuery({
    queryKey: masterDataKeys.resolve(code ?? '', asOf),
    queryFn: () => masterDataApi.resolveAdminUnit(code!, asOf),
    enabled: !!code,
  });
}

export function useCodeLists() {
  return useQuery({ queryKey: masterDataKeys.codeLists, queryFn: masterDataApi.codeLists, staleTime: STALE });
}

export function useCodeValues(listCode: string | undefined, q: string, includeInactive: boolean) {
  return useQuery({
    queryKey: masterDataKeys.codeValues(listCode ?? '', q, includeInactive),
    queryFn: () => masterDataApi.codeValues(listCode!, { q: q || undefined, includeInactive }),
    enabled: !!listCode,
    staleTime: STALE,
  });
}

export function useCountries(q: string) {
  return useQuery({
    queryKey: masterDataKeys.countries(q),
    queryFn: () => masterDataApi.countries(q || undefined),
    staleTime: STALE,
  });
}

export function useCurrencies() {
  return useQuery({ queryKey: masterDataKeys.currencies, queryFn: masterDataApi.currencies, staleTime: STALE });
}

export function useBanks(q: string, includeInactive: boolean) {
  return useQuery({
    queryKey: masterDataKeys.banks(q, includeInactive),
    queryFn: () => masterDataApi.banks(q || undefined, includeInactive),
    staleTime: STALE,
  });
}

export function useBankBranches(bankCode: string | undefined, q: string) {
  return useQuery({
    queryKey: masterDataKeys.branches(bankCode ?? '', q),
    queryFn: () => masterDataApi.branches(bankCode!, q || undefined, true),
    enabled: !!bankCode,
    staleTime: STALE,
  });
}

export function useMasterDataReleases() {
  return useQuery({ queryKey: masterDataKeys.releases, queryFn: masterDataApi.releases, staleTime: STALE });
}
