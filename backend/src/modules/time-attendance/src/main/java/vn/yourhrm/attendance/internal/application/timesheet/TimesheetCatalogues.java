package vn.yourhrm.attendance.internal.application.timesheet;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * What daily processing registers with Core: the T&amp;A policy settings per legal entity, effective-dated (US-ATT-022,
 * option B: one policy set per legal entity, BR-ATT-083 is P2), the approval process of correction requests
 * (BR-ATT-036) and the platform default attendance codes (BR-ATT-033), which the release job writes as rows of the
 * sentinel tenant ({@link #defaultCodesUpsertSql()}, run with the platform role: the T&amp;A role cannot write them).
 */
@Component
public class TimesheetCatalogues implements SettingCatalogue, ProcessTypeCatalogue {

    /** BR-ATT-031: FIRST_LAST (default), ALL_PAIRS or BY_DIRECTION. */
    public static final String SETTING_PAIRING_MODE = "att.pairing_mode";
    /** BR-ATT-036: maximum ESS correction requests per employee and calendar month; 0 = no limit. */
    public static final String SETTING_CORRECTION_LIMIT = "att.correction_monthly_limit";

    public static final String PROCESS_CORRECTION = "att.ATT_CORRECTION";
    public static final String SUBJECT_CORRECTION = "att.timesheet_correction";

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<SettingDefinition> settings() {
        return List.of(
                new SettingDefinition(SETTING_PAIRING_MODE, "ENUM", DayModel.PAIRING_MODES.stream().sorted().toList(),
                        "FIRST_LAST", Set.of("TENANT", "LEGAL_ENTITY"), true, false,
                        "Cách ghép cặp lượt chấm: vào đầu/ra cuối, mọi cặp, theo hướng vào/ra",
                        "Punch pairing: first in / last out, all pairs, by direction"),
                new SettingDefinition(SETTING_CORRECTION_LIMIT, "INT", List.of(), 0,
                        Set.of("TENANT", "LEGAL_ENTITY"), true, false,
                        "Số yêu cầu điều chỉnh chấm công tối đa mỗi tháng của một nhân viên (0 = không giới hạn)",
                        "Maximum attendance correction requests per employee and month (0 = no limit)"));
    }

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        return List.of(new ProcessTypeDefinition(PROCESS_CORRECTION, "Điều chỉnh chấm công",
                "Attendance correction",
                Map.of("type", "object", "properties", Map.of(
                        "workDate", Map.of("type", "string"),
                        "correctionType", Map.of("type", "string"),
                        "requestedIn", Map.of("type", "string"),
                        "requestedOut", Map.of("type", "string"),
                        "requestedCode", Map.of("type", "string"),
                        "reason", Map.of("type", "string"))),
                List.of("correctionType"), Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false, false,
                // default: the T&A admin role in scope; tenants switch to the line manager in Core's workflow designer
                List.of(new StepTemplate("ta", "T&A duyệt", "T&A approval",
                        ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "TA_ADMIN"), true))));
    }

    // ------------------------------------------------------------------------------------------- default codes

    /**
     * A platform default attendance code. Categories follow the Attendance Result contract (BRD §7.3) and must match
     * Payroll's shared code list (risk R4).
     */
    public record DefaultCode(String code, String labelVi, String labelEn, String kind, String category,
            String subCode, boolean countsAsWorked, String paidBy, boolean siNonwork, int order) {
    }

    public static final List<DefaultCode> DEFAULT_CODES = List.of(
            new DefaultCode("X", "Đi làm", "Worked", "WORK", "WORKED_DAYS", null, true, "EMPLOYER", false, 10),
            // trips (P1: attendance codes, BR-ATT-070 is P2): the sub-code is the TRIP_DAYS destination (§7.3 item 14)
            new DefaultCode("CT", "Công tác trong nước", "Domestic business trip", "TRIP", "WORKED_DAYS", "DOMESTIC",
                    true, "EMPLOYER", false, 20),
            new DefaultCode("CTNN", "Công tác nước ngoài", "Overseas business trip", "TRIP", "WORKED_DAYS",
                    "OVERSEAS", true, "EMPLOYER", false, 25),
            new DefaultCode("WFH", "Làm việc tại nhà", "Work from home", "WFH", "WORKED_DAYS", "WFH", true, "EMPLOYER",
                    false, 30),
            new DefaultCode("P", "Nghỉ phép năm", "Annual leave", "LEAVE", "PAID_LEAVE_DAYS", "ANNUAL", false,
                    "EMPLOYER", false, 40),
            new DefaultCode("R", "Nghỉ việc riêng có lương", "Paid personal leave", "LEAVE", "PAID_LEAVE_DAYS",
                    "PERSONAL", false, "EMPLOYER", false, 50),
            new DefaultCode("L", "Nghỉ lễ, Tết", "Public holiday", "HOLIDAY", "HOLIDAY_DAYS", null, false, "EMPLOYER",
                    false, 60),
            new DefaultCode("NB", "Nghỉ bù ngày lễ", "Substitute holiday", "HOLIDAY", "HOLIDAY_DAYS", "SUBSTITUTE",
                    false, "EMPLOYER", false, 70),
            new DefaultCode("Ô", "Nghỉ ốm", "Sick leave", "LEAVE", "SI_LEAVE_DAYS", "SICK", false, "SI_FUND", true, 80),
            new DefaultCode("CO", "Nghỉ con ốm", "Child sick leave", "LEAVE", "SI_LEAVE_DAYS", "CHILD_SICK", false,
                    "SI_FUND", true, 90),
            new DefaultCode("TS", "Nghỉ thai sản", "Maternity leave", "LEAVE", "SI_LEAVE_DAYS", "MATERNITY", false,
                    "SI_FUND", false, 100),
            new DefaultCode("TN", "Nghỉ điều trị tai nạn lao động", "Work accident treatment", "LEAVE",
                    "PAID_LEAVE_DAYS", "WORK_ACCIDENT", false, "EMPLOYER", false, 105),
            new DefaultCode("BU", "Nghỉ bù làm thêm giờ", "Time off in lieu", "LEAVE", "PAID_LEAVE_DAYS", "TOIL",
                    false, "EMPLOYER", false, 106),
            new DefaultCode("KL", "Nghỉ không lương", "Unpaid leave", "LEAVE", "UNPAID_DAYS", "PERSONAL", false,
                    "UNPAID", true, 110),
            new DefaultCode("V", "Vắng không phép", "Absent without leave", "ABSENCE", "UNPAID_DAYS", "ABSENT", false,
                    "UNPAID", true, 120),
            new DefaultCode("TH", "Tạm hoãn hợp đồng", "Contract suspended", "SUSPENDED", "UNPAID_DAYS", "SUSPENSION",
                    false, "UNPAID", true, 130),
            new DefaultCode("NE", "Ngoài thời gian làm việc", "Not employed", "NOT_EMPLOYED", "NOT_EMPLOYED_DAYS",
                    null, false, "NONE", false, 140));

    /** Idempotent upsert of {@link #DEFAULT_CODES} into the sentinel tenant (run by the platform release job). */
    public static String defaultCodesUpsertSql() {
        String values = DEFAULT_CODES.stream().map(c -> "(sys.global_tenant_id(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %d)"
                .formatted(lit(c.code()), lit(c.labelVi()), lit(c.labelEn()), lit(c.kind()), lit(c.category()),
                        lit(c.subCode()), c.countsAsWorked(), lit(c.paidBy()), c.siNonwork(), c.order()))
                .collect(Collectors.joining(",\n"));
        return """
                insert into att.attendance_code (tenant_id, code, label_vi, label_en, code_kind, payroll_category,
                       payroll_sub_code, counts_as_worked, paid_by, counts_si_nonwork, display_order)
                values %s
                on conflict (tenant_id, code) do update set label_vi = excluded.label_vi, label_en = excluded.label_en,
                       code_kind = excluded.code_kind, payroll_category = excluded.payroll_category,
                       payroll_sub_code = excluded.payroll_sub_code, counts_as_worked = excluded.counts_as_worked,
                       paid_by = excluded.paid_by, counts_si_nonwork = excluded.counts_si_nonwork,
                       display_order = excluded.display_order""".formatted(values);
    }

    private static String lit(String s) {
        return s == null ? "null" : "'" + s.replace("'", "''") + "'";
    }
}
