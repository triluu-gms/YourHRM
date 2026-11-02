package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.shared.common.entitlement.TenantLimits;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestTenantLimits;

/**
 * Legal entities: 1..n per tenant within the plan limit (BR-CORE-001), employer attributes (BR-CORE-002), MST
 * format and uniqueness (BR-CORE-003), effective-dated history (BR-CORE-004) and status lifecycle.
 */
class LegalEntityApiIT extends IntegrationTest {

    @BeforeAll
    static void adminUnits() {
        ADMIN.update("""
                insert into core.admin_unit (admin_unit_code, level, name_vi, valid_from) values
                  ('IT-01', 'PROVINCE', 'Thành phố Hà Nội', date '2025-07-01'),
                  ('IT-02', 'PROVINCE', 'Thành phố Hồ Chí Minh', date '2025-07-01'),
                  ('IT-OLD', 'DISTRICT', 'Quận cũ', date '2000-01-01')
                on conflict do nothing""");
        ADMIN.update("update core.admin_unit set valid_to = date '2025-07-01' where admin_unit_code = 'IT-OLD'");
    }

    /** Attribute set; {@code extra} is spliced in as additional JSON members. */
    static String attrs(String name, String taxCode, String extra) {
        return """
                {"legalNameVi":"Công ty TNHH %s","legalNameEn":"%s Co., Ltd.","taxCode":"%s","siUnitCode":"TA0001A",
                 "businessRegNo":"%s","businessRegDate":"2019-05-02","businessRegIssuer":"Sở KH&ĐT Hà Nội",
                 "phone":"+84 24 1234 5678","email":"hr@%s.vn","adminUnitCode":"IT-01","addressLine":"1 Tràng Tiền",
                 "legalRepName":"Nguyễn Văn A","legalRepTitle":"Giám đốc","fiscalYearStartMonth":1,
                 "taxAuthorityCode":"CT-HN"%s}""".formatted(name, name, taxCode, taxCode, name.toLowerCase(),
                extra.isEmpty() ? "" : "," + extra);
    }

    static String body(String code, String taxCode) {
        return body(code, taxCode, "");
    }

    static String body(String code, String taxCode, String extra) {
        return """
                {"code":"%s","validFrom":"2026-01-01","attributes":%s}""".formatted(code, attrs(code, taxCode, extra));
    }

