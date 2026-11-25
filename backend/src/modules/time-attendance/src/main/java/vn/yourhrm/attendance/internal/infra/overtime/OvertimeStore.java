package vn.yourhrm.attendance.internal.infra.overtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.ResultRow;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * {@code att.overtime_request}, {@code att.overtime_result}, {@code att.ot_cap_ledger}, the protected-worker
 * projection {@code att.ext_hr_special_status} and the employment facts overtime needs. Runs inside the caller's
 * T&amp;A transaction.
 */
@Component
public class OvertimeStore {

    public record RequestRow(UUID id, UUID employmentId, UUID legalEntityId, UUID orgUnitId, UUID requestGroupId,
            LocalDate workDate, Instant plannedStartAt, Instant plannedEndAt, int plannedMinutes, String reason,
            String source, String compChoice, boolean employeeConsent, Instant consentAt, String consentMethod,
            boolean protectedWorkerFlag, boolean capOverride, String capOverrideReason, UUID capOverrideBy,
            String status, UUID workflowInstanceId, Instant decidedAt, UUID decidedBy, UUID createdBy, int version) {
    }

    public record NewRequest(UUID employmentId, UUID legalEntityId, UUID orgUnitId, UUID requestGroupId,
            LocalDate workDate, Instant plannedStartAt, Instant plannedEndAt, int plannedMinutes, String reason,
            boolean employeeConsent, String consentMethod) {
    }

    public record LedgerRow(UUID id, UUID employmentId, UUID legalEntityId, String periodKind, LocalDate periodStart,
            String regimeCode, int capMin, int openingMin, int approvedMin, int pendingMin, int paidMin,
            int overrideMin, String regimeNoticeRef) {

        /** Minutes the cap check counts: opening + approved + pending (BR-ATT-046). */
        public int usedMin() {
            return openingMin + approvedMin + pendingMin;
        }
    }

    public record ResultView(UUID id, UUID employmentId, LocalDate workDate, String otCategory, int actualMinutes,
            int approvedMinutes, int paidMinutes, boolean precededByDayOt, int calcVersion, UUID legalPackId) {
    }

    /** {@code att.ext_hr_employment} facts. */
    public record EmploymentFacts(UUID employmentId, UUID personId, UUID legalEntityId, LocalDate hireDate,
            LocalDate terminationDate, String status) {

        public boolean employedOn(LocalDate d) {
            return !"CANCELLED".equals(status) && !d.isBefore(hireDate)
                    && (terminationDate == null || !d.isAfter(terminationDate));
        }
    }

    private static final String REQUEST_COLUMNS = """
            select id, employment_id, legal_entity_id, org_unit_id, request_group_id, work_date, planned_start_at,
                   planned_end_at, planned_minutes, reason, source, comp_choice, employee_consent, consent_at,
                   consent_method, protected_worker_flag, cap_override, cap_override_reason, cap_override_by, status,
                   workflow_instance_id, decided_at, decided_by, created_by, version
              from att.overtime_request""";

    private final AttDb db;

    public OvertimeStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    // ============================================================================================== requests

    public UUID insertRequest(NewRequest r) {
        UUID id = db.jdbc().sql("""
                        insert into att.overtime_request (tenant_id, employment_id, legal_entity_id, org_unit_id,
                               request_group_id, work_date, planned_start_at, planned_end_at, planned_minutes, reason,
                               employee_consent, consent_at, consent_method, created_by, updated_by)
                        values (:t, :emp, :le, :ou, :grp, :d, :s, :e, :m, :reason, :consent,
                                case when :consent then now() end, :method, :actor, :actor)
                        returning id""")
                .param("t", tenant()).param("emp", r.employmentId()).param("le", r.legalEntityId())
                .param("ou", r.orgUnitId()).param("grp", r.requestGroupId()).param("d", r.workDate())
                .param("s", ts(r.plannedStartAt())).param("e", ts(r.plannedEndAt())).param("m", r.plannedMinutes())
                .param("reason", r.reason()).param("consent", r.employeeConsent())
                .param("method", r.consentMethod()).param("actor", actor())
                .query(UUID.class).single();
        return id;
    }

