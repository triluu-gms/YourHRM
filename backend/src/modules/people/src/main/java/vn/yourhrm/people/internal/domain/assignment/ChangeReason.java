package vn.yourhrm.people.internal.domain.assignment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A job-change reason (BR-PPL-032, US-PPL-035): what the change is, which approval (Core process type) it needs and
 * which legal document (decision and / or contract appendix) issues it.
 *
 * @param actionCode       stored in {@code hr.assignment.action_code} (TRANSFER, PROMOTION ...)
 * @param assignmentType   PRIMARY (a new version of the primary line) or TEMP_TRANSFER (a separate, dated line)
 * @param processTypeCode  Core workflow process type; null = applied directly by HR without approval
 * @param decisionTypeCode decision type issued on approval (DecisionService.TYPES); null when no decision
 * @param documentKind     DECISION, DECISION_AND_APPENDIX (an appendix when contract terms change), APPENDIX or NONE
 * @param phase2           reasons of Phase 2 features (secondment, concurrent position, reorg): never active in P1
 */
public record ChangeReason(String code, String nameVi, String nameEn, String actionCode, String assignmentType,
        String processTypeCode, String decisionTypeCode, String documentKind, boolean active, boolean phase2) {

    public static final String JOB_CHANGE = "hr.JOB_CHANGE";
    public static final String TEMPORARY_TRANSFER = "hr.TEMPORARY_TRANSFER";
    public static final Set<String> PROCESS_TYPES = Set.of(JOB_CHANGE, TEMPORARY_TRANSFER);
    public static final Set<String> DOCUMENT_KINDS = Set.of("DECISION", "DECISION_AND_APPENDIX", "APPENDIX", "NONE");
    public static final Set<String> ASSIGNMENT_TYPES = Set.of("PRIMARY", "TEMP_TRANSFER");

    public ChangeReason {
        Objects.requireNonNull(code, "code");
    }

    public boolean usable() {
        return active && !phase2;
    }

    public boolean needsDecision() {
        return documentKind.startsWith("DECISION");
    }

    public boolean temporaryTransfer() {
        return "TEMP_TRANSFER".equals(assignmentType);
    }

    /** Vendor defaults (seed data of US-PPL-035); a tenant disables or adds reasons through a setting. */
    public static final List<ChangeReason> DEFAULTS = List.of(
            new ChangeReason("TRANSFER", "Điều chuyển", "Transfer", "TRANSFER", "PRIMARY", JOB_CHANGE, "TRANSFER",
                    "DECISION_AND_APPENDIX", true, false),
            new ChangeReason("PROMOTION", "Thăng chức", "Promotion", "PROMOTION", "PRIMARY", JOB_CHANGE, "PROMOTION",
                    "DECISION_AND_APPENDIX", true, false),
            new ChangeReason("DEMOTION", "Giáng chức", "Demotion", "DEMOTION", "PRIMARY", JOB_CHANGE, "DEMOTION",
                    "DECISION_AND_APPENDIX", true, false),
            new ChangeReason("TEMPORARY_TRANSFER", "Điều chuyển tạm thời (Điều 29)", "Temporary transfer (Art. 29)",
                    "TEMPORARY_TRANSFER", "TEMP_TRANSFER", TEMPORARY_TRANSFER, "TEMPORARY_TRANSFER", "DECISION", true,
                    false),
            new ChangeReason("JOB_TITLE_CHANGE", "Thay đổi chức danh", "Job title change", "JOB_CHANGE", "PRIMARY",
                    JOB_CHANGE, "JOB_CHANGE", "DECISION_AND_APPENDIX", true, false),
            new ChangeReason("MANAGER_CHANGE", "Thay đổi quản lý trực tiếp", "Line manager change", "MANAGER_CHANGE",
                    "PRIMARY", null, null, "NONE", true, false),
            new ChangeReason("LOCATION_CHANGE", "Thay đổi nơi làm việc", "Work location change", "LOCATION_CHANGE",
                    "PRIMARY", JOB_CHANGE, "TRANSFER", "DECISION_AND_APPENDIX", true, false),
            new ChangeReason("COST_CENTRE_CHANGE", "Thay đổi trung tâm chi phí", "Cost centre change",
                    "COST_CENTRE_CHANGE", "PRIMARY", null, null, "NONE", true, false),
            new ChangeReason("SECONDMENT", "Biệt phái", "Secondment", "SECONDMENT", "PRIMARY", null, null, "DECISION",
                    false, true),
            new ChangeReason("CONCURRENT_POSITION", "Kiêm nhiệm", "Concurrent position", "CONCURRENT", "PRIMARY", null,
                    null, "DECISION", false, true),
            new ChangeReason("REORG", "Tái cơ cấu", "Reorganisation", "REORG", "PRIMARY", null, null, "DECISION", false,
                    true));

    /**
     * Applies one tenant override entry ({@code code} plus any of the record's fields) to this reason; Phase 2 reasons
     * stay inactive whatever the override says.
     */
    public ChangeReason with(Map<String, Object> o) {
        return new ChangeReason(code, str(o, "nameVi", nameVi), str(o, "nameEn", nameEn),
                str(o, "actionCode", actionCode), str(o, "assignmentType", assignmentType),
                o.containsKey("processTypeCode") ? (String) o.get("processTypeCode") : processTypeCode,
                o.containsKey("decisionTypeCode") ? (String) o.get("decisionTypeCode") : decisionTypeCode,
                str(o, "documentKind", documentKind),
                !phase2 && (o.containsKey("active") ? Boolean.TRUE.equals(o.get("active")) : active), phase2);
    }

    /** A tenant-defined reason (not in the defaults). */
    public static ChangeReason custom(Map<String, Object> o) {
        String code = (String) o.get("code");
        return new ChangeReason(code, str(o, "nameVi", code), str(o, "nameEn", code), str(o, "actionCode", code),
                str(o, "assignmentType", "PRIMARY"), (String) o.get("processTypeCode"),
                (String) o.get("decisionTypeCode"), str(o, "documentKind", "DECISION"),
                !o.containsKey("active") || Boolean.TRUE.equals(o.get("active")), false);
    }

    /** Problems of a tenant-defined configuration (an invalid reason is unusable). */
    public List<String> problems(Set<String> decisionTypes) {
        var out = new java.util.ArrayList<String>();
        if (!ASSIGNMENT_TYPES.contains(assignmentType)) {
            out.add("assignmentType must be PRIMARY or TEMP_TRANSFER");
        }
        if (processTypeCode != null && !PROCESS_TYPES.contains(processTypeCode)) {
            out.add("processTypeCode must be one of " + PROCESS_TYPES + " or null");
        }
        if (!DOCUMENT_KINDS.contains(documentKind)) {
            out.add("documentKind must be one of " + DOCUMENT_KINDS);
        }
        if (needsDecision() && (decisionTypeCode == null || !decisionTypes.contains(decisionTypeCode))) {
            out.add("decisionTypeCode must be a P1 decision type");
        }
        if (temporaryTransfer() && processTypeCode == null) {
            out.add("a temporary transfer needs an approval process");
        }
        return out;
    }

    private static String str(Map<String, Object> o, String key, String fallback) {
        Object v = o.get(key);
        return v == null ? fallback : v.toString();
    }
}
