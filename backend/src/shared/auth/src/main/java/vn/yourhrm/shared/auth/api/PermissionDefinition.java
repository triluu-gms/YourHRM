package vn.yourhrm.shared.auth.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One permission of a module's catalogue (US-CORE-033, BR-CORE-031). Mirrors {@code core.permission}.
 *
 * @param code          {@code <module>.<function>.<action>}, e.g. {@code core.role.update}
 * @param functionCode  menu / resource, e.g. {@code core.role}
 * @param action        action kind
 * @param nameVi        Vietnamese label
 * @param nameEn        English label
 * @param sensitive     every use is audited (view-sensitive / compensation)
 * @param privileged    granting it needs approval (BR-CORE-036, deferred)
 * @param requiresStepUp MFA step-up at use (ADR-0005, deferred)
 */
public record PermissionDefinition(
        String code,
        String functionCode,
        PermissionAction action,
        String nameVi,
        String nameEn,
        boolean sensitive,
        boolean privileged,
        boolean requiresStepUp) {

    /** Same pattern as the {@code permission_code_ck} CHECK constraint. */
    public static final Pattern CODE_PATTERN = Pattern.compile("^[a-z]+(\\.[a-z0-9_]+)+$");

    public PermissionDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(functionCode, "functionCode");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(nameVi, "nameVi");
        Objects.requireNonNull(nameEn, "nameEn");
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid permission code: " + code);
        }
        if (!code.startsWith(functionCode + ".")) {
            throw new IllegalArgumentException("Permission " + code + " must start with its function " + functionCode);
        }
    }

    public static PermissionDefinition of(String code, PermissionAction action, String nameVi, String nameEn) {
        String function = code.substring(0, code.lastIndexOf('.'));
        return new PermissionDefinition(code, function, action, nameVi, nameEn, false, false, false);
    }

    public PermissionDefinition asPrivileged() {
        return new PermissionDefinition(code, functionCode, action, nameVi, nameEn, sensitive, true, requiresStepUp);
    }

    public PermissionDefinition asSensitive() {
        return new PermissionDefinition(code, functionCode, action, nameVi, nameEn, true, privileged, requiresStepUp);
    }
}
