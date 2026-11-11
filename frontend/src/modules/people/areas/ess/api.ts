import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

/** `YYYY-MM` (Java `YearMonth`). */
export type YearMonth = string;

// ------------------------------------------------------------------------------------------- profile (EssService)

export interface IdentityDoc {
  docType: string;
  docNoMasked?: string | null;
  issuingCountryCode?: string | null;
  issueDate?: ISODate | null;
  expiryDate?: ISODate | null;
  status?: string | null;
}

export interface MyAddress {
  addressType: string;
  countryCode?: string | null;
  provinceCode?: string | null;
  communeCode?: string | null;
  addressLine?: string | null;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
}

export interface MyContact {
  contactType: string;
  contactValue: string;
  primary: boolean;
  verified: boolean;
}

export interface MyQualification {
  qualificationLevelCode?: string | null;
  vocationalSkillLevelCode?: string | null;
  generalEducationCode?: string | null;
  fieldOfStudy?: string | null;
  institutionName?: string | null;
  graduationYear?: number | null;
  highest: boolean;
}

export interface MyTaxResidency {
  status: string;
  basis?: string | null;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
  approvalStatus?: string | null;
}

export interface EmploymentRef {
  employmentId: UUID;
  employeeNo: string;
  legalEntityId?: UUID | null;
  status: string;
  hireDate?: ISODate | null;
  endDate?: ISODate | null;
}

/** `EssService.MyProfile` (US-PPL-072): identifiers masked; birth date is the caller's own. */
export interface MyProfile {
  personId: UUID;
  familyName?: string | null;
  middleName?: string | null;
  givenName?: string | null;
  fullName: string;
  preferredName?: string | null;
  birthDate?: ISODate | null;
  genderCode?: string | null;
  nationalityCode?: string | null;
  nationalIdType?: string | null;
  nationalIdMasked?: string | null;
  taxCodeMasked?: string | null;
  siNumberMasked?: string | null;
  hiCardNoMasked?: string | null;
  identityDocuments: IdentityDoc[];
  addresses: MyAddress[];
  contacts: MyContact[];
  qualifications: MyQualification[];
  taxResidency: MyTaxResidency[];
  employments: EmploymentRef[];
}

