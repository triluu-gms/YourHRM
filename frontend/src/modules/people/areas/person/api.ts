import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';
import type { PersonView } from '../../common';

// ------------------------------------------------------------------------------------------------- persons

/** `PersonService.Field` — P3 fields that can be unmasked or cleared. */
export type PersonField = 'NATIONAL_ID' | 'TAX_CODE' | 'SI_NUMBER' | 'HI_CARD_NO' | 'ETHNICITY' | 'RELIGION' | 'BIRTH_DATE';
export const PERSON_FIELDS: PersonField[] = ['NATIONAL_ID', 'TAX_CODE', 'SI_NUMBER', 'HI_CARD_NO', 'ETHNICITY', 'RELIGION', 'BIRTH_DATE'];
/** Fields an identifier change may clear (birth date is a person attribute, not an identifier). */
export const CLEARABLE_FIELDS: PersonField[] = ['NATIONAL_ID', 'TAX_CODE', 'SI_NUMBER', 'HI_CARD_NO', 'ETHNICITY', 'RELIGION'];

/** `PersonRules.NATIONAL_ID_TYPES`. */
export type NationalIdType = 'CCCD' | 'CMND' | 'PASSPORT';
export const NATIONAL_ID_TYPES: NationalIdType[] = ['CCCD', 'CMND', 'PASSPORT'];
/** `PersonRules.SI_BOOK_CUSTODY`. */
export const SI_BOOK_CUSTODY = ['EMPLOYER', 'EMPLOYEE', 'RETURNED'] as const;

/** Core code lists People validates against (`PersonKeys.CL_*`). */
export const CodeLists = {
  GENDER: 'GENDER',
  ETHNICITY: 'ETHNICITY',
  RELIGION: 'RELIGION',
  QUALIFICATION_LEVEL: 'QUALIFICATION_LEVEL',
  VOCATIONAL_SKILL_LEVEL: 'VOCATIONAL_SKILL_LEVEL',
  GENERAL_EDUCATION: 'GENERAL_EDUCATION',
  VISA_TYPE: 'VISA_TYPE',
} as const;

/** `PersonController.PersonDataRequest` (PUT semantics: every field is replaced). */
export interface PersonData {
  familyName: string;
  middleName?: string | null;
  givenName: string;
  latinName?: string | null;
  preferredName?: string | null;
  birthDate?: ISODate | null;
  genderCode?: string | null;
  nationalityCode?: string | null;
  hiFacilityCode?: string | null;
  siBookCustody?: string | null;
}

/** `PersonController.IdentifiersRequest`; null = unchanged on a change. */
export interface IdentifiersRequest {
  nationalIdType?: NationalIdType | null;
  nationalId?: string | null;
  taxCode?: string | null;
  legacyTaxCode?: string | null;
  siNumber?: string | null;
  hiCardNo?: string | null;
  ethnicityCode?: string | null;
  religionCode?: string | null;
}

export interface UpdatePersonRequest {
  version: number;
  person: PersonData;
}

export interface ChangeIdentifiersRequest {
  version: number;
  set?: IdentifiersRequest | null;
  clear?: PersonField[];
}

/** `PersonController.MatchRequest`. */
export interface MatchRequest {
  nationalIdType?: NationalIdType | null;
  nationalId?: string | null;
  nationalityCode?: string | null;
  passportNo?: string | null;
  taxCode?: string | null;
  siNumber?: string | null;
}

/** `PersonService.Candidate` (masked: name, birth year, employee numbers). */
export interface Candidate {
  personId: UUID;
  fullName: string;
  birthYear?: number | null;
  matchedOn: string;
  employeeNos: string[];
}

/** `PersonService.MatchResult`. */
export interface MatchResult {
  blocking: Candidate[];
  warnings: Candidate[];
}

/** `PersonService.Unmasked`. */
export interface Unmasked {
  personId: UUID;
  values: Record<string, string>;
  withheld: string[];
}

