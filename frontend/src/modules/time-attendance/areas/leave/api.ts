import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

/**
 * Leave management API (backend `web.leave.LeaveController`, BRD 03 §4.6). Types mirror the Java records 1:1:
 * `LeaveStore.{TypeRow,PolicyRow,RequestRow,LedgerRow}`, `LeaveTypeService.{TypeInput,PolicyInput,SeedResult}`,
 * `LeaveRequestService.{RequestInput,Preview,SiClaimInput,SiClaimView}`, `LeaveBalanceService.{BalanceView,
 * AdjustOutcome,RunSummary}` and `api.LeaveSettlements.Settlement`. BigDecimal travels as a JSON number.
 */

// ------------------------------------------------------------------------------------------------ codes (DDL CHECKs)

export const LEAVE_CATEGORIES = [
  'ANNUAL',
  'PERSONAL_PAID',
  'PERSONAL_UNPAID',
  'SICK',
  'CHILD_SICK',
  'MATERNITY',
  'PATERNITY',
  'PRENATAL',
  'MISCARRIAGE',
  'WORK_ACCIDENT',
  'UNPAID_AGREED',
  'TOIL',
  'COMPANY_PAID',
  'OTHER',
] as const;
export type LeaveCategory = (typeof LEAVE_CATEGORIES)[number];
export const PAID_BY = ['EMPLOYER', 'SI_FUND', 'UNPAID'] as const;
export const MIN_UNITS = ['DAY', 'HALF_DAY', 'HOUR'] as const;
export const COUNTING_BASES = ['WORKING_DAY', 'CALENDAR_DAY'] as const;
export const PAYROLL_CATEGORIES = ['PAID_LEAVE_DAYS', 'SI_LEAVE_DAYS', 'UNPAID_DAYS'] as const;
export const ACCRUAL_METHODS = ['MONTHLY', 'ANNUAL_FRONT', 'PRORATA', 'NONE'] as const;
export const PRORATION_RULES = ['ND145_HALF_MONTH', 'CALENDAR_DAYS', 'NONE'] as const;
export const EXPIRY_ACTIONS = ['FORFEIT', 'ENCASH', 'FLAG'] as const;
export const REQUEST_STATUSES = ['DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'WITHDRAWN'] as const;
export type LeaveRequestStatus = (typeof REQUEST_STATUSES)[number];
export const DAY_PARTS = ['FULL', 'AM', 'PM'] as const;
export type DayPart = (typeof DAY_PARTS)[number];
export const TXN_TYPES = ['OPENING', 'ACCRUE', 'TAKE', 'CANCEL', 'CARRY', 'EXPIRE', 'ENCASH', 'ADJUST'] as const;
export const TREATMENT_TYPES = ['OUTPATIENT', 'INPATIENT'] as const;
/** `TypeInput.eligibility` keys (`LeaveTypeService.ELIGIBILITY_KEYS`). */
export interface LeaveEligibility {
  gender?: 'M' | 'F';
  min_service_months?: number;
  allow_probation?: boolean;
  employee_categories?: string[];
}

// ------------------------------------------------------------------------------------------------ types & policies

/** `LeaveStore.TypeData`. */
export interface LeaveTypeData {
  code: string;
  nameVi: string;
  nameEn?: string | null;
  category: string;
  paidBy: string;
  minUnit: string;
  countingBasis: string;
  tracksBalance: boolean;
  requiresSiClaimData: boolean;
  requiresAttachment: boolean;
  maxDaysPerRequest?: number | null;
  maxDaysPerYear?: number | null;
  countsAsService: boolean;
  attendanceCode: string;
  payrollCategory: string;
  eligibility: LeaveEligibility;
  statutory: boolean;
  status: string;
}

/** `LeaveStore.TypeRow`. */
export interface LeaveTypeRow {
  id: UUID;
  data: LeaveTypeData;
  version: number;
}

/** `LeaveTypeService.TypeInput` (POST / PUT; `version` on PUT; the code cannot change). */
export interface LeaveTypeInput {
  code?: string | null;
  nameVi: string;
  nameEn?: string | null;
  category: string;
  paidBy: string;
  minUnit?: string | null;
  countingBasis?: string | null;
  tracksBalance?: boolean | null;
  requiresSiClaimData?: boolean | null;
  requiresAttachment?: boolean | null;
  maxDaysPerRequest?: number | null;
  maxDaysPerYear?: number | null;
  countsAsService?: boolean | null;
  attendanceCode: string;
  payrollCategory: string;
  eligibility?: LeaveEligibility | null;
  status?: string | null;
  version?: number | null;
}

/** `LeaveTypeService.SeedResult`. */
export interface SeedResult {
  created: string[];
  existing: string[];
  warnings: string[];
}

/** `LeaveStore.PolicyData`. */
export interface LeavePolicyData {
  leaveTypeId: UUID;
  code: string;
  legalEntityId?: UUID | null;
  employeeCategoryCode?: string | null;
  accrualMethod: string;
  baseEntitlementDays?: number | null;
  seniorityStepYears?: number | null;
  seniorityStepDays?: number | null;
  prorationRule: string;
  leaveYearStartMonth: number;
  carryOverMaxDays?: number | null;
  carryOverExpiryMonths?: number | null;
  expiryAction: string;
  allowNegativeDays: number;
  noticeDays: number;
  validFrom: ISODate;
  validTo?: ISODate | null;
  status: string;
}

/** `LeaveStore.PolicyRow`. */
export interface LeavePolicyRow {
  id: UUID;
  data: LeavePolicyData;
  version: number;
}

/** `LeaveTypeService.PolicyInput`. */
export interface LeavePolicyInput {
  leaveTypeId: UUID;
  code: string;
  legalEntityId?: UUID | null;
  employeeCategoryCode?: string | null;
  accrualMethod?: string | null;
  baseEntitlementDays?: number | null;
  seniorityStepYears?: number | null;
  seniorityStepDays?: number | null;
  prorationRule?: string | null;
  leaveYearStartMonth?: number | null;
  carryOverMaxDays?: number | null;
  carryOverExpiryMonths?: number | null;
  expiryAction?: string | null;
  allowNegativeDays?: number | null;
  noticeDays?: number | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

// ------------------------------------------------------------------------------------------------ requests

/** `LeaveRequestService.SiClaimInput` (P3 values in plain; sealed by the backend under the person's key). */
export interface SiClaimInput {
  certificateType?: string | null;
  certificateNo?: string | null;
  certificateDate?: ISODate | null;
  issuingFacility?: string | null;
  diagnosisCategory?: string | null;
  longTermIllness?: boolean | null;
  treatmentType?: string | null;
  childBirthDate?: ISODate | null;
  childId?: string | null;
  confinementDate?: ISODate | null;
  childrenBornCount?: number | null;
  caesarean?: boolean | null;
}

/** `LeaveRequestService.SiClaimView` (audited read, `att.leave_request.view_si_data`). */
export type SiClaimView = SiClaimInput;

/** `LeaveController.LeaveRequestBody`. Times are `HH:mm[:ss]` (hourly leave only). */
export interface LeaveRequestBody {
  leaveTypeId?: UUID | null;
  leaveTypeCode?: string | null;
  startDate: ISODate;
  endDate?: ISODate | null;
  startPart?: DayPart | null;
  endPart?: DayPart | null;
  startTime?: string | null;
  endTime?: string | null;
  reason?: string | null;
  attachmentDocumentIds?: UUID[] | null;
  siClaim?: SiClaimInput | null;
}

/** `LeaveRules.CountedDay`. */
export interface CountedDay {
  date: ISODate;
  quantity: number;
  part?: string | null;
}

/** `LeaveRequestService.Preview`; map keys are leave years. Warnings are codes (`insufficient-balance 2026`, …). */
export interface LeavePreview {
  leaveTypeCode: string;
  requestUnit: string;
  durationDays: number;
  durationHours?: number | null;
  days: CountedDay[];
  daysPerLeaveYear: Record<string, number>;
  available: Record<string, number>;
  warnings: string[];
}

/** `LeaveStore.RequestRow` (without P3 columns; only last-4 values). */
export interface LeaveRequestRow {
  id: UUID;
  employmentId: UUID;
  personId: UUID;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  leaveTypeId: UUID;
  leaveTypeCode: string;
  requestUnit: string;
  startDate: ISODate;
  endDate: ISODate;
  startPart: string;
  endPart: string;
  startTime?: string | null;
  endTime?: string | null;
  durationDays: number;
  durationHours?: number | null;
  reason?: string | null;
  status: string;
  workflowInstanceId?: UUID | null;
  decidedAt?: ISOInstant | null;
  decidedBy?: UUID | null;
  cancelledAt?: ISOInstant | null;
  cancelReason?: string | null;
  actualReturnDate?: ISODate | null;
  attachmentDocumentIds?: UUID[] | null;
  siCertificateType?: string | null;
  siCertificateNoLast4?: string | null;
  siCertificateDate?: ISODate | null;
  childIdLast4?: string | null;
  childBirthDate?: ISODate | null;
  confinementDate?: ISODate | null;
  childrenBornCount?: number | null;
  hasSiClaimData: boolean;
  createdAt: ISOInstant;
}

export interface LeaveRequestListParams {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  leaveTypeId?: UUID;
  status?: string;
  from: ISODate;
  to: ISODate;
  limit?: number;
  offset?: number;
}

// ------------------------------------------------------------------------------------------------ balances

/** `LeaveBalanceService.BalanceView`. */
export interface BalanceView {
  leaveTypeId: UUID;
  leaveTypeCode: string;
  leaveYear: number;
  opening: number;
  accrued: number;
  carried: number;
  taken: number;
  adjusted: number;
  expired: number;
  encashed: number;
  balance: number;
  pending: number;
  available: number;
  yearStart: ISODate;
  yearEnd: ISODate;
}

/** `LeaveStore.LedgerRow`. */
export interface LedgerRow {
  id: UUID;
  employmentId: UUID;
  leaveTypeId: UUID;
  leaveTypeCode: string;
  leaveYear: number;
  txnType: string;
  quantity: number;
  unit: string;
  txnDate: ISODate;
  accrualMonth?: ISODate | null;
  expiresOn?: ISODate | null;
  leaveRequestId?: UUID | null;
  refType?: string | null;
  refId?: UUID | null;
  reason?: string | null;
  source: string;
  importJobId?: UUID | null;
  createdAt: ISOInstant;
}

/** `LeaveController.AdjustRequest`. */
export interface AdjustRequest {
  leaveTypeId: UUID;
  leaveYear?: number | null;
  quantity: number;
  reason: string;
}

/** 202 body of an adjustment that needs an approval (`LeaveBalanceService.AdjustOutcome`). */
export interface AdjustOutcome {
  outcome: 'ADJUSTED' | 'APPROVAL_REQUESTED';
  row?: LedgerRow | null;
  approvalRequestId?: UUID | null;
}

/** `LeaveSettlements.Line` (encash / recover are derived from `remaining`, as in the record's helpers). */
export interface SettlementLine {
  leaveTypeId: UUID;
  leaveTypeCode: string;
  leaveYear: number;
  entitledToLastDay: number;
  taken: number;
  remaining: number;
  finalised: boolean;
}

/** `LeaveSettlements.Settlement`; `lastWorkingDate` null = projection as of today. */
export interface Settlement {
  employmentId: UUID;
  lastWorkingDate?: ISODate | null;
  lines: SettlementLine[];
}

/** `LeaveBalanceService.RunSummary`; `failures` maps employment id → message. */
export interface RunSummary {
  run: string;
  employments: number;
  written: number;
  skipped: number;
  flagged: string[];
  failures: Record<UUID, string>;
}

// ------------------------------------------------------------------------------------------------ attendance codes

/** Minimal `TimesheetStore.CodeRow` (owned by the timesheets area; read-only here for the leave-type form). */
export interface AttendanceCodeLookup {
  id: UUID;
  code: string;
  labelVi: string;
  labelEn?: string | null;
  kind: string;
  status: string;
}

// ------------------------------------------------------------------------------------------------ client

const A = '/attendance';

export const leaveApi = {
  types: () => http.get<LeaveTypeRow[]>(`${A}/leave-types`),
  createType: (body: LeaveTypeInput) => http.post<LeaveTypeRow>(`${A}/leave-types`, body),
  updateType: (id: UUID, body: LeaveTypeInput) => http.put<LeaveTypeRow>(`${A}/leave-types/${id}`, body),
  seedStatutory: () => http.post<SeedResult>(`${A}/leave-types/statutory`),

  policies: (leaveTypeId?: UUID) => http.get<LeavePolicyRow[]>(`${A}/leave-policies`, { params: { leaveTypeId } }),
  createPolicy: (body: LeavePolicyInput) => http.post<LeavePolicyRow>(`${A}/leave-policies`, body),
  endPolicy: (id: UUID, version: number, validTo: ISODate) =>
    http.post<LeavePolicyRow>(`${A}/leave-policies/${id}/end`, { version, validTo }),

  preview: (employmentId: UUID, body: LeaveRequestBody) =>
    http.post<LeavePreview>(`${A}/employments/${employmentId}/leave-requests/preview`, body),
  submit: (employmentId: UUID, body: LeaveRequestBody) =>
    http.post<LeaveRequestRow>(`${A}/employments/${employmentId}/leave-requests`, body),
  ofEmployment: (employmentId: UUID, from: ISODate, to: ISODate) =>
    http.get<LeaveRequestRow[]>(`${A}/employments/${employmentId}/leave-requests`, { params: { from, to } }),
  requests: (p: LeaveRequestListParams) => http.get<LeaveRequestRow[]>(`${A}/leave-requests`, { params: { ...p } }),
  request: (id: UUID) => http.get<LeaveRequestRow>(`${A}/leave-requests/${id}`),
  withdraw: (id: UUID, reason?: string | null) => http.post<LeaveRequestRow>(`${A}/leave-requests/${id}/withdraw`, { reason }),
  earlyReturn: (id: UUID, returnDate: ISODate, attachmentDocumentIds?: UUID[] | null) =>
    http.post<LeaveRequestRow>(`${A}/leave-requests/${id}/early-return`, { returnDate, attachmentDocumentIds }),
  siClaim: (id: UUID) => http.get<SiClaimView>(`${A}/leave-requests/${id}/si-claim`),
  updateSiClaim: (id: UUID, body: SiClaimInput) => http.put<LeaveRequestRow>(`${A}/leave-requests/${id}/si-claim`, body),

  balances: (employmentId: UUID, asOf?: ISODate) =>
    http.get<BalanceView[]>(`${A}/employments/${employmentId}/leave-balances`, { params: { asOf } }),
  ledger: (employmentId: UUID, leaveTypeId?: UUID, leaveYear?: number) =>
    http.get<LedgerRow[]>(`${A}/employments/${employmentId}/leave-ledger`, { params: { leaveTypeId, leaveYear } }),
  /** 201 → the ADJUST ledger row; 202 → `AdjustOutcome` (approval requested). */
  adjust: (employmentId: UUID, body: AdjustRequest) =>
    http.post<LedgerRow | AdjustOutcome>(`${A}/employments/${employmentId}/leave-adjustments`, body),
  settlement: (employmentId: UUID) => http.get<Settlement>(`${A}/employments/${employmentId}/leave-settlement`),
  finaliseSettlement: (employmentId: UUID) => http.post<Settlement>(`${A}/employments/${employmentId}/leave-settlement`),

  /** `month` is `yyyy-MM` (YearMonth). */
  runAccrual: (legalEntityId: UUID | null | undefined, month: string) =>
    http.post<RunSummary>(`${A}/leave-balances/accrual`, { legalEntityId: legalEntityId ?? null, month }),
  runYearEnd: (legalEntityId: UUID | null | undefined, year: number) =>
    http.post<RunSummary>(`${A}/leave-balances/year-end`, { legalEntityId: legalEntityId ?? null, year }),
  runCarryExpiry: (legalEntityId: UUID | null | undefined, asOf?: ISODate | null) =>
    http.post<RunSummary>(`${A}/leave-balances/carry-expiry`, { legalEntityId: legalEntityId ?? null, asOf: asOf ?? null }),

  attendanceCodes: () => http.get<AttendanceCodeLookup[]>(`${A}/attendance-codes`),
};

export function isAdjustOutcome(v: LedgerRow | AdjustOutcome): v is AdjustOutcome {
  return (v as AdjustOutcome).outcome !== undefined;
}
