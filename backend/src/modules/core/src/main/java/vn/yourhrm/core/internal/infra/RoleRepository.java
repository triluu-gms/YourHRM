package vn.yourhrm.core.internal.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.yourhrm.core.internal.domain.Role;
import vn.yourhrm.core.internal.domain.TenantScopedId;

/** Spring Data JPA repository. Every finder takes the tenant id explicitly (app-level guard behind RLS, ADR-0003). */
public interface RoleRepository extends JpaRepository<Role, TenantScopedId> {

    Optional<Role> findByTenantIdAndId(UUID tenantId, UUID id);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}
