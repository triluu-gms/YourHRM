package vn.yourhrm.shared.common.legal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the Legal Parameter Service ({@code getParam(key, scope, asOfDate)}, ADR-0010 / ADR-0025, BR-SAAS
 * PR-06). Implemented by the SaaS module, which alone reads {@code saas.legal_param_*}; modules never hard-code a
 * statutory value. Evaluated for the tenant of the current {@code TenantContext}: an active tenant override at the
 * most specific permitted scope (LOCATION → LEGAL_ENTITY → TENANT) wins over the pack value.
 */
public interface LegalParameters {

    /**
     * MAP of administrative unit code → minimum-wage region; row value {@code {"region": "I" | "II" | "III" | "IV"}}.
     * [verify-legal] key name to be confirmed with the pack authoring team (saas.legal_param_definition).
     */
    String MIN_WAGE_REGION_MAP = "MIN_WAGE.REGION_MAP";

    /** Override scope of a lookup; {@code null} members mean "not at that level". */
    record Scope(UUID legalEntityId, UUID locationId) {

        public static Scope tenant() {
            return new Scope(null, null);
        }
    }

    /**
     * One row of a MAP parameter.
     *
     * @param value       the row value (e.g. {@code {"region": "II"}})
     * @param packVersion version of the pack the value came from
     * @param overridden  true when a tenant override supplied the value
     */
    record MapEntry(Map<String, Object> value, long packVersion, boolean overridden) {
    }

    /**
     * A scalar or structured parameter value ({@code getParam(key, scope, asOfDate)}).
     *
     * @param value       a JSON-compatible value: Number, String, Boolean, List or Map
     * @param packId      the published legal pack the value came from (stamped on derived records, e.g.
     *                    {@code hr.insurance_participation.legal_pack_id})
     * @param packVersion version of that pack
     * @param overridden  true when a tenant override supplied the value
     */
    record Value(Object value, UUID packId, long packVersion, boolean overridden) {

        public BigDecimal asDecimal() {
            return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
        }

        public int asInt() {
            return asDecimal().intValueExact();
        }

        public String asString() {
            return value.toString();
        }

        public boolean asBoolean() {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> asMap() {
            return (Map<String, Object>) value;
        }

        @SuppressWarnings("unchecked")
        public List<Object> asList() {
            return (List<Object>) value;
        }
    }

    /**
     * Parameter {@code key} in effect on {@code asOf} for {@code scope}, using the current published pack. Empty when
     * the pack does not define it: callers fail closed (refuse the operation), they never fall back to a constant.
     */
    Optional<Value> value(String key, Scope scope, LocalDate asOf);

    /** Row {@code rowKey} of MAP parameter {@code key} on {@code asOf}, using the current published pack. */
    Optional<MapEntry> mapEntry(String key, String rowKey, Scope scope, LocalDate asOf);

    /** The same lookup against a specific published pack version (used to diff two packs, BR-CORE-007). */
    Optional<MapEntry> mapEntry(String key, String rowKey, Scope scope, LocalDate asOf, long packVersion);
}
