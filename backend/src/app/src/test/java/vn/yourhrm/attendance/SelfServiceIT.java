package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.application.schedule.AssignmentProjection;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * BRD 03 §4.9 (Phase 1): employee self-service of T&amp;A (BR-ATT-080), the reminders and notifications through Core
 * (BR-ATT-082) and the year-to-date overtime opening balance {@code OB_OT_YTD} (BR-ATT-086).
 */
class SelfServiceIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalDate TODAY = LocalDate.now(VN);
    /** A Monday of the previous month (its timesheet partition exists, and the whole week is in the past). */
    static final LocalDate MON = TODAY.minusMonths(1).withDayOfMonth(1)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    static final LocalDate HIRE = LocalDate.of(2020, 1, 1);

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);

    @Autowired
    AssignmentProjection projection;

    @Autowired
    DocumentScanResults scans;

    record World(UUID tenant, TestUser admin, TestUser ess, UUID le, Employee emp, String employeeNo) {
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
                AttendancePermissions.LEAVE_TYPE_VIEW, AttendancePermissions.LEAVE_TYPE_UPDATE,
                AttendancePermissions.OVERTIME_VIEW, AttendancePermissions.OVERTIME_IMPORT,
                AttendancePermissions.PERIOD_CLOSE, AttendancePermissions.REMINDER_RUN,
                AttendancePermissions.ESS_VIEW));
        UUID role = FIX.role(tenant, "TA_ADMIN", false, perms.toArray(String[]::new));
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        UUID le = FIX.legalEntity(tenant, "LE1");
        var unit = FIX.orgUnit(tenant, "U1", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Trần", "Hoa", HIRE);
        String no = ADMIN.queryForObject("select employee_no from hr.employment where id = ?", String.class,
                emp.employmentId());
        TestUser ess = PPL.userWith(tenant, "SELF", List.of(), AttendancePermissions.ESS_VIEW);
        PPL.link(ess, emp);
        Map<String, Object> lps = Map.of(AttendanceLegalParameters.BREAK_DUE_AFTER_MIN, 360,
                AttendanceLegalParameters.BREAK_MIN_DAY, 30, AttendanceLegalParameters.BREAK_MIN_NIGHT, 45,
                AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY, 8, AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK, 48,
                AttendanceLegalParameters.NIGHT_START, "22:00", AttendanceLegalParameters.NIGHT_END, "06:00",
                AttendanceLegalParameters.LEAVE_ANNUAL_BASE_DAYS, 12,
                AttendanceLegalParameters.LEAVE_ANNUAL_PROTECTED_DAYS, 14,
                AttendanceLegalParameters.LEAVE_SENIORITY_STEP_YEARS, 5);
        lps.forEach((k, v) -> TestLegalParameters.define(tenant, k, v));
        TestLegalParameters.define(tenant, AttendanceLegalParameters.LEAVE_SENIORITY_STEP_DAYS, 1);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.LEAVE_MATERNITY_MIN_MONTHS, 4);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.LEAVE_PERSONAL_DAYS,
                Map.of("MARRIAGE_SELF", 3, "MARRIAGE_CHILD", 1, "BEREAVEMENT", 3, "UNPAID_FAMILY", 1));
        TestLegalParameters.define(tenant, AttendanceLegalParameters.LEAVE_SERVICE_COUNTED_CAPS,
                Map.of("SICK", 60, "UNPAID_AGREED", 30));
        ADMIN.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       status, worker_category_code, source_event_id, source_version)
                values (?, ?, ?, ?, ?, 'ACTIVE', 'EMPLOYEE', ?, 1)""",
                tenant, emp.employmentId(), emp.personId(), le, HIRE, Ids.newId());
        TenantContext ctx = new TenantContext(tenant, admin.userId(), admin.identityId().toString(), "it", "vi");
        TenantContext.runWith(ctx, () -> projection.apply(new LineVersion(Ids.newId(), emp.assignmentKey(),
                emp.employmentId(), emp.personId(), "PRIMARY", le, unit.id(), null, null, null, null, null, "STAFF",
                "FULL_TIME", BigDecimal.ONE, HIRE, null), false));
        World w = new World(tenant, admin, ess, le, emp, no);
        postJson(admin, "/leave-types/statutory", "{}").andExpect(status().isOk());
        return w;
    }

    /** Office shift Mon-Fri 08:00-17:00, weekends REST, assigned by hand from {@code MON}. */
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
        postJson(w.admin(), "/employments/" + w.e() + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"%s"}""".formatted(w.emp().assignmentKey(),
                pattern, MON)).andExpect(status().isCreated());
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

    void recompute(World w, LocalDate from, LocalDate to) throws Exception {
        postJson(w.admin(), "/timesheets/recompute", """
                {"employmentIds":["%s"],"from":"%s","to":"%s"}""".formatted(w.e(), from, to))
                .andExpect(status().isOk());
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions me(TestUser user, String path) throws Exception {
        return mvc.perform(get(BASE + "/me" + path).with(as(user)));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    static int notifications(World w, TestUser to, String eventCode) {
        return ADMIN.queryForObject("""
                select count(*) from core.notification where tenant_id = ? and recipient_user_id = ?
                   and event_code = ?""", Integer.class, w.tenant(), to.userId(), eventCode);
    }

    // ============================================================================================ tests

    @Test
    void essShowsMyOwnAttendanceInMyLanguage() throws Exception {
        World w = world("att-ess");
        schedule(w);
        // the manual schedule assignment is announced to the employee (BR-ATT-082)
        assertThat(notifications(w, w.ess(), "att.ScheduleChanged")).isEqualTo(1);
        punch(w, MON, "07:55", "IN");
        punch(w, MON, "17:10", "OUT");
        punch(w, MON.plusDays(1), "08:00", "IN");
        recompute(w, MON, MON.plusDays(6));

        me(w.ess(), "/employments").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].employmentId").value(w.e().toString()))
                .andExpect(jsonPath("$[0].activeToday").value(true));
        String range = "?from=" + MON + "&to=" + MON.plusDays(6);
        me(w.ess(), "/schedule" + range).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].shiftCode").value("HC"));
        me(w.ess(), "/punches" + range).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
        me(w.ess(), "/timesheets" + range).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].dayStatusLabel").value("Có mặt"))
                .andExpect(jsonPath("$[1].exceptions[0].code").value("MISSING_OUT"))
                .andExpect(jsonPath("$[1].exceptions[0].label").value("Thiếu giờ ra"))
                .andExpect(jsonPath("$[1].exceptions[0].blocking").value(true));
        me(w.ess(), "/timesheets" + range + "&lang=en")
                .andExpect(jsonPath("$[1].exceptions[0].label").value("Missing check-out"))
                .andExpect(jsonPath("$[5].dayTypeLabel").value("Weekly rest day"));
        // Tuesday misses the check-out, Wednesday to Friday are absences without a code
        me(w.ess(), "/exceptions" + range).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(4)));
        me(w.ess(), "/timesheets/" + MON.plusDays(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.day.dayStatus").value("INCOMPLETE"))
                .andExpect(jsonPath("$.punches", hasSize(3)));
        me(w.ess(), "/leave-balances").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].leaveTypeLabel", hasItem("Nghỉ phép năm")));
        me(w.ess(), "/leave-balances?lang=en").andExpect(jsonPath("$[*].leaveTypeLabel", hasItem("Annual leave")));
        me(w.ess(), "/leave-ledger").andExpect(status().isOk());
        me(w.ess(), "/requests" + range).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

        // only my own employments: another one is unknown, an unlinked user has no "me", no permission is 403
        me(w.ess(), "/timesheets" + range + "&employmentId=" + Ids.newId()).andExpect(status().isNotFound());
        TestUser unlinked = PPL.userWith(w.tenant(), "SELF", List.of(), AttendancePermissions.ESS_VIEW);
        me(unlinked, "/employments").andExpect(status().isNotFound());
        TestUser noEss = PPL.userWith(w.tenant(), "TENANT", List.of(), AttendancePermissions.TIMESHEET_VIEW);
        me(noEss, "/employments").andExpect(status().isForbidden());
        // a TENANT-wide holder of att.ess.view sees nobody (it is not linked to an employee)
        me(w.admin(), "/employments").andExpect(status().isNotFound());
    }

    @Test
    void dailyRemindersReachTheEmployeeAndTheAdministrators() throws Exception {
        World w = world("att-remind");
        schedule(w);
        punch(w, MON.plusDays(1), "08:00", "IN");
        recompute(w, MON, MON.plusDays(6));

        // missing punch: the morning after, once (Core de-duplicates a re-run)
        String run = "{\"today\":\"" + MON.plusDays(2) + "\"}";
        postJson(w.admin(), "/reminders/run", run).andExpect(status().isOk())
                .andExpect(jsonPath("$.missingPunch").value(1)).andExpect(jsonPath("$.failures").value(0));
        postJson(w.admin(), "/reminders/run", run).andExpect(status().isOk());
        assertThat(notifications(w, w.ess(), "att.MissingPunchReminder")).isEqualTo(1);
        assertThat(ADMIN.queryForObject("""
                select deep_link from core.notification where tenant_id = ? and recipient_user_id = ?
                   and event_code = 'att.MissingPunchReminder'""", String.class, w.tenant(), w.ess().userId()))
                .isEqualTo("/attendance/me/timesheets/" + MON.plusDays(1));

        // carried leave expiring in 30 days (the default notice) and a period due to close in 2 days
        UUID annual = ADMIN.queryForObject("select id from att.leave_type where tenant_id = ? and code = 'ANNUAL'",
                UUID.class, w.tenant());
        ADMIN.update("""
                insert into att.leave_balance_ledger (tenant_id, employment_id, legal_entity_id, leave_type_id,
                       leave_type_code, leave_year, txn_type, quantity, txn_date, expires_on, ref_type, source)
                values (?, ?, ?, ?, 'ANNUAL', ?, 'CARRY', 3, ?, ?, 'CARRY_IN', 'SYSTEM')""",
                w.tenant(), w.e(), w.le(), annual, TODAY.getYear(), TODAY.withDayOfYear(1), TODAY.plusDays(30));
        ADMIN.update("""
                insert into att.attendance_period (tenant_id, legal_entity_id, pay_group_id, period_start,
                       period_end, close_deadline, status)
                values (?, ?, ?, ?, ?, ?, 'OPEN')""",
                w.tenant(), w.le(), Ids.newId(), TODAY.plusYears(1), TODAY.plusYears(1).plusDays(27),
                TODAY.plusDays(2));
        postJson(w.admin(), "/reminders/run", "{\"today\":\"" + TODAY + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.carryExpiry").value(1)).andExpect(jsonPath("$.closeDue").value(1));
        assertThat(ADMIN.queryForObject("""
                select title from core.notification where tenant_id = ? and recipient_user_id = ?
                   and event_code = 'att.LeaveCarryExpiring'""", String.class, w.tenant(), w.ess().userId()))
                .isEqualTo("3 ngày phép chuyển sang sắp hết hạn");
        assertThat(notifications(w, w.admin(), "att.PeriodCloseDue")).isEqualTo(1);
        assertThat(notifications(w, w.ess(), "att.PeriodCloseDue")).isZero();
        // a day later the carry is 29 days away: no second reminder
        postJson(w.admin(), "/reminders/run", "{\"today\":\"" + TODAY.plusDays(1) + "\"}")
                .andExpect(jsonPath("$.carryExpiry").value(0));

        // the manual run needs the permission for the whole tenant
        TestUser leOnly = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(w.le()),
                AttendancePermissions.REMINDER_RUN);
        postJson(leOnly, "/reminders/run", "{}").andExpect(status().isForbidden());
    }

    @Test
    void yearToDateOvertimeIsLoadedIntoTheCapLedger() throws Exception {
        World w = world("att-ob-ot");
        TestLegalParameters.define(w.tenant(), AttendanceLegalParameters.OT_MAX_DAY_TOTAL_HOURS, 12);
        TestLegalParameters.define(w.tenant(), AttendanceLegalParameters.OT_MAX_DAY_OFF_HOURS, 12);
        TestLegalParameters.define(w.tenant(), AttendanceLegalParameters.OT_MAX_MONTH_HOURS, 40);
        TestLegalParameters.define(w.tenant(), AttendanceLegalParameters.OT_MAX_YEAR_HOURS, 200);
        TestLegalParameters.define(w.tenant(), AttendanceLegalParameters.OT_MAX_YEAR_EXTENDED_HOURS, 300);
        TestLegalParameters.define(w.tenant(), AttendanceLegalParameters.OT_PROTECTED_RULES,
                Map.of("PREGNANCY", "BLOCK"));
        LocalDate goLive = TODAY.withDayOfMonth(10);
        List<String> cols = List.of("employee_no", "year", "ytd_ot_hours", "month_ot_hours");
        String y = String.valueOf(goLive.getYear());

        // an earlier year never counts for a cap
        String bad = dryRun(w, LeaveIT.xlsx(cols, List.of(List.of(w.employeeNo(), String.valueOf(goLive.getYear() - 1),
                "10", "")), List.of(List.of("OT_YTD", "HOURS", "10"))), goLive);
        assertThat(JsonPath.<Integer>read(bad, "$.errorCount")).isEqualTo(1);

        String ok = dryRun(w, LeaveIT.xlsx(cols, List.of(List.of(w.employeeNo(), y, "50.5", "4")),
                List.of(List.of("OT_YTD", "HOURS", "50.5"))), goLive);
        assertThat(JsonPath.<Integer>read(ok, "$.errorCount")).isZero();
        UUID jobId = UUID.fromString(JsonPath.read(ok, "$.id"));
        mvc.perform(post("/api/v1/core/imports/jobs/" + jobId + "/sign-off").with(as(w.admin())))
                .andExpect(jsonPath("$.status").value("SIGNED_OFF"));
        mvc.perform(post("/api/v1/core/imports/jobs/" + jobId + "/commit").with(as(w.admin())))
                .andExpect(status().isAccepted());
        await(w, jobId, "COMMITTED");
        assertThat(ADMIN.queryForObject("""
                select opening_min from att.ot_cap_ledger where tenant_id = ? and employment_id = ?
                   and period_kind = 'YEAR'""", Integer.class, w.tenant(), w.e())).isEqualTo(3030);
        assertThat(ADMIN.queryForObject("""
                select opening_min from att.ot_cap_ledger where tenant_id = ? and employment_id = ?
                   and period_kind = 'MONTH' and period_start = ?""", Integer.class, w.tenant(), w.e(),
                goLive.withDayOfMonth(1))).isEqualTo(240);
        mvc.perform(get(BASE + "/employments/" + w.e() + "/overtime-caps?date=" + goLive).with(as(w.admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[1].kind").value("YEAR"))
                .andExpect(jsonPath("$[1].openingMin").value(3030));
    }

    // ------------------------------------------------------------------------------------------ import helpers

    String dryRun(World w, byte[] content, LocalDate goLive) throws Exception {
        String body = body(mvc.perform(multipart("/api/v1/core/imports/jobs").file(new MockMultipartFile("file",
                        "ob.xlsx", "application/octet-stream", content)).param("importType", "OB_OT_YTD")
                .param("mode", "OPENING_BALANCE").param("asOfDate", goLive.toString())
                .param("legalEntityId", w.le().toString()).with(as(w.admin()))).andExpect(status().isAccepted()));
        UUID job = UUID.fromString(JsonPath.read(body, "$.id"));
        UUID file = UUID.fromString(JsonPath.read(body, "$.fileDocumentId"));
        String key = ADMIN.queryForObject("select object_key from core.document_version where tenant_id = ? and "
                + "document_id = ? and version_no = 1", String.class, w.tenant(), file);
        scans.report(w.tenant(), key, "CLEAN");
        String last = null;
        for (int i = 0; i < 300; i++) {
            String b = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(w.admin()))));
            last = JsonPath.read(b, "$.status");
            if ("RECONCILED".equals(last) || "VALIDATED".equals(last)) {
                return b;
            }
            if ("FAILED".equals(last)) {
                throw new AssertionError("Job failed: " + JsonPath.read(b, "$.failureReason"));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + job + " stayed " + last);
    }

    void await(World w, UUID job, String expected) throws Exception {
        String last = null;
        for (int i = 0; i < 300; i++) {
            String b = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(w.admin()))));
            last = JsonPath.read(b, "$.status");
            if (expected.equals(last)) {
                return;
            }
            if ("FAILED".equals(last)) {
                throw new AssertionError("Job failed: " + JsonPath.read(b, "$.failureReason"));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + job + " stayed " + last + ", expected " + expected);
    }
}
