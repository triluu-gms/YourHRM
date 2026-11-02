package vn.yourhrm.people.internal.application.assignment;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/** Read-only facts of {@code hr.employment} needed by the assignment and salary flows. */
@Component
@HrTransactional(readOnly = true)
public class EmploymentFacts {

    /** {@code endDate} is INCLUSIVE (DDL). */
    public record Employment(UUID id, UUID personId, UUID legalEntityId, String employeeNo, String status,
            LocalDate hireDate, LocalDate endDate, String fullName) {

        public boolean live() {
            return !"CANCELLED".equals(status) && !"TERMINATED".equals(status);
        }

        /** Is {@code d} inside the employment period? */
        public boolean covers(LocalDate d) {
            return !d.isBefore(hireDate) && (endDate == null || !d.isAfter(endDate));
        }
    }

    private final HrDb db;

    public EmploymentFacts(HrDb db) {
        this.db = db;
    }

    public Optional<Employment> find(UUID employmentId) {
        return db.jdbc().sql("""
                        select e.id, e.person_id, e.legal_entity_id, e.employee_no, e.status, e.hire_date, e.end_date,
                               p.full_name
                          from hr.employment e join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id
                         where e.tenant_id = :t and e.id = :id""")
                .param("t", TenantContext.current().tenantId()).param("id", employmentId)
                .query((rs, n) -> new Employment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class), rs.getString(8)))
                .optional();
    }

    public Employment require(UUID employmentId) {
        return find(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
    }
}
