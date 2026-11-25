package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.schedule.AssignmentProjection;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Punch sources and raw punches (BRD 03 §4.3: BR-ATT-020/022/025/027; US-ATT-013..017, 021): device registry,
 * enrolment mappings, punch import through Core's engine (standard template, ZKTeco and Hikvision exports), dedup,
 * quarantine and its resolution, immutability and the audited read.
 */
class PunchImportIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    /** A day of the previous month: its raw_punch partition exists, and it is not in the future. */
    static final LocalDate DAY = LocalDate.now(VN).minusMonths(1).withDayOfMonth(10);

    @Autowired
    AssignmentProjection projection;

    @Autowired
    DocumentScanResults scans;

    record World(UUID tenant, TestUser admin, UUID le, UUID e1, UUID e2) {
    }

    World world(String slug) {
        UUID tenant = FIX.tenant(slug);
        List<String> perms = new ArrayList<>(FIX.allCorePermissions());
        perms.addAll(List.of(AttendancePermissions.DEVICE_VIEW, AttendancePermissions.DEVICE_UPDATE,
                AttendancePermissions.PUNCH_VIEW, AttendancePermissions.PUNCH_IMPORT,
                AttendancePermissions.PUNCH_RESOLVE));
        UUID role = FIX.role(tenant, "TA_ADMIN", false, perms.toArray(String[]::new));
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        UUID le = FIX.legalEntity(tenant, "LE1");
        World w = new World(tenant, admin, le, Ids.newId(), Ids.newId());
        employment(w, w.e1());
        employment(w, w.e2());
        return w;
    }

    /** An employment known to T&A (projection as fed by People), hired long ago, with one PRIMARY line. */
    void employment(World w, UUID employment) {
        ADMIN.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       status, worker_category_code, source_event_id, source_version)
                values (?, ?, ?, ?, date '2020-01-01', 'ACTIVE', 'EMPLOYEE', ?, 1)""",
                w.tenant(), employment, Ids.newId(), w.le(), Ids.newId());
        TenantContext ctx = new TenantContext(w.tenant(), w.admin().userId(), w.admin().identityId().toString(),
                "it", "vi");
        TenantContext.runWith(ctx, () -> projection.apply(new LineVersion(Ids.newId(), Ids.newId(), employment,
                Ids.newId(), "PRIMARY", w.le(), Ids.newId(), null, null, null, null, null, "STAFF", "FULL_TIME",
                BigDecimal.ONE, LocalDate.of(2020, 1, 1), null), false));
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    UUID device(World w, String code, String terminal, String layout) throws Exception {
        return UUID.fromString(JsonPath.read(body(postJson(w.admin(), "/devices", """
                {"code":"%s","name":"Máy %s","terminalCode":"%s","vendor":"zkteco","legalEntityId":"%s",
                 "fileLayoutCode":"%s"}""".formatted(code, code, terminal, w.le(), layout))
                .andExpect(status().isCreated())), "$.id"));
    }

    void mapping(World w, UUID device, String enrol, UUID employment, LocalDate from, LocalDate to)
            throws Exception {
        postJson(w.admin(), "/enrolment-mappings", """
                {%s"enrolId":"%s","employmentId":"%s","validFrom":"%s"%s}""".formatted(
                device == null ? "" : "\"deviceId\":\"" + device + "\",", enrol, employment, from,
                to == null ? "" : ",\"validTo\":\"" + to + "\"")).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------------------------------- import helpers

    UUID validated(TestUser user, String type, String fileName, byte[] content) throws Exception {
        String b = mvc.perform(multipart("/api/v1/core/imports/jobs")
                        .file(new MockMultipartFile("file", fileName, "application/octet-stream", content))
                        .param("importType", type).with(as(user)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID job = UUID.fromString(JsonPath.read(b, "$.id"));
        UUID file = UUID.fromString(JsonPath.read(b, "$.fileDocumentId"));
        String key = ADMIN.queryForObject("select object_key from core.document_version where tenant_id = ?"
                + " and document_id = ? and version_no = 1", String.class, user.tenantId(), file);
        scans.report(user.tenantId(), key, "CLEAN");
        await(user, job, "VALIDATED");
        return job;
    }

    String await(TestUser user, UUID job, String expected) throws Exception {
        String last = null;
        for (int i = 0; i < 300; i++) {
            String b = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(user))));
            last = JsonPath.read(b, "$.status");
            if (expected.equals(last)) {
                return b;
            }
            if ("FAILED".equals(last)) {
                throw new AssertionError("Job failed: " + JsonPath.read(b, "$.failureReason"));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + job + " stayed " + last + ", expected " + expected);
    }

    void commit(TestUser user, UUID job) throws Exception {
        mvc.perform(post("/api/v1/core/imports/jobs/" + job + "/commit").with(as(user)))
                .andExpect(status().isAccepted());
        await(user, job, "COMMITTED");
    }

    ResultActions rows(TestUser user, UUID job, String status) throws Exception {
        return mvc.perform(get("/api/v1/core/imports/jobs/" + job + "/rows").param("status", status).with(as(user)));
    }

    List<Map<String, Object>> punches(UUID tenant) {
        return ADMIN.queryForList("""
                select terminal_code, enrol_id, to_char(local_ts, 'HH24:MI:SS') as local,
                       to_char(punch_at at time zone 'UTC', 'HH24:MI:SS') as utc, direction, validation_status,
                       employment_id, enrolment_mapping_id, legal_entity_id
                  from att.raw_punch where tenant_id = ? order by punch_at, terminal_code""", tenant);
    }

    // -------------------------------------------------------------------------------------------------- tests

    @Test
    void theStandardTemplateMatchesQuarantinesDedupesAndIsResolvedWithoutTouchingEvidence() throws Exception {
        World w = world("att-punch");
        UUID t01 = device(w, "D01", "T01", "STANDARD");
        mapping(w, null, "101", w.e1(), DAY.minusMonths(6), null); // tenant-wide
        mapping(w, t01, "102", w.e2(), DAY.minusMonths(6), DAY); // ends before the punch day
        String d = DAY.toString();
        String old = LocalDate.now(VN).minusYears(2).toString(); // no partition
        String csv = String.join("\n",
                "terminal,enrol_id,punch_date,punch_time,direction,verify_method",
                "T01,101," + d + ",07:58,VÀO,Vân tay",
                "t01,101," + d + ",17:05:30,Ra,",
                "T01,101," + d + ",07:58:00,in,",           // same punch again in the file
                "T01,102," + d + ",08:00,IN,",              // mapping expired
                "T01,999," + d + ",08:01,,",                // unmapped
                "T99,101," + d + ",08:02,IN,",              // unregistered terminal
                "T01,101," + d + ",25:00,IN,",              // invalid time
                "T01,101," + old + ",08:00,IN,") + "\n";    // month not open
        UUID job = validated(w.admin(), "ATT_PUNCH_EXCEL", "punches.csv", csv.getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(w.admin())))
                .andExpect(jsonPath("$.rowCount").value(8)).andExpect(jsonPath("$.errorCount").value(2));
        rows(w.admin(), job, "ERROR")
                .andExpect(jsonPath("$[0].messages[0].code").value("invalid-time"))
                .andExpect(jsonPath("$[1].messages[0].code").value("punch-month-not-open"));
        rows(w.admin(), job, "WARNING")
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("duplicate-in-file")))
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("quarantined-expired")))
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("quarantined-unmapped")))
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("quarantined-unknown-device")));
        assertThat(punches(w.tenant())).isEmpty(); // the dry run wrote nothing
        commit(w.admin(), job);

        List<Map<String, Object>> rows = punches(w.tenant());
        assertThat(rows).hasSize(5);
        assertThat(rows.getFirst()).containsEntry("terminal_code", "T01").containsEntry("local", "07:58:00")
                .containsEntry("utc", "00:58:00").containsEntry("direction", "IN")
                .containsEntry("validation_status", "VALID").containsEntry("employment_id", w.e1())
                .containsEntry("legal_entity_id", w.le());
        assertThat(rows.stream().map(r -> r.get("enrol_id") + ":" + r.get("validation_status")).toList())
                .containsExactly("101:VALID", "102:QUARANTINED_EXPIRED", "999:QUARANTINED_UNMAPPED",
                        "101:QUARANTINED_UNKNOWN_DEVICE", "101:VALID");
        assertThat(ADMIN.queryForObject("select last_import_at is not null from att.device where id = ?",
                Boolean.class, t01)).isTrue();

        // re-uploading the same file creates no duplicate
        UUID again = validated(w.admin(), "ATT_PUNCH_EXCEL", "punches.csv", csv.getBytes(StandardCharsets.UTF_8));
        rows(w.admin(), again, "WARNING").andExpect(jsonPath("$[*].messages[*].code", hasItem("already-imported")));
        commit(w.admin(), again);
        assertThat(punches(w.tenant())).hasSize(5);

        // quarantine: list, counts, map + register, then re-match
        String from = DAY.minusDays(1).toString();
        String to = DAY.plusDays(1).toString();
        mvc.perform(get(BASE + "/punches/quarantine").param("from", from).param("to", to).with(as(w.admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get(BASE + "/punches/quarantine/counts").param("from", from).param("to", to)
                        .with(as(w.admin())))
                .andExpect(jsonPath("$.QUARANTINED_UNMAPPED").value(1));
        mapping(w, t01, "999", w.e2(), DAY.minusMonths(1), null);
        device(w, "D99", "T99", "STANDARD");
        postJson(w.admin(), "/punches/quarantine/rematch", """
                {"from":"%s","to":"%s"}""".formatted(from, to)).andExpect(status().isOk())
                .andExpect(jsonPath("$.examined").value(3)).andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.stillQuarantined").value(1));
        assertThat(punches(w.tenant()).stream().filter(r -> "VALID".equals(r.get("validation_status"))))
                .hasSize(4);
        // the evidence of the re-matched punch is unchanged: still no device id, terminal as in the file
        assertThat(ADMIN.queryForObject("select device_id is null from att.raw_punch where tenant_id = ?"
                + " and terminal_code = 'T99'", Boolean.class, w.tenant())).isTrue();

        // ignore the expired one with a reason; a resolved punch cannot be resolved again
        Map<String, Object> expired = ADMIN.queryForMap("select id, work_date_hint::text as hint from att.raw_punch"
                + " where tenant_id = ? and validation_status = 'QUARANTINED_EXPIRED'", w.tenant());
        String ignore = """
                {"punches":[{"id":"%s","workDateHint":"%s"}],"reason":"Thẻ thử nghiệm"}"""
                .formatted(expired.get("id"), expired.get("hint"));
        postJson(w.admin(), "/punches/quarantine/ignore", ignore).andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(1));
        postJson(w.admin(), "/punches/quarantine/ignore", ignore).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("punch-resolved"));
        assertThat(ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ?"
                + " and action = 'IGNORE' and object_type = 'raw_punch'", Integer.class, w.tenant())).isOne();

        // raw punches are evidence: no change of evidence columns, no delete (BR-ATT-027)
        assertThatThrownBy(() -> ADMIN.update("update att.raw_punch set local_ts = local_ts + interval '1 hour'"
                + " where tenant_id = ?", w.tenant())).hasMessageContaining("immutable");
        assertThatThrownBy(() -> ADMIN.update("delete from att.raw_punch where tenant_id = ?", w.tenant()))
                .hasMessageContaining("append-only");

        // the employee's punches, read with an audit entry
        mvc.perform(get(BASE + "/employments/" + w.e1() + "/punches").param("from", from).param("to", to)
                        .with(as(w.admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].verifyMethod").value("FINGERPRINT"));
        assertThat(ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ?"
                + " and action = 'VIEW' and object_type = 'raw_punch'", Integer.class, w.tenant())).isOne();
    }

    @Test
    void deviceExportsAreReadWithTheVendorHeadersAndBiometricColumnsAreRefused() throws Exception {
        World w = world("att-export");
        UUID zk = device(w, "ZK1", "1", "ZKTECO");
        mapping(w, zk, "7", w.e1(), DAY.minusMonths(6), null);

        // a ZKTeco workbook as the device software writes it: one sheet, its own headers, no terminal column
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Sheet1");
            String dmy = DAY.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            List<List<String>> data = List.of(List.of("AC-No.", "Name", "Time", "State"),
                    List.of("7", "Nguyễn Văn A", dmy + " 07:55:10", "C/In"),
                    List.of("7", "Nguyễn Văn A", dmy + " 17:31:00", "C/Out"));
            for (int r = 0; r < data.size(); r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < data.get(r).size(); c++) {
                    row.createCell(c).setCellValue(data.get(r).get(c));
                }
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }
        UUID job = validated(w.admin(), "ATT_PUNCH_ZKTECO", "AttLog.xlsx", xlsx);
        mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(w.admin())))
                .andExpect(jsonPath("$.rowCount").value(2)).andExpect(jsonPath("$.errorCount").value(0));
        commit(w.admin(), job);
        assertThat(punches(w.tenant()).stream().map(r -> r.get("terminal_code") + "/" + r.get("direction") + "/"
                + r.get("validation_status")).toList()).containsExactly("1/IN/VALID", "1/OUT/VALID");

        // a Hikvision export with a photo column is refused row by row (NFR-ATT-008)
        device(w, "HK1", "Cổng chính", "HIKVISION");
        String csv = "Employee ID,Time,Attendance Status,Attendance Check Point,Photo\n"
                + "7," + DAY + " 08:00:00,Check-in,Cổng chính,base64...\n"
                + "7," + DAY + " 18:00:00,Check-out,Cổng chính,\n";
        UUID hk = validated(w.admin(), "ATT_PUNCH_HIKVISION", "hik.csv", csv.getBytes(StandardCharsets.UTF_8));
        rows(w.admin(), hk, "ERROR").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].messages[0].code").value("biometric-data-refused"));
    }

    @Test
    void mappingsDoNotOverlapAndDevicesFollowTheDataScope() throws Exception {
        World w = world("att-scope");
        UUID dev = device(w, "D01", "T01", "STANDARD");
        mapping(w, dev, "101", w.e1(), DAY, null);
        postJson(w.admin(), "/enrolment-mappings", """
                {"deviceId":"%s","enrolId":"101","employmentId":"%s","validFrom":"%s"}"""
                .formatted(dev, w.e2(), DAY.plusDays(5))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("enrolment-mapping-overlap"));
        // the same enrol id tenant-wide is another scope: allowed
        mapping(w, null, "101", w.e2(), DAY, null);
        mvc.perform(get(BASE + "/enrolment-mappings").param("enrolId", "101").with(as(w.admin())))
                .andExpect(jsonPath("$", hasSize(2)));
        postJson(w.admin(), "/devices", """
                {"code":"D02","name":"Máy 2","terminalCode":"t01","legalEntityId":"%s"}""".formatted(w.le()))
                .andExpect(status().isCreated()); // terminal codes are case-sensitive in the registry
        postJson(w.admin(), "/devices", """
                {"code":"D03","name":"Máy 3","terminalCode":"T03","legalEntityId":"%s","timeZone":"Mars/Base"}"""
                .formatted(w.le())).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-time-zone"));

        // a T&A admin of another legal entity sees none of these devices
        UUID le2 = FIX.legalEntity(w.tenant(), "LE2");
        UUID role = FIX.role(w.tenant(), "TA_LE2", false, AttendancePermissions.DEVICE_VIEW,
                AttendancePermissions.DEVICE_UPDATE);
        TestUser other = FIX.user(w.tenant(), "le2-" + w.tenant().toString().substring(24) + "@test.local");
        FIX.assign(other, role, "LEGAL_ENTITY", List.of(le2), false, TestFixtures.EPOCH, null);
        mvc.perform(get(BASE + "/devices").with(as(other))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get(BASE + "/devices/" + dev).with(as(other))).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/enrolment-mappings").with(as(other))).andExpect(jsonPath("$", hasSize(0)));
    }
}
