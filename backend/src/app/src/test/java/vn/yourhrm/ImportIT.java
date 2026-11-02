package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.core.api.CoreEvents.ImportCommitted;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.RecordedEvents;
import vn.yourhrm.support.TestBalanceImport;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestOpeningBalanceLocks;

/** The import engine (BR-CORE-110..112/114, US-CORE-015/025/110..117). */
class ImportIT extends IntegrationTest {

    @Autowired
    DocumentScanResults scans;

    @BeforeAll
    static void catalogue() {
        TestBalanceImport.register(ADMIN);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private byte[] template(TestUser user, String type, String lang) throws Exception {
        return mvc.perform(get("/api/v1/core/imports/types/" + type + "/template").param("lang", lang).with(as(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /** The template with {@code rows} written into its DATA sheet (and the control sheet when given). */
    private byte[] filled(TestUser user, String type, List<List<String>> rows, List<List<String>> control)
            throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template(user, type, "vi")));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.getSheet("DATA"), rows);
            if (control != null) {
                write(wb.getSheet("CONTROL"), control);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void write(Sheet sheet, List<List<String>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < rows.get(r).size(); c++) {
                row.createCell(c).setCellValue(rows.get(r).get(c));
            }
        }
    }

    private ResultActions upload(TestUser user, String type, String fileName, byte[] content, String mode,
            String asOf) throws Exception {
        var req = multipart("/api/v1/core/imports/jobs").file(new MockMultipartFile("file", fileName,
                "application/octet-stream", content)).param("importType", type);
        if (mode != null) {
            req.param("mode", mode);
        }
        if (asOf != null) {
            req.param("asOfDate", asOf);
        }
        return mvc.perform(req.with(as(user)));
    }

    /** Uploads, reports a clean scan as the storage would, and waits for the dry-run result. */
    private UUID validated(TestUser user, String type, String fileName, byte[] content, String mode, String asOf,
            String expected) throws Exception {
        String body = upload(user, type, fileName, content, mode, asOf).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED")).andReturn().getResponse().getContentAsString();
        UUID job = UUID.fromString(JsonPath.read(body, "$.id"));
        UUID file = UUID.fromString(JsonPath.read(body, "$.fileDocumentId"));
        DocumentIT.scanned(scans, user.tenantId(), file, 1, "CLEAN");
        await(user, job, expected);
        return job;
    }

    private String await(TestUser user, UUID job, String expected) throws Exception {
        String last = null;
        for (int i = 0; i < 200; i++) {
            String body = mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(user)))
                    .andReturn().getResponse().getContentAsString();
            last = JsonPath.read(body, "$.status");
            if (expected.equals(last)) {
                return body;
            }
            if ("FAILED".equals(last) && !"FAILED".equals(expected)) {
                throw new AssertionError("Job failed: " + JsonPath.read(body, "$.failureReason"));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + job + " stayed " + last + ", expected " + expected);
    }

    private ResultActions action(TestUser user, UUID job, String action) throws Exception {
        return mvc.perform(post("/api/v1/core/imports/jobs/" + job + "/" + action).with(as(user)));
    }

    // -------------------------------------------------------------------------------------------------- tests

