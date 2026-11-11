package vn.yourhrm.people.internal.infra.employment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/** SQL access to {@code hr.employment} for the employment stream (Pattern: soft references, optimistic version). */
@Component
public class EmploymentStore {

    /** One {@code hr.employment} row; {@code endDate} INCLUSIVE. */
    public record Row(UUID id, UUID personId, UUID legalEntityId, String employeeNo, String hireType,
            LocalDate hireDate, LocalDate originalHireDate, LocalDate seniorityDate, LocalDate probationEndDate,
            LocalDate endDate, LocalDate lastWorkingDate, String status, String workerCategoryCode,
            String employeeCategoryCode, String workingRegimeCode, Boolean rehireEligible, UUID previousEmploymentId,
            String source, int version) {

        public boolean live() {
            return "PRE_HIRE".equals(status) || "ACTIVE".equals(status) || "SUSPENDED".equals(status);
        }

        /** Is {@code d} inside the employment period (and the employment not cancelled)? */
        public boolean covers(LocalDate d) {
            return !"CANCELLED".equals(status) && !d.isBefore(hireDate) && (endDate == null || !d.isAfter(endDate));
        }

        /** First day after the employment (exclusive end), null when open. */
        public LocalDate endExclusive() {
            return endDate == null ? null : endDate.plusDays(1);
        }
    }

    private final HrDb db;

    public EmploymentStore(HrDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    public Optional<Row> find(UUID id) {
        return db.jdbc().sql("select * from hr.employment where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(EmploymentStore::map).optional();
    }

    /** Locks the row for the rest of the transaction. */
    public Optional<Row> lock(UUID id) {
        return db.jdbc().sql("select * from hr.employment where tenant_id = :t and id = :id for update")
                .param("t", tenant()).param("id", id).query(EmploymentStore::map).optional();
    }

    public List<Row> ofPerson(UUID personId) {
        return db.jdbc().sql("select * from hr.employment where tenant_id = :t and person_id = :p order by hire_date, id")
                .param("t", tenant()).param("p", personId).query(EmploymentStore::map).list();
    }

    /** Serialises changes of one person's employments (hire, primary, insurance) until the transaction ends. */
    public void lockPerson(UUID personId) {
        db.jdbc().sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", "hr.person." + personId)
                .query((rs, n) -> 1).single();
    }

    public void insert(Row r, UUID actor) {
        db.jdbc().sql("""
                        insert into hr.employment (tenant_id, id, person_id, legal_entity_id, employee_no, hire_type,
                               hire_date, original_hire_date, seniority_date, probation_end_date, status,
                               worker_category_code, employee_category_code, working_regime_code, is_rehire_eligible,
                               previous_employment_id, source, created_by, updated_by)
                        values (:t, :id, :p, :le, :no, :ht, :hd, :ohd, :sd, :ped, :st, :wc, :ec, :wr, :re, :prev, :src,
                                :actor, :actor)""")
                .param("t", tenant()).param("id", r.id()).param("p", r.personId()).param("le", r.legalEntityId())
                .param("no", r.employeeNo()).param("ht", r.hireType()).param("hd", r.hireDate())
                .param("ohd", r.originalHireDate()).param("sd", r.seniorityDate()).param("ped", r.probationEndDate())
                .param("st", r.status()).param("wc", r.workerCategoryCode()).param("ec", r.employeeCategoryCode())
                .param("wr", r.workingRegimeCode()).param("re", r.rehireEligible())
                .param("prev", r.previousEmploymentId()).param("src", r.source()).param("actor", actor)
                .update();
    }

    /** Status transition guarded by the expected current status; returns false when the row was not in {@code from}. */
    public boolean transition(UUID id, String from, String to, UUID actor) {
        return db.jdbc().sql("""
                        update hr.employment set status = :to, version = version + 1, updated_by = :actor,
                               updated_at = now()
                         where tenant_id = :t and id = :id and status = :from""")
                .param("to", to).param("actor", actor).param("t", tenant()).param("id", id).param("from", from)
                .update() == 1;
    }

    /** PUT of the HR-maintained attributes with optimistic version; false when stale. */
    public boolean updateAttributes(UUID id, int version, String workerCategory, String employeeCategory,
            String workingRegime, LocalDate originalHireDate, LocalDate seniorityDate, LocalDate probationEndDate,
            UUID actor) {
        return db.jdbc().sql("""
                        update hr.employment set worker_category_code = :wc, employee_category_code = :ec,
                               working_regime_code = :wr, original_hire_date = :ohd, seniority_date = :sd,
                               probation_end_date = :ped, version = version + 1, updated_by = :actor, updated_at = now()
                         where tenant_id = :t and id = :id and version = :v""")
                .param("wc", workerCategory).param("ec", employeeCategory).param("wr", workingRegime)
                .param("ohd", originalHireDate).param("sd", seniorityDate).param("ped", probationEndDate)
                .param("actor", actor).param("t", tenant()).param("id", id).param("v", version)
                .update() == 1;
    }

    public static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getObject("legal_entity_id", UUID.class), rs.getString("employee_no"), rs.getString("hire_type"),
                rs.getObject("hire_date", LocalDate.class), rs.getObject("original_hire_date", LocalDate.class),
                rs.getObject("seniority_date", LocalDate.class), rs.getObject("probation_end_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getObject("last_working_date", LocalDate.class),
                rs.getString("status"), rs.getString("worker_category_code"), rs.getString("employee_category_code"),
                rs.getString("working_regime_code"), (Boolean) rs.getObject("is_rehire_eligible"),
                rs.getObject("previous_employment_id", UUID.class), rs.getString("source"), rs.getInt("version"));
    }
}
