package vn.yourhrm.attendance.internal.application.punch;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.domain.punch.PunchMatching;
import vn.yourhrm.attendance.internal.domain.punch.PunchMatching.Decision;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.DeviceRow;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.PunchRow;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The quarantine of punches that could not be matched (BR-ATT-022, US-ATT-015): list with filters, counts for the
 * close checklist, re-match in bulk after the admin fixed a mapping or registered a device ("map, then release"), and
 * ignore with a reason. Only the matching columns of a punch change, once it is resolved never again
 * ({@code att.tg_raw_punch_guard}); the evidence columns stay as imported (BR-ATT-027). Scoped by the device's legal
 * entity / location; punches of unregistered terminals need a tenant-wide scope.
 */
@Service
public class QuarantineService {

    /** Longest date range of one list / re-match call (partition pruning on the monthly partitions). */
    static final int MAX_RANGE_DAYS = 62;

    public record RematchResult(int examined, int matched, int stillQuarantined) {
    }

    /** A punch to ignore: id plus its partition key. */
    public record PunchRef(UUID id, LocalDate workDateHint) {
    }

    private final PunchStore store;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public QuarantineService(PunchStore store, AuthorizationService authz, AuditLog audit, BusinessClock clock,
            ApplicationEventPublisher events) {
        this.events = events;
        this.store = store;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    @AttTransactional(readOnly = true)
    public List<PunchRow> list(LocalDate from, LocalDate to, String status, UUID deviceId, String terminalCode,
            String enrolId, int limit, int offset) {
        range(from, to);
        List<PunchRow> rows = store.quarantine(from, to, status(status), deviceId, DeviceService.text(terminalCode),
                DeviceService.text(enrolId), scope(AttendancePermissions.PUNCH_VIEW), Math.clamp(limit, 1, 1000),
                Math.max(0, offset));
        audit.record(new BusinessAction("VIEW", "att", "raw_punch_quarantine", null, null, Map.of("from",
                from.toString(), "to", to.toString(), "rows", String.valueOf(rows.size()))));
        return rows;
    }

    /** Unresolved punches per status of a legal entity's devices (feeds the close checklist, US-ATT-015/030). */
    @AttTransactional(readOnly = true)
    public Map<String, Long> counts(UUID legalEntityId, LocalDate from, LocalDate to) {
        range(from, to);
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] r : store.quarantineCounts(legalEntityId, from, to, scope(AttendancePermissions.PUNCH_VIEW))) {
            out.put((String) r[0], (Long) r[1]);
        }
        return out;
    }

    /**
     * Matches again every unresolved punch in the range (optionally of one device / enrol id) with the current
     * devices and mappings; matched punches become VALID, the others keep (or change) their quarantine reason.
     */
    @AttTransactional
    public RematchResult rematch(LocalDate from, LocalDate to, UUID deviceId, String enrolId) {
        range(from, to);
        ScopePredicate scope = scope(AttendancePermissions.PUNCH_RESOLVE);
        int examined = 0;
        int matched = 0;
        int still = 0;
        Map<String, DeviceRow> devices = new LinkedHashMap<>();
        Map<UUID, LocalDate[]> matchedDays = new LinkedHashMap<>();
        while (true) {
            // matched rows leave the list; quarantined ones are paged past
            List<PunchRow> page = store.quarantine(from, to, null, deviceId, null, DeviceService.text(enrolId), scope,
                    500, still);
            if (page.isEmpty()) {
                break;
            }
            for (PunchRow p : page) {
                examined++;
                UUID dev = p.deviceId();
                if (dev == null) {
                    DeviceRow d = devices.computeIfAbsent(p.terminalCode().toUpperCase(java.util.Locale.ROOT),
                            k -> store.deviceByTerminalOrCode(p.terminalCode()).orElse(null));
                    // a terminal registered after the import: the evidence keeps device_id NULL, matching uses it
                    dev = d == null || !scope.covers(d.data().legalEntityId(), null, d.data().locationId(), null)
                            ? null : d.id();
                }
                Decision d = PunchMatching.decide(dev, p.enrolId(), store.spansOfEnrol(p.enrolId()),
                        p.workDateHint());
                if (d.valid()) {
                    if (store.resolve(p.id(), p.workDateHint(), PunchMatching.VALID, d.employmentId(),
                            store.placement(d.employmentId(), p.workDateHint()).orElse(null), d.mappingId())) {
                        matched++;
                        matchedDays.merge(d.employmentId(), new LocalDate[] {p.workDateHint(), p.workDateHint()},
                                (a, b) -> new LocalDate[] {a[0].isBefore(b[0]) ? a[0] : b[0],
                                        a[1].isAfter(b[1]) ? a[1] : b[1]});
                    }
                } else {
                    if (!d.status().equals(p.validationStatus())) {
                        store.resolve(p.id(), p.workDateHint(), d.status(), null, null, null);
                    }
                    still++;
                }
            }
        }
        audit.record(new BusinessAction("REMATCH", "att", "raw_punch_quarantine", deviceId, null, Map.of("from",
                from.toString(), "to", to.toString(), "examined", String.valueOf(examined), "matched",
                String.valueOf(matched))));
        if (!matchedDays.isEmpty()) {
            // daily processing recomputes these days after commit (BR-ATT-038)
            events.publishEvent(new PunchesMatched(vn.yourhrm.shared.common.tenancy.TenantContext.current().tenantId(),
                    matchedDays));
        }
        return new RematchResult(examined, matched, still);
    }

    /** Marks the punches IGNORED (e.g. a test badge, a visitor); the reason goes to the audit log. */
    @AttTransactional
    public int ignore(List<PunchRef> punches, String reason) {
        String why = DeviceService.text(reason);
        if (why == null) {
            throw DeviceService.invalid("reason-required", "A reason is required to ignore punches");
        }
        if (punches == null || punches.isEmpty() || punches.size() > 1000) {
            throw DeviceService.invalid("invalid-selection", "Select 1 to 1000 punches");
        }
        ScopePredicate scope = scope(AttendancePermissions.PUNCH_RESOLVE);
        int done = 0;
        for (PunchRef ref : punches) {
            PunchRow p = store.punch(ref.id(), ref.workDateHint())
                    .orElseThrow(() -> ApiException.notFound("Punch", ref.id()));
            DeviceRow d = p.deviceId() == null ? null : store.device(p.deviceId()).orElse(null);
            boolean visible = d == null ? scope.tenantWide()
                    : scope.covers(d.data().legalEntityId(), null, d.data().locationId(), null);
            if (!visible) {
                throw ApiException.notFound("Punch", ref.id());
            }
            if ("VALID".equals(p.validationStatus()) || PunchMatching.IGNORED.equals(p.validationStatus())) {
                throw ApiException.conflict("punch-resolved", "Punch " + p.id() + " is already "
                        + p.validationStatus());
            }
            if (store.resolve(p.id(), p.workDateHint(), PunchMatching.IGNORED, null, null, null)) {
                done++;
                audit.record(new BusinessAction("IGNORE", "att", "raw_punch", p.id(),
                        d == null ? null : d.data().legalEntityId(),
                        Map.of("reason", why, "previousStatus", p.validationStatus())));
            }
        }
        return done;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ScopePredicate scope(String permission) {
        return authz.scopeFor(permission, clock.today());
    }

    static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw DeviceService.invalid("invalid-range", "Give from and to (to on or after from)");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw DeviceService.invalid("range-too-long", "The range is at most " + MAX_RANGE_DAYS + " days");
        }
    }

    private static String status(String raw) {
        String s = DeviceService.upper(raw);
        if (s != null && !List.of("PENDING", PunchMatching.UNMAPPED, PunchMatching.EXPIRED,
                PunchMatching.UNKNOWN_DEVICE).contains(s)) {
            throw DeviceService.invalid("invalid-status", "The status is PENDING or QUARANTINED_*");
        }
        return s;
    }
}
