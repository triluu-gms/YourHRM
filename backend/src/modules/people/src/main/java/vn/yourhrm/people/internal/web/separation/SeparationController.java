package vn.yourhrm.people.internal.web.separation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.separation.SeparationReasons;
import vn.yourhrm.people.internal.application.separation.SeparationRecords.SeparationView;
import vn.yourhrm.people.internal.application.separation.SeparationRecords.ServicePeriodView;
import vn.yourhrm.people.internal.application.separation.SeparationService;
import vn.yourhrm.people.internal.application.separation.SeparationService.Attributes;
import vn.yourhrm.people.internal.application.separation.SeparationService.Preview;
import vn.yourhrm.people.internal.application.separation.SeparationService.SeparationResult;
import vn.yourhrm.people.internal.application.separation.SeparationService.ServicePeriodData;
import vn.yourhrm.people.internal.application.separation.SeparationService.Submission;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * HR separation records (EP-PPL-08, US-PPL-060..067): reasons and legal grounds, the separation of an employment with
 * its notice / protection / severance assessment, the protected-employee evidence, submission for approval, cancel,
 * and the service periods supplied to Payroll. Out-of-scope employments look missing (404).
 */
@RestController
@RequestMapping("/api/v1/people")
class SeparationController {

    private final SeparationService separations;

    SeparationController(SeparationService separations) {
        this.separations = separations;
    }

    record AttributesRequest(@NotBlank String legalGroundCode, @Size(max = 60) String reasonCode, LocalDate requestDate,
            LocalDate noticeDate, @NotNull LocalDate lastWorkingDate, LocalDate terminationDate,
            LocalDate accessRevocationDate, @Size(max = 60) String specialTradeCode,
            @Size(max = 60) String noticeExemptReasonCode, @Size(max = 2000) String protectedOverrideReason,
            Boolean rehireEligible, @Size(max = 200) String signatoryName, @Size(max = 200) String signatoryTitle) {

        Attributes toAttributes() {
            return new Attributes(legalGroundCode, reasonCode, requestDate, noticeDate, lastWorkingDate,
                    terminationDate, accessRevocationDate, specialTradeCode, noticeExemptReasonCode,
                    protectedOverrideReason, rehireEligible, signatoryName, signatoryTitle);
        }
    }

    record UpdateRequest(@NotNull Integer version, @NotNull @Valid AttributesRequest attributes) {
    }

    record SubmitRequest(@NotNull Integer version, @Size(max = 254) String personalEmail, Boolean noPersonalEmail,
            @Size(max = 2000) String note) {
    }

    record CancelRequest(@NotNull Integer version, @Size(max = 2000) String reason) {
    }

    record ServicePeriodRequest(@NotBlank String periodKind, @NotNull LocalDate periodFrom, @NotNull LocalDate periodTo,
            UUID sourceEmploymentId, @Size(max = 500) String note) {
    }

    record ContractExpiryRequest(@NotNull UUID contractId, LocalDate noticeDate) {
    }

    // ------------------------------------------------------------------------------------------------ catalogue

    @GetMapping("/separations/reasons")
    @RequiresPermission(PeoplePermissions.SEPARATION_VIEW)
    public SeparationReasons.Catalogue reasons(@RequestParam(name = "legalEntityId", required = false) UUID le,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return separations.reasons(le, asOf);
    }

    // ------------------------------------------------------------------------------------------------ queries

    @GetMapping("/separations")
    @RequiresPermission(PeoplePermissions.SEPARATION_VIEW)
    public List<SeparationView> list(@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return separations.list(status, legalEntityId, from, to);
    }

    @GetMapping("/separations/{id}")
    @RequiresPermission(PeoplePermissions.SEPARATION_VIEW)
    public SeparationView get(@PathVariable UUID id) {
        return separations.get(id);
    }

    @GetMapping("/employments/{employmentId}/separation")
    @RequiresPermission(PeoplePermissions.SEPARATION_VIEW)
    public SeparationView live(@PathVariable UUID employmentId) {
        return separations.liveOf(employmentId);
    }

    @GetMapping("/employments/{employmentId}/separations")
    @RequiresPermission(PeoplePermissions.SEPARATION_VIEW)
    public List<SeparationView> ofEmployment(@PathVariable UUID employmentId) {
        return separations.ofEmployment(employmentId);
    }

    // ------------------------------------------------------------------------------------------------ commands

    @PostMapping("/employments/{employmentId}/separation/preview")
    @RequiresPermission(PeoplePermissions.SEPARATION_CREATE)
    public Preview preview(@PathVariable UUID employmentId, @Valid @RequestBody AttributesRequest req) {
        return separations.preview(employmentId, req.toAttributes());
    }

    @PostMapping("/employments/{employmentId}/separation")
    @RequiresPermission(PeoplePermissions.SEPARATION_CREATE)
    public ResponseEntity<SeparationResult> create(@PathVariable UUID employmentId,
            @Valid @RequestBody AttributesRequest req) {
        return ResponseEntity.status(201).body(separations.create(employmentId, req.toAttributes()));
    }

    @PostMapping("/separations/contract-expiry")
    @RequiresPermission(PeoplePermissions.SEPARATION_CREATE)
    public ResponseEntity<SeparationResult> atContractExpiry(@Valid @RequestBody ContractExpiryRequest req) {
        return ResponseEntity.status(201).body(separations.openForContractExpiry(req.contractId(), req.noticeDate()));
    }

    @PutMapping("/separations/{id}")
    @RequiresPermission(PeoplePermissions.SEPARATION_UPDATE)
    public SeparationResult update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest req) {
        return separations.update(id, req.version(), req.attributes().toAttributes());
    }

    @PostMapping(value = "/separations/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PeoplePermissions.SEPARATION_UPDATE)
    public SeparationView evidence(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
            @RequestParam(name = "title", required = false) String title) throws IOException {
        return separations.uploadEvidence(id, title, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @PostMapping("/separations/{id}/submit")
    @RequiresPermission(PeoplePermissions.SEPARATION_UPDATE)
    public SeparationResult submit(@PathVariable UUID id, @Valid @RequestBody SubmitRequest req) {
        return separations.submit(id, req.version(), new Submission(req.personalEmail(),
                Boolean.TRUE.equals(req.noPersonalEmail()), req.note()));
    }

    @PostMapping("/separations/{id}/cancel")
    @RequiresPermission(PeoplePermissions.SEPARATION_UPDATE)
    public SeparationView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest req) {
        return separations.cancel(id, req.version(), req.reason());
    }

    @PostMapping("/separations/{id}/service-periods")
    @RequiresPermission(PeoplePermissions.SEPARATION_UPDATE)
    public ResponseEntity<ServicePeriodView> addPeriod(@PathVariable UUID id,
            @Valid @RequestBody ServicePeriodRequest req) {
        return ResponseEntity.status(201).body(separations.addServicePeriod(id, new ServicePeriodData(req.periodKind(),
                req.periodFrom(), req.periodTo(), req.sourceEmploymentId(), req.note())));
    }

    @DeleteMapping("/separations/{id}/service-periods/{periodId}")
    @RequiresPermission(PeoplePermissions.SEPARATION_UPDATE)
    public ResponseEntity<Void> removePeriod(@PathVariable UUID id, @PathVariable UUID periodId) {
        separations.removeServicePeriod(id, periodId);
        return ResponseEntity.noContent().build();
    }
}
