import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

// ------------------------------------------------------------------------------------------- tax residency

export type ResidencyStatus = 'RESIDENT' | 'NON_RESIDENT';
/** `TaxResidencyService.RESIDENT_BASES` / `NON_RESIDENT_BASES` (status ↔ basis must match). */
export const RESIDENCY_BASES: Record<ResidencyStatus, string[]> = {
  RESIDENT: ['DAYS_183', 'MONTHS_12', 'PERMANENT_RESIDENCE', 'LEASE_183'],
  NON_RESIDENT: ['NOT_MET', 'TREATY_TIE_BREAKER'],
};

/** `TaxResidencyService.ResidencyView` (also a Payroll proposal when `source` is a proposal). */
export interface ResidencyView {
  id: UUID;
  personId: UUID;
  status: ResidencyStatus;
  basis: string;
  validFrom: ISODate;
  validTo?: ISODate | null;
  arrivalDate?: ISODate | null;
  departureDate?: ISODate | null;
  taxYear?: number | null;
  daysInVn?: number | null;
  source?: string | null;
  proposalRef?: string | null;
  approvalStatus?: string | null;
  approvedBy?: UUID | null;
  approvedAt?: ISOInstant | null;
  evidenceDocumentId?: UUID | null;
  version: number;
}

/** `PersonComplianceController.ResidencyRequest`. */
export interface ResidencyRequest {
  status: ResidencyStatus;
  basis: string;
  validFrom: ISODate;
  validTo?: ISODate | null;
  arrivalDate?: ISODate | null;
  departureDate?: ISODate | null;
  taxYear?: number | null;
  daysInVn?: number | null;
}

/** `TaxResidencyService.Submitted`: the change waits for approval (`hr.TAX_RESIDENCY`). */
export interface ResidencySubmitted {
  residency: ResidencyView;
  workflowInstanceId?: UUID | null;
  workflowStatus?: string | null;
}

/** `TaxResidencyService.PresenceView`. */
export interface PresenceView {
  id: UUID;
  personId: UUID;
  entryDate: ISODate;
  exitDate?: ISODate | null;
  evidenceDocumentId?: UUID | null;
  version: number;
}

// ------------------------------------------------------------------------------------------- special statuses

/** `SpecialStatusService.TYPES`; MINOR / ELDERLY_WORKER are derived from the birth date and never entered. */
export const SPECIAL_STATUS_TYPES = [
  'PREGNANCY',
  'CHILD_UNDER_12M',
  'DISABILITY',
  'MINOR',
  'ELDERLY_WORKER',
  'HAZARDOUS_JOB',
  'UNION_MEMBER',
  'PARTY_MEMBER',
  'YOUTH_UNION_MEMBER',
] as const;
export const DERIVED_STATUS_TYPES = ['MINOR', 'ELDERLY_WORKER'];
/** Political data: needs the person's consent (BR-PPL-151). */
export const CONSENT_STATUS_TYPES = ['PARTY_MEMBER', 'YOUTH_UNION_MEMBER'];

/** `SpecialStatusService.StatusView`. */
export interface StatusView {
  id: UUID;
  personId: UUID;
  employmentId?: UUID | null;
  statusType: string;
  sensitive: boolean;
  validFrom: ISODate;
  validTo?: ISODate | null;
  classificationCode?: string | null;
  derived: boolean;
  evidenceDocumentId?: UUID | null;
  status: string;
  version: number;
}

/** `PersonComplianceController.StatusRequest`. `details` is stored encrypted. */
export interface StatusRequest {
  statusType: string;
  employmentId?: UUID | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
  classificationCode?: string | null;
  details?: Record<string, unknown> | null;
  overrideReason?: string | null;
}

/** `PersonComplianceController.ChangeStatusRequest`. */
export interface ChangeStatusRequest {
  version: number;
  validTo?: ISODate | null;
  classificationCode?: string | null;
  details?: Record<string, unknown> | null;
  overrideReason?: string | null;
}

// ------------------------------------------------------------------------------------------- work permits

export const PERMIT_TYPES = ['WORK_PERMIT', 'EXEMPTION_CERT', 'NOT_REQUIRED_CONFIRMATION'] as const;
export const PERMIT_STATUSES = ['DRAFT', 'APPLIED', 'ACTIVE', 'EXPIRED', 'REVOKED', 'CANCELLED'] as const;
/** `WorkPermitService.TRANSITIONS`: allowed next statuses. */
export const PERMIT_TRANSITIONS: Record<string, string[]> = {
  DRAFT: ['DRAFT', 'APPLIED', 'ACTIVE', 'CANCELLED'],
  APPLIED: ['APPLIED', 'ACTIVE', 'CANCELLED'],
  ACTIVE: ['ACTIVE', 'EXPIRED', 'REVOKED'],
  EXPIRED: ['EXPIRED'],
  REVOKED: ['REVOKED'],
  CANCELLED: ['CANCELLED'],
};

