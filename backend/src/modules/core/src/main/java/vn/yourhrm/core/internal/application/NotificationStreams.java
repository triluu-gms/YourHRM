package vn.yourhrm.core.internal.application;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events for the in-app inbox (US-CORE-072: new items reach the browser within seconds). Emitters are
 * kept per (tenant, user) on this node; a multi-node deployment needs a pub/sub fan-out (Redis / DB listen-notify),
 * which is a TECH item. Only the notification id and title are pushed; the client reloads the counter.
 */
@Component
public class NotificationStreams {

    static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private record Key(UUID tenantId, UUID userId) {
    }

    private final Map<Key, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter open(UUID tenantId, UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Key key = new Key(tenantId, userId);
        emitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> emitters.getOrDefault(key, List.of()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /** Pushes a new-notification event to every open stream of the user (best effort). */
    public void push(UUID tenantId, UUID userId, UUID notificationId, String title) {
        for (SseEmitter emitter : emitters.getOrDefault(new Key(tenantId, userId), List.of())) {
            try {
                emitter.send(SseEmitter.event().name("notification")
                        .data(Map.of("notificationId", notificationId.toString(), "title", title)));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }
    }

    int openStreams(UUID tenantId, UUID userId) {
        return emitters.getOrDefault(new Key(tenantId, userId), List.of()).size();
    }
}