/** `PersonMergeService.MergePreview`. */
export interface MergePreview {
  personId: UUID;
  duplicatePersonId: UUID;
  rowsToMove: Record<string, number>;
  identifiersTaken: string[];
  adjustments: string[];
  conflicts: string[];
  mergeable: boolean;
  merged: boolean;
}

export const personApi = {
  get: (personId: UUID) => http.get<PersonView>(`/people/persons/${personId}`),
  update: (personId: UUID, body: UpdatePersonRequest) => http.put<PersonView>(`/people/persons/${personId}`, body),
  changeIdentifiers: (personId: UUID, body: ChangeIdentifiersRequest) =>
    http.put<PersonView>(`/people/persons/${personId}/identifiers`, body),
  unmask: (personId: UUID, fields: PersonField[], purpose: string) =>
    http.post<Unmasked>(`/people/persons/${personId}/unmask`, { fields, purpose }),
  match: (body: MatchRequest) => http.post<MatchResult>('/people/persons/match', body),
  mergePreview: (personId: UUID, duplicatePersonId: UUID) =>
    http.get<MergePreview>(`/people/persons/${personId}/merge-preview`, { params: { duplicatePersonId } }),
  merge: (personId: UUID, body: { duplicatePersonId: UUID; reason: string; version: number }) =>
    http.post<MergePreview>(`/people/persons/${personId}/merge`, body),
};

// ----------------------------------------------------------------------------------------------- addresses

/** `PersonAddressService.TYPES`. */
export const ADDRESS_TYPES = ['PERMANENT', 'CURRENT', 'TEMPORARY', 'HOMETOWN', 'BIRTH_PLACE'] as const;
export type AddressType = (typeof ADDRESS_TYPES)[number];

/** `PersonDetailsController.AddressRequest`. */
export interface AddressData {
  countryCode?: string | null;
  provinceCode?: string | null;
  communeCode?: string | null;
  addressLine?: string | null;
}

/** `PersonAddressService.AddressView`; `readOnly` = legacy (3-level) address kept for history. */
export interface AddressView {
  id: UUID;
  addressType: AddressType;
  countryCode?: string | null;
  provinceCode?: string | null;
  communeCode?: string | null;
  addressLine?: string | null;
  legacyProvinceCode?: string | null;
  legacyDistrictCode?: string | null;
  legacyCommuneCode?: string | null;
  legacyAddressText?: string | null;
  mappingStatus?: string | null;
  readOnly: boolean;
  validFrom: ISODate;
  validTo?: ISODate | null;
  source?: string | null;
  version: number;
}

export const addressApi = {
  list: (personId: UUID, asOf?: ISODate) =>
    http.get<AddressView[]>(`/people/persons/${personId}/addresses`, { params: { asOf } }),
  add: (personId: UUID, body: { addressType: AddressType; validFrom: ISODate; address: AddressData }) =>
    http.post<AddressView>(`/people/persons/${personId}/addresses`, body),
  correct: (personId: UUID, addressId: UUID, body: { version: number; address: AddressData }) =>
    http.put<AddressView>(`/people/persons/${personId}/addresses/${addressId}`, body),
  end: (personId: UUID, addressId: UUID, body: { version: number; validTo: ISODate }) =>
    http.post<AddressView>(`/people/persons/${personId}/addresses/${addressId}/end`, body),
};

// ------------------------------------------------------------------------------------------------ contacts

/** Contact types accepted by `PersonRules.contactValue`. */
export const CONTACT_TYPES = ['MOBILE', 'HOME_PHONE', 'WORK_PHONE', 'PERSONAL_EMAIL', 'WORK_EMAIL', 'OTHER'] as const;
export type ContactType = (typeof CONTACT_TYPES)[number];

/** `PersonContactService.ContactView`. */
export interface ContactView {
  id: UUID;
  contactType: ContactType;
  value: string;
  primary: boolean;
  verified: boolean;
  usableForFormerEmployeeLogin: boolean;
  version: number;
}

