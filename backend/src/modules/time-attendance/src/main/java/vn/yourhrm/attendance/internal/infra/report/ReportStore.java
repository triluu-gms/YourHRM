package vn.yourhrm.attendance.internal.infra.report;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid.CodeQty;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid.Day;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.Columns;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Read-only SQL of the T&amp;A reports (BRD 03 §8, R-01..R-09). Every query filters by the caller's data scope
 * ({@link ScopePredicate} of the report's domain permission) and runs inside the caller's read-only T&amp;A
 * transaction. Pages use {@code limit + 1} rows so the caller can tell whether more exist.
 */
@Component
public class ReportStore {

    /** Scope placement of an employment without its own org unit column: the assignment on {@code :asg}. */
    private static final String ASSIGNMENT_AT = """
            left join lateral (select a.org_unit_id, a.location_id from att.ext_hr_assignment a
                                where a.tenant_id = %1$s.tenant_id and a.employment_id = %1$s.employment_id
                                  and a.valid_from <= cast(:asg as date)
                                order by (a.valid_to is null or a.valid_to > cast(:asg as date)) desc,
                                         (a.assignment_type = 'PRIMARY') desc, a.valid_from desc
                                limit 1) a on true
            """;

    public record ExceptionRow(UUID employmentId, LocalDate workDate, UUID legalEntityId, UUID orgUnitId,
            String dayStatus, String primaryCode, Instant firstInAt, Instant lastOutAt, BigDecimal scheduledHours,
            BigDecimal workedHours, int lateMin, int earlyMin, BigDecimal absentHours, List<String> exceptions,
            boolean blocking) {
    }

    public record PunchLogRow(UUID id, LocalDate workDateHint, Instant punchAt, LocalDateTime localTs, String tzName,
            String source, UUID deviceId, String deviceCode, String deviceName, String terminalCode, String enrolId,
            String direction, String verifyMethod, UUID importJobId, Integer importRowNo, Instant receivedAt,
            UUID employmentId, UUID legalEntityId, UUID orgUnitId, String validationStatus, Instant matchedAt) {
    }

    public record Placed(UUID employmentId, UUID legalEntityId, UUID orgUnitId) {
    }

    public record OtLedger(UUID employmentId, String periodKind, String regimeCode, int capMin, int openingMin,
            int approvedMin, int pendingMin, int paidMin, int overrideMin, String regimeNoticeRef) {
    }

    public record OverrideRow(UUID requestId, UUID employmentId, UUID legalEntityId, UUID orgUnitId,
            LocalDate workDate, int plannedMinutes, String status, String reason, String overrideReason,
            UUID overrideBy, UUID decidedBy, Instant decidedAt, UUID createdBy, Instant createdAt,
            boolean protectedWorker, String consentMethod, OtLedger month, OtLedger year) {
    }

    /** Ledger sums of one employment × leave type × leave year up to the report date (R-06). */
    public record BalanceSums(UUID employmentId, UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId,
            String leaveTypeCode, String leaveTypeNameVi, String leaveTypeNameEn, int leaveYear,
            Map<String, BigDecimal> sums, BigDecimal carryIn, BigDecimal carryOut) {
    }

    public record TakenRow(LocalDate month, UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId,
            String leaveTypeCode, String leaveTypeNameVi, String leaveTypeNameEn, String leaveCategory, String paidBy,
            long requests, long employees, BigDecimal days, BigDecimal hours) {
    }

    /** One SI-paid leave of the register (R-07). P3 values only as their last 4 characters; nothing is decrypted. */
    public record SiRow(UUID requestId, UUID employmentId, UUID legalEntityId, UUID orgUnitId, String leaveTypeCode,
            String leaveTypeNameVi, String leaveTypeNameEn, String leaveCategory, LocalDate startDate,
            LocalDate endDate, LocalDate actualReturnDate, BigDecimal durationDays, String certificateType,
            String certificateNoLast4, LocalDate certificateDate, String treatmentType, Boolean longTermIllness,
            LocalDate childBirthDate, String childIdLast4, LocalDate confinementDate, Integer childrenBornCount,
            Boolean caesarean, boolean claimDataRecorded) {
    }

    public record RetroRow(UUID lineId, UUID employmentId, UUID legalEntityId, UUID orgUnitId, String categoryCode,
            BigDecimal quantity, String unit, LocalDate segmentFrom, LocalDate segmentTo, UUID originalPeriodId,
            LocalDate originalPeriodStart, LocalDate originalPeriodEnd, UUID originalResultId,
            Integer originalResultVersion, LocalDate originalSegmentFrom, String retroReason, UUID resultId,
            int resultVersion, String resultStatus, UUID carryingPeriodId, LocalDate carryingPeriodStart,
            LocalDate carryingPeriodEnd, Instant createdAt) {
    }

    public record RetroTotal(UUID originalPeriodId, LocalDate originalPeriodStart, LocalDate originalPeriodEnd,
            UUID legalEntityId, UUID payGroupId, String categoryCode, String unit, BigDecimal quantity, long lines,
            long employments) {
    }

    private final AttDb db;
    private final JsonMapper json;

    public ReportStore(AttDb db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Boolean boolOrNull(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    private static List<String> textArray(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    // ------------------------------------------------------------------------------------- R-01 timesheet grid

    /** One page of the employments having days in the range (grid rows), ordered by employment id. */
    public List<UUID> gridEmployments(UUID legalEntityId, UUID orgUnitId, LocalDate from, LocalDate to,
            ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(dayColumns(), "sc");
        return db.jdbc().sql("""
                        select d.employment_id from att.daily_timesheet d
                         where d.tenant_id = :t and d.work_date between :f and :to
                           and (cast(:le as uuid) is null or d.legal_entity_id = :le)
                           and (cast(:ou as uuid) is null or d.org_unit_id = :ou)
                        """ + " and " + f.sql() + """
                         group by d.employment_id order by d.employment_id limit :lim offset :off""")
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .param("ou", orgUnitId).params(f.params()).param("lim", limit).param("off", offset)
                .query(UUID.class).list();
    }

    /** The days of the employments in the range, limited to the legal entity / org unit filter and the scope. */
    public List<Day> gridDays(List<UUID> employmentIds, UUID legalEntityId, UUID orgUnitId, LocalDate from,
            LocalDate to, ScopePredicate scope) {
        if (employmentIds.isEmpty()) {
            return List.of();
        }
        SqlFragment f = scope.toSql(dayColumns(), "sc");
        return db.jdbc().sql("""
                        select d.employment_id, d.work_date, d.org_unit_id, d.day_status, d.primary_code,
                               d.codes::text as codes, d.worked_hours, d.late_min, d.early_min, d.night_hours,
                               d.exceptions
                          from att.daily_timesheet d
                         where d.tenant_id = :t and d.employment_id in (:ids) and d.work_date between :f and :to
                           and (cast(:le as uuid) is null or d.legal_entity_id = :le)
                           and (cast(:ou as uuid) is null or d.org_unit_id = :ou)
                        """ + " and " + f.sql() + " order by d.employment_id, d.work_date")
                .param("t", tenant()).param("ids", employmentIds).param("f", from).param("to", to)
                .param("le", legalEntityId).param("ou", orgUnitId).params(f.params())
                .query((rs, n) -> new Day(rs.getObject("employment_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class), rs.getObject("org_unit_id", UUID.class),
                        rs.getString("day_status"), rs.getString("primary_code"), codes(rs.getString("codes")),
                        rs.getBigDecimal("worked_hours"), rs.getInt("late_min"), rs.getInt("early_min"),
                        rs.getBigDecimal("night_hours"), textArray(rs, "exceptions")))
                .list();
    }

    private List<CodeQty> codes(String text) {
        return text == null ? List.of() : json.readValue(text, new TypeReference<List<CodeQty>>() {
        });
    }

    /** Paid overtime minutes per employment in the range ({@code att.overtime_result}). */
    public Map<UUID, Long> paidOvertimeMinutes(List<UUID> employmentIds, LocalDate from, LocalDate to) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        if (employmentIds.isEmpty()) {
            return out;
        }
        db.jdbc().sql("""
                        select employment_id, sum(paid_minutes) from att.overtime_result
                         where tenant_id = :t and employment_id in (:ids) and work_date between :f and :to
                         group by employment_id""")
                .param("t", tenant()).param("ids", employmentIds).param("f", from).param("to", to)
                .query((rs, n) -> out.put(rs.getObject(1, UUID.class), rs.getLong(2))).list();
        return out;
    }

    private static Columns dayColumns() {
        return new Columns("d.legal_entity_id", "d.org_unit_id", "d.location_id", "d.employment_id");
    }

    // --------------------------------------------------------------------------------- R-02 daily exceptions

    private static final String EXCEPTION_FILTER = """
             where d.tenant_id = :t and d.work_date between :f and :to
               and (cast(:le as uuid) is null or d.legal_entity_id = :le)
               and (cast(:ou as uuid) is null or d.org_unit_id = :ou)
               and (cast(:e as uuid) is null or d.employment_id = :e)
               and (d.exceptions && array['LATE','EARLY','MISSING_IN','MISSING_OUT','ABSENT_NO_CODE']::text[]
                    or d.day_status = 'ABSENT')
               and (cast(:ty as text) is null
                    or (cast(:ty as text) = 'LATE' and 'LATE' = any(d.exceptions))
                    or (cast(:ty as text) = 'EARLY' and 'EARLY' = any(d.exceptions))
                    or (cast(:ty as text) = 'MISSING_PUNCH' and d.exceptions && array['MISSING_IN','MISSING_OUT']::text[])
                    or (cast(:ty as text) = 'ABSENT' and (d.day_status = 'ABSENT' or 'ABSENT_NO_CODE' = any(d.exceptions))))
            """;

    public List<ExceptionRow> exceptions(UUID legalEntityId, UUID orgUnitId, UUID employmentId, LocalDate from,
            LocalDate to, String type, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(dayColumns(), "sc");
        return db.jdbc().sql("""
                        select d.employment_id, d.work_date, d.legal_entity_id, d.org_unit_id, d.day_status,
                               d.primary_code, d.first_in_at, d.last_out_at, d.scheduled_hours, d.worked_hours,
                               d.late_min, d.early_min, d.absent_hours, d.exceptions, d.has_blocking_exception
                          from att.daily_timesheet d
                        """ + EXCEPTION_FILTER + " and " + f.sql()
                        + " order by d.work_date, d.employment_id limit :lim offset :off")
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .param("ou", orgUnitId).param("e", employmentId).param("ty", type).params(f.params())
                .param("lim", limit).param("off", offset)
                .query((rs, n) -> new ExceptionRow(rs.getObject("employment_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class), rs.getObject("legal_entity_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class), rs.getString("day_status"),
                        rs.getString("primary_code"), instant(rs, "first_in_at"), instant(rs, "last_out_at"),
                        rs.getBigDecimal("scheduled_hours"), rs.getBigDecimal("worked_hours"), rs.getInt("late_min"),
                        rs.getInt("early_min"), rs.getBigDecimal("absent_hours"), textArray(rs, "exceptions"),
                        rs.getBoolean("has_blocking_exception")))
                .list();
    }

    /** Days per exception type over the whole filter (not only the page): LATE, EARLY, MISSING_PUNCH, ABSENT. */
    public Map<String, Long> exceptionCounts(UUID legalEntityId, UUID orgUnitId, UUID employmentId, LocalDate from,
            LocalDate to, ScopePredicate scope) {
        SqlFragment f = scope.toSql(dayColumns(), "sc");
        return db.jdbc().sql("""
                        select count(*) filter (where 'LATE' = any(d.exceptions)) as late,
                               count(*) filter (where 'EARLY' = any(d.exceptions)) as early,
                               count(*) filter (where d.exceptions && array['MISSING_IN','MISSING_OUT']::text[]) as missing,
                               count(*) filter (where d.day_status = 'ABSENT' or 'ABSENT_NO_CODE' = any(d.exceptions)) as absent,
                               count(*) as total
                          from att.daily_timesheet d
                        """ + EXCEPTION_FILTER + " and " + f.sql())
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .param("ou", orgUnitId).param("e", employmentId).param("ty", null).params(f.params())
                .query((rs, n) -> {
                    Map<String, Long> m = new LinkedHashMap<>();
                    m.put("LATE", rs.getLong("late"));
                    m.put("EARLY", rs.getLong("early"));
                    m.put("MISSING_PUNCH", rs.getLong("missing"));
                    m.put("ABSENT", rs.getLong("absent"));
                    m.put("TOTAL_DAYS", rs.getLong("total"));
                    return m;
                }).single();
    }

    // ------------------------------------------------------------------------------------- R-03 raw punch log

    public List<PunchLogRow> punches(UUID legalEntityId, UUID orgUnitId, UUID deviceId, UUID employmentId,
            String status, LocalDate from, LocalDate to, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("coalesce(p.legal_entity_id, v.legal_entity_id)", "p.org_unit_id",
                "v.location_id", "p.employment_id"), "sc");
        return db.jdbc().sql("""
                        select p.id, p.work_date_hint, p.punch_at, p.local_ts, p.tz_name, p.source, p.device_id,
                               v.code as device_code, v.name as device_name, p.terminal_code, p.enrol_id, p.direction,
                               p.verify_method, p.import_job_id, p.import_row_no, p.received_at, p.employment_id,
                               p.legal_entity_id, p.org_unit_id, p.validation_status, p.matched_at
                          from att.raw_punch p
                          left join att.device v on v.tenant_id = p.tenant_id and v.id = p.device_id
                         where p.tenant_id = :t and p.work_date_hint between :f and :to
                           and (cast(:le as uuid) is null or coalesce(p.legal_entity_id, v.legal_entity_id) = :le)
                           and (cast(:ou as uuid) is null or p.org_unit_id = :ou)
                           and (cast(:dev as uuid) is null or p.device_id = :dev)
                           and (cast(:e as uuid) is null or p.employment_id = :e)
                           and (cast(:st as text) is null or p.validation_status = :st
                                or (cast(:st as text) = 'QUARANTINED' and p.validation_status like 'QUARANTINED%'))
                        """ + " and " + f.sql() + " order by p.punch_at, p.id limit :lim offset :off")
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .param("ou", orgUnitId).param("dev", deviceId).param("e", employmentId).param("st", status)
                .params(f.params()).param("lim", limit).param("off", offset)
                .query((rs, n) -> new PunchLogRow(rs.getObject("id", UUID.class),
                        rs.getObject("work_date_hint", LocalDate.class), instant(rs, "punch_at"),
                        rs.getObject("local_ts", LocalDateTime.class), rs.getString("tz_name"),
                        rs.getString("source"), rs.getObject("device_id", UUID.class), rs.getString("device_code"),
                        rs.getString("device_name"), rs.getString("terminal_code"), rs.getString("enrol_id"),
                        rs.getString("direction"), rs.getString("verify_method"),
                        rs.getObject("import_job_id", UUID.class), intOrNull(rs, "import_row_no"),
                        instant(rs, "received_at"), rs.getObject("employment_id", UUID.class),
                        rs.getObject("legal_entity_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                        rs.getString("validation_status"), instant(rs, "matched_at")))
                .list();
    }

    // ------------------------------------------------------------------------------- R-04 / R-05 overtime

    /** Employments with a cap ledger row of the month or of the year (page), placed on {@code placeOn}. */
    public List<Placed> overtimeEmployments(UUID legalEntityId, UUID orgUnitId, LocalDate monthStart,
            LocalDate yearStart, LocalDate placeOn, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("l.legal_entity_id", "a.org_unit_id", "a.location_id",
                "l.employment_id"), "sc");
        return db.jdbc().sql("""
                        select l.employment_id, l.legal_entity_id, a.org_unit_id
                          from (select distinct tenant_id, employment_id, legal_entity_id from att.ot_cap_ledger
                                 where tenant_id = :t
                                   and ((period_kind = 'MONTH' and period_start = :ms)
                                        or (period_kind = 'YEAR' and period_start = :ys))
                                   and (cast(:le as uuid) is null or legal_entity_id = :le)) l
                        """ + ASSIGNMENT_AT.formatted("l") + """
                         where (cast(:ou as uuid) is null or a.org_unit_id = :ou)
                        """ + " and " + f.sql() + " order by l.employment_id limit :lim offset :off")
                .param("t", tenant()).param("ms", monthStart).param("ys", yearStart).param("le", legalEntityId)
                .param("asg", placeOn).param("ou", orgUnitId).params(f.params()).param("lim", limit)
                .param("off", offset)
                .query((rs, n) -> new Placed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)))
                .list();
    }

    private static final String LEDGER_COLUMNS = """
            employment_id, period_kind, regime_code, cap_min, opening_min, approved_min, pending_min, paid_min,
            override_min, regime_notice_ref""";

    private static OtLedger ledger(ResultSet rs, String prefix) throws SQLException {
        UUID e = rs.getObject(prefix + "employment_id", UUID.class);
        if (e == null) {
            return null;
        }
        return new OtLedger(e, rs.getString(prefix + "period_kind"), rs.getString(prefix + "regime_code"),
                rs.getInt(prefix + "cap_min"), rs.getInt(prefix + "opening_min"), rs.getInt(prefix + "approved_min"),
                rs.getInt(prefix + "pending_min"), rs.getInt(prefix + "paid_min"), rs.getInt(prefix + "override_min"),
                rs.getString(prefix + "regime_notice_ref"));
    }

    /** The MONTH and YEAR cap ledger rows of the employments. */
    public List<OtLedger> overtimeLedger(List<UUID> employmentIds, LocalDate monthStart, LocalDate yearStart) {
        if (employmentIds.isEmpty()) {
            return List.of();
        }
        return db.jdbc().sql("select " + LEDGER_COLUMNS + """
                          from att.ot_cap_ledger
                         where tenant_id = :t and employment_id in (:ids)
                           and ((period_kind = 'MONTH' and period_start = :ms)
                                or (period_kind = 'YEAR' and period_start = :ys))""")
                .param("t", tenant()).param("ids", employmentIds).param("ms", monthStart).param("ys", yearStart)
                .query((rs, n) -> ledger(rs, "")).list();
    }

    /** Paid overtime minutes per employment and category in the range. */
    public Map<UUID, Map<String, Long>> overtimeByCategory(List<UUID> employmentIds, LocalDate from, LocalDate to) {
        Map<UUID, Map<String, Long>> out = new LinkedHashMap<>();
        if (employmentIds.isEmpty()) {
            return out;
        }
        db.jdbc().sql("""
                        select employment_id, ot_category, sum(paid_minutes) from att.overtime_result
                         where tenant_id = :t and employment_id in (:ids) and work_date between :f and :to
                         group by employment_id, ot_category""")
                .param("t", tenant()).param("ids", employmentIds).param("f", from).param("to", to)
                .query((rs, n) -> out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                        .put(rs.getString(2), rs.getLong(3)))
                .list();
        return out;
    }

    /** Overtime requests submitted above the cap with an authorised override (BR-ATT-046), with the cap usage. */
    public List<OverrideRow> capOverrides(UUID legalEntityId, UUID orgUnitId, UUID employmentId, LocalDate from,
            LocalDate to, String status, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("r.legal_entity_id", "r.org_unit_id", null, "r.employment_id"),
                "sc");
        return db.jdbc().sql("""
                        select r.id, r.employment_id, r.legal_entity_id, r.org_unit_id, r.work_date, r.planned_minutes,
                               r.status, r.reason, r.cap_override_reason, r.cap_override_by, r.decided_by,
                               r.decided_at, r.created_by, r.created_at, r.protected_worker_flag, r.consent_method,
                               m.employment_id as m_employment_id, m.period_kind as m_period_kind,
                               m.regime_code as m_regime_code, m.cap_min as m_cap_min, m.opening_min as m_opening_min,
                               m.approved_min as m_approved_min, m.pending_min as m_pending_min,
                               m.paid_min as m_paid_min, m.override_min as m_override_min,
                               m.regime_notice_ref as m_regime_notice_ref,
                               y.employment_id as y_employment_id, y.period_kind as y_period_kind,
                               y.regime_code as y_regime_code, y.cap_min as y_cap_min, y.opening_min as y_opening_min,
                               y.approved_min as y_approved_min, y.pending_min as y_pending_min,
                               y.paid_min as y_paid_min, y.override_min as y_override_min,
                               y.regime_notice_ref as y_regime_notice_ref
                          from att.overtime_request r
                          left join att.ot_cap_ledger m on m.tenant_id = r.tenant_id and m.employment_id = r.employment_id
                               and m.period_kind = 'MONTH' and m.period_start = cast(date_trunc('month', r.work_date) as date)
                          left join att.ot_cap_ledger y on y.tenant_id = r.tenant_id and y.employment_id = r.employment_id
                               and y.period_kind = 'YEAR' and y.period_start = cast(date_trunc('year', r.work_date) as date)
                         where r.tenant_id = :t and r.cap_override and r.work_date between :f and :to
                           and r.status <> 'DRAFT'
                           and (cast(:st as text) is null or r.status = :st)
                           and (cast(:le as uuid) is null or r.legal_entity_id = :le)
                           and (cast(:ou as uuid) is null or r.org_unit_id = :ou)
                           and (cast(:e as uuid) is null or r.employment_id = :e)
                        """ + " and " + f.sql() + " order by r.work_date, r.employment_id, r.id limit :lim offset :off")
                .param("t", tenant()).param("f", from).param("to", to).param("st", status).param("le", legalEntityId)
                .param("ou", orgUnitId).param("e", employmentId).params(f.params()).param("lim", limit)
                .param("off", offset)
                .query((rs, n) -> new OverrideRow(rs.getObject("id", UUID.class),
                        rs.getObject("employment_id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class), rs.getObject("work_date", LocalDate.class),
                        rs.getInt("planned_minutes"), rs.getString("status"), rs.getString("reason"),
                        rs.getString("cap_override_reason"), rs.getObject("cap_override_by", UUID.class),
                        rs.getObject("decided_by", UUID.class), instant(rs, "decided_at"),
                        rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                        rs.getBoolean("protected_worker_flag"), rs.getString("consent_method"), ledger(rs, "m_"),
                        ledger(rs, "y_")))
                .list();
    }

    // ---------------------------------------------------------------------------------- R-06 / R-07 leave

    /**
     * Balance-tracked leave of the employments employed on {@code asOf}: per employment × type, the leave year that
     * contains {@code asOf} (start month of the policy in force, else January) and the ledger sums up to that date.
     * Pairs without any ledger row are left out.
     */
    public List<BalanceSums> leaveBalances(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, UUID employmentId,
            LocalDate asOf, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("e.legal_entity_id", "a.org_unit_id", "a.location_id",
                "e.employment_id"), "sc");
        return db.jdbc().sql("""
                        with pairs as (
                          select e.tenant_id, e.employment_id, e.legal_entity_id, a.org_unit_id, lt.id as leave_type_id,
                                 lt.code as leave_type_code, lt.name_vi, lt.name_en,
                                 cast(case when extract(month from cast(:d as date)) >= coalesce(p.leave_year_start_month, 1)
                                           then extract(year from cast(:d as date))
                                           else extract(year from cast(:d as date)) - 1 end as int) as leave_year
                            from att.ext_hr_employment e
                            join att.leave_type lt on lt.tenant_id = e.tenant_id and lt.tracks_balance
                                 and lt.status = 'ACTIVE' and (cast(:lt as uuid) is null or lt.id = :lt)
                            left join lateral (select lp.leave_year_start_month from att.leave_policy lp
                                                where lp.tenant_id = e.tenant_id and lp.leave_type_id = lt.id
                                                  and lp.status = 'ACTIVE' and lp.valid_during @> cast(:d as date)
                                                  and (lp.legal_entity_id is null or lp.legal_entity_id = e.legal_entity_id)
                                                  and (lp.employee_category_code is null
                                                       or lp.employee_category_code = e.employee_category_code)
                                                order by (lp.legal_entity_id is not null) desc,
                                                         (lp.employee_category_code is not null) desc
                                                limit 1) p on true
                        """ + ASSIGNMENT_AT.formatted("e") + """
                           where e.tenant_id = :t and e.status not in ('PRE_HIRE', 'CANCELLED')
                             and e.hire_date <= cast(:d as date)
                             and (e.termination_date is null or e.termination_date >= cast(:d as date))
                             and (cast(:le as uuid) is null or e.legal_entity_id = :le)
                             and (cast(:ou as uuid) is null or a.org_unit_id = :ou)
                             and (cast(:e as uuid) is null or e.employment_id = :e)
                        """ + " and " + f.sql() + """
                        ), paged as (
                          select * from pairs pr
                           where exists (select 1 from att.leave_balance_ledger l
                                          where l.tenant_id = pr.tenant_id and l.employment_id = pr.employment_id
                                            and l.leave_type_id = pr.leave_type_id and l.leave_year = pr.leave_year
                                            and l.txn_date <= cast(:d as date))
                           order by pr.employment_id, pr.leave_type_code
                           limit :lim offset :off)
                        select pg.employment_id, pg.legal_entity_id, pg.org_unit_id, pg.leave_type_id,
                               pg.leave_type_code, pg.name_vi, pg.name_en, pg.leave_year, s.*
                          from paged pg
                          cross join lateral (
                            select sum(quantity) filter (where txn_type = 'OPENING') as s_opening,
                                   sum(quantity) filter (where txn_type = 'ACCRUE') as s_accrue,
                                   sum(quantity) filter (where txn_type = 'TAKE') as s_take,
                                   sum(quantity) filter (where txn_type = 'CANCEL') as s_cancel,
                                   sum(quantity) filter (where txn_type = 'ADJUST') as s_adjust,
                                   sum(quantity) filter (where txn_type = 'EXPIRE') as s_expire,
                                   sum(quantity) filter (where txn_type = 'ENCASH') as s_encash,
                                   sum(quantity) filter (where txn_type = 'CARRY' and quantity > 0) as s_carry_in,
                                   sum(quantity) filter (where txn_type = 'CARRY' and quantity < 0) as s_carry_out
                              from att.leave_balance_ledger l
                             where l.tenant_id = pg.tenant_id and l.employment_id = pg.employment_id
                               and l.leave_type_id = pg.leave_type_id and l.leave_year = pg.leave_year
                               and l.txn_date <= cast(:d as date)) s
                         order by pg.employment_id, pg.leave_type_code""")
                .param("t", tenant()).param("d", asOf).param("asg", asOf).param("lt", leaveTypeId)
                .param("le", legalEntityId).param("ou", orgUnitId).param("e", employmentId).params(f.params())
                .param("lim", limit).param("off", offset)
                .query((rs, n) -> {
                    Map<String, BigDecimal> sums = new LinkedHashMap<>();
                    for (String k : List.of("OPENING", "ACCRUE", "TAKE", "CANCEL", "ADJUST", "EXPIRE", "ENCASH")) {
                        BigDecimal v = rs.getBigDecimal("s_" + k.toLowerCase(java.util.Locale.ROOT));
                        if (v != null) {
                            sums.put(k, v);
                        }
                    }
                    return new BalanceSums(rs.getObject("employment_id", UUID.class),
                            rs.getObject("legal_entity_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                            rs.getObject("leave_type_id", UUID.class), rs.getString("leave_type_code"),
                            rs.getString("name_vi"), rs.getString("name_en"), rs.getInt("leave_year"), sums,
                            rs.getBigDecimal("s_carry_in"), rs.getBigDecimal("s_carry_out"));
                }).list();
    }

    /** Approved leave by month of its start date × legal entity × org unit × type (R-07). */
    public List<TakenRow> leaveTaken(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, LocalDate from,
            LocalDate to, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("r.legal_entity_id", "r.org_unit_id", null, "r.employment_id"),
                "sc");
        return db.jdbc().sql("""
                        select cast(date_trunc('month', r.start_date) as date) as month, r.legal_entity_id,
                               r.org_unit_id, lt.id as leave_type_id, lt.code, lt.name_vi, lt.name_en,
                               lt.leave_category, lt.paid_by, count(*) as requests,
                               count(distinct r.employment_id) as employees, sum(r.duration_days) as days,
                               sum(r.duration_hours) as hours
                          from att.leave_request r
                          join att.leave_type lt on lt.tenant_id = r.tenant_id and lt.id = r.leave_type_id
                         where r.tenant_id = :t and r.status = 'APPROVED' and r.start_date between :f and :to
                           and (cast(:le as uuid) is null or r.legal_entity_id = :le)
                           and (cast(:ou as uuid) is null or r.org_unit_id = :ou)
                           and (cast(:lt as uuid) is null or r.leave_type_id = :lt)
                        """ + " and " + f.sql() + """
                         group by 1, r.legal_entity_id, r.org_unit_id, lt.id, lt.code, lt.name_vi, lt.name_en,
                                  lt.leave_category, lt.paid_by
                         order by 1, r.legal_entity_id, r.org_unit_id, lt.code limit :lim offset :off""")
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .param("ou", orgUnitId).param("lt", leaveTypeId).params(f.params()).param("lim", limit)
                .param("off", offset)
                .query((rs, n) -> new TakenRow(rs.getObject("month", LocalDate.class),
                        rs.getObject("legal_entity_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                        rs.getObject("leave_type_id", UUID.class), rs.getString("code"), rs.getString("name_vi"),
                        rs.getString("name_en"), rs.getString("leave_category"), rs.getString("paid_by"),
                        rs.getLong("requests"), rs.getLong("employees"), rs.getBigDecimal("days"),
                        rs.getBigDecimal("hours")))
                .list();
    }

    /** Approved SI-paid leave overlapping the range, with the (masked) SI claim data (R-07 register). */
    public List<SiRow> siRegister(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, LocalDate from, LocalDate to,
            ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("r.legal_entity_id", "r.org_unit_id", null, "r.employment_id"),
                "sc");
        return db.jdbc().sql("""
                        select r.id, r.employment_id, r.legal_entity_id, r.org_unit_id, lt.code, lt.name_vi,
                               lt.name_en, lt.leave_category, r.start_date, r.end_date, r.actual_return_date,
                               r.duration_days, r.si_certificate_type, r.si_certificate_no_last4,
                               r.si_certificate_date, r.si_treatment_type, r.si_is_long_term_illness,
                               r.child_birth_date, r.child_id_last4, r.confinement_date, r.children_born_count,
                               r.is_caesarean, (r.si_certificate_type is not null) as recorded
                          from att.leave_request r
                          join att.leave_type lt on lt.tenant_id = r.tenant_id and lt.id = r.leave_type_id
                         where r.tenant_id = :t and r.status = 'APPROVED'
                           and (lt.paid_by = 'SI_FUND' or lt.requires_si_claim_data)
                           and r.start_date <= :to and r.end_date >= :f
                           and (cast(:le as uuid) is null or r.legal_entity_id = :le)
                           and (cast(:ou as uuid) is null or r.org_unit_id = :ou)
                           and (cast(:lt as uuid) is null or r.leave_type_id = :lt)
                        """ + " and " + f.sql() + " order by r.start_date, r.employment_id, r.id limit :lim offset :off")
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .param("ou", orgUnitId).param("lt", leaveTypeId).params(f.params()).param("lim", limit)
                .param("off", offset)
                .query((rs, n) -> new SiRow(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                        rs.getObject("legal_entity_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                        rs.getString("code"), rs.getString("name_vi"), rs.getString("name_en"),
                        rs.getString("leave_category"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class), rs.getObject("actual_return_date", LocalDate.class),
                        rs.getBigDecimal("duration_days"), rs.getString("si_certificate_type"),
                        rs.getString("si_certificate_no_last4"), rs.getObject("si_certificate_date", LocalDate.class),
                        rs.getString("si_treatment_type"), boolOrNull(rs, "si_is_long_term_illness"),
                        rs.getObject("child_birth_date", LocalDate.class), rs.getString("child_id_last4"),
                        rs.getObject("confinement_date", LocalDate.class), intOrNull(rs, "children_born_count"),
                        boolOrNull(rs, "is_caesarean"), rs.getBoolean("recorded")))
                .list();
    }

    // ------------------------------------------------------------------------------------ R-09 retro lines

    private static final String RETRO_FROM = """
              from att.attendance_result_line l
              join att.attendance_result r on r.tenant_id = l.tenant_id and r.id = l.result_id
              join att.attendance_period cp on cp.tenant_id = r.tenant_id and cp.id = r.period_id
              join att.attendance_period op on op.tenant_id = l.tenant_id and op.id = l.original_period_id
              left join att.attendance_result ores on ores.tenant_id = l.tenant_id and ores.id = l.original_result_id
             where l.tenant_id = :t and l.is_retro
               and (cast(:le as uuid) is null or op.legal_entity_id = :le)
               and (cast(:pg as uuid) is null or op.pay_group_id = :pg)
               and (cast(:op as uuid) is null or l.original_period_id = :op)
               and (cast(:f as date) is null or op.period_start >= cast(:f as date))
               and (cast(:to as date) is null or op.period_start <= cast(:to as date))
               and (cast(:e as uuid) is null or l.employment_id = :e)
               and (:wd or r.status <> 'WITHDRAWN')
            """;

    private static Columns retroColumns() {
        return new Columns("l.legal_entity_id", "l.org_unit_id", null, "l.employment_id");
    }

    public List<RetroRow> retroLines(UUID legalEntityId, UUID payGroupId, UUID originalPeriodId, LocalDate from,
            LocalDate to, UUID employmentId, boolean includeWithdrawn, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(retroColumns(), "sc");
        return db.jdbc().sql("""
                        select l.id, l.employment_id, l.legal_entity_id, l.org_unit_id, l.category_code, l.quantity,
                               l.unit, l.segment_from, l.segment_to, l.original_period_id,
                               op.period_start as op_start, op.period_end as op_end, l.original_result_id,
                               ores.result_version as orig_version, l.original_segment_from, l.retro_reason,
                               l.result_id, r.result_version, r.status, r.period_id, cp.period_start as cp_start,
                               cp.period_end as cp_end, l.created_at
                        """ + RETRO_FROM + " and " + f.sql() + """
                         order by op.period_start, l.original_period_id, l.employment_id, l.category_code,
                                  r.result_version, l.id
                         limit :lim offset :off""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId)
                .param("op", originalPeriodId).param("f", from).param("to", to).param("e", employmentId)
                .param("wd", includeWithdrawn).params(f.params()).param("lim", limit).param("off", offset)
                .query((rs, n) -> new RetroRow(rs.getObject("id", UUID.class),
                        rs.getObject("employment_id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class), rs.getString("category_code"),
                        rs.getBigDecimal("quantity"), rs.getString("unit"),
                        rs.getObject("segment_from", LocalDate.class), rs.getObject("segment_to", LocalDate.class),
                        rs.getObject("original_period_id", UUID.class), rs.getObject("op_start", LocalDate.class),
                        rs.getObject("op_end", LocalDate.class), rs.getObject("original_result_id", UUID.class),
                        intOrNull(rs, "orig_version"), rs.getObject("original_segment_from", LocalDate.class),
                        rs.getString("retro_reason"), rs.getObject("result_id", UUID.class),
                        rs.getInt("result_version"), rs.getString("status"), rs.getObject("period_id", UUID.class),
                        rs.getObject("cp_start", LocalDate.class), rs.getObject("cp_end", LocalDate.class),
                        instant(rs, "created_at")))
                .list();
    }

    /** Σ of the retro lines per original period × category over the whole filter. */
    public List<RetroTotal> retroTotals(UUID legalEntityId, UUID payGroupId, UUID originalPeriodId, LocalDate from,
            LocalDate to, UUID employmentId, boolean includeWithdrawn, ScopePredicate scope) {
        SqlFragment f = scope.toSql(retroColumns(), "sc");
        return db.jdbc().sql("""
                        select l.original_period_id, op.period_start, op.period_end, op.legal_entity_id,
                               op.pay_group_id, l.category_code, l.unit, sum(l.quantity) as qty, count(*) as lines,
                               count(distinct l.employment_id) as employments
                        """ + RETRO_FROM + " and " + f.sql() + """
                         group by l.original_period_id, op.period_start, op.period_end, op.legal_entity_id,
                                  op.pay_group_id, l.category_code, l.unit
                         order by op.period_start, l.original_period_id, l.category_code""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId)
                .param("op", originalPeriodId).param("f", from).param("to", to).param("e", employmentId)
                .param("wd", includeWithdrawn).params(f.params())
                .query((rs, n) -> new RetroTotal(rs.getObject("original_period_id", UUID.class),
                        rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                        rs.getObject("legal_entity_id", UUID.class), rs.getObject("pay_group_id", UUID.class),
                        rs.getString("category_code"), rs.getString("unit"), rs.getBigDecimal("qty"),
                        rs.getLong("lines"), rs.getLong("employments")))
                .list();
    }

    /** Pending retro requests per LOCKED period (changes seen after the lock, not yet carried by a result). */
    public Map<UUID, Long> pendingRetroRequests(List<UUID> periodIds) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        if (periodIds.isEmpty()) {
            return out;
        }
        db.jdbc().sql("""
                        select period_id, count(*) from att.retro_request
                         where tenant_id = :t and status = 'PENDING' and period_id in (:ids) group by period_id""")
                .param("t", tenant()).param("ids", periodIds)
                .query((rs, n) -> out.put(rs.getObject(1, UUID.class), rs.getLong(2))).list();
        return out;
    }
}
