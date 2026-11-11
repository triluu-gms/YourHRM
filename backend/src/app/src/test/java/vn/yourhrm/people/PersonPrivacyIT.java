package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import vn.yourhrm.people.internal.application.person.PersonalDataInventory;
import vn.yourhrm.people.internal.application.person.PersonalDataInventory.PiiClass;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * PDPL for People data (EP-PPL-12): every hr column is classified (US-PPL-083), sensitive columns are P3 with a lawful
 * basis, and P3 values never appear in logs, events, audit diffs or error responses (US-PPL-086).
 */
@ExtendWith(OutputCaptureExtension.class)
class PersonPrivacyIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);

    @Test
    void everyHrColumnIsClassified() {
        List<Map<String, Object>> columns = ADMIN.queryForList("""
                select c.table_name, c.column_name,
                       col_description(format('hr.%I', c.table_name)::regclass, c.ordinal_position) as comment
                  from information_schema.columns c
                  join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
                 where c.table_schema = 'hr' and t.table_type = 'BASE TABLE'
                   and c.table_name !~ '^(outbox_event|inbox_message)'
                 order by 1, c.ordinal_position""");
        assertThat(columns).hasSizeGreaterThan(300);
        List<String> unclassified = new ArrayList<>();
        List<String> inconsistent = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            String table = (String) c.get("table_name");
            String column = (String) c.get("column_name");
            String comment = (String) c.get("comment");
            var cls = PersonalDataInventory.classify(table, column, comment);
            if (cls.isEmpty()) {
                unclassified.add(table + "." + column);
            } else if (comment != null && comment.contains("pii:P3") && cls.get().piiClass() != PiiClass.P3) {
                inconsistent.add(table + "." + column);
            }
        }
        assertThat(unclassified).as("hr columns without a pii class (add a pii:Px comment or an inventory entry)").isEmpty();
        assertThat(inconsistent).as("columns the DDL marks P3 but the inventory does not").isEmpty();
        // the sensitive classes of BR-PPL-150 are P3, sensitive, and optional ones need consent
        var religion = PersonalDataInventory.classify("person", "religion_enc", null).orElseThrow();
        assertThat(religion.sensitive()).isTrue();
        assertThat(religion.lawfulBasis()).isEqualTo(PersonalDataInventory.LawfulBasis.CONSENT);
        assertThat(PersonalDataInventory.classify("person", "ethnicity_enc", null).orElseThrow().sensitive()).isTrue();
        assertThat(PersonalDataInventory.classify("bank_account", "account_no_enc", null).orElseThrow().category())
                .isEqualTo("FINANCIAL");
        assertThat(PersonalDataInventory.classify("person", "brand_new_column", null)).isEmpty();
    }

    @Test
    void p3ValuesNeverReachLogsEventsAuditOrErrors(CapturedOutput output) throws Exception {
        UUID tenant = FIX.tenant("ppl-p3-logs");
        PersonTestData.codes(ADMIN, tenant);
        TestUser hr = PPL.hrAdmin(tenant);
        String cccd = "079188012345";
        String si = "7988012345";
        String body = mvc.perform(post("/api/v1/people/persons").with(as(hr)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"person": {"familyName": "Bùi", "givenName": "Quang"},
                                 "identifiers": {"nationalIdType": "CCCD", "nationalId": "%s", "siNumber": "%s",
                                                 "ethnicityCode": "TAY"}}""".formatted(cccd, si)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        // errors never echo the value
        String error = mvc.perform(post("/api/v1/people/persons").with(as(hr)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"person": {"familyName": "Bùi", "givenName": "Quang"},
                                 "identifiers": {"nationalIdType": "CCCD", "nationalId": "%s"}}""".formatted(cccd)))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        String badSi = mvc.perform(put("/api/v1/people/persons/" + id + "/identifiers").with(as(hr))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0, \"set\": {\"siNumber\": \"79880123456789\"}}"))
                .andExpect(status().isUnprocessableContent()).andReturn().getResponse().getContentAsString();
        String events = String.join("\n", ADMIN.queryForList(
                "select payload::text from hr.outbox_event where tenant_id = ?", String.class, tenant));
        String audit = String.join("\n", ADMIN.queryForList(
                "select coalesce(change::text, '') from audit.audit_event where tenant_id = ?", String.class, tenant));
        for (String text : List.of(body, error, badSi, events, audit, output.getAll())) {
            assertThat(text).doesNotContain(cccd).doesNotContain(si).doesNotContain("79880123456789");
        }
        assertThat(events).contains("PERSON_CREATED", "national_id", "si_number");
    }
}
