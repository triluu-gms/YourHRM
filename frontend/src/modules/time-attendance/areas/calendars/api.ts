import { http, type ISODate, type UUID } from '@/shared/api';

/**
 * Holiday calendars (backend `web.holiday.HolidayCalendarController`, `HolidayCalendarService` records; BR-ATT-001/002/
 * 005/006, US-ATT-001..007). Assignment `validTo` is exclusive (Pattern A).
 */

// ------------------------------------------------------------------------------------------------ codes

/** `HolidayDateRules.PHASE1_DAY_TYPES`; SWAP_OFF / SWAP_WORK are Phase 2 (BR-ATT-004) and refused by the backend. */
export const DAY_TYPES = ['PUBLIC', 'SUBSTITUTE', 'COMPANY'] as const;
export type DayType = (typeof DAY_TYPES)[number] | 'SWAP_OFF' | 'SWAP_WORK';
export const ELIGIBILITIES = ['ALL', 'FOREIGNERS'] as const;
export type Eligibility = (typeof ELIGIBILITIES)[number];
/** `att.holiday_calendar.source`. */
export type CalendarSource = 'PLATFORM_TEMPLATE' | 'TENANT_COPY' | 'CUSTOM';
export type CalendarStatus = 'ACTIVE' | 'INACTIVE';
export type DateStatus = 'ACTIVE' | 'CANCELLED';
/** `HolidayCalendarService.DiffKind`. */
export type DiffKind = 'ADDED' | 'CHANGED' | 'REMOVED' | 'OVERRIDDEN';

/** Mirrors `HolidayDateRules.CALENDAR_CODE` (the backend upper-cases first). */
export const CALENDAR_CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/;
/** Mirrors `HolidayDateRules.HOLIDAY_CODE` (the backend upper-cases first). */
export const HOLIDAY_CODE_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,39}$/;

// ------------------------------------------------------------------------------------------------ records

/** `HolidayCalendarService.AssignmentView`. */
export interface CalendarAssignment {
  id: UUID;
  calendarId: UUID;
  legalEntityId: UUID;
  locationId?: UUID | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
  version: number;
}

/** `HolidayCalendarService.CalendarView`. */
export interface HolidayCalendar {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  countryCode: string;
  source: CalendarSource;
  templateCalendarId?: UUID | null;
  templateYear?: number | null;
  status: CalendarStatus;
  version: number;
  assignments: CalendarAssignment[];
}

/** `HolidayCalendarService.TemplateView`. */
export interface HolidayTemplate {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  countryCode: string;
  years: number[];
}

/** `HolidayCalendarStore.DateEntry` (template side of a diff). */
export interface DateEntry {
  holidayDate: ISODate;
  dayType: DayType;
  holidayCode?: string | null;
  nameVi: string;
  nameEn?: string | null;
  lunarMonth?: number | null;
  lunarDay?: number | null;
  lunarLeapMonth: boolean;
  substituteForDate?: ISODate | null;
  paid: boolean;
  eligibility: Eligibility;
  nationalityCode?: string | null;
  legalRef?: string | null;
}

/** `HolidayCalendarService.HolidayDateView`. */
export interface HolidayDate extends DateEntry {
  id: UUID;
  calendarId: UUID;
  status: DateStatus;
  version: number;
}

/** `HolidayCalendarService.DiffItem`: `key` = `date|dayType|eligibility|nationality`. */
export interface DiffItem {
  key: string;
  kind: DiffKind;
  template?: DateEntry | null;
  current?: HolidayDate | null;
}

/** `HolidayCalendarService.ApplyResult`. */
export interface ApplyResult {
  year: number;
  applied: DiffItem[];
}

/** `HolidayCalendars.Holiday` (resolved holidays of a legal entity, ATT-P-16). */
export interface ResolvedHoliday {
  date: ISODate;
  dayType: DayType;
  holidayCode?: string | null;
  nameVi: string;
  nameEn?: string | null;
  substituteForDate?: ISODate | null;
  paid: boolean;
  foreignersOnly: boolean;
  nationalityCode?: string | null;
  calendarId: UUID;
}

// ------------------------------------------------------------------------------------------------ requests

/** `CreateCalendarRequest`: CUSTOM, or (with `templateId`) a TENANT_COPY filled for `year`. */
export interface CreateCalendarRequest {
  code: string;
  nameVi?: string | null;
  nameEn?: string | null;
  templateId?: UUID | null;
  year?: number | null;
}

