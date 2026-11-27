import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import {
  codesApi,
  menstrualBreakApi,
  punchesApi,
  timesheetsApi,
  type CodeRequest,
  type CorrectionRequest,
  type EditRequest,
  type GridQuery,
  type RecomputeRequest,
} from './api';

/** Everything of this area hangs under `['att', 'timesheets']` (the devices area refreshes it after re-matching). */
export const timesheetKeys = {
  all: ['att', 'timesheets'] as const,
  codes: ['att', 'timesheets', 'codes'] as const,
  grid: (q: GridQuery) => ['att', 'timesheets', 'grid', q] as const,
  employment: (id: UUID) => ['att', 'timesheets', 'employment', id] as const,
  days: (id: UUID, from: ISODate, to: ISODate) => ['att', 'timesheets', 'employment', id, 'days', from, to] as const,
  day: (id: UUID, date: ISODate) => ['att', 'timesheets', 'employment', id, 'day', date] as const,
  punches: (id: UUID, from: ISODate, to: ISODate) => ['att', 'timesheets', 'employment', id, 'punches', from, to] as const,
  menstrual: (id: UUID, month: string) => ['att', 'timesheets', 'employment', id, 'menstrual', month] as const,
};

function useInvalidate() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: timesheetKeys.all });
  };
}

// ------------------------------------------------------------------------------------------------ codes

export const useAttendanceCodes = () => useQuery({ queryKey: timesheetKeys.codes, queryFn: codesApi.list, staleTime: 60_000 });

export function useSaveCode() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, body }: { id?: UUID; body: CodeRequest }) => (id ? codesApi.update(id, body) : codesApi.create(body)),
    onSuccess: invalidate,
  });
}

// ------------------------------------------------------------------------------------------------ days

export const useTimesheetGrid = (q: GridQuery) =>
  useQuery({ queryKey: timesheetKeys.grid(q), queryFn: () => timesheetsApi.grid(q), placeholderData: keepPreviousData });

export const useEmploymentDays = (id: UUID | undefined, from: ISODate, to: ISODate) =>
  useQuery({
    queryKey: timesheetKeys.days(id ?? '', from, to),
    queryFn: () => timesheetsApi.ofEmployment(id as UUID, from, to),
    enabled: !!id,
  });

export const useDayDetail = (id: UUID | undefined, date: ISODate | undefined) =>
  useQuery({
    queryKey: timesheetKeys.day(id ?? '', date ?? ''),
    queryFn: () => timesheetsApi.day(id as UUID, date as ISODate),
    enabled: !!id && !!date,
  });

export const useEmploymentPunches = (id: UUID | undefined, from: ISODate, to: ISODate, enabled = true) =>
  useQuery({
    queryKey: timesheetKeys.punches(id ?? '', from, to),
    queryFn: () => punchesApi.ofEmployment(id as UUID, from, to),
    enabled: enabled && !!id,
  });

export function useRecompute() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: RecomputeRequest) => timesheetsApi.recompute(body), onSuccess: invalidate });
}

export function useEditDays() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: EditRequest) => timesheetsApi.edit(body), onSuccess: invalidate });
}

export function useRequestCorrection() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ employmentId, body }: { employmentId: UUID; body: CorrectionRequest }) =>
      timesheetsApi.requestCorrection(employmentId, body),
    onSuccess: invalidate,
  });
}

// ------------------------------------------------------------------------------------------------ menstrual break

export const useMenstrualBreaks = (id: UUID | undefined, month: string, enabled = true) =>
  useQuery({
    queryKey: timesheetKeys.menstrual(id ?? '', month),
    queryFn: () => menstrualBreakApi.month(id as UUID, month),
    enabled: enabled && !!id,
  });

export function useDeclareMenstrualBreak() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ employmentId, workDate }: { employmentId: UUID; workDate: ISODate }) =>
      menstrualBreakApi.declare(employmentId, workDate),
    onSuccess: invalidate,
  });
}

export function useWithdrawMenstrualBreak() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ employmentId, workDate }: { employmentId: UUID; workDate: ISODate }) =>
      menstrualBreakApi.withdraw(employmentId, workDate),
    onSuccess: invalidate,
  });
}
