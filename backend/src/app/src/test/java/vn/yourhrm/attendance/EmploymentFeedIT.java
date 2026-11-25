package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.people.api.AssignmentEvents.AssignmentChanged;
import vn.yourhrm.people.api.EmploymentEvents.EmployeeHired;
import vn.yourhrm.people.api.EmploymentEvents.EmploymentChanged;
import vn.yourhrm.people.api.EmploymentEvents.HireCancelled;
import vn.yourhrm.people.api.SeparationEvents.TerminationCancelled;
import vn.yourhrm.people.api.SeparationEvents.TerminationScheduled;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * People events into T&amp;A through the inbox (dedupe, dead letter), the employment projection
 * ({@code att.ext_hr_employment}) clipping schedules to the employment, and {@code att.ScheduleAssignmentChanged}.
 * Events are published in-process outside a transaction, as People's after-commit delivery does. D3: the employee
 * category / working regime come with {@code AssignmentChanged} v2 and, dated, with {@code EmploymentChanged}.
 */
class EmploymentFeedIT extends IntegrationTest {

    @Autowired
    ApplicationEventPublisher events;

    record World(UUID tenant, TestUser admin, UUID le, UUID pattern) {
    }

    /** A tenant with a Mon-Fri pattern and a rule for employee category WORKER only. */
    World world(String slug) {
        UUID tenant = FIX.tenant(slug);
        UUID role = FIX.role(tenant, "TA_ADMIN", false, AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE, AttendancePermissions.INTEGRATION_VIEW);
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        UUID le = FIX.legalEntity(tenant, "LE1");
        UUID shift = Ids.newId();
        ADMIN.update("""
                insert into att.shift_definition (tenant_id, id, code, name_vi, start_time, end_time, std_hours)
                values (?, ?, 'OFFICE', 'Hành chính', time '08:00', time '17:00', 8)""", tenant, shift);
        UUID pattern = Ids.newId();
        ADMIN.update("""
                insert into att.schedule_pattern (tenant_id, id, code, name_vi, anchor_date, weekly_rest_days)
                values (?, ?, 'P5', 'Tuần 5 ngày', date '2027-01-04', '{6,7}')""", tenant, pattern);
        for (int i = 0; i < 7; i++) {
            ADMIN.update("""
                    insert into att.schedule_pattern_day (tenant_id, pattern_id, day_index, day_kind, shift_id)
                    values (?, ?, ?, ?, ?)""", tenant, pattern, i, i < 5 ? "WORK" : "REST", i < 5 ? shift : null);
        }
        ADMIN.update("""
                insert into att.schedule_assignment_rule (tenant_id, code, priority, legal_entity_id,
                       employee_category_code, pattern_id, valid_from)
                values (?, 'WORKERS', 10, ?, 'WORKER', ?, date '2027-01-01')""", tenant, le, pattern);
        return new World(tenant, admin, le, pattern);
    }

    void publish(World w, Object event) {
        TenantContext ctx = new TenantContext(w.tenant(), w.admin().userId(), w.admin().identityId().toString(),
                "it", "vi");
        TenantContext.runWith(ctx, () -> events.publishEvent(event));
    }

    record Emp(UUID employment, UUID person, UUID key) {
    }

    Emp hire(World w, String hireDate, String category) {
        Emp e = new Emp(Ids.newId(), Ids.newId(), Ids.newId());
        LocalDate hd = LocalDate.parse(hireDate);
        // People publishes the first assignment line and the hire in the same transaction
        publish(w, new AssignmentChanged(w.tenant(), w.le(), e.person(), e.employment(), hd, "HIRE", Ids.newId(),
                e.key(), "PRIMARY", "HIRE", null, Ids.newId(), null, null, null, null, null, null, null, List.of(),
                BigDecimal.ONE, hd, null, null, null, null, false, null, false, category, "FULL_TIME"));
        publish(w, hired(w, e, hd, category, false));
        return e;
    }

    static EmployeeHired hired(World w, Emp e, LocalDate hireDate, String category, boolean preview) {
        return new EmployeeHired(w.tenant(), w.le(), e.person(), e.employment(), hireDate, preview, "NEW", "NV001",
                "Nguyễn An", hireDate, hireDate, hireDate, "EMPLOYEE", category, "FULL_TIME", null, null, null, null,
                null, null, null, null, List.of());
    }

