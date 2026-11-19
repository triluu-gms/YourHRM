import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import {
  sequencesApi,
  settingsApi,
  type CreateSequenceRequest,
  type PreviewParams,
  type ResolveParams,
  type SetSettingRequest,
  type UpdateSequenceRequest,
} from './api';

export const configurationKeys = {
  all: ['core', 'configuration'] as const,
  settings: ['core', 'configuration', 'settings'] as const,
  settingList: ['core', 'configuration', 'settings', 'list'] as const,
  setting: (key: string) => ['core', 'configuration', 'settings', key] as const,
  resolve: (key: string, params: ResolveParams) => ['core', 'configuration', 'settings', key, 'resolve', params] as const,
  sequences: ['core', 'configuration', 'sequences'] as const,
  sequenceList: ['core', 'configuration', 'sequences', 'list'] as const,
};

// ------------------------------------------------------------------------------------------------ settings

export function useSettings() {
  return useQuery({ queryKey: configurationKeys.settingList, queryFn: settingsApi.list });
}

export function useSetting(key: string) {
  return useQuery({ queryKey: configurationKeys.setting(key), queryFn: () => settingsApi.get(key) });
}

/** The value that applies at a scope on a date; only runs once the caller asks (`enabled`). */
export function useResolveSetting(key: string, params: ResolveParams, enabled: boolean) {
  return useQuery({
    queryKey: configurationKeys.resolve(key, params),
    queryFn: () => settingsApi.resolve(key, params),
    enabled,
  });
}

/** Every change can alter the list's tenant value, the detail's stored values and any resolution. */
function useInvalidateSettings() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: configurationKeys.settings });
}

export function useSetSetting(key: string) {
  const invalidate = useInvalidateSettings();
  return useMutation({
    mutationFn: (body: SetSettingRequest) => settingsApi.set(key, body),
    onSuccess: invalidate,
  });
}

export function useRemoveSettingValue(key: string) {
  const invalidate = useInvalidateSettings();
  return useMutation({
    mutationFn: (valueId: UUID) => settingsApi.remove(key, valueId),
    onSuccess: invalidate,
    onError: invalidate,
  });
}

// ------------------------------------------------------------------------------------------------ number series

export function useSequences() {
  return useQuery({ queryKey: configurationKeys.sequenceList, queryFn: sequencesApi.list });
}

/** The number the next call would produce; a mutation so that every click re-reads the live counter. */
export function useSequencePreview(code: string) {
  return useMutation({ mutationFn: (params: PreviewParams) => sequencesApi.preview(code, params) });
}

export function useCreateSequence() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateSequenceRequest) => sequencesApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: configurationKeys.sequences }),
  });
}

export function useUpdateSequence() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ code, body }: { code: string; body: UpdateSequenceRequest }) => sequencesApi.update(code, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: configurationKeys.sequences }),
    // a stale-version refusal means someone else changed it: reload so the next attempt uses the new version
    onError: () => void qc.invalidateQueries({ queryKey: configurationKeys.sequences }),
  });
}
