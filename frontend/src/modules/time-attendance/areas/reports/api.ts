import { http, type ISODate, type ISOInstant, type QueryParams, type UUID } from '@/shared/api';

/**
 * T&A reports (BRD 03 §8, R-01..R-09), integration inbox and manual jobs. Types mirror the backend records 1:1:
 * `AttendanceReportController` + `TimesheetReports` / `OvertimeReports` / `LeaveReports` / `PeriodReports`,
 * `ReportStore` rows, `ReportFigures`, `AttendanceInboxController` (`AttInbox.Message`, `LocationProjection`),
 * `AttendanceJobController` (`AttendanceJobs.NightlySummary`) and `ReminderController`
 * (`AttendanceReminderJobs.Summary`). BigDecimal arrives as a JSON number.
 */

const BASE = '/attendance/reports';

/** `ReportPage<T>`: limit/offset window (limit ≤ 2000) with `hasMore` instead of a total. */
export interface ReportPage<T> {
  items: T[];
  limit: number;
  offset: number;
  hasMore: boolean;
}

export interface Paging {
  limit?: number;
  offset?: number;
}

/** `lang` (vi | en) picks the language of the server labels. */
interface Lang {
  lang?: string;
}

export interface Column {
  code: string;
  label: string;
}

// ----------------------------------------------------------------------------------- R-01 timesheet grid

export interface GridDate {
  date: ISODate;
  dayOfMonth: number;
  weekday: string;
}

export interface CodeLegend {
  code: string;
  label: string;
  payrollCategory: string | null;
}

export interface GridRow {
  employmentId: UUID;
  employeeNo: string | null;
  fullName: string | null;
  orgUnitId: UUID | null;
  /** One attendance code (or empty) per `dates` entry. */
  cells: (string | null)[];
  codeTotals: Record<string, number>;
  categoryTotals: Record<string, number>;
  workedHours: number;
  overtimeHours: number;
  nightHours: number;
  lateMin: number;
  earlyMin: number;
  exceptionDays: number;
  missingDays: number;
}

export interface GridReport {
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  periodId: UUID | null;
  from: ISODate;
  to: ISODate;
  dates: GridDate[];
  categories: Column[];
  codes: CodeLegend[];
  page: ReportPage<GridRow>;
  pageTotals: Record<string, number>;
}

export interface GridParams extends Paging, Lang {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  periodId?: UUID;
  from?: ISODate;
  to?: ISODate;
}

// ----------------------------------------------------------------------------------- R-02 exceptions

export const EXCEPTION_TYPES = ['LATE', 'EARLY', 'MISSING_PUNCH', 'ABSENT'] as const;
export type ExceptionType = (typeof EXCEPTION_TYPES)[number];

export interface ExceptionLine {
  employmentId: UUID;
  workDate: ISODate;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  dayStatus: string;
  dayStatusLabel: string;
  primaryCode: string | null;
  firstInAt: ISOInstant | null;
  lastOutAt: ISOInstant | null;
  scheduledHours: number | null;
  workedHours: number | null;
  lateMin: number;
  earlyMin: number;
  absentHours: number | null;
  exceptions: Column[];
  blocking: boolean;
}

export interface Count {
  code: string;
  label: string;
  count: number;
}

export interface ExceptionReport {
  from: ISODate;
  to: ISODate;
  summary: Count[];
  page: ReportPage<ExceptionLine>;
}

export interface ExceptionParams extends Paging, Lang {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  employmentId?: UUID;
  from: ISODate;
  to: ISODate;
  type?: ExceptionType;
}

// ----------------------------------------------------------------------------------- R-03 punch log

export const PUNCH_STATUSES = [
  'PENDING',
  'VALID',
  'QUARANTINED',
  'QUARANTINED_UNMAPPED',
  'QUARANTINED_EXPIRED',
  'QUARANTINED_UNKNOWN_DEVICE',
  'IGNORED',
] as const;

export interface PunchLogRow {
  id: UUID;
  workDateHint: ISODate;
  punchAt: ISOInstant;
  localTs: string;
  tzName: string;
  source: string;
  deviceId: UUID | null;
  deviceCode: string | null;
  deviceName: string | null;
  terminalCode: string | null;
  enrolId: string | null;
  direction: string | null;
  verifyMethod: string | null;
  importJobId: UUID | null;
  importRowNo: number | null;
  receivedAt: ISOInstant;
  employmentId: UUID | null;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  validationStatus: string;
  matchedAt: ISOInstant | null;
}

