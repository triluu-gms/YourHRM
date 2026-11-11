import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

// ------------------------------------------------------------------------------------------------ codes

/** `domain.contract.ContractType` (BR-PPL-050). */
export type ContractTypeCode = 'PROBATION' | 'DEFINITE' | 'INDEFINITE' | 'SEASONAL' | 'APPRENTICE' | 'INTERNSHIP' | 'SERVICE';
export const CONTRACT_TYPES: ContractTypeCode[] = ['PROBATION', 'DEFINITE', 'INDEFINITE', 'SEASONAL', 'APPRENTICE', 'INTERNSHIP', 'SERVICE'];
/** Types whose end date is mandatory (`ContractType.requiresEndDate`). */
export const END_DATE_REQUIRED: ContractTypeCode[] = ['PROBATION', 'DEFINITE', 'SEASONAL'];
/** Types that may carry a probation clause (`ContractType.probationClauseAllowed`). */
export const PROBATION_CLAUSE_ALLOWED: ContractTypeCode[] = ['DEFINITE', 'INDEFINITE'];
/** Labour contracts (appendix / suspension allowed, `ContractType.labourContract`). */
export const LABOUR_CONTRACT_TYPES: ContractTypeCode[] = ['DEFINITE', 'INDEFINITE', 'SEASONAL'];

/** `hr.contract.status` (contract_status_ck). */
export type ContractStatus =
  | 'DRAFT'
  | 'PENDING_SIGNATURE'
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'EXPIRED'
  | 'TERMINATED'
  | 'SUPERSEDED'
  | 'CANCELLED';

/** `hr.contract_appendix.status`. */
export type AppendixStatus = 'DRAFT' | 'PENDING_SIGNATURE' | 'SIGNED' | 'CANCELLED';
/** `hr.contract_suspension.status`. */
export type SuspensionStatus = 'DRAFT' | 'APPROVED' | 'ACTIVE' | 'ENDED' | 'CANCELLED';
/** `ContractSuspensionService.REASONS` (Labour Code Art. 30). */
export const SUSPENSION_REASONS = ['MILITARY_SERVICE', 'DETENTION', 'MATERNITY_EXTENDED', 'ELECTED_OFFICE', 'AGREEMENT', 'OTHER'] as const;
export type SuspensionReason = (typeof SUSPENSION_REASONS)[number];

/** `ContractAppendixService.CHANGE_KEYS`; `salary` / `allowances` are pay terms (need `hr.compensation.update`). */
export const APPENDIX_CHANGE_KEYS = [
  'salary',
  'allowances',
  'jobTitleId',
  'positionId',
  'orgUnitId',
  'locationId',
  'workLocation',
  'workingTime',
  'other',
] as const;
export type AppendixChangeKey = (typeof APPENDIX_CHANGE_KEYS)[number];
export const APPENDIX_PAY_KEYS: AppendixChangeKey[] = ['salary', 'allowances'];

/** `DecisionService.TYPES` (P1). */
export const DECISION_TYPES = [
  'HIRE',
  'PROBATION_RESULT',
  'TRANSFER',
  'PROMOTION',
  'DEMOTION',
  'TEMPORARY_TRANSFER',
  'JOB_CHANGE',
  'SALARY_CHANGE',
  'CONTRACT_SUSPENSION',
  'TERMINATION',
  'RETIREMENT',
] as const;
export type DecisionType = (typeof DECISION_TYPES)[number];
/** `hr.decision.status`. */
export type DecisionStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'SIGNED' | 'CANCELLED';

// ------------------------------------------------------------------------------------------------ records

/** `ContractRecords.ContractView`. */
export interface ContractView {
  id: UUID;
  employmentId: UUID;
  legalEntityId?: UUID | null;
  personId?: UUID | null;
  employeeNo?: string | null;
  contractNo?: string | null;
  contractTypeCode: ContractTypeCode;
  definiteSeq?: number | null;
  signDate?: ISODate | null;
  startDate: ISODate;
  /** Inclusive. */
  endDate?: ISODate | null;
  probationDays?: number | null;
  probationLevelCode?: string | null;
  probationEndDate?: ISODate | null;
  probationResult?: 'PASS' | 'FAIL' | null;
  probationResultDate?: ISODate | null;
  probationResultDecisionId?: UUID | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
  documentId?: UUID | null;
  previousContractId?: UUID | null;
  definiteExceptionCode?: string | null;
  definiteExceptionNote?: string | null;
  autoConversionDueSince?: ISODate | null;
  source?: string | null;
  status: ContractStatus;
  createdAt?: ISOInstant | null;
  updatedAt?: ISOInstant | null;
  version: number;
}

