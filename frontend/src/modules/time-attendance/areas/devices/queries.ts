import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ISODate, UUID } from '@/shared/api';
import {
  devicesApi,
  mappingsApi,
  quarantineApi,
  type DeviceRequest,
  type MappingQuery,
  type MappingRequest,
  type PunchRef,
  type QuarantineQuery,
  type RematchRequest,
} from './api';

/** Everything of this area hangs under `['att', 'devices']`; punches resolved here also refresh timesheets. */
export const deviceKeys = {
  all: ['att', 'devices'] as const,
  list: (status?: string) => ['att', 'devices', 'list', status ?? null] as const,
  device: (id: UUID) => ['att', 'devices', 'device', id] as const,
  mappings: (q: MappingQuery) => ['att', 'devices', 'mappings', q] as const,
  quarantine: (q: QuarantineQuery) => ['att', 'devices', 'quarantine', q] as const,
  counts: (q: { legalEntityId?: UUID; from: ISODate; to: ISODate }) => ['att', 'devices', 'quarantine-counts', q] as const,
};

function useInvalidate() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: deviceKeys.all });
  };
}

export const useDevices = (status?: string) =>
  useQuery({ queryKey: deviceKeys.list(status), queryFn: () => devicesApi.list(status) });

export const useDevice = (id: UUID | undefined) =>
  useQuery({ queryKey: deviceKeys.device(id ?? ''), queryFn: () => devicesApi.get(id as UUID), enabled: !!id });

export function useSaveDevice() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, body }: { id?: UUID; body: DeviceRequest }) => (id ? devicesApi.update(id, body) : devicesApi.create(body)),
    onSuccess: invalidate,
  });
}

export const useMappings = (q: MappingQuery) =>
  useQuery({ queryKey: deviceKeys.mappings(q), queryFn: () => mappingsApi.list(q), placeholderData: keepPreviousData });

export function useCreateMapping() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (body: MappingRequest) => mappingsApi.create(body), onSuccess: invalidate });
}

export function useEndMapping() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ id, version, validTo }: { id: UUID; version: number; validTo: ISODate | null }) =>
      mappingsApi.end(id, version, validTo),
    onSuccess: invalidate,
  });
}

export const useQuarantine = (q: QuarantineQuery, enabled = true) =>
  useQuery({ queryKey: deviceKeys.quarantine(q), queryFn: () => quarantineApi.list(q), enabled, placeholderData: keepPreviousData });

export const useQuarantineCounts = (q: { legalEntityId?: UUID; from: ISODate; to: ISODate }, enabled = true) =>
  useQuery({ queryKey: deviceKeys.counts(q), queryFn: () => quarantineApi.counts(q), enabled });

/** Resolving punches changes days: timesheet caches (`['att', 'timesheets']`) are refreshed too. */
function useInvalidateWithTimesheets() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: deviceKeys.all });
    void qc.invalidateQueries({ queryKey: ['att', 'timesheets'] });
  };
}

export function useRematch() {
  const invalidate = useInvalidateWithTimesheets();
  return useMutation({ mutationFn: (body: RematchRequest) => quarantineApi.rematch(body), onSuccess: invalidate });
}

export function useIgnorePunches() {
  const invalidate = useInvalidateWithTimesheets();
  return useMutation({
    mutationFn: ({ punches, reason }: { punches: PunchRef[]; reason: string }) => quarantineApi.ignore(punches, reason),
    onSuccess: invalidate,
  });
}
