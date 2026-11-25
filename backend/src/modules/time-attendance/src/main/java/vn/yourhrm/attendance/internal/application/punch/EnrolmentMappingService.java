package vn.yourhrm.attendance.internal.application.punch;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.DeviceRow;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.MappingRow;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.Placement;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Enrolment mappings, device user id ↔ employment, effective-dated (BR-ATT-022, US-ATT-014): per device, or tenant-wide
 * (device NULL) when the same id is enrolled on every terminal. At most one employment per enrol id per device scope
 * on any date ({@code enrolment_mapping_ex}). A mapping version is never deleted: it is ended ({@code validTo},
 * exclusive). Quarantined punches are re-matched explicitly ({@link QuarantineService#rematch}).
 */
@Service
public class EnrolmentMappingService {

    public record MappingInput(UUID deviceId, String enrolId, UUID employmentId, LocalDate validFrom,
            LocalDate validTo) {
    }

    public record MappingView(UUID id, UUID deviceId, String enrolId, UUID employmentId, LocalDate validFrom,
            LocalDate validTo, String source, int version) {

        static MappingView of(MappingRow r) {
            return new MappingView(r.span().id(), r.span().deviceId(), r.span().enrolId(), r.span().employmentId(),
                    r.span().validFrom(), r.span().validTo(), r.source(), r.version());
        }
    }

    private final PunchStore store;
    private final AuthorizationService authz;
    private final BusinessClock clock;

    public EnrolmentMappingService(PunchStore store, AuthorizationService authz, BusinessClock clock) {
        this.store = store;
        this.authz = authz;
        this.clock = clock;
    }

    @AttTransactional(readOnly = true)
    public List<MappingView> list(UUID deviceId, boolean tenantWideOnly, String enrolId, UUID employmentId,
            LocalDate on, int limit, int offset) {
        LocalDate today = clock.today();
        return store.mappings(deviceId, tenantWideOnly, DeviceService.text(enrolId), employmentId, on,
                        authz.scopeFor(AttendancePermissions.DEVICE_VIEW, today), on == null ? today : on,
                        Math.clamp(limit, 1, 500), Math.max(0, offset))
                .stream().map(MappingView::of).toList();
    }

    @AttTransactional
    public MappingView create(MappingInput in) {
        return MappingView.of(store.mapping(createChecked(in, "APP")).orElseThrow());
    }

    /** Sets the exclusive end date (end the mapping, or move its end). */
    @AttTransactional
    public MappingView end(UUID id, int version, LocalDate validTo) {
        MappingRow m = store.mapping(id).orElseThrow(() -> ApiException.notFound("Enrolment mapping", id));
        requireScope(m.span().deviceId(), m.span().employmentId(), m.span().validFrom());
        if (validTo != null && !validTo.isAfter(m.span().validFrom())) {
            throw DeviceService.invalid("invalid-period", "The end date (exclusive) must be after the start date "
                    + m.span().validFrom());
        }
        if (!store.setMappingEnd(id, version, validTo)) {
            throw ApiException.conflict("stale-version", "The mapping was changed by someone else; reload it");
        }
        return MappingView.of(store.mapping(id).orElseThrow());
    }

    /**
     * Creates a mapping version after the checks, inside the caller's T&amp;A transaction (screen or import row);
     * returns its id. {@code source} APP / IMPORT / OPENING_LOAD.
     */
    UUID createChecked(MappingInput in, String source) {
        String enrol = DeviceService.text(in.enrolId());
        if (enrol == null || enrol.length() > 64) {
            throw DeviceService.invalid("enrol-id-required", "The enrol id is required (at most 64 characters)");
        }
        if (in.employmentId() == null || in.validFrom() == null) {
            throw DeviceService.invalid("mapping-incomplete", "The employment and the start date are required");
        }
        if (in.validTo() != null && !in.validTo().isAfter(in.validFrom())) {
            throw DeviceService.invalid("invalid-period", "The end date (exclusive) must be after the start date");
        }
        if (in.deviceId() != null) {
            store.device(in.deviceId()).orElseThrow(() -> DeviceService.invalid("unknown-device",
                    "Device " + in.deviceId() + " not found"));
        }
        store.employment(in.employmentId()).orElseThrow(() -> DeviceService.invalid("unknown-employment",
                "Employment " + in.employmentId() + " is not known to T&A yet"));
        requireScope(in.deviceId(), in.employmentId(), in.validFrom());
        return store.insertMapping(in.deviceId(), enrol, in.employmentId(), in.validFrom(), in.validTo(), source);
    }

    /** The existing version with the same device scope, enrol id and start date (import idempotency). */
    Optional<MappingRow> sameStart(UUID deviceId, String enrolId, LocalDate validFrom) {
        return store.spansOfEnrol(enrolId.strip()).stream()
                .filter(s -> Objects.equals(s.deviceId(), deviceId) && s.validFrom().equals(validFrom))
                .findFirst().flatMap(s -> store.mapping(s.id()));
    }

    /** The device (when given) and the employment (placement on {@code date}) must be in the updater's scope. */
    private void requireScope(UUID deviceId, UUID employmentId, LocalDate date) {
        var scope = authz.scopeFor(AttendancePermissions.DEVICE_UPDATE, clock.today());
        if (deviceId != null) {
            DeviceRow d = store.device(deviceId).orElseThrow(() -> ApiException.notFound("Device", deviceId));
            if (!scope.covers(d.data().legalEntityId(), null, d.data().locationId(), null)) {
                throw forbidden("device-not-in-scope", "The device is outside your data scope");
            }
        }
        Placement p = store.placement(employmentId, date).orElse(null);
        if (!authz.can(AttendancePermissions.DEVICE_UPDATE, new AuthorizationTarget.Employee(employmentId,
                p == null ? null : p.legalEntityId(), p == null ? null : p.orgUnitId(),
                p == null ? null : p.locationId()), date)) {
            throw forbidden("employment-not-in-scope", "Employment " + employmentId + " is outside your data scope");
        }
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
}
