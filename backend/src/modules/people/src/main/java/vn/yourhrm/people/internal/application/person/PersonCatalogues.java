package vn.yourhrm.people.internal.application.person;

import static vn.yourhrm.people.internal.application.person.PersonKeys.DOC_IDENTITY_SCAN;
import static vn.yourhrm.people.internal.application.person.PersonKeys.DOC_QUALIFICATION_SCAN;
import static vn.yourhrm.people.internal.application.person.PersonKeys.DOC_SPECIAL_STATUS_EVIDENCE;
import static vn.yourhrm.people.internal.application.person.PersonKeys.DOC_TAX_RESIDENCY_EVIDENCE;
import static vn.yourhrm.people.internal.application.person.PersonKeys.DOC_WORK_PERMIT_SCAN;
import static vn.yourhrm.people.internal.application.person.PersonKeys.OWNER_IDENTITY_DOC;
import static vn.yourhrm.people.internal.application.person.PersonKeys.OWNER_QUALIFICATION;
import static vn.yourhrm.people.internal.application.person.PersonKeys.OWNER_SPECIAL_STATUS;
import static vn.yourhrm.people.internal.application.person.PersonKeys.OWNER_TAX_RESIDENCY;
import static vn.yourhrm.people.internal.application.person.PersonKeys.OWNER_VN_PRESENCE;
import static vn.yourhrm.people.internal.application.person.PersonKeys.OWNER_WORK_PERMIT;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;

/**
 * What the person stream registers with Core (module {@code hr}): the tax-residency approval process type
 * (US-PPL-013), the expiry alert settings and notification templates (US-PPL-007/011, US-PPL-059 share), and the
 * document types of scans / evidence (US-PPL-007/010/011/012/013) with their owner resolver.
 */
