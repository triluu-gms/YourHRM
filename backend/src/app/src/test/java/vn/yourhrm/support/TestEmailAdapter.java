package vn.yourhrm.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import vn.yourhrm.shared.common.delivery.EmailDeliveryAdapter;

/** Stand-in for the SaaS e-mail delivery adapter (tests only): records every queued e-mail. */
public class TestEmailAdapter implements EmailDeliveryAdapter {

    public record Sent(UUID tenantId, UUID deliveryId, String to, String subject, String body, Attachment attachment) {
    }

    private static final List<Sent> SENT = new CopyOnWriteArrayList<>();

    public static List<Sent> sentTo(String address) {
        return SENT.stream().filter(s -> s.to().equals(address)).toList();
    }

    @Override
    public UUID enqueue(UUID tenantId, UUID deliveryId, String toAddress, String subject, String body,
            String bodyFormat, Attachment attachment) {
        SENT.add(new Sent(tenantId, deliveryId, toAddress, subject, body, attachment));
        return UUID.randomUUID();
    }
}
