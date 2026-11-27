import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import {
  assignmentsApi,
  patternsApi,
  rulesApi,
  scheduleLookupApi,
  shiftsApi,
  type ManualAssignmentRequest,
  type PatternRequest,
  type RuleRequest,
  type ShiftRequest,
  type StandardDaysPreviewParams,
} from './api';

/** Everything of this area hangs under `['att', 'schedules']`. */
export const scheduleKeys = {
  all: ['att', 'schedules'] as const,
  shifts: () => ['att', 'schedules', 'shifts'] as const,
  patterns: () => ['att', 'schedules', 'patterns'] as const,
  rules: () => ['att', 'schedules', 'rules'] as const,
  employment: (id: UUID) => ['att', 'schedules', 'employment', id] as const,
  assignments: (id: UUID, includeCancelled: boolean) => ['att', 'schedules', 'employment', id, 'assignments', includeCancelled] as const,
  plan: (id: UUID, from: ISODate, to: ISODate) => ['att', 'schedules', 'employment', id, 'plan', from, to] as const,
  stdDays: (id: UUID, periodId: UUID) => ['att', 'schedules', 'employment', id, 'std', periodId] as const,
  stdPreview: (id: UUID, p: StandardDaysPreviewParams) => ['att', 'schedules', 'employment', id, 'std-preview', p] as const,
  unscheduled: (le: UUID, date: ISODate) => ['att', 'schedules', 'unscheduled', le, date] as const,
  periods: (le?: UUID) => ['att', 'schedules', 'lookup', 'periods', le ?? null] as const,
  jobTitles: () => ['att', 'schedules', 'lookup', 'job-titles'] as const,
  categories: () => ['att', 'schedules', 'lookup', 'employee-categories'] as const,
};

function useInvalidate() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: scheduleKeys.all });
}

const LONG = 10 * 60_000;

export const useShifts = () => useQuery({ queryKey: scheduleKeys.shifts(), queryFn: shiftsApi.list });
export const usePatterns = () => useQuery({ queryKey: scheduleKeys.patterns(), queryFn: patternsApi.list });
export const useRules = () => useQuery({ queryKey: scheduleKeys.rules(), queryFn: rulesApi.list });

export const useJobTitles = () => useQuery({ queryKey: scheduleKeys.jobTitles(), queryFn: scheduleLookupApi.jobTitles, staleTime: LONG });
export const useEmployeeCategories = () =>
  useQuery({ queryKey: scheduleKeys.categories(), queryFn: scheduleLookupApi.employeeCategories, staleTime: LONG });
export const usePeriods = (legalEntityId: UUID | undefined, enabled: boolean) =>
  useQuery({ queryKey: scheduleKeys.periods(legalEntityId), queryFn: () => scheduleLookupApi.periods(legalEntityId), enabled });

export function useSaveShift() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, body }: { id?: UUID; body: ShiftRequest }) => (id ? shiftsApi.update(id, body) : shiftsApi.create(body)),
    onSuccess: invalidate,
  });
}

export function useSavePattern() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, body }: { id?: UUID; body: PatternRequest }) => (id ? patternsApi.update(id, body) : patternsApi.create(body)),
    onSuccess: invalidate,
  });
}

export function useSaveRule() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, body }: { id?: UUID; body: RuleRequest }) => (id ? rulesApi.update(id, body) : rulesApi.create(body)),
    onSuccess: invalidate,
  });
}

export function useRegenerateRules() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (legalEntityId?: UUID | null) => rulesApi.regenerate(legalEntityId), onSuccess: invalidate });
}

export const useScheduleAssignments = (employmentId: UUID | undefined, includeCancelled: boolean) =>
  useQuery({
    queryKey: scheduleKeys.assignments(employmentId ?? '', includeCancelled),
    queryFn: () => assignmentsApi.list(employmentId as UUID, includeCancelled),
    enabled: !!employmentId,
  });

export const useWorkPlan = (employmentId: UUID | undefined, from: ISODate, to: ISODate) =>
  useQuery({
    queryKey: scheduleKeys.plan(employmentId ?? '', from, to),
    queryFn: () => assignmentsApi.workPlan(employmentId as UUID, from, to),
    enabled: !!employmentId,
  });

export const useStandardDays = (employmentId: UUID | undefined, periodId: UUID | undefined) =>
  useQuery({
    queryKey: scheduleKeys.stdDays(employmentId ?? '', periodId ?? ''),
    queryFn: () => assignmentsApi.standardDays(employmentId as UUID, periodId as UUID),
    enabled: !!employmentId && !!periodId,
  });

export const useStandardDaysPreview = (employmentId: UUID | undefined, p: StandardDaysPreviewParams | null) =>
  useQuery({
    queryKey: scheduleKeys.stdPreview(employmentId ?? '', p ?? { from: '', to: '', method: 'SCHEDULE_DAYS' }),
    queryFn: () => assignmentsApi.standardDaysPreview(employmentId as UUID, p as StandardDaysPreviewParams),
    enabled: !!employmentId && !!p,
  });

export const useUnscheduled = (legalEntityId: UUID | undefined, date: ISODate) =>
  useQuery({
    queryKey: scheduleKeys.unscheduled(legalEntityId ?? '', date),
    queryFn: () => assignmentsApi.unscheduled(legalEntityId as UUID, date),
    enabled: !!legalEntityId,
  });

export function useAssignmentActions(employmentId: UUID) {
  const invalidate = useInvalidate();
  return {
    create: useMutation({ mutationFn: (body: ManualAssignmentRequest) => assignmentsApi.create(employmentId, body), onSuccess: invalidate }),
    cancel: useMutation({ mutationFn: (id: UUID) => assignmentsApi.cancel(employmentId, id), onSuccess: invalidate }),
    regenerate: useMutation({ mutationFn: () => assignmentsApi.regenerate(employmentId), onSuccess: invalidate }),
  };
}
