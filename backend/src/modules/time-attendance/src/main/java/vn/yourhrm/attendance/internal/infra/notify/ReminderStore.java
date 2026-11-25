package vn.yourhrm.attendance.internal.infra.notify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/** The reads behind T&amp;A's daily reminders (BR-ATT-082), in the current tenant. */
@Component
public class ReminderStore {

    /** A computed day with a missing check-in / check-out. */
    public record MissingPunchDay(UUID employmentId, UUID legalEntityId, LocalDate workDate) {
    }

    /** A positive CARRY ledger row not expired yet. */
    public record ExpiringCarry(UUID id, UUID employmentId, UUID legalEntityId, UUID leaveTypeId, int leaveYear,
            BigDecimal quantity, LocalDate expiresOn) {
    }

    /** An OPEN / IN_REVIEW period with a close deadline. */
    public record DuePeriod(UUID id, UUID legalEntityId, LocalDate periodStart, LocalDate periodEnd,
            LocalDate closeDeadline) {
    }

    private final AttDb db;

    public ReminderStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    /** Days of {@code date} (employed, in an open period or none) with MISSING_IN / MISSING_OUT. */
    public List<MissingPunchDay> missingPunches(LocalDate date) {
        return db.jdbc().sql("""
                        select d.employment_id, d.legal_entity_id, d.work_date from att.daily_timesheet d
                         where d.tenant_id = :t and d.work_date = :d
                           and d.exceptions && array['MISSING_IN','MISSING_OUT']::text[]
                           and d.day_status not in ('NOT_EMPLOYED','SUSPENDED')
                           and not exists (select 1 from att.attendance_period p
                                            where p.tenant_id = d.tenant_id and p.id = d.period_id
                                              and p.status in ('CLOSED','LOCKED'))
                         order by d.employment_id""")
                .param("t", tenant()).param("d", date)
                .query((rs, n) -> new MissingPunchDay(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class)))
                .list();
    }

    /** Positive CARRY rows expiring in {@code [from, to]} with no EXPIRE row referring to them yet. */
    public List<ExpiringCarry> carriesExpiring(LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select c.id, c.employment_id, c.legal_entity_id, c.leave_type_id, c.leave_year, c.quantity,
                               c.expires_on
                          from att.leave_balance_ledger c
                         where c.tenant_id = :t and c.txn_type = 'CARRY' and c.quantity > 0
                           and c.expires_on between :f and :to
                           and not exists (select 1 from att.leave_balance_ledger x
                                            where x.tenant_id = c.tenant_id and x.txn_type = 'EXPIRE'
                                              and x.ref_type = 'CARRY_EXPIRY' and x.ref_id = c.id)
                         order by c.employment_id, c.expires_on""")
                .param("t", tenant()).param("f", from).param("to", to)
                .query((rs, n) -> new ExpiringCarry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getInt(5),
                        rs.getBigDecimal(6), rs.getObject(7, LocalDate.class)))
                .list();
    }

    /** OPEN / IN_REVIEW periods whose close deadline lies in {@code [from, to]}. */
    public List<DuePeriod> periodsDue(LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select id, legal_entity_id, period_start, period_end, close_deadline
                          from att.attendance_period
                         where tenant_id = :t and status in ('OPEN','IN_REVIEW')
                           and close_deadline between :f and :to
                         order by close_deadline, legal_entity_id""")
                .param("t", tenant()).param("f", from).param("to", to)
                .query((rs, n) -> new DuePeriod(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class)))
                .list();
    }
}
