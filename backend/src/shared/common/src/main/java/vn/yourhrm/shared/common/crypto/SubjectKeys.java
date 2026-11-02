package vn.yourhrm.shared.common.crypto;

import java.util.UUID;

/**
 * SPI of the SaaS keyring for personal data (ADR-0026, DESIGN §9.3; {@code saas.subject_key}), implemented by the SaaS
 * module. Each data subject (a person, a family member, a candidate ...) has its own data encryption key, wrapped by
 * the tenant KEK and kept in the platform cluster; destroying it crypto-shreds every P3 value of that subject.
 * Blind indexes ({@code *_bidx}) use one HMAC key per tenant derived from the tenant KEK (HKDF), never stored in plain.
 *
 * <p>Keys are used in memory only: never logged, never put into events or job payloads.
 */
public interface SubjectKeys {

    /** Subject types of {@code saas.subject_key.subject_type}. */
    String PERSON = "PERSON";
    String FAMILY_MEMBER = "FAMILY_MEMBER";

    /** A subject key in plaintext (AES-256) and its version (stored on the row as {@code subject_key_version}). */
    record SubjectKey(byte[] plaintext, int version) {
    }

    /** The current key of the subject, created on first use. */
    SubjectKey current(UUID tenantId, String subjectType, UUID subjectId);

    /**
     * A given version of the subject's key.
     *
     * @throws IllegalStateException when the key was destroyed (the subject was crypto-shredded)
     */
    byte[] key(UUID tenantId, String subjectType, UUID subjectId, int version);

    /** The tenant's blind-index key (HMAC-SHA256). */
    byte[] blindIndexKey(UUID tenantId);

    /** Destroys every version of the subject's key (DSR erasure, retention purge). Irreversible; idempotent. */
    void destroy(UUID tenantId, String subjectType, UUID subjectId);
}
