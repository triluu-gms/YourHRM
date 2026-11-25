package vn.yourhrm.attendance.internal.infra.leave;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.yourhrm.attendance.internal.infra.AttCipher.Sealed;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.Columns;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of the leave tables ({@code att.leave_type}, {@code att.leave_policy}, {@code att.leave_request},
 * {@code att.leave_balance_ledger}) and the People projections leave rules read. Every method runs inside the caller's
 * T&amp;A transaction. The ledger is append-only (DB trigger): there is no update or delete path.
 */
@Component
public class LeaveStore {

    // ------------------------------------------------------------------------------------------------ records

    public record TypeData(String code, String nameVi, String nameEn, String category, String paidBy, String minUnit,
            String countingBasis, boolean tracksBalance, boolean requiresSiClaimData, boolean requiresAttachment,
            BigDecimal maxDaysPerRequest, BigDecimal maxDaysPerYear, boolean countsAsService, String attendanceCode,
            String payrollCategory, Map<String, Object> eligibility, boolean statutory, String status) {
    }

    public record TypeRow(UUID id, TypeData data, int version) {
    }

    public record PolicyData(UUID leaveTypeId, String code, UUID legalEntityId, String employeeCategoryCode,
            String accrualMethod, BigDecimal baseEntitlementDays, Integer seniorityStepYears,
            BigDecimal seniorityStepDays, String prorationRule, int leaveYearStartMonth, BigDecimal carryOverMaxDays,
            Integer carryOverExpiryMonths, String expiryAction, BigDecimal allowNegativeDays, int noticeDays,
            LocalDate validFrom, LocalDate validTo, String status) {
    }

    public record PolicyRow(UUID id, PolicyData data, int version) {
    }

    /** A leave request without its P3 columns. */
    public record RequestRow(UUID id, UUID employmentId, UUID personId, UUID legalEntityId, UUID orgUnitId,
            UUID leaveTypeId, String leaveTypeCode, String requestUnit, LocalDate startDate, LocalDate endDate,
            String startPart, String endPart, LocalTime startTime, LocalTime endTime, BigDecimal durationDays,
            BigDecimal durationHours, String reason, String status, UUID workflowInstanceId, Instant decidedAt,
            UUID decidedBy, Instant cancelledAt, String cancelReason, LocalDate actualReturnDate,
            List<UUID> attachmentDocumentIds, String siCertificateType, String siCertificateNoLast4,
            LocalDate siCertificateDate, String childIdLast4, LocalDate childBirthDate, LocalDate confinementDate,
            Short childrenBornCount, boolean hasSiClaimData, Instant createdAt) {

        /** Last day the leave covers (an early return ends it the day before). */
        public LocalDate effectiveEnd() {
            return actualReturnDate != null && !actualReturnDate.isAfter(endDate) ? actualReturnDate.minusDays(1)
                    : endDate;
        }
    }

    /** SI claim data of a request (BR-ATT-063): encrypted P3 values and the minimal flags. */
    public record SiClaim(String certificateType, Sealed certificateNo, String certificateNoLast4,
            LocalDate certificateDate, Sealed issuingFacility, Sealed diagnosisCategory, Boolean longTermIllness,
            String treatmentType, LocalDate childBirthDate, Sealed childId, String childIdLast4,
            LocalDate confinementDate, Short childrenBornCount, Boolean caesarean) {
    }

    /** SI claim columns as stored (ciphertexts + key version). */
    public record StoredSiClaim(UUID personId, int keyVersion, String certificateType, byte[] certificateNo,
            String certificateNoLast4, LocalDate certificateDate, byte[] issuingFacility, byte[] diagnosisCategory,
            Boolean longTermIllness, String treatmentType, LocalDate childBirthDate, byte[] childId,
            String childIdLast4, LocalDate confinementDate, Short childrenBornCount, Boolean caesarean) {
    }

    public record NewRequest(UUID employmentId, UUID personId, UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId,
            String requestUnit, LocalDate startDate, LocalDate endDate, String startPart, String endPart,
            LocalTime startTime, LocalTime endTime, BigDecimal durationDays, BigDecimal durationHours, String reason,
            String status, List<UUID> attachments, SiClaim si) {
    }

    public record LedgerRow(UUID id, UUID employmentId, UUID leaveTypeId, String leaveTypeCode, int leaveYear,
            String txnType, BigDecimal quantity, String unit, LocalDate txnDate, LocalDate accrualMonth,
            LocalDate expiresOn, UUID leaveRequestId, String refType, UUID refId, String reason, String source,
            UUID importJobId, Instant createdAt) {
    }

    public record NewTxn(UUID employmentId, UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, String leaveTypeCode,
            int leaveYear, String txnType, BigDecimal quantity, LocalDate txnDate, LocalDate accrualMonth,
            LocalDate expiresOn, UUID leaveRequestId, String refType, UUID refId, String reason, UUID importJobId,
            String source) {
    }

