package vn.yourhrm.core.api;

import java.util.UUID;

/**
 * Status reports of the SaaS channel delivery adapter (BR-CORE-070, US-CORE-073): the adapter sends and retries;
 * Core only records the outcome per delivery. Called in the tenant's {@code TenantContext}.
 */
public interface NotificationDeliveryStatus {

    /**
     * @param status            SENT, DELIVERED, OPENED, FAILED or BOUNCED
     * @param providerMessageId the provider's id, when known
     * @param error             failure / bounce reason
     */
    void report(UUID deliveryId, String status, String providerMessageId, String error);
}
