package vn.yourhrm.attendance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The Payroll facts T&amp;A consumes (ATT-C-18, ATT-C-19, ATT-C-24), as an in-process port. The Payroll module does not
 * exist yet, so there is no {@code pay.*} event type to listen to: Payroll (or, until then, an operator tool / test)
 * calls this port with the payload of {@code pay.PayCalendarPublished}, {@code pay.PayGroupAssigned} and
 * {@code pay.PayrollRunStatusChanged}. When Payroll publishes those events, T&amp;A's listener calls the same handlers.
 * Every call goes through T&amp;A's inbox ({@code att.inbox_message}), keyed by {@code eventId}: a redelivery is a
 * DUPLICATE. Runs in the current {@code TenantContext}.
 */
public interface PayrollInbound {

    /** Outcome of one delivery: PROCESSED, SKIPPED, DUPLICATE or FAILED (kept as a dead letter). */
    String deliver(PayCalendarPublished event);

    String deliver(PayGroupAssigned event);

    String deliver(PayrollRunStatusChanged event);

    /**
     * {@code pay.PayCalendarPublished}: the periods of a pay group in a legal entity, with the attendance cut-off, the
     * optional OT cut-off, the T&amp;A close deadline and the standard-day method and value (BR-ATT-016, 072).
     */
    record PayCalendarPublished(UUID eventId, UUID legalEntityId, UUID payGroupId, String stdDayMethod,
            BigDecimal stdDayValue, List<PayPeriod> periods) {

        public PayCalendarPublished {
            periods = List.copyOf(periods);
        }
    }

    /** {@code periodEnd} / {@code otPeriodEnd} inclusive. */
    record PayPeriod(UUID payPeriodId, LocalDate periodStart, LocalDate periodEnd, LocalDate otPeriodStart,
            LocalDate otPeriodEnd, LocalDate closeDeadline) {
    }

    /** {@code pay.PayGroupAssigned}: employment → pay group, effective-dated ({@code validTo} exclusive). */
    record PayGroupAssigned(UUID eventId, UUID assignmentId, UUID employmentId, UUID payGroupId, UUID legalEntityId,
            LocalDate validFrom, LocalDate validTo, long sourceVersion) {
    }

    /**
     * {@code pay.PayrollRunStatusChanged}: APPROVED locks the versions the run holds (BR-ATT-088), UNLOCKED takes them
     * back to CLOSED, REJECTED / WITHDRAWN / CANCELLED release them, REVERSED changes nothing; other statuses are
     * ignored.
     *
     * @param heldVersions the attendance versions the run consumed, as Payroll sees them (for reconciliation)
     */
    record PayrollRunStatusChanged(UUID eventId, UUID payrollRunId, String status, List<HeldVersion> heldVersions) {

        public PayrollRunStatusChanged {
            heldVersions = heldVersions == null ? List.of() : List.copyOf(heldVersions);
        }
    }

    /** @param employmentId null for the PERIOD scope */
    record HeldVersion(UUID periodId, int resultVersion, UUID employmentId) {
    }
}
