import { useState } from 'react';
import { Button, Card, DatePicker, Form, Input, Result, Select, Skeleton, Typography, Upload } from 'antd';
import { CloudDownloadOutlined, FilePdfOutlined, SendOutlined, UploadOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import {
  ConfirmButton,
  DataTable,
  DescriptionList,
  FormDrawer,
  PageHeader,
  ProblemAlert,
  useFeedback,
} from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime, formatNumber } from '@/shared/format';
import { applyFieldErrors, fileForm, LegalEntityName, toIsoDate } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import {
  SUBMISSION_CHANNELS,
  type DocumentVersionView,
  type StatutoryReportView,
  type SubmissionChannel,
} from './api';
import {
  useCancelReport,
  useDownloadReport,
  useOpenDocument,
  useReportPdf,
  useStatutoryReport,
  useSubmitReport,
  useUploadReceipt,
} from './queries';
import { periodLabel, ReportStatusTag } from './shared';
import './i18n';

/**
 * One statutory report (US-PPL-071): register facts, file versions (signed downloads), mark submitted
 * (GENERATED → SUBMITTED), upload the authority's receipt (→ ACKNOWLEDGED), cancel, and the 6-monthly PDF.
 */
export function StatutoryReportDetailPage({ reportId }: { reportId: UUID }) {
  const { t } = useTranslation();
  const { t: tr } = useTranslation('people-reports');
  const fb = useFeedback();
  const query = useStatutoryReport(reportId);
  const download = useDownloadReport(reportId);
  const pdf = useReportPdf(reportId);
  const cancel = useCancelReport(reportId);
  const openDoc = useOpenDocument();
  const [submitting, setSubmitting] = useState(false);
  const [receipt, setReceipt] = useState(false);

  const r = query.data;
  if (query.isLoading) return <Skeleton active />;
  if (query.error || !r) {
    return (
      <Result
        status="404"
        title={tr('detail.notFound')}
        extra={query.error ? <ProblemAlert error={query.error} /> : null}
      />
    );
  }

  const run = async (fn: () => Promise<unknown>, done?: string) => {
    try {
      await fn();
      if (done) fb.done(done);
    } catch (e) {
      fb.failed(e);
    }
  };

  const live = r.status !== 'CANCELLED';
  const hasFile = !!r.generatedDocumentId;
  const receiptId = r.receiptDocumentId ?? r.receiptDocumentIds?.[0] ?? null;

  return (
    <div>
      <PageHeader
        title={tr('detail.title', { type: tr(`statutory.types.${r.reportType}`), period: periodLabel(r) })}
        subTitle={<LegalEntityName id={r.legalEntityId} />}
        breadcrumb={[
          { label: tr('statutory.title'), to: '/people/statutory-reports' },
          { label: periodLabel(r) },
        ]}
        extra={
          <>
            {hasFile ? (
              <Button
                icon={<CloudDownloadOutlined />}
                loading={download.isPending}
                onClick={() => void run(() => download.mutateAsync(undefined))}
              >
                {tr('detail.download')}
              </Button>
            ) : null}
            <PermissionGate anyOf={P.STATUTORY_REPORT_EXECUTE}>
              {live && r.reportType === 'LABOUR_CHANGE_6M' ? (
                <Button
                  icon={<FilePdfOutlined />}
                  loading={pdf.isPending}
                  onClick={() => void run(() => pdf.mutateAsync(), tr('common.pdfReady'))}
                >
                  {tr('detail.pdf')}
                </Button>
              ) : null}
              {r.status === 'GENERATED' ? (
                <Button type="primary" icon={<SendOutlined />} onClick={() => setSubmitting(true)}>
                  {tr('detail.submit')}
                </Button>
              ) : null}
              {r.status === 'SUBMITTED' || r.status === 'ACKNOWLEDGED' ? (
                <Button icon={<UploadOutlined />} onClick={() => setReceipt(true)}>
                  {tr('detail.receipt')}
                </Button>
              ) : null}
              {live ? (
                <ConfirmButton
                  danger
                  title={tr('detail.confirmCancel')}
                  loading={cancel.isPending}
                  onConfirm={() => run(() => cancel.mutateAsync(r.version), tr('detail.cancelled'))}
                >
                  {tr('detail.cancel')}
                </ConfirmButton>
              ) : null}
            </PermissionGate>
          </>
        }
      />

      <Card title={tr('detail.details')} style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: tr('statutory.type'), value: tr(`statutory.types.${r.reportType}`) },
            { label: tr('common.status'), value: <ReportStatusTag status={r.status} /> },
            { label: tr('statutory.periodRange'), value: periodLabel(r) },
            { label: tr('statutory.dueDate'), value: formatDate(r.dueDate) },
            { label: tr('statutory.rows'), value: r.rowCount },
            { label: tr('detail.templateVersion'), value: r.templateVersion, hideEmpty: true },
            { label: tr('statutory.generatedAt'), value: formatDateTime(r.generatedAt) },
            { label: tr('statutory.submittedAt'), value: formatDateTime(r.submittedAt) },
            {
              label: tr('statutory.channel'),
              value: r.submissionChannel ? tr(`statutory.channels.${r.submissionChannel}`) : null,
            },
            { label: tr('statutory.submissionRef'), value: r.submissionRef },
            {
              label: tr('detail.openReceipt'),
              hideEmpty: true,
              value: receiptId ? (
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => void run(() => openDoc.mutateAsync(receiptId))}
                >
                  {tr('detail.openReceipt')}
                </Button>
              ) : null,
            },
            { label: t('field.version'), value: r.version },
          ]}
        />
        {r.hrNotes && Object.keys(r.hrNotes).length > 0 ? (
          <DescriptionList
            style={{ marginTop: 12 }}
            title={tr('detail.notes')}
            column={1}
            items={Object.entries(r.hrNotes).map(([k, v]) => ({ label: k, value: v }))}
          />
        ) : null}
      </Card>

      <Card title={tr('detail.versions')}>
        {hasFile ? (
          <DataTable<DocumentVersionView>
            size="small"
            rowKey="versionNo"
            data={[...(r.versions ?? [])].sort((a, b) => b.versionNo - a.versionNo)}
            columns={[
              { title: tr('detail.versionNo'), dataIndex: 'versionNo' },
              { title: tr('detail.fileName'), dataIndex: 'fileName' },
              {
                title: tr('detail.size'),
                dataIndex: 'sizeBytes',
                align: 'right',
                render: (n: number) => `${formatNumber(Math.ceil(n / 1024))} KB`,
              },
              {
                title: tr('detail.createdAt'),
                dataIndex: 'createdAt',
                render: (v: string) => formatDateTime(v),
              },
              {
                title: t('field.actions'),
                key: 'actions',
                render: (_, v) => (
                  <Button
                    type="link"
                    size="small"
                    onClick={() => void run(() => download.mutateAsync(v.versionNo))}
                  >
                    {tr('detail.downloadVersion')}
                  </Button>
                ),
              },
            ]}
          />
        ) : (
          <Typography.Text type="secondary">{tr('detail.noFile')}</Typography.Text>
        )}
      </Card>

      {submitting ? <SubmitDrawer report={r} open onClose={() => setSubmitting(false)} /> : null}
      {/* Mounted only while open so the chosen file and errors start fresh each time. */}
      {receipt ? <ReceiptDrawer report={r} open onClose={() => setReceipt(false)} /> : null}
    </div>
  );
}

