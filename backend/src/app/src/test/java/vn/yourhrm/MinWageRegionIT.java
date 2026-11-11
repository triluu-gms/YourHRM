package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import vn.yourhrm.core.api.CoreEvents.LegalEntityRegionChanged;
import vn.yourhrm.core.api.CoreEvents.LocationRegionChanged;
import vn.yourhrm.core.api.MinWageRegions;
import vn.yourhrm.core.api.MinWageRegions.Region;
import vn.yourhrm.shared.common.legal.LegalParameterPackPublished;
import vn.yourhrm.shared.common.legal.LegalParameters;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.RecordedEvents;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/** Minimum-wage region via the Legal Parameter Service (BR-CORE-006) and its recomputation (BR-CORE-007). */
class MinWageRegionIT extends IntegrationTest {

    @Autowired
    MinWageRegions regions;

    @BeforeAll
    static void adminUnits() {
        ADMIN.update("""
                insert into core.admin_unit (admin_unit_code, level, name_vi, valid_from) values
                  ('RG-URBAN', 'COMMUNE', 'Phường trung tâm', date '2025-07-01'),
                  ('RG-RURAL', 'COMMUNE', 'Xã nông thôn', date '2025-07-01'),
                  ('RG-NEW', 'COMMUNE', 'Xã chưa có trong pack', date '2025-07-01')
                on conflict do nothing""");
    }

    private static String locationAttrs(String adminUnit) {
        return """
                {"name":"Nhà máy","locationType":"FACTORY","adminUnitCode":"%s","addressLine":"KCN 1"}""".formatted(adminUnit);
    }

