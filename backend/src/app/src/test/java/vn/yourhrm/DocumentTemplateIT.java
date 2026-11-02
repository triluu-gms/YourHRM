package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.DocumentGeneration;
import vn.yourhrm.core.api.DocumentGeneration.GenerateRequest;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestHrDocuments;
import vn.yourhrm.support.TestObjectStorage;

/** Document templates and generation (BR-CORE-102, US-CORE-103/104). */
class DocumentTemplateIT extends IntegrationTest {

    static final String HTML = """
            <html><head><title>HĐLĐ</title></head><body>
            <h1>HỢP ĐỒNG LAO ĐỘNG số {{doc.number}}</h1>
            <p>Bên A: {{le.legal_name}} (MST {{le.tax_code}}), ngày {{doc.date}}</p>
            <p>Bên B: {{ employee.full_name }}, chức danh {{job.title}}, từ ngày {{contract.start_date}}</p>
            </body></html>""";

    @Autowired
    DocumentGeneration generation;

    @Autowired
    DocumentScanResults scans;

    @Autowired
    PlatformTransactionManager txManager;

    private <T> T inTx(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), () -> new TransactionTemplate(txManager).execute(s -> {
                    try {
                        return call.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }

    private UUID createTemplate(TestUser owner, UUID le, String sequence) throws Exception {
        String body = mvc.perform(post("/api/v1/core/document-templates").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + TestHrDocuments.CONTRACT_TEMPLATE + "\",\"legalEntityId\":"
                                + (le == null ? "null" : "\"" + le + "\"") + ",\"numberingSequenceCode\":"
                                + (sequence == null ? "null" : "\"" + sequence + "\"") + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("LABOUR_CONTRACT"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String uploadVersion(TestUser owner, UUID template, String fileName, byte[] content, String validFrom,
            String output) throws Exception {
        var req = multipart("/api/v1/core/document-templates/" + template + "/versions")
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content))
                .param("validFrom", validFrom);
        if (output != null) {
            req.param("outputFormat", output);
        }
        return mvc.perform(req.with(as(owner))).andReturn().getResponse().getContentAsString();
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    void anHtmlTemplateIsCheckedPreviewedPublishedAndGeneratesANumberedPdfA() throws Exception {
        UUID tenant = FIX.tenant("tpl-html");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        mvc.perform(post("/api/v1/core/sequences").with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"CONTRACT_NO","name":"Số hợp đồng","scopeLevel":"LEGAL_ENTITY","pattern":"{SEQ:4}/{YYYY}/HĐLĐ-{LE}",
                 "resetRule":"YEARLY"}""")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/core/document-template-definitions").with(as(owner)))
                .andExpect(jsonPath("$[?(@.code == '" + TestHrDocuments.CONTRACT_TEMPLATE + "')].mergeFields.length()")
                        .value(org.hamcrest.Matchers.hasItem(3)));
        UUID template = createTemplate(owner, null, "CONTRACT_NO");
        mvc.perform(post("/api/v1/core/document-templates").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + TestHrDocuments.CONTRACT_TEMPLATE + "\"}"))
                .andExpect(status().isConflict());

        // unknown merge fields are refused on upload
        String refused = uploadVersion(owner, template, "hd.html",
                (HTML + "{{employee.salary}}{{nope}}").getBytes(StandardCharsets.UTF_8), "2026-01-01", null);
        assertThat(JsonPath.<String>read(refused, "$.code")).isEqualTo("unknown-merge-fields");
        assertThat(JsonPath.<java.util.List<String>>read(refused, "$.fields")).containsExactlyInAnyOrder("employee.salary", "nope");

        String uploaded = uploadVersion(owner, template, "hd.html", HTML.getBytes(StandardCharsets.UTF_8), "2026-01-01", null);
        UUID version = UUID.fromString(JsonPath.read(uploaded, "$.versions[0].id"));
        assertThat(JsonPath.<String>read(uploaded, "$.versions[0].status")).isEqualTo("DRAFT");

        // preview with sample data before publishing
        byte[] preview = mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + version + "/preview")
                        .with(as(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(pdfText(preview)).contains("Nguyễn Thị Ánh").contains("Kỹ sư phần mềm");
        assertThatThrownBy(() -> inTx(owner, () -> generation.generate(new GenerateRequest(
                TestHrDocuments.CONTRACT_TEMPLATE, le, TestHrDocuments.contract(le), null, null,
                LocalDate.of(2026, 3, 1), Map.of())))).isInstanceOf(ApiException.class)
                .hasMessageContaining("No version");

        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + version + "/publish").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versions[0].status").value("PUBLISHED"));

        assertThatThrownBy(() -> inTx(owner, () -> generation.generate(new GenerateRequest(
                TestHrDocuments.CONTRACT_TEMPLATE, le, TestHrDocuments.contract(le), null, null,
                LocalDate.of(2026, 3, 1), Map.of("employee.full_name", "Trần Văn Bình")))))
                .isInstanceOf(ApiException.class).hasMessageContaining("missing");

        UUID contract = TestHrDocuments.contract(le);
        Generated g = inTx(owner, () -> generation.generate(new GenerateRequest(TestHrDocuments.CONTRACT_TEMPLATE, le,
                contract, null, null, LocalDate.of(2026, 3, 1), Map.of("employee.full_name", "Trần Văn Bình",
                        "job.title", "Kế toán trưởng", "contract.start_date", "01/03/2026"))));
        assertThat(g.documentNumber()).isEqualTo("0001/2026/HĐLĐ-LE");
        assertThat(g.mediaType()).isEqualTo("application/pdf");
        Map<String, Object> v = ADMIN.queryForMap("""
                select d.status, d.document_type_code, d.owner_entity_id, d.confidentiality, dv.source, dv.scan_status,
                       dv.generated_from_template_version_id, dv.object_key
                  from core.document d join core.document_version dv on dv.tenant_id = d.tenant_id and dv.document_id = d.id
                 where d.tenant_id = ? and d.id = ?""", tenant, g.documentId());
        assertThat(v).containsEntry("status", "ACTIVE").containsEntry("document_type_code", TestHrDocuments.CONTRACT)
                .containsEntry("owner_entity_id", contract).containsEntry("confidentiality", "CONFIDENTIAL")
                .containsEntry("source", "GENERATED").containsEntry("scan_status", "SKIPPED")
                .containsEntry("generated_from_template_version_id", version);
        byte[] pdf = new TestObjectStorage().get(tenant, (String) v.get("object_key"));
        String text = pdfText(pdf).replaceAll("\\R", ""); // the renderer may wrap long lines
        assertThat(text).contains("HỢP ĐỒNG LAO ĐỘNG số 0001/2026/HĐLĐ-LE").contains("Pháp nhân LE")
                .contains("0101234567").contains("Trần Văn Bình").contains("01/03/2026");
        try (PDDocument d = Loader.loadPDF(pdf)) {
            String xmp = new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertThat(xmp).contains("pdfaid:part").contains("2");
        }
        assertThat(ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ? and action = 'GENERATE' and object_id = ?",
                Integer.class, tenant, g.documentId())).isEqualTo(1);

        // a later version takes over from its date; generation on earlier dates keeps the first one
        String v2 = uploadVersion(owner, template, "hd2.html", HTML.replace("HỢP ĐỒNG", "HĐ MỚI").getBytes(StandardCharsets.UTF_8),
                "2026-07-01", null);
        UUID version2 = UUID.fromString(JsonPath.read(v2, "$.versions[1].id"));
        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + version2 + "/publish").with(as(owner)))
                .andExpect(jsonPath("$.versions[0].validTo").value("2026-07-01"));
        Generated later = inTx(owner, () -> generation.generate(new GenerateRequest(TestHrDocuments.CONTRACT_TEMPLATE,
                le, contract, null, "HĐ", LocalDate.of(2026, 8, 1), Map.of("employee.full_name", "A",
                        "job.title", "B", "contract.start_date", "C"))));
        assertThat(later.templateVersionId()).isEqualTo(version2);
        assertThat(later.documentNumber()).isEqualTo("0002/2026/HĐLĐ-LE");
    }

    @Test
    void aDocxTemplateMergesFieldsWithTheLegalEntitysSeal() throws Exception {
        UUID tenant = FIX.tenant("tpl-docx");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        UUID template = createTemplate(owner, le, null);

        byte[] wrongTag = docx("Bên B: {{@employee.full_name}}");
        String refused = uploadVersion(owner, template, "hd.docx", wrongTag, "2026-01-01", "DOCX");
        assertThat(JsonPath.<java.util.List<String>>read(refused, "$.fields")).containsExactly("@employee.full_name");
        String bad = uploadVersion(owner, template, "hd.docx", "not a docx".getBytes(), "2026-01-01", "DOCX");
        assertThat(JsonPath.<String>read(bad, "$.code")).isEqualTo("invalid-template-file");

        String uploaded = uploadVersion(owner, template, "hd.docx",
                docx("Bên A: {{le.legal_name}} - MST {{le.tax_code}}", "Bên B: {{employee.full_name}}", "{{@le.seal}}"),
                "2026-01-01", "DOCX");
        UUID version = UUID.fromString(JsonPath.read(uploaded, "$.versions[0].id"));
        assertThat(JsonPath.<java.util.List<String>>read(uploaded, "$.versions[0].mergeFields"))
                .containsExactly("employee.full_name", "le.legal_name", "le.seal", "le.tax_code");
        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + version + "/publish").with(as(owner)))
                .andExpect(status().isOk());

        // the seal is a scanned image document of the legal entity
        String sealBody = DocumentIT.upload(mvc, owner, "LE_SEAL", le, le, "seal.png", "image/png", png())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID seal = UUID.fromString(JsonPath.read(sealBody, "$.id"));
        mvc.perform(put("/api/v1/core/legal-entities/" + le + "/letterhead").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sealDocumentId\":\"" + seal + "\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-letterhead-document"));
        DocumentIT.scanned(scans, tenant, seal, 1, "CLEAN");
        mvc.perform(put("/api/v1/core/legal-entities/" + le + "/letterhead").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sealDocumentId\":\"" + seal + "\"}"))
                .andExpect(status().isNoContent());

        Generated g = inTx(owner, () -> generation.generate(new GenerateRequest(TestHrDocuments.CONTRACT_TEMPLATE, le,
                TestHrDocuments.contract(le), null, null, LocalDate.of(2026, 2, 1),
                Map.of("employee.full_name", "Lê Thị Hồng"))));
        assertThat(g.mediaType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(g.documentNumber()).isNull();
        String key = ADMIN.queryForObject("select object_key from core.document_version where document_id = ?", String.class,
                g.documentId());
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(new TestObjectStorage().get(tenant, key)));
                XWPFWordExtractor text = new XWPFWordExtractor(doc)) {
            assertThat(text.getText()).contains("Bên A: Pháp nhân LE - MST 0101234567").contains("Bên B: Lê Thị Hồng");
            assertThat(doc.getAllPictures()).hasSize(1);
        }
        // DOCX -> PDF needs the converter service
        String pdfVersion = uploadVersion(owner, template, "hd.docx", docx("{{employee.full_name}}"), "2026-06-01", null);
        UUID pdfV = UUID.fromString(JsonPath.read(pdfVersion, "$.versions[1].id"));
        mvc.perform(post("/api/v1/core/document-templates/" + template + "/versions/" + pdfV + "/preview").with(as(owner)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("pdf-converter-unavailable"));

        TestUser nobody = FIX.user(tenant, "nobody@test.local");
        mvc.perform(get("/api/v1/core/document-templates").with(as(nobody))).andExpect(status().isForbidden());
    }

    private static byte[] docx(String... paragraphs) throws Exception {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String p : paragraphs) {
                d.createParagraph().createRun().setText(p);
            }
            d.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
