package vn.yourhrm.people.internal.application.employment;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.SalaryEvents.SalaryChanged;

/**
 * US-PPL-023: a salary version took effect ({@code people.SalaryChanged}, published in-process inside the salary
 * transaction). When an insurance type uses HIGHEST_SALARY on that date, the person's participation is re-derived from
 * the version's start in a savepoint of the same transaction (a failure never blocks the salary change). Opening-load
 * versions are skipped: the import re-derives once at the end.
 */
@Component
class SalaryChangeListener {

    private final InsuranceParticipationService insurance;
    private final Savepoints savepoints;

    SalaryChangeListener(InsuranceParticipationService insurance, Savepoints savepoints) {
        this.insurance = insurance;
        this.savepoints = savepoints;
    }

    @EventListener
    void on(SalaryChanged e) {
        if (e.initialLoad() || e.personId() == null) {
            return;
        }
        savepoints.tryRun("Insurance participation after salary change", () -> {
            if (!insurance.usesHighestSalary(e.validFrom())) {
                return 0;
            }
            return insurance.derive(e.personId(), e.validFrom(), "SALARY_CHANGED");
        }, 0);
    }
}
