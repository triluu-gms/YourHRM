package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.application.schedule.AssignmentProjection;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.people.api.WorkingDayCalendar;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Shifts, weekly patterns, rule-based and manual schedule assignment, planned days with per-employee substitute days,
 * standard days (BRD 03 §4.2: BR-ATT-010/011/014/015/016, with BR-ATT-003 from §4.1).
 */
class ScheduleIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final String ANCHOR = "2027-01-04"; // a Monday

    @Autowired
    AssignmentProjection projection;

    @Autowired
    WorkingDayCalendar peopleCalendar;

    record World(UUID tenant, TestUser admin, UUID le) {
    }

    World world(String slug) {
        UUID tenant = FIX.tenant(slug);
        UUID role = FIX.role(tenant, "TA_ADMIN", false, AttendancePermissions.SCHEDULE_CONFIG_VIEW,
                AttendancePermissions.SCHEDULE_CONFIG_UPDATE, AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE, AttendancePermissions.HOLIDAY_CALENDAR_VIEW,
                AttendancePermissions.HOLIDAY_CALENDAR_UPDATE);
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.BREAK_DUE_AFTER_MIN, 360);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.BREAK_MIN_DAY, 30);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.BREAK_MIN_NIGHT, 45);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY, 8);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK, 48);
        return new World(tenant, admin, FIX.legalEntity(tenant, "LE1"));
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions putJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(put(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    UUID shift(World w, String code, String start, String end, String breaks, String extra) throws Exception {
        String json = """
                {"code":"%s","nameVi":"Ca %s","startTime":"%s","endTime":"%s","breaks":[%s]%s}"""
                .formatted(code, code, start, end, breaks, extra.isEmpty() ? "" : "," + extra);
        return UUID.fromString(JsonPath.read(body(postJson(w.admin(), "/shifts", json)
                .andExpect(status().isCreated())), "$.shift.id"));
    }

    static final String LUNCH = "{\"start\":\"12:00\",\"end\":\"13:00\"}";

    /** 7 day kinds Monday..Sunday as "SHIFT_CODE", "REST" or "OFF". */
    static String days(String... kinds) {
        return IntStream.range(0, kinds.length).mapToObj(i -> {
            String k = kinds[i];
            return "REST".equals(k) || "OFF".equals(k)
                    ? "{\"dayIndex\":%d,\"dayKind\":\"%s\"}".formatted(i, k)
                    : "{\"dayIndex\":%d,\"dayKind\":\"WORK\",\"shiftCode\":\"%s\"}".formatted(i, k);
        }).collect(Collectors.joining(",", "[", "]"));
    }

    ResultActions createPattern(World w, String code, int cycle, String days) throws Exception {
        return postJson(w.admin(), "/schedule-patterns", """
                {"code":"%s","nameVi":"Lịch %s","cycleLengthDays":%d,"anchorDate":"%s","days":%s}"""
                .formatted(code, code, cycle, ANCHOR, days));
    }

    UUID pattern(World w, String code, String... kinds) throws Exception {
        return UUID.fromString(JsonPath.read(body(createPattern(w, code, kinds.length, days(kinds))
                .andExpect(status().isCreated())), "$.pattern.id"));
    }

    UUID rule(World w, String code, int priority, UUID location, UUID pattern) throws Exception {
        String json = """
                {"code":"%s","priority":%d,"legalEntityId":"%s",%s"patternId":"%s","validFrom":"2027-01-01"}"""
                .formatted(code, priority, w.le(), location == null ? "" : "\"locationId\":\"" + location + "\",",
                        pattern);
        return UUID.fromString(JsonPath.read(body(postJson(w.admin(), "/schedule-rules", json)
                .andExpect(status().isCreated())), "$.rule.id"));
    }

    /** Feeds one People assignment version into T&A, as people.AssignmentChanged does. */
    void line(World w, UUID versionId, UUID key, UUID employment, UUID location, String from, String to) {
        TenantContext ctx = new TenantContext(w.tenant(), w.admin().userId(), w.admin().identityId().toString(),
                "it", "vi");
        TenantContext.runWith(ctx, () -> projection.apply(new LineVersion(versionId, key, employment, Ids.newId(),
                "PRIMARY", w.le(), Ids.newId(), null, null, location, null, null, "STAFF", "FULL_TIME",
                BigDecimal.ONE, LocalDate.parse(from), to == null ? null : LocalDate.parse(to)), false));
    }

    List<Map<String, Object>> schedules(UUID employment) {
        return ADMIN.queryForList("""
                select p.code, a.source, a.valid_from::text as valid_from, a.valid_to::text as valid_to
                  from att.schedule_assignment a join att.schedule_pattern p on p.id = a.pattern_id
                 where a.employment_id = ? and a.status = 'ACTIVE' order by a.valid_from""", employment);
    }

    static String rows(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("code") + "/" + r.get("source") + "/" + r.get("valid_from") + ".."
                + r.get("valid_to")).collect(Collectors.joining(" "));
    }

    /** An OPEN attendance period of the world's legal entity (as the pay-calendar consumer will create it). */
    static UUID period(World w, String start, String end, String method, BigDecimal value) {
        UUID id = Ids.newId();
        ADMIN.update("""
                insert into att.attendance_period (tenant_id, id, legal_entity_id, pay_group_id, period_start,
                       period_end, std_day_method, std_day_value)
                values (?, ?, ?, ?, ?::date, ?::date, ?, ?)""", w.tenant(), id, w.le(), Ids.newId(), start, end,
                method, value);
        return id;
    }

    // ============================================================================================ tests

    @Test
    void theStandardDayValueFollowsPayrollsRuleAndIsFrozenOnceClosed() {
        World w = world("att-sch-stdvalue");
        assertThatThrownBy(() -> period(w, "2027-01-01", "2027-01-31", "FIXED_DAYS", null))
                .hasMessageContaining("attendance_period_std_value_ck");
        assertThatThrownBy(() -> period(w, "2027-02-01", "2027-02-28", "SCHEDULE_DAYS", new BigDecimal("26")))
                .hasMessageContaining("attendance_period_std_value_ck");
        assertThatThrownBy(() -> period(w, "2027-03-01", "2027-03-31", "WEEKLY", null))
                .hasMessageContaining("attendance_period_std_method_ck");
        UUID p = period(w, "2027-04-01", "2027-04-30", "FIXED_HOURS", new BigDecimal("176"));
        ADMIN.update("update att.attendance_period set std_day_value = 168 where id = ?", p); // OPEN: Payroll may republish
        ADMIN.update("update att.attendance_period set status = 'CLOSED', current_version = 1 where id = ?", p);
        assertThatThrownBy(() -> ADMIN.update("update att.attendance_period set std_day_value = 160 where id = ?", p))
                .hasMessageContaining("frozen");
        assertThatThrownBy(() -> ADMIN.update(
                "update att.attendance_period set std_day_method = 'SCHEDULE_DAYS', std_day_value = null where id = ?", p))
                .hasMessageContaining("frozen");
        assertThat(ADMIN.queryForObject("select std_day_value from att.attendance_period where id = ?",
                BigDecimal.class, p)).isEqualByComparingTo("168");
    }

    @Test
    void shiftsAreValidatedAndWarnAgainstLegalParameters() throws Exception {
        World w = world("att-sch-shift");
        String office = body(postJson(w.admin(), "/shifts", """
                {"code":"office","nameVi":"Hành chính","startTime":"08:00","endTime":"17:00","breaks":[%s]}"""
                .formatted(LUNCH)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.shift.code").value("OFFICE"))
                .andExpect(jsonPath("$.shift.crossesMidnight").value(false))
                .andExpect(jsonPath("$.shift.workingMinutes").value(480))
                .andExpect(jsonPath("$.shift.stdHours").value(8.0))
                .andExpect(jsonPath("$.warnings", hasSize(0))));
        assertThat((String) JsonPath.read(office, "$.shift.breaks[0].start")).isEqualTo("12:00:00");

        postJson(w.admin(), "/shifts", """
                {"code":"NIGHT","nameVi":"Ca đêm","startTime":"22:00","endTime":"06:00",
                 "breaks":[{"start":"02:00","end":"02:45","paid":true}]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shift.crossesMidnight").value(true))
                .andExpect(jsonPath("$.shift.stdHours").value(8.0))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
        // no break in a 9h shift, 9 standard hours: two statutory warnings, still saved
        postJson(w.admin(), "/shifts", """
                {"code":"LONG","nameVi":"Ca dài","startTime":"07:00","endTime":"16:00"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings", hasSize(2)));

        postJson(w.admin(), "/shifts", """
                {"code":"BAD1","nameVi":"x","startTime":"22:00","endTime":"06:00",
                 "breaks":[{"start":"07:00","end":"07:30"}]}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("break-outside-shift"));
        postJson(w.admin(), "/shifts", """
                {"code":"BAD2","nameVi":"x","startTime":"08:00","endTime":"17:00","stdHours":9,"breaks":[%s]}"""
                .formatted(LUNCH))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-std-hours"));
        postJson(w.admin(), "/shifts", """
                {"code":"OFFICE","nameVi":"x","startTime":"08:00","endTime":"17:00"}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("shift-code-taken"));
    }

    @Test
    void weeklyPatternsDeriveTheirRestDays() throws Exception {
        World w = world("att-sch-pattern");
        shift(w, "OFFICE", "08:00", "17:00", LUNCH, "");
        shift(w, "HALF", "08:00", "12:00", "", "\"dayValue\":0.5");
        shift(w, "LONG", "07:00", "17:00", LUNCH, "");

        // 5.5-day week, Sunday rest
        String half = body(createPattern(w, "W55", 7, days("OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "HALF",
                "REST")).andExpect(status().isCreated()));
        assertThat((List<Integer>) JsonPath.read(half, "$.pattern.weeklyRestDays")).containsExactly(7);
        assertThat(((Number) JsonPath.read(half, "$.pattern.stdHoursWeek")).doubleValue()).isEqualTo(44.0);
        assertThat(((Number) JsonPath.read(half, "$.pattern.workDaysWeek")).doubleValue()).isEqualTo(5.5);

        // rest day other than Sunday (BR-ATT-014)
        String tue = body(createPattern(w, "TUE", 7, days("OFFICE", "REST", "OFFICE", "OFFICE", "OFFICE", "OFFICE",
                "OFFICE")).andExpect(status().isCreated()));
        assertThat((List<Integer>) JsonPath.read(tue, "$.pattern.weeklyRestDays")).containsExactly(2);

        // alternating Saturdays: worked in week 1, OFF in week 2
        createPattern(w, "ALT", 14, days("OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST",
                "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFF", "REST"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pattern.weeklyRestDays", hasSize(1)));
        createPattern(w, "ALT_BAD", 14, days("OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST",
                "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST", "REST"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("alternating-rest-day"));
        createPattern(w, "NO_REST", 7, days("OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFF", "OFF"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("weekly-rest-required"));
        createPattern(w, "ROT", 4, days("OFFICE", "OFFICE", "REST", "REST"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-cycle"));
        postJson(w.admin(), "/schedule-patterns", """
                {"code":"SUNDAY_ANCHOR","nameVi":"x","anchorDate":"2027-01-03","days":%s}"""
                .formatted(days("OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-anchor"));

        // 6 x 9h = 54h a week: above the 48h parameter, a warning
        createPattern(w, "W6_LONG", 7, days("LONG", "LONG", "LONG", "LONG", "LONG", "LONG", "REST"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings", hasSize(1)));
    }

    @Test
    void rulesAssignSchedulesAndManualRowsOverrideThem() throws Exception {
        World w = world("att-sch-rules");
        UUID hcm = FIX.location(w.tenant(), w.le(), "HCM");
        UUID officeShift = shift(w, "OFFICE", "08:00", "17:00", LUNCH, "");
        UUID p5 = pattern(w, "P5", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST", "REST");
        UUID p6 = pattern(w, "P6", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST");

        UUID a = Ids.newId();
        UUID aKey = Ids.newId();
        UUID b = Ids.newId();
        UUID bKey = Ids.newId();
        line(w, Ids.newId(), aKey, a, hcm, "2027-01-01", null);
        line(w, Ids.newId(), bKey, b, null, "2027-01-01", null);
        assertThat(schedules(a)).isEmpty();
        mvc.perform(get(BASE + "/legal-entities/" + w.le() + "/unscheduled-employees").param("date", "2027-02-01")
                .with(as(w.admin()))).andExpect(jsonPath("$", hasSize(2)));

        rule(w, "SITE_HCM", 10, hcm, p6);
        String created = body(postJson(w.admin(), "/schedule-rules", """
                {"code":"LE_DEFAULT","priority":20,"legalEntityId":"%s","patternId":"%s","validFrom":"2027-01-01"}"""
                .formatted(w.le(), p5)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.linesChanged").value(1)));
        assertThat(rows(schedules(a))).isEqualTo("P6/RULE/2027-01-01..null");
        assertThat(rows(schedules(b))).isEqualTo("P5/RULE/2027-01-01..null");

        // A leaves the HCM site from 1 March (People version change): the entity rule applies from then
        line(w, Ids.newId(), aKey, a, null, "2027-03-01", null);
        assertThat(rows(schedules(a))).isEqualTo("P6/RULE/2027-01-01..2027-03-01 P5/RULE/2027-03-01..null");

        // manual override for B in February, then cancelled: the rule fills the gap again
        String manual = body(postJson(w.admin(), "/employments/" + b + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"2027-02-01","validTo":"2027-03-01"}"""
                .formatted(bKey, p6)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL")));
        assertThat(rows(schedules(b))).isEqualTo(
                "P5/RULE/2027-01-01..2027-02-01 P6/MANUAL/2027-02-01..2027-03-01 P5/RULE/2027-03-01..null");
        postJson(w.admin(), "/employments/" + b + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"2027-02-15"}""".formatted(bKey, p5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("schedule-assignment-overlap"));
        String ruleRowId = ADMIN.queryForObject("""
                select id::text from att.schedule_assignment where employment_id = ? and status = 'ACTIVE'
                   and source = 'RULE' order by valid_from limit 1""", String.class, b);
        postJson(w.admin(), "/employments/" + b + "/schedule-assignments/" + ruleRowId + "/cancel", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("rule-schedule-not-cancellable"));
        postJson(w.admin(), "/employments/" + b + "/schedule-assignments/" + JsonPath.read(manual, "$.id")
                + "/cancel", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(rows(schedules(b))).isEqualTo("P5/RULE/2027-01-01..null");

        // deactivating the entity rule removes B's schedule: B is on the "no schedule" list again
        UUID ruleId = UUID.fromString(JsonPath.read(created, "$.rule.id"));
        putJson(w.admin(), "/schedule-rules/" + ruleId, """
                {"priority":20,"legalEntityId":"%s","patternId":"%s","validFrom":"2027-01-01","status":"INACTIVE",
                 "version":0}""".formatted(w.le(), p5))
                .andExpect(status().isOk()).andExpect(jsonPath("$.linesChanged").value(2));
        assertThat(schedules(b)).isEmpty();
        mvc.perform(get(BASE + "/legal-entities/" + w.le() + "/unscheduled-employees").param("date", "2027-04-01")
                        .with(as(w.admin())))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].employmentId", hasItem(b.toString())));

        // an assigned shift and pattern are frozen
        putJson(w.admin(), "/shifts/" + officeShift, """
                {"nameVi":"Hành chính","startTime":"08:30","endTime":"17:30","breaks":[%s],"version":0}"""
                .formatted(LUNCH)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("shift-in-use"));
        putJson(w.admin(), "/shifts/" + officeShift, """
                {"nameVi":"Ca hành chính","startTime":"08:00","endTime":"17:00","breaks":[%s],"version":0}"""
                .formatted(LUNCH)).andExpect(status().isOk()).andExpect(jsonPath("$.shift.nameVi").value("Ca hành chính"));
        putJson(w.admin(), "/schedule-patterns/" + p6, """
                {"nameVi":"P6","anchorDate":"%s","days":%s,"version":0}""".formatted(ANCHOR,
                days("OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST", "REST")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("pattern-in-use"));
    }

    @Test
    void planWithSubstituteDaysAndStandardDays() throws Exception {
        World w = world("att-sch-plan");
        shift(w, "OFFICE", "08:00", "17:00", LUNCH, "");
        UUID p5 = pattern(w, "P5", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST", "REST");
        rule(w, "LE_DEFAULT", 10, null, p5);

        // holiday calendar of the legal entity: 1 May 2027 is a Saturday, 2 Sep a Thursday
        String cal = body(postJson(w.admin(), "/holiday-calendars", "{\"code\":\"VN\",\"nameVi\":\"Lịch lễ\"}")
                .andExpect(status().isCreated()));
        UUID calendar = UUID.fromString(JsonPath.read(cal, "$.id"));
        postJson(w.admin(), "/holiday-calendars/" + calendar + "/dates",
                "{\"holidayDate\":\"2027-05-01\",\"dayType\":\"PUBLIC\",\"nameVi\":\"Quốc tế Lao động\"}")
                .andExpect(status().isCreated());
        postJson(w.admin(), "/holiday-calendars/" + calendar + "/dates",
                "{\"holidayDate\":\"2027-09-02\",\"dayType\":\"PUBLIC\",\"nameVi\":\"Quốc khánh\"}")
                .andExpect(status().isCreated());
        postJson(w.admin(), "/holiday-calendars/" + calendar + "/assignments",
                "{\"legalEntityId\":\"%s\",\"validFrom\":\"2027-01-01\"}".formatted(w.le()))
                .andExpect(status().isCreated());

        UUID e = Ids.newId();
        line(w, Ids.newId(), Ids.newId(), e, null, "2027-05-17", null);
        ADMIN.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       status, worker_category_code, source_event_id, source_version)
                values (?, ?, ?, ?, date '2027-05-17', 'ACTIVE', 'EMPLOYEE', ?, 1)""",
                w.tenant(), e, Ids.newId(), w.le(), Ids.newId());

        // a second employee, scheduled from 1 April: 1 May (Sat, rest day) moves to Monday 3 May (BR-ATT-003)
        UUID f = Ids.newId();
        line(w, Ids.newId(), Ids.newId(), f, null, "2027-04-01", null);
        String plan = body(mvc.perform(get(BASE + "/employments/" + f + "/work-plan").with(as(w.admin()))
                .param("from", "2027-05-01").param("to", "2027-05-07")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7))));
        assertThat((String) JsonPath.read(plan, "$[0].dayKind")).isEqualTo("REST");
        assertThat((String) JsonPath.read(plan, "$[0].holiday.dayType")).isEqualTo("PUBLIC");
        assertThat((String) JsonPath.read(plan, "$[2].date")).isEqualTo("2027-05-03");
        assertThat((String) JsonPath.read(plan, "$[2].substituteForDate")).isEqualTo("2027-05-01");
        assertThat((Boolean) JsonPath.read(plan, "$[2].workingDay")).isFalse();
        assertThat((Boolean) JsonPath.read(plan, "$[3].workingDay")).isTrue();
        mvc.perform(get(BASE + "/employments/" + f + "/work-plan").with(as(w.admin()))
                        .param("from", "2027-09-02").param("to", "2027-09-02"))
                .andExpect(jsonPath("$[0].dayKind").value("WORK"))
                .andExpect(jsonPath("$[0].holiday.nameVi").value("Quốc khánh"))
                .andExpect(jsonPath("$[0].substituteForDate").doesNotExist());

        // standard days of May 2027 for the employee hired on Monday 17 May (BR-ATT-016)
        mvc.perform(get(BASE + "/employments/" + e + "/standard-days/preview").with(as(w.admin()))
                        .param("from", "2027-05-01").param("to", "2027-05-31").param("method", "SCHEDULE_DAYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStdDays").value(21.0))
                .andExpect(jsonPath("$.periodStdHours").value(168.0))
                .andExpect(jsonPath("$.segments", hasSize(1)))
                .andExpect(jsonPath("$.segments[0].stdDays").value(11.0))
                .andExpect(jsonPath("$.segments[0].from").value("2027-05-17"));
        mvc.perform(get(BASE + "/employments/" + e + "/standard-days/preview").with(as(w.admin()))
                        .param("from", "2027-05-01").param("to", "2027-05-31").param("method", "FIXED_DAYS")
                        .param("value", "26"))
                .andExpect(jsonPath("$.periodStdDays").value(26.0))
                .andExpect(jsonPath("$.segments[0].stdDays").value(12.5806));
        mvc.perform(get(BASE + "/employments/" + e + "/standard-days/preview").with(as(w.admin()))
                        .param("from", "2027-05-01").param("to", "2027-05-31").param("method", "FIXED_DAYS"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("std-day-value-required"));

        // the period carries Payroll's method and value (att.attendance_period.std_day_method / std_day_value)
        UUID fixed = period(w, "2027-05-01", "2027-05-31", "FIXED_DAYS", new BigDecimal("26"));
        mvc.perform(get(BASE + "/employments/" + e + "/standard-days").with(as(w.admin()))
                        .param("periodId", fixed.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("FIXED_DAYS"))
                .andExpect(jsonPath("$.methodValue").value(26.0))
                .andExpect(jsonPath("$.periodStdDays").value(26.0))
                .andExpect(jsonPath("$.segments[0].stdDays").value(12.5806));
        UUID schedule = period(w, "2027-06-01", "2027-06-30", "SCHEDULE_DAYS", null);
        mvc.perform(get(BASE + "/employments/" + e + "/standard-days").with(as(w.admin()))
                        .param("periodId", schedule.toString()))
                .andExpect(jsonPath("$.periodStdDays").value(22.0))
                .andExpect(jsonPath("$.methodValue").doesNotExist());
        UUID unpublished = period(w, "2027-07-01", "2027-07-31", null, null);
        mvc.perform(get(BASE + "/employments/" + e + "/standard-days").with(as(w.admin()))
                        .param("periodId", unpublished.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("std-day-method-not-published"));
        UUID before = period(w, "2027-04-01", "2027-04-30", "CALENDAR_DAYS", null);
        mvc.perform(get(BASE + "/employments/" + e + "/standard-days").with(as(w.admin()))
                        .param("periodId", before.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("employment-not-in-period"));

        // People's working-day SPI now knows the holidays of the legal entity
        TenantContext ctx = new TenantContext(w.tenant(), w.admin().userId(), w.admin().identityId().toString(),
                "it", "vi");
        TenantContext.runWith(ctx, () -> {
            assertThat(peopleCalendar.isWorkingDay(w.le(), null, LocalDate.parse("2027-09-02"))).isFalse();
            assertThat(peopleCalendar.isWorkingDay(w.le(), null, LocalDate.parse("2027-09-03"))).isTrue();
            assertThat(peopleCalendar.isWorkingDay(w.le(), null, LocalDate.parse("2027-09-05"))).isFalse();
        });
    }

    @Test
    void employeeScopeAndUnknownEmployments() throws Exception {
        World w = world("att-sch-scope");
        UUID otherLe = FIX.legalEntity(w.tenant(), "LE2");
        shift(w, "OFFICE", "08:00", "17:00", LUNCH, "");
        UUID p5 = pattern(w, "P5", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "OFFICE", "REST", "REST");
        UUID e = Ids.newId();
        UUID key = Ids.newId();
        line(w, Ids.newId(), key, e, null, "2027-01-01", null);

        UUID role = FIX.role(w.tenant(), "TA_LE2", false, AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE, AttendancePermissions.SCHEDULE_CONFIG_UPDATE);
        TestUser le2 = FIX.user(w.tenant(), "le2-" + w.tenant().toString().substring(24) + "@test.local");
        FIX.assign(le2, role, "LEGAL_ENTITY", List.of(otherLe), false, TestFixtures.EPOCH, null);
        mvc.perform(get(BASE + "/employments/" + e + "/schedule-assignments").with(as(le2)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("employment-not-in-scope"));
        postJson(le2, "/employments/" + e + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"2027-01-01"}""".formatted(key, p5))
                .andExpect(status().isForbidden());
        // a tenant-wide rule needs a tenant-wide grant
        postJson(le2, "/schedule-rules", """
                {"code":"ALL","patternId":"%s","validFrom":"2027-01-01"}""".formatted(p5))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("legal-entity-not-in-scope"));

        mvc.perform(get(BASE + "/employments/" + Ids.newId() + "/schedule-assignments").with(as(w.admin())))
                .andExpect(status().isNotFound());
        // the line must belong to the employment, and the schedule must lie inside the line
        postJson(w.admin(), "/employments/" + e + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"2027-01-01"}""".formatted(Ids.newId(), p5))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown-assignment-line"));
        postJson(w.admin(), "/employments/" + e + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"2026-12-01"}""".formatted(key, p5))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("outside-assignment-line"));
        postJson(w.admin(), "/employments/" + e + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"2027-01-01"}""".formatted(key, p5))
                .andExpect(status().isCreated());
        mvc.perform(get(BASE + "/employments/" + e + "/schedule-assignments").with(as(w.admin())))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].patternCode").value("P5"));
    }
}