/** `WorkPermitService.PermitView`. */
export interface PermitView {
  id: UUID;
  personId: UUID;
  employmentId?: UUID | null;
  permitType: string;
  permitNo?: string | null;
  jobTitleOnPermit?: string | null;
  workLocationText?: string | null;
  issuingAuthority?: string | null;
  issueDate?: ISODate | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
  renewalOfId?: UUID | null;
  scanDocumentId?: UUID | null;
  status: string;
  version: number;
}

/** `PersonComplianceController.PermitRequest`. */
export interface PermitRequest {
  employmentId?: UUID | null;
  permitType: string;
  permitNo?: string | null;
  jobTitleOnPermit?: string | null;
  workLocationText?: string | null;
  issuingAuthority?: string | null;
  issueDate?: ISODate | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
  renewalOfId?: UUID | null;
  status?: string | null;
}

/** `WorkPermitService.Expiring`. */
export interface ExpiringPermit {
  permitId: UUID;
  personId: UUID;
  employmentId?: UUID | null;
  employeeNo?: string | null;
  fullName: string;
  permitType: string;
  lastValidDay: ISODate;
  daysLeft: number;
  renewalInProgress: boolean;
}

/** `IdentityDocumentService.Expiring`. */
export interface ExpiringIdentityDocument {
  documentId: UUID;
  personId: UUID;
  fullName: string;
  employeeNos: string[];
  docType: string;
  docNoMasked?: string | null;
  expiryDate: ISODate;
  daysLeft: number;
}

/** `EmploymentService.EmploymentView` (subset): used to link a person to an employee profile. */
export interface PersonEmployment {
  id: UUID;
  employeeNo: string;
  status: string;
  pitPrimary: boolean;
}


const base = (personId: UUID) => `/people/persons/${personId}`;

export const complianceApi = {
  taxResidencies: (personId: UUID) => http.get<ResidencyView[]>(`${base(personId)}/tax-residencies`),
  submitTaxResidency: (personId: UUID, body: ResidencyRequest) =>
    http.post<ResidencySubmitted>(`${base(personId)}/tax-residencies`, body),
  attachResidencyEvidence: (personId: UUID, id: UUID, documentId: UUID) =>
    http.put<ResidencyView>(`${base(personId)}/tax-residencies/${id}/evidence`, { documentId }),

  proposals: () => http.get<ResidencyView[]>('/people/persons/residency-proposals'),
  approveProposal: (id: UUID, version: number) =>
    http.post<ResidencyView>(`/people/persons/residency-proposals/${id}/approve`, { version }),
  rejectProposal: (id: UUID, version: number, reason: string) =>
    http.post<ResidencyView>(`/people/persons/residency-proposals/${id}/reject`, { version, reason }),

  presence: (personId: UUID) => http.get<PresenceView[]>(`${base(personId)}/vn-presence-periods`),
  addPresence: (personId: UUID, body: { entryDate: ISODate; exitDate?: ISODate | null }) =>
    http.post<PresenceView>(`${base(personId)}/vn-presence-periods`, body),
  changePresence: (personId: UUID, id: UUID, body: { version: number; entryDate: ISODate; exitDate?: ISODate | null }) =>
    http.put<PresenceView>(`${base(personId)}/vn-presence-periods/${id}`, body),

  specialStatuses: (personId: UUID, includeCancelled: boolean) =>
    http.get<StatusView[]>(`${base(personId)}/special-statuses`, { params: { includeCancelled } }),
  addSpecialStatus: (personId: UUID, body: StatusRequest) => http.post<StatusView>(`${base(personId)}/special-statuses`, body),
  changeSpecialStatus: (personId: UUID, id: UUID, body: ChangeStatusRequest) =>
    http.put<StatusView>(`${base(personId)}/special-statuses/${id}`, body),
  cancelSpecialStatus: (personId: UUID, id: UUID, version: number) =>
    http.post<StatusView>(`${base(personId)}/special-statuses/${id}/cancel`, { version }),
  attachStatusEvidence: (personId: UUID, id: UUID, documentId: UUID) =>
    http.put<StatusView>(`${base(personId)}/special-statuses/${id}/evidence`, { documentId }),

  workPermits: (personId: UUID) => http.get<PermitView[]>(`${base(personId)}/work-permits`),
  addWorkPermit: (personId: UUID, body: PermitRequest) => http.post<PermitView>(`${base(personId)}/work-permits`, body),
  changeWorkPermit: (personId: UUID, id: UUID, version: number, permit: PermitRequest) =>
    http.put<PermitView>(`${base(personId)}/work-permits/${id}`, { version, permit }),
  attachPermitScan: (personId: UUID, id: UUID, documentId: UUID) =>
    http.put<PermitView>(`${base(personId)}/work-permits/${id}/scan`, { documentId }),

  expiringWorkPermits: (withinDays: number) =>
    http.get<ExpiringPermit[]>('/people/persons/expiring-work-permits', { params: { withinDays } }),
  expiringIdentityDocuments: (withinDays: number) =>
    http.get<ExpiringIdentityDocument[]>('/people/persons/expiring-identity-documents', { params: { withinDays } }),

  personEmployments: (personId: UUID) => http.get<PersonEmployment[]>(`${base(personId)}/employments`),
  person: (personId: UUID) => http.get<{ id: UUID; fullName: string }>(base(personId)),
};
