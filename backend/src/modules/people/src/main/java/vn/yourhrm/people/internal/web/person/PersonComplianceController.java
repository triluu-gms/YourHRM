package vn.yourhrm.people.internal.web.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.person.SpecialStatusService;
import vn.yourhrm.people.internal.application.person.SpecialStatusService.StatusView;
import vn.yourhrm.people.internal.application.person.TaxResidencyService;
import vn.yourhrm.people.internal.application.person.TaxResidencyService.PresenceView;
import vn.yourhrm.people.internal.application.person.TaxResidencyService.ResidencyData;
import vn.yourhrm.people.internal.application.person.TaxResidencyService.ResidencyView;
import vn.yourhrm.people.internal.application.person.TaxResidencyService.Submitted;
import vn.yourhrm.people.internal.application.person.WorkPermitService;
import vn.yourhrm.people.internal.application.person.WorkPermitService.Expiring;
import vn.yourhrm.people.internal.application.person.WorkPermitService.PermitData;
import vn.yourhrm.people.internal.application.person.WorkPermitService.PermitView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Tax residency + VN presence (US-PPL-013/014), special statuses (US-PPL-012) and work permits (US-PPL-011) of a
 * person. Payroll's residency proposals are reviewed under {@code /residency-proposals}.
 */
@RestController
@RequestMapping("/api/v1/people/persons")
class PersonComplianceController {

    private final TaxResidencyService residency;
    private final SpecialStatusService statuses;
    private final WorkPermitService permits;

    PersonComplianceController(TaxResidencyService residency, SpecialStatusService statuses, WorkPermitService permits) {
        this.residency = residency;
        this.statuses = statuses;
        this.permits = permits;
    }

    record ResidencyRequest(@NotBlank String status, @NotBlank String basis, @NotNull LocalDate validFrom,
            LocalDate validTo, LocalDate arrivalDate, LocalDate departureDate, Integer taxYear, Integer daysInVn) {
    }

    record PresenceRequest(@NotNull LocalDate entryDate, LocalDate exitDate) {
    }

    record ChangePresenceRequest(@NotNull Integer version, @NotNull LocalDate entryDate, LocalDate exitDate) {
    }

    record VersionRequest(@NotNull Integer version) {
    }

    record RejectRequest(@NotNull Integer version, @NotBlank @Size(max = 500) String reason) {
    }

    record AttachRequest(@NotNull UUID documentId) {
    }

    record StatusRequest(@NotBlank String statusType, UUID employmentId, @NotNull LocalDate validFrom, LocalDate validTo,
            @Size(max = 32) String classificationCode, Map<String, Object> details,
            @Size(max = 500) String overrideReason) {
    }

    record ChangeStatusRequest(@NotNull Integer version, LocalDate validTo, @Size(max = 32) String classificationCode,
            Map<String, Object> details, @Size(max = 500) String overrideReason) {
    }

    record PermitRequest(UUID employmentId, @NotBlank String permitType, @Size(max = 64) String permitNo,
            @Size(max = 200) String jobTitleOnPermit, @Size(max = 500) String workLocationText,
            @Size(max = 200) String issuingAuthority, LocalDate issueDate, @NotNull LocalDate validFrom,
            LocalDate validTo, UUID renewalOfId, String status) {

        PermitData toData() {
            return new PermitData(employmentId, permitType, permitNo, jobTitleOnPermit, workLocationText,
                    issuingAuthority, issueDate, validFrom, validTo, renewalOfId, status);
        }
    }

    record ChangePermitRequest(@NotNull Integer version, @NotNull @Valid PermitRequest permit) {
    }

    // ---- tax residency (US-PPL-013) --------------------------------------------------------------------------------

    @GetMapping("/{personId}/tax-residencies")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<ResidencyView> taxResidencies(@PathVariable UUID personId) {
        return residency.list(personId);
    }

    /** Submits a status for HR approval (process type hr.TAX_RESIDENCY). */
    @PostMapping("/{personId}/tax-residencies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PeoplePermissions.TAX_RESIDENCY_UPDATE)
    public Submitted submitTaxResidency(@PathVariable UUID personId, @Valid @RequestBody ResidencyRequest r) {
        return residency.submit(personId, new ResidencyData(r.status(), r.basis(), r.validFrom(), r.validTo(),
                r.arrivalDate(), r.departureDate(), r.taxYear(), r.daysInVn()));
    }

    @PutMapping("/{personId}/tax-residencies/{residencyId}/evidence")
    @RequiresPermission(PeoplePermissions.TAX_RESIDENCY_UPDATE)
    public ResidencyView attachResidencyEvidence(@PathVariable UUID personId, @PathVariable UUID residencyId,
            @Valid @RequestBody AttachRequest req) {
        return residency.attachEvidence(personId, residencyId, req.documentId());
    }

    // ---- Payroll proposals (US-PPL-014) ----------------------------------------------------------------------------

    @GetMapping("/residency-proposals")
    @RequiresPermission(PeoplePermissions.TAX_RESIDENCY_UPDATE)
    public List<ResidencyView> residencyProposals() {
        return residency.proposals();
    }

    @PostMapping("/residency-proposals/{proposalId}/approve")
    @RequiresPermission(PeoplePermissions.TAX_RESIDENCY_UPDATE)
    public ResidencyView approveProposal(@PathVariable UUID proposalId, @Valid @RequestBody VersionRequest req) {
        return residency.approveProposal(proposalId, req.version());
    }

    @PostMapping("/residency-proposals/{proposalId}/reject")
    @RequiresPermission(PeoplePermissions.TAX_RESIDENCY_UPDATE)
    public ResidencyView rejectProposal(@PathVariable UUID proposalId, @Valid @RequestBody RejectRequest req) {
        return residency.rejectProposal(proposalId, req.version(), req.reason());
    }

    // ---- presence in VN --------------------------------------------------------------------------------------------

    @GetMapping("/{personId}/vn-presence-periods")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<PresenceView> presence(@PathVariable UUID personId) {
        return residency.presence(personId);
    }

    @PostMapping("/{personId}/vn-presence-periods")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PeoplePermissions.TAX_RESIDENCY_UPDATE)
    public PresenceView addPresence(@PathVariable UUID personId, @Valid @RequestBody PresenceRequest req) {
        return residency.addPresence(personId, req.entryDate(), req.exitDate());
    }

