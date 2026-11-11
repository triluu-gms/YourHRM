package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Sealed;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestObjectStorage;

/**
 * The electronic labour book (BR-PPL-140, US-PPL-068): as of any date from the base tables, national id masked on
 * screen and decrypted only in exports for holders of the sensitive view, salary only with the compensation view,
 * Excel export and the PDF entry from the Core template, scope and tenant isolation.
 */
class LabourBookIT extends SeparationTestBase {

    @Autowired
    PiiCipher cipher;

    static final String BOOK = "/api/v1/people/reports/labour-book";

    void identity(TestUser hr, UUID person, String nationalId) throws Exception {
        Sealed sealed = in(hr, () -> cipher.encrypt(Subject.person(hr.tenantId(), person), "national_id", nationalId));
        ADMIN.update("""
                update hr.person set national_id_type = 'CCCD', national_id_enc = ?, national_id_last4 = ?,
                       subject_key_version = ? where id = ?""", sealed.ciphertext(), PiiCipher.last4(nationalId),
                sealed.keyVersion(), person);
    }

    static byte[] stored(UUID tenant, UUID documentId) {
        String key = ADMIN.queryForObject("""
                select object_key from core.document_version where tenant_id = ? and document_id = ?
                 order by version_no desc limit 1""", String.class, tenant, documentId);
        return new TestObjectStorage().get(tenant, key);
    }

