package vn.yourhrm.attendance.internal.infra.schedule;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.Columns;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.schedule_assignment_rule}, {@code att.schedule_assignment} and the People projections
 * {@code att.ext_hr_assignment} / {@code att.ext_hr_employment}. Every method runs inside the caller's T&amp;A
 * transaction.
 */
@Component
public class ScheduleAssignmentStore {

    public record RuleData(String code, int priority, UUID legalEntityId, UUID orgUnitId, UUID locationId,
            UUID jobTitleId, String employeeCategoryCode, String workingRegimeCode, UUID patternId,
            LocalDate validFrom, LocalDate validTo) {
    }

    public record RuleRow(UUID id, RuleData data, String status, int version) {
    }

    /** {@code validTo} exclusive. */
    public record AssignmentRow(UUID id, UUID employmentId, UUID assignmentKey, UUID patternId, UUID ruleId,
            LocalDate validFrom, LocalDate validTo, String source, String status, int version) {

        public boolean covers(LocalDate d) {
            return !d.isBefore(validFrom) && (validTo == null || d.isBefore(validTo));
        }
    }

    /** One version of a People assignment line ({@code att.ext_hr_assignment}); {@code validTo} exclusive. */
    public record LineVersion(UUID id, UUID assignmentKey, UUID employmentId, UUID personId, String assignmentType,
            UUID legalEntityId, UUID orgUnitId, UUID positionId, UUID jobTitleId, UUID locationId, UUID costCentreId,
            UUID managerEmploymentId, String employeeCategoryCode, String workingRegimeCode, BigDecimal fte,
            LocalDate validFrom, LocalDate validTo) {

        public boolean covers(LocalDate d) {
            return !d.isBefore(validFrom) && (validTo == null || d.isBefore(validTo));
        }
    }

    /** Employment dates ({@code att.ext_hr_employment}); {@code terminationDate} inclusive. */
    public record EmploymentDates(UUID employmentId, LocalDate hireDate, LocalDate terminationDate, String status) {
    }

    private final AttDb db;

    public ScheduleAssignmentStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    // ============================================================================================ rules

    private static final String RULE_COLUMNS = """
            select id, code, priority, legal_entity_id, org_unit_id, location_id, job_title_id,
                   employee_category_code, working_regime_code, pattern_id, valid_from, valid_to, status, version
              from att.schedule_assignment_rule""";

    public List<RuleRow> rules() {
        return db.jdbc().sql(RULE_COLUMNS + " where tenant_id = :t order by priority, code")
                .param("t", tenant()).query(ScheduleAssignmentStore::rule).list();
    }

    public List<RuleRow> activeRules() {
        return db.jdbc().sql(RULE_COLUMNS + " where tenant_id = :t and status = 'ACTIVE' order by priority, code")
                .param("t", tenant()).query(ScheduleAssignmentStore::rule).list();
    }

    public Optional<RuleRow> rule(UUID id) {
        return db.jdbc().sql(RULE_COLUMNS + " where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(ScheduleAssignmentStore::rule).optional();
    }

    public UUID insertRule(RuleData d) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.schedule_assignment_rule (tenant_id, id, code, priority, legal_entity_id,
                                   org_unit_id, location_id, job_title_id, employee_category_code, working_regime_code,
                                   pattern_id, valid_from, valid_to, created_by, updated_by)
                            values (:t, :id, :code, :prio, :le, :ou, :loc, :job, :cat, :reg, :p, :f, :to, :by, :by)""")
                    .param("t", tenant()).param("id", id).params(ruleParams(d)).param("by", actor()).update();
        } catch (DataIntegrityViolationException e) {
            throw StoreErrors.translate(e, "schedule_assignment_rule_code_uq", "rule-code-taken",
                    "A schedule rule with code " + d.code() + " already exists");
        }
        return id;
    }

    /** @return false when {@code version} is stale */
    public boolean updateRule(UUID id, RuleData d, String status, int version) {
        return db.jdbc().sql("""
                        update att.schedule_assignment_rule
                           set priority = :prio, legal_entity_id = :le, org_unit_id = :ou, location_id = :loc,
                               job_title_id = :job, employee_category_code = :cat, working_regime_code = :reg,
                               pattern_id = :p, valid_from = :f, valid_to = :to, status = :status, updated_by = :by,
                               version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .params(ruleParams(d)).param("status", status).param("by", actor()).param("t", tenant())
                .param("id", id).param("v", version).update() == 1;
    }

