package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.core.api.CompanyGroupProvisioning;
import vn.yourhrm.core.api.CoreEvents.JobCatalogueChanged;
import vn.yourhrm.core.api.JobCatalogue;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.RecordedEvents;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Job architecture and cost centres: job families / titles (BR-CORE-020), grades (BR-CORE-021), cost centres
 * (BR-CORE-025), employee categories (BR-CORE-026) and the module lookup API (US-CORE-024).
 */
class JobCatalogueApiIT extends IntegrationTest {

    @Autowired
    JobCatalogue catalogue;

    @Autowired
    CompanyGroupProvisioning provisioning;

    @BeforeAll
    static void hazardList() {
        // vendor code list of statutory heavy / hazardous job categories (released by the platform)
        ADMIN.update("""
                insert into core.code_list (list_code, name_vi, name_en, is_statutory, owner_module)
                values ('JOB_HAZARD_CATEGORY', 'Nghề nặng nhọc, độc hại', 'Heavy / hazardous jobs', true, 'core')
                on conflict do nothing""");
        ADMIN.update("""
                insert into core.code_value (tenant_id, list_code, value_code, label_vi, label_en)
                values (sys.global_tenant_id(), 'JOB_HAZARD_CATEGORY', 'IV', 'Loại IV', 'Category IV'),
                       (sys.global_tenant_id(), 'JOB_HAZARD_CATEGORY', 'V', 'Loại V', 'Category V')
                on conflict do nothing""");
    }