    static TerminationScheduled termination(World w, Emp e, LocalDate date) {
        return new TerminationScheduled(w.tenant(), w.le(), e.person(), e.employment(), date, Ids.newId(),
                Ids.newId(), "QD-1", "RESIGNATION", "EMPLOYEE", null, null, null, date.minusDays(30), date, date, 30,
                "CALENDAR", 0, List.of(), false, date, null, null, null, false, false);
    }

    String schedules(UUID employment) {
        return ADMIN.queryForList("""
                select a.source, a.valid_from::text as f, a.valid_to::text as t from att.schedule_assignment a
                 where a.employment_id = ? and a.status = 'ACTIVE' order by a.valid_from""", employment).stream()
                .map(r -> r.get("source") + "/" + r.get("f") + ".." + r.get("t")).collect(Collectors.joining(" "));
    }

    Map<String, Object> employmentRow(UUID employment) {
        return ADMIN.queryForMap("""
                select status, hire_date::text as hire, termination_date::text as term,
                       last_working_date::text as lwd, employee_category_code as cat, source_version
                  from att.ext_hr_employment where employment_id = ?""", employment);
    }

    List<Map<String, Object>> scheduleEvents(UUID employment) {
        return ADMIN.queryForList("""
                select is_retro, payload->>'cause' as cause, payload->>'rangeFrom' as range_from,
                       payload->>'rangeTo' as range_to, (payload->>'scheduled')::boolean as scheduled
                  from att.outbox_event where event_type = 'att.ScheduleAssignmentChanged' and aggregate_id = ?
                 order by created_at, id""", employment);
    }

    // ============================================================================================ tests

    @Test
    void aHireFeedsTheEmploymentAndItsCategoryDrivesTheRules() {
        World w = world("att-feed-hire");
        Emp worker = hire(w, "2027-01-01", "WORKER");
        Emp staff = hire(w, "2027-01-01", "STAFF");

        assertThat(employmentRow(worker.employment())).containsEntry("status", "ACTIVE")
                .containsEntry("hire", "2027-01-01").containsEntry("cat", "WORKER");
        assertThat(ADMIN.queryForObject("select employee_category_code from att.ext_hr_assignment"
                + " where employment_id = ?", String.class, worker.employment())).isEqualTo("WORKER");
        assertThat(schedules(worker.employment())).isEqualTo("RULE/2027-01-01..null");
        assertThat(schedules(staff.employment())).isEmpty();

        // gap 2: the schedule change is published for daily processing; since AssignmentChanged v2 (D3) the first
        // line already carries the category, so the assignment event creates the schedule
        assertThat(scheduleEvents(worker.employment())).singleElement().satisfies(ev -> {
            assertThat(ev.get("cause")).isEqualTo("PEOPLE_ASSIGNMENT");
            assertThat(ev.get("range_from")).isEqualTo("2027-01-01");
            assertThat(ev.get("range_to")).isNull();
            assertThat(ev.get("scheduled")).isEqualTo(true);
            assertThat(ev.get("is_retro")).isEqualTo(false);
        });
        assertThat(scheduleEvents(staff.employment())).isEmpty();
    }

