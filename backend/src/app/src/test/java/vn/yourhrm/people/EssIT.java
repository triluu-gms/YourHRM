package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * People ESS pages (US-PPL-072 my profile, US-PPL-073 my employment and documents, US-PPL-077 former employee access):
 * SELF scope only, masked identifiers, signed download links through Core, the salary tab behind the tenant policy
 * flag, the former-employee window of {@code core.former_employee_access_days}.
 */
class EssIT extends EmploymentTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    vn.yourhrm.core.api.DocumentScanResults scans;

    record Hired(UUID employmentId, UUID personId, UUID contractId) {
    }

    Hired hired(World w, String nationalId) throws Exception {
        LocalDate hireDate = TODAY.minusDays(200);
        UUID id = hireApproved(w, hireBody(newPerson(nationalId), w.le(), w.unit().id(), w.location(), hireDate,
                10_000_000));
        UUID c = ADMIN.queryForObject("select id from hr.contract where employment_id = ?", UUID.class, id);
        int v = ADMIN.queryForObject("select version from hr.contract where id = ?", Integer.class, c);
        mvc.perform(multipart("/api/v1/people/contracts/" + c + "/sign")
                        .file(new MockMultipartFile("file", "scan.pdf", "application/pdf", ContractTestBase.PDF))
                        .param("version", String.valueOf(v)).param("signDate", hireDate.toString()).with(as(w.hr())))
                .andExpect(status().isOk());
        UUID person = ADMIN.queryForObject("select person_id from hr.employment where id = ?", UUID.class, id);
        return new Hired(id, person, c);
    }

    TestUser essUser(World w, Hired h) {
        TestUser u = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.ESS_VIEW,
                PeoplePermissions.CONTRACT_VIEW, PeoplePermissions.DECISION_VIEW);
        PPL.link(u, new Employee(h.personId(), h.employmentId(), null));
        return u;
    }

    @Test
    void myProfileShowsMyOwnDataWithMaskedIdentifiers() throws Exception {
        World w = employmentWorld("ess-profile");
        Hired h = hired(w, "001095000501");
        TestUser me = essUser(w, h);
        String body = body(mvc.perform(get("/api/v1/people/me/profile").with(as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personId").value(h.personId().toString()))
                .andExpect(jsonPath("$.fullName").value("Lê Bình"))
                .andExpect(jsonPath("$.birthDate").value("1995-03-02"))
                .andExpect(jsonPath("$.nationalIdMasked").value("••••0501"))
                .andExpect(jsonPath("$.employments", hasSize(1))));
        assertThat(body).doesNotContain("001095000501");
        // not linked to an employment: nothing to show
        TestUser stranger = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.ESS_VIEW);
        mvc.perform(get("/api/v1/people/me/profile").with(as(stranger))).andExpect(status().isNotFound());
        TestUser noEss = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get("/api/v1/people/me/profile").with(as(noEss))).andExpect(status().isForbidden());
    }

    @Test
    void myEmploymentListsAssignmentsContractsDecisionsAndHidesSalaryByDefault() throws Exception {
        World w = employmentWorld("ess-employment");
        Hired h = hired(w, "001095000502");
        TestUser me = essUser(w, h);
        mvc.perform(get("/api/v1/people/me/employments").with(as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].assignments", hasSize(1)))
                .andExpect(jsonPath("$[0].contracts[0].recordId").value(h.contractId().toString()))
                .andExpect(jsonPath("$[0].contracts[0].documentId").exists())
                .andExpect(jsonPath("$[0].decisions[0].typeCode").value("HIRE"))
                .andExpect(jsonPath("$[0].salaryVisible").value(false))
                .andExpect(jsonPath("$[0].salary").value(nullValue()));

        // documents: only my own, downloaded through Core's signed URL
        String docs = body(mvc.perform(get("/api/v1/people/me/documents").with(as(me))).andExpect(status().isOk()));
        String documentId = JsonPath.read(docs, "$[?(@.kind == 'CONTRACT')].documentId").toString()
                .replaceAll("[\\[\\]\"]", "");
        String key = ADMIN.queryForObject("select object_key from core.document_version where document_id = ? "
                + "and version_no = 1", String.class, UUID.fromString(documentId));
        scans.report(w.tenant(), key, "CLEAN"); // Core serves clean versions only
        mvc.perform(get("/api/v1/people/me/documents/" + documentId + "/download-link").with(as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists());
        Hired other = hired(w, "001095000503");
        UUID otherDoc = ADMIN.queryForObject("select document_id from hr.contract where id = ?", UUID.class,
                other.contractId());
        mvc.perform(get("/api/v1/people/me/documents/" + otherDoc + "/download-link").with(as(me)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theSalaryTabFollowsTheTenantPolicyAndShowsOnlyMyOwnVersions() throws Exception {
        World w = employmentWorld("ess-salary");
        ADMIN.update("insert into core.setting_value (tenant_id, setting_key, value) values (?, ?, 'true')",
                w.tenant(), "hr.ess.salary_history_visible");
        Hired h = hired(w, "001095000504");
        TestUser me = essUser(w, h);
        mvc.perform(get("/api/v1/people/me/employments").with(as(me)))
                .andExpect(jsonPath("$[0].salaryVisible").value(true))
                .andExpect(jsonPath("$[0].salary", hasSize(1)))
                .andExpect(jsonPath("$[0].salary[0].baseSalaryAmount").value(10000000));
        assertThat(count("select count(*) from audit.audit_event where tenant_id = ? and action = 'VIEW_OWN_SALARY'",
                w.tenant())).isEqualTo(1);
    }

    @Test
    void aFormerEmployeeSeesOwnDocumentsOnlyWithinTheConfiguredWindow() throws Exception {
        World w = employmentWorld("ess-former");
        Hired h = hired(w, "001095000505");
        TestUser former = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.CONTRACT_VIEW,
                PeoplePermissions.DECISION_VIEW);
        PPL.link(former, new Employee(h.personId(), h.employmentId(), null));
        ADMIN.update("update hr.employment set status = 'TERMINATED', end_date = ? where id = ?", TODAY.minusDays(10),
                h.employmentId());
        mvc.perform(get("/api/v1/people/me/documents").with(as(former)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'CONTRACT')].number").exists());
        // the profile and employment pages need hr.ess.view (active employees only)
        mvc.perform(get("/api/v1/people/me/profile").with(as(former))).andExpect(status().isForbidden());
        // beyond core.former_employee_access_days (default 90) the documents are gone
        ADMIN.update("update hr.employment set end_date = ? where id = ?", TODAY.minusDays(91), h.employmentId());
        mvc.perform(get("/api/v1/people/me/documents").with(as(former))).andExpect(status().isNotFound());
    }
}
