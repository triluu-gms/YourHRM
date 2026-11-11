import type { ISODate, UUID } from '@/shared/api';

/** `EmployeeDirectoryService.STATUSES` (hr.employment.status). */
export type EmploymentStatus = 'PRE_HIRE' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED' | 'CANCELLED';
export const EMPLOYMENT_STATUSES: EmploymentStatus[] = ['PRE_HIRE', 'ACTIVE', 'SUSPENDED', 'TERMINATED', 'CANCELLED'];

/** `EmployeeDirectoryService.EmployeeRow` (GET /people/employees). National id arrives masked. */
export interface EmployeeRow {
  employmentId: UUID;
  personId: UUID;
  employeeNo: string;
  fullName: string;
  nationalIdMasked?: string | null;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  positionId?: UUID | null;
  jobTitleId?: UUID | null;
  locationId?: UUID | null;
  status: EmploymentStatus;
  hireDate?: ISODate | null;
  endDate?: ISODate | null;
  workerCategoryCode?: string | null;
  employeeCategoryCode?: string | null;
}

/** `EmployeeDirectoryService.Page` — note: `total` before `page`, same shape as the shared `Page<T>`. */
export interface EmployeePage {
  items: EmployeeRow[];
  total: number;
  page: number;
  size: number;
}

export interface EmployeeSearchParams {
  q?: string;
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  status?: EmploymentStatus;
  workerCategoryCode?: string;
  employeeCategoryCode?: string;
  asOf?: ISODate;
  page?: number;
  size?: number;
}

/** `EmployeeDirectoryService.EmploymentSummary`. */
export interface EmploymentSummary {
  employmentId: UUID;
  employeeNo: string;
  legalEntityId?: UUID | null;
  hireType?: string | null;
  status: EmploymentStatus;
  hireDate?: ISODate | null;
  endDate?: ISODate | null;
  seniorityDate?: ISODate | null;
  workerCategoryCode?: string | null;
  employeeCategoryCode?: string | null;
}

/** `EmployeeDirectoryService.AssignmentSummary`. */
export interface AssignmentSummary {
  assignmentId: UUID;
  assignmentType?: string | null;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  positionId?: UUID | null;
  jobTitleId?: UUID | null;
  gradeId?: UUID | null;
  locationId?: UUID | null;
  managerEmploymentId?: UUID | null;
  managerName?: string | null;
  fte?: number | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

/** `PersonService.PersonView`; identifiers are masked server-side (BR-CORE-034), clear values only via unmask. */
export interface PersonView {
  id: UUID;
  familyName?: string | null;
  middleName?: string | null;
  givenName?: string | null;
  fullName: string;
  latinName?: string | null;
  preferredName?: string | null;
  birthDate?: ISODate | null;
  genderCode?: string | null;
  nationalityCode?: string | null;
  nationalIdType?: string | null;
  nationalIdMasked?: string | null;
  taxCodeMasked?: string | null;
  siNumberMasked?: string | null;
  hiCardNoMasked?: string | null;
  hiFacilityCode?: string | null;
  siBookCustody?: string | null;
  ethnicityRecorded: boolean;
  religionRecorded: boolean;
  verificationStatus?: string | null;
  source?: string | null;
  anonymised: boolean;
  version: number;
}

/**
 * Profile tab keys the backend may return in `EmployeeProfile.tabs` (key → API path); a key is present only when the
 * caller holds the tab's permission for this employee (US-PPL-016).
 */
export type ProfileTabKey =
  | 'person'
  | 'addresses'
  | 'contacts'
  | 'identityDocuments'
  | 'qualifications'
  | 'taxResidency'
  | 'workPermits'
  | 'specialStatuses'
  | 'assignments'
  | 'contracts'
  | 'decisions'
  | 'compensation'
  | 'familyMembers'
  | 'dependants'
  | 'bankAccounts'
  | 'separation';

/** `EmployeeDirectoryService.Profile` (GET /people/employees/{employmentId}). */
export interface EmployeeProfile {
  employment: EmploymentSummary;
  person: PersonView;
  otherEmployments: EmploymentSummary[];
  primaryAssignment?: AssignmentSummary | null;
  tabs: Partial<Record<ProfileTabKey, string>>;
  asOf: ISODate;
}

/** Props every employee-profile tab component receives from the profile shell. */
export interface ProfileTabProps {
  employmentId: UUID;
  personId: UUID;
  profile: EmployeeProfile;
}
