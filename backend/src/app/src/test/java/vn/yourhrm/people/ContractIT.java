package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.contract.ContractAppendixService;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.application.contract.ContractService;
import vn.yourhrm.people.internal.application.contract.ContractService.InitialSource;
import vn.yourhrm.people.internal.application.contract.ContractTerms;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Labour contracts (EP-PPL-06): statutory types and terms, numbering, overlap, paper signing (US-PPL-047/056), the
 * consecutive-definite rule with renew / convert and the imported prior count (US-PPL-050/052), probation rules and
 * result (US-PPL-048/049), work-permit validity (BR-PPL-020), appendices (US-PPL-053), suspensions (US-PPL-054),
 * scope and tenant isolation.
 */
class ContractIT extends ContractTestBase {

    @Autowired
    ContractService contracts;
    @Autowired
    ContractAppendixService appendices;

    // ------------------------------------------------------------------------------------------- US-PPL-047/056

    @Test
    void contractsAreNumberedValidatedSignedOnPaperAndNeverOverlap() throws Exception {
        World w = world("ctr-basic");
        UUID emp = w.emp().employmentId();

        createContract(w.hr(), emp, "{\"contractTypeCode\":\"DEFINITE\",\"startDate\":\"2025-01-01\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("end-date-required"));
        createContract(w.hr(), emp, definite("2025-01-01", "2028-01-01"))
                .andExpect(jsonPath("$.code").value("definite-term-too-long"));
        createContract(w.hr(), emp, "{\"contractTypeCode\":\"INDEFINITE\",\"startDate\":\"2025-01-01\",\"endDate\":\"2026-01-01\"}")
                .andExpect(jsonPath("$.code").value("indefinite-has-no-end"));
        createContract(w.hr(), emp, "{\"contractTypeCode\":\"FREELANCE\",\"startDate\":\"2025-01-01\"}")
                .andExpect(jsonPath("$.code").value("invalid-contract-type"));
        createContract(w.hr(), emp, definite("2024-12-01", "2025-06-30"))
                .andExpect(jsonPath("$.code").value("contract-before-hire"));
        createContract(w.hr(), emp, "{\"contractTypeCode\":\"SEASONAL\",\"startDate\":\"2025-01-01\",\"endDate\":\"2025-01-31\"}")
                .andExpect(jsonPath("$.code").value("seasonal-term-too-long"));

        String body = createContract(w.hr(), emp, definite("2025-01-01", "2027-12-31"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractNo").value("001/2025/HĐLĐ-LE"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.definiteSeq").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID id = id(body);

        String path = "/api/v1/people/contracts/" + id + "/sign";
        sign(w.hr(), path, LocalDate.now().plusDays(5).toString(), 0)
                .andExpect(jsonPath("$.code").value("invalid-sign-date"));
        sign(w.hr(), path, "2025-01-01", 7).andExpect(status().isConflict());
        sign(w.hr(), path, "2024-12-28", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.signDate").value("2024-12-28"))
                .andExpect(jsonPath("$.documentId").isString());
        assertThat(ADMIN.queryForObject("select owner_entity_type from core.document where tenant_id = ? and owner_entity_id = ?",
                String.class, w.tenant(), id)).isEqualTo("hr.contract");
        List<String> signed = events(w.tenant(), "people.ContractChanged");
        assertThat(signed).hasSize(1);
        assertThat(JsonPath.<String>read(signed.get(0), "$.subtype")).isEqualTo("SIGNED");
        assertThat(JsonPath.<String>read(signed.get(0), "$.contractNo")).isEqualTo("001/2025/HĐLĐ-LE");

        createContract(w.hr(), emp, definite("2025-06-01", "2026-05-31"))
                .andExpect(jsonPath("$.code").value("contract-overlap"));

        // a draft can be cancelled and keeps its number; a signed contract cannot
        UUID draft = id(createContract(w.hr(), emp, "{\"contractTypeCode\":\"INDEFINITE\",\"startDate\":\"2028-01-01\"}")
                .andExpect(jsonPath("$.contractNo").value("001/2028/HĐLĐ-LE"))
                .andReturn().getResponse().getContentAsString());
        postJson(w.hr(), "/api/v1/people/contracts/" + draft + "/cancel", "{\"version\":0}")
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.contractNo").value("001/2028/HĐLĐ-LE"));
        postJson(w.hr(), "/api/v1/people/contracts/" + id + "/cancel", "{\"version\":1}")
                .andExpect(jsonPath("$.code").value("invalid-status-transition"));

        mvc.perform(get("/api/v1/people/employments/" + emp + "/contracts").with(as(w.hr())))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/people/employments/" + emp + "/contracts/current?asOf=2026-03-01").with(as(w.hr())))
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/api/v1/people/employments/" + emp + "/contracts/current?asOf=2028-03-01").with(as(w.hr())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/people/employments/" + emp + "/contracts/current?asOf=2026-03-01&asKnownAt=2020-01-01T00:00:00Z")
                .with(as(w.hr()))).andExpect(status().isNotFound());
    }

    @Test
    void scopeAndTenantIsolation() throws Exception {
        World w = world("ctr-scope");
        UUID id = signedContract(w, w.emp().employmentId(), definite("2025-01-01", "2025-12-31"));
        OrgUnit other = FIX.orgUnit(w.tenant(), "OTHER", null, w.le(), null, TestFixtures.EPOCH, null);
        TestUser outsider = PPL.userWith(w.tenant(), "ORG_UNIT", List.of(other.id()), PeoplePermissions.CONTRACT_VIEW,
                PeoplePermissions.CONTRACT_CREATE);
        mvc.perform(get("/api/v1/people/contracts/" + id).with(as(outsider))).andExpect(status().isNotFound());
        createContract(outsider, w.emp().employmentId(), definite("2026-01-01", "2026-12-31"))
                .andExpect(status().isNotFound());
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.CONTRACT_VIEW);
        mvc.perform(get("/api/v1/people/contracts/" + id).with(as(viewer))).andExpect(status().isOk());
        createContract(viewer, w.emp().employmentId(), definite("2026-01-01", "2026-12-31"))
                .andExpect(status().isForbidden());

        World x = world("ctr-scope-x");
        mvc.perform(get("/api/v1/people/contracts/" + id).with(as(x.hr()))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------- US-PPL-050/052

    @Test
    void afterTwoConsecutiveDefiniteContractsTheNextIsIndefiniteUnlessALegalExceptionApplies() throws Exception {
        World w = world("ctr-two-definite");
        UUID first = signedContract(w, w.emp().employmentId(), definite("2025-01-01", "2025-06-30"));

        String second = postJson(w.hr(), "/api/v1/people/contracts/" + first + "/renew", "{\"endDate\":\"2025-12-31\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").value("2025-07-01"))
                .andExpect(jsonPath("$.definiteSeq").value(2))
                .andExpect(jsonPath("$.previousContractId").value(first.toString()))
                .andReturn().getResponse().getContentAsString();
        postJson(w.hr(), "/api/v1/people/contracts/" + first + "/renew", "{\"endDate\":\"2025-12-31\"}")
                .andExpect(jsonPath("$.code").value("contract-already-followed"));
        UUID secondId = id(second);
        sign(w.hr(), "/api/v1/people/contracts/" + secondId + "/sign", "2025-06-20", 0).andExpect(status().isOk());
        assertThat(events(w.tenant(), "people.ContractChanged")).last()
                .satisfies(e -> assertThat(JsonPath.<String>read(e, "$.subtype")).isEqualTo("RENEWED"));

        postJson(w.hr(), "/api/v1/people/contracts/" + secondId + "/renew", "{\"endDate\":\"2026-12-31\"}")
                .andExpect(jsonPath("$.code").value("indefinite-contract-required"))
                .andExpect(jsonPath("$.definiteSeq").value(3));
        postJson(w.hr(), "/api/v1/people/contracts/" + secondId + "/renew",
                "{\"endDate\":\"2026-12-31\",\"definiteExceptionCode\":\"FRIEND_OF_CEO\"}")
                .andExpect(jsonPath("$.code").value("invalid-definite-exception"));
        UUID third = id(postJson(w.hr(), "/api/v1/people/contracts/" + secondId + "/renew",
                "{\"endDate\":\"2026-12-31\",\"definiteExceptionCode\":\"ELDERLY_WORKER\",\"definiteExceptionNote\":\"Retired, re-employed\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.definiteSeq").value(3))
                .andReturn().getResponse().getContentAsString());
        assertThat(ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ? and action = 'APPLY_EXCEPTION' and object_id = ?",
                Integer.class, w.tenant(), third)).isEqualTo(1);
        postJson(w.hr(), "/api/v1/people/contracts/" + third + "/cancel", "{\"version\":0}").andExpect(status().isOk());

