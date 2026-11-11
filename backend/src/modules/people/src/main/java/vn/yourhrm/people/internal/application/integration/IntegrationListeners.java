package vn.yourhrm.people.internal.application.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.CoreEvents.UserStatusChanged;
import vn.yourhrm.people.api.BankEvents;
import vn.yourhrm.people.api.BankEvents.BankAccountChanged;
import vn.yourhrm.people.api.ContractEvents;
import vn.yourhrm.people.api.ContractEvents.ContractChanged;
import vn.yourhrm.people.api.EmploymentEvents.EmployeeHired;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * In-process consumers of the integration stream (US-PPL-082):
 * <ul>
 *   <li>Core's {@code core.UserStatusChanged} (IN-29, published in-process by Core after its commit) goes through the
 *       People inbox; it carries no employment link, so it is recorded (SKIPPED) until Core provides one;</li>
 *   <li>People's own {@code EmployeeHired}, {@code ContractChanged} SIGNED / RENEWED / CONVERTED and
 *       {@code BankAccountChanged} ACTIVATED re-evaluate the onboarding readiness in a savepoint of the change's
 *       transaction (a failure never blocks the change).</li>
 * </ul>
 */
@Component
class IntegrationListeners {

    private static final Logger log = LoggerFactory.getLogger(IntegrationListeners.class);
    private static final List<String> SIGNATURES = List.of(ContractEvents.SIGNED, ContractEvents.RENEWED,
            ContractEvents.CONVERTED);

    private final InboundEventService inbound;
    private final OnboardingReadinessService readiness;
    private final TransactionTemplate nested;

    IntegrationListeners(InboundEventService inbound, OnboardingReadinessService readiness,
            @Qualifier(HrDb.TX) PlatformTransactionManager hrTm) {
        this.inbound = inbound;
        this.readiness = readiness;
        this.nested = new TransactionTemplate(hrTm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(UserStatusChanged e) {
        if (!TenantContext.isBound() || !TenantContext.current().tenantId().equals(e.tenantId())) {
            return;
        }
        try {
            inbound.userStatusChanged(Ids.newId(), e.userAccountId(), null, e.status());
        } catch (RuntimeException ex) {
            log.warn("core.UserStatusChanged not recorded in the People inbox: {}", ex.getClass().getSimpleName());
        }
    }

    @EventListener
    void on(EmployeeHired e) {
        evaluate(e.employmentId(), "EMPLOYEE_HIRED");
    }

    @EventListener
    void on(ContractChanged e) {
        if (!e.initialLoad() && SIGNATURES.contains(e.subtype())) {
            evaluate(e.employmentId(), "CONTRACT_SIGNED");
        }
    }

    @EventListener
    void on(BankAccountChanged e) {
        if (e.initialLoad() || !BankEvents.ACTIVATED.equals(e.subtype()) || e.personId() == null) {
            return;
        }
        try {
            for (UUID id : readiness.onboardingEmploymentsOf(e.personId())) {
                evaluate(id, "BANK_ACCOUNT_CHANGED");
            }
        } catch (RuntimeException ex) {
            log.warn("Onboarding readiness after a bank account change failed: {}", ex.getMessage());
        }
    }

    private void evaluate(UUID employmentId, String trigger) {
        if (employmentId == null) {
            return;
        }
        try {
            nested.execute(s -> readiness.evaluate(employmentId, trigger, Map.of()));
        } catch (RuntimeException ex) {
            log.warn("Onboarding readiness ({}) failed and was rolled back to its savepoint: {}", trigger,
                    ex.getMessage());
        }
    }
}
