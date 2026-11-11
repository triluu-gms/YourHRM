import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

// --------------------------------------------------------------------------------------- statutory register

/** `ReportKeys` types (`hr.statutory_report.report_type`); FOREIGN_WORKER / INITIAL_DECLARATION are later phases. */
export type StatutoryReportType = 'LABOUR_BOOK_EXPORT' | 'LABOUR_CHANGE_6M' | 'LABOUR_FLUCTUATION_MONTHLY';
export const STATUTORY_REPORT_TYPES: StatutoryReportType[] = ['LABOUR_CHANGE_6M', 'LABOUR_FLUCTUATION_MONTHLY', 'LABOUR_BOOK_EXPORT'];

/** `statutory_report_status_ck`. */
export type StatutoryReportStatus = 'DRAFT' | 'GENERATED' | 'SUBMITTED' | 'ACKNOWLEDGED' | 'CANCELLED';

/** `StatutoryReportService.CHANNELS`. */
export type SubmissionChannel = 'PORTAL' | 'PAPER' | 'EMAIL' | 'OTHER';
export const SUBMISSION_CHANNELS: SubmissionChannel[] = ['PORTAL', 'PAPER', 'EMAIL', 'OTHER'];

/** Core `Documents.DocumentVersionView`. */
export interface DocumentVersionView {
  versionNo: number;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  sha256?: string | null;
  source?: string | null;
  scanStatus?: string | null;
  createdAt: ISOInstant;
  uploadedBy?: UUID | null;
}

/** Core `Documents.DownloadLink` (signed URL, ≤ 15 min). */
export interface DownloadLink {
  url: string;
  expiresAt: ISOInstant;
  fileName?: string | null;
}

/** Core `DocumentGeneration.Generated`. */
export interface GeneratedDocument {
  documentId: UUID;
  documentNumber?: string | null;
  templateVersionId?: UUID | null;
  mediaType: string;
}

/** `StatutoryReportService.ReportView`. */
export interface StatutoryReportView {
  id: UUID;
  legalEntityId: UUID;
  reportType: StatutoryReportType;
  periodStart: ISODate;
  periodEnd: ISODate;
  dueDate?: ISODate | null;
  templateVersion?: string | null;
  generatedDocumentId?: UUID | null;
  generatedAt?: ISOInstant | null;
  generatedBy?: UUID | null;
  rowCount?: number | null;
  submittedAt?: ISOInstant | null;
  submittedBy?: UUID | null;
  submissionChannel?: SubmissionChannel | null;
  submissionRef?: string | null;
  status: StatutoryReportStatus;
  version: number;
  hrNotes?: Record<string, string> | null;
  receiptDocumentId?: UUID | null;
  versions?: DocumentVersionView[] | null;
  receiptDocumentIds?: UUID[] | null;
}

/** `StatutoryReportService.Generation` — `content` is the data behind the file (shape depends on the type). */
export interface StatutoryReportGeneration {
  report: StatutoryReportView;
  content?: unknown;
}

/** `StatutoryReportController.GenerateBody`. 6-monthly: year + period; monthly: year + month; labour book: asOf. */
export interface GenerateReportRequest {
  legalEntityId: UUID;
  reportType: StatutoryReportType;
  year?: number | null;
  period?: string | null;
  month?: number | null;
  asOf?: ISODate | null;
  notes?: Record<string, string> | null;
}

/** `StatutoryReportController.SubmitBody`. */
export interface SubmitReportRequest {
  version: number;
  submittedOn: ISODate;
  channel?: SubmissionChannel | null;
  submissionRef?: string | null;
}

/** `LabourReportService.LabourChange` (6-monthly labour change report, on screen). */
export interface LabourChange {
  legalEntityId: UUID;
  periodCode: string;
  periodStart: ISODate;
  periodEnd: ISODate;
  dueDate?: ISODate | null;
  formCode?: string | null;
  titleVi?: string | null;
  headcountStart: number;
  hires: number;
  terminations: number;
  headcountEnd: number;
  femaleEnd: number;
  hiresByType: Record<string, number>;
  terminationsByType: Record<string, number>;
  contractTypesEnd: Record<string, number>;
  qualificationsEnd: Record<string, number>;
  notes?: Record<string, string> | null;
}

/** `LabourReportService.Movement` (one fluctuation row; identifiers masked unless `identityRevealed`). */
export interface LabourMovement {
  employmentId: UUID;
  employeeNo: string;
  fullName: string;
  birthDate?: ISODate | null;
  genderCode?: string | null;
  nationalId?: string | null;
  siNumber?: string | null;
  jobTitle?: string | null;
  movementType: string;
  movementDate: ISODate;
  contractTypeCode?: string | null;
  reasonCode?: string | null;
  legalGroundCode?: string | null;
}

