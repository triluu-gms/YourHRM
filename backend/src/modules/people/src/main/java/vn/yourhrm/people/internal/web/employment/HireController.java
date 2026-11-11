package vn.yourhrm.people.internal.web.employment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.internal.application.assignment.AssignmentService;
import vn.yourhrm.people.internal.application.contract.ContractTerms;
import vn.yourhrm.people.internal.application.employment.EmploymentService;
import vn.yourhrm.people.internal.application.employment.EmploymentService.EmploymentView;
import vn.yourhrm.people.internal.application.employment.HireService;
import vn.yourhrm.people.internal.application.employment.HireService.CancelRequest;
import vn.yourhrm.people.internal.application.employment.HireService.HireRequest;
import vn.yourhrm.people.internal.application.employment.HireService.HireResult;
import vn.yourhrm.people.internal.application.employment.HireService.MatchView;
import vn.yourhrm.people.internal.application.employment.HireService.PersonInput;
import vn.yourhrm.people.internal.application.person.PersonService;
import vn.yourhrm.people.internal.application.person.PersonService.MatchQuery;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryTerms;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Direct-hire wizard (US-PPL-018/020/021): person match, submission (employment + first assignment + contract +
 * salary in one transaction, approved through {@code hr.HIRE}) and cancellation of a pre-hire.
 */
@RestController
@RequestMapping("/api/v1/people/hires")
class HireController {

    private final HireService hires;
    private final EmploymentService employments;

    HireController(HireService hires, EmploymentService employments) {
        this.hires = hires;
        this.employments = employments;
    }

    record MatchBody(@Size(max = 16) String nationalIdType, @Size(max = 32) String nationalId,
            @Size(max = 3) String nationalityCode, @Size(max = 32) String passportNo, @Size(max = 32) String taxCode,
            @Size(max = 32) String siNumber, UUID legalEntityId, LocalDate hireDate) {
    }

    /** Step 1: blocking candidates (with their employments and options NEW / REHIRE / CONCURRENT / ASSIGNMENT_CHANGE). */
    @PostMapping("/match")
    @RequiresPermission(PeoplePermissions.EMPLOYMENT_CREATE)
    MatchView match(@Valid @RequestBody MatchBody b) {
        return hires.match(new MatchQuery(b.nationalIdType(), b.nationalId(), b.nationalityCode(), b.passportNo(),
                b.taxCode(), b.siNumber()), b.legalEntityId(), b.hireDate());
    }

    record PersonBody(@NotBlank @Size(max = 100) String familyName, @Size(max = 100) String middleName,
            @NotBlank @Size(max = 100) String givenName, @Size(max = 200) String latinName,
            @Size(max = 100) String preferredName, LocalDate birthDate, @Size(max = 8) String genderCode,
            @Size(max = 3) String nationalityCode, @Size(max = 32) String hiFacilityCode,
            @Size(max = 32) String siBookCustody, @Size(max = 16) String nationalIdType,
            @Size(max = 32) String nationalId, @Size(max = 32) String taxCode, @Size(max = 32) String siNumber,
            @Size(max = 32) String hiCardNo, Boolean acknowledgeWarnings) {

        PersonInput toInput() {
            return new PersonInput(new PersonService.PersonData(familyName, middleName, givenName, latinName,
                    preferredName, birthDate, genderCode, nationalityCode, hiFacilityCode, siBookCustody),
                    new PersonService.Identifiers(nationalIdType, nationalId, taxCode, null, siNumber, hiCardNo,
                            null, null), Boolean.TRUE.equals(acknowledgeWarnings));
        }
    }

    record AssignmentBody(@NotNull UUID orgUnitId, UUID positionId, UUID jobTitleId, UUID gradeId,
            UUID managerEmploymentId, UUID locationId, UUID costCentreId, BigDecimal fte) {

        AssignmentService.Attributes toAttributes() {
            return new AssignmentService.Attributes(orgUnitId, positionId, jobTitleId, gradeId, managerEmploymentId,
                    locationId, costCentreId, null, fte);
        }
    }

