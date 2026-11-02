package vn.yourhrm.people.internal.web.assignment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The {@code asKnown} query parameter of as-known reads (US-PPL-037): a business date (what was known at the end of
 * that day in Vietnam) or an ISO instant with offset (what was known at that instant). Returned as an EXCLUSIVE bound.
 */
public final class AsKnown {

    private AsKnown() {
    }

    public static Instant exclusiveBound(String asKnown) {
        if (asKnown == null || asKnown.isBlank()) {
            return null;
        }
        try {
            if (asKnown.length() == 10) {
                return LocalDate.parse(asKnown).plusDays(1).atStartOfDay(BusinessClock.VIETNAM).toInstant();
            }
            return OffsetDateTime.parse(asKnown).toInstant().plus(1, ChronoUnit.MICROS);
        } catch (DateTimeParseException ex) {
            throw ApiException.unprocessable("invalid-as-known",
                    "asKnown must be a date (yyyy-MM-dd) or an ISO date-time with offset");
        }
    }
}
