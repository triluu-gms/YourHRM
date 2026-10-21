package vn.yourhrm.shared.auth.api;

/**
 * Data-scope kinds of a role assignment, exactly as allowed by the {@code role_assignment_scope_ck}
 * CHECK constraint of {@code core.role_assignment} (002_core.sql).
 */
public enum ScopeType {
    /** Whole tenant. */
    TENANT,
    /** Listed legal entities ({@code scope_ids} = core.legal_entity ids). */
    LEGAL_ENTITY,
    /** Listed locations ({@code scope_ids} = core.location ids). */
    LOCATION,
    /** Listed org units, optionally with their subtree as of a date ({@code scope_ids} = core.org_unit ids). */
    ORG_UNIT,
    /** The user's own employment. */
    SELF,
    /** Employments directly reporting to the user (People data, via {@code ReportingLineProvider}). */
    DIRECT_REPORTS,
    /** All employments below the user in the reporting line (People data, via {@code ReportingLineProvider}). */
    ALL_REPORTS;

    /** Scope kinds whose {@code scope_ids} must be non-empty (DDL {@code role_assignment_scope_ids_ck}). */
    public boolean requiresIds() {
        return this == LEGAL_ENTITY || this == LOCATION || this == ORG_UNIT;
    }
}
