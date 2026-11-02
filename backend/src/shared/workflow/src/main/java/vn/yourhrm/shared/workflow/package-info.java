/**
 * Shared workflow client library (ADR-0007): the process-type catalogue SPI that modules implement, the
 * {@code WorkflowService} API they call to start approvals and acknowledge outcomes, and the lifecycle events they
 * consume. The table-driven engine is implemented by Core. Open module: every module may use all of it.
 */
@ApplicationModule(displayName = "Shared kernel: workflow", type = ApplicationModule.Type.OPEN)
package vn.yourhrm.shared.workflow;

import org.springframework.modulith.ApplicationModule;
