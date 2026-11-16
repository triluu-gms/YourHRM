import { http, type ISODate, type ISOInstant } from '@/shared/api';

/*
 * Global master data (BR-CORE-080..082). Types mirror `MasterData` and the views of `MasterDataService` 1:1.
 * Every endpoint is read-only for tenants: releases are loaded by the SaaS back-office, so this area browses and
 * never writes. The employer area keeps a local copy of `AdminUnit` for its address pickers; keep them in step.
 */

export type AdminUnitLevel = 'PROVINCE' | 'DISTRICT' | 'COMMUNE' | string;

/** `MasterData.AdminUnit`; `DISTRICT` exists in pre-2025 data only (34-province reform, BR-CORE-080). */
export interface AdminUnit {
  code: string;
  level: AdminUnitLevel;
  nameVi: string;
  nameEn?: string | null;
  parentCode?: string | null;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
}

/** `MasterData.Resolution` (BR-CORE-081): CURRENT, MAPPED, AMBIGUOUS (a split) or UNKNOWN. */
export type ResolutionStatus = 'CURRENT' | 'MAPPED' | 'AMBIGUOUS' | 'UNKNOWN' | string;

export interface Resolution {
  inputCode: string;
  status: ResolutionStatus;
  candidates: AdminUnit[];
  note?: string | null;
}

/** `MasterDataService.FormattedAddress`: the unit with its ancestors, written in the OLD or NEW form. */
export interface FormattedAddress {
  code: string;
  form: 'OLD' | 'NEW' | string;
  text: string;
  path: AdminUnit[];
  resolution?: Resolution | null;
}

/** `MasterDataService.CodeListView`. */
export interface CodeList {
  listCode: string;
  nameVi: string;
  nameEn?: string | null;
  tenantExtensible: boolean;
  statutory: boolean;
  ownerModule?: string | null;
}

/** `MasterDataService.CodeValueView`; `tenantValue` marks a value this tenant added or overrode. */
export interface CodeValue {
  valueCode: string;
  labelVi?: string | null;
  labelEn?: string | null;
  sortOrder: number;
  attributes: Record<string, unknown>;
  active: boolean;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
  tenantValue: boolean;
}

/** `MasterDataService.CountryView`. */
export interface Country {
  code: string;
  iso3?: string | null;
  nameVi: string;
  nameEn?: string | null;
  nationalityVi?: string | null;
  nationalityEn?: string | null;
}

/** `MasterDataService.CurrencyView`. */
export interface Currency {
  code: string;
  nameVi: string;
  nameEn?: string | null;
  minorUnits: number;
}

/** `MasterDataService.BankView`; `bin` is the 6-digit Napas id used on bank files. */
export interface Bank {
  bankCode: string;
  bin?: string | null;
  swiftCode?: string | null;
  shortName?: string | null;
  nameVi: string;
  nameEn?: string | null;
  active: boolean;
}

/** `MasterDataService.BankBranchView`. */
export interface BankBranch {
  bankCode: string;
  branchCode: string;
  nameVi: string;
  provinceCode?: string | null;
  active: boolean;
}

/** `MasterDataService.ReleaseView`: what the back-office has loaded for this installation. */
export interface MasterDataRelease {
  releaseNo: number;
  title?: string | null;
  datasets: string[];
  itemCounts: Record<string, unknown>;
  effectiveDate?: ISODate | null;
  publishedAt?: ISOInstant | null;
  loadedAt?: ISOInstant | null;
}

const MD = '/core/master-data';

export const masterDataApi = {
  /** Provinces when `parent` is omitted, otherwise the children of that unit. */
  adminUnits: (params: { parent?: string; level?: AdminUnitLevel; asOf?: ISODate }) =>
    http.get<AdminUnit[]>(`${MD}/admin-units`, { params: { ...params } }),
  searchAdminUnits: (q: string, params: { level?: AdminUnitLevel; asOf?: ISODate; limit?: number } = {}) =>
    http.get<AdminUnit[]>(`${MD}/admin-units/search`, { params: { q, ...params } }),
  adminUnit: (code: string) => http.get<AdminUnit>(`${MD}/admin-units/${encodeURIComponent(code)}`),
  resolveAdminUnit: (code: string, asOf?: ISODate) =>
    http.get<Resolution>(`${MD}/admin-units/${encodeURIComponent(code)}/resolve`, { params: { asOf } }),
  address: (code: string, form: 'OLD' | 'NEW', asOf?: ISODate) =>
    http.get<FormattedAddress>(`${MD}/admin-units/${encodeURIComponent(code)}/address`, { params: { form, asOf } }),

  codeLists: () => http.get<CodeList[]>(`${MD}/code-lists`),
  codeValues: (listCode: string, params: { asOf?: ISODate; q?: string; includeInactive?: boolean } = {}) =>
    http.get<CodeValue[]>(`${MD}/code-lists/${encodeURIComponent(listCode)}/values`, { params: { ...params } }),

  countries: (q?: string) => http.get<Country[]>(`${MD}/countries`, { params: { q } }),
  currencies: () => http.get<Currency[]>(`${MD}/currencies`),
  banks: (q?: string, includeInactive = false) => http.get<Bank[]>(`${MD}/banks`, { params: { q, includeInactive } }),
  branches: (bankCode: string, q?: string, includeInactive = false) =>
    http.get<BankBranch[]>(`${MD}/banks/${encodeURIComponent(bankCode)}/branches`, { params: { q, includeInactive } }),
  releases: () => http.get<MasterDataRelease[]>(`${MD}/releases`),
};
