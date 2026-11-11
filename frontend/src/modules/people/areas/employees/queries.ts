import { queryOptions, useQuery } from '@tanstack/react-query';
import { http, type ISODate, type UUID } from '@/shared/api';
import type { EmployeePage, EmployeeProfile, EmployeeSearchParams } from '../../common';

export const employeesApi = {
  search: (params: EmployeeSearchParams) => http.get<EmployeePage>('/people/employees', { params: { ...params } }),
  profile: (employmentId: UUID, asOf?: ISODate) =>
    http.get<EmployeeProfile>(`/people/employees/${employmentId}`, { params: { asOf } }),
};

/**
 * Query keys. `profile` is the prefix other areas invalidate after a change that alters the profile header
 * (e.g. a new assignment, a person rename): `queryClient.invalidateQueries({ queryKey: employeeKeys.all })`.
 */
export const employeeKeys = {
  all: ['people', 'employees'] as const,
  search: (params: EmployeeSearchParams) => ['people', 'employees', 'search', params] as const,
  profile: (employmentId: UUID, asOf?: ISODate) => ['people', 'employees', employmentId, { asOf: asOf ?? null }] as const,
};

export const employeeSearchOptions = (params: EmployeeSearchParams) =>
  queryOptions({ queryKey: employeeKeys.search(params), queryFn: () => employeesApi.search(params) });

export function useEmployeeSearch(params: EmployeeSearchParams) {
  return useQuery(employeeSearchOptions(params));
}

export function useEmployeeProfile(employmentId: UUID, asOf?: ISODate) {
  return useQuery({
    queryKey: employeeKeys.profile(employmentId, asOf),
    queryFn: () => employeesApi.profile(employmentId, asOf),
  });
}
