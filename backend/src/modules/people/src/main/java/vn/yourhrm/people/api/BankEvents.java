package vn.yourhrm.people.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Events of salary bank accounts (BR-PPL-015, OUT-16). The full account number never travels (D7): Payroll reads it
 * through {@link PayeeBankAccounts}.
 */
public final class BankEvents {

    private BankEvents() {
    }

    /** Subtypes of {@link BankAccountChanged}. */
    public static final String ACTIVATED = "ACTIVATED";
    public static final String CLOSED = "CLOSED";

    /**
     * {@code people.BankAccountChanged} (OUT-16, US-PPL-032): an account became ACTIVE after approval (ACTIVATED) or
     * its validity was ended (CLOSED; {@code validTo} exclusive). Bank accounts belong to the person, so the event is
     * person-level ({@code legalEntityId} and {@code employmentId} are null); Payroll applies it to every employment of
     * the person.
     *
     * @param accountNoLast4 display hint only, never the full number
     * @param splitType      PERCENT, FIXED or REMAINDER (the default account)
     */
    public record BankAccountChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String subtype, UUID bankAccountId, String bankCode, String branchCode,
            String accountNoLast4, String currencyCode, String splitType, BigDecimal splitValue, int priority,
            LocalDate validFrom, LocalDate validTo, String status, boolean initialLoad) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.BankAccountChanged";
        }
    }
}
