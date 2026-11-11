import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import {
  codeListApi,
  gradeStructuresApi,
  gradesApi,
  HAZARD_CODE_LIST,
  jobFamiliesApi,
  jobTitlesApi,
  type CreateGradeRequest,
  type CreateGradeStructureRequest,
  type CreateJobFamilyRequest,
  type CreateJobTitleRequest,
  type GradeListParams,
  type JobTitleListParams,
  type RenameGradeStructureRequest,
  type UpdateGradeRequest,
  type UpdateJobFamilyRequest,
  type UpdateJobTitleRequest,
} from './api';

export const jobCatalogueKeys = {
  all: ['core', 'job-catalogue'] as const,
  families: ['core', 'job-catalogue', 'job-families'] as const,
  familyList: (includeInactive: boolean) => ['core', 'job-catalogue', 'job-families', { includeInactive }] as const,
  titles: ['core', 'job-catalogue', 'job-titles'] as const,
  titleList: (params: JobTitleListParams) => ['core', 'job-catalogue', 'job-titles', 'list', params] as const,
  title: (id: UUID) => ['core', 'job-catalogue', 'job-titles', id] as const,
  structures: ['core', 'job-catalogue', 'grade-structures'] as const,
  grades: ['core', 'job-catalogue', 'grades'] as const,
  gradeList: (structureId: UUID, params: GradeListParams) =>
    ['core', 'job-catalogue', 'grades', structureId, params] as const,
  codeList: (listCode: string) => ['core', 'job-catalogue', 'code-list', listCode] as const,
};

// ------------------------------------------------------------------------------------------------ job families

export function useJobFamilies(includeInactive = false) {
  return useQuery({
    queryKey: jobCatalogueKeys.familyList(includeInactive),
    queryFn: () => jobFamiliesApi.list(includeInactive),
  });
}

export function useCreateJobFamily() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateJobFamilyRequest) => jobFamiliesApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: jobCatalogueKeys.families }),
  });
}

export function useUpdateJobFamily() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: UpdateJobFamilyRequest }) => jobFamiliesApi.update(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: jobCatalogueKeys.families }),
  });
}

// ------------------------------------------------------------------------------------------------ job titles

export function useJobTitles(params: JobTitleListParams) {
  return useQuery({ queryKey: jobCatalogueKeys.titleList(params), queryFn: () => jobTitlesApi.list(params) });
}

export function useJobTitle(id: UUID) {
  return useQuery({ queryKey: jobCatalogueKeys.title(id), queryFn: () => jobTitlesApi.get(id) });
}

export function useCreateJobTitle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateJobTitleRequest) => jobTitlesApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: jobCatalogueKeys.titles }),
  });
}

export function useUpdateJobTitle(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateJobTitleRequest) => jobTitlesApi.update(id, body),
    onSuccess: (data) => {
      qc.setQueryData(jobCatalogueKeys.title(id), data);
      void qc.invalidateQueries({ queryKey: jobCatalogueKeys.titles });
    },
    // A stale-version refusal means someone else changed it: reload so the next attempt uses the new version.
    onError: () => void qc.invalidateQueries({ queryKey: jobCatalogueKeys.titles }),
  });
}

// ------------------------------------------------------------------------------------------------ grade structures

export function useGradeStructures() {
  return useQuery({ queryKey: jobCatalogueKeys.structures, queryFn: gradeStructuresApi.list });
}

export function useCreateGradeStructure() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateGradeStructureRequest) => gradeStructuresApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: jobCatalogueKeys.structures }),
  });
}

export function useRenameGradeStructure() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: RenameGradeStructureRequest }) => gradeStructuresApi.rename(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: jobCatalogueKeys.structures }),
  });
}

// ------------------------------------------------------------------------------------------------ grades

export function useGrades(structureId: UUID | undefined, params: GradeListParams) {
  return useQuery({
    queryKey: jobCatalogueKeys.gradeList(structureId ?? '', params),
    queryFn: () => gradesApi.list(structureId as UUID, params),
    enabled: !!structureId,
  });
}

/** Every grade (all periods) of every structure: resolves default grade ranges of job titles by id. */
export function useAllGrades() {
  const structures = useGradeStructures();
  const grades = useQueries({
    queries: (structures.data ?? []).map((s) => ({
      queryKey: jobCatalogueKeys.gradeList(s.id, { allDates: true }),
      queryFn: () => gradesApi.list(s.id, { allDates: true }),
    })),
  });
  return {
    structures: structures.data ?? [],
    grades: grades.flatMap((g) => g.data ?? []),
    isLoading: structures.isLoading || grades.some((g) => g.isLoading),
  };
}

export function useCreateGrade(structureId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateGradeRequest) => gradesApi.create(structureId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: jobCatalogueKeys.grades }),
  });
}

export function useUpdateGrade() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: UUID; body: UpdateGradeRequest }) => gradesApi.update(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: jobCatalogueKeys.grades }),
  });
}

// ------------------------------------------------------------------------------------------------ code list

/** Statutory heavy / hazardous categories (inactive included so stored codes still resolve to a label). */
export function useHazardCategories() {
  return useQuery({
    queryKey: jobCatalogueKeys.codeList(HAZARD_CODE_LIST),
    queryFn: () => codeListApi.values(HAZARD_CODE_LIST),
    staleTime: 10 * 60_000,
  });
}
