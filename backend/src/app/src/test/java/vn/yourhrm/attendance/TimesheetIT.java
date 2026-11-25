package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.application.schedule.AssignmentProjection;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.attendance.internal.domain.timesheet.DayCalculator;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Daily timesheet processing (BRD 03 §4.4: BR-ATT-030..039; US-ATT-022..030, 032; EP-ATT-05 corrections): the engine
 * on stored punches, idempotent recalculation, the trigger of a schedule change, the grid, frozen periods, admin edits
 * and correction requests approved through Core's workflow.
 */
class TimesheetIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    /** A Monday of the previous month (its timesheet partition exists, and the whole week is in the past). */
    static final LocalDate MON = LocalDate.now(VN).minusMonths(1).withDayOfMonth(1)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    @Autowired
    AssignmentProjection projection;

    record World(UUID tenant, TestUser admin, TestUser clerk, UUID le, UUID emp, UUID key) {
    }

    World world(String slug) throws Exception {
        UUID tenant = FIX.tenant(slug);
        List<String> perms = new ArrayList<>(FIX.allCorePermissions());
        perms.addAll(List.of(AttendancePermissions.SCHEDULE_CONFIG_VIEW, AttendancePermissions.SCHEDULE_CONFIG_UPDATE,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE,
                AttendancePermissions.TIMESHEET_VIEW, AttendancePermissions.TIMESHEET_UPDATE,
                AttendancePermissions.TIMESHEET_CORRECTION_REQUEST, AttendancePermissions.ATTENDANCE_CODE_VIEW,
                AttendancePermissions.ATTENDANCE_CODE_UPDATE, AttendancePermissions.PUNCH_VIEW));
        UUID role = FIX.role(tenant, "TA_ADMIN", false, perms.toArray(String[]::new));
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        UUID clerkRole = FIX.role(tenant, "TA_CLERK", false, perms.toArray(String[]::new));
        TestUser clerk = FIX.user(tenant, "clerk-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(clerk, clerkRole, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
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
        World w = new World(tenant, admin, clerk, le, emp, key);
        ADMIN.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       status, worker_category_code, source_event_id, source_version)
                values (?, ?, ?, ?, date '2020-01-01', 'ACTIVE', 'EMPLOYEE', ?, 1)""",
                tenant, emp, Ids.newId(), le, Ids.newId());
        TenantContext ctx = new TenantContext(tenant, admin.userId(), admin.identityId().toString(), "it", "vi");
        TenantContext.runWith(ctx, () -> projection.apply(new LineVersion(Ids.newId(), key, emp, Ids.newId(),
                "PRIMARY", le, unit.id(), null, null, null, null, null, "STAFF", "FULL_TIME", BigDecimal.ONE,
                LocalDate.of(2020, 1, 1), null), false));
        return w;
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    /** Office shift Mon-Fri 08:00-17:00 (lunch 12-13 unpaid, grace 5), weekends REST, assigned from MON. */
    void schedule(World w) throws Exception {
        UUID shift = UUID.fromString(JsonPath.read(body(postJson(w.admin(), "/shifts", """
                {"code":"HC","nameVi":"Hành chính","startTime":"08:00","endTime":"17:00",
                 "breaks":[{"start":"12:00","end":"13:00"}],"graceInMin":5,"graceOutMin":5}""")
                .andExpect(status().isCreated())), "$.shift.id"));
        String day = "{\"dayIndex\":%d,\"dayKind\":\"WORK\",\"shiftCode\":\"HC\"}";
        String days = "[" + String.format(day, 0) + "," + String.format(day, 1) + "," + String.format(day, 2) + ","
                + String.format(day, 3) + "," + String.format(day, 4)
                + ",{\"dayIndex\":5,\"dayKind\":\"REST\"},{\"dayIndex\":6,\"dayKind\":\"REST\"}]";
        UUID pattern = UUID.fromString(JsonPath.read(body(postJson(w.admin(), "/schedule-patterns", """
                {"code":"T2T6","nameVi":"Thứ 2 - thứ 6","cycleLengthDays":7,"anchorDate":"%s","days":%s}"""
                .formatted(MON, days)).andExpect(status().isCreated())), "$.pattern.id"));
        assertThat(shift).isNotNull();
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

    Map<String, Object> day(World w, LocalDate d) {
        return ADMIN.queryForMap("""
                select day_type, day_status, primary_code, codes::text as codes, worked_hours, late_min,
                       ot_candidate_min, day_value, exceptions::text as exceptions, has_blocking_exception,
                       is_manual_override, calc_version, rule_version, calc_inputs_hash
                  from att.daily_timesheet where tenant_id = ? and employment_id = ? and work_date = ?""",
                w.tenant(), w.emp(), d);
    }

    // -------------------------------------------------------------------------------------------------- tests

    @Test
    void punchesBecomeDaysIdempotentlyAndTheGridShowsTheExceptions() throws Exception {
        World w = world("att-ts");
        schedule(w);
        // the schedule change already processed the week: workdays without punches are absences
        assertThat(day(w, MON)).containsEntry("day_status", "ABSENT").containsEntry("primary_code", "V");
        assertThat(day(w, MON.plusDays(5))).containsEntry("day_type", "REST_DAY").containsEntry("day_status", "REST");

        punch(w, MON, "07:55", "IN");
        punch(w, MON, "17:10", "OUT");
        punch(w, MON.plusDays(1), "08:00", "IN");
        punch(w, MON.plusDays(5), "09:00", "IN");
        punch(w, MON.plusDays(5), "11:00", "OUT");
        recompute(w, MON, MON.plusDays(6)).andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(3)).andExpect(jsonPath("$.unchanged").value(4));

        assertThat(day(w, MON)).containsEntry("day_status", "PRESENT").containsEntry("primary_code", "X")
                .containsEntry("ot_candidate_min", 15).containsEntry("calc_version", 2)
                .containsEntry("rule_version", "ATT-DAY-1").containsEntry("has_blocking_exception", false);
        assertThat((BigDecimal) day(w, MON).get("worked_hours")).isEqualByComparingTo("8.00");
        assertThat(day(w, MON.plusDays(1))).containsEntry("day_status", "INCOMPLETE")
                .containsEntry("exceptions", "{MISSING_OUT}").containsEntry("has_blocking_exception", true);
        assertThat(day(w, MON.plusDays(2))).containsEntry("exceptions", "{ABSENT_NO_CODE}");
        assertThat(day(w, MON.plusDays(5))).containsEntry("day_status", "REST").containsEntry("ot_candidate_min", 120);

        // the same inputs again: nothing is written, versions stay
        String hash = (String) day(w, MON).get("calc_inputs_hash");
        recompute(w, MON, MON.plusDays(6)).andExpect(jsonPath("$.written").value(0))
                .andExpect(jsonPath("$.unchanged").value(7));
        assertThat(day(w, MON)).containsEntry("calc_version", 2).containsEntry("calc_inputs_hash", hash);

        // the grid of the legal entity, blocking exceptions only; the drill-down of a day
        mvc.perform(get(BASE + "/timesheets").param("legalEntityId", w.le().toString())
                        .param("from", MON.toString()).param("to", MON.plusDays(6).toString())
                        .param("blockingOnly", "true").with(as(w.admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(4)));
        mvc.perform(get(BASE + "/employments/" + w.emp() + "/timesheets/" + MON).with(as(w.admin())))
                .andExpect(jsonPath("$.day.dayStatus").value("PRESENT"))
                .andExpect(jsonPath("$.punches", hasSize(3))); // the day and its neighbours (night shifts)
    }

    @Test
    void adminEditsAndApprovedRequestsAreInputsOfTheRecalculation() throws Exception {
        World w = world("att-corr");
        schedule(w);
        LocalDate wed = MON.plusDays(2);
        LocalDate tue = MON.plusDays(1);
        punch(w, tue, "08:00", "IN");
        recompute(w, MON, MON.plusDays(4)).andExpect(status().isOk());

        // admin edit needs a reason; with it the absence becomes a business trip, before / after kept
        postJson(w.admin(), "/timesheets/edits", """
                {"days":[{"employmentId":"%s","workDate":"%s"}],"code":"CT"}""".formatted(w.emp(), wed))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("reason-required"));
        postJson(w.admin(), "/timesheets/edits", """
                {"days":[{"employmentId":"%s","workDate":"%s"}],"code":"CT","reason":"Công tác Đà Nẵng"}"""
                .formatted(w.emp(), wed)).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));
        assertThat(day(w, wed)).containsEntry("day_status", "PRESENT").containsEntry("primary_code", "CT")
                .containsEntry("is_manual_override", true).containsEntry("has_blocking_exception", false);
        Map<String, Object> edit = ADMIN.queryForMap("""
                select status, source, field_changes::text as fc, applied_calc_version
                  from att.timesheet_correction where tenant_id = ? and work_date = ?""", w.tenant(), wed);
        assertThat(edit).containsEntry("status", "APPLIED").containsEntry("source", "ADMIN");
        assertThat((String) edit.get("fc")).contains("\"day_status\"").contains("\"ABSENT\"")
                .contains("\"PRESENT\"").contains("\"code\": \"CT\"");
        // the edit survives a recalculation
        recompute(w, wed, wed).andExpect(jsonPath("$.written").value(0));

        // ESS request: the missing out punch, approved by the T&A admin through the workflow
        String req = body(postJson(w.clerk(), "/employments/" + w.emp() + "/timesheet-corrections", """
                {"workDate":"%s","correctionType":"MISSING_PUNCH","requestedOut":"%s","reasonText":"Quên chấm ra"}"""
                .formatted(tue, LocalDateTime.of(tue, LocalTime.of(17, 0)).atZone(VN).toInstant()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
        UUID correction = UUID.fromString(JsonPath.read(req, "$.id"));
        assertThat(day(w, tue)).containsEntry("day_status", "INCOMPLETE");
        UUID task = ADMIN.queryForObject("""
                select t.id from core.workflow_task t
                  join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                 where i.subject_id = ? and t.status = 'PENDING'""", UUID.class, correction);
        mvc.perform(post("/api/v1/core/workflow/tasks/" + task + "/actions").with(as(w.admin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\",\"comment\":\"ok\"}"))
                .andExpect(status().isNoContent());
        assertThat(ADMIN.queryForObject("select status from att.timesheet_correction where id = ?", String.class,
                correction)).isEqualTo("APPLIED");
        assertThat(day(w, tue)).containsEntry("day_status", "PRESENT").containsEntry("primary_code", "X")
                .containsEntry("is_manual_override", false);

        // a request for a code that is leave is refused (leave uses a leave request)
        postJson(w.admin(), "/employments/" + w.emp() + "/timesheet-corrections", """
                {"workDate":"%s","correctionType":"CODE_CHANGE","requestedCode":"P","reasonText":"x"}"""
                .formatted(MON)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("code-not-requestable"));

        // CT / WFH on a rest day: the request is refused; an admin edit is kept but does not turn the day into a
        // worked day (it stays REST, flagged, nothing for Payroll's worked or trip days)
        LocalDate sat = MON.plusDays(5);
        recompute(w, sat, sat).andExpect(status().isOk());
        postJson(w.clerk(), "/employments/" + w.emp() + "/timesheet-corrections", """
                {"workDate":"%s","correctionType":"CODE_CHANGE","requestedCode":"CT","reasonText":"Công tác"}"""
                .formatted(sat)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("code-on-non-working-day"));
        postJson(w.admin(), "/timesheets/edits", """
                {"days":[{"employmentId":"%s","workDate":"%s"}],"code":"WFH","reason":"x"}"""
                .formatted(w.emp(), sat)).andExpect(status().isOk());
        Map<String, Object> rest = day(w, sat);
        assertThat(rest).containsEntry("day_status", "REST").containsEntry("has_blocking_exception", false);
        assertThat(rest.get("primary_code")).isNull();
        assertThat(String.valueOf(rest.get("exceptions"))).contains(DayCalculator.CODE_NOT_APPLIED);
    }

    @Test
    void daysOfAClosedPeriodAreNeverRecomputedOrEdited() throws Exception {
        World w = world("att-frozen");
        schedule(w);
        ADMIN.update("""
                insert into att.attendance_period (tenant_id, legal_entity_id, pay_group_id, period_start, period_end,
                       status, current_version)
                values (?, ?, ?, ?, ?, 'CLOSED', 1)""", w.tenant(), w.le(), Ids.newId(), MON, MON.plusDays(6));
        int version = (Integer) day(w, MON).get("calc_version");
        punch(w, MON, "08:00", "IN");
        punch(w, MON, "17:00", "OUT");
        recompute(w, MON, MON.plusDays(6)).andExpect(jsonPath("$.frozen").value(7))
                .andExpect(jsonPath("$.written").value(0));
        assertThat(day(w, MON)).containsEntry("day_status", "ABSENT").containsEntry("calc_version", version);
        postJson(w.admin(), "/timesheets/edits", """
                {"days":[{"employmentId":"%s","workDate":"%s"}],"code":"CT","reason":"x"}"""
                .formatted(w.emp(), MON)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("day-frozen"));
    }

    @Test
    void theCodeCatalogueHasThePlatformDefaultsAndTenantCodes() throws Exception {
        World w = world("att-codes");
        mvc.perform(get(BASE + "/attendance-codes").with(as(w.admin())))
                .andExpect(jsonPath("$[*].code", hasItem("X"))).andExpect(jsonPath("$[*].code", hasItem("Ô")))
                .andExpect(jsonPath("$[?(@.code == 'V')].global").value(hasItem(true)));
        postJson(w.admin(), "/attendance-codes", """
                {"code":"CT","labelVi":"Đi công tác","kind":"TRIP","payrollCategory":"WORKED_DAYS",
                 "payrollSubCode":"TRIP","countsAsWorked":true}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.global").value(false));
        mvc.perform(get(BASE + "/attendance-codes").with(as(w.admin())))
                .andExpect(jsonPath("$[?(@.code == 'CT')].labelVi").value(hasItem("Đi công tác")))
                .andExpect(jsonPath("$[?(@.code == 'CT')]", hasSize(1)));
        postJson(w.admin(), "/attendance-codes", """
                {"code":"ZZ","labelVi":"x","kind":"NOPE","payrollCategory":"WORKED_DAYS"}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-kind"));
    }
}
