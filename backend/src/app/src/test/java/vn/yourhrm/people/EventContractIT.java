package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import vn.yourhrm.people.api.PeopleEvent;
import vn.yourhrm.people.internal.infra.HrJson;

/**
 * Versioned event contracts of People (US-PPL-079 / US-PPL-080, NFR-PPL-002, D7): every {@link PeopleEvent} record in
 * {@code vn.yourhrm.people.api} has a JSON Schema {@code events/people/<EventType>.v<schemaVersion>.schema.json}; a
 * serialised sample payload (all fields set, and all nullable fields null) validates against it (a tiny validator for
 * the subset the schemas use, no extra dependency); no payload property name hints at a P3 value.
 *
 * <p>To (re)generate the schema files after a deliberate payload change, run this test once with the environment
 * variable {@code YHRM_WRITE_EVENT_SCHEMAS} set to the People module's {@code src/main/resources} directory, then
 * review the diff (a breaking change needs {@code schemaVersion} + 1 and a new file).
 */
class EventContractIT extends EmploymentTestSupport {

    /** Property names that would carry a P3 value (national id, tax code, SI / HI number, bank account, health). */
    static final Pattern P3_NAME = Pattern.compile(
            "(?i).*(nationalid|idnumber|identitynumber|cccd|passportno|taxcode|sinumber|sibook|hicard"
                    + "|accountno|accountnumber|iban|cardno|health|medical|diagnos|religion|ethnic|personalemail"
                    + "|phone|addressline).*");

    @Autowired
    HrJson json;

    // ------------------------------------------------------------------------------------------------ catalogue

