import { useState } from 'react';
import { Button, Select, Space, Table, Tag, Tooltip, Typography, Upload } from 'antd';
import { CloudUploadOutlined, DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, useFeedback } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import {
  currentVersion,
  formatBytes,
  isDownloadable,
  type Confidentiality,
  type DocumentTypeDefinition,
  type DocumentVersionView,
  type DocumentView,
  type ScanStatus,
} from './api';
import {
  useAddDocumentVersion,
  useDocumentTypes,
  useDocumentsOf,
  useDownloadLink,
  useUploadDocument,
} from './queries';
import { DOCUMENTS_NS } from './i18n';

/** Antivirus state of a file: only CLEAN and SKIPPED can be downloaded (`DocumentService.downloadLink`). */
export function ScanTag({ status }: { status: ScanStatus }) {
  const { t } = useTranslation(DOCUMENTS_NS);
  const color =
    status === 'CLEAN' || status === 'SKIPPED' ? 'green' : status === 'PENDING' ? 'processing' : 'red';
  return <Tag color={color}>{t(`scanStatus.${status}`)}</Tag>;
}

export function ConfidentialityTag({ value }: { value: Confidentiality }) {
  const { t } = useTranslation(DOCUMENTS_NS);
  const color = value === 'RESTRICTED' ? 'red' : value === 'CONFIDENTIAL' ? 'orange' : undefined;
  return <Tag color={color}>{t(`confidentiality.${value}`)}</Tag>;
}

/**
 * The shared attachment component of US-CORE-101: the files of one owner record, with upload, versions and
 * downloads, so no module builds its own. Access is decided by the document type on the owner record, so this
 * component shows whatever the API returns and lets the caller say whether the user may attach files
 * (`canManage`, usually the owner record's own update permission).
 *
 * A file is unusable until its scan is clean; while one is pending the list refreshes by itself.
 */
