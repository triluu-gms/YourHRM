package vn.yourhrm.people.internal.web.assignment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.AssignmentEvents.CostShare;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.assignment.AssignmentService;
import vn.yourhrm.people.internal.application.assignment.AssignmentService.AssignmentView;
import vn.yourhrm.people.internal.application.assignment.AssignmentService.Attributes;
import vn.yourhrm.people.internal.application.assignment.AssignmentService.ChangeRequest;
import vn.yourhrm.people.internal.application.assignment.AssignmentService.ChangeResult;
import vn.yourhrm.people.internal.application.assignment.ChangeReasonCatalogue;
import vn.yourhrm.people.internal.application.assignment.ChangeReasonCatalogue.ReasonView;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Date-effective assignments of an employment (EP-PPL-04): as-of / as-known reads and the full history (US-PPL-033/037),
 * job-change requests through approval (US-PPL-036, incl. Art. 29 temporary transfers US-PPL-040), corrections
 * (US-PPL-037) and cancellation of future-dated changes (US-PPL-038). Scope: the employment's legal entity, org unit /
 * location of its primary assignment, or SELF / reports; out of scope = 404.
 */
@RestController
@RequestMapping("/api/v1/people")
class AssignmentController {

    private final AssignmentService assignments;
    private final ChangeReasonCatalogue reasons;
    private final BusinessClock clock;

    AssignmentController(AssignmentService assignments, ChangeReasonCatalogue reasons, BusinessClock clock) {
        this.assignments = assignments;
        this.reasons = reasons;
        this.clock = clock;
    }

    record CostShareRequest(@NotNull UUID costCentreId, @NotNull @DecimalMin("0.01") @DecimalMax("100") BigDecimal pct) {
    }

    /** No pay group and no work schedule (BR-PPL-031): Payroll and T&A own them. */
    record AttributesRequest(
            @NotNull UUID orgUnitId,
            UUID positionId,
            UUID jobTitleId,
            UUID gradeId,
            UUID managerEmploymentId,
            UUID locationId,
            UUID costCentreId,
            @Valid List<CostShareRequest> costSplit,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") BigDecimal fte) {

        Attributes toAttributes() {
            return new Attributes(orgUnitId, positionId, jobTitleId, gradeId, managerEmploymentId, locationId,
                    costCentreId, costSplit == null ? List.of()
                            : costSplit.stream().map(s -> new CostShare(s.costCentreId(), s.pct())).toList(),
                    fte);
        }
    }

    record ChangeRequestBody(
            @NotBlank @Size(max = 64) String reasonCode,
            @NotNull LocalDate effectiveDate,
            LocalDate endDate,
            @NotNull @Valid AttributesRequest attributes,
            UUID contractAppendixId,
            UUID consentDocumentId,
            LocalDate noticeDate,
            @Size(max = 200) String signatoryName,
            @Size(max = 200) String signatoryTitle,
            @Size(max = 2000) String comment) {
    }

    record CorrectionBody(@NotBlank @Size(max = 1000) String reason, @NotNull @Valid AttributesRequest attributes) {
    }

    record CancellationBody(@NotBlank @Size(max = 1000) String reason) {
    }

    @GetMapping("/employments/{employmentId}/assignments")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<AssignmentView> asOf(@PathVariable UUID employmentId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "asKnown", required = false) String asKnown) {
        return assignments.asOf(employmentId, asOf == null ? clock.today() : asOf, AsKnown.exclusiveBound(asKnown));
    }

    @GetMapping("/employments/{employmentId}/assignments/history")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<AssignmentView> history(@PathVariable UUID employmentId) {
        return assignments.history(employmentId);
    }

    @GetMapping("/employments/{employmentId}/assignments/{assignmentId}")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public AssignmentView get(@PathVariable UUID employmentId, @PathVariable UUID assignmentId) {
        return assignments.get(employmentId, assignmentId);
    }

    @PostMapping("/employments/{employmentId}/assignments/changes")
    @RequiresPermission(PeoplePermissions.ASSIGNMENT_UPDATE)
    public ChangeResult requestChange(@PathVariable UUID employmentId, @Valid @RequestBody ChangeRequestBody b) {
        return assignments.requestChange(employmentId, new ChangeRequest(b.reasonCode(), b.effectiveDate(),
                b.endDate(), b.attributes().toAttributes(), b.contractAppendixId(), b.consentDocumentId(),
                b.noticeDate(), b.signatoryName(), b.signatoryTitle(), b.comment()));
    }

    @PostMapping("/employments/{employmentId}/assignments/{assignmentId}/corrections")
    @RequiresPermission(PeoplePermissions.ASSIGNMENT_UPDATE)
    public AssignmentView correct(@PathVariable UUID employmentId, @PathVariable UUID assignmentId,
            @Valid @RequestBody CorrectionBody b) {
        return assignments.correct(employmentId, assignmentId, b.reason(), b.attributes().toAttributes());
    }

    @PostMapping("/employments/{employmentId}/assignments/{assignmentId}/cancellation")
    @RequiresPermission(PeoplePermissions.ASSIGNMENT_UPDATE)
    public AssignmentView cancel(@PathVariable UUID employmentId, @PathVariable UUID assignmentId,
            @Valid @RequestBody CancellationBody b) {
        return assignments.cancelFutureChange(employmentId, assignmentId, b.reason());
    }

    /** The tenant's change reasons with their approval process and document kind (US-PPL-035). */
    @GetMapping("/assignment-change-reasons")
    @RequiresPermission(PeoplePermissions.ASSIGNMENT_UPDATE)
    public List<ReasonView> reasons() {
        return reasons.list(clock.today());
    }
}