    public Optional<RequestRow> request(UUID id) {
        return db.jdbc().sql(REQUEST_COLUMNS + " " + "where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(OvertimeStore::mapRequest).optional();
    }

    public Optional<RequestRow> requestForUpdate(UUID id) {
        return db.jdbc().sql(REQUEST_COLUMNS + " " + "where tenant_id = :t and id = :id for update")
                .param("t", tenant()).param("id", id).query(OvertimeStore::mapRequest).optional();
    }

    public List<RequestRow> requestsOfEmployment(UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql(REQUEST_COLUMNS + " " + """
                         where tenant_id = :t and employment_id = :emp and work_date between :from and :to
                         order by work_date, planned_start_at""")
                .param("t", tenant()).param("emp", employmentId).param("from", from).param("to", to)
                .query(OvertimeStore::mapRequest).list();
    }

    /** PENDING / APPROVED requests of the employment whose planned time overlaps {@code [start, end)}. */
    public List<RequestRow> overlapping(UUID employmentId, Instant start, Instant end, UUID excludeId) {
        return db.jdbc().sql(REQUEST_COLUMNS + " " + """
                         where tenant_id = :t and employment_id = :emp and status in ('PENDING','APPROVED')
                           and id <> :ex and planned_start_at < :e and planned_end_at > :s""")
                .param("t", tenant()).param("emp", employmentId).param("ex", excludeId).param("s", ts(start))
                .param("e", ts(end)).query(OvertimeStore::mapRequest).list();
    }

    /** Requests of one work date in the given statuses. */
    public List<RequestRow> requestsOfDay(UUID employmentId, LocalDate workDate, Set<String> statuses) {
        return db.jdbc().sql(REQUEST_COLUMNS + " " + """
                         where tenant_id = :t and employment_id = :emp and work_date = :d and status in (:st)
                         order by planned_start_at""")
                .param("t", tenant()).param("emp", employmentId).param("d", workDate).param("st", statuses)
                .query(OvertimeStore::mapRequest).list();
    }

    public void recordConsent(UUID id, String method) {
        db.jdbc().sql("""
                        update att.overtime_request set employee_consent = true, consent_at = now(),
                               consent_method = :m, updated_at = now(), updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("m", method).param("actor", actor()).param("t", tenant()).param("id", id).update();
    }

    public void markPending(UUID id, boolean protectedWorker, boolean capOverride, String overrideReason) {
        db.jdbc().sql("""
                        update att.overtime_request set status = 'PENDING', protected_worker_flag = :pw,
                               cap_override = :co, cap_override_reason = :cr,
                               cap_override_by = case when :co then :actor end,
                               updated_at = now(), updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("pw", protectedWorker).param("co", capOverride).param("cr", capOverride ? overrideReason : null)
                .param("actor", actor()).param("t", tenant()).param("id", id).update();
    }

    public void setWorkflowInstance(UUID id, UUID instanceId) {
        db.jdbc().sql("""
                        update att.overtime_request set workflow_instance_id = :wf
                         where tenant_id = :t and id = :id""")
                .param("wf", instanceId).param("t", tenant()).param("id", id).update();
    }

    /** Final status APPROVED / REJECTED / CANCELLED. */
    public void decide(UUID id, String status, UUID decidedBy) {
        db.jdbc().sql("""
                        update att.overtime_request set status = :s, decided_at = now(), decided_by = :by,
                               updated_at = now(), updated_by = coalesce(:actor, :by), version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("s", status).param("by", decidedBy).param("actor", actor()).param("t", tenant())
                .param("id", id).update();
    }

    private static RequestRow mapRequest(ResultSet rs, int n) throws SQLException {
        return new RequestRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, LocalDate.class),
                instant(rs, 7), instant(rs, 8), rs.getInt(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getBoolean(13), instant(rs, 14), rs.getString(15), rs.getBoolean(16), rs.getBoolean(17),
                rs.getString(18), rs.getObject(19, UUID.class), rs.getString(20), rs.getObject(21, UUID.class),
                instant(rs, 22), rs.getObject(23, UUID.class), rs.getObject(24, UUID.class), rs.getInt(25));
    }

    // ================================================================================================ ledger

    private static final String LEDGER_COLUMNS = """
            select id, employment_id, legal_entity_id, period_kind, period_start, regime_code, cap_min, opening_min,
                   approved_min, pending_min, paid_min, override_min, regime_notice_ref
              from att.ot_cap_ledger""";

    /**
     * The ledger row of {@code employment × kind × periodStart}, created when missing and locked FOR UPDATE: cap checks
     * of one employee are serialised (DESIGN §7.4). The cap, regime, pack and notice are refreshed to the given values.
     */
    public LedgerRow lockLedger(UUID employmentId, UUID legalEntityId, UUID personId, String kind,
            LocalDate periodStart, String regime, int capMin, UUID packId, String noticeRef) {
        db.jdbc().sql("""
                        insert into att.ot_cap_ledger (tenant_id, employment_id, legal_entity_id, person_id,
                               period_kind, period_start, regime_code, cap_min, legal_pack_id, regime_notice_ref,
                               created_by, updated_by)
                        values (:t, :emp, :le, :p, :k, :ps, :r, :cap, :pack, :ref, :actor, :actor)
                        on conflict (tenant_id, employment_id, period_kind, period_start) do nothing""")
                .param("t", tenant()).param("emp", employmentId).param("le", legalEntityId).param("p", personId)
                .param("k", kind).param("ps", periodStart).param("r", regime).param("cap", capMin)
                .param("pack", packId).param("ref", noticeRef).param("actor", actor()).update();
        LedgerRow row = db.jdbc().sql(LEDGER_COLUMNS + " " + """
                         where tenant_id = :t and employment_id = :emp and period_kind = :k and period_start = :ps
                         for update""")
                .param("t", tenant()).param("emp", employmentId).param("k", kind).param("ps", periodStart)
                .query(OvertimeStore::mapLedger).single();
        if (row.capMin() != capMin || !row.regimeCode().equals(regime)
                || !java.util.Objects.equals(row.regimeNoticeRef(), noticeRef)) {
            db.jdbc().sql("""
                            update att.ot_cap_ledger set cap_min = :cap, regime_code = :r, regime_notice_ref = :ref,
                                   legal_pack_id = :pack, updated_at = now(), updated_by = :actor,
                                   version = version + 1
                             where tenant_id = :t and id = :id""")
                    .param("cap", capMin).param("r", regime).param("ref", noticeRef).param("pack", packId)
                    .param("actor", actor()).param("t", tenant()).param("id", row.id()).update();
            row = new LedgerRow(row.id(), row.employmentId(), row.legalEntityId(), row.periodKind(),
                    row.periodStart(), regime, capMin, row.openingMin(), row.approvedMin(), row.pendingMin(),
                    row.paidMin(), row.overrideMin(), noticeRef);
        }
        return row;
    }

    public void adjustLedger(UUID ledgerId, int pendingDelta, int approvedDelta, int overrideDelta) {
        db.jdbc().sql("""
                        update att.ot_cap_ledger set pending_min = greatest(0, pending_min + :p),
                               approved_min = greatest(0, approved_min + :a),
                               override_min = greatest(0, override_min + :o),
                               updated_at = now(), updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("p", pendingDelta).param("a", approvedDelta).param("o", overrideDelta).param("actor", actor())
                .param("t", tenant()).param("id", ledgerId).update();
    }

    public List<LedgerRow> ledgerOfEmployment(UUID employmentId, LocalDate monthStart, LocalDate yearStart) {
        return db.jdbc().sql(LEDGER_COLUMNS + " " + """
                         where tenant_id = :t and employment_id = :emp
                           and ((period_kind = 'MONTH' and period_start = :m) or (period_kind = 'YEAR' and period_start = :y))
                         order by period_kind""")
                .param("t", tenant()).param("emp", employmentId).param("m", monthStart).param("y", yearStart)
                .query(OvertimeStore::mapLedger).list();
    }

    /** Re-sums {@code paid_min} of the month and year rows from {@code att.overtime_result}. */
    public void refreshPaid(UUID employmentId, LocalDate monthStart, LocalDate yearStart) {
        db.jdbc().sql("""
                        update att.ot_cap_ledger l set paid_min = coalesce((
                                 select sum(r.paid_minutes) from att.overtime_result r
                                  where r.tenant_id = l.tenant_id and r.employment_id = l.employment_id
                                    and r.work_date >= l.period_start
                                    and r.work_date < l.period_start + case when l.period_kind = 'MONTH'
                                                                            then interval '1 month'
                                                                            else interval '1 year' end), 0),
                               updated_at = now(), version = version + 1
                         where l.tenant_id = :t and l.employment_id = :emp
                           and ((l.period_kind = 'MONTH' and l.period_start = :m)
                             or (l.period_kind = 'YEAR' and l.period_start = :y))""")
                .param("t", tenant()).param("emp", employmentId).param("m", monthStart).param("y", yearStart)
                .update();
    }

    private static LedgerRow mapLedger(ResultSet rs, int n) throws SQLException {
        return new LedgerRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getObject(5, LocalDate.class), rs.getString(6), rs.getInt(7), rs.getInt(8),
                rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getString(13));
    }

    // =============================================================================================== results

    public List<ResultView> results(UUID employmentId, LocalDate from, LocalDate to) {
        return db.jdbc().sql("""
                        select id, employment_id, work_date, ot_category, actual_minutes, approved_minutes,
                               paid_minutes, preceded_by_day_ot, calc_version, legal_pack_id
                          from att.overtime_result
                         where tenant_id = :t and employment_id = :emp and work_date between :from and :to
                         order by work_date, ot_category""")
                .param("t", tenant()).param("emp", employmentId).param("from", from).param("to", to)
                .query((rs, n) -> new ResultView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getBoolean(8), rs.getInt(9), rs.getObject(10, UUID.class)))
                .list();
    }

    /**
     * Replaces the result rows of one employment × work date: upserts the given categories (calc_version + 1 when a
     * value changed) and deletes the others. Idempotent for the same rows.
     */
    public void replaceResults(UUID employmentId, UUID legalEntityId, UUID orgUnitId, LocalDate workDate,
            List<ResultRow> rows, UUID requestId, UUID packId) {
        Set<String> keep = new HashSet<>();
        for (ResultRow r : rows) {
            keep.add(r.category().code());
            db.jdbc().sql("""
                            insert into att.overtime_result (tenant_id, employment_id, legal_entity_id, org_unit_id,
                                   work_date, ot_category, actual_minutes, approved_minutes, paid_minutes,
                                   preceded_by_day_ot, overtime_request_id, legal_pack_id, created_by, updated_by)
                            values (:t, :emp, :le, :ou, :d, :c, :act, :app, :paid, :flag, :req, :pack, :actor, :actor)
                            on conflict (tenant_id, employment_id, work_date, ot_category) do update
                               set actual_minutes = excluded.actual_minutes,
                                   approved_minutes = excluded.approved_minutes,
                                   paid_minutes = excluded.paid_minutes,
                                   preceded_by_day_ot = excluded.preceded_by_day_ot,
                                   overtime_request_id = excluded.overtime_request_id,
                                   legal_entity_id = excluded.legal_entity_id, org_unit_id = excluded.org_unit_id,
                                   legal_pack_id = excluded.legal_pack_id,
                                   calc_version = att.overtime_result.calc_version + 1,
                                   updated_at = now(), updated_by = excluded.updated_by,
                                   version = att.overtime_result.version + 1
                             where (att.overtime_result.actual_minutes, att.overtime_result.approved_minutes,
                                    att.overtime_result.paid_minutes, att.overtime_result.preceded_by_day_ot,
                                    att.overtime_result.overtime_request_id)
                                   is distinct from (excluded.actual_minutes, excluded.approved_minutes,
                                    excluded.paid_minutes, excluded.preceded_by_day_ot,
                                    excluded.overtime_request_id)""")
                    .param("t", tenant()).param("emp", employmentId).param("le", legalEntityId)
                    .param("ou", orgUnitId).param("d", workDate).param("c", r.category().code())
                    .param("act", r.actualMin()).param("app", r.approvedMin()).param("paid", r.paidMin())
                    .param("flag", r.precededByDayOt()).param("req", requestId).param("pack", packId)
                    .param("actor", actor()).update();
        }
        db.jdbc().sql("""
                        delete from att.overtime_result
                         where tenant_id = :t and employment_id = :emp and work_date = :d
                           and not (ot_category = any (:keep))""")
                .param("t", tenant()).param("emp", employmentId).param("d", workDate)
                .param("keep", keep.toArray(String[]::new)).update();
    }

    // ========================================================================================== facts / views

    public Optional<EmploymentFacts> employment(UUID employmentId) {
        return db.jdbc().sql("""
                        select employment_id, person_id, legal_entity_id, hire_date, termination_date, status
                          from att.ext_hr_employment where tenant_id = :t and employment_id = :emp""")
                .param("t", tenant()).param("emp", employmentId)
                .query((rs, n) -> new EmploymentFacts(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class), rs.getString(6)))
                .optional();
    }

