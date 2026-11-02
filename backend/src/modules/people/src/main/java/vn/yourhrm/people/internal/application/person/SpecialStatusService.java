package vn.yourhrm.people.internal.application.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonConsentRegistry;
import vn.yourhrm.people.api.PersonEvents.SpecialStatusChanged;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.HrMasking;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrJson;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Sealed;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;

/**
 * Special statuses (BR-PPL-021, US-PPL-012; Pattern A, {@code hr.special_status}): pregnancy, child under 12 months,
 * disability, union / party / youth-union membership, per-employee hazardous-job overrides (GAP-045: Core's job-title
 * flag is the master, People stores overrides with a reason). MINOR and ELDERLY_WORKER are derived from the birth date
 * with the legal age parameters, never entered. Details (disability degree, certificate data, card numbers) are
 * encrypted ({@code detail_enc}); sensitive types are shown only at mask level NONE ({@code hr.v_special_status_masked});
 * party / youth-union membership is optional and needs consent (BR-PPL-151). Changes publish
 * {@code people.SpecialStatusChanged}.
 */
@Service
public class SpecialStatusService {

    public static final Set<String> TYPES = Set.of("PREGNANCY", "CHILD_UNDER_12M", "DISABILITY", "MINOR",
            "ELDERLY_WORKER", "HAZARDOUS_JOB", "UNION_MEMBER", "PARTY_MEMBER", "YOUTH_UNION_MEMBER");
    /** Derived from the birth date, never entered (US-PPL-012 AC). */
    public static final Set<String> DERIVED = Set.of("MINOR", "ELDERLY_WORKER");
    /** Optional political data: needs consent (BR-PPL-151). */
    public static final Set<String> CONSENT_REQUIRED = Set.of("PARTY_MEMBER", "YOUTH_UNION_MEMBER");
    /**
     * Statuses that protect against employer-initiated termination (BR-PPL-073, C8: pregnancy, child under 12
     * months; maternity and sick leave come from T&A, IN-14).
     */
    public static final Set<String> PROTECTIVE = Set.of("PREGNANCY", "CHILD_UNDER_12M");

    public record Create(String statusType, UUID employmentId, LocalDate validFrom, LocalDate validTo,
            String classificationCode, Map<String, Object> details, String overrideReason) {
    }

    public record Change(LocalDate validTo, String classificationCode, Map<String, Object> details,
            String overrideReason) {
    }

    /** A status as the caller may see it: sensitive types read "RESTRICTED" unless unmasked level. */
    public record StatusView(UUID id, UUID personId, UUID employmentId, String statusType, boolean sensitive,
            LocalDate validFrom, LocalDate validTo, String classificationCode, boolean derived, UUID evidenceDocumentId,
            String status, int version) {
    }

    private final HrDb db;
    private final PersonSupport support;
    private final PiiCipher cipher;
    private final HrJson json;
    private final HrOutbox outbox;
    private final HrMasking masking;
    private final EmployeeAccess access;
    private final PeopleLegalParameters legal;

    public SpecialStatusService(HrDb db, PersonSupport support, PiiCipher cipher, HrJson json, HrOutbox outbox,
            HrMasking masking, EmployeeAccess access, PeopleLegalParameters legal) {
        this.db = db;
        this.support = support;
        this.cipher = cipher;
        this.json = json;
        this.outbox = outbox;
        this.masking = masking;
        this.access = access;
        this.legal = legal;
    }

