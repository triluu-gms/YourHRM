import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import { overtimeApi, type CreateOvertimeRequest, type RangeParams } from './api';

/** Everything of this area hangs under `['att', 'overtime']` so one invalidation refreshes every view. */
export const overtimeKeys = {
  all: ['att', 'overtime'] as const,
  request: (id: UUID) => ['att', 'overtime', 'request', id] as const,
  requests: (employmentId: UUID, p: RangeParams) => ['att', 'overtime', 'requests', employmentId, p] as const,
  results: (employmentId: UUID, p: RangeParams) => ['att', 'overtime', 'results', employmentId, p] as const,
  caps: (employmentId: UUID, date: ISODate) => ['att', 'overtime', 'caps', employmentId, date] as const,
  regime: (legalEntityId: UUID, date: ISODate) => ['att', 'overtime', 'regime', legalEntityId, date] as const,
};

function useInvalidate() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: overtimeKeys.all });
}

export const useOvertimeRequest = (id: UUID) =>
  useQuery({ queryKey: overtimeKeys.request(id), queryFn: () => overtimeApi.get(id) });

export const useOvertimeRequests = (employmentId: UUID | undefined, p: RangeParams) =>
  useQuery({
    queryKey: overtimeKeys.requests(employmentId ?? '', p),
    queryFn: () => overtimeApi.list(employmentId as UUID, p),
    enabled: !!employmentId,
  });

export const useOvertimeResults = (employmentId: UUID | undefined, p: RangeParams) =>
  useQuery({
    queryKey: overtimeKeys.results(employmentId ?? '', p),
    queryFn: () => overtimeApi.results(employmentId as UUID, p),
    enabled: !!employmentId,
  });

export const useOvertimeCaps = (employmentId: UUID | undefined, date: ISODate) =>
  useQuery({
    queryKey: overtimeKeys.caps(employmentId ?? '', date),
    queryFn: () => overtimeApi.caps(employmentId as UUID, date),
    enabled: !!employmentId,
  });

export const useOvertimeRegime = (legalEntityId: UUID | undefined, date: ISODate) =>
  useQuery({
    queryKey: overtimeKeys.regime(legalEntityId ?? '', date),
    queryFn: () => overtimeApi.regime(legalEntityId as UUID, date),
    enabled: !!legalEntityId,
  });

export function useCreateOvertime() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: CreateOvertimeRequest) => overtimeApi.create(body), onSuccess: invalidate });
}

export function useOvertimeActions(id: UUID) {
  const invalidate = useInvalidate();
  return {
    consent: useMutation({ mutationFn: () => overtimeApi.consent(id), onSuccess: invalidate }),
    submit: useMutation({ mutationFn: (capOverrideReason?: string | null) => overtimeApi.submit(id, capOverrideReason), onSuccess: invalidate }),
    cancel: useMutation({ mutationFn: (reason?: string | null) => overtimeApi.cancel(id, reason), onSuccess: invalidate }),
  };
}
