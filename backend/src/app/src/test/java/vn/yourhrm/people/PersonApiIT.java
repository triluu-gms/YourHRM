package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Persons (US-PPL-002/003/008/009/085/086): creation with encrypted identifiers, duplicate detection inside the tenant
 * only, masked reads, audited unmasking, consent for optional sensitive data, tax-code defaulting and legacy MST
 * history, and scope.
 */
class PersonApiIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final String PERSONS = "/api/v1/people/persons";

    @BeforeAll
    static void referenceData() {
        PersonTestData.adminUnits(ADMIN);
    }

    record World(UUID tenant, UUID le, OrgUnit unit, TestUser hr) {
    }

    World world(String name) {
        UUID tenant = FIX.tenant(name);
        PersonTestData.codes(ADMIN, tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        TestLegalParameters.define(tenant, "person.tax_code.personal_id_as_tax_code_from", "2025-07-01");
        return new World(tenant, le, unit, PPL.hrAdmin(tenant));
    }

    ResultActions postJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(post(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions putJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(put(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String person(String family, String given, String nationalId, String extraIdentifiers) {
        return """
                {"person": {"familyName": "%s", "middleName": "Văn", "givenName": "%s", "birthDate": "1990-05-17",
                            "genderCode": "M", "nationalityCode": "VN"},
                 "identifiers": {"nationalIdType": "CCCD", "nationalId": "%s", "ethnicityCode": "KINH" %s}}"""
                .formatted(family, given, nationalId, extraIdentifiers);
    }

    UUID create(World w, String json) throws Exception {
        String body = postJson(w.hr(), PERSONS, json).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    @Test
    void aPersonIsCreatedWithEncryptedIdentifiersAndMaskedResponses() throws Exception {
        World w = world("ppl-person-create");
        String body = postJson(w.hr(), PERSONS, person("Nguyễn", "Ánh", "001 090-012345", ", \"siNumber\": \"0123456789\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Nguyễn Văn Ánh"))
                .andExpect(jsonPath("$.nationalIdMasked").value("••••2345"))
                // BR-PPL-013: the tax code defaults to the 12-digit personal id
                .andExpect(jsonPath("$.taxCodeMasked").value("••••2345"))
                .andExpect(jsonPath("$.siNumberMasked").value("••••6789"))
                .andExpect(jsonPath("$.birthDate").value("1990-01-01"))   // masked by default
                .andExpect(jsonPath("$.ethnicityRecorded").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(ADMIN.queryForObject("select full_name_search from hr.person where id = ?", String.class, id))
                .isEqualTo("nguyen van anh");
        byte[] enc = ADMIN.queryForObject("select national_id_enc from hr.person where id = ?", byte[].class, id);
        assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("001090012345");
        String payload = ADMIN.queryForObject("""
                select payload::text from hr.outbox_event where tenant_id = ? and person_id = ?
                   and event_type = 'people.PersonalDataChanged'""", String.class, w.tenant(), id);
        assertThat(payload).contains("PERSON_CREATED", "national_id", "tax_code").doesNotContain("001090012345")
                .doesNotContain("0123456789");
        // row audit (US-PPL-087): the insert is audited, encrypted columns are not in the diff
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and object_schema = 'hr'
                   and object_type = 'person' and object_id = ? and action = 'INSERT'""", Long.class, w.tenant(), id))
                .isEqualTo(1L);
        assertThat(ADMIN.queryForObject("select change::text from audit.audit_event where tenant_id = ? and object_id = ? and action = 'INSERT'",
                String.class, w.tenant(), id)).doesNotContain("001090012345");
    }

    @Test
    void duplicatesAreBlockedOrWarnedWithinTheTenantOnly() throws Exception {
        World w = world("ppl-person-dup");
        World other = world("ppl-person-dup-other");
        UUID first = create(w, person("Trần", "Bình", "079090000111",
                ", \"taxCode\": \"8001234567\", \"siNumber\": \"7900001111\""));

        // same CCCD: hard block (409 duplicate-person)
        postJson(w.hr(), PERSONS, person("Trần", "Bình", "079-090-000-111", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate-person"))
                .andExpect(jsonPath("$.personId").value(first.toString()));
        // same tax code or SI number: warning with candidates until acknowledged
        String warn = person("Lê", "Cường", "079090000222", ", \"siNumber\": \"7900001111\"");
        postJson(w.hr(), PERSONS, warn)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("possible-duplicate-person"))
                .andExpect(jsonPath("$.candidates[0].personId").value(first.toString()));
        postJson(w.hr(), PERSONS + "/match", """
                {"taxCode": "8001234567", "nationalIdType": "CCCD", "nationalId": "079090000111"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking[0].personId").value(first.toString()))
                .andExpect(jsonPath("$.blocking[0].birthYear").value(1990))
                .andExpect(jsonPath("$.warnings.length()").value(0));
        postJson(w.hr(), PERSONS, warn.replace("}}", "}, \"acknowledgeWarnings\": true}"))
                .andExpect(status().isCreated());
        // never across tenants (NFR-PPL-008): the same CCCD is a new person in another tenant
        postJson(other.hr(), PERSONS, person("Trần", "Bình", "079090000111", ""))
                .andExpect(status().isCreated());
        postJson(other.hr(), PERSONS + "/match", """
                {"nationalIdType": "CCCD", "nationalId": "079090000222"}""")
                .andExpect(jsonPath("$.blocking.length()").value(0));
    }

    @Test
    void aForeignerIsIdentifiedByPassportAndNationality() throws Exception {
        World w = world("ppl-person-passport");
        String expat = """
                {"person": {"familyName": "Smith", "givenName": "John", "nationalityCode": "GB", "latinName": "SMITH JOHN"},
                 "identifiers": {"nationalIdType": "PASSPORT", "nationalId": "%s"}}""";
        create(w, expat.formatted("C1234567"));
        postJson(w.hr(), PERSONS, expat.formatted("c-1234567"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate-person"));
        // the same number from another country is another passport
        postJson(w.hr(), PERSONS, expat.formatted("C1234567").replace("\"GB\"", "\"US\""))
                .andExpect(status().isCreated());
    }

    @Test
    void identifiersChangeKeepsTheLegacyMstAndUnmaskingIsAudited() throws Exception {
        World w = world("ppl-person-ident");
        UUID id = create(w, person("Phạm", "Dũng", "001090055555", ", \"taxCode\": \"0101234567\""));
        assertThat(ADMIN.queryForObject("select tax_code_last4 from hr.person where id = ?", String.class, id))
                .isEqualTo("4567");

        // the 10-digit MST is replaced by the personal id: the old code stays as history (US-PPL-008)
        putJson(w.hr(), PERSONS + "/" + id + "/identifiers", """
                {"version": 0, "set": {"taxCode": "001090055555", "hiCardNo": "DN4797912345678"}}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.taxCodeMasked").value("••••5555"))
                .andExpect(jsonPath("$.hiCardNoMasked").value("••••5678"));
        assertThat(ADMIN.queryForList("""
                select status from hr.person_identity_doc where person_id = ? and doc_type = 'OTHER'
                   and visa_type_code = 'LEGACY_TAX_CODE' and doc_no_last4 = '4567'""", String.class, id))
                .containsExactly("SUPERSEDED");
        putJson(w.hr(), PERSONS + "/" + id + "/identifiers", """
                {"version": 0, "set": {"siNumber": "123"}}""")
                .andExpect(status().isConflict());
        putJson(w.hr(), PERSONS + "/" + id + "/identifiers", """
                {"version": 1, "set": {"siNumber": "123"}}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-si-number"));

        // unmask: purpose required, values shown, sensitive read + field / purpose audited (US-PPL-085/087)
        postJson(w.hr(), PERSONS + "/" + id + "/unmask", """
                {"fields": ["NATIONAL_ID"], "purpose": ""}""").andExpect(status().isBadRequest());
        postJson(w.hr(), PERSONS + "/" + id + "/unmask", """
                {"fields": ["NATIONAL_ID", "TAX_CODE", "BIRTH_DATE", "ETHNICITY", "RELIGION"], "purpose": "BHXH declaration"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.NATIONAL_ID").value("001090055555"))
                .andExpect(jsonPath("$.values.BIRTH_DATE").value("1990-05-17"))
                .andExpect(jsonPath("$.values.ETHNICITY").value("KINH"))
                .andExpect(jsonPath("$.withheld[0]").value("RELIGION"));
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and object_id = ? and action = 'READ_SENSITIVE'""",
                Long.class, w.tenant(), id)).isEqualTo(1L);
        assertThat(ADMIN.queryForObject("""
                select change::text from audit.audit_event where tenant_id = ? and object_id = ? and action = 'UNMASK'""",
                String.class, w.tenant(), id)).contains("BHXH declaration", "NATIONAL_ID");

        // without hr.person.view_sensitive: forbidden
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        postJson(viewer, PERSONS + "/" + id + "/unmask", """
                {"fields": ["NATIONAL_ID"], "purpose": "curious"}""").andExpect(status().isForbidden());
    }

    @Test
    void religionIsOptionalSensitiveDataAndNeedsConsent() throws Exception {
        World w = world("ppl-person-consent");
        // the consent registry stub (SaaS not built) grants nothing: religion is refused (BR-PPL-151)
        postJson(w.hr(), PERSONS, person("Võ", "Em", "001090077777", ", \"religionCode\": \"BUDDHISM\""))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("consent-required"));
        postJson(w.hr(), PERSONS, person("Võ", "Em", "001090077777", ", \"religionCode\": \"NO_SUCH\""))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-code"));
    }

    @Test
    void updatesPublishTheChangedFieldsAndDeriveAgeStatuses() throws Exception {
        World w = world("ppl-person-update");
        TestLegalParameters.define(w.tenant(), "person.minor.age_years", 18);
        TestLegalParameters.define(w.tenant(), "person.elderly_worker.age_months", java.util.Map.of("M", 744, "F", 720));
        UUID id = create(w, person("Đỗ", "Giang", "001090088888", ""));
        putJson(w.hr(), PERSONS + "/" + id, """
                {"version": 0, "person": {"familyName": "Đỗ", "middleName": "Văn", "givenName": "Giang",
                 "birthDate": "2010-03-01", "genderCode": "M", "nationalityCode": "VN", "hiFacilityCode": "01-001",
                 "siBookCustody": "EMPLOYER"}}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.siBookCustody").value("EMPLOYER"));
        String payload = ADMIN.queryForObject("""
                select payload::text from hr.outbox_event where tenant_id = ? and person_id = ?
                   and event_type = 'people.PersonalDataChanged' and payload->>'reason' = 'PERSON_UPDATED'""",
                String.class, w.tenant(), id);
        assertThat(payload).contains("birth_date", "hi_facility_code", "si_book_custody").doesNotContain("family_name");
        // minor until the 18th birthday, elderly from 62 (744 months), both derived (US-PPL-012)
        assertThat(ADMIN.queryForList("""
                select status_type || ' ' || valid_from || ' ' || coalesce(valid_to::text, '-') from hr.special_status
                 where person_id = ? and status = 'ACTIVE' order by status_type""", String.class, id))
                .containsExactly("ELDERLY_WORKER 2072-03-01 -", "MINOR 2010-03-01 2028-03-01");
        putJson(w.hr(), PERSONS + "/" + id, """
                {"version": 1, "person": {"familyName": "Đỗ", "givenName": "Giang", "birthDate": "2999-01-01"}}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-birth-date"));
    }

    @Test
    void personsAreReadOnlyThroughAnEmploymentInScope() throws Exception {
        World w = world("ppl-person-scope");
        UUID le2 = FIX.legalEntity(w.tenant(), "LE2");
        OrgUnit unit2 = FIX.orgUnit(w.tenant(), "U2", null, le2, null, TestFixtures.EPOCH, null);
        Employee inLe1 = PPL.employee(w.tenant(), w.le(), w.unit(), "Hồ", "Hoa", LocalDate.of(2026, 1, 5));
        Employee inLe2 = PPL.employee(w.tenant(), le2, unit2, "Lý", "Khoa", LocalDate.of(2026, 1, 5));
        TestUser le1Hr = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(w.le()), PeoplePermissions.EMPLOYEE_VIEW,
                PeoplePermissions.PERSON_UPDATE);
        mvc.perform(get(PERSONS + "/" + inLe1.personId()).with(as(le1Hr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Hồ Hoa"));
        mvc.perform(get(PERSONS + "/" + inLe2.personId()).with(as(le1Hr))).andExpect(status().isNotFound());
        // cross-tenant: invisible
        World other = world("ppl-person-scope-x");
        mvc.perform(get(PERSONS + "/" + inLe1.personId()).with(as(other.hr()))).andExpect(status().isNotFound());
    }
}
