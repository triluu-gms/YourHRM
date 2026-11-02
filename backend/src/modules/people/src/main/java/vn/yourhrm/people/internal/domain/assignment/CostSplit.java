package vn.yourhrm.people.internal.domain.assignment;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.yourhrm.people.api.AssignmentEvents.CostShare;

/**
 * Cost-centre split of an assignment (BR-PPL-031, US-PPL-034): every share positive, no cost centre twice, total
 * exactly 100 %. No split means 100 % on the primary cost centre.
 */
public final class CostSplit {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CostSplit() {
    }

    /** The first problem of {@code split}, empty when it is valid (or absent). */
    public static Optional<String> problem(List<CostShare> split) {
        if (split == null || split.isEmpty()) {
            return Optional.empty();
        }
        var seen = new HashSet<UUID>();
        BigDecimal total = BigDecimal.ZERO;
        for (CostShare s : split) {
            if (s.costCentreId() == null || s.pct() == null) {
                return Optional.of("every share needs a cost centre and a percentage");
            }
            if (s.pct().signum() <= 0 || s.pct().compareTo(HUNDRED) > 0) {
                return Optional.of("a share must be greater than 0 and at most 100");
            }
            if (!seen.add(s.costCentreId())) {
                return Optional.of("cost centre " + s.costCentreId() + " appears twice");
            }
            total = total.add(s.pct());
        }
        if (total.compareTo(HUNDRED) != 0) {
            return Optional.of("the shares total " + total.stripTrailingZeros().toPlainString() + " %, not 100 %");
        }
        return Optional.empty();
    }

    /** The primary cost centre of a split: the largest share (the first one on a tie). */
    public static UUID primary(List<CostShare> split) {
        CostShare best = split.getFirst();
        for (CostShare s : split) {
            if (s.pct().compareTo(best.pct()) > 0) {
                best = s;
            }
        }
        return best.costCentreId();
    }
}