/** `UpdateCalendarRequest`. */
export interface UpdateCalendarRequest {
  nameVi?: string | null;
  nameEn?: string | null;
  status?: CalendarStatus | null;
  version: number;
}

/** `DateRequest`: `version` + `reason` are required on PUT. */
export interface DateRequest {
  holidayDate: ISODate;
  dayType: DayType;
  holidayCode?: string | null;
  nameVi: string;
  nameEn?: string | null;
  lunarMonth?: number | null;
  lunarDay?: number | null;
  lunarLeapMonth?: boolean | null;
  substituteForDate?: ISODate | null;
  paid?: boolean | null;
  eligibility?: Eligibility | null;
  nationalityCode?: string | null;
  legalRef?: string | null;
  version?: number | null;
  reason?: string | null;
}

export interface CancelDateRequest {
  version: number;
  reason: string;
}

/** Without `acceptKeys`: every ADDED / REMOVED difference; CHANGED and OVERRIDDEN only when named. */
export interface ApplyTemplateRequest {
  year: number;
  acceptKeys?: string[] | null;
  reason?: string | null;
}

export interface AssignRequest {
  legalEntityId: UUID;
  locationId?: UUID | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

export interface EndAssignmentRequest {
  validTo?: ISODate | null;
  version: number;
}

export interface ResolvedHolidaysParams {
  from: ISODate;
  to: ISODate;
  locationId?: UUID;
  nationality?: string;
}

// ------------------------------------------------------------------------------------------------ client

const BASE = '/attendance';

export const calendarsApi = {
  list: () => http.get<HolidayCalendar[]>(`${BASE}/holiday-calendars`),
  get: (id: UUID) => http.get<HolidayCalendar>(`${BASE}/holiday-calendars/${id}`),
  create: (body: CreateCalendarRequest) => http.post<HolidayCalendar>(`${BASE}/holiday-calendars`, body),
  update: (id: UUID, body: UpdateCalendarRequest) => http.put<HolidayCalendar>(`${BASE}/holiday-calendars/${id}`, body),

  dates: (id: UUID, year: number, includeCancelled = false) =>
    http.get<HolidayDate[]>(`${BASE}/holiday-calendars/${id}/dates`, { params: { year, includeCancelled } }),
  addDate: (id: UUID, body: DateRequest) => http.post<HolidayDate>(`${BASE}/holiday-calendars/${id}/dates`, body),
  updateDate: (id: UUID, dateId: UUID, body: DateRequest) =>
    http.put<HolidayDate>(`${BASE}/holiday-calendars/${id}/dates/${dateId}`, body),
  cancelDate: (id: UUID, dateId: UUID, body: CancelDateRequest) =>
    http.post<HolidayDate>(`${BASE}/holiday-calendars/${id}/dates/${dateId}/cancel`, body),

  templates: () => http.get<HolidayTemplate[]>(`${BASE}/holiday-calendar-templates`),
  templateDates: (templateId: UUID, year: number) =>
    http.get<HolidayDate[]>(`${BASE}/holiday-calendar-templates/${templateId}/dates`, { params: { year } }),
  templateDiff: (id: UUID, year: number) =>
    http.get<DiffItem[]>(`${BASE}/holiday-calendars/${id}/template-diff`, { params: { year } }),
  applyTemplate: (id: UUID, body: ApplyTemplateRequest) =>
    http.post<ApplyResult>(`${BASE}/holiday-calendars/${id}/template-apply`, body),

  assign: (id: UUID, body: AssignRequest) =>
    http.post<CalendarAssignment>(`${BASE}/holiday-calendars/${id}/assignments`, body),
  endAssignment: (id: UUID, assignmentId: UUID, body: EndAssignmentRequest) =>
    http.put<CalendarAssignment>(`${BASE}/holiday-calendars/${id}/assignments/${assignmentId}`, body),
  assignmentsOfLegalEntity: (legalEntityId: UUID) =>
    http.get<CalendarAssignment[]>(`${BASE}/legal-entities/${legalEntityId}/holiday-calendar-assignments`),
  resolvedHolidays: (legalEntityId: UUID, p: ResolvedHolidaysParams) =>
    http.get<ResolvedHoliday[]>(`${BASE}/legal-entities/${legalEntityId}/holidays`, { params: { ...p } }),
};
