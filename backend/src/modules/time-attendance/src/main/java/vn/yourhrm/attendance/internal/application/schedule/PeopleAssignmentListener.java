package vn.yourhrm.attendance.internal.application.schedule;

import java.math.BigDecimal;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.yourhrm.attendance.internal.infra.AttInbox.Outcome;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.people.api.AssignmentEvents.AssignmentChangeCancelled;
import vn.yourhrm.people.api.AssignmentEvents.AssignmentChanged;
import vn.yourhrm.people.api.EmploymentEvents.EmployeeHired;
import vn.yourhrm.people.api.EmploymentEvents.EmploymentChanged;
import vn.yourhrm.people.api.EmploymentEvents.HireCancelled;
import vn.yourhrm.people.api.PeopleEvent;
import vn.yourhrm.people.api.SeparationEvents.EmployeeTerminated;
import vn.yourhrm.people.api.SeparationEvents.TerminationCancelled;
import vn.yourhrm.people.api.SeparationEvents.TerminationScheduled;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * T&amp;A's consumer of People's in-process events after People's commit, every one through T&amp;A's inbox
 * ({@code att.inbox_message}: dedupe, FAILED dead letter with the error, listed for operations):
 * <ul>
 *   <li>{@code people.AssignmentChanged} / {@code AssignmentChangeCancelled} feed {@code att.ext_hr_assignment} and
 *       regenerate the line's rule-based schedules (BR-ATT-015, future- and back-dated changes included). Since v2 the
 *       event carries the employee category and working regime in force on its start (D3), so T&amp;A no longer reads
 *       them with the data scope of the user who made the change;</li>
 *   <li>{@code people.EmploymentChanged} (D3): the dated employee category / working regime
 *       ({@code att.ext_hr_employment_classification}), then the employment's lines are regenerated;</li>
 *   <li>{@code people.EmployeeHired}, {@code HireCancelled}, {@code TerminationScheduled}, {@code EmployeeTerminated},
 *       {@code TerminationCancelled} feed {@code att.ext_hr_employment} ({@link EmploymentProjection}).</li>
 * </ul>
 * In-process events carry no envelope id; the inbox key is a content id, so a redelivered identical event is a
 * DUPLICATE. A failure never affects People's committed change; the dead letter shows it and the employment can be
 * regenerated ({@code POST /employments/{id}/schedule-assignments/regenerate}). When the Phase-1 DB bus delivers
 * events, the bus adapter calls the same handlers with the envelope id.
 */
@Component
class PeopleAssignmentListener {

    private static final Logger log = LoggerFactory.getLogger(PeopleAssignmentListener.class);

    private final PeopleInbound inbound;
    private final AssignmentProjection assignments;
    private final EmploymentProjection employments;

    PeopleAssignmentListener(PeopleInbound inbound, AssignmentProjection assignments,
            EmploymentProjection employments) {
        this.inbound = inbound;
        this.assignments = assignments;
        this.employments = employments;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(AssignmentChanged e) {
        if (!sameTenant(e)) {
            return;
        }
        LineVersion v = new LineVersion(e.assignmentId(), e.assignmentKey(), e.employmentId(), e.personId(),
                e.assignmentType() == null ? "PRIMARY" : e.assignmentType(), e.legalEntityId(), e.orgUnitId(),
                e.positionId(), e.jobTitleId(), e.locationId(), e.costCentreId(), e.managerEmploymentId(),
                e.employeeCategoryCode(), e.workingRegimeCode(),
                e.fte() == null ? BigDecimal.ONE : e.fte(), e.validFrom(), e.validTo());
        receive(PeopleInbound.ASSIGNMENT_CONSUMER, e, () -> {
            assignments.apply(v, e.initialLoad());
            return true;
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(AssignmentChangeCancelled e) {
        if (sameTenant(e)) {
            receive(PeopleInbound.ASSIGNMENT_CONSUMER, e, () -> {
                assignments.cancel(e.assignmentKey(), e.assignmentId());
                return true;
            });
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(EmployeeHired e) {
        if (sameTenant(e)) {
            receive(PeopleInbound.EMPLOYMENT_CONSUMER, e, () -> employments.hired(e));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(EmploymentChanged e) {
        if (sameTenant(e)) {
            receive(PeopleInbound.EMPLOYMENT_CONSUMER, e, () -> employments.classificationChanged(e));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(HireCancelled e) {
        if (sameTenant(e)) {
            receive(PeopleInbound.EMPLOYMENT_CONSUMER, e, () -> employments.hireCancelled(e));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(TerminationScheduled e) {
        if (sameTenant(e)) {
            receive(PeopleInbound.EMPLOYMENT_CONSUMER, e, () -> employments.terminationScheduled(e));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(EmployeeTerminated e) {
        if (sameTenant(e)) {
            receive(PeopleInbound.EMPLOYMENT_CONSUMER, e, () -> employments.terminated(e));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(TerminationCancelled e) {
        if (sameTenant(e)) {
            receive(PeopleInbound.EMPLOYMENT_CONSUMER, e, () -> employments.terminationCancelled(e));
        }
    }

    private void receive(String consumer, PeopleEvent e, Supplier<Boolean> handler) {
        try {
            Outcome outcome = inbound.receive(consumer, inbound.contentId(e.eventType(), e), e.eventType(), handler);
            if (outcome == Outcome.FAILED) {
                log.warn("{} for an employment failed in T&A and is kept as a dead letter", e.eventType());
            }
        } catch (RuntimeException ex) {
            log.warn("{} not recorded in the T&A inbox: {}", e.eventType(), ex.getClass().getSimpleName());
        }
    }

    private static boolean sameTenant(PeopleEvent e) {
        return TenantContext.isBound() && TenantContext.current().tenantId().equals(e.tenantId());
    }
}
