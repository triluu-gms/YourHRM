package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestObjectStorage;

/** HR decisions (BR-PPL-130, US-PPL-057/058): register, paper signing, cancel / re-issue, templates. */
class DecisionIT extends ContractTestBase {

    String issue(World w, String type, String effective) throws Exception {
        return postJson(w.hr(), "/api/v1/people/decisions", "{\"employmentId\":\"" + w.emp().employmentId()
                + "\",\"decisionTypeCode\":\"" + type + "\",\"effectiveDate\":\"" + effective
                + "\",\"signatoryName\":\"Lê Văn Giám\",\"signatoryTitle\":\"Giám đốc\"}")
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void decisionsAreIssuedSignedOnPaperCancelledWithTheirNumberAndReissued() throws Exception {
        World w = world("dec-register");
        postJson(w.hr(), "/api/v1/people/decisions", "{\"employmentId\":\"" + w.emp().employmentId()
                + "\",\"decisionTypeCode\":\"REWARD\",\"effectiveDate\":\"2026-01-05\"}")
                .andExpect(jsonPath("$.code").value("invalid-decision-type"));
        String first = issue(w, "TRANSFER", "2026-01-05");
        assertThat(JsonPath.<String>read(first, "$.decisionNo")).isEqualTo("001/2026/QĐ-LE");
        assertThat(JsonPath.<String>read(first, "$.status")).isEqualTo("APPROVED");
        UUID d1 = id(first);

        mvc.perform(multipart("/api/v1/people/decisions/" + d1 + "/sign")
                        .file(new MockMultipartFile("file", "qd.pdf", "application/pdf", PDF))
                        .param("signDate", "2026-01-04").param("version", "0").with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"))
                .andExpect(jsonPath("$.signDate").value("2026-01-04"))
                .andExpect(jsonPath("$.documentId").isString());
        assertThat(ADMIN.queryForObject("select owner_entity_type from core.document where tenant_id = ? and owner_entity_id = ?",
                String.class, w.tenant(), d1)).isEqualTo("hr.decision");

        // re-issue: the old decision is cancelled and keeps its number, the new one gets the next number
        String second = postJson(w.hr(), "/api/v1/people/decisions/" + d1 + "/reissue",
                "{\"effectiveDate\":\"2026-02-01\",\"version\":1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionNo").value("002/2026/QĐ-LE"))
                .andExpect(jsonPath("$.decisionTypeCode").value("TRANSFER"))
                .andExpect(jsonPath("$.signatoryName").value("Lê Văn Giám"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/people/decisions/" + d1).with(as(w.hr())))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.decisionNo").value("001/2026/QĐ-LE"));
        postJson(w.hr(), "/api/v1/people/decisions/" + d1 + "/cancel", "{\"version\":2}")
                .andExpect(jsonPath("$.code").value("invalid-status-transition"));
        postJson(w.hr(), "/api/v1/people/decisions/" + id(second) + "/cancel", "{\"version\":0}")
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(get("/api/v1/people/employments/" + w.emp().employmentId() + "/decisions").with(as(w.hr())))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].decisionNo").value("002/2026/QĐ-LE"));
    }

