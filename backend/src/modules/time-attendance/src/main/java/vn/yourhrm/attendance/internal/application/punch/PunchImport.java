package vn.yourhrm.attendance.internal.application.punch;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.domain.punch.PunchMatching;
import vn.yourhrm.attendance.internal.domain.punch.PunchMatching.Decision;
import vn.yourhrm.attendance.internal.domain.punch.PunchParsing;
import vn.yourhrm.attendance.internal.domain.punch.PunchParsing.Normalised;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.DeviceRow;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.NewPunch;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.Placement;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Base of the punch-file import types on Core's import engine (BR-ATT-020/022/025, US-ATT-016/017/021): the standard
 * Excel template and the device-export layouts. Each line becomes one append-only {@code att.raw_punch} row:
 * <ol>
 *   <li>the terminal resolves the registered device (its legal entity, location and clock zone); a file without a
 *       terminal column uses the only active device registered with the layout;</li>
 *   <li>the device's wall-clock time is normalised to UTC in the device's zone, and the dedup key
 *       {@code sha256(terminal|enrol id|UTC)} makes a re-upload create no duplicate (NFR-ATT-001);</li>
 *   <li>the enrol id is matched through the enrolment mapping valid on the punch's local date; unknown or expired
 *       mappings and unregistered terminals are stored QUARANTINED for the admin to resolve (BR-ATT-022). Standard
 *       template rows identified by employee number are matched directly.</li>
 * </ol>
 * Rows that cannot be read are errors and skipped (partial commit); duplicates in the file or of an earlier import,
 * quarantine, days outside the employment and days of a closed / locked attendance period are warnings. The
 * device-export layouts refuse photo and biometric columns (NFR-ATT-008). Daily processing (BRD §4.4) reads the stored
 * punches; there is no undo, since raw punches are never deleted (BR-ATT-027).
 */
abstract class PunchImport implements ImportType {

    static final String TEMPLATE_VERSION = "1";
    /** Terminal code of standard-template rows without a device (employee-number rows). */
    static final String NO_TERMINAL = "EXCEL";
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Duration FUTURE_TOLERANCE = Duration.ofHours(24);
    static final List<String> REFUSED_COLUMNS = List.of("photo", "picture", "biometric_template",
            "fingerprint_template", "face_template");

    /** One line of the file as read by a layout; {@code local} is the device's wall-clock time. */
    record Line(String terminal, String enrolId, String employeeNo, LocalDateTime local, String direction,
            String verifyMethod) {
    }

    protected final PunchImportSupport s;
    protected final PunchStore store;
    protected final AuthorizationService authz;

    protected PunchImport(PunchImportSupport support, PunchStore store, AuthorizationService authz) {
        this.s = support;
        this.store = store;
        this.authz = authz;
    }

    /** STANDARD, ZKTECO, HIKVISION: matched against {@code att.device.file_layout_code}. */
    protected abstract String layoutCode();

    /** Reads a line or adds its errors to {@code out} and returns null. */
    protected abstract Line read(ImportRow row, List<RowMessage> out);

    /**
     * Partial commit (unreadable rows are skipped). The nominal business key is Core's optional {@code external_ref}:
     * a punch's identity is the dedup key computed here from the normalised time, so a duplicate line of the file is
     * a warning (and stored once), not a Core duplicate-key error.
     */
    protected Definition definition(String code, String nameVi, String nameEn, List<Column> columns) {
        return new Definition(code, "att", nameVi, nameEn, TEMPLATE_VERSION, AttendancePermissions.PUNCH_IMPORT,
                columns, List.of("external_ref"), true, false, List.of(), List.of());
    }

    // ----------------------------------------------------------------------------------------------- ImportType

    @Override
    public List<RowMessage> validate(ImportContext ctx, ImportRow row) {
        Map<String, Object> st = s.state(ctx, row, "V");
        List<RowMessage> out = new ArrayList<>();
        Line line = precheck(row, out);
        if (line == null) {
            return out;
        }
        out.addAll(s.dryRun(() -> write(ctx, row, line, st, true).warnings()));
        return out;
    }

