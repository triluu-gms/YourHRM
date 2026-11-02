package vn.yourhrm.people.internal.application.person;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.domain.person.PersonRules;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Sealed;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Additional identity documents (BR-PPL-011, US-PPL-007; {@code hr.person_identity_doc}): passport, work visa,
 * temporary / permanent residence card, old CMND / CCCD. The CURRENT national id stays on the person. Numbers are P3
 * (encrypted, blind index, last 4); the same live number cannot belong to two persons of the tenant (BR-PPL-001). The
 * scan is a Core document of type {@link PersonKeys#DOC_IDENTITY_SCAN}. Expiring documents are listed for HR and
 * alerted by the nightly job ({@link PersonJobService}).
 */
@Service
public class IdentityDocumentService {

    public static final Set<String> TYPES = Set.of("PASSPORT", "WORK_VISA", "TEMP_RESIDENCE_CARD",
            "PERMANENT_RESIDENCE_CARD", "OLD_CMND", "OLD_CCCD", "OTHER");
    public static final Set<String> STATUSES = Set.of("ACTIVE", "EXPIRED", "REVOKED", "SUPERSEDED");
    private static final Set<String> VISA_TYPED = Set.of("WORK_VISA", "TEMP_RESIDENCE_CARD", "PERMANENT_RESIDENCE_CARD");

    /** {@code docNo} null on change = unchanged. */
    public record DocumentData(String docType, String docNo, String issuingCountryCode, LocalDate issueDate,
            String issuePlace, LocalDate expiryDate, String visaTypeCode, String status) {
    }

    public record DocumentView(UUID id, UUID personId, String docType, String docNoMasked, String issuingCountryCode,
            LocalDate issueDate, String issuePlace, LocalDate expiryDate, String visaTypeCode, UUID scanDocumentId,
            String status, boolean legacyTaxCode, int version) {
    }

    /** A row of the expiring-documents report (US-PPL-007). */
    public record Expiring(UUID documentId, UUID personId, String fullName, List<String> employeeNos, String docType,
            String docNoMasked, LocalDate expiryDate, int daysLeft) {
    }

    private final HrDb db;
    private final PersonSupport support;
    private final PiiCipher cipher;
    private final SensitiveDataAccess sensitive;
    private final AuditLog audit;
    private final EmployeeAccess access;
    private final AuthorizationService authz;

    public IdentityDocumentService(HrDb db, PersonSupport support, PiiCipher cipher, SensitiveDataAccess sensitive,
            AuditLog audit, EmployeeAccess access, AuthorizationService authz) {
        this.db = db;
        this.support = support;
        this.cipher = cipher;
        this.sensitive = sensitive;
        this.audit = audit;
        this.access = access;
        this.authz = authz;
    }

    @HrTransactional(readOnly = true)
    public List<DocumentView> list(UUID personId) {
        support.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId);
        return db.jdbc().sql(SELECT + " where d.tenant_id = :t and d.person_id = :p order by d.doc_type, d.created_at")
                .param("t", support.tenant()).param("p", personId).query((rs, n) -> view(rs)).list();
    }

    @HrTransactional
    public DocumentView add(UUID personId, DocumentData data) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        support.lockPerson(personId);
        if (data.docNo() == null || data.docNo().isBlank()) {
            throw ApiException.unprocessable("invalid-document-no", "The document number is required");
        }
        DocumentData v = validate(data);
        String status = v.status() == null ? "ACTIVE" : v.status();
        String no = PersonRules.documentNo(data.docNo());
        UUID t = support.tenant();
        byte[] bidx = cipher.blindIndex(t, no);
        rejectTaken(personId, v.docType(), v.issuingCountryCode(), bidx, null);
        UUID id = Ids.newId();
        Sealed s = cipher.encrypt(Subject.person(t, personId), "doc_no", no);
        try {
            db.jdbc().sql("""
                            insert into hr.person_identity_doc (tenant_id, id, person_id, doc_type, doc_no_enc, doc_no_bidx,
                                   doc_no_last4, issuing_country_code, issue_date, issue_place, expiry_date, visa_type_code,
                                   status, subject_key_version, created_by, updated_by)
                            values (:t, :id, :p, :type, :enc, :b, :l4, :c, :issued, :place, :exp, :visa, :status, :kv,
                                    :a, :a)""")
                    .param("t", t).param("id", id).param("p", personId).param("type", v.docType())
                    .param("enc", s.ciphertext()).param("b", bidx).param("l4", PiiCipher.last4(no))
                    .param("c", v.issuingCountryCode()).param("issued", v.issueDate()).param("place", v.issuePlace())
                    .param("exp", v.expiryDate()).param("visa", v.visaTypeCode()).param("status", status)
                    .param("kv", s.keyVersion()).param("a", support.actor())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("identity-document-taken", "This document is already recorded as active");
        }
        return find(id);
    }

    /** Replaces the attributes (and the number when given) of a document, incl. its status. */
    @HrTransactional
    public DocumentView change(UUID personId, UUID id, int version, DocumentData data) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        DocumentView current = owned(personId, id);
        if (current.legacyTaxCode()) {
            throw ApiException.unprocessable("legacy-tax-code-read-only", "A legacy tax code kept as history is read-only");
        }
        DocumentData v = validate(new DocumentData(current.docType(), data.docNo(), data.issuingCountryCode(),
                data.issueDate(), data.issuePlace(), data.expiryDate(), data.visaTypeCode(), data.status()));
        String status = v.status() == null ? current.status() : v.status();
        UUID t = support.tenant();
        String no = data.docNo() == null || data.docNo().isBlank() ? null : PersonRules.documentNo(data.docNo());
        Sealed s = no == null ? null : cipher.encrypt(Subject.person(t, personId), "doc_no", no);
        byte[] bidx = no == null ? null : cipher.blindIndex(t, no);
        if (bidx != null && "ACTIVE".equals(status)) {
            rejectTaken(personId, current.docType(), v.issuingCountryCode(), bidx, id);
        }
        int updated;
        try {
            updated = db.jdbc().sql("""
                            update hr.person_identity_doc
                               set doc_no_enc = coalesce(:enc, doc_no_enc), doc_no_bidx = coalesce(:b, doc_no_bidx),
                                   doc_no_last4 = coalesce(:l4, doc_no_last4),
                                   subject_key_version = coalesce(:kv, subject_key_version),
                                   issuing_country_code = :c, issue_date = :issued, issue_place = :place,
                                   expiry_date = :exp, visa_type_code = :visa, status = :status,
                                   version = version + 1, updated_by = :a
                             where tenant_id = :t and id = :id and version = :v""")
                    .param("enc", s == null ? null : s.ciphertext()).param("b", bidx)
                    .param("l4", no == null ? null : PiiCipher.last4(no)).param("kv", s == null ? null : s.keyVersion())
                    .param("c", v.issuingCountryCode()).param("issued", v.issueDate()).param("place", v.issuePlace())
                    .param("exp", v.expiryDate()).param("visa", v.visaTypeCode()).param("status", status)
                    .param("a", support.actor()).param("t", t).param("id", id).param("v", version)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("identity-document-taken", "This document is already recorded as active");
        }
        PersonSupport.requireUpdated(updated, "Identity document", id);
        return find(id);
    }

    /** Links the uploaded scan (Core document of type HR_IDENTITY_DOC_SCAN owned by this document). */
    @HrTransactional
    public DocumentView attachScan(UUID personId, UUID id, UUID documentId) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        owned(personId, id);
        support.requireDocument(documentId, PersonKeys.DOC_IDENTITY_SCAN, PersonKeys.OWNER_IDENTITY_DOC, id);
        db.jdbc().sql("""
                        update hr.person_identity_doc set document_id = :d, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("d", documentId).param("a", support.actor()).param("t", support.tenant()).param("id", id).update();
        return find(id);
    }

    /** The document number in clear (US-PPL-085): sensitive permission in scope + purpose, audited. */
    @HrTransactional(readOnly = true)
    public Map<String, String> unmask(UUID personId, UUID id, String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw ApiException.unprocessable("purpose-required", "Unmasking needs a purpose (BR-PPL-152)");
        }
        support.requirePerson(PeoplePermissions.PERSON_VIEW_SENSITIVE, personId);
        owned(personId, id);
        UUID t = support.tenant();
        String no = db.jdbc().sql("select doc_no_enc, subject_key_version from hr.person_identity_doc where tenant_id = :t and id = :id")
                .param("t", t).param("id", id)
                .query((rs, n) -> cipher.decrypt(Subject.person(t, personId), "doc_no", rs.getBytes(1), rs.getInt(2)))
                .single();
        sensitive.recordSensitiveRead(PeoplePermissions.PERSON_VIEW_SENSITIVE, "hr.person_identity_doc", id, null);
        audit.record(new AuditLog.BusinessAction("UNMASK", "hr", "person_identity_doc", id, null,
                Map.of("fields", "DOC_NO", "purpose", purpose.trim(), "personId", personId.toString())));
        return Map.of("docNo", no);
    }

    /** ACTIVE documents of employees in the caller's scope expiring within {@code withinDays} (US-PPL-007 report). */
    @HrTransactional(readOnly = true)
    public List<Expiring> expiring(int withinDays) {
        if (withinDays < 0 || withinDays > 3660) {
            throw ApiException.unprocessable("invalid-range", "withinDays must be between 0 and 3660");
        }
        LocalDate today = support.today();
        SqlFragment scope = access.scope(PeoplePermissions.EMPLOYEE_VIEW, today, "s");
        boolean tenantWide = authz.hasTenantWidePermission(PeoplePermissions.EMPLOYEE_VIEW);
        return db.jdbc().sql("""
                        select d.id, d.person_id, p.full_name, d.doc_type, d.doc_no_last4, d.expiry_date,
                               coalesce((select array_agg(x.employee_no order by x.hire_date) from hr.employment x
                                          where x.tenant_id = d.tenant_id and x.person_id = d.person_id
                                            and x.status <> 'CANCELLED'), '{}') as nos
                          from hr.person_identity_doc d
                          join hr.person p on p.tenant_id = d.tenant_id and p.id = d.person_id
                         where d.tenant_id = :t and d.status = 'ACTIVE' and d.expiry_date is not null
                           and d.expiry_date >= :today and d.expiry_date <= :until
                           and (exists (select 1 from""" + " " + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " where e.tenant_id = d.tenant_id and e.person_id = d.person_id and " + scope.sql() + ")"
                        + " or (:tenantWide and not exists (select 1 from hr.employment z where z.tenant_id = d.tenant_id"
                        + " and z.person_id = d.person_id)))"
                        + " order by d.expiry_date, p.full_name")
                .param("t", support.tenant()).param("today", today).param("until", today.plusDays(withinDays))
                .param("asOf", today).param("tenantWide", tenantWide).params(scope.params())
                .query((rs, n) -> new Expiring(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), List.of((String[]) rs.getArray(7).getArray()), rs.getString(4),
                        "••••" + rs.getString(5), rs.getObject(6, LocalDate.class),
                        (int) java.time.temporal.ChronoUnit.DAYS.between(today, rs.getObject(6, LocalDate.class))))
                .list();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void rejectTaken(UUID personId, String type, String country, byte[] bidx, UUID except) {
        db.jdbc().sql("""
                        select person_id from hr.person_identity_doc
                         where tenant_id = :t and doc_type = :type and issuing_country_code = :c and doc_no_bidx = :b
                           and status = 'ACTIVE' and (cast(:x as uuid) is null or id <> :x) limit 1""")
                .param("t", support.tenant()).param("type", type).param("c", country).param("b", bidx).param("x", except)
                .query(UUID.class).optional().ifPresent(owner -> {
                    throw new ApiException(HttpStatus.CONFLICT, owner.equals(personId) ? "identity-document-taken"
                            : "duplicate-person", owner.equals(personId) ? "This document is already recorded as active"
                            : "This document belongs to another person of the tenant (BR-PPL-001)", Map.of("personId", owner));
                });
    }

    private DocumentData validate(DocumentData d) {
        if (!TYPES.contains(d.docType())) {
            throw ApiException.unprocessable("invalid-document-type", "Document type must be one of " + TYPES);
        }
        if (d.status() != null && !STATUSES.contains(d.status())) {
            throw ApiException.unprocessable("invalid-status", "Status must be one of " + STATUSES);
        }
        if (d.issueDate() != null && d.expiryDate() != null && !d.expiryDate().isAfter(d.issueDate())) {
            throw ApiException.unprocessable("invalid-period", "The expiry date must be after the issue date");
        }
        if (d.issueDate() != null && d.issueDate().isAfter(support.today())) {
            throw ApiException.unprocessable("invalid-issue-date", "The issue date cannot be in the future");
        }
        String country = PersonRules.country(d.issuingCountryCode() == null ? "VN" : d.issuingCountryCode());
        String visa = null;
        if (d.visaTypeCode() != null && !d.visaTypeCode().isBlank()) {
            if (!VISA_TYPED.contains(d.docType())) {
                throw ApiException.unprocessable("invalid-visa-type", "Only visas and residence cards have a visa type");
            }
            visa = support.code(PersonKeys.CL_VISA_TYPE, d.visaTypeCode(), support.today());
        }
        return new DocumentData(d.docType(), d.docNo(), country, d.issueDate(),
                d.issuePlace() == null || d.issuePlace().isBlank() ? null : d.issuePlace().trim(), d.expiryDate(), visa,
                d.status());
    }

    private DocumentView owned(UUID personId, UUID id) {
        return db.jdbc().sql(SELECT + " where d.tenant_id = :t and d.id = :id and d.person_id = :p")
                .param("t", support.tenant()).param("id", id).param("p", personId)
                .query((rs, n) -> view(rs)).optional().orElseThrow(() -> ApiException.notFound("Identity document", id));
    }

    private DocumentView find(UUID id) {
        return db.jdbc().sql(SELECT + " where d.tenant_id = :t and d.id = :id")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> view(rs)).single();
    }

    private static final String SELECT = """
            select d.id, d.person_id, d.doc_type, m.doc_no_masked, d.issuing_country_code, d.issue_date, d.issue_place,
                   d.expiry_date, d.visa_type_code, d.document_id, d.status, d.version
              from hr.person_identity_doc d
              join hr.v_person_identity_doc_masked m on m.tenant_id = d.tenant_id and m.id = d.id""";

    private static DocumentView view(ResultSet rs) throws SQLException {
        String visa = rs.getString("visa_type_code");
        return new DocumentView(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getString("doc_type"), rs.getString("doc_no_masked"), rs.getString("issuing_country_code"),
                rs.getObject("issue_date", LocalDate.class), rs.getString("issue_place"),
                rs.getObject("expiry_date", LocalDate.class), visa, rs.getObject("document_id", UUID.class),
                rs.getString("status"), PersonKeys.LEGACY_TAX_CODE_MARKER.equals(visa), rs.getInt("version"));
    }
}
