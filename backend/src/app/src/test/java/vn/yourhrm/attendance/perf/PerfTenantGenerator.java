package vn.yourhrm.attendance.perf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.yourhrm.attendance.api.AttendanceJobs;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.PayrollInbound;
import vn.yourhrm.attendance.api.PayrollInbound.PayCalendarPublished;
import vn.yourhrm.attendance.api.PayrollInbound.PayPeriod;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Synthetic T&amp;A tenant for the performance tests (NFR-ATT-002 / 003). Configuration (two shifts, two weekly
 * patterns, the pay calendar) goes through the real API and in-process ports; the bulk volume (employments,
 * assignment lines, schedule assignments, pay-group membership, a month of raw punches, approved leave and overtime)
 * is written set-based with {@code generate_series} as the superuser, the way the projections and the punch import
 * would have left it. Every per-employee choice is a hash of the employment id, so the shares are stable for a size.
 *
 * <p>Shape of the tenant (all shares configurable, see {@link Config}):
 * <ul>
 *   <li>one legal entity, a root org unit with {@code units} departments, one pay group, the previous month (P1) and
 *       the current month (P2) as OPEN periods (FIXED_DAYS 26);</li>
 *   <li>{@code nightShare} of the employees on a night shift 22:00-06:00 Mon-Sat (crosses midnight), the others on
 *       the office shift 08:00-17:00 Mon-Fri with a 12-13 lunch break;</li>
 *   <li>1 % hired in the middle of P1, 1 % terminated in the middle of P1 (NOT_EMPLOYED days);</li>
 *   <li>raw punches for every planned work day from P1 start to yesterday: absences, missing OUT and late arrivals
 *       ({@code exceptionShare} of the days in total), the rest with a few minutes of jitter;
 *       {@code quarantineShare} of extra unmapped punches (quarantine checklist item);</li>
 *   <li>{@code leaveShare} of the office employees with one or two days of approved annual leave in P1 (no punches
 *       on those days);</li>
 *   <li>{@code otShare} of the office employees with approved, consented overtime 17:00-19:00 every Tuesday and
 *       Thursday, punched out at about 19:05.</li>
 * </ul>
 * Not generated: holiday calendars, corrections, pending requests, protected-worker statuses, a second legal entity.
 */
public final class PerfTenantGenerator {

    public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final String BASE = "/api/v1/attendance";

    /** Generator parameters; {@link #fromSystemProperties()} reads {@code -Dperf.*}. */
    public record Config(int employees, int units, double nightShare, double exceptionShare, double leaveShare,
            double otShare, double quarantineShare) {

        public static Config fromSystemProperties() {
            return new Config(Integer.getInteger("perf.employees", 1_000), Integer.getInteger("perf.units", 20),
                    dbl("perf.nightShare", 0.10), dbl("perf.exceptionShare", 0.06), dbl("perf.leaveShare", 0.05),
                    dbl("perf.otShare", 0.10), dbl("perf.quarantineShare", 0.001));
        }

        private static double dbl(String key, double def) {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? def : Double.parseDouble(v);
        }

        /** Percent of employees as an integer bucket width of the 0..99 hash buckets. */
        int pct(double share) {
            return (int) Math.round(share * 100);
        }

        /** Per-mille of days. */
        int permille(double share) {
            return (int) Math.round(share * 1000);
        }
    }

    /** What was generated (ids for the tests, counts for the report). */
    public record Tenant(UUID tenant, TestUser admin, UUID legalEntity, UUID payGroup, UUID periodP1, UUID periodP2,
            LocalDate p1Start, LocalDate p1End, LocalDate today, Map<String, Long> counts, long seedMillis) {

        public <T> T asAdmin(Callable<T> op) throws Exception {
            return TenantContext.callWith(new TenantContext(tenant, admin.userId(), admin.identityId().toString(),
                    "perf", "vi-VN"), op);
        }

        public <T> T asSystem(Callable<T> op) throws Exception {
            return TenantContext.callWith(new TenantContext(tenant, null, "perf-nightly", null, null), op);
        }

        public long employments() {
            return counts.get("employments");
        }
    }

    private final JdbcTemplate admin;
    private final TestFixtures fix;
    private final MockMvc mvc;
    private final java.util.function.Function<TestUser, RequestPostProcessor> as;
    private final PayrollInbound payroll;
    private final AttendanceJobs jobs;

    public PerfTenantGenerator(JdbcTemplate admin, TestFixtures fix, MockMvc mvc,
            java.util.function.Function<TestUser, RequestPostProcessor> as, PayrollInbound payroll,
            AttendanceJobs jobs) {
        this.admin = admin;
        this.fix = fix;
        this.mvc = mvc;
        this.as = as;
        this.payroll = payroll;
        this.jobs = jobs;
    }

