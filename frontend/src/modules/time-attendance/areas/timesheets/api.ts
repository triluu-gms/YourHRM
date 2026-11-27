import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

/**
 * Daily timesheets (backend `web.timesheet.TimesheetController` + `MenstrualBreakController`, BR-ATT-030..039).
 * Types mirror `TimesheetStore.CodeRow/DayRow/CorrectionRow`, `TimesheetQueries.DayDetail`, `CorrectionService`,
 * `TimesheetProcessor.Summary` and `MenstrualBreakService` 1:1. A computed day is never edited: edits and approved
 * corrections are inputs every recalculation applies.
 */

// ------------------------------------------------------------------------------------------------ codes

/** `AttendanceCodeService.KINDS` / `PAID_BY`. */
export const CODE_KINDS = ['WORK', 'LEAVE', 'HOLIDAY', 'TRIP', 'WFH', 'ABSENCE', 'REST', 'NOT_EMPLOYED', 'SUSPENDED'] as const;
export const PAID_BY = ['EMPLOYER', 'SI_FUND', 'UNPAID', 'NONE'] as const;
export const CODE_PATTERN = /^[\p{Lu}0-9_]{1,10}$/u;
export const CATEGORY_PATTERN = /^[A-Z][A-Z_]*$/;
export const COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/;

/** `TimesheetStore.CodeRow`; `global` rows are platform defaults (read-only for tenants). */
export interface CodeRow {
  id: UUID;
  global: boolean;
  code: string;
  labelVi: string;
  labelEn?: string | null;
  kind: string;
  payrollCategory?: string | null;
  payrollSubCode?: string | null;
  countsAsWorked: boolean;
  paidBy: string;
  siNonwork: boolean;
  displayOrder: number;
  colorHex?: string | null;
  status: string;
  version: number;
}

/** `TimesheetController.CodeRequest` (version only on PUT). */
export interface CodeRequest {
  code: string;
  labelVi: string;
  labelEn?: string | null;
  kind: string;
  payrollCategory?: string | null;
  payrollSubCode?: string | null;
  countsAsWorked?: boolean | null;
  paidBy?: string | null;
  siNonwork?: boolean | null;
  displayOrder?: number | null;
  colorHex?: string | null;
  status?: string | null;
  version?: number;
}

// ------------------------------------------------------------------------------------------------ days

export const DAY_STATUSES = [
  'SCHEDULED',
  'PRESENT',
  'PARTIAL',
  'ABSENT',
  'ON_LEAVE',
  'HOLIDAY',
  'REST',
  'INCOMPLETE',
  'NOT_EMPLOYED',
  'SUSPENDED',
] as const;
export const DAY_TYPES = ['WORKDAY', 'REST_DAY', 'PUBLIC_HOLIDAY', 'SUBSTITUTE_HOLIDAY', 'COMPANY_HOLIDAY'] as const;
/** Exceptions set by `DayCalculator`; `DayModel.BLOCKING` block the close. */
export const EXCEPTIONS = ['MISSING_IN', 'MISSING_OUT', 'ABSENT_NO_CODE', 'LATE', 'EARLY', 'CODE_NOT_APPLIED_OFF_DAY'] as const;

export interface CodeQty {
  code: string;
  qty: number;
}

/** `TimesheetStore.DayRow`. Decimal hours arrive as JSON numbers. */
export interface DayRow {
  id: UUID;
  employmentId: UUID;
  workDate: ISODate;
  assignmentKey?: UUID | null;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  locationId?: UUID | null;
  periodId?: UUID | null;
  shiftId?: UUID | null;
  dayType: string;
  dayStatus: string;
  primaryCode?: string | null;
  codes: CodeQty[];
  firstInAt?: ISOInstant | null;
  lastOutAt?: ISOInstant | null;
  scheduledHours?: number | null;
  workedHours?: number | null;
  paidBreakHours?: number | null;
  protectedBreakMin: number;
  lateMin: number;
  earlyMin: number;
  absentHours?: number | null;
  nightHours?: number | null;
  otCandidateMin: number;
  dayValue?: number | null;
  exceptions: string[];
  hasBlockingException: boolean;
  manualOverride: boolean;
  calcVersion: number;
  calcAt?: ISOInstant | null;
  calcInputsHash?: string | null;
  legalPackId?: UUID | null;
  ruleVersion?: string | null;
}

export const CORRECTION_TYPES = ['MISSING_PUNCH', 'WRONG_TIME', 'FORGOT_CHECKIN', 'CLIENT_SITE', 'CODE_CHANGE'] as const;
export const CORRECTION_STATUSES = ['DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'APPLIED'] as const;
/** Kinds an employee may request (`CorrectionService.REQUESTABLE_KINDS`). */
export const REQUESTABLE_KINDS = ['WORK', 'TRIP', 'WFH'];

/** `TimesheetStore.CorrectionRow`; `fieldChanges` is the before / after JSON array as text. */
export interface CorrectionRow {
  id: UUID;
  employmentId: UUID;
  workDate: ISODate;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  correctionType: string;
  requestedInAt?: ISOInstant | null;
  requestedOutAt?: ISOInstant | null;
  requestedCode?: string | null;
  fieldChanges?: string | null;
  reasonCode?: string | null;
  reasonText?: string | null;
  source: string;
  bulkBatchId?: UUID | null;
  status: string;
  workflowInstanceId?: UUID | null;
  decidedAt?: ISOInstant | null;
  decidedBy?: UUID | null;
  appliedCalcVersion?: number | null;
  createdAt: ISOInstant;
  createdBy?: UUID | null;
  version: number;
}

/** `PunchStore.PunchRow` (restated: the devices area owns its own copy). `localTs` is the device's local time. */
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
  validationStatus: string;
  matchedAt?: ISOInstant | null;
}

