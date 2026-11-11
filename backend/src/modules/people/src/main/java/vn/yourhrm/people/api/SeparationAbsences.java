package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Consumer-declared SPI (ADR-0001 decision 3) for the leave periods that protect an employee against employer-initiated
 * termination (BR-PPL-073, US-PPL-063, IN-14 {@code att.AbsenceChanged}): maternity leave, sick leave under treatment
 * and other statutory leave [verify-legal Art. 37, 137]. Evaluated in the current {@code TenantContext}.
 *
 * <p>People's default implementation reads its projection {@code hr.ext_att_absence}, fed by {@code att.AbsenceChanged}
 * through {@link IntegrationInbox#absenceChanged}; a T&amp;A implementation registered as a bean takes precedence. While
 * a tenant's T&amp;A has sent no absence at all the leave data is <em>unavailable</em> (not "no leave"): the separation
 * check then relies on {@code hr.special_status} only and tells HR in the response that leave-based protection could
 * not be checked, so HR confirms it manually before approving.
 */
public interface SeparationAbsences {

    /** A protective absence overlapping the checked period; {@code absenceTypeCode} e.g. MATERNITY, SICK_TREATMENT. */
    record Absence(String absenceTypeCode, LocalDate from, LocalDate to) {
    }

    /** {@code available=false}: the leave owner cannot answer (stub), {@code absences} is then empty. */
    record Result(boolean available, List<Absence> absences) {

        public Result {
            absences = absences == null ? List.of() : List.copyOf(absences);
        }

        public static Result unavailable() {
            return new Result(false, List.of());
        }
    }

    /** Protective absences of {@code employmentId} overlapping {@code [from, to]} (inclusive). */
    Result protectiveAbsences(UUID employmentId, LocalDate from, LocalDate to);

    /** Leave data unavailable (no leave owner can answer); the consumers' last-resort fallback. */
    SeparationAbsences NONE = (employmentId, from, to) -> Result.unavailable();
}
