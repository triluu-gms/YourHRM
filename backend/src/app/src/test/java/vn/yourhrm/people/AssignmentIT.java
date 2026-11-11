package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.core.api.OrgUnitAssignments;
import vn.yourhrm.people.api.AssignmentJobs;
import vn.yourhrm.people.api.IntegrationInbox;
import vn.yourhrm.people.api.IntegrationInbox.Outcome;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.assignment.AssignmentService;
import vn.yourhrm.people.internal.application.assignment.AssignmentService.Attributes;
import vn.yourhrm.shared.auth.api.spi.ReportingLineProvider;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Date-effective assignments (US-PPL-033..040): bi-temporal history without gaps, job change through approval with
 * decision and event, as-of / as-known, corrections, future-dated changes with the daily job and cancellation,
 * Art. 29 temporary transfers, reason catalogue, scope / tenant isolation, and the reporting-line SPIs for Core.
 */
class AssignmentIT extends AssignmentTestSupport {

    @Autowired
    AssignmentJobs jobs;
    @Autowired
    AssignmentService assignments;
    @Autowired
    ReportingLineProvider reportingLines;
    @Autowired
    OrgUnitAssignments orgUnitAssignments;
    @Autowired
    EmployeeAccess access;
    @Autowired
    IntegrationInbox inbox;

    static String change(String reason, LocalDate effective, UUID orgUnit, String extra) {
        return """
                {"reasonCode":"%s","effectiveDate":"%s","attributes":{"orgUnitId":"%s"%s},"signatoryName":"Giám đốc"}"""
                .formatted(reason, effective, orgUnit, extra == null ? "" : "," + extra);
    }

    String base(Employee e) {
        return "/api/v1/people/employments/" + e.employmentId() + "/assignments";
    }

