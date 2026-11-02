package vn.yourhrm.people.internal.application.contract;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.DocumentGeneration;
import vn.yourhrm.core.api.DocumentGeneration.GenerateRequest;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.core.api.JobCatalogue;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.contract.ContractAppendixService.AppendixView;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Contract and appendix documents from the tenant's bilingual templates (BR-PPL-057, US-PPL-055; BR-CORE-102): the
 * merge-field provider of {@link ContractDocuments#CONTRACT_TEMPLATE} / {@link ContractDocuments#APPENDIX_TEMPLATE}.
 * Core picks the template version of the legal entity, adds the legal entity's fields (name, tax code, address,
 * legal representative, letterhead, seal) and renders PDF/A with Vietnamese fonts; the file is stored on the contract
 * / appendix. The contract number is the People one ({@code contract.no}); templates should have no number series.
 *
 * <p>No P3 value is merged (no national id): a template needing it must wait for a sensitive-field provider.
 * Signatory: the one recorded on the contract, else the template falls back to Core's {@code le.legal_rep_*} (Core
 * keeps no list of authorised signatories per legal entity yet).
 */
@Service
public class ContractDocumentService {

    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final Map<String, String[]> TYPE_NAMES = Map.of(
            "PROBATION", new String[] {"Hợp đồng thử việc", "Probation agreement"},
            "DEFINITE", new String[] {"Hợp đồng lao động xác định thời hạn", "Definite-term labour contract"},
            "INDEFINITE", new String[] {"Hợp đồng lao động không xác định thời hạn", "Indefinite-term labour contract"},
            "SEASONAL", new String[] {"Hợp đồng lao động thời vụ (dưới 1 tháng)", "Seasonal labour contract (under 1 month)"},
            "APPRENTICE", new String[] {"Hợp đồng học nghề", "Apprenticeship contract"},
            "INTERNSHIP", new String[] {"Hợp đồng thực tập", "Internship agreement"},
            "SERVICE", new String[] {"Hợp đồng dịch vụ / cộng tác viên", "Service / collaborator agreement"});

    private static final Map<String, String[]> CHANGE_LABELS = Map.of(
            "salary", new String[] {"Tiền lương", "Salary"},
            "allowances", new String[] {"Phụ cấp", "Allowances"},
            "jobTitleId", new String[] {"Chức danh", "Job title"},
            "positionId", new String[] {"Vị trí", "Position"},
            "orgUnitId", new String[] {"Đơn vị", "Organisation unit"},
            "locationId", new String[] {"Địa điểm làm việc", "Work location"},
            "workLocation", new String[] {"Địa điểm làm việc", "Work location"},
            "workingTime", new String[] {"Thời giờ làm việc", "Working time"},
            "other", new String[] {"Nội dung khác", "Other terms"});

    private final HrDb db;
    private final ContractRecords records;
    private final ContractAppendixService appendices;
    private final EmployeeAccess access;
    private final DocumentGeneration generation;
    private final JobCatalogue jobs;
    private final BusinessClock clock;

    public ContractDocumentService(HrDb db, ContractRecords records, ContractAppendixService appendices,
            EmployeeAccess access, DocumentGeneration generation, JobCatalogue jobs, BusinessClock clock) {
        this.db = db;
        this.records = records;
        this.appendices = appendices;
        this.access = access;
        this.generation = generation;
        this.jobs = jobs;
        this.clock = clock;
    }

    /** Generates the contract document (draft for printing and signing). */
    @HrTransactional(readOnly = true)
    public Generated generateContract(UUID contractId) {
        ContractView c = records.require(contractId);
        access.require(PeoplePermissions.CONTRACT_UPDATE, c.employmentId(), clock.today());
        return generation.generate(new GenerateRequest(ContractDocuments.CONTRACT_TEMPLATE, c.legalEntityId(), c.id(),
                c.personId(), "HĐLĐ " + c.contractNo(), clock.today(), contractFields(c)));
    }

