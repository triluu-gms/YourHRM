package vn.yourhrm.shared.workflow.api;

import java.util.List;

/**
 * SPI: each module declares its process types as a Spring bean implementing this interface (US-CORE-050). Core
 * registers them into {@code core.process_type} at start-up, idempotently, like the permission catalogue.
 */
public interface ProcessTypeCatalogue {

    /** Owner module code as used in {@code core.process_type.owner_module}: saas, core, hr, att, pay or rec. */
    String module();

    List<ProcessTypeDefinition> processTypes();
}
