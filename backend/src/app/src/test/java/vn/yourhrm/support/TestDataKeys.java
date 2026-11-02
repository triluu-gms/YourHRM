package vn.yourhrm.support;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import vn.yourhrm.shared.common.crypto.DataKeys;

/**
 * Stand-in for the SaaS key service (tests only): the "wrapped" key is the plaintext XOR a per-tenant mask, enough to
 * prove that only the wrapped form is stored and that unwrapping needs the right tenant.
 */
public class TestDataKeys implements DataKeys {

    private final SecureRandom random = new SecureRandom();

    @Override
    public DataKey generate(UUID tenantId) {
        byte[] key = new byte[32];
        random.nextBytes(key);
        return new DataKey(key.clone(), xor(key, tenantId), 1);
    }

    @Override
    public byte[] unwrap(UUID tenantId, byte[] wrapped, int keyVersion) {
        return xor(wrapped, tenantId);
    }

    private static byte[] xor(byte[] in, UUID tenant) {
        byte[] mask = tenant.toString().getBytes();
        byte[] out = Arrays.copyOf(in, in.length);
        for (int i = 0; i < out.length; i++) {
            out[i] ^= mask[i % mask.length];
        }
        return out;
    }
}
