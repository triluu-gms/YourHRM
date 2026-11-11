import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';
import type { EmploymentStatus } from '../../common';

// ------------------------------------------------------------------------------------------------- employment

/** `EmploymentService.EmploymentView` (GET /people/employments/{id}). */
export interface EmploymentView {
  id: UUID;
  personId: UUID;
  fullName: string;
  legalEntityId: UUID;
  employeeNo: string;
  hireType: 'NEW' | 'REHIRE' | 'CONCURRENT' | 'TRANSFER_IN' | string;
  hireDate: ISODate;
  originalHireDate?: ISODate | null;
  seniorityDate?: ISODate | null;
  probationEndDate?: ISODate | null;
  endDate?: ISODate | null;
  lastWorkingDate?: ISODate | null;
  status: EmploymentStatus;
  noticeState?: string | null;
  workerCategoryCode: string;
  employeeCategoryCode?: string | null;
  workingRegimeCode?: string | null;
  rehireEligible?: boolean | null;
  previousEmploymentId?: UUID | null;
  source?: string | null;
  hireApproval?: string | null;
  hireDecisionId?: UUID | null;
  hireDecisionNo?: string | null;
  incomplete: boolean;
  /** NATIONAL_ID, BIRTH_DATE, TAX_CODE, SIGNED_CONTRACT, BANK_ACCOUNT… */
  missingData: string[];
  pitPrimary: boolean;
  version: number;
}

/** `EmploymentService.StatusView` (status as of a date; `statusOn` is the derived status on that day). */
export interface EmploymentStatusView {
  employmentId: UUID;
  asOf: ISODate;
  status: EmploymentStatus;
  statusOn: EmploymentStatus;
  noticeState?: string | null;
  hireDate: ISODate;
  endDate?: ISODate | null;
  lastWorkingDate?: ISODate | null;
  live: boolean;
}

/** `EmploymentController.UpdateBody` — hire date and legal entity are not editable (cancel and re-hire). */
export interface UpdateEmploymentRequest {
  version: number;
  workerCategoryCode: string;
  employeeCategoryCode?: string | null;
  workingRegimeCode?: string | null;
  originalHireDate?: ISODate | null;
  seniorityDate?: ISODate | null;
  probationEndDate?: ISODate | null;
}

// ---------------------------------------------------------------------------------------- primary employment

export type PrimaryBasis = 'DESIGNATED' | 'SINGLE_EMPLOYMENT' | 'MISSING' | 'NONE';

/** `PrimaryEmploymentService.Designation`. */
export interface PrimaryDesignation {
  id: UUID;
  employmentId: UUID;
  employeeNo: string;
  legalEntityId: UUID;
  validFrom: ISODate;
  validTo?: ISODate | null;
  status: string;
  reasonCode?: string | null;
  source?: string | null;
  version: number;
}

/** `PrimaryEmploymentService.PendingRequest`. */
export interface PrimaryPendingRequest {
  requestId: UUID;
  employmentId: UUID;
  validFrom: ISODate;
  reasonCode?: string | null;
}

/** `PrimaryEmploymentService.PrimaryView` (GET /people/persons/{id}/primary-employment). */
export interface PrimaryView {
  personId: UUID;
  asOf: ISODate;
  primaryEmploymentId?: UUID | null;
  basis: PrimaryBasis;
  liveEmployments: number;
  designations: PrimaryDesignation[];
  pending: PrimaryPendingRequest[];
}

/** `EmploymentController.PrimaryChangeBody` (validFrom must be the 1st of a month). */
export interface PrimaryChangeRequest {
  employmentId: UUID;
  validFrom: ISODate;
  reasonCode?: string | null;
  comment?: string | null;
}

/** `PrimaryEmploymentService.ChangeResult`; status PENDING_APPROVAL or APPLIED. */
export interface PrimaryChangeResult {
  requestId: UUID;
  status: 'PENDING_APPROVAL' | 'APPLIED' | string;
  workflowInstanceId?: UUID | null;
  designationId?: UUID | null;
}

// ------------------------------------------------------------------------------------- insurance participation

/** `EmploymentKeys.INSURANCE_TYPES`. */
export type InsuranceType = 'SI' | 'HI' | 'UI' | 'OA_OD';
export const INSURANCE_TYPES: InsuranceType[] = ['SI', 'HI', 'UI', 'OA_OD'];