    private ResultActions postJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(post(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions putJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(put(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static UUID id(ResultActions created) throws Exception {
        return UUID.fromString(JsonPath.read(created.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.id"));
    }

    private static <T> T inTenant(UUID tenant, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(tenant, null, null, "t", null), call);
    }

    @Test
    void gradesAreEffectiveDatedWithUniqueRanksAndNoPayData() throws Exception {
        UUID tenant = FIX.tenant("jc-grade");
        TestUser owner = FIX.owner(tenant);
        UUID structure = id(postJson(owner, "/api/v1/core/grade-structures", "{\"code\":\"NGACH_CV\",\"name\":\"Chuyên viên\"}"));
        String url = "/api/v1/core/grade-structures/" + structure + "/grades";
        UUID b1 = id(postJson(owner, url, "{\"code\":\"B1\",\"name\":\"Bậc 1\",\"rankOrder\":1,\"validFrom\":\"2026-01-01\"}"));
        id(postJson(owner, url, "{\"code\":\"B2\",\"name\":\"Bậc 2\",\"rankOrder\":2,\"validFrom\":\"2026-01-01\"}"));

        postJson(owner, url, "{\"code\":\"B2X\",\"name\":\"x\",\"rankOrder\":2,\"validFrom\":\"2026-06-01\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("grade-rank-taken"));
        postJson(owner, url, "{\"code\":\"B1\",\"name\":\"x\",\"rankOrder\":9}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("grade-code-taken"));

        // B1 is retired from 2027; a new grade can take rank 1 from then on
        putJson(owner, "/api/v1/core/grades/" + b1, "{\"name\":\"Bậc 1\",\"rankOrder\":1,\"validTo\":\"2027-01-01\",\"active\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.validTo").value("2027-01-01"));
        id(postJson(owner, url, "{\"code\":\"B1N\",\"name\":\"Bậc 1 (mới)\",\"rankOrder\":1,\"validFrom\":\"2027-01-01\"}"));

        mvc.perform(get(url + "?asOf=2026-12-31").with(as(owner)))
                .andExpect(jsonPath("$[*].code", contains("B1", "B2")))
                .andExpect(jsonPath("$[0].minSalary").doesNotExist());
        mvc.perform(get(url + "?asOf=2027-01-01").with(as(owner)))
                .andExpect(jsonPath("$[*].code", contains("B1N", "B2")));
        mvc.perform(get(url + "?allDates=true").with(as(owner))).andExpect(jsonPath("$", hasSize(3)));

        Map<UUID, JobCatalogue.Grade> found = inTenant(tenant, () -> catalogue.grades(List.of(b1), LocalDate.of(2027, 1, 1)));
        assertThat(found).isEmpty(); // not valid on that date
    }

    @Test
    void jobTitlesCarryHazardCategoryGradeRangeAndJd() throws Exception {
        UUID tenant = FIX.tenant("jc-title");
        TestUser owner = FIX.owner(tenant);
        UUID family = id(postJson(owner, "/api/v1/core/job-families", "{\"code\":\"PROD\",\"nameVi\":\"Sản xuất\",\"nameEn\":\"Production\"}"));
        UUID structure = id(postJson(owner, "/api/v1/core/grade-structures", "{\"code\":\"CN\",\"name\":\"Công nhân\"}"));
        String gradesUrl = "/api/v1/core/grade-structures/" + structure + "/grades";
        UUID g1 = id(postJson(owner, gradesUrl, "{\"code\":\"G1\",\"name\":\"Bậc 1\",\"rankOrder\":1,\"validFrom\":\"2020-01-01\"}"));
        UUID g3 = id(postJson(owner, gradesUrl, "{\"code\":\"G3\",\"name\":\"Bậc 3\",\"rankOrder\":3,\"validFrom\":\"2020-01-01\"}"));
        UUID jd = FIX.document(tenant, "JD thợ hàn");

        String attrs = """
                {"jobFamilyId":"%s","nameVi":"Thợ hàn","nameEn":"Welder","description":"Hàn kết cấu thép",
                 "jdDocumentId":"%s","defaultGradeMinId":"%s","defaultGradeMaxId":"%s","occupationCode":"7212",
                 "hazardousCategory":%s}""";
        UUID welder = id(postJson(owner, "/api/v1/core/job-titles",
                "{\"code\":\"WELDER\",\"attributes\":" + attrs.formatted(family, jd, g1, g3, "\"IV\"") + "}"));
        mvc.perform(get("/api/v1/core/job-titles/" + welder).with(as(owner)))
                .andExpect(jsonPath("$.hazardous").value(true))
                .andExpect(jsonPath("$.hazardousCategory").value("IV"))
                .andExpect(jsonPath("$.occupationCode").value("7212"));

        postJson(owner, "/api/v1/core/job-titles", "{\"code\":\"X1\",\"attributes\":" + attrs.formatted(family, jd, g1, g3, "\"IX\"") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown-hazardous-category"));
        postJson(owner, "/api/v1/core/job-titles", "{\"code\":\"X2\",\"attributes\":" + attrs.formatted(family, jd, g3, g1, "null") + "}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-grade-range"));
        postJson(owner, "/api/v1/core/job-titles", "{\"code\":\"X3\",\"attributes\":"
                        + attrs.formatted(family, UUID.randomUUID(), g1, g3, "null") + "}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-document"));
        postJson(owner, "/api/v1/core/job-titles", "{\"code\":\"WELDER\",\"attributes\":" + attrs.formatted(family, jd, g1, g3, "null") + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("job-title-code-taken"));

        // changing the hazard flag is signalled to People / T&A / Payroll
        putJson(owner, "/api/v1/core/job-titles/" + welder, "{\"version\":0,\"attributes\":" + attrs.formatted(family, jd, g1, g3, "\"V\"") + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        putJson(owner, "/api/v1/core/job-titles/" + welder, "{\"version\":0,\"attributes\":" + attrs.formatted(family, jd, g1, g3, "\"V\"") + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale-version"));
        assertThat(RecordedEvents.of(JobCatalogueChanged.class)).filteredOn(e -> e.entityId().equals(welder))
                .extracting(JobCatalogueChanged::change).containsExactly("CREATED", "HAZARD_CHANGED");

        mvc.perform(get("/api/v1/core/job-titles?q=hàn&jobFamilyId=" + family).with(as(owner)))
                .andExpect(jsonPath("$[*].code", contains("WELDER")));
        JobCatalogue.JobTitle t = inTenant(tenant, () -> catalogue.jobTitles(List.of(welder, UUID.randomUUID()))).get(welder);
        assertThat(t.hazardous()).isTrue();
        assertThat(t.hazardousCategory()).isEqualTo("V");
    }

    @Test
    void costCentresBelongToALegalEntityAndCannotEndWhileInUse() throws Exception {
        UUID tenant = FIX.tenant("jc-cc");
        TestUser owner = FIX.owner(tenant);
        UUID le1 = FIX.legalEntity(tenant, "LE1");
        UUID le2 = FIX.legalEntity(tenant, "LE2");
        String body = "{\"legalEntityId\":\"%s\",\"code\":\"%s\",\"name\":\"Chi phí %s\",\"glSegment\":\"6421\","
                + "\"ownerUserId\":\"" + owner.userId() + "\",\"validFrom\":\"2026-01-01\"}";
        UUID cc1 = id(postJson(owner, "/api/v1/core/cost-centres", body.formatted(le1, "CC-ADM", "ADM")));
        id(postJson(owner, "/api/v1/core/cost-centres", body.formatted(le2, "CC-ADM", "ADM"))); // per LE
        postJson(owner, "/api/v1/core/cost-centres", body.formatted(le1, "CC-ADM", "ADM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cost-centre-code-taken"));
        postJson(owner, "/api/v1/core/cost-centres", body.formatted(le1, "CC-X", "X").replace(owner.userId().toString(),
                        UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-owner"));

        // default cost centre of an org unit: must be of the unit's legal entity
        UUID type = ADMIN.queryForObject("select id from core.org_unit_type where tenant_id = ? and code = 'UNIT'",
                UUID.class, tenant);
        String unit = """
                {"code":"ROOT","validFrom":"2026-02-01","attributes":{"nameVi":"Công ty","typeId":"%s",
                 "legalEntityId":"%s","costCentreId":"%s"}}""";
        postJson(owner, "/api/v1/core/org-units", unit.formatted(type, le1, cc1)).andExpect(status().isCreated());

        putJson(owner, "/api/v1/core/cost-centres/" + cc1,
                "{\"name\":\"Chi phí ADM\",\"glSegment\":\"6421\",\"validTo\":\"2026-12-31\",\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cost-centre-in-use"))
                .andExpect(jsonPath("$.orgUnits[0]").value("ROOT"));
        putJson(owner, "/api/v1/core/cost-centres/" + cc1, "{\"name\":\"Chi phí hành chính\",\"glSegment\":\"6422\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.glSegment").value("6422"));

        // LE-scoped viewer sees only its legal entity's cost centres
        TestUser le2Viewer = FIX.user(tenant, "cc@test.local");
        FIX.assign(le2Viewer, FIX.role(tenant, "CC_VIEW", false, "core.cost_centre.view"), "LEGAL_ENTITY",
                List.of(le2), true, TestFixtures.EPOCH, null);
        mvc.perform(get("/api/v1/core/cost-centres").with(as(le2Viewer)))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].legalEntityId").value(le2.toString()));
        mvc.perform(get("/api/v1/core/cost-centres/" + cc1).with(as(le2Viewer))).andExpect(status().isNotFound());
        postJson(le2Viewer, "/api/v1/core/cost-centres", body.formatted(le2, "CC-Y", "Y")).andExpect(status().isForbidden());

        assertThat(inTenant(tenant, () -> catalogue.costCentres(List.of(cc1), LocalDate.of(2025, 12, 31)))).isEmpty();
        assertThat(inTenant(tenant, () -> catalogue.costCentres(List.of(cc1), LocalDate.of(2026, 3, 1)))).containsKey(cc1);
    }

    @Test
    void employeeCategoriesAreSeededAndCarryTypedFlags() throws Exception {
        UUID tenant = FIX.tenant("jc-cat");
        TestUser owner = FIX.owner(tenant);
        inTenant(tenant, provisioning::provision);
        inTenant(tenant, provisioning::provision); // idempotent
        mvc.perform(get("/api/v1/core/employee-categories").with(as(owner)))
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].code", hasItem("CTV")))
                .andExpect(jsonPath("$[?(@.code == 'THU_VIEC')].flags.probation").value(true))
                .andExpect(jsonPath("$[?(@.code == 'CTV')].flags.countsInHeadcount").value(false));

        String flags = "{\"probation\":false,\"seasonal\":false,\"intern\":false,\"expat\":true,\"collaborator\":false,"
                + "\"countsInHeadcount\":true}";
        UUID expatShort = id(postJson(owner, "/api/v1/core/employee-categories",
                "{\"code\":\"NN_NGAN_HAN\",\"nameVi\":\"Người nước ngoài ngắn hạn\",\"flags\":" + flags + ",\"sortOrder\":65}"));
        postJson(owner, "/api/v1/core/employee-categories",
                "{\"code\":\"NN_NGAN_HAN\",\"nameVi\":\"x\",\"flags\":" + flags + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("employee-category-code-taken"));
        putJson(owner, "/api/v1/core/employee-categories/" + expatShort,
                "{\"nameVi\":\"Người nước ngoài ngắn hạn\",\"flags\":" + flags.replace("\"seasonal\":false", "\"seasonal\":true")
                        + ",\"sortOrder\":65,\"active\":false,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.flags.seasonal").value(true))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/core/employee-categories").with(as(owner))).andExpect(jsonPath("$", hasSize(6)));

        JobCatalogue.EmployeeCategory c = inTenant(tenant, () -> catalogue.employeeCategories(List.of(expatShort)))
                .get(expatShort);
        assertThat(c.expat()).isTrue();
        assertThat(c.active()).isFalse();
    }

    @Test
    void catalogueMaintenanceNeedsATenantWideGrant() throws Exception {
        UUID tenant = FIX.tenant("jc-perm");
        UUID le = FIX.legalEntity(tenant, "LE");
        TestUser scoped = FIX.user(tenant, "hr@test.local");
        FIX.assign(scoped, FIX.role(tenant, "HR_LE", false, "core.job_catalogue.view", "core.job_catalogue.update",
                "core.employee_category.update"), "LEGAL_ENTITY", List.of(le), true, TestFixtures.EPOCH, null);
        mvc.perform(get("/api/v1/core/job-titles").with(as(scoped))).andExpect(status().isOk());
        postJson(scoped, "/api/v1/core/job-families", "{\"code\":\"F\",\"nameVi\":\"F\"}").andExpect(status().isForbidden());
        postJson(scoped, "/api/v1/core/grade-structures", "{\"code\":\"S\",\"name\":\"S\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/core/employee-categories").with(as(scoped))).andExpect(status().isForbidden());
    }
}