    @Test
    void aJobChangeIsAppliedOnApprovalWithItsDecisionAndKeepsHistoryWithoutGaps() throws Exception {
        World w = world("asg-change");
        LocalDate d = TODAY.minusDays(10);
        var r = postJson(w.hr(), base(w.emp()) + "/changes", change("TRANSFER", d, w.unit2().id(),
                "\"managerEmploymentId\":\"" + w.manager().employmentId() + "\",\"locationId\":\"" + w.location() + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        UUID request = UUID.fromString(read(r, "$.requestId"));
        // nothing changed yet: the request is invisible to as-of reads
        mvc.perform(get(base(w.emp())).with(as(w.hr())).param("asOf", d.toString()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orgUnitId").value(w.unit().id().toString()));
        assertThat(events(w.tenant(), "people.AssignmentChanged")).isEmpty();
        String knownBefore = ADMIN.queryForObject("select to_char(now() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')",
                String.class);

        decide(w.approver(), request, "APPROVE");
        assertThat(callbackStatus(request)).isEqualTo("ACKED");

        mvc.perform(get(base(w.emp())).with(as(w.hr())).param("asOf", d.minusDays(1).toString()))
                .andExpect(jsonPath("$[0].orgUnitId").value(w.unit().id().toString()))
                .andExpect(jsonPath("$[0].validTo").value(d.toString()));
        mvc.perform(get(base(w.emp())).with(as(w.hr())).param("asOf", d.toString()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orgUnitId").value(w.unit2().id().toString()))
                .andExpect(jsonPath("$[0].validFrom").value(d.toString()))
                .andExpect(jsonPath("$[0].actionCode").value("TRANSFER"))
                .andExpect(jsonPath("$[0].decisionNo").exists())
                .andExpect(jsonPath("$[0].payGroup").doesNotExist())
                .andExpect(jsonPath("$[0].workSchedule").doesNotExist());
        // as known before the approval: the old line, still open-ended
        mvc.perform(get(base(w.emp())).with(as(w.hr())).param("asOf", d.toString()).param("asKnown", knownBefore))
                .andExpect(jsonPath("$[0].orgUnitId").value(w.unit().id().toString()))
                .andExpect(jsonPath("$[0].validTo").doesNotExist());
        // history: the superseded original, the closing copy and the new version
        mvc.perform(get(base(w.emp()) + "/history").with(as(w.hr())))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].knownTo").exists());
        // no gaps / overlaps among the current versions
        List<String> periods = ADMIN.queryForList("""
                select valid_from || '/' || coalesce(valid_to::text, '') from hr.assignment
                 where employment_id = ? and superseded_at is null order by valid_from""", String.class,
                w.emp().employmentId());
        assertThat(periods).containsExactly(HIRE + "/" + d, d + "/");

        List<String> changed = events(w.tenant(), "people.AssignmentChanged");
        assertThat(changed).hasSize(1);
        assertThat((String) JsonPath.read(changed.getFirst(), "$.changeKind")).isEqualTo("CHANGE");
        assertThat((String) JsonPath.read(changed.getFirst(), "$.reasonCode")).isEqualTo("TRANSFER");
        assertThat((String) JsonPath.read(changed.getFirst(), "$.decisionNo")).endsWith("QĐ-LE");
        assertThat((Boolean) JsonPath.read(changed.getFirst(), "$.retro")).isFalse();
        assertThat(ADMIN.queryForObject("select decision_type_code from hr.decision where employment_id = ?",
                String.class, w.emp().employmentId())).isEqualTo("TRANSFER");
    }

    @Test
    void aChangeDatedInAPayPeriodPayrollLockedIsPublishedRetro() throws Exception {
        World w = world("asg-retro");
        LocalDate lastMonthEnd = TODAY.withDayOfMonth(1).minusDays(1);
        LocalDate lastMonthStart = lastMonthEnd.withDayOfMonth(1);
        // Payroll locked last month (period lock intake -> hr.ext_pay_period_lock)
        UUID lockEvent = UUID.randomUUID();
        var lock = new IntegrationInbox.PayPeriodLockChange(w.le(), lastMonthEnd.toString().substring(0, 7),
                lastMonthStart, lastMonthEnd, true);
        assertThat(as(w.hr(), () -> inbox.payPeriodLockChanged(lockEvent, lock))).isEqualTo(Outcome.PROCESSED);
        assertThat(as(w.hr(), () -> inbox.payPeriodLockChanged(lockEvent, lock))).isEqualTo(Outcome.DUPLICATE);
        assertThat(ADMIN.queryForObject("select locked_through from hr.ext_pay_period_lock where tenant_id = ?",
                LocalDate.class, w.tenant())).isEqualTo(lastMonthEnd);

        LocalDate d = lastMonthStart.plusDays(9);
        UUID request = UUID.fromString(read(postJson(w.hr(), base(w.emp()) + "/changes",
                change("TRANSFER", d, w.unit2().id(), null)).andExpect(status().isOk()), "$.requestId"));
        decide(w.approver(), request, "APPROVE");
        List<String> changed = events(w.tenant(), "people.AssignmentChanged");
        assertThat(changed).hasSize(1);
        assertThat((Boolean) JsonPath.read(changed.getFirst(), "$.retro")).isTrue();
        assertThat((String) JsonPath.read(changed.getFirst(), "$.earliestAffectedPeriod"))
                .isEqualTo(d.toString().substring(0, 7));

        // Payroll reopens last month: a later change into it is no longer retro
        assertThat(as(w.hr(), () -> inbox.payPeriodLockChanged(UUID.randomUUID(),
                new IntegrationInbox.PayPeriodLockChange(w.le(), null, lastMonthStart, lastMonthEnd, false))))
                .isEqualTo(Outcome.PROCESSED);
        assertThat(ADMIN.queryForObject("select locked_through from hr.ext_pay_period_lock where tenant_id = ?",
                LocalDate.class, w.tenant())).isEqualTo(lastMonthStart.minusDays(1));
    }

    @Test
    void aRejectedRequestChangesNothing() throws Exception {
        World w = world("asg-reject");
        UUID request = UUID.fromString(read(postJson(w.hr(), base(w.emp()) + "/changes",
                change("PROMOTION", TODAY.minusDays(3), w.unit2().id(), null)), "$.requestId"));
        postJson(w.approver(), "/api/v1/core/workflow/tasks/" + ADMIN.queryForObject("""
                select t.id from core.workflow_task t join core.workflow_instance i on i.id = t.workflow_instance_id
                 where i.subject_id = ? and t.status = 'PENDING'""", UUID.class, request) + "/actions",
                "{\"action\":\"REJECT\",\"comment\":\"Chưa đủ điều kiện\"}").andExpect(status().isNoContent());
        assertThat(callbackStatus(request)).isEqualTo("ACKED");
        mvc.perform(get(base(w.emp()) + "/history").with(as(w.hr()))).andExpect(jsonPath("$", hasSize(1)));
        assertThat(events(w.tenant(), "people.AssignmentChanged")).isEmpty();
    }

    @Test
    void futureChangesWaitForTheDailyJobAndCanBeCancelled() throws Exception {
        World w = world("asg-future");
        LocalDate d1 = TODAY.plusDays(10);
        LocalDate d2 = TODAY.plusDays(20);
        // MANAGER_CHANGE needs no approval: applied directly, effective in the future
        postJson(w.hr(), base(w.emp()) + "/changes", change("MANAGER_CHANGE", d1, w.unit().id(), null))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.assignment.managerEmploymentId").doesNotExist());
        var second = postJson(w.hr(), base(w.emp()) + "/changes", change("COST_CENTRE_CHANGE", d2, w.unit().id(),
                "\"managerEmploymentId\":\"" + w.manager().employmentId() + "\""))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
        UUID secondId = UUID.fromString(read(second, "$.assignment.id"));
        assertThat(events(w.tenant(), "people.AssignmentChanged")).isEmpty();

        assertThat(as(w.hr(), () -> jobs.publishDueChanges(TODAY)).assignmentChanges()).isZero();
        assertThat(as(w.hr(), () -> jobs.publishDueChanges(d1)).assignmentChanges()).isEqualTo(1);
        assertThat(as(w.hr(), () -> jobs.publishDueChanges(d1)).assignmentChanges()).isZero();   // idempotent
        assertThat((String) JsonPath.read(events(w.tenant(), "people.AssignmentChanged").getFirst(), "$.effectiveDate"))
                .isEqualTo(d1.toString());

        // the second change is cancelled before its date: the previous version runs on
        postJson(w.hr(), base(w.emp()) + "/" + secondId + "/cancellation", "{\"reason\":\"Kế hoạch thay đổi\"}")
                .andExpect(status().isOk());
        assertThat(events(w.tenant(), "people.AssignmentChangeCancelled")).hasSize(1);
        mvc.perform(get(base(w.emp())).with(as(w.hr())).param("asOf", d2.toString()))
                .andExpect(jsonPath("$[0].validFrom").value(d1.toString()))
                .andExpect(jsonPath("$[0].validTo").doesNotExist());
        assertThat(as(w.hr(), () -> jobs.publishDueChanges(d2)).assignmentChanges()).isZero();
        // an effective change cannot be cancelled
        UUID first = UUID.fromString(JsonPath.read(body(mvc.perform(get(base(w.emp())).with(as(w.hr()))
                .param("asOf", HIRE.toString()))), "$[0].id"));
        postJson(w.hr(), base(w.emp()) + "/" + first + "/cancellation", "{\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("change-already-effective"));
    }

    @Test
    void aCorrectionKeepsTheSupersededVersionWithItsReason() throws Exception {
        World w = world("asg-correct");
        UUID current = UUID.fromString(JsonPath.read(body(mvc.perform(get(base(w.emp())).with(as(w.hr())))), "$[0].id"));
        var r = postJson(w.hr(), base(w.emp()) + "/" + current + "/corrections",
                "{\"reason\":\"Nhập sai đơn vị\",\"attributes\":{\"orgUnitId\":\"" + w.unit2().id()
                        + "\",\"managerEmploymentId\":\"" + w.manager().employmentId() + "\",\"fte\":0.5}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validFrom").value(HIRE.toString()))
                .andExpect(jsonPath("$.correctionReason").value("Nhập sai đơn vị"));
        assertThat((Double) read(r, "$.fte")).isEqualTo(0.5);
        mvc.perform(get(base(w.emp()) + "/history").with(as(w.hr())))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].correctionReason").value("Nhập sai đơn vị"))
                .andExpect(jsonPath("$[0].supersededBy").exists());
        // correcting a superseded version is refused
        postJson(w.hr(), base(w.emp()) + "/" + current + "/corrections",
                "{\"reason\":\"again\",\"attributes\":{\"orgUnitId\":\"" + w.unit().id() + "\"}}")
                .andExpect(status().isConflict());
        assertThat((String) JsonPath.read(events(w.tenant(), "people.AssignmentChanged").getFirst(), "$.changeKind"))
                .isEqualTo("CORRECTION");
    }

    @Test
    void invalidRequestsAreRefused() throws Exception {
        World w = world("asg-invalid");
        LocalDate d = TODAY.minusDays(5);
        postJson(w.hr(), base(w.emp()) + "/changes", change("TRANSFER", d, w.unit2().id(),
                "\"costCentreId\":null,\"costSplit\":[{\"costCentreId\":\"" + UUID.randomUUID() + "\",\"pct\":60},"
                        + "{\"costCentreId\":\"" + UUID.randomUUID() + "\",\"pct\":30}]"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-cost-split"));
        postJson(w.hr(), base(w.emp()) + "/changes", change("SECONDMENT", d, w.unit2().id(), null))
                .andExpect(jsonPath("$.code").value("invalid-change-reason"));
        postJson(w.hr(), base(w.emp()) + "/changes", change("TRANSFER", HIRE, w.unit2().id(), null))
                .andExpect(jsonPath("$.code").value("assignment-version-exists"));
        postJson(w.hr(), base(w.emp()) + "/changes", change("TRANSFER", d, w.unit2().id(),
                "\"managerEmploymentId\":\"" + w.emp().employmentId() + "\""))
                .andExpect(jsonPath("$.code").value("invalid-manager"));
        postJson(w.hr(), base(w.emp()) + "/changes", change("TRANSFER", d, w.unit2().id(),
                "\"jobTitleId\":\"" + UUID.randomUUID() + "\""))
                .andExpect(jsonPath("$.code").value("unknown-job-title"));
        postJson(w.hr(), base(w.emp()) + "/changes", change("TRANSFER", d, w.unit2().id(),
                "\"locationId\":\"" + UUID.randomUUID() + "\""))
                .andExpect(jsonPath("$.code").value("unknown-location"));
    }

    @Test
    void scopePermissionAndTenantAreEnforced() throws Exception {
        World w = world("asg-scope");
        World other = world("asg-scope-b");
        TestUser unit2Hr = PPL.userWith(w.tenant(), "ORG_UNIT", List.of(w.unit2().id()), PeoplePermissions.EMPLOYEE_VIEW,
                PeoplePermissions.ASSIGNMENT_UPDATE);
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(base(w.emp())).with(as(unit2Hr))).andExpect(status().isNotFound());
        postJson(unit2Hr, base(w.emp()) + "/changes", change("TRANSFER", TODAY, w.unit2().id(), null))
                .andExpect(status().isNotFound());
        mvc.perform(get(base(w.emp())).with(as(viewer))).andExpect(status().isOk());
        postJson(viewer, base(w.emp()) + "/changes", change("TRANSFER", TODAY, w.unit2().id(), null))
                .andExpect(status().isForbidden());
        // another tenant's employment is not found
        mvc.perform(get(base(other.emp())).with(as(w.hr()))).andExpect(status().isNotFound());
    }

    @Test
    void temporaryTransfersFollowArticle29() throws Exception {
        World w = world("asg-temp");
        LocalDate start = TODAY.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
        LocalDate shortEnd = start.plusDays(13);
        String noticeToday = ",\"noticeDate\":\"" + TODAY + "\"";
        // notice: told on the Saturday before → too short
        LocalDate lateNotice = start.minusDays(2);
        postJson(w.hr(), base(w.emp()) + "/changes", change("TEMPORARY_TRANSFER", start, w.unit2().id(), null)
                .replace("\"signatoryName\"", "\"endDate\":\"" + shortEnd + "\",\"noticeDate\":\"" + lateNotice
                        + "\",\"signatoryName\""))
                .andExpect(jsonPath("$.code").value("temp-transfer-notice-too-short"));
        // without an end date
        postJson(w.hr(), base(w.emp()) + "/changes", change("TEMPORARY_TRANSFER", start, w.unit2().id(), null))
                .andExpect(jsonPath("$.code").value("temp-transfer-end-required"));
        // longer than the yearly limit without consent (keep it within one calendar year when possible)
        // 150 calendar days exceed the yearly limit of 60 working days in at least one calendar year
        LocalDate longEnd = start.plusDays(150);
        String longBody = change("TEMPORARY_TRANSFER", start, w.unit2().id(), null)
                .replace("\"signatoryName\"", "\"endDate\":\"" + longEnd + "\"" + noticeToday + ",\"signatoryName\"");
        postJson(w.hr(), base(w.emp()) + "/changes", longBody)
                .andExpect(jsonPath("$.code").value("temp-transfer-consent-required"));
        UUID consent = FIX.document(w.tenant(), "Văn bản đồng ý");
        ADMIN.update("update core.document set owner_entity_id = ?, legal_entity_id = ? where id = ?", w.le(), w.le(),
                consent);
        var ok = postJson(w.hr(), base(w.emp()) + "/changes", longBody.replace("\"signatoryName\"",
                "\"consentDocumentId\":\"" + consent + "\",\"signatoryName\""))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        UUID request = UUID.fromString(read(ok, "$.requestId"));
        decide(w.approver(), request, "APPROVE");
        mvc.perform(get(base(w.emp())).with(as(w.hr())).param("asOf", start.toString()))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.assignmentType=='TEMP_TRANSFER')].validTo").value(longEnd.plusDays(1).toString()));
        // overlapping transfer refused
        postJson(w.hr(), base(w.emp()) + "/changes", change("TEMPORARY_TRANSFER", start.plusDays(7), w.unit2().id(),
                null).replace("\"signatoryName\"", "\"endDate\":\"" + start.plusDays(9) + "\"" + noticeToday
                        + ",\"signatoryName\""))
                .andExpect(jsonPath("$.code").value("temp-transfer-overlap"));
        // events when the transfer starts and ends
        assertThat(as(w.hr(), () -> jobs.publishDueChanges(start)).temporaryTransfersStarted()).isEqualTo(1);
        List<String> started = events(w.tenant(), "people.TemporaryTransferChanged");
        assertThat(started).hasSize(1);
        assertThat((String) JsonPath.read(started.getFirst(), "$.status")).isEqualTo("STARTED");
        assertThat((Integer) JsonPath.read(started.getFirst(), "$.wageProtection.previousWageWorkingDays")).isEqualTo(30);
        assertThat((String) JsonPath.read(started.getFirst(), "$.consentDocumentId")).isEqualTo(consent.toString());
        assertThat((Integer) JsonPath.read(started.getFirst(), "$.workingDaysInYear")).isPositive();
        LocalDate after = longEnd.plusDays(1);
        assertThat(as(w.hr(), () -> jobs.publishDueChanges(after)).temporaryTransfersEnded()).isEqualTo(1);
        assertThat((String) JsonPath.read(events(w.tenant(), "people.TemporaryTransferChanged").get(1), "$.status"))
                .isEqualTo("ENDED");
    }

    @Test
    void tenantsCanDisableAndAddChangeReasons() throws Exception {
        World w = world("asg-reasons");
        ADMIN.update("""
                insert into core.setting_value (tenant_id, setting_key, value)
                values (?, 'hr.assignment.change_reasons',
                        '[{"code":"MANAGER_CHANGE","active":false},
                          {"code":"SECONDMENT","active":true},
                          {"code":"TEAM_MOVE","nameVi":"Chuyển nhóm","actionCode":"TRANSFER","documentKind":"NONE"}]')""",
                w.tenant());
        mvc.perform(get("/api/v1/people/assignment-change-reasons").with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.code=='MANAGER_CHANGE')].active").value(false))
                .andExpect(jsonPath("$[?(@.code=='SECONDMENT')].active").value(false))
                .andExpect(jsonPath("$[?(@.code=='TRANSFER')].processTypeCode").value("hr.JOB_CHANGE"))
                .andExpect(jsonPath("$[?(@.code=='TEAM_MOVE')].tenantDefined").value(true));
        postJson(w.hr(), base(w.emp()) + "/changes", change("MANAGER_CHANGE", TODAY, w.unit().id(), null))
                .andExpect(jsonPath("$.code").value("invalid-change-reason"));
        postJson(w.hr(), base(w.emp()) + "/changes", change("TEAM_MOVE", TODAY.minusDays(1), w.unit2().id(), null))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.decisionId").doesNotExist());
        assertThat(events(w.tenant(), "people.AssignmentChanged")).hasSize(1);
    }

    @Test
    void theReportingLineFeedsCoresReportScopes() throws Exception {
        World w = world("asg-lines");
        Employee sub = PPL.employee(w.tenant(), w.le(), w.unit2(), w.emp(), "Lê", "Bình", HIRE);
        assertThat(as(w.hr(), () -> reportingLines.directReports(w.tenant(), w.manager().employmentId(), TODAY)))
                .containsExactly(w.emp().employmentId());
        assertThat(as(w.hr(), () -> reportingLines.allReports(w.tenant(), w.manager().employmentId(), TODAY)))
                .containsExactlyInAnyOrder(w.emp().employmentId(), sub.employmentId());
        assertThat(as(w.hr(), () -> orgUnitAssignments.hasActiveAssignments(w.unit2().id(), TODAY))).isTrue();
        assertThat(as(w.hr(), () -> orgUnitAssignments.hasActiveAssignments(
                FIX.orgUnit(w.tenant(), "EMPTY", null, w.le(), null, HIRE, null).id(), TODAY))).isFalse();

        // a DIRECT_REPORTS grant of the manager's user now covers their report, not the report's report
        TestUser managerUser = PPL.userWith(w.tenant(), "DIRECT_REPORTS", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        PPL.link(managerUser, w.manager());
        mvc.perform(get(base(w.emp())).with(as(managerUser))).andExpect(status().isOk());
        mvc.perform(get(base(sub)).with(as(managerUser))).andExpect(status().isNotFound());
        TestUser allReports = PPL.userWith(w.tenant(), "ALL_REPORTS", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        PPL.link(allReports, w.manager());
        mvc.perform(get(base(sub)).with(as(allReports))).andExpect(status().isOk());
    }

    @Test
    void createInitialIsTheHireStreamsEntryPoint() throws Exception {
        World w = world("asg-initial");
        UUID person = PPL.person(w.tenant(), "Phạm", "Mới", null);
        UUID employment = PPL.employment(w.tenant(), person, w.le(), "NEW1", TODAY.plusDays(5), "PRE_HIRE");
        var view = as(w.hr(), () -> new org.springframework.transaction.support.TransactionTemplate(hrTx)
                .execute(s -> assignments.createInitial(employment, new Attributes(w.unit().id(), null, null, null,
                        w.manager().employmentId(), w.location(), null, List.of(), null), TODAY.plusDays(5), null,
                        "APP")));
        assertThat(view.actionCode()).isEqualTo("HIRE");
        assertThat(events(w.tenant(), "people.AssignmentChanged")).isEmpty();
        as(w.hr(), () -> jobs.publishDueChanges(TODAY.plusDays(5)));
        assertThat((String) JsonPath.read(events(w.tenant(), "people.AssignmentChanged").getFirst(), "$.changeKind"))
                .isEqualTo("HIRE");
    }

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier(vn.yourhrm.people.internal.infra.HrDb.TX)
    org.springframework.transaction.PlatformTransactionManager hrTx;
}