/** `InsuranceParticipationService.ParticipationView`; `validTo` exclusive. */
export interface ParticipationView {
  id: UUID;
  employmentId: UUID;
  insuranceType: InsuranceType;
  validFrom: ISODate;
  validTo?: ISODate | null;
  participating: boolean;
  basisRule?: string | null;
  exemptionReasonCode?: string | null;
  manualOverride: boolean;
  overrideReason?: string | null;
  legalPackId?: UUID | null;
  legalPackVersion?: number | null;
  decidedAt?: ISOInstant | null;
  decidedBy?: UUID | null;
  status?: string | null;
}

/** `EmploymentController.OverrideBody`. */
export interface InsuranceOverrideRequest {
  insuranceType: InsuranceType;
  validFrom: ISODate;
  validTo?: ISODate | null;
  participating: boolean;
  exemptionReasonCode?: string | null;
  overrideReason: string;
}

// ---------------------------------------------------------------------------------------- concurrent report

/** `ConcurrentEmploymentReport.ExceptionLine`. */
export interface ConcurrentExceptionLine {
  code: string;
  personId: UUID;
  fullName: string;
  employmentId?: UUID | null;
  employeeNo?: string | null;
  insuranceType?: string | null;
  detail?: string | null;
  link?: string | null;
}

/** `ConcurrentEmploymentReport.Report`. */
export interface ConcurrentReport {
  legalEntityId: UUID;
  asOf: ISODate;
  personsChecked: number;
  exceptions: ConcurrentExceptionLine[];
}

// ------------------------------------------------------------------------------------------------ assignments

/** `AssignmentEvents.CostShare`. */
export interface CostShare {
  costCentreId: UUID;
  pct: number;
}

/** `AssignmentController.AttributesRequest` — no pay group and no work schedule (BR-PPL-031). */
export interface AssignmentAttributes {
  orgUnitId: UUID;
  positionId?: UUID | null;
  jobTitleId?: UUID | null;
  gradeId?: UUID | null;
  managerEmploymentId?: UUID | null;
  locationId?: UUID | null;
  costCentreId?: UUID | null;
  costSplit?: CostShare[];
  fte?: number | null;
}

/** `AssignmentService.AssignmentView` — valid time (`validTo` exclusive) and transaction time (known from/to). */
export interface AssignmentView {
  id: UUID;
  assignmentKey: UUID;
  employmentId: UUID;
  assignmentType: string;
  legalEntityId: UUID;
  payingLegalEntityId?: UUID | null;
  orgUnitId: UUID;
  positionId?: UUID | null;
  jobTitleId?: UUID | null;
  gradeId?: UUID | null;
  managerEmploymentId?: UUID | null;
  locationId?: UUID | null;
  costCentreId?: UUID | null;
  costSplit: CostShare[];
  fte?: number | null;
  actionCode?: string | null;
  reasonCode?: string | null;
  decisionId?: UUID | null;
  decisionNo?: string | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
  knownFrom?: ISOInstant | null;
  knownTo?: ISOInstant | null;
  supersededBy?: UUID | null;
  correctionReason?: string | null;
  source?: string | null;
}

/** `AssignmentController.ChangeRequestBody`. */
export interface AssignmentChangeRequest {
  reasonCode: string;
  effectiveDate: ISODate;
  /** Temporary transfer (Art. 29): end date required. */
  endDate?: ISODate | null;
  attributes: AssignmentAttributes;
  contractAppendixId?: UUID | null;
  consentDocumentId?: UUID | null;
  noticeDate?: ISODate | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
  comment?: string | null;
}

/** `Warning` (non-blocking findings returned with a change). */
export interface ChangeWarning {
  code: string;
  message: string;
}

export type ChangeStatus = 'PENDING_APPROVAL' | 'APPLIED' | 'SCHEDULED';

/** `AssignmentService.ChangeResult`. */
export interface AssignmentChangeResult {
  requestId: UUID;
  status: ChangeStatus;
  workflowInstanceId?: UUID | null;
  assignment?: AssignmentView | null;
  decisionId?: UUID | null;
  decisionNo?: string | null;
  warnings: ChangeWarning[];
}

