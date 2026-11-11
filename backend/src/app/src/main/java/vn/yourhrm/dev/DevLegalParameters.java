package vn.yourhrm.dev;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.yourhrm.shared.common.legal.LegalParameters;

/**
 * LOCAL DEVELOPMENT ONLY ({@code dev} profile): stand-in for the SaaS Legal Parameter Service, which does not exist yet.
 * Without any {@link LegalParameters} bean People fails closed ({@code 422 legal-parameter-missing}) on hires,
 * contracts, salary, separation, family and reports, so the dev stack could not be used from the frontend.
 *
 * <p>Values come from {@code dev/legal-parameters.json} (the same example values the integration tests use, all
 * {@code [verify-legal]}): {@code values} = scalar / structured parameters for every tenant and date,
 * {@code minWageRegionMap} = administrative unit code → region (I..IV). No overrides, one pack version.
 */
@Component
@Profile("dev")
public class DevLegalParameters implements LegalParameters {

    private static final Logger log = LoggerFactory.getLogger(DevLegalParameters.class);
    /** The pretend published pack all dev values come from. */
    public static final UUID PACK_ID = UUID.fromString("00000000-0000-7000-8000-0000000de001");
    private static final long PACK_VERSION = 1;

    private final Map<String, Object> values;
    private final Map<String, String> regionMap;

    public DevLegalParameters() throws IOException {
        Pack pack;
        try (InputStream in = new ClassPathResource("dev/legal-parameters.json").getInputStream()) {
            pack = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build().readValue(in, new TypeReference<Pack>() {
            });
        }
        this.values = Map.copyOf(pack.values());
        this.regionMap = Map.copyOf(pack.minWageRegionMap());
        log.warn("DEV legal parameters active ({} values, {} region rows) - never use outside local development",
                values.size(), regionMap.size());
    }

    record Pack(Map<String, Object> values, Map<String, String> minWageRegionMap) {
    }

    @Override
    public Optional<Value> value(String key, Scope scope, LocalDate asOf) {
        return Optional.ofNullable(values.get(key)).map(v -> new Value(v, PACK_ID, PACK_VERSION, false));
    }

    @Override
    public Optional<MapEntry> mapEntry(String key, String rowKey, Scope scope, LocalDate asOf) {
        return mapEntry(key, rowKey, scope, asOf, PACK_VERSION);
    }

    @Override
    public Optional<MapEntry> mapEntry(String key, String rowKey, Scope scope, LocalDate asOf, long packVersion) {
        if (!MIN_WAGE_REGION_MAP.equals(key) || rowKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(regionMap.get(rowKey))
                .map(region -> new MapEntry(Map.of("region", region), packVersion, false));
    }
}
