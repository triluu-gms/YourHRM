import { http, type UUID } from '@/shared/api';

/*
 * Employee categories (BR-CORE-026, US-CORE-023). Types mirror `EmployeeCategoryService` and the request records of
 * `EmployeeCategoryController` 1:1. The list is tenant-wide and never deleted: a category is deactivated instead.
 *
 * Optimistic locking: every update sends the `version` it read; a concurrent change is refused with 409
 * `stale-version`.
 */

/**
 * `EmployeeCategoryService.Flags` — the typed behaviour other modules key on. Workflow conditions, data scope rules
 * and payroll eligibility read these flags, never the tenant-chosen code.
 */
export interface EmployeeCategoryFlags {
  probation: boolean;
  seasonal: boolean;
  intern: boolean;
  expat: boolean;
  collaborator: boolean;
  countsInHeadcount: boolean;
}

/** The flags in the order the UI shows them; `countsInHeadcount` is not a kind, so it is listed on its own. */
export const CATEGORY_KINDS = ['probation', 'seasonal', 'intern', 'expat', 'collaborator'] as const;
export type CategoryKind = (typeof CATEGORY_KINDS)[number];

export const NO_FLAGS: EmployeeCategoryFlags = {
  probation: false,
  seasonal: false,
  intern: false,
  expat: false,
  collaborator: false,
  countsInHeadcount: true,
};

/** `EmployeeCategoryService.EmployeeCategoryView`. */
export interface EmployeeCategory {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  flags: EmployeeCategoryFlags;
  sortOrder: number;
  active: boolean;
  version: number;
}

/** `EmployeeCategoryController.CreateCategoryRequest`; a new category is always active. */
export interface CreateEmployeeCategoryRequest {
  code: string;
  nameVi: string;
  nameEn?: string | null;
  flags: EmployeeCategoryFlags;
  sortOrder?: number | null;
}

/** `EmployeeCategoryController.UpdateCategoryRequest`; the code is immutable, `version` is the one read. */
export interface UpdateEmployeeCategoryRequest {
  nameVi: string;
  nameEn?: string | null;
  flags: EmployeeCategoryFlags;
  sortOrder: number;
  active: boolean;
  version: number;
}

/** Mirrors the backend `@Pattern` of the category code. */
export const CATEGORY_CODE_PATTERN = /^[A-Z0-9_]{2,32}$/;

export const employeeCategoriesApi = {
  list: (includeInactive: boolean) =>
    http.get<EmployeeCategory[]>('/core/employee-categories', { params: { includeInactive } }),
  create: (body: CreateEmployeeCategoryRequest) => http.post<EmployeeCategory>('/core/employee-categories', body),
  update: (id: UUID, body: UpdateEmployeeCategoryRequest) =>
    http.put<EmployeeCategory>(`/core/employee-categories/${id}`, body),
};
