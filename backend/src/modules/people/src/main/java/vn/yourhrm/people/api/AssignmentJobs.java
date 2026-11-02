package vn.yourhrm.people.api;

import java.time.LocalDate;

/**
 * Daily job of the assignment and salary streams (US-PPL-038), called by the platform scheduler once per tenant inside
 * that tenant's {@code TenantContext}. Idempotent: a re-run on the same day publishes nothing twice.
 */
public interface AssignmentJobs {

    /** What one run published. */
    record DueChanges(int assignmentChanges, int temporaryTransfersStarted, int temporaryTransfersEnded,
            int salaryChanges) {
    }

    /**
     * Publishes the events of approved future-dated changes whose effective date has come (in effective-date order,
     * so overlapping future changes apply in sequence): {@code people.AssignmentChanged},
     * {@code people.TemporaryTransferChanged} (STARTED / ENDED) and {@code people.SalaryChanged}.
     */
    DueChanges publishDueChanges(LocalDate today);
}