    @PutMapping("/{personId}/vn-presence-periods/{periodId}")
    @RequiresPermission(PeoplePermissions.TAX_RESIDENCY_UPDATE)
    public PresenceView changePresence(@PathVariable UUID personId, @PathVariable UUID periodId,
            @Valid @RequestBody ChangePresenceRequest req) {
        return residency.changePresence(personId, periodId, req.version(), req.entryDate(), req.exitDate());
    }

    // ---- special statuses (US-PPL-012) -----------------------------------------------------------------------------

    @GetMapping("/{personId}/special-statuses")
    @RequiresPermission(PeoplePermissions.SPECIAL_STATUS_VIEW)
    public List<StatusView> specialStatuses(@PathVariable UUID personId,
            @RequestParam(name = "includeCancelled", defaultValue = "false") boolean includeCancelled) {
        return statuses.list(personId, includeCancelled);
    }

    @PostMapping("/{personId}/special-statuses")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PeoplePermissions.SPECIAL_STATUS_UPDATE)
    public StatusView addSpecialStatus(@PathVariable UUID personId, @Valid @RequestBody StatusRequest r) {
        return statuses.create(personId, new SpecialStatusService.Create(r.statusType(), r.employmentId(), r.validFrom(),
                r.validTo(), r.classificationCode(), r.details(), r.overrideReason()));
    }

    @PutMapping("/{personId}/special-statuses/{statusId}")
    @RequiresPermission(PeoplePermissions.SPECIAL_STATUS_UPDATE)
    public StatusView changeSpecialStatus(@PathVariable UUID personId, @PathVariable UUID statusId,
            @Valid @RequestBody ChangeStatusRequest r) {
        return statuses.change(personId, statusId, r.version(), new SpecialStatusService.Change(r.validTo(),
                r.classificationCode(), r.details(), r.overrideReason()));
    }

    @PostMapping("/{personId}/special-statuses/{statusId}/cancel")
    @RequiresPermission(PeoplePermissions.SPECIAL_STATUS_UPDATE)
    public StatusView cancelSpecialStatus(@PathVariable UUID personId, @PathVariable UUID statusId,
            @Valid @RequestBody VersionRequest req) {
        return statuses.cancel(personId, statusId, req.version());
    }

    @PutMapping("/{personId}/special-statuses/{statusId}/evidence")
    @RequiresPermission(PeoplePermissions.SPECIAL_STATUS_UPDATE)
    public StatusView attachStatusEvidence(@PathVariable UUID personId, @PathVariable UUID statusId,
            @Valid @RequestBody AttachRequest req) {
        return statuses.attachEvidence(personId, statusId, req.documentId());
    }

    // ---- work permits (US-PPL-011) ---------------------------------------------------------------------------------

    @GetMapping("/{personId}/work-permits")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<PermitView> workPermits(@PathVariable UUID personId) {
        return permits.list(personId);
    }

    @PostMapping("/{personId}/work-permits")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PeoplePermissions.WORK_PERMIT_UPDATE)
    public PermitView addWorkPermit(@PathVariable UUID personId, @Valid @RequestBody PermitRequest req) {
        return permits.add(personId, req.toData());
    }

    @PutMapping("/{personId}/work-permits/{permitId}")
    @RequiresPermission(PeoplePermissions.WORK_PERMIT_UPDATE)
    public PermitView changeWorkPermit(@PathVariable UUID personId, @PathVariable UUID permitId,
            @Valid @RequestBody ChangePermitRequest req) {
        return permits.change(personId, permitId, req.version(), req.permit().toData());
    }

    @PutMapping("/{personId}/work-permits/{permitId}/scan")
    @RequiresPermission(PeoplePermissions.WORK_PERMIT_UPDATE)
    public PermitView attachPermitScan(@PathVariable UUID personId, @PathVariable UUID permitId,
            @Valid @RequestBody AttachRequest req) {
        return permits.attachScan(personId, permitId, req.documentId());
    }

    /** Report: ACTIVE permits of employments in scope whose last valid day falls within {@code withinDays}. */
    @GetMapping("/expiring-work-permits")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<Expiring> expiringWorkPermits(
            @RequestParam(name = "withinDays", defaultValue = "60") @Min(0) @Max(3660) int withinDays) {
        return permits.expiring(withinDays);
    }
}
