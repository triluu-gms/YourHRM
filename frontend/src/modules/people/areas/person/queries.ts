import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import { employeeKeys } from '../employees/queries';
import {
  addressApi,
  contactApi,
  coreDocumentApi,
  hireApi,
  identityDocumentApi,
  personApi,
  qualificationApi,
  type AddressData,
  type AddressType,
  type CancelHireRequest,
  type ChangeIdentifiersRequest,
  type ContactType,
  type DocumentRequest,
  type HireMatchRequest,
  type HireRequest,
  type MatchRequest,
  type PersonField,
  type QualificationRequest,
  type UpdatePersonRequest,
} from './api';

export const personKeys = {
  all: ['people', 'person'] as const,
  person: (id: UUID) => ['people', 'person', id] as const,
  addresses: (id: UUID, asOf?: ISODate) => ['people', 'person', id, 'addresses', { asOf: asOf ?? null }] as const,
  addressesAll: (id: UUID) => ['people', 'person', id, 'addresses'] as const,
  contacts: (id: UUID) => ['people', 'person', id, 'contacts'] as const,
  documents: (id: UUID) => ['people', 'person', id, 'identity-documents'] as const,
  qualifications: (id: UUID) => ['people', 'person', id, 'qualifications'] as const,
  mergePreview: (id: UUID, dup: UUID) => ['people', 'person', id, 'merge-preview', dup] as const,
};

/** Invalidates the person and the employee profiles (the header shows the person's name / masked id). */
function useInvalidatePerson(personId: UUID) {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: personKeys.person(personId) });
    void qc.invalidateQueries({ queryKey: employeeKeys.all });
  };
}

// ------------------------------------------------------------------------------------------------- persons

export function usePerson(personId: UUID) {
  return useQuery({ queryKey: personKeys.person(personId), queryFn: () => personApi.get(personId) });
}

export function useUpdatePerson(personId: UUID) {
  const invalidate = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: (body: UpdatePersonRequest) => personApi.update(personId, body),
    onSuccess: invalidate,
    onError: invalidate,
  });
}

export function useChangeIdentifiers(personId: UUID) {
  const invalidate = useInvalidatePerson(personId);
  return useMutation({
    mutationFn: (body: ChangeIdentifiersRequest) => personApi.changeIdentifiers(personId, body),
    onSuccess: invalidate,
    onError: invalidate,
  });
}

/** Unmask is a POST (audited); never cached. */
export function useUnmaskPerson(personId: UUID) {
  return useMutation({
    mutationFn: ({ fields, purpose }: { fields: PersonField[]; purpose: string }) => personApi.unmask(personId, fields, purpose),
  });
}

export function usePersonMatch() {
  return useMutation({ mutationFn: (body: MatchRequest) => personApi.match(body) });
}

export function useMergePreview(personId: UUID, duplicatePersonId: UUID | null) {
  return useQuery({
    queryKey: personKeys.mergePreview(personId, duplicatePersonId ?? ''),
    queryFn: () => personApi.mergePreview(personId, duplicatePersonId as UUID),
    enabled: !!duplicatePersonId && duplicatePersonId !== personId,
  });
}

export function useMergePerson(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { duplicatePersonId: UUID; reason: string; version: number }) => personApi.merge(personId, body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: personKeys.all });
      void qc.invalidateQueries({ queryKey: employeeKeys.all });
    },
  });
}

// ----------------------------------------------------------------------------------------------- addresses

export function useAddresses(personId: UUID, asOf?: ISODate) {
  return useQuery({ queryKey: personKeys.addresses(personId, asOf), queryFn: () => addressApi.list(personId, asOf) });
}

export function useAddAddress(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { addressType: AddressType; validFrom: ISODate; address: AddressData }) => addressApi.add(personId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: personKeys.addressesAll(personId) }),
  });
}

export function useCorrectAddress(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ addressId, ...body }: { addressId: UUID; version: number; address: AddressData }) =>
      addressApi.correct(personId, addressId, body),
    onSettled: () => void qc.invalidateQueries({ queryKey: personKeys.addressesAll(personId) }),
  });
}

export function useEndAddress(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ addressId, ...body }: { addressId: UUID; version: number; validTo: ISODate }) =>
      addressApi.end(personId, addressId, body),
    onSettled: () => void qc.invalidateQueries({ queryKey: personKeys.addressesAll(personId) }),
  });
}

