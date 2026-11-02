package vn.yourhrm.shared.workflow.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A process type a module registers at deploy (BR-CORE-050, US-CORE-050; global table {@code core.process_type}).
 *
 * @param code            {@code <module>.<NAME>}, e.g. {@code att.LEAVE_REQUEST}
 * @param summarySchema   JSON schema of the request summary shown to approvers (no P3 values, no salary amounts
 *                        unless the type is payroll-scoped)
 * @param conditionFields request fields definitions may use in their conditions
 * @param allowedActions  actions allowed on this type's tasks (APPROVE / REJECT / WITHDRAW / COMMENT are always on)
 * @param defaultSteps    the vendor-seeded definition (US-CORE-051): every tenant without a definition of its own
 *                        gets it on the first submit
 */
public record ProcessTypeDefinition(String code, String nameVi, String nameEn, Map<String, Object> summarySchema,
        List<String> conditionFields, Set<WorkflowAction> allowedActions, boolean allowsAutoApprove,
        boolean requiresStepUp, List<StepTemplate> defaultSteps) {

    private static final Pattern CODE = Pattern.compile("^[a-z]+\\.[A-Z][A-Z0-9_]*$");

    public ProcessTypeDefinition {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Process type code must look like att.LEAVE_REQUEST: " + code);
        }
        summarySchema = summarySchema == null ? Map.of() : Map.copyOf(summarySchema);
        conditionFields = conditionFields == null ? List.of() : List.copyOf(conditionFields);
        allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
        defaultSteps = defaultSteps == null ? List.of() : List.copyOf(defaultSteps);
    }

    public String module() {
        return code.substring(0, code.indexOf('.'));
    }
}