    /** Protected-worker types of the employment on {@code date} (BR-ATT-048), via {@code att.protected_flags}. */
    public Set<String> protectedTypes(UUID employmentId, LocalDate date) {
        return db.jdbc().sql("select * from att.protected_flags(:emp, :d)")
                .param("emp", employmentId).param("d", date)
                .query((rs, n) -> {
                    Set<String> s = new HashSet<>();
                    String[] types = {"PREGNANCY", "CHILD_UNDER_12M", "DISABILITY", "MINOR", "ELDERLY_WORKER",
                            "HAZARDOUS_JOB"};
                    for (int i = 0; i < types.length; i++) {
                        if (rs.getBoolean(i + 1)) {
                            s.add(types[i]);
                        }
                    }
                    return s;
                })
                .optional().orElse(Set.of());
    }

    /** True when {@code date} lies in a CLOSED or LOCKED attendance period of the legal entity. */
    public boolean frozen(UUID legalEntityId, LocalDate date) {
        return db.jdbc().sql("""
                        select exists (select 1 from att.attendance_period
                                        where tenant_id = :t and legal_entity_id = :le
                                          and status in ('CLOSED','LOCKED')
                                          and :d between period_start and period_end)""")
                .param("t", tenant()).param("le", legalEntityId).param("d", date).query(Boolean.class).single();
    }

