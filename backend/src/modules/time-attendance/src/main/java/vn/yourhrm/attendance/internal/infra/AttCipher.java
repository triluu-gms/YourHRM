package vn.yourhrm.attendance.internal.infra;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.common.crypto.SubjectKeys;
import vn.yourhrm.shared.common.crypto.SubjectKeys.SubjectKey;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Field-level encryption of T&amp;A's P3 columns (the SI claim data of a leave request, BR-ATT-063; ADR-0026, DESIGN
 * §9.3), the same scheme as People's: AES-256-GCM under the PERSON's subject key ({@link SubjectKeys}), a random 96-bit
 * IV in front of the ciphertext, and {@code personId|column} as associated data so a value cannot be moved to another
 * person or column. Destroying the person's key crypto-shreds these values together with People's. Plain values never
 * reach logs, events or exceptions.
 */
@Component
public class AttCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** An encrypted value and the subject-key version it was encrypted with. */
    public record Sealed(byte[] ciphertext, int keyVersion) {
    }

    private final ObjectProvider<SubjectKeys> subjectKeys;
    private final SecureRandom random = new SecureRandom();

    public AttCipher(ObjectProvider<SubjectKeys> subjectKeys) {
        this.subjectKeys = subjectKeys;
    }

    /** Encrypts {@code plaintext} of the person for {@code column}; null / blank stays null. */
    public Sealed encrypt(UUID tenantId, UUID personId, String column, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        SubjectKey key = keys().current(tenantId, SubjectKeys.PERSON, personId);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.plaintext(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(personId, column));
            byte[] ct = c.doFinal(plaintext.strip().getBytes(StandardCharsets.UTF_8));
            return new Sealed(ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array(), key.version());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encrypting " + column + " failed", e);
        } finally {
            Arrays.fill(key.plaintext(), (byte) 0);
        }
    }

    /** Decrypts a value of {@link #encrypt}; null stays null. */
    public String decrypt(UUID tenantId, UUID personId, String column, byte[] sealed, int keyVersion) {
        if (sealed == null) {
            return null;
        }
        byte[] key = keys().key(tenantId, SubjectKeys.PERSON, personId, keyVersion);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            c.updateAAD(aad(personId, column));
            return new String(c.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decrypting " + column + " failed", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The last 4 characters of a value (display hint), null when absent. */
    public static String last4(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String s = value.strip();
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    private SubjectKeys keys() {
        SubjectKeys k = subjectKeys.getIfAvailable();
        if (k == null) {
            throw ApiException.unprocessable("keyring-unavailable",
                    "The personal-data keyring is not available; sensitive data cannot be stored");
        }
        return k;
    }

    private static byte[] aad(UUID personId, String column) {
        return (personId + "|" + column).getBytes(StandardCharsets.UTF_8);
    }
}
