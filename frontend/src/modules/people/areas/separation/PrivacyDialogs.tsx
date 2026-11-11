import { useState } from 'react';
import { Alert, Button, Form, Input, Modal, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { saveBlob, type UUID } from '@/shared/api';
import { ProblemAlert, useFeedback } from '@/shared/components';
import { formatDate, formatDateTime } from '@/shared/format';
import type { AnonymisationConfirmation, ExportPackage } from './api';
import { NS } from './parts';
import { useAnonymise, usePrivacyExport } from './queries';

/** Mounted only while open, so every opening starts from a clean state. */
export function PrivacyExportModal(props: { open: boolean; onClose: () => void; personId: UUID }) {
  return props.open ? <PrivacyExportModalBody {...props} /> : null;
}

/**
 * DSR access export (US-PPL-088): builds the package server-side and offers it as a JSON download. The package holds
 * decrypted personal data, so it is only kept in memory for the download and never cached.
 */
function PrivacyExportModalBody({ open, onClose, personId }: { open: boolean; onClose: () => void; personId: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const run = usePrivacyExport(personId);
  const [ref, setRef] = useState('');
  const [pkg, setPkg] = useState<ExportPackage | null>(null);


  const rows = pkg ? Object.values(pkg.data).reduce((n, list) => n + list.length, 0) : 0;

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={t('privacy.exportTitle')}
      destroyOnHidden
      footer={
        <Space>
          <Button onClick={onClose}>{tc('action.close')}</Button>
          {pkg ? (
            <Button
              type="primary"
              onClick={() =>
                saveBlob(new Blob([JSON.stringify(pkg, null, 2)], { type: 'application/json' }), `dsr-export-${personId}.json`)
              }
            >
              {t('privacy.exportDownload')}
            </Button>
          ) : (
            <Button type="primary" loading={run.isPending} onClick={() => void run.mutateAsync(ref).then(setPkg, () => undefined)}>
              {t('privacy.exportRun')}
            </Button>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">{t('privacy.exportHint')}</Typography.Paragraph>
      <Form layout="vertical">
        <Form.Item label={t('field.requestRef')}>
          <Input value={ref} maxLength={100} disabled={!!pkg} onChange={(e) => setRef(e.target.value)} />
        </Form.Item>
      </Form>
      {run.error ? <ProblemAlert error={run.error} /> : null}
      {pkg ? (
        <Alert
          type="success"
          showIcon
          message={t('privacy.exportDone', { at: formatDateTime(pkg.generatedAt) })}
          description={
            <Space direction="vertical" size={2}>
              <span>{t('privacy.exportSections', { count: Object.keys(pkg.data).length, rows })}</span>
              {pkg.pdfDocumentId ? <span>{t('privacy.pdfReady')}</span> : null}
              {pkg.warnings.map((w) => (
                <Typography.Text key={w} type="warning">
                  {w}
                </Typography.Text>
              ))}
            </Space>
          }
        />
      ) : null}
    </Modal>
  );
}

/** Mounted only while open, so every opening starts from a clean state. */
export function AnonymiseModal(props: { open: boolean; onClose: () => void; personId: UUID }) {
  return props.open ? <AnonymiseModalBody {...props} /> : null;
}

/**
 * DSR erasure (US-PPL-088): irreversible, so the user must type a confirmation word; the request carries
 * `confirm: true` only then.
 */
function AnonymiseModalBody({ open, onClose, personId }: { open: boolean; onClose: () => void; personId: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const run = useAnonymise(personId);
  const [ref, setRef] = useState('');
  const [typed, setTyped] = useState('');
  const [done, setDone] = useState<AnonymisationConfirmation | null>(null);
  const word = t('privacy.anonymiseWord');


  const onRun = async () => {
    try {
      const res = await run.mutateAsync(ref);
      setDone(res);
      fb.done(t('privacy.anonymised', { mode: t(`privacy.mode.${res.mode}`, { defaultValue: res.mode }) }));
    } catch {
      /* shown in the modal */
    }
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={t('privacy.anonymiseTitle')}
      destroyOnHidden
      footer={
        <Space>
          <Button onClick={onClose}>{tc('action.close')}</Button>
          {!done ? (
            <Button danger type="primary" disabled={typed.trim() !== word} loading={run.isPending} onClick={() => void onRun()}>
              {t('privacy.anonymiseRun')}
            </Button>
          ) : null}
        </Space>
      }
    >
      <Alert type="error" showIcon message={t('privacy.anonymiseWarning')} style={{ marginBottom: 16 }} />
      {!done ? (
        <Form layout="vertical">
          <Form.Item label={t('field.requestRef')}>
            <Input value={ref} maxLength={100} onChange={(e) => setRef(e.target.value)} />
          </Form.Item>
          <Form.Item label={t('privacy.anonymiseConfirmLabel', { word })}>
            <Input aria-label={t('privacy.anonymiseConfirmLabel', { word })} value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
          </Form.Item>
        </Form>
      ) : (
        <Alert
          type="success"
          showIcon
          message={t('privacy.anonymised', { mode: t(`privacy.mode.${done.mode}`, { defaultValue: done.mode }) })}
          description={
            <Space direction="vertical" size={2}>
              {done.retainedUntil ? <span>{t('privacy.retainedUntil', { date: formatDate(done.retainedUntil) })}</span> : null}
              {done.cleared.length > 0 ? (
                <span>
                  {t('privacy.cleared')}: {done.cleared.join(', ')}
                </span>
              ) : null}
              {done.retained.length > 0 ? (
                <span>
                  {t('privacy.retained')}: {done.retained.join(', ')}
                </span>
              ) : null}
            </Space>
          }
        />
      )}
      {run.error ? <ProblemAlert error={run.error} /> : null}
    </Modal>
  );
}
