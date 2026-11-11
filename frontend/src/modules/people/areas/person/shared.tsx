import { useEffect, useRef } from 'react';
import { Button, Form, Input, Space, Tooltip, type FormInstance } from 'antd';
import { EyeOutlined, PaperClipOutlined, UploadOutlined } from '@ant-design/icons';
import type { Rule } from 'antd/es/form';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import type { NationalIdType } from './api';
import { useAttachScan, useOpenScan, type ScanTarget } from './queries';
import './i18n';

/** Client-side mirrors of `PersonRules` patterns (the backend stays authoritative). */
export const PATTERNS = {
  CCCD: /^\d{12}$/,
  CMND: /^(\d{9}|\d{12})$/,
  PASSPORT: /^[A-Z0-9]{6,12}$/,
  TAX_CODE: /^(\d{10}|\d{12})$/,
  SI_NUMBER: /^\d{10}$/,
  HI_CARD: /^([A-Z]{2}\d{13}|\d{10})$/,
  DOC_NO: /^[A-Z0-9]{4,20}$/,
  EMAIL: /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/,
  PHONE: /^\+?\d{8,15}$/,
};

/** Rule for the national id number depending on the selected id type. */
export function nationalIdRule(type: NationalIdType | undefined | null, t: (k: string) => string): Rule {
  const pattern = type === 'PASSPORT' ? PATTERNS.PASSPORT : type === 'CMND' ? PATTERNS.CMND : PATTERNS.CCCD;
  const message = type === 'PASSPORT' ? t('person.passportInvalid') : type === 'CMND' ? t('person.cmndInvalid') : t('person.cccdInvalid');
  return { pattern, message };
}

/** Trims and upper-cases identifier inputs (passport / document numbers are upper-case). */
export const normaliseId = (v: string | undefined) => (v ? v.replace(/\s+/g, '').toUpperCase() : v);

/**
 * Drawer asking for the purpose of a sensitive read (US-PPL-085: purpose + audit), optionally with extra fields
 * (`children`, e.g. which identifiers to show). Calls `onSubmit` with the form values.
 */
export function PurposeDrawer<T extends { purpose: string }>({
  open,
  title,
  onClose,
  onSubmit,
  submitting,
  error,
  children,
  form: externalForm,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  onSubmit: (values: T) => void;
  submitting?: boolean;
  error?: unknown;
  children?: React.ReactNode;
  form?: FormInstance<T>;
}) {
  const { t: tp } = useTranslation('people');
  const { t } = useTranslation('people-person');
  const [own] = Form.useForm<T>();
  const form = externalForm ?? own;
  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);
  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={title}
      width={480}
      submitLabel={t('person.unmaskSubmit')}
      submitting={submitting}
      error={error}
      onSubmit={() => void form.validateFields().then(onSubmit, () => undefined)}
    >
      <p>{tp('common.unmaskConfirm')}</p>
      <Form<T> form={form} layout="vertical">
        {children}
        <Form.Item
          name="purpose"
          label={t('person.purpose')}
          rules={[{ required: true, whitespace: true, message: t('person.purposeRequired') }, { max: 200 }]}
        >
          <Input.TextArea rows={2} maxLength={200} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

/**
 * Upload a scan for a record (identity document / qualification) and open the current one. The file goes to Core's
 * document service first; the record then references it.
 */
export function ScanActions({
  personId,
  recordId,
  scanDocumentId,
  target,
  canUpload,
}: {
  personId: UUID;
  recordId: UUID;
  scanDocumentId?: UUID | null;
  target: ScanTarget;
  canUpload: boolean;
}) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const input = useRef<HTMLInputElement>(null);
  const attach = useAttachScan(personId, target);
  const open = useOpenScan();

  const onFile = async (file: File | undefined) => {
    if (!file) return;
    try {
      await attach.mutateAsync({ recordId, file });
      fb.done(t('documents.scanUploaded'));
    } catch (e) {
      fb.failed(e);
    } finally {
      if (input.current) input.current.value = '';
    }
  };

  return (
    <Space size={4}>
      {scanDocumentId ? (
        <Tooltip title={t('documents.openScan')}>
          <Button
            size="small"
            type="text"
            icon={<PaperClipOutlined />}
            aria-label={t('documents.openScan')}
            loading={open.isPending}
            onClick={() => open.mutate(scanDocumentId, { onError: fb.failed })}
          />
        </Tooltip>
      ) : null}
      {canUpload ? (
        <>
          <input
            ref={input}
            type="file"
            accept="application/pdf,image/*"
            style={{ display: 'none' }}
            data-testid={`scan-input-${recordId}`}
            onChange={(e) => void onFile(e.target.files?.[0])}
          />
          <Tooltip title={t('documents.uploadScan')}>
            <Button
              size="small"
              type="text"
              icon={<UploadOutlined />}
              aria-label={t('documents.uploadScan')}
              loading={attach.isPending}
              onClick={() => input.current?.click()}
            />
          </Tooltip>
        </>
      ) : null}
    </Space>
  );
}

export const UnmaskIcon = EyeOutlined;