    @Override
    public CommitResult commit(ImportContext ctx, ImportRow row) {
        Map<String, Object> st = s.state(ctx, row, "C");
        List<RowMessage> out = new ArrayList<>();
        Line line = precheck(row, out);
        if (line == null) {
            throw ApiException.unprocessable("invalid-row", out.getFirst().messageEn());
        }
        return s.write(() -> write(ctx, row, line, st, false).result());
    }

    // --------------------------------------------------------------------------------------------- write path

    record Written(CommitResult result, List<RowMessage> warnings) {
    }

    private Line precheck(ImportRow row, List<RowMessage> out) {
        for (String k : REFUSED_COLUMNS) {
            if (row.values().get(k) != null) {
                out.add(RowMessage.error(k, "biometric-data-refused",
                        "Không nhận ảnh hoặc dữ liệu sinh trắc học trong dữ liệu chấm công; xóa cột này khỏi tệp",
                        "Photos and biometric data are not accepted in punch data; remove this column from the file"));
                return null;
            }
        }
        Line line = read(row, out);
        if (line == null || PunchImportSupport.hasError(out)) {
            return null;
        }
        if (line.enrolId() == null && line.employeeNo() == null) {
            out.add(RowMessage.error("enrol_id", "enrol-id-required", "Thiếu mã chấm công hoặc mã nhân viên",
                    "The enrol id or the employee number is required"));
            return null;
        }
        return line;
    }

