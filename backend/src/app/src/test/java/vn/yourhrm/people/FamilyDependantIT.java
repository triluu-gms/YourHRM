package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.family.DependantService;
import vn.yourhrm.people.internal.application.family.FamilyLegalKeys;
import vn.yourhrm.people.internal.application.family.FamilyMemberService;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;
import vn.yourhrm.support.TestObjectStorage;

/**
 * Family members and PIT dependants (BR-PPL-004/016/017; US-PPL-024, 027-030, 074): CRUD with encrypted, masked
 * identifiers, eligibility rules from legal parameters, one claimant per dependant per month, the PIT primary
 * employment rule, approval through {@code hr.DEPENDANT_CHANGE} with {@code people.DependantChanged}, end requests,
 * transfer to a new primary employment, ESS requests and the registration list (Excel + PDF).
 */
class FamilyDependantIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final LocalDate HIRED = LocalDate.of(2024, 1, 1);
    static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    DependantService dependants;
    @Autowired
    FamilyMemberService members;
    @Autowired
    @Qualifier(HrDb.TX)
    PlatformTransactionManager hrTx;

    record World(UUID tenant, UUID le, OrgUnit unit, Employee emp, TestUser hr, TestUser approver) {
    }

    @BeforeAll
    static void relationshipCodeList() {
        ADMIN.update("""
                insert into core.code_list (list_code, name_vi, name_en, is_tenant_extensible, is_statutory, owner_module)
                values ('RELATIONSHIP', 'Quan hệ gia đình', 'Relationship', false, true, 'hr') on conflict do nothing""");
        for (String code : List.of("CHILD", "SPOUSE", "FATHER", "MOTHER", "FRIEND")) {
            ADMIN.update("""
                    insert into core.code_value (tenant_id, list_code, value_code, label_vi, label_en)
                    values (sys.global_tenant_id(), 'RELATIONSHIP', ?, ?, ?) on conflict do nothing""", code, code, code);
        }
    }

    World world(String name) {
        UUID tenant = FIX.tenant(name);
        UUID le = FIX.legalEntity(tenant, "LE1");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Nguyễn", "An", HIRED);
        TestUser hr = PPL.hrAdmin(tenant);
        TestUser approver = approver(tenant);
        TestLegalParameters.define(tenant, FamilyLegalKeys.RELATIONSHIP_CATEGORIES, Map.of("CHILD", List.of("CHILD"),
                "SPOUSE", List.of("SPOUSE"), "PARENT", List.of("FATHER", "MOTHER"), "OTHER", List.of()));
        TestLegalParameters.define(tenant, FamilyLegalKeys.CHILD_MAX_AGE, 18);
        TestLegalParameters.define(tenant, FamilyLegalKeys.INCOME_MONTHLY_THRESHOLD, 1_000_000);
        TestLegalParameters.define(tenant, FamilyLegalKeys.REGISTRATION_FORM_CODE, "07/ĐK-NPT-TNCN");
        return new World(tenant, le, unit, emp, hr, approver);
    }

    /** The approver pool of the vendor definition: holders of role HR_ADMIN (tenant scope). */
    static TestUser approver(UUID tenant) {
        List<String> codes = new ArrayList<>(FIX.allCorePermissions());
        codes.addAll(PPL.allHrPermissions());
        UUID role = FIX.role(tenant, "HR_ADMIN", false, codes.toArray(String[]::new));
        TestUser user = FIX.user(tenant, "approver-" + Ids.newId().toString().substring(24) + "@test.local");
        FIX.assign(user, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        return user;
    }

    static String memberJson(String relationship, String name, String birth, String nid, Boolean income) {
        return """
                {"relationshipCode":"%s","fullName":"%s","birthDate":%s,"nationalId":%s,"hasIncomeAboveThreshold":%s}"""
                .formatted(relationship, name, birth == null ? "null" : "\"" + birth + "\"",
                        nid == null ? "null" : "\"" + nid + "\"", income);
    }

    UUID member(TestUser user, UUID personId, String json) throws Exception {
        String body = mvc.perform(post("/api/v1/people/persons/" + personId + "/family-members").with(as(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    UUID evidence(TestUser user, String path) throws Exception {
        String body = mvc.perform(multipart(path).file(new MockMultipartFile("file", "giay-khai-sinh.pdf",
                        "application/pdf", PDF)).with(as(user)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    static String registration(UUID memberId, String from, String to, String reason, UUID... evidence) {
        List<String> ids = java.util.Arrays.stream(evidence).map(e -> "\"" + e + "\"").toList();
        return """
                {"familyMemberId":"%s","fromMonth":"%s","toMonth":%s,"reasonCode":"%s","evidenceDocumentIds":[%s]}"""
                .formatted(memberId, from, to == null ? "null" : "\"" + to + "\"", reason, String.join(",", ids));
    }

    ResultActions register(TestUser user, UUID personId, String json) throws Exception {
        return mvc.perform(post("/api/v1/people/persons/" + personId + "/dependants").with(as(user))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static UUID id(ResultActions r) throws Exception {
        return UUID.fromString(JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    static UUID pendingTask(UUID subjectId) {
        return ADMIN.queryForObject("""
                select t.id from core.workflow_task t
                  join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                 where i.subject_id = ? and t.status = 'PENDING'""", UUID.class, subjectId);
    }

    void decide(TestUser approver, UUID subjectId, String action) throws Exception {
        mvc.perform(post("/api/v1/core/workflow/tasks/" + pendingTask(subjectId) + "/actions").with(as(approver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"comment\":\"ok\"}"))
                .andExpect(status().isNoContent());
    }

    static String dependantStatus(UUID id) {
        return ADMIN.queryForObject("select status from hr.pit_dependant where id = ?", String.class, id);
    }

    static List<Map<String, Object>> events(UUID tenant) {
        return ADMIN.queryForList("""
                select payload ->> 'subtype' as subtype, payload::text as payload, employment_id, effective_date
                  from hr.outbox_event where tenant_id = ? and event_type = 'people.DependantChanged'
                 order by created_at, id""", tenant);
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void familyMembersAreValidatedEncryptedMaskedAndScoped() throws Exception {
        World w = world("fam-crud");
        String base = "/api/v1/people/persons/" + w.emp().personId() + "/family-members";
        String created = mvc.perform(post(base).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"relationshipCode":"CHILD","fullName":"Nguyễn Bé Ba","birthDate":"2015-03-10",
                                 "nationalId":"001 215-012345","taxCode":"8012345678","averageMonthlyIncome":0}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nationalIdMasked").value("••••2345"))
                .andExpect(jsonPath("$.taxCodeMasked").value("••••5678"))
                .andExpect(jsonPath("$.hasIncomeAboveThreshold").value(false))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .doesNotContain("001215012345").doesNotContain("8012345678"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
        Map<String, Object> row = ADMIN.queryForMap("select * from hr.family_member where id = ?", id);
        assertThat(row.get("national_id_enc")).isNotNull();
        assertThat(new String((byte[]) row.get("national_id_enc"), StandardCharsets.ISO_8859_1)).doesNotContain("012345");
        assertThat(row.get("national_id_bidx")).isNotNull();

        // validation: code list, identifier format, duplicate identity number of the same employee
        mvc.perform(post(base).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson("NEIGHBOUR", "X", null, null, false)))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-relationship"));
        mvc.perform(post(base).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson("CHILD", "X", null, "12!", false)))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-national-id"));
        mvc.perform(post(base).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson("CHILD", "Bản sao", null, "001215012345", false)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("family-member-duplicate"));

        // update: identifiers kept when omitted (masked screens never hold them)
        mvc.perform(put(base + "/" + id).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON).content("""
                        {"relationshipCode":"CHILD","fullName":"Nguyễn Bé Ba","birthDate":"2015-03-10",
                         "hasIncomeAboveThreshold":false,"disabled":true,"version":0}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.disabled").value(true))
                .andExpect(jsonPath("$.nationalIdMasked").value("••••2345"));
        mvc.perform(put(base + "/" + id).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON).content("""
                        {"relationshipCode":"CHILD","fullName":"Nguyễn Bé Ba","version":0}"""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale-version"));

        // unmasking needs hr.person.view_sensitive and is audited
        mvc.perform(get(base + "/" + id + "/identifiers").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nationalId").value("001215012345"))
                .andExpect(jsonPath("$.taxCode").value("8012345678"));
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and action = 'READ_SENSITIVE' and object_id = ?""",
                Integer.class, w.tenant(), id)).isEqualTo(1);
        TestUser viewer = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(w.le()), PeoplePermissions.DEPENDANT_VIEW);
        mvc.perform(get(base).with(as(viewer))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(base + "/" + id + "/identifiers").with(as(viewer)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("sensitive-not-permitted"));
        mvc.perform(post(base).with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content(memberJson("SPOUSE", "Y", null, null, false))).andExpect(status().isForbidden());

        // scope: another legal entity's HR does not see the person; nor does another tenant
        UUID le2 = FIX.legalEntity(w.tenant(), "LE2");
        TestUser otherLe = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(le2), PeoplePermissions.DEPENDANT_VIEW);
        mvc.perform(get(base).with(as(otherLe))).andExpect(status().isNotFound());
        TestUser foreign = PPL.hrAdmin(FIX.tenant("fam-crud-x"));
        mvc.perform(get(base).with(as(foreign))).andExpect(status().isNotFound());

        mvc.perform(delete(base + "/" + id).param("version", "1").with(as(w.hr())))
                .andExpect(status().isNoContent());
        mvc.perform(get(base).with(as(w.hr()))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void dependantsAreCheckedApprovedPublishedAndEnded() throws Exception {
        World w = world("fam-dep");
        UUID person = w.emp().personId();
        UUID child = member(w.hr(), person, memberJson("CHILD", "Nguyễn Bé Ba", "2015-03-10", "001215012345", false));
        UUID doc = evidence(w.hr(), "/api/v1/people/persons/" + person + "/family-members/" + child + "/documents");
        String deps = "/api/v1/people/persons/" + person + "/dependants";

        // eligibility before submitting: under 18 until the birthday month 2033-03 (inclusive)
        mvc.perform(post(deps + "/validate").with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(registration(child, "2026-01", "2033-03", "CHILD_UNDER_AGE", doc)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.claimEmploymentId").value(w.emp().employmentId().toString()));
        mvc.perform(post(deps + "/validate").with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(registration(child, "2026-01", "2033-04", "CHILD_UNDER_AGE", doc)))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.violations[0].code").value("child-over-age"))
                .andExpect(jsonPath("$.violations[0].month").value("2033-04-01"));
        mvc.perform(post(deps + "/validate").with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(registration(child, "2026-01", null, "CHILD_UNDER_AGE", doc)))
                .andExpect(jsonPath("$.violations[0].code").value("end-month-required"));
        mvc.perform(post(deps + "/validate").with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(registration(child, "2026-01", null, "SPOUSE", doc)))
                .andExpect(jsonPath("$.violations[0].code").value("relationship-not-eligible"));
        // ineligible = blocked with the reason; evidence is required
        register(w.hr(), person, registration(child, "2026-01", "2033-04", "CHILD_UNDER_AGE", doc))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("dependant-ineligible"))
                .andExpect(jsonPath("$.violations[0].code").value("child-over-age"));
        register(w.hr(), person, registration(child, "2026-01", "2033-03", "CHILD_UNDER_AGE"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("evidence-required"));

        ResultActions created = register(w.hr(), person, registration(child, "2026-01", "2033-03", "CHILD_UNDER_AGE", doc))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.evidenceDocumentIds[0]").value(doc.toString()));
        UUID claim = id(created);
        assertThat(events(w.tenant())).isEmpty(); // nothing published before approval
        // one claimant per month: a second claim of the same member overlaps even while pending
        register(w.hr(), person, registration(child, "2027-01", "2027-12", "CHILD_UNDER_AGE", doc))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.violations[*].code", hasItem("already-claimed")));

        decide(w.approver(), claim, "APPROVE");
        assertThat(dependantStatus(claim)).isEqualTo("APPROVED");
        List<Map<String, Object>> ev = events(w.tenant());
        assertThat(ev).hasSize(1);
        assertThat(ev.getFirst()).containsEntry("subtype", "REGISTERED")
                .containsEntry("employment_id", w.emp().employmentId());
        assertThat((String) ev.getFirst().get("payload")).contains("\"fromMonth\":\"2026-01-01\"")
                .contains("\"toMonth\":\"2033-03-01\"").doesNotContain("012345").doesNotContain("Bé Ba");

        // the same individual (same identity number) recorded by the spouse, another employee, is already claimed
        Employee spouse = PPL.employee(w.tenant(), w.le(), w.unit(), "Trần", "Bình", HIRED);
        UUID sameChild = member(w.hr(), spouse.personId(), memberJson("CHILD", "Nguyễn Bé Ba", "2015-03-10",
                "001215012345", false));
        UUID doc2 = evidence(w.hr(), "/api/v1/people/persons/" + spouse.personId() + "/family-members/" + sameChild
                + "/documents");
        register(w.hr(), spouse.personId(), registration(sameChild, "2026-06", "2026-12", "CHILD_UNDER_AGE", doc2))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.violations[*].code", hasItem("claimed-by-another-taxpayer")));

        // a parent with income above the threshold is ineligible; without it the claim can be rejected
        UUID father = member(w.hr(), person, memberJson("FATHER", "Nguyễn Văn Cha", "1960-01-01", null, true));
        UUID docF = evidence(w.hr(), "/api/v1/people/persons/" + person + "/family-members/" + father + "/documents");
        register(w.hr(), person, registration(father, "2026-01", null, "PARENT", docF))
                .andExpect(jsonPath("$.violations[0].code").value("income-above-threshold"));
        UUID mother = member(w.hr(), person, memberJson("MOTHER", "Lê Thị Mẹ", "1962-01-01", null, false));
        UUID docM = evidence(w.hr(), "/api/v1/people/persons/" + person + "/family-members/" + mother + "/documents");
        UUID motherClaim = id(register(w.hr(), person, registration(mother, "2026-01", null, "PARENT", docM))
                .andExpect(status().isCreated()));
        decide(w.approver(), motherClaim, "REJECT");
        assertThat(dependantStatus(motherClaim)).isEqualTo("REJECTED");

        // end: requested, visible as pending, applied on approval
        mvc.perform(post(deps + "/" + claim + "/end").with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toMonth\":\"2026-06\",\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.pendingEndMonth").value("2026-06-01"));
        mvc.perform(post(deps + "/" + claim + "/end").with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toMonth\":\"2026-05\",\"version\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("end-request-pending"));
        UUID endRequest = ADMIN.queryForObject("""
                select id from hr.pit_dependant where tenant_id = ? and status = 'DRAFT'""", UUID.class, w.tenant());
        decide(w.approver(), endRequest, "APPROVE");
        mvc.perform(get(deps).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value(hasItem("ENDED")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].toMonth").value(hasItem("2026-06-01")))
                .andExpect(jsonPath("$[*].status", not(hasItem("DRAFT"))));
        assertThat(events(w.tenant())).extracting(e -> e.get("subtype")).containsExactly("REGISTERED", "ENDED");
        assertThat(events(w.tenant()).get(1).get("effective_date")).isEqualTo(Date.valueOf("2026-07-01"));

        // permission: a viewer cannot register
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.DEPENDANT_VIEW);
        register(viewer, person, registration(child, "2034-01", null, "CHILD_STUDENT", doc))
                .andExpect(status().isForbidden());
    }

    @Test
    void dependantsAreClaimedOnlyThroughThePitPrimaryEmploymentAndFollowItsChange() throws Exception {
        World w = world("fam-primary");
        UUID person = w.emp().personId();
        UUID le2 = FIX.legalEntity(w.tenant(), "LE2");
        OrgUnit unit2 = FIX.orgUnit(w.tenant(), "U2", null, le2, null, TestFixtures.EPOCH, null);
        UUID emp2 = PPL.employment(w.tenant(), person, le2, "C" + Ids.newId().toString().substring(28),
                LocalDate.of(2025, 6, 1), "ACTIVE");
        PPL.primaryAssignment(w.tenant(), emp2, le2, unit2, null, null, LocalDate.of(2025, 6, 1));
        UUID spouse = member(w.hr(), person, memberJson("SPOUSE", "Trần Thị Vợ", "1992-02-02", null, false));
        UUID doc = evidence(w.hr(), "/api/v1/people/persons/" + person + "/family-members/" + spouse + "/documents");

        // two live employments and no PIT primary designation: blocked
        register(w.hr(), person, registration(spouse, "2026-01", null, "SPOUSE", doc))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.violations[*].code", hasItem("primary-employment-required")));

        ADMIN.update("""
                insert into hr.primary_employment (tenant_id, person_id, employment_id, valid_from)
                values (?, ?, ?, ?)""", w.tenant(), person, w.emp().employmentId(), Date.valueOf("2025-06-01"));
        // the other employment may not claim
        register(w.hr(), person, """
                {"familyMemberId":"%s","employmentId":"%s","fromMonth":"2026-01","reasonCode":"SPOUSE",
                 "evidenceDocumentIds":["%s"]}""".formatted(spouse, emp2, doc))
                .andExpect(jsonPath("$.violations[*].code", hasItem("not-primary-employment")));
        UUID claim = id(register(w.hr(), person, registration(spouse, "2026-01", null, "SPOUSE", doc))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employmentId").value(w.emp().employmentId().toString())));
        decide(w.approver(), claim, "APPROVE");

        // the primary employment moves to emp2 from April: the claim is split and transferred (US-PPL-024)
        var moved = TenantContext.callWith(new TenantContext(w.tenant(), w.hr().userId(),
                w.hr().identityId().toString(), "t", null), () -> new TransactionTemplate(hrTx).execute(s -> {
                    ADMIN.update("update hr.primary_employment set valid_to = '2026-04-01' where person_id = ?", person);
                    ADMIN.update("""
                            insert into hr.primary_employment (tenant_id, person_id, employment_id, valid_from)
                            values (?, ?, ?, '2026-04-01')""", w.tenant(), person, emp2);
                    return dependants.transferToNewPrimary(person, emp2, LocalDate.of(2026, 4, 1));
                }));
        assertThat(moved).hasSize(1);
        assertThat(moved.getFirst().employmentId()).isEqualTo(emp2);
        assertThat(moved.getFirst().fromMonth()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(ADMIN.queryForObject("select to_month from hr.pit_dependant where id = ?", LocalDate.class, claim))
                .isEqualTo(LocalDate.of(2026, 3, 1));
        Map<String, Object> transferred = events(w.tenant()).getLast();
        assertThat(transferred).containsEntry("subtype", "TRANSFERRED").containsEntry("employment_id", emp2);
        assertThat((String) transferred.get("payload")).contains("\"previousEmploymentId\":\"" + w.emp().employmentId())
                .contains("\"previousLegalEntityId\":\"" + w.le());
        // the old employment can no longer claim the spouse from April: one claimant per month
        List<UUID> rows = ADMIN.queryForList("""
                select employment_id from hr.pit_dependant d join hr.family_member f on f.id = d.family_member_id
                 where f.person_id = ? and d.status = 'APPROVED' order by from_month""", UUID.class, person);
        assertThat(rows).containsExactly(w.emp().employmentId(), emp2);
    }

    @Test
    void employeesSeeTheirFamilyAndRequestDependantsInEss() throws Exception {
        World w = world("fam-ess");
        TestUser me = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.ESS_VIEW,
                PeoplePermissions.ESS_REQUEST);
        PPL.link(me, w.emp());
        UUID father = member(w.hr(), w.emp().personId(), memberJson("FATHER", "Nguyễn Văn Cha", "1960-01-01", null,
                null));

        mvc.perform(get("/api/v1/people/me/family-members").with(as(me)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].fullName").value("Nguyễn Văn Cha"));
        String created = mvc.perform(post("/api/v1/people/me/family-members").with(as(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson("CHILD", "Nguyễn Bé Út", "2020-08-15", "001220098765", false)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID child = UUID.fromString(JsonPath.read(created, "$.id"));
        UUID doc = evidence(me, "/api/v1/people/me/family-members/" + child + "/documents");

        // eligibility errors are shown before submitting
        mvc.perform(post("/api/v1/people/me/dependants/validate").with(as(me)).contentType(MediaType.APPLICATION_JSON)
                        .content(registration(father, "2026-01", null, "PARENT", doc)))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.violations[0].code").value("income-status-required"));
        ResultActions req = mvc.perform(post("/api/v1/people/me/dependants").with(as(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration(child, "2026-02", "2038-08", "CHILD_UNDER_AGE", doc)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.employmentId").value(w.emp().employmentId().toString()));
        UUID claim = id(req);
        assertThat(ADMIN.queryForObject("""
                select requester_user_id from core.workflow_instance where subject_id = ?""", UUID.class, claim))
                .isEqualTo(me.userId());
        mvc.perform(get("/api/v1/people/me/dependants").with(as(me)))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        decide(w.approver(), claim, "APPROVE");
        mvc.perform(get("/api/v1/people/me/dependants").with(as(me)))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
        // ESS end request goes into the same approval
        mvc.perform(post("/api/v1/people/me/dependants/" + claim + "/end").with(as(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"toMonth\":\"2026-12\",\"version\":1}"))
                .andExpect(jsonPath("$.pendingEndMonth").value("2026-12-01"));

        // not linked to an employment: nothing to show; ESS cannot reach the HR endpoints
        TestUser unlinked = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.ESS_VIEW);
        mvc.perform(get("/api/v1/people/me/dependants").with(as(unlinked))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/people/persons/" + w.emp().personId() + "/dependants").with(as(me)))
                .andExpect(status().isForbidden());
        // another employee's family member cannot be used
        Employee other = PPL.employee(w.tenant(), w.le(), w.unit(), "Lê", "Khác", HIRED);
        UUID foreignMember = member(w.hr(), other.personId(), memberJson("CHILD", "Lê Con", "2019-01-01", null, false));
        mvc.perform(post("/api/v1/people/me/dependants/validate").with(as(me)).contentType(MediaType.APPLICATION_JSON)
                        .content(registration(foreignMember, "2026-01", "2026-02", "CHILD_UNDER_AGE", doc)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRegistrationListIsProducedAsExcelAndPdfPerLegalEntityAndPeriod() throws Exception {
        World w = world("fam-report");
        UUID person = w.emp().personId();
        UUID child = member(w.hr(), person, memberJson("CHILD", "Nguyễn Bé Ba", "2015-03-10", "001215012345", false));
        UUID doc = evidence(w.hr(), "/api/v1/people/persons/" + person + "/family-members/" + child + "/documents");
        UUID claim = id(register(w.hr(), person, registration(child, "2026-02", "2026-03", "CHILD_UNDER_AGE", doc))
                .andExpect(status().isCreated()));
        decide(w.approver(), claim, "APPROVE");
        String report = "/api/v1/people/reports/dependant-registrations";

        mvc.perform(get(report).param("legalEntityId", w.le().toString()).param("fromMonth", "2026-01")
                        .param("toMonth", "2026-03").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.formCode").value("07/ĐK-NPT-TNCN"))
                .andExpect(jsonPath("$.registeredCount").value(1)).andExpect(jsonPath("$.endedCount").value(1))
                .andExpect(jsonPath("$.lines[0].dependantName").value("Nguyễn Bé Ba"))
                .andExpect(jsonPath("$.lines[0].dependantIdNo").value("••••2345"));
        mvc.perform(get(report).param("legalEntityId", w.le().toString()).param("fromMonth", "2026-04")
                        .param("toMonth", "2026-06").with(as(w.hr())))
                .andExpect(jsonPath("$.lines", hasSize(0)));

        byte[] xlsx = mvc.perform(get(report + "/export.xlsx").param("legalEntityId", w.le().toString())
                        .param("fromMonth", "2026-01").param("toMonth", "2026-03").with(as(w.hr())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("07/ĐK-NPT-TNCN");
            assertThat(sheet.getRow(4).getCell(5).getStringCellValue()).isEqualTo("Nguyễn Bé Ba");
            assertThat(sheet.getRow(4).getCell(7).getStringCellValue()).isEqualTo("001215012345"); // HR may unmask
            assertThat(sheet.getLastRowNum()).isEqualTo(5);
        }
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and action = 'EXPORT'
                   and object_type = 'dependant_registration_list'""", Integer.class, w.tenant())).isEqualTo(1);
        // out of scope: another legal entity's HR gets an empty list; no permission: 403
        UUID le2 = FIX.legalEntity(w.tenant(), "LE2");
        TestUser otherLe = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(le2),
                PeoplePermissions.STATUTORY_REPORT_VIEW);
        mvc.perform(get(report).param("legalEntityId", w.le().toString()).param("fromMonth", "2026-01")
                .param("toMonth", "2026-03").with(as(otherLe))).andExpect(jsonPath("$.lines", hasSize(0)));
        TestUser noPerm = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.DEPENDANT_VIEW);
        mvc.perform(get(report).param("legalEntityId", w.le().toString()).param("fromMonth", "2026-01")
                .param("toMonth", "2026-03").with(as(noPerm))).andExpect(status().isForbidden());

        // PDF through the tenant's Core template (Vietnamese text)
        String tpl = mvc.perform(post("/api/v1/core/document-templates").with(as(w.hr()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"HR_DEPENDANT_REGISTRATION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID template = UUID.fromString(JsonPath.read(tpl, "$.id"));
        String html = """
                <html><head><title>NPT</title></head><body>
                <h1>ĐĂNG KÝ NGƯỜI PHỤ THUỘC – Mẫu {{report.form_code}}</h1>
                <p>{{le.legal_name}} – Kỳ {{report.period}}: mới {{report.registered_count}}, kết thúc {{report.ended_count}}</p>
                <div style="white-space: pre-line">{{report.rows}}</div></body></html>""";
        String version = mvc.perform(multipart("/api/v1/core/document-templates/" + template + "/versions")
                        .file(new MockMultipartFile("file", "npt.html", "application/octet-stream",
                                html.getBytes(StandardCharsets.UTF_8)))
                        .param("validFrom", "2020-01-01").with(as(w.hr())))
                .andReturn().getResponse().getContentAsString();
        UUID versionId = UUID.fromString(JsonPath.read(version, "$.versions[0].id"));
        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + versionId + "/publish")
                .with(as(w.hr()))).andExpect(status().isOk());
        String pdf = mvc.perform(post(report + "/pdf").param("legalEntityId", w.le().toString())
                        .param("fromMonth", "2026-01").param("toMonth", "2026-03").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mediaType").value("application/pdf"))
                .andExpect(jsonPath("$.download.url", containsString("http")))
                .andReturn().getResponse().getContentAsString();
        UUID documentId = UUID.fromString(JsonPath.read(pdf, "$.documentId"));
        String key = ADMIN.queryForObject("""
                select object_key from core.document_version where document_id = ? order by version_no desc limit 1""",
                String.class, documentId);
        try (PDDocument d = Loader.loadPDF(new TestObjectStorage().get(w.tenant(), key))) {
            String text = new PDFTextStripper().getText(d).replaceAll("\\R", " ");
            assertThat(text).contains("ĐĂNG KÝ NGƯỜI PHỤ THUỘC").contains("07/ĐK-NPT-TNCN").contains("Nguyễn Bé Ba")
                    .contains("01/2026 - 03/2026").contains("Đăng ký").contains("Kết thúc");
        }
    }
}