    /** Generates the appendix document; pay terms are printed, so it needs {@code hr.compensation.view} when present. */
    @HrTransactional(readOnly = true)
    public Generated generateAppendix(UUID appendixId) {
        AppendixView a = appendices.find(appendixId).orElseThrow(() -> ApiException.notFound("Appendix", appendixId));
        access.require(PeoplePermissions.CONTRACT_UPDATE, a.employmentId(), clock.today());
        if (a.changes().keySet().stream().anyMatch(ContractAppendixService.PAY_KEYS::contains)) {
            access.require(PeoplePermissions.COMPENSATION_VIEW, a.employmentId(), clock.today());
        }
        ContractView c = records.require(a.contractId());
        Map<String, Object> data = contractFields(c);
        data.put("appendix.no", a.appendixNo());
        data.put("appendix.effective_date", VN.format(a.effectiveDate()));
        data.put("appendix.changes_vi", changes(a.changes(), 0));
        data.put("appendix.changes_en", changes(a.changes(), 1));
        return generation.generate(new GenerateRequest(ContractDocuments.APPENDIX_TEMPLATE, c.legalEntityId(), a.id(),
                c.personId(), "Phụ lục " + a.appendixNo(), clock.today(), data));
    }

    /** The contract merge fields (all present; empty text when unknown). */
    Map<String, Object> contractFields(ContractView c) {
        Map<String, Object> f = new HashMap<>();
        db.jdbc().sql("""
                        select full_name, birth_date, gender_code, nationality_code from hr.person
                         where tenant_id = :t and id = :p""")
                .param("t", TenantContext.current().tenantId()).param("p", c.personId())
                .query(rs -> {
                    f.put("employee.full_name", nz(rs.getString(1)));
                    LocalDate birth = rs.getObject(2, LocalDate.class);
                    f.put("employee.birth_date", birth == null ? "" : VN.format(birth));
                    f.put("employee.gender", gender(rs.getString(3)));
                    f.put("employee.nationality", nz(rs.getString(4)));
                });
        f.put("employee.no", nz(c.employeeNo()));
        f.put("contract.no", c.contractNo());
        String[] type = TYPE_NAMES.getOrDefault(c.contractTypeCode(), new String[] {c.contractTypeCode(), c.contractTypeCode()});
        f.put("contract.type_vi", type[0]);
        f.put("contract.type_en", type[1]);
        f.put("contract.start_date", VN.format(c.startDate()));
        f.put("contract.end_date", c.endDate() == null ? "" : VN.format(c.endDate()));
        f.put("contract.probation_days", c.probationDays() == null ? "" : String.valueOf(c.probationDays()));
        f.put("contract.sign_date", c.signDate() == null ? "" : VN.format(c.signDate()));
        f.put("signatory.name", nz(c.signatoryName()));
        f.put("signatory.title", nz(c.signatoryTitle()));
        UUID jobTitle = db.jdbc().sql("""
                        select job_title_id from hr.assignment
                         where tenant_id = :t and employment_id = :e and assignment_type = 'PRIMARY' and superseded_at is null
                         order by (valid_during @> cast(:d as date)) desc, valid_from desc limit 1""")
                .param("t", TenantContext.current().tenantId()).param("e", c.employmentId()).param("d", c.startDate())
                .query(UUID.class).optional().orElse(null);
        JobCatalogue.JobTitle jt = jobTitle == null ? null : jobs.jobTitles(List.of(jobTitle)).get(jobTitle);
        f.put("job.title_vi", jt == null ? "" : nz(jt.nameVi()));
        f.put("job.title_en", jt == null ? "" : nz(jt.nameEn() != null ? jt.nameEn() : jt.nameVi()));
        return f;
    }

    private static String changes(Map<String, Object> changes, int lang) {
        return changes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> CHANGE_LABELS.getOrDefault(e.getKey(), new String[] {e.getKey(), e.getKey()})[lang] + ": "
                        + e.getValue())
                .collect(Collectors.joining("; "));
    }

    private static String gender(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "M" -> "Nam / Male";
            case "F" -> "Nữ / Female";
            default -> code;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
