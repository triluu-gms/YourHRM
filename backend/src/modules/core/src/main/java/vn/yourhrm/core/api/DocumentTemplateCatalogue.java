package vn.yourhrm.core.api;

import java.util.List;

/** A module's generated documents and their merge fields (BR-CORE-102); registered at start-up. */
public interface DocumentTemplateCatalogue {

    String module();

    List<DocumentTemplateDefinition> templates();
}
