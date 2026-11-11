package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    /** The unit's current optimistic-lock token (anchor version). */
    private static int version(UUID id) {
        return ADMIN.queryForObject("select version from core.org_unit where id = ?", Integer.class, id);
    }

    private ResultActions change(World w, UUID id, String from, String attributes) throws Exception {
        return change(w, id, from, attributes, version(id));
    }

    private ResultActions change(World w, UUID id, String from, String attributes, int version) throws Exception {
        return mvc.perform(post("/api/v1/core/org-units/" + id + "/versions").with(as(w.owner()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"effectiveFrom\":\"" + from + "\",\"attributes\":" + attributes + ",\"version\":"
                        + version + "}"));
    }

    private ResultActions close(World w, UUID id, String from) throws Exception {
        return mvc.perform(post("/api/v1/core/org-units/" + id + "/close").with(as(w.owner()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"effectiveFrom\":\"" + from + "\",\"version\":" + version(id) + "}"));
    }

    private ResultActions removeVersion(World w, UUID id, UUID versionId, int version) throws Exception {
        return mvc.perform(delete("/api/v1/core/org-units/" + id + "/versions/" + versionId + "?version=" + version)
                .with(as(w.owner())));
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
                        .content("{\"nameVi\":\"Chuyền SX\",\"nameEn\":\"Line\",\"levelHint\":6,\"sortOrder\":70,\"active\":false,"
                                + "\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1));
        // a second writer still holding version 0 is refused like every other versioned write
        mvc.perform(put("/api/v1/core/org-unit-types/" + lineType).with(as(w.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameVi\":\"x\",\"nameEn\":\"x\",\"sortOrder\":70,\"active\":true,\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale-version"));
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
    void writesCarryTheUnitVersionAndStaleOnesAreRejected() throws Exception {
        World w = world("ou-stale");
        UUID root = unit(w, "R", null);
        UUID a = unit(w, "A", root);
        UUID a1 = unit(w, "A1", a);
        mvc.perform(get("/api/v1/core/org-units/" + a).with(as(w.owner()))).andExpect(jsonPath("$.version").value(0));

        change(w, a, "2026-03-01", attrs(w, "A renamed", root, w.le()), 0)
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        // someone else still holding version 0
        change(w, a, "2026-04-01", attrs(w, "A again", root, w.le()), 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale-version"));
        mvc.perform(post("/api/v1/core/org-units/" + a1 + "/close").with(as(w.owner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\"2026-09-01\",\"version\":7}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale-version"));
        // the version is required
        mvc.perform(post("/api/v1/core/org-units/" + a1 + "/close").with(as(w.owner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\"2026-09-01\"}"))
                .andExpect(status().isBadRequest());

        // moving A re-paths (splits) A1 from that date: A1's token changes too
        UUID b = unit(w, "B", root);
        int a1Before = version(a1);
        change(w, a, "2026-07-01", attrs(w, "A renamed", b, w.le())).andExpect(status().isOk());
        assertThat(version(a1)).isGreaterThan(a1Before);
    }

    @Test
    void aPlannedVersionCanBeRemovedToUnblockAClose() throws Exception {
        World w = world("ou-planned");
        UUID root = unit(w, "R", null);
        UUID b = unit(w, "B", root);
        String planned = change(w, b, "2099-01-01", attrs(w, "Unit B 2099", root, w.le()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID plannedVersion = UUID.fromString(JsonPath.read(planned, "$.versionId"));
        close(w, b, "2026-06-01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("future-versions-exist"));

        // stale token, then an already effective version, are refused
        removeVersion(w, b, plannedVersion, version(b) - 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale-version"));
        UUID effective = ADMIN.queryForObject("select id from core.org_unit_version where org_unit_id = ? and valid_from = '2026-01-01'",
                UUID.class, b);
        removeVersion(w, b, effective, version(b)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("version-already-effective"));

        removeVersion(w, b, plannedVersion, version(b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nameVi").value("Unit B"))
                .andExpect(jsonPath("$.validTo").doesNotExist());
        mvc.perform(get("/api/v1/core/org-units/" + b + "/versions").with(as(w.owner())))
                .andExpect(jsonPath("$[*].validFrom", contains("2026-01-01")));
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event
                 where tenant_id = ? and action = 'REMOVE_PLANNED_VERSION' and object_type = 'org_unit' and object_id = ?""",
                Integer.class, w.tenant(), b)).isOne();
        close(w, b, "2026-06-01").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        // a scoped user without update on B cannot remove its versions
        UUID role = FIX.role(w.tenant(), "OU_VIEW2", false, "core.org_unit.view", "core.org_unit.update");
        TestUser other = FIX.user(w.tenant(), "other@test.local");
        UUID c = unit(w, "C", root);
        FIX.assign(other, role, "ORG_UNIT", List.of(c), true, TestFixtures.EPOCH, null);
        mvc.perform(delete("/api/v1/core/org-units/" + b + "/versions/" + plannedVersion + "?version=" + version(b))
                        .with(as(other)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theViewCarriesTheNamesOfWhatAUnitReferences() throws Exception {
        World w = world("ou-names");
        UUID root = unit(w, "R", null);
        UUID loc = FIX.location(w.tenant(), w.le(), "HCM");
        UUID cc = ADMIN.queryForObject("""
                insert into core.cost_centre (tenant_id, legal_entity_id, code, name, valid_from)
                values (?, ?, 'CC-HR', 'Nhân sự', '2020-01-01') returning id""", UUID.class, w.tenant(), w.le());
        TestUser manager = FIX.user(w.tenant(), "manager@test.local");
        ADMIN.update("update core.user_account set display_name = 'Nguyễn Văn Quản' where id = ?", manager.userId());
        String attributes = """
                {"nameVi":"Phòng HR","nameEn":"HR","typeId":"%s","parentId":"%s","legalEntityId":"%s","locationId":"%s",
                 "costCentreId":"%s","managerUserId":"%s","sortOrder":1}""".formatted(w.type(), root, w.le(), loc, cc,
                manager.userId());
        String created = mvc.perform(post("/api/v1/core/org-units").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HR\",\"validFrom\":\"2026-01-01\",\"attributes\":" + attributes + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID hr = UUID.fromString(JsonPath.read(created, "$.id"));

        mvc.perform(get("/api/v1/core/org-units?asOf=2026-02-01&q=HR").with(as(w.owner())))
                .andExpect(jsonPath("$[0].typeCode").value("UNIT"))
                .andExpect(jsonPath("$[0].typeNameVi").isNotEmpty())
                .andExpect(jsonPath("$[0].legalEntityCode").value("LE"))
                .andExpect(jsonPath("$[0].legalEntityName").value("Pháp nhân LE"))
                .andExpect(jsonPath("$[0].locationId").value(loc.toString()))
                .andExpect(jsonPath("$[0].locationCode").value("HCM"))
                .andExpect(jsonPath("$[0].locationName").value("Location HCM"))
                .andExpect(jsonPath("$[0].costCentreCode").value("CC-HR"))
                .andExpect(jsonPath("$[0].costCentreName").value("Nhân sự"))
                .andExpect(jsonPath("$[0].managerUserId").value(manager.userId().toString()))
                .andExpect(jsonPath("$[0].managerName").value("Nguyễn Văn Quản"));
        mvc.perform(get("/api/v1/core/org-units/" + hr + "/versions").with(as(w.owner())))
                .andExpect(jsonPath("$[0].legalEntityName").value("Pháp nhân LE"));
    }

    @Test
    void pickerLookupsNeedOrgUnitWriteAccessAndExposeOnlyIdCodeAndName() throws Exception {
        World w = world("ou-lookup");
        UUID root = unit(w, "R", null);
        UUID a = unit(w, "A", root);
        UUID le2 = FIX.legalEntity(w.tenant(), "LE2");
        ADMIN.update("""
                insert into core.cost_centre (tenant_id, legal_entity_id, code, name, valid_from)
                values (?, ?, 'CC-1', 'Cost one', '2020-01-01'), (?, ?, 'CC-2', 'Cost two', '2020-01-01')""",
                w.tenant(), w.le(), w.tenant(), le2);
        TestUser picked = FIX.user(w.tenant(), "tran.b@test.local");
        ADMIN.update("update core.user_account set display_name = 'Trần Thị B', email = 'b@x.vn' where id = ?",
                picked.userId());

        // view-only: refused (no core.user.view / core.cost_centre.view needed, but org-unit write access is)
        UUID viewRole = FIX.role(w.tenant(), "OU_VIEW_ONLY", false, "core.org_unit.view");
        TestUser viewer = FIX.user(w.tenant(), "viewer@test.local");
        FIX.assign(viewer, viewRole, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        mvc.perform(get("/api/v1/core/org-units/lookups/managers?q=tran").with(as(viewer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/core/org-units/lookups/cost-centres?legalEntityId=" + w.le()).with(as(viewer)))
                .andExpect(status().isForbidden());

        // a writer scoped to unit A (no user / cost-centre permissions)
        UUID writeRole = FIX.role(w.tenant(), "OU_WRITE", false, "core.org_unit.view", "core.org_unit.update");
        TestUser writer = FIX.user(w.tenant(), "writer@test.local");
        FIX.assign(writer, writeRole, "ORG_UNIT", List.of(a), true, TestFixtures.EPOCH, null);
        mvc.perform(get("/api/v1/core/org-units/lookups/managers?q=tran").with(as(writer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(picked.userId().toString()))
                .andExpect(jsonPath("$[0].code").value("tran.b@test.local"))
                .andExpect(jsonPath("$[0].name").value("Trần Thị B"))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].status").doesNotExist());
        mvc.perform(get("/api/v1/core/org-units/lookups/cost-centres?legalEntityId=" + w.le() + "&asOf=2026-02-01")
                        .with(as(writer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("CC-1")))
                .andExpect(jsonPath("$[0].name").value("Cost one"))
                .andExpect(jsonPath("$[0].glSegment").doesNotExist());
        // data scope: the writer has nothing in LE2
        mvc.perform(get("/api/v1/core/org-units/lookups/cost-centres?legalEntityId=" + le2 + "&asOf=2026-02-01")
                        .with(as(writer)))
                .andExpect(status().isNotFound());
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
