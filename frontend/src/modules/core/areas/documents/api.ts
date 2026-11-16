import { http, type ISODate, type ISOInstant, type UUID } from '@/shared/api';

/**
 * The document service (BR-CORE-100/101, US-CORE-100..102), served by `DocumentController`.
 *
 * Every module stores files here. A *document type* is registered at deploy and decides the access rules: viewing
 * needs its `viewPermission` and uploading its `managePermission`, both on the record the document belongs to - so
 * there is no permission of its own, and no tenant-wide listing either. Documents are read per owner record
 * (`ownerEntityType` + `ownerEntityId`) or by id. A file is usable only once its antivirus scan came back clean,
 * and it is downloaded through a signed URL that lives at most 15 minutes.
 */

/** `document_conf_ck`; downloads of the last two are audited (BR-CORE-101). */
export const CONFIDENTIALITIES = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'] as const;
export type Confidentiality = (typeof CONFIDENTIALITIES)[number];

/** `document_status_ck`. */
export const DOCUMENT_STATUSES = ['PENDING_SCAN', 'ACTIVE', 'QUARANTINED', 'ARCHIVED', 'PURGED'] as const;
export type DocumentStatus = (typeof DOCUMENT_STATUSES)[number];

/** `document_version_scan_ck`; SKIPPED is a file the platform generated itself. */
export const SCAN_STATUSES = ['PENDING', 'CLEAN', 'INFECTED', 'ERROR', 'SKIPPED'] as const;
export type ScanStatus = (typeof SCAN_STATUSES)[number];

/** `DocumentTypeDefinition`: what a module may store, and who may see it. */
export interface DocumentTypeDefinition {
  code: string;
  ownerModule: string;
  ownerEntityType: string;
  nameVi: string;
  nameEn?: string | null;
  defaultConfidentiality: Confidentiality;
  retentionClass: string;
  viewPermission: string;
  managePermission: string;
  /** Empty = the tenant's default media-type list. */
  mediaTypes: string[];
  /** A lower per-type limit than the tenant's upload limit, or null. */
  maxSizeBytes?: number | null;
}

/** `Documents.DocumentVersionView`. */
export interface DocumentVersionView {
  versionNo: number;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  source: string;
  scanStatus: ScanStatus;
  createdAt: ISOInstant;
  uploadedBy?: UUID | null;
}

/** `Documents.DocumentView`. */
export interface DocumentView {
  id: UUID;
  documentTypeCode: string;
  ownerModule: string;
  ownerEntityType: string;
  ownerEntityId: UUID;
  legalEntityId?: UUID | null;
  title: string;
  confidentiality: Confidentiality;
  retentionClass: string;
  status: DocumentStatus;
  /** 0 until the first version is scanned clean. */
  currentVersionNo: number;
  versions: DocumentVersionView[];
}

/** `Documents.DownloadLink`: signed URL, at most 15 minutes (NFR-CORE-010). */
export interface DownloadLink {
  url: string;
  expiresAt: ISOInstant;
  fileName?: string | null;
}

export interface UploadRequest {
  file: File;
  documentTypeCode: string;
  ownerEntityId: UUID;
  legalEntityId?: UUID | null;
  subjectPersonId?: UUID | null;
  title?: string | null;
  confidentiality?: Confidentiality | null;
}

/** Only a clean (or platform-generated) version can be downloaded - `DocumentService.downloadLink` refuses the rest. */
export function isDownloadable(version: DocumentVersionView): boolean {
  return version.scanStatus === 'CLEAN' || version.scanStatus === 'SKIPPED';
}

/** True while a scan may still land, so the list is worth refreshing (the scan should take under a minute). */
export function isScanPending(documents: DocumentView[]): boolean {
  return documents.some((d) => d.versions.some((v) => v.scanStatus === 'PENDING'));
}