    /** Employment facts from People's projection. */
    public record EmploymentFacts(UUID employmentId, UUID personId, UUID legalEntityId, LocalDate hireDate,
            LocalDate seniorityDate, LocalDate probationEndDate, LocalDate lastWorkingDate, LocalDate terminationDate,
            String status, String employeeCategoryCode) {

        public LocalDate serviceStart() {
            return seniorityDate != null ? seniorityDate : hireDate;
        }

        /** Last day the employee works (LWD, else termination date), null when open. */
        public LocalDate lastDay() {
            return lastWorkingDate != null ? lastWorkingDate : terminationDate;
        }

        public boolean employedOn(LocalDate d) {
            return !"CANCELLED".equals(status) && !d.isBefore(hireDate) && (lastDay() == null || !d.isAfter(lastDay()));
        }
    }

    /** Protected-worker flags of the work date that change the annual floor (BR-ATT-057). */
    public record JobFlags(boolean minor, boolean disabled, boolean hazardous) {
    }

    /** An approved day of leave in a range, with what the type says about service time (BR-ATT-059). */
    public record LeaveSpan(UUID requestId, String category, boolean countsAsService, LocalDate start, LocalDate end) {
    }

    private final AttDb db;
    private final JsonMapper json;

    public LeaveStore(AttDb db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    // ------------------------------------------------------------------------------------------------- types

    private static final String TYPE_COLUMNS = """
            select id, code, name_vi, name_en, leave_category, paid_by, min_unit, counting_basis, tracks_balance,
                   requires_si_claim_data, requires_attachment, max_days_per_request, max_days_per_year,
                   counts_as_service, attendance_code, payroll_category, eligibility::text as eligibility,
                   is_statutory, status, version
              from att.leave_type
            """;

    public List<TypeRow> types() {
        return db.jdbc().sql(TYPE_COLUMNS + " where tenant_id = :t order by code").param("t", tenant())
                .query(this::mapType).list();
    }

    public Optional<TypeRow> type(UUID id) {
        return db.jdbc().sql(TYPE_COLUMNS + " where tenant_id = :t and id = :id").param("t", tenant()).param("id", id)
                .query(this::mapType).optional();
    }

    public Optional<TypeRow> typeByCode(String code) {
        return db.jdbc().sql(TYPE_COLUMNS + " where tenant_id = :t and code = :c").param("t", tenant())
                .param("c", code).query(this::mapType).optional();
    }

    public UUID insertType(TypeData d) {
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into att.leave_type (tenant_id, id, code, name_vi, name_en, leave_category, paid_by,
                               min_unit, counting_basis, tracks_balance, requires_si_claim_data, requires_attachment,
                               max_days_per_request, max_days_per_year, counts_as_service, attendance_code,
                               payroll_category, eligibility, is_statutory, status, created_by, updated_by)
                        values (:t, :id, :code, :vi, :en, :cat, :paid, :unit, :basis, :tb, :si, :att, :mpr, :mpy, :svc,
                               :ac, :pc, cast(:elig as jsonb), :stat, :st, :by, :by)""")
                .param("t", tenant()).param("id", id).params(typeParams(d)).param("by", actor()).update();
        return id;
    }

    /** @return false on a version conflict */
    public boolean updateType(UUID id, int version, TypeData d) {
        return db.jdbc().sql("""
                        update att.leave_type set name_vi = :vi, name_en = :en, leave_category = :cat, paid_by = :paid,
                               min_unit = :unit, counting_basis = :basis, tracks_balance = :tb,
                               requires_si_claim_data = :si, requires_attachment = :att, max_days_per_request = :mpr,
                               max_days_per_year = :mpy, counts_as_service = :svc, attendance_code = :ac,
                               payroll_category = :pc, eligibility = cast(:elig as jsonb), status = :st,
                               updated_at = now(), updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("t", tenant()).param("id", id).param("v", version).params(typeParams(d)).param("by", actor())
                .update() == 1;
    }

    private Map<String, Object> typeParams(TypeData d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", d.code());
        p.put("vi", d.nameVi());
        p.put("en", d.nameEn());
        p.put("cat", d.category());
        p.put("paid", d.paidBy());
        p.put("unit", d.minUnit());
        p.put("basis", d.countingBasis());
        p.put("tb", d.tracksBalance());
        p.put("si", d.requiresSiClaimData());
        p.put("att", d.requiresAttachment());
        p.put("mpr", d.maxDaysPerRequest());
        p.put("mpy", d.maxDaysPerYear());
        p.put("svc", d.countsAsService());
        p.put("ac", d.attendanceCode());
        p.put("pc", d.payrollCategory());
        p.put("elig", json.writeValueAsString(d.eligibility() == null ? Map.of() : d.eligibility()));
        p.put("stat", d.statutory());
        p.put("st", d.status());
        return p;
    }

    private TypeRow mapType(ResultSet rs, int n) throws SQLException {
        Map<String, Object> elig = json.readValue(rs.getString("eligibility"),
                new TypeReference<Map<String, Object>>() {
                });
        return new TypeRow(rs.getObject("id", UUID.class), new TypeData(rs.getString("code"), rs.getString("name_vi"),
                rs.getString("name_en"), rs.getString("leave_category"), rs.getString("paid_by"),
                rs.getString("min_unit"), rs.getString("counting_basis"), rs.getBoolean("tracks_balance"),
                rs.getBoolean("requires_si_claim_data"), rs.getBoolean("requires_attachment"),
                rs.getBigDecimal("max_days_per_request"), rs.getBigDecimal("max_days_per_year"),
                rs.getBoolean("counts_as_service"), rs.getString("attendance_code"), rs.getString("payroll_category"),
                elig, rs.getBoolean("is_statutory"), rs.getString("status")), rs.getInt("version"));
    }

    // ---------------------------------------------------------------------------------------------- policies

    private static final String POLICY_COLUMNS = """
            select id, leave_type_id, code, legal_entity_id, employee_category_code, accrual_method,
                   base_entitlement_days, seniority_step_years, seniority_step_days, proration_rule,
                   leave_year_start_month, carry_over_max_days, carry_over_expiry_months, expiry_action,
                   allow_negative_days, notice_days, valid_from, valid_to, status, version
              from att.leave_policy
            """;

    public List<PolicyRow> policies(UUID leaveTypeId) {
        return db.jdbc().sql(POLICY_COLUMNS + """
                         where tenant_id = :t and (cast(:lt as uuid) is null or leave_type_id = :lt)
                         order by leave_type_id, legal_entity_id nulls first, employee_category_code nulls first,
                               valid_from""")
                .param("t", tenant()).param("lt", leaveTypeId).query(this::mapPolicy).list();
    }

    public Optional<PolicyRow> policy(UUID id) {
        return db.jdbc().sql(POLICY_COLUMNS + " where tenant_id = :t and id = :id").param("t", tenant())
                .param("id", id).query(this::mapPolicy).optional();
    }

    /**
     * The ACTIVE policy of the type valid on {@code date} for the legal entity and employee category, most specific
     * first: (LE, category), (LE, any), (any, category), (any, any).
     */
    public Optional<PolicyRow> policyFor(UUID leaveTypeId, UUID legalEntityId, String category, LocalDate date) {
        return db.jdbc().sql(POLICY_COLUMNS + """
                         where tenant_id = :t and leave_type_id = :lt and status = 'ACTIVE'
                           and valid_during @> cast(:d as date)
                           and (legal_entity_id is null or legal_entity_id = :le)
                           and (employee_category_code is null or employee_category_code = :cat)
                         order by (legal_entity_id is not null) desc, (employee_category_code is not null) desc
                         limit 1""")
                .param("t", tenant()).param("lt", leaveTypeId).param("d", date).param("le", legalEntityId)
                .param("cat", category).query(this::mapPolicy).optional();
    }

    public UUID insertPolicy(PolicyData d) {
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into att.leave_policy (tenant_id, id, leave_type_id, code, legal_entity_id,
                               employee_category_code, accrual_method, base_entitlement_days, seniority_step_years,
                               seniority_step_days, proration_rule, leave_year_start_month, carry_over_max_days,
                               carry_over_expiry_months, expiry_action, allow_negative_days, notice_days, valid_from,
                               valid_to, status, created_by, updated_by)
                        values (:t, :id, :lt, :code, :le, :cat, :acc, :base, :sy, :sd, :pr, :ysm, :cmax, :cexp, :exp,
                               :neg, :notice, :vf, :vt, :st, :by, :by)""")
                .param("t", tenant()).param("id", id).param("lt", d.leaveTypeId()).param("code", d.code())
                .param("le", d.legalEntityId()).param("cat", d.employeeCategoryCode())
                .param("acc", d.accrualMethod()).param("base", d.baseEntitlementDays())
                .param("sy", d.seniorityStepYears()).param("sd", d.seniorityStepDays())
                .param("pr", d.prorationRule()).param("ysm", d.leaveYearStartMonth())
                .param("cmax", d.carryOverMaxDays()).param("cexp", d.carryOverExpiryMonths())
                .param("exp", d.expiryAction()).param("neg", d.allowNegativeDays()).param("notice", d.noticeDays())
                .param("vf", d.validFrom()).param("vt", d.validTo()).param("st", d.status()).param("by", actor())
                .update();
        return id;
    }

    /** Ends a policy (Pattern A): sets {@code valid_to}; false on a version conflict. */
    public boolean endPolicy(UUID id, int version, LocalDate validTo) {
        return db.jdbc().sql("""
                        update att.leave_policy set valid_to = :vt, updated_at = now(), updated_by = :by,
                               version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("t", tenant()).param("id", id).param("v", version).param("vt", validTo).param("by", actor())
                .update() == 1;
    }

    private PolicyRow mapPolicy(ResultSet rs, int n) throws SQLException {
        return new PolicyRow(rs.getObject("id", UUID.class), new PolicyData(rs.getObject("leave_type_id", UUID.class),
                rs.getString("code"), rs.getObject("legal_entity_id", UUID.class),
                rs.getString("employee_category_code"), rs.getString("accrual_method"),
                rs.getBigDecimal("base_entitlement_days"), rs.getObject("seniority_step_years", Integer.class),
                rs.getBigDecimal("seniority_step_days"), rs.getString("proration_rule"),
                rs.getInt("leave_year_start_month"), rs.getBigDecimal("carry_over_max_days"),
                rs.getObject("carry_over_expiry_months", Integer.class), rs.getString("expiry_action"),
                rs.getBigDecimal("allow_negative_days"), rs.getInt("notice_days"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                rs.getString("status")), rs.getInt("version"));
    }

    // ---------------------------------------------------------------------------------------------- requests

    private static final String REQUEST_COLUMNS = """
            select r.id, r.employment_id, r.person_id, r.legal_entity_id, r.org_unit_id, r.leave_type_id,
                   t.code as type_code, r.request_unit, r.start_date, r.end_date, r.start_part, r.end_part,
                   r.start_time, r.end_time, r.duration_days, r.duration_hours, r.reason, r.status,
                   r.workflow_instance_id, r.decided_at, r.decided_by, r.cancelled_at, r.cancel_reason,
                   r.actual_return_date, r.attachment_document_ids, r.si_certificate_type, r.si_certificate_no_last4,
                   r.si_certificate_date, r.child_id_last4, r.child_birth_date, r.confinement_date,
                   r.children_born_count,
                   (r.si_certificate_type is not null or r.si_certificate_no_enc is not null
                    or r.child_birth_date is not null or r.confinement_date is not null) as has_si,
                   r.created_at
              from att.leave_request r
              join att.leave_type t on t.tenant_id = r.tenant_id and t.id = r.leave_type_id
            """;

    public Optional<RequestRow> request(UUID id) {
        return db.jdbc().sql(REQUEST_COLUMNS + " where r.tenant_id = :t and r.id = :id").param("t", tenant())
                .param("id", id).query(this::mapRequest).optional();
    }

    /** Locks the request row for a status change. */
    public Optional<RequestRow> requestForUpdate(UUID id) {
        return db.jdbc().sql(REQUEST_COLUMNS + " where r.tenant_id = :t and r.id = :id for update of r")
                .param("t", tenant()).param("id", id).query(this::mapRequest).optional();
    }

    /** Requests of the employment overlapping {@code [from, to]}, optionally of some statuses. */
    public List<RequestRow> requestsOf(UUID employmentId, LocalDate from, LocalDate to, List<String> statuses) {
        return db.jdbc().sql(REQUEST_COLUMNS + """
                         where r.tenant_id = :t and r.employment_id = :e and r.start_date <= :to and r.end_date >= :f
                           and (:all or r.status in (:st))
                         order by r.start_date, r.id""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("to", to)
                .param("all", statuses == null || statuses.isEmpty())
                .param("st", statuses == null || statuses.isEmpty() ? List.of("-") : statuses)
                .query(this::mapRequest).list();
    }

    /** Requests in the caller's scope overlapping {@code [from, to]} (team view, HR list). */
    public List<RequestRow> list(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, String status, LocalDate from,
            LocalDate to, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("r.legal_entity_id", "r.org_unit_id", null, "r.employment_id"), "sc");
        return db.jdbc().sql(REQUEST_COLUMNS + """
                         where r.tenant_id = :t and r.start_date <= :to and r.end_date >= :f
                           and (cast(:le as uuid) is null or r.legal_entity_id = :le)
                           and (cast(:ou as uuid) is null or r.org_unit_id = :ou)
                           and (cast(:lt as uuid) is null or r.leave_type_id = :lt)
                           and (cast(:st as text) is null or r.status = :st)
                        """ + " and " + f.sql() + " order by r.start_date, r.employment_id limit :lim offset :off")
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .param("ou", orgUnitId).param("lt", leaveTypeId).param("st", status).params(f.params())
                .param("lim", limit).param("off", offset).query(this::mapRequest).list();
    }

    public UUID insertRequest(NewRequest r) {
        UUID id = Ids.newId();
        SiClaim si = r.si() == null ? new SiClaim(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null) : r.si();
        int keyVersion = keyVersion(si);
        db.jdbc().sql("""
                        insert into att.leave_request (tenant_id, id, employment_id, person_id, legal_entity_id,
                               org_unit_id, leave_type_id, request_unit, start_date, end_date, start_part, end_part,
                               start_time, end_time, duration_days, duration_hours, reason, status,
                               attachment_document_ids, si_certificate_type, si_certificate_no_enc,
                               si_certificate_no_last4, si_certificate_date, si_issuing_facility_enc,
                               si_diagnosis_category_enc, si_is_long_term_illness, si_treatment_type, child_birth_date,
                               child_id_enc, child_id_last4, confinement_date, children_born_count, is_caesarean,
                               subject_key_version, created_by, updated_by)
                        values (:t, :id, :e, :p, :le, :ou, :lt, :unit, :sd, :ed, :sp, :ep, :stime, :etime, :dd, :dh,
                               :reason, :st, cast(:att as uuid[]), :sct, :scn, :scl4, :scd, :sif, :sdc, :slt, :stt,
                               :cbd, :cid, :cl4, :conf, :cnt, :caes, :kv, :by, :by)""")
                .param("t", tenant()).param("id", id).param("e", r.employmentId()).param("p", r.personId())
                .param("le", r.legalEntityId()).param("ou", r.orgUnitId()).param("lt", r.leaveTypeId())
                .param("unit", r.requestUnit()).param("sd", r.startDate()).param("ed", r.endDate())
                .param("sp", r.startPart()).param("ep", r.endPart()).param("stime", r.startTime())
                .param("etime", r.endTime()).param("dd", r.durationDays()).param("dh", r.durationHours())
                .param("reason", r.reason()).param("st", r.status()).param("att", uuidArray(r.attachments()))
                .params(siParams(si)).param("kv", keyVersion).param("by", actor()).update();
        return id;
    }

    /** Replaces the SI claim data of a request (BR-ATT-063: the certificate often arrives after the leave). */
    public void updateSiClaim(UUID id, SiClaim si) {
        db.jdbc().sql("""
                        update att.leave_request set si_certificate_type = :sct, si_certificate_no_enc = :scn,
                               si_certificate_no_last4 = :scl4, si_certificate_date = :scd,
                               si_issuing_facility_enc = :sif, si_diagnosis_category_enc = :sdc,
                               si_is_long_term_illness = :slt, si_treatment_type = :stt, child_birth_date = :cbd,
                               child_id_enc = :cid, child_id_last4 = :cl4, confinement_date = :conf,
                               children_born_count = :cnt, is_caesarean = :caes, subject_key_version = :kv,
                               updated_at = now(), updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).params(siParams(si)).param("kv", keyVersion(si))
                .param("by", actor()).update();
    }

    public Optional<StoredSiClaim> siClaim(UUID id) {
        return db.jdbc().sql("""
                        select person_id, subject_key_version, si_certificate_type, si_certificate_no_enc,
                               si_certificate_no_last4, si_certificate_date, si_issuing_facility_enc,
                               si_diagnosis_category_enc, si_is_long_term_illness, si_treatment_type, child_birth_date,
                               child_id_enc, child_id_last4, confinement_date, children_born_count, is_caesarean
                          from att.leave_request where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id)
                .query((rs, n) -> new StoredSiClaim(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                        rs.getBytes(4), rs.getString(5), rs.getObject(6, LocalDate.class), rs.getBytes(7),
                        rs.getBytes(8), (Boolean) rs.getObject(9), rs.getString(10), rs.getObject(11, LocalDate.class),
                        rs.getBytes(12), rs.getString(13), rs.getObject(14, LocalDate.class),
                        rs.getObject(15) == null ? null : rs.getShort(15), (Boolean) rs.getObject(16)))
                .optional();
    }

    private static int keyVersion(SiClaim si) {
        for (Sealed s : new Sealed[] {si.certificateNo(), si.issuingFacility(), si.diagnosisCategory(), si.childId()}) {
            if (s != null) {
                return s.keyVersion();
            }
        }
        return 1;
    }

    private static Map<String, Object> siParams(SiClaim si) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sct", si.certificateType());
        p.put("scn", bytes(si.certificateNo()));
        p.put("scl4", si.certificateNoLast4());
        p.put("scd", si.certificateDate());
        p.put("sif", bytes(si.issuingFacility()));
        p.put("sdc", bytes(si.diagnosisCategory()));
        p.put("slt", si.longTermIllness());
        p.put("stt", si.treatmentType());
        p.put("cbd", si.childBirthDate());
        p.put("cid", bytes(si.childId()));
        p.put("cl4", si.childIdLast4());
        p.put("conf", si.confinementDate());
        p.put("cnt", si.childrenBornCount());
        p.put("caes", si.caesarean());
        return p;
    }

    private static byte[] bytes(Sealed s) {
        return s == null ? null : s.ciphertext();
    }

    public void setWorkflow(UUID id, UUID instanceId) {
        db.jdbc().sql("update att.leave_request set workflow_instance_id = :w where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).param("w", instanceId).update();
    }

    /** Moves the request from {@code from} to {@code to}; false when it was no longer in {@code from}. */
    public boolean setStatus(UUID id, String from, String to, UUID decidedBy, String cancelReason) {
        return db.jdbc().sql("""
                        update att.leave_request set status = :to,
                               decided_at = case when :to in ('APPROVED','REJECTED') then now() else decided_at end,
                               decided_by = case when :to in ('APPROVED','REJECTED') then cast(:dec as uuid)
                                                 else decided_by end,
                               cancelled_at = case when :to in ('CANCELLED','WITHDRAWN') then now() else cancelled_at end,
                               cancelled_by = case when :to in ('CANCELLED','WITHDRAWN') then cast(:by as uuid)
                                                   else cancelled_by end,
                               cancel_reason = coalesce(:cr, cancel_reason),
                               updated_at = now(), updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id and status = :from""")
                .param("t", tenant()).param("id", id).param("from", from).param("to", to).param("dec", decidedBy)
                .param("cr", cancelReason).param("by", actor()).update() == 1;
    }

    public void setActualReturn(UUID id, LocalDate returnDate) {
        db.jdbc().sql("""
                        update att.leave_request set actual_return_date = :d, updated_at = now(), updated_by = :by,
                               version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("d", returnDate).param("by", actor()).update();
    }

    /** PENDING requests of the employment and type other than {@code exceptId} (reserved balance, BR-ATT-062). */
    public List<RequestRow> pending(UUID employmentId, UUID leaveTypeId, UUID exceptId) {
        return db.jdbc().sql(REQUEST_COLUMNS + """
                         where r.tenant_id = :t and r.employment_id = :e and r.leave_type_id = :lt
                           and r.status = 'PENDING' and (cast(:x as uuid) is null or r.id <> :x)""")
                .param("t", tenant()).param("e", employmentId).param("lt", leaveTypeId).param("x", exceptId)
                .query(this::mapRequest).list();
    }

    /** Days of PENDING / APPROVED requests of the type starting in {@code [from, to]} (yearly maximum). */
    public BigDecimal daysRequested(UUID employmentId, UUID leaveTypeId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select coalesce(sum(duration_days), 0) from att.leave_request
                         where tenant_id = :t and employment_id = :e and leave_type_id = :lt
                           and status in ('PENDING','APPROVED') and start_date between :f and :to""")
                .param("t", tenant()).param("e", employmentId).param("lt", leaveTypeId).param("f", from)
                .param("to", to).query(BigDecimal.class).single();
    }

    /** Whether an hourly request overlaps leave of the employment on that day (hourly leave: checked here). */
    public boolean hourOverlap(UUID employmentId, LocalDate day, LocalTime start, LocalTime end) {
        return db.jdbc().sql("""
                        select exists (select 1 from att.leave_request
                         where tenant_id = :t and employment_id = :e and status in ('PENDING','APPROVED')
                           and start_date <= :d and end_date >= :d
                           and (request_unit <> 'HOUR' or (start_time < :en and end_time > :st)))""")
                .param("t", tenant()).param("e", employmentId).param("d", day).param("st", start).param("en", end)
                .query(Boolean.class).single();
    }

    /** Approved day leave of the employment overlapping {@code [from, to]}, as spans (service-time rules). */
    public List<LeaveSpan> approvedSpans(UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select r.id, t.leave_category, t.counts_as_service, r.start_date,
                               least(r.end_date, coalesce(r.actual_return_date - 1, r.end_date))
                          from att.leave_request r
                          join att.leave_type t on t.tenant_id = r.tenant_id and t.id = r.leave_type_id
                         where r.tenant_id = :t and r.employment_id = :e and r.status = 'APPROVED'
                           and r.request_unit <> 'HOUR' and r.start_date <= :to and r.end_date >= :f
                         order by r.start_date""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("to", to)
                .query((rs, n) -> new LeaveSpan(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class)))
                .list();
    }

    private RequestRow mapRequest(ResultSet rs, int n) throws SQLException {
        Array att = rs.getArray("attachment_document_ids");
        OffsetDateTime dec = rs.getObject("decided_at", OffsetDateTime.class);
        OffsetDateTime can = rs.getObject("cancelled_at", OffsetDateTime.class);
        return new RequestRow(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getObject("person_id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                rs.getObject("org_unit_id", UUID.class), rs.getObject("leave_type_id", UUID.class),
                rs.getString("type_code"), rs.getString("request_unit"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getString("start_part"), rs.getString("end_part"),
                rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
                rs.getBigDecimal("duration_days"), rs.getBigDecimal("duration_hours"), rs.getString("reason"),
                rs.getString("status"), rs.getObject("workflow_instance_id", UUID.class),
                dec == null ? null : dec.toInstant(), rs.getObject("decided_by", UUID.class),
                can == null ? null : can.toInstant(), rs.getString("cancel_reason"),
                rs.getObject("actual_return_date", LocalDate.class),
                att == null ? List.of() : Arrays.asList((UUID[]) att.getArray()), rs.getString("si_certificate_type"),
                rs.getString("si_certificate_no_last4"), rs.getObject("si_certificate_date", LocalDate.class),
                rs.getString("child_id_last4"), rs.getObject("child_birth_date", LocalDate.class),
                rs.getObject("confinement_date", LocalDate.class),
                rs.getObject("children_born_count") == null ? null : rs.getShort("children_born_count"),
                rs.getBoolean("has_si"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static String uuidArray(List<UUID> ids) {
        return "{" + String.join(",", (ids == null ? List.<UUID>of() : ids).stream().map(UUID::toString).toList())
                + "}";
    }

    // ------------------------------------------------------------------------------------------------- ledger

    private static final String LEDGER_COLUMNS = """
            select id, employment_id, leave_type_id, leave_type_code, leave_year, txn_type, quantity, unit, txn_date,
                   accrual_month, expires_on, leave_request_id, ref_type, ref_id, reason, source, import_job_id,
                   created_at
              from att.leave_balance_ledger
            """;

    /** Inserts a ledger row; false when an idempotency index (one OPENING per year, one ACCRUE per month) refused it. */
    public boolean insertTxn(NewTxn x) {
        return db.jdbc().sql("""
                        insert into att.leave_balance_ledger (tenant_id, id, employment_id, legal_entity_id,
                               org_unit_id, leave_type_id, leave_type_code, leave_year, txn_type, quantity, unit,
                               txn_date, accrual_month, expires_on, leave_request_id, ref_type, ref_id, reason,
                               import_job_id, source, created_by)
                        values (:t, :id, :e, :le, :ou, :lt, :code, :y, :type, :q, 'DAY', :d, :am, :exp, :req, :rt,
                               :rid, :reason, :job, :src, :by)
                        on conflict do nothing""")
                .param("t", tenant()).param("id", Ids.newId()).param("e", x.employmentId())
                .param("le", x.legalEntityId()).param("ou", x.orgUnitId()).param("lt", x.leaveTypeId())
                .param("code", x.leaveTypeCode()).param("y", x.leaveYear()).param("type", x.txnType())
                .param("q", x.quantity()).param("d", x.txnDate()).param("am", x.accrualMonth())
                .param("exp", x.expiresOn()).param("req", x.leaveRequestId()).param("rt", x.refType())
                .param("rid", x.refId()).param("reason", x.reason()).param("job", x.importJobId())
                .param("src", x.source()).param("by", actor()).update() == 1;
    }

    public List<LedgerRow> ledger(UUID employmentId, UUID leaveTypeId, Integer leaveYear) {
        return db.jdbc().sql(LEDGER_COLUMNS + """
                         where tenant_id = :t and employment_id = :e
                           and (cast(:lt as uuid) is null or leave_type_id = :lt)
                           and (cast(:y as int) is null or leave_year = :y)
                         order by leave_year, txn_date, created_at, id""")
                .param("t", tenant()).param("e", employmentId).param("lt", leaveTypeId).param("y", leaveYear)
                .query(this::ledgerRow).list();
    }

    /** Ledger rows of a request (TAKE / CANCEL). */
    public List<LedgerRow> ledgerOfRequest(UUID requestId) {
        return db.jdbc().sql(LEDGER_COLUMNS + " where tenant_id = :t and leave_request_id = :r order by created_at")
                .param("t", tenant()).param("r", requestId).query(this::ledgerRow).list();
    }

    /** Σ quantity per txn type of the employment × type × year. */
    public Map<String, BigDecimal> sums(UUID employmentId, UUID leaveTypeId, int leaveYear) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        db.jdbc().sql("""
                        select txn_type, sum(quantity) from att.leave_balance_ledger
                         where tenant_id = :t and employment_id = :e and leave_type_id = :lt and leave_year = :y
                         group by txn_type""")
                .param("t", tenant()).param("e", employmentId).param("lt", leaveTypeId).param("y", leaveYear)
                .query((rs, n) -> out.put(rs.getString(1), rs.getBigDecimal(2))).list();
        return out;
    }

    public BigDecimal balance(UUID employmentId, UUID leaveTypeId, int leaveYear) {
        return sums(employmentId, leaveTypeId, leaveYear).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Year-end rows already written by a carry run of the year (idempotency of the run). */
    public boolean yearEndDone(UUID employmentId, UUID leaveTypeId, int leaveYear) {
        return db.jdbc().sql("""
                        select exists (select 1 from att.leave_balance_ledger
                         where tenant_id = :t and employment_id = :e and leave_type_id = :lt and leave_year = :y
                           and ref_type = 'CARRY_RUN' and txn_type in ('CARRY','EXPIRE','ENCASH'))""")
                .param("t", tenant()).param("e", employmentId).param("lt", leaveTypeId).param("y", leaveYear)
                .query(Boolean.class).single();
    }

    /** Employments with a ledger row of the type in the year. */
    public List<UUID> employmentsWithLedger(UUID leaveTypeId, int leaveYear, UUID legalEntityId) {
        return db.jdbc().sql("""
                        select distinct employment_id from att.leave_balance_ledger
                         where tenant_id = :t and leave_type_id = :lt and leave_year = :y
                           and (cast(:le as uuid) is null or legal_entity_id = :le)
                         order by employment_id""")
                .param("t", tenant()).param("lt", leaveTypeId).param("y", leaveYear).param("le", legalEntityId)
                .query(UUID.class).list();
    }

    /** Positive CARRY rows expiring on or before {@code asOf} with no EXPIRE row referring to them yet. */
    public List<LedgerRow> carriesToExpire(LocalDate asOf, UUID legalEntityId) {
        return db.jdbc().sql(LEDGER_COLUMNS.replace("from att.leave_balance_ledger", "from att.leave_balance_ledger c")
                        .replace("select id,", "select c.id,") + """
                         where c.tenant_id = :t and c.txn_type = 'CARRY' and c.quantity > 0 and c.expires_on <= :d
                           and (cast(:le as uuid) is null or c.legal_entity_id = :le)
                           and not exists (select 1 from att.leave_balance_ledger x
                                            where x.tenant_id = c.tenant_id and x.txn_type = 'EXPIRE'
                                              and x.ref_type = 'CARRY_EXPIRY' and x.ref_id = c.id)
                         order by c.employment_id, c.expires_on""")
                .param("t", tenant()).param("d", asOf).param("le", legalEntityId).query(this::ledgerRow).list();
    }

    /** Days taken (TAKE + CANCEL, as a positive number) of the year with a txn date before {@code before}. */
    public BigDecimal takenBefore(UUID employmentId, UUID leaveTypeId, int leaveYear, LocalDate before) {
        return db.jdbc().sql("""
                        select coalesce(-sum(quantity), 0) from att.leave_balance_ledger
                         where tenant_id = :t and employment_id = :e and leave_type_id = :lt and leave_year = :y
                           and txn_type in ('TAKE','CANCEL') and txn_date < :d""")
                .param("t", tenant()).param("e", employmentId).param("lt", leaveTypeId).param("y", leaveYear)
                .param("d", before).query(BigDecimal.class).single();
    }

    /** Σ OPENING + CARRY per leave type code of an import job (opening-balance reconciliation). */
    public Map<String, BigDecimal> openingTotals(UUID importJobId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        db.jdbc().sql("""
                        select leave_type_code, sum(quantity) from att.leave_balance_ledger
                         where tenant_id = :t and import_job_id = :j and txn_type in ('OPENING','CARRY')
                         group by leave_type_code order by leave_type_code""")
                .param("t", tenant()).param("j", importJobId)
                .query((rs, n) -> out.put(rs.getString(1), rs.getBigDecimal(2))).list();
        return out;
    }

    public Optional<LedgerRow> opening(UUID employmentId, UUID leaveTypeId, int leaveYear) {
        return db.jdbc().sql(LEDGER_COLUMNS + """
                         where tenant_id = :t and employment_id = :e and leave_type_id = :lt and leave_year = :y
                           and txn_type = 'OPENING'""")
                .param("t", tenant()).param("e", employmentId).param("lt", leaveTypeId).param("y", leaveYear)
                .query(this::ledgerRow).optional();
    }

    private LedgerRow ledgerRow(ResultSet rs, int n) throws SQLException {
        return new LedgerRow(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getObject("leave_type_id", UUID.class), rs.getString("leave_type_code"), rs.getInt("leave_year"),
                rs.getString("txn_type"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                rs.getObject("txn_date", LocalDate.class), rs.getObject("accrual_month", LocalDate.class),
                rs.getObject("expires_on", LocalDate.class), rs.getObject("leave_request_id", UUID.class),
                rs.getString("ref_type"), rs.getObject("ref_id", UUID.class), rs.getString("reason"),
                rs.getString("source"), rs.getObject("import_job_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    // ----------------------------------------------------------------------------------------- People facts

    public Optional<EmploymentFacts> employment(UUID employmentId, LocalDate asOf) {
        return db.jdbc().sql("""
                        select e.employment_id, e.person_id, e.legal_entity_id, e.hire_date, e.seniority_date,
                               e.probation_end_date, e.last_working_date, e.termination_date, e.status,
                               coalesce((select c.employee_category_code from att.ext_hr_employment_classification c
                                          where c.tenant_id = e.tenant_id and c.employment_id = e.employment_id
                                            and c.valid_during @> cast(:d as date)), e.employee_category_code)
                          from att.ext_hr_employment e where e.tenant_id = :t and e.employment_id = :id""")
                .param("t", tenant()).param("id", employmentId).param("d", asOf)
                .query((rs, n) -> new EmploymentFacts(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                        rs.getObject(8, LocalDate.class), rs.getString(9), rs.getString(10)))
                .optional();
    }

    /** Employments employed on some day of {@code [from, to]}, optionally of one legal entity. */
    public List<UUID> employments(UUID legalEntityId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select employment_id from att.ext_hr_employment
                         where tenant_id = :t and status <> 'CANCELLED'
                           and (cast(:le as uuid) is null or legal_entity_id = :le)
                           and hire_date <= :to and coalesce(last_working_date, termination_date, :to) >= :f
                         order by employment_id""")
                .param("t", tenant()).param("le", legalEntityId).param("f", from).param("to", to)
                .query(UUID.class).list();
    }

    /** CLOSED / LOCKED attendance periods of the legal entity overlapping {@code [from, to]}: their status. */
    public List<String> frozenPeriods(UUID legalEntityId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select status from att.attendance_period
                         where tenant_id = :t and legal_entity_id = :le and period_start <= :to and period_end >= :f
                           and status in ('CLOSED', 'LOCKED')
                         order by period_start""")
                .param("t", tenant()).param("le", legalEntityId).param("f", from).param("to", to)
                .query(String.class).list();
    }

    /** Whether ledger rows of a reference type exist for the employment × type × year. */
    public boolean hasRef(UUID employmentId, UUID leaveTypeId, int leaveYear, String refType) {
        return db.jdbc().sql("""
                        select exists (select 1 from att.leave_balance_ledger
                         where tenant_id = :t and employment_id = :e and leave_type_id = :lt and leave_year = :y
                           and ref_type = :rt)""")
                .param("t", tenant()).param("e", employmentId).param("lt", leaveTypeId).param("y", leaveYear)
                .param("rt", refType).query(Boolean.class).single();
    }

    public JobFlags jobFlags(UUID employmentId, LocalDate date) {
        return db.jdbc().sql("select is_minor, is_disabled, hazardous_job from att.protected_flags(:e, :d)")
                .param("e", employmentId).param("d", date)
                .query((rs, n) -> new JobFlags(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3))).optional()
                .orElse(new JobFlags(false, false, false));
    }
}
