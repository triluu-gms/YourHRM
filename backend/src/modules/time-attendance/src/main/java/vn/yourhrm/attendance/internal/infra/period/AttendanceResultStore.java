package vn.yourhrm.attendance.internal.infra.period;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.yourhrm.attendance.api.AttendanceResults.Hold;
import vn.yourhrm.attendance.api.AttendanceResults.Line;
import vn.yourhrm.attendance.api.AttendanceResults.Result;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.CodeQty;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Dated;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.DayFacts;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Existing;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Overtime;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.SegmentKeys;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Attendance Result versions and their lines ({@code att.attendance_result[_line]}), close holds
 * ({@code att.attendance_result_hold}), retro requests ({@code att.retro_request}) and the inputs of a close (days,
 * paid overtime, checklist counts). Immutability, the seal and the hold rules are DB triggers; this store only issues
 * the statements. Runs inside the caller's T&amp;A transaction.
 */
@Component
public class AttendanceResultStore {

    /** Header to insert; lines follow in the same transaction (the seal stamps them at commit). */
    public record NewResult(UUID periodId, LocalDate periodStart, String scope, UUID employmentId, String kind,
            int version, LocalDate attFrom, LocalDate attTo, LocalDate otFrom, LocalDate otTo, String checksum,
            UUID legalPackId, String ruleVersion, int overrideVersion, UUID supersedesId, String cause) {
    }

    public record NewLine(UUID employmentId, UUID assignmentKey, LocalDate segmentFrom, LocalDate segmentTo,
            UUID legalEntityId, UUID orgUnitId, UUID payGroupId, UUID costCentreId, String category, String unit,
            BigDecimal quantity, BigDecimal periodQuantity, boolean precededByDayOt, UUID originalPeriodId,
            UUID originalResultId, LocalDate originalSegmentFrom, String retroReason, String sourceRefJson,
            SegmentKeys keys) {

        public NewLine {
            keys = keys == null ? SegmentKeys.NONE : keys;
        }
    }

    /** One day read for a close, with the manual-override flag and the legal pack it was computed with. */
    public record ClosingDay(UUID employmentId, DayFacts facts, boolean manualOverride, UUID legalPackId) {
    }

    /** A retro request to apply: employment x LOCKED original period. */
    public record RetroCandidate(UUID id, UUID employmentId, UUID periodId, LocalDate periodStart,
            LocalDate periodEnd, LocalDate firstDate, LocalDate lastDate) {
    }

    /** A checklist count. */
    public record Count(String code, long count) {
    }

    private static final String RESULT_COLUMNS = """
            select r.id, r.period_id, p.legal_entity_id, p.pay_group_id, p.period_start, p.period_end, r.scope,
                   r.employment_id, r.result_kind, r.result_version, r.status, r.att_date_from, r.att_date_to,
                   r.ot_date_from, r.ot_date_to, r.checksum_sha256, r.line_count, r.close_cause, r.rule_version,
                   r.published_at
              from att.attendance_result r
              join att.attendance_period p on p.tenant_id = r.tenant_id and p.id = r.period_id
            """;

    private static final String HOLD_COLUMNS = """
            select id, period_id, result_id, result_version, scope, employment_id, payroll_run_id, status, held_at,
                   release_reason, released_at, consumed_at
              from att.attendance_result_hold
            """;

    private final AttDb db;
    private final JsonMapper json;

