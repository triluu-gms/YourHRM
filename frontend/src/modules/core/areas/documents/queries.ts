import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import {
  documentTemplatesApi,
  documentsApi,
  isScanPending,
  type CreateTemplateRequest,
  type OutputFormat,
  type TemplateView,
  type UploadRequest,
} from './api';

export const documentKeys = {
  templates: ['core', 'document-templates'] as const,
  definitions: ['core', 'document-template-definitions'] as const,
  types: ['core', 'document-types'] as const,
  byOwner: (ownerEntityType: string, ownerEntityId: UUID) =>
    ['core', 'documents', ownerEntityType, ownerEntityId] as const,
};

// ------------------------------------------------------------------------------------------------ document service

/** The registered types change only with a release, so they are cached for the session. */
export function useDocumentTypes() {
  return useQuery({ queryKey: documentKeys.types, queryFn: documentsApi.types, staleTime: Infinity });
}

/**
 * The documents of one owner record. While a scan is still pending the list refreshes by itself, because the file
 * only becomes usable once Core records a clean result (US-CORE-100: within a minute).
 */
export function useDocumentsOf(ownerEntityType: string, ownerEntityId: UUID | undefined) {
  return useQuery({
    queryKey: documentKeys.byOwner(ownerEntityType, ownerEntityId ?? ''),
    queryFn: () => documentsApi.byOwner(ownerEntityType, ownerEntityId!),
    enabled: !!ownerEntityType && !!ownerEntityId,
    refetchInterval: (query) => (query.state.data && isScanPending(query.state.data) ? 5_000 : false),
  });
}

export function useUploadDocument(ownerEntityType: string, ownerEntityId: UUID | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UploadRequest) => documentsApi.upload(body),
    onSuccess: () =>
      void qc.invalidateQueries({ queryKey: documentKeys.byOwner(ownerEntityType, ownerEntityId ?? '') }),
  });
}

export function useAddDocumentVersion(ownerEntityType: string, ownerEntityId: UUID | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ documentId, file }: { documentId: UUID; file: File }) =>
      documentsApi.addVersion(documentId, file),
    onSuccess: () =>
      void qc.invalidateQueries({ queryKey: documentKeys.byOwner(ownerEntityType, ownerEntityId ?? '') }),
  });
}

/** A mutation: the signed URL is short-lived, so it is fetched at the moment the user asks for the file. */
export function useDownloadLink() {
  return useMutation({
    mutationFn: ({ documentId, versionNo }: { documentId: UUID; versionNo?: number }) =>
      documentsApi.downloadLink(documentId, versionNo),
  });
}

/** The registered definitions change only with a release, so they are cached for the session. */
export function useTemplateDefinitions() {
  return useQuery({
    queryKey: documentKeys.definitions,
    queryFn: documentTemplatesApi.definitions,
    staleTime: Infinity,
  });
}

export function useDocumentTemplates() {
  return useQuery({ queryKey: documentKeys.templates, queryFn: documentTemplatesApi.list });
}

export function useCreateTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateTemplateRequest) => documentTemplatesApi.create(body),
    onSuccess: (created) => {
      qc.setQueryData<TemplateView[]>(documentKeys.templates, (old) => (old ? [...old, created] : [created]));
      void qc.invalidateQueries({ queryKey: documentKeys.templates });
    },
  });
}

export function useUploadVersion(templateId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      file,
      validFrom,
      outputFormat,
    }: {
      file: File;
      validFrom?: ISODate | null;
      outputFormat?: OutputFormat | null;
    }) => {
      const form = new FormData();
      form.append('file', file);
      return documentTemplatesApi.uploadVersion(templateId, form, validFrom, outputFormat);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: documentKeys.templates }),
  });
}

export function usePublishVersion(templateId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (versionId: UUID) => documentTemplatesApi.publish(templateId, versionId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: documentKeys.templates }),
  });
}

/** A mutation, not a query: every click renders the template again with the current sample values. */
export function usePreviewVersion(templateId: UUID) {
  return useMutation({
    mutationFn: (versionId: UUID) => documentTemplatesApi.preview(templateId, versionId),
  });
}
