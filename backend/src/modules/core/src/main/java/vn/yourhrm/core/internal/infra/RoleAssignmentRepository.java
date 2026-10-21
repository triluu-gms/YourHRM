package vn.yourhrm.core.internal.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vn.yourhrm.core.internal.domain.RoleAssignment;
import vn.yourhrm.core.internal.domain.TenantScopedId;

/** Spring Data JPA repository. Every finder takes the tenant id explicitly (app-level guard behind RLS, ADR-0003). */
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, TenantScopedId> {

    @Query("select a from RoleAssignment a where a.tenantId = ?1 and a.id = ?2")
    Optional<RoleAssignment> findOne(UUID tenantId, UUID id);

    @Query("select a from RoleAssignment a where a.tenantId = ?1 and a.userId = ?2 order by a.validFrom, a.id")
    List<RoleAssignment> findByUser(UUID tenantId, UUID userId);

    @Query("select count(a) > 0 from RoleAssignment a where a.tenantId = ?1 and a.roleId = ?2")
    boolean existsForRole(UUID tenantId, UUID roleId);
}
