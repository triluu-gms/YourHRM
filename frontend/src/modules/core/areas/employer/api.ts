import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

/*
 * Types mirror the Core backend records 1:1 (CompanyGroupController, LegalEntityController, LocationController,
 * OrgUnitTypeController, OrgUnitController and the *Service records they expose).
 *
 * Not covered here: `PUT /core/legal-entities/{id}/letterhead` (DocumentTemplateController) — the documents area
 * owns letterheads; this area only shows the legal entity.
 */

// ------------------------------------------------------------------------------------------------ company group

/** `CompanyGroupService.CompanyGroupView`. */
export interface CompanyGroup {
  id: UUID;
  name: string;
  logoDocumentId?: UUID | null;
  defaultLocale: string;
  timeZone: string;
  currencyCode: string;
  updatedAt?: ISOInstant | null;
  updatedBy?: UUID | null;
  version: number;
}

/** `CompanyGroupController.UpdateCompanyGroupRequest`. */
export interface UpdateCompanyGroupRequest {
  name: string;
  logoDocumentId?: UUID | null;
  defaultLocale: string;
  timeZone: string;
  currencyCode: string;
  version: number;
}

// ------------------------------------------------------------------------------------------------ legal entities

export type LegalEntityStatus = 'DRAFT' | 'ACTIVE' | 'DISSOLVED';
export const LEGAL_ENTITY_STATUSES: LegalEntityStatus[] = ['DRAFT', 'ACTIVE', 'DISSOLVED'];
export type PitDeclarationFrequency = 'MONTHLY' | 'QUARTERLY';
export const PIT_DECLARATION_FREQUENCIES: PitDeclarationFrequency[] = ['MONTHLY', 'QUARTERLY'];

/** `LegalEntityController.AttributesRequest` (= `LegalEntityService.Attributes`). */
export interface LegalEntityAttributes {
  legalNameVi: string;
  legalNameEn?: string | null;
  shortName?: string | null;
  taxCode: string;
  siUnitCode?: string | null;
  businessRegNo?: string | null;
  businessRegDate?: ISODate | null;
  businessRegIssuer?: string | null;
  phone?: string | null;
  email?: string | null;
  adminUnitCode?: string | null;
  addressLine?: string | null;
  legalRepName?: string | null;
  legalRepTitle?: string | null;
  fiscalYearStartMonth?: number | null;
  taxAuthorityCode?: string | null;
  pitDeclarationFrequency?: PitDeclarationFrequency | null;
  oaOdRate?: number | null;
  oaOdDecisionRef?: string | null;
  oaOdDecisionDocId?: UUID | null;
}

/** `LegalEntityService.VersionView`. */
export interface LegalEntityVersion extends LegalEntityAttributes {
  versionId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
  fiscalYearStartMonth: number;
}

/** `LegalEntityService.LegalEntityView`: anchor + the version in effect on the requested date. */
export interface LegalEntity {
  id: UUID;
  code: string;
  status: LegalEntityStatus;
  version: number;
  current?: LegalEntityVersion | null;
}

/** `LegalEntityService.Usage`: entities counted against the plan (`limit` null = unlimited). */
export interface LegalEntityUsage {
  used: number;
  limit?: number | null;
}

export interface CreateLegalEntityRequest {
  code: string;
  status?: LegalEntityStatus | null;
  validFrom?: ISODate | null;
  attributes: LegalEntityAttributes;
}

export interface ChangeLegalEntityRequest {
  effectiveFrom: ISODate;
  attributes: LegalEntityAttributes;
  /** The entity's `version` as read (optimistic lock over its whole history). */
  version: number;
}

export interface ChangeLegalEntityStatusRequest {
  status: LegalEntityStatus;
  version: number;
}

// ------------------------------------------------------------------------------------------------ min-wage region

export type MinWageRegionCode = 'I' | 'II' | 'III' | 'IV';

/** `MinWageRegions.Resolution` (BR-CORE-006); `region` null when it cannot be determined. */
export interface MinWageRegion {
  legalEntityId?: UUID | null;
  locationId?: UUID | null;
  asOf: ISODate;
  adminUnitCode?: string | null;
  region?: MinWageRegionCode | null;
  packVersion?: number | null;
  overridden: boolean;
}

// ------------------------------------------------------------------------------------------------ locations

export type LocationType =
  | 'HEAD_OFFICE'
  | 'BRANCH'
  | 'REPRESENTATIVE_OFFICE'
  | 'FACTORY'
  | 'STORE'
  | 'CONSTRUCTION_SITE'
  | 'REMOTE';
export const LOCATION_TYPES: LocationType[] = [
  'HEAD_OFFICE',
  'BRANCH',
  'REPRESENTATIVE_OFFICE',
  'FACTORY',
  'STORE',
  'CONSTRUCTION_SITE',
  'REMOTE',
];
export type LocationStatus = 'ACTIVE' | 'CLOSED';
export const LOCATION_STATUSES: LocationStatus[] = ['ACTIVE', 'CLOSED'];

