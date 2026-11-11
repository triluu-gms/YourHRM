package vn.yourhrm.people.internal.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.Numbering;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * HR decisions (quyết định, BR-PPL-130, US-PPL-057): every P1 HR change is issued by exactly one numbered decision.
 * The number comes from Core's {@code DECISION_NO} series (per legal entity, typically yearly) when the change is
 * approved; a cancelled decision keeps its number (the trail stays complete) and a re-issue is a new decision.
 * Signing is on paper in P1 (R2-10): the signed scan is attached afterwards.
 *
 * <p>Internal service used by the change flows (hire, job change, salary change, contract, suspension, separation)
 * inside their People transaction; it does not check permissions (the calling flow already did).
 * Note: the number is drawn in Core's own transaction (ADR-0021), so a People rollback can leave a hole.
 */
@Service
public class DecisionService {

    /** Core number series of decisions (BR-CORE-085); configured per tenant. */
    public static final String SEQUENCE = "DECISION_NO";

    /** P1 decision types (US-PPL-058); reward, discipline, secondment and concurrent-position decisions are P2. */
    public static final Set<String> TYPES = Set.of("HIRE", "PROBATION_RESULT", "TRANSFER", "PROMOTION", "DEMOTION",
            "TEMPORARY_TRANSFER", "JOB_CHANGE", "SALARY_CHANGE", "CONTRACT_SUSPENSION", "TERMINATION", "RETIREMENT");

    /** What a change flow issues. {@code employmentId} is null only for collective decisions. */
    public record Issue(UUID legalEntityId, String decisionTypeCode, UUID employmentId, LocalDate effectiveDate,
            String signatoryName, String signatoryTitle, UUID workflowInstanceId) {
    }

    public record Decision(UUID id, UUID legalEntityId, String decisionNo, String decisionTypeCode, UUID employmentId,
            LocalDate effectiveDate, LocalDate signDate, String signatoryName, String signatoryTitle, UUID documentId,
            UUID workflowInstanceId, String status, int version) {
    }

    private final HrDb db;
    private final Numbering numbering;

    public DecisionService(HrDb db, Numbering numbering) {
        this.db = db;
        this.numbering = numbering;
    }

