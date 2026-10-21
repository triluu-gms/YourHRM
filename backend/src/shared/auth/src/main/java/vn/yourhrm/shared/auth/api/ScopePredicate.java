package vn.yourhrm.shared.auth.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The resolved data scope of the current user for one permission at one date (ADR-0006 decision 3,
 * US-CORE-037). It is the UNION of all role assignments carrying the permission (BR-CORE-032):
 * a row is in scope when it matches ANY of the dimensions below. An empty predicate denies everything
 * (default deny).
 *
 * <p>Org-unit subtrees are already expanded (as of the requested date) into {@link #orgUnitIds()}, so
 * modules filter with index-friendly {@code org_unit_id = ANY(...)} conditions (DESIGN.md §5.3).
 *
 * @param tenantWide     a TENANT-scoped grant exists: no row filter
 * @param legalEntityIds rows whose legal_entity_id is in this set
 * @param orgUnitIds     rows whose org_unit_id is in this set (subtrees expanded)
 * @param locationIds    rows whose location_id is in this set
 * @param employmentIds  rows of these employments (SELF / DIRECT_REPORTS / ALL_REPORTS)
 */
public record ScopePredicate(
        boolean tenantWide,
        Set<UUID> legalEntityIds,
        Set<UUID> orgUnitIds,
        Set<UUID> locationIds,
        Set<UUID> employmentIds) {

    public ScopePredicate {
        legalEntityIds = Set.copyOf(legalEntityIds);
        orgUnitIds = Set.copyOf(orgUnitIds);
        locationIds = Set.copyOf(locationIds);
        employmentIds = Set.copyOf(employmentIds);
    }

    public static ScopePredicate none() {
        return new ScopePredicate(false, Set.of(), Set.of(), Set.of(), Set.of());
    }

    public static ScopePredicate tenant() {
        return new ScopePredicate(true, Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** True when nothing at all is in scope. */
    public boolean isEmpty() {
        return !tenantWide && legalEntityIds.isEmpty() && orgUnitIds.isEmpty() && locationIds.isEmpty()
                && employmentIds.isEmpty();
    }

    /**
     * Does a row with these attributes fall in scope? {@code null} attributes never match.
     */
    public boolean covers(UUID legalEntityId, UUID orgUnitId, UUID locationId, UUID employmentId) {
        return tenantWide
                || (legalEntityId != null && legalEntityIds.contains(legalEntityId))
                || (orgUnitId != null && orgUnitIds.contains(orgUnitId))
                || (locationId != null && locationIds.contains(locationId))
                || (employmentId != null && employmentIds.contains(employmentId));
    }

    /**
     * Translates the predicate into an SQL boolean expression for named-parameter queries
     * ({@code JdbcClient}, {@code NamedParameterJdbcTemplate}; collections expand into IN lists).
     * Dimensions whose column is {@code null} cannot be applied and therefore grant nothing (fail closed).
     *
     * @param columns     the table's columns for each dimension
     * @param paramPrefix prefix for the generated parameter names (unique per query)
     */
    public SqlFragment toSql(Columns columns, String paramPrefix) {
        if (tenantWide) {
            return new SqlFragment("(true)", Map.of());
        }
        List<String> terms = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        addTerm(terms, params, columns.legalEntity(), legalEntityIds, paramPrefix + "_le");
        addTerm(terms, params, columns.orgUnit(), orgUnitIds, paramPrefix + "_ou");
        addTerm(terms, params, columns.location(), locationIds, paramPrefix + "_loc");
        addTerm(terms, params, columns.employment(), employmentIds, paramPrefix + "_emp");
        if (terms.isEmpty()) {
            return new SqlFragment("(false)", Map.of());
        }
        return new SqlFragment("(" + String.join(" or ", terms) + ")", params);
    }

    private static void addTerm(List<String> terms, Map<String, Object> params, String column, Set<UUID> ids,
            String param) {
        if (column == null || ids.isEmpty()) {
            return;
        }
        terms.add(column + " in (:" + param + ")");
        params.put(param, List.copyOf(ids));
    }

    /** Column names of a scoped table; {@code null} when the table has no such dimension. */
    public record Columns(String legalEntity, String orgUnit, String location, String employment) {
    }

    /** A SQL boolean expression plus its named parameters. */
    public record SqlFragment(String sql, Map<String, Object> params) {
        public SqlFragment {
            params = Map.copyOf(params);
        }
    }
}
