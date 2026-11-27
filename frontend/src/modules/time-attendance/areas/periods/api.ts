import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

// ------------------------------------------------------------------------------------------------ codes

/** `att.attendance_period.status` (state machine `tg_attendance_period_guard`). */
export type PeriodStatus = 'OPEN' | 'IN_REVIEW' | 'CLOSED' | 'LOCKED';
export const PERIOD_STATUSES: PeriodStatus[] = ['OPEN', 'IN_REVIEW', 'CLOSED', 'LOCKED'];

/** `attendance_period.std_day_method` (copied from the pay calendar, BR-ATT-016). */
export type StdDayMethod = 'FIXED_DAYS' | 'SCHEDULE_DAYS' | 'CALENDAR_DAYS' | 'FIXED_HOURS';

/** Checklist codes of `PeriodService.checklist` (BR-ATT-074). */
export const CHECKLIST_CODES = [
  'STD_METHOD_MISSING',
  'PERIOD_NOT_ENDED',
  'NOT_PROCESSED',
  'MISSING_PUNCH',
  'ABSENT_NO_CODE',
  'QUARANTINED_PUNCH',
  'OT_CAP',
  'PENDING_LEAVE',
  'PENDING_OVERTIME',
  'PENDING_CORRECTION',
] as const;

/** `AttendanceResults.Hold.status`. */
export type HoldStatus = 'ACTIVE' | 'RELEASED' | 'CONSUMED';
/** `attendance_result.status`. */
export type ResultStatus = 'PUBLISHED' | 'WITHDRAWN' | 'LOCKED';
/** `PeriodService.ReopenOutcome.outcome`. */
export type ReopenOutcomeCode = 'REOPENED' | 'RELEASE_REQUESTED' | 'APPROVAL_REQUESTED';
/** `ResultImportService.JobStatus.status`. */
export type ImportJobStatus = 'STAGED' | 'PUBLISHED' | 'EMPTY';

// ------------------------------------------------------------------------------------------------ records

/** `AttendanceResults.Hold` (the sole authority for close-hold state, ADR-0019). */
export interface Hold {
  id: UUID;
  periodId: UUID;
  resultId: UUID;
  resultVersion: number;
  scope: 'PERIOD' | 'EMPLOYMENT';
  employmentId?: UUID | null;
  payrollRunId: UUID;
  status: HoldStatus;
  heldAt: ISOInstant;
  releaseReason?: string | null;
  releasedAt?: ISOInstant | null;
  consumedAt?: ISOInstant | null;
}

/** `PeriodService.PeriodView`. */
export interface PeriodView {
  id: UUID;
  legalEntityId: UUID;
  payGroupId: UUID;
  periodStart: ISODate;
  periodEnd: ISODate;
  otPeriodStart?: ISODate | null;
  otPeriodEnd?: ISODate | null;
  stdDayMethod?: StdDayMethod | null;
  stdDayValue?: number | null;
  closeDeadline?: ISODate | null;
  status: PeriodStatus;
  currentVersion: number;
  lockedVersion?: number | null;
  closedAt?: ISOInstant | null;
  reopenedAt?: ISOInstant | null;
  reopenReason?: string | null;
  reopenRequestedAt?: ISOInstant | null;
  reopenRequestReason?: string | null;
  activeHold?: Hold | null;
  version: number;
}

/** `PeriodService.ChecklistItem`. */
export interface ChecklistItem {
  code: string;
  count: number;
  blocking: boolean;
  waivable: boolean;
}

/** `PeriodService.Checklist`; `employmentId` null for the period scope. */
export interface Checklist {
  periodId: UUID;
  employmentId?: UUID | null;
  dateFrom: ISODate;
  dateTo: ISODate;
  canClose: boolean;
  items: ChecklistItem[];
}

/** `AttendanceResults.Result` (immutable version). */
export interface AttendanceResult {
  id: UUID;
  periodId: UUID;
  legalEntityId: UUID;
  payGroupId: UUID;
  periodStart: ISODate;
  periodEnd: ISODate;
  scope: 'PERIOD' | 'EMPLOYMENT';
  employmentId?: UUID | null;
  kind: 'REGULAR' | 'EARLY_CLOSE' | 'OFF_CYCLE_RETRO' | string;
  version: number;
  status: ResultStatus | string;
  attDateFrom: ISODate;
  attDateTo: ISODate;
  otDateFrom?: ISODate | null;
  otDateTo?: ISODate | null;
  checksum?: string | null;
  lineCount?: number | null;
  closeCause: string;
  ruleVersion?: string | null;
  publishedAt?: ISOInstant | null;
}

