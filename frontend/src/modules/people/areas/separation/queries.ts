import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import { employeeKeys } from '../employees/queries';
import {
  privacyApi,
  separationApi,
  type CancelSeparationRequest,
  type ContractExpiryRequest,
  type SeparationAttributes,
  type SeparationListParams,
  type ServicePeriodRequest,
  type SubmitSeparationRequest,
  type UpdateSeparationRequest,
} from './api';

export const separationKeys = {
  all: ['people', 'separations'] as const,
  list: (params: SeparationListParams) => ['people', 'separations', 'list', params] as const,
  detail: (id: UUID) => ['people', 'separations', 'detail', id] as const,
  employment: (employmentId: UUID) => ['people', 'separations', 'employment', employmentId] as const,
  reasons: (legalEntityId?: UUID | null, asOf?: ISODate | null) =>
    ['people', 'separations', 'reasons', legalEntityId ?? '', asOf ?? ''] as const,
};

/** After any separation change: every separation list/detail and the profile header (employment phase). */
function useInvalidateAll() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: separationKeys.all });
    void qc.invalidateQueries({ queryKey: employeeKeys.all });
  };
}

export function useSeparationReasons(legalEntityId?: UUID | null, asOf?: ISODate | null, enabled = true) {
  return useQuery({
    queryKey: separationKeys.reasons(legalEntityId, asOf),
    queryFn: () => separationApi.reasons({ legalEntityId, asOf }),
    staleTime: 10 * 60_000,
    enabled,
  });
}

export function useSeparations(params: SeparationListParams) {
  return useQuery({ queryKey: separationKeys.list(params), queryFn: () => separationApi.list(params) });
}

export function useSeparation(id: UUID) {
  return useQuery({ queryKey: separationKeys.detail(id), queryFn: () => separationApi.get(id) });
}

export function useLiveSeparation(employmentId: UUID) {
  return useQuery({
    queryKey: [...separationKeys.employment(employmentId), 'live'],
    queryFn: () => separationApi.live(employmentId),
  });
}

export function useEmploymentSeparations(employmentId: UUID) {
  return useQuery({
    queryKey: [...separationKeys.employment(employmentId), 'history'],
    queryFn: () => separationApi.ofEmployment(employmentId),
  });
}

export function usePreviewSeparation(employmentId: UUID) {
  return useMutation({ mutationFn: (body: SeparationAttributes) => separationApi.preview(employmentId, body) });
}

export function useCreateSeparation(employmentId: UUID) {
  const invalidate = useInvalidateAll();
  return useMutation({
    mutationFn: (body: SeparationAttributes) => separationApi.create(employmentId, body),
    onSuccess: invalidate,
  });
}

export function useContractExpirySeparation() {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (body: ContractExpiryRequest) => separationApi.contractExpiry(body), onSuccess: invalidate });
}

export function useUpdateSeparation(id: UUID) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (body: UpdateSeparationRequest) => separationApi.update(id, body), onSuccess: invalidate, onError: invalidate });
}

export function useUploadSeparationEvidence(id: UUID) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (form: FormData) => separationApi.evidence(id, form), onSuccess: invalidate });
}

export function useSubmitSeparation(id: UUID) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (body: SubmitSeparationRequest) => separationApi.submit(id, body), onSuccess: invalidate });
}

export function useCancelSeparation(id: UUID) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (body: CancelSeparationRequest) => separationApi.cancel(id, body), onSuccess: invalidate });
}

export function useAddServicePeriod(id: UUID) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (body: ServicePeriodRequest) => separationApi.addPeriod(id, body), onSuccess: invalidate });
}

export function useRemoveServicePeriod(id: UUID) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (periodId: UUID) => separationApi.removePeriod(id, periodId), onSuccess: invalidate });
}

export function usePrivacyExport(personId: UUID) {
  return useMutation({ mutationFn: (requestRef?: string | null) => privacyApi.export(personId, requestRef) });
}

export function useAnonymise(personId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (requestRef?: string | null) => privacyApi.anonymise(personId, requestRef),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['people'] }),
  });
}
