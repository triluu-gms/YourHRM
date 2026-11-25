package vn.yourhrm.attendance.internal.infra.approval;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.change_request} (DDL v0.10, ATT-P-11): period reopen and leave balance adjustment requests that
 * wait for Core's approval. Every method runs inside the caller's T&amp;A transaction.
 */
@Component
public class ChangeRequestStore {

    public static final String PERIOD_REOPEN = "PERIOD_REOPEN";
    public static final String BALANCE_ADJUST = "BALANCE_ADJUST";

    public record ChangeRequest(UUID id, String requestType, UUID legalEntityId, UUID orgUnitId, UUID periodId,
            UUID employmentId, UUID leaveTypeId, Integer leaveYear, BigDecimal quantity, String reason, String status,
            UUID workflowInstanceId, Instant decidedAt, UUID decidedBy, String outcome, UUID ledgerTxnId,
            Instant createdAt, UUID createdBy, int version) {
    }

    private static final String COLUMNS = """
            select id, request_type, legal_entity_id, org_unit_id, period_id, employment_id, leave_type_id, leave_year,
                   quantity, reason_text, status, workflow_instance_id, decided_at, decided_by, outcome, ledger_txn_id,
                   created_at, created_by, version
              from att.change_request
            """;

    private final AttDb db;

    public ChangeRequestStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static ChangeRequest row(ResultSet rs, int n) throws SQLException {
        int year = rs.getInt("leave_year");
        Integer leaveYear = rs.wasNull() ? null : year;
        return new ChangeRequest(rs.getObject("id", UUID.class), rs.getString("request_type"),
                rs.getObject("legal_entity_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                rs.getObject("period_id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getObject("leave_type_id", UUID.class), leaveYear, rs.getBigDecimal("quantity"),
                rs.getString("reason_text"), rs.getString("status"), rs.getObject("workflow_instance_id", UUID.class),
                instant(rs, "decided_at"), rs.getObject("decided_by", UUID.class), rs.getString("outcome"),
                rs.getObject("ledger_txn_id", UUID.class), instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class), rs.getInt("version"));
    }

    public Optional<ChangeRequest> find(UUID id) {
        return db.jdbc().sql(COLUMNS + " where tenant_id = :t and id = :id").param("t", tenant()).param("id", id)
                .query(ChangeRequestStore::row).optional();
    }

    /** The open reopen request of a period (at most one, {@code change_request_reopen_pending_uq}). */
    public Optional<ChangeRequest> pendingReopen(UUID periodId) {
        return db.jdbc().sql(COLUMNS + """
                         where tenant_id = :t and period_id = :p and request_type = 'PERIOD_REOPEN'
                           and status = 'PENDING'""")
                .param("t", tenant()).param("p", periodId).query(ChangeRequestStore::row).optional();
    }

    public UUID insertReopen(UUID periodId, UUID legalEntityId, String reason) {
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into att.change_request (tenant_id, id, request_type, legal_entity_id, period_id,
                               reason_text, created_by, updated_by)
                        values (:t, :id, 'PERIOD_REOPEN', :le, :p, :r, :by, :by)""")
                .param("t", tenant()).param("id", id).param("le", legalEntityId).param("p", periodId)
                .param("r", reason).param("by", actor()).update();
        return id;
    }

    public UUID insertAdjust(UUID employmentId, UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, int leaveYear,
            BigDecimal quantity, String reason) {
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into att.change_request (tenant_id, id, request_type, legal_entity_id, org_unit_id,
                               employment_id, leave_type_id, leave_year, quantity, reason_text, created_by, updated_by)
                        values (:t, :id, 'BALANCE_ADJUST', :le, :ou, :e, :lt, :y, :q, :r, :by, :by)""")
                .param("t", tenant()).param("id", id).param("le", legalEntityId).param("ou", orgUnitId)
                .param("e", employmentId).param("lt", leaveTypeId).param("y", leaveYear).param("q", quantity)
                .param("r", reason).param("by", actor()).update();
        return id;
    }

    public void setWorkflow(UUID id, UUID instanceId) {
        db.jdbc().sql("""
                        update att.change_request set workflow_instance_id = :w, updated_by = :by,
                               version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).param("w", instanceId).param("by", actor()).update();
    }

    /** PENDING → {@code status} with the decision; false when the request was no longer PENDING. */
    public boolean decide(UUID id, String status, UUID decidedBy, String outcome, UUID ledgerTxnId) {
        return db.jdbc().sql("""
                        update att.change_request
                           set status = :st, decided_at = now(), decided_by = :dby, outcome = :o, ledger_txn_id = :l,
                               updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id and status = 'PENDING'""")
                .param("t", tenant()).param("id", id).param("st", status).param("dby", decidedBy)
                .param("o", outcome).param("l", ledgerTxnId).param("by", actor()).update() == 1;
    }
}