/** `ContractController.TermsRequest` (create, renew, convert, next contract after probation). */
export interface TermsRequest {
  contractTypeCode?: ContractTypeCode | null;
  startDate?: ISODate | null;
  endDate?: ISODate | null;
  probationDays?: number | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
  probationLevelCode?: string | null;
  probationPayPct?: number | null;
  jobTitleId?: UUID | null;
  definiteExceptionCode?: string | null;
  definiteExceptionNote?: string | null;
}

/** `ContractLifecycleController.ProbationResultRequest`. */
export interface ProbationResultRequest {
  result: 'PASS' | 'FAIL';
  resultDate?: ISODate | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
  nextContract?: TermsRequest | null;
}

/** `ProbationService.Recorded`. */
export interface ProbationRecorded {
  contract: ContractView;
  decisionId?: UUID | null;
  decisionNo?: string | null;
  nextContract?: ContractView | null;
}

/** `ContractExpiryService.ExpiringContract`. */
export interface ExpiringContract {
  contractId: UUID;
  employmentId: UUID;
  legalEntityId?: UUID | null;
  employeeNo?: string | null;
  contractNo?: string | null;
  contractTypeCode: ContractTypeCode;
  definiteSeq?: number | null;
  endDate: ISODate;
  status: ContractStatus;
  daysLeft: number;
  followingContractId?: UUID | null;
  autoConversionDue?: boolean | null;
  autoConversionDueSince?: ISODate | null;
  nextMustBeIndefinite?: boolean | null;
}

/** `ContractExpiryService.EndingProbation`. */
export interface EndingProbation {
  contractId: UUID;
  employmentId: UUID;
  legalEntityId?: UUID | null;
  employeeNo?: string | null;
  contractNo?: string | null;
  contractTypeCode: ContractTypeCode;
  probationEndDate: ISODate;
  daysLeft: number;
}

export interface ExpiryParams {
  asOf?: ISODate;
  withinDays?: number;
  legalEntityId?: UUID;
}

/** `ContractAppendixService.AppendixView`; pay terms arrive as `***` when `payTermsMasked`. */
export interface AppendixView {
  id: UUID;
  contractId: UUID;
  employmentId: UUID;
  legalEntityId?: UUID | null;
  contractNo?: string | null;
  appendixNo?: string | null;
  effectiveDate: ISODate;
  changes: Record<string, unknown>;
  payTermsMasked: boolean;
  documentId?: UUID | null;
  status: AppendixStatus;
  createdAt?: ISOInstant | null;
  version: number;
}

export interface AppendixRequest {
  effectiveDate: ISODate;
  changes: Partial<Record<AppendixChangeKey, unknown>>;
}

/** `ContractSuspensionService.SuspensionView`; `plannedEndDate` is the last suspended day (inclusive). */
export interface SuspensionView {
  id: UUID;
  contractId: UUID;
  employmentId: UUID;
  contractNo?: string | null;
  reasonCode: SuspensionReason;
  validFrom: ISODate;
  plannedEndDate?: ISODate | null;
  returnDate?: ISODate | null;
  returnDueDate?: ISODate | null;
  decisionId?: UUID | null;
  decisionNo?: string | null;
  status: SuspensionStatus;
  version: number;
}

export interface SuspensionRequest {
  reasonCode: SuspensionReason;
  validFrom: ISODate;
  plannedEndDate?: ISODate | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
}

/** `core.api.DocumentGeneration.Generated`. */
export interface GeneratedDocument {
  documentId: UUID;
  documentNumber?: string | null;
  templateVersionId?: UUID | null;
  mediaType?: string | null;
}

/** `DecisionService.Decision`. */
export interface DecisionView {
  id: UUID;
  legalEntityId?: UUID | null;
  decisionNo?: string | null;
  decisionTypeCode: DecisionType;
  employmentId?: UUID | null;
  effectiveDate: ISODate;
  signDate?: ISODate | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
  documentId?: UUID | null;
  workflowInstanceId?: UUID | null;
  status: DecisionStatus;
  version: number;
}

/** `DecisionController.IssueRequest`. */
export interface IssueDecisionRequest {
  employmentId: UUID;
  decisionTypeCode: DecisionType;
  effectiveDate: ISODate;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
}

/** `DecisionController.ReissueRequest`. */
export interface ReissueDecisionRequest {
  effectiveDate?: ISODate | null;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
  version: number;
}

/** Minimal `SeparationService.SeparationResult` (the separation area owns the full type). */
export interface SeparationResultLite {
  separation: { id: UUID; status: string; employmentId: UUID };
  warnings?: string[];
}

/** Core `Documents.DownloadLink` (signed URL ≤ 15 min). */
export interface DownloadLink {
  url: string;
  expiresAt: ISOInstant;
  fileName?: string | null;
}

/** Multipart sign body: `file` part + `signDate` / `version` request params (sent as form fields). */
export function signForm(file: File, version: number, signDate?: ISODate | null): FormData {
  const form = new FormData();
  form.append('file', file);
  if (signDate) form.append('signDate', signDate);
  form.append('version', String(version));
  return form;
}

