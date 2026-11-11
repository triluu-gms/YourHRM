import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import {
  adminUnitsApi,
  companyGroupApi,
  legalEntitiesApi,
  locationsApi,
  orgUnitTypesApi,
  orgUnitsApi,
  type ChangeLegalEntityRequest,
  type ChangeLegalEntityStatusRequest,
  type ChangeLocationRequest,
  type CloseLocationRequest,
  type ChangeOrgUnitRequest,
  type CloseOrgUnitRequest,
  type CreateLegalEntityRequest,
  type CreateLocationRequest,
  type CreateOrgUnitRequest,
  type CreateOrgUnitTypeRequest,
  type LegalEntityStatus,
  type OrgTreeParams,
  type UpdateCompanyGroupRequest,
  type UpdateOrgUnitTypeRequest,
} from './api';

export const employerKeys = {
  all: ['core', 'employer'] as const,
  companyGroup: ['core', 'employer', 'company-group'] as const,
  legalEntities: ['core', 'employer', 'legal-entities'] as const,
  legalEntityList: (asOf?: ISODate, status?: LegalEntityStatus) =>
    ['core', 'employer', 'legal-entities', 'list', { asOf, status }] as const,
  legalEntityUsage: ['core', 'employer', 'legal-entities', 'usage'] as const,
  legalEntity: (id: UUID, asOf?: ISODate) => ['core', 'employer', 'legal-entities', id, { asOf }] as const,
  legalEntityHistory: (id: UUID) => ['core', 'employer', 'legal-entities', id, 'versions'] as const,
  legalEntityMinWage: (id: UUID, asOf?: ISODate) =>
    ['core', 'employer', 'legal-entities', id, 'min-wage-region', { asOf }] as const,
  locations: ['core', 'employer', 'locations'] as const,
  locationList: (asOf?: ISODate, legalEntityId?: UUID) =>
    ['core', 'employer', 'locations', 'list', { asOf, legalEntityId }] as const,
  location: (id: UUID, asOf?: ISODate) => ['core', 'employer', 'locations', id, { asOf }] as const,
  locationHistory: (id: UUID) => ['core', 'employer', 'locations', id, 'versions'] as const,
  locationMinWage: (id: UUID, asOf?: ISODate) =>
    ['core', 'employer', 'locations', id, 'min-wage-region', { asOf }] as const,
  orgUnitTypes: ['core', 'employer', 'org-unit-types'] as const,
  orgUnitTypeList: (includeInactive: boolean) =>
    ['core', 'employer', 'org-unit-types', 'list', { includeInactive }] as const,
  orgUnits: ['core', 'employer', 'org-units'] as const,
  orgTree: (params: OrgTreeParams) => ['core', 'employer', 'org-units', 'tree', params] as const,
  orgUnit: (id: UUID, asOf?: ISODate) => ['core', 'employer', 'org-units', id, { asOf }] as const,
  orgUnitHistory: (id: UUID) => ['core', 'employer', 'org-units', id, 'versions'] as const,
  orgUnitAncestors: (id: UUID, asOf?: ISODate) => ['core', 'employer', 'org-units', id, 'ancestors', { asOf }] as const,
  orgUnitDescendants: (id: UUID, asOf?: ISODate) =>
    ['core', 'employer', 'org-units', id, 'descendants', { asOf }] as const,
  costCentreLookup: (legalEntityId: UUID, asOf?: ISODate) =>
    ['core', 'employer', 'lookups', 'cost-centres', { legalEntityId, asOf }] as const,
  managerLookup: (q: string) => ['core', 'employer', 'lookups', 'managers', q] as const,
  adminUnitSearch: (q: string, asOf?: ISODate, level?: string) =>
    ['core', 'master-data', 'admin-units', 'search', { q, asOf, level }] as const,
  adminUnit: (code: string) => ['core', 'master-data', 'admin-units', code] as const,
};

// ------------------------------------------------------------------------------------------------ company group

export const companyGroupOptions = () =>
  queryOptions({ queryKey: employerKeys.companyGroup, queryFn: companyGroupApi.get });

export function useCompanyGroup() {
  return useQuery(companyGroupOptions());
}

