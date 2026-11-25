package vn.yourhrm.attendance.internal.web.punch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.punch.DeviceService;
import vn.yourhrm.attendance.internal.application.punch.DeviceService.DeviceInput;
import vn.yourhrm.attendance.internal.application.punch.DeviceService.DeviceView;
import vn.yourhrm.attendance.internal.application.punch.EnrolmentMappingService;
import vn.yourhrm.attendance.internal.application.punch.EnrolmentMappingService.MappingInput;
import vn.yourhrm.attendance.internal.application.punch.EnrolmentMappingService.MappingView;
import vn.yourhrm.attendance.internal.application.punch.QuarantineService;
import vn.yourhrm.attendance.internal.application.punch.QuarantineService.PunchRef;
import vn.yourhrm.attendance.internal.application.punch.QuarantineService.RematchResult;
import vn.yourhrm.attendance.internal.application.punch.RawPunchQueries;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.PunchRow;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Punch sources and raw punches (BRD 03 §4.3: BR-ATT-020/022/025/027): the device registry, enrolment mappings, the
 * quarantine and the audited read of an employment's raw punches. Punch files are uploaded through Core's import
 * engine ({@code /api/v1/core/imports}, types {@code ATT_PUNCH_EXCEL}, {@code ATT_PUNCH_ZKTECO},
 * {@code ATT_PUNCH_HIKVISION}, {@code ATT_ENROLMENT_MAPPING}). There is no endpoint that changes or deletes a punch's
 * evidence.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class PunchController {

    private final DeviceService devices;
    private final EnrolmentMappingService mappings;
    private final QuarantineService quarantine;
    private final RawPunchQueries punches;

    PunchController(DeviceService devices, EnrolmentMappingService mappings, QuarantineService quarantine,
            RawPunchQueries punches) {
        this.devices = devices;
        this.mappings = mappings;
        this.quarantine = quarantine;
        this.punches = punches;
    }

    record DeviceRequest(String code, String name, String terminalCode, String vendor, String model, String serialNo,
            UUID legalEntityId, UUID locationId, String timeZone, String fileLayoutCode, String status,
            Integer version) {

        DeviceInput input() {
            return new DeviceInput(code, name, terminalCode, vendor, model, serialNo, legalEntityId, locationId,
                    timeZone, fileLayoutCode, status);
        }
    }

    record MappingRequest(UUID deviceId, @NotNull String enrolId, @NotNull UUID employmentId,
            @NotNull LocalDate validFrom, LocalDate validTo) {
    }

    record EndMappingRequest(@NotNull Integer version, LocalDate validTo) {
    }

    record RematchRequest(@NotNull LocalDate from, @NotNull LocalDate to, UUID deviceId, String enrolId) {
    }

    record IgnoreRequest(@NotEmpty List<PunchRef> punches, @NotNull String reason) {
    }

    // ---------------------------------------------------------------------------------------------------- devices

    @GetMapping("/devices")
    @RequiresPermission(AttendancePermissions.DEVICE_VIEW)
    public List<DeviceView> devices(@RequestParam(required = false) String status) {
        return devices.list(status);
    }

    @GetMapping("/devices/{id}")
    @RequiresPermission(AttendancePermissions.DEVICE_VIEW)
    public DeviceView device(@PathVariable UUID id) {
        return devices.get(id);
    }

    @PostMapping("/devices")
    @RequiresPermission(AttendancePermissions.DEVICE_UPDATE)
    public ResponseEntity<DeviceView> createDevice(@RequestBody DeviceRequest req) {
        return ResponseEntity.status(201).body(devices.create(req.input()));
    }

    @PutMapping("/devices/{id}")
    @RequiresPermission(AttendancePermissions.DEVICE_UPDATE)
    public DeviceView updateDevice(@PathVariable UUID id, @RequestBody DeviceRequest req) {
        return devices.update(id, req.version() == null ? -1 : req.version(), req.input());
    }

    // ------------------------------------------------------------------------------------------ enrolment mapping

    @GetMapping("/enrolment-mappings")
    @RequiresPermission(AttendancePermissions.DEVICE_VIEW)
    public List<MappingView> mappings(@RequestParam(required = false) UUID deviceId,
            @RequestParam(defaultValue = "false") boolean tenantWideOnly,
            @RequestParam(required = false) String enrolId, @RequestParam(required = false) UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate on,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return mappings.list(deviceId, tenantWideOnly, enrolId, employmentId, on, limit, offset);
    }

    @PostMapping("/enrolment-mappings")
    @RequiresPermission(AttendancePermissions.DEVICE_UPDATE)
    public ResponseEntity<MappingView> createMapping(@Valid @RequestBody MappingRequest req) {
        return ResponseEntity.status(201).body(mappings.create(new MappingInput(req.deviceId(), req.enrolId(),
                req.employmentId(), req.validFrom(), req.validTo())));
    }

    /** Sets the exclusive end date of a mapping version (null = open again). */
    @PostMapping("/enrolment-mappings/{id}/end")
    @RequiresPermission(AttendancePermissions.DEVICE_UPDATE)
    public MappingView endMapping(@PathVariable UUID id, @Valid @RequestBody EndMappingRequest req) {
        return mappings.end(id, req.version(), req.validTo());
    }

    // ------------------------------------------------------------------------------------------------ quarantine

    @GetMapping("/punches/quarantine")
    @RequiresPermission(AttendancePermissions.PUNCH_VIEW)
    public List<PunchRow> quarantine(@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String terminalCode, @RequestParam(required = false) String enrolId,
            @RequestParam(defaultValue = "200") int limit, @RequestParam(defaultValue = "0") int offset) {
        return quarantine.list(from, to, status, deviceId, terminalCode, enrolId, limit, offset);
    }

    /** Unresolved punches per status (close checklist input). */
    @GetMapping("/punches/quarantine/counts")
    @RequiresPermission(AttendancePermissions.PUNCH_VIEW)
    public Map<String, Long> quarantineCounts(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return quarantine.counts(legalEntityId, from, to);
    }

    @PostMapping("/punches/quarantine/rematch")
    @RequiresPermission(AttendancePermissions.PUNCH_RESOLVE)
    public RematchResult rematch(@Valid @RequestBody RematchRequest req) {
        return quarantine.rematch(req.from(), req.to(), req.deviceId(), req.enrolId());
    }

    @PostMapping("/punches/quarantine/ignore")
    @RequiresPermission(AttendancePermissions.PUNCH_RESOLVE)
    public Map<String, Integer> ignore(@Valid @RequestBody IgnoreRequest req) {
        return Map.of("ignored", quarantine.ignore(req.punches(), req.reason()));
    }

    // -------------------------------------------------------------------------------------------- raw punches

    @GetMapping("/employments/{employmentId}/punches")
    @RequiresPermission(AttendancePermissions.PUNCH_VIEW)
    public List<PunchRow> punches(@PathVariable UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return punches.ofEmployment(employmentId, from, to);
    }
}