export interface MyAssignment {
  assignmentType?: string | null;
  orgUnitId?: UUID | null;
  positionId?: UUID | null;
  jobTitleId?: UUID | null;
  gradeId?: UUID | null;
  managerEmploymentId?: UUID | null;
  locationId?: UUID | null;
  actionCode?: string | null;
  reasonCode?: string | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

/** `EssService.Document`: kind CONTRACT, APPENDIX or DECISION; `documentId` null when no file is attached yet. */
export interface MyDocument {
  kind: 'CONTRACT' | 'APPENDIX' | 'DECISION' | string;
  recordId: UUID;
  number?: string | null;
  typeCode?: string | null;
  effectiveDate?: ISODate | null;
  endDate?: ISODate | null;
  signDate?: ISODate | null;
  status?: string | null;
  documentId?: UUID | null;
}

export interface MyAllowance {
  componentCode: string;
  amount: number;
}

export interface MySalary {
  payBasis?: string | null;
  salaryKind?: string | null;
  baseSalaryAmount?: number | null;
  agreedNetAmount?: number | null;
  siSalaryAmount?: number | null;
  currencyCode?: string | null;
  allowances: MyAllowance[];
  actionCode?: string | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

/** `EssService.MyEmployment` (US-PPL-073); `salary` is null/empty when the tenant policy hides it. */
export interface MyEmployment {
  employment: EmploymentRef;
  assignments: MyAssignment[];
  contracts: MyDocument[];
  decisions: MyDocument[];
  salaryVisible: boolean;
  salary?: MySalary[] | null;
}

/** `core.api.Documents.DownloadLink`: signed URL valid ≤ 15 min. */
export interface DownloadLink {
  url: string;
  expiresAt: ISOInstant;
  fileName?: string | null;
}

/** `core.api.Documents.DocumentView` (only the fields the ESS screens use). */
export interface UploadedDocument {
  id: UUID;
  documentTypeCode?: string | null;
  title?: string | null;
  status?: string | null;
}

// ------------------------------------------------------------------------------------------- family / dependants

/** `FamilyMemberService.FamilyMemberView`; identifiers masked. */
export interface FamilyMemberView {
  id: UUID;
  personId: UUID;
  relationshipCode: string;
  fullName: string;
  birthDate?: ISODate | null;
  birthYear?: number | null;
  nationalIdMasked?: string | null;
  taxCodeMasked?: string | null;
  hasIncomeAboveThreshold?: boolean | null;
  disabled?: boolean | null;
  deceased: boolean;
  masked: boolean;
  version: number;
}

/** `FamilyController.FamilyMemberRequest` (ESS create ignores `version`). */
export interface FamilyMemberRequest {
  relationshipCode: string;
  fullName: string;
  birthDate?: ISODate | null;
  nationalId?: string | null;
  taxCode?: string | null;
  hasIncomeAboveThreshold?: boolean | null;
  averageMonthlyIncome?: number | null;
  disabled?: boolean | null;
  deceased?: boolean | null;
}

/** `DependantEligibility.REASONS`. */
export const DEPENDANT_REASONS = ['CHILD_UNDER_AGE', 'CHILD_STUDENT', 'CHILD_DISABLED', 'SPOUSE', 'PARENT', 'OTHER_SUPPORTED'] as const;
export type DependantReason = (typeof DEPENDANT_REASONS)[number];

/** Claim status: PENDING, APPROVED, REJECTED, ENDED (+ CANCELLED / END_REJECTED transitions). */
export type DependantStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'ENDED' | string;

/** `DependantService.DependantView`; `pendingEndMonth` = an end request awaiting approval. */
export interface DependantView {
  id: UUID;
  personId: UUID;
  familyMemberId: UUID;
  familyMemberName: string;
  relationshipCode: string;
  employmentId?: UUID | null;
  fromMonth: ISODate;
  toMonth?: ISODate | null;
  reasonCode: string;
  status: DependantStatus;
  pendingEndMonth?: ISODate | null;
  pendingEndRequestId?: UUID | null;
  taxRegistrationRef?: string | null;
  registeredAt?: ISODate | null;
  approvedAt?: ISOInstant | null;
  evidenceDocumentIds: UUID[];
  version: number;
}

/** `FamilyController.RegistrationRequest` (ESS: `employmentId` is decided by the rules, leave it empty). */
export interface RegistrationRequest {
  familyMemberId: UUID;
  employmentId?: UUID | null;
  fromMonth: YearMonth;
  toMonth?: YearMonth | null;
  reasonCode: DependantReason;
  evidenceDocumentIds?: UUID[];
  taxRegistrationRef?: string | null;
}

/** `FamilyController.EndRequest`. */
export interface EndRequest {
  toMonth: YearMonth;
  version: number;
  evidenceDocumentIds?: UUID[];
}

/** One failed rule of `DependantEligibility.Violation` (serialised as a map). */
export interface EligibilityViolation {
  code: string;
  message?: string | null;
  month?: ISODate | null;
}

/** `DependantService.EligibilityResult`. */
export interface EligibilityResult {
  eligible: boolean;
  claimEmploymentId?: UUID | null;
  violations: EligibilityViolation[];
}

// --------------------------------------------------------------------------------------------- bank accounts

export type SplitType = 'PERCENT' | 'FIXED' | 'REMAINDER';
export const SPLIT_TYPES: SplitType[] = ['REMAINDER', 'PERCENT', 'FIXED'];

/** `BankAccountService.BankAccountView`; the number arrives masked. */
export interface BankAccountView {
  id: UUID;
  personId: UUID;
  bankCode: string;
  bankName?: string | null;
  branchCode?: string | null;
  accountNoMasked?: string | null;
  holderName: string;
  currencyCode?: string | null;
  splitType?: SplitType | null;
  splitValue?: number | null;
  priority: number;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
  status: string;
  stepUpVerified: boolean;
  verifiedAt?: ISOInstant | null;
  version: number;
}

/** `BankAccountController.BankAccountRequest` (`splitType` REMAINDER = default account, no `splitValue`). */
export interface BankAccountRequest {
  bankCode: string;
  branchCode?: string | null;
  accountNo: string;
  holderName: string;
  currencyCode?: string | null;
  splitType?: SplitType | null;
  splitValue?: number | null;
  priority?: number | null;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
}

export interface BankWarning {
  code: string;
  message?: string | null;
}

/** `BankAccountService.Saved`: the pending account and non-blocking warnings (e.g. holder name mismatch). */
export interface BankSaved {
  account: BankAccountView;
  warnings: BankWarning[];
}

// --------------------------------------------------------------------------------------------- resignation

/** `SeparationRecords.SeparationView` (the fields ESS shows). */
export interface SeparationView {
  id: UUID;
  employmentId: UUID;
  employeeNo?: string | null;
  fullName?: string | null;
  status: string;
  separationType?: string | null;
  initiatedBy?: string | null;
  reasonCode?: string | null;
  requestDate?: ISODate | null;
  noticeDate?: ISODate | null;
  requiredNoticeDays?: number | null;
  noticeShortfallDays?: number | null;
  lastWorkingDate?: ISODate | null;
  terminationDate?: ISODate | null;
  accessRevocationDate?: ISODate | null;
  employeeNote?: string | null;
  noPersonalEmail?: boolean;
  workflowInstanceId?: UUID | null;
  withdrawnAt?: ISOInstant | null;
  createdAt?: ISOInstant | null;
  version: number;
}

/** `NoticeCalculator.Assessment` (subset). */
export interface NoticeAssessment {
  requiredDays?: number | null;
  givenDays?: number | null;
  shortfallDays: number;
  consequences: string[];
}

/** `SeparationService.SeparationResult`. */
export interface SeparationResult {
  separation: SeparationView;
  notice?: NoticeAssessment | null;
  statutoryCode?: string | null;
  warnings: string[];
}

/** `EssResignationController.ResignationRequest`. */
export interface ResignationRequest {
  employmentId?: UUID | null;
  proposedLastWorkingDate: ISODate;
  reasonCode?: string | null;
  note?: string | null;
  personalEmail?: string | null;
}

/** `SeparationService.WithdrawalResult`: WITHDRAWN (before approval), WITHDRAWAL_REQUESTED (HR consent) or CANCELLED. */
export interface WithdrawalResult {
  outcome: 'WITHDRAWN' | 'WITHDRAWAL_REQUESTED' | 'CANCELLED' | string;
  separation: SeparationView;
  workflowInstanceId?: UUID | null;
}

/** Separation statuses after which the request is over (`SeparationRecords.CLOSED`). */
export const CLOSED_SEPARATION_STATUSES = ['WITHDRAWN', 'REJECTED', 'CANCELLED'];

// --------------------------------------------------------------------------------------------- endpoints

export const essApi = {
  profile: () => http.get<MyProfile>('/people/me/profile'),
  employments: () => http.get<MyEmployment[]>('/people/me/employments'),
  documents: () => http.get<MyDocument[]>('/people/me/documents'),
  downloadLink: (documentId: UUID) => http.get<DownloadLink>(`/people/me/documents/${documentId}/download-link`),

  familyMembers: () => http.get<FamilyMemberView[]>('/people/me/family-members'),
  addFamilyMember: (body: FamilyMemberRequest) => http.post<FamilyMemberView>('/people/me/family-members', body),
  uploadEvidence: (familyMemberId: UUID, form: FormData) =>
    http.post<UploadedDocument>(`/people/me/family-members/${familyMemberId}/documents`, form),
  dependants: () => http.get<DependantView[]>('/people/me/dependants'),
  validateDependant: (body: RegistrationRequest) => http.post<EligibilityResult>('/people/me/dependants/validate', body),
  registerDependant: (body: RegistrationRequest) => http.post<DependantView>('/people/me/dependants', body),
  endDependant: (id: UUID, body: EndRequest) => http.post<DependantView>(`/people/me/dependants/${id}/end`, body),

  bankAccounts: () => http.get<BankAccountView[]>('/people/me/bank-accounts'),
  requestBankAccount: (body: BankAccountRequest) => http.post<BankSaved>('/people/me/bank-accounts', body),

  /** 204 (undefined) when the employee has no resignation. */
  resignation: () => http.get<SeparationView | undefined>('/people/me/resignation'),
  resign: (body: ResignationRequest) => http.post<SeparationResult>('/people/me/resignation', body),
  withdraw: (id: UUID, note?: string | null) =>
    http.post<WithdrawalResult>(`/people/me/resignation/${id}/withdraw`, note ? { note } : {}),
};