    @HrTransactional(readOnly = true)
    public List<StatusView> list(UUID personId, boolean includeCancelled) {
        support.requirePerson(PeoplePermissions.SPECIAL_STATUS_VIEW, personId);
        masking.apply(PeoplePermissions.SPECIAL_STATUS_VIEW);
        boolean political = support.hasConsent(personId, PersonConsentRegistry.PURPOSE_POLITICAL_MEMBERSHIP);
        return db.jdbc().sql("""
                        select m.*, s.classification_code as raw_class, s.evidence_document_id, s.version,
                               s.status_type as raw_type
                          from hr.v_special_status_masked m
                          join hr.special_status s on s.tenant_id = m.tenant_id and s.id = m.id
                         where m.tenant_id = :t and m.person_id = :p and (:all or m.status = 'ACTIVE')
                         order by s.status_type, m.valid_from""")
                .param("t", support.tenant()).param("p", personId).param("all", includeCancelled)
                .query((rs, n) -> {
                    String raw = rs.getString("raw_type");
                    if (CONSENT_REQUIRED.contains(raw) && !political) {
                        return null;                           // hidden without consent (US-PPL-084)
                    }
                    return new StatusView(rs.getObject("id", UUID.class), personId,
                            rs.getObject("employment_id", UUID.class), rs.getString("status_type"),
                            rs.getBoolean("is_sensitive"), rs.getObject("valid_from", LocalDate.class),
                            rs.getObject("valid_to", LocalDate.class), rs.getString("classification_code"),
                            PersonKeys.DERIVED_FROM_BIRTH_DATE.equals(rs.getString("raw_class")),
                            rs.getObject("evidence_document_id", UUID.class), rs.getString("status"), rs.getInt("version"));
                })
                .list().stream().filter(java.util.Objects::nonNull).toList();
    }

    @HrTransactional
    public StatusView create(UUID personId, Create c) {
        support.requirePerson(PeoplePermissions.SPECIAL_STATUS_UPDATE, personId);
        support.lockPerson(personId);
        String type = c.statusType();
        if (!TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid-status-type", "Unknown special status " + type);
        }
        if (DERIVED.contains(type)) {
            throw ApiException.unprocessable("derived-status", type + " is derived from the birth date and cannot be "
                    + "entered (US-PPL-012)");
        }
        if (c.validFrom() == null) {
            throw ApiException.unprocessable("invalid-period", "validFrom is required");
        }
        if ("HAZARDOUS_JOB".equals(type)) {
            if (c.employmentId() == null || c.overrideReason() == null || c.overrideReason().isBlank()) {
                throw ApiException.unprocessable("hazardous-override-incomplete", "A hazardous-job override needs the "
                        + "employment and a reason (GAP-045: the job title's flag is the master)");
            }
            access.require(PeoplePermissions.SPECIAL_STATUS_UPDATE, c.employmentId(), c.validFrom());
            if (c.classificationCode() != null) {
                support.code("JOB_HAZARD_CATEGORY", c.classificationCode(), c.validFrom());
            }
        } else if (c.employmentId() != null) {
            throw ApiException.unprocessable("invalid-employment", "Only HAZARDOUS_JOB is employment-scoped");
        }
        if (CONSENT_REQUIRED.contains(type)) {
            support.requireConsent(personId, PersonConsentRegistry.PURPOSE_POLITICAL_MEMBERSHIP);
        }
        // the status name defines the period: a child under 12 months (valid_to EXCLUSIVE = 1st birthday)
        LocalDate validTo = c.validTo() == null && "CHILD_UNDER_12M".equals(type) ? c.validFrom().plusMonths(12) : c.validTo();
        checkPeriod(c.validFrom(), validTo);
        UUID t = support.tenant();
        UUID id = Ids.newId();
        Sealed detail = seal(t, personId, c.details());
        try {
            db.jdbc().sql("""
                            insert into hr.special_status (tenant_id, id, person_id, employment_id, status_type, valid_from,
                                   valid_to, classification_code, detail_enc, override_reason, created_by, updated_by)
                            values (:t, :id, :p, :e, :type, :from, :to, :cls, :detail, :reason, :a, :a)""")
                    .param("t", t).param("id", id).param("p", personId).param("e", c.employmentId()).param("type", type)
                    .param("from", c.validFrom()).param("to", validTo).param("cls", c.classificationCode())
                    .param("detail", detail == null ? null : detail.ciphertext()).param("reason", c.overrideReason())
                    .param("a", support.actor())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("special-status-overlap", "An active " + type + " status already covers part "
                    + "of this period");
        }
        outbox.publish(new SpecialStatusChanged(t, personId, c.employmentId(), id, type, c.validFrom(), validTo,
                "CREATED"));
        return find(id);
    }

