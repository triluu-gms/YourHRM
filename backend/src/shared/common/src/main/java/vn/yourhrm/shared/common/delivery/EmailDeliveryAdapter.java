package vn.yourhrm.shared.common.delivery;

import java.util.UUID;

/**
 * SPI of the SaaS channel delivery adapter for e-mail (BR-CORE-070, BR-SAAS; SMTP/DKIM, provider accounts, retries),
 * implemented by the SaaS module. Core enqueues after its transaction commits; the adapter reports the outcome back
 * through Core's {@code NotificationDeliveryStatus}. Without an implementation, e-mail deliveries stay QUEUED.
 */
public interface EmailDeliveryAdapter {

    /**
     * Queues one e-mail and returns the adapter's job id.
     *
     * @param deliveryId {@code core.notification_delivery.id}, echoed back in status reports
     * @param attachment a password-protected PDF of the document service, or null
     */
    UUID enqueue(UUID tenantId, UUID deliveryId, String toAddress, String subject, String body, String bodyFormat,
            Attachment attachment);

    /** A document-service file to attach (already encrypted by its producer). */
    record Attachment(UUID documentId, String fileName, String mediaType, String sha256) {
    }
}
