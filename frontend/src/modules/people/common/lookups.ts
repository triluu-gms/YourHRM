import { useQueries, useQuery } from '@tanstack/react-query';
import { http, type ISODate, type UUID } from '@/shared/api';
import type { EmployeePage, EmployeeSearchParams } from './types';

/**
 * Read-only Core / master-data lookups used by People screens (names for ids, pickers). People may not import the
 * Core module (eslint boundaries), so the minimal shapes of Core's endpoints are restated here.
 */

export interface LegalEntityLookup {
  id: UUID;
  code: string;
  status?: string | null;
  current?: { legalNameVi?: string | null; legalNameEn?: string | null; shortName?: string | null; taxCode?: string | null } | null;
}

export interface OrgUnitLookup {
  id: UUID;
  code: string;
  nameVi?: string | null;
  nameEn?: string | null;
  parentId?: UUID | null;
  legalEntityId?: UUID | null;
  locationId?: UUID | null;
  depth?: number | null;
  status?: string | null;
}

export interface LocationLookup {
  id: UUID;
  code: string;
  legalEntityId?: UUID | null;
  status?: string | null;
  current?: { name?: string | null; adminUnitCode?: string | null } | null;
}

export interface JobTitleLookup {
  id: UUID;
  code: string;
  nameVi?: string | null;
  nameEn?: string | null;
  hazardous?: boolean;
  defaultGradeMinId?: UUID | null;
  defaultGradeMaxId?: UUID | null;
  active?: boolean;
}

export interface GradeStructureLookup {
  id: UUID;
  code: string;
  name: string;
}

export interface GradeLookup {
  id: UUID;
  gradeStructureId: UUID;
  code: string;
  name: string;
  rankOrder: number;
  active: boolean;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
}

export interface EmployeeCategoryLookup {
  id: UUID;
  code: string;
  nameVi?: string | null;
  nameEn?: string | null;
  active?: boolean;
}

export interface CostCentreLookup {
  id: UUID;
  legalEntityId?: UUID | null;
  code: string;
  name: string;
}

/** `MasterDataService.CodeValueView`. */
export interface CodeValue {
  valueCode: string;
  labelVi?: string | null;
  labelEn?: string | null;
  sortOrder: number;
  attributes?: Record<string, unknown> | null;
  active: boolean;
}

export interface CountryLookup {
  code: string;
  iso3?: string | null;
  nameVi?: string | null;
  nameEn?: string | null;
  nationalityVi?: string | null;
  nationalityEn?: string | null;
}

export interface BankLookup {
  bankCode: string;
  bin?: string | null;
  shortName?: string | null;
  nameVi?: string | null;
  nameEn?: string | null;
  active: boolean;
}

export interface AdminUnitLookup {
  code: string;
  level: 'PROVINCE' | 'DISTRICT' | 'COMMUNE' | string;
  nameVi?: string | null;
  nameEn?: string | null;
  parentCode?: string | null;
}

export const lookupApi = {
  legalEntities: () => http.get<LegalEntityLookup[]>('/core/legal-entities'),
  orgUnits: (asOf?: ISODate) => http.get<OrgUnitLookup[]>('/core/org-units', { params: { asOf, includeClosed: true } }),
  locations: (legalEntityId?: UUID) => http.get<LocationLookup[]>('/core/locations', { params: { legalEntityId } }),
  jobTitles: () => http.get<JobTitleLookup[]>('/core/job-titles', { params: { includeInactive: true } }),
  gradeStructures: () => http.get<GradeStructureLookup[]>('/core/grade-structures'),
  grades: (structureId: UUID) =>
    http.get<GradeLookup[]>(`/core/grade-structures/${structureId}/grades`, { params: { allDates: true } }),
  employeeCategories: () => http.get<EmployeeCategoryLookup[]>('/core/employee-categories', { params: { includeInactive: true } }),
  costCentres: () => http.get<CostCentreLookup[]>('/core/cost-centres'),
  codeValues: (listCode: string) =>
    http.get<CodeValue[]>(`/core/master-data/code-lists/${listCode}/values`, { params: { includeInactive: true } }),
  countries: () => http.get<CountryLookup[]>('/core/master-data/countries'),
  banks: () => http.get<BankLookup[]>('/core/master-data/banks'),
  provinces: () => http.get<AdminUnitLookup[]>('/core/master-data/admin-units'),
  adminUnitChildren: (parent: string) => http.get<AdminUnitLookup[]>('/core/master-data/admin-units', { params: { parent } }),
  adminUnit: (code: string) => http.get<AdminUnitLookup>(`/core/master-data/admin-units/${code}`),
  employees: (params: EmployeeSearchParams) => http.get<EmployeePage>('/people/employees', { params: { ...params } }),
};

