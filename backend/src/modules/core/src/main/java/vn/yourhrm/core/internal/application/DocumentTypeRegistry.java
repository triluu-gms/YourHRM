package vn.yourhrm.core.internal.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;

/**
 * The document types of all modules (US-CORE-101) and the owner-record resolvers (BR-CORE-101), validated at
 * start-up: unique codes, known owner modules, permissions of the owning module, one resolver per owner entity type.
 */
@Component
public class DocumentTypeRegistry {

    private final Map<String, DocumentTypeDefinition> types = new HashMap<>();
    private final Map<String, DocumentOwnerResolver> resolvers = new HashMap<>();

    public DocumentTypeRegistry(List<DocumentTypeCatalogue> catalogues, List<DocumentOwnerResolver> ownerResolvers) {
        for (DocumentTypeCatalogue c : catalogues) {
            if (!AuditLogService.MODULES.contains(c.module())) {
                throw new IllegalStateException("Unknown owner module " + c.module() + " in " + c.getClass());
            }
            for (DocumentTypeDefinition d : c.documentTypes()) {
                if (!d.ownerModule().equals(c.module())) {
                    throw new IllegalStateException("Document type " + d.code() + " is declared by " + c.module());
                }
                if (types.put(d.code(), d) != null) {
                    throw new IllegalStateException("Duplicate document type " + d.code());
                }
            }
        }
        for (DocumentOwnerResolver r : ownerResolvers) {
            for (String t : r.ownerEntityTypes()) {
                if (resolvers.put(t, r) != null) {
                    throw new IllegalStateException("Two owner resolvers for " + t);
                }
            }
        }
    }

    public Optional<DocumentTypeDefinition> find(String code) {
        return Optional.ofNullable(types.get(code));
    }

    public List<DocumentTypeDefinition> all() {
        return types.values().stream().sorted(java.util.Comparator.comparing(DocumentTypeDefinition::code)).toList();
    }

    public Optional<DocumentOwnerResolver> resolver(String ownerEntityType) {
        return Optional.ofNullable(resolvers.get(ownerEntityType));
    }

    public Set<String> codes() {
        return Set.copyOf(types.keySet());
    }
}
