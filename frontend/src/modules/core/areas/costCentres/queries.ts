import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import {
  costCentresApi,
  type CostCentreListParams,
  type CreateCostCentreRequest,
  type UpdateCostCentreRequest,
} from './api';

export const costCentreKeys = {
  all: ['core', 'cost-centres'] as const,
  list: (params: CostCentreListParams) => ['core', 'cost-centres', 'list', params] as const,
};

export function useCostCentres(params: CostCentreListParams) {
  return useQuery({ queryKey: costCentreKeys.list(params), queryFn: () => costCentresApi.list(params) });
}

export function useCreateCostCentre() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateCostCentreRequest) => costCentresApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: costCentreKeys.all }),
  });
}

export function useUpdateCostCentre() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: UpdateCostCentreRequest }) => costCentresApi.update(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: costCentreKeys.all }),
    // A stale-version refusal means someone else changed it: reload so the next attempt uses the new version.
    onError: () => void qc.invalidateQueries({ queryKey: costCentreKeys.all }),
  });
}
