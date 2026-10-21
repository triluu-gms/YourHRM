/**
 * Shared auth library (architecture-overview §15, ADR-0005/0006): JWT resource-server configuration,
 * {@code @RequiresPermission} / {@code @PublicEndpoint}, the {@code AuthorizationService} API, the
 * {@code ScopePredicate} and the {@code ReportingLineProvider} SPI. Decisions are made by Core, which
 * implements {@code AuthorizationService} and {@code UserAccountResolver}.
 */
@ApplicationModule(displayName = "Shared kernel: auth", type = ApplicationModule.Type.OPEN)
package vn.yourhrm.shared.auth;

import org.springframework.modulith.ApplicationModule;
