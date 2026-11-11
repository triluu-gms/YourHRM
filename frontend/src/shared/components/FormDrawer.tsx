import type { ReactNode } from 'react';
import { Button, Drawer, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { ProblemAlert } from './ProblemAlert';

/** Create/edit drawer with a sticky footer; the form inside owns its state and submits via `onSubmit`. */
export function FormDrawer({
  open,
  onClose,
  title,
  onSubmit,
  submitting,
  error,
  submitLabel,
  width = 560,
  children,
  footerExtra,
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  onSubmit: () => void;
  submitting?: boolean;
  error?: unknown;
  submitLabel?: ReactNode;
  width?: number;
  children: ReactNode;
  footerExtra?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={title}
      width={width}
      destroyOnHidden
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          {footerExtra}
          <Button onClick={onClose}>{t('action.cancel')}</Button>
          <Button type="primary" onClick={onSubmit} loading={submitting}>
            {submitLabel ?? t('action.save')}
          </Button>
        </Space>
      }
    >
      {error ? <ProblemAlert error={error} style={{ marginBottom: 16 }} /> : null}
      {children}
    </Drawer>
  );
}