    static String sheetText(byte[] xlsx) throws Exception {
        StringBuilder b = new StringBuilder();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.getSheetAt(0);
            for (Row r : s) {
                for (Cell c : r) {
                    b.append(c.toString()).append('|');
                }
                b.append('\n');
            }
        }
        return b.toString();
    }

    @Test
    void theBookShowsEveryStatutoryFieldAsOfADateWithMaskedIdentity() throws Exception {
        World w = world("book-asof");
        UUID emp = w.emp().employmentId();
        identity(w.hr(), w.emp().personId(), "001090000123");
        ADMIN.update("""
                insert into hr.person_address (tenant_id, id, person_id, address_type, province_code, address_line,
                       valid_from) values (?, ?, ?, 'PERMANENT', '01', 'Số 1 Tràng Tiền', ?)""",
                w.tenant(), Ids.newId(), w.emp().personId(), Date.valueOf("2020-01-01"));
        ADMIN.update("""
                insert into hr.education_qualification (tenant_id, id, person_id, qualification_level_code, is_highest)
                values (?, ?, ?, 'UNIVERSITY', true)""", w.tenant(), Ids.newId(), w.emp().personId());
        ADMIN.update("""
                insert into hr.insurance_participation (tenant_id, id, person_id, employment_id, insurance_type,
                       valid_from, is_participating, basis_rule) values (?, ?, ?, ?, 'SI', ?, true, 'FIRST_CONTRACT')""",
                w.tenant(), Ids.newId(), w.emp().personId(), emp, Date.valueOf("2024-01-01"));
        ADMIN.update("""
                insert into hr.salary_record (tenant_id, id, employment_id, pay_basis, base_salary_amount, action_code,
                       valid_from) values (?, ?, ?, 'GROSS', 20000000, 'HIRE', ?)""",
                w.tenant(), Ids.newId(), emp, Date.valueOf("2024-01-01"));
        // a later hire and a leaver
        Employee later = PPL.employee(w.tenant(), w.le(), w.unit(), "Trần", "Bình", LocalDate.of(2026, 6, 1));
        Employee leaver = PPL.employee(w.tenant(), w.le(), w.unit(), "Lê", "Chi", LocalDate.of(2024, 3, 1));
        ADMIN.update("update hr.employment set status = 'TERMINATED', end_date = ? where id = ?",
                Date.valueOf("2026-02-15"), leaver.employmentId());
        ADMIN.update("""
                insert into hr.separation (tenant_id, id, employment_id, separation_type, initiated_by, legal_ground_code,
                       last_working_date, termination_date, status)
                values (?, ?, ?, 'RESIGNATION', 'EMPLOYEE', 'EMPLOYEE_RESIGNATION', ?, ?, 'COMPLETED')""",
                w.tenant(), Ids.newId(), leaver.employmentId(), Date.valueOf("2026-02-15"), Date.valueOf("2026-02-15"));

        mvc.perform(get(BOOK).param("legalEntityId", w.le().toString()).param("asOf", "2026-01-31").with(as(w.hr())))
                .andDo(FAIL_ON_500).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.identityRevealed").value(false))
                .andExpect(jsonPath("$.entries[0].fullName").value("Nguyễn An"))
                .andExpect(jsonPath("$.entries[0].nationalId").value("••••0123"))
                .andExpect(jsonPath("$.entries[0].nationalIdType").value("CCCD"))
                .andExpect(jsonPath("$.entries[0].residenceAddress").value("Số 1 Tràng Tiền"))
                .andExpect(jsonPath("$.entries[0].qualificationLevelCode").value("UNIVERSITY"))
                .andExpect(jsonPath("$.entries[0].contractTypeCode").value("INDEFINITE"))
                .andExpect(jsonPath("$.entries[0].siParticipating").value(true))
                .andExpect(jsonPath("$.entries[0].salary.baseAmount").value(20000000))
                .andExpect(jsonPath("$.entries[0].training", hasSize(0)))
                .andExpect(jsonPath("$.entries[1].employmentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.entries[1].terminationDate").doesNotExist());
        mvc.perform(get(BOOK).param("legalEntityId", w.le().toString()).param("asOf", "2026-07-01").with(as(w.hr())))
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[?(@.employeeNo=='" + employeeNo(leaver) + "')].terminationDate")
                        .value("2026-02-15"))
                .andExpect(jsonPath("$.entries[?(@.employeeNo=='" + employeeNo(leaver) + "')].legalGroundCode")
                        .value("EMPLOYEE_RESIGNATION"))
                .andExpect(jsonPath("$.entries[?(@.employeeNo=='" + employeeNo(leaver) + "')].employmentStatus")
                        .value("TERMINATED"))
                .andExpect(jsonPath("$.entries[*].employeeNo", hasItem(employeeNo(later))));

        // without the compensation view the salary is not shown; without the sensitive view ids stay masked in exports
        TestUser clerk = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(w.le()),
                PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE);
        mvc.perform(get(BOOK).param("legalEntityId", w.le().toString()).param("asOf", "2026-01-31").with(as(clerk)))
                .andExpect(jsonPath("$.entries[0].salary").doesNotExist())
                .andExpect(jsonPath("$.salaryColumns").value(0))
                .andExpect(jsonPath("$.notes", hasItem("salary columns are hidden: hr.compensation.view is required")));
        byte[] masked = mvc.perform(get(BOOK + "/export").param("legalEntityId", w.le().toString())
                        .param("asOf", "2026-01-31").with(as(clerk)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(sheetText(masked)).contains("SỔ QUẢN LÝ LAO ĐỘNG").contains("Nguyễn An").contains("••••0123")
                .doesNotContain("001090000123");
        byte[] clear = mvc.perform(get(BOOK + "/export").param("legalEntityId", w.le().toString())
                        .param("asOf", "2026-01-31").with(as(w.hr())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(sheetText(clear)).contains("001090000123").contains("2.0E7");
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and action = 'READ_SENSITIVE'
                   and object_id = ?""", Integer.class, w.tenant(), w.emp().personId())).isPositive();
    }

    @Test
    void oneEntryIsPrintedFromTheBilingualCoreTemplate() throws Exception {
        World w = world("book-pdf");
        identity(w.hr(), w.emp().personId(), "079190004567");
        UUID template = UUID.fromString(JsonPath.read(body(postJson(w.hr(), "/api/v1/core/document-templates",
                "{\"code\":\"HR_LABOUR_BOOK_ENTRY\"}").andExpect(status().isCreated())), "$.id"));
        String html = """
                <html><body><h1>SỔ QUẢN LÝ LAO ĐỘNG</h1><p>{{employee.no}} {{employee.full_name}}</p>
                <p>CCCD {{employee.national_id}}, HĐ {{contract.type}} từ {{contract.start}}, BHXH {{si.participating}}</p>
                <p>Ngày lập {{book.as_of}}</p></body></html>""";
        String uploaded = mvc.perform(multipart("/api/v1/core/document-templates/" + template + "/versions")
                        .file(new MockMultipartFile("file", "sld.html", "text/html", html.getBytes(StandardCharsets.UTF_8)))
                        .param("validFrom", "2020-01-01").with(as(w.hr())))
                .andReturn().getResponse().getContentAsString();
        UUID version = UUID.fromString(JsonPath.read(uploaded, "$.versions[0].id"));
        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + version + "/publish")
                .with(as(w.hr()))).andExpect(status().isOk());

        String generated = body(mvc.perform(post(BOOK + "/employments/" + w.emp().employmentId() + "/pdf")
                        .param("asOf", "2026-03-01").with(as(w.hr())).contentType(MediaType.APPLICATION_JSON))
                .andDo(FAIL_ON_500).andExpect(status().isOk()));
        UUID doc = UUID.fromString(JsonPath.read(generated, "$.documentId"));
        try (PDDocument d = Loader.loadPDF(stored(w.tenant(), doc))) {
            String text = new PDFTextStripper().getText(d).replaceAll("\\R", " ");
            assertThat(text).contains("SỔ QUẢN LÝ LAO ĐỘNG").contains("Nguyễn An").contains("079190004567")
                    .contains("INDEFINITE").contains("01/01/2024").contains("01/03/2026");
        }
        assertThat(ADMIN.queryForObject("select owner_entity_type from core.document where id = ?", String.class, doc))
                .isEqualTo("hr.labour_book_entry");
    }

    @Test
    void theBookIsScopedPerLegalEntityAndTenant() throws Exception {
        World w = world("book-scope");
        UUID otherLe = FIX.legalEntity(w.tenant(), "LE2");
        TestUser outsider = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(otherLe),
                PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE);
        mvc.perform(get(BOOK).param("legalEntityId", w.le().toString()).with(as(outsider)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries", hasSize(0)));
        mvc.perform(post(BOOK + "/employments/" + w.emp().employmentId() + "/pdf").with(as(outsider)))
                .andExpect(status().isNotFound());
        TestUser noPermission = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(BOOK).param("legalEntityId", w.le().toString()).with(as(noPermission)))
                .andExpect(status().isForbidden());
        World x = world("book-scope-x");
        mvc.perform(get(BOOK).param("legalEntityId", w.le().toString()).with(as(x.hr())))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    static String employeeNo(Employee e) {
        return ADMIN.queryForObject("select employee_no from hr.employment where id = ?", String.class,
                e.employmentId());
    }
}
