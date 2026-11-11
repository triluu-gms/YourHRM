package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import vn.yourhrm.people.api.EmployeeDirectory;
import vn.yourhrm.people.api.EmployeeDirectory.EmployeeSnapshot;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * OUT-03 read API (US-PPL-081, NFR-PPL-001): snapshot of one employee and a page per legal entity, as of a date and as
 * known at an instant; masked person data without P3; salary only with {@code hr.compensation.view} (and audited);
 * scope and tenant isolation.
 */
class ReadApiIT extends EmploymentTestSupport {

    @Autowired
    EmployeeDirectory directory;

    UUID hiredWithSignedContract(World w, String nationalId, LocalDate hireDate) throws Exception {
        UUID id = hireApproved(w, hireBody(newPerson(nationalId), w.le(), w.unit().id(), w.location(), hireDate,
                10_000_000));
        UUID c = ADMIN.queryForObject("select id from hr.contract where employment_id = ?", UUID.class, id);
        int v = ADMIN.queryForObject("select version from hr.contract where id = ?", Integer.class, c);
        mvc.perform(multipart("/api/v1/people/contracts/" + c + "/sign")
                        .file(new MockMultipartFile("file", "scan.pdf", "application/pdf", ContractTestBase.PDF))
                        .param("version", String.valueOf(v)).param("signDate", hireDate.toString()).with(as(w.hr())))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void aSnapshotCarriesMaskedPersonEmploymentAssignmentContractAndSalaryOnlyWithCompensation() throws Exception {
        World w = employmentWorld("read-one");
        LocalDate hireDate = TODAY.minusDays(10);
        UUID id = hiredWithSignedContract(w, "001095000701", hireDate);
        String path = "/api/v1/people/employments/" + id + "/snapshot";
        String full = body(mvc.perform(get(path).with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.person.fullName").value("Lê Bình"))
                .andExpect(jsonPath("$.person.nationalIdMasked").value(startsWith("••••")))
                .andExpect(jsonPath("$.employment.activeOn").value(true))
                .andExpect(jsonPath("$.employment.status").value("ACTIVE"))
                .andExpect(jsonPath("$.primaryAssignment.orgUnitId").value(w.unit().id().toString()))
                .andExpect(jsonPath("$.contract.contractTypeCode").value("INDEFINITE"))
                .andExpect(jsonPath("$.contract.status").value("ACTIVE"))
                .andExpect(jsonPath("$.salaryVisible").value(true))
                .andExpect(jsonPath("$.salary.baseSalaryAmount").value(10000000))
                .andExpect(jsonPath("$.salary.payBasis").value("GROSS")));
        assertThat(full).doesNotContain("001095000701").doesNotContain("nationalId\":");
        assertThat(count("select count(*) from audit.audit_event where tenant_id = ? and action = 'VIEW_SALARY'",
                w.tenant())).isEqualTo(1);

        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(path).with(as(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salaryVisible").value(false))
                .andExpect(jsonPath("$.salary").value(nullValue()))
                .andExpect(jsonPath("$.primaryAssignment.orgUnitId").value(w.unit().id().toString()));

        // as of a date before the hire: not active, no assignment / contract / salary in force
        mvc.perform(get(path).param("asOf", hireDate.minusDays(1).toString()).with(as(w.hr())))
                .andExpect(jsonPath("$.employment.activeOn").value(false))
                .andExpect(jsonPath("$.primaryAssignment").value(nullValue()))
                .andExpect(jsonPath("$.contract").value(nullValue()))
                .andExpect(jsonPath("$.salary").value(nullValue()));
    }

    @Test
    void asKnownAtShowsTheBiTemporalPartsAsTheyWereRecorded() throws Exception {
        World w = employmentWorld("read-known");
        Instant before = Instant.now().minusSeconds(1);
        UUID id = hiredWithSignedContract(w, "001095000702", TODAY.minusDays(10));
        EmployeeSnapshot now = as(w.hr(), () -> directory.snapshot(id, TODAY, null)).orElseThrow();
        assertThat(now.assignments()).hasSize(1);
        assertThat(now.salary()).isNotNull();
        EmployeeSnapshot then = as(w.hr(), () -> directory.snapshot(id, TODAY, before)).orElseThrow();
        assertThat(then.assignments()).isEmpty();
        assertThat(then.salary()).isNull();
        mvc.perform(get("/api/v1/people/employments/" + id + "/snapshot").param("asKnownAt", "not-a-date")
                .with(as(w.hr()))).andExpect(status().isUnprocessableContent());
    }

    @Test
    void theBatchOfALegalEntityIsScopedAndTenantIsolated() throws Exception {
        World w = employmentWorld("read-batch");
        UUID id = hiredWithSignedContract(w, "001095000703", TODAY.minusDays(10));
        String path = "/api/v1/people/legal-entities/" + w.le() + "/employee-snapshots";
        mvc.perform(get(path).with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3))) // manager, employee and the new hire
                .andExpect(jsonPath("$[?(@.employment.employmentId == '" + id + "')].salary.baseSalaryAmount")
                        .value(org.hamcrest.Matchers.hasItem(1.0E7)));
        mvc.perform(get(path).param("limit", "1").param("offset", "1").with(as(w.hr())))
                .andExpect(jsonPath("$", hasSize(1)));
        UUID otherLe = FIX.legalEntity(w.tenant(), "LEO");
        TestUser otherLeUser = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(otherLe),
                PeoplePermissions.EMPLOYEE_VIEW, PeoplePermissions.COMPENSATION_VIEW);
        mvc.perform(get(path).with(as(otherLeUser))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/people/employments/" + id + "/snapshot").with(as(otherLeUser)))
                .andExpect(status().isNotFound());
        World other = employmentWorld("read-batch-x");
        mvc.perform(get(path).with(as(other.hr()))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/people/employments/" + id + "/snapshot").with(as(other.hr())))
                .andExpect(status().isNotFound());
        TestUser nobody = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.ESS_VIEW);
        mvc.perform(get(path).with(as(nobody))).andExpect(status().isForbidden());
    }
}
