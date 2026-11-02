package vn.yourhrm.core.api;

/** Permission codes owned by Core (registered through Core's {@code PermissionCatalogue}). */
public final class CorePermissions {

    public static final String PERMISSION_VIEW = "core.permission.view";

    public static final String ROLE_VIEW = "core.role.view";
    public static final String ROLE_CREATE = "core.role.create";
    public static final String ROLE_UPDATE = "core.role.update";
    public static final String ROLE_DELETE = "core.role.delete";

    public static final String USER_VIEW = "core.user.view";
    public static final String USER_CREATE = "core.user.create";
    public static final String USER_UPDATE = "core.user.update";

    public static final String ROLE_ASSIGNMENT_VIEW = "core.role_assignment.view";
    public static final String ROLE_ASSIGNMENT_CREATE = "core.role_assignment.create";
    public static final String ROLE_ASSIGNMENT_DELETE = "core.role_assignment.delete";

    public static final String COMPANY_GROUP_VIEW = "core.company_group.view";
    public static final String COMPANY_GROUP_UPDATE = "core.company_group.update";

    public static final String LEGAL_ENTITY_VIEW = "core.legal_entity.view";
    public static final String LEGAL_ENTITY_CREATE = "core.legal_entity.create";
    public static final String LEGAL_ENTITY_UPDATE = "core.legal_entity.update";

    public static final String LOCATION_VIEW = "core.location.view";
    public static final String LOCATION_CREATE = "core.location.create";
    public static final String LOCATION_UPDATE = "core.location.update";

    public static final String ORG_UNIT_VIEW = "core.org_unit.view";
    public static final String ORG_UNIT_CREATE = "core.org_unit.create";
    public static final String ORG_UNIT_UPDATE = "core.org_unit.update";
    public static final String ORG_UNIT_TYPE_UPDATE = "core.org_unit_type.update";

    /** Job families, job titles, grade structures and grades (BR-CORE-020/021). */
    public static final String JOB_CATALOGUE_VIEW = "core.job_catalogue.view";
    public static final String JOB_CATALOGUE_UPDATE = "core.job_catalogue.update";

    public static final String COST_CENTRE_VIEW = "core.cost_centre.view";
    public static final String COST_CENTRE_CREATE = "core.cost_centre.create";
    public static final String COST_CENTRE_UPDATE = "core.cost_centre.update";

    public static final String EMPLOYEE_CATEGORY_VIEW = "core.employee_category.view";
    public static final String EMPLOYEE_CATEGORY_UPDATE = "core.employee_category.update";

    /** Workflow (BR-CORE-050..061): others' requests in scope, task reassignment, the console, definitions. */
    public static final String WORKFLOW_VIEW = "core.workflow.view";
    public static final String WORKFLOW_REASSIGN = "core.workflow.reassign";
    public static final String WORKFLOW_ADMIN = "core.workflow.admin";
    public static final String WORKFLOW_DEFINITION_VIEW = "core.workflow_definition.view";
    public static final String WORKFLOW_DEFINITION_MANAGE = "core.workflow_definition.manage";

    /** Notification centre (BR-CORE-070): delivery monitor + resend, template registry + preview. */
    public static final String NOTIFICATION_ADMIN = "core.notification.admin";
    public static final String NOTIFICATION_TEMPLATE_VIEW = "core.notification_template.view";

    /** Tenant settings (BR-CORE-084) and number series (BR-CORE-085). */
    public static final String SETTING_VIEW = "core.setting.view";
    public static final String SETTING_UPDATE = "core.setting.update";
    public static final String SEQUENCE_VIEW = "core.sequence.view";
    public static final String SEQUENCE_UPDATE = "core.sequence.update";

    /** Audit search and chain verification (BR-CORE-093, "AUDIT_VIEW"); unmasked P2/P3 values in diffs. */
    public static final String AUDIT_VIEW = "core.audit.view";
    public static final String AUDIT_VIEW_SENSITIVE = "core.audit.view_sensitive";

    /** Document templates (BR-CORE-102): view and preview; upload, publish, letterhead / seal. */
    public static final String DOCUMENT_TEMPLATE_VIEW = "core.document_template.view";
    public static final String DOCUMENT_TEMPLATE_MANAGE = "core.document_template.manage";

    /**
     * Imports (BR-CORE-110..114): the job history of every import type, and running Core's own import types
     * (org units, cost centres). Other modules' import types are run with the permission their type declares.
     */
    public static final String IMPORT_VIEW = "core.import.view";
    public static final String IMPORT_RUN = "core.import.run";

    /** Platform support sessions in the tenant and their actions (BR-CORE-041). */
    public static final String SUPPORT_SESSION_VIEW = "core.support_session.view";

    private CorePermissions() {
    }
}