/** `LabourReportService.Fluctuation` (monthly fluctuation notice, on screen). */
export interface LabourFluctuation {
  legalEntityId: UUID;
  month: string;
  periodStart: ISODate;
  periodEnd: ISODate;
  dueDate?: ISODate | null;
  formCode?: string | null;
  titleVi?: string | null;
  columns: string[];
  hires: number;
  terminations: number;
  identityRevealed: boolean;
  rows: LabourMovement[];
}

export interface StatutoryReportListParams {
  legalEntityId: UUID;
  reportType?: StatutoryReportType;
  year?: number;
}

export const statutoryReportsApi = {
  list: (params: StatutoryReportListParams) =>
    http.get<StatutoryReportView[]>('/people/statutory-reports', { params: { ...params } }),
  get: (id: UUID) => http.get<StatutoryReportView>(`/people/statutory-reports/${id}`),
  labourChange: (legalEntityId: UUID, year: number, period: string) =>
    http.get<LabourChange>('/people/statutory-reports/labour-change', { params: { legalEntityId, year, period } }),
  /** `month` is `YYYY-MM`. */
  labourFluctuation: (legalEntityId: UUID, month: string) =>
    http.get<LabourFluctuation>('/people/statutory-reports/labour-fluctuation', { params: { legalEntityId, month } }),
  downloadLink: (id: UUID, fileVersion?: number) =>
    http.get<DownloadLink>(`/people/statutory-reports/${id}/download`, { params: { fileVersion } }),
  generate: (body: GenerateReportRequest) => http.post<StatutoryReportGeneration>('/people/statutory-reports', body),
  submit: (id: UUID, body: SubmitReportRequest) => http.post<StatutoryReportView>(`/people/statutory-reports/${id}/submit`, body),
  receipt: (id: UUID, body: FormData) => http.post<StatutoryReportView>(`/people/statutory-reports/${id}/receipt`, body),
  cancel: (id: UUID, version: number) => http.post<StatutoryReportView>(`/people/statutory-reports/${id}/cancel`, { version }),
  pdf: (id: UUID, notes?: Record<string, string>) =>
    http.post<GeneratedDocument>(`/people/statutory-reports/${id}/pdf`, notes ? { notes } : undefined),
};

// ------------------------------------------------------------------------------------------ labour book

/** `LabourBookService.Salary`. */
export interface LabourBookSalary {
  payBasis?: string | null;
  salaryKind?: string | null;
  baseAmount?: number | null;
  currencyCode?: string | null;
  validFrom?: ISODate | null;
}

/** `LabourBookService.Entry` (national id masked on screen). */
export interface LabourBookEntry {
  employmentId: UUID;
  employeeNo: string;
  fullName: string;
  genderCode?: string | null;
  birthDate?: ISODate | null;
  nationalityCode?: string | null;
  nationalIdType?: string | null;
  nationalId?: string | null;
  residenceAddress?: string | null;
  residenceProvinceCode?: string | null;
  residenceCommuneCode?: string | null;
  qualificationLevelCode?: string | null;
  vocationalSkillLevelCode?: string | null;
  orgUnitId?: UUID | null;
  positionId?: UUID | null;
  jobTitleId?: UUID | null;
  jobTitle?: string | null;
  contractTypeCode?: string | null;
  contractNo?: string | null;
  contractStartDate?: ISODate | null;
  contractEndDate?: ISODate | null;
  salary?: LabourBookSalary | null;
  siParticipating: boolean;
  training?: string[] | null;
  discipline?: string[] | null;
  hireDate?: ISODate | null;
  employmentStatus?: string | null;
  terminationDate?: ISODate | null;
  separationType?: string | null;
  legalGroundCode?: string | null;
}

/** `LabourBookService.Book`. */
export interface LabourBook {
  legalEntityId: UUID;
  asOf: ISODate;
  identityRevealed: boolean;
  salaryColumns: number;
  entries: LabourBookEntry[];
  notes?: string[] | null;
}

export const labourBookApi = {
  book: (legalEntityId: UUID, asOf?: ISODate) =>
    http.get<LabourBook>('/people/reports/labour-book', { params: { legalEntityId, asOf } }),
  export: (legalEntityId: UUID, asOf?: ISODate) =>
    http.download('/people/reports/labour-book/export', { params: { legalEntityId, asOf } }),
  entryPdf: (employmentId: UUID, asOf?: ISODate) =>
    http.post<GeneratedDocument>(`/people/reports/labour-book/employments/${employmentId}/pdf`, undefined, { params: { asOf } }),
};

