package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/** Branches / work locations (BR-CORE-005, US-CORE-007): one legal entity each, effective-dated, scoped. */
class LocationApiIT extends IntegrationTest {

    @BeforeAll
    static void adminUnits() {
        ADMIN.update("""
                insert into core.admin_unit (admin_unit_code, level, name_vi, valid_from) values
                  ('IT-LOC-1', 'COMMUNE', 'Phường Bến Nghé', date '2025-07-01'),
                  ('IT-LOC-2', 'COMMUNE', 'Phường Sài Gòn', date '2025-07-01')
                on conflict do nothing""");
    }

    static String attrs(String name, String extra) {
        return """
                {"name":"%s","locationType":"BRANCH","adminUnitCode":"IT-LOC-1","addressLine":"2 Lê Lợi",
                 "timeZone":"Asia/Ho_Chi_Minh"%s}""".formatted(name, extra.isEmpty() ? "" : "," + extra);
    }

    static String body(String code, UUID legalEntity, String extra) {
        return """
                {"code":"%s","legalEntityId":"%s","validFrom":"2026-01-01","attributes":%s}"""
                .formatted(code, legalEntity, attrs("Chi nhánh " + code, extra));
    }

    private ResultActions create(TestUser user, String json) throws Exception {
        return mvc.perform(post("/api/v1/core/locations").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions change(TestUser user, UUID id, String effectiveFrom, String attributes) throws Exception {
        return mvc.perform(post("/api/v1/core/locations/" + id + "/versions").with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"effectiveFrom\":\"" + effectiveFrom + "\",\"attributes\":" + attributes + ",\"version\":"
                        + version(id) + "}"));
    }

    private ResultActions close(TestUser user, UUID id, String effectiveFrom, int version) throws Exception {
        return mvc.perform(post("/api/v1/core/locations/" + id + "/close").with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"effectiveFrom\":\"" + effectiveFrom + "\",\"version\":" + version + "}"));
    }

    /** The location's current optimistic-lock token (0 for an unknown id, so scope tests still reach the API). */
    private static int version(UUID id) {
        return ADMIN.queryForObject("select coalesce((select version from core.location where id = ?), 0)",
                Integer.class, id);
    }

