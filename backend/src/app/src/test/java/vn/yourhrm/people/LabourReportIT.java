package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.ReportJobs;
import vn.yourhrm.people.internal.application.report.ReportKeys;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Sealed;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * The 6-monthly labour change report, the monthly labour fluctuation notice and the statutory register (BR-PPL-141/142,
 * US-PPL-069/070/071): counts from People's history, generation with file versions, submission and receipt, one live
 * report per legal entity × type × period, deadline alerts, legal-entity scope.
 */
class LabourReportIT extends SeparationTestBase {

    static final String REPORTS = "/api/v1/people/statutory-reports";

    @Autowired
    ReportJobs jobs;

    @Autowired
    PiiCipher cipher;

    @BeforeAll
    static void reportParameters() {
        TestLegalParameters.define(ReportKeys.CHANGE_6M_PERIODS, Map.of(
                "JUNE", Map.of("start_month", 1, "end_month", 6, "due_month", 6, "due_day", 5),
                "DECEMBER", Map.of("start_month", 7, "end_month", 12, "due_month", 12, "due_day", 5)));
        TestLegalParameters.define(ReportKeys.CHANGE_6M_FORM, Map.of("form_code", "01/PLI",
                "title_vi", "Báo cáo tình hình thay đổi về lao động"));
        TestLegalParameters.define(ReportKeys.FLUCTUATION_DUE, Map.of("month_offset", 1, "day", 3));
        TestLegalParameters.define(ReportKeys.FLUCTUATION_LAYOUT, Map.of("form_code", "TB-BDLD",
                "title_vi", "Thông báo biến động lao động",
                "columns", List.of("employee_no", "full_name", "national_id", "movement_type", "movement_date", "reason")));
    }

    /** World employee (2024, indefinite) + A (2025, no contract) + B hired Feb 2026 + C left 31 Mar 2026 + a pre-hire. */
    record Data(World w, Employee hired, Employee leaver) {
    }

    Data data(String name) throws Exception {
        World w = world(name);
        PPL.employee(w.tenant(), w.le(), w.unit(), "Phạm", "An", LocalDate.of(2025, 6, 1));
        Employee hired = PPL.employee(w.tenant(), w.le(), w.unit(), "Trần", "Bình", LocalDate.of(2026, 2, 10));
        Employee leaver = PPL.employee(w.tenant(), w.le(), w.unit(), "Lê", "Chi", LocalDate.of(2024, 3, 1));
        ADMIN.update("update hr.employment set status = 'TERMINATED', end_date = ? where id = ?",
                Date.valueOf("2026-03-31"), leaver.employmentId());
        ADMIN.update("""
                insert into hr.separation (tenant_id, id, employment_id, separation_type, initiated_by, legal_ground_code,
                       last_working_date, termination_date, status)
                values (?, ?, ?, 'RESIGNATION', 'EMPLOYEE', 'EMPLOYEE_RESIGNATION', ?, ?, 'COMPLETED')""",
                w.tenant(), Ids.newId(), leaver.employmentId(), Date.valueOf("2026-03-31"), Date.valueOf("2026-03-31"));
        UUID p = PPL.person(w.tenant(), "Đỗ", "Em", LocalDate.of(2000, 1, 1));
        PPL.employment(w.tenant(), p, w.le(), "PH" + p.toString().substring(28), LocalDate.of(2026, 4, 1), "PRE_HIRE");
        Sealed sealed = in(w.hr(), () -> cipher.encrypt(Subject.person(w.tenant(), hired.personId()), "national_id",
                "001200009876"));
        ADMIN.update("update hr.person set national_id_enc = ?, national_id_last4 = '9876', subject_key_version = ? "
                + "where id = ?", sealed.ciphertext(), sealed.keyVersion(), hired.personId());
        return new Data(w, hired, leaver);
    }

