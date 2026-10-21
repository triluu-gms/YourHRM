package vn.yourhrm.shared.common.tenancy;

import java.util.concurrent.Callable;

/**
 * Explicit marker for platform work that is NOT tenant-scoped (e.g. registering the global permission
 * catalogue at start-up). Transactions started inside {@link #call} carry an EMPTY tenant context, so RLS
 * keeps every tenant table invisible (fail closed); only global tables (no RLS) are usable.
 */
public final class PlatformContext {

    private static final ScopedValue<String> PURPOSE = ScopedValue.newInstance();

    private PlatformContext() {
    }

    public static <T> T call(String purpose, Callable<T> op) throws Exception {
        return ScopedValue.where(PURPOSE, purpose).call(op::call);
    }

    public static boolean isBound() {
        return PURPOSE.isBound();
    }

    public static String purpose() {
        return PURPOSE.isBound() ? PURPOSE.get() : null;
    }
}