// ------------------------------------------------------------------------------------------------ contacts

export function useContacts(personId: UUID) {
  return useQuery({ queryKey: personKeys.contacts(personId), queryFn: () => contactApi.list(personId) });
}

export function useAddContact(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { contactType: ContactType; value: string; primary?: boolean }) => contactApi.add(personId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: personKeys.contacts(personId) }),
  });
}

export function useChangeContact(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ contactId, ...body }: { contactId: UUID; version: number; value: string; primary?: boolean }) =>
      contactApi.change(personId, contactId, body),
    onSettled: () => void qc.invalidateQueries({ queryKey: personKeys.contacts(personId) }),
  });
}

export function useRemoveContact(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (contactId: UUID) => contactApi.remove(personId, contactId),
    onSettled: () => void qc.invalidateQueries({ queryKey: personKeys.contacts(personId) }),
  });
}

// -------------------------------------------------------------------------------------- identity documents

export function useIdentityDocuments(personId: UUID) {
  return useQuery({ queryKey: personKeys.documents(personId), queryFn: () => identityDocumentApi.list(personId) });
}

export function useAddIdentityDocument(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: DocumentRequest) => identityDocumentApi.add(personId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: personKeys.documents(personId) }),
  });
}

export function useChangeIdentityDocument(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ documentId, ...body }: { documentId: UUID; version: number; document: DocumentRequest }) =>
      identityDocumentApi.change(personId, documentId, body),
    onSettled: () => void qc.invalidateQueries({ queryKey: personKeys.documents(personId) }),
  });
}

export function useUnmaskIdentityDocument(personId: UUID) {
  return useMutation({
    mutationFn: ({ documentId, purpose }: { documentId: UUID; purpose: string }) =>
      identityDocumentApi.unmask(personId, documentId, purpose),
  });
}

// ------------------------------------------------------------------------------------------- qualifications

export function useQualifications(personId: UUID) {
  return useQuery({ queryKey: personKeys.qualifications(personId), queryFn: () => qualificationApi.list(personId) });
}

export function useAddQualification(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: QualificationRequest) => qualificationApi.add(personId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: personKeys.qualifications(personId) }),
  });
}

export function useChangeQualification(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ qualificationId, ...body }: { qualificationId: UUID; version: number; qualification: QualificationRequest }) =>
      qualificationApi.change(personId, qualificationId, body),
    onSettled: () => void qc.invalidateQueries({ queryKey: personKeys.qualifications(personId) }),
  });
}

// ------------------------------------------------------------------------------------------------- scans

export type ScanTarget = 'IDENTITY_DOC' | 'QUALIFICATION';

/**
 * Uploads a scan to Core's document service (owner = the People record) and links it to the record. The Core
 * document is PENDING_SCAN until the antivirus result arrives; People accepts the link right away.
 */
export function useAttachScan(personId: UUID, target: ScanTarget) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ recordId, file }: { recordId: UUID; file: File }) => {
      const type = target === 'IDENTITY_DOC' ? 'HR_IDENTITY_DOC_SCAN' : 'HR_QUALIFICATION_SCAN';
      const doc = await coreDocumentApi.upload(file, type, recordId, personId);
      return target === 'IDENTITY_DOC'
        ? identityDocumentApi.attachScan(personId, recordId, doc.id)
        : qualificationApi.attachScan(personId, recordId, doc.id);
    },
    onSettled: () =>
      void qc.invalidateQueries({
        queryKey: target === 'IDENTITY_DOC' ? personKeys.documents(personId) : personKeys.qualifications(personId),
      }),
  });
}

export function useOpenScan() {
  return useMutation({
    mutationFn: (documentId: UUID) => coreDocumentApi.downloadLink(documentId),
    onSuccess: (link) => window.open(link.url, '_blank', 'noopener'),
  });
}

// --------------------------------------------------------------------------------------------------- hires

export function useHireMatch() {
  return useMutation({ mutationFn: (body: HireMatchRequest) => hireApi.match(body) });
}

export function useHire() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: HireRequest) => hireApi.hire(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employeeKeys.all }),
  });
}

export function useCancelHire() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ employmentId, ...body }: CancelHireRequest & { employmentId: UUID }) => hireApi.cancel(employmentId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employeeKeys.all }),
  });
}
