package vn.yourhrm.attendance.internal.application.report;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a report (BRD 03 §8): the rows, the requested window and whether more rows follow. Stores fetch
 * {@code limit + 1} rows; the extra one only tells that the next page is not empty.
 */
public record ReportPage<T>(List<T> items, int limit, int offset, boolean hasMore) {

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 2000;

    static int limit(Integer requested) {
        return Math.clamp(requested == null ? DEFAULT_LIMIT : requested, 1, MAX_LIMIT);
    }

    static int offset(Integer requested) {
        return Math.max(0, requested == null ? 0 : requested);
    }

    /** Builds the page from {@code limit + 1} fetched rows. */
    static <T> ReportPage<T> of(List<T> fetched, int limit, int offset) {
        boolean more = fetched.size() > limit;
        return new ReportPage<>(List.copyOf(more ? fetched.subList(0, limit) : fetched), limit, offset, more);
    }

    <R> ReportPage<R> map(Function<T, R> f) {
        return new ReportPage<>(items.stream().map(f).toList(), limit, offset, hasMore);
    }
}
