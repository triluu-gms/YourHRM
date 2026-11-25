package vn.yourhrm.attendance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code att.RetroDeltaCreated} (ATT-P-02, BR-ATT-071): days of a LOCKED period changed after the lock; the signed
 * differences are lines of the result {@code targetResultId} of a later period (the locked snapshot never changes).
 * For tracking: Payroll pays them from the target result.
 */
public record RetroDeltaCreated(
        UUID tenantId,
        UUID legalEntityId,
        UUID employmentId,
        UUID originalPeriodId,
        LocalDate originalPeriodStart,
        UUID originalResultId,
        UUID targetPeriodId,
        UUID targetResultId,
        int targetResultVersion,
        List<Item> items) implements AttendanceEvent {

    public static final String TYPE = "att.RetroDeltaCreated";

    /** One delta: {@code category} is the original category (e.g. WORKED_DAYS), {@code quantity} is signed. */
    public record Item(LocalDate originalSegmentFrom, String category, String unit, BigDecimal quantity,
            boolean precededByDayOt) {
    }

    public RetroDeltaCreated {
        items = List.copyOf(items);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "employment";
    }

    @Override
    public UUID aggregateId() {
        return employmentId;
    }

    @Override
    public LocalDate effectiveDate() {
        return originalPeriodStart;
    }

    @Override
    public boolean retro() {
        return true;
    }
}
