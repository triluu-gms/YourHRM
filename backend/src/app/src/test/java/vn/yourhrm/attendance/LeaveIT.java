package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.LeaveSettlements;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.application.schedule.AssignmentProjection;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Leave management (BRD 03 §4.6: BR-ATT-055..063, 065, 067, 068): statutory types, policies with the statutory floor,
 * requests with duration, balance reservation and overlap checks, approval through Core's workflow into the ledger
 * and the timesheet, SI claim data, maternity early return, accrual, year end, carried-day expiry, the leaver
 * settlement and the opening-balance import.
 */
class LeaveIT extends IntegrationTest {

    static final String BASE = "/api/v1/attendance";
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalDate TODAY = LocalDate.now(VN);
    /** A Monday of the previous month (its timesheet partition exists, and the whole week is in the past). */
    static final LocalDate MON = TODAY.minusMonths(1).withDayOfMonth(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    static final LocalDate HIRE = LocalDate.of(2020, 1, 1);

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);

    @Autowired
    AssignmentProjection projection;

    @Autowired
    DocumentScanResults scans;

    @Autowired
    LeaveSettlements settlements;

    record World(UUID tenant, TestUser admin, TestUser ess, TestUser manager, UUID le, Employee emp,
            String employeeNo) {
        UUID e() {
            return emp.employmentId();
        }
    }