    // ============================================================================ protected-worker projection

    /** Upserts one {@code people.SpecialStatusChanged} row; {@code source_version} counts the applied events. */
    public void upsertSpecialStatus(UUID sourceRecordId, UUID personId, UUID employmentId, String type,
            LocalDate validFrom, LocalDate validTo, UUID eventId) {
        db.jdbc().sql("""
                        insert into att.ext_hr_special_status (tenant_id, source_record_id, person_id, employment_id,
                               protection_type, valid_from, valid_to, source_event_id, source_version)
                        values (:t, :src, :p, :emp, :type, :from, :to, :ev, 1)
                        on conflict (tenant_id, source_record_id) do update
                           set person_id = excluded.person_id, employment_id = excluded.employment_id,
                               protection_type = excluded.protection_type, valid_from = excluded.valid_from,
                               valid_to = excluded.valid_to, source_event_id = excluded.source_event_id,
                               source_version = att.ext_hr_special_status.source_version + 1,
                               synced_at = now(), updated_at = now()""")
                .param("t", tenant()).param("src", sourceRecordId).param("p", personId).param("emp", employmentId)
                .param("type", type).param("from", validFrom).param("to", validTo).param("ev", eventId).update();
    }

    public void deleteSpecialStatus(UUID sourceRecordId) {
        db.jdbc().sql("delete from att.ext_hr_special_status where tenant_id = :t and source_record_id = :src")
                .param("t", tenant()).param("src", sourceRecordId).update();
    }

