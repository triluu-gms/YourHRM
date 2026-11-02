package vn.yourhrm.people.internal.infra;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.common.crypto.SubjectKeys;
import vn.yourhrm.shared.common.crypto.SubjectKeys.SubjectKey;

/**
 * Field-level encryption of People's P3 columns (NFR-PPL-005, ADR-0026, DESIGN §9.3):
 * <ul>
 *   <li>{@code *_enc}: AES-256-GCM under the data subject's key ({@link SubjectKeys}), a random 96-bit IV in front of
 *       the ciphertext, and {@code subjectId|column} as associated data so a value cannot be moved to another subject
 *       or column; the key version goes to the row's {@code subject_key_version};</li>
 *   <li>{@code *_bidx}: HMAC-SHA256 under the tenant's blind-index key over the {@link #normalise normalised} value,
 *       truncated to 16 bytes, for equality lookups (duplicate CCCD, exact search);</li>
 *   <li>{@code *_last4}: display hint for masked UIs.</li>
 * </ul>
 * Plain values never reach logs, events or exceptions.
 */
@Component
public class PiiCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int BIDX_BYTES = 16;

    /** The data subject a value belongs to (its key), e.g. {@code PERSON}/person id. */
    public record Subject(UUID tenantId, String type, UUID id) {

        public static Subject person(UUID tenantId, UUID personId) {
            return new Subject(tenantId, SubjectKeys.PERSON, personId);
        }

        public static Subject familyMember(UUID tenantId, UUID familyMemberId) {
            return new Subject(tenantId, SubjectKeys.FAMILY_MEMBER, familyMemberId);
        }
    }

    /** An encrypted value and the subject-key version it was encrypted with. */
    public record Sealed(byte[] ciphertext, int keyVersion) {
    }

    private final ObjectProvider<SubjectKeys> subjectKeys;
    private final SecureRandom random = new SecureRandom();

    public PiiCipher(ObjectProvider<SubjectKeys> subjectKeys) {
        this.subjectKeys = subjectKeys;
    }

    /** Encrypts {@code plaintext} for column {@code column}; null stays null. */
    public Sealed encrypt(Subject subject, String column, String plaintext) {
        if (plaintext == null) {
            return null;
        }
        SubjectKey key = keys().current(subject.tenantId(), subject.type(), subject.id());
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.plaintext(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(subject, column));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Sealed(ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array(), key.version());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encrypting " + column + " failed", e);
        } finally {
            Arrays.fill(key.plaintext(), (byte) 0);
        }
    }

    /** Decrypts a value of {@code column}; null stays null. Callers audit the sensitive read. */
    public String decrypt(Subject subject, String column, byte[] sealed, int keyVersion) {
        if (sealed == null) {
            return null;
        }
        byte[] key = keys().key(subject.tenantId(), subject.type(), subject.id(), keyVersion);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            c.updateAAD(aad(subject, column));
            return new String(c.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A value of " + column + " cannot be decrypted (wrong subject or tampered)", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Blind index of {@code value} for equality lookups within the tenant; null stays null. */
    public byte[] blindIndex(UUID tenantId, String value) {
        if (value == null) {
            return null;
        }
        byte[] key = keys().blindIndexKey(tenantId);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Arrays.copyOf(mac.doFinal(normalise(value).getBytes(StandardCharsets.UTF_8)), BIDX_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Computing a blind index failed", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The last (up to) four characters of the normalised value; null stays null. */
    public static String last4(String value) {
        if (value == null) {
            return null;
        }
        String n = normalise(value);
        return n.length() <= 4 ? n : n.substring(n.length() - 4);
    }

    /** Identifier normal form: NFKC, upper case, without spaces, dots and dashes ("001 090-012345" = "001090012345"). */
    public static String normalise(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[\\s.\\-]", "");
    }

    private SubjectKeys keys() {
        SubjectKeys k = subjectKeys.getIfAvailable();
        if (k == null) {
            throw new IllegalStateException("No key service (SubjectKeys) is available: P3 data cannot be processed");
        }
        return k;
    }

    private static byte[] aad(Subject subject, String column) {
        return (subject.id() + "|" + column).getBytes(StandardCharsets.UTF_8);
    }
}
