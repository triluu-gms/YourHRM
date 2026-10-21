package vn.yourhrm.shared.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Application-generated identifiers: UUIDv7 (RFC 9562), time-ordered (ADR-0023, DESIGN.md §4.1).
 * The SQL fallback {@code sys.uuid_v7()} produces the same layout.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
