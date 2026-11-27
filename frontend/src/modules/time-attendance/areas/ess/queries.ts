import { useSyncExternalStore } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import { essApi, type LeaveRequestBody, type MyEmployment } from './api';

const key = (...parts: unknown[]) => ['att', 'me', ...parts] as const;
export const essKeys = { all: key() };

// ------------------------------------------------------------------------- employment choice

/** The employment picked in the switcher, kept for the session across the ESS pages. */
let chosenEmployment: UUID | undefined;
const listeners = new Set<() => void>();
const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};
function setChosenEmployment(id: UUID | undefined) {
  chosenEmployment = id;
  listeners.forEach((l) => l());
}

export const useMyEmployments = () =>
  useQuery({ queryKey: key('employments'), queryFn: essApi.employments, staleTime: 5 * 60_000, retry: false });

export interface EmploymentChoice {
  employments: MyEmployment[];
  /** The employment to query; undefined = the backend's default (the caller's only / active one). */
  employmentId: UUID | undefined;
  setEmploymentId: (id: UUID) => void;
  /** False until `/me/employments` answered (data or error): the page queries wait for it. */
  ready: boolean;
  error: unknown;
}

/** Caller's employments and the chosen one (default: active today, else the first). */
export function useEmploymentChoice(): EmploymentChoice {
  const q = useMyEmployments();
  const chosen = useSyncExternalStore(subscribe, () => chosenEmployment);
  const employments = q.data ?? [];
  const valid = employments.some((e) => e.employmentId === chosen) ? chosen : undefined;
  const fallback = employments.find((e) => e.activeToday)?.employmentId ?? employments[0]?.employmentId;
  return {
    employments,
    employmentId: employments.length > 1 ? (valid ?? fallback) : undefined,
    setEmploymentId: setChosenEmployment,
    ready: !q.isLoading,
    error: q.error,
  };
}

// ------------------------------------------------------------------------- reads

interface Base {
  employmentId?: UUID;
  lang?: string;
}

export const useMySchedule = (p: { employmentId?: UUID; from: ISODate; to: ISODate }, enabled: boolean) =>
  useQuery({ queryKey: key('schedule', p), queryFn: () => essApi.schedule(p), enabled, placeholderData: keepPreviousData });

export const useMyPunches = (p: { employmentId?: UUID; from: ISODate; to: ISODate }, enabled: boolean) =>
  useQuery({ queryKey: key('punches', p), queryFn: () => essApi.punches(p), enabled, placeholderData: keepPreviousData });

export const useMyTimesheets = (p: Base & { from: ISODate; to: ISODate }, enabled: boolean) =>
  useQuery({ queryKey: key('timesheets', p), queryFn: () => essApi.timesheets(p), enabled, placeholderData: keepPreviousData });

export const useMyDay = (date: ISODate | null, p: Base, enabled: boolean) =>
  useQuery({
    queryKey: key('day', date, p),
    queryFn: () => essApi.day(date as ISODate, p),
    enabled: enabled && date !== null,
  });

export const useMyExceptions = (p: Base & { from: ISODate; to: ISODate }, enabled: boolean) =>
  useQuery({ queryKey: key('exceptions', p), queryFn: () => essApi.exceptions(p), enabled, placeholderData: keepPreviousData });

export const useMyBalances = (p: Base & { asOf?: ISODate }, enabled: boolean) =>
  useQuery({ queryKey: key('balances', p), queryFn: () => essApi.balances(p), enabled, placeholderData: keepPreviousData });

export const useMyLedger = (p: Base & { leaveTypeId?: UUID; leaveYear?: number }, enabled: boolean) =>
  useQuery({ queryKey: key('ledger', p), queryFn: () => essApi.ledger(p), enabled, placeholderData: keepPreviousData });

export const useMyOvertimeCaps = (p: { employmentId?: UUID; date?: ISODate }, enabled: boolean) =>
  useQuery({ queryKey: key('overtime-caps', p), queryFn: () => essApi.overtimeCaps(p), enabled, placeholderData: keepPreviousData });

export const useMyRequests = (p: Base & { from: ISODate; to: ISODate }, enabled: boolean) =>
  useQuery({ queryKey: key('requests', p), queryFn: () => essApi.requests(p), enabled, placeholderData: keepPreviousData });

// ------------------------------------------------------------------------- leave request

export const usePreviewLeave = () =>
  useMutation({
    mutationFn: ({ employmentId, body }: { employmentId: UUID; body: LeaveRequestBody }) =>
      essApi.previewLeave(employmentId, body),
  });

export function useRequestLeave() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ employmentId, body }: { employmentId: UUID; body: LeaveRequestBody }) =>
      essApi.requestLeave(employmentId, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: essKeys.all }),
  });
}