export function DocumentsPanel({
  ownerEntityType,
  ownerEntityId,
  documentTypeCode,
  legalEntityId,
  subjectPersonId,
  canManage = false,
}: {
  ownerEntityType: string;
  ownerEntityId: UUID | undefined;
  /** Fixes the type of new uploads; without it the user picks from the types of this owner record kind. */
  documentTypeCode?: string;
  legalEntityId?: UUID | null;
  subjectPersonId?: UUID | null;
  canManage?: boolean;
}) {
  const { t } = useTranslation(DOCUMENTS_NS);
  const fb = useFeedback();
  const documents = useDocumentsOf(ownerEntityType, ownerEntityId);
  const types = useDocumentTypes();
  const upload = useUploadDocument(ownerEntityType, ownerEntityId);
  const addVersion = useAddDocumentVersion(ownerEntityType, ownerEntityId);
  const link = useDownloadLink();
  const [newType, setNewType] = useState<string | undefined>(documentTypeCode);

  const ownTypes = (types.data ?? []).filter((x) => x.ownerEntityType === ownerEntityType);
  const typeOf = (code: string): DocumentTypeDefinition | undefined =>
    (types.data ?? []).find((x) => x.code === code);
  const uploadType = documentTypeCode ?? newType;

  const open = async (documentId: UUID, versionNo?: number) => {
    try {
      const l = await link.mutateAsync({ documentId, versionNo });
      window.open(l.url, '_blank', 'noopener');
    } catch (e) {
      fb.failed(e);
    }
  };

  const onUpload = async (file: File) => {
    if (!uploadType || !ownerEntityId) return;
    try {
      await upload.mutateAsync({
        file,
        documentTypeCode: uploadType,
        ownerEntityId,
        legalEntityId,
        subjectPersonId,
      });
      fb.done(t('panel.uploaded'));
    } catch (e) {
      fb.failed(e);
    }
  };

  const onNewVersion = async (documentId: UUID, file: File) => {
    try {
      await addVersion.mutateAsync({ documentId, file });
      fb.done(t('panel.uploaded'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      {canManage ? (
        <Space style={{ marginBottom: 12 }} wrap>
          {documentTypeCode ? null : (
            <Select<string>
              style={{ width: 280 }}
              aria-label={t('panel.field.type')}
              placeholder={t('panel.pickType')}
              value={newType}
              onChange={setNewType}
              options={ownTypes.map((x) => ({ value: x.code, label: `${x.nameVi} (${x.code})` }))}
              notFoundContent={t('panel.noTypes')}
            />
          )}
          <FilePick
            accept={acceptOf(typeOf(uploadType ?? ''))}
            disabled={!uploadType || !ownerEntityId}
            loading={upload.isPending}
            label={t('panel.upload')}
            onFile={(f) => void onUpload(f)}
          />
        </Space>
      ) : null}

      <DataTable<DocumentView>
        data={documents.data ?? []}
        isLoading={documents.isLoading}
        error={documents.error}
        locale={{ emptyText: documents.isLoading ? undefined : t('panel.empty') }}
        expandable={{
          rowExpandable: (d) => d.versions.length > 0,
          expandedRowRender: (d) => (
            <VersionTable
              document={d}
              canManage={canManage}
              onOpen={(versionNo) => void open(d.id, versionNo)}
              onNewVersion={(file) => void onNewVersion(d.id, file)}
              busy={addVersion.isPending}
            />
          ),
        }}
        columns={[
          {
            title: t('panel.field.title'),
            dataIndex: 'title',
            render: (title: string, d) => (
              <Space direction="vertical" size={0}>
                <span>{title}</span>
                <Typography.Text type="secondary" style={{ fontFamily: 'monospace' }}>
                  {typeOf(d.documentTypeCode)?.nameVi ?? d.documentTypeCode}
                </Typography.Text>
              </Space>
            ),
          },
          {
            title: t('panel.field.confidentiality'),
            dataIndex: 'confidentiality',
            render: (c: Confidentiality) => <ConfidentialityTag value={c} />,
          },
          {
            title: t('panel.field.status'),
            dataIndex: 'status',
            render: (s: string, d) => {
              const v = currentVersion(d);
              return (
                <Space size={4}>
                  <Tag color={s === 'ACTIVE' ? 'green' : s === 'QUARANTINED' ? 'red' : 'default'}>
                    {t(`documentStatus.${s}`)}
                  </Tag>
                  {v && v.scanStatus !== 'CLEAN' && v.scanStatus !== 'SKIPPED' ? (
                    <ScanTag status={v.scanStatus} />
                  ) : null}
                </Space>
              );
            },
          },
          {
            title: t('panel.field.file'),
            key: 'file',
            render: (_, d) => {
              const v = currentVersion(d);
              return v ? (
                <Space direction="vertical" size={0}>
                  <span>{v.fileName}</span>
                  <Typography.Text type="secondary">
                    {t('panel.versionNo', { no: v.versionNo })} · {formatBytes(v.sizeBytes)} ·{' '}
                    {formatDateTime(v.createdAt)}
                  </Typography.Text>
                </Space>
              ) : (
                <Typography.Text type="secondary">{t('panel.noFile')}</Typography.Text>
              );
            },
          },
          {
            key: 'actions',
            fixed: 'right',
            render: (_, d) => {
              const v = currentVersion(d);
              const ready = !!v && isDownloadable(v) && d.currentVersionNo > 0;
              return (
                <Tooltip title={ready ? undefined : t('panel.notReady')}>
                  <Button
                    size="small"
                    icon={<DownloadOutlined />}
                    disabled={!ready}
                    loading={link.isPending && link.variables?.documentId === d.id}
                    aria-label={t('panel.downloadLabel', { title: d.title })}
                    onClick={() => void open(d.id)}
                  />
                </Tooltip>
              );
            },
          },
        ]}
      />
    </div>
  );
}

function VersionTable({
  document,
  canManage,
  onOpen,
  onNewVersion,
  busy,
}: {
  document: DocumentView;
  canManage: boolean;
  onOpen: (versionNo: number) => void;
  onNewVersion: (file: File) => void;
  busy: boolean;
}) {
  const { t } = useTranslation(DOCUMENTS_NS);
  return (
    <div>
      {canManage ? (
        <FilePick
          label={t('panel.newVersion')}
          loading={busy}
          onFile={onNewVersion}
          style={{ marginBottom: 8 }}
          size="small"
        />
      ) : null}
      <Table<DocumentVersionView>
        rowKey="versionNo"
        size="small"
        pagination={false}
        dataSource={document.versions}
        columns={[
          { title: t('panel.field.versionNo'), dataIndex: 'versionNo', width: 90 },
          { title: t('panel.field.fileName'), dataIndex: 'fileName' },
          { title: t('panel.field.size'), dataIndex: 'sizeBytes', render: (b: number) => formatBytes(b) },
          {
            title: t('panel.field.source'),
            dataIndex: 'source',
            render: (s: string) => t(`source.${s}`, { defaultValue: s }),
          },
          {
            title: t('panel.field.scan'),
            dataIndex: 'scanStatus',
            render: (s: ScanStatus) => <ScanTag status={s} />,
          },
          {
            title: t('panel.field.uploadedAt'),
            dataIndex: 'createdAt',
            render: (at: string) => formatDateTime(at),
          },
          {
            key: 'actions',
            render: (_, v) => (
              <Button
                size="small"
                icon={<DownloadOutlined />}
                disabled={!isDownloadable(v)}
                aria-label={t('panel.downloadVersionLabel', { no: v.versionNo })}
                onClick={() => onOpen(v.versionNo)}
              />
            ),
          },
        ]}
      />
    </div>
  );
}

/** Opens the file picker and hands the chosen file over; nothing is uploaded by Ant itself. */
function FilePick({
  onFile,
  label,
  accept,
  disabled,
  loading,
  size,
  style,
}: {
  onFile: (file: File) => void;
  label: string;
  accept?: string;
  disabled?: boolean;
  loading?: boolean;
  size?: 'small' | 'middle';
  style?: React.CSSProperties;
}) {
  return (
    <Upload
      accept={accept}
      showUploadList={false}
      maxCount={1}
      disabled={disabled}
      beforeUpload={(file) => {
        onFile(file as File);
        return false;
      }}
    >
      <Button
        icon={accept ? <CloudUploadOutlined /> : <UploadOutlined />}
        disabled={disabled}
        loading={loading}
        size={size}
        style={style}
        aria-label={label}
      >
        {label}
      </Button>
    </Upload>
  );
}

/** The file picker's filter: the type's own media types, else everything the tenant allows. */
function acceptOf(type: DocumentTypeDefinition | undefined): string | undefined {
  return type && type.mediaTypes.length ? type.mediaTypes.join(',') : undefined;
}
