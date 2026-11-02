package vn.yourhrm.core.api;

import java.util.List;

/**
 * SPI: each module declares the notification templates of its event codes as a Spring bean (US-CORE-070). Core
 * validates them at start-up (a template that uses an undeclared placeholder, or a sensitive / money placeholder,
 * fails the start) and registers them as global templates.
 */
public interface NotificationTemplateCatalogue {

    /** Owner module code: saas, core, hr, att, pay or rec; event codes start with it. */
    String module();

    List<NotificationTemplateDefinition> templates();
}