    @Test
    void theSixMonthlyReportCountsHistoryAndIsRegisteredWithVersionsSubmissionAndReceipt() throws Exception {
        Data d = data("rep-6m");
        World w = d.w();
        mvc.perform(get(REPORTS + "/labour-change").param("legalEntityId", w.le().toString()).param("year", "2026")
                        .param("period", "JUNE").with(as(w.hr())))
                .andDo(FAIL_ON_500).andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-06-30"))
                .andExpect(jsonPath("$.dueDate").value("2026-06-05"))
                .andExpect(jsonPath("$.formCode").value("01/PLI"))
                .andExpect(jsonPath("$.headcountStart").value(3))
                .andExpect(jsonPath("$.hires").value(1))
                .andExpect(jsonPath("$.terminations").value(1))
                .andExpect(jsonPath("$.headcountEnd").value(3))
                .andExpect(jsonPath("$.femaleEnd").value(3))
                .andExpect(jsonPath("$.terminationsByType.RESIGNATION").value(1))
                .andExpect(jsonPath("$.hiresByType.NEW").value(1))
                .andExpect(jsonPath("$.contractTypesEnd.INDEFINITE").value(1))
                .andExpect(jsonPath("$.contractTypesEnd.NONE").value(2));
        postJson(w.hr(), REPORTS, "{\"legalEntityId\":\"" + w.le() + "\",\"reportType\":\"LABOUR_CHANGE_6M\","
                + "\"year\":2026,\"period\":\"MARCH\"}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-report-period"));
        postJson(w.hr(), REPORTS, "{\"legalEntityId\":\"" + w.le() + "\",\"reportType\":\"FOREIGN_WORKER\",\"year\":2026}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("report-type-not-supported"));

        String gen = "{\"legalEntityId\":\"" + w.le() + "\",\"reportType\":\"LABOUR_CHANGE_6M\",\"year\":2026,"
                + "\"period\":\"JUNE\",\"notes\":{\"HIRES\":\"Tuyển mới bộ phận kho\"}}";
        String first = body(postJson(w.hr(), REPORTS, gen).andExpect(status().isCreated())
                .andExpect(jsonPath("$.report.status").value("GENERATED"))
                .andExpect(jsonPath("$.report.dueDate").value("2026-06-05"))
                .andExpect(jsonPath("$.report.templateVersion").value("FORM 01/PLI"))
                .andExpect(jsonPath("$.report.versions", hasSize(1)))
                .andExpect(jsonPath("$.content.headcountEnd").value(3)));
        UUID id = UUID.fromString(JsonPath.read(first, "$.report.id"));
        UUID doc = UUID.fromString(JsonPath.read(first, "$.report.generatedDocumentId"));
        String sheet = LabourBookIT.sheetText(LabourBookIT.stored(w.tenant(), doc));
        assertThat(sheet).contains("BÁO CÁO TÌNH HÌNH THAY ĐỔI VỀ LAO ĐỘNG").contains("01/PLI")
                .contains("Số lao động cuối kỳ").contains("Tuyển mới bộ phận kho");
        // v0.6: HR's review notes are stored on the report
        assertThat(ADMIN.queryForObject("select hr_notes from hr.statutory_report where id = ?", String.class, id))
                .contains("Tuyển mới bộ phận kho");
        // regenerating keeps the report and adds a file version; without new notes the stored ones are re-printed
        String regen = "{\"legalEntityId\":\"" + w.le() + "\",\"reportType\":\"LABOUR_CHANGE_6M\",\"year\":2026,"
                + "\"period\":\"JUNE\"}";
        postJson(w.hr(), REPORTS, regen).andExpect(status().isCreated())
                .andExpect(jsonPath("$.report.id").value(id.toString()))
                .andExpect(jsonPath("$.report.generatedDocumentId").value(doc.toString()))
                .andExpect(jsonPath("$.report.hrNotes.HIRES").value("Tuyển mới bộ phận kho"))
                .andExpect(jsonPath("$.report.versions", hasSize(2)));
        assertThat(LabourBookIT.sheetText(LabourBookIT.stored(w.tenant(), doc))).contains("Tuyển mới bộ phận kho");

        int v = ADMIN.queryForObject("select version from hr.statutory_report where id = ?", Integer.class, id);
        postJson(w.hr(), REPORTS + "/" + id + "/submit", "{\"version\":" + v + ",\"submittedOn\":\""
                + LocalDate.now().plusDays(3) + "\",\"channel\":\"PORTAL\"}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-submission-date"));
        postJson(w.hr(), REPORTS + "/" + id + "/submit", "{\"version\":" + v + ",\"submittedOn\":\"2026-06-03\","
                + "\"channel\":\"PORTAL\",\"submissionRef\":\"DVC-123\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submissionRef").value("DVC-123"))
                .andExpect(jsonPath("$.submittedAt").exists());
        postJson(w.hr(), REPORTS, gen).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("report-already-submitted"));
        mvc.perform(multipart(REPORTS + "/" + id + "/receipt")
                        .file(new MockMultipartFile("file", "bien-nhan.pdf", "application/pdf",
                                "%PDF-1.4 receipt".getBytes(StandardCharsets.US_ASCII)))
                        .with(as(w.hr())))
                .andDo(FAIL_ON_500).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.receiptDocumentId").exists())
                .andExpect(jsonPath("$.receiptDocumentIds", hasSize(1)));
        // v0.6: the receipt is stored on the report and is a Core document of the receipt type
        UUID receipt = ADMIN.queryForObject("select receipt_document_id from hr.statutory_report where id = ?",
                UUID.class, id);
        assertThat(ADMIN.queryForObject("select document_type_code from core.document where id = ?", String.class,
                receipt)).isEqualTo("HR_STATUTORY_RECEIPT");
        mvc.perform(get(REPORTS + "/" + id).with(as(w.hr())))
                .andExpect(jsonPath("$.receiptDocumentId").value(receipt.toString()));
        mvc.perform(get(REPORTS).param("legalEntityId", w.le().toString()).param("year", "2026").with(as(w.hr())))
                .andExpect(jsonPath("$", hasSize(1)));

        // cancelled: a new live report of the same period can be generated
        v = ADMIN.queryForObject("select version from hr.statutory_report where id = ?", Integer.class, id);
        postJson(w.hr(), REPORTS + "/" + id + "/cancel", "{\"version\":" + v + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        String second = body(postJson(w.hr(), REPORTS, gen).andExpect(status().isCreated()));
        assertThat(JsonPath.<String>read(second, "$.report.id")).isNotEqualTo(id.toString());
    }

    @Test
    void theMonthlyNoticeListsHiresAndTerminationsInTheParameterisedLayout() throws Exception {
        Data d = data("rep-month");
        World w = d.w();
        mvc.perform(get(REPORTS + "/labour-fluctuation").param("legalEntityId", w.le().toString())
                        .param("month", "2026-02").with(as(w.hr())))
                .andDo(FAIL_ON_500).andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value("2026-03-03"))
                .andExpect(jsonPath("$.hires").value(1)).andExpect(jsonPath("$.terminations").value(0))
                .andExpect(jsonPath("$.rows[0].fullName").value("Trần Bình"))
                .andExpect(jsonPath("$.rows[0].nationalId").value("••••9876"))
                .andExpect(jsonPath("$.columns", hasSize(6)));
        mvc.perform(get(REPORTS + "/labour-fluctuation").param("legalEntityId", w.le().toString())
                        .param("month", "2026-03").with(as(w.hr())))
                .andExpect(jsonPath("$.terminations").value(1))
                .andExpect(jsonPath("$.rows[0].movementType").value("TERMINATION"))
                .andExpect(jsonPath("$.rows[0].reasonCode").value("RESIGNATION"))
                .andExpect(jsonPath("$.rows[0].movementDate").value("2026-03-31"));
        String gen = body(postJson(w.hr(), REPORTS, "{\"legalEntityId\":\"" + w.le()
                + "\",\"reportType\":\"LABOUR_FLUCTUATION_MONTHLY\",\"year\":2026,\"month\":2}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.report.periodStart").value("2026-02-01"))
                .andExpect(jsonPath("$.report.rowCount").value(1))
                .andExpect(jsonPath("$.content.identityRevealed").value(true)));
        UUID doc = UUID.fromString(JsonPath.read(gen, "$.report.generatedDocumentId"));
        assertThat(LabourBookIT.sheetText(LabourBookIT.stored(w.tenant(), doc)))
                .contains("THÔNG BÁO BIẾN ĐỘNG LAO ĐỘNG").contains("001200009876").contains("Tăng");
        // a layout naming an unknown column is refused
        TestLegalParameters.define(w.tenant(), ReportKeys.FLUCTUATION_LAYOUT, Map.of("columns", List.of("salary")));
        mvc.perform(get(REPORTS + "/labour-fluctuation").param("legalEntityId", w.le().toString())
                        .param("month", "2026-02").with(as(w.hr())))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("legal-parameter-invalid"));
    }

    @Test
    void deadlineAlertsFollowTheTenantOffsetsUntilTheReportIsSubmitted() throws Exception {
        World w = world("rep-alerts");
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 5, 31))).deadlineAlerts()).isEqualTo(1);
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 5, 29))).deadlineAlerts()).isEqualTo(1);
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 5, 30))).deadlineAlerts()).isZero();
        String gen = body(postJson(w.hr(), REPORTS, "{\"legalEntityId\":\"" + w.le()
                + "\",\"reportType\":\"LABOUR_CHANGE_6M\",\"year\":2026,\"period\":\"JUNE\"}")
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(JsonPath.read(gen, "$.report.id"));
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 6, 4))).deadlineAlerts()).isEqualTo(1);
        int v = ADMIN.queryForObject("select version from hr.statutory_report where id = ?", Integer.class, id);
        postJson(w.hr(), REPORTS + "/" + id + "/submit", "{\"version\":" + v + ",\"submittedOn\":\"2026-06-01\"}")
                .andExpect(status().isOk());
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 6, 4))).deadlineAlerts()).isZero();
    }

    @Test
    void reportsAreScopedToTheLegalEntityAndTenant() throws Exception {
        World w = world("rep-scope");
        UUID otherLe = FIX.legalEntity(w.tenant(), "LE2");
        TestUser outsider = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(otherLe),
                PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE);
        mvc.perform(get(REPORTS).param("legalEntityId", w.le().toString()).with(as(outsider)))
                .andExpect(status().isNotFound());
        postJson(outsider, REPORTS, "{\"legalEntityId\":\"" + w.le()
                + "\",\"reportType\":\"LABOUR_CHANGE_6M\",\"year\":2026,\"period\":\"JUNE\"}")
                .andExpect(status().isNotFound());
        String gen = body(postJson(w.hr(), REPORTS, "{\"legalEntityId\":\"" + w.le()
                + "\",\"reportType\":\"LABOUR_BOOK_EXPORT\",\"asOf\":\"2026-03-01\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.report.rowCount").value(1))
                .andExpect(jsonPath("$.report.periodStart").value("2026-03-01")));
        UUID id = UUID.fromString(JsonPath.read(gen, "$.report.id"));
        mvc.perform(get(REPORTS + "/" + id).with(as(outsider))).andExpect(status().isNotFound());
        TestUser viewer = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(w.le()),
                PeoplePermissions.STATUTORY_REPORT_VIEW);
        mvc.perform(get(REPORTS + "/" + id).with(as(viewer))).andExpect(status().isOk());
        postJson(viewer, REPORTS + "/" + id + "/cancel", "{\"version\":1}").andExpect(status().isForbidden());
        World x = world("rep-scope-x");
        mvc.perform(get(REPORTS + "/" + id).with(as(x.hr()))).andExpect(status().isNotFound());
    }
}
