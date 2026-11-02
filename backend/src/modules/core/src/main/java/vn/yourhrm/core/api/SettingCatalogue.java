package vn.yourhrm.core.api;

import java.util.List;

/** SPI: each module declares its tenant settings as a Spring bean (US-CORE-085); Core registers them at start-up. */
public interface SettingCatalogue {

    /** Owner module code: saas, core, hr, att, pay or rec. */
    String module();

    List<SettingDefinition> settings();
}
