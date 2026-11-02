package vn.yourhrm.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An import type a module registers with Core's single import engine (BR-CORE-110, US-CORE-110; ADR-0037). Core owns
 * the template, the upload, parsing into encrypted staging, the generic checks (required, types, code-or-name
 * lookups, duplicate business keys in the file), dry-run, error file, history, reconciliation and the commit
 * transaction; the owner module validates its business rules and writes its own schema through this handler.
 *
 * <p>Commit is all-or-nothing by default: every row is committed in one transaction and any failure rolls back the
 * batch. {@link Definition#partialCommit} lets the valid rows commit while rows with errors are skipped. Within 24 h a
 * committed job can be rolled back through {@link #undo} unless {@link #undoBlockers} names dependent data.
 */
public interface ImportType {

    Definition definition();

    /** Business validation of one row the generic checks accepted; rows are passed in file order. */
    List<RowMessage> validate(ImportContext ctx, ImportRow row);

    /**
     * Writes one row in the owner module's schema inside the engine's commit transaction (idempotent by business
     * key: an existing record is updated). Opening-balance jobs ({@link ImportContext#openingBalance}) skip workflows
     * and side effects and stamp their events {@code initial_load = true}.
     */
    CommitResult commit(ImportContext ctx, ImportRow row);

    /** Why the committed rows cannot be undone (dependent transactions); empty = undo allowed. */
    default List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        return List.of("Import type " + definition().code() + " does not support rollback");
    }

    /** Removes what the job created (rows with action CREATED), in one transaction. */
    default void undo(ImportContext ctx, List<CommittedRow> rows) {
        throw new UnsupportedOperationException("rollback");
    }

    /** Opening balance: the type's control values computed from the validated staging rows (ADR-0037 §3). */
    default List<ReconciliationValue> stagedTotals(ImportContext ctx, List<ImportRow> rows) {
        return List.of();
    }

    /** Opening balance: the same values recomputed from the owner module's tables after commit. */
    default List<ReconciliationValue> committedTotals(ImportContext ctx, List<CommittedRow> rows) {
        return List.of();
    }

    // -------------------------------------------------------------------------------------------- definition

    /**
     * @param code            e.g. {@code CORE_ORG_UNIT}, {@code OB_EMPLOYEE_MASTER}
     * @param templateVersion stamped into generated templates and checked on upload
     * @param permission      needed to upload, commit and roll back jobs of this type
     * @param businessKey     column keys identifying a record (duplicates in one file are errors)
     * @param openingBalance  the type can run in OPENING_BALANCE mode (go-live load)
     * @param dependsOn       import types whose opening-balance job must be committed first
     * @param controlChecks   opening-balance reconciliation checks (control sheet vs staged vs committed)
     */
    record Definition(String code, String ownerModule, String nameVi, String nameEn, String templateVersion,
            String permission, List<Column> columns, List<String> businessKey, boolean partialCommit,
            boolean openingBalance, List<String> dependsOn, List<ControlCheck> controlChecks) {

        public Definition {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(ownerModule, "ownerModule");
            Objects.requireNonNull(templateVersion, "templateVersion");
            Objects.requireNonNull(permission, "permission");
            columns = List.copyOf(columns);
            businessKey = List.copyOf(businessKey);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            controlChecks = controlChecks == null ? List.of() : List.copyOf(controlChecks);
        }
    }

    enum ColumnType { TEXT, INTEGER, DECIMAL, DATE, BOOLEAN, CODE }

    /**
     * A template column. CODE columns name a {@code lookup}, which accepts a code or an (accent-insensitive) name and
     * yields: {@code CODE_LIST:<list>} the value code; {@code LEGAL_ENTITY}, {@code LOCATION}, {@code ORG_UNIT},
     * {@code ORG_UNIT_TYPE} the record id. Sensitive columns (P2/P3) never appear in the preview grid.
     */
    record Column(String key, String headerVi, String headerEn, ColumnType type, boolean required, String lookup,
            boolean sensitive, String hintVi, String hintEn) {

        public Column {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
            if (type == ColumnType.CODE && lookup == null) {
                throw new IllegalArgumentException("CODE column " + key + " needs a lookup");
            }
        }

        public static Column of(String key, String headerVi, String headerEn, ColumnType type, boolean required) {
            return new Column(key, headerVi, headerEn, type, required, null, false, null, null);
        }

        public static Column lookup(String key, String headerVi, String headerEn, String lookup, boolean required) {
            return new Column(key, headerVi, headerEn, ColumnType.CODE, required, lookup, false, null, null);
        }
    }

    record ControlCheck(String code, String labelVi, String labelEn) {
    }

    // ------------------------------------------------------------------------------------------------ runtime

    /**
     * @param seenKeys business keys of the rows before this one in the file (dependency order inside one file,
     *                 e.g. a parent unit listed above its children)
     */
    record ImportContext(UUID tenantId, UUID jobId, String importType, String mode, UUID legalEntityId,
            LocalDate asOfDate, Set<String> seenKeys) {

        public boolean openingBalance() {
            return "OPENING_BALANCE".equals(mode);
        }

        /** Events of an opening-balance commit carry {@code initial_load = true} (ADR-0037 §8). */
        public boolean initialLoad() {
            return openingBalance();
        }
    }

    /**
     * One staged row: typed values by column key (String, Long, BigDecimal, LocalDate, Boolean; CODE columns hold the
     * resolved code or record id), plus the optional {@code external_ref} of the source system kept for tracing.
     */
    record ImportRow(int rowNo, String businessKey, String externalRef, Map<String, Object> values) {

        public String text(String key) {
            Object v = values.get(key);
            return v == null ? null : v.toString();
        }

        public UUID id(String key) {
            Object v = values.get(key);
            return v == null ? null : (UUID) v;
        }

        public LocalDate date(String key) {
            return (LocalDate) values.get(key);
        }

        public BigDecimal decimal(String key) {
            return (BigDecimal) values.get(key);
        }
    }

    /** {@code severity} ERROR or WARNING; messages in both languages (BR-CORE-111). */
    record RowMessage(String field, String code, String severity, String messageVi, String messageEn) {

        public static RowMessage error(String field, String code, String vi, String en) {
            return new RowMessage(field, code, "ERROR", vi, en);
        }

        public static RowMessage warning(String field, String code, String vi, String en) {
            return new RowMessage(field, code, "WARNING", vi, en);
        }
    }

    /** {@code action} CREATED or UPDATED. */
    record CommitResult(String targetEntity, UUID targetId, String action) {

        public static CommitResult created(String entity, UUID id) {
            return new CommitResult(entity, id, "CREATED");
        }

        public static CommitResult updated(String entity, UUID id) {
            return new CommitResult(entity, id, "UPDATED");
        }
    }

    record CommittedRow(int rowNo, String businessKey, Map<String, Object> values, String targetEntity, UUID targetId,
            String action) {
    }

    /** One reconciliation value: a check at a grain, e.g. {@code HEADCOUNT} at {@code LE=HN01}. */
    record ReconciliationValue(String checkCode, String grainKey, BigDecimal value) {
    }
}
