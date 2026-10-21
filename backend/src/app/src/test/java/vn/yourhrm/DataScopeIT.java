package vn.yourhrm;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Data scopes through the single authorisation service (US-CORE-035/037): ORG_UNIT subtree resolved via
 * core.org_unit_version.path AS OF a date (a reorganisation changes the answer), LEGAL_ENTITY, LOCATION,
 * SELF and the stubbed reporting line, default deny.
 *
 * <pre>
 *  until 2026-06-30:  ROOT ── A ── C ── D        from 2026-07-01:  ROOT ── A
 *                         └── B                                         └── B ── C ── D
 *  A, C, D in LE1 / LOC1 before the reorg; B (and C, D after it) in LE2 / LOC2
 * </pre>
 */
class DataScopeIT extends IntegrationTest {

    static final String PERM = "core.org_unit.view";
    static final LocalDate REORG = LocalDate.of(2026, 7, 1);
    static final String BEFORE = "2026-06-30";
    static final String AFTER = "2026-07-02";

    static UUID tenant;
    static UUID le1;
    static UUID le2;
    static UUID loc1;
    static UUID loc2;
    static OrgUnit root;
    static OrgUnit a;
    static OrgUnit b;
    static UUID c;
    static UUID d;
    static TestUser orgSubtree;
    static TestUser orgNoSubtree;
    static TestUser leUser;
    static TestUser locUser;
    static TestUser selfUser;
    static TestUser managerUser;
    static UUID selfEmployment;

    @BeforeAll
    static void data() {
        tenant = FIX.tenant("scope");
        le1 = FIX.legalEntity(tenant, "LE1");
        le2 = FIX.legalEntity(tenant, "LE2");
        loc1 = FIX.location(tenant, le1, "LOC1");
        loc2 = FIX.location(tenant, le2, "LOC2");
        LocalDate epoch = TestFixtures.EPOCH;
        root = FIX.orgUnit(tenant, "ROOT", null, le1, loc1, epoch, null);
        a = FIX.orgUnit(tenant, "A", root, le1, loc1, epoch, null);
        b = FIX.orgUnit(tenant, "B", root, le2, loc2, epoch, null);
        OrgUnit cBefore = FIX.orgUnit(tenant, "C", a, le1, loc1, epoch, REORG);
        OrgUnit dBefore = FIX.orgUnit(tenant, "D", cBefore, le1, loc1, epoch, REORG);
        c = cBefore.id();
        d = dBefore.id();
        // reorganisation effective 2026-07-01: C (with its child D) moves under B, into LE2 / LOC2
        OrgUnit cAfter = FIX.orgUnitVersion(tenant, c, b, le2, loc2, REORG, null);
        FIX.orgUnitVersion(tenant, d, cAfter, le2, loc2, REORG, null);

        UUID viewer = FIX.role(tenant, "ORG_VIEWER", false, PERM);
        orgSubtree = grant(viewer, "org-subtree", "ORG_UNIT", List.of(a.id()), true);
        orgNoSubtree = grant(viewer, "org-only", "ORG_UNIT", List.of(a.id()), false);
        leUser = grant(viewer, "le", "LEGAL_ENTITY", List.of(le1), true);
        locUser = grant(viewer, "loc", "LOCATION", List.of(loc2), true);
        selfUser = grant(viewer, "self", "SELF", List.of(), true);
        selfEmployment = UUID.randomUUID();
        ADMIN.update("update core.user_account set employment_id = ? where tenant_id = ? and id = ?",
                selfEmployment, tenant, selfUser.userId());
        managerUser = grant(viewer, "manager", "DIRECT_REPORTS", List.of(), true);
        ADMIN.update("update core.user_account set employment_id = ? where tenant_id = ? and id = ?",
                UUID.randomUUID(), tenant, managerUser.userId());
    }

    static TestUser grant(UUID role, String name, String scope, List<UUID> ids, boolean subtree) {
        TestUser u = FIX.user(tenant, name + "@scope.test");
        FIX.assign(u, role, scope, ids, subtree, TestFixtures.EPOCH, null);
        return u;
    }

