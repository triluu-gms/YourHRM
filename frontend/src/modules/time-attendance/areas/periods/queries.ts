import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import { periodsApi, type CloseRequest, type PeriodFilter } from './api';

/** Everything of this area hangs under `['att', 'periods']` so one invalidation refreshes every view. */
export const periodKeys = {
  all: ['att', 'periods'] as const,
  list: (f: PeriodFilter) => ['att', 'periods', 'list', f] as const,
  period: (id: UUID) => ['att', 'periods', 'period', id] as const,
  checklist: (id: UUID, employmentId?: UUID) => ['att', 'periods', 'period', id, 'checklist', employmentId ?? null] as const,
  results: (id: UUID) => ['att', 'periods', 'period', id, 'results'] as const,
  holds: (id: UUID) => ['att', 'periods', 'period', id, 'holds'] as const,
  retro: (id: UUID) => ['att', 'periods', 'period', id, 'retro'] as const,
  result: (id: UUID) => ['att', 'periods', 'result', id] as const,
  lines: (id: UUID, employmentId?: UUID) => ['att', 'periods', 'result', id, 'lines', employmentId ?? null] as const,
  importJob: (jobId: UUID) => ['att', 'periods', 'import', jobId] as const,
};

function useInvalidate() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: periodKeys.all });
}

export const usePeriods = (f: PeriodFilter) => useQuery({ queryKey: periodKeys.list(f), queryFn: () => periodsApi.list(f) });
export const usePeriod = (id: UUID) => useQuery({ queryKey: periodKeys.period(id), queryFn: () => periodsApi.get(id) });
export const useChecklist = (id: UUID, employmentId?: UUID, enabled = true) =>
  useQuery({ queryKey: periodKeys.checklist(id, employmentId), queryFn: () => periodsApi.checklist(id, employmentId), enabled });
export const usePeriodResults = (id: UUID) => useQuery({ queryKey: periodKeys.results(id), queryFn: () => periodsApi.results(id) });
export const usePeriodHolds = (id: UUID) => useQuery({ queryKey: periodKeys.holds(id), queryFn: () => periodsApi.holds(id) });
export const useRetroRequests = (id: UUID) => useQuery({ queryKey: periodKeys.retro(id), queryFn: () => periodsApi.retroRequests(id) });
export const useAttendanceResult = (id: UUID) => useQuery({ queryKey: periodKeys.result(id), queryFn: () => periodsApi.result(id) });
export const useResultLines = (id: UUID, employmentId?: UUID) =>
  useQuery({ queryKey: periodKeys.lines(id, employmentId), queryFn: () => periodsApi.lines(id, employmentId) });
export const useImportJob = (jobId: UUID | undefined) =>
  useQuery({ queryKey: periodKeys.importJob(jobId ?? ''), queryFn: () => periodsApi.importJob(jobId as UUID), enabled: !!jobId });

export function usePeriodActions(id: UUID) {
  const invalidate = useInvalidate();
  return {
    review: useMutation({ mutationFn: () => periodsApi.review(id), onSuccess: invalidate }),
    close: useMutation({ mutationFn: (body: CloseRequest) => periodsApi.close(id, body), onSuccess: invalidate }),
    reopen: useMutation({ mutationFn: (reason: string) => periodsApi.reopen(id, reason), onSuccess: invalidate }),
    earlyClose: useMutation({
      mutationFn: ({ employmentId, body }: { employmentId: UUID; body: CloseRequest }) => periodsApi.earlyClose(id, employmentId, body),
      onSuccess: invalidate,
    }),
  };
}

export function usePublishImport() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (jobId: UUID) => periodsApi.publishImport(jobId), onSuccess: invalidate });
}
