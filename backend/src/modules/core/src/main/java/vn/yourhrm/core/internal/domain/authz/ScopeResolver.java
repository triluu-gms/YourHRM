package vn.yourhrm.core.internal.domain.authz;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation.AssignmentGrant;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.spi.ReportingLineProvider;

/**
 * Pure data-scope resolution (US-CORE-035/037, ADR-0006 decision 3). Effective scope = UNION of the
 * grants. No Spring, no SQL: the organisation tree and the reporting line are ports.
 */
public final class ScopeResolver {

    /** Read access to the as-of organisation tree (core.org_unit_version.path). */
    public interface OrgTree {
        /** All org units in the subtrees of {@code roots} (roots included) as of {@code asOf}. */
        Set<UUID> subtreeOf(UUID tenantId, Set<UUID> roots, LocalDate asOf);
    }

    private final OrgTree orgTree;
    private final ReportingLineProvider reportingLines;

    public ScopeResolver(OrgTree orgTree, ReportingLineProvider reportingLines) {
        this.orgTree = orgTree;
        this.reportingLines = reportingLines;
    }

    /**
     * @param tenantId         tenant
     * @param grants           grants of ONE permission, already filtered to the assignments valid today
     * @param selfEmploymentId the user's own employment, {@code null} for non-employee users
     * @param asOf             date for the organisation structure / reporting line
     */
    public ScopePredicate resolve(UUID tenantId, Collection<AssignmentGrant> grants, UUID selfEmploymentId,
            LocalDate asOf) {
        if (grants.isEmpty()) {
            return ScopePredicate.none();
        }
        Set<UUID> legalEntities = new HashSet<>();
        Set<UUID> locations = new HashSet<>();
        Set<UUID> orgUnits = new HashSet<>();
        Set<UUID> subtreeRoots = new HashSet<>();
        Set<UUID> employments = new HashSet<>();
        for (AssignmentGrant g : grants) {
            switch (g.scopeType()) {
                case TENANT -> {
                    return ScopePredicate.tenant();
                }
                case LEGAL_ENTITY -> legalEntities.addAll(g.scopeIds());
                case LOCATION -> locations.addAll(g.scopeIds());
                case ORG_UNIT -> {
                    if (g.includeSubtree()) {
                        subtreeRoots.addAll(g.scopeIds());
                    } else {
                        orgUnits.addAll(g.scopeIds());
                    }
                }
                case SELF -> {
                    if (selfEmploymentId != null) {
                        employments.add(selfEmploymentId);
                    }
                }
                case DIRECT_REPORTS -> {
                    if (selfEmploymentId != null) {
                        employments.addAll(reportingLines.directReports(tenantId, selfEmploymentId, asOf));
                    }
                }
                case ALL_REPORTS -> {
                    if (selfEmploymentId != null) {
                        employments.addAll(reportingLines.allReports(tenantId, selfEmploymentId, asOf));
                    }
                }
            }
        }
        if (!subtreeRoots.isEmpty()) {
            orgUnits.addAll(orgTree.subtreeOf(tenantId, subtreeRoots, asOf));
        }
        return new ScopePredicate(false, legalEntities, orgUnits, locations, employments);
    }
}
