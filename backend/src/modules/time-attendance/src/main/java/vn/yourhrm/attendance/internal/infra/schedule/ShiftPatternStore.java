package vn.yourhrm.attendance.internal.infra.schedule;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.shift_definition} / {@code att.shift_break} and {@code att.schedule_pattern} /
 * {@code att.schedule_pattern_day}. Every method runs inside the caller's T&amp;A transaction.
 */
@Component
public class ShiftPatternStore {

    /** The attributes of a shift (insert / update). */
    public record ShiftData(String code, String nameVi, String nameEn, LocalTime startTime, LocalTime endTime,
            int graceInMin, int graceOutMin, String roundingRuleCode, int punchWindowBeforeMin,
            int punchWindowAfterMin, Integer minMinutesHalfDay, Integer minMinutesFullDay, BigDecimal stdHours,
            BigDecimal dayValue, LocalTime nightWindowStart, LocalTime nightWindowEnd, String shiftGroupCode) {
    }

    public record BreakData(int seq, LocalTime start, LocalTime end, int durationMin, boolean paid,
            boolean flexible) {
    }

    public record ShiftRow(UUID id, ShiftData data, boolean crossesMidnight, String status, int version,
            List<BreakData> breaks) {
    }

    public record PatternData(String code, String nameVi, String nameEn, int cycleLengthDays, LocalDate anchorDate,
            List<Integer> weeklyRestDays, BigDecimal stdHoursWeek) {
    }

    /** {@code dayKind} WORK / REST / OFF; {@code shiftId} only for WORK. */
    public record PatternDay(int dayIndex, String dayKind, UUID shiftId) {
    }

    public record PatternRow(UUID id, PatternData data, String patternType, String status, int version,
            List<PatternDay> days) {
    }

    private final AttDb db;

    public ShiftPatternStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    // ============================================================================================ shifts

    private static final String SHIFT_COLUMNS = """
            select id, code, name_vi, name_en, start_time, end_time, grace_in_min, grace_out_min, rounding_rule_code,
                   punch_window_before_min, punch_window_after_min, min_minutes_half_day, min_minutes_full_day,
                   std_hours, day_value, night_window_start, night_window_end, shift_group_code, crosses_midnight,
                   status, version
              from att.shift_definition""";

    public List<ShiftRow> shifts() {
        List<ShiftRow> rows = db.jdbc().sql(SHIFT_COLUMNS + " where tenant_id = :t order by code")
                .param("t", tenant()).query(ShiftPatternStore::shift).list();
        Map<UUID, List<BreakData>> breaks = breaks(rows.stream().map(ShiftRow::id).toList());
        return rows.stream().map(r -> withBreaks(r, breaks.getOrDefault(r.id(), List.of()))).toList();
    }

