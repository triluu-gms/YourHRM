package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.HolidayCalendars;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Holiday calendars (BRD 03 §4.1): tenant calendars and assignments (BR-ATT-001), copy and re-acceptance of the
 * platform VN template (BR-ATT-002), days for foreign employees (BR-ATT-005), change events with retro detection
 * (BR-ATT-006), and the always-on read API (ATT-P-16).
 */
class HolidayCalendarIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final UUID GLOBAL = new UUID(0L, 0L);

    @Autowired
    HolidayCalendars holidayCalendars;

    record World(UUID tenant, TestUser admin, UUID le) {
    }

    /** A tenant whose T&A admin holds both holiday permissions tenant-wide. */
    World world(String slug) {
        UUID tenant = FIX.tenant(slug);
        UUID role = FIX.role(tenant, "TA_ADMIN", false, AttendancePermissions.HOLIDAY_CALENDAR_VIEW,
                AttendancePermissions.HOLIDAY_CALENDAR_UPDATE);
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        return new World(tenant, admin, FIX.legalEntity(tenant, "LE1"));
    }

    /** A platform template (sentinel tenant) with the 2027 rows used by the tests; codes unique per call. */
    static UUID template(String code) {
        UUID id = Ids.newId();
        ADMIN.update("""
                insert into att.holiday_calendar (tenant_id, id, code, name_vi, name_en, source)
                values (?, ?, ?, 'Lịch lễ Việt Nam', 'Vietnam holidays', 'PLATFORM_TEMPLATE')""", GLOBAL, id, code);
        templateDate(id, "2027-01-01", "PUBLIC", "NEW_YEAR", "Tết Dương lịch", null, "ALL", null);
        templateDate(id, "2027-02-06", "PUBLIC", "TET_AL", "Tết Âm lịch", null, "ALL", null);
        templateDate(id, "2027-02-07", "PUBLIC", "TET_AL", "Tết Âm lịch", null, "ALL", null);
        templateDate(id, "2027-02-08", "SUBSTITUTE", "TET_AL", "Nghỉ bù Tết", "2027-02-07", "ALL", null);
        templateDate(id, "2027-09-02", "PUBLIC", "NATIONAL_DAY", "Quốc khánh", null, "ALL", null);
        templateDate(id, "2027-10-03", "PUBLIC", "FOREIGN_NATIONAL_DAY", "Quốc khánh Hàn Quốc", null, "FOREIGNERS",
                "KR");
        // Phase 2 day type in the template: never copied in Phase 1
        templateDate(id, "2027-04-29", "SWAP_OFF", "SWAP", "Hoán đổi", null, "ALL", null);
        return id;
    }

    static void templateDate(UUID template, String date, String type, String code, String name, String substituteFor,
            String eligibility, String nationality) {
        ADMIN.update("""
                insert into att.holiday_date (tenant_id, calendar_id, holiday_date, day_type, holiday_code, name_vi,
                       substitute_for_date, eligibility, nationality_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", GLOBAL, template, Date.valueOf(date), type, code, name,
                substituteFor == null ? null : Date.valueOf(substituteFor), eligibility, nationality);
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

    UUID copyOfTemplate(World w, UUID template, String code) throws Exception {
        String created = body(postJson(w.admin(), "/holiday-calendars", """
                {"code":"%s","templateId":"%s","year":2027}""".formatted(code, template))
                .andExpect(status().isCreated()));
        return UUID.fromString(JsonPath.read(created, "$.id"));
    }

    void assign(World w, UUID calendar, UUID location, String from) throws Exception {
        postJson(w.admin(), "/holiday-calendars/" + calendar + "/assignments", """
                {"legalEntityId":"%s",%s"validFrom":"%s"}""".formatted(w.le(),
                location == null ? "" : "\"locationId\":\"" + location + "\",", from))
                .andExpect(status().isCreated());
    }

    UUID dateId(UUID calendar, String date, String type) {
        return ADMIN.queryForObject("select id from att.holiday_date where calendar_id = ? and holiday_date = ?"
                + " and day_type = ?", UUID.class, calendar, Date.valueOf(date), type);
    }

    int dateVersion(UUID dateId) {
        return ADMIN.queryForObject("select version from att.holiday_date where id = ?", Integer.class, dateId);
    }

    List<Map<String, Object>> events(UUID tenant) {
        return ADMIN.queryForList("""
                select event_type, aggregate_id, legal_entity_id, is_retro, effective_date, payload->>'change' as change,
                       payload::text as payload
                  from att.outbox_event where tenant_id = ? order by created_at, id""", tenant);
    }

    // ============================================================================================ tests

    @Test
    void copyTheVnTemplateAssignItAndResolveHolidays() throws Exception {
        World w = world("att-hol-copy");
        UUID template = template("VN_IT_COPY_" + w.tenant().toString().substring(24));

        mvc.perform(get(BASE + "/holiday-calendar-templates").with(as(w.admin())))
                .andExpect(status().isOk());
        UUID calendar = copyOfTemplate(w, template, "VN-2027");

        mvc.perform(get(BASE + "/holiday-calendars/" + calendar).with(as(w.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("TENANT_COPY"))
                .andExpect(jsonPath("$.templateCalendarId").value(template.toString()))
                .andExpect(jsonPath("$.templateYear").value(2027));
        // 6 Phase-1 rows copied, the SWAP_OFF row skipped
        mvc.perform(get(BASE + "/holiday-calendars/" + calendar + "/dates").param("year", "2027").with(as(w.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));

        assign(w, calendar, null, "2027-01-01");

        // resolved: foreigner day only with a matching nationality (BR-ATT-005)
        mvc.perform(get(BASE + "/legal-entities/" + w.le() + "/holidays").with(as(w.admin()))
                        .param("from", "2027-01-01").param("to", "2027-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].date").value("2027-01-01"))
                .andExpect(jsonPath("$[3].dayType").value("SUBSTITUTE"))
                .andExpect(jsonPath("$[3].substituteForDate").value("2027-02-07"));
        mvc.perform(get(BASE + "/legal-entities/" + w.le() + "/holidays").with(as(w.admin()))
                        .param("from", "2027-01-01").param("to", "2027-12-31").param("nationality", "kr"))
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[5].foreignersOnly").value(true));
        mvc.perform(get(BASE + "/legal-entities/" + w.le() + "/holidays").with(as(w.admin()))
                        .param("from", "2027-01-01").param("to", "2027-12-31").param("nationality", "JP"))
                .andExpect(jsonPath("$", hasSize(5)));

        // in-process read API (ATT-P-16), no permission needed
        TenantContext ctx = new TenantContext(w.tenant(), w.admin().userId(), w.admin().identityId().toString(),
                "it", "vi");
        TenantContext.runWith(ctx, () -> {
            assertThat(holidayCalendars.isHoliday(w.le(), null, LocalDate.of(2027, 9, 2))).isTrue();
            assertThat(holidayCalendars.isHoliday(w.le(), null, LocalDate.of(2027, 9, 3))).isFalse();
            assertThat(holidayCalendars.isHoliday(w.le(), null, LocalDate.of(2027, 10, 3))).isFalse();
            assertThat(holidayCalendars.calendarFor(w.le(), null, LocalDate.of(2026, 12, 31))).isEmpty();
            assertThat(holidayCalendars.calendarFor(w.le(), null, LocalDate.of(2027, 1, 1))).contains(calendar);
        });

        // copy and assignment each wrote att.HolidayCalendarChanged
        List<Map<String, Object>> events = events(w.tenant());
        assertThat(events).extracting(e -> e.get("event_type")).containsOnly("att.HolidayCalendarChanged");
        assertThat(events).extracting(e -> e.get("change")).containsExactly("TEMPLATE_APPLIED", "ASSIGNMENT_CHANGED");
        assertThat((String) events.get(1).get("payload")).contains(w.le().toString());
        assertThat(events.get(1).get("legal_entity_id")).isEqualTo(w.le());
    }

    @Test
    void aLocationCalendarWinsOverTheLegalEntityCalendar() throws Exception {
        World w = world("att-hol-loc");
        UUID template = template("VN_IT_LOC_" + w.tenant().toString().substring(24));
        UUID national = copyOfTemplate(w, template, "VN");
        UUID location = FIX.location(w.tenant(), w.le(), "HCM");
        String created = body(postJson(w.admin(), "/holiday-calendars", """
                {"code":"HCM-SITE","nameVi":"Lịch chi nhánh HCM"}""").andExpect(status().isCreated()));
        UUID site = UUID.fromString(JsonPath.read(created, "$.id"));
        postJson(w.admin(), "/holiday-calendars/" + site + "/dates", """
                {"holidayDate":"2027-06-15","dayType":"COMPANY","nameVi":"Ngày thành lập chi nhánh"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paid").value(true))
                .andExpect(jsonPath("$.eligibility").value("ALL"));

        assign(w, national, null, "2027-01-01");
        assign(w, site, location, "2027-06-01");

        // before the site assignment the entity calendar applies to the location too
        mvc.perform(get(BASE + "/legal-entities/" + w.le() + "/holidays").with(as(w.admin()))
                        .param("from", "2027-01-01").param("to", "2027-12-31")
                        .param("locationId", location.toString()))
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[4].date").value("2027-06-15"))
                .andExpect(jsonPath("$[4].calendarId").value(site.toString()));
        // without a location: the entity calendar only
        mvc.perform(get(BASE + "/legal-entities/" + w.le() + "/holidays").with(as(w.admin()))
                        .param("from", "2027-01-01").param("to", "2027-12-31"))
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[4].date").value("2027-09-02"));

        // a second entity-wide calendar overlapping the first is refused
        postJson(w.admin(), "/holiday-calendars/" + site + "/assignments", """
                {"legalEntityId":"%s","validFrom":"2027-03-01"}""".formatted(w.le()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("holiday-calendar-assignment-overlap"));

        mvc.perform(get(BASE + "/legal-entities/" + w.le() + "/holiday-calendar-assignments").with(as(w.admin())))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void overrideATemplateDateAndReAcceptARepublishedTemplate() throws Exception {
        World w = world("att-hol-diff");
        UUID template = template("VN_IT_DIFF_" + w.tenant().toString().substring(24));
        UUID calendar = copyOfTemplate(w, template, "VN");

        // the tenant gives 2 Sep a different English name (override, reason audited) and cancels New Year
        UUID nationalDay = dateId(calendar, "2027-09-02", "PUBLIC");
        putJson(w.admin(), "/holiday-calendars/" + calendar + "/dates/" + nationalDay, """
                {"holidayDate":"2027-09-02","dayType":"PUBLIC","holidayCode":"NATIONAL_DAY","nameVi":"Quốc khánh",
                 "nameEn":"National Day","version":%d,"reason":"Company naming"}""".formatted(dateVersion(nationalDay)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameEn").value("National Day"));
        UUID newYear = dateId(calendar, "2027-01-01", "PUBLIC");
        postJson(w.admin(), "/holiday-calendars/" + calendar + "/dates/" + newYear + "/cancel", """
                {"version":%d,"reason":"Working day by agreement, compensated later"}""".formatted(dateVersion(newYear)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and action in ('OVERRIDE','CANCEL')""",
                Integer.class, w.tenant())).isEqualTo(2);

        // the platform republishes 2027: 30 April added, 7 Feb removed (with its substitute day)
        templateDate(template, "2027-04-30", "PUBLIC", "REUNIFICATION", "Ngày Thống nhất", null, "ALL", null);
        ADMIN.update("update att.holiday_date set status = 'CANCELLED' where calendar_id = ? and holiday_date in (?, ?)",
                template, Date.valueOf("2027-02-07"), Date.valueOf("2027-02-08"));

        String diff = body(mvc.perform(get(BASE + "/holiday-calendars/" + calendar + "/template-diff")
                .param("year", "2027").with(as(w.admin()))).andExpect(status().isOk()));
        List<String> kinds = JsonPath.read(diff, "$[*].kind");
        assertThat(kinds).containsExactlyInAnyOrder("OVERRIDDEN", "ADDED", "REMOVED", "REMOVED", "CHANGED");

        // default acceptance: additions and removals only; the tenant's own edit and cancellation stay
        String applied = body(postJson(w.admin(), "/holiday-calendars/" + calendar + "/template-apply",
                "{\"year\":2027,\"reason\":\"2027 pack republished\"}").andExpect(status().isOk()));
        List<String> appliedKinds = JsonPath.read(applied, "$.applied[*].kind");
        assertThat(appliedKinds).containsExactlyInAnyOrder("ADDED", "REMOVED", "REMOVED");
        assertThat(ADMIN.queryForObject("select status from att.holiday_date where id = ?", String.class, newYear))
                .isEqualTo("CANCELLED");
        assertThat(ADMIN.queryForObject("select name_en from att.holiday_date where id = ?", String.class,
                nationalDay)).isEqualTo("National Day");

        // overrides are taken back only explicitly, by key
        String rest = body(mvc.perform(get(BASE + "/holiday-calendars/" + calendar + "/template-diff")
                .param("year", "2027").with(as(w.admin()))));
        List<String> keys = JsonPath.read(rest, "$[*].key");
        assertThat(keys).containsExactly("2027-01-01|PUBLIC|ALL|", "2027-09-02|PUBLIC|ALL|");
        postJson(w.admin(), "/holiday-calendars/" + calendar + "/template-apply",
                "{\"year\":2027,\"acceptKeys\":[\"2027-01-01|PUBLIC|ALL|\",\"2027-09-02|PUBLIC|ALL|\"]}")
                .andExpect(status().isOk());
        assertThat(ADMIN.queryForObject("select status from att.holiday_date where id = ?", String.class, newYear))
                .isEqualTo("ACTIVE");
        assertThat(ADMIN.queryForObject("select name_en from att.holiday_date where id = ?", String.class,
                nationalDay)).isNull();
        mvc.perform(get(BASE + "/holiday-calendars/" + calendar + "/template-diff").param("year", "2027")
                .with(as(w.admin()))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aChangeInsideALockedPeriodIsFlaggedRetro() throws Exception {
        World w = world("att-hol-retro");
        UUID template = template("VN_IT_RETRO_" + w.tenant().toString().substring(24));
        UUID calendar = copyOfTemplate(w, template, "VN");
        assign(w, calendar, null, "2027-01-01");
        UUID period = Ids.newId();
        ADMIN.update("""
                insert into att.attendance_period (tenant_id, id, legal_entity_id, pay_group_id, period_start,
                       period_end, status, current_version, locked_version)
                values (?, ?, ?, ?, date '2027-09-01', date '2027-09-30', 'LOCKED', 1, 1)""",
                w.tenant(), period, w.le(), Ids.newId());

        postJson(w.admin(), "/holiday-calendars/" + calendar + "/dates", """
                {"holidayDate":"2027-09-03","dayType":"PUBLIC","holidayCode":"NATIONAL_DAY","nameVi":"Quốc khánh (ngày 2)"}""")
                .andExpect(status().isCreated());
        postJson(w.admin(), "/holiday-calendars/" + calendar + "/dates", """
                {"holidayDate":"2027-12-24","dayType":"COMPANY","nameVi":"Year-end party"}""")
                .andExpect(status().isCreated());

        List<Map<String, Object>> added = events(w.tenant()).stream()
                .filter(e -> "DATE_ADDED".equals(e.get("change"))).toList();
        assertThat(added).hasSize(2);
        assertThat(added.get(0).get("is_retro")).isEqualTo(true);
        assertThat((String) added.get(0).get("payload")).contains(period.toString());
        assertThat(added.get(0).get("legal_entity_id")).isEqualTo(w.le());
        assertThat(added.get(1).get("is_retro")).isEqualTo(false);
    }

    @Test
    void invalidEntriesAreRefused() throws Exception {
        World w = world("att-hol-invalid");
        String created = body(postJson(w.admin(), "/holiday-calendars", """
                {"code":"custom","nameVi":"Lịch riêng"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CUSTOM")));
        UUID calendar = UUID.fromString(JsonPath.read(created, "$.id"));
        String dates = "/holiday-calendars/" + calendar + "/dates";

        postJson(w.admin(), "/holiday-calendars", "{\"code\":\"CUSTOM\",\"nameVi\":\"Trùng mã\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("holiday-calendar-code-taken"));
        postJson(w.admin(), dates, "{\"holidayDate\":\"2027-05-03\",\"dayType\":\"SWAP_OFF\",\"nameVi\":\"Hoán đổi\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("day-swap-not-available"));
        postJson(w.admin(), dates, """
                {"holidayDate":"2027-10-03","dayType":"PUBLIC","nameVi":"Quốc khánh","eligibility":"FOREIGNERS"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("nationality-required"));
        postJson(w.admin(), dates, """
                {"holidayDate":"2027-05-03","dayType":"SUBSTITUTE","nameVi":"Nghỉ bù","substituteForDate":"2027-05-01"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("substitute-for-unknown"));

        postJson(w.admin(), dates, "{\"holidayDate\":\"2027-05-01\",\"dayType\":\"PUBLIC\",\"nameVi\":\"Quốc tế Lao động\"}")
                .andExpect(status().isCreated());
        postJson(w.admin(), dates, "{\"holidayDate\":\"2027-05-01\",\"dayType\":\"PUBLIC\",\"nameVi\":\"Trùng\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("holiday-date-exists"));
        postJson(w.admin(), dates, """
                {"holidayDate":"2027-05-03","dayType":"SUBSTITUTE","nameVi":"Nghỉ bù","substituteForDate":"2027-05-01"}""")
                .andExpect(status().isCreated());

        // a holiday with a substitute day cannot be cancelled first; a reason is required
        UUID labourDay = dateId(calendar, "2027-05-01", "PUBLIC");
        postJson(w.admin(), dates + "/" + labourDay + "/cancel", """
                {"version":%d,"reason":"Correction"}""".formatted(dateVersion(labourDay)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("holiday-has-substitutes"));
        postJson(w.admin(), dates + "/" + labourDay + "/cancel", "{\"version\":0}")
                .andExpect(status().is4xxClientError());

        // a template diff needs a template copy
        mvc.perform(get(BASE + "/holiday-calendars/" + calendar + "/template-diff").param("year", "2027")
                .with(as(w.admin()))).andExpect(jsonPath("$.code").value("not-a-template-copy"));
    }

    @Test
    void scopeAndTenantIsolation() throws Exception {
        World w = world("att-hol-scope");
        UUID otherLe = FIX.legalEntity(w.tenant(), "LE2");
        String created = body(postJson(w.admin(), "/holiday-calendars", "{\"code\":\"LE1\",\"nameVi\":\"Lịch LE1\"}"));
        UUID calendar = UUID.fromString(JsonPath.read(created, "$.id"));
        assign(w, calendar, null, "2027-01-01");

        // an admin scoped to the other legal entity can read but not change LE1's calendar
        UUID role = FIX.role(w.tenant(), "TA_LE2", false, AttendancePermissions.HOLIDAY_CALENDAR_VIEW,
                AttendancePermissions.HOLIDAY_CALENDAR_UPDATE);
        TestUser le2Admin = FIX.user(w.tenant(), "ta-le2-" + w.tenant().toString().substring(24) + "@test.local");
        FIX.assign(le2Admin, role, "LEGAL_ENTITY", List.of(otherLe), false, TestFixtures.EPOCH, null);
        mvc.perform(get(BASE + "/holiday-calendars/" + calendar).with(as(le2Admin))).andExpect(status().isOk());
        postJson(le2Admin, "/holiday-calendars/" + calendar + "/dates",
                "{\"holidayDate\":\"2027-05-01\",\"dayType\":\"PUBLIC\",\"nameVi\":\"Lao động\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("legal-entity-not-in-scope"));
        postJson(le2Admin, "/holiday-calendars/" + calendar + "/assignments", """
                {"legalEntityId":"%s","validFrom":"2027-01-01"}""".formatted(otherLe))
                .andExpect(status().isCreated());

        // no permission at all: 403 on every endpoint
        TestUser nobody = FIX.user(w.tenant(), "nobody-" + w.tenant().toString().substring(24) + "@test.local");
        mvc.perform(get(BASE + "/holiday-calendars").with(as(nobody))).andExpect(status().isForbidden());

        // another tenant sees nothing of this one
        World other = world("att-hol-other");
        mvc.perform(get(BASE + "/holiday-calendars").with(as(other.admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get(BASE + "/holiday-calendars/" + calendar).with(as(other.admin())))
                .andExpect(status().isNotFound());

        // a template can never be changed by a tenant (it is not a tenant calendar)
        UUID template = template("VN_IT_SCOPE_" + w.tenant().toString().substring(24));
        postJson(w.admin(), "/holiday-calendars/" + template + "/dates",
                "{\"holidayDate\":\"2027-05-01\",\"dayType\":\"PUBLIC\",\"nameVi\":\"Lao động\"}")
                .andExpect(status().isNotFound());
    }
}
