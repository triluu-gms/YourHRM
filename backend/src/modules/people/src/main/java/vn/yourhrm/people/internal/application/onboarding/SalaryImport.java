package vn.yourhrm.people.internal.application.onboarding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts;
import vn.yourhrm.people.internal.application.employment.InsuranceParticipationService;
import vn.yourhrm.people.internal.application.salary.SalaryRules;
import vn.yourhrm.people.internal.application.salary.SalaryService;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryTerms;
import vn.yourhrm.people.internal.infra.salary.SalaryStore;

/**
 * {@code OB_EMP_SALARY} (US-PPL-089/090/091; BR-PPL-040/043): the current agreed salary (GROSS or NET, SI salary, up to
 * three contractual allowances). Validation applies the salary stream's structure rules and its business rules on the
 * go-live date — regional minimum wage (blocking), probation floor, pay-band warnings — although the stream itself only
 * checks the structure of imported records. Commit: the salary stream's import path {@code createInitial} with
 * {@code source = OPENING_LOAD} (the only salary writer without a decision / appendix; {@code SalaryChanged} HIRE with
 * {@code initialLoad}). Insurance participation is re-derived afterwards. Idempotent: an employment with a salary
 * record keeps it (changes are salary changes).
 */
@Component
class SalaryImport extends OpeningBalanceImport {

    private final SalaryService salaries;
    private final SalaryRules rules;
    private final SalaryStore store;
    private final EmploymentFacts facts;
    private final InsuranceParticipationService insurance;

    SalaryImport(OnboardingSupport support, SalaryService salaries, SalaryRules rules, SalaryStore store,
            EmploymentFacts facts, InsuranceParticipationService insurance) {
        super(support);
        this.salaries = salaries;
        this.rules = rules;
        this.store = store;
        this.facts = facts;
        this.insurance = insurance;
    }

    static List<Column> amountColumns(boolean current) {
        List<Column> c = new ArrayList<>(List.of(
                text("pay_basis", "Hình thức lương", "Pay basis", true, "GROSS hoặc NET", "GROSS or NET"),
                text("salary_kind", "Loại lương", "Salary kind", true, "MONTHLY, DAILY, HOURLY, PIECE",
                        "MONTHLY, DAILY, HOURLY, PIECE"),
                sensitive("base_salary", "Lương cơ bản", "Base salary", ColumnType.DECIMAL, true, null, null),
                sensitive("agreed_net", "Lương NET thỏa thuận", "Agreed net salary", ColumnType.DECIMAL, false,
                        "Bắt buộc khi NET", "Required for NET"),
                text("net_scope", "Phạm vi bảo đảm NET", "NET guaranteed scope", false,
                        "Danh sách mã, cách nhau bởi dấu phẩy", "Comma-separated codes"),
                sensitive("si_salary", "Lương đóng BHXH", "SI salary", ColumnType.DECIMAL, false, null, null),
                text("currency", "Tiền tệ", "Currency", false, "Mặc định VND", "Default VND"),
                text("pay_scale_code", "Thang lương", "Pay scale", false),
                text("pay_scale_step", "Bậc lương", "Pay scale step", false)));
        if (current) {
            c.add(decimal("probation_pct", "Tỷ lệ lương thử việc (%)", "Probation pay %", false));
        }
        for (int i = 1; i <= 3; i++) {
            c.add(text("allowance_" + i + "_code", "Mã phụ cấp " + i, "Allowance " + i + " code", false));
            c.add(sensitive("allowance_" + i + "_amount", "Số tiền phụ cấp " + i, "Allowance " + i + " amount",
                    ColumnType.DECIMAL, false, null, null));
            c.add(bool("allowance_" + i + "_in_si", "Phụ cấp " + i + " tính đóng BHXH", "Allowance " + i + " in SI salary",
                    null, null));
        }
        return c;
    }