        UUID indefinite = id(postJson(w.hr(), "/api/v1/people/contracts/" + secondId + "/convert", "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractTypeCode").value("INDEFINITE"))
                .andExpect(jsonPath("$.startDate").value("2026-01-01"))
                .andExpect(jsonPath("$.endDate").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        sign(w.hr(), "/api/v1/people/contracts/" + indefinite + "/sign", "2025-12-20", 0).andExpect(status().isOk());
        assertThat(events(w.tenant(), "people.ContractChanged")).last()
                .satisfies(e -> assertThat(JsonPath.<String>read(e, "$.subtype")).isEqualTo("CONVERTED"));
    }

    @Test
    void theFirstContractOfAnOpeningLoadContinuesTheImportedDefiniteCount() throws Exception {
        World w = world("ctr-import");
        UUID emp = w.emp().employmentId();
        ContractView c = as(w.hr(), () -> contracts.createInitial(emp, new ContractTerms("DEFINITE", HIRE,
                LocalDate.of(2025, 12, 31), null, null, null, null, null, null, null, null),
                new InitialSource("OPENING_LOAD", 1, "HD-LEGACY-77", LocalDate.of(2024, 12, 20))));
        assertThat(c.status()).isEqualTo("ACTIVE");
        assertThat(c.contractNo()).isEqualTo("HD-LEGACY-77");
        assertThat(c.definiteSeq()).isEqualTo(2);
        assertThat(ADMIN.queryForObject("select headers::text from hr.outbox_event where tenant_id = ? and event_type = 'people.ContractChanged'",
                String.class, w.tenant())).contains("initial_load");
        assertThatThrownBy(() -> as(w.hr(), () -> contracts.createInitial(emp, new ContractTerms("INDEFINITE",
                LocalDate.of(2026, 1, 1), null, null, null, null, null, null, null, null, null), InitialSource.app())))
                .isInstanceOf(ApiException.class).hasMessageContaining("already has a contract");
        postJson(w.hr(), "/api/v1/people/contracts/" + c.id() + "/renew", "{\"endDate\":\"2026-12-31\"}")
                .andExpect(jsonPath("$.code").value("indefinite-contract-required"));
    }