    /** Issues an APPROVED decision with the next number of the legal entity's series. */
    @HrTransactional
    public Decision issue(Issue issue) {
        if (!TYPES.contains(issue.decisionTypeCode())) {
            throw ApiException.unprocessable("invalid-decision-type",
                    "Unknown decision type " + issue.decisionTypeCode());
        }
        TenantContext ctx = TenantContext.current();
        String number;
        try {
            number = numbering.next(SEQUENCE, issue.legalEntityId(), issue.effectiveDate());
        } catch (RuntimeException ex) {
            throw ApiException.unprocessable("numbering-not-configured", "Number series " + SEQUENCE
                    + " is not configured for this legal entity: " + ex.getMessage(), Map.of("sequence", SEQUENCE));
        }
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into hr.decision (tenant_id, id, legal_entity_id, decision_no, decision_type_code,
                               employment_id, effective_date, signatory_name, signatory_title, workflow_instance_id,
                               status, created_by, updated_by)
                        values (:t, :id, :le, :no, :type, :emp, :eff, :sn, :st, :wf, 'APPROVED', :actor, :actor)""")
                .param("t", ctx.tenantId()).param("id", id).param("le", issue.legalEntityId()).param("no", number)
                .param("type", issue.decisionTypeCode()).param("emp", issue.employmentId())
                .param("eff", issue.effectiveDate()).param("sn", issue.signatoryName())
                .param("st", issue.signatoryTitle()).param("wf", issue.workflowInstanceId())
                .param("actor", ctx.userAccountId())
                .update();
        return find(id).orElseThrow();
    }

    /**
     * Issues a numbered decision that still awaits the approval of the change it carries (PENDING_APPROVAL), e.g. a hire
     * whose workflow is running; {@link #approve} or {@link #cancel} settles it. The number is drawn now so the
     * approver sees the document that will be issued.
     */
    @HrTransactional
    public Decision issuePending(Issue issue) {
        Decision d = issue(issue);
        db.jdbc().sql("""
                        update hr.decision set status = 'PENDING_APPROVAL', version = version + 1, updated_at = now()
                         where tenant_id = :t and id = :id and status = 'APPROVED'""")
                .param("t", TenantContext.current().tenantId()).param("id", d.id()).update();
        return find(d.id()).orElseThrow();
    }

    /** The carried change was approved (PENDING_APPROVAL → APPROVED). Idempotent for an already approved decision. */
    @HrTransactional
    public Decision approve(UUID id, UUID workflowInstanceId) {
        int updated = db.jdbc().sql("""
                        update hr.decision set status = 'APPROVED', workflow_instance_id = coalesce(:wf, workflow_instance_id),
                               version = version + 1, updated_by = :actor, updated_at = now()
                         where tenant_id = :t and id = :id and status = 'PENDING_APPROVAL'""")
                .param("wf", workflowInstanceId).param("actor", TenantContext.current().userAccountId())
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .update();
        Decision d = find(id).orElseThrow(() -> ApiException.notFound("Decision", id));
        if (updated == 0 && !"APPROVED".equals(d.status())) {
            throw ApiException.conflict("invalid-status-transition", "Decision " + d.decisionNo() + " is "
                    + d.status() + ", expected PENDING_APPROVAL");
        }
        return d;
    }

    /** Records the paper signature: sign date and the signed scan in Core's document service (APPROVED → SIGNED). */
    @HrTransactional
    public Decision markSigned(UUID id, LocalDate signDate, UUID documentId, int expectedVersion) {
        int updated = db.jdbc().sql("""
                        update hr.decision set status = 'SIGNED', sign_date = :sd, document_id = :doc,
                               version = version + 1, updated_by = :actor, updated_at = now()
                         where tenant_id = :t and id = :id and version = :v and status = 'APPROVED'""")
                .param("sd", signDate).param("doc", documentId).param("actor", TenantContext.current().userAccountId())
                .param("t", TenantContext.current().tenantId()).param("id", id).param("v", expectedVersion)
                .update();
        return afterTransition(id, updated, "APPROVED");
    }

    /** Cancels a decision; it keeps its number. The caller reverts or re-issues the change it carried. */
    @HrTransactional
    public Decision cancel(UUID id, int expectedVersion) {
        int updated = db.jdbc().sql("""
                        update hr.decision set status = 'CANCELLED', version = version + 1, updated_by = :actor,
                               updated_at = now()
                         where tenant_id = :t and id = :id and version = :v and status in ('DRAFT','PENDING_APPROVAL','APPROVED','SIGNED')""")
                .param("actor", TenantContext.current().userAccountId())
                .param("t", TenantContext.current().tenantId()).param("id", id).param("v", expectedVersion)
                .update();
        return afterTransition(id, updated, "not CANCELLED");
    }

    @HrTransactional(readOnly = true)
    public Optional<Decision> find(UUID id) {
        return db.jdbc().sql("select * from hr.decision where tenant_id = :t and id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .query((rs, n) -> new Decision(rs.getObject("id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                        rs.getString("decision_no"), rs.getString("decision_type_code"),
                        rs.getObject("employment_id", UUID.class), rs.getObject("effective_date", LocalDate.class),
                        rs.getObject("sign_date", LocalDate.class), rs.getString("signatory_name"),
                        rs.getString("signatory_title"), rs.getObject("document_id", UUID.class),
                        rs.getObject("workflow_instance_id", UUID.class), rs.getString("status"), rs.getInt("version")))
                .optional();
    }

    private Decision afterTransition(UUID id, int updated, String expectedState) {
        Decision d = find(id).orElseThrow(() -> ApiException.notFound("Decision", id));
        if (updated == 0) {
            throw ApiException.conflict("stale-version", "Decision " + d.decisionNo() + " is " + d.status()
                    + " (version " + d.version() + "), expected " + expectedState + "; reload and retry");
        }
        return d;
    }
}
