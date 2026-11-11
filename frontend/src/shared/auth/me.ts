import { queryOptions, useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { http, type ISODate, type UUID } from '@/shared/api';

/** `GET /api/v1/core/me` (US-CORE-041): user, tenant, locale, roles, effective permissions + scopes. */
export type ScopeType = 'TENANT' | 'LEGAL_ENTITY' | 'LOCATION' | 'ORG_UNIT' | 'LINE_MANAGER' | 'SELF';
export type FieldMaskLevel = 'NONE' | 'PARTIAL' | 'FULL' | string;

export interface Me {
  user: {
    id: UUID;
    login: string;
    email?: string | null;
    status: string;
    employmentId?: UUID | null;
    identityId?: UUID | null;
  };
  tenant: {
    id: UUID;
    slug?: string | null;
    name?: string | null;
    state?: string | null;
    defaultLocale?: string | null;
    timeZone?: string | null;
  };
  locale: string;
  asOf: ISODate;
  roles: RoleGrant[];
  permissions: EffectivePermission[];
}

export interface RoleGrant {
  assignmentId: UUID;
  roleId: UUID;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  scopeType: ScopeType;
  scopeIds: UUID[];
  includeSubtree: boolean;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

export interface EffectivePermission {
  code: string;
  scopes: { scopeType: ScopeType; scopeIds: UUID[]; includeSubtree: boolean; fieldMaskLevel: FieldMaskLevel }[];
}

export const meQueryOptions = () =>
  queryOptions({
    queryKey: ['core', 'me'] as const,
    queryFn: () => http.get<Me>('/core/me'),
    staleTime: 5 * 60_000,
  });

export function useMe() {
  return useSuspenseQuery(meQueryOptions()).data;
}

export function useMeOptional() {
  return useQuery({ ...meQueryOptions(), retry: false });
}

/** Menu/route guard: does the user hold the permission in at least one scope? The backend stays authoritative. */
export function hasPermission(me: Me | undefined, ...codes: string[]): boolean {
  if (!me) return false;
  if (codes.length === 0) return true;
  return codes.some((code) => me.permissions.some((p) => p.code === code));
}

export function usePermission(...codes: string[]): boolean {
  const me = useMe();
  return hasPermission(me, ...codes);
}

/** `GET /me/authorisation/check` for record-level decisions (buttons on a detail page). */
export type TargetType = 'TENANT' | 'LEGAL_ENTITY' | 'ORG_UNIT' | 'LOCATION' | 'EMPLOYEE';

export interface AuthorisationCheck {
  permission: string;
  targetType?: TargetType;
  targetId?: UUID;
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  locationId?: UUID;
  asOf?: ISODate;
}

export const authorisationCheckOptions = (check: AuthorisationCheck) =>
  queryOptions({
    queryKey: ['core', 'me', 'check', check] as const,
    queryFn: () =>
      http.get<{ permission: string; allowed: boolean }>('/core/me/authorisation/check', { params: { ...check } }),
    staleTime: 60_000,
  });

export function useCan(check: AuthorisationCheck, enabled = true) {
  const q = useQuery({ ...authorisationCheckOptions(check), enabled });
  return { allowed: q.data?.allowed ?? false, isLoading: q.isLoading };
}
