package vn.yourhrm.core.internal.infra;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.yourhrm.shared.workflow.api.StepTemplate;

/** JSON (de)serialisation of Core's jsonb columns (workflow steps and snapshots, setting values, release content). */
@Component
public class CoreJson {

    private static final TypeReference<List<StepTemplate>> STEPS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST_OF_STRINGS = new TypeReference<>() {
    };

    private final JsonMapper json;

    public CoreJson(JsonMapper json) {
        this.json = json;
    }

    public String write(Object value) {
        return json.writeValueAsString(value);
    }

    /** Any JSON value as a plain Java value (Boolean, Number, String, Map, List or null). */
    public Object value(String value) {
        return value == null ? null : json.readValue(value, Object.class);
    }

    public List<StepTemplate> steps(String value) {
        return json.readValue(value, STEPS);
    }

    public Map<String, Object> map(String value) {
        return value == null ? Map.of() : json.readValue(value, MAP);
    }

    public List<String> listOfStrings(String value) {
        return value == null ? List.of() : json.readValue(value, LIST_OF_STRINGS);
    }

    public List<Map<String, Object>> listOfMaps(String value) {
        return value == null ? List.of() : json.readValue(value, LIST_OF_MAPS);
    }
}
