package vn.yourhrm.attendance.internal.application.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AbsenceChanged;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.WorkSchedules.PlannedDay;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules.CountedDay;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules.PlanDay;
import vn.yourhrm.attendance.internal.infra.AttCipher;
import vn.yourhrm.attendance.internal.infra.AttOutbox;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.EmploymentFacts;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.NewRequest;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.NewTxn;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.PolicyData;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.PolicyRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.RequestRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.SiClaim;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.StoredSiClaim;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeRow;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.EmployeeDirectory;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;

/**
 * Leave requests (BR-ATT-061..063, 065; process P6). The duration comes from the employee's planned days (working days
 * only, or every calendar day for e.g. maternity); a request is checked for the employment period (no leave after the
 * last working day), frozen attendance periods, unit, maxima, eligibility, notice, attachments, SI claim data, overlap
 * and balance, where PENDING requests of the type reserve their days (BR-ATT-062). Approval goes through Core's
 * workflow ({@code att.LEAVE}); on approval the balance-tracked days become TAKE ledger rows per leave year and
 * {@code att.AbsenceChanged} makes daily processing recompute the days. A PENDING request can be withdrawn; cancelling
 * an APPROVED one (BR-ATT-066) is P2: HR uses a balance adjustment. A maternity leave can end early after the legal
 * minimum (BR-ATT-065). SI claim data is P3: encrypted under the person's key, read only through an audited call.
 */
@Service
public class LeaveRequestService {

    static final int MAX_RANGE_DAYS = 366;
    static final List<String> OPEN = List.of("PENDING", "APPROVED");

    public record SiClaimInput(String certificateType, String certificateNo, LocalDate certificateDate,
            String issuingFacility, String diagnosisCategory, Boolean longTermIllness, String treatmentType,
            LocalDate childBirthDate, String childId, LocalDate confinementDate, Short childrenBornCount,
            Boolean caesarean) {
    }

    public record RequestInput(UUID leaveTypeId, String leaveTypeCode, LocalDate startDate, LocalDate endDate,
            String startPart, String endPart, LocalTime startTime, LocalTime endTime, String reason,
            List<UUID> attachmentDocumentIds, SiClaimInput siClaim) {
    }

    /** The computed duration of a request (preview) and the balance it would leave. */
    public record Preview(String leaveTypeCode, String requestUnit, BigDecimal durationDays, BigDecimal durationHours,
            List<CountedDay> days, Map<Integer, BigDecimal> daysPerLeaveYear, Map<Integer, BigDecimal> available,
            List<String> warnings) {
    }

    /** SI claim data in plain (audited read). */
    public record SiClaimView(String certificateType, String certificateNo, LocalDate certificateDate,
            String issuingFacility, String diagnosisCategory, Boolean longTermIllness, String treatmentType,
            LocalDate childBirthDate, String childId, LocalDate confinementDate, Short childrenBornCount,
            Boolean caesarean) {
    }

    private final LeaveStore store;
    private final PunchStore punches;
    private final LeaveEntitlements entitlements;
    private final LeaveBalanceService balances;
    private final WorkflowService workflow;
    private final AttOutbox outbox;
    private final AttCipher cipher;
    private final AttendanceLegalParameters legal;
    private final ObjectProvider<EmployeeDirectory> directory;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;

