package vn.yourhrm.people.internal.application.separation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.SeparationEvents.ServicePeriod;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Reads and service periods of {@code hr.separation} / {@code hr.separation_service_period} shared by the separation
 * services, the daily job and the decision merge fields. Joins the caller's People transaction.
 */
@Component
public class SeparationRecords {

    /** Statuses that are not live (DDL {@code separation_live_uq}). */
    public static final List<String> CLOSED = List.of("WITHDRAWN", "REJECTED", "CANCELLED");
    /** Marker of the service periods People derives itself (refreshed on every change); HR rows have no marker. */
    static final String AUTO = "auto:";
    /** US-PPL-061: access is revoked by default at 23:59 of the revocation day, business time zone. */
    static final LocalTime REVOCATION_TIME = LocalTime.of(23, 59);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public record ServicePeriodView(UUID id, String periodKind, LocalDate periodFrom, LocalDate periodTo,
            UUID sourceEmploymentId, String note, boolean derived, int version) {

        public ServicePeriod event() {
            return new ServicePeriod(periodKind, periodFrom, periodTo, sourceEmploymentId);
        }
    }

    /**
     * A separation with its employee. {@code employmentPhase}: NOTICE between approval and termination (the DDL has no
     * NOTICE employment status, so it is derived here), TERMINATED once completed, else the employment status.
     * {@code accessRevocationAt}: the revocation date at 23:59 business time (US-PPL-061). {@code specialTradeCode} /
     * {@code noticeExemptReasonCode}: the notice inputs (BR-PPL-071); {@code employeeNote}: the note of an ESS
     * resignation (BR-PPL-072); {@code noPersonalEmail}: HR recorded that the leaver has no personal e-mail (OD-8);
     * {@code signatoryName} / {@code signatoryTitle}: signatory of the termination decision.
     */
    public record SeparationView(UUID id, UUID employmentId, UUID personId, UUID legalEntityId, String employeeNo,
            String fullName, String status, String employmentStatus, String employmentPhase, String separationType,
            String initiatedBy, String legalGroundCode, String reasonCode, LocalDate requestDate, LocalDate noticeDate,
            Integer requiredNoticeDays, String noticeDayBasis, Integer noticeShortfallDays, String specialTradeCode,
            String noticeExemptReasonCode, LocalDate lastWorkingDate,
            LocalDate terminationDate, LocalDate accessRevocationDate, OffsetDateTime accessRevocationAt,
            String severanceType, String severanceEligibility, boolean protectedEmployee,
            String protectedOverrideReason, UUID protectedOverrideDocumentId, Boolean rehireEligible,
            String employeeNote, boolean noPersonalEmail, String signatoryName, String signatoryTitle,
            UUID payrollResultRef, LocalDate settlementDueDate, UUID decisionId, UUID workflowInstanceId,
            Instant withdrawnAt, Instant createdAt, List<ServicePeriodView> servicePeriods, int version) {

        public boolean live() {
            return !CLOSED.contains(status);
        }

        SeparationView withPeriods(List<ServicePeriodView> periods) {
            return new SeparationView(id, employmentId, personId, legalEntityId, employeeNo, fullName, status,
                    employmentStatus, employmentPhase, separationType, initiatedBy, legalGroundCode, reasonCode,
                    requestDate, noticeDate, requiredNoticeDays, noticeDayBasis, noticeShortfallDays, specialTradeCode,
                    noticeExemptReasonCode, lastWorkingDate, terminationDate, accessRevocationDate, accessRevocationAt,
                    severanceType, severanceEligibility, protectedEmployee, protectedOverrideReason,
                    protectedOverrideDocumentId, rehireEligible, employeeNote, noPersonalEmail, signatoryName,
                    signatoryTitle, payrollResultRef, settlementDueDate, decisionId, workflowInstanceId, withdrawnAt, createdAt,
                    List.copyOf(periods), version);
        }
    }

    static final String SELECT = """
            select s.*, e.person_id, e.legal_entity_id, e.employee_no, e.status as employment_status, p.full_name
              from hr.separation s
              join hr.employment e on e.tenant_id = s.tenant_id and e.id = s.employment_id
              join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id
            """;