    @HrTransactional
    public StatusView change(UUID personId, UUID id, int version, Change c) {
        support.requirePerson(PeoplePermissions.SPECIAL_STATUS_UPDATE, personId);
        StatusRow row = row(personId, id);
        if (row.derived()) {
            throw ApiException.unprocessable("derived-status", "A derived status changes with the birth date only");
        }
        if ("HAZARDOUS_JOB".equals(row.type()) && (c.overrideReason() == null || c.overrideReason().isBlank())) {
            throw ApiException.unprocessable("hazardous-override-incomplete", "A hazardous-job override needs a reason");
        }
        checkPeriod(row.validFrom(), c.validTo());
        Sealed detail = seal(support.tenant(), personId, c.details());
        int updated;
        try {
            updated = db.jdbc().sql("""
                            update hr.special_status set valid_to = :to, classification_code = :cls,
                                   detail_enc = coalesce(:detail, detail_enc), override_reason = :reason,
                                   version = version + 1, updated_by = :a
                             where tenant_id = :t and id = :id and version = :v and status = 'ACTIVE'""")
                    .param("to", c.validTo()).param("cls", c.classificationCode())
                    .param("detail", detail == null ? null : detail.ciphertext()).param("reason", c.overrideReason())
                    .param("a", support.actor()).param("t", support.tenant()).param("id", id).param("v", version)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("special-status-overlap", "The new period overlaps another active status");
        }
        PersonSupport.requireUpdated(updated, "Special status", id);
        outbox.publish(new SpecialStatusChanged(support.tenant(), personId, row.employmentId(), id, row.type(),
                row.validFrom(), c.validTo(), "CHANGED"));
        return find(id);
    }

    @HrTransactional
    public StatusView cancel(UUID personId, UUID id, int version) {
        support.requirePerson(PeoplePermissions.SPECIAL_STATUS_UPDATE, personId);
        StatusRow row = row(personId, id);
        if (row.derived()) {
            throw ApiException.unprocessable("derived-status", "A derived status changes with the birth date only");
        }
        int updated = db.jdbc().sql("""
                        update hr.special_status set status = 'CANCELLED', version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id and version = :v and status = 'ACTIVE'""")
                .param("a", support.actor()).param("t", support.tenant()).param("id", id).param("v", version).update();
        PersonSupport.requireUpdated(updated, "Special status", id);
        outbox.publish(new SpecialStatusChanged(support.tenant(), personId, row.employmentId(), id, row.type(),
                row.validFrom(), row.validTo(), "CANCELLED"));
        return find(id);
    }

    /** Attaches the evidence file (Core document of type HR_SPECIAL_STATUS_EVIDENCE uploaded for this status). */
    @HrTransactional
    public StatusView attachEvidence(UUID personId, UUID id, UUID documentId) {
        support.requirePerson(PeoplePermissions.SPECIAL_STATUS_UPDATE, personId);
        row(personId, id);
        support.requireDocument(documentId, PersonKeys.DOC_SPECIAL_STATUS_EVIDENCE, PersonKeys.OWNER_SPECIAL_STATUS, id);
        db.jdbc().sql("""
                        update hr.special_status set evidence_document_id = :d, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("d", documentId).param("a", support.actor()).param("t", support.tenant()).param("id", id).update();
        return find(id);
    }

    /**
     * (Re-)derives MINOR and ELDERLY_WORKER from the birth date (US-PPL-012): MINOR from birth until the
     * {@link PersonKeys#LP_MINOR_AGE_YEARS} birthday, ELDERLY_WORKER from the {@link PersonKeys#LP_ELDERLY_AGE_MONTHS}
     * age of the person's gender. Previous derived rows are cancelled. A missing parameter derives nothing (the
     * person can still be recorded); the status is then entered once the legal pack defines it.
     */
    @HrTransactional
    public void deriveAgeStatuses(UUID personId, LocalDate birthDate, String genderCode, boolean replace) {
        UUID t = support.tenant();
        if (replace) {
            db.jdbc().sql("""
                            update hr.special_status set status = 'CANCELLED', version = version + 1, updated_by = :a
                             where tenant_id = :t and person_id = :p and status = 'ACTIVE' and status_type in ('MINOR','ELDERLY_WORKER')
                               and classification_code = :m
                            returning id, status_type, valid_from, valid_to""")
                    .param("a", support.actor()).param("t", t).param("p", personId)
                    .param("m", PersonKeys.DERIVED_FROM_BIRTH_DATE)
                    .query((rs, n) -> new SpecialStatusChanged(t, personId, null, rs.getObject(1, UUID.class),
                            rs.getString(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                            "CANCELLED"))
                    .list().forEach(outbox::publish);
        }
        if (birthDate == null) {
            return;
        }
        LocalDate asOf = support.today();
        legal.find(PersonKeys.LP_MINOR_AGE_YEARS, Scope.tenant(), asOf).ifPresent(v ->
                insertDerived(t, personId, "MINOR", birthDate, birthDate.plusYears(v.asInt())));
        if (genderCode != null) {
            legal.find(PersonKeys.LP_ELDERLY_AGE_MONTHS, Scope.tenant(), asOf)
                    .map(v -> v.asMap().get(genderCode))
                    .ifPresent(months -> insertDerived(t, personId, "ELDERLY_WORKER",
                            birthDate.plusMonths(new java.math.BigDecimal(months.toString()).intValueExact()), null));
        }
    }

    private void insertDerived(UUID t, UUID personId, String type, LocalDate from, LocalDate to) {
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into hr.special_status (tenant_id, id, person_id, status_type, valid_from, valid_to,
                               classification_code, created_by, updated_by)
                        values (:t, :id, :p, :type, :from, :to, :m, :a, :a)""")
                .param("t", t).param("id", id).param("p", personId).param("type", type).param("from", from)
                .param("to", to).param("m", PersonKeys.DERIVED_FROM_BIRTH_DATE).param("a", support.actor())
                .update();
        outbox.publish(new SpecialStatusChanged(t, personId, null, id, type, from, to, "DERIVED"));
    }

