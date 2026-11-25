package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Cross-module (People → T&amp;A, BR-ATT-015): an approved hire publishes {@code people.AssignmentChanged}; T&amp;A
 * feeds its assignment projection from it and gives the new line its rule-based schedule.
 */
class AttendanceScheduleFeedIT extends EmploymentTestSupport {

    /** A Mon-Fri pattern and a rule assigning it to every line of {@code le}, written as the T&amp;A admin would. */
    static UUID schedulingRule(UUID tenant, UUID le) {
        UUID shift = Ids.newId();
        ADMIN.update("""
                insert into att.shift_definition (tenant_id, id, code, name_vi, start_time, end_time, std_hours)
                values (?, ?, 'OFFICE', 'Hành chính', time '08:00', time '17:00', 8)""", tenant, shift);
        UUID pattern = Ids.newId();
        ADMIN.update("""
                insert into att.schedule_pattern (tenant_id, id, code, name_vi, anchor_date, weekly_rest_days)
                values (?, ?, 'P5', 'Tuần 5 ngày', date '2020-01-06', '{6,7}')""", tenant, pattern);
        for (int i = 0; i < 7; i++) {
            ADMIN.update("""
                    insert into att.schedule_pattern_day (tenant_id, pattern_id, day_index, day_kind, shift_id)
                    values (?, ?, ?, ?, ?)""", tenant, pattern, i, i < 5 ? "WORK" : "REST", i < 5 ? shift : null);
        }
        ADMIN.update("""
                insert into att.schedule_assignment_rule (tenant_id, code, priority, legal_entity_id, pattern_id,
                       valid_from)
                values (?, 'LE_DEFAULT', 10, ?, ?, date '2020-01-01')""", tenant, le, pattern);
        return pattern;
    }

    @Test
    void anApprovedHireGetsItsRuleBasedSchedule() throws Exception {
        World w = employmentWorld("att-feed");
        UUID pattern = schedulingRule(w.tenant(), w.le());
        UUID employment = hireApproved(w, hireBody(newPerson("001095000201"), w.le(), w.unit().id(), w.location(),
                TODAY.minusDays(2), 10_000_000));

        List<Map<String, Object>> lines = ADMIN.queryForList("""
                select assignment_key, legal_entity_id, location_id, valid_from from att.ext_hr_assignment
                 where employment_id = ?""", employment);
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.get("legal_entity_id")).isEqualTo(w.le());
            assertThat(l.get("location_id")).isEqualTo(w.location());
        });
        List<Map<String, Object>> schedules = ADMIN.queryForList("""
                select pattern_id, source, valid_from from att.schedule_assignment
                 where employment_id = ? and status = 'ACTIVE'""", employment);
        assertThat(schedules).singleElement().satisfies(s -> {
            assertThat(s.get("pattern_id")).isEqualTo(pattern);
            assertThat(s.get("source")).isEqualTo("RULE");
            assertThat(s.get("valid_from").toString()).isEqualTo(TODAY.minusDays(2).toString());
        });
        // the employment facts, the inbox rows of both consumers, and the schedule event
        assertThat(ADMIN.queryForObject("select status from att.ext_hr_employment where employment_id = ?",
                String.class, employment)).isEqualTo("ACTIVE");
        assertThat(ADMIN.queryForList("select consumer, status from att.inbox_message where tenant_id = ?",
                w.tenant()))
                .extracting(r -> r.get("consumer") + "/" + r.get("status"))
                .contains("att.AssignmentProjection/PROCESSED", "att.EmploymentProjection/PROCESSED")
                .noneMatch(x -> x.endsWith("/FAILED"));
        assertThat(ADMIN.queryForObject("select count(*) from att.outbox_event where event_type = "
                + "'att.ScheduleAssignmentChanged' and aggregate_id = ?", Integer.class, employment)).isPositive();
    }
}
