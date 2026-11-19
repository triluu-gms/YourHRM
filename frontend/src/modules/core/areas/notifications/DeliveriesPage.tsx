import { Select, Space, Tooltip, Typography } from 'antd';
import { PaperClipOutlined, RedoOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ConfirmButton, DataTable, PageHeader, useFeedback, usePaging } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import { CHANNELS, DELIVERY_STATUSES, type Channel, type DeliveryItem, type DeliveryStatus } from './api';
import { useDeliveries, useResendDelivery } from './queries';
import { DeliveryStatusTag, channelLabel } from './widgets';
import { NOTIFICATIONS_NS } from './i18n';

/** Statuses `NotificationService.resend` accepts: failed, bounced, or never left. */
const RESENDABLE: ReadonlySet<string> = new Set(['FAILED', 'BOUNCED', 'QUEUED', 'SUPPRESSED']);

/** Why a delivery cannot be resent, or null when it can (same checks as `NotificationService.resend`). */
export function resendBlocker(d: Pick<DeliveryItem, 'channel' | 'status' | 'hasAttachment'>): 'channel' | 'status' | 'attachment' | null {
  if (d.channel !== 'EMAIL') return 'channel';
  if (!RESENDABLE.has(d.status)) return 'status';
  if (d.hasAttachment) return 'attachment';
  return null;
}

/** Delivery monitor (US-CORE-073): every in-app / e-mail delivery with its status, and resend of failed e-mails. */
export function DeliveriesPage() {
  const { t } = useTranslation(NOTIFICATIONS_NS);
  const fb = useFeedback();
  const paging = usePaging(50);
  const [status, setStatus] = useState<DeliveryStatus | undefined>();
  const [channel, setChannel] = useState<Channel | undefined>();
  const query = useDeliveries({ status, channel, page: paging.page, size: paging.size });
  const resend = useResendDelivery();

  const doResend = async (d: DeliveryItem) => {
    try {
      await resend.mutateAsync(d.deliveryId);
      fb.done(t('deliveries.resent'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader title={t('deliveries.title')} subTitle={t('deliveries.subtitle')} />

      <Space style={{ marginBottom: 12 }} wrap>
        <Select<DeliveryStatus>
          allowClear
          aria-label={t('deliveries.field.status')}
          placeholder={t('deliveries.allStatuses')}
          style={{ width: 180 }}
          value={status}
          onChange={(v) => {
            setStatus(v);
            paging.reset();
          }}
          options={DELIVERY_STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))}
        />
        <Select<Channel>
          allowClear
          aria-label={t('deliveries.field.channel')}
          placeholder={t('deliveries.allChannels')}
          style={{ width: 180 }}
          value={channel}
          onChange={(v) => {
            setChannel(v);
            paging.reset();
          }}
          options={CHANNELS.map((c) => ({ value: c, label: channelLabel(t, c) }))}
        />
      </Space>

      <DataTable<DeliveryItem>
        rowKey="deliveryId"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        paging={{ ...paging, hasMore: (query.data?.length ?? 0) === paging.size }}
        columns={[
          {
            title: t('deliveries.field.createdAt'),
            dataIndex: 'createdAt',
            render: (v: string) => formatDateTime(v),
          },
          {
            title: t('deliveries.field.eventCode'),
            dataIndex: 'eventCode',
            render: (code: string) => <Typography.Text style={{ fontFamily: 'monospace' }}>{code}</Typography.Text>,
          },
          {
            title: t('deliveries.field.channel'),
            dataIndex: 'channel',
            render: (c: string) => channelLabel(t, c),
          },
          {
            title: t('deliveries.field.destination'),
            key: 'destination',
            render: (_, d) => (
              <Tooltip title={t('deliveries.recipientHint', { id: d.recipientUserId })}>
                <Space size={4}>
                  <span>{d.destinationMasked || '—'}</span>
                  {d.hasAttachment ? (
                    <PaperClipOutlined aria-label={t('deliveries.withAttachment')} title={t('deliveries.withAttachment')} />
                  ) : null}
                </Space>
              </Tooltip>
            ),
          },
          {
            title: t('deliveries.field.status'),
            dataIndex: 'status',
            render: (s: string) => <DeliveryStatusTag status={s} />,
          },
          { title: t('deliveries.field.attempts'), dataIndex: 'attempts', align: 'right' },
          {
            title: t('deliveries.field.lastError'),
            dataIndex: 'lastError',
            render: (e?: string | null) =>
              e ? (
                <Typography.Text type="danger" ellipsis={{ tooltip: e }} style={{ maxWidth: 240 }}>
                  {e}
                </Typography.Text>
              ) : null,
          },
          {
            title: t('deliveries.field.sentAt'),
            key: 'when',
            render: (_, d) => formatDateTime(d.failedAt ?? d.deliveredAt ?? d.sentAt),
          },
          {
            key: 'actions',
            fixed: 'right',
            render: (_, d) => {
              const blocker = resendBlocker(d);
              if (blocker === 'channel') return null;
              const button = (
                <ConfirmButton
                  size="small"
                  icon={<RedoOutlined />}
                  title={t('deliveries.resendConfirm')}
                  disabled={blocker !== null}
                  loading={resend.isPending && resend.variables === d.deliveryId}
                  aria-label={t('deliveries.resendLabel', { destination: d.destinationMasked ?? d.deliveryId })}
                  onConfirm={() => doResend(d)}
                >
                  {t('deliveries.resend')}
                </ConfirmButton>
              );
              return blocker ? <Tooltip title={t(`deliveries.notResendable.${blocker}`)}>{button}</Tooltip> : button;
            },
          },
        ]}
      />
    </div>
  );
}
