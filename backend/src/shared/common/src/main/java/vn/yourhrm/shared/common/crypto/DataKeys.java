package vn.yourhrm.shared.common.crypto;

import java.util.UUID;

/**
 * SPI of the SaaS key service (ADR-0026, DESIGN §9.3; OpenBao transit), implemented by the SaaS module: data
 * encryption keys (AES-256) wrapped by the tenant's KEK. The plaintext key is used in memory only and never stored;
 * destroying the wrapped key (or the tenant KEK) crypto-shreds whatever it encrypted.
 */
public interface DataKeys {

    /** A fresh 256-bit key: {@code plaintext} for immediate use, {@code wrapped} to store next to the ciphertext. */
    record DataKey(byte[] plaintext, byte[] wrapped, int keyVersion) {
    }

    DataKey generate(UUID tenantId);

    /** The plaintext of a key {@link #generate} returned for the tenant. */
    byte[] unwrap(UUID tenantId, byte[] wrapped, int keyVersion);
}
