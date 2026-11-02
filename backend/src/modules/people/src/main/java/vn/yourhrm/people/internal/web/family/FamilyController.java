package vn.yourhrm.people.internal.web.family;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
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
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.family.DependantService;
import vn.yourhrm.people.internal.application.family.DependantService.DependantView;
import vn.yourhrm.people.internal.application.family.DependantService.EligibilityResult;
import vn.yourhrm.people.internal.application.family.DependantService.Registration;
import vn.yourhrm.people.internal.application.family.FamilyEvidenceService;
import vn.yourhrm.people.internal.application.family.FamilyMemberService;
import vn.yourhrm.people.internal.application.family.FamilyMemberService.Attributes;
import vn.yourhrm.people.internal.application.family.FamilyMemberService.FamilyMemberView;
import vn.yourhrm.people.internal.application.family.FamilyMemberService.Identifiers;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Family members and PIT dependants of an employee-person, HR side (BR-PPL-016/017, US-PPL-027/028/029). Scope: the
 * person is in scope when one of their employments is. Months are {@code yyyy-MM}.
 */
@RestController
@RequestMapping("/api/v1/people/persons/{personId}")
class FamilyController {

    private final FamilyMemberService members;
    private final DependantService dependants;
    private final FamilyEvidenceService evidence;

    FamilyController(FamilyMemberService members, DependantService dependants, FamilyEvidenceService evidence) {
        this.members = members;
        this.dependants = dependants;
        this.evidence = evidence;
    }

    /**
     * {@code nationalId} / {@code taxCode}: null keeps the stored value on update, blank clears it.
     * {@code averageMonthlyIncome} (optional, not stored) derives the income flag from the legal threshold.
     */
    record FamilyMemberRequest(
            @NotBlank @Size(max = 64) String relationshipCode,
            @NotBlank @Size(max = 200) String fullName,
            @PastOrPresent LocalDate birthDate,
            @Size(max = 32) String nationalId,
            @Size(max = 20) String taxCode,
            Boolean hasIncomeAboveThreshold,
            @PositiveOrZero BigDecimal averageMonthlyIncome,
            boolean disabled,
            boolean deceased,
            Integer version) {

        Attributes toAttributes() {
            return new Attributes(relationshipCode, fullName, birthDate, nationalId, taxCode, hasIncomeAboveThreshold,
                    averageMonthlyIncome, disabled, deceased);
        }
    }

    record VersionRequest(@NotNull Integer version) {
    }

    record RegistrationRequest(
            @NotNull UUID familyMemberId,
            UUID employmentId,
            @NotNull YearMonth fromMonth,
            YearMonth toMonth,
            @NotBlank String reasonCode,
            List<UUID> evidenceDocumentIds,
            @Size(max = 100) String taxRegistrationRef) {

        Registration toRegistration() {
            return new Registration(familyMemberId, employmentId, fromMonth.atDay(1),
                    toMonth == null ? null : toMonth.atDay(1), reasonCode, evidenceDocumentIds, taxRegistrationRef);
        }
    }

    record EndRequest(@NotNull YearMonth toMonth, @NotNull Integer version, List<UUID> evidenceDocumentIds) {
    }

    // ------------------------------------------------------------------------------------------- family members

    @GetMapping("/family-members")
    @RequiresPermission(PeoplePermissions.DEPENDANT_VIEW)
    public List<FamilyMemberView> list(@PathVariable UUID personId) {
        return members.list(personId);
    }

    @GetMapping("/family-members/{id}")
    @RequiresPermission(PeoplePermissions.DEPENDANT_VIEW)
    public FamilyMemberView get(@PathVariable UUID personId, @PathVariable UUID id) {
        return members.get(personId, id);
    }

    /** Unmasked identity number and tax code; needs {@code hr.person.view_sensitive} too; audited. */
    @GetMapping("/family-members/{id}/identifiers")
    @RequiresPermission(PeoplePermissions.DEPENDANT_VIEW)
    public Identifiers identifiers(@PathVariable UUID personId, @PathVariable UUID id) {
        return members.identifiers(personId, id);
    }

    @PostMapping("/family-members")
    @RequiresPermission(PeoplePermissions.DEPENDANT_UPDATE)
    public ResponseEntity<FamilyMemberView> create(@PathVariable UUID personId,
            @Valid @RequestBody FamilyMemberRequest req) {
        FamilyMemberView created = members.create(personId, req.toAttributes());
        return ResponseEntity.created(URI.create("/api/v1/people/persons/" + personId + "/family-members/"
                + created.id())).body(created);
    }

    @PutMapping("/family-members/{id}")
    @RequiresPermission(PeoplePermissions.DEPENDANT_UPDATE)
    public FamilyMemberView update(@PathVariable UUID personId, @PathVariable UUID id,
            @Valid @RequestBody FamilyMemberRequest req) {
        if (req.version() == null) {
            throw vn.yourhrm.shared.common.error.ApiException.unprocessable("version-required",
                    "The version read is required to update");
        }
        return members.update(personId, id, req.toAttributes(), req.version());
    }

    @DeleteMapping("/family-members/{id}")
    @RequiresPermission(PeoplePermissions.DEPENDANT_UPDATE)
    public ResponseEntity<Void> delete(@PathVariable UUID personId, @PathVariable UUID id,
            @RequestParam int version) {
        members.delete(personId, id, version);
        return ResponseEntity.noContent().build();
    }

    /** Uploads an evidence document of the family member (birth / school / disability certificate ...). */
    @PostMapping(value = "/family-members/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PeoplePermissions.DEPENDANT_UPDATE)
    public ResponseEntity<Documents.DocumentView> upload(@PathVariable UUID personId, @PathVariable UUID id,
            @RequestParam("file") MultipartFile file, @RequestParam(name = "title", required = false) String title)
            throws IOException {
        return ResponseEntity.status(201).body(evidence.upload(personId, id, false, title, file.getOriginalFilename(),
                file.getContentType(), file.getBytes()));
    }

    // ------------------------------------------------------------------------------------------- dependants

    @GetMapping("/dependants")
    @RequiresPermission(PeoplePermissions.DEPENDANT_VIEW)
    public List<DependantView> dependants(@PathVariable UUID personId) {
        return dependants.list(personId);
    }

    /** Eligibility and claimant check without saving (errors shown before submit). */
    @PostMapping("/dependants/validate")
    @RequiresPermission(PeoplePermissions.DEPENDANT_UPDATE)
    public EligibilityResult validate(@PathVariable UUID personId, @Valid @RequestBody RegistrationRequest req) {
        return dependants.validate(personId, req.toRegistration());
    }

    /** Registers a dependant claim: PENDING until approved through {@code hr.DEPENDANT_CHANGE}. */
    @PostMapping("/dependants")
    @RequiresPermission(PeoplePermissions.DEPENDANT_UPDATE)
    public ResponseEntity<DependantView> register(@PathVariable UUID personId,
            @Valid @RequestBody RegistrationRequest req) {
        DependantView created = dependants.register(personId, req.toRegistration());
        return ResponseEntity.created(URI.create("/api/v1/people/persons/" + personId + "/dependants/" + created.id()))
                .body(created);
    }

    /** Requests the end of a claim (last month of deduction); applied on approval. */
    @PostMapping("/dependants/{id}/end")
    @RequiresPermission(PeoplePermissions.DEPENDANT_UPDATE)
    public DependantView end(@PathVariable UUID personId, @PathVariable UUID id, @Valid @RequestBody EndRequest req) {
        return dependants.requestEnd(personId, id, req.toMonth().atDay(1), req.version(), req.evidenceDocumentIds());
    }
}
