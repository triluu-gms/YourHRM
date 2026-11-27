import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { saveBlob, type ISODate, type UUID } from '@/shared/api';
import {
  integrationApi,
  jobsApi,
  reportsApi,
  type BalanceParams,
  type CloseStatusParams,
  type ExceptionParams,
  type ExportKind,
  type GridParams,
  type InboxParams,
  type LeaveRangeParams,
  type OverrideParams,
  type OvertimeSummaryParams,
  type PunchLogParams,
  type RetroParams,
} from './api';

const key = (...parts: unknown[]) => ['att', 'reports', ...parts] as const;

/** A report query runs only once its required filters are applied (`params` non-null). */
function report<P, R>(name: string, params: P | null, fn: (p: P) => Promise<R>) {
  return {
    queryKey: key(name, params),
    queryFn: () => fn(params as P),
    enabled: params !== null,
    placeholderData: keepPreviousData,
  };
}

export const useTimesheetGrid = (p: GridParams | null) => useQuery(report('timesheet-grid', p, reportsApi.timesheetGrid));
export const useDailyExceptions = (p: ExceptionParams | null) =>
  useQuery(report('daily-exceptions', p, reportsApi.dailyExceptions));
export const usePunchLog = (p: PunchLogParams | null) => useQuery(report('punch-log', p, reportsApi.punchLog));
export const useOvertimeSummary = (p: OvertimeSummaryParams | null) =>
  useQuery(report('overtime-summary', p, reportsApi.overtimeSummary));
export const useOvertimeCapOverrides = (p: OverrideParams | null) =>
  useQuery(report('overtime-cap-overrides', p, reportsApi.overtimeCapOverrides));
export const useLeaveBalancesReport = (p: BalanceParams | null) =>
  useQuery(report('leave-balances', p, reportsApi.leaveBalances));
export const useLeaveTaken = (p: LeaveRangeParams | null) => useQuery(report('leave-taken', p, reportsApi.leaveTaken));
export const useSiLeaveRegister = (p: LeaveRangeParams | null) =>
  useQuery(report('si-leave-register', p, reportsApi.siLeaveRegister));
export const usePeriodCloseStatus = (p: CloseStatusParams | null) =>
  useQuery(report('period-close-status', p, reportsApi.periodCloseStatus));
export const useRetroAdjustments = (p: RetroParams | null) =>
  useQuery(report('retro-adjustments', p, reportsApi.retroAdjustments));

const LONG = 5 * 60_000;
export const usePeriodOptions = (legalEntityId: UUID | undefined, enabled: boolean) =>
  useQuery({
    queryKey: key('lookup', 'periods', legalEntityId ?? ''),
    queryFn: () => reportsApi.periods(legalEntityId),
    enabled,
    staleTime: LONG,
  });
export const useDeviceOptions = (enabled: boolean) =>
  useQuery({ queryKey: key('lookup', 'devices'), queryFn: reportsApi.devices, enabled, staleTime: LONG });
export const useLeaveTypeOptions = (enabled: boolean) =>
  useQuery({ queryKey: key('lookup', 'leave-types'), queryFn: reportsApi.leaveTypes, enabled, staleTime: LONG });

/** Downloads an Excel export and saves it under the server's file name. */
export function useExportReport() {
  return useMutation({
    mutationFn: async ({ kind, params }: { kind: ExportKind; params: Record<string, unknown> }) => {
      const { blob, fileName } = await reportsApi.exportXlsx(kind, params);
      saveBlob(blob, fileName ?? `${kind}.xlsx`);
    },
  });
}

export const useInbox = (p: InboxParams) =>
  useQuery({ queryKey: ['att', 'integration', 'inbox', p], queryFn: () => integrationApi.inbox(p), placeholderData: keepPreviousData });

export const useResyncLocations = () => useMutation({ mutationFn: integrationApi.resyncLocations });
export const useRunNightly = () => useMutation({ mutationFn: (today?: ISODate) => jobsApi.runNightly(today) });
export const useRunReminders = () => useMutation({ mutationFn: (today?: ISODate) => jobsApi.runReminders(today) });