    public Optional<ShiftRow> shift(UUID id) {
        return db.jdbc().sql(SHIFT_COLUMNS + " where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(ShiftPatternStore::shift).optional()
                .map(r -> withBreaks(r, breaks(List.of(id)).getOrDefault(id, List.of())));
    }

    /** Shifts by id (breaks included), for resolving patterns. */
    public Map<UUID, ShiftRow> shifts(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ShiftRow> out = new HashMap<>();
        List<ShiftRow> rows = db.jdbc().sql(SHIFT_COLUMNS + " where tenant_id = :t and id in (:ids)")
                .param("t", tenant()).param("ids", ids).query(ShiftPatternStore::shift).list();
        Map<UUID, List<BreakData>> breaks = breaks(rows.stream().map(ShiftRow::id).toList());
        rows.forEach(r -> out.put(r.id(), withBreaks(r, breaks.getOrDefault(r.id(), List.of()))));
        return out;
    }

    public UUID insertShift(ShiftData d, boolean crossesMidnight) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.shift_definition (tenant_id, id, code, name_vi, name_en, start_time,
                                   end_time, crosses_midnight, grace_in_min, grace_out_min, rounding_rule_code,
                                   punch_window_before_min, punch_window_after_min, min_minutes_half_day,
                                   min_minutes_full_day, std_hours, day_value, night_window_start, night_window_end,
                                   shift_group_code, created_by, updated_by)
                            values (:t, :id, :code, :vi, :en, :st, :et, :cm, :gi, :go, :rr, :pb, :pa, :mh, :mf, :sh,
                                    :dv, :ns, :ne, :grp, :by, :by)""")
                    .param("t", tenant()).param("id", id).params(shiftParams(d)).param("cm", crossesMidnight)
                    .param("by", actor()).update();
        } catch (DataIntegrityViolationException e) {
            throw StoreErrors.translate(e, "shift_definition_code_uq", "shift-code-taken",
                    "A shift with code " + d.code() + " already exists");
        }
        return id;
    }

    /** @return false when {@code version} is stale */
    public boolean updateShift(UUID id, ShiftData d, boolean crossesMidnight, String status, int version) {
        return db.jdbc().sql("""
                        update att.shift_definition
                           set name_vi = :vi, name_en = :en, start_time = :st, end_time = :et, crosses_midnight = :cm,
                               grace_in_min = :gi, grace_out_min = :go, rounding_rule_code = :rr,
                               punch_window_before_min = :pb, punch_window_after_min = :pa, min_minutes_half_day = :mh,
                               min_minutes_full_day = :mf, std_hours = :sh, day_value = :dv, night_window_start = :ns,
                               night_window_end = :ne, shift_group_code = :grp, status = :status, updated_by = :by,
                               version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .params(shiftParams(d)).param("cm", crossesMidnight).param("status", status).param("by", actor())
                .param("t", tenant()).param("id", id).param("v", version).update() == 1;
    }

    public void replaceBreaks(UUID shiftId, List<BreakData> breaks) {
        db.jdbc().sql("delete from att.shift_break where tenant_id = :t and shift_id = :s")
                .param("t", tenant()).param("s", shiftId).update();
        for (BreakData b : breaks) {
            db.jdbc().sql("""
                            insert into att.shift_break (tenant_id, shift_id, seq, break_start, break_end, duration_min,
                                   is_paid, is_flexible)
                            values (:t, :s, :seq, :bs, :be, :d, :paid, :flex)""")
                    .param("t", tenant()).param("s", shiftId).param("seq", b.seq()).param("bs", b.start())
                    .param("be", b.end()).param("d", b.durationMin()).param("paid", b.paid())
                    .param("flex", b.flexible()).update();
        }
    }

    /** Is the shift used by a pattern that an ACTIVE schedule assignment or rule uses? */
    public boolean shiftInUse(UUID shiftId) {
        return db.jdbc().sql("""
                        select exists (
                          select 1 from att.schedule_pattern_day d
                           where d.tenant_id = :t and d.shift_id = :s
                             and (exists (select 1 from att.schedule_assignment a where a.tenant_id = d.tenant_id
                                           and a.pattern_id = d.pattern_id and a.status = 'ACTIVE')
                               or exists (select 1 from att.schedule_assignment_rule r where r.tenant_id = d.tenant_id
                                           and r.pattern_id = d.pattern_id and r.status = 'ACTIVE')))""")
                .param("t", tenant()).param("s", shiftId).query(Boolean.class).single();
    }

    /** Codes of ACTIVE patterns with a WORK day on the shift. */
    public List<String> activePatternsUsing(UUID shiftId) {
        return db.jdbc().sql("""
                        select distinct p.code from att.schedule_pattern p
                          join att.schedule_pattern_day d on d.tenant_id = p.tenant_id and d.pattern_id = p.id
                         where p.tenant_id = :t and d.shift_id = :s and p.status = 'ACTIVE' order by 1""")
                .param("t", tenant()).param("s", shiftId).query(String.class).list();
    }