    /**
     * BR-PPL-073 input for the separation stream: the protective statuses ({@link #PROTECTIVE}) of the person active
     * on {@code on}. {@code employmentId} is accepted for employment-scoped rules and future types; today all
     * protective statuses are person-level. No permission check (internal call of an authorised flow).
     */
    @HrTransactional(readOnly = true)
    public List<String> protectedReasons(UUID personId, UUID employmentId, LocalDate on) {
        return db.jdbc().sql("""
                        select distinct status_type from hr.special_status
                         where tenant_id = :t and person_id = :p and status = 'ACTIVE' and valid_during @> cast(:d as date)
                           and status_type in ('PREGNANCY','CHILD_UNDER_12M')
                           and (employment_id is null or employment_id = :e)
                         order by status_type""")
                .param("t", support.tenant()).param("p", personId).param("d", on).param("e", employmentId)
                .query(String.class).list();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private record StatusRow(String type, UUID employmentId, LocalDate validFrom, LocalDate validTo, boolean derived) {
    }

    private StatusRow row(UUID personId, UUID id) {
        return db.jdbc().sql("""
                        select status_type, employment_id, valid_from, valid_to, classification_code
                          from hr.special_status where tenant_id = :t and id = :id and person_id = :p""")
                .param("t", support.tenant()).param("id", id).param("p", personId)
                .query((rs, n) -> new StatusRow(rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                        PersonKeys.DERIVED_FROM_BIRTH_DATE.equals(rs.getString(5))))
                .optional().orElseThrow(() -> ApiException.notFound("Special status", id));
    }

    private StatusView find(UUID id) {
        masking.apply(PeoplePermissions.SPECIAL_STATUS_VIEW);
        return db.jdbc().sql("""
                        select m.*, s.classification_code as raw_class, s.evidence_document_id, s.version
                          from hr.v_special_status_masked m
                          join hr.special_status s on s.tenant_id = m.tenant_id and s.id = m.id
                         where m.tenant_id = :t and m.id = :id""")
                .param("t", support.tenant()).param("id", id)
                .query((rs, n) -> new StatusView(id, rs.getObject("person_id", UUID.class),
                        rs.getObject("employment_id", UUID.class), rs.getString("status_type"),
                        rs.getBoolean("is_sensitive"), rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class), rs.getString("classification_code"),
                        PersonKeys.DERIVED_FROM_BIRTH_DATE.equals(rs.getString("raw_class")),
                        rs.getObject("evidence_document_id", UUID.class), rs.getString("status"), rs.getInt("version")))
                .single();
    }

    private Sealed seal(UUID t, UUID personId, Map<String, Object> details) {
        return details == null || details.isEmpty() ? null
                : cipher.encrypt(Subject.person(t, personId), "detail", json.write(details));
    }

    private static void checkPeriod(LocalDate from, LocalDate to) {
        if (to != null && !to.isAfter(from)) {
            throw ApiException.unprocessable("invalid-period", "validTo (exclusive) must be after validFrom");
        }
    }
}
