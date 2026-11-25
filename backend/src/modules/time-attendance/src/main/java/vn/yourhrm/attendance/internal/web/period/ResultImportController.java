package vn.yourhrm.attendance.internal.web.period;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.period.ResultImportService;
import vn.yourhrm.attendance.internal.application.period.ResultImportService.JobStatus;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * The T&amp;A side of an Attendance Result import ({@code ATT_RESULT_IMPORT}, BR-ATT-025, R2-18): which periods a
 * committed job published, and a repeat of the publish when it failed after Core's commit (e.g. a period was closed
 * meanwhile and has been reopened since). Upload, validation and commit are Core's import endpoints.
 */
@RestController
@RequestMapping("/api/v1/attendance/result-imports")
class ResultImportController {

    private final ResultImportService imports;

    ResultImportController(ResultImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/{jobId}")
    @RequiresPermission(AttendancePermissions.RESULT_IMPORT)
    public JobStatus status(@PathVariable UUID jobId) {
        return imports.statusInScope(jobId);
    }

    @PostMapping("/{jobId}/publish")
    @RequiresPermission(AttendancePermissions.RESULT_IMPORT)
    public JobStatus publish(@PathVariable UUID jobId) {
        return imports.publishInScope(jobId);
    }
}
