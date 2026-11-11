package vn.yourhrm.dev;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.CompanyGroupProvisioning;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * LOCAL DEVELOPMENT ONLY ({@code dev} profile): stand-in for the SaaS {@code TenantProvisioned} consumer, which does
 * not exist yet. On start-up it runs Core's idempotent tenant provisioning for the dev tenant of
 * {@code dev/seed-dev-tenant.sql}, so the system roles (HR_ADMIN, ESS, LINE_MANAGER ...), default org unit types and
 * employee categories exist and pick up permissions registered by newly added modules.
 */
@Component
@Profile("dev")
public class DevTenantProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DevTenantProvisioner.class);

    private final CompanyGroupProvisioning provisioning;
    private final UUID tenantId;

    public DevTenantProvisioner(CompanyGroupProvisioning provisioning,
            @Value("${yhrm.dev.tenant-id:01900000-0000-7000-8000-000000000001}") UUID tenantId) {
        this.provisioning = provisioning;
        this.tenantId = tenantId;
    }

    /** After {@link DevReleaseJob} and the registrars, so the system roles see every registered permission. */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void provisionDevTenant() {
        try {
            TenantContext.runWith(new TenantContext(tenantId, null, null, "dev-provision", "vi-VN"),
                    provisioning::provision);
            log.info("DEV tenant {} provisioned (system roles, default org unit types and categories)", tenantId);
        } catch (RuntimeException e) {
            log.warn("DEV tenant {} could not be provisioned: {}", tenantId, e.getMessage());
        }
    }
}