    // ------------------------------------------------------------------------------------- US-PPL-048/049

    @Test
    void probationIsLimitedByLevelOncePerJobAndPaidAtLeastTheLegalFloor() throws Exception {
        World w = world("ctr-probation");
        UUID emp = w.emp().employmentId();
        String probation = "{\"contractTypeCode\":\"PROBATION\",\"startDate\":\"2025-01-01\",\"endDate\":\"%s\"%s}";

        createContract(w.hr(), emp, probation.formatted("2025-03-01", ""))
                .andExpect(jsonPath("$.code").value("probation-level-required"));
        createContract(w.hr(), emp, probation.formatted("2025-03-31", ",\"probationLevelCode\":\"COLLEGE_OR_ABOVE\""))
                .andExpect(jsonPath("$.code").value("probation-too-long")).andExpect(jsonPath("$.maxDays").value(60));
        createContract(w.hr(), emp, probation.formatted("2025-03-01", ",\"probationLevelCode\":\"ASTRONAUT\""))
                .andExpect(jsonPath("$.code").value("invalid-probation-level"));
        createContract(w.hr(), emp, probation.formatted("2025-03-01",
                ",\"probationLevelCode\":\"COLLEGE_OR_ABOVE\",\"probationPayPct\":80"))
                .andExpect(jsonPath("$.code").value("probation-pay-below-minimum"));
        createContract(w.hr(), emp, "{\"contractTypeCode\":\"SEASONAL\",\"startDate\":\"2025-01-01\",\"endDate\":\"2025-01-20\","
                + "\"probationDays\":5,\"probationLevelCode\":\"OTHER\"}")
                .andExpect(jsonPath("$.code").value("probation-not-allowed"));
        createContract(w.hr(), emp, "{\"contractTypeCode\":\"DEFINITE\",\"startDate\":\"2025-01-01\",\"endDate\":\"2025-01-20\","
                + "\"probationDays\":5,\"probationLevelCode\":\"OTHER\"}")
                .andExpect(jsonPath("$.code").value("probation-not-allowed"));

        UUID p = signedContract(w, emp, probation.formatted("2025-03-01",
                ",\"probationLevelCode\":\"COLLEGE_OR_ABOVE\",\"probationPayPct\":85"));
        mvc.perform(get("/api/v1/people/contracts/" + p).with(as(w.hr())))
                .andExpect(jsonPath("$.probationDays").value(60))
                .andExpect(jsonPath("$.probationEndDate").value("2025-03-01"));
        // once per job: a second probation for the same job (here: no job title on either) is refused
        createContract(w.hr(), emp, "{\"contractTypeCode\":\"DEFINITE\",\"startDate\":\"2025-03-02\",\"endDate\":\"2026-03-01\","
                + "\"probationDays\":30,\"probationLevelCode\":\"COLLEGE_OR_ABOVE\"}")
                .andExpect(jsonPath("$.code").value("probation-already-done"));
        createContract(w.hr(), emp, "{\"contractTypeCode\":\"DEFINITE\",\"startDate\":\"2025-03-02\",\"endDate\":\"2026-03-01\","
                + "\"probationDays\":30,\"probationLevelCode\":\"COLLEGE_OR_ABOVE\",\"jobTitleId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isCreated());

        // the salary record's probation percentage is cross-checked too
        Employee other = PPL.employee(w.tenant(), w.le(), w.unit(), "Trần", "Bình", HIRE);
        UUID decision = ADMIN.queryForObject("""
                insert into hr.decision (tenant_id, legal_entity_id, decision_no, decision_type_code, employment_id,
                       effective_date, status) values (?, ?, 'X-1', 'HIRE', ?, ?, 'APPROVED') returning id""",
                UUID.class, w.tenant(), w.le(), other.employmentId(), java.sql.Date.valueOf(HIRE));
        ADMIN.update("""
                insert into hr.salary_record (tenant_id, employment_id, base_salary_amount, probation_pct, action_code,
                       decision_id, valid_from) values (?, ?, 20000000, 70, 'HIRE', ?, ?)""",
                w.tenant(), other.employmentId(), decision, java.sql.Date.valueOf(HIRE));
        createContract(w.hr(), other.employmentId(), probation.formatted("2025-01-30",
                ",\"probationLevelCode\":\"INTERMEDIATE\"")).andExpect(jsonPath("$.code").value("probation-pay-below-minimum"));
    }

    @Test
    void theProbationResultIsRecordedBeforeTheEndAndAPassDraftsTheLabourContract() throws Exception {
        World w = world("ctr-probation-result");
        UUID emp = w.emp().employmentId();
        UUID p = signedContract(w, emp, "{\"contractTypeCode\":\"PROBATION\",\"startDate\":\"2025-01-01\","
                + "\"endDate\":\"2025-03-01\",\"probationLevelCode\":\"COLLEGE_OR_ABOVE\"}");
        String path = "/api/v1/people/contracts/" + p + "/probation-result";

        postJson(w.hr(), path, "{\"result\":\"PASS\",\"resultDate\":\"2025-03-05\"}")
                .andExpect(jsonPath("$.code").value("probation-result-late"));
        postJson(w.hr(), path, "{\"result\":\"PASS\",\"resultDate\":\"2025-02-25\"}")
                .andExpect(jsonPath("$.code").value("next-contract-required"));
        postJson(w.hr(), path, "{\"result\":\"MAYBE\",\"resultDate\":\"2025-02-25\"}")
                .andExpect(jsonPath("$.code").value("invalid-probation-result"));

        String recorded = postJson(w.hr(), path, "{\"result\":\"PASS\",\"resultDate\":\"2025-02-25\",\"signatoryName\":\"Lê Văn Giám\","
                + "\"nextContract\":{\"contractTypeCode\":\"DEFINITE\",\"endDate\":\"2026-03-01\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionNo").value("001/2025/QĐ-LE"))
                .andExpect(jsonPath("$.contract.probationResult").value("PASS"))
                .andExpect(jsonPath("$.nextContract.status").value("DRAFT"))
                .andExpect(jsonPath("$.nextContract.startDate").value("2025-03-02"))
                .andExpect(jsonPath("$.nextContract.previousContractId").value(p.toString()))
                .andExpect(jsonPath("$.nextContract.definiteSeq").value(1))
                .andReturn().getResponse().getContentAsString();
        String event = events(w.tenant(), "people.ProbationCompleted").get(0);
        assertThat(JsonPath.<String>read(event, "$.result")).isEqualTo("PASS");
        assertThat(JsonPath.<String>read(event, "$.probationEndDate")).isEqualTo("2025-03-01");
        assertThat(JsonPath.<String>read(event, "$.decisionId")).isEqualTo(JsonPath.read(recorded, "$.decisionId"));
        assertThat(ADMIN.queryForObject("select decision_type_code from hr.decision where tenant_id = ? and id = ?::uuid",
                String.class, w.tenant(), JsonPath.read(recorded, "$.decisionId"))).isEqualTo("PROBATION_RESULT");
        postJson(w.hr(), path, "{\"result\":\"FAIL\",\"resultDate\":\"2025-02-26\"}").andExpect(status().isConflict());

        // a failed probation clause: event only (the separation flow listens to it)
        Employee other = PPL.employee(w.tenant(), w.le(), w.unit(), "Phạm", "Cường", HIRE);
        UUID c = signedContract(w, other.employmentId(), "{\"contractTypeCode\":\"DEFINITE\",\"startDate\":\"2025-01-01\","
                + "\"endDate\":\"2025-12-31\",\"probationDays\":30,\"probationLevelCode\":\"INTERMEDIATE\"}");
        postJson(w.hr(), "/api/v1/people/contracts/" + c + "/probation-result",
                "{\"result\":\"FAIL\",\"resultDate\":\"2025-01-28\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextContract").doesNotExist());
        assertThat(events(w.tenant(), "people.ProbationCompleted")).last()
                .satisfies(e -> assertThat(JsonPath.<String>read(e, "$.result")).isEqualTo("FAIL"));
    }

    // ------------------------------------------------------------------------------------------- BR-PPL-020

    @Test
    void aContractCannotRunBeyondTheWorkPermitUnlessARenewalIsRecorded() throws Exception {
        World w = world("ctr-permit");
        Employee e = w.emp();
        UUID permit = ADMIN.queryForObject("""
                insert into hr.work_permit (tenant_id, person_id, employment_id, permit_type, permit_no, valid_from,
                       valid_to, status) values (?, ?, ?, 'WORK_PERMIT', 'GP-1', ?, ?, 'ACTIVE') returning id""",
                UUID.class, w.tenant(), e.personId(), e.employmentId(), java.sql.Date.valueOf(HIRE),
                java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)));
        createContract(w.hr(), e.employmentId(), definite("2025-01-01", "2026-06-30"))
                .andExpect(jsonPath("$.code").value("contract-beyond-work-permit"))
                .andExpect(jsonPath("$.permitValidUntil").value("2025-12-31"));
        createContract(w.hr(), e.employmentId(), definite("2025-01-01", "2025-12-31")).andExpect(status().isCreated());
        ADMIN.update("""
                insert into hr.work_permit (tenant_id, person_id, employment_id, permit_type, valid_from, valid_to,
                       renewal_of_id, status) values (?, ?, ?, 'WORK_PERMIT', ?, ?, ?, 'APPLIED')""",
                w.tenant(), e.personId(), e.employmentId(), java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)),
                java.sql.Date.valueOf(LocalDate.of(2028, 1, 1)), permit);
        Employee other = PPL.employee(w.tenant(), w.le(), w.unit(), "Smith", "John", HIRE);
        createContract(w.hr(), e.employmentId(), "{\"contractTypeCode\":\"INDEFINITE\",\"startDate\":\"2026-01-01\"}")
                .andExpect(status().isCreated());
        createContract(w.hr(), other.employmentId(), definite("2025-01-01", "2026-06-30")).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------------------------------- US-PPL-053

    @Test
    void appendicesAmendTermsButNeverTheContractTerm() throws Exception {
        World w = world("ctr-appendix");
        UUID c = signedContract(w, w.emp().employmentId(), definite("2025-01-01", "2026-12-31"));
        String path = "/api/v1/people/contracts/" + c + "/appendices";

        postJson(w.hr(), path, "{\"effectiveDate\":\"2025-07-01\",\"changes\":{\"endDate\":\"2027-12-31\"}}")
                .andExpect(jsonPath("$.code").value("appendix-changes-term"));
        postJson(w.hr(), path, "{\"effectiveDate\":\"2025-07-01\",\"changes\":{\"bonusPlan\":\"x\"}}")
                .andExpect(jsonPath("$.code").value("invalid-appendix-change"));
        postJson(w.hr(), path, "{\"effectiveDate\":\"2027-07-01\",\"changes\":{\"workLocation\":\"Hà Nội\"}}")
                .andExpect(jsonPath("$.code").value("appendix-outside-contract"));
        UUID a = id(postJson(w.hr(), path, "{\"effectiveDate\":\"2025-07-01\",\"changes\":{\"workLocation\":\"Chi nhánh Hà Nội\","
                + "\"salary\":{\"base\":25000000}}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appendixNo").value("001/2025/HĐLĐ-LE/PL01"))
                .andExpect(jsonPath("$.changes.salary.base").value(25000000))
                .andReturn().getResponse().getContentAsString());

        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.CONTRACT_VIEW);
        mvc.perform(get("/api/v1/people/contract-appendices/" + a).with(as(viewer)))
                .andExpect(jsonPath("$.changes.salary").value("***"))
                .andExpect(jsonPath("$.changes.workLocation").value("Chi nhánh Hà Nội"))
                .andExpect(jsonPath("$.payTermsMasked").value(true));
        TestUser noPay = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.CONTRACT_VIEW,
                PeoplePermissions.CONTRACT_UPDATE);
        postJson(noPay, path, "{\"effectiveDate\":\"2025-08-01\",\"changes\":{\"salary\":{\"base\":1}}}")
                .andExpect(status().isNotFound());

        sign(w.hr(), "/api/v1/people/contract-appendices/" + a + "/sign", null, 0)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SIGNED"));
        String event = events(w.tenant(), "people.ContractChanged").stream()
                .filter(e -> e.contains("\"APPENDIX\"")).findFirst().orElseThrow();
        assertThat(JsonPath.<List<String>>read(event, "$.changedTerms")).containsExactly("salary", "workLocation");
        assertThat(event).doesNotContain("25000000");
        assertThat(as(w.hr(), () -> appendices.signedEffectiveOn(w.emp().employmentId(), LocalDate.of(2025, 7, 1))))
                .singleElement().satisfies(v -> assertThat(v.changes()).containsKey("salary"));
        assertThat(as(w.hr(), () -> appendices.signedEffectiveOn(w.emp().employmentId(), LocalDate.of(2025, 6, 30))))
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------- US-PPL-054

    @Test
    void aSuspensionSuspendsTheEmploymentUntilTheReturnToWork() throws Exception {
        World w = world("ctr-suspension");
        UUID emp = w.emp().employmentId();
        UUID c = signedContract(w, emp, "{\"contractTypeCode\":\"INDEFINITE\",\"startDate\":\"2025-01-01\"}");
        String path = "/api/v1/people/contracts/" + c + "/suspensions";

        postJson(w.hr(), path, "{\"reasonCode\":\"HOLIDAY\",\"validFrom\":\"2025-06-01\"}")
                .andExpect(jsonPath("$.code").value("invalid-suspension-reason"));
        String s = postJson(w.hr(), path, "{\"reasonCode\":\"MILITARY_SERVICE\",\"validFrom\":\"2025-06-01\","
                + "\"plannedEndDate\":\"2025-08-31\",\"signatoryName\":\"Lê Văn Giám\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plannedEndDate").value("2025-08-31"))
                .andExpect(jsonPath("$.returnDueDate").value("2025-09-15"))
                .andExpect(jsonPath("$.decisionNo").value("001/2025/QĐ-LE"))
                .andReturn().getResponse().getContentAsString();
        UUID sid = id(s);
        assertThat(ADMIN.queryForObject("select status from hr.employment where id = ?", String.class, emp))
                .isEqualTo("SUSPENDED");
        assertThat(ADMIN.queryForObject("select status from hr.contract where id = ?", String.class, c))
                .isEqualTo("SUSPENDED");
        postJson(w.hr(), path, "{\"reasonCode\":\"DETENTION\",\"validFrom\":\"2025-07-01\"}")
                .andExpect(status().isUnprocessableContent());
        assertThat(events(w.tenant(), "people.ContractChanged")).last()
                .satisfies(e -> {
                    assertThat(JsonPath.<String>read(e, "$.subtype")).isEqualTo("SUSPENDED");
                    assertThat(JsonPath.<String>read(e, "$.suspendedUntil")).isEqualTo("2025-08-31");
                });

        postJson(w.hr(), "/api/v1/people/contract-suspensions/" + sid + "/resume",
                "{\"returnDate\":\"2025-09-03\",\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.returnDate").value("2025-09-03"));
        assertThat(ADMIN.queryForObject("select status from hr.employment where id = ?", String.class, emp))
                .isEqualTo("ACTIVE");
        assertThat(ADMIN.queryForObject("select status from hr.contract where id = ?", String.class, c))
                .isEqualTo("ACTIVE");
        assertThat(events(w.tenant(), "people.ContractChanged")).last()
                .satisfies(e -> assertThat(JsonPath.<String>read(e, "$.subtype")).isEqualTo("RESUMED"));

        // a future suspension waits for its first day; it can be withdrawn before, with its decision
        String future = postJson(w.hr(), path, "{\"reasonCode\":\"AGREEMENT\",\"validFrom\":\""
                + LocalDate.now().plusDays(30) + "\"}")
                .andExpect(jsonPath("$.status").value("APPROVED")).andReturn().getResponse().getContentAsString();
        assertThat(ADMIN.queryForObject("select status from hr.employment where id = ?", String.class, emp))
                .isEqualTo("ACTIVE");
        postJson(w.hr(), "/api/v1/people/contract-suspensions/" + id(future) + "/cancel", "{\"version\":0}")
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(ADMIN.queryForObject("select d.status from hr.decision d join hr.contract_suspension s on s.decision_id = d.id where s.id = ?",
                String.class, id(future))).isEqualTo("CANCELLED");
        mvc.perform(get("/api/v1/people/employments/" + emp + "/contract-suspensions").with(as(w.hr())))
                .andExpect(jsonPath("$.length()").value(2));
    }
}
