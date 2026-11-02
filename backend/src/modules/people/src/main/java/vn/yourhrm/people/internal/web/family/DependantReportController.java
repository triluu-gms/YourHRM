package vn.yourhrm.people.internal.web.family;

import java.time.YearMonth;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.family.DependantRegistrationReport;
import vn.yourhrm.people.internal.application.family.DependantRegistrationReport.PdfResult;
import vn.yourhrm.people.internal.application.family.DependantRegistrationReport.Report;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Dependant registration declaration / list per legal entity and period (US-PPL-030): on-screen (masked), Excel and
 * PDF (Core template). Restricted to the caller's data scope of the statutory-report permission.
 */
@RestController
@RequestMapping("/api/v1/people/reports/dependant-registrations")
class DependantReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final DependantRegistrationReport report;

    DependantReportController(DependantRegistrationReport report) {
        this.report = report;
    }

    @GetMapping
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_VIEW)
    public Report view(@RequestParam UUID legalEntityId, @RequestParam YearMonth fromMonth,
            @RequestParam YearMonth toMonth) {
        return report.view(legalEntityId, fromMonth.atDay(1), toMonth.atDay(1));
    }

    @GetMapping("/export.xlsx")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public ResponseEntity<byte[]> excel(@RequestParam UUID legalEntityId, @RequestParam YearMonth fromMonth,
            @RequestParam YearMonth toMonth) {
        byte[] body = report.excel(legalEntityId, fromMonth.atDay(1), toMonth.atDay(1));
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dang-ky-npt-" + fromMonth + "_"
                        + toMonth + ".xlsx\"")
                .body(body);
    }

    /** Generates the PDF from the tenant's template; returns the stored document and a short-lived download link. */
    @PostMapping("/pdf")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public PdfResult pdf(@RequestParam UUID legalEntityId, @RequestParam YearMonth fromMonth,
            @RequestParam YearMonth toMonth) {
        return report.pdf(legalEntityId, fromMonth.atDay(1), toMonth.atDay(1));
    }
}
