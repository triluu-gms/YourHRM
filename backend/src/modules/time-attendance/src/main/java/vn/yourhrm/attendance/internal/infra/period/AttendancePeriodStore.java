package vn.yourhrm.attendance.internal.infra.period;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * {@code att.attendance_period} (BR-ATT-072/073): reads and the state changes. The state machine itself is enforced by
 * {@code att.tg_attendance_period_guard}; this store only issues the updates. Runs inside the caller's T&amp;A
 * transaction.
 */
@Component
public class AttendancePeriodStore {

    /**
     * {@code periodEnd} inclusive; {@code stdDayMethod} / {@code stdDayValue} as copied from the pay calendar
     * (null method = not published yet).
     */
    public record PeriodRow(UUID id, UUID legalEntityId, UUID payGroupId, LocalDate periodStart, LocalDate periodEnd,
            String stdDayMethod, BigDecimal stdDayValue, String status) {
    }

    /** The whole period row. */
    public record Period(UUID id, UUID legalEntityId, UUID payGroupId, UUID payPeriodId, LocalDate periodStart,
            LocalDate periodEnd, LocalDate otPeriodStart, LocalDate otPeriodEnd, String stdDayMethod,
            BigDecimal stdDayValue, LocalDate closeDeadline, String status, int currentVersion, Integer lockedVersion,
            Instant closedAt, UUID closedBy, Instant reopenedAt, String reopenReason, Instant reopenRequestedAt,
            UUID reopenRequestedBy, String reopenRequestReason, Instant lockedAt, UUID lockedByRunId, int version) {

        public boolean frozen() {
            return "CLOSED".equals(status) || "LOCKED".equals(status);
        }
    }

    private static final String COLUMNS = """
            select id, legal_entity_id, pay_group_id, pay_period_id, period_start, period_end, ot_period_start,
                   ot_period_end, std_day_method, std_day_value, close_deadline, status, current_version,
                   locked_version, closed_at, closed_by, reopened_at, reopen_reason, reopen_requested_at,
                   reopen_requested_by, reopen_request_reason, locked_at, locked_by_run_id, version
              from att.attendance_period
            """;

    private final AttDb db;

    public AttendancePeriodStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    public Optional<PeriodRow> period(UUID id) {
        return find(id).map(p -> new PeriodRow(p.id(), p.legalEntityId(), p.payGroupId(), p.periodStart(),
                p.periodEnd(), p.stdDayMethod(), p.stdDayValue(), p.status()));
    }

    public Optional<Period> find(UUID id) {
        return db.jdbc().sql(COLUMNS + " where tenant_id = :t and id = :id").param("t", tenant()).param("id", id)
                .query(this::map).optional();
    }

    /** Locks the row (serialises with hold acquisition, which takes FOR SHARE on it). */
    public Optional<Period> forUpdate(UUID id) {
        return db.jdbc().sql(COLUMNS + " where tenant_id = :t and id = :id for update").param("t", tenant())
                .param("id", id).query(this::map).optional();
    }

    public Optional<Period> byStart(UUID legalEntityId, UUID payGroupId, LocalDate periodStart) {
        return db.jdbc().sql(COLUMNS + """
                         where tenant_id = :t and legal_entity_id = :le and pay_group_id = :pg
                           and period_start = :s for update""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId).param("s", periodStart)
                .query(this::map).optional();
    }

    /** Periods of a legal entity (and pay group / status when given) overlapping {@code [from, to]}. */
    public List<Period> list(UUID legalEntityId, UUID payGroupId, String status, LocalDate from, LocalDate to) {
        return db.jdbc().sql(COLUMNS + """
                         where tenant_id = :t and (cast(:le as uuid) is null or legal_entity_id = :le)
                           and (cast(:pg as uuid) is null or pay_group_id = :pg)
                           and (cast(:st as text) is null or status = :st)
                           and (cast(:f as date) is null or period_end >= :f)
                           and (cast(:to as date) is null or period_start <= :to)
                         order by period_start, legal_entity_id, pay_group_id limit 500""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId).param("st", status)
                .param("f", from).param("to", to).query(this::map).list();
    }

