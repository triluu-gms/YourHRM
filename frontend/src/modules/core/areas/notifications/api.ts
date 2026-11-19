import { http, type ISOInstant, type UUID } from '@/shared/api';

// ------------------------------------------------------------------------------------------------ inbox

/** `NotificationInboxService.InboxItem`. */
export interface InboxItem {
  id: UUID;
  createdAt: ISOInstant;
  eventCode: string;
  category: string;
  title: string;
  body?: string | null;
  deepLink?: string | null;
  readAt?: ISOInstant | null;
}

/** `NotificationInboxService.Inbox`: no total, only the caller's unread count. */
export interface Inbox {
  items: InboxItem[];
  unread: number;
  page: number;
  size: number;
}

// ------------------------------------------------------------------------------------------------ delivery monitor

/** Phase 1 channels (`NotificationTemplateRegistry.CHANNELS`). */
export const CHANNELS = ['IN_APP', 'EMAIL'] as const;
export type Channel = (typeof CHANNELS)[number];

/** `notification_delivery_status_ck`. */
export const DELIVERY_STATUSES = ['QUEUED', 'SENT', 'DELIVERED', 'OPENED', 'FAILED', 'BOUNCED', 'SUPPRESSED'] as const;
export type DeliveryStatus = (typeof DELIVERY_STATUSES)[number];

/** `NotificationInboxService.DeliveryItem`. */
export interface DeliveryItem {
  deliveryId: UUID;
  notificationId: UUID;
  eventCode: string;
  recipientUserId: UUID;
  channel: string;
  destinationMasked?: string | null;
  status: DeliveryStatus;
  attempts: number;
  lastError?: string | null;
  hasAttachment: boolean;
  createdAt: ISOInstant;
  sentAt?: ISOInstant | null;
  deliveredAt?: ISOInstant | null;
  failedAt?: ISOInstant | null;
}

export interface DeliveryFilter {
  status?: DeliveryStatus;
  channel?: Channel;
  page: number;
  size: number;
}

// ------------------------------------------------------------------------------------------------ templates

export const PLACEHOLDER_KINDS = ['TEXT', 'DATE', 'NUMBER', 'LINK', 'SENSITIVE', 'MONEY'] as const;
export type PlaceholderKind = (typeof PLACEHOLDER_KINDS)[number];

export const LOCALES = ['vi-VN', 'en-US'] as const;
export type Locale = (typeof LOCALES)[number];

/** `NotificationInboxService.TemplateItem`: one event × channel × locale. */
export interface TemplateItem {
  eventCode: string;
  category: string;
  mandatory: boolean;
  channel: string;
  locale: string;
  subject?: string | null;
  body: string;
  bodyFormat: string;
  placeholders: { name: string; kind: PlaceholderKind }[];
  attachmentAllowed: boolean;
}

export interface PreviewRequest {
  eventCode: string;
  channel: string;
  locale?: string;
  sample?: Record<string, unknown>;
  deepLink?: string;
}

export interface TemplatePreview {
  subject?: string | null;
  body: string;
  bodyFormat: string;
}

// ------------------------------------------------------------------------------------------------ clients

export const notificationsApi = {
  inbox: (unreadOnly: boolean, page: number, size: number) =>
    http.get<Inbox>('/core/notifications', { params: { unreadOnly, page, size } }),
  unreadCount: () => http.get<{ unread: number }>('/core/notifications/unread-count'),
  markRead: (id: UUID) => http.post<void>(`/core/notifications/${id}/read`),
  markAllRead: () => http.post<{ marked: number }>('/core/notifications/read-all'),
};

export const deliveriesApi = {
  list: ({ status, channel, page, size }: DeliveryFilter) =>
    http.get<DeliveryItem[]>('/core/notification-deliveries', { params: { status, channel, page, size } }),
  resend: (deliveryId: UUID) => http.post<void>(`/core/notification-deliveries/${deliveryId}/resend`),
};

export const templatesApi = {
  list: () => http.get<TemplateItem[]>('/core/notification-templates'),
  preview: (body: PreviewRequest) => http.post<TemplatePreview>('/core/notification-templates/preview', body),
};
