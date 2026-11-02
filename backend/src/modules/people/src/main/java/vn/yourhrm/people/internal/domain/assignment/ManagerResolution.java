package vn.yourhrm.people.internal.domain.assignment;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Line manager derived from the position hierarchy (BR-PPL-031, US-PPL-034): the incumbent, on the date, of the
 * position the employee's position reports to; when that position is vacant, the next level up, and so on to the top.
 * Pure: the hierarchy and the incumbency are ports.
 */
public final class ManagerResolution {

    /** Upper bound on the walk (guards against a cyclic hierarchy in bad data). */
    static final int MAX_LEVELS = 50;

    private ManagerResolution() {
    }

    /**
     * @param positionId        the employee's position
     * @param reportsTo         position → the position it reports to (empty at the top / unknown)
     * @param incumbent         position → its incumbent employment on the date (empty when vacant)
     * @param selfEmploymentId  the employee, never their own manager
     */
    public static Optional<UUID> fromPosition(UUID positionId, Function<UUID, Optional<UUID>> reportsTo,
            Function<UUID, Optional<UUID>> incumbent, UUID selfEmploymentId) {
        var visited = new HashSet<UUID>();
        visited.add(positionId);
        Optional<UUID> next = reportsTo.apply(positionId);
        for (int level = 0; next.isPresent() && level < MAX_LEVELS; level++) {
            UUID managerPosition = next.get();
            if (!visited.add(managerPosition)) {
                return Optional.empty();
            }
            Optional<UUID> holder = incumbent.apply(managerPosition).filter(e -> !e.equals(selfEmploymentId));
            if (holder.isPresent()) {
                return holder;
            }
            next = reportsTo.apply(managerPosition);
        }
        return Optional.empty();
    }
}
