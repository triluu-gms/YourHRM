package vn.yourhrm.core.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A typed tenant setting a module registers (BR-CORE-084, US-CORE-085; global table {@code core.setting_definition}).
 *
 * @param key            {@code <module>.<name>}, e.g. {@code core.auto_create_user}
 * @param valueType      BOOL, INT, DECIMAL, TEXT, ENUM, DATE or JSON
 * @param allowedValues  for ENUM
 * @param defaultValue   used when no tenant value applies (as a JSON-compatible Java value)
 * @param allowedLevels  TENANT, LEGAL_ENTITY, LOCATION: where values may be set; resolution takes the most specific
 * @param effectiveDated values carry a start date and keep their history
 */
public record SettingDefinition(String key, String valueType, List<String> allowedValues, Object defaultValue,
        Set<String> allowedLevels, boolean effectiveDated, boolean sensitive, String descriptionVi,
        String descriptionEn) {

    public SettingDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(defaultValue, "defaultValue");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        allowedLevels = allowedLevels == null || allowedLevels.isEmpty() ? Set.of("TENANT") : Set.copyOf(allowedLevels);
    }

    public String module() {
        return key.substring(0, key.indexOf('.'));
    }
}
