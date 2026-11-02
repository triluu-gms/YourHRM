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
