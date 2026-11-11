import { http, type ISODate, type ISOInstant, type Page, type UUID } from '@/shared/api';

// ------------------------------------------------------------------------------------------------ users

/** `core.user_account.status` (backend `UserStatus`). */
export type UserStatus = 'INVITED' | 'ACTIVE' | 'FORMER_EMPLOYEE' | 'DEACTIVATED' | 'LOCKED';
export const USER_STATUSES: UserStatus[] = ['INVITED', 'ACTIVE', 'FORMER_EMPLOYEE', 'DEACTIVATED', 'LOCKED'];

/** `UserService.UserView`. */
export interface UserView {
  id: UUID;
  login: string;
  displayName?: string | null;
  email?: string | null;
  mobile?: string | null;
  employmentId?: UUID | null;
  personId?: UUID | null;
  identityId?: UUID | null;
  locale?: string | null;
  status: UserStatus;
  expiresAt?: ISOInstant | null;
  accessRevokedAt?: ISOInstant | null;
  createdAt?: ISOInstant | null;
  createdBy?: UUID | null;
  updatedAt?: ISOInstant | null;
  updatedBy?: UUID | null;
  version: number;
}

/** `UserController.CreateUserRequest`. */
export interface CreateUserRequest {
  login: string;
  displayName?: string | null;
  email?: string | null;
  mobile?: string | null;
  locale?: string | null;
  identityId?: UUID | null;
  employmentId?: UUID | null;
  personId?: UUID | null;
  expiresAt?: ISOInstant | null;
}

/** `UserController.UpdateUserRequest` (PUT with the version read). */
export interface UpdateUserRequest extends Omit<CreateUserRequest, 'login'> {
  version: number;
}

export interface UserSearchParams {
  q?: string;
  status?: UserStatus;
  page?: number;
  size?: number;
}

export const usersApi = {
  search: (params: UserSearchParams) => http.get<Page<UserView>>('/core/users', { params: { ...params } }),
  get: (userId: UUID) => http.get<UserView>(`/core/users/${userId}`),
  create: (body: CreateUserRequest) => http.post<UserView>('/core/users', body),
  update: (userId: UUID, body: UpdateUserRequest) => http.put<UserView>(`/core/users/${userId}`, body),
  activate: (userId: UUID) => http.post<UserView>(`/core/users/${userId}/activate`),
  deactivate: (userId: UUID) => http.post<UserView>(`/core/users/${userId}/deactivate`),
};

// -------------------------------------------------------------------------------------- role assignments

/** `vn.yourhrm.shared.auth.api.ScopeType` (role_assignment_scope_ck). */
export type AssignmentScopeType =
  | 'TENANT'
  | 'LEGAL_ENTITY'
  | 'LOCATION'
  | 'ORG_UNIT'
  | 'SELF'
  | 'DIRECT_REPORTS'
  | 'ALL_REPORTS';
export const SCOPE_TYPES: AssignmentScopeType[] = [
  'TENANT',
  'LEGAL_ENTITY',
  'LOCATION',
  'ORG_UNIT',
  'SELF',
  'DIRECT_REPORTS',
  'ALL_REPORTS',
];
/** Scope kinds whose `scopeIds` must be non-empty (`ScopeType.requiresIds`). */
export function scopeRequiresIds(scopeType: AssignmentScopeType | undefined): boolean {
  return scopeType === 'LEGAL_ENTITY' || scopeType === 'LOCATION' || scopeType === 'ORG_UNIT';
}

/** `RoleAssignmentService.AssignmentView`. */
export interface AssignmentView {
  id: UUID;
  userId: UUID;
  roleId: UUID;
  roleCode: string;
  scopeType: AssignmentScopeType;
  scopeIds: UUID[];
  includeSubtree: boolean;
  validFrom: ISODate;
  validTo?: ISODate | null;
  activeToday: boolean;
  grantedBy?: UUID | null;
  createdAt?: ISOInstant | null;
}

/** `RoleAssignmentController.GrantRequest`; `validTo` is exclusive, null = open-ended. */
export interface GrantRequest {
  roleId: UUID;
  scopeType: AssignmentScopeType;
  scopeIds?: UUID[];
  includeSubtree?: boolean;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
}

export const roleAssignmentsApi = {
  list: (userId: UUID, includeHistory = false) =>
    http.get<AssignmentView[]>(`/core/users/${userId}/role-assignments`, { params: { includeHistory } }),
  grant: (userId: UUID, body: GrantRequest) => http.post<AssignmentView>(`/core/users/${userId}/role-assignments`, body),
  revoke: (userId: UUID, assignmentId: UUID) => http.delete(`/core/users/${userId}/role-assignments/${assignmentId}`),
};

