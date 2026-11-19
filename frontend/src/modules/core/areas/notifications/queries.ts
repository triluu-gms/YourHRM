import { useEffect } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import { deliveriesApi, notificationsApi, templatesApi, type DeliveryFilter, type PreviewRequest } from './api';

export const notificationKeys = {
  /** The caller's inbox; the bell, the inbox page and the live stream all refresh this prefix. */
  all: ['core', 'notifications'] as const,
  unreadCount: ['core', 'notifications', 'unread-count'] as const,
  inbox: (unreadOnly: boolean, page: number, size: number) =>
    ['core', 'notifications', 'inbox', { unreadOnly, page, size }] as const,
  deliveries: ['core', 'notification-deliveries'] as const,
  deliveryList: (filter: DeliveryFilter) => ['core', 'notification-deliveries', filter] as const,
  templates: ['core', 'notification-templates'] as const,
};

// ------------------------------------------------------------------------------------------------ inbox

export function useUnreadCount() {
  return useQuery({ queryKey: notificationKeys.unreadCount, queryFn: notificationsApi.unreadCount, refetchInterval: 60_000 });
}

export function useInbox(unreadOnly: boolean, page: number, size: number) {
  return useQuery({
    queryKey: notificationKeys.inbox(unreadOnly, page, size),
    queryFn: () => notificationsApi.inbox(unreadOnly, page, size),
    placeholderData: keepPreviousData,
  });
}

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: UUID) => notificationsApi.markRead(id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: notificationKeys.all }),
  });
}

export function useMarkAllRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: notificationsApi.markAllRead,
    onSuccess: () => void qc.invalidateQueries({ queryKey: notificationKeys.all }),
  });
}

/** Subscribes to the SSE stream (US-CORE-072) and refreshes the inbox queries on every event. */
export function useNotificationStream() {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (typeof EventSource === 'undefined') return;
    const source = new EventSource('/api/v1/core/notifications/stream', { withCredentials: true });
    const refresh = () => void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    source.onmessage = refresh;
    source.addEventListener('notification', refresh);
    source.onerror = () => {
      /* the browser reconnects on its own; after a 401 the next API call redirects to login */
    };
    return () => source.close();
  }, [queryClient]);
}

// ------------------------------------------------------------------------------------------------ delivery monitor

export function useDeliveries(filter: DeliveryFilter) {
  return useQuery({
    queryKey: notificationKeys.deliveryList(filter),
    queryFn: () => deliveriesApi.list(filter),
    placeholderData: keepPreviousData,
  });
}

export function useResendDelivery() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (deliveryId: UUID) => deliveriesApi.resend(deliveryId),
    onSettled: () => void qc.invalidateQueries({ queryKey: notificationKeys.deliveries }),
  });
}

// ------------------------------------------------------------------------------------------------ templates

export function useNotificationTemplates() {
  return useQuery({ queryKey: notificationKeys.templates, queryFn: templatesApi.list });
}

/** A mutation: each click renders again with the current sample values. */
export function useTemplatePreview() {
  return useMutation({ mutationFn: (body: PreviewRequest) => templatesApi.preview(body) });
}
