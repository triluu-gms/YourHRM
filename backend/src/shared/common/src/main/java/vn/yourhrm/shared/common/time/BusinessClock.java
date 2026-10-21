package vn.yourhrm.shared.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Business time (DESIGN.md §4.7): instants are UTC, business dates are local dates in Vietnam
 * ({@code Asia/Ho_Chi_Minh}) unless a location/legal entity says otherwise.
 */
public class BusinessClock {

    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final Clock clock;

    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    public static BusinessClock system() {
        return new BusinessClock(Clock.systemUTC());
    }

    public Instant now() {
        return clock.instant();
    }

    /** Today's business date in Vietnam. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), VIETNAM);
    }
}
