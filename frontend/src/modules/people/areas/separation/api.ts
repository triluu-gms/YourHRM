import { http, isProblem, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

// ------------------------------------------------------------------------------------------------ vocab

/** `hr.separation.status` (separation_status_ck). */
export type SeparationStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'APPROVED'
  | 'SCHEDULED'
  | 'COMPLETED'
  | 'WITHDRAWN'
  | 'REJECTED'
  | 'CANCELLED';
export const SEPARATION_STATUSES: SeparationStatus[] = [
  'DRAFT',
  'SUBMITTED',
  'APPROVED',
  'SCHEDULED',
  'COMPLETED',
  'WITHDRAWN',
  'REJECTED',
  'CANCELLED',
];
/** `SeparationRecords.CLOSED`: a separation in any other status is the employment's live one. */
export const CLOSED_STATUSES: SeparationStatus[] = ['WITHDRAWN', 'REJECTED', 'CANCELLED'];

/** `separation_service_period_kind_ck`. */
export type ServicePeriodKind = 'WORKED' | 'RECOGNISED_PRIOR' | 'PAID_SEVERANCE' | 'PAID_JOB_LOSS' | 'EXCLUDED';
export const SERVICE_PERIOD_KINDS: ServicePeriodKind[] = ['WORKED', 'RECOGNISED_PRIOR', 'PAID_SEVERANCE', 'PAID_JOB_LOSS', 'EXCLUDED'];

/** Which commands the backend accepts per status (`SeparationService` guards). */
export const canEdit = (s: SeparationStatus) => s === 'DRAFT';
export const canSubmit = (s: SeparationStatus) => s === 'DRAFT';
export const canCancel = (s: SeparationStatus) => s === 'DRAFT' || s === 'SUBMITTED';
export const canEditPeriods = (s: SeparationStatus) => s === 'DRAFT' || s === 'SUBMITTED' || s === 'SCHEDULED';

// ------------------------------------------------------------------------------------------------ views

/** `SeparationRecords.ServicePeriodView`; `derived` periods are computed by the backend and cannot be removed. */
export interface ServicePeriodView {
  id: UUID;
  periodKind: ServicePeriodKind;
  periodFrom: ISODate;
  periodTo: ISODate;
  sourceEmploymentId?: UUID | null;
  note?: string | null;
  derived: boolean;
  version: number;
}

/** `SeparationRecords.SeparationView`. */
export interface SeparationView {
  id: UUID;
  employmentId: UUID;
  personId: UUID;
  legalEntityId?: UUID | null;
  employeeNo: string;
  fullName: string;
  status: SeparationStatus;
  employmentStatus?: string | null;
  /** NOTICE between approval and termination, TERMINATED once completed, else the employment status. */
  employmentPhase?: string | null;
  separationType?: string | null;
  initiatedBy?: string | null;
  legalGroundCode?: string | null;
  reasonCode?: string | null;
  requestDate?: ISODate | null;
  noticeDate?: ISODate | null;
  requiredNoticeDays?: number | null;
  noticeDayBasis?: 'CALENDAR' | 'WORKING' | null;
  noticeShortfallDays?: number | null;
  specialTradeCode?: string | null;
  noticeExemptReasonCode?: string | null;
  lastWorkingDate?: ISODate | null;
  terminationDate?: ISODate | null;
  accessRevocationDate?: ISODate | null;
  accessRevocationAt?: string | null;
  severanceType?: 'NONE' | 'SEVERANCE_ALLOWANCE' | 'JOB_LOSS_ALLOWANCE' | null;
  severanceEligibility?: 'ELIGIBLE' | 'NOT_ELIGIBLE' | 'PENDING' | null;
  protectedEmployee: boolean;
  protectedOverrideReason?: string | null;
  protectedOverrideDocumentId?: UUID | null;
  rehireEligible?: boolean | null;
  employeeNote?: string | null;
  noPersonalEmail: boolean;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
  payrollResultRef?: UUID | null;
  settlementDueDate?: ISODate | null;
  decisionId?: UUID | null;
  workflowInstanceId?: UUID | null;
  withdrawnAt?: ISOInstant | null;
  createdAt?: ISOInstant | null;
  servicePeriods: ServicePeriodView[];
  version: number;
}

/** `NoticeCalculator.Assessment`. */
export interface NoticeAssessment {
  side?: string | null;
  category?: string | null;
  contractId?: UUID | null;
  contractNo?: string | null;
  contractTypeCode?: string | null;
  requiredDays?: number | null;
  basis?: string | null;
  givenDays?: number | null;
  shortfallDays: number;
  specialTradeCode?: string | null;
  exemptReasonCode?: string | null;
  consequences: string[];
}

/** `SeparationAbsences.Absence`. */
export interface ProtectiveAbsence {
  absenceTypeCode: string;
  from: ISODate;
  to: ISODate;
}

/** `SeparationService.Protection` (BR-PPL-073). */
export interface Protection {
  protectedEmployee: boolean;
  statusReasons: string[];
  absences: ProtectiveAbsence[];
  leaveDataAvailable: boolean;
  exceptionRequired: boolean;
  exceptionDocumented: boolean;
}

/** `SeparationService.SeparationResult`. */
export interface SeparationResult {
  separation: SeparationView;
  notice?: NoticeAssessment | null;
  protection?: Protection | null;
  statutoryCode?: string | null;
  warnings: string[];
}

/** `SeparationService.Preview` (nothing stored). */
export interface SeparationPreview {
  legalGroundCode: string;
  separationType?: string | null;
  statutoryCode?: string | null;
  notice?: NoticeAssessment | null;
  protection?: Protection | null;
  severanceType?: string | null;
  severanceEligibility?: string | null;
  terminationDate?: ISODate | null;
  accessRevocationDate?: ISODate | null;
  warnings: string[];
}

/** `SeparationReasons.GroundView`; `configured=false` grounds cannot be used until the legal pack is fixed. */
export interface LegalGround {
  code: string;
  separationType?: string | null;
  initiatedBy?: string | null;
  article?: string | null;
  nameVi?: string | null;
  nameEn?: string | null;
  employerInitiated: boolean;
  decisionType?: string | null;
  statutoryCode?: string | null;
  noticeSide?: string | null;
  severanceType?: string | null;
  configured: boolean;
  problems: string[];
}

/** `SeparationReasons.SubReasonView`. */
export interface SubReason {
  code: string;
  groundCode: string;
  nameVi?: string | null;
  nameEn?: string | null;
  active: boolean;
  problems: string[];
}

/** `SeparationReasons.Catalogue`. */
export interface ReasonCatalogue {
  grounds: LegalGround[];
  subReasons: SubReason[];
}

// ------------------------------------------------------------------------------------------------ requests

/** `SeparationController.AttributesRequest` (preview / create, and inside `UpdateRequest`). */
export interface SeparationAttributes {
  legalGroundCode: string;
  reasonCode?: string | null;
  requestDate?: ISODate | null;
  noticeDate?: ISODate | null;
  lastWorkingDate: ISODate;
  terminationDate?: ISODate | null;
  accessRevocationDate?: ISODate | null;
  specialTradeCode?: string | null;
  noticeExemptReasonCode?: string | null;
  protectedOverrideReason?: string | null;
  rehireEligible?: boolean | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
}

export interface UpdateSeparationRequest {
  version: number;
  attributes: SeparationAttributes;
}

export interface SubmitSeparationRequest {
  version: number;
  personalEmail?: string | null;
  noPersonalEmail?: boolean | null;
  note?: string | null;
}

export interface CancelSeparationRequest {
  version: number;
  reason?: string | null;
}

export interface ServicePeriodRequest {
  periodKind: ServicePeriodKind;
  periodFrom: ISODate;
  periodTo: ISODate;
  sourceEmploymentId?: UUID | null;
  note?: string | null;
}

export interface ContractExpiryRequest {
  contractId: UUID;
  noticeDate?: ISODate | null;
}

export interface SeparationListParams {
  status?: SeparationStatus;
  legalEntityId?: UUID;
  /** Termination-date range. */
  from?: ISODate;
  to?: ISODate;
}

// ------------------------------------------------------------------------------------------------ privacy

/** `PrivacyRequests.ExportPackage`: every People record of the person (P3 values decrypted server-side). */
export interface ExportPackage {
  personId: UUID;
  requestRef?: string | null;
  generatedAt: ISOInstant;
  data: Record<string, Record<string, unknown>[]>;
  pdfDocumentId?: UUID | null;
  warnings: string[];
}

/** `PrivacyRequests.AnonymisationConfirmation`. */
export interface AnonymisationConfirmation {
  personId: UUID;
  requestRef?: string | null;
  mode: 'RESTRICTED' | 'FULL' | string;
  retainedUntil?: ISODate | null;
  cleared: string[];
  retained: string[];
  shreddedSubjects: number;
  completedAt: ISOInstant;
}

// ------------------------------------------------------------------------------------------------ client

export const separationApi = {
  reasons: (params: { legalEntityId?: UUID | null; asOf?: ISODate | null } = {}) =>
    http.get<ReasonCatalogue>('/people/separations/reasons', { params: { ...params } }),
  list: (params: SeparationListParams) => http.get<SeparationView[]>('/people/separations', { params: { ...params } }),
  get: (id: UUID) => http.get<SeparationView>(`/people/separations/${id}`),
  /** The employment's live separation, or null (the backend answers 404 when there is none). */
  live: async (employmentId: UUID) => {
    try {
      return await http.get<SeparationView>(`/people/employments/${employmentId}/separation`);
    } catch (e) {
      if (isProblem(e) && e.status === 404) return null;
      throw e;
    }
  },
  ofEmployment: (employmentId: UUID) => http.get<SeparationView[]>(`/people/employments/${employmentId}/separations`),
  preview: (employmentId: UUID, body: SeparationAttributes) =>
    http.post<SeparationPreview>(`/people/employments/${employmentId}/separation/preview`, body),
  create: (employmentId: UUID, body: SeparationAttributes) =>
    http.post<SeparationResult>(`/people/employments/${employmentId}/separation`, body),
  contractExpiry: (body: ContractExpiryRequest) => http.post<SeparationResult>('/people/separations/contract-expiry', body),
  update: (id: UUID, body: UpdateSeparationRequest) => http.put<SeparationResult>(`/people/separations/${id}`, body),
  evidence: (id: UUID, form: FormData) => http.post<SeparationView>(`/people/separations/${id}/evidence`, form),
  submit: (id: UUID, body: SubmitSeparationRequest) => http.post<SeparationResult>(`/people/separations/${id}/submit`, body),
  cancel: (id: UUID, body: CancelSeparationRequest) => http.post<SeparationView>(`/people/separations/${id}/cancel`, body),
  addPeriod: (id: UUID, body: ServicePeriodRequest) =>
    http.post<ServicePeriodView>(`/people/separations/${id}/service-periods`, body),
  removePeriod: (id: UUID, periodId: UUID) => http.delete(`/people/separations/${id}/service-periods/${periodId}`),
};

export const privacyApi = {
  export: (personId: UUID, requestRef?: string | null) =>
    http.post<ExportPackage>(`/people/privacy/persons/${personId}/export`, { requestRef: requestRef || null }),
  anonymise: (personId: UUID, requestRef?: string | null) =>
    http.post<AnonymisationConfirmation>(`/people/privacy/persons/${personId}/anonymise`, {
      requestRef: requestRef || null,
      confirm: true,
    }),
};