    private final HrDb db;

    public SeparationRecords(HrDb db) {
        this.db = db;
    }

    public Optional<SeparationView> find(UUID id) {
        return db.jdbc().sql(SELECT + " where s.tenant_id = :t and s.id = :id")
                .param("t", tenant()).param("id", id).query(SeparationRecords::map).optional()
                .map(v -> v.withPeriods(periods(v.id())));
    }

    public SeparationView require(UUID id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Separation", id));
    }

    /** Locks the row for the rest of the transaction (state transitions, workflow callbacks, the daily job). */
    public SeparationView lock(UUID id) {
        db.jdbc().sql("select id from hr.separation where tenant_id = :t and id = :id for update")
                .param("t", tenant()).param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Separation", id));
        return require(id);
    }

    public Optional<SeparationView> live(UUID employmentId) {
        return db.jdbc().sql(SELECT + " where s.tenant_id = :t and s.employment_id = :e and s.status not in "
                        + "('WITHDRAWN','REJECTED','CANCELLED')")
                .param("t", tenant()).param("e", employmentId).query(SeparationRecords::map).optional()
                .map(v -> v.withPeriods(periods(v.id())));
    }

    public List<SeparationView> ofEmployment(UUID employmentId) {
        return db.jdbc().sql(SELECT + " where s.tenant_id = :t and s.employment_id = :e order by s.created_at desc")
                .param("t", tenant()).param("e", employmentId).query(SeparationRecords::map).list().stream()
                .map(v -> v.withPeriods(periods(v.id()))).toList();
    }

    public List<ServicePeriodView> periods(UUID separationId) {
        return db.jdbc().sql("""
                        select * from hr.separation_service_period where tenant_id = :t and separation_id = :s
                         order by period_from, period_kind, id""")
                .param("t", tenant()).param("s", separationId)
                .query((rs, n) -> {
                    String note = rs.getString("note");
                    boolean derived = note != null && note.startsWith(AUTO);
                    return new ServicePeriodView(rs.getObject("id", UUID.class), rs.getString("period_kind"),
                            rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
                            rs.getObject("source_employment_id", UUID.class),
                            derived ? note.substring(AUTO.length()).trim() : note, derived, rs.getInt("version"));
                })
                .list();
    }

