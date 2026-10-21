package vn.yourhrm.core.internal.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vn.yourhrm.core.internal.domain.TenantScopedId;
import vn.yourhrm.core.internal.domain.UserAccount;

/** Spring Data JPA repository. Every finder takes the tenant id explicitly (app-level guard behind RLS, ADR-0003). */
public interface UserAccountRepository extends JpaRepository<UserAccount, TenantScopedId> {

    Optional<UserAccount> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<UserAccount> findByTenantIdAndIdentityId(UUID tenantId, UUID identityId);

    @Query("select count(u) > 0 from UserAccount u where u.tenantId = ?1 and lower(u.login) = lower(?2)")
    boolean existsLogin(UUID tenantId, String login);
}
