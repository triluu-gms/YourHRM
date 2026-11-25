package vn.yourhrm.attendance.internal.application.ess;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.WorkSchedules.PlannedDay;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService.BalanceView;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService.CapView;
import vn.yourhrm.attendance.internal.application.schedule.WorkScheduleResolver;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.LedgerRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeRow;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.EmploymentDates;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.PunchRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CodeRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CorrectionRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.DayRow;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Employee self-service of Time &amp; Attendance (BR-ATT-080): the caller's own schedule, punches, daily timesheets
 * with their exceptions, leave balances with the ledger, overtime cap usage and the status of their leave, overtime
 * and correction requests, labelled in Vietnamese or English.
 *
 * <p>"Me" comes only from the SELF scope of {@code att.ess.view} (Core's user ↔ employment link), never from a
 * request parameter: an {@code employmentId} parameter only chooses among the caller's own concurrent employments.
 * The employments in scope must all belong to one person, otherwise nothing is shown (fails closed when the
 * permission was granted with a reports scope by mistake). Requests are created through the existing endpoints with
 * their SELF-scoped permissions ({@code att.leave_request.create}, {@code att.timesheet_correction.request},
 * {@code att.overtime.consent}). Reading one's own punches is not written to the audit log (NFR-ATT-008 audits the
 * reads of other people's presence data).
 */
@Service
public class AttendanceEssService {

    static final int MAX_RANGE_DAYS = 62;

    public record MyEmployment(UUID employmentId, UUID legalEntityId, LocalDate hireDate, LocalDate terminationDate,
            String status, boolean activeToday) {
    }

    public record Label(String code, String label) {
    }

    public record CodeQuantity(String code, String label, BigDecimal quantity) {
    }

    public record MyException(String code, String label, boolean blocking) {
    }

    public record MyDay(LocalDate workDate, String dayType, String dayTypeLabel, String dayStatus,
            String dayStatusLabel, String primaryCode, String primaryCodeLabel, List<CodeQuantity> codes,
            Instant firstInAt, Instant lastOutAt, BigDecimal scheduledHours, BigDecimal workedHours, int lateMin,
            int earlyMin, BigDecimal absentHours, BigDecimal nightHours, BigDecimal dayValue,
            List<MyException> exceptions, boolean manualOverride) {
    }

    public record MyPunch(Instant punchAt, LocalDateTime localTime, String direction, String terminalCode,
            String status) {
    }

    public record MyCorrection(UUID id, LocalDate workDate, String correctionType, Instant requestedInAt,
            Instant requestedOutAt, String requestedCode, String source, String status, String statusLabel,
            Instant createdAt) {
    }

    public record MyDayDetail(MyDay day, List<MyPunch> punches, List<MyCorrection> corrections) {
    }

    public record MyBalance(UUID leaveTypeId, String leaveTypeCode, String leaveTypeLabel, int leaveYear,
            LocalDate yearStart, LocalDate yearEnd, BigDecimal opening, BigDecimal accrued, BigDecimal carried,
            BigDecimal taken, BigDecimal adjusted, BigDecimal expired, BigDecimal encashed, BigDecimal balance,
            BigDecimal pending, BigDecimal available) {
    }

    public record MyLedgerEntry(UUID id, String leaveTypeCode, String leaveTypeLabel, int leaveYear, String txnType,
            BigDecimal quantity, String unit, LocalDate txnDate, LocalDate expiresOn, UUID leaveRequestId,
            String reason) {
    }

    /** A request of the caller: {@code kind} LEAVE, OVERTIME or CORRECTION. */
    public record MyRequest(String kind, String kindLabel, UUID id, LocalDate fromDate, LocalDate toDate,
            String subject, String subjectLabel, BigDecimal quantity, String unit, String status,
            String statusLabel, UUID workflowInstanceId, Instant decidedAt) {
    }

    private final PunchStore punches;
    private final TimesheetStore timesheets;
    private final LeaveStore leave;
    private final OvertimeStore overtime;
    private final WorkScheduleResolver schedules;
    private final LeaveBalanceService balances;
    private final OvertimeService overtimeService;
    private final AuthorizationService authz;
    private final BusinessClock clock;

    public AttendanceEssService(PunchStore punches, TimesheetStore timesheets, LeaveStore leave, OvertimeStore overtime,
            WorkScheduleResolver schedules, LeaveBalanceService balances, OvertimeService overtimeService,
            AuthorizationService authz, BusinessClock clock) {
        this.punches = punches;
        this.timesheets = timesheets;
        this.leave = leave;
        this.overtime = overtime;
        this.schedules = schedules;
        this.balances = balances;
        this.overtimeService = overtimeService;
        this.authz = authz;
        this.clock = clock;
    }

    // ============================================================================================== self

    /** The caller's employments known to T&amp;A, newest hire first. */
    @AttTransactional(readOnly = true)
    public List<MyEmployment> employments() {
        LocalDate today = clock.today();
        return mine().stream().map(punches::employment).flatMap(Optional::stream)
                .sorted(Comparator.comparing(EmploymentDates::hireDate).reversed())
                .map(e -> new MyEmployment(e.employmentId(), e.legalEntityId(), e.hireDate(), e.terminationDate(),
                        e.status(), e.employedOn(today)))
                .toList();
    }

    /** The caller's employments (all of one person); 404 when the user is not linked to an employee. */
    private List<UUID> mine() {
        Set<UUID> ids = authz.scopeFor(AttendancePermissions.ESS_VIEW, clock.today()).employmentIds();
        if (ids.isEmpty()) {
            throw ApiException.notFound("Employee record of the current user", "me");
        }
        Set<UUID> persons = ids.stream().map(punches::personOf).flatMap(Optional::stream).collect(Collectors.toSet());
        if (persons.size() != 1) {
            throw ApiException.notFound("Employee record of the current user", "me");
        }
        return ids.stream().filter(id -> punches.employment(id).isPresent()).sorted().toList();
    }

    /**
     * The employment to show: the requested one when it is the caller's, else the caller's only one, else the one
     * employed today (most recent hire). 404 for an employment that is not the caller's.
     */
    private UUID employment(UUID requested) {
        List<UUID> own = mine();
        if (requested != null) {
            if (!own.contains(requested)) {
                throw ApiException.notFound("Employment", requested);
            }
            return requested;
        }
        if (own.isEmpty()) {
            throw ApiException.notFound("Employee record of the current user", "me");
        }
        if (own.size() == 1) {
            return own.getFirst();
        }
        LocalDate today = clock.today();
        List<EmploymentDates> all = own.stream().map(punches::employment).flatMap(Optional::stream)
                .sorted(Comparator.comparing(EmploymentDates::hireDate).reversed()).toList();
        return all.stream().filter(e -> e.employedOn(today)).findFirst().orElse(all.getFirst()).employmentId();
    }

    // ============================================================================================ reads

    @AttTransactional(readOnly = true)
    public List<PlannedDay> schedule(UUID employmentId, LocalDate from, LocalDate to) {
        range(from, to);
        return schedules.plan(employment(employmentId), from, to);
    }

    @AttTransactional(readOnly = true)
    public List<MyPunch> punches(UUID employmentId, LocalDate from, LocalDate to) {
        range(from, to);
        return punches.punchesOfEmployment(employment(employmentId), from, to).stream().map(AttendanceEssService::punch)
                .toList();
    }

    @AttTransactional(readOnly = true)
    public List<MyDay> timesheets(UUID employmentId, LocalDate from, LocalDate to, String lang) {
        range(from, to);
        String l = EssLabels.lang(lang);
        Map<String, CodeRow> codes = timesheets.effectiveCodes();
        return timesheets.days(employment(employmentId), from, to).stream().map(d -> day(d, codes, l)).toList();
    }

    /** Days of the range with at least one exception (missing punches, lateness, absence without a code ...). */
    @AttTransactional(readOnly = true)
    public List<MyDay> exceptions(UUID employmentId, LocalDate from, LocalDate to, String lang) {
        return timesheets(employmentId, from, to, lang).stream().filter(d -> !d.exceptions().isEmpty()).toList();
    }

    @AttTransactional(readOnly = true)
    public MyDayDetail day(UUID employmentId, LocalDate date, String lang) {
        UUID e = employment(employmentId);
        String l = EssLabels.lang(lang);
        DayRow d = timesheets.day(e, date).orElseThrow(() -> ApiException.notFound("Timesheet day", date));
        List<MyPunch> ps = punches.punchesOfEmployment(e, date.minusDays(1), date.plusDays(1)).stream()
                .map(AttendanceEssService::punch).toList();
        List<MyCorrection> cs = timesheets.corrections(e, date, date, false).stream().map(c -> correction(c, l))
                .toList();
        return new MyDayDetail(day(d, timesheets.effectiveCodes(), l), ps, cs);
    }

    @AttTransactional(readOnly = true)
    public List<MyBalance> balances(UUID employmentId, LocalDate asOf, String lang) {
        String l = EssLabels.lang(lang);
        Map<UUID, TypeRow> types = types();
        return balances.balancesOf(employment(employmentId), asOf).stream().map(b -> balance(b, types, l)).toList();
    }

    @AttTransactional(readOnly = true)
    public List<MyLedgerEntry> ledger(UUID employmentId, UUID leaveTypeId, Integer leaveYear, String lang) {
        String l = EssLabels.lang(lang);
        Map<UUID, TypeRow> types = types();
        return leave.ledger(employment(employmentId), leaveTypeId, leaveYear).stream()
                .map(r -> ledgerEntry(r, types, l)).toList();
    }

    @AttTransactional(readOnly = true)
    public List<CapView> overtimeCaps(UUID employmentId, LocalDate date) {
        return overtimeService.capsOf(employment(employmentId), date == null ? clock.today() : date);
    }

    /** Leave, overtime and correction requests of the caller overlapping the range, newest date first. */
    @AttTransactional(readOnly = true)
    public List<MyRequest> requests(UUID employmentId, LocalDate from, LocalDate to, String lang) {
        range(from, to);
        UUID e = employment(employmentId);
        String l = EssLabels.lang(lang);
        Map<UUID, TypeRow> types = types();
        Map<String, CodeRow> codes = timesheets.effectiveCodes();
        List<MyRequest> out = new ArrayList<>();
        for (LeaveStore.RequestRow r : leave.requestsOf(e, from, to, null)) {
            TypeRow t = types.get(r.leaveTypeId());
            boolean hours = "HOUR".equals(r.requestUnit());
            out.add(new MyRequest("LEAVE", EssLabels.kind("LEAVE", l), r.id(), r.startDate(), r.effectiveEnd(),
                    r.leaveTypeCode(), t == null ? r.leaveTypeCode() : EssLabels.pick(t.data().nameVi(),
                            t.data().nameEn(), l),
                    hours ? r.durationHours() : r.durationDays(), hours ? "HOUR" : "DAY", r.status(),
                    EssLabels.status(r.status(), l), r.workflowInstanceId(), r.decidedAt()));
        }
        for (OvertimeStore.RequestRow r : overtime.requestsOfEmployment(e, from, to)) {
            out.add(new MyRequest("OVERTIME", EssLabels.kind("OVERTIME", l), r.id(), r.workDate(), r.workDate(),
                    null, null, BigDecimal.valueOf(r.plannedMinutes()), "MINUTE", r.status(),
                    EssLabels.status(r.status(), l), r.workflowInstanceId(), r.decidedAt()));
        }
        for (CorrectionRow c : timesheets.corrections(e, from, to, false)) {
            if (!"ESS".equals(c.source()) && !"MSS".equals(c.source())) {
                continue; // admin / bulk edits are not the employee's requests (they show on the day)
            }
            CodeRow code = c.requestedCode() == null ? null : codes.get(c.requestedCode());
            out.add(new MyRequest("CORRECTION", EssLabels.kind("CORRECTION", l), c.id(), c.workDate(),
                    c.workDate(), c.correctionType(), code == null ? c.correctionType()
                            : EssLabels.pick(code.labelVi(), code.labelEn(), l),
                    null, null, c.status(), EssLabels.status(c.status(), l), c.workflowInstanceId(), c.decidedAt()));
        }
        out.sort(Comparator.comparing(MyRequest::fromDate).reversed().thenComparing(MyRequest::kind));
        return out;
    }

    // ============================================================================================ mapping

    private Map<UUID, TypeRow> types() {
        Map<UUID, TypeRow> m = new HashMap<>();
        leave.types().forEach(t -> m.put(t.id(), t));
        return m;
    }

    private static MyDay day(DayRow d, Map<String, CodeRow> codes, String l) {
        List<CodeQuantity> qs = d.codes().stream()
                .map(c -> new CodeQuantity(c.code(), codeLabel(codes, c.code(), l), c.qty())).toList();
        List<MyException> ex = d.exceptions().stream()
                .map(x -> new MyException(x, EssLabels.exception(x, l), DayModel.BLOCKING.contains(x))).toList();
        return new MyDay(d.workDate(), d.dayType(), EssLabels.dayType(d.dayType(), l), d.dayStatus(),
                EssLabels.dayStatus(d.dayStatus(), l), d.primaryCode(), codeLabel(codes, d.primaryCode(), l), qs,
                d.firstInAt(), d.lastOutAt(), d.scheduledHours(), d.workedHours(), d.lateMin(), d.earlyMin(),
                d.absentHours(), d.nightHours(), d.dayValue(), ex, d.manualOverride());
    }

    private static String codeLabel(Map<String, CodeRow> codes, String code, String l) {
        if (code == null) {
            return null;
        }
        CodeRow c = codes.get(code);
        return c == null ? code : EssLabels.pick(c.labelVi(), c.labelEn(), l);
    }

    private static MyPunch punch(PunchRow p) {
        return new MyPunch(p.punchAt(), p.localTs(), p.direction(), p.terminalCode(), p.validationStatus());
    }

    private static MyCorrection correction(CorrectionRow c, String l) {
        return new MyCorrection(c.id(), c.workDate(), c.correctionType(), c.requestedInAt(), c.requestedOutAt(),
                c.requestedCode(), c.source(), c.status(), EssLabels.status(c.status(), l), c.createdAt());
    }

    private static MyBalance balance(BalanceView b, Map<UUID, TypeRow> types, String l) {
        TypeRow t = types.get(b.leaveTypeId());
        return new MyBalance(b.leaveTypeId(), b.leaveTypeCode(), t == null ? b.leaveTypeCode()
                : EssLabels.pick(t.data().nameVi(), t.data().nameEn(), l), b.leaveYear(), b.yearStart(),
                b.yearEnd(), b.opening(), b.accrued(), b.carried(), b.taken(), b.adjusted(), b.expired(),
                b.encashed(), b.balance(), b.pending(), b.available());
    }

    private static MyLedgerEntry ledgerEntry(LedgerRow r, Map<UUID, TypeRow> types, String l) {
        TypeRow t = types.get(r.leaveTypeId());
        return new MyLedgerEntry(r.id(), r.leaveTypeCode(), t == null ? r.leaveTypeCode()
                : EssLabels.pick(t.data().nameVi(), t.data().nameEn(), l), r.leaveYear(), r.txnType(), r.quantity(),
                r.unit(), r.txnDate(), r.expiresOn(), r.leaveRequestId(), r.reason());
    }

    private static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.unprocessable("invalid-range", "Give from and to (to on or after from)");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("range-too-long", "The range is at most " + MAX_RANGE_DAYS + " days");
        }
    }
}
