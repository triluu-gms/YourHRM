import { useSyncExternalStore } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isProblem, type ISODate, type UUID } from '@/shared/api';
import { employeeKeys } from '../employees/queries';
import {
  assignmentApi,
  employmentApi,
  salaryApi,
  type AssignmentChangeRequest,
  type AssignmentCorrectionRequest,
  type InsuranceOverrideRequest,
  type PrimaryChangeRequest,
  type SalaryChangeRequest,
  type UpdateEmploymentRequest,
} from './api';

export const employmentKeys = {
  all: ['people', 'employment'] as const,
  employment: (id: UUID) => ['people', 'employment', id] as const,
  status: (id: UUID, asOf?: ISODate) => ['people', 'employment', id, 'status', asOf ?? null] as const,
  primary: (personId: UUID, asOf?: ISODate) => ['people', 'employment', 'primary', personId, asOf ?? null] as const,
  insurance: (id: UUID, asOf?: ISODate) => ['people', 'employment', id, 'insurance', asOf ?? null] as const,
  assignments: (id: UUID) => ['people', 'employment', id, 'assignments'] as const,
  assignmentsAsOf: (id: UUID, asOf?: ISODate) => ['people', 'employment', id, 'assignments', 'as-of', asOf ?? null] as const,
  assignmentHistory: (id: UUID) => ['people', 'employment', id, 'assignments', 'history'] as const,
  reasons: ['people', 'employment', 'assignment-change-reasons'] as const,
  salary: (id: UUID) => ['people', 'employment', id, 'salary'] as const,
  salaryAsOf: (id: UUID, asOf?: ISODate) => ['people', 'employment', id, 'salary', 'as-of', asOf ?? null] as const,
  salaryHistory: (id: UUID) => ['people', 'employment', id, 'salary', 'history'] as const,
  salaryList: (params: object) => ['people', 'employment', 'salary-list', params] as const,
  concurrent: (le: UUID, asOf?: ISODate) => ['people', 'employment', 'concurrent', le, asOf ?? null] as const,
};

// ---------------------------------------------------------------------------------------- salary view purpose

/**
 * The purpose of viewing salary data (US-PPL-045), asked once per browser session and sent with every salary
 * read (the backend's own memory is per node and expires). Not persisted: a reload asks again.
 */
let salaryPurpose: string | undefined;
const purposeListeners = new Set<() => void>();
export const salaryPurposeStore = {
  get: () => salaryPurpose,
  set: (p: string | undefined) => {
    salaryPurpose = p?.trim() || undefined;
    purposeListeners.forEach((l) => l());
  },
  subscribe: (l: () => void) => {
    purposeListeners.add(l);
    return () => purposeListeners.delete(l);
  },
};

export function useSalaryPurpose(): [string | undefined, (p: string | undefined) => void] {
  const p = useSyncExternalStore(salaryPurposeStore.subscribe, salaryPurposeStore.get, salaryPurposeStore.get);
  return [p, salaryPurposeStore.set];
}

/** True when the backend refused a salary read because no purpose was given (422 `purpose-required`). */
export const needsPurpose = (error: unknown) => isProblem(error, 'purpose-required');

const noRetryOnPurpose = (count: number, error: unknown) => !needsPurpose(error) && !isProblem(error) && count < 2;

// ------------------------------------------------------------------------------------------------ employment

export function useEmployment(employmentId: UUID) {
  return useQuery({ queryKey: employmentKeys.employment(employmentId), queryFn: () => employmentApi.get(employmentId) });
}

export function useEmploymentStatus(employmentId: UUID, asOf?: ISODate) {
  return useQuery({
    queryKey: employmentKeys.status(employmentId, asOf),
    queryFn: () => employmentApi.status(employmentId, asOf),
  });
}

function useInvalidateEmployment() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: employmentKeys.all });
    void qc.invalidateQueries({ queryKey: employeeKeys.all });
  };
}

export function useUpdateEmployment(employmentId: UUID) {
  const invalidate = useInvalidateEmployment();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateEmploymentRequest) => employmentApi.update(employmentId, body),
    onSuccess: invalidate,
    onError: () => void qc.invalidateQueries({ queryKey: employmentKeys.employment(employmentId) }),
  });
}

export function usePrimaryEmployment(personId: UUID, asOf?: ISODate) {
  return useQuery({ queryKey: employmentKeys.primary(personId, asOf), queryFn: () => employmentApi.primary(personId, asOf) });
}