/** `LocationController.AttributesRequest` (= `LocationService.Attributes`). */
export interface LocationAttributes {
  name: string;
  locationType: LocationType;
  adminUnitCode?: string | null;
  addressLine?: string | null;
  remoteArea?: boolean | null;
  timeZone?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  geofenceRadiusM?: number | null;
  dependentTaxCode?: string | null;
}

/** `LocationService.VersionView`. */
export interface LocationVersion extends LocationAttributes {
  versionId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
  remoteArea: boolean;
}

/** `LocationService.LocationView`: `status` is as of the requested date (CLOSED from `closedFrom` on). */
export interface Location {
  id: UUID;
  code: string;
  legalEntityId: UUID;
  status: LocationStatus;
  closedFrom?: ISODate | null;
  version: number;
  current?: LocationVersion | null;
}

export interface CreateLocationRequest {
  code: string;
  legalEntityId: UUID;
  validFrom?: ISODate | null;
  attributes: LocationAttributes;
}

export interface ChangeLocationRequest {
  effectiveFrom: ISODate;
  attributes: LocationAttributes;
  version: number;
}

/** Closed from `effectiveFrom` (the history ends the day before), like org units. */
export interface CloseLocationRequest {
  effectiveFrom: ISODate;
  version: number;
}

// ------------------------------------------------------------------------------------------------ org unit types

/** `OrgUnitTypeService.OrgUnitTypeView`. */
export interface OrgUnitType {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn: string;
  levelHint?: number | null;
  sortOrder: number;
  active: boolean;
  version: number;
}

export interface CreateOrgUnitTypeRequest {
  code: string;
  nameVi: string;
  nameEn: string;
  levelHint?: number | null;
  sortOrder?: number | null;
}

export interface UpdateOrgUnitTypeRequest {
  nameVi: string;
  nameEn: string;
  levelHint?: number | null;
  sortOrder: number;
  active: boolean;
  version: number;
}

// ------------------------------------------------------------------------------------------------ org units

export type OrgUnitStatus = 'ACTIVE' | 'CLOSED';

/** `OrgUnitController.AttributesRequest` (= `OrgUnitService.Attributes`). */
export interface OrgUnitAttributes {
  nameVi: string;
  nameEn?: string | null;
  typeId: UUID;
  parentId?: UUID | null;
  legalEntityId: UUID;
  locationId?: UUID | null;
  costCentreId?: UUID | null;
  managerUserId?: UUID | null;
  sortOrder?: number | null;
}

/**
 * `OrgUnitService.OrgUnitView`: one version of a unit (the tree endpoint returns the versions in effect) with the
 * display names of what it references. `version` is the unit's optimistic-lock token (whole history).
 */
export interface OrgUnit {
  id: UUID;
  code: string;
  versionId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
  nameVi: string;
  nameEn?: string | null;
  typeId: UUID;
  typeCode?: string | null;
  typeNameVi?: string | null;
  typeNameEn?: string | null;
  parentId?: UUID | null;
  legalEntityId: UUID;
  legalEntityCode?: string | null;
  legalEntityName?: string | null;
  locationId?: UUID | null;
  locationCode?: string | null;
  locationName?: string | null;
  costCentreId?: UUID | null;
  costCentreCode?: string | null;
  costCentreName?: string | null;
  managerUserId?: UUID | null;
  managerName?: string | null;
  sortOrder: number;
  status: OrgUnitStatus | string;
  depth: number;
  version: number;
}

export interface CreateOrgUnitRequest {
  code: string;
  validFrom?: ISODate | null;
  attributes: OrgUnitAttributes;
}

export interface ChangeOrgUnitRequest {
  effectiveFrom: ISODate;
  attributes: OrgUnitAttributes;
  version: number;
}

export interface CloseOrgUnitRequest {
  effectiveFrom: ISODate;
  version: number;
}

export interface OrgTreeParams {
  asOf?: ISODate;
  root?: UUID;
  includeClosed?: boolean;
  q?: string;
}

// ------------------------------------------------------------------------------------------------ admin units

/** Local copy of `MasterData.AdminUnit` (owned by the master-data area) for the address pickers. */
export interface AdminUnit {
  code: string;
  level: 'PROVINCE' | 'DISTRICT' | 'COMMUNE' | string;
  nameVi: string;
  nameEn?: string | null;
  parentCode?: string | null;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
}

// ------------------------------------------------------------------------------------------------ lookups

/** `OrgUnitService.LookupItem`: picker entry of the org form (manager: code = login; cost centre: its code). */
export interface LookupItem {
  id: UUID;
  code: string;
  name: string;
}

// ------------------------------------------------------------------------------------------------ clients

export const companyGroupApi = {
  get: () => http.get<CompanyGroup>('/core/company-group'),
  update: (body: UpdateCompanyGroupRequest) => http.put<CompanyGroup>('/core/company-group', body),
};