    @Test
    void decisionsFollowTheEmployeesScope() throws Exception {
        World w = world("dec-scope");
        UUID d = id(issue(w, "SALARY_CHANGE", "2026-01-05"));
        OrgUnit other = FIX.orgUnit(w.tenant(), "OTHER", null, w.le(), null, TestFixtures.EPOCH, null);
        TestUser outsider = PPL.userWith(w.tenant(), "ORG_UNIT", List.of(other.id()), PeoplePermissions.DECISION_VIEW,
                PeoplePermissions.DECISION_CREATE, PeoplePermissions.DECISION_UPDATE);
        mvc.perform(get("/api/v1/people/decisions/" + d).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/people/employments/" + w.emp().employmentId() + "/decisions").with(as(outsider)))
                .andExpect(status().isNotFound());
        postJson(outsider, "/api/v1/people/decisions/" + d + "/cancel", "{\"version\":0}")
                .andExpect(status().isNotFound());
        TestUser viewer = PPL.userWith(w.tenant(), "ORG_UNIT", List.of(w.unit().id()), PeoplePermissions.DECISION_VIEW);
        mvc.perform(get("/api/v1/people/decisions/" + d).with(as(viewer))).andExpect(status().isOk());
        postJson(viewer, "/api/v1/people/decisions/" + d + "/cancel", "{\"version\":0}")
                .andExpect(status().isForbidden());
        World x = world("dec-scope-x");
        mvc.perform(get("/api/v1/people/decisions/" + d).with(as(x.hr()))).andExpect(status().isNotFound());
    }

    @Test
    void aSuspensionDecisionIsGeneratedFromItsBilingualTemplate() throws Exception {
        World w = world("dec-template");
        UUID c = signedContract(w, w.emp().employmentId(), "{\"contractTypeCode\":\"INDEFINITE\",\"startDate\":\"2025-01-01\"}");
        String s = postJson(w.hr(), "/api/v1/people/contracts/" + c + "/suspensions",
                "{\"reasonCode\":\"MILITARY_SERVICE\",\"validFrom\":\"2025-06-01\",\"plannedEndDate\":\"2027-05-31\","
                        + "\"signatoryName\":\"Lê Văn Giám\",\"signatoryTitle\":\"Giám đốc\"}")
                .andReturn().getResponse().getContentAsString();
        UUID decision = UUID.fromString(JsonPath.read(s, "$.decisionId"));

        mvc.perform(get("/api/v1/core/document-template-definitions").with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.code == 'HR_DECISION_CONTRACT_SUSPENSION')].purpose").value("DECISION"))
                .andExpect(jsonPath("$[?(@.code == 'HR_DECISION_RETIREMENT')].purpose").value("DECISION"));
        UUID template = id(mvc.perform(post("/api/v1/core/document-templates").with(as(w.hr()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"HR_DECISION_CONTRACT_SUSPENSION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String html = """
                <html><body><h1>{{decision.type_vi}} số {{decision.no}}</h1><p>{{decision.type_en}}</p>
                <p>Ông/bà {{employee.full_name}} ({{employee.no}}), hợp đồng {{contract.no}}</p>
                <p>Lý do: {{suspension.reason_vi}} / {{suspension.reason_en}}, từ {{suspension.from}} đến {{suspension.to}}</p>
                <p>{{signatory.title}} {{signatory.name}}</p></body></html>""";
        String uploaded = mvc.perform(multipart("/api/v1/core/document-templates/" + template + "/versions")
                        .file(new MockMultipartFile("file", "qd.html", "text/html", html.getBytes(StandardCharsets.UTF_8)))
                        .param("validFrom", "2025-01-01").with(as(w.hr())))
                .andReturn().getResponse().getContentAsString();
        UUID version = UUID.fromString(JsonPath.read(uploaded, "$.versions[0].id"));
        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + version + "/publish")
                .with(as(w.hr()))).andExpect(status().isOk());

        String generated = mvc.perform(post("/api/v1/people/decisions/" + decision + "/document").with(as(w.hr())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID doc = UUID.fromString(JsonPath.read(generated, "$.documentId"));
        Map<String, Object> row = ADMIN.queryForMap("""
                select d.owner_entity_type, dv.object_key from core.document d
                  join core.document_version dv on dv.tenant_id = d.tenant_id and dv.document_id = d.id
                 where d.tenant_id = ? and d.id = ?""", w.tenant(), doc);
        assertThat(row).containsEntry("owner_entity_type", "hr.decision");
        byte[] pdf = new TestObjectStorage().get(w.tenant(), (String) row.get("object_key"));
        try (PDDocument d = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(d).replaceAll("\\R", "");
            assertThat(text).contains("Quyết định tạm hoãn thực hiện hợp đồng lao động").contains("001/2025/QĐ-LE")
                    .contains("Nguyễn An").contains("Thực hiện nghĩa vụ quân sự").contains("01/06/2025")
                    .contains("31/05/2027").contains("001/2025/HĐLĐ-LE");
        }
    }
}
