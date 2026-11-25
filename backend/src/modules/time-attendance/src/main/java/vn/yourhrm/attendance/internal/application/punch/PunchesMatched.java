package vn.yourhrm.attendance.internal.application.punch;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * In-process event: punches of these employments were matched after the import (quarantine released, US-ATT-015);
 * {@code ranges} holds the first and last work-date hint per employment. Daily processing recomputes the days after
 * commit.
 */
public record PunchesMatched(UUID tenantId, Map<UUID, LocalDate[]> ranges) {

    public PunchesMatched {
        ranges = Map.copyOf(ranges);
    }
}
