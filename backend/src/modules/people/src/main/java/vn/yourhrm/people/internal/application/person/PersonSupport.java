package vn.yourhrm.people.internal.application.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.core.api.MasterData;
import vn.yourhrm.people.api.PersonConsentRegistry;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Shared plumbing of the person stream: tenant / actor, existence and version checks, code-list validation against
 * Core master data, consent lookups and document attachment checks. Every method runs in (or joins) a People
 * transaction.
 */
@Component
public class PersonSupport {

    private final HrDb db;
    private final MasterData masterData;
    private final Documents documents;
    private final BusinessClock clock;
    private final ObjectProvider<PersonConsentRegistry> consent;
    private final EmployeeAccess access;

    public PersonSupport(HrDb db, MasterData masterData, Documents documents, BusinessClock clock,
            ObjectProvider<PersonConsentRegistry> consent, EmployeeAccess access) {
        this.db = db;
        this.masterData = masterData;
        this.documents = documents;
        this.clock = clock;
        this.consent = consent;
        this.access = access;
    }

    public UUID tenant() {
        return TenantContext.current().tenantId();
    }

    public UUID actor() {
        return TenantContext.current().userAccountId();
    }

    public LocalDate today() {
        return clock.today();
    }

    public LocalDate orToday(LocalDate date) {
        return date == null ? clock.today() : date;
    }

    /** Scope check on a person (any employment in scope, or a tenant-wide grant for a person without one). */
    public void requirePerson(String permission, UUID personId) {
        access.requirePerson(permission, personId, clock.today());
    }

    /** Locks the person row; 404 when missing in the tenant. */
    @HrTransactional
    public void lockPerson(UUID personId) {
        db.jdbc().sql("select id from hr.person where tenant_id = :t and id = :id for update")
                .param("t", tenant()).param("id", personId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Person", personId));
    }

    /** Validates {@code value} of Core code list {@code list} on {@code asOf}; null passes. */
    public String code(String list, String value, LocalDate asOf) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (!masterData.isValidCode(list, v, asOf)) {
            throw ApiException.unprocessable("unknown-code", "Code " + v + " is not an active value of list " + list,
                    Map.of("list", list, "value", v));
        }
        return v;
    }

    public boolean hasConsent(UUID personId, String purpose) {
        return consent.getIfAvailable(() -> PersonConsentRegistry.NONE).hasConsent(tenant(), personId, purpose);
    }

    public void requireConsent(UUID personId, String purpose) {
        if (!hasConsent(personId, purpose)) {
            throw ApiException.unprocessable("consent-required", "Processing this optional sensitive data needs the "
                    + "person's consent for purpose " + purpose + " (BR-PPL-151)", Map.of("purpose", purpose));
        }
    }

    /**
     * Checks that Core document {@code documentId} belongs to the record ({@code ownerEntityType}, {@code ownerId}) and
     * is of {@code documentTypeCode}; Core also checks the caller's right to view it.
     */
    public void requireDocument(UUID documentId, String documentTypeCode, String ownerEntityType, UUID ownerId) {
        Documents.DocumentView d;
        try {
            d = documents.get(documentId);
        } catch (ApiException e) {
            throw ApiException.unprocessable("invalid-document", "Document " + documentId + " is not available");
        }
        if (!documentTypeCode.equals(d.documentTypeCode()) || !ownerEntityType.equals(d.ownerEntityType())
                || !ownerId.equals(d.ownerEntityId())) {
            throw ApiException.unprocessable("invalid-document", "Document " + documentId + " must be a "
                    + documentTypeCode + " uploaded for " + ownerEntityType + " " + ownerId);
        }
    }

    /** Fails with 409 stale-version when an optimistic UPDATE touched no row. */
    public static void requireUpdated(int updated, String what, UUID id) {
        if (updated == 0) {
            throw ApiException.conflict("stale-version", what + " " + id + " was changed meanwhile; reload and retry");
        }
    }

    /** Authorisation targets (as of {@code asOf}) of the person's employments that are not cancelled. */
    @HrTransactional(readOnly = true)
    public List<AuthorizationTarget> employeeTargets(UUID personId, LocalDate asOf) {
        return db.jdbc().sql("select e.id, e.legal_entity_id, pa.org_unit_id, pa.location_id from "
                        + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " where e.tenant_id = :t and e.person_id = :p and e.status <> 'CANCELLED' order by e.hire_date desc")
                .param("t", tenant()).param("p", personId).param("asOf", asOf)
                .query((rs, n) -> (AuthorizationTarget) new AuthorizationTarget.Employee(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)))
                .list();
    }

    /** Translates a constraint violation into a 409 with a stable code. */
    public static ApiException conflictOf(DataIntegrityViolationException e, String code, String message) {
        return ApiException.conflict(code, message);
    }

    public HrDb db() {
        return db;
    }
}
