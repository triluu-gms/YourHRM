import { useState } from 'react';
import { Alert, Button, List, Space, Tag, Typography } from 'antd';
import { EditOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, useFeedback } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { CodeLabel, fileForm, MaskedValue, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { DocumentLink, FilePickButton } from '../compliance/evidence';
import type { FamilyMemberIdentifiers, FamilyMemberView } from './api';
import { FamilyMemberDrawer, RELATIONSHIP_LIST } from './FamilyMemberDrawer';
import { useFamilyMembers, useMemberDocuments, useRevealIdentifiers, useUploadMemberEvidence } from './queries';
import './i18n';

/** Family members of the person (BR-PPL-016, US-PPL-027): masked identifiers, evidence documents, create/edit. */
export function FamilyMembersTab({ personId }: ProfileTabProps) {
  const { t } = useTranslation();
  const { t: tf } = useTranslation('people-family');
  const fb = useFeedback();
  const query = useFamilyMembers(personId);
  const reveal = useRevealIdentifiers(personId);
  const upload = useUploadMemberEvidence(personId);
  const [editing, setEditing] = useState<FamilyMemberView | null | undefined>(undefined);
  const [revealed, setRevealed] = useState<Record<UUID, FamilyMemberIdentifiers>>({});

  const onReveal = async (id: UUID) => {
    try {
      const ids = await reveal.mutateAsync(id);
      setRevealed((r) => ({ ...r, [id]: ids }));
    } catch (e) {
      fb.failed(e);
    }
  };

  const onUpload = async (memberId: UUID, file: File) => {
    try {
      await upload.mutateAsync({ memberId, form: fileForm(file, { title: file.name }) });
      fb.done(tf('members.uploaded'));
    } catch (e) {
      fb.failed(e);
    }
  };

  const anyMasked = (query.data ?? []).some((m) => m.masked);

  return (
    <div>
      <Space style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {tf('members.title')}
        </Typography.Title>
        <PermissionGate anyOf={P.DEPENDANT_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing(null)}>
            {tf('members.add')}
          </Button>
        </PermissionGate>
      </Space>
      {anyMasked ? <Alert type="info" showIcon message={tf('members.masked')} style={{ marginBottom: 12 }} /> : null}

      <DataTable<FamilyMemberView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        expandable={{ expandedRowRender: (m) => <MemberDocuments memberId={m.id} /> }}
        columns={[
          { title: tf('members.relationship'), dataIndex: 'relationshipCode', render: (c: string) => <CodeLabel list={RELATIONSHIP_LIST} code={c} /> },
          {
            title: tf('members.fullName'),
            dataIndex: 'fullName',
            render: (name: string, m) => (
              <Space size={4} wrap>
                {name}
                {m.deceased ? <Tag>{tf('members.deceased')}</Tag> : null}
                {m.disabled ? <Tag color="purple">{tf('members.disabled')}</Tag> : null}
              </Space>
            ),
          },
          {
            title: tf('members.birthDate'),
            key: 'birth',
            render: (_, m) => (m.birthDate ? formatDate(m.birthDate) : (m.birthYear ?? '—')),
          },
          {
            title: tf('members.nationalId'),
            key: 'nationalId',
            render: (_, m) =>
              revealed[m.id] ? <Typography.Text code>{revealed[m.id]?.nationalId ?? '—'}</Typography.Text> : <MaskedValue value={m.nationalIdMasked} />,
          },
          {
            title: tf('members.taxCode'),
            key: 'taxCode',
            render: (_, m) =>
              revealed[m.id] ? <Typography.Text code>{revealed[m.id]?.taxCode ?? '—'}</Typography.Text> : <MaskedValue value={m.taxCodeMasked} />,
          },
          {
            title: tf('members.income'),
            dataIndex: 'hasIncomeAboveThreshold',
            render: (v: boolean | null) => (v === true ? t('common.yes') : v === false ? t('common.no') : '—'),
          },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, m) => (
              <Space size={0} wrap>
                <PermissionGate anyOf={P.DEPENDANT_UPDATE}>
                  <Button type="link" size="small" icon={<EditOutlined />} onClick={() => setEditing(m)}>
                    {t('action.edit')}
                  </Button>
                  <FilePickButton size="small" type="link" loading={upload.isPending} onFile={(f) => void onUpload(m.id, f)}>
                    {tf('members.uploadEvidence')}
                  </FilePickButton>
                </PermissionGate>
                {!revealed[m.id] && (m.nationalIdMasked || m.taxCodeMasked) ? (
                  <PermissionGate anyOf={P.PERSON_VIEW_SENSITIVE}>
                    <ConfirmButton
                      type="link"
                      size="small"
                      icon={<EyeOutlined />}
                      title={tf('members.revealConfirm')}
                      onConfirm={() => onReveal(m.id)}
                    >
                      {tf('members.reveal')}
                    </ConfirmButton>
                  </PermissionGate>
                ) : null}
              </Space>
            ),
          },
        ]}
      />

      <FamilyMemberDrawer personId={personId} open={editing !== undefined} member={editing} onClose={() => setEditing(undefined)} />
    </div>
  );
}

function MemberDocuments({ memberId }: { memberId: UUID }) {
  const { t: tf } = useTranslation('people-family');
  const docs = useMemberDocuments(memberId);
  return (
    <List
      size="small"
      loading={docs.isLoading}
      header={<Typography.Text strong>{tf('members.documents', { count: docs.data?.length ?? 0 })}</Typography.Text>}
      dataSource={docs.data ?? []}
      locale={{ emptyText: tf('dependants.noEvidence') }}
      renderItem={(d) => (
        <List.Item actions={[<DocumentLink key="dl" documentId={d.id} />]}>
          {d.title ?? d.documentTypeCode} <Tag>{d.status}</Tag>
        </List.Item>
      )}
    />
  );
}