    public Tenant generate(String slug, Config c) throws Exception {
        long t0 = System.nanoTime();
        LocalDate today = LocalDate.now(VN);
        LocalDate p1Start = today.minusMonths(1).withDayOfMonth(1);
        LocalDate p1End = p1Start.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate p2Start = p1End.plusDays(1);
        LocalDate p2End = p2Start.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate yesterday = today.minusDays(1);
        jobs.maintainPartitions();

        // ---- tenant, admin, legal parameters, legal entity, org units
        UUID tenant = fix.tenant(slug);
        List<String> perms = new ArrayList<>(fix.allCorePermissions());
        perms.addAll(List.of(AttendancePermissions.SCHEDULE_CONFIG_VIEW, AttendancePermissions.SCHEDULE_CONFIG_UPDATE,
                AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE,
                AttendancePermissions.TIMESHEET_VIEW, AttendancePermissions.TIMESHEET_UPDATE,
                AttendancePermissions.PERIOD_VIEW, AttendancePermissions.PERIOD_CLOSE,
                AttendancePermissions.PERIOD_REOPEN, AttendancePermissions.JOB_RUN));
        UUID role = fix.role(tenant, "TA_PERF", false, perms.toArray(String[]::new));
        TestUser user = fix.user(tenant, "perf-" + tenant.toString().substring(24) + "@test.local");
        fix.assign(user, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        legalParameters(tenant);
        UUID le = fix.legalEntity(tenant, "PERF-LE");
        var root = fix.orgUnit(tenant, "ROOT", null, le, null, TestFixtures.EPOCH, null);
        List<UUID> units = new ArrayList<>();
        for (int i = 0; i < c.units(); i++) {
            units.add(fix.orgUnit(tenant, "D" + i, root, le, null, TestFixtures.EPOCH, null).id());
        }

        // ---- shifts and weekly patterns (API)
        LocalDate anchor = LocalDate.of(2020, 1, 6);   // a Monday
        postJson(user, "/shifts", """
                {"code":"HC","nameVi":"Hành chính","startTime":"08:00","endTime":"17:00",
                 "breaks":[{"start":"12:00","end":"13:00"}],"graceInMin":5,"graceOutMin":5}""");
        postJson(user, "/shifts", """
                {"code":"DEM","nameVi":"Ca đêm","startTime":"22:00","endTime":"06:00",
                 "breaks":[{"start":"02:00","end":"02:45","paid":true}],"graceInMin":5,"graceOutMin":5}""");
        UUID office = pattern(user, "T2T6", "HC", 5, anchor);
        UUID night = pattern(user, "DEM_T2T7", "DEM", 6, anchor);

        UUID pg = Ids.newId();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tenant", tenant);
        p.put("le", le);
        p.put("pg", pg);
        p.put("p1Start", Date.valueOf(p1Start));
        p.put("p1End", Date.valueOf(p1End));
        p.put("yesterday", Date.valueOf(yesterday));
        p.put("schedFrom", Date.valueOf(p1Start.minusMonths(1)));
        p.put("midP1", Date.valueOf(p1Start.plusDays(14)));
        p.put("nightPct", c.pct(c.nightShare()));
        p.put("otPct", c.pct(c.otShare()));
        p.put("leavePct", c.pct(c.leaveShare()));

        // ---- employments (People projection): 1 % hired mid-P1, 1 % terminated mid-P1
        admin.update("""
                insert into att.ext_hr_employment (tenant_id, employment_id, person_id, legal_entity_id, hire_date,
                       termination_date, last_working_date, status, worker_category_code, employee_category_code,
                       source_event_id, source_version)
                select ?, sys.uuid_v7(), sys.uuid_v7(), ?,
                       case when g % 100 = 0 then ?::date else date '2020-01-01' end,
                       case when g % 100 = 1 then ?::date end, case when g % 100 = 1 then ?::date end,
                       case when g % 100 = 1 then 'TERMINATED' else 'ACTIVE' end, 'EMPLOYEE', 'STAFF',
                       sys.uuid_v7(), 1
                  from generate_series(1, ?) g""",
                tenant, le, p.get("midP1"), p.get("midP1"), p.get("midP1"), c.employees());

        // the hash bucket (0..99) of an employment drives its shift group, overtime and leave
        String bucket = "(abs(hashtext(e.employment_id::text)::bigint) % 100)";
        String unitArray = units.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        admin.update("""
                insert into att.ext_hr_assignment (tenant_id, id, assignment_key, employment_id, person_id,
                       assignment_type, legal_entity_id, org_unit_id, employee_category_code, working_regime_code,
                       fte, valid_from, source_event_id, source_version, source_recorded_at)
                select e.tenant_id, md5(e.employment_id::text || ':line')::uuid,
                       md5(e.employment_id::text || ':key')::uuid, e.employment_id, e.person_id, 'PRIMARY',
                       e.legal_entity_id, (?::uuid[])[(1 + abs(hashtext(e.employment_id::text || ':unit')::bigint) % ?)::int],
                       'STAFF', 'FULL_TIME', 1, e.hire_date, sys.uuid_v7(), 1, now()
                  from att.ext_hr_employment e where e.tenant_id = ?""", unitArray, c.units(), tenant);
        admin.update("""
                insert into att.schedule_assignment (tenant_id, employment_id, assignment_key, pattern_id, valid_from,
                       source)
                select e.tenant_id, e.employment_id, md5(e.employment_id::text || ':key')::uuid,
                       case when %s < ? then ?::uuid else ?::uuid end, greatest(e.hire_date, ?::date), 'MANUAL'
                  from att.ext_hr_employment e where e.tenant_id = ?""".formatted(bucket),
                p.get("nightPct"), night, office, p.get("schedFrom"), tenant);
        admin.update("""
                insert into att.ext_pay_pay_group_assignment (tenant_id, id, employment_id, pay_group_id,
                       legal_entity_id, valid_from, source_event_id, source_version)
                select e.tenant_id, sys.uuid_v7(), e.employment_id, ?, e.legal_entity_id, e.hire_date, sys.uuid_v7(), 1
                  from att.ext_hr_employment e where e.tenant_id = ?""", pg, tenant);

        // ---- pay calendar (Payroll port): P1 and P2 OPEN, FIXED_DAYS 26
        TenantContext ctx = new TenantContext(tenant, user.userId(), user.identityId().toString(), "perf", "vi-VN");
        TenantContext.callWith(ctx, () -> payroll.deliver(new PayCalendarPublished(Ids.newId(), le, pg, "FIXED_DAYS",
                new BigDecimal("26"), List.of(new PayPeriod(Ids.newId(), p1Start, p1End, null, null, null),
                        new PayPeriod(Ids.newId(), p2Start, p2End, null, null, null)))));
        UUID periodP1 = period(tenant, p1Start);
        UUID periodP2 = period(tenant, p2Start);

        // ---- approved annual leave (office employees of the leave buckets): 1-2 working days in P1
        UUID annual = Ids.newId();
        admin.update("""
                insert into att.leave_type (tenant_id, id, code, name_vi, leave_category, paid_by, min_unit,
                       tracks_balance, attendance_code, payroll_category, is_statutory)
                values (?, ?, 'ANNUAL', 'Nghỉ phép năm', 'ANNUAL', 'EMPLOYER', 'HALF_DAY', true, 'P',
                        'PAID_LEAVE_DAYS', true)""", tenant, annual);
        admin.update("""
                insert into att.leave_request (tenant_id, employment_id, person_id, legal_entity_id, org_unit_id,
                       leave_type_id, start_date, end_date, duration_days, reason, status, decided_at)
                select x.tenant_id, x.employment_id, x.person_id, x.legal_entity_id, x.org_unit_id, ?, x.d,
                       case when x.two and extract(isodow from x.d) < 5 then x.d + 1 else x.d end,
                       case when x.two and extract(isodow from x.d) < 5 then 2 else 1 end, 'perf', 'APPROVED', now()
                  from (select e.tenant_id, e.employment_id, e.person_id, e.legal_entity_id, a.org_unit_id,
                               (abs(hashtext(e.employment_id::text || ':two')::bigint) % 2 = 0) as two,
                               (select dd::date from generate_series(?::date + 7 + (abs(hashtext(e.employment_id::text
                                       || ':leave')::bigint) % 14)::int, ?::date, interval '1 day') dd
                                 where extract(isodow from dd) <= 5 limit 1) as d
                          from att.ext_hr_employment e
                          join att.ext_hr_assignment a on a.tenant_id = e.tenant_id and a.employment_id = e.employment_id
                         where e.tenant_id = ? and e.status = 'ACTIVE' and e.hire_date <= ?::date
                           and %s >= ? + ? and %s < ? + ? + ?) x
                 where x.d is not null""".formatted(bucket, bucket),
                annual, p.get("p1Start"), p.get("p1End"), tenant, p.get("p1Start"),
                p.get("nightPct"), p.get("otPct"), p.get("nightPct"), p.get("otPct"), p.get("leavePct"));

        // ---- approved, consented overtime 17:00-19:00 on Tuesdays and Thursdays (office employees of the OT buckets)
        admin.update("""
                insert into att.overtime_request (tenant_id, employment_id, legal_entity_id, org_unit_id, work_date,
                       planned_start_at, planned_end_at, planned_minutes, reason, employee_consent, consent_at,
                       consent_method, status, decided_at)
                select e.tenant_id, e.employment_id, e.legal_entity_id, a.org_unit_id, d::date,
                       (d::date + time '17:00') at time zone 'Asia/Ho_Chi_Minh',
                       (d::date + time '19:00') at time zone 'Asia/Ho_Chi_Minh', 120, 'perf', true, now(), 'PAPER',
                       'APPROVED', now()
                  from att.ext_hr_employment e
                  join att.ext_hr_assignment a on a.tenant_id = e.tenant_id and a.employment_id = e.employment_id
                 cross join generate_series(?::date, ?::date, interval '1 day') d
                 where e.tenant_id = ? and %s >= ? and %s < ? + ? and extract(isodow from d) in (2, 4)
                   and d::date >= e.hire_date and (e.termination_date is null or d::date <= e.termination_date)"""
                .formatted(bucket, bucket),
                p.get("p1Start"), p.get("yesterday"), tenant, p.get("nightPct"), p.get("nightPct"), p.get("otPct"));

        // ---- raw punches of every planned work day, P1 start .. yesterday
        int ex = c.permille(c.exceptionShare());
        int absent = ex / 3;
        int missingOut = absent + ex / 3;
        int late = ex;
        UUID importJob = Ids.newId();
        admin.update("""
                with days as (
                  select e.tenant_id, e.employment_id, e.legal_entity_id, a.org_unit_id, d::date as d,
                         (%s < ?) as night,
                         abs(hashtext(e.employment_id::text || d::date::text)::bigint) % 1000 as r,
                         exists (select 1 from att.overtime_request o where o.tenant_id = e.tenant_id
                                   and o.employment_id = e.employment_id and o.work_date = d::date) as ot
                    from att.ext_hr_employment e
                    join att.ext_hr_assignment a on a.tenant_id = e.tenant_id and a.employment_id = e.employment_id
                   cross join generate_series(?::date, ?::date, interval '1 day') d
                   where e.tenant_id = ? and d::date >= e.hire_date
                     and (e.termination_date is null or d::date <= e.termination_date)
                     and not exists (select 1 from att.leave_request l where l.tenant_id = e.tenant_id
                                       and l.employment_id = e.employment_id and l.status = 'APPROVED'
                                       and d::date between l.start_date and l.end_date)),
                planned as (
                  select * from days
                   where (night and extract(isodow from d) <= 6) or (not night and extract(isodow from d) <= 5)),
                punches as (
                  select p.*, v.dir,
                         case when v.dir = 'IN' then
                                (p.d + case when p.night then time '22:00' else time '08:00' end)
                                + case when p.r >= ? and p.r < ? then interval '20 minutes'
                                       else - make_interval(mins => (p.r % 15)::int) end
                              else
                                (p.d + case when p.night then interval '30 hours'
                                            when p.ot then interval '19 hours'
                                            else interval '17 hours' end)
                                + make_interval(mins => (p.r % 20)::int)
                         end as local_ts
                    from planned p
                   cross join (values ('IN'), ('OUT')) v(dir)
                   where p.r >= ?                                   -- absent days: no punch at all
                     and not (v.dir = 'OUT' and p.r < ?))            -- missing OUT
                insert into att.raw_punch (tenant_id, work_date_hint, punch_at, local_ts, tz_name, source,
                       terminal_code, enrol_id, direction, dedup_key, import_job_id, employment_id, legal_entity_id,
                       org_unit_id, validation_status, matched_at)
                select tenant_id, local_ts::date, local_ts at time zone 'Asia/Ho_Chi_Minh', local_ts,
                       'Asia/Ho_Chi_Minh', 'IMPORT', 'T01', employment_id::text, dir,
                       md5('T01|' || employment_id::text || '|' || local_ts::text), ?, employment_id,
                       legal_entity_id, org_unit_id, 'VALID', now()
                  from punches""".formatted(bucket),
                p.get("nightPct"), p.get("p1Start"), p.get("yesterday"), tenant,
                missingOut, late, absent, missingOut, importJob);
        // unknown enrol ids from the same files: quarantined (they never reach a timesheet)
        admin.update("""
                insert into att.raw_punch (tenant_id, work_date_hint, punch_at, local_ts, tz_name, source,
                       terminal_code, enrol_id, direction, dedup_key, import_job_id, legal_entity_id,
                       validation_status)
                select ?, ts::date, ts at time zone 'Asia/Ho_Chi_Minh', ts, 'Asia/Ho_Chi_Minh', 'IMPORT', 'T01',
                       'UNK' || g, 'IN', md5('T01|UNK' || g), ?, ?, 'QUARANTINED_UNMAPPED'
                  from generate_series(1, ?) g,
                       lateral (select (?::date + (g % 25) + time '07:55') as ts) x""",
                tenant, importJob, le, Math.max(1, (int) Math.round(c.employees() * 20 * c.quarantineShare())),
                p.get("p1Start"));

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("employments", count("att.ext_hr_employment", tenant));
        counts.put("nightShiftEmployments", admin.queryForObject("""
                select count(*) from att.schedule_assignment where tenant_id = ? and pattern_id = ?""", Long.class,
                tenant, night));
        counts.put("rawPunches", count("att.raw_punch", tenant));
        counts.put("approvedLeaveRequests", count("att.leave_request", tenant));
        counts.put("approvedOvertimeRequests", count("att.overtime_request", tenant));
        counts.put("calendarDays", (long) (yesterday.toEpochDay() - p1Start.toEpochDay() + 1));
        long seedMillis = (System.nanoTime() - t0) / 1_000_000;
        return new Tenant(tenant, user, le, pg, periodP1, periodP2, p1Start, p1End, today, counts, seedMillis);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private long count(String table, UUID tenant) {
        return admin.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenant);
    }

    private UUID period(UUID tenant, LocalDate start) {
        return admin.queryForObject("select id from att.attendance_period where tenant_id = ? and period_start = ?",
                UUID.class, tenant, start);
    }

    private static void legalParameters(UUID tenant) {
        Map<String, Object> lps = new LinkedHashMap<>();
        lps.put(AttendanceLegalParameters.BREAK_DUE_AFTER_MIN, 360);
        lps.put(AttendanceLegalParameters.BREAK_MIN_DAY, 30);
        lps.put(AttendanceLegalParameters.BREAK_MIN_NIGHT, 45);
        lps.put(AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY, 8);
        lps.put(AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK, 48);
        lps.put(AttendanceLegalParameters.NIGHT_START, "22:00");
        lps.put(AttendanceLegalParameters.NIGHT_END, "06:00");
        lps.put(AttendanceLegalParameters.OT_MAX_DAY_TOTAL_HOURS, 12);
        lps.put(AttendanceLegalParameters.OT_MAX_DAY_OFF_HOURS, 12);
        lps.put(AttendanceLegalParameters.OT_MAX_MONTH_HOURS, 40);
        lps.put(AttendanceLegalParameters.OT_MAX_YEAR_HOURS, 200);
        lps.put(AttendanceLegalParameters.OT_MAX_YEAR_EXTENDED_HOURS, 300);
        lps.put(AttendanceLegalParameters.OT_PROTECTED_RULES, Map.of("PREGNANCY", "BLOCK", "CHILD_UNDER_12M",
                "CONSENT", "MINOR", "BLOCK", "DISABILITY", "CONSENT", "ELDERLY_WORKER", "ALLOW", "HAZARDOUS_JOB",
                "ALLOW"));
        lps.forEach((k, v) -> TestLegalParameters.define(tenant, k, v));
    }

    private String postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(BASE + path).with(as.apply(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    /** A 7-day pattern: the first {@code workDays} days WORK on {@code shift}, the remaining REST. */
    private UUID pattern(TestUser user, String code, String shift, int workDays, LocalDate mondayAnchor)
            throws Exception {
        if (mondayAnchor.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("anchor must be a Monday");
        }
        String days = IntStream.range(0, 7).mapToObj(i -> i < workDays
                ? "{\"dayIndex\":" + i + ",\"dayKind\":\"WORK\",\"shiftCode\":\"" + shift + "\"}"
                : "{\"dayIndex\":" + i + ",\"dayKind\":\"REST\"}").collect(Collectors.joining(",", "[", "]"));
        String body = postJson(user, "/schedule-patterns", """
                {"code":"%s","nameVi":"%s","cycleLengthDays":7,"anchorDate":"%s","days":%s}"""
                .formatted(code, code, mondayAnchor, days));
        return UUID.fromString(JsonPath.read(body, "$.pattern.id"));
    }
}
