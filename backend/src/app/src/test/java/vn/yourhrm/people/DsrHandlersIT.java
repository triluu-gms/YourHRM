package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.bank.BankAccountService;
import vn.yourhrm.people.internal.application.family.FamilyMemberService;
import vn.yourhrm.people.internal.application.person.PersonService;
import vn.yourhrm.people.internal.application.person.PersonService.CreatePerson;
import vn.yourhrm.people.internal.application.person.PersonService.Identifiers;
import vn.yourhrm.people.internal.application.person.PersonService.PersonData;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;
import vn.yourhrm.support.TestObjectStorage;

/** People's DSR handlers (US-PPL-088, BR-PPL-153, OUT-33): export package and anonymisation with retention. */
class DsrHandlersIT extends AssignmentTestSupport {

    static final String BANK = "DSRBANK";
    static final String RETENTION = "privacy.retention.labour_records_years";

    @Autowired
    PersonService persons;
    @Autowired
    BankAccountService banks;
    @Autowired
    FamilyMemberService family;

    @BeforeAll
    static void referenceData() {
        ADMIN.update("insert into core.bank (bank_code, short_name, name_vi) values (?, ?, ?) on conflict do nothing",
                BANK, "DSR Bank", "Ngân hàng thử DSR");
        ADMIN.update("""
                insert into core.code_list (list_code, name_vi, name_en, is_tenant_extensible, is_statutory, owner_module)
                values ('RELATIONSHIP', 'Quan hệ gia đình', 'Relationship', false, true, 'hr') on conflict do nothing""");
        ADMIN.update("""
                insert into core.code_value (tenant_id, list_code, value_code, label_vi, label_en)
                values (sys.global_tenant_id(), 'RELATIONSHIP', 'SPOUSE', 'SPOUSE', 'SPOUSE') on conflict do nothing""");
    }

    record Subject(World w, UUID person, UUID employment) {
    }

    /** A person with identifiers, an employment, a bank account, a contact and a spouse without PIT claim. */
    Subject subject(String slug) throws Exception {
        World w = world(slug);
        PersonTestData.codes(ADMIN, w.tenant());
        TestLegalParameters.define(w.tenant(), RETENTION, 5);
        UUID person = as(w.hr(), () -> persons.createOrMatch(new CreatePerson(
                new PersonData("Đặng", null, "Dữ Liệu", null, null, LocalDate.of(1988, 8, 8), "F", "VN", null, null),
                new Identifiers("CCCD", "001088000201", null, null, "0101234567", null, null, null), "APP", true))
                .personId());
        UUID employment = PPL.employment(w.tenant(), person, w.le(), "DSR1", TODAY.minusYears(3), "ACTIVE");
        PPL.primaryAssignment(w.tenant(), employment, w.le(), w.unit(), w.location(), null, TODAY.minusYears(3));
        as(w.hr(), () -> banks.createForInitialLoad(person, new BankAccountService.Attributes(BANK, null,
                "9988776655", "DANG DU LIEU", null, null, null, 1, TODAY.minusYears(3), null)));
        as(w.hr(), () -> family.createForInitialLoad(person, new FamilyMemberService.Attributes("SPOUSE", "Ngô Chồng",
                LocalDate.of(1987, 1, 1), "001087000301", null, null, null, false, false)));
        ADMIN.update("""
                insert into hr.person_contact (tenant_id, person_id, contact_type, contact_value, is_primary)
                values (?, ?, 'PERSONAL_EMAIL', 'du.lieu@example.vn', true)""", w.tenant(), person);
        return new Subject(w, person, employment);
    }