/** `AssignmentController.CorrectionBody`. */
export interface AssignmentCorrectionRequest {
  reason: string;
  attributes: AssignmentAttributes;
}

/** `ChangeReasonCatalogue.ReasonView` (US-PPL-035). */
export interface ChangeReasonView {
  code: string;
  nameVi: string;
  nameEn?: string | null;
  actionCode: string;
  assignmentType?: string | null;
  processTypeCode?: string | null;
  decisionTypeCode?: string | null;
  documentKind?: string | null;
  active: boolean;
  phase2: boolean;
  tenantDefined: boolean;
  problems: string[];
}

// ---------------------------------------------------------------------------------------------------- salary

export type PayBasis = 'GROSS' | 'NET';
export const PAY_BASES: PayBasis[] = ['GROSS', 'NET'];
export type SalaryKind = 'MONTHLY' | 'DAILY' | 'HOURLY' | 'PIECE';
export const SALARY_KINDS: SalaryKind[] = ['MONTHLY', 'DAILY', 'HOURLY', 'PIECE'];
/** `SalaryService.CHANGE_ACTIONS`. */
export type SalaryAction = 'INCREASE' | 'DECREASE' | 'PROMOTION' | 'ADJUSTMENT';
export const SALARY_ACTIONS: SalaryAction[] = ['INCREASE', 'DECREASE', 'PROMOTION', 'ADJUSTMENT'];

/** `SalaryEvents.Allowance`. */
export interface Allowance {
  componentCode: string;
  amount: number;
  inSiSalary: boolean;
}

/** `SalaryService.SalaryView`; amounts are null when `amountsMasked`. */
export interface SalaryView {
  id: UUID;
  employmentId: UUID;
  payBasis: PayBasis;
  salaryKind: SalaryKind;
  baseSalaryAmount?: number | null;
  agreedNetAmount?: number | null;
  netGuaranteedScope?: string[] | null;
  siSalaryAmount?: number | null;
  currencyCode: string;
  payScaleCode?: string | null;
  payScaleStepCode?: string | null;
  probationPct?: number | null;
  allowances: Allowance[];
  actionCode?: string | null;
  reasonCode?: string | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
  knownFrom?: ISOInstant | null;
  knownTo?: ISOInstant | null;
  supersededBy?: UUID | null;
  correctionReason?: string | null;
  source?: string | null;
  decisionId?: UUID | null;
  decisionNo?: string | null;
  decisionDocumentId?: UUID | null;
  contractAppendixId?: UUID | null;
  appendixNo?: string | null;
  appendixDocumentId?: UUID | null;
  amountsMasked: boolean;
}

/** `SalaryService.SalaryListItem` (GET /people/salary-records). */
export interface SalaryListItem {
  employmentId: UUID;
  employeeNo: string;
  fullName: string;
  legalEntityId: UUID;
  salary: SalaryView;
}

/** `SalaryController.TermsRequest`. */
export interface SalaryTerms {
  payBasis?: PayBasis | null;
  salaryKind?: SalaryKind | null;
  baseSalaryAmount: number;
  agreedNetAmount?: number | null;
  netGuaranteedScope?: string[] | null;
  siSalaryAmount?: number | null;
  currencyCode?: string | null;
  payScaleCode?: string | null;
  payScaleStepCode?: string | null;
  probationPct?: number | null;
  allowances?: Allowance[];
}

/** `SalaryController.ChangeRequestBody`; with `correctsRecordId` it corrects that version (reason required). */
export interface SalaryChangeRequest {
  actionCode?: SalaryAction | null;
  reasonCode?: string | null;
  effectiveDate?: ISODate | null;
  terms: SalaryTerms;
  contractAppendixId?: UUID | null;
  correctsRecordId?: UUID | null;
  correctionReason?: string | null;
  bandOverrideReason?: string | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
}

/** `SalaryService.ChangeResult` (no amounts). */
export interface SalaryChangeResult {
  requestId: UUID;
  status: ChangeStatus;
  workflowInstanceId?: UUID | null;
  salaryRecordId?: UUID | null;
  decisionId?: UUID | null;
  decisionNo?: string | null;
  warnings: ChangeWarning[];
}