    /** The first OPEN / IN_REVIEW period of the legal entity x pay group starting after {@code after}. */
    public Optional<Period> nextOpen(UUID legalEntityId, UUID payGroupId, LocalDate after) {
        return db.jdbc().sql(COLUMNS + """
                         where tenant_id = :t and legal_entity_id = :le and pay_group_id = :pg
                           and period_start > :a and status in ('OPEN', 'IN_REVIEW')
                         order by period_start limit 1""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId).param("a", after)
                .query(this::map).optional();
    }

    /** OPEN periods whose end is before {@code today} (they go to review the day after, gap §7.2). */
    public List<UUID> endedOpen(LocalDate today) {
        return db.jdbc().sql("""
                        select id from att.attendance_period
                         where tenant_id = :t and status = 'OPEN' and period_end < :d order by period_start""")
                .param("t", tenant()).param("d", today).query(UUID.class).list();
    }

    public UUID insert(UUID legalEntityId, UUID payGroupId, UUID payPeriodId, LocalDate start, LocalDate end,
            LocalDate otStart, LocalDate otEnd, String method, BigDecimal value, LocalDate closeDeadline,
            UUID sourceEventId) {
        return db.jdbc().sql("""
                        insert into att.attendance_period (tenant_id, legal_entity_id, pay_group_id, pay_period_id,
                               period_start, period_end, ot_period_start, ot_period_end, std_day_method,
                               std_day_value, close_deadline, source_event_id, created_by, updated_by)
                        values (:t, :le, :pg, :pp, :s, :e, :os, :oe, :m, :v, :cd, :src, :by, :by)
                        returning id""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId).param("pp", payPeriodId)
                .param("s", start).param("e", end).param("os", otStart).param("oe", otEnd).param("m", method)
                .param("v", value).param("cd", closeDeadline).param("src", sourceEventId).param("by", actor())
                .query(UUID.class).single();
    }

