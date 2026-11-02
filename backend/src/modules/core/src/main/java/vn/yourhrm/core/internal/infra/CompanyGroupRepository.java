package vn.yourhrm.core.internal.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.yourhrm.core.internal.domain.CompanyGroup;
import vn.yourhrm.core.internal.domain.TenantScopedId;

/** Spring Data JPA repository. Every finder takes the tenant id explicitly (app-level guard behind RLS, ADR-0003). */
public interface CompanyGroupRepository extends JpaRepository<CompanyGroup, TenantScopedId> {

    Optional<CompanyGroup> findByTenantId(UUID tenantId);
}
