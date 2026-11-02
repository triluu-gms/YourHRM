package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestHrDocuments;
import vn.yourhrm.support.TestObjectStorage;

/** The document service (BR-CORE-100/101, US-CORE-100..102). */
class DocumentIT extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};

    @Autowired
    DocumentScanResults scans;

    static ResultActions upload(org.springframework.test.web.servlet.MockMvc mvc, TestUser user, String type,
            UUID owner, UUID le, String fileName, String media, byte[] content) throws Exception {
        var req = multipart("/api/v1/core/documents").file(new MockMultipartFile("file", fileName, media, content))
                .param("documentTypeCode", type).param("ownerEntityId", owner.toString());
        if (le != null) {
            req.param("legalEntityId", le.toString());
        }
        return mvc.perform(req.with(as(user)));
    }

    /** What the SaaS storage reports after scanning the document's version {@code no}. */
    static void scanned(DocumentScanResults scans, UUID tenant, UUID document, int no, String status) {
        String key = ADMIN.queryForObject("select object_key from core.document_version where tenant_id = ? and document_id = ? and version_no = ?",
                String.class, tenant, document, no);
        scans.report(tenant, key, status);
    }

    @Test
    void anUploadIsUsableOnlyAfterACleanScanAndKeepsItsVersions() throws Exception {
        UUID tenant = FIX.tenant("doc-flow");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        String body = upload(mvc, owner, "EVIDENCE", le, le, "C:\\scans\\quyet-dinh.pdf", "application/pdf", PDF)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_SCAN"))
                .andExpect(jsonPath("$.title").value("quyet-dinh.pdf"))
                .andExpect(jsonPath("$.versions[0].scanStatus").value("PENDING"))
                .andExpect(jsonPath("$.versions[0].sha256").isString())
                .andReturn().getResponse().getContentAsString();
        UUID doc = UUID.fromString(JsonPath.read(body, "$.id"));
        String key = ADMIN.queryForObject("select object_key from core.document_version where document_id = ?", String.class, doc);
        assertThat(key).startsWith(tenant + "/core/");
        assertThat(TestObjectStorage.scanQueued(key)).isTrue();

        mvc.perform(post("/api/v1/core/documents/" + doc + "/download-link").with(as(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("document-not-ready"));
        scanned(scans, tenant, doc, 1, "CLEAN");
        scanned(scans, tenant, doc, 1, "INFECTED"); // a late contradicting report is ignored
        mvc.perform(get("/api/v1/core/documents/" + doc).with(as(owner)))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.currentVersionNo").value(1));
        mvc.perform(post("/api/v1/core/documents/" + doc + "/download-link").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fileName").value("quyet-dinh.pdf"))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://storage.test/" + tenant)));
        assertThat(TestObjectStorage.lastTtl).isLessThanOrEqualTo(Duration.ofMinutes(15));

        // a new version is current only once it is clean; the old one stays downloadable
        mvc.perform(multipart("/api/v1/core/documents/" + doc + "/versions")
                        .file(new MockMultipartFile("file", "quyet-dinh-v2.pdf", "application/pdf", PDF)).with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentVersionNo").value(1))
                .andExpect(jsonPath("$.versions.length()").value(2));
        scanned(scans, tenant, doc, 2, "CLEAN");
        mvc.perform(get("/api/v1/core/documents").param("ownerEntityType", "core.legal_entity")
                        .param("ownerEntityId", le.toString()).with(as(owner)))
                .andExpect(jsonPath("$[0].currentVersionNo").value(2));
        mvc.perform(post("/api/v1/core/documents/" + doc + "/download-link").param("versionNo", "1").with(as(owner)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/core/document-types").with(as(owner)))
                .andExpect(jsonPath("$[?(@.code == 'EVIDENCE')].ownerEntityType").value(org.hamcrest.Matchers.hasItem("core.legal_entity")));
    }

    @Test
    void uploadsAreCheckedAgainstTheTenantLimitsAndTheirContent() throws Exception {
        UUID tenant = FIX.tenant("doc-limits");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        mvc.perform(put("/api/v1/core/settings/core.document.max_upload_mb").with(as(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"TENANT\",\"value\":1}"))
                .andExpect(status().isOk());
        byte[] big = new byte[1_200_000];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        upload(mvc, owner, "EVIDENCE", le, le, "big.pdf", "application/pdf", big)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("file-too-large"));
        upload(mvc, owner, "EVIDENCE", le, le, "notes.txt", "text/plain", "hello".getBytes())
                .andExpect(jsonPath("$.code").value("media-type-not-allowed"));
        upload(mvc, owner, "EVIDENCE", le, le, "fake.pdf", "application/pdf", PNG)
                .andExpect(jsonPath("$.code").value("file-type-mismatch"));
        upload(mvc, owner, "EVIDENCE", le, le, "photo.png", "application/pdf", PDF)
                .andExpect(jsonPath("$.code").value("file-type-mismatch"));
        upload(mvc, owner, "LE_SEAL", le, le, "seal.pdf", "application/pdf", PDF)
                .andExpect(jsonPath("$.code").value("media-type-not-allowed"));
        upload(mvc, owner, "NOPE", le, le, "x.pdf", "application/pdf", PDF)
                .andExpect(jsonPath("$.code").value("unknown-document-type"));
    }

    @Test
    void anInfectedFileIsQuarantinedAndItsUploaderNotified() throws Exception {
        UUID tenant = FIX.tenant("doc-virus");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        UUID doc = UUID.fromString(JsonPath.read(upload(mvc, owner, "EVIDENCE", le, le, "virus.pdf", "application/pdf", PDF)
                .andReturn().getResponse().getContentAsString(), "$.id"));
        scanned(scans, tenant, doc, 1, "INFECTED");
        mvc.perform(get("/api/v1/core/documents/" + doc).with(as(owner)))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.versions[0].scanStatus").value("INFECTED"));
        mvc.perform(post("/api/v1/core/documents/" + doc + "/download-link").with(as(owner)))
                .andExpect(status().isConflict());
        assertThat(ADMIN.queryForObject("""
                select count(*) from core.notification where tenant_id = ? and recipient_user_id = ?
                   and event_code = 'core.DocumentQuarantined'""", Integer.class, tenant, owner.userId())).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ? and action = 'QUARANTINE'",
                Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void accessFollowsTheOwnerRecordAndConfidentialDownloadsAreAudited() throws Exception {
        UUID tenant = FIX.tenant("doc-scope");
        TestUser owner = FIX.owner(tenant);
        UUID leA = FIX.legalEntity(tenant, "A");
        UUID leB = FIX.legalEntity(tenant, "B");
        UUID role = FIX.role(tenant, "LE_VIEWER", false, "core.legal_entity.view");
        TestUser viewer = FIX.user(tenant, "viewer@test.local");
        FIX.assign(viewer, role, "LEGAL_ENTITY", List.of(leA), false, TestFixtures.EPOCH, null);
        UUID contractA = TestHrDocuments.contract(leA);
        UUID contractB = TestHrDocuments.contract(leB);
        UUID docA = UUID.fromString(JsonPath.read(upload(mvc, owner, TestHrDocuments.CONTRACT, contractA, null,
                "hd-a.pdf", "application/pdf", PDF).andExpect(status().isCreated())
                .andExpect(jsonPath("$.confidentiality").value("CONFIDENTIAL"))
                .andReturn().getResponse().getContentAsString(), "$.id"));
        UUID docB = UUID.fromString(JsonPath.read(upload(mvc, owner, TestHrDocuments.CONTRACT, contractB, null,
                "hd-b.pdf", "application/pdf", PDF).andReturn().getResponse().getContentAsString(), "$.id"));
        scanned(scans, tenant, docA, 1, "CLEAN");
        scanned(scans, tenant, docB, 1, "CLEAN");

        mvc.perform(get("/api/v1/core/documents/" + docA).with(as(viewer))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/core/documents/" + docB).with(as(viewer)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("document-access-denied"));
        mvc.perform(post("/api/v1/core/documents/" + docB + "/download-link").with(as(viewer))).andExpect(status().isForbidden());
        // viewing does not allow adding files (manage permission)
        upload(mvc, viewer, TestHrDocuments.CONTRACT, contractA, null, "x.pdf", "application/pdf", PDF)
                .andExpect(status().isForbidden());
        // an unknown owner record resolves to nothing: refused
        upload(mvc, owner, TestHrDocuments.CONTRACT, UUID.randomUUID(), null, "x.pdf", "application/pdf", PDF)
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/core/documents/" + docA + "/download-link").with(as(viewer))).andExpect(status().isOk());
        assertThat(ADMIN.queryForList("""
                select actor_id from audit.audit_event where tenant_id = ? and action = 'DOWNLOAD' and object_id = ?
                   and is_sensitive_read""", UUID.class, tenant, docA)).containsExactly(viewer.userId());
    }
}
