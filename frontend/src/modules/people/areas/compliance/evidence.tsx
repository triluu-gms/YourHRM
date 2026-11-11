import { useState, type ReactNode } from 'react';
import { Button, Upload, type ButtonProps } from 'antd';
import { PaperClipOutlined, UploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { http, type ISOInstant, type UUID } from '@/shared/api';
import { useFeedback } from '@/shared/components';
import { fileForm } from '../../common';

/**
 * Evidence / scan helpers shared by the compliance and family areas. Files go to Core's document service
 * (`POST /core/documents`, AV-scanned there); the People record then references the returned document id.
 */

/** `core.api.Documents.DocumentView` (subset). */
export interface UploadedDocument {
  id: UUID;
  documentTypeCode: string;
  title?: string | null;
  status: string;
}

interface DownloadLink {
  url: string;
  expiresAt: ISOInstant;
  fileName?: string | null;
}

export const EVIDENCE_ACCEPT = '.pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png';

/** Uploads a file as a Core document owned by a People row (`documentTypeCode` decides the access rules). */
export function uploadDocument(args: {
  file: File;
  documentTypeCode: string;
  ownerEntityId: UUID;
  subjectPersonId?: UUID;
  title?: string;
}): Promise<UploadedDocument> {
  return http.post<UploadedDocument>(
    '/core/documents',
    fileForm(args.file, {
      documentTypeCode: args.documentTypeCode,
      ownerEntityId: args.ownerEntityId,
      subjectPersonId: args.subjectPersonId,
      title: args.title ?? args.file.name,
    }),
  );
}

/** Button opening the OS file picker; hands the chosen file to `onFile` (nothing is uploaded by Ant itself). */
export function FilePickButton({
  onFile,
  loading,
  children,
  accept = EVIDENCE_ACCEPT,
  ...rest
}: { onFile: (file: File) => void; loading?: boolean; children: ReactNode; accept?: string } & Omit<ButtonProps, 'onClick'>) {
  return (
    <Upload
      accept={accept}
      showUploadList={false}
      maxCount={1}
      beforeUpload={(file) => {
        onFile(file as File);
        return false;
      }}
    >
      <Button icon={<UploadOutlined />} loading={loading} {...rest}>
        {children}
      </Button>
    </Upload>
  );
}

/** Opens a short-lived signed URL (≤15 min, BR-CORE-101) of a document; confidential downloads are audited by Core. */
export function DocumentLink({ documentId, label }: { documentId: UUID; label?: ReactNode }) {
  const { t } = useTranslation('people');
  const fb = useFeedback();
  const [loading, setLoading] = useState(false);
  const open = async () => {
    setLoading(true);
    try {
      const link = await http.post<DownloadLink>(`/core/documents/${documentId}/download-link`);
      window.open(link.url, '_blank', 'noopener');
    } catch (e) {
      fb.failed(e);
    } finally {
      setLoading(false);
    }
  };
  return (
    <Button type="link" size="small" icon={<PaperClipOutlined />} loading={loading} onClick={() => void open()}>
      {label ?? t('common.download')}
    </Button>
  );
}
