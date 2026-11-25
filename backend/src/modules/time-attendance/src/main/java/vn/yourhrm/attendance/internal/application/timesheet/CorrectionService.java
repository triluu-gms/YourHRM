package vn.yourhrm.attendance.internal.application.timesheet;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CodeRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CorrectionRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.DayRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.NewCorrection;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;

/**
 * Corrections of a day (BR-ATT-036/037, EP-ATT-05). A correction never edits the computed day: it is an input that
 * daily processing applies on every recalculation, so the original punches stay as evidence and the history keeps
 * every change.
 * <ul>
 *   <li><b>Requests</b> (ESS, or HR on behalf): missing punch, wrong time, forgotten check-in, client site, code change
 *       (CT / WFH in P1), with a reason and attachments; approved through Core's workflow
 *       ({@code att.ATT_CORRECTION}); a monthly limit per employee applies ({@code att.correction_monthly_limit}).
 *       On approval the day is recomputed and the request becomes APPLIED.</li>
 *   <li><b>Admin / bulk edits</b>: a code and / or the in / out times of one or many days, with a mandatory reason;
 *       applied at once, the day keeps a before / after record in {@code field_changes} and is marked as manually
 *       overridden.</li>
 * </ul>
 * Days of a CLOSED or LOCKED period cannot be corrected (reopen the period first; BR-ATT-079).
 */
@Service
public class CorrectionService {

    static final Set<String> REQUEST_TYPES = Set.of("MISSING_PUNCH", "WRONG_TIME", "FORGOT_CHECKIN", "CLIENT_SITE",
            "CODE_CHANGE");
    /** Codes an employee may request for a day (P1: business trip and WFH are codes, BR-ATT-070 is P2). */
    static final Set<String> REQUESTABLE_KINDS = Set.of("WORK", "TRIP", "WFH");
    static final int MAX_BULK = 500;

    public record RequestInput(LocalDate workDate, String correctionType, Instant requestedIn, Instant requestedOut,
            String requestedCode, String reasonCode, String reasonText, List<UUID> attachmentDocumentIds) {
    }

    public record EditItem(UUID employmentId, LocalDate workDate) {
    }

    public record EditInput(List<EditItem> days, String code, Instant inAt, Instant outAt, String reason) {
    }

    public record EditResult(UUID bulkBatchId, List<UUID> correctionIds, int applied) {
    }

    private final TimesheetStore store;
    private final PunchStore punches;
    private final TimesheetProcessor processor;
    private final WorkflowService workflow;
    private final Settings settings;
    private final AuthorizationService authz;
    private final BusinessClock clock;
    private final JsonMapper json;

    public CorrectionService(TimesheetStore store, PunchStore punches, TimesheetProcessor processor,
            WorkflowService workflow, Settings settings, AuthorizationService authz, BusinessClock clock,
            JsonMapper json) {
        this.store = store;
        this.punches = punches;
        this.processor = processor;
        this.workflow = workflow;
        this.settings = settings;
        this.authz = authz;
        this.clock = clock;
        this.json = json;
    }

    // --------------------------------------------------------------------------------------------- requests

