import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

/**
 * T&A self-service (BR-ATT-080): `AttendanceEssController` + `AttendanceEssService` records, `WorkSchedules.PlannedDay`,
 * `OvertimeService.CapView`; the leave request of `LeaveController` (`LeaveRequestBody`, `LeaveRequestService.Preview`,
 * `LeaveStore.RequestRow`). The employee is always the caller; `employmentId` picks one of their own employments.
 * Ranges are at most 62 days (`range-too-long`).
 */

export interface MyEmployment {
  employmentId: UUID;
  legalEntityId: UUID;
  hireDate: ISODate | null;
  terminationDate: ISODate | null;
  status: string;
  activeToday: boolean;
}

export interface CodeQuantity {
  code: string;
  label: string;
  quantity: number;
}

export interface MyException {
  code: string;
  label: string;
  blocking: boolean;
}

export interface MyDay {
  workDate: ISODate;
  dayType: string | null;
  dayTypeLabel: string | null;
  dayStatus: string;
  dayStatusLabel: string;
  primaryCode: string | null;
  primaryCodeLabel: string | null;
  codes: CodeQuantity[];
  firstInAt: ISOInstant | null;
  lastOutAt: ISOInstant | null;
  scheduledHours: number | null;
  workedHours: number | null;
  lateMin: number;
  earlyMin: number;
  absentHours: number | null;
  nightHours: number | null;
  dayValue: number | null;
  exceptions: MyException[];
  manualOverride: boolean;
}

export interface MyPunch {
  punchAt: ISOInstant;
  /** `LocalDateTime` (`yyyy-MM-ddTHH:mm[:ss]`) in the device's time zone. */
  localTime: string;
  direction: string | null;
  terminalCode: string | null;
  status: string;
}

export interface MyCorrection {
  id: UUID;
  workDate: ISODate;
  correctionType: string;
  requestedInAt: ISOInstant | null;
  requestedOutAt: ISOInstant | null;
  requestedCode: string | null;
  source: string;
  status: string;
  statusLabel: string;
  createdAt: ISOInstant;
}

export interface MyDayDetail {
  day: MyDay;
  punches: MyPunch[];
  corrections: MyCorrection[];
}

export interface MyBalance {
  leaveTypeId: UUID;
  leaveTypeCode: string;
  leaveTypeLabel: string;
  leaveYear: number;
  yearStart: ISODate;
  yearEnd: ISODate;
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
}

export interface MyLedgerEntry {
  id: UUID;
  leaveTypeCode: string;
  leaveTypeLabel: string;
  leaveYear: number;
  txnType: string;
  quantity: number;
  unit: string;
  txnDate: ISODate;
  expiresOn: ISODate | null;
  leaveRequestId: UUID | null;
  reason: string | null;
}

/** `OvertimeService.CapView` (minutes); `level` OK | WARN | EXCEEDED. */
export interface CapView {
  kind: string;
  periodStart: ISODate;
  regime: string | null;
  capMin: number;
  openingMin: number;
  approvedMin: number;
  pendingMin: number;
  paidMin: number;
  overrideMin: number;
  usedMin: number;
  level: 'OK' | 'WARN' | 'EXCEEDED' | string;
}

/** `kind` LEAVE | OVERTIME | CORRECTION. */
export interface MyRequest {
  kind: string;
  kindLabel: string;
  id: UUID;
  fromDate: ISODate;
  toDate: ISODate;
  subject: string | null;
  subjectLabel: string | null;
  quantity: number | null;
  unit: string | null;
  status: string;
  statusLabel: string;
  workflowInstanceId: UUID | null;
  decidedAt: ISOInstant | null;
}

/** `HolidayCalendars.Holiday`. */
export interface Holiday {
  date: ISODate;
  dayType: string;
  holidayCode: string | null;
  nameVi: string;
  nameEn: string | null;
  substituteForDate: ISODate | null;
  paid: boolean;
  foreignersOnly: boolean;
  nationalityCode: string | null;
  calendarId: UUID;
}

