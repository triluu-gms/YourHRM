package vn.yourhrm.attendance.internal.application.schedule;

import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Writes one People assignment version into {@code att.ext_hr_assignment} and regenerates the rule-based schedules of
 * its line, in one T&amp;A transaction (BR-ATT-015).
 */
@Component
public class AssignmentProjection {

    private final ScheduleAssignmentStore store;
    private final ScheduleGenerator generator;

    AssignmentProjection(ScheduleAssignmentStore store, ScheduleGenerator generator) {
        this.store = store;
        this.generator = generator;
    }

    /** {@code initialLoad}: opening-balance load (ADR-0037 §8), no readiness side effect when nothing changed. */
    @AttTransactional
    public void apply(LineVersion version, boolean initialLoad) {
        LineVersion v = version.employeeCategoryCode() != null ? version
                : store.employmentFacts(version.employmentId())
                        .map(e -> withCategory(version, e.employeeCategoryCode())).orElse(version);
        store.applyLineVersion(v, Ids.newId());
        boolean changed = generator.regenerate(v.assignmentKey(), "PEOPLE_ASSIGNMENT");
        if (!changed && !initialLoad) {
            generator.notifyReadinessAfterCommit(v.employmentId());
        }
    }

    private static LineVersion withCategory(LineVersion v, String category) {
        return new LineVersion(v.id(), v.assignmentKey(), v.employmentId(), v.personId(), v.assignmentType(),
                v.legalEntityId(), v.orgUnitId(), v.positionId(), v.jobTitleId(), v.locationId(), v.costCentreId(),
                v.managerEmploymentId(), category, v.workingRegimeCode(), v.fte(), v.validFrom(), v.validTo());
    }

    /** A cancelled future version: removed, the previous version runs on, the line is regenerated. */
    @AttTransactional
    public void cancel(UUID assignmentKey, UUID versionId) {
        store.removeLineVersion(assignmentKey, versionId);
        generator.regenerate(assignmentKey, "PEOPLE_ASSIGNMENT");
    }
}
