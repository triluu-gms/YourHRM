package vn.yourhrm.people.internal.application.assignment;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.PayPeriodLocks;

/**
 * Retro flag of dated People events (BR-PPL-037, US-PPL-039): an effective date on or before the end of the latest
 * locked pay period of the legal entity is retro, and the earliest affected period is the month of the effective date
 * (pay periods are calendar months in P1; GAP-064 leaves People's exact cut-off open). Reusable by every People stream
 * that publishes dated events (contract, termination ...).
 */
@Component
public class RetroFlags {

    public record Retro(boolean retro, String earliestAffectedPeriod) {

        public static final Retro NO = new Retro(false, null);
    }

    private final PayPeriodLocks locks;

    public RetroFlags(ObjectProvider<PayPeriodLocks> locks) {
        // Payroll implements PayPeriodLocks; until then nothing is locked (documented stub).
        this.locks = locks.getIfAvailable(() -> PayPeriodLocks.NONE);
    }

    public Retro of(UUID legalEntityId, LocalDate effectiveDate) {
        return locks.lockedThrough(legalEntityId)
                .filter(lockedThrough -> !effectiveDate.isAfter(lockedThrough))
                .map(x -> new Retro(true, YearMonth.from(effectiveDate).toString()))
                .orElse(Retro.NO);
    }
}