    private Map<UUID, List<BreakData>> breaks(Collection<UUID> shiftIds) {
        if (shiftIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<BreakData>> out = new LinkedHashMap<>();
        db.jdbc().sql("""
                        select shift_id, seq, break_start, break_end, duration_min, is_paid, is_flexible
                          from att.shift_break where tenant_id = :t and shift_id in (:ids) order by shift_id, seq""")
                .param("t", tenant()).param("ids", shiftIds)
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>())
                            .add(new BreakData(rs.getInt(2), rs.getObject(3, LocalTime.class),
                                    rs.getObject(4, LocalTime.class), rs.getInt(5), rs.getBoolean(6),
                                    rs.getBoolean(7)));
                    return 1;
                }).list();
        return out;
    }

    private static ShiftRow withBreaks(ShiftRow r, List<BreakData> breaks) {
        return new ShiftRow(r.id(), r.data(), r.crossesMidnight(), r.status(), r.version(), List.copyOf(breaks));
    }

    private static Map<String, Object> shiftParams(ShiftData d) {
        Map<String, Object> p = new HashMap<>();
        p.put("code", d.code());
        p.put("vi", d.nameVi());
        p.put("en", d.nameEn());
        p.put("st", d.startTime());
        p.put("et", d.endTime());
        p.put("gi", d.graceInMin());
        p.put("go", d.graceOutMin());
        p.put("rr", d.roundingRuleCode());
        p.put("pb", d.punchWindowBeforeMin());
        p.put("pa", d.punchWindowAfterMin());
        p.put("mh", d.minMinutesHalfDay());
        p.put("mf", d.minMinutesFullDay());
        p.put("sh", d.stdHours());
        p.put("dv", d.dayValue());
        p.put("ns", d.nightWindowStart());
        p.put("ne", d.nightWindowEnd());
        p.put("grp", d.shiftGroupCode());
        return p;
    }

    private static ShiftRow shift(ResultSet rs, int n) throws SQLException {
        ShiftData d = new ShiftData(rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalTime.class), rs.getObject(6, LocalTime.class), rs.getInt(7), rs.getInt(8),
                rs.getString(9), rs.getInt(10), rs.getInt(11), intOrNull(rs, 12), intOrNull(rs, 13),
                rs.getBigDecimal(14), rs.getBigDecimal(15), rs.getObject(16, LocalTime.class),
                rs.getObject(17, LocalTime.class), rs.getString(18));
        return new ShiftRow(rs.getObject(1, UUID.class), d, rs.getBoolean(19), rs.getString(20), rs.getInt(21),
                List.of());
    }

    static Integer intOrNull(ResultSet rs, int col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).intValue();
    }

    // ============================================================================================ patterns

    private static final String PATTERN_COLUMNS = """
            select id, code, name_vi, name_en, cycle_length_days, anchor_date, weekly_rest_days, std_hours_week,
                   pattern_type, status, version
              from att.schedule_pattern""";

    public List<PatternRow> patterns() {
        List<PatternRow> rows = db.jdbc().sql(PATTERN_COLUMNS + " where tenant_id = :t order by code")
                .param("t", tenant()).query(ShiftPatternStore::pattern).list();
        Map<UUID, List<PatternDay>> days = days(rows.stream().map(PatternRow::id).toList());
        return rows.stream().map(r -> withDays(r, days.getOrDefault(r.id(), List.of()))).toList();
    }

    public Optional<PatternRow> pattern(UUID id) {
        return patterns(List.of(id)).values().stream().findFirst();
    }

    public Map<UUID, PatternRow> patterns(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PatternDay>> days = days(ids);
        Map<UUID, PatternRow> out = new HashMap<>();
        db.jdbc().sql(PATTERN_COLUMNS + " where tenant_id = :t and id in (:ids)")
                .param("t", tenant()).param("ids", ids).query(ShiftPatternStore::pattern).list()
                .forEach(r -> out.put(r.id(), withDays(r, days.getOrDefault(r.id(), List.of()))));
        return out;
    }

    public UUID insertPattern(PatternData d) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.schedule_pattern (tenant_id, id, code, name_vi, name_en, pattern_type,
                                   cycle_length_days, anchor_date, weekly_rest_days, std_hours_week, created_by,
                                   updated_by)
                            values (:t, :id, :code, :vi, :en, 'WEEKLY', :len, :anchor, cast(:rest as smallint[]),
                                    :shw, :by, :by)""")
                    .param("t", tenant()).param("id", id).param("code", d.code()).param("vi", d.nameVi())
                    .param("en", d.nameEn()).param("len", d.cycleLengthDays()).param("anchor", d.anchorDate())
                    .param("rest", pgArray(d.weeklyRestDays())).param("shw", d.stdHoursWeek())
                    .param("by", actor()).update();
        } catch (DataIntegrityViolationException e) {
            throw StoreErrors.translate(e, "schedule_pattern_code_uq", "pattern-code-taken",
                    "A schedule pattern with code " + d.code() + " already exists");
        }
        return id;
    }

    /** @return false when {@code version} is stale */
    public boolean updatePattern(UUID id, PatternData d, String status, int version) {
        return db.jdbc().sql("""
                        update att.schedule_pattern
                           set name_vi = :vi, name_en = :en, cycle_length_days = :len, anchor_date = :anchor,
                               weekly_rest_days = cast(:rest as smallint[]), std_hours_week = :shw, status = :status,
                               updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("vi", d.nameVi()).param("en", d.nameEn()).param("len", d.cycleLengthDays())
                .param("anchor", d.anchorDate()).param("rest", pgArray(d.weeklyRestDays()))
                .param("shw", d.stdHoursWeek()).param("status", status).param("by", actor())
                .param("t", tenant()).param("id", id).param("v", version).update() == 1;
    }

    public void replaceDays(UUID patternId, List<PatternDay> days) {
        db.jdbc().sql("delete from att.schedule_pattern_day where tenant_id = :t and pattern_id = :p")
                .param("t", tenant()).param("p", patternId).update();
        for (PatternDay d : days) {
            db.jdbc().sql("""
                            insert into att.schedule_pattern_day (tenant_id, pattern_id, day_index, day_kind, shift_id)
                            values (:t, :p, :i, :k, :s)""")
                    .param("t", tenant()).param("p", patternId).param("i", d.dayIndex()).param("k", d.dayKind())
                    .param("s", d.shiftId()).update();
        }
    }

    /** Is the pattern used by an ACTIVE schedule assignment? */
    public boolean patternAssigned(UUID patternId) {
        return db.jdbc().sql("""
                        select exists (select 1 from att.schedule_assignment
                                        where tenant_id = :t and pattern_id = :p and status = 'ACTIVE')""")
                .param("t", tenant()).param("p", patternId).query(Boolean.class).single();
    }

    /** Codes of ACTIVE rules that assign the pattern. */
    public List<String> activeRulesUsing(UUID patternId) {
        return db.jdbc().sql("""
                        select code from att.schedule_assignment_rule
                         where tenant_id = :t and pattern_id = :p and status = 'ACTIVE' order by 1""")
                .param("t", tenant()).param("p", patternId).query(String.class).list();
    }

    private Map<UUID, List<PatternDay>> days(Collection<UUID> patternIds) {
        if (patternIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PatternDay>> out = new LinkedHashMap<>();
        db.jdbc().sql("""
                        select pattern_id, day_index, day_kind, shift_id from att.schedule_pattern_day
                         where tenant_id = :t and pattern_id in (:ids) order by pattern_id, day_index""")
                .param("t", tenant()).param("ids", patternIds)
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>())
                            .add(new PatternDay(rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class)));
                    return 1;
                }).list();
        return out;
    }

    private static PatternRow withDays(PatternRow r, List<PatternDay> days) {
        return new PatternRow(r.id(), r.data(), r.patternType(), r.status(), r.version(), List.copyOf(days));
    }

    private static PatternRow pattern(ResultSet rs, int n) throws SQLException {
        java.sql.Array arr = rs.getArray(7);
        List<Integer> rest = arr == null ? List.of() : java.util.Arrays.stream((Object[]) arr.getArray())
                .map(o -> ((Number) o).intValue()).sorted().toList();
        PatternData d = new PatternData(rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getObject(6, LocalDate.class), rest, rs.getBigDecimal(8));
        return new PatternRow(rs.getObject(1, UUID.class), d, rs.getString(9), rs.getString(10), rs.getInt(11),
                List.of());
    }

    private static String pgArray(List<Integer> values) {
        return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}";
    }
}