// ------------------------------------------------------------------------------------------------ api

export const contractsApi = {
  list: (employmentId: UUID) => http.get<ContractView[]>(`/people/employments/${employmentId}/contracts`),
  current: (employmentId: UUID, asOf?: ISODate) =>
    http.get<ContractView>(`/people/employments/${employmentId}/contracts/current`, { params: { asOf } }),
  create: (employmentId: UUID, body: TermsRequest) =>
    http.post<ContractView>(`/people/employments/${employmentId}/contracts`, body),
  get: (contractId: UUID) => http.get<ContractView>(`/people/contracts/${contractId}`),
  renew: (contractId: UUID, body: TermsRequest) => http.post<ContractView>(`/people/contracts/${contractId}/renew`, body),
  convert: (contractId: UUID, body: TermsRequest) => http.post<ContractView>(`/people/contracts/${contractId}/convert`, body),
  sign: (contractId: UUID, form: FormData) => http.post<ContractView>(`/people/contracts/${contractId}/sign`, form),
  cancel: (contractId: UUID, version: number) => http.post<ContractView>(`/people/contracts/${contractId}/cancel`, { version }),
  probationResult: (contractId: UUID, body: ProbationResultRequest) =>
    http.post<ProbationRecorded>(`/people/contracts/${contractId}/probation-result`, body),
  document: (contractId: UUID) => http.post<GeneratedDocument>(`/people/contracts/${contractId}/document`),
  expiring: (params: ExpiryParams) => http.get<ExpiringContract[]>('/people/contracts/expiring', { params: { ...params } }),
  probationsEnding: (params: ExpiryParams) =>
    http.get<EndingProbation[]>('/people/contracts/probations-ending', { params: { ...params } }),
  terminateAtExpiry: (contractId: UUID, noticeDate?: ISODate | null) =>
    http.post<SeparationResultLite>(`/people/contracts/${contractId}/terminate-at-expiry`, { noticeDate: noticeDate ?? null }),
};

export const appendicesApi = {
  list: (contractId: UUID) => http.get<AppendixView[]>(`/people/contracts/${contractId}/appendices`),
  create: (contractId: UUID, body: AppendixRequest) =>
    http.post<AppendixView>(`/people/contracts/${contractId}/appendices`, body),
  get: (appendixId: UUID) => http.get<AppendixView>(`/people/contract-appendices/${appendixId}`),
  sign: (appendixId: UUID, form: FormData) => http.post<AppendixView>(`/people/contract-appendices/${appendixId}/sign`, form),
  cancel: (appendixId: UUID, version: number) =>
    http.post<AppendixView>(`/people/contract-appendices/${appendixId}/cancel`, { version }),
  document: (appendixId: UUID) => http.post<GeneratedDocument>(`/people/contract-appendices/${appendixId}/document`),
};

export const suspensionsApi = {
  list: (employmentId: UUID) => http.get<SuspensionView[]>(`/people/employments/${employmentId}/contract-suspensions`),
  create: (contractId: UUID, body: SuspensionRequest) =>
    http.post<SuspensionView>(`/people/contracts/${contractId}/suspensions`, body),
  get: (suspensionId: UUID) => http.get<SuspensionView>(`/people/contract-suspensions/${suspensionId}`),
  resume: (suspensionId: UUID, returnDate: ISODate, version: number) =>
    http.post<SuspensionView>(`/people/contract-suspensions/${suspensionId}/resume`, { returnDate, version }),
  cancel: (suspensionId: UUID, version: number) =>
    http.post<SuspensionView>(`/people/contract-suspensions/${suspensionId}/cancel`, { version }),
};

export const decisionsApi = {
  list: (employmentId: UUID) => http.get<DecisionView[]>(`/people/employments/${employmentId}/decisions`),
  get: (decisionId: UUID) => http.get<DecisionView>(`/people/decisions/${decisionId}`),
  issue: (body: IssueDecisionRequest) => http.post<DecisionView>('/people/decisions', body),
  document: (decisionId: UUID) => http.post<GeneratedDocument>(`/people/decisions/${decisionId}/document`),
  sign: (decisionId: UUID, form: FormData) => http.post<DecisionView>(`/people/decisions/${decisionId}/sign`, form),
  cancel: (decisionId: UUID, version: number) => http.post<DecisionView>(`/people/decisions/${decisionId}/cancel`, { version }),
  reissue: (decisionId: UUID, body: ReissueDecisionRequest) =>
    http.post<DecisionView>(`/people/decisions/${decisionId}/reissue`, body),
};

/** Core document service: signed download URL (the document's owner permission is checked by Core). */
export const documentLinkApi = {
  downloadLink: (documentId: UUID) => http.post<DownloadLink>(`/core/documents/${documentId}/download-link`),
};
