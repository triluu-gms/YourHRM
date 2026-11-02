package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.core.api.CompanyGroupProvisioning;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/** Org unit types (BR-CORE-010), org units (BR-CORE-011) and the effective-dated tree (BR-CORE-012). */
class OrgUnitApiIT extends IntegrationTest {

    @Autowired
    CompanyGroupProvisioning provisioning;

    /** One tenant with an owner, a legal entity and the fixture type UNIT. */
    record World(UUID tenant, TestUser owner, UUID le, UUID type) {
    }

    private static World world(String name) {
        UUID tenant = FIX.tenant(name);
        UUID type = ADMIN.queryForObject("select id from core.org_unit_type where tenant_id = ? and code = 'UNIT'",
                UUID.class, tenant);
        return new World(tenant, FIX.owner(tenant), FIX.legalEntity(tenant, "LE"), type);
    }

    private static String attrs(World w, String name, UUID parent, UUID le) {
        return """
                {"nameVi":"%s","nameEn":"%s","typeId":"%s","parentId":%s,"legalEntityId":"%s","sortOrder":1}"""
                .formatted(name, name, w.type(), parent == null ? "null" : "\"" + parent + "\"", le);
    }

    private ResultActions create(World w, String code, UUID parent, String from) throws Exception {
        return create(w, code, parent, w.le(), from);
    }

