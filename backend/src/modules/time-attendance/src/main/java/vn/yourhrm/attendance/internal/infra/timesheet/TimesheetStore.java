package vn.yourhrm.attendance.internal.infra.timesheet;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.CodeQty;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.DayResult;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.Columns;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.daily_timesheet}, {@code att.timesheet_correction} and {@code att.attendance_code}, plus the
 * read-only inputs of daily processing (approved leave, protected flags, periods, location zones). Every method runs
 * inside the caller's T&amp;A transaction.
 */
@Component
public class TimesheetStore {

    public record CodeRow(UUID id, boolean global, String code, String labelVi, String labelEn, String kind,
            String payrollCategory, String payrollSubCode, boolean countsAsWorked, String paidBy, boolean siNonwork,
            int displayOrder, String colorHex, String status, int version) {
    }

    /** Where the day belongs (M9 scope columns, period). */
    public record Placement(UUID assignmentKey, UUID legalEntityId, UUID orgUnitId, UUID locationId, UUID payGroupId,
            UUID periodId, UUID shiftId) {
    }

    public record DayRow(UUID id, UUID employmentId, LocalDate workDate, UUID assignmentKey, UUID legalEntityId,
            UUID orgUnitId, UUID locationId, UUID periodId, UUID shiftId, String dayType, String dayStatus,
            String primaryCode, List<CodeQty> codes, Instant firstInAt, Instant lastOutAt, BigDecimal scheduledHours,
            BigDecimal workedHours, BigDecimal paidBreakHours, int protectedBreakMin, int lateMin, int earlyMin,
            BigDecimal absentHours, BigDecimal nightHours, int otCandidateMin, BigDecimal dayValue,
            List<String> exceptions, boolean hasBlockingException, boolean manualOverride, int calcVersion,
            Instant calcAt, String calcInputsHash, UUID legalPackId, String ruleVersion) {
    }

    public record CorrectionRow(UUID id, UUID employmentId, LocalDate workDate, UUID legalEntityId, UUID orgUnitId,
            String correctionType, Instant requestedInAt, Instant requestedOutAt, String requestedCode,
            String fieldChanges, String reasonCode, String reasonText, String source, UUID bulkBatchId, String status,
            UUID workflowInstanceId, Instant decidedAt, UUID decidedBy, Integer appliedCalcVersion, Instant createdAt,
            UUID createdBy, int version) {
    }

    public record NewCorrection(UUID employmentId, LocalDate workDate, UUID legalEntityId, UUID orgUnitId,
            String correctionType, Instant requestedInAt, Instant requestedOutAt, String requestedCode,
            String reasonCode, String reasonText, List<UUID> attachments, String source, UUID bulkBatchId,
            String status) {
    }

    /** An approved leave request covering a day: the code and the part of that day (FULL, AM, PM). */
    public record LeaveDay(LocalDate date, String code, String part) {
    }

    public record Period(UUID id, String status) {
    }

    private final AttDb db;
    private final JsonMapper json;

