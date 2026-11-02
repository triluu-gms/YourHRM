package vn.yourhrm.people.internal.web.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.application.contract.ContractService;
import vn.yourhrm.people.internal.application.contract.ContractTerms;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Labour contracts of an employment (EP-PPL-06: US-PPL-047/048/050/052/056). Scope: the employee's data scope
 * through {@code EmployeeAccess} (404 when out of scope).
 */
@RestController
@RequestMapping("/api/v1/people")
class ContractController {

    private final ContractService contracts;

    ContractController(ContractService contracts) {
        this.contracts = contracts;
    }

    record TermsRequest(
            @Size(max = 32) String contractTypeCode,
            LocalDate startDate,
            LocalDate endDate,
            @Positive Integer probationDays,
            @Size(max = 200) String signatoryName,
            @Size(max = 200) String signatoryTitle,
            @Size(max = 64) String probationLevelCode,
            @DecimalMin("0") @DecimalMax("100") BigDecimal probationPayPct,
            UUID jobTitleId,
            @Size(max = 64) String definiteExceptionCode,
            @Size(max = 1000) String definiteExceptionNote) {

        ContractTerms toTerms() {
            return new ContractTerms(contractTypeCode, startDate, endDate, probationDays, signatoryName, signatoryTitle,
                    probationLevelCode, probationPayPct, jobTitleId, definiteExceptionCode, definiteExceptionNote);
        }
    }

    record VersionRequest(@NotNull Integer version) {
    }

    @GetMapping("/employments/{employmentId}/contracts")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public List<ContractView> list(@PathVariable UUID employmentId) {
        return contracts.list(employmentId);
    }

    /** The contract in force on {@code asOf}, as known at {@code asKnownAt} (NFR-PPL-001, see the service note). */
    @GetMapping("/employments/{employmentId}/contracts/current")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public ContractView current(@PathVariable UUID employmentId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "asKnownAt", required = false) Instant asKnownAt) {
        return contracts.asOf(employmentId, asOf, asKnownAt)
                .orElseThrow(() -> ApiException.notFound("Contract in force on " + (asOf == null ? "today" : asOf)
                        + " of employment", employmentId));
    }

    @PostMapping("/employments/{employmentId}/contracts")
    @RequiresPermission(PeoplePermissions.CONTRACT_CREATE)
    public ResponseEntity<ContractView> create(@PathVariable UUID employmentId,
            @Valid @RequestBody TermsRequest req) {
        if (req.contractTypeCode() == null || req.contractTypeCode().isBlank()) {
            throw ApiException.unprocessable("invalid-contract-type", "The contract type is required");
        }
        ContractView c = contracts.create(employmentId, req.toTerms());
        return ResponseEntity.created(URI.create("/api/v1/people/contracts/" + c.id())).body(c);
    }

    @GetMapping("/contracts/{contractId}")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public ContractView get(@PathVariable UUID contractId) {
        return contracts.get(contractId);
    }

    /** Next definite contract (US-PPL-052); the two-definite rule applies. */
    @PostMapping("/contracts/{contractId}/renew")
    @RequiresPermission(PeoplePermissions.CONTRACT_CREATE)
    public ResponseEntity<ContractView> renew(@PathVariable UUID contractId, @Valid @RequestBody TermsRequest req) {
        ContractView c = contracts.renew(contractId, req.toTerms());
        return ResponseEntity.created(URI.create("/api/v1/people/contracts/" + c.id())).body(c);
    }

    /** Indefinite contract following a definite one (US-PPL-052). */
    @PostMapping("/contracts/{contractId}/convert")
    @RequiresPermission(PeoplePermissions.CONTRACT_CREATE)
    public ResponseEntity<ContractView> convert(@PathVariable UUID contractId, @Valid @RequestBody TermsRequest req) {
        ContractView c = contracts.convert(contractId, req.toTerms());
        return ResponseEntity.created(URI.create("/api/v1/people/contracts/" + c.id())).body(c);
    }

    /** Paper signature: sign date + signed scan (US-PPL-056); DRAFT → ACTIVE. */
    @PostMapping(path = "/contracts/{contractId}/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public ContractView sign(@PathVariable UUID contractId, @RequestPart("file") MultipartFile file,
            @RequestParam("signDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signDate,
            @RequestParam("version") int version) throws IOException {
        return contracts.sign(contractId, signDate, file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                version);
    }

    @PostMapping("/contracts/{contractId}/cancel")
    @RequiresPermission(PeoplePermissions.CONTRACT_UPDATE)
    public ContractView cancel(@PathVariable UUID contractId, @Valid @RequestBody VersionRequest req) {
        return contracts.cancel(contractId, req.version());
    }
}