    @Test
    void orgUnitSubtreeFollowsTheTreeAsOfTheDate() throws Exception {
        expect(orgSubtree, "ORG_UNIT", a.id(), BEFORE, true);
        expect(orgSubtree, "ORG_UNIT", c, BEFORE, true);
        expect(orgSubtree, "ORG_UNIT", d, BEFORE, true);
        expect(orgSubtree, "ORG_UNIT", b.id(), BEFORE, false);
        expect(orgSubtree, "ORG_UNIT", root.id(), BEFORE, false);
        // after the reorganisation C and D are no longer below A
        expect(orgSubtree, "ORG_UNIT", a.id(), AFTER, true);
        expect(orgSubtree, "ORG_UNIT", c, AFTER, false);
        expect(orgSubtree, "ORG_UNIT", d, AFTER, false);

        mvc.perform(get("/api/v1/core/me/authorisation/scope").param("permission", PERM).param("asOf", BEFORE)
                        .with(as(orgSubtree)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.tenantWide").value(false))
                .andExpect(jsonPath("$.scope.orgUnitIds", containsInAnyOrder(a.id().toString(), c.toString(), d.toString())));
        mvc.perform(get("/api/v1/core/me/authorisation/scope").param("permission", PERM).param("asOf", AFTER)
                        .with(as(orgSubtree)))
                .andExpect(jsonPath("$.scope.orgUnitIds", containsInAnyOrder(a.id().toString())));
    }

    @Test
    void orgUnitWithoutSubtreeCoversOnlyTheListedUnits() throws Exception {
        expect(orgNoSubtree, "ORG_UNIT", a.id(), BEFORE, true);
        expect(orgNoSubtree, "ORG_UNIT", c, BEFORE, false);
    }

    @Test
    void legalEntityScope() throws Exception {
        expect(leUser, "LEGAL_ENTITY", le1, BEFORE, true);
        expect(leUser, "LEGAL_ENTITY", le2, BEFORE, false);
        expect(leUser, "LOCATION", loc1, BEFORE, true);
        expect(leUser, "LOCATION", loc2, BEFORE, false);
        // org units by their legal entity as of the date
        expect(leUser, "ORG_UNIT", c, BEFORE, true);
        expect(leUser, "ORG_UNIT", c, AFTER, false);
        expect(leUser, "ORG_UNIT", b.id(), BEFORE, false);
        expect(leUser, "TENANT", null, BEFORE, false);
        mvc.perform(get("/api/v1/core/me/authorisation/scope").param("permission", PERM).with(as(leUser)))
                .andExpect(jsonPath("$.scope.legalEntityIds", containsInAnyOrder(le1.toString())))
                .andExpect(jsonPath("$.scope.orgUnitIds", hasSize(0)));
    }

    @Test
    void locationScope() throws Exception {
        expect(locUser, "LOCATION", loc2, BEFORE, true);
        expect(locUser, "LOCATION", loc1, BEFORE, false);
        expect(locUser, "ORG_UNIT", b.id(), BEFORE, true);
        expect(locUser, "ORG_UNIT", c, BEFORE, false);
        expect(locUser, "ORG_UNIT", c, AFTER, true);
        expect(locUser, "LEGAL_ENTITY", le2, BEFORE, false);
    }

    @Test
    void employeeTargetsUseTheSuppliedPlacementAndSelf() throws Exception {
        UUID someone = UUID.randomUUID();
        mvc.perform(get("/api/v1/core/me/authorisation/check").with(as(leUser)).param("permission", PERM)
                        .param("targetType", "EMPLOYEE").param("targetId", someone.toString())
                        .param("legalEntityId", le1.toString()))
                .andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(get("/api/v1/core/me/authorisation/check").with(as(orgSubtree)).param("permission", PERM)
                        .param("targetType", "EMPLOYEE").param("targetId", someone.toString())
                        .param("orgUnitId", d.toString()).param("asOf", BEFORE))
                .andExpect(jsonPath("$.allowed").value(true));
        expect(selfUser, "EMPLOYEE", selfEmployment, BEFORE, true);
        expect(selfUser, "EMPLOYEE", someone, BEFORE, false);
    }

    @Test
    void reportingLineScopesAreEmptyUntilPeopleProvidesThem() throws Exception {
        // ReportingLineProvider.NONE stub (US-CORE-036): DIRECT_REPORTS resolves to nobody (fail closed)
        expect(managerUser, "EMPLOYEE", UUID.randomUUID(), BEFORE, false);
        mvc.perform(get("/api/v1/core/me/authorisation/scope").param("permission", PERM).with(as(managerUser)))
                .andExpect(jsonPath("$.scope.employmentIds", hasSize(0)));
    }

    @Test
    void otherPermissionsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/v1/core/me/authorisation/check").with(as(orgSubtree))
                        .param("permission", "core.user.view").param("targetType", "ORG_UNIT")
                        .param("targetId", a.id().toString()))
                .andExpect(jsonPath("$.allowed").value(false));
    }

    private void expect(TestUser user, String type, UUID target, String asOf, boolean allowed) throws Exception {
        var req = get("/api/v1/core/me/authorisation/check").with(as(user)).param("permission", PERM)
                .param("targetType", type).param("asOf", asOf);
        if (target != null) {
            req = req.param("targetId", target.toString());
        }
        mvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(allowed));
    }
}
