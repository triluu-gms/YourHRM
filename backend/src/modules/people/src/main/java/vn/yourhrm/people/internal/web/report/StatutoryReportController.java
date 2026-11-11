package vn.yourhrm.people.internal.web.report;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.core.api.Documents.DownloadLink;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.report.LabourReportService;
import vn.yourhrm.people.internal.application.report.LabourReportService.Fluctuation;
import vn.yourhrm.people.internal.application.report.LabourReportService.LabourChange;
import vn.yourhrm.people.internal.application.report.StatutoryReportService;
import vn.yourhrm.people.internal.application.report.StatutoryReportService.GenerateRequest;
import vn.yourhrm.people.internal.application.report.StatutoryReportService.Generation;
import vn.yourhrm.people.internal.application.report.StatutoryReportService.ReportView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Statutory labour reports and their register (US-PPL-069/070/071, BR-PPL-141/142): on-screen review of the 6-monthly
 * labour change report and the monthly fluctuation notice, generation into the register (file versions in Core's
 * document service), submission with date / channel / reference, the authority's receipt, cancel, the 6-monthly PDF.
 */
@RestController
@RequestMapping("/api/v1/people/statutory-reports")
class StatutoryReportController {

    private final StatutoryReportService register;
    private final LabourReportService reports;

    StatutoryReportController(StatutoryReportService register, LabourReportService reports) {
        this.register = register;
        this.reports = reports;
    }

    record GenerateBody(@NotNull UUID legalEntityId, @NotBlank String reportType, Integer year,
            @Size(max = 30) String period, Integer month, LocalDate asOf, Map<String, @Size(max = 1000) String> notes) {
    }

    record SubmitBody(@NotNull Integer version, @NotNull LocalDate submittedOn, @Size(max = 20) String channel,
            @Size(max = 200) String submissionRef) {
    }

    record VersionBody(@NotNull Integer version) {
    }

    record NotesBody(Map<String, @Size(max = 1000) String> notes) {
    }

    @GetMapping
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_VIEW)
    public List<ReportView> list(@RequestParam("legalEntityId") UUID legalEntityId,
            @RequestParam(name = "reportType", required = false) String reportType,
            @RequestParam(name = "year", required = false) Integer year) {
        return register.list(legalEntityId, reportType, year);
    }

    @GetMapping("/labour-change")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_VIEW)
    public LabourChange labourChange(@RequestParam("legalEntityId") UUID legalEntityId, @RequestParam("year") int year,
            @RequestParam("period") String period) {
        return reports.labourChange(legalEntityId, year, period, Map.of());
    }

    @GetMapping("/labour-fluctuation")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_VIEW)
    public Fluctuation labourFluctuation(@RequestParam("legalEntityId") UUID legalEntityId,
            @RequestParam("month") @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return reports.fluctuation(legalEntityId, month, false);
    }

    @GetMapping("/{id}")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_VIEW)
    public ReportView get(@PathVariable UUID id) {
        return register.get(id);
    }

    @GetMapping("/{id}/download")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_VIEW)
    public DownloadLink download(@PathVariable UUID id,
            @RequestParam(name = "fileVersion", required = false) Integer fileVersion) {
        return register.download(id, fileVersion);
    }

    @PostMapping
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public ResponseEntity<Generation> generate(@Valid @RequestBody GenerateBody body) {
        return ResponseEntity.status(201).body(register.generate(new GenerateRequest(body.legalEntityId(),
                body.reportType(), body.year(), body.period(), body.month(), body.asOf(), body.notes())));
    }

    @PostMapping("/{id}/submit")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public ReportView submit(@PathVariable UUID id, @Valid @RequestBody SubmitBody body) {
        return register.submit(id, body.version(), body.submittedOn(), body.channel(), body.submissionRef());
    }

    @PostMapping(value = "/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public ReportView receipt(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
            @RequestParam(name = "title", required = false) String title) throws IOException {
        return register.uploadReceipt(id, title, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public ReportView cancel(@PathVariable UUID id, @Valid @RequestBody VersionBody body) {
        return register.cancel(id, body.version());
    }

    @PostMapping("/{id}/pdf")
    @RequiresPermission(PeoplePermissions.STATUTORY_REPORT_EXECUTE)
    public Generated pdf(@PathVariable UUID id, @RequestBody(required = false) NotesBody body) {
        return register.pdf(id, body == null || body.notes() == null ? Map.of() : body.notes());
    }
}