    public LeaveRequestService(LeaveStore store, PunchStore punches, LeaveEntitlements entitlements,
            LeaveBalanceService balances, WorkflowService workflow, AttOutbox outbox, AttCipher cipher,
            AttendanceLegalParameters legal, ObjectProvider<EmployeeDirectory> directory, AuthorizationService authz,
            AuditLog audit, BusinessClock clock) {
        this.store = store;
        this.punches = punches;
        this.entitlements = entitlements;
        this.balances = balances;
        this.workflow = workflow;
        this.outbox = outbox;
        this.cipher = cipher;
        this.legal = legal;
        this.directory = directory;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    // --------------------------------------------------------------------------------------------- requests

    /** Duration and remaining balance of a request without creating it (BR-ATT-061, ESS form). */
    @AttTransactional(readOnly = true)
    public Preview preview(UUID employmentId, RequestInput in) {
        Checked c = check(employmentId, in, null, false);
        return c.preview();
    }

    @AttTransactional
    public RequestRow request(UUID employmentId, RequestInput in) {
        Checked c = check(employmentId, in, null, true);
        TypeRow type = c.type();
        SiClaim si = seal(c.facts().personId(), in.siClaim());
        UUID id;
        try {
            id = store.insertRequest(new NewRequest(employmentId, c.facts().personId(), c.where().legalEntityId(),
                    c.where().orgUnitId(), type.id(), c.unit(), in.startDate(), c.end(), c.startPart(), c.endPart(),
                    in.startTime(), in.endTime(), c.preview().durationDays(), c.preview().durationHours(),
                    LeaveInputs.text(in.reason()), "PENDING",
                    in.attachmentDocumentIds() == null ? List.of() : in.attachmentDocumentIds(), si));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("leave_request_overlap_ex")) {
                throw ApiException.conflict("leave-overlap", "Another pending or approved leave covers these days");
            }
            throw e;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("leaveType", type.data().code());
        summary.put("leaveCategory", type.data().category());
        summary.put("startDate", in.startDate().toString());
        summary.put("endDate", c.end().toString());
        summary.put("startPart", c.startPart());
        summary.put("endPart", c.endPart());
        summary.put("durationDays", c.preview().durationDays());
        if (type.data().tracksBalance()) {
            c.preview().available().values().stream().reduce(BigDecimal::min)
                    .ifPresent(a -> summary.put("balanceAfter", a));
        }
        if (in.reason() != null && !in.reason().isBlank()) {
            summary.put("reason", in.reason().strip());
        }
        if (!c.preview().warnings().isEmpty()) {
            summary.put("warnings", String.join("; ", c.preview().warnings()));
        }
        var started = workflow.start(new StartRequest(LeaveCatalogues.PROCESS_LEAVE, LeaveCatalogues.SUBJECT_LEAVE, id,
                0, "att-leave-" + id, null, c.facts().personId(), c.where().legalEntityId(), c.where().orgUnitId(),
                c.where().locationId(), null, summary, Map.of("leaveCategory", type.data().category(),
                        "durationDays", c.preview().durationDays()), Map.of()));
        store.setWorkflow(id, started.instanceId());
        if ("APPROVED".equals(started.status())) {
            applyOutcome(id, "APPROVED", null);
        }
        return store.request(id).orElseThrow();
    }

    /** Applies the final workflow outcome (listener); idempotent, and a no-op once the request left PENDING. */
    @AttTransactional
    public Map<String, Object> applyOutcome(UUID requestId, String outcome, UUID decidedBy) {
        RequestRow r = store.requestForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("Leave request", requestId));
        if (!"PENDING".equals(r.status())) {
            return Map.of("status", r.status());
        }
        switch (outcome) {
            case "APPROVED" -> {
                store.setStatus(r.id(), "PENDING", "APPROVED", decidedBy, null);
                TypeRow type = store.type(r.leaveTypeId()).orElseThrow();
                if (type.data().tracksBalance()) {
                    take(r, type);
                }
                publish(store.request(r.id()).orElseThrow(), type, "APPROVED");
                return Map.of("status", "APPROVED");
            }
            case "REJECTED" -> {
                store.setStatus(r.id(), "PENDING", "REJECTED", decidedBy, null);
                return Map.of("status", "REJECTED");
            }
            default -> {
                store.setStatus(r.id(), "PENDING", "CANCELLED", decidedBy, null);
                return Map.of("status", "CANCELLED");
            }
        }
    }