interface SubmitValues {
  submittedOn: Dayjs;
  channel?: SubmissionChannel;
  submissionRef?: string;
}

function SubmitDrawer({
  report,
  open,
  onClose,
}: {
  report: StatutoryReportView;
  open: boolean;
  onClose: () => void;
}) {
  const { t: tr } = useTranslation('people-reports');
  const fb = useFeedback();
  const [form] = Form.useForm<SubmitValues>();
  const submit = useSubmitReport(report.id);

  const onSubmit = async () => {
    let v: SubmitValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await submit.mutateAsync({
        version: report.version,
        submittedOn: toIsoDate(v.submittedOn)!,
        channel: v.channel ?? null,
        submissionRef: v.submissionRef?.trim() || null,
      });
      fb.done(tr('detail.submitted'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={tr('detail.submit')}
      onSubmit={() => void onSubmit()}
      submitting={submit.isPending}
      error={submit.error}
      width={440}
    >
      <Form<SubmitValues>
        form={form}
        layout="vertical"
        initialValues={{ submittedOn: dayjs(), channel: 'PORTAL' }}
      >
        <Form.Item
          name="submittedOn"
          label={tr('detail.submittedOn')}
          rules={[{ required: true, message: tr('detail.submittedOnRequired') }]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="channel" label={tr('statutory.channel')}>
          <Select
            allowClear
            options={SUBMISSION_CHANNELS.map((c) => ({ value: c, label: tr(`statutory.channels.${c}`) }))}
          />
        </Form.Item>
        <Form.Item name="submissionRef" label={tr('statutory.submissionRef')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

function ReceiptDrawer({
  report,
  open,
  onClose,
}: {
  report: StatutoryReportView;
  open: boolean;
  onClose: () => void;
}) {
  const { t: tr } = useTranslation('people-reports');
  const fb = useFeedback();
  const [form] = Form.useForm<{ title?: string }>();
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState(false);
  const upload = useUploadReceipt(report.id);

  const onSubmit = async () => {
    if (!file) {
      setFileError(true);
      return;
    }
    const { title } = form.getFieldsValue();
    try {
      await upload.mutateAsync(fileForm(file, { title: title?.trim() }));
      fb.done(tr('detail.receiptUploaded'));
      onClose();
    } catch {
      /* problem shown in the drawer */
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={tr('detail.receipt')}
      onSubmit={() => void onSubmit()}
      submitting={upload.isPending}
      error={upload.error}
      width={440}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="title" label={tr('detail.receiptTitle')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item
          label={tr('detail.receiptFile')}
          required
          validateStatus={fileError ? 'error' : undefined}
          help={fileError ? tr('detail.receiptFileRequired') : undefined}
        >
          <Upload
            maxCount={1}
            beforeUpload={(f) => {
              setFile(f);
              setFileError(false);
              return false;
            }}
            onRemove={() => setFile(null)}
            fileList={file ? [{ uid: '1', name: file.name, status: 'done' }] : []}
          >
            <Button icon={<UploadOutlined />}>{tr('detail.receiptFile')}</Button>
          </Upload>
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
