package vn.yourhrm.people.api;

import java.util.UUID;

/**
 * Consumer-side SPI of the SaaS consent registry (BR-PPL-151, IN-27, US-PPL-084): People never stores consent, it
 * asks {@code hasConsent()} before processing OPTIONAL sensitive data (religion, party / youth-union membership).
 * Data processed on a lawful basis (labour contract, legal obligation) needs no consent.
 *
 * <p>Implemented by the SaaS platform when it exists. Until then People uses {@link #NONE}: nobody has consented, so
 * optional sensitive fields cannot be written and are hidden on read (fail closed). A withdrawn consent
 * ({@code saas.ConsentChanged}) hides the data at the next read because the check runs on every access.
 */
public interface PersonConsentRegistry {

    /** Purpose: recording the person's religion (optional, Decree 13/2023 sensitive data). */
    String PURPOSE_RELIGION = "hr.religion";
    /** Purpose: recording party / youth-union membership (optional political data). */
    String PURPOSE_POLITICAL_MEMBERSHIP = "hr.political_membership";

    /** Has the person (data subject) a valid consent for {@code purpose} in the tenant today? */
    boolean hasConsent(UUID tenantId, UUID personId, String purpose);

    /** STUB until the SaaS consent registry exists: no consent for anybody (fail closed). */
    PersonConsentRegistry NONE = new PersonConsentRegistry() {
        @Override
        public boolean hasConsent(UUID tenantId, UUID personId, String purpose) {
            return false;
        }

        @Override
        public String toString() {
            return "PersonConsentRegistry.NONE (stub until the SaaS consent registry exists: no consent)";
        }
    };
}
