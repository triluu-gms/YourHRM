package vn.yourhrm.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.yourhrm.core.api.ImportType;

/**
 * An opening-balance import of a pretend Payroll module (tests only): one row per employee number with an amount and a
 * sensitive identity number; it depends on the org-unit load, allows partial commit and reconciles the amount total.
 * Committed rows are kept in memory (a real module writes its own schema).
 */
public class TestBalanceImport implements ImportType {

    public static final String CODE = "TEST_OB_BALANCE";
    public static final String PERMISSION = "pay.test_balance.import";
    /** Committed amounts by job, then by employee number. */
    public static final Map<UUID, Map<String, BigDecimal>> COMMITTED = new ConcurrentHashMap<>();

    /** The release job's part: the permission exists in the global catalogue. */
    public static void register(JdbcTemplate admin) {
        admin.update("""
                insert into core.permission (permission_code, owner_module, function_code, action, name_vi, name_en,
                       is_sensitive, is_privileged, requires_step_up, is_active)
                values (?, 'pay', 'pay.test_balance', 'CREATE', 'Import số dư (test)', 'Import balances (test)',
                        false, false, false, true)
                on conflict do nothing""", PERMISSION);
    }

    @Override
    public Definition definition() {
        return new Definition(CODE, "pay", "Số dư đầu kỳ (test)", "Opening balances (test)", "3", PERMISSION,
                List.of(Column.of("employee_no", "Mã nhân viên", "Employee number", ColumnType.TEXT, true),
                        Column.of("amount", "Số tiền", "Amount", ColumnType.DECIMAL, true),
                        new Column("id_number", "Số CCCD", "Identity number", ColumnType.TEXT, false, null, true,
                                null, null)),
                List.of("employee_no"), true, true, List.of("CORE_ORG_UNIT"),
                List.of(new ControlCheck("AMOUNT_TOTAL", "Tổng số tiền", "Total amount")));
    }

    @Override
    public List<RowMessage> validate(ImportContext ctx, ImportRow row) {
        List<RowMessage> out = new ArrayList<>();
        if (row.decimal("amount").signum() < 0) {
            out.add(RowMessage.error("amount", "negative", "Số tiền âm", "Negative amount"));
        }
        return out;
    }

    @Override
    public CommitResult commit(ImportContext ctx, ImportRow row) {
        COMMITTED.computeIfAbsent(ctx.jobId(), j -> new ConcurrentHashMap<>()).put(row.text("employee_no"),
                row.decimal("amount"));
        return CommitResult.created("pay.test_balance", UUID.nameUUIDFromBytes(row.businessKey().getBytes()));
    }

    @Override
    public List<ReconciliationValue> stagedTotals(ImportContext ctx, List<ImportRow> rows) {
        return List.of(new ReconciliationValue("AMOUNT_TOTAL", "ALL",
                rows.stream().map(r -> r.decimal("amount")).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Override
    public List<ReconciliationValue> committedTotals(ImportContext ctx, List<CommittedRow> rows) {
        return List.of(new ReconciliationValue("AMOUNT_TOTAL", "ALL", COMMITTED.getOrDefault(ctx.jobId(), Map.of())
                .values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        return List.of("Payroll run 2026-10 already used these balances");
    }
}
