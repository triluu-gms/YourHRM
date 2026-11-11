import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

// ------------------------------------------------------------------------------------------- family members

/** `FamilyMemberService.FamilyMemberView`: P3 identifiers always masked; with masking the birth date is the year only. */
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

/** `FamilyController.FamilyMemberRequest`. `nationalId`/`taxCode`: null keeps the stored value on update, blank clears. */
export interface FamilyMemberRequest {
  relationshipCode: string;
  fullName: string;
  birthDate?: ISODate | null;
  nationalId?: string | null;
  taxCode?: string | null;
  hasIncomeAboveThreshold?: boolean | null;
  /** Not stored; derives the income flag from the legal threshold. */
  averageMonthlyIncome?: number | null;
  disabled?: boolean | null;
  deceased?: boolean | null;
  version?: number | null;
}

/** `FamilyMemberService.Identifiers` (unmasked, audited sensitive read). */
export interface FamilyMemberIdentifiers {
  id: UUID;
  nationalId?: string | null;
  taxCode?: string | null;
}

/** `core.api.Documents.DocumentView` (subset used here). */
export interface DocumentView {
  id: UUID;
  documentTypeCode: string;
  title?: string | null;
  status: string;
  currentVersionNo: number;
}

// ------------------------------------------------------------------------------------------- dependants

export const DEPENDANT_REASONS = ['CHILD_UNDER_AGE', 'CHILD_STUDENT', 'CHILD_DISABLED', 'SPOUSE', 'PARENT', 'OTHER_SUPPORTED'] as const;
export type DependantReason = (typeof DEPENDANT_REASONS)[number];
export type DependantStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'ENDED';

/** `DependantService.DependantView`; months are first-of-month dates. */
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

/** `FamilyController.RegistrationRequest`; months as `yyyy-MM`. */
export interface RegistrationRequest {
  familyMemberId: UUID;
  employmentId?: UUID | null;
  fromMonth: string;
  toMonth?: string | null;
  reasonCode: string;
  evidenceDocumentIds?: UUID[];
  taxRegistrationRef?: string | null;
}

/** `DependantService.EligibilityResult`; each violation has `code`, `message` and optionally `month`. */
export interface EligibilityResult {
  eligible: boolean;
  claimEmploymentId?: UUID | null;
  violations: { code: string; message: string; month?: string }[];
}

/** `FamilyController.EndRequest`. */
export interface EndRequest {
  toMonth: string;
  version: number;
  evidenceDocumentIds?: UUID[];
}

// ------------------------------------------------------------------------------------------- bank accounts

export const SPLIT_TYPES = ['REMAINDER', 'PERCENT', 'FIXED'] as const;
export type SplitType = (typeof SPLIT_TYPES)[number];

/** `BankAccountService.BankAccountView`; the number is masked (P3), full only via account-number (audited). */
export interface BankAccountView {
  id: UUID;
  personId: UUID;
  bankCode: string;
  bankName?: string | null;
  branchCode?: string | null;
  accountNoMasked: string;
  holderName: string;
  currencyCode?: string | null;
  splitType?: SplitType | null;
  splitValue?: number | null;
  priority: number;
  validFrom: ISODate;
  validTo?: ISODate | null;
  status: string;
  stepUpVerified: boolean;
  verifiedAt?: ISOInstant | null;
  version: number;
}

/** `BankAccountController.BankAccountRequest`. */
export interface BankAccountRequest {
  bankCode: string;
  branchCode?: string | null;
  accountNo: string;
  holderName: string;
  currencyCode?: string | null;
  splitType?: SplitType | null;
  splitValue?: number | null;
  priority?: number | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
}

/** `BankAccountService.Saved`: new account (PENDING approval) + non-blocking warnings (e.g. holder name mismatch). */
export interface BankAccountSaved {
  account: BankAccountView;
  warnings: { code: string; message: string }[];
}

export interface AccountNumber {
  id: UUID;
  accountNo: string;
}

const base = (personId: UUID) => `/people/persons/${personId}`;

export const familyApi = {
  members: (personId: UUID) => http.get<FamilyMemberView[]>(`${base(personId)}/family-members`),
  member: (personId: UUID, id: UUID) => http.get<FamilyMemberView>(`${base(personId)}/family-members/${id}`),
  identifiers: (personId: UUID, id: UUID) =>
    http.get<FamilyMemberIdentifiers>(`${base(personId)}/family-members/${id}/identifiers`),
  createMember: (personId: UUID, body: FamilyMemberRequest) =>
    http.post<FamilyMemberView>(`${base(personId)}/family-members`, body),
  updateMember: (personId: UUID, id: UUID, body: FamilyMemberRequest) =>
    http.put<FamilyMemberView>(`${base(personId)}/family-members/${id}`, body),
  uploadEvidence: (personId: UUID, id: UUID, form: FormData) =>
    http.post<DocumentView>(`${base(personId)}/family-members/${id}/documents`, form),
  /** Evidence documents of a family member (Core document service, owner `hr.family_member`). */
  memberDocuments: (id: UUID) =>
    http.get<DocumentView[]>('/core/documents', { params: { ownerEntityType: 'hr.family_member', ownerEntityId: id } }),

  dependants: (personId: UUID) => http.get<DependantView[]>(`${base(personId)}/dependants`),
  validate: (personId: UUID, body: RegistrationRequest) =>
    http.post<EligibilityResult>(`${base(personId)}/dependants/validate`, body),
  register: (personId: UUID, body: RegistrationRequest) => http.post<DependantView>(`${base(personId)}/dependants`, body),
  end: (personId: UUID, id: UUID, body: EndRequest) => http.post<DependantView>(`${base(personId)}/dependants/${id}/end`, body),

  bankAccounts: (personId: UUID) => http.get<BankAccountView[]>(`${base(personId)}/bank-accounts`),
  accountNumber: (personId: UUID, id: UUID) => http.get<AccountNumber>(`${base(personId)}/bank-accounts/${id}/account-number`),
  createBankAccount: (personId: UUID, body: BankAccountRequest) =>
    http.post<BankAccountSaved>(`${base(personId)}/bank-accounts`, body),
  closeBankAccount: (personId: UUID, id: UUID, body: { validTo: ISODate; version: number }) =>
    http.post<BankAccountView>(`${base(personId)}/bank-accounts/${id}/close`, body),
};
