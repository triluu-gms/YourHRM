package vn.yourhrm.people.internal.application.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.SeparationEvents.EmployeeTerminated;
import vn.yourhrm.people.internal.application.employment.InsuranceParticipationService;
import vn.yourhrm.people.internal.application.employment.PrimaryEmploymentService;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Integration fix F3 (BR-PPL-004/075, US-PPL-022/023/066): when the separation stream completes a termination it
 * publishes {@code people.EmployeeTerminated} inside its People transaction (in-process as well). This listener runs
 * synchronously in that same transaction, so the follow-ups commit (or roll back) with the termination:
 *
 * <ol>
 *   <li>the PIT family-deduction designation of the employment ends the day after its end date and the only remaining
 *       employment takes over ({@link PrimaryEmploymentService#onEmploymentEnded}, OUT-36);</li>
 *   <li>insurance participation is re-derived from the day after ({@link InsuranceParticipationService#onEmploymentEnded},
 *       OUT-37);</li>
 *   <li>the live labour contract (ACTIVE / SUSPENDED, started on or before the termination date) becomes TERMINATED
 *       with its end date cut to the termination date (inclusive, like {@code hr.employment.end_date}); contracts that
 *       would only start after it (renewal drafts, signed future renewals) are CANCELLED.</li>
 * </ol>
 * Steps 1 and 2 run in savepoints: a failing derivation (e.g. a missing legal parameter) never blocks the termination;
 * the daily jobs of the employment stream ({@code closeEndedPrimaries}, {@code rederiveEnded}) catch it up later.
 * No extra event is published for the contract: {@code EmployeeTerminated} is the consumers' signal (the catalogue has
 * no {@code ContractChanged} subtype for a termination).
 */
@Component
class EmploymentEndedListener {

    private static final Logger log = LoggerFactory.getLogger(EmploymentEndedListener.class);

    private final HrDb db;
    private final PrimaryEmploymentService primaries;
    private final InsuranceParticipationService insurance;
    private final AuditLog audit;
    private final TransactionTemplate nested;

    EmploymentEndedListener(HrDb db, PrimaryEmploymentService primaries, InsuranceParticipationService insurance,
            AuditLog audit, @Qualifier(HrDb.TX) PlatformTransactionManager hrTm) {
        this.db = db;
        this.primaries = primaries;
        this.insurance = insurance;
        this.audit = audit;
        this.nested = new TransactionTemplate(hrTm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @EventListener
    void on(EmployeeTerminated e) {
        if (e.employmentId() == null || e.terminationDate() == null) {
            return;
        }
        UUID employmentId = e.employmentId();
        savepoint("PIT primary employment after termination", () -> primaries.onEmploymentEnded(employmentId));
        savepoint("Insurance participation after termination", () -> insurance.onEmploymentEnded(employmentId));
        terminateContracts(e);
    }

    private void terminateContracts(EmployeeTerminated e) {
        UUID tenant = TenantContext.current().tenantId();
        UUID actor = TenantContext.current().userAccountId();
        List<UUID> terminated = db.jdbc().sql("""
                        update hr.contract set status = 'TERMINATED',
                               end_date = case when end_date is null or end_date > :term then cast(:term as date)
                                               else end_date end,
                               version = version + 1, updated_at = now(), updated_by = :a
                         where tenant_id = :t and employment_id = :e and status in ('ACTIVE','SUSPENDED')
                           and start_date <= :term
                        returning id""")
                .param("term", e.terminationDate()).param("a", actor).param("t", tenant)
                .param("e", e.employmentId()).query(UUID.class).list();
        List<UUID> cancelled = db.jdbc().sql("""
                        update hr.contract set status = 'CANCELLED', version = version + 1, updated_at = now(),
                               updated_by = :a
                         where tenant_id = :t and employment_id = :e
                           and status in ('DRAFT','PENDING_SIGNATURE','ACTIVE') and start_date > :term
                        returning id""")
                .param("term", e.terminationDate()).param("a", actor).param("t", tenant)
                .param("e", e.employmentId()).query(UUID.class).list();
        for (UUID id : terminated) {
            audit.record(new BusinessAction("TERMINATE_CONTRACT", "hr", "contract", id, e.legalEntityId(),
                    Map.of("employmentId", e.employmentId().toString(), "terminationDate",
                            e.terminationDate().toString(), "separationId", String.valueOf(e.separationId()))));
        }
        for (UUID id : cancelled) {
            audit.record(new BusinessAction("CANCEL_CONTRACT", "hr", "contract", id, e.legalEntityId(),
                    Map.of("employmentId", e.employmentId().toString(), "reason", "EMPLOYMENT_TERMINATED")));
        }
    }

    private void savepoint(String what, Supplier<Object> work) {
        try {
            nested.execute(s -> work.get());
        } catch (RuntimeException ex) {
            log.warn("{} failed and was rolled back to its savepoint (the daily job retries): {}", what,
                    ex.getMessage());
        }
    }
}
