package vn.yourhrm.people.internal.infra;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (de)serialisation of People's jsonb columns and event payloads. */
@Component
public class HrJson {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {
    };

    private final JsonMapper json;

    public HrJson(JsonMapper json) {
        this.json = json;
    }

    public String write(Object value) {
        return json.writeValueAsString(value);
    }

    public Map<String, Object> map(String value) {
        return value == null ? Map.of() : json.readValue(value, MAP);
    }

    public List<Map<String, Object>> listOfMaps(String value) {
        return value == null ? List.of() : json.readValue(value, LIST_OF_MAPS);
    }

    public <T> T read(String value, Class<T> type) {
        return value == null ? null : json.readValue(value, type);
    }
}
