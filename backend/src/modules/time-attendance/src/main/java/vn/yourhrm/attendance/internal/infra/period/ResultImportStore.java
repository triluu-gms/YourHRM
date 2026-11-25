package vn.yourhrm.attendance.internal.infra.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.attendance_result_import_line} (DDL v0.9): the staged rows of an Attendance Result import
 * (BR-ATT-025, R2-18), plus the lookups that place an imported row (period, assignment line). Runs inside the caller's
 * T&amp;A transaction.
 */
@Component
public class ResultImportStore {

    /** A staged row; {@code resultId} is set once PUBLISHED. */
    public record StagedLine(UUID id, UUID importJobId, int rowNo, UUID periodId, LocalDate periodStart,
            UUID employmentId, UUID assignmentKey, LocalDate segmentFrom, LocalDate segmentTo, UUID legalEntityId,
            UUID orgUnitId, UUID payGroupId, UUID costCentreId, String category, String unit, BigDecimal quantity,
            BigDecimal periodQuantity, String status, java.time.Instant jobCommittedAt, UUID resultId) {
    }

    /** The assignment line of an employment on a date (primary first). */
    public record Line(UUID assignmentKey, UUID legalEntityId, UUID orgUnitId, UUID costCentreId) {
    }

    /** A period found by its start, without locking it. */
    public record PeriodRef(UUID id, LocalDate periodStart, LocalDate periodEnd, String status) {
    }

    private final AttDb db;

    public ResultImportStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    public Optional<PeriodRef> period(UUID legalEntityId, UUID payGroupId, LocalDate periodStart) {
        return db.jdbc().sql("""
                        select id, period_start, period_end, status from att.attendance_period
                         where tenant_id = :t and legal_entity_id = :le and pay_group_id = :pg and period_start = :s""")
                .param("t", tenant()).param("le", legalEntityId).param("pg", payGroupId).param("s", periodStart)
                .query((rs, n) -> new PeriodRef(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class), rs.getString(4)))
                .optional();
    }

    public Optional<Line> line(UUID employmentId, LocalDate date) {
        return db.jdbc().sql("""
                        select assignment_key, legal_entity_id, org_unit_id, cost_centre_id from att.ext_hr_assignment
                         where tenant_id = :t and employment_id = :e and valid_during @> cast(:d as date)
                         order by (assignment_type = 'PRIMARY') desc, valid_from limit 1""")
                .param("t", tenant()).param("e", employmentId).param("d", date)
                .query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)))
                .optional();
    }

    /** Stages (or re-stages, while still STAGED) the row of the job; returns its id. */
    public UUID stage(StagedLine l) {
        return db.jdbc().sql("""
                        insert into att.attendance_result_import_line (tenant_id, import_job_id, row_no, period_id,
                               period_start, employment_id, assignment_key, segment_from, segment_to, legal_entity_id,
                               org_unit_id, pay_group_id, cost_centre_id, category_code, unit, quantity,
                               period_quantity, created_by, updated_by)
                        values (:t, :j, :r, :p, :ps, :e, :ak, :sf, :st, :le, :ou, :pg, :cc, :cat, :u, :q, :pq, :by, :by)
                        on conflict (tenant_id, import_job_id, row_no) do update
                           set period_id = excluded.period_id, period_start = excluded.period_start,
                               employment_id = excluded.employment_id, assignment_key = excluded.assignment_key,
                               segment_from = excluded.segment_from, segment_to = excluded.segment_to,
                               legal_entity_id = excluded.legal_entity_id, org_unit_id = excluded.org_unit_id,
                               pay_group_id = excluded.pay_group_id, cost_centre_id = excluded.cost_centre_id,
                               category_code = excluded.category_code, unit = excluded.unit,
                               quantity = excluded.quantity, period_quantity = excluded.period_quantity,
                               updated_by = excluded.updated_by
                         where att.attendance_result_import_line.status = 'STAGED'
                        returning id""")
                .param("t", tenant()).param("j", l.importJobId()).param("r", l.rowNo()).param("p", l.periodId())
                .param("ps", l.periodStart()).param("e", l.employmentId()).param("ak", l.assignmentKey())
                .param("sf", l.segmentFrom()).param("st", l.segmentTo()).param("le", l.legalEntityId())
                .param("ou", l.orgUnitId()).param("pg", l.payGroupId()).param("cc", l.costCentreId())
                .param("cat", l.category()).param("u", l.unit()).param("q", l.quantity())
                .param("pq", l.periodQuantity()).param("by", actor())
                .query(UUID.class).optional()
                .orElseThrow(() -> new IllegalStateException("Row " + l.rowNo() + " of import job " + l.importJobId()
                        + " was already published"));
    }

    /** Every row of the job, in row order. */
    public List<StagedLine> lines(UUID importJobId) {
        return db.jdbc().sql("""
                        select id, import_job_id, row_no, period_id, period_start, employment_id, assignment_key,
                               segment_from, segment_to, legal_entity_id, org_unit_id, pay_group_id, cost_centre_id,
                               category_code, unit, quantity, period_quantity, status, job_committed_at, result_id
                          from att.attendance_result_import_line
                         where tenant_id = :t and import_job_id = :j order by row_no""")
                .param("t", tenant()).param("j", importJobId)
                .query((rs, n) -> new StagedLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getInt(3), rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getObject(8, LocalDate.class),
                        rs.getObject(9, LocalDate.class), rs.getObject(10, UUID.class), rs.getObject(11, UUID.class),
                        rs.getObject(12, UUID.class), rs.getObject(13, UUID.class), rs.getString(14),
                        rs.getString(15), rs.getBigDecimal(16), rs.getBigDecimal(17), rs.getString(18),
                        rs.getObject(19, java.time.OffsetDateTime.class) == null ? null
                                : rs.getObject(19, java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(20, UUID.class)))
                .list();
    }

    /** Core committed the job (core.ImportCommitted): its rows may now be published. */
    public int markJobCommitted(UUID importJobId) {
        return db.jdbc().sql("""
                        update att.attendance_result_import_line set job_committed_at = now(), updated_by = :by
                         where tenant_id = :t and import_job_id = :j and job_committed_at is null""")
                .param("t", tenant()).param("j", importJobId).param("by", actor()).update();
    }

    public void markPublished(UUID importJobId, UUID periodId, UUID resultId) {
        db.jdbc().sql("""
                        update att.attendance_result_import_line set status = 'PUBLISHED', result_id = :r,
                               updated_by = :by
                         where tenant_id = :t and import_job_id = :j and period_id = :p and status = 'STAGED'""")
                .param("t", tenant()).param("j", importJobId).param("p", periodId).param("r", resultId)
                .param("by", actor()).update();
    }
}
