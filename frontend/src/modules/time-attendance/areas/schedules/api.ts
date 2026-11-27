import { http, type ISODate, type UUID } from '@/shared/api';

/**
 * Shifts, weekly patterns, assignment rules (backend `web.schedule.ScheduleConfigController`) and schedule assignments,
 * planned days, STD_DAYS and the unscheduled list (`web.schedule.ScheduleAssignmentController`); BR-ATT-010/011/014-016,
 * US-ATT-008..014. Times are local `HH:mm[:ss]`; `validTo` is exclusive (Pattern A).
 */

/** `LocalTime` on the wire (`08:00:00`, or `08:00`). */
export type LocalTime = string;
/** BigDecimal on the wire (a JSON number). */
export type Decimal = number;

export type ConfigStatus = 'ACTIVE' | 'INACTIVE';
/** `att.schedule_pattern_day.day_kind`. */
export const DAY_KINDS = ['WORK', 'REST', 'OFF'] as const;
export type DayKind = (typeof DAY_KINDS)[number];
/** `SchedulePatternService.CYCLES` (N-day rotation is Phase 2). */
export const CYCLE_LENGTHS = [7, 14] as const;
/** `att.schedule_assignment.source`. */
export type AssignmentSource = 'RULE' | 'MANUAL' | 'IMPORT' | 'OPENING_LOAD';
export type AssignmentStatus = 'ACTIVE' | 'CANCELLED';
/** `StandardDays.METHODS` (Payroll's pay-group method, BR-PAY-009). */
export const STD_DAY_METHODS = ['FIXED_DAYS', 'SCHEDULE_DAYS', 'CALENDAR_DAYS', 'FIXED_HOURS'] as const;
export type StdDayMethod = (typeof STD_DAY_METHODS)[number];

/** Mirrors the backend code check (upper-cased first): letters, digits, '_' or '-'. */
export const CONFIG_CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/;

// ------------------------------------------------------------------------------------------------ shifts

/** `ShiftPatternStore.BreakData`. */
export interface ShiftBreak {
  seq: number;
  start?: LocalTime | null;
  end?: LocalTime | null;
  durationMin: number;
  paid: boolean;
  flexible: boolean;
}

/** `ShiftService.ShiftView`. */
export interface Shift {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  startTime: LocalTime;
  endTime: LocalTime;
  crossesMidnight: boolean;
  spanMinutes: number;
  workingMinutes: number;
  graceInMin: number;
  graceOutMin: number;
  roundingRuleCode?: string | null;
  punchWindowBeforeMin: number;
  punchWindowAfterMin: number;
  minMinutesHalfDay?: number | null;
  minMinutesFullDay?: number | null;
  stdHours: Decimal;
  dayValue: Decimal;
  nightWindowStart?: LocalTime | null;
  nightWindowEnd?: LocalTime | null;
  shiftGroupCode?: string | null;
  breaks: ShiftBreak[];
  status: ConfigStatus;
  version: number;
}

/** `ShiftService.ShiftResult`: the saved shift plus statutory warnings (`code: detail`). */
export interface ShiftResult {
  shift: Shift;
  warnings: string[];
}

/** `ScheduleConfigController.BreakRequest`. */
export interface BreakRequest {
  start?: LocalTime | null;
  end?: LocalTime | null;
  durationMin?: number | null;
  paid?: boolean | null;
  flexible?: boolean | null;
}

/** `ScheduleConfigController.ShiftRequest` (PUT replaces the shift; the code never changes). */
export interface ShiftRequest {
  code?: string | null;
  nameVi: string;
  nameEn?: string | null;
  startTime: LocalTime;
  endTime: LocalTime;
  graceInMin?: number | null;
  graceOutMin?: number | null;
  roundingRuleCode?: string | null;
  punchWindowBeforeMin?: number | null;
  punchWindowAfterMin?: number | null;
  minMinutesHalfDay?: number | null;
  minMinutesFullDay?: number | null;
  stdHours?: Decimal | null;
  dayValue?: Decimal | null;
  nightWindowStart?: LocalTime | null;
  nightWindowEnd?: LocalTime | null;
  shiftGroupCode?: string | null;
  breaks?: BreakRequest[] | null;
  status?: ConfigStatus | null;
  version?: number | null;
}

// ------------------------------------------------------------------------------------------------ patterns

/** `SchedulePatternService.DayView`; `dayOfWeek` is Java's `DayOfWeek` name. */
export interface PatternDay {
  dayIndex: number;
  dayOfWeek: 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';
  dayKind: DayKind;
  shiftId?: UUID | null;
  shiftCode?: string | null;
  stdHours?: Decimal | null;
  dayValue?: Decimal | null;
}

