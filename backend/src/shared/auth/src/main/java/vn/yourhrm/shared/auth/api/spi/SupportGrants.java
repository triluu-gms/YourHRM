package vn.yourhrm.shared.auth.api.spi;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SPI for platform support grants ({@code saas.support_grant}, BR-SAAS-034/035), implemented by the SaaS module,
 * which alone reads that table. Core asks it on every request of an operator token (BR-CORE-041). Without an
 * implementation every support session is refused (fail closed).
 */
public interface SupportGrants {

    /** The grant {@code grantId} of {@code tenantId}, whatever its status. */
    Optional<Grant> find(UUID tenantId, UUID grantId);

    /**
     * @param status            REQUESTED | APPROVED | REJECTED | ACTIVE | EXPIRED | REVOKED
     * @param modules           module codes covered (core, ppl, att, pay, rec, saas)
     * @param readWrite         access mode READ_WRITE (otherwise READ)
     * @param includesSensitive unmask sensitive data
     * @param approvedBy        tenant user who approved, null while requested
     */
    record Grant(UUID id, UUID tenantId, UUID operatorId, String ticketRef, Set<String> modules, boolean readWrite,
            boolean includesSensitive, String status, UUID approvedBy, Instant validFrom, Instant validTo) {

        public Grant {
            modules = Set.copyOf(modules);
        }

        /** Usable now: approved by the tenant and inside its window. */
        public boolean usableAt(Instant now) {
            return ("APPROVED".equals(status) || "ACTIVE".equals(status)) && approvedBy != null
                    && !now.isBefore(validFrom) && now.isBefore(validTo);
        }
    }
}
