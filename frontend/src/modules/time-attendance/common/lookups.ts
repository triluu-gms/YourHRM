import { useQuery } from '@tanstack/react-query';
import { http, type ISODate, type UUID } from '@/shared/api';

/**
 * Read-only Core / People lookups used by T&A screens. T&A may not import other modules (eslint boundaries), so the
 * minimal shapes of their endpoints are restated here.
 */

export interface LegalEntityLookup {
  id: UUID;
  code: string;
  status?: string | null;
  current?: { legalNameVi?: string | null; legalNameEn?: string | null; shortName?: string | null } | null;
}

export interface OrgUnitLookup {
  id: UUID;
  code: string;
  nameVi?: string | null;
  nameEn?: string | null;
  legalEntityId?: UUID | null;
  depth?: number | null;
}

export interface LocationLookup {
  id: UUID;
  code: string;
  legalEntityId?: UUID | null;
  current?: { name?: string | null } | null;
}

/** People `EmployeeDirectoryService.EmployeeRow` (GET /people/employees). */
export interface EmployeeLookup {
  employmentId: UUID;
  personId: UUID;
  employeeNo: string;
  fullName: string;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  status: string;
}

export interface EmployeeLookupPage {
  items: EmployeeLookup[];
  total: number;
  page: number;
  size: number;
}

export const lookupApi = {
  legalEntities: () => http.get<LegalEntityLookup[]>('/core/legal-entities'),
  orgUnits: (asOf?: ISODate) => http.get<OrgUnitLookup[]>('/core/org-units', { params: { asOf, includeClosed: true } }),
  locations: (legalEntityId?: UUID) => http.get<LocationLookup[]>('/core/locations', { params: { legalEntityId } }),
  employees: (params: { q?: string; legalEntityId?: UUID; size?: number; page?: number }) =>
    http.get<EmployeeLookupPage>('/people/employees', { params: { ...params } }),
};

const LONG = 10 * 60_000;
const key = (...parts: unknown[]) => ['att', 'lookup', ...parts] as const;

export const useLegalEntities = () => useQuery({ queryKey: key('legal-entities'), queryFn: lookupApi.legalEntities, staleTime: LONG });
export const useOrgUnits = () => useQuery({ queryKey: key('org-units'), queryFn: () => lookupApi.orgUnits(), staleTime: LONG });
export const useLocations = () => useQuery({ queryKey: key('locations'), queryFn: () => lookupApi.locations(), staleTime: LONG });

export function legalEntityLabel(le: LegalEntityLookup | undefined, language: string): string {
  if (!le) return '';
  const c = le.current;
  const name = c?.shortName || (language.startsWith('en') ? c?.legalNameEn || c?.legalNameVi : c?.legalNameVi || c?.legalNameEn);
  return name ? `${le.code} – ${name}` : le.code;
}
