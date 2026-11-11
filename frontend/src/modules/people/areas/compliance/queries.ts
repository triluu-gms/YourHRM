import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import { complianceApi, type ChangeStatusRequest, type PermitRequest, type ResidencyRequest, type StatusRequest } from './api';
import { uploadDocument } from './evidence';

export const complianceKeys = {
  person: (personId: UUID) => ['people', 'compliance', personId] as const,
  residencies: (personId: UUID) => ['people', 'compliance', personId, 'tax-residencies'] as const,
  presence: (personId: UUID) => ['people', 'compliance', personId, 'presence'] as const,
  statuses: (personId: UUID, includeCancelled: boolean) =>
    ['people', 'compliance', personId, 'special-statuses', { includeCancelled }] as const,
  permits: (personId: UUID) => ['people', 'compliance', personId, 'work-permits'] as const,
  proposals: ['people', 'compliance', 'residency-proposals'] as const,
  expiring: (kind: 'permits' | 'identity', withinDays: number) => ['people', 'compliance', 'expiring', kind, withinDays] as const,
  personLink: (personId: UUID) => ['people', 'compliance', 'person-link', personId] as const,
};

export const useTaxResidencies = (personId: UUID) =>
  useQuery({ queryKey: complianceKeys.residencies(personId), queryFn: () => complianceApi.taxResidencies(personId) });
export const usePresence = (personId: UUID) =>
  useQuery({ queryKey: complianceKeys.presence(personId), queryFn: () => complianceApi.presence(personId) });
export const useSpecialStatuses = (personId: UUID, includeCancelled: boolean) =>
  useQuery({
    queryKey: complianceKeys.statuses(personId, includeCancelled),
    queryFn: () => complianceApi.specialStatuses(personId, includeCancelled),
  });
export const useWorkPermits = (personId: UUID) =>
  useQuery({ queryKey: complianceKeys.permits(personId), queryFn: () => complianceApi.workPermits(personId) });
export const useResidencyProposals = () => useQuery({ queryKey: complianceKeys.proposals, queryFn: complianceApi.proposals });
export const useExpiringWorkPermits = (withinDays: number) =>
  useQuery({ queryKey: complianceKeys.expiring('permits', withinDays), queryFn: () => complianceApi.expiringWorkPermits(withinDays) });
export const useExpiringIdentityDocuments = (withinDays: number) =>
  useQuery({
    queryKey: complianceKeys.expiring('identity', withinDays),
    queryFn: () => complianceApi.expiringIdentityDocuments(withinDays),
  });

/** Name + employments of a person, to link a person-level row to an employee profile. */
export function usePersonLink(personId: UUID, enabled = true) {
  return useQuery({
    queryKey: complianceKeys.personLink(personId),
    queryFn: async () => {
      const [person, employments] = await Promise.all([complianceApi.person(personId), complianceApi.personEmployments(personId)]);
      return { person, employments };
    },
    enabled,
    staleTime: 5 * 60_000,
  });
}

function useInvalidatePerson(personId: UUID) {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: complianceKeys.person(personId) });
}

export function useSubmitTaxResidency(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({ mutationFn: (body: ResidencyRequest) => complianceApi.submitTaxResidency(personId, body), onSuccess: done });
}

export function useSavePresence(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: ({ id, version, entryDate, exitDate }: { id?: UUID; version?: number; entryDate: ISODate; exitDate?: ISODate | null }) =>
      id ? complianceApi.changePresence(personId, id, { version: version as number, entryDate, exitDate }) : complianceApi.addPresence(personId, { entryDate, exitDate }),
    onSuccess: done,
  });
}

export function useAddSpecialStatus(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({ mutationFn: (body: StatusRequest) => complianceApi.addSpecialStatus(personId, body), onSuccess: done });
}

export function useChangeSpecialStatus(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: ChangeStatusRequest }) => complianceApi.changeSpecialStatus(personId, id, body),
    onSuccess: done,
  });
}

export function useCancelSpecialStatus(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: ({ id, version }: { id: UUID; version: number }) => complianceApi.cancelSpecialStatus(personId, id, version),
    onSuccess: done,
  });
}

export function useSaveWorkPermit(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: ({ id, version, body }: { id?: UUID; version?: number; body: PermitRequest }) =>
      id ? complianceApi.changeWorkPermit(personId, id, version as number, body) : complianceApi.addWorkPermit(personId, body),
    onSuccess: done,
  });
}

export type EvidenceKind = 'taxResidency' | 'specialStatus' | 'workPermit';

const DOC_TYPES: Record<EvidenceKind, string> = {
  taxResidency: 'HR_TAX_RESIDENCY_EVIDENCE',
  specialStatus: 'HR_SPECIAL_STATUS_EVIDENCE',
  workPermit: 'HR_WORK_PERMIT_SCAN',
};

/** Uploads a file to Core documents (owner = the People row) and attaches it as the row's evidence / scan. */
export function useAttachEvidence(personId: UUID, kind: EvidenceKind) {
  const done = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: async ({ id, file }: { id: UUID; file: File }) => {
      const doc = await uploadDocument({ file, documentTypeCode: DOC_TYPES[kind], ownerEntityId: id, subjectPersonId: personId });
      if (kind === 'taxResidency') return complianceApi.attachResidencyEvidence(personId, id, doc.id);
      if (kind === 'specialStatus') return complianceApi.attachStatusEvidence(personId, id, doc.id);
      return complianceApi.attachPermitScan(personId, id, doc.id);
    },
    onSuccess: done,
  });
}

export function useDecideProposal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, reject }: { id: UUID; version: number; reject?: string }) =>
      reject !== undefined ? complianceApi.rejectProposal(id, version, reject) : complianceApi.approveProposal(id, version),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['people', 'compliance'] }),
  });
}
