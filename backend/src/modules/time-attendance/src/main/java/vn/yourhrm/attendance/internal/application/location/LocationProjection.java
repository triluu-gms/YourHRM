package vn.yourhrm.attendance.internal.application.location;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.infra.AttInbox;
import vn.yourhrm.attendance.internal.infra.AttInbox.Outcome;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.attendance.internal.infra.location.LocationStore;
import vn.yourhrm.core.api.CoreEvents.LocationChanged;
import vn.yourhrm.core.api.Locations;
import vn.yourhrm.core.api.Locations.Version;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Feeds {@code att.ext_core_location} from Core (ATT-C-07, NFR-ATT-005): the location's time zone decides how punches,
 * corrections and overtime are placed on local work dates, and the remote-area flag the protected-worker rules. On
 * every {@code core.LocationChanged} (created, changed, closed, version removed), after Core's commit, the location's
 * versions are read back from Core's {@link Locations} API and replace the projection as a whole, through T&amp;A's
 * inbox (consumer {@value #CONSUMER}). The inbox key is derived from the versions read, so a redelivered event with no
 * new data is a DUPLICATE while a second change with the same event content is still applied. A failure is a dead
 * letter ({@code GET /integration/inbox}); {@link #resyncAll} rebuilds every location of the tenant.
 */
@Component
public class LocationProjection {

    static final String CONSUMER = "att.LocationProjection";
    private static final Logger log = LoggerFactory.getLogger(LocationProjection.class);

    public record ResyncSummary(int locations, int versions) {
    }

    private final Locations locations;
    private final LocationStore store;
    private final AttInbox inbox;
    private final AuthorizationService authz;
    private final BusinessClock clock;
    private final TransactionTemplate tx;

    LocationProjection(Locations locations, LocationStore store, AttInbox inbox, AuthorizationService authz,
            BusinessClock clock, @Qualifier(AttDb.TX) PlatformTransactionManager attTm) {
        this.locations = locations;
        this.store = store;
        this.inbox = inbox;
        this.authz = authz;
        this.clock = clock;
        this.tx = new TransactionTemplate(attTm);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(LocationChanged e) {
        if (!TenantContext.isBound() || !TenantContext.current().tenantId().equals(e.tenantId())) {
            return;
        }
        try {
            Outcome outcome = sync(e.locationId());
            if (outcome == Outcome.FAILED) {
                log.warn("core.LocationChanged for location {} failed in T&A and is kept as a dead letter",
                        e.locationId());
            }
        } catch (RuntimeException ex) {
            log.warn("core.LocationChanged for location {} not recorded in the T&A inbox: {}", e.locationId(),
                    ex.toString());
        }
    }

    /** Reads the location's versions from Core and replaces its projection (one T&amp;A transaction). */
    public Outcome sync(UUID locationId) {
        List<Version> versions = locations.versions(locationId);   // Core's own transaction
        return apply(locationId, versions);
    }

    /** One T&amp;A transaction: inbox dedupe and the replacement (a failing replacement leaves a dead letter). */
    private Outcome apply(UUID locationId, List<Version> versions) {
        UUID id = inbox.contentId("core.LocationChanged", new Snapshot(locationId, versions));
        return tx.execute(s -> inbox.handle(CONSUMER, id, "core.LocationChanged",
                () -> store.replace(locationId, versions, id) >= 0));
    }

    /** Rebuilds the projection of every location of the tenant (operations, go-live); tenant-wide permission. */
    public ResyncSummary resyncAll() {
        if (!authz.scopeFor(AttendancePermissions.INTEGRATION_RESYNC, clock.today()).tenantWide()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "tenant-scope-required",
                    "Re-syncing the locations needs tenant-wide scope");
        }
        return resyncAllAsSystem();
    }

    /** {@link #resyncAll} without the permission check (system jobs). */
    public ResyncSummary resyncAllAsSystem() {
        int n = 0;
        int versions = 0;
        for (UUID location : locations.locationIds()) {
            List<Version> v = locations.versions(location);
            Outcome o = apply(location, v);
            if (o == Outcome.FAILED) {
                log.warn("Location {} could not be projected into T&A", location);
            }
            n++;
            versions += v.size();
        }
        return new ResyncSummary(n, versions);
    }

    /** Content of the inbox key: the location and the versions read from Core. */
    record Snapshot(UUID locationId, List<Version> versions) {
    }
}
