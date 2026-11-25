package vn.yourhrm.attendance.internal.infra.punch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.domain.punch.PunchMatching.Span;
import vn.yourhrm.attendance.internal.domain.punch.PunchParsing.Normalised;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.Columns;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.device}, {@code att.enrolment_mapping} and {@code att.raw_punch} (BR-ATT-020/022/025/027).
 * Raw punches are only ever INSERTed ({@code ON CONFLICT DO NOTHING} on the dedup key) and, while unresolved, have
 * their matching columns set once ({@code att.tg_raw_punch_guard}); there is no other UPDATE and no DELETE path.
 * Every method runs inside the caller's T&amp;A transaction.
 */
@Component
public class PunchStore {

    public record DeviceData(String code, String name, String terminalCode, String vendor, String model,
            String serialNo, UUID legalEntityId, UUID locationId, String timeZone, String fileLayoutCode,
            String status) {
    }

    public record DeviceRow(UUID id, DeviceData data, Instant lastImportAt, int version) {
    }

    public record MappingRow(Span span, String source, int version) {
    }

    /** Where a matched punch belongs on its date (M9 data-scope columns). */
    public record Placement(UUID legalEntityId, UUID orgUnitId, UUID locationId) {
    }

    /** Employment dates as projected from People (termination inclusive). */
    public record EmploymentDates(UUID employmentId, UUID legalEntityId, LocalDate hireDate, LocalDate terminationDate,
            String status) {

        public boolean employedOn(LocalDate d) {
            return !"CANCELLED".equals(status) && !d.isBefore(hireDate)
                    && (terminationDate == null || !d.isAfter(terminationDate));
        }
    }

    /** A raw punch as listed (no P3 column exists in P1; punch time is P2 presence data). */
    public record PunchRow(UUID id, LocalDate workDateHint, Instant punchAt, LocalDateTime localTs, String tzName,
            String source, UUID deviceId, String terminalCode, String enrolId, String direction, String verifyMethod,
            UUID importJobId, Integer importRowNo, Instant receivedAt, UUID employmentId, UUID legalEntityId,
            UUID orgUnitId, UUID enrolmentMappingId, String validationStatus, Instant matchedAt) {
    }

    /** One punch to insert. */
    public record NewPunch(Normalised n, UUID deviceId, String terminalCode, String enrolId, String direction,
            String verifyMethod, UUID importJobId, int importRowNo, UUID employmentId, Placement placement,
            UUID mappingId, String status) {
    }

    private final AttDb db;

    public PunchStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    // ---------------------------------------------------------------------------------------------------- devices

    private static final String DEVICE_COLUMNS = """
            select d.id, d.code, d.name, d.terminal_code, d.vendor, d.model, d.serial_no, d.legal_entity_id,
                   d.location_id, d.time_zone, d.file_layout_code, d.status, d.last_import_at, d.version
              from att.device d
            """;