    public TimesheetStore(AttDb db, JsonMapper json) {
        this.db = db;
        this.json = json;
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

    private static OffsetDateTime odt(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------------------------------- attendance codes

    private static final String CODE_COLUMNS = """
            select id, tenant_id = sys.global_tenant_id() as global, code, label_vi, label_en, code_kind,
                   payroll_category, payroll_sub_code, counts_as_worked, paid_by, counts_si_nonwork, display_order,
                   color_hex, status, version
              from att.attendance_code
            """;

    private static CodeRow code(ResultSet rs, int n) throws SQLException {
        return new CodeRow(rs.getObject("id", UUID.class), rs.getBoolean("global"), rs.getString("code"),
                rs.getString("label_vi"), rs.getString("label_en"), rs.getString("code_kind"),
                rs.getString("payroll_category"), rs.getString("payroll_sub_code"), rs.getBoolean("counts_as_worked"),
                rs.getString("paid_by"), rs.getBoolean("counts_si_nonwork"), rs.getInt("display_order"),
                rs.getString("color_hex"), rs.getString("status"), rs.getInt("version"));
    }

    /** Effective codes: tenant rows shadow the platform defaults of the same code. */
    public Map<String, CodeRow> effectiveCodes() {
        Map<String, CodeRow> byCode = new LinkedHashMap<>();
        List<CodeRow> rows = db.jdbc().sql(CODE_COLUMNS + """
                         where tenant_id in (:t, sys.global_tenant_id())
                         order by code, (tenant_id = sys.global_tenant_id())""")
                .param("t", tenant()).query(TimesheetStore::code).list();
        for (CodeRow r : rows) {
            byCode.putIfAbsent(r.code(), r); // the tenant row comes first and wins
        }
        Map<String, CodeRow> out = new LinkedHashMap<>();
        byCode.values().stream()
                .sorted(java.util.Comparator.comparingInt(CodeRow::displayOrder).thenComparing(CodeRow::code))
                .forEach(r -> out.put(r.code(), r));
        return out;
    }

    public Optional<CodeRow> tenantCode(UUID id) {
        return db.jdbc().sql(CODE_COLUMNS + " where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(TimesheetStore::code).optional();
    }

    public UUID insertCode(CodeRow c) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.attendance_code (tenant_id, id, code, label_vi, label_en, code_kind,
                                   payroll_category, payroll_sub_code, counts_as_worked, paid_by, counts_si_nonwork,
                                   display_order, color_hex, status, created_by, updated_by)
                            values (:t, :id, :code, :vi, :en, :kind, :cat, :sub, :worked, :paid, :si, :ord, :color,
                                   :st, :by, :by)""")
                    .param("t", tenant()).param("id", id).param("code", c.code()).param("vi", c.labelVi())
                    .param("en", c.labelEn()).param("kind", c.kind()).param("cat", c.payrollCategory())
                    .param("sub", c.payrollSubCode()).param("worked", c.countsAsWorked()).param("paid", c.paidBy())
                    .param("si", c.siNonwork()).param("ord", c.displayOrder()).param("color", c.colorHex())
                    .param("st", c.status()).param("by", actor()).update();
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("attendance_code_uq")) {
                throw ApiException.conflict("attendance-code-taken", "The tenant already has code " + c.code());
            }
            throw e;
        }
        return id;
    }

    public boolean updateCode(UUID id, int version, CodeRow c) {
        return db.jdbc().sql("""
                        update att.attendance_code set label_vi = :vi, label_en = :en, code_kind = :kind,
                               payroll_category = :cat, payroll_sub_code = :sub, counts_as_worked = :worked,
                               paid_by = :paid, counts_si_nonwork = :si, display_order = :ord, color_hex = :color,
                               status = :st, updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("t", tenant()).param("id", id).param("v", version).param("vi", c.labelVi())
                .param("en", c.labelEn()).param("kind", c.kind()).param("cat", c.payrollCategory())
                .param("sub", c.payrollSubCode()).param("worked", c.countsAsWorked()).param("paid", c.paidBy())
                .param("si", c.siNonwork()).param("ord", c.displayOrder()).param("color", c.colorHex())
                .param("st", c.status()).param("by", actor()).update() == 1;
    }

    // ------------------------------------------------------------------------------------------------ inputs

    /** The attendance period of the legal entity (and pay group, when known) covering {@code date}. */
    public Optional<Period> periodOn(UUID legalEntityId, UUID payGroupId, LocalDate date) {
        return db.jdbc().sql("""
                        select id, status from att.attendance_period
                         where tenant_id = :t and legal_entity_id = :le and :d between period_start and period_end
                           and (cast(:pg as uuid) is null or pay_group_id = :pg)
                         order by (status in ('CLOSED', 'LOCKED')) desc, period_start limit 1""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId).param("d", date)
                .query((rs, n) -> new Period(rs.getObject(1, UUID.class), rs.getString(2))).optional();
    }

    /** The employment's pay group on {@code date} (Payroll's projection), if fed. */
    public Optional<UUID> payGroup(UUID employmentId, LocalDate date) {
        return db.jdbc().sql("""
                        select pay_group_id from att.ext_pay_pay_group_assignment
                         where tenant_id = :t and employment_id = :e and valid_during @> cast(:d as date)
                         limit 1""")
                .param("t", tenant()).param("e", employmentId).param("d", date).query(UUID.class).optional();
    }

    /** IANA zone of the location version in effect on {@code date} (else its latest version), if projected. */
    public Optional<String> locationZone(UUID locationId, LocalDate date) {
        if (locationId == null) {
            return Optional.empty();
        }
        return db.jdbc().sql("""
                        select time_zone from att.ext_core_location where tenant_id = :t and location_id = :id
                         order by (valid_during @> cast(:d as date)) desc, valid_from desc limit 1""")
                .param("t", tenant()).param("id", locationId).param("d", date).query(String.class).optional();
    }

    /** {@code att.protected_flags} as of the work date: [pregnant, child under 12 months]. */
    public boolean[] protectedFlags(UUID employmentId, LocalDate date) {
        return db.jdbc().sql("select is_pregnant, has_child_under_12m from att.protected_flags(:e, :d)")
                .param("e", employmentId).param("d", date)
                .query((rs, n) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)}).optional()
                .orElse(new boolean[] {false, false});
    }

    /** Approved day / half-day leave of the employment per date in {@code [from, to]} (hourly leave: P1 not read). */
    public List<LeaveDay> approvedLeave(UUID employmentId, LocalDate from, LocalDate to) {
        record Req(LocalDate start, LocalDate end, String startPart, String endPart, String code) {
        }
        List<Req> reqs = db.jdbc().sql("""
                        select r.start_date, least(r.end_date, coalesce(r.actual_return_date - 1, r.end_date)),
                               r.start_part, r.end_part, t.attendance_code
                          from att.leave_request r
                          join att.leave_type t on t.tenant_id = r.tenant_id and t.id = r.leave_type_id
                         where r.tenant_id = :t and r.employment_id = :e and r.status = 'APPROVED'
                           and r.request_unit <> 'HOUR' and r.start_date <= :to and r.end_date >= :f
                         order by r.start_date, r.id""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("to", to)
                .query((rs, n) -> new Req(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class),
                        rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
        List<LeaveDay> out = new ArrayList<>();
        for (Req r : reqs) {
            for (LocalDate d = r.start().isBefore(from) ? from : r.start(); !d.isAfter(r.end()) && !d.isAfter(to);
                    d = d.plusDays(1)) {
                String part = "FULL";
                if (d.equals(r.start()) && "PM".equals(r.startPart())) {
                    part = "PM";
                } else if (d.equals(r.end()) && "AM".equals(r.endPart())) {
                    part = "AM";
                }
                out.add(new LeaveDay(d, r.code(), part));
            }
        }
        return out;
    }

    /** Employments with an assignment line of the legal entity overlapping {@code [from, to]}. */
    public List<UUID> employmentsOfLegalEntity(UUID legalEntityId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select distinct employment_id from att.ext_hr_assignment
                         where tenant_id = :t and legal_entity_id = :le
                           and valid_during && daterange(cast(:f as date), cast(:to as date), '[]')
                         order by employment_id""")
                .param("t", tenant()).param("le", legalEntityId).param("f", from).param("to", to)
                .query(UUID.class).list();
    }

    /** Legal entities the holiday calendar is assigned to on any day of {@code [from, to]}. */
    public List<UUID> legalEntitiesOfCalendar(UUID calendarId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select distinct legal_entity_id from att.holiday_calendar_assignment
                         where tenant_id = :t and calendar_id = :c
                           and valid_during && daterange(cast(:f as date), cast(:to as date), '[]')""")
                .param("t", tenant()).param("c", calendarId).param("f", from).param("to", to)
                .query(UUID.class).list();
    }

    /** Employment and dates of the VALID punches of an import job. */
    public List<Object[]> punchDaysOfImport(UUID importJobId) {
        return db.jdbc().sql("""
                        select employment_id, min(work_date_hint), max(work_date_hint) from att.raw_punch
                         where tenant_id = :t and import_job_id = :j and validation_status = 'VALID'
                         group by employment_id""")
                .param("t", tenant()).param("j", importJobId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)})
                .list();
    }

    // ------------------------------------------------------------------------------------------- daily timesheet

    private static final String DAY_COLUMNS = """
            select id, employment_id, work_date, assignment_key, legal_entity_id, org_unit_id, location_id, period_id,
                   shift_id, day_type, day_status, primary_code, codes::text as codes, first_in_at, last_out_at,
                   scheduled_hours, worked_hours, paid_break_hours, protected_break_min, late_min, early_min,
                   absent_hours, night_hours, ot_candidate_min, day_value, exceptions, has_blocking_exception,
                   is_manual_override, calc_version, calc_at, calc_inputs_hash, legal_pack_id, rule_version
              from att.daily_timesheet d
            """;

    private DayRow day(ResultSet rs, int n) throws SQLException {
        Array ex = rs.getArray("exceptions");
        List<CodeQty> codes = json.readValue(rs.getString("codes"), new TypeReference<List<CodeQty>>() {
        });
        return new DayRow(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getObject("work_date", LocalDate.class), rs.getObject("assignment_key", UUID.class),
                rs.getObject("legal_entity_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                rs.getObject("location_id", UUID.class), rs.getObject("period_id", UUID.class),
                rs.getObject("shift_id", UUID.class), rs.getString("day_type"), rs.getString("day_status"),
                rs.getString("primary_code"), codes, instant(rs, "first_in_at"), instant(rs, "last_out_at"),
                rs.getBigDecimal("scheduled_hours"), rs.getBigDecimal("worked_hours"),
                rs.getBigDecimal("paid_break_hours"), rs.getInt("protected_break_min"), rs.getInt("late_min"),
                rs.getInt("early_min"), rs.getBigDecimal("absent_hours"), rs.getBigDecimal("night_hours"),
                rs.getInt("ot_candidate_min"), rs.getBigDecimal("day_value"),
                ex == null ? List.of() : Arrays.asList((String[]) ex.getArray()),
                rs.getBoolean("has_blocking_exception"), rs.getBoolean("is_manual_override"),
                rs.getInt("calc_version"), instant(rs, "calc_at"), rs.getString("calc_inputs_hash"),
                rs.getObject("legal_pack_id", UUID.class), rs.getString("rule_version"));
    }

    public Optional<DayRow> day(UUID employmentId, LocalDate date) {
        return db.jdbc().sql(DAY_COLUMNS + " where tenant_id = :t and employment_id = :e and work_date = :d")
                .param("t", tenant()).param("e", employmentId).param("d", date).query(this::day).optional();
    }

    public List<DayRow> days(UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql(DAY_COLUMNS + """
                         where tenant_id = :t and employment_id = :e and work_date between :f and :to
                         order by work_date""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("to", to)
                .query(this::day).list();
    }

    /** The grid (US-ATT-032): days of a legal entity / org unit in the caller's scope. */
    public List<DayRow> grid(UUID legalEntityId, UUID orgUnitId, LocalDate from, LocalDate to, String status,
            String exception, boolean blockingOnly, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("d.legal_entity_id", "d.org_unit_id", "d.location_id",
                "d.employment_id"), "sc");
        return db.jdbc().sql(DAY_COLUMNS + """
                         where d.tenant_id = :t and d.work_date between :f and :to
                           and (cast(:le as uuid) is null or d.legal_entity_id = :le)
                           and (cast(:ou as uuid) is null or d.org_unit_id = :ou)
                           and (cast(:st as text) is null or d.day_status = :st)
                           and (cast(:ex as text) is null or cast(:ex as text) = any(d.exceptions))
                           and (not :blk or d.has_blocking_exception)
                        """ + " and " + f.sql() + " order by d.employment_id, d.work_date limit :lim offset :off")
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .param("ou", orgUnitId).param("st", status).param("ex", exception).param("blk", blockingOnly)
                .params(f.params()).param("lim", limit).param("off", offset).query(this::day).list();
    }

    /**
     * Writes the computed day: inserts it, or updates it when the inputs hash changed (calc_version + 1); returns the
     * stored calc_version, or empty when nothing changed (idempotent re-run, BR-ATT-038).
     */
    public Optional<Integer> upsertDay(UUID employmentId, LocalDate date, Placement p, DayResult r, String hash,
            UUID legalPackId, String ruleVersion) {
        String codes = json.writeValueAsString(r.codes());
        return db.jdbc().sql("""
                        insert into att.daily_timesheet as d (tenant_id, id, employment_id, work_date, assignment_key,
                               legal_entity_id, org_unit_id, location_id, pay_group_id, period_id, shift_id, day_type,
                               day_status, primary_code, codes, first_in_at, last_out_at, scheduled_hours,
                               worked_hours, paid_break_hours, protected_break_min, late_min, early_min, absent_hours,
                               night_hours, ot_candidate_min, day_value, exceptions, has_blocking_exception,
                               is_manual_override, calc_version, calc_at, calc_inputs_hash, legal_pack_id,
                               rule_version, created_by, updated_by)
                        values (:t, :id, :e, :d, :key, :le, :ou, :loc, :pg, :period, :shift, :dt, :ds, :pc,
                               cast(:codes as jsonb), :fin, :lout, :sch, :wrk, :pb, :prot, :late, :early, :abs,
                               :night, :ot, :dv, cast(:ex as text[]), :blk, :man, 1, now(), :hash, :pack, :rule,
                               :by, :by)
                        on conflict (tenant_id, employment_id, work_date) do update set
                               assignment_key = excluded.assignment_key, legal_entity_id = excluded.legal_entity_id,
                               org_unit_id = excluded.org_unit_id, location_id = excluded.location_id,
                               pay_group_id = excluded.pay_group_id, period_id = excluded.period_id,
                               shift_id = excluded.shift_id, day_type = excluded.day_type,
                               day_status = excluded.day_status, primary_code = excluded.primary_code,
                               codes = excluded.codes, first_in_at = excluded.first_in_at,
                               last_out_at = excluded.last_out_at, scheduled_hours = excluded.scheduled_hours,
                               worked_hours = excluded.worked_hours, paid_break_hours = excluded.paid_break_hours,
                               protected_break_min = excluded.protected_break_min, late_min = excluded.late_min,
                               early_min = excluded.early_min, absent_hours = excluded.absent_hours,
                               night_hours = excluded.night_hours, ot_candidate_min = excluded.ot_candidate_min,
                               day_value = excluded.day_value, exceptions = excluded.exceptions,
                               has_blocking_exception = excluded.has_blocking_exception,
                               is_manual_override = excluded.is_manual_override,
                               calc_version = d.calc_version + 1, calc_at = now(),
                               calc_inputs_hash = excluded.calc_inputs_hash, legal_pack_id = excluded.legal_pack_id,
                               rule_version = excluded.rule_version, updated_by = excluded.updated_by,
                               version = d.version + 1
                         where d.calc_inputs_hash is distinct from excluded.calc_inputs_hash
                        returning calc_version""")
                .param("t", tenant()).param("id", Ids.newId()).param("e", employmentId).param("d", date)
                .param("key", p.assignmentKey()).param("le", p.legalEntityId()).param("ou", p.orgUnitId())
                .param("loc", p.locationId()).param("pg", p.payGroupId()).param("period", p.periodId())
                .param("shift", p.shiftId()).param("dt", r.dayType()).param("ds", r.dayStatus())
                .param("pc", r.primaryCode()).param("codes", codes).param("fin", odt(r.firstInAt()))
                .param("lout", odt(r.lastOutAt())).param("sch", r.scheduledHours()).param("wrk", r.workedHours())
                .param("pb", r.paidBreakHours()).param("prot", r.protectedBreakMin()).param("late", r.lateMin())
                .param("early", r.earlyMin()).param("abs", r.absentHours()).param("night", r.nightHours())
                .param("ot", r.otCandidateMin()).param("dv", r.dayValue())
                .param("ex", "{" + String.join(",", r.exceptions()) + "}").param("blk", r.blocking())
                .param("man", r.manualOverride()).param("hash", hash).param("pack", legalPackId)
                .param("rule", ruleVersion).param("by", actor())
                .query(Integer.class).optional();
    }

    // -------------------------------------------------------------------------------------------- corrections

    private static final String CORRECTION_COLUMNS = """
            select id, employment_id, work_date, legal_entity_id, org_unit_id, correction_type, requested_in_at,
                   requested_out_at, requested_code, field_changes::text as field_changes, reason_code, reason_text,
                   source, bulk_batch_id, status, workflow_instance_id, decided_at, decided_by, applied_calc_version,
                   created_at, created_by, version
              from att.timesheet_correction
            """;

    private static CorrectionRow correction(ResultSet rs, int n) throws SQLException {
        int applied = rs.getInt("applied_calc_version");
        Integer appliedV = rs.wasNull() ? null : applied;
        return new CorrectionRow(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getObject("work_date", LocalDate.class), rs.getObject("legal_entity_id", UUID.class),
                rs.getObject("org_unit_id", UUID.class), rs.getString("correction_type"),
                instant(rs, "requested_in_at"), instant(rs, "requested_out_at"), rs.getString("requested_code"),
                rs.getString("field_changes"), rs.getString("reason_code"), rs.getString("reason_text"),
                rs.getString("source"), rs.getObject("bulk_batch_id", UUID.class), rs.getString("status"),
                rs.getObject("workflow_instance_id", UUID.class), instant(rs, "decided_at"),
                rs.getObject("decided_by", UUID.class), appliedV, instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class), rs.getInt("version"));
    }

    public Optional<CorrectionRow> correction(UUID id) {
        return db.jdbc().sql(CORRECTION_COLUMNS + " where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(TimesheetStore::correction).optional();
    }

    /** Corrections of the employment in {@code [from, to]}, optionally only APPROVED / APPLIED, in creation order. */
    public List<CorrectionRow> corrections(UUID employmentId, LocalDate from, LocalDate to, boolean effectiveOnly) {
        return db.jdbc().sql(CORRECTION_COLUMNS + """
                         where tenant_id = :t and employment_id = :e and work_date between :f and :to
                           and (not :eff or status in ('APPROVED', 'APPLIED'))
                         order by work_date, created_at, id""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("to", to)
                .param("eff", effectiveOnly).query(TimesheetStore::correction).list();
    }

    /** ESS / MSS requests of the employment in the month of {@code date} that are not rejected or cancelled. */
    public int requestsInMonth(UUID employmentId, LocalDate date) {
        LocalDate first = date.withDayOfMonth(1);
        return db.jdbc().sql("""
                        select count(*) from att.timesheet_correction
                         where tenant_id = :t and employment_id = :e and source in ('ESS', 'MSS')
                           and work_date between :f and :l and status not in ('REJECTED', 'CANCELLED')""")
                .param("t", tenant()).param("e", employmentId).param("f", first)
                .param("l", first.plusMonths(1).minusDays(1)).query(Integer.class).single();
    }

    public UUID insertCorrection(NewCorrection c) {
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into att.timesheet_correction (tenant_id, id, employment_id, work_date, legal_entity_id,
                               org_unit_id, correction_type, requested_in_at, requested_out_at, requested_code,
                               reason_code, reason_text, attachment_document_ids, source, bulk_batch_id, status,
                               decided_at, decided_by, created_by, updated_by)
                        values (:t, :id, :e, :d, :le, :ou, :type, :in, :out, :code, :rc, :rt, cast(:att as uuid[]),
                               :src, :batch, :st, case when :st = 'APPROVED' then now() end,
                               case when :st = 'APPROVED' then cast(:by as uuid) end, :by, :by)""")
                .param("t", tenant()).param("id", id).param("e", c.employmentId()).param("d", c.workDate())
                .param("le", c.legalEntityId()).param("ou", c.orgUnitId()).param("type", c.correctionType())
                .param("in", odt(c.requestedInAt())).param("out", odt(c.requestedOutAt()))
                .param("code", c.requestedCode()).param("rc", c.reasonCode()).param("rt", c.reasonText())
                .param("att", "{" + String.join(",", c.attachments().stream().map(UUID::toString).toList()) + "}")
                .param("src", c.source()).param("batch", c.bulkBatchId()).param("st", c.status())
                .param("by", actor()).update();
        return id;
    }

    public void setWorkflow(UUID id, UUID instanceId) {
        db.jdbc().sql("update att.timesheet_correction set workflow_instance_id = :w where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).param("w", instanceId).update();
    }

    /** Status change of a correction (decision or application). */
    public boolean setCorrectionStatus(UUID id, String from, String to, UUID decidedBy, Integer appliedCalcVersion,
            String fieldChanges) {
        return db.jdbc().sql("""
                        update att.timesheet_correction
                           set status = :to,
                               decided_at = case when :to in ('APPROVED', 'REJECTED', 'CANCELLED') then now()
                                                 else decided_at end,
                               decided_by = case when :to in ('APPROVED', 'REJECTED', 'CANCELLED')
                                                 then cast(:by as uuid) else decided_by end,
                               applied_calc_version = coalesce(cast(:acv as int), applied_calc_version),
                               field_changes = coalesce(cast(:fc as jsonb), field_changes),
                               updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id and status = :from""")
                .param("t", tenant()).param("id", id).param("from", from).param("to", to).param("by", decidedBy)
                .param("acv", appliedCalcVersion).param("fc", fieldChanges).param("actor", actor()).update() == 1;
    }
}
