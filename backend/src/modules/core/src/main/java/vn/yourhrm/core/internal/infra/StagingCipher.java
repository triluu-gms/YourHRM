package vn.yourhrm.core.internal.infra;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encryption of staged import rows (ADR-0037 §3, ADR-0026): AES-256-GCM with the job's data key, a random 96-bit IV
 * per row stored in front of the ciphertext, and the job id as associated data so a row cannot be moved into
 * another job. Destroying the job's wrapped key makes its staging unreadable.
 */
@Component
public class StagingCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();

    public byte[] encrypt(byte[] key, UUID jobId, byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(jobId));
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encrypting a staged row failed", e);
        }
    }

    public byte[] decrypt(byte[] key, UUID jobId, byte[] sealed) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            c.updateAAD(aad(jobId));
            return c.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A staged row cannot be decrypted (wrong job or tampered)", e);
        }
    }

    private static byte[] aad(UUID jobId) {
        return ByteBuffer.allocate(16).putLong(jobId.getMostSignificantBits())
                .putLong(jobId.getLeastSignificantBits()).array();
    }
}
