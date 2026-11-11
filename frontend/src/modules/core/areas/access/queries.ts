import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import {
  permissionsApi,
  roleAssignmentsApi,
  rolesApi,
  scopeLookupApi,
  supportSessionsApi,
  usersApi,
  type CopyRoleRequest,
  type CreateRoleRequest,
  type CreateUserRequest,
  type GrantRequest,
  type LegalEntityOption,
  type LocationOption,
  type OrgUnitOption,
  type ReplacePermissionsRequest,
  type UpdateRoleRequest,
  type UpdateUserRequest,
  type UserSearchParams,
} from './api';

export const accessKeys = {
  users: ['core', 'access', 'users'] as const,
  userSearch: (params: UserSearchParams) => ['core', 'access', 'users', 'search', params] as const,
  user: (id: UUID) => ['core', 'access', 'users', id] as const,
  assignments: (userId: UUID, includeHistory: boolean) =>
    ['core', 'access', 'users', userId, 'role-assignments', { includeHistory }] as const,
  roles: ['core', 'access', 'roles'] as const,
  roleList: (q?: string) => ['core', 'access', 'roles', 'list', q ?? ''] as const,
  role: (id: UUID) => ['core', 'access', 'roles', id] as const,
  permissions: (includeInactive: boolean) => ['core', 'access', 'permissions', { includeInactive }] as const,
  supportSessions: ['core', 'access', 'support-sessions'] as const,
  supportEvents: (grantId: UUID) => ['core', 'access', 'support-sessions', grantId, 'events'] as const,
  lookup: (kind: string) => ['core', 'access', 'lookup', kind] as const,
};

// ------------------------------------------------------------------------------------------------ users

export const userSearchOptions = (params: UserSearchParams) =>
  queryOptions({ queryKey: accessKeys.userSearch(params), queryFn: () => usersApi.search(params) });

export const userOptions = (userId: UUID) =>
  queryOptions({ queryKey: accessKeys.user(userId), queryFn: () => usersApi.get(userId) });

export function useUserSearch(params: UserSearchParams) {
  return useQuery(userSearchOptions(params));
}

export function useUser(userId: UUID) {
  return useQuery(userOptions(userId));
}

export function useCreateUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateUserRequest) => usersApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.users }),
  });
}

export function useUpdateUser(userId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateUserRequest) => usersApi.update(userId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.users }),
    onError: () => void qc.invalidateQueries({ queryKey: accessKeys.user(userId) }),
  });
}

export function useUserActivation(userId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (action: 'activate' | 'deactivate') =>
      action === 'activate' ? usersApi.activate(userId) : usersApi.deactivate(userId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.users }),
  });
}

// -------------------------------------------------------------------------------------- role assignments

export function useRoleAssignments(userId: UUID, includeHistory: boolean) {
  return useQuery({
    queryKey: accessKeys.assignments(userId, includeHistory),
    queryFn: () => roleAssignmentsApi.list(userId, includeHistory),
  });
}

export function useGrantRole(userId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: GrantRequest) => roleAssignmentsApi.grant(userId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.user(userId) }),
  });
}

export function useRevokeRole(userId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (assignmentId: UUID) => roleAssignmentsApi.revoke(userId, assignmentId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.user(userId) }),
  });
}

// ------------------------------------------------------------------------------------------------ roles

export const roleListOptions = (q?: string) =>
  queryOptions({ queryKey: accessKeys.roleList(q), queryFn: () => rolesApi.list(q) });

export const roleOptions = (roleId: UUID) =>
  queryOptions({ queryKey: accessKeys.role(roleId), queryFn: () => rolesApi.get(roleId) });

export function useRoles(q?: string) {
  return useQuery(roleListOptions(q));
}

export function useRole(roleId: UUID) {
  return useQuery(roleOptions(roleId));
}

export function useCreateRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateRoleRequest) => rolesApi.create(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.roles }),
  });
}

export function useUpdateRole(roleId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateRoleRequest) => rolesApi.update(roleId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.roles }),
    onError: () => void qc.invalidateQueries({ queryKey: accessKeys.role(roleId) }),
  });
}

export function useDeleteRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (roleId: UUID) => rolesApi.remove(roleId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.roles }),
  });
}

export function useCopyRole(roleId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CopyRoleRequest) => rolesApi.copy(roleId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.roles }),
  });
}

export function useReplaceRolePermissions(roleId: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ReplacePermissionsRequest) => rolesApi.replacePermissions(roleId, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: accessKeys.roles }),
    onError: () => void qc.invalidateQueries({ queryKey: accessKeys.role(roleId) }),
  });
}

// ------------------------------------------------------------------------------------------- permissions

export const permissionCatalogueOptions = (includeInactive = false) =>
  queryOptions({
    queryKey: accessKeys.permissions(includeInactive),
    queryFn: () => permissionsApi.list({ includeInactive }),
    staleTime: 10 * 60_000,
  });

export function usePermissionCatalogue(includeInactive = false) {
  return useQuery(permissionCatalogueOptions(includeInactive));
}

// --------------------------------------------------------------------------------------- support sessions

export function useSupportSessions() {
  return useQuery({ queryKey: accessKeys.supportSessions, queryFn: supportSessionsApi.list });
}

export function useSupportEvents(grantId: UUID | null) {
  return useQuery({
    queryKey: accessKeys.supportEvents(grantId ?? ''),
    queryFn: () => supportSessionsApi.events(grantId as UUID),
    enabled: grantId !== null,
  });
}

// ------------------------------------------------------------------------------------------- lookups

export function useScopeLookup(kind: 'LEGAL_ENTITY' | 'LOCATION' | 'ORG_UNIT' | null) {
  return useQuery<LegalEntityOption[] | LocationOption[] | OrgUnitOption[]>({
    queryKey: accessKeys.lookup(kind ?? 'none'),
    queryFn: (): Promise<LegalEntityOption[] | LocationOption[] | OrgUnitOption[]> => {
      if (kind === 'LEGAL_ENTITY') return scopeLookupApi.legalEntities();
      if (kind === 'LOCATION') return scopeLookupApi.locations();
      return scopeLookupApi.orgUnits();
    },
    enabled: kind !== null,
    staleTime: 5 * 60_000,
  });
}
