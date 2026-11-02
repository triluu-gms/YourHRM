package vn.yourhrm.core.internal.application;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.core.api.ImportType.Column;
import vn.yourhrm.core.api.ImportType.Definition;
import vn.yourhrm.core.internal.infra.ImportWorkbooks;

/**
 * The import types of all modules (BR-CORE-110, US-CORE-110), checked at start-up: unique codes and column keys,
 * business keys among the columns, known lookups, the type's permission in its module's namespace, dependencies on
 * registered opening-balance types.
 */
@Component
public class ImportTypeRegistry {

    private static final Set<String> LOOKUPS = Set.of("LEGAL_ENTITY", "LOCATION", "ORG_UNIT", "ORG_UNIT_TYPE");

    private final Map<String, ImportType> types = new HashMap<>();

    public ImportTypeRegistry(List<ImportType> importTypes) {
        for (ImportType t : importTypes) {
            Definition d = t.definition();
            if (!AuditLogService.MODULES.contains(d.ownerModule())) {
                throw new IllegalStateException("Unknown owner module " + d.ownerModule() + " of import " + d.code());
            }
            if (!d.permission().startsWith(d.ownerModule() + ".")) {
                throw new IllegalStateException("Import " + d.code() + " must use a permission of " + d.ownerModule());
            }
            Set<String> keys = new HashSet<>();
            for (Column c : ImportWorkbooks.withExternalRef(d.columns())) {
                if (!keys.add(c.key())) {
                    throw new IllegalStateException("Duplicate column " + c.key() + " in import " + d.code());
                }
                if (c.lookup() != null && !LOOKUPS.contains(c.lookup()) && !c.lookup().startsWith("CODE_LIST:")) {
                    throw new IllegalStateException("Unknown lookup " + c.lookup() + " in import " + d.code());
                }
            }
            if (d.businessKey().isEmpty() || !keys.containsAll(d.businessKey())) {
                throw new IllegalStateException("The business key of import " + d.code() + " must name its columns");
            }
            if (types.put(d.code(), t) != null) {
                throw new IllegalStateException("Duplicate import type " + d.code());
            }
        }
        for (ImportType t : types.values()) {
            for (String dep : t.definition().dependsOn()) {
                if (!types.containsKey(dep) || !types.get(dep).definition().openingBalance()) {
                    throw new IllegalStateException("Import " + t.definition().code() + " depends on unknown "
                            + "opening-balance type " + dep);
                }
            }
        }
    }

    public Optional<ImportType> find(String code) {
        return Optional.ofNullable(types.get(code));
    }

    public List<ImportType> all() {
        return types.values().stream().sorted(Comparator.comparing(t -> t.definition().code())).toList();
    }
}