    static SalaryTerms terms(ImportRow row) {
        List<Allowance> allowances = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String code = row.text("allowance_" + i + "_code");
            if (code != null) {
                BigDecimal amount = row.decimal("allowance_" + i + "_amount");
                allowances.add(new Allowance(code.strip(), amount == null ? BigDecimal.ZERO : amount,
                        Boolean.TRUE.equals(flag(row, "allowance_" + i + "_in_si"))));
            }
        }
        List<String> scope = row.text("net_scope") == null ? null
                : Arrays.stream(row.text("net_scope").split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
        return new SalaryTerms(upper(row, "pay_basis"), upper(row, "salary_kind"), row.decimal("base_salary"),
                row.decimal("agreed_net"), scope, row.decimal("si_salary"),
                row.text("currency") == null ? null : upper(row, "currency"), row.text("pay_scale_code"),
                row.text("pay_scale_step"), row.values().containsKey("probation_pct") ? row.decimal("probation_pct")
                        : null, allowances);
    }

    @Override
    public Definition definition() {
        List<Column> columns = new ArrayList<>(List.of(employeeNo(),
                date("valid_from", "Hiệu lực từ", "Valid from", true)));
        columns.addAll(amountColumns(true));
        return definition(OnboardingKeys.SALARY, "Số dư đầu kỳ - Lương hiện tại", "Opening balance - current salary",
                columns, List.of("employee_no"), List.of(OnboardingKeys.CONTRACT),
                List.of(new ControlCheck(OnboardingKeys.CHECK_SALARY_TOTAL, "Tổng lương cơ bản", "Total base salary")));
    }

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        OnboardingSupport.Emp emp = s.requireEmployment(row.text("employee_no"));
        s.requireUnlocked(emp.legalEntityId());
        Optional<UUID> existing = s.db().jdbc().sql("select s.id from hr.salary_record s where s.tenant_id = :t "
                        + "and s.employment_id = :e and s.superseded_at is null and s.valid_to is null and "
                        + SalaryStore.NOT_PROPOSAL + " limit 1")
                .param("t", OnboardingSupport.tenant()).param("e", emp.id()).query(UUID.class).optional();
        if (existing.isPresent()) {
            return Written.updated("hr.salary_record", existing.get(), List.of(OnboardingSupport.warn("employee_no",
                    "salary-record-exists", "Nhân viên đã có hồ sơ lương: giữ nguyên (thay đổi qua điều chỉnh lương)",
                    "The employee already has a salary record: kept (changes go through a salary change)")));
        }
        LocalDate from = row.date("valid_from");
        LocalDate checkOn = from.isAfter(s.asOf(ctx)) ? from : s.asOf(ctx);
        SalaryTerms t = rules.structure(terms(row), from);
        List<RowMessage> warnings = new ArrayList<>();
        rules.business(facts.require(emp.id()), t, checkOn).forEach(w -> warnings.add(
                OnboardingSupport.warn("base_salary", w.code(), w.message(), w.message())));
        var created = salaries.createInitial(emp.id(), t, from, null, null, OnboardingKeys.SOURCE);
        if (!dryRun) {
            s.tryRun("Insurance participation after opening salary " + emp.employeeNo(),
                    () -> insurance.derive(emp.personId(), emp.hireDate(), "OPENING_BALANCE"), 0);
        }
        return Written.created("hr.salary_record", created.salaryRecordId(), warnings);
    }

    @Override
    public List<ReconciliationValue> stagedTotals(ImportContext ctx, List<ImportRow> rows) {
        return List.of(new ReconciliationValue(OnboardingKeys.CHECK_SALARY_TOTAL, "ALL", rows.stream()
                .map(r -> r.decimal("base_salary")).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Override
    public List<ReconciliationValue> committedTotals(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = rows.stream().map(CommittedRow::targetId).toList();
        BigDecimal total = ids.isEmpty() ? BigDecimal.ZERO : s.write(() -> s.db().jdbc().sql("""
                        select coalesce(sum(base_salary_amount), 0) from hr.salary_record
                         where tenant_id = :t and id in (:ids)""")
                .param("t", OnboardingSupport.tenant()).param("ids", ids).query(BigDecimal.class).single());
        return List.of(new ReconciliationValue(OnboardingKeys.CHECK_SALARY_TOTAL, "ALL", total));
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        return List.of("Salary records are bi-temporal history and cannot be deleted; correct them with a salary "
                + "correction");
    }
}
