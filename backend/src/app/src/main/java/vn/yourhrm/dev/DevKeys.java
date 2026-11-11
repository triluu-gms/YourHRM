package vn.yourhrm.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.common.crypto.DataKeys;
import vn.yourhrm.shared.common.crypto.SubjectKeys;

/**
 * LOCAL DEVELOPMENT ONLY ({@code dev} profile): stand-in for the SaaS keyring / key service (OpenBao transit in real
 * environments), which does not exist yet. Without it People refuses every P3 field (CCCD, tax code, bank account ...).
 *
 * <p>Keys are DERIVED from {@code yhrm.dev.key-secret} with HMAC-SHA256, so encrypted data stays readable across
 * restarts without storing keys. {@link #destroy} (crypto-shredding, DSR anonymisation) is remembered in
 * {@code <yhrm.dev.storage-dir>/destroyed-subject-keys.txt}. This offers no protection at all; never use it outside a
 * developer laptop.
 */
@Component
@Profile("dev")
public class DevKeys implements SubjectKeys, DataKeys {

    private static final Logger log = LoggerFactory.getLogger(DevKeys.class);
    private static final int VERSION = 1;

    private final byte[] secret;
    private final Path destroyedFile;
    private final Set<String> destroyed = ConcurrentHashMap.newKeySet();

    public DevKeys(@Value("${yhrm.dev.key-secret:yourhrm-local-dev-only-not-a-secret}") String secret,
            @Value("${yhrm.dev.storage-dir:${user.home}/.yourhrm/dev-storage}") Path storageDir) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.destroyedFile = storageDir.resolve("destroyed-subject-keys.txt");
        try {
            Files.createDirectories(storageDir);
            if (Files.exists(destroyedFile)) {
                destroyed.addAll(Files.readAllLines(destroyedFile));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.warn("DEV key service active (derived keys, NO protection) - never use outside local development");
    }

    // ---------------------------------------------------------------- SubjectKeys

    @Override
    public SubjectKey current(UUID tenantId, String subjectType, UUID subjectId) {
        return new SubjectKey(key(tenantId, subjectType, subjectId, VERSION), VERSION);
    }

    @Override
    public byte[] key(UUID tenantId, String subjectType, UUID subjectId, int version) {
        String name = name(tenantId, subjectType, subjectId);
        if (version != VERSION || destroyed.contains(name)) {
            throw new IllegalStateException("Subject key destroyed or unknown");
        }
        return derive("subject/" + name);
    }

    @Override
    public byte[] blindIndexKey(UUID tenantId) {
        return derive("blind-index/" + tenantId);
    }

    @Override
    public void destroy(UUID tenantId, String subjectType, UUID subjectId) {
        String name = name(tenantId, subjectType, subjectId);
        if (destroyed.add(name)) {
            try {
                Files.writeString(destroyedFile, name + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ---------------------------------------------------------------- DataKeys (envelope encryption of documents)

    @Override
    public DataKey generate(UUID tenantId) {
        byte[] key = derive("dek/" + tenantId + "/" + UUID.randomUUID());
        return new DataKey(key.clone(), xor(key, kek(tenantId)), VERSION);
    }

    @Override
    public byte[] unwrap(UUID tenantId, byte[] wrapped, int keyVersion) {
        return xor(wrapped, kek(tenantId));
    }

    // ---------------------------------------------------------------- internals

    private byte[] kek(UUID tenantId) {
        return derive("kek/" + tenantId);
    }

    private static String name(UUID tenantId, String subjectType, UUID subjectId) {
        return tenantId + "/" + subjectType + "/" + subjectId;
    }

    private byte[] derive(String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] xor(byte[] in, byte[] mask) {
        byte[] out = Arrays.copyOf(in, in.length);
        for (int i = 0; i < out.length; i++) {
            out[i] ^= mask[i % mask.length];
        }
        return out;
    }
}