export function usePersonEmployments(personId: UUID) {
  return useQuery({
    queryKey: ['people', 'employment', 'of-person', personId],
    queryFn: () => employmentApi.ofPerson(personId),
  });
}

export function useRequestPrimaryChange(personId: UUID) {
  const invalidate = useInvalidateEmployment();
  return useMutation({
    mutationFn: (body: PrimaryChangeRequest) => employmentApi.requestPrimaryChange(personId, body),
    onSuccess: invalidate,
  });
}

export function useInsurance(employmentId: UUID, asOf?: ISODate) {
  return useQuery({
    queryKey: employmentKeys.insurance(employmentId, asOf),
    queryFn: () => employmentApi.insurance(employmentId, asOf),
  });
}

export function useOverrideInsurance(employmentId: UUID) {
  const invalidate = useInvalidateEmployment();
  return useMutation({
    mutationFn: (body: InsuranceOverrideRequest) => employmentApi.overrideInsurance(employmentId, body),
    onSuccess: invalidate,
  });
}

export function useRederiveInsurance(employmentId: UUID) {
  const invalidate = useInvalidateEmployment();
  return useMutation({
    mutationFn: (from?: ISODate | null) => employmentApi.rederiveInsurance(employmentId, from),
    onSuccess: invalidate,
  });
}

export function useConcurrentExceptions(legalEntityId: UUID | undefined, asOf?: ISODate) {
  return useQuery({
    queryKey: employmentKeys.concurrent(legalEntityId ?? '', asOf),
    queryFn: () => employmentApi.concurrentExceptions(legalEntityId as UUID, asOf),
    enabled: !!legalEntityId,
  });
}

// ----------------------------------------------------------------------------------------------- assignments

export function useAssignmentsAsOf(employmentId: UUID, asOf?: ISODate) {
  return useQuery({
    queryKey: employmentKeys.assignmentsAsOf(employmentId, asOf),
    queryFn: () => assignmentApi.asOf(employmentId, asOf),
  });
}

export function useAssignmentHistory(employmentId: UUID, enabled: boolean) {
  return useQuery({
    queryKey: employmentKeys.assignmentHistory(employmentId),
    queryFn: () => assignmentApi.history(employmentId),
    enabled,
  });
}

export function useChangeReasons(enabled = true) {
  return useQuery({ queryKey: employmentKeys.reasons, queryFn: assignmentApi.reasons, enabled, staleTime: 10 * 60_000 });
}

export function useRequestAssignmentChange(employmentId: UUID) {
  const invalidate = useInvalidateEmployment();
  return useMutation({
    mutationFn: (body: AssignmentChangeRequest) => assignmentApi.requestChange(employmentId, body),
    onSuccess: invalidate,
  });
}

export function useCorrectAssignment(employmentId: UUID) {
  const invalidate = useInvalidateEmployment();
  return useMutation({
    mutationFn: ({ assignmentId, body }: { assignmentId: UUID; body: AssignmentCorrectionRequest }) =>
      assignmentApi.correct(employmentId, assignmentId, body),
    onSuccess: invalidate,
  });
}

export function useCancelAssignment(employmentId: UUID) {
  const invalidate = useInvalidateEmployment();
  return useMutation({
    mutationFn: ({ assignmentId, reason }: { assignmentId: UUID; reason: string }) =>
      assignmentApi.cancel(employmentId, assignmentId, reason),
    onSuccess: invalidate,
  });
}

// ---------------------------------------------------------------------------------------------------- salary

export function useSalaryAsOf(employmentId: UUID, asOf: ISODate | undefined, purpose: string | undefined) {
  return useQuery({
    queryKey: [...employmentKeys.salaryAsOf(employmentId, asOf), purpose ?? null],
    queryFn: async () => (await salaryApi.asOf(employmentId, asOf, purpose)) ?? null,
    retry: noRetryOnPurpose,
  });
}

export function useSalaryHistory(employmentId: UUID, purpose: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: [...employmentKeys.salaryHistory(employmentId), purpose ?? null],
    queryFn: () => salaryApi.history(employmentId, purpose),
    enabled,
    retry: noRetryOnPurpose,
  });
}

export function useSalaryList(params: { asOf?: ISODate; legalEntityId?: UUID; purpose?: string; limit: number; offset: number }) {
  return useQuery({
    queryKey: employmentKeys.salaryList(params),
    queryFn: () => salaryApi.list(params),
    retry: noRetryOnPurpose,
  });
}

export function useRequestSalaryChange(employmentId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: SalaryChangeRequest) => salaryApi.requestChange(employmentId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employmentKeys.salary(employmentId) }),
  });
}
