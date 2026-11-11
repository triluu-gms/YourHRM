package vn.yourhrm.core.internal.infra;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Pattern A version tables (ADR-0024: anchor + {@code *_version} with {@code [valid_from, valid_to)} and a
 * no-overlap exclusion constraint). A change "from date D" either corrects the version that starts on D, or
 * splits the version covering D: the old part ends at D ({@code [)} semantics, i.e. its last day is D-1) and a
 * new version starts at D and keeps the old end, so later future versions stay untouched (US-CORE-003 AC).
 *
 * <p>Table and column names are compile-time constants of the callers ({@link Table}), never user input.
 */
@Repository
public class EffectiveDatedVersions {

    /** A version table and the column referencing its anchor. */
    public record Table(String anchorTable, String versionTable, String anchorColumn) {
    }

    public static final Table LEGAL_ENTITY = new Table("core.legal_entity", "core.legal_entity_version",
            "legal_entity_id");
    public static final Table LOCATION = new Table("core.location", "core.location_version", "location_id");
    public static final Table ORG_UNIT = new Table("core.org_unit", "core.org_unit_version", "org_unit_id");

    /** The version covering a date: its id and period. */
    public record Span(UUID id, LocalDate validFrom, LocalDate validTo) {
    }

    /** Where a change from date D lands. {@code split} = the covering version must be closed at D first. */
    public record Slot(Span covering, boolean split) {
    }

    private final JdbcClient jdbc;

    public EffectiveDatedVersions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the anchor row (serialises concurrent changes of one entity) and its version covering {@code date}.
     *
     * @throws ApiException 404 when the anchor is missing, 422 when no version covers the date
     */
    public Slot slotFor(Table table, UUID tenant, UUID anchorId, LocalDate date) {
        jdbc.sql("select id from " + table.anchorTable() + " where tenant_id = :t and id = :id for update")
                .param("t", tenant).param("id", anchorId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Entity", anchorId));
        Span covering = covering(table, tenant, anchorId, date, true).orElseThrow(() -> ApiException.unprocessable(
                "no-version-at-date", "Nothing is in effect on " + date + " (before the start or after the end); "
                        + "a change must fall inside the existing history"));
        return new Slot(covering, !covering.validFrom().equals(date));
    }

    /**
     * Optimistic lock over an entity's whole history (the anchor's {@code version}): bumps it when it equals
     * {@code expectedVersion}, else 409 {@code stale-version} (404 when the anchor is missing). {@code null} skips
     * the comparison (internal writers such as imports) but still bumps. The update also locks the anchor row for
     * the rest of the transaction.
     */
    public void checkAndBump(Table table, UUID tenant, UUID anchorId, Integer expectedVersion, UUID actor, String label) {
        int updated = jdbc.sql("update " + table.anchorTable()
                        + " set version = version + 1, updated_at = now(), updated_by = :actor"
                        + " where tenant_id = :t and id = :id and (cast(:v as int) is null or version = :v)")
                .param("actor", actor).param("t", tenant).param("id", anchorId).param("v", expectedVersion).update();
        if (updated == 0) {
            Integer current = jdbc.sql("select version from " + table.anchorTable() + " where tenant_id = :t and id = :id")
                    .param("t", tenant).param("id", anchorId).query(Integer.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Entity", anchorId));
            throw ApiException.conflict("stale-version", label + " was changed (version " + current
                    + ", expected " + expectedVersion + "); reload and retry");
        }
    }

    /** A removed planned version and the version that now covers its period. */
    public record Removal(Span removed, Span extended) {
    }

    /**
     * Removes a planned version (one that starts after {@code today}) and extends the version before it over the
     * freed period, so the history stays gap-free. The first version of an entity cannot be removed this way.
     *
     * @throws ApiException 404 unknown version, 422 {@code version-already-effective} /
     *                      {@code cannot-remove-first-version}
     */
    public Removal removePlanned(Table table, UUID tenant, UUID anchorId, UUID versionId, LocalDate today) {
        Span removed = jdbc.sql("select id, valid_from, valid_to from " + table.versionTable()
                        + " where tenant_id = :t and " + table.anchorColumn() + " = :a and id = :id for update")
                .param("t", tenant).param("a", anchorId).param("id", versionId)
                .query((rs, n) -> new Span(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Version", versionId));
        if (!removed.validFrom().isAfter(today)) {
            throw ApiException.unprocessable("version-already-effective", "The version starting on "
                    + removed.validFrom() + " is already in effect; record a new change instead");
        }
        Span previous = jdbc.sql("select id, valid_from, valid_to from " + table.versionTable()
                        + " where tenant_id = :t and " + table.anchorColumn() + " = :a and valid_to = :d for update")
                .param("t", tenant).param("a", anchorId).param("d", removed.validFrom())
                .query((rs, n) -> new Span(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)))
                .optional().orElseThrow(() -> ApiException.unprocessable("cannot-remove-first-version",
                        "The version starting on " + removed.validFrom() + " is the first one and cannot be removed"));
        jdbc.sql("delete from " + table.versionTable() + " where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", removed.id()).update();
        jdbc.sql("update " + table.versionTable() + " set valid_to = :to, version = version + 1"
                        + " where tenant_id = :t and id = :id")
                .param("to", removed.validTo()).param("t", tenant).param("id", previous.id()).update();
        return new Removal(removed, new Span(previous.id(), previous.validFrom(), removed.validTo()));
    }

    /** The version valid on {@code date}, if any. */
    public Optional<Span> covering(Table table, UUID tenant, UUID anchorId, LocalDate date) {
        return covering(table, tenant, anchorId, date, false);
    }

    /** Ends the version at {@code date} (exclusive); the caller then inserts the version starting at {@code date}. */
    public void closeAt(Table table, UUID tenant, UUID versionId, LocalDate date) {
        jdbc.sql("update " + table.versionTable() + " set valid_to = :d where tenant_id = :t and id = :id")
                .param("d", date).param("t", tenant).param("id", versionId).update();
    }

    private Optional<Span> covering(Table table, UUID tenant, UUID anchorId, LocalDate date, boolean lock) {
        return jdbc.sql("select id, valid_from, valid_to from " + table.versionTable()
                        + " where tenant_id = :t and " + table.anchorColumn() + " = :a"
                        + " and valid_during @> cast(:d as date)" + (lock ? " for update" : ""))
                .param("t", tenant).param("a", anchorId).param("d", date)
                .query((rs, n) -> new Span(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)))
                .optional();
    }
}