    @Test
    void redeliveredEventsAreDuplicatesAndFailuresAreDeadLetters() throws Exception {
        World w = world("att-feed-inbox");
        Emp e = hire(w, "2027-01-01", "WORKER");
        int version = ((Number) employmentRow(e.employment()).get("source_version")).intValue();

        publish(w, hired(w, e, LocalDate.parse("2027-01-01"), "WORKER", false));
        assertThat(((Number) employmentRow(e.employment()).get("source_version")).intValue()).isEqualTo(version);
        assertThat(ADMIN.queryForList("""
                select status, attempts from att.inbox_message where tenant_id = ? and event_type = 'people.EmployeeHired'""",
                w.tenant())).singleElement().satisfies(r -> {
                    assertThat(r.get("status")).isEqualTo("PROCESSED");
                    assertThat(r.get("attempts")).isEqualTo(1);
                });

        // a hire without a hire date cannot be stored: FAILED with its error, nothing half-applied
        Emp broken = new Emp(Ids.newId(), Ids.newId(), Ids.newId());
        publish(w, hired(w, broken, null, "WORKER", false));
        assertThat(ADMIN.queryForObject("select count(*) from att.ext_hr_employment where employment_id = ?",
                Integer.class, broken.employment())).isZero();
        mvc.perform(get("/api/v1/attendance/integration/inbox").param("status", "failed").with(as(w.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].consumer").value("att.EmploymentProjection"))
                .andExpect(jsonPath("$[0].eventType").value("people.EmployeeHired"))
                .andExpect(jsonPath("$[0].lastError").isNotEmpty());
    }

    @Test
    void terminationEndsTheScheduleAndItsCancellationRestoresIt() throws Exception {
        World w = world("att-feed-term");
        Emp e = hire(w, "2027-01-01", "WORKER");
        // a manual override that runs past the termination date stays as entered (only rules are clipped)
        mvc.perform(post("/api/v1/attendance/employments/" + e.employment() + "/schedule-assignments")
                        .with(as(w.admin())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignmentKey":"%s","patternId":"%s","validFrom":"2027-03-01","validTo":"2027-04-01"}"""
                                .formatted(e.key(), w.pattern())))
                .andExpect(status().isCreated());
        UUID period = Ids.newId();
        ADMIN.update("""
                insert into att.attendance_period (tenant_id, id, legal_entity_id, pay_group_id, period_start,
                       period_end, status, current_version, locked_version)
                values (?, ?, ?, ?, date '2027-07-01', date '2027-07-31', 'LOCKED', 1, 1)""",
                w.tenant(), period, w.le(), Ids.newId());

        publish(w, termination(w, e, LocalDate.parse("2027-06-30")));
        assertThat(employmentRow(e.employment())).containsEntry("term", "2027-06-30").containsEntry("lwd", "2027-06-30");
        assertThat(schedules(e.employment())).isEqualTo(
                "RULE/2027-01-01..2027-03-01 MANUAL/2027-03-01..2027-04-01 RULE/2027-04-01..2027-07-01");
        List<Map<String, Object>> evs = scheduleEvents(e.employment());
        assertThat(evs.getLast()).containsEntry("cause", "EMPLOYMENT").containsEntry("is_retro", true);

        publish(w, new TerminationCancelled(w.tenant(), w.le(), e.person(), e.employment(),
                LocalDate.parse("2027-06-30"), Ids.newId(), Ids.newId(), "ACTIVE", false, "withdrawn"));
        assertThat(employmentRow(e.employment())).containsEntry("status", "ACTIVE").containsEntry("term", null);
        assertThat(schedules(e.employment())).isEqualTo(
                "RULE/2027-01-01..2027-03-01 MANUAL/2027-03-01..2027-04-01 RULE/2027-04-01..null");
    }

    @Test
    void aCancelledHireDropsEverySchedule() throws Exception {
        World w = world("att-feed-cancel");
        Emp e = new Emp(Ids.newId(), Ids.newId(), Ids.newId());
        LocalDate hd = LocalDate.parse("2027-02-01");
        publish(w, new AssignmentChanged(w.tenant(), w.le(), e.person(), e.employment(), hd, "HIRE", Ids.newId(),
                e.key(), "PRIMARY", "HIRE", null, Ids.newId(), null, null, null, null, null, null, null, List.of(),
                BigDecimal.ONE, hd, null, null, null, null, false, null, false, "WORKER", "FULL_TIME"));
        publish(w, hired(w, e, hd, "WORKER", true));
        assertThat(employmentRow(e.employment())).containsEntry("status", "PRE_HIRE");
        assertThat(schedules(e.employment())).isEqualTo("RULE/2027-02-01..null");
        mvc.perform(post("/api/v1/attendance/employments/" + e.employment() + "/schedule-assignments")
                        .with(as(w.admin())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignmentKey":"%s","patternId":"%s","validFrom":"2027-02-01","validTo":"2027-03-01"}"""
                                .formatted(e.key(), w.pattern())))
                .andExpect(status().isCreated());

        publish(w, new HireCancelled(w.tenant(), w.le(), e.person(), e.employment(), hd, "NV001", hd, "NO_SHOW",
                null, "no show", true));
        assertThat(employmentRow(e.employment())).containsEntry("status", "CANCELLED");
        assertThat(schedules(e.employment())).isEmpty();
        assertThat(scheduleEvents(e.employment()).getLast()).containsEntry("scheduled", false);
        assertThat(ADMIN.queryForObject("select count(*) from att.schedule_assignment where employment_id = ?"
                + " and status = 'CANCELLED'", Integer.class, e.employment())).isGreaterThanOrEqualTo(2);
        // a late preview event does not revive it
        publish(w, hired(w, e, hd, "WORKER", false));
        assertThat(employmentRow(e.employment())).containsEntry("status", "CANCELLED");
    }

    static EmploymentChanged changed(World w, Emp e, UUID version, String category, String from, String to) {
        LocalDate f = LocalDate.parse(from);
        return new EmploymentChanged(w.tenant(), w.le(), e.person(), e.employment(), f, version, category,
                "FULL_TIME", null, null, f, to == null ? null : LocalDate.parse(to), false, null);
    }

    List<String> classificationRows(UUID employment) {
        return ADMIN.queryForList("""
                select employee_category_code as c, valid_from::text as f, valid_to::text as t
                  from att.ext_hr_employment_classification where employment_id = ? order by valid_from""",
                employment).stream().map(r -> r.get("c") + "/" + r.get("f") + ".." + r.get("t")).toList();
    }

    @Test
    void anAssignmentChangedV2CarriesTheCategoryWithoutAPeopleLookup() {
        World w = world("att-feed-v2");
        Emp e = new Emp(Ids.newId(), Ids.newId(), Ids.newId());
        LocalDate hd = LocalDate.parse("2027-01-01");
        // no EmployeeHired and no People directory row: the event alone carries the category
        publish(w, new AssignmentChanged(w.tenant(), w.le(), e.person(), e.employment(), hd, "HIRE", Ids.newId(),
                e.key(), "PRIMARY", "HIRE", null, Ids.newId(), null, null, null, null, null, null, null, List.of(),
                BigDecimal.ONE, hd, null, null, null, null, false, null, false, "WORKER", "FULL_TIME"));
        assertThat(ADMIN.queryForObject("select employee_category_code from att.ext_hr_assignment"
                + " where employment_id = ?", String.class, e.employment())).isEqualTo("WORKER");
        assertThat(schedules(e.employment())).isEqualTo("RULE/2027-01-01..null");
    }

    @Test
    void datedCategoryChangesCutTheRuleSchedulesAndRedeliveryChangesNothing() {
        World w = world("att-feed-class");
        Emp e = hire(w, "2027-01-01", "WORKER");
        assertThat(classificationRows(e.employment())).containsExactly("WORKER/2027-01-01..null");
        assertThat(schedules(e.employment())).isEqualTo("RULE/2027-01-01..null");

        // STAFF from 1 March: the WORKERS rule stops there
        publish(w, changed(w, e, Ids.newId(), "STAFF", "2027-03-01", null));
        assertThat(classificationRows(e.employment()))
                .containsExactly("WORKER/2027-01-01..2027-03-01", "STAFF/2027-03-01..null");
        assertThat(schedules(e.employment())).isEqualTo("RULE/2027-01-01..2027-03-01");

        // WORKER again from 1 May (a later version): the rule applies again from that day
        publish(w, changed(w, e, Ids.newId(), "WORKER", "2027-05-01", null));
        assertThat(classificationRows(e.employment())).containsExactly("WORKER/2027-01-01..2027-03-01",
                "STAFF/2027-03-01..2027-05-01", "WORKER/2027-05-01..null");
        assertThat(schedules(e.employment())).isEqualTo("RULE/2027-01-01..2027-03-01 RULE/2027-05-01..null");

        // a back-dated correction inside the STAFF months, bounded by People's next version
        EmploymentChanged correction = changed(w, e, Ids.newId(), "WORKER", "2027-04-01", "2027-05-01");
        publish(w, correction);
        assertThat(schedules(e.employment())).isEqualTo(
                "RULE/2027-01-01..2027-03-01 RULE/2027-04-01..null");

        // the same event again is a duplicate in the inbox and changes nothing
        String before = schedules(e.employment());
        publish(w, correction);
        assertThat(schedules(e.employment())).isEqualTo(before);
        assertThat(ADMIN.queryForObject("select count(*) from att.inbox_message where tenant_id = ?"
                + " and event_type = 'people.EmploymentChanged' and status = 'PROCESSED'", Integer.class,
                w.tenant())).isEqualTo(3);   // the redelivery left no new inbox row
    }
}
