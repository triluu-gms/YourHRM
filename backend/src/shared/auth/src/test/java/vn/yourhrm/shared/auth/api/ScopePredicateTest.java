package vn.yourhrm.shared.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.shared.auth.api.ScopePredicate.Columns;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;

class ScopePredicateTest {

    static final Columns ALL = new Columns("e.legal_entity_id", "e.org_unit_id", "e.location_id", "e.employment_id");

    @Test
    void tenantWideIsTrue() {
        assertThat(ScopePredicate.tenant().toSql(ALL, "s").sql()).isEqualTo("(true)");
    }

    @Test
    void emptyScopeIsFalse() {
        assertThat(ScopePredicate.none().toSql(ALL, "s").sql()).isEqualTo("(false)");
        assertThat(ScopePredicate.none().isEmpty()).isTrue();
    }

    @Test
    void dimensionsAreOrCombinedWithNamedParameters() {
        UUID le = UUID.randomUUID();
        UUID ou = UUID.randomUUID();
        SqlFragment f = new ScopePredicate(false, Set.of(le), Set.of(ou), Set.of(), Set.of()).toSql(ALL, "s");
        assertThat(f.sql()).isEqualTo("(e.legal_entity_id in (:s_le) or e.org_unit_id in (:s_ou))");
        assertThat(f.params()).containsEntry("s_le", List.of(le)).containsEntry("s_ou", List.of(ou));
    }

    @Test
    void dimensionsWithoutAColumnGrantNothing() {
        ScopePredicate p = new ScopePredicate(false, Set.of(), Set.of(), Set.of(UUID.randomUUID()), Set.of());
        assertThat(p.toSql(new Columns("le", "ou", null, null), "x").sql()).isEqualTo("(false)");
    }

    @Test
    void coversMatchesAnyDimension() {
        UUID loc = UUID.randomUUID();
        ScopePredicate p = new ScopePredicate(false, Set.of(), Set.of(), Set.of(loc), Set.of());
        assertThat(p.covers(null, null, loc, null)).isTrue();
        assertThat(p.covers(UUID.randomUUID(), null, null, null)).isFalse();
        assertThat(p.covers(null, null, null, null)).isFalse();
    }

    @Test
    void permissionCodesFollowTheDdlPattern() {
        assertThat(PermissionDefinition.of("core.role.view", PermissionAction.VIEW, "v", "e").functionCode())
                .isEqualTo("core.role");
        assertThatThrownBy(() -> PermissionDefinition.of("Core.Role", PermissionAction.VIEW, "v", "e"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