    static List<Class<?>> eventRecords() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition d) {
                return d.getMetadata().isIndependent() && d.getMetadata().isConcrete();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(PeopleEvent.class));
        List<Class<?>> out = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("vn.yourhrm.people.api")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (c.isRecord()) {
                out.add(c);
            }
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    static String fileName(PeopleEvent e) {
        return e.eventType().substring("people.".length()) + ".v" + e.schemaVersion() + ".schema.json";
    }

    @Test
    void everyPeopleEventHasAVersionedSchemaAndItsSamplePayloadsValidate() throws Exception {
        List<Class<?>> records = eventRecords();
        assertThat(records).as("People event records").hasSizeGreaterThanOrEqualTo(20);
        String env = System.getenv("YHRM_WRITE_EVENT_SCHEMAS");
        String writeTo = env == null || env.isBlank() ? null : env;
        Map<String, List<String>> problems = new TreeMap<>();
        Map<String, Class<?>> generatedBy = new LinkedHashMap<>();
        Map<String, List<Class<?>>> byFile = new LinkedHashMap<>();
        for (Class<?> type : records) {
            byFile.computeIfAbsent(fileName((PeopleEvent) sample(type, false)), k -> new ArrayList<>()).add(type);
        }
        for (Class<?> type : records) {
            PeopleEvent full = (PeopleEvent) sample(type, false);
            PeopleEvent sparse = (PeopleEvent) sample(type, true);
            assertThat(full.eventType()).as(type.getName()).matches("^people\\.[A-Z][A-Za-z]+$");
            String file = fileName(full);
            if (writeTo != null && !generatedBy.containsKey(file)) {
                Path p = Path.of(writeTo, "events", "people", file);
                Files.createDirectories(p.getParent());
                Files.writeString(p, tools.jackson.databind.json.JsonMapper.builder()
                        .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT).build()
                        .writeValueAsString(schemaOf(byFile.get(file), full)) + "\n", StandardCharsets.UTF_8);
                generatedBy.put(file, type);
            }
            Map<String, Object> schema = loadSchema(writeTo, file);
            if (schema == null) {
                problems.computeIfAbsent(type.getName(), k -> new ArrayList<>()).add("no schema file " + file);
                continue;
            }
            assertThat(schema.get("x-eventType")).as(file).isEqualTo(full.eventType());
            for (PeopleEvent e : List.of(full, sparse)) {
                Object payload = json.map(json.write(e));
                List<String> errors = new ArrayList<>();
                validate(schema, payload, "$", errors);
                if (!errors.isEmpty()) {
                    problems.computeIfAbsent(type.getName(), k -> new ArrayList<>()).addAll(errors);
                }
                List<String> p3 = new ArrayList<>();
                p3Names(payload, "$", p3);
                if (!p3.isEmpty()) {
                    problems.computeIfAbsent(type.getName(), k -> new ArrayList<>()).add("P3-like names " + p3);
                }
            }
        }
        assertThat(problems).as("event contract problems").isEmpty();
    }

    @Test
    void theValidatorRejectsUnknownMissingAndMistypedProperties() {
        Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("a", "b"),
                "properties", Map.of("a", Map.of("type", "string", "format", "uuid"),
                        "b", Map.of("type", List.of("array", "null"), "items", Map.of("type", "integer"))));
        List<String> errors = new ArrayList<>();
        validate(schema, Map.of("a", "not-a-uuid", "b", List.of(1, "x"), "c", true), "$", errors);
        assertThat(errors).hasSize(3);
        errors.clear();
        validate(schema, Map.of("b", List.of()), "$", errors);
        assertThat(errors).containsExactly("$.a: required");
        List<String> p3 = new ArrayList<>();
        p3Names(Map.of("nationalId", "1", "accountNoLast4", "1234", "siSalaryAmount", 1), "$", p3);
        assertThat(p3).containsExactly("$.nationalId");
    }

    // ------------------------------------------------------------------------------------------------ samples

    /** A record instance with a sample for every component ({@code sparse}: every nullable component null). */
    static Object sample(Class<?> type, boolean sparse) throws Exception {
        RecordComponent[] comps = type.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            Class<?> ct = comps[i].getType();
            if (sparse && List.class.isAssignableFrom(ct)) {
                args[i] = List.of();
            } else if (sparse && Map.class.isAssignableFrom(ct)) {
                args[i] = Map.of();
            } else {
                args[i] = sparse && !ct.isPrimitive() && !isRequired(type, comps[i].getName()) ? null
                        : value(comps[i].getGenericType(), comps[i].getName(), sparse);
            }
        }
        Constructor<?> c = type.getDeclaredConstructor(types);
        c.setAccessible(true);
        return c.newInstance(args);
    }

    /** Envelope fields every payload carries (the outbox needs them). */
    static boolean isRequired(Class<?> type, String name) {
        return PeopleEvent.class.isAssignableFrom(type) && List.of("tenantId", "eventType").contains(name);
    }

    static Object value(Type t, String name, boolean sparse) throws Exception {
        Class<?> raw = t instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) t;
        if (raw == UUID.class) {
            return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        } else if (raw == String.class) {
            return "S_" + name.toUpperCase(Locale.ROOT);
        } else if (raw == LocalDate.class) {
            return LocalDate.of(2026, 3, 1);
        } else if (raw == Instant.class) {
            return Instant.parse("2026-03-01T03:04:05Z");
        } else if (raw == boolean.class || raw == Boolean.class) {
            return true;
        } else if (raw == int.class || raw == Integer.class || raw == short.class || raw == Short.class) {
            return 3;
        } else if (raw == long.class || raw == Long.class) {
            return 7L;
        } else if (raw == BigDecimal.class) {
            return new BigDecimal("1234.5");
        } else if (List.class.isAssignableFrom(raw)) {
            Type item = ((ParameterizedType) t).getActualTypeArguments()[0];
            return List.of(value(item, name, sparse));
        } else if (Map.class.isAssignableFrom(raw)) {
            Type v = ((ParameterizedType) t).getActualTypeArguments()[1];
            return Map.of("k", value(v, name, sparse));
        } else if (raw.isRecord()) {
            return sample(raw, sparse);
        }
        throw new IllegalStateException("No sample for " + t + " (" + name + ")");
    }

    // ------------------------------------------------------------------------------------------------ schemas

    /**
     * The schema of an event type; several records may publish the same type (e.g. {@code EmployeeHired} and the
     * import's {@code InitialEmployeeHired}): properties are their union, required ones those common to all.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> schemaOf(List<Class<?>> types, PeopleEvent sample) {
        Map<String, Object> s = schemaOf(types.getFirst(), sample);
        if (types.size() > 1) {
            Map<String, Object> props = (Map<String, Object>) s.get("properties");
            List<String> required = new ArrayList<>((List<String>) s.get("required"));
            for (Class<?> t : types.subList(1, types.size())) {
                Map<String, Object> other = objectSchema(t, false);
                ((Map<String, Object>) other.get("properties")).forEach(props::putIfAbsent);
                required.retainAll((List<String>) other.get("required"));
            }
            s.put("required", required);
            s.put("description", s.get("description") + " Also published by "
                    + types.subList(1, types.size()).stream().map(Class::getSimpleName).toList() + ".");
        }
        return s;
    }

    static Map<String, Object> schemaOf(Class<?> type, PeopleEvent sample) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        s.put("$id", "https://schemas.yourhrm.vn/events/people/" + fileName(sample));
        s.put("title", sample.eventType() + " v" + sample.schemaVersion());
        s.put("description", "Payload of " + sample.eventType() + " (People outbox, NFR-PPL-002). The envelope "
                + "(event_id, event_type, schema_version, tenant_id, legal_entity_id, occurred_at, effective_date, "
                + "producer, correlation_id, causation_id, retro, person_id, employment_id, initial_load header) is "
                + "carried by hr.outbox_event columns. No P3 value (D7). Generated from " + type.getSimpleName()
                + "; a breaking change needs a new schema version.");
        s.put("x-eventType", sample.eventType());
        s.put("x-schemaVersion", sample.schemaVersion());
        s.putAll(objectSchema(type, false));
        return s;
    }

    static Map<String, Object> objectSchema(Class<?> type, boolean nullable) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (RecordComponent c : type.getRecordComponents()) {
            props.put(c.getName(), typeSchema(c.getGenericType(), c.getType().isPrimitive()
                    || isRequired(type, c.getName())));
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", nullable ? List.of("object", "null") : "object");
        s.put("additionalProperties", false);
        s.put("required", new ArrayList<>(props.keySet()));
        s.put("properties", props);
        return s;
    }

    static Map<String, Object> typeSchema(Type t, boolean nonNull) {
        Class<?> raw = t instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) t;
        Map<String, Object> s = new LinkedHashMap<>();
        String jsonType;
        String format = null;
        if (raw == UUID.class) {
            jsonType = "string";
            format = "uuid";
        } else if (raw == String.class) {
            jsonType = "string";
        } else if (raw == LocalDate.class) {
            jsonType = "string";
            format = "date";
        } else if (raw == Instant.class) {
            jsonType = "string";
            format = "date-time";
        } else if (raw == boolean.class || raw == Boolean.class) {
            jsonType = "boolean";
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class) {
            jsonType = "integer";
        } else if (raw == BigDecimal.class) {
            jsonType = "number";
        } else if (List.class.isAssignableFrom(raw)) {
            jsonType = "array";
            s.put("items", typeSchema(((ParameterizedType) t).getActualTypeArguments()[0], true));
        } else if (Map.class.isAssignableFrom(raw)) {
            jsonType = "object";
            s.put("additionalProperties", typeSchema(((ParameterizedType) t).getActualTypeArguments()[1], true));
        } else if (raw.isRecord()) {
            return objectSchema(raw, !nonNull);
        } else {
            throw new IllegalStateException("No schema for " + t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", nonNull ? jsonType : List.of(jsonType, "null"));
        if (format != null) {
            out.put("format", format);
        }
        out.putAll(s);
        return out;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> loadSchema(String writtenTo, String file) throws IOException {
        if (writtenTo != null) {
            Path p = Path.of(writtenTo, "events", "people", file);
            return Files.exists(p) ? (Map<String, Object>) (Map<?, ?>) json.map(Files.readString(p)) : null;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("events/people/" + file)) {
            return in == null ? null : json.map(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------------------------------------ validator

    static final Pattern UUID_RE = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** The subset of JSON Schema the People schemas use: type, format, properties, required, additionalProperties, items. */
    @SuppressWarnings("unchecked")
    static void validate(Map<String, Object> schema, Object value, String path, List<String> errors) {
        Object type = schema.get("type");
        List<String> types = type instanceof List<?> l ? (List<String>) l : type == null ? List.of() : List.of((String) type);
        if (!types.isEmpty() && types.stream().noneMatch(t -> matches(t, value))) {
            errors.add(path + ": expected " + types + " but was " + (value == null ? "null" : value.getClass().getSimpleName()));
            return;
        }
        if (value == null) {
            return;
        }
        Object format = schema.get("format");
        if (value instanceof String s && format != null) {
            boolean ok = switch (format.toString()) {
                case "uuid" -> UUID_RE.matcher(s).matches();
                case "date" -> DATE_RE.matcher(s).matches();
                case "date-time" -> parses(s);
                default -> true;
            };
            if (!ok) {
                errors.add(path + ": not a " + format);
            }
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            for (Object r : (Collection<Object>) schema.getOrDefault("required", List.of())) {
                if (!m.containsKey(r)) {
                    errors.add(path + "." + r + ": required");
                }
            }
            Object additional = schema.get("additionalProperties");
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (props.containsKey(k)) {
                    validate((Map<String, Object>) props.get(k), e.getValue(), path + "." + k, errors);
                } else if (Boolean.FALSE.equals(additional)) {
                    errors.add(path + "." + k + ": not allowed");
                } else if (additional instanceof Map<?, ?> a) {
                    validate((Map<String, Object>) a, e.getValue(), path + "." + k, errors);
                }
            }
        }
        if (value instanceof List<?> l && schema.get("items") instanceof Map<?, ?> items) {
            for (int i = 0; i < l.size(); i++) {
                validate((Map<String, Object>) items, l.get(i), path + "[" + i + "]", errors);
            }
        }
    }

    static boolean matches(String type, Object v) {
        return switch (type) {
            case "null" -> v == null;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "integer" -> v instanceof Integer || v instanceof Long || v instanceof Short
                    || v instanceof java.math.BigInteger;
            case "number" -> v instanceof Number;
            case "array" -> v instanceof List<?>;
            case "object" -> v instanceof Map<?, ?>;
            default -> false;
        };
    }

    static boolean parses(String s) {
        try {
            OffsetDateTime.parse(s);
            return true;
        } catch (RuntimeException ex) {
            try {
                Instant.parse(s);
                return true;
            } catch (RuntimeException ex2) {
                return false;
            }
        }
    }

    static void p3Names(Object v, String path, List<String> out) {
        if (v instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (P3_NAME.matcher(k).matches() && !k.endsWith("Last4")) {
                    out.add(path + "." + k);
                }
                p3Names(e.getValue(), path + "." + k, out);
            }
        } else if (v instanceof List<?> l) {
            for (Object o : l) {
                p3Names(o, path + "[]", out);
            }
        }
    }
}