    /**
     * Withdraws a PENDING request (the employee or HR in scope); the reserved days are released. An APPROVED request
     * cannot be cancelled in P1 (BR-ATT-066 is P2): HR adjusts the balance with a reason instead.
     */
    @AttTransactional
    public RequestRow withdraw(UUID requestId, String reason) {
        RequestRow r = store.requestForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("Leave request", requestId));
        requireEmployee(AttendancePermissions.LEAVE_REQUEST_CREATE, r.employmentId(), r.startDate());
        if ("APPROVED".equals(r.status())) {
            throw ApiException.conflict("cancel-approved-not-supported", "An approved leave cannot be cancelled yet; "
                    + "HR adjusts the balance with a reason (BR-ATT-066 comes in phase 2)");
        }
        if (!"PENDING".equals(r.status())) {
            throw ApiException.conflict("not-pending", "Only a pending request can be withdrawn (it is "
                    + r.status() + ")");
        }
        store.setStatus(r.id(), "PENDING", "WITHDRAWN", null, LeaveInputs.text(reason));
        return store.request(r.id()).orElseThrow();
    }

    /**
     * Early return from maternity leave (BR-ATT-065, Art. 139(4) BLLĐ [verify-legal]): allowed after the legal minimum of
     * leave; the leave ends the day before {@code returnDate}, the timesheet is recomputed and People / Payroll are
     * told through {@code att.AbsenceChanged} (SHORTENED). The employee's agreement and the medical confirmation are
     * attachments of the request.
     */
    @AttTransactional
    public RequestRow earlyReturn(UUID requestId, LocalDate returnDate, List<UUID> attachments) {
        RequestRow r = store.requestForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("Leave request", requestId));
        var where = requireEmployee(AttendancePermissions.LEAVE_REQUEST_MANAGE, r.employmentId(), r.startDate());
        TypeRow type = store.type(r.leaveTypeId()).orElseThrow();
        if (!"MATERNITY".equals(type.data().category())) {
            throw invalid("not-maternity", "Only maternity leave can end early");
        }
        if (!"APPROVED".equals(r.status())) {
            throw ApiException.conflict("not-approved", "Only an approved leave can end early");
        }
        if (returnDate == null || !returnDate.isAfter(r.startDate()) || returnDate.isAfter(r.endDate())) {
            throw invalid("invalid-return-date", "The return date lies after the start and on or before the end");
        }
        int minMonths = legal.find(AttendanceLegalParameters.LEAVE_MATERNITY_MIN_MONTHS,
                new Scope(where.legalEntityId(), null), returnDate)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "legal-parameter-missing",
                        "The legal parameter " + AttendanceLegalParameters.LEAVE_MATERNITY_MIN_MONTHS
                                + " is not defined", Map.of("parameter",
                                AttendanceLegalParameters.LEAVE_MATERNITY_MIN_MONTHS)))
                .asInt();
        if (returnDate.isBefore(r.startDate().plusMonths(minMonths))) {
            throw invalid("too-early", "A return is possible after at least " + minMonths + " months of leave");
        }
        store.setActualReturn(r.id(), returnDate);
        RequestRow after = store.request(r.id()).orElseThrow();
        audit.record(new BusinessAction("EARLY_RETURN", "att", "leave_request", r.id(), r.legalEntityId(),
                Map.of("returnDate", returnDate.toString(), "attachments",
                        String.valueOf(attachments == null ? 0 : attachments.size()))));
        publish(after, type, "SHORTENED");
        return after;
    }

    /** Replaces the SI claim data of a PENDING / APPROVED request (BR-ATT-063; certificates often come later). */
    @AttTransactional
    public RequestRow updateSiClaim(UUID requestId, SiClaimInput in) {
        RequestRow r = store.requestForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("Leave request", requestId));
        requireEmployee(AttendancePermissions.LEAVE_REQUEST_MANAGE, r.employmentId(), r.startDate());
        if (!OPEN.contains(r.status())) {
            throw ApiException.conflict("request-closed", "The request is " + r.status());
        }
        TypeRow type = store.type(r.leaveTypeId()).orElseThrow();
        checkSi(type, in);
        store.updateSiClaim(r.id(), seal(r.personId(), in));
        audit.record(new BusinessAction("UPDATE_SI_CLAIM", "att", "leave_request", r.id(), r.legalEntityId(),
                Map.of()));
        return store.request(r.id()).orElseThrow();
    }

    /** The SI claim data in plain; audited (P3 health data, ND13/2023). */
    @AttTransactional(readOnly = true)
    public SiClaimView siClaim(UUID requestId) {
        RequestRow r = store.request(requestId).orElseThrow(() -> ApiException.notFound("Leave request", requestId));
        requireEmployee(AttendancePermissions.LEAVE_SI_DATA_VIEW, r.employmentId(), r.startDate());
        StoredSiClaim s = store.siClaim(requestId).orElseThrow();
        UUID t = TenantContext.current().tenantId();
        audit.record(new BusinessAction("VIEW", "att", "leave_request_si_claim", r.id(), r.legalEntityId(),
                Map.of("employmentId", r.employmentId().toString())));
        return new SiClaimView(s.certificateType(),
                cipher.decrypt(t, s.personId(), "si_certificate_no_enc", s.certificateNo(), s.keyVersion()),
                s.certificateDate(),
                cipher.decrypt(t, s.personId(), "si_issuing_facility_enc", s.issuingFacility(), s.keyVersion()),
                cipher.decrypt(t, s.personId(), "si_diagnosis_category_enc", s.diagnosisCategory(), s.keyVersion()),
                s.longTermIllness(), s.treatmentType(), s.childBirthDate(),
                cipher.decrypt(t, s.personId(), "child_id_enc", s.childId(), s.keyVersion()), s.confinementDate(),
                s.childrenBornCount(), s.caesarean());
    }

    // ---------------------------------------------------------------------------------------------- queries

    @AttTransactional(readOnly = true)
    public RequestRow get(UUID requestId) {
        RequestRow r = store.request(requestId).orElseThrow(() -> ApiException.notFound("Leave request", requestId));
        requireEmployee(AttendancePermissions.LEAVE_REQUEST_VIEW, r.employmentId(), r.startDate());
        return r;
    }

    @AttTransactional(readOnly = true)
    public List<RequestRow> ofEmployment(UUID employmentId, LocalDate from, LocalDate to) {
        range(from, to);
        requireEmployee(AttendancePermissions.LEAVE_REQUEST_VIEW, employmentId, to);
        return store.requestsOf(employmentId, from, to, null);
    }

    @AttTransactional(readOnly = true)
    public List<RequestRow> list(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, String status, LocalDate from,
            LocalDate to, int limit, int offset) {
        range(from, to);
        return store.list(legalEntityId, orgUnitId, leaveTypeId, LeaveInputs.upper(status), from, to,
                authz.scopeFor(AttendancePermissions.LEAVE_REQUEST_VIEW, clock.today()), Math.clamp(limit, 1, 2000),
                Math.max(0, offset));
    }

    // ------------------------------------------------------------------------------------------------ checks

    private record Checked(TypeRow type, PolicyData policy, EmploymentFacts facts, PunchStore.Placement where,
            String unit, String startPart, String endPart, LocalDate end, Preview preview) {
    }

    private Checked check(UUID employmentId, RequestInput in, UUID exceptId, boolean submit) {
        if (in.startDate() == null) {
            throw invalid("start-date-required", "The start date is required");
        }
        TypeRow type = (in.leaveTypeId() != null ? store.type(in.leaveTypeId())
                : store.typeByCode(LeaveInputs.upper(in.leaveTypeCode())))
                .filter(t -> "ACTIVE".equals(t.data().status()))
                .orElseThrow(() -> invalid("unknown-leave-type", "The leave type does not exist or is inactive"));
        var where = requireEmployee(AttendancePermissions.LEAVE_REQUEST_CREATE, employmentId, in.startDate());
        EmploymentFacts facts = store.employment(employmentId, in.startDate())
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        boolean hourly = in.startTime() != null || in.endTime() != null;
        LocalDate end = in.endDate() == null ? in.startDate() : in.endDate();
        if (end.isBefore(in.startDate())) {
            throw invalid("invalid-range", "The end date is on or after the start date");
        }
        if (ChronoUnit.DAYS.between(in.startDate(), end) >= MAX_RANGE_DAYS) {
            throw invalid("range-too-long", "A request covers at most " + MAX_RANGE_DAYS + " days");
        }
        String startPart = LeaveInputs.upper(in.startPart()) == null ? "FULL" : LeaveInputs.upper(in.startPart());
        String endPart = LeaveInputs.upper(in.endPart()) == null ? "FULL" : LeaveInputs.upper(in.endPart());
        if (!LeaveRules.PARTS.contains(startPart) || !LeaveRules.PARTS.contains(endPart)) {
            throw invalid("invalid-part", "Parts are FULL, AM or PM");
        }
        if (in.startDate().equals(end) && "PM".equals(startPart) && "AM".equals(endPart)) {
            throw invalid("invalid-part", "A one-day request cannot start in the afternoon and end at noon");
        }
        String unit = hourly ? "HOUR" : "FULL".equals(startPart) && "FULL".equals(endPart)
                || "AM".equals(startPart) && !in.startDate().equals(end) && "FULL".equals(endPart) ? "DAY"
                : "HALF_DAY";
        String min = type.data().minUnit();
        if ("HOUR".equals(unit) && !"HOUR".equals(min) || "HALF_DAY".equals(unit) && "DAY".equals(min)) {
            throw invalid("unit-not-allowed", type.data().code() + " is taken in units of " + min);
        }
        // employment: nothing before hire, nothing after the last working day (P10)
        if (!facts.employedOn(in.startDate()) || !facts.employedOn(end)) {
            throw invalid("outside-employment", "Leave lies within the employment, up to the last working day");
        }
        // frozen attendance periods
        if (!store.frozenPeriods(where.legalEntityId(), in.startDate(), end).isEmpty()) {
            throw ApiException.conflict("period-frozen", "An attendance period of these days is closed or locked; "
                    + "reopen it first");
        }
        List<String> warnings = new ArrayList<>();
        // duration
        BigDecimal days;
        BigDecimal hours = null;
        List<CountedDay> counted;
        if (hourly) {
            if (in.startTime() == null || in.endTime() == null || !in.endTime().isAfter(in.startTime())
                    || !in.startDate().equals(end)) {
                throw invalid("invalid-hours", "Hourly leave is one day with an end time after the start time");
            }
            PlannedDay p = entitlements.planned(employmentId, in.startDate(), in.startDate()).getFirst();
            if (!p.isWorkingDay() || p.stdHours() == null || p.stdHours().signum() <= 0) {
                throw invalid("no-working-day", "Hourly leave is taken on a working day of the schedule");
            }
            hours = BigDecimal.valueOf(Duration.between(in.startTime(), in.endTime()).toMinutes())
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
            days = hours.divide(p.stdHours(), 2, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            counted = List.of(new CountedDay(in.startDate(), days, "FULL"));
            if (store.hourOverlap(employmentId, in.startDate(), in.startTime(), in.endTime())) {
                throw ApiException.conflict("leave-overlap", "Another pending or approved leave covers this time");
            }
        } else {
            List<PlanDay> plan = "CALENDAR_DAY".equals(type.data().countingBasis())
                    ? in.startDate().datesUntil(end.plusDays(1)).map(d -> new PlanDay(d, true, BigDecimal.ONE)).toList()
                    : entitlements.plan(employmentId, in.startDate(), end);
            counted = LeaveRules.countedDays(plan, type.data().countingBasis(), startPart, endPart);
            days = LeaveRules.total(counted);
            if (days.signum() == 0) {
                throw invalid("no-working-days", "The range has no working day in the schedule");
            }
            boolean hourlyInRange = store.requestsOf(employmentId, in.startDate(), end, OPEN).stream()
                    .anyMatch(o -> "HOUR".equals(o.requestUnit()) && !o.id().equals(exceptId));
            if (hourlyInRange) {
                throw ApiException.conflict("leave-overlap", "An hourly leave lies in the range");
            }
        }
        // maxima
        if (type.data().maxDaysPerRequest() != null && days.compareTo(type.data().maxDaysPerRequest()) > 0) {
            throw invalid("max-per-request", type.data().code() + " allows at most " + type.data().maxDaysPerRequest()
                    + " days per request");
        }
        PolicyData policy = store.policyFor(type.id(), where.legalEntityId(), facts.employeeCategoryCode(),
                in.startDate()).map(PolicyRow::data).orElse(null);
        int startMonth = policy == null ? 1 : policy.leaveYearStartMonth();
        if (type.data().maxDaysPerYear() != null) {
            int y = LeaveRules.leaveYearOf(in.startDate(), startMonth);
            BigDecimal used = store.daysRequested(employmentId, type.id(), LeaveRules.yearStart(y, startMonth),
                    LeaveRules.yearEnd(y, startMonth));
            if (used.add(days).compareTo(type.data().maxDaysPerYear()) > 0) {
                throw invalid("max-per-year", type.data().code() + " allows at most " + type.data().maxDaysPerYear()
                        + " days per year (" + used + " already requested)");
            }
        }
        eligibility(type, facts, in.startDate(), warnings);
        // notice (BR-ATT-062)
        if (policy != null && policy.noticeDays() > 0
                && ChronoUnit.DAYS.between(clock.today(), in.startDate()) < policy.noticeDays()) {
            throw invalid("notice-too-short", type.data().code() + " is requested at least " + policy.noticeDays()
                    + " days ahead");
        }
        if (submit && type.data().requiresAttachment()
                && (in.attachmentDocumentIds() == null || in.attachmentDocumentIds().isEmpty())) {
            throw invalid("attachment-required", type.data().code() + " needs a supporting document");
        }
        if (submit) {
            checkSi(type, in.siClaim());
        }
        // balance per leave year, PENDING requests reserve their days (BR-ATT-062)
        Map<Integer, BigDecimal> perYear = new TreeMap<>();
        for (CountedDay d : counted) {
            perYear.merge(LeaveRules.leaveYearOf(d.date(), startMonth), d.quantity(), BigDecimal::add);
        }
        Map<Integer, BigDecimal> available = new TreeMap<>();
        if (type.data().tracksBalance()) {
            BigDecimal negative = policy == null ? BigDecimal.ZERO : policy.allowNegativeDays();
            for (var e : perYear.entrySet()) {
                BigDecimal avail = balances.available(employmentId, type.id(), e.getKey(), startMonth, exceptId);
                BigDecimal after = avail.subtract(e.getValue());
                available.put(e.getKey(), after);
                if (after.add(negative).signum() < 0) {
                    if (submit) {
                        throw ApiException.conflict("insufficient-balance", "Leave year " + e.getKey() + ": "
                                + avail + " days available, " + e.getValue() + " requested"
                                + (negative.signum() > 0 ? " (" + negative + " days below zero allowed)" : ""));
                    }
                    warnings.add("insufficient-balance " + e.getKey());
                }
            }
        }
        Preview preview = new Preview(type.data().code(), unit, days, hours, counted, perYear, available, warnings);
        return new Checked(type, policy, facts, where, unit, startPart, endPart, end, preview);
    }

    private void eligibility(TypeRow type, EmploymentFacts facts, LocalDate start, List<String> warnings) {
        Map<String, Object> e = type.data().eligibility();
        Object months = e.get("min_service_months");
        if (months != null && LeaveRules.serviceMonths(facts.serviceStart(), start)
                < new BigDecimal(months.toString()).longValue()) {
            throw invalid("not-eligible-service", type.data().code() + " needs " + months + " months of service");
        }
        if (Boolean.FALSE.equals(e.get("allow_probation")) && facts.probationEndDate() != null
                && !start.isAfter(facts.probationEndDate())) {
            throw invalid("not-eligible-probation", type.data().code() + " is not available during probation");
        }
        Object cats = e.get("employee_categories");
        if (cats instanceof List<?> list && !list.isEmpty()
                && (facts.employeeCategoryCode() == null || !list.contains(facts.employeeCategoryCode()))) {
            throw invalid("not-eligible-category", type.data().code() + " is not available to the employee category");
        }
        Object gender = e.get("gender");
        if (gender != null) {
            String g = gender(facts.employmentId(), start);
            if (g == null) {
                warnings.add("eligibility-gender-unverified");
            } else if (!g.equals(gender.toString())) {
                throw invalid("not-eligible-gender", type.data().code() + " is not available to the employee");
            }
        }
    }

    /** M / F from People's directory (masked snapshot), null when not visible or not recorded. */
    private String gender(UUID employmentId, LocalDate asOf) {
        EmployeeDirectory dir = directory.getIfAvailable();
        if (dir == null) {
            return null;
        }
        try {
            return dir.snapshot(employmentId, asOf, Instant.now()).map(s -> s.person().genderCode())
                    .filter(Objects::nonNull).map(c -> c.strip().toUpperCase(Locale.ROOT))
                    .filter(c -> c.equals("M") || c.equals("F")) // code list GENDER
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void checkSi(TypeRow type, SiClaimInput si) {
        if (!type.data().requiresSiClaimData()) {
            return;
        }
        if (si == null || LeaveInputs.text(si.certificateType()) == null) {
            throw invalid("si-claim-required", type.data().code() + " needs the SI certificate type (BR-ATT-063)");
        }
        if ("CHILD_SICK".equals(type.data().category()) && si.childBirthDate() == null) {
            throw invalid("si-claim-required", "Child sick leave needs the child's birth date");
        }
        if (si.treatmentType() != null && !List.of("OUTPATIENT", "INPATIENT").contains(si.treatmentType())) {
            throw invalid("invalid-treatment-type", "The treatment type is OUTPATIENT or INPATIENT");
        }
    }

    private SiClaim seal(UUID personId, SiClaimInput in) {
        if (in == null) {
            return null;
        }
        UUID t = TenantContext.current().tenantId();
        return new SiClaim(LeaveInputs.upper(in.certificateType()),
                cipher.encrypt(t, personId, "si_certificate_no_enc", in.certificateNo()),
                AttCipher.last4(in.certificateNo()), in.certificateDate(),
                cipher.encrypt(t, personId, "si_issuing_facility_enc", in.issuingFacility()),
                cipher.encrypt(t, personId, "si_diagnosis_category_enc", in.diagnosisCategory()),
                in.longTermIllness(), in.treatmentType(), in.childBirthDate(),
                cipher.encrypt(t, personId, "child_id_enc", in.childId()), AttCipher.last4(in.childId()),
                in.confinementDate(), in.childrenBornCount(), in.caesarean());
    }

    // ---------------------------------------------------------------------------------------------- approval

    /** TAKE rows per leave year of an approved balance-tracked request (BR-ATT-068). */
    private void take(RequestRow r, TypeRow type) {
        EmploymentFacts facts = store.employment(r.employmentId(), r.startDate()).orElseThrow();
        PolicyData policy = store.policyFor(type.id(), r.legalEntityId(), facts.employeeCategoryCode(), r.startDate())
                .map(PolicyRow::data).orElse(null);
        int startMonth = policy == null ? 1 : policy.leaveYearStartMonth();
        Map<Integer, BigDecimal> perYear = new TreeMap<>();
        Map<Integer, LocalDate> firstDay = new TreeMap<>();
        if ("HOUR".equals(r.requestUnit()) || LeaveRules.leaveYearOf(r.startDate(), startMonth)
                == LeaveRules.leaveYearOf(r.endDate(), startMonth)) {
            int y = LeaveRules.leaveYearOf(r.startDate(), startMonth);
            perYear.put(y, r.durationDays());
            firstDay.put(y, r.startDate());
        } else {
            List<PlanDay> plan = "CALENDAR_DAY".equals(type.data().countingBasis())
                    ? r.startDate().datesUntil(r.endDate().plusDays(1)).map(d -> new PlanDay(d, true, BigDecimal.ONE))
                            .toList()
                    : entitlements.plan(r.employmentId(), r.startDate(), r.endDate());
            for (CountedDay d : LeaveRules.countedDays(plan, type.data().countingBasis(), r.startPart(),
                    r.endPart())) {
                int y = LeaveRules.leaveYearOf(d.date(), startMonth);
                perYear.merge(y, d.quantity(), BigDecimal::add);
                firstDay.putIfAbsent(y, d.date());
            }
        }
        perYear.forEach((y, q) -> {
            if (q.signum() > 0) {
                store.insertTxn(new NewTxn(r.employmentId(), r.legalEntityId(), r.orgUnitId(), type.id(),
                        type.data().code(), y, "TAKE", q.negate(), firstDay.get(y), null, null, r.id(), "LEAVE_REQUEST",
                        r.id(), null, null, "APP"));
            }
        });
    }

    private void publish(RequestRow r, TypeRow type, String status) {
        boolean retro = !store.frozenPeriods(r.legalEntityId(), r.startDate(), r.endDate()).isEmpty();
        outbox.publish(new AbsenceChanged(TenantContext.current().tenantId(), r.legalEntityId(), r.id(),
                r.employmentId(), r.personId(), type.data().code(), type.data().category(), type.data().paidBy(),
                type.data().payrollCategory(), type.data().attendanceCode(), r.startDate(), r.endDate(),
                r.startPart(), r.endPart(), r.actualReturnDate(), r.effectiveEnd(), r.durationDays(), status,
                r.hasSiClaimData(), r.siCertificateType(), retro));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private PunchStore.Placement requireEmployee(String permission, UUID employmentId, LocalDate date) {
        PunchStore.Placement p = punches.placement(employmentId, date)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!authz.can(permission, new AuthorizationTarget.Employee(employmentId, p.legalEntityId(), p.orgUnitId(),
                p.locationId()), date)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "employment-not-in-scope",
                    "Employment " + employmentId + " is outside your data scope");
        }
        return p;
    }

    static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw invalid("invalid-range", "Give from and to (to on or after from)");
        }
        if (Period.between(from, to).toTotalMonths() >= 24) {
            throw invalid("range-too-long", "The range is at most 2 years");
        }
    }

    static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }
}
