package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Opening-balance employee import (EP-PPL-13: US-PPL-089..093, NFR-PPL-007) through Core's import engine: templates,
 * dry run with the streams' rules, cross-sheet dependencies, reconciliation and sign-off, idempotent commit with
 * initial-load events, history sheets, the R20 report and rollback.
 */
class OpeningBalanceImportIT extends EmploymentTestSupport {

    static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final LocalDate GO_LIVE = TODAY.withDayOfMonth(1);
    static final String BANK = "OBBANK";
    static final List<String> MASTER_COLS = List.of("employee_no", "legal_entity", "family_name", "given_name",
            "birth_date", "gender", "national_id_type", "national_id", "tax_code", "si_number", "hire_date",
            "worker_category", "pit_primary", "custom_attributes");

    @Autowired
    DocumentScanResults scans;

    @BeforeAll
    static void referenceData() {
        ADMIN.update("""
                insert into core.admin_unit (admin_unit_code, level, name_vi, parent_code, valid_from, valid_to) values
                  ('OBNEW-P', 'PROVINCE', 'Tỉnh mới (ob)', null, '2025-07-01', null),
                  ('OBNEW-C', 'COMMUNE', 'Xã mới (ob)', 'OBNEW-P', '2025-07-01', null),
                  ('OBNEW-C2', 'COMMUNE', 'Xã mới 2 (ob)', 'OBNEW-P', '2025-07-01', null),
                  ('OBOLD-P', 'PROVINCE', 'Tỉnh cũ (ob)', null, '1976-01-01', '2025-07-01'),
                  ('OBOLD-D', 'DISTRICT', 'Huyện cũ (ob)', 'OBOLD-P', '1976-01-01', '2025-07-01'),
                  ('OBOLD-C', 'COMMUNE', 'Xã cũ (ob)', 'OBOLD-D', '1976-01-01', '2025-07-01'),
                  ('OBSPLIT-C', 'COMMUNE', 'Xã tách (ob)', 'OBOLD-D', '1976-01-01', '2025-07-01')
                on conflict do nothing""");
        ADMIN.update("""
                insert into core.admin_unit_mapping (old_code, new_code, effective_date, mapping_type) values
                  ('OBOLD-C', 'OBNEW-C', '2025-07-01', 'MERGE'), ('OBOLD-P', 'OBNEW-P', '2025-07-01', 'MERGE'),
                  ('OBSPLIT-C', 'OBNEW-C', '2025-07-01', 'SPLIT'), ('OBSPLIT-C', 'OBNEW-C2', '2025-07-01', 'SPLIT')
                on conflict do nothing""");
        ADMIN.update("insert into core.bank (bank_code, short_name, name_vi) values (?, ?, ?) on conflict do nothing",
                BANK, "OB Bank", "Ngân hàng thử OB");
        ADMIN.update("""
                insert into core.code_list (list_code, name_vi, name_en, is_tenant_extensible, is_statutory, owner_module)
                values ('RELATIONSHIP', 'Quan hệ gia đình', 'Relationship', false, true, 'hr') on conflict do nothing""");
        for (String code : List.of("CHILD", "SPOUSE")) {
            ADMIN.update("""
                    insert into core.code_value (tenant_id, list_code, value_code, label_vi, label_en)
                    values (sys.global_tenant_id(), 'RELATIONSHIP', ?, ?, ?) on conflict do nothing""", code, code, code);
        }
    }

    World obWorld(String slug) {
        World w = employmentWorld(slug);
        PersonTestData.codes(ADMIN, w.tenant());
        return w;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static String d(LocalDate date) {
        return date.format(VN);
    }

    /** A workbook with the DATA sheet (header = column keys) and, when given, the CONTROL sheet. */
    static byte[] xlsx(List<String> columns, List<List<String>> rows, List<List<String>> control) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet data = wb.createSheet("DATA");
            List<List<String>> all = new ArrayList<>();
            all.add(columns);
            all.addAll(rows);
            write(data, all);
            if (control != null) {
                List<List<String>> c = new ArrayList<>();
                c.add(List.of("check", "grain", "value"));
                c.addAll(control);
                write(wb.createSheet("CONTROL"), c);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void write(Sheet sheet, List<List<String>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows.get(r).size(); c++) {
                row.createCell(c).setCellValue(rows.get(r).get(c));
            }
        }
    }

