package vn.yourhrm.people.internal.web.employment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.employment.ConcurrentEmploymentReport;
import vn.yourhrm.people.internal.application.employment.ConcurrentEmploymentReport.Report;
import vn.yourhrm.people.internal.application.employment.EmploymentService;
import vn.yourhrm.people.internal.application.employment.EmploymentService.Attributes;
import vn.yourhrm.people.internal.application.employment.EmploymentService.EmploymentView;
import vn.yourhrm.people.internal.application.employment.EmploymentService.StatusView;
import vn.yourhrm.people.internal.application.employment.InsuranceParticipationService;
import vn.yourhrm.people.internal.application.employment.InsuranceParticipationService.OverrideRequest;
import vn.yourhrm.people.internal.application.employment.InsuranceParticipationService.ParticipationView;
import vn.yourhrm.people.internal.application.employment.PrimaryEmploymentService;
import vn.yourhrm.people.internal.application.employment.PrimaryEmploymentService.ChangeRequest;
import vn.yourhrm.people.internal.application.employment.PrimaryEmploymentService.ChangeResult;
import vn.yourhrm.people.internal.application.employment.PrimaryEmploymentService.PrimaryView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Employment records (US-PPL-017), PIT primary employment (US-PPL-022), insurance participation (US-PPL-023) and the
 * concurrent-employment exception report (US-PPL-025). Reads need {@code hr.employee.view} in scope (404 otherwise).
 */
@RestController
@RequestMapping("/api/v1/people")
class EmploymentController {

    private final EmploymentService employments;
    private final PrimaryEmploymentService primaries;
    private final InsuranceParticipationService insurance;
    private final ConcurrentEmploymentReport report;

    EmploymentController(EmploymentService employments, PrimaryEmploymentService primaries,
            InsuranceParticipationService insurance, ConcurrentEmploymentReport report) {
        this.employments = employments;
        this.primaries = primaries;
        this.insurance = insurance;
        this.report = report;
    }

    @GetMapping("/persons/{personId}/employments")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    List<EmploymentView> ofPerson(@PathVariable UUID personId) {
        return employments.ofPerson(personId);
    }

    @GetMapping("/employments/{employmentId}")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    EmploymentView get(@PathVariable UUID employmentId) {
        return employments.get(employmentId);
    }

    @GetMapping("/employments/{employmentId}/status")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    StatusView status(@PathVariable UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return employments.status(employmentId, asOf);
    }

    record UpdateBody(@NotNull Integer version, @NotBlank @Size(max = 64) String workerCategoryCode,
            @Size(max = 64) String employeeCategoryCode, @Size(max = 64) String workingRegimeCode,
            LocalDate originalHireDate, LocalDate seniorityDate, LocalDate probationEndDate) {
    }

    @PutMapping("/employments/{employmentId}")
    @RequiresPermission(PeoplePermissions.EMPLOYMENT_UPDATE)
    EmploymentView update(@PathVariable UUID employmentId, @Valid @RequestBody UpdateBody b) {
        return employments.update(employmentId, b.version(), new Attributes(b.workerCategoryCode(),
                b.employeeCategoryCode(), b.workingRegimeCode(), b.originalHireDate(), b.seniorityDate(),
                b.probationEndDate()));
    }

    // ------------------------------------------------------------------------------------- primary employment

    @GetMapping("/persons/{personId}/primary-employment")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    PrimaryView primary(@PathVariable UUID personId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return primaries.view(personId, asOf);
    }

    record PrimaryChangeBody(@NotNull UUID employmentId, @NotNull LocalDate validFrom,
            @Size(max = 64) String reasonCode, @Size(max = 1000) String comment) {
    }

    @PostMapping("/persons/{personId}/primary-employment/change-requests")
    @RequiresPermission(PeoplePermissions.EMPLOYMENT_UPDATE)
    ChangeResult requestPrimaryChange(@PathVariable UUID personId, @Valid @RequestBody PrimaryChangeBody b) {
        return primaries.requestChange(personId, new ChangeRequest(b.employmentId(), b.validFrom(), b.reasonCode(),
                b.comment()));
    }

    // ---------------------------------------------------------------------------------- insurance participation

    @GetMapping("/employments/{employmentId}/insurance-participation")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    List<ParticipationView> participation(@PathVariable UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return insurance.list(employmentId, asOf);
    }

    record OverrideBody(@NotBlank String insuranceType, @NotNull LocalDate validFrom, LocalDate validTo,
            @NotNull Boolean participating, @Size(max = 64) String exemptionReasonCode,
            @NotBlank @Size(max = 1000) String overrideReason) {
    }

    @PostMapping("/employments/{employmentId}/insurance-participation/overrides")
    @RequiresPermission(PeoplePermissions.EMPLOYMENT_UPDATE)
    List<ParticipationView> override(@PathVariable UUID employmentId, @Valid @RequestBody OverrideBody b) {
        return insurance.override(employmentId, new OverrideRequest(b.insuranceType(), b.validFrom(), b.validTo(),
                b.participating(), b.exemptionReasonCode(), b.overrideReason()));
    }

    @PostMapping("/employments/{employmentId}/insurance-participation/rederive")
    @RequiresPermission(PeoplePermissions.EMPLOYMENT_UPDATE)
    List<ParticipationView> rederive(@PathVariable UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return insurance.rederive(employmentId, from);
    }

    // ----------------------------------------------------------------------------------------------- report

    @GetMapping("/reports/concurrent-employment-exceptions")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    Report exceptions(@RequestParam UUID legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return report.run(legalEntityId, asOf);
    }
}
