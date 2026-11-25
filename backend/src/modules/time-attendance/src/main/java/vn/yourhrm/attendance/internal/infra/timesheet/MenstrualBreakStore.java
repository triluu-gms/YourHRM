package vn.yourhrm.attendance.internal.infra.timesheet;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.menstrual_break_day} (BR-ATT-035, DDL v0.10): the dates a worker declared for the paid menstrual
 * break. The rows hold the date only (data minimisation); every method runs inside the caller's T&amp;A transaction.
 */
@Component
public class MenstrualBreakStore {

    public record DayRow(UUID id, UUID employmentId, LocalDate workDate, String source, String status,
            Instant createdAt, Instant withdrawnAt) {
    }

    private final AttDb db;

    public MenstrualBreakStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static DayRow row(ResultSet rs, int n) throws SQLException {
        return new DayRow(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getObject("work_date", LocalDate.class), rs.getString("source"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "withdrawn_at"));
    }

    /**
     * Serialises declarations of one employment (the monthly limit is checked and written in the same transaction).
     */
    public void lockEmployment(UUID employmentId) {
        db.jdbc().sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", "att.menstrual_break:" + tenant() + ":" + employmentId).query((rs, n) -> 1).list();
    }

    /** ACTIVE declared dates of the employment in {@code [from, to]}, ascending. */
    public List<LocalDate> activeDates(UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select work_date from att.menstrual_break_day
                         where tenant_id = :t and employment_id = :e and status = 'ACTIVE'
                           and work_date between :f and :l
                         order by work_date""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("l", to)
                .query(LocalDate.class).list();
    }

    /** Rows (ACTIVE and WITHDRAWN) of the employment in a month, by date. */
    public List<DayRow> ofMonth(UUID employmentId, YearMonth month) {
        return db.jdbc().sql("""
                        select id, employment_id, work_date, source, status, created_at, withdrawn_at
                          from att.menstrual_break_day
                         where tenant_id = :t and employment_id = :e and work_date between :f and :l
                         order by work_date, created_at""")
                .param("t", tenant()).param("e", employmentId).param("f", month.atDay(1))
                .param("l", month.atEndOfMonth()).query(MenstrualBreakStore::row).list();
    }

    public Optional<DayRow> active(UUID employmentId, LocalDate date) {
        return db.jdbc().sql("""
                        select id, employment_id, work_date, source, status, created_at, withdrawn_at
                          from att.menstrual_break_day
                         where tenant_id = :t and employment_id = :e and work_date = :d and status = 'ACTIVE'""")
                .param("t", tenant()).param("e", employmentId).param("d", date)
                .query(MenstrualBreakStore::row).optional();
    }

    public UUID insert(UUID employmentId, LocalDate date, UUID legalEntityId, UUID orgUnitId, String source) {
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into att.menstrual_break_day (tenant_id, id, employment_id, work_date, legal_entity_id,
                               org_unit_id, source, created_by, updated_by)
                        values (:t, :id, :e, :d, :le, :ou, :src, :by, :by)""")
                .param("t", tenant()).param("id", id).param("e", employmentId).param("d", date)
                .param("le", legalEntityId).param("ou", orgUnitId).param("src", source).param("by", actor())
                .update();
        return id;
    }

    public boolean withdraw(UUID id) {
        return db.jdbc().sql("""
                        update att.menstrual_break_day
                           set status = 'WITHDRAWN', withdrawn_at = now(), withdrawn_by = :by, updated_by = :by,
                               version = version + 1
                         where tenant_id = :t and id = :id and status = 'ACTIVE'""")
                .param("t", tenant()).param("id", id).param("by", actor()).update() == 1;
    }
}
