package vn.yourhrm.people.internal.application.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.people.api.PeoplePermissions;

/**
 * Base of People's opening-balance import types (US-PPL-089..092; ADR-0037, DESIGN §13). Every type is registered
 * with Core's import engine as a bean of {@link ImportType}; Core generates the VI / EN template with its column
 * dictionary (hints) and lookup lists, parses into encrypted staging (P3 columns are {@code sensitive}: never in the
 * preview), runs the generic checks, the dry run (validate-only), the control sheet reconciliation, sign-off, commit
 * and the 24-hour rollback. The permission of every type is {@code hr.import.run}.
 *
 * <p>{@link #validate}: the type's pure / in-file checks ({@link #precheck}), then the row's write path in a rolled-back
 * People transaction ({@link OnboardingSupport#dryRun}) so that the streams' own rules decide (errors vs warnings).
 * {@link #commit}: the same write path in its own People transaction, idempotent by natural key.
 */
abstract class OpeningBalanceImport implements ImportType {

    /** The outcome of a row's write path. */
    record Written(CommitResult result, List<RowMessage> warnings) {

        static Written created(String entity, UUID id, List<RowMessage> warnings) {
            return new Written(CommitResult.created(entity, id), warnings);
        }

        static Written updated(String entity, UUID id, List<RowMessage> warnings) {
            return new Written(CommitResult.updated(entity, id), warnings);
        }
    }

    protected final OnboardingSupport s;

    protected OpeningBalanceImport(OnboardingSupport support) {
        this.s = support;
    }

    /** Checks without writing (formats, cross-row rules of the file); {@code state} is the validation pass's. */
    protected List<RowMessage> precheck(ImportContext ctx, ImportRow row, Map<String, Object> state) {
        return List.of();
    }

    /** Writes the row (or rehearses it when {@code dryRun}); throws the stream's {@code ApiException} on a rule. */
    protected abstract Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun);

    @Override
    public List<RowMessage> validate(ImportContext ctx, ImportRow row) {
        Map<String, Object> state = s.state(ctx, "V");
        List<RowMessage> out = new ArrayList<>(precheck(ctx, row, state));
        if (!OnboardingSupport.hasError(out)) {
            out.addAll(s.dryRun(() -> write(ctx, row, state, true).warnings()));
        }
        return out;
    }

    @Override
    public CommitResult commit(ImportContext ctx, ImportRow row) {
        Map<String, Object> state = s.state(ctx, "C");
        return s.write(() -> write(ctx, row, state, false)).result();
    }

    // -------------------------------------------------------------------------------------------- definition help

    protected Definition definition(String code, String nameVi, String nameEn, List<Column> columns,
            List<String> businessKey, List<String> dependsOn, List<ControlCheck> checks) {
        return new Definition(code, "hr", nameVi, nameEn, OnboardingKeys.TEMPLATE_VERSION, PeoplePermissions.IMPORT_RUN,
                columns, businessKey, false, true, dependsOn, checks);
    }

    static Column text(String key, String vi, String en, boolean required, String hintVi, String hintEn) {
        return new Column(key, vi, en, ColumnType.TEXT, required, null, false, hintVi, hintEn);
    }

    static Column text(String key, String vi, String en, boolean required) {
        return Column.of(key, vi, en, ColumnType.TEXT, required);
    }

    /** P2 / P3 column: kept out of the preview grid, staged encrypted only. */
    static Column sensitive(String key, String vi, String en, ColumnType type, boolean required, String hintVi,
            String hintEn) {
        return new Column(key, vi, en, type, required, null, true, hintVi, hintEn);
    }

    static Column date(String key, String vi, String en, boolean required) {
        return Column.of(key, vi, en, ColumnType.DATE, required);
    }

    static Column decimal(String key, String vi, String en, boolean required) {
        return Column.of(key, vi, en, ColumnType.DECIMAL, required);
    }

    static Column integer(String key, String vi, String en, boolean required) {
        return Column.of(key, vi, en, ColumnType.INTEGER, required);
    }

    static Column bool(String key, String vi, String en, String hintVi, String hintEn) {
        return new Column(key, vi, en, ColumnType.BOOLEAN, false, null, false, hintVi, hintEn);
    }

    static Column lookup(String key, String vi, String en, String lookup, boolean required) {
        return new Column(key, vi, en, ColumnType.CODE, required, lookup, false, "Mã hoặc tên", "Code or name");
    }

    static Column employeeNo() {
        return text("employee_no", "Mã nhân viên", "Employee number", true,
                "Mã nhân viên của hồ sơ trong sheet OB_EMPLOYEE_MASTER",
                "Employee number of the OB_EMPLOYEE_MASTER row");
    }

    // ---------------------------------------------------------------------------------------------- row helpers

    static Boolean flag(ImportRow row, String key) {
        Object v = row.values().get(key);
        return v == null ? null : (Boolean) v;
    }

    static Integer integer(ImportRow row, String key) {
        Object v = row.values().get(key);
        return v == null ? null : ((Long) v).intValue();
    }

    static String upper(ImportRow row, String key) {
        String v = row.text(key);
        return v == null ? null : v.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
