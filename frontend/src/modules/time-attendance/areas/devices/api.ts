import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

/**
 * Punch sources (backend `web.punch.PunchController`, BR-ATT-020/022/025/027): device registry, enrolment mappings and
 * the quarantine of unmatched punches. Types mirror `DeviceService`, `EnrolmentMappingService`, `QuarantineService`
 * and `PunchStore.PunchRow` 1:1. Punch files themselves are uploaded through Core's import engine.
 */

export const DEVICE_STATUSES = ['ACTIVE', 'INACTIVE', 'RETIRED'] as const;
export type DeviceStatus = (typeof DEVICE_STATUSES)[number];
/** `DeviceService.LAYOUTS` (tenant-defined layouts are not built, US-ATT-017). */
export const FILE_LAYOUTS = ['STANDARD', 'ZKTECO', 'HIKVISION'] as const;
export const DEVICE_CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]{0,39}$/;

/** `DeviceService.DeviceView`. */
export interface DeviceView {
  id: UUID;
  code: string;
  name: string;
  terminalCode: string;
  vendor?: string | null;
  model?: string | null;
  serialNo?: string | null;
  legalEntityId?: UUID | null;
  locationId?: UUID | null;
  timeZone?: string | null;
  /** Always `FILE_IMPORT` in P1. */
  connectorType: string;
  fileLayoutCode?: string | null;
  lastImportAt?: ISOInstant | null;
  status: string;
  version: number;
}

/** `PunchController.DeviceRequest` (version only on PUT). */
export interface DeviceRequest {
  code: string;
  name: string;
  terminalCode: string;
  vendor?: string | null;
  model?: string | null;
  serialNo?: string | null;
  legalEntityId?: UUID | null;
  locationId?: UUID | null;
  timeZone?: string | null;
  fileLayoutCode?: string | null;
  status?: string | null;
  version?: number;
}

/** `EnrolmentMappingService.MappingView`; `deviceId` null = every device; `validTo` exclusive. */
export interface MappingView {
  id: UUID;
  deviceId?: UUID | null;
  enrolId: string;
  employmentId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
  source: string;
  version: number;
}

export interface MappingRequest {
  deviceId?: UUID | null;
  enrolId: string;
  employmentId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

export interface MappingQuery {
  deviceId?: UUID;
  tenantWideOnly?: boolean;
  enrolId?: string;
  employmentId?: UUID;
  on?: ISODate;
  limit?: number;
  offset?: number;
}

export const QUARANTINE_STATUSES = [
  'PENDING',
  'QUARANTINED_UNMAPPED',
  'QUARANTINED_EXPIRED',
  'QUARANTINED_UNKNOWN_DEVICE',
] as const;
/** The backend refuses a quarantine range longer than this (`QuarantineService.MAX_RANGE_DAYS`). */
export const MAX_RANGE_DAYS = 62;

/** `PunchStore.PunchRow` (no P3 column in P1). `localTs` is a LocalDateTime of the device's zone. */
export interface PunchRow {
  id: UUID;
  workDateHint: ISODate;
  punchAt: ISOInstant;
  localTs: string;
  tzName?: string | null;
  source: string;
  deviceId?: UUID | null;
  terminalCode?: string | null;
  enrolId?: string | null;
  direction?: string | null;
  verifyMethod?: string | null;
  importJobId?: UUID | null;
  importRowNo?: number | null;
  receivedAt?: ISOInstant | null;
  employmentId?: UUID | null;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  enrolmentMappingId?: UUID | null;
  validationStatus: string;
  matchedAt?: ISOInstant | null;
}

export interface QuarantineQuery {
  from: ISODate;
  to: ISODate;
  status?: string;
  deviceId?: UUID;
  terminalCode?: string;
  enrolId?: string;
  limit?: number;
  offset?: number;
}

/** `QuarantineService.RematchResult`. */
export interface RematchResult {
  examined: number;
  matched: number;
  stillQuarantined: number;
}

export interface RematchRequest {
  from: ISODate;
  to: ISODate;
  deviceId?: UUID | null;
  enrolId?: string | null;
}

/** `QuarantineService.PunchRef`: the id plus its partition key. */
export interface PunchRef {
  id: UUID;
  workDateHint: ISODate;
}

const BASE = '/attendance';

export const devicesApi = {
  list: (status?: string) => http.get<DeviceView[]>(`${BASE}/devices`, { params: { status } }),
  get: (id: UUID) => http.get<DeviceView>(`${BASE}/devices/${id}`),
  create: (body: DeviceRequest) => http.post<DeviceView>(`${BASE}/devices`, body),
  update: (id: UUID, body: DeviceRequest) => http.put<DeviceView>(`${BASE}/devices/${id}`, body),
};

export const mappingsApi = {
  list: (q: MappingQuery) => http.get<MappingView[]>(`${BASE}/enrolment-mappings`, { params: { ...q } }),
  create: (body: MappingRequest) => http.post<MappingView>(`${BASE}/enrolment-mappings`, body),
  /** Sets the exclusive end date (null = open again). */
  end: (id: UUID, version: number, validTo: ISODate | null) =>
    http.post<MappingView>(`${BASE}/enrolment-mappings/${id}/end`, { version, validTo }),
};

export const quarantineApi = {
  list: (q: QuarantineQuery) => http.get<PunchRow[]>(`${BASE}/punches/quarantine`, { params: { ...q } }),
  counts: (q: { legalEntityId?: UUID; from: ISODate; to: ISODate }) =>
    http.get<Record<string, number>>(`${BASE}/punches/quarantine/counts`, { params: { ...q } }),
  rematch: (body: RematchRequest) => http.post<RematchResult>(`${BASE}/punches/quarantine/rematch`, body),
  ignore: (punches: PunchRef[], reason: string) =>
    http.post<{ ignored: number }>(`${BASE}/punches/quarantine/ignore`, { punches, reason }),
};