    /** Boundaries change only while OPEN without a result (guard); method / value only while not CLOSED / LOCKED. */
    public void updateCalendar(UUID id, boolean boundaries, LocalDate end, LocalDate otStart, LocalDate otEnd,
            boolean method, String stdMethod, BigDecimal stdValue, UUID payPeriodId, LocalDate closeDeadline,
            UUID sourceEventId) {
        db.jdbc().sql("""
                        update att.attendance_period
                           set period_end = case when :b then :e else period_end end,
                               ot_period_start = case when :b then :os else ot_period_start end,
                               ot_period_end = case when :b then :oe else ot_period_end end,
                               std_day_method = case when :m then :sm else std_day_method end,
                               std_day_value = case when :m then :sv else std_day_value end,
                               pay_period_id = coalesce(:pp, pay_period_id), close_deadline = :cd,
                               source_event_id = :src, updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("b", boundaries).param("e", end).param("os", otStart)
                .param("oe", otEnd).param("m", method).param("sm", stdMethod).param("sv", stdValue)
                .param("pp", payPeriodId).param("cd", closeDeadline).param("src", sourceEventId).param("by", actor())
                .update();
    }

    /**
     * Gives days computed before the period existed (period_id NULL) to the period: the close counts the days by
     * {@code period_id}. Days of another pay group are left alone.
     */
    public int attachDays(Period p) {
        return db.jdbc().sql("""
                        update att.daily_timesheet set period_id = :p
                         where tenant_id = :t and legal_entity_id = :le and work_date between :s and :e
                           and period_id is null and (pay_group_id is null or pay_group_id = :pg)""")
                .param("t", tenant()).param("p", p.id()).param("le", p.legalEntityId()).param("s", p.periodStart())
                .param("e", p.periodEnd()).param("pg", p.payGroupId()).update();
    }

    /**
     * {@code att.ext_pay_pay_group_assignment} from {@code pay.PayGroupAssigned} (ATT-C-24): keyed by Payroll's row id,
     * an older source version never overwrites a newer one. @return true when written
     */
    public boolean upsertPayGroupAssignment(UUID id, UUID employmentId, UUID payGroupId, UUID legalEntityId,
            LocalDate validFrom, LocalDate validTo, UUID sourceEventId, long sourceVersion) {
        return db.jdbc().sql("""
                        insert into att.ext_pay_pay_group_assignment as a (tenant_id, id, employment_id, pay_group_id,
                               legal_entity_id, valid_from, valid_to, source_event_id, source_version)
                        values (:t, :id, :e, :pg, :le, :f, :to, :src, :sv)
                        on conflict (tenant_id, id) do update
                           set employment_id = excluded.employment_id, pay_group_id = excluded.pay_group_id,
                               legal_entity_id = excluded.legal_entity_id, valid_from = excluded.valid_from,
                               valid_to = excluded.valid_to, source_event_id = excluded.source_event_id,
                               source_version = excluded.source_version, synced_at = now()
                         where a.source_version < excluded.source_version""")
                .param("t", tenant()).param("id", id).param("e", employmentId).param("pg", payGroupId)
                .param("le", legalEntityId).param("f", validFrom).param("to", validTo).param("src", sourceEventId)
                .param("sv", sourceVersion).update() == 1;
    }

    public void toStatus(UUID id, String status) {
        db.jdbc().sql("""
                        update att.attendance_period set status = :st, updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("st", status).param("by", actor()).update();
    }

    public void close(UUID id, int newVersion) {
        db.jdbc().sql("""
                        update att.attendance_period
                           set status = 'CLOSED', current_version = :v, closed_at = now(), closed_by = :by,
                               reopen_requested_at = null, reopen_requested_by = null, reopen_request_reason = null,
                               updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("v", newVersion).param("by", actor()).update();
    }

    public void reopen(UUID id, String reason, UUID by) {
        db.jdbc().sql("""
                        update att.attendance_period
                           set status = 'OPEN', reopened_at = now(), reopened_by = :by, reopen_reason = :r,
                               reopen_requested_at = null, reopen_requested_by = null, reopen_request_reason = null,
                               updated_by = :u, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("r", reason).param("by", by).param("u", actor())
                .update();
    }

    public void requestReopen(UUID id, String reason) {
        requestReopen(id, reason, actor());
    }

    /** A reopen asked while held, on behalf of {@code requestedBy} (the requester of an approved reopen). */
    public void requestReopen(UUID id, String reason, UUID requestedBy) {
        db.jdbc().sql("""
                        update att.attendance_period
                           set reopen_requested_at = now(), reopen_requested_by = :rby, reopen_request_reason = :r,
                               updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("r", reason).param("rby", requestedBy)
                .param("by", actor()).update();
    }

    public void lock(UUID id, int version, UUID runId) {
        db.jdbc().sql("""
                        update att.attendance_period
                           set status = 'LOCKED', locked_version = :v, locked_at = now(), locked_by_run_id = :run,
                               reopen_requested_at = null, reopen_requested_by = null, reopen_request_reason = null,
                               updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("v", version).param("run", runId).param("by", actor())
                .update();
    }

    /** Payroll unlock: LOCKED -> CLOSED, same current version (BR-ATT-088). */
    public void unlock(UUID id) {
        db.jdbc().sql("""
                        update att.attendance_period
                           set status = 'CLOSED', locked_version = null, locked_at = null, locked_by_run_id = null,
                               updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("by", actor()).update();
    }

    private Period map(ResultSet rs, int n) throws SQLException {
        return new Period(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class), rs.getString(9),
                rs.getBigDecimal(10), rs.getObject(11, LocalDate.class), rs.getString(12), rs.getInt(13),
                (Integer) rs.getObject(14), instant(rs, 15), rs.getObject(16, UUID.class), instant(rs, 17),
                rs.getString(18), instant(rs, 19), rs.getObject(20, UUID.class), rs.getString(21), instant(rs, 22),
                rs.getObject(23, UUID.class), rs.getInt(24));
    }

    static Instant instant(ResultSet rs, int i) throws SQLException {
        OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
