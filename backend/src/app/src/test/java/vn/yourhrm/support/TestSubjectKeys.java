package vn.yourhrm.support;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.yourhrm.shared.common.crypto.SubjectKeys;

/** Stand-in for the SaaS keyring (tests only): random per-subject keys and per-tenant blind-index keys in memory. */
public class TestSubjectKeys implements SubjectKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, byte[]> KEYS = new ConcurrentHashMap<>();
    private static final Map<UUID, byte[]> BLIND_INDEX_KEYS = new ConcurrentHashMap<>();

    @Override
    public SubjectKey current(UUID tenantId, String subjectType, UUID subjectId) {
        return new SubjectKey(KEYS.computeIfAbsent(name(tenantId, subjectType, subjectId), n -> random()).clone(), 1);
    }

    @Override
    public byte[] key(UUID tenantId, String subjectType, UUID subjectId, int version) {
        byte[] key = KEYS.get(name(tenantId, subjectType, subjectId));
        if (key == null || version != 1) {
            throw new IllegalStateException("Subject key destroyed or unknown");
        }
        return key.clone();
    }

    @Override
    public byte[] blindIndexKey(UUID tenantId) {
        return BLIND_INDEX_KEYS.computeIfAbsent(tenantId, t -> random()).clone();
    }

    @Override
    public void destroy(UUID tenantId, String subjectType, UUID subjectId) {
        KEYS.remove(name(tenantId, subjectType, subjectId));
    }

    /** Was the subject's key destroyed (crypto-shredded)? */
    public static boolean isDestroyed(UUID tenantId, String subjectType, UUID subjectId) {
        return !KEYS.containsKey(name(tenantId, subjectType, subjectId));
    }

    private static String name(UUID tenantId, String subjectType, UUID subjectId) {
        return tenantId + "/" + subjectType + "/" + subjectId;
    }

    private static byte[] random() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }
}
