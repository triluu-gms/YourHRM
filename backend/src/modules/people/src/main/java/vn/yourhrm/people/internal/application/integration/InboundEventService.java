package vn.yourhrm.people.internal.application.integration;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.IntegrationInbox;
import vn.yourhrm.people.api.OnboardingReadinessSources.ItemState;
import vn.yourhrm.people.api.PersonResidencyProposals;
import vn.yourhrm.people.api.SeparationSettlements;
import vn.yourhrm.people.api.SeparationSettlements.FinalSettlementStatus;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.integration.HrInbox;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * People's {@link IntegrationInbox} (US-PPL-082, IN-14/19/29/30/32): every intake is deduplicated and dead-lettered
 * through {@link HrInbox} under the consumer name {@code hr.inbox.<producer event type>}, then routed to the owning
 * People stream (separation settlements, residency proposals), to the onboarding readiness, or to People's projections
 * of other modules' facts ({@code hr.ext_att_absence}, {@code hr.ext_pay_period_lock}; row-audited by the DDL).
 */
@Service
public class InboundEventService implements IntegrationInbox {

    static final String USER_STATUS = "hr.inbox.core.UserStatusChanged";
    static final String PAY_GROUP = "hr.inbox.pay.PayGroupAssigned";
    static final String SCHEDULE = "hr.inbox.att.WorkScheduleAssigned";
    static final String SETTLEMENT = "hr.inbox.pay.FinalSettlementStatusChanged";
    static final String RESIDENCY = "hr.inbox.pay.ResidencyProposed";
    static final String ABSENCE = "hr.inbox.att.AbsenceChanged";
    static final String PAY_PERIOD_LOCK = "hr.inbox.pay.PayPeriodLockChanged";
    /** {@code att.AbsenceChanged} statuses that withdraw the absence. */
    static final Set<String> CANCELLING = Set.of("CANCELLED", "REJECTED", "WITHDRAWN", "DELETED");

    private final HrDb db;
    private final HrInbox inbox;
    private final OnboardingReadinessService readiness;
    private final SeparationSettlements settlements;
    private final PersonResidencyProposals residency;
    private final AbsenceProjection absences;
    private final PayPeriodLockProjection locks;

    public InboundEventService(HrDb db, HrInbox inbox, OnboardingReadinessService readiness,
            SeparationSettlements settlements, PersonResidencyProposals residency, AbsenceProjection absences,
            PayPeriodLockProjection locks) {
        this.db = db;
        this.inbox = inbox;
        this.readiness = readiness;
        this.settlements = settlements;
        this.residency = residency;
        this.absences = absences;
        this.locks = locks;
    }

    @Override
    @HrTransactional
    public Outcome userStatusChanged(UUID eventId, UUID userAccountId, UUID employmentId, String status) {
        return inbox.handle(USER_STATUS, eventId, "core.UserStatusChanged", () -> {
            if (employmentId == null) {
                return false; // Core's event carries no employment link (no user <-> employment API yet)
            }
            requireEmployment(employmentId);
            ItemState state = "ACTIVE".equals(status) || "INVITED".equals(status) ? ItemState.READY : ItemState.MISSING;
            readiness.evaluate(employmentId, "USER_STATUS_CHANGED", Map.of("USER_ACCOUNT", state));
            return true;
        });
    }

    @Override
    @HrTransactional
    public Outcome payGroupAssigned(UUID eventId, UUID employmentId, String payGroupCode, boolean assigned) {
        return inbox.handle(PAY_GROUP, eventId, "pay.PayGroupAssigned", () -> {
            requireEmployment(employmentId);
            readiness.evaluate(employmentId, "PAY_GROUP_ASSIGNED",
                    Map.of("PAY_GROUP", assigned ? ItemState.READY : ItemState.MISSING));
            return true;
        });
    }

    @Override
    @HrTransactional
    public Outcome workScheduleAssigned(UUID eventId, UUID employmentId, boolean assigned) {
        return inbox.handle(SCHEDULE, eventId, "att.WorkScheduleAssigned", () -> {
            requireEmployment(employmentId);
            readiness.evaluate(employmentId, "WORK_SCHEDULE_ASSIGNED",
                    Map.of("WORK_SCHEDULE", assigned ? ItemState.READY : ItemState.MISSING));
            return true;
        });
    }

    @Override
    @HrTransactional
    public Outcome finalSettlementStatusChanged(UUID eventId, UUID employmentId, UUID payrollResultRef, String status,
            LocalDate settlementDueDate) {
        return inbox.handle(SETTLEMENT, eventId, "pay.FinalSettlementStatusChanged", () ->
                settlements.finalSettlementStatusChanged(new FinalSettlementStatus(eventId, employmentId,
                        payrollResultRef, status, settlementDueDate)));
    }

    @Override
    @HrTransactional
    public Outcome residencyProposed(UUID eventId, PersonResidencyProposals.Proposal proposal) {
        return inbox.handle(RESIDENCY, eventId, "pay.ResidencyProposed", () -> {
            residency.receive(proposal);
            return true;
        });
    }

    @Override
    @HrTransactional
    public Outcome absenceChanged(UUID eventId, AbsenceChange change) {
        return inbox.handle(ABSENCE, eventId, "att.AbsenceChanged", () -> {
            if (change.absenceId() == null) {
                throw ApiException.unprocessable("absence-id-required", "The event carries no absence id");
            }
            if (change.status() != null && CANCELLING.contains(change.status().toUpperCase(Locale.ROOT))) {
                return absences.cancel(eventId, change.absenceId());
            }
            requireEmployment(change.employmentId());
            if (change.absenceTypeCode() == null || change.from() == null
                    || (change.to() != null && change.to().isBefore(change.from()))) {
                // no P3 value in the message: it is stored as the dead letter's error
                throw ApiException.unprocessable("invalid-absence", "The absence needs a type and a valid period");
            }
            return absences.upsert(eventId, change);
        });
    }

    @Override
    @HrTransactional
    public Outcome payPeriodLockChanged(UUID eventId, PayPeriodLockChange change) {
        return inbox.handle(PAY_PERIOD_LOCK, eventId, "pay.PayPeriodLockChanged", () -> {
            if (change.legalEntityId() == null) {
                throw ApiException.unprocessable("legal-entity-required", "The event carries no legal entity id");
            }
            if (change.locked()) {
                if (change.periodEnd() == null) {
                    throw ApiException.unprocessable("period-end-required", "A period lock needs the period end");
                }
                return locks.locked(eventId, change.legalEntityId(), change.periodCode(), change.periodEnd());
            }
            if (change.periodStart() == null) {
                throw ApiException.unprocessable("period-start-required", "A period unlock needs the period start");
            }
            return locks.unlocked(eventId, change.legalEntityId(), change.periodStart());
        });
    }

    /** Inbox rows for operations (dead letters: {@code status = FAILED}). */
    @HrTransactional(readOnly = true)
    public List<HrInbox.Message> messages(String status, String consumer, int limit) {
        return inbox.list(status, consumer, limit);
    }

    private UUID requireEmployment(UUID employmentId) {
        if (employmentId == null) {
            throw ApiException.unprocessable("employment-required", "The event carries no employment id");
        }
        return db.jdbc().sql("select legal_entity_id from hr.employment where tenant_id = :t and id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", employmentId).query(UUID.class)
                .optional().orElseThrow(() -> ApiException.notFound("Employment", employmentId));
    }
}