    public AttendanceResultStore(AttDb db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    // ------------------------------------------------------------------------------------------------ results

    public Optional<Result> result(UUID id) {
        return db.jdbc().sql(RESULT_COLUMNS + " where r.tenant_id = :t and r.id = :id").param("t", tenant())
                .param("id", id).query(this::mapResult).optional();
    }

    /** The live (PUBLISHED / LOCKED) regular or early-close version of the period scope or of one employment. */
    public Optional<Result> live(UUID periodId, UUID employmentId) {
        return db.jdbc().sql(RESULT_COLUMNS + """
                         where r.tenant_id = :t and r.period_id = :p
                           and r.scope_key = coalesce(cast(:e as uuid), '00000000-0000-0000-0000-000000000000'::uuid)
                           and r.status in ('PUBLISHED', 'LOCKED') and r.result_kind <> 'OFF_CYCLE_RETRO'""")
                .param("t", tenant()).param("p", periodId).param("e", employmentId).query(this::mapResult).optional();
    }

    /** Every version of the period, both scopes, newest first. */
    public List<Result> results(UUID periodId) {
        return db.jdbc().sql(RESULT_COLUMNS + """
                         where r.tenant_id = :t and r.period_id = :p
                         order by r.scope, r.employment_id nulls first, r.result_version desc""")
                .param("t", tenant()).param("p", periodId).query(this::mapResult).list();
    }

    public int nextVersion(UUID periodId, UUID employmentId) {
        return db.jdbc().sql("""
                        select coalesce(max(result_version), 0) + 1 from att.attendance_result
                         where tenant_id = :t and period_id = :p
                           and scope_key = coalesce(cast(:e as uuid), '00000000-0000-0000-0000-000000000000'::uuid)""")
                .param("t", tenant()).param("p", periodId).param("e", employmentId).query(Integer.class).single();
    }

    /** Employments with a live early-close result in the period (left out of the period result). */
    public List<UUID> earlyClosed(UUID periodId) {
        return db.jdbc().sql("""
                        select employment_id from att.attendance_result
                         where tenant_id = :t and period_id = :p and scope = 'EMPLOYMENT'
                           and result_kind = 'EARLY_CLOSE' and status in ('PUBLISHED', 'LOCKED')""")
                .param("t", tenant()).param("p", periodId).query(UUID.class).list();
    }

    public UUID insertResult(NewResult r) {
        return insertResult(r, null);
    }

    /** {@code importJobId} non-null: a version of source IMPORT (BR-ATT-025, R2-18), else CALCULATION. */
    public UUID insertResult(NewResult r, UUID importJobId) {
        return db.jdbc().sql("""
                        insert into att.attendance_result (tenant_id, period_id, period_start, scope, employment_id,
                               result_kind, result_version, source, import_job_id, att_date_from, att_date_to,
                               ot_date_from, ot_date_to, checksum_sha256, legal_pack_id, rule_version, override_version,
                               supersedes_result_id, close_cause, published_by, created_by)
                        values (:t, :p, :ps, :sc, :e, :k, :v, :src, :job, :af, :at, :of, :ot, :cs, :lp, :rv, :ov,
                                :sup, :cause, :by, :by)
                        returning id""")
                .param("t", tenant()).param("src", importJobId == null ? "CALCULATION" : "IMPORT")
                .param("job", importJobId).param("p", r.periodId()).param("ps", r.periodStart()).param("sc", r.scope())
                .param("e", r.employmentId()).param("k", r.kind()).param("v", r.version()).param("af", r.attFrom())
                .param("at", r.attTo()).param("of", r.otFrom()).param("ot", r.otTo()).param("cs", r.checksum())
                .param("lp", r.legalPackId()).param("rv", r.ruleVersion()).param("ov", r.overrideVersion())
                .param("sup", r.supersedesId()).param("cause", r.cause()).param("by", actor())
                .query(UUID.class).single();
    }

    public void insertLine(UUID resultId, LocalDate periodStart, NewLine l) {
        db.jdbc().sql("""
                        insert into att.attendance_result_line (tenant_id, result_id, period_start, employment_id,
                               assignment_key, segment_from, segment_to, legal_entity_id, org_unit_id, pay_group_id,
                               cost_centre_id, schedule_pattern_id, employee_group_code, fte, category_code, quantity,
                               unit, period_quantity, preceded_by_day_ot, is_retro, original_period_id, original_result_id,
                               original_segment_from, retro_reason, source_ref, created_by)
                        values (:t, :r, :ps, :e, :ak, :sf, :st, :le, :ou, :pg, :cc, :sp, :eg, :fte, :cat, :q, :u,
                                :pq, :pre, :retro, :op, :or, :osf, :rr, cast(:src as jsonb), :by)""")
                .param("t", tenant()).param("r", resultId).param("ps", periodStart).param("e", l.employmentId())
                .param("ak", l.assignmentKey()).param("sf", l.segmentFrom()).param("st", l.segmentTo())
                .param("le", l.legalEntityId()).param("ou", l.orgUnitId()).param("pg", l.payGroupId())
                .param("cc", l.costCentreId()).param("sp", l.keys().schedulePatternId())
                .param("eg", l.keys().employeeGroup()).param("fte", l.keys().fte()).param("cat", l.category())
                .param("q", l.quantity())
                .param("u", l.unit()).param("pq", l.periodQuantity()).param("pre", l.precededByDayOt())
                .param("retro", l.originalPeriodId() != null).param("op", l.originalPeriodId())
                .param("or", l.originalResultId()).param("osf", l.originalSegmentFrom())
                .param("rr", l.retroReason()).param("src", l.sourceRefJson()).param("by", actor()).update();
    }

    public List<Line> lines(UUID resultId, UUID employmentId) {
        return db.jdbc().sql("""
                        select employment_id, assignment_key, segment_from, segment_to, legal_entity_id, org_unit_id,
                               pay_group_id, cost_centre_id, category_code, unit, quantity, period_quantity,
                               preceded_by_day_ot, is_retro, original_period_id, original_result_id,
                               original_segment_from, retro_reason, schedule_pattern_id, employee_group_code, fte
                          from att.attendance_result_line
                         where tenant_id = :t and result_id = :r and (cast(:e as uuid) is null or employment_id = :e)
                         order by employment_id, is_retro, segment_from, category_code, preceded_by_day_ot""")
                .param("t", tenant()).param("r", resultId).param("e", employmentId)
                .query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                        rs.getObject(8, UUID.class), rs.getString(9), rs.getString(10), rs.getBigDecimal(11),
                        rs.getBigDecimal(12), rs.getBoolean(13), rs.getBoolean(14), rs.getObject(15, UUID.class),
                        rs.getObject(16, UUID.class), rs.getObject(17, LocalDate.class), rs.getString(18),
                        rs.getObject(19, UUID.class), rs.getString(20), rs.getBigDecimal(21)))
                .list();
    }

    public void withdraw(UUID resultId, String reason) {
        db.jdbc().sql("""
                        update att.attendance_result
                           set status = 'WITHDRAWN', withdrawn_at = now(), withdrawn_by = :by, withdraw_reason = :r
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", resultId).param("r", reason).param("by", actor()).update();
    }

    public void lock(UUID resultId, UUID runId) {
        db.jdbc().sql("""
                        update att.attendance_result set status = 'LOCKED', locked_at = now(), locked_by_run_id = :run
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", resultId).param("run", runId).update();
    }

    public void unlock(UUID resultId, UUID runId) {
        db.jdbc().sql("""
                        update att.attendance_result
                           set status = 'PUBLISHED', unlocked_at = now(), unlocked_by_run_id = :run
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", resultId).param("run", runId).update();
    }

    private Result mapResult(ResultSet rs, int n) throws SQLException {
        return new Result(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                rs.getString(7), rs.getObject(8, UUID.class), rs.getString(9), rs.getInt(10), rs.getString(11),
                rs.getObject(12, LocalDate.class), rs.getObject(13, LocalDate.class),
                rs.getObject(14, LocalDate.class), rs.getObject(15, LocalDate.class), rs.getString(16),
                (Integer) rs.getObject(17), rs.getString(18), rs.getString(19),
                AttendancePeriodStore.instant(rs, 20));
    }

    // ------------------------------------------------------------------------------------------------ holds

    public UUID insertHold(UUID periodId, UUID resultId, int version, String scope, UUID employmentId, UUID runId,
            String correlationId) {
        return db.jdbc().sql("""
                        insert into att.attendance_result_hold (tenant_id, period_id, result_id, result_version,
                               scope, employment_id, payroll_run_id, correlation_id, created_by, updated_by)
                        values (:t, :p, :r, :v, :sc, :e, :run, :corr, :by, :by) returning id""")
                .param("t", tenant()).param("p", periodId).param("r", resultId).param("v", version)
                .param("sc", scope).param("e", employmentId).param("run", runId).param("corr", correlationId)
                .param("by", actor()).query(UUID.class).single();
    }

    public Optional<Hold> hold(UUID id) {
        return db.jdbc().sql(HOLD_COLUMNS + " where tenant_id = :t and id = :id").param("t", tenant())
                .param("id", id).query(this::mapHold).optional();
    }

    public Optional<Hold> activeHold(UUID periodId, UUID employmentId) {
        return db.jdbc().sql(HOLD_COLUMNS + """
                         where tenant_id = :t and period_id = :p and status = 'ACTIVE'
                           and scope_key = coalesce(cast(:e as uuid), '00000000-0000-0000-0000-000000000000'::uuid)""")
                .param("t", tenant()).param("p", periodId).param("e", employmentId).query(this::mapHold).optional();
    }

    /** Holds of a run on a period scope (any status), newest first. */
    public List<Hold> holdsOfRun(UUID periodId, UUID runId, UUID employmentId) {
        return db.jdbc().sql(HOLD_COLUMNS + """
                         where tenant_id = :t and period_id = :p and payroll_run_id = :run
                           and scope_key = coalesce(cast(:e as uuid), '00000000-0000-0000-0000-000000000000'::uuid)
                         order by held_at desc""")
                .param("t", tenant()).param("p", periodId).param("run", runId).param("e", employmentId)
                .query(this::mapHold).list();
    }

    /** Every hold of a run in {@code status}, oldest first (run status changes). */
    public List<Hold> holdsOfRun(UUID runId, String status) {
        return db.jdbc().sql(HOLD_COLUMNS + """
                         where tenant_id = :t and payroll_run_id = :run and status = :st order by held_at""")
                .param("t", tenant()).param("run", runId).param("st", status).query(this::mapHold).list();
    }

    public List<Hold> holdsOfPeriod(UUID periodId) {
        return db.jdbc().sql(HOLD_COLUMNS + " where tenant_id = :t and period_id = :p order by held_at desc")
                .param("t", tenant()).param("p", periodId).query(this::mapHold).list();
    }

    /** Every ACTIVE / CONSUMED hold of the tenant (reconciliation). */
    public List<Hold> openHolds() {
        return db.jdbc().sql(HOLD_COLUMNS + " where tenant_id = :t and status in ('ACTIVE', 'CONSUMED')")
                .param("t", tenant()).query(this::mapHold).list();
    }

    public void releaseHold(UUID holdId, String reason) {
        db.jdbc().sql("""
                        update att.attendance_result_hold
                           set status = 'RELEASED', release_reason = :r, released_at = now(), released_by = :by,
                               updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", holdId).param("r", reason).param("by", actor()).update();
    }

    public void consumeHold(UUID holdId) {
        db.jdbc().sql("""
                        update att.attendance_result_hold
                           set status = 'CONSUMED', consumed_at = now(), updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", holdId).param("by", actor()).update();
    }

    private Hold mapHold(ResultSet rs, int n) throws SQLException {
        return new Hold(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                rs.getString(8), AttendancePeriodStore.instant(rs, 9), rs.getString(10),
                AttendancePeriodStore.instant(rs, 11), AttendancePeriodStore.instant(rs, 12));
    }

    // ------------------------------------------------------------------------------------------------ close inputs

    /**
     * Days of the period ({@code daily_timesheet.period_id}) in {@code [from, to]}, of one employment or of all,
     * ordered by employment and date. The cost centre, FTE and schedule pattern are the assignment line's on that date,
     * the employee group the dated classification's (else the line's): the segment keys of BRD §7.3 ({@link #lineKeys}).
     */
    public List<ClosingDay> days(UUID periodId, UUID payGroupId, UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select d.employment_id, d.work_date, d.day_status, d.assignment_key, d.legal_entity_id,
                               d.org_unit_id, a.cost_centre_id,
                               d.codes::text, d.night_hours, d.late_min, d.early_min, d.exceptions, d.worked_hours,
                               s.shift_group_code, d.is_manual_override, d.legal_pack_id,
                               sa.pattern_id,
                               case when cl.employment_id is not null then cl.employee_category_code
                                    else a.employee_category_code end,
                               a.fte
                          from att.daily_timesheet d
                          left join att.shift_definition s on s.tenant_id = d.tenant_id and s.id = d.shift_id
                          left join lateral (select x.cost_centre_id, x.employee_category_code, x.fte
                                               from att.ext_hr_assignment x
                                              where x.tenant_id = d.tenant_id and x.assignment_key = d.assignment_key
                                                and x.valid_during @> d.work_date limit 1) a on true
                          left join lateral (select x.pattern_id from att.schedule_assignment x
                                              where x.tenant_id = d.tenant_id and x.assignment_key = d.assignment_key
                                                and x.status = 'ACTIVE' and x.valid_during @> d.work_date
                                              limit 1) sa on true
                          left join lateral (select x.employment_id, x.employee_category_code
                                               from att.ext_hr_employment_classification x
                                              where x.tenant_id = d.tenant_id and x.employment_id = d.employment_id
                                                and d.assignment_key is not null
                                                and x.valid_during @> d.work_date limit 1) cl on true
                         where d.tenant_id = :t and d.period_id = :p and d.work_date between :f and :to
                           and (cast(:e as uuid) is null or d.employment_id = :e)
                         order by d.employment_id, d.work_date""")
                .param("t", tenant()).param("p", periodId).param("f", from).param("to", to).param("e", employmentId)
                .query((rs, n) -> new ClosingDay(rs.getObject(1, UUID.class),
                        new DayFacts(rs.getObject(2, LocalDate.class), !"NOT_EMPLOYED".equals(rs.getString(3)),
                                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                                payGroupId, rs.getObject(7, UUID.class), codes(rs.getString(8)),
                                rs.getBigDecimal(9), rs.getInt(10), rs.getInt(11), strings(rs.getArray(12)),
                                rs.getBigDecimal(13), rs.getString(14),
                                new SegmentKeys(rs.getObject(17, UUID.class), rs.getString(18),
                                        rs.getBigDecimal(19))),
                        rs.getBoolean(15), rs.getObject(16, UUID.class)))
                .list();
    }

    /** Cost centre and segment keys of an assignment line on a date (for days recomputed in memory). */
    public record LineKeys(UUID costCentreId, SegmentKeys keys) {

        public static final LineKeys NONE = new LineKeys(null, SegmentKeys.NONE);
    }

    /**
     * The cost centre of the assignment line on the date, its schedule pattern ({@code att.schedule_assignment}), the
     * employee group (dated classification, else the line's) and its FTE: the segment keys of BRD §7.3.
     */
    public LineKeys lineKeys(UUID employmentId, UUID assignmentKey, LocalDate date) {
        if (assignmentKey == null) {
            return LineKeys.NONE;
        }
        return db.jdbc().sql("""
                        select a.cost_centre_id, sa.pattern_id,
                               case when cl.employment_id is not null then cl.employee_category_code
                                    else a.employee_category_code end,
                               a.fte
                          from (select 1) one
                          left join lateral (select x.cost_centre_id, x.employee_category_code, x.fte
                                               from att.ext_hr_assignment x
                                              where x.tenant_id = :t and x.assignment_key = :k
                                                and x.valid_during @> cast(:d as date) limit 1) a on true
                          left join lateral (select x.pattern_id from att.schedule_assignment x
                                              where x.tenant_id = :t and x.assignment_key = :k
                                                and x.status = 'ACTIVE' and x.valid_during @> cast(:d as date)
                                              limit 1) sa on true
                          left join lateral (select x.employment_id, x.employee_category_code
                                               from att.ext_hr_employment_classification x
                                              where x.tenant_id = :t and x.employment_id = :e
                                                and x.valid_during @> cast(:d as date) limit 1) cl on true""")
                .param("t", tenant()).param("k", assignmentKey).param("e", employmentId).param("d", date)
                .query((rs, n) -> new LineKeys(rs.getObject(1, UUID.class),
                        new SegmentKeys(rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4))))
                .optional().orElse(LineKeys.NONE);
    }

    /**
     * Leave days paid out by the year-end / carry-expiry runs (ATT-P-06) with a ledger date in {@code [from, to]}, as
     * LEAVE_ENCASH_DAYS. Termination payouts (ref TERMINATION) are left out: the leaver's settlement carries them
     * (BR-ATT-067), so a day is never paid twice.
     */
    public List<Dated> leaveEncash(UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select txn_date, -sum(quantity) from att.leave_balance_ledger
                         where tenant_id = :t and employment_id = :e and txn_type = 'ENCASH' and unit = 'DAY'
                           and ref_type in ('CARRY_RUN', 'CARRY_EXPIRY') and txn_date between :f and :to
                         group by txn_date order by txn_date""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("to", to)
                .query((rs, n) -> new Dated(rs.getObject(1, LocalDate.class), "LEAVE_ENCASH_DAYS", "DAY",
                        rs.getBigDecimal(2)))
                .list();
    }

    public List<Overtime> overtime(UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select work_date, ot_category, preceded_by_day_ot, paid_minutes from att.overtime_result
                         where tenant_id = :t and employment_id = :e and work_date between :f and :to
                         order by work_date, ot_category""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("to", to)
                .query((rs, n) -> new Overtime(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getBoolean(3),
                        rs.getInt(4)))
                .list();
    }

    /** Blocking exceptions of the period's days, per exception type. */
    public List<Count> blockingExceptions(UUID periodId, UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select x.code, count(*) from att.daily_timesheet d, unnest(d.exceptions) as x(code)
                         where d.tenant_id = :t and d.period_id = :p and d.has_blocking_exception
                           and d.work_date between :f and :to and (cast(:e as uuid) is null or d.employment_id = :e)
                         group by x.code order by x.code""")
                .param("t", tenant()).param("p", periodId).param("e", employmentId).param("f", from)
                .param("to", to).query((rs, n) -> new Count(rs.getString(1), rs.getLong(2))).list();
    }

    /** Requests still waiting for a decision on days of the range (BR-ATT-074). */
    public List<Count> pendingRequests(UUID legalEntityId, UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select 'PENDING_LEAVE', count(*) from att.leave_request
                         where tenant_id = :t and legal_entity_id = :le and status = 'PENDING'
                           and start_date <= :to and end_date >= :f and (cast(:e as uuid) is null or employment_id = :e)
                        union all
                        select 'PENDING_OVERTIME', count(*) from att.overtime_request
                         where tenant_id = :t and legal_entity_id = :le and status = 'PENDING'
                           and work_date between :f and :to and (cast(:e as uuid) is null or employment_id = :e)
                        union all
                        select 'PENDING_CORRECTION', count(*) from att.timesheet_correction
                         where tenant_id = :t and legal_entity_id = :le and status = 'PENDING'
                           and work_date between :f and :to and (cast(:e as uuid) is null or employment_id = :e)""")
                .param("t", tenant()).param("le", legalEntityId).param("e", employmentId).param("f", from)
                .param("to", to).query((rs, n) -> new Count(rs.getString(1), rs.getLong(2))).list();
    }

    /** Unresolved quarantined punches of the legal entity (matched LE, or the device's) in the range. */
    public long quarantinedPunches(UUID legalEntityId, UUID employmentId, LocalDate from, LocalDate to) {
        if (employmentId != null) {
            return 0;   // a quarantined punch has no employment yet
        }
        return db.jdbc().sql("""
                        select count(*) from att.raw_punch r
                         where r.tenant_id = :t and r.validation_status like 'QUARANTINED%'
                           and r.work_date_hint between :f and :to
                           and (r.legal_entity_id = :le or exists (select 1 from att.device v
                                 where v.tenant_id = r.tenant_id and v.id = r.device_id and v.legal_entity_id = :le))""")
                .param("t", tenant()).param("le", legalEntityId).param("f", from).param("to", to)
                .query(Long.class).single();
    }

    /**
     * Employments of the legal entity employed on some day of {@code [from, to]} without any day of the period: their
     * days were never computed (or belong to another pay group).
     */
    public long unprocessedEmployments(UUID periodId, UUID legalEntityId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select count(distinct a.employment_id) from att.ext_hr_assignment a
                          join att.ext_hr_employment e on e.tenant_id = a.tenant_id and e.employment_id = a.employment_id
                         where a.tenant_id = :t and a.legal_entity_id = :le
                           and a.valid_during && daterange(cast(:f as date), cast(:to as date), '[]')
                           and e.status <> 'CANCELLED' and e.hire_date <= :to
                           and (e.termination_date is null or e.termination_date >= :f)
                           and not exists (select 1 from att.daily_timesheet d
                                 where d.tenant_id = a.tenant_id and d.employment_id = a.employment_id
                                   and d.period_id = :p)""")
                .param("t", tenant()).param("p", periodId).param("le", legalEntityId).param("f", from)
                .param("to", to).query(Long.class).single();
    }

    // ------------------------------------------------------------------------------------------------ retro

    /** Records that days of a LOCKED period changed; widens an existing PENDING request. */
    public void recordRetro(UUID employmentId, UUID periodId, LocalDate date, String reason) {
        db.jdbc().sql("""
                        insert into att.retro_request as r (tenant_id, employment_id, period_id, first_date, last_date,
                               reason, created_by, updated_by)
                        values (:t, :e, :p, :d, :d, :r, :by, :by)
                        on conflict (tenant_id, employment_id, period_id) where status = 'PENDING'
                        do update set first_date = least(r.first_date, excluded.first_date),
                                      last_date = greatest(r.last_date, excluded.last_date),
                                      updated_by = excluded.updated_by, version = r.version + 1""")
                .param("t", tenant()).param("e", employmentId).param("p", periodId).param("d", date)
                .param("r", reason).param("by", actor()).update();
    }

    /**
     * Requests to apply at the close of a period of {@code legalEntityId} x {@code payGroupId} starting on
     * {@code before}: PENDING ones, and APPLIED ones whose result was withdrawn since (reopen), of LOCKED earlier periods
     * of the same legal entity x pay group, optionally of one employment.
     */
    public List<RetroCandidate> retroCandidates(UUID legalEntityId, UUID payGroupId, LocalDate before,
            UUID employmentId) {
        return db.jdbc().sql("""
                        select q.id, q.employment_id, q.period_id, p.period_start, p.period_end, q.first_date,
                               q.last_date
                          from att.retro_request q
                          join att.attendance_period p on p.tenant_id = q.tenant_id and p.id = q.period_id
                          left join att.attendance_result r on r.tenant_id = q.tenant_id and r.id = q.applied_result_id
                         where q.tenant_id = :t and p.legal_entity_id = :le and p.pay_group_id = :pg
                           and p.status = 'LOCKED' and p.period_start < :b
                           and (cast(:e as uuid) is null or q.employment_id = :e)
                           and (q.status = 'PENDING' or (q.status = 'APPLIED' and r.status = 'WITHDRAWN'))
                         order by q.employment_id, p.period_start""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId).param("b", before)
                .param("e", employmentId)
                .query((rs, n) -> new RetroCandidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class)))
                .list();
    }

    public List<RetroCandidate> retroRequests(UUID periodId) {
        return db.jdbc().sql("""
                        select q.id, q.employment_id, q.period_id, p.period_start, p.period_end, q.first_date,
                               q.last_date
                          from att.retro_request q
                          join att.attendance_period p on p.tenant_id = q.tenant_id and p.id = q.period_id
                         where q.tenant_id = :t and q.period_id = :p and q.status = 'PENDING'
                         order by q.employment_id""")
                .param("t", tenant()).param("p", periodId)
                .query((rs, n) -> new RetroCandidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class)))
                .list();
    }

    public void markRetro(List<UUID> ids, UUID resultId) {
        db.jdbc().sql("""
                        update att.retro_request
                           set status = case when cast(:r as uuid) is null then 'NO_CHANGE' else 'APPLIED' end,
                               applied_result_id = :r, applied_at = now(), updated_by = :by, version = version + 1
                         where tenant_id = :t and id = any(:ids)""")
                .param("t", tenant()).param("ids", ids.toArray(UUID[]::new)).param("r", resultId)
                .param("by", actor()).update();
    }

    /** The LOCKED version that carries the employment's lines of the period (period or early-close scope). */
    public Optional<UUID> lockedResultOf(UUID periodId, UUID employmentId) {
        return db.jdbc().sql("""
                        select r.id from att.attendance_result r
                         where r.tenant_id = :t and r.period_id = :p and r.status = 'LOCKED'
                           and r.result_kind in ('REGULAR', 'EARLY_CLOSE')
                           and exists (select 1 from att.attendance_result_line l
                                 where l.tenant_id = r.tenant_id and l.result_id = r.id
                                   and l.period_start = r.period_start and l.employment_id = :e)
                         order by r.result_kind desc limit 1""")
                .param("t", tenant()).param("p", periodId).param("e", employmentId).query(UUID.class).optional();
    }

    /**
     * What was already handed over for the employment's days of {@code originalPeriodId}: the non-retro lines of the
     * LOCKED version, plus every retro line of live results that corrects that period.
     */
    public List<Existing> handedOver(UUID originalPeriodId, UUID employmentId) {
        return db.jdbc().sql("""
                        select coalesce(l.original_segment_from, l.segment_from), l.segment_to, l.assignment_key,
                               l.legal_entity_id, l.org_unit_id, l.pay_group_id, l.cost_centre_id, l.category_code,
                               l.unit, l.quantity, l.preceded_by_day_ot, l.schedule_pattern_id, l.employee_group_code,
                               l.fte
                          from att.attendance_result_line l
                          join att.attendance_result r on r.tenant_id = l.tenant_id and r.id = l.result_id
                         where l.tenant_id = :t and l.employment_id = :e
                           and ((r.period_id = :p and r.status = 'LOCKED' and not l.is_retro
                                 and r.result_kind in ('REGULAR', 'EARLY_CLOSE'))
                             or (l.is_retro and l.original_period_id = :p and r.status in ('PUBLISHED', 'LOCKED')))""")
                .param("t", tenant()).param("e", employmentId).param("p", originalPeriodId)
                .query((rs, n) -> new Existing(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
                        rs.getBigDecimal(10), rs.getBoolean(11),
                        new SegmentKeys(rs.getObject(12, UUID.class), rs.getString(13), rs.getBigDecimal(14))))
                .list();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private List<CodeQty> codes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        JsonNode arr = json.readTree(text);
        List<CodeQty> out = new ArrayList<>();
        for (JsonNode n : arr) {
            JsonNode q = n.get("qty");
            out.add(new CodeQty(n.get("code").asString(), q == null || q.isNull() ? BigDecimal.ZERO : q.decimalValue()));
        }
        return out;
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    /** For tests and views: the lines of a result as a map per category (sum). */
    public static Map<String, BigDecimal> sumByCategory(List<Line> lines) {
        Map<String, BigDecimal> out = new java.util.TreeMap<>();
        lines.forEach(l -> out.merge(l.category(), l.quantity(), BigDecimal::add));
        return out;
    }
}
