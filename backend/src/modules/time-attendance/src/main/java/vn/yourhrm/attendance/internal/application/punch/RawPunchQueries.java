package vn.yourhrm.attendance.internal.application.punch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.Placement;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.PunchRow;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Read access to the raw punches of an employment (BR-ATT-020, US-ATT-013/021). Punch times are presence data
 * (pii:P2): the employment must be in the reader's {@code att.punch.view} scope (placement on {@code to}), and every
 * read is written to the audit log (NFR-ATT-008).
 */
@Service
public class RawPunchQueries {

    private final PunchStore store;
    private final AuthorizationService authz;
    private final AuditLog audit;

    public RawPunchQueries(PunchStore store, AuthorizationService authz, AuditLog audit) {
        this.store = store;
        this.authz = authz;
        this.audit = audit;
    }

    @AttTransactional(readOnly = true)
    public List<PunchRow> ofEmployment(UUID employmentId, LocalDate from, LocalDate to) {
        QuarantineService.range(from, to);
        Placement p = store.placement(employmentId, to)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!authz.can(AttendancePermissions.PUNCH_VIEW, new AuthorizationTarget.Employee(employmentId,
                p.legalEntityId(), p.orgUnitId(), p.locationId()), to)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "employment-not-in-scope",
                    "Employment " + employmentId + " is outside your data scope");
        }
        List<PunchRow> rows = store.punchesOfEmployment(employmentId, from, to);
        audit.record(new BusinessAction("VIEW", "att", "raw_punch", employmentId, p.legalEntityId(),
                Map.of("from", from.toString(), "to", to.toString(), "rows", String.valueOf(rows.size()))));
        return rows;
    }
}