export const contactApi = {
  list: (personId: UUID) => http.get<ContactView[]>(`/people/persons/${personId}/contacts`),
  add: (personId: UUID, body: { contactType: ContactType; value: string; primary?: boolean }) =>
    http.post<ContactView>(`/people/persons/${personId}/contacts`, body),
  change: (personId: UUID, contactId: UUID, body: { version: number; value: string; primary?: boolean }) =>
    http.put<ContactView>(`/people/persons/${personId}/contacts/${contactId}`, body),
  remove: (personId: UUID, contactId: UUID) => http.delete(`/people/persons/${personId}/contacts/${contactId}`),
};

// -------------------------------------------------------------------------------------- identity documents

/** `IdentityDocumentService.TYPES` / `STATUSES` / visa-typed documents. */
export const DOCUMENT_TYPES = ['PASSPORT', 'WORK_VISA', 'TEMP_RESIDENCE_CARD', 'PERMANENT_RESIDENCE_CARD', 'OLD_CMND', 'OLD_CCCD', 'OTHER'] as const;
export type DocumentType = (typeof DOCUMENT_TYPES)[number];
export const DOCUMENT_STATUSES = ['ACTIVE', 'EXPIRED', 'REVOKED', 'SUPERSEDED'] as const;
export type DocumentStatus = (typeof DOCUMENT_STATUSES)[number];
export const VISA_TYPED: DocumentType[] = ['WORK_VISA', 'TEMP_RESIDENCE_CARD', 'PERMANENT_RESIDENCE_CARD'];

/** `PersonDetailsController.DocumentRequest`; `docNo` null on change = unchanged. */
export interface DocumentRequest {
  docType?: DocumentType | null;
  docNo?: string | null;
  issuingCountryCode?: string | null;
  issueDate?: ISODate | null;
  issuePlace?: string | null;
  expiryDate?: ISODate | null;
  visaTypeCode?: string | null;
  status?: DocumentStatus | null;
}

/** `IdentityDocumentService.DocumentView` (number masked). */
export interface IdentityDocumentView {
  id: UUID;
  personId: UUID;
  docType: DocumentType;
  docNoMasked?: string | null;
  issuingCountryCode?: string | null;
  issueDate?: ISODate | null;
  issuePlace?: string | null;
  expiryDate?: ISODate | null;
  visaTypeCode?: string | null;
  scanDocumentId?: UUID | null;
  status: DocumentStatus;
  legacyTaxCode: boolean;
  version: number;
}

export const identityDocumentApi = {
  list: (personId: UUID) => http.get<IdentityDocumentView[]>(`/people/persons/${personId}/identity-documents`),
  add: (personId: UUID, body: DocumentRequest) =>
    http.post<IdentityDocumentView>(`/people/persons/${personId}/identity-documents`, body),
  change: (personId: UUID, documentId: UUID, body: { version: number; document: DocumentRequest }) =>
    http.put<IdentityDocumentView>(`/people/persons/${personId}/identity-documents/${documentId}`, body),
  attachScan: (personId: UUID, documentId: UUID, coreDocumentId: UUID) =>
    http.put<IdentityDocumentView>(`/people/persons/${personId}/identity-documents/${documentId}/scan`, {
      documentId: coreDocumentId,
    }),
  unmask: (personId: UUID, documentId: UUID, purpose: string) =>
    http.post<Record<string, string>>(`/people/persons/${personId}/identity-documents/${documentId}/unmask`, { purpose }),
};

// ------------------------------------------------------------------------------------------- qualifications

/** `PersonDetailsController.QualificationRequest`. */
export interface QualificationRequest {
  qualificationLevelCode: string;
  vocationalSkillLevelCode?: string | null;
  generalEducationCode?: string | null;
  fieldOfStudy?: string | null;
  institutionName?: string | null;
  graduationYear?: number | null;
  highest?: boolean;
}

