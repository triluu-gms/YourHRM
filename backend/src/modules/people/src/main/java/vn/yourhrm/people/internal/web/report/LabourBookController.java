package vn.yourhrm.people.internal.web.report;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.report.LabourBookService;
import vn.yourhrm.people.internal.application.report.LabourBookService.Book;
import vn.yourhrm.people.internal.application.report.LabourBookService.Export;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * The electronic labour book (US-PPL-068, BR-PPL-140): on screen as of any date (national id masked), Excel export
 * (national id decrypted for holders of the sensitive view, audited) and the PDF entry of one employee from the Core
 * template {@code HR_LABOUR_BOOK_ENTRY}.
 */
@RestController
@RequestMapping("/api/v1/people/reports/labour-book")
class LabourBookController {

    private final LabourBookService book;

    LabourBookController(LabourBookService book) {
        this.book = book;
    }

    @GetMapping
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_VIEW)
    public Book book(@RequestParam("legalEntityId") UUID legalEntityId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return book.book(legalEntityId, asOf);
    }

    @GetMapping("/export")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public ResponseEntity<byte[]> export(@RequestParam("legalEntityId") UUID legalEntityId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Export x = book.excel(legalEntityId, asOf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(x.fileName())
                        .build().toString())
                .contentType(MediaType.parseMediaType(x.mediaType()))
                .body(x.content());
    }

    @PostMapping("/employments/{employmentId}/pdf")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public Generated entryPdf(@PathVariable UUID employmentId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return book.entryPdf(employmentId, asOf);
    }
}
