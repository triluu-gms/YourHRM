package vn.yourhrm.people.internal.web.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.contract.ContractAppendixService;
import vn.yourhrm.people.internal.application.contract.ContractAppendixService.AppendixView;
import vn.yourhrm.people.internal.application.contract.ContractDocumentService;
import vn.yourhrm.people.internal.application.contract.ContractExpiryService;
import vn.yourhrm.people.internal.application.contract.ContractExpiryService.EndingProbation;
import vn.yourhrm.people.internal.application.contract.ContractExpiryService.ExpiringContract;
import vn.yourhrm.people.internal.application.contract.ContractSuspensionService;
import vn.yourhrm.people.internal.application.contract.ContractSuspensionService.SuspensionView;
import vn.yourhrm.people.internal.application.contract.ProbationService;
import vn.yourhrm.people.internal.application.contract.ProbationService.Recorded;
import vn.yourhrm.people.internal.web.contract.ContractController.TermsRequest;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Probation results, appendices, suspensions, the expiry list and contract documents (US-PPL-049/051/053/054/055).
 */
@RestController
@RequestMapping("/api/v1/people")
class ContractLifecycleController {

    private final ProbationService probation;
    private final ContractAppendixService appendices;
    private final ContractSuspensionService suspensions;
    private final ContractExpiryService expiry;
    private final ContractDocumentService documents;

    ContractLifecycleController(ProbationService probation, ContractAppendixService appendices,
            ContractSuspensionService suspensions, ContractExpiryService expiry, ContractDocumentService documents) {
        this.probation = probation;
        this.appendices = appendices;
        this.suspensions = suspensions;
        this.expiry = expiry;
        this.documents = documents;
    }

    record ProbationResultRequest(@NotBlank String result, LocalDate resultDate, @Size(max = 200) String signatoryName,
            @Size(max = 200) String signatoryTitle, @Valid TermsRequest nextContract) {
    }

    record AppendixRequest(@NotNull LocalDate effectiveDate, @NotEmpty Map<String, Object> changes) {
    }

    record SuspensionRequest(@NotBlank String reasonCode, @NotNull LocalDate validFrom, LocalDate plannedEndDate,
            @Size(max = 200) String signatoryName, @Size(max = 200) String signatoryTitle) {
    }

    record ResumeRequest(@NotNull LocalDate returnDate, @NotNull Integer version) {
    }

    record VersionRequest(@NotNull Integer version) {
    }

    // ------------------------------------------------------------------------------------------ probation

    /** Records PASS / FAIL before the probation ends (US-PPL-049); PASS of an agreement drafts the labour contract. */
    @PostMapping("/contracts/{contractId}/probation-result")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public Recorded probationResult(@PathVariable UUID contractId, @Valid @RequestBody ProbationResultRequest req) {
        return probation.record(contractId, new ProbationService.Result(req.result(), req.resultDate(),
                req.signatoryName(), req.signatoryTitle(), req.nextContract() == null ? null
                        : req.nextContract().toTerms()));
    }

    // ------------------------------------------------------------------------------------------ expiry list

    @GetMapping("/contracts/expiring")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public List<ExpiringContract> expiring(
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "withinDays", defaultValue = "60") @PositiveOrZero @Max(366) int withinDays,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId) {
        return expiry.expiring(asOf, withinDays, legalEntityId);
    }

    @GetMapping("/contracts/probations-ending")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public List<EndingProbation> probationsEnding(
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "withinDays", defaultValue = "30") @PositiveOrZero @Max(366) int withinDays,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId) {
        return expiry.probationsEnding(asOf, withinDays, legalEntityId);
    }

    // ------------------------------------------------------------------------------------------ appendices

    @GetMapping("/contracts/{contractId}/appendices")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public List<AppendixView> appendices(@PathVariable UUID contractId) {
        return appendices.list(contractId);
    }

    @PostMapping("/contracts/{contractId}/appendices")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public ResponseEntity<AppendixView> createAppendix(@PathVariable UUID contractId,
            @Valid @RequestBody AppendixRequest req) {
        AppendixView a = appendices.create(contractId, req.effectiveDate(), req.changes());
        return ResponseEntity.created(URI.create("/api/v1/people/contract-appendices/" + a.id())).body(a);
    }

    @GetMapping("/contract-appendices/{appendixId}")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public AppendixView appendix(@PathVariable UUID appendixId) {
        return appendices.get(appendixId);
    }

    @PostMapping(path = "/contract-appendices/{appendixId}/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public AppendixView signAppendix(@PathVariable UUID appendixId, @RequestPart("file") MultipartFile file,
            @RequestParam("version") int version) throws IOException {
        return appendices.sign(appendixId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), version);
    }

    @PostMapping("/contract-appendices/{appendixId}/cancel")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public AppendixView cancelAppendix(@PathVariable UUID appendixId, @Valid @RequestBody VersionRequest req) {
        return appendices.cancel(appendixId, req.version());
    }

    @PostMapping("/contract-appendices/{appendixId}/document")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public Generated appendixDocument(@PathVariable UUID appendixId) {
        return documents.generateAppendix(appendixId);
    }

    // ------------------------------------------------------------------------------------------ documents

    /** Generates the contract from the tenant's template (US-PPL-055) for printing and paper signature. */
    @PostMapping("/contracts/{contractId}/document")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public Generated contractDocument(@PathVariable UUID contractId) {
        return documents.generateContract(contractId);
    }

    // ------------------------------------------------------------------------------------------ suspensions

    @GetMapping("/employments/{employmentId}/contract-suspensions")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public List<SuspensionView> suspensions(@PathVariable UUID employmentId) {
        return suspensions.list(employmentId);
    }

    @PostMapping("/contracts/{contractId}/suspensions")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public ResponseEntity<SuspensionView> suspend(@PathVariable UUID contractId,
            @Valid @RequestBody SuspensionRequest req) {
        SuspensionView s = suspensions.create(contractId, req.reasonCode(), req.validFrom(), req.plannedEndDate(),
                req.signatoryName(), req.signatoryTitle());
        return ResponseEntity.created(URI.create("/api/v1/people/contract-suspensions/" + s.id())).body(s);
    }

    @GetMapping("/contract-suspensions/{suspensionId}")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public SuspensionView suspension(@PathVariable UUID suspensionId) {
        return suspensions.get(suspensionId);
    }

    @PostMapping("/contract-suspensions/{suspensionId}/resume")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public SuspensionView resume(@PathVariable UUID suspensionId, @Valid @RequestBody ResumeRequest req) {
        return suspensions.resume(suspensionId, req.returnDate(), req.version());
    }

    @PostMapping("/contract-suspensions/{suspensionId}/cancel")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public SuspensionView cancelSuspension(@PathVariable UUID suspensionId, @Valid @RequestBody VersionRequest req) {
        return suspensions.cancel(suspensionId, req.version());
    }
}
