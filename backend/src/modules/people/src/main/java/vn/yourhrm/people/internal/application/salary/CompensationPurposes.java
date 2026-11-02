package vn.yourhrm.people.internal.application.salary;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Purpose of salary views (US-PPL-045, NFR-PPL-006): the purpose is captured on the first view of a session and
 * written with every view to the audit. The client sends {@code purpose} (a request parameter, never a header) on
 * its first salary view; it is remembered for the user for the session length of tenant setting
 * {@code hr.compensation.purpose_session_minutes}, so later views of the session may omit it. Kept in memory per
 * application node: a view on another node (or after a restart) asks for the purpose again.
 */
@Component
public class CompensationPurposes {

    static final String SESSION_MINUTES = "hr.compensation.purpose_session_minutes";

    private record Key(UUID tenantId, UUID userId) {
    }

    private record Entry(String purpose, Instant expires) {
    }

    private final Map<Key, Entry> sessions = new ConcurrentHashMap<>();
    private final Settings settings;
    private final BusinessClock clock;

    public CompensationPurposes(Settings settings, BusinessClock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** The purpose of this view: the given one (remembered for the session), else the session's; 422 when none. */
    public String resolve(String given) {
        TenantContext ctx = TenantContext.current();
        Key key = new Key(ctx.tenantId(), ctx.userAccountId());
        Instant now = clock.now();
        if (given != null && !given.isBlank()) {
            String purpose = given.strip();
            if (purpose.length() > 500) {
                throw ApiException.unprocessable("invalid-purpose", "The purpose is limited to 500 characters");
            }
            long minutes = settings.longValue(SESSION_MINUTES, Settings.Scope.tenant(), clock.today());
            sessions.put(key, new Entry(purpose, now.plus(Duration.ofMinutes(minutes))));
            return purpose;
        }
        Entry e = sessions.get(key);
        if (e == null || e.expires().isBefore(now)) {
            sessions.remove(key);
            throw ApiException.unprocessable("purpose-required",
                    "State the purpose of viewing salary data (parameter 'purpose'); it is recorded in the audit log");
        }
        return e.purpose();
    }
}