    record ContractBody(@NotBlank String contractTypeCode, LocalDate startDate, LocalDate endDate,
            Integer probationDays, @Size(max = 200) String signatoryName, @Size(max = 200) String signatoryTitle,
            @Size(max = 64) String probationLevelCode, BigDecimal probationPayPct, UUID jobTitleId,
            @Size(max = 64) String definiteExceptionCode, @Size(max = 1000) String definiteExceptionNote) {

        ContractTerms toTerms() {
            return new ContractTerms(contractTypeCode, startDate, endDate, probationDays, signatoryName,
                    signatoryTitle, probationLevelCode, probationPayPct, jobTitleId, definiteExceptionCode,
                    definiteExceptionNote);
        }
    }

    record AllowanceBody(@NotBlank @Size(max = 64) String componentCode, @NotNull @DecimalMin("0") BigDecimal amount,
            Boolean inSiSalary) {
    }

    record SalaryBody(String payBasis, String salaryKind,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal baseSalaryAmount,
            @DecimalMin("0") BigDecimal agreedNetAmount, List<@NotBlank String> netGuaranteedScope,
            @DecimalMin("0") BigDecimal siSalaryAmount, String currencyCode, @Size(max = 64) String payScaleCode,
            @Size(max = 64) String payScaleStepCode, BigDecimal probationPct, @Valid List<AllowanceBody> allowances) {

        SalaryTerms toTerms() {
            return new SalaryTerms(payBasis, salaryKind, baseSalaryAmount, agreedNetAmount, netGuaranteedScope,
                    siSalaryAmount, currencyCode, payScaleCode, payScaleStepCode, probationPct,
                    allowances == null ? List.of() : allowances.stream().map(a -> new Allowance(a.componentCode(),
                            a.amount(), Boolean.TRUE.equals(a.inSiSalary()))).toList());
        }
    }

    record HireBody(UUID personId, @Valid PersonBody person, @Size(max = 16) String hireType,
            @NotNull UUID legalEntityId, @NotNull LocalDate hireDate, LocalDate originalHireDate,
            LocalDate seniorityDate, LocalDate probationEndDate, @NotBlank @Size(max = 64) String workerCategoryCode,
            @Size(max = 64) String employeeCategoryCode, @Size(max = 64) String workingRegimeCode,
            @NotNull @Valid AssignmentBody assignment, @NotNull @Valid ContractBody contract,
            @NotNull @Valid SalaryBody salary, @Size(max = 200) String signatoryName,
            @Size(max = 200) String signatoryTitle) {
    }

    @PostMapping
    @RequiresPermission(PeoplePermissions.EMPLOYMENT_CREATE)
    HireResult hire(@Valid @RequestBody HireBody b) {
        return hires.hire(new HireRequest(b.personId(), b.person() == null ? null : b.person().toInput(),
                b.hireType(), b.legalEntityId(), b.hireDate(), b.originalHireDate(), b.seniorityDate(),
                b.probationEndDate(), b.workerCategoryCode(), b.employeeCategoryCode(), b.workingRegimeCode(),
                b.assignment().toAttributes(), b.contract().toTerms(), b.salary().toTerms(), b.signatoryName(),
                b.signatoryTitle()));
    }

    record CancelBody(@Size(max = 64) String reasonCode, @NotBlank @Size(max = 1000) String reason, Integer version) {
    }

    /** US-PPL-020: cancels a pre-hire before its start (no-show). */
    @PostMapping("/{employmentId}/cancel")
    @RequiresPermission(PeoplePermissions.EMPLOYMENT_CREATE)
    EmploymentView cancel(@PathVariable UUID employmentId, @Valid @RequestBody CancelBody b) {
        hires.cancel(employmentId, new CancelRequest(b.reasonCode(), b.reason(), b.version()));
        return employments.describe(employmentId);
    }
}
