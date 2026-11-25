package vn.yourhrm.attendance.internal.application.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.EmploymentFacts;
import vn.yourhrm.people.api.EmployeeDirectory;
import vn.yourhrm.people.api.EmployeeDirectory.EmploymentSummary;
import vn.yourhrm.people.api.EmploymentEvents.EmployeeHired;
import vn.yourhrm.people.api.EmploymentEvents.EmploymentChanged;
import vn.yourhrm.people.api.EmploymentEvents.HireCancelled;
import vn.yourhrm.people.api.SeparationEvents.EmployeeTerminated;
import vn.yourhrm.people.api.SeparationEvents.TerminationCancelled;
import vn.yourhrm.people.api.SeparationEvents.TerminationScheduled;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Keeps {@code att.ext_hr_employment} (D5: one latest-state row per employment) from People's employment events, and
 * re-evaluates the employment's schedules, which are clipped to the employment period (BR-ATT-039):
 * <ul>
 *   <li>{@code people.EmployeeHired}: the row (PRE_HIRE for the preview event, ACTIVE for the final one); its employee
 *       category and working regime are copied onto the assignment lines for the schedule rules, and are the first
 *       dated row from the hire date when no {@code EmploymentChanged} came first;</li>
 *   <li>{@code people.EmploymentChanged} (D3): {@code [validFrom, validTo)} of
 *       {@code att.ext_hr_employment_classification} takes the new values, the lines are regenerated;</li>
 *   <li>{@code people.HireCancelled}: CANCELLED, every schedule of the employment is dropped;</li>
 *   <li>{@code people.TerminationScheduled}: last working day and termination date (schedules end then);</li>
 *   <li>{@code people.EmployeeTerminated}: TERMINATED;</li>
 *   <li>{@code people.TerminationCancelled}: the dates are cleared, ACTIVE again when People reinstated it.</li>
 * </ul>
 * An event for an employment T&amp;A does not know yet (e.g. hired before T&amp;A was enabled) first loads it from
 * People's {@link EmployeeDirectory}; when that is not visible, the event is skipped. Each method returns whether it
 * applied something (inbox PROCESSED vs SKIPPED).
 */
@Component
public class EmploymentProjection {

    private static final Set<String> STATUSES = Set.of("PRE_HIRE", "ACTIVE", "SUSPENDED", "TERMINATED", "CANCELLED");

    private final ScheduleAssignmentStore store;
    private final ScheduleGenerator generator;
    private final ObjectProvider<EmployeeDirectory> directory;

    EmploymentProjection(ScheduleAssignmentStore store, ScheduleGenerator generator,
            ObjectProvider<EmployeeDirectory> directory) {
        this.store = store;
        this.generator = generator;
        this.directory = directory;
    }

    @AttTransactional
    public boolean hired(EmployeeHired e) {
        EmploymentFacts current = store.employmentFacts(e.employmentId()).orElse(null);
        // events may arrive out of order: the preview never downgrades a later state, and the final event never
        // revives a suspended, terminated or cancelled employment
        String status;
        if (e.preview()) {
            status = current == null || "PRE_HIRE".equals(current.status()) ? "PRE_HIRE" : current.status();
        } else {
            status = current != null && Set.of("SUSPENDED", "TERMINATED", "CANCELLED").contains(current.status())
                    ? current.status() : "ACTIVE";
        }
        store.upsertEmployment(new EmploymentFacts(e.employmentId(), e.personId(), e.legalEntityId(), e.hireDate(),
                e.seniorityDate(), current == null ? null : current.probationEndDate(),
                current == null ? null : current.lastWorkingDate(),
                current == null ? null : current.terminationDate(), status,
                e.workerCategoryCode() == null ? "EMPLOYEE" : e.workerCategoryCode(), e.employeeCategoryCode()),
                Ids.newId());
        store.setLineCategories(e.employmentId(), e.employeeCategoryCode(), e.workingRegimeCode());
        if (store.classifications(e.employmentId()).isEmpty()) {
            store.overwriteClassification(e.employmentId(), e.hireDate(), null, e.employeeCategoryCode(),
                    e.workingRegimeCode(), null);
        }
        regenerate(e.employmentId());
        return true;
    }

    /** D3: the dated employee category / working regime; idempotent (the range is overwritten). */
    @AttTransactional
    public boolean classificationChanged(EmploymentChanged e) {
        store.overwriteClassification(e.employmentId(), e.validFrom(), e.validTo(), e.employeeCategoryCode(),
                e.workingRegimeCode(), e.classificationId());
        regenerate(e.employmentId());
        return true;
    }

