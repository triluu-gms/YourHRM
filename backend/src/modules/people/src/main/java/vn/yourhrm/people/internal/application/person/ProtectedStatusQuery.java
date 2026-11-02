package vn.yourhrm.people.internal.application.person;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Internal query for the separation stream (BR-PPL-073, US-PPL-063): why an employee is protected against
 * employer-initiated termination on a date, from {@code hr.special_status} (PREGNANCY, CHILD_UNDER_12M). Leave-based
 * protection (maternity, sick leave) comes from T&A (IN-14) and is the caller's part. Empty list = not protected by a
 * special status. Joins the caller's People transaction; no permission check.
 */
@Component
public class ProtectedStatusQuery {

    private final SpecialStatusService statuses;

    public ProtectedStatusQuery(SpecialStatusService statuses) {
        this.statuses = statuses;
    }

    /** Status types (e.g. {@code PREGNANCY}) of the person protecting {@code employmentId} on {@code on}. */
    public List<String> protectedReasons(UUID personId, UUID employmentId, LocalDate on) {
        return statuses.protectedReasons(personId, employmentId, on);
    }
}
