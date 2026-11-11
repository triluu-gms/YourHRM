package vn.yourhrm.people.internal.web.integration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.EmployeeDirectory;
import vn.yourhrm.people.api.EmployeeDirectory.EmployeeSnapshot;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.web.assignment.AsKnown;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * REST face of the OUT-03 read API (US-PPL-081): one employee snapshot, and a page of snapshots of a legal entity for
 * payroll / T&amp;A runs. {@code asOf} (business date, default today) and {@code asKnownAt} (a date = as known at the
 * end of that day in Vietnam, or an ISO instant with offset; default = current knowledge). The salary summary is
 * present only for callers holding {@code hr.compensation.view} on the employment.
 */
@RestController
@RequestMapping("/api/v1/people")
class EmployeeSnapshotController {

    private final EmployeeDirectory directory;

    EmployeeSnapshotController(EmployeeDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/employments/{employmentId}/snapshot")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public EmployeeSnapshot snapshot(@PathVariable UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String asKnownAt) {
        return directory.snapshot(employmentId, asOf, knownAt(asKnownAt))
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
    }

    @GetMapping("/legal-entities/{legalEntityId}/employee-snapshots")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<EmployeeSnapshot> snapshots(@PathVariable UUID legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String asKnownAt,
            @RequestParam(defaultValue = "500") int limit, @RequestParam(defaultValue = "0") int offset) {
        return directory.snapshots(legalEntityId, asOf, knownAt(asKnownAt), limit, offset);
    }

    /** The inclusive instant of an {@code asKnownAt} parameter (AsKnown returns the exclusive bound). */
    static Instant knownAt(String asKnownAt) {
        Instant bound = AsKnown.exclusiveBound(asKnownAt);
        return bound == null ? null : bound.minus(1, ChronoUnit.MICROS);
    }
}