/** `AttendanceResults.Line` (BRD §7.3); retro lines carry the original period / result / segment, signed quantity. */
export interface ResultLine {
  employmentId: UUID;
  assignmentKey?: UUID | null;
  segmentFrom: ISODate;
  segmentTo: ISODate;
  legalEntityId: UUID;
  orgUnitId?: UUID | null;
  payGroupId?: UUID | null;
  costCentreId?: UUID | null;
  category: string;
  unit: string;
  quantity: number;
  periodQuantity?: number | null;
  precededByDayOt: boolean;
  retro: boolean;
  originalPeriodId?: UUID | null;
  originalResultId?: UUID | null;
  originalSegmentFrom?: ISODate | null;
  retroReason?: string | null;
  schedulePatternId?: UUID | null;
  employeeGroupCode?: string | null;
  fte?: number | null;
}

/** `AttendanceResultStore.RetroCandidate`. */
export interface RetroCandidate {
  id: UUID;
  employmentId: UUID;
  periodId: UUID;
  periodStart: ISODate;
  periodEnd: ISODate;
  firstDate: ISODate;
  lastDate: ISODate;
}

/** `PeriodService.ReopenOutcome`. */
export interface ReopenOutcome {
  outcome: ReopenOutcomeCode;
  period: PeriodView;
  hold?: Hold | null;
  approvalRequestId?: UUID | null;
}

/** `PeriodController.CloseRequest`: waived checklist codes need `waiveReason`. */
export interface CloseRequest {
  waive?: string[];
  waiveReason?: string | null;
}

/** `ResultImportService.PublishedPeriod`. */
export interface PublishedPeriod {
  periodId: UUID;
  periodStart: ISODate;
  resultId: UUID;
  version: number;
  lines: number;
}

/** `ResultImportService.JobStatus`. */
export interface ImportJobView {
  importJobId: UUID;
  status: ImportJobStatus;
  stagedLines: number;
  publishedLines: number;
  periods: PublishedPeriod[];
}

export interface PeriodFilter {
  legalEntityId?: UUID;
  payGroupId?: UUID;
  status?: PeriodStatus;
  from?: ISODate;
  to?: ISODate;
}

// ------------------------------------------------------------------------------------------------ client

const BASE = '/attendance';

export const periodsApi = {
  list: (f: PeriodFilter) => http.get<PeriodView[]>(`${BASE}/attendance-periods`, { params: { ...f } }),
  get: (id: UUID) => http.get<PeriodView>(`${BASE}/attendance-periods/${id}`),
  checklist: (id: UUID, employmentId?: UUID) =>
    http.get<Checklist>(`${BASE}/attendance-periods/${id}/checklist`, { params: { employmentId } }),
  review: (id: UUID) => http.post<PeriodView>(`${BASE}/attendance-periods/${id}/review`),
  close: (id: UUID, body: CloseRequest) => http.post<AttendanceResult>(`${BASE}/attendance-periods/${id}/close`, body),
  reopen: (id: UUID, reason: string) => http.post<ReopenOutcome>(`${BASE}/attendance-periods/${id}/reopen`, { reason }),
  earlyClose: (id: UUID, employmentId: UUID, body: CloseRequest) =>
    http.post<AttendanceResult>(`${BASE}/attendance-periods/${id}/employments/${employmentId}/early-close`, body),
  results: (id: UUID) => http.get<AttendanceResult[]>(`${BASE}/attendance-periods/${id}/results`),
  holds: (id: UUID) => http.get<Hold[]>(`${BASE}/attendance-periods/${id}/holds`),
  retroRequests: (id: UUID) => http.get<RetroCandidate[]>(`${BASE}/attendance-periods/${id}/retro-requests`),
  result: (id: UUID) => http.get<AttendanceResult>(`${BASE}/attendance-results/${id}`),
  lines: (id: UUID, employmentId?: UUID) =>
    http.get<ResultLine[]>(`${BASE}/attendance-results/${id}/lines`, { params: { employmentId } }),
  importJob: (jobId: UUID) => http.get<ImportJobView>(`${BASE}/result-imports/${jobId}`),
  publishImport: (jobId: UUID) => http.post<ImportJobView>(`${BASE}/result-imports/${jobId}/publish`),
};
