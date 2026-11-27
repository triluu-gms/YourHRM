import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

// ------------------------------------------------------------------------------------------------ codes

/** `att.overtime_request.status` (overtime_request_status_ck). */
export type OvertimeStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
export const OVERTIME_STATUSES: OvertimeStatus[] = ['DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'];

/** `OvertimeService.CONSENT_METHODS`: ESS (the employee's own action), PAPER (signed form), MANAGER_RECORDED. */
export const CONSENT_METHODS = ['ESS', 'PAPER', 'MANAGER_RECORDED'] as const;
export type ConsentMethod = (typeof CONSENT_METHODS)[number];

/** `att.overtime_result.ot_category` (overtime_result_category_ck): each minute falls into exactly one. */
export const OT_CATEGORIES = ['OT-WD', 'OT-RD', 'OT-PH', 'OT-WD-N', 'OT-RD-N', 'OT-PH-N'] as const;
export type OtCategory = (typeof OT_CATEGORIES)[number];

/** `OvertimeRules.Level`. */
export type CapLevel = 'OK' | 'WARN' | 'EXCEEDED';
/** `att.ot_cap_ledger.regime_code`. */
export type OtRegime = 'STANDARD_200' | 'EXTENDED_300';

// ------------------------------------------------------------------------------------------------ records

/** `OvertimeService.Finding`: BLOCK keeps the request in DRAFT, WARN is shown to the requester. */
export interface Finding {
  code: string;
  severity: 'BLOCK' | 'WARN';
  message: string;
  data?: Record<string, unknown> | null;
}

/** `OvertimeService.CategoryMinutes`. */
export interface CategoryMinutes {
  category: OtCategory;
  minutes: number;
  precededByDayOt: boolean;
}

/** `OvertimeService.RequestView`. */
export interface OvertimeRequestView {
  id: UUID;
  employmentId: UUID;
  legalEntityId: UUID;
  requestGroupId?: UUID | null;
  workDate: ISODate;
  plannedStartAt: ISOInstant;
  plannedEndAt: ISOInstant;
  plannedMinutes: number;
  reason?: string | null;
  status: OvertimeStatus;
  employeeConsent: boolean;
  consentMethod?: ConsentMethod | null;
  consentAt?: ISOInstant | null;
  protectedWorker: boolean;
  capOverride: boolean;
  capOverrideReason?: string | null;
  workflowInstanceId?: UUID | null;
  decidedAt?: ISOInstant | null;
  categories: CategoryMinutes[];
  version: number;
}

/** `OvertimeService.Outcome`. */
export interface OvertimeOutcome {
  request: OvertimeRequestView;
  submitted: boolean;
  findings: Finding[];
}

/** `OvertimeStore.ResultView`: paid overtime per work date and category. */
export interface OvertimeResultView {
  id: UUID;
  employmentId: UUID;
  workDate: ISODate;
  otCategory: OtCategory;
  actualMinutes: number;
  approvedMinutes: number;
  paidMinutes: number;
  precededByDayOt: boolean;
  calcVersion: number;
  legalPackId?: UUID | null;
}

/** `OvertimeService.CapView` (one per MONTH / YEAR). */
export interface OvertimeCapView {
  kind: 'MONTH' | 'YEAR';
  periodStart: ISODate;
  regime: OtRegime | string;
  capMin: number;
  openingMin: number;
  approvedMin: number;
  pendingMin: number;
  paidMin: number;
  overrideMin: number;
  usedMin: number;
  level: CapLevel;
}

/** `OvertimeService.RegimeView` (BR-ATT-049). */
export interface OvertimeRegimeView {
  legalEntityId: UUID;
  asOf: ISODate;
  regimeRequested: OtRegime | string;
  regimeApplied: OtRegime | string;
  noticeRef?: string | null;
  noticeRequired: boolean;
  monthCapMin: number;
  yearCapMin: number;
}

/** `OvertimeController.CreateRequest`; times are local `HH:mm`. */
export interface CreateOvertimeRequest {
  employmentIds: UUID[];
  workDate: ISODate;
  startTime: string;
  endTime: string;
  reason?: string | null;
  consent?: boolean;
  consentMethod?: ConsentMethod | null;
  submit?: boolean;
  capOverrideReason?: string | null;
}

export interface RangeParams {
  from: ISODate;
  to: ISODate;
}

// ------------------------------------------------------------------------------------------------ client

const BASE = '/attendance';

export const overtimeApi = {
  create: (body: CreateOvertimeRequest) => http.post<OvertimeOutcome[]>(`${BASE}/overtime-requests`, body),
  get: (id: UUID) => http.get<OvertimeRequestView>(`${BASE}/overtime-requests/${id}`),
  consent: (id: UUID) => http.post<OvertimeRequestView>(`${BASE}/overtime-requests/${id}/consent`),
  submit: (id: UUID, capOverrideReason?: string | null) =>
    http.post<OvertimeOutcome>(`${BASE}/overtime-requests/${id}/submit`, { capOverrideReason: capOverrideReason || null }),
  cancel: (id: UUID, reason?: string | null) =>
    http.post<OvertimeRequestView>(`${BASE}/overtime-requests/${id}/cancel`, { reason: reason || null }),
  list: (employmentId: UUID, p: RangeParams) =>
    http.get<OvertimeRequestView[]>(`${BASE}/employments/${employmentId}/overtime-requests`, { params: { ...p } }),
  results: (employmentId: UUID, p: RangeParams) =>
    http.get<OvertimeResultView[]>(`${BASE}/employments/${employmentId}/overtime-results`, { params: { ...p } }),
  caps: (employmentId: UUID, date: ISODate) =>
    http.get<OvertimeCapView[]>(`${BASE}/employments/${employmentId}/overtime-caps`, { params: { date } }),
  regime: (legalEntityId: UUID, date: ISODate) =>
    http.get<OvertimeRegimeView>(`${BASE}/legal-entities/${legalEntityId}/overtime-regime`, { params: { date } }),
};
