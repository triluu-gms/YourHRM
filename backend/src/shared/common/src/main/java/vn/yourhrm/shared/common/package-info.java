/**
 * Shared kernel (common): tenant context, identifiers, API errors, business time and the
 * tenant-aware transaction manager. Open module: every module may use all of it.
 */
@ApplicationModule(displayName = "Shared kernel: common", type = ApplicationModule.Type.OPEN)
package vn.yourhrm.shared.common;

import org.springframework.modulith.ApplicationModule;
