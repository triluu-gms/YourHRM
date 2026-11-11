import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import { familyApi, type BankAccountRequest, type EndRequest, type FamilyMemberRequest, type RegistrationRequest } from './api';

export const familyKeys = {
  person: (personId: UUID) => ['people', 'family', personId] as const,
  members: (personId: UUID) => ['people', 'family', personId, 'members'] as const,
  memberDocuments: (memberId: UUID) => ['people', 'family', 'member-documents', memberId] as const,
  dependants: (personId: UUID) => ['people', 'family', personId, 'dependants'] as const,
  bankAccounts: (personId: UUID) => ['people', 'family', personId, 'bank-accounts'] as const,
};

export const useFamilyMembers = (personId: UUID) =>
  useQuery({ queryKey: familyKeys.members(personId), queryFn: () => familyApi.members(personId) });

export const useMemberDocuments = (memberId: UUID | null | undefined) =>
  useQuery({
    queryKey: familyKeys.memberDocuments(memberId ?? ''),
    queryFn: () => familyApi.memberDocuments(memberId as UUID),
    enabled: !!memberId,
  });

export const useDependants = (personId: UUID) =>
  useQuery({ queryKey: familyKeys.dependants(personId), queryFn: () => familyApi.dependants(personId) });

export const useBankAccounts = (personId: UUID) =>
  useQuery({ queryKey: familyKeys.bankAccounts(personId), queryFn: () => familyApi.bankAccounts(personId) });

function useInvalidatePerson(personId: UUID) {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: familyKeys.person(personId) });
}

export function useSaveFamilyMember(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: ({ id, body }: { id?: UUID; body: FamilyMemberRequest }) =>
      id ? familyApi.updateMember(personId, id, body) : familyApi.createMember(personId, body),
    onSuccess: done,
  });
}

/** Unmasked identifiers on demand (never cached: audited sensitive read). */
export function useRevealIdentifiers(personId: UUID) {
  return useMutation({ mutationFn: (id: UUID) => familyApi.identifiers(personId, id) });
}

export function useUploadMemberEvidence(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ memberId, form }: { memberId: UUID; form: FormData }) => familyApi.uploadEvidence(personId, memberId, form),
    onSuccess: (_d, v) => void qc.invalidateQueries({ queryKey: familyKeys.memberDocuments(v.memberId) }),
  });
}

export function useValidateDependant(personId: UUID) {
  return useMutation({ mutationFn: (body: RegistrationRequest) => familyApi.validate(personId, body) });
}

export function useRegisterDependant(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({ mutationFn: (body: RegistrationRequest) => familyApi.register(personId, body), onSuccess: done });
}

export function useEndDependant(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: EndRequest }) => familyApi.end(personId, id, body),
    onSuccess: done,
  });
}

export function useRevealAccountNumber(personId: UUID) {
  return useMutation({ mutationFn: (id: UUID) => familyApi.accountNumber(personId, id) });
}

export function useCreateBankAccount(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({ mutationFn: (body: BankAccountRequest) => familyApi.createBankAccount(personId, body), onSuccess: done });
}

export function useCloseBankAccount(personId: UUID) {
  const done = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: ({ id, validTo, version }: { id: UUID; validTo: ISODate; version: number }) =>
      familyApi.closeBankAccount(personId, id, { validTo, version }),
    onSuccess: done,
  });
}