/** `QualificationService.QualificationView`. */
export interface QualificationView {
  id: UUID;
  qualificationLevelCode: string;
  vocationalSkillLevelCode?: string | null;
  generalEducationCode?: string | null;
  fieldOfStudy?: string | null;
  institutionName?: string | null;
  graduationYear?: number | null;
  highest: boolean;
  scanDocumentId?: UUID | null;
  version: number;
}

export const qualificationApi = {
  list: (personId: UUID) => http.get<QualificationView[]>(`/people/persons/${personId}/qualifications`),
  add: (personId: UUID, body: QualificationRequest) =>
    http.post<QualificationView>(`/people/persons/${personId}/qualifications`, body),
  change: (personId: UUID, qualificationId: UUID, body: { version: number; qualification: QualificationRequest }) =>
    http.put<QualificationView>(`/people/persons/${personId}/qualifications/${qualificationId}`, body),
  attachScan: (personId: UUID, qualificationId: UUID, coreDocumentId: UUID) =>
    http.put<QualificationView>(`/people/persons/${personId}/qualifications/${qualificationId}/scan`, {
      documentId: coreDocumentId,
    }),
};

// ----------------------------------------------------------------------------------- Core document service

/** Core document types People registers for scans (`PersonKeys.DOC_*`). */
export const ScanDocumentTypes = {
  IDENTITY_DOC: 'HR_IDENTITY_DOC_SCAN',
  QUALIFICATION: 'HR_QUALIFICATION_SCAN',
} as const;

/** Minimal `Documents.DocumentView` from `POST /core/documents` (PENDING_SCAN until the AV scan is clean). */
export interface CoreDocumentView {
  id: UUID;
  documentTypeCode: string;
  status: string;
  title?: string | null;
  currentVersionNo: number;
}

export interface DownloadLink {
  url: string;
  expiresAt: ISOInstant;
  fileName?: string | null;
}

export const coreDocumentApi = {
  /** Upload to Core's document service; the owner is the People record the scan belongs to. */
  upload: (file: File, documentTypeCode: string, ownerEntityId: UUID, subjectPersonId: UUID) => {
    const form = new FormData();
    form.append('file', file);
    form.append('documentTypeCode', documentTypeCode);
    form.append('ownerEntityId', ownerEntityId);
    form.append('subjectPersonId', subjectPersonId);
    return http.post<CoreDocumentView>('/core/documents', form);
  },
  downloadLink: (documentId: UUID) => http.post<DownloadLink>(`/core/documents/${documentId}/download-link`),
};

// --------------------------------------------------------------------------------------------------- hires

/** `HireService.MatchOption` options. */
export type HireOption = 'NEW' | 'REHIRE' | 'CONCURRENT' | 'ASSIGNMENT_CHANGE';
export type HireType = 'NEW' | 'REHIRE' | 'CONCURRENT';

/** `HireController.MatchBody`. */
export interface HireMatchRequest extends MatchRequest {
  legalEntityId?: UUID | null;
  hireDate?: ISODate | null;
}

/** `HireService.EmploymentSummary`. */
export interface HireEmploymentSummary {
  employmentId: UUID;
  legalEntityId?: UUID | null;
  employeeNo: string;
  status: string;
  hireDate?: ISODate | null;
  endDate?: ISODate | null;
}

/** `HireService.MatchOption`. */
export interface MatchOption {
  candidate: Candidate;
  employments: HireEmploymentSummary[];
  options: HireOption[];
}

/** `HireService.MatchView`. */
export interface HireMatchView {
  blocking: MatchOption[];
  warnings: Candidate[];
}

/** `HireController.PersonBody` (new person: data + identifiers flattened). */
export interface HirePersonBody extends PersonData, Omit<IdentifiersRequest, 'legacyTaxCode' | 'ethnicityCode' | 'religionCode'> {
  acknowledgeWarnings?: boolean;
}

