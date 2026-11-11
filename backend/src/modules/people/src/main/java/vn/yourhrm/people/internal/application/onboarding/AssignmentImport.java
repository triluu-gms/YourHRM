package vn.yourhrm.people.internal.application.onboarding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.OnboardingReferenceLookup;
import vn.yourhrm.people.internal.application.assignment.AssignmentService;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * {@code OB_EMP_ASSIGNMENT} (US-PPL-089/090/091; BR-PPL-030..): the current PRIMARY assignment of each employment as a
 * bi-temporal version from its start date (DESIGN §13.3), created through the assignment stream's
 * {@code createInitial} with {@code source = OPENING_LOAD} (no decision; {@code AssignmentChanged} HIRE with
 * {@code initialLoad}). Org unit and location are Core lookups (code or name); job title, position, grade and cost
 * centre are resolved through {@link OnboardingReferenceLookup} (record id accepted while Core offers no code lookup);
 * the line manager is given by employee number. Idempotent: an employment that already has a primary assignment is
 * left as it is (later changes are job changes).
 */
@Component
class AssignmentImport extends OpeningBalanceImport {

    private final AssignmentService assignments;
    private final ObjectProvider<OnboardingReferenceLookup> references;

    AssignmentImport(OnboardingSupport support, AssignmentService assignments,
            ObjectProvider<OnboardingReferenceLookup> references) {
        super(support);
        this.assignments = assignments;
        this.references = references;
    }

    @Override
    public Definition definition() {
        String hintVi = "Mã (hoặc ID bản ghi)";
        String hintEn = "Code (or record id)";
        return definition(OnboardingKeys.ASSIGNMENT, "Số dư đầu kỳ - Phân công hiện tại",
                "Opening balance - current assignment",
                List.of(employeeNo(),
                        date("valid_from", "Hiệu lực từ", "Valid from", true),
                        lookup("org_unit", "Đơn vị", "Org unit", "ORG_UNIT", true),
                        lookup("location", "Địa điểm làm việc", "Work location", "LOCATION", false),
                        text("job_title", "Chức danh", "Job title", false, hintVi, hintEn),
                        text("position", "Vị trí", "Position", false, hintVi, hintEn),
                        text("grade", "Ngạch / bậc", "Grade", false, hintVi, hintEn),
                        text("cost_centre", "Trung tâm chi phí", "Cost centre", false, hintVi, hintEn),
                        text("manager_employee_no", "Mã nhân viên của quản lý trực tiếp", "Line manager employee no.",
                                false),
                        decimal("fte", "Tỷ lệ thời gian (FTE)", "FTE", false)),
                List.of("employee_no"), List.of(OnboardingKeys.MASTER), List.of());
    }

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        OnboardingSupport.Emp emp = s.requireEmployment(row.text("employee_no"));
        s.requireUnlocked(emp.legalEntityId());
        Optional<UUID> existing = s.db().jdbc().sql("""
                        select id from hr.assignment where tenant_id = :t and employment_id = :e
                           and assignment_type = 'PRIMARY' and superseded_at is null order by valid_from desc limit 1""")
                .param("t", OnboardingSupport.tenant()).param("e", emp.id()).query(UUID.class).optional();
        if (existing.isPresent()) {
            return Written.updated("hr.assignment", existing.get(), List.of(OnboardingSupport.warn("employee_no",
                    "assignment-exists", "Nhân viên đã có phân công: giữ nguyên (thay đổi qua điều chuyển)",
                    "The employee already has an assignment: kept (changes go through a job change)")));
        }
        LocalDate from = row.date("valid_from");
        if (from.isBefore(emp.hireDate())) {
            throw ApiException.unprocessable("assignment-before-hire",
                    "The assignment starts before the hire date " + emp.hireDate());
        }
        UUID manager = null;
        if (row.text("manager_employee_no") != null) {
            manager = s.employment(row.text("manager_employee_no")).map(OnboardingSupport.Emp::id)
                    .orElseThrow(() -> ApiException.unprocessable("manager-not-found", "Line manager "
                            + row.text("manager_employee_no") + " is not loaded (load managers in the master sheet)"));
        }
        var a = new AssignmentService.Attributes(row.id("org_unit"), ref(row, "position", "POSITION", emp, from),
                ref(row, "job_title", "JOB_TITLE", emp, from), ref(row, "grade", "GRADE", emp, from), manager,
                row.id("location"), ref(row, "cost_centre", "COST_CENTRE", emp, from), null, row.decimal("fte"));
        var view = assignments.createInitial(emp.id(), a, from, null, OnboardingKeys.SOURCE);
        return Written.created("hr.assignment", view.id(), new ArrayList<>());
    }

    private UUID ref(ImportRow row, String column, String kind, OnboardingSupport.Emp emp, LocalDate on) {
        String v = row.text(column);
        if (v == null) {
            return null;
        }
        Optional<UUID> resolved = references.getIfAvailable(() -> OnboardingReferenceLookup.NONE)
                .resolve(kind, v.strip(), emp.legalEntityId(), on);
        if (resolved.isPresent()) {
            return resolved.get();
        }
        try {
            return UUID.fromString(v.strip());
        } catch (IllegalArgumentException ex) {
            throw ApiException.unprocessable("reference-not-found", kind + " '" + v + "' not found (Core offers no "
                    + "code lookup for it yet: enter the record id)");
        }
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        return List.of("Assignments are bi-temporal history and cannot be deleted; correct them with an assignment "
                + "correction");
    }
}
