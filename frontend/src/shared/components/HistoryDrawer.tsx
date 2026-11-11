import type { ReactNode } from 'react';
import { Drawer, Timeline, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { EffectiveDated } from '@/shared/api';
import { formatValidity } from '@/shared/format';
import { ProblemAlert } from './ProblemAlert';

/** Version history of a Pattern-A record: one timeline item per version, newest first. */
export function HistoryDrawer<T extends EffectiveDated>({
  open,
  onClose,
  title,
  versions,
  isLoading,
  error,
  renderVersion,
}: {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  versions: T[] | undefined;
  isLoading?: boolean;
  error?: unknown;
  renderVersion: (version: T) => ReactNode;
}) {
  const { t } = useTranslation();
  const sorted = [...(versions ?? [])].sort((a, b) => (a.validFrom < b.validFrom ? 1 : -1));
  return (
    <Drawer open={open} onClose={onClose} title={title ?? t('action.history')} width={520} loading={isLoading}>
      {error ? <ProblemAlert error={error} /> : null}
      {sorted.length === 0 && !isLoading ? <Typography.Text type="secondary">{t('common.empty')}</Typography.Text> : null}
      <Timeline
        items={sorted.map((v) => ({
          children: (
            <div>
              <Typography.Text strong>{formatValidity(v.validFrom, v.validTo, t('common.openEnded'))}</Typography.Text>
              <div>{renderVersion(v)}</div>
            </div>
          ),
        }))}
      />
    </Drawer>
  );
}
