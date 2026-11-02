package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.yourhrm.people.api.PayeeBankAccounts;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Salary bank accounts (BR-PPL-015, NFR-PPL-005/011; US-PPL-031/032/075): encrypted and masked account numbers,
 * default (REMAINDER) and split rules, holder-name warning without diacritics, approval through
 * {@code hr.BANK_ACCOUNT_CHANGE} with {@code people.BankAccountChanged} (no full number), ESS changes with step-up and
 * the employee notification, Payroll's read API, scope and tenant isolation.
 */
class BankAccountIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final String BANK = "TB" + Ids.newId().toString().substring(30).toUpperCase();
    static final LocalDate HIRED = LocalDate.of(2024, 1, 1);

    @Autowired
    PayeeBankAccounts payee;

    record World(UUID tenant, UUID le, OrgUnit unit, Employee emp, TestUser hr, TestUser approver) {
    }

    @BeforeAll
    static void banks() {
        ADMIN.update("insert into core.bank (bank_code, short_name, name_vi) values (?, ?, ?) on conflict do nothing",
                BANK, "Vietcombank " + BANK, "Ngân hàng TMCP Ngoại thương " + BANK);
        ADMIN.update("""
                insert into core.bank_branch (bank_code, branch_code, name_vi) values (?, '001', 'Chi nhánh Hà Nội')
                on conflict do nothing""", BANK);
    }

    World world(String name) {
        UUID tenant = FIX.tenant(name);
        UUID le = FIX.legalEntity(tenant, "LE1");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Nguyễn", "Ánh", HIRED);
        List<String> codes = new ArrayList<>(FIX.allCorePermissions());
        codes.addAll(PPL.allHrPermissions());
        UUID role = FIX.role(tenant, "HR_ADMIN", false, codes.toArray(String[]::new));
        TestUser approver = FIX.user(tenant, "approver@test.local");
        FIX.assign(approver, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        return new World(tenant, le, unit, emp, PPL.hrAdmin(tenant), approver);
    }

    static String account(String accountNo, String holder, String splitType, String splitValue, String validFrom) {
        return """
                {"bankCode":"%s","branchCode":"001","accountNo":"%s","holderName":"%s","splitType":"%s",
                 "splitValue":%s,"validFrom":"%s"}""".formatted(BANK, accountNo, holder, splitType, splitValue, validFrom);
    }

    ResultActions create(TestUser user, UUID personId, String json) throws Exception {
        return mvc.perform(post("/api/v1/people/persons/" + personId + "/bank-accounts").with(as(user))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static UUID id(ResultActions r) throws Exception {
        return UUID.fromString(JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.account.id"));
    }

    void decide(TestUser approver, UUID subjectId, String action) throws Exception {
        UUID task = ADMIN.queryForObject("""
                select t.id from core.workflow_task t
                  join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                 where i.subject_id = ? and t.status = 'PENDING'""", UUID.class, subjectId);
        mvc.perform(post("/api/v1/core/workflow/tasks/" + task + "/actions").with(as(approver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"comment\":\"checked\"}"))
                .andExpect(status().isNoContent());
    }

    static Map<String, Object> account(UUID id) {
        return ADMIN.queryForMap("select status, valid_to, step_up_auth_ref, verified_by from hr.bank_account where id = ?",
                id);
    }

    static List<Map<String, Object>> events(UUID tenant) {
        return ADMIN.queryForList("""
                select payload ->> 'subtype' as subtype, payload ->> 'bankAccountId' as account, payload::text as payload
                  from hr.outbox_event where tenant_id = ? and event_type = 'people.BankAccountChanged'
                 order by created_at, id""", tenant);
    }

    /** An ESS token; {@code acr} null = an ordinary session. */
    static RequestPostProcessor ess(TestUser user, String acr, Instant authTime) {
        return jwt().jwt(j -> {
            j.subject(user.identityId().toString()).claim("tenant_id", user.tenantId().toString()).jti("jti-1");
            if (acr != null) {
                j.claim("acr", acr).claim("auth_time", authTime.getEpochSecond());
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void accountsAreEncryptedSplitCheckedApprovedAndPublishedWithoutTheNumber() throws Exception {
        World w = world("bank-hr");
        UUID person = w.emp().personId();

        // a split account needs a default account first
        create(w.hr(), person, account("0011 0022 33", "NGUYEN ANH", "PERCENT", "30", "2026-01-01"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("default-account-required"));
        ResultActions def = create(w.hr(), person, account("0011-0022-3456", "nguyen anh", "REMAINDER", "null",
                "2026-01-01"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.account.accountNoMasked").value("••••3456"))
                .andExpect(jsonPath("$.account.bankName").value("Vietcombank " + BANK))
                .andExpect(jsonPath("$.warnings", hasSize(0))); // "nguyen anh" = "Nguyễn Ánh" without diacritics
        UUID defaultId = id(def);
        assertThat(def.andReturn().getResponse().getContentAsString()).doesNotContain("00110022");
        Map<String, Object> row = ADMIN.queryForMap("select * from hr.bank_account where id = ?", defaultId);
        assertThat(row.get("account_no_last4")).isEqualTo("3456");
        assertThat(new String((byte[]) row.get("account_no_enc"), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("3456");

        // validation
        create(w.hr(), person, account("123", "NGUYEN ANH", "REMAINDER", "null", "2026-01-01"))
                .andExpect(jsonPath("$.code").value("invalid-account-no"));
        create(w.hr(), person, """
                {"bankCode":"NOPE","accountNo":"99998888","holderName":"X","validFrom":"2026-01-01"}""")
                .andExpect(jsonPath("$.code").value("unknown-bank"));
        create(w.hr(), person, account("001100223456", "NGUYEN ANH", "REMAINDER", "null", "2026-03-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("bank-account-duplicate"));

        // a split account: warning on the holder name; percentages over 100 are refused
        ResultActions split = create(w.hr(), person, account("7777888899", "TRAN VAN BINH", "PERCENT", "60",
                "2026-01-01"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings[*].code", hasItem("holder-name-mismatch")));
        UUID splitId = id(split);
        create(w.hr(), person, account("5555666677", "NGUYEN ANH", "PERCENT", "50", "2026-02-01"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("split-exceeds-100"));

        assertThat(events(w.tenant())).isEmpty();
        decide(w.approver(), defaultId, "APPROVE");
        decide(w.approver(), splitId, "APPROVE");
        assertThat(account(defaultId)).containsEntry("status", "ACTIVE").containsEntry("verified_by", w.approver().userId());
        List<Map<String, Object>> ev = events(w.tenant());
        assertThat(ev).extracting(e -> e.get("subtype")).containsExactly("ACTIVATED", "ACTIVATED");
        assertThat(ev).allSatisfy(e -> assertThat((String) e.get("payload")).doesNotContain("00110022")
                .doesNotContain("7777888899"));
        assertThat((String) ev.getFirst().get("payload")).contains("\"accountNoLast4\":\"3456\"");

        // a new default account from June ends the previous one the day before (one ACTIVE default per day)
        UUID newDefault = id(create(w.hr(), person, account("2222333344", "NGUYEN ANH", "REMAINDER", "null",
                "2026-06-01")).andExpect(status().isCreated()));
        decide(w.approver(), newDefault, "APPROVE");
        assertThat(account(defaultId).get("valid_to")).isEqualTo(java.sql.Date.valueOf("2026-06-01"));
        assertThat(events(w.tenant())).extracting(e -> e.get("subtype"))
                .containsExactly("ACTIVATED", "ACTIVATED", "CLOSED", "ACTIVATED");

        // Payroll's read API: ACTIVE accounts on the date, split first, decrypted
        var onJuly = TenantContext.callWith(new TenantContext(w.tenant(), w.hr().userId(), null, "t", null),
                () -> payee.activeAccounts(person, LocalDate.of(2026, 7, 1)));
        assertThat(onJuly).extracting(PayeeBankAccounts.PayeeBankAccount::accountNo)
                .containsExactly("7777888899", "2222333344");

        // the split can be ended directly by HR; the list shows history masked
        String splitVersion = ADMIN.queryForObject("select version::text from hr.bank_account where id = ?", String.class,
                splitId);
        mvc.perform(post("/api/v1/people/persons/" + person + "/bank-accounts/" + splitId + "/close").with(as(w.hr()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validTo\":\"2026-09-01\",\"version\":" + splitVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validTo").value("2026-09-01"));
        mvc.perform(get("/api/v1/people/persons/" + person + "/bank-accounts").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].accountNoMasked", hasItem("••••3344")));

        // unmasking needs hr.person.view_sensitive (audited); others get 403
        mvc.perform(get("/api/v1/people/persons/" + person + "/bank-accounts/" + newDefault + "/account-number")
                        .with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountNo").value("2222333344"));
        TestUser viewer = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(w.le()), PeoplePermissions.BANK_ACCOUNT_VIEW);
        mvc.perform(get("/api/v1/people/persons/" + person + "/bank-accounts").with(as(viewer)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/people/persons/" + person + "/bank-accounts/" + newDefault + "/account-number")
                .with(as(viewer))).andExpect(status().isForbidden());
        create(viewer, person, account("4444555566", "NGUYEN ANH", "REMAINDER", "null", "2027-01-01"))
                .andExpect(status().isForbidden());
        // other legal entity / other tenant: not found
        TestUser otherLe = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(FIX.legalEntity(w.tenant(), "LE2")),
                PeoplePermissions.BANK_ACCOUNT_VIEW);
        mvc.perform(get("/api/v1/people/persons/" + person + "/bank-accounts").with(as(otherLe)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/people/persons/" + person + "/bank-accounts").with(as(PPL.hrAdmin(FIX.tenant("bank-x")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void employeesChangeTheirAccountWithStepUpAndAreNotified() throws Exception {
        World w = world("bank-ess");
        TestUser me = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.ESS_VIEW,
                PeoplePermissions.ESS_REQUEST);
        PPL.link(me, w.emp());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        String body = account("9876543210", "NGUYEN ANH", "REMAINDER", "null", today.toString());

        // without step-up, or with a stale one: refused
        mvc.perform(post("/api/v1/people/me/bank-accounts").with(ess(me, null, null))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("step-up-required"));
        mvc.perform(post("/api/v1/people/me/bank-accounts").with(ess(me, "step-up", Instant.now().minusSeconds(3600)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("step-up-required"));
        mvc.perform(post("/api/v1/people/me/bank-accounts").with(ess(me, "1", Instant.now()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // a past start date is refused even with step-up
        mvc.perform(post("/api/v1/people/me/bank-accounts").with(ess(me, "step-up", Instant.now()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(account("9876543210", "NGUYEN ANH", "REMAINDER", "null", "2020-01-01")))
                .andExpect(status().is(422));

        String created = mvc.perform(post("/api/v1/people/me/bank-accounts").with(ess(me, "step-up", Instant.now()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.account.stepUpVerified").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID accountId = UUID.fromString(JsonPath.read(created, "$.account.id"));
        assertThat((String) account(accountId).get("step_up_auth_ref")).startsWith("acr=step-up;auth_time=")
                .endsWith(";jti=jti-1");
        mvc.perform(get("/api/v1/people/me/bank-accounts").with(as(me)))
                .andExpect(jsonPath("$[0].status").value("PENDING_VERIFICATION"));

        decide(w.approver(), accountId, "APPROVE");
        assertThat(account(accountId)).containsEntry("status", "ACTIVE");
        assertThat(ADMIN.queryForList("""
                select event_code from core.notification where tenant_id = ? and recipient_user_id = ?""",
                String.class, w.tenant(), me.userId())).contains("hr.BankAccountChanged");

        // a rejected request: REJECTED and the employee is told
        String second = mvc.perform(post("/api/v1/people/me/bank-accounts").with(ess(me, "mfa", Instant.now()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(account("1212343456", "NGUYEN ANH", "FIXED", "2000000", today.plusDays(1).toString())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID secondId = UUID.fromString(JsonPath.read(second, "$.account.id"));
        decide(w.approver(), secondId, "REJECT");
        assertThat(account(secondId)).containsEntry("status", "REJECTED");
        assertThat(ADMIN.queryForList("""
                select event_code from core.notification where tenant_id = ? and recipient_user_id = ?""",
                String.class, w.tenant(), me.userId())).contains("hr.BankAccountChangeRejected");

        // ESS cannot use the HR endpoints; an unlinked user has no own record
        mvc.perform(get("/api/v1/people/persons/" + w.emp().personId() + "/bank-accounts").with(as(me)))
                .andExpect(status().isForbidden());
        TestUser unlinked = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.ESS_VIEW,
                PeoplePermissions.ESS_REQUEST);
        mvc.perform(get("/api/v1/people/me/bank-accounts").with(as(unlinked))).andExpect(status().isNotFound());
    }
}
