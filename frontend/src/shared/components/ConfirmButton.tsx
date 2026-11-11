import type { ReactNode } from 'react';
import { Button, Popconfirm, type ButtonProps } from 'antd';
import { useTranslation } from 'react-i18next';

/** Button guarded by a confirmation popover (close, deactivate, delete, rollback…). */
export function ConfirmButton({
  title,
  description,
  onConfirm,
  children,
  ...rest
}: { title?: ReactNode; description?: ReactNode; onConfirm: () => void | Promise<unknown> } & Omit<
  ButtonProps,
  'onClick' | 'title'
>) {
  const { t } = useTranslation();
  return (
    <Popconfirm
      title={title ?? t('action.confirm')}
      description={description}
      okText={t('action.confirm')}
      cancelText={t('action.cancel')}
      onConfirm={() => void onConfirm()}
    >
      <Button {...rest}>{children}</Button>
    </Popconfirm>
  );
}