    @SuppressWarnings("unchecked")
    private Written write(ImportContext ctx, ImportRow row, Line line, Map<String, Object> st, boolean dryRun) {
        List<RowMessage> warnings = new ArrayList<>();
        ScopePredicate scope = (ScopePredicate) st.computeIfAbsent("$scope",
                k -> authz.scopeFor(AttendancePermissions.PUNCH_IMPORT, s.clock().today()));

        DeviceRow device = device(ctx, line, scope);
        if (device != null && !"ACTIVE".equals(device.data().status())) {
            warnings.add(RowMessage.warning("terminal", "device-inactive", "Máy chấm công " + device.data().code()
                    + " đang ở trạng thái " + device.data().status(), "Device " + device.data().code() + " is "
                    + device.data().status()));
        }
        ZoneId zone = device == null ? DEFAULT_ZONE
                : Optional.ofNullable(PunchParsing.zone(device.data().timeZone())).orElse(DEFAULT_ZONE);
        boolean byEmployeeNo = line.enrolId() == null;
        String terminal = device != null ? device.data().terminalCode()
                : line.terminal() != null ? line.terminal() : NO_TERMINAL;
        String enrolId = byEmployeeNo ? line.employeeNo().strip() : line.enrolId();
        Normalised n = PunchParsing.normalise(terminal, enrolId, line.local(), zone);
        if (n.punchAt().isAfter(Instant.now().plus(FUTURE_TOLERANCE))) {
            throw ApiException.unprocessable("punch-in-future", "The punch time lies in the future");
        }

        // match
        UUID employmentId;
        UUID mappingId = null;
        String status;
        if (byEmployeeNo) {
            employmentId = s.employmentByNumber(ctx, st, line.employeeNo());
            if (employmentId == null) {
                throw ApiException.unprocessable("employee-not-found", "Employee " + line.employeeNo().strip()
                        + " is not an employee of the job's legal entity in your scope on the job's as-of date");
            }
            status = PunchMatching.VALID;
        } else {
            Decision d = PunchMatching.decide(device == null ? null : device.id(), enrolId,
                    store.spansOfEnrol(enrolId), n.workDateHint());
            employmentId = d.employmentId();
            mappingId = d.mappingId();
            status = d.status();
            if (!d.valid()) {
                warnings.add(quarantined(status));
            }
        }
        Placement placement = employmentId == null ? null : store.placement(employmentId, n.workDateHint()).orElse(null);
        if (byEmployeeNo && !authz.can(AttendancePermissions.PUNCH_IMPORT, new AuthorizationTarget.Employee(
                employmentId, placement == null ? ctx.legalEntityId() : placement.legalEntityId(),
                placement == null ? null : placement.orgUnitId(), placement == null ? null : placement.locationId()),
                n.workDateHint())) {
            throw ApiException.unprocessable("employment-not-in-scope",
                    "Employee " + line.employeeNo().strip() + " is outside your data scope");
        }
        if (employmentId != null) {
            store.employment(employmentId).filter(e -> !e.employedOn(n.workDateHint())).ifPresent(e -> warnings.add(
                    RowMessage.warning("punch_date", "not-employed-on-date",
                            "Ngày " + n.workDateHint() + " nằm ngoài thời gian làm việc của nhân viên",
                            "The employee is not employed on " + n.workDateHint())));
        }
        UUID le = placement != null ? placement.legalEntityId() : device != null ? device.data().legalEntityId() : null;
        if (le != null) {
            store.frozenPeriodStatus(le, n.workDateHint()).ifPresent(p -> warnings.add(RowMessage.warning(
                    "punch_date", "period-closed", "Kỳ chấm công chứa ngày " + n.workDateHint() + " đã " + p
                            + "; lượt chấm được lưu và xử lý như điều chỉnh hồi tố",
                    "The attendance period of " + n.workDateHint() + " is " + p
                            + "; the punch is stored and handled as a retro change")));
        }

        // dedup: in the file, then against earlier imports
        Set<String> seen = (Set<String>) st.computeIfAbsent("$keys", k -> new HashSet<String>());
        if (!seen.add(n.dedupKey())) {
            warnings.add(RowMessage.warning(null, "duplicate-in-file", "Trùng với một dòng trước trong tệp; bỏ qua",
                    "Duplicate of an earlier row of the file; skipped"));
        }
        Optional<UUID> existing = store.punchByKey(n.dedupKey(), n.workDateHint()).map(PunchStore.PunchRow::id);
        if (existing.isPresent()) {
            if (dryRun) {
                warnings.add(RowMessage.warning(null, "already-imported", "Lượt chấm đã có từ lần import trước; bỏ qua",
                        "The punch was imported before; skipped"));
            }
            return new Written(CommitResult.updated("att.raw_punch", existing.get()), warnings);
        }
        UUID id = store.insertPunch(new NewPunch(n, device == null ? null : device.id(), terminal, enrolId,
                line.direction(), line.verifyMethod(), ctx.jobId(), row.rowNo(), employmentId, placement, mappingId,
                status)).orElseGet(() -> store.punchByKey(n.dedupKey(), n.workDateHint()).orElseThrow().id());
        if (!dryRun && device != null && ((Set<UUID>) st.computeIfAbsent("$devices", k -> new HashSet<UUID>()))
                .add(device.id())) {
            store.touchLastImport(device.id());
        }
        return new Written(CommitResult.created("att.raw_punch", id), warnings);
    }

    /** The registered device of the line, null for an admissible unknown terminal or an employee-number row. */
    @SuppressWarnings("unchecked")
    private DeviceRow device(ImportContext ctx, Line line, ScopePredicate scope) {
        DeviceRow device;
        if (line.terminal() != null) {
            device = store.deviceByTerminalOrCode(line.terminal()).orElse(null);
            if (device == null) {
                if (line.enrolId() == null) {
                    throw ApiException.unprocessable("unknown-device", "Device " + line.terminal()
                            + " is not registered");
                }
                if (!scope.tenantWide()) {
                    throw ApiException.unprocessable("unknown-device", "Terminal " + line.terminal()
                            + " is not registered; register the device first (only a tenant-wide importer can"
                            + " store punches of unregistered terminals in quarantine)");
                }
                return null;
            }
        } else if (line.enrolId() == null) {
            return null;
        } else {
            List<DeviceRow> candidates = store.activeDevicesOfLayout(layoutCode(), ctx.legalEntityId());
            if (candidates.size() != 1) {
                throw ApiException.unprocessable(candidates.isEmpty() ? "device-required" : "device-ambiguous",
                        "The row has no terminal and " + (candidates.isEmpty() ? "no" : candidates.size())
                                + " active devices use layout " + layoutCode()
                                + (ctx.legalEntityId() == null ? "" : " in the job's legal entity")
                                + "; add the terminal column");
            }
            device = candidates.getFirst();
        }
        if (ctx.legalEntityId() != null && !ctx.legalEntityId().equals(device.data().legalEntityId())) {
            throw ApiException.unprocessable("device-of-other-legal-entity", "Device " + device.data().code()
                    + " belongs to another legal entity than the import job's");
        }
        if (!scope.covers(device.data().legalEntityId(), null, device.data().locationId(), null)) {
            throw ApiException.unprocessable("device-not-in-scope", "Device " + device.data().code()
                    + " is outside your data scope");
        }
        return device;
    }