    @AttTransactional
    public CorrectionRow request(UUID employmentId, RequestInput in) {
        if (in.workDate() == null) {
            throw invalid("work-date-required", "The work date is required");
        }
        var where = requireEmployee(AttendancePermissions.TIMESHEET_CORRECTION_REQUEST, employmentId, in.workDate());
        String type = TimesheetInputs.upper(in.correctionType());
        if (type == null || !REQUEST_TYPES.contains(type)) {
            throw invalid("invalid-correction-type", "The correction type is one of " + REQUEST_TYPES);
        }
        if (in.workDate().isAfter(clock.today())) {
            throw invalid("future-day", "A day in the future cannot be corrected");
        }
        String code = TimesheetInputs.upper(in.requestedCode());
        switch (type) {
            case "WRONG_TIME" -> {
                if (in.requestedIn() == null || in.requestedOut() == null) {
                    throw invalid("times-required", "A wrong-time correction gives both the in and the out time");
                }
            }
            case "MISSING_PUNCH", "FORGOT_CHECKIN" -> {
                if (in.requestedIn() == null && in.requestedOut() == null) {
                    throw invalid("time-required", "Give the missing in and / or out time");
                }
            }
            case "CODE_CHANGE" -> {
                if (code == null) {
                    throw invalid("code-required", "A code change names the requested code (e.g. CT, WFH)");
                }
            }
            default -> {
                if (code == null && in.requestedIn() == null && in.requestedOut() == null) {
                    throw invalid("code-or-time-required", "Give the code or the times worked at the client site");
                }
            }
        }
        if (code != null) {
            requireCode(code, REQUESTABLE_KINDS);
            // CT / WFH / X stand for a worked working day; on a rest day or holiday they would not apply (the
            // calculator ignores them there), so refuse them up front. Work on such a day is overtime.
            store.day(employmentId, in.workDate()).filter(d -> !"WORKDAY".equals(d.dayType())).ifPresent(d -> {
                throw invalid("code-on-non-working-day", "Code " + code + " applies to a working day only; "
                        + in.workDate() + " is a " + d.dayType() + " (request overtime for work on that day)");
            });
        }
        checkTimes(in.workDate(), in.requestedIn(), in.requestedOut());
        if (in.reasonText() == null || in.reasonText().isBlank()) {
            throw invalid("reason-required", "A reason is required");
        }
        requireOpen(employmentId, where, in.workDate());
        long limit = settings.longValue(TimesheetCatalogues.SETTING_CORRECTION_LIMIT,
                new Settings.Scope(where.legalEntityId(), null), in.workDate());
        if (limit > 0 && store.requestsInMonth(employmentId, in.workDate()) >= limit) {
            throw ApiException.conflict("correction-limit-reached", "At most " + limit
                    + " correction requests per month are allowed");
        }
        UUID id = store.insertCorrection(new NewCorrection(employmentId, in.workDate(), where.legalEntityId(),
                where.orgUnitId(), type, in.requestedIn(), in.requestedOut(), code, TimesheetInputs.text(in.reasonCode()),
                in.reasonText().strip(), in.attachmentDocumentIds() == null ? List.of() : in.attachmentDocumentIds(),
                "ESS", null, "PENDING"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("workDate", in.workDate().toString());
        summary.put("correctionType", type);
        if (in.requestedIn() != null) {
            summary.put("requestedIn", in.requestedIn().toString());
        }
        if (in.requestedOut() != null) {
            summary.put("requestedOut", in.requestedOut().toString());
        }
        if (code != null) {
            summary.put("requestedCode", code);
        }
        summary.put("reason", in.reasonText().strip());
        UUID person = punches.personOf(employmentId).orElse(null);
        var started = workflow.start(new StartRequest(TimesheetCatalogues.PROCESS_CORRECTION,
                TimesheetCatalogues.SUBJECT_CORRECTION, id, 0, "att-correction-" + id, null, person,
                where.legalEntityId(), where.orgUnitId(), where.locationId(), null, summary,
                Map.of("correctionType", type), Map.of()));
        store.setWorkflow(id, started.instanceId());
        if ("APPROVED".equals(started.status())) {
            applyOutcome(id, "APPROVED", null);
        }
        return store.correction(id).orElseThrow();
    }

    /** Applies the final workflow outcome (listener); idempotent. */
    @AttTransactional
    public Map<String, Object> applyOutcome(UUID correctionId, String outcome, UUID decidedBy) {
        CorrectionRow c = store.correction(correctionId)
                .orElseThrow(() -> ApiException.notFound("Timesheet correction", correctionId));
        if (!"PENDING".equals(c.status())) {
            return Map.of("status", c.status());
        }
        switch (outcome) {
            case "APPROVED" -> {
                store.setCorrectionStatus(c.id(), "PENDING", "APPROVED", decidedBy, null, null);
                return Map.of("status", apply(c, null));
            }
            case "REJECTED" -> {
                store.setCorrectionStatus(c.id(), "PENDING", "REJECTED", decidedBy, null, null);
                return Map.of("status", "REJECTED");
            }
            default -> {
                store.setCorrectionStatus(c.id(), "PENDING", "CANCELLED", decidedBy, null, null);
                return Map.of("status", "CANCELLED");
            }
        }
    }

    // ------------------------------------------------------------------------------------------- admin edits

    @AttTransactional
    public EditResult edit(EditInput in) {
        String reason = TimesheetInputs.text(in.reason());
        if (reason == null) {
            throw invalid("reason-required", "A reason is required for an admin edit (BR-ATT-037)");
        }
        if (in.days() == null || in.days().isEmpty() || in.days().size() > MAX_BULK) {
            throw invalid("invalid-selection", "Select 1 to " + MAX_BULK + " days");
        }
        String code = TimesheetInputs.upper(in.code());
        if (code == null && in.inAt() == null && in.outAt() == null) {
            throw invalid("nothing-to-change", "Give a code and / or the in / out times");
        }
        if (code != null) {
            requireCode(code, null);
        }
        if (in.inAt() != null && in.outAt() != null && !in.outAt().isAfter(in.inAt())) {
            throw invalid("invalid-times", "The out time must be after the in time");
        }
        if ((in.inAt() != null || in.outAt() != null) && in.days().size() > 1) {
            throw invalid("times-single-day", "In / out times can be set for one day at a time");
        }
        UUID batch = in.days().size() > 1 ? vn.yourhrm.shared.common.id.Ids.newId() : null;
        List<UUID> ids = new ArrayList<>();
        int applied = 0;
        for (EditItem item : in.days().stream().distinct().toList()) {
            var where = requireEmployee(AttendancePermissions.TIMESHEET_UPDATE, item.employmentId(), item.workDate());
            if (item.workDate().isAfter(clock.today())) {
                throw invalid("future-day", "A day in the future cannot be edited");
            }
            checkTimes(item.workDate(), in.inAt(), in.outAt());
            requireOpen(item.employmentId(), where, item.workDate());
            UUID id = store.insertCorrection(new NewCorrection(item.employmentId(), item.workDate(),
                    where.legalEntityId(), where.orgUnitId(), "ADMIN_EDIT", in.inAt(), in.outAt(), code, null, reason,
                    List.of(), batch == null ? "ADMIN" : "BULK", batch, "APPROVED"));
            ids.add(id);
            CorrectionRow c = store.correction(id).orElseThrow();
            if ("APPLIED".equals(apply(c, store.day(item.employmentId(), item.workDate()).orElse(null)))) {
                applied++;
            }
        }
        return new EditResult(batch, ids, applied);
    }

    /** @param created false when the same values were imported for the day before (nothing written) */
    public record ImportedDay(UUID correctionId, boolean created, String status) {
    }

    /**
     * One day of a daily-timesheet import ({@code ATT_TIMESHEET_DAY}, BR-ATT-025 / 037, US-ATT-018): a correction of
     * source IMPORT (type ADMIN_EDIT, APPROVED at once, {@code bulk_batch_id} = the import job, mandatory reason) that
     * daily processing applies on every recalculation, so the day is marked as manually overridden and the original
     * punches stay as evidence. In and out times replace the day's punches; a code alone sets the day's code.
     * Idempotent: when the day's latest effective correction is an import with the same values, nothing is written.
     */
    @AttTransactional
    public ImportedDay importDay(UUID employmentId, LocalDate workDate, String code, Instant inAt, Instant outAt,
            String reason, UUID importJobId) {
        String why = TimesheetInputs.text(reason);
        if (why == null) {
            throw invalid("reason-required", "A reason is required for an imported day (BR-ATT-037)");
        }
        String c = TimesheetInputs.upper(code);
        if (c == null && inAt == null && outAt == null) {
            throw invalid("nothing-to-change", "Give a code and / or the in and out times");
        }
        if ((inAt == null) != (outAt == null)) {
            throw invalid("times-incomplete", "An imported day gives both the in and the out time, or neither");
        }
        var where = requireEmployee(AttendancePermissions.TIMESHEET_IMPORT, employmentId, workDate);
        if (workDate.isAfter(clock.today())) {
            throw invalid("future-day", "A day in the future cannot be imported");
        }
        if (c != null) {
            requireCode(c, null);
        }
        checkTimes(workDate, inAt, outAt);
        requireOpen(employmentId, where, workDate);
        List<CorrectionRow> effective = store.corrections(employmentId, workDate, workDate, true);
        if (!effective.isEmpty()) {
            CorrectionRow last = effective.getLast();
            if ("IMPORT".equals(last.source()) && Objects.equals(last.requestedCode(), c)
                    && Objects.equals(last.requestedInAt(), inAt) && Objects.equals(last.requestedOutAt(), outAt)) {
                return new ImportedDay(last.id(), false, last.status());
            }
        }
        UUID id = store.insertCorrection(new NewCorrection(employmentId, workDate, where.legalEntityId(),
                where.orgUnitId(), "ADMIN_EDIT", inAt, outAt, c, null, why, List.of(), "IMPORT", importJobId,
                "APPROVED"));
        CorrectionRow row = store.correction(id).orElseThrow();
        return new ImportedDay(id, true, apply(row, store.day(employmentId, workDate).orElse(null)));
    }

    /** IANA zone of the employment's location on {@code date}, for local times of an import. */
    @AttTransactional(readOnly = true)
    public java.time.ZoneId zoneOf(UUID employmentId, LocalDate date) {
        return punches.placement(employmentId, date).flatMap(p -> store.locationZone(p.locationId(), date))
                .map(z -> {
                    try {
                        return java.time.ZoneId.of(z);
                    } catch (RuntimeException e) {
                        return null;
                    }
                }).orElse(TimesheetProcessor.DEFAULT_ZONE);
    }

    /** Recomputes the day of an APPROVED correction; APPLIED with before / after values when the day was written. */
    private String apply(CorrectionRow c, DayRow before) {
        DayRow prior = before != null ? before : store.day(c.employmentId(), c.workDate()).orElse(null);
        var summary = processor.recomputeInTx(c.employmentId(), c.workDate(), c.workDate());
        DayRow after = store.day(c.employmentId(), c.workDate()).orElse(null);
        if (after == null || summary.frozen() > 0 || !summary.failures().isEmpty()) {
            // the period closed meanwhile (or the day could not be written): it applies on the next recalculation
            return "APPROVED";
        }
        String changes = json.writeValueAsString(diff(prior, after));
        store.setCorrectionStatus(c.id(), "APPROVED", "APPLIED", null, after.calcVersion(), changes);
        return "APPLIED";
    }

    static List<Map<String, Object>> diff(DayRow a, DayRow b) {
        Map<String, Object[]> pairs = new LinkedHashMap<>();
        pairs.put("day_status", new Object[] {a == null ? null : a.dayStatus(), b.dayStatus()});
        pairs.put("primary_code", new Object[] {a == null ? null : a.primaryCode(), b.primaryCode()});
        pairs.put("codes", new Object[] {a == null ? null : a.codes(), b.codes()});
        pairs.put("first_in_at", new Object[] {a == null ? null : a.firstInAt(), b.firstInAt()});
        pairs.put("last_out_at", new Object[] {a == null ? null : a.lastOutAt(), b.lastOutAt()});
        pairs.put("worked_hours", new Object[] {a == null ? null : a.workedHours(), b.workedHours()});
        pairs.put("day_value", new Object[] {a == null ? null : a.dayValue(), b.dayValue()});
        pairs.put("exceptions", new Object[] {a == null ? null : a.exceptions(), b.exceptions()});
        List<Map<String, Object>> out = new ArrayList<>();
        pairs.forEach((field, v) -> {
            if (!Objects.equals(String.valueOf(v[0]), String.valueOf(v[1]))) {
                Map<String, Object> m = new HashMap<>();
                m.put("field", field);
                m.put("old", v[0]);
                m.put("new", v[1]);
                out.add(m);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void requireCode(String code, Set<String> kinds) {
        CodeRow r = store.effectiveCodes().get(code);
        if (r == null || !"ACTIVE".equals(r.status())) {
            throw invalid("unknown-attendance-code", "Attendance code " + code + " does not exist or is inactive");
        }
        if (kinds != null && !kinds.contains(r.kind())) {
            throw invalid("code-not-requestable", "Code " + code + " cannot be requested (leave uses a leave request)");
        }
    }

    private static void checkTimes(LocalDate day, Instant in, Instant out) {
        Instant lo = day.minusDays(1).atStartOfDay(TimesheetProcessor.DEFAULT_ZONE).toInstant();
        Instant hi = day.plusDays(2).atStartOfDay(TimesheetProcessor.DEFAULT_ZONE).toInstant();
        for (Instant t : new Instant[] {in, out}) {
            if (t != null && (t.isBefore(lo) || !t.isBefore(hi))) {
                throw invalid("time-outside-day", "The times must lie between the day before and the day after");
            }
        }
        if (in != null && out != null && (!out.isAfter(in) || Duration.between(in, out).toHours() > 24)) {
            throw invalid("invalid-times", "The out time must be after the in time, within 24 hours");
        }
    }

    private void requireOpen(UUID employmentId, PunchStore.Placement where, LocalDate date) {
        UUID pg = store.payGroup(employmentId, date).orElse(null);
        store.periodOn(where.legalEntityId(), pg, date)
                .filter(p -> "CLOSED".equals(p.status()) || "LOCKED".equals(p.status()))
                .ifPresent(p -> {
                    throw ApiException.conflict("day-frozen", "The attendance period of " + date + " is "
                            + p.status() + "; reopen it before correcting the day");
                });
    }

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

    static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }
}
