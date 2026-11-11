import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import { employeeKeys } from '../employees/queries';
import {
  appendicesApi,
  contractsApi,
  decisionsApi,
  suspensionsApi,
  type AppendixRequest,
  type ExpiryParams,
  type IssueDecisionRequest,
  type ProbationResultRequest,
  type ReissueDecisionRequest,
  type SuspensionRequest,
  type TermsRequest,
} from './api';

/** Everything of this area hangs under `['people', 'contracts']` so one invalidation refreshes every view. */
export const contractKeys = {
  all: ['people', 'contracts'] as const,
  employment: (employmentId: UUID) => ['people', 'contracts', 'employment', employmentId] as const,
  current: (employmentId: UUID, asOf?: ISODate) => ['people', 'contracts', 'employment', employmentId, 'current', asOf ?? null] as const,
  suspensions: (employmentId: UUID) => ['people', 'contracts', 'employment', employmentId, 'suspensions'] as const,
  contract: (contractId: UUID) => ['people', 'contracts', 'contract', contractId] as const,
  appendices: (contractId: UUID) => ['people', 'contracts', 'contract', contractId, 'appendices'] as const,
  expiring: (p: ExpiryParams) => ['people', 'contracts', 'expiring', p] as const,
  probations: (p: ExpiryParams) => ['people', 'contracts', 'probations-ending', p] as const,
  decisions: (employmentId: UUID) => ['people', 'contracts', 'decisions', 'employment', employmentId] as const,
  decision: (decisionId: UUID) => ['people', 'contracts', 'decisions', decisionId] as const,
};

/** After any change: this area's caches + the employee profile header (status may change, e.g. SUSPENDED). */
function useInvalidate() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: contractKeys.all });
    void qc.invalidateQueries({ queryKey: employeeKeys.all });
  };
}

// ------------------------------------------------------------------------------------------------ contracts

export const useContracts = (employmentId: UUID) =>
  useQuery({ queryKey: contractKeys.employment(employmentId), queryFn: () => contractsApi.list(employmentId) });

export const useContract = (contractId: UUID) =>
  useQuery({ queryKey: contractKeys.contract(contractId), queryFn: () => contractsApi.get(contractId) });

export const useSuspensions = (employmentId: UUID | undefined) =>
  useQuery({
    queryKey: contractKeys.suspensions(employmentId ?? ''),
    queryFn: () => suspensionsApi.list(employmentId as UUID),
    enabled: !!employmentId,
  });

export const useAppendices = (contractId: UUID) =>
  useQuery({ queryKey: contractKeys.appendices(contractId), queryFn: () => appendicesApi.list(contractId) });

export const useExpiringContracts = (p: ExpiryParams) =>
  useQuery({ queryKey: contractKeys.expiring(p), queryFn: () => contractsApi.expiring(p) });

export const useProbationsEnding = (p: ExpiryParams) =>
  useQuery({ queryKey: contractKeys.probations(p), queryFn: () => contractsApi.probationsEnding(p) });

/** Create (employment), renew or convert (from a contract): all take `TermsRequest`. */
export type TermsAction = { kind: 'create'; employmentId: UUID } | { kind: 'renew' | 'convert'; contractId: UUID };

export function useTermsMutation() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ action, body }: { action: TermsAction; body: TermsRequest }) => {
      if (action.kind === 'create') return contractsApi.create(action.employmentId, body);
      return action.kind === 'renew' ? contractsApi.renew(action.contractId, body) : contractsApi.convert(action.contractId, body);
    },
    onSuccess: invalidate,
  });
}

export function useSignContract(contractId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (form: FormData) => contractsApi.sign(contractId, form), onSuccess: invalidate });
}

export function useCancelContract(contractId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (version: number) => contractsApi.cancel(contractId, version), onSuccess: invalidate });
}

export function useProbationResult(contractId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (body: ProbationResultRequest) => contractsApi.probationResult(contractId, body),
    onSuccess: invalidate,
  });
}

export function useGenerateContractDocument(contractId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: () => contractsApi.document(contractId), onSuccess: invalidate });
}

export function useTerminateAtExpiry() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ contractId, noticeDate }: { contractId: UUID; noticeDate?: ISODate | null }) =>
      contractsApi.terminateAtExpiry(contractId, noticeDate),
    onSuccess: invalidate,
  });
}

// ------------------------------------------------------------------------------------------------ appendices

export function useCreateAppendix(contractId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: AppendixRequest) => appendicesApi.create(contractId, body), onSuccess: invalidate });
}

export function useAppendixActions(appendixId: UUID) {
  const invalidate = useInvalidate();
  return {
    sign: useMutation({ mutationFn: (form: FormData) => appendicesApi.sign(appendixId, form), onSuccess: invalidate }),
    cancel: useMutation({ mutationFn: (version: number) => appendicesApi.cancel(appendixId, version), onSuccess: invalidate }),
    document: useMutation({ mutationFn: () => appendicesApi.document(appendixId), onSuccess: invalidate }),
  };
}

// ------------------------------------------------------------------------------------------------ suspensions

export function useCreateSuspension(contractId: UUID) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: SuspensionRequest) => suspensionsApi.create(contractId, body), onSuccess: invalidate });
}

export function useSuspensionActions(suspensionId: UUID) {
  const invalidate = useInvalidate();
  return {
    resume: useMutation({
      mutationFn: ({ returnDate, version }: { returnDate: ISODate; version: number }) =>
        suspensionsApi.resume(suspensionId, returnDate, version),
      onSuccess: invalidate,
    }),
    cancel: useMutation({ mutationFn: (version: number) => suspensionsApi.cancel(suspensionId, version), onSuccess: invalidate }),
  };
}

// ------------------------------------------------------------------------------------------------ decisions

export const useDecisions = (employmentId: UUID) =>
  useQuery({ queryKey: contractKeys.decisions(employmentId), queryFn: () => decisionsApi.list(employmentId) });

export const useDecision = (decisionId: UUID) =>
  useQuery({ queryKey: contractKeys.decision(decisionId), queryFn: () => decisionsApi.get(decisionId) });

export function useIssueDecision() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: IssueDecisionRequest) => decisionsApi.issue(body), onSuccess: invalidate });
}

export function useDecisionActions(decisionId: UUID) {
  const invalidate = useInvalidate();
  return {
    document: useMutation({ mutationFn: () => decisionsApi.document(decisionId), onSuccess: invalidate }),
    sign: useMutation({ mutationFn: (form: FormData) => decisionsApi.sign(decisionId, form), onSuccess: invalidate }),
    cancel: useMutation({ mutationFn: (version: number) => decisionsApi.cancel(decisionId, version), onSuccess: invalidate }),
    reissue: useMutation({ mutationFn: (body: ReissueDecisionRequest) => decisionsApi.reissue(decisionId, body), onSuccess: invalidate }),
  };
}
