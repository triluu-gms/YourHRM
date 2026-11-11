import { useEffect, useState } from 'react';
import { Alert, Button, DatePicker, Form, Input, Select, Space, Tag, Typography } from 'antd';
import { EyeOutlined, PlusOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import {
  applyFieldErrors,
  blankToNull,
  CodeLabel,
  CodeValueSelect,
  CountryName,
  CountrySelect,
  fromIsoDate,
  MaskedValue,
  toIsoDate,
  type ProfileTabProps,
} from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import {
  CodeLists,
  DOCUMENT_STATUSES,
  DOCUMENT_TYPES,
  VISA_TYPED,
  type DocumentRequest,
  type DocumentStatus,
  type DocumentType,
  type IdentityDocumentView,
} from './api';
import { useAddIdentityDocument, useChangeIdentityDocument, useIdentityDocuments, useUnmaskIdentityDocument } from './queries';
import { normaliseId, PATTERNS, PurposeDrawer, ScanActions } from './shared';
import './i18n';

/** Passports, visas, residence cards and old ID documents of the person (US-PPL-007); numbers are masked. */
export function IdentityDocumentsTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation('people-person');
  const { t: tc } = useTranslation();
  const [editing, setEditing] = useState<IdentityDocumentView | 'new' | null>(null);
  const [unmasking, setUnmasking] = useState<IdentityDocumentView | null>(null);
  const [revealed, setRevealed] = useState<{ id: UUID; values: Record<string, string> } | null>(null);
  const query = useIdentityDocuments(personId);
  const canUpdate = usePermission(P.PERSON_UPDATE) && !profile.person.anonymised;
  const statusLabels = Object.fromEntries(DOCUMENT_STATUSES.map((s) => [s, t(`documents.statuses.${s}`)]));

  return (
    <div>
      {canUpdate ? (
        <div style={{ textAlign: 'right', marginBottom: 12 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>
            {t('documents.add')}
          </Button>
        </div>
      ) : null}
      {revealed ? (
        <Alert
          type="warning"
          showIcon
          closable
          onClose={() => setRevealed(null)}
          style={{ marginBottom: 12 }}
          message={t('person.unmaskedValues')}
          description={Object.entries(revealed.values).map(([k, v]) => (
            <div key={k}>
              {k}: <Typography.Text code copyable>{v}</Typography.Text>
            </div>
          ))}
        />
      ) : null}
      <DataTable<IdentityDocumentView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: t('documents.type'),
            dataIndex: 'docType',
            render: (v: DocumentType, d) => (
              <Space size={4} wrap>
                {t(`documents.types.${v}`, { defaultValue: v })}
                {d.legacyTaxCode ? <Tag>{t('documents.legacyTaxCode')}</Tag> : null}
              </Space>
            ),
          },
          { title: t('documents.docNo'), dataIndex: 'docNoMasked', render: (v: string | null) => <MaskedValue value={v} /> },
          { title: t('documents.issuingCountry'), dataIndex: 'issuingCountryCode', render: (c: string | null) => <CountryName code={c} /> },
          { title: t('documents.issueDate'), dataIndex: 'issueDate', render: (v: string | null) => formatDate(v) },
          { title: t('documents.expiryDate'), dataIndex: 'expiryDate', render: (v: string | null) => formatDate(v) },
          {
            title: t('documents.visaType'),
            dataIndex: 'visaTypeCode',
            render: (v: string | null) => (v ? <CodeLabel list={CodeLists.VISA_TYPE} code={v} /> : '—'),
          },
          { title: t('documents.status'), dataIndex: 'status', render: (s: DocumentStatus) => <StatusTag status={s} labels={statusLabels} /> },
          {
            title: t('documents.scan'),
            key: 'scan',
            render: (_, d) => (
              <ScanActions personId={personId} recordId={d.id} scanDocumentId={d.scanDocumentId} target="IDENTITY_DOC" canUpload={canUpdate && !d.legacyTaxCode} />
            ),
          },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, d) => (
              <Space size={0}>
                <PermissionGate anyOf={P.PERSON_VIEW_SENSITIVE}>
                  <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setUnmasking(d)}>
                    {t('documents.unmask')}
                  </Button>
                </PermissionGate>
                {canUpdate && !d.legacyTaxCode ? (
                  <Button type="link" size="small" onClick={() => setEditing(d)}>
                    {tc('action.edit')}
                  </Button>
                ) : null}
              </Space>
            ),
          },
        ]}
      />
      <DocumentDrawer personId={personId} editing={editing} onClose={() => setEditing(null)} />
      <UnmaskDocumentDrawer
        personId={personId}
        document={unmasking}
        onClose={() => setUnmasking(null)}
        onShown={(values) => {
          if (unmasking) setRevealed({ id: unmasking.id, values });
          setUnmasking(null);
        }}
      />
    </div>
  );
}

