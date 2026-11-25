package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeCatalogues;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeResults;
import vn.yourhrm.attendance.internal.application.overtime.SpecialStatusProjection;
import vn.yourhrm.attendance.internal.application.schedule.AssignmentProjection;
import vn.yourhrm.attendance.internal.application.timesheet.ActualOvertimeSink;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.people.api.PersonEvents.SpecialStatusChanged;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Pre-approved overtime end to end (BR-ATT-040..046, 048, 049): consent, checks, Core approval by the line manager,
 * the cap ledger, paid results from daily processing's actual intervals, protected workers and the 300-hour regime.
 */
class OvertimeIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final String ANCHOR = "2027-01-04"; // a Monday

    @Autowired
    AssignmentProjection projection;

    @Autowired
    SpecialStatusProjection specialStatus;

    @Autowired
    OvertimeResults results;

    record World(UUID tenant, UUID le, TestFixtures.OrgUnit unit, TestUser admin, TestUser manager, TestUser owner) {
    }

    record Emp(UUID employment, UUID person) {
    }

    World world(String slug) throws Exception {
        UUID tenant = FIX.tenant(slug);
        UUID le = FIX.legalEntity(tenant, "LE1");
        TestFixtures.OrgUnit unit = FIX.orgUnit(tenant, "OPS", null, le, null, TestFixtures.EPOCH, null);
        String suffix = tenant.toString().substring(24);
        TestUser manager = FIX.user(tenant, "mgr-" + suffix + "@test.local");
        ADMIN.update("update core.org_unit_version set manager_user_id = ? where org_unit_id = ?", manager.userId(),
                unit.id());
        UUID role = FIX.role(tenant, "TA_ADMIN", false, AttendancePermissions.SCHEDULE_CONFIG_VIEW,
                AttendancePermissions.SCHEDULE_CONFIG_UPDATE, AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE, AttendancePermissions.HOLIDAY_CALENDAR_VIEW,
                AttendancePermissions.HOLIDAY_CALENDAR_UPDATE, AttendancePermissions.OVERTIME_VIEW,
                AttendancePermissions.OVERTIME_REQUEST, AttendancePermissions.OVERTIME_CONSENT,
                AttendancePermissions.OVERTIME_CAP_OVERRIDE);
        TestUser admin = FIX.user(tenant, "ta-" + suffix + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        for (Map.Entry<String, Object> p : Map.<String, Object>of(
                AttendanceLegalParameters.BREAK_DUE_AFTER_MIN, 360, AttendanceLegalParameters.BREAK_MIN_DAY, 30,
                AttendanceLegalParameters.BREAK_MIN_NIGHT, 45, AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY, 8,
                AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK, 48, AttendanceLegalParameters.NIGHT_START, "22:00",
                AttendanceLegalParameters.NIGHT_END, "06:00",
                AttendanceLegalParameters.OT_MAX_DAY_TOTAL_HOURS, 12,
                AttendanceLegalParameters.OT_MAX_DAY_OFF_HOURS, 12,
                AttendanceLegalParameters.OT_MAX_MONTH_HOURS, 40).entrySet()) {
            TestLegalParameters.define(tenant, p.getKey(), p.getValue());
        }
        TestLegalParameters.define(tenant, AttendanceLegalParameters.OT_MAX_YEAR_HOURS, 200);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.OT_MAX_YEAR_EXTENDED_HOURS, 300);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.OT_PROTECTED_RULES, Map.of("PREGNANCY", "BLOCK",
                "CHILD_UNDER_12M", "CONSENT", "MINOR", "BLOCK", "DISABILITY", "CONSENT", "ELDERLY_WORKER", "ALLOW",
                "HAZARDOUS_JOB", "ALLOW"));
        World w = new World(tenant, le, unit, admin, manager, FIX.owner(tenant));

        // Mon-Fri office 08:00-17:00 (8 h), Saturday off, Sunday weekly rest
        postJson(admin, "/shifts", """
                {"code":"OFFICE","nameVi":"Ca hành chính","startTime":"08:00","endTime":"17:00",
                 "breaks":[{"start":"12:00","end":"13:00"}]}""").andExpect(status().isCreated());
        String days = "[" + java.util.stream.IntStream.range(0, 7).mapToObj(i -> i < 5
                ? "{\"dayIndex\":%d,\"dayKind\":\"WORK\",\"shiftCode\":\"OFFICE\"}".formatted(i)
                : "{\"dayIndex\":%d,\"dayKind\":\"%s\"}".formatted(i, i == 5 ? "OFF" : "REST"))
                .collect(Collectors.joining(",")) + "]";
        UUID pattern = UUID.fromString(JsonPath.read(body(postJson(admin, "/schedule-patterns", """
                {"code":"P5","nameVi":"5 ngày","cycleLengthDays":7,"anchorDate":"%s","days":%s}"""
                .formatted(ANCHOR, days)).andExpect(status().isCreated())), "$.pattern.id"));
        postJson(admin, "/schedule-rules", """
                {"code":"LE","priority":10,"legalEntityId":"%s","patternId":"%s","validFrom":"2027-01-01"}"""
                .formatted(le, pattern)).andExpect(status().isCreated());
        return w;
    }

    Emp employee(World w) {
        UUID e = Ids.newId();
        UUID person = Ids.newId();
        TenantContext.runWith(ctx(w), () -> projection.apply(new LineVersion(Ids.newId(), Ids.newId(), e, person,
                "PRIMARY", w.le(), w.unit().id(), null, null, null, null, null, "STAFF", "FULL_TIME", BigDecimal.ONE,
                LocalDate.parse("2027-01-01"), null), false));
        ADMIN.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       status, worker_category_code, source_event_id, source_version)
                values (?, ?, ?, ?, date '2027-01-01', 'ACTIVE', 'EMPLOYEE', ?, 1)""",
                w.tenant(), e, person, w.le(), Ids.newId());
        return new Emp(e, person);
    }

    TenantContext ctx(World w) {
        return new TenantContext(w.tenant(), w.admin().userId(), w.admin().identityId().toString(), "it", "vi");
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    ResultActions request(World w, UUID employment, String date, String start, String end, String extra)
            throws Exception {
        return postJson(w.admin(), "/overtime-requests", """
                {"employmentIds":["%s"],"workDate":"%s","startTime":"%s","endTime":"%s","reason":"Đơn hàng gấp"%s}"""
                .formatted(employment, date, start, end, extra.isEmpty() ? "" : "," + extra));
    }

    static final String PAPER = "\"consent\":true,\"consentMethod\":\"PAPER\",\"submit\":true";

    UUID requestId(ResultActions r) throws Exception {
        return UUID.fromString(JsonPath.read(body(r), "$[0].request.id"));
    }

    void decide(TestUser approver, UUID subjectId, String action) throws Exception {
        UUID task = ADMIN.queryForObject("""
                select t.id from core.workflow_task t
                  join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                 where i.subject_id = ? and t.status = 'PENDING'""", UUID.class, subjectId);
        mvc.perform(post("/api/v1/core/workflow/tasks/" + task + "/actions").with(as(approver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"comment\":\"ok\"}"))
                .andExpect(status().isNoContent());
    }

    static String requestStatus(UUID request) {
        return ADMIN.queryForObject("select status from att.overtime_request where id = ?", String.class, request);
    }

    static Map<String, Object> ledger(UUID employment, String kind) {
        return ledger(employment, kind, "MONTH".equals(kind) ? "2027-09-01" : "2027-01-01");
    }

    static Map<String, Object> ledger(UUID employment, String kind, String periodStart) {
        return ADMIN.queryForMap("""
                select cap_min, approved_min, pending_min, paid_min, override_min, regime_code
                  from att.ot_cap_ledger where employment_id = ? and period_kind = ? and period_start = ?::date""",
                employment, kind, periodStart);
    }

    static String results(UUID employment) {
        return ADMIN.queryForList("""
                select ot_category, approved_minutes, actual_minutes, paid_minutes, preceded_by_day_ot
                  from att.overtime_result where employment_id = ? order by work_date, ot_category""", employment)
                .stream().map(r -> r.get("ot_category") + ":" + r.get("approved_minutes") + "/"
                        + r.get("actual_minutes") + "/" + r.get("paid_minutes")
                        + (Boolean.TRUE.equals(r.get("preceded_by_day_ot")) ? "+" : ""))
                .collect(Collectors.joining(" "));
    }

    void setting(World w, String key, String json) throws Exception {
        mvc.perform(put("/api/v1/core/settings/" + key).with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isOk());
    }

    String le(World w, String value) {
        return "{\"level\":\"LEGAL_ENTITY\",\"legalEntityId\":\"%s\",\"value\":%s,\"validFrom\":\"2027-01-01\"}"
                .formatted(w.le(), value);
    }

    // ============================================================================================ tests

    @Test
    void anApprovedRequestIsPaidByTheActualMinutesPerCategory() throws Exception {
        World w = world("att-ot-flow");
        Emp e = employee(w);

        // Wed 1 Sep 2027, 19:00-23:00 after the shift (8 h + 4 h = the 12 h limit): 180 min day + 60 min night OT
        ResultActions created = request(w, e.employment(), "2027-09-01", "19:00", "23:00", PAPER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].submitted").value(true))
                .andExpect(jsonPath("$[0].request.status").value("PENDING"))
                .andExpect(jsonPath("$[0].request.plannedMinutes").value(240))
                .andExpect(jsonPath("$[0].request.categories[0].category").value("OT-WD"))
                .andExpect(jsonPath("$[0].request.categories[0].minutes").value(180))
                .andExpect(jsonPath("$[0].request.categories[1].category").value("OT-WD-N"))
                .andExpect(jsonPath("$[0].request.categories[1].precededByDayOt").value(true));
        UUID id = requestId(created);
        assertThat(ledger(e.employment(), "MONTH")).containsEntry("pending_min", 240).containsEntry("cap_min", 2400);
        assertThat(ledger(e.employment(), "YEAR")).containsEntry("pending_min", 240).containsEntry("cap_min", 12000)
                .containsEntry("regime_code", "STANDARD_200");
        assertThat(ADMIN.queryForObject("""
                select process_type_code from core.workflow_instance where subject_id = ?""", String.class, id))
                .isEqualTo(OvertimeCatalogues.PROCESS_OT_REQUEST);

        // the line manager approves in Core: minutes move to approved, results show approved minutes (no actual yet)
        decide(w.manager(), id, "APPROVE");
        assertThat(requestStatus(id)).isEqualTo("APPROVED");
        assertThat(ledger(e.employment(), "MONTH")).containsEntry("pending_min", 0).containsEntry("approved_min", 240);
        assertThat(results(e.employment())).isEqualTo("OT-WD:180/0/0 OT-WD-N:60/0/0+");

        // daily processing pushes the actual presence after the shift: 17:30-22:30 -> paid = min(approved, actual)
        TenantContext.runWith(ctx(w), () -> results.recordActual(e.employment(), LocalDate.parse("2027-09-01"),
                List.of(new ActualOvertimeSink.Interval(Instant.parse("2027-09-01T10:30:00Z"),
                        Instant.parse("2027-09-01T15:30:00Z")))));
        assertThat(results(e.employment())).isEqualTo("OT-WD:180/270/180 OT-WD-N:60/30/30+");
        assertThat(ledger(e.employment(), "MONTH")).containsEntry("paid_min", 210);
        // idempotent: the same push changes nothing
        TenantContext.runWith(ctx(w), () -> results.recordActual(e.employment(), LocalDate.parse("2027-09-01"),
                List.of(new ActualOvertimeSink.Interval(Instant.parse("2027-09-01T10:30:00Z"),
                        Instant.parse("2027-09-01T15:30:00Z")))));
        assertThat(ADMIN.queryForObject("select max(calc_version) from att.overtime_result where employment_id = ?",
                Integer.class, e.employment())).isEqualTo(2);

        mvc.perform(get(BASE + "/employments/" + e.employment() + "/overtime-results").with(as(w.admin()))
                        .param("from", "2027-09-01").param("to", "2027-09-30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].otCategory").value("OT-WD-N"))
                .andExpect(jsonPath("$[1].precededByDayOt").value(true));
        mvc.perform(get(BASE + "/employments/" + e.employment() + "/overtime-caps").with(as(w.admin()))
                        .param("date", "2027-09-15"))
                .andExpect(jsonPath("$[0].kind").value("MONTH"))
                .andExpect(jsonPath("$[0].approvedMin").value(240))
                .andExpect(jsonPath("$[0].level").value("OK"))
                .andExpect(jsonPath("$[1].kind").value("YEAR"))
                .andExpect(jsonPath("$[1].capMin").value(12000));

        // cancelling approved overtime needs a reason, releases the minutes and clears the approved results
        postJson(w.admin(), "/overtime-requests/" + id + "/cancel", "{}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("reason-required"));
        postJson(w.admin(), "/overtime-requests/" + id + "/cancel", "{\"reason\":\"Đơn hàng hủy\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(ledger(e.employment(), "MONTH")).containsEntry("approved_min", 0).containsEntry("paid_min", 0);
        assertThat(results(e.employment())).isEqualTo("OT-WD:0/270/0 OT-WD-N:0/30/0+");
    }

    @Test
    void checksBlockOrWarnAndAManagerRequestsForAGroup() throws Exception {
        World w = world("att-ot-checks");
        Emp a = employee(w);
        Emp b = employee(w);

        // without consent the request stays DRAFT; the employee's ESS consent then allows the submit
        UUID draft = requestId(request(w, a.employment(), "2027-09-01", "17:00", "19:00", "\"submit\":true")
                .andExpect(jsonPath("$[0].submitted").value(false))
                .andExpect(jsonPath("$[0].findings[0].code").value("consent-required"))
                .andExpect(jsonPath("$[0].request.status").value("DRAFT")));
        postJson(w.admin(), "/overtime-requests/" + draft + "/consent", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.consentMethod").value("ESS"));
        postJson(w.admin(), "/overtime-requests/" + draft + "/submit", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("PENDING"));

        // overlap with the shift, with another request, and an invalid consent method
        request(w, a.employment(), "2027-09-02", "16:00", "18:00", PAPER)
                .andExpect(jsonPath("$[0].findings[*].code").value(org.hamcrest.Matchers.hasItem("ot-overlaps-shift")));
        request(w, a.employment(), "2027-09-01", "18:00", "20:00", PAPER)
                .andExpect(jsonPath("$[0].findings[*].code").value(org.hamcrest.Matchers.hasItem("ot-request-overlap")));
        request(w, a.employment(), "2027-09-03", "17:00", "18:00", "\"consent\":true,\"consentMethod\":\"SMS\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-consent-method"));

        // daily limit: 8 h normal + 5 h > 12 h -> BLOCK by default, WARN when the legal entity says so
        UUID longDay = requestId(request(w, b.employment(), "2027-09-06", "17:00", "22:00",
                "\"consent\":true,\"consentMethod\":\"PAPER\""));
        postJson(w.admin(), "/overtime-requests/" + longDay + "/submit", "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ot-cap-day-exceeded"))
                .andExpect(jsonPath("$.findings[0].data.capMin").value(720));
        setting(w, OvertimeCatalogues.SETTING_DAILY_CAP_MODE, le(w, "\"WARN\""));
        postJson(w.admin(), "/overtime-requests/" + longDay + "/submit", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.findings[0].severity").value("WARN"));

        // a rest day and an OFF Saturday: OT-RD on Sunday, OT-WD on the Saturday of a 5-day week
        request(w, b.employment(), "2027-09-05", "08:00", "10:00", PAPER)
                .andExpect(jsonPath("$[0].request.categories[0].category").value("OT-RD"));
        request(w, b.employment(), "2027-09-04", "08:00", "10:00", PAPER)
                .andExpect(jsonPath("$[0].request.categories[0].category").value("OT-WD"));

        // monthly cap: 9 h pending of 40 h is fine; with a 15 h cap 14 h warns (>= 80 %), 17 h blocks unless overridden
        String month = body(request(w, b.employment(), "2027-09-11", "08:00", "11:00", PAPER)
                .andExpect(jsonPath("$[0].submitted").value(true)));
        assertThat(month).doesNotContain("ot-cap-month-warning");
        TestLegalParameters.define(w.tenant(), AttendanceLegalParameters.OT_MAX_MONTH_HOURS, 15);
        request(w, b.employment(), "2027-09-12", "08:00", "10:00", PAPER)
                .andExpect(jsonPath("$[0].submitted").value(true))
                .andExpect(jsonPath("$[0].findings[*].code")
                        .value(org.hamcrest.Matchers.hasItem("ot-cap-month-warning")));
        // the threshold is notified after commit to the cap-override holders (BR-ATT-082), once a day
        assertThat(ADMIN.queryForObject("""
                select count(*) from core.notification where tenant_id = ? and recipient_user_id = ?
                   and event_code = 'att.OvertimeMonthCapThreshold'""", Integer.class, w.tenant(),
                w.admin().userId())).isEqualTo(1);
        UUID over = requestId(request(w, b.employment(), "2027-09-18", "08:00", "11:00",
                "\"consent\":true,\"consentMethod\":\"PAPER\""));
        postJson(w.admin(), "/overtime-requests/" + over + "/submit", "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ot-cap-month-exceeded"));
        postJson(w.admin(), "/overtime-requests/" + over + "/submit",
                "{\"capOverrideReason\":\"Kiểm kê cuối quý\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.request.capOverride").value(true));
        assertThat(ledger(b.employment(), "MONTH")).containsEntry("pending_min", 1020).containsEntry("cap_min", 900);
        decide(w.manager(), over, "APPROVE");
        assertThat(ledger(b.employment(), "MONTH")).containsEntry("override_min", 120)
                .containsEntry("approved_min", 180).containsEntry("pending_min", 840);

        // a manager's request for two employees shares one group id and starts one approval each
        String group = body(postJson(w.admin(), "/overtime-requests", """
                {"employmentIds":["%s","%s"],"workDate":"2027-10-04","startTime":"18:00","endTime":"20:00",
                 "consent":true,"consentMethod":"MANAGER_RECORDED","submit":true}"""
                .formatted(a.employment(), b.employment())).andExpect(status().isCreated()));
        List<String> groups = JsonPath.read(group, "$[*].request.requestGroupId");
        assertThat(groups).hasSize(2).doesNotContainNull();
        assertThat(groups.get(0)).isEqualTo(groups.get(1));
        assertThat(ADMIN.queryForObject("""
                select count(*) from core.workflow_instance where process_type_code = ? and subject_id in (
                       select id from att.overtime_request where request_group_id = ?::uuid)""", Integer.class,
                OvertimeCatalogues.PROCESS_OT_REQUEST, groups.getFirst())).isEqualTo(2);

        // a rejected request releases its pending minutes
        UUID rejected = UUID.fromString(JsonPath.read(group, "$[0].request.id"));
        assertThat(ledger(a.employment(), "MONTH", "2027-10-01")).containsEntry("pending_min", 120);
        decide(w.manager(), rejected, "REJECT");
        assertThat(requestStatus(rejected)).isEqualTo("REJECTED");
        assertThat(ledger(a.employment(), "MONTH", "2027-10-01")).containsEntry("pending_min", 0);
    }

    @Test
    void protectedWorkersAndTheExtendedAnnualRegime() throws Exception {
        World w = world("att-ot-protected");
        Emp pregnant = employee(w);
        Emp parent = employee(w);
        Emp later = employee(w);
        TenantContext.runWith(ctx(w), () -> {
            specialStatus.receive(new SpecialStatusChanged(w.tenant(), pregnant.person(), null, Ids.newId(),
                    "PREGNANCY", LocalDate.parse("2027-06-01"), LocalDate.parse("2028-01-01"), "CREATED"));
            specialStatus.receive(new SpecialStatusChanged(w.tenant(), parent.person(), null, Ids.newId(),
                    "CHILD_UNDER_12M", LocalDate.parse("2027-03-01"), LocalDate.parse("2028-03-01"), "CREATED"));
            // a membership type is not projected
            specialStatus.receive(new SpecialStatusChanged(w.tenant(), parent.person(), null, Ids.newId(),
                    "UNION_MEMBER", LocalDate.parse("2027-01-01"), null, "CREATED"));
        });
        assertThat(ADMIN.queryForObject("select count(*) from att.ext_hr_special_status where tenant_id = ?",
                Integer.class, w.tenant())).isEqualTo(2);

        // BLOCK: never allowed, and the response never names the protected-worker type
        String blocked = body(request(w, pregnant.employment(), "2027-09-01", "17:30", "19:30", PAPER)
                .andExpect(jsonPath("$[0].submitted").value(false))
                .andExpect(jsonPath("$[0].findings[*].code").value(org.hamcrest.Matchers.hasItem("protected-worker"))));
        assertThat(blocked).doesNotContain("PREGNANCY");
        // the same work date before the status started is allowed
        request(w, pregnant.employment(), "2027-05-03", "17:30", "19:30", PAPER)
                .andExpect(jsonPath("$[0].submitted").value(true));

        // CONSENT: the employee's own consent (ESS / paper) is required, a manager-recorded one is not enough
        request(w, parent.employment(), "2027-09-01", "17:30", "19:30",
                "\"consent\":true,\"consentMethod\":\"MANAGER_RECORDED\",\"submit\":true")
                .andExpect(jsonPath("$[0].findings[*].code")
                        .value(org.hamcrest.Matchers.hasItem("protected-worker-own-consent-required")));
        request(w, parent.employment(), "2027-09-02", "17:30", "19:30", PAPER)
                .andExpect(jsonPath("$[0].submitted").value(true))
                .andExpect(jsonPath("$[0].request.protectedWorker").value(true));

        // a status recorded after the submit turns the approval into a rejection (as of the work date)
        UUID pending = requestId(request(w, later.employment(), "2027-10-04", "17:30", "19:30", PAPER));
        TenantContext.runWith(ctx(w), () -> specialStatus.receive(new SpecialStatusChanged(w.tenant(),
                later.person(), null, Ids.newId(), "PREGNANCY", LocalDate.parse("2027-09-15"), null, "CREATED")));
        decide(w.manager(), pending, "APPROVE");
        assertThat(requestStatus(pending)).isEqualTo("REJECTED");
        assertThat(ledger(later.employment(), "MONTH", "2027-10-01")).containsEntry("pending_min", 0)
                .containsEntry("approved_min", 0);

        // the 300-hour regime applies only once the notice to the labour authority is recorded (BR-ATT-049)
        String regime = BASE + "/legal-entities/" + w.le() + "/overtime-regime";
        mvc.perform(get(regime).with(as(w.admin())).param("date", "2027-09-01"))
                .andExpect(jsonPath("$.regimeApplied").value("STANDARD_200"))
                .andExpect(jsonPath("$.yearCapMin").value(12000));
        setting(w, OvertimeCatalogues.SETTING_ANNUAL_REGIME, le(w, "\"EXTENDED_300\""));
        mvc.perform(get(regime).with(as(w.admin())).param("date", "2027-09-01"))
                .andExpect(jsonPath("$.regimeRequested").value("EXTENDED_300"))
                .andExpect(jsonPath("$.regimeApplied").value("STANDARD_200"))
                .andExpect(jsonPath("$.noticeRequired").value(true));
        request(w, parent.employment(), "2027-09-03", "17:30", "19:30", PAPER)
                .andExpect(jsonPath("$[0].findings[*].code")
                        .value(org.hamcrest.Matchers.hasItem("ot-300h-notice-missing")));
        setting(w, OvertimeCatalogues.SETTING_REGIME_NOTICE_REF, le(w, "\"123/TB-CTY\""));
        mvc.perform(get(regime).with(as(w.admin())).param("date", "2027-09-01"))
                .andExpect(jsonPath("$.regimeApplied").value("EXTENDED_300"))
                .andExpect(jsonPath("$.noticeRequired").value(false))
                .andExpect(jsonPath("$.yearCapMin").value(18000));
        request(w, parent.employment(), "2027-09-06", "17:30", "19:30", PAPER)
                .andExpect(jsonPath("$[0].submitted").value(true));
        assertThat(ledger(parent.employment(), "YEAR")).containsEntry("regime_code", "EXTENDED_300")
                .containsEntry("cap_min", 18000);

        // a missing statutory value refuses the check (fail closed)
        TestLegalParameters.undefine(w.tenant(), AttendanceLegalParameters.OT_MAX_MONTH_HOURS);
        request(w, parent.employment(), "2027-09-07", "17:30", "19:30", PAPER)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("legal-parameter-missing"));
    }

    @Test
    void scopeAndPermissions() throws Exception {
        World w = world("att-ot-scope");
        Emp e = employee(w);
        UUID otherLe = FIX.legalEntity(w.tenant(), "LE2");
        UUID role = FIX.role(w.tenant(), "TA_LE2", false, AttendancePermissions.OVERTIME_VIEW,
                AttendancePermissions.OVERTIME_REQUEST);
        TestUser le2 = FIX.user(w.tenant(), "le2-" + w.tenant().toString().substring(24) + "@test.local");
        FIX.assign(le2, role, "LEGAL_ENTITY", List.of(otherLe), false, TestFixtures.EPOCH, null);
        postJson(le2, "/overtime-requests", """
                {"employmentIds":["%s"],"workDate":"2027-09-01","startTime":"17:30","endTime":"19:30"}"""
                .formatted(e.employment())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("employment-not-in-scope"));
        postJson(le2, "/overtime-requests", """
                {"employmentIds":["%s"],"workDate":"2027-09-01","startTime":"17:30","endTime":"19:30"}"""
                .formatted(Ids.newId())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/employments/" + e.employment() + "/overtime-caps").with(as(le2))
                .param("date", "2027-09-01")).andExpect(status().isForbidden());
        TestUser nobody = FIX.user(w.tenant(), "nobody-" + w.tenant().toString().substring(24) + "@test.local");
        postJson(nobody, "/overtime-requests", """
                {"employmentIds":["%s"],"workDate":"2027-09-01","startTime":"17:30","endTime":"19:30"}"""
                .formatted(e.employment())).andExpect(status().isForbidden());
    }
}