@Component
public class PersonCatalogues implements ProcessTypeCatalogue, SettingCatalogue, NotificationTemplateCatalogue,
        DocumentTypeCatalogue, DocumentOwnerResolver {

    /** Role whose holders (in scope of the request) approve tax-residency changes by default. */
    public static final String TAX_RESIDENCY_APPROVER_ROLE = "HR_ADMIN";

    private final HrDb db;
    private final BusinessClock clock;

    public PersonCatalogues(HrDb db, BusinessClock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public String module() {
        return "hr";
    }

    // ---- process types ---------------------------------------------------------------------------------------------

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        return List.of(new ProcessTypeDefinition(PersonKeys.PROCESS_TAX_RESIDENCY, "Thay đổi cư trú thuế",
                "Tax residency change", Map.of("type", "object", "properties", Map.of(
                        "status", Map.of("type", "string"), "basis", Map.of("type", "string"),
                        "validFrom", Map.of("type", "string"), "validTo", Map.of("type", "string"))),
                List.of("status", "basis"), Set.of(), false, false,
                List.of(new StepTemplate("hr", "Nhân sự phê duyệt", "HR approval",
                        ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", TAX_RESIDENCY_APPROVER_ROLE),
                        true))));
    }

    // ---- settings --------------------------------------------------------------------------------------------------

    @Override
    public List<SettingDefinition> settings() {
        Set<String> levels = Set.of("TENANT", "LEGAL_ENTITY");
        return List.of(
                new SettingDefinition(PersonKeys.SETTING_ID_DOC_ALERT_DAYS, "INT", List.of(), 30, levels, false, false,
                        "Số ngày cảnh báo trước khi giấy tờ tùy thân hết hạn",
                        "Days before an identity document expires to alert HR"),
                new SettingDefinition(PersonKeys.SETTING_WP_ALERT_DAYS_FIRST, "INT", List.of(), 60, levels, false,
                        false, "Cảnh báo lần 1 trước khi giấy phép lao động hết hạn (ngày)",
                        "First work-permit expiry alert (days before)"),
                new SettingDefinition(PersonKeys.SETTING_WP_ALERT_DAYS_SECOND, "INT", List.of(), 45, levels, false,
                        false, "Cảnh báo lần 2 trước khi giấy phép lao động hết hạn (ngày)",
                        "Second work-permit expiry alert (days before)"));
    }

    // ---- notification templates ------------------------------------------------------------------------------------

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<Placeholder> ph = List.of(new Placeholder("employeeNo", Kind.TEXT), new Placeholder("documentType", Kind.TEXT),
                new Placeholder("expiryDate", Kind.DATE), new Placeholder("daysLeft", Kind.NUMBER));
        String idVi = "Giấy tờ {{documentType}} của nhân viên {{employeeNo}} hết hạn ngày {{expiryDate}} (còn {{daysLeft}} ngày).";
        String idEn = "Identity document {{documentType}} of employee {{employeeNo}} expires on {{expiryDate}} ({{daysLeft}} days left).";
        String wpVi = "Giấy phép lao động ({{documentType}}) của nhân viên {{employeeNo}} hết hiệu lực ngày {{expiryDate}} "
                + "(còn {{daysLeft}} ngày). Cần gia hạn trước khi hết hạn.";
        String wpEn = "Work permit ({{documentType}}) of employee {{employeeNo}} expires on {{expiryDate}} "
                + "({{daysLeft}} days left). Renew it before it expires.";
        return List.of(
                tpl(PersonKeys.EVENT_ID_DOC_EXPIRING, "IN_APP", "vi-VN", "Giấy tờ tùy thân sắp hết hạn", idVi, ph),
                tpl(PersonKeys.EVENT_ID_DOC_EXPIRING, "IN_APP", "en-US", "Identity document expiring", idEn, ph),
                tpl(PersonKeys.EVENT_ID_DOC_EXPIRING, "EMAIL", "vi-VN", "Giấy tờ tùy thân sắp hết hạn",
                        idVi + " {{deepLink}}", ph),
                tpl(PersonKeys.EVENT_WORK_PERMIT_EXPIRING, "IN_APP", "vi-VN", "Giấy phép lao động sắp hết hạn", wpVi, ph),
                tpl(PersonKeys.EVENT_WORK_PERMIT_EXPIRING, "IN_APP", "en-US", "Work permit expiring", wpEn, ph),
                tpl(PersonKeys.EVENT_WORK_PERMIT_EXPIRING, "EMAIL", "vi-VN", "Giấy phép lao động sắp hết hạn",
                        wpVi + " {{deepLink}}", ph));
    }

    private static NotificationTemplateDefinition tpl(String event, String channel, String locale, String subject,
            String body, List<Placeholder> ph) {
        return new NotificationTemplateDefinition(event, "REMINDER", true, channel, locale, subject, body, "TEXT", ph,
                false);
    }

    // ---- document types --------------------------------------------------------------------------------------------

    @Override
    public List<DocumentTypeDefinition> documentTypes() {
        return List.of(
                new DocumentTypeDefinition(DOC_IDENTITY_SCAN, "hr", OWNER_IDENTITY_DOC, "Bản scan giấy tờ tùy thân",
                        "Identity document scan", "RESTRICTED", "EMPLOYEE_FILE", PeoplePermissions.PERSON_VIEW_SENSITIVE,
                        PeoplePermissions.PERSON_UPDATE, Set.of(), null),
                new DocumentTypeDefinition(DOC_WORK_PERMIT_SCAN, "hr", OWNER_WORK_PERMIT, "Bản scan giấy phép lao động",
                        "Work permit scan", "CONFIDENTIAL", "EMPLOYEE_FILE", PeoplePermissions.EMPLOYEE_VIEW,
                        PeoplePermissions.WORK_PERMIT_UPDATE, Set.of(), null),
                new DocumentTypeDefinition(DOC_SPECIAL_STATUS_EVIDENCE, "hr", OWNER_SPECIAL_STATUS,
                        "Bằng chứng tình trạng đặc biệt", "Special status evidence", "RESTRICTED", "EMPLOYEE_FILE",
                        PeoplePermissions.SPECIAL_STATUS_VIEW, PeoplePermissions.SPECIAL_STATUS_UPDATE, Set.of(), null),
                new DocumentTypeDefinition(DOC_TAX_RESIDENCY_EVIDENCE, "hr", OWNER_TAX_RESIDENCY,
                        "Bằng chứng cư trú thuế", "Tax residency evidence", "CONFIDENTIAL", "EMPLOYEE_FILE",
                        PeoplePermissions.EMPLOYEE_VIEW, PeoplePermissions.TAX_RESIDENCY_UPDATE, Set.of(), null),
                new DocumentTypeDefinition(DOC_QUALIFICATION_SCAN, "hr", OWNER_QUALIFICATION, "Bản scan bằng cấp",
                        "Diploma scan", "CONFIDENTIAL", "EMPLOYEE_FILE", PeoplePermissions.EMPLOYEE_VIEW,
                        PeoplePermissions.PERSON_UPDATE, Set.of(), null));
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of(OWNER_IDENTITY_DOC, OWNER_WORK_PERMIT, OWNER_SPECIAL_STATUS, OWNER_TAX_RESIDENCY,
                OWNER_VN_PRESENCE, OWNER_QUALIFICATION);
    }

    /**
     * The record's employee: the work permit's (or HAZARDOUS_JOB status's) employment, else the person's most recent
     * non-cancelled employment; a person without employment resolves to the tenant (tenant-wide grants only).
     */
    @Override
    @HrTransactional(readOnly = true)
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        String table = switch (ownerEntityType) {
            case OWNER_IDENTITY_DOC, OWNER_WORK_PERMIT, OWNER_SPECIAL_STATUS, OWNER_TAX_RESIDENCY, OWNER_VN_PRESENCE,
                 OWNER_QUALIFICATION -> ownerEntityType;
            default -> null;
        };
        if (table == null) {
            return Optional.empty();
        }
        boolean hasEmployment = OWNER_WORK_PERMIT.equals(table) || OWNER_SPECIAL_STATUS.equals(table);
        UUID tenant = TenantContext.current().tenantId();
        record Owner(UUID personId, UUID employmentId) {
        }
        Optional<Owner> owner = db.jdbc().sql("select person_id, " + (hasEmployment ? "employment_id" : "null::uuid")
                        + " from " + table + " where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", ownerEntityId)
                .query((rs, n) -> new Owner(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional();
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        String filter = owner.get().employmentId() != null ? "e.id = :emp" : "e.person_id = :p and e.status <> 'CANCELLED'";
        Optional<AuthorizationTarget> employee = db.jdbc().sql("""
                        select e.id, e.legal_entity_id, pa.org_unit_id, pa.location_id from hr.employment e
                          left join lateral (select x.org_unit_id, x.location_id from hr.assignment x
                                              where x.tenant_id = e.tenant_id and x.employment_id = e.id
                                                and x.assignment_type = 'PRIMARY' and x.superseded_at is null
                                              order by (x.valid_during @> cast(:d as date)) desc, x.valid_from desc
                                              limit 1) pa on true
                         where e.tenant_id = :t""" + " and " + filter + " order by e.hire_date desc limit 1")
                .param("t", tenant).param("emp", owner.get().employmentId()).param("p", owner.get().personId())
                .param("d", clock.today())
                .query((rs, n) -> (AuthorizationTarget) new AuthorizationTarget.Employee(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)))
                .optional();
        return Optional.of(employee.orElse(AuthorizationTarget.tenant()));
    }
}