export function useUpdateCompanyGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateCompanyGroupRequest) => companyGroupApi.update(body),
    onSuccess: (data) => qc.setQueryData(employerKeys.companyGroup, data),
    onError: () => void qc.invalidateQueries({ queryKey: employerKeys.companyGroup }),
  });
}

// ------------------------------------------------------------------------------------------------ legal entities

export function useLegalEntities(asOf?: ISODate, status?: LegalEntityStatus, enabled = true) {
  return useQuery({
    queryKey: employerKeys.legalEntityList(asOf, status),
    queryFn: () => legalEntitiesApi.list({ asOf, status }),
    enabled,
  });
}

export function useLegalEntityUsage(enabled: boolean) {
  return useQuery({ queryKey: employerKeys.legalEntityUsage, queryFn: legalEntitiesApi.usage, enabled });
}

export function useLegalEntity(id: UUID, asOf?: ISODate) {
  return useQuery({ queryKey: employerKeys.legalEntity(id, asOf), queryFn: () => legalEntitiesApi.get(id, asOf) });
}

export function useLegalEntityHistory(id: UUID, enabled: boolean) {
  return useQuery({ queryKey: employerKeys.legalEntityHistory(id), queryFn: () => legalEntitiesApi.history(id), enabled });
}

export function useLegalEntityMinWageRegion(id: UUID, asOf?: ISODate) {
  return useQuery({
    queryKey: employerKeys.legalEntityMinWage(id, asOf),
    queryFn: () => legalEntitiesApi.minWageRegion(id, asOf),
    retry: false,
  });
}

export function useCreateLegalEntity() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateLegalEntityRequest) => legalEntitiesApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employerKeys.legalEntities }),
  });
}

export function useChangeLegalEntity(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ChangeLegalEntityRequest) => legalEntitiesApi.changeFrom(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employerKeys.legalEntities }),
  });
}

export function useChangeLegalEntityStatus(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ChangeLegalEntityStatusRequest) => legalEntitiesApi.changeStatus(id, body),
    onSettled: () => void qc.invalidateQueries({ queryKey: employerKeys.legalEntities }),
  });
}

// ------------------------------------------------------------------------------------------------ locations

export function useLocations(asOf?: ISODate, legalEntityId?: UUID, enabled = true) {
  return useQuery({
    queryKey: employerKeys.locationList(asOf, legalEntityId),
    queryFn: () => locationsApi.list({ asOf, legalEntityId }),
    enabled,
  });
}

export function useLocation(id: UUID, asOf?: ISODate) {
  return useQuery({ queryKey: employerKeys.location(id, asOf), queryFn: () => locationsApi.get(id, asOf) });
}

export function useLocationHistory(id: UUID, enabled: boolean) {
  return useQuery({ queryKey: employerKeys.locationHistory(id), queryFn: () => locationsApi.history(id), enabled });
}

export function useLocationMinWageRegion(id: UUID, asOf?: ISODate) {
  return useQuery({ queryKey: employerKeys.locationMinWage(id, asOf), queryFn: () => locationsApi.minWageRegion(id, asOf), retry: false });
}

export function useCreateLocation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateLocationRequest) => locationsApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employerKeys.locations }),
  });
}

export function useChangeLocation(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ChangeLocationRequest) => locationsApi.changeFrom(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employerKeys.locations }),
  });
}

export function useCloseLocation(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CloseLocationRequest) => locationsApi.close(id, body),
    onSettled: () => void qc.invalidateQueries({ queryKey: employerKeys.locations }),
  });
}

/** Removes a planned (not yet effective) version, e.g. one blocking a close (`future-versions-exist`). */
export function useRemoveLocationVersion(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ versionId, version }: { versionId: UUID; version: number }) =>
      locationsApi.removePlannedVersion(id, versionId, version),
    onSettled: () => void qc.invalidateQueries({ queryKey: employerKeys.locations }),
  });
}

// ------------------------------------------------------------------------------------------------ org unit types

export function useOrgUnitTypes(includeInactive = false) {
  return useQuery({
    queryKey: employerKeys.orgUnitTypeList(includeInactive),
    queryFn: () => orgUnitTypesApi.list(includeInactive),
    staleTime: 5 * 60_000,
  });
}

