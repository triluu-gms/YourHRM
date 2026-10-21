package vn.yourhrm.shared.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.NoTenantContextException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

class TenantContextTest {

    @Test
    void contextIsBoundOnlyInsideTheScope() throws Exception {
        UUID tenant = UUID.randomUUID();
        assertThat(TenantContext.isBound()).isFalse();
        String inside = TenantContext.callWith(new TenantContext(tenant, null, "sub", "r", null),
                () -> TenantContext.current().tenantId().toString());
        assertThat(inside).isEqualTo(tenant.toString());
        assertThat(TenantContext.isBound()).isFalse();
        assertThatThrownBy(TenantContext::current).isInstanceOf(NoTenantContextException.class);
    }

    @Test
    void cacheKeysArePrefixedWithTheTenant() {
        UUID tenant = UUID.randomUUID();
        assertThat(new TenantContext(tenant, null, null, null, null).cachePrefix()).isEqualTo("t:" + tenant + ":");
    }

    @Test
    void idsAreVersion7AndTimeOrdered() {
        Set<UUID> seen = new HashSet<>();
        UUID previous = Ids.newId();
        for (int i = 0; i < 1000; i++) {
            UUID id = Ids.newId();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
            assertThat(id.getMostSignificantBits() >>> 16).isGreaterThanOrEqualTo(previous.getMostSignificantBits() >>> 16);
            assertThat(seen.add(id)).isTrue();
            previous = id;
        }
    }
}
