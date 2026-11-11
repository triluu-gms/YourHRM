import { Space } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { useFeedback } from '@/shared/components';
import { DocumentLink, FilePickButton } from './evidence';
import { useAttachEvidence, type EvidenceKind } from './queries';
import './i18n';

/** Download link of a row's evidence / scan plus an attach (or replace) button for users holding `permission`. */
export function EvidenceCell({
  personId,
  kind,
  rowId,
  documentId,
  permission,
}: {
  personId: UUID;
  kind: EvidenceKind;
  rowId: UUID;
  documentId?: UUID | null;
  permission: string;
}) {
  const { t } = useTranslation('people-compliance');
  const fb = useFeedback();
  const attach = useAttachEvidence(personId, kind);
  const onFile = async (file: File) => {
    try {
      await attach.mutateAsync({ id: rowId, file });
      fb.done(t('common.attached'));
    } catch (e) {
      fb.failed(e);
    }
  };
  return (
    <Space size={0} wrap>
      {documentId ? <DocumentLink documentId={documentId} /> : null}
      <PermissionGate anyOf={permission}>
        <FilePickButton size="small" type="link" loading={attach.isPending} onFile={(f) => void onFile(f)}>
          {documentId ? t('common.replace') : t('common.attach')}
        </FilePickButton>
      </PermissionGate>
    </Space>
  );
}
