package vn.yourhrm.people.internal.web.integration;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.separation.SeparationService;
import vn.yourhrm.people.internal.application.separation.SeparationService.SeparationResult;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Integration fix F5 (US-PPL-052, BR-PPL-054): the "terminate at expiry" action of a row of the contract expiry list
 * ({@code GET /api/v1/people/contracts/expiring}) addressed by the contract, as the list's action link. It opens a
 * DRAFT separation with ground CONTRACT_EXPIRY through {@link SeparationService#openForContractExpiry} (the same
 * service as {@code POST /api/v1/people/separations/contract-expiry}); the scope check on the employment and every
 * separation rule stay in the separation stream.
 */
@RestController
@RequestMapping("/api/v1/people/contracts")
class ContractExpiryActionController {

    record TerminateAtExpiryRequest(LocalDate noticeDate) {
    }

    private final SeparationService separations;

    ContractExpiryActionController(SeparationService separations) {
        this.separations = separations;
    }

    @PostMapping("/{contractId}/terminate-at-expiry")
    @RequiresPermission(PeoplePermissions.SEPARATION_CREATE)
    public ResponseEntity<SeparationResult> terminateAtExpiry(@PathVariable UUID contractId,
            @RequestBody(required = false) TerminateAtExpiryRequest req) {
        return ResponseEntity.status(201).body(separations.openForContractExpiry(contractId,
                req == null ? null : req.noticeDate()));
    }
}