    World world(String slug) throws Exception {
        UUID tenant = FIX.tenant(slug);
        List<String> perms = new ArrayList<>(FIX.allCorePermissions());
        perms.addAll(PPL.allHrPermissions());
        perms.addAll(List.of(AttendancePermissions.SCHEDULE_CONFIG_VIEW, AttendancePermissions.SCHEDULE_CONFIG_UPDATE,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE,
                AttendancePermissions.TIMESHEET_VIEW, AttendancePermissions.TIMESHEET_UPDATE,
                AttendancePermissions.LEAVE_TYPE_VIEW, AttendancePermissions.LEAVE_TYPE_UPDATE,
                AttendancePermissions.LEAVE_REQUEST_VIEW, AttendancePermissions.LEAVE_REQUEST_CREATE,
                AttendancePermissions.LEAVE_REQUEST_MANAGE, AttendancePermissions.LEAVE_SI_DATA_VIEW,
                AttendancePermissions.LEAVE_BALANCE_VIEW, AttendancePermissions.LEAVE_BALANCE_ADJUST,
                AttendancePermissions.LEAVE_BALANCE_IMPORT));
        UUID role = FIX.role(tenant, "TA_ADMIN", false, perms.toArray(String[]::new));
        TestUser admin = FIX.user(tenant, "ta-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(admin, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        UUID le = FIX.legalEntity(tenant, "LE1");
        var unit = FIX.orgUnit(tenant, "U1", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Trần", "Hoa", HIRE);
        String no = ADMIN.queryForObject("select employee_no from hr.employment where id = ?", String.class,
                emp.employmentId());
        TestUser ess = PPL.userWith(tenant, "SELF", List.of(), AttendancePermissions.LEAVE_REQUEST_CREATE,
                AttendancePermissions.LEAVE_REQUEST_VIEW, AttendancePermissions.LEAVE_BALANCE_VIEW);
        PPL.link(ess, emp);
        TestUser manager = PPL.userWith(tenant, "TENANT", List.of(), AttendancePermissions.LEAVE_REQUEST_VIEW);
        ADMIN.update("update core.org_unit_version set manager_user_id = ? where tenant_id = ? and org_unit_id = ?",
                manager.userId(), tenant, unit.id());
        Map<String, Object> lps = Map.of(AttendanceLegalParameters.BREAK_DUE_AFTER_MIN, 360,
                AttendanceLegalParameters.BREAK_MIN_DAY, 30, AttendanceLegalParameters.BREAK_MIN_NIGHT, 45,
                AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY, 8, AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK, 48,
                AttendanceLegalParameters.NIGHT_START, "22:00", AttendanceLegalParameters.NIGHT_END, "06:00",
                AttendanceLegalParameters.LEAVE_ANNUAL_BASE_DAYS, 12,
                AttendanceLegalParameters.LEAVE_ANNUAL_PROTECTED_DAYS, 14,
                AttendanceLegalParameters.LEAVE_SENIORITY_STEP_YEARS, 5);
        lps.forEach((k, v) -> TestLegalParameters.define(tenant, k, v));
        TestLegalParameters.define(tenant, AttendanceLegalParameters.LEAVE_SENIORITY_STEP_DAYS, 1);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.LEAVE_MATERNITY_MIN_MONTHS, 4);
        TestLegalParameters.define(tenant, AttendanceLegalParameters.LEAVE_PERSONAL_DAYS,
                Map.of("MARRIAGE_SELF", 3, "MARRIAGE_CHILD", 1, "BEREAVEMENT", 3, "UNPAID_FAMILY", 1));
        TestLegalParameters.define(tenant, AttendanceLegalParameters.LEAVE_SERVICE_COUNTED_CAPS,
                Map.of("SICK", 60, "UNPAID_AGREED", 30));
        ADMIN.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       status, worker_category_code, source_event_id, source_version)
                values (?, ?, ?, ?, ?, 'ACTIVE', 'EMPLOYEE', ?, 1)""",
                tenant, emp.employmentId(), emp.personId(), le, HIRE, Ids.newId());
        TenantContext ctx = new TenantContext(tenant, admin.userId(), admin.identityId().toString(), "it", "vi");
        TenantContext.runWith(ctx, () -> projection.apply(new LineVersion(Ids.newId(), emp.assignmentKey(),
                emp.employmentId(), emp.personId(), "PRIMARY", le, unit.id(), null, null, null, null, null, "STAFF",
                "FULL_TIME", BigDecimal.ONE, HIRE, null), false));
        World w = new World(tenant, admin, ess, manager, le, emp, no);
        schedule(w);
        postJson(admin, "/leave-types/statutory", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.created", hasItem("ANNUAL"))).andExpect(jsonPath("$.warnings").isEmpty());
        return w;
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    /** Office shift Mon-Fri 08:00-17:00, weekends REST, assigned from 2020-01-06. */
    void schedule(World w) throws Exception {
        postJson(w.admin(), "/shifts", """
                {"code":"HC","nameVi":"Hành chính","startTime":"08:00","endTime":"17:00",
                 "breaks":[{"start":"12:00","end":"13:00"}],"graceInMin":5,"graceOutMin":5}""")
                .andExpect(status().isCreated());
        String day = "{\"dayIndex\":%d,\"dayKind\":\"WORK\",\"shiftCode\":\"HC\"}";
        String days = "[" + String.format(day, 0) + "," + String.format(day, 1) + "," + String.format(day, 2) + ","
                + String.format(day, 3) + "," + String.format(day, 4)
                + ",{\"dayIndex\":5,\"dayKind\":\"REST\"},{\"dayIndex\":6,\"dayKind\":\"REST\"}]";
        LocalDate anchor = LocalDate.of(2020, 1, 6);
        UUID pattern = UUID.fromString(JsonPath.read(body(postJson(w.admin(), "/schedule-patterns", """
                {"code":"T2T6","nameVi":"Thứ 2 - thứ 6","cycleLengthDays":7,"anchorDate":"%s","days":%s}"""
                .formatted(anchor, days)).andExpect(status().isCreated())), "$.pattern.id"));
        postJson(w.admin(), "/employments/" + w.e() + "/schedule-assignments", """
                {"assignmentKey":"%s","patternId":"%s","validFrom":"%s"}""".formatted(w.emp().assignmentKey(),
                pattern, MON.minusMonths(7).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))))
                .andExpect(status().isCreated());
    }

    UUID typeId(World w, String code) {
        return ADMIN.queryForObject("select id from att.leave_type where tenant_id = ? and code = ?", UUID.class,
                w.tenant(), code);
    }

    void annualPolicy(World w) throws Exception {
        postJson(w.admin(), "/leave-policies", """
                {"leaveTypeId":"%s","code":"AN-ALL","accrualMethod":"MONTHLY","carryOverMaxDays":5,
                 "carryOverExpiryMonths":3,"expiryAction":"FORFEIT","validFrom":"2020-01-01"}"""
                .formatted(typeId(w, "ANNUAL"))).andExpect(status().isCreated());
    }

    /** Approves the pending workflow task of a request as the line manager. */
    void approve(World w, UUID requestId) throws Exception {
        UUID task = ADMIN.queryForObject("""
                select t.id from core.workflow_task t
                  join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                 where i.subject_id = ? and t.status = 'PENDING'""", UUID.class, requestId);
        mvc.perform(post("/api/v1/core/workflow/tasks/" + task + "/actions").with(as(w.manager()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\",\"comment\":\"ok\"}"))
                .andExpect(status().isNoContent());
    }

    UUID request(World w, TestUser who, String json) throws Exception {
        return UUID.fromString(JsonPath.read(body(postJson(who, "/employments/" + w.e() + "/leave-requests", json)
                .andExpect(status().isCreated())), "$.id"));
    }

    String requestStatus(UUID id) {
        return ADMIN.queryForObject("select status from att.leave_request where id = ?", String.class, id);
    }

    BigDecimal ledgerSum(World w, String txn, int year) {
        return ADMIN.queryForObject("""
                select coalesce(sum(quantity), 0) from att.leave_balance_ledger
                 where tenant_id = ? and employment_id = ? and txn_type = ? and leave_year = ?""",
                BigDecimal.class, w.tenant(), w.e(), txn, year);
    }

    // -------------------------------------------------------------------------------------------------- tests

    @Test
    void requestsReserveTheBalanceAndApprovalTakesItAndMarksTheDays() throws Exception {
        World w = world("att-lv-req");
        // a policy may grant more than the statutory 12 days, never less
        postJson(w.admin(), "/leave-policies", """
                {"leaveTypeId":"%s","code":"LOW","baseEntitlementDays":10,"validFrom":"2020-01-01"}"""
                .formatted(typeId(w, "ANNUAL"))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("below-statutory-floor"));
        annualPolicy(w);
        int year = MON.getYear();
        // HR adjusts the balance: a reason is mandatory
        String adj = """
                {"leaveTypeId":"%s","leaveYear":%d,"quantity":3%s}""";
        postJson(w.admin(), "/employments/" + w.e() + "/leave-adjustments",
                adj.formatted(typeId(w, "ANNUAL"), year, "")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("reason-required"));
        postJson(w.admin(), "/employments/" + w.e() + "/leave-adjustments",
                adj.formatted(typeId(w, "ANNUAL"), year, ",\"reason\":\"Phép thưởng\"")).andExpect(status().isCreated());

        // the duration skips the weekend: Friday + Monday morning = 1.5 days
        LocalDate fri = MON.plusDays(4);
        String friToMon = """
                {"leaveTypeCode":"ANNUAL","startDate":"%s","endDate":"%s","endPart":"AM","reason":"Việc gia đình"}"""
                .formatted(fri, fri.plusDays(3));
        postJson(w.ess(), "/employments/" + w.e() + "/leave-requests/preview", friToMon).andExpect(status().isOk())
                .andExpect(jsonPath("$.durationDays").value(1.5)).andExpect(jsonPath("$.requestUnit").value("HALF_DAY"));
        UUID first = request(w, w.ess(), friToMon);
        assertThat(requestStatus(first)).isEqualTo("PENDING");
        mvc.perform(get(BASE + "/employments/" + w.e() + "/leave-balances").param("asOf", MON.toString())
                        .with(as(w.ess())))
                .andExpect(jsonPath("$[?(@.leaveTypeCode == 'ANNUAL')].pending").value(hasItem(1.5)))
                .andExpect(jsonPath("$[?(@.leaveTypeCode == 'ANNUAL')].available").value(hasItem(1.5)));

        // pending days are reserved: 3 more days do not fit; the same days twice overlap
        postJson(w.ess(), "/employments/" + w.e() + "/leave-requests", """
                {"leaveTypeCode":"ANNUAL","startDate":"%s","endDate":"%s"}""".formatted(MON, MON.plusDays(2)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("insufficient-balance"));
        postJson(w.ess(), "/employments/" + w.e() + "/leave-requests", """
                {"leaveTypeCode":"ANNUAL","startDate":"%s"}""".formatted(fri))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("leave-overlap"));

        // approval by the line manager: TAKE in the ledger, the days become leave in the timesheet
        approve(w, first);
        assertThat(requestStatus(first)).isEqualTo("APPROVED");
        assertThat(ledgerSum(w, "TAKE", LeaveRules.leaveYearOf(fri, 1))).isEqualByComparingTo("-1.5");
        if (!fri.isAfter(TODAY.minusDays(1))) {
            assertThat(ADMIN.queryForObject("""
                    select primary_code from att.daily_timesheet where tenant_id = ? and employment_id = ?
                       and work_date = ?""", String.class, w.tenant(), w.e(), fri)).isEqualTo("P");
        }

        // a pending request can be withdrawn; an approved one cannot be cancelled in phase 1
        UUID second = request(w, w.ess(), """
                {"leaveTypeCode":"ANNUAL","startDate":"%s"}""".formatted(MON.plusDays(1)));
        postJson(w.ess(), "/leave-requests/" + second + "/withdraw", "{\"reason\":\"Đổi kế hoạch\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        postJson(w.ess(), "/leave-requests/" + first + "/withdraw", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cancel-approved-not-supported"));
        // an approved leave event went out
        assertThat(ADMIN.queryForObject("""
                select count(*) from att.outbox_event where tenant_id = ? and event_type = 'att.AbsenceChanged'""",
                Integer.class, w.tenant())).isEqualTo(1);
    }

    @Test
    void siLeaveCarriesEncryptedClaimDataAndMaternityCanEndEarly() throws Exception {
        World w = world("att-lv-si");
        String sick = """
                {"leaveTypeCode":"SICK","startDate":"%s","endDate":"%s"%s}""";
        postJson(w.ess(), "/employments/" + w.e() + "/leave-requests", sick.formatted(MON, MON.plusDays(1), ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("attachment-required"));
        String att = ",\"attachmentDocumentIds\":[\"" + Ids.newId() + "\"]";
        postJson(w.ess(), "/employments/" + w.e() + "/leave-requests", sick.formatted(MON, MON.plusDays(1), att))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("si-claim-required"));
        UUID id = request(w, w.ess(), sick.formatted(MON, MON.plusDays(1), att + """
                ,"siClaim":{"certificateType":"C65","certificateNo":"SO123456789","diagnosisCategory":"J10",
                 "treatmentType":"OUTPATIENT"}"""));
        Map<String, Object> row = ADMIN.queryForMap("""
                select si_certificate_no_last4, si_certificate_no_enc, si_diagnosis_category_enc, duration_days
                  from att.leave_request where id = ?""", id);
        assertThat(row).containsEntry("si_certificate_no_last4", "6789");
        assertThat(new String((byte[]) row.get("si_certificate_no_enc"))).doesNotContain("SO123456789");
        assertThat((BigDecimal) row.get("duration_days")).isEqualByComparingTo("2");
        mvc.perform(get(BASE + "/leave-requests/" + id).with(as(w.ess())))
                .andExpect(jsonPath("$.siCertificateNoLast4").value("6789"))
                .andExpect(jsonPath("$.hasSiClaimData").value(true));
        mvc.perform(get(BASE + "/leave-requests/" + id + "/si-claim").with(as(w.ess())))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/leave-requests/" + id + "/si-claim").with(as(w.admin())))
                .andExpect(jsonPath("$.certificateNo").value("SO123456789"))
                .andExpect(jsonPath("$.diagnosisCategory").value("J10"));

        // paternity is for men (gender from People, visible to HR)
        postJson(w.admin(), "/employments/" + w.e() + "/leave-requests", """
                {"leaveTypeCode":"PATERNITY","startDate":"%s"%s,"siClaim":{"certificateType":"BIRTH"}}"""
                .formatted(MON.plusDays(2), att)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("not-eligible-gender"));

        // maternity counts calendar days; an early return needs 4 months of leave
        LocalDate start = MON.minusMonths(8); // ends before the sick leave above
        UUID mat = request(w, w.ess(), """
                {"leaveTypeCode":"MATERNITY","startDate":"%s","endDate":"%s"%s,
                 "siClaim":{"certificateType":"BIRTH","confinementDate":"%s","childrenBornCount":1}}"""
                .formatted(start, start.plusMonths(6).minusDays(1), att, start.plusDays(3)));
        assertThat(ADMIN.queryForObject("select duration_days from att.leave_request where id = ?", BigDecimal.class,
                mat)).isEqualByComparingTo(String.valueOf(start.plusMonths(6).toEpochDay() - start.toEpochDay()));
        approve(w, mat);
        postJson(w.admin(), "/leave-requests/" + mat + "/early-return", """
                {"returnDate":"%s"}""".formatted(start.plusMonths(2))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("too-early"));
        postJson(w.admin(), "/leave-requests/" + mat + "/early-return", """
                {"returnDate":"%s"}""".formatted(start.plusMonths(4))).andExpect(status().isOk())
                .andExpect(jsonPath("$.actualReturnDate").value(start.plusMonths(4).toString()));
        assertThat(ADMIN.queryForObject("""
                select count(*) from att.outbox_event where tenant_id = ? and event_type = 'att.AbsenceChanged'
                   and payload ->> 'status' = 'SHORTENED'""", Integer.class, w.tenant())).isEqualTo(1);
    }

    @Test
    void accrualYearEndExpiryAndTheLeaverSettlement() throws Exception {
        World w = world("att-lv-bal");
        annualPolicy(w);
        UUID annual = typeId(w, "ANNUAL");
        // monthly accrual of a finished month: 12 + 1 seniority day (5+ years) = 13 a year, idempotent
        YearMonth m = YearMonth.from(TODAY).minusMonths(2);
        String acc = "{\"legalEntityId\":\"%s\",\"month\":\"%s\"}".formatted(w.le(), m);
        postJson(w.admin(), "/leave-balances/accrual", acc).andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(1));
        postJson(w.admin(), "/leave-balances/accrual", acc).andExpect(jsonPath("$.written").value(0));
        assertThat(ledgerSum(w, "ACCRUE", m.getYear()))
                .isEqualByComparingTo(LeaveRules.monthlyStep(new BigDecimal("13"), m.getMonthValue()));

        // year end two years back: 4 days carried (max 5), expiring 3 months into the next year
        int y2 = TODAY.getYear() - 2;
        int y1 = TODAY.getYear() - 1;
        String adj = "{\"leaveTypeId\":\"%s\",\"leaveYear\":%d,\"quantity\":%s,\"reason\":\"Số dư\"}";
        postJson(w.admin(), "/employments/" + w.e() + "/leave-adjustments", adj.formatted(annual, y2, "4"))
                .andExpect(status().isCreated());
        String ye = "{\"legalEntityId\":\"%s\",\"year\":%d}";
        postJson(w.admin(), "/leave-balances/year-end", ye.formatted(w.le(), y2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(2));
        assertThat(ledgerSum(w, "CARRY", y1)).isEqualByComparingTo("4");
        // nothing taken before the expiry: the carried days expire
        postJson(w.admin(), "/leave-balances/carry-expiry", "{\"legalEntityId\":\"%s\"}".formatted(w.le()))
                .andExpect(jsonPath("$.written").value(1));
        assertThat(ledgerSum(w, "EXPIRE", y1)).isEqualByComparingTo("-4");
        postJson(w.admin(), "/leave-balances/carry-expiry", "{\"legalEntityId\":\"%s\"}".formatted(w.le()))
                .andExpect(jsonPath("$.written").value(0));
        // last year: 8 days left, 5 carried, 3 forfeited; a re-run writes nothing
        postJson(w.admin(), "/employments/" + w.e() + "/leave-adjustments", adj.formatted(annual, y1, "8"))
                .andExpect(status().isCreated());
        postJson(w.admin(), "/leave-balances/year-end", ye.formatted(w.le(), y1))
                .andExpect(jsonPath("$.written").value(3));
        assertThat(ledgerSum(w, "CARRY", TODAY.getYear())).isEqualByComparingTo("5");
        assertThat(ledgerSum(w, "EXPIRE", y1)).isEqualByComparingTo("-7");
        postJson(w.admin(), "/leave-balances/year-end", ye.formatted(w.le(), y1))
                .andExpect(jsonPath("$.written").value(0));

        // the leaver: no leave after the last working day; the settlement pays out what is owed
        LocalDate lwd = MON.plusDays(4);
        ADMIN.update("update att.ext_hr_employment set last_working_date = ?, termination_date = ? "
                + "where employment_id = ?", lwd, lwd, w.e());
        postJson(w.ess(), "/employments/" + w.e() + "/leave-requests", """
                {"leaveTypeCode":"ANNUAL","startDate":"%s"}""".formatted(lwd.plusDays(3)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("outside-employment"));
        var s = TenantContext.callWith(new TenantContext(w.tenant(), w.admin().userId(),
                w.admin().identityId().toString(), "it", "vi"), () -> settlements.settlement(w.e()));
        assertThat(s.lastWorkingDate()).isEqualTo(lwd);
        var line = s.lines().stream().filter(l -> l.leaveTypeCode().equals("ANNUAL")).findFirst().orElseThrow();
        assertThat(line.finalised()).isFalse();
        // owed: the 5 carried days + the year's entitlement prorated to the LWD
        assertThat(line.remaining()).isGreaterThan(new BigDecimal("5"));
        postJson(w.admin(), "/employments/" + w.e() + "/leave-settlement", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.leaveTypeCode == 'ANNUAL')].finalised").value(hasItem(true)));
        int lwdYear = lwd.getYear();
        assertThat(ADMIN.queryForObject("""
                select coalesce(sum(quantity), 0) from att.leave_balance_ledger
                 where tenant_id = ? and employment_id = ? and leave_type_id = ? and leave_year = ?""",
                BigDecimal.class, w.tenant(), w.e(), annual, lwdYear)).isEqualByComparingTo("0");
        assertThat(ledgerSum(w, "ENCASH", lwdYear)).isEqualByComparingTo(line.remaining().negate());
    }

    @Test
    void openingBalancesAreLoadedAtGoLive() throws Exception {
        World w = world("att-lv-ob");
        LocalDate goLive = TODAY.withDayOfMonth(1);
        int year = goLive.getYear();
        List<String> cols = List.of("employee_no", "leave_type", "leave_year", "entitled_days", "taken_days",
                "carried_days", "closing_balance", "carry_expires_on");
        String expires = goLive.plusMonths(3).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        // closing = entitled + carried - taken
        byte[] bad = xlsx(cols, List.of(List.of(w.employeeNo(), "ANNUAL", String.valueOf(year), "12", "4", "2", "9",
                expires)), List.of(List.of("LEAVE_BALANCE", "ANNUAL", "9")));
        String job = dryRun(w, bad, goLive);
        assertThat(JsonPath.<Integer>read(job, "$.errorCount")).isEqualTo(1);

        byte[] good = xlsx(cols, List.of(List.of(w.employeeNo(), "ANNUAL", String.valueOf(year), "12", "4", "2", "10",
                expires)), List.of(List.of("LEAVE_BALANCE", "ANNUAL", "10")));
        String ok = dryRun(w, good, goLive);
        assertThat(JsonPath.<Integer>read(ok, "$.errorCount")).isZero();
        UUID jobId = UUID.fromString(JsonPath.read(ok, "$.id"));
        mvc.perform(post("/api/v1/core/imports/jobs/" + jobId + "/sign-off").with(as(w.admin())))
                .andExpect(jsonPath("$.status").value("SIGNED_OFF"));
        mvc.perform(post("/api/v1/core/imports/jobs/" + jobId + "/commit").with(as(w.admin())))
                .andExpect(status().isAccepted());
        await(w, jobId, "COMMITTED");
        // the closing balance is one OPENING row plus the carried days with their expiry
        assertThat(ledgerSum(w, "OPENING", year)).isEqualByComparingTo("8");
        assertThat(ledgerSum(w, "CARRY", year)).isEqualByComparingTo("2");
        assertThat(ADMIN.queryForObject("""
                select source from att.leave_balance_ledger where tenant_id = ? and employment_id = ?
                   and txn_type = 'OPENING'""", String.class, w.tenant(), w.e())).isEqualTo("OPENING_LOAD");
    }

    // ------------------------------------------------------------------------------------------ import helpers

    static byte[] xlsx(List<String> columns, List<List<String>> rows, List<List<String>> control) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<List<String>> all = new ArrayList<>();
            all.add(columns);
            all.addAll(rows);
            write(wb.createSheet("DATA"), all);
            List<List<String>> c = new ArrayList<>();
            c.add(List.of("check", "grain", "value"));
            c.addAll(control);
            write(wb.createSheet("CONTROL"), c);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void write(Sheet sheet, List<List<String>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows.get(r).size(); c++) {
                row.createCell(c).setCellValue(rows.get(r).get(c));
            }
        }
    }

    String dryRun(World w, byte[] content, LocalDate goLive) throws Exception {
        String body = body(mvc.perform(multipart("/api/v1/core/imports/jobs").file(new MockMultipartFile("file",
                        "ob.xlsx", "application/octet-stream", content)).param("importType", "OB_LEAVE_BALANCE")
                .param("mode", "OPENING_BALANCE").param("asOfDate", goLive.toString())
                .param("legalEntityId", w.le().toString()).with(as(w.admin()))).andExpect(status().isAccepted()));
        UUID job = UUID.fromString(JsonPath.read(body, "$.id"));
        UUID file = UUID.fromString(JsonPath.read(body, "$.fileDocumentId"));
        String key = ADMIN.queryForObject("select object_key from core.document_version where tenant_id = ? and "
                + "document_id = ? and version_no = 1", String.class, w.tenant(), file);
        scans.report(w.tenant(), key, "CLEAN");
        String last = null;
        for (int i = 0; i < 300; i++) {
            String b = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(w.admin()))));
            last = JsonPath.read(b, "$.status");
            if ("RECONCILED".equals(last)) {
                return b;
            }
            if ("FAILED".equals(last)) {
                throw new AssertionError("Job failed: " + JsonPath.read(b, "$.failureReason"));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + job + " stayed " + last);
    }

    void await(World w, UUID job, String expected) throws Exception {
        String last = null;
        for (int i = 0; i < 300; i++) {
            String b = body(mvc.perform(get("/api/v1/core/imports/jobs/" + job).with(as(w.admin()))));
            last = JsonPath.read(b, "$.status");
            if (expected.equals(last)) {
                return;
            }
            if ("FAILED".equals(last)) {
                throw new AssertionError("Job failed: " + JsonPath.read(b, "$.failureReason"));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + job + " stayed " + last + ", expected " + expected);
    }
}
