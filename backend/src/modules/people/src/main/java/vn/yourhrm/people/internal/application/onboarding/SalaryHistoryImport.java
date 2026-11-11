package vn.yourhrm.people.internal.application.onboarding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.application.salary.SalaryRules;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryTerms;
import vn.yourhrm.people.internal.infra.salary.SalaryRow;
import vn.yourhrm.people.internal.infra.salary.SalaryStore;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * {@code OB_EMP_SALARY_HISTORY} (US-PPL-089/092; BR-PPL-074 inputs): earlier salary versions of the current employment,
 * needed by Payroll for severance / job-loss averages, loaded as CLOSED effective-dated {@code hr.salary_record} rows
 * ({@code source = OPENING_LOAD}, action ADJUSTMENT, reason OPENING_HISTORY) ending before the current salary. No
 * events (history, ADR-0037), no minimum-wage check (historic data: structure only). Idempotent by employee + start.
 */
@Component
class SalaryHistoryImport extends OpeningBalanceImport {

    static final String REASON = "OPENING_HISTORY";

    private final SalaryRules rules;
    private final SalaryStore store;

    SalaryHistoryImport(OnboardingSupport support, SalaryRules rules, SalaryStore store) {
        super(support);
        this.rules = rules;
        this.store = store;
    }

    @Override
    public Definition definition() {
        List<Column> columns = new ArrayList<>(List.of(employeeNo(),
                date("valid_from", "Từ ngày", "From", true),
                date("valid_to", "Đến ngày (bao gồm)", "To (inclusive)", true)));
        columns.addAll(SalaryImport.amountColumns(false));
        return definition(OnboardingKeys.SALARY_HISTORY, "Số dư đầu kỳ - Lịch sử lương", "Opening balance - salary history",
                columns, List.of("employee_no", "valid_from"), List.of(OnboardingKeys.SALARY), List.of());
    }

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        OnboardingSupport.Emp emp = s.requireEmployment(row.text("employee_no"));
        s.requireUnlocked(emp.legalEntityId());
        LocalDate from = row.date("valid_from");
        LocalDate to = row.date("valid_to").plusDays(1);
        if (!to.isAfter(from)) {
            throw ApiException.unprocessable("invalid-period", "The end date is before the start date");
        }
        if (from.isBefore(emp.hireDate())) {
            throw ApiException.unprocessable("history-before-hire", "Salary history starts before the hire date "
                    + emp.hireDate() + " (earlier employments go to the service history sheet)");
        }
        var j = s.db().jdbc();
        UUID t = OnboardingSupport.tenant();
        Optional<UUID> same = j.sql("select s.id from hr.salary_record s where s.tenant_id = :t and s.employment_id = :e "
                        + "and s.valid_from = :f and s.superseded_at is null and " + SalaryStore.NOT_PROPOSAL)
                .param("t", t).param("e", emp.id()).param("f", from).query(UUID.class).optional();
        if (same.isPresent()) {
            return Written.updated("hr.salary_record", same.get(), List.of());
        }
        LocalDate current = j.sql("select min(s.valid_from) from hr.salary_record s where s.tenant_id = :t "
                        + "and s.employment_id = :e and s.superseded_at is null and s.reason_code is distinct from '"
                        + REASON + "' and " + SalaryStore.NOT_PROPOSAL)
                .param("t", t).param("e", emp.id()).query(LocalDate.class).optional().orElse(null);
        if (current == null) {
            throw ApiException.unprocessable("current-salary-missing",
                    "Load the current salary (" + OnboardingKeys.SALARY + ") before its history");
        }
        if (to.isAfter(current)) {
            throw ApiException.unprocessable("history-overlaps-current",
                    "A history version must end before the current salary starts (" + current + ")");
        }
        SalaryTerms tm = rules.structure(SalaryImport.terms(row), from);
        SalaryRow r = new SalaryRow(Ids.newId(), emp.id(), tm.payBasis(), tm.salaryKind(), tm.baseSalaryAmount(),
                tm.agreedNetAmount(), tm.netGuaranteedScope(), tm.siSalaryAmount(), tm.currencyCode(), tm.payScaleCode(),
                tm.payScaleStepCode(), null, "ADJUSTMENT", REASON, null, null, from, to, OnboardingKeys.SOURCE, null,
                null, null, null, tm.allowances());
        store.insert(r);
        return Written.created("hr.salary_record", r.id(), List.of());
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        return List.of("Salary records are bi-temporal history and cannot be deleted; correct them with a salary "
                + "correction");
    }
}
