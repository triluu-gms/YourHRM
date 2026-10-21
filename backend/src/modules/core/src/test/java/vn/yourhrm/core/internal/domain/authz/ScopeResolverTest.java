package vn.yourhrm.core.internal.domain.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.core.internal.domain.FieldMaskLevel;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation.AssignmentGrant;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopeType;
import vn.yourhrm.shared.auth.api.spi.ReportingLineProvider;

class ScopeResolverTest {

    static final UUID TENANT = UUID.randomUUID();
    static final LocalDate DAY = LocalDate.of(2026, 6, 30);
    static final UUID A = UUID.randomUUID();
    static final UUID A1 = UUID.randomUUID();
    static final UUID A2 = UUID.randomUUID();
    static final UUID LE = UUID.randomUUID();
    static final UUID LOC = UUID.randomUUID();
    static final UUID ME = UUID.randomUUID();
    static final UUID REPORT = UUID.randomUUID();

    final List<Set<UUID>> subtreeCalls = new ArrayList<>();

    final ScopeResolver.OrgTree tree = (tenant, roots, asOf) -> {
        subtreeCalls.add(roots);
        Set<UUID> out = new HashSet<>(roots);
        if (roots.contains(A) && asOf.equals(DAY)) {
            out.addAll(Set.of(A1, A2));
        }
        return out;
    };

    final ReportingLineProvider reports = new ReportingLineProvider() {
        @Override
        public Set<UUID> directReports(UUID tenantId, UUID manager, LocalDate asOf) {
            return manager.equals(ME) ? Set.of(REPORT) : Set.of();
        }

        @Override
        public Set<UUID> allReports(UUID tenantId, UUID manager, LocalDate asOf) {
            return manager.equals(ME) ? Set.of(REPORT, UUID.fromString("00000000-0000-0000-0000-000000000009")) : Set.of();
        }
    };

    final ScopeResolver resolver = new ScopeResolver(tree, reports);

    static AssignmentGrant grant(ScopeType type, Set<UUID> ids, boolean subtree) {
        return new AssignmentGrant(UUID.randomUUID(), UUID.randomUUID(), "R", "r", "r", type, ids, subtree,
                LocalDate.of(2020, 1, 1), null, Map.of("core.org_unit.view", FieldMaskLevel.NONE));
    }

    @Test
    void noGrantsMeansNothing() {
        assertThat(resolver.resolve(TENANT, List.of(), ME, DAY).isEmpty()).isTrue();
    }

    @Test
    void tenantGrantWinsOverEverything() {
        ScopePredicate p = resolver.resolve(TENANT,
                List.of(grant(ScopeType.LEGAL_ENTITY, Set.of(LE), true), grant(ScopeType.TENANT, Set.of(), true)), ME, DAY);
        assertThat(p.tenantWide()).isTrue();
        assertThat(subtreeCalls).isEmpty();
    }

    @Test
    void orgUnitSubtreeIsExpandedAsOfTheDate() {
        ScopePredicate p = resolver.resolve(TENANT, List.of(grant(ScopeType.ORG_UNIT, Set.of(A), true)), ME, DAY);
        assertThat(p.orgUnitIds()).containsExactlyInAnyOrder(A, A1, A2);
        ScopePredicate later = resolver.resolve(TENANT, List.of(grant(ScopeType.ORG_UNIT, Set.of(A), true)), ME,
                DAY.plusDays(2));
        assertThat(later.orgUnitIds()).containsExactly(A);
    }

    @Test
    void orgUnitWithoutSubtreeIsNotExpanded() {
        ScopePredicate p = resolver.resolve(TENANT, List.of(grant(ScopeType.ORG_UNIT, Set.of(A), false)), ME, DAY);
        assertThat(p.orgUnitIds()).containsExactly(A);
        assertThat(subtreeCalls).isEmpty();
    }

    @Test
    void scopesAreTheUnionOfAllGrants() {
        ScopePredicate p = resolver.resolve(TENANT, List.of(
                grant(ScopeType.LEGAL_ENTITY, Set.of(LE), true),
                grant(ScopeType.LOCATION, Set.of(LOC), true),
                grant(ScopeType.ORG_UNIT, Set.of(A), true),
                grant(ScopeType.SELF, Set.of(), true),
                grant(ScopeType.DIRECT_REPORTS, Set.of(), true)), ME, DAY);
        assertThat(p.tenantWide()).isFalse();
        assertThat(p.legalEntityIds()).containsExactly(LE);
        assertThat(p.locationIds()).containsExactly(LOC);
        assertThat(p.orgUnitIds()).containsExactlyInAnyOrder(A, A1, A2);
        assertThat(p.employmentIds()).containsExactlyInAnyOrder(ME, REPORT);
        assertThat(p.covers(null, A2, null, null)).isTrue();
        assertThat(p.covers(UUID.randomUUID(), UUID.randomUUID(), null, null)).isFalse();
    }

    @Test
    void selfAndReportsNeedAnEmployment() {
        ScopePredicate p = resolver.resolve(TENANT, List.of(grant(ScopeType.SELF, Set.of(), true),
                grant(ScopeType.ALL_REPORTS, Set.of(), true)), null, DAY);
        assertThat(p.isEmpty()).isTrue();
    }

    @Test
    void stubReportingLineGivesNoReports() {
        ScopePredicate p = new ScopeResolver(tree, ReportingLineProvider.NONE)
                .resolve(TENANT, List.of(grant(ScopeType.ALL_REPORTS, Set.of(), true)), ME, DAY);
        assertThat(p.isEmpty()).isTrue();
    }

    @Test
    void snapshotFiltersGrantsByValidityAndPermission() {
        AssignmentGrant expired = new AssignmentGrant(UUID.randomUUID(), UUID.randomUUID(), "R", "r", "r",
                ScopeType.TENANT, Set.of(), true, LocalDate.of(2020, 1, 1), DAY,
                Map.of("core.org_unit.view", FieldMaskLevel.NONE));
        AssignmentGrant future = new AssignmentGrant(UUID.randomUUID(), UUID.randomUUID(), "R", "r", "r",
                ScopeType.TENANT, Set.of(), true, DAY.plusDays(1), null, Map.of("core.org_unit.view", FieldMaskLevel.NONE));
        AssignmentGrant other = grant(ScopeType.TENANT, Set.of(), true);
        EffectiveAuthorisation snapshot = new EffectiveAuthorisation(TENANT, UUID.randomUUID(), null,
                List.of(expired, future, other));
        assertThat(snapshot.grantsFor("core.org_unit.view", DAY)).containsExactly(other);
        assertThat(snapshot.grantsFor("core.org_unit.view", DAY.minusDays(1))).containsExactly(expired, other);
        assertThat(snapshot.grantsFor("core.user.view", DAY)).isEmpty();
    }
}