/** `WorkSchedules.PlannedDay`. */
export interface PlannedDay {
  date: ISODate;
  scheduled: boolean;
  scheduleAssignmentId: UUID | null;
  assignmentKey: UUID | null;
  patternId: UUID | null;
  patternCode: string | null;
  dayKind: string | null;
  shiftId: UUID | null;
  shiftCode: string | null;
  stdHours: number | null;
  dayValue: number | null;
  legalEntityId: UUID | null;
  locationId: UUID | null;
  holiday: Holiday | null;
  substituteForDate: ISODate | null;
}

// ------------------------------------------------------------------------------------------- leave request

export type DayPart = 'FULL' | 'AM' | 'PM';

/** `LeaveController.LeaveRequestBody` (SI claim data and attachments are not offered in ESS here). */
export interface LeaveRequestBody {
  leaveTypeId?: UUID;
  leaveTypeCode?: string;
  startDate: ISODate;
  endDate?: ISODate;
  startPart?: DayPart;
  endPart?: DayPart;
  startTime?: string;
  endTime?: string;
  reason?: string;
  attachmentDocumentIds?: UUID[];
}

export interface CountedDay {
  date: ISODate;
  quantity: number;
  part: string | null;
}

/** `LeaveRequestService.Preview`. */
export interface LeavePreview {
  leaveTypeCode: string;
  requestUnit: string;
  durationDays: number | null;
  durationHours: number | null;
  days: CountedDay[];
  daysPerLeaveYear: Record<string, number>;
  available: Record<string, number>;
  warnings: string[];
}

/** `LeaveStore.RequestRow` (fields the ESS confirmation needs). */
export interface LeaveRequestRow {
  id: UUID;
  employmentId: UUID;
  leaveTypeId: UUID;
  leaveTypeCode: string;
  startDate: ISODate;
  endDate: ISODate;
  durationDays: number | null;
  durationHours: number | null;
  status: string;
  workflowInstanceId: UUID | null;
  createdAt: ISOInstant;
}

interface Who {
  employmentId?: UUID;
}
interface Range extends Who {
  from: ISODate;
  to: ISODate;
}
interface Lang {
  lang?: string;
}

const ME = '/attendance/me';

export const essApi = {
  employments: () => http.get<MyEmployment[]>(`${ME}/employments`),
  schedule: (p: Range) => http.get<PlannedDay[]>(`${ME}/schedule`, { params: { ...p } }),
  punches: (p: Range) => http.get<MyPunch[]>(`${ME}/punches`, { params: { ...p } }),
  timesheets: (p: Range & Lang) => http.get<MyDay[]>(`${ME}/timesheets`, { params: { ...p } }),
  day: (date: ISODate, p: Who & Lang) => http.get<MyDayDetail>(`${ME}/timesheets/${date}`, { params: { ...p } }),
  exceptions: (p: Range & Lang) => http.get<MyDay[]>(`${ME}/exceptions`, { params: { ...p } }),
  balances: (p: Who & Lang & { asOf?: ISODate }) => http.get<MyBalance[]>(`${ME}/leave-balances`, { params: { ...p } }),
  ledger: (p: Who & Lang & { leaveTypeId?: UUID; leaveYear?: number }) =>
    http.get<MyLedgerEntry[]>(`${ME}/leave-ledger`, { params: { ...p } }),
  overtimeCaps: (p: Who & { date?: ISODate }) => http.get<CapView[]>(`${ME}/overtime-caps`, { params: { ...p } }),
  requests: (p: Range & Lang) => http.get<MyRequest[]>(`${ME}/requests`, { params: { ...p } }),

  previewLeave: (employmentId: UUID, body: LeaveRequestBody) =>
    http.post<LeavePreview>(`/attendance/employments/${employmentId}/leave-requests/preview`, body),
  requestLeave: (employmentId: UUID, body: LeaveRequestBody) =>
    http.post<LeaveRequestRow>(`/attendance/employments/${employmentId}/leave-requests`, body),
};
