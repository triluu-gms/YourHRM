import { http, type ISODate, type UUID } from '@/shared/api';

/*
 * Types mirror the Core backend records 1:1: `SettingsController` / `SettingsService` (BR-CORE-084, US-CORE-085)
 * and `SequenceController` / `SequenceService` (BR-CORE-085, US-CORE-086).
 */

// ------------------------------------------------------------------------------------------------ settings

export type SettingValueType = 'BOOL' | 'INT' | 'DECIMAL' | 'TEXT' | 'ENUM' | 'DATE' | 'JSON';
export type SettingLevel = 'TENANT' | 'LEGAL_ENTITY' | 'LOCATION';
export const SETTING_LEVELS: SettingLevel[] = ['TENANT', 'LEGAL_ENTITY', 'LOCATION'];
/** Where a resolved value comes from: one of the levels, or the definition's default. */
export type SettingSource = SettingLevel | 'DEFAULT';

/** Any JSON value; its shape depends on the setting's `valueType`. Sensitive values arrive as `"***"`. */
export type SettingJson = boolean | number | string | null | SettingJson[] | { [key: string]: SettingJson };

/** `SettingsService.ValueView`: one stored value; `validTo` is exclusive. Non-dated values start on 1900-01-01. */
export interface SettingValue {
  id: UUID;
  level: SettingLevel;
  legalEntityId?: UUID | null;
  locationId?: UUID | null;
  value: SettingJson;
  validFrom: ISODate;
  validTo?: ISODate | null;
  version: number;
}

/** `SettingsService.SettingView`: the definition, the value that applies to the tenant today and every stored value. */
export interface Setting {
  key: string;
  ownerModule: string;
  valueType: SettingValueType;
  allowedValues: string[];
  defaultValue: SettingJson;
  allowedLevels: SettingLevel[];
  effectiveDated: boolean;
  sensitive: boolean;
  descriptionVi: string;
  descriptionEn: string;
  tenantValue: SettingJson;
  values: SettingValue[];
}

/** `SettingsService.Resolved`. */
export interface ResolvedSetting {
  key: string;
  value: SettingJson;
  source: SettingSource;
  valueId?: UUID | null;
  validFrom?: ISODate | null;
}

/** `SettingsController.SetValueRequest`; `validFrom` is ignored for non-dated settings. */
export interface SetSettingRequest {
  level: SettingLevel;
  legalEntityId?: UUID | null;
  locationId?: UUID | null;
  value: SettingJson;
  validFrom?: ISODate | null;
}

export interface ResolveParams {
  legalEntityId?: UUID;
  locationId?: UUID;
  asOf?: ISODate;
}

/** The masked value the backend returns for a sensitive setting. */
export const MASKED = '***';

/** Non-dated values carry this start date (`SettingsService.OPEN`). */
export const OPEN_FROM = '1900-01-01';

// ------------------------------------------------------------------------------------------------ number series

export type SequenceScope = 'TENANT' | 'LEGAL_ENTITY';
export const SEQUENCE_SCOPES: SequenceScope[] = ['TENANT', 'LEGAL_ENTITY'];
export type ResetRule = 'NEVER' | 'YEARLY' | 'MONTHLY';
export const RESET_RULES: ResetRule[] = ['NEVER', 'YEARLY', 'MONTHLY'];
export type SequenceStatus = 'ACTIVE' | 'INACTIVE';
export const SEQUENCE_STATUSES: SequenceStatus[] = ['ACTIVE', 'INACTIVE'];

/** `SequenceService.SequenceView`. */
export interface Sequence {
  id: UUID;
  code: string;
  name: string;
  scopeLevel: SequenceScope;
  pattern: string;
  resetRule: ResetRule;
  gapFree: boolean;
  startValue: number;
  incrementBy: number;
  status: SequenceStatus;
  version: number;
}

/** `SequenceController.CreateSequenceRequest`. */
export interface CreateSequenceRequest {
  code: string;
  name: string;
  scopeLevel?: SequenceScope;
  pattern: string;
  resetRule?: ResetRule;
  gapFree?: boolean;
  startValue?: number;
  incrementBy?: number;
}

/** `SequenceController.UpdateSequenceRequest`: code, scope and start value cannot change. */
export interface UpdateSequenceRequest {
  name: string;
  pattern: string;
  resetRule: ResetRule;
  gapFree?: boolean;
  incrementBy?: number;
  status?: SequenceStatus;
  version: number;
}

export interface PreviewParams {
  legalEntityId?: UUID;
  onDate?: ISODate;
}

// ------------------------------------------------------------------------------------------------ clients

const enc = encodeURIComponent;

export const settingsApi = {
  list: () => http.get<Setting[]>('/core/settings'),
  get: (key: string) => http.get<Setting>(`/core/settings/${enc(key)}`),
  resolve: (key: string, params: ResolveParams) =>
    http.get<ResolvedSetting>(`/core/settings/${enc(key)}/resolve`, { params: { ...params } }),
  set: (key: string, body: SetSettingRequest) => http.put<ResolvedSetting>(`/core/settings/${enc(key)}`, body),
  remove: (key: string, valueId: UUID) => http.delete(`/core/settings/${enc(key)}/values/${valueId}`),
};

export const sequencesApi = {
  list: () => http.get<Sequence[]>('/core/sequences'),
  create: (body: CreateSequenceRequest) => http.post<Sequence>('/core/sequences', body),
  update: (code: string, body: UpdateSequenceRequest) => http.put<Sequence>(`/core/sequences/${enc(code)}`, body),
  preview: (code: string, params: PreviewParams) =>
    http.get<{ next: string }>(`/core/sequences/${enc(code)}/preview`, { params: { ...params } }),
};
