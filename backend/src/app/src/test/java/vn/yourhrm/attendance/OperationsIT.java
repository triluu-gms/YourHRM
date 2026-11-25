package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendanceJobs;
import vn.yourhrm.attendance.api.AttendanceJobs.NightlySummary;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.AttendanceResults;
import vn.yourhrm.attendance.api.PayrollInbound;
import vn.yourhrm.attendance.api.PayrollInbound.PayCalendarPublished;
import vn.yourhrm.attendance.api.PayrollInbound.PayGroupAssigned;
import vn.yourhrm.attendance.api.PayrollInbound.PayPeriod;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.application.schedule.AssignmentProjection;
import vn.yourhrm.attendance.internal.infra.jobs.AttendanceJobStore;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.PostgresTestDatabase;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * The Phase-1 completion items of the T&amp;A backend: the daily-timesheet import (US-ATT-018) and the Attendance
 * Result import (US-ATT-019, BR-ATT-025 baseline), the location projection fed from Core (NFR-ATT-005), the nightly
 * job with partition maintenance (BR-ATT-038 / 073 / 088, NFR-ATT-002, DESIGN §10.2), and the release-job role's
 * access to the global att rows only.
 */
class OperationsIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalDate TODAY = LocalDate.now(VN);
    static final LocalDate P1_START = TODAY.minusMonths(1).withDayOfMonth(1);
    static final LocalDate P1_END = P1_START.with(TemporalAdjusters.lastDayOfMonth());
    /** The first Monday of the previous month (its partitions exist, the week is in the past and in P1). */
    static final LocalDate MON = P1_START.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    static final LocalDate HIRE = LocalDate.of(2020, 1, 1);
    static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);

    @Autowired
    AssignmentProjection projection;

    @Autowired
    DocumentScanResults scans;

    @Autowired
    PayrollInbound payroll;

    @Autowired
    AttendanceResults handover;

    @Autowired
    AttendanceJobs jobs;

    @Autowired
    AttendanceJobStore jobStore;

    @BeforeAll
    static void adminUnits() {
        ADMIN.update("""
                insert into core.admin_unit (admin_unit_code, level, name_vi, valid_from) values
                  ('IT-LOC-1', 'COMMUNE', 'Phường Bến Nghé', date '2025-07-01')
                on conflict do nothing""");
    }

    record World(UUID tenant, TestUser admin, UUID le, Employee emp, String employeeNo, UUID pg) {
        UUID e() {
            return emp.employmentId();
        }
    }

    World world(String slug) throws Exception {
        UUID tenant = FIX.tenant(slug);
        List<String> perms = new ArrayList<>(FIX.allCorePermissions());
        perms.addAll(PPL.allHrPermissions());
        perms.addAll(List.of(AttendancePermissions.SCHEDULE_CONFIG_VIEW, AttendancePermissions.SCHEDULE_CONFIG_UPDATE,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE,
                AttendancePermissions.TIMESHEET_VIEW, AttendancePermissions.TIMESHEET_UPDATE,
                AttendancePermissions.TIMESHEET_IMPORT, AttendancePermissions.RESULT_IMPORT,
                AttendancePermissions.PERIOD_VIEW, AttendancePermissions.PERIOD_CLOSE,
                AttendancePermissions.INTEGRATION_VIEW, AttendancePermissions.INTEGRATION_RESYNC,
                AttendancePermissions.JOB_RUN));
        UUID role = FIX.role(tenant, "TA_ADMIN", false, perms.toArray(String[]::new));
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        UUID le = FIX.legalEntity(tenant, "LE1");
        var unit = FIX.orgUnit(tenant, "U1", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Lê", "Minh", HIRE);
        String no = ADMIN.queryForObject("select employee_no from hr.employment where id = ?", String.class,
                emp.employmentId());
        Map<String, Object> lps = Map.of(AttendanceLegalParameters.BREAK_DUE_AFTER_MIN, 360,
                AttendanceLegalParameters.BREAK_MIN_DAY, 30, AttendanceLegalParameters.BREAK_MIN_NIGHT, 45,
                AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY, 8, AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK, 48,
                AttendanceLegalParameters.NIGHT_START, "22:00", AttendanceLegalParameters.NIGHT_END, "06:00");
        lps.forEach((k, v) -> TestLegalParameters.define(tenant, k, v));
        ADMIN.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       status, worker_category_code, source_event_id, source_version)
                values (?, ?, ?, ?, ?, 'ACTIVE', 'EMPLOYEE', ?, 1)""",
                tenant, emp.employmentId(), emp.personId(), le, HIRE, Ids.newId());
        World w = new World(tenant, admin, le, emp, no, Ids.newId());
        call(w, () -> {
            projection.apply(new LineVersion(Ids.newId(), emp.assignmentKey(), emp.employmentId(), emp.personId(),
                    "PRIMARY", le, unit.id(), null, null, null, null, null, "STAFF", "FULL_TIME", BigDecimal.ONE,
                    HIRE, null), false);
            return null;
        });
        schedule(w);
        return w;
    }

    static <T> T call(World w, Callable<T> op) throws Exception {
        return TenantContext.callWith(new TenantContext(w.tenant(), w.admin().userId(),
                w.admin().identityId().toString(), "it", "vi-VN"), op);
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    /** Office shift Mon-Fri 08:00-17:00 (lunch 12-13 unpaid), weekends REST. */
    void schedule(World w) throws Exception {
        postJson(w.admin(), "/shifts", """
                {"code":"HC","nameVi":"Hành chính","startTime":"08:00","endTime":"17:00",
                 "breaks":[{"start":"12:00","end":"13:00"}],"graceInMin":5,"graceOutMin":5}""")
                .andExpect(status().isCreated());
        String day = "{\"dayIndex\":%d,\"dayKind\":\"WORK\",\"shiftCode\":\"HC\"}";
        String days = "[" + String.format(day, 0) + "," + String.format(day, 1) + "," + String.format(day, 2) + ","
                + String.format(day, 3) + "," + String.format(day, 4)
                + ",{\"dayIndex\":5,\"dayKind\":\"REST\"},{\"dayIndex\":6,\"dayKind\":\"REST\"}]";
        UUID pattern = UUID.fromString(JsonPath.read(body(postJson(w.admin(), "/schedule-patterns", """
                {"code":"T2T6","nameVi":"Thứ 2 - thứ 6","cycleLengthDays":7,"anchorDate":"2020-01-06","days":%s}"""
                .formatted(days)).andExpect(status().isCreated())), "$.pattern.id"));
        postJson(w.admin(), "/employments/" + w.e() + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"%s"}""".formatted(w.emp().assignmentKey(),
                pattern, P1_START.minusMonths(1))).andExpect(status().isCreated());
    }

    /** Pay group of the employee and the pay calendar with the previous month (an OPEN period). */
    UUID payCalendar(World w) throws Exception {
        call(w, () -> payroll.deliver(new PayGroupAssigned(Ids.newId(), Ids.newId(), w.e(), w.pg(), w.le(), HIRE, null,
                1)));
        call(w, () -> payroll.deliver(new PayCalendarPublished(Ids.newId(), w.le(), w.pg(), "FIXED_DAYS",
                new BigDecimal("26"), List.of(new PayPeriod(Ids.newId(), P1_START, P1_END, null, null, null)))));
        return ADMIN.queryForObject("select id from att.attendance_period where tenant_id = ? and period_start = ?",
                UUID.class, w.tenant(), P1_START);
    }

    // ------------------------------------------------------------------------------------------ import helpers

    String validated(World w, String type, List<String> cols, List<List<String>> rows) throws Exception {
        byte[] content = LeaveIT.xlsx(cols, rows, List.of());
        String b = body(mvc.perform(multipart("/api/v1/core/imports/jobs").file(new MockMultipartFile("file",
                        "data.xlsx", "application/octet-stream", content)).param("importType", type)
                .param("legalEntityId", w.le().toString()).with(as(w.admin()))).andExpect(status().isAccepted()));
        UUID job = UUID.fromString(JsonPath.read(b, "$.id"));
        UUID file = UUID.fromString(JsonPath.read(b, "$.fileDocumentId"));
        String key = ADMIN.queryForObject("select object_key from core.document_version where tenant_id = ? and "
                + "document_id = ? and version_no = 1", String.class, w.tenant(), file);
        scans.report(w.tenant(), key, "CLEAN");
        return await(w, job, "VALIDATED");
    }

    String await(World w, UUID job, String expected) throws Exception {
        String last = null;
        for (int i = 0; i < 300; i++) {
            String b = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(w.admin()))));
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

    void commit(World w, UUID job) throws Exception {
        mvc.perform(post("/api/v1/core/imports/jobs/" + job + "/commit").with(as(w.admin())))
                .andExpect(status().isAccepted());
        await(w, job, "COMMITTED");
    }

    ResultActions rows(World w, UUID job, String status) throws Exception {
        return mvc.perform(get("/api/v1/core/imports/jobs/" + job + "/rows").param("status", status)
                .with(as(w.admin())));
    }

    static UUID id(String job) {
        return UUID.fromString(JsonPath.read(job, "$.id"));
    }

    Map<String, Object> day(World w, LocalDate d) {
        return ADMIN.queryForMap("""
                select primary_code, worked_hours, is_manual_override, day_status from att.daily_timesheet
                 where tenant_id = ? and employment_id = ? and work_date = ?""", w.tenant(), w.e(), d);
    }

    // -------------------------------------------------------------------------------------------------- tests

    @Test
    void dailyTimesheetsAreImportedAsCorrectionsThatProcessingApplies() throws Exception {
        World w = world("att-ops-ts");
        List<String> cols = List.of("employee_no", "work_date", "code", "time_in", "time_out", "reason");
        String no = w.employeeNo();
        List<List<String>> rows = List.of(
                List.of(no, VN_DATE.format(MON), "", "08:00", "17:00", "Máy chấm công hỏng"),
                List.of(no, VN_DATE.format(MON.plusDays(1)), "CT", "", "", "Đi công tác"),
                List.of(no, VN_DATE.format(MON.plusDays(2)), "ZZZ", "", "", "Sai ký hiệu"),
                List.of(no, VN_DATE.format(TODAY.plusDays(2)), "X", "", "", "Ngày tương lai"),
                List.of(no, VN_DATE.format(MON.plusDays(3)), "", "08:00", "", "Thiếu giờ ra"));
        String job = validated(w, "ATT_TIMESHEET_DAY", cols, rows);
        assertThat(JsonPath.<Integer>read(job, "$.errorCount")).isEqualTo(3);
        rows(w, id(job), "ERROR")
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("unknown-attendance-code")))
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("future-day")))
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("times-incomplete")));
        assertThat(ADMIN.queryForObject("select count(*) from att.timesheet_correction where tenant_id = ?",
                Integer.class, w.tenant())).isZero(); // the dry run wrote nothing
        commit(w, id(job));

        List<Map<String, Object>> made = ADMIN.queryForList("""
                select work_date, source, correction_type, status, bulk_batch_id, reason_text, requested_code
                  from att.timesheet_correction where tenant_id = ? order by work_date""", w.tenant());
        assertThat(made).hasSize(2);
        assertThat(made).allSatisfy(c -> assertThat(c).containsEntry("source", "IMPORT")
                .containsEntry("correction_type", "ADMIN_EDIT").containsEntry("status", "APPLIED")
                .containsEntry("bulk_batch_id", id(job)));
        assertThat(made.getFirst()).containsEntry("reason_text", "Máy chấm công hỏng");
        // the days are recomputed with the imported values and marked as manual
        assertThat(day(w, MON)).containsEntry("is_manual_override", true);
        assertThat((BigDecimal) day(w, MON).get("worked_hours")).isEqualByComparingTo("8");
        assertThat(day(w, MON.plusDays(1))).containsEntry("primary_code", "CT")
                .containsEntry("is_manual_override", true);

        // the same values again: nothing new
        String again = validated(w, "ATT_TIMESHEET_DAY", cols, List.of(rows.getFirst()));
        rows(w, id(again), "WARNING").andExpect(jsonPath("$[*].messages[*].code", hasItem("already-imported")));
        commit(w, id(again));
        assertThat(ADMIN.queryForObject("select count(*) from att.timesheet_correction where tenant_id = ?",
                Integer.class, w.tenant())).isEqualTo(2);
    }

    @Test
    void anImportedResultIsAClosedVersionThatPayrollCanHold() throws Exception {
        World w = world("att-ops-res");
        UUID period = payCalendar(w);
        List<String> cols = List.of("employee_no", "period_start", "category", "quantity", "unit", "period_quantity");
        String no = w.employeeNo();
        String start = VN_DATE.format(P1_START);

        String bad = validated(w, "ATT_RESULT_IMPORT", cols, List.of(
                List.of(no, start, "worked days", "20", "", ""),
                List.of(no, start, "MEAL_COUNT", "20", "", ""),
                List.of(no, start, "RETRO.WORKED_DAYS", "1", "", ""),
                List.of(no, VN_DATE.format(P1_START.plusDays(3)), "WORKED_DAYS", "1", "", "")));
        assertThat(JsonPath.<Integer>read(bad, "$.errorCount")).isEqualTo(4);
        rows(w, id(bad), "ERROR")
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("invalid-category")))
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("unit-required")))
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("retro-not-importable")))
                .andExpect(jsonPath("$[*].messages[*].code", hasItem("period-not-found")));

        String job = validated(w, "ATT_RESULT_IMPORT", cols, List.of(
                List.of(no, start, "WORKED_DAYS", "20", "", ""),
                List.of(no, start, "STD_DAYS", "26", "", "26"),
                List.of(no, start, "OT_HOURS.OT-WD", "3.5", "", ""),
                List.of(no, start, "PAID_LEAVE_DAYS.ANNUAL", "2", "", "")));
        assertThat(JsonPath.<Integer>read(job, "$.errorCount")).isZero();
        assertThat(ADMIN.queryForObject("select count(*) from att.attendance_result_import_line where tenant_id = ?",
                Integer.class, w.tenant())).isZero(); // the dry run staged nothing
        commit(w, id(job));
        String status = null;
        for (int i = 0; i < 100 && !"CLOSED".equals(status); i++) {
            status = ADMIN.queryForObject("select status from att.attendance_period where id = ?", String.class,
                    period);
            Thread.sleep(50);
        }
        assertThat(status).isEqualTo("CLOSED");

        Map<String, Object> result = ADMIN.queryForMap("""
                select id, result_version, source, import_job_id, close_cause, status, line_count, scope
                  from att.attendance_result where period_id = ?""", period);
        assertThat(result).containsEntry("result_version", 1).containsEntry("source", "IMPORT")
                .containsEntry("import_job_id", id(job)).containsEntry("close_cause", "IMPORT")
                .containsEntry("status", "PUBLISHED").containsEntry("line_count", 4).containsEntry("scope", "PERIOD");
        List<Map<String, Object>> lines = ADMIN.queryForList("""
                select category_code, unit, quantity, period_quantity, segment_from, segment_to, pay_group_id
                  from att.attendance_result_line where result_id = ? order by category_code""", result.get("id"));
        assertThat(lines).extracting(l -> l.get("category_code") + ":" + l.get("unit")).containsExactly(
                "OT_HOURS.OT-WD:HOUR", "PAID_LEAVE_DAYS.ANNUAL:DAY", "STD_DAYS:DAY", "WORKED_DAYS:DAY");
        assertThat((BigDecimal) lines.getFirst().get("quantity")).isEqualByComparingTo("3.5");
        assertThat((BigDecimal) lines.get(2).get("period_quantity")).isEqualByComparingTo("26");
        assertThat(lines).allSatisfy(l -> assertThat(l).containsEntry("pay_group_id", w.pg())
                .containsEntry("segment_from", java.sql.Date.valueOf(P1_START))
                .containsEntry("segment_to", java.sql.Date.valueOf(P1_END)));
        assertThat(ADMIN.queryForObject("""
                select count(*) from att.outbox_event where tenant_id = ? and event_type = 'att.AttendancePeriodClosed'
                   and payload ->> 'cause' = 'IMPORT'""", Integer.class, w.tenant())).isOne();

        mvc.perform(get(BASE + "/result-imports/" + id(job)).with(as(w.admin()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.publishedLines").value(4))
                .andExpect(jsonPath("$.periods[0].version").value(1));
        // publishing again is a no-op
        postJson(w.admin(), "/result-imports/" + id(job) + "/publish", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.periods[0].version").value(1));

        // the close-hold protocol applies unchanged
        var hold = call(w, () -> handover.acquireHold(period, 1, Ids.newId(), null));
        assertThat(hold.granted()).isTrue();

        // a CLOSED period is not imported over: reopen it first
        String again = validated(w, "ATT_RESULT_IMPORT", cols, List.of(List.of(no, start, "WORKED_DAYS", "21", "", "")));
        rows(w, id(again), "ERROR").andExpect(jsonPath("$[0].messages[0].code").value("period-not-open"));
    }

    @Test
    void locationsAreProjectedFromCoreWithTheirVersions() throws Exception {
        World w = world("att-ops-loc");
        String attrs = """
                {"name":"Chi nhánh HN","locationType":"BRANCH","adminUnitCode":"IT-LOC-1","addressLine":"1 Tràng Tiền",
                 "timeZone":"%s"}""";
        String created = body(mvc.perform(post("/api/v1/core/locations").with(as(w.admin()))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"HN","legalEntityId":"%s","validFrom":"2026-01-01","attributes":%s}"""
                        .formatted(w.le(), attrs.formatted("Asia/Ho_Chi_Minh"))))
                .andExpect(status().isCreated()));
        UUID location = UUID.fromString(JsonPath.read(created, "$.id"));
        List<Map<String, Object>> rows = projected(w, location);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("time_zone", "Asia/Ho_Chi_Minh").containsEntry("code", "HN")
                .containsEntry("legal_entity_id", w.le()).containsEntry("status", "ACTIVE");

        int version = ADMIN.queryForObject("select version from core.location where id = ?", Integer.class, location);
        mvc.perform(post("/api/v1/core/locations/" + location + "/versions").with(as(w.admin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\"2026-09-01\",\"attributes\":"
                                + attrs.formatted("Asia/Bangkok") + ",\"version\":" + version + "}"))
                .andExpect(status().isOk());
        rows = projected(w, location);
        assertThat(rows).extracting(r -> r.get("time_zone") + "@" + r.get("valid_from") + ".." + r.get("valid_to"))
                .containsExactly("Asia/Ho_Chi_Minh@2026-01-01..2026-09-01", "Asia/Bangkok@2026-09-01..null");

        // the re-sync rebuilds the same projection (nothing new: a duplicate in the inbox)
        postJson(w.admin(), "/integration/locations/resync", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.locations").value(1)).andExpect(jsonPath("$.versions").value(2));
        assertThat(projected(w, location)).hasSize(2);
    }

    List<Map<String, Object>> projected(World w, UUID location) {
        return ADMIN.queryForList("""
                select code, legal_entity_id, time_zone, status, valid_from::text as valid_from,
                       valid_to::text as valid_to
                  from att.ext_core_location where tenant_id = ? and location_id = ? order by valid_from""",
                w.tenant(), location);
    }

    @Test
    void theNightlyJobMovesEndedPeriodsRecomputesAndKeepsPartitionsAhead() throws Exception {
        World w = world("att-ops-job");
        UUID period = payCalendar(w);
        // punches arrive without any trigger (e.g. written by a past release): only the nightly run picks them up
        punch(w, MON, "07:55", "IN");
        punch(w, MON, "17:05", "OUT");

        assertThat(jobStore.tenants()).contains(w.tenant());
        NightlySummary s = TenantContext.callWith(new TenantContext(w.tenant(), null, "it-job", null, null),
                () -> jobs.runNightly(TODAY));
        assertThat(s.steps()).extracting(AttendanceJobs.StepResult::step)
                .contains("PERIOD_REVIEW", "RECALCULATION", "LEAVE_CARRY_EXPIRY", "HOLD_RECONCILIATION");
        assertThat(s.steps()).filteredOn(x -> List.of("PERIOD_REVIEW", "RECALCULATION").contains(x.step()))
                .allSatisfy(x -> assertThat(x.failures()).isEmpty());
        assertThat(ADMIN.queryForObject("select status from att.attendance_period where id = ?", String.class, period))
                .isEqualTo("IN_REVIEW");
        assertThat((BigDecimal) day(w, MON).get("worked_hours")).isEqualByComparingTo("8");

        // partitions: 3 months ahead of the current one, idempotent
        jobs.maintainPartitions();
        String ahead = TODAY.plusMonths(AttendanceJobs.PARTITION_MONTHS_AHEAD).format(DateTimeFormatter.ofPattern("yyyyMM"));
        for (String table : List.of("raw_punch", "daily_timesheet", "attendance_result_line")) {
            assertThat(ADMIN.queryForObject("select to_regclass(?) is not null", Boolean.class,
                    "att." + table + "_p" + ahead)).as(table).isTrue();
        }
        assertThat(jobs.maintainPartitions()).isZero();

        // the manual run needs att.job.run tenant-wide
        postJson(w.admin(), "/jobs/nightly/run", "{\"today\":\"" + TODAY + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[*].step", hasItem("RECALCULATION")));
    }

    void punch(World w, LocalDate d, String hhmm, String dir) {
        LocalDateTime local = LocalDateTime.of(d, LocalTime.parse(hhmm));
        ADMIN.update("""
                insert into att.raw_punch (tenant_id, work_date_hint, punch_at, local_ts, tz_name, source,
                       terminal_code, enrol_id, direction, dedup_key, import_job_id, employment_id, legal_entity_id,
                       validation_status)
                values (?, ?, ?, ?, 'Asia/Ho_Chi_Minh', 'IMPORT', 'T01', '101', ?, ?, ?, ?, ?, 'VALID')""",
                w.tenant(), d, java.sql.Timestamp.from(local.atZone(VN).toInstant()), local, dir,
                UUID.randomUUID().toString(), Ids.newId(), w.e(), w.le());
    }

    @Test
    void theReleaseJobRoleWritesGlobalRowsOnly() throws Exception {
        UUID tenant = FIX.tenant("att-ops-platform");
        ADMIN.update("""
                insert into att.attendance_code (tenant_id, code, label_vi, label_en, code_kind, payroll_category,
                       counts_as_worked, paid_by, counts_si_nonwork, display_order)
                values (?, 'TT', 'Mã tenant', 'Tenant code', 'WORK', 'WORKED_DAYS', true, 'EMPLOYER', false, 1)""",
                tenant);
        var platform = PostgresTestDatabase.platform();
        // the default codes were written by TestReleaseJob as yhrm_platform at start-up
        assertThat(platform.queryForObject("select count(*) from att.attendance_code where code = 'X'", Integer.class))
                .isOne();
        // no tenant row is visible or writable
        assertThat(platform.queryForObject("select count(*) from att.attendance_code where tenant_id = ?",
                Integer.class, tenant)).isZero();
        assertThatThrownBy(() -> platform.update("""
                insert into att.attendance_code (tenant_id, code, label_vi, label_en, code_kind, payroll_category,
                       counts_as_worked, paid_by, counts_si_nonwork, display_order)
                values (?, 'T2', 'x', 'x', 'WORK', 'WORKED_DAYS', true, 'EMPLOYER', false, 1)""", tenant))
                .rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> platform.queryForObject("select count(*) from att.daily_timesheet", Integer.class))
                .rootCause().hasMessageContaining("permission denied");
        // a global holiday template is written, with its row audit
        UUID calendar = Ids.newId();
        platform.update("""
                insert into att.holiday_calendar (tenant_id, id, code, name_vi, name_en, source)
                values (sys.global_tenant_id(), ?, ?, 'Lịch lễ', 'Holidays', 'PLATFORM_TEMPLATE')""",
                calendar, "VN-OPS-" + calendar.toString().substring(0, 8));
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where object_type = 'holiday_calendar' and object_id = ?""",
                Integer.class, calendar)).isOne();
    }
}
