package vn.yourhrm.people.internal.application.decision;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.DecisionService.Decision;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The decision register for HR users (BR-PPL-130, US-PPL-057/058), on top of the lead's {@link DecisionService}:
 * list / view per employment, issue a decision manually, generate its document, record the paper signature with the
 * signed scan, cancel (the number is kept) and re-issue (cancel + a new number, same type and subject).
 *
 * <p>Manual issue covers every P1 type: the change flows issue their own decisions on approval; a manual decision is
 * for a change recorded outside a flow (e.g. before go-live). Cancelling or re-issuing a decision does not revert the
 * change it carried: the owning flow does that. A re-issue moves the suspension link
 * ({@code hr.contract_suspension.decision_id}) to the new decision; other streams' links are theirs to move.
 */
@Service
public class DecisionRegister {

    public record ManualIssue(UUID employmentId, String decisionTypeCode, LocalDate effectiveDate,
            String signatoryName, String signatoryTitle) {
    }

    public record Reissue(LocalDate effectiveDate, String signatoryName, String signatoryTitle, int version) {
    }

    private final HrDb db;
    private final DecisionService decisions;
    private final DecisionDocumentService documentsGen;
    private final Documents documents;
    private final EmployeeAccess access;
    private final AuthorizationService authz;
    private final BusinessClock clock;

    public DecisionRegister(HrDb db, DecisionService decisions, DecisionDocumentService documentsGen,
            Documents documents, EmployeeAccess access, AuthorizationService authz, BusinessClock clock) {
        this.db = db;
        this.decisions = decisions;
        this.documentsGen = documentsGen;
        this.documents = documents;
        this.access = access;
        this.authz = authz;
        this.clock = clock;
    }

    /** Decisions about an employment, newest effective date first. */
    @HrTransactional(readOnly = true)
    public List<Decision> list(UUID employmentId) {
        access.require(PeoplePermissions.DECISION_VIEW, employmentId, clock.today());
        return db.jdbc().sql("""
                        select id from hr.decision where tenant_id = :t and employment_id = :e
                         order by effective_date desc, decision_no desc""")
                .param("t", tenant()).param("e", employmentId).query(UUID.class).list()
                .stream().map(id -> decisions.find(id).orElseThrow()).toList();
    }

    @HrTransactional(readOnly = true)
    public Decision get(UUID decisionId) {
        Decision d = load(decisionId);
        require(PeoplePermissions.DECISION_VIEW, d);
        return d;
    }

    @HrTransactional
    public Decision issue(ManualIssue r) {
        if (r.employmentId() == null) {
            throw ApiException.unprocessable("employment-required", "A manual decision is about one employment");
        }
        access.require(PeoplePermissions.DECISION_CREATE, r.employmentId(), clock.today());
        UUID le = db.jdbc().sql("select legal_entity_id from hr.employment where tenant_id = :t and id = :e")
                .param("t", tenant()).param("e", r.employmentId()).query(UUID.class).single();
        if (r.effectiveDate() == null) {
            throw ApiException.unprocessable("effective-date-required", "The effective date is required");
        }
        return decisions.issue(new DecisionService.Issue(le, r.decisionTypeCode(), r.employmentId(), r.effectiveDate(),
                r.signatoryName(), r.signatoryTitle(), null));
    }

    /** Generates the decision document from the tenant's bilingual template of its type (US-PPL-058). */
    @HrTransactional(readOnly = true)
    public Generated generate(UUID decisionId) {
        Decision d = load(decisionId);
        require(PeoplePermissions.DECISION_UPDATE, d);
        return documentsGen.generate(d);
    }

    /** Paper signature: sign date + signed scan in Core's document service (APPROVED → SIGNED). */
    @HrTransactional
    public Decision sign(UUID decisionId, LocalDate signDate, String fileName, String mediaType, byte[] scan,
            int expectedVersion) {
        Decision d = load(decisionId);
        require(PeoplePermissions.DECISION_UPDATE, d);
        if (!"APPROVED".equals(d.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Decision " + d.decisionNo() + " is "
                    + d.status() + "; only an approved decision can be signed");
        }
        if (d.version() != expectedVersion) {
            throw ApiException.conflict("stale-version", "Decision " + d.decisionNo() + " was changed; reload and retry");
        }
        if (signDate == null || signDate.isAfter(clock.today())) {
            throw ApiException.unprocessable("invalid-sign-date", "The sign date is required and cannot be in the future");
        }
        UUID person = d.employmentId() == null ? null : db.jdbc()
                .sql("select person_id from hr.employment where tenant_id = :t and id = :e")
                .param("t", tenant()).param("e", d.employmentId()).query(UUID.class).single();
        Documents.DocumentView doc = documents.upload(new Documents.Upload(DecisionDocuments.DECISION_DOC, d.id(),
                d.legalEntityId(), person, "QĐ " + d.decisionNo(), null, fileName, mediaType, scan));
        return decisions.markSigned(d.id(), signDate, doc.id(), expectedVersion);
    }

    @HrTransactional
    public Decision cancel(UUID decisionId, int expectedVersion) {
        Decision d = load(decisionId);
        require(PeoplePermissions.DECISION_UPDATE, d);
        if ("CANCELLED".equals(d.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Decision " + d.decisionNo()
                    + " is already cancelled");
        }
        return decisions.cancel(decisionId, expectedVersion);
    }

    /** Cancels {@code decisionId} (it keeps its number) and issues its replacement with a new number. */
    @HrTransactional
    public Decision reissue(UUID decisionId, Reissue r) {
        Decision d = load(decisionId);
        require(PeoplePermissions.DECISION_CREATE, d);
        require(PeoplePermissions.DECISION_UPDATE, d);
        if ("CANCELLED".equals(d.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Decision " + d.decisionNo()
                    + " is cancelled; issue a new one");
        }
        if (d.version() != r.version()) {
            throw ApiException.conflict("stale-version", "Decision " + d.decisionNo() + " was changed; reload and retry");
        }
        Decision replacement = decisions.issue(new DecisionService.Issue(d.legalEntityId(), d.decisionTypeCode(),
                d.employmentId(), r.effectiveDate() != null ? r.effectiveDate() : d.effectiveDate(),
                r.signatoryName() != null ? r.signatoryName() : d.signatoryName(),
                r.signatoryTitle() != null ? r.signatoryTitle() : d.signatoryTitle(), d.workflowInstanceId()));
        decisions.cancel(decisionId, r.version());
        db.jdbc().sql("""
                        update hr.contract_suspension set decision_id = :n, version = version + 1
                         where tenant_id = :t and decision_id = :o""")
                .param("n", replacement.id()).param("t", tenant()).param("o", decisionId).update();
        return replacement;
    }

    private Decision load(UUID decisionId) {
        return decisions.find(decisionId).orElseThrow(() -> ApiException.notFound("Decision", decisionId));
    }

    /** Employee scope, or the legal entity's for a collective decision; 404 when out of scope. */
    private void require(String permission, Decision d) {
        boolean ok = d.employmentId() != null
                ? access.can(permission, d.employmentId(), clock.today())
                : authz.can(permission, new AuthorizationTarget.LegalEntity(d.legalEntityId()), clock.today());
        if (!ok) {
            throw ApiException.notFound("Decision", d.id());
        }
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

}
