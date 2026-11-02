package vn.yourhrm.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;

/**
 * Records Core's and the workflow engine's in-process domain events (tests only); filter by tenant to stay
 * independent of other tests.
 */
public class RecordedEvents {

    private static final List<Object> EVENTS = new CopyOnWriteArrayList<>();

    @EventListener
    void on(Object event) {
        String type = event.getClass().getName();
        if (type.startsWith("vn.yourhrm.core.api.CoreEvents")
                || type.startsWith("vn.yourhrm.shared.workflow.api.WorkflowEvents")) {
            EVENTS.add(event);
        }
    }

    public static <T> List<T> of(Class<T> type) {
        return EVENTS.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