    private static DeviceRow device(ResultSet rs, int n) throws SQLException {
        Timestamp last = rs.getTimestamp(13);
        return new DeviceRow(rs.getObject(1, UUID.class), new DeviceData(rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class),
                rs.getObject(9, UUID.class), rs.getString(10), rs.getString(11), rs.getString(12)),
                last == null ? null : last.toInstant(), rs.getInt(14));
    }

    public List<DeviceRow> devices(ScopePredicate scope, String status) {
        SqlFragment f = scope.toSql(new Columns("d.legal_entity_id", null, "d.location_id", null), "sc");
        return db.jdbc().sql(DEVICE_COLUMNS + " where d.tenant_id = :t and (cast(:st as text) is null or d.status = :st)"
                        + " and " + f.sql() + " order by d.code")
                .param("t", tenant()).param("st", status).params(f.params()).query(PunchStore::device).list();
    }

    public Optional<DeviceRow> device(UUID id) {
        return db.jdbc().sql(DEVICE_COLUMNS + " where d.tenant_id = :t and d.id = :id")
                .param("t", tenant()).param("id", id).query(PunchStore::device).optional();
    }

    /**
     * The device whose terminal code (as in the file) or device code is {@code text}: an exact terminal match first,
     * then a case-insensitive one, then the device code.
     */
    public Optional<DeviceRow> deviceByTerminalOrCode(String text) {
        List<DeviceRow> rows = db.jdbc().sql(DEVICE_COLUMNS + """
                         where d.tenant_id = :t and (upper(d.terminal_code) = upper(:x) or d.code = upper(:x))
                         order by (d.terminal_code = :x) desc, (upper(d.terminal_code) = upper(:x)) desc, d.code""")
                .param("t", tenant()).param("x", text.strip()).query(PunchStore::device).list();
        return rows.stream().findFirst();
    }

    /** Active devices with the given file layout, optionally of one legal entity. */
    public List<DeviceRow> activeDevicesOfLayout(String layoutCode, UUID legalEntityId) {
        return db.jdbc().sql(DEVICE_COLUMNS + """
                         where d.tenant_id = :t and d.status = 'ACTIVE' and d.file_layout_code = :l
                           and (cast(:le as uuid) is null or d.legal_entity_id = :le) order by d.code""")
                .param("t", tenant()).param("l", layoutCode).param("le", legalEntityId)
                .query(PunchStore::device).list();
    }

    public UUID insertDevice(DeviceData d) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.device (tenant_id, id, code, name, terminal_code, vendor, model, serial_no,
                                   legal_entity_id, location_id, time_zone, connector_type, file_layout_code, status,
                                   created_by, updated_by)
                            values (:t, :id, :code, :name, :term, :vendor, :model, :serial, :le, :loc, :tz,
                                   'FILE_IMPORT', :layout, :st, :by, :by)""")
                    .param("t", tenant()).param("id", id).param("code", d.code()).param("name", d.name())
                    .param("term", d.terminalCode()).param("vendor", d.vendor()).param("model", d.model())
                    .param("serial", d.serialNo()).param("le", d.legalEntityId()).param("loc", d.locationId())
                    .param("tz", d.timeZone()).param("layout", d.fileLayoutCode()).param("st", d.status())
                    .param("by", actor()).update();
        } catch (DataIntegrityViolationException e) {
            throw duplicateDevice(e);
        }
        return id;
    }

    public boolean updateDevice(UUID id, int version, DeviceData d) {
        try {
            return db.jdbc().sql("""
                            update att.device set code = :code, name = :name, terminal_code = :term, vendor = :vendor,
                                   model = :model, serial_no = :serial, legal_entity_id = :le, location_id = :loc,
                                   time_zone = :tz, file_layout_code = :layout, status = :st, updated_by = :by,
                                   version = version + 1
                             where tenant_id = :t and id = :id and version = :v""")
                    .param("t", tenant()).param("id", id).param("v", version).param("code", d.code())
                    .param("name", d.name()).param("term", d.terminalCode()).param("vendor", d.vendor())
                    .param("model", d.model()).param("serial", d.serialNo()).param("le", d.legalEntityId())
                    .param("loc", d.locationId()).param("tz", d.timeZone()).param("layout", d.fileLayoutCode())
                    .param("st", d.status()).param("by", actor()).update() == 1;
        } catch (DataIntegrityViolationException e) {
            throw duplicateDevice(e);
        }
    }

    public void touchLastImport(UUID deviceId) {
        db.jdbc().sql("""
                        update att.device set last_import_at = now()
                         where tenant_id = :t and id = :id and (last_import_at is null or last_import_at < now())""")
                .param("t", tenant()).param("id", deviceId).update();
    }

    private static RuntimeException duplicateDevice(DataIntegrityViolationException e) {
        String m = String.valueOf(e.getMostSpecificCause().getMessage());
        if (m.contains("device_code_uq")) {
            return ApiException.conflict("device-code-taken", "Another device already has this code");
        }
        if (m.contains("device_terminal_uq")) {
            return ApiException.conflict("terminal-code-taken", "Another device already has this terminal code");
        }
        throw e;
    }

    /** IANA zone of a location as projected from Core (the version in effect today, else the latest), if known. */
    public Optional<String> locationTimeZone(UUID locationId) {
        return db.jdbc().sql("""
                        select time_zone from att.ext_core_location where tenant_id = :t and location_id = :id
                         order by (valid_during @> current_date) desc, valid_from desc limit 1""")
                .param("t", tenant()).param("id", locationId).query(String.class).optional();
    }

    // ------------------------------------------------------------------------------------------- enrolment mapping

    private static final String MAPPING_COLUMNS = """
            select m.id, m.device_id, m.enrol_id, m.employment_id, m.valid_from, m.valid_to, m.source, m.version
              from att.enrolment_mapping m
            """;

    private static MappingRow mapping(ResultSet rs, int n) throws SQLException {
        return new MappingRow(new Span(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class)),
                rs.getString(7), rs.getInt(8));
    }

    /** Every mapping version of {@code enrolId} (any device). */
    public List<Span> spansOfEnrol(String enrolId) {
        return db.jdbc().sql(MAPPING_COLUMNS + " where m.tenant_id = :t and m.enrol_id = :e order by m.valid_from")
                .param("t", tenant()).param("e", enrolId).query(PunchStore::mapping).list().stream()
                .map(MappingRow::span).toList();
    }

    public Optional<MappingRow> mapping(UUID id) {
        return db.jdbc().sql(MAPPING_COLUMNS + " where m.tenant_id = :t and m.id = :id")
                .param("t", tenant()).param("id", id).query(PunchStore::mapping).optional();
    }

    /**
     * Mappings filtered by device ({@code tenantWideOnly}: device NULL), enrol id, employment and date, in the
     * caller's scope: a device's legal entity / location, or the employment's placement on {@code scopeDate}.
     */
    public List<MappingRow> mappings(UUID deviceId, boolean tenantWideOnly, String enrolId, UUID employmentId,
            LocalDate on, ScopePredicate scope, LocalDate scopeDate, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("coalesce(d.legal_entity_id, x.legal_entity_id)", "x.org_unit_id",
                "coalesce(d.location_id, x.location_id)", "m.employment_id"), "sc");
        return db.jdbc().sql(MAPPING_COLUMNS + """
                          left join att.device d on d.tenant_id = m.tenant_id and d.id = m.device_id
                          left join lateral (select a.legal_entity_id, a.org_unit_id, a.location_id
                                               from att.ext_hr_assignment a
                                              where a.tenant_id = m.tenant_id and a.employment_id = m.employment_id
                                                and a.valid_during @> cast(:sd as date)
                                              order by (a.assignment_type = 'PRIMARY') desc limit 1) x on true
                         where m.tenant_id = :t
                           and (cast(:dev as uuid) is null or m.device_id = :dev)
                           and (not :two or m.device_id is null)
                           and (cast(:e as text) is null or m.enrol_id = :e)
                           and (cast(:emp as uuid) is null or m.employment_id = :emp)
                           and (cast(:on as date) is null or m.valid_during @> cast(:on as date))
                        """ + " and " + f.sql() + " order by m.enrol_id, m.valid_from limit :lim offset :off")
                .param("t", tenant()).param("dev", deviceId).param("two", tenantWideOnly).param("e", enrolId)
                .param("emp", employmentId).param("on", on).param("sd", scopeDate).params(f.params())
                .param("lim", limit).param("off", offset).query(PunchStore::mapping).list();
    }

    public UUID insertMapping(UUID deviceId, String enrolId, UUID employmentId, LocalDate from, LocalDate to,
            String source) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.enrolment_mapping (tenant_id, id, device_id, enrol_id, employment_id,
                                   valid_from, valid_to, source, created_by, updated_by)
                            values (:t, :id, :dev, :e, :emp, :f, :to, :src, :by, :by)""")
                    .param("t", tenant()).param("id", id).param("dev", deviceId).param("e", enrolId)
                    .param("emp", employmentId).param("f", from).param("to", to).param("src", source)
                    .param("by", actor()).update();
        } catch (DataIntegrityViolationException e) {
            throw overlap(e);
        }
        return id;
    }

    /** Sets the exclusive end of a mapping version (end it, or shorten / extend it). */
    public boolean setMappingEnd(UUID id, int version, LocalDate validTo) {
        try {
            return db.jdbc().sql("""
                            update att.enrolment_mapping set valid_to = :to, updated_by = :by, version = version + 1
                             where tenant_id = :t and id = :id and version = :v""")
                    .param("t", tenant()).param("id", id).param("v", version).param("to", validTo)
                    .param("by", actor()).update() == 1;
        } catch (DataIntegrityViolationException e) {
            throw overlap(e);
        }
    }

    private static RuntimeException overlap(DataIntegrityViolationException e) {
        if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("enrolment_mapping_ex")) {
            return ApiException.conflict("enrolment-mapping-overlap", "The enrol id is already mapped on this device"
                    + " scope for part of the period; end that mapping first");
        }
        throw e;
    }

    // --------------------------------------------------------------------------------------- employment context

    public Optional<EmploymentDates> employment(UUID employmentId) {
        return db.jdbc().sql("""
                        select employment_id, legal_entity_id, hire_date, termination_date, status
                          from att.ext_hr_employment where tenant_id = :t and employment_id = :id""")
                .param("t", tenant()).param("id", employmentId)
                .query((rs, n) -> new EmploymentDates(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getString(5)))
                .optional();
    }

    /** The person of the employment as projected from People. */
    public Optional<UUID> personOf(UUID employmentId) {
        return db.jdbc().sql("select person_id from att.ext_hr_employment where tenant_id = :t and employment_id = :id")
                .param("t", tenant()).param("id", employmentId).query(UUID.class).optional();
    }

    /**
     * Legal entity, org unit and location of the employment on {@code date}: the PRIMARY assignment line valid then
     * (else another line), else the employment's legal entity only, else none (not known to T&amp;A).
     */
    public Optional<Placement> placement(UUID employmentId, LocalDate date) {
        Optional<Placement> line = db.jdbc().sql("""
                        select legal_entity_id, org_unit_id, location_id from att.ext_hr_assignment
                         where tenant_id = :t and employment_id = :e and valid_during @> cast(:d as date)
                         order by (assignment_type = 'PRIMARY') desc, valid_from limit 1""")
                .param("t", tenant()).param("e", employmentId).param("d", date)
                .query((rs, n) -> new Placement(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)))
                .optional();
        return line.isPresent() ? line
                : employment(employmentId).map(e -> new Placement(e.legalEntityId(), null, null));
    }

    /** CLOSED / LOCKED when an attendance period of the legal entity covering {@code date} is frozen. */
    public Optional<String> frozenPeriodStatus(UUID legalEntityId, LocalDate date) {
        return db.jdbc().sql("""
                        select status from att.attendance_period
                         where tenant_id = :t and legal_entity_id = :le and :d between period_start and period_end
                           and status in ('CLOSED', 'LOCKED')
                         order by (status = 'LOCKED') desc limit 1""")
                .param("t", tenant()).param("le", legalEntityId).param("d", date).query(String.class).optional();
    }

    // ------------------------------------------------------------------------------------------------- raw punch

    private static final String PUNCH_COLUMNS = """
            select p.id, p.work_date_hint, p.punch_at, p.local_ts, p.tz_name, p.source, p.device_id, p.terminal_code,
                   p.enrol_id, p.direction, p.verify_method, p.import_job_id, p.import_row_no, p.received_at,
                   p.employment_id, p.legal_entity_id, p.org_unit_id, p.enrolment_mapping_id, p.validation_status,
                   p.matched_at
              from att.raw_punch p
            """;

    private static Instant instant(ResultSet rs, int i) throws SQLException {
        OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static PunchRow punch(ResultSet rs, int n) throws SQLException {
        int row = rs.getInt(13);
        return new PunchRow(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), instant(rs, 3),
                rs.getObject(4, LocalDateTime.class), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getObject(12, UUID.class),
                rs.wasNull() ? null : row, instant(rs, 14), rs.getObject(15, UUID.class), rs.getObject(16, UUID.class),
                rs.getObject(17, UUID.class), rs.getObject(18, UUID.class), rs.getString(19), instant(rs, 20));
    }

    /**
     * Inserts the punch unless one with the same dedup key exists; returns the new id, or empty for a duplicate.
     * A date without a monthly partition fails with the database error (the scheduler keeps partitions ahead).
     */
    public Optional<UUID> insertPunch(NewPunch p) {
        UUID t = tenant();
        Placement pl = p.placement();
        return db.jdbc().sql("""
                        insert into att.raw_punch (tenant_id, id, work_date_hint, punch_at, local_ts, tz_name, source,
                               device_id, terminal_code, enrol_id, direction, verify_method, dedup_key, import_job_id,
                               import_row_no, employment_id, legal_entity_id, org_unit_id, enrolment_mapping_id,
                               validation_status, matched_at, matched_by, created_by)
                        values (:t, :id, :hint, :at, :local, :tz, 'IMPORT', :dev, :term, :enrol, :dir, :verify, :key,
                               :job, :row, :emp, :le, :ou, :map, :st,
                               case when :st = 'VALID' then now() end, case when :st = 'VALID' then cast(:by as uuid) end,
                               :by)
                        on conflict (tenant_id, dedup_key, work_date_hint) do nothing
                        returning id""")
                .param("t", t).param("id", Ids.newId()).param("hint", p.n().workDateHint())
                .param("at", OffsetDateTime.ofInstant(p.n().punchAt(), ZoneOffset.UTC))
                .param("local", p.n().localTs()).param("tz", p.n().tzName()).param("dev", p.deviceId())
                .param("term", p.terminalCode()).param("enrol", p.enrolId()).param("dir", p.direction())
                .param("verify", p.verifyMethod()).param("key", p.n().dedupKey()).param("job", p.importJobId())
                .param("row", p.importRowNo()).param("emp", p.employmentId())
                .param("le", pl == null ? null : pl.legalEntityId()).param("ou", pl == null ? null : pl.orgUnitId())
                .param("map", p.mappingId()).param("st", p.status()).param("by", actor())
                .query(UUID.class).optional();
    }

    public Optional<PunchRow> punchByKey(String dedupKey, LocalDate hint) {
        return db.jdbc().sql(PUNCH_COLUMNS + " where p.tenant_id = :t and p.dedup_key = :k and p.work_date_hint = :h")
                .param("t", tenant()).param("k", dedupKey).param("h", hint).query(PunchStore::punch).optional();
    }

    public Optional<PunchRow> punch(UUID id, LocalDate hint) {
        return db.jdbc().sql(PUNCH_COLUMNS + " where p.tenant_id = :t and p.id = :id and p.work_date_hint = :h")
                .param("t", tenant()).param("id", id).param("h", hint).query(PunchStore::punch).optional();
    }

    /** Punches of an employment whose hint lies in {@code [from, to]} (inclusive), in time order. */
    public List<PunchRow> punchesOfEmployment(UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql(PUNCH_COLUMNS + """
                         where p.tenant_id = :t and p.employment_id = :e and p.work_date_hint between :f and :to
                         order by p.punch_at, p.id""")
                .param("t", tenant()).param("e", employmentId).param("f", from).param("to", to)
                .query(PunchStore::punch).list();
    }

    /**
     * Unresolved punches (PENDING / QUARANTINED_*) with hint in {@code [from, to]}, in the caller's scope by the
     * device's legal entity / location (a punch of an unknown terminal is visible only with a tenant-wide scope).
     */
    public List<PunchRow> quarantine(LocalDate from, LocalDate to, String status, UUID deviceId, String terminalCode,
            String enrolId, ScopePredicate scope, int limit, int offset) {
        SqlFragment f = scope.toSql(new Columns("d.legal_entity_id", null, "d.location_id", null), "sc");
        return db.jdbc().sql(PUNCH_COLUMNS + """
                          left join att.device d on d.tenant_id = p.tenant_id and d.id = p.device_id
                         where p.tenant_id = :t and p.work_date_hint between :f and :to
                           and (p.validation_status like 'QUARANTINED%' or p.validation_status = 'PENDING')
                           and (cast(:st as text) is null or p.validation_status = :st)
                           and (cast(:dev as uuid) is null or p.device_id = :dev)
                           and (cast(:term as text) is null or upper(p.terminal_code) = upper(:term))
                           and (cast(:e as text) is null or p.enrol_id = :e)
                        """ + " and " + f.sql() + " order by p.work_date_hint, p.punch_at, p.id limit :lim offset :off")
                .param("t", tenant()).param("f", from).param("to", to).param("st", status).param("dev", deviceId)
                .param("term", terminalCode).param("e", enrolId).params(f.params()).param("lim", limit)
                .param("off", offset).query(PunchStore::punch).list();
    }

    /** Counts of unresolved punches per status in {@code [from, to]} for a legal entity's devices (close checklist). */
    public List<Object[]> quarantineCounts(UUID legalEntityId, LocalDate from, LocalDate to, ScopePredicate scope) {
        SqlFragment f = scope.toSql(new Columns("d.legal_entity_id", null, "d.location_id", null), "sc");
        return db.jdbc().sql("""
                        select p.validation_status, count(*)
                          from att.raw_punch p
                          left join att.device d on d.tenant_id = p.tenant_id and d.id = p.device_id
                         where p.tenant_id = :t and p.work_date_hint between :f and :to
                           and (p.validation_status like 'QUARANTINED%' or p.validation_status = 'PENDING')
                           and (cast(:le as uuid) is null or d.legal_entity_id = :le)
                        """ + " and " + f.sql() + " group by p.validation_status order by 1")
                .param("t", tenant()).param("f", from).param("to", to).param("le", legalEntityId)
                .params(f.params())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).list();
    }

    /**
     * Sets the matching columns of an unresolved punch (the only UPDATE the guard allows); false when it was resolved
     * meanwhile.
     */
    public boolean resolve(UUID id, LocalDate hint, String status, UUID employmentId, Placement placement,
            UUID mappingId) {
        return db.jdbc().sql("""
                        update att.raw_punch
                           set validation_status = :st, employment_id = :emp, legal_entity_id = :le,
                               org_unit_id = :ou, enrolment_mapping_id = :map,
                               matched_at = case when :st in ('VALID', 'IGNORED') then now() end,
                               matched_by = case when :st in ('VALID', 'IGNORED') then cast(:by as uuid) end
                         where tenant_id = :t and id = :id and work_date_hint = :h
                           and validation_status not in ('VALID', 'IGNORED')""")
                .param("t", tenant()).param("id", id).param("h", hint).param("st", status).param("emp", employmentId)
                .param("le", placement == null ? null : placement.legalEntityId())
                .param("ou", placement == null ? null : placement.orgUnitId()).param("map", mappingId)
                .param("by", actor()).update() == 1;
    }
}