    @AttTransactional
    public boolean hireCancelled(HireCancelled e) {
        return change(e.employmentId(), e.effectiveDate(), f -> withStatus(f, "CANCELLED"));
    }

    @AttTransactional
    public boolean terminationScheduled(TerminationScheduled e) {
        return change(e.employmentId(), e.effectiveDate(), f -> new EmploymentFacts(f.employmentId(), f.personId(),
                f.legalEntityId(), f.hireDate(), f.seniorityDate(), f.probationEndDate(), e.lastWorkingDate(),
                e.terminationDate() != null ? e.terminationDate() : e.effectiveDate(), f.status(),
                f.workerCategoryCode(), f.employeeCategoryCode()));
    }

    @AttTransactional
    public boolean terminated(EmployeeTerminated e) {
        return change(e.employmentId(), e.effectiveDate(), f -> new EmploymentFacts(f.employmentId(), f.personId(),
                f.legalEntityId(), f.hireDate(), f.seniorityDate(), f.probationEndDate(), e.lastWorkingDate(),
                e.terminationDate() != null ? e.terminationDate() : e.effectiveDate(), "TERMINATED",
                f.workerCategoryCode(), f.employeeCategoryCode()));
    }

    @AttTransactional
    public boolean terminationCancelled(TerminationCancelled e) {
        return change(e.employmentId(), e.effectiveDate(), f -> new EmploymentFacts(f.employmentId(), f.personId(),
                f.legalEntityId(), f.hireDate(), f.seniorityDate(), f.probationEndDate(), null, null,
                "TERMINATED".equals(f.status()) && e.employmentReinstated() ? "ACTIVE" : f.status(),
                f.workerCategoryCode(), f.employeeCategoryCode()));
    }

    // ------------------------------------------------------------------------------------------------ internals

    private boolean change(UUID employmentId, LocalDate asOf,
            java.util.function.UnaryOperator<EmploymentFacts> update) {
        Optional<EmploymentFacts> current = store.employmentFacts(employmentId).or(() -> fromPeople(employmentId,
                asOf));
        if (current.isEmpty()) {
            return false;
        }
        EmploymentFacts next = update.apply(current.get());
        if (next.terminationDate() != null && next.terminationDate().isBefore(next.hireDate())) {
            next = new EmploymentFacts(next.employmentId(), next.personId(), next.legalEntityId(), next.hireDate(),
                    next.seniorityDate(), next.probationEndDate(), null, next.hireDate(), next.status(),
                    next.workerCategoryCode(), next.employeeCategoryCode());
        }
        store.upsertEmployment(next, Ids.newId());
        regenerate(employmentId);
        return true;
    }

    private void regenerate(UUID employmentId) {
        for (UUID key : store.lineKeysOfEmployment(employmentId)) {
            generator.regenerate(key, "EMPLOYMENT");
        }
    }

    /** The current People state, for an employment T&amp;A has not seen yet; empty when not visible. */
    private Optional<EmploymentFacts> fromPeople(UUID employmentId, LocalDate asOf) {
        EmployeeDirectory dir = directory.getIfAvailable();
        if (dir == null) {
            return Optional.empty();
        }
        try {
            return dir.snapshot(employmentId, asOf == null ? LocalDate.now() : asOf, Instant.now())
                    .map(EmployeeDirectory.EmployeeSnapshot::employment).map(EmploymentProjection::facts);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static EmploymentFacts facts(EmploymentSummary s) {
        return new EmploymentFacts(s.employmentId(), s.personId(), s.legalEntityId(), s.hireDate(),
                s.seniorityDate(), s.probationEndDate(), s.lastWorkingDate(), s.endDate(),
                STATUSES.contains(s.status()) ? s.status() : "ACTIVE",
                s.workerCategoryCode() == null ? "EMPLOYEE" : s.workerCategoryCode(), s.employeeCategoryCode());
    }

    private static EmploymentFacts withStatus(EmploymentFacts f, String status) {
        return new EmploymentFacts(f.employmentId(), f.personId(), f.legalEntityId(), f.hireDate(),
                f.seniorityDate(), f.probationEndDate(), f.lastWorkingDate(), f.terminationDate(), status,
                f.workerCategoryCode(), f.employeeCategoryCode());
    }
}
