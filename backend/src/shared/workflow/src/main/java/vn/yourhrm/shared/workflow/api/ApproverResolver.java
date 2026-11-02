package vn.yourhrm.shared.workflow.api;

import java.util.Map;
import java.util.Objects;

/**
 * How a step finds its approver(s) (BR-CORE-052), evaluated at submit with the organisation as of that day.
 * Parameters per type:
 * <ul>
 *   <li>{@code LINE_MANAGER}: none; the manager of the request's org unit, or of the nearest ancestor when that
 *       manager is the requester / subject;</li>
 *   <li>{@code MANAGER_N_UP}: {@code levels} (1 = line manager);</li>
 *   <li>{@code ORG_UNIT_MANAGER}: {@code orgUnitId};</li>
 *   <li>{@code ROLE_IN_SCOPE}: {@code roleCode}; every holder of the role whose data scope covers the request (a
 *       pool: the first to act decides);</li>
 *   <li>{@code USER}: {@code userId};</li>
 *   <li>{@code REQUESTER_PICK}: optional {@code roleCode} restricting the choice to the ROLE_IN_SCOPE pool;</li>
 *   <li>{@code REQUEST_FIELD}: {@code field}, a condition value holding a user id (e.g. the project manager);</li>
 *   <li>{@code POSITION_HOLDER} (needs positions, P2) and {@code FUNCTIONAL_MANAGER} (P3): accepted in definitions
 *       but resolve to nothing, so the step's fallback applies.</li>
 * </ul>
 */
public record ApproverResolver(Type type, Map<String, String> params) {

    public enum Type {
        LINE_MANAGER, MANAGER_N_UP, ORG_UNIT_MANAGER, ROLE_IN_SCOPE, USER, REQUESTER_PICK, REQUEST_FIELD,
        POSITION_HOLDER, FUNCTIONAL_MANAGER
    }

    public ApproverResolver {
        Objects.requireNonNull(type, "type");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static ApproverResolver of(Type type) {
        return new ApproverResolver(type, Map.of());
    }

    public static ApproverResolver of(Type type, String key, String value) {
        return new ApproverResolver(type, Map.of(key, value));
    }
}
