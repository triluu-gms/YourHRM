package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/** Merging duplicate persons (US-PPL-004): preview, re-pointing of child rows, identifiers, conflicts, audit, events. */
class PersonMergeIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final String PERSONS = "/api/v1/people/persons/";

    record World(UUID tenant, UUID le, UUID le2, OrgUnit unit, OrgUnit unit2, TestUser hr) {
    }

    World world(String name) {
        UUID tenant = FIX.tenant(name);
        PersonTestData.codes(ADMIN, tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        UUID le2 = FIX.legalEntity(tenant, "LE2");
        return new World(tenant, le, le2, FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null),
                FIX.orgUnit(tenant, "U2", null, le2, null, TestFixtures.EPOCH, null), PPL.hrAdmin(tenant));
    }

    ResultActions postJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(post(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void theDuplicateIsMergedIntoTheSurvivorWithItsChildRowsAndIdentifiers() throws Exception {
        World w = world("ppl-merge");
        // survivor: employment in LE; duplicate (e.g. imported twice): employment in LE2, a passport, a primary contact
        Employee survivor = PPL.employee(w.tenant(), w.le(), w.unit(), "Nguyễn", "An", LocalDate.of(2024, 1, 2));
        Employee dup = PPL.employee(w.tenant(), w.le2(), w.unit2(), "Nguyen", "An", LocalDate.of(2025, 1, 2));
        String dupBody = mvc.perform(post("/api/v1/people/persons/" + dup.personId() + "/identity-documents")
                        .with(as(w.hr())).contentType(MediaType.APPLICATION_JSON).content("""
                                {"docType": "PASSPORT", "docNo": "C7654321", "issuingCountryCode": "VN"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID passport = UUID.fromString(JsonPath.read(dupBody, "$.id"));
        postJson(w.hr(), PERSONS + survivor.personId() + "/contacts", "{\"contactType\": \"MOBILE\", \"value\": \"0911111111\"}")
                .andExpect(status().isCreated());
        postJson(w.hr(), PERSONS + dup.personId() + "/contacts", "{\"contactType\": \"MOBILE\", \"value\": \"0922222222\"}")
                .andExpect(status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(PERSONS + dup.personId() + "/identifiers").with(as(w.hr()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0, \"set\": {\"siNumber\": \"7901234567\"}}"))
                .andExpect(status().isOk());

        mvc.perform(get(PERSONS + survivor.personId() + "/merge-preview?duplicatePersonId=" + dup.personId())
                        .with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mergeable").value(true))
                .andExpect(jsonPath("$.merged").value(false))
                .andExpect(jsonPath("$.rowsToMove.employment").value(1))
                .andExpect(jsonPath("$.rowsToMove.assignment").doesNotExist())
                .andExpect(jsonPath("$.rowsToMove.person_identity_doc").value(1))
                .andExpect(jsonPath("$.identifiersTaken[0]").value("si_number"));
        // the preview changed nothing
        assertThat(ADMIN.queryForObject("select person_id from hr.employment where id = ?", UUID.class,
                dup.employmentId())).isEqualTo(dup.personId());

        postJson(w.hr(), PERSONS + survivor.personId() + "/merge", """
                {"duplicatePersonId": "%s", "reason": "Imported twice (RK2)", "version": 0}""".formatted(dup.personId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.merged").value(true));
        assertThat(ADMIN.queryForObject("select person_id from hr.employment where id = ?", UUID.class,
                dup.employmentId())).isEqualTo(survivor.personId());
        assertThat(ADMIN.queryForObject("select person_id from hr.person_identity_doc where id = ?", UUID.class,
                passport)).isEqualTo(survivor.personId());
        assertThat(ADMIN.queryForObject("select si_number_last4 from hr.person where id = ?", String.class,
                survivor.personId())).isEqualTo("4567");
        assertThat(ADMIN.queryForObject("select is_anonymised from hr.person where id = ?", Boolean.class,
                dup.personId())).isTrue();
        assertThat(ADMIN.queryForObject("""
                select count(*) from hr.person_contact where person_id = ? and contact_type = 'MOBILE' and is_primary""",
                Long.class, survivor.personId())).isEqualTo(1L);
        // the moved P3 values are readable under the survivor's key
        postJson(w.hr(), PERSONS + survivor.personId() + "/identity-documents/" + passport + "/unmask",
                "{\"purpose\": \"merge check\"}").andExpect(status().isOk()).andExpect(jsonPath("$.docNo").value("C7654321"));
        postJson(w.hr(), PERSONS + survivor.personId() + "/unmask", """
                {"fields": ["SI_NUMBER"], "purpose": "merge check"}""")
                .andExpect(jsonPath("$.values.SI_NUMBER").value("7901234567"));
        assertThat(ADMIN.queryForObject("""
                select payload->>'mergedPersonId' from hr.outbox_event where tenant_id = ? and event_type = 'people.PersonsMerged'""",
                String.class, w.tenant())).isEqualTo(dup.personId().toString());
        assertThat(ADMIN.queryForObject("""
                select change::text from audit.audit_event where tenant_id = ? and action = 'MERGE' and object_id = ?""",
                String.class, w.tenant(), survivor.personId())).contains("Imported twice (RK2)");
        // merging again is refused
        postJson(w.hr(), PERSONS + survivor.personId() + "/merge", """
                {"duplicatePersonId": "%s", "reason": "again", "version": 1}""".formatted(dup.personId()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void overlappingEmploymentsInOneLegalEntityOrDifferentIdentifiersBlockTheMerge() throws Exception {
        World w = world("ppl-merge-conflict");
        Employee a = PPL.employee(w.tenant(), w.le(), w.unit(), "Lê", "Bình", LocalDate.of(2024, 1, 2));
        Employee b = PPL.employee(w.tenant(), w.le(), w.unit(), "Le", "Binh", LocalDate.of(2025, 1, 2));
        mvc.perform(get(PERSONS + a.personId() + "/merge-preview?duplicatePersonId=" + b.personId()).with(as(w.hr())))
                .andExpect(jsonPath("$.mergeable").value(false))
                .andExpect(jsonPath("$.conflicts[0]").value("constraint: employment_person_le_ex"));
        postJson(w.hr(), PERSONS + a.personId() + "/merge", """
                {"duplicatePersonId": "%s", "reason": "dup", "version": 0}""".formatted(b.personId()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("merge-conflict"));

        Employee c = PPL.employee(w.tenant(), w.le2(), w.unit2(), "Lê", "Bình", LocalDate.of(2025, 1, 2));
        for (UUID[] x : new UUID[][] {{a.personId(), null}, {c.personId(), null}}) {
            String nid = x[0].equals(a.personId()) ? "001090011111" : "001090022222";
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put(PERSONS + x[0] + "/identifiers").with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\": 0, \"set\": {\"nationalIdType\": \"CCCD\", \"nationalId\": \"" + nid + "\"}}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(PERSONS + a.personId() + "/merge-preview?duplicatePersonId=" + c.personId()).with(as(w.hr())))
                .andExpect(jsonPath("$.mergeable").value(false))
                .andExpect(jsonPath("$.conflicts[0]").value(org.hamcrest.Matchers.containsString("national_id")));
        // privileged permission required
        TestUser hrOnly = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.PERSON_UPDATE,
                PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(PERSONS + a.personId() + "/merge-preview?duplicatePersonId=" + c.personId()).with(as(hrOnly)))
                .andExpect(status().isForbidden());
    }
}
