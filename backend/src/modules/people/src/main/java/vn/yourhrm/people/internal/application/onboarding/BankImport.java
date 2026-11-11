package vn.yourhrm.people.internal.application.onboarding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.application.bank.BankAccountService;
import vn.yourhrm.people.internal.infra.PiiCipher;

/**
 * {@code OB_EMP_BANK} (US-PPL-089/090/091; BR-PPL-080..): salary bank accounts of the person, ACTIVE without approval
 * through the bank stream's {@code createForInitialLoad} (same validation as the UI: bank and branch in Core's bank
 * master data, split rules, one REMAINDER account per date, shared-account check; {@code BankAccountChanged}
 * ACTIVATED with {@code initialLoad}). The account number is a sensitive column (staged encrypted, never previewed).
 * Idempotent by the account's blind index.
 */
@Component
class BankImport extends OpeningBalanceImport {

    private final BankAccountService accounts;
    private final PiiCipher cipher;

    BankImport(OnboardingSupport support, BankAccountService accounts, PiiCipher cipher) {
        super(support);
        this.accounts = accounts;
        this.cipher = cipher;
    }

    @Override
    public Definition definition() {
        return definition(OnboardingKeys.BANK, "Số dư đầu kỳ - Tài khoản ngân hàng", "Opening balance - bank accounts",
                List.of(employeeNo(),
                        integer("priority", "Thứ tự ưu tiên", "Priority", true),
                        text("bank_code", "Mã ngân hàng", "Bank code", true, "Mã trong danh mục ngân hàng của Core",
                                "Code of Core's bank master data"),
                        text("branch_code", "Mã chi nhánh", "Branch code", false),
                        sensitive("account_no", "Số tài khoản", "Account number", ColumnType.TEXT, true, null, null),
                        text("holder_name", "Tên chủ tài khoản", "Account holder", true),
                        text("currency", "Tiền tệ", "Currency", false, "Mặc định VND", "Default VND"),
                        text("split_type", "Cách chia lương", "Split type", false, "REMAINDER (mặc định), PERCENT, FIXED",
                                "REMAINDER (default), PERCENT, FIXED"),
                        sensitive("split_value", "Giá trị chia", "Split value", ColumnType.DECIMAL, false, null, null),
                        date("valid_from", "Hiệu lực từ", "Valid from", false)),
                List.of("employee_no", "priority"), List.of(OnboardingKeys.MASTER),
                List.of(new ControlCheck(OnboardingKeys.CHECK_BANK_ACCOUNTS, "Số tài khoản ngân hàng",
                        "Number of bank accounts")));
    }

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        OnboardingSupport.Emp emp = s.requireEmployment(row.text("employee_no"));
        s.requireUnlocked(emp.legalEntityId());
        String account = PiiCipher.normalise(row.text("account_no"));
        Optional<UUID> same = s.db().jdbc().sql("""
                        select id from hr.bank_account where tenant_id = :t and person_id = :p and account_no_bidx = :b
                           and status in ('ACTIVE','PENDING_VERIFICATION')""")
                .param("t", OnboardingSupport.tenant()).param("p", emp.personId())
                .param("b", cipher.blindIndex(OnboardingSupport.tenant(), account)).query(UUID.class).optional();
        if (same.isPresent()) {
            return Written.updated("hr.bank_account", same.get(), List.of());
        }
        UUID id = accounts.createForInitialLoad(emp.personId(), new BankAccountService.Attributes(
                upper(row, "bank_code"), row.text("branch_code"), account, row.text("holder_name"),
                row.text("currency") == null ? null : upper(row, "currency"), upper(row, "split_type"),
                row.decimal("split_value"), integer(row, "priority"),
                row.date("valid_from") != null ? row.date("valid_from") : s.asOf(ctx), null));
        return Written.created("hr.bank_account", id, new ArrayList<>());
    }

    @Override
    public List<ReconciliationValue> stagedTotals(ImportContext ctx, List<ImportRow> rows) {
        return List.of(new ReconciliationValue(OnboardingKeys.CHECK_BANK_ACCOUNTS, "ALL",
                BigDecimal.valueOf(rows.size())));
    }

    @Override
    public List<ReconciliationValue> committedTotals(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = rows.stream().map(CommittedRow::targetId).distinct().toList();
        long n = ids.isEmpty() ? 0 : s.write(() -> s.db().jdbc().sql("""
                        select count(*) from hr.bank_account where tenant_id = :t and id in (:ids) and status = 'ACTIVE'""")
                .param("t", OnboardingSupport.tenant()).param("ids", ids).query(Long.class).single());
        return List.of(new ReconciliationValue(OnboardingKeys.CHECK_BANK_ACCOUNTS, "ALL", BigDecimal.valueOf(n)));
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        return s.write(() -> s.locked(ctx.legalEntityId()) ? List.of("Opening balances are locked (go-live)")
                : new ArrayList<String>());
    }

    /**
     * Deletes the accounts the job created (DDL v0.6 grants DELETE on {@code hr.bank_account}); accounts that existed
     * before the job were only matched, never changed, and stay. The initial-load {@code BankAccountChanged} events
     * are not recalled (as for the other opening-balance sheets).
     */
    @Override
    public void undo(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = EmployeeMasterImport.created(rows);
        if (!ids.isEmpty()) {
            s.write(() -> s.db().jdbc().sql("delete from hr.bank_account where tenant_id = :t and id in (:ids)")
                    .param("t", OnboardingSupport.tenant()).param("ids", ids).update());
        }
    }
}
