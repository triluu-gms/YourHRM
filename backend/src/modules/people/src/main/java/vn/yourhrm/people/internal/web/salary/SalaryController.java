package vn.yourhrm.people.internal.web.salary;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.internal.application.salary.SalaryService;
import vn.yourhrm.people.internal.application.salary.SalaryService.ChangeRequest;
import vn.yourhrm.people.internal.application.salary.SalaryService.ChangeResult;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryListItem;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryTerms;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryView;
import vn.yourhrm.people.internal.web.assignment.AsKnown;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Agreed compensation (EP-PPL-05). Reads need {@code hr.compensation.view} in scope (US-PPL-044) and a
 * {@code purpose} on the first view of a session (US-PPL-045); every read is audited. There is deliberately no create /
 * update / delete endpoint (US-PPL-042): changes and corrections are change requests approved through
 * {@code hr.SALARY_CHANGE} and issued by a decision or contract appendix.
 */
@RestController
@RequestMapping("/api/v1/people")
class SalaryController {

    private final SalaryService salaries;
    private final BusinessClock clock;

    SalaryController(SalaryService salaries, BusinessClock clock) {
        this.salaries = salaries;
        this.clock = clock;
    }

    /** {@code inSiSalary} is optional (default false: the allowance is not part of the SI salary). */
    record AllowanceRequest(@NotBlank @Size(max = 64) String componentCode, @NotNull @DecimalMin("0") BigDecimal amount,
            Boolean inSiSalary) {
    }

    record TermsRequest(
            String payBasis,
            String salaryKind,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal baseSalaryAmount,
            @DecimalMin("0") BigDecimal agreedNetAmount,
            List<@NotBlank String> netGuaranteedScope,
            @DecimalMin("0") BigDecimal siSalaryAmount,
            String currencyCode,
            @Size(max = 64) String payScaleCode,
            @Size(max = 64) String payScaleStepCode,
            BigDecimal probationPct,
            @Valid List<AllowanceRequest> allowances) {

        SalaryTerms toTerms() {
            return new SalaryTerms(payBasis, salaryKind, baseSalaryAmount, agreedNetAmount, netGuaranteedScope,
                    siSalaryAmount, currencyCode, payScaleCode, payScaleStepCode, probationPct,
                    allowances == null ? List.of()
                            : allowances.stream().map(a -> new Allowance(a.componentCode(), a.amount(),
                                    Boolean.TRUE.equals(a.inSiSalary())))
                                    .toList());
        }
    }

    record ChangeRequestBody(
            String actionCode,
            @Size(max = 64) String reasonCode,
            LocalDate effectiveDate,
            @NotNull @Valid TermsRequest terms,
            UUID contractAppendixId,
            UUID correctsRecordId,
            @Size(max = 1000) String correctionReason,
            @Size(max = 1000) String bandOverrideReason,
            @Size(max = 200) String signatoryName,
            @Size(max = 200) String signatoryTitle) {
    }

    @GetMapping("/employments/{employmentId}/salary-records")
    @RequiresPermission(PeoplePermissions.COMPENSATION_VIEW)
    public ResponseEntity<SalaryView> asOf(@PathVariable UUID employmentId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "asKnown", required = false) String asKnown,
            @RequestParam(name = "purpose", required = false) String purpose) {
        return salaries.asOf(employmentId, asOf == null ? clock.today() : asOf, AsKnown.exclusiveBound(asKnown), purpose)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/employments/{employmentId}/salary-records/history")
    @RequiresPermission(PeoplePermissions.COMPENSATION_VIEW)
    public List<SalaryView> history(@PathVariable UUID employmentId,
            @RequestParam(name = "purpose", required = false) String purpose) {
        return salaries.history(employmentId, purpose);
    }

    @GetMapping("/employments/{employmentId}/salary-records/{salaryRecordId}")
    @RequiresPermission(PeoplePermissions.COMPENSATION_VIEW)
    public SalaryView get(@PathVariable UUID employmentId, @PathVariable UUID salaryRecordId,
            @RequestParam(name = "purpose", required = false) String purpose) {
        return salaries.get(employmentId, salaryRecordId, purpose);
    }

    @GetMapping("/salary-records")
    @RequiresPermission(PeoplePermissions.COMPENSATION_VIEW)
    public List<SalaryListItem> list(
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "purpose", required = false) String purpose,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return salaries.list(legalEntityId, asOf == null ? clock.today() : asOf, purpose, limit, offset);
    }

    /** Proposes a salary change or a correction of a version; applied only after approval (US-PPL-042). */
    @PostMapping("/employments/{employmentId}/salary-records/change-requests")
    @RequiresPermission(PeoplePermissions.COMPENSATION_UPDATE)
    public ChangeResult requestChange(@PathVariable UUID employmentId, @Valid @RequestBody ChangeRequestBody b) {
        return salaries.requestChange(employmentId, new ChangeRequest(b.actionCode(), b.reasonCode(),
                b.effectiveDate(), b.terms().toTerms(), b.contractAppendixId(), b.correctsRecordId(),
                b.correctionReason(), b.bandOverrideReason(), b.signatoryName(), b.signatoryTitle()));
    }
}
