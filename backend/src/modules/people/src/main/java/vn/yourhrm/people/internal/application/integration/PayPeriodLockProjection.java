package vn.yourhrm.people.internal.application.integration;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.PayPeriodLocks;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * People's default {@link PayPeriodLocks} (BR-PPL-037, US-PPL-039): reads {@code hr.ext_pay_period_lock}, the
 * projection of Payroll's period lock / unlock events fed through {@link InboundEventService#payPeriodLockChanged}.
 * {@link Fallback}: a real Payroll implementation registered as a bean wins over it.
 */
@Component
@Fallback
public class PayPeriodLockProjection implements PayPeriodLocks {

    private final HrDb db;

    public PayPeriodLockProjection(HrDb db) {
        this.db = db;
    }

    @Override
    @HrTransactional(readOnly = true)
    public Optional<LocalDate> lockedThrough(UUID legalEntityId) {
        return db.jdbc().sql("""
                        select locked_through from hr.ext_pay_period_lock
                         where tenant_id = :t and legal_entity_id = :le""")
                .param("t", TenantContext.current().tenantId()).param("le", legalEntityId)
                .query(LocalDate.class).optional();
    }

    // No UPDATE on hr.ext_pay_period_lock: the DDL v0.6 touch trigger (sys.tg_touch) reads NEW.id, which this table
    // has not, so a row is replaced by DELETE + INSERT (DELETE is granted on the projections).

    /**
     * Applies a period lock: the projection keeps the end of the latest locked period of the legal entity.
     *
     * @return whether the projection changed
     */
    boolean locked(UUID eventId, UUID legalEntityId, String periodCode, LocalDate periodEnd) {
        // insert, or move the lock forward; an older lock arriving late changes nothing
        return db.jdbc().sql("""
                        insert into hr.ext_pay_period_lock (tenant_id, legal_entity_id, locked_through, period_code,
                               source_event_id)
                        values (:t, :le, :through, :code, :ev)
                        on conflict (tenant_id, legal_entity_id) do update
                           set locked_through = excluded.locked_through, period_code = excluded.period_code,
                               source_event_id = excluded.source_event_id, received_at = now(),
                               version = hr.ext_pay_period_lock.version + 1
                         where hr.ext_pay_period_lock.locked_through < excluded.locked_through""")
                .param("t", TenantContext.current().tenantId()).param("le", legalEntityId).param("through", periodEnd)
                .param("code", periodCode).param("ev", eventId).update() > 0;
    }

    /**
     * Applies a period unlock (reopen): the lock moves back to the day before the reopened period (the earlier period
     * stays locked; its code is not known from the event, so {@code period_code} is cleared).
     *
     * @return whether the projection changed
     */
    boolean unlocked(UUID eventId, UUID legalEntityId, LocalDate periodStart) {
        return db.jdbc().sql("""
                        update hr.ext_pay_period_lock
                           set locked_through = :before, period_code = null, source_event_id = :ev,
                               received_at = now(), version = version + 1
                         where tenant_id = :t and legal_entity_id = :le and locked_through > :before""")
                .param("before", periodStart.minusDays(1)).param("ev", eventId)
                .param("t", TenantContext.current().tenantId()).param("le", legalEntityId).update() > 0;
    }
}
