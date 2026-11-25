package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.application.schedule.AssignmentProjection;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * The T&amp;A reports of BRD 03 §8 (R-01..R-09) end to end: computed days in the timesheet grid (JSON and the VN
 * Excel layout with signature cells), daily exceptions, the punch log, overtime cap usage, leave balances as of a date,
 * the period close status, and the data scope (a user scoped to another legal entity sees no rows).
 */
class ReportsIT extends IntegrationTest {

    static final String BASE = TimesheetIT.BASE;
    static final ZoneId VN = TimesheetIT.VN;
    static final LocalDate MON = TimesheetIT.MON;
    static final String REPORTS = BASE + "/reports";

    @Autowired
    AssignmentProjection projection;

    record World(UUID tenant, TestUser admin, UUID le, UUID emp, UUID key) {
    }

    /** As {@code TimesheetIT.world}: a T&amp;A admin (no report export), one employment on an org unit of LE1. */
    World world(String slug) throws Exception {
        UUID tenant = FIX.tenant(slug);
        List<String> perms = new ArrayList<>(FIX.allCorePermissions());
        perms.addAll(List.of(AttendancePermissions.SCHEDULE_CONFIG_VIEW, AttendancePermissions.SCHEDULE_CONFIG_UPDATE,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE,
                AttendancePermissions.TIMESHEET_VIEW, AttendancePermissions.TIMESHEET_UPDATE,
                AttendancePermissions.PUNCH_VIEW));
        UUID role = FIX.role(tenant, "TA_ADMIN", false, perms.toArray(String[]::new));
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.BREAK_DUE_AFTER_MIN, 360);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.BREAK_MIN_DAY, 30);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.BREAK_MIN_NIGHT, 45);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY, 8);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK, 48);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.NIGHT_START, "22:00");
        TestLegalParameters.define(tenant, AttendanceLegalParameters.NIGHT_END, "06:00");
        UUID le = FIX.legalEntity(tenant, "LE1");
        var unit = FIX.orgUnit(tenant, "U1", null, le, null, TestFixtures.EPOCH, null);
        UUID emp = Ids.newId();
        UUID key = Ids.newId();
        ADMIN.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       status, worker_category_code, source_event_id, source_version)
                values (?, ?, ?, ?, date '2020-01-01', 'ACTIVE', 'EMPLOYEE', ?, 1)""",
                tenant, emp, Ids.newId(), le, Ids.newId());
        TenantContext ctx = new TenantContext(tenant, admin.userId(), admin.identityId().toString(), "it", "vi");
        TenantContext.runWith(ctx, () -> projection.apply(new LineVersion(Ids.newId(), key, emp, Ids.newId(),
                "PRIMARY", le, unit.id(), null, null, null, null, null, "STAFF", "FULL_TIME", BigDecimal.ONE,
                LocalDate.of(2020, 1, 1), null), false));
        return new World(tenant, admin, le, emp, key);
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    /** Office shift Mon-Fri 08:00-17:00 (lunch 12-13, grace 5), weekends REST, assigned from MON. */
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
                {"code":"T2T6","nameVi":"Thứ 2 - thứ 6","cycleLengthDays":7,"anchorDate":"%s","days":%s}"""
                .formatted(MON, days)).andExpect(status().isCreated())), "$.pattern.id"));
        postJson(w.admin(), "/employments/" + w.emp() + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"%s"}""".formatted(w.key(), pattern, MON))
                .andExpect(status().isCreated());
    }

    void punch(World w, LocalDate d, String hhmm, String dir) {
        LocalDateTime local = LocalDateTime.of(d, LocalTime.parse(hhmm));
        ADMIN.update("""
                insert into att.raw_punch (tenant_id, work_date_hint, punch_at, local_ts, tz_name, source,
                       terminal_code, enrol_id, direction, dedup_key, import_job_id, employment_id, legal_entity_id,
                       validation_status)
                values (?, ?, ?, ?, 'Asia/Ho_Chi_Minh', 'IMPORT', 'T01', '101', ?, ?, ?, ?, ?, 'VALID')""",
                w.tenant(), d, java.sql.Timestamp.from(local.atZone(VN).toInstant()), local, dir,
                UUID.randomUUID().toString(), Ids.newId(), w.emp(), w.le());
    }

    ResultActions recompute(World w, LocalDate from, LocalDate to) throws Exception {
        return postJson(w.admin(), "/timesheets/recompute", """
                {"employmentIds":["%s"],"from":"%s","to":"%s"}""".formatted(w.emp(), from, to));
    }

    World reportWorld(String slug) throws Exception {
        World w = world(slug);
        schedule(w);
        // Monday 08:20 - 17:00 (late), Tuesday in only (missing out), Wednesday nothing (absent)
        punch(w, MON, "08:20", "IN");
        punch(w, MON, "17:00", "OUT");
        punch(w, MON.plusDays(1), "08:00", "IN");
        recompute(w, MON, MON.plusDays(4)).andExpect(status().isOk());
        return w;
    }

    TestUser reporter(World w, String scopeType, List<UUID> scopeIds) {
        UUID role = FIX.role(w.tenant(), "REPORTS_" + scopeType, false, AttendancePermissions.TIMESHEET_VIEW,
                AttendancePermissions.PUNCH_VIEW, AttendancePermissions.OVERTIME_VIEW,
                AttendancePermissions.LEAVE_BALANCE_VIEW, AttendancePermissions.LEAVE_REQUEST_VIEW,
                AttendancePermissions.PERIOD_VIEW, AttendancePermissions.REPORT_EXPORT);
        TestUser u = FIX.user(w.tenant(), "rep-" + scopeType.toLowerCase() + "-" + w.tenant().toString()
                .substring(24) + "@test.local");
        FIX.assign(u, role, scopeType, scopeIds, true, TestFixtures.EPOCH, null);
        return u;
    }

    @Test
    void timesheetGridShowsCodesPerDayAndTotalsPerCategory() throws Exception {
        World w = reportWorld("rep-grid");
        TestUser hr = reporter(w, "TENANT", List.of());
        mvc.perform(get(REPORTS + "/timesheet-grid").with(as(hr)).param("legalEntityId", w.le().toString())
                        .param("from", MON.toString()).param("to", MON.plusDays(6).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates", hasSize(7)))
                .andExpect(jsonPath("$.page.items", hasSize(1)))
                .andExpect(jsonPath("$.page.items[0].employmentId").value(w.emp().toString()))
                .andExpect(jsonPath("$.page.items[0].cells", hasSize(7)))
                .andExpect(jsonPath("$.page.items[0].lateMin").value(20))
                .andExpect(jsonPath("$.page.hasMore").value(false));

        byte[] xlsx = mvc.perform(get(REPORTS + "/timesheet-grid/export.xlsx").with(as(hr))
                        .param("legalEntityId", w.le().toString()).param("from", MON.toString())
                        .param("to", MON.plusDays(6).toString()).param("unitLabel", "Phòng Kế toán"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("BẢNG CHẤM CÔNG");
            assertThat(s.getRow(2).getCell(0).getStringCellValue()).contains("Phòng Kế toán");
            StringBuilder all = new StringBuilder();
            s.forEach(r -> r.forEach(c -> all.append(c.toString()).append('|')));
            assertThat(all).contains("Người lập").contains("Trưởng bộ phận").contains("Giám đốc");
        }
    }

    @Test
    void dailyExceptionsCountLateMissingAndAbsentDays() throws Exception {
        World w = reportWorld("rep-exc");
        TestUser hr = reporter(w, "TENANT", List.of());
        mvc.perform(get(REPORTS + "/daily-exceptions").with(as(hr)).param("from", MON.toString())
                        .param("to", MON.plusDays(4).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary[?(@.code=='LATE')].count").value(1))
                .andExpect(jsonPath("$.summary[?(@.code=='MISSING_PUNCH')].count").value(1));
        mvc.perform(get(REPORTS + "/daily-exceptions").with(as(hr)).param("from", MON.toString())
                        .param("to", MON.plusDays(4).toString()).param("type", "LATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items", hasSize(1)))
                .andExpect(jsonPath("$.page.items[0].workDate").value(MON.toString()));
    }

    @Test
    void punchLogListsTheRawPunchesWithTheirStatus() throws Exception {
        World w = reportWorld("rep-punch");
        TestUser hr = reporter(w, "TENANT", List.of());
        mvc.perform(get(REPORTS + "/punch-log").with(as(hr)).param("employmentId", w.emp().toString())
                        .param("from", MON.toString()).param("to", MON.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].punch.validationStatus").value("VALID"))
                .andExpect(jsonPath("$.items[0].validationStatusLabel").value("Hợp lệ"));
    }

    @Test
    void overtimeSummaryAndLeaveBalancesFromTheLedgers() throws Exception {
        World w = reportWorld("rep-ledgers");
        TestUser hr = reporter(w, "TENANT", List.of());
        LocalDate month = MON.withDayOfMonth(1);
        ADMIN.update("""
                insert into att.ot_cap_ledger (tenant_id, employment_id, legal_entity_id, period_kind, period_start,
                       cap_min, approved_min)
                values (?, ?, ?, 'MONTH', ?, 2400, 1200), (?, ?, ?, 'YEAR', ?, 12000, 6000)""",
                w.tenant(), w.emp(), w.le(), month, w.tenant(), w.emp(), w.le(), month.withDayOfYear(1));
        mvc.perform(get(REPORTS + "/overtime-summary").with(as(hr)).param("legalEntityId", w.le().toString())
                        .param("month", month.toString().substring(0, 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items", hasSize(1)))
                .andExpect(jsonPath("$.page.items[0].month.usedPercent").value(50.0))
                .andExpect(jsonPath("$.page.items[0].year.usedPercent").value(50.0));
        mvc.perform(get(REPORTS + "/overtime-summary/export.xlsx").with(as(hr))
                        .param("legalEntityId", w.le().toString()).param("month", month.toString().substring(0, 7)))
                .andExpect(status().isOk());

        UUID type = Ids.newId();
        ADMIN.update("""
                insert into att.leave_type (tenant_id, id, code, name_vi, leave_category, paid_by, tracks_balance,
                       attendance_code, payroll_category)
                values (?, ?, 'ANNUAL', 'Phép năm', 'ANNUAL', 'EMPLOYER', true, 'P', 'PAID_LEAVE_DAYS')""",
                w.tenant(), type);
        int year = MON.getYear();
        ADMIN.update("""
                insert into att.leave_balance_ledger (tenant_id, employment_id, legal_entity_id, leave_type_id,
                       leave_type_code, leave_year, txn_type, quantity, txn_date, source, reason)
                values (?, ?, ?, ?, 'ANNUAL', ?, 'OPENING', 3, ?, 'OPENING_LOAD', null),
                       (?, ?, ?, ?, 'ANNUAL', ?, 'ADJUST', 2, ?, 'APP', 'it')""",
                w.tenant(), w.emp(), w.le(), type, year, LocalDate.of(year, 1, 1),
                w.tenant(), w.emp(), w.le(), type, year, LocalDate.of(year, 1, 2));
        mvc.perform(get(REPORTS + "/leave-balances").with(as(hr)).param("asOf", MON.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items", hasSize(1)))
                .andExpect(jsonPath("$.page.items[0].balance.opening").value(3))
                .andExpect(jsonPath("$.page.items[0].balance.closing").value(5));
        mvc.perform(get(REPORTS + "/leave-balances/export.xlsx").with(as(hr)).param("asOf", MON.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void reportsFollowTheDataScopeAndExportNeedsItsPermission() throws Exception {
        World w = reportWorld("rep-scope");
        UUID otherLe = FIX.legalEntity(w.tenant(), "LE2");
        TestUser outsider = reporter(w, "LEGAL_ENTITY", List.of(otherLe));
        mvc.perform(get(REPORTS + "/timesheet-grid").with(as(outsider)).param("from", MON.toString())
                        .param("to", MON.plusDays(6).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.items", hasSize(0)));
        mvc.perform(get(REPORTS + "/daily-exceptions").with(as(outsider)).param("from", MON.toString())
                        .param("to", MON.plusDays(4).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.items", hasSize(0)));
        mvc.perform(get(REPORTS + "/punch-log").with(as(outsider)).param("from", MON.toString())
                        .param("to", MON.plusDays(4).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        // the T&A admin of the world has no att.report.export
        mvc.perform(get(REPORTS + "/timesheet-grid/export.xlsx").with(as(w.admin())).param("from", MON.toString())
                        .param("to", MON.plusDays(6).toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get(REPORTS + "/period-close-status").with(as(outsider)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get(REPORTS + "/retro-adjustments").with(as(outsider)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.items", hasSize(0)));
    }
}
