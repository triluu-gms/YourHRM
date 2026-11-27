import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import {
  calendarsApi,
  type ApplyTemplateRequest,
  type AssignRequest,
  type CancelDateRequest,
  type CreateCalendarRequest,
  type DateRequest,
  type EndAssignmentRequest,
  type ResolvedHolidaysParams,
  type UpdateCalendarRequest,
} from './api';

/** Everything of this area hangs under `['att', 'calendars']` so one invalidation refreshes every view. */
export const calendarKeys = {
  all: ['att', 'calendars'] as const,
  list: () => ['att', 'calendars', 'list'] as const,
  detail: (id: UUID) => ['att', 'calendars', 'detail', id] as const,
  dates: (id: UUID, year: number, includeCancelled: boolean) => ['att', 'calendars', 'detail', id, 'dates', year, includeCancelled] as const,
  diff: (id: UUID, year: number) => ['att', 'calendars', 'detail', id, 'diff', year] as const,
  templates: () => ['att', 'calendars', 'templates'] as const,
  templateDates: (id: UUID, year: number) => ['att', 'calendars', 'templates', id, year] as const,
  leAssignments: (le: UUID) => ['att', 'calendars', 'le', le, 'assignments'] as const,
  resolved: (le: UUID, p: ResolvedHolidaysParams) => ['att', 'calendars', 'le', le, 'holidays', p] as const,
};

function useInvalidate() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: calendarKeys.all });
}

export const useHolidayCalendars = () => useQuery({ queryKey: calendarKeys.list(), queryFn: calendarsApi.list });

export const useHolidayCalendar = (id: UUID) =>
  useQuery({ queryKey: calendarKeys.detail(id), queryFn: () => calendarsApi.get(id) });

export const useHolidayDates = (id: UUID, year: number, includeCancelled: boolean) =>
  useQuery({ queryKey: calendarKeys.dates(id, year, includeCancelled), queryFn: () => calendarsApi.dates(id, year, includeCancelled) });

export const useTemplateDiff = (id: UUID, year: number, enabled: boolean) =>
  useQuery({ queryKey: calendarKeys.diff(id, year), queryFn: () => calendarsApi.templateDiff(id, year), enabled });

export const useHolidayTemplates = () =>
  useQuery({ queryKey: calendarKeys.templates(), queryFn: calendarsApi.templates, staleTime: 5 * 60_000 });

export const useTemplateDates = (templateId: UUID | undefined, year: number | undefined) =>
  useQuery({
    queryKey: calendarKeys.templateDates(templateId ?? '', year ?? 0),
    queryFn: () => calendarsApi.templateDates(templateId as UUID, year as number),
    enabled: !!templateId && !!year,
  });

export const useLegalEntityCalendarAssignments = (legalEntityId: UUID | undefined) =>
  useQuery({
    queryKey: calendarKeys.leAssignments(legalEntityId ?? ''),
    queryFn: () => calendarsApi.assignmentsOfLegalEntity(legalEntityId as UUID),
    enabled: !!legalEntityId,
  });

export const useResolvedHolidays = (legalEntityId: UUID | undefined, p: ResolvedHolidaysParams) =>
  useQuery({
    queryKey: calendarKeys.resolved(legalEntityId ?? '', p),
    queryFn: () => calendarsApi.resolvedHolidays(legalEntityId as UUID, p),
    enabled: !!legalEntityId,
  });

export function useCreateCalendar() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: CreateCalendarRequest) => calendarsApi.create(body), onSuccess: invalidate });
}

export function useUpdateCalendar(id: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: UpdateCalendarRequest) => calendarsApi.update(id, body), onSuccess: invalidate });
}

export function useSaveDate(calendarId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ dateId, body }: { dateId?: UUID; body: DateRequest }) =>
      dateId ? calendarsApi.updateDate(calendarId, dateId, body) : calendarsApi.addDate(calendarId, body),
    onSuccess: invalidate,
  });
}

export function useCancelDate(calendarId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ dateId, body }: { dateId: UUID; body: CancelDateRequest }) => calendarsApi.cancelDate(calendarId, dateId, body),
    onSuccess: invalidate,
  });
}

export function useApplyTemplate(calendarId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: ApplyTemplateRequest) => calendarsApi.applyTemplate(calendarId, body), onSuccess: invalidate });
}

export function useAssignCalendar(calendarId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: AssignRequest) => calendarsApi.assign(calendarId, body), onSuccess: invalidate });
}

export function useEndCalendarAssignment(calendarId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ assignmentId, body }: { assignmentId: UUID; body: EndAssignmentRequest }) =>
      calendarsApi.endAssignment(calendarId, assignmentId, body),
    onSuccess: invalidate,
  });
}
