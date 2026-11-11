package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.api.PersonEvents.PersonalDataChanged;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;

/**
 * NFR-PPL-002 / US-PPL-079: a People change and its outbox event commit or roll back together, for a few
 * representative aggregates (person + employment + assignment + contract + salary of a hire; a hand-made change in a
 * People transaction); event ids are unique per row.
 */
class EventRollbackIT extends EmploymentTestSupport {

    @Autowired
    HrOutbox outbox;
    @Autowired
    @Qualifier(HrDb.TX)
    PlatformTransactionManager hrTx;

    static int outboxRows(UUID tenant) {
        return count("select count(*) from hr.outbox_event where tenant_id = ?", tenant);
    }

    @Test
    void aHireThatFailsLateLeavesNeitherRowsNorEvents() throws Exception {
        World w = employmentWorld("evt-rollback-hire");
        int events = outboxRows(w.tenant());
        int persons = count("select count(*) from hr.person where tenant_id = ?", w.tenant());
        int employments = count("select count(*) from hr.employment where tenant_id = ?", w.tenant());
        int assignments = count("select count(*) from hr.assignment where tenant_id = ?", w.tenant());
        // person, employment, decision, assignment and contract are written before the salary check blocks
        postJson(w.hr(), "/api/v1/people/hires", hireBody(newPerson("001095000951"), w.le(), w.unit().id(),
                        w.location(), TODAY.minusDays(1), 1_000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("below-minimum-wage"));
        assertThat(outboxRows(w.tenant())).isEqualTo(events);
        assertThat(count("select count(*) from hr.person where tenant_id = ?", w.tenant())).isEqualTo(persons);
        assertThat(count("select count(*) from hr.employment where tenant_id = ?", w.tenant())).isEqualTo(employments);
        assertThat(count("select count(*) from hr.assignment where tenant_id = ?", w.tenant())).isEqualTo(assignments);

        // the successful hire commits its rows and its events together, each with its own event id
        UUID id = hireApproved(w, hireBody(newPerson("001095000951"), w.le(), w.unit().id(), w.location(),
                TODAY.minusDays(1), 10_000_000));
        List<String> types = ADMIN.queryForList("select event_type from hr.outbox_event where employment_id = ?",
                String.class, id);
        assertThat(types).contains("people.EmployeeHired", "people.AssignmentChanged", "people.SalaryChanged");
        assertThat(count("select count(*) - count(distinct id) from hr.outbox_event where tenant_id = ?", w.tenant()))
                .isZero();
    }

    @Test
    void aRolledBackPeopleTransactionTakesItsEventWithIt() throws Exception {
        World w = employmentWorld("evt-rollback-tx");
        int before = outboxRows(w.tenant());
        Integer inside = as(w.hr(), () -> {
            TransactionTemplate tx = new TransactionTemplate(hrTx);
            return tx.execute(s -> {
                outbox.publish(new PersonalDataChanged(w.tenant(), w.emp().personId(), LocalDate.now(),
                        List.of("preferred_name"), null, null, null, "PERSON_UPDATED"));
                int n = db().jdbc().sql("select count(*) from hr.outbox_event where tenant_id = :t")
                        .param("t", w.tenant()).query(Integer.class).single();
                s.setRollbackOnly();
                return n;
            });
        });
        assertThat(inside).isEqualTo(before + 1);
        assertThat(outboxRows(w.tenant())).isEqualTo(before);
    }

    @Autowired
    HrDb hrDb;

    HrDb db() {
        return hrDb;
    }
}
