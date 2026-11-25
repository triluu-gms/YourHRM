package vn.yourhrm.attendance.internal.application.punch;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.domain.punch.PunchParsing;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.DeviceData;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.DeviceRow;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The device registry (BR-ATT-020/025, OD-9 / R2-01): in Phase 1 a device is the terminal code that appears in the
 * customer's device-export files, with its legal entity, location and clock zone. There is no live connector
 * ({@code connector_type = FILE_IMPORT}). Data-scoped by the device's legal entity / location.
 */
@Service
public class DeviceService {

    public static final Set<String> LAYOUTS = Set.of("STANDARD", "ZKTECO", "HIKVISION");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "RETIRED");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,39}$");

    public record DeviceInput(String code, String name, String terminalCode, String vendor, String model,
            String serialNo, UUID legalEntityId, UUID locationId, String timeZone, String fileLayoutCode,
            String status) {
    }

    public record DeviceView(UUID id, String code, String name, String terminalCode, String vendor, String model,
            String serialNo, UUID legalEntityId, UUID locationId, String timeZone, String connectorType,
            String fileLayoutCode, Instant lastImportAt, String status, int version) {

        static DeviceView of(DeviceRow r) {
            DeviceData d = r.data();
            return new DeviceView(r.id(), d.code(), d.name(), d.terminalCode(), d.vendor(), d.model(), d.serialNo(),
                    d.legalEntityId(), d.locationId(), d.timeZone(), "FILE_IMPORT", d.fileLayoutCode(),
                    r.lastImportAt(), d.status(), r.version());
        }
    }

    private final PunchStore store;
    private final AuthorizationService authz;
    private final BusinessClock clock;

    public DeviceService(PunchStore store, AuthorizationService authz, BusinessClock clock) {
        this.store = store;
        this.authz = authz;
        this.clock = clock;
    }

    @AttTransactional(readOnly = true)
    public List<DeviceView> list(String status) {
        return store.devices(authz.scopeFor(AttendancePermissions.DEVICE_VIEW, clock.today()), upper(status)).stream()
                .map(DeviceView::of).toList();
    }

    @AttTransactional(readOnly = true)
    public DeviceView get(UUID id) {
        return DeviceView.of(visible(id, AttendancePermissions.DEVICE_VIEW));
    }

    @AttTransactional
    public DeviceView create(DeviceInput in) {
        DeviceData d = validate(in, "ACTIVE");
        requireScope(d);
        return DeviceView.of(store.device(store.insertDevice(d)).orElseThrow());
    }

    @AttTransactional
    public DeviceView update(UUID id, int version, DeviceInput in) {
        DeviceRow current = visible(id, AttendancePermissions.DEVICE_UPDATE);
        DeviceData d = validate(in, current.data().status());
        requireScope(d);
        if (!store.updateDevice(id, version, d)) {
            throw ApiException.conflict("stale-version", "The device was changed by someone else; reload it");
        }
        return DeviceView.of(store.device(id).orElseThrow());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private DeviceRow visible(UUID id, String permission) {
        DeviceRow r = store.device(id).orElseThrow(() -> ApiException.notFound("Device", id));
        if (!authz.scopeFor(permission, clock.today()).covers(r.data().legalEntityId(), null, r.data().locationId(),
                null)) {
            throw ApiException.notFound("Device", id);
        }
        return r;
    }

    private void requireScope(DeviceData d) {
        if (!authz.scopeFor(AttendancePermissions.DEVICE_UPDATE, clock.today()).covers(d.legalEntityId(), null,
                d.locationId(), null)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "device-not-in-scope",
                    "The device's legal entity / location is outside your data scope");
        }
    }

    private DeviceData validate(DeviceInput in, String currentStatus) {
        String code = upper(in.code());
        if (code == null || !CODE.matcher(code).matches()) {
            throw invalid("invalid-device-code", "The device code is 1-40 upper-case letters, digits, '_' or '-'");
        }
        if (in.name() == null || in.name().isBlank()) {
            throw invalid("name-required", "The device name is required");
        }
        String terminal = text(in.terminalCode());
        if (terminal == null || terminal.length() > 64) {
            throw invalid("terminal-code-required", "The terminal code as written in the export file is required"
                    + " (at most 64 characters)");
        }
        if (in.legalEntityId() == null) {
            throw invalid("legal-entity-required", "The device's legal entity is required");
        }
        String tz = text(in.timeZone());
        if (tz == null && in.locationId() != null) {
            tz = store.locationTimeZone(in.locationId()).orElse(null);
        }
        tz = tz == null ? PunchImport.DEFAULT_ZONE.getId() : tz;
        if (PunchParsing.zone(tz) == null) {
            throw invalid("invalid-time-zone", "The time zone must be an IANA zone id such as Asia/Ho_Chi_Minh");
        }
        String layout = upper(in.fileLayoutCode());
        if (layout != null && !LAYOUTS.contains(layout)) {
            throw invalid("invalid-file-layout", "The file layout is one of " + LAYOUTS);
        }
        String status = in.status() == null ? currentStatus : upper(in.status());
        if (!STATUSES.contains(status)) {
            throw invalid("invalid-status", "The status is one of " + STATUSES);
        }
        return new DeviceData(code, in.name().strip(), terminal, upper(in.vendor()), text(in.model()),
                text(in.serialNo()), in.legalEntityId(), in.locationId(), tz, layout, status);
    }

    static String text(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }
}