    private ResultActions create(TestUser user, String json) throws Exception {
        return mvc.perform(post("/api/v1/core/legal-entities").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions change(TestUser user, UUID id, String effectiveFrom, String attributes) throws Exception {
        return mvc.perform(post("/api/v1/core/legal-entities/" + id + "/versions").with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"effectiveFrom\":\"" + effectiveFrom + "\",\"attributes\":" + attributes + "}"));
    }

    private UUID createdId(TestUser user, String json) throws Exception {
        String body = create(user, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    @Test
    void ownerCreatesTheFirstAndFurtherLegalEntities() throws Exception {
        UUID tenant = FIX.tenant("le-create");
        TestUser owner = FIX.owner(tenant);
        String location = create(owner, body("HQ", "0101234567"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.code").value("HQ"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current.taxCode").value("0101234567"))
                .andExpect(jsonPath("$.current.businessRegIssuer").value("Sở KH&ĐT Hà Nội"))
                .andExpect(jsonPath("$.current.email").value("hr@hq.vn"))
                .andExpect(jsonPath("$.current.taxAuthorityCode").value("CT-HN"))
                .andExpect(jsonPath("$.current.pitDeclarationFrequency").value("MONTHLY"))
                .andExpect(jsonPath("$.current.validFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.current.validTo").doesNotExist())
                .andReturn().getResponse().getHeader("Location");
        create(owner, body("BR-HCM", "0101234567-001")).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/core/legal-entities").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("BR-HCM", "HQ")));
        mvc.perform(get(location).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.legalNameVi").value("Công ty TNHH HQ"));

        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event
                 where tenant_id = ? and object_type = 'legal_entity' and object_id = ? and action = 'INSERT'""",
                Integer.class, tenant, id)).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select created_by from core.legal_entity where tenant_id = ? and id = ?",
                UUID.class, tenant, id)).isEqualTo(owner.userId());
    }

    @Test
    void creationIsBlockedAtThePlanLimit() throws Exception {
        UUID tenant = FIX.tenant("le-limit");
        TestUser owner = FIX.owner(tenant);
        TestTenantLimits.set(tenant, TenantLimits.LEGAL_ENTITIES, 2);
        FIX.legalEntity(tenant, "EXISTING");
        UUID dissolved = FIX.legalEntity(tenant, "GONE");
        ADMIN.update("update core.legal_entity set status = 'DISSOLVED' where id = ?", dissolved);
        ADMIN.update("update core.legal_entity_version set tax_code = '0300000009' where legal_entity_id = ?", dissolved);

        mvc.perform(get("/api/v1/core/legal-entities/usage").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.limit").value(2));
        // dissolved entities do not count: the second active one is still allowed
        create(owner, body("SECOND", "0300000002")).andExpect(status().isCreated());
        create(owner, body("THIRD", "0300000003"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("legal-entity-limit-reached"))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.used").value(2));
    }

    @Test
    void concurrentCreatesCannotExceedTheLimit() throws Exception {
        UUID tenant = FIX.tenant("le-race");
        TestUser owner = FIX.owner(tenant);
        TestTenantLimits.set(tenant, TenantLimits.LEGAL_ENTITIES, 1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Integer>> calls = List.of(1, 2, 3, 4).stream().<Callable<Integer>>map(i -> () ->
                    create(owner, body("RACE" + i, "040000000" + i)).andReturn().getResponse().getStatus()).toList();
            List<Integer> statuses = pool.invokeAll(calls).stream().map(LegalEntityApiIT::join).toList();
            assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
        }
        assertThat(ADMIN.queryForObject("select count(*) from core.legal_entity where tenant_id = ?", Integer.class,
                tenant)).isEqualTo(1);
    }

    private static int join(Future<Integer> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void taxCodeMustBeWellFormedAndUniqueInTheTenant() throws Exception {
        UUID tenant = FIX.tenant("le-mst");
        TestUser owner = FIX.owner(tenant);
        create(owner, body("A", "12345")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-tax-code"));
        create(owner, body("A", "0101234567-01")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-tax-code"));
        UUID a = createdId(owner, body("A", "0500000001"));
        create(owner, body("B", "0500000001")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("tax-code-taken"));
        create(owner, body("A", "0500000002")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("legal-entity-code-taken"));
        // the rule also holds for effective-dated changes
        UUID b = createdId(owner, body("B", "0500000002"));
        change(owner, b, "2026-06-01", attrs("B", "0500000001", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("tax-code-taken"));
        change(owner, a, "2026-06-01", attrs("A", "0500000001", "")).andExpect(status().isOk());

        // another tenant may register the same MST (uniqueness is per tenant)
        UUID other = FIX.tenant("le-mst2");
        create(FIX.owner(other), body("A", "0500000001")).andExpect(status().isCreated());
    }

    @Test
    void attributesAreValidated() throws Exception {
        UUID tenant = FIX.tenant("le-val");
        TestUser owner = FIX.owner(tenant);
        create(owner, body("A", "0600000001").replace("IT-01", "NOPE"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-admin-unit"));
        create(owner, body("A", "0600000001").replace("IT-01", "IT-OLD"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-admin-unit"));
        create(owner, body("A", "0600000001").replace("{\"code\"", "{\"status\":\"DISSOLVED\",\"code\""))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-status"));
        create(owner, body("A", "0600000001").replace("hr@a.vn", "not-an-email"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-email"));
        create(owner, body("A", "0600000001").replace("+84 24 1234 5678", "call me"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-phone"));
    }

    @Test
    void reducedOaOdRateNeedsTheDecisionAndEvidence() throws Exception {
        UUID tenant = FIX.tenant("le-oaod");
        TestUser owner = FIX.owner(tenant);
        UUID evidence = FIX.document(tenant, "QĐ giảm mức đóng BHTNLĐ-BNN");
        create(owner, body("A", "0610000001", "\"oaOdRate\":0.003"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("oa-od-evidence-required"));
        create(owner, body("A", "0610000001", "\"oaOdRate\":0.003,\"oaOdDecisionRef\":\"123/QĐ-BHXH\","
                        + "\"oaOdDecisionDocId\":\"" + UUID.randomUUID() + "\""))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-document"));
        create(owner, body("A", "0610000001", "\"oaOdRate\":0.05,\"oaOdDecisionRef\":\"x\",\"oaOdDecisionDocId\":\""
                        + evidence + "\""))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-oa-od-rate"));
        create(owner, body("A", "0610000001", "\"oaOdDecisionRef\":\"123/QĐ-BHXH\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("oa-od-decision-without-rate"));
        create(owner, body("A", "0610000001", "\"oaOdRate\":0.003,\"oaOdDecisionRef\":\"123/QĐ-BHXH\","
                        + "\"oaOdDecisionDocId\":\"" + evidence + "\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current.oaOdRate").value(0.003))
                .andExpect(jsonPath("$.current.oaOdDecisionDocId").value(evidence.toString()));
    }

    @Test
    void changesAreEffectiveDatedWithHistoryAndAsOfQueries() throws Exception {
        UUID tenant = FIX.tenant("le-hist");
        TestUser owner = FIX.owner(tenant);
        UUID id = createdId(owner, body("HQ", "0620000001"));

        // future-dated change: the old version ends at 2026-07-01 (last day 30/06), a new one starts then
        change(owner, id, "2026-07-01", attrs("HQ", "0620000001", "").replace("IT-01", "IT-02")
                        .replace("TA0001A", "TB0002B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.validFrom").value("2026-07-01"))
                .andExpect(jsonPath("$.current.siUnitCode").value("TB0002B"));
        // a change inside the first period splits it again; the later version is untouched
        change(owner, id, "2026-03-15", attrs("HQ", "0620000001", "").replace("Nguyễn Văn A", "Trần Thị B"))
                .andExpect(status().isOk());
        // a change on an existing start date corrects that version in place (no new version)
        change(owner, id, "2026-03-15", attrs("HQ", "0620000001", "").replace("Nguyễn Văn A", "Trần Thị C"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/core/legal-entities/" + id + "/versions").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].validFrom", contains("2026-01-01", "2026-03-15", "2026-07-01")))
                .andExpect(jsonPath("$[*].validTo", contains("2026-03-15", "2026-07-01", null)))
                .andExpect(jsonPath("$[*].legalRepName", contains("Nguyễn Văn A", "Trần Thị C", "Nguyễn Văn A")))
                .andExpect(jsonPath("$[*].adminUnitCode", contains("IT-01", "IT-01", "IT-02")));
        mvc.perform(get("/api/v1/core/legal-entities/" + id + "?asOf=2026-06-30").with(as(owner)))
                .andExpect(jsonPath("$.current.siUnitCode").value("TA0001A"))
                .andExpect(jsonPath("$.current.legalRepName").value("Trần Thị C"));
        mvc.perform(get("/api/v1/core/legal-entities/" + id + "?asOf=2026-07-01").with(as(owner)))
                .andExpect(jsonPath("$.current.siUnitCode").value("TB0002B"));

        // nothing is in effect before the entity started
        change(owner, id, "2025-12-31", attrs("HQ", "0620000001", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("no-version-at-date"));
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event
                 where tenant_id = ? and object_type = 'legal_entity_version' and action = 'UPDATE'""",
                Integer.class, tenant)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void pitDeclarationFrequencyChangesOnlyFromTheStartOfATaxYear() throws Exception {
        UUID tenant = FIX.tenant("le-pit");
        TestUser owner = FIX.owner(tenant);
        UUID id = createdId(owner, body("A", "0630000001"));
        change(owner, id, "2026-04-01", attrs("A", "0630000001", "\"pitDeclarationFrequency\":\"QUARTERLY\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("pit-frequency-mid-year"));
        change(owner, id, "2027-01-01", attrs("A", "0630000001", "\"pitDeclarationFrequency\":\"QUARTERLY\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.pitDeclarationFrequency").value("QUARTERLY"));
        // other mid-year changes keep the year's frequency and are fine
        change(owner, id, "2027-05-01", attrs("A", "0630000001", "\"pitDeclarationFrequency\":\"QUARTERLY\""))
                .andExpect(status().isOk());
    }

    @Test
    void statusLifecycle() throws Exception {
        UUID tenant = FIX.tenant("le-status");
        TestUser owner = FIX.owner(tenant);
        UUID id = createdId(owner, body("A", "0640000001").replace("{\"code\"", "{\"status\":\"DRAFT\",\"code\""));
        String url = "/api/v1/core/legal-entities/" + id + "/status";
        mvc.perform(put(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISSOLVED\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale-version"));
        FIX.location(tenant, id, "SITE-A");
        mvc.perform(put(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISSOLVED\",\"version\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("legal-entity-has-active-locations"));
        ADMIN.update("update core.location set status = 'CLOSED' where legal_entity_id = ?", id);
        mvc.perform(put(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISSOLVED\",\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISSOLVED"));
        mvc.perform(put(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"version\":2}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-status-transition"));
    }

    @Test
    void readsAreFilteredByDataScopeAndWritesNeedTheRightGrant() throws Exception {
        UUID tenant = FIX.tenant("le-scope");
        UUID le1 = FIX.legalEntity(tenant, "LE1");
        UUID le2 = FIX.legalEntity(tenant, "LE2");
        ADMIN.update("update core.legal_entity_version set tax_code = '0700000002' where legal_entity_id = ?", le2);
        UUID role = FIX.role(tenant, "LE_HR", false, "core.legal_entity.view", "core.legal_entity.create",
                "core.legal_entity.update");
        TestUser le1Admin = FIX.user(tenant, "le1@test.local");
        FIX.assign(le1Admin, role, "LEGAL_ENTITY", List.of(le1), true, TestFixtures.EPOCH, null);

        mvc.perform(get("/api/v1/core/legal-entities").with(as(le1Admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("LE1")));
        mvc.perform(get("/api/v1/core/legal-entities/" + le1).with(as(le1Admin))).andExpect(status().isOk());
        // out of scope is indistinguishable from missing
        mvc.perform(get("/api/v1/core/legal-entities/" + le2).with(as(le1Admin))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/core/legal-entities/" + le2 + "/versions").with(as(le1Admin)))
                .andExpect(status().isNotFound());
        create(le1Admin, body("LE3", "0700000001")).andExpect(status().isForbidden());
        // an LE-scoped update grant changes its own entity only
        change(le1Admin, le1, "2026-01-01", attrs("LE1", "0101234567", "")).andExpect(status().isOk());
        change(le1Admin, le2, "2026-01-01", attrs("LE2", "0700000002", "")).andExpect(status().isNotFound());

        TestUser nobody = FIX.user(tenant, "nobody@test.local");
        mvc.perform(get("/api/v1/core/legal-entities").with(as(nobody))).andExpect(status().isForbidden());
        String json = mvc.perform(get("/api/v1/core/legal-entities").with(as(FIX.owner(tenant))))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(json, "$[*].code")).containsExactly("LE1", "LE2");
    }
}