export interface PunchLine {
  punch: PunchLogRow;
  validationStatusLabel: string;
  sourceLabel: string;
}

export interface PunchLogParams extends Paging, Lang {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  deviceId?: UUID;
  employmentId?: UUID;
  status?: string;
  from: ISODate;
  to: ISODate;
}

/** `DeviceService.DeviceView` (GET /attendance/devices), only what the device filter needs. */
export interface DeviceOption {
  id: UUID;
  code: string;
  name: string | null;
  status: string;
}

// ----------------------------------------------------------------------------------- R-04 / R-05 overtime

/** `ReportFigures.CapUsage` (hours). */
export interface CapUsage {
  regimeCode: string | null;
  capHours: number;
  openingHours: number;
  approvedHours: number;
  pendingHours: number;
  usedHours: number;
  paidHours: number;
  overHours: number;
  overrideHours: number;
  usedPercent: number | null;
  breached: boolean;
}

export interface OvertimeRow {
  employmentId: UUID;
  employeeNo: string | null;
  fullName: string | null;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  month: CapUsage;
  year: CapUsage;
  regimeLabel: string | null;
  regimeNoticeRef: string | null;
  monthByCategory: Record<string, number>;
  yearToDateByCategory: Record<string, number>;
}

export interface OvertimeSummary {
  /** `YearMonth` = `yyyy-MM`. */
  month: string;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  categories: Column[];
  page: ReportPage<OvertimeRow>;
}

export interface OvertimeSummaryParams extends Paging, Lang {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  month: string;
}

export interface OtLedger {
  employmentId: UUID;
  periodKind: string;
  regimeCode: string | null;
  capMin: number;
  openingMin: number;
  approvedMin: number;
  pendingMin: number;
  paidMin: number;
  overrideMin: number;
  regimeNoticeRef: string | null;
}

export interface OverrideRow {
  requestId: UUID;
  employmentId: UUID;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  workDate: ISODate;
  plannedMinutes: number;
  status: string;
  reason: string | null;
  overrideReason: string | null;
  overrideBy: UUID | null;
  decidedBy: UUID | null;
  decidedAt: ISOInstant | null;
  createdBy: UUID | null;
  createdAt: ISOInstant;
  protectedWorker: boolean;
  consentMethod: string | null;
  month: OtLedger | null;
  year: OtLedger | null;
}

export interface OverrideLine {
  request: OverrideRow;
  statusLabel: string;
  month: CapUsage | null;
  year: CapUsage | null;
}

export const OT_REQUEST_STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'] as const;

export interface OverrideParams extends Paging, Lang {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  employmentId?: UUID;
  from: ISODate;
  to: ISODate;
  status?: string;
}

// ----------------------------------------------------------------------------------- R-06 / R-07 leave

/** `ReportFigures.LeaveMovements`: out-flows as positive numbers, `closing` = the balance. */
export interface LeaveMovements {
  opening: number;
  carriedIn: number;
  accrued: number;
  taken: number;
  adjusted: number;
  carriedOut: number;
  expired: number;
  encashed: number;
  closing: number;
}

export interface BalanceRow {
  employmentId: UUID;
  employeeNo: string | null;
  fullName: string | null;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  leaveTypeId: UUID;
  leaveTypeCode: string;
  leaveTypeName: string;
  leaveYear: number;
  balance: LeaveMovements;
}

export interface BalanceReport {
  asOf: ISODate;
  page: ReportPage<BalanceRow>;
}

export interface BalanceParams extends Paging, Lang {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  leaveTypeId?: UUID;
  employmentId?: UUID;
  asOf?: ISODate;
}

export interface TakenLine {
  /** First day of the month. */
  month: ISODate;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  leaveTypeId: UUID;
  leaveTypeCode: string;
  leaveTypeName: string;
  leaveCategory: string | null;
  paidBy: string | null;
  requests: number;
  employees: number;
  days: number | null;
  hours: number | null;
}

