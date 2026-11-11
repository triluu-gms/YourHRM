import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import { fileForm } from '../../common';
import {
  essApi,
  type BankAccountRequest,
  type EndRequest,
  type FamilyMemberRequest,
  type RegistrationRequest,
  type ResignationRequest,
} from './api';

/** Query keys of the caller's own data (`/people/me/**`). */
export const essKeys = {
  all: ['people', 'me'] as const,
  profile: ['people', 'me', 'profile'] as const,
  employments: ['people', 'me', 'employments'] as const,
  documents: ['people', 'me', 'documents'] as const,
  familyMembers: ['people', 'me', 'family-members'] as const,
  dependants: ['people', 'me', 'dependants'] as const,
  bankAccounts: ['people', 'me', 'bank-accounts'] as const,
  resignation: ['people', 'me', 'resignation'] as const,
};

export const useMyProfile = () => useQuery({ queryKey: essKeys.profile, queryFn: essApi.profile });
export const useMyEmployments = () => useQuery({ queryKey: essKeys.employments, queryFn: essApi.employments });
export const useMyDocuments = () => useQuery({ queryKey: essKeys.documents, queryFn: essApi.documents });
export const useMyFamilyMembers = () => useQuery({ queryKey: essKeys.familyMembers, queryFn: essApi.familyMembers });
export const useMyDependants = () => useQuery({ queryKey: essKeys.dependants, queryFn: essApi.dependants });
export const useMyBankAccounts = () => useQuery({ queryKey: essKeys.bankAccounts, queryFn: essApi.bankAccounts });
export const useMyResignation = () =>
  useQuery({ queryKey: essKeys.resignation, queryFn: async () => (await essApi.resignation()) ?? null });

/** Fetches a fresh signed URL (≤ 15 min) and opens it; links are never cached. */
export function useOpenDocument() {
  return useMutation({
    mutationFn: (documentId: UUID) => essApi.downloadLink(documentId),
    onSuccess: (link) => {
      window.open(link.url, '_blank', 'noopener');
    },
  });
}

export function useAddFamilyMember() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: FamilyMemberRequest) => essApi.addFamilyMember(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: essKeys.familyMembers }),
  });
}

export function useUploadEvidence() {
  return useMutation({
    mutationFn: ({ familyMemberId, file, title }: { familyMemberId: UUID; file: File; title?: string }) =>
      essApi.uploadEvidence(familyMemberId, fileForm(file, { title })),
  });
}

export function useValidateDependant() {
  return useMutation({ mutationFn: (body: RegistrationRequest) => essApi.validateDependant(body) });
}

export function useRegisterDependant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: RegistrationRequest) => essApi.registerDependant(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: essKeys.dependants }),
  });
}

export function useEndDependant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: EndRequest }) => essApi.endDependant(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: essKeys.dependants }),
  });
}

export function useRequestBankAccount() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: BankAccountRequest) => essApi.requestBankAccount(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: essKeys.bankAccounts }),
  });
}

export function useResign() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ResignationRequest) => essApi.resign(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: essKeys.resignation }),
  });
}

export function useWithdrawResignation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, note }: { id: UUID; note?: string | null }) => essApi.withdraw(id, note),
    onSuccess: () => void qc.invalidateQueries({ queryKey: essKeys.resignation }),
  });
}