    private static RowMessage quarantined(String status) {
        return switch (status) {
            case PunchMatching.UNKNOWN_DEVICE -> RowMessage.warning("terminal", "quarantined-unknown-device",
                    "Máy chấm công chưa đăng ký: lượt chấm được đưa vào danh sách cách ly",
                    "Unregistered terminal: the punch goes to quarantine");
            case PunchMatching.EXPIRED -> RowMessage.warning("enrol_id", "quarantined-expired",
                    "Ánh xạ mã chấm công đã hết hiệu lực vào ngày chấm: lượt chấm được đưa vào danh sách cách ly",
                    "The enrolment mapping is not valid on the punch date: the punch goes to quarantine");
            default -> RowMessage.warning("enrol_id", "quarantined-unmapped",
                    "Mã chấm công chưa được ánh xạ: lượt chấm được đưa vào danh sách cách ly",
                    "Unmapped enrol id: the punch goes to quarantine");
        };
    }

    // ------------------------------------------------------------------------------------------- column helpers

    static Column text(String key, String vi, String en, boolean required, String hintVi, String hintEn) {
        return new Column(key, vi, en, ColumnType.TEXT, required, null, false, hintVi, hintEn);
    }

    /** A column that must stay empty (photos, biometric templates): staged encrypted, never in the preview. */
    static Column refused(String key, String vi, String en) {
        return new Column(key, vi, en, ColumnType.TEXT, false, null, true,
                "Không dùng: ảnh / dữ liệu sinh trắc học bị từ chối", "Do not use: photos / biometric data are refused");
    }

    static List<Column> refusedColumns() {
        return List.of(refused("photo", "Ảnh", "Photo"), refused("picture", "Hình ảnh", "Picture"),
                refused("biometric_template", "Mẫu sinh trắc học", "Biometric template"),
                refused("fingerprint_template", "Mẫu vân tay", "Fingerprint template"),
                refused("face_template", "Mẫu khuôn mặt", "Face template"));
    }

    /** Direction / verify method of a line, or an error. */
    static String direction(ImportRow row, String key, List<RowMessage> out) {
        String raw = row.text(key);
        String d = PunchParsing.direction(raw);
        if (d == null) {
            out.add(RowMessage.warning(key, "unknown-direction", "Không nhận ra hướng vào/ra '" + raw
                    + "'; ghi nhận là không xác định", "Direction '" + raw + "' not recognised; stored as UNKNOWN"));
            return "UNKNOWN";
        }
        return d;
    }

    static String trimmed(ImportRow row, String key) {
        String v = row.text(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    static LocalDateTime dateTime(ImportRow row, String key, List<RowMessage> out) {
        try {
            return PunchParsing.dateTime(row.text(key));
        } catch (RuntimeException e) {
            out.add(RowMessage.error(key, "invalid-date-time", "Ngày giờ không hợp lệ: '" + row.text(key)
                    + "' (dd/MM/yyyy HH:mm[:ss] hoặc yyyy-MM-dd HH:mm[:ss])", "Invalid date and time '"
                    + row.text(key) + "' (dd/MM/yyyy HH:mm[:ss] or yyyy-MM-dd HH:mm[:ss])"));
            return null;
        }
    }
}
