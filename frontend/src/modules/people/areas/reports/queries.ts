import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { saveBlob, type ISODate, type UUID } from '@/shared/api';
import {
  dependantRegistrationsApi,
  documentLinkApi,
  integrationInboxApi,
  labourBookApi,
  openingBalanceApi,
  statutoryReportsApi,
  type DownloadLink,
  type GenerateReportRequest,
  type InboxParams,
  type MonthRange,
  type OpeningBalanceParams,
  type StatutoryReportListParams,
  type SubmitReportRequest,
} from './api';

export const reportKeys = {
  statutory: ['people', 'reports', 'statutory'] as const,
  statutoryList: (p: StatutoryReportListParams) => ['people', 'reports', 'statutory', 'list', p] as const,
  statutoryReport: (id: UUID) => ['people', 'reports', 'statutory', id] as const,
  labourChange: (le: UUID, year: number, period: string) => ['people', 'reports', 'labour-change', le, year, period] as const,
  labourFluctuation: (le: UUID, month: string) => ['people', 'reports', 'labour-fluctuation', le, month] as const,
  labourBook: (le: UUID, asOf?: ISODate) => ['people', 'reports', 'labour-book', le, asOf ?? null] as const,
  dependantRegistrations: (p: MonthRange) => ['people', 'reports', 'dependant-registrations', p] as const,
  openingBalance: (p: OpeningBalanceParams) => ['people', 'reports', 'opening-balance', p] as const,
  inbox: (p: InboxParams) => ['people', 'reports', 'inbox', p] as const,
};

/** Opens a signed download URL (short-lived, never cached). */
export function openLink(link: DownloadLink) {
  window.open(link.url, '_blank', 'noopener');
}

// ---------------------------------------------------------------------------------- statutory register

export function useStatutoryReports(params: StatutoryReportListParams | null) {
  return useQuery({
    queryKey: reportKeys.statutoryList(params ?? ({} as StatutoryReportListParams)),
    queryFn: () => statutoryReportsApi.list(params as StatutoryReportListParams),
    enabled: !!params?.legalEntityId,
  });
}

export function useStatutoryReport(id: UUID) {
  return useQuery({ queryKey: reportKeys.statutoryReport(id), queryFn: () => statutoryReportsApi.get(id) });
}

export function useLabourChange(legalEntityId: UUID | undefined, year: number, period: string | undefined) {
  return useQuery({
    queryKey: reportKeys.labourChange(legalEntityId ?? '', year, period ?? ''),
    queryFn: () => statutoryReportsApi.labourChange(legalEntityId as UUID, year, period as string),
    enabled: !!legalEntityId && !!period,
  });
}

export function useLabourFluctuation(legalEntityId: UUID | undefined, month: string | undefined) {
  return useQuery({
    queryKey: reportKeys.labourFluctuation(legalEntityId ?? '', month ?? ''),
    queryFn: () => statutoryReportsApi.labourFluctuation(legalEntityId as UUID, month as string),
    enabled: !!legalEntityId && !!month,
  });
}

export function useGenerateReport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: GenerateReportRequest) => statutoryReportsApi.generate(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: reportKeys.statutory }),
  });
}

export function useSubmitReport(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: SubmitReportRequest) => statutoryReportsApi.submit(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: reportKeys.statutory }),
    onError: () => void qc.invalidateQueries({ queryKey: reportKeys.statutoryReport(id) }),
  });
}

export function useUploadReceipt(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: FormData) => statutoryReportsApi.receipt(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: reportKeys.statutory }),
  });
}

export function useCancelReport(id: UUID) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (version: number) => statutoryReportsApi.cancel(id, version),
    onSuccess: () => void qc.invalidateQueries({ queryKey: reportKeys.statutory }),
    onError: () => void qc.invalidateQueries({ queryKey: reportKeys.statutoryReport(id) }),
  });
}

/** Signed link of the report file (latest or a given file version), then opened. */
export function useDownloadReport(id: UUID) {
  return useMutation({
    mutationFn: async (fileVersion?: number) => openLink(await statutoryReportsApi.downloadLink(id, fileVersion)),
  });
}

/** Generates the PDF from the Core template, then opens it. */
export function useReportPdf(id: UUID) {
  return useMutation({
    mutationFn: async () => {
      const doc = await statutoryReportsApi.pdf(id);
      openLink(await documentLinkApi.link(doc.documentId));
      return doc;
    },
  });
}

export function useOpenDocument() {
  return useMutation({ mutationFn: async (documentId: UUID) => openLink(await documentLinkApi.link(documentId)) });
}

// ------------------------------------------------------------------------------------------ labour book

export function useLabourBook(legalEntityId: UUID | undefined, asOf?: ISODate) {
  return useQuery({
    queryKey: reportKeys.labourBook(legalEntityId ?? '', asOf),
    queryFn: () => labourBookApi.book(legalEntityId as UUID, asOf),
    enabled: !!legalEntityId,
    placeholderData: keepPreviousData,
  });
}

export function useExportLabourBook() {
  return useMutation({
    mutationFn: async ({ legalEntityId, asOf }: { legalEntityId: UUID; asOf?: ISODate }) => {
      const { blob, fileName } = await labourBookApi.export(legalEntityId, asOf);
      saveBlob(blob, fileName ?? 'so-quan-ly-lao-dong.xlsx');
    },
  });
}

export function useLabourBookEntryPdf() {
  return useMutation({
    mutationFn: async ({ employmentId, asOf }: { employmentId: UUID; asOf?: ISODate }) => {
      const doc = await labourBookApi.entryPdf(employmentId, asOf);
      openLink(await documentLinkApi.link(doc.documentId));
      return doc;
    },
  });
}

// ------------------------------------------------------------------------------ dependant registrations

export function useDependantRegistrations(range: MonthRange | null) {
  return useQuery({
    queryKey: reportKeys.dependantRegistrations(range ?? ({} as MonthRange)),
    queryFn: () => dependantRegistrationsApi.view(range as MonthRange),
    enabled: !!range,
  });
}

export function useExportDependantRegistrations() {
  return useMutation({
    mutationFn: async (range: MonthRange) => {
      const { blob, fileName } = await dependantRegistrationsApi.export(range);
      saveBlob(blob, fileName ?? `dang-ky-npt-${range.fromMonth}_${range.toMonth}.xlsx`);
    },
  });
}

export function useDependantRegistrationsPdf() {
  return useMutation({
    mutationFn: async (range: MonthRange) => {
      const pdf = await dependantRegistrationsApi.pdf(range);
      openLink(pdf.download ?? (await documentLinkApi.link(pdf.documentId)));
      return pdf;
    },
  });
}

// ------------------------------------------------------------------------------------- opening balance

export function useOpeningBalanceReport(params: OpeningBalanceParams) {
  return useQuery({
    queryKey: reportKeys.openingBalance(params),
    queryFn: () => openingBalanceApi.report(params),
    placeholderData: keepPreviousData,
  });
}

export function useExportOpeningBalance() {
  return useMutation({
    mutationFn: async (params: OpeningBalanceParams) => {
      const { blob, fileName } = await openingBalanceApi.export(params);
      saveBlob(blob, fileName ?? 'opening-balance-r20.xlsx');
    },
  });
}

// ------------------------------------------------------------------------------------- integration inbox

export function useIntegrationInbox(params: InboxParams) {
  return useQuery({ queryKey: reportKeys.inbox(params), queryFn: () => integrationInboxApi.list(params) });
}
