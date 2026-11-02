package vn.yourhrm.people.internal.application.decision;

import java.util.List;
import java.util.Map;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;
import vn.yourhrm.people.internal.application.DecisionService.Decision;

/**
 * Type-specific merge fields of a decision template (US-PPL-058): the stream that owns the change behind a decision
 * type (transfer, salary change, termination ...) declares a bean for its type; its fields are added to the template
 * definition {@code HR_DECISION_<TYPE>} registered with Core, and its values to the generated document. The common
 * fields (employee, decision number / dates, signatory, job title) come from {@link DecisionDocumentService}.
 * Values must never contain P3 data; amounts only where the decision legally states them (salary change).
 */
public interface DecisionMergeFields {

    /** A {@code DecisionService.TYPES} code. */
    String decisionType();

    /** Field definitions; keys must not clash with the common ones ({@code employee.*}, {@code decision.*} ...). */
    List<MergeField> fields();

    /** Values for {@code decision}, one per declared field (empty text when unknown); called in a People transaction. */
    Map<String, Object> values(Decision decision);
}