    /**
     * Re-derives People's own service periods (BR-PPL-074 inputs; Payroll computes service time): the current
     * employment worked until the termination date (or LWD), earlier employments of the same person in the same legal
     * entity (WORKED, with their employment), and the severance / job-loss periods already paid on earlier separations
     * (incl. imported history). HR-entered periods are kept.
     */
    void refreshDerivedPeriods(SeparationView s) {
        UUID t = tenant();
        db.jdbc().sql("delete from hr.separation_service_period where tenant_id = :t and separation_id = :s "
                        + "and note like 'auto:%'")
                .param("t", t).param("s", s.id()).update();
        LocalDate end = s.terminationDate() != null ? s.terminationDate() : s.lastWorkingDate();
        LocalDate hire = db.jdbc().sql("select hire_date from hr.employment where tenant_id = :t and id = :e")
                .param("t", t).param("e", s.employmentId()).query(LocalDate.class).single();
        if (end != null && !end.isBefore(hire)) {
            insert(s.id(), "WORKED", hire, end, null, AUTO + " current employment");
        }
        record Earlier(UUID id, LocalDate from, LocalDate to) {
        }
        db.jdbc().sql("""
                        select id, hire_date, end_date from hr.employment
                         where tenant_id = :t and person_id = :p and legal_entity_id = :le and id <> :e
                           and status = 'TERMINATED' and end_date is not null and end_date < :hire
                         order by hire_date""")
                .param("t", t).param("p", s.personId()).param("le", s.legalEntityId()).param("e", s.employmentId())
                .param("hire", hire)
                .query((rs, n) -> new Earlier(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)))
                .list()
                .forEach(x -> insert(s.id(), "WORKED", x.from(), x.to(), x.id(), AUTO + " earlier employment"));
        record Paid(String kind, LocalDate from, LocalDate to, UUID source) {
        }
        db.jdbc().sql("""
                        select sp.period_kind, sp.period_from, sp.period_to, coalesce(sp.source_employment_id, s.employment_id)
                          from hr.separation_service_period sp
                          join hr.separation s on s.tenant_id = sp.tenant_id and s.id = sp.separation_id
                          join hr.employment e on e.tenant_id = s.tenant_id and e.id = s.employment_id
                         where sp.tenant_id = :t and e.person_id = :p and e.legal_entity_id = :le and s.id <> :s
                           and s.status = 'COMPLETED' and sp.period_kind in ('PAID_SEVERANCE','PAID_JOB_LOSS')
                         order by sp.period_from""")
                .param("t", t).param("p", s.personId()).param("le", s.legalEntityId()).param("s", s.id())
                .query((rs, n) -> new Paid(rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, UUID.class)))
                .list()
                .forEach(x -> insert(s.id(), x.kind(), x.from(), x.to(), x.source(), AUTO + " paid on an earlier separation"));
    }

    UUID insert(UUID separationId, String kind, LocalDate from, LocalDate to, UUID sourceEmploymentId, String note) {
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into hr.separation_service_period (tenant_id, id, separation_id, period_kind, period_from,
                               period_to, source_employment_id, note, created_by, updated_by)
                        values (:t, :id, :s, :k, :f, :to, :src, :note, :a, :a)""")
                .param("t", tenant()).param("id", id).param("s", separationId).param("k", kind).param("f", from)
                .param("to", to).param("src", sourceEmploymentId).param("note", note)
                .param("a", TenantContext.current().userAccountId()).update();
        return id;
    }

    static OffsetDateTime revocationAt(LocalDate date) {
        return date == null ? null : date.atTime(REVOCATION_TIME).atZone(BUSINESS_ZONE).toOffsetDateTime();
    }

    private static SeparationView map(ResultSet rs, int n) throws SQLException {
        String status = rs.getString("status");
        String employmentStatus = rs.getString("employment_status");
        String phase = switch (status) {
            case "APPROVED", "SCHEDULED" -> "NOTICE";
            case "COMPLETED" -> "TERMINATED";
            default -> employmentStatus;
        };
        LocalDate revocation = rs.getObject("access_revocation_date", LocalDate.class);
        java.sql.Timestamp withdrawn = rs.getTimestamp("withdrawn_at");
        return new SeparationView(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getObject("person_id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                rs.getString("employee_no"), rs.getString("full_name"), status, employmentStatus, phase,
                rs.getString("separation_type"), rs.getString("initiated_by"), rs.getString("legal_ground_code"),
                rs.getString("reason_code"), rs.getObject("request_date", LocalDate.class),
                rs.getObject("notice_date", LocalDate.class), rs.getObject("required_notice_days", Integer.class),
                rs.getString("notice_day_basis"), rs.getObject("notice_shortfall_days", Integer.class),
                rs.getString("special_trade_code"), rs.getString("notice_exempt_reason_code"),
                rs.getObject("last_working_date", LocalDate.class), rs.getObject("termination_date", LocalDate.class),
                revocation, revocationAt(revocation), rs.getString("severance_type"),
                rs.getString("severance_eligibility"), rs.getBoolean("is_protected"),
                rs.getString("protected_override_reason"),
                rs.getObject("protected_override_document_id", UUID.class),
                rs.getObject("is_rehire_eligible", Boolean.class), rs.getString("employee_note"),
                rs.getBoolean("no_personal_email"), rs.getString("signatory_name"), rs.getString("signatory_title"),
                rs.getObject("payroll_result_ref", UUID.class),
                rs.getObject("settlement_due_date", LocalDate.class), rs.getObject("decision_id", UUID.class),
                rs.getObject("workflow_instance_id", UUID.class), withdrawn == null ? null : withdrawn.toInstant(),
                rs.getTimestamp("created_at").toInstant(), List.of(), rs.getInt("version"));
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