    private static Map<String, Object> ruleParams(RuleData d) {
        Map<String, Object> p = new HashMap<>();
        p.put("code", d.code());
        p.put("prio", d.priority());
        p.put("le", d.legalEntityId());
        p.put("ou", d.orgUnitId());
        p.put("loc", d.locationId());
        p.put("job", d.jobTitleId());
        p.put("cat", d.employeeCategoryCode());
        p.put("reg", d.workingRegimeCode());
        p.put("p", d.patternId());
        p.put("f", d.validFrom());
        p.put("to", d.validTo());
        return p;
    }

    private static RuleRow rule(ResultSet rs, int n) throws SQLException {
        RuleData d = new RuleData(rs.getString(2), rs.getInt(3), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class), rs.getObject(11, LocalDate.class),
                rs.getObject(12, LocalDate.class));
        return new RuleRow(rs.getObject(1, UUID.class), d, rs.getString(13), rs.getInt(14));
    }

    // ============================================================================================ assignments

    private static final String ASSIGNMENT_COLUMNS = """
            select id, employment_id, assignment_key, pattern_id, rule_id, valid_from, valid_to, source, status,
                   version
              from att.schedule_assignment""";

    /** ACTIVE schedule assignments of one assignment line, ordered by start. */
    public List<AssignmentRow> activeAssignmentsOfKey(UUID assignmentKey) {
        return db.jdbc().sql(ASSIGNMENT_COLUMNS + " " + """
                         where tenant_id = :t and assignment_key = :k and status = 'ACTIVE' order by valid_from""")
                .param("t", tenant()).param("k", assignmentKey).query(ScheduleAssignmentStore::assignment).list();
    }

    /** Schedule assignments of an employment (all lines); cancelled ones when {@code includeCancelled}. */
    public List<AssignmentRow> assignmentsOfEmployment(UUID employmentId, boolean includeCancelled) {
        return db.jdbc().sql(ASSIGNMENT_COLUMNS + " " + """
                         where tenant_id = :t and employment_id = :e and (:all or status = 'ACTIVE')
                         order by valid_from, assignment_key""")
                .param("t", tenant()).param("e", employmentId).param("all", includeCancelled)
                .query(ScheduleAssignmentStore::assignment).list();
    }

    public Optional<AssignmentRow> assignment(UUID id) {
        return db.jdbc().sql(ASSIGNMENT_COLUMNS + " where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(ScheduleAssignmentStore::assignment).optional();
    }

    public UUID insertAssignment(UUID employmentId, UUID assignmentKey, UUID patternId, UUID ruleId,
            LocalDate validFrom, LocalDate validTo, String source) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.schedule_assignment (tenant_id, id, employment_id, assignment_key, pattern_id,
                                   rule_id, valid_from, valid_to, source, created_by, updated_by)
                            values (:t, :id, :e, :k, :p, :r, :f, :to, :src, :by, :by)""")
                    .param("t", tenant()).param("id", id).param("e", employmentId).param("k", assignmentKey)
                    .param("p", patternId).param("r", ruleId).param("f", validFrom).param("to", validTo)
                    .param("src", source).param("by", actor()).update();
        } catch (DataIntegrityViolationException e) {
            throw StoreErrors.translate(e, "schedule_assignment_period_ex", "schedule-assignment-overlap",
                    "Another schedule is already assigned to this assignment line in that period");
        }
        return id;
    }

    public void cancelAssignment(UUID id) {
        db.jdbc().sql("""
                        update att.schedule_assignment set status = 'CANCELLED', updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id and status = 'ACTIVE'""")
                .param("by", actor()).param("t", tenant()).param("id", id).update();
    }

    /** Shortens or extends an ACTIVE row (validTo exclusive, null = open). */
    public void setAssignmentEnd(UUID id, LocalDate validTo) {
        try {
            db.jdbc().sql("""
                            update att.schedule_assignment set valid_to = :to, updated_by = :by, version = version + 1
                             where tenant_id = :t and id = :id""")
                    .param("to", validTo).param("by", actor()).param("t", tenant()).param("id", id).update();
        } catch (DataIntegrityViolationException e) {
            throw StoreErrors.translate(e, "schedule_assignment_period_ex", "schedule-assignment-overlap",
                    "Another schedule is already assigned to this assignment line in that period");
        }
    }

    private static AssignmentRow assignment(ResultSet rs, int n) throws SQLException {
        return new AssignmentRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class), rs.getString(8),
                rs.getString(9), rs.getInt(10));
    }

    // ============================================================================================ projections

    private static final String LINE_COLUMNS = """
            select id, assignment_key, employment_id, person_id, assignment_type, legal_entity_id, org_unit_id,
                   position_id, job_title_id, location_id, cost_centre_id, manager_employment_id,
                   employee_category_code, working_regime_code, fte, valid_from, valid_to
              from att.ext_hr_assignment""";

    public List<LineVersion> lineVersions(UUID assignmentKey) {
        return db.jdbc().sql(LINE_COLUMNS + " where tenant_id = :t and assignment_key = :k order by valid_from")
                .param("t", tenant()).param("k", assignmentKey).query(ScheduleAssignmentStore::line).list();
    }

    public List<LineVersion> lineVersionsOfEmployment(UUID employmentId) {
        return db.jdbc().sql(LINE_COLUMNS + " where tenant_id = :t and employment_id = :e order by valid_from")
                .param("t", tenant()).param("e", employmentId).query(ScheduleAssignmentStore::line).list();
    }

    /** Distinct assignment lines with a version in {@code legalEntityId} (any legal entity when null). */
    public List<UUID> lineKeys(UUID legalEntityId) {
        return db.jdbc().sql("""
                        select distinct assignment_key from att.ext_hr_assignment
                         where tenant_id = :t and (cast(:le as uuid) is null or legal_entity_id = :le)""")
                .param("t", tenant()).param("le", legalEntityId).query(UUID.class).list();
    }

    /**
     * Assignment-line versions valid on {@code date} in {@code legalEntityId} that no ACTIVE schedule assignment
     * covers on that date, restricted to {@code scope} (US-ATT-011 "no schedule" list).
     */
    public List<LineVersion> unscheduledOn(UUID legalEntityId, LocalDate date, ScopePredicate scope) {
        SqlFragment f = scope.toSql(new Columns("x.legal_entity_id", "x.org_unit_id", "x.location_id",
                "x.employment_id"), "sc");
        return db.jdbc().sql(LINE_COLUMNS.replace("from att.ext_hr_assignment", "from att.ext_hr_assignment x")
                        .replace("select id,", "select x.id,") + " " + """
                         where x.tenant_id = :t and x.legal_entity_id = :le and x.valid_during @> cast(:d as date)
                           and not exists (select 1 from att.schedule_assignment a
                                            where a.tenant_id = x.tenant_id and a.assignment_key = x.assignment_key
                                              and a.status = 'ACTIVE' and a.valid_during @> cast(:d as date))
                        """ + " and " + f.sql() + " order by x.employment_id")
                .param("t", tenant()).param("le", legalEntityId).param("d", date).params(f.params())
                .query(ScheduleAssignmentStore::line).list();
    }

    /**
     * Applies one People assignment version to the projection (Pattern A, no overlap per line): versions of the
     * same line that start inside {@code [validFrom, validTo)} are removed, one that starts before is cut at
     * {@code validFrom}, one that starts at or after {@code validTo} is kept.
     */
    public void applyLineVersion(LineVersion v, UUID sourceEventId) {
        UUID t = tenant();
        db.jdbc().sql("""
                        delete from att.ext_hr_assignment
                         where tenant_id = :t and assignment_key = :k and id <> :id and valid_from >= :f
                           and (cast(:to as date) is null or valid_from < :to)""")
                .param("t", t).param("k", v.assignmentKey()).param("id", v.id()).param("f", v.validFrom())
                .param("to", v.validTo()).update();
        db.jdbc().sql("""
                        update att.ext_hr_assignment set valid_to = :f, synced_at = now()
                         where tenant_id = :t and assignment_key = :k and id <> :id and valid_from < :f
                           and (valid_to is null or valid_to > :f)""")
                .param("t", t).param("k", v.assignmentKey()).param("id", v.id()).param("f", v.validFrom()).update();
        int updated = db.jdbc().sql("""
                        update att.ext_hr_assignment
                           set assignment_key = :k, employment_id = :e, person_id = :p, assignment_type = :type,
                               legal_entity_id = :le, org_unit_id = :ou, position_id = :pos, job_title_id = :job,
                               location_id = :loc, cost_centre_id = :cc, manager_employment_id = :mgr,
                               employee_category_code = :cat, working_regime_code = :reg, fte = :fte,
                               valid_from = :f, valid_to = :to, source_event_id = :ev,
                               source_version = source_version + 1, source_recorded_at = now(), synced_at = now()
                         where tenant_id = :t and id = :id""")
                .params(lineParams(v)).param("ev", sourceEventId).param("t", t).update();
        if (updated == 0) {
            db.jdbc().sql("""
                            insert into att.ext_hr_assignment (tenant_id, id, assignment_key, employment_id, person_id,
                                   assignment_type, legal_entity_id, org_unit_id, position_id, job_title_id,
                                   location_id, cost_centre_id, manager_employment_id, employee_category_code,
                                   working_regime_code, fte, valid_from, valid_to, source_event_id, source_version,
                                   source_recorded_at)
                            values (:t, :id, :k, :e, :p, :type, :le, :ou, :pos, :job, :loc, :cc, :mgr, :cat, :reg,
                                    :fte, :f, :to, :ev, 1, now())""")
                    .params(lineParams(v)).param("ev", sourceEventId).param("t", t).update();
        }
    }

    /**
     * A cancelled future version (People {@code AssignmentChangeCancelled}): removes it and lets the version that
     * ended where it started run on to its end.
     */
    public void removeLineVersion(UUID assignmentKey, UUID versionId) {
        UUID t = tenant();
        List<LineVersion> removed = db.jdbc().sql(LINE_COLUMNS + " where tenant_id = :t and id = :id")
                .param("t", t).param("id", versionId).query(ScheduleAssignmentStore::line).list();
        if (removed.isEmpty()) {
            return;
        }
        LineVersion r = removed.getFirst();
        db.jdbc().sql("delete from att.ext_hr_assignment where tenant_id = :t and id = :id")
                .param("t", t).param("id", versionId).update();
        db.jdbc().sql("""
                        update att.ext_hr_assignment set valid_to = :to, synced_at = now()
                         where tenant_id = :t and assignment_key = :k and valid_to = :f""")
                .param("to", r.validTo()).param("t", t).param("k", assignmentKey).param("f", r.validFrom())
                .update();
    }

    public Optional<EmploymentDates> employment(UUID employmentId) {
        return db.jdbc().sql("""
                        select employment_id, hire_date, termination_date, status from att.ext_hr_employment
                         where tenant_id = :t and employment_id = :e""")
                .param("t", tenant()).param("e", employmentId)
                .query((rs, n) -> new EmploymentDates(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class), rs.getString(4)))
                .optional();
    }

    /** The employment facts T&amp;A keeps ({@code att.ext_hr_employment}, latest state, D5). */
    public record EmploymentFacts(UUID employmentId, UUID personId, UUID legalEntityId, LocalDate hireDate,
            LocalDate seniorityDate, LocalDate probationEndDate, LocalDate lastWorkingDate, LocalDate terminationDate,
            String status, String workerCategoryCode, String employeeCategoryCode) {
    }

    /** Inserts or replaces the employment row; {@code source_version} counts the applied events. */
    public void upsertEmployment(EmploymentFacts f, UUID sourceEventId) {
        db.jdbc().sql("""
                        insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id,
                               hire_date, seniority_date, probation_end_date, last_working_date, termination_date,
                               status, worker_category_code, employee_category_code, source_event_id, source_version)
                        values (:t, :e, :p, :le, :hire, :sen, :prob, :lwd, :term, :st, :wc, :ec, :ev, 1)
                        on conflict (tenant_id, employment_id) do update
                           set person_id = excluded.person_id, legal_entity_id = excluded.legal_entity_id,
                               hire_date = excluded.hire_date, seniority_date = excluded.seniority_date,
                               probation_end_date = coalesce(excluded.probation_end_date,
                                                             att.ext_hr_employment.probation_end_date),
                               last_working_date = excluded.last_working_date,
                               termination_date = excluded.termination_date, status = excluded.status,
                               worker_category_code = excluded.worker_category_code,
                               employee_category_code = excluded.employee_category_code,
                               source_event_id = excluded.source_event_id,
                               source_version = att.ext_hr_employment.source_version + 1,
                               synced_at = now(), updated_at = now()""")
                .param("t", tenant()).param("e", f.employmentId()).param("p", f.personId())
                .param("le", f.legalEntityId()).param("hire", f.hireDate()).param("sen", f.seniorityDate())
                .param("prob", f.probationEndDate()).param("lwd", f.lastWorkingDate())
                .param("term", f.terminationDate()).param("st", f.status()).param("wc", f.workerCategoryCode())
                .param("ec", f.employeeCategoryCode()).param("ev", sourceEventId).update();
    }

    public Optional<EmploymentFacts> employmentFacts(UUID employmentId) {
        return db.jdbc().sql("""
                        select employment_id, person_id, legal_entity_id, hire_date, seniority_date, probation_end_date,
                               last_working_date, termination_date, status, worker_category_code,
                               employee_category_code
                          from att.ext_hr_employment where tenant_id = :t and employment_id = :e""")
                .param("t", tenant()).param("e", employmentId)
                .query((rs, n) -> new EmploymentFacts(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class), rs.getString(9),
                        rs.getString(10), rs.getString(11)))
                .optional();
    }

    /**
     * Employee category and contractual working regime live on the employment (People): copied onto every
     * assignment-line version of it, for the schedule rules. {@code workingRegimeCode} null keeps the stored value.
     */
    public void setLineCategories(UUID employmentId, String employeeCategoryCode, String workingRegimeCode) {
        db.jdbc().sql("""
                        update att.ext_hr_assignment
                           set employee_category_code = :cat,
                               working_regime_code = coalesce(cast(:reg as text), working_regime_code),
                               synced_at = now()
                         where tenant_id = :t and employment_id = :e""")
                .param("cat", employeeCategoryCode).param("reg", workingRegimeCode).param("t", tenant())
                .param("e", employmentId).update();
    }

    // ------------------------------------------------------------------------- D3: dated category / regime

    /** One row of {@code att.ext_hr_employment_classification}; {@code validTo} EXCLUSIVE, null = open. */
    public record Classification(UUID id, UUID employmentId, String employeeCategoryCode, String workingRegimeCode,
            LocalDate validFrom, LocalDate validTo) {

        public boolean covers(LocalDate d) {
            return !d.isBefore(validFrom) && (validTo == null || d.isBefore(validTo));
        }
    }

    /** Rows of the employment, oldest first. */
    public List<Classification> classifications(UUID employmentId) {
        return db.jdbc().sql("""
                        select id, employment_id, employee_category_code, working_regime_code, valid_from, valid_to
                          from att.ext_hr_employment_classification
                         where tenant_id = :t and employment_id = :e order by valid_from""")
                .param("t", tenant()).param("e", employmentId)
                .query((rs, n) -> new Classification(rs.getObject("id", UUID.class),
                        rs.getObject("employment_id", UUID.class), rs.getString("employee_category_code"),
                        rs.getString("working_regime_code"), rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class)))
                .list();
    }

    /**
     * Sets exactly {@code [from, to)} (to null = open) to these values: rows inside it are removed, rows crossing its
     * edges are trimmed (a row spanning the whole range keeps its part after {@code to}). Idempotent.
     */
    public void overwriteClassification(UUID employmentId, LocalDate from, LocalDate to, String employeeCategory,
            String workingRegime, UUID sourceEventId) {
        for (Classification c : classifications(employmentId)) {
            boolean endsAfterFrom = c.validTo() == null || c.validTo().isAfter(from);
            boolean startsBeforeTo = to == null || c.validFrom().isBefore(to);
            if (!endsAfterFrom || !startsBeforeTo) {
                continue;
            }
            boolean keepsHead = c.validFrom().isBefore(from);
            boolean keepsTail = to != null && (c.validTo() == null || c.validTo().isAfter(to));
            deleteClassification(c.id());
            if (keepsHead) {
                insertClassification(employmentId, c.employeeCategoryCode(), c.workingRegimeCode(), c.validFrom(),
                        from, null);
            }
            if (keepsTail) {
                insertClassification(employmentId, c.employeeCategoryCode(), c.workingRegimeCode(), to,
                        c.validTo(), null);
            }
        }
        insertClassification(employmentId, employeeCategory, workingRegime, from, to, sourceEventId);
    }

    private void deleteClassification(UUID id) {
        db.jdbc().sql("delete from att.ext_hr_employment_classification where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).update();
    }

    private void insertClassification(UUID employmentId, String employeeCategory, String workingRegime,
            LocalDate from, LocalDate to, UUID sourceEventId) {
        db.jdbc().sql("""
                        insert into att.ext_hr_employment_classification (tenant_id, id, employment_id,
                               employee_category_code, working_regime_code, valid_from, valid_to, source_event_id)
                        values (:t, :id, :e, :ec, :wr, :vf, :vt, :src)""")
                .param("t", tenant()).param("id", Ids.newId()).param("e", employmentId)
                .param("ec", employeeCategory).param("wr", workingRegime).param("vf", from).param("vt", to)
                .param("src", sourceEventId).update();
    }

    /** Distinct assignment lines of the employment. */
    public List<UUID> lineKeysOfEmployment(UUID employmentId) {
        return db.jdbc().sql("""
                        select distinct assignment_key from att.ext_hr_assignment
                         where tenant_id = :t and employment_id = :e""")
                .param("t", tenant()).param("e", employmentId).query(UUID.class).list();
    }

    private static Map<String, Object> lineParams(LineVersion v) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", v.id());
        p.put("k", v.assignmentKey());
        p.put("e", v.employmentId());
        p.put("p", v.personId());
        p.put("type", v.assignmentType());
        p.put("le", v.legalEntityId());
        p.put("ou", v.orgUnitId());
        p.put("pos", v.positionId());
        p.put("job", v.jobTitleId());
        p.put("loc", v.locationId());
        p.put("cc", v.costCentreId());
        p.put("mgr", v.managerEmploymentId());
        p.put("cat", v.employeeCategoryCode());
        p.put("reg", v.workingRegimeCode());
        p.put("fte", v.fte());
        p.put("f", v.validFrom());
        p.put("to", v.validTo());
        return p;
    }

    private static LineVersion line(ResultSet rs, int n) throws SQLException {
        return new LineVersion(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, UUID.class),
                rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                rs.getObject(10, UUID.class), rs.getObject(11, UUID.class), rs.getObject(12, UUID.class),
                rs.getString(13), rs.getString(14), rs.getBigDecimal(15), rs.getObject(16, LocalDate.class),
                rs.getObject(17, LocalDate.class));
    }
}
