package vn.yourhrm.shared.auth.api;

import java.time.LocalDate;

/**
 * The single authorisation service (US-CORE-037, ADR-0006). Implemented by Core; every module asks it,
 * never re-implements the rules. All methods evaluate the CURRENT user of the bound tenant context and
 * deny by default.
 *
 * <p>Role assignments are evaluated for validity at today's business date (access is granted "now");
 * organisational structure (org-unit subtrees, unit -> legal entity / location) is evaluated as of the
 * requested date, so a reorganisation changes the answer for dates after it.
 */
public interface AuthorizationService {

    /** Holds {@code permission} with any scope through an assignment valid today. */
    boolean hasPermission(String permission);

    /** Holds {@code permission} with scope TENANT through an assignment valid today. */
    boolean hasTenantWidePermission(String permission);

    /** May the current user exercise {@code permission} on {@code target} (structure as of today)? */
    boolean can(String permission, AuthorizationTarget target);

    /** May the current user exercise {@code permission} on {@code target} with structure as of {@code asOf}? */
    boolean can(String permission, AuthorizationTarget target, LocalDate asOf);

    /** The data scope of {@code permission}, ready to filter SQL ({@link ScopePredicate#toSql}). */
    ScopePredicate scopeFor(String permission, LocalDate asOf);
}