interface DocumentValues {
  docType: DocumentType;
  docNo?: string;
  issuingCountryCode?: string;
  issueDate?: Dayjs | null;
  issuePlace?: string;
  expiryDate?: Dayjs | null;
  visaTypeCode?: string;
  status?: DocumentStatus;
}

function DocumentDrawer({
  personId,
  editing,
  onClose,
}: {
  personId: UUID;
  editing: IdentityDocumentView | 'new' | null;
  onClose: () => void;
}) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const [form] = Form.useForm<DocumentValues>();
  const add = useAddIdentityDocument(personId);
  const change = useChangeIdentityDocument(personId);
  const existing = editing && editing !== 'new' ? editing : null;
  const mutation = existing ? change : add;
  const docType = Form.useWatch('docType', form);

  useEffect(() => {
    if (!editing) return;
    form.resetFields();
    add.reset();
    change.reset();
    if (existing) {
      form.setFieldsValue({
        docType: existing.docType,
        issuingCountryCode: existing.issuingCountryCode ?? undefined,
        issueDate: fromIsoDate(existing.issueDate),
        issuePlace: existing.issuePlace ?? undefined,
        expiryDate: fromIsoDate(existing.expiryDate),
        visaTypeCode: existing.visaTypeCode ?? undefined,
        status: existing.status,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [editing, form]);

  const submit = async () => {
    let v: DocumentValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const document = blankToNull({
      docType: v.docType,
      docNo: v.docNo,
      issuingCountryCode: v.issuingCountryCode,
      issueDate: toIsoDate(v.issueDate),
      issuePlace: v.issuePlace,
      expiryDate: toIsoDate(v.expiryDate),
      visaTypeCode: VISA_TYPED.includes(v.docType) ? v.visaTypeCode : null,
      status: v.status,
    }) as DocumentRequest;
    try {
      if (existing) await change.mutateAsync({ documentId: existing.id, version: existing.version, document });
      else await add.mutateAsync(document);
      fb.saved(t('documents.saved'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={editing !== null}
      onClose={onClose}
      title={existing ? t('documents.edit') : t('documents.add')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={520}
    >
      <Form<DocumentValues> form={form} layout="vertical">
        <Form.Item name="docType" label={t('documents.type')} rules={[{ required: true, message: t('documents.typeRequired') }]}>
          <Select options={DOCUMENT_TYPES.map((v) => ({ value: v, label: t(`documents.types.${v}`) }))} />
        </Form.Item>
        <Form.Item
          name="docNo"
          label={t('documents.docNo')}
          normalize={normaliseId}
          extra={existing ? <Space>{t('documents.docNoKeep')}<MaskedValue value={existing.docNoMasked} /></Space> : undefined}
          rules={[
            ...(existing ? [] : [{ required: true, message: t('documents.docNoRequired') }]),
            { pattern: PATTERNS.DOC_NO, message: t('documents.docNoInvalid') },
          ]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="issuingCountryCode" label={t('documents.issuingCountry')}>
          <CountrySelect />
        </Form.Item>
        <Space.Compact block>
          <Form.Item name="issueDate" label={t('documents.issueDate')} style={{ flex: 1 }}>
            <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="expiryDate" label={t('documents.expiryDate')} style={{ flex: 1 }}>
            <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
        </Space.Compact>
        <Form.Item name="issuePlace" label={t('documents.issuePlace')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        {docType && VISA_TYPED.includes(docType) ? (
          <Form.Item name="visaTypeCode" label={t('documents.visaType')}>
            <CodeValueSelect list={CodeLists.VISA_TYPE} />
          </Form.Item>
        ) : null}
        {existing ? (
          <Form.Item name="status" label={t('documents.status')}>
            <Select options={DOCUMENT_STATUSES.map((s) => ({ value: s, label: t(`documents.statuses.${s}`) }))} />
          </Form.Item>
        ) : null}
      </Form>
    </FormDrawer>
  );
}

function UnmaskDocumentDrawer({
  personId,
  document,
  onClose,
  onShown,
}: {
  personId: UUID;
  document: IdentityDocumentView | null;
  onClose: () => void;
  onShown: (values: Record<string, string>) => void;
}) {
  const { t } = useTranslation('people-person');
  const unmask = useUnmaskIdentityDocument(personId);
  return (
    <PurposeDrawer
      open={document !== null}
      title={t('documents.unmaskTitle')}
      onClose={() => {
        unmask.reset();
        onClose();
      }}
      submitting={unmask.isPending}
      error={unmask.error}
      onSubmit={(v) => {
        if (document) unmask.mutate({ documentId: document.id, purpose: v.purpose.trim() }, { onSuccess: onShown });
      }}
    />
  );
}
