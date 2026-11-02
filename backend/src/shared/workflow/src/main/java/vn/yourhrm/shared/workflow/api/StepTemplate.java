package vn.yourhrm.shared.workflow.api;

import java.util.Objects;

/**
 * One step of a workflow definition (DDL {@code workflow_definition_version.steps}). Phase 1 runs sequential
 * single-approver steps (mvp-scope: BR-CORE-053 is P2); a resolver that yields several users makes a pool in which
 * the first to act decides.
 *
 * @param stepKey     stable key, unique in the definition
 * @param allowReturn the approver may return the request to the requester or the previous step
 */
public record StepTemplate(String stepKey, String nameVi, String nameEn, ApproverResolver resolver,
        boolean allowReturn) {

    public StepTemplate {
        Objects.requireNonNull(stepKey, "stepKey");
        Objects.requireNonNull(nameVi, "nameVi");
        Objects.requireNonNull(resolver, "resolver");
    }
}
