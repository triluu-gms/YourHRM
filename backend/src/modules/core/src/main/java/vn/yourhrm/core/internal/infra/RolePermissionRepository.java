package vn.yourhrm.core.internal.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.yourhrm.core.internal.domain.RolePermission;

/** Spring Data JPA repository. Every finder takes the tenant id explicitly (app-level guard behind RLS, ADR-0003). */
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {

    List<RolePermission> findByTenantIdAndRoleId(UUID tenantId, UUID roleId);
}
