import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import {
  employeeCategoriesApi,
  type CreateEmployeeCategoryRequest,
  type UpdateEmployeeCategoryRequest,
} from './api';

export const employeeCategoryKeys = {
  all: ['core', 'employee-categories'] as const,
  list: (includeInactive: boolean) => ['core', 'employee-categories', 'list', { includeInactive }] as const,
};

export function useEmployeeCategories(includeInactive = false) {
  return useQuery({
    queryKey: employeeCategoryKeys.list(includeInactive),
    queryFn: () => employeeCategoriesApi.list(includeInactive),
  });
}

export function useCreateEmployeeCategory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateEmployeeCategoryRequest) => employeeCategoriesApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employeeCategoryKeys.all }),
  });
}

export function useUpdateEmployeeCategory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: UpdateEmployeeCategoryRequest }) =>
      employeeCategoriesApi.update(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: employeeCategoryKeys.all }),
    // A stale-version refusal means someone else changed it: reload so the next attempt uses the new version.
    onError: () => void qc.invalidateQueries({ queryKey: employeeCategoryKeys.all }),
  });
}
