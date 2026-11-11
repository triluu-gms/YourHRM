package vn.yourhrm.core.internal.domain;

/**
 * Status of a location as of a date ({@code core.location.status} + {@code closed_from}): CLOSED from the closing
 * date on (US-CORE-007). The closure itself is final once recorded.
 */
public enum LocationStatus {
    ACTIVE,
    CLOSED
}