    private UUID createLocation(TestUser owner, UUID le, String code, String adminUnit) throws Exception {
        String json = mvc.perform(post("/api/v1/core/locations").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"legalEntityId\":\"" + le
                                + "\",\"validFrom\":\"2026-01-01\",\"attributes\":" + locationAttrs(adminUnit) + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private static int locationVersion(UUID location) {
        return ADMIN.queryForObject("select version from core.location where id = ?", Integer.class, location);
    }

    private static List<LocationRegionChanged> locationEvents(UUID tenant) {
        return RecordedEvents.of(LocationRegionChanged.class).stream().filter(e -> e.tenantId().equals(tenant)).toList();
    }

    @Test
    void regionIsResolvedFromTheAddressAsOfADate() throws Exception {
        UUID tenant = FIX.tenant("rg-resolve");
        TestUser owner = FIX.owner(tenant);
        TestLegalParameters.publish(tenant, 1, Map.of("RG-URBAN", "I", "RG-RURAL", "III"));
        UUID le = FIX.legalEntity(tenant, "HQ");
        UUID loc = createLocation(owner, le, "F1", "RG-URBAN");
        // the factory's address moves to a rural commune from 01/07/2026
        mvc.perform(post("/api/v1/core/locations/" + loc + "/versions").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"2026-07-01\",\"attributes\":" + locationAttrs("RG-RURAL")
                                + ",\"version\":" + locationVersion(loc) + "}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/core/locations/" + loc + "/min-wage-region?asOf=2026-06-30").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminUnitCode").value("RG-URBAN"))
                .andExpect(jsonPath("$.region").value("I"))
                .andExpect(jsonPath("$.packVersion").value(1))
                .andExpect(jsonPath("$.overridden").value(false));
        mvc.perform(get("/api/v1/core/locations/" + loc + "/min-wage-region?asOf=2026-07-01").with(as(owner)))
                .andExpect(jsonPath("$.region").value("III"));
        mvc.perform(get("/api/v1/core/locations/" + loc + "/min-wage-region?asOf=2025-12-31").with(as(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("no-version-at-date"));

        // the module API (P-03) gives the same answer
        MinWageRegions.Resolution r = TenantContext.callWith(new TenantContext(tenant, null, null, "t", null),
                () -> regions.forLocation(loc, LocalDate.of(2026, 7, 1)).orElseThrow());
        assertThat(r.region()).isEqualTo(Region.III);
        assertThat(r.legalEntityId()).isEqualTo(le);

        // events: region known at creation, then I -> III from 01/07/2026 because of the address
        assertThat(locationEvents(tenant)).extracting(LocationRegionChanged::cause, LocationRegionChanged::previousRegion,
                        LocationRegionChanged::region, LocationRegionChanged::effectiveFrom)
                .containsExactly(
                        tuple("CREATED", null, "I", LocalDate.of(2026, 1, 1)),
                        tuple("ADDRESS", "I", "III", LocalDate.of(2026, 7, 1)));
    }

    @Test
    void addressChangeWithinTheSameRegionPublishesNoRegionChange() throws Exception {
        UUID tenant = FIX.tenant("rg-same");
        TestUser owner = FIX.owner(tenant);
        TestLegalParameters.publish(tenant, 1, Map.of("RG-URBAN", "II", "RG-RURAL", "II"));
        UUID loc = createLocation(owner, FIX.legalEntity(tenant, "HQ"), "F1", "RG-URBAN");
        mvc.perform(post("/api/v1/core/locations/" + loc + "/versions").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"2026-03-01\",\"attributes\":" + locationAttrs("RG-RURAL")
                                + ",\"version\":" + locationVersion(loc) + "}"))
                .andExpect(status().isOk());
        assertThat(locationEvents(tenant)).extracting(LocationRegionChanged::cause).containsExactly("CREATED");
    }

    @Test
    void unmappedAdminUnitAndPermittedOverride() throws Exception {
        UUID tenant = FIX.tenant("rg-ovr");
        TestUser owner = FIX.owner(tenant);
        TestLegalParameters.publish(tenant, 3, Map.of("RG-URBAN", "I"));
        UUID le = FIX.legalEntity(tenant, "HQ");
        UUID unmapped = createLocation(owner, le, "F1", "RG-NEW");
        mvc.perform(get("/api/v1/core/locations/" + unmapped + "/min-wage-region?asOf=2026-02-01").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").doesNotExist())
                .andExpect(jsonPath("$.packVersion").doesNotExist());

        UUID overridden = createLocation(owner, le, "F2", "RG-URBAN");
        TestLegalParameters.overrideLocation(tenant, overridden, "II");
        mvc.perform(get("/api/v1/core/locations/" + overridden + "/min-wage-region?asOf=2026-02-01").with(as(owner)))
                .andExpect(jsonPath("$.region").value("II"))
                .andExpect(jsonPath("$.overridden").value(true));
    }

    @Test
    void legalEntityRegionFollowsItsRegisteredAddress() throws Exception {
        UUID tenant = FIX.tenant("rg-le");
        TestUser owner = FIX.owner(tenant);
        TestLegalParameters.publish(tenant, 1, Map.of("RG-URBAN", "I", "RG-RURAL", "IV"));
        UUID le = FIX.legalEntity(tenant, "HQ");
        ADMIN.update("update core.legal_entity_version set admin_unit_code = 'RG-URBAN' where legal_entity_id = ?", le);
        mvc.perform(get("/api/v1/core/legal-entities/" + le + "/min-wage-region?asOf=2026-02-01").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("I"));

        mvc.perform(post("/api/v1/core/legal-entities/" + le + "/versions").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"2026-04-01\",\"attributes\":"
                                + LegalEntityApiIT.attrs("HQ", "0101234567", "").replace("IT-01", "RG-RURAL")
                                + ",\"version\":" + ADMIN.queryForObject("select version from core.legal_entity where id = ?",
                                        Integer.class, le) + "}"))
                .andExpect(status().isOk());
        assertThat(RecordedEvents.of(LegalEntityRegionChanged.class).stream()
                        .filter(e -> e.tenantId().equals(tenant)).toList())
                .extracting(LegalEntityRegionChanged::previousRegion, LegalEntityRegionChanged::region,
                        LegalEntityRegionChanged::effectiveFrom)
                .containsExactly(tuple("I", "IV", LocalDate.of(2026, 4, 1)));
    }

    @Test
    void aNewLegalPackRemappingACommuneRecomputesTheAffectedLocationsOnly() throws Exception {
        UUID tenant = FIX.tenant("rg-pack");
        TestUser owner = FIX.owner(tenant);
        TestLegalParameters.publish(tenant, 1, Map.of("RG-URBAN", "II", "RG-RURAL", "III"));
        UUID le = FIX.legalEntity(tenant, "HQ");
        UUID urban = createLocation(owner, le, "U", "RG-URBAN");
        createLocation(owner, le, "R", "RG-RURAL");
        UUID closed = createLocation(owner, le, "C", "RG-URBAN");
        ADMIN.update("update core.location set status = 'CLOSED' where id = ?", closed);

        // pack 2 moves the urban commune from region II to I from 01/01/2027
        TestLegalParameters.publish(tenant, 2, Map.of("RG-URBAN", "I", "RG-RURAL", "III"));
        LegalParameterPackPublished published = new LegalParameterPackPublished(2, 1,
                Set.of(LegalParameters.MIN_WAGE_REGION_MAP), List.of(LocalDate.of(2027, 1, 1)));
        int changes = TenantContext.callWith(new TenantContext(tenant, null, null, "pack", null),
                () -> regions.recomputeAfterPackPublished(published));

        assertThat(changes).isEqualTo(1);
        assertThat(locationEvents(tenant)).filteredOn(e -> e.cause().equals("LEGAL_PACK"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.locationId()).isEqualTo(urban);
                    assertThat(e.previousRegion()).isEqualTo("II");
                    assertThat(e.region()).isEqualTo("I");
                    assertThat(e.effectiveFrom()).isEqualTo(LocalDate.of(2027, 1, 1));
                    assertThat(e.packVersion()).isEqualTo(2);
                });
        // a pack that does not touch the region map is ignored
        int none = TenantContext.callWith(new TenantContext(tenant, null, null, "pack", null),
                () -> regions.recomputeAfterPackPublished(new LegalParameterPackPublished(3, 2,
                        Set.of("SI.EE.RATE"), List.of(LocalDate.of(2027, 1, 1)))));
        assertThat(none).isZero();
    }

    @Test
    void regionLookupsFollowDataScope() throws Exception {
        UUID tenant = FIX.tenant("rg-scope");
        TestLegalParameters.publish(tenant, 1, Map.of("RG-URBAN", "I"));
        UUID le1 = FIX.legalEntity(tenant, "LE1");
        UUID le2 = FIX.legalEntity(tenant, "LE2");
        UUID loc2 = FIX.location(tenant, le2, "L2");
        TestUser le1Viewer = FIX.user(tenant, "v@test.local");
        FIX.assign(le1Viewer, FIX.role(tenant, "V", false, "core.location.view", "core.legal_entity.view"),
                "LEGAL_ENTITY", List.of(le1), true, TestFixtures.EPOCH, null);
        mvc.perform(get("/api/v1/core/locations/" + loc2 + "/min-wage-region").with(as(le1Viewer)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/core/legal-entities/" + le2 + "/min-wage-region").with(as(le1Viewer)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/core/legal-entities/" + le1 + "/min-wage-region").with(as(le1Viewer)))
                .andExpect(status().isOk());
    }
}