// ------------------------------------------------------------------------------------------------ roles

/** `core.role_permission.field_mask_level` (backend `FieldMaskLevel`). */
export type RoleFieldMaskLevel = 'NONE' | 'PII' | 'PII_AND_PAY';
export const FIELD_MASK_LEVELS: RoleFieldMaskLevel[] = ['NONE', 'PII', 'PII_AND_PAY'];

/** `RoleService.Grant` / `RoleController.PermissionGrant`. */
export interface PermissionGrant {
  code: string;
  fieldMaskLevel?: RoleFieldMaskLevel | null;
}

/** `RoleService.RoleSummary`. */
export interface RoleSummary {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  system: boolean;
  permissionCount: number;
  version: number;
}

/** `RoleService.RoleDetail`. */
export interface RoleDetail {
  id: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  system: boolean;
  templateVersion?: number | null;
  version: number;
  permissions: PermissionGrant[];
}

export interface CreateRoleRequest {
  code: string;
  nameVi: string;
  nameEn: string;
  permissions?: PermissionGrant[];
}

export interface UpdateRoleRequest {
  nameVi: string;
  nameEn: string;
  version: number;
}

export interface CopyRoleRequest {
  code: string;
  nameVi: string;
  nameEn: string;
}

export interface ReplacePermissionsRequest {
  permissions: PermissionGrant[];
  version?: number | null;
}

export const rolesApi = {
  list: (q?: string) => http.get<RoleSummary[]>('/core/roles', { params: { q } }),
  get: (roleId: UUID) => http.get<RoleDetail>(`/core/roles/${roleId}`),
  create: (body: CreateRoleRequest) => http.post<RoleDetail>('/core/roles', body),
  update: (roleId: UUID, body: UpdateRoleRequest) => http.put<RoleDetail>(`/core/roles/${roleId}`, body),
  remove: (roleId: UUID) => http.delete(`/core/roles/${roleId}`),
  copy: (roleId: UUID, body: CopyRoleRequest) => http.post<RoleDetail>(`/core/roles/${roleId}/copy`, body),
  replacePermissions: (roleId: UUID, body: ReplacePermissionsRequest) =>
    http.put<RoleDetail>(`/core/roles/${roleId}/permissions`, body),
};

// ------------------------------------------------------------------------------------------- permissions

/** `PermissionQueryService.PermissionView` (global catalogue, US-CORE-033). */
export interface PermissionView {
  code: string;
  module: string;
  functionCode: string;
  action: string;
  nameVi: string;
  nameEn?: string | null;
  sensitive: boolean;
  privileged: boolean;
  requiresStepUp: boolean;
  active: boolean;
}

export const permissionsApi = {
  list: (params?: { module?: string; includeInactive?: boolean }) =>
    http.get<PermissionView[]>('/core/permissions', { params: { ...params } }),
};

// --------------------------------------------------------------------------------------- support sessions

/** `SupportSessionService.SupportSessionView` (BR-CORE-041). */
export interface SupportSessionView {
  supportGrantId: UUID;
  operatorId?: UUID | null;
  firstSeen: ISOInstant;
  lastSeen: ISOInstant;
  requests: number;
  refusedRequests: number;
  changes: number;
}

/** `SupportSessionService.SupportEventView`. */
export interface SupportEventView {
  occurredAt: ISOInstant;
  action: string;
  objectType?: string | null;
  objectId?: UUID | null;
  details?: Record<string, string> | null;
}

export const supportSessionsApi = {
  list: () => http.get<SupportSessionView[]>('/core/support-sessions'),
  events: (supportGrantId: UUID) => http.get<SupportEventView[]>(`/core/support-sessions/${supportGrantId}/events`),
};

// ------------------------------------------------------------------------------- scope lookups (minimal)

/** Minimal shapes of the employer-structure list endpoints, only what the scope pickers need. */
export interface LegalEntityOption {
  id: UUID;
  code: string;
  status?: string | null;
  current?: { legalNameVi?: string | null; legalNameEn?: string | null; shortName?: string | null } | null;
}

export interface LocationOption {
  id: UUID;
  code: string;
  legalEntityId?: UUID | null;
  status?: string | null;
  current?: { name?: string | null } | null;
}

export interface OrgUnitOption {
  id: UUID;
  code: string;
  nameVi?: string | null;
  nameEn?: string | null;
  parentId?: UUID | null;
  depth?: number | null;
  status?: string | null;
}

export const scopeLookupApi = {
  legalEntities: () => http.get<LegalEntityOption[]>('/core/legal-entities'),
  locations: () => http.get<LocationOption[]>('/core/locations'),
  orgUnits: () => http.get<OrgUnitOption[]>('/core/org-units'),
};