export interface SiLine {
  requestId: UUID;
  employmentId: UUID;
  employeeNo: string | null;
  fullName: string | null;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  leaveTypeCode: string;
  leaveTypeName: string;
  leaveCategory: string | null;
  startDate: ISODate;
  endDate: ISODate;
  actualReturnDate: ISODate | null;
  durationDays: number | null;
  certificateType: string | null;
  certificateNoMasked: string | null;
  certificateDate: ISODate | null;
  treatmentType: string | null;
  longTermIllness: boolean | null;
  childBirthDate: ISODate | null;
  childIdMasked: string | null;
  confinementDate: ISODate | null;
  childrenBornCount: number | null;
  caesarean: boolean | null;
  claimDataRecorded: boolean;
}

export interface LeaveRangeParams extends Paging, Lang {
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  leaveTypeId?: UUID;
  from: ISODate;
  to: ISODate;
}

/** `LeaveStore.TypeRow` (GET /attendance/leave-types), only what the type filter needs. */
export interface LeaveTypeOption {
  id: UUID;
  data: { code: string; nameVi: string; nameEn: string | null; status: string };
}

// ----------------------------------------------------------------------------------- R-08 / R-09 periods

export const PERIOD_STATUSES = ['OPEN', 'IN_REVIEW', 'CLOSED', 'LOCKED'] as const;

/** `AttendanceResults.Hold`. */
export interface Hold {
  id: UUID;
  periodId: UUID;
  resultId: UUID;
  resultVersion: number;
  scope: string;
  employmentId: UUID | null;
  payrollRunId: UUID;
  status: string;
  heldAt: ISOInstant;
  releaseReason: string | null;
  releasedAt: ISOInstant | null;
  consumedAt: ISOInstant | null;
}

/** `PeriodService.PeriodView`. */
export interface PeriodView {
  id: UUID;
  legalEntityId: UUID;
  payGroupId: UUID;
  periodStart: ISODate;
  periodEnd: ISODate;
  otPeriodStart: ISODate | null;
  otPeriodEnd: ISODate | null;
  stdDayMethod: string | null;
  stdDayValue: number | null;
  closeDeadline: ISODate | null;
  status: string;
  currentVersion: number;
  lockedVersion: number | null;
  closedAt: ISOInstant | null;
  reopenedAt: ISOInstant | null;
  reopenReason: string | null;
  reopenRequestedAt: ISOInstant | null;
  reopenRequestReason: string | null;
  activeHold: Hold | null;
  version: number;
}

export interface Outstanding {
  code: string;
  label: string;
  count: number;
  waivable: boolean;
}

export interface CloseStatus {
  period: PeriodView;
  statusLabel: string;
  overdue: boolean;
  canClose: boolean | null;
  outstanding: Outstanding[];
  outstandingTotal: number;
  pendingRetro: number;
}

export interface CloseStatusParams extends Paging, Lang {
  legalEntityId?: UUID;
  payGroupId?: UUID;
  status?: string;
  from?: ISODate;
  to?: ISODate;
}

export interface RetroRow {
  lineId: UUID;
  employmentId: UUID;
  legalEntityId: UUID | null;
  orgUnitId: UUID | null;
  categoryCode: string;
  quantity: number;
  unit: string;
  segmentFrom: ISODate;
  segmentTo: ISODate;
  originalPeriodId: UUID | null;
  originalPeriodStart: ISODate | null;
  originalPeriodEnd: ISODate | null;
  originalResultId: UUID | null;
  originalResultVersion: number | null;
  originalSegmentFrom: ISODate | null;
  retroReason: string | null;
  resultId: UUID;
  resultVersion: number;
  resultStatus: string;
  carryingPeriodId: UUID | null;
  carryingPeriodStart: ISODate | null;
  carryingPeriodEnd: ISODate | null;
  createdAt: ISOInstant;
}

export interface RetroTotal {
  originalPeriodId: UUID | null;
  originalPeriodStart: ISODate | null;
  originalPeriodEnd: ISODate | null;
  legalEntityId: UUID | null;
  payGroupId: UUID | null;
  categoryCode: string;
  unit: string;
  quantity: number;
  lines: number;
  employments: number;
}

export interface RetroReport {
  totals: RetroTotal[];
  page: ReportPage<RetroRow>;
}

export interface RetroParams extends Paging {
  legalEntityId?: UUID;
  payGroupId?: UUID;
  originalPeriodId?: UUID;
  from?: ISODate;
  to?: ISODate;
  employmentId?: UUID;
  includeWithdrawn?: boolean;
}

