package vn.yourhrm.people.internal.application.integration;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.IntegrationInbox.AbsenceChange;
import vn.yourhrm.people.api.SeparationAbsences;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * People's default {@link SeparationAbsences} (BR-PPL-073, US-PPL-063): reads {@code hr.ext_att_absence}, the
 * projection of {@code att.AbsenceChanged} fed through {@link InboundEventService#absenceChanged}. The leave data is
 * <em>available</em> once the tenant's T&amp;A has sent at least one absence (a tenant without T&amp;A never has a row,
 * so the separation check keeps telling HR to confirm leave-based protection manually). {@link Fallback}: a real
 * T&amp;A implementation registered as a bean wins over it.
 *
 * <p>{@code absence_type_code} is P3 (may reveal health or pregnancy): it is never logged or put in an event; the
 * separation stream masks it for users without the special-status view.
 */
@Component
@Fallback
public class AbsenceProjection implements SeparationAbsences {

    private final HrDb db;

    public AbsenceProjection(HrDb db) {
        this.db = db;
    }

    @Override
    @HrTransactional(readOnly = true)
    public Result protectiveAbsences(UUID employmentId, LocalDate from, LocalDate to) {
        UUID t = TenantContext.current().tenantId();
        boolean fed = db.jdbc().sql("select exists (select 1 from hr.ext_att_absence where tenant_id = :t)")
                .param("t", t).query(Boolean.class).single();
        if (!fed) {
            return Result.unavailable();
        }
        List<Absence> absences = db.jdbc().sql("""
                        select absence_type_code, start_date, end_date from hr.ext_att_absence
                         where tenant_id = :t and employment_id = :e and status = 'ACTIVE' and is_protective
                           and absence_period && daterange(cast(:from as date), cast(:to as date), '[]')
                         order by start_date""")
                .param("t", t).param("e", employmentId).param("from", from).param("to", to)
                .query((rs, n) -> new Absence(rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)))
                .list();
        return new Result(true, absences);
    }

    /** Inserts or replaces the absence (ACTIVE). */
    boolean upsert(UUID eventId, AbsenceChange a) {
        return db.jdbc().sql("""
                        insert into hr.ext_att_absence (tenant_id, att_absence_id, employment_id, absence_type_code,
                               start_date, end_date, is_protective, status, source_event_id)
                        values (:t, :id, :e, :type, :from, :to, :prot, 'ACTIVE', :ev)
                        on conflict (tenant_id, att_absence_id) do update
                           set employment_id = excluded.employment_id, absence_type_code = excluded.absence_type_code,
                               start_date = excluded.start_date, end_date = excluded.end_date,
                               is_protective = excluded.is_protective, status = 'ACTIVE',
                               source_event_id = excluded.source_event_id, received_at = now(),
                               version = hr.ext_att_absence.version + 1""")
                .param("t", TenantContext.current().tenantId()).param("id", a.absenceId())
                .param("e", a.employmentId()).param("type", a.absenceTypeCode()).param("from", a.from())
                .param("to", a.to()).param("prot", a.protective()).param("ev", eventId).update() > 0;
    }

    /** Cancels the absence; false when People never received it. */
    boolean cancel(UUID eventId, UUID absenceId) {
        return db.jdbc().sql("""
                        update hr.ext_att_absence set status = 'CANCELLED', source_event_id = :ev, received_at = now(),
                               version = version + 1
                         where tenant_id = :t and att_absence_id = :id and status <> 'CANCELLED'""")
                .param("ev", eventId).param("t", TenantContext.current().tenantId()).param("id", absenceId)
                .update() > 0;
    }
}
