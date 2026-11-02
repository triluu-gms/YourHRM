package vn.yourhrm.people.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-declared SPI for Payroll's net-to-gross simulator (BR-PPL-041, US-PPL-043; IF not yet specified by Payroll):
 * the gross that yields the agreed net for an employment on a date. People checks a NET salary record against the
 * regional minimum wage on that gross.
 *
 * <p>Fallback {@link #NONE}: no simulation (empty). People then checks the record's contractual base salary
 * ({@code base_salary_amount}, which is a gross amount) instead and returns the warning
 * {@code gross-up-unavailable}; it never blocks only because the simulator is missing.
 */
public interface GrossUpSimulator {

    Optional<BigDecimal> grossForNet(UUID employmentId, BigDecimal agreedNet, List<String> netGuaranteedScope,
            LocalDate asOf);

    /** STUB until Payroll exists: no simulation available. */
    GrossUpSimulator NONE = (employmentId, agreedNet, scope, asOf) -> Optional.empty();
}