    void publishTemplate(TestUser hr) throws Exception {
        UUID template = UUID.fromString(read(postJson(hr, "/api/v1/core/document-templates",
                "{\"code\":\"HR_DSR_EXPORT\"}").andExpect(status().isCreated()), "$.id"));
        String html = "<html><body><h1>DỮ LIỆU CÁ NHÂN</h1><p>{{person.full_name}} - {{request.ref}}</p>"
                + "<p>{{export.sections}}</p><pre>{{export.content}}</pre></body></html>";
        String uploaded = body(mvc.perform(multipart("/api/v1/core/document-templates/" + template + "/versions")
                .file(new MockMultipartFile("file", "dsr.html", "text/html", html.getBytes(StandardCharsets.UTF_8)))
                .param("validFrom", "2020-01-01").with(as(hr))));
        UUID version = UUID.fromString(JsonPath.read(uploaded, "$.versions[0].id"));
        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + version + "/publish")
                .with(as(hr))).andExpect(status().isOk());
    }

    static int audits(UUID tenant, UUID person, String action) {
        return ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ? and object_id = ? "
                + "and action = ?", Integer.class, tenant, person, action);
    }

    @Test
    void theExportPackageHoldsEveryPeopleRecordDecryptedAndAReadablePdf() throws Exception {
        Subject s = subject("dsr-export");
        publishTemplate(s.w().hr());
        String pkg = body(postJson(s.w().hr(), "/api/v1/people/privacy/persons/" + s.person() + "/export",
                "{\"requestRef\":\"DSR-2026-007\"}").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(pkg, "$.data.person[0].national_id")).isEqualTo("001088000201");
        assertThat(JsonPath.<String>read(pkg, "$.data.person[0].si_number")).isEqualTo("0101234567");
        assertThat(JsonPath.<String>read(pkg, "$.data.bank_account[0].account_no")).isEqualTo("9988776655");
        assertThat(JsonPath.<List<Object>>read(pkg, "$.data.employment")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(pkg, "$.data.assignment")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(pkg, "$.data.person_contact")).hasSize(1);
        // third-party data stays masked; no ciphertext or blind index leaves
        assertThat(JsonPath.<String>read(pkg, "$.data.family_member[0].national_id_last4")).isEqualTo("0301");
        assertThat(pkg).doesNotContain("_enc").doesNotContain("_bidx").doesNotContain("001087000301");
        UUID pdf = UUID.fromString(JsonPath.read(pkg, "$.pdfDocumentId"));
        String key = ADMIN.queryForObject("select object_key from core.document_version where tenant_id = ? and "
                + "document_id = ? order by version_no desc limit 1", String.class, s.w().tenant(), pdf);
        try (PDDocument d = Loader.loadPDF(new TestObjectStorage().get(s.w().tenant(), key))) {
            String text = new PDFTextStripper().getText(d).replaceAll("\\R", " ");
            assertThat(text).contains("DỮ LIỆU CÁ NHÂN").contains("Đặng Dữ Liệu").contains("DSR-2026-007")
                    .doesNotContain("001088000201");
        }
        assertThat(audits(s.w().tenant(), s.person(), "DSR_EXPORT_STARTED")).isEqualTo(1);
        assertThat(audits(s.w().tenant(), s.person(), "DSR_EXPORT_COMPLETED")).isEqualTo(1);
        assertThat(audits(s.w().tenant(), s.person(), "READ_SENSITIVE")).isPositive();
    }

    @Test
    void anonymisationKeepsWhatTheLawRetainsThenShredsAfterTheRetentionPeriod() throws Exception {
        Subject s = subject("dsr-anon");
        String path = "/api/v1/people/privacy/persons/" + s.person() + "/anonymise";
        postJson(s.w().hr(), path, "{\"requestRef\":\"R1\",\"confirm\":true}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("employment-live"));

        // optional and labour-law data of the person: a consent-based status and a statutory one, an education
        // record, a passport and an address of each kind
        ADMIN.update("""
                insert into hr.special_status (tenant_id, person_id, status_type, valid_from)
                values (?, ?, 'PARTY_MEMBER', ?), (?, ?, 'DISABILITY', ?)""", s.w().tenant(), s.person(),
                Date.valueOf(TODAY.minusYears(2)), s.w().tenant(), s.person(), Date.valueOf(TODAY.minusYears(2)));
        ADMIN.update("insert into hr.education_qualification (tenant_id, person_id, qualification_level_code) "
                + "values (?, ?, 'UNIVERSITY')", s.w().tenant(), s.person());
        ADMIN.update("""
                insert into hr.person_identity_doc (tenant_id, person_id, doc_type, doc_no_enc, doc_no_bidx, doc_no_last4)
                values (?, ?, 'PASSPORT', decode('00', 'hex'), decode('01', 'hex'), '1234')""", s.w().tenant(),
                s.person());
        ADMIN.update("""
                insert into hr.person_address (tenant_id, person_id, address_type, country_code, address_line, valid_from)
                values (?, ?, 'PERMANENT', 'LA', 'Số 1', ?), (?, ?, 'CURRENT', 'LA', 'Số 2', ?)""", s.w().tenant(),
                s.person(), Date.valueOf(TODAY.minusYears(3)), s.w().tenant(), s.person(),
                Date.valueOf(TODAY.minusYears(3)));

        // left a year ago: retention runs (5 years), only optional data goes
        ADMIN.update("update hr.employment set status = 'TERMINATED', end_date = ? where id = ?",
                Date.valueOf(TODAY.minusYears(1)), s.employment());
        String restricted = body(postJson(s.w().hr(), path, "{\"requestRef\":\"R1\",\"confirm\":true}")
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(restricted, "$.mode")).isEqualTo("RESTRICTED");
        assertThat(JsonPath.<String>read(restricted, "$.retainedUntil")).isEqualTo(TODAY.plusYears(4).toString());
        assertThat(JsonPath.<Integer>read(restricted, "$.shreddedSubjects")).isEqualTo(1);
        assertThat(count("select count(*) from hr.person_contact where person_id = ?", s.person())).isZero();
        assertThat(ADMIN.queryForMap("select status, account_no_last4 from hr.bank_account where person_id = ?",
                s.person())).containsEntry("status", "CLOSED").containsEntry("account_no_last4", "****");
        // the spouse has no PIT claim: deleted (DDL v0.6 DELETE grant), its subject key destroyed
        assertThat(count("select count(*) from hr.family_member where person_id = ?", s.person())).isZero();
        assertThat(count("select count(*) from hr.special_status where person_id = ?", s.person())).isEqualTo(1);
        assertThat(count("select count(*) from hr.education_qualification where person_id = ?", s.person()))
                .isEqualTo(1);
        assertThat(count("select count(*) from hr.person_identity_doc where person_id = ?", s.person())).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select address_type from hr.person_address where person_id = ?", String.class,
                s.person())).isEqualTo("PERMANENT");
        assertThat(ADMIN.queryForMap("select is_anonymised, national_id_enc is not null as nid, full_name from hr.person "
                + "where id = ?", s.person())).containsEntry("is_anonymised", true).containsEntry("nid", true)
                .containsEntry("full_name", "Đặng Dữ Liệu");

        // retention over: identity anonymised, the subject key destroyed, employment facts kept
        ADMIN.update("update hr.employment set hire_date = ?, original_hire_date = ?, seniority_date = ?, end_date = ? "
                        + "where id = ?", Date.valueOf(TODAY.minusYears(12)), Date.valueOf(TODAY.minusYears(12)),
                Date.valueOf(TODAY.minusYears(12)), Date.valueOf(TODAY.minusYears(10)), s.employment());
        String full = body(postJson(s.w().hr(), path, "{\"requestRef\":\"R2\",\"confirm\":true}")
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(full, "$.mode")).isEqualTo("FULL");
        assertThat(JsonPath.<Object>read(full, "$.retainedUntil")).isNull();
        assertThat(ADMIN.queryForMap("select family_name, national_id_enc, si_number_bidx, birth_date from hr.person "
                + "where id = ?", s.person())).containsEntry("family_name", "Đã ẩn danh")
                .containsEntry("national_id_enc", null).containsEntry("si_number_bidx", null)
                .containsEntry("birth_date", null);
        assertThat(count("select count(*) from hr.employment where id = ?", s.employment())).isEqualTo(1);
        assertThat(count("select count(*) from hr.person_address where person_id = ?", s.person())).isZero();
        for (String table : List.of("bank_account", "family_member", "special_status", "education_qualification",
                "person_identity_doc", "work_permit")) {
            assertThat(count("select count(*) from hr." + table + " where person_id = ?", s.person())).as(table)
                    .isZero();
        }
        assertThat(audits(s.w().tenant(), s.person(), "DSR_CRYPTO_SHREDDED")).isEqualTo(2);
        assertThat(audits(s.w().tenant(), s.person(), "DSR_ANONYMISE_COMPLETED")).isEqualTo(2);
        assertThat(events(s.w().tenant(), "people.PersonalDataChanged")).anySatisfy(p ->
                assertThat(p).contains("PERSON_ANONYMISED").doesNotContain("001088000201"));
    }

    @Test
    void theHandlersNeedTheDsrPermissionAConfirmationAndStayInTheTenant() throws Exception {
        Subject s = subject("dsr-perm");
        TestUser viewer = PPL.userWith(s.w().tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW,
                PeoplePermissions.PERSON_VIEW_SENSITIVE);
        postJson(viewer, "/api/v1/people/privacy/persons/" + s.person() + "/export", "{}")
                .andExpect(status().isForbidden());
        postJson(s.w().hr(), "/api/v1/people/privacy/persons/" + s.person() + "/anonymise", "{\"confirm\":false}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("confirmation-required"));
        World other = world("dsr-perm-other");
        postJson(other.hr(), "/api/v1/people/privacy/persons/" + s.person() + "/export", "{}")
                .andExpect(status().isNotFound());
        postJson(other.hr(), "/api/v1/people/privacy/persons/" + s.person() + "/anonymise", "{\"confirm\":true}")
                .andExpect(status().isNotFound());
        assertThat(ADMIN.queryForObject("select is_anonymised from hr.person where id = ?", Boolean.class, s.person()))
                .isFalse();
    }

    static int count(String sql, Object... args) {
        return ADMIN.queryForObject(sql, Integer.class, args);
    }
}