// ------------------------------------------------------------------------------------------------------- api

const e = (employmentId: UUID) => `/people/employments/${employmentId}`;

export const employmentApi = {
  get: (employmentId: UUID) => http.get<EmploymentView>(e(employmentId)),
  ofPerson: (personId: UUID) => http.get<EmploymentView[]>(`/people/persons/${personId}/employments`),
  status: (employmentId: UUID, asOf?: ISODate) => http.get<EmploymentStatusView>(`${e(employmentId)}/status`, { params: { asOf } }),
  update: (employmentId: UUID, body: UpdateEmploymentRequest) => http.put<EmploymentView>(e(employmentId), body),

  primary: (personId: UUID, asOf?: ISODate) =>
    http.get<PrimaryView>(`/people/persons/${personId}/primary-employment`, { params: { asOf } }),
  requestPrimaryChange: (personId: UUID, body: PrimaryChangeRequest) =>
    http.post<PrimaryChangeResult>(`/people/persons/${personId}/primary-employment/change-requests`, body),

  insurance: (employmentId: UUID, asOf?: ISODate) =>
    http.get<ParticipationView[]>(`${e(employmentId)}/insurance-participation`, { params: { asOf } }),
  overrideInsurance: (employmentId: UUID, body: InsuranceOverrideRequest) =>
    http.post<ParticipationView[]>(`${e(employmentId)}/insurance-participation/overrides`, body),
  rederiveInsurance: (employmentId: UUID, from?: ISODate | null) =>
    http.post<ParticipationView[]>(`${e(employmentId)}/insurance-participation/rederive`, undefined, {
      params: { from: from ?? undefined },
    }),

  concurrentExceptions: (legalEntityId: UUID, asOf?: ISODate) =>
    http.get<ConcurrentReport>('/people/reports/concurrent-employment-exceptions', { params: { legalEntityId, asOf } }),
};

export const assignmentApi = {
  asOf: (employmentId: UUID, asOf?: ISODate) => http.get<AssignmentView[]>(`${e(employmentId)}/assignments`, { params: { asOf } }),
  history: (employmentId: UUID) => http.get<AssignmentView[]>(`${e(employmentId)}/assignments/history`),
  get: (employmentId: UUID, assignmentId: UUID) => http.get<AssignmentView>(`${e(employmentId)}/assignments/${assignmentId}`),
  requestChange: (employmentId: UUID, body: AssignmentChangeRequest) =>
    http.post<AssignmentChangeResult>(`${e(employmentId)}/assignments/changes`, body),
  correct: (employmentId: UUID, assignmentId: UUID, body: AssignmentCorrectionRequest) =>
    http.post<AssignmentView>(`${e(employmentId)}/assignments/${assignmentId}/corrections`, body),
  cancel: (employmentId: UUID, assignmentId: UUID, reason: string) =>
    http.post<AssignmentView>(`${e(employmentId)}/assignments/${assignmentId}/cancellation`, { reason }),
  reasons: () => http.get<ChangeReasonView[]>('/people/assignment-change-reasons'),
};

/** Salary reads carry the audit `purpose` (US-PPL-045); the backend remembers it per session and node. */
export const salaryApi = {
  asOf: (employmentId: UUID, asOf: ISODate | undefined, purpose: string | undefined) =>
    http.get<SalaryView | undefined>(`${e(employmentId)}/salary-records`, { params: { asOf, purpose } }),
  history: (employmentId: UUID, purpose: string | undefined) =>
    http.get<SalaryView[]>(`${e(employmentId)}/salary-records/history`, { params: { purpose } }),
  get: (employmentId: UUID, salaryRecordId: UUID, purpose: string | undefined) =>
    http.get<SalaryView>(`${e(employmentId)}/salary-records/${salaryRecordId}`, { params: { purpose } }),
  list: (params: { asOf?: ISODate; legalEntityId?: UUID; purpose?: string; limit?: number; offset?: number }) =>
    http.get<SalaryListItem[]>('/people/salary-records', { params: { ...params } }),
  requestChange: (employmentId: UUID, body: SalaryChangeRequest) =>
    http.post<SalaryChangeResult>(`${e(employmentId)}/salary-records/change-requests`, body),
};