/** `HireController.AssignmentBody`. */
export interface HireAssignmentBody {
  orgUnitId: UUID;
  positionId?: UUID | null;
  jobTitleId?: UUID | null;
  gradeId?: UUID | null;
  managerEmploymentId?: UUID | null;
  locationId?: UUID | null;
  costCentreId?: UUID | null;
  fte?: number | null;
}

/** `ContractType` enum (domain). */
export const CONTRACT_TYPES = ['PROBATION', 'DEFINITE', 'INDEFINITE', 'SEASONAL', 'APPRENTICE', 'INTERNSHIP', 'SERVICE'] as const;
export type ContractTypeCode = (typeof CONTRACT_TYPES)[number];
/** Contract types with a mandatory end date (`ContractType` first flag). */
export const CONTRACT_TYPES_WITH_END: ContractTypeCode[] = ['DEFINITE', 'SEASONAL'];

/** `HireController.ContractBody`. */
export interface HireContractBody {
  contractTypeCode: ContractTypeCode;
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

/** `SalaryRules.BASES` / `KINDS`. */
export const PAY_BASES = ['GROSS', 'NET'] as const;
export const SALARY_KINDS = ['MONTHLY', 'DAILY', 'HOURLY', 'PIECE'] as const;

export interface HireAllowanceBody {
  componentCode: string;
  amount: number;
  inSiSalary?: boolean;
}

/** `HireController.SalaryBody`. */
export interface HireSalaryBody {
  payBasis?: (typeof PAY_BASES)[number] | null;
  salaryKind?: (typeof SALARY_KINDS)[number] | null;
  baseSalaryAmount: number;
  agreedNetAmount?: number | null;
  netGuaranteedScope?: string[] | null;
  siSalaryAmount?: number | null;
  currencyCode?: string | null;
  payScaleCode?: string | null;
  payScaleStepCode?: string | null;
  probationPct?: number | null;
  allowances?: HireAllowanceBody[];
}

/** Worker categories (DDL comment on `hr.employment.worker_category_code`). */
export const WORKER_CATEGORIES = ['EMPLOYEE', 'EXPAT', 'APPRENTICE', 'COLLABORATOR'] as const;

/** `HireController.HireBody`: either `personId` (existing person) or `person` (new). */
export interface HireRequest {
  personId?: UUID | null;
  person?: HirePersonBody | null;
  hireType?: HireType | null;
  legalEntityId: UUID;
  hireDate: ISODate;
  originalHireDate?: ISODate | null;
  seniorityDate?: ISODate | null;
  probationEndDate?: ISODate | null;
  workerCategoryCode: string;
  employeeCategoryCode?: string | null;
  workingRegimeCode?: string | null;
  assignment: HireAssignmentBody;
  contract: HireContractBody;
  salary: HireSalaryBody;
  signatoryName?: string | null;
  signatoryTitle?: string | null;
}

/** `assignment.Warning`. */
export interface HireWarning {
  code: string;
  message?: string | null;
  [k: string]: unknown;
}

/** `HireService.HireResult`. */
export interface HireResult {
  employmentId: UUID;
  personId: UUID;
  personCreated: boolean;
  employeeNo: string;
  hireType: HireType;
  status: string;
  workflowInstanceId?: UUID | null;
  decisionId?: UUID | null;
  decisionNo?: string | null;
  assignmentId?: UUID | null;
  contractId?: UUID | null;
  salaryRecordId?: UUID | null;
  warnings: HireWarning[];
  missingData: string[];
}

/** `HireController.CancelBody`. */
export interface CancelHireRequest {
  reasonCode?: string | null;
  reason: string;
  version?: number | null;
}

export const hireApi = {
  match: (body: HireMatchRequest) => http.post<HireMatchView>('/people/hires/match', body),
  hire: (body: HireRequest) => http.post<HireResult>('/people/hires', body),
  /** Returns the cancelled `EmploymentView`; only its id/status are used here. */
  cancel: (employmentId: UUID, body: CancelHireRequest) =>
    http.post<{ id: UUID; status: string }>(`/people/hires/${employmentId}/cancel`, body),
};