/** `SchedulePatternService.PatternView`; `weeklyRestDays` are ISO weekdays (1 = Monday). */
export interface SchedulePattern {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  patternType: string;
  cycleLengthDays: number;
  anchorDate: ISODate;
  weeklyRestDays: number[];
  stdHoursWeek: Decimal;
  workDaysWeek: Decimal;
  status: ConfigStatus;
  version: number;
  days: PatternDay[];
}

export interface PatternResult {
  pattern: SchedulePattern;
  warnings: string[];
}

/** `ScheduleConfigController.DayRequest`: a WORK day names `shiftId` or `shiftCode`. */
export interface DayRequest {
  dayIndex: number;
  dayKind: DayKind;
  shiftId?: UUID | null;
  shiftCode?: string | null;
}

/** `ScheduleConfigController.PatternRequest`: anchor = day 0 of the cycle, a Monday. */
export interface PatternRequest {
  code?: string | null;
  nameVi: string;
  nameEn?: string | null;
  cycleLengthDays?: number | null;
  anchorDate: ISODate;
  days: DayRequest[];
  status?: ConfigStatus | null;
  version?: number | null;
}

// ------------------------------------------------------------------------------------------------ rules

/** `ScheduleRuleService.RuleView`: criteria left null match everything; evaluated by `priority`. */
export interface ScheduleRule {
  id: UUID;
  code: string;
  priority: number;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  locationId?: UUID | null;
  jobTitleId?: UUID | null;
  employeeCategoryCode?: string | null;
  workingRegimeCode?: string | null;
  patternId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
  status: ConfigStatus;
  version: number;
}

/** `ScheduleRuleService.RuleResult`: how many assignment lines got a different schedule. */
export interface RuleResult {
  rule: ScheduleRule;
  linesChanged: number;
}

export interface RuleRequest {
  code?: string | null;
  priority?: number | null;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  locationId?: UUID | null;
  jobTitleId?: UUID | null;
  employeeCategoryCode?: string | null;
  workingRegimeCode?: string | null;
  patternId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
  status?: ConfigStatus | null;
  version?: number | null;
}

export interface RegenerateResult {
  linesChanged: number;
}

// ------------------------------------------------------------------------------------------------ assignments

/** `ScheduleAssignmentService.ScheduleAssignmentView`. */
export interface ScheduleAssignment {
  id: UUID;
  employmentId: UUID;
  assignmentKey: UUID;
  patternId: UUID;
  patternCode?: string | null;
  ruleId?: UUID | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
  source: AssignmentSource;
  status: AssignmentStatus;
  version: number;
}