    ResultActions upload(TestUser user, String type, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/core/imports/jobs").file(new MockMultipartFile("file", "ob.xlsx",
                        "application/octet-stream", content)).param("importType", type).param("mode", "OPENING_BALANCE")
                .param("asOfDate", GO_LIVE.toString()).with(as(user)));
    }

    /** Uploads, reports a clean scan and waits for the dry run; returns the job body. */
    String dryRun(TestUser user, String type, byte[] content) throws Exception {
        String body = body(upload(user, type, content).andExpect(status().isAccepted()));
        UUID job = UUID.fromString(JsonPath.read(body, "$.id"));
        UUID file = UUID.fromString(JsonPath.read(body, "$.fileDocumentId"));
        String key = ADMIN.queryForObject("select object_key from core.document_version where tenant_id = ? and "
                + "document_id = ? and version_no = 1", String.class, user.tenantId(), file);
        scans.report(user.tenantId(), key, "CLEAN");
        return await(user, job, "RECONCILED");
    }

    String await(TestUser user, UUID job, String expected) throws Exception {
        String last = null;
        for (int i = 0; i < 300; i++) {
            String body = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(user))));
            last = JsonPath.read(body, "$.status");
            if (expected.equals(last)) {
                return body;
            }
            if ("FAILED".equals(last)) {
                throw new AssertionError("Job failed: " + JsonPath.read(body, "$.failureReason"));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + job + " stayed " + last + ", expected " + expected);
    }

    static UUID id(String jobBody) {
        return UUID.fromString(JsonPath.read(jobBody, "$.id"));
    }

    /** Messages of the rows in error: "rowNo:code". */
    List<String> errors(TestUser user, UUID job) throws Exception {
        String body = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job + "/rows").param("status", "ERROR")
                .with(as(user))));
        List<String> out = new ArrayList<>();
        List<Map<String, Object>> rows = JsonPath.read(body, "$");
        for (Map<String, Object> r : rows) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) r.get("messages");
            messages.stream().filter(m -> "ERROR".equals(m.get("severity")))
                    .forEach(m -> out.add(r.get("rowNo") + ":" + m.get("code")));
        }
        return out;
    }

    List<String> warnings(TestUser user, UUID job) throws Exception {
        String body = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job + "/rows").param("status", "WARNING")
                .with(as(user))));
        return JsonPath.read(body, "$[*].messages[*].code");
    }

    /** Sign-off and commit of a clean dry run; returns the committed job body. */
    String commit(TestUser user, String jobBody) throws Exception {
        UUID job = id(jobBody);
        assertThat(JsonPath.<Integer>read(jobBody, "$.errorCount")).as("errors " + errors(user, job)).isZero();
        mvc.perform(post("/api/v1/core/imports/jobs/" + job + "/sign-off").with(as(user)))
                .andExpect(jsonPath("$.status").value("SIGNED_OFF"));
        mvc.perform(post("/api/v1/core/imports/jobs/" + job + "/commit").with(as(user)))
                .andExpect(status().isAccepted());
        return await(user, job, "COMMITTED");
    }

    String load(TestUser user, String type, List<String> cols, List<List<String>> rows, List<List<String>> control)
            throws Exception {
        return commit(user, dryRun(user, type, xlsx(cols, rows, control)));
    }

    static List<String> master(String no, String le, String family, String given, String nid, LocalDate hire,
            String primary) {
        return List.of(no, le, family, given, "01/02/1990", "M", "CCCD", nid, "", "", d(hire), "EMPLOYEE", primary,
                "");
    }

    static List<List<String>> headcount(int n) {
        return List.of(List.of("HEADCOUNT", "ALL", String.valueOf(n)));
    }

    static UUID employmentId(UUID tenant, String no) {
        return ADMIN.queryForObject("select id from hr.employment where tenant_id = ? and employee_no = ?", UUID.class,
                tenant, no);
    }

    // -------------------------------------------------------------------------------------------------- tests

    @Test
    void theMasterSheetIsCheckedLoadedOnceWithInitialLoadEventsAndReRunsWithoutDuplicates() throws Exception {
        World w = obWorld("ob-master");
        secondLe(w);
        LocalDate hire = LocalDate.of(2024, 1, 1);
        List<String> one = master("OB001", "LE", "Phạm", "Một", "001090000101", hire, "");
        List<String> twoA = master("OB002", "LE", "Phạm", "Hai", "001090000102", hire, "");
        List<String> twoB = master("OB003", "LE2", "Phạm", "Hai", "001090000102", hire.plusMonths(5), "Có");
        List<String> six = List.of("OB006", "LE", "Võ", "Sáu", "03/04/1992", "F", "CCCD", "001092000106",
                "8001234567", "0123456789", d(hire), "EMPLOYEE", "", "nhom_mau=O; so_the_cu=12");
        List<List<String>> bad = List.of(one, twoA, twoB,
                master("OB004", "LE", "Lỗi", "Bốn", "12345", hire, ""),
                master("OB005", "LE", "Phạm", "Một", "001090000101", hire, ""), six);

        // dry run: nothing is written, the stream rules decide
        String checked = dryRun(w.hr(), "OB_EMPLOYEE_MASTER", xlsx(MASTER_COLS, bad, null));
        UUID job = id(checked);
        assertThat(errors(w.hr(), job)).containsExactlyInAnyOrder("5:invalid-national-id",
                "6:duplicate-person-in-legal-entity");
        assertThat(warnings(w.hr(), job)).contains("unmapped-custom-attribute");
        assertThat(count("select count(*) from hr.employment where tenant_id = ? and source = 'OPENING_LOAD'",
                w.tenant())).isZero();
        mvc.perform(post("/api/v1/core/imports/jobs/" + job + "/sign-off").with(as(w.hr())))
                .andExpect(status().isConflict());

        // the corrected file reconciles with the control sheet, is signed off and committed
        List<List<String>> good = List.of(one, twoA, twoB, six);
        List<List<String>> control = List.of(List.of("HEADCOUNT", "ALL", "4"), List.of("ROW_COUNT", "ALL", "4"));
        String done = load(w.hr(), "OB_EMPLOYEE_MASTER", MASTER_COLS, good, control);
        assertThat(JsonPath.<List<String>>read(done, "$.reconciliation[*].status")).containsOnly("MATCHED");
        assertThat(JsonPath.<List<Object>>read(done, "$.reconciliation[?(@.checkCode == 'HEADCOUNT')].committedValue")
                .getFirst().toString()).startsWith("4");
        assertThat(ADMIN.queryForList("""
                select employee_no || ':' || status || ':' || hire_type from hr.employment
                 where tenant_id = ? and source = 'OPENING_LOAD' order by employee_no""", String.class, w.tenant()))
                .containsExactly("OB001:ACTIVE:NEW", "OB002:ACTIVE:NEW", "OB003:ACTIVE:CONCURRENT", "OB006:ACTIVE:NEW");
        assertThat(count("select count(*) from hr.person where tenant_id = ? and source = 'OPENING_LOAD'", w.tenant()))
                .isEqualTo(3);
        // the concurrent person: the flagged employment is the PIT primary from the day both run
        assertThat(ADMIN.queryForMap("""
                select employment_id, valid_from, source from hr.primary_employment where tenant_id = ?""", w.tenant()))
                .containsEntry("employment_id", employmentId(w.tenant(), "OB003"))
                .containsEntry("valid_from", java.sql.Date.valueOf(hire.plusMonths(5)))
                .containsEntry("source", "OPENING_LOAD");
        // one EmployeeHired per employment, flagged initial load, no P3
        List<Map<String, Object>> hired = ADMIN.queryForList("""
                select payload::text as payload, headers::text as headers from hr.outbox_event
                 where tenant_id = ? and event_type = 'people.EmployeeHired'""", w.tenant());
        assertThat(hired).hasSize(4).allSatisfy(e -> {
            assertThat(e.get("headers").toString()).contains("\"initial_load\": true");
            assertThat(e.get("payload").toString()).contains("\"initialLoad\": true").doesNotContain("001090000101");
        });
        // P3 staged encrypted and never in the preview
        mvc.perform(get("/api/v1/core/imports/jobs/" + id(done) + "/rows").with(as(w.hr())))
                .andExpect(jsonPath("$[0].preview.national_id").doesNotExist())
                .andExpect(jsonPath("$[0].preview.employee_no").value("OB001"));

        // re-running the same file updates, never duplicates, and publishes nothing new
        String again = load(w.hr(), "OB_EMPLOYEE_MASTER", MASTER_COLS, good, control);
        assertThat(ADMIN.queryForList("select distinct commit_action from core.import_row where import_job_id = ?",
                String.class, id(again))).containsExactly("UPDATED");
        assertThat(count("select count(*) from hr.employment where tenant_id = ? and source = 'OPENING_LOAD'",
                w.tenant())).isEqualTo(4);
        assertThat(count("select count(*) from hr.outbox_event where tenant_id = ? and event_type = 'people.EmployeeHired'",
                w.tenant())).isEqualTo(4);
    }

    @Test
    void laterSheetsDependOnTheMasterAndApplyTheAssignmentContractAndSalaryRules() throws Exception {
        World w = obWorld("ob-chain");
        LocalDate hire = LocalDate.of(2024, 1, 1);
        LocalDate salaryFrom = LocalDate.of(2025, 1, 1);
        String masterJob = load(w.hr(), "OB_EMPLOYEE_MASTER", MASTER_COLS,
                List.of(master("OB010", "LE", "Đỗ", "Mười", "001090000110", hire, "")), headcount(1));

        // the chain is enforced by Core: the contract needs the assignment load first
        upload(w.hr(), "OB_EMP_CONTRACT", xlsx(List.of("employee_no"), List.of(List.of("OB010")), null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("dependency-not-committed"));

        load(w.hr(), "OB_EMP_ASSIGNMENT", List.of("employee_no", "valid_from", "org_unit", "location"),
                List.of(List.of("OB010", d(hire), "U1", "HN")), null);
        UUID emp = employmentId(w.tenant(), "OB010");
        assertThat(ADMIN.queryForMap("select org_unit_id, source from hr.assignment where employment_id = ?", emp))
                .containsEntry("org_unit_id", w.unit().id()).containsEntry("source", "OPENING_LOAD");
        // a master load with dependent data cannot be rolled back
        mvc.perform(post("/api/v1/core/imports/jobs/" + id(masterJob) + "/rollback").with(as(w.hr())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("rollback-blocked"));

        LocalDate end = GO_LIVE.plusMonths(12);
        load(w.hr(), "OB_EMP_CONTRACT", List.of("employee_no", "contract_no", "contract_type", "sign_date",
                        "start_date", "end_date", "prior_definite_count"),
                List.of(List.of("OB010", "HD-OB-010", "DEFINITE", d(salaryFrom), d(salaryFrom), d(end), "1")), null);
        assertThat(ADMIN.queryForMap("select status, definite_seq, contract_no from hr.contract where employment_id = ?",
                emp)).containsEntry("status", "ACTIVE").containsEntry("contract_no", "HD-OB-010");
        assertThat(ADMIN.queryForObject("select definite_seq from hr.contract where employment_id = ?", Integer.class,
                emp)).isEqualTo(2);

        List<String> salaryCols = List.of("employee_no", "valid_from", "pay_basis", "salary_kind", "base_salary",
                "si_salary", "allowance_1_code", "allowance_1_amount");
        // below the regional minimum wage on the go-live date: blocked, as on screen
        String low = dryRun(w.hr(), "OB_EMP_SALARY", xlsx(salaryCols,
                List.of(List.of("OB010", d(salaryFrom), "GROSS", "MONTHLY", "3000000", "3000000", "", "")), null));
        assertThat(errors(w.hr(), id(low))).containsExactly("2:below-minimum-wage");
        String salaryJob = load(w.hr(), "OB_EMP_SALARY", salaryCols,
                List.of(List.of("OB010", d(salaryFrom), "GROSS", "MONTHLY", "15000000", "15000000", "LUNCH", "730000")),
                List.of(List.of("SALARY_TOTAL", "ALL", "15000000")));
        assertThat(ADMIN.queryForMap("""
                select base_salary_amount, source, decision_id from hr.salary_record where employment_id = ?""", emp))
                .containsEntry("source", "OPENING_LOAD").containsEntry("decision_id", null);
        assertThat(events(w.tenant(), "people.SalaryChanged")).singleElement()
                .satisfies(p -> assertThat(p).contains("\"initialLoad\": true"));
        mvc.perform(post("/api/v1/core/imports/jobs/" + id(salaryJob) + "/rollback").with(as(w.hr())))
                .andExpect(status().isConflict());

        // history: closed versions before the current salary, no events
        List<String> histCols = List.of("employee_no", "valid_from", "valid_to", "pay_basis", "salary_kind",
                "base_salary");
        String overlap = dryRun(w.hr(), "OB_EMP_SALARY_HISTORY", xlsx(histCols,
                List.of(List.of("OB010", "01/06/2024", "30/06/2025", "GROSS", "MONTHLY", "12000000")), null));
        assertThat(errors(w.hr(), id(overlap))).containsExactly("2:history-overlaps-current");
        load(w.hr(), "OB_EMP_SALARY_HISTORY", histCols,
                List.of(List.of("OB010", d(hire), "31/12/2024", "GROSS", "MONTHLY", "12000000")), null);
        assertThat(ADMIN.queryForList("""
                select valid_from || '>' || coalesce(valid_to::text, '-') from hr.salary_record
                 where employment_id = ? and superseded_at is null order by valid_from""", String.class, emp))
                .containsExactly("2024-01-01>2025-01-01", "2025-01-01>-");
        assertThat(events(w.tenant(), "people.SalaryChanged")).hasSize(1);

        // R20: the employee lacks an SI number and a bank account, the rest is there
        mvc.perform(get("/api/v1/people/opening-balance/report").param("asOf", GO_LIVE.toString()).with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalEntities[0].employees").value(1))
                .andExpect(jsonPath("$.legalEntities[0].activeHeadcount").value(1))
                .andExpect(jsonPath("$.legalEntities[0].salaryTotal").value(15000000.0))
                .andExpect(jsonPath("$.legalEntities[0].missingBankAccount").value(1))
                .andExpect(jsonPath("$.discrepancies[0].employeeNo").value("OB010"))
                .andExpect(jsonPath("$.discrepancies[0].issues").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "MISSING_SI_NUMBER", "MISSING_BANK_ACCOUNT")));
        byte[] xlsx = mvc.perform(get("/api/v1/people/opening-balance/report/export").param("asOf", GO_LIVE.toString())
                .with(as(w.hr()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("CHENH_LECH").getRow(1).getCell(0).getStringCellValue()).isEqualTo("OB010");
        }
    }

    @Test
    void contactsFamilyBankAccountsAndServiceHistoryUseTheStreamsAndRollBack() throws Exception {
        World w = obWorld("ob-side");
        LocalDate hire = LocalDate.of(2024, 1, 1);
        load(w.hr(), "OB_EMPLOYEE_MASTER", MASTER_COLS,
                List.of(master("OB020", "LE", "Bùi", "Hai Mươi", "001090000120", hire, "")), headcount(1));
        UUID emp = employmentId(w.tenant(), "OB020");
        UUID person = ADMIN.queryForObject("select person_id from hr.employment where id = ?", UUID.class, emp);

        // contacts: legacy three-level codes are mapped; an ambiguous split is refused
        List<String> contactCols = List.of("employee_no", "record_type", "legacy_province_code",
                "legacy_district_code", "legacy_commune_code", "address_line", "value");
        String split = dryRun(w.hr(), "OB_EMP_CONTACT", xlsx(contactCols, List.of(
                List.of("OB020", "PERMANENT", "OBOLD-P", "OBOLD-D", "OBOLD-C", "Số 1", ""),
                List.of("OB020", "CURRENT", "OBOLD-P", "OBOLD-D", "OBSPLIT-C", "Số 2", "")), null));
        assertThat(errors(w.hr(), id(split))).containsExactly("3:admin-unit-unmapped");
        String contactJob = load(w.hr(), "OB_EMP_CONTACT", contactCols, List.of(
                List.of("OB020", "PERMANENT", "OBOLD-P", "OBOLD-D", "OBOLD-C", "Số 1", ""),
                List.of("OB020", "MOBILE", "", "", "", "", "0912345678")), null);
        assertThat(ADMIN.queryForMap("""
                select province_code, commune_code, legacy_commune_code, mapping_status from hr.person_address
                 where person_id = ?""", person))
                .containsEntry("province_code", "OBNEW-P").containsEntry("commune_code", "OBNEW-C")
                .containsEntry("legacy_commune_code", "OBOLD-C").containsEntry("mapping_status", "MAPPED");
        assertThat(count("select count(*) from hr.person_contact where person_id = ? and contact_type = 'MOBILE'",
                person)).isEqualTo(1);

        // the addresses and contacts the job created are rolled back (DELETE on hr.person_address, DDL v0.6)
        mvc.perform(post("/api/v1/core/imports/jobs/" + id(contactJob) + "/rollback").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        assertThat(count("select count(*) from hr.person_address where person_id = ?", person)).isZero();
        assertThat(count("select count(*) from hr.person_contact where person_id = ?", person)).isZero();

        // family: a registered child dependant with its months, and a claim on a spouse already entered in the app
        UUID spouse = Ids.newId();
        ADMIN.update("""
                insert into hr.family_member (tenant_id, id, person_id, relationship_code, full_name, birth_date)
                values (?, ?, ?, 'SPOUSE', 'Lê Vợ', '1991-05-01')""", w.tenant(), spouse, person);
        List<String> famCols = List.of("employee_no", "member_ref", "relationship", "full_name", "birth_date",
                "dependant_from", "dependant_reason");
        String famJob = load(w.hr(), "OB_EMP_FAMILY", famCols, List.of(
                List.of("OB020", "1", "CHILD", "Bùi Con", "01/05/2015", "01/01/2025", "CHILD_UNDER_AGE"),
                List.of("OB020", "2", "SPOUSE", "Lê Vợ", "01/05/1991", "01/01/2025", "SPOUSE")),
                List.of(List.of("DEPENDANTS", "ALL", "2")));
        assertThat(count("select count(*) from hr.family_member where person_id = ?", person)).isEqualTo(2);
        assertThat(ADMIN.queryForList("select status || ' ' || from_month || ' ' || source from hr.pit_dependant "
                + "where employment_id = ?", String.class, emp))
                .containsOnly("APPROVED 2025-01-01 OPENING_LOAD");
        // something entered in the app on an imported member blocks the rollback
        UUID child = ADMIN.queryForObject("select id from hr.family_member where person_id = ? and relationship_code "
                + "= 'CHILD'", UUID.class, person);
        ADMIN.update("""
                insert into hr.emergency_contact (tenant_id, person_id, family_member_id, full_name, phone, priority)
                values (?, ?, ?, 'Bùi Con', '0912000000', 1)""", w.tenant(), person, child);
        mvc.perform(post("/api/v1/core/imports/jobs/" + id(famJob) + "/rollback").with(as(w.hr())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("rollback-blocked"));
        ADMIN.update("delete from hr.emergency_contact where person_id = ?", person);
        // the rollback removes the imported child and both loaded claims; the spouse entered in the app stays
        mvc.perform(post("/api/v1/core/imports/jobs/" + id(famJob) + "/rollback").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        assertThat(ADMIN.queryForList("select id from hr.family_member where person_id = ?", UUID.class, person))
                .containsExactly(spouse);
        assertThat(count("select count(*) from hr.pit_dependant where employment_id = ?", emp)).isZero();

        // bank: ACTIVE without approval; the job can be rolled back within 24 hours
        String bankJob = load(w.hr(), "OB_EMP_BANK", List.of("employee_no", "priority", "bank_code", "account_no",
                        "holder_name"), List.of(List.of("OB020", "1", BANK, "0123456789012", "BUI HAI MUOI")),
                List.of(List.of("BANK_ACCOUNTS", "ALL", "1")));
        assertThat(ADMIN.queryForObject("select status from hr.bank_account where person_id = ?", String.class, person))
                .isEqualTo("ACTIVE");
        mvc.perform(post("/api/v1/core/imports/jobs/" + id(bankJob) + "/rollback").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        assertThat(count("select count(*) from hr.bank_account where person_id = ?", person)).isZero();

        // service history: an earlier employment with paid severance, as closed records
        List<String> histCols = List.of("employee_no", "period_kind", "period_from", "period_to");
        String orphan = dryRun(w.hr(), "OB_EMP_SERVICE_HISTORY", xlsx(histCols, List.of(
                List.of("OB020", "WORKED", "01/01/2018", "31/12/2020"),
                List.of("OB020", "PAID_SEVERANCE", "01/01/2018", "31/12/2020"),
                List.of("OB020", "PAID_JOB_LOSS", "01/01/2015", "31/12/2015")), null));
        assertThat(errors(w.hr(), id(orphan))).containsExactly("4:history-holder-missing");
        load(w.hr(), "OB_EMP_SERVICE_HISTORY", histCols, List.of(
                List.of("OB020", "WORKED", "01/01/2018", "31/12/2020"),
                List.of("OB020", "PAID_SEVERANCE", "01/01/2018", "31/12/2020")), null);
        assertThat(ADMIN.queryForMap("""
                select e.status, e.end_date, s.status as sep, sp.period_kind from hr.employment e
                  join hr.separation s on s.employment_id = e.id
                  join hr.separation_service_period sp on sp.separation_id = s.id
                 where e.person_id = ? and e.id <> ?""", person, emp))
                .containsEntry("status", "TERMINATED").containsEntry("end_date", java.sql.Date.valueOf("2020-12-31"))
                .containsEntry("sep", "COMPLETED").containsEntry("period_kind", "PAID_SEVERANCE");
    }

    @Test
    void templatesPermissionsAndRollbackOfACleanMasterLoad() throws Exception {
        World w = obWorld("ob-perm");
        byte[] template = mvc.perform(get("/api/v1/core/imports/types/OB_EMPLOYEE_MASTER/template").param("lang", "vi")
                .with(as(w.hr()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertThat(wb.getSheet("DATA").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Mã nhân viên *");
            assertThat(wb.getSheet("_lists").getRow(0).getCell(0).getStringCellValue()).startsWith("LE");
        }
        mvc.perform(get("/api/v1/core/imports/types").with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.code == 'OB_EMP_SALARY')].openingBalance").value(
                        org.hamcrest.Matchers.hasItem(true)));

        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        upload(viewer, "OB_EMPLOYEE_MASTER", xlsx(MASTER_COLS, List.of(), null)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/people/opening-balance/report").with(as(viewer))).andExpect(status().isForbidden());

        List<List<String>> rows = List.of(master("OB030", "LE", "Hồ", "Ba Mươi", "001090000130",
                LocalDate.of(2024, 3, 1), ""));
        String job = load(w.hr(), "OB_EMPLOYEE_MASTER", MASTER_COLS, rows, headcount(1));
        // another tenant sees nothing of this load
        World other = obWorld("ob-perm-other");
        mvc.perform(get("/api/v1/people/opening-balance/report").with(as(other.hr())))
                .andExpect(jsonPath("$.legalEntities").isEmpty());
        mvc.perform(get("/api/v1/core/imports/jobs/" + id(job)).with(as(other.hr()))).andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/core/imports/jobs/" + id(job) + "/rollback").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        // rolled back = CANCELLED (People's role cannot delete employments); a re-load re-activates it
        assertThat(ADMIN.queryForObject("select status from hr.employment where tenant_id = ? and employee_no = 'OB030'",
                String.class, w.tenant())).isEqualTo("CANCELLED");
        String reloaded = load(w.hr(), "OB_EMPLOYEE_MASTER", MASTER_COLS, rows, headcount(1));
        assertThat(ADMIN.queryForObject("select commit_action from core.import_row where import_job_id = ?",
                String.class, id(reloaded))).isEqualTo("CREATED");
        assertThat(ADMIN.queryForObject("select status from hr.employment where tenant_id = ? and employee_no = 'OB030'",
                String.class, w.tenant())).isEqualTo("ACTIVE");
        assertThat(count("select count(*) from hr.person where tenant_id = ? and source = 'OPENING_LOAD'",
                w.tenant())).isEqualTo(1);
    }
}