export function useCreateOrgUnitType() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateOrgUnitTypeRequest) => orgUnitTypesApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employerKeys.orgUnitTypes }),
  });
}

export function useUpdateOrgUnitType() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: UpdateOrgUnitTypeRequest }) => orgUnitTypesApi.update(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employerKeys.orgUnitTypes }),
  });
}

// ------------------------------------------------------------------------------------------------ org units

export function useOrgTree(params: OrgTreeParams) {
  return useQuery({ queryKey: employerKeys.orgTree(params), queryFn: () => orgUnitsApi.tree(params) });
}

export function useOrgUnit(id: UUID | undefined, asOf?: ISODate) {
  return useQuery({
    queryKey: employerKeys.orgUnit(id ?? '', asOf),
    queryFn: () => orgUnitsApi.get(id as UUID, asOf),
    enabled: !!id,
  });
}

export function useOrgUnitHistory(id: UUID | undefined, enabled: boolean) {
  return useQuery({
    queryKey: employerKeys.orgUnitHistory(id ?? ''),
    queryFn: () => orgUnitsApi.history(id as UUID),
    enabled: enabled && !!id,
  });
}

export function useOrgUnitAncestors(id: UUID | undefined, asOf?: ISODate) {
  return useQuery({
    queryKey: employerKeys.orgUnitAncestors(id ?? '', asOf),
    queryFn: () => orgUnitsApi.ancestors(id as UUID, asOf),
    enabled: !!id,
  });
}

export function useCreateOrgUnit() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateOrgUnitRequest) => orgUnitsApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employerKeys.orgUnits }),
  });
}

export function useChangeOrgUnit(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ChangeOrgUnitRequest) => orgUnitsApi.changeFrom(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employerKeys.orgUnits }),
  });
}

export function useCloseOrgUnit(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CloseOrgUnitRequest) => orgUnitsApi.close(id, body),
    onSettled: () => void qc.invalidateQueries({ queryKey: employerKeys.orgUnits }),
  });
}

/** Removes a planned (not yet effective) version, e.g. one blocking a close (`future-versions-exist`). */
export function useRemoveOrgUnitVersion(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ versionId, version }: { versionId: UUID; version: number }) =>
      orgUnitsApi.removePlannedVersion(id, versionId, version),
    onSettled: () => void qc.invalidateQueries({ queryKey: employerKeys.orgUnits }),
  });
}

// ------------------------------------------------------------------------------------------------ admin units

export function useAdminUnitSearch(q: string, asOf?: ISODate, level?: string) {
  const term = q.trim();
  return useQuery({
    queryKey: employerKeys.adminUnitSearch(term, asOf, level),
    queryFn: () => adminUnitsApi.search(term, asOf, level),
    enabled: term.length >= 2,
    staleTime: 5 * 60_000,
  });
}

export function useAdminUnit(code: string | null | undefined) {
  return useQuery({
    queryKey: employerKeys.adminUnit(code ?? ''),
    queryFn: () => adminUnitsApi.get(code as string),
    enabled: !!code,
    staleTime: 30 * 60_000,
  });
}

export function useOrgUnitDescendants(id: UUID, asOf?: ISODate) {
  return useQuery({
    queryKey: employerKeys.orgUnitDescendants(id, asOf),
    queryFn: () => orgUnitsApi.descendants(id, asOf),
  });
}

// ------------------------------------------------------------------------------------------------ org-form pickers

export function useCostCentreLookup(legalEntityId: UUID | undefined, asOf: ISODate | undefined) {
  return useQuery({
    queryKey: employerKeys.costCentreLookup(legalEntityId ?? '', asOf),
    queryFn: () => orgUnitsApi.costCentreLookup(legalEntityId as UUID, asOf),
    enabled: !!legalEntityId,
    staleTime: 60_000,
  });
}

export function useManagerLookup(q: string) {
  const term = q.trim();
  return useQuery({
    queryKey: employerKeys.managerLookup(term),
    queryFn: () => orgUnitsApi.managerLookup(term),
    enabled: term.length >= 2,
    staleTime: 60_000,
  });
}
