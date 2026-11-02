package vn.yourhrm.people.internal.web.family;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import vn.yourhrm.people.internal.application.family.FamilyEvidenceService;
import vn.yourhrm.people.internal.application.family.FamilyMemberService;
import vn.yourhrm.people.internal.application.family.FamilyMemberService.FamilyMemberView;
import vn.yourhrm.people.internal.application.family.SelfServiceScope;
import vn.yourhrm.people.internal.web.family.FamilyController.EndRequest;
import vn.yourhrm.people.internal.web.family.FamilyController.FamilyMemberRequest;
import vn.yourhrm.people.internal.web.family.FamilyController.RegistrationRequest;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * ESS "My family and dependants" (US-PPL-074, BR-PPL-120): the employee sees their own family members and claims,
 * adds a family member, uploads evidence, checks eligibility before submitting and requests a registration or an end;
 * requests go into HR approval ({@code hr.DEPENDANT_CHANGE}). The employee is the caller (SELF scope), never a
 * request parameter; a claim's employment is always the one the rules determine.
 */
@RestController
@RequestMapping("/api/v1/people/me")
class EssFamilyController {

    private final SelfServiceScope self;
    private final FamilyMemberService members;
    private final DependantService dependants;
    private final FamilyEvidenceService evidence;

    EssFamilyController(SelfServiceScope self, FamilyMemberService members, DependantService dependants,
            FamilyEvidenceService evidence) {
        this.self = self;
        this.members = members;
        this.dependants = dependants;
        this.evidence = evidence;
    }

    @GetMapping("/family-members")
    @RequiresPermission(PeoplePermissions.ESS_VIEW)
    public List<FamilyMemberView> familyMembers() {
        return members.listOwn(self.require(PeoplePermissions.ESS_VIEW).personId());
    }

    @PostMapping("/family-members")
    @RequiresPermission(PeoplePermissions.ESS_REQUEST)
    public ResponseEntity<FamilyMemberView> addFamilyMember(@Valid @RequestBody FamilyMemberRequest req) {
        return ResponseEntity.status(201).body(members.createOwn(self.require(PeoplePermissions.ESS_REQUEST).personId(),
                req.toAttributes()));
    }

    @PostMapping(value = "/family-members/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PeoplePermissions.ESS_REQUEST)
    public ResponseEntity<Documents.DocumentView> upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
            @RequestParam(name = "title", required = false) String title) throws IOException {
        UUID person = self.require(PeoplePermissions.ESS_REQUEST).personId();
        return ResponseEntity.status(201).body(evidence.upload(person, id, true, title, file.getOriginalFilename(),
                file.getContentType(), file.getBytes()));
    }

    @GetMapping("/dependants")
    @RequiresPermission(PeoplePermissions.ESS_VIEW)
    public List<DependantView> myDependants() {
        return dependants.listOwn(self.require(PeoplePermissions.ESS_VIEW).personId());
    }

    @PostMapping("/dependants/validate")
    @RequiresPermission(PeoplePermissions.ESS_REQUEST)
    public EligibilityResult validate(@Valid @RequestBody RegistrationRequest req) {
        return dependants.validateOwn(self.require(PeoplePermissions.ESS_REQUEST).personId(), req.toRegistration());
    }

    @PostMapping("/dependants")
    @RequiresPermission(PeoplePermissions.ESS_REQUEST)
    public ResponseEntity<DependantView> request(@Valid @RequestBody RegistrationRequest req) {
        return ResponseEntity.status(201).body(dependants.registerOwn(
                self.require(PeoplePermissions.ESS_REQUEST).personId(), req.toRegistration()));
    }

    @PostMapping("/dependants/{id}/end")
    @RequiresPermission(PeoplePermissions.ESS_REQUEST)
    public DependantView requestEnd(@PathVariable UUID id, @Valid @RequestBody EndRequest req) {
        return dependants.requestEndOwn(self.require(PeoplePermissions.ESS_REQUEST).personId(), id,
                req.toMonth().atDay(1), req.version(), req.evidenceDocumentIds());
    }
}