    @Test
    void theTemplateCarriesDropdownsHintsInstructionsAndAVersionStamp() throws Exception {
        UUID tenant = FIX.tenant("imp-tpl");
        TestUser owner = FIX.owner(tenant);
        FIX.legalEntity(tenant, "LE1");
        mvc.perform(get("/api/v1/core/imports/types").with(as(owner)))
                .andExpect(jsonPath("$[?(@.code == 'CORE_ORG_UNIT')].openingBalance").value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.code == 'TEST_OB_BALANCE')]").isEmpty());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template(owner, "CORE_COST_CENTRE", "vi")))) {
            Sheet data = wb.getSheet("DATA");
            assertThat(data.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Pháp nhân *");
            assertThat(data.getRow(0).getCell(0).getCellComment().getString().getString()).contains("Mã hoặc tên");
            assertThat(data.getRow(0).getCell(5).getStringCellValue()).isEqualTo("Mã tham chiếu ngoài");
            assertThat(data.getDataValidations()).hasSize(1);
            assertThat(wb.getSheet("_lists").getRow(0).getCell(0).getStringCellValue()).isEqualTo("LE1 - Pháp nhân LE1");
            assertThat(wb.getSheetVisibility(wb.getSheetIndex("_lists"))).isEqualTo(SheetVisibility.HIDDEN);
            assertThat(wb.getSheetVisibility(wb.getSheetIndex("_meta"))).isEqualTo(SheetVisibility.VERY_HIDDEN);
            assertThat(wb.getSheet("_meta").getRow(0).getCell(1).getStringCellValue()).isEqualTo("CORE_COST_CENTRE");
            assertThat(wb.getSheet("HUONG_DAN")).isNotNull();
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template(owner, "CORE_COST_CENTRE", "en")))) {
            assertThat(wb.getSheet("DATA").getRow(0).getCell(1).getStringCellValue()).isEqualTo("Code *");
            assertThat(wb.getSheet("INSTRUCTIONS")).isNotNull();
        }
        TestUser nobody = FIX.user(tenant, "nobody@test.local");
        mvc.perform(get("/api/v1/core/imports/types/CORE_COST_CENTRE/template").with(as(nobody)))
                .andExpect(status().isForbidden());
    }

    @Test
    void costCentresAreValidatedFirstCommittedAllOrNothingAndRolledBack() throws Exception {
        UUID tenant = FIX.tenant("imp-cc");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE1");
        FIX.legalEntity(tenant, "LE2");
        ADMIN.update("update core.legal_entity_version set legal_name_vi = 'Công ty Đông Á' where tenant_id = ? and legal_entity_id = ?",
                tenant, le);

        // dry-run: lookups by code or accent-insensitive name, types, required values, duplicate keys
        UUID bad = validated(owner, "CORE_COST_CENTRE", "cc.xlsx", filled(owner, "CORE_COST_CENTRE", List.of(
                List.of("cong ty dong a", "CC01", "Kế toán", "6421", "01/01/2026", "OLD-1"),
                List.of("LE9", "CC02", "Bán hàng", "", "", ""),
                List.of("LE1", "CC01", "Trùng", "", "", ""),
                List.of("LE2 - Pháp nhân LE2", "CC03", "", "", "31/02/2026", "")), null), null, null, "VALIDATED");
        mvc.perform(get("/api/v1/core/imports/jobs/" + bad).with(as(owner)))
                .andExpect(jsonPath("$.rowCount").value(4)).andExpect(jsonPath("$.errorCount").value(3));
        mvc.perform(get("/api/v1/core/imports/jobs/" + bad + "/rows").param("status", "ERROR").with(as(owner)))
                .andExpect(jsonPath("$[0].rowNo").value(3))
                .andExpect(jsonPath("$[0].messages[0].code").value("not-found"))
                .andExpect(jsonPath("$[1].messages[0].code").value("duplicate-key"))
                .andExpect(jsonPath("$[2].messages[*].code").value(org.hamcrest.Matchers.containsInAnyOrder("required", "invalid-date")));
        mvc.perform(get("/api/v1/core/imports/jobs/" + bad + "/rows").param("status", "VALID").with(as(owner)))
                .andExpect(jsonPath("$[0].preview.external_ref").value("OLD-1"));
        assertThat(ADMIN.queryForObject("select count(*) from core.cost_centre where tenant_id = ?", Integer.class, tenant)).isZero();
        action(owner, bad, "commit").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("job-has-errors"));

        // the error file keeps the user's columns and adds status and messages
        byte[] errors = mvc.perform(get("/api/v1/core/imports/jobs/" + bad + "/error-file").with(as(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(errors))) {
            Sheet s = wb.getSheet("DATA");
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Pháp nhân *");
            assertThat(s.getRow(0).getCell(7).getStringCellValue()).isEqualTo("Trạng thái");
            assertThat(s.getRow(2).getCell(0).getStringCellValue()).isEqualTo("LE9");
            assertThat(s.getRow(2).getCell(8).getStringCellValue()).contains("không tìm thấy 'LE9'");
        }

        // a corrected CSV with English headers commits every row in one go
        String csv = "Legal entity,Code,Name,GL segment\nLE1,CC01,Kế toán,6421\n\"LE2\",CC02,\"Bán hàng, miền Bắc\",\n";
        UUID good = validated(owner, "CORE_COST_CENTRE", "cc.csv", csv.getBytes(StandardCharsets.UTF_8), null, null,
                "VALIDATED");
        action(owner, good, "commit").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("COMMITTING"));
        await(owner, good, "COMMITTED");
        assertThat(ADMIN.queryForList("select name from core.cost_centre where tenant_id = ? order by code", String.class, tenant))
                .containsExactly("Kế toán", "Bán hàng, miền Bắc");
        // the owner module's row audit names the channel and the job
        assertThat(ADMIN.queryForList("""
                select distinct channel || ':' || correlation_id from audit.audit_event
                 where tenant_id = ? and object_type = 'cost_centre'""", String.class, tenant))
                .containsExactly("IMPORT:" + good);
        assertThat(RecordedEvents.of(ImportCommitted.class)).anySatisfy(e -> {
            assertThat(e.jobId()).isEqualTo(good);
            assertThat(e.initialLoad()).isFalse();
            assertThat(e.committedRows()).isEqualTo(2);
        });

        // the same file again updates instead of duplicating (idempotent by business key)
        UUID again = validated(owner, "CORE_COST_CENTRE", "cc.csv",
                csv.replace("Kế toán", "Kế toán tổng hợp").getBytes(StandardCharsets.UTF_8), null, null, "VALIDATED");
        mvc.perform(get("/api/v1/core/imports/jobs/" + again).with(as(owner))).andExpect(jsonPath("$.warningCount").value(2));
        action(owner, again, "commit");
        await(owner, again, "COMMITTED");
        assertThat(ADMIN.queryForList("select name from core.cost_centre where tenant_id = ? order by code", String.class, tenant))
                .containsExactly("Kế toán tổng hợp", "Bán hàng, miền Bắc");

        // rollback: refused when the job updated records, allowed for a job that only created them
        action(owner, again, "rollback").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("rollback-blocked")).andExpect(jsonPath("$.blockers[0]").exists());
        UUID third = validated(owner, "CORE_COST_CENTRE", "cc3.csv", "Legal entity,Code,Name\nLE1,CC09,Kho\n"
                .getBytes(StandardCharsets.UTF_8), null, null, "VALIDATED");
        action(owner, third, "commit");
        await(owner, third, "COMMITTED");
        action(owner, third, "rollback").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        assertThat(ADMIN.queryForObject("select count(*) from core.cost_centre where tenant_id = ? and code = 'CC09'",
                Integer.class, tenant)).isZero();
        ADMIN.update("update core.import_job set committed_at = now() - interval '25 hours' where id = ?", good);
        action(owner, good, "rollback").andExpect(jsonPath("$.code").value("rollback-window-expired"));

        // staging is encrypted; history lists the jobs
        byte[] staged = ADMIN.queryForObject("select payload_enc from core.import_row where import_job_id = ? and row_no = 2",
                byte[].class, good);
        assertThat(new String(staged, StandardCharsets.ISO_8859_1)).doesNotContain("CC01");
        mvc.perform(get("/api/v1/core/imports/jobs").param("importType", "CORE_COST_CENTRE").with(as(owner)))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void orgUnitsResolveParentsInFileOrder() throws Exception {
        UUID tenant = FIX.tenant("imp-ou");
        TestUser owner = FIX.owner(tenant);
        FIX.legalEntity(tenant, "LE1");
        UUID wrongOrder = validated(owner, "CORE_ORG_UNIT", "ou.xlsx", filled(owner, "CORE_ORG_UNIT", List.of(
                List.of("SALES-N", "Kinh doanh miền Bắc", "", "UNIT", "SALES", "LE1", "", "01/01/2026"),
                List.of("SALES", "Khối kinh doanh", "Sales", "Đơn vị", "", "LE1", "", "01/01/2026")), null),
                null, null, "VALIDATED");
        mvc.perform(get("/api/v1/core/imports/jobs/" + wrongOrder + "/rows").param("status", "ERROR").with(as(owner)))
                .andExpect(jsonPath("$[0].messages[0].code").value("parent-not-found"));

        UUID ok = validated(owner, "CORE_ORG_UNIT", "ou.xlsx", filled(owner, "CORE_ORG_UNIT", List.of(
                List.of("SALES", "Khối kinh doanh", "Sales", "Đơn vị", "", "LE1", "", "01/01/2026"),
                List.of("SALES-N", "Kinh doanh miền Bắc", "", "UNIT", "sales", "LE1", "", "01/01/2026")), null),
                null, null, "VALIDATED");
        action(owner, ok, "commit");
        await(owner, ok, "COMMITTED");
        Map<String, Object> child = ADMIN.queryForMap("""
                select p.code as parent, v.path::text as path from core.org_unit u
                  join core.org_unit_version v on v.tenant_id = u.tenant_id and v.org_unit_id = u.id
                  join core.org_unit p on p.tenant_id = v.tenant_id and p.id = v.parent_id
                 where u.tenant_id = ? and u.code = 'SALES-N'""", tenant);
        assertThat(child).containsEntry("parent", "SALES");
        assertThat((String) child.get("path")).contains(".");

        // a role scoped to an imported unit blocks the rollback
        UUID sales = ADMIN.queryForObject("select id from core.org_unit where tenant_id = ? and code = 'SALES'", UUID.class, tenant);
        UUID role = FIX.role(tenant, "MGR", false, "core.org_unit.view");
        FIX.assign(FIX.user(tenant, "mgr@test.local"), role, "ORG_UNIT", List.of(sales), true, TestFixtures.EPOCH, null);
        action(owner, ok, "rollback").andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.startsWith("Role assignment")));
    }

    @Test
    void openingBalancesFollowTheLoadOrderReconcileAndNeedSignOff() throws Exception {
        UUID tenant = FIX.tenant("imp-ob");
        TestUser owner = FIX.owner(tenant);
        FIX.legalEntity(tenant, "LE1");
        UUID role = FIX.role(tenant, "CB", false, TestBalanceImport.PERMISSION);
        FIX.assign(owner, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        byte[] balances = filled(owner, TestBalanceImport.CODE, List.of(
                List.of("NV001", "1000000.50", "001234567890"),
                List.of("NV002", "2000000", "001234567891"),
                List.of("NV003", "-5", "")), List.of(List.of("AMOUNT_TOTAL", "ALL", "3000000")));

        upload(owner, TestBalanceImport.CODE, "ob.xlsx", balances, "OPENING_BALANCE", null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("as-of-date-required"));
        upload(owner, TestBalanceImport.CODE, "ob.xlsx", balances, "OPENING_BALANCE", "2026-07-01")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("dependency-not-committed"));

        // the org tree first, as an opening balance: no row audit per unit, one summary event per chunk
        UUID tree = validated(owner, "CORE_ORG_UNIT", "ou.xlsx", filled(owner, "CORE_ORG_UNIT", List.of(
                List.of("HQ", "Văn phòng", "", "UNIT", "", "LE1", "", "")), null), "OPENING_BALANCE", "2026-07-01",
                "RECONCILED");
        action(owner, tree, "commit").andExpect(jsonPath("$.code").value("job-not-committable"));
        action(owner, tree, "sign-off").andExpect(jsonPath("$.status").value("SIGNED_OFF"));
        action(owner, tree, "commit");
        await(owner, tree, "COMMITTED");
        assertThat(ADMIN.queryForObject("select v.valid_from::text from core.org_unit u join core.org_unit_version v on v.org_unit_id = u.id where u.tenant_id = ? and u.code = 'HQ'",
                String.class, tenant)).isEqualTo("2026-07-01");
        assertThat(ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ? and object_type in ('org_unit', 'org_unit_version')",
                Integer.class, tenant)).isZero();
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and action = 'IMPORT_CHUNK'
                   and actor_type = 'IMPORT' and channel = 'IMPORT' and correlation_id = ?""",
                Integer.class, tenant, tree.toString())).isEqualTo(1);

        // the balances: one row in error (partial commit allowed), the control total differs
        String body = mvc.perform(get("/api/v1/core/imports/jobs/" + validated(owner, TestBalanceImport.CODE, "ob.xlsx",
                        balances, "OPENING_BALANCE", "2026-07-01", "RECONCILED")).with(as(owner)))
                .andReturn().getResponse().getContentAsString();
        UUID job = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(JsonPath.<String>read(body, "$.dependsOnJobId")).isEqualTo(tree.toString());
        assertThat(JsonPath.<Integer>read(body, "$.errorCount")).isEqualTo(1);
        List<Map<String, Object>> rec = JsonPath.read(body, "$.reconciliation[?(@.checkCode == 'AMOUNT_TOTAL')]");
        assertThat(rec.getFirst()).containsEntry("status", "MISMATCH");
        assertThat(new BigDecimal(rec.getFirst().get("stagedValue").toString())).isEqualByComparingTo("3000000.50");
        // the identity number is sensitive: not in the preview, not readable in staging
        mvc.perform(get("/api/v1/core/imports/jobs/" + job + "/rows").with(as(owner)))
                .andExpect(jsonPath("$[0].preview.id_number").doesNotExist())
                .andExpect(jsonPath("$[0].preview.employee_no").value("NV001"));

        action(owner, job, "sign-off").andExpect(jsonPath("$.code").value("reconciliation-open"));
        byte[] report = mvc.perform(get("/api/v1/core/imports/jobs/" + job + "/reconciliation-report").with(as(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            assertThat(wb.getSheet("DOI_CHIEU").getRow(0).getCell(0).getStringCellValue()).contains("đối chiếu");
        }
        mvc.perform(post("/api/v1/core/imports/jobs/" + job + "/reconciliation/" + rec.getFirst().get("id") + "/waive")
                        .with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(jsonPath("$.code").value("waiver-reason-required"));
        mvc.perform(post("/api/v1/core/imports/jobs/" + job + "/reconciliation/" + rec.getFirst().get("id") + "/waive")
                        .with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Làm tròn 0,50 đ trên bảng tổng hợp của khách hàng\"}"))
                .andExpect(status().isOk());
        action(owner, job, "sign-off").andExpect(jsonPath("$.status").value("SIGNED_OFF"))
                .andExpect(jsonPath("$.signedOffBy").value(owner.userId().toString()));
        action(owner, job, "commit");
        String done = await(owner, job, "COMMITTED");
        assertThat(TestBalanceImport.COMMITTED.get(job)).containsOnlyKeys("NV001", "NV002");
        assertThat(JsonPath.<List<Object>>read(done, "$.reconciliation[?(@.checkCode == 'AMOUNT_TOTAL')].committedValue"))
                .hasSize(1);
        mvc.perform(get("/api/v1/core/imports/jobs/" + job + "/rows").param("status", "SKIPPED").with(as(owner)))
                .andExpect(jsonPath("$[0].rowNo").value(4));
        assertThat(RecordedEvents.of(ImportCommitted.class)).anySatisfy(e -> {
            assertThat(e.jobId()).isEqualTo(job);
            assertThat(e.initialLoad()).isTrue();
        });
        action(owner, job, "rollback").andExpect(jsonPath("$.blockers[0]").value("Payroll run 2026-10 already used these balances"));

        // after go-live the opening balances are locked
        TestOpeningBalanceLocks.lock(tenant);
        upload(owner, "CORE_ORG_UNIT", "ou.xlsx", balances, "OPENING_BALANCE", "2026-07-01")
                .andExpect(jsonPath("$.code").value("opening-balance-locked"));
    }

    @Test
    void aFileOfAnotherTemplateOrVersionIsRefused() throws Exception {
        UUID tenant = FIX.tenant("imp-ver");
        TestUser owner = FIX.owner(tenant);
        FIX.legalEntity(tenant, "LE1");
        byte[] orgTemplate = filled(owner, "CORE_ORG_UNIT", List.of(List.of("X", "X", "", "UNIT", "", "LE1")), null);
        String body = upload(owner, "CORE_COST_CENTRE", "ou.xlsx", orgTemplate, null, null)
                .andReturn().getResponse().getContentAsString();
        UUID job = UUID.fromString(JsonPath.read(body, "$.id"));
        DocumentIT.scanned(scans, tenant, UUID.fromString(JsonPath.read(body, "$.fileDocumentId")), 1, "CLEAN");
        String failed = await(owner, job, "FAILED");
        assertThat(JsonPath.<String>read(failed, "$.failureReason")).contains("CORE_ORG_UNIT");

        String infected = upload(owner, "CORE_COST_CENTRE", "cc.csv", "Code\nX\n".getBytes(), null, null)
                .andReturn().getResponse().getContentAsString();
        DocumentIT.scanned(scans, tenant, UUID.fromString(JsonPath.read(infected, "$.fileDocumentId")), 1, "INFECTED");
        await(owner, UUID.fromString(JsonPath.read(infected, "$.id")), "FAILED");
        upload(owner, "CORE_COST_CENTRE", "cc.txt", "x".getBytes(), null, null)
                .andExpect(jsonPath("$.code").value("media-type-not-allowed"));
    }
}
