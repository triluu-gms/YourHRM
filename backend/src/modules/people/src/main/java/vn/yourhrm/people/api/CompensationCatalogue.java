package vn.yourhrm.people.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-declared SPI for Payroll's compensation catalogue (IN-17, US-PPL-041/043): salary component codes, pay
 * scales / steps and the pay band of a grade or scale step. Payroll implements it once it exists (fed by
 * {@code pay.CompensationCatalogueChanged}). Evaluated in the current {@code TenantContext}.
 *
 * <p>Fallback {@link #NONE}: every code is accepted (People cannot validate Payroll's codes yet) and no band is known,
 * so the out-of-band warning never fires. Minimum-wage checks do not depend on this SPI.
 */
public interface CompensationCatalogue {

    /** Min / max of the pay band (VND); either bound may be null. */
    record Band(BigDecimal min, BigDecimal max, String source) {
    }

    /** Is {@code componentCode} an active salary component usable as a contractual allowance on {@code asOf}? */
    boolean isAllowanceComponent(String componentCode, LocalDate asOf);

    /** Is the pay scale (and step, when given) defined on {@code asOf}? */
    boolean isPayScale(String payScaleCode, String stepCode, LocalDate asOf);

    /** The band for the scale step, else for the grade; empty when Payroll defines none. */
    Optional<Band> band(String payScaleCode, String stepCode, UUID gradeId, LocalDate asOf);

    /** STUB until Payroll exists: any code is accepted, no band. */
    CompensationCatalogue NONE = new CompensationCatalogue() {
        @Override
        public boolean isAllowanceComponent(String componentCode, LocalDate asOf) {
            return true;
        }

        @Override
        public boolean isPayScale(String payScaleCode, String stepCode, LocalDate asOf) {
            return true;
        }

        @Override
        public Optional<Band> band(String payScaleCode, String stepCode, UUID gradeId, LocalDate asOf) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "CompensationCatalogue.NONE (Payroll not available: any code accepted, no bands)";
        }
    };
}