export interface ManualAssignmentRequest {
  assignmentKey: UUID;
  patternId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

/** `HolidayCalendars.Holiday` as embedded in a planned day. */
export interface PlannedHoliday {
  date: ISODate;
  dayType: string;
  holidayCode?: string | null;
  nameVi: string;
  nameEn?: string | null;
  substituteForDate?: ISODate | null;
  paid: boolean;
  foreignersOnly: boolean;
  nationalityCode?: string | null;
  calendarId: UUID;
}

/** `WorkSchedules.PlannedDay` (one per date). */
export interface PlannedDay {
  date: ISODate;
  scheduled: boolean;
  scheduleAssignmentId?: UUID | null;
  assignmentKey?: UUID | null;
  patternId?: UUID | null;
  patternCode?: string | null;
  dayKind?: DayKind | null;
  shiftId?: UUID | null;
  shiftCode?: string | null;
  stdHours?: Decimal | null;
  dayValue?: Decimal | null;
  legalEntityId?: UUID | null;
  locationId?: UUID | null;
  holiday?: PlannedHoliday | null;
  substituteForDate?: ISODate | null;
  /** `isWorkingDay()` (serialised by Jackson when present). */
  workingDay?: boolean;
}

/** `StandardDays.SegmentValue`. */
export interface StdSegment {
  segmentId?: UUID | null;
  from: ISODate;
  to: ISODate;
  stdDays?: Decimal | null;
  stdHours?: Decimal | null;
}

/** `StandardDays.Result`. */
export interface StandardDaysResult {
  method: StdDayMethod;
  methodValue?: Decimal | null;
  periodStdDays?: Decimal | null;
  periodStdHours?: Decimal | null;
  segments: StdSegment[];
}

export interface StandardDaysPreviewParams {
  from: ISODate;
  to: ISODate;
  method: StdDayMethod;
  value?: number | null;
}

/** `ScheduleAssignmentService.Unscheduled` (US-ATT-011). */
export interface UnscheduledLine {
  employmentId: UUID;
  assignmentKey: UUID;
  assignmentType?: string | null;
  orgUnitId?: UUID | null;
  locationId?: UUID | null;
  lineValidFrom: ISODate;
}

// ------------------------------------------------------------------------------------------------ lookups (other areas)

/** Minimal `PeriodService.PeriodView` for the STD_DAYS period picker (GET /attendance/attendance-periods). */
export interface PeriodLookup {
  id: UUID;
  legalEntityId: UUID;
  payGroupId?: UUID | null;
  periodStart: ISODate;
  periodEnd: ISODate;
  stdDayMethod?: string | null;
  stdDayValue?: Decimal | null;
  status: string;
}

/** Minimal Core `JobTitle` / `EmployeeCategory` for the rule criteria. */
export interface JobTitleLookup {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
}

export interface EmployeeCategoryLookup {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  active: boolean;
}

// ------------------------------------------------------------------------------------------------ client

const BASE = '/attendance';

export const shiftsApi = {
  list: () => http.get<Shift[]>(`${BASE}/shifts`),
  get: (id: UUID) => http.get<Shift>(`${BASE}/shifts/${id}`),
  create: (body: ShiftRequest) => http.post<ShiftResult>(`${BASE}/shifts`, body),
  update: (id: UUID, body: ShiftRequest) => http.put<ShiftResult>(`${BASE}/shifts/${id}`, body),
};

export const patternsApi = {
  list: () => http.get<SchedulePattern[]>(`${BASE}/schedule-patterns`),
  get: (id: UUID) => http.get<SchedulePattern>(`${BASE}/schedule-patterns/${id}`),
  create: (body: PatternRequest) => http.post<PatternResult>(`${BASE}/schedule-patterns`, body),
  update: (id: UUID, body: PatternRequest) => http.put<PatternResult>(`${BASE}/schedule-patterns/${id}`, body),
};

export const rulesApi = {
  list: () => http.get<ScheduleRule[]>(`${BASE}/schedule-rules`),
  get: (id: UUID) => http.get<ScheduleRule>(`${BASE}/schedule-rules/${id}`),
  create: (body: RuleRequest) => http.post<RuleResult>(`${BASE}/schedule-rules`, body),
  update: (id: UUID, body: RuleRequest) => http.put<RuleResult>(`${BASE}/schedule-rules/${id}`, body),
  regenerate: (legalEntityId?: UUID | null) =>
    http.post<RegenerateResult>(`${BASE}/schedule-rules/regenerate`, { legalEntityId: legalEntityId ?? null }),
};

export const assignmentsApi = {
  list: (employmentId: UUID, includeCancelled = false) =>
    http.get<ScheduleAssignment[]>(`${BASE}/employments/${employmentId}/schedule-assignments`, { params: { includeCancelled } }),
  create: (employmentId: UUID, body: ManualAssignmentRequest) =>
    http.post<ScheduleAssignment>(`${BASE}/employments/${employmentId}/schedule-assignments`, body),
  cancel: (employmentId: UUID, id: UUID) =>
    http.post<ScheduleAssignment>(`${BASE}/employments/${employmentId}/schedule-assignments/${id}/cancel`),
  regenerate: (employmentId: UUID) =>
    http.post<ScheduleAssignment[]>(`${BASE}/employments/${employmentId}/schedule-assignments/regenerate`),
  workPlan: (employmentId: UUID, from: ISODate, to: ISODate) =>
    http.get<PlannedDay[]>(`${BASE}/employments/${employmentId}/work-plan`, { params: { from, to } }),
  standardDays: (employmentId: UUID, periodId: UUID) =>
    http.get<StandardDaysResult>(`${BASE}/employments/${employmentId}/standard-days`, { params: { periodId } }),
  standardDaysPreview: (employmentId: UUID, p: StandardDaysPreviewParams) =>
    http.get<StandardDaysResult>(`${BASE}/employments/${employmentId}/standard-days/preview`, { params: { ...p } }),
  unscheduled: (legalEntityId: UUID, date: ISODate) =>
    http.get<UnscheduledLine[]>(`${BASE}/legal-entities/${legalEntityId}/unscheduled-employees`, { params: { date } }),
};

export const scheduleLookupApi = {
  periods: (legalEntityId?: UUID) => http.get<PeriodLookup[]>(`${BASE}/attendance-periods`, { params: { legalEntityId } }),
  jobTitles: () => http.get<JobTitleLookup[]>('/core/job-titles'),
  employeeCategories: () => http.get<EmployeeCategoryLookup[]>('/core/employee-categories'),
};
