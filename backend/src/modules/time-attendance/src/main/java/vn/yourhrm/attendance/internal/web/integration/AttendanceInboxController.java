package vn.yourhrm.attendance.internal.web.integration;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.AttendanceInboxQueries;
import vn.yourhrm.attendance.internal.application.location.LocationProjection;
import vn.yourhrm.attendance.internal.application.location.LocationProjection.ResyncSummary;
import vn.yourhrm.attendance.internal.infra.AttInbox;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Operations view of T&amp;A's inbox: the events of other modules T&amp;A consumed, with FAILED ones (dead letters)
 * and their error. Repair a failed People event with {@code POST /employments/{id}/schedule-assignments/regenerate},
 * Core's locations with {@code POST /integration/locations/resync}.
 */
@RestController
@RequestMapping("/api/v1/attendance/integration")
class AttendanceInboxController {

    private final AttendanceInboxQueries inbox;
    private final LocationProjection locations;

    AttendanceInboxController(AttendanceInboxQueries inbox, LocationProjection locations) {
        this.inbox = inbox;
        this.locations = locations;
    }

    @GetMapping("/inbox")
    @RequiresPermission(AttendancePermissions.INTEGRATION_VIEW)
    public List<AttInbox.Message> inbox(@RequestParam(required = false) String status,
            @RequestParam(required = false) String consumer, @RequestParam(defaultValue = "100") int limit) {
        return inbox.list(status, consumer, limit);
    }

    /** Rebuilds {@code att.ext_core_location} from Core's locations (go-live, repair of a dead letter). */
    @PostMapping("/locations/resync")
    @RequiresPermission(AttendancePermissions.INTEGRATION_RESYNC)
    public ResyncSummary resyncLocations() {
        return locations.resyncAll();
    }
}