export const legalEntitiesApi = {
  list: (params: { asOf?: ISODate; status?: LegalEntityStatus }) =>
    http.get<LegalEntity[]>('/core/legal-entities', { params }),
  usage: () => http.get<LegalEntityUsage>('/core/legal-entities/usage'),
  get: (id: UUID, asOf?: ISODate) => http.get<LegalEntity>(`/core/legal-entities/${id}`, { params: { asOf } }),
  history: (id: UUID) => http.get<LegalEntityVersion[]>(`/core/legal-entities/${id}/versions`),
  minWageRegion: (id: UUID, asOf?: ISODate) =>
    http.get<MinWageRegion>(`/core/legal-entities/${id}/min-wage-region`, { params: { asOf } }),
  create: (body: CreateLegalEntityRequest) => http.post<LegalEntity>('/core/legal-entities', body),
  changeFrom: (id: UUID, body: ChangeLegalEntityRequest) =>
    http.post<LegalEntity>(`/core/legal-entities/${id}/versions`, body),
  changeStatus: (id: UUID, body: ChangeLegalEntityStatusRequest) =>
    http.put<LegalEntity>(`/core/legal-entities/${id}/status`, body),
};

export const locationsApi = {
  list: (params: { asOf?: ISODate; legalEntityId?: UUID }) => http.get<Location[]>('/core/locations', { params }),
  get: (id: UUID, asOf?: ISODate) => http.get<Location>(`/core/locations/${id}`, { params: { asOf } }),
  history: (id: UUID) => http.get<LocationVersion[]>(`/core/locations/${id}/versions`),
  minWageRegion: (id: UUID, asOf?: ISODate) =>
    http.get<MinWageRegion>(`/core/locations/${id}/min-wage-region`, { params: { asOf } }),
  create: (body: CreateLocationRequest) => http.post<Location>('/core/locations', body),
  changeFrom: (id: UUID, body: ChangeLocationRequest) => http.post<Location>(`/core/locations/${id}/versions`, body),
  close: (id: UUID, body: CloseLocationRequest) => http.post<Location>(`/core/locations/${id}/close`, body),
  removePlannedVersion: (id: UUID, versionId: UUID, version: number) =>
    http.delete<Location>(`/core/locations/${id}/versions/${versionId}`, { params: { version } }),
};

export const orgUnitTypesApi = {
  list: (includeInactive = false) => http.get<OrgUnitType[]>('/core/org-unit-types', { params: { includeInactive } }),
  create: (body: CreateOrgUnitTypeRequest) => http.post<OrgUnitType>('/core/org-unit-types', body),
  update: (id: UUID, body: UpdateOrgUnitTypeRequest) => http.put<OrgUnitType>(`/core/org-unit-types/${id}`, body),
};

export const orgUnitsApi = {
  /** Flat list in path order (parents before children); the client builds the tree from `parentId`. */
  tree: (params: OrgTreeParams) => http.get<OrgUnit[]>('/core/org-units', { params: { ...params } }),
  get: (id: UUID, asOf?: ISODate) => http.get<OrgUnit>(`/core/org-units/${id}`, { params: { asOf } }),
  history: (id: UUID) => http.get<OrgUnit[]>(`/core/org-units/${id}/versions`),
  ancestors: (id: UUID, asOf?: ISODate) => http.get<OrgUnit[]>(`/core/org-units/${id}/ancestors`, { params: { asOf } }),
  descendants: (id: UUID, asOf?: ISODate) =>
    http.get<OrgUnit[]>(`/core/org-units/${id}/descendants`, { params: { asOf } }),
  create: (body: CreateOrgUnitRequest) => http.post<OrgUnit>('/core/org-units', body),
  changeFrom: (id: UUID, body: ChangeOrgUnitRequest) => http.post<OrgUnit>(`/core/org-units/${id}/versions`, body),
  close: (id: UUID, body: CloseOrgUnitRequest) => http.post<OrgUnit>(`/core/org-units/${id}/close`, body),
  removePlannedVersion: (id: UUID, versionId: UUID, version: number) =>
    http.delete<OrgUnit>(`/core/org-units/${id}/versions/${versionId}`, { params: { version } }),
  /** Org-form pickers (need org-unit write access, not `core.user.view` / `core.cost_centre.view`). */
  managerLookup: (q: string) => http.get<LookupItem[]>('/core/org-units/lookups/managers', { params: { q } }),
  costCentreLookup: (legalEntityId: UUID, asOf?: ISODate) =>
    http.get<LookupItem[]>('/core/org-units/lookups/cost-centres', { params: { legalEntityId, asOf } }),
};

export const adminUnitsApi = {
  search: (q: string, asOf?: ISODate, level?: string) =>
    http.get<AdminUnit[]>('/core/master-data/admin-units/search', { params: { q, asOf, level, limit: 20 } }),
  get: (code: string) => http.get<AdminUnit>(`/core/master-data/admin-units/${encodeURIComponent(code)}`),
};