/** `TimesheetQueries.DayDetail`. */
export interface DayDetail {
  day: DayRow | null;
  punches: PunchRow[];
  corrections: CorrectionRow[];
}

export interface GridQuery {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  from: ISODate;
  to: ISODate;
  status?: string;
  exception?: string;
  blockingOnly?: boolean;
  limit?: number;
  offset?: number;
}

/** `TimesheetController.RecomputeRequest`: a legal entity or a list of employments. */
export interface RecomputeRequest {
  legalEntityId?: UUID | null;
  employmentIds?: UUID[] | null;
  from: ISODate;
  to: ISODate;
}

/** `TimesheetProcessor.Summary`. */
export interface RecomputeSummary {
  written: number;
  unchanged: number;
  frozen: number;
  skipped: number;
  failures: string[];
}

export interface EditItem {
  employmentId: UUID;
  workDate: ISODate;
}

/** `TimesheetController.EditRequest` (BR-ATT-037): a code and / or in / out times, reason mandatory. */
export interface EditRequest {
  days: EditItem[];
  code?: string | null;
  inAt?: ISOInstant | null;
  outAt?: ISOInstant | null;
  reason: string;
}

/** `CorrectionService.EditResult`. */
export interface EditResult {
  bulkBatchId?: UUID | null;
  correctionIds: UUID[];
  applied: number;
}

/** `TimesheetController.CorrectionRequest` (BR-ATT-036). */
export interface CorrectionRequest {
  workDate: ISODate;
  correctionType: string;
  requestedIn?: ISOInstant | null;
  requestedOut?: ISOInstant | null;
  requestedCode?: string | null;
  reasonCode?: string | null;
  reasonText?: string | null;
  attachmentDocumentIds?: UUID[] | null;
}

// ------------------------------------------------------------------------------------------------ menstrual break

/** `MenstrualBreakService.Declaration`. */
export interface MenstrualBreakDeclaration {
  id: UUID;
  workDate: ISODate;
  source: string;
  status: string;
  createdAt: ISOInstant;
  withdrawnAt?: ISOInstant | null;
}

/** `MenstrualBreakService.MonthView`; `month` is `YYYY-MM`. */
export interface MenstrualBreakMonth {
  employmentId: UUID;
  month: string;
  activeInMonth: number;
  daysPerMonth?: number | null;
  days: MenstrualBreakDeclaration[];
}

/** `MenstrualBreakService.DeclareResult`. */
export interface DeclareResult {
  declaration: MenstrualBreakDeclaration;
  activeInMonth: number;
  daysPerMonth: number;
  warnings: string[];
}

const BASE = '/attendance';
const emp = (id: UUID) => `${BASE}/employments/${id}`;

export const codesApi = {
  list: () => http.get<CodeRow[]>(`${BASE}/attendance-codes`),
  create: (body: CodeRequest) => http.post<CodeRow>(`${BASE}/attendance-codes`, body),
  update: (id: UUID, body: CodeRequest) => http.put<CodeRow>(`${BASE}/attendance-codes/${id}`, body),
};

export const timesheetsApi = {
  grid: (q: GridQuery) => http.get<DayRow[]>(`${BASE}/timesheets`, { params: { ...q } }),
  ofEmployment: (employmentId: UUID, from: ISODate, to: ISODate) =>
    http.get<DayRow[]>(`${emp(employmentId)}/timesheets`, { params: { from, to } }),
  day: (employmentId: UUID, workDate: ISODate) => http.get<DayDetail>(`${emp(employmentId)}/timesheets/${workDate}`),
  recompute: (body: RecomputeRequest) => http.post<RecomputeSummary>(`${BASE}/timesheets/recompute`, body),
  edit: (body: EditRequest) => http.post<EditResult>(`${BASE}/timesheets/edits`, body),
  requestCorrection: (employmentId: UUID, body: CorrectionRequest) =>
    http.post<CorrectionRow>(`${emp(employmentId)}/timesheet-corrections`, body),
};

/** Raw punches of an employment (audited read, `att.punch.view`). */
export const punchesApi = {
  ofEmployment: (employmentId: UUID, from: ISODate, to: ISODate) =>
    http.get<PunchRow[]>(`${emp(employmentId)}/punches`, { params: { from, to } }),
};

export const menstrualBreakApi = {
  month: (employmentId: UUID, month?: string) =>
    http.get<MenstrualBreakMonth>(`${emp(employmentId)}/menstrual-breaks`, { params: { month } }),
  declare: (employmentId: UUID, workDate: ISODate) =>
    http.post<DeclareResult>(`${emp(employmentId)}/menstrual-breaks`, { workDate }),
  withdraw: (employmentId: UUID, workDate: ISODate) =>
    http.delete<MenstrualBreakDeclaration>(`${emp(employmentId)}/menstrual-breaks/${workDate}`),
};