// ------------------------------------------------------------------------------ dependant registrations

/** `DependantRegistrationReport.Line`. */
export interface DependantRegistrationLine {
  no: number;
  changeType: string;
  employmentId: UUID;
  employeeNo: string;
  taxpayerName: string;
  taxpayerTaxCode?: string | null;
  dependantId: UUID;
  dependantName: string;
  birthDate?: ISODate | null;
  dependantIdNo?: string | null;
  relationshipCode?: string | null;
  reasonCode?: string | null;
  fromMonth?: ISODate | null;
  toMonth?: ISODate | null;
  status?: string | null;
}

/** `DependantRegistrationReport.Report`. */
export interface DependantRegistrationReport {
  legalEntityId: UUID;
  fromMonth: ISODate;
  toMonth: ISODate;
  formCode?: string | null;
  registeredCount: number;
  endedCount: number;
  unmasked: boolean;
  lines: DependantRegistrationLine[];
}

/** `DependantRegistrationReport.PdfResult`. */
export interface DependantRegistrationPdf {
  documentId: UUID;
  documentNumber?: string | null;
  mediaType: string;
  download?: DownloadLink | null;
}

/** `fromMonth` / `toMonth` are `YYYY-MM`. */
export interface MonthRange {
  legalEntityId: UUID;
  fromMonth: string;
  toMonth: string;
}

export const dependantRegistrationsApi = {
  view: (p: MonthRange) => http.get<DependantRegistrationReport>('/people/reports/dependant-registrations', { params: { ...p } }),
  export: (p: MonthRange) => http.download('/people/reports/dependant-registrations/export.xlsx', { params: { ...p } }),
  pdf: (p: MonthRange) =>
    http.post<DependantRegistrationPdf>('/people/reports/dependant-registrations/pdf', undefined, { params: { ...p } }),
};

// ------------------------------------------------------------------------------------- opening balance

/** `OpeningBalanceReportService.LegalEntitySummary`; `salaryTotal` null when masked. */
export interface OpeningBalanceSummary {
  legalEntityId: UUID;
  employees: number;
  activeHeadcount: number;
  preHires: number;
  salaryTotal?: number | null;
  dependants: number;
  missingTaxCode: number;
  missingSiNumber: number;
  missingBankAccount: number;
}

/** `OpeningBalanceReportService.Discrepancy`. */
export interface OpeningBalanceDiscrepancy {
  employmentId: UUID;
  employeeNo: string;
  fullName: string;
  legalEntityId: UUID;
  status: string;
  issues: string[];
}

/** `OpeningBalanceReportService.Report` (R20). */
export interface OpeningBalanceReport {
  asOf: ISODate;
  legalEntityId?: UUID | null;
  salaryMasked: boolean;
  legalEntities: OpeningBalanceSummary[];
  discrepancies: OpeningBalanceDiscrepancy[];
}

export interface OpeningBalanceParams {
  legalEntityId?: UUID;
  asOf?: ISODate;
  openingLoadOnly?: boolean;
}

export const openingBalanceApi = {
  report: (p: OpeningBalanceParams) => http.get<OpeningBalanceReport>('/people/opening-balance/report', { params: { ...p } }),
  export: (p: OpeningBalanceParams) => http.download('/people/opening-balance/report/export', { params: { ...p } }),
};

// ------------------------------------------------------------------------------------- integration inbox

/** `IntegrationInboxController.STATUSES`. */
export type InboxStatus = 'RECEIVED' | 'PROCESSED' | 'FAILED' | 'SKIPPED';
export const INBOX_STATUSES: InboxStatus[] = ['RECEIVED', 'PROCESSED', 'FAILED', 'SKIPPED'];

/** `HrInbox.Message` (no payloads are stored). */
export interface InboxMessage {
  consumer: string;
  eventId: UUID;
  eventType: string;
  status: InboxStatus;
  attempts: number;
  lastError?: string | null;
  receivedAt: ISOInstant;
  processedAt?: ISOInstant | null;
}

export interface InboxParams {
  status?: InboxStatus;
  consumer?: string;
  limit?: number;
}

export const integrationInboxApi = {
  list: (p: InboxParams) => http.get<InboxMessage[]>('/people/integration/inbox', { params: { ...p } }),
};

// --------------------------------------------------------------------------------- Core document links

/** Signed URL of a Core document (generated PDFs, receipts); access follows the owning record. */
export const documentLinkApi = {
  link: (documentId: UUID, versionNo?: number) =>
    http.post<DownloadLink>(`/core/documents/${documentId}/download-link`, undefined, { params: { versionNo } }),
};
