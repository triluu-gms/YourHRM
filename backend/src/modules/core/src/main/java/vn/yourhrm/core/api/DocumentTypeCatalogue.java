package vn.yourhrm.core.api;

import java.util.List;

/** A module's document types (US-CORE-101); every catalogue bean is registered at start-up. */
public interface DocumentTypeCatalogue {

    String module();

    List<DocumentTypeDefinition> documentTypes();
}
