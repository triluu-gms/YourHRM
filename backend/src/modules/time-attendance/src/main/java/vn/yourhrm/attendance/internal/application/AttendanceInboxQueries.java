package vn.yourhrm.attendance.internal.application;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.internal.infra.AttInbox;
import vn.yourhrm.attendance.internal.infra.AttTransactional;

/** Reads T&amp;A's inbox for operations (dead letters). */
@Service
public class AttendanceInboxQueries {

    private final AttInbox inbox;

    public AttendanceInboxQueries(AttInbox inbox) {
        this.inbox = inbox;
    }

    @AttTransactional(readOnly = true)
    public List<AttInbox.Message> list(String status, String consumer, int limit) {
        return inbox.list(status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT),
                consumer, limit);
    }
}