    /** Employments of a person known to T&amp;A (a person-level status can change the flags of each). */
    public List<UUID> employmentsOfPerson(UUID personId) {
        return new ArrayList<>(db.jdbc().sql("""
                        select employment_id from att.ext_hr_employment where tenant_id = :t and person_id = :p""")
                .param("t", tenant()).param("p", personId).query(UUID.class).list());
    }

    /** The organisation of an employment on a date: its primary assignment line (else any line). */
    public record Placement(UUID legalEntityId, UUID orgUnitId, UUID locationId) {
    }

    /**
     * Placement on {@code date}; without a line on that date, the latest line that started before it, else the
     * earliest one (for scope checks on dates outside the employment).
     */
    public Optional<Placement> placement(UUID employmentId, LocalDate date) {
        return db.jdbc().sql("""
                        select legal_entity_id, org_unit_id, location_id from att.ext_hr_assignment
                         where tenant_id = :t and employment_id = :emp
                         order by (valid_during @> :d) desc, (assignment_type = 'PRIMARY') desc,
                                  (valid_from <= :d) desc,
                                  case when valid_from <= :d then valid_from end desc nulls last, valid_from
                         limit 1""")
                .param("t", tenant()).param("emp", employmentId).param("d", date)
                .query((rs, n) -> new Placement(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)))
                .optional();
    }

    /** Sets the opening minutes (year-to-date OT loaded at go-live, BR-ATT-086) of a locked ledger row. */
    public void setOpening(UUID ledgerId, int openingMin) {
        db.jdbc().sql("""
                        update att.ot_cap_ledger set opening_min = :o, updated_at = now(), updated_by = :actor,
                               version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("o", openingMin).param("actor", actor()).param("t", tenant()).param("id", ledgerId).update();
    }

    /** Σ opening minutes of the given ledger rows (opening-balance reconciliation). */
    public long openingMinutes(List<UUID> ledgerIds) {
        if (ledgerIds.isEmpty()) {
            return 0;
        }
        return db.jdbc().sql("""
                        select coalesce(sum(opening_min), 0) from att.ot_cap_ledger
                         where tenant_id = :t and id in (:ids)""")
                .param("t", tenant()).param("ids", ledgerIds).query(Long.class).single();
    }

    /** IANA time zone of the location on {@code date} from {@code att.ext_core_location}; empty when unknown. */
    public Optional<String> timeZone(UUID locationId, LocalDate date) {
        if (locationId == null) {
            return Optional.empty();
        }
        return db.jdbc().sql("""
                        select time_zone from att.ext_core_location
                         where tenant_id = :t and location_id = :loc
                         order by (valid_during @> cast(:d as date)) desc, valid_from desc limit 1""")
                .param("t", tenant()).param("loc", locationId).param("d", date).query(String.class).optional();
    }
}
