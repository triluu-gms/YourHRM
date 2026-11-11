package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import vn.yourhrm.people.api.ContractJobs;
import vn.yourhrm.people.api.ContractJobs.Summary;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.contract.ContractNotificationTemplates;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestObjectStorage;

/**
 * The daily contract monitor and the expiry list (BR-PPL-052/054/056, US-PPL-049/051/054/059) and contract documents
 * from the tenant's template (US-PPL-055).
 */
class ContractMonitorIT extends ContractTestBase {

    @Autowired
    ContractJobs jobs;

    @Test
    void expiryAlertsFireOncePerOffsetThenTheContractExpiresAndIsFlaggedForAutoConversion() throws Exception {
        World w = world("ctr-monitor");
        UUID c = signedContract(w, w.emp().employmentId(), definite("2025-01-01", "2025-12-31"));

        Summary first = as(w.hr(), () -> jobs.runDaily(LocalDate.of(2025, 11, 20)));
        assertThat(first.expiryAlerts()).isEqualTo(1);
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2025, 11, 20))).expiryAlerts()).isZero();
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2025, 11, 21))).expiryAlerts()).isZero();
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2025, 12, 5))).expiryAlerts()).isEqualTo(1);
        List<String> expiring = events(w.tenant(), "people.ContractExpiring");
        assertThat(expiring).extracting(e -> JsonPath.<Integer>read(e, "$.daysBefore")).containsExactly(45, 30);
        assertThat(notifications(w.tenant(), ContractNotificationTemplates.CONTRACT_EXPIRING)).isGreaterThanOrEqualTo(2);
        String text = ADMIN.queryForObject("select body from core.notification where tenant_id = ? and event_code = ? limit 1",
                String.class, w.tenant(), ContractNotificationTemplates.CONTRACT_EXPIRING);
        assertThat(text).contains("001/2025/HĐLĐ-LE").contains("31/12/2025").doesNotContain("Nguyễn");

        // the list shows it with the days left; after the end it expires, and past 30 days it is flagged
        mvc.perform(get("/api/v1/people/contracts/expiring?asOf=2025-12-05&withinDays=60").with(as(w.hr())))
                .andExpect(jsonPath("$[0].contractId").value(c.toString()))
                .andExpect(jsonPath("$[0].daysLeft").value(26))
                .andExpect(jsonPath("$[0].autoConversionDue").value(false))
                .andExpect(jsonPath("$[0].nextMustBeIndefinite").value(false));
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 1, 1))).contractsExpired()).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select status from hr.contract where id = ?", String.class, c)).isEqualTo("EXPIRED");
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 1, 20))).autoConversionFlags()).isZero();
        assertThat(ADMIN.queryForObject("select auto_conversion_due_since from hr.contract where id = ?",
                LocalDate.class, c)).isNull();
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 2, 5))).autoConversionFlags()).isEqualTo(1);
        // v0.6: the first day the contract counts as converted (end + 30 days + 1) is stored once
        assertThat(ADMIN.queryForObject("select auto_conversion_due_since from hr.contract where id = ?",
                LocalDate.class, c)).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 2, 6))).autoConversionFlags()).isZero();
        assertThat(events(w.tenant(), "people.ContractExpiring"))
                .filteredOn(e -> JsonPath.<Boolean>read(e, "$.autoConversionDue")).hasSize(1);
        assertThat(notifications(w.tenant(), ContractNotificationTemplates.AUTO_CONVERSION_DUE)).isPositive();
        mvc.perform(get("/api/v1/people/contracts/expiring?asOf=2026-02-05").with(as(w.hr())))
                .andExpect(jsonPath("$[0].contractId").value(c.toString()))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$[0].autoConversionDue").value(true))
                .andExpect(jsonPath("$[0].autoConversionDueSince").value("2026-01-31"));
        mvc.perform(get("/api/v1/people/contracts/" + c).with(as(w.hr())))
                .andExpect(jsonPath("$.autoConversionDueSince").value("2026-01-31"));

        // scope: an HR officer of another unit sees nothing
        OrgUnit other = FIX.orgUnit(w.tenant(), "OTHER", null, w.le(), null, TestFixtures.EPOCH, null);
        TestUser outsider = PPL.userWith(w.tenant(), "ORG_UNIT", List.of(other.id()), PeoplePermissions.CONTRACT_VIEW);
        mvc.perform(get("/api/v1/people/contracts/expiring?asOf=2026-02-05").with(as(outsider)))
                .andExpect(jsonPath("$.length()").value(0));
        World x = world("ctr-monitor-x");
        mvc.perform(get("/api/v1/people/contracts/expiring?asOf=2026-02-05").with(as(x.hr())))
                .andExpect(jsonPath("$.length()").value(0));

        // a renewal takes it off the list
        postJson(w.hr(), "/api/v1/people/contracts/" + c + "/convert", "{}").andExpect(status().isCreated());
        mvc.perform(get("/api/v1/people/contracts/expiring?asOf=2026-02-05").with(as(w.hr())))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void probationsEndingAndSuspendedEmployeesDueBackAreReminded() throws Exception {
        World w = world("ctr-monitor-prob");
        UUID p = signedContract(w, w.emp().employmentId(), "{\"contractTypeCode\":\"PROBATION\",\"startDate\":\"2025-01-01\","
                + "\"endDate\":\"2025-03-01\",\"probationLevelCode\":\"COLLEGE_OR_ABOVE\"}");
        mvc.perform(get("/api/v1/people/contracts/probations-ending?asOf=2025-02-20&withinDays=30").with(as(w.hr())))
                .andExpect(jsonPath("$[0].contractId").value(p.toString()))
                .andExpect(jsonPath("$[0].daysLeft").value(9));
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2025, 2, 10))).probationAlerts()).isZero();
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.of(2025, 2, 25))).probationAlerts()).isEqualTo(1);
        assertThat(notifications(w.tenant(), ContractNotificationTemplates.PROBATION_ENDING)).isPositive();

        Employee e = PPL.employee(w.tenant(), w.le(), w.unit(), "Lý", "Dung", HIRE);
        UUID c = signedContract(w, e.employmentId(), "{\"contractTypeCode\":\"INDEFINITE\",\"startDate\":\"2025-01-01\"}");
        postJson(w.hr(), "/api/v1/people/contracts/" + c + "/suspensions", "{\"reasonCode\":\"ELECTED_OFFICE\","
                + "\"validFrom\":\"" + LocalDate.now().plusDays(3) + "\",\"plannedEndDate\":\"" + LocalDate.now().plusDays(20) + "\"}")
                .andExpect(jsonPath("$.status").value("APPROVED"));
        Summary started = as(w.hr(), () -> jobs.runDaily(LocalDate.now().plusDays(3)));
        assertThat(started.suspensionsStarted()).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select status from hr.employment where id = ?", String.class, e.employmentId()))
                .isEqualTo("SUSPENDED");
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.now().plusDays(10))).returnAlerts()).isZero();
        assertThat(as(w.hr(), () -> jobs.runDaily(LocalDate.now().plusDays(21))).returnAlerts()).isEqualTo(1);
        assertThat(notifications(w.tenant(), ContractNotificationTemplates.SUSPENSION_RETURN_DUE)).isPositive();
    }

    @Test
    void theContractDocumentIsGeneratedFromTheTenantsBilingualTemplate() throws Exception {
        World w = world("ctr-document");
        TestUser owner = FIX.owner(w.tenant());
        UUID c = id(createContract(w.hr(), w.emp().employmentId(),
                "{\"contractTypeCode\":\"DEFINITE\",\"startDate\":\"2025-01-01\",\"endDate\":\"2025-12-31\","
                        + "\"signatoryName\":\"Lê Văn Giám\",\"signatoryTitle\":\"Giám đốc\"}")
                .andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/people/contracts/" + c + "/document").with(as(w.hr())))
                .andExpect(status().isUnprocessableContent());

        String body = mvc.perform(post("/api/v1/core/document-templates").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"HR_LABOUR_CONTRACT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID template = id(body);
        String html = """
                <html><body><h1>{{contract.type_vi}} / {{contract.type_en}} số {{contract.no}}</h1>
                <p>Bên A: {{le.legal_name}}, đại diện {{signatory.name}} ({{signatory.title}})</p>
                <p>Bên B: {{employee.full_name}}, sinh ngày {{employee.birth_date}}, mã {{employee.no}}</p>
                <p>Từ {{contract.start_date}} đến {{contract.end_date}}</p></body></html>""";
        String uploaded = mvc.perform(multipart("/api/v1/core/document-templates/" + template + "/versions")
                        .file(new MockMultipartFile("file", "hd.html", "text/html", html.getBytes(StandardCharsets.UTF_8)))
                        .param("validFrom", "2025-01-01").with(as(owner)))
                .andReturn().getResponse().getContentAsString();
        UUID version = UUID.fromString(JsonPath.read(uploaded, "$.versions[0].id"));
        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + version + "/publish").with(as(owner)))
                .andExpect(status().isOk());

        String generated = mvc.perform(post("/api/v1/people/contracts/" + c + "/document").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mediaType").value("application/pdf"))
                .andReturn().getResponse().getContentAsString();
        UUID doc = UUID.fromString(JsonPath.read(generated, "$.documentId"));
        Map<String, Object> row = ADMIN.queryForMap("""
                select d.owner_entity_type, d.owner_entity_id, d.document_type_code, dv.object_key
                  from core.document d join core.document_version dv on dv.tenant_id = d.tenant_id and dv.document_id = d.id
                 where d.tenant_id = ? and d.id = ?""", w.tenant(), doc);
        assertThat(row).containsEntry("owner_entity_type", "hr.contract").containsEntry("owner_entity_id", c)
                .containsEntry("document_type_code", "HR_LABOUR_CONTRACT");
        byte[] pdf = new TestObjectStorage().get(w.tenant(), (String) row.get("object_key"));
        try (PDDocument d = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(d).replaceAll("\\R", "");
            assertThat(text).contains("Hợp đồng lao động xác định thời hạn").contains("001/2025/HĐLĐ-LE")
                    .contains("Nguyễn An").contains("Lê Văn Giám").contains("31/12/2025").contains("17/05/1990");
        }
        // the generated file is listed on the contract for users who may see the contract
        mvc.perform(get("/api/v1/core/documents?ownerEntityType=hr.contract&ownerEntityId=" + c).with(as(w.hr())))
                .andExpect(status().isOk());
    }
}