    private UUID createdId(TestUser user, String json) throws Exception {
        String body = create(user, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    @Test
    void createChangeAndCloseALocation() throws Exception {
        UUID tenant = FIX.tenant("loc-crud");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "HQ"); // MST 0101234567
        UUID id = createdId(owner, body("HCM", le, "\"dependentTaxCode\":\"0101234567-001\",\"latitude\":10.7769,"
                + "\"longitude\":106.7009,\"geofenceRadiusM\":150"));

        mvc.perform(get("/api/v1/core/locations/" + id).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalEntityId").value(le.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current.locationType").value("BRANCH"))
                .andExpect(jsonPath("$.current.dependentTaxCode").value("0101234567-001"))
                .andExpect(jsonPath("$.current.geofenceRadiusM").value(150));

        change(owner, id, "2026-09-01", attrs("Chi nhánh HCM (mới)", "").replace("IT-LOC-1", "IT-LOC-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.adminUnitCode").value("IT-LOC-2"));
        mvc.perform(get("/api/v1/core/locations/" + id + "/versions").with(as(owner)))
                .andExpect(jsonPath("$[*].validFrom", contains("2026-01-01", "2026-09-01")))
                .andExpect(jsonPath("$[*].validTo", contains("2026-09-01", null)));
        mvc.perform(get("/api/v1/core/locations/" + id + "?asOf=2026-08-31").with(as(owner)))
                .andExpect(jsonPath("$.current.adminUnitCode").value("IT-LOC-1"));

        mvc.perform(get("/api/v1/core/locations?legalEntityId=" + le).with(as(owner)))
                .andExpect(jsonPath("$[*].code", contains("HCM")));

        // the change bumped the version: the token read before it is stale for every write
        change(owner, id, "2026-10-01", attrs("x", "")).andExpect(status().isOk());
        int stale = version(id) - 1;
        close(owner, id, "2026-12-01", stale).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale-version"));
        mvc.perform(post("/api/v1/core/locations/" + id + "/versions").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"2026-11-01\",\"attributes\":" + attrs("y", "") + ",\"version\":" + stale + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale-version"));

        // closing is effective-dated: in effect until the day before, CLOSED from the date on
        close(owner, id, "2026-12-01", version(id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedFrom").value("2026-12-01"));
        mvc.perform(get("/api/v1/core/locations/" + id + "?asOf=2026-11-30").with(as(owner)))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.closedFrom").value("2026-12-01"));
        mvc.perform(get("/api/v1/core/locations/" + id + "/versions").with(as(owner)))
                .andExpect(jsonPath("$[*].validTo", contains("2026-09-01", "2026-10-01", "2026-12-01")));
        close(owner, id, "2027-01-01", version(id)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-status-transition"));
    }

    @Test
    void aCloseIsRefusedBeforePlannedChangesUntilTheyAreRemoved() throws Exception {
        UUID tenant = FIX.tenant("loc-planned");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "HQ");
        UUID id = createdId(owner, body("HN", le, ""));
        String planned = change(owner, id, "2099-03-01", attrs("Chi nhánh HN 2099", "")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID plannedVersion = ADMIN.queryForObject(
                "select id from core.location_version where location_id = ? and valid_from = '2099-03-01'", UUID.class, id);
        assertThat(planned).contains("2099-03-01");

        close(owner, id, "2027-01-01", version(id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("future-versions-exist"));
        close(owner, id, "2099-03-01", version(id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("future-versions-exist"));

        mvc.perform(delete("/api/v1/core/locations/" + id + "/versions/" + plannedVersion + "?version=" + (version(id) - 1))
                        .with(as(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale-version"));
        mvc.perform(delete("/api/v1/core/locations/" + id + "/versions/" + plannedVersion + "?version=" + version(id))
                        .with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.name").value("Chi nhánh HN"))
                .andExpect(jsonPath("$.current.validTo").doesNotExist());
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event
                 where tenant_id = ? and action = 'REMOVE_PLANNED_VERSION' and object_type = 'location' and object_id = ?""",
                Integer.class, tenant, id)).isOne();
        // the first day of a location's history cannot be its closing date
        close(owner, id, "2026-01-01", version(id)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-close-date"));
        close(owner, id, "2027-01-01", version(id)).andExpect(status().isOk());
    }

    @Test
    void locationRulesAreEnforced() throws Exception {
        UUID tenant = FIX.tenant("loc-rules");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "HQ");
        createdId(owner, body("A", le, "\"dependentTaxCode\":\"0101234567-001\""));

        create(owner, body("A", le, "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("location-code-taken"));
        create(owner, body("B", le, "\"dependentTaxCode\":\"0101234567-001\"")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dependent-tax-code-taken"));
        // a dependent unit's MST must extend the legal entity's own MST
        create(owner, body("B", le, "\"dependentTaxCode\":\"0999999999-002\"")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-dependent-tax-code"));
        create(owner, body("B", le, "\"dependentTaxCode\":\"0101234567\"")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-dependent-tax-code"));
        create(owner, body("B", le, "\"latitude\":10.7")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-geo"));
        create(owner, body("B", le, "\"timeZone\":\"Mars/Olympus\"")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-time-zone"));
        create(owner, body("B", le, "").replace("\"validFrom\":\"2026-01-01\"", "\"validFrom\":\"2019-01-01\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("legal-entity-not-in-effect"));
        create(owner, body("B", UUID.randomUUID(), "")).andExpect(status().isNotFound());

        UUID dissolved = FIX.legalEntity(tenant, "OLD");
        ADMIN.update("update core.legal_entity set status = 'DISSOLVED' where id = ?", dissolved);
        create(owner, body("C", dissolved, "")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("legal-entity-dissolved"));
    }

    @Test
    void scopeComesFromTheLocationOrItsLegalEntity() throws Exception {
        UUID tenant = FIX.tenant("loc-scope");
        UUID le1 = FIX.legalEntity(tenant, "LE1");
        UUID le2 = FIX.legalEntity(tenant, "LE2");
        UUID loc1 = FIX.location(tenant, le1, "L1");
        UUID loc2 = FIX.location(tenant, le2, "L2");
        UUID loc3 = FIX.location(tenant, le2, "L3");

        UUID leRole = FIX.role(tenant, "LOC_ADMIN", false, "core.location.view", "core.location.create",
                "core.location.update");
        TestUser le1Admin = FIX.user(tenant, "le1@test.local");
        FIX.assign(le1Admin, leRole, "LEGAL_ENTITY", List.of(le1), true, TestFixtures.EPOCH, null);
        TestUser siteManager = FIX.user(tenant, "site@test.local");
        FIX.assign(siteManager, FIX.role(tenant, "LOC_VIEW", false, "core.location.view"), "LOCATION",
                List.of(loc2), true, TestFixtures.EPOCH, null);

        mvc.perform(get("/api/v1/core/locations").with(as(le1Admin))).andExpect(jsonPath("$[*].code", contains("L1")));
        mvc.perform(get("/api/v1/core/locations").with(as(siteManager))).andExpect(jsonPath("$[*].code", contains("L2")));
        mvc.perform(get("/api/v1/core/locations/" + loc3).with(as(siteManager))).andExpect(status().isNotFound());

        // creating / changing is allowed inside the grant's legal entity only
        create(le1Admin, body("N1", le1, "")).andExpect(status().isCreated());
        create(le1Admin, body("N2", le2, "")).andExpect(status().isNotFound());
        change(le1Admin, loc1, "2026-01-01", attrs("L1 mới", "")).andExpect(status().isOk());
        change(le1Admin, loc2, "2026-01-01", attrs("L2 mới", "")).andExpect(status().isNotFound());
        change(siteManager, loc2, "2026-01-01", attrs("L2 mới", "")).andExpect(status().isForbidden());
    }
}
