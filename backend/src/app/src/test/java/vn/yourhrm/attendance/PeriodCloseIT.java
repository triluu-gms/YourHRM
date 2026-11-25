package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.AttendanceResults;
import vn.yourhrm.attendance.api.AttendanceResults.HoldOutcome;
import vn.yourhrm.attendance.api.PayrollInbound;
import vn.yourhrm.attendance.api.PayrollInbound.HeldVersion;
import vn.yourhrm.attendance.api.PayrollInbound.PayCalendarPublished;
import vn.yourhrm.attendance.api.PayrollInbound.PayPeriod;
import vn.yourhrm.attendance.api.PayrollInbound.PayrollRunStatusChanged;
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
 * Period close, payroll handover and retro (BRD 03 §4.8: BR-ATT-071..076, 079, 087, 088): the pay calendar feed, the
 * checklist with waivers, close / re-close with versioned results, the close hold (acquire, idempotency, release
 * request and automatic reopen), lock and unlock on the payroll run status, the leaver's early close, and retro deltas
 * of a change after the lock attached to the next close.
 */
class PeriodCloseIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalDate P1_START = LocalDate.now(VN).minusMonths(1).withDayOfMonth(1);
    static final LocalDate P1_END = P1_START.with(TemporalAdjusters.lastDayOfMonth());
    static final LocalDate P2_START = P1_END.plusDays(1);
    static final LocalDate P2_END = P2_START.with(TemporalAdjusters.lastDayOfMonth());
    /** The first Monday of the previous month: Monday to Friday of that week lie in the period. */
    static final LocalDate MON = P1_START.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    static final List<String> WAIVE_ALL = List.of("MISSING_PUNCH", "ABSENT_NO_CODE", "PERIOD_NOT_ENDED",
            "NOT_PROCESSED", "QUARANTINED_PUNCH");

    @Autowired
    AssignmentProjection projection;

    @Autowired
    PayrollInbound payroll;

    @Autowired
    AttendanceResults handover;

    record World(UUID tenant, TestUser admin, UUID le, UUID pg, UUID emp, UUID key) {
    }

    World world(String slug) throws Exception {
        UUID tenant = FIX.tenant(slug);
        List<String> perms = new ArrayList<>(FIX.allCorePermissions());
        perms.addAll(List.of(AttendancePermissions.SCHEDULE_CONFIG_VIEW, AttendancePermissions.SCHEDULE_CONFIG_UPDATE,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE,
                AttendancePermissions.TIMESHEET_VIEW, AttendancePermissions.TIMESHEET_UPDATE,
                AttendancePermissions.PERIOD_VIEW, AttendancePermissions.PERIOD_CLOSE,
                AttendancePermissions.PERIOD_REOPEN, AttendancePermissions.PERIOD_HOLD));
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
        World w = new World(tenant, admin, le, Ids.newId(), emp, key);
        call(w, () -> {
            projection.apply(new LineVersion(Ids.newId(), key, emp, Ids.newId(), "PRIMARY", le, unit.id(), null,
                    null, null, null, null, "STAFF", "FULL_TIME", BigDecimal.ONE, LocalDate.of(2020, 1, 1), null),
                    false);
            return null;
        });
        schedule(w);
        return w;
    }

    static <T> T call(World w, Callable<T> op) throws Exception {
        return TenantContext.callWith(new TenantContext(w.tenant(), w.admin().userId(),
                w.admin().identityId().toString(), "it", "vi-VN"), op);
    }

    ResultActions postJson(World w, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(w.admin())).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    /** Office shift Mon-Fri 08:00-17:00 (lunch 12-13), weekends REST, from P1_START. */
    void schedule(World w) throws Exception {
        postJson(w, "/shifts", """
                {"code":"HC","nameVi":"Hành chính","startTime":"08:00","endTime":"17:00",
                 "breaks":[{"start":"12:00","end":"13:00"}],"graceInMin":5,"graceOutMin":5}""")
                .andExpect(status().isCreated());
        String day = "{\"dayIndex\":%d,\"dayKind\":\"WORK\",\"shiftCode\":\"HC\"}";
        String days = "[" + String.format(day, 0) + "," + String.format(day, 1) + "," + String.format(day, 2) + ","
                + String.format(day, 3) + "," + String.format(day, 4)
                + ",{\"dayIndex\":5,\"dayKind\":\"REST\"},{\"dayIndex\":6,\"dayKind\":\"REST\"}]";
        UUID pattern = UUID.fromString(JsonPath.read(body(postJson(w, "/schedule-patterns", """
                {"code":"T2T6","nameVi":"Thứ 2 - thứ 6","cycleLengthDays":7,"anchorDate":"%s","days":%s}"""
                .formatted(MON, days)).andExpect(status().isCreated())), "$.pattern.id"));
        postJson(w, "/employments/" + w.emp() + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"%s"}""".formatted(w.key(), pattern, P1_START))
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

    void fullDay(World w, LocalDate d) {
        punch(w, d, "07:55", "IN");
        punch(w, d, "17:05", "OUT");
    }

    void recompute(World w, LocalDate from, LocalDate to) throws Exception {
        postJson(w, "/timesheets/recompute", """
                {"employmentIds":["%s"],"from":"%s","to":"%s"}""".formatted(w.emp(), from, to))
                .andExpect(status().is2xxSuccessful());
    }

    String calendar(World w, UUID eventId) throws Exception {
        return call(w, () -> payroll.deliver(new PayCalendarPublished(eventId, w.le(), w.pg(), "FIXED_DAYS",
                new BigDecimal("26"), List.of(new PayPeriod(Ids.newId(), P1_START, P1_END, null, null, null),
                        new PayPeriod(Ids.newId(), P2_START, P2_END, null, null, null)))));
    }

    UUID period(World w, LocalDate start) {
        return ADMIN.queryForObject("select id from att.attendance_period where tenant_id = ? and period_start = ?",
                UUID.class, w.tenant(), start);
    }

    Map<String, Object> periodRow(UUID id) {
        return ADMIN.queryForMap("""
                select status, current_version, locked_version, reopen_requested_at, reopen_request_reason
                  from att.attendance_period where id = ?""", id);
    }

    ResultActions close(World w, UUID period, List<String> waive) throws Exception {
        String list = waive.stream().map(s -> "\"" + s + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return postJson(w, "/attendance-periods/" + period + "/close",
                "{\"waive\":[" + list + "],\"waiveReason\":\"accepted by HR\"}");
    }

    List<Map<String, Object>> lines(UUID resultId) {
        return ADMIN.queryForList("""
                select category_code, quantity, period_quantity, is_retro, original_period_id, original_result_id,
                       segment_from, segment_to
                  from att.attendance_result_line where result_id = ? order by category_code""", resultId);
    }

    static BigDecimal sum(List<Map<String, Object>> lines, String category) {
        return lines.stream().filter(l -> category.equals(l.get("category_code")))
                .map(l -> (BigDecimal) l.get("quantity")).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    List<String> events(World w, String type) {
        return ADMIN.queryForList("""
                select payload::text from att.outbox_event where tenant_id = ? and event_type = ?
                 order by created_at, id""", String.class, w.tenant(), type);
    }

    BigDecimal storedWorkedDays(UUID period) {
        return ADMIN.queryForObject("""
                select coalesce(sum((c->>'qty')::numeric), 0) from att.daily_timesheet d,
                       jsonb_array_elements(d.codes) c
                 where d.period_id = ? and c->>'code' = 'X'""", BigDecimal.class, period);
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Test
    void closeHoldReopenOnReleaseLockAndUnlock() throws Exception {
        World w = world("att-close-hold");
        UUID calendarEvent = Ids.newId();
        assertThat(calendar(w, calendarEvent)).as(() -> ADMIN.queryForList("select last_error from att.inbox_message"
                + " where event_id = ?", String.class, calendarEvent).toString()).isEqualTo("PROCESSED");
        assertThat(calendar(w, calendarEvent)).isEqualTo("DUPLICATE");
        UUID p1 = period(w, P1_START);
        assertThat(periodRow(p1)).containsEntry("status", "OPEN");

        for (int i = 0; i < 3; i++) {
            fullDay(w, MON.plusDays(i));
        }
        punch(w, MON.plusDays(3), "07:58", "IN");          // no OUT: MISSING_OUT, blocking
        recompute(w, P1_START, P1_END);

        mvc.perform(get(BASE + "/attendance-periods/" + p1 + "/checklist").with(as(w.admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.items[?(@.code == 'MISSING_PUNCH')]").exists());
        postJson(w, "/attendance-periods/" + p1 + "/close", "{}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("close-blocked"));
        postJson(w, "/attendance-periods/" + p1 + "/close", "{\"waive\":[\"MISSING_PUNCH\",\"ABSENT_NO_CODE\"]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("waive-reason-required"));

        String v1 = body(close(w, p1, WAIVE_ALL).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.closeCause").value("CLOSE")));
        UUID r1 = UUID.fromString(JsonPath.read(v1, "$.id"));
        assertThat(periodRow(p1)).containsEntry("status", "CLOSED").containsEntry("current_version", 1);
        List<Map<String, Object>> l1 = lines(r1);
        assertThat(sum(l1, "WORKED_DAYS")).isEqualByComparingTo(storedWorkedDays(p1));
        assertThat(sum(l1, "WORKED_DAYS")).isGreaterThanOrEqualTo(new BigDecimal("3"));
        assertThat(sum(l1, "STD_DAYS")).isCloseTo(new BigDecimal("26"), org.assertj.core.data.Offset.offset(
                new BigDecimal("0.02")));
        assertThat(l1).filteredOn(l -> "STD_DAYS".equals(l.get("category_code")))
                .allSatisfy(l -> assertThat((BigDecimal) l.get("period_quantity")).isEqualByComparingTo("26"));
        assertThat(ADMIN.queryForObject("select line_count from att.attendance_result where id = ?", Integer.class,
                r1)).isEqualTo(l1.size());
        assertThat(events(w, "att.AttendancePeriodClosed")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"cause\": \"CLOSE\"", "\"resultVersion\": 1"));

        // the close hold: version check, idempotent acquire, other run refused
        UUID run1 = Ids.newId();
        HoldOutcome wrong = call(w, () -> handover.acquireHold(p1, 2, run1, null));
        assertThat(wrong.granted()).isFalse();
        assertThat(wrong.refusalCode()).isEqualTo("version-not-current");
        HoldOutcome granted = call(w, () -> handover.acquireHold(p1, 1, run1, null));
        assertThat(granted.granted()).isTrue();
        assertThat(call(w, () -> handover.acquireHold(p1, 1, run1, null)).hold().id())
                .isEqualTo(granted.hold().id());
        assertThat(call(w, () -> handover.acquireHold(p1, 1, Ids.newId(), null)).refusalCode())
                .isEqualTo("held-by-other-run");
        assertThat(events(w, "att.AttendancePeriodHoldGranted")).hasSize(1);

        // reopen while held: a release request; Payroll's release reopens the period automatically (BR-ATT-087)
        postJson(w, "/attendance-periods/" + p1 + "/reopen", "{\"reason\":\"late leave approval\"}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("RELEASE_REQUESTED"));
        assertThat(periodRow(p1)).containsEntry("status", "CLOSED")
                .containsEntry("reopen_request_reason", "late leave approval");
        assertThat(call(w, () -> handover.releaseHold(p1, run1, "REOPEN_REQUESTED", null)).granted()).isTrue();
        assertThat(periodRow(p1)).containsEntry("status", "OPEN").containsEntry("reopen_requested_at", null);
        assertThat(ADMIN.queryForObject("select status from att.attendance_result where id = ?", String.class, r1))
                .isEqualTo("WITHDRAWN");
        assertThat(events(w, "att.AttendancePeriodReopened")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"automatic\": true", "\"withdrawnVersion\": 1"));
        assertThat(call(w, () -> handover.releaseHold(p1, run1, "REOPEN_REQUESTED", null)).granted()).isTrue();

        // re-close: v2, then a run holds and is approved: LOCKED
        String v2 = body(close(w, p1, WAIVE_ALL).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.closeCause").value("RECLOSE")));
        UUID r2 = UUID.fromString(JsonPath.read(v2, "$.id"));
        assertThat(lines(r2)).hasSameSizeAs(l1);
        assertThat(ADMIN.queryForObject("select checksum_sha256 = (select checksum_sha256 from att.attendance_result"
                + " where id = ?) from att.attendance_result where id = ?", Boolean.class, r1, r2)).isTrue();
        UUID run2 = Ids.newId();
        assertThat(call(w, () -> handover.acquireHold(p1, 2, run2, null)).granted()).isTrue();
        PayrollRunStatusChanged approved = new PayrollRunStatusChanged(Ids.newId(), run2, "APPROVED",
                List.of(new HeldVersion(p1, 2, null)));
        assertThat(call(w, () -> payroll.deliver(approved))).isEqualTo("PROCESSED");
        assertThat(call(w, () -> payroll.deliver(approved))).isEqualTo("DUPLICATE");
        assertThat(periodRow(p1)).containsEntry("status", "LOCKED").containsEntry("locked_version", 2);
        assertThat(ADMIN.queryForObject("select status from att.attendance_result where id = ?", String.class, r2))
                .isEqualTo("LOCKED");
        assertThat(events(w, "att.AttendancePeriodLocked")).hasSize(1);
        postJson(w, "/attendance-periods/" + p1 + "/reopen", "{\"reason\":\"x\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("period-locked"));
        assertThat(call(w, () -> handover.releaseHold(p1, run2, "WITHDRAWN", null)).refusalCode())
                .isEqualTo("hold-consumed");

        // REVERSED changes nothing; UNLOCKED takes it back to CLOSED with the same version
        assertThat(call(w, () -> payroll.deliver(new PayrollRunStatusChanged(Ids.newId(), run2, "REVERSED",
                List.of())))).isEqualTo("SKIPPED");
        assertThat(call(w, () -> payroll.deliver(new PayrollRunStatusChanged(Ids.newId(), run2, "UNLOCKED",
                List.of())))).isEqualTo("PROCESSED");
        assertThat(periodRow(p1)).containsEntry("status", "CLOSED").containsEntry("current_version", 2)
                .containsEntry("locked_version", null);
        assertThat(ADMIN.queryForObject("select status from att.attendance_result where id = ?", String.class, r2))
                .isEqualTo("PUBLISHED");
        assertThat(ADMIN.queryForObject("""
                select release_reason from att.attendance_result_hold where payroll_run_id = ?""", String.class,
                run2)).isEqualTo("UNLOCKED");
        assertThat(events(w, "att.AttendancePeriodClosed")).last()
                .satisfies(e -> assertThat(e).contains("\"cause\": \"UNLOCK\"", "\"resultVersion\": 2"));
        mvc.perform(get(BASE + "/attendance-periods/" + p1).with(as(w.admin()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.currentVersion").value(2));
    }

    @Test
    void aChangeAfterTheLockBecomesRetroLinesOfTheNextClose() throws Exception {
        World w = world("att-close-retro");
        assertThat(calendar(w, Ids.newId())).isEqualTo("PROCESSED");
        UUID p1 = period(w, P1_START);
        UUID p2 = period(w, P2_START);
        for (int i = 0; i < 3; i++) {
            fullDay(w, MON.plusDays(i));
        }
        recompute(w, P1_START, P1_END);
        UUID r1 = UUID.fromString(JsonPath.read(body(close(w, p1, WAIVE_ALL).andExpect(status().isCreated())),
                "$.id"));
        UUID run = Ids.newId();
        assertThat(call(w, () -> handover.acquireHold(p1, 1, run, null)).granted()).isTrue();
        call(w, () -> payroll.deliver(new PayrollRunStatusChanged(Ids.newId(), run, "APPROVED", List.of())));
        assertThat(periodRow(p1)).containsEntry("status", "LOCKED");
        BigDecimal lockedWorked = sum(lines(r1), "WORKED_DAYS");

        // a forgotten day of the locked period: its day is not rewritten, a retro request is recorded
        LocalDate thursday = MON.plusDays(3);
        Integer calcBefore = ADMIN.queryForObject("""
                select calc_version from att.daily_timesheet where employment_id = ? and work_date = ?""",
                Integer.class, w.emp(), thursday);
        fullDay(w, thursday);
        recompute(w, P1_START, P1_END);
        assertThat(ADMIN.queryForObject("""
                select calc_version from att.daily_timesheet where employment_id = ? and work_date = ?""",
                Integer.class, w.emp(), thursday)).isEqualTo(calcBefore);
        mvc.perform(get(BASE + "/attendance-periods/" + p1 + "/retro-requests").with(as(w.admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].firstDate").value(thursday.toString()));

        // the next close carries the difference against the locked version
        recompute(w, P2_START, P2_END);
        UUID r2 = UUID.fromString(JsonPath.read(body(close(w, p2, WAIVE_ALL).andExpect(status().isCreated())),
                "$.id"));
        List<Map<String, Object>> retro = lines(r2).stream().filter(l -> (Boolean) l.get("is_retro")).toList();
        assertThat(sum(retro, "RETRO.WORKED_DAYS")).isEqualByComparingTo("1");
        assertThat(retro).allSatisfy(l -> {
            assertThat(l.get("original_period_id")).isEqualTo(p1);
            assertThat(l.get("original_result_id")).isEqualTo(r1);
        });
        assertThat(sum(lines(r1), "WORKED_DAYS")).isEqualByComparingTo(lockedWorked);   // snapshot untouched
        assertThat(events(w, "att.RetroDeltaCreated")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"category\": \"WORKED_DAYS\"", "\"quantity\": 1.00"));
        assertThat(ADMIN.queryForObject("select status from att.retro_request where period_id = ?", String.class,
                p1)).isEqualTo("APPLIED");

        // reopen and re-close the target period: the delta is issued again, once
        postJson(w, "/attendance-periods/" + p2 + "/reopen", "{\"reason\":\"fix\"}").andExpect(status().isOk());
        UUID r3 = UUID.fromString(JsonPath.read(body(close(w, p2, WAIVE_ALL).andExpect(status().isCreated())),
                "$.id"));
        assertThat(sum(lines(r3), "RETRO.WORKED_DAYS")).isEqualByComparingTo("1");

        // nothing changed since: a new close of the period does not repeat it
        recompute(w, P1_START, P1_END);
        postJson(w, "/attendance-periods/" + p2 + "/reopen", "{\"reason\":\"again\"}").andExpect(status().isOk());
        UUID r4 = UUID.fromString(JsonPath.read(body(close(w, p2, WAIVE_ALL).andExpect(status().isCreated())),
                "$.id"));
        assertThat(sum(lines(r4), "RETRO.WORKED_DAYS")).isEqualByComparingTo("1");
    }

    @Test
    void aLeaverIsClosedEarlyAndLeftOutOfThePeriodResult() throws Exception {
        World w = world("att-close-leaver");
        LocalDate lwd = MON.plusDays(2);
        ADMIN.update("update att.ext_hr_employment set termination_date = ? where employment_id = ?", lwd, w.emp());
        calendar(w, Ids.newId());
        UUID p1 = period(w, P1_START);
        for (int i = 0; i < 3; i++) {
            fullDay(w, MON.plusDays(i));
        }
        recompute(w, P1_START, P1_END);

        postJson(w, "/attendance-periods/" + p1 + "/employments/" + Ids.newId() + "/early-close", "{}")
                .andExpect(status().isNotFound());
        String early = body(postJson(w, "/attendance-periods/" + p1 + "/employments/" + w.emp() + "/early-close",
                "{\"waive\":[\"ABSENT_NO_CODE\",\"MISSING_PUNCH\"],\"waiveReason\":\"leaver\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.scope").value("EMPLOYMENT"))
                .andExpect(jsonPath("$.kind").value("EARLY_CLOSE"))
                .andExpect(jsonPath("$.attDateTo").value(lwd.toString())));
        UUID er = UUID.fromString(JsonPath.read(early, "$.id"));
        assertThat(sum(lines(er), "WORKED_DAYS")).isGreaterThanOrEqualTo(new BigDecimal("3"));
        assertThat(events(w, "att.LeaverAttendanceClosed")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"lastWorkingDate\": \"" + lwd + "\""));
        // Payroll's final-pay run holds the early-close version
        assertThat(call(w, () -> handover.acquireHold(p1, 1, Ids.newId(), w.emp())).granted()).isTrue();

        UUID pr = UUID.fromString(JsonPath.read(body(close(w, p1, WAIVE_ALL).andExpect(status().isCreated())),
                "$.id"));
        assertThat(lines(pr)).isEmpty();
    }
}
