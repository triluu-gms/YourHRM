package vn.yourhrm.people.internal.web.separation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.separation.SeparationRecords.SeparationView;
import vn.yourhrm.people.internal.application.separation.SeparationService;
import vn.yourhrm.people.internal.application.separation.SeparationService.Resignation;
import vn.yourhrm.people.internal.application.separation.SeparationService.SeparationResult;
import vn.yourhrm.people.internal.application.separation.SeparationService.WithdrawalResult;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * ESS resignation (US-PPL-065, BR-PPL-072): the employee (SELF scope, never a request parameter) submits a proposed
 * last working day, reason and note; the line manager and then HR approve in Core's inbox. Before approval the
 * employee withdraws freely; afterwards the withdrawal goes to HR for consent.
 */
@RestController
@RequestMapping("/api/v1/people/me/resignation")
class EssResignationController {

    private final SeparationService separations;

    EssResignationController(SeparationService separations) {
        this.separations = separations;
    }

    record ResignationRequest(UUID employmentId, @NotNull LocalDate proposedLastWorkingDate,
            @Size(max = 60) String reasonCode, @Size(max = 2000) String note, @Size(max = 254) String personalEmail) {
    }

    record WithdrawRequest(@Size(max = 2000) String note) {
    }

    @GetMapping
    @RequiresPermission(PeoplePermissions.ESS_VIEW)
    public ResponseEntity<SeparationView> mine() {
        return separations.myResignation().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping
    @RequiresPermission(PeoplePermissions.ESS_REQUEST)
    public ResponseEntity<SeparationResult> resign(@Valid @RequestBody ResignationRequest req) {
        return ResponseEntity.status(201).body(separations.resign(new Resignation(req.employmentId(),
                req.proposedLastWorkingDate(), req.reasonCode(), req.note(), req.personalEmail())));
    }

    @PostMapping("/{id}/withdraw")
    @RequiresPermission(PeoplePermissions.ESS_REQUEST)
    public WithdrawalResult withdraw(@PathVariable UUID id, @RequestBody(required = false) @Valid WithdrawRequest req) {
        return separations.withdrawOwn(id, req == null ? null : req.note());
    }
}
