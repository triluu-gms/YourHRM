import { Button, Typography } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { formatDate } from '@/shared/format';
import type { MyDocument } from './api';
import { useMyDocuments, useOpenDocument } from './queries';
import './i18n';

/** "My documents" (US-PPL-073/077): contracts, appendices and decisions with signed download links (≤ 15 min). */
export function MyDocumentsPage() {
  const { t: te } = useTranslation('people-ess');
  const query = useMyDocuments();
  return (
    <div>
      <PageHeader title={te('documents.title')} subTitle={te('documents.intro')} />
      <DocumentsTable documents={query.data} isLoading={query.isLoading} error={query.error} />
    </div>
  );
}

/** Table of own documents with a download button per attached file; also used on the profile page. */
export function DocumentsTable({
  documents,
  isLoading,
  error,
}: {
  documents: MyDocument[] | undefined;
  isLoading?: boolean;
  error?: unknown;
}) {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const { t: tp } = useTranslation('people');
  const fb = useFeedback();
  const open = useOpenDocument();

  const download = async (documentId: UUID) => {
    try {
      await open.mutateAsync(documentId);
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <DataTable<MyDocument>
      size="small"
      rowKey={(d) => `${d.kind}:${d.recordId}`}
      data={documents}
      isLoading={isLoading}
      error={error}
      locale={{ emptyText: tp('common.noData') }}
      columns={[
        { title: te('documents.kind'), dataIndex: 'kind', render: (k: string) => te(`documents.kinds.${k}`, { defaultValue: k }) },
        { title: te('documents.number'), dataIndex: 'number' },
        { title: te('documents.type'), dataIndex: 'typeCode' },
        { title: te('documents.effectiveDate'), dataIndex: 'effectiveDate', render: (v: string | null) => formatDate(v) },
        { title: te('documents.endDate'), dataIndex: 'endDate', render: (v: string | null) => formatDate(v) },
        { title: te('documents.signDate'), dataIndex: 'signDate', render: (v: string | null) => formatDate(v) },
        { title: t('field.status'), dataIndex: 'status', render: (s: string | null) => <StatusTag status={s} /> },
        {
          title: t('field.actions'),
          key: 'download',
          render: (_, d) =>
            d.documentId ? (
              <Button
                size="small"
                icon={<DownloadOutlined />}
                loading={open.isPending && open.variables === d.documentId}
                onClick={() => void download(d.documentId as UUID)}
              >
                {tp('common.download')}
              </Button>
            ) : (
              <Typography.Text type="secondary">{te('documents.noFile')}</Typography.Text>
            ),
        },
      ]}
    />
  );
}
