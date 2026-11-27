import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import {
  leaveApi,
  type AdjustRequest,
  type LeavePolicyInput,
  type LeaveRequestBody,
  type LeaveRequestListParams,
  type LeaveTypeInput,
  type SiClaimInput,
} from './api';

/** Everything of this area hangs under `['att', 'leave']` so one invalidation refreshes every view. */
export const leaveKeys = {
  all: ['att', 'leave'] as const,
  types: () => ['att', 'leave', 'types'] as const,
  policies: (leaveTypeId?: UUID) => ['att', 'leave', 'policies', leaveTypeId ?? null] as const,
  requests: (p: LeaveRequestListParams) => ['att', 'leave', 'requests', p] as const,
  request: (id: UUID) => ['att', 'leave', 'request', id] as const,
  siClaim: (id: UUID) => ['att', 'leave', 'request', id, 'si-claim'] as const,
  employmentRequests: (employmentId: UUID, from: ISODate, to: ISODate) =>
    ['att', 'leave', 'employment', employmentId, 'requests', from, to] as const,
  balances: (employmentId: UUID, asOf?: ISODate) => ['att', 'leave', 'employment', employmentId, 'balances', asOf ?? null] as const,
  ledger: (employmentId: UUID, leaveTypeId?: UUID, leaveYear?: number) =>
    ['att', 'leave', 'employment', employmentId, 'ledger', leaveTypeId ?? null, leaveYear ?? null] as const,
  settlement: (employmentId: UUID) => ['att', 'leave', 'employment', employmentId, 'settlement'] as const,
  attendanceCodes: () => ['att', 'leave', 'attendance-codes'] as const,
};

function useInvalidate() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: leaveKeys.all });
}

// ------------------------------------------------------------------------------------------------ types & policies

export const useLeaveTypes = () => useQuery({ queryKey: leaveKeys.types(), queryFn: leaveApi.types, staleTime: 60_000 });

export const useLeavePolicies = (leaveTypeId?: UUID) =>
  useQuery({ queryKey: leaveKeys.policies(leaveTypeId), queryFn: () => leaveApi.policies(leaveTypeId) });

export const useAttendanceCodes = (enabled = true) =>
  useQuery({ queryKey: leaveKeys.attendanceCodes(), queryFn: leaveApi.attendanceCodes, staleTime: 10 * 60_000, enabled });

export function useSaveLeaveType() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, body }: { id?: UUID; body: LeaveTypeInput }) => (id ? leaveApi.updateType(id, body) : leaveApi.createType(body)),
    onSuccess: invalidate,
  });
}

export function useSeedStatutory() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: leaveApi.seedStatutory, onSuccess: invalidate });
}

export function useCreatePolicy() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: LeavePolicyInput) => leaveApi.createPolicy(body), onSuccess: invalidate });
}

export function useEndPolicy() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, version, validTo }: { id: UUID; version: number; validTo: ISODate }) => leaveApi.endPolicy(id, version, validTo),
    onSuccess: invalidate,
  });
}

// ------------------------------------------------------------------------------------------------ requests

export const useLeaveRequests = (p: LeaveRequestListParams) =>
  useQuery({ queryKey: leaveKeys.requests(p), queryFn: () => leaveApi.requests(p) });

export const useLeaveRequest = (id: UUID) => useQuery({ queryKey: leaveKeys.request(id), queryFn: () => leaveApi.request(id) });

export const useEmploymentLeaveRequests = (employmentId: UUID | undefined, from: ISODate, to: ISODate) =>
  useQuery({
    queryKey: leaveKeys.employmentRequests(employmentId ?? '', from, to),
    queryFn: () => leaveApi.ofEmployment(employmentId as UUID, from, to),
    enabled: !!employmentId,
  });

/** P3 data: fetched only on demand (audited read). */
export const useSiClaim = (id: UUID, enabled: boolean) =>
  useQuery({ queryKey: leaveKeys.siClaim(id), queryFn: () => leaveApi.siClaim(id), enabled, gcTime: 0, staleTime: 0 });

export function usePreviewLeave() {
  return useMutation({
    mutationFn: ({ employmentId, body }: { employmentId: UUID; body: LeaveRequestBody }) => leaveApi.preview(employmentId, body),
  });
}

export function useSubmitLeave() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ employmentId, body }: { employmentId: UUID; body: LeaveRequestBody }) => leaveApi.submit(employmentId, body),
    onSuccess: invalidate,
  });
}

export function useWithdrawLeave(id: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (reason?: string | null) => leaveApi.withdraw(id, reason), onSuccess: invalidate });
}

export function useEarlyReturn(id: UUID) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ returnDate, attachmentDocumentIds }: { returnDate: ISODate; attachmentDocumentIds?: UUID[] | null }) =>
      leaveApi.earlyReturn(id, returnDate, attachmentDocumentIds),
    onSuccess: invalidate,
  });
}

export function useUpdateSiClaim(id: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: SiClaimInput) => leaveApi.updateSiClaim(id, body), onSuccess: invalidate });
}

// ------------------------------------------------------------------------------------------------ balances

export const useBalances = (employmentId: UUID | undefined, asOf?: ISODate) =>
  useQuery({
    queryKey: leaveKeys.balances(employmentId ?? '', asOf),
    queryFn: () => leaveApi.balances(employmentId as UUID, asOf),
    enabled: !!employmentId,
  });

export const useLedger = (employmentId: UUID | undefined, leaveTypeId?: UUID, leaveYear?: number) =>
  useQuery({
    queryKey: leaveKeys.ledger(employmentId ?? '', leaveTypeId, leaveYear),
    queryFn: () => leaveApi.ledger(employmentId as UUID, leaveTypeId, leaveYear),
    enabled: !!employmentId,
  });

export const useSettlement = (employmentId: UUID | undefined, enabled: boolean) =>
  useQuery({
    queryKey: leaveKeys.settlement(employmentId ?? ''),
    queryFn: () => leaveApi.settlement(employmentId as UUID),
    enabled: !!employmentId && enabled,
  });

export function useAdjustBalance(employmentId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: AdjustRequest) => leaveApi.adjust(employmentId, body), onSuccess: invalidate });
}

export function useFinaliseSettlement(employmentId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: () => leaveApi.finaliseSettlement(employmentId), onSuccess: invalidate });
}

// ------------------------------------------------------------------------------------------------ runs

export type LeaveRun =
  | { kind: 'accrual'; legalEntityId?: UUID | null; month: string }
  | { kind: 'yearEnd'; legalEntityId?: UUID | null; year: number }
  | { kind: 'carryExpiry'; legalEntityId?: UUID | null; asOf?: ISODate | null };

export function useLeaveRun() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (run: LeaveRun) => {
      if (run.kind === 'accrual') return leaveApi.runAccrual(run.legalEntityId, run.month);
      if (run.kind === 'yearEnd') return leaveApi.runYearEnd(run.legalEntityId, run.year);
      return leaveApi.runCarryExpiry(run.legalEntityId, run.asOf);
    },
    onSuccess: invalidate,
  });
}