/** The version shown by default: the current one, else the newest uploaded. */
export function currentVersion(d: DocumentView): DocumentVersionView | undefined {
  return d.versions.find((v) => v.versionNo === d.currentVersionNo) ?? d.versions.at(-1);
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function multipart(file: File, fields: Record<string, string | null | undefined> = {}): FormData {
  const form = new FormData();
  form.append('file', file);
  for (const [k, v] of Object.entries(fields))
    if (v !== null && v !== undefined && v !== '') form.append(k, v);
  return form;
}

export const documentsApi = {
  /** The registered types; reference data for the upload component, readable by anyone signed in. */
  types: () => http.get<DocumentTypeDefinition[]>('/core/document-types'),
  /** Documents of one owner record. There is no tenant-wide listing. */
  byOwner: (ownerEntityType: string, ownerEntityId: UUID) =>
    http.get<DocumentView[]>('/core/documents', { params: { ownerEntityType, ownerEntityId } }),
  get: (documentId: UUID) => http.get<DocumentView>(`/core/documents/${documentId}`),
  upload: ({ file, ...fields }: UploadRequest) =>
    http.post<DocumentView>(
      '/core/documents',
      multipart(file, { ...fields, title: fields.title ?? file.name }),
    ),
  addVersion: (documentId: UUID, file: File) =>
    http.post<DocumentView>(`/core/documents/${documentId}/versions`, multipart(file)),
  downloadLink: (documentId: UUID, versionNo?: number) =>
    http.post<DownloadLink>(`/core/documents/${documentId}/download-link`, undefined, {
      params: { versionNo },
    }),
};

/**
 * Tenant document templates (BR-CORE-102, US-CORE-103/104), served by `DocumentTemplateController`.
 *
 * A module registers a *definition* (its code, purpose and merge fields); a tenant creates a *template* for that
 * definition - tenant-wide or for one legal entity - and uploads *versions* (DOCX or HTML) whose placeholders are
 * checked against the definition. A version is previewed with sample data and then published from a date; exactly
 * one version is published per date, and generation picks the version valid on the document's date.
 */

/** `DocumentTemplateRegistry.PURPOSES`. */
export const TEMPLATE_PURPOSES = [
  'LABOUR_CONTRACT',
  'DECISION',
  'CERTIFICATE',
  'PAYSLIP',
  'LETTER',
  'REPORT',
] as const;
export type TemplatePurpose = (typeof TEMPLATE_PURPOSES)[number];

/** `document_template_version_src_ck` - what the uploaded file is. */
export const SOURCE_FORMATS = ['HTML', 'DOCX'] as const;
export type SourceFormat = (typeof SOURCE_FORMATS)[number];

/** `document_template_version_out_ck` - what generation produces. HTML always renders to PDF/A. */
export const OUTPUT_FORMATS = ['PDF', 'DOCX'] as const;
export type OutputFormat = (typeof OUTPUT_FORMATS)[number];

export const VERSION_STATUSES = ['DRAFT', 'PUBLISHED', 'RETIRED'] as const;
export type VersionStatus = (typeof VERSION_STATUSES)[number];

/** `DocumentTemplateDefinition.MergeField`: `image` fields take a picture, the others text. */
export interface MergeField {
  key: string;
  labelVi: string;
  labelEn?: string | null;
  sample?: string | null;
  image: boolean;
}

/** `DocumentTemplateDefinition`: what a module offers, not what the tenant has. */
export interface TemplateDefinition {
  code: string;
  ownerModule: string;
  purpose: TemplatePurpose;
  nameVi: string;
  nameEn?: string | null;
  documentTypeCode: string;
  mergeFields: MergeField[];
}

/** `DocumentTemplateService.TemplateVersionView`; `mergeFields` are the keys this version actually uses. */
export interface TemplateVersionView {
  id: UUID;
  versionNo: number;
  sourceFormat: SourceFormat;
  outputFormat: OutputFormat;
  status: VersionStatus;
  validFrom: ISODate;
  validTo?: ISODate | null;
  mergeFields: string[];
}

/** `DocumentTemplateService.TemplateView`. */
export interface TemplateView {
  id: UUID;
  code: string;
  ownerModule: string;
  purpose: TemplatePurpose;
  nameVi: string;
  nameEn?: string | null;
  legalEntityId?: UUID | null;
  numberingSequenceCode?: string | null;
  status: string;
  versions: TemplateVersionView[];
}

export interface CreateTemplateRequest {
  code: string;
  legalEntityId?: UUID | null;
  numberingSequenceCode?: string | null;
}

/**
 * Core's own merge fields, available in EVERY template on top of the definition's own
 * (`DocumentTemplateRegistry.CORE_FIELDS`). The definitions endpoint returns only the owning module's fields, so this
 * list is mirrored here to document the placeholders a template author may use. Keep it in step with the backend
 * constant.
 */
export const CORE_MERGE_FIELDS: MergeField[] = [
  {
    key: 'doc.number',
    labelVi: 'Số văn bản',
    labelEn: 'Document number',
    sample: '0001/2026/HĐLĐ-HQ',
    image: false,
  },
  { key: 'doc.date', labelVi: 'Ngày văn bản', labelEn: 'Document date', sample: '01/01/2026', image: false },
  {
    key: 'le.legal_name',
    labelVi: 'Tên pháp nhân',
    labelEn: 'Legal name',
    sample: 'Công ty TNHH Mẫu',
    image: false,
  },
  { key: 'le.short_name', labelVi: 'Tên viết tắt', labelEn: 'Short name', sample: 'MAU', image: false },
  { key: 'le.tax_code', labelVi: 'Mã số thuế', labelEn: 'Tax code', sample: '0101234567', image: false },
  { key: 'le.address', labelVi: 'Địa chỉ', labelEn: 'Address', sample: '1 Tràng Tiền, Hà Nội', image: false },
  { key: 'le.phone', labelVi: 'Điện thoại', labelEn: 'Phone', sample: '024 0000 0000', image: false },
  { key: 'le.email', labelVi: 'E-mail', labelEn: 'E-mail', sample: 'hr@example.vn', image: false },
  {
    key: 'le.legal_rep_name',
    labelVi: 'Người đại diện',
    labelEn: 'Legal representative',
    sample: 'Nguyễn Văn A',
    image: false,
  },
  {
    key: 'le.legal_rep_title',
    labelVi: 'Chức vụ người đại diện',
    labelEn: "Representative's title",
    sample: 'Giám đốc',
    image: false,
  },
  { key: 'le.letterhead', labelVi: 'Tiêu đề thư', labelEn: 'Letterhead', sample: null, image: true },
  { key: 'le.seal', labelVi: 'Con dấu', labelEn: 'Seal', sample: null, image: true },
];

/** The placeholder as it is written in a template: `{{key}}`, or `{{@key}}` for a picture in a DOCX. */
export function placeholderOf(field: MergeField, source: SourceFormat = 'HTML'): string {
  return field.image && source === 'DOCX' ? `{{@${field.key}}}` : `{{${field.key}}}`;
}

/** The version in force on a date, i.e. the one generation would pick; `undefined` when nothing is published. */
export function publishedVersion(t: TemplateView, onDate: ISODate): TemplateVersionView | undefined {
  return t.versions.find(
    (v) => v.status === 'PUBLISHED' && v.validFrom <= onDate && (!v.validTo || v.validTo > onDate),
  );
}

export const documentTemplatesApi = {
  /** What the modules register - the codes a template can be created for. */
  definitions: () => http.get<TemplateDefinition[]>('/core/document-template-definitions'),
  list: () => http.get<TemplateView[]>('/core/document-templates'),
  create: (body: CreateTemplateRequest) => http.post<TemplateView>('/core/document-templates', body),
  /** `validFrom` / `outputFormat` are request parameters, not parts of the multipart body. */
  uploadVersion: (
    templateId: UUID,
    form: FormData,
    validFrom?: ISODate | null,
    outputFormat?: OutputFormat | null,
  ) =>
    http.post<TemplateView>(`/core/document-templates/${templateId}/versions`, form, {
      params: { validFrom: validFrom ?? undefined, outputFormat: outputFormat ?? undefined },
    }),
  /** Renders the version with the definition's sample values; returns the file itself (PDF or DOCX). */
  preview: (templateId: UUID, versionId: UUID) =>
    http.post<Blob>(`/core/document-templates/${templateId}/versions/${versionId}/preview`),
  publish: (templateId: UUID, versionId: UUID) =>
    http.post<TemplateView>(`/core/document-templates/${templateId}/versions/${versionId}/publish`),
};
