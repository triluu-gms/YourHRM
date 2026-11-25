package vn.yourhrm.attendance.internal.application.timesheet;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CodeRow;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * The attendance code catalogue (BR-ATT-033, US-ATT-027): the platform defaults (X, P, L, NB, Ô, CO, TS, KL, V, CT,
 * WFH, TH, NE ...) and the tenant's own codes, which may shadow a default with the same code (other label, colour or
 * payroll mapping). Platform rows are never changed here (invariant 11). The codes daily processing assigns itself (X,
 * V, L, NB, NE, TH) keep their meaning; a tenant may relabel them.
 */
@Service
public class AttendanceCodeService {

    static final Set<String> KINDS = Set.of("WORK", "LEAVE", "HOLIDAY", "TRIP", "WFH", "ABSENCE", "REST",
            "NOT_EMPLOYED", "SUSPENDED");
    static final Set<String> PAID_BY = Set.of("EMPLOYER", "SI_FUND", "UNPAID", "NONE");
    private static final Pattern CODE = Pattern.compile("^[\\p{Lu}0-9_]{1,10}$");
    private static final Pattern CATEGORY = Pattern.compile("^[A-Z][A-Z_]*$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    public record CodeInput(String code, String labelVi, String labelEn, String kind, String payrollCategory,
            String payrollSubCode, Boolean countsAsWorked, String paidBy, Boolean siNonwork, Integer displayOrder,
            String colorHex, String status) {
    }

    private final TimesheetStore store;

    public AttendanceCodeService(TimesheetStore store) {
        this.store = store;
    }

    @AttTransactional(readOnly = true)
    public List<CodeRow> list() {
        return List.copyOf(store.effectiveCodes().values());
    }

    @AttTransactional
    public CodeRow create(CodeInput in) {
        CodeRow c = validate(in, null);
        return store.tenantCode(store.insertCode(c)).orElseThrow();
    }

    @AttTransactional
    public CodeRow update(UUID id, int version, CodeInput in) {
        CodeRow current = store.tenantCode(id).orElseThrow(() -> ApiException.notFound("Attendance code", id));
        if (current.global()) {
            throw ApiException.conflict("platform-code", "A platform code cannot be changed; create a tenant code");
        }
        CodeRow c = validate(in, current);
        if (!store.updateCode(id, version, c)) {
            throw ApiException.conflict("stale-version", "The code was changed by someone else; reload it");
        }
        return store.tenantCode(id).orElseThrow();
    }

    private static CodeRow validate(CodeInput in, CodeRow current) {
        String code = current != null ? current.code() : TimesheetInputs.upper(in.code());
        if (code == null || !CODE.matcher(code).matches()) {
            throw invalid("invalid-code", "The code is 1-10 upper-case letters, digits or '_'");
        }
        if (in.labelVi() == null || in.labelVi().isBlank()) {
            throw invalid("label-required", "The Vietnamese label is required");
        }
        String kind = TimesheetInputs.upper(in.kind());
        if (kind == null || !KINDS.contains(kind)) {
            throw invalid("invalid-kind", "The kind is one of " + KINDS);
        }
        String cat = TimesheetInputs.upper(in.payrollCategory());
        if (cat == null || !CATEGORY.matcher(cat).matches()) {
            throw invalid("invalid-payroll-category", "The payroll category is an upper-case code such as"
                    + " WORKED_DAYS or PAID_LEAVE_DAYS (BRD §7.3)");
        }
        String paid = in.paidBy() == null ? "EMPLOYER" : TimesheetInputs.upper(in.paidBy());
        if (!PAID_BY.contains(paid)) {
            throw invalid("invalid-paid-by", "Paid by is one of " + PAID_BY);
        }
        if (in.colorHex() != null && !COLOR.matcher(in.colorHex()).matches()) {
            throw invalid("invalid-color", "The colour is #RRGGBB");
        }
        String status = in.status() == null ? "ACTIVE" : TimesheetInputs.upper(in.status());
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
            throw invalid("invalid-status", "The status is ACTIVE or INACTIVE");
        }
        return new CodeRow(null, false, code, in.labelVi().strip(), TimesheetInputs.text(in.labelEn()), kind, cat,
                TimesheetInputs.upper(in.payrollSubCode()), Boolean.TRUE.equals(in.countsAsWorked()), paid,
                Boolean.TRUE.equals(in.siNonwork()), in.displayOrder() == null ? 100 : in.displayOrder(),
                in.colorHex(), status, 0);
    }

    private static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }
}
