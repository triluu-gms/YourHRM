package vn.yourhrm.core.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Hierarchical tenant settings (BR-CORE-084, interface P-17): the most specific value valid on the date wins,
 * LOCATION → LEGAL_ENTITY → TENANT → the definition's default. Evaluated for the current tenant.
 */
public interface Settings {

    /** Where the value is looked up; both null = tenant level. A location needs its legal entity. */
    record Scope(UUID legalEntityId, UUID locationId) {

        public static Scope tenant() {
            return new Scope(null, null);
        }
    }

    /** The value as a JSON-compatible Java value (Boolean, Number, String, Map, List). Unknown keys are refused. */
    Object value(String key, Scope scope, LocalDate asOf);

    boolean booleanValue(String key, Scope scope, LocalDate asOf);

    long longValue(String key, Scope scope, LocalDate asOf);

    String stringValue(String key, Scope scope, LocalDate asOf);
}