// ----------------------------------------------------------------------------------- integration + jobs

export const INBOX_STATUSES = ['RECEIVED', 'PROCESSED', 'SKIPPED', 'FAILED'] as const;

/** `AttInbox.Message`. */
export interface InboxMessage {
  consumer: string;
  eventId: UUID;
  eventType: string;
  status: string;
  attempts: number;
  lastError: string | null;
  receivedAt: ISOInstant;
  processedAt: ISOInstant | null;
}

export interface InboxParams {
  status?: string;
  consumer?: string;
  limit?: number;
}

/** `LocationProjection.ResyncSummary`. */
export interface ResyncSummary {
  locations: number;
  versions: number;
}

/** `AttendanceJobs.StepResult` / `NightlySummary` (`failed()` is not serialised: derived client-side). */
export interface StepResult {
  step: string;
  count: number;
  failures: string[];
}

export interface NightlySummary {
  day: ISODate;
  steps: StepResult[];
}

/** `AttendanceReminderJobs.Summary`. */
export interface ReminderSummary {
  day: ISODate;
  missingPunch: number;
  carryExpiry: number;
  closeDue: number;
  failures: number;
}

/** Excel exports of R-01 / R-04 / R-06 (`att.report.export`); `unitLabel` is printed as "Đơn vị". */
export type ExportKind = 'timesheet-grid' | 'overtime-summary' | 'leave-balances';

const q = (p: object) => p as QueryParams;

export const reportsApi = {
  timesheetGrid: (p: GridParams) => http.get<GridReport>(`${BASE}/timesheet-grid`, { params: q(p) }),
  dailyExceptions: (p: ExceptionParams) => http.get<ExceptionReport>(`${BASE}/daily-exceptions`, { params: q(p) }),
  punchLog: (p: PunchLogParams) => http.get<ReportPage<PunchLine>>(`${BASE}/punch-log`, { params: q(p) }),
  overtimeSummary: (p: OvertimeSummaryParams) =>
    http.get<OvertimeSummary>(`${BASE}/overtime-summary`, { params: q(p) }),
  overtimeCapOverrides: (p: OverrideParams) =>
    http.get<ReportPage<OverrideLine>>(`${BASE}/overtime-cap-overrides`, { params: q(p) }),
  leaveBalances: (p: BalanceParams) => http.get<BalanceReport>(`${BASE}/leave-balances`, { params: q(p) }),
  leaveTaken: (p: LeaveRangeParams) => http.get<ReportPage<TakenLine>>(`${BASE}/leave-taken`, { params: q(p) }),
  siLeaveRegister: (p: LeaveRangeParams) =>
    http.get<ReportPage<SiLine>>(`${BASE}/si-leave-register`, { params: q(p) }),
  periodCloseStatus: (p: CloseStatusParams) =>
    http.get<ReportPage<CloseStatus>>(`${BASE}/period-close-status`, { params: q(p) }),
  retroAdjustments: (p: RetroParams) => http.get<RetroReport>(`${BASE}/retro-adjustments`, { params: q(p) }),
  /** The export endpoints take the report's filters (no paging) plus `unitLabel`. */
  exportXlsx: (kind: ExportKind, params: Record<string, unknown>) =>
    http.download(`${BASE}/${kind}/export.xlsx`, { params: q(params) }),

  // Lookups of other T&A areas, restated minimally (areas do not import each other).
  periods: (legalEntityId?: UUID) =>
    http.get<PeriodView[]>('/attendance/attendance-periods', { params: { legalEntityId } }),
  devices: () => http.get<DeviceOption[]>('/attendance/devices'),
  leaveTypes: () => http.get<LeaveTypeOption[]>('/attendance/leave-types'),
};

export const integrationApi = {
  inbox: (p: InboxParams) => http.get<InboxMessage[]>('/attendance/integration/inbox', { params: q(p) }),
  resyncLocations: () => http.post<ResyncSummary>('/attendance/integration/locations/resync'),
};

export const jobsApi = {
  runNightly: (today?: ISODate) => http.post<NightlySummary>('/attendance/jobs/nightly/run', today ? { today } : {}),
  runReminders: (today?: ISODate) => http.post<ReminderSummary>('/attendance/reminders/run', today ? { today } : {}),
};