const LONG = 10 * 60_000;
const key = (...parts: unknown[]) => ['people', 'lookup', ...parts] as const;

export const useLegalEntities = () => useQuery({ queryKey: key('legal-entities'), queryFn: lookupApi.legalEntities, staleTime: LONG });
export const useOrgUnits = () => useQuery({ queryKey: key('org-units'), queryFn: () => lookupApi.orgUnits(), staleTime: LONG });
export const useLocations = () => useQuery({ queryKey: key('locations'), queryFn: () => lookupApi.locations(), staleTime: LONG });
export const useJobTitles = () => useQuery({ queryKey: key('job-titles'), queryFn: lookupApi.jobTitles, staleTime: LONG });
export const useEmployeeCategories = () =>
  useQuery({ queryKey: key('employee-categories'), queryFn: lookupApi.employeeCategories, staleTime: LONG });
export const useCostCentres = () => useQuery({ queryKey: key('cost-centres'), queryFn: lookupApi.costCentres, staleTime: LONG });
export const useCountries = () => useQuery({ queryKey: key('countries'), queryFn: lookupApi.countries, staleTime: LONG });
export const useBanks = () => useQuery({ queryKey: key('banks'), queryFn: lookupApi.banks, staleTime: LONG });
export const useProvinces = () => useQuery({ queryKey: key('provinces'), queryFn: lookupApi.provinces, staleTime: LONG });
export const useAdminUnitChildren = (parent: string | null | undefined) =>
  useQuery({
    queryKey: key('admin-units', parent ?? ''),
    queryFn: () => lookupApi.adminUnitChildren(parent as string),
    enabled: !!parent,
    staleTime: LONG,
  });

/** Values of one Core code list (inactive included so stored codes still resolve to a label). */
export const useCodeValues = (listCode: string | null | undefined) =>
  useQuery({
    queryKey: key('code-list', listCode ?? ''),
    queryFn: () => lookupApi.codeValues(listCode as string),
    enabled: !!listCode,
    staleTime: LONG,
  });

/** Every grade of every structure (grades are looked up by id in People screens). */
export function useAllGrades() {
  const structures = useQuery({ queryKey: key('grade-structures'), queryFn: lookupApi.gradeStructures, staleTime: LONG });
  const grades = useQueries({
    queries: (structures.data ?? []).map((s) => ({
      queryKey: key('grades', s.id),
      queryFn: () => lookupApi.grades(s.id),
      staleTime: LONG,
    })),
  });
  return {
    structures: structures.data ?? [],
    grades: grades.flatMap((g) => g.data ?? []),
    isLoading: structures.isLoading || grades.some((g) => g.isLoading),
  };
}

/** Label of a code value in the UI language (falls back to the other language, then the code). */
export function codeLabel(value: CodeValue | undefined, code: string, language: string): string {
  if (!value) return code;
  const primary = language.startsWith('en') ? value.labelEn : value.labelVi;
  return primary || value.labelVi || value.labelEn || code;
}

export function legalEntityLabel(le: LegalEntityLookup | undefined, language: string): string {
  if (!le) return '';
  const c = le.current;
  const name = c?.shortName || (language.startsWith('en') ? c?.legalNameEn || c?.legalNameVi : c?.legalNameVi || c?.legalNameEn);
  return name ? `${le.code} – ${name}` : le.code;
}
