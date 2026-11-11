import { http, type ISODate, type UUID } from '@/shared/api';

/*
 * Types mirror the Core backend records 1:1: `JobCatalogueController` request records and the views of
 * `JobTitleService` / `GradeService`. Job titles are compatible with People's `JobTitleLookup`, grade structures and
 * grades with `GradeStructureLookup` / `GradeLookup` (people/common/lookups.ts); keep them that way.
 *
 * Optimistic locking: only job titles carry a `version`. Job families, grade structures and grades have none in the
 * backend (last write wins).
 */

// ------------------------------------------------------------------------------------------------ job families

/** `JobTitleService.JobFamilyView`. */
export interface JobFamily {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  active: boolean;
}

/** `JobCatalogueController.CreateFamilyRequest`. */
export interface CreateJobFamilyRequest {
  code: string;
  nameVi: string;
  nameEn?: string | null;
}

/** `JobCatalogueController.UpdateFamilyRequest` (no version: the backend has no optimistic lock here). */
export interface UpdateJobFamilyRequest {
  nameVi: string;
  nameEn?: string | null;
  active: boolean;
}

// ------------------------------------------------------------------------------------------------ job titles

/** `JobTitleService.JobTitleView`. `hazardous` is derived: `hazardousCategory != null`. */
export interface JobTitle {
  id: UUID;
  code: string;
  jobFamilyId?: UUID | null;
  nameVi: string;
  nameEn?: string | null;
  description?: string | null;
  jdDocumentId?: UUID | null;
  defaultGradeMinId?: UUID | null;
  defaultGradeMaxId?: UUID | null;
  occupationCode?: string | null;
  /** Value of code list `JOB_HAZARD_CATEGORY` (TT 11/2020/TT-BLĐTBXH) [verify-legal]; null = not hazardous. */
  hazardousCategory?: string | null;
  hazardous: boolean;
  active: boolean;
  version: number;
}

/** `JobCatalogueController.JobTitleRequest` (the full attribute set; PUT replaces every field). */
export interface JobTitleAttributes {
  jobFamilyId: UUID | null;
  nameVi: string;
  nameEn: string | null;
  description: string | null;
  jdDocumentId: UUID | null;
  defaultGradeMinId: UUID | null;
  defaultGradeMaxId: UUID | null;
  occupationCode: string | null;
  hazardousCategory: string | null;
  active: boolean;
}

export interface CreateJobTitleRequest {
  code: string;
  attributes: JobTitleAttributes;
}

export interface UpdateJobTitleRequest {
  attributes: JobTitleAttributes;
  version: number;
}

export interface JobTitleListParams {
  q?: string;
  jobFamilyId?: UUID;
  includeInactive?: boolean;
}

// ------------------------------------------------------------------------------------------------ grades

/** `GradeService.GradeStructureView` (single-language name, no status). */
export interface GradeStructure {
  id: UUID;
  code: string;
  name: string;
}

export interface CreateGradeStructureRequest {
  code: string;
  name: string;
}

export interface RenameGradeStructureRequest {
  name: string;
}

/** `GradeService.GradeView`. No pay data (BR-CORE-021). */
export interface Grade {
  id: UUID;
  gradeStructureId: UUID;
  code: string;
  name: string;
  rankOrder: number;
  active: boolean;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

/** `JobCatalogueController.CreateGradeRequest`; `validFrom` null = today. */
export interface CreateGradeRequest {
  code: string;
  name: string;
  rankOrder: number;
  validFrom: ISODate | null;
  validTo: ISODate | null;
}

/** `JobCatalogueController.UpdateGradeRequest` (code and validFrom are immutable; no version). */
export interface UpdateGradeRequest {
  name: string;
  rankOrder: number;
  validTo: ISODate | null;
  active: boolean;
}

export interface GradeListParams {
  asOf?: ISODate;
  allDates?: boolean;
}

// ------------------------------------------------------------------------------------------------ code list

/** `MasterDataService.CodeValueView` (only the fields this area reads). */
export interface CodeValue {
  valueCode: string;
  labelVi?: string | null;
  labelEn?: string | null;
  sortOrder: number;
  active: boolean;
}

export const HAZARD_CODE_LIST = 'JOB_HAZARD_CATEGORY';

// ------------------------------------------------------------------------------------------------ clients

export const jobFamiliesApi = {
  list: (includeInactive: boolean) => http.get<JobFamily[]>('/core/job-families', { params: { includeInactive } }),
  create: (body: CreateJobFamilyRequest) => http.post<JobFamily>('/core/job-families', body),
  update: (id: UUID, body: UpdateJobFamilyRequest) => http.put<JobFamily>(`/core/job-families/${id}`, body),
};

export const jobTitlesApi = {
  list: (params: JobTitleListParams) => http.get<JobTitle[]>('/core/job-titles', { params: { ...params } }),
  get: (id: UUID) => http.get<JobTitle>(`/core/job-titles/${id}`),
  create: (body: CreateJobTitleRequest) => http.post<JobTitle>('/core/job-titles', body),
  update: (id: UUID, body: UpdateJobTitleRequest) => http.put<JobTitle>(`/core/job-titles/${id}`, body),
};

export const gradeStructuresApi = {
  list: () => http.get<GradeStructure[]>('/core/grade-structures'),
  create: (body: CreateGradeStructureRequest) => http.post<GradeStructure>('/core/grade-structures', body),
  rename: (id: UUID, body: RenameGradeStructureRequest) =>
    http.put<GradeStructure>(`/core/grade-structures/${id}`, body),
};

export const gradesApi = {
  list: (structureId: UUID, params: GradeListParams) =>
    http.get<Grade[]>(`/core/grade-structures/${structureId}/grades`, { params: { ...params } }),
  create: (structureId: UUID, body: CreateGradeRequest) =>
    http.post<Grade>(`/core/grade-structures/${structureId}/grades`, body),
  update: (id: UUID, body: UpdateGradeRequest) => http.put<Grade>(`/core/grades/${id}`, body),
};

export const codeListApi = {
  values: (listCode: string) =>
    http.get<CodeValue[]>(`/core/master-data/code-lists/${listCode}/values`, { params: { includeInactive: true } }),
};