    private ResultActions create(World w, String code, UUID parent, UUID le, String from) throws Exception {
        return mvc.perform(post("/api/v1/core/org-units").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"validFrom\":\"" + from + "\",\"attributes\":"
                        + attrs(w, "Unit " + code, parent, le) + "}"));
    }

    private UUID unit(World w, String code, UUID parent) throws Exception {
        String body = create(w, code, parent, "2026-01-01").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions change(World w, UUID id, String from, String attributes) throws Exception {
        return mvc.perform(post("/api/v1/core/org-units/" + id + "/versions").with(as(w.owner()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"effectiveFrom\":\"" + from + "\",\"attributes\":" + attributes + "}"));
    }

    private ResultActions close(World w, UUID id, String from) throws Exception {
        return mvc.perform(post("/api/v1/core/org-units/" + id + "/close").with(as(w.owner()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\"" + from + "\"}"));
    }

    @Test
    void defaultTypesAreSeededAndTypesAreConfigurable() throws Exception {
        World w = world("ou-types");
        TenantContext.callWith(new TenantContext(w.tenant(), null, null, "t", null), provisioning::provision);
        mvc.perform(get("/api/v1/core/org-unit-types").with(as(w.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("DIVISION")))
                .andExpect(jsonPath("$[?(@.code == 'TEAM')].nameVi").value("Tổ/Nhóm"));

        String created = mvc.perform(post("/api/v1/core/org-unit-types").with(as(w.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"FACTORY_LINE\",\"nameVi\":\"Chuyền\",\"nameEn\":\"Line\",\"levelHint\":6,\"sortOrder\":70}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID lineType = UUID.fromString(JsonPath.read(created, "$.id"));
        mvc.perform(post("/api/v1/core/org-unit-types").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"FACTORY_LINE\",\"nameVi\":\"x\",\"nameEn\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("org-unit-type-code-taken"));
        mvc.perform(put("/api/v1/core/org-unit-types/" + lineType).with(as(w.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameVi\":\"Chuyền SX\",\"nameEn\":\"Line\",\"levelHint\":6,\"sortOrder\":70,\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/core/org-unit-types").with(as(w.owner())))
                .andExpect(jsonPath("$[*].code", not(hasItem("FACTORY_LINE"))));

        // an inactive type cannot be used for new versions
        World inactive = new World(w.tenant(), w.owner(), w.le(), lineType);
        create(inactive, "X", null, "2026-01-01").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown-org-unit-type"));
    }

    @Test
    void theTreeSupportsMoreThanTwelveLevels() throws Exception {
        World w = world("ou-deep");
        UUID root = unit(w, "L00", null);
        UUID parent = root;
        UUID deepest = null;
        for (int i = 1; i <= 13; i++) {
            deepest = unit(w, "L%02d".formatted(i), parent);
            parent = deepest;
        }
        mvc.perform(get("/api/v1/core/org-units?asOf=2026-02-01").with(as(w.owner())))
                .andExpect(jsonPath("$", hasSize(14)))
                .andExpect(jsonPath("$[13].code").value("L13"))
                .andExpect(jsonPath("$[13].depth").value(14));
        mvc.perform(get("/api/v1/core/org-units/" + deepest + "/ancestors?asOf=2026-02-01").with(as(w.owner())))
                .andExpect(jsonPath("$", hasSize(13)))
                .andExpect(jsonPath("$[0].code").value("L00"));
        mvc.perform(get("/api/v1/core/org-units/" + root + "/descendants?asOf=2026-02-01").with(as(w.owner())))
                .andExpect(jsonPath("$", hasSize(13)));
    }

    @Test
    void movingASubtreeIsEffectiveDatedAndRepathsDescendants() throws Exception {
        World w = world("ou-move");
        UUID root = unit(w, "R", null);
        UUID a = unit(w, "A", root);
        UUID b = unit(w, "B", root);
        UUID a1 = unit(w, "A1", a);
        UUID a11 = unit(w, "A11", a1);

        // scoped user: org unit B with subtree
        UUID role = FIX.role(w.tenant(), "OU_VIEW", false, "core.org_unit.view");
        TestUser bManager = FIX.user(w.tenant(), "b@test.local");
        FIX.assign(bManager, role, "ORG_UNIT", List.of(b), true, TestFixtures.EPOCH, null);
        mvc.perform(get("/api/v1/core/org-units/" + a11 + "?asOf=2026-07-01").with(as(bManager)))
                .andExpect(status().isNotFound()); // warms the authorisation cache too

        // A moves under B from 01/07/2026
        change(w, a, "2026-07-01", attrs(w, "Unit A", b, w.le()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(b.toString()))
                .andExpect(jsonPath("$.depth").value(3));

        mvc.perform(get("/api/v1/core/org-units/" + a11 + "/ancestors?asOf=2026-06-30").with(as(w.owner())))
                .andExpect(jsonPath("$[*].code", contains("R", "A", "A1")));
        mvc.perform(get("/api/v1/core/org-units/" + a11 + "/ancestors?asOf=2026-07-01").with(as(w.owner())))
                .andExpect(jsonPath("$[*].code", contains("R", "B", "A", "A1")));
        mvc.perform(get("/api/v1/core/org-units/" + b + "/descendants?asOf=2026-06-30").with(as(w.owner())))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/core/org-units/" + b + "/descendants?asOf=2026-07-01").with(as(w.owner())))
                .andExpect(jsonPath("$[*].code", contains("A", "A1", "A11")));

        // descendants' history records the ancestry change (split at the move date, same attributes)
        mvc.perform(get("/api/v1/core/org-units/" + a11 + "/versions").with(as(w.owner())))
                .andExpect(jsonPath("$[*].validFrom", contains("2026-01-01", "2026-07-01")))
                .andExpect(jsonPath("$[*].depth", contains(4, 5)));

        // data scope follows the tree as of the date, and the move evicted the cached scope
        mvc.perform(get("/api/v1/core/org-units/" + a11 + "?asOf=2026-07-01").with(as(bManager)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/core/org-units/" + a11 + "?asOf=2026-06-30").with(as(bManager)))
                .andExpect(status().isNotFound());

        // every stored path ends with the unit's own label and starts at its legal entity's root
        assertThat(ADMIN.queryForObject("""
                select count(*) from core.org_unit_version v
                 where v.tenant_id = ? and subpath(v.path, 0, 1)::text <> replace(?::text, '-', '')""",
                Integer.class, w.tenant(), root)).isZero();
    }

    @Test
    void renameKeepsTheCodeAndHistory() throws Exception {
        World w = world("ou-rename");
        UUID root = unit(w, "R", null);
        UUID hr = unit(w, "HR", root);
        change(w, hr, "2026-04-01", attrs(w, "Phòng Nhân sự & Hành chính", root, w.le())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/core/org-units/" + hr + "/versions").with(as(w.owner())))
                .andExpect(jsonPath("$[*].nameVi", contains("Unit HR", "Phòng Nhân sự & Hành chính")))
                .andExpect(jsonPath("$[*].code", contains("HR", "HR")))
                .andExpect(jsonPath("$[*].validTo", contains("2026-04-01", null)));
        mvc.perform(get("/api/v1/core/org-units?asOf=2026-03-31&q=nhân sự").with(as(w.owner())))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/core/org-units?asOf=2026-04-01&q=nhân sự").with(as(w.owner())))
                .andExpect(jsonPath("$[*].code", contains("HR")));
    }

    @Test
    void structuralRulesAreEnforced() throws Exception {
        World w = world("ou-rules");
        UUID root = unit(w, "R", null);
        UUID a = unit(w, "A", root);
        UUID a1 = unit(w, "A1", a);

        create(w, "R", null, "2026-01-01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("org-unit-code-taken"));
        create(w, "R2", null, "2026-01-01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("legal-entity-has-root"));
        // no cycles: a unit cannot move under itself or its own descendant
        change(w, a, "2026-05-01", attrs(w, "Unit A", a, w.le())).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("org-unit-cycle"));
        change(w, root, "2026-05-01", attrs(w, "Unit R", a1, w.le())).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("org-unit-cycle"));
        // the parent must be in the same legal entity and active over the whole period
        UUID le2 = FIX.legalEntity(w.tenant(), "LE2");
        create(w, "X", a, le2, "2026-01-01").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("parent-in-other-legal-entity"));
        create(w, "Y", a, "2025-12-31").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-parent"));
        change(w, a, "2026-01-01", attrs(w, "Unit A", root, le2)).andExpect(status().isUnprocessableContent());

        // closing: blocked by active children, then allowed bottom-up; closed units leave the tree
        close(w, a, "2026-09-01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("org-unit-has-children"));
        close(w, a1, "2026-09-01").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        close(w, a1, "2026-10-01").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("org-unit-already-closed"));
        close(w, a, "2026-09-01").andExpect(status().isOk());
        mvc.perform(get("/api/v1/core/org-units?asOf=2026-09-01").with(as(w.owner())))
                .andExpect(jsonPath("$[*].code", contains("R")));
        mvc.perform(get("/api/v1/core/org-units?asOf=2026-09-01&includeClosed=true").with(as(w.owner())))
                .andExpect(jsonPath("$", hasSize(3)));
        // a closed unit cannot become a parent
        create(w, "Z", a, "2026-10-01").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-parent"));

        // closing is refused while later changes are planned
        UUID b = unit(w, "B", root);
        change(w, b, "2027-01-01", attrs(w, "Unit B 2027", root, w.le())).andExpect(status().isOk());
        close(w, b, "2026-06-01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("future-versions-exist"));
    }

    @Test
    void writesNeedPermissionOnTheTargetSubtree() throws Exception {
        World w = world("ou-perm");
        UUID root = unit(w, "R", null);
        UUID a = unit(w, "A", root);
        UUID b = unit(w, "B", root);
        UUID role = FIX.role(w.tenant(), "OU_ADMIN", false, "core.org_unit.view", "core.org_unit.create",
                "core.org_unit.update");
        TestUser aAdmin = FIX.user(w.tenant(), "a@test.local");
        FIX.assign(aAdmin, role, "ORG_UNIT", List.of(a), true, TestFixtures.EPOCH, null);
        World asA = new World(w.tenant(), aAdmin, w.le(), w.type());

        create(asA, "A-1", a, "2026-02-01").andExpect(status().isCreated());
        create(asA, "B-1", b, "2026-02-01").andExpect(status().isNotFound());
        change(asA, b, "2026-03-01", attrs(w, "B renamed", root, w.le())).andExpect(status().isNotFound());
        // may not move its own unit under a parent outside its scope
        change(asA, a, "2026-03-01", attrs(w, "Unit A", b, w.le())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/core/org-units?asOf=2026-02-01").with(as(aAdmin)))
                .andExpect(jsonPath("$[*].code", contains("A", "A-1")));
    }
}
