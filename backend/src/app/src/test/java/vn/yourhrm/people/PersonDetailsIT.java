package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonJobs;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Addresses (US-PPL-005), contacts (US-PPL-006), identity documents with scan, expiring list and expiry alerts
 * (US-PPL-007), and the minimal qualification (US-PPL-010).
 */
class PersonDetailsIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final String PERSONS = "/api/v1/people/persons/";

    @Autowired
    PersonJobs jobs;
    @Autowired
    BusinessClock clock;

    @BeforeAll
    static void referenceData() {
        PersonTestData.adminUnits(ADMIN);
    }

    record World(UUID tenant, UUID le, OrgUnit unit, TestUser hr, Employee emp) {
    }

    World world(String name) {
        UUID tenant = FIX.tenant(name);
        PersonTestData.codes(ADMIN, tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Nguyễn", "An", LocalDate.of(2025, 1, 6));
        return new World(tenant, le, unit, PPL.hrAdmin(tenant), emp);
    }

    ResultActions postJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(post(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions putJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(put(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static UUID id(ResultActions r) throws Exception {
        return UUID.fromString(JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", null), call);
    }

    @Test
    void addressesAreEffectiveDatedOnTwoLevelUnitsAndLegacyOnesAreReadOnly() throws Exception {
        World w = world("ppl-addr");
        String url = PERSONS + w.emp().personId() + "/addresses";
        UUID first = id(postJson(w.hr(), url, """
                {"addressType": "PERMANENT", "validFrom": "2025-08-01",
                 "address": {"provinceCode": "PT79", "communeCode": "PT79-001", "addressLine": "12 Lê Lợi"}}""")
                .andExpect(status().isCreated()));
        // a move from 2026-03-01 closes the first address (history kept, valid_to exclusive)
        postJson(w.hr(), url, """
                {"addressType": "PERMANENT", "validFrom": "2026-03-01",
                 "address": {"provinceCode": "PT01", "communeCode": "PT01-001", "addressLine": "1 Tràng Tiền"}}""")
                .andExpect(status().isCreated());
        mvc.perform(get(url).with(as(w.hr()))).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.toString()))
                .andExpect(jsonPath("$[0].validTo").value("2026-03-01"));
        mvc.perform(get(url + "?asOf=2026-01-15").with(as(w.hr()))).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].provinceCode").value("PT79"));
        // validation against Core master data on the date: commune in province, unit in effect
        postJson(w.hr(), url, """
                {"addressType": "CURRENT", "validFrom": "2026-03-01",
                 "address": {"provinceCode": "PT79", "communeCode": "PT01-001"}}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-admin-unit"));
        postJson(w.hr(), url, """
                {"addressType": "CURRENT", "validFrom": "2025-01-01",
                 "address": {"provinceCode": "PT79", "communeCode": "PT79-001"}}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("admin-unit-not-in-effect"));
        postJson(w.hr(), url, """
                {"addressType": "PERMANENT", "validFrom": "2026-01-01",
                 "address": {"provinceCode": "PT79", "communeCode": "PT79-002"}}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("address-history-conflict"));
        assertThat(ADMIN.queryForObject("""
                select count(*) from hr.outbox_event where tenant_id = ? and event_type = 'people.PersonalDataChanged'
                   and payload->'changedFields' ? 'address.PERMANENT'""", Long.class, w.tenant())).isEqualTo(2L);

        // an imported three-level address is kept read-only
        UUID legacy = UUID.randomUUID();
        ADMIN.update("""
                insert into hr.person_address (tenant_id, id, person_id, address_type, legacy_province_code,
                       legacy_district_code, legacy_commune_code, legacy_address_text, mapping_status, valid_from, source)
                values (?, ?, ?, 'HOMETOWN', 'PTL79', 'PTL79-D1', 'PTL79-D1-C1', 'Bến Nghé, Q1', 'UNMAPPED', '1990-01-01',
                        'OPENING_LOAD')""", w.tenant(), legacy, w.emp().personId());
        putJson(w.hr(), url + "/" + legacy, """
                {"version": 0, "address": {"provinceCode": "PT79", "communeCode": "PT79-001"}}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("legacy-address-read-only"));
        mvc.perform(get(url).with(as(w.hr()))).andExpect(jsonPath("$[?(@.addressType == 'HOMETOWN')].readOnly").value(true));
    }

    @Test
    void contactsKeepOnePrimaryPerTypeAndFlagThePersonalEmail() throws Exception {
        World w = world("ppl-contact");
        String url = PERSONS + w.emp().personId() + "/contacts";
        UUID mail1 = id(postJson(w.hr(), url, """
                {"contactType": "PERSONAL_EMAIL", "value": "An.Nguyen@Example.com"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.primary").value(true))
                .andExpect(jsonPath("$.value").value("an.nguyen@example.com"))
                .andExpect(jsonPath("$.usableForFormerEmployeeLogin").value(true)));
        UUID mail2 = id(postJson(w.hr(), url, """
                {"contactType": "PERSONAL_EMAIL", "value": "an2@example.com", "primary": true}""")
                .andExpect(status().isCreated()));
        postJson(w.hr(), url, """
                {"contactType": "MOBILE", "value": "+84 912 345 678"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.value").value("+84912345678"));
        postJson(w.hr(), url, """
                {"contactType": "PERSONAL_EMAIL", "value": "not-an-email"}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-email"));
        mvc.perform(get(url).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.id == '" + mail1 + "')].primary").value(false))
                .andExpect(jsonPath("$[?(@.id == '" + mail2 + "')].primary").value(true));
        mvc.perform(delete(url + "/" + mail2).with(as(w.hr()))).andExpect(status().isNoContent());
        mvc.perform(get(url).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.id == '" + mail1 + "')].primary").value(true));
        // read-only user cannot write
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        postJson(viewer, url, """
                {"contactType": "MOBILE", "value": "0912345678"}""").andExpect(status().isForbidden());
    }

    @Test
    void identityDocumentsAreEncryptedScannedListedAndAlertedBeforeExpiry() throws Exception {
        World w = world("ppl-iddoc");
        String url = PERSONS + w.emp().personId() + "/identity-documents";
        LocalDate today = clock.today();
        LocalDate expiry = today.plusDays(30);                         // default alert offset: 30 days
        UUID passport = id(postJson(w.hr(), url, """
                {"docType": "PASSPORT", "docNo": "B 1234567", "issuingCountryCode": "VN", "issueDate": "2020-01-10",
                 "issuePlace": "Cục QLXNC", "expiryDate": "%s"}""".formatted(expiry))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.docNoMasked").value("••••4567")));
        // the same live passport cannot be recorded for another person (BR-PPL-001)
        Employee other = PPL.employee(w.tenant(), w.le(), w.unit(), "Trần", "Bảo", LocalDate.of(2025, 1, 6));
        postJson(w.hr(), PERSONS + other.personId() + "/identity-documents", """
                {"docType": "PASSPORT", "docNo": "B1234567", "issuingCountryCode": "VN"}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate-person"));
        postJson(w.hr(), url, """
                {"docType": "WORK_VISA", "docNo": "V998877", "issuingCountryCode": "VN", "visaTypeCode": "XX"}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-code"));

        // scan through Core's document service, then linked to the document
        String body = mvc.perform(multipart("/api/v1/core/documents")
                        .file(new MockMultipartFile("file", "passport.pdf", "application/pdf",
                                "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII)))
                        .param("documentTypeCode", "HR_IDENTITY_DOC_SCAN").param("ownerEntityId", passport.toString())
                        .with(as(w.hr())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID scan = UUID.fromString(JsonPath.read(body, "$.id"));
        putJson(w.hr(), url + "/" + passport + "/scan", "{\"documentId\": \"" + scan + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.scanDocumentId").value(scan.toString()));
        putJson(w.hr(), url + "/" + passport + "/scan", "{\"documentId\": \"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-document"));

        // unmask the number: audited
        postJson(w.hr(), url + "/" + passport + "/unmask", "{\"purpose\": \"visa application\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.docNo").value("B1234567"));

        // expiring list (scoped) and the nightly alert
        mvc.perform(get(PERSONS + "expiring-identity-documents?withinDays=31").with(as(w.hr())))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].daysLeft").value(30))
                .andExpect(jsonPath("$[0].employeeNos[0]").isString());
        UUID le2 = FIX.legalEntity(w.tenant(), "LE2");
        TestUser le2Hr = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(le2), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(PERSONS + "expiring-identity-documents?withinDays=31").with(as(le2Hr)))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(as(w.hr(), () -> jobs.identityDocumentExpiryAlerts(today))).isEqualTo(1);
        assertThat(as(w.hr(), () -> jobs.identityDocumentExpiryAlerts(today))).isZero();     // idempotent
        String text = ADMIN.queryForObject("""
                select body from core.notification where tenant_id = ? and event_code = 'hr.IdentityDocumentExpiring'
                   and recipient_user_id = ?""", String.class, w.tenant(), w.hr().userId());
        assertThat(text).contains("PASSPORT").doesNotContain("B1234567");
        // a document past its expiry date becomes EXPIRED
        as(w.hr(), () -> jobs.identityDocumentExpiryAlerts(expiry.plusDays(1)));
        assertThat(ADMIN.queryForObject("select status from hr.person_identity_doc where id = ?", String.class, passport))
                .isEqualTo("EXPIRED");
    }

    @Test
    void oneQualificationIsTheHighest() throws Exception {
        World w = world("ppl-qual");
        String url = PERSONS + w.emp().personId() + "/qualifications";
        UUID college = id(postJson(w.hr(), url, """
                {"qualificationLevelCode": "COLLEGE", "fieldOfStudy": "Kế toán", "graduationYear": 2010}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.highest").value(true)));
        UUID university = id(postJson(w.hr(), url, """
                {"qualificationLevelCode": "UNIVERSITY", "fieldOfStudy": "Kinh tế", "graduationYear": 2014, "highest": true}""")
                .andExpect(status().isCreated()));
        mvc.perform(get(url).with(as(w.hr()))).andExpect(jsonPath("$[0].id").value(university.toString()))
                .andExpect(jsonPath("$[?(@.id == '" + college + "')].highest").value(false));
        assertThat(ADMIN.queryForObject("select highest_qualification_code from hr.v_labour_book where employment_id = ?",
                String.class, w.emp().employmentId())).isEqualTo("UNIVERSITY");
        postJson(w.hr(), url, """
                {"qualificationLevelCode": "PHD"}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-code"));
        putJson(w.hr(), url + "/" + university, """
                {"version": 0, "qualification": {"qualificationLevelCode": "MASTER", "highest": false}}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("highest-qualification-required"));
    }
}
