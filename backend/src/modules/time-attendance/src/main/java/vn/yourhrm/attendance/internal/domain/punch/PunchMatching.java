package vn.yourhrm.attendance.internal.domain.punch;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Matching of a punch to an employment through the enrolment mapping valid on the punch's local date (BR-ATT-022).
 * A mapping of the punch's own device wins over a tenant-wide mapping (device NULL) of the same enrol id. Without a
 * registered device the punch is {@code QUARANTINED_UNKNOWN_DEVICE}; with mappings of the enrol id that do not cover
 * the date, {@code QUARANTINED_EXPIRED}; with none at all, {@code QUARANTINED_UNMAPPED}.
 */
public final class PunchMatching {

    private PunchMatching() {
    }

    public static final String VALID = "VALID";
    public static final String UNMAPPED = "QUARANTINED_UNMAPPED";
    public static final String EXPIRED = "QUARANTINED_EXPIRED";
    public static final String UNKNOWN_DEVICE = "QUARANTINED_UNKNOWN_DEVICE";
    public static final String IGNORED = "IGNORED";

    /** One enrolment mapping version; {@code deviceId} null = every device; {@code validTo} exclusive. */
    public record Span(UUID id, UUID deviceId, String enrolId, UUID employmentId, LocalDate validFrom,
            LocalDate validTo) {

        public boolean covers(LocalDate d) {
            return !d.isBefore(validFrom) && (validTo == null || d.isBefore(validTo));
        }
    }

    /** {@code mappingId} / {@code employmentId} only when VALID. */
    public record Decision(String status, UUID mappingId, UUID employmentId) {

        public boolean valid() {
            return VALID.equals(status);
        }
    }

    /**
     * @param deviceId the registered device of the punch's terminal, null when the terminal is unknown
     * @param spans    mappings of the punch's enrol id (any device), as loaded
     */
    public static Decision decide(UUID deviceId, String enrolId, List<Span> spans, LocalDate date) {
        if (deviceId == null) {
            return new Decision(UNKNOWN_DEVICE, null, null);
        }
        List<Span> mine = spans.stream().filter(s -> s.enrolId().equals(enrolId)).toList();
        for (UUID scope : new UUID[] {deviceId, null}) {
            for (Span s : mine) {
                if (Objects.equals(s.deviceId(), scope) && s.covers(date)) {
                    return new Decision(VALID, s.id(), s.employmentId());
                }
            }
        }
        boolean any = mine.stream().anyMatch(s -> s.deviceId() == null || s.deviceId().equals(deviceId));
        return new Decision(any ? EXPIRED : UNMAPPED, null, null);
    }
}
