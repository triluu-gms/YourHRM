package vn.yourhrm.people.internal.web.onboarding;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.onboarding.OpeningBalanceReportService;
import vn.yourhrm.people.internal.application.onboarding.OpeningBalanceReportService.Export;
import vn.yourhrm.people.internal.application.onboarding.OpeningBalanceReportService.Report;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Reconciliation and data-quality report R20 of the opening-balance employee load (US-PPL-093). The import itself
 * (templates, upload, dry run, reconciliation, sign-off, commit, rollback) runs through Core's import endpoints
 * {@code /api/v1/core/imports/...} with People's import types {@code OB_EMPLOYEE_MASTER}, {@code OB_EMP_*}.
 */
@RestController
@RequestMapping("/api/v1/people/opening-balance")
class OpeningBalanceController {

    private final OpeningBalanceReportService reports;

    OpeningBalanceController(OpeningBalanceReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/report")
    @RequiresPermission(PeoplePermissions.IMPORT_RUN)
    public Report report(@RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "openingLoadOnly", defaultValue = "true") boolean openingLoadOnly) {
        return reports.report(legalEntityId, asOf, openingLoadOnly);
    }

    @GetMapping("/report/export")
    @RequiresPermission(PeoplePermissions.IMPORT_RUN)
    public ResponseEntity<byte[]> export(@RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "openingLoadOnly", defaultValue = "true") boolean openingLoadOnly) {
        Export x = reports.excel(legalEntityId, asOf, openingLoadOnly);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(x.fileName())
                        .build().toString())
                .contentType(MediaType.parseMediaType(x.mediaType()))
                .body(x.content());
    }
}
