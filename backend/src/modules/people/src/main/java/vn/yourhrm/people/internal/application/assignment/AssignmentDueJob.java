package vn.yourhrm.people.internal.application.assignment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.AssignmentJobs;
import vn.yourhrm.people.internal.application.integration.HireApprovalGate;
import vn.yourhrm.people.internal.application.salary.SalaryService;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.assignment.AssignmentRow;
import vn.yourhrm.people.internal.infra.assignment.AssignmentStore;
import vn.yourhrm.people.internal.infra.assignment.ProcessedMarkers;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * The daily job of future-dated changes (US-PPL-038): versions that were recorded before their effective date and
 * whose date has come get their events now, in effective-date order (overlapping future changes apply in sequence);
 * temporary transfers whose last day has passed get ENDED. Idempotent through markers in {@code hr.inbox_message};
 * the window looks back {@link #LOOKBACK_DAYS} days so a missed run is caught up.
 */
@Service
public class AssignmentDueJob implements AssignmentJobs {

    static final String DUE_MARKER = "hr.assignment.due";
    static final String ENDED_MARKER = "hr.assignment.transfer_ended";
    /** Catch-up window of the job, well inside the 90-day inbox retention. */
    public static final int LOOKBACK_DAYS = 60;

    private final HrDb db;
    private final AssignmentStore store;
    private final ProcessedMarkers markers;
    private final EmploymentFacts employments;
    private final AssignmentService assignments;
    private final SalaryService salaries;
    private final HireApprovalGate hireGate;

    public AssignmentDueJob(HrDb db, AssignmentStore store, ProcessedMarkers markers, EmploymentFacts employments,
            AssignmentService assignments, SalaryService salaries, HireApprovalGate hireGate) {
        this.hireGate = hireGate;
        this.db = db;
        this.store = store;
        this.markers = markers;
        this.employments = employments;
        this.assignments = assignments;
        this.salaries = salaries;
    }

    @Override
    @HrTransactional
    public DueChanges publishDueChanges(LocalDate today) {
        int changed = 0;
        int started = 0;
        int ended = 0;
        for (AssignmentRow row : due("a.valid_from", today)) {
            markers.lock(DUE_MARKER, row.id());
            if (markers.exists(DUE_MARKER, row.id())) {
                continue;
            }
            if (predecessorPublished(DUE_MARKER, row.id(), row.validFrom())) {
                markers.mark(DUE_MARKER, row.id(), "people.AssignmentChanged", "SKIPPED");
                continue;
            }
            var emp = employments.require(row.employmentId());
            if ("CANCELLED".equals(emp.status())) {
                markers.mark(DUE_MARKER, row.id(), "people.AssignmentChanged", "SKIPPED");
                continue;
            }
            if (hireGate.pending(emp.id())) {
                // F1 (integration): not announced before the hire is approved; no marker, so HireService releases it
                // on approval (AssignmentService.releaseHeldInitialEvents) or a later run of this job picks it up.
                continue;
            }
            assignments.publishDue(emp, row, true);
            markers.mark(DUE_MARKER, row.id(), "people.AssignmentChanged", "PROCESSED");
            changed++;
            if ("TEMP_TRANSFER".equals(row.assignmentType())) {
                started++;
            }
        }
        for (AssignmentRow row : due("a.valid_to", today)) {
            if (!"TEMP_TRANSFER".equals(row.assignmentType())) {
                continue;
            }
            markers.lock(ENDED_MARKER, row.id());
            if (markers.exists(ENDED_MARKER, row.id())) {
                continue;
            }
            if (predecessorPublished(ENDED_MARKER, row.id(), row.validFrom())) {
                markers.mark(ENDED_MARKER, row.id(), "people.TemporaryTransferChanged", "SKIPPED");
                continue;
            }
            var emp = employments.require(row.employmentId());
            assignments.publishTransferEnded(emp, row);
            markers.mark(ENDED_MARKER, row.id(), "people.TemporaryTransferChanged", "PROCESSED");
            ended++;
        }
        int salary = salaries.publishDueChanges(today, LOOKBACK_DAYS);
        return new DueChanges(changed, started, ended, salary);
    }

    /**
     * Current versions whose {@code dateColumn} (valid_from / valid_to) lies in the window up to today and after the
     * business date on which they were recorded (earlier ones had their events at recording).
     */
    private List<AssignmentRow> due(String dateColumn, LocalDate today) {
        return db.jdbc().sql("select a.* from hr.assignment a where a.tenant_id = :t and a.superseded_at is null"
                        + " and " + dateColumn + " <= :today and " + dateColumn + " > :from"
                        + " and " + dateColumn + " > cast((a.recorded_at at time zone 'Asia/Ho_Chi_Minh') as date)"
                        + " order by " + dateColumn + ", a.recorded_at, a.id")
                .param("t", TenantContext.current().tenantId()).param("today", today)
                .param("from", today.minusDays(LOOKBACK_DAYS))
                .query(store::map).list();
    }

    /**
     * A version re-inserted with another period (e.g. the version before a cancelled change, extended again) replaces
     * one with the same start that the job already published: it must not be published twice.
     */
    private boolean predecessorPublished(String consumer, UUID versionId, LocalDate validFrom) {
        UUID current = versionId;
        for (int hop = 0; hop < 20; hop++) {
            var previous = db.jdbc().sql("""
                            select id from hr.assignment where tenant_id = :t and superseded_by = :id
                               and valid_from = :vf limit 1""")
                    .param("t", TenantContext.current().tenantId()).param("id", current).param("vf", validFrom)
                    .query(UUID.class).optional();
            if (previous.isEmpty()) {
                return false;
            }
            if (markers.exists(consumer, previous.get())) {
                return true;
            }
            current = previous.get();
        }
        return false;
    }

    /** Ids already published by the job (tests / diagnostics). */
    boolean published(UUID versionId) {
        return markers.exists(DUE_MARKER, versionId);
    }
}
