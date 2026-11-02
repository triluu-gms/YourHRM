package vn.yourhrm.people.internal.application.decision;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.DocumentGeneration;
import vn.yourhrm.core.api.DocumentGeneration.GenerateRequest;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.core.api.JobCatalogue;
import vn.yourhrm.people.internal.application.DecisionService.Decision;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Generates a decision from the tenant's template {@code HR_DECISION_<TYPE>} (US-PPL-058; BR-CORE-102): the common
 * merge fields plus those of the type's {@link DecisionMergeFields}; Core adds the legal entity's fields and renders
 * PDF/A with Vietnamese fonts. The decision number is People's ({@code decision.no}); templates carry no number
 * series. No permission check: {@link DecisionRegister} authorised the caller.
 */
@Service
public class DecisionDocumentService {

    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HrDb db;
    private final DecisionDocuments definitions;
    private final DocumentGeneration generation;
    private final JobCatalogue jobs;
    private final BusinessClock clock;

    public DecisionDocumentService(HrDb db, DecisionDocuments definitions, DocumentGeneration generation,
            JobCatalogue jobs, BusinessClock clock) {
        this.db = db;
        this.definitions = definitions;
        this.generation = generation;
        this.jobs = jobs;
        this.clock = clock;
    }

    @HrTransactional(readOnly = true)
    public Generated generate(Decision d) {
        if ("CANCELLED".equals(d.status())) {
            throw ApiException.unprocessable("decision-cancelled", "Decision " + d.decisionNo() + " is cancelled");
        }
        Map<String, Object> data = fields(d);
        definitions.providers(d.decisionTypeCode()).forEach(p -> data.putAll(p.values(d)));
        UUID person = data.remove("_person") instanceof UUID p ? p : null;
        return generation.generate(new GenerateRequest(DecisionDocuments.TEMPLATE_PREFIX + d.decisionTypeCode(),
                d.legalEntityId(), d.id(), person, "QĐ " + d.decisionNo(), clock.today(), data));
    }

    Map<String, Object> fields(Decision d) {
        Map<String, Object> f = new HashMap<>();
        String[] name = DecisionDocuments.TYPE_NAMES.getOrDefault(d.decisionTypeCode(),
                new String[] {d.decisionTypeCode(), d.decisionTypeCode()});
        f.put("decision.no", d.decisionNo());
        f.put("decision.type_vi", name[0]);
        f.put("decision.type_en", name[1]);
        f.put("decision.effective_date", VN.format(d.effectiveDate()));
        f.put("decision.sign_date", d.signDate() == null ? "" : VN.format(d.signDate()));
        f.put("signatory.name", nz(d.signatoryName()));
        f.put("signatory.title", nz(d.signatoryTitle()));
        f.put("employee.full_name", "");
        f.put("employee.no", "");
        f.put("employee.birth_date", "");
        f.put("job.title_vi", "");
        f.put("job.title_en", "");
        if (d.employmentId() == null) {
            return f;
        }
        UUID tenant = TenantContext.current().tenantId();
        db.jdbc().sql("""
                        select p.id, p.full_name, p.birth_date, e.employee_no
                          from hr.employment e join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id
                         where e.tenant_id = :t and e.id = :e""")
                .param("t", tenant).param("e", d.employmentId())
                .query(rs -> {
                    f.put("_person", rs.getObject(1, UUID.class));
                    f.put("employee.full_name", nz(rs.getString(2)));
                    LocalDate birth = rs.getObject(3, LocalDate.class);
                    f.put("employee.birth_date", birth == null ? "" : VN.format(birth));
                    f.put("employee.no", nz(rs.getString(4)));
                });
        UUID jobTitle = db.jdbc().sql("""
                        select job_title_id from hr.assignment
                         where tenant_id = :t and employment_id = :e and assignment_type = 'PRIMARY' and superseded_at is null
                         order by (valid_during @> cast(:d as date)) desc, valid_from desc limit 1""")
                .param("t", tenant).param("e", d.employmentId()).param("d", d.effectiveDate())
                .query(UUID.class).optional().orElse(null);
        JobCatalogue.JobTitle jt = jobTitle == null ? null : jobs.jobTitles(List.of(jobTitle)).get(jobTitle);
        if (jt != null) {
            f.put("job.title_vi", nz(jt.nameVi()));
            f.put("job.title_en", nz(jt.nameEn() != null ? jt.nameEn() : jt.nameVi()));
        }
        return f;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
