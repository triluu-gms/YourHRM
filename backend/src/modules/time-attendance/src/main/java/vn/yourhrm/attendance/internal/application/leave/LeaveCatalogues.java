package vn.yourhrm.attendance.internal.application.leave;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * What leave management registers with Core: the approval process of leave requests ({@code att.LEAVE}, ATT-P-11) and
 * the VN statutory leave types seeded per tenant (BR-ATT-056; DESIGN §12: leave types are tenant rows, not global).
 */
@Component
public class LeaveCatalogues implements ProcessTypeCatalogue {

    public static final String PROCESS_LEAVE = "att.LEAVE";
    public static final String SUBJECT_LEAVE = "att.leave_request";

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        return List.of(new ProcessTypeDefinition(PROCESS_LEAVE, "Nghỉ phép", "Leave request",
                Map.of("type", "object", "properties", Map.of(
                        "leaveType", Map.of("type", "string"),
                        "leaveCategory", Map.of("type", "string"),
                        "startDate", Map.of("type", "string"),
                        "endDate", Map.of("type", "string"),
                        "startPart", Map.of("type", "string"),
                        "endPart", Map.of("type", "string"),
                        "durationDays", Map.of("type", "number"),
                        "balanceAfter", Map.of("type", "number"),
                        "reason", Map.of("type", "string"))),
                List.of("leaveCategory", "durationDays"), Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO),
                false, false,
                // default: the line manager; tenants add HR steps (e.g. long or SI leave) in Core's workflow designer
                List.of(new StepTemplate("manager", "Quản lý duyệt", "Manager approval",
                        ApproverResolver.of(ApproverResolver.Type.LINE_MANAGER), true))));
    }

    // ------------------------------------------------------------------------------------- statutory types

    /**
     * A VN statutory leave type (BR-ATT-056, Điều 113/115 BLLĐ, Luật BHXH [verify-legal]). {@code personalKey} names
     * the entry of {@code LEAVE.PERSONAL.DAYS} that gives the maximum days per request; null = no maximum.
     */
    public record StatutoryType(String code, String nameVi, String nameEn, String category, String paidBy,
            String minUnit, String basis, boolean tracksBalance, boolean siClaim, boolean attachment,
            boolean countsAsService, String attendanceCode, String payrollCategory, Map<String, Object> eligibility,
            String personalKey, String status) {
    }

    public static final List<StatutoryType> STATUTORY_TYPES = List.of(
            new StatutoryType("ANNUAL", "Nghỉ phép năm", "Annual leave", "ANNUAL", "EMPLOYER", "HALF_DAY",
                    "WORKING_DAY", true, false, false, true, "P", "PAID_LEAVE_DAYS", Map.of(), null, "ACTIVE"),
            new StatutoryType("MARRIAGE_SELF", "Nghỉ kết hôn", "Own marriage", "PERSONAL_PAID", "EMPLOYER", "DAY",
                    "WORKING_DAY", false, false, false, true, "R", "PAID_LEAVE_DAYS", Map.of(), "MARRIAGE_SELF",
                    "ACTIVE"),
            new StatutoryType("MARRIAGE_CHILD", "Nghỉ con kết hôn", "Child's marriage", "PERSONAL_PAID", "EMPLOYER",
                    "DAY", "WORKING_DAY", false, false, false, true, "R", "PAID_LEAVE_DAYS", Map.of(),
                    "MARRIAGE_CHILD", "ACTIVE"),
            new StatutoryType("BEREAVEMENT", "Nghỉ tang (cha mẹ, vợ/chồng, con)", "Bereavement (parent, spouse, child)",
                    "PERSONAL_PAID", "EMPLOYER", "DAY", "WORKING_DAY", false, false, false, true, "R",
                    "PAID_LEAVE_DAYS", Map.of(), "BEREAVEMENT", "ACTIVE"),
            new StatutoryType("PERSONAL_UNPAID", "Nghỉ việc riêng không lương", "Unpaid family leave",
                    "PERSONAL_UNPAID", "UNPAID", "DAY", "WORKING_DAY", false, false, false, true, "KL", "UNPAID_DAYS",
                    Map.of(), "UNPAID_FAMILY", "ACTIVE"),
            new StatutoryType("SICK", "Nghỉ ốm (BHXH)", "Sick leave (SI)", "SICK", "SI_FUND", "HALF_DAY",
                    "WORKING_DAY", false, true, true, true, "Ô", "SI_LEAVE_DAYS", Map.of(), null, "ACTIVE"),
            new StatutoryType("CHILD_SICK", "Nghỉ chăm con ốm (BHXH)", "Child sick leave (SI)", "CHILD_SICK", "SI_FUND",
                    "HALF_DAY", "WORKING_DAY", false, true, true, false, "CO", "SI_LEAVE_DAYS", Map.of(), null,
                    "ACTIVE"),
            new StatutoryType("MATERNITY", "Nghỉ thai sản (BHXH)", "Maternity leave (SI)", "MATERNITY", "SI_FUND",
                    "DAY", "CALENDAR_DAY", false, true, true, true, "TS", "SI_LEAVE_DAYS", Map.of("gender", "F"), null,
                    "ACTIVE"),
            new StatutoryType("PATERNITY", "Nghỉ khi vợ sinh con (BHXH)", "Paternity leave (SI)", "PATERNITY",
                    "SI_FUND", "DAY", "WORKING_DAY", false, true, true, true, "TS", "SI_LEAVE_DAYS",
                    Map.of("gender", "M"), null, "ACTIVE"),
            new StatutoryType("PRENATAL", "Nghỉ khám thai (BHXH)", "Prenatal check-up (SI)", "PRENATAL", "SI_FUND",
                    "HALF_DAY", "WORKING_DAY", false, true, true, true, "TS", "SI_LEAVE_DAYS", Map.of("gender", "F"),
                    null, "ACTIVE"),
            new StatutoryType("MISCARRIAGE", "Nghỉ sẩy thai, phá thai (BHXH)", "Miscarriage / abortion (SI)",
                    "MISCARRIAGE", "SI_FUND", "DAY", "CALENDAR_DAY", false, true, true, true, "TS", "SI_LEAVE_DAYS",
                    Map.of("gender", "F"), null, "ACTIVE"),
            new StatutoryType("WORK_ACCIDENT", "Nghỉ điều trị TNLĐ-BNN", "Work accident / occupational disease",
                    "WORK_ACCIDENT", "EMPLOYER", "DAY", "WORKING_DAY", false, false, true, true, "TN",
                    "PAID_LEAVE_DAYS", Map.of(), null, "ACTIVE"),
            new StatutoryType("UNPAID_AGREED", "Nghỉ không lương theo thỏa thuận", "Unpaid leave by agreement",
                    "UNPAID_AGREED", "UNPAID", "HALF_DAY", "WORKING_DAY", false, false, false, true, "KL",
                    "UNPAID_DAYS", Map.of(), null, "ACTIVE"),
            // TOIL needs OT paid as time off (comp_choice TOIL), which is P2: seeded inactive
            new StatutoryType("TOIL", "Nghỉ bù làm thêm giờ", "Time off in lieu", "TOIL", "EMPLOYER", "HALF_DAY",
                    "WORKING_DAY", true, false, false, true, "BU", "PAID_LEAVE_DAYS", Map.of(), null, "INACTIVE"));
}
