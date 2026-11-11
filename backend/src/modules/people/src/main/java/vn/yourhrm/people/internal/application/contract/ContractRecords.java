package vn.yourhrm.people.internal.application.contract;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.domain.contract.ContractType;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Reads of {@code hr.contract} and of the employment it belongs to, shared by the contract services. Runs in (or
 * joins) a People transaction; no permission check (the calling service did it).
 */
@Component
@HrTransactional(readOnly = true)
public class ContractRecords {

    /** Statuses of a contract that is (or was) in force: they take part in the overlap and counting rules. */
    public static final List<String> LIVE = List.of("ACTIVE", "SUSPENDED", "EXPIRED", "TERMINATED");

    /** The employment a contract belongs to (People's own row). */
    public record Employment(UUID id, UUID personId, UUID legalEntityId, String employeeNo, String status,
            LocalDate hireDate, String workerCategoryCode, int version) {

        public boolean closed() {
            return "TERMINATED".equals(status) || "CANCELLED".equals(status);
        }
    }

    /**
     * A contract with its employment's legal entity and person. {@code probationEndDate}: last probation day (the end
     * of a PROBATION agreement, or start + probation days - 1 for a probation clause), null without probation.
     * {@code probationLevelCode}: the level whose legal maximum applied. {@code probationResult} / {@code
     * probationResultDate} / {@code probationResultDecisionId}: PASS / FAIL once recorded (US-PPL-049, BR-PPL-052).
     * {@code definiteExceptionCode} / {@code definiteExceptionNote}: the legal exception that allowed a further definite
     * contract (BR-PPL-053). {@code autoConversionDueSince}: first day the contract counts as converted to indefinite,
     * set by the daily monitor (BR-PPL-054). {@code source}: APP | OPENING_LOAD | IMPORT | REC_HANDOFF.
     */
    public record ContractView(UUID id, UUID employmentId, UUID legalEntityId, UUID personId, String employeeNo,
            String contractNo, String contractTypeCode, Integer definiteSeq, LocalDate signDate, LocalDate startDate,
            LocalDate endDate, Integer probationDays, String probationLevelCode, LocalDate probationEndDate,
            String probationResult, LocalDate probationResultDate, UUID probationResultDecisionId,
            String signatoryName, String signatoryTitle, UUID documentId, UUID previousContractId,
            String definiteExceptionCode, String definiteExceptionNote, LocalDate autoConversionDueSince,
            String source, String status, Instant createdAt, Instant updatedAt, int version) {

        public ContractType type() {
            return ContractType.of(contractTypeCode).orElseThrow();
        }

        public boolean hasProbation() {
            return probationEndDate != null;
        }
    }

    static final String SELECT = """
            select c.id, c.employment_id, e.legal_entity_id, e.person_id, e.employee_no, c.contract_no,
                   c.contract_type_code, c.definite_seq, c.sign_date, c.start_date, c.end_date, c.probation_days,
                   c.probation_level_code, c.probation_result, c.probation_result_date,
                   c.probation_result_decision_id, c.signatory_name, c.signatory_title, c.document_id,
                   c.previous_contract_id, c.definite_exception_code, c.definite_exception_note,
                   c.auto_conversion_due_since, c.source, c.status, c.created_at, c.updated_at, c.version
              from hr.contract c
              join hr.employment e on e.tenant_id = c.tenant_id and e.id = c.employment_id
            """;

    private final HrDb db;

    public ContractRecords(HrDb db) {
        this.db = db;
    }

    public Optional<Employment> employment(UUID employmentId) {
        return db.jdbc().sql("""
                        select id, person_id, legal_entity_id, employee_no, status, hire_date, worker_category_code, version
                          from hr.employment where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", employmentId)
                .query((rs, n) -> new Employment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getString(7), rs.getInt(8)))
                .optional();
    }

    public Employment requireEmployment(UUID employmentId) {
        return employment(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
    }

    public Optional<ContractView> find(UUID contractId) {
        return db.jdbc().sql(SELECT + " where c.tenant_id = :t and c.id = :id")
                .param("t", tenant()).param("id", contractId).query(ContractRecords::map).optional();
    }

    public ContractView require(UUID contractId) {
        return find(contractId).orElseThrow(() -> ApiException.notFound("Contract", contractId));
    }

    /** Every contract of the employment, oldest first. */
    public List<ContractView> ofEmployment(UUID employmentId) {
        return db.jdbc().sql(SELECT + " where c.tenant_id = :t and c.employment_id = :e order by c.start_date, c.created_at")
                .param("t", tenant()).param("e", employmentId).query(ContractRecords::map).list();
    }

    /** Contracts of the employment in force or once in force (not DRAFT / CANCELLED / SUPERSEDED), newest first. */
    public List<ContractView> liveOfEmployment(UUID employmentId) {
        return db.jdbc().sql(SELECT + """
                         where c.tenant_id = :t and c.employment_id = :e
                           and c.status in ('ACTIVE','SUSPENDED','EXPIRED','TERMINATED')
                         order by c.start_date desc""")
                .param("t", tenant()).param("e", employmentId).query(ContractRecords::map).list();
    }

    static ContractView map(ResultSet rs, int n) throws SQLException {
        LocalDate start = rs.getObject("start_date", LocalDate.class);
        LocalDate end = rs.getObject("end_date", LocalDate.class);
        String type = rs.getString("contract_type_code");
        Integer probationDays = rs.getObject("probation_days", Integer.class);
        LocalDate probationEnd = null;
        if ("PROBATION".equals(type)) {
            probationEnd = end;
        } else if (probationDays != null && probationDays > 0) {
            probationEnd = start.plusDays(probationDays - 1L);
        }
        return new ContractView(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getObject("legal_entity_id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getString("employee_no"), rs.getString("contract_no"), type,
                rs.getObject("definite_seq", Integer.class), rs.getObject("sign_date", LocalDate.class),
                start, end, probationDays, rs.getString("probation_level_code"), probationEnd,
                rs.getString("probation_result"), rs.getObject("probation_result_date", LocalDate.class),
                rs.getObject("probation_result_decision_id", UUID.class),
                rs.getString("signatory_name"), rs.getString("signatory_title"),
                rs.getObject("document_id", UUID.class), rs.getObject("previous_contract_id", UUID.class),
                rs.getString("definite_exception_code"), rs.getString("definite_exception_note"),
                rs.getObject("auto_conversion_due_since", LocalDate.class), rs.getString("source"),
                rs.getString("status"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
